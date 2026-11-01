package com.tomcatmanager.deployment;

import jakarta.persistence.*;
import java.time.Instant;

/** A persisted, sanitized deployment event. Never contains credentials. */
@Entity
@Table(name = "deployment_logs")
public class DeploymentLog {
    public enum Level { INFO, WARN, ERROR }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "run_id", nullable = false, updatable = false) private Long runId;
    @Column(name = "target_id", updatable = false) private Long targetId;
    @Column(name = "logged_at", nullable = false, updatable = false) private Instant loggedAt = Instant.now();
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10, updatable = false) private Level level;
    @Column(nullable = false, length = 60, updatable = false) private String event;
    @Column(nullable = false, length = 2000, updatable = false) private String message;
    @Column(name = "duration_ms", updatable = false) private Long durationMs;

    protected DeploymentLog() { }

    public DeploymentLog(Long runId, Long targetId, Level level, String event, String message, Long durationMs) {
        this.runId = runId; this.targetId = targetId; this.level = level; this.event = event; this.message = message; this.durationMs = durationMs;
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public Long getTargetId() { return targetId; }
    public Instant getLoggedAt() { return loggedAt; }
    public Level getLevel() { return level; }
    public String getEvent() { return event; }
    public String getMessage() { return message; }
    public Long getDurationMs() { return durationMs; }
}
