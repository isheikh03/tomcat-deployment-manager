package com.tomcatmanager.access;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Tomcat instance access of a deployment operator or viewer. A grant covers one instance, every instance of a
 * Linux host, or every instance of a team; host and team grants include instances added later. Its level never
 * exceeds what the user's role allows (a viewer's grants are always VIEW).
 */
@Entity
@Table(name = "access_grants")
public class AccessGrant {
    public enum Scope { INSTANCE, HOST, TEAM }

    public enum Level {
        DEPLOY("Deploy"), VIEW("View");
        private final String label;
        Level(String label) { this.label = label; }
        public String label() { return label; }
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false, updatable = false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10, updatable = false) private Scope scope;
    @Column(name = "team_id", updatable = false) private Long teamId;
    @Column(name = "host_id", updatable = false) private Long hostId;
    @Column(name = "instance_id", updatable = false) private Long instanceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Level level;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(length = 300) private String reason;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "created_by", nullable = false, length = 60, updatable = false) private String createdBy;

    protected AccessGrant() { }

    private AccessGrant(Long userId, Scope scope, Long teamId, Long hostId, Long instanceId, Level level, String createdBy) {
        this.userId = userId; this.scope = scope; this.teamId = teamId; this.hostId = hostId; this.instanceId = instanceId;
        this.level = level; this.createdBy = createdBy;
    }

    public static AccessGrant toInstance(Long userId, Long instanceId, Level level, String createdBy) {
        return new AccessGrant(userId, Scope.INSTANCE, null, null, instanceId, level, createdBy);
    }

    public static AccessGrant toHost(Long userId, Long hostId, Level level, String createdBy) {
        return new AccessGrant(userId, Scope.HOST, null, hostId, null, level, createdBy);
    }

    public static AccessGrant toTeam(Long userId, Long teamId, Level level, String createdBy) {
        return new AccessGrant(userId, Scope.TEAM, teamId, null, null, level, createdBy);
    }

    /** Whether the grant is in force at the given time (it has no expiry, or has not expired yet). */
    public boolean activeAt(Instant now) { return expiresAt == null || expiresAt.isAfter(now); }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Scope getScope() { return scope; }
    public Long getTeamId() { return teamId; }
    public Long getHostId() { return hostId; }
    public Long getInstanceId() { return instanceId; }
    public Level getLevel() { return level; }
    public void setLevel(Level level) { this.level = level; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason == null || reason.isBlank() ? null : reason.strip(); }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
