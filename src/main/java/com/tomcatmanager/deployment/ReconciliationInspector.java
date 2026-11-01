package com.tomcatmanager.deployment;

import com.tomcatmanager.connectivity.RemoteOperationException;
import com.tomcatmanager.health.HealthProbe;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Collects read-only evidence for a target whose outcome is unknown: which WAR is on the server
 * (compared by SHA-256 with the new version and the backup), what Tomcat reports, and one health
 * request. It suggests an outcome; an administrator still decides.
 */
@Service
public class ReconciliationInspector {
    private final DeploymentTargetRepository targets;
    private final DeploymentBackupRepository backups;
    private final TomcatInstanceRepository instances;
    private final RemoteInspector inspector;
    private final RestorePoints restorePoints;
    private final HealthVerifier health;
    private final HealthProbe probe;
    private final DeploymentProperties properties;
    private final DeploymentRecorder recorder;

    public ReconciliationInspector(DeploymentTargetRepository targets, DeploymentBackupRepository backups, TomcatInstanceRepository instances,
                                   RemoteInspector inspector, RestorePoints restorePoints, HealthVerifier health, HealthProbe probe,
                                   DeploymentProperties properties, DeploymentRecorder recorder) {
        this.targets = targets; this.backups = backups; this.instances = instances; this.inspector = inspector; this.restorePoints = restorePoints;
        this.health = health; this.probe = probe; this.properties = properties; this.recorder = recorder;
    }

    /** {@code suggestion} is SUCCEEDED, FAILED, ROLLBACK_REQUIRED or null when the evidence is inconclusive. */
    public record Evidence(List<String> findings, TargetState suggestion, String suggestionText, boolean rollbackPossible) { }

    public Evidence inspect(long runId, long targetId, String actor) {
        DeploymentTarget target = targets.findWithRun(targetId).orElseThrow(() -> new NoSuchElementException("Target not found."));
        if (!target.getRun().getId().equals(runId)) throw new NoSuchElementException("Target not found in this deployment.");
        if (target.getState() != TargetState.UNKNOWN) throw new IllegalStateException("Only targets with an unknown outcome need inspection.");
        TomcatInstance instance = target.getInstanceId() == null ? null : instances.findById(target.getInstanceId()).orElse(null);
        if (instance == null) throw new IllegalStateException("The Tomcat instance no longer exists; reconcile from what you know.");

        DeploymentRun run = target.getRun();
        String newSha = expectedSha(run);
        String oldSha = backups.findByTargetId(targetId).map(DeploymentBackup::getSha256).orElse(null);
        boolean rollbackPossible = !run.isRollback() && restorePoints.resolve(target).isPresent();
        List<String> findings = new ArrayList<>();
        Boolean newInPlace = null, healthy = null;

        try {
            RemoteInspector.Evidence e = inspector.evidence(instance, target.getTargetFilename(), target.getContextPath());
            findings.add(e.description());
            if (e.warSha256() != null) {
                if (e.warSha256().equals(newSha)) { newInPlace = true; findings.add("The file is the " + (run.isRollback() ? "restored" : "new") + " version (SHA-256 matches)."); }
                else if (e.warSha256().equals(oldSha)) { newInPlace = false; findings.add("The file is still the previous version (matches the backup)."); }
                else findings.add("The file matches neither the " + (run.isRollback() ? "restored" : "new") + " version nor the backup.");
            } else if (instance.getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP) {
                newInPlace = false;
            }
            if (e.managerState() != null) findings.add("Tomcat Manager reports " + target.getContextPath() + " as " + e.managerState() + ".");
        } catch (RemoteOperationException e) {
            findings.add("The server could not be inspected: " + e.getMessage());
        }

        if (instance.isHealthCheckEnabled() && instance.getHealthUrl() != null) {
            try {
                HealthProbe.Outcome o = probe.check(instance.healthUrlFor(target.getContextPath()), health.policyFor(instance).expectation(), properties.healthRequestTimeout());
                healthy = o.healthy();
                findings.add("Health check: " + (o.healthy() ? "healthy, " : "not healthy, ") + o.detail());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IllegalArgumentException e) {
                findings.add("Health check not possible: " + e.getMessage());
            }
        } else {
            findings.add("Application health check is off for this instance.");
        }

        TargetState suggestion = null;
        String text = "The evidence is inconclusive. Inspect the server yourself before deciding.";
        if (Boolean.TRUE.equals(newInPlace) && !Boolean.FALSE.equals(healthy)) {
            suggestion = TargetState.SUCCEEDED; text = "The " + (run.isRollback() ? "restored" : "new") + " version is in place" + (healthy != null ? " and healthy" : "") + ": reconcile as succeeded.";
        } else if (Boolean.TRUE.equals(newInPlace) && rollbackPossible) {
            suggestion = TargetState.ROLLBACK_REQUIRED; text = "The new version is in place but not healthy: reconcile as rollback required, then roll back.";
        } else if (Boolean.FALSE.equals(newInPlace)) {
            suggestion = TargetState.FAILED; text = "The new version is not in place: reconcile as failed.";
        }
        recorder.info(runId, targetId, "RECONCILE_INSPECTION", actor + " inspected the server: " + String.join(" ", findings));
        return new Evidence(findings, suggestion, text, rollbackPossible);
    }

    private String expectedSha(DeploymentRun run) {
        if (!run.isRollback()) return run.getRelease().getArtifact().getSha256();
        return targets.findWithRun(run.getRollbackOfTargetId()).flatMap(restorePoints::resolve).map(RestorePoints.RestorePoint::sha256).orElse(null);
    }
}
