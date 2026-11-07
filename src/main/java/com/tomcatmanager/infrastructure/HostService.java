package com.tomcatmanager.infrastructure;

import com.tomcatmanager.connectivity.HostKeyFingerprints;
import com.tomcatmanager.connectivity.SshKeyLoader;
import com.tomcatmanager.security.SecretEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.Errors;

import java.util.NoSuchElementException;

/** Host configuration rules, including write-only secret handling and host-key pinning. */
@Service
public class HostService {
    private static final Logger log = LoggerFactory.getLogger(HostService.class);

    private final ManagedHostRepository hosts;
    private final TomcatInstanceRepository instances;
    private final SecretEncryptionService encryption;

    public HostService(ManagedHostRepository hosts, TomcatInstanceRepository instances, SecretEncryptionService encryption) {
        this.hosts = hosts; this.instances = instances; this.encryption = encryption;
    }

    public HostForm formFor(long id) { return HostForm.from(find(id)); }

    public ManagedHost find(long id) { return hosts.findById(id).orElseThrow(() -> new NoSuchElementException("Host " + id + " not found.")); }

    /** Validates beyond bean validation; errors are added to {@code errors}. */
    public void validate(HostForm form, Errors errors) {
        ManagedHost existing = form.getId() == null ? null : find(form.getId());
        if (form.getId() != null) form.setStoredIndicators(existing);

        if (form.getHostname() != null && !form.getHostname().isBlank() && !InfrastructureValidation.isValidHostname(form.getHostname()))
            errors.rejectValue("hostname", "invalid", "Enter a valid DNS name or IP address.");
        if (form.getName() != null && !form.getName().isBlank()) {
            hosts.findByNameIgnoreCase(form.getName().trim())
                    .filter(other -> !other.getId().equals(form.getId()))
                    .ifPresent(other -> errors.rejectValue("name", "duplicate", "Another host already uses this name."));
        }
        if (form.getSshUsername() != null && !form.getSshUsername().isBlank() && !InfrastructureValidation.isValidPosixUsername(form.getSshUsername().trim()))
            errors.rejectValue("sshUsername", "invalid", "Enter a valid Linux account name.");
        if (form.getSshHostKeyFingerprint() != null && !form.getSshHostKeyFingerprint().isBlank()
                && !HostKeyFingerprints.isValid(form.getSshHostKeyFingerprint()))
            errors.rejectValue("sshHostKeyFingerprint", "invalid", "Use the OpenSSH SHA-256 format: SHA256: followed by 43 Base64 characters.");

        boolean newSecretProvided = hasText(form.getSshPassword()) || hasText(form.getSshPrivateKey()) || hasText(form.getSshKeyPassphrase());
        if (form.isClearStoredSecrets() && newSecretProvided)
            errors.reject("secrets", "Either remove the stored secrets or provide new ones, not both.");

        // Never let a stored secret follow the host to a different address without re-entry.
        boolean storedSecret = existing != null && (existing.hasSshPassword() || existing.hasSshPrivateKey());
        boolean targetChanged = existing != null && form.getHostname() != null
                && (!existing.getHostname().equalsIgnoreCase(form.getHostname().trim()) || existing.getSshPort() != form.getSshPort());
        boolean methodChanged = existing != null && existing.getSshAuthMethod() != form.getSshAuthMethod();
        if (storedSecret && targetChanged && !methodChanged && !newSecretProvided && !form.isClearStoredSecrets()) {
            String field = form.getSshAuthMethod() == ManagedHost.SshAuthMethod.PRIVATE_KEY ? "sshPrivateKey" : "sshPassword";
            errors.rejectValue(field, "reenter", "The host name or port changed: enter the credential again so the stored one is not sent to a different server.");
        }

        if (newSecretProvided && !encryption.isConfigured())
            errors.reject("encryption", "TDL_APP_ENCRYPTION_KEY is not configured, so credentials cannot be stored.");

        if (form.getSshAuthMethod() == ManagedHost.SshAuthMethod.PRIVATE_KEY) {
            if (hasText(form.getSshPassword()))
                errors.rejectValue("sshPassword", "unused", "A password is not used with private-key authentication.");
            if (hasText(form.getSshPrivateKey())) {
                try {
                    SshKeyLoader.load(form.getSshPrivateKey(), form.getSshKeyPassphrase());
                } catch (SshKeyLoader.InvalidKeyException e) {
                    errors.rejectValue("sshPrivateKey", "invalid", e.getMessage());
                }
            } else if (hasText(form.getSshKeyPassphrase())) {
                errors.rejectValue("sshKeyPassphrase", "keyRequired", "Paste the private key together with its passphrase.");
            }
        } else if (hasText(form.getSshPrivateKey()) || hasText(form.getSshKeyPassphrase())) {
            errors.rejectValue("sshPrivateKey", "unused", "A private key is not used with password authentication.");
        }
    }

    @Transactional
    public ManagedHost save(HostForm form) { return saveWithChanges(form).host(); }

    /** What a save changed, for the audit log and notifications. Never contains secret values. */
    public record HostChange(ManagedHost host, boolean created, boolean connectionChanged, boolean hostKeyChanged, String credentials) { }

    public HostChange saveWithChanges(HostForm form) {
        ManagedHost host = form.getId() == null ? new ManagedHost() : find(form.getId());
        boolean created = form.getId() == null;
        String connectionBefore = host.getHostname() + "|" + host.getSshPort() + "|" + host.getSshUsername() + "|" + host.isEnabled();
        String previousFingerprint = host.getSshHostKeyFingerprint();
        host.setName(form.getName());
        host.setHostname(form.getHostname());
        host.setSshPort(form.getSshPort());
        host.setSshUsername(form.getSshUsername() == null ? null : form.getSshUsername().trim());
        host.setSshHostKeyFingerprint(HostKeyFingerprints.normalize(form.getSshHostKeyFingerprint()));
        host.setNotes(hasText(form.getNotes()) ? form.getNotes().trim() : null);
        host.setEnabled(form.isEnabled());

        ManagedHost.SshAuthMethod previousMethod = host.getSshAuthMethod();
        host.setSshAuthMethod(form.getSshAuthMethod());
        boolean secretsChanged = false;
        if (form.isClearStoredSecrets()) {
            host.setSshPasswordEncrypted(null); host.setSshPrivateKeyEncrypted(null); host.setSshKeyPassphraseEncrypted(null);
            secretsChanged = true;
        } else if (form.getSshAuthMethod() == ManagedHost.SshAuthMethod.PASSWORD) {
            if (hasText(form.getSshPassword())) { host.setSshPasswordEncrypted(encryption.encrypt(form.getSshPassword())); secretsChanged = true; }
            if (host.hasSshPrivateKey() || host.hasSshKeyPassphrase()) {
                host.setSshPrivateKeyEncrypted(null); host.setSshKeyPassphraseEncrypted(null); secretsChanged = true;
            }
        } else {
            if (hasText(form.getSshPrivateKey())) {
                host.setSshPrivateKeyEncrypted(encryption.encrypt(form.getSshPrivateKey().strip()));
                // A new key replaces any passphrase belonging to the old one.
                host.setSshKeyPassphraseEncrypted(hasText(form.getSshKeyPassphrase()) ? encryption.encrypt(form.getSshKeyPassphrase()) : null);
                secretsChanged = true;
            }
            if (host.hasSshPassword()) { host.setSshPasswordEncrypted(null); secretsChanged = true; }
        }

        ManagedHost saved = hosts.save(host);
        boolean keyChanged = !HostKeyFingerprints.matches(previousFingerprint, saved.getSshHostKeyFingerprint())
                && !(previousFingerprint == null && saved.getSshHostKeyFingerprint() == null);
        if (form.getId() != null && keyChanged)
            log.warn("Pinned SSH host key for host {} changed from {} to {}", saved.getId(), previousFingerprint, saved.getSshHostKeyFingerprint());
        String credentials = null;
        if (secretsChanged || previousMethod != saved.getSshAuthMethod()) {
            credentials = form.isClearStoredSecrets() ? "removed" : "replaced (" + saved.getSshAuthMethod() + ")";
            log.info("SSH credentials for host {} were {} (method {})", saved.getId(), form.isClearStoredSecrets() ? "removed" : "updated", saved.getSshAuthMethod());
        }
        String connectionAfter = saved.getHostname() + "|" + saved.getSshPort() + "|" + saved.getSshUsername() + "|" + saved.isEnabled();
        return new HostChange(saved, created, !created && (!connectionAfter.equals(connectionBefore) || keyChanged || credentials != null), keyChanged, credentials);
    }

    @Transactional
    public void delete(long id) {
        if (instances.existsByHostId(id))
            throw new IllegalStateException("This host still has Tomcat instances. Remove or reassign them first.");
        hosts.delete(find(id));
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }
}
