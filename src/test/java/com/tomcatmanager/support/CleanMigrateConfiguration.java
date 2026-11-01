package com.tomcatmanager.support;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Rebuilds the integration database from the Flyway migrations once per Spring test context. */
@TestConfiguration(proxyBeanMethods = false)
public class CleanMigrateConfiguration {
    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return (Flyway flyway) -> { flyway.clean(); flyway.migrate(); };
    }
}
