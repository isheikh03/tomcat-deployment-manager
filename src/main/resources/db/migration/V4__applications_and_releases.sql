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
