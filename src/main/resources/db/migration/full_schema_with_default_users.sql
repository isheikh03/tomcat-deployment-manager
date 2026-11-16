-- MANUAL SCRIPT - NOT A FLYWAY MIGRATION. Run it by hand on an EMPTY database, e.g.:
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f full_schema_with_default_users.sql
-- Never rename it to V<n>__*.sql, R__*.sql or a callback name: Flyway would run it automatically.
--
-- Contains migrations V1 to V15 merged in order, plus the three default accounts.
-- It is one transaction and fails on a database that already has these tables, so it changes nothing there.
--
-- Afterwards, tell Flyway the schema is already at V15, on the first start of the application only:
--   SPRING_FLYWAY_BASELINE_ON_MIGRATE=true
--   SPRING_FLYWAY_BASELINE_VERSION=15
-- Without these the application refuses to start, because the schema is not empty and has no Flyway history.
--
-- SECURITY: the accounts below have well-known passwords (admin/admin, operator/operator, viewer/viewer).
-- Change all three immediately after the first sign-in on any shared or production server.

BEGIN;

-- ==================================================================
-- V1__infrastructure.sql
-- ==================================================================

CREATE TABLE managed_hosts (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    hostname VARCHAR(255) NOT NULL,
    ssh_port INTEGER NOT NULL DEFAULT 22 CHECK (ssh_port BETWEEN 1 AND 65535),
    ssh_username VARCHAR(120),
    ssh_host_key_fingerprint VARCHAR(1000),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_managed_hosts_name_lower ON managed_hosts (LOWER(name));

CREATE TABLE server_groups (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tomcat_instances (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    host_id BIGINT NOT NULL REFERENCES managed_hosts(id),
    catalina_base VARCHAR(255) NOT NULL,
    app_base VARCHAR(255) NOT NULL DEFAULT 'webapps',
    health_url VARCHAR(255) NOT NULL,
    http_port INTEGER NOT NULL DEFAULT 8080 CHECK (http_port BETWEEN 1 AND 65535),
    deployment_method VARCHAR(30) NOT NULL,
    manager_protocol VARCHAR(10) NOT NULL DEFAULT 'HTTPS',
    manager_host VARCHAR(255),
    manager_port INTEGER NOT NULL DEFAULT 8443 CHECK (manager_port BETWEEN 1 AND 65535),
    manager_endpoint VARCHAR(255) DEFAULT '/manager/text',
    manager_username VARCHAR(120),
    manager_secret_encrypted TEXT,
    authentication_method VARCHAR(30) NOT NULL DEFAULT 'PASSWORD',
    deployment_username VARCHAR(120),
    deployment_secret_encrypted TEXT,
    ssh_private_key_encrypted TEXT,
    ssh_key_passphrase_encrypted TEXT,
    ssh_host_key_fingerprint VARCHAR(1000),
    timeout_seconds INTEGER NOT NULL DEFAULT 15 CHECK (timeout_seconds BETWEEN 1 AND 300),
    max_retries INTEGER NOT NULL DEFAULT 2 CHECK (max_retries BETWEEN 0 AND 5),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tomcat_host_name UNIQUE(host_id, name)
);
CREATE INDEX ix_tomcat_instances_host_id ON tomcat_instances(host_id);

CREATE TABLE server_group_instances (
    instance_id BIGINT NOT NULL REFERENCES tomcat_instances(id) ON DELETE CASCADE,
    group_id BIGINT NOT NULL REFERENCES server_groups(id) ON DELETE CASCADE,
    PRIMARY KEY (instance_id, group_id)
);


-- ==================================================================
-- V2__host_credentials_and_connection_tests.sql
-- ==================================================================

-- Milestone 3: SSH authentication belongs to the Linux host (one account per server),
-- and connection tests are persisted with one row per individual check.

ALTER TABLE managed_hosts
    ADD COLUMN ssh_auth_method VARCHAR(30) NOT NULL DEFAULT 'PASSWORD',
    ADD COLUMN ssh_password_encrypted TEXT,
    ADD COLUMN ssh_private_key_encrypted TEXT,
    ADD COLUMN ssh_key_passphrase_encrypted TEXT,
    ADD COLUMN notes VARCHAR(2000);

ALTER TABLE managed_hosts
    ADD CONSTRAINT ck_managed_hosts_ssh_auth_method CHECK (ssh_auth_method IN ('PASSWORD', 'PRIVATE_KEY'));

-- Carry instance-level SSH settings from V1 over to the owning host. When several
-- instances on one host had credentials, the lowest instance ID wins; the host's own
-- username and fingerprint take precedence over instance values.
UPDATE managed_hosts h
SET ssh_username                 = COALESCE(h.ssh_username, t.deployment_username),
    ssh_host_key_fingerprint     = COALESCE(h.ssh_host_key_fingerprint, t.ssh_host_key_fingerprint),
    ssh_auth_method              = CASE WHEN t.authentication_method = 'SSH_PRIVATE_KEY' THEN 'PRIVATE_KEY' ELSE 'PASSWORD' END,
    ssh_password_encrypted       = t.deployment_secret_encrypted,
    ssh_private_key_encrypted    = t.ssh_private_key_encrypted,
    ssh_key_passphrase_encrypted = t.ssh_key_passphrase_encrypted
FROM (SELECT DISTINCT ON (host_id) *
      FROM tomcat_instances
      WHERE deployment_username IS NOT NULL
         OR deployment_secret_encrypted IS NOT NULL
         OR ssh_private_key_encrypted IS NOT NULL
         OR ssh_host_key_fingerprint IS NOT NULL
      ORDER BY host_id, id) t
WHERE t.host_id = h.id;

ALTER TABLE tomcat_instances
    DROP COLUMN authentication_method,
    DROP COLUMN deployment_username,
    DROP COLUMN deployment_secret_encrypted,
    DROP COLUMN ssh_private_key_encrypted,
    DROP COLUMN ssh_key_passphrase_encrypted,
    DROP COLUMN ssh_host_key_fingerprint;

CREATE TABLE connection_test_results (
    id                       BIGSERIAL PRIMARY KEY,
    correlation_id           UUID         NOT NULL UNIQUE,
    host_id                  BIGINT       NOT NULL REFERENCES managed_hosts (id) ON DELETE CASCADE,
    instance_id              BIGINT REFERENCES tomcat_instances (id) ON DELETE CASCADE,
    test_type                VARCHAR(40)  NOT NULL,
    outcome                  VARCHAR(20)  NOT NULL,
    host_key_verification    VARCHAR(20)  NOT NULL,
    presented_host_key       VARCHAR(200),
    remote_server_info       VARCHAR(255),
    initiated_by             VARCHAR(120) NOT NULL,
    started_at               TIMESTAMPTZ  NOT NULL,
    finished_at              TIMESTAMPTZ  NOT NULL,
    duration_ms              BIGINT       NOT NULL CHECK (duration_ms >= 0),
    CONSTRAINT ck_connection_test_type CHECK (test_type IN ('HOST_SSH', 'INSTANCE_SSH_SFTP', 'INSTANCE_TOMCAT_MANAGER')),
    CONSTRAINT ck_connection_test_outcome CHECK (outcome IN ('SUCCESS', 'WARNING', 'FAILED')),
    CONSTRAINT ck_connection_test_host_key CHECK (host_key_verification IN ('MATCHED', 'UNKNOWN', 'MISMATCH', 'NOT_CHECKED'))
);
CREATE INDEX ix_connection_tests_host_finished ON connection_test_results (host_id, finished_at DESC);
CREATE INDEX ix_connection_tests_instance_finished ON connection_test_results (instance_id, finished_at DESC);

CREATE TABLE connection_test_checks (
    result_id   BIGINT       NOT NULL REFERENCES connection_test_results (id) ON DELETE CASCADE,
    check_order INTEGER      NOT NULL,
    check_type  VARCHAR(40)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    detail      VARCHAR(1000),
    PRIMARY KEY (result_id, check_order),
    CONSTRAINT ck_connection_check_status CHECK (status IN ('SUCCESS', 'WARNING', 'FAILED', 'NOT_APPLICABLE', 'SKIPPED'))
);


-- ==================================================================
-- V3__instance_deployment_filename.sql
-- ==================================================================

-- The WAR filename an uploaded release is renamed to when deployed to this instance
-- (e.g. app.war -> context /app). The canonical release artifact is never renamed.
ALTER TABLE tomcat_instances ADD COLUMN deployment_filename VARCHAR(255);

-- Two instances cannot both claim the same file in the same deployment directory.
CREATE UNIQUE INDEX uk_tomcat_instances_host_dir_filename
    ON tomcat_instances (host_id, catalina_base, app_base, LOWER(deployment_filename))
    WHERE deployment_filename IS NOT NULL;


-- ==================================================================
-- V4__applications_and_releases.sql
-- ==================================================================

-- Milestone 4: application registry and release library.

CREATE TABLE applications (
    id                        BIGSERIAL PRIMARY KEY,
    name                      VARCHAR(120)  NOT NULL,
    description               VARCHAR(2000),
    servlet_api               VARCHAR(20)   NOT NULL DEFAULT 'DETECT',
    min_java_version          INTEGER,
    max_java_version          INTEGER,
    supported_tomcat_versions VARCHAR(200),
    other_requirements        VARCHAR(2000),
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                VARCHAR(120)  NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                VARCHAR(120)  NOT NULL,
    version                   BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_applications_servlet_api CHECK (servlet_api IN ('DETECT', 'JAVAX', 'JAKARTA')),
    CONSTRAINT ck_applications_min_java CHECK (min_java_version IS NULL OR min_java_version BETWEEN 8 AND 40),
    CONSTRAINT ck_applications_max_java CHECK (max_java_version IS NULL OR max_java_version BETWEEN 8 AND 40),
    CONSTRAINT ck_applications_java_range CHECK (min_java_version IS NULL OR max_java_version IS NULL OR max_java_version >= min_java_version)
);
CREATE UNIQUE INDEX uk_applications_name_lower ON applications (LOWER(name));

-- Canonical, immutable WAR bytes, addressed by SHA-256. Shared by every release with identical content.
CREATE TABLE stored_artifacts (
    id                    BIGSERIAL PRIMARY KEY,
    sha256                VARCHAR(64)   NOT NULL UNIQUE,
    size_bytes            BIGINT        NOT NULL,
    storage_path          VARCHAR(500)  NOT NULL,
    entry_count           INTEGER       NOT NULL,
    has_web_xml           BOOLEAN       NOT NULL,
    detected_java_version INTEGER,
    detected_servlet_api  VARCHAR(20)   NOT NULL,
    inspection_notes      VARCHAR(1000),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_stored_artifacts_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_stored_artifacts_size CHECK (size_bytes > 0),
    CONSTRAINT ck_stored_artifacts_servlet_api CHECK (detected_servlet_api IN ('JAVAX', 'JAKARTA', 'UNKNOWN'))
);

CREATE TABLE release_artifacts (
    id                   BIGSERIAL PRIMARY KEY,
    application_id       BIGINT        NOT NULL REFERENCES applications (id),
    artifact_id          BIGINT        NOT NULL REFERENCES stored_artifacts (id),
    version_label        VARCHAR(100)  NOT NULL,
    original_filename    VARCHAR(255)  NOT NULL,
    release_notes        TEXT,
    archived             BOOLEAN       NOT NULL DEFAULT FALSE,
    uploaded_by          VARCHAR(120)  NOT NULL,
    uploaded_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by           VARCHAR(120)  NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_verified_at     TIMESTAMPTZ,
    last_verification_ok BOOLEAN,
    version              BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_release_app_version_label ON release_artifacts (application_id, LOWER(version_label));
-- The same bytes become at most one release per application.
CREATE UNIQUE INDEX uk_release_app_artifact ON release_artifacts (application_id, artifact_id);
CREATE INDEX ix_release_uploaded_at ON release_artifacts (uploaded_at DESC);
CREATE INDEX ix_release_artifact ON release_artifacts (artifact_id);

-- Known runtime of each Tomcat instance, used for compatibility checks.
ALTER TABLE tomcat_instances
    ADD COLUMN tomcat_version VARCHAR(40),
    ADD COLUMN java_version   INTEGER;
ALTER TABLE tomcat_instances
    ADD CONSTRAINT ck_tomcat_instances_java_version CHECK (java_version IS NULL OR java_version BETWEEN 8 AND 40);


-- ==================================================================
-- V5__deployments.sql
-- ==================================================================

-- Milestone 5: deployment preflight and engine.

-- Environment of each Tomcat instance; NULL means "not assigned" and blocks deployment until set.
ALTER TABLE tomcat_instances ADD COLUMN environment VARCHAR(20);
ALTER TABLE tomcat_instances ADD CONSTRAINT ck_tomcat_instances_environment
    CHECK (environment IS NULL OR environment IN ('DEVELOPMENT', 'TESTING', 'STAGING', 'PRODUCTION'));

CREATE TABLE deployment_runs (
    id                     BIGSERIAL PRIMARY KEY,
    request_id             UUID          NOT NULL UNIQUE,   -- idempotency key from the wizard
    correlation_id         UUID          NOT NULL UNIQUE,
    application_id         BIGINT        NOT NULL REFERENCES applications (id),
    release_id             BIGINT        NOT NULL REFERENCES release_artifacts (id),
    environment            VARCHAR(20)   NOT NULL,
    strategy               VARCHAR(20)   NOT NULL,
    max_parallel           INTEGER       NOT NULL DEFAULT 1,
    server_group_id        BIGINT        REFERENCES server_groups (id) ON DELETE SET NULL,
    server_group_name      VARCHAR(100),
    state                  VARCHAR(30)   NOT NULL,
    warnings_acknowledged  BOOLEAN       NOT NULL DEFAULT FALSE,
    requested_by           VARCHAR(120)  NOT NULL,
    requested_at           TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,
    cancel_requested_by    VARCHAR(120),
    cancel_requested_at    TIMESTAMPTZ,
    summary                VARCHAR(1000),
    version                BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_deployment_runs_environment CHECK (environment IN ('DEVELOPMENT', 'TESTING', 'STAGING', 'PRODUCTION')),
    CONSTRAINT ck_deployment_runs_strategy CHECK (strategy IN ('SEQUENTIAL', 'PARALLEL')),
    CONSTRAINT ck_deployment_runs_parallel CHECK (max_parallel BETWEEN 1 AND 10),
    CONSTRAINT ck_deployment_runs_state CHECK (state IN ('PENDING', 'VALIDATING', 'RUNNING', 'PARTIALLY_SUCCEEDED', 'SUCCEEDED',
                                                         'FAILED', 'CANCEL_REQUESTED', 'CANCELLED', 'UNKNOWN'))
);
CREATE INDEX ix_deployment_runs_requested ON deployment_runs (requested_at DESC);
CREATE INDEX ix_deployment_runs_release ON deployment_runs (release_id);
CREATE INDEX ix_deployment_runs_state ON deployment_runs (state);

-- One row per target, created before any remote action. Instance details are copied so history
-- survives later configuration changes or deletion.
CREATE TABLE deployment_targets (
    id                   BIGSERIAL PRIMARY KEY,
    run_id               BIGINT        NOT NULL REFERENCES deployment_runs (id),
    instance_id          BIGINT        REFERENCES tomcat_instances (id) ON DELETE SET NULL,
    instance_name        VARCHAR(120)  NOT NULL,
    host_name            VARCHAR(120)  NOT NULL,
    hostname             VARCHAR(255)  NOT NULL,
    deployment_method    VARCHAR(30)   NOT NULL,
    target_filename      VARCHAR(255)  NOT NULL,
    context_path         VARCHAR(255)  NOT NULL,
    previous_release_id  BIGINT        REFERENCES release_artifacts (id) ON DELETE SET NULL,
    sequence             INTEGER       NOT NULL,
    state                VARCHAR(30)   NOT NULL,
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    bytes_transferred    BIGINT,
    message              VARCHAR(1000),
    reconciled_by        VARCHAR(120),
    reconciled_at        TIMESTAMPTZ,
    reconcile_note       VARCHAR(1000),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_deployment_targets_state CHECK (state IN ('PENDING', 'PREFLIGHT_FAILED', 'BACKUP_IN_PROGRESS', 'BACKUP_COMPLETED',
        'TRANSFER_IN_PROGRESS', 'DEPLOYING', 'HEALTH_CHECKING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'ROLLBACK_REQUIRED',
        'ROLLING_BACK', 'ROLLED_BACK', 'UNKNOWN')),
    CONSTRAINT uk_deployment_targets_run_instance UNIQUE (run_id, instance_id)
);
CREATE INDEX ix_deployment_targets_run ON deployment_targets (run_id, sequence);
CREATE INDEX ix_deployment_targets_instance ON deployment_targets (instance_id, finished_at DESC);
-- Per-instance exclusion: at most one unfinished (or unreconciled) deployment per Tomcat instance.
CREATE UNIQUE INDEX uk_deployment_targets_active_instance ON deployment_targets (instance_id)
    WHERE state IN ('PENDING', 'BACKUP_IN_PROGRESS', 'BACKUP_COMPLETED', 'TRANSFER_IN_PROGRESS', 'DEPLOYING',
                    'HEALTH_CHECKING', 'ROLLING_BACK', 'UNKNOWN');

CREATE TABLE deployment_backups (
    id               BIGSERIAL PRIMARY KEY,
    target_id        BIGINT        NOT NULL UNIQUE REFERENCES deployment_targets (id),
    source           VARCHAR(30)   NOT NULL,
    release_id       BIGINT        REFERENCES release_artifacts (id) ON DELETE SET NULL,
    storage_path     VARCHAR(500),
    sha256           VARCHAR(64),
    size_bytes       BIGINT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_deployment_backups_source CHECK (source IN ('REMOTE_WAR', 'LIBRARY_RELEASE'))
);

CREATE TABLE deployment_logs (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT        NOT NULL REFERENCES deployment_runs (id),
    target_id    BIGINT        REFERENCES deployment_targets (id),
    logged_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    level        VARCHAR(10)   NOT NULL,
    event        VARCHAR(60)   NOT NULL,
    message      VARCHAR(2000) NOT NULL,
    duration_ms  BIGINT,
    CONSTRAINT ck_deployment_logs_level CHECK (level IN ('INFO', 'WARN', 'ERROR'))
);
CREATE INDEX ix_deployment_logs_run ON deployment_logs (run_id, id);


-- ==================================================================
-- V6__optional_health_check.sql
-- ==================================================================

-- Application health verification after deployment becomes a per-instance setting.
-- When disabled, the health URL and HTTP port are optional and deployments skip the health check.
ALTER TABLE tomcat_instances ADD COLUMN health_check_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tomcat_instances ALTER COLUMN health_url DROP NOT NULL;
ALTER TABLE tomcat_instances ALTER COLUMN http_port DROP NOT NULL;
ALTER TABLE tomcat_instances ALTER COLUMN http_port DROP DEFAULT;


-- ==================================================================
-- V7__optional_backup.sql
-- ==================================================================

-- Backing up the currently deployed WAR before replacing it becomes a per-instance setting.
-- Existing instances keep backups on.
ALTER TABLE tomcat_instances ADD COLUMN backup_enabled BOOLEAN NOT NULL DEFAULT TRUE;


-- ==================================================================
-- V8__health_monitoring_and_rollback.sql
-- ==================================================================

-- Milestone 6: health checks, periodic monitoring, backup management and manual rollback.

-- Per-instance health policy. NULL means "use the application default" (app.deployment.*).
ALTER TABLE tomcat_instances ADD COLUMN startup_timeout_seconds INTEGER;
ALTER TABLE tomcat_instances ADD COLUMN health_consecutive_successes INTEGER;
ALTER TABLE tomcat_instances ADD COLUMN health_expected_status VARCHAR(60);   -- e.g. "200-299" or "200,204"; NULL = any 2xx
ALTER TABLE tomcat_instances ADD COLUMN health_expected_text VARCHAR(200);    -- optional text the response body must contain
ALTER TABLE tomcat_instances ADD COLUMN monitoring_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tomcat_instances ADD CONSTRAINT ck_tomcat_instances_startup_timeout
    CHECK (startup_timeout_seconds IS NULL OR startup_timeout_seconds BETWEEN 10 AND 1800);
ALTER TABLE tomcat_instances ADD CONSTRAINT ck_tomcat_instances_consecutive_successes
    CHECK (health_consecutive_successes IS NULL OR health_consecutive_successes BETWEEN 1 AND 10);

-- Rollbacks are separate deployment runs linked to the deployment target they undo.
ALTER TABLE deployment_runs ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'DEPLOYMENT';
ALTER TABLE deployment_runs ADD CONSTRAINT ck_deployment_runs_kind CHECK (kind IN ('DEPLOYMENT', 'ROLLBACK'));
ALTER TABLE deployment_runs ADD COLUMN rollback_of_target_id BIGINT REFERENCES deployment_targets (id);
ALTER TABLE deployment_runs ADD COLUMN rollback_backup_id BIGINT REFERENCES deployment_backups (id) ON DELETE SET NULL;
ALTER TABLE deployment_runs ADD COLUMN restore_description VARCHAR(300);
-- A rollback that restores a server backup which matches no library release has no release.
ALTER TABLE deployment_runs ALTER COLUMN release_id DROP NOT NULL;
ALTER TABLE deployment_runs ADD CONSTRAINT ck_deployment_runs_release CHECK (release_id IS NOT NULL OR kind = 'ROLLBACK');
ALTER TABLE deployment_runs ADD CONSTRAINT ck_deployment_runs_rollback_link CHECK ((kind = 'ROLLBACK') = (rollback_of_target_id IS NOT NULL));
CREATE INDEX ix_deployment_runs_rollback_of ON deployment_runs (rollback_of_target_id);

-- Set on the original target once a rollback of it has succeeded.
ALTER TABLE deployment_targets ADD COLUMN rolled_back_by_run_id BIGINT REFERENCES deployment_runs (id);

-- Backup management: integrity verification and deletion are recorded, never silent.
ALTER TABLE deployment_backups ADD COLUMN last_verified_at TIMESTAMPTZ;
ALTER TABLE deployment_backups ADD COLUMN last_verification_ok BOOLEAN;
ALTER TABLE deployment_backups ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE deployment_backups ADD COLUMN deleted_by VARCHAR(120);
ALTER TABLE deployment_backups ADD COLUMN delete_reason VARCHAR(300);

-- Application health history (scheduled, manual and post-deployment checks).
CREATE TABLE health_check_results (
    id            BIGSERIAL PRIMARY KEY,
    instance_id   BIGINT        NOT NULL REFERENCES tomcat_instances (id) ON DELETE CASCADE,
    context_path  VARCHAR(255),
    url           VARCHAR(500)  NOT NULL,
    trigger       VARCHAR(20)   NOT NULL,
    checked_at    TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    healthy       BOOLEAN       NOT NULL,
    status_code   INTEGER,
    elapsed_ms    BIGINT        NOT NULL,
    detail        VARCHAR(500)  NOT NULL,
    checked_by    VARCHAR(120),
    CONSTRAINT ck_health_check_results_trigger CHECK (trigger IN ('SCHEDULED', 'MANUAL'))
);
CREATE INDEX ix_health_check_results_instance ON health_check_results (instance_id, checked_at DESC);
CREATE INDEX ix_health_check_results_checked ON health_check_results (checked_at);

-- Latest monitoring state per instance (one row, updated after every check).
CREATE TABLE instance_health_status (
    instance_id           BIGINT        PRIMARY KEY REFERENCES tomcat_instances (id) ON DELETE CASCADE,
    status                VARCHAR(20)   NOT NULL,
    last_checked_at       TIMESTAMPTZ,
    last_success_at       TIMESTAMPTZ,
    last_failure_at       TIMESTAMPTZ,
    consecutive_failures  INTEGER       NOT NULL DEFAULT 0,
    last_status_code      INTEGER,
    last_detail           VARCHAR(500),
    last_elapsed_ms       BIGINT,
    unhealthy_since       TIMESTAMPTZ,
    last_recovered_at     TIMESTAMPTZ,
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_instance_health_status CHECK (status IN ('HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNKNOWN'))
);


-- ==================================================================
-- V9__users_audit_notifications_settings.sql
-- ==================================================================

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
    FOR EACH ROW EXECUTE PROCEDURE audit_events_append_only();

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


-- ==================================================================
-- V10__individual_notification_reads.sql
-- ==================================================================

-- Notifications a user marked as read one by one, above their "read up to" marker (notification_read_markers).
-- "Mark all as read" advances the marker and removes the rows it now covers.
CREATE TABLE notification_reads (
    username         VARCHAR(60)   NOT NULL,
    notification_id  BIGINT        NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    read_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (username, notification_id)
);


-- ==================================================================
-- V11__release_file_removal.sql
-- ==================================================================

-- Storage cleanup: an administrator can remove a stored WAR file to free space while keeping the
-- release records, deployment history and audit trail. A removed file cannot be deployed or used
-- for a rollback; uploading the identical WAR again (same SHA-256) restores it.
ALTER TABLE stored_artifacts ADD COLUMN file_removed_at TIMESTAMPTZ;
ALTER TABLE stored_artifacts ADD COLUMN file_removed_by VARCHAR(60);
ALTER TABLE stored_artifacts ADD COLUMN file_removed_reason VARCHAR(500);


-- ==================================================================
-- V12__teams_and_access.sql
-- ==================================================================

-- Teams and access control. Compatible with PostgreSQL 9.5.
--
-- A team owns Linux hosts (and through them their Tomcat instances), applications and server groups.
-- Super users have access everywhere; administrators to the teams assigned to them (user_teams);
-- deployment operators and viewers to the Tomcat instances granted to them (access_grants).
-- Everything that exists before this migration moves into the "Default team", and every account keeps
-- the access it had: administrators become super users; operators and viewers get the whole Default team.

CREATE TABLE teams (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL,
    description  VARCHAR(500),
    is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(60)   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version      BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_teams_name ON teams (lower(name));
-- At most one default team.
CREATE UNIQUE INDEX uk_teams_default ON teams (is_default) WHERE is_default;

INSERT INTO teams (name, description, is_default, created_by)
VALUES ('Default team', 'Everything that existed before teams were introduced.', TRUE, 'system');

-- New hosts, applications and groups belong to the default team unless a team is given.
CREATE FUNCTION default_team_id() RETURNS BIGINT AS $$
    SELECT id FROM teams WHERE is_default
$$ LANGUAGE sql STABLE;

ALTER TABLE managed_hosts ADD COLUMN team_id BIGINT REFERENCES teams (id);
UPDATE managed_hosts SET team_id = default_team_id();
ALTER TABLE managed_hosts ALTER COLUMN team_id SET DEFAULT default_team_id();
ALTER TABLE managed_hosts ALTER COLUMN team_id SET NOT NULL;
CREATE INDEX ix_managed_hosts_team ON managed_hosts (team_id);

ALTER TABLE applications ADD COLUMN team_id BIGINT REFERENCES teams (id);
UPDATE applications SET team_id = default_team_id();
ALTER TABLE applications ALTER COLUMN team_id SET DEFAULT default_team_id();
ALTER TABLE applications ALTER COLUMN team_id SET NOT NULL;
CREATE INDEX ix_applications_team ON applications (team_id);

ALTER TABLE server_groups ADD COLUMN team_id BIGINT REFERENCES teams (id);
UPDATE server_groups SET team_id = default_team_id();
ALTER TABLE server_groups ALTER COLUMN team_id SET DEFAULT default_team_id();
ALTER TABLE server_groups ALTER COLUMN team_id SET NOT NULL;
CREATE INDEX ix_server_groups_team ON server_groups (team_id);

-- Roles: the new top role SUPER_ADMIN. Existing administrators become super users.
ALTER TABLE app_users DROP CONSTRAINT ck_app_users_role;
UPDATE app_users SET role = 'SUPER_ADMIN' WHERE role = 'ADMIN';
ALTER TABLE app_users ADD CONSTRAINT ck_app_users_role CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'DEPLOYMENT_OPERATOR', 'VIEWER'));

-- Teams assigned to an administrator.
CREATE TABLE user_teams (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    team_id     BIGINT        NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(60)   NOT NULL,
    CONSTRAINT uk_user_teams UNIQUE (user_id, team_id)
);
CREATE INDEX ix_user_teams_team ON user_teams (team_id);

-- Tomcat instance access of deployment operators and viewers: one instance, all instances of a Linux host,
-- or all instances of a team (host and team grants include instances added later). DEPLOY or VIEW, never
-- more than the user's role allows; optionally until a date.
CREATE TABLE access_grants (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    scope        VARCHAR(10)   NOT NULL,
    team_id      BIGINT        REFERENCES teams (id) ON DELETE CASCADE,
    host_id      BIGINT        REFERENCES managed_hosts (id) ON DELETE CASCADE,
    instance_id  BIGINT        REFERENCES tomcat_instances (id) ON DELETE CASCADE,
    level        VARCHAR(10)   NOT NULL,
    expires_at   TIMESTAMPTZ,
    reason       VARCHAR(300),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(60)   NOT NULL,
    CONSTRAINT ck_access_grants_level CHECK (level IN ('DEPLOY', 'VIEW')),
    CONSTRAINT ck_access_grants_scope CHECK (
        (scope = 'TEAM'     AND team_id IS NOT NULL     AND host_id IS NULL     AND instance_id IS NULL) OR
        (scope = 'HOST'     AND host_id IS NOT NULL     AND team_id IS NULL     AND instance_id IS NULL) OR
        (scope = 'INSTANCE' AND instance_id IS NOT NULL AND team_id IS NULL     AND host_id IS NULL))
);
CREATE INDEX ix_access_grants_user ON access_grants (user_id);
-- One grant per user and target.
CREATE UNIQUE INDEX uk_access_grants_target ON access_grants
    (user_id, scope, COALESCE(team_id, 0), COALESCE(host_id, 0), COALESCE(instance_id, 0));

-- Operators and viewers keep what they could do before: the whole Default team.
INSERT INTO access_grants (user_id, scope, team_id, level, reason, created_by)
SELECT id, 'TEAM', default_team_id(),
       CASE WHEN role = 'DEPLOYMENT_OPERATOR' THEN 'DEPLOY' ELSE 'VIEW' END,
       'Access before teams were introduced', 'system'
FROM app_users
WHERE role IN ('DEPLOYMENT_OPERATOR', 'VIEWER');


-- ==================================================================
-- V13__application_version_pattern.sql
-- ==================================================================

ALTER TABLE applications ADD COLUMN version_mode VARCHAR(10) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE applications ADD COLUMN version_pattern VARCHAR(60);
ALTER TABLE applications ADD CONSTRAINT ck_applications_version_mode CHECK (version_mode IN ('MANUAL', 'AUTO'));
ALTER TABLE applications ADD CONSTRAINT ck_applications_version_pattern CHECK (version_mode = 'MANUAL' OR version_pattern IS NOT NULL);


-- ==================================================================
-- V14__tomcat_restart.sql
-- ==================================================================

ALTER TABLE tomcat_instances ADD COLUMN restart_mode VARCHAR(10) NOT NULL DEFAULT 'NONE';
ALTER TABLE tomcat_instances ADD COLUMN restart_service VARCHAR(100);
ALTER TABLE tomcat_instances ADD COLUMN restart_use_sudo BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tomcat_instances ADD COLUMN catalina_home VARCHAR(255);
ALTER TABLE tomcat_instances ADD COLUMN restart_stop_wait_seconds INTEGER;
ALTER TABLE tomcat_instances ADD CONSTRAINT ck_tomcat_instances_restart_mode CHECK (restart_mode IN ('NONE', 'SYSTEMD', 'SCRIPTS'));
ALTER TABLE tomcat_instances ADD CONSTRAINT ck_tomcat_instances_restart_service CHECK (restart_mode <> 'SYSTEMD' OR restart_service IS NOT NULL);
ALTER TABLE tomcat_instances ADD CONSTRAINT ck_tomcat_instances_restart_wait CHECK (restart_stop_wait_seconds IS NULL OR restart_stop_wait_seconds BETWEEN 0 AND 300);


-- ==================================================================
-- V15__remember_me.sql
-- ==================================================================

CREATE TABLE persistent_logins (
    username   VARCHAR(64)  NOT NULL,
    series     VARCHAR(64)  PRIMARY KEY,
    token      VARCHAR(64)  NOT NULL,
    last_used  TIMESTAMP    NOT NULL
);
CREATE INDEX ix_persistent_logins_username ON persistent_logins (username);


-- ==================================================================
-- Default accounts (BCrypt, strength 12)
-- ==================================================================

INSERT INTO app_users (username, password_hash, role, must_change_password, created_by) VALUES
    ('admin',    '$2a$12$fBRydmeKmfa8G.2fFYzPYO76rE2mzZttTRPUOjQmVnKwDod.tDltC', 'SUPER_ADMIN',               FALSE, 'system'),
    ('operator', '$2a$12$HTP8FpTx94qoUXZCXHCqG.TsVLCMSihAyF6ljbhZWJcGjkb6KNAnC', 'DEPLOYMENT_OPERATOR', FALSE, 'system'),
    ('viewer',   '$2a$12$yFwlK58wuNAB9lAOrtxz9.3u86WPMSfQPPts0G5UaUiJ5qqsF4.qK', 'VIEWER',              FALSE, 'system');

-- The operator and viewer can work with everything in the Default team (deploy and view, respectively).
INSERT INTO access_grants (user_id, scope, team_id, level, reason, created_by)
SELECT id, 'TEAM', default_team_id(), CASE WHEN role = 'DEPLOYMENT_OPERATOR' THEN 'DEPLOY' ELSE 'VIEW' END, 'Default account', 'system'
FROM app_users WHERE username IN ('operator', 'viewer');

COMMIT;
