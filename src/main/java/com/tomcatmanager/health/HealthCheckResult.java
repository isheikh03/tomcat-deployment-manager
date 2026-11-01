package com.tomcatmanager.health;

import jakarta.persistence.*;
import java.time.Instant;

/** One application health check (scheduled or manual). Details are sanitized before storage. */
@Entity
@Table(name = "health_check_results")
public class HealthCheckResult {
    public enum Trigger { SCHEDULED, MANUAL }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "instance_id", nullable = false, updatable = false) private Long instanceId;
    @Column(name = "context_path", length = 255, updatable = false) private String contextPath;
    @Column(nullable = false, length = 500, updatable = false) private String url;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private Trigger trigger;
    @Column(name = "checked_at", nullable = false, updatable = false) private Instant checkedAt = Instant.now();
    @Column(nullable = false, updatable = false) private boolean healthy;
    @Column(name = "status_code", updatable = false) private Integer statusCode;
    @Column(name = "elapsed_ms", nullable = false, updatable = false) private long elapsedMs;
    @Column(nullable = false, length = 500, updatable = false) private String detail;
    @Column(name = "checked_by", length = 120, updatable = false) private String checkedBy;

    protected HealthCheckResult() { }

    public HealthCheckResult(Long instanceId, String contextPath, String url, Trigger trigger, boolean healthy, Integer statusCode,
                             long elapsedMs, String detail, String checkedBy) {
        this.instanceId = instanceId; this.contextPath = contextPath; this.url = url; this.trigger = trigger; this.healthy = healthy;
        this.statusCode = statusCode; this.elapsedMs = elapsedMs; this.detail = detail; this.checkedBy = checkedBy;
    }

    public Long getId() { return id; }
    public Long getInstanceId() { return instanceId; }
    public String getContextPath() { return contextPath; }
    public String getUrl() { return url; }
    public Trigger getTrigger() { return trigger; }
    public Instant getCheckedAt() { return checkedAt; }
    public boolean isHealthy() { return healthy; }
    public Integer getStatusCode() { return statusCode; }
    public long getElapsedMs() { return elapsedMs; }
    public String getDetail() { return detail; }
    public String getCheckedBy() { return checkedBy; }
}
