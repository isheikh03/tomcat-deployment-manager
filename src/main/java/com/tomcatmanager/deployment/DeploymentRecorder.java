package com.tomcatmanager.deployment;

import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.NoSuchElementException;

/**
 * Persists every state change and log line immediately, each in its own short transaction, so the
 * database always reflects the last known state even if the process stops mid-deployment.
 */
@Component
public class DeploymentRecorder {
    private static final Logger log = LoggerFactory.getLogger(DeploymentRecorder.class);

    private final DeploymentRunRepository runs;
    private final DeploymentTargetRepository targets;
    private final DeploymentLogRepository logs;
    private final DeploymentBackupRepository backups;
    private final TransactionTemplate tx;

    public DeploymentRecorder(DeploymentRunRepository runs, DeploymentTargetRepository targets, DeploymentLogRepository logs,
                              DeploymentBackupRepository backups, TransactionTemplate tx) {
        this.runs = runs; this.targets = targets; this.logs = logs; this.backups = backups; this.tx = tx;
    }

    public DeploymentRun run(long runId) { return runs.findById(runId).orElseThrow(() -> new NoSuchElementException("Deployment " + runId + " not found.")); }
    public DeploymentTarget target(long targetId) { return targets.findById(targetId).orElseThrow(() -> new NoSuchElementException("Target " + targetId + " not found.")); }

    public void runState(long runId, DeploymentState next, String summary) {
        retrying(() -> tx.executeWithoutResult(s -> {
            DeploymentRun run = run(runId);
            run.transitionTo(next);
            if (summary != null) run.setSummary(DiagnosticSanitizer.clean(summary, 1000));
            runs.save(run);
        }));
        info(runId, null, "DEPLOYMENT_" + next.name(), "Deployment " + next.label().toLowerCase() + (summary == null ? "." : ": " + summary));
    }

    /** Moves the run to {@code next} only if it is still in {@code expected} (e.g. not cancelled meanwhile). */
    public boolean runStateIf(long runId, DeploymentState expected, DeploymentState next) {
        boolean[] changed = {false};
        retrying(() -> tx.executeWithoutResult(s -> {
            DeploymentRun run = run(runId);
            changed[0] = false;
            if (run.getState() != expected) return;
            run.transitionTo(next);
            runs.save(run);
            changed[0] = true;
        }));
        if (changed[0]) info(runId, null, "DEPLOYMENT_" + next.name(), "Deployment " + next.label().toLowerCase() + ".");
        return changed[0];
    }

    public void targetState(long runId, long targetId, TargetState next, String message) {
        retrying(() -> tx.executeWithoutResult(s -> {
            DeploymentTarget target = target(targetId);
            target.transitionTo(next, message == null ? null : DiagnosticSanitizer.clean(message, 1000));
            targets.save(target);
        }));
        DeploymentLog.Level level = switch (next) {
            case FAILED, PREFLIGHT_FAILED -> DeploymentLog.Level.ERROR;
            case UNKNOWN, ROLLBACK_REQUIRED, SKIPPED -> DeploymentLog.Level.WARN;
            default -> DeploymentLog.Level.INFO;
        };
        log(runId, targetId, level, "TARGET_" + next.name(), message == null ? next.label() + "." : message, null);
    }

    /** Marks the original target of a succeeded rollback; a ROLLBACK_REQUIRED target becomes ROLLED_BACK. */
    public void markRolledBack(long originalTargetId, long rollbackRunId) {
        retrying(() -> tx.executeWithoutResult(s -> { DeploymentTarget t = target(originalTargetId); t.markRolledBack(rollbackRunId); targets.save(t); }));
    }

    public void bytesTransferred(long targetId, long bytes) {
        retrying(() -> tx.executeWithoutResult(s -> { DeploymentTarget t = target(targetId); t.setBytesTransferred(bytes); targets.save(t); }));
    }

    /**
     * Re-reads and re-applies an update when another writer (e.g. a cancel request) changed the same
     * row concurrently. The transition rules are evaluated again on the fresh state each time.
     */
    void retrying(Runnable update) {
        for (int attempt = 1; ; attempt++) {
            try { update.run(); return; }
            catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) { if (attempt >= 5) throw e; }
        }
    }

    public void backup(DeploymentBackup backup) { tx.executeWithoutResult(s -> backups.save(backup)); }

    public void info(long runId, Long targetId, String event, String message) { log(runId, targetId, DeploymentLog.Level.INFO, event, message, null); }
    public void warn(long runId, Long targetId, String event, String message) { log(runId, targetId, DeploymentLog.Level.WARN, event, message, null); }
    public void error(long runId, Long targetId, String event, String message) { log(runId, targetId, DeploymentLog.Level.ERROR, event, message, null); }

    public void log(long runId, Long targetId, DeploymentLog.Level level, String event, String message, Long durationMs) {
        String clean = DiagnosticSanitizer.clean(message, 2000);
        tx.executeWithoutResult(s -> logs.save(new DeploymentLog(runId, targetId, level, event, clean == null ? "-" : clean, durationMs)));
        if (level == DeploymentLog.Level.ERROR) log.warn("Deployment {} target {}: {} {}", runId, targetId, event, clean);
        else log.info("Deployment {} target {}: {} {}", runId, targetId, event, clean);
    }
}
