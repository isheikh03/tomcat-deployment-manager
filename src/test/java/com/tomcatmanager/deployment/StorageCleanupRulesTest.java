package com.tomcatmanager.deployment;

import com.tomcatmanager.release.Release;
import com.tomcatmanager.release.ReleaseRepository;
import com.tomcatmanager.release.ReleaseService;
import com.tomcatmanager.release.StoredArtifact;
import com.tomcatmanager.release.StoredArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Which stored WAR files can be removed: a file being deployed never; one in use or a rollback point only with
 * confirmation; release records and history are always kept (only {@link ReleaseService#removeFile} is called).
 */
class StorageCleanupRulesTest {
    private final ReleaseService releaseService = mock(ReleaseService.class);
    private final ReleaseRepository releases = mock(ReleaseRepository.class);
    private final StoredArtifactRepository artifacts = mock(StoredArtifactRepository.class);
    private final DeploymentTargetRepository targets = mock(DeploymentTargetRepository.class);
    private final DeploymentRunRepository runs = mock(DeploymentRunRepository.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final BackupService backupService = mock(BackupService.class);
    private final StorageCleanupService cleanup = new StorageCleanupService(releaseService, releases, artifacts, targets, runs,
            mock(RollbackPreflight.class), mock(RestorePoints.class), backupService, tx);

    private final StoredArtifact file = mock(StoredArtifact.class);

    @SuppressWarnings("unchecked")
    @BeforeEach void oneReleaseWithOneFile() {
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
        when(file.getId()).thenReturn(10L);
        when(file.getSizeBytes()).thenReturn(1000L);
        Release release = mock(Release.class, RETURNS_DEEP_STUBS);
        when(release.getArtifact()).thenReturn(file);
        when(release.getUploadedAt()).thenReturn(Instant.now());
        when(release.getApplication().getName()).thenReturn("Portal");
        when(release.getVersionLabel()).thenReturn("1.0");
        when(releases.findById(1L)).thenReturn(Optional.of(release));
        when(releases.findByArtifactIdOrderByUploadedAtDesc(10L)).thenReturn(List.of(release));
        when(artifacts.findById(10L)).thenReturn(Optional.of(file));
        when(artifacts.findByFileRemovedAtIsNull()).thenReturn(List.of(file));
    }

    private void runningOnTomcatA() {
        DeploymentTarget t = mock(DeploymentTarget.class, RETURNS_DEEP_STUBS);
        when(t.getRun().getRelease().getArtifact().getId()).thenReturn(10L);
        when(t.getInstanceName()).thenReturn("tomcat-a");
        when(t.getContextPath()).thenReturn("/portal");
        when(targets.findAllCurrentReleaseDeployments()).thenReturn(List.of(t));
    }

    @Test void aFileInUseIsRemovedOnlyWithConfirmation() {
        runningOnTomcatA();
        assertThat(cleanup.releaseFiles(null, "").get(0).state()).isEqualTo(StorageCleanupService.FileState.RUNNING);
        assertThat(cleanup.releaseFiles(null, "").get(0).removable()).isTrue();

        assertThatThrownBy(() -> cleanup.removeReleaseFile(1L, "root", "disk almost full", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Confirm").hasMessageContaining("Currently running on tomcat-a");
        verify(releaseService, never()).removeFile(anyLong(), anyString(), anyString());

        cleanup.removeReleaseFile(1L, "root", "disk almost full", true);
        verify(releaseService).removeFile(eq(10L), eq("root"), contains("was in use: Currently running on tomcat-a"));
    }

    @Test void inBulkAFileInUseIsSkippedUnlessConfirmed() {
        runningOnTomcatA();
        StorageCleanupService.Result skipped = cleanup.removeReleaseFiles(List.of(10L), "root", "disk almost full", false);
        assertThat(skipped.removed()).isZero();
        assertThat(skipped.skipped()).isEqualTo(1);
        verify(releaseService, never()).removeFile(anyLong(), anyString(), anyString());

        StorageCleanupService.Result removed = cleanup.removeReleaseFiles(List.of(10L), "root", "disk almost full", true);
        assertThat(removed.removed()).isEqualTo(1);
        assertThat(removed.freedBytes()).isEqualTo(1000L);
        verify(releaseService).removeFile(eq(10L), eq("root"), contains("was in use"));
    }

    @Test void aFileBeingDeployedIsNeverRemoved() {
        DeploymentRun run = mock(DeploymentRun.class, RETURNS_DEEP_STUBS);
        when(run.getRelease().getArtifact().getId()).thenReturn(10L);
        when(run.getId()).thenReturn(7L);
        when(runs.findByStateIn(any())).thenReturn(List.of(run));

        assertThat(cleanup.releaseFiles(null, "").get(0).state()).isEqualTo(StorageCleanupService.FileState.DEPLOYING);
        assertThat(cleanup.releaseFiles(null, "").get(0).removable()).isFalse();
        assertThatThrownBy(() -> cleanup.removeReleaseFile(1L, "root", "disk almost full", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("being deployed right now");
        assertThat(cleanup.removeReleaseFiles(List.of(10L), "root", "disk almost full", true).skipped()).isEqualTo(1);
        verify(releaseService, never()).removeFile(anyLong(), anyString(), anyString());
    }

    // ---- server backups ----

    private void backups(boolean secondIsRollbackPoint) {
        java.util.List<BackupService.Entry> list = new java.util.ArrayList<>();
        for (long id : new long[]{1, 2}) {
            DeploymentBackup b = mock(DeploymentBackup.class);
            when(b.getId()).thenReturn(id);
            when(b.getSource()).thenReturn(DeploymentBackup.Source.REMOTE_WAR);
            when(b.getSizeBytes()).thenReturn(500L);
            list.add(new BackupService.Entry(b, mock(DeploymentTarget.class), id == 2 && secondIsRollbackPoint, true));
        }
        when(backupService.list()).thenReturn(list);
    }

    @Test void aRollbackPointBackupIsDeletedOnlyWithConfirmation() {
        backups(true);
        StorageCleanupService.Result unconfirmed = cleanup.deleteBackups(List.of(1L, 2L), "root", "disk almost full", false);
        assertThat(unconfirmed.removed()).isEqualTo(1);
        assertThat(unconfirmed.skipped()).isEqualTo(1);
        verify(backupService).delete(1L, "root", "disk almost full", false);
        verify(backupService, never()).delete(eq(2L), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        StorageCleanupService.Result confirmed = cleanup.deleteBackups(List.of(2L), "root", "disk almost full", true);
        assertThat(confirmed.removed()).isEqualTo(1);
        verify(backupService).delete(2L, "root", "disk almost full", true);
    }

    @Test void aBackupBeingRestoredIsNeverDeleted() {
        backups(true);
        when(backupService.beingRestored()).thenReturn(java.util.Set.of(2L));
        StorageCleanupService.Result result = cleanup.deleteBackups(List.of(2L), "root", "disk almost full", true);
        assertThat(result.removed()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(backupService, never()).delete(eq(2L), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test void anUnusedFileIsRemovedWithoutConfirmation() {
        cleanup.removeReleaseFile(1L, "root", "old version", false);
        verify(releaseService).removeFile(10L, "root", "old version");
    }
}
