package com.tomcatmanager.deployment;

import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import com.tomcatmanager.infrastructure.TomcatRestart;
import org.apache.sshd.client.session.ClientSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Runs the stop or start command of an instance's restart settings over an open SSH session. Only commands built by
 * {@link TomcatRestart#commandsFor} can be run: this class takes a {@link TomcatRestart.Commands} and a step, never a
 * command string.
 */
final class TomcatRestarter {
    enum Step { STOP, START }

    /** How long a stop or start command may take (systemd waits for the service to stop or start). */
    static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(3);

    private TomcatRestarter() { }

    /** Runs the step; a non-zero exit status or timeout throws, with the (sanitized) error output in the message. */
    static void run(ClientSession session, TomcatRestart.Commands commands, Step step) throws IOException {
        String command = step == Step.STOP ? commands.stop() : commands.start();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            session.executeRemoteCommand(command, stderr, StandardCharsets.UTF_8, COMMAND_TIMEOUT);
        } catch (IOException e) {
            String err = DiagnosticSanitizer.clean(stderr.toString(StandardCharsets.UTF_8).strip(), 400);
            String hint = err != null && (err.contains("a password is required") || err.contains("not allowed") || err.contains("may not run"))
                    ? " The SSH account may not run this with sudo without a password; add a NOPASSWD sudoers rule for exactly this command."
                    : err != null && err.contains("Interactive authentication required")
                    ? " The account may not manage the service; enable \"Use sudo\" or add a polkit rule." : "";
            throw new IOException("`" + command + "` failed" + (err == null || err.isBlank() ? ": " + DiagnosticSanitizer.describe(e) : ": " + err) + "." + hint, e);
        }
    }
}
