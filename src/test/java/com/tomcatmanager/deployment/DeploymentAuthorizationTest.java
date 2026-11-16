package com.tomcatmanager.deployment;

import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.config.SecurityConfig;
import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.infrastructure.ServerGroupRepository;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import com.tomcatmanager.release.ReleaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may preview, start, cancel, reconcile and roll back deployments and manage backups: enforced on the server. */
@WebMvcTest({DeploymentApiController.class, DeploymentController.class, RollbackApiController.class, BackupController.class})
@Import({SecurityConfig.class, com.tomcatmanager.support.AllAccess.class})
class DeploymentAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean DeploymentService service;
    @MockitoBean DeploymentPreflight preflight;
    @MockitoBean RollbackPreflight rollbackPreflight;
    @MockitoBean RollbackService rollbacks;
    @MockitoBean BackupService backupService;
    @MockitoBean ReconciliationInspector reconciliation;
    @MockitoBean DeploymentRunRepository runs;
    @MockitoBean DeploymentTargetRepository targets;
    @MockitoBean DeploymentLogRepository logs;
    @MockitoBean DeploymentBackupRepository backups;
    @MockitoBean ApplicationRepository applications;
    @MockitoBean ReleaseRepository releases;
    @MockitoBean TomcatInstanceRepository instances;
    @MockitoBean ServerGroupRepository groups;
    @MockitoBean ConnectionTestService connections;
    @MockitoBean DeploymentProperties properties;
    @MockitoBean StorageCleanupService cleanup;

    /** Backup 1 exists, on instance 5 (the controller looks it up before verifying or deleting it). */
    @org.junit.jupiter.api.BeforeEach void backupExists() {
        DeploymentBackup backup = org.mockito.Mockito.mock(DeploymentBackup.class);
        when(backup.getId()).thenReturn(1L);
        DeploymentTarget target = org.mockito.Mockito.mock(DeploymentTarget.class);
        when(target.getInstanceId()).thenReturn(5L);
        java.util.List<BackupService.Entry> entries = java.util.List.of(new BackupService.Entry(backup, target, false, true));
        when(backupService.list()).thenReturn(entries);
    }

    @Test void onlyAdministratorsCleanUpBackups() throws Exception {
        mvc.perform(get("/backups/cleanup").with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(post("/backups/cleanup").param("backupIds", "1").param("reason", "free disk space")
                .with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        verify(cleanup, never()).deleteBackups(any(), anyString(), anyString());
        when(cleanup.deleteBackups(any(), anyString(), anyString()))
                .thenReturn(new StorageCleanupService.Result(1, 10, 0, java.util.List.of()));
        mvc.perform(post("/backups/cleanup").param("backupIds", "1").param("reason", "free disk space")
                .with(csrf()).with(user("admin").roles("ADMIN"))).andExpect(status().is3xxRedirection());
        verify(cleanup).deleteBackups(java.util.List.of(1L), "admin", "free disk space");
    }

    private static final String BODY = "{\"requestId\":\"6f1c2a7e-0d5b-4c41-9a2e-1b0c3d4e5f60\",\"deployment\":{\"releaseId\":1,\"environment\":\"DEVELOPMENT\",\"targets\":[]},\"acknowledgeWarnings\":true}";

    @Test void viewersCannotPreviewStartOrCancel() throws Exception {
        mvc.perform(post("/api/deployments").with(csrf()).with(user("viewer").roles("VIEWER")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/deployments/preview").with(csrf()).with(user("viewer").roles("VIEWER")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/deployments/1/cancel").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/deployments/new").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        verify(service, never()).submit(any(), anyString());
    }

    @Test void onlyAdministratorsReconcile() throws Exception {
        mvc.perform(post("/api/deployments/1/targets/2/reconcile").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"succeeded\":true,\"note\":\"checked the server\"}")).andExpect(status().isForbidden());
        verify(service, never()).reconcile(anyLong(), anyLong(), anyBoolean(), anyString(), anyString());
    }

    @Test void submitWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/deployments").with(user("operator").roles("DEPLOYMENT_OPERATOR")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test void viewersCannotRollBackVerifyOrDeleteBackupsOrInspect() throws Exception {
        String rollback = "{\"requestId\":\"6f1c2a7e-0d5b-4c41-9a2e-1b0c3d4e5f60\",\"targetId\":2,\"confirmed\":true}";
        mvc.perform(post("/api/rollbacks").with(csrf()).with(user("viewer").roles("VIEWER")).contentType(MediaType.APPLICATION_JSON).content(rollback))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/rollbacks/preview").with(csrf()).with(user("viewer").roles("VIEWER")).contentType(MediaType.APPLICATION_JSON).content("{\"targetId\":2}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/backups/1/verify").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/deployments/1/targets/2/inspect").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        verify(rollbacks, never()).submit(any(), anyString());
        verify(backupService, never()).verify(anyLong(), anyString());
        verify(reconciliation, never()).inspect(anyLong(), anyLong(), anyString());
    }

    @Test void operatorsRollBackButOnlyAdministratorsDeleteBackups() throws Exception {
        mvc.perform(post("/api/backups/1/delete").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"free disk space\"}")).andExpect(status().isForbidden());
        verify(backupService, never()).delete(anyLong(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        mvc.perform(post("/api/backups/1/delete").with(csrf()).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"free disk space\"}")).andExpect(status().isOk());
        verify(backupService).delete(1L, "admin", "free disk space", false);
        mvc.perform(post("/api/rollbacks/preview").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"targetId\":2}")).andExpect(status().isOk());
        verify(rollbackPreflight).evaluate(2L, "operator");
    }

    @Test void viewersCanReadHistory() throws Exception {
        org.mockito.Mockito.when(service.search(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/api/deployments").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
    }
}
