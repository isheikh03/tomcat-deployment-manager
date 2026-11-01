package com.tomcatmanager.health;

import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import com.tomcatmanager.connectivity.NetworkTargetPolicy;
import com.tomcatmanager.connectivity.TargetNotAllowedException;
import com.tomcatmanager.connectivity.TlsContextProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * One application health request: GET with a bounded timeout, verified TLS, no redirects, and the
 * outbound network policy applied. Only a bounded prefix of the body is read.
 */
@Component
public class HealthProbe {
    private static final int BODY_LIMIT = 64 * 1024;

    private final NetworkTargetPolicy policy;
    private final HttpClient client;

    public HealthProbe(NetworkTargetPolicy policy, TlsContextProvider tls, HealthProperties properties) {
        this.policy = policy;
        this.client = HttpClient.newBuilder().connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).sslContext(tls.context()).build();
    }

    public record Outcome(boolean healthy, Integer status, String detail, long elapsedMs) { }

    /** Reason the URL cannot be checked at all (invalid, not http(s), blocked by policy, unresolvable), or null. */
    public String urlProblem(String url) {
        if (url == null || url.isBlank()) return "No health URL is configured.";
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getScheme() == null
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")))
                return "The health URL is not an http(s) URL.";
            policy.resolve(uri.getHost());
            return null;
        } catch (IllegalArgumentException e) {
            return "The health URL is invalid.";
        } catch (TargetNotAllowedException e) {
            return e.getMessage();
        } catch (UnknownHostException e) {
            return "Health URL host could not be resolved.";
        }
    }

    public Outcome check(String url, HealthExpectation expectation, Duration timeout) throws InterruptedException {
        Instant started = Instant.now();
        String problem = urlProblem(url);
        if (problem != null) return new Outcome(false, null, problem, 0);
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Accept", "application/json, text/plain, */*").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String body;
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(expectation.bodyText() == null ? 4096 : BODY_LIMIT);
                body = expectation.bodyText() == null ? null : new String(bytes, StandardCharsets.UTF_8);
            }
            long elapsed = elapsed(started);
            if (!expectation.statusAccepted(status))
                return new Outcome(false, status, "HTTP " + status + (status >= 300 && status < 400 ? " (redirects are not followed)" : "")
                        + "; expected " + expectation.statusSpec() + ".", elapsed);
            if (expectation.bodyText() != null && !body.contains(expectation.bodyText()))
                return new Outcome(false, status, "HTTP " + status + " but the response does not contain \"" + expectation.bodyText() + "\".", elapsed);
            return new Outcome(true, status, "HTTP " + status + (expectation.bodyText() == null ? "" : ", contains \"" + expectation.bodyText() + "\"") + ".", elapsed);
        } catch (IOException e) {
            return new Outcome(false, null, DiagnosticSanitizer.describe(e), elapsed(started));
        }
    }

    private static long elapsed(Instant started) { return Duration.between(started, Instant.now()).toMillis(); }
}
