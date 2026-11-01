package com.tomcatmanager.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM at-rest encryption. The key must be supplied outside the database.
 *
 * <p>Stored format: {@code v1:} + Base64(12-byte nonce || ciphertext || 16-byte tag), with a fresh
 * random nonce per encryption. Values without a version prefix are the original unversioned layout
 * and are still readable.
 *
 * <p>Key rotation: set the new key as {@code APP_ENCRYPTION_KEY} and the old one as
 * {@code APP_ENCRYPTION_KEY_PREVIOUS}. Values are then decrypted with whichever key authenticates
 * them (GCM rejects the wrong key), and the administrator re-encrypts all stored credentials with
 * the current key ({@link CredentialRotationService}); afterwards the previous key can be removed.
 */
@Service
public class SecretEncryptionService {
    private static final Logger log = LoggerFactory.getLogger(SecretEncryptionService.class);
    private static final String V1_PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;
    private final byte[] previousKey;
    private final String keyProblem;
    private final SecureRandom random = new SecureRandom();

    public SecretEncryptionService(String configuredKey) { this(configuredKey, ""); }

    @Autowired
    public SecretEncryptionService(@Value("${app.security.encryption-key:}") String configuredKey,
                                   @Value("${app.security.encryption-key-previous:}") String previousConfiguredKey) {
        byte[] parsed = null; String problem = null;
        if (configuredKey == null || configuredKey.isBlank()) {
            problem = "APP_ENCRYPTION_KEY is not configured; credentials cannot be saved or used.";
        } else {
            parsed = parse(configuredKey);
            if (parsed == null) problem = "APP_ENCRYPTION_KEY must be Base64 for exactly 32 random bytes.";
        }
        byte[] previous = previousConfiguredKey == null || previousConfiguredKey.isBlank() ? null : parse(previousConfiguredKey);
        if (previousConfiguredKey != null && !previousConfiguredKey.isBlank() && previous == null)
            log.warn("APP_ENCRYPTION_KEY_PREVIOUS is not Base64 for 32 bytes and is ignored.");
        this.key = parsed; this.previousKey = previous; this.keyProblem = problem;
        if (problem != null) log.warn("{} Generate one with: openssl rand -base64 32", problem);
        if (previous != null) log.warn("APP_ENCRYPTION_KEY_PREVIOUS is set: credentials encrypted with the previous key remain readable. "
                + "Re-encrypt them under Settings, then remove the previous key.");
    }

    private static byte[] parse(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            return bytes.length == 32 ? bytes : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isConfigured() { return key != null; }

    public boolean hasPreviousKey() { return previousKey != null; }

    /** A non-secret identifier of the current key (first 8 hex characters of its SHA-256), to confirm which key is active. */
    public String keyId() { return key == null ? null : id(key); }

    public String previousKeyId() { return previousKey == null ? null : id(previousKey); }

    private static String id(byte[] k) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(k)).substring(0, 8); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length); System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return V1_PREFIX + Base64.getEncoder().encodeToString(result);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Could not encrypt credential.", e); }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return null;
        requireKey();
        byte[] all = decode(value);
        try {
            return decryptWith(key, all);
        } catch (AEADBadTagException e) {
            if (previousKey != null) {
                try { return decryptWith(previousKey, all); }
                catch (AEADBadTagException ignored) { /* fall through */ }
                catch (GeneralSecurityException ex) { throw new IllegalStateException("Could not decrypt stored credential.", ex); }
            }
            throw new IllegalStateException("Stored credential could not be decrypted: wrong APP_ENCRYPTION_KEY or tampered data.");
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Could not decrypt stored credential.", e); }
    }

    /** True when the value authenticates with the current key (false: previous key or unreadable). */
    public boolean isEncryptedWithCurrentKey(String value) {
        if (value == null || value.isBlank() || key == null) return false;
        try { decryptWith(key, decode(value)); return true; }
        catch (GeneralSecurityException | IllegalStateException e) { return false; }
    }

    private static byte[] decode(String value) {
        String payload = value.startsWith(V1_PREFIX) ? value.substring(V1_PREFIX.length()) : value;
        byte[] all;
        try { all = Base64.getDecoder().decode(payload); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("Stored credential is corrupt (invalid encoding)."); }
        if (all.length <= NONCE_BYTES + TAG_BITS / 8) throw new IllegalStateException("Stored credential is corrupt (too short).");
        return all;
    }

    private static String decryptWith(byte[] k, byte[] all) throws GeneralSecurityException {
        byte[] nonce = Arrays.copyOfRange(all, 0, NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(all, NONCE_BYTES, all.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private void requireKey() { if (key == null) throw new IllegalStateException(keyProblem); }
}
