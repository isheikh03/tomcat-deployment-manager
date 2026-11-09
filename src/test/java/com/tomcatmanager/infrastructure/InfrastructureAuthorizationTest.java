package com.tomcatmanager.infrastructure;

import com.tomcatmanager.config.SecurityConfig;
import com.tomcatmanager.connectivity.ConnectionTestService;
import com.tomcatmanager.security.SecretEncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Infrastructure configuration is administrator-only; operators and viewers are refused on the server. */
@WebMvcTest(InfrastructureController.class)
@Import(SecurityConfig.class)
class InfrastructureAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean ManagedHostRepository hosts;
    @MockitoBean TomcatInstanceRepository instances;
    @MockitoBean ServerGroupRepository groups;
    @MockitoBean HostService hostService;
    @MockitoBean InstanceService instanceService;
    @MockitoBean ConnectionTestService connectionTests;
    @MockitoBean SecretEncryptionService encryption;
    @MockitoBean com.tomcatmanager.deployment.DeploymentTargetRepository deploymentTargets;
    @MockitoBean ServerGroupService groupService;
    @MockitoBean InfrastructureAudit infrastructureAudit;

    @Test void nonAdminsCannotOpenConfigurationForms() throws Exception {
        mvc.perform(get("/infrastructure/hosts/new").with(user("operator").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/infrastructure/instances/5/edit").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
    }

    @Test void nonAdminsCannotChangeConfiguration() throws Exception {
        mvc.perform(post("/infrastructure/hosts/save").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR"))
                .param("name", "x").param("hostname", "x.example").param("sshUsername", "deploy")).andExpect(status().isForbidden());
        mvc.perform(post("/infrastructure/hosts/1/delete").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        verify(hostService, never()).saveWithChanges(any());
    }

    @Test void onlyAdminsManageGroupMembership() throws Exception {
        mvc.perform(post("/infrastructure/groups/1/members").with(csrf()).with(user("operator").roles("DEPLOYMENT_OPERATOR")).param("instanceId", "2"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/infrastructure/groups/1/members/2/remove").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        verify(groupService, never()).addMember(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        verify(groupService, never()).removeMember(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test void adminCanOpenHostFormAndSecretsAreNotRendered() throws Exception {
        mvc.perform(get("/infrastructure/hosts/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Server host key fingerprint")));
    }

    @Test void infrastructureStartsAtTheHostListAndViewersSeeNoAddButton() throws Exception {
        mvc.perform(get("/infrastructure").with(user("viewer").roles("VIEWER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/infrastructure/hosts"));
        mvc.perform(get("/infrastructure/hosts").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Add host"))));
    }

    @Test void hostListFiltersAndShowsRemovableChipsWithoutAdminActionsForViewers() throws Exception {
        org.mockito.Mockito.when(hosts.findAllByOrderByNameAsc()).thenReturn(java.util.List.of(
                InfrastructureListingTest.host(1, "Web Server", "10.0.0.5", true, true),
                InfrastructureListingTest.host(2, "database-live", "db.example.com", false, false)));
        mvc.perform(get("/infrastructure/hosts").param("q", "web").param("status", "enabled").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Web Server")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("database-live"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Showing 1–1 of 1 hosts")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"?status=enabled\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/infrastructure/hosts/1/edit"))));
    }

    @Test void instanceAndGroupListsOpenForViewersAndExplainEmptyResults() throws Exception {
        mvc.perform(get("/infrastructure/instances").param("environment", "NONE").param("page", "x").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No Tomcat instances match these filters")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Environment: None")));
        mvc.perform(get("/infrastructure/groups").with(user("operator").roles("DEPLOYMENT_OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No server groups yet")));
    }

    @Test void pagesTellScriptsTheContextPath() throws Exception {
        mvc.perform(get("/tdm/infrastructure/hosts").contextPath("/tdm").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<meta name=\"app-base\" content=\"/tdm/\">")));
        mvc.perform(get("/infrastructure/hosts").with(user("viewer").roles("VIEWER")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<meta name=\"app-base\" content=\"/\">")));
    }

    @Test void adminSeesListActions() throws Exception {
        org.mockito.Mockito.when(hosts.findAllByOrderByNameAsc()).thenReturn(java.util.List.of(InfrastructureListingTest.host(1, "Web Server", "10.0.0.5", true, true)));
        mvc.perform(get("/infrastructure/hosts").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/infrastructure/hosts/1/edit")));
    }
}
