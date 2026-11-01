package com.tomcatmanager.deployment;

import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.release.ArtifactStorage;
import com.tomcatmanager.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Backup management: lists restore points, verifies their integrity and deletes server backups.
 * The restore point of a deployment that can still be rolled back is never deleted, neither by a
 * user nor by retention.
 */
@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final DeploymentBackupRepository backups;
    private final DeploymentTargetRepository targets;
    private final RestorePoints restorePoints;
    private final RollbackPreflight rollbacks;
    private final DeploymentRecorder recorder;
    private final SettingsService settings;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public BackupService(DeploymentBackupRepository backups, DeploymentTargetRepository targets, RestorePoints restorePoints, RollbackPreflight rollbacks,
                         DeploymentRecorder recorder, SettingsService settings, AuditService audit, TransactionTemplate tx) {
        this.backups = backups; this.targets = targets; this.restorePoints = restorePoints; this.rollbacks = rollbacks;
        this.recorder = recorder; this.settings = settings; this.audit = audit; this.tx = tx;
    }

    /** Server backups kept per instance and context (runtime setting; 0 keeps all). */
    public int retentionPerContext() { return settings.backupRetentionPerContext(); }

    /** A backup with its deployment context; {@code rollbackPoint} means it is the current restore point and protected. */
    public record Entry(DeploymentBackup backup, DeploymentTarget target, boolean rollbackPoint, boolean fileExists) {
        public boolean serverBackup() { return backup.getSource() == DeploymentBackup.Source.REMOTE_WAR; }
        public String sizeLabel() { return backup.getSizeBytes() == null ? "—" : ArtifactStorage.humanSize(backup.getSizeBytes()); }
    }

    public List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        for (DeploymentBackup b : backups.findByDeletedAtIsNullOrderByCreatedAtDesc()) {
            DeploymentTarget t = targets.findWithRun(b.getTargetId()).orElse(null);
            if (t == null) continue;
            boolean exists = b.getSource() != DeploymentBackup.Source.REMOTE_WAR || Files.isRegularFile(restorePoints.backupFile(b));
            entries.add(new Entry(b, t, rollbacks.isRollbackCandidate(t), exists));
        }
        return entries;
    }

    public long totalServerBackupBytes() {
        return backups.totalServerBackupBytes();
    }

    public ArtifactStorage.Verification verify(long backupId, String actor) {
        DeploymentBackup backup = find(backupId);
        ArtifactStorage.Verification result = restorePoints.verifyBackup(backup);
        DeploymentTarget t = recorder.target(backup.getTargetId());
        recorder.log(t.getRun().getId(), t.getId(), result.ok() ? DeploymentLog.Level.INFO : DeploymentLog.Level.ERROR, "BACKUP_VERIFIED",
                actor + " verified the backup: " + result.message(), null);
        return result;
    }

    public void delete(long backupId, String actor, String reason) { delete(backupId, actor, reason, false); }

    /**
     * Deletes a server backup. The current rollback point of a deployment can be deleted too, but
     * only with {@code confirmRollbackPoint}: afterwards that deployment can only be rolled back to
     * the previous library release, if one is known, or not at all.
     */
    public void delete(long backupId, String actor, String reason, boolean confirmRollbackPoint) {
        if (reason == null || reason.strip().length() < 5) throw new IllegalArgumentException("Give a reason for deleting this backup (at least 5 characters).");
        DeploymentBackup backup = find(backupId);
        if (backup.getSource() != DeploymentBackup.Source.REMOTE_WAR)
            throw new IllegalStateException("This restore point is a release in the library, not a backup file. Manage it in the release library.");
        DeploymentTarget t = targets.findWithRun(backup.getTargetId()).orElseThrow();
        boolean rollbackPoint = rollbacks.isRollbackCandidate(t);
        if (rollbackPoint && !confirmRollbackPoint)
            throw new IllegalStateException("This backup is the current rollback point of " + t.getContextPath() + " on " + t.getInstanceName()
                    + ". Confirm that you want to delete it and lose this rollback option.");
        remove(backup, t, actor, reason.strip() + (rollbackPoint ? " (was the current rollback point; " + fallbackAfterDeletion(t) + ")" : ""));
    }

    /** What a rollback of the target could still restore once its server backup is gone. */
    public String fallbackAfterDeletion(DeploymentTarget t) {
        return t.getPreviousReleaseId() != null ? "a rollback can still restore the previous library release"
                : "no rollback is possible for this deployment any more";
    }

    /** Retention: keeps the newest {@code backup-retention-per-context} server backups per instance and context. */
    @Scheduled(initialDelayString = "${app.deployment.backup-housekeeping-interval:PT6H}", fixedDelayString = "${app.deployment.backup-housekeeping-interval:PT6H}")
    public void applyRetention() {
        int keep = settings.backupRetentionPerContext();
        if (keep <= 0) return;
        try {
            Map<String, List<Entry>> byContext = new LinkedHashMap<>();
            for (Entry e : list()) if (e.serverBackup())
                byContext.computeIfAbsent(e.target().getInstanceId() + "|" + e.target().getContextPath(), k -> new ArrayList<>()).add(e);
            for (List<Entry> entries : byContext.values()) {
                // list() is newest first
                for (Entry e : entries.subList(Math.min(keep, entries.size()), entries.size())) {
                    if (e.rollbackPoint()) continue;
                    remove(e.backup(), e.target(), "system", "Retention: only the newest " + keep + " backups per context are kept.");
                }
            }
        } catch (RuntimeException e) {
            log.error("Backup retention failed", e);
        }
    }

    private void remove(DeploymentBackup backup, DeploymentTarget t, String actor, String reason) {
        Path file = restorePoints.backupFile(backup);
        try {
            if (Files.exists(file)) {
                file.toFile().setWritable(true, false); // backups are stored read-only
                Files.delete(file);
            }
            Path dir = file.getParent();
            if (dir != null && dir.getFileName().toString().startsWith("run-")) // remove the per-run folder once empty
                try (var rest = Files.list(dir)) { if (rest.findAny().isEmpty()) Files.delete(dir); } catch (IOException ignored) { }
        } catch (IOException e) {
            throw new IllegalStateException("The backup file could not be deleted; nothing was changed.", e);
        }
        tx.executeWithoutResult(s -> backups.findById(backup.getId()).ifPresent(b -> { b.markDeleted(actor, reason); backups.save(b); }));
        recorder.warn(t.getRun().getId(), t.getId(), "BACKUP_DELETED", actor + " deleted the server backup of " + t.getTargetFilename() + ": " + reason);
        audit.record(actor, "BACKUP_DELETED", "BACKUP", backup.getId(), com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS,
                "Deleted the server backup of " + t.getTargetFilename() + " on " + t.getInstanceName() + " (deployment #" + t.getRun().getId() + "): " + reason);
        log.info("Deleted backup {} of target {} ({}): {}", backup.getId(), t.getId(), actor, reason);
    }

    private DeploymentBackup find(long id) {
        DeploymentBackup b = backups.findById(id).orElseThrow(() -> new NoSuchElementException("Backup " + id + " not found."));
        if (b.isDeleted()) throw new NoSuchElementException("Backup " + id + " was deleted.");
        return b;
    }
}
