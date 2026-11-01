package com.tomcatmanager.connectivity;

import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the real SSH/SFTP client against an in-process SSH server. */
class SshConnectivityClientTest {
    private static SshConnectivityClient client;
    private static KeyPair clientKey;

    @TempDir Path root;
    private EmbeddedSshServer server;

    @BeforeAll static void startClient() throws Exception {
        client = new SshConnectivityClient();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        clientKey = generator.generateKeyPair();
    }
    @AfterAll static void stopClient() throws Exception { client.destroy(); }
    @AfterEach void stopServer() throws Exception { if (server != null) server.close(); }

    private EmbeddedSshServer start(boolean allowCommands) throws Exception {
        Files.createDirectories(root.resolve("opt/tomcat/webapps"));
        server = new EmbeddedSshServer(root, clientKey.getPublic(), allowCommands);
        return server;
    }

    private SshConnectivityClient.SshTarget target(String fingerprint, SshCredentials credentials) {
        return new SshConnectivityClient.SshTarget(InetAddress.getLoopbackAddress(), "localhost", server.port(), fingerprint, credentials, null, Duration.ofSeconds(10));
    }

    private ProbeReport probe(String fingerprint, SshCredentials credentials, String directory) {
        ProbeReport report = new ProbeReport();
        client.probe(target(fingerprint, credentials), directory, report);
        return report;
    }

    @Test void passwordAuthenticationWithPinnedKeySucceeds() throws Exception {
        start(true);
        ProbeReport report = probe(server.hostKeyFingerprint(), SshCredentials.password(EmbeddedSshServer.USER, EmbeddedSshServer.PASSWORD), null);
        assertThat(report.statusOf(CheckType.SSH_CONNECTION)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.statusOf(CheckType.SSH_HOST_KEY)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.statusOf(CheckType.SSH_AUTHENTICATION)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.statusOf(CheckType.SFTP_ACCESS)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.hostKeyVerification()).isEqualTo(HostKeyVerification.MATCHED);
        assertThat(report.outcome()).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.serverInfo()).startsWith("SSH-2.0-");
    }

    @Test void wrongPasswordFailsAuthenticationOnly() throws Exception {
        start(true);
        ProbeReport report = probe(server.hostKeyFingerprint(), SshCredentials.password(EmbeddedSshServer.USER, "wrong-password-value"), null);
        assertThat(report.statusOf(CheckType.SSH_HOST_KEY)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.statusOf(CheckType.SSH_AUTHENTICATION)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.statusOf(CheckType.SFTP_ACCESS)).isEqualTo(CheckStatus.SKIPPED);
        assertThat(report.outcome()).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).allSatisfy(c -> assertThat(String.valueOf(c.getDetail())).doesNotContain("wrong-password-value"));
    }

    @Test void unknownHostKeyIsRejectedBeforeCredentialsAreSent() throws Exception {
        start(true);
        ProbeReport report = probe(null, SshCredentials.password(EmbeddedSshServer.USER, EmbeddedSshServer.PASSWORD), null);
        assertThat(report.hostKeyVerification()).isEqualTo(HostKeyVerification.UNKNOWN);
        assertThat(report.presentedHostKey()).isEqualTo(server.hostKeyFingerprint());
        assertThat(report.statusOf(CheckType.SSH_HOST_KEY)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.statusOf(CheckType.SSH_AUTHENTICATION)).isEqualTo(CheckStatus.SKIPPED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("Unknown host key"));
    }

    @Test void changedHostKeyIsReportedAsMismatch() throws Exception {
        start(true);
        String otherKey = "SHA256:" + "A".repeat(43);
        ProbeReport report = probe(otherKey, SshCredentials.password(EmbeddedSshServer.USER, EmbeddedSshServer.PASSWORD), null);
        assertThat(report.hostKeyVerification()).isEqualTo(HostKeyVerification.MISMATCH);
        assertThat(report.statusOf(CheckType.SSH_HOST_KEY)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.statusOf(CheckType.SSH_AUTHENTICATION)).isEqualTo(CheckStatus.SKIPPED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("HOST KEY MISMATCH"));
    }

    @Test void encryptedPrivateKeyAuthenticationSucceeds() throws Exception {
        start(true);
        OpenSSHKeyEncryptionContext encryption = new OpenSSHKeyEncryptionContext();
        encryption.setPassword("key-passphrase");
        encryption.setCipherName("AES");
        encryption.setCipherType("256");
        ByteArrayOutputStream pem = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(clientKey, "test", encryption, pem);
        String privateKey = pem.toString(StandardCharsets.UTF_8);

        ProbeReport ok = probe(server.hostKeyFingerprint(), SshCredentials.privateKey(EmbeddedSshServer.USER, privateKey, "key-passphrase"), null);
        assertThat(ok.statusOf(CheckType.SSH_AUTHENTICATION)).isEqualTo(CheckStatus.SUCCESS);

        ProbeReport wrongPassphrase = probe(server.hostKeyFingerprint(), SshCredentials.privateKey(EmbeddedSshServer.USER, privateKey, "nope"), null);
        assertThat(wrongPassphrase.statusOf(CheckType.SSH_HOST_KEY)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(wrongPassphrase.statusOf(CheckType.SSH_AUTHENTICATION)).isEqualTo(CheckStatus.FAILED);
        assertThat(wrongPassphrase.checks()).allSatisfy(c -> assertThat(String.valueOf(c.getDetail())).doesNotContain("PRIVATE KEY-----"));
    }

    @Test void existingDeploymentDirectoryIsCheckedForWriteAccess() throws Exception {
        start(true);
        ProbeReport report = probe(server.hostKeyFingerprint(), SshCredentials.password(EmbeddedSshServer.USER, EmbeddedSshServer.PASSWORD), "/opt/tomcat/webapps");
        assertThat(report.statusOf(CheckType.DEPLOYMENT_DIRECTORY)).isIn(CheckStatus.SUCCESS, CheckStatus.FAILED);
        assertThat(report.checks()).anySatisfy(c -> {
            assertThat(c.getCheckType()).isEqualTo(CheckType.DEPLOYMENT_DIRECTORY);
            assertThat(c.getDetail()).contains("/opt/tomcat/webapps").contains("uid 1000");
        });
    }

    @Test void missingDeploymentDirectoryFails() throws Exception {
        start(true);
        ProbeReport report = probe(server.hostKeyFingerprint(), SshCredentials.password(EmbeddedSshServer.USER, EmbeddedSshServer.PASSWORD), "/opt/tomcat/missing");
        assertThat(report.statusOf(CheckType.SFTP_ACCESS)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.statusOf(CheckType.DEPLOYMENT_DIRECTORY)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("does not exist"));
    }

    @Test void sftpOnlyAccountReportsUndeterminedWritePermission() throws Exception {
        start(false);
        ProbeReport report = probe(server.hostKeyFingerprint(), SshCredentials.password(EmbeddedSshServer.USER, EmbeddedSshServer.PASSWORD), "/opt/tomcat/webapps");
        assertThat(report.statusOf(CheckType.DEPLOYMENT_DIRECTORY)).isEqualTo(CheckStatus.WARNING);
        assertThat(report.outcome()).isEqualTo(CheckStatus.WARNING);
    }

    @Test void closedPortFailsConnection() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { freePort = socket.getLocalPort(); }
        ProbeReport report = new ProbeReport();
        client.probe(new SshConnectivityClient.SshTarget(InetAddress.getLoopbackAddress(), "localhost", freePort, null,
                SshCredentials.password("deploy", "x"), null, Duration.ofSeconds(5)), null, report);
        assertThat(report.statusOf(CheckType.SSH_CONNECTION)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.statusOf(CheckType.SSH_HOST_KEY)).isEqualTo(CheckStatus.SKIPPED);
    }

    @Test void missingStoredSecretStillVerifiesHostKey() throws Exception {
        start(true);
        ProbeReport report = new ProbeReport();
        client.probe(new SshConnectivityClient.SshTarget(InetAddress.getLoopbackAddress(), "localhost", server.port(), server.hostKeyFingerprint(),
                SshCredentials.password(EmbeddedSshServer.USER, null), "No SSH password is stored for this host.", Duration.ofSeconds(10)), null, report);
        assertThat(report.statusOf(CheckType.SSH_HOST_KEY)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.statusOf(CheckType.SSH_AUTHENTICATION)).isEqualTo(CheckStatus.FAILED);
    }
}
