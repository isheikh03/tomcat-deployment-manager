package com.tomcatmanager.users;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

/**
 * "Remember me" on the sign-in page, with Spring Security's persistent tokens: the browser keeps a random series and
 * token (renewed on every use, so a stolen cookie is detected when both are used), stored in {@code persistent_logins}.
 * The tokens can be revoked: sign-out, a password change or reset, disabling the account or changing its role delete them
 * ({@link UserAccountService#forgetRememberedLogins}). Disabled or locked accounts are refused by the user details check.
 * With {@code app.security.remember-me-validity=PT0S} the option is switched off.
 */
@Configuration
public class RememberMeConfig {
    /** Checkbox name on the sign-in form. */
    public static final String PARAMETER = "remember-me";
    public static final String COOKIE = "TDM_REMEMBER_ME";

    @Bean
    PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    /** Null (no remember-me) when the validity is zero. */
    @Bean
    RememberMeServices rememberMeServices(UserDetailsService userDetails, PersistentTokenRepository tokens, SecurityProperties properties,
                                          UserAccountService accounts, @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
        long seconds = properties.rememberMeValidity().toSeconds();
        if (seconds <= 0) return null;
        // The key only signs the in-memory authentication of this run; the tokens themselves live in the database.
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Services services = new Services(HexFormat.of().formatHex(key), userDetails, tokens, accounts);
        services.setParameter(PARAMETER);
        services.setCookieName(COOKIE);
        services.setTokenValiditySeconds((int) Math.min(seconds, Integer.MAX_VALUE));
        if (secureCookie) services.setUseSecureCookie(true); // otherwise secure whenever the request came over HTTPS
        return services;
    }

    /** Removes remembered sign-ins that have expired (they would be refused anyway). */
    @Bean
    ExpiredTokenPurge expiredTokenPurge(JdbcTemplate jdbc, SecurityProperties properties) { return new ExpiredTokenPurge(jdbc, properties); }

    /** Deletes expired remember-me tokens every night. */
    public static final class ExpiredTokenPurge {
        private final JdbcTemplate jdbc;
        private final SecurityProperties properties;
        ExpiredTokenPurge(JdbcTemplate jdbc, SecurityProperties properties) { this.jdbc = jdbc; this.properties = properties; }

        @Scheduled(cron = "0 17 3 * * *")
        public void purge() {
            Instant oldest = Instant.now().minus(properties.rememberMeValidity());
            jdbc.update("delete from persistent_logins where last_used < ?", Timestamp.from(oldest));
        }
    }

    /** Also enforces a pending password change for a user signed in by their remember-me cookie. */
    static final class Services extends PersistentTokenBasedRememberMeServices {
        private final UserAccountService accounts;

        Services(String key, UserDetailsService userDetails, PersistentTokenRepository tokens, UserAccountService accounts) {
            super(key, userDetails, tokens);
            this.accounts = accounts;
        }

        @Override
        protected UserDetails processAutoLoginCookie(String[] cookieTokens, HttpServletRequest request, HttpServletResponse response) {
            UserDetails user = super.processAutoLoginCookie(cookieTokens, request, response);
            if (accounts.findByUsername(user.getUsername()).map(AppUser::isMustChangePassword).orElse(false))
                request.getSession(true).setAttribute(PasswordChangeEnforcement.SESSION_FLAG, true);
            return user;
        }
    }
}
