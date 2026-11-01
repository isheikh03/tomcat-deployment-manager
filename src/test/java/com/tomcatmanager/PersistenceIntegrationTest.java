package com.tomcatmanager;

import com.tomcatmanager.connectivity.CheckStatus;
import com.tomcatmanager.connectivity.CheckType;
import com.tomcatmanager.connectivity.ConnectionTestResult;
import com.tomcatmanager.connectivity.ConnectionTestResultRepository;
import com.tomcatmanager.connectivity.ProbeReport;
import com.tomcatmanager.infrastructure.ManagedHost;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migrations, Hibernate schema validation, constraints and custom queries of the infrastructure model on PostgreSQL. */
class PersistenceIntegrationTest extends DatabaseIntegrationTest {
    @Autowired ManagedHostRepository hosts;
    @Autowired TomcatInstanceRepository instances;
    @Autowired ConnectionTestResultRepository results;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE release_artifacts, stored_artifacts, applications, connection_test_results, server_group_instances, tomcat_instances, server_groups, managed_hosts RESTART IDENTITY CASCADE");
    }

    private ManagedHost host(String name) {
        ManagedHost host = new ManagedHost();
        host.setName(name); host.setHostname("10.0.0.5"); host.setSshUsername("deploy");
        return hosts.save(host);
    }

    private TomcatInstance instance(ManagedHost host, String name, String base, String filename) {
        TomcatInstance i = new TomcatInstance();
        i.setName(name); i.setHost(host); i.setCatalinaBase(base); i.setHealthUrl("http://10.0.0.5:8080/health"); i.setDeploymentFilename(filename);
        i.setTomcatVersion("10.1.30"); i.setJavaVersion(21);
        return instances.save(i);
    }

    @Test void latestConnectionResultPerInstance() {
        ManagedHost host = host("it-host");
        TomcatInstance instance = instance(host, "a", "/opt/tomcat/a", "appA.war");
        ProbeReport failed = new ProbeReport(); failed.failed(CheckType.SSH_CONNECTION, "refused");
        ProbeReport ok = new ProbeReport(); ok.success(CheckType.SSH_CONNECTION, "ok");
        Instant t = Instant.now();
        results.save(new ConnectionTestResult(UUID.randomUUID(), host.getId(), instance.getId(), ConnectionTestResult.TestType.INSTANCE_SSH_SFTP, "it", t.minusSeconds(60), t.minusSeconds(59), failed));
        results.save(new ConnectionTestResult(UUID.randomUUID(), host.getId(), instance.getId(), ConnectionTestResult.TestType.INSTANCE_SSH_SFTP, "it", t.minusSeconds(2), t, ok));

        assertThat(results.findLatestInstanceTests()).singleElement().satisfies(r -> {
            assertThat(r.getOutcome()).isEqualTo(CheckStatus.SUCCESS);
            assertThat(r.getChecks()).hasSize(1);
        });
        assertThat(instances.existsByHostId(host.getId())).isTrue();
        assertThat(instances.findById(instance.getId()).orElseThrow().getTomcatVersion()).isEqualTo("10.1.30");
    }

    @Test void hostNamesAreUniqueCaseInsensitively() {
        host("Prod-01");
        assertThatThrownBy(() -> host("prod-01")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void deploymentFilenameIsUniquePerDeploymentDirectory() {
        ManagedHost host = host("it-host");
        instance(host, "a", "/opt/tomcat/a", "appA.war");
        instance(host, "b", "/opt/tomcat/b", "appA.war");
        assertThatThrownBy(() -> instance(host, "a2", "/opt/tomcat/a", "APPA.war")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void connectionResultsAreRemovedWithTheirHost() {
        ManagedHost host = host("it-host");
        ProbeReport ok = new ProbeReport(); ok.success(CheckType.SSH_CONNECTION, "ok");
        results.save(new ConnectionTestResult(UUID.randomUUID(), host.getId(), null, ConnectionTestResult.TestType.HOST_SSH, "it", Instant.now(), Instant.now(), ok));
        hosts.deleteById(host.getId());
        assertThat(results.count()).isZero();
    }
}
