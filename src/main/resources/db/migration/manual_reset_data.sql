-- MANUAL SCRIPT - NOT A FLYWAY MIGRATION. Run it by hand, e.g.:
--   psql -h localhost -U <user> -d tomcat_manager -f src/main/resources/db/migration/manual_reset_data.sql
-- Never rename it to V<n>__*.sql, R__*.sql or a callback name (beforeMigrate.sql, afterMigrate.sql, ...):
-- Flyway would then run it automatically at start-up and wipe the data.
--
-- Deletes all data except accounts (app_users) and runtime settings (app_settings), and keeps
-- flyway_schema_history so Flyway still knows the schema is up to date. ID sequences restart at 1.
-- Tables added by later migrations are included automatically.
-- Not CASCADE: if a kept table ever references a truncated one, this fails instead of emptying it.
-- Stored release files and WAR backups on disk (TDL_ARTIFACT_STORAGE, TDL_DEPLOYMENT_BACKUP_DIR) are not removed.

DO $$
DECLARE
    tables TEXT;
BEGIN
    SELECT string_agg(format('%I.%I', schemaname, tablename), ', ')
    INTO tables
    FROM pg_tables
    WHERE schemaname = current_schema()
      AND tablename NOT IN ('app_users', 'app_settings', 'flyway_schema_history');

    IF tables IS NOT NULL THEN
        RAISE NOTICE 'Truncating: %', tables;
        EXECUTE 'TRUNCATE ' || tables || ' RESTART IDENTITY';
    END IF;
END $$;
