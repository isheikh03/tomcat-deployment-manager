package com.tomcatmanager.users;

import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.ServerGroup;
import com.tomcatmanager.infrastructure.ServerGroupRepository;
import com.tomcatmanager.infrastructure.ServerGroupService;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.notifications.Notification;
import com.tomcatmanager.notifications.NotificationService;
import com.tomcatmanager.settings.SettingsService;
import com.tomcatmanager.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Database-backed accounts end to end through the real security filter chain: sign-in, lockout,
 * forced password change, role enforcement, CSRF, first-administrator setup, and the append-only audit trail.
 */
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {"app.security.dev-users=false",
        "app.security.bootstrap-admin.username=", "app.security.bootstrap-admin.password="})
class AccountSecurityIntegrationTest extends DatabaseIntegrationTest {
    private static final String PASSWORD = "Correct-horse-42";

    @Autowired MockMvc mvc;
    @Autowired UserAccountService accounts;
    @Autowired AppUserRepository users;
    @Autowired UserBootstrap bootstrap;
    @Autowired SettingsService settings;
    @Autowired NotificationService notifications;
    @Autowired AuditService audit;
    @Autowired ServerGroupService groupService;
    @Autowired ServerGroupRepository groups;
    @Autowired ManagedHostRepository hosts;
    @Autowired TomcatInstanceRepository instances;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE app_users, audit_events, notifications, notification_read_markers, app_settings, server_group_instances, "
                + "tomcat_instances, server_groups, managed_hosts RESTART IDENTITY CASCADE");
        settings.refresh();
    }

    private AppUser create(String username, Role role, boolean temporaryPassword) {
        UserForm f = new UserForm();
        f.setUsername(username); f.setRole(role); f.setPassword(PASSWORD); f.setPasswordConfirmation(PASSWORD);
        AppUser u = accounts.create(f, "it");
        if (!temporaryPassword) jdbc.update("update app_users set must_change_password = false where id = ?", u.getId());
        return users.findById(u.getId()).orElseThrow();
    }

    private long audits(String action) { return jdbc.queryForObject("select count(*) from audit_events where action = ?", Long.class, action); }

    @Test void signInIsAuditedAndRepeatedWrongPasswordsLockTheAccount() throws Exception {
        AppUser alice = create("alice", Role.DEPLOYMENT_OPERATOR, false);
        mvc.perform(formLogin("/login").user("alice").password(PASSWORD)).andExpect(authenticated().withUsername("alice").withRoles("DEPLOYMENT_OPERATOR"));
        assertThat(audits("LOGIN_SUCCESS")).isEqualTo(1L);

        for (int i = 0; i < 5; i++) mvc.perform(formLogin("/login").user("alice").password("wrong-password")).andExpect(unauthenticated());
        assertThat(users.findById(alice.getId()).orElseThrow().isLocked()).isTrue();
        mvc.perform(formLogin("/login").user("alice").password(PASSWORD)).andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));
        assertThat(audits("LOGIN_FAILURE")).isEqualTo(6L);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action = 'LOGIN_FAILURE' and summary like '%now locked%'", Long.class)).isEqualTo(1L);

        accounts.unlock(alice.getId(), "admin");
        mvc.perform(formLogin("/login").user("alice").password(PASSWORD)).andExpect(authenticated());
        mvc.perform(formLogin("/login").user("nobody").password(PASSWORD)).andExpect(unauthenticated());
        assertThat(jdbc.queryForObject("select summary from audit_events where actor = 'nobody'", String.class)).contains("unknown username");
    }

    @Test void aTemporaryPasswordMustBeChangedBeforeAnythingElse() throws Exception {
        create("bob", Role.VIEWER, true);
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin("/login").user("bob").password(PASSWORD))
                .andExpect(redirectedUrl("/account/password?required")).andReturn().getRequest().getSession();
        mvc.perform(get("/").session(session)).andExpect(redirectedUrl("/account/password?required"));
        mvc.perform(get("/api/deployments").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/account/password").session(session)).andExpect(status().isOk());

        mvc.perform(post("/account/password").session(session).with(csrf()).param("currentPassword", PASSWORD)
                .param("newPassword", "bob").param("passwordConfirmation", "bob")).andExpect(status().isOk()); // too short: form shown again
        mvc.perform(post("/account/password").session(session).with(csrf()).param("currentPassword", PASSWORD)
                .param("newPassword", "Another-good-passphrase").param("passwordConfirmation", "Another-good-passphrase"))
                .andExpect(redirectedUrl("/account"));
        mvc.perform(get("/").session(session)).andExpect(status().isOk());
        assertThat(audits("PASSWORD_CHANGED")).isEqualTo(1L);
        assertThat(users.findByUsernameIgnoreCase("bob").orElseThrow().isMustChangePassword()).isFalse();
    }

    @Test void administrationIsForAdministratorsOnlyAndSignInNeedsCsrf() throws Exception {
        create("root", Role.SUPER_ADMIN, false);
        create("vera", Role.VIEWER, false);
        for (String page : new String[] {"/admin/users", "/admin/audit", "/admin/settings", "/admin/users/new"})
            mvc.perform(get(page).with(user("vera").roles("VIEWER"))).andExpect(status().isForbidden());
        for (String page : new String[] {"/admin/users", "/admin/audit", "/admin/settings", "/admin/users/1/edit", "/notifications", "/account", "/"})
            mvc.perform(get(page).with(user("root").roles("ADMIN", "DEPLOYMENT_OPERATOR"))).andExpect(status().isOk());
        mvc.perform(post("/admin/users/save").with(csrf()).with(user("vera").roles("VIEWER")).param("username", "x")).andExpect(status().isForbidden());
        // Without a CSRF token the sign-in is refused: the user is sent back to the login page to retry (not signed in).
        mvc.perform(post("/login").param("username", "root").param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?retry"));
    }

    @Test void theLastSuperUserCannotBeDemotedAndNobodyChangesTheirOwnRole() {
        AppUser root = create("root", Role.SUPER_ADMIN, false);
        UserForm demote = UserForm.from(root);
        demote.setRole(Role.VIEWER);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(demote, "form");
        accounts.validate(demote, "root", errors);
        assertThat(errors.getFieldErrors("role")).extracting(e -> e.getDefaultMessage())
                .anyMatch(m -> m.contains("last enabled super user")).anyMatch(m -> m.contains("your own role"));

        create("second", Role.SUPER_ADMIN, false);
        errors = new BeanPropertyBindingResult(demote, "form");
        accounts.validate(demote, "second", errors);
        assertThat(errors.hasErrors()).isFalse();
        accounts.update(demote, "second");
        assertThat(audits("USER_ROLE_CHANGED")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select summary from audit_events where action = 'USER_ROLE_CHANGED'", String.class)).contains("Super user → Viewer");
    }

    @Test void theFirstAdministratorIsCreatedOnlyWithTheOneTimeSetupToken() throws Exception {
        bootstrap.run(null); // no account exists: a setup token is issued
        assertThat(bootstrap.setupOpen()).isTrue();
        mvc.perform(get("/login")).andExpect(redirectedUrl("/setup"));
        assertThatThrownBy(() -> bootstrap.completeSetup("wrong", "chief", PASSWORD, PASSWORD)).hasMessageContaining("setup token is not correct");
        assertThat(audits("SETUP_REJECTED")).isEqualTo(1L);

        bootstrap.completeSetup(bootstrap.currentSetupToken(), "chief", PASSWORD, PASSWORD);
        assertThat(bootstrap.setupOpen()).isFalse();
        assertThat(users.findByUsernameIgnoreCase("chief").orElseThrow().getRole()).isEqualTo(Role.SUPER_ADMIN);
        mvc.perform(get("/setup")).andExpect(redirectedUrl("/login"));
        mvc.perform(formLogin("/login").user("chief").password(PASSWORD)).andExpect(authenticated().withRoles("SUPER_ADMIN", "ADMIN", "DEPLOYMENT_OPERATOR"));
    }

    @Test void auditRecordsCannotBeChangedOrDeleted() {
        audit.record("it", "TEST_EVENT", "TEST", 1, com.tomcatmanager.audit.AuditEvent.Outcome.SUCCESS, "password=hunter2 should be redacted");
        assertThat(jdbc.queryForObject("select summary from audit_events where action = 'TEST_EVENT'", String.class)).doesNotContain("hunter2");
        assertThatThrownBy(() -> jdbc.update("update audit_events set summary = 'changed'")).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_events")).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test void settingsAreValidatedAuditedAndApplyImmediately() {
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.BACKUP_RETENTION, "-1"), "it")).hasMessageContaining("between 0");
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.EMAIL_RECIPIENTS, "not-an-address"), "it")).hasMessageContaining("Not a valid email");
        assertThat(settings.update(Map.of(SettingsService.BACKUP_RETENTION, "3", SettingsService.OPERATOR_UPLOAD, "false"), "it")).isEqualTo(2);
        assertThat(settings.backupRetentionPerContext()).isEqualTo(3);
        assertThat(settings.operatorUploadEnabled()).isFalse();
        assertThat(audits("SETTING_CHANGED")).isEqualTo(2L);
    }

    @Test void releaseUploadPolicyIsEnforcedAtRuntime() throws Exception {
        settings.update(Map.of(SettingsService.OPERATOR_UPLOAD, "false"), "it");
        mvc.perform(get("/releases/upload").with(user("op").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        settings.update(Map.of(SettingsService.OPERATOR_UPLOAD, "true"), "it");
        mvc.perform(get("/releases/upload").with(user("op").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isOk());
    }

    @Test void quickDeploymentPageFollowsTheUploadPolicy() throws Exception {
        settings.update(Map.of(SettingsService.OPERATOR_UPLOAD, "false"), "it");
        mvc.perform(get("/deployments/quick").with(user("op").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        settings.update(Map.of(SettingsService.OPERATOR_UPLOAD, "true"), "it");
        mvc.perform(get("/deployments/quick").with(user("op").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isOk());
        mvc.perform(get("/deployments/quick").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/deployments/quick").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
    }

    @Test void notificationsAreReadPerUser() {
        notifications.publish(Notification.Level.WARNING, Notification.Category.HEALTH, "a", "first", null);
        notifications.publish(Notification.Level.SUCCESS, Notification.Category.DEPLOYMENT, "b", "second", "/deployments/1");
        assertThat(notifications.unreadCount("alice")).isEqualTo(2);
        notifications.markAllRead("alice");
        assertThat(notifications.unreadCount("alice")).isZero();
        assertThat(notifications.unreadCount("bob")).isEqualTo(2);
    }

    @Test void aSingleNotificationCanBeMarkedRead() throws Exception {
        for (String title : List.of("a", "b", "c")) notifications.publish(Notification.Level.INFO, Notification.Category.HEALTH, title, title, null);
        List<Long> ids = notifications.recent().stream().map(Notification::getId).sorted().toList();
        long first = ids.get(0), second = ids.get(1), third = ids.get(2);

        notifications.markRead("alice", second);
        notifications.markRead("alice", second); // repeating it changes nothing
        assertThat(notifications.unreadCount("alice")).isEqualTo(2);
        assertThat(notifications.readIds("alice", ids)).containsExactly(second);
        assertThat(notifications.unreadCount("bob")).isEqualTo(3);

        // Through the API, as the bell and the page call it; viewers may do it too.
        mvc.perform(post("/api/notifications/" + third + "/read").with(csrf()).with(user("alice").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(1));
        mvc.perform(post("/api/notifications/999999/read").with(csrf()).with(user("alice").roles("VIEWER"))).andExpect(status().isNotFound());
        mvc.perform(get("/notifications").with(user("alice").roles("VIEWER"))).andExpect(status().isOk());

        notifications.markAllRead("alice");
        assertThat(notifications.unreadCount("alice")).isZero();
        assertThat(notifications.readIds("alice", ids)).containsExactlyInAnyOrder(first, second, third);
        assertThat(jdbc.queryForObject("select count(*) from notification_reads where username = 'alice'", Long.class))
                .as("rows covered by the marker are removed").isZero();
    }

    @Test void anInstanceJoinsAGroupOnlyOnce() {
        ManagedHost host = new ManagedHost();
        host.setName("h"); host.setHostname("10.0.0.9"); host.setSshUsername("deploy");
        host = hosts.save(host);
        TomcatInstance i = new TomcatInstance();
        i.setName("t1"); i.setHost(host); i.setCatalinaBase("/opt/tomcat"); i.setAppBase("webapps");
        i = instances.save(i);
        ServerGroup g = new ServerGroup(); g.setName("Europe");
        g = groups.save(g);
        groupService.addMember(g.getId(), i.getId());
        long groupId = g.getId(), instanceId = i.getId();
        assertThatThrownBy(() -> groupService.addMember(groupId, instanceId)).hasMessageContaining("already a member");
        assertThat(groupService.members(groupId)).extracting(TomcatInstance::getName).containsExactly("t1");
        groupService.removeMember(groupId, instanceId);
        assertThat(groupService.members(groupId)).isEmpty();
    }
}
