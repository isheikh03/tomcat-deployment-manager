package com.tomcatmanager.deployment;

import com.tomcatmanager.health.HealthExpectation;
import com.tomcatmanager.health.HealthProbe;
import com.tomcatmanager.infrastructure.TomcatInstance;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Post-deployment application health verification: polls the health URL until the required number
 * of consecutive healthy responses is reached, or the startup timeout expires. A failed request
 * during startup is not final while time remains. Timeout, success count and the expected response
 * come from the instance, falling back to the application defaults.
 */
@Component
public class HealthVerifier {
    private final DeploymentProperties properties;
    private final HealthProbe probe;

    public HealthVerifier(DeploymentProperties properties, HealthProbe probe) {
        this.properties = properties; this.probe = probe;
    }

    /** Effective health policy of an instance. */
    public record Policy(Duration startupTimeout, int consecutiveSuccesses, HealthExpectation expectation) {
        public String describe() {
            return "needs " + consecutiveSuccesses + " consecutive " + expectation.describe() + " response" + (consecutiveSuccesses == 1 ? "" : "s")
                    + " within " + startupTimeout.toSeconds() + " s";
        }
    }

    public Policy policyFor(TomcatInstance instance) {
        int timeout = instance.getStartupTimeoutSeconds() != null ? instance.getStartupTimeoutSeconds() : properties.startupTimeoutSeconds();
        int successes = instance.getHealthConsecutiveSuccesses() != null ? instance.getHealthConsecutiveSuccesses() : properties.consecutiveSuccesses();
        return new Policy(Duration.ofSeconds(timeout), Math.max(1, successes), HealthExpectation.forInstance(instance));
    }

    public record Result(boolean healthy, int attempts, Integer lastStatus, String message, long elapsedMs) { }

    public interface Listener { void attempt(int number, Integer status, String detail); }

    public Result verify(String url, Policy policy, BooleanSupplier cancelled, Listener listener) {
        Instant started = Instant.now();
        String problem = probe.urlProblem(url);
        if (problem != null) return new Result(false, 0, null, problem, 0);

        Instant deadline = started.plus(policy.startupTimeout());
        int attempts = 0, consecutive = 0;
        Integer lastStatus = null;
        String lastDetail = "No response yet.";
        while (true) {
            attempts++;
            try {
                HealthProbe.Outcome outcome = probe.check(url, policy.expectation(), properties.healthRequestTimeout());
                consecutive = outcome.healthy() ? consecutive + 1 : 0;
                lastStatus = outcome.status();
                lastDetail = outcome.detail();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(false, attempts, lastStatus, "Health verification was interrupted.", elapsed(started));
            }
            if (listener != null) listener.attempt(attempts, lastStatus, lastDetail);
            if (consecutive >= policy.consecutiveSuccesses())
                return new Result(true, attempts, lastStatus, consecutive + " consecutive healthy response" + (consecutive == 1 ? "" : "s") + " (" + lastDetail + ")", elapsed(started));
            if (cancelled.getAsBoolean())
                return new Result(false, attempts, lastStatus, "Cancelled while waiting for the application to become healthy.", elapsed(started));
            if (Instant.now().plus(properties.healthInterval()).isAfter(deadline))
                return new Result(false, attempts, lastStatus, "Not healthy within " + policy.startupTimeout().toSeconds() + " s; last result: " + lastDetail, elapsed(started));
            try { Thread.sleep(properties.healthInterval().toMillis()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return new Result(false, attempts, lastStatus, "Health verification was interrupted.", elapsed(started)); }
        }
    }

    private static long elapsed(Instant started) { return Duration.between(started, Instant.now()).toMillis(); }
}
