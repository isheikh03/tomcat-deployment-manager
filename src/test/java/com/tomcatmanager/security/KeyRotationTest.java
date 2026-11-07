package com.tomcatmanager.security;

import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Encryption-key rotation: the previous key still decrypts, re-encryption moves every credential to the new key. */
class KeyRotationTest {
    private static String newKey() {
        byte[] k = new byte[32]; new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test void thePreviousKeyDecryptsUntilCredentialsAreReencrypted() {
        String oldKey = newKey(), currentKey = newKey();
        String stored = new SecretEncryptionService(oldKey).encrypt("s3cret");

        SecretEncryptionService withoutPrevious = new SecretEncryptionService(currentKey);
        assertThatThrownBy(() -> withoutPrevious.decrypt(stored)).hasMessageContaining("wrong TDL_APP_ENCRYPTION_KEY");

        SecretEncryptionService rotating = new SecretEncryptionService(currentKey, oldKey);
        assertThat(rotating.decrypt(stored)).isEqualTo("s3cret");
        assertThat(rotating.isEncryptedWithCurrentKey(stored)).isFalse();
        String reencrypted = rotating.encrypt(rotating.decrypt(stored));
        assertThat(rotating.isEncryptedWithCurrentKey(reencrypted)).isTrue();
        assertThat(withoutPrevious.decrypt(reencrypted)).isEqualTo("s3cret");
        assertThat(rotating.keyId()).hasSize(8).isNotEqualTo(rotating.previousKeyId());
    }

    @Test void rotationServiceReencryptsHostAndManagerCredentials() {
        String oldKey = newKey(), currentKey = newKey();
        SecretEncryptionService old = new SecretEncryptionService(oldKey);
        SecretEncryptionService rotating = new SecretEncryptionService(currentKey, oldKey);

        ManagedHost host = new ManagedHost();
        host.setSshPasswordEncrypted(old.encrypt("ssh-pass"));
        host.setSshPrivateKeyEncrypted(rotating.encrypt("already-current"));
        TomcatInstance instance = new TomcatInstance();
        instance.setManagerSecretEncrypted(old.encrypt("manager-pass"));

        ManagedHostRepository hosts = mock(ManagedHostRepository.class);
        TomcatInstanceRepository instances = mock(TomcatInstanceRepository.class);
        when(hosts.findAll()).thenReturn(List.of(host));
        when(instances.findAll()).thenReturn(List.of(instance));
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));

        CredentialRotationService service = new CredentialRotationService(hosts, instances, rotating, mock(AuditService.class), tx);
        assertThat(service.status()).isEqualTo(new CredentialRotationService.Status(1, 2, 0));
        assertThat(service.reencryptAll("admin")).isEqualTo(2);
        assertThat(service.status()).isEqualTo(new CredentialRotationService.Status(3, 0, 0));

        SecretEncryptionService newOnly = new SecretEncryptionService(currentKey);
        assertThat(newOnly.decrypt(host.getSshPasswordEncrypted())).isEqualTo("ssh-pass");
        assertThat(newOnly.decrypt(instance.getManagerSecretEncrypted())).isEqualTo("manager-pass");
    }

    @Test void unreadableCredentialsBlockReencryption() {
        String unknown = newKey(), currentKey = newKey();
        ManagedHost host = new ManagedHost();
        host.setSshPasswordEncrypted(new SecretEncryptionService(unknown).encrypt("lost"));
        ManagedHostRepository hosts = mock(ManagedHostRepository.class);
        when(hosts.findAll()).thenReturn(List.of(host));
        TomcatInstanceRepository instances = mock(TomcatInstanceRepository.class);
        CredentialRotationService service = new CredentialRotationService(hosts, instances, new SecretEncryptionService(currentKey),
                mock(AuditService.class), mock(TransactionTemplate.class));
        assertThat(service.status().unreadable()).isEqualTo(1);
        assertThatThrownBy(() -> service.reencryptAll("admin")).hasMessageContaining("cannot be decrypted");
    }
}
