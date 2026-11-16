package com.tomcatmanager.config;

import com.tomcatmanager.settings.SettingsService;
import com.tomcatmanager.users.LoginSuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Role model: ADMIN manages users, settings, infrastructure and releases and reads the audit log;
 * DEPLOYMENT_OPERATOR runs connection tests, uploads releases (when the policy allows), deploys,
 * rolls back and checks health; VIEWER reads only. Rules are enforced here on the server,
 * independent of which buttons the UI shows. Accounts are stored in the database (BCrypt).
 */
@Configuration
public class SecurityConfig {
    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final String ADMIN = "ADMIN";
    private static final String OPERATOR = "DEPLOYMENT_OPERATOR";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry,
                                            ObjectProvider<LoginSuccessHandler> loginSuccess, ObjectProvider<SettingsService> settings,
                                            ObjectProvider<org.springframework.security.web.authentication.RememberMeServices> rememberMe,
                                            @Value("${app.release.operator-upload-enabled:true}") boolean operatorUploadDefault) throws Exception {
        PathPatternRequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        // Release upload: administrators always; operators while the (runtime) policy allows it.
        AuthorizationManager<RequestAuthorizationContext> uploaders = (auth, context) -> {
            Set<String> roles = auth.get().getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
            SettingsService s = settings.getIfAvailable();
            boolean operatorsMayUpload = s == null ? operatorUploadDefault : s.operatorUploadEnabled();
            return new AuthorizationDecision(auth.get().isAuthenticated()
                    && (roles.contains("ROLE_" + ADMIN) || (operatorsMayUpload && roles.contains("ROLE_" + OPERATOR))));
        };
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/assets/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/login", "/setup").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // System-wide settings (policies, email, credential re-encryption) belong to super users.
                        .requestMatchers("/admin/settings", "/admin/settings/**").hasRole(SUPER_ADMIN)
                        // Creating, renaming and deleting teams: super users. Administrators read their own teams.
                        .requestMatchers("/admin/teams/new", "/admin/teams/save", "/admin/teams/*/edit", "/admin/teams/*/delete").hasRole(SUPER_ADMIN)
                        // User management and the audit log: administrators, limited to their teams by the controllers.
                        .requestMatchers("/admin/**").hasRole(ADMIN)
                        // Testing unsaved settings is part of configuring infrastructure.
                        .requestMatchers(HttpMethod.POST, "/api/connectivity-tests/drafts/**").hasRole(ADMIN)
                        // Connection tests: operators may run them; viewers may only read results.
                        .requestMatchers(HttpMethod.POST, "/api/connectivity-tests/**").hasAnyRole(ADMIN, OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/api/hosts/*/host-key/trust").hasRole(ADMIN)
                        // Releases: upload per policy; integrity checks by operators; reading by everyone signed in.
                        .requestMatchers(HttpMethod.POST, "/api/releases").access(uploaders)
                        // Quick Deployment uploads a release too, so it follows the same policy.
                        .requestMatchers(HttpMethod.POST, "/api/quick-deployments/release").access(uploaders)
                        .requestMatchers(HttpMethod.POST, "/api/releases/*/verify").hasAnyRole(ADMIN, OPERATOR)
                        // Deployments: operators preview, start and cancel; only administrators inspect and reconcile unknown outcomes.
                        .requestMatchers(HttpMethod.POST, "/api/deployments/*/targets/*/reconcile", "/api/deployments/*/targets/*/inspect").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/deployments", "/api/deployments/preview", "/api/deployments/*/cancel").hasAnyRole(ADMIN, OPERATOR)
                        // Rollbacks: authorized operators preview and start them.
                        .requestMatchers(HttpMethod.POST, "/api/rollbacks", "/api/rollbacks/preview").hasAnyRole(ADMIN, OPERATOR)
                        // Backups: operators verify integrity; only administrators delete.
                        .requestMatchers(HttpMethod.POST, "/api/backups/*/verify").hasAnyRole(ADMIN, OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/api/backups/*/delete").hasRole(ADMIN)
                        // Health: manual checks by operators (outbound requests); reading by everyone signed in.
                        .requestMatchers(HttpMethod.POST, "/api/health/instances/*/check").hasAnyRole(ADMIN, OPERATOR)
                        // Every signed-in user marks their own notifications as read.
                        .requestMatchers(HttpMethod.POST, "/api/notifications/read", "/api/notifications/*/read").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers("/api/**").hasRole(ADMIN)
                        // Infrastructure configuration is administrator-only.
                        .requestMatchers(HttpMethod.POST, "/infrastructure/**").hasRole(ADMIN)
                        .requestMatchers("/infrastructure/*/new", "/infrastructure/*/*/edit", "/infrastructure/hosts/*/discover").hasRole(ADMIN)
                        // Application registry: administrators manage it; everyone signed in can read it.
                        .requestMatchers(HttpMethod.POST, "/applications/**").hasRole(ADMIN)
                        .requestMatchers("/applications/new", "/applications/*/edit").hasRole(ADMIN)
                        // Release storage is shared by all teams (one WAR file can serve releases of several teams), so checking it
                        // and removing release files is for super users only.
                        .requestMatchers("/releases/cleanup", "/releases/storage-check", "/releases/*/remove-file").hasRole(SUPER_ADMIN)
                        // Bulk-deleting backups: administrators, limited to their teams' instances by the controller.
                        .requestMatchers("/backups/cleanup").hasRole(ADMIN)
                        // Release library: upload per policy, metadata by operators, archive/delete by administrators
                        // (each limited to the application's team by the controllers).
                        .requestMatchers("/releases/upload").access(uploaders)
                        .requestMatchers(HttpMethod.POST, "/releases/*/metadata").hasAnyRole(ADMIN, OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/releases/**").hasRole(ADMIN)
                        // Deployment history is readable by everyone signed in; starting one needs an operator.
                        .requestMatchers("/deployments/new").hasAnyRole(ADMIN, OPERATOR)
                        .requestMatchers("/deployments/quick").access(uploaders)
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").failureUrl("/login?error").permitAll().successHandler(successHandler(loginSuccess)))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID", com.tomcatmanager.users.RememberMeConfig.COOKIE))
                // "Remember me" (persistent tokens, see RememberMeConfig); absent in web-layer tests and when switched off.
                .rememberMe(remember -> { var services = rememberMe.getIfAvailable(); if (services != null) remember.rememberMeServices(services); else remember.disable(); })
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), api)
                        .accessDeniedHandler(accessDeniedHandler()))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        // Tracked so that disabling a user or changing their role or password ends their sessions.
                        .maximumSessions(5).sessionRegistry(sessionRegistry).expiredUrl("/login?expired"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                // The UI template's stylesheet loads its Lato/Roboto web fonts from Google Fonts; everything else is served locally.
                                "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com 'unsafe-inline'; "
                                        + "img-src 'self' data:; font-src 'self' data: https://fonts.gstatic.com; connect-src 'self'; frame-ancestors 'none'; form-action 'self'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
                .build();
    }

    /**
     * A sign-in rejected by the CSRF check (a stale login page, or a session cookie the browser did not keep)
     * returns to the login page with an explanation. Nothing is authenticated; all other denials stay 403.
     */
    private static AccessDeniedHandler accessDeniedHandler() {
        AccessDeniedHandler standard = new AccessDeniedHandlerImpl();
        return (request, response, denied) -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (denied instanceof CsrfException && HttpMethod.POST.matches(request.getMethod()) && path.equals("/login")) {
                response.sendRedirect(request.getContextPath() + "/login?retry");
                return;
            }
            standard.handle(request, response, denied);
        };
    }

    /** Sends users with a temporary password to the password page; falls back to the dashboard (e.g. in web-layer tests). */
    private static AuthenticationSuccessHandler successHandler(ObjectProvider<LoginSuccessHandler> provider) {
        LoginSuccessHandler handler = provider.getIfAvailable();
        return handler != null ? handler : new SimpleUrlAuthenticationSuccessHandler("/");
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SessionRegistry sessionRegistry() { return new SessionRegistryImpl(); }

    /** Lets the session registry learn about expired and invalidated sessions. */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() { return new HttpSessionEventPublisher(); }
}
