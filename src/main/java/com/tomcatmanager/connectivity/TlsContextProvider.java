package com.tomcatmanager.connectivity;

import org.springframework.stereotype.Component;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * SSL context for outbound HTTPS. Certificates and host names are always verified: either
 * against the JVM default trust store or, when {@code app.connection.tls.trust-store} is set,
 * against that store only (it must then contain every CA the Manager endpoints use).
 */
@Component
public class TlsContextProvider {
    private final SSLContext context;
    private final String description;

    public TlsContextProvider(ConnectivityProperties properties) {
        ConnectivityProperties.Tls tls = properties.tls();
        try {
            if (tls.trustStore() == null || tls.trustStore().isBlank()) {
                context = SSLContext.getDefault();
                description = "JVM default trust store";
            } else {
                KeyStore store = KeyStore.getInstance(tls.trustStoreType());
                char[] password = tls.trustStorePassword() == null ? null : tls.trustStorePassword().toCharArray();
                try (InputStream in = Files.newInputStream(Path.of(tls.trustStore()))) { store.load(in, password); }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(store);
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tmf.getTrustManagers(), null);
                context = ctx;
                description = "trust store " + Path.of(tls.trustStore()).getFileName();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise TLS trust store from app.connection.tls.trust-store: " + DiagnosticSanitizer.describe(e), e);
        }
    }

    public SSLContext context() { return context; }
    public String description() { return description; }
}
