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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Backups are selected and deleted on the Restore points page itself; only administrators get the selection. */
@WebMvcTest(BackupController.class)
@Import({SecurityConfig.class, DisplayTime.class, com.tomcatmanager.support.AllAccess.class})
class RestorePointsPageTest {
    @Autowired MockMvc mvc;
    @MockitoBean BackupService service;
    @MockitoBean DeploymentProperties properties;
    @MockitoBean StorageCleanupService cleanup;
    @MockitoBean com.tomcatmanager.infrastructure.TomcatInstanceRepository instanceRepository;

    private static BackupService.Entry entry(long backupId, long instanceId, String instance, boolean rollbackPoint) {
        DeploymentBackup backup = mock(DeploymentBackup.class);
        when(backup.getId()).thenReturn(backupId);
        when(backup.getSource()).thenReturn(DeploymentBackup.Source.REMOTE_WAR);
        when(backup.getSizeBytes()).thenReturn(2048L);
        when(backup.getCreatedAt()).thenReturn(Instant.parse("2026-11-01T10:00:00Z"));
        DeploymentRun run = mock(DeploymentRun.class);
        when(run.getId()).thenReturn(40 + backupId);
        DeploymentTarget target = mock(DeploymentTarget.class);
        when(target.getInstanceId()).thenReturn(instanceId);
        when(target.getInstanceName()).thenReturn(instance);
        when(target.getContextPath()).thenReturn("/sol");
        when(target.getTargetFilename()).thenReturn("sol.war");
        when(target.getRun()).thenReturn(run);
        return new BackupService.Entry(backup, target, rollbackPoint, true);
    }

    @BeforeEach void backups() {
        when(properties.backupDir()).thenReturn(Path.of("."));
        // Built before stubbing service.list(): creating mocks inside thenReturn() would interrupt that stubbing.
        List<BackupService.Entry> entries = List.of(entry(1, 10, "tomcat-a", false), entry(2, 10, "tomcat-a", true), entry(3, 11, "tomcat-b", false));
        when(service.list()).thenReturn(entries);
    }

    @Test void administratorsSelectBackupsOnThePageButNotTheCurrentRollbackPoint() throws Exception {
        String page = mvc.perform(get("/backups").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(page)
                .contains("Delete selected backups", "data-check-all=\"backupIds\"", "All instances")
                .doesNotContain("href=\"/backups/cleanup\"");
        org.assertj.core.api.Assertions.assertThat(page.split("name=\"backupIds\"", -1)).hasSize(4);
        // Only the rollback point's checkbox is disabled.
        org.assertj.core.api.Assertions.assertThat(page.split("disabled=\"disabled\"", -1)).hasSize(2);
    }

    @Test void operatorsSeeRestorePointsWithoutBulkDelete() throws Exception {
        mvc.perform(get("/backups").with(user("operator").roles("DEPLOYMENT_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<strong>tomcat-a</strong>")))
                .andExpect(content().string(not(containsString("name=\"backupIds\""))))
                .andExpect(content().string(not(containsString("Delete selected backups"))));
    }

    @Test void theInstanceFilterNarrowsTheList() throws Exception {
        mvc.perform(get("/backups").param("instance", "11").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<strong>tomcat-b</strong>")))
                .andExpect(content().string(not(containsString("<strong>tomcat-a</strong>"))))
                .andExpect(content().string(containsString("Show all instances")));
    }

    @Test void theOldCleanupAddressOpensRestorePoints() throws Exception {
        mvc.perform(get("/backups/cleanup").param("instance", "11").with(user("admin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/backups?instance=11"));
    }
}
