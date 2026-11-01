package com.tomcatmanager.deployment;

import com.tomcatmanager.audit.AuditEvent;
import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.notifications.Notification;
import com.tomcatmanager.notifications.NotificationService;
import org.springframework.stereotype.Component;

/** Audit record and user notification when a deployment or rollback reaches its final (or unknown) state. */
@Component
public class DeploymentOutcomeNotifier {
    private final AuditService audit;
    private final NotificationService notifications;

    public DeploymentOutcomeNotifier(AuditService audit, NotificationService notifications) {
        this.audit = audit; this.notifications = notifications;
    }

    void finished(DeploymentRun run) {
        boolean rollback = run.isRollback();
        String what = (rollback ? "Rollback #" : "Deployment #") + run.getId() + " of " + run.getApplication().getName() + " " + run.getVersionLabel()
                + " (" + run.getEnvironment().label() + ")";
        String summary = run.getSummary() == null ? "" : " " + run.getSummary();
        AuditEvent.Outcome outcome = run.getState() == DeploymentState.SUCCEEDED ? AuditEvent.Outcome.SUCCESS : AuditEvent.Outcome.FAILURE;
        audit.record(AuditService.SYSTEM, rollback ? "ROLLBACK_COMPLETED" : "DEPLOYMENT_COMPLETED", rollback ? "ROLLBACK" : "DEPLOYMENT", run.getId(), outcome,
                what + " ended " + run.getState().label().toLowerCase() + "." + summary + " Requested by " + run.getRequestedBy() + ".");

        Notification.Level level = switch (run.getState()) {
            case SUCCEEDED -> Notification.Level.SUCCESS;
            case PARTIALLY_SUCCEEDED, UNKNOWN, CANCELLED -> Notification.Level.WARNING;
            default -> Notification.Level.DANGER;
        };
        String title = what + ": " + run.getState().label();
        notifications.publish(level, rollback ? Notification.Category.ROLLBACK : Notification.Category.DEPLOYMENT, title,
                (run.getSummary() == null ? run.getState().label() + "." : run.getSummary())
                        + (run.getState() == DeploymentState.UNKNOWN ? " An administrator must inspect and reconcile the affected targets." : ""),
                "/deployments/" + run.getId());
    }
}
