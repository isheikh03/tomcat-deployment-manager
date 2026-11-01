package com.tomcatmanager.release;

import com.tomcatmanager.application.ApplicationForm;
import com.tomcatmanager.application.ApplicationService;
import com.tomcatmanager.application.ManagedApplication;
import com.tomcatmanager.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The release library end to end: service, real PostgreSQL, real file storage. */
class ReleaseLibraryIntegrationTest extends DatabaseIntegrationTest {
    @Autowired ReleaseService releases;
    @Autowired ReleaseRepository releaseRepository;
    @Autowired StoredArtifactRepository artifacts;
    @Autowired ApplicationService applications;
    @Autowired ArtifactStorage storage;
    @Autowired StorageHousekeeping housekeeping;
    @Autowired JdbcTemplate jdbc;

    private ManagedApplication portal, billing;
    private byte[] warA, warB, warLegacy;

    @BeforeEach void setUp() throws Exception {
        jdbc.execute("TRUNCATE release_artifacts, stored_artifacts, applications RESTART IDENTITY CASCADE");
        for (String stored : storage.storedFiles().keySet()) storage.deleteFile(storage.resolveStored(stored));
        portal = app("Customer Portal", ManagedApplication.ServletApi.DETECT);
        billing = app("Billing", ManagedApplication.ServletApi.JAVAX);
        warA = TestWars.war().jakartaWebXml().clazz("A", 17, null).entry("index.jsp", "a").bytes();
        warB = TestWars.war().jakartaWebXml().clazz("A", 21, null).entry("index.jsp", "b").bytes();
        warLegacy = TestWars.war().javaxWebXml().clazz("A", 8, null).bytes();
    }

    private ManagedApplication app(String name, ManagedApplication.ServletApi api) {
        ApplicationForm form = new ApplicationForm();
        form.setName(name); form.setServletApi(api);
        return applications.save(form, "it");
    }

    private Release upload(ManagedApplication app, String version, byte[] war, String filename) throws Exception {
        return releases.upload(new ReleaseService.UploadCommand(app.getId(), version, "notes for " + version, filename), new ByteArrayInputStream(war), "it");
    }

    @Test void uploadPersistsMetadataInspectionAndReadOnlyFile() throws Exception {
        Release r = upload(portal, "2.4.1", warA, "C:\\builds\\customer-portal-2.4.1.war");
        Release loaded = releases.find(r.getId());
        assertThat(loaded.getOriginalFilename()).isEqualTo("customer-portal-2.4.1.war");
        assertThat(loaded.getUploadedBy()).isEqualTo("it");
        assertThat(loaded.getReleaseNotes()).isEqualTo("notes for 2.4.1");
        StoredArtifact a = loaded.getArtifact();
        assertThat(a.getSha256()).matches("[0-9a-f]{64}");
        assertThat(a.getSizeBytes()).isEqualTo(warA.length);
        assertThat(a.getDetectedJavaVersion()).isEqualTo(17);
        assertThat(a.getDetectedServletApi()).isEqualTo(StoredArtifact.ServletApi.JAKARTA);
        Path file = storage.resolveStored(a.getStoragePath());
        assertThat(Files.readAllBytes(file)).isEqualTo(warA);
        assertThat(Files.isWritable(file)).isFalse();
        assertThat(releases.verify(r.getId(), "it").ok()).isTrue();
        assertThat(releases.find(r.getId()).getLastVerificationOk()).isTrue();
    }

    @Test void identicalBytesAreDeduplicatedAndSameAppDuplicatesRefused() throws Exception {
        Release first = upload(portal, "2.4.1", warA, "portal.war");
        assertThatThrownBy(() -> upload(portal, "2.4.1-again", warA, "portal.war"))
                .isInstanceOf(DuplicateReleaseException.class)
                .satisfies(e -> assertThat(((DuplicateReleaseException) e).getExistingReleaseId()).isEqualTo(first.getId()));

        Release shared = upload(billing, "9.9", warA, "portal.war");
        assertThat(shared.getArtifact().getId()).isEqualTo(first.getArtifact().getId());
        assertThat(artifacts.count()).isEqualTo(1);
        assertThat(artifacts.totalStoredBytes()).isEqualTo(warA.length);
    }

    @Test void versionLabelsAreUniquePerApplicationIgnoringCase() throws Exception {
        upload(portal, "2.4.1-rc1", warA, "portal.war");
        assertThatThrownBy(() -> upload(portal, "2.4.1-RC1", warB, "portal.war")).hasMessageContaining("already exists");
        upload(billing, "2.4.1-RC1", warLegacy, "billing.war");
        assertThat(artifacts.count()).isEqualTo(2);
    }

    @Test void invalidWarIsRejectedAndNothingIsStored() throws Exception {
        assertThatThrownBy(() -> upload(portal, "1.0", "not a zip".getBytes(), "portal.war")).isInstanceOf(InvalidWarException.class);
        assertThat(artifacts.count()).isZero();
        assertThat(storage.storedFiles()).isEmpty();
    }

    @Test void searchFiltersAndPaginates() throws Exception {
        upload(portal, "2.4.1", warA, "customer-portal-2.4.1.war");
        Release b = upload(portal, "2.5.0", warB, "customer-portal-2.5.0.war");
        upload(billing, "1.0", warLegacy, "billing-1.0.war");
        releases.setArchived(b.getId(), true, "it");

        assertThat(versions(releases.search(null, "", false, 0, 20))).containsExactlyInAnyOrder("2.4.1", "1.0");
        assertThat(versions(releases.search(null, "", true, 0, 20))).hasSize(3);
        assertThat(versions(releases.search(portal.getId(), "", true, 0, 20))).containsExactlyInAnyOrder("2.4.1", "2.5.0");
        assertThat(versions(releases.search(null, "BILLING", false, 0, 20))).containsExactly("1.0");
        String shaPrefix = releases.find(b.getId()).getArtifact().getSha256().substring(0, 10);
        assertThat(versions(releases.search(null, shaPrefix, true, 0, 20))).containsExactly("2.5.0");
        assertThat(versions(releases.search(null, "notes for 2.4", false, 0, 20))).containsExactly("2.4.1");
        Page<Release> page = releases.search(null, "", true, 0, 2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);

        List<Object[]> stats = releaseRepository.statisticsByApplication();
        assertThat(stats).anySatisfy(row -> { assertThat(row[0]).isEqualTo(portal.getId()); assertThat(row[1]).isEqualTo(2L); assertThat(row[2]).isInstanceOf(Instant.class); });
    }

    @Test void metadataEditsUseOptimisticLockingAndNeverTouchTheFile() throws Exception {
        Release r = upload(portal, "2.4.1", warA, "portal.war");
        String sha = r.getArtifact().getSha256();
        Release edited = releases.updateMetadata(r.getId(), r.getVersion(), "2.4.1-final", "Adds SSO", "editor");
        assertThat(edited.getVersionLabel()).isEqualTo("2.4.1-final");
        assertThat(edited.getUpdatedBy()).isEqualTo("editor");
        assertThat(releases.find(r.getId()).getArtifact().getSha256()).isEqualTo(sha);
        assertThatThrownBy(() -> releases.updateMetadata(r.getId(), r.getVersion(), "2.4.1-x", null, "late"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test void deletingKeepsSharedFilesAndRemovesTheLastCopy() throws Exception {
        Release a = upload(portal, "2.4.1", warA, "portal.war");
        Release shared = upload(billing, "9.9", warA, "portal.war");
        Path file = storage.resolveStored(a.getArtifact().getStoragePath());

        releases.delete(shared.getId(), "it");
        assertThat(Files.exists(file)).isTrue();
        assertThat(artifacts.count()).isEqualTo(1);

        releases.delete(a.getId(), "it");
        assertThat(Files.exists(file)).isFalse();
        assertThat(artifacts.count()).isZero();
    }

    @Test void applicationsWithReleasesCannotBeDeleted() throws Exception {
        upload(portal, "1.0", warA, "portal.war");
        assertThatThrownBy(() -> applications.delete(portal.getId(), "it")).isInstanceOf(IllegalStateException.class);
        applications.delete(billing.getId(), "it");
    }

    @Test void databaseRefusesTheSameArtifactTwiceForOneApplication() throws Exception {
        Release r = upload(portal, "1.0", warA, "portal.war");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO release_artifacts (application_id, artifact_id, version_label, original_filename, uploaded_by, updated_by) VALUES (?, ?, 'other', 'x.war', 'it', 'it')",
                portal.getId(), r.getArtifact().getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void housekeepingReportsMissingFiles() throws Exception {
        Release r = upload(portal, "1.0", warA, "portal.war");
        assertThat(housekeeping.run().healthy()).isTrue();
        storage.deleteFile(storage.resolveStored(r.getArtifact().getStoragePath()));
        StorageHousekeeping.Report report = housekeeping.run();
        assertThat(report.missing()).containsExactly(r.getArtifact().getSha256());
        assertThat(releases.verify(r.getId(), "it").ok()).isFalse();
    }

    private static List<String> versions(Page<Release> page) {
        return page.getContent().stream().map(Release::getVersionLabel).toList();
    }
}
