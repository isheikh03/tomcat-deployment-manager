package com.tomcatmanager.connectivity;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Real SSH/SFTP connectivity checks using Apache MINA SSHD.
 *
 * <p>Host keys are verified strictly against the pinned SHA-256 fingerprint before any
 * authentication is attempted. The client ignores ~/.ssh/config, ~/.ssh/known_hosts and
 * default identity files so behaviour depends only on the stored configuration.
 */
@Component
public class SshConnectivityClient implements DisposableBean {
    static final AttributeRepository.AttributeKey<HostKeyCheck> HOST_KEY_CHECK = new AttributeRepository.AttributeKey<>();

    private final SshClient client;

    public SshConnectivityClient() {
        client = SshClient.setUpDefaultClient();
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setServerKeyVerifier((session, remoteAddress, serverKey) -> {
            HostKeyCheck check = session.getConnectionContext() == null ? null : session.getConnectionContext().getAttribute(HOST_KEY_CHECK);
            return check != null && check.verify(serverKey);
        });
        client.start();
    }

    public record SshTarget(InetAddress address, String hostname, int port, String pinnedFingerprint,
                            SshCredentials credentials, String credentialProblem, Duration timeout) {
        @Override public String toString() { return "SshTarget[" + hostname + ":" + port + "]"; }
    }

    /**
     * Runs connection, host-key, authentication and SFTP checks. When {@code deploymentDirectory}
     * is non-null the directory is inspected (read-only) as well.
     */
    public void probe(SshTarget target, String deploymentDirectory, ProbeReport report) {
        String username = target.credentials() == null ? null : target.credentials().username();
        if (username == null || username.isBlank()) {
            report.failed(CheckType.SSH_CONNECTION, "No SSH username is configured for this host.");
            report.skip("SSH username missing.", CheckType.SSH_HOST_KEY, CheckType.SSH_AUTHENTICATION, CheckType.SFTP_ACCESS);
            if (deploymentDirectory != null) report.skip("SSH username missing.", CheckType.DEPLOYMENT_DIRECTORY);
            return;
        }

        HostKeyCheck hostKeyCheck = new HostKeyCheck(target.pinnedFingerprint());
        long timeoutMs = target.timeout().toMillis();
        ClientSession session;
        try {
            ConnectFuture future = client.connect(username, new InetSocketAddress(target.address(), target.port()),
                    AttributeRepository.ofKeyValuePair(HOST_KEY_CHECK, hostKeyCheck), null);
            session = future.verify(timeoutMs).getSession();
        } catch (IOException | RuntimeException e) {
            report.failed(CheckType.SSH_CONNECTION, connectFailure(target, e));
            report.skip("No SSH connection.", CheckType.SSH_HOST_KEY, CheckType.SSH_AUTHENTICATION, CheckType.SFTP_ACCESS);
            if (deploymentDirectory != null) report.skip("No SSH connection.", CheckType.DEPLOYMENT_DIRECTORY);
            return;
        }

        try (session) {
            List<KeyPair> keys = null;
            String credentialProblem = target.credentialProblem();
            if (credentialProblem == null) {
                try {
                    keys = addIdentities(session, target.credentials());
                } catch (SshKeyLoader.InvalidKeyException e) {
                    credentialProblem = e.getMessage();
                }
            }

            boolean authenticated = false;
            String authFailure = credentialProblem;
            if (credentialProblem == null) {
                try {
                    session.auth().verify(timeoutMs);
                    authenticated = true;
                } catch (IOException | RuntimeException e) {
                    authFailure = DiagnosticSanitizer.describe(e);
                }
            } else {
                // Still complete the key exchange so the host key is verified and reported.
                waitForKeyExchange(session, timeoutMs);
            }

            if (!reportHandshake(target, session, hostKeyCheck, report)) {
                report.skip("Host key not trusted; credentials were not sent.", CheckType.SSH_AUTHENTICATION, CheckType.SFTP_ACCESS);
                if (deploymentDirectory != null) report.skip("Host key not trusted.", CheckType.DEPLOYMENT_DIRECTORY);
                return;
            }
            if (!authenticated) {
                report.failed(CheckType.SSH_AUTHENTICATION, credentialProblem != null ? credentialProblem
                        : "Server rejected " + describeMethod(target.credentials(), keys) + " for user '" + username + "'. (" + authFailure + ")");
                report.skip("Not authenticated.", CheckType.SFTP_ACCESS);
                if (deploymentDirectory != null) report.skip("Not authenticated.", CheckType.DEPLOYMENT_DIRECTORY);
                return;
            }
            report.success(CheckType.SSH_AUTHENTICATION, "Authenticated as '" + username + "' using " + describeMethod(target.credentials(), keys) + ".");
            probeSftp(session, deploymentDirectory, timeoutMs, report);
        } catch (IOException e) {
            // Closing the session failed; results already recorded stand.
        }
    }

    /**
     * Opens an authenticated session for remote operations (deployments). The host key must match
     * the pinned fingerprint; otherwise the session is closed before any credential is sent.
     * The caller must close the returned session.
     */
    public ClientSession openSession(SshTarget target) throws RemoteOperationException {
        if (target.credentials() == null || target.credentials().username() == null || target.credentials().username().isBlank())
            throw new RemoteOperationException("No SSH username is configured for host " + target.hostname() + ".");
        if (target.credentialProblem() != null) throw new RemoteOperationException(target.credentialProblem());
        HostKeyCheck hostKeyCheck = new HostKeyCheck(target.pinnedFingerprint());
        long timeoutMs = target.timeout().toMillis();
        ClientSession session;
        try {
            session = client.connect(target.credentials().username(), new InetSocketAddress(target.address(), target.port()),
                    AttributeRepository.ofKeyValuePair(HOST_KEY_CHECK, hostKeyCheck), null).verify(timeoutMs).getSession();
        } catch (IOException | RuntimeException e) {
            throw new RemoteOperationException(connectFailure(target, e), e);
        }
        try {
            addIdentities(session, target.credentials());
            session.auth().verify(timeoutMs);
            return session;
        } catch (SshKeyLoader.InvalidKeyException e) {
            closeQuietly(session);
            throw new RemoteOperationException(e.getMessage());
        } catch (IOException | RuntimeException e) {
            closeQuietly(session);
            switch (hostKeyCheck.outcome()) {
                case UNKNOWN -> throw new RemoteOperationException("No host key is pinned for " + target.hostname() + "; credentials were not sent. Pin the host key first.");
                case MISMATCH -> throw new RemoteOperationException("HOST KEY MISMATCH for " + target.hostname() + ": the server presented " + hostKeyCheck.presented()
                        + ", which is not the pinned key. Operation aborted; credentials were not sent.");
                case NOT_CHECKED -> throw new RemoteOperationException("The SSH key exchange with " + target.hostname() + " did not complete.");
                default -> throw new RemoteOperationException("SSH authentication as '" + target.credentials().username() + "' was rejected by " + target.hostname() + ".");
            }
        }
    }

    private static void closeQuietly(ClientSession session) {
        try { session.close(true); } catch (RuntimeException ignored) { /* best effort */ }
    }

    private static List<KeyPair> addIdentities(ClientSession session, SshCredentials credentials) throws SshKeyLoader.InvalidKeyException {
        if (credentials.method() == SshCredentials.Method.PASSWORD) {
            if (credentials.password() == null || credentials.password().isEmpty())
                throw new SshKeyLoader.InvalidKeyException("No SSH password is stored for this host.");
            session.addPasswordIdentity(credentials.password());
            return null;
        }
        List<KeyPair> keys = SshKeyLoader.load(credentials.privateKey(), credentials.passphrase());
        keys.forEach(session::addPublicKeyIdentity);
        return keys;
    }

    private static void waitForKeyExchange(ClientSession session, long timeoutMs) {
        try {
            session.waitFor(java.util.EnumSet.of(org.apache.sshd.client.session.ClientSession.ClientSessionEvent.WAIT_AUTH,
                    org.apache.sshd.client.session.ClientSession.ClientSessionEvent.CLOSED), timeoutMs);
        } catch (RuntimeException ignored) {
            // Outcome is read from the host-key check below.
        }
    }

    /** Records SSH_CONNECTION and SSH_HOST_KEY; returns true when the host key matched. */
    private static boolean reportHandshake(SshTarget target, ClientSession session, HostKeyCheck check, ProbeReport report) {
        String serverVersion = session.getServerVersion();
        if (serverVersion != null) report.serverInfo(serverVersion);
        report.hostKey(check.outcome(), check.presented());
        switch (check.outcome()) {
            case MATCHED -> {
                report.success(CheckType.SSH_CONNECTION, "Connected to " + target.address().getHostAddress() + ":" + target.port()
                        + (serverVersion == null ? "." : " (" + serverVersion + ")."));
                report.success(CheckType.SSH_HOST_KEY, "Host key matches the pinned fingerprint " + check.presented() + ".");
                return true;
            }
            case UNKNOWN -> {
                report.success(CheckType.SSH_CONNECTION, "TCP and SSH handshake reached " + target.address().getHostAddress() + ":" + target.port() + ".");
                report.failed(CheckType.SSH_HOST_KEY, "Unknown host key: no fingerprint is pinned for this host. The server presented "
                        + check.presented() + ". Verify it out-of-band (for example on the server console) before an administrator trusts it.");
            }
            case MISMATCH -> {
                report.success(CheckType.SSH_CONNECTION, "TCP and SSH handshake reached " + target.address().getHostAddress() + ":" + target.port() + ".");
                report.failed(CheckType.SSH_HOST_KEY, "HOST KEY MISMATCH: the server presented " + check.presented()
                        + " but " + HostKeyFingerprints.normalize(target.pinnedFingerprint()) + " is pinned. This can indicate a man-in-the-middle attack or a reinstalled server."
                        + " The connection was aborted; an administrator must investigate and update the fingerprint explicitly.");
            }
            default -> {
                report.failed(CheckType.SSH_CONNECTION, "Connected, but the SSH key exchange did not complete within the timeout.");
                report.skip("Key exchange incomplete.", CheckType.SSH_HOST_KEY);
            }
        }
        return false;
    }

    private void probeSftp(ClientSession session, String directory, long timeoutMs, ProbeReport report) {
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            String home = sftp.canonicalPath(".");
            report.success(CheckType.SFTP_ACCESS, "SFTP subsystem available (protocol v" + sftp.getVersion() + "); login directory " + home + ".");
            if (directory != null) probeDirectory(session, sftp, directory, timeoutMs, report);
        } catch (SshChannelOpenException e) {
            report.failed(CheckType.SFTP_ACCESS, "The server refused to open an SFTP channel. Is the SFTP subsystem enabled for this account?");
            if (directory != null) report.skip("No SFTP session.", CheckType.DEPLOYMENT_DIRECTORY);
        } catch (IOException | RuntimeException e) {
            report.failed(CheckType.SFTP_ACCESS, "SFTP session failed: " + DiagnosticSanitizer.describe(e));
            if (directory != null) report.skip("No SFTP session.", CheckType.DEPLOYMENT_DIRECTORY);
        }
    }

    private static void probeDirectory(ClientSession session, SftpClient sftp, String directory, long timeoutMs, ProbeReport report) {
        SftpClient.Attributes attributes;
        try {
            attributes = sftp.stat(directory);
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE || e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH)
                report.failed(CheckType.DEPLOYMENT_DIRECTORY, "Directory " + directory + " does not exist.");
            else if (e.getStatus() == SftpConstants.SSH_FX_PERMISSION_DENIED)
                report.failed(CheckType.DEPLOYMENT_DIRECTORY, "Permission denied while accessing " + directory + ".");
            else report.failed(CheckType.DEPLOYMENT_DIRECTORY, "Could not inspect " + directory + ": " + DiagnosticSanitizer.describe(e));
            return;
        } catch (IOException e) {
            report.failed(CheckType.DEPLOYMENT_DIRECTORY, "Could not inspect " + directory + ": " + DiagnosticSanitizer.describe(e));
            return;
        }
        if (!attributes.isDirectory()) {
            report.failed(CheckType.DEPLOYMENT_DIRECTORY, directory + " exists but is not a directory.");
            return;
        }
        try (SftpClient.CloseableHandle handle = sftp.openDir(directory)) {
            sftp.readDir(handle);
        } catch (IOException e) {
            report.failed(CheckType.DEPLOYMENT_DIRECTORY, directory + " exists but cannot be listed: " + DiagnosticSanitizer.describe(e));
            return;
        }

        int mode = attributes.getPermissions() & 0777;
        String modeText = DirectoryPermissions.symbolic(mode) + " owner uid " + attributes.getUserId() + " gid " + attributes.getGroupId();
        Integer uid; Set<Integer> gids;
        try {
            uid = Integer.valueOf(runFixedCommand(session, "id -u", timeoutMs).trim());
            gids = new HashSet<>();
            for (String g : runFixedCommand(session, "id -G", timeoutMs).trim().split("\\s+")) if (!g.isBlank()) gids.add(Integer.valueOf(g));
        } catch (IOException | RuntimeException e) {
            report.warning(CheckType.DEPLOYMENT_DIRECTORY, directory + " exists and is readable (" + modeText + "). Write permission could not be"
                    + " determined because the account cannot run 'id' (SFTP-only accounts are not supported for permission checks).");
            return;
        }
        if (DirectoryPermissions.evaluate(mode, attributes.getUserId(), attributes.getGroupId(), uid, gids) == DirectoryPermissions.Access.WRITABLE) {
            String rootNote = uid == 0 ? " Note: the account is root; a dedicated least-privilege deployment account is recommended." : "";
            report.success(CheckType.DEPLOYMENT_DIRECTORY, directory + " exists, is readable and writable by uid " + uid + " (" + modeText + ")." + rootNote);
        } else {
            report.failed(CheckType.DEPLOYMENT_DIRECTORY, directory + " is readable but not writable by uid " + uid + " (" + modeText + ").");
        }
    }

    /** Executes one of a fixed set of argument-free commands; never interpolates user input. */
    private static String runFixedCommand(ClientSession session, String command, long timeoutMs) throws IOException {
        if (!command.equals("id -u") && !command.equals("id -G")) throw new IllegalArgumentException("Command not allowed");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        // The permission probe is optional: SFTP-only accounts may never answer an exec request, so cap the wait.
        return session.executeRemoteCommand(command, stderr, StandardCharsets.UTF_8, Duration.ofMillis(Math.min(timeoutMs, 3000)));
    }

    private static String connectFailure(SshTarget target, Throwable e) {
        String where = target.hostname() + " (" + target.address().getHostAddress() + "):" + target.port();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) return "Connection refused by " + where + ". Is SSH listening on this port?";
            if (t instanceof NoRouteToHostException) return "No route to " + where + ".";
            if (t instanceof SocketTimeoutException) return "Timed out connecting to " + where + ".";
        }
        String message = DiagnosticSanitizer.describe(e);
        if (message != null && message.toLowerCase().contains("timeout"))
            return "Timed out after " + target.timeout().toSeconds() + "s connecting to " + where + ".";
        return "Could not connect to " + where + ": " + message;
    }

    private static String describeMethod(SshCredentials credentials, List<KeyPair> keys) {
        if (credentials.method() == SshCredentials.Method.PASSWORD) return "password authentication";
        String type = keys == null || keys.isEmpty() ? "private key" : keys.get(0).getPublic().getAlgorithm() + " private key";
        return type + " authentication";
    }

    @Override
    public void destroy() throws IOException { client.stop(); client.close(); }

    /** Holds the expected fingerprint and records what the server actually presented. */
    static final class HostKeyCheck {
        private final String pinned;
        private volatile HostKeyVerification outcome = HostKeyVerification.NOT_CHECKED;
        private volatile String presented;

        HostKeyCheck(String pinned) { this.pinned = HostKeyFingerprints.normalize(pinned); }

        boolean verify(PublicKey key) {
            presented = HostKeyFingerprints.sha256(key);
            if (pinned == null) { outcome = HostKeyVerification.UNKNOWN; return false; }
            boolean match = HostKeyFingerprints.matches(pinned, presented);
            outcome = match ? HostKeyVerification.MATCHED : HostKeyVerification.MISMATCH;
            return match;
        }
        HostKeyVerification outcome() { return outcome; }
        String presented() { return presented; }
    }
}
