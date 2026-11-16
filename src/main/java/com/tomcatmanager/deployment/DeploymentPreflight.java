package com.tomcatmanager.deployment;

import com.tomcatmanager.connectivity.CheckStatus;
import com.tomcatmanager.connectivity.ConnectionTestResult;
import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.connectivity.ConnectivityBusyException;
import com.tomcatmanager.connectivity.ConnectivityStatus;
import com.tomcatmanager.connectivity.RemoteOperationException;
import com.tomcatmanager.infrastructure.DeploymentFilename;
import com.tomcatmanager.infrastructure.Environment;
import com.tomcatmanager.infrastructure.ServerGroup;
import com.tomcatmanager.infrastructure.ServerGroupRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.ArtifactStorage;
import com.tomcatmanager.release.Compatibility;
import com.tomcatmanager.release.Release;
import com.tomcatmanager.release.ReleaseRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Preflight: validates a requested deployment and produces the exact per-target preview. Blockers
 * prevent deployment; warnings must be explicitly acknowledged. Used for the wizard preview and
 * again, on the server, when the deployment is submitted.
 */
@Service
public class DeploymentPreflight {
    static final Set<TargetState> LOCKING_STATES = EnumSet.of(TargetState.PENDING, TargetState.BACKUP_IN_PROGRESS, TargetState.BACKUP_COMPLETED,
            TargetState.TRANSFER_IN_PROGRESS, TargetState.DEPLOYING, TargetState.HEALTH_CHECKING, TargetState.ROLLING_BACK, TargetState.UNKNOWN);

    private final ReleaseRepository releases;
    private final TomcatInstanceRepository instances;
    private final ServerGroupRepository groups;
    private final DeploymentTargetRepository targets;
    private final ConnectionTestService connections;
    private final RemoteInspector inspector;
    private final ArtifactStorage storage;
    private final DeploymentProperties properties;
    private final com.tomcatmanager.access.Access access;

    public DeploymentPreflight(ReleaseRepository releases, TomcatInstanceRepository instances, ServerGroupRepository groups,
                               DeploymentTargetRepository targets, ConnectionTestService connections, RemoteInspector inspector,
                               ArtifactStorage storage, DeploymentProperties properties, com.tomcatmanager.access.Access access) {
        this.releases = releases; this.instances = instances; this.groups = groups; this.targets = targets;
        this.connections = connections; this.inspector = inspector; this.storage = storage; this.properties = properties;
        this.access = access;
    }

    public record TargetSpec(Long instanceId, String filename) { }

    public record Request(Long releaseId, Environment environment, Long serverGroupId, List<TargetSpec> targets,
                          DeploymentRun.Strategy strategy, Integer maxParallel) { }

    public record Row(Long instanceId, String instanceName, String hostName, String hostname, Environment environment,
                      TomcatInstance.DeploymentMethod method, String filename, String contextPath, String currentRelease,
                      Long currentReleaseId, String connectivity, String connectivityState, Compatibility.Status compatibility,
                      List<String> compatibilityReasons, String remoteState, String backup, List<String> blockers, List<String> warnings) { }

    public record Preview(Long releaseId, String applicationName, String versionLabel, String sha256, Environment environment,
                          DeploymentRun.Strategy strategy, int maxParallel, String serverGroupName, List<Row> rows,
                          List<String> blockers, List<String> warnings, boolean production, String confirmationText) {
        public boolean canDeploy() { return blockers.isEmpty() && rows.stream().allMatch(r -> r.blockers().isEmpty()); }
        public boolean requiresAcknowledgement() { return !warnings.isEmpty() || rows.stream().anyMatch(r -> !r.warnings().isEmpty()); }
        public int blockerCount() { return blockers.size() + rows.stream().mapToInt(r -> r.blockers().size()).sum(); }
        public int warningCount() { return warnings.size() + rows.stream().mapToInt(r -> r.warnings().size()).sum(); }
    }

    public Preview evaluate(Request request, String actor) {
        if (request.releaseId() == null) throw new IllegalArgumentException("Choose a release.");
        if (request.environment() == null) throw new IllegalArgumentException("Choose an environment.");
        Release release = releases.findById(request.releaseId()).orElseThrow(() -> new NoSuchElementException("Release " + request.releaseId() + " not found."));
        // Access: the release's application must be visible; each target must be one the user can deploy to, in the application's team.
        com.tomcatmanager.access.AccessScope scope = access.current();
        Long appTeam = release.getApplication().getTeamId();
        if (!scope.canViewApplication(appTeam)) throw new NoSuchElementException("Release " + request.releaseId() + " not found.");
        DeploymentRun.Strategy strategy = request.strategy() == null ? DeploymentRun.Strategy.SEQUENTIAL : request.strategy();
        int maxParallel = strategy == DeploymentRun.Strategy.SEQUENTIAL ? 1 : request.maxParallel() == null ? 2 : request.maxParallel();
        boolean production = request.environment().isProduction();

        List<String> blockers = new ArrayList<>(), warnings = new ArrayList<>();
        if (release.isArchived()) blockers.add("The release is archived. Restore it before deploying.");
        ArtifactStorage.Verification integrity = storage.verify(release.getArtifact());
        if (!integrity.ok()) blockers.add("Release file failed its integrity check: " + integrity.message());
        if (production && (release.getReleaseNotes() == null || release.getReleaseNotes().strip().length() < properties.productionReleaseNotesMinLength()))
            blockers.add("Production deployments need meaningful release notes (at least " + properties.productionReleaseNotesMinLength() + " characters). Add them on the release page.");
        if (production && strategy == DeploymentRun.Strategy.PARALLEL) blockers.add("Production deployments must be sequential.");
        if (strategy == DeploymentRun.Strategy.PARALLEL && (maxParallel < 2 || maxParallel > properties.maxParallelTargets()))
            blockers.add("Parallel deployments may run 2 to " + properties.maxParallelTargets() + " targets at a time.");

        String groupName = null;
        Set<Long> groupMembers = null;
        if (request.serverGroupId() != null) {
            ServerGroup group = groups.findById(request.serverGroupId()).orElseThrow(() -> new NoSuchElementException("Server group not found."));
            if (!scope.canViewTeam(group.getTeamId())) throw new NoSuchElementException("Server group not found.");
            if (!java.util.Objects.equals(group.getTeamId(), appTeam))
                blockers.add("Server group " + group.getName() + " belongs to another team; " + release.getApplication().getName() + " deploys only to its own team's instances.");
            groupName = group.getName();
            groupMembers = new HashSet<>();
            for (TomcatInstance i : instances.findAllByOrderByNameAsc())
                if (i.getGroups().stream().anyMatch(g -> g.getId().equals(group.getId()))) groupMembers.add(i.getId());
        }

        List<TargetSpec> specs = request.targets() == null ? List.of() : request.targets();
        if (specs.isEmpty()) blockers.add("Select at least one Tomcat instance.");
        Compatibility.Requirements requirements = Compatibility.requirements(release.getApplication(), release.getArtifact());
        Set<Long> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (TargetSpec spec : specs) {
            if (spec.instanceId() == null || !seen.add(spec.instanceId())) { blockers.add("Each instance can be selected only once."); continue; }
            TomcatInstance instance = instances.findById(spec.instanceId()).orElse(null);
            // An instance outside the user's access is reported like a missing one: its existence is not revealed.
            if (instance == null || !scope.canViewInstance(instance.getId())) { blockers.add("Instance " + spec.instanceId() + " no longer exists."); continue; }
            if (!scope.canDeployTo(instance.getId()))
                blockers.add("You have view-only access to " + instance.getName() + "; deploying needs deploy access.");
            if (!java.util.Objects.equals(instance.getHost().getTeamId(), appTeam))
                blockers.add(instance.getName() + " belongs to another team; " + release.getApplication().getName() + " deploys only to its own team's instances.");
            if (groupMembers != null && !groupMembers.contains(instance.getId()))
                blockers.add(instance.getName() + " is not a member of server group " + groupName + ".");
            rows.add(evaluateTarget(instance, spec, release, request.environment(), requirements, production, actor));
        }
        return new Preview(release.getId(), release.getApplication().getName(), release.getVersionLabel(), release.getArtifact().getSha256(),
                request.environment(), strategy, maxParallel, groupName, rows, blockers, warnings, production, release.getVersionLabel());
    }

    private Row evaluateTarget(TomcatInstance instance, TargetSpec spec, Release release, Environment environment,
                               Compatibility.Requirements requirements, boolean production, String actor) {
        List<String> blockers = new ArrayList<>(), warnings = new ArrayList<>();
        String filename = spec.filename() == null || spec.filename().isBlank() ? instance.getDeploymentFilename() : spec.filename().trim();
        String filenameProblem = DeploymentFilename.problem(filename);
        String context = filenameProblem == null ? DeploymentFilename.contextPath(filename) : "—";
        if (filenameProblem != null) blockers.add("Target file name: " + filenameProblem);

        if (instance.getEnvironment() == null) blockers.add("The instance has no environment assigned. Set it on the instance first.");
        else if (instance.getEnvironment() != environment) blockers.add("The instance belongs to " + instance.getEnvironment().label() + ", not " + environment.label() + ".");
        if (!instance.isEnabled()) blockers.add("The Tomcat instance is disabled.");
        if (!instance.getHost().isEnabled()) blockers.add("The Linux host is disabled.");
        if (!instance.isBackupEnabled())
            warnings.add("Backup is off for this instance: the current application is not saved first, so a failed deployment cannot be rolled back automatically.");
        if (!instance.isHealthCheckEnabled())
            warnings.add("The application health check is off for this instance: success will only mean the WAR was deployed, not that the application started.");
        else if (instance.getHealthUrl() == null || instance.getHealthUrl().isBlank())
            blockers.add("No health URL is configured, so success cannot be verified. Add one or switch off the health check on the instance.");

        accessBlockers(instance, blockers);

        Optional<DeploymentTarget> current = filenameProblem == null ? targets.findCurrentDeployment(instance.getId(), context) : Optional.empty();
        String currentLabel = current.map(DeploymentPreflight::deployedLabel).orElse(null);
        Long currentId = current.map(t -> t.getRun().getRelease() == null ? null : t.getRun().getRelease().getId()).orElse(null);
        if (currentId != null && currentId.equals(release.getId())) warnings.add("This release is already deployed here; it will be redeployed.");

        Compatibility.Result compatibility = Compatibility.evaluate(requirements, instance.getTomcatVersion(), instance.getJavaVersion());
        if (compatibility.status() == Compatibility.Status.INCOMPATIBLE) blockers.add("Incompatible: " + String.join(" ", compatibility.reasons()));
        if (compatibility.status() == Compatibility.Status.UNKNOWN) warnings.add("Compatibility could not be confirmed: " + String.join(" ", compatibility.reasons()));

        Reachability reachability = reachability(instance, actor, blockers, warnings);
        String connectivity = reachability.label();
        boolean reachable = reachability.reachable();

        String remote = "Not checked";
        boolean backupOn = instance.isBackupEnabled();
        String backup = !backupOn ? "Off for this instance" : currentLabel != null ? "Previous release " + currentLabel : "None needed (first deployment)";
        if (reachable && filenameProblem == null) {
            try {
                RemoteInspector.Inspection inspection = inspector.inspect(instance, filename, context);
                remote = inspection.description();
                if (instance.getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP) {
                    if (backupOn && inspection.warPresent()) backup = "Current WAR will be downloaded before replacement";
                    else if (backupOn && inspection.applicationPresent()) {
                        backup = "Not possible (exploded directory only)";
                        warnings.add("An exploded application exists at " + context + " without a WAR file; it cannot be backed up and will be replaced.");
                    }
                    long size = release.getArtifact().getSizeBytes();
                    if (inspection.freeBytes() == null) warnings.add("Free disk space could not be checked on this server.");
                    else if (inspection.freeBytes() < size * 2)
                        blockers.add("Not enough free space: " + RemoteInspector.humanSize(inspection.freeBytes()) + " free, at least " + RemoteInspector.humanSize(size * 2) + " needed.");
                } else if (backupOn && inspection.applicationPresent() && currentLabel == null) {
                    backup = "Not possible (deployed outside this tool)";
                }
                if (inspection.applicationPresent() && currentLabel == null)
                    warnings.add("An application not deployed by this tool exists at " + context + "; it will be replaced" + (production ? " in PRODUCTION." : "."));
            } catch (RemoteOperationException e) {
                blockers.add("Could not inspect the server: " + e.getMessage());
            }
        }

        return new Row(instance.getId(), instance.getName(), instance.getHost().getName(), instance.getHost().getHostname(), instance.getEnvironment(),
                instance.getDeploymentMethod(), filename, context, currentLabel, currentId, connectivity,
                reachable ? "ok" : "failed", compatibility.status(), compatibility.reasons(), remote, backup, blockers, warnings);
    }

    /** Version label of what a successful target installed (a release, or a restored server backup). */
    static String deployedLabel(DeploymentTarget t) {
        DeploymentRun run = t.getRun();
        if (run.getRelease() != null) return run.getRelease().getVersionLabel();
        return "server backup restored by rollback #" + run.getId();
    }

    /** Credential and per-instance lock blockers; shared with the rollback preflight. */
    void accessBlockers(TomcatInstance instance, List<String> blockers) {
        if (instance.getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP) {
            if (!instance.getHost().hasPinnedHostKey()) blockers.add("The host's SSH host key is not pinned.");
            if (!instance.getHost().hasSshPassword() && !instance.getHost().hasSshPrivateKey()) blockers.add("The host has no SSH credentials.");
        } else if (!instance.hasManagerSecret() || instance.getManagerUsername() == null) {
            blockers.add("Tomcat Manager credentials are incomplete.");
        }
        for (DeploymentTarget t : targets.findByInstanceIdAndStateIn(instance.getId(), LOCKING_STATES)) {
            String what = (t.getRun().isRollback() ? "Rollback #" : "Deployment #") + t.getRun().getId();
            if (t.getState() == TargetState.UNKNOWN) blockers.add(what + " to this instance has an unknown outcome and must be reconciled first.");
            else blockers.add(what + " to this instance is still in progress.");
        }
    }

    record Reachability(String label, boolean reachable) { }

    /** Connectivity: uses a recent successful test, otherwise runs a fresh one now. */
    Reachability reachability(TomcatInstance instance, String actor, List<String> blockers, List<String> warnings) {
        ConnectivityStatus status = connections.latestInstanceStatuses().getOrDefault(instance.getId(), ConnectivityStatus.NEVER);
        String connectivity = status.label();
        boolean reachable = status.state() == ConnectivityStatus.State.VERIFIED;
        if (!reachable && blockers.stream().noneMatch(b -> b.contains("disabled") || b.contains("credentials") || b.contains("not pinned"))) {
            try {
                ConnectionTestResult fresh = connections.testInstance(instance.getId(), actor);
                ConnectivityStatus freshStatus = connections.status(fresh);
                connectivity = freshStatus.label() + " (tested now)";
                reachable = fresh.getOutcome() != CheckStatus.FAILED;
                if (fresh.getOutcome() == CheckStatus.FAILED)
                    blockers.add("Connection test failed: " + fresh.getChecks().stream().filter(c -> c.getStatus() == CheckStatus.FAILED)
                            .map(c -> c.getCheckType().label() + ": " + c.getDetail()).findFirst().orElse("see the instance page") + ".");
                else if (fresh.getOutcome() == CheckStatus.WARNING) warnings.add("Connection test passed with warnings; review it on the instance page.");
            } catch (ConnectivityBusyException e) {
                blockers.add("A fresh connection test could not run now (too many tests running). Try the preview again.");
            }
        } else if (!reachable) {
            blockers.add("Connectivity is not verified.");
        }
        return new Reachability(connectivity, reachable);
    }

    /** Targets eligible for the wizard: instances of an environment with their default file name. */
    public List<TomcatInstance> instancesIn(Environment environment) {
        return instances.findAllByOrderByNameAsc().stream().filter(i -> i.getEnvironment() == environment).toList();
    }

    Map<Long, ConnectivityStatus> statuses() { return connections.latestInstanceStatuses(); }
}
