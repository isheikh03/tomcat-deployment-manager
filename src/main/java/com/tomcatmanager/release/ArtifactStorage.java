package com.tomcatmanager.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.UUID;

/**
 * Private, content-addressed WAR storage: {@code <storage-dir>/sha256/ab/cd/<sha256>.war}.
 * Uploads are streamed to a temporary file while the SHA-256 is computed (never held in memory),
 * then moved into place atomically. Stored files are made read-only and never rewritten.
 */
@Component
public class ArtifactStorage {
    private static final Logger log = LoggerFactory.getLogger(ArtifactStorage.class);
    private static final int BUFFER = 64 * 1024;

    private final Path root;
    private final Path temp;
    private final long maxBytes;

    public ArtifactStorage(ReleaseProperties properties) throws IOException {
        this.root = properties.storageDir().toAbsolutePath().normalize();
        this.temp = root.resolve("tmp");
        this.maxBytes = properties.maxUploadSize().toBytes();
        Files.createDirectories(root.resolve("sha256"));
        Files.createDirectories(temp);
        log.info("Release artifacts are stored in {}", root);
    }

    public long maxBytes() { return maxBytes; }

    /** A file written to temporary storage with its checksum, not yet part of the library. */
    public record StagedFile(Path path, String sha256, long size) { }

    /** Streams {@code in} to a temporary file, computing SHA-256 and enforcing the size limit. */
    public StagedFile stage(InputStream in) throws IOException {
        Path file = temp.resolve(UUID.randomUUID() + ".part");
        MessageDigest digest = sha256();
        long total = 0;
        try (InputStream source = new DigestInputStream(in, digest);
             OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new UploadTooLargeException("The file is larger than the " + humanSize(maxBytes) + " upload limit.");
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        if (total == 0) { Files.deleteIfExists(file); throw new IllegalArgumentException("The uploaded file is empty."); }
        return new StagedFile(file, HexFormat.of().formatHex(digest.digest()), total);
    }

    public void discard(StagedFile staged) {
        try { Files.deleteIfExists(staged.path()); } catch (IOException e) { log.warn("Could not delete temporary upload {}", staged.path().getFileName()); }
    }

    /**
     * Moves a staged file to its content address and returns the relative storage path. When the
     * same content is already stored, the existing file is kept untouched and the staged copy discarded.
     */
    public String commit(StagedFile staged) throws IOException {
        String relative = relativePath(staged.sha256());
        Path target = resolve(relative);
        if (Files.exists(target)) {
            discard(staged);
            return relative;
        }
        Files.createDirectories(target.getParent());
        try {
            try {
                Files.move(staged.path(), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged.path(), target);
            }
        } catch (FileAlreadyExistsException e) {
            discard(staged); // another upload of identical bytes won the race
            return relative;
        }
        if (!target.toFile().setWritable(false, false)) log.warn("Could not mark {} read-only", target.getFileName());
        return relative;
    }

    /** Recomputes the checksum of a stored file and compares it with the recorded one. */
    public Verification verify(StoredArtifact artifact) {
        Path file;
        try { file = resolve(artifact.getStoragePath()); }
        catch (IllegalArgumentException e) { return new Verification(false, "The recorded storage path is invalid."); }
        if (!Files.isRegularFile(file)) return new Verification(false, "The stored file is missing.");
        try {
            long size = Files.size(file);
            if (size != artifact.getSizeBytes())
                return new Verification(false, "The stored file is " + size + " bytes but " + artifact.getSizeBytes() + " bytes were recorded.");
            String actual = sha256Of(file);
            return actual.equals(artifact.getSha256())
                    ? new Verification(true, "SHA-256 matches the value recorded at upload.")
                    : new Verification(false, "SHA-256 mismatch: the stored file has changed since upload (" + actual.substring(0, 12) + "…).");
        } catch (IOException e) {
            return new Verification(false, "The stored file could not be read.");
        }
    }

    public record Verification(boolean ok, String message) { }

    /** Opens the stored bytes for reading (used by the deployment engine). */
    public InputStream open(StoredArtifact artifact) throws IOException {
        return Files.newInputStream(resolve(artifact.getStoragePath()));
    }

    public void delete(StoredArtifact artifact) {
        try {
            Path file = resolve(artifact.getStoragePath());
            file.toFile().setWritable(true, true);
            Files.deleteIfExists(file);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not delete stored artifact {}: {}", artifact.getSha256(), e.getMessage());
        }
    }

    /** Temporary upload files not modified for longer than {@code age} (left behind by interrupted uploads). */
    public List<Path> staleTempFiles(Duration age) throws IOException {
        Instant cutoff = Instant.now().minus(age);
        try (Stream<Path> files = Files.list(temp)) {
            return files.filter(Files::isRegularFile).filter(p -> modifiedBefore(p, cutoff)).toList();
        }
    }

    /** Relative paths of all stored WAR files, with their last-modified time. */
    public Map<String, Instant> storedFiles() throws IOException {
        Map<String, Instant> result = new HashMap<>();
        Path base = root.resolve("sha256");
        try (Stream<Path> files = Files.walk(base)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator)
                result.put(root.relativize(p).toString().replace('\\', '/'), Files.getLastModifiedTime(p).toInstant());
        }
        return result;
    }

    public boolean exists(String relativePath) {
        try { return Files.isRegularFile(resolve(relativePath)); } catch (IllegalArgumentException e) { return false; }
    }

    /** Deletes a file in the store or temp area (read-only flag is cleared first). */
    public boolean deleteFile(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalArgumentException("Refusing to delete outside the artifact store.");
        try {
            normalized.toFile().setWritable(true, true);
            return Files.deleteIfExists(normalized);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", normalized.getFileName(), e.getMessage());
            return false;
        }
    }

    public Path resolveStored(String relativePath) { return resolve(relativePath); }

    private static boolean modifiedBefore(Path p, Instant cutoff) {
        try { return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff); } catch (IOException e) { return false; }
    }

    /** Resolves a relative storage path and refuses anything outside the storage root. */
    Path resolve(String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.resolve("sha256"))) throw new IllegalArgumentException("Storage path outside the artifact store.");
        return resolved;
    }

    static String relativePath(String sha256) {
        return "sha256/" + sha256.substring(0, 2) + "/" + sha256.substring(2, 4) + "/" + sha256 + ".war";
    }

    public static String sha256Of(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            byte[] buffer = new byte[BUFFER];
            while (in.read(buffer) != -1) { /* digest only */ }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return (value >= 100 ? String.format(java.util.Locale.ROOT, "%.0f", value) : String.format(java.util.Locale.ROOT, "%.1f", value)) + " " + units[unit];
    }
}
