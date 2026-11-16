package com.tomcatmanager.infrastructure;

import com.tomcatmanager.access.Access;
import com.tomcatmanager.access.AccessScope;
import com.tomcatmanager.access.NotPermittedException;
import com.tomcatmanager.access.Team;
import com.tomcatmanager.access.TeamRepository;
import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.connectivity.ConnectivityStatus;
import com.tomcatmanager.connectivity.ConnectivityStatus.State;
import com.tomcatmanager.common.ListView;
import com.tomcatmanager.security.SecretEncryptionService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/infrastructure")
public class InfrastructureController {
    private final ManagedHostRepository hosts;
    private final TomcatInstanceRepository instances;
    private final ServerGroupRepository groups;
    private final HostService hostService;
    private final InstanceService instanceService;
    private final ConnectionTestService connectionTests;
    private final SecretEncryptionService encryption;
    private final com.tomcatmanager.deployment.DeploymentTargetRepository deploymentTargets;
    private final ServerGroupService groupService;
    private final InfrastructureAudit audit;
    private final Access access;
    private final TeamRepository teams;
    private final com.tomcatmanager.access.AccessAdminService accessAdmin;

    public InfrastructureController(ManagedHostRepository hosts, TomcatInstanceRepository instances, ServerGroupRepository groups,
                                    HostService hostService, InstanceService instanceService, ConnectionTestService connectionTests,
                                    SecretEncryptionService encryption, com.tomcatmanager.deployment.DeploymentTargetRepository deploymentTargets,
                                    ServerGroupService groupService, InfrastructureAudit audit, Access access, TeamRepository teams,
                                    com.tomcatmanager.access.AccessAdminService accessAdmin) {
        this.hosts = hosts; this.instances = instances; this.groups = groups; this.hostService = hostService;
        this.instanceService = instanceService; this.connectionTests = connectionTests; this.encryption = encryption;
        this.deploymentTargets = deploymentTargets; this.groupService = groupService; this.audit = audit;
        this.access = access; this.teams = teams; this.accessAdmin = accessAdmin;
    }

    // ---- Access: what the signed-in user sees (not found otherwise) and may change (forbidden otherwise) ----

    private List<ManagedHost> visibleHosts(AccessScope s) {
        return hosts.findAllByOrderByNameAsc().stream().filter(h -> s.canViewHost(h.getId(), h.getTeamId())).toList();
    }

    private List<TomcatInstance> visibleInstances(AccessScope s) {
        return instances.findAllByOrderByNameAsc().stream().filter(i -> s.canViewInstance(i.getId())).toList();
    }

    private List<ServerGroup> visibleGroups(AccessScope s) {
        return groups.findAll().stream().filter(g -> s.canViewTeam(g.getTeamId())).toList();
    }

    /** Teams in which the user may create hosts and groups. */
    private List<Team> manageableTeams(AccessScope s) {
        return teams.findAllByOrderByNameAsc().stream().filter(t -> s.canManageTeam(t.getId())).toList();
    }

    private ManagedHost viewableHost(Long id, AccessScope s) {
        ManagedHost h = hostService.find(id);
        if (!s.canViewHost(h.getId(), h.getTeamId())) throw new java.util.NoSuchElementException("Host " + id + " not found.");
        return h;
    }

    private ManagedHost manageableHost(Long id, AccessScope s) {
        ManagedHost h = viewableHost(id, s);
        if (!s.canManageHost(h.getTeamId())) throw new NotPermittedException("Only administrators of this host's team can change it.");
        return h;
    }

    private TomcatInstance viewableInstance(Long id, AccessScope s) {
        TomcatInstance i = instanceService.find(id);
        if (!s.canViewInstance(i.getId())) throw new java.util.NoSuchElementException("Tomcat instance " + id + " not found.");
        return i;
    }

    private TomcatInstance manageableInstance(Long id, AccessScope s) {
        TomcatInstance i = viewableInstance(id, s);
        if (!s.canManageInstance(i.getHost().getTeamId())) throw new NotPermittedException("Only administrators of this instance's team can change it.");
        return i;
    }

    private ServerGroup viewableGroup(Long id, AccessScope s) {
        ServerGroup g = groupService.find(id);
        if (!s.canViewTeam(g.getTeamId())) throw new java.util.NoSuchElementException("Server group " + id + " not found.");
        return g;
    }

    private ServerGroup manageableGroup(Long id, AccessScope s) {
        ServerGroup g = viewableGroup(id, s);
        if (!s.canManageTeam(g.getTeamId())) throw new NotPermittedException("Only administrators of this group's team can change it.");
        return g;
    }

    private void requireSomeTeam(AccessScope s, String what) {
        if (manageableTeams(s).isEmpty()) throw new NotPermittedException("You need a team to " + what + ". Ask a super user to assign one to you.");
    }

    /** The infrastructure section starts at the Linux hosts list (old links and bookmarks keep working). */
    @GetMapping public String index() { return "redirect:/infrastructure/hosts"; }

    // ---- Lists with search and filters ----

    @GetMapping("/hosts") public String hostList(@RequestParam(required = false) String q, @RequestParam(required = false) String team, @RequestParam(required = false) String connectivity,
                                                 @RequestParam(required = false) String status, @RequestParam(required = false) String hostKey,
                                                 @RequestParam(required = false) String sort, @RequestParam(required = false) String page,
                                                 @RequestParam(required = false) String size, Model model) {
        AccessScope s = access.current();
        List<ManagedHost> mine = visibleHosts(s);
        Map<Long, ConnectivityStatus> statuses = connectionTests.latestHostStatuses();
        com.tomcatmanager.access.Team teamChoice = teamFilter(team, s, model);
        State state = InfrastructureListing.parseEnum(State.class, connectivity);
        Boolean enabled = InfrastructureListing.parseFlag(status, "enabled", "disabled");
        Boolean pinned = InfrastructureListing.parseFlag(hostKey, "pinned", "unpinned");
        String sortKey = oneOf(sort, "name-desc", "hostname", "problems", "newest");
        List<ManagedHost> filtered = InfrastructureListing.hosts(mine.stream().filter(h -> inTeam(teamChoice, h.getTeamId())).toList(),
                new InfrastructureListing.HostFilter(q, state, enabled, pinned, sortKey), statuses);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", q);
        params.put("team", teamChoice == null ? null : teamChoice.getId().toString());
        params.put("connectivity", state == null ? null : state.name());
        params.put("status", enabled == null ? null : status);
        params.put("hostKey", pinned == null ? null : hostKey);
        params.put("sort", sortKey);
        ListView<ManagedHost> view = ListView.of(filtered, page, size, params)
                .chip("q", "Search: “" + trim(q) + "”")
                .chip("team", teamChoice == null ? "" : "Team: " + teamChoice.getName())
                .chip("connectivity", "Connectivity: " + (state == null ? "" : stateLabel(state)))
                .chip("status", enabled == null ? "" : (enabled ? "Enabled" : "Disabled"))
                .chip("hostKey", pinned == null ? "" : (pinned ? "Host key pinned" : "Host key not pinned"));

        Map<Long, Long> instanceCounts = visibleInstances(s).stream()
                .collect(Collectors.groupingBy(i -> i.getHost().getId(), Collectors.counting()));
        model.addAttribute("pageTitle", "Linux hosts");
        model.addAttribute("view", view);
        model.addAttribute("q", trim(q));
        model.addAttribute("connectivity", state == null ? "" : state.name());
        model.addAttribute("status", enabled == null ? "" : status);
        model.addAttribute("hostKey", pinned == null ? "" : hostKey);
        model.addAttribute("sort", sortKey == null ? "" : sortKey);
        model.addAttribute("totalCount", mine.size());
        model.addAttribute("connectivityOptions", connectivityOptions());
        model.addAttribute("pageSizes", ListView.PAGE_SIZES);
        model.addAttribute("hostStatuses", statuses);
        model.addAttribute("instanceCounts", instanceCounts);
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        model.addAttribute("encryptionConfigured", encryption.isConfigured());
        return "infrastructure/hosts";
    }

    @GetMapping("/instances") public String instanceList(@RequestParam(required = false) String q, @RequestParam(required = false) String team, @RequestParam(required = false) String environment,
                                                         @RequestParam(required = false) String host, @RequestParam(required = false) String method,
                                                         @RequestParam(required = false) String group, @RequestParam(required = false) String connectivity,
                                                         @RequestParam(required = false) String status, @RequestParam(required = false) String sort,
                                                         @RequestParam(required = false) String page, @RequestParam(required = false) String size, Model model) {
        AccessScope s = access.current();
        List<TomcatInstance> mine = visibleInstances(s);
        Map<Long, ConnectivityStatus> statuses = connectionTests.latestInstanceStatuses();
        com.tomcatmanager.access.Team teamChoice = teamFilter(team, s, model);
        // With a team chosen, the host and group choices narrow to that team.
        List<ManagedHost> allHosts = visibleHosts(s).stream().filter(h -> inTeam(teamChoice, h.getTeamId())).toList();
        List<ServerGroup> allGroups = InfrastructureListing.groups(visibleGroups(s), null).stream().filter(g -> inTeam(teamChoice, g.getTeamId())).toList();
        String env = InfrastructureListing.NO_ENVIRONMENT.equals(environment) ? environment
                : (InfrastructureListing.parseEnum(Environment.class, environment) == null ? null : environment.strip());
        Long hostId = InfrastructureListing.parseId(host);
        ManagedHost selectedHost = hostId == null ? null : allHosts.stream().filter(h -> h.getId().equals(hostId)).findFirst().orElse(null);
        Long groupId = InfrastructureListing.parseId(group);
        ServerGroup selectedGroup = groupId == null ? null : allGroups.stream().filter(g -> g.getId().equals(groupId)).findFirst().orElse(null);
        TomcatInstance.DeploymentMethod deploymentMethod = InfrastructureListing.parseEnum(TomcatInstance.DeploymentMethod.class, method);
        State state = InfrastructureListing.parseEnum(State.class, connectivity);
        Boolean enabled = InfrastructureListing.parseFlag(status, "enabled", "disabled");
        String sortKey = oneOf(sort, "name-desc", "host", "environment", "problems", "newest");
        List<TomcatInstance> filtered = InfrastructureListing.instances(mine.stream().filter(i -> inTeam(teamChoice, i.getHost().getTeamId())).toList(),
                new InfrastructureListing.InstanceFilter(q, env, selectedHost == null ? null : hostId, deploymentMethod,
                        selectedGroup == null ? null : groupId, state, enabled, sortKey), statuses);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", q);
        params.put("team", teamChoice == null ? null : teamChoice.getId().toString());
        params.put("environment", env);
        params.put("host", selectedHost == null ? null : hostId.toString());
        params.put("method", deploymentMethod == null ? null : deploymentMethod.name());
        params.put("group", selectedGroup == null ? null : groupId.toString());
        params.put("connectivity", state == null ? null : state.name());
        params.put("status", enabled == null ? null : status);
        params.put("sort", sortKey);
        ListView<TomcatInstance> view = ListView.of(filtered, page, size, params)
                .chip("q", "Search: “" + trim(q) + "”")
                .chip("team", teamChoice == null ? "" : "Team: " + teamChoice.getName())
                .chip("environment", env == null ? "" : "Environment: " + (InfrastructureListing.NO_ENVIRONMENT.equals(env) ? "None" : Environment.valueOf(env).label()))
                .chip("host", selectedHost == null ? "" : "Host: " + selectedHost.getName())
                .chip("method", deploymentMethod == null ? "" : "Method: " + methodLabel(deploymentMethod))
                .chip("group", selectedGroup == null ? "" : "Group: " + selectedGroup.getName())
                .chip("connectivity", state == null ? "" : "Connectivity: " + stateLabel(state))
                .chip("status", enabled == null ? "" : (enabled ? "Enabled" : "Disabled"));

        model.addAttribute("pageTitle", "Tomcat instances");
        model.addAttribute("view", view);
        model.addAttribute("q", trim(q));
        model.addAttribute("environment", env == null ? "" : env);
        model.addAttribute("host", selectedHost == null ? "" : hostId.toString());
        model.addAttribute("method", deploymentMethod == null ? "" : deploymentMethod.name());
        model.addAttribute("group", selectedGroup == null ? "" : groupId.toString());
        model.addAttribute("connectivity", state == null ? "" : state.name());
        model.addAttribute("status", enabled == null ? "" : status);
        model.addAttribute("sort", sortKey == null ? "" : sortKey);
        model.addAttribute("totalCount", mine.size());
        model.addAttribute("hostOptions", allHosts);
        model.addAttribute("groupOptions", allGroups);
        model.addAttribute("environments", Environment.values());
        model.addAttribute("deploymentMethods", TomcatInstance.DeploymentMethod.values());
        model.addAttribute("connectivityOptions", connectivityOptions());
        model.addAttribute("pageSizes", ListView.PAGE_SIZES);
        model.addAttribute("instanceStatuses", statuses);
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        model.addAttribute("encryptionConfigured", encryption.isConfigured());
        return "infrastructure/instances";
    }

    @GetMapping("/groups") public String groupList(@RequestParam(required = false) String q, @RequestParam(required = false) String team, @RequestParam(required = false) String page,
                                                   @RequestParam(required = false) String size, Model model) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", q);
        AccessScope s = access.current();
        List<ServerGroup> mine = visibleGroups(s);
        com.tomcatmanager.access.Team teamChoice = teamFilter(team, s, model);
        params.put("team", teamChoice == null ? null : teamChoice.getId().toString());
        ListView<ServerGroup> view = ListView.of(InfrastructureListing.groups(mine.stream().filter(g -> inTeam(teamChoice, g.getTeamId())).toList(), q), page, size, params)
                .chip("q", "Search: “" + trim(q) + "”")
                .chip("team", teamChoice == null ? "" : "Team: " + teamChoice.getName());
        Map<Long, Long> memberCounts = new HashMap<>();
        for (TomcatInstance i : visibleInstances(s))
            for (ServerGroup g : i.getGroups()) memberCounts.merge(g.getId(), 1L, Long::sum);
        model.addAttribute("pageTitle", "Server groups");
        model.addAttribute("view", view);
        model.addAttribute("q", trim(q));
        model.addAttribute("totalCount", mine.size());
        model.addAttribute("pageSizes", ListView.PAGE_SIZES);
        model.addAttribute("memberCounts", memberCounts);
        return "infrastructure/groups";
    }

    /**
     * The team filter of a list: the teams the user can see, and the chosen one (null: all). Adds teamOptions, teamNames,
     * showTeams (more than one team: show the filter and a Team column) and team to the model.
     */
    private com.tomcatmanager.access.Team teamFilter(String team, AccessScope s, Model model) {
        List<com.tomcatmanager.access.Team> options = teams.findAllByOrderByNameAsc().stream().filter(t -> s.canViewTeam(t.getId())).toList();
        Long id = InfrastructureListing.parseId(team);
        com.tomcatmanager.access.Team selected = id == null ? null : options.stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
        Map<Long, String> names = new HashMap<>();
        options.forEach(t -> names.put(t.getId(), t.getName()));
        model.addAttribute("teamOptions", options);
        model.addAttribute("teamNames", names);
        model.addAttribute("showTeams", options.size() > 1);
        model.addAttribute("team", selected == null ? "" : selected.getId().toString());
        return selected;
    }

    private static boolean inTeam(com.tomcatmanager.access.Team team, Long teamId) { return team == null || team.getId().equals(teamId); }

    private static Map<String, String> connectivityOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        for (State s : List.of(State.FAILED, State.WARNING, State.STALE, State.NEVER_TESTED, State.VERIFIED)) options.put(s.name(), stateLabel(s));
        return options;
    }

    private static String stateLabel(State s) { return new ConnectivityStatus(s, null, null).label(); }

    private static String methodLabel(TomcatInstance.DeploymentMethod m) { return m == TomcatInstance.DeploymentMethod.SSH_SFTP ? "SSH / SFTP" : "Tomcat Manager"; }

    /** The value if it is one of the allowed sort keys, else null (the default order). */
    private static String oneOf(String value, String... allowed) {
        return value != null && Arrays.asList(allowed).contains(value.strip()) ? value.strip() : null;
    }

    private static String trim(String s) { return s == null ? "" : s.strip(); }

    // ---- Hosts ----

    @GetMapping("/hosts/{id}") public String hostDetail(@PathVariable Long id, Model model) {
        AccessScope s = access.current();
        ManagedHost host = viewableHost(id, s);
        model.addAttribute("pageTitle", host.getName());
        model.addAttribute("host", host);
        model.addAttribute("teamName", teams.findById(host.getTeamId()).map(com.tomcatmanager.access.Team::getName).orElse(null));
        model.addAttribute("canManage", s.canManageHost(host.getTeamId()));
        model.addAttribute("canTest", s.canManageHost(host.getTeamId()) || instances.findByHostIdOrderByNameAsc(id).stream().anyMatch(i -> s.canDeployTo(i.getId())));
        if (s.canManageHost(host.getTeamId())) model.addAttribute("holders", accessAdmin.holdersOfHost(host));
        model.addAttribute("instances", instances.findByHostIdOrderByNameAsc(id).stream().filter(i -> s.canViewInstance(i.getId())).toList());
        model.addAttribute("hostStatus", connectionTests.latestHostStatuses().getOrDefault(id, ConnectivityStatus.NEVER));
        model.addAttribute("instanceStatuses", connectionTests.latestInstanceStatuses());
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        return "infrastructure/host-detail";
    }
    @GetMapping("/hosts/new") public String newHost(Model model) {
        AccessScope s = access.current();
        requireSomeTeam(s, "add a Linux host");
        HostForm form = new HostForm();
        List<Team> mine = manageableTeams(s);
        if (mine.size() == 1) form.setTeamId(mine.get(0).getId());
        return hostForm(model, form);
    }
    @GetMapping("/hosts/{id}/edit") public String editHost(@PathVariable Long id, Model model) {
        manageableHost(id, access.current());
        return hostForm(model, hostService.formFor(id));
    }
    @PostMapping("/hosts/save") public String saveHost(@Valid @ModelAttribute("host") HostForm form, BindingResult result, Model model, RedirectAttributes flash) {
        AccessScope s = access.current();
        if (form.getId() != null) form.setTeamId(manageableHost(form.getId(), s).getTeamId()); // a host stays in its team
        else if (form.getTeamId() == null) result.rejectValue("teamId", "required", "Choose the team this host belongs to.");
        else if (!s.canManageTeam(form.getTeamId())) result.rejectValue("teamId", "forbidden", "You cannot add hosts to this team.");
        hostService.validate(form, result);
        if (result.hasErrors()) return hostForm(model, form);
        HostService.HostChange change = hostService.saveWithChanges(form);
        audit.hostSaved(change);
        flash.addFlashAttribute("success", "Host saved. Run a connection test to verify it.");
        return "redirect:/infrastructure/hosts/" + change.host().getId();
    }
    @PostMapping("/hosts/{id}/delete") public String deleteHost(@PathVariable Long id, RedirectAttributes flash) {
        String name = manageableHost(id, access.current()).getName();
        try { hostService.delete(id); audit.hostDeleted(id, name); flash.addFlashAttribute("success", "Host deleted."); }
        catch (IllegalStateException e) { flash.addFlashAttribute("error", e.getMessage()); }
        return "redirect:/infrastructure/hosts";
    }
    private String hostForm(Model model, HostForm form) {
        // Never echo secrets back into the page, even after a validation error.
        form.setSshPassword(null); form.setSshPrivateKey(null); form.setSshKeyPassphrase(null);
        model.addAttribute("pageTitle", form.getId() == null ? "Add Linux host" : "Edit Linux host");
        model.addAttribute("host", form);
        model.addAttribute("authMethods", ManagedHost.SshAuthMethod.values());
        AccessScope s = access.current();
        model.addAttribute("sites", visibleHosts(s));
        model.addAttribute("teams", manageableTeams(s));
        model.addAttribute("hostTeam", form.getTeamId() == null ? null : teams.findById(form.getTeamId()).orElse(null));
        model.addAttribute("hostStatuses", connectionTests.latestHostStatuses());
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        model.addAttribute("encryptionConfigured", encryption.isConfigured());
        return "infrastructure/host-form";
    }

    // ---- Server groups ----

    private String groupForm(Model model, ServerGroup group, String title) {
        model.addAttribute("pageTitle", title);
        model.addAttribute("group", group);
        model.addAttribute("teams", manageableTeams(access.current()));
        model.addAttribute("groupTeam", group.getTeamId() == null ? null : teams.findById(group.getTeamId()).orElse(null));
        return "infrastructure/group-form";
    }
    @GetMapping("/groups/new") public String newGroup(Model model) {
        AccessScope s = access.current();
        requireSomeTeam(s, "create a server group");
        ServerGroup group = new ServerGroup();
        List<Team> mine = manageableTeams(s);
        if (mine.size() == 1) group.setTeamId(mine.get(0).getId());
        return groupForm(model, group, "Create server group");
    }
    @GetMapping("/groups/{id}/edit") public String editGroup(@PathVariable Long id, Model model) {
        return groupForm(model, manageableGroup(id, access.current()), "Edit server group");
    }
    @GetMapping("/groups/{id}") public String groupDetail(@PathVariable Long id, Model model) {
        AccessScope s = access.current();
        ServerGroup group = viewableGroup(id, s);
        boolean canManage = s.canManageTeam(group.getTeamId());
        model.addAttribute("pageTitle", group.getName());
        model.addAttribute("group", group);
        model.addAttribute("teamName", teams.findById(group.getTeamId()).map(com.tomcatmanager.access.Team::getName).orElse(null));
        model.addAttribute("canManage", canManage);
        model.addAttribute("members", groupService.members(id).stream().filter(i -> s.canViewInstance(i.getId())).toList());
        model.addAttribute("candidates", canManage ? groupService.nonMembers(id) : List.of());
        model.addAttribute("instanceStatuses", connectionTests.latestInstanceStatuses());
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        return "infrastructure/group-detail";
    }
    @PostMapping("/groups/save") public String saveGroup(@Valid @ModelAttribute("group") ServerGroup group, BindingResult result, Model model, RedirectAttributes flash) {
        AccessScope s = access.current();
        if (group.getId() != null) group.setTeamId(manageableGroup(group.getId(), s).getTeamId()); // a group stays in its team
        else if (group.getTeamId() == null) result.rejectValue("teamId", "required", "Choose the team this group belongs to.");
        else if (!s.canManageTeam(group.getTeamId())) result.rejectValue("teamId", "forbidden", "You cannot create groups in this team.");
        groupService.validate(group, result);
        if (result.hasErrors()) return groupForm(model, group, "Server group");
        boolean created = group.getId() == null;
        ServerGroup saved = groupService.save(group);
        audit.groupSaved(saved, created);
        flash.addFlashAttribute("success", created ? "Server group created. Add its members below." : "Server group saved.");
        return "redirect:/infrastructure/groups/" + saved.getId();
    }
    @PostMapping("/groups/{id}/delete") public String deleteGroup(@PathVariable Long id, RedirectAttributes flash) {
        String name = manageableGroup(id, access.current()).getName();
        groupService.delete(id); audit.groupDeleted(id, name);
        flash.addFlashAttribute("success", "Server group deleted."); return "redirect:/infrastructure/groups";
    }
    @PostMapping("/groups/{id}/members") public String addMember(@PathVariable Long id, @RequestParam(required = false) Long instanceId, RedirectAttributes flash) {
        AccessScope s = access.current();
        manageableGroup(id, s);
        if (instanceId == null) { flash.addFlashAttribute("error", "Choose a Tomcat instance to add."); return "redirect:/infrastructure/groups/" + id; }
        manageableInstance(instanceId, s);
        try {
            TomcatInstance i = groupService.addMember(id, instanceId);
            audit.membershipChanged(groupService.find(id), i, true);
            flash.addFlashAttribute("success", i.getName() + " added to the group.");
        } catch (IllegalStateException e) { flash.addFlashAttribute("error", e.getMessage()); }
        return "redirect:/infrastructure/groups/" + id;
    }
    @PostMapping("/groups/{id}/members/{instanceId}/remove") public String removeMember(@PathVariable Long id, @PathVariable Long instanceId, RedirectAttributes flash) {
        manageableGroup(id, access.current());
        try {
            TomcatInstance i = groupService.removeMember(id, instanceId);
            audit.membershipChanged(groupService.find(id), i, false);
            flash.addFlashAttribute("success", i.getName() + " removed from the group.");
        } catch (IllegalStateException e) { flash.addFlashAttribute("error", e.getMessage()); }
        return "redirect:/infrastructure/groups/" + id;
    }

    // ---- Tomcat instances ----

    @GetMapping("/instances/{id}") public String instanceDetail(@PathVariable Long id, Model model) {
        AccessScope s = access.current();
        TomcatInstance instance = viewableInstance(id, s);
        model.addAttribute("pageTitle", instance.getName());
        model.addAttribute("instance", instance);
        // The exact restart commands, when a full Tomcat restart is configured (null when none or invalid).
        TomcatRestart.Commands restartCommands = null;
        try { if (instance.restartsTomcat()) restartCommands = TomcatRestart.commandsFor(instance); } catch (IllegalArgumentException ignored) { }
        model.addAttribute("restartCommands", restartCommands);
        model.addAttribute("teamName", teams.findById(instance.getHost().getTeamId()).map(com.tomcatmanager.access.Team::getName).orElse(null));
        model.addAttribute("canManage", s.canManageInstance(instance.getHost().getTeamId()));
        model.addAttribute("canDeploy", s.canDeployTo(instance.getId()));
        model.addAttribute("canTest", s.canDeployTo(instance.getId()));
        if (s.canManageInstance(instance.getHost().getTeamId())) model.addAttribute("holders", accessAdmin.holdersOfInstance(instance));
        model.addAttribute("instanceStatus", connectionTests.latestInstanceStatuses().getOrDefault(id, ConnectivityStatus.NEVER));
        model.addAttribute("currentDeployments", deploymentTargets.findCurrentDeployments(id));
        return "infrastructure/instance-detail";
    }
    @GetMapping("/instances/new") public String newInstance(@RequestParam(required = false) Long host, Model model) {
        AccessScope s = access.current();
        requireSomeTeam(s, "add a Tomcat instance");
        InstanceForm form = new InstanceForm();
        if (host != null && s.canManageHost(viewableHost(host, s).getTeamId())) form.setHostId(host);
        return instanceForm(model, form);
    }
    @GetMapping("/instances/{id}/edit") public String editInstance(@PathVariable Long id, Model model) {
        return instanceForm(model, InstanceForm.from(manageableInstance(id, access.current())));
    }
    @PostMapping("/instances/save") public String saveInstance(@Valid @ModelAttribute("form") InstanceForm f, BindingResult result, Model model, RedirectAttributes flash) {
        AccessScope s = access.current();
        if (f.getId() != null) manageableInstance(f.getId(), s);
        // The (new) host must be one of the user's own hosts: its team is the instance's team.
        if (f.getHostId() != null) {
            ManagedHost target = hosts.findById(f.getHostId()).orElse(null);
            if (target == null || !s.canManageHost(target.getTeamId()))
                result.rejectValue("hostId", "forbidden", "Choose one of the Linux hosts of your teams.");
        }
        instanceService.validate(f, result);
        if (result.hasErrors()) return instanceForm(model, f);
        InstanceService.InstanceChange change = instanceService.saveWithChanges(f);
        audit.instanceSaved(change);
        flash.addFlashAttribute("success", "Tomcat instance saved. Credentials are encrypted at rest; blank secret fields keep existing values.");
        return "redirect:/infrastructure/instances/" + change.instance().getId();
    }
    @PostMapping("/instances/{id}/delete") public String deleteInstance(@PathVariable Long id, RedirectAttributes flash) {
        String name = manageableInstance(id, access.current()).getName();
        instanceService.delete(id); audit.instanceDeleted(id, name);
        flash.addFlashAttribute("success", "Tomcat instance deleted."); return "redirect:/infrastructure/instances";
    }
    private String instanceForm(Model model, InstanceForm form) {
        AccessScope s = access.current();
        form.setManagerSecret(null);
        model.addAttribute("pageTitle", form.getId() == null ? "Add Tomcat instance" : "Edit Tomcat instance");
        model.addAttribute("form", form);
        // Only the user's own hosts, and groups of their teams (the save keeps only groups of the host's team).
        model.addAttribute("hosts", hosts.findAllByOrderByNameAsc().stream().filter(h -> s.canManageHost(h.getTeamId())).toList());
        model.addAttribute("groups", groups.findAll().stream().filter(g -> s.canManageTeam(g.getTeamId())).toList());
        model.addAttribute("deploymentMethods", TomcatInstance.DeploymentMethod.values());
        model.addAttribute("restartModes", TomcatRestart.Mode.values());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("encryptionConfigured", encryption.isConfigured());
        // Offer the Tomcat/Java versions reported by the last Tomcat Manager test, if any.
        if (form.getId() != null) connectionTests.historyForInstance(form.getId(), 0, 5).getContent().stream()
                .map(r -> RuntimeDetection.parse(r.getRemoteServerInfo())).filter(java.util.Objects::nonNull).findFirst()
                .ifPresent(d -> model.addAttribute("detectedRuntime", d));
        return "infrastructure/instance-form";
    }
}
