package com.tomcatmanager.notifications;

import jakarta.persistence.*;
import java.time.Instant;

/** An operational event shown to users in the application and optionally emailed. */
@Entity
@Table(name = "notifications")
public class Notification {
    public enum Level {
        INFO, SUCCESS, WARNING, DANGER;
        public String badgeClass() {
            return switch (this) { case INFO -> "text-bg-info"; case SUCCESS -> "text-bg-success"; case WARNING -> "text-bg-warning"; case DANGER -> "text-bg-danger"; };
        }
        public boolean atLeast(Level other) { return ordinal() >= other.ordinal(); }
    }
    public enum Category { DEPLOYMENT, ROLLBACK, HEALTH, CONFIGURATION, SECURITY }
    public enum EmailStatus { NOT_SENT, SENT, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10, updatable = false) private Level level;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30, updatable = false) private Category category;
    @Column(nullable = false, length = 200, updatable = false) private String title;
    @Column(nullable = false, length = 1000, updatable = false) private String message;
    @Column(length = 300, updatable = false) private String link;
    @Enumerated(EnumType.STRING) @Column(name = "email_status", nullable = false, length = 20) private EmailStatus emailStatus = EmailStatus.NOT_SENT;
    @Column(name = "email_error", length = 300) private String emailError;

    protected Notification() { }

    Notification(Level level, Category category, String title, String message, String link) {
        this.level = level; this.category = category; this.title = title; this.message = message; this.link = link;
    }

    void emailResult(EmailStatus status, String error) { this.emailStatus = status; this.emailError = error; }

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Level getLevel() { return level; }
    public Category getCategory() { return category; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public String getLink() { return link; }
    public EmailStatus getEmailStatus() { return emailStatus; }
    public String getEmailError() { return emailError; }
}
