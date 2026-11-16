-- "Remember me" sign-ins (PostgreSQL 9.5 compatible). The layout is Spring Security's persistent token store:
-- the browser keeps only a random series and token, renewed on every use; the password is never stored.
-- Rows are deleted at sign-out, when the password is changed or reset, and when the account is disabled or its role changes.
CREATE TABLE persistent_logins (
    username   VARCHAR(64)  NOT NULL,
    series     VARCHAR(64)  PRIMARY KEY,
    token      VARCHAR(64)  NOT NULL,
    last_used  TIMESTAMP    NOT NULL
);
CREATE INDEX ix_persistent_logins_username ON persistent_logins (username);
