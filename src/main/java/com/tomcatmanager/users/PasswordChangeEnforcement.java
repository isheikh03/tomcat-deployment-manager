package com.tomcatmanager.users;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Users with a temporary password (new account, administrator reset, first administrator) must set
 * their own before doing anything else. The flag is set at sign-in ({@link LoginSuccessHandler})
 * and kept in the session until the password is changed.
 */
@Component
public class PasswordChangeEnforcement extends OncePerRequestFilter {
    public static final String SESSION_FLAG = "tdm.mustChangePassword";
    public static final String CHANGE_URL = "/account/password";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_FLAG)) && !allowed(request)) {
            if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Change your temporary password first.\"}");
            } else {
                response.sendRedirect(request.getContextPath() + CHANGE_URL + "?required");
            }
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean allowed(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(CHANGE_URL) || path.equals("/logout") || path.equals("/login") || path.startsWith("/css/") || path.startsWith("/js/")
                || path.startsWith("/images/") || path.startsWith("/assets/") || path.equals("/error") || path.equals("/favicon.ico");
    }
}
