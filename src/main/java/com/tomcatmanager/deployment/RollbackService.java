package com.tomcatmanager.deployment;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Manual rollback requests. A rollback is its own durable deployment run (kind ROLLBACK) linked to
 * the target it undoes; the original deployment and its logs are never modified, except that the
 * original target is marked rolled back once the rollback has succeeded.
 */
@Service
public class RollbackService {
    private final RollbackPreflight preflight;
    private final DeploymentEngine engine;
    private final DeploymentRecorder recorder;
    private final DeploymentRunRepository runs;
    private final DeploymentTargetRepository targets;
    private final com.tomcatmanager.audit.AuditService audit;
    private final TransactionTemplate tx;

    public RollbackService(RollbackPreflight preflight, DeploymentEngine engine, DeploymentRecorder recorder, DeploymentRunRepository runs,
                           DeploymentTargetRepository targets, com.tomcatmanager.audit.AuditService audit, TransactionTemplate tx) {
        this.preflight = preflight; this.engine = engine; this.recorder = recorder; this.runs = runs; this.targets = targets; this.audit = audit; this.tx = tx;
    }

    public record Submission(UUID requestId, Long targetId, boolean confirmed, boolean acknowledgeWarnings, String confirmation) { }

    public static class RollbackBlockedException extends RuntimeException {
        private final RollbackPreflight.Preview preview;
        public RollbackBlockedException(String message, RollbackPreflight.Preview preview) { super(message); this.preview = preview; }
        public RollbackPreflight.Preview getPreview() { return preview; }
    }

    public DeploymentRun submit(Submission s, String actor) {
        if (s.requestId() == null) throw new IllegalArgumentException("Missing request identifier; reload the page.");
        if (s.targetId() == null) throw new IllegalArgumentException("Choose the deployment target to roll back.");
        var existing = runs.findByRequestId(s.requestId());
        if (existing.isPresent()) return existing.get();
        if (!s.confirmed()) throw new IllegalArgumentException("Confirm the rollback explicitly.");

        RollbackPreflight.Evaluation evaluation = preflight.evaluateFull(s.targetId(), actor);
        RollbackPreflight.Preview preview = evaluation.preview();
        if (!preview.canRollBack()) {
            audit.failure("ROLLBACK_REQUESTED", "DEPLOYMENT", preview.runId(), "Rollback of " + preview.instanceName() + " (deployment #" + preview.runId()
                    + ") refused: " + String.join(" ", preview.blockers()));
            throw new RollbackBlockedException(preview.blockers().size() + " blocker(s) prevent this rollback. Review the checks.", preview);
        }
        if (preview.requiresAcknowledgement() && !s.acknowledgeWarnings())
            throw new IllegalArgumentException("Review and acknowledge the " + preview.warnings().size() + " warning(s) before rolling back.");
        if (preview.production() && !preview.confirmationText().equals(s.confirmation() == null ? null : s.confirmation().trim()))
            throw new IllegalArgumentException("Type the instance name " + preview.confirmationText() + " to confirm this Production rollback.");

        DeploymentTarget original = evaluation.original();
        RestorePoints.RestorePoint point = evaluation.restorePoint();
        DeploymentRun run;
        try {
            run = tx.execute(status -> {
                DeploymentRun r = runs.save(DeploymentRun.rollback(s.requestId(), original, point.release(),
                        point.backup() == null ? null : point.backup().getId(), point.description(), actor));
                Long replaced = original.getRun().getRelease() == null ? null : original.getRun().getRelease().getId();
                targets.save(new DeploymentTarget(r, evaluation.instance(), original.getTargetFilename(), original.getContextPath(), replaced, 1));
                targets.flush();
                return r;
            });
        } catch (DataIntegrityViolationException e) {
            var again = runs.findByRequestId(s.requestId());
            if (again.isPresent()) return again.get();
            throw new IllegalStateException("Another deployment to this instance started a moment ago. Review the checks again.");
        }
        recorder.info(run.getId(), null, "ROLLBACK_REQUESTED", actor + " requested a rollback of deployment #" + original.getRun().getId()
                + " on " + original.getInstanceName() + ": restore " + point.description() + " as " + original.getTargetFilename()
                + (preview.requiresAcknowledgement() ? " (" + preview.warnings().size() + " warning(s) acknowledged)" : "") + ".");
        recorder.warn(original.getRun().getId(), original.getId(), "ROLLBACK_REQUESTED", actor + " started rollback #" + run.getId() + " of this target.");
        audit.success("ROLLBACK_REQUESTED", "ROLLBACK", run.getId(), "Confirmed rollback of deployment #" + original.getRun().getId() + " on "
                + original.getInstanceName() + ": restore " + point.description() + " as " + original.getTargetFilename()
                + (preview.production() ? " (Production confirmation typed)" : "") + ".");
        engine.submit(run.getId());
        return run;
    }
}
