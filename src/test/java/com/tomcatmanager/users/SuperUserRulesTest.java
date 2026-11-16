package com.tomcatmanager.users;

import com.tomcatmanager.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Super users: the top role, which only another super user can give, change, reset or unlock. */
class SuperUserRulesTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserAccountService service = new UserAccountService(users, new BCryptPasswordEncoder(4), mock(AuditService.class),
            new SessionRegistryImpl(), new SecurityProperties(false, new SecurityProperties.Bootstrap(null, null), 5, Duration.ofMinutes(15), 8),
            mock(TransactionTemplate.class));

    private AppUser account(long id, String username, Role role) {
        AppUser u = new AppUser(username, "hash", role, "test");
        ReflectionTestUtils.setField(u, "id", id);
        when(users.findById(id)).thenReturn(Optional.of(u));
        when(users.findByUsernameIgnoreCase(username)).thenReturn(Optional.of(u));
        return u;
    }

    private static List<String> roleErrors(BeanPropertyBindingResult errors) {
        return errors.getFieldErrors("role").stream().map(FieldError::getDefaultMessage).toList();
    }

    @Test void aSuperUserHoldsEveryPermissionOfTheRolesBelow() {
        assertThat(Role.SUPER_ADMIN.authorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_DEPLOYMENT_OPERATOR");
        assertThat(Role.ADMIN.authorities()).extracting(GrantedAuthority::getAuthority).doesNotContain("ROLE_SUPER_ADMIN");
        assertThat(Role.SUPER_ADMIN.administers()).isTrue();
        assertThat(Role.DEPLOYMENT_OPERATOR.administers()).isFalse();
    }

    @Test void onlySuperUsersCanGiveTheSuperUserRole() {
        account(1, "boss", Role.SUPER_ADMIN);
        account(2, "ann", Role.ADMIN);
        assertThat(service.assignableRoles("boss")).contains(Role.SUPER_ADMIN);
        assertThat(service.assignableRoles("ann")).containsExactly(Role.ADMIN, Role.DEPLOYMENT_OPERATOR, Role.VIEWER);

        UserForm eve = new UserForm();
        eve.setUsername("eve"); eve.setPassword("Tr7#kq2Zx"); eve.setPasswordConfirmation("Tr7#kq2Zx"); eve.setRole(Role.SUPER_ADMIN);
        when(users.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        BeanPropertyBindingResult byAdmin = new BeanPropertyBindingResult(eve, "form");
        service.validate(eve, "ann", byAdmin);
        assertThat(roleErrors(byAdmin)).anyMatch(m -> m.contains("Only a super user"));

        BeanPropertyBindingResult bySuper = new BeanPropertyBindingResult(eve, "form");
        service.validate(eve, "boss", bySuper);
        assertThat(bySuper.hasErrors()).isFalse();
    }

    @Test void anAdministratorCannotChangeResetOrUnlockASuperUser() {
        AppUser boss = account(1, "boss", Role.SUPER_ADMIN);
        account(2, "ann", Role.ADMIN);
        when(users.countByRoleAndEnabledTrue(Role.SUPER_ADMIN)).thenReturn(2L);
        UserForm rename = UserForm.from(boss);
        rename.setDisplayName("The boss");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(rename, "form");
        service.validate(rename, "ann", errors);
        assertThat(roleErrors(errors)).anyMatch(m -> m.contains("Only a super user can change a super user"));

        assertThatThrownBy(() -> service.resetPassword(1L, "Another-pass-42", "ann")).hasMessageContaining("Only a super user");
        assertThatThrownBy(() -> service.unlock(1L, "ann")).hasMessageContaining("Only a super user");
    }

    @Test void theLastSuperUserStaysAndNobodyChangesTheirOwnRole() {
        AppUser boss = account(1, "boss", Role.SUPER_ADMIN);
        account(3, "other", Role.SUPER_ADMIN);
        when(users.countByRoleAndEnabledTrue(Role.SUPER_ADMIN)).thenReturn(1L);
        UserForm demote = UserForm.from(boss);
        demote.setRole(Role.ADMIN);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(demote, "form");
        service.validate(demote, "boss", errors);
        assertThat(roleErrors(errors)).anyMatch(m -> m.contains("your own role")).anyMatch(m -> m.contains("last enabled super user"));

        when(users.countByRoleAndEnabledTrue(Role.SUPER_ADMIN)).thenReturn(2L);
        BeanPropertyBindingResult byOther = new BeanPropertyBindingResult(demote, "form");
        service.validate(demote, "other", byOther);
        assertThat(byOther.hasErrors()).isFalse();
    }
}
