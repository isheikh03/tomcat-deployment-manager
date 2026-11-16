package com.tomcatmanager.deployment;

import com.tomcatmanager.application.ApplicationForm;
import com.tomcatmanager.application.ApplicationService;
import com.tomcatmanager.application.ManagedApplication;
import com.tomcatmanager.connectivity.EmbeddedSshServer;
import com.tomcatmanager.infrastructure.Environment;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.Release;
import com.tomcatmanager.release.ReleaseService;
import com.tomcatmanager.release.TestWars;
import com.tomcatmanager.security.SecretEncryptionService;
import com.tomcatmanager.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real deployments end to end: PostgreSQL, the release library, an SSH/SFTP server and a
 * Tomcat stand-in (Manager text interface + health endpoints).
 */
@TestPropertySource(properties = {
        "app.connection.target-policy.allow-loopback=true",
        "app.deployment.health-interval=PT0.1S",
        "app.deployment.startup-timeout-seconds=2",
        "app.deployment.consecutive-successes=2",
        "app.health.monitoring-enabled=false"})
class DeploymentEngineIntegrationTest extends DatabaseIntegrationTest {
    @Autowired DeploymentService deployments;
    @Autowired DeploymentRecorder recorder;
    @Autowired DeploymentTargetRepository targets;
    @Autowired DeploymentBackupRepository backups;
    @Autowired ReleaseService releases;
    @Autowired ApplicationService applications;
    @Autowired ManagedHostRepository hosts;
    @Autowired TomcatInstanceRepository instances;
    @Autowired SecretEncryptionService encryption;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path serverRoot;
    private EmbeddedSshServer sshServer;
    private FakeTomcat tomcat;
    private ManagedHost host;
    private Release releaseA, releaseB;
    private byte[] warA, warB;

    @BeforeEach void setUp() throws Exception {
        jdbc.execute("TRUNCATE audit_events, deployment_logs, deployment_backups, deployment_targets, deployment_runs, release_artifacts, stored_artifacts, applications, "
                + "connection_test_results, server_group_instances, tomcat_instances, server_groups, managed_hosts RESTART IDENTITY CASCADE");
        for (String dir : List.of("opt/tomcat/a/webapps", "opt/tomcat/b/webapps")) Files.createDirectories(serverRoot.resolve(dir));
        sshServer = new EmbeddedSshServer(serverRoot, null, false);
        tomcat = new FakeTomcat();

        host = new ManagedHost();
        host.setName("it-host"); host.setHostname("127.0.0.1"); host.setSshPort(sshServer.port()); host.setSshUsername(EmbeddedSshServer.USER);
        host.setSshAuthMethod(ManagedHost.SshAuthMethod.PASSWORD); host.setSshPasswordEncrypted(encryption.encrypt(EmbeddedSshServer.PASSWORD));
        host.setSshHostKeyFingerprint(sshServer.hostKeyFingerprint());
        host = hosts.save(host);

        ApplicationForm form = new ApplicationForm();
        form.setName("Portal"); form.setServletApi(ManagedApplication.ServletApi.DETECT);
        ManagedApplication app = applications.save(form, "it");
        warA = TestWars.war().jakartaWebXml().clazz("A", 17, null).entry("v.txt", "A").bytes();
        warB = TestWars.war().jakartaWebXml().clazz("A", 17, null).entry("v.txt", "B").bytes();
        releaseA = releases.upload(new ReleaseService.UploadCommand(app.getId(), "1.0", "First release of the portal", "portal-1.0.war"), new ByteArrayInputStream(warA), "it");
        releaseB = releases.upload(new ReleaseService.UploadCommand(app.getId(), "1.1", "Second release with fixes", "portal-1.1.war"), new ByteArrayInputStream(warB), "it");
    }

    @AfterEach void tearDown() throws Exception {
        if (sshServer != null) sshServer.close();
        if (tomcat != null) tomcat.close();
    }

    private TomcatInstance sshInstance(String name, String base, String filename, Environment env) {
        TomcatInstance i = new TomcatInstance();
        i.setName(name); i.setHost(host); i.setCatalinaBase(base); i.setAppBase("webapps"); i.setDeploymentFilename(filename);
        i.setHealthUrl(tomcat.healthUrl()); i.setEnvironment(env); i.setTomcatVersion("10.1.30"); i.setJavaVersion(21);
        i.setDeploymentMethod(TomcatInstance.DeploymentMethod.SSH_SFTP); i.setTimeoutSeconds(10);
        return instances.save(i);
    }

    private TomcatInstance managerInstance(String name, String filename, Environment env) {
        TomcatInstance i = new TomcatInstance();
        i.setName(name); i.setHost(host); i.setCatalinaBase("/opt/tomcat/m"); i.setAppBase("webapps"); i.setDeploymentFilename(filename);
        i.setHealthUrl(tomcat.healthUrl()); i.setEnvironment(env); i.setTomcatVersion("10.1.30"); i.setJavaVersion(21);
        i.setDeploymentMethod(TomcatInstance.DeploymentMethod.TOMCAT_MANAGER); i.setTimeoutSeconds(10);
        i.setManagerProtocol(TomcatInstance.ManagerProtocol.HTTP); i.setManagerHost("127.0.0.1"); i.setManagerPort(tomcat.port());
        i.setManagerEndpoint("/manager/text"); i.setManagerUsername(FakeTomcat.USER); i.setManagerSecretEncrypted(encryption.encrypt(FakeTomcat.PASSWORD));
        return instances.save(i);
    }

    private DeploymentRun deploy(Release release, Environment env, DeploymentRun.Strategy strategy, DeploymentPreflight.TargetSpec... specs) {
        DeploymentPreflight.Request request = new DeploymentPreflight.Request(release.getId(), env, null, List.of(specs), strategy, strategy == DeploymentRun.Strategy.PARALLEL ? 2 : 1);
        DeploymentRun run = deployments.submit(new DeploymentService.Submission(UUID.randomUUID(), request, true, release.getVersionLabel()), "it");
        return awaitFinished(run.getId());
    }

    private DeploymentRun awaitFinished(long runId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            DeploymentRun run = recorder.run(runId);
            if (!run.getState().isActive()) return run;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("Deployment " + runId + " did not finish in time");
    }

    private static DeploymentPreflight.TargetSpec target(TomcatInstance i) { return new DeploymentPreflight.TargetSpec(i.getId(), null); }
    private static DeploymentPreflight.TargetSpec target(TomcatInstance i, String filename) { return new DeploymentPreflight.TargetSpec(i.getId(), filename); }

    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private List<TargetState> states(DeploymentRun run) { return targets.findByRunIdOrderBySequenceAsc(run.getId()).stream().map(DeploymentTarget::getState).toList(); }

    @Test void sshDeploymentInstallsVerifiesAndBacksUpThePreviousWar() throws Exception {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        Path deployed = serverRoot.resolve("opt/tomcat/a/webapps/portal.war");

        DeploymentRun first = deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        assertThat(first.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(Files.readAllBytes(deployed)).isEqualTo(warA);
        try (Stream<Path> files = Files.list(deployed.getParent())) { assertThat(files.map(p -> p.getFileName().toString())).containsExactly("portal.war"); }

        DeploymentRun second = deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        assertThat(second.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(Files.readAllBytes(deployed)).isEqualTo(warB);
        DeploymentTarget t = targets.findByRunIdOrderBySequenceAsc(second.getId()).get(0);
        assertThat(t.getPreviousReleaseId()).isEqualTo(releaseA.getId());
        DeploymentBackup backup = backups.findByTargetId(t.getId()).orElseThrow();
        assertThat(backup.getSource()).isEqualTo(DeploymentBackup.Source.REMOTE_WAR);
        assertThat(backup.getSha256()).isEqualTo(sha(warA));
        assertThat(targets.findCurrentDeployment(a.getId(), "/portal").orElseThrow().getRun().getRelease().getId()).isEqualTo(releaseB.getId());
    }

    @Test void oneReleaseIsDeployedUnderDifferentFileNamesPerTarget() throws Exception {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "appA.war", Environment.TESTING);
        TomcatInstance b = sshInstance("tomcat-b", "/opt/tomcat/b", "appB.war", Environment.TESTING);
        DeploymentRun run = deploy(releaseA, Environment.TESTING, DeploymentRun.Strategy.PARALLEL, target(a), target(b, "custom-b.war"));
        assertThat(run.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(Files.readAllBytes(serverRoot.resolve("opt/tomcat/a/webapps/appA.war"))).isEqualTo(warA);
        assertThat(Files.readAllBytes(serverRoot.resolve("opt/tomcat/b/webapps/custom-b.war"))).isEqualTo(warA);
        assertThat(targets.findByRunIdOrderBySequenceAsc(run.getId())).extracting(DeploymentTarget::getContextPath).containsExactly("/appA", "/custom-b");
    }

    @Test void tomcatManagerDeploymentStreamsTheExactReleaseBytes() throws Exception {
        TomcatInstance m = managerInstance("tomcat-m", "shop#admin.war", Environment.DEVELOPMENT);
        DeploymentRun run = deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(m));
        assertThat(run.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(tomcat.deployedSha.get("/shop/admin")).isEqualTo(sha(warA));

        DeploymentRun update = deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(m));
        assertThat(update.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        DeploymentTarget t = targets.findByRunIdOrderBySequenceAsc(update.getId()).get(0);
        assertThat(backups.findByTargetId(t.getId()).orElseThrow().getReleaseId()).isEqualTo(releaseA.getId());
    }

    @Test void failedTargetStopsTheSequenceAndNothingIsReportedAsSuccess() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "bad.war", Environment.DEVELOPMENT);
        TomcatInstance b = sshInstance("tomcat-b", "/opt/tomcat/b", "good.war", Environment.DEVELOPMENT);
        tomcat.unhealthy.add("/bad");
        DeploymentRun run = deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a), target(b));
        assertThat(run.getState()).isEqualTo(DeploymentState.FAILED);
        assertThat(states(run)).containsExactly(TargetState.FAILED, TargetState.SKIPPED);
        assertThat(Files.exists(serverRoot.resolve("opt/tomcat/b/webapps/good.war"))).isFalse();
        // The timeline marks the step that failed (4 = health), not the final one; the skipped target never started.
        assertThat(api.status(run.getId(), 0).targets()).extracting(DeploymentApiController.TargetDto::step).containsExactly(4, 0);
        // The summary says why, not only how many.
        assertThat(recorder.run(run.getId()).getSummary()).startsWith("0 of 2 target(s) succeeded, 1 not started. tomcat-a: ")
                .contains("did not become healthy").doesNotContain("null");
    }

    @Test void unhealthyUpdateWithBackupRequiresRollbackAndPartialSuccessIsReported() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "ok.war", Environment.DEVELOPMENT);
        TomcatInstance b = sshInstance("tomcat-b", "/opt/tomcat/b", "flaky.war", Environment.DEVELOPMENT);
        assertThat(deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(b)).getState()).isEqualTo(DeploymentState.SUCCEEDED);
        tomcat.unhealthy.add("/flaky");
        DeploymentRun run = deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a), target(b));
        assertThat(run.getState()).isEqualTo(DeploymentState.PARTIALLY_SUCCEEDED);
        assertThat(states(run)).containsExactly(TargetState.SUCCEEDED, TargetState.ROLLBACK_REQUIRED);
        assertThat(api.status(run.getId(), 0).targets()).extracting(DeploymentApiController.TargetDto::step).containsExactly(5, 4);
        assertThat(targets.findCurrentDeployment(b.getId(), "/flaky").orElseThrow().getRun().getRelease().getId())
                .as("current state only advances after verification").isEqualTo(releaseA.getId());
    }

    private DeploymentService.Submission submission(Release release, Environment env, DeploymentRun.Strategy strategy, boolean ack, String confirm,
                                                    DeploymentPreflight.TargetSpec... specs) {
        return new DeploymentService.Submission(UUID.randomUUID(), new DeploymentPreflight.Request(release.getId(), env, null, List.of(specs),
                strategy, strategy == DeploymentRun.Strategy.PARALLEL ? 2 : 1), ack, confirm);
    }

    @Test void preflightBlocksUnsafeRequestsAndRequiresAcknowledgementAndConfirmation() {
        TomcatInstance dev = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        TomcatInstance prod = sshInstance("tomcat-b", "/opt/tomcat/b", "portal.war", Environment.PRODUCTION);

        assertThatThrownBy(() -> deployments.submit(submission(releaseA, Environment.PRODUCTION, DeploymentRun.Strategy.SEQUENTIAL, true, "1.0", target(dev)), "it"))
                .isInstanceOf(DeploymentService.PreflightBlockedException.class)
                .satisfies(e -> assertThat(((DeploymentService.PreflightBlockedException) e).getPreview().rows().get(0).blockers())
                        .anySatisfy(b -> assertThat(b).contains("belongs to Development")));
        assertThatThrownBy(() -> deployments.submit(submission(releaseA, Environment.PRODUCTION, DeploymentRun.Strategy.PARALLEL, true, "1.0", target(prod)), "it"))
                .isInstanceOf(DeploymentService.PreflightBlockedException.class)
                .satisfies(e -> assertThat(((DeploymentService.PreflightBlockedException) e).getPreview().blockers()).anySatisfy(b -> assertThat(b).contains("sequential")));
        assertThatThrownBy(() -> deployments.submit(submission(releaseA, Environment.PRODUCTION, DeploymentRun.Strategy.SEQUENTIAL, false, "1.0", target(prod)), "it"))
                .hasMessageContaining("acknowledge");
        assertThatThrownBy(() -> deployments.submit(submission(releaseA, Environment.PRODUCTION, DeploymentRun.Strategy.SEQUENTIAL, true, "1.1", target(prod)), "it"))
                .hasMessageContaining("Type the version 1.0");
        assertThat(Files.exists(serverRoot.resolve("opt/tomcat/b/webapps/portal.war"))).isFalse();

        DeploymentRun ok = awaitFinished(deployments.submit(submission(releaseA, Environment.PRODUCTION, DeploymentRun.Strategy.SEQUENTIAL, true, "1.0", target(prod)), "it").getId());
        assertThat(ok.getState()).isEqualTo(DeploymentState.SUCCEEDED);
    }

    @Test void switchedOffHealthCheckIsSkippedButWarnedAndLogged() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "nocheck.war", Environment.DEVELOPMENT);
        a.setHealthCheckEnabled(false); a.setHealthUrl(null); a.setHttpPort(null);
        instances.save(a);
        tomcat.unhealthy.add("/nocheck"); // would fail if it were checked

        DeploymentPreflight.Preview preview = preflight.evaluate(new DeploymentPreflight.Request(releaseA.getId(), Environment.DEVELOPMENT, null,
                List.of(target(a)), DeploymentRun.Strategy.SEQUENTIAL, 1), "it");
        assertThat(preview.canDeploy()).isTrue();
        assertThat(preview.rows().get(0).warnings()).anySatisfy(w -> assertThat(w).contains("health check is off"));

        DeploymentRun run = deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        assertThat(run.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(targets.findByRunIdOrderBySequenceAsc(run.getId()).get(0).getMessage()).contains("not checked");
        assertThat(jdbc.queryForObject("select count(*) from deployment_logs where run_id = ? and event = 'HEALTH_CHECK_SKIPPED'", Long.class, run.getId())).isEqualTo(1L);
        assertThat(api.status(run.getId(), 0).targets().get(0).stepsOff()).containsExactly(4); // the timeline shows "Health · off"
    }

    @Autowired DeploymentApiController api;

    @Test void switchedOffBackupDeploysWithoutARollbackPointButWarnsAndLogs() throws Exception {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        DeploymentRun first = deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        assertThat(first.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(api.status(first.getId(), 0).targets().get(0).stepsOff()).isEmpty();
        a.setBackupEnabled(false);
        instances.save(a);

        DeploymentPreflight.Preview preview = preflight.evaluate(new DeploymentPreflight.Request(releaseB.getId(), Environment.DEVELOPMENT, null,
                List.of(target(a)), DeploymentRun.Strategy.SEQUENTIAL, 1), "it");
        assertThat(preview.canDeploy()).isTrue();
        assertThat(preview.rows().get(0).backup()).isEqualTo("Off for this instance");
        assertThat(preview.rows().get(0).warnings()).anySatisfy(w -> assertThat(w).contains("Backup is off"));

        DeploymentRun run = deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        assertThat(run.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(Files.readAllBytes(serverRoot.resolve("opt/tomcat/a/webapps/portal.war"))).isEqualTo(warB);
        assertThat(backups.findByTargetId(targets.findByRunIdOrderBySequenceAsc(run.getId()).get(0).getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from deployment_logs where run_id = ? and event = 'BACKUP_SKIPPED'", Long.class, run.getId())).isEqualTo(1L);
        assertThat(api.status(run.getId(), 0).targets().get(0).stepsOff()).containsExactly(1); // the timeline shows "Backup · off"
    }

    @Autowired DeploymentPreflight preflight;

    @Test void repeatedSubmissionIsIdempotent() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        DeploymentService.Submission s = submission(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, true, null, target(a));
        DeploymentRun first = deployments.submit(s, "it");
        DeploymentRun again = deployments.submit(s, "it");
        assertThat(again.getId()).isEqualTo(first.getId());
        awaitFinished(first.getId());
        assertThat(jdbc.queryForObject("select count(*) from deployment_runs", Long.class)).isEqualTo(1L);
    }

    @Test void restartMidDeploymentMarksUnknownBlocksTheInstanceAndRequiresReconciliation() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        // Simulate a deployment that was in the middle of replacing the WAR when the process stopped.
        DeploymentRun run = jdbcRun(a);
        long targetId = targets.findByRunIdOrderBySequenceAsc(run.getId()).get(0).getId();
        recorder.runState(run.getId(), DeploymentState.VALIDATING, null);
        recorder.runState(run.getId(), DeploymentState.RUNNING, null);
        for (TargetState s : List.of(TargetState.BACKUP_IN_PROGRESS, TargetState.BACKUP_COMPLETED, TargetState.TRANSFER_IN_PROGRESS, TargetState.DEPLOYING))
            recorder.targetState(run.getId(), targetId, s, null);

        deployments.recoverAfterRestart();
        assertThat(recorder.target(targetId).getState()).isEqualTo(TargetState.UNKNOWN);
        assertThat(recorder.run(run.getId()).getState()).isEqualTo(DeploymentState.UNKNOWN);

        assertThatThrownBy(() -> deployments.submit(submission(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, true, null, target(a)), "it"))
                .isInstanceOf(DeploymentService.PreflightBlockedException.class)
                .satisfies(e -> assertThat(((DeploymentService.PreflightBlockedException) e).getPreview().rows().get(0).blockers())
                        .anySatisfy(b -> assertThat(b).contains("must be reconciled")));

        assertThatThrownBy(() -> deployments.reconcile(run.getId(), targetId, true, "ok", "admin")).hasMessageContaining("at least 5");
        deployments.reconcile(run.getId(), targetId, true, "Checked webapps: portal.war is 1.0 and healthy", "admin");
        assertThat(recorder.target(targetId).getState()).isEqualTo(TargetState.SUCCEEDED);
        assertThat(recorder.run(run.getId()).getState()).isEqualTo(DeploymentState.SUCCEEDED);
    }

    private DeploymentRun jdbcRun(TomcatInstance instance) {
        // A run persisted exactly as submit() does, but not handed to the engine.
        var preview = new DeploymentRun(UUID.randomUUID(), releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, 1, null, null, false, "it");
        DeploymentRun saved = runRepository.save(preview);
        targets.save(new DeploymentTarget(saved, instances.findById(instance.getId()).orElseThrow(), "portal.war", "/portal", null, 1));
        return saved;
    }

    @Autowired DeploymentRunRepository runRepository;

    @Test void cancellationStopsBeforeTheNextTargetAndIsNeverReportedAsSuccess() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "one.war", Environment.DEVELOPMENT);
        TomcatInstance b = sshInstance("tomcat-b", "/opt/tomcat/b", "two.war", Environment.DEVELOPMENT);
        tomcat.healthDelayMs = 300;
        DeploymentRun run = deployments.submit(submission(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, true, null, target(a), target(b)), "it");
        deployments.cancel(run.getId(), "it");
        DeploymentRun finished = awaitFinished(run.getId());
        assertThat(finished.getState()).isEqualTo(DeploymentState.CANCELLED);
        assertThat(states(finished).get(1)).isEqualTo(TargetState.SKIPPED);
        assertThat(Files.exists(serverRoot.resolve("opt/tomcat/b/webapps/two.war"))).isFalse();
    }

    // ---------------------------------------------------------------- Milestone 6: rollback, backups, monitoring

    @Autowired RollbackPreflight rollbackPreflight;
    @Autowired RollbackService rollbacks;
    @Autowired BackupService backupService;
    @Autowired ReconciliationInspector reconciliation;
    @Autowired com.tomcatmanager.health.HealthMonitor monitor;
    @Autowired com.tomcatmanager.health.InstanceHealthStatusRepository healthStatuses;

    private DeploymentRun rollBack(DeploymentTarget original, String confirmation) {
        DeploymentRun run = rollbacks.submit(new RollbackService.Submission(UUID.randomUUID(), original.getId(), true, true, confirmation), "it");
        return awaitFinished(run.getId());
    }

    private DeploymentTarget onlyTarget(DeploymentRun run) { return targets.findByRunIdOrderBySequenceAsc(run.getId()).get(0); }

    @Test void rollbackAfterAnUnhealthyUpdateRestoresTheServerBackupVerifiesItAndClosesTheOriginal() throws Exception {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        Path deployed = serverRoot.resolve("opt/tomcat/a/webapps/portal.war");
        assertThat(deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)).getState()).isEqualTo(DeploymentState.SUCCEEDED);
        tomcat.unhealthy.add("/portal");
        DeploymentRun bad = deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        DeploymentTarget original = onlyTarget(bad);
        assertThat(original.getState()).isEqualTo(TargetState.ROLLBACK_REQUIRED);
        tomcat.unhealthy.remove("/portal"); // the previous version is healthy again once restored

        RollbackPreflight.Preview preview = rollbackPreflight.evaluate(original.getId(), "it");
        assertThat(preview.canRollBack()).as(String.join("; ", preview.blockers())).isTrue();
        assertThat(preview.restoredVersion()).as("server backup identical to release 1.0").isEqualTo("1.0");
        assertThat(preview.integrity()).contains("SHA-256 matches");
        assertThat(preview.filename()).isEqualTo("portal.war");

        DeploymentRun rollback = rollBack(original, null);
        assertThat(rollback.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(rollback.isRollback()).isTrue();
        assertThat(rollback.getRollbackOfTargetId()).isEqualTo(original.getId());
        assertThat(Files.readAllBytes(deployed)).isEqualTo(warA);
        DeploymentTarget closed = recorder.target(original.getId());
        assertThat(closed.getState()).isEqualTo(TargetState.ROLLED_BACK);
        assertThat(closed.getRolledBackByRunId()).isEqualTo(rollback.getId());
        assertThat(targets.findCurrentDeployment(a.getId(), "/portal").orElseThrow().getRun().getId()).isEqualTo(rollback.getId());
        assertThat(jdbc.queryForObject("select count(*) from deployment_logs where run_id = ? and event = 'ROLLED_BACK'", Long.class, bad.getId()))
                .as("the original deployment's log records the rollback; its failure logs stay").isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from deployment_logs where run_id = ? and event = 'HEALTH_CHECK_FAILED'", Long.class, bad.getId())).isEqualTo(1L);
        assertThat(rollbackPreflight.evaluate(original.getId(), "it").blockers()).anySatisfy(b -> assertThat(b).contains("already rolled back"));
    }

    @Test void aRollbackWhoseRestoredVersionIsUnhealthyFailsAndTheOriginalStillRequiresRollback() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        tomcat.unhealthy.add("/portal");
        DeploymentTarget original = onlyTarget(deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)));

        DeploymentRun rollback = rollBack(original, null);
        assertThat(rollback.getState()).as("never reported as success before verification").isEqualTo(DeploymentState.FAILED);
        assertThat(onlyTarget(rollback).getMessage()).contains("restored version is in place but did not become healthy");
        assertThat(recorder.target(original.getId()).getState()).isEqualTo(TargetState.ROLLBACK_REQUIRED);
        assertThat(recorder.target(original.getId()).getRolledBackByRunId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from deployment_logs where run_id = ? and event = 'ROLLBACK_NOT_COMPLETED'", Long.class,
                original.getRun().getId())).isEqualTo(1L);
    }

    @Test void manualRollbackOfASucceededManagerDeploymentRestoresThePreviousLibraryRelease() throws Exception {
        TomcatInstance m = managerInstance("tomcat-m", "shop.war", Environment.DEVELOPMENT);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(m));
        DeploymentTarget update = onlyTarget(deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(m)));
        assertThat(update.getState()).isEqualTo(TargetState.SUCCEEDED);
        assertThat(tomcat.deployedSha.get("/shop")).isEqualTo(sha(warB));
        assertThat(rollbackPreflight.isRollbackCandidate(targets.findWithRun(update.getId()).orElseThrow())).isTrue();

        DeploymentRun rollback = rollBack(update, null);
        assertThat(rollback.getState()).isEqualTo(DeploymentState.SUCCEEDED);
        assertThat(rollback.getRelease().getId()).isEqualTo(releaseA.getId());
        assertThat(tomcat.deployedSha.get("/shop")).isEqualTo(sha(warA));
        DeploymentTarget after = targets.findWithRun(update.getId()).orElseThrow();
        assertThat(after.getState()).as("a succeeded target stays succeeded, linked to its rollback").isEqualTo(TargetState.SUCCEEDED);
        assertThat(after.getRolledBackByRunId()).isEqualTo(rollback.getId());
        assertThat(rollbackPreflight.isRollbackCandidate(after)).isFalse();
    }

    @Test void rollbackIsBlockedAfterALaterChangeOrWhenTheBackupIsCorruptAndProductionNeedsTheInstanceName() throws Exception {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.PRODUCTION);
        deploy(releaseA, Environment.PRODUCTION, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        DeploymentTarget second = onlyTarget(deploy(releaseB, Environment.PRODUCTION, DeploymentRun.Strategy.SEQUENTIAL, target(a)));
        DeploymentTarget third = onlyTarget(deploy(releaseA, Environment.PRODUCTION, DeploymentRun.Strategy.SEQUENTIAL, target(a)));

        assertThat(rollbackPreflight.evaluate(second.getId(), "it").blockers()).anySatisfy(b -> assertThat(b).contains("after this deployment"));
        RollbackPreflight.Preview latest = rollbackPreflight.evaluate(third.getId(), "it");
        assertThat(latest.canRollBack()).isTrue();
        assertThat(latest.production()).isTrue();
        assertThatThrownBy(() -> rollBack(third, "wrong")).hasMessageContaining("Type the instance name");

        DeploymentBackup backup = backups.findByTargetId(third.getId()).orElseThrow();
        Path file = restorePoints.backupFile(backup);
        file.toFile().setWritable(true, false);
        Files.write(file, "tampered".getBytes());
        RollbackPreflight.Preview corrupt = rollbackPreflight.evaluate(third.getId(), "it");
        assertThat(corrupt.blockers()).anySatisfy(b -> assertThat(b).contains("integrity check"));
        assertThat(backups.findById(backup.getId()).orElseThrow().getLastVerificationOk()).isFalse();
    }

    @Autowired RestorePoints restorePoints;

    @Test void theCurrentRestorePointIsDeletedOnlyWithConfirmationAndRollbackThenUsesTheLibraryRelease() throws Exception {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        DeploymentTarget second = onlyTarget(deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)));
        DeploymentBackup rollbackPoint = backups.findByTargetId(second.getId()).orElseThrow();
        assertThatThrownBy(() -> backupService.delete(rollbackPoint.getId(), "admin", "free some space"))
                .hasMessageContaining("Confirm that you want to delete it");
        assertThat(backupService.verify(rollbackPoint.getId(), "admin").ok()).isTrue();

        backupService.delete(rollbackPoint.getId(), "admin", "free some space", true);
        assertThat(Files.exists(restorePoints.backupFile(rollbackPoint))).isFalse();
        assertThat(jdbc.queryForObject("select summary from audit_events where action = 'BACKUP_DELETED' and resource_id = ?", String.class,
                String.valueOf(rollbackPoint.getId()))).contains("was the current rollback point").contains("previous library release");
        RollbackPreflight.Preview fallback = rollbackPreflight.evaluate(second.getId(), "it");
        assertThat(fallback.canRollBack()).isTrue();
        assertThat(fallback.restoreDescription()).isEqualTo("Previous release 1.0 from the release library");
    }

    @Test void aSupersededBackupIsDeletedWithoutExtraConfirmation() throws Exception {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        DeploymentTarget second = onlyTarget(deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)));
        DeploymentBackup protectedBackup = backups.findByTargetId(second.getId()).orElseThrow();

        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)); // supersedes the second deployment
        Path file = restorePoints.backupFile(protectedBackup);
        assertThat(Files.exists(file)).isTrue();
        backupService.delete(protectedBackup.getId(), "admin", "superseded by a newer deployment");
        assertThat(Files.exists(file)).isFalse();
        assertThat(backups.findById(protectedBackup.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(backupService.list()).noneMatch(e -> e.backup().getId().equals(protectedBackup.getId()));
    }

    @Test void perInstanceHealthPolicyIsUsedAfterDeployment() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        a.setHealthConsecutiveSuccesses(3); a.setStartupTimeoutSeconds(30); a.setHealthExpectedText("UP");
        instances.save(a);
        DeploymentRun run = deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        // FakeTomcat answers "status", not "UP": the expected text makes the health check fail.
        assertThat(run.getState()).isEqualTo(DeploymentState.FAILED);
        assertThat(jdbc.queryForObject("select message from deployment_logs where run_id = ? and event = 'HEALTH_CHECK_STARTED'", String.class, run.getId()))
                .contains("needs 3 consecutive HTTP 200-299 containing \"UP\" responses within 30 s");
        assertThat(onlyTarget(run).getMessage()).contains("does not contain \"UP\"");
    }

    @Test void deploymentsAndRollbacksAreAuditedAndNotified() {
        jdbc.execute("TRUNCATE audit_events, notification_reads, notifications RESTART IDENTITY");
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        tomcat.unhealthy.add("/portal");
        DeploymentTarget bad = onlyTarget(deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)));
        tomcat.unhealthy.remove("/portal");
        DeploymentRun rollback = rollBack(bad, null);
        // The completion record is written right after the final state; wait for it briefly.
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline) && jdbc.queryForObject("select count(*) from notifications where link = ?", Long.class,
                "/deployments/" + rollback.getId()) == 0) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        assertThat(jdbc.queryForList("select action from audit_events order by id", String.class)).containsSubsequence(
                "DEPLOYMENT_REQUESTED", "DEPLOYMENT_COMPLETED", "DEPLOYMENT_REQUESTED", "DEPLOYMENT_COMPLETED", "ROLLBACK_REQUESTED", "ROLLBACK_COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action = 'DEPLOYMENT_COMPLETED' and outcome = 'FAILURE'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForList("select level || ' ' || category from notifications order by id", String.class))
                .containsExactly("SUCCESS DEPLOYMENT", "DANGER DEPLOYMENT", "SUCCESS ROLLBACK");
    }

    @Test void monitoringTracksDegradedUnhealthyAndRecovery() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        assertThat(monitor.checkNow(a.getId(), "it").isHealthy()).isTrue();
        tomcat.unhealthy.add("/portal");
        assertThat(monitor.checkNow(a.getId(), "it").getDetail()).contains("HTTP 503");
        assertThat(healthStatuses.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(com.tomcatmanager.health.InstanceHealthStatus.Status.DEGRADED);
        monitor.checkNow(a.getId(), "it");
        var status = healthStatuses.findById(a.getId()).orElseThrow();
        assertThat(status.getStatus()).isEqualTo(com.tomcatmanager.health.InstanceHealthStatus.Status.UNHEALTHY);
        assertThat(status.getConsecutiveFailures()).isEqualTo(2);
        tomcat.unhealthy.remove("/portal");
        monitor.checkNow(a.getId(), "it");
        status = healthStatuses.findById(a.getId()).orElseThrow();
        assertThat(status.getStatus()).isEqualTo(com.tomcatmanager.health.InstanceHealthStatus.Status.HEALTHY);
        assertThat(status.getLastRecoveredAt()).isNotNull();
        assertThat(monitor.history(a.getId(), 10)).hasSize(4);
    }

    @Test void inspectionGivesEvidenceAndAnUnknownTargetCanBeReconciledAsRollbackRequired() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)); // portal.war = release 1.0 on the server
        DeploymentRun run = jdbcRun(a);                                                          // an interrupted deployment of 1.0
        long targetId = onlyTarget(run).getId();
        recorder.runState(run.getId(), DeploymentState.VALIDATING, null);
        recorder.runState(run.getId(), DeploymentState.RUNNING, null);
        for (TargetState s : List.of(TargetState.BACKUP_IN_PROGRESS, TargetState.BACKUP_COMPLETED, TargetState.TRANSFER_IN_PROGRESS, TargetState.DEPLOYING))
            recorder.targetState(run.getId(), targetId, s, null);
        deployments.recoverAfterRestart();

        ReconciliationInspector.Evidence evidence = reconciliation.inspect(run.getId(), targetId, "admin");
        assertThat(evidence.findings()).anySatisfy(f -> assertThat(f).contains("SHA-256 matches"));
        assertThat(evidence.suggestion()).isEqualTo(TargetState.SUCCEEDED);

        assertThatThrownBy(() -> deployments.reconcile(run.getId(), targetId, TargetState.ROLLBACK_REQUIRED, "new version bad", "admin"))
                .as("no restore point was recorded for this target").hasMessageContaining("no restore point");
        backups.save(DeploymentBackup.libraryRelease(targetId, releaseB.getId(), releaseB.getArtifact().getSha256(), releaseB.getArtifact().getSizeBytes()));
        deployments.reconcile(run.getId(), targetId, TargetState.ROLLBACK_REQUIRED, "Health endpoint broken after the update", "admin");
        assertThat(recorder.target(targetId).getState()).isEqualTo(TargetState.ROLLBACK_REQUIRED);
        assertThat(recorder.run(run.getId()).getState()).isEqualTo(DeploymentState.FAILED);
    }

    // ---- storage cleanup ----

    @Autowired StorageCleanupService cleanup;
    @Autowired com.tomcatmanager.release.StoredArtifactRepository storedArtifacts;
    @Autowired com.tomcatmanager.release.StorageHousekeeping housekeeping;
    @Autowired com.tomcatmanager.release.ArtifactStorage storage;
    @Autowired org.springframework.web.context.WebApplicationContext web;

    @Test void theRunningVersionNeedsConfirmationAnOldOneIsRemovedKeepingHistoryAndAnIdenticalUploadRestoresIt() throws Exception {
        jdbc.execute("TRUNCATE audit_events RESTART IDENTITY");
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        assertThat(cleanup.describe(releases.find(releaseA.getId())).state()).isEqualTo(StorageCleanupService.FileState.RUNNING);
        assertThatThrownBy(() -> cleanup.removeReleaseFile(releaseA.getId(), "admin", "free some space", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Currently running on tomcat-a").hasMessageContaining("Confirm");

        // After 1.1 replaces it (with a server backup as rollback point), 1.0 is no longer needed.
        DeploymentRun second = deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        assertThat(cleanup.describe(releases.find(releaseA.getId())).state()).isEqualTo(StorageCleanupService.FileState.STORED);
        assertThatThrownBy(() -> cleanup.removeReleaseFile(releaseA.getId(), "admin", "x", false)).hasMessageContaining("reason");
        cleanup.removeReleaseFile(releaseA.getId(), "admin", "old version, disk almost full", false);

        Release removed = releases.find(releaseA.getId());
        assertThat(removed.getArtifact().isFileRemoved()).isTrue();
        assertThat(storage.exists(removed.getArtifact().getStoragePath())).isFalse();
        assertThat(runRepository.findByReleaseIdOrderByRequestedAtDesc(releaseA.getId())).hasSize(1); // history kept
        assertThat(housekeeping.run().missing()).as("removed on purpose is not reported missing").isEmpty();
        assertThat(storedArtifacts.totalStoredBytes()).isEqualTo(warB.length);
        DeploymentPreflight.Preview preview = preflight.evaluate(new DeploymentPreflight.Request(releaseA.getId(), Environment.DEVELOPMENT, null,
                List.of(target(a)), DeploymentRun.Strategy.SEQUENTIAL, 1), "it");
        assertThat(preview.blockers()).anySatisfy(b -> assertThat(b).contains("removed from storage by admin").contains("Upload the identical WAR"));
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action = 'RELEASE_FILE_REMOVED'", Long.class)).isEqualTo(1L);
        assertThat(second.getState()).isEqualTo(DeploymentState.SUCCEEDED);

        // Uploading the identical bytes again restores the file for the existing release.
        assertThatThrownBy(() -> releases.upload(new ReleaseService.UploadCommand(releaseA.getApplication().getId(), "1.0-again", null, "portal-1.0.war"),
                new ByteArrayInputStream(warA), "admin")).isInstanceOf(com.tomcatmanager.release.DuplicateReleaseException.class).hasMessageContaining("now restored");
        Release restored = releases.find(releaseA.getId());
        assertThat(restored.getArtifact().isFileRemoved()).isFalse();
        assertThat(storage.verify(restored.getArtifact()).ok()).isTrue();
    }

    @Test void aLibraryRollbackPointNeedsConfirmation() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        a.setBackupEnabled(false); // no server backup: a rollback would restore the previous library release
        instances.save(a);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));

        StorageCleanupService.ReleaseFile file = cleanup.describe(releases.find(releaseA.getId()));
        assertThat(file.state()).isEqualTo(StorageCleanupService.FileState.ROLLBACK_POINT);
        assertThat(file.reasons()).anySatisfy(r -> assertThat(r).startsWith("Rollback point of deployment #2"));
        assertThatThrownBy(() -> cleanup.removeReleaseFile(releaseA.getId(), "admin", "free some space", false)).hasMessageContaining("Confirm");
        cleanup.removeReleaseFile(releaseA.getId(), "admin", "free some space", true);
        assertThat(releases.find(releaseA.getId()).getArtifact().getFileRemovedReason()).contains("was a rollback point");
    }

    @Test void theFileOfARunningVersionIsRemovedOnlyWithConfirmation_theServerAndHistoryAreUntouched() {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        Long file = releaseA.getArtifact().getId();
        assertThat(cleanup.removeReleaseFiles(List.of(file), "admin", "disk almost full").skipped()).as("not confirmed").isEqualTo(1);

        StorageCleanupService.Result result = cleanup.removeReleaseFiles(List.of(file), "admin", "disk almost full", true);
        assertThat(result.removed()).isEqualTo(1);
        Release removed = releases.find(releaseA.getId());
        assertThat(removed.getArtifact().isFileRemoved()).isTrue();
        assertThat(removed.getArtifact().getFileRemovedReason()).contains("was in use").contains("Currently running on tomcat-a");
        assertThat(runRepository.findByReleaseIdOrderByRequestedAtDesc(releaseA.getId())).as("history kept").hasSize(1);

        // The next deployment over it still succeeds; over SSH it backs up the running WAR from the server.
        assertThat(deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)).getState()).isEqualTo(DeploymentState.SUCCEEDED);
    }

    @Test void selectedReleaseFilesAndBackupsAreRemovedNothingIsPreselectedAndProtectedOnesAreSkipped() throws Exception {
        TomcatInstance a = sshInstance("tomcat-a", "/opt/tomcat/a", "portal.war", Environment.DEVELOPMENT);
        TomcatInstance b = sshInstance("tomcat-b", "/opt/tomcat/b", "portal.war", Environment.DEVELOPMENT);
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a));
        deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)); // backup of 1.0 on a
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(a)); // backup of 1.1 on a: the rollback point
        deploy(releaseB, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(b));
        deploy(releaseA, Environment.DEVELOPMENT, DeploymentRun.Strategy.SEQUENTIAL, target(b)); // backup of 1.1 on b: rollback point
        // 1.0 now runs on both instances; 1.1 runs nowhere and is no library rollback point (server backups exist).

        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var admin = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN");
        String page = mvc.perform(get("/releases").param("tab", "storage").with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Portal").contains("1.1").contains("Can be removed").contains("In use · needs confirmation").contains("Nothing selected");
        assertThat(page).as("nothing is preselected").doesNotContainPattern("name=\"artifactIds\"[^>]*checked");
        assertThat(mvc.perform(get("/releases/" + releaseA.getId()).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("In use").contains("Currently running on tomcat-a");

        // Filtering by version.
        assertThat(cleanup.releaseFiles(null, "1.1")).extracting(f -> f.artifact().getId()).containsExactly(releaseB.getArtifact().getId());
        assertThat(cleanup.releaseFiles(releaseA.getApplication().getId(), "")).hasSize(2);
        assertThat(cleanup.releaseFiles(null, "no-such-version")).isEmpty();

        // Selecting both: the running 1.0 is skipped, 1.1 is removed.
        StorageCleanupService.Result result = cleanup.removeReleaseFiles(List.of(releaseB.getArtifact().getId(), releaseA.getArtifact().getId()), "admin", "disk almost full");
        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.skipped()).as("the running version is skipped even when selected").isEqualTo(1);
        assertThat(cleanup.releaseFiles(null, "")).extracting(f -> f.artifact().getId()).containsExactly(releaseA.getArtifact().getId());
        assertThat(mvc.perform(get("/releases/" + releaseB.getId()).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("File removed · not deployable").contains("Reason: disk almost full");
        assertThat(mvc.perform(get("/releases").with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("1 removed to free space").contains("Clean up storage");

        // Backups: filtered by instance; the rollback point of each instance cannot be selected.
        assertThat(cleanup.backupInstances()).containsOnlyKeys(a.getId(), b.getId());
        List<BackupService.Entry> onA = cleanup.serverBackups(a.getId());
        assertThat(onA).hasSize(2).allSatisfy(e -> assertThat(e.target().getInstanceId()).isEqualTo(a.getId()));
        assertThat(cleanup.serverBackups(b.getId())).hasSize(1).allSatisfy(e -> assertThat(e.rollbackPoint()).isTrue());
        String backupPage = mvc.perform(get("/backups").param("instance", String.valueOf(a.getId())).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(backupPage).contains("Current rollback point").contains("Remove selected files").doesNotContain("<strong>tomcat-b</strong>");

        long oldOnA = onA.stream().filter(e -> !e.rollbackPoint()).findFirst().orElseThrow().backup().getId();
        long pointOnA = onA.stream().filter(BackupService.Entry::rollbackPoint).findFirst().orElseThrow().backup().getId();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/backups/cleanup")
                        .param("backupIds", String.valueOf(oldOnA), String.valueOf(pointOnA)).param("reason", "disk almost full")
                        .param("instance", String.valueOf(a.getId())).with(admin)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/backups?instance=" + a.getId() + "&tab=storage"));
        assertThat(cleanup.serverBackups(a.getId())).extracting(e -> e.backup().getId()).containsExactly(pointOnA);
        assertThat(backups.findByDeletedAtIsNullOrderByCreatedAtDesc()).hasSize(2);

        // With confirmation, the rollback point is deleted too; the deployment history keeps a record of it.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/backups/cleanup")
                        .param("backupIds", String.valueOf(pointOnA)).param("reason", "disk almost full").param("confirmProtected", "true")
                        .with(admin).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(cleanup.serverBackups(a.getId())).isEmpty();
        assertThat(backups.findById(pointOnA).orElseThrow().getDeletedAt()).isNotNull();
    }
}
