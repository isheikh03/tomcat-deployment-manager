package com.tomcatmanager.release;

import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.deployment.QuickDeploymentService;
import com.tomcatmanager.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Quick Deployment upload end to end: application resolution, release reuse and version numbering. */
class QuickDeploymentUploadIntegrationTest extends DatabaseIntegrationTest {
    @Autowired QuickDeploymentService quick;
    @Autowired ApplicationRepository applications;
    @Autowired ReleaseRepository releases;
    @Autowired ArtifactStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.tomcatmanager.access.TeamRepository teams;

    private byte[] warA, warB;

    @BeforeEach void setUp() throws Exception {
        jdbc.execute("TRUNCATE release_artifacts, stored_artifacts, applications RESTART IDENTITY CASCADE");
        for (String stored : storage.storedFiles().keySet()) storage.deleteFile(storage.resolveStored(stored));
        warA = TestWars.war().jakartaWebXml().clazz("A", 17, null).entry("index.jsp", "a").bytes();
        warB = TestWars.war().jakartaWebXml().clazz("A", 17, null).entry("index.jsp", "b").bytes();
    }

    private QuickDeploymentService.Prepared prepare(String app, String version, String filename, byte[] war, boolean admin) throws Exception {
        Long team = teams.findByDefaultTeamTrue().orElseThrow().getId();
        com.tomcatmanager.access.AccessScope scope = admin ? com.tomcatmanager.access.AccessScope.everything("admin")
                : com.tomcatmanager.access.AccessScope.of("operator", com.tomcatmanager.users.Role.DEPLOYMENT_OPERATOR, java.util.Set.of(),
                        java.util.Map.of(), java.util.Set.of(), java.util.Set.of(team), java.util.Set.of(team));
        return quick.prepare(app, version, filename, new ByteArrayInputStream(war), admin ? "admin" : "operator", scope);
    }

    @Test void createsApplicationAndReleaseFromTheFileName() throws Exception {
        var p = prepare(null, null, "customer-portal-2.4.1.war", warA, true);
        assertThat(p.applicationName()).isEqualTo("customer-portal");
        assertThat(p.applicationCreated()).isTrue();
        assertThat(p.versionLabel()).isEqualTo("2.4.1");
        assertThat(p.reusedRelease()).isFalse();
        assertThat(p.detectedJavaVersion()).isEqualTo(17);
        assertThat(applications.findByNameIgnoreCase("customer-portal")).isPresent();
        assertThat(releases.findById(p.releaseId()).orElseThrow().getReleaseNotes()).isNull(); // Production still needs real notes
    }

    @Test void identicalWarReusesTheReleaseAndNewContentGetsAUniqueVersion() throws Exception {
        var first = prepare(null, null, "portal-1.0.war", warA, true);
        var again = prepare(null, null, "portal-1.0.war", warA, true);
        assertThat(again.reusedRelease()).isTrue();
        assertThat(again.releaseId()).isEqualTo(first.releaseId());
        assertThat(again.applicationCreated()).isFalse();

        var rebuilt = prepare("PORTAL", null, "portal-1.0.war", warB, false); // existing app, case-insensitive
        assertThat(rebuilt.applicationId()).isEqualTo(first.applicationId());
        assertThat(rebuilt.versionLabel()).isEqualTo("1.0-2");
        assertThat(releases.count()).isEqualTo(2);
    }

    @Test void typedVersionMustBeNewForDifferentContent() throws Exception {
        prepare("portal", "1.0", "a.war", warA, true);
        assertThatThrownBy(() -> prepare("portal", "1.0", "b.war", warB, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
    }

    @Test void operatorCannotCreateApplications() {
        assertThatThrownBy(() -> prepare(null, null, "newapp-1.0.war", warA, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no application named newapp");
        assertThat(applications.count()).isZero();
    }

    @Test void invalidWarCreatesNothing() {
        assertThatThrownBy(() -> prepare(null, null, "broken-1.0.war", new byte[]{1, 2, 3}, true)).isInstanceOf(InvalidWarException.class);
        assertThat(applications.count()).isZero();
        assertThat(releases.count()).isZero();
    }
}
