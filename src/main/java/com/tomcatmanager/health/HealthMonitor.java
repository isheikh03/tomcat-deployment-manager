package com.tomcatmanager.health;

import com.tomcatmanager.connectivity.ConnectivityBusyException;
import com.tomcatmanager.connectivity.DiagnosticSanitizer;
import com.tomcatmanager.deployment.DeploymentTargetRepository;
import com.tomcatmanager.deployment.TargetState;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.notifications.Notification;
import com.tomcatmanager.notifications.NotificationService;
import com.tomcatmanager.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Application health monitoring: scheduled checks of every monitored instance plus manual checks.
 * Each result is stored as history and folded into the instance's latest status. Concurrency is
 * bounded, one check per instance at a time, and instances with a deployment in progress are
 * skipped by the scheduler (the deployment verifies health itself).
 */
@Service
public class HealthMonitor implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);
    private static final Set<TargetState> DEPLOYING = Arrays.stream(TargetState.values()).filter(TargetState::isInFlight).collect(Collectors.toSet());

    private final TomcatInstanceRepository instances;
    private final DeploymentTargetRepository deploymentTargets;
    private final HealthCheckResultRepository results;
    private final InstanceHealthStatusRepository statuses;
    private final HealthProbe probe;
    private final HealthProperties properties;
    private final TransactionTemplate tx;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final ExecutorService workers;
    private final Semaphore manualSlots;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public HealthMonitor(TomcatInstanceRepository instances, DeploymentTargetRepository deploymentTargets, HealthCheckResultRepository results,
                         InstanceHealthStatusRepository statuses, HealthProbe probe, HealthProperties properties, TransactionTemplate tx,
                         NotificationService notifications, SettingsService settings) {
        this.instances = instances; this.deploymentTargets = deploymentTargets; this.results = results; this.statuses = statuses;
        this.probe = probe; this.properties = properties; this.tx = tx; this.notifications = notifications; this.settings = settings;
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(Math.max(1, properties.maxConcurrentChecks()), r -> {
            Thread t = new Thread(r, "health-check-" + n.incrementAndGet()); t.setDaemon(true); return t;
        });
        this.manualSlots = new Semaphore(Math.max(1, properties.maxConcurrentChecks()));
    }

    /** Why an instance is not monitored, or null when it is. */
    public static String notMonitoredReason(TomcatInstance i) {
        if (!i.isEnabled()) return "Instance is disabled.";
        if (!i.getHost().isEnabled()) return "Linux host is disabled.";
        if (!i.isHealthCheckEnabled()) return "Application health check is switched off.";
        if (i.getHealthUrl() == null) return "No health URL configured.";
        if (i.getHealthUrl().contains("{context}") && i.getDeploymentFilename() == null) return "No deployment filename, so {context} in the health URL is unknown.";
        if (!i.isMonitoringEnabled()) return "Periodic monitoring is switched off for this instance.";
        return null;
    }

    /** URL checked for an instance: the health URL with {context} replaced by the instance's deployment context. */
    public static String urlFor(TomcatInstance i) {
        String context = i.getDeploymentContextPath();
        return context == null ? i.getHealthUrl() : i.healthUrlFor(context);
    }

    @Scheduled(initialDelayString = "${app.health.initial-delay:PT1M}", fixedDelayString = "${app.health.monitor-interval:PT5M}")
    public void scheduledRun() {
        if (!settings.healthMonitoringEnabled()) return;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (TomcatInstance instance : instances.findAllByOrderByNameAsc()) {
                if (notMonitoredReason(instance) != null) continue;
                if (!deploymentTargets.findByInstanceIdAndStateIn(instance.getId(), DEPLOYING).isEmpty()) continue;
                futures.add(workers.submit(() -> {
                    if (!running.add(instance.getId())) return;
                    try { record(instance, HealthCheckResult.Trigger.SCHEDULED, null); }
                    catch (RuntimeException e) { log.warn("Health check of instance {} failed unexpectedly: {}", instance.getId(), DiagnosticSanitizer.describe(e)); }
                    finally { running.remove(instance.getId()); }
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) { log.warn("Health check task failed: {}", e.toString()); }
            }
            int purged = results.deleteOlderThan(Instant.now().minus(properties.retention()));
            if (purged > 0) log.info("Removed {} health check result(s) older than {}", purged, properties.retention());
        } catch (RuntimeException e) {
            log.error("Scheduled health monitoring failed", e);
        }
    }

    /** Manual "check now" by a user. Works even when periodic monitoring is off for the instance. */
    public HealthCheckResult checkNow(long instanceId, String actor) {
        TomcatInstance instance = instances.findById(instanceId).orElseThrow(() -> new NoSuchElementException("Tomcat instance " + instanceId + " not found."));
        if (!instance.isHealthCheckEnabled() || instance.getHealthUrl() == null)
            throw new IllegalStateException("This instance has no application health check configured. Edit the instance to add a health URL.");
        if (!manualSlots.tryAcquire()) throw new ConnectivityBusyException("Too many health checks are running. Try again in a moment.");
        try {
            if (!running.add(instanceId)) throw new ConnectivityBusyException("A health check of this instance is already running.");
            try { return record(instance, HealthCheckResult.Trigger.MANUAL, actor); }
            finally { running.remove(instanceId); }
        } finally {
            manualSlots.release();
        }
    }

    private HealthCheckResult record(TomcatInstance instance, HealthCheckResult.Trigger trigger, String actor) {
        String url = urlFor(instance);
        HealthExpectation expectation;
        HealthProbe.Outcome outcome;
        try {
            expectation = HealthExpectation.forInstance(instance);
            outcome = probe.check(url, expectation, properties.requestTimeout());
        } catch (IllegalArgumentException e) {
            outcome = new HealthProbe.Outcome(false, null, "Invalid expected status: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Health check interrupted.");
        }
        HealthCheckResult result = new HealthCheckResult(instance.getId(), instance.getDeploymentContextPath(), DiagnosticSanitizer.clean(url, 500), trigger,
                outcome.healthy(), outcome.status(), outcome.elapsedMs(), DiagnosticSanitizer.clean(outcome.detail(), 500), actor);
        results.save(result);
        InstanceHealthStatus.Status[] before = new InstanceHealthStatus.Status[1];
        InstanceHealthStatus.Status[] after = new InstanceHealthStatus.Status[1];
        for (int attempt = 1; ; attempt++) {
            try {
                tx.executeWithoutResult(s -> {
                    InstanceHealthStatus status = statuses.findById(instance.getId()).orElseGet(() -> new InstanceHealthStatus(instance.getId()));
                    before[0] = status.getStatus();
                    status.apply(result, properties.unhealthyAfterFailures());
                    after[0] = status.getStatus();
                    statuses.save(status);
                });
                break;
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= 3) throw e;
            }
        }
        boolean recovered = (before[0] == InstanceHealthStatus.Status.DEGRADED || before[0] == InstanceHealthStatus.Status.UNHEALTHY)
                && after[0] == InstanceHealthStatus.Status.HEALTHY;
        if (recovered) {
            log.info("Instance {} ({}) recovered: {}", instance.getId(), instance.getName(), result.getDetail());
            // Only an outage that was reported (UNHEALTHY) gets a recovery notification; a single blip does not.
            if (before[0] == InstanceHealthStatus.Status.UNHEALTHY)
                notifications.publish(Notification.Level.SUCCESS, Notification.Category.HEALTH, instance.getName() + " is healthy again",
                        "The application health check of " + instance.getName() + " succeeded again: " + result.getDetail(), "/health/instances/" + instance.getId());
        } else if (!result.isHealthy()) {
            log.warn("Instance {} ({}) health check failed: {}", instance.getId(), instance.getName(), result.getDetail());
            if (before[0] != InstanceHealthStatus.Status.UNHEALTHY && after[0] == InstanceHealthStatus.Status.UNHEALTHY)
                notifications.publish(Notification.Level.DANGER, Notification.Category.HEALTH, instance.getName() + " is unhealthy",
                        "The application health check of " + instance.getName() + " failed " + properties.unhealthyAfterFailures()
                                + " times in a row. Last result: " + result.getDetail(), "/health/instances/" + instance.getId());
        }
        return result;
    }

    public List<HealthCheckResult> history(long instanceId, int limit) {
        return results.findByInstanceIdOrderByCheckedAtDescIdDesc(instanceId, PageRequest.of(0, Math.min(Math.max(1, limit), 500)));
    }

    @Override public void destroy() { workers.shutdownNow(); }
}
