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
