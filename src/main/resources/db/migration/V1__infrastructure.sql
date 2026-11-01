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
