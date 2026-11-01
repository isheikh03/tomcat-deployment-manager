package com.tomcatmanager.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageHousekeepingTest {
    @TempDir Path dir;

    @Test void removesStaleTempAndOrphanFilesButKeepsRecentOnesAndReportsMissing() throws Exception {
        ArtifactStorage storage = new ArtifactStorage(new ReleaseProperties(dir, DataSize.ofMegabytes(5), true));
        StoredArtifactRepository repo = mock(StoredArtifactRepository.class);

        // A referenced file, an old orphan, a fresh orphan (upload in flight), an old and a fresh temp file.
        String referenced = storage.commit(storage.stage(new ByteArrayInputStream("referenced".getBytes())));
        String oldOrphan = storage.commit(storage.stage(new ByteArrayInputStream("old-orphan".getBytes())));
        String freshOrphan = storage.commit(storage.stage(new ByteArrayInputStream("fresh-orphan".getBytes())));
        Path oldTemp = Files.writeString(dir.resolve("tmp/old.part"), "x");
        Path freshTemp = Files.writeString(dir.resolve("tmp/fresh.part"), "x");
        FileTime twoHoursAgo = FileTime.from(Instant.now().minus(StorageHousekeeping.GRACE.multipliedBy(2)));
        Files.setLastModifiedTime(storage.resolveStored(oldOrphan), twoHoursAgo);
        Files.setLastModifiedTime(oldTemp, twoHoursAgo);

        StoredArtifact known = artifact("a".repeat(64), referenced);
        StoredArtifact lost = artifact("b".repeat(64), "sha256/bb/bb/" + "b".repeat(64) + ".war");
        when(repo.findAll()).thenReturn(List.of(known, lost));

        StorageHousekeeping.Report report = new StorageHousekeeping(storage, repo).run();
        assertThat(report.tempFilesRemoved()).isEqualTo(1);
        assertThat(report.orphanFilesRemoved()).isEqualTo(1);
        assertThat(report.missing()).containsExactly("b".repeat(64));
        assertThat(report.healthy()).isFalse();
        assertThat(storage.exists(referenced)).isTrue();
        assertThat(storage.exists(oldOrphan)).isFalse();
        assertThat(storage.exists(freshOrphan)).isTrue();
        assertThat(Files.exists(oldTemp)).isFalse();
        assertThat(Files.exists(freshTemp)).isTrue();
    }

    private static StoredArtifact artifact(String sha, String path) {
        return new StoredArtifact(sha, 1, path, new WarInspection(1, false, null, StoredArtifact.ServletApi.UNKNOWN, List.of()));
    }
}
