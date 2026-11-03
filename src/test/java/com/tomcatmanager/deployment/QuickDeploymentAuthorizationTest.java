package com.tomcatmanager.deployment;

import com.tomcatmanager.config.SecurityConfig;
import com.tomcatmanager.release.InvalidWarException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Quick Deployment follows the release upload policy; only administrators may create applications. */
@WebMvcTest(QuickDeploymentApiController.class)
@Import(SecurityConfig.class)
class QuickDeploymentAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean QuickDeploymentService service;

    private static MockMultipartFile war() { return new MockMultipartFile("file", "portal-1.0.war", "application/octet-stream", new byte[]{1, 2, 3}); }
    private static QuickDeploymentService.Prepared prepared() {
        return new QuickDeploymentService.Prepared(7L, 3L, "portal", false, "1.0", false, "ab".repeat(32), "3 B", 17, "JAKARTA", Map.of(1L, "portal.war"));
    }

    @Test void viewerCannotUpload() throws Exception {
        mvc.perform(multipart("/api/quick-deployments/release").file(war()).with(csrf()).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        verify(service, never()).prepare(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test void uploadWithoutCsrfIsRejected() throws Exception {
        mvc.perform(multipart("/api/quick-deployments/release").file(war()).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test void operatorUploadsButMayNotCreateApplications() throws Exception {
        when(service.prepare(any(), any(), anyString(), any(InputStream.class), anyString(), anyBoolean())).thenReturn(prepared());
        mvc.perform(multipart("/api/quick-deployments/release").file(war()).with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.releaseId").value(7)).andExpect(jsonPath("$.filenames.1").value("portal.war"));
        verify(service).prepare(isNull(), isNull(), eq("portal-1.0.war"), any(InputStream.class), eq("operator"), eq(false));
    }

    @Test void administratorMayCreateApplications() throws Exception {
        when(service.prepare(any(), any(), anyString(), any(InputStream.class), anyString(), anyBoolean())).thenReturn(prepared());
        mvc.perform(multipart("/api/quick-deployments/release").file(war()).param("applicationName", "Portal").param("versionLabel", "1.0")
                .with(csrf()).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        verify(service).prepare(eq("Portal"), eq("1.0"), eq("portal-1.0.war"), any(InputStream.class), eq("admin"), eq(true));
    }

    @Test void errorsAreClearJson() throws Exception {
        when(service.prepare(any(), any(), anyString(), any(InputStream.class), anyString(), anyBoolean()))
                .thenThrow(new InvalidWarException("The archive has no WEB-INF/ directory."));
        mvc.perform(multipart("/api/quick-deployments/release").file(war()).with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.message").value("The archive has no WEB-INF/ directory."));
        when(service.prepare(any(), any(), anyString(), any(InputStream.class), anyString(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("There is no application named portal."));
        mvc.perform(multipart("/api/quick-deployments/release").file(war()).with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("There is no application named portal."));
    }
}
