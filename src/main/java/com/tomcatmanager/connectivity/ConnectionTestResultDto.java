package com.tomcatmanager.connectivity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API representation of a connection test. Contains no credentials. */
public record ConnectionTestResultDto(
        Long id, UUID correlationId, Long hostId, Long instanceId, ConnectionTestResult.TestType testType,
        CheckStatus outcome, HostKeyVerification hostKeyVerification, String presentedHostKey, String remoteServerInfo,
        String initiatedBy, Instant startedAt, Instant finishedAt, long durationMs, List<Check> checks) {

    public record Check(CheckType type, String label, CheckStatus status, String detail) { }

    /** An unsaved result from a "test before saving" request; {@code id} is null. */
    public static ConnectionTestResultDto draft(UUID correlationId, Long hostId, Long instanceId, ConnectionTestResult.TestType type, String actor,
                                                Instant started, Instant finished, ProbeReport report) {
        return new ConnectionTestResultDto(null, correlationId, hostId, instanceId, type, report.outcome(), report.hostKeyVerification(),
                report.presentedHostKey(), report.serverInfo(), actor, started, finished, Math.max(0, finished.toEpochMilli() - started.toEpochMilli()),
                report.checks().stream().map(c -> new Check(c.getCheckType(), c.getCheckType().label(), c.getStatus(), c.getDetail())).toList());
    }

    public static ConnectionTestResultDto from(ConnectionTestResult r) {
        return new ConnectionTestResultDto(r.getId(), r.getCorrelationId(), r.getHostId(), r.getInstanceId(), r.getTestType(),
                r.getOutcome(), r.getHostKeyVerification(), r.getPresentedHostKey(), r.getRemoteServerInfo(), r.getInitiatedBy(),
                r.getStartedAt(), r.getFinishedAt(), r.getDurationMs(),
                r.getChecks().stream().map(c -> new Check(c.getCheckType(), c.getCheckType().label(), c.getStatus(), c.getDetail())).toList());
    }
}
