package com.tomcatmanager;

import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.core.env.Environment;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.Set;

/**
 * Entry point when the WAR runs in an external Tomcat. That container ignores the embedded server's
 * {@code server.servlet.session.*} settings, so they are applied to the servlet context here.
 */
public class ServletInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(TomcatDeploymentManagerApplication.class);
    }

    @Override
    protected WebApplicationContext createRootApplicationContext(ServletContext servletContext) {
        WebApplicationContext context = super.createRootApplicationContext(servletContext);
        if (context != null) applySessionSettings(servletContext, context.getEnvironment());
        return context;
    }

    private static void applySessionSettings(ServletContext servletContext, Environment environment) {
        Duration timeout = Binder.get(environment).bind("server.servlet.session.timeout", Duration.class).orElse(Duration.ofMinutes(30));
        servletContext.setSessionTimeout(Math.max(1, (int) timeout.toMinutes()));
        servletContext.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));

        SessionCookieConfig cookie = servletContext.getSessionCookieConfig();
        cookie.setHttpOnly(environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class, true));
        cookie.setSecure(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
        cookie.setAttribute("SameSite", environment.getProperty("server.servlet.session.cookie.same-site", "lax"));
    }
}
