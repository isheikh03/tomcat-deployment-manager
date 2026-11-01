package com.tomcatmanager.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chooses the PostgreSQL used by integration tests:
 * <ol>
 *   <li>{@code TDM_IT_DB_URL} (+ {@code TDM_IT_DB_USERNAME}/{@code TDM_IT_DB_PASSWORD}) when set: a dedicated,
 *       disposable database whose name must end in {@code _it} or {@code _test}, because it is wiped;</li>
 *   <li>otherwise a Testcontainers PostgreSQL when Docker is available;</li>
 *   <li>otherwise the tests are skipped (reported as skipped, never as passed).</li>
 * </ol>
 */
public final class IntegrationDatabase {
    private static PostgreSQLContainer container;
    private static Boolean dockerAvailable;

    private IntegrationDatabase() { }

    /** JUnit {@code @EnabledIf} condition. */
    public static boolean available() { return externalUrl() != null || docker(); }

    static String externalUrl() {
        String url = System.getenv("TDM_IT_DB_URL");
        return url == null || url.isBlank() ? null : url.trim();
    }

    private static synchronized boolean docker() {
        if (dockerAvailable == null) {
            try { dockerAvailable = DockerClientFactory.instance().isDockerAvailable(); }
            catch (Throwable e) { dockerAvailable = false; }
        }
        return dockerAvailable;
    }

    public static synchronized void register(DynamicPropertyRegistry registry) throws Exception {
        String url = externalUrl();
        if (url != null) {
            String database = url.replaceAll("[?;].*$", "").replaceAll("^.*/", "");
            if (!database.endsWith("_it") && !database.endsWith("_test"))
                throw new IllegalStateException("Refusing to use database '" + database + "' for integration tests: its name must end in _it or _test because it is wiped.");
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> env("TDM_IT_DB_USERNAME", "postgres"));
            registry.add("spring.datasource.password", () -> env("TDM_IT_DB_PASSWORD", ""));
        } else {
            if (container == null) {
                container = new PostgreSQLContainer("postgres:18-alpine");
                container.start();
            }
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        Path storage = Files.createTempDirectory("tdm-it-artifacts");
        registry.add("app.release.storage-dir", storage::toString);
        Path backups = Files.createTempDirectory("tdm-it-backups");
        registry.add("app.deployment.backup-dir", backups::toString);
        registry.add("app.security.encryption-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        // Never pick up the developer's local .env (real database credentials) during tests.
        registry.add("spring.config.import", () -> "");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
