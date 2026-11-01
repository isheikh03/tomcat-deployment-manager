package com.tomcatmanager.users;

import jakarta.persistence.*;
import java.time.Instant;

/** A local user account. Only a BCrypt hash of the password is stored. */
@Entity
@Table(name = "app_users")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 60, updatable = false) private String username;
    @Column(name = "display_name", length = 120) private String displayName;
    @Column(length = 254) private String email;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Role role = Role.VIEWER;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "must_change_password", nullable = false) private boolean mustChangePassword;
    @Column(name = "failed_attempts", nullable = false) private int failedAttempts;
    @Column(name = "locked_until") private Instant lockedUntil;
    @Column(name = "last_login_at") private Instant lastLoginAt;
    @Column(name = "password_changed_at", nullable = false) private Instant passwordChangedAt = Instant.now();
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "created_by", length = 60, updatable = false) private String createdBy;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected AppUser() { }

    AppUser(String username, String passwordHash, Role role, String createdBy) {
        this.username = username; this.passwordHash = passwordHash; this.role = role; this.createdBy = createdBy;
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public boolean isLocked() { return lockedUntil != null && lockedUntil.isAfter(Instant.now()); }

    void setPasswordHash(String hash, boolean mustChange) {
        this.passwordHash = hash; this.mustChangePassword = mustChange; this.passwordChangedAt = Instant.now();
        this.failedAttempts = 0; this.lockedUntil = null;
    }
    void recordFailedLogin(int maxAttempts, java.time.Duration lockout) {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) { lockedUntil = Instant.now().plus(lockout); failedAttempts = 0; }
    }
    void recordSuccessfulLogin() { failedAttempts = 0; lockedUntil = null; lastLoginAt = Instant.now(); }
    void unlock() { failedAttempts = 0; lockedUntil = null; }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    void setDisplayName(String v) { displayName = v == null || v.isBlank() ? null : v.strip(); }
    public String getEmail() { return email; }
    void setEmail(String v) { email = v == null || v.isBlank() ? null : v.strip(); }
    String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    void setRole(Role v) { role = v; }
    public boolean isEnabled() { return enabled; }
    void setEnabled(boolean v) { enabled = v; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public String getLabel() { return displayName != null ? displayName + " (" + username + ")" : username; }
}
