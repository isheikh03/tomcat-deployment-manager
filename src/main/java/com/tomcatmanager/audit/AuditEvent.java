package com.tomcatmanager.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** One durable, append-only audit record. Never contains secret values. */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {
    public enum Outcome { SUCCESS, FAILURE, DENIED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt = Instant.now();
    @Column(nullable = false, length = 120) private String actor;
    @Column(nullable = false, length = 60) private String action;
    @Column(name = "resource_type", length = 40) private String resourceType;
    @Column(name = "resource_id", length = 60) private String resourceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Outcome outcome;
    @Column(name = "source_ip", length = 64) private String sourceIp;
    @Column(name = "user_agent", length = 200) private String userAgent;
    @Column(name = "correlation_id", length = 64) private String correlationId;
    @Column(nullable = false, length = 1000) private String summary;

    protected AuditEvent() { }

    AuditEvent(String actor, String action, String resourceType, String resourceId, Outcome outcome,
               String sourceIp, String userAgent, String correlationId, String summary) {
        this.actor = actor; this.action = action; this.resourceType = resourceType; this.resourceId = resourceId; this.outcome = outcome;
        this.sourceIp = sourceIp; this.userAgent = userAgent; this.correlationId = correlationId; this.summary = summary;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public Outcome getOutcome() { return outcome; }
    public String getSourceIp() { return sourceIp; }
    public String getUserAgent() { return userAgent; }
    public String getCorrelationId() { return correlationId; }
    public String getSummary() { return summary; }
}
