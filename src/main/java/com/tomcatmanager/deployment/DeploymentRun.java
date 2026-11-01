package com.tomcatmanager.deployment;

import com.tomcatmanager.application.ManagedApplication;
import com.tomcatmanager.infrastructure.Environment;
import com.tomcatmanager.release.Release;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One deployment of a release to a resolved, frozen set of targets, or a rollback (one target)
 * restoring a previous version. For a rollback, {@code release} is the restored release; it is
 * null when the restored server backup matches no release in the library.
 */
@Entity
@Table(name = "deployment_runs")
public class DeploymentRun {
    public enum Strategy { SEQUENTIAL, PARALLEL }
    public enum Kind { DEPLOYMENT, ROLLBACK }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "request_id", nullable = false, unique = true, updatable = false) private UUID requestId;
    @Column(name = "correlation_id", nullable = false, unique = true, updatable = false) private UUID correlationId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private Kind kind = Kind.DEPLOYMENT;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "application_id", nullable = false, updatable = false) private ManagedApplication application;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "release_id", updatable = false) private Release release;
    @Column(name = "rollback_of_target_id", updatable = false) private Long rollbackOfTargetId;
    @Column(name = "rollback_backup_id", updatable = false) private Long rollbackBackupId;
    @Column(name = "restore_description", length = 300, updatable = false) private String restoreDescription;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private Environment environment;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private Strategy strategy;
    @Column(name = "max_parallel", nullable = false, updatable = false) private int maxParallel = 1;
    @Column(name = "server_group_id", updatable = false) private Long serverGroupId;
    @Column(name = "server_group_name", length = 100, updatable = false) private String serverGroupName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private DeploymentState state = DeploymentState.PENDING;
    @Column(name = "warnings_acknowledged", nullable = false, updatable = false) private boolean warningsAcknowledged;
    @Column(name = "requested_by", nullable = false, length = 120, updatable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt = Instant.now();
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "cancel_requested_by", length = 120) private String cancelRequestedBy;
    @Column(name = "cancel_requested_at") private Instant cancelRequestedAt;
    @Column(length = 1000) private String summary;
    @Version private long version;

    protected DeploymentRun() { }

    public DeploymentRun(UUID requestId, Release release, Environment environment, Strategy strategy, int maxParallel,
                         Long serverGroupId, String serverGroupName, boolean warningsAcknowledged, String requestedBy) {
        this.requestId = requestId; this.correlationId = UUID.randomUUID(); this.release = release; this.application = release.getApplication();
        this.environment = environment; this.strategy = strategy; this.maxParallel = strategy == Strategy.SEQUENTIAL ? 1 : maxParallel;
        this.serverGroupId = serverGroupId; this.serverGroupName = serverGroupName;
        this.warningsAcknowledged = warningsAcknowledged; this.requestedBy = requestedBy;
    }

    /** A rollback of {@code original}; {@code restored} may be null (server backup not in the library). */
    static DeploymentRun rollback(UUID requestId, DeploymentTarget original, Release restored, Long backupId, String restoreDescription, String requestedBy) {
        DeploymentRun source = original.getRun();
        DeploymentRun r = new DeploymentRun();
        r.requestId = requestId; r.correlationId = UUID.randomUUID(); r.kind = Kind.ROLLBACK;
        r.application = source.getApplication(); r.release = restored; r.environment = source.getEnvironment();
        r.strategy = Strategy.SEQUENTIAL; r.maxParallel = 1; r.warningsAcknowledged = true; r.requestedBy = requestedBy;
        r.rollbackOfTargetId = original.getId(); r.rollbackBackupId = backupId; r.restoreDescription = restoreDescription;
        return r;
    }

    void transitionTo(DeploymentState next) {
        if (state == next) return;
        if (!state.canTransitionTo(next)) throw new IllegalStateException("Deployment " + id + " cannot change from " + state + " to " + next + ".");
        state = next;
        if (next == DeploymentState.VALIDATING && startedAt == null) startedAt = Instant.now();
        if (next.isTerminal() || next == DeploymentState.UNKNOWN) finishedAt = Instant.now();
    }
    void requestCancel(String actor) { cancelRequestedBy = actor; cancelRequestedAt = Instant.now(); }
    void setSummary(String summary) { this.summary = summary; }

    public Long getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public UUID getCorrelationId() { return correlationId; }
    public Kind getKind() { return kind; }
    public boolean isRollback() { return kind == Kind.ROLLBACK; }
    public Long getRollbackOfTargetId() { return rollbackOfTargetId; }
    public Long getRollbackBackupId() { return rollbackBackupId; }
    public String getRestoreDescription() { return restoreDescription; }
    /** What this run installs, for display: the release version, or the restored server backup. */
    public String getVersionLabel() { return release != null ? release.getVersionLabel() : "server backup"; }
    public ManagedApplication getApplication() { return application; }
    public Release getRelease() { return release; }
    public Environment getEnvironment() { return environment; }
    public Strategy getStrategy() { return strategy; }
    public int getMaxParallel() { return maxParallel; }
    public Long getServerGroupId() { return serverGroupId; }
    public String getServerGroupName() { return serverGroupName; }
    public DeploymentState getState() { return state; }
    public boolean isWarningsAcknowledged() { return warningsAcknowledged; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getCancelRequestedBy() { return cancelRequestedBy; }
    public Instant getCancelRequestedAt() { return cancelRequestedAt; }
    public boolean isCancelRequested() { return cancelRequestedAt != null; }
    public String getSummary() { return summary; }
    public long getVersion() { return version; }
}
