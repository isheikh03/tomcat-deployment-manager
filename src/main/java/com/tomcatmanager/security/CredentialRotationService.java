package com.tomcatmanager.security;

import com.tomcatmanager.audit.AuditService;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Re-encrypts every stored credential (SSH passwords, private keys, passphrases, Tomcat Manager
 * passwords) with the current {@code TDL_APP_ENCRYPTION_KEY}. Used after rotating the key, while the old
 * key is still configured as {@code APP_ENCRYPTION_KEY_PREVIOUS}. Runs in one transaction: either
 * all credentials are re-encrypted or none. Values are never logged or audited.
 */
@Service
public class CredentialRotationService {
    private final ManagedHostRepository hosts;
    private final TomcatInstanceRepository instances;
    private final SecretEncryptionService encryption;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public CredentialRotationService(ManagedHostRepository hosts, TomcatInstanceRepository instances, SecretEncryptionService encryption,
                                     AuditService audit, TransactionTemplate tx) {
        this.hosts = hosts; this.instances = instances; this.encryption = encryption; this.audit = audit; this.tx = tx;
    }

    /** {@code current}: readable with the current key; {@code previous}: needs re-encryption; {@code unreadable}: neither key works. */
    public record Status(int current, int previous, int unreadable) {
        public int total() { return current + previous + unreadable; }
    }

    public Status status() {
        int[] counts = new int[3];
        visit((get, set) -> {
            String v = get.get();
            if (encryption.isEncryptedWithCurrentKey(v)) counts[0]++;
            else if (readable(v)) counts[1]++;
            else counts[2]++;
        });
        return new Status(counts[0], counts[1], counts[2]);
    }

    /** Re-encrypts all credentials that are not yet under the current key. Refuses if any value is unreadable. */
    public int reencryptAll(String actor) {
        if (!encryption.isConfigured()) throw new IllegalStateException("TDL_APP_ENCRYPTION_KEY is not configured.");
        Status before = status();
        if (before.unreadable() > 0)
            throw new IllegalStateException(before.unreadable() + " stored credential(s) cannot be decrypted with the current or previous key. "
                    + "Configure the key they were written with as APP_ENCRYPTION_KEY_PREVIOUS, or re-enter those credentials.");
        Integer changed = tx.execute(s -> {
            int[] n = {0};
            visit((get, set) -> {
                String v = get.get();
                if (!encryption.isEncryptedWithCurrentKey(v)) { set.accept(encryption.encrypt(encryption.decrypt(v))); n[0]++; }
            });
            hosts.flush(); instances.flush();
            return n[0];
        });
        int count = changed == null ? 0 : changed;
        audit.success("CREDENTIALS_REENCRYPTED", "SETTING", "encryption-key", "Re-encrypted " + count + " stored credential(s) with the current key "
                + encryption.keyId() + " (" + before.current() + " were already current).");
        return count;
    }

    private boolean readable(String v) {
        try { encryption.decrypt(v); return true; } catch (IllegalStateException e) { return false; }
    }

    private interface Visitor { void accept(Supplier<String> get, Consumer<String> set); }

    /** Visits every non-empty encrypted field; setters save the owning entity. */
    private void visit(Visitor visitor) {
        for (ManagedHost h : hosts.findAll()) {
            field(visitor, h::getSshPasswordEncrypted, v -> { h.setSshPasswordEncrypted(v); hosts.save(h); });
            field(visitor, h::getSshPrivateKeyEncrypted, v -> { h.setSshPrivateKeyEncrypted(v); hosts.save(h); });
            field(visitor, h::getSshKeyPassphraseEncrypted, v -> { h.setSshKeyPassphraseEncrypted(v); hosts.save(h); });
        }
        for (TomcatInstance i : instances.findAll())
            field(visitor, i::getManagerSecretEncrypted, v -> { i.setManagerSecretEncrypted(v); instances.save(i); });
    }

    private static void field(Visitor visitor, Supplier<String> get, Consumer<String> set) {
        String v = get.get();
        if (v != null && !v.isBlank()) visitor.accept(get, set);
    }
}
