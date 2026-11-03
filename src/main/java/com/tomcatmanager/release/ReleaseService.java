package com.tomcatmanager.release;

import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.application.ManagedApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Release library rules. File I/O happens outside database transactions; database writes are
 * short transactions. Stored bytes are never modified; only release metadata can change.
 */
@Service
public class ReleaseService {
    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);
    static final Pattern VERSION_LABEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,99}$");
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._#+-]{0,250}\\.[Ww][Aa][Rr]$");

    private final ReleaseRepository releases;
    private final StoredArtifactRepository artifacts;
    private final ApplicationRepository applications;
    private final ArtifactStorage storage;
    private final WarInspector inspector;
    private final TransactionTemplate tx;
    private final com.tomcatmanager.audit.AuditService audit;

    public ReleaseService(ReleaseRepository releases, StoredArtifactRepository artifacts, ApplicationRepository applications,
                          ArtifactStorage storage, WarInspector inspector, TransactionTemplate tx, com.tomcatmanager.audit.AuditService audit) {
        this.releases = releases; this.artifacts = artifacts; this.applications = applications;
        this.storage = storage; this.inspector = inspector; this.tx = tx; this.audit = audit;
    }

    public record UploadCommand(long applicationId, String versionLabel, String releaseNotes, String originalFilename) { }

    public Release upload(UploadCommand command, InputStream content, String actor) throws IOException, InvalidWarException {
        ManagedApplication application = applications.findById(command.applicationId())
                .orElseThrow(() -> new IllegalArgumentException("Choose an existing application."));
        String label = validateLabel(command.versionLabel());
        if (releases.existsByApplicationIdAndVersionLabelIgnoreCase(application.getId(), label))
            throw new IllegalArgumentException("Version " + label + " already exists for " + application.getName() + ". Use a different version label.");
        String filename = sanitizeFilename(command.originalFilename());
        String notes = blankToNull(command.releaseNotes());

        ArtifactStorage.StagedFile staged = storage.stage(content);
        try {
            StoredArtifact artifact = artifacts.findBySha256(staged.sha256()).orElse(null);
            if (artifact != null) {
                releases.findByApplicationIdAndArtifactId(application.getId(), artifact.getId()).ifPresent(existing -> {
                    throw new DuplicateReleaseException("This exact WAR (same SHA-256) is already release " + existing.getVersionLabel()
                            + " of " + application.getName() + ".", existing.getId());
                });
            }
            return store(application, staged, artifact, null, label, filename, notes, actor, "");
        } finally {
            storage.discard(staged); // no-op when the file was committed or already discarded
        }
    }

    /** A Quick Deployment upload: the release to deploy, and whether it already existed (identical WAR). */
    public record QuickUpload(Release release, boolean reused) { }

    /**
     * Quick Deployment upload. The WAR is validated before {@code application} is resolved, so an
     * invalid file never creates an application. If the identical WAR is already a release of the
     * application, that release is reused. Without a version label one is derived (see
     * {@link QuickReleaseNaming}) and made unique with a -2, -3, … suffix; a label typed by the user must be new.
     */
    public QuickUpload uploadForQuickDeployment(Supplier<ManagedApplication> application, String versionLabel, String originalFilename,
                                                InputStream content, String actor) throws IOException, InvalidWarException {
        String filename = sanitizeFilename(originalFilename);
        String requested = versionLabel == null || versionLabel.isBlank() ? null : validateLabel(versionLabel);
        ArtifactStorage.StagedFile staged = storage.stage(content);
        try {
            StoredArtifact artifact = artifacts.findBySha256(staged.sha256()).orElse(null);
            WarInspection inspection = artifact == null ? inspector.inspect(staged.path()) : null;
            ManagedApplication app = application.get();
            if (artifact != null) {
                var existing = releases.findByApplicationIdAndArtifactId(app.getId(), artifact.getId());
                if (existing.isPresent()) {
                    Release r = existing.get();
                    if (r.isArchived()) throw new IllegalArgumentException("This exact WAR is release " + r.getVersionLabel() + " of " + app.getName()
                            + ", which is archived. Restore it in the release library first.");
                    log.info("Quick Deployment by {} reuses release {} ({} {}): identical WAR", actor, r.getId(), app.getName(), r.getVersionLabel());
                    return new QuickUpload(releases.findById(r.getId()).orElseThrow(), true);
                }
            }
            String label;
            if (requested != null) {
                if (releases.existsByApplicationIdAndVersionLabelIgnoreCase(app.getId(), requested))
                    throw new IllegalArgumentException("Version " + requested + " already exists for " + app.getName() + " with different content. Use another version, or leave it empty to number it automatically.");
                label = requested;
            } else {
                label = uniqueLabel(app.getId(), QuickReleaseNaming.versionLabel(filename, staged.path()));
            }
            return new QuickUpload(store(app, staged, artifact, inspection, label, filename, null, actor, " via Quick Deployment"), false);
        } finally {
            storage.discard(staged);
        }
    }

    private String uniqueLabel(long applicationId, String base) {
        String trimmed = base.length() > 90 ? base.substring(0, 90) : base;
        if (!releases.existsByApplicationIdAndVersionLabelIgnoreCase(applicationId, trimmed)) return trimmed;
        for (int n = 2; n < 1000; n++) {
            String candidate = trimmed + "-" + n;
            if (!releases.existsByApplicationIdAndVersionLabelIgnoreCase(applicationId, candidate)) return candidate;
        }
        throw new IllegalArgumentException("Could not find a free version label for " + base + ". Enter a version label.");
    }

    /**
     * Stores staged bytes (or reuses {@code artifact} when identical bytes are already stored) and
     * records the release. {@code inspection} may be null; the WAR is then inspected here.
     */
    private Release store(ManagedApplication application, ArtifactStorage.StagedFile staged, StoredArtifact artifact, WarInspection inspection,
                          String label, String filename, String notes, String actor, String via) throws IOException, InvalidWarException {
        if (artifact != null) {
            storage.discard(staged); // identical bytes already stored: reuse them
        } else {
            WarInspection checked = inspection != null ? inspection : inspector.inspect(staged.path());
            String path = storage.commit(staged);
            artifact = saveArtifact(new StoredArtifact(staged.sha256(), staged.size(), path, checked));
        }
        StoredArtifact finalArtifact = artifact;
        Release saved;
        try {
            saved = tx.execute(status -> releases.save(new Release(application, finalArtifact, label, filename, notes, actor)));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Version " + label + " or this exact WAR was just added for " + application.getName() + " by someone else.");
        }
        log.info("Release {} ({} {}) uploaded by {}{}: sha256={} size={}", saved.getId(), application.getName(), label, actor, via, finalArtifact.getSha256(), finalArtifact.getSizeBytes());
        audit.record(actor, "RELEASE_UPLOADED", "RELEASE", saved.getId(), com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS,
                "Uploaded " + application.getName() + " " + label + via + " (" + filename + ", " + ArtifactStorage.humanSize(finalArtifact.getSizeBytes())
                        + ", SHA-256 " + finalArtifact.getSha256() + ").");
        return releases.findById(saved.getId()).orElseThrow();
    }

    private StoredArtifact saveArtifact(StoredArtifact candidate) {
        try {
            return tx.execute(status -> artifacts.save(candidate));
        } catch (DataIntegrityViolationException e) {
            // A concurrent upload of identical bytes inserted the row first.
            return artifacts.findBySha256(candidate.getSha256()).orElseThrow(() -> e);
        }
    }

    public Release find(long id) {
        return releases.findById(id).orElseThrow(() -> new NoSuchElementException("Release " + id + " not found."));
    }

    public Page<Release> search(Long applicationId, String query, boolean includeArchived, int page, int size) {
        String q = query == null || query.isBlank() ? "" : "%" + query.trim().toLowerCase() + "%";
        return releases.search(applicationId == null ? 0 : applicationId, includeArchived, q,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by(Sort.Direction.DESC, "uploadedAt")));
    }

    /** Recomputes the SHA-256 of the stored bytes and records the outcome on the release. */
    public ArtifactStorage.Verification verify(long id, String actor) {
        Release release = find(id);
        ArtifactStorage.Verification result = storage.verify(release.getArtifact());
        tx.executeWithoutResult(status -> {
            Release r = find(id);
            r.recordVerification(result.ok());
            releases.save(r);
        });
        if (result.ok()) log.info("Release {} integrity verified by {}", id, actor);
        else {
            log.warn("Release {} FAILED integrity verification ({}): {}", id, actor, result.message());
            audit.record(actor, "RELEASE_INTEGRITY_FAILED", "RELEASE", id, com.tomcatmanager.audit.AuditEvent.Outcome.FAILURE,
                    "Integrity check of " + release.getApplication().getName() + " " + release.getVersionLabel() + " failed: " + result.message());
        }
        return result;
    }

    public Release updateMetadata(long id, long expectedVersion, String versionLabel, String releaseNotes, String actor) {
        String label = validateLabel(versionLabel);
        String[] before = new String[1];
        Release updated = tx.execute(status -> {
            Release r = find(id);
            if (r.getVersion() != expectedVersion) throw new ObjectOptimisticLockingFailureException(Release.class, id);
            if (!r.getVersionLabel().equalsIgnoreCase(label) && releases.existsByApplicationIdAndVersionLabelIgnoreCase(r.getApplication().getId(), label))
                throw new IllegalArgumentException("Version " + label + " already exists for " + r.getApplication().getName() + ".");
            before[0] = r.getVersionLabel();
            r.updateMetadata(label, blankToNull(releaseNotes), actor);
            log.info("Release {} metadata updated by {}", id, actor);
            return releases.save(r);
        });
        audit.record(actor, "RELEASE_UPDATED", "RELEASE", id, com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS,
                "Updated release metadata" + (before[0].equals(label) ? " of " + label : ": version " + before[0] + " → " + label) + " (release notes saved).");
        return updated;
    }

    public void setArchived(long id, boolean archived, String actor) {
        tx.executeWithoutResult(status -> {
            Release r = find(id);
            r.setArchived(archived, actor);
            releases.save(r);
        });
        log.info("Release {} {} by {}", id, archived ? "archived" : "restored", actor);
        Release r = find(id);
        audit.record(actor, archived ? "RELEASE_ARCHIVED" : "RELEASE_RESTORED", "RELEASE", id, com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS,
                (archived ? "Archived " : "Restored ") + r.getApplication().getName() + " " + r.getVersionLabel() + ".");
    }

    /**
     * Deletes a release record. The stored file is removed only when no other release uses the
     * same bytes. Once deployments exist (Milestone 5), deployed releases must be archived instead.
     */
    public void delete(long id, String actor) {
        Release release = find(id);
        if (releases.countDeployments(id) > 0)
            throw new IllegalStateException("This release has been deployed, so it is kept for the deployment history and rollback. Archive it instead.");
        StoredArtifact artifact = release.getArtifact();
        boolean lastReference = Boolean.TRUE.equals(tx.execute(status -> {
            releases.deleteById(id);
            releases.flush();
            if (releases.countByArtifactId(artifact.getId()) == 0) { artifacts.deleteById(artifact.getId()); return true; }
            return false;
        }));
        if (lastReference) storage.delete(artifact);
        log.info("Release {} ({} {}) deleted by {}{}", id, release.getApplication().getName(), release.getVersionLabel(), actor,
                lastReference ? "; stored file removed" : "; stored file kept (shared)");
        audit.record(actor, "RELEASE_DELETED", "RELEASE", id, com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS,
                "Deleted " + release.getApplication().getName() + " " + release.getVersionLabel() + (lastReference ? "; stored file removed." : "; stored file kept (shared)."));
    }

    static String validateLabel(String label) {
        String l = label == null ? "" : label.trim();
        if (l.isEmpty()) throw new IllegalArgumentException("Enter a version label, e.g. 1.4.2.");
        if (!VERSION_LABEL.matcher(l).matches())
            throw new IllegalArgumentException("The version label may contain letters, digits and . _ + - (max 100), starting with a letter or digit.");
        return l;
    }

    static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) throw new IllegalArgumentException("The uploaded file has no name.");
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (!name.toLowerCase().endsWith(".war")) throw new IllegalArgumentException("Only .war files can be uploaded.");
        if (!SAFE_FILENAME.matcher(name).matches())
            throw new IllegalArgumentException("The file name may contain letters, digits and . _ # + - only. Rename the file and try again.");
        return name;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
