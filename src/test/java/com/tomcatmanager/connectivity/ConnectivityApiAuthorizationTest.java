package com.tomcatmanager.connectivity;

import com.tomcatmanager.config.SecurityConfig;
import com.tomcatmanager.infrastructure.ManagedHost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Backend authorization and CSRF rules for the connectivity API, independent of the UI. */
@WebMvcTest(ConnectivityApiController.class)
@Import({SecurityConfig.class, com.tomcatmanager.support.AllAccess.class})
class ConnectivityApiAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean ConnectionTestService service;
    @MockitoBean com.tomcatmanager.audit.AuditService audit;
    @MockitoBean com.tomcatmanager.infrastructure.ManagedHostRepository hosts;
    @MockitoBean com.tomcatmanager.infrastructure.TomcatInstanceRepository instances;

    /** Every host id exists (the controller looks the host up before testing it). */
    @org.junit.jupiter.api.BeforeEach void hostsExist() {
        when(hosts.findById(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            var h = new com.tomcatmanager.infrastructure.ManagedHost();
            h.setId(call.getArgument(0));
            return java.util.Optional.of(h);
        });
    }

    private static ConnectionTestResult sampleResult() {
        ProbeReport report = new ProbeReport();
        report.success(CheckType.NETWORK_TARGET, "ok");
        return new ConnectionTestResult(UUID.randomUUID(), 1L, null, ConnectionTestResult.TestType.HOST_SSH, "admin", Instant.now(), Instant.now(), report);
    }

    @Test void anonymousGets401NotARedirect() throws Exception {
        mvc.perform(post("/api/connectivity-tests/hosts/1").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/connectivity-tests/hosts/1")).andExpect(status().isUnauthorized());
    }

    @Test void viewerCannotRunTests() throws Exception {
        mvc.perform(post("/api/connectivity-tests/hosts/1").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/connectivity-tests/instances/1").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        verify(service, never()).testHost(anyLong(), anyString());
    }

    @Test void viewerCanReadHistory() throws Exception {
        when(service.historyForHost(eq(1L), anyInt(), anyInt())).thenReturn(Page.empty());
        mvc.perform(get("/api/connectivity-tests/hosts/1").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void operatorCanRunTests() throws Exception {
        when(service.testHost(1L, "operator")).thenReturn(sampleResult());
        mvc.perform(post("/api/connectivity-tests/hosts/1").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("SUCCESS")).andExpect(jsonPath("$.checks[0].type").value("NETWORK_TARGET"));
    }

    @Test void postWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/connectivity-tests/hosts/1").with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        verify(service, never()).testHost(anyLong(), anyString());
    }

    @Test void onlyAdminCanTrustHostKeys() throws Exception {
        String body = "{\"fingerprint\":\"SHA256:" + "A".repeat(43) + "\"}";
        mvc.perform(post("/api/hosts/1/host-key/trust").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());

        ManagedHost host = new ManagedHost();
        host.setSshHostKeyFingerprint("SHA256:" + "A".repeat(43));
        when(service.trustInitialHostKey(eq(1L), anyString(), eq("admin"))).thenReturn(host);
        mvc.perform(post("/api/hosts/1/host-key/trust").with(csrf()).with(user("admin").roles("ADMIN", "DEPLOYMENT_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test void onlyAdminCanTestUnsavedSettings() throws Exception {
        String body = "{\"hostname\":\"10.0.0.5\",\"port\":22,\"username\":\"deploy\",\"authMethod\":\"PASSWORD\",\"password\":\"x\"}";
        mvc.perform(post("/api/connectivity-tests/drafts/host").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verify(service, never()).testDraftHost(org.mockito.ArgumentMatchers.any(), anyString());

        ProbeReport report = new ProbeReport();
        report.success(CheckType.SSH_CONNECTION, "ok");
        when(service.testDraftHost(org.mockito.ArgumentMatchers.any(), eq("admin")))
                .thenReturn(ConnectionTestResultDto.draft(UUID.randomUUID(), null, null, ConnectionTestResult.TestType.HOST_SSH, "admin", Instant.now(), Instant.now(), report));
        mvc.perform(post("/api/connectivity-tests/drafts/host").with(csrf()).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.outcome").value("SUCCESS"));

        mvc.perform(post("/api/connectivity-tests/drafts/host").with(csrf()).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"hostname\":\"\",\"port\":0}")).andExpect(status().isBadRequest());
    }

    @Test void errorsUseConsistentJson() throws Exception {
        when(service.testHost(1L, "operator")).thenThrow(new ConnectivityBusyException("Too many connection tests are running."));
        mvc.perform(post("/api/connectivity-tests/hosts/1").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR")))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.message").value("Too many connection tests are running."));

        when(service.trustInitialHostKey(eq(2L), anyString(), anyString())).thenThrow(new IllegalStateException("A host key is already pinned for this host."));
        mvc.perform(post("/api/hosts/2/host-key/trust").with(csrf()).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"x\"}")).andExpect(status().isConflict());

        mvc.perform(post("/api/hosts/2/host-key/trust").with(csrf()).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }
}
