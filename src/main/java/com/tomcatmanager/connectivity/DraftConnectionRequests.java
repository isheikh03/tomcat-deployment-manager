package com.tomcatmanager.connectivity;

import com.tomcatmanager.infrastructure.ManagedHost;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * "Test before saving" requests, sent from the host and instance forms. Results are returned
 * but not persisted. Secrets left blank fall back to stored values only when the target is unchanged.
 */
public final class DraftConnectionRequests {
    private DraftConnectionRequests() { }

    public record DraftHost(
            Long hostId,
            @NotBlank @Size(max = 255) String hostname,
            @NotNull @Min(1) @Max(65535) Integer port,
            @NotBlank @Size(max = 64) String username,
            @NotNull ManagedHost.SshAuthMethod authMethod,
            @Size(max = 1024) String password,
            @Size(max = 16384) String privateKey,
            @Size(max = 1024) String passphrase,
            @Size(max = 100) String fingerprint) {
        @Override public String toString() { return "DraftHost[" + hostname + ":" + port + ", user=" + username + ", secrets=REDACTED]"; }
    }

    public record DraftManager(
            Long instanceId,
            @NotBlank @Size(max = 500) String managerUrl,
            @NotBlank @Size(max = 120) String username,
            @Size(max = 1024) String password,
            @Min(1) @Max(300) Integer timeoutSeconds) {
        @Override public String toString() { return "DraftManager[" + managerUrl + ", user=" + username + ", secrets=REDACTED]"; }
    }
}
