package com.tomcatmanager.users;

import com.tomcatmanager.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** "Remember me" sign-ins end whenever the user's sessions must end, and when the user changes their password. */
class RememberMeRulesTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PersistentTokenRepository tokens = mock(PersistentTokenRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private UserAccountService service(Duration validity) {
        UserAccountService s = new UserAccountService(users, encoder, mock(AuditService.class), new SessionRegistryImpl(),
                new SecurityProperties(false, new SecurityProperties.Bootstrap(null, null), 5, Duration.ofMinutes(15), 8, validity),
                mock(TransactionTemplate.class));
        s.setRememberMeTokens(tokens);
        return s;
    }

    private AppUser account(long id, String username, String password, Role role) {
        AppUser u = new AppUser(username, encoder.encode(password), role, "test");
        ReflectionTestUtils.setField(u, "id", id);
        when(users.findById(id)).thenReturn(Optional.of(u));
        when(users.findByUsernameIgnoreCase(username)).thenReturn(Optional.of(u));
        return u;
    }

    @Test void endingSessionsAlsoForgetsRememberedBrowsers() {
        service(Duration.ofDays(14)).expireSessions("ann");
        verify(tokens).removeUserTokens("ann");
    }

    @Test void aTemporaryPasswordFromAnAdministratorForgetsRememberedBrowsers() {
        account(7L, "ann", "Old-password-1", Role.DEPLOYMENT_OPERATOR);
        account(1L, "root", "Root-password-1", Role.SUPER_ADMIN);
        service(Duration.ofDays(14)).resetPassword(7L, "Temp-password-9", "root");
        verify(tokens).removeUserTokens("ann");
    }

    @Test void changingYourOwnPasswordForgetsRememberedBrowsers() {
        account(7L, "ann", "Old-password-1", Role.VIEWER);
        UserAccountService s = service(Duration.ofDays(14));
        s.changeOwnPassword("ann", "Old-password-1", "New-password-2", "New-password-2");
        verify(tokens).removeUserTokens("ann");

        reset(tokens);
        try { s.changeOwnPassword("ann", "wrong", "Other-password-3", "Other-password-3"); } catch (IllegalArgumentException expected) { }
        verify(tokens, never()).removeUserTokens(anyString());
    }

    @Test void theOptionCanBeSwitchedOff() {
        assertThat(service(Duration.ofDays(14)).rememberMeDays()).isEqualTo(14);
        assertThat(service(Duration.ZERO).rememberMeDays()).isZero();
    }
}
