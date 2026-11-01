package com.tomcatmanager.deployment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Deployment-level states. Transitions are enforced; terminal states never change again. */
public enum DeploymentState {
    PENDING, VALIDATING, RUNNING, CANCEL_REQUESTED, PARTIALLY_SUCCEEDED, SUCCEEDED, FAILED, CANCELLED, UNKNOWN;

    private static final Map<DeploymentState, Set<DeploymentState>> NEXT = Map.of(
            PENDING, EnumSet.of(VALIDATING, CANCEL_REQUESTED, CANCELLED, FAILED, UNKNOWN),
            VALIDATING, EnumSet.of(RUNNING, CANCEL_REQUESTED, FAILED, CANCELLED, UNKNOWN),
            RUNNING, EnumSet.of(CANCEL_REQUESTED, SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, UNKNOWN),
            CANCEL_REQUESTED, EnumSet.of(CANCELLED, PARTIALLY_SUCCEEDED, SUCCEEDED, FAILED, UNKNOWN),
            UNKNOWN, EnumSet.of(SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, CANCELLED),   // only via reconciliation
            PARTIALLY_SUCCEEDED, EnumSet.noneOf(DeploymentState.class),
            SUCCEEDED, EnumSet.noneOf(DeploymentState.class),
            FAILED, EnumSet.noneOf(DeploymentState.class),
            CANCELLED, EnumSet.noneOf(DeploymentState.class));

    public boolean canTransitionTo(DeploymentState next) { return NEXT.get(this).contains(next); }

    public boolean isActive() { return this == PENDING || this == VALIDATING || this == RUNNING || this == CANCEL_REQUESTED; }
    public boolean isTerminal() { return NEXT.get(this).isEmpty(); }

    public String label() {
        return switch (this) {
            case PENDING -> "Queued"; case VALIDATING -> "Validating"; case RUNNING -> "Running"; case CANCEL_REQUESTED -> "Cancelling";
            case PARTIALLY_SUCCEEDED -> "Partially succeeded"; case SUCCEEDED -> "Succeeded"; case FAILED -> "Failed";
            case CANCELLED -> "Cancelled"; case UNKNOWN -> "Needs reconciliation";
        };
    }

    public String badgeClass() {
        return switch (this) {
            case SUCCEEDED -> "text-bg-success"; case FAILED -> "text-bg-danger"; case PARTIALLY_SUCCEEDED, UNKNOWN -> "text-bg-warning";
            case CANCELLED -> "text-bg-secondary"; default -> "text-bg-primary";
        };
    }
}
