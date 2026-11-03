package com.tomcatmanager.deployment;

import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.ArtifactStorage;
import com.tomcatmanager.release.Compatibility;
import com.tomcatmanager.release.Release;
import com.tomcatmanager.release.ReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs deployments in backend workers, independent of any browser request. Concurrency is bounded
 * (deployments and targets); the database, not memory, is the source of truth for progress.
 */
@Component
public class DeploymentEngine implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(DeploymentEngine.class);

    private final DeploymentRecorder recorder;
    private final DeploymentTargetRepository targets;
    private final TomcatInstanceRepository instances;
    private final ReleaseRepository releases;
    private final ArtifactStorage storage;
    private final TargetDeployers deployers;
    private final RestorePoints restorePoints;
    private final DeploymentOutcomeNotifier outcomes;
    private final ExecutorService runWorkers;
    private final ExecutorService targetWorkers;

    public DeploymentEngine(DeploymentRecorder recorder, DeploymentTargetRepository targets, TomcatInstanceRepository instances,
                            ReleaseRepository releases, ArtifactStorage storage, TargetDeployers deployers, RestorePoints restorePoints,
                            DeploymentOutcomeNotifier outcomes, DeploymentProperties properties) {
        this.recorder = recorder; this.targets = targets; this.instances = instances; this.releases = releases;
        this.storage = storage; this.deployers = deployers; this.restorePoints = restorePoints; this.outcomes = outcomes;
        this.runWorkers = Executors.newFixedThreadPool(Math.max(1, properties.maxConcurrentDeployments()), named("deployment-run"));
        this.targetWorkers = Executors.newFixedThreadPool(Math.max(1, properties.maxParallelTargets()), named("deployment-target"));
    }

    public void submit(long runId) { runWorkers.execute(() -> execute(runId)); }

    void execute(long runId) {
        DeploymentRun run = recorder.run(runId);
        MDC.put("correlationId", run.getCorrelationId().toString());
        try {
            if (run.getState() != DeploymentState.PENDING) return; // already handled (e.g. after restart)
            recorder.runStateIf(runId, DeploymentState.PENDING, DeploymentState.VALIDATING);
            List<DeploymentTarget> list = targets.findByRunIdOrderBySequenceAsc(runId);
            if (run.isRollback()) { executeRollback(run, list.get(0)); return; }
            Release release = releases.findById(run.getRelease().getId()).orElseThrow();
            TargetJob.Payload payload = new TargetJob.Payload(release.getVersionLabel(), release.getArtifact().getSha256(), release.getArtifact().getSizeBytes(),
                    () -> storage.open(release.getArtifact()));

            ArtifactStorage.Verification integrity = storage.verify(release.getArtifact());
            if (!integrity.ok()) {
                for (DeploymentTarget t : list) recorder.targetState(runId, t.getId(), TargetState.PREFLIGHT_FAILED, "Release file failed its integrity check.");
                finish(runId, "Release file failed its integrity check before deployment: " + integrity.message());
                return;
            }
            recorder.info(runId, null, "CHECKSUM_VERIFIED", "Release file verified (SHA-256 " + release.getArtifact().getSha256().substring(0, 12) + "…).");

            if (cancelled(runId)) { skipAll(runId, list, "Cancelled before any target started."); finish(runId, null); return; }
            recorder.runStateIf(runId, DeploymentState.VALIDATING, DeploymentState.RUNNING);

            AtomicBoolean failure = new AtomicBoolean(false);
            if (run.getStrategy() == DeploymentRun.Strategy.SEQUENTIAL) {
                for (DeploymentTarget t : list) {
                    if (failure.get() || cancelled(runId)) { skip(runId, t, failure.get()); continue; }
                    if (!runTarget(runId, t, release, payload, false)) failure.set(true);
                }
            } else {
                Semaphore slots = new Semaphore(run.getMaxParallel());
                List<Future<?>> futures = new ArrayList<>();
                for (DeploymentTarget t : list) {
                    slots.acquireUninterruptibly();
                    if (failure.get() || cancelled(runId)) { slots.release(); skip(runId, t, failure.get()); continue; }
                    futures.add(targetWorkers.submit(() -> {
                        try { if (!runTarget(runId, t, release, payload, false)) failure.set(true); }
                        finally { slots.release(); }
                    }));
                }
                for (Future<?> f : futures) {
                    try { f.get(); } catch (Exception e) { log.error("Target task failed unexpectedly", e); }
                }
            }
            finish(runId, null);
        } catch (RuntimeException e) {
            log.error("Deployment {} aborted unexpectedly", runId, e);
            abort(runId, DiagnosticSanitizer.describe(e));
        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * A rollback run: verifies the restore point again right before use, restores it through the
     * normal pipeline (upload, verify, replace, health check) and, only when that succeeded,
     * closes the original target as rolled back.
     */
    private void executeRollback(DeploymentRun run, DeploymentTarget target) {
        long runId = run.getId();
        DeploymentTarget original = targets.findWithRun(run.getRollbackOfTargetId()).orElseThrow();
        RestorePoints.RestorePoint point = restorePoints.resolve(original).orElse(null);
        if (point == null) {
            recorder.targetState(runId, target.getId(), TargetState.PREFLIGHT_FAILED, "The restore point is no longer available. Nothing was changed on the server.");
            finish(runId, "The restore point is no longer available.");
            afterRollback(runId);
            return;
        }
        ArtifactStorage.Verification integrity = restorePoints.verify(point);
        if (!integrity.ok()) {
            recorder.targetState(runId, target.getId(), TargetState.PREFLIGHT_FAILED, "The restore point failed its integrity check: " + integrity.message()
                    + " Nothing was changed on the server.");
            finish(runId, "Restore point failed its integrity check before the rollback: " + integrity.message());
            afterRollback(runId);
            return;
        }
        recorder.info(runId, null, "CHECKSUM_VERIFIED", "Restore point verified (SHA-256 " + point.sha256().substring(0, 12) + "…): " + point.description() + ".");
        if (cancelled(runId)) { skipAll(runId, List.of(target), "Cancelled before the rollback started."); finish(runId, null); afterRollback(runId); return; }
        recorder.runStateIf(runId, DeploymentState.VALIDATING, DeploymentState.RUNNING);
        // The original is linked before the run is finalized, so anyone seeing the finished rollback also sees the original closed.
        if (runTarget(runId, target, point.release(), restorePoints.payload(point), true)) linkToOriginal(recorder.run(runId));
        finish(runId, null);
        afterRollback(runId);
    }

    private void linkToOriginal(DeploymentRun rollback) {
        DeploymentTarget original = recorder.target(rollback.getRollbackOfTargetId());
        if (Long.valueOf(rollback.getId()).equals(original.getRolledBackByRunId())) return;
        recorder.markRolledBack(original.getId(), rollback.getId());
        recorder.info(original.getRun().getId(), original.getId(), "ROLLED_BACK", "Rolled back by rollback #" + rollback.getId()
                + ": restored " + rollback.getRestoreDescription() + ".");
    }

    /** Links a finished rollback to the original target; only a succeeded rollback marks the original as rolled back. */
    void afterRollback(long runId) {
        DeploymentRun run = recorder.run(runId);
        if (!run.isRollback() || !run.getState().isTerminal()) return;
        DeploymentTarget original = recorder.target(run.getRollbackOfTargetId());
        long originalRunId = original.getRun().getId();
        if (run.getState() == DeploymentState.SUCCEEDED) {
            linkToOriginal(run); // no-op when already linked (normal path); needed after reconciliation
        } else {
            recorder.warn(originalRunId, original.getId(), "ROLLBACK_NOT_COMPLETED", "Rollback #" + runId + " ended " + run.getState().label().toLowerCase()
                    + "; this target was not marked as rolled back. See rollback #" + runId + ".");
        }
    }

    /** Runs one target; returns true when it ended SUCCEEDED. {@code release} is null for a server-backup restore. */
    private boolean runTarget(long runId, DeploymentTarget target, Release release, TargetJob.Payload payload, boolean rollback) {
        long targetId = target.getId();
        try {
            TomcatInstance instance = target.getInstanceId() == null ? null : instances.findById(target.getInstanceId()).orElse(null);
            String problem = revalidate(instance, target, release, rollback);
            if (problem != null) {
                recorder.targetState(runId, targetId, TargetState.PREFLIGHT_FAILED, problem);
                return false;
            }
            Release previous = target.getPreviousReleaseId() == null ? null : releases.findById(target.getPreviousReleaseId()).orElse(null);
            String method = instance.getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP ? "SSH/SFTP" : "Tomcat Manager";
            recorder.info(runId, targetId, "TARGET_STARTED", (rollback ? "Restoring " + payload.label() : "Deploying " + payload.label()) + " to " + target.getInstanceName()
                    + " as " + target.getTargetFilename() + " (" + target.getContextPath() + ") via " + method + ".");
            deployers.execute(new TargetJob(runId, targetId, instance, payload, target.getTargetFilename(), target.getContextPath(), previous, rollback, () -> cancelled(runId)));
        } catch (RuntimeException e) {
            log.error("Target {} aborted unexpectedly", targetId, e);
            TargetState current = recorder.target(targetId).getState();
            if (!current.isTerminal() && current != TargetState.UNKNOWN && current != TargetState.ROLLBACK_REQUIRED)
                recorder.targetState(runId, targetId, current.remoteMayHaveChanged() ? TargetState.UNKNOWN : TargetState.FAILED,
                        "Unexpected error: " + DiagnosticSanitizer.describe(e));
        }
        return recorder.target(targetId).getState() == TargetState.SUCCEEDED;
    }

    /** Critical preflight conditions re-checked immediately before touching a target. */
    private String revalidate(TomcatInstance instance, DeploymentTarget target, Release release, boolean rollback) {
        String what = rollback ? "rollback" : "deployment";
        if (instance == null) return "The Tomcat instance was deleted after the " + what + " was requested.";
        if (!instance.isEnabled()) return "The Tomcat instance was disabled after the " + what + " was requested.";
        if (!instance.getHost().isEnabled()) return "The Linux host was disabled after the " + what + " was requested.";
        if (instance.getDeploymentMethod() != target.getDeploymentMethod()) return "The instance's deployment method changed after the " + what + " was requested.";
        if (release == null) return null; // server backup: it ran on this instance before
        Compatibility.Result compatibility = Compatibility.evaluate(Compatibility.requirements(release.getApplication(), release.getArtifact()),
                instance.getTomcatVersion(), instance.getJavaVersion());
        if (compatibility.status() == Compatibility.Status.INCOMPATIBLE) return "Incompatible: " + String.join(" ", compatibility.reasons());
        return null;
    }

    private void skip(long runId, DeploymentTarget target, boolean afterFailure) {
        recorder.targetState(runId, target.getId(), TargetState.SKIPPED, afterFailure
                ? "Not started because an earlier target failed. Nothing was changed on this server."
                : "Not started because the deployment was cancelled. Nothing was changed on this server.");
    }

    private void skipAll(long runId, List<DeploymentTarget> list, String reason) {
        for (DeploymentTarget t : list) if (recorder.target(t.getId()).getState() == TargetState.PENDING)
            recorder.targetState(runId, t.getId(), TargetState.SKIPPED, reason);
    }

    private boolean cancelled(long runId) { return recorder.run(runId).isCancelRequested(); }

    /** Derives the final deployment state from the persisted target states. Never reports success unless every target succeeded. */
    void finish(long runId, String reason) {
        List<DeploymentTarget> list = targets.findByRunIdOrderBySequenceAsc(runId);
        long succeeded = list.stream().filter(t -> t.getState() == TargetState.SUCCEEDED).count();
        long unknown = list.stream().filter(t -> t.getState() == TargetState.UNKNOWN).count();
        long skipped = list.stream().filter(t -> t.getState() == TargetState.SKIPPED).count();
        long rollback = list.stream().filter(t -> t.getState() == TargetState.ROLLBACK_REQUIRED).count();
        DeploymentRun run = recorder.run(runId);
        DeploymentState next;
        if (unknown > 0) next = DeploymentState.UNKNOWN;
        else if (succeeded == list.size()) next = DeploymentState.SUCCEEDED;
        else if (run.isCancelRequested() && skipped > 0) next = DeploymentState.CANCELLED;
        else if (succeeded > 0) next = DeploymentState.PARTIALLY_SUCCEEDED;
        else next = DeploymentState.FAILED;
        if (next == DeploymentState.SUCCEEDED && !run.getState().canTransitionTo(DeploymentState.SUCCEEDED)) next = DeploymentState.PARTIALLY_SUCCEEDED;
        String summary = reason != null ? reason : succeeded + " of " + list.size() + " target(s) succeeded"
                + (unknown > 0 ? ", " + unknown + " unknown (reconcile)" : "") + (rollback > 0 ? ", " + rollback + " need rollback" : "")
                + (skipped > 0 ? ", " + skipped + " not started" : "") + "." + failureReason(list);
        recorder.runState(runId, next, summary);
        try { outcomes.finished(recorder.run(runId)); }
        catch (RuntimeException e) { log.error("Could not record the outcome notification of run {}", runId, e); }
    }

    /** Why the first target failed (or needs a rollback), so the summary explains the outcome; empty when none did. */
    static String failureReason(List<DeploymentTarget> list) {
        List<DeploymentTarget> failed = list.stream().filter(t -> (t.getState() == TargetState.FAILED || t.getState() == TargetState.ROLLBACK_REQUIRED)
                && t.getMessage() != null && !t.getMessage().isBlank()).toList();
        if (failed.isEmpty()) return "";
        String message = failed.get(0).getMessage().strip();
        return " " + failed.get(0).getInstanceName() + ": " + message + (message.endsWith(".") ? "" : ".")
                + (failed.size() > 1 ? " " + (failed.size() - 1) + " more target(s) failed; see below." : "");
    }

    private void abort(long runId, String reason) {
        try {
            for (DeploymentTarget t : targets.findByRunIdOrderBySequenceAsc(runId)) {
                TargetState s = recorder.target(t.getId()).getState();
                if (s == TargetState.PENDING) recorder.targetState(runId, t.getId(), TargetState.SKIPPED, "Not started: the deployment was aborted.");
                else if (!s.isTerminal() && s != TargetState.UNKNOWN && s != TargetState.ROLLBACK_REQUIRED)
                    recorder.targetState(runId, t.getId(), s.remoteMayHaveChanged() ? TargetState.UNKNOWN : TargetState.FAILED, "Aborted: " + reason);
            }
            finish(runId, "Aborted: " + reason);
        } catch (RuntimeException e) {
            log.error("Could not record abort of deployment {}", runId, e);
        }
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return r -> { Thread t = new Thread(r, prefix + "-" + n.incrementAndGet()); t.setDaemon(false); return t; };
    }

    @Override public void destroy() { runWorkers.shutdown(); targetWorkers.shutdown(); }
}
