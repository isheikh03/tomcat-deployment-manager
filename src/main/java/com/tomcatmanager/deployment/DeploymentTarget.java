package com.tomcatmanager.deployment;

import com.tomcatmanager.infrastructure.TomcatInstance;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * One Tomcat instance within a deployment. Instance details are copied at creation so the record
 * stays accurate even if the instance is later reconfigured or deleted.
 */
@Entity
@Table(name = "deployment_targets")
public class DeploymentTarget {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "run_id", nullable = false, updatable = false) private DeploymentRun run;
    @Column(name = "instance_id") private Long instanceId;
    @Column(name = "instance_name", nullable = false, length = 120, updatable = false) private String instanceName;
    @Column(name = "host_name", nullable = false, length = 120, updatable = false) private String hostName;
    @Column(nullable = false, length = 255, updatable = false) private String hostname;
    @Enumerated(EnumType.STRING) @Column(name = "deployment_method", nullable = false, length = 30, updatable = false) private TomcatInstance.DeploymentMethod deploymentMethod;
    @Column(name = "target_filename", nullable = false, length = 255, updatable = false) private String targetFilename;
    @Column(name = "context_path", nullable = false, length = 255, updatable = false) private String contextPath;
    @Column(name = "previous_release_id", updatable = false) private Long previousReleaseId;
    @Column(nullable = false, updatable = false) private int sequence;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private TargetState state = TargetState.PENDING;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "bytes_transferred") private Long bytesTransferred;
    @Column(length = 1000) private String message;
    @Column(name = "reconciled_by", length = 120) private String reconciledBy;
    @Column(name = "reconciled_at") private Instant reconciledAt;
    @Column(name = "reconcile_note", length = 1000) private String reconcileNote;
    /** The rollback run that successfully undid this target, if any. */
    @Column(name = "rolled_back_by_run_id") private Long rolledBackByRunId;
    @Version private long version;

    protected DeploymentTarget() { }

    public DeploymentTarget(DeploymentRun run, TomcatInstance instance, String targetFilename, String contextPath, Long previousReleaseId, int sequence) {
        this.run = run; this.instanceId = instance.getId(); this.instanceName = instance.getName();
        this.hostName = instance.getHost().getName(); this.hostname = instance.getHost().getHostname();
        this.deploymentMethod = instance.getDeploymentMethod(); this.targetFilename = targetFilename; this.contextPath = contextPath;
        this.previousReleaseId = previousReleaseId; this.sequence = sequence;
    }

    void transitionTo(TargetState next, String message) {
        if (!state.canTransitionTo(next)) throw new IllegalStateException("Target " + id + " cannot change from " + state + " to " + next + ".");
        state = next;
        if (startedAt == null && next != TargetState.SKIPPED && next != TargetState.PREFLIGHT_FAILED) startedAt = Instant.now();
        if (next.isTerminal() || next == TargetState.UNKNOWN || next == TargetState.ROLLBACK_REQUIRED) finishedAt = Instant.now();
        if (message != null) this.message = message;
    }
    void setBytesTransferred(long bytes) { this.bytesTransferred = bytes; }
    /** Records a successful rollback of this target; a target that required rollback is closed as ROLLED_BACK. */
    void markRolledBack(long rollbackRunId) {
        this.rolledBackByRunId = rollbackRunId;
        if (state == TargetState.ROLLBACK_REQUIRED) transitionTo(TargetState.ROLLED_BACK, "Rolled back by rollback #" + rollbackRunId + ".");
    }
    void reconcile(TargetState outcome, String actor, String note) {
        transitionTo(outcome, "Reconciled by " + actor + ": " + note);
        this.reconciledBy = actor; this.reconciledAt = Instant.now(); this.reconcileNote = note;
    }

    public Long getId() { return id; }
    public DeploymentRun getRun() { return run; }
    public Long getInstanceId() { return instanceId; }
    public String getInstanceName() { return instanceName; }
    public String getHostName() { return hostName; }
    public String getHostname() { return hostname; }
    public TomcatInstance.DeploymentMethod getDeploymentMethod() { return deploymentMethod; }
    public String getTargetFilename() { return targetFilename; }
    public String getContextPath() { return contextPath; }
    public Long getPreviousReleaseId() { return previousReleaseId; }
    public int getSequence() { return sequence; }
    public TargetState getState() { return state; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getBytesTransferred() { return bytesTransferred; }
    public String getMessage() { return message; }
    public String getReconciledBy() { return reconciledBy; }
    public Instant getReconciledAt() { return reconciledAt; }
    public String getReconcileNote() { return reconcileNote; }
    public Long getRolledBackByRunId() { return rolledBackByRunId; }
    public long getVersion() { return version; }
}
