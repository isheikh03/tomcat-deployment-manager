package com.tomcatmanager.deployment;

import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.infrastructure.Environment;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.Release;
import com.tomcatmanager.release.ReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Deployment requests, cancellation, reconciliation and restart recovery. A request is accepted only
 * after the full preflight runs again on the server; the browser preview is never trusted.
 */
@Service
public class DeploymentService {
    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);

    private final DeploymentPreflight preflight;
    private final DeploymentEngine engine;
    private final DeploymentRecorder recorder;
    private final DeploymentRunRepository runs;
    private final DeploymentTargetRepository targets;
    private final ReleaseRepository releases;
    private final TomcatInstanceRepository instances;
    private final RestorePoints restorePoints;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public DeploymentService(DeploymentPreflight preflight, DeploymentEngine engine, DeploymentRecorder recorder, DeploymentRunRepository runs,
                             DeploymentTargetRepository targets, ReleaseRepository releases, TomcatInstanceRepository instances,
                             RestorePoints restorePoints, AuditService audit, TransactionTemplate tx) {
        this.preflight = preflight; this.engine = engine; this.recorder = recorder; this.runs = runs; this.targets = targets;
        this.releases = releases; this.instances = instances; this.restorePoints = restorePoints; this.audit = audit; this.tx = tx;
    }

    public record Submission(UUID requestId, DeploymentPreflight.Request request, boolean acknowledgeWarnings, String confirmation) { }

    /** Thrown when preflight blockers prevent the deployment; carries the evaluated preview. */
    public static class PreflightBlockedException extends RuntimeException {
        private final DeploymentPreflight.Preview preview;
        public PreflightBlockedException(String message, DeploymentPreflight.Preview preview) { super(message); this.preview = preview; }
        public DeploymentPreflight.Preview getPreview() { return preview; }
    }

    public DeploymentRun submit(Submission submission, String actor) {
        if (submission.requestId() == null) throw new IllegalArgumentException("Missing request identifier; reload the wizard.");
        // Idempotency: a repeated submission of the same wizard returns the deployment already created.
        var existing = runs.findByRequestId(submission.requestId());
        if (existing.isPresent()) return existing.get();

        DeploymentPreflight.Preview preview = preflight.evaluate(submission.request(), actor);
        String described = preview.applicationName() + " " + preview.versionLabel() + " to " + preview.rows().size() + " target(s) in " + preview.environment().label();
        if (!preview.canDeploy()) {
            audit.failure("DEPLOYMENT_REQUESTED", "RELEASE", preview.releaseId(), "Deployment of " + described + " refused: " + preview.blockerCount() + " preflight blocker(s).");
            throw new PreflightBlockedException(preview.blockerCount() + " blocker(s) prevent this deployment. Review the preview.", preview);
        }
        if (preview.requiresAcknowledgement() && !submission.acknowledgeWarnings())
            throw new IllegalArgumentException("Review and acknowledge the " + preview.warningCount() + " warning(s) before deploying.");
        if (preview.production() && !preview.confirmationText().equals(submission.confirmation() == null ? null : submission.confirmation().trim())) {
            audit.failure("DEPLOYMENT_REQUESTED", "RELEASE", preview.releaseId(), "Production deployment of " + described + " refused: confirmation text did not match.");
            throw new IllegalArgumentException("Type the version " + preview.confirmationText() + " to confirm this Production deployment.");
        }

        Release release = releases.findById(preview.releaseId()).orElseThrow();
        DeploymentRun run;
        try {
            run = tx.execute(status -> {
                DeploymentRun r = runs.save(new DeploymentRun(submission.requestId(), release, preview.environment(), preview.strategy(),
                        preview.maxParallel(), submission.request().serverGroupId(), preview.serverGroupName(), preview.requiresAcknowledgement(), actor));
                int sequence = 0;
                for (DeploymentPreflight.Row row : preview.rows()) {
                    TomcatInstance instance = instances.findById(row.instanceId()).orElseThrow();
                    targets.save(new DeploymentTarget(r, instance, row.filename(), row.contextPath(), row.currentReleaseId(), ++sequence));
                }
                targets.flush();
                return r;
            });
        } catch (DataIntegrityViolationException e) {
            var again = runs.findByRequestId(submission.requestId());
            if (again.isPresent()) return again.get();
            throw new IllegalStateException("Another deployment to one of these instances started a moment ago. Refresh the preview.");
        }
        recorder.info(run.getId(), null, "DEPLOYMENT_REQUESTED", actor + " requested " + release.getApplication().getName() + " " + release.getVersionLabel()
                + " to " + preview.rows().size() + " target(s) in " + preview.environment().label() + " (" + preview.strategy().name().toLowerCase()
                + (preview.requiresAcknowledgement() ? "; " + preview.warningCount() + " warning(s) acknowledged" : "") + ").");
        audit.success("DEPLOYMENT_REQUESTED", "DEPLOYMENT", run.getId(), "Confirmed deployment of " + described + " (" + preview.strategy().name().toLowerCase()
                + (preview.requiresAcknowledgement() ? "; " + preview.warningCount() + " warning(s) acknowledged" : "")
                + (preview.production() ? "; Production confirmation typed" : "") + "): "
                + preview.rows().stream().map(r -> r.instanceName() + " as " + r.filename()).collect(java.util.stream.Collectors.joining(", ")) + ".");
        engine.submit(run.getId());
        return run;
    }

    public void cancel(long runId, String actor) {
        recorder.retrying(() -> tx.executeWithoutResult(status -> {
            DeploymentRun run = recorder.run(runId);
            if (!run.getState().isActive()) throw new IllegalStateException("This deployment is no longer running.");
            if (run.isCancelRequested()) return;
            run.requestCancel(actor);
            if (run.getState().canTransitionTo(DeploymentState.CANCEL_REQUESTED)) run.transitionTo(DeploymentState.CANCEL_REQUESTED);
            runs.save(run);
        }));
        recorder.warn(runId, null, "CANCEL_REQUESTED", actor + " requested cancellation. Targets already in progress finish safely; no new target is started.");
        audit.success("DEPLOYMENT_CANCELLED", "DEPLOYMENT", runId, actor + " requested cancellation of deployment #" + runId + ".");
    }

    /** Administrator decision for a target whose outcome was unknown, after inspecting the server. */
    public void reconcile(long runId, long targetId, boolean succeeded, String note, String actor) {
        reconcile(runId, targetId, succeeded ? TargetState.SUCCEEDED : TargetState.FAILED, note, actor);
    }

    /**
     * Outcomes: SUCCEEDED (new version in place and healthy), FAILED (unchanged or unusable), or
     * ROLLBACK_REQUIRED (new version in place but bad, and a restore point exists; the target can then be rolled back).
     */
    public void reconcile(long runId, long targetId, TargetState outcome, String note, String actor) {
        if (note == null || note.strip().length() < 5) throw new IllegalArgumentException("Describe what you verified on the server (at least 5 characters).");
        if (outcome != TargetState.SUCCEEDED && outcome != TargetState.FAILED && outcome != TargetState.ROLLBACK_REQUIRED)
            throw new IllegalArgumentException("Reconcile a target as succeeded, failed or rollback required.");
        DeploymentTarget target = recorder.target(targetId);
        if (!target.getRun().getId().equals(runId)) throw new NoSuchElementException("Target not found in this deployment.");
        if (target.getState() != TargetState.UNKNOWN) throw new IllegalStateException("Only targets with an unknown outcome can be reconciled.");
        if (outcome == TargetState.ROLLBACK_REQUIRED) {
            DeploymentTarget withRun = targets.findWithRun(targetId).orElseThrow();
            if (withRun.getRun().isRollback()) throw new IllegalArgumentException("A rollback cannot itself require a rollback; reconcile it as succeeded or failed.");
            if (restorePoints.resolve(withRun).isEmpty()) throw new IllegalArgumentException("There is no restore point for this target, so it cannot be marked as rollback required.");
        }
        tx.executeWithoutResult(status -> {
            DeploymentTarget t = recorder.target(targetId);
            t.reconcile(outcome, actor, note.strip());
            targets.save(t);
        });
        recorder.warn(runId, targetId, "TARGET_RECONCILED", actor + " reconciled the target as " + outcome.name() + ": " + note.strip());
        audit.success("TARGET_RECONCILED", "DEPLOYMENT", runId, actor + " reconciled target " + target.getInstanceName() + " of deployment #" + runId
                + " as " + outcome.name() + ": " + note.strip());
        boolean anyUnknown = targets.findByRunIdOrderBySequenceAsc(runId).stream().anyMatch(t -> t.getState() == TargetState.UNKNOWN);
        if (!anyUnknown && recorder.run(runId).getState() == DeploymentState.UNKNOWN) {
            engine.finish(runId, null);
            engine.afterRollback(runId); // a reconciled rollback links back to the target it undid
        }
    }

    /**
     * Restart recovery: queued deployments that never started are resumed; deployments interrupted
     * mid-way are closed truthfully (unchanged targets FAILED, possibly changed targets UNKNOWN).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        List<DeploymentRun> interrupted = runs.findByStateIn(EnumSet.of(DeploymentState.PENDING, DeploymentState.VALIDATING,
                DeploymentState.RUNNING, DeploymentState.CANCEL_REQUESTED));
        for (DeploymentRun run : interrupted) {
            try {
                List<DeploymentTarget> list = targets.findByRunIdOrderBySequenceAsc(run.getId());
                boolean untouched = run.getState() == DeploymentState.PENDING && list.stream().allMatch(t -> t.getState() == TargetState.PENDING);
                if (untouched && !run.isCancelRequested()) {
                    recorder.warn(run.getId(), null, "RESUMED_AFTER_RESTART", "The application restarted before this deployment started; it was queued again.");
                    engine.submit(run.getId());
                    continue;
                }
                recorder.warn(run.getId(), null, "INTERRUPTED_BY_RESTART", "The application restarted while this deployment was running.");
                for (DeploymentTarget t : list) {
                    TargetState s = t.getState();
                    if (s == TargetState.PENDING) recorder.targetState(run.getId(), t.getId(), TargetState.SKIPPED, "Not started: interrupted by an application restart.");
                    else if (s.remoteMayHaveChanged()) recorder.targetState(run.getId(), t.getId(), TargetState.UNKNOWN,
                            "Interrupted by an application restart while " + s.label().toLowerCase() + ". The server may have changed; inspect it and reconcile.");
                    else if (s.isInFlight()) recorder.targetState(run.getId(), t.getId(), TargetState.FAILED,
                            "Interrupted by an application restart during " + s.label().toLowerCase() + ". The deployed application was not changed (a temporary upload file may remain).");
                }
                engine.finish(run.getId(), null);
            } catch (RuntimeException e) {
                log.error("Could not recover deployment {}", run.getId(), e);
            }
        }
        if (!interrupted.isEmpty()) log.warn("Recovered {} deployment(s) interrupted by a restart", interrupted.size());
    }

    public DeploymentRun find(long id) { return recorder.run(id); }

    /**
     * A run is visible to administrators of its application's team and to users who can see one of its target instances.
     * (Super users see every run.)
     */
    public boolean visible(DeploymentRun run, com.tomcatmanager.access.AccessScope s) {
        if (s.isEverything() || s.canManageTeam(run.getApplication().getTeamId())) return true;
        return targets.findByRunIdOrderBySequenceAsc(run.getId()).stream().anyMatch(t -> t.getInstanceId() != null && s.canViewInstance(t.getInstanceId()));
    }

    /** The run, if visible to the user (otherwise: not found). */
    public DeploymentRun findVisible(long id, com.tomcatmanager.access.AccessScope s) {
        DeploymentRun run = find(id);
        if (!visible(run, s)) throw new java.util.NoSuchElementException("Deployment " + id + " not found.");
        return run;
    }

    /** Cancelling a run needs deploy access to each of its target instances (or administering its team). */
    public void requireCanOperate(DeploymentRun run, com.tomcatmanager.access.AccessScope s) {
        if (s.isEverything() || s.canManageTeam(run.getApplication().getTeamId())) return;
        boolean all = targets.findByRunIdOrderBySequenceAsc(run.getId()).stream()
                .allMatch(t -> t.getInstanceId() != null && s.canDeployTo(t.getInstanceId()));
        if (!all) throw new com.tomcatmanager.access.NotPermittedException("You need deploy access to every target of this deployment.");
    }

    /** History of the runs the user can see. */
    public Page<DeploymentRun> search(Long applicationId, Environment environment, DeploymentState state, int page, int size,
                                      com.tomcatmanager.access.AccessScope s) {
        java.util.Set<Long> instanceIds = s.visibleInstanceIds();
        java.util.Set<Long> managed = s.managedTeams();
        return runs.search(applicationId == null ? 0 : applicationId, environment, state, s.isEverything(),
                managed.isEmpty() ? java.util.List.of(-1L) : managed,
                instanceIds == null || instanceIds.isEmpty() ? java.util.List.of(-1L) : instanceIds,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
    }
}
