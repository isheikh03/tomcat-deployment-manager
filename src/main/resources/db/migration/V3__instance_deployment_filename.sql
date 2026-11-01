-- The WAR filename an uploaded release is renamed to when deployed to this instance
-- (e.g. appA.war -> context /appA). The canonical release artifact is never renamed.
ALTER TABLE tomcat_instances ADD COLUMN deployment_filename VARCHAR(255);

-- Two instances cannot both claim the same file in the same deployment directory.
CREATE UNIQUE INDEX uk_tomcat_instances_host_dir_filename
    ON tomcat_instances (host_id, catalina_base, app_base, LOWER(deployment_filename))
    WHERE deployment_filename IS NOT NULL;
