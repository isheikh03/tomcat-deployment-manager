-- Backing up the currently deployed WAR before replacing it becomes a per-instance setting.
-- Existing instances keep backups on.
ALTER TABLE tomcat_instances ADD COLUMN backup_enabled BOOLEAN NOT NULL DEFAULT TRUE;
