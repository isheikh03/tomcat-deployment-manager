package com.tomcatmanager.users;

import com.tomcatmanager.audit.AuditEvent;
import com.tomcatmanager.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * First-administrator provisioning when no account exists, in this order:
 * <ol>
 *   <li>{@code APP_BOOTSTRAP_ADMIN_USERNAME}/{@code _PASSWORD}: created with a temporary password that must be changed;</li>
 *   <li>{@code APP_DEV_USERS=true} (development only): admin/operator/viewer with well-known passwords;</li>
 *   <li>otherwise a one-time setup token is written to the log; {@code /setup} accepts it to create the first administrator.</li>
 * </ol>
 * No account with a hard-coded password exists unless development mode is explicitly enabled.
 */
@Component
@Order(0)
public class UserBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final UserAccountService accounts;
    private final SecurityProperties properties;
    private final AuditService audit;
    private volatile String setupToken;

    public UserBootstrap(UserAccountService accounts, SecurityProperties properties, AuditService audit) {
        this.accounts = accounts; this.properties = properties; this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.anyUserExists()) return;
        SecurityProperties.Bootstrap admin = properties.bootstrapAdmin();
        if (admin != null && admin.configured()) {
            accounts.provision(admin.username(), admin.password(), Role.ADMIN, true, AuditService.SYSTEM);
            audit.record(AuditService.SYSTEM, "USER_CREATED", "USER", admin.username(), AuditEvent.Outcome.SUCCESS,
                    "First administrator " + admin.username() + " created from APP_BOOTSTRAP_ADMIN_*; the password must be changed at first sign-in.");
            log.warn("Created the first administrator '{}' from APP_BOOTSTRAP_ADMIN_*. Sign in and change the password; then remove these variables.", admin.username());
            return;
        }
        if (properties.devUsers()) {
            accounts.provision("admin", "admin", Role.ADMIN, false, AuditService.SYSTEM);
            accounts.provision("operator", "operator", Role.DEPLOYMENT_OPERATOR, false, AuditService.SYSTEM);
            accounts.provision("viewer", "viewer", Role.VIEWER, false, AuditService.SYSTEM);
            audit.record(AuditService.SYSTEM, "DEV_USERS_SEEDED", "USER", null, AuditEvent.Outcome.SUCCESS,
                    "Development accounts admin, operator and viewer were created (APP_DEV_USERS=true).");
            log.warn("DEVELOPMENT MODE: created admin/admin, operator/operator, viewer/viewer (APP_DEV_USERS=true). Never enable this on a shared or production server.");
            return;
        }
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        setupToken = HexFormat.of().formatHex(bytes);
        log.warn("""

                ================================================================
                 No user account exists yet. Create the first administrator at
                   /setup   with this one-time setup token:
                   {}
                 The token is valid until the first administrator is created
                 or the application restarts.
                ================================================================""", setupToken);
    }

    /** For tests: the token written to the log. */
    String currentSetupToken() { return setupToken; }

    /** True while the setup page may be used (no account yet and a token was issued). */
    public boolean setupOpen() { return setupToken != null && !accounts.anyUserExists(); }

    public boolean tokenMatches(String token) {
        String expected = setupToken;
        if (expected == null || token == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.strip().getBytes(StandardCharsets.UTF_8));
    }

    /** Creates the first administrator through the setup page and closes setup. */
    public synchronized AppUser completeSetup(String token, String username, String password, String confirmation) {
        if (!setupOpen()) throw new IllegalStateException("Setup is closed: an account already exists.");
        if (!tokenMatches(token)) {
            audit.record("(setup)", "SETUP_REJECTED", "USER", null, AuditEvent.Outcome.DENIED, "First-administrator setup rejected: wrong setup token.");
            throw new IllegalArgumentException("The setup token is not correct. Copy it from the application log.");
        }
        String name = username == null ? "" : username.strip().toLowerCase();
        if (!name.matches("^[a-z0-9][a-z0-9._-]{1,59}$"))
            throw new IllegalArgumentException("Username: 2–60 characters, lowercase letters, digits, dot, dash or underscore.");
        String problem = accounts.passwordProblem(name, password);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (!password.equals(confirmation)) throw new IllegalArgumentException("The passwords do not match.");
        AppUser user = accounts.provision(name, password, Role.ADMIN, false, "(setup)");
        setupToken = null;
        audit.record(name, "USER_CREATED", "USER", name, AuditEvent.Outcome.SUCCESS, "First administrator " + name + " created through the setup page.");
        log.info("First administrator '{}' created through the setup page; setup is closed.", name);
        return user;
    }
}
