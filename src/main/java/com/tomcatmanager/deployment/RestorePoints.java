package com.tomcatmanager.deployment;

import com.tomcatmanager.release.ArtifactStorage;
import com.tomcatmanager.release.Release;
import com.tomcatmanager.release.ReleaseRepository;
import com.tomcatmanager.release.StoredArtifactRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds what a rollback of a deployment target restores, and verifies its integrity:
 * the server WAR downloaded before the target replaced it (preferred: the exact bytes that ran),
 * else the previously deployed library release.
 */
@Component
public class RestorePoints {
    private final DeploymentBackupRepository backups;
    private final ReleaseRepository releases;
    private final StoredArtifactRepository artifacts;
    private final ArtifactStorage storage;
    private final TransactionTemplate tx;
    private final Path backupRoot;

    public RestorePoints(DeploymentBackupRepository backups, ReleaseRepository releases, StoredArtifactRepository artifacts,
                         ArtifactStorage storage, TransactionTemplate tx, DeploymentProperties properties) {
        this.backups = backups; this.releases = releases; this.artifacts = artifacts; this.storage = storage; this.tx = tx;
        this.backupRoot = properties.backupDir().toAbsolutePath().normalize();
    }

    /**
     * @param backup  the backup record used (null when restoring the previous library release without a backup record)
     * @param release the restored release; for a server backup, the library release with identical bytes, if any
     * @param file    the local backup file (server backups only)
     */
    public record RestorePoint(DeploymentBackup backup, Release release, Path file, String sha256, long size, String description) {
        boolean isServerBackup() { return file != null; }
    }

    public Optional<RestorePoint> resolve(DeploymentTarget original) {
        Long applicationId = original.getRun().getApplication().getId();
        DeploymentBackup backup = backups.findByTargetId(original.getId()).filter(b -> !b.isDeleted()).orElse(null);
        if (backup != null && backup.getSource() == DeploymentBackup.Source.REMOTE_WAR) {
            Path file = backupFile(backup);
            Release same = artifacts.findBySha256(backup.getSha256())
                    .flatMap(a -> releases.findByApplicationIdAndArtifactId(applicationId, a.getId()))
                    .flatMap(r -> releases.findById(r.getId())).orElse(null);
            return Optional.of(new RestorePoint(backup, same, file, backup.getSha256(), backup.getSizeBytes(),
                    "Server backup of " + original.getTargetFilename() + " (" + ArtifactStorage.humanSize(backup.getSizeBytes()) + ")"
                            + (same != null ? ", identical to release " + same.getVersionLabel() : ", not a release in the library")));
        }
        Long releaseId = backup != null ? backup.getReleaseId() : original.getPreviousReleaseId();
        if (releaseId == null) return Optional.empty();
        return releases.findById(releaseId).map(r -> new RestorePoint(backup, r, null, r.getArtifact().getSha256(), r.getArtifact().getSizeBytes(),
                "Previous release " + r.getVersionLabel() + " from the release library"));
    }

    /** Recomputes the checksum of the restore point. Server backups record the result on the backup. */
    public ArtifactStorage.Verification verify(RestorePoint point) {
        if (!point.isServerBackup()) return storage.verify(point.release().getArtifact());
        ArtifactStorage.Verification result = verifyFile(point.file(), point.sha256(), point.size());
        recordVerification(point.backup().getId(), result.ok());
        return result;
    }

    public ArtifactStorage.Verification verifyBackup(DeploymentBackup backup) {
        if (backup.getSource() != DeploymentBackup.Source.REMOTE_WAR) {
            if (backup.getReleaseId() == null) return new ArtifactStorage.Verification(false, "The referenced release no longer exists.");
            return releases.findById(backup.getReleaseId()).map(r -> storage.verify(r.getArtifact()))
                    .orElse(new ArtifactStorage.Verification(false, "The referenced release no longer exists."));
        }
        ArtifactStorage.Verification result = verifyFile(backupFile(backup), backup.getSha256(), backup.getSizeBytes());
        recordVerification(backup.getId(), result.ok());
        return result;
    }

    TargetJob.Payload payload(RestorePoint point) {
        if (point.isServerBackup())
            return new TargetJob.Payload(point.description(), point.sha256(), point.size(), () -> Files.newInputStream(point.file()));
        return new TargetJob.Payload(point.description(), point.sha256(), point.size(), () -> storage.open(point.release().getArtifact()));
    }

    /** Absolute path of a server backup; never resolves outside the backup directory. */
    Path backupFile(DeploymentBackup backup) {
        Path file = backupRoot.resolve(backup.getStoragePath()).normalize();
        if (!file.startsWith(backupRoot)) throw new IllegalStateException("Backup " + backup.getId() + " has an invalid storage path.");
        return file;
    }

    private static ArtifactStorage.Verification verifyFile(Path file, String sha256, Long size) {
        if (!Files.isRegularFile(file)) return new ArtifactStorage.Verification(false, "The backup file is missing.");
        try {
            long actualSize = Files.size(file);
            if (size != null && actualSize != size)
                return new ArtifactStorage.Verification(false, "The backup file is " + actualSize + " bytes but " + size + " bytes were recorded.");
            String actual = ArtifactStorage.sha256Of(file);
            return actual.equals(sha256) ? new ArtifactStorage.Verification(true, "SHA-256 matches the value recorded when the backup was taken.")
                    : new ArtifactStorage.Verification(false, "SHA-256 mismatch: the backup file has changed since it was taken.");
        } catch (IOException e) {
            return new ArtifactStorage.Verification(false, "The backup file could not be read.");
        }
    }

    private void recordVerification(Long backupId, boolean ok) {
        tx.executeWithoutResult(s -> backups.findById(backupId).ifPresent(b -> { b.verified(ok); backups.save(b); }));
    }
}
