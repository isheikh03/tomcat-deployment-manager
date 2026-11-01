package com.tomcatmanager.infrastructure;

import com.tomcatmanager.security.SecretEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HostServiceTest {
    private final SecretEncryptionService encryption = new SecretEncryptionService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private HostService service;

    @BeforeEach void setUp() {
        ManagedHostRepository hosts = mock(ManagedHostRepository.class);
        ManagedHost stored = new ManagedHost();
        stored.setId(3L); stored.setName("prod-01"); stored.setHostname("10.0.0.5"); stored.setSshPort(22); stored.setSshUsername("deploy");
        stored.setSshAuthMethod(ManagedHost.SshAuthMethod.PASSWORD); stored.setSshPasswordEncrypted(encryption.encrypt("pw"));
        when(hosts.findById(3L)).thenReturn(Optional.of(stored));
        when(hosts.findByNameIgnoreCase(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        service = new HostService(hosts, mock(TomcatInstanceRepository.class), encryption);
    }

    private HostForm edit(String hostname, String password) {
        HostForm form = new HostForm();
        form.setId(3L); form.setName("prod-01"); form.setHostname(hostname); form.setSshPort(22); form.setSshUsername("deploy");
        form.setSshAuthMethod(ManagedHost.SshAuthMethod.PASSWORD); form.setSshPassword(password);
        return form;
    }

    @Test void changingHostRequiresReenteringTheSecret() {
        HostForm form = edit("10.0.0.99", null);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "host");
        service.validate(form, errors);
        assertThat(errors.getFieldError("sshPassword")).isNotNull();
        assertThat(errors.getFieldError("sshPassword").getDefaultMessage()).contains("enter the credential again");
    }

    @Test void unchangedHostKeepsSecretAndServerName() {
        HostForm form = edit("10.0.0.5", null);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "host");
        service.validate(form, errors);
        assertThat(errors.hasErrors()).isFalse();
        assertThat(form.getName()).isEqualTo("prod-01");
    }

    @Test void changedHostWithNewSecretIsAccepted() {
        HostForm form = edit("10.0.0.99", "new-pw");
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "host");
        service.validate(form, errors);
        assertThat(errors.hasErrors()).isFalse();
    }

    @Test void serverNameIsRequired() {
        HostForm form = edit("10.0.0.5", null);
        form.setName(" ");
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validateProperty(form, "name")).extracting(v -> v.getMessage()).containsExactly("Enter a server name.");
        }
    }
}
