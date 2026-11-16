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

    private final com.tomcatmanager.access.Access access;
    private final com.tomcatmanager.infrastructure.ManagedHostRepository hosts;
    private final com.tomcatmanager.infrastructure.TomcatInstanceRepository instances;
    private final com.tomcatmanager.infrastructure.ServerGroupRepository groups;
    private final com.tomcatmanager.release.ReleaseRepository releases;
    private final com.tomcatmanager.deployment.DeploymentRunRepository runs;
    private final com.tomcatmanager.users.AppUserRepository users;

    public AuditController(AuditService audit, AuditEventRepository events, DisplayTime time, com.tomcatmanager.access.Access access,
                           com.tomcatmanager.infrastructure.ManagedHostRepository hosts, com.tomcatmanager.infrastructure.TomcatInstanceRepository instances,
                           com.tomcatmanager.infrastructure.ServerGroupRepository groups, com.tomcatmanager.release.ReleaseRepository releases,
                           com.tomcatmanager.deployment.DeploymentRunRepository runs, com.tomcatmanager.users.AppUserRepository users) {
        this.audit = audit; this.events = events; this.zone = ZoneId.of(time.zone());
        this.access = access; this.hosts = hosts; this.instances = instances; this.groups = groups; this.releases = releases;
        this.runs = runs; this.users = users;
    }

    /**
     * Super users read the whole audit log. An administrator reads the events about their teams' hosts, instances,
     * server groups, releases and deployments, and the events by or about the people in their teams (and themselves).
     */
    private AuditService.AuditScope scope() {
        com.tomcatmanager.access.AccessScope s = access.current();
        if (s.isEverything()) return AuditService.AuditScope.EVERYTHING;
        java.util.Set<Long> teams = s.managedTeams();
        java.util.function.Function<Long, String> id = String::valueOf;
        java.util.Set<String> hostIds = new java.util.HashSet<>(), instanceIds = new java.util.HashSet<>();
        hosts.findAll().stream().filter(h -> teams.contains(h.getTeamId())).forEach(h -> hostIds.add(id.apply(h.getId())));
        // (findAllByOrderByNameAsc loads each instance's host: the team is read outside a transaction.)
        instances.findAllByOrderByNameAsc().stream().filter(i -> teams.contains(i.getHost().getTeamId())).forEach(i -> instanceIds.add(id.apply(i.getId())));
        java.util.Set<String> groupIds = groups.findAll().stream().filter(g -> teams.contains(g.getTeamId())).map(g -> id.apply(g.getId()))
                .collect(java.util.stream.Collectors.toSet());
        java.util.Collection<Long> teamList = teams.isEmpty() ? java.util.List.of(-1L) : teams;
        java.util.Set<String> releaseIds = releases.idsOfTeams(teamList).stream().map(id).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> runIds = runs.idsOfTeams(teamList).stream().map(id).collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, java.util.Set<Long>> teamsByUser = access.teamsByUser();
        java.util.Set<String> actors = new java.util.HashSet<>();
        actors.add(s.username());
        for (var u : users.findAll())
            if (teamsByUser.getOrDefault(u.getId(), java.util.Set.of()).stream().anyMatch(teams::contains)) actors.add(u.getUsername());
        return new AuditService.AuditScope(false, actors, hostIds, instanceIds, groupIds, releaseIds, runIds);
    }

    @GetMapping("/admin/audit")
    public String index(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                        @RequestParam(required = false) String resourceType, @RequestParam(required = false) AuditEvent.Outcome outcome,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        Page<AuditEvent> result = audit.search(actor, action, resourceType, outcome, q,
                from == null ? null : from.atStartOfDay(zone).toInstant(), to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(), page, 50, scope());
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
