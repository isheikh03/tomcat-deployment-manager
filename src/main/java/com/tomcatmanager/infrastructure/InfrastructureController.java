package com.tomcatmanager.infrastructure;

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

    public InfrastructureController(ManagedHostRepository hosts, TomcatInstanceRepository instances, ServerGroupRepository groups,
                                    HostService hostService, InstanceService instanceService, ConnectionTestService connectionTests,
                                    SecretEncryptionService encryption, com.tomcatmanager.deployment.DeploymentTargetRepository deploymentTargets,
                                    ServerGroupService groupService, InfrastructureAudit audit) {
        this.hosts = hosts; this.instances = instances; this.groups = groups; this.hostService = hostService;
        this.instanceService = instanceService; this.connectionTests = connectionTests; this.encryption = encryption;
        this.deploymentTargets = deploymentTargets; this.groupService = groupService; this.audit = audit;
    }

    /** The infrastructure section starts at the Linux hosts list (old links and bookmarks keep working). */
    @GetMapping public String index() { return "redirect:/infrastructure/hosts"; }

    // ---- Lists with search and filters ----

    @GetMapping("/hosts") public String hostList(@RequestParam(required = false) String q, @RequestParam(required = false) String connectivity,
                                                 @RequestParam(required = false) String status, @RequestParam(required = false) String hostKey,
                                                 @RequestParam(required = false) String sort, @RequestParam(required = false) String page,
                                                 @RequestParam(required = false) String size, Model model) {
        Map<Long, ConnectivityStatus> statuses = connectionTests.latestHostStatuses();
        State state = InfrastructureListing.parseEnum(State.class, connectivity);
        Boolean enabled = InfrastructureListing.parseFlag(status, "enabled", "disabled");
        Boolean pinned = InfrastructureListing.parseFlag(hostKey, "pinned", "unpinned");
        String sortKey = oneOf(sort, "name-desc", "hostname", "problems", "newest");
        List<ManagedHost> filtered = InfrastructureListing.hosts(hosts.findAllByOrderByNameAsc(),
                new InfrastructureListing.HostFilter(q, state, enabled, pinned, sortKey), statuses);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", q);
        params.put("connectivity", state == null ? null : state.name());
        params.put("status", enabled == null ? null : status);
        params.put("hostKey", pinned == null ? null : hostKey);
        params.put("sort", sortKey);
        ListView<ManagedHost> view = ListView.of(filtered, page, size, params)
                .chip("q", "Search: “" + trim(q) + "”")
                .chip("connectivity", "Connectivity: " + (state == null ? "" : stateLabel(state)))
                .chip("status", enabled == null ? "" : (enabled ? "Enabled" : "Disabled"))
                .chip("hostKey", pinned == null ? "" : (pinned ? "Host key pinned" : "Host key not pinned"));

        Map<Long, Long> instanceCounts = instances.findAllByOrderByNameAsc().stream()
                .collect(Collectors.groupingBy(i -> i.getHost().getId(), Collectors.counting()));
        model.addAttribute("pageTitle", "Linux hosts");
        model.addAttribute("view", view);
        model.addAttribute("q", trim(q));
        model.addAttribute("connectivity", state == null ? "" : state.name());
        model.addAttribute("status", enabled == null ? "" : status);
        model.addAttribute("hostKey", pinned == null ? "" : hostKey);
        model.addAttribute("sort", sortKey == null ? "" : sortKey);
        model.addAttribute("totalCount", hosts.count());
        model.addAttribute("connectivityOptions", connectivityOptions());
        model.addAttribute("pageSizes", ListView.PAGE_SIZES);
        model.addAttribute("hostStatuses", statuses);
        model.addAttribute("instanceCounts", instanceCounts);
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        model.addAttribute("encryptionConfigured", encryption.isConfigured());
        return "infrastructure/hosts";
    }

    @GetMapping("/instances") public String instanceList(@RequestParam(required = false) String q, @RequestParam(required = false) String environment,
                                                         @RequestParam(required = false) String host, @RequestParam(required = false) String method,
                                                         @RequestParam(required = false) String group, @RequestParam(required = false) String connectivity,
                                                         @RequestParam(required = false) String status, @RequestParam(required = false) String sort,
                                                         @RequestParam(required = false) String page, @RequestParam(required = false) String size, Model model) {
        Map<Long, ConnectivityStatus> statuses = connectionTests.latestInstanceStatuses();
        List<ManagedHost> allHosts = hosts.findAllByOrderByNameAsc();
        List<ServerGroup> allGroups = InfrastructureListing.groups(groups.findAll(), null);
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
        List<TomcatInstance> filtered = InfrastructureListing.instances(instances.findAllByOrderByNameAsc(),
                new InfrastructureListing.InstanceFilter(q, env, selectedHost == null ? null : hostId, deploymentMethod,
                        selectedGroup == null ? null : groupId, state, enabled, sortKey), statuses);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", q);
        params.put("environment", env);
        params.put("host", selectedHost == null ? null : hostId.toString());
        params.put("method", deploymentMethod == null ? null : deploymentMethod.name());
        params.put("group", selectedGroup == null ? null : groupId.toString());
        params.put("connectivity", state == null ? null : state.name());
        params.put("status", enabled == null ? null : status);
        params.put("sort", sortKey);
        ListView<TomcatInstance> view = ListView.of(filtered, page, size, params)
                .chip("q", "Search: “" + trim(q) + "”")
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
        model.addAttribute("totalCount", instances.count());
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

    @GetMapping("/groups") public String groupList(@RequestParam(required = false) String q, @RequestParam(required = false) String page,
                                                   @RequestParam(required = false) String size, Model model) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", q);
        ListView<ServerGroup> view = ListView.of(InfrastructureListing.groups(groups.findAll(), q), page, size, params)
                .chip("q", "Search: “" + trim(q) + "”");
        Map<Long, Long> memberCounts = new HashMap<>();
        for (TomcatInstance i : instances.findAllByOrderByNameAsc())
            for (ServerGroup g : i.getGroups()) memberCounts.merge(g.getId(), 1L, Long::sum);
        model.addAttribute("pageTitle", "Server groups");
        model.addAttribute("view", view);
        model.addAttribute("q", trim(q));
        model.addAttribute("totalCount", groups.count());
        model.addAttribute("pageSizes", ListView.PAGE_SIZES);
        model.addAttribute("memberCounts", memberCounts);
        return "infrastructure/groups";
    }

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
        ManagedHost host = hostService.find(id);
        model.addAttribute("pageTitle", host.getName());
        model.addAttribute("host", host);
        model.addAttribute("instances", instances.findByHostIdOrderByNameAsc(id));
        model.addAttribute("hostStatus", connectionTests.latestHostStatuses().getOrDefault(id, ConnectivityStatus.NEVER));
        model.addAttribute("instanceStatuses", connectionTests.latestInstanceStatuses());
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        return "infrastructure/host-detail";
    }
    @GetMapping("/hosts/new") public String newHost(Model model) { return hostForm(model, new HostForm()); }
    @GetMapping("/hosts/{id}/edit") public String editHost(@PathVariable Long id, Model model) { return hostForm(model, hostService.formFor(id)); }
    @PostMapping("/hosts/save") public String saveHost(@Valid @ModelAttribute("host") HostForm form, BindingResult result, Model model, RedirectAttributes flash) {
        hostService.validate(form, result);
        if (result.hasErrors()) return hostForm(model, form);
        HostService.HostChange change = hostService.saveWithChanges(form);
        audit.hostSaved(change);
        flash.addFlashAttribute("success", "Host saved. Run a connection test to verify it.");
        return "redirect:/infrastructure/hosts/" + change.host().getId();
    }
    @PostMapping("/hosts/{id}/delete") public String deleteHost(@PathVariable Long id, RedirectAttributes flash) {
        String name = hostService.find(id).getName();
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
        model.addAttribute("sites", hosts.findAllByOrderByNameAsc());
        model.addAttribute("hostStatuses", connectionTests.latestHostStatuses());
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        model.addAttribute("encryptionConfigured", encryption.isConfigured());
        return "infrastructure/host-form";
    }

    // ---- Server groups ----

    @GetMapping("/groups/new") public String newGroup(Model model) { model.addAttribute("pageTitle", "Create server group"); model.addAttribute("group", new ServerGroup()); return "infrastructure/group-form"; }
    @GetMapping("/groups/{id}/edit") public String editGroup(@PathVariable Long id, Model model) { model.addAttribute("pageTitle", "Edit server group"); model.addAttribute("group", groupService.find(id)); return "infrastructure/group-form"; }
    @GetMapping("/groups/{id}") public String groupDetail(@PathVariable Long id, Model model) {
        ServerGroup group = groupService.find(id);
        model.addAttribute("pageTitle", group.getName());
        model.addAttribute("group", group);
        model.addAttribute("members", groupService.members(id));
        model.addAttribute("candidates", groupService.nonMembers(id));
        model.addAttribute("instanceStatuses", connectionTests.latestInstanceStatuses());
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        return "infrastructure/group-detail";
    }
    @PostMapping("/groups/save") public String saveGroup(@Valid @ModelAttribute("group") ServerGroup group, BindingResult result, Model model, RedirectAttributes flash) {
        groupService.validate(group, result);
        if (result.hasErrors()) { model.addAttribute("pageTitle", "Server group"); return "infrastructure/group-form"; }
        boolean created = group.getId() == null;
        ServerGroup saved = groupService.save(group);
        audit.groupSaved(saved, created);
        flash.addFlashAttribute("success", created ? "Server group created. Add its members below." : "Server group saved.");
        return "redirect:/infrastructure/groups/" + saved.getId();
    }
    @PostMapping("/groups/{id}/delete") public String deleteGroup(@PathVariable Long id, RedirectAttributes flash) {
        String name = groupService.find(id).getName();
        groupService.delete(id); audit.groupDeleted(id, name);
        flash.addFlashAttribute("success", "Server group deleted."); return "redirect:/infrastructure/groups";
    }
    @PostMapping("/groups/{id}/members") public String addMember(@PathVariable Long id, @RequestParam(required = false) Long instanceId, RedirectAttributes flash) {
        if (instanceId == null) { flash.addFlashAttribute("error", "Choose a Tomcat instance to add."); return "redirect:/infrastructure/groups/" + id; }
        try {
            TomcatInstance i = groupService.addMember(id, instanceId);
            audit.membershipChanged(groupService.find(id), i, true);
            flash.addFlashAttribute("success", i.getName() + " added to the group.");
        } catch (IllegalStateException e) { flash.addFlashAttribute("error", e.getMessage()); }
        return "redirect:/infrastructure/groups/" + id;
    }
    @PostMapping("/groups/{id}/members/{instanceId}/remove") public String removeMember(@PathVariable Long id, @PathVariable Long instanceId, RedirectAttributes flash) {
        try {
            TomcatInstance i = groupService.removeMember(id, instanceId);
            audit.membershipChanged(groupService.find(id), i, false);
            flash.addFlashAttribute("success", i.getName() + " removed from the group.");
        } catch (IllegalStateException e) { flash.addFlashAttribute("error", e.getMessage()); }
        return "redirect:/infrastructure/groups/" + id;
    }

    // ---- Tomcat instances ----

    @GetMapping("/instances/{id}") public String instanceDetail(@PathVariable Long id, Model model) {
        TomcatInstance instance = instanceService.find(id);
        model.addAttribute("pageTitle", instance.getName());
        model.addAttribute("instance", instance);
        model.addAttribute("instanceStatus", connectionTests.latestInstanceStatuses().getOrDefault(id, ConnectivityStatus.NEVER));
        model.addAttribute("currentDeployments", deploymentTargets.findCurrentDeployments(id));
        return "infrastructure/instance-detail";
    }
    @GetMapping("/instances/new") public String newInstance(Model model) { return instanceForm(model, new InstanceForm()); }
    @GetMapping("/instances/{id}/edit") public String editInstance(@PathVariable Long id, Model model) { return instanceForm(model, InstanceForm.from(instanceService.find(id))); }
    @PostMapping("/instances/save") public String saveInstance(@Valid @ModelAttribute("form") InstanceForm f, BindingResult result, Model model, RedirectAttributes flash) {
        instanceService.validate(f, result);
        if (result.hasErrors()) return instanceForm(model, f);
        InstanceService.InstanceChange change = instanceService.saveWithChanges(f);
        audit.instanceSaved(change);
        flash.addFlashAttribute("success", "Tomcat instance saved. Credentials are encrypted at rest; blank secret fields keep existing values.");
        return "redirect:/infrastructure/instances/" + change.instance().getId();
    }
    @PostMapping("/instances/{id}/delete") public String deleteInstance(@PathVariable Long id, RedirectAttributes flash) {
        String name = instanceService.find(id).getName();
        instanceService.delete(id); audit.instanceDeleted(id, name);
        flash.addFlashAttribute("success", "Tomcat instance deleted."); return "redirect:/infrastructure/instances";
    }
    private String instanceForm(Model model, InstanceForm form) {
        form.setManagerSecret(null);
        model.addAttribute("pageTitle", form.getId() == null ? "Add Tomcat instance" : "Edit Tomcat instance");
        model.addAttribute("form", form); model.addAttribute("hosts", hosts.findAllByOrderByNameAsc()); model.addAttribute("groups", groups.findAll());
        model.addAttribute("deploymentMethods", TomcatInstance.DeploymentMethod.values());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("encryptionConfigured", encryption.isConfigured());
        // Offer the Tomcat/Java versions reported by the last Tomcat Manager test, if any.
        if (form.getId() != null) connectionTests.historyForInstance(form.getId(), 0, 5).getContent().stream()
                .map(r -> RuntimeDetection.parse(r.getRemoteServerInfo())).filter(java.util.Objects::nonNull).findFirst()
                .ifPresent(d -> model.addAttribute("detectedRuntime", d));
        return "infrastructure/instance-form";
    }
}
