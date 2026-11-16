-- Automatic version labels (PostgreSQL 9.5 compatible).
-- An application is versioned MANUAL (the version is typed at upload, as before) or AUTO (the version is built
-- from version_pattern at every upload, e.g. 1.0.{n} or {yyyy}.{MM}.{dd}.{n}). Existing applications stay MANUAL.
ALTER TABLE applications ADD COLUMN version_mode VARCHAR(10) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE applications ADD COLUMN version_pattern VARCHAR(60);
ALTER TABLE applications ADD CONSTRAINT ck_applications_version_mode CHECK (version_mode IN ('MANUAL', 'AUTO'));
ALTER TABLE applications ADD CONSTRAINT ck_applications_version_pattern CHECK (version_mode = 'MANUAL' OR version_pattern IS NOT NULL);
