package com.tomcatmanager.infrastructure;

import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.connectivity.ConnectivityStatus;
import com.tomcatmanager.security.SecretEncryptionService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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

    @GetMapping public String index(Model model) {
        model.addAttribute("pageTitle", "Infrastructure");
        model.addAttribute("hosts", hosts.findAllByOrderByNameAsc());
        java.util.List<TomcatInstance> allInstances = instances.findAllByOrderByNameAsc();
        model.addAttribute("instances", allInstances);
        model.addAttribute("unassignedInstances", allInstances.stream().filter(i -> i.getEnvironment() == null).count());
        model.addAttribute("groups", groups.findAll());
        model.addAttribute("hostStatuses", connectionTests.latestHostStatuses());
        model.addAttribute("instanceStatuses", connectionTests.latestInstanceStatuses());
        model.addAttribute("neverTested", ConnectivityStatus.NEVER);
        model.addAttribute("encryptionConfigured", encryption.isConfigured());
        return "infrastructure/index";
    }

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
        return "redirect:/infrastructure";
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
        flash.addFlashAttribute("success", "Server group deleted."); return "redirect:/infrastructure";
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
        flash.addFlashAttribute("success", "Tomcat instance deleted."); return "redirect:/infrastructure";
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
