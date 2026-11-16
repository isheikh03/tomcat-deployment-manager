package com.tomcatmanager.access;

import com.tomcatmanager.access.AccessGrant.Level;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.users.AppUser;
import com.tomcatmanager.users.AppUserRepository;
import com.tomcatmanager.users.Role;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The access of the signed-in user ({@link #current()}), computed from their role, the teams assigned to them and their
 * instance grants. Computed once per request. An account that is unknown or disabled gets no access at all.
 */
@Component
public class Access {
    private static final String REQUEST_KEY = Access.class.getName() + ".scope";

    private final AppUserRepository users;
    private final UserTeamRepository userTeams;
    private final AccessGrantRepository grants;
    private final ManagedHostRepository hosts;
    private final TomcatInstanceRepository instances;

    public Access(AppUserRepository users, UserTeamRepository userTeams, AccessGrantRepository grants,
                  ManagedHostRepository hosts, TomcatInstanceRepository instances) {
        this.users = users; this.userTeams = userTeams; this.grants = grants; this.hosts = hosts; this.instances = instances;
    }

    /** The signed-in user's access; no access when nobody is signed in. */
    public AccessScope current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return AccessScope.nothing(null);
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            Object cached = request.getAttribute(REQUEST_KEY, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof AccessScope scope && auth.getName().equals(scope.username())) return scope;
        }
        AccessScope scope = forUser(auth.getName());
        if (request != null) request.setAttribute(REQUEST_KEY, scope, RequestAttributes.SCOPE_REQUEST);
        return scope;
    }

    /**
     * The teams each account belongs to: an administrator's assigned teams; for operators and viewers the teams their
     * active instance grants point into. Super users are not listed (they belong to every team).
     */
    public Map<Long, Set<Long>> teamsByUser() {
        Map<Long, Long> hostTeam = new HashMap<>();
        hosts.findAll().forEach(h -> hostTeam.put(h.getId(), h.getTeamId()));
        Map<Long, Long> instanceTeam = new HashMap<>();
        instances.findAllByOrderByNameAsc().forEach(i -> instanceTeam.put(i.getId(), hostTeam.get(i.getHost().getId())));
        Map<Long, Set<Long>> result = new HashMap<>();
        userTeams.findAll().forEach(t -> result.computeIfAbsent(t.getUserId(), k -> new HashSet<>()).add(t.getTeamId()));
        Instant now = Instant.now();
        for (AccessGrant g : grants.findAll()) {
            if (!g.activeAt(now)) continue;
            Long team = switch (g.getScope()) {
                case TEAM -> g.getTeamId();
                case HOST -> hostTeam.get(g.getHostId());
                case INSTANCE -> instanceTeam.get(g.getInstanceId());
            };
            if (team != null) result.computeIfAbsent(g.getUserId(), k -> new HashSet<>()).add(team);
        }
        return result;
    }

    /** The access of the given account, as it is now. */
    public AccessScope forUser(String username) {
        AppUser user = username == null ? null : users.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null || !user.isEnabled()) return AccessScope.nothing(username);
        if (user.getRole() == Role.SUPER_ADMIN) return AccessScope.everything(user.getUsername());

        Set<Long> managedTeams = new HashSet<>();
        if (user.getRole() == Role.ADMIN) userTeams.findByUserId(user.getId()).forEach(t -> managedTeams.add(t.getTeamId()));

        List<ManagedHost> allHosts = hosts.findAll();
        List<TomcatInstance> allInstances = instances.findAllByOrderByNameAsc(); // loads each host: no lazy loading outside a transaction
        Map<Long, Long> hostTeam = new HashMap<>();
        allHosts.forEach(h -> hostTeam.put(h.getId(), h.getTeamId()));

        Map<Long, Level> levels = new HashMap<>();
        // Administrators deploy to every instance of their teams.
        for (TomcatInstance i : allInstances)
            if (managedTeams.contains(hostTeam.get(i.getHost().getId()))) levels.put(i.getId(), Level.DEPLOY);

        // Instance grants: one instance, all of a host, or all of a team; host and team grants include instances added later.
        Instant now = Instant.now();
        boolean viewer = user.getRole() == Role.VIEWER;
        for (AccessGrant g : grants.findByUserId(user.getId())) {
            if (!g.activeAt(now)) continue;
            Level level = viewer ? Level.VIEW : g.getLevel();
            for (TomcatInstance i : allInstances) {
                Long hostId = i.getHost().getId();
                boolean covered = switch (g.getScope()) {
                    case INSTANCE -> i.getId().equals(g.getInstanceId());
                    case HOST -> hostId.equals(g.getHostId());
                    case TEAM -> g.getTeamId() != null && g.getTeamId().equals(hostTeam.get(hostId));
                };
                if (covered) levels.merge(i.getId(), level, (a, b) -> a == Level.DEPLOY || b == Level.DEPLOY ? Level.DEPLOY : Level.VIEW);
            }
        }

        Set<Long> visibleHosts = new HashSet<>(), visibleTeams = new HashSet<>(managedTeams), deployTeams = new HashSet<>();
        allHosts.stream().filter(h -> managedTeams.contains(h.getTeamId())).forEach(h -> visibleHosts.add(h.getId()));
        for (TomcatInstance i : allInstances) {
            Level level = levels.get(i.getId());
            if (level == null) continue;
            Long hostId = i.getHost().getId(), teamId = hostTeam.get(hostId);
            visibleHosts.add(hostId);
            if (teamId != null) {
                visibleTeams.add(teamId);
                if (level == Level.DEPLOY) deployTeams.add(teamId);
            }
        }
        return new AccessScope(user.getUsername(), user.getRole(), false, managedTeams, levels, visibleHosts, visibleTeams, deployTeams);
    }
}
