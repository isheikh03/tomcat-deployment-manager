package com.tomcatmanager.deployment;

import com.tomcatmanager.common.DisplayTime;
import com.tomcatmanager.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Restore points page works like the Release library: a "Restore points" tab for everyone (removed backups stay
 * listed as "File removed") and, for administrators, a "Storage cleanup" tab to select and delete backup files.
 */
@WebMvcTest(BackupController.class)
@Import({SecurityConfig.class, DisplayTime.class, com.tomcatmanager.support.AllAccess.class})
class RestorePointsPageTest {
    @Autowired MockMvc mvc;
    @MockitoBean BackupService service;
    @MockitoBean DeploymentProperties properties;
    @MockitoBean StorageCleanupService cleanup;
    @MockitoBean com.tomcatmanager.infrastructure.TomcatInstanceRepository instanceRepository;

    private static BackupService.Entry entry(long backupId, long instanceId, String instance, boolean rollbackPoint, boolean removed) {
        DeploymentBackup backup = mock(DeploymentBackup.class);
        when(backup.getId()).thenReturn(backupId);
        when(backup.getSource()).thenReturn(DeploymentBackup.Source.REMOTE_WAR);
        when(backup.getSizeBytes()).thenReturn(2048L);
        when(backup.getCreatedAt()).thenReturn(Instant.parse("2026-11-01T10:00:00Z"));
        if (removed) {
            when(backup.isDeleted()).thenReturn(true);
            when(backup.getDeletedAt()).thenReturn(Instant.parse("2026-11-10T10:00:00Z"));
            when(backup.getDeletedBy()).thenReturn("root");
            when(backup.getDeleteReason()).thenReturn("disk almost full");
        }
        DeploymentRun run = mock(DeploymentRun.class);
        when(run.getId()).thenReturn(40 + backupId);
        DeploymentTarget target = mock(DeploymentTarget.class);
        when(target.getInstanceId()).thenReturn(instanceId);
        when(target.getInstanceName()).thenReturn(instance);
        when(target.getContextPath()).thenReturn("/sol");
        when(target.getTargetFilename()).thenReturn("sol.war");
        when(target.getRun()).thenReturn(run);
        return new BackupService.Entry(backup, target, rollbackPoint, !removed);
    }

    @BeforeEach void backups() {
        when(properties.backupDir()).thenReturn(Path.of("."));
        // Built before stubbing: creating mocks inside thenReturn() would interrupt that stubbing.
        List<BackupService.Entry> entries = List.of(entry(1, 10, "tomcat-a", false, false), entry(2, 10, "tomcat-a", true, false),
                entry(3, 11, "tomcat-b", false, false), entry(4, 11, "tomcat-b", false, true));
        when(service.listIncludingRemoved()).thenReturn(entries);
    }

    private String page(String role, String... params) throws Exception {
        var request = get("/backups").with(user("u").roles(role));
        for (int i = 0; i + 1 < params.length; i += 2) request = request.param(params[i], params[i + 1]);
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test void administratorsGetRestorePointsAndStorageCleanupTabs() throws Exception {
        String page = page("ADMIN");
        assertThat(page).contains("id=\"tab-points\"", "id=\"tab-storage\"", "Storage cleanup", "Clean up storage", "Remove selected files",
                "data-check-all=\"backupIds\"", "data-protected-confirm=\"backupIds\"", "name=\"confirmProtected\"")
                .doesNotContain("href=\"/backups/cleanup\"");
        // Restore points is the open tab by default.
        assertThat(page).containsPattern("class=\"tab-pane fade\\s+show active\" id=\"tab-points\"|id=\"tab-points\"[^>]*class=\"tab-pane fade\\s+show active\"");
        // Storage line: files still kept, and how many were removed to free space.
        assertThat(page).contains("Backup files: ", "in 3 files", "1 removed to free space");
    }

    @Test void removedBackupsStayListedAsFileRemovedButCannotBeSelectedAgain() throws Exception {
        String page = page("ADMIN");
        assertThat(page).contains("File removed", "by root", "Reason: disk almost full");
        // Three backups are still on disk and can be selected; the removed one (4) cannot.
        assertThat(page.split("name=\"backupIds\"", -1)).hasSize(4);
        assertThat(page).doesNotContain("name=\"backupIds\" value=\"4\"").doesNotContain("data-verify=\"4\"");
    }

    @Test void aRollbackPointCanBeSelectedButNeedsConfirmation() throws Exception {
        String page = page("ADMIN", "tab", "storage");
        assertThat(page).containsPattern("id=\"tab-storage\"[^>]*class=\"tab-pane fade\\s+show active\"|class=\"tab-pane fade\\s+show active\"[^>]*id=\"tab-storage\"");
        assertThat(page).contains("Rollback point · needs confirmation", "Can be removed")
                .containsPattern("value=\"2\"[^>]*data-protected=\"true\"").doesNotContain("data-no-check-all")
                .doesNotContainPattern("value=\"1\"[^>]*data-protected")
                .doesNotContain("disabled=\"disabled\"", "Being restored");
    }

    @Test void aBackupBeingRestoredIsLocked() throws Exception {
        when(service.beingRestored()).thenReturn(java.util.Set.of(1L));
        String page = page("ADMIN", "tab", "storage");
        assertThat(page).contains("Being restored · locked").containsPattern("value=\"1\"[^>]*disabled=\"disabled\"");
    }

    @Test void operatorsSeeRestorePointsWithoutTheCleanupTab() throws Exception {
        mvc.perform(get("/backups").param("tab", "storage").with(user("operator").roles("DEPLOYMENT_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<strong>tomcat-a</strong>")))
                .andExpect(content().string(containsString("File removed")))
                .andExpect(content().string(not(containsString("tab-storage"))))
                .andExpect(content().string(not(containsString("name=\"backupIds\""))))
                .andExpect(content().string(not(containsString("Remove selected files"))));
    }

    @Test void theInstanceFilterAndSearchNarrowBothTabs() throws Exception {
        String byInstance = page("ADMIN", "instance", "11");
        assertThat(byInstance).contains("<strong>tomcat-b</strong>", "Clear filters").doesNotContain("<strong>tomcat-a</strong>");
        String bySearch = page("ADMIN", "q", "#42");
        assertThat(bySearch).contains("Deployment #42").doesNotContain("Deployment #41", "Deployment #43");
    }

    @Test void theOldCleanupAddressOpensTheStorageTab() throws Exception {
        mvc.perform(get("/backups/cleanup").param("instance", "11").with(user("admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/backups?instance=11&tab=storage"));
    }
}
