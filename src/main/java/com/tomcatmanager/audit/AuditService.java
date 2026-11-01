package com.tomcatmanager.audit;

import com.tomcatmanager.common.CorrelationIdFilter;
import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

/**
 * Writes audit records in their own transaction, so they survive a failing business operation and
 * an audit failure never breaks the operation itself. Summaries are sanitized; callers must not
 * pass secret values (and never do: only names, IDs and "changed"/"removed" facts are recorded).
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    public static final String SYSTEM = "system";

    private final AuditEventRepository events;
    private final TransactionTemplate tx;

    public AuditService(AuditEventRepository events, PlatformTransactionManager transactionManager) {
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Records an action by the current user (or "system" outside a request). */
    public void success(String action, String resourceType, Object resourceId, String summary) {
        record(currentActor(), action, resourceType, resourceId, AuditEvent.Outcome.SUCCESS, summary);
    }

    public void failure(String action, String resourceType, Object resourceId, String summary) {
        record(currentActor(), action, resourceType, resourceId, AuditEvent.Outcome.FAILURE, summary);
    }

    public void record(String actor, String action, String resourceType, Object resourceId, AuditEvent.Outcome outcome, String summary) {
        try {
            HttpServletRequest request = currentRequest();
            String ip = request == null ? null : truncate(request.getRemoteAddr(), 64);
            String agent = request == null ? null : truncate(request.getHeader("User-Agent"), 200);
            String clean = DiagnosticSanitizer.clean(summary == null ? action : summary, 1000);
            AuditEvent event = new AuditEvent(truncate(actor == null ? SYSTEM : actor, 120), truncate(action, 60), truncate(resourceType, 40),
                    resourceId == null ? null : truncate(String.valueOf(resourceId), 60), outcome, ip, agent, truncate(MDC.get(CorrelationIdFilter.MDC_KEY), 64),
                    clean == null || clean.isBlank() ? action : clean);
            tx.executeWithoutResult(s -> events.save(event));
        } catch (RuntimeException e) {
            // Auditing must never break the audited operation; the failure itself is logged.
            log.error("Could not write audit record {} {} {}: {}", action, resourceType, resourceId, e.toString());
        }
    }

    public Page<AuditEvent> search(String actor, String action, String resourceType, AuditEvent.Outcome outcome, String text,
                                   Instant from, Instant to, int page, int size) {
        return events.search(blank(actor), blank(action), blank(resourceType), outcome, blank(text),
                from == null ? Instant.EPOCH : from, to == null ? Instant.parse("9999-01-01T00:00:00Z") : to,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
    }

    public static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) return SYSTEM;
        return auth.getName();
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs ? attrs.getRequest() : null;
    }

    private static String blank(String s) { return s == null ? "" : s.strip(); }

    private static String truncate(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }
}
