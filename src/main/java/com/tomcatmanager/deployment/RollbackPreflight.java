package com.tomcatmanager.deployment;

import com.tomcatmanager.infrastructure.Environment;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.ArtifactStorage;
import com.tomcatmanager.release.Compatibility;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Checks a requested rollback of one deployment target before anything is changed: a previous
 * version exists and its integrity is verified, the target is reachable and compatible, nothing
 * later changed the application, and no other deployment holds the instance. Evaluated for the
 * confirmation dialog and again on submission.
 */
@Service
public class RollbackPreflight {
    /** Later target states that mean the application was (or may have been) changed after the original target. */
    private static final Set<TargetState> CHANGED_LATER = EnumSet.of(TargetState.SUCCEEDED, TargetState.ROLLBACK_REQUIRED, TargetState.ROLLED_BACK,
            TargetState.UNKNOWN, TargetState.PENDING, TargetState.BACKUP_IN_PROGRESS, TargetState.BACKUP_COMPLETED, TargetState.TRANSFER_IN_PROGRESS,
            TargetState.DEPLOYING, TargetState.HEALTH_CHECKING, TargetState.ROLLING_BACK);

    private final DeploymentTargetRepository targets;
    private final TomcatInstanceRepository instances;
    private final RestorePoints restorePoints;
    private final DeploymentPreflight preflight;

    private final com.tomcatmanager.access.Access access;

    public RollbackPreflight(DeploymentTargetRepository targets, TomcatInstanceRepository instances, RestorePoints restorePoints, DeploymentPreflight preflight,
                             com.tomcatmanager.access.Access access) {
        this.targets = targets; this.instances = instances; this.restorePoints = restorePoints; this.preflight = preflight;
        this.access = access;
    }

    public record Preview(Long targetId, Long runId, String applicationName, String replacedVersion, Long instanceId, String instanceName,
                          String hostName, String hostname, Environment environment, String method, String filename, String contextPath,
                          String restoreDescription, String restoredVersion, Long restoredReleaseId, String restoreSha256, Long restoreSize,
                          String integrity, String connectivity, String compatibility, boolean healthCheck,
                          List<String> blockers, List<String> warnings, boolean production, String confirmationText) {
        public boolean canRollBack() { return blockers.isEmpty(); }
        public boolean requiresAcknowledgement() { return !warnings.isEmpty(); }
    }

    /** Resolved data used when the rollback is accepted. */
    record Evaluation(Preview preview, DeploymentTarget original, TomcatInstance instance, RestorePoints.RestorePoint restorePoint) { }

    public Preview evaluate(long targetId, String actor) { return evaluateFull(targetId, actor).preview(); }

    /**
     * Quick check without remote access: the target installed a version that is still in place and
     * not yet rolled back, so its restore point is the current rollback option.
     */
    public boolean isRollbackCandidate(DeploymentTarget t) {
        if (t.getRun().isRollback() || t.getRolledBackByRunId() != null || t.getInstanceId() == null) return false;
        if (t.getState() != TargetState.SUCCEEDED && t.getState() != TargetState.ROLLBACK_REQUIRED) return false;
        return targets.findLaterTargets(t.getInstanceId(), t.getContextPath(), t.getId()).stream().noneMatch(l -> CHANGED_LATER.contains(l.getState()));
    }

    Evaluation evaluateFull(long targetId, String actor) {
        DeploymentTarget original = targets.findWithRun(targetId).orElseThrow(() -> new NoSuchElementException("Deployment target " + targetId + " not found."));
        // Access: a target on an instance outside the user's access does not exist for them; rolling back needs deploy access.
        com.tomcatmanager.access.AccessScope scope = access.current();
        if (original.getInstanceId() == null || !scope.canViewInstance(original.getInstanceId()))
            throw new NoSuchElementException("Deployment target " + targetId + " not found.");
        if (!scope.canDeployTo(original.getInstanceId()))
            throw new com.tomcatmanager.access.NotPermittedException("You need deploy access to " + original.getInstanceName() + " to roll it back.");
        DeploymentRun run = original.getRun();
        List<String> blockers = new ArrayList<>(), warnings = new ArrayList<>();

        if (run.isRollback()) blockers.add("This is already a rollback. To undo it, deploy the wanted release again with the deployment wizard.");
        switch (original.getState()) {
            case SUCCEEDED, ROLLBACK_REQUIRED -> { }
            case ROLLED_BACK -> blockers.add("This deployment was already rolled back" + (original.getRolledBackByRunId() != null ? " by rollback #" + original.getRolledBackByRunId() : "") + ".");
            case UNKNOWN -> blockers.add("The outcome of this target is unknown. Inspect the server and reconcile it first.");
            default -> blockers.add("Only a target that installed a new version (succeeded or rollback required) can be rolled back; this one is " + original.getState().label().toLowerCase() + ".");
        }
        if (original.getRolledBackByRunId() != null && original.getState() != TargetState.ROLLED_BACK)
            blockers.add("This deployment was already rolled back by rollback #" + original.getRolledBackByRunId() + ".");

        TomcatInstance instance = original.getInstanceId() == null ? null : instances.findById(original.getInstanceId()).orElse(null);
        if (instance == null) blockers.add("The Tomcat instance no longer exists.");

        // Nothing after this target may have changed the application, otherwise the restore would undo more than intended.
        for (DeploymentTarget later : targets.findLaterTargets(original.getInstanceId(), original.getContextPath(), original.getId())) {
            String what = (later.getRun().isRollback() ? "Rollback #" : "Deployment #") + later.getRun().getId();
            if (CHANGED_LATER.contains(later.getState()))
                blockers.add(what + " changed " + original.getContextPath() + " on this instance after this deployment; roll back the latest change instead.");
            else if (later.getState() == TargetState.FAILED)
                warnings.add(what + " to " + original.getContextPath() + " failed after this deployment. Check that it did not leave a different version in place.");
        }

        RestorePoints.RestorePoint point = restorePoints.resolve(original).orElse(null);
        String integrity = "Not available";
        if (point == null) {
            blockers.add("No restore point: no backup was taken for this deployment (backup off, or the first deployment) and no previous release is known.");
        } else {
            ArtifactStorage.Verification verification = restorePoints.verify(point);
            integrity = verification.message();
            if (!verification.ok()) blockers.add("The restore point failed its integrity check: " + verification.message());
        }

        String connectivity = "Not checked", compatibility = "Not checked";
        if (instance != null) {
            if (!instance.isEnabled()) blockers.add("The Tomcat instance is disabled.");
            if (!instance.getHost().isEnabled()) blockers.add("The Linux host is disabled.");
            preflight.accessBlockers(instance, blockers);
            if (instance.getDeploymentMethod() != original.getDeploymentMethod())
                warnings.add("The deployment method changed since this deployment; the rollback uses the current method ("
                        + (instance.getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP ? "SSH / SFTP" : "Tomcat Manager") + ").");
            if (instance.getDeploymentFilename() != null && !instance.getDeploymentFilename().equals(original.getTargetFilename()))
                warnings.add("The instance now deploys " + instance.getDeploymentFilename() + "; the rollback restores " + original.getTargetFilename()
                        + " (" + original.getContextPath() + "), the file this deployment replaced.");
            if (instance.restartsTomcat()) {
                try {
                    com.tomcatmanager.infrastructure.TomcatRestart.Commands restart = com.tomcatmanager.infrastructure.TomcatRestart.commandsFor(instance);
                    warnings.add("Tomcat on this instance is restarted for the rollback (" + restart.stop() + ", then " + restart.start()
                            + "): every application on it is unavailable until Tomcat has started again.");
                } catch (IllegalArgumentException e) {
                    blockers.add(e.getMessage() + " Fix the instance's restart settings.");
                }
            }
            if (!instance.isHealthCheckEnabled())
                warnings.add("The application health check is off for this instance: the restored version will not be verified.");
            else if (instance.getHealthUrl() == null)
                blockers.add("No health URL is configured, so the restored version cannot be verified.");

            if (point != null && point.release() != null) {
                Compatibility.Result c = Compatibility.evaluate(Compatibility.requirements(point.release().getApplication(), point.release().getArtifact()),
                        instance.getTomcatVersion(), instance.getJavaVersion());
                compatibility = c.status().name();
                if (c.status() == Compatibility.Status.INCOMPATIBLE) blockers.add("The restored release is incompatible: " + String.join(" ", c.reasons()));
                if (c.status() == Compatibility.Status.UNKNOWN) warnings.add("Compatibility of the restored release could not be confirmed: " + String.join(" ", c.reasons()));
            } else if (point != null) {
                compatibility = "UNKNOWN";
                warnings.add("Compatibility is not checked: the server backup is not a release in the library. It ran on this instance before this deployment.");
            }
            if (blockers.isEmpty()) connectivity = preflight.reachability(instance, actor, blockers, warnings).label();
        }

        Environment environment = instance != null && instance.getEnvironment() != null ? instance.getEnvironment() : run.getEnvironment();
        boolean production = environment.isProduction() || run.getEnvironment().isProduction();
        String replaced = run.getRelease() != null ? run.getRelease().getVersionLabel() : run.getVersionLabel();
        Preview preview = new Preview(original.getId(), run.getId(), run.getApplication().getName(), replaced, original.getInstanceId(),
                original.getInstanceName(), original.getHostName(), original.getHostname(), environment,
                instance == null ? original.getDeploymentMethod().name() : instance.getDeploymentMethod().name(),
                original.getTargetFilename(), original.getContextPath(),
                point == null ? null : point.description(), point == null || point.release() == null ? null : point.release().getVersionLabel(),
                point == null || point.release() == null ? null : point.release().getId(), point == null ? null : point.sha256(),
                point == null ? null : point.size(), integrity, connectivity, compatibility, instance != null && instance.isHealthCheckEnabled(),
                blockers, warnings, production, original.getInstanceName());
        return new Evaluation(preview, original, instance, point);
    }
}
