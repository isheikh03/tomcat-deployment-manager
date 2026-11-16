-- Teams and access control. Compatible with PostgreSQL 9.5.
--
-- A team owns Linux hosts (and through them their Tomcat instances), applications and server groups.
-- Super users have access everywhere; administrators to the teams assigned to them (user_teams);
-- deployment operators and viewers to the Tomcat instances granted to them (access_grants).
-- Everything that exists before this migration moves into the "Default team", and every account keeps
-- the access it had: administrators become super users; operators and viewers get the whole Default team.

CREATE TABLE teams (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL,
    description  VARCHAR(500),
    is_default   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(60)   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version      BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_teams_name ON teams (lower(name));
-- At most one default team.
CREATE UNIQUE INDEX uk_teams_default ON teams (is_default) WHERE is_default;

INSERT INTO teams (name, description, is_default, created_by)
VALUES ('Default team', 'Everything that existed before teams were introduced.', TRUE, 'system');

-- New hosts, applications and groups belong to the default team unless a team is given.
CREATE FUNCTION default_team_id() RETURNS BIGINT AS $$
    SELECT id FROM teams WHERE is_default
$$ LANGUAGE sql STABLE;

ALTER TABLE managed_hosts ADD COLUMN team_id BIGINT REFERENCES teams (id);
UPDATE managed_hosts SET team_id = default_team_id();
ALTER TABLE managed_hosts ALTER COLUMN team_id SET DEFAULT default_team_id();
ALTER TABLE managed_hosts ALTER COLUMN team_id SET NOT NULL;
CREATE INDEX ix_managed_hosts_team ON managed_hosts (team_id);

ALTER TABLE applications ADD COLUMN team_id BIGINT REFERENCES teams (id);
UPDATE applications SET team_id = default_team_id();
ALTER TABLE applications ALTER COLUMN team_id SET DEFAULT default_team_id();
ALTER TABLE applications ALTER COLUMN team_id SET NOT NULL;
CREATE INDEX ix_applications_team ON applications (team_id);

ALTER TABLE server_groups ADD COLUMN team_id BIGINT REFERENCES teams (id);
UPDATE server_groups SET team_id = default_team_id();
ALTER TABLE server_groups ALTER COLUMN team_id SET DEFAULT default_team_id();
ALTER TABLE server_groups ALTER COLUMN team_id SET NOT NULL;
CREATE INDEX ix_server_groups_team ON server_groups (team_id);

-- Roles: the new top role SUPER_ADMIN. Existing administrators become super users.
ALTER TABLE app_users DROP CONSTRAINT ck_app_users_role;
UPDATE app_users SET role = 'SUPER_ADMIN' WHERE role = 'ADMIN';
ALTER TABLE app_users ADD CONSTRAINT ck_app_users_role CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'DEPLOYMENT_OPERATOR', 'VIEWER'));

-- Teams assigned to an administrator.
CREATE TABLE user_teams (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    team_id     BIGINT        NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by  VARCHAR(60)   NOT NULL,
    CONSTRAINT uk_user_teams UNIQUE (user_id, team_id)
);
CREATE INDEX ix_user_teams_team ON user_teams (team_id);

-- Tomcat instance access of deployment operators and viewers: one instance, all instances of a Linux host,
-- or all instances of a team (host and team grants include instances added later). DEPLOY or VIEW, never
-- more than the user's role allows; optionally until a date.
CREATE TABLE access_grants (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    scope        VARCHAR(10)   NOT NULL,
    team_id      BIGINT        REFERENCES teams (id) ON DELETE CASCADE,
    host_id      BIGINT        REFERENCES managed_hosts (id) ON DELETE CASCADE,
    instance_id  BIGINT        REFERENCES tomcat_instances (id) ON DELETE CASCADE,
    level        VARCHAR(10)   NOT NULL,
    expires_at   TIMESTAMPTZ,
    reason       VARCHAR(300),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(60)   NOT NULL,
    CONSTRAINT ck_access_grants_level CHECK (level IN ('DEPLOY', 'VIEW')),
    CONSTRAINT ck_access_grants_scope CHECK (
        (scope = 'TEAM'     AND team_id IS NOT NULL     AND host_id IS NULL     AND instance_id IS NULL) OR
        (scope = 'HOST'     AND host_id IS NOT NULL     AND team_id IS NULL     AND instance_id IS NULL) OR
        (scope = 'INSTANCE' AND instance_id IS NOT NULL AND team_id IS NULL     AND host_id IS NULL))
);
CREATE INDEX ix_access_grants_user ON access_grants (user_id);
-- One grant per user and target.
CREATE UNIQUE INDEX uk_access_grants_target ON access_grants
    (user_id, scope, COALESCE(team_id, 0), COALESCE(host_id, 0), COALESCE(instance_id, 0));

-- Operators and viewers keep what they could do before: the whole Default team.
INSERT INTO access_grants (user_id, scope, team_id, level, reason, created_by)
SELECT id, 'TEAM', default_team_id(),
       CASE WHEN role = 'DEPLOYMENT_OPERATOR' THEN 'DEPLOY' ELSE 'VIEW' END,
       'Access before teams were introduced', 'system'
FROM app_users
WHERE role IN ('DEPLOYMENT_OPERATOR', 'VIEWER');
