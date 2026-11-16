package com.tomcatmanager.access;

import jakarta.persistence.*;

import java.time.Instant;

/** A team assigned to an administrator: they manage that team's hosts, instances, applications and users. */
@Entity
@Table(name = "user_teams")
public class UserTeam {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false, updatable = false) private Long userId;
    @Column(name = "team_id", nullable = false, updatable = false) private Long teamId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "created_by", nullable = false, length = 60, updatable = false) private String createdBy;

    protected UserTeam() { }

    public UserTeam(Long userId, Long teamId, String createdBy) {
        this.userId = userId; this.teamId = teamId; this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getTeamId() { return teamId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
