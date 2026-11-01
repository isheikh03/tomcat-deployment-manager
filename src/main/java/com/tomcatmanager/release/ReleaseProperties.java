package com.tomcatmanager.release;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import java.nio.file.Path;

/** Release library settings ({@code app.release.*}). */
@ConfigurationProperties("app.release")
public record ReleaseProperties(
        /* Private directory for WAR files; must not be served by any web server. */
        @DefaultValue("./var/artifacts") Path storageDir,
        @DefaultValue("1GB") DataSize maxUploadSize,
        /* Whether DEPLOYMENT_OPERATOR may upload releases (administrators always can). */
        @DefaultValue("true") boolean operatorUploadEnabled) { }
