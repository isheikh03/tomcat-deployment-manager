package com.tomcatmanager.deployment;

import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A backup that a rollback is restoring right now is never deleted, not even with confirmation. */
class BackupDeletionRulesTest {
    private final DeploymentBackupRepository backups = mock(DeploymentBackupRepository.class);
    private final DeploymentTargetRepository targets = mock(DeploymentTargetRepository.class);
    private final DeploymentRunRepository runs = mock(DeploymentRunRepository.class);
    private final RestorePoints restorePoints = mock(RestorePoints.class);
    private final BackupService service = new BackupService(backups, targets, restorePoints, mock(RollbackPreflight.class),
            mock(DeploymentRecorder.class), mock(SettingsService.class), mock(AuditService.class), mock(TransactionTemplate.class), runs);

    private DeploymentRun activeRollbackOf(long backupId) {
        DeploymentRun run = mock(DeploymentRun.class);
        when(run.isRollback()).thenReturn(true);
        when(run.getRollbackBackupId()).thenReturn(backupId);
        return run;
    }

    @Test void aBackupBeingRestoredCannotBeDeleted() {
        DeploymentBackup backup = DeploymentBackup.remoteWar(5L, "run-1/target-5-portal.war", "a".repeat(64), 100);
        org.springframework.test.util.ReflectionTestUtils.setField(backup, "id", 9L);
        when(backups.findById(9L)).thenReturn(Optional.of(backup));
        DeploymentRun rollback = activeRollbackOf(9L);
        when(runs.findByStateIn(any())).thenReturn(List.of(rollback));

        assertThat(service.beingRestored()).containsExactly(9L);
        assertThatThrownBy(() -> service.delete(9L, "root", "disk almost full", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("being restored by a rollback right now");
        verify(restorePoints, never()).backupFile(any());
    }

    @Test void deploymentsThatAreNotRollbacksLockNoBackup() {
        DeploymentRun deployment = mock(DeploymentRun.class);
        when(deployment.isRollback()).thenReturn(false);
        when(runs.findByStateIn(any())).thenReturn(List.of(deployment));
        assertThat(service.beingRestored()).isEmpty();
    }
}
