package com.tomcatmanager.infrastructure;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A Linux server. SSH credentials live here because they belong to the server account,
 * not to an individual Tomcat instance. Secret columns hold AES-GCM ciphertext only and
 * are never bound from requests or rendered to pages.
 */
@Entity
@Table(name = "managed_hosts")
public class ManagedHost {
    public enum SshAuthMethod { PASSWORD, PRIVATE_KEY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 255)
    private String hostname;
    @Column(nullable = false)
    private int sshPort = 22;
    @Column(length = 120)
    private String sshUsername;
    @Enumerated(EnumType.STRING) @Column(name = "ssh_auth_method", nullable = false, length = 30)
    private SshAuthMethod sshAuthMethod = SshAuthMethod.PASSWORD;
    @Column(name = "ssh_password_encrypted", columnDefinition = "text")
    private String sshPasswordEncrypted;
    @Column(name = "ssh_private_key_encrypted", columnDefinition = "text")
    private String sshPrivateKeyEncrypted;
    @Column(name = "ssh_key_passphrase_encrypted", columnDefinition = "text")
    private String sshKeyPassphraseEncrypted;
    @Column(length = 1000)
    private String sshHostKeyFingerprint;
    @Column(length = 2000)
    private String notes;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.trim(); }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname == null ? null : hostname.trim(); }
    public int getSshPort() { return sshPort; }
    public void setSshPort(int sshPort) { this.sshPort = sshPort; }
    public String getSshUsername() { return sshUsername; }
    public void setSshUsername(String sshUsername) { this.sshUsername = sshUsername; }
    public SshAuthMethod getSshAuthMethod() { return sshAuthMethod; }
    public void setSshAuthMethod(SshAuthMethod v) { this.sshAuthMethod = v; }
    public String getSshPasswordEncrypted() { return sshPasswordEncrypted; }
    public void setSshPasswordEncrypted(String v) { this.sshPasswordEncrypted = v; }
    public String getSshPrivateKeyEncrypted() { return sshPrivateKeyEncrypted; }
    public void setSshPrivateKeyEncrypted(String v) { this.sshPrivateKeyEncrypted = v; }
    public String getSshKeyPassphraseEncrypted() { return sshKeyPassphraseEncrypted; }
    public void setSshKeyPassphraseEncrypted(String v) { this.sshKeyPassphraseEncrypted = v; }
    public String getSshHostKeyFingerprint() { return sshHostKeyFingerprint; }
    public void setSshHostKeyFingerprint(String value) { this.sshHostKeyFingerprint = value; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean hasSshPassword() { return sshPasswordEncrypted != null; }
    public boolean hasSshPrivateKey() { return sshPrivateKeyEncrypted != null; }
    public boolean hasSshKeyPassphrase() { return sshKeyPassphraseEncrypted != null; }
    public boolean hasPinnedHostKey() { return sshHostKeyFingerprint != null && !sshHostKeyFingerprint.isBlank(); }
}
