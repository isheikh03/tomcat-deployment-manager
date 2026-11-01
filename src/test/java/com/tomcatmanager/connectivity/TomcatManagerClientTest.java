package com.tomcatmanager.connectivity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the Manager client against real local HTTP/HTTPS servers emulating Tomcat's text interface. */
class TomcatManagerClientTest {
    private static final String USER = "deployer";
    private static final String PASSWORD = "manager-pass-123";
    private static final String STOREPASS = "changeit";

    @TempDir static Path certs;
    private static Path serverStore, wrongNameStore, trustStore;

    private HttpServer server;
    private ServerSocket rawServer;

    @BeforeAll static void createCertificates() throws Exception {
        serverStore = certs.resolve("server.p12");
        wrongNameStore = certs.resolve("wrong-name.p12");
        trustStore = certs.resolve("trust.p12");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-keystore", serverStore.toString(), "-storepass", STOREPASS, "-storetype", "PKCS12");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=wrong.example",
                "-ext", "SAN=dns:wrong.example", "-validity", "2", "-keystore", wrongNameStore.toString(), "-storepass", STOREPASS, "-storetype", "PKCS12");
        Path cert = certs.resolve("server.crt"), wrongCert = certs.resolve("wrong.crt");
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", serverStore.toString(), "-storepass", STOREPASS, "-file", cert.toString());
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", wrongNameStore.toString(), "-storepass", STOREPASS, "-file", wrongCert.toString());
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", cert.toString(), "-keystore", trustStore.toString(), "-storepass", STOREPASS, "-storetype", "PKCS12");
        keytool("-importcert", "-noprompt", "-alias", "wrong", "-file", wrongCert.toString(), "-keystore", trustStore.toString(), "-storepass", STOREPASS, "-storetype", "PKCS12");
    }

    private static void keytool(String... args) throws Exception {
        String exe = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> command = new java.util.ArrayList<>(List.of(exe)); command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException("keytool failed: " + out);
    }

    @AfterEach void stop() throws IOException {
        if (server != null) server.stop(0);
        if (rawServer != null) rawServer.close();
    }

    /** Tomcat-like behaviour: Basic auth, manager-script role check, text responses. */
    private static HttpHandler manager(boolean hasScriptRole) {
        return exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String expected = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(auth)) { respond(exchange, 401, "<html>401 Unauthorized</html>"); return; }
            if (!hasScriptRole) { respond(exchange, 403, "<html>403 Access Denied</html>"); return; }
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/serverinfo")) respond(exchange, 200, "OK - Server info\nTomcat Version: [Apache Tomcat/10.1.30]\nJVM Version: [21.0.4]\n");
            else if (path.endsWith("/list")) respond(exchange, 200, "OK - Listed applications for virtual host [localhost]\n/:running:0:ROOT\n/manager:running:0:manager\n");
            else respond(exchange, 404, "Not found");
        };
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private int startHttp(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", handler);
        server.start();
        return server.getAddress().getPort();
    }

    private int startHttps(Path keyStore, HttpHandler handler) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) { ks.load(in, STOREPASS.toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STOREPASS.toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(ssl));
        https.createContext("/", handler);
        https.start();
        server = https;
        return https.getAddress().getPort();
    }

    private static TomcatManagerClient client(boolean useTrustStore) {
        ConnectivityProperties.Tls tls = useTrustStore ? new ConnectivityProperties.Tls(trustStore.toString(), STOREPASS, "PKCS12") : new ConnectivityProperties.Tls(null, null, "PKCS12");
        ConnectivityProperties props = new ConnectivityProperties(15, 2, 4, Duration.ofHours(24), Duration.ofHours(1),
                new ConnectivityProperties.TargetPolicy(true, false, true, List.of(), List.of()), tls);
        return new TomcatManagerClient(new TlsContextProvider(props));
    }

    private static ProbeReport probe(TomcatManagerClient client, String scheme, String host, int port, String user, String password) {
        ProbeReport report = new ProbeReport();
        client.probe(new TomcatManagerClient.ManagerTarget(scheme, host, port, "/manager/text", user, password, Duration.ofSeconds(3)), report);
        return report;
    }

    @Test void validCredentialsWithScriptRoleSucceed() throws Exception {
        int port = startHttp(manager(true));
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", port, USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.statusOf(CheckType.MANAGER_AUTHENTICATION)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.statusOf(CheckType.MANAGER_PERMISSIONS)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.serverInfo()).contains("Apache Tomcat/10.1.30");
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("2 deployed application"));
    }

    @Test void wrongPasswordIsAnAuthenticationFailure() throws Exception {
        int port = startHttp(manager(true));
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", port, USER, "not-the-password");
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.SUCCESS);
        assertThat(report.statusOf(CheckType.MANAGER_AUTHENTICATION)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).allSatisfy(c -> assertThat(String.valueOf(c.getDetail())).doesNotContain("not-the-password"));
    }

    @Test void missingRoleIsAPermissionFailure() throws Exception {
        int port = startHttp(manager(false));
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", port, USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_PERMISSIONS)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("manager-script"));
    }

    @Test void http200WithFailBodyIsNotSuccess() throws Exception {
        int port = startHttp(exchange -> respond(exchange, 200, "FAIL - Unknown command [/serverinfo]\n"));
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", port, USER, PASSWORD);
        assertThat(report.outcome()).isEqualTo(CheckStatus.FAILED);
        assertThat(report.statusOf(CheckType.MANAGER_PERMISSIONS)).isEqualTo(CheckStatus.FAILED);
    }

    @Test void htmlManagerPageIsRejected() throws Exception {
        int port = startHttp(exchange -> respond(exchange, 200, "<!DOCTYPE html><html><body>Tomcat Web Application Manager</body></html>"));
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", port, USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("/manager/text"));
    }

    @Test void wrongPathIsReportedAsInvalidEndpoint() throws Exception {
        int port = startHttp(exchange -> respond(exchange, 404, "Not Found"));
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", port, USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("HTTP 404"));
    }

    @Test void redirectsAreNotFollowed() throws Exception {
        int port = startHttp(exchange -> { exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", port, USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("not followed"));
    }

    @Test void slowServerTimesOut() throws Exception {
        int port = startHttp(exchange -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "OK - late");
        });
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", port, USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("within 3s"));
    }

    @Test void connectionClosedWithoutResponseIsReported() throws Exception {
        rawServer = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            try { while (!rawServer.isClosed()) { try (Socket s = rawServer.accept()) { s.getInputStream().read(); } } } catch (IOException ignored) { }
        });
        acceptor.setDaemon(true); acceptor.start();
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", rawServer.getLocalPort(), USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.statusOf(CheckType.MANAGER_AUTHENTICATION)).isEqualTo(CheckStatus.SKIPPED);
    }

    @Test void refusedConnectionIsReported() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { freePort = socket.getLocalPort(); }
        ProbeReport report = probe(client(false), "HTTP", "127.0.0.1", freePort, USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.FAILED);
    }

    @Test void untrustedCertificateFailsTls() throws Exception {
        int port = startHttps(serverStore, manager(true));
        ProbeReport report = probe(client(false), "HTTPS", "localhost", port, USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("not trusted"));
    }

    @Test void trustedCertificateSucceeds() throws Exception {
        int port = startHttps(serverStore, manager(true));
        ProbeReport report = probe(client(true), "HTTPS", "localhost", port, USER, PASSWORD);
        assertThat(report.outcome()).isEqualTo(CheckStatus.SUCCESS);
    }

    @Test void certificateForAnotherHostFailsHostnameVerification() throws Exception {
        int port = startHttps(wrongNameStore, manager(true));
        ProbeReport report = probe(client(true), "HTTPS", "localhost", port, USER, PASSWORD);
        assertThat(report.statusOf(CheckType.MANAGER_ENDPOINT)).isEqualTo(CheckStatus.FAILED);
        assertThat(report.checks()).anySatisfy(c -> assertThat(String.valueOf(c.getDetail())).contains("hostname verification failed"));
    }

    @Test void commandUriEncodesBasePath() {
        var target = new TomcatManagerClient.ManagerTarget("HTTPS", "tomcat.example", 8443, "manager/text/", "u", "p", Duration.ofSeconds(1));
        assertThat(TomcatManagerClient.commandUri(target, "list").toString()).isEqualTo("https://tomcat.example:8443/manager/text/list");
        assertThat(target.toString()).doesNotContain("p,").contains("REDACTED");
    }
}
