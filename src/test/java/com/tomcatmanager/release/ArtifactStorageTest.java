package com.tomcatmanager.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactStorageTest {
    @TempDir Path dir;

    private ArtifactStorage storage(DataSize max) throws Exception {
        return new ArtifactStorage(new ReleaseProperties(dir, max, true));
    }

    private static StoredArtifact artifact(String sha, long size, String path) {
        return new StoredArtifact(sha, size, path, new WarInspection(1, false, null, StoredArtifact.ServletApi.UNKNOWN, List.of()));
    }

    @Test void stagingComputesSha256AndCommitIsContentAddressedAndReadOnly() throws Exception {
        ArtifactStorage storage = storage(DataSize.ofMegabytes(5));
        byte[] content = TestWars.war().jakartaWebXml().bytes();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));

        ArtifactStorage.StagedFile staged = storage.stage(new ByteArrayInputStream(content));
        assertThat(staged.sha256()).isEqualTo(expected);
        assertThat(staged.size()).isEqualTo(content.length);

        String path = storage.commit(staged);
        assertThat(path).isEqualTo("sha256/" + expected.substring(0, 2) + "/" + expected.substring(2, 4) + "/" + expected + ".war");
        Path stored = dir.resolve(path);
        assertThat(Files.readAllBytes(stored)).isEqualTo(content);
        assertThat(Files.isWritable(stored)).isFalse();
        assertThat(Files.exists(staged.path())).isFalse();
    }

    @Test void identicalContentIsStoredOnce() throws Exception {
        ArtifactStorage storage = storage(DataSize.ofMegabytes(5));
        byte[] content = TestWars.war().jakartaWebXml().bytes();
        String first = storage.commit(storage.stage(new ByteArrayInputStream(content)));
        ArtifactStorage.StagedFile again = storage.stage(new ByteArrayInputStream(content));
        String second = storage.commit(again);
        assertThat(second).isEqualTo(first);
        assertThat(Files.exists(again.path())).isFalse();
        try (var files = Files.walk(dir.resolve("sha256"))) { assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1); }
    }

    @Test void oversizedUploadIsRejectedAndLeavesNoTemporaryFile() throws Exception {
        ArtifactStorage storage = storage(DataSize.ofBytes(100));
        assertThatThrownBy(() -> storage.stage(new ByteArrayInputStream(new byte[101]))).isInstanceOf(UploadTooLargeException.class);
        try (var files = Files.list(dir.resolve("tmp"))) { assertThat(files.count()).isZero(); }
    }

    @Test void verificationDetectsTamperingAndMissingFiles() throws Exception {
        ArtifactStorage storage = storage(DataSize.ofMegabytes(5));
        byte[] content = TestWars.war().jakartaWebXml().bytes();
        ArtifactStorage.StagedFile staged = storage.stage(new ByteArrayInputStream(content));
        String path = storage.commit(staged);
        StoredArtifact artifact = artifact(staged.sha256(), staged.size(), path);
        assertThat(storage.verify(artifact).ok()).isTrue();

        Path stored = dir.resolve(path);
        stored.toFile().setWritable(true);
        byte[] tampered = content.clone();
        tampered[tampered.length - 1] ^= 1;
        Files.write(stored, tampered);
        assertThat(storage.verify(artifact).ok()).isFalse();
        assertThat(storage.verify(artifact).message()).contains("SHA-256 mismatch");

        Files.delete(stored);
        assertThat(storage.verify(artifact).message()).contains("missing");
    }

    @Test void refusesPathsOutsideTheStore() throws Exception {
        ArtifactStorage storage = storage(DataSize.ofMegabytes(1));
        assertThatThrownBy(() -> storage.resolve("../outside.war")).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.verify(artifact("a".repeat(64), 1, "../../etc/passwd")).ok()).isFalse();
    }

    @Test void humanSizes() {
        assertThat(ArtifactStorage.humanSize(512)).isEqualTo("512 B");
        assertThat(ArtifactStorage.humanSize(1536)).isEqualTo("1.5 KB");
        assertThat(ArtifactStorage.humanSize(1024L * 1024 * 1024)).isEqualTo("1.0 GB");
    }
}
