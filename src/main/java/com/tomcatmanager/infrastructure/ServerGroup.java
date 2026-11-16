package com.tomcatmanager.infrastructure;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@org.hibernate.annotations.DynamicInsert
@Entity
@Table(name = "server_groups")
public class ServerGroup {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Owning team, chosen when the record is created and fixed afterwards. Left empty, the database uses the default team. */
    @Column(name = "team_id", updatable = false) private Long teamId;
    @NotBlank @Size(max = 100) @Column(nullable = false, unique = true, length = 100)
    private String name;
    @Size(max = 500) private String description;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    public Long getId() { return id; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.trim(); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getCreatedAt() { return createdAt; }
}
