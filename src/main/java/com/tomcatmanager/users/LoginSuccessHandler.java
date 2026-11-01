package com.tomcatmanager.users;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** After sign-in: users with a temporary password go to the password page first, everyone else to the dashboard. */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {
    private final AppUserRepository users;
    private final SimpleUrlAuthenticationSuccessHandler dashboard = new SimpleUrlAuthenticationSuccessHandler("/");

    public LoginSuccessHandler(AppUserRepository users) { this.users = users; }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication auth) throws IOException, ServletException {
        boolean mustChange = users.findByUsernameIgnoreCase(auth.getName()).map(AppUser::isMustChangePassword).orElse(false);
        if (mustChange) {
            request.getSession().setAttribute(PasswordChangeEnforcement.SESSION_FLAG, Boolean.TRUE);
            response.sendRedirect(request.getContextPath() + PasswordChangeEnforcement.CHANGE_URL + "?required");
            return;
        }
        dashboard.onAuthenticationSuccess(request, response, auth);
    }
}
