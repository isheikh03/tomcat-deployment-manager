package com.tomcatmanager.connectivity;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;

/**
 * A real SSH/SFTP server on 127.0.0.1 for tests (Apache MINA SSHD). Files are served from a
 * temporary directory; only the fixed commands {@code id -u} / {@code id -G} are answered.
 */
public final class EmbeddedSshServer implements AutoCloseable {
    public static final String USER = "deploy";
    public static final String PASSWORD = "correct-horse-battery";

    private final SshServer server;
    private final PublicKey hostKey;

    public EmbeddedSshServer(Path root, PublicKey authorizedKey, boolean allowCommands) throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider();
        keys.setAlgorithm(KeyUtils.EC_ALGORITHM);
        server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((username, password, session) -> USER.equals(username) && PASSWORD.equals(password));
        server.setPublickeyAuthenticator((username, key, session) -> USER.equals(username) && authorizedKey != null && KeyUtils.compareKeys(authorizedKey, key));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        if (allowCommands) server.setCommandFactory((channel, command) -> new FixedCommand(command));
        server.start();
        hostKey = keys.loadKeys(null).iterator().next().getPublic();
    }

    public int port() { return server.getPort(); }
    public String hostKeyFingerprint() { return HostKeyFingerprints.sha256(hostKey); }

    @Override public void close() throws IOException { server.stop(true); }

    /** Answers {@code id -u} and {@code id -G} like a Linux account with uid 1000 in groups 1000 and 27. */
    private static final class FixedCommand implements Command {
        private final String command;
        private OutputStream out, err;
        private ExitCallback exit;
        FixedCommand(String command) { this.command = command; }
        @Override public void setInputStream(InputStream in) { }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.exit = callback; }
        @Override public void start(ChannelSession channel, Environment env) throws IOException {
            String output = switch (command) { case "id -u" -> "1000\n"; case "id -G" -> "1000 27\n"; default -> null; };
            if (output == null) { err.write("not allowed\n".getBytes(StandardCharsets.UTF_8)); err.flush(); exit.onExit(127); return; }
            out.write(output.getBytes(StandardCharsets.UTF_8)); out.flush(); exit.onExit(0);
        }
        @Override public void destroy(ChannelSession channel) { }
    }
}
