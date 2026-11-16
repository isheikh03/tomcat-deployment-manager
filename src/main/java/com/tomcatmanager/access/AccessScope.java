package com.tomcatmanager.access;

import com.tomcatmanager.access.AccessGrant.Level;
import com.tomcatmanager.users.Role;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * What one signed-in user may see and do, computed once per request by {@link Access}.
 *
 * <ul>
 *   <li>A super user: everything.</li>
 *   <li>An administrator: everything in the teams assigned to them (their Linux hosts, the Tomcat instances on those
 *       hosts, their applications and server groups); they deploy to all of those instances.</li>
 *   <li>A deployment operator or viewer: the Tomcat instances granted to them, each at its level (DEPLOY or VIEW;
 *       a viewer's level is always VIEW), plus read access to the hosts and teams those instances belong to.</li>
 * </ul>
 *
 * Callers use "can view" to decide whether something exists for the user at all (otherwise: not found) and the
 * stronger checks to decide whether an action is allowed (otherwise: forbidden).
 */
public final class AccessScope {
    private final String username;
    private final Role role;
    private final boolean everything;
    private final Set<Long> managedTeams;
    private final Map<Long, Level> instances;
    private final Set<Long> visibleHosts;
    private final Set<Long> visibleTeams;
    private final Set<Long> deployTeams;

    AccessScope(String username, Role role, boolean everything, Set<Long> managedTeams, Map<Long, Level> instances,
                Set<Long> visibleHosts, Set<Long> visibleTeams, Set<Long> deployTeams) {
        this.username = username; this.role = role; this.everything = everything;
        this.managedTeams = Set.copyOf(managedTeams); this.instances = Map.copyOf(instances);
        this.visibleHosts = Set.copyOf(visibleHosts); this.visibleTeams = Set.copyOf(visibleTeams); this.deployTeams = Set.copyOf(deployTeams);
    }

    /** Full access, as a super user has it. */
    public static AccessScope everything(String username) {
        return new AccessScope(username, Role.SUPER_ADMIN, true, Set.of(), Map.of(), Set.of(), Set.of(), Set.of());
    }

    /**
     * A restricted scope built directly (tests; {@link Access} computes real ones). {@code instances}: instance id to level;
     * hosts and teams the user can see; {@code deployTeams}: teams where they can deploy to at least one instance.
     */
    public static AccessScope of(String username, Role role, Set<Long> managedTeams, Map<Long, Level> instances,
                                 Set<Long> visibleHosts, Set<Long> visibleTeams, Set<Long> deployTeams) {
        return new AccessScope(username, role, false, managedTeams, instances, visibleHosts, visibleTeams, deployTeams);
    }

    /** No access at all (unknown or disabled account). */
    public static AccessScope nothing(String username) {
        return new AccessScope(username, Role.VIEWER, false, Set.of(), Map.of(), Set.of(), Set.of(), Set.of());
    }

    public String username() { return username; }
    public Role role() { return role; }
    /** A super user: no restriction at all. */
    public boolean isEverything() { return everything; }
    public boolean isSuperUser() { return everything; }
    /** Super user or administrator. */
    public boolean isAdministrator() { return everything || role.administers(); }
    /** Whether the role may run operations at all (deploy, roll back, test, upload). */
    public boolean mayOperate() { return everything || role != Role.VIEWER; }

    // ---- Teams ----

    public boolean canViewTeam(Long teamId) { return everything || (teamId != null && visibleTeams.contains(teamId)); }
    public boolean canManageTeam(Long teamId) { return everything || (teamId != null && managedTeams.contains(teamId) && role.administers()); }
    /** Tomcat instances the user can see, or null for all (super user). */
    public Set<Long> visibleInstanceIds() { return everything ? null : Collections.unmodifiableSet(instances.keySet()); }

    /** Teams whose data the user can see, or null for all (super user). */
    public Set<Long> visibleTeamIds() { return everything ? null : Collections.unmodifiableSet(visibleTeams); }

    /** Teams the user administers (empty for a super user, who administers all: check {@link #isEverything()}). */
    public Set<Long> managedTeams() { return Collections.unmodifiableSet(managedTeams); }

    // ---- Linux hosts ----

    public boolean canViewHost(Long hostId, Long teamId) { return everything || canManageTeam(teamId) || (hostId != null && visibleHosts.contains(hostId)); }
    public boolean canManageHost(Long teamId) { return canManageTeam(teamId); }

    // ---- Tomcat instances ----

    public boolean canViewInstance(Long instanceId) { return everything || (instanceId != null && instances.containsKey(instanceId)); }
    /** Deploy, roll back, run connection and health checks on the instance. */
    public boolean canDeployTo(Long instanceId) { return mayOperate() && (everything || (instanceId != null && instances.get(instanceId) == Level.DEPLOY)); }
    /** Change the instance's configuration and credentials: administrators of its host's team. */
    public boolean canManageInstance(Long hostTeamId) { return canManageTeam(hostTeamId); }
    /** The level on an instance, or null when it is not visible. */
    public Level levelOn(Long instanceId) { return everything ? Level.DEPLOY : instances.get(instanceId); }

    // ---- Applications and releases ----

    public boolean canViewApplication(Long teamId) { return canViewTeam(teamId); }
    public boolean canManageApplication(Long teamId) { return canManageTeam(teamId); }
    /** Upload releases of an application: someone who can deploy to at least one instance of its team. */
    public boolean canUploadTo(Long applicationTeamId) {
        return mayOperate() && (everything || canManageTeam(applicationTeamId) || (applicationTeamId != null && deployTeams.contains(applicationTeamId)));
    }
}
