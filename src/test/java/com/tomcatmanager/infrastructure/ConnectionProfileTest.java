package com.tomcatmanager.infrastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionProfileTest {

    @Test
    void sshProfileRequiresHostKeyFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionProfile(
                ConnectionProfile.DeploymentMethod.SSH, "SFTP", "example.test", 22,
                "deploy", ConnectionProfile.AuthenticationMethod.PRIVATE_KEY,
                "/opt/tomcat/webapps", null, null, 15, 2));
    }

    @Test
    void retryCountIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionProfile(
                ConnectionProfile.DeploymentMethod.TOMCAT_MANAGER, "HTTPS", "example.test", 8443,
                "manager", ConnectionProfile.AuthenticationMethod.PASSWORD,
                null, "/manager/text", null, 15, 6));
    }

    @Test
    void acceptsValidSshProfile() {
        ConnectionProfile profile = new ConnectionProfile(
                ConnectionProfile.DeploymentMethod.SSH, "SFTP", "example.test", 22,
                "deploy", ConnectionProfile.AuthenticationMethod.PRIVATE_KEY,
                "/opt/tomcat/webapps", null, "SHA256:example-fingerprint", 15, 2);
        assertEquals("example.test", profile.hostname());
    }
}
