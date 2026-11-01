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
