package com.tomcatmanager.release;

import jakarta.persistence.*;
import java.time.Instant;

/** Canonical WAR bytes on disk, addressed by SHA-256. Never modified after creation. */
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
}
