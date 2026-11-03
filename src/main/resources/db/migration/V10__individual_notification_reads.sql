-- Notifications a user marked as read one by one, above their "read up to" marker (notification_read_markers).
-- "Mark all as read" advances the marker and removes the rows it now covers.
CREATE TABLE notification_reads (
    username         VARCHAR(60)   NOT NULL,
    notification_id  BIGINT        NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    read_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (username, notification_id)
);
