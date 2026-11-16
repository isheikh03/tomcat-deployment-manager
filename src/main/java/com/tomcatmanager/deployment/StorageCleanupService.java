package com.tomcatmanager.deployment;

import com.tomcatmanager.release.ArtifactStorage;
import com.tomcatmanager.release.Release;
import com.tomcatmanager.release.ReleaseRepository;
import com.tomcatmanager.release.ReleaseService;
import com.tomcatmanager.release.StoredArtifact;
import com.tomcatmanager.release.StoredArtifactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Storage cleanup for super users: removing release WAR files (keeping the release records, deployment
 * history and audit trail) and deleting selected server backups. Rules:
 * <ul>
 *   <li>the file a deployment or rollback is copying right now is never removed;</li>
 *   <li>the file of a version running on an instance, or one a rollback would restore, is removed only with
 *       explicit confirmation; it then cannot be redeployed or rolled back to until the identical WAR is
 *       uploaded again (the version running on the server is not touched);</li>
 *   <li>the administrator selects the items to remove; each is re-checked on the server when confirmed.</li>
 * </ul>
 */
@Service
public class StorageCleanupService {
    private static final Set<DeploymentState> ACTIVE = EnumSet.of(DeploymentState.PENDING, DeploymentState.VALIDATING,
            DeploymentState.RUNNING, DeploymentState.CANCEL_REQUESTED);

    private final ReleaseService releaseService;
    private final ReleaseRepository releases;
    private final StoredArtifactRepository artifacts;
    private final DeploymentTargetRepository targets;
    private final DeploymentRunRepository runs;
    private final RollbackPreflight rollbacks;
    private final RestorePoints restorePoints;
    private final BackupService backups;
    private final TransactionTemplate tx;

    public StorageCleanupService(ReleaseService releaseService, ReleaseRepository releases, StoredArtifactRepository artifacts,
                                 DeploymentTargetRepository targets, DeploymentRunRepository runs, RollbackPreflight rollbacks,
                                 RestorePoints restorePoints, BackupService backups, TransactionTemplate tx) {
        this.releaseService = releaseService; this.releases = releases; this.artifacts = artifacts; this.targets = targets; this.runs = runs;
        this.rollbacks = rollbacks; this.restorePoints = restorePoints; this.backups = backups; this.tx = tx;
    }

    public enum FileState {
        STORED("Stored"), RUNNING("In use"), ROLLBACK_POINT("Rollback point"), DEPLOYING("Being deployed"), REMOVED("File removed");
        private final String label;
        FileState(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Why stored files are protected right now, per artifact id. */
    record Usage(Map<Long, List<String>> deploying, Map<Long, List<String>> running, Map<Long, List<String>> rollbackPoints) {
        FileState state(StoredArtifact a) {
            if (a.isFileRemoved()) return FileState.REMOVED;
            if (deploying.containsKey(a.getId())) return FileState.DEPLOYING;
            if (running.containsKey(a.getId())) return FileState.RUNNING;
            if (rollbackPoints.containsKey(a.getId())) return FileState.ROLLBACK_POINT;
            return FileState.STORED;
        }
        List<String> reasons(StoredArtifact a) {
            List<String> all = new ArrayList<>(deploying.getOrDefault(a.getId(), List.of()));
            all.addAll(running.getOrDefault(a.getId(), List.of()));
            all.addAll(rollbackPoints.getOrDefault(a.getId(), List.of()));
            return all;
        }
    }

    /** One stored WAR file with the releases that share it and whether it may be removed. */
    public record ReleaseFile(StoredArtifact artifact, List<Release> releases, FileState state, List<String> reasons,
                              Instant lastDeployedAt, Instant lastActivity) {
        public String size() { return ArtifactStorage.humanSize(artifact.getSizeBytes()); }
        /** Still on disk and not being deployed right now. */
        public boolean removable() { return state == FileState.STORED || isProtected(); }
        /** In use on an instance or a rollback point: removing it needs explicit confirmation. */
        public boolean isProtected() { return state == FileState.RUNNING || state == FileState.ROLLBACK_POINT; }
        public String releaseNames() {
            return String.join(", ", releases.stream().map(r -> r.getApplication().getName() + " " + r.getVersionLabel()).toList());
        }
    }

    Usage usage() {
        return tx.execute(status -> {
            Map<Long, List<String>> deploying = new HashMap<>(), running = new HashMap<>(), rollbackPoints = new HashMap<>();
            for (DeploymentTarget t : targets.findAllCurrentReleaseDeployments())
                add(running, t.getRun().getRelease().getArtifact().getId(), "Currently running on " + t.getInstanceName() + " at " + t.getContextPath());
            for (DeploymentRun r : runs.findByStateIn(ACTIVE))
                if (r.getRelease() != null) add(deploying, r.getRelease().getArtifact().getId(), "Being deployed by " + (r.isRollback() ? "rollback #" : "deployment #") + r.getId());
            for (DeploymentTarget t : targets.findPossibleLibraryRollbacks()) {
                if (!rollbacks.isRollbackCandidate(t)) continue;
                restorePoints.resolve(t).filter(p -> !p.isServerBackup() && p.release() != null).ifPresent(p -> add(rollbackPoints,
                        p.release().getArtifact().getId(), "Rollback point of deployment #" + t.getRun().getId() + " on " + t.getInstanceName() + " at " + t.getContextPath()));
            }
            return new Usage(deploying, running, rollbackPoints);
        });
    }

    private static void add(Map<Long, List<String>> map, Long artifactId, String reason) {
        List<String> list = map.computeIfAbsent(artifactId, k -> new ArrayList<>());
        if (!list.contains(reason)) list.add(reason);
    }

    /** The stored file of a release, for its detail page. */
    public ReleaseFile describe(Release release) {
        return describe(release.getArtifact(), usage(), lastDeployed());
    }

    private ReleaseFile describe(StoredArtifact artifact, Usage usage, Map<Long, Instant> lastDeployed) {
        List<Release> sharing = releases.findByArtifactIdOrderByUploadedAtDesc(artifact.getId());
        Instant deployed = lastDeployed.get(artifact.getId());
        Instant activity = sharing.stream().map(Release::getUploadedAt).max(Comparator.naturalOrder()).orElse(artifact.getCreatedAt());
        if (deployed != null && deployed.isAfter(activity)) activity = deployed;
        return new ReleaseFile(artifact, sharing, usage.state(artifact), usage.reasons(artifact), deployed, activity);
    }

    private Map<Long, Instant> lastDeployed() {
        Map<Long, Instant> map = new HashMap<>();
        for (Object[] row : targets.lastDeployedPerArtifact()) if (row[0] != null && row[1] != null) map.put((Long) row[0], (Instant) row[1]);
        return map;
    }

    /**
     * Removes the WAR file of a release (and of every release sharing the same bytes); the release records and
     * history are kept. A file in use on an instance or serving as a rollback point needs {@code confirm}; the
     * file a deployment is copying right now is never removed.
     */
    public void removeReleaseFile(long releaseId, String actor, String reason, boolean confirm) {
        String why = requireReason(reason);
        Release release = releases.findById(releaseId).orElseThrow(() -> new NoSuchElementException("Release " + releaseId + " not found."));
        ReleaseFile file = describe(release);
        String reasons = String.join("; ", file.reasons());
        switch (file.state()) {
            case REMOVED -> throw new IllegalStateException("The WAR file of this release was already removed.");
            case DEPLOYING -> throw new IllegalStateException("This WAR file is being deployed right now (" + reasons
                    + "). Try again when that deployment has finished.");
            case RUNNING, ROLLBACK_POINT -> {
                if (!confirm) throw new IllegalStateException("This WAR file is " + (file.state() == FileState.RUNNING ? "in use" : "a rollback point")
                        + " (" + reasons + "). Confirm that you want to remove it: the version on the server keeps running, but this release "
                        + "cannot be redeployed or rolled back to until the identical WAR is uploaded again.");
                why = why + " (was " + (file.state() == FileState.RUNNING ? "in use" : "a rollback point") + ": " + reasons + ")";
            }
            default -> { }
        }
        releaseService.removeFile(file.artifact().getId(), actor, why);
    }

    // ---- cleanup of selected release files ----

    /**
     * Release files still on disk, optionally narrowed to one application and a search text (version,
     * file name or application name). Nothing is preselected: the administrator picks what to remove.
     */
    public List<ReleaseFile> releaseFiles(Long applicationId, String query) {
        String q = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        Usage usage = usage();
        Map<Long, Instant> lastDeployed = lastDeployed();
        List<ReleaseFile> files = new ArrayList<>();
        for (StoredArtifact a : artifacts.findByFileRemovedAtIsNull()) {
            ReleaseFile f = describe(a, usage, lastDeployed);
            if (f.releases().isEmpty()) continue;
            boolean matches = f.releases().stream().anyMatch(r -> (applicationId == null || applicationId.equals(r.getApplication().getId()))
                    && (q.isEmpty() || (r.getApplication().getName() + " " + r.getVersionLabel() + " " + r.getOriginalFilename())
                            .toLowerCase(java.util.Locale.ROOT).contains(q)));
            if (matches) files.add(f);
        }
        files.sort(Comparator.comparing((ReleaseFile f) -> f.releases().get(0).getApplication().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(f -> f.releases().get(0).getUploadedAt(), Comparator.reverseOrder()));
        return files;
    }

    /** Outcome of removing selected items. */
    public record Result(int removed, long freedBytes, int skipped, List<String> problems) {
        public String message(String what) {
            String text = removed == 0 ? "Nothing was removed." : "Removed " + removed + " " + what + (removed == 1 ? "" : "s") + ", freeing " + ArtifactStorage.humanSize(freedBytes) + ".";
            if (skipped > 0) text += " " + skipped + " skipped: being deployed or restored right now, already removed, or in use / a rollback point without confirmation (checked again just now).";
            if (!problems.isEmpty()) text += " Problems: " + String.join(" ", problems);
            return text;
        }
    }

    /** Removes the selected files, never files in use or rollback points (see the overload with {@code confirmProtected}). */
    public Result removeReleaseFiles(Collection<Long> artifactIds, String actor, String reason) {
        return removeReleaseFiles(artifactIds, actor, reason, false);
    }

    /**
     * Removes the selected files; each is checked again now. A file being deployed or already removed is skipped;
     * one in use or serving as a rollback point is removed only with {@code confirmProtected}, otherwise skipped.
     */
    public Result removeReleaseFiles(Collection<Long> artifactIds, String actor, String reason, boolean confirmProtected) {
        String why = requireReason(reason);
        Set<Long> selected = new LinkedHashSet<>(artifactIds == null ? List.of() : artifactIds);
        if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one release file to remove.");
        Usage usage = usage();
        Map<Long, Instant> lastDeployed = lastDeployed();
        int removed = 0, skipped = 0;
        long freed = 0;
        List<String> problems = new ArrayList<>();
        for (Long id : selected) {
            StoredArtifact a = artifacts.findById(id).orElse(null);
            ReleaseFile f = a == null ? null : describe(a, usage, lastDeployed);
            if (f == null || !(f.state() == FileState.STORED || (confirmProtected && f.isProtected()))) { skipped++; continue; }
            try {
                releaseService.removeFile(id, actor, f.isProtected()
                        ? why + " (was " + (f.state() == FileState.RUNNING ? "in use" : "a rollback point") + ": " + String.join("; ", f.reasons()) + ")" : why);
                removed++;
                freed += a.getSizeBytes();
            } catch (RuntimeException e) {
                problems.add(f.releaseNames() + ": " + e.getMessage());
            }
        }
        return new Result(removed, freed, skipped, problems);
    }

    // ---- cleanup of selected server backups ----

    /** Server backups, optionally of one Tomcat instance (current rollback points included; deleting them needs confirmation). */
    public List<BackupService.Entry> serverBackups(Long instanceId) {
        return backups.list().stream().filter(BackupService.Entry::serverBackup)
                .filter(e -> instanceId == null || instanceId.equals(e.target().getInstanceId())).toList();
    }

    /** Instances that have server backups, for the filter: id → name. */
    public Map<Long, String> backupInstances() {
        Map<Long, String> instances = new java.util.TreeMap<>();
        for (BackupService.Entry e : serverBackups(null))
            if (e.target().getInstanceId() != null) instances.putIfAbsent(e.target().getInstanceId(), e.target().getInstanceName());
        return instances;
    }

    /** Deletes the selected backups, never current rollback points (see the overload with {@code confirmRollbackPoints}). */
    public Result deleteBackups(Collection<Long> backupIds, String actor, String reason) {
        return deleteBackups(backupIds, actor, reason, false);
    }

    /**
     * Deletes the selected backups; each is checked again now. One that is gone or being restored by a rollback
     * right now is skipped; a current rollback point is deleted only with {@code confirmRollbackPoints}, otherwise skipped.
     */
    public Result deleteBackups(Collection<Long> backupIds, String actor, String reason, boolean confirmRollbackPoints) {
        String why = requireReason(reason);
        Set<Long> selected = new LinkedHashSet<>(backupIds == null ? List.of() : backupIds);
        if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one backup to delete.");
        Map<Long, BackupService.Entry> current = new HashMap<>();
        for (BackupService.Entry e : serverBackups(null)) current.put(e.backup().getId(), e);
        Set<Long> restoring = backups.beingRestored();
        int removed = 0, skipped = 0;
        long freed = 0;
        List<String> problems = new ArrayList<>();
        for (Long id : selected) {
            BackupService.Entry e = current.get(id);
            if (e == null || restoring.contains(id) || (e.rollbackPoint() && !confirmRollbackPoints)) { skipped++; continue; }
            try {
                backups.delete(id, actor, why, e.rollbackPoint());
                removed++;
                freed += e.backup().getSizeBytes() == null ? 0 : e.backup().getSizeBytes();
            } catch (RuntimeException ex) {
                problems.add(e.target().getTargetFilename() + " on " + e.target().getInstanceName() + ": " + ex.getMessage());
            }
        }
        return new Result(removed, freed, skipped, problems);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.strip().length() < 5) throw new IllegalArgumentException("Give a reason for the cleanup (at least 5 characters).");
        String r = reason.strip();
        return r.length() > 300 ? r.substring(0, 300) : r;
    }
}
