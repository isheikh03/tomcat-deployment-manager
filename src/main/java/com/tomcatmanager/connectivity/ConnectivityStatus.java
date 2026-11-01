package com.tomcatmanager.connectivity;

import java.time.Instant;

/**
 * Display summary of the latest connection test for a host or instance. A successful result
 * older than {@code app.connection.result-stale-after} is reported as STALE, not VERIFIED.
 */
public record ConnectivityStatus(State state, Long resultId, Instant checkedAt) {
    public enum State { VERIFIED, STALE, WARNING, FAILED, NEVER_TESTED }

    public static final ConnectivityStatus NEVER = new ConnectivityStatus(State.NEVER_TESTED, null, null);

    public String label() {
        return switch (state) {
            case VERIFIED -> "Verified";
            case STALE -> "Stale";
            case WARNING -> "Warning";
            case FAILED -> "Failed";
            case NEVER_TESTED -> "Never tested";
        };
    }

    public String badgeClass() {
        return switch (state) {
            case VERIFIED -> "text-bg-success";
            case STALE -> "text-bg-secondary";
            case WARNING -> "text-bg-warning";
            case FAILED -> "text-bg-danger";
            case NEVER_TESTED -> "text-bg-light border";
        };
    }
}
