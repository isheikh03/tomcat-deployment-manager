package com.tomcatmanager.dashboard;

import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.connectivity.ConnectivityStatus;
import com.tomcatmanager.deployment.DeploymentRun;
import com.tomcatmanager.deployment.DeploymentTargetRepository;
import com.tomcatmanager.deployment.TargetState;
import com.tomcatmanager.health.HealthOverview;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.ArtifactStorage;
import com.tomcatmanager.release.ReleaseRepository;
import com.tomcatmanager.release.StoredArtifactRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Controller
public class DashboardController {
    private final ManagedHostRepository hosts;
    private final TomcatInstanceRepository instances;
    private final ConnectionTestService connectionTests;
    private final ApplicationRepository applications;
    private final ReleaseRepository releases;
    private final StoredArtifactRepository artifacts;
    private final com.tomcatmanager.deployment.DeploymentRunRepository deployments;
    private final DeploymentTargetRepository deploymentTargets;
    private final com.tomcatmanager.deployment.DeploymentBackupRepository backups;
    private final HealthOverview health;

    public DashboardController(ManagedHostRepository hosts, TomcatInstanceRepository instances, ConnectionTestService connectionTests,
                               ApplicationRepository applications, ReleaseRepository releases, StoredArtifactRepository artifacts,
                               com.tomcatmanager.deployment.DeploymentRunRepository deployments, DeploymentTargetRepository deploymentTargets,
                               com.tomcatmanager.deployment.DeploymentBackupRepository backups, HealthOverview health,
                               com.tomcatmanager.access.Access access, com.tomcatmanager.deployment.DeploymentService deploymentService,
                               com.tomcatmanager.release.ReleaseService releaseService) {
        this.hosts = hosts; this.instances = instances; this.connectionTests = connectionTests;
        this.applications = applications; this.releases = releases; this.artifacts = artifacts;
        this.deployments = deployments; this.deploymentTargets = deploymentTargets; this.backups = backups; this.health = health;
        this.access = access; this.deploymentService = deploymentService; this.releaseService = releaseService;
    }

    private final com.tomcatmanager.access.Access access;
    private final com.tomcatmanager.deployment.DeploymentService deploymentService;
    private final com.tomcatmanager.release.ReleaseService releaseService;

    /** Everything on the dashboard counts only what the signed-in user can see (their teams and instances). */
    @GetMapping("/")
    public String dashboard(Model model) {
        com.tomcatmanager.access.AccessScope s = access.current();
        List<com.tomcatmanager.infrastructure.ManagedHost> myHosts = hosts.findAll().stream().filter(h -> s.canViewHost(h.getId(), h.getTeamId())).toList();
        List<com.tomcatmanager.infrastructure.TomcatInstance> myInstances = instances.findAll().stream().filter(i -> s.canViewInstance(i.getId())).toList();
        java.util.Set<Long> myInstanceIds = myInstances.stream().map(com.tomcatmanager.infrastructure.TomcatInstance::getId).collect(java.util.stream.Collectors.toSet());
        long instanceTotal = myInstances.size();
        Map<Long, ConnectivityStatus> all = connectionTests.latestInstanceStatuses();
        List<ConnectivityStatus> statuses = all.entrySet().stream().filter(e -> myInstanceIds.contains(e.getKey())).map(Map.Entry::getValue).toList();
        long verified = count(statuses, ConnectivityStatus.State.VERIFIED);
        long failed = count(statuses, ConnectivityStatus.State.FAILED) + count(statuses, ConnectivityStatus.State.WARNING);
        model.addAttribute("pageTitle", "Dashboard");
        model.addAttribute("hostsCount", myHosts.size());
        model.addAttribute("enabledHostsCount", myHosts.stream().filter(com.tomcatmanager.infrastructure.ManagedHost::isEnabled).count());
        model.addAttribute("instancesCount", instanceTotal);
        model.addAttribute("enabledInstancesCount", myInstances.stream().filter(com.tomcatmanager.infrastructure.TomcatInstance::isEnabled).count());
        model.addAttribute("verifiedInstancesCount", verified);
        model.addAttribute("failedInstancesCount", failed);
        model.addAttribute("unverifiedInstancesCount", Math.max(0, instanceTotal - verified - failed));
        model.addAttribute("applicationsCount", applications.findAll().stream().filter(a -> s.canViewApplication(a.getTeamId())).count());
        model.addAttribute("releasesCount", releaseService.search(null, "", false, 0, 1, s).getTotalElements());
        // Storage totals are system-wide (shared by all teams); they reveal sizes only.
        model.addAttribute("storageUsed", ArtifactStorage.humanSize(artifacts.totalStoredBytes()));
        model.addAttribute("backupStorageUsed", ArtifactStorage.humanSize(backups.totalServerBackupBytes()));
        model.addAttribute("serverBackupCount", backups.countServerBackups());
        model.addAttribute("recentReleases", releaseService.search(null, "", false, 0, 5, s).getContent());
        model.addAttribute("runningDeployments", runCount(s, com.tomcatmanager.deployment.DeploymentState.PENDING, com.tomcatmanager.deployment.DeploymentState.VALIDATING,
                com.tomcatmanager.deployment.DeploymentState.RUNNING, com.tomcatmanager.deployment.DeploymentState.CANCEL_REQUESTED));
        model.addAttribute("succeededDeployments", runCount(s, com.tomcatmanager.deployment.DeploymentState.SUCCEEDED));
        model.addAttribute("problemDeployments", runCount(s, com.tomcatmanager.deployment.DeploymentState.FAILED,
                com.tomcatmanager.deployment.DeploymentState.PARTIALLY_SUCCEEDED, com.tomcatmanager.deployment.DeploymentState.UNKNOWN));
        model.addAttribute("recentDeployments", deploymentService.search(null, null, null, 0, 6, s).getContent());
        HealthOverview.Overview overview = health.overview(s::canViewInstance);
        model.addAttribute("failingHealth", overview.unhealthy());
        model.addAttribute("degradedHealth", overview.degraded());
        model.addAttribute("failingApplications", overview.failing());
        java.util.Collection<Long> ids = myInstanceIds.isEmpty() ? List.of(-1L) : myInstanceIds;
        model.addAttribute("rollbackRequired", s.isEverything() ? deploymentTargets.countByState(TargetState.ROLLBACK_REQUIRED)
                : deploymentTargets.countByStateAndInstanceIdIn(TargetState.ROLLBACK_REQUIRED, ids));
        model.addAttribute("unknownTargets", s.isEverything() ? deploymentTargets.countByState(TargetState.UNKNOWN)
                : deploymentTargets.countByStateAndInstanceIdIn(TargetState.UNKNOWN, ids));
        model.addAttribute("recentRollbacks", deployments.findTop5ByKindOrderByRequestedAtDesc(DeploymentRun.Kind.ROLLBACK).stream()
                .filter(r -> deploymentService.visible(r, s)).toList());
        return "dashboard";
    }

    /** Runs in any of the given states that the user can see. */
    private long runCount(com.tomcatmanager.access.AccessScope s, com.tomcatmanager.deployment.DeploymentState... states) {
        long total = 0;
        for (com.tomcatmanager.deployment.DeploymentState state : states) total += deploymentService.search(null, null, state, 0, 1, s).getTotalElements();
        return total;
    }

    private static long count(Collection<ConnectivityStatus> statuses, ConnectivityStatus.State state) {
        return statuses.stream().filter(s -> s.state() == state).count();
    }
}
