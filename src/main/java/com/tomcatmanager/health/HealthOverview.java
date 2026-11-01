package com.tomcatmanager.health;

import com.tomcatmanager.infrastructure.Environment;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Latest application health of every instance, for the monitoring page and the dashboard. */
@Service
public class HealthOverview {
    private final TomcatInstanceRepository instances;
    private final InstanceHealthStatusRepository statuses;
    private final HealthProperties properties;
    private final com.tomcatmanager.settings.SettingsService settings;

    public HealthOverview(TomcatInstanceRepository instances, InstanceHealthStatusRepository statuses, HealthProperties properties,
                          com.tomcatmanager.settings.SettingsService settings) {
        this.instances = instances; this.statuses = statuses; this.properties = properties; this.settings = settings;
    }

    /** One row of the overview. {@code notMonitored} explains why an instance is not checked periodically. */
    public record Row(Long instanceId, String instanceName, String hostName, Environment environment, String contextPath, String url,
                      boolean checkable, String notMonitored, String status, String statusLabel, String badgeClass,
                      Instant lastCheckedAt, Instant lastSuccessAt, Instant unhealthySince, int consecutiveFailures,
                      Integer lastStatusCode, String lastDetail, Long lastElapsedMs, Instant lastRecoveredAt) {
        public boolean failing() { return "UNHEALTHY".equals(status) || "DEGRADED".equals(status); }
    }

    public record Overview(boolean monitoringEnabled, long intervalSeconds, int unhealthyAfter, List<Row> rows,
                           long healthy, long degraded, long unhealthy, long unchecked, long notMonitored) {
        public List<Row> failing() { return rows.stream().filter(Row::failing).toList(); }
    }

    public Overview overview() {
        Map<Long, InstanceHealthStatus> byInstance = statuses.findAll().stream().collect(Collectors.toMap(InstanceHealthStatus::getInstanceId, Function.identity()));
        List<Row> rows = instances.findAllByOrderByNameAsc().stream().map(i -> row(i, byInstance.get(i.getId())))
                .sorted(Comparator.comparingInt(HealthOverview::severity).thenComparing(Row::instanceName, String.CASE_INSENSITIVE_ORDER)).toList();
        return new Overview(settings.healthMonitoringEnabled(), properties.monitorInterval().toSeconds(), properties.unhealthyAfterFailures(), rows,
                count(rows, "HEALTHY"), count(rows, "DEGRADED"), count(rows, "UNHEALTHY"),
                rows.stream().filter(r -> r.notMonitored() == null && "UNKNOWN".equals(r.status())).count(),
                rows.stream().filter(r -> r.notMonitored() != null).count());
    }

    public Row row(TomcatInstance i) { return row(i, statuses.findById(i.getId()).orElse(null)); }

    private static long count(List<Row> rows, String status) { return rows.stream().filter(r -> status.equals(r.status())).count(); }

    /** Unhealthy first, then degraded, unchecked, healthy, not monitored. */
    private static int severity(Row r) {
        if (r.notMonitored() != null && "UNKNOWN".equals(r.status())) return 5;
        return switch (r.status()) { case "UNHEALTHY" -> 0; case "DEGRADED" -> 1; case "UNKNOWN" -> 2; default -> 3; };
    }

    static Row row(TomcatInstance i, InstanceHealthStatus s) {
        InstanceHealthStatus.Status status = s == null ? InstanceHealthStatus.Status.UNKNOWN : s.getStatus();
        boolean checkable = i.isHealthCheckEnabled() && i.getHealthUrl() != null;
        return new Row(i.getId(), i.getName(), i.getHost().getName(), i.getEnvironment(), i.getDeploymentContextPath(),
                checkable ? HealthMonitor.urlFor(i) : null, checkable, HealthMonitor.notMonitoredReason(i),
                status.name(), status.label(), status.badgeClass(),
                s == null ? null : s.getLastCheckedAt(), s == null ? null : s.getLastSuccessAt(), s == null ? null : s.getUnhealthySince(),
                s == null ? 0 : s.getConsecutiveFailures(), s == null ? null : s.getLastStatusCode(), s == null ? null : s.getLastDetail(),
                s == null ? null : s.getLastElapsedMs(), s == null ? null : s.getLastRecoveredAt());
    }
}
