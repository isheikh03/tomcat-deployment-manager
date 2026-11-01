package com.tomcatmanager.infrastructure;

import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.notifications.Notification;
import com.tomcatmanager.notifications.NotificationService;
import org.springframework.stereotype.Component;

/**
 * Audit records and notifications for infrastructure changes. Only names, IDs and the fact that a
 * credential was replaced or removed are recorded, never the credential itself.
 */
@Component
public class InfrastructureAudit {
    private final AuditService audit;
    private final NotificationService notifications;

    public InfrastructureAudit(AuditService audit, NotificationService notifications) {
        this.audit = audit; this.notifications = notifications;
    }

    void hostSaved(HostService.HostChange change) {
        ManagedHost h = change.host();
        String target = "host " + h.getName() + " (" + h.getHostname() + ":" + h.getSshPort() + ")";
        audit.success(change.created() ? "HOST_CREATED" : "HOST_UPDATED", "HOST", h.getId(),
                (change.created() ? "Created " : "Updated ") + target + (h.isEnabled() ? "" : ", disabled") + ".");
        if (change.hostKeyChanged())
            audit.success("HOST_KEY_CHANGED", "HOST", h.getId(), "Pinned SSH host key of " + target + " changed to "
                    + (h.getSshHostKeyFingerprint() == null ? "none" : h.getSshHostKeyFingerprint()) + ".");
        if (change.credentials() != null)
            audit.success("CREDENTIALS_CHANGED", "HOST", h.getId(), "SSH credentials of " + target + " " + change.credentials() + ".");
        if (change.connectionChanged())
            notifications.publish(Notification.Level.INFO, Notification.Category.CONFIGURATION, "Connection settings of " + h.getName() + " changed",
                    AuditService.currentActor() + " changed the connection configuration of " + target
                            + (change.hostKeyChanged() ? " including the pinned host key" : "") + (change.credentials() != null ? " and its SSH credentials" : "")
                            + ". Run a connection test before the next deployment.", "/infrastructure/hosts/" + h.getId());
    }

    void hostDeleted(long id, String name) { audit.success("HOST_DELETED", "HOST", id, "Deleted host " + name + "."); }

    void instanceSaved(InstanceService.InstanceChange change) {
        TomcatInstance i = change.instance();
        String target = "Tomcat instance " + i.getName() + " on " + i.getHost().getName();
        audit.success(change.created() ? "INSTANCE_CREATED" : "INSTANCE_UPDATED", "INSTANCE", i.getId(),
                (change.created() ? "Created " : "Updated ") + target + " (" + i.getDeploymentMethod() + ", "
                        + (i.getEnvironment() == null ? "no environment" : i.getEnvironment().label()) + ", file " + i.getDeploymentFilename() + ").");
        if (change.credentials() != null)
            audit.success("CREDENTIALS_CHANGED", "INSTANCE", i.getId(), change.credentials() + " for " + target + ".");
        if (change.connectionChanged())
            notifications.publish(Notification.Level.INFO, Notification.Category.CONFIGURATION, "Configuration of " + i.getName() + " changed",
                    AuditService.currentActor() + " changed the connection or deployment configuration of " + target
                            + ". Run a connection test before the next deployment.", "/infrastructure/instances/" + i.getId());
    }

    void instanceDeleted(long id, String name) { audit.success("INSTANCE_DELETED", "INSTANCE", id, "Deleted Tomcat instance " + name + "."); }

    void groupSaved(ServerGroup g, boolean created) {
        audit.success(created ? "GROUP_CREATED" : "GROUP_UPDATED", "GROUP", g.getId(), (created ? "Created" : "Updated") + " server group " + g.getName() + ".");
    }

    void groupDeleted(long id, String name) { audit.success("GROUP_DELETED", "GROUP", id, "Deleted server group " + name + "."); }

    void membershipChanged(ServerGroup g, TomcatInstance i, boolean added) {
        audit.success(added ? "GROUP_MEMBER_ADDED" : "GROUP_MEMBER_REMOVED", "GROUP", g.getId(),
                (added ? "Added " : "Removed ") + i.getName() + (added ? " to" : " from") + " server group " + g.getName() + ".");
    }
}
