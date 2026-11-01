package com.tomcatmanager.connectivity;

import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.security.SecretEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** "Test before saving" must never send a stored secret to a host other than the one it was saved for. */
class DraftConnectionTestServiceTest {
    private final SecretEncryptionService encryption = new SecretEncryptionService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private ManagedHostRepository hosts;
    private ConnectionTestResultRepository results;
    private SshConnectivityClient ssh;
    private ConnectionTestService service;

    @BeforeEach void setUp() {
        hosts = mock(ManagedHostRepository.class);
        results = mock(ConnectionTestResultRepository.class);
        ssh = mock(SshConnectivityClient.class);
        ConnectivityProperties props = new ConnectivityProperties(5, 2, 4, Duration.ofHours(24), Duration.ofHours(1),
                new ConnectivityProperties.TargetPolicy(true, false, true, List.of(), List.of()), new ConnectivityProperties.Tls(null, null, "PKCS12"));
        service = new ConnectionTestService(hosts, mock(TomcatInstanceRepository.class), results, encryption,
                new NetworkTargetPolicy(props), ssh, mock(TomcatManagerClient.class), props);

        ManagedHost stored = new ManagedHost();
        stored.setId(7L); stored.setName("prod"); stored.setHostname("127.0.0.1"); stored.setSshPort(22); stored.setSshUsername("deploy");
        stored.setSshAuthMethod(ManagedHost.SshAuthMethod.PASSWORD);
        stored.setSshPasswordEncrypted(encryption.encrypt("stored-secret"));
        when(hosts.findById(7L)).thenReturn(Optional.of(stored));
    }

    private SshConnectivityClient.SshTarget runDraft(String hostname, String password) {
        service.testDraftHost(new DraftConnectionRequests.DraftHost(7L, hostname, 22, "deploy", ManagedHost.SshAuthMethod.PASSWORD,
                password, null, null, null), "admin");
        ArgumentCaptor<SshConnectivityClient.SshTarget> target = ArgumentCaptor.forClass(SshConnectivityClient.SshTarget.class);
        verify(ssh).probe(target.capture(), isNull(), any());
        return target.getValue();
    }

    @Test void unchangedTargetReusesStoredSecret() {
        SshConnectivityClient.SshTarget target = runDraft("127.0.0.1", null);
        assertThat(target.credentials().password()).isEqualTo("stored-secret");
        assertThat(target.credentialProblem()).isNull();
    }

    @Test void changedHostDoesNotReceiveStoredSecret() {
        SshConnectivityClient.SshTarget target = runDraft("127.0.0.2", null);
        assertThat(target.credentials().password()).isNull();
        assertThat(target.credentialProblem()).contains("enter the password again");
    }

    @Test void newlyTypedSecretIsUsedAndNothingIsPersisted() {
        SshConnectivityClient.SshTarget target = runDraft("127.0.0.2", "typed-now");
        assertThat(target.credentials().password()).isEqualTo("typed-now");
        verifyNoInteractions(results);
    }
}
