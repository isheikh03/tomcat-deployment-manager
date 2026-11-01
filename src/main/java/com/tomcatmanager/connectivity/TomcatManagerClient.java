package com.tomcatmanager.connectivity;

import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for the Tomcat Manager text interface ({@code /manager/text}). The connection test
 * only issues read-only commands ({@code serverinfo}, {@code list}), which require the
 * {@code manager-script} role, the same role needed for deploy/undeploy.
 */
@Component
public class TomcatManagerClient {
    static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final TlsContextProvider tls;
    private final Map<Long, HttpClient> clientsByTimeout = new ConcurrentHashMap<>();

    public TomcatManagerClient(TlsContextProvider tls) { this.tls = tls; }

    public record ManagerTarget(String scheme, String host, int port, String basePath, String username, String password, Duration timeout) {
        @Override public String toString() { return "ManagerTarget[" + scheme + "://" + host + ":" + port + basePath + ", credentials=REDACTED]"; }
    }

    public record ManagerResponse(int status, String body, String location) {
        public boolean ok() { return status == 200 && body != null && body.startsWith("OK -"); }
    }

    public static URI commandUri(ManagerTarget target, String command) {
        String base = target.basePath() == null || target.basePath().isBlank() ? "/manager/text" : target.basePath().trim();
        if (!base.startsWith("/")) base = "/" + base;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            return new URI(target.scheme().toLowerCase(), null, target.host(), target.port(), base + "/" + command, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Manager URL: " + DiagnosticSanitizer.describe(e));
        }
    }

    /** Sends a read-only GET command. Redirects are never followed. */
    public ManagerResponse get(ManagerTarget target, String command) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(commandUri(target, command))
                .timeout(target.timeout()).header("Accept", "text/plain").GET();
        if (target.username() != null && !target.username().isBlank() && target.password() != null) {
            String token = Base64.getEncoder().encodeToString((target.username() + ":" + target.password()).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + token);
        }
        HttpResponse<InputStream> response = client(target.timeout()).send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        String body;
        try (InputStream in = response.body()) { body = new String(in.readNBytes(MAX_RESPONSE_BYTES), StandardCharsets.UTF_8); }
        return new ManagerResponse(response.statusCode(), body, response.headers().firstValue("Location").orElse(null));
    }

    /** Deployed contexts reported by {@code list}: context path → state ("running", "stopped"). */
    public Map<String, String> listApplications(ManagerTarget target) throws IOException, InterruptedException {
        ManagerResponse response = get(target, "list");
        if (response.status() == 401) throw new RemoteOperationException("Tomcat Manager rejected the credentials (HTTP 401).");
        if (response.status() == 403) throw new RemoteOperationException("Tomcat Manager denied access (HTTP 403): manager-script role or RemoteAddrValve.");
        if (!response.ok()) throw new RemoteOperationException("Tomcat Manager 'list' failed (HTTP " + response.status() + "): " + firstLine(response.body()));
        Map<String, String> apps = new java.util.LinkedHashMap<>();
        response.body().lines().skip(1).filter(l -> !l.isBlank()).forEach(line -> {
            String[] parts = line.split(":", 4);
            if (parts.length >= 2) apps.put(parts[0], parts[1]);
        });
        return apps;
    }

    /**
     * Streams a WAR to {@code /deploy?path=...&update=true}. A transport failure after the request
     * started is reported as an {@link IOException}; the caller must treat the outcome as unknown.
     */
    public ManagerResponse deploy(ManagerTarget target, String contextPath, String version, java.util.function.Supplier<InputStream> war,
                                  long size, Duration requestTimeout) throws IOException, InterruptedException {
        String query = "path=" + contextPath + "&update=true" + (version == null ? "" : "&version=" + version);
        URI base = commandUri(target, "deploy");
        URI uri;
        try { uri = new URI(base.getScheme(), null, base.getHost(), base.getPort(), base.getPath(), query, null); }
        catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid deploy URL: " + DiagnosticSanitizer.describe(e)); }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                .header("Content-Type", "application/octet-stream").header("Accept", "text/plain")
                .PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(war), size));
        String token = Base64.getEncoder().encodeToString((target.username() + ":" + target.password()).getBytes(StandardCharsets.UTF_8));
        request.header("Authorization", "Basic " + token);
        HttpResponse<InputStream> response = client(target.timeout()).send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        String body;
        try (InputStream in = response.body()) { body = new String(in.readNBytes(MAX_RESPONSE_BYTES), StandardCharsets.UTF_8); }
        return new ManagerResponse(response.statusCode(), body, response.headers().firstValue("Location").orElse(null));
    }

    public static String firstLineOf(String body) { return firstLine(body); }

    /** Connection test: endpoint reachability, authentication, and manager-script permission. Never modifies applications. */
    public void probe(ManagerTarget target, ProbeReport report) {
        boolean hasCredentials = target.username() != null && !target.username().isBlank() && target.password() != null && !target.password().isEmpty();
        String url = commandUri(target, "").toString().replaceAll("/$", "");
        ManagerResponse info;
        try {
            info = get(target, "serverinfo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report.failed(CheckType.MANAGER_ENDPOINT, "Connection test was interrupted.");
            report.skip("Interrupted.", CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
            return;
        } catch (IOException e) {
            report.failed(CheckType.MANAGER_ENDPOINT, transportFailure(target, e));
            report.skip("Manager endpoint unreachable.", CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
            return;
        }

        String transport = "https".equalsIgnoreCase(target.scheme()) ? "HTTPS, certificate verified via " + tls.description()
                : "plain HTTP: credentials are not encrypted in transit";
        int status = info.status();
        if (status >= 300 && status < 400) {
            report.failed(CheckType.MANAGER_ENDPOINT, "Manager answered with redirect " + status + (info.location() == null ? "" : " to " + info.location())
                    + ". Redirects are not followed; configure the final Manager URL (for example the HTTPS address).");
            report.skip("Endpoint misconfigured.", CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
            return;
        }
        if (status == 404) {
            report.failed(CheckType.MANAGER_ENDPOINT, "No Manager found at " + url + " (HTTP 404). The text interface is normally /manager/text.");
            report.skip("Endpoint misconfigured.", CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
            return;
        }
        if (status == 401) {
            report.success(CheckType.MANAGER_ENDPOINT, "Manager endpoint reachable (" + transport + ").");
            report.failed(CheckType.MANAGER_AUTHENTICATION, hasCredentials ? "Credentials for '" + target.username() + "' were rejected (HTTP 401)."
                    : "No Manager username/password is stored for this instance.");
            report.skip("Not authenticated.", CheckType.MANAGER_PERMISSIONS);
            return;
        }
        if (status == 403) {
            report.success(CheckType.MANAGER_ENDPOINT, "Manager endpoint reachable (" + transport + ").");
            report.warning(CheckType.MANAGER_AUTHENTICATION, "Access was refused before the account could be confirmed (HTTP 403).");
            report.failed(CheckType.MANAGER_PERMISSIONS, "Access denied: the account lacks the 'manager-script' role, or the Manager's RemoteAddrValve does not allow this server's address.");
            return;
        }
        if (status != 200) {
            report.failed(CheckType.MANAGER_ENDPOINT, "Unexpected HTTP " + status + " from " + url + ": " + firstLine(info.body()));
            report.skip("Endpoint error.", CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
            return;
        }
        if (looksLikeHtml(info.body())) {
            report.failed(CheckType.MANAGER_ENDPOINT, "The endpoint returned an HTML page, not the Manager text interface. Use the /manager/text path, not /manager/html.");
            report.skip("Endpoint misconfigured.", CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
            return;
        }
        if (!info.ok()) {
            report.success(CheckType.MANAGER_ENDPOINT, "Manager endpoint reachable (" + transport + ").");
            report.success(CheckType.MANAGER_AUTHENTICATION, "Credentials accepted.");
            report.failed(CheckType.MANAGER_PERMISSIONS, "Manager reported failure for 'serverinfo': " + firstLine(info.body()));
            return;
        }

        String version = extract(info.body(), "Tomcat Version:");
        String jvm = extract(info.body(), "JVM Version:");
        report.serverInfo(version == null ? "Apache Tomcat (version not reported)" : version + (jvm == null ? "" : ", JVM " + jvm));
        report.success(CheckType.MANAGER_ENDPOINT, "Manager text interface reachable at " + url + " (" + transport + ")"
                + (version == null ? "." : "; " + version + "."));
        report.success(CheckType.MANAGER_AUTHENTICATION, "Authenticated as '" + target.username() + "'.");

        try {
            ManagerResponse list = get(target, "list");
            if (list.ok()) {
                long apps = list.body().lines().skip(1).filter(l -> !l.isBlank()).count();
                report.success(CheckType.MANAGER_PERMISSIONS, "manager-script access confirmed: 'list' returned " + apps + " deployed application(s).");
            } else {
                report.failed(CheckType.MANAGER_PERMISSIONS, "'list' command failed (HTTP " + list.status() + "): " + firstLine(list.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report.failed(CheckType.MANAGER_PERMISSIONS, "Connection test was interrupted.");
        } catch (IOException e) {
            report.failed(CheckType.MANAGER_PERMISSIONS, "'list' command failed: " + transportFailure(target, e));
        }
    }

    static String transportFailure(ManagerTarget target, IOException e) {
        String where = target.host() + ":" + target.port();
        if (e instanceof HttpConnectTimeoutException) return "Timed out after " + target.timeout().toSeconds() + "s connecting to " + where + ".";
        if (e instanceof HttpTimeoutException) return "No response from " + where + " within " + target.timeout().toSeconds() + "s.";
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CertificateExpiredException) return "TLS certificate of " + where + " has expired.";
            if (t instanceof CertificateNotYetValidException) return "TLS certificate of " + where + " is not yet valid.";
            String m = t.getMessage() == null ? "" : t.getMessage();
            if (m.contains("No subject alternative") || m.contains("No name matching"))
                return "TLS hostname verification failed: the certificate presented by " + where + " is not issued for '" + target.host() + "'.";
            if (m.contains("PKIX path") || m.contains("unable to find valid certification path"))
                return "TLS certificate of " + where + " is not trusted (self-signed or unknown CA). Add its CA to the configured trust store; verification is never disabled.";
        }
        if (e instanceof SSLHandshakeException || e instanceof SSLException) {
            if ("http".equalsIgnoreCase(target.scheme())) return "Unexpected TLS error on a plain HTTP endpoint: " + DiagnosticSanitizer.describe(e);
            return "TLS handshake with " + where + " failed: " + DiagnosticSanitizer.describe(e) + ". Is the port serving HTTPS?";
        }
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof ConnectException) return "Connection refused or host unreachable at " + where + ".";
        String m = DiagnosticSanitizer.describe(e);
        if (m != null && m.contains("HTTP/1.1 header parser received no bytes"))
            return "Connection to " + where + " was closed without a response. If the port serves HTTPS, select the HTTPS protocol.";
        return "Request to " + where + " failed: " + m;
    }

    private HttpClient client(Duration timeout) {
        return clientsByTimeout.computeIfAbsent(timeout.toMillis(), ms -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(ms))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(tls.context())
                .build());
    }

    private static boolean looksLikeHtml(String body) {
        String head = body == null ? "" : body.stripLeading().toLowerCase();
        return head.startsWith("<!doctype html") || head.startsWith("<html");
    }

    private static String firstLine(String body) {
        if (body == null || body.isBlank()) return "(empty response)";
        return DiagnosticSanitizer.clean(body.lines().findFirst().orElse(""), 300);
    }

    private static String extract(String body, String label) {
        return body.lines().filter(l -> l.startsWith(label)).map(l -> l.substring(label.length()).trim()).findFirst().orElse(null);
    }
}
