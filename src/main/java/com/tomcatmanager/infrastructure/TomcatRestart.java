package com.tomcatmanager.infrastructure;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The optional full Tomcat restart around an SSH/SFTP deployment: stop Tomcat, put the new WAR in place, start Tomcat.
 *
 * <p>No free-text command is ever accepted. An administrator chooses a method and the commands are built here from
 * fixed templates and strictly validated values (a systemd unit name, absolute paths without spaces or quotes), each
 * value single-quoted. {@link Commands} can only be obtained through {@link #commandsFor(TomcatInstance)}, so callers
 * that run remote commands cannot be handed anything else.
 *
 * <ul>
 *   <li>{@code SYSTEMD}: {@code [sudo -n] systemctl stop 'tomcat'} / {@code [sudo -n] systemctl start 'tomcat'}</li>
 *   <li>{@code SCRIPTS}: {@code [sudo -n] env CATALINA_BASE='/opt/tomcat/base' '/opt/tomcat/bin/shutdown.sh'} / {@code … startup.sh},
 *       then a wait, because shutdown.sh returns before Tomcat has stopped</li>
 * </ul>
 * {@code sudo -n} never prompts: it fails at once if the account may not run the command without a password.
 */
public final class TomcatRestart {
    /** NONE: hot deployment (Tomcat keeps running and redeploys the changed WAR itself). */
    public enum Mode {
        NONE("No restart (hot deployment)"), SYSTEMD("systemd service"), SCRIPTS("Tomcat scripts (shutdown.sh / startup.sh)");
        private final String label;
        Mode(String label) { this.label = label; }
        public String label() { return label; }
    }

    private static final Pattern SERVICE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9@._:-]{0,99}$");
    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9._/-]{0,254}$");
    public static final int DEFAULT_STOP_WAIT_SECONDS = 15;

    private TomcatRestart() { }

    /** Why a systemd unit name cannot be used, or null. */
    public static String serviceProblem(String service) {
        if (service == null || service.isBlank()) return "Enter the systemd service name, e.g. tomcat or tomcat9.";
        if (!SERVICE.matcher(service.strip()).matches()) return "Use letters, digits and @ . _ : - only (a systemd unit name such as tomcat9 or tomcat@app1).";
        return null;
    }

    /** Why a directory path cannot be used, or null. */
    public static String pathProblem(String path) {
        if (path == null || path.isBlank()) return "Enter an absolute path, e.g. /opt/tomcat.";
        String p = path.strip();
        if (!PATH.matcher(p).matches()) return "Use an absolute path with letters, digits and . _ - / only (no spaces or quotes).";
        for (String segment : p.split("/")) if (segment.equals("..")) return "The path must not contain \"..\".";
        return null;
    }

    /** The commands for the instance, or null when it is not restarted ({@code NONE}). Invalid settings throw. */
    public static Commands commandsFor(TomcatInstance instance) {
        Mode mode = instance.getRestartMode();
        if (mode == null || mode == Mode.NONE) return null;
        String sudo = instance.isRestartUseSudo() ? "sudo -n " : "";
        if (mode == Mode.SYSTEMD) {
            String problem = serviceProblem(instance.getRestartService());
            if (problem != null) throw new IllegalArgumentException("Restart settings: " + problem);
            String unit = quote(instance.getRestartService().strip());
            return new Commands(mode, sudo + "systemctl stop " + unit, sudo + "systemctl start " + unit,
                    "systemctl is-active " + unit + " || true", 0, instance.isRestartUseSudo(), List.of());
        }
        String base = stripSlash(instance.getCatalinaBase());
        String home = instance.getCatalinaHome() == null || instance.getCatalinaHome().isBlank() ? base : stripSlash(instance.getCatalinaHome());
        for (String p : List.of(base, home)) {
            String problem = pathProblem(p);
            if (problem != null) throw new IllegalArgumentException("Restart settings: " + problem + " (" + p + ")");
        }
        String env = "env CATALINA_BASE=" + quote(base) + " ";
        String shutdown = home + "/bin/shutdown.sh", startup = home + "/bin/startup.sh";
        int wait = instance.getRestartStopWaitSeconds() == null ? DEFAULT_STOP_WAIT_SECONDS : instance.getRestartStopWaitSeconds();
        return new Commands(mode, sudo + env + quote(shutdown), sudo + env + quote(startup), null, wait, instance.isRestartUseSudo(),
                List.of(shutdown, startup));
    }

    private static String quote(String value) { return "'" + value + "'"; } // values are validated: no quotes inside

    private static String stripSlash(String p) { return p == null ? null : (p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p).strip(); }

    /**
     * Commands built from validated settings. {@code status} (systemd only) is read-only; {@code stopWaitSeconds} is the
     * pause after the stop command (scripts); {@code scripts} are the script files that must exist (scripts only).
     */
    public static final class Commands {
        private final Mode mode;
        private final String stop, start, status;
        private final int stopWaitSeconds;
        private final boolean sudo;
        private final List<String> scripts;

        private Commands(Mode mode, String stop, String start, String status, int stopWaitSeconds, boolean sudo, List<String> scripts) {
            this.mode = mode; this.stop = stop; this.start = start; this.status = status; this.stopWaitSeconds = stopWaitSeconds;
            this.sudo = sudo; this.scripts = scripts;
        }

        public Mode mode() { return mode; }
        public String stop() { return stop; }
        public String start() { return start; }
        /** Read-only state query (systemd), or null. */
        public String status() { return status; }
        public int stopWaitSeconds() { return stopWaitSeconds; }
        public boolean sudo() { return sudo; }
        public List<String> scripts() { return scripts; }
        /** {@code sudo -n -l <command>}: asks sudo whether the command is allowed without a password, without running it. */
        public String sudoCheck(String command) { return "sudo -n -l " + command.substring("sudo -n ".length()); }
    }
}
