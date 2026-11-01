package com.tomcatmanager.connectivity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A persisted, immutable record of one connection test and its individual checks. */
@Entity
@Table(name = "connection_test_results")
public class ConnectionTestResult {
    public enum TestType { HOST_SSH, INSTANCE_SSH_SFTP, INSTANCE_TOMCAT_MANAGER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, updatable = false) private UUID correlationId;
    @Column(name = "host_id", nullable = false, updatable = false) private Long hostId;
    @Column(name = "instance_id", updatable = false) private Long instanceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40, updatable = false) private TestType testType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private CheckStatus outcome;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false) private HostKeyVerification hostKeyVerification;
    @Column(length = 200, updatable = false) private String presentedHostKey;
    @Column(length = 255, updatable = false) private String remoteServerInfo;
    @Column(nullable = false, length = 120, updatable = false) private String initiatedBy;
    @Column(nullable = false, updatable = false) private Instant startedAt;
    @Column(nullable = false, updatable = false) private Instant finishedAt;
    @Column(nullable = false, updatable = false) private long durationMs;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "connection_test_checks", joinColumns = @JoinColumn(name = "result_id"))
    @OrderColumn(name = "check_order")
    private List<ConnectionCheck> checks = new ArrayList<>();

    protected ConnectionTestResult() { }

    public ConnectionTestResult(UUID correlationId, Long hostId, Long instanceId, TestType testType, String initiatedBy,
                                Instant startedAt, Instant finishedAt, ProbeReport report) {
        this.correlationId = correlationId; this.hostId = hostId; this.instanceId = instanceId; this.testType = testType;
        this.initiatedBy = initiatedBy; this.startedAt = startedAt; this.finishedAt = finishedAt;
        this.durationMs = Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        this.outcome = report.outcome(); this.hostKeyVerification = report.hostKeyVerification();
        this.presentedHostKey = report.presentedHostKey(); this.remoteServerInfo = report.serverInfo();
        this.checks = new ArrayList<>(report.checks());
    }

    public Long getId() { return id; }
    public UUID getCorrelationId() { return correlationId; }
    public Long getHostId() { return hostId; }
    public Long getInstanceId() { return instanceId; }
    public TestType getTestType() { return testType; }
    public CheckStatus getOutcome() { return outcome; }
    public HostKeyVerification getHostKeyVerification() { return hostKeyVerification; }
    public String getPresentedHostKey() { return presentedHostKey; }
    public String getRemoteServerInfo() { return remoteServerInfo; }
    public String getInitiatedBy() { return initiatedBy; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getDurationMs() { return durationMs; }
    public List<ConnectionCheck> getChecks() { return List.copyOf(checks); }
}
