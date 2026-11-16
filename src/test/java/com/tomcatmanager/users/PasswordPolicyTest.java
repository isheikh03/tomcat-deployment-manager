package com.tomcatmanager.users;

import com.tomcatmanager.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PasswordPolicyTest {
    private final UserAccountService service = new UserAccountService(mock(AppUserRepository.class), new BCryptPasswordEncoder(4), mock(AuditService.class),
            new SessionRegistryImpl(), new SecurityProperties(false, new SecurityProperties.Bootstrap(null, null), 5, Duration.ofMinutes(15), 8, Duration.ofDays(14)),
            mock(TransactionTemplate.class));

    @Test void weakPasswordsAreRejectedWithAClearReason() {
        assertThat(service.passwordProblem("alice", "short")).contains("at least 8");
        assertThat(service.passwordProblem("alice", "aaaaaaaaaaaaaa")).contains("less predictable");
        assertThat(service.passwordProblem("alice", "my-alice-password-1")).contains("username");
        assertThat(service.passwordProblem("alice", "password1234")).contains("too common");
        assertThat(service.passwordProblem("alice", "x".repeat(129))).contains("at most 128");
    }

    @Test void eightCharactersIsTheMinimum() {
        assertThat(service.minPasswordLength()).isEqualTo(8);
        assertThat(service.passwordProblem("alice", "Tr7#kq2")).contains("at least 8");
        assertThat(service.passwordProblem("alice", "Tr7#kq2Z")).isNull();
        assertThat(service.passwordProblem("alice", "12345678")).contains("too common");
        assertThat(service.passwordProblem("alice", "Admin123")).contains("too common");
    }

    @Test void aLongVariedPassphraseIsAccepted() {
        assertThat(service.passwordProblem("alice", "Correct-horse-42")).isNull();
        assertThat(service.passwordProblem("alice", "blue cactus river lamp")).isNull();
    }

    @Test void roleAuthoritiesIncludeOperatorRightsForAdministrators() {
        assertThat(Role.ADMIN.authorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN", "ROLE_DEPLOYMENT_OPERATOR");
        assertThat(Role.VIEWER.authorities()).extracting(Object::toString).containsExactly("ROLE_VIEWER");
    }
}
