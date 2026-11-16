package com.tomcatmanager.users;

import com.tomcatmanager.audit.AuditEvent;
import com.tomcatmanager.audit.AuditService;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.Errors;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * User accounts: creation, role and status changes, password changes and resets, lockout. Every
 * change is audited (never with password values). Changing a user's role, status or password
 * ends their open sessions. At least one enabled administrator always remains.
 */
@Service
public class UserAccountService {
    private static final Set<String> COMMON_PASSWORDS = Set.of("password", "passwort", "123456789012", "qwertyuiopas", "administrator",
            "changeme1234", "letmein12345", "welcome12345", "tomcatmanager", "password1234",
            // Common 8- to 11-character choices, now that 8 is the minimum.
            "12345678", "123456789", "1234567890", "87654321", "qwertyui", "qwerty123", "1qaz2wsx", "abcd1234", "abc12345",
            "password1", "password12", "passw0rd", "p@ssw0rd", "admin123", "admin1234", "changeme", "letmein1", "welcome1",
            "iloveyou", "sunshine", "football", "baseball", "trustno1", "tomcat123", "tomcat1234");

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final SessionRegistry sessions;
    private final SecurityProperties properties;
    private final TransactionTemplate tx;

    public UserAccountService(AppUserRepository users, PasswordEncoder encoder, AuditService audit, SessionRegistry sessions,
                              SecurityProperties properties, TransactionTemplate tx) {
        this.users = users; this.encoder = encoder; this.audit = audit; this.sessions = sessions; this.properties = properties; this.tx = tx;
    }

    public List<AppUser> all() { return users.findAllByOrderByUsernameAsc(); }
    public AppUser find(long id) { return users.findById(id).orElseThrow(() -> new NoSuchElementException("User " + id + " not found.")); }
    public Optional<AppUser> findByUsername(String username) { return users.findByUsernameIgnoreCase(username == null ? "" : username.strip()); }
    public boolean anyUserExists() { return users.count() > 0; }

    /** Password rules; returns a message or null when acceptable. */
    /** The configured minimum password length; never below 8, whatever is configured. */
    public int minPasswordLength() { return Math.max(8, properties.minPasswordLength()); }

    public String passwordProblem(String username, String password) {
        int min = minPasswordLength();
        if (password == null || password.length() < min) return "Use at least " + min + " characters.";
        if (password.length() > 128) return "Use at most 128 characters.";
        if (password.isBlank() || password.chars().distinct().count() < 4) return "Use a less predictable password (more different characters).";
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && !username.isBlank() && lower.contains(username.toLowerCase(Locale.ROOT))) return "The password must not contain the username.";
        if (COMMON_PASSWORDS.contains(lower)) return "This password is too common.";
        return null;
    }

    /** Whether the signed-in user is a super user. */
    public boolean isSuperUser(String actor) {
        return findByUsername(actor).map(u -> u.getRole() == Role.SUPER_ADMIN).orElse(false);
    }

    /** Roles the actor may give to a user: only a super user can create or promote super users. */
    public List<Role> assignableRoles(String actor) {
        return isSuperUser(actor) ? List.of(Role.values()) : List.of(Role.ADMIN, Role.DEPLOYMENT_OPERATOR, Role.VIEWER);
    }

    /** Only a super user may change, reset or unlock a super user's account. */
    private void requireMayManage(String actor, AppUser target) {
        if (target.getRole() == Role.SUPER_ADMIN && !isSuperUser(actor))
            throw new IllegalArgumentException("Only a super user can change a super user's account.");
    }

    public void validate(UserForm f, String actor, Errors errors) {
        boolean actorIsSuper = isSuperUser(actor);
        if (f.getRole() == Role.SUPER_ADMIN && !actorIsSuper)
            errors.rejectValue("role", "superOnly", "Only a super user can create a super user or give this role.");
        if (f.isNew()) {
            if (f.getUsername() == null || !f.getUsername().matches("^[a-z0-9][a-z0-9._-]{1,59}$"))
                errors.rejectValue("username", "invalid", "2–60 characters: lowercase letters, digits, dot, dash or underscore; start with a letter or digit.");
            else if (users.existsByUsernameIgnoreCase(f.getUsername()))
                errors.rejectValue("username", "duplicate", "This username is already taken.");
            String problem = passwordProblem(f.getUsername(), f.getPassword());
            if (problem != null) errors.rejectValue("password", "weak", problem);
            else if (!f.getPassword().equals(f.getPasswordConfirmation())) errors.rejectValue("passwordConfirmation", "mismatch", "The passwords do not match.");
            return;
        }
        AppUser existing = find(f.getId());
        boolean self = existing.getUsername().equalsIgnoreCase(actor);
        if (existing.getRole() == Role.SUPER_ADMIN && !actorIsSuper)
            errors.rejectValue("role", "superOnly", "Only a super user can change a super user's account.");
        if (self && f.getRole() != existing.getRole()) errors.rejectValue("role", "self", "You cannot change your own role. Ask another super user or administrator.");
        if (self && !f.isEnabled()) errors.rejectValue("enabled", "self", "You cannot disable your own account.");
        if (existing.getRole() == Role.SUPER_ADMIN && existing.isEnabled() && (f.getRole() != Role.SUPER_ADMIN || !f.isEnabled())
                && users.countByRoleAndEnabledTrue(Role.SUPER_ADMIN) <= 1)
            errors.rejectValue("role", "lastSuperUser", "This is the last enabled super user. Create or enable another super user first.");
    }

    public AppUser create(UserForm f, String actor) {
        AppUser user = new AppUser(f.getUsername(), encoder.encode(f.getPassword()), f.getRole(), actor);
        user.setDisplayName(f.getDisplayName()); user.setEmail(f.getEmail()); user.setEnabled(f.isEnabled());
        user.setPasswordHash(user.getPasswordHash(), true); // the initial password is temporary
        AppUser saved = users.save(user);
        audit.success("USER_CREATED", "USER", saved.getUsername(), "Created user " + saved.getUsername() + " with role " + saved.getRole().label()
                + (saved.isEnabled() ? "" : " (disabled)") + "; the initial password must be changed at first sign-in.");
        return saved;
    }

    public AppUser update(UserForm f, String actor) {
        AppUser user = find(f.getId());
        if (user.getVersion() != f.getVersion()) throw new ObjectOptimisticLockingFailureException(AppUser.class, f.getId());
        Role oldRole = user.getRole(); boolean wasEnabled = user.isEnabled();
        String oldEmail = user.getEmail(), oldName = user.getDisplayName();
        user.setDisplayName(f.getDisplayName()); user.setEmail(f.getEmail()); user.setRole(f.getRole()); user.setEnabled(f.isEnabled());
        AppUser saved = users.save(user);
        StringBuilder changes = new StringBuilder();
        if (oldRole != saved.getRole()) changes.append(" role ").append(oldRole.label()).append(" → ").append(saved.getRole().label()).append(';');
        if (wasEnabled != saved.isEnabled()) changes.append(saved.isEnabled() ? " enabled;" : " disabled;");
        if (!java.util.Objects.equals(oldEmail, saved.getEmail())) changes.append(" email changed;");
        if (!java.util.Objects.equals(oldName, saved.getDisplayName())) changes.append(" display name changed;");
        audit.success(oldRole != saved.getRole() ? "USER_ROLE_CHANGED" : "USER_UPDATED", "USER", saved.getUsername(),
                "Updated user " + saved.getUsername() + ":" + (changes.isEmpty() ? " no changes." : changes));
        if (oldRole != saved.getRole() || wasEnabled != saved.isEnabled()) expireSessions(saved.getUsername());
        return saved;
    }

    /** Administrator sets a temporary password; the user must change it at the next sign-in. */
    public void resetPassword(long id, String temporaryPassword, String actor) {
        AppUser user = find(id);
        requireMayManage(actor, user);
        String problem = passwordProblem(user.getUsername(), temporaryPassword);
        if (problem != null) throw new IllegalArgumentException(problem);
        user.setPasswordHash(encoder.encode(temporaryPassword), true);
        users.save(user);
        audit.success("USER_PASSWORD_RESET", "USER", user.getUsername(), actor + " set a temporary password for " + user.getUsername()
                + "; it must be changed at the next sign-in.");
        expireSessions(user.getUsername());
    }

    public void unlock(long id, String actor) {
        AppUser user = find(id);
        requireMayManage(actor, user);
        user.unlock();
        users.save(user);
        audit.success("USER_UNLOCKED", "USER", user.getUsername(), "Unlocked user " + user.getUsername() + ".");
    }

    /** A signed-in user changes their own password; the current one must be confirmed. */
    public void changeOwnPassword(String username, String current, String next, String confirmation) {
        AppUser user = findByUsername(username).orElseThrow(() -> new NoSuchElementException("Your account no longer exists."));
        if (current == null || !encoder.matches(current, user.getPasswordHash())) {
            audit.failure("PASSWORD_CHANGE", "USER", user.getUsername(), "Password change rejected: the current password was wrong.");
            throw new IllegalArgumentException("The current password is not correct.");
        }
        String problem = passwordProblem(user.getUsername(), next);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (!next.equals(confirmation)) throw new IllegalArgumentException("The new passwords do not match.");
        if (encoder.matches(next, user.getPasswordHash())) throw new IllegalArgumentException("Choose a password different from the current one.");
        user.setPasswordHash(encoder.encode(next), false);
        users.save(user);
        audit.success("PASSWORD_CHANGED", "USER", user.getUsername(), user.getUsername() + " changed their password.");
    }

    // ---- sign-in bookkeeping (called by the authentication event listener) ----

    void loginSucceeded(String username) {
        retrying(() -> findByUsername(username).ifPresent(u -> { u.recordSuccessfulLogin(); users.save(u); }));
        audit.record(username, "LOGIN_SUCCESS", "USER", username, AuditEvent.Outcome.SUCCESS, "Signed in.");
    }

    void loginFailed(String attemptedUsername, String reason) {
        String name = attemptedUsername == null ? "" : attemptedUsername.strip();
        boolean[] locked = {false};
        Optional<AppUser> known = findByUsername(name);
        known.ifPresent(u -> retrying(() -> {
            AppUser fresh = find(u.getId());
            boolean wasLocked = fresh.isLocked();
            if (!wasLocked && "bad-credentials".equals(reason)) fresh.recordFailedLogin(Math.max(1, properties.maxFailedLogins()), properties.lockoutDuration());
            users.save(fresh);
            locked[0] = !wasLocked && fresh.isLocked();
        }));
        String actor = name.isEmpty() ? "(empty)" : name.length() > 60 ? name.substring(0, 60) : name;
        audit.record(actor, "LOGIN_FAILURE", "USER", known.isPresent() ? actor : null, AuditEvent.Outcome.FAILURE,
                "Sign-in failed (" + ("bad-credentials".equals(reason) ? (known.isEmpty() ? "unknown username" : "wrong password") : reason) + ")."
                        + (locked[0] ? " The account is now locked for " + properties.lockoutDuration().toMinutes() + " minutes." : ""));
    }

    private void retrying(Runnable r) {
        for (int i = 1; ; i++) {
            try { tx.executeWithoutResult(s -> r.run()); return; }
            catch (ObjectOptimisticLockingFailureException e) { if (i >= 3) throw e; }
        }
    }

    /** Ends every open session of a user (role, status or password changed). */
    public void expireSessions(String username) {
        for (Object principal : sessions.getAllPrincipals()) {
            String name = principal instanceof UserDetails d ? d.getUsername() : String.valueOf(principal);
            if (!name.equalsIgnoreCase(username)) continue;
            for (SessionInformation info : sessions.getAllSessions(principal, false)) info.expireNow();
        }
    }

    // ---- provisioning ----

    /** Creates an account without the interactive password rules (bootstrap and development seed only). */
    AppUser provision(String username, String rawPassword, Role role, boolean mustChange, String actor) {
        AppUser user = new AppUser(username.strip().toLowerCase(Locale.ROOT), encoder.encode(rawPassword), role, actor);
        user.setPasswordHash(user.getPasswordHash(), mustChange);
        return users.save(user);
    }
}
