package com.tomcatmanager.connectivity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.List;

/**
 * Remote connectivity settings ({@code app.connection.*}). Defaults are restrictive:
 * loopback, link-local (cloud metadata) and wildcard addresses are refused, TLS
 * certificates are verified with the JVM trust store unless a dedicated store is configured.
 */
@ConfigurationProperties("app.connection")
public record ConnectivityProperties(
        @DefaultValue("15") int defaultTimeoutSeconds,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("4") int maxConcurrentTests,
        @DefaultValue("PT24H") Duration resultStaleAfter,
        @DefaultValue("PT1H") Duration hostKeyTrustWindow,
        @DefaultValue TargetPolicy targetPolicy,
        @DefaultValue Tls tls) {

    public record TargetPolicy(
            @DefaultValue("false") boolean allowLoopback,
            @DefaultValue("false") boolean allowLinkLocal,
            @DefaultValue("true") boolean allowPrivate,
            @DefaultValue List<String> allowedCidrs,
            @DefaultValue List<String> deniedCidrs) { }

    public record Tls(
            String trustStore,
            String trustStorePassword,
            @DefaultValue("PKCS12") String trustStoreType) { }
}
