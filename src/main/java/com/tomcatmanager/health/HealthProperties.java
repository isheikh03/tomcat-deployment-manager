package com.tomcatmanager.health;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Application health monitoring settings ({@code app.health.*}). */
@ConfigurationProperties("app.health")
public record HealthProperties(
        @DefaultValue("true") boolean monitoringEnabled,
        @DefaultValue("PT5M") Duration monitorInterval,
        @DefaultValue("PT10S") Duration requestTimeout,
        @DefaultValue("4") int maxConcurrentChecks,
        /* consecutive failed checks before an application counts as UNHEALTHY (before that: DEGRADED) */
        @DefaultValue("2") int unhealthyAfterFailures,
        @DefaultValue("P30D") Duration retention) { }
