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
