package com.tomcatmanager.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps the artifact store consistent with the database, at start-up and periodically:
 * <ul>
 *   <li>removes temporary files of uploads interrupted more than an hour ago;</li>
 *   <li>removes stored files no release refers to (left when a database write failed after the
 *       file was stored), once they are older than an hour so in-flight uploads are never touched;</li>
 *   <li>reports database records whose file is missing. Those are never "repaired" automatically.</li>
 * </ul>
 */
@Component
public class StorageHousekeeping {
    private static final Logger log = LoggerFactory.getLogger(StorageHousekeeping.class);
    static final Duration GRACE = Duration.ofHours(1);

    private final ArtifactStorage storage;
    private final StoredArtifactRepository artifacts;
    private volatile Report lastReport;

    public StorageHousekeeping(ArtifactStorage storage, StoredArtifactRepository artifacts) {
        this.storage = storage; this.artifacts = artifacts;
    }

    /** Outcome of one storage check. {@code missing} lists SHA-256 values whose stored file is gone. */
    public record Report(Instant ranAt, int tempFilesRemoved, int orphanFilesRemoved, List<String> missing, String error) {
        public boolean healthy() { return error == null && missing.isEmpty(); }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { run(); }

    @Scheduled(initialDelayString = "${app.release.housekeeping-interval:PT6H}", fixedDelayString = "${app.release.housekeeping-interval:PT6H}")
    public void scheduled() { run(); }

    public synchronized Report run() {
        Report report;
        try {
            int temp = 0;
            for (Path file : storage.staleTempFiles(GRACE)) if (storage.deleteFile(file)) temp++;

            List<StoredArtifact> known = artifacts.findAll();
            Set<String> referenced = new HashSet<>();
            List<String> missing = new ArrayList<>();
            for (StoredArtifact a : known) {
                referenced.add(a.getStoragePath());
                if (!a.isFileRemoved() && !storage.exists(a.getStoragePath())) missing.add(a.getSha256()); // removed on purpose is not missing
            }

            int orphans = 0;
            Instant cutoff = Instant.now().minus(GRACE);
            for (Map.Entry<String, Instant> file : storage.storedFiles().entrySet()) {
                if (!referenced.contains(file.getKey()) && file.getValue().isBefore(cutoff)
                        && storage.deleteFile(storage.resolveStored(file.getKey()))) orphans++;
            }
            report = new Report(Instant.now(), temp, orphans, List.copyOf(missing), null);
            if (temp + orphans > 0) log.info("Storage housekeeping removed {} temporary and {} orphaned file(s)", temp, orphans);
            if (!missing.isEmpty()) log.error("Storage check: {} stored artifact file(s) are MISSING: {}", missing.size(), missing);
        } catch (IOException | RuntimeException e) {
            log.error("Storage check failed: {}", e.getMessage());
            report = new Report(Instant.now(), 0, 0, List.of(), "The storage check could not complete: " + e.getMessage());
        }
        lastReport = report;
        return report;
    }

    public Optional<Report> lastReport() { return Optional.ofNullable(lastReport); }
}
