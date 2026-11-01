package com.tomcatmanager.release;

import com.tomcatmanager.application.ApplicationController;
import com.tomcatmanager.application.ApplicationRepository;
import com.tomcatmanager.application.ApplicationService;
import com.tomcatmanager.config.SecurityConfig;
import com.tomcatmanager.infrastructure.TomcatInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Server-side permissions for the release library and application registry. */
@WebMvcTest({ReleaseApiController.class, ReleaseController.class, ApplicationController.class, com.tomcatmanager.application.ApplicationApiController.class})
@Import(SecurityConfig.class)
class ReleaseAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReleaseService releaseService;
    @MockitoBean ReleaseRepository releases;
    @MockitoBean ApplicationRepository applications;
    @MockitoBean ApplicationService applicationService;
    @MockitoBean TomcatInstanceRepository instances;
    @MockitoBean ArtifactStorage storage;
    @MockitoBean StorageHousekeeping housekeeping;
    @MockitoBean StoredArtifactRepository artifacts;
    @MockitoBean com.tomcatmanager.deployment.DeploymentRunRepository deploymentRuns;

    private static MockMultipartFile war() { return new MockMultipartFile("file", "app-1.0.war", "application/octet-stream", new byte[]{1, 2, 3}); }

    @Test void viewerCannotUploadOrChangeReleases() throws Exception {
        mvc.perform(multipart("/api/releases").file(war()).param("applicationId", "1").param("versionLabel", "1.0")
                .with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/releases/upload").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/releases/1/metadata").param("version", "0").param("versionLabel", "x").with(csrf()).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/releases/1/verify").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        verify(releaseService, never()).upload(any(), any(), anyString());
    }

    @Test void operatorCanUploadButNotArchiveOrDelete() throws Exception {
        when(releaseService.upload(any(), any(InputStream.class), anyString())).thenThrow(new DuplicateReleaseException("Already release 1.0.", 42));
        mvc.perform(multipart("/api/releases").file(war()).param("applicationId", "1").param("versionLabel", "1.0")
                        .with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.link").value("/releases/42"));
        mvc.perform(post("/releases/1/archive").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(post("/releases/1/delete").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
    }

    @Test void uploadErrorsAreClearJson() throws Exception {
        when(releaseService.upload(any(), any(InputStream.class), anyString())).thenThrow(new InvalidWarException("The archive has no WEB-INF/ directory."));
        mvc.perform(multipart("/api/releases").file(war()).param("applicationId", "1").param("versionLabel", "1.0")
                        .with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.message").value("The archive has no WEB-INF/ directory."));
        mvc.perform(multipart("/api/releases").file(war()).param("applicationId", "1")
                .with(csrf()).with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }

    @Test void uploadWithoutCsrfIsRejected() throws Exception {
        mvc.perform(multipart("/api/releases").file(war()).param("applicationId", "1").param("versionLabel", "1.0")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test void onlyAdminsManageApplications() throws Exception {
        mvc.perform(get("/applications/new").with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(post("/applications/save").param("name", "x").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(post("/applications/1/delete").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/applications").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
    }

    @Test void applicationApiIsReadableByAllButWritableOnlyByAdmins() throws Exception {
        mvc.perform(get("/api/applications").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(post("/api/applications").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"servletApi\":\"DETECT\"}")).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/applications/1").with(csrf()).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/applications").with(csrf()).with(user("admin").roles("ADMIN"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"servletApi\":\"DETECT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Enter an application name")));
    }

    @Test void storageCheckIsAdminOnly() throws Exception {
        mvc.perform(post("/releases/storage-check").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
    }
}
