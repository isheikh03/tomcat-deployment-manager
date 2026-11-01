package com.tomcatmanager.deployment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for Tomcat: the Manager text interface ({@code /manager/text/serverinfo|list|deploy})
 * and application health endpoints ({@code <context>/health}). Healthy contexts are controlled by the test.
 */
final class FakeTomcat implements AutoCloseable {
    static final String USER = "deployer";
    static final String PASSWORD = "manager-secret";

    private final HttpServer server;
    final Map<String, String> deployedSha = new ConcurrentHashMap<>();   // context -> SHA-256 of received WAR
    final Map<String, String> states = new ConcurrentHashMap<>();        // context -> running/stopped
    final Set<String> unhealthy = ConcurrentHashMap.newKeySet();         // contexts whose health endpoint fails
    final AtomicInteger deployRequests = new AtomicInteger();
    volatile long healthDelayMs = 0;

    FakeTomcat() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/manager/text/", this::manager);
        server.createContext("/", this::health);
        server.start();
    }

    int port() { return server.getAddress().getPort(); }
    String managerUrl() { return "http://127.0.0.1:" + port() + "/manager/text"; }
    String healthUrl() { return "http://127.0.0.1:" + port() + "{context}/health"; }

    private void manager(HttpExchange ex) throws IOException {
        String expected = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) { respond(ex, 401, "401"); return; }
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/serverinfo")) { respond(ex, 200, "OK - Server info\nTomcat Version: [Apache Tomcat/10.1.30]\nJVM Version: [21.0.4]\n"); return; }
        if (path.endsWith("/list")) {
            StringBuilder sb = new StringBuilder("OK - Listed applications for virtual host [localhost]\n");
            states.forEach((ctx, state) -> sb.append(ctx).append(':').append(state).append(":0:x\n"));
            respond(ex, 200, sb.toString());
            return;
        }
        if (path.endsWith("/deploy") && ex.getRequestMethod().equals("PUT")) {
            deployRequests.incrementAndGet();
            String query = ex.getRequestURI().getQuery();
            String context = query.replaceAll(".*path=([^&]*).*", "$1");
            try (InputStream in = ex.getRequestBody()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(in.readAllBytes());
                deployedSha.put(context, HexFormat.of().formatHex(digest.digest()));
            } catch (Exception e) { respond(ex, 500, "FAIL - " + e); return; }
            states.put(context, "running");
            respond(ex, 200, "OK - Deployed application at context path [" + context + "]\n");
            return;
        }
        respond(ex, 404, "not found");
    }

    private void health(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.endsWith("/health")) { respond(ex, 404, "no"); return; }
        if (healthDelayMs > 0) try { Thread.sleep(healthDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        String context = path.substring(0, path.length() - "/health".length());
        respond(ex, unhealthy.contains(context.isEmpty() ? "/" : context) ? 503 : 200, "status");
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    @Override public void close() { server.stop(0); }
}
