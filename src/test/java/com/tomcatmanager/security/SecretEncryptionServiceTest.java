package com.tomcatmanager.security;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretEncryptionServiceTest {
    private static String randomKey() {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test void roundTripUsesVersionedFormatAndFreshNonce() {
        SecretEncryptionService service = new SecretEncryptionService(randomKey());
        String a = service.encrypt("s3cret-value"), b = service.encrypt("s3cret-value");
        assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("s3cret");
        assertThat(service.decrypt(a)).isEqualTo("s3cret-value");
    }

    @Test void legacyUnversionedCiphertextIsStillReadable() {
        SecretEncryptionService service = new SecretEncryptionService(randomKey());
        String legacy = service.encrypt("old").substring(3);
        assertThat(service.decrypt(legacy)).isEqualTo("old");
    }

    @Test void wrongKeyFails() {
        String ciphertext = new SecretEncryptionService(randomKey()).encrypt("value");
        assertThatThrownBy(() -> new SecretEncryptionService(randomKey()).decrypt(ciphertext))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("wrong APP_ENCRYPTION_KEY or tampered");
    }

    @Test void alteredCiphertextFails() {
        SecretEncryptionService service = new SecretEncryptionService(randomKey());
        byte[] raw = Base64.getDecoder().decode(service.encrypt("value").substring(3));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> service.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test void missingOrInvalidKeyIsReportedNotSilentlyIgnored() {
        SecretEncryptionService missing = new SecretEncryptionService("");
        assertThat(missing.isConfigured()).isFalse();
        assertThatThrownBy(() -> missing.encrypt("x")).isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured");
        assertThat(new SecretEncryptionService(Base64.getEncoder().encodeToString(new byte[16])).isConfigured()).isFalse();
        assertThat(new SecretEncryptionService("not base64!").isConfigured()).isFalse();
    }

    @Test void blankValuesAreNotEncrypted() {
        SecretEncryptionService service = new SecretEncryptionService(randomKey());
        assertThat(service.encrypt(" ")).isNull();
        assertThat(service.decrypt(null)).isNull();
    }
}
