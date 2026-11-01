package com.tomcatmanager.connectivity;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.putty.PuttyKeyUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Parses a private key held in memory: OpenSSH, PEM (PKCS#1/PKCS#8) or PuTTY/WinSCP {@code .ppk}.
 * Never touches the filesystem or ~/.ssh.
 */
public final class SshKeyLoader {
    private static final NamedResource RESOURCE = NamedResource.ofName("stored-private-key");

    private SshKeyLoader() { }

    public static boolean isPuttyKey(String privateKey) {
        return privateKey != null && privateKey.stripLeading().startsWith("PuTTY-User-Key-File-");
    }

    public static List<KeyPair> load(String privateKey, String passphrase) throws InvalidKeyException {
        if (privateKey == null || privateKey.isBlank()) throw new InvalidKeyException("No private key provided.");
        FilePasswordProvider passwords = (passphrase == null || passphrase.isEmpty()) ? FilePasswordProvider.EMPTY : FilePasswordProvider.of(passphrase);
        List<KeyPair> keys = new ArrayList<>();
        String text = privateKey.strip().replace("\r\n", "\n").concat("\n");
        try {
            if (isPuttyKey(text)) {
                Collection<KeyPair> loaded = PuttyKeyUtils.DEFAULT_INSTANCE.loadKeyPairs(null, RESOURCE, passwords, text);
                if (loaded != null) keys.addAll(loaded);
            } else {
                Iterable<KeyPair> loaded = SecurityUtils.loadKeyPairIdentities(null, RESOURCE,
                        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), passwords);
                if (loaded != null) loaded.forEach(keys::add);
            }
        } catch (Exception e) {
            String hint = (passphrase == null || passphrase.isEmpty())
                    ? "The key may be encrypted and require a passphrase, or its format is not supported."
                    : "Check the passphrase and that the key is in OpenSSH, PEM or PuTTY (.ppk) format.";
            throw new InvalidKeyException("Private key could not be loaded. " + hint);
        }
        if (keys.isEmpty()) throw new InvalidKeyException("No private key found. Choose the key file or paste the full key including its first and last lines.");
        return keys;
    }

    public static class InvalidKeyException extends Exception {
        public InvalidKeyException(String message) { super(message); }
    }
}
