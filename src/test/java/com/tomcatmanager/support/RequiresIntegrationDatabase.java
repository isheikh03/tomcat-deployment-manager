package com.tomcatmanager.support;

import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Skips the annotated test class (and subclasses) unless an integration database is available. */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@EnabledIf(value = "com.tomcatmanager.support.IntegrationDatabase#available",
        disabledReason = "No integration database: set TDM_IT_DB_URL or install Docker")
public @interface RequiresIntegrationDatabase { }
