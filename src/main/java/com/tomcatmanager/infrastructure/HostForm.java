package com.tomcatmanager.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Host create/edit form. Secret fields are write-only: they are never populated from the
 * database, and a blank value on edit keeps the stored secret.
 */
public class HostForm {
    private Long id;
    /** Team of a new host; a host stays in its team. */
    private Long teamId;
    @NotBlank(message = "Enter a server name.") @Size(max = 120) private String name;
    @NotBlank @Size(max = 255) private String hostname;
    @Min(1) @Max(65535) private int sshPort = 22;
    @NotBlank @Size(max = 64) private String sshUsername;
    @NotNull private ManagedHost.SshAuthMethod sshAuthMethod = ManagedHost.SshAuthMethod.PASSWORD;
    @Size(max = 1024) private String sshPassword;
    @Size(max = 16384) private String sshPrivateKey;
    @Size(max = 1024) private String sshKeyPassphrase;
    private boolean clearStoredSecrets;
    @Size(max = 100) private String sshHostKeyFingerprint;
    @Size(max = 2000) private String notes;
    private boolean enabled = true;

    // Read-only indicators for the edit page; never bound from requests into secrets.
    private boolean passwordStored;
    private boolean privateKeyStored;
    private boolean passphraseStored;

    public static HostForm from(ManagedHost h) {
        HostForm f = new HostForm();
        f.teamId = h.getTeamId();
        f.id = h.getId(); f.name = h.getName(); f.hostname = h.getHostname(); f.sshPort = h.getSshPort();
        f.sshUsername = h.getSshUsername(); f.sshAuthMethod = h.getSshAuthMethod(); f.sshHostKeyFingerprint = h.getSshHostKeyFingerprint();
        f.notes = h.getNotes(); f.enabled = h.isEnabled();
        f.passwordStored = h.hasSshPassword(); f.privateKeyStored = h.hasSshPrivateKey(); f.passphraseStored = h.hasSshKeyPassphrase();
        return f;
    }

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getTeamId() { return teamId; } public void setTeamId(Long v) { teamId = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getHostname() { return hostname; } public void setHostname(String v) { hostname = v; }
    public int getSshPort() { return sshPort; } public void setSshPort(int v) { sshPort = v; }
    public String getSshUsername() { return sshUsername; } public void setSshUsername(String v) { sshUsername = v; }
    public ManagedHost.SshAuthMethod getSshAuthMethod() { return sshAuthMethod; } public void setSshAuthMethod(ManagedHost.SshAuthMethod v) { sshAuthMethod = v; }
    public String getSshPassword() { return sshPassword; } public void setSshPassword(String v) { sshPassword = v; }
    public String getSshPrivateKey() { return sshPrivateKey; } public void setSshPrivateKey(String v) { sshPrivateKey = v; }
    public String getSshKeyPassphrase() { return sshKeyPassphrase; } public void setSshKeyPassphrase(String v) { sshKeyPassphrase = v; }
    public boolean isClearStoredSecrets() { return clearStoredSecrets; } public void setClearStoredSecrets(boolean v) { clearStoredSecrets = v; }
    public String getSshHostKeyFingerprint() { return sshHostKeyFingerprint; } public void setSshHostKeyFingerprint(String v) { sshHostKeyFingerprint = v; }
    public String getNotes() { return notes; } public void setNotes(String v) { notes = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
    public boolean isPasswordStored() { return passwordStored; }
    public boolean isPrivateKeyStored() { return privateKeyStored; }
    public boolean isPassphraseStored() { return passphraseStored; }
    void setStoredIndicators(ManagedHost h) { passwordStored = h.hasSshPassword(); privateKeyStored = h.hasSshPrivateKey(); passphraseStored = h.hasSshKeyPassphrase(); }
}
