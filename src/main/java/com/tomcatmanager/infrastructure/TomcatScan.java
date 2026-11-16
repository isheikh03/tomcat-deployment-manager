package com.tomcatmanager.infrastructure;

import org.apache.sshd.sftp.client.SftpClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Tomcat installations on a Linux host by <b>reading files over SFTP only</b>; no command is run on the server.
 * Sources, in this order:
 * <ol>
 *   <li>running Tomcats: {@code /proc/<pid>/cmdline} with {@code org.apache.catalina.startup.Bootstrap}
 *       ({@code -Dcatalina.base}, {@code -Dcatalina.home} and the Java binary);</li>
 *   <li>systemd units named {@code *tomcat*.service} ({@code CATALINA_BASE} / {@code CATALINA_HOME});</li>
 *   <li>folders below common locations ({@code /opt}, {@code /usr/share}, {@code /var/lib}, …) that contain {@code conf/server.xml}.</li>
 * </ol>
 * For each CATALINA_BASE it reads {@code conf/server.xml} (HTTP port, appBase, autoDeploy), the version
 * ({@code RELEASE-NOTES} or the folder name) and the applications in the deployment directory. Every read is
 * size-limited and the whole scan has an operation and time budget; unreadable places are skipped.
 */
public final class TomcatScan {
    static final List<String> FOLDER_ROOTS = List.of("/opt", "/usr/share", "/var/lib", "/usr/local", "/srv", "/home", "/data", "/app", "/apps", "/u01");
    static final List<String> SYSTEMD_DIRS = List.of("/etc/systemd/system", "/lib/systemd/system", "/usr/lib/systemd/system");
    /** Applications that come with Tomcat; they are not offered as instances. */
    static final Set<String> STANDARD_APPS = Set.of("manager", "host-manager", "docs", "examples");
    private static final String BOOTSTRAP = "org.apache.catalina.startup.Bootstrap";
    private static final Pattern RELEASE_VERSION = Pattern.compile("Apache Tomcat Version (\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern PATH_VERSION = Pattern.compile("tomcat[-_]?(\\d+\\.\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVA_VERSION = Pattern.compile("(?:jdk|jre|java|openjdk|temurin|corretto|zulu)[-_.]?(?:1\\.)?(\\d{1,2})(?![0-9])", Pattern.CASE_INSENSITIVE);

    /** One application in a Tomcat's deployment directory: a WAR file, or an unpacked folder without one. */
    public record App(String filename, boolean exploded) { }

    /** One Tomcat installation (one CATALINA_BASE). */
    public record Tomcat(String catalinaBase, String catalinaHome, String appBase, String deploymentDirectory, Integer httpPort,
                         String tomcatVersion, Integer javaVersion, boolean running, String systemdService, Boolean autoDeploy,
                         List<App> apps, String foundBy) { }

    public record Result(List<Tomcat> tomcats, List<String> notes) { }

    /** Limits for one scan. */
    static final class Budget {
        private final Instant deadline;
        private int operations;
        private final int maxOperations;
        Budget(Duration time, int maxOperations) { this.deadline = Instant.now().plus(time); this.maxOperations = maxOperations; }
        boolean spend() { return ++operations <= maxOperations && Instant.now().isBefore(deadline); }
        boolean exhausted() { return operations > maxOperations || !Instant.now().isBefore(deadline); }
    }

    /** What the sources said about one CATALINA_BASE before it is inspected. */
    private static final class Lead {
        String base, home, systemdService, foundBy;
        Integer javaVersion;
        boolean running;
    }

    private TomcatScan() { }

    public static Result scan(SftpClient sftp, Duration timeLimit) {
        Budget budget = new Budget(timeLimit, 4000);
        List<String> notes = new ArrayList<>();
        Map<String, Lead> leads = new LinkedHashMap<>();
        fromProcesses(sftp, budget, leads);
        fromSystemd(sftp, budget, leads);
        fromFolders(sftp, budget, leads);

        List<Tomcat> tomcats = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Lead lead : leads.values()) {
            if (!budget.spend()) break;
            Tomcat t = inspect(sftp, lead, budget);
            if (t != null && seen.add(t.catalinaBase())) tomcats.add(t);
        }
        if (budget.exhausted()) notes.add("The scan stopped at its time or size limit; some Tomcat installations may be missing. Add those by hand.");
        if (!leads.isEmpty() && tomcats.isEmpty()) notes.add("Possible Tomcat folders were found but none could be read (no conf/server.xml, or no permission).");
        tomcats.sort(Comparator.comparing(Tomcat::catalinaBase));
        return new Result(tomcats, notes);
    }

    // ---------------------------------------------------------------- sources

    private static void fromProcesses(SftpClient sftp, Budget budget, Map<String, Lead> leads) {
        for (String pid : list(sftp, "/proc", budget, true)) {
            if (!pid.chars().allMatch(Character::isDigit)) continue;
            if (!budget.spend()) return;
            String cmdline = read(sftp, "/proc/" + pid + "/cmdline", 32 * 1024);
            if (cmdline == null || !cmdline.contains(BOOTSTRAP)) continue;
            String[] args = cmdline.split("\u0000");
            String base = null, home = null;
            for (String arg : args) {
                if (arg.startsWith("-Dcatalina.base=")) base = arg.substring("-Dcatalina.base=".length());
                else if (arg.startsWith("-Dcatalina.home=")) home = arg.substring("-Dcatalina.home=".length());
            }
            if (base == null) base = home;
            if (base == null) continue;
            Lead lead = lead(leads, base, "running process " + pid);
            lead.running = true;
            if (home != null) lead.home = clean(home);
            if (args.length > 0) lead.javaVersion = javaVersion(args[0]);
        }
    }

    private static void fromSystemd(SftpClient sftp, Budget budget, Map<String, Lead> leads) {
        for (String dir : SYSTEMD_DIRS) {
            for (String file : list(sftp, dir, budget, false)) {
                String lower = file.toLowerCase(Locale.ROOT);
                if (!lower.contains("tomcat") || !lower.endsWith(".service") || file.contains("@")) continue;
                if (!budget.spend()) return;
                String unit = read(sftp, dir + "/" + file, 64 * 1024);
                if (unit == null) continue;
                String base = null, home = null;
                for (String line : unit.split("\\R")) {
                    String l = line.strip();
                    if (l.startsWith("Environment=")) {
                        for (String assignment : environmentAssignments(l.substring("Environment=".length()))) {
                            if (assignment.startsWith("CATALINA_BASE=")) base = assignment.substring("CATALINA_BASE=".length());
                            if (assignment.startsWith("CATALINA_HOME=")) home = assignment.substring("CATALINA_HOME=".length());
                        }
                    } else if (l.startsWith("ExecStart=") && home == null) {
                        Matcher m = Pattern.compile("(/[^\\s\"']+)/bin/(?:startup|catalina)\\.sh").matcher(l);
                        if (m.find()) home = m.group(1);
                    }
                }
                if (base == null) base = home;
                if (base == null || base.contains("%") || base.contains("$")) continue;
                Lead lead = lead(leads, base, "systemd unit " + file);
                lead.systemdService = file.substring(0, file.length() - ".service".length());
                if (home != null && lead.home == null && !home.contains("$")) lead.home = clean(home);
            }
        }
    }

    private static void fromFolders(SftpClient sftp, Budget budget, Map<String, Lead> leads) {
        for (String root : FOLDER_ROOTS) {
            for (String child : list(sftp, root, budget, true)) {
                if (child.startsWith(".")) continue;
                String dir = root + "/" + child;
                if (isTomcatBase(sftp, dir, budget)) { lead(leads, dir, "folder " + dir); continue; }
                for (String grandchild : list(sftp, dir, budget, true)) {
                    if (grandchild.startsWith(".")) continue;
                    String sub = dir + "/" + grandchild;
                    if (isTomcatBase(sftp, sub, budget)) lead(leads, sub, "folder " + sub);
                    if (budget.exhausted()) return;
                }
                if (budget.exhausted()) return;
            }
        }
    }

    private static Lead lead(Map<String, Lead> leads, String base, String foundBy) {
        String key = clean(base);
        return leads.computeIfAbsent(key, k -> { Lead l = new Lead(); l.base = k; l.foundBy = foundBy; return l; });
    }

    // ---------------------------------------------------------------- inspection

    private static Tomcat inspect(SftpClient sftp, Lead lead, Budget budget) {
        String base = canonical(sftp, lead.base);
        String serverXml = read(sftp, base + "/conf/server.xml", 1024 * 1024);
        if (serverXml == null) return null;
        ServerXml config = ServerXml.parse(serverXml, base);
        String home = lead.home == null ? base : canonical(sftp, lead.home);
        String version = null;
        String notes = read(sftp, home + "/RELEASE-NOTES", 16 * 1024);
        if (notes != null) {
            Matcher m = RELEASE_VERSION.matcher(notes);
            if (m.find()) version = m.group(1);
        }
        if (version == null) version = pathVersion(home, base);
        String deployDir = config.appBase().startsWith("/") ? config.appBase() : base + "/" + config.appBase();
        List<App> apps = apps(sftp, deployDir, budget);
        return new Tomcat(base, lead.home == null ? null : home, config.appBase(), deployDir, config.httpPort(), version, lead.javaVersion,
                lead.running, lead.systemdService, config.autoDeploy(), apps, lead.foundBy);
    }

    /** WAR files of the deployment directory, and unpacked folders that have no WAR, without Tomcat's own applications. */
    private static List<App> apps(SftpClient sftp, String dir, Budget budget) {
        List<String> names = new ArrayList<>();
        Set<String> folders = new TreeSet<>();
        try {
            if (!budget.spend()) return List.of();
            for (SftpClient.DirEntry e : sftp.readDir(dir)) {
                String n = e.getFilename();
                if (n.startsWith(".")) continue;
                if (e.getAttributes().isRegularFile() && n.toLowerCase(Locale.ROOT).endsWith(".war")) names.add(n);
                else if (e.getAttributes().isDirectory() && !STANDARD_APPS.contains(n)) folders.add(n);
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        List<App> apps = new ArrayList<>();
        Set<String> warBases = new HashSet<>();
        for (String war : names) {
            String bare = war.substring(0, war.length() - 4);
            warBases.add(bare);
            if (!STANDARD_APPS.contains(bare)) apps.add(new App(war, false));
        }
        for (String folder : folders) if (!warBases.contains(folder)) apps.add(new App(folder + ".war", true));
        apps.sort(Comparator.comparing(App::filename, String.CASE_INSENSITIVE_ORDER));
        return apps;
    }

    private static boolean isTomcatBase(SftpClient sftp, String dir, Budget budget) {
        if (!budget.spend()) return false;
        try {
            SftpClient.Attributes a = sftp.stat(dir + "/conf/server.xml");
            return a != null && a.isRegularFile();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Directory entries (names only); empty when the directory is missing or unreadable. */
    private static List<String> list(SftpClient sftp, String dir, Budget budget, boolean directoriesOnly) {
        List<String> names = new ArrayList<>();
        if (!budget.spend()) return names;
        try {
            for (SftpClient.DirEntry e : sftp.readDir(dir)) {
                String n = e.getFilename();
                if (n.equals(".") || n.equals("..")) continue;
                if (directoriesOnly && !e.getAttributes().isDirectory() && !e.getAttributes().isSymbolicLink()) continue;
                names.add(n);
                if (names.size() > 5000) break;
            }
        } catch (IOException | RuntimeException e) {
            // missing or not readable: nothing to find there
        }
        return names;
    }

    /** Reads at most {@code limit} bytes as UTF-8; null when missing or unreadable. */
    static String read(SftpClient sftp, String path, int limit) {
        try (InputStream in = sftp.read(path)) {
            return new String(in.readNBytes(limit), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String canonical(SftpClient sftp, String path) {
        try { return clean(sftp.canonicalPath(path)); } catch (IOException | RuntimeException e) { return clean(path); }
    }

    private static String clean(String path) {
        String p = path.strip();
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /** {@code "A=1" "B=2"} or {@code A=1 B=2} → [A=1, B=2]. */
    static List<String> environmentAssignments(String value) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(value);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2));
        return out;
    }

    static Integer javaVersion(String javaBinary) {
        Matcher m = JAVA_VERSION.matcher(javaBinary);
        Integer found = null;
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v >= 5 && v <= 40) found = v;
        }
        return found;
    }

    static String pathVersion(String... paths) {
        for (String p : paths) {
            if (p == null) continue;
            Matcher m = PATH_VERSION.matcher(p);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** What matters in {@code conf/server.xml}: the HTTP port and the default host's appBase and autoDeploy. */
    record ServerXml(Integer httpPort, String appBase, Boolean autoDeploy) {
        static ServerXml parse(String xml, String catalinaBase) {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                // No DTDs, external entities or includes: the file comes from a remote server.
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setXIncludeAware(false);
                f.setExpandEntityReferences(false);
                Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                Integer port = null, sslPort = null;
                NodeList connectors = doc.getElementsByTagName("Connector");
                for (int i = 0; i < connectors.getLength(); i++) {
                    Element c = (Element) connectors.item(i);
                    String protocol = c.getAttribute("protocol");
                    if (protocol.toUpperCase(Locale.ROOT).contains("AJP")) continue;
                    Integer p = number(c.getAttribute("port"));
                    if (p == null) continue;
                    boolean ssl = "true".equalsIgnoreCase(c.getAttribute("SSLEnabled"));
                    if (!ssl && port == null) port = p;
                    if (ssl && sslPort == null) sslPort = p;
                }
                String defaultHost = null;
                NodeList engines = doc.getElementsByTagName("Engine");
                if (engines.getLength() > 0) defaultHost = ((Element) engines.item(0)).getAttribute("defaultHost");
                String appBase = "webapps";
                Boolean autoDeploy = null;
                NodeList hosts = doc.getElementsByTagName("Host");
                for (int i = 0; i < hosts.getLength(); i++) {
                    Element h = (Element) hosts.item(i);
                    if (defaultHost != null && !defaultHost.isEmpty() && !defaultHost.equals(h.getAttribute("name")) && hosts.getLength() > 1) continue;
                    if (!h.getAttribute("appBase").isBlank()) appBase = h.getAttribute("appBase").strip();
                    String auto = h.getAttribute("autoDeploy");
                    autoDeploy = auto.isBlank() ? Boolean.TRUE : Boolean.valueOf(auto.strip());
                    break;
                }
                appBase = appBase.replace("${catalina.base}", catalinaBase);
                return new ServerXml(port != null ? port : sslPort, appBase, autoDeploy);
            } catch (Exception e) {
                return new ServerXml(null, "webapps", null);
            }
        }

        private static Integer number(String s) {
            try { int v = Integer.parseInt(s.strip()); return v > 0 && v <= 65535 ? v : null; } catch (NumberFormatException e) { return null; }
        }
    }
}
