package com.tomcatmanager.release;

import java.time.Instant;

/** API view of a release; never exposes the server-side storage path. */
public record ReleaseDto(Long id, Long applicationId, String applicationName, String versionLabel, String originalFilename,
                         String releaseNotes, boolean archived, String sha256, long sizeBytes, String size,
                         Integer detectedJavaVersion, StoredArtifact.ServletApi detectedServletApi, String inspectionNotes,
                         String uploadedBy, Instant uploadedAt, Instant lastVerifiedAt, Boolean lastVerificationOk, long version, String url) {

    public static ReleaseDto from(Release r) {
        StoredArtifact a = r.getArtifact();
        return new ReleaseDto(r.getId(), r.getApplication().getId(), r.getApplication().getName(), r.getVersionLabel(), r.getOriginalFilename(),
                r.getReleaseNotes(), r.isArchived(), a.getSha256(), a.getSizeBytes(), ArtifactStorage.humanSize(a.getSizeBytes()),
                a.getDetectedJavaVersion(), a.getDetectedServletApi(), a.getInspectionNotes(),
                r.getUploadedBy(), r.getUploadedAt(), r.getLastVerifiedAt(), r.getLastVerificationOk(), r.getVersion(), "/releases/" + r.getId());
    }
}
