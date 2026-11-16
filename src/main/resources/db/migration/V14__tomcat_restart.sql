-- Optional full Tomcat restart around SSH/SFTP deployments (PostgreSQL 9.5 compatible).
-- restart_mode: NONE (hot deployment, as before), SYSTEMD (systemctl stop/start <restart_service>) or SCRIPTS
-- (shutdown.sh/startup.sh of catalina_home, or of catalina_base when empty). The commands are built by the application
-- from these validated values; no free-text command is stored. Existing instances stay NONE.
ALTER TABLE tomcat_instances ADD COLUMN restart_mode VARCHAR(10) NOT NULL DEFAULT 'NONE';
ALTER TABLE tomcat_instances ADD COLUMN restart_service VARCHAR(100);
ALTER TABLE tomcat_instances ADD COLUMN restart_use_sudo BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tomcat_instances ADD COLUMN catalina_home VARCHAR(255);
ALTER TABLE tomcat_instances ADD COLUMN restart_stop_wait_seconds INTEGER;
ALTER TABLE tomcat_instances ADD CONSTRAINT ck_tomcat_instances_restart_mode CHECK (restart_mode IN ('NONE', 'SYSTEMD', 'SCRIPTS'));
ALTER TABLE tomcat_instances ADD CONSTRAINT ck_tomcat_instances_restart_service CHECK (restart_mode <> 'SYSTEMD' OR restart_service IS NOT NULL);
ALTER TABLE tomcat_instances ADD CONSTRAINT ck_tomcat_instances_restart_wait CHECK (restart_stop_wait_seconds IS NULL OR restart_stop_wait_seconds BETWEEN 0 AND 300);
