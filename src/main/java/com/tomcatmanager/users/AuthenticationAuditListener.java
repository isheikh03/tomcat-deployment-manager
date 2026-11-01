package com.tomcatmanager.users;

import com.tomcatmanager.audit.AuditEvent;
import com.tomcatmanager.audit.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/** Records sign-in success and failure (with lockout) and sign-out. */
@Component
public class AuthenticationAuditListener {
    private final UserAccountService accounts;
    private final AuditService audit;

    public AuthenticationAuditListener(UserAccountService accounts, AuditService audit) {
        this.accounts = accounts; this.audit = audit;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        // Only interactive username/password sign-ins (not e.g. test or token authentications).
        if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken) accounts.loginSucceeded(event.getAuthentication().getName());
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        Exception failure = event.getException();
        String reason = failure instanceof LockedException ? "account locked"
                : failure instanceof DisabledException ? "account disabled"
                : failure instanceof BadCredentialsException ? "bad-credentials"
                : failure.getClass().getSimpleName();
        accounts.loginFailed(String.valueOf(event.getAuthentication().getPrincipal()), reason);
    }

    @EventListener
    public void onLogout(LogoutSuccessEvent event) {
        String name = event.getAuthentication().getName();
        audit.record(name, "LOGOUT", "USER", name, AuditEvent.Outcome.SUCCESS, "Signed out.");
    }
}
