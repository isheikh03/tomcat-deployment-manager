package com.tomcatmanager.health;

import jakarta.persistence.*;
import java.time.Instant;

/** Latest application health of one Tomcat instance, updated after every check. */
@Entity
@Table(name = "instance_health_status")
public class InstanceHealthStatus {
    public enum Status {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN;

        public String label() {
            return switch (this) { case HEALTHY -> "Healthy"; case DEGRADED -> "Degraded"; case UNHEALTHY -> "Unhealthy"; case UNKNOWN -> "Not checked yet"; };
        }
        public String badgeClass() {
            return switch (this) { case HEALTHY -> "text-bg-success"; case DEGRADED -> "text-bg-warning"; case UNHEALTHY -> "text-bg-danger"; case UNKNOWN -> "text-bg-light border"; };
        }
    }

    @Id @Column(name = "instance_id") private Long instanceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.UNKNOWN;
    @Column(name = "last_checked_at") private Instant lastCheckedAt;
    @Column(name = "last_success_at") private Instant lastSuccessAt;
    @Column(name = "last_failure_at") private Instant lastFailureAt;
    @Column(name = "consecutive_failures", nullable = false) private int consecutiveFailures;
    @Column(name = "last_status_code") private Integer lastStatusCode;
    @Column(name = "last_detail", length = 500) private String lastDetail;
    @Column(name = "last_elapsed_ms") private Long lastElapsedMs;
    @Column(name = "unhealthy_since") private Instant unhealthySince;
    @Column(name = "last_recovered_at") private Instant lastRecoveredAt;
    @Version private long version;

    protected InstanceHealthStatus() { }

    public InstanceHealthStatus(Long instanceId) { this.instanceId = instanceId; }

    /**
     * Applies a check result. Returns true when the application recovered (was degraded or unhealthy, now healthy).
     * {@code unhealthyAfter} consecutive failures turn DEGRADED into UNHEALTHY, so one failed request is not an outage.
     */
    boolean apply(HealthCheckResult result, int unhealthyAfter) {
        boolean recovered = false;
        lastCheckedAt = result.getCheckedAt(); lastStatusCode = result.getStatusCode(); lastDetail = result.getDetail(); lastElapsedMs = result.getElapsedMs();
        if (result.isHealthy()) {
            recovered = status == Status.DEGRADED || status == Status.UNHEALTHY;
            if (recovered) lastRecoveredAt = result.getCheckedAt();
            status = Status.HEALTHY; consecutiveFailures = 0; lastSuccessAt = result.getCheckedAt(); unhealthySince = null;
        } else {
            consecutiveFailures++;
            lastFailureAt = result.getCheckedAt();
            if (unhealthySince == null) unhealthySince = result.getCheckedAt();
            status = consecutiveFailures >= Math.max(1, unhealthyAfter) ? Status.UNHEALTHY : Status.DEGRADED;
        }
        return recovered;
    }

    public Long getInstanceId() { return instanceId; }
    public Status getStatus() { return status; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public Integer getLastStatusCode() { return lastStatusCode; }
    public String getLastDetail() { return lastDetail; }
    public Long getLastElapsedMs() { return lastElapsedMs; }
    public Instant getUnhealthySince() { return unhealthySince; }
    public Instant getLastRecoveredAt() { return lastRecoveredAt; }
}
