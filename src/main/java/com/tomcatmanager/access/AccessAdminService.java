package com.tomcatmanager.access;

import com.tomcatmanager.access.AccessGrant.Level;
import com.tomcatmanager.access.AccessGrant.Scope;
import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.common.DisplayTime;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.ServerGroupRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.users.AppUser;
import com.tomcatmanager.users.AppUserRepository;
import com.tomcatmanager.users.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Teams, the teams assigned to administrators, and the instance grants of operators and viewers: listing them,
 * changing them (every change is audited) and answering "who has access" for a team, host or instance.
 *
 * <p>Rules: only super users create, rename and delete teams. An administrator assigns only the teams they administer
 * and grants only on instances of those teams, so nobody gives more access than they have. A viewer's grants are always
 * View. Callers check that the actor may manage the target account at all.
 */
@Service
public class AccessAdminService {
    private final TeamRepository teams;
    private final UserTeamRepository userTeams;
    private final AccessGrantRepository grants;
    private final ManagedHostRepository hosts;
    private final TomcatInstanceRepository instances;
    private final ApplicationRepository applications;
    private final ServerGroupRepository groups;
    private final AppUserRepository users;
    private final AuditService audit;
    private final ZoneId zone;

    public AccessAdminService(TeamRepository teams, UserTeamRepository userTeams, AccessGrantRepository grants, ManagedHostRepository hosts,
                              TomcatInstanceRepository instances, ApplicationRepository applications, ServerGroupRepository groups,
                              AppUserRepository users, AuditService audit, DisplayTime time) {
        this.teams = teams; this.userTeams = userTeams; this.grants = grants; this.hosts = hosts; this.instances = instances;
        this.applications = applications; this.groups = groups; this.users = users; this.audit = audit; this.zone = ZoneId.of(time.zone());
    }

    // ================= Teams =================

    /** One line of the team list. */
    public record TeamRow(Team team, long hosts, long instances, long applications, long groups, List<String> administrators, long grantees) { }

    /** The teams the user administers (a super user: all), with what they contain and who works in them. */
    @Transactional(readOnly = true)
    public List<TeamRow> teamRows(AccessScope s) {
        Map<Long, Long> hostTeam = hostTeams();
        Map<Long, Long> instanceCount = new HashMap<>();
        instances.findAllByOrderByNameAsc().forEach(i -> instanceCount.merge(hostTeam.get(i.getHost().getId()), 1L, Long::sum));
        Map<Long, String> names = usernames();
        Map<Long, List<String>> admins = new HashMap<>();
        userTeams.findAll().forEach(t -> admins.computeIfAbsent(t.getTeamId(), k -> new ArrayList<>()).add(names.getOrDefault(t.getUserId(), "?")));
        Map<Long, Set<Long>> grantees = new HashMap<>();
        Map<Long, Long> instTeam = instanceTeams(hostTeam);
        Instant now = Instant.now();
        for (AccessGrant g : grants.findAll())
            if (g.activeAt(now)) {
                Long team = teamOf(g, hostTeam, instTeam);
                if (team != null) grantees.computeIfAbsent(team, k -> new HashSet<>()).add(g.getUserId());
            }
        return teams.findAllByOrderByNameAsc().stream().filter(t -> s.canManageTeam(t.getId()))
                .map(t -> new TeamRow(t, hosts.countByTeamId(t.getId()), instanceCount.getOrDefault(t.getId(), 0L),
                        applications.countByTeamId(t.getId()), groups.countByTeamId(t.getId()),
                        admins.getOrDefault(t.getId(), List.of()).stream().sorted().toList(), grantees.getOrDefault(t.getId(), Set.of()).size()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Team team(long id) { return teams.findById(id).orElseThrow(() -> new NoSuchElementException("Team " + id + " not found.")); }

    @Transactional(readOnly = true)
    public List<Team> allTeams() { return teams.findAllByOrderByNameAsc(); }

    /** Why the name cannot be used, or null. */
    @Transactional(readOnly = true)
    public String teamNameProblem(Long id, String name) {
        String n = name == null ? "" : name.strip();
        if (n.length() < 2 || n.length() > 100) return "Use 2–100 characters.";
        Optional<Team> same = teams.findAll().stream().filter(t -> t.getName().equalsIgnoreCase(n)).findFirst();
        if (same.isPresent() && !same.get().getId().equals(id)) return "Another team already has this name.";
        return null;
    }

    @Transactional
    public Team saveTeam(Long id, String name, String description, String actor) {
        String problem = teamNameProblem(id, name);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (description != null && description.strip().length() > 500) throw new IllegalArgumentException("The description is limited to 500 characters.");
        if (id == null) {
            Team saved = teams.save(new Team(name, description, actor));
            audit.success("TEAM_CREATED", "TEAM", saved.getId(), "Created team " + saved.getName() + ".");
            return saved;
        }
        Team team = team(id);
        String old = team.getName();
        team.setName(name); team.setDescription(description);
        Team saved = teams.save(team);
        audit.success("TEAM_UPDATED", "TEAM", saved.getId(), old.equals(saved.getName())
                ? "Updated team " + saved.getName() + "." : "Renamed team " + old + " to " + saved.getName() + ".");
        return saved;
    }

    /** Deletes an empty team; its administrator assignments and grants go with it. */
    @Transactional
    public void deleteTeam(long id) {
        Team team = team(id);
        if (team.isDefaultTeam()) throw new IllegalStateException("The default team cannot be deleted; rename it instead.");
        long h = hosts.countByTeamId(id), a = applications.countByTeamId(id), g = groups.countByTeamId(id);
        if (h + a + g > 0) {
            List<String> parts = new ArrayList<>();
            if (h > 0) parts.add(h + " Linux host" + (h == 1 ? "" : "s"));
            if (a > 0) parts.add(a + " application" + (a == 1 ? "" : "s"));
            if (g > 0) parts.add(g + " server group" + (g == 1 ? "" : "s"));
            throw new IllegalStateException("Team " + team.getName() + " still owns " + String.join(", ", parts) + ". Delete those first.");
        }
        userTeams.deleteAll(userTeams.findByTeamId(id));
        grants.deleteAll(grants.findByTeamId(id));
        teams.delete(team);
        audit.success("TEAM_DELETED", "TEAM", id, "Deleted team " + team.getName() + ".");
    }

    // ================= One user's access =================

    /** A grant as shown to people: what it covers and in which team. */
    public record GrantView(AccessGrant grant, String target, Long teamId, String teamName, boolean active) {
        public String scopeLabel() {
            return switch (grant.getScope()) { case TEAM -> "Team"; case HOST -> "Linux host"; case INSTANCE -> "Tomcat instance"; };
        }
    }

    /** A choice in the "give access to" list: value {@code TEAM:id}, {@code HOST:id} or {@code INSTANCE:id}. */
    public record Option(String value, String label) { }

    public record GrantOptions(List<Option> teams, List<Option> hosts, List<Option> instances) {
        public boolean isEmpty() { return teams.isEmpty() && hosts.isEmpty() && instances.isEmpty(); }
    }

    /** The teams assigned to an administrator. */
    @Transactional(readOnly = true)
    public List<Team> teamsOf(long userId) {
        Set<Long> ids = userTeams.findByUserId(userId).stream().map(UserTeam::getTeamId).collect(Collectors.toSet());
        return teams.findAllByOrderByNameAsc().stream().filter(t -> ids.contains(t.getId())).toList();
    }

    @Transactional(readOnly = true)
    public List<GrantView> grantsOf(long userId) {
        Labels l = labels();
        Instant now = Instant.now();
        return grants.findByUserId(userId).stream().map(g -> view(g, l, now))
                .sorted(Comparator.comparing((GrantView v) -> v.teamName() == null ? "" : v.teamName())
                        .thenComparing(v -> v.grant().getScope()).thenComparing(GrantView::target))
                .toList();
    }

    /** The teams the actor can assign to an administrator: all for a super user, otherwise the teams they administer. */
    @Transactional(readOnly = true)
    public List<Team> assignableTeams(AccessScope s) {
        return teams.findAllByOrderByNameAsc().stream().filter(t -> s.canManageTeam(t.getId())).toList();
    }

    /** What the actor can give access to: their teams, those teams' hosts and instances. */
    @Transactional(readOnly = true)
    public GrantOptions grantOptions(AccessScope s) {
        Map<Long, String> teamNames = teamNames();
        List<Option> t = teams.findAllByOrderByNameAsc().stream().filter(x -> s.canManageTeam(x.getId()))
                .map(x -> new Option("TEAM:" + x.getId(), "All instances of team " + x.getName())).toList();
        List<Option> h = hosts.findAllByOrderByNameAsc().stream().filter(x -> s.canManageTeam(x.getTeamId()))
                .map(x -> new Option("HOST:" + x.getId(), "All instances on " + x.getName() + " (" + teamNames.get(x.getTeamId()) + ")")).toList();
        List<Option> i = instances.findAllByOrderByNameAsc().stream().filter(x -> s.canManageTeam(x.getHost().getTeamId()))
                .map(x -> new Option("INSTANCE:" + x.getId(), x.getName() + " on " + x.getHost().getName() + " (" + teamNames.get(x.getHost().getTeamId()) + ")")).toList();
        return new GrantOptions(t, h, i);
    }

    /**
     * Sets the teams of an administrator. Only the actor's own teams change: a team the actor does not administer
     * is neither added nor removed.
     */
    @Transactional
    public void setAdminTeams(AppUser target, Collection<Long> requested, AccessScope s) {
        if (target.getRole() != Role.ADMIN) throw new IllegalArgumentException("Teams are assigned to administrators. Give operators and viewers instance access instead.");
        Set<Long> wanted = new HashSet<>(requested == null ? List.of() : requested);
        Map<Long, UserTeam> current = new HashMap<>();
        userTeams.findByUserId(target.getId()).forEach(t -> current.put(t.getTeamId(), t));
        Map<Long, String> names = teamNames();
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        for (Long teamId : wanted) {
            if (!names.containsKey(teamId)) throw new NoSuchElementException("Team " + teamId + " not found.");
            if (!s.canManageTeam(teamId)) throw new NotPermittedException("You can only assign teams you administer.");
            if (!current.containsKey(teamId)) { userTeams.save(new UserTeam(target.getId(), teamId, s.username())); added.add(names.get(teamId)); }
        }
        for (var e : current.entrySet())
            if (!wanted.contains(e.getKey()) && s.canManageTeam(e.getKey())) { userTeams.delete(e.getValue()); removed.add(names.get(e.getKey())); }
        if (added.isEmpty() && removed.isEmpty()) return;
        StringBuilder summary = new StringBuilder("Changed the teams of administrator " + target.getUsername() + ":");
        if (!added.isEmpty()) summary.append(" added ").append(String.join(", ", added)).append(';');
        if (!removed.isEmpty()) summary.append(" removed ").append(String.join(", ", removed)).append(';');
        audit.success("ACCESS_TEAMS_CHANGED", "USER", target.getUsername(), summary.toString());
    }

    /**
     * Gives an operator or viewer access to a team, host or instance ({@code TEAM:id}, {@code HOST:id}, {@code INSTANCE:id}).
     * A second grant on the same target replaces the level, expiry and reason of the first.
     *
     * @return true when a new grant was made, false when an existing one was updated
     */
    @Transactional
    public boolean grant(AppUser target, String what, Level level, LocalDate expires, String reason, AccessScope s) {
        if (target.getRole() != Role.DEPLOYMENT_OPERATOR && target.getRole() != Role.VIEWER)
            throw new IllegalArgumentException("Only deployment operators and viewers get instance access; administrators get teams.");
        if (level == null) throw new IllegalArgumentException("Choose Deploy or View.");
        if (target.getRole() == Role.VIEWER && level == Level.DEPLOY)
            throw new IllegalArgumentException("Viewers can only get View access. Make them a deployment operator to let them deploy.");
        if (expires != null && expires.isBefore(LocalDate.now(zone))) throw new IllegalArgumentException("The expiry date is in the past.");
        if (reason != null && reason.strip().length() > 300) throw new IllegalArgumentException("The reason is limited to 300 characters.");

        String[] parts = what == null ? new String[0] : what.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Choose what to give access to.");
        Scope scope;
        long id;
        try { scope = Scope.valueOf(parts[0]); id = Long.parseLong(parts[1]); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Choose what to give access to."); }

        Labels l = labels();
        Long team = switch (scope) {
            case TEAM -> l.teams.containsKey(id) ? id : null;
            case HOST -> l.hostTeam.get(id);
            case INSTANCE -> l.instanceTeam.get(id);
        };
        if (team == null) throw new NoSuchElementException("That team, host or instance no longer exists.");
        if (!s.canManageTeam(team)) throw new NotPermittedException("You can only give access in teams you administer.");

        Instant expiresAt = expires == null ? null : expires.plusDays(1).atStartOfDay(zone).toInstant();
        AccessGrant existing = grants.findByUserId(target.getId()).stream().filter(g -> g.getScope() == scope && id == switch (scope) {
            case TEAM -> nz(g.getTeamId()); case HOST -> nz(g.getHostId()); case INSTANCE -> nz(g.getInstanceId());
        }).findFirst().orElse(null);
        AccessGrant g = existing != null ? existing : switch (scope) {
            case TEAM -> AccessGrant.toTeam(target.getId(), id, level, s.username());
            case HOST -> AccessGrant.toHost(target.getId(), id, level, s.username());
            case INSTANCE -> AccessGrant.toInstance(target.getId(), id, level, s.username());
        };
        g.setLevel(level); g.setExpiresAt(expiresAt); g.setReason(reason);
        grants.save(g);
        GrantView v = view(g, l, Instant.now());
        audit.success(existing == null ? "ACCESS_GRANTED" : "ACCESS_CHANGED", "USER", target.getUsername(),
                (existing == null ? "Gave " : "Changed the access of ") + target.getUsername() + " " + level.label().toLowerCase(Locale.ROOT)
                        + " access to " + describe(v) + (expires == null ? "" : " until " + expires) + (g.getReason() == null ? "." : " (" + g.getReason() + ")."));
        return existing == null;
    }

    @Transactional
    public void revoke(AppUser target, long grantId, AccessScope s) {
        AccessGrant g = grants.findById(grantId).filter(x -> x.getUserId().equals(target.getId()))
                .orElseThrow(() -> new NoSuchElementException("Access " + grantId + " not found."));
        Labels l = labels();
        GrantView v = view(g, l, Instant.now());
        // A grant whose target is gone has no team; only a super user removes it.
        if (v.teamId() == null ? !s.isEverything() : !s.canManageTeam(v.teamId()))
            throw new NotPermittedException("You can only remove access in teams you administer.");
        grants.delete(g);
        audit.success("ACCESS_REVOKED", "USER", target.getUsername(), "Removed the " + g.getLevel().label().toLowerCase(Locale.ROOT)
                + " access of " + target.getUsername() + " to " + describe(v) + ".");
    }

    /**
     * Removes access that no longer fits a changed role: administrators keep only teams, operators and viewers only
     * grants, super users neither (they have everything).
     */
    @Transactional
    public void roleChanged(AppUser user, Role oldRole) {
        if (oldRole == user.getRole()) return;
        List<UserTeam> t = user.getRole() == Role.ADMIN ? List.of() : userTeams.findByUserId(user.getId());
        List<AccessGrant> g = user.getRole().administers() ? grants.findByUserId(user.getId()) : List.of();
        if (t.isEmpty() && g.isEmpty()) return;
        userTeams.deleteAll(t);
        grants.deleteAll(g);
        audit.success("ACCESS_CLEARED", "USER", user.getUsername(), "Role of " + user.getUsername() + " changed to " + user.getRole().label()
                + ": removed " + (t.isEmpty() ? "" : t.size() + " team assignment(s)") + (t.isEmpty() || g.isEmpty() ? "" : " and ")
                + (g.isEmpty() ? "" : g.size() + " instance grant(s)") + " that no longer apply.");
    }

    // ================= Who has access =================

    /** Someone with access to a team, host or instance, and how they got it. Super users are not listed (they have everything). */
    public record Holder(Long userId, String username, String label, Role role, String via, Level level, Instant expiresAt) { }

    @Transactional(readOnly = true)
    public List<Holder> holdersOfTeam(long teamId) {
        Labels l = labels();
        return holders(teamId, g -> teamId == nz(teamOf(g, l.hostTeam, l.instanceTeam)), l);
    }

    @Transactional(readOnly = true)
    public List<Holder> holdersOfHost(ManagedHost host) {
        Labels l = labels();
        long id = host.getId();
        return holders(host.getTeamId(), g -> switch (g.getScope()) {
            case TEAM -> Objects.equals(g.getTeamId(), host.getTeamId());
            case HOST -> id == nz(g.getHostId());
            case INSTANCE -> Objects.equals(l.instanceHost.get(g.getInstanceId()), id);
        }, l);
    }

    @Transactional(readOnly = true)
    public List<Holder> holdersOfInstance(TomcatInstance instance) {
        Labels l = labels();
        Long hostId = instance.getHost().getId(), teamId = l.hostTeam.get(hostId);
        return holders(teamId, g -> switch (g.getScope()) {
            case TEAM -> Objects.equals(g.getTeamId(), teamId);
            case HOST -> Objects.equals(g.getHostId(), hostId);
            case INSTANCE -> Objects.equals(g.getInstanceId(), instance.getId());
        }, l);
    }

    private List<Holder> holders(Long teamId, Predicate<AccessGrant> covers, Labels l) {
        Map<Long, AppUser> byId = new HashMap<>();
        users.findAll().stream().filter(AppUser::isEnabled).forEach(u -> byId.put(u.getId(), u));
        List<Holder> result = new ArrayList<>();
        if (teamId != null)
            for (UserTeam t : userTeams.findByTeamId(teamId)) {
                AppUser u = byId.get(t.getUserId());
                if (u != null && u.getRole() == Role.ADMIN)
                    result.add(new Holder(u.getId(), u.getUsername(), u.getLabel(), u.getRole(), "Administrator of team " + l.teams.get(teamId), Level.DEPLOY, null));
            }
        Instant now = Instant.now();
        for (AccessGrant g : grants.findAll()) {
            AppUser u = byId.get(g.getUserId());
            if (u == null || u.getRole().administers() || !g.activeAt(now) || !covers.test(g)) continue;
            Level level = u.getRole() == Role.VIEWER ? Level.VIEW : g.getLevel();
            result.add(new Holder(u.getId(), u.getUsername(), u.getLabel(), u.getRole(), describe(view(g, l, now)), level, g.getExpiresAt()));
        }
        result.sort(Comparator.comparing(Holder::username).thenComparing(Holder::via));
        return result;
    }

    // ================= Helpers =================

    /** Names and teams of everything a grant can point at. */
    private record Labels(Map<Long, String> teams, Map<Long, Long> hostTeam, Map<Long, String> hostName,
                          Map<Long, Long> instanceTeam, Map<Long, Long> instanceHost, Map<Long, String> instanceName) { }

    private Labels labels() {
        Map<Long, Long> hostTeam = new HashMap<>(), instanceTeam = new HashMap<>(), instanceHost = new HashMap<>();
        Map<Long, String> hostName = new HashMap<>(), instanceName = new HashMap<>();
        hosts.findAll().forEach(h -> { hostTeam.put(h.getId(), h.getTeamId()); hostName.put(h.getId(), h.getName()); });
        for (TomcatInstance i : instances.findAllByOrderByNameAsc()) {
            Long hostId = i.getHost().getId();
            instanceTeam.put(i.getId(), hostTeam.get(hostId)); instanceHost.put(i.getId(), hostId);
            instanceName.put(i.getId(), i.getName() + " on " + hostName.get(hostId));
        }
        return new Labels(teamNames(), hostTeam, hostName, instanceTeam, instanceHost, instanceName);
    }

    private GrantView view(AccessGrant g, Labels l, Instant now) {
        Long team = teamOf(g, l.hostTeam, l.instanceTeam);
        String target = switch (g.getScope()) {
            case TEAM -> l.teams.getOrDefault(g.getTeamId(), "(deleted team)");
            case HOST -> l.hostName.getOrDefault(g.getHostId(), "(deleted host)");
            case INSTANCE -> l.instanceName.getOrDefault(g.getInstanceId(), "(deleted instance)");
        };
        return new GrantView(g, target, team, team == null ? null : l.teams.get(team), g.activeAt(now));
    }

    private static String describe(GrantView v) {
        return switch (v.grant().getScope()) {
            case TEAM -> "all instances of team " + v.target();
            case HOST -> "all instances on host " + v.target();
            case INSTANCE -> "instance " + v.target();
        };
    }

    private static Long teamOf(AccessGrant g, Map<Long, Long> hostTeam, Map<Long, Long> instanceTeam) {
        return switch (g.getScope()) {
            case TEAM -> g.getTeamId();
            case HOST -> hostTeam.get(g.getHostId());
            case INSTANCE -> instanceTeam.get(g.getInstanceId());
        };
    }

    private Map<Long, Long> hostTeams() {
        Map<Long, Long> m = new HashMap<>();
        hosts.findAll().forEach(h -> m.put(h.getId(), h.getTeamId()));
        return m;
    }

    private Map<Long, Long> instanceTeams(Map<Long, Long> hostTeam) {
        Map<Long, Long> m = new HashMap<>();
        instances.findAllByOrderByNameAsc().forEach(i -> m.put(i.getId(), hostTeam.get(i.getHost().getId())));
        return m;
    }

    private Map<Long, String> teamNames() {
        Map<Long, String> m = new HashMap<>();
        teams.findAll().forEach(t -> m.put(t.getId(), t.getName()));
        return m;
    }

    private Map<Long, String> usernames() {
        Map<Long, String> m = new HashMap<>();
        users.findAll().forEach(u -> m.put(u.getId(), u.getUsername()));
        return m;
    }

    private static long nz(Long v) { return v == null ? Long.MIN_VALUE : v; }
}
