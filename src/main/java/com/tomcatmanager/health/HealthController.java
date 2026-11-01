package com.tomcatmanager.health;

import com.tomcatmanager.deployment.DeploymentRun;
import com.tomcatmanager.deployment.DeploymentRunRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Health monitoring pages and API. Manual checks: ADMIN or DEPLOYMENT_OPERATOR; reading: signed-in users. */
@Controller
public class HealthController {
    private final TomcatInstanceRepository instances;
    private final InstanceHealthStatusRepository statuses;
    private final HealthMonitor monitor;
    private final HealthOverview overview;
    private final DeploymentRunRepository runs;

    public HealthController(TomcatInstanceRepository instances, InstanceHealthStatusRepository statuses, HealthMonitor monitor,
                            HealthOverview overview, DeploymentRunRepository runs) {
        this.instances = instances; this.statuses = statuses; this.monitor = monitor; this.overview = overview; this.runs = runs;
    }

    @GetMapping("/health")
    public String index(Model model) {
        model.addAttribute("pageTitle", "Health monitoring");
        model.addAttribute("overview", overview.overview());
        model.addAttribute("recoveries", statuses.findTop10ByLastRecoveredAtNotNullOrderByLastRecoveredAtDesc().stream()
                .map(s -> instances.findById(s.getInstanceId()).map(i -> Map.of("instance", i, "status", (Object) s)).orElse(null))
                .filter(Objects::nonNull).toList());
        model.addAttribute("rollbacks", runs.findTop5ByKindOrderByRequestedAtDesc(DeploymentRun.Kind.ROLLBACK));
        return "health/index";
    }

    @GetMapping("/health/instances/{id}")
    public String instance(@PathVariable long id, Model model) {
        TomcatInstance instance = find(id);
        model.addAttribute("pageTitle", "Health · " + instance.getName());
        model.addAttribute("instance", instance);
        model.addAttribute("row", overview.row(instance));
        model.addAttribute("history", monitor.history(id, 100));
        model.addAttribute("expectation", describe(instance));
        return "health/instance";
    }

    private TomcatInstance find(long id) {
        return instances.findById(id).orElseThrow(() -> new NoSuchElementException("Tomcat instance " + id + " not found."));
    }

    private static String describe(TomcatInstance i) {
        try { return HealthExpectation.forInstance(i).describe(); }
        catch (IllegalArgumentException e) { return "Invalid: " + e.getMessage(); }
    }

    // ---- API ----

    @GetMapping("/api/health")
    @ResponseBody
    public HealthOverview.Overview overviewApi() { return overview.overview(); }

    public record ResultDto(Long id, Instant checkedAt, String trigger, boolean healthy, Integer statusCode, long elapsedMs, String detail,
                            String url, String checkedBy) {
        static ResultDto from(HealthCheckResult r) {
            return new ResultDto(r.getId(), r.getCheckedAt(), r.getTrigger().name(), r.isHealthy(), r.getStatusCode(), r.getElapsedMs(),
                    r.getDetail(), r.getUrl(), r.getCheckedBy());
        }
    }

    @GetMapping("/api/health/instances/{id}/history")
    @ResponseBody
    public List<ResultDto> history(@PathVariable long id, @RequestParam(defaultValue = "50") int limit) {
        find(id);
        return monitor.history(id, limit).stream().map(ResultDto::from).toList();
    }

    public record CheckDto(ResultDto result, HealthOverview.Row status) { }

    @PostMapping("/api/health/instances/{id}/check")
    @ResponseBody
    public CheckDto check(@PathVariable long id, Principal principal) {
        HealthCheckResult result = monitor.checkNow(id, principal.getName());
        return new CheckDto(ResultDto.from(result), overview.row(find(id)));
    }
}
