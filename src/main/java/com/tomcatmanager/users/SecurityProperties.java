package com.tomcatmanager.users;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Account settings ({@code app.security.*}).
 *
 * @param devUsers           development only: seed admin/operator/viewer with well-known passwords when no user exists
 * @param bootstrapAdmin     first administrator created from configuration when no user exists (password must be changed at first sign-in)
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue("false") boolean devUsers,
        @DefaultValue Bootstrap bootstrapAdmin,
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("PT15M") Duration lockoutDuration,
        @DefaultValue("8") int minPasswordLength) {

    public record Bootstrap(String username, String password) {
        public boolean configured() { return username != null && !username.isBlank() && password != null && !password.isBlank(); }
    }
}
