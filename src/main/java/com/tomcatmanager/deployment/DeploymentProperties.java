package com.tomcatmanager.deployment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.nio.file.Path;
import java.time.Duration;

/** Deployment engine settings ({@code app.deployment.*}). */
@ConfigurationProperties("app.deployment")
public record DeploymentProperties(
        @DefaultValue("300") int startupTimeoutSeconds,
        @DefaultValue("2") int consecutiveSuccesses,
        @DefaultValue("PT5S") Duration healthInterval,
        @DefaultValue("PT10S") Duration healthRequestTimeout,
        @DefaultValue("PT15M") Duration transferTimeout,
        @DefaultValue("2") int maxConcurrentDeployments,
        @DefaultValue("4") int maxParallelTargets,
        @DefaultValue("./var/backups") Path backupDir,
        @DefaultValue("10") int backupRetentionPerContext,
        @DefaultValue("10") int productionReleaseNotesMinLength) { }
