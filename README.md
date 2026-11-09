# Tomcat Deployment Manager

A standalone web application for managing Linux hosts and Apache Tomcat instances and deploying
verified WAR releases to them directly over SSH/SFTP or the Tomcat Manager HTTP interface. No
Jenkins, CI/CD platform, Docker or Kubernetes is needed on target servers.

**Current state: Version 1 (Milestones 1–7).** Infrastructure is verified with real SSH/SFTP and
Tomcat Manager connection tests; WAR releases are checksummed and inspected; releases are deployed
through a guided wizard with an exact preflight preview, durable per-target records, backups,
post-deployment health verification and live progress. Applications are monitored periodically,
restore points are managed, and a deployment can be rolled back with its own checks and
post-rollback verification. Local user accounts with roles, an append-only audit log,
in-application (and optional email) notifications and runtime settings complete Version 1.

## Technology stack

| Area | Choice |
|---|---|
| Runtime | Java 21 (compiles and runs on 17+; see *JDK* below) |
| Framework | Spring Boot 4.1.2 — Spring MVC, Security, Data JPA, Validation, Actuator |
| Database | PostgreSQL 18, schema owned by Flyway, Hibernate `ddl-auto=validate` |
| UI | Thymeleaf + fragments on the Solic admin template (Bootstrap 5, Boxicons/Remix icons, light and dark mode; assets served locally from `static/assets`, only the template's web fonts come from Google Fonts), vanilla JS (Fetch API), SweetAlert2 |
| SSH / SFTP | Apache MINA SSHD 2.20.0 |
| HTTP | `java.net.http.HttpClient` (certificate and host-name verification always on) |
| Build | Maven (wrapper included), Testcontainers for PostgreSQL integration tests |

## Architecture and packages

A modular monolith under `com.tomcatmanager`:

| Package | Responsibility |
|---|---|
| `config` | Spring Security: roles, URL rules, CSRF, sessions and session registry, security headers |
| `users` | Database accounts (BCrypt), roles, lockout, forced password change, first-administrator provisioning, user management |
| `audit` | Append-only audit trail (database trigger), audit service and viewer |
| `notifications` | In-application notifications with per-user read state, optional SMTP email |
| `settings` | Runtime policies (upload policy, monitoring, backup retention, notification email) and the settings page |
| `dashboard` | Dashboard metrics from persisted data |
| `infrastructure` | Hosts, Tomcat instances, server groups; forms, validation, services |
| `connectivity` | SSH/SFTP client, Tomcat Manager client, network-target policy, TLS trust, connection-test orchestration and persisted results, REST API |
| `security` | AES-256-GCM secret encryption, key rotation and credential re-encryption |
| `application` | Application registry and compatibility requirements |
| `release` | Content-addressed WAR storage, WAR inspection, releases, compatibility evaluation, upload/verify API |
| `deployment` | Preflight, engine, deployers, health verification, restore points, rollback preflight/service, backup management, reconciliation evidence, APIs |
| `health` | Application health probe and expectations, periodic/manual monitoring, history and latest status, monitoring pages and API |
| `common` | API/web error handling, pagination DTO, time formatting |

Controllers are thin; rules live in `HostService`, `InstanceService` and `ConnectionTestService`.
Network I/O never runs inside a database transaction.

## Prerequisites

- **JDK 21** (recommended). The code avoids Java 21-only APIs, so it also builds on JDK 17 with
  `-Djava.version=17` if that is all you have.
- Maven 3.9+ or the included wrapper (`./mvnw`, `mvnw.cmd`).
- PostgreSQL 18: either `docker compose up -d postgres` or a local installation.

## Local setup

1. **Database.** With Docker: `docker compose up -d postgres`. Without Docker, create a database
   and an owner, e.g.
   ```sql
   CREATE USER tomcat_manager WITH PASSWORD 'change-me';
   CREATE DATABASE tomcat_manager OWNER tomcat_manager;
   ```
2. **Configuration.** Copy `.env.example` to `.env` (gitignored; loaded automatically via
   `spring.config.import`) and fill in `TDL_DB_*` and `TDL_APP_ENCRYPTION_KEY`. Environment variables of the
   same name also work and take precedence.
3. **Encryption key** (Base64 of 32 random bytes):
   ```bash
   openssl rand -base64 32
   ```
   ```powershell
   $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
   ```
4. **Run:**
   ```bash
   ./mvnw spring-boot:run                       # JDK 21
   ./mvnw -Djava.version=17 spring-boot:run     # JDK 17
   ```
   Open http://localhost:8181 (`SERVER_PORT` changes it). Flyway applies migrations on start-up.

5. **First administrator.** No account with a known password exists by default. On the first
   start with an empty user table, one of these applies (see *Users and roles* below):
   - `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD` set: that administrator is
     created; the password is temporary and must be changed at first sign-in;
   - otherwise the log shows a **one-time setup token**; open http://localhost:8181/setup, enter the
     token and choose the administrator's username and password.

   For a purely local development machine, `APP_DEV_USERS=true` instead seeds `admin`/`admin`,
   `operator`/`operator` and `viewer`/`viewer` (logged as a warning and audited). **Never enable it
   on a shared or production server.**

> If an IDE with a bundled newer JDK compiles into `target/` and Maven then fails with
> `UnsupportedClassVersionError` / "class file version 65.0", run `./mvnw clean ...`.

## Tests

```bash
./mvnw clean test           # add -Djava.version=17 on JDK 17
```

Integration tests need a PostgreSQL they may wipe. With Docker they start one automatically (Testcontainers). Without Docker, create a dedicated database whose name ends in `_it` or `_test` (the tests refuse any other name) and point the tests at it:

```bash
createdb tomcat_manager_it
TDM_IT_DB_URL=jdbc:postgresql://localhost:5432/tomcat_manager_it TDM_IT_DB_USERNAME=postgres TDM_IT_DB_PASSWORD=... ./mvnw clean test
```

| Suite | What it proves |
|---|---|
| `SshConnectivityClientTest`, `PuttyKeyTest` | Against a real in-process SSH/SFTP server: PuTTY `.ppk` keys, password and encrypted private-key auth, wrong password, wrong passphrase, **unknown host key**, **changed host key**, missing directory, SFTP-only account, refused port. Secrets never appear in diagnostics. |
| `TomcatManagerClientTest` | Against real local HTTP/HTTPS servers: success with `manager-script`, 401, 403, `FAIL` body on HTTP 200, HTML page instead of text API, 404, redirect not followed, read timeout, connection closed without response, refused port, **untrusted certificate**, trusted via trust store, **hostname mismatch**. |
| `ConnectivityApiAuthorizationTest`, `InfrastructureAuthorizationTest` | Server-side role rules, CSRF enforcement, 401 for anonymous API calls, JSON error format. |
| `SecretEncryptionServiceTest` | Round trip, fresh nonce, versioned format, wrong key, tampered ciphertext, missing key. |
| `WarInspectorTest`, `ArtifactStorageTest`, `CompatibilityTest`, `ReleaseInputRulesTest`, `ReleaseAuthorizationTest` | WAR validation (non-ZIP, no WEB-INF, path traversal), Java/Servlet API detection, SHA-256, deduplication, read-only storage, tamper and missing-file detection, size limit, compatibility rules (unknown never compatible), upload/archive/delete permissions and JSON errors. |
| `NetworkTargetPolicyTest`, `ConnectivityUtilitiesTest`, `InfrastructureValidationTest` | SSRF policy, sanitising/redaction, fingerprint format, POSIX permission evaluation, path/host validation. |
| `AccountSecurityIntegrationTest` | Against real PostgreSQL through the full security filter chain: database sign-in with roles, lockout after repeated failures and unlock, forced change of a temporary password (pages redirect, APIs 403), administration pages for administrators only, CSRF on sign-in, last-administrator and self-demotion protection, one-time setup token, append-only audit table (UPDATE/DELETE rejected by the database) with redaction, settings validation and audit, runtime upload policy, per-user notification read state, duplicate group membership prevention. |
| `DeploymentEngineIntegrationTest`, `DeploymentAuthorizationTest`, `HealthExpectationTest` | Real deployments, rollbacks, backups, monitoring and reconciliation against PostgreSQL, the embedded SSH/SFTP server and a Tomcat Manager stand-in (see the Milestone 5/6 sections), plus the audit trail and notifications they produce; role rules for deploy, rollback, backup and inspect APIs; health status expectations. |
| `KeyRotationTest`, `PasswordPolicyTest` | Previous-key decryption and re-encryption of host and Manager credentials, refusal when a credential is unreadable; password rules and role authorities. |
| `PersistenceIntegrationTest`, `ReleaseLibraryIntegrationTest` | Against real PostgreSQL: Flyway migrations, Hibernate schema validation, unique constraints, cascade rules, release upload and metadata persistence, deduplication, search/filter/pagination queries, statistics, optimistic locking, shared-file deletion and storage housekeeping. **Skipped (reported as skipped) when no integration database is available**; see below. |

## Two deployment methods

| | SSH / SFTP | Tomcat Manager |
|---|---|---|
| Configured on | the **Linux host**: one login per server, like a WinSCP session | each **Tomcat instance**: its own HTTP/HTTPS web interface |
| How WARs arrive | copied into the instance's `webapps` directory over SFTP | uploaded to `/manager/text` |
| Needs | SSH account with write access to `webapps`, pinned host key | Manager user with the `manager-script` role |

Each instance chooses one method, so both can be mixed on the same server.

### Adding a Linux host (WinSCP-style)

*Infrastructure → New site (Add host)* opens a login dialog modelled on WinSCP. Saved sites are on the
left and the session settings on the right (protocol SFTP, host name, port, user name, then a
**Password** or **Private key** choice).

- **Private key file:** choose a PuTTY/WinSCP `.ppk`, OpenSSH or PEM key (automated tests cover unencrypted PPK v2; verify passphrase-protected PPK v3 keys with **Test connection**, or convert them in PuTTYgen via *Conversions → Export OpenSSH key*); it is read in the
  browser, sent once over the HTTPS session and stored encrypted. You can also paste it.
- **Test connection** checks the values in the form *before saving*; nothing is stored. If the server
  is unknown, a WinSCP-style prompt shows its host key fingerprint; **Accept** fills the fingerprint
  in *Advanced* and repeats the test. Verify the fingerprint on the server first.
- **Server name** (required) is the friendly name shown in lists; the connection uses *Host name / IP address*.
- **Advanced:** host key fingerprint, notes, enabled flag,
  remove saved credentials.
- **Saved secrets** are reused for tests and saves only while host name, port and user stay the same.
  After changing those you must re-enter the password or key, so a stored secret is never sent to
  a different server.

### Adding a Tomcat instance

Set the **Deployment filename** (e.g. `appA.war`): every release deployed to this instance is renamed to it in the target Tomcat, so one uploaded WAR can run as `appA.war` (`/appA`) on one instance and `appB.war` (`/appB`) on another. The stored release is never renamed. Tomcat naming applies: `ROOT.war` → `/`, `shop#admin.war` → `/shop/admin`, `app##2.0.war` → `/app` version 2.0. Two instances deploying into the same directory cannot use the same file or context. The form previews the final path and context. *The upload itself is part of the deployment engine (Milestone 5); this milestone stores and validates the name.*

Pick the deployment method as a card. For **Tomcat Manager**, enter one **Manager URL**
(e.g. `https://tomcat01.example.com:8443/manager/text`; *Use host address* pre-fills it) plus user and
password, and use **Test Manager connection** before saving. For **SSH / SFTP**, the form shows which
host session will be used and the resulting deployment directory.

## Deployments (Milestone 5)

**Before deploying**, give every Tomcat instance an **Environment** (Development, Testing, Staging,
Production) and a **Deployment filename**. Instances without an environment are blocked.

**Wizard** (*Deployments → New deployment*, administrators and operators, or *Deploy this release* on a
release page):

1. **Release:** application and version.
2. **Environment:** one environment per deployment.
3. **Targets:** individual instances, or a whole **server group** (its current members are resolved now
   and the exact instance list is recorded; later group changes never rewrite history). Each target
   can use its own WAR filename (e.g. one release as `appA.war` on A and `appB.war` on B); the
   stored release is never renamed. The resulting context path is shown live.
4. **Strategy:** one at a time (default, mandatory for Production) or parallel (2 to
   `DEPLOYMENT_MAX_PARALLEL_TARGETS`). In both modes no new target starts after a failure.
5. **Preview and preflight:** one row per target with host, method, file and context, current → new
   release, connectivity (a fresh connection test runs when the last success is older than 24 h),
   compatibility, what is on the server now, backup plan, and **blockers** (red, deployment impossible)
   and **warnings** (amber, must be acknowledged). Production also needs release notes of at least 10
   characters and typing the version number to confirm.

The server repeats the whole preflight when you press **Deploy**; the browser preview is never
trusted. Submitting twice (double click, retry) returns the same deployment.

**Execution** runs in backend workers, so the browser can be closed. Each step is written to the
database before it starts:

| Method | Steps |
|---|---|
| SSH / SFTP | inspect directory and free space → **backup**: download the current WAR to `TDL_DEPLOYMENT_BACKUP_DIR` (read-only, SHA-256 recorded) → upload to a hidden temporary file `.<name>.tdm-<id>.part` (Tomcat ignores it) → re-read it and compare the SHA-256 with the release → atomic rename over `<name>.war` (OpenSSH `posix-rename`) → Tomcat auto-deploys → health check |
| Tomcat Manager | `list` → **backup reference**: the previously deployed library release → stream the WAR to `/deploy?path=<context>&update=true` (`version` for `name##v.war`) → check the response text and that `list` shows the context `running` → health check |

**Backup per instance:** on the Tomcat instance form, *Back up the current application before replacing it* is on by default. Switch it off (e.g. for large WARs on test servers) to skip the backup step: nothing is downloaded to `TDL_DEPLOYMENT_BACKUP_DIR`, no rollback point is recorded (a failed health check ends as `FAILED`, not `ROLLBACK_REQUIRED`), the preflight shows *Backup: Off for this instance* with a warning that must be acknowledged, and the log records `BACKUP_SKIPPED`.

**Health check per instance:** on the Tomcat instance form, *Verify application health after each deployment* is on by default. Switch it off when the application has no health endpoint: the health URL and HTTP port then become optional, deployments finish as soon as the WAR is in place (for Tomcat Manager, once Tomcat reports the context `running`), the preflight shows a warning that must be acknowledged, and the log records `HEALTH_CHECK_SKIPPED`.

**Health verification:** the instance health URL (may contain `{context}`, e.g.
`http://host:8080{context}/health`) must return an accepted response `DEPLOYMENT_CONSECUTIVE_SUCCESSES`
times in a row (default 2) within `DEPLOYMENT_STARTUP_TIMEOUT` seconds (default 300); failures during
startup are retried until the timeout. Redirects are not followed. Only then is the target
**Succeeded** and the instance's *current release* updated. Each instance can override this under
*Health policy* on its form: accepted status codes (e.g. `200`, `200,204`, default `200-299`), text the
response must contain (e.g. `UP`), startup timeout (10–1800 s) and consecutive successes (1–10).

**Outcomes:**

| Target state | Meaning |
|---|---|
| Succeeded | Deployed and verified healthy |
| Failed | Did not complete; messages say whether the server was left unchanged |
| Rollback required | The new version is in place but unhealthy, and a restore point exists: use **Roll back…** on the target |
| Rolled back | A rollback restored the previous version and verified it |
| Not started | Skipped after an earlier failure or a cancellation; nothing changed |
| Unknown – reconcile | The connection or the application stopped after the server may have changed. **Never retried automatically.** The instance stays locked until an administrator uses **Inspect & reconcile…**: the tool reads the deployed WAR's SHA-256 (compared with the new version and the backup), what Tomcat Manager reports and one health request, suggests an outcome, and the administrator records *succeeded*, *failed* or *rollback required* with a note. |

A deployment is *Succeeded* only when every target succeeded; otherwise *Partially succeeded*,
*Failed*, *Cancelled* or *Needs reconciliation*. **Cancel** stops starting new targets; a target in
progress finishes safely. Only one unfinished or unreconciled deployment per Tomcat instance is
possible (enforced by a database index). After an application restart, queued deployments that never
started are resumed and interrupted ones are closed truthfully (unchanged targets *Failed*, possibly
changed targets *Unknown*).

**Requirements on the Tomcat side:** for SSH/SFTP, the Host needs `autoDeploy="true"` (the default)
and the SSH account write access to `appBase`; for Tomcat Manager, the `manager-script` role.

| Method | Path | Role |
|---|---|---|
| GET | `/api/deployments/wizard-options`, `/api/deployments?applicationId=&environment=&state=`, `/api/deployments/{id}?afterLogId=` | any signed-in user |
| POST | `/api/deployments/preview`, `/api/deployments` (with `requestId`), `/api/deployments/{id}/cancel` | ADMIN, DEPLOYMENT_OPERATOR |
| POST | `/api/deployments/{id}/targets/{targetId}/inspect`, `/api/deployments/{id}/targets/{targetId}/reconcile` (`outcome`: `SUCCEEDED`, `FAILED`, `ROLLBACK_REQUIRED`) | ADMIN |

## Rollback, backups and health monitoring (Milestone 6)

**Manual rollback.** On a deployment's page, a target that installed a version still in place shows
**⟲ Roll back…** (red when it is *Rollback required*). The rollback check runs first and shows exactly
what will happen:

- **Restore point**: the server WAR downloaded before the deployment replaced it (preferred: the exact
  bytes that ran; matched to a library release by SHA-256 when possible), else the previously deployed
  library release.
- **Integrity**: the restore point's SHA-256 is recomputed now (and again right before the restore).
- **Blockers**: no restore point, failed integrity, a later deployment changed the same context
  (roll back the latest change instead), already rolled back, unknown outcome, instance disabled or
  locked by another deployment, missing credentials or health URL, incompatible restored release,
  failed connection test.
- **Warnings** (to acknowledge): compatibility of a server backup cannot be checked, file name or
  deployment method changed since, health check off, a later failed deployment.
- **Confirmation**: explicit; in Production, type the instance name.

The rollback is its own durable run (`kind = ROLLBACK`, *Rollback #n*) linked to the target it undoes,
with its own progress, logs, lock and restart recovery. It uses the instance's deployment method and
the original file name/context: upload → checksum verification → atomic replace (or Manager deploy) →
health verification. It is **Succeeded** only after the restored version is verified healthy; then
the original target is marked *Rolled back* (a *Rollback required* target) or linked to the rollback
(a succeeded one). If the restored version is unhealthy, the rollback is *Failed*, the original stays
*Rollback required*, and its restore point is kept. The original deployment's logs are never changed;
a line referencing the rollback is added. Backups are never overwritten.

**Backups & Rollback page** (`/backups`): every restore point with instance, context, size, SHA-256,
deployment, integrity status and whether it is the *current rollback point*. Operators can **Verify**
(recompute SHA-256); administrators can **Delete** a server backup with a reason (deployment and audit
log). Deleting the *current rollback point* of a deployment needs an extra confirmation and says what
remains: a rollback then restores the previous library release, if one is known, or is no longer
possible. Retention keeps the
newest `DEPLOYMENT_BACKUP_RETENTION` (default 10) server backups per instance and context, checked every
6 h, and never removes a current rollback point.

**Health monitoring page** (`/health`): every instance's application health (the health URL with the
instance's deployment context), checked every `HEALTH_MONITOR_INTERVAL` (default 5 min) with at most
`HEALTH_MAX_CONCURRENT_CHECKS` requests at a time and a 10 s timeout. One failed check marks an
application *Degraded*; `app.health.unhealthy-after-failures` (default 2) in a row mark it *Unhealthy*;
the next success records a recovery. Shows last check, last success, consecutive failures, failing
since, recent recoveries and rollbacks; each instance has a history page with the last 100 results.
**Check now** (operators) works even when periodic monitoring is off. Instances with a deployment in
progress are skipped. History older than `HEALTH_RETENTION` (default 30 days) is removed. Periodic
monitoring can be switched off per instance (*Include in periodic health monitoring*) or globally
(`HEALTH_MONITORING_ENABLED=false`). This is application-level health; host/SSH and Tomcat Manager
reachability are the connection tests on the instance page.

| Method | Path | Role |
|---|---|---|
| GET | `/api/rollbacks`, `/api/backups`, `/api/health`, `/api/health/instances/{id}/history` | any signed-in user |
| POST | `/api/rollbacks/preview` (`targetId`), `/api/rollbacks` (`requestId`, `targetId`, `confirmed`, `acknowledgeWarnings`, `confirmation`) | ADMIN, DEPLOYMENT_OPERATOR |
| POST | `/api/backups/{id}/verify`, `/api/health/instances/{id}/check` | ADMIN, DEPLOYMENT_OPERATOR |
| POST | `/api/backups/{id}/delete` (`reason`) | ADMIN |

## Users, roles, audit log, notifications and settings (Milestone 7)

### Users and roles

Accounts are stored in the database (`app_users`, BCrypt hashes). Administrators manage them under
**Users** (`/admin/users`): create (with a temporary initial password), change display name, email,
role and status, set a temporary password, unlock. Accounts are disabled rather than deleted so the
audit history stays meaningful. You cannot demote or disable yourself, and the last enabled
administrator cannot be demoted or disabled.

| Role | May |
|---|---|
| Administrator | Everything an operator may, plus users and roles, hosts/instances/groups, credentials and host-key pinning, applications, archive/delete releases, reconcile unknown outcomes, delete backups, settings, audit log |
| Deployment operator | Read everything except administration; run connection and health checks; upload releases (if the *operator upload* setting allows it); preview, start and cancel deployments; roll back; verify backups |
| Viewer | Read dashboards, infrastructure, releases, deployments, health history and notifications. Cannot change anything |

Every user can view *My account* and change their own password (the current password is required).

**First administrator** (only when no account exists):

1. Recommended for servers: start once with `APP_BOOTSTRAP_ADMIN_USERNAME` and a strong
   `APP_BOOTSTRAP_ADMIN_PASSWORD` (from your secret store). The account is created with a
   *temporary* password; sign in, choose a new one, then remove both variables.
2. Without those variables the log prints a **one-time setup token**. Open `/setup`, paste the token
   and create the administrator. The token is random (144 bits), valid until the administrator exists
   or the application restarts, and wrong tokens are audited.
3. `APP_DEV_USERS=true` (local development only) seeds `admin`/`operator`/`viewer` with the same
   passwords.

### Audit log

`/admin/audit` (administrators) lists append-only records with time, user, action, resource,
outcome, sanitized summary, source IP and correlation ID (also returned as `X-Correlation-Id` on
every response and written to the application log). Filter by user, action, resource type,
outcome, text and date range. A database trigger rejects every `UPDATE` and `DELETE` on
`audit_events`; the application has no edit or delete function. Recorded actions include:

| Area | Actions |
|---|---|
| Sign-in | `LOGIN_SUCCESS`, `LOGIN_FAILURE` (incl. lockout), `LOGOUT`, `SETUP_REJECTED` |
| Users | `USER_CREATED`, `USER_UPDATED`, `USER_ROLE_CHANGED`, `USER_PASSWORD_RESET`, `USER_UNLOCKED`, `PASSWORD_CHANGED`, `PASSWORD_CHANGE` (failed) |
| Infrastructure | `HOST_CREATED/UPDATED/DELETED`, `HOST_KEY_CHANGED`, `HOST_KEY_TRUSTED`, `INSTANCE_CREATED/UPDATED/DELETED`, `CREDENTIALS_CHANGED`, `GROUP_CREATED/UPDATED/DELETED`, `GROUP_MEMBER_ADDED/REMOVED` |
| Releases | `APPLICATION_CREATED/UPDATED/DELETED`, `COMPATIBILITY_CHANGED`, `RELEASE_UPLOADED/UPDATED/ARCHIVED/RESTORED/DELETED`, `RELEASE_INTEGRITY_FAILED` |
| Operations | `DEPLOYMENT_REQUESTED` (confirmed, or refused by preflight/confirmation), `DEPLOYMENT_COMPLETED`, `DEPLOYMENT_CANCELLED`, `TARGET_RECONCILED`, `ROLLBACK_REQUESTED`, `ROLLBACK_COMPLETED`, `BACKUP_DELETED` |
| Policy | `SETTING_CHANGED` (old and new value), `CREDENTIALS_REENCRYPTED`, `TEST_EMAIL`, `DEV_USERS_SEEDED` |

### Notifications

The bell in the top bar shows unread notifications (per user) and `/notifications` the full list:
deployment succeeded / partially succeeded / failed / needs reconciliation, rollback succeeded or
failed, an application became **unhealthy** (failed `unhealthy-after-failures` times in a row) and
recovered, and connection or deployment configuration of a host or instance changed. Optionally the
same notifications are emailed: configure SMTP (`SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`,
`SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `NOTIFICATION_EMAIL_FROM`, `APP_BASE_URL` for links),
then enable email, recipients and the minimum level under Settings. Emails are sent in the background
with timeouts; a failure is shown on the notification and logged, and never affects a deployment or
monitoring result. Core operations never need email.

### Settings

`/admin/settings` (administrators) changes runtime policies without a restart (each change audited
with old and new value): operators may upload releases; periodic health monitoring on/off; server
backups kept per context; notification email, recipients and minimum level (with a *test email*
button). It also shows the credential-encryption status and re-encryption (see *Security notes*) and
the effective, non-secret configuration (storage paths, timeouts, limits, network policy, session
and lockout settings, SMTP host).

### Server groups

A group's page (`/infrastructure/groups/{id}`) lists its members with environment, file name and
connectivity; administrators add and remove members there. An instance can be a member of a group
only once (enforced by the database and checked with a clear message).

| Method | Path | Role |
|---|---|---|
| GET | `/api/notifications` | any signed-in user (own unread count) |
| POST | `/api/notifications/read` | any signed-in user (own read state) |
| GET/POST | `/admin/**` (users, audit log, settings) | ADMIN |

## Production deployment considerations

- Run behind HTTPS (reverse proxy or Tomcat/Spring TLS) and set `TDL_SESSION_COOKIE_SECURE=true`. Only set it when
  browsers reach the app over HTTPS: on plain HTTP they discard the session cookie and sign-in cannot work. Keep
  the application on an internal network; it holds credentials for your servers.
- Use a dedicated PostgreSQL account that owns the schema; back up the database, `TDL_ARTIFACT_STORAGE`
  and `TDL_DEPLOYMENT_BACKUP_DIR` together, and `TDL_APP_ENCRYPTION_KEY` separately.
- Provide `TDL_APP_ENCRYPTION_KEY`, database and SMTP passwords through environment variables or a
  secret manager, never in files in Git. Do not set `APP_DEV_USERS`.
- Provision the first administrator with `APP_BOOTSTRAP_ADMIN_*` or the setup token, then create
  personal accounts for every user; do not share accounts.
- Restrict outbound targets with `CONNECTION_ALLOWED_CIDRS` / `CONNECTION_DENIED_CIDRS` where possible.
- Single application instance (V1 design): run one node; PostgreSQL holds all operation state, so a
  restart is safe (interrupted deployments are recovered or marked for reconciliation).
- Build: `./mvnw clean package` produces `target/tomcat-deployment-manager.war`. Deploy it to
  **Apache Tomcat 11** on Java 17+ by copying it into `webapps/`; older Tomcat versions cannot run it
  (Spring Boot 4 needs Servlet 6.1). The file name sets the context path (`ROOT.war` serves it at `/`).
  Tomcat does not read `.env` from the project, so set the environment for the Tomcat service
  (for example in `bin/setenv.sh`), including absolute `TDL_ARTIFACT_STORAGE` and `TDL_DEPLOYMENT_BACKUP_DIR`.
  Copy the file rather than using the Manager web page, whose default upload limit is 50 MB.
  The WAR also runs on its own with `java -jar` under a dedicated service account (e.g. a systemd unit).

## Applications and releases (Milestone 4)

**Applications** (*Applications* in the menu, administrators) hold the requirements every release is
checked against: Servlet API (*Detect from WAR*, `jakarta.servlet`, or `javax.servlet`), minimum and
maximum Java version, supported Tomcat lines (8.5, 9.0, 10.0, 10.1, 11.0; none = any) and free-text
requirements. Contradictions are rejected (e.g. jakarta with Tomcat 9 only).

**Uploading a WAR** (*Release library → Upload WAR*, administrators and, by default, operators):
choose the application, drop the `.war` file, confirm the version (suggested from the file name, e.g.
`customer-portal-2.4.1.war` → `2.4.1`) and add release notes. The browser checks type and size
first; the upload shows progress and can be cancelled. On the server the file is:

1. streamed to `<TDL_ARTIFACT_STORAGE>/tmp` while its **SHA-256** is computed (never held in memory);
2. **validated** as a WAR: a readable ZIP with `WEB-INF/` and no unsafe entry paths (`..`,
   absolute, drive letters), so files that are not WARs or have path-traversal entries are rejected;
3. **inspected**: the highest class-file level in `WEB-INF/classes` gives the Java version, and the
   `web.xml` namespace, class references or bundled libraries give `javax` vs `jakarta`;
4. moved to `sha256/ab/cd/<sha256>.war` and made **read-only**. Identical bytes are stored once:
   uploading the same WAR to the same application is refused with a link to the existing release;
   for another application it creates a new release that shares the stored file.

**Release details** show the SHA-256 (copy button), size, inspection results, release notes, and a
**compatibility check against every Tomcat instance** (*Compatible*, *Incompatible* with reasons, or
*Unknown* when the instance's Tomcat/Java version or the Servlet API is not known; unknown is never
treated as compatible). **Verify now** recomputes the checksum and flags tampered or missing files.
Only the version label and notes can be edited (with conflict detection); the file never changes.
Administrators can **archive** a release (hidden from the library and from future deployments) or
**delete** it; the stored file is removed only when no other release uses it. An application can be
deleted only when it has no releases.

**Storage housekeeping** runs at start-up and every 6 hours (`app.release.housekeeping-interval`): it removes temporary files of interrupted uploads and stored files that no release refers to (both only after an hour, so uploads in progress are never touched), and reports releases whose file is missing. Missing files are never "repaired" automatically; administrators see a red banner listing the affected releases in the library and can run the check on demand (*Run check now*).

Tomcat instances now have optional **Tomcat version** and **Java version** fields; after a Tomcat
Manager connection test the instance form offers the detected values with one click.

| Method | Path | Role |
|---|---|---|
| POST | `/api/releases` (multipart: `file`, `applicationId`, `versionLabel`, `releaseNotes`) | ADMIN, DEPLOYMENT_OPERATOR* |
| GET | `/api/releases?applicationId=&q=&includeArchived=&page=&size=`, `/api/releases/{id}` | any signed-in user |
| POST | `/api/releases/{id}/verify` | ADMIN, DEPLOYMENT_OPERATOR |
| GET | `/api/releases/{id}/compatibility` (requirements and per-instance result) | any signed-in user |
| GET | `/api/applications?q=`, `/api/applications/{id}`, `/api/applications/{id}/releases` | any signed-in user |
| POST, PUT, DELETE | `/api/applications`, `/api/applications/{id}` (PUT needs the last read `version`; stale = 409) | ADMIN |

\* Operators only while `RELEASE_OPERATOR_UPLOAD=true` (default). Upload errors: 400 invalid input,
409 duplicate (with `link`), 413 too large, 422 not a valid WAR.

## Connection testing (Milestone 3)

Every host and Tomcat instance detail page has **Test connection** (ADMIN and DEPLOYMENT_OPERATOR).
Results are persisted with a correlation ID, timestamps, duration and one row per check, and are
shown on the detail pages, the infrastructure list and the dashboard. A success older than 24 h is
shown as *Stale*; an instance counts as deployment-ready only with a recent successful test.

| Target | Checks |
|---|---|
| Host | network policy → SSH connection → host-key verification → authentication → SFTP session |
| SSH/SFTP instance | host checks + deployment directory (exists, is a directory, listable, writable) |
| Tomcat Manager instance | network policy → endpoint/TLS → authentication → `manager-script` permission |

Tests never modify the target: SSH tests run only the fixed commands `id -u` and `id -G`; Manager
tests call only the read-only `serverinfo` and `list` commands.

REST endpoints (JSON, CSRF header required for POST):

| Method | Path | Role |
|---|---|---|
| POST | `/api/connectivity-tests/hosts/{id}` | ADMIN, DEPLOYMENT_OPERATOR |
| POST | `/api/connectivity-tests/instances/{id}` | ADMIN, DEPLOYMENT_OPERATOR |
| GET | `/api/connectivity-tests/{id}`, `/api/connectivity-tests/hosts/{id}?page=&size=`, `/api/connectivity-tests/instances/{id}` | any signed-in user |
| POST | `/api/hosts/{id}/host-key/trust` | ADMIN |
| POST | `/api/connectivity-tests/drafts/host`, `/api/connectivity-tests/drafts/manager` (test unsaved form values; not persisted) | ADMIN |

### SSH host-key verification

Verification is strict and mandatory; `~/.ssh/known_hosts`, `~/.ssh/config` and default identity
files are ignored.

- **No fingerprint pinned (unknown key):** the test fails before any credentials are sent and reports
  the key the server presented. An administrator verifies it out-of-band and either enters it in the
  host form or uses **Trust this host key…**, which requires retyping the exact fingerprint and only
  accepts the key observed in a test within the last hour (`app.connection.host-key-trust-window`).
- **Pinned fingerprint differs (changed key):** reported as `HOST KEY MISMATCH`; the connection is
  aborted and the pinned key is never replaced automatically. Investigate, then an administrator
  updates the fingerprint in the host form (logged as a warning).

Obtain the fingerprint **on the server console**, not over the network you are trying to verify:
```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub    # e.g. 256 SHA256:nThbg6k... root@host (ED25519)
```
Enter the `SHA256:...` part. Only SHA-256 fingerprints are accepted.

### SSH account and SFTP permissions

- Use a dedicated, non-root account (e.g. `deploy`) with key authentication (Ed25519, ECDSA or RSA,
  OpenSSH or PEM format, optional passphrase). Password authentication is supported.
- The account needs the SFTP subsystem and write + execute permission on each instance's deployment
  directory (`CATALINA_BASE/appBase`, normally `webapps`), typically via group ownership:
  ```bash
  sudo groupadd tomcat-deploy; sudo usermod -aG tomcat-deploy deploy
  sudo chgrp tomcat-deploy /opt/tomcat/a/webapps; sudo chmod 2775 /opt/tomcat/a/webapps
  ```
- The write check reads POSIX mode bits and the account's `id -u`/`id -G`; it writes nothing. ACLs
  and SELinux contexts are not visible over SFTP. Accounts that cannot run `id` (SFTP-only, chroot)
  get a *Warning* instead of a verdict.

### Tomcat Manager

Use the text interface (`/manager/text`) with a dedicated user that has **only** the
`manager-script` role (do not grant `manager-gui`; never combine `manager-script` with
`manager-gui` for the same user). In `$CATALINA_BASE/conf/tomcat-users.xml`:
```xml
<role rolename="manager-script"/>
<user username="deployer" password="(generate a long random password)" roles="manager-script"/>
```
By default the Manager app only accepts localhost (`RemoteAddrValve` in
`webapps/manager/META-INF/context.xml`); allow this server's address explicitly, and prefer HTTPS.
Supported: Tomcat 9.0, 10.1 and 11.0 text interface (same `serverinfo`/`list`/`deploy` commands).

### TLS

HTTPS Manager certificates and host names are always verified; there is no "trust all" option. For
internal CAs, either import the CA into the JVM trust store or configure a dedicated store
(it then replaces the JVM default, so include every CA you need):
```properties
TLS_TRUST_STORE=/etc/tomcat-deployment-manager/truststore.p12
TLS_TRUST_STORE_PASSWORD=...
```
Redirects are never followed; configure the final Manager URL.

### Network-target policy (SSRF protection)

Before connecting, the configured host name is resolved and **every** resolved address must be
permitted; the connection then goes to the vetted address.

| Setting | Default |
|---|---|
| `CONNECTION_ALLOW_LOOPBACK` | `false` (refuses 127.0.0.0/8, ::1) |
| `app.connection.target-policy.allow-link-local` | `false` (refuses 169.254.0.0/16 incl. cloud metadata, fe80::/10) |
| `app.connection.target-policy.allow-private` | `true` (RFC 1918, fc00::/7, 100.64/10) |
| `CONNECTION_ALLOWED_CIDRS` / `CONNECTION_DENIED_CIDRS` | empty; e.g. `10.20.0.0/16` |

Wildcard and multicast addresses are always refused. Restrict `CONNECTION_ALLOWED_CIDRS` to your
server networks in production. Limitation: for Tomcat Manager URLs the HTTP client re-resolves the
name (needed for TLS host-name verification), so a DNS answer that changes between check and
connect is not fully prevented.

## Security notes

- **Secrets** (SSH password, private key, passphrase, Manager password) are AES-256-GCM encrypted
  with a fresh 96-bit nonce per value, stored as `v1:` + Base64. They are write-only in forms: never
  rendered, never returned by the API, and redacted from diagnostics and logs. Blank fields keep the
  stored value; explicit "remove" checkboxes delete them; changes are logged without values.
- **Key management:** back up `TDL_APP_ENCRYPTION_KEY` with your database backups but store it
  separately (e.g. a password manager or secret store). Losing it makes stored credentials
  unrecoverable (re-enter them). **Rotation:** generate a new key; set it as `TDL_APP_ENCRYPTION_KEY`
  and the old one as `APP_ENCRYPTION_KEY_PREVIOUS`; restart; on *Settings → Credential encryption*
  check the counts and click *Re-encrypt credentials with the current key* (one transaction,
  audited, refused while any credential is unreadable); then remove `APP_ENCRYPTION_KEY_PREVIOUS` and
  restart. The settings page shows only key **fingerprints** (first 8 hex characters of SHA-256),
  never keys. Never reuse a development key elsewhere.
- **Roles:** see *Users and roles*. Rules are enforced on the server in `SecurityConfig`, not by hiding buttons.
- **Accounts:** BCrypt (cost 12) password hashes; at least 8 characters (`PASSWORD_MIN_LENGTH`), not containing the
  username, not trivially repetitive or common; lockout after `LOGIN_MAX_FAILED_ATTEMPTS` (5) wrong
  passwords for `LOGIN_LOCKOUT_DURATION` (15 min); the sign-in page never says which part was wrong.
  New and reset passwords are temporary and must be changed before anything else. Disabling a user or
  changing their role or password ends their sessions immediately.
- CSRF protection on all state-changing requests (forms and `X-CSRF-TOKEN` for AJAX), session
  fixation protection, session timeout (`SESSION_TIMEOUT`, 30 min), logout invalidates the session and
  deletes the cookie, HttpOnly/SameSite cookies (`TDL_SESSION_COOKIE_SECURE=true` behind HTTPS), a
  Content-Security-Policy, `X-Frame-Options: DENY`, error pages without stack traces or exception
  messages, and only `health`/`info` Actuator endpoints exposed (never `env` or `configprops`).
- **Audit details** contain names, IDs and facts such as "SSH credentials replaced", never secret
  values; summaries additionally pass the credential redactor.
- Concurrent connection tests are capped (`CONNECTION_MAX_CONCURRENT_TESTS`, default 4); excess
  requests get HTTP 429.

## Configuration reference

| Variable | Purpose |
|---|---|
| `TDL_DB_URL`, `TDL_DB_USERNAME`, `TDL_DB_PASSWORD` | PostgreSQL connection |
| `TDL_APP_ENCRYPTION_KEY` | Credential encryption key (required to store credentials) |
| `APP_ENCRYPTION_KEY_PREVIOUS` | Old key during a rotation (decrypt only); remove after re-encryption |
| `APP_BOOTSTRAP_ADMIN_USERNAME`, `APP_BOOTSTRAP_ADMIN_PASSWORD` | First administrator when no account exists (temporary password) |
| `APP_DEV_USERS` | Development only: seed admin/operator/viewer (default `false`) |
| `LOGIN_MAX_FAILED_ATTEMPTS`, `LOGIN_LOCKOUT_DURATION`, `PASSWORD_MIN_LENGTH` | Lockout (default 5, `PT15M`) and minimum password length (8; values below 8 are raised to 8) |
| `SERVER_PORT` | HTTP port (default 8181) |
| `TDL_SESSION_COOKIE_SECURE`, `SESSION_TIMEOUT` | `true` only when browsers use HTTPS (on plain HTTP sign-in cannot work); idle session timeout (default `30m`) |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `SPRING_MAIL_STARTTLS` | Optional SMTP server for notification email |
| `NOTIFICATION_EMAIL_FROM`, `APP_BASE_URL` | Sender address; public URL for links in emails, including any context path (e.g. `https://host/tdm`) |
| `APP_TIME_ZONE` | Display time zone (timestamps are stored in UTC) |
| `CONNECTION_*`, `TLS_*` | See connectivity sections above |
| `TDL_DEPLOYMENT_BACKUP_DIR` | Backups of replaced WARs (default `./var/backups`); private, backed up, outside the app directory in production |
| `DEPLOYMENT_STARTUP_TIMEOUT`, `DEPLOYMENT_CONSECUTIVE_SUCCESSES` | Health verification after deployment (default 300 s, 2) |
| `DEPLOYMENT_MAX_CONCURRENT`, `DEPLOYMENT_MAX_PARALLEL_TARGETS` | Concurrent deployments (default 2) and targets in parallel mode (default 4) |
| `DEPLOYMENT_BACKUP_RETENTION` | Server backups kept per instance and context (default 10; `0` keeps all). The current rollback point is never pruned |
| `HEALTH_MONITORING_ENABLED`, `HEALTH_MONITOR_INTERVAL` | Periodic application health monitoring (default on, every `PT5M`) |
| `HEALTH_MAX_CONCURRENT_CHECKS`, `HEALTH_RETENTION` | Parallel health requests (default 4); history kept (default `P30D`) |
| `TDL_ARTIFACT_STORAGE` | Private WAR storage directory (default `./var/artifacts`). Use a dedicated, backed-up volume outside the application directory; never serve it over HTTP |
| `RELEASE_MAX_UPLOAD_SIZE`, `RELEASE_MAX_REQUEST_SIZE` | Upload limits (default `1GB` / `1100MB`) |
| `RELEASE_OPERATOR_UPLOAD` | Whether deployment operators may upload releases (default `true`) |

Per-instance timeouts (1–300 s) bound each network step; the host test uses
`app.connection.default-timeout-seconds`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| "Unknown host key" | Nothing pinned yet. Verify on the console, then pin it. |
| "HOST KEY MISMATCH" | Server rebuilt or a man-in-the-middle. Investigate before updating the fingerprint. |
| "...not a permitted target (loopback...)" | Target blocked by the network policy; adjust `CONNECTION_*` settings. |
| "Server rejected password authentication" | Wrong credentials, or the server disables that method (`PasswordAuthentication no`). |
| "Private key could not be loaded" | Encrypted key without passphrase, wrong passphrase, or unsupported format. |
| Deployment directory "not writable" | Fix ownership/mode as shown above. |
| Manager 403 | User lacks `manager-script`, or `RemoteAddrValve` blocks this server. |
| "certificate is not trusted" | Add the issuing CA to the trust store. |
| "Stored credential could not be decrypted" | `TDL_APP_ENCRYPTION_KEY` differs from the one used to save it. |

## Milestone status

| Milestone | Status |
|---|---|
| 1 Foundation | Done. Flyway is now actually wired in (Spring Boot 4 needs `spring-boot-starter-flyway`). |
| 2 Infrastructure management | Done. SSH credentials moved from instances to hosts in `V2` (existing values migrated). |
| 3 Remote connectivity | **Done**: verified against an in-process SSH/SFTP server and HTTP/HTTPS Manager emulators. Still to verify against a real Linux host and Tomcat before Milestone 5 deployments. |
| 4 Application registry and release library | **Done**: registry (UI + REST), streaming upload with SHA-256, WAR validation and inspection, content-addressed read-only storage with deduplication, integrity verification, storage housekeeping, search/filter/pagination, compatibility evaluation (UI + REST). 121 automated tests incl. 14 PostgreSQL integration tests. |
| 5 Deployment preflight and engine | **Done**: environments, wizard with server groups and per-target file names, exact preflight preview (blockers/warnings, fresh connection tests, remote inspection, compatibility, disk space, locks, production policy), durable runs/targets/logs/backups, SSH/SFTP and Tomcat Manager deployers with checksum verification and health checks, sequential and bounded parallel execution, cancellation, idempotent submit, restart recovery and reconciliation, live progress. Verified end to end against an embedded SSH/SFTP server and a Tomcat Manager stand-in; **still to verify against a real Tomcat**. |
| 6 Health checks and rollback | **Done**: per-instance health policy (accepted status, expected text, startup timeout, consecutive successes), periodic and manual application monitoring with history, degraded/unhealthy/recovery status and retention, backup management (integrity verification, retention that keeps current rollback points, deletion with reason and confirmation for rollback points), manual rollback as a separate durable run with preflight checks, explicit (Production: typed) confirmation and post-rollback health verification, evidence-based reconciliation of unknown outcomes (incl. *rollback required*), dashboard and monitoring pages. 157 automated tests; rollback, backup, monitoring and reconciliation verified against the embedded SSH/SFTP server and Tomcat stand-in; **still to verify against a real Tomcat**. |
| 7 Production hardening and delivery | **Done**: database accounts with BCrypt, password policy, lockout, forced change of temporary passwords, session registry (role/status/password changes end sessions), first-administrator provisioning without default passwords (bootstrap variables or one-time setup token; development seed only on request), user management; append-only audit log covering sign-in, users, infrastructure, credentials, releases, compatibility, deployments, cancellation, reconciliation, rollback, backups and settings, with viewer and filters; in-application notifications with per-user read state and optional SMTP email; runtime settings; encryption-key rotation with re-encryption; server-group detail and membership management; correlation IDs; error pages without internals; custom sign-in page. 175 automated tests (unit, web-layer, PostgreSQL integration, embedded SSH/SFTP and Tomcat stand-in). |

## Known limitations

- Not yet verified against a real Linux host and real Apache Tomcat in this environment (all remote behaviour is tested against an embedded SSH/SFTP server and a Tomcat Manager stand-in). Run a first deployment on a test server before production use.
- Connection-test results and notifications are kept indefinitely (no retention job); health history has a retention (`HEALTH_RETENTION`).
- No self-service password reset by email (by design an administrator sets a temporary password); no multi-factor authentication or external identity provider (out of V1 scope).
- Audit records are never deleted by the application; archive or partition the `audit_events` table with database tools if it grows large.
- Rollback is manual by design (never automatic). A rollback itself cannot be rolled back; deploy the wanted release again with the wizard.
- SSH/SFTP deployments rely on Tomcat `autoDeploy`; restarting a Tomcat service is not supported (by design, no remote shell commands are run).
- Monitoring runs inside the application; with several application nodes each would check (single-node deployment is assumed in V1).
- Release storage is a local directory; back it up together with the database (metadata without files, or files without metadata, cannot be deployed).

## Non-negotiable design constraints

- Never log passwords, tokens, private keys, passphrases, or secret command output.
- Never silently trust a new SSH host key.
- Never overwrite the canonical release when using a different target filename.
- Never deploy when required preflight checks fail.
- Never retry an ambiguous state-changing operation until target state has been reconciled.
- A successful backup must be verified before replacing a deployed WAR.
- A deployment is successful only after configured health verification passes.
