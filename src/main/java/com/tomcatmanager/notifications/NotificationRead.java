package com.tomcatmanager.notifications;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** One notification a user marked as read individually (above their {@link NotificationReadMarker}). */
@Entity
@Table(name = "notification_reads")
@IdClass(NotificationRead.Key.class)
public class NotificationRead {
    @Id @Column(length = 60) private String username;
    @Id @Column(name = "notification_id") private long notificationId;
    @Column(name = "read_at", nullable = false) private Instant readAt = Instant.now();

    protected NotificationRead() { }

    NotificationRead(String username, long notificationId) { this.username = username; this.notificationId = notificationId; }

    public String getUsername() { return username; }
    public long getNotificationId() { return notificationId; }

    public static class Key implements Serializable {
        private String username;
        private long notificationId;

        public Key() { }
        public Key(String username, long notificationId) { this.username = username; this.notificationId = notificationId; }

        @Override public boolean equals(Object o) {
            return o instanceof Key k && k.notificationId == notificationId && Objects.equals(k.username, username);
        }
        @Override public int hashCode() { return Objects.hash(username, notificationId); }
    }
}
