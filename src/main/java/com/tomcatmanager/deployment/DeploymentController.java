package com.tomcatmanager.deployment;

import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.connectivity.ConnectivityStatus;
import com.tomcatmanager.infrastructure.DeploymentFilename;
import com.tomcatmanager.infrastructure.Environment;
import com.tomcatmanager.infrastructure.ServerGroup;
import com.tomcatmanager.infrastructure.ServerGroupRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.Release;
import com.tomcatmanager.release.ReleaseRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class DeploymentController {
    private final DeploymentService service;
    private final DeploymentTargetRepository targets;
    private final ApplicationRepository applications;
    private final ReleaseRepository releases;
    private final TomcatInstanceRepository instances;
    private final ServerGroupRepository groups;
    private final ConnectionTestService connections;
    private final DeploymentProperties properties;

    public DeploymentController(DeploymentService service, DeploymentTargetRepository targets, ApplicationRepository applications,
                                ReleaseRepository releases, TomcatInstanceRepository instances, ServerGroupRepository groups,
                                ConnectionTestService connections, DeploymentProperties properties, com.tomcatmanager.access.Access access) {
        this.service = service; this.targets = targets; this.applications = applications; this.releases = releases;
        this.instances = instances; this.groups = groups; this.connections = connections; this.properties = properties;
        this.access = access;
    }

    private final com.tomcatmanager.access.Access access;

    @GetMapping("/deployments")
    public String history(@RequestParam(required = false) Long app, @RequestParam(required = false) Environment environment,
                          @RequestParam(required = false) DeploymentState state, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("pageTitle", "Deployments");
        com.tomcatmanager.access.AccessScope s = access.current();
        model.addAttribute("runs", service.search(app, environment, state, page, 20, s));
        model.addAttribute("applications", applications.findAllByOrderByNameAsc().stream().filter(a -> s.canViewApplication(a.getTeamId())).toList());
        model.addAttribute("environments", Environment.values());
        model.addAttribute("states", DeploymentState.values());
        model.addAttribute("app", app);
        model.addAttribute("environment", environment);
        model.addAttribute("state", state);
        model.addAttribute("filtered", app != null || environment != null || state != null);
        return "deployments/index";
    }

    @GetMapping("/deployments/new")
    public String wizard(@RequestParam(required = false) Long release, Model model) {
        model.addAttribute("pageTitle", "New deployment");
        model.addAttribute("requestId", UUID.randomUUID());
        model.addAttribute("preselectedRelease", release);
        model.addAttribute("maxParallel", properties.maxParallelTargets());
        com.tomcatmanager.access.AccessScope s = access.current();
        model.addAttribute("hasApplications", applications.findAllByOrderByNameAsc().stream().anyMatch(a -> s.canUploadTo(a.getTeamId())));
        model.addAttribute("hasInstances", instances.findAllByOrderByNameAsc().stream().anyMatch(i -> s.canDeployTo(i.getId())));
        return "deployments/new";
    }

    @GetMapping("/deployments/{id}")
    public String detail(@PathVariable long id, Model model) {
        DeploymentRun run = service.findVisible(id, access.current());
        model.addAttribute("pageTitle", (run.isRollback() ? "Rollback #" : "Deployment #") + id);
        model.addAttribute("run", run);
        model.addAttribute("targetCount", targets.findByRunIdOrderBySequenceAsc(id).size());
        model.addAttribute("rollbackOfRunId", run.isRollback() ? targets.findById(run.getRollbackOfTargetId()).map(t -> t.getRun().getId()).orElse(null) : null);
        return "deployments/detail";
    }

    // ---- data for the wizard ----

    public record WizardRelease(Long id, String versionLabel, Instant uploadedAt, boolean hasNotes, String sha256) { }
    /** {@code teamId}: an application deploys only to instances (and groups) of the same team. */
    public record WizardApplication(Long id, String name, List<WizardRelease> releases, Long teamId) { }
    public record WizardInstance(Long id, String name, String hostName, String hostname, Environment environment, String method,
                                 String filename, String contextPath, boolean enabled, String connectivity, String connectivityState, List<Long> groupIds,
                                 Long teamId) { }
    public record WizardGroup(Long id, String name, Long teamId) { }
    public record WizardEnvironment(Environment id, String label, long instanceCount, boolean production) { }
    public record WizardOptions(List<WizardApplication> applications, List<WizardEnvironment> environments, List<WizardInstance> instances,
                                List<WizardGroup> groups, int maxParallel) { }

    @GetMapping("/api/deployments/wizard-options")
    @ResponseBody
    public WizardOptions wizardOptions() {
        Map<Long, ConnectivityStatus> statuses = connections.latestInstanceStatuses();
        com.tomcatmanager.access.AccessScope scope = access.current();
        // Only what the user can deploy: instances with deploy access, and applications of teams where they have such an instance.
        List<TomcatInstance> all = instances.findAllByOrderByNameAsc().stream().filter(i -> scope.canDeployTo(i.getId())).toList();
        List<WizardApplication> apps = applications.findAllByOrderByNameAsc().stream().filter(a -> scope.canUploadTo(a.getTeamId()))
                .map(a -> new WizardApplication(a.getId(), a.getName(),
                releases.findByApplicationIdOrderByUploadedAtDesc(a.getId()).stream().filter(r -> !r.isArchived())
                        .map(r -> new WizardRelease(r.getId(), r.getVersionLabel(), r.getUploadedAt(), r.hasReleaseNotes(), r.getArtifact().getSha256()))
                        .toList(), a.getTeamId())).toList();
        List<WizardEnvironment> envs = Arrays.stream(Environment.values())
                .map(e -> new WizardEnvironment(e, e.label(), all.stream().filter(i -> i.getEnvironment() == e).count(), e.isProduction())).toList();
        List<WizardInstance> list = all.stream().map(i -> {
            ConnectivityStatus s = statuses.getOrDefault(i.getId(), ConnectivityStatus.NEVER);
            String filename = i.getDeploymentFilename();
            return new WizardInstance(i.getId(), i.getName(), i.getHost().getName(), i.getHost().getHostname(), i.getEnvironment(),
                    i.getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP ? "SSH / SFTP" : "Tomcat Manager", filename,
                    filename != null && DeploymentFilename.isValid(filename) ? DeploymentFilename.contextPath(filename) : null,
                    i.isEnabled() && i.getHost().isEnabled(), s.label(), s.state().name(), i.getGroups().stream().map(ServerGroup::getId).toList(),
                    i.getHost().getTeamId());
        }).toList();
        List<WizardGroup> groupList = groups.findAll().stream().filter(g -> scope.canViewTeam(g.getTeamId()))
                .map(g -> new WizardGroup(g.getId(), g.getName(), g.getTeamId())).toList();
        return new WizardOptions(apps, envs, list, groupList, properties.maxParallelTargets());
    }

    /** Release used by the "Deploy" button on a release page. */
    public static String deployLink(Release release) { return "/deployments/new?release=" + release.getId(); }
}
