-- Application health verification after deployment becomes a per-instance setting.
-- When disabled, the health URL and HTTP port are optional and deployments skip the health check.
ALTER TABLE tomcat_instances ADD COLUMN health_check_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tomcat_instances ALTER COLUMN health_url DROP NOT NULL;
ALTER TABLE tomcat_instances ALTER COLUMN http_port DROP NOT NULL;
ALTER TABLE tomcat_instances ALTER COLUMN http_port DROP DEFAULT;
