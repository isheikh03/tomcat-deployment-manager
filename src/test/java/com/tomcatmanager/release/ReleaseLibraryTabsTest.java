package com.tomcatmanager.release;

import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.application.ManagedApplication;
import com.tomcatmanager.common.DisplayTime;
import com.tomcatmanager.config.SecurityConfig;
import com.tomcatmanager.deployment.DeploymentRunRepository;
import com.tomcatmanager.deployment.StorageCleanupService;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Release storage is cleaned up in a tab of the Release library itself; only administrators get that tab. */
@WebMvcTest(ReleaseController.class)
@Import({SecurityConfig.class, DisplayTime.class, com.tomcatmanager.support.AllAccess.class})
class ReleaseLibraryTabsTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReleaseService service;
    @MockitoBean ReleaseRepository releases;
    @MockitoBean ApplicationRepository applications;
    @MockitoBean TomcatInstanceRepository instances;
    @MockitoBean ArtifactStorage storage;
    @MockitoBean StorageHousekeeping housekeeping;
    @MockitoBean StoredArtifactRepository artifacts;
    @MockitoBean DeploymentRunRepository deploymentRuns;
    @MockitoBean StorageCleanupService cleanup;

    private static StorageCleanupService.ReleaseFile storedFile() { return file(7L, StorageCleanupService.FileState.STORED, List.of()); }

    private static StorageCleanupService.ReleaseFile file(long id, StorageCleanupService.FileState state, List<String> reasons) {
        StoredArtifact artifact = mock(StoredArtifact.class);
        when(artifact.getId()).thenReturn(id);
        when(artifact.getSha256()).thenReturn("a".repeat(64));
        when(artifact.getSizeBytes()).thenReturn(4096L);
        ManagedApplication app = mock(ManagedApplication.class);
        when(app.getName()).thenReturn("SOL");
        Release release = mock(Release.class);
        when(release.getId()).thenReturn(3L);
        when(release.getApplication()).thenReturn(app);
        when(release.getVersionLabel()).thenReturn("1.0");
        when(release.getOriginalFilename()).thenReturn("sol.war");
        when(release.getUploadedAt()).thenReturn(Instant.parse("2026-11-01T10:00:00Z"));
        return new StorageCleanupService.ReleaseFile(artifact, List.of(release), state, reasons, null, null);
    }

    @BeforeEach void library() {
        when(service.search(any(), anyString(), anyBoolean(), anyInt(), anyInt(), any())).thenReturn(Page.empty());
        // Built before stubbing: creating mocks inside thenReturn() would interrupt that stubbing.
        List<StorageCleanupService.ReleaseFile> files = List.of(storedFile());
        when(cleanup.releaseFiles(any(), anyString())).thenReturn(files);
    }

    @Test void administratorsGetAStorageCleanupTabOnTheLibraryPage() throws Exception {
        String page = mvc.perform(get("/releases").with(user("admin").roles("SUPER_ADMIN", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"tab-releases\"", "id=\"tab-storage\"", "Storage cleanup", "name=\"artifactIds\"", "Remove selected files")
                .doesNotContain("href=\"/releases/cleanup\"");
        // Releases is the open tab by default.
        assertThat(page).containsPattern("class=\"tab-pane fade\\s+show active\" id=\"tab-releases\"|id=\"tab-releases\"[^>]*class=\"tab-pane fade\\s+show active\"");
    }

    @Test void theStorageTabCanBeOpenedDirectly() throws Exception {
        String page = mvc.perform(get("/releases").param("tab", "storage").with(user("admin").roles("SUPER_ADMIN", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).containsPattern("id=\"tab-storage\"[^>]*class=\"tab-pane fade\\s+show active\"|class=\"tab-pane fade\\s+show active\"[^>]*id=\"tab-storage\"");
        assertThat(page).contains("name=\"tab\" value=\"storage\"");
    }

    @Test void viewersAndOperatorsSeeOnlyTheReleases() throws Exception {
        for (String role : new String[]{"VIEWER", "DEPLOYMENT_OPERATOR"}) {
            String page = mvc.perform(get("/releases").param("tab", "storage").with(user("u").roles(role)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(page).contains("id=\"tab-releases\"").doesNotContain("tab-storage", "name=\"artifactIds\"", "Remove selected files");
        }
    }

    @Test void filesInUseCanBeSelectedButNeedConfirmation_filesBeingDeployedAreLocked() throws Exception {
        List<StorageCleanupService.ReleaseFile> files = List.of(storedFile(),
                file(8L, StorageCleanupService.FileState.RUNNING, List.of("Currently running on tomcat-a at /sol")),
                file(9L, StorageCleanupService.FileState.DEPLOYING, List.of("Being deployed by deployment #4")));
        when(cleanup.releaseFiles(any(), anyString())).thenReturn(files);
        String page = mvc.perform(get("/releases").param("tab", "storage").with(user("admin").roles("SUPER_ADMIN", "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("In use · needs confirmation", "Currently running on tomcat-a", "Being deployed · locked",
                "data-protected-confirm=\"artifactIds\"", "name=\"confirmProtected\"");
        assertThat(page).containsPattern("value=\"8\"[^>]*data-protected=\"true\"");
        assertThat(page).containsPattern("value=\"9\"[^>]*disabled|disabled[^>]*value=\"9\"");
        assertThat(page).doesNotContainPattern("value=\"7\"[^>]*data-protected");
    }

    @Test void theOldCleanupAddressOpensTheTab() throws Exception {
        mvc.perform(get("/releases/cleanup").param("app", "2").param("q", "1.0").with(user("admin").roles("SUPER_ADMIN", "ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/releases?app=2&q=1.0&tab=storage"));
    }
}
