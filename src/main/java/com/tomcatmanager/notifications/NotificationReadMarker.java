package com.tomcatmanager.notifications;

import jakarta.persistence.*;
import java.time.Instant;

/** Per user: every notification with an id up to {@code lastReadId} has been read. */
@Entity
@Table(name = "notification_read_markers")
public class NotificationReadMarker {
    @Id @Column(length = 60) private String username;
    @Column(name = "last_read_id", nullable = false) private long lastReadId;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected NotificationReadMarker() { }

    NotificationReadMarker(String username) { this.username = username; }

    void readUpTo(long id) { if (id > lastReadId) { lastReadId = id; updatedAt = Instant.now(); } }

    public String getUsername() { return username; }
    public long getLastReadId() { return lastReadId; }
}
