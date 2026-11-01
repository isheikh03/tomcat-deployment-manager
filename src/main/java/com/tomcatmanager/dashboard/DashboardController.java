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
                               com.tomcatmanager.deployment.DeploymentBackupRepository backups, HealthOverview health) {
        this.hosts = hosts; this.instances = instances; this.connectionTests = connectionTests;
        this.applications = applications; this.releases = releases; this.artifacts = artifacts;
        this.deployments = deployments; this.deploymentTargets = deploymentTargets; this.backups = backups; this.health = health;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        long instanceTotal = instances.count();
        Map<Long, ConnectivityStatus> statuses = connectionTests.latestInstanceStatuses();
        long verified = count(statuses.values(), ConnectivityStatus.State.VERIFIED);
        long failed = count(statuses.values(), ConnectivityStatus.State.FAILED) + count(statuses.values(), ConnectivityStatus.State.WARNING);
        model.addAttribute("pageTitle", "Dashboard");
        model.addAttribute("hostsCount", hosts.count());
        model.addAttribute("enabledHostsCount", hosts.countByEnabledTrue());
        model.addAttribute("instancesCount", instanceTotal);
        model.addAttribute("enabledInstancesCount", instances.countByEnabledTrue());
        model.addAttribute("verifiedInstancesCount", verified);
        model.addAttribute("failedInstancesCount", failed);
        model.addAttribute("unverifiedInstancesCount", Math.max(0, instanceTotal - verified - failed));
        model.addAttribute("applicationsCount", applications.count());
        model.addAttribute("releasesCount", releases.countByArchivedFalse());
        model.addAttribute("storageUsed", ArtifactStorage.humanSize(artifacts.totalStoredBytes()));
        model.addAttribute("backupStorageUsed", ArtifactStorage.humanSize(backups.totalServerBackupBytes()));
        model.addAttribute("serverBackupCount", backups.countServerBackups());
        model.addAttribute("recentReleases", releases.findTop5ByOrderByUploadedAtDesc());
        model.addAttribute("runningDeployments", deployments.countByStateIn(java.util.EnumSet.of(com.tomcatmanager.deployment.DeploymentState.PENDING,
                com.tomcatmanager.deployment.DeploymentState.VALIDATING, com.tomcatmanager.deployment.DeploymentState.RUNNING, com.tomcatmanager.deployment.DeploymentState.CANCEL_REQUESTED)));
        model.addAttribute("succeededDeployments", deployments.countByStateIn(java.util.EnumSet.of(com.tomcatmanager.deployment.DeploymentState.SUCCEEDED)));
        model.addAttribute("problemDeployments", deployments.countByStateIn(java.util.EnumSet.of(com.tomcatmanager.deployment.DeploymentState.FAILED,
                com.tomcatmanager.deployment.DeploymentState.PARTIALLY_SUCCEEDED, com.tomcatmanager.deployment.DeploymentState.UNKNOWN)));
        model.addAttribute("recentDeployments", deployments.findTop6ByOrderByRequestedAtDesc());
        HealthOverview.Overview overview = health.overview();
        model.addAttribute("failingHealth", overview.unhealthy());
        model.addAttribute("degradedHealth", overview.degraded());
        model.addAttribute("failingApplications", overview.failing());
        model.addAttribute("rollbackRequired", deploymentTargets.countByState(TargetState.ROLLBACK_REQUIRED));
        model.addAttribute("unknownTargets", deploymentTargets.countByState(TargetState.UNKNOWN));
        model.addAttribute("recentRollbacks", deployments.findTop5ByKindOrderByRequestedAtDesc(DeploymentRun.Kind.ROLLBACK));
        return "dashboard";
    }

    private static long count(Collection<ConnectivityStatus> statuses, ConnectivityStatus.State state) {
        return statuses.stream().filter(s -> s.state() == state).count();
    }
}
