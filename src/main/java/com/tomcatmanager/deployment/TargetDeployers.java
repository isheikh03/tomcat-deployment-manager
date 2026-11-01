package com.tomcatmanager.deployment;

import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import com.tomcatmanager.connectivity.RemoteOperationException;
import com.tomcatmanager.connectivity.SshConnectivityClient;
import com.tomcatmanager.connectivity.TomcatManagerClient;
import com.tomcatmanager.infrastructure.DeploymentFilename;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.release.ArtifactStorage;
import com.tomcatmanager.release.StoredArtifact;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Executes one target with the instance's deployment method. Every step is recorded before it
 * starts. Until the remote application is touched, any failure is a clean FAILED ("nothing was
 * changed"); after that point an interrupted step is UNKNOWN and must be reconciled, never retried.
 */
@Component
public class TargetDeployers {
    private final ConnectionTestService connections;
    private final SshConnectivityClient ssh;
    private final TomcatManagerClient manager;
    private final ArtifactStorage storage;
    private final HealthVerifier health;
    private final DeploymentRecorder recorder;
    private final DeploymentProperties properties;
    private final Path backupRoot;

    public TargetDeployers(ConnectionTestService connections, SshConnectivityClient ssh, TomcatManagerClient manager, ArtifactStorage storage,
                           HealthVerifier health, DeploymentRecorder recorder, DeploymentProperties properties) throws IOException {
        this.connections = connections; this.ssh = ssh; this.manager = manager; this.storage = storage; this.health = health;
        this.recorder = recorder; this.properties = properties;
        this.backupRoot = properties.backupDir().toAbsolutePath().normalize();
        Files.createDirectories(backupRoot);
    }

    public void execute(TargetJob job) {
        if (job.instance().getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP) deployOverSftp(job);
        else deployViaManager(job);
    }

    // ---------------------------------------------------------------- SSH / SFTP

    private void deployOverSftp(TargetJob job) {
        TomcatInstance instance = job.instance();
        TargetJob.Payload artifact = job.payload();
        String dir = instance.getRemoteDeploymentDirectory();
        String finalPath = SftpOperations.join(dir, job.filename());
        String tempPath = SftpOperations.join(dir, "." + job.filename() + ".tdm-" + job.targetId() + ".part");
        TargetState phase = TargetState.BACKUP_IN_PROGRESS;
        boolean backupAvailable = false;

        recorder.targetState(job.runId(), job.targetId(), phase, "Connecting to " + instance.getHost().getHostname() + " over SSH.");
        try (ClientSession session = ssh.openSession(connections.sshTargetFor(instance.getHost(), Duration.ofSeconds(instance.getTimeoutSeconds())));
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {

            SftpOperations.RemoteState state = SftpOperations.inspect(sftp, dir, job.filename());
            if (state.freeBytes() != null && state.freeBytes() < artifact.size() * 2) {
                recorder.targetState(job.runId(), job.targetId(), TargetState.FAILED, "Not enough free space in " + dir + ". Nothing was changed on the server.");
                return;
            }
            if (job.rollback()) {
                noBackupDuringRollback(job);
            } else if (!instance.isBackupEnabled()) {
                skipBackup(job);
            } else if (state.warExists()) {
                Instant t0 = Instant.now();
                // Unique even if database IDs repeat (e.g. after a restore); existing backups are never overwritten.
                String relative = "run-" + job.runId() + "/target-" + job.targetId() + "-" + System.currentTimeMillis() + "-" + job.filename();
                Path local = backupRoot.resolve(relative);
                SftpOperations.Checksum backup;
                try {
                    backup = SftpOperations.download(sftp, finalPath, local);
                } catch (IOException e) {
                    Files.deleteIfExists(local);
                    recorder.targetState(job.runId(), job.targetId(), TargetState.FAILED, "The backup of the current " + job.filename()
                            + " could not be created (" + DiagnosticSanitizer.describe(e) + "). Nothing was changed on the server.");
                    return;
                }
                local.toFile().setWritable(false, false);
                recorder.backup(DeploymentBackup.remoteWar(job.targetId(), relative, backup.sha256(), backup.size()));
                recorder.log(job.runId(), job.targetId(), DeploymentLog.Level.INFO, "BACKUP_COMPLETED",
                        "Backed up current " + job.filename() + " (" + RemoteInspector.humanSize(backup.size()) + ", SHA-256 " + backup.sha256().substring(0, 12) + "…).",
                        Duration.between(t0, Instant.now()).toMillis());
                backupAvailable = true;
            } else if (job.previousRelease() != null) {
                backupAvailable = recordLibraryBackup(job);
            } else if (state.explodedExists()) {
                recorder.warn(job.runId(), job.targetId(), "NO_BACKUP", "Only an exploded directory exists at " + job.contextPath() + "; no backup is possible (acknowledged at preflight).");
            } else {
                recorder.info(job.runId(), job.targetId(), "NO_BACKUP", "Nothing is deployed at " + job.contextPath() + " yet; no backup needed.");
            }
            phase = TargetState.BACKUP_COMPLETED;
            recorder.targetState(job.runId(), job.targetId(), phase, null);

            if (job.cancelled().getAsBoolean()) {
                recorder.targetState(job.runId(), job.targetId(), TargetState.SKIPPED, "Cancelled before upload. Nothing was changed on the server.");
                return;
            }

            phase = TargetState.TRANSFER_IN_PROGRESS;
            recorder.targetState(job.runId(), job.targetId(), phase, "Uploading " + RemoteInspector.humanSize(artifact.size()) + " to a temporary file.");
            Instant t0 = Instant.now();
            try (InputStream in = artifact.open()) {
                long bytes = SftpOperations.upload(sftp, in, tempPath, b -> recorder.bytesTransferred(job.targetId(), b));
                recorder.log(job.runId(), job.targetId(), DeploymentLog.Level.INFO, "UPLOAD_COMPLETED",
                        "Uploaded " + RemoteInspector.humanSize(bytes) + ".", Duration.between(t0, Instant.now()).toMillis());
            } catch (IOException e) {
                SftpOperations.deleteQuietly(sftp, tempPath);
                recorder.targetState(job.runId(), job.targetId(), TargetState.FAILED, "Upload failed: " + DiagnosticSanitizer.describe(e) + ". Nothing was changed on the server.");
                return;
            }
            SftpOperations.Checksum uploaded = SftpOperations.remoteChecksum(sftp, tempPath);
            if (!uploaded.sha256().equals(artifact.sha256()) || uploaded.size() != artifact.size()) {
                SftpOperations.deleteQuietly(sftp, tempPath);
                recorder.targetState(job.runId(), job.targetId(), TargetState.FAILED,
                        "The uploaded file does not match the " + what(job) + " checksum. It was removed; nothing was changed on the server.");
                return;
            }
            recorder.info(job.runId(), job.targetId(), "UPLOAD_VERIFIED", "Uploaded file verified on the server (SHA-256 matches the " + what(job) + ").");

            phase = TargetState.DEPLOYING;
            recorder.targetState(job.runId(), job.targetId(), phase, "Moving the verified file into place as " + finalPath + ".");
            boolean atomic;
            try {
                atomic = SftpOperations.replace(sftp, tempPath, finalPath);
            } catch (IOException e) {
                // If the temporary file is still there, the rename did not happen and the server is unchanged.
                if (SftpOperations.statOrNull(sftp, tempPath) != null) {
                    SftpOperations.deleteQuietly(sftp, tempPath);
                    recorder.targetState(job.runId(), job.targetId(), TargetState.FAILED,
                            "Could not move the WAR into place: " + DiagnosticSanitizer.describe(e) + ". Nothing was changed on the server.");
                    return;
                }
                throw e;
            }
            recorder.info(job.runId(), job.targetId(), "REMOTE_DEPLOY_REQUESTED", (atomic ? "Atomically replaced " : "Replaced ") + job.filename()
                    + "; Tomcat deploys it automatically (requires autoDeploy=\"true\" on the Host).");
        } catch (RemoteOperationException e) {
            failOrUnknown(job, phase, e.getMessage());
            return;
        } catch (IOException | RuntimeException e) {
            failOrUnknown(job, phase, DiagnosticSanitizer.describe(e));
            return;
        }
        verifyHealth(job, backupAvailable, null);
    }

    // ---------------------------------------------------------------- Tomcat Manager

    private void deployViaManager(TargetJob job) {
        TomcatInstance instance = job.instance();
        TargetJob.Payload artifact = job.payload();
        TargetState phase = TargetState.BACKUP_IN_PROGRESS;
        boolean backupAvailable = false;
        recorder.targetState(job.runId(), job.targetId(), phase, "Checking the current deployment through Tomcat Manager.");
        TomcatManagerClient.ManagerTarget target;
        try {
            target = connections.managerTargetFor(instance, Duration.ofSeconds(instance.getTimeoutSeconds()));
            Map<String, String> apps = manager.listApplications(target);
            if (job.rollback()) noBackupDuringRollback(job);
            else if (!instance.isBackupEnabled()) skipBackup(job);
            else if (job.previousRelease() != null) backupAvailable = recordLibraryBackup(job);
            else if (apps.containsKey(job.contextPath()))
                recorder.warn(job.runId(), job.targetId(), "NO_BACKUP", job.contextPath() + " is deployed but not by this tool; no backup is possible (acknowledged at preflight).");
            else recorder.info(job.runId(), job.targetId(), "NO_BACKUP", "Nothing is deployed at " + job.contextPath() + " yet; no backup needed.");
            phase = TargetState.BACKUP_COMPLETED;
            recorder.targetState(job.runId(), job.targetId(), phase, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failOrUnknown(job, phase, "Interrupted.");
            return;
        } catch (IOException | RuntimeException e) {
            failOrUnknown(job, phase, e instanceof RemoteOperationException ? e.getMessage() : DiagnosticSanitizer.describe(e));
            return;
        }

        if (job.cancelled().getAsBoolean()) {
            recorder.targetState(job.runId(), job.targetId(), TargetState.SKIPPED, "Cancelled before upload. Nothing was changed on the server.");
            return;
        }

        recorder.targetState(job.runId(), job.targetId(), TargetState.TRANSFER_IN_PROGRESS, "Streaming " + RemoteInspector.humanSize(artifact.size()) + " to Tomcat Manager.");
        phase = TargetState.DEPLOYING;
        recorder.targetState(job.runId(), job.targetId(), phase, "Tomcat Manager deploy requested for " + job.contextPath() + ".");
        Instant t0 = Instant.now();
        TomcatManagerClient.ManagerResponse response;
        try {
            response = manager.deploy(target, job.contextPath(), DeploymentFilename.version(job.filename()), () -> {
                try { return artifact.open(); } catch (IOException e) { throw new UncheckedIOException(e); }
            }, artifact.size(), properties.transferTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recorder.targetState(job.runId(), job.targetId(), TargetState.UNKNOWN, "Interrupted while Tomcat Manager was deploying; the outcome is unknown.");
            return;
        } catch (IOException | UncheckedIOException e) {
            recorder.targetState(job.runId(), job.targetId(), TargetState.UNKNOWN, "The connection failed after the deploy request started ("
                    + DiagnosticSanitizer.describe(e) + "). Tomcat may or may not have deployed it; it was not retried. Inspect the server and reconcile.");
            return;
        }
        recorder.bytesTransferred(job.targetId(), artifact.size());
        String first = TomcatManagerClient.firstLineOf(response.body());
        recorder.log(job.runId(), job.targetId(), response.ok() ? DeploymentLog.Level.INFO : DeploymentLog.Level.ERROR, "TOMCAT_RESPONSE",
                "Tomcat Manager answered HTTP " + response.status() + ": " + first, Duration.between(t0, Instant.now()).toMillis());
        if (response.status() == 401 || response.status() == 403) {
            recorder.targetState(job.runId(), job.targetId(), TargetState.FAILED, "Tomcat Manager refused the deployment (HTTP " + response.status() + "). Nothing was changed.");
            return;
        }
        if (!response.ok()) {
            // With update=true Tomcat may already have undeployed the previous version.
            recorder.targetState(job.runId(), job.targetId(), backupAvailable ? TargetState.ROLLBACK_REQUIRED : TargetState.FAILED,
                    "Tomcat Manager reported a failure: " + first + (backupAvailable ? " The previous version may have been undeployed; roll back to restore it." : ""));
            return;
        }
        verifyHealth(job, backupAvailable, target);
    }

    // ---------------------------------------------------------------- shared

    /** A rollback replaces a version this tool deployed (kept in the release library); restore points are never overwritten. */
    private void noBackupDuringRollback(TargetJob job) {
        recorder.info(job.runId(), job.targetId(), "NO_BACKUP", "Rollback: the version being replaced"
                + (job.previousRelease() != null ? " (" + job.previousRelease().getVersionLabel() + ") stays in the release library" : " was deployed by this tool")
                + "; no new backup is taken and existing backups are kept unchanged.");
    }

    private static String what(TargetJob job) { return job.rollback() ? "restore point" : "release"; }

    private void skipBackup(TargetJob job) {
        recorder.warn(job.runId(), job.targetId(), "BACKUP_SKIPPED", "Backup is switched off for this instance; the current "
                + job.filename() + " was not saved and this deployment has no rollback point.");
    }

    private boolean recordLibraryBackup(TargetJob job) {
        StoredArtifact previous = job.previousRelease().getArtifact();
        ArtifactStorage.Verification verification = storage.verify(previous);
        if (!verification.ok()) {
            recorder.warn(job.runId(), job.targetId(), "NO_BACKUP", "Previous release " + job.previousRelease().getVersionLabel()
                    + " cannot serve as backup: " + verification.message());
            return false;
        }
        recorder.backup(DeploymentBackup.libraryRelease(job.targetId(), job.previousRelease().getId(), previous.getSha256(), previous.getSizeBytes()));
        recorder.info(job.runId(), job.targetId(), "BACKUP_COMPLETED", "Rollback point: previous release " + job.previousRelease().getVersionLabel()
                + " (verified in the release library).");
        return true;
    }

    private void verifyHealth(TargetJob job, boolean backupAvailable, TomcatManagerClient.ManagerTarget managerTarget) {
        TomcatInstance instance = job.instance();
        recorder.targetState(job.runId(), job.targetId(), TargetState.HEALTH_CHECKING, "Waiting for the application to become healthy.");
        if (managerTarget != null) {
            try {
                String state = manager.listApplications(managerTarget).get(job.contextPath());
                if (!"running".equals(state)) {
                    recorder.targetState(job.runId(), job.targetId(), backupAvailable ? TargetState.ROLLBACK_REQUIRED : TargetState.FAILED,
                            "Tomcat reports " + job.contextPath() + " as " + (state == null ? "not deployed" : state) + " after deployment.");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recorder.targetState(job.runId(), job.targetId(), TargetState.UNKNOWN, "Interrupted during verification.");
                return;
            } catch (IOException e) {
                recorder.warn(job.runId(), job.targetId(), "MANAGER_LIST_FAILED", "Could not confirm the context state via Tomcat Manager: " + DiagnosticSanitizer.describe(e));
            }
        }
        if (!instance.isHealthCheckEnabled()) {
            recorder.warn(job.runId(), job.targetId(), "HEALTH_CHECK_SKIPPED", "Application health check is switched off for this instance; the application start was not verified.");
            recorder.targetState(job.runId(), job.targetId(), TargetState.SUCCEEDED, managerTarget != null
                    ? "Deployed; Tomcat reports it running. Application health was not checked (switched off for this instance)."
                    : "Deployed; application health was not checked (switched off for this instance).");
            return;
        }
        String url = instance.healthUrlFor(job.contextPath());
        HealthVerifier.Policy policy;
        try {
            policy = health.policyFor(instance);
        } catch (IllegalArgumentException e) {
            recorder.targetState(job.runId(), job.targetId(), backupAvailable ? TargetState.ROLLBACK_REQUIRED : TargetState.FAILED,
                    "The instance's expected health status is invalid (" + e.getMessage() + "); health could not be verified.");
            return;
        }
        recorder.info(job.runId(), job.targetId(), "HEALTH_CHECK_STARTED", "Checking " + url + " (" + policy.describe() + ").");
        HealthVerifier.Result result = health.verify(url, policy, () -> false,
                (n, status, detail) -> { if (n == 1 || n % 6 == 0) recorder.info(job.runId(), job.targetId(), "HEALTH_ATTEMPT", "Attempt " + n + ": " + detail); });
        recorder.log(job.runId(), job.targetId(), result.healthy() ? DeploymentLog.Level.INFO : DeploymentLog.Level.ERROR,
                result.healthy() ? "HEALTH_CHECK_SUCCEEDED" : "HEALTH_CHECK_FAILED", result.message(), result.elapsedMs());
        if (result.healthy()) {
            recorder.targetState(job.runId(), job.targetId(), TargetState.SUCCEEDED, job.rollback() ? "Restored and verified healthy." : "Deployed and verified healthy.");
        } else if (job.rollback()) {
            recorder.targetState(job.runId(), job.targetId(), TargetState.FAILED,
                    "The restored version is in place but did not become healthy: " + result.message() + " Inspect the server; the restore point is kept.");
        } else {
            recorder.targetState(job.runId(), job.targetId(), backupAvailable ? TargetState.ROLLBACK_REQUIRED : TargetState.FAILED,
                    "The new version is in place but did not become healthy: " + result.message()
                            + (backupAvailable ? " A backup is available for rollback." : " No backup was available."));
        }
    }

    private void failOrUnknown(TargetJob job, TargetState phase, String reason) {
        if (phase.remoteMayHaveChanged())
            recorder.targetState(job.runId(), job.targetId(), TargetState.UNKNOWN, reason + " The server may already have changed; inspect it and reconcile.");
        else
            recorder.targetState(job.runId(), job.targetId(), TargetState.FAILED, reason + " Nothing was changed on the server.");
    }
}
