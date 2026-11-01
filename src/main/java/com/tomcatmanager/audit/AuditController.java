package com.tomcatmanager.audit;

import com.tomcatmanager.common.DisplayTime;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.ZoneId;

/** Read-only audit log viewer (administrators only via /admin/**). There is no edit or delete operation. */
@Controller
public class AuditController {
    private final AuditService audit;
    private final AuditEventRepository events;
    private final ZoneId zone;

    public AuditController(AuditService audit, AuditEventRepository events, DisplayTime time) {
        this.audit = audit; this.events = events; this.zone = ZoneId.of(time.zone());
    }

    @GetMapping("/admin/audit")
    public String index(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                        @RequestParam(required = false) String resourceType, @RequestParam(required = false) AuditEvent.Outcome outcome,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        Page<AuditEvent> result = audit.search(actor, action, resourceType, outcome, q,
                from == null ? null : from.atStartOfDay(zone).toInstant(), to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(), page, 50);
        model.addAttribute("pageTitle", "Audit log");
        model.addAttribute("events", result);
        model.addAttribute("actions", events.distinctActions());
        model.addAttribute("resourceTypes", events.distinctResourceTypes());
        model.addAttribute("outcomes", AuditEvent.Outcome.values());
        model.addAttribute("actor", actor); model.addAttribute("action", action); model.addAttribute("resourceType", resourceType);
        model.addAttribute("outcome", outcome); model.addAttribute("q", q); model.addAttribute("from", from); model.addAttribute("to", to);
        model.addAttribute("filtered", notBlank(actor) || notBlank(action) || notBlank(resourceType) || outcome != null || notBlank(q) || from != null || to != null);
        return "admin/audit";
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
