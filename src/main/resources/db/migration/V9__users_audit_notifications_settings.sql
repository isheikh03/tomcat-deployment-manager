-- Milestone 7: local user accounts, durable audit log, in-application notifications, runtime settings.

CREATE TABLE app_users (
    id                     BIGSERIAL PRIMARY KEY,
    username               VARCHAR(60)   NOT NULL,
    display_name           VARCHAR(120),
    email                  VARCHAR(254),
    password_hash          VARCHAR(100)  NOT NULL,           -- BCrypt; never a plain password
    role                   VARCHAR(30)   NOT NULL,
    enabled                BOOLEAN       NOT NULL DEFAULT TRUE,
    must_change_password   BOOLEAN       NOT NULL DEFAULT FALSE,
    failed_attempts        INTEGER       NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    last_login_at          TIMESTAMPTZ,
    password_changed_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by             VARCHAR(60),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_app_users_role CHECK (role IN ('ADMIN', 'DEPLOYMENT_OPERATOR', 'VIEWER')),
    CONSTRAINT ck_app_users_username CHECK (username ~ '^[a-z0-9][a-z0-9._-]{1,59}$')
);
CREATE UNIQUE INDEX uk_app_users_username ON app_users (lower(username));

-- Append-only audit trail. The application never updates or deletes rows; the trigger enforces it.
CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    occurred_at     TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor           VARCHAR(120)  NOT NULL,
    action          VARCHAR(60)   NOT NULL,
    resource_type   VARCHAR(40),
    resource_id     VARCHAR(60),
    outcome         VARCHAR(10)   NOT NULL,
    source_ip       VARCHAR(64),
    user_agent      VARCHAR(200),
    correlation_id  VARCHAR(64),
    summary         VARCHAR(1000) NOT NULL,
    CONSTRAINT ck_audit_events_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
);
CREATE INDEX ix_audit_events_occurred ON audit_events (occurred_at DESC);
CREATE INDEX ix_audit_events_actor ON audit_events (actor, occurred_at DESC);
CREATE INDEX ix_audit_events_resource ON audit_events (resource_type, resource_id);

CREATE FUNCTION audit_events_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_events_append_only BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_append_only();

-- Operational notifications shown in the application (and optionally emailed).
CREATE TABLE notifications (
    id            BIGSERIAL PRIMARY KEY,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    level         VARCHAR(10)   NOT NULL,
    category      VARCHAR(30)   NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    message       VARCHAR(1000) NOT NULL,
    link          VARCHAR(300),
    email_status  VARCHAR(20)   NOT NULL DEFAULT 'NOT_SENT',
    email_error   VARCHAR(300),
    CONSTRAINT ck_notifications_level CHECK (level IN ('INFO', 'SUCCESS', 'WARNING', 'DANGER')),
    CONSTRAINT ck_notifications_email CHECK (email_status IN ('NOT_SENT', 'SENT', 'FAILED'))
);
CREATE INDEX ix_notifications_created ON notifications (created_at DESC);

-- Per-user "read up to" marker: everything with id <= last_read_id counts as read for that user.
CREATE TABLE notification_read_markers (
    username      VARCHAR(60)   PRIMARY KEY,
    last_read_id  BIGINT        NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Runtime-adjustable, non-secret policies. Secrets never go here.
CREATE TABLE app_settings (
    setting_key   VARCHAR(80)   PRIMARY KEY,
    setting_value VARCHAR(1000) NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    VARCHAR(60)   NOT NULL
);
