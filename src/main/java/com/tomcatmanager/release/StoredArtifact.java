package com.tomcatmanager.release;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Canonical WAR bytes on disk, addressed by SHA-256. The bytes are never modified; an administrator
 * may remove the file to free space (recorded here), and uploading identical bytes restores it.
 */
@Entity
@Table(name = "stored_artifacts")
public class StoredArtifact {
    public enum ServletApi { JAVAX, JAKARTA, UNKNOWN }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 64, updatable = false) private String sha256;
    @Column(name = "size_bytes", nullable = false, updatable = false) private long sizeBytes;
    @Column(name = "storage_path", nullable = false, length = 500, updatable = false) private String storagePath;
    @Column(name = "entry_count", nullable = false, updatable = false) private int entryCount;
    @Column(name = "has_web_xml", nullable = false, updatable = false) private boolean hasWebXml;
    @Column(name = "detected_java_version", updatable = false) private Integer detectedJavaVersion;
    @Enumerated(EnumType.STRING) @Column(name = "detected_servlet_api", nullable = false, length = 20, updatable = false) private ServletApi detectedServletApi;
    @Column(name = "inspection_notes", length = 1000, updatable = false) private String inspectionNotes;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    /** Set when an administrator removed the file to free space; the metadata above is kept. */
    @Column(name = "file_removed_at") private Instant fileRemovedAt;
    @Column(name = "file_removed_by", length = 60) private String fileRemovedBy;
    @Column(name = "file_removed_reason", length = 500) private String fileRemovedReason;

    protected StoredArtifact() { }

    public StoredArtifact(String sha256, long sizeBytes, String storagePath, WarInspection inspection) {
        this.sha256 = sha256; this.sizeBytes = sizeBytes; this.storagePath = storagePath;
        this.entryCount = inspection.entryCount(); this.hasWebXml = inspection.hasWebXml();
        this.detectedJavaVersion = inspection.javaVersion(); this.detectedServletApi = inspection.servletApi();
        this.inspectionNotes = inspection.notesText();
    }

    public Long getId() { return id; }
    public String getSha256() { return sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStoragePath() { return storagePath; }
    public int getEntryCount() { return entryCount; }
    public boolean isHasWebXml() { return hasWebXml; }
    public Integer getDetectedJavaVersion() { return detectedJavaVersion; }
    public ServletApi getDetectedServletApi() { return detectedServletApi; }
    public String getInspectionNotes() { return inspectionNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isFileRemoved() { return fileRemovedAt != null; }
    public Instant getFileRemovedAt() { return fileRemovedAt; }
    public String getFileRemovedBy() { return fileRemovedBy; }
    public String getFileRemovedReason() { return fileRemovedReason; }

    void markFileRemoved(String actor, String reason) { fileRemovedAt = Instant.now(); fileRemovedBy = actor; fileRemovedReason = reason; }
    void markFileRestored() { fileRemovedAt = null; fileRemovedBy = null; fileRemovedReason = null; }
}
