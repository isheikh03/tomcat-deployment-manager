package com.tomcatmanager.release;

import com.tomcatmanager.application.ManagedApplication;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * A release of an application: version label and notes pointing at immutable stored bytes.
 * Only metadata (label, notes, archived) can change; the artifact reference never does.
 */
@Entity
@Table(name = "release_artifacts")
public class Release {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private ManagedApplication application;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "artifact_id", nullable = false, updatable = false)
    private StoredArtifact artifact;
    @Column(name = "version_label", nullable = false, length = 100) private String versionLabel;
    @Column(name = "original_filename", nullable = false, length = 255, updatable = false) private String originalFilename;
    @Column(name = "release_notes", columnDefinition = "text") private String releaseNotes;
    @Column(nullable = false) private boolean archived;
    @Column(name = "uploaded_by", nullable = false, length = 120, updatable = false) private String uploadedBy;
    @Column(name = "uploaded_at", nullable = false, updatable = false) private Instant uploadedAt = Instant.now();
    @Column(name = "updated_by", nullable = false, length = 120) private String updatedBy;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Column(name = "last_verified_at") private Instant lastVerifiedAt;
    @Column(name = "last_verification_ok") private Boolean lastVerificationOk;
    @Version private long version;

    protected Release() { }

    public Release(ManagedApplication application, StoredArtifact artifact, String versionLabel, String originalFilename, String releaseNotes, String actor) {
        this.application = application; this.artifact = artifact; this.versionLabel = versionLabel;
        this.originalFilename = originalFilename; this.releaseNotes = releaseNotes; this.uploadedBy = actor; this.updatedBy = actor;
    }

    public Long getId() { return id; }
    public ManagedApplication getApplication() { return application; }
    public StoredArtifact getArtifact() { return artifact; }
    public String getVersionLabel() { return versionLabel; }
    public String getOriginalFilename() { return originalFilename; }
    public String getReleaseNotes() { return releaseNotes; }
    public boolean isArchived() { return archived; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public Boolean getLastVerificationOk() { return lastVerificationOk; }
    public long getVersion() { return version; }
    public boolean hasReleaseNotes() { return releaseNotes != null && !releaseNotes.isBlank(); }

    void updateMetadata(String versionLabel, String releaseNotes, String actor) {
        this.versionLabel = versionLabel; this.releaseNotes = releaseNotes; touch(actor);
    }
    void setArchived(boolean archived, String actor) { this.archived = archived; touch(actor); }
    void recordVerification(boolean ok) { this.lastVerifiedAt = Instant.now(); this.lastVerificationOk = ok; }
    private void touch(String actor) { this.updatedBy = actor; this.updatedAt = Instant.now(); }
}
