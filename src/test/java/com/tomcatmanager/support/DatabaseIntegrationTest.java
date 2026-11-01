package com.tomcatmanager.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests against a real PostgreSQL: the schema is rebuilt from the Flyway migrations once
 * per Spring context, and Hibernate validates the entities against it.
 */
@SpringBootTest
@RequiresIntegrationDatabase
@Import(CleanMigrateConfiguration.class)
public abstract class DatabaseIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        IntegrationDatabase.register(registry);
    }
}
