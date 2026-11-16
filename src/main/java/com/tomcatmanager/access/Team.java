package com.tomcatmanager.access;

import jakarta.persistence.*;

import java.time.Instant;

/** A team owns Linux hosts (and through them Tomcat instances), applications and server groups. */
@Entity
@Table(name = "teams")
public class Team {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 100) private String name;
    @Column(length = 500) private String description;
    /** The team that received everything created before teams existed; there is at most one. */
    @Column(name = "is_default", nullable = false) private boolean defaultTeam;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "created_by", nullable = false, length = 60, updatable = false) private String createdBy;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Team() { }

    public Team(String name, String description, String createdBy) {
        setName(name); setDescription(description); this.createdBy = createdBy;
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.strip(); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description == null || description.isBlank() ? null : description.strip(); }
    public boolean isDefaultTeam() { return defaultTeam; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
