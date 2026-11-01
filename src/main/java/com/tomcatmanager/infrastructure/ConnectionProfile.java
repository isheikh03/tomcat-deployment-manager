package com.tomcatmanager.infrastructure;

public record ConnectionProfile(
        DeploymentMethod deploymentMethod,
        String protocol,
        String hostname,
        int port,
        String username,
        AuthenticationMethod authenticationMethod,
        String remoteDeploymentDirectory,
        String managerEndpoint,
        String expectedHostKeyFingerprint,
        int connectTimeoutSeconds,
        int maxRetries
) {
    public enum DeploymentMethod { SSH, TOMCAT_MANAGER }
    public enum AuthenticationMethod { PASSWORD, PRIVATE_KEY }

    public ConnectionProfile {
        if (hostname == null || hostname.isBlank()) throw new IllegalArgumentException("Hostname is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 300)
            throw new IllegalArgumentException("Connection timeout must be between 1 and 300 seconds");
        if (maxRetries < 0 || maxRetries > 5)
            throw new IllegalArgumentException("Retries must be between 0 and 5");
        if (deploymentMethod == DeploymentMethod.SSH
                && (expectedHostKeyFingerprint == null || expectedHostKeyFingerprint.isBlank()))
            throw new IllegalArgumentException("SSH host-key fingerprint must be configured");
    }
}
