package com.tomcatmanager.deployment;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentStateTest {

    @Test void targetHappyPathIsLegal() {
        TargetState[] path = {TargetState.PENDING, TargetState.BACKUP_IN_PROGRESS, TargetState.BACKUP_COMPLETED, TargetState.TRANSFER_IN_PROGRESS,
                TargetState.DEPLOYING, TargetState.HEALTH_CHECKING, TargetState.SUCCEEDED};
        for (int i = 0; i < path.length - 1; i++) assertThat(path[i].canTransitionTo(path[i + 1])).as(path[i] + " -> " + path[i + 1]).isTrue();
    }

    @Test void terminalTargetStatesNeverChange() {
        for (TargetState terminal : EnumSet.of(TargetState.SUCCEEDED, TargetState.FAILED, TargetState.SKIPPED, TargetState.PREFLIGHT_FAILED, TargetState.ROLLED_BACK))
            for (TargetState next : TargetState.values()) assertThat(terminal.canTransitionTo(next)).as(terminal + " -> " + next).isFalse();
    }

    @Test void onceTheServerMayHaveChangedFailuresCannotBeSkippedOrClaimedUnchanged() {
        assertThat(TargetState.DEPLOYING.canTransitionTo(TargetState.SKIPPED)).isFalse();
        assertThat(TargetState.HEALTH_CHECKING.canTransitionTo(TargetState.SKIPPED)).isFalse();
        assertThat(TargetState.DEPLOYING.remoteMayHaveChanged()).isTrue();
        assertThat(TargetState.TRANSFER_IN_PROGRESS.remoteMayHaveChanged()).isFalse();
        assertThat(TargetState.PENDING.canTransitionTo(TargetState.SUCCEEDED)).as("no success without doing the work").isFalse();
    }

    @Test void unknownOnlyResolvesToAConcreteOutcome() {
        assertThat(TargetState.UNKNOWN.canTransitionTo(TargetState.SUCCEEDED)).isTrue();
        assertThat(TargetState.UNKNOWN.canTransitionTo(TargetState.FAILED)).isTrue();
        assertThat(TargetState.UNKNOWN.canTransitionTo(TargetState.PENDING)).isFalse();
        assertThat(TargetState.UNKNOWN.canTransitionTo(TargetState.DEPLOYING)).as("no blind retry").isFalse();
    }

    @Test void deploymentStates() {
        assertThat(DeploymentState.PENDING.canTransitionTo(DeploymentState.SUCCEEDED)).isFalse();
        assertThat(DeploymentState.RUNNING.canTransitionTo(DeploymentState.PARTIALLY_SUCCEEDED)).isTrue();
        assertThat(DeploymentState.CANCEL_REQUESTED.canTransitionTo(DeploymentState.CANCELLED)).isTrue();
        assertThat(DeploymentState.CANCEL_REQUESTED.canTransitionTo(DeploymentState.RUNNING)).isFalse();
        assertThat(DeploymentState.SUCCEEDED.isTerminal()).isTrue();
        assertThat(DeploymentState.UNKNOWN.isTerminal()).as("unknown must be reconciled").isFalse();
        assertThat(DeploymentState.UNKNOWN.isActive()).isFalse();
    }
}
