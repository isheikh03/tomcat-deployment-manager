package com.tomcatmanager.deployment;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-target states and their legal transitions. Once the remote application may have changed
 * (from DEPLOYING on), an interrupted operation can only become UNKNOWN, never a guessed outcome.
 */
public enum TargetState {
    PENDING, PREFLIGHT_FAILED, BACKUP_IN_PROGRESS, BACKUP_COMPLETED, TRANSFER_IN_PROGRESS, DEPLOYING, HEALTH_CHECKING,
    SUCCEEDED, FAILED, SKIPPED, ROLLBACK_REQUIRED, ROLLING_BACK, ROLLED_BACK, UNKNOWN;

    private static final Map<TargetState, Set<TargetState>> NEXT = new EnumMap<>(TargetState.class);
    static {
        NEXT.put(PENDING, EnumSet.of(PREFLIGHT_FAILED, BACKUP_IN_PROGRESS, SKIPPED, FAILED, UNKNOWN));
        NEXT.put(BACKUP_IN_PROGRESS, EnumSet.of(BACKUP_COMPLETED, FAILED, UNKNOWN));
        NEXT.put(BACKUP_COMPLETED, EnumSet.of(TRANSFER_IN_PROGRESS, SKIPPED, FAILED, UNKNOWN));
        NEXT.put(TRANSFER_IN_PROGRESS, EnumSet.of(DEPLOYING, FAILED, UNKNOWN));
        NEXT.put(DEPLOYING, EnumSet.of(HEALTH_CHECKING, FAILED, ROLLBACK_REQUIRED, UNKNOWN));
        NEXT.put(HEALTH_CHECKING, EnumSet.of(SUCCEEDED, ROLLBACK_REQUIRED, FAILED, UNKNOWN));
        // A separate rollback run restores the target; on its success the original is closed as ROLLED_BACK.
        NEXT.put(ROLLBACK_REQUIRED, EnumSet.of(ROLLING_BACK, ROLLED_BACK, FAILED));
        NEXT.put(ROLLING_BACK, EnumSet.of(ROLLED_BACK, FAILED, UNKNOWN));
        NEXT.put(UNKNOWN, EnumSet.of(SUCCEEDED, FAILED, ROLLBACK_REQUIRED)); // only through administrator reconciliation
        for (TargetState terminal : EnumSet.of(PREFLIGHT_FAILED, SUCCEEDED, FAILED, SKIPPED, ROLLED_BACK))
            NEXT.put(terminal, EnumSet.noneOf(TargetState.class));
    }

    public boolean canTransitionTo(TargetState next) { return NEXT.get(this).contains(next); }
    public boolean isTerminal() { return NEXT.get(this).isEmpty(); }
    /** Work in progress on the remote side (or not yet started). */
    public boolean isInFlight() { return EnumSet.of(BACKUP_IN_PROGRESS, BACKUP_COMPLETED, TRANSFER_IN_PROGRESS, DEPLOYING, HEALTH_CHECKING, ROLLING_BACK).contains(this); }
    /** States in which the remote application may already have been replaced. */
    public boolean remoteMayHaveChanged() { return EnumSet.of(DEPLOYING, HEALTH_CHECKING, ROLLING_BACK).contains(this); }

    public String label() {
        return switch (this) {
            case PENDING -> "Waiting"; case PREFLIGHT_FAILED -> "Preflight failed"; case BACKUP_IN_PROGRESS -> "Backing up";
            case BACKUP_COMPLETED -> "Backup ready"; case TRANSFER_IN_PROGRESS -> "Uploading"; case DEPLOYING -> "Deploying";
            case HEALTH_CHECKING -> "Health check"; case SUCCEEDED -> "Succeeded"; case FAILED -> "Failed"; case SKIPPED -> "Not started";
            case ROLLBACK_REQUIRED -> "Rollback required"; case ROLLING_BACK -> "Rolling back"; case ROLLED_BACK -> "Rolled back";
            case UNKNOWN -> "Unknown – reconcile";
        };
    }

    public String badgeClass() {
        return switch (this) {
            case SUCCEEDED, ROLLED_BACK -> "text-bg-success"; case FAILED, PREFLIGHT_FAILED -> "text-bg-danger";
            case ROLLBACK_REQUIRED, UNKNOWN -> "text-bg-warning"; case SKIPPED -> "text-bg-secondary"; case PENDING -> "text-bg-light border";
            default -> "text-bg-primary";
        };
    }
}
