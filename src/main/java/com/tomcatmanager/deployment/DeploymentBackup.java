package com.tomcatmanager.deployment;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * What can be restored if a target's new version is bad: either the WAR downloaded from the
 * server before it was replaced (REMOTE_WAR) or the previously deployed library release.
 */
@Entity
@Table(name = "deployment_backups")
public class DeploymentBackup {
    public enum Source { REMOTE_WAR, LIBRARY_RELEASE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "target_id", nullable = false, unique = true, updatable = false) private Long targetId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30, updatable = false) private Source source;
    @Column(name = "release_id", updatable = false) private Long releaseId;
    @Column(name = "storage_path", length = 500, updatable = false) private String storagePath;
    @Column(length = 64, updatable = false) private String sha256;
    @Column(name = "size_bytes", updatable = false) private Long sizeBytes;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "last_verified_at") private Instant lastVerifiedAt;
    @Column(name = "last_verification_ok") private Boolean lastVerificationOk;
    @Column(name = "deleted_at") private Instant deletedAt;
    @Column(name = "deleted_by", length = 120) private String deletedBy;
    @Column(name = "delete_reason", length = 300) private String deleteReason;

    protected DeploymentBackup() { }

    void verified(boolean ok) { lastVerifiedAt = Instant.now(); lastVerificationOk = ok; }
    void markDeleted(String actor, String reason) { deletedAt = Instant.now(); deletedBy = actor; deleteReason = reason; }

    public static DeploymentBackup remoteWar(Long targetId, String storagePath, String sha256, long size) {
        DeploymentBackup b = new DeploymentBackup();
        b.targetId = targetId; b.source = Source.REMOTE_WAR; b.storagePath = storagePath; b.sha256 = sha256; b.sizeBytes = size;
        return b;
    }

    public static DeploymentBackup libraryRelease(Long targetId, Long releaseId, String sha256, long size) {
        DeploymentBackup b = new DeploymentBackup();
        b.targetId = targetId; b.source = Source.LIBRARY_RELEASE; b.releaseId = releaseId; b.sha256 = sha256; b.sizeBytes = size;
        return b;
    }

    public Long getId() { return id; }
    public Long getTargetId() { return targetId; }
    public Source getSource() { return source; }
    public Long getReleaseId() { return releaseId; }
    public String getStoragePath() { return storagePath; }
    public String getSha256() { return sha256; }
    public Long getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public Boolean getLastVerificationOk() { return lastVerificationOk; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }
    public String getDeletedBy() { return deletedBy; }
    public String getDeleteReason() { return deleteReason; }
}
