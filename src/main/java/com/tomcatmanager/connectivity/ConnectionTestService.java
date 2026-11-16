package com.tomcatmanager.connectivity;

import com.tomcatmanager.infrastructure.InfrastructureValidation;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.ManagerUrl;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.security.SecretEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Runs connection tests against configured hosts and Tomcat instances, choosing SSH/SFTP or
 * Tomcat Manager checks by the instance's deployment method, and persists every result.
 *
 * <p>Network I/O happens outside any database transaction. Concurrency is bounded by
 * {@code app.connection.max-concurrent-tests}; excess requests are rejected rather than queued.
 */
@Service
public class ConnectionTestService {
    private static final Logger log = LoggerFactory.getLogger(ConnectionTestService.class);

    private final ManagedHostRepository hosts;
    private final TomcatInstanceRepository instances;
    private final ConnectionTestResultRepository results;
    private final SecretEncryptionService encryption;
    private final NetworkTargetPolicy targetPolicy;
    private final SshConnectivityClient ssh;
    private final TomcatManagerClient manager;
    private final ConnectivityProperties properties;
    private final Semaphore permits;

    public ConnectionTestService(ManagedHostRepository hosts, TomcatInstanceRepository instances, ConnectionTestResultRepository results,
                                 SecretEncryptionService encryption, NetworkTargetPolicy targetPolicy, SshConnectivityClient ssh,
                                 TomcatManagerClient manager, ConnectivityProperties properties) {
        this.hosts = hosts; this.instances = instances; this.results = results; this.encryption = encryption;
        this.targetPolicy = targetPolicy; this.ssh = ssh; this.manager = manager; this.properties = properties;
        this.permits = new Semaphore(Math.max(1, properties.maxConcurrentTests()));
    }

    public ConnectionTestResult testHost(long hostId, String actor) {
        ManagedHost host = hosts.findById(hostId).orElseThrow(() -> new NoSuchElementException("Host " + hostId + " not found."));
        Duration timeout = Duration.ofSeconds(properties.defaultTimeoutSeconds());
        return run(host.getId(), null, ConnectionTestResult.TestType.HOST_SSH, actor, report -> {
            report.notApplicable(CheckType.DEPLOYMENT_DIRECTORY, CheckType.MANAGER_ENDPOINT, CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
            InetAddress address = resolve(host.getHostname(), report);
            if (address == null) {
                report.skip("Target not permitted or unresolvable.", CheckType.SSH_CONNECTION, CheckType.SSH_HOST_KEY, CheckType.SSH_AUTHENTICATION, CheckType.SFTP_ACCESS);
                return;
            }
            ssh.probe(sshTarget(host, address, timeout), null, report);
        });
    }

    public ConnectionTestResult testInstance(long instanceId, String actor) {
        TomcatInstance instance = instances.findById(instanceId).orElseThrow(() -> new NoSuchElementException("Tomcat instance " + instanceId + " not found."));
        ManagedHost host = instance.getHost();
        Duration timeout = Duration.ofSeconds(instance.getTimeoutSeconds());

        if (instance.getDeploymentMethod() == TomcatInstance.DeploymentMethod.SSH_SFTP) {
            return run(host.getId(), instance.getId(), ConnectionTestResult.TestType.INSTANCE_SSH_SFTP, actor, report -> {
                report.notApplicable(CheckType.MANAGER_ENDPOINT, CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
                // The optional Tomcat restart is checked too (nothing is stopped); invalid settings are reported as such.
                com.tomcatmanager.infrastructure.TomcatRestart.Commands restart = null;
                if (instance.restartsTomcat()) {
                    try { restart = com.tomcatmanager.infrastructure.TomcatRestart.commandsFor(instance); }
                    catch (IllegalArgumentException e) { report.failed(CheckType.TOMCAT_RESTART, e.getMessage()); }
                } else report.notApplicable(CheckType.TOMCAT_RESTART);
                InetAddress address = resolve(host.getHostname(), report);
                if (address == null) {
                    report.skip("Target not permitted or unresolvable.", CheckType.SSH_CONNECTION, CheckType.SSH_HOST_KEY,
                            CheckType.SSH_AUTHENTICATION, CheckType.SFTP_ACCESS, CheckType.DEPLOYMENT_DIRECTORY);
                    if (restart != null) report.skip("Target not permitted or unresolvable.", CheckType.TOMCAT_RESTART);
                    return;
                }
                ssh.probe(sshTarget(host, address, timeout), instance.getRemoteDeploymentDirectory(), restart, report);
                if (restart != null && !report.has(CheckType.TOMCAT_RESTART)) report.skip("No SSH or SFTP session.", CheckType.TOMCAT_RESTART);
            });
        }

        return run(host.getId(), instance.getId(), ConnectionTestResult.TestType.INSTANCE_TOMCAT_MANAGER, actor, report -> {
            report.notApplicable(CheckType.SSH_CONNECTION, CheckType.SSH_HOST_KEY, CheckType.SSH_AUTHENTICATION, CheckType.SFTP_ACCESS, CheckType.DEPLOYMENT_DIRECTORY,
                    CheckType.TOMCAT_RESTART);
            ManagerUrl url = ManagerUrl.of(instance);
            if (resolve(url.host(), report) == null) {
                report.skip("Target not permitted or unresolvable.", CheckType.MANAGER_ENDPOINT, CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
                return;
            }
            String password;
            try {
                password = encryption.decrypt(instance.getManagerSecretEncrypted());
            } catch (IllegalStateException e) {
                report.failed(CheckType.MANAGER_ENDPOINT, "Not contacted: " + e.getMessage());
                report.skip("Stored credential unavailable.", CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
                return;
            }
            manager.probe(new TomcatManagerClient.ManagerTarget(url.protocol().name(), url.host(), url.port(),
                    url.path(), instance.getManagerUsername(), password, timeout), report);
        });
    }

    /**
     * Pins the host key presented in the most recent test, only when no key is pinned yet and the
     * administrator confirms the exact fingerprint. A changed key is never replaced here.
     */
    @Transactional
    public ManagedHost trustInitialHostKey(long hostId, String fingerprint, String actor) {
        ManagedHost host = hosts.findById(hostId).orElseThrow(() -> new NoSuchElementException("Host " + hostId + " not found."));
        if (host.hasPinnedHostKey())
            throw new IllegalStateException("A host key is already pinned for this host. A changed key must be investigated and updated explicitly in the host configuration.");
        String confirmed = HostKeyFingerprints.normalize(fingerprint);
        if (!HostKeyFingerprints.isValid(confirmed)) throw new IllegalArgumentException("Fingerprint must look like SHA256:<43 Base64 characters>.");
        ConnectionTestResult latest = results.findFirstByHostIdAndPresentedHostKeyIsNotNullOrderByFinishedAtDesc(hostId)
                .orElseThrow(() -> new IllegalStateException("Run a connection test first so the server's host key can be observed."));
        if (latest.getHostKeyVerification() != HostKeyVerification.UNKNOWN || !HostKeyFingerprints.matches(confirmed, latest.getPresentedHostKey()))
            throw new IllegalStateException("The fingerprint does not match the key presented in the latest connection test. Run the test again and verify the key.");
        if (latest.getFinishedAt().isBefore(Instant.now().minus(properties.hostKeyTrustWindow())))
            throw new IllegalStateException("The latest observation of this host key is too old. Run the connection test again before trusting it.");
        host.setSshHostKeyFingerprint(confirmed);
        log.info("Host key {} pinned for host {} by {}", confirmed, hostId, actor);
        return hosts.save(host);
    }

    public ConnectionTestResult get(long id) {
        return results.findById(id).orElseThrow(() -> new NoSuchElementException("Connection test " + id + " not found."));
    }
    public Page<ConnectionTestResult> historyForHost(long hostId, int page, int size) {
        return results.findByHostIdOrderByFinishedAtDesc(hostId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
    }
    public Page<ConnectionTestResult> historyForInstance(long instanceId, int page, int size) {
        return results.findByInstanceIdOrderByFinishedAtDesc(instanceId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
    }

    public Map<Long, ConnectivityStatus> latestHostStatuses() {
        Map<Long, ConnectivityStatus> map = new HashMap<>();
        results.findLatestHostTests().forEach(r -> map.put(r.getHostId(), status(r)));
        return map;
    }
    public Map<Long, ConnectivityStatus> latestInstanceStatuses() {
        Map<Long, ConnectivityStatus> map = new HashMap<>();
        results.findLatestInstanceTests().forEach(r -> map.put(r.getInstanceId(), status(r)));
        return map;
    }

    public ConnectivityStatus status(ConnectionTestResult r) {
        ConnectivityStatus.State state = switch (r.getOutcome()) {
            case SUCCESS -> r.getFinishedAt().isBefore(Instant.now().minus(properties.resultStaleAfter()))
                    ? ConnectivityStatus.State.STALE : ConnectivityStatus.State.VERIFIED;
            case WARNING -> ConnectivityStatus.State.WARNING;
            default -> ConnectivityStatus.State.FAILED;
        };
        return new ConnectivityStatus(state, r.getId(), r.getFinishedAt());
    }

    /**
     * Tests SSH settings from the host form before they are saved. Nothing is persisted.
     * A blank secret reuses the stored one only when host name, port, user and method are unchanged,
     * so stored credentials can never be redirected to a different server.
     */
    public ConnectionTestResultDto testDraftHost(DraftConnectionRequests.DraftHost draft, String actor) {
        if (!InfrastructureValidation.isValidHostname(draft.hostname())) throw new IllegalArgumentException("Enter a valid host name or IP address.");
        if (!InfrastructureValidation.isValidPosixUsername(draft.username().trim())) throw new IllegalArgumentException("Enter a valid Linux user name.");
        if (draft.fingerprint() != null && !draft.fingerprint().isBlank() && !HostKeyFingerprints.isValid(draft.fingerprint()))
            throw new IllegalArgumentException("The host key fingerprint must look like SHA256: followed by 43 characters.");

        ManagedHost stored = draft.hostId() == null ? null : hosts.findById(draft.hostId()).orElseThrow(() -> new NoSuchElementException("Host " + draft.hostId() + " not found."));
        boolean sameTarget = stored != null && stored.getHostname().equalsIgnoreCase(draft.hostname().trim()) && stored.getSshPort() == draft.port()
                && draft.username().trim().equals(stored.getSshUsername()) && stored.getSshAuthMethod() == draft.authMethod();

        SshCredentials credentials;
        String problem = null;
        try {
            if (draft.authMethod() == ManagedHost.SshAuthMethod.PRIVATE_KEY) {
                String key = hasText(draft.privateKey()) ? draft.privateKey() : null;
                String passphrase = hasText(draft.passphrase()) ? draft.passphrase() : null;
                if (key == null && sameTarget) {
                    key = encryption.decrypt(stored.getSshPrivateKeyEncrypted());
                    if (passphrase == null) passphrase = encryption.decrypt(stored.getSshKeyPassphraseEncrypted());
                }
                if (key == null) problem = stored != null && !sameTarget
                        ? "Host, port or user changed: choose the private key again so it is not sent to a different server."
                        : "Choose a private key file or paste the key.";
                credentials = SshCredentials.privateKey(draft.username().trim(), key, passphrase);
            } else {
                String password = hasText(draft.password()) ? draft.password() : null;
                if (password == null && sameTarget) password = encryption.decrypt(stored.getSshPasswordEncrypted());
                if (password == null) problem = stored != null && !sameTarget
                        ? "Host, port or user changed: enter the password again so it is not sent to a different server."
                        : "Enter the password.";
                credentials = SshCredentials.password(draft.username().trim(), password);
            }
        } catch (IllegalStateException e) {
            problem = e.getMessage();
            credentials = SshCredentials.password(draft.username().trim(), null);
        }

        SshCredentials finalCredentials = credentials;
        String finalProblem = problem;
        Duration timeout = Duration.ofSeconds(properties.defaultTimeoutSeconds());
        Execution run = execute(report -> {
            report.notApplicable(CheckType.DEPLOYMENT_DIRECTORY, CheckType.MANAGER_ENDPOINT, CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
            InetAddress address = resolve(draft.hostname().trim(), report);
            if (address == null) {
                report.skip("Target not permitted or unresolvable.", CheckType.SSH_CONNECTION, CheckType.SSH_HOST_KEY, CheckType.SSH_AUTHENTICATION, CheckType.SFTP_ACCESS);
                return;
            }
            ssh.probe(new SshConnectivityClient.SshTarget(address, draft.hostname().trim(), draft.port(), draft.fingerprint(),
                    finalCredentials, finalProblem, timeout), null, report);
        });
        log.info("Draft SSH test {} for {}:{} by {}: {}", run.correlationId(), draft.hostname(), draft.port(), actor, run.report().outcome());
        return ConnectionTestResultDto.draft(run.correlationId(), draft.hostId(), null, ConnectionTestResult.TestType.HOST_SSH, actor, run.started(), run.finished(), run.report());
    }

    /** Tests Tomcat Manager settings from the instance form before they are saved. Nothing is persisted. */
    public ConnectionTestResultDto testDraftManager(DraftConnectionRequests.DraftManager draft, String actor) {
        ManagerUrl url = ManagerUrl.parse(draft.managerUrl());
        TomcatInstance stored = draft.instanceId() == null ? null : instances.findById(draft.instanceId())
                .orElseThrow(() -> new NoSuchElementException("Tomcat instance " + draft.instanceId() + " not found."));
        boolean sameTarget = stored != null && stored.hasManagerSecret() && ManagerUrl.of(stored).equals(url)
                && draft.username().trim().equals(stored.getManagerUsername());

        String password = hasText(draft.password()) ? draft.password() : null;
        String problem = null;
        if (password == null && sameTarget) {
            try { password = encryption.decrypt(stored.getManagerSecretEncrypted()); }
            catch (IllegalStateException e) { problem = e.getMessage(); }
        }
        if (password == null && problem == null) problem = stored != null && stored.hasManagerSecret() && !sameTarget
                ? "URL or user changed: enter the Manager password again so it is not sent to a different server."
                : "Enter the Manager password.";

        String finalPassword = password, finalProblem = problem;
        Duration timeout = Duration.ofSeconds(draft.timeoutSeconds() == null ? properties.defaultTimeoutSeconds() : draft.timeoutSeconds());
        Execution run = execute(report -> {
            report.notApplicable(CheckType.SSH_CONNECTION, CheckType.SSH_HOST_KEY, CheckType.SSH_AUTHENTICATION, CheckType.SFTP_ACCESS, CheckType.DEPLOYMENT_DIRECTORY);
            if (resolve(url.host(), report) == null) {
                report.skip("Target not permitted or unresolvable.", CheckType.MANAGER_ENDPOINT, CheckType.MANAGER_AUTHENTICATION, CheckType.MANAGER_PERMISSIONS);
                return;
            }
            if (finalProblem != null) {
                // Probe reachability and TLS without credentials, then report the missing secret.
                manager.probe(new TomcatManagerClient.ManagerTarget(url.protocol().name(), url.host(), url.port(), url.path(), null, null, timeout), report);
                return;
            }
            manager.probe(new TomcatManagerClient.ManagerTarget(url.protocol().name(), url.host(), url.port(), url.path(),
                    draft.username().trim(), finalPassword, timeout), report);
        });
        if (finalProblem != null) run = run.withAuthenticationProblem(finalProblem);
        log.info("Draft Manager test {} for {} by {}: {}", run.correlationId(), url, actor, run.report().outcome());
        return ConnectionTestResultDto.draft(run.correlationId(), stored == null ? null : stored.getHost().getId(), draft.instanceId(),
                ConnectionTestResult.TestType.INSTANCE_TOMCAT_MANAGER, actor, run.started(), run.finished(), run.report());
    }

    private interface Probe { void run(ProbeReport report); }

    private record Execution(UUID correlationId, Instant started, Instant finished, ProbeReport report) {
        /** Replaces a generic 401 message with the precise reason no credentials were sent. */
        Execution withAuthenticationProblem(String problem) {
            ProbeReport copy = new ProbeReport();
            report.checks().forEach(c -> {
                if (c.getCheckType() == CheckType.MANAGER_AUTHENTICATION && c.getStatus() == CheckStatus.FAILED) copy.failed(c.getCheckType(), problem);
                else copy.record(c.getCheckType(), c.getStatus(), c.getDetail());
            });
            copy.hostKey(report.hostKeyVerification(), report.presentedHostKey());
            copy.serverInfo(report.serverInfo());
            return new Execution(correlationId, started, finished, copy);
        }
    }

    private Execution execute(Probe probe) {
        if (!permits.tryAcquire()) throw new ConnectivityBusyException("Too many connection tests are running. Try again shortly.");
        UUID correlationId = UUID.randomUUID();
        MDC.put("correlationId", correlationId.toString());
        try {
            Instant started = Instant.now();
            ProbeReport report = new ProbeReport();
            try {
                probe.run(report);
            } catch (RuntimeException e) {
                log.warn("Connection test {} aborted unexpectedly: {}", correlationId, DiagnosticSanitizer.describe(e));
                report.record(CheckType.NETWORK_TARGET, CheckStatus.FAILED, "Test aborted unexpectedly: " + DiagnosticSanitizer.describe(e));
            }
            return new Execution(correlationId, started, Instant.now(), report);
        } finally {
            MDC.remove("correlationId");
            permits.release();
        }
    }

    private ConnectionTestResult run(Long hostId, Long instanceId, ConnectionTestResult.TestType type, String actor, Probe probe) {
        Execution run = execute(probe);
        ConnectionTestResult result = results.save(new ConnectionTestResult(run.correlationId(), hostId, instanceId, type, actor, run.started(), run.finished(), run.report()));
        log.info("Connection test {} ({}) host={} instance={} by {}: {} in {} ms",
                run.correlationId(), type, hostId, instanceId, actor, result.getOutcome(), result.getDurationMs());
        return result;
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    private InetAddress resolve(String hostname, ProbeReport report) {
        try {
            InetAddress address = targetPolicy.resolve(hostname);
            report.success(CheckType.NETWORK_TARGET, hostname + " resolved to " + address.getHostAddress() + "; target permitted by network policy.");
            return address;
        } catch (TargetNotAllowedException e) {
            report.failed(CheckType.NETWORK_TARGET, e.getMessage());
        } catch (UnknownHostException e) {
            report.failed(CheckType.NETWORK_TARGET, "Host name '" + hostname + "' could not be resolved.");
        }
        return null;
    }

    /** SSH target for remote operations: name resolved and checked against the network policy, credentials decrypted. */
    public SshConnectivityClient.SshTarget sshTargetFor(ManagedHost host, Duration timeout) throws RemoteOperationException {
        return sshTarget(host, vetted(host.getHostname()), timeout);
    }

    /** Tomcat Manager target for remote operations, with the stored password decrypted in memory only. */
    public TomcatManagerClient.ManagerTarget managerTargetFor(TomcatInstance instance, Duration timeout) throws RemoteOperationException {
        ManagerUrl url = ManagerUrl.of(instance);
        vetted(url.host());
        String password;
        try { password = encryption.decrypt(instance.getManagerSecretEncrypted()); }
        catch (IllegalStateException e) { throw new RemoteOperationException(e.getMessage()); }
        if (instance.getManagerUsername() == null || password == null)
            throw new RemoteOperationException("Tomcat Manager credentials are not configured for instance " + instance.getName() + ".");
        return new TomcatManagerClient.ManagerTarget(url.protocol().name(), url.host(), url.port(), url.path(), instance.getManagerUsername(), password, timeout);
    }

    private InetAddress vetted(String hostname) throws RemoteOperationException {
        try { return targetPolicy.resolve(hostname); }
        catch (TargetNotAllowedException e) { throw new RemoteOperationException(e.getMessage()); }
        catch (UnknownHostException e) { throw new RemoteOperationException("Host name '" + hostname + "' could not be resolved."); }
    }

    private SshConnectivityClient.SshTarget sshTarget(ManagedHost host, InetAddress address, Duration timeout) {
        SshCredentials credentials = null;
        String problem = null;
        try {
            if (host.getSshAuthMethod() == ManagedHost.SshAuthMethod.PRIVATE_KEY) {
                String key = encryption.decrypt(host.getSshPrivateKeyEncrypted());
                if (key == null) problem = "No SSH private key is stored for this host.";
                credentials = SshCredentials.privateKey(host.getSshUsername(), key, encryption.decrypt(host.getSshKeyPassphraseEncrypted()));
            } else {
                String password = encryption.decrypt(host.getSshPasswordEncrypted());
                if (password == null) problem = "No SSH password is stored for this host.";
                credentials = SshCredentials.password(host.getSshUsername(), password);
            }
        } catch (IllegalStateException e) {
            problem = e.getMessage();
            credentials = SshCredentials.password(host.getSshUsername(), null);
        }
        return new SshConnectivityClient.SshTarget(address, host.getHostname(), host.getSshPort(), host.getSshHostKeyFingerprint(), credentials, problem, timeout);
    }
}
