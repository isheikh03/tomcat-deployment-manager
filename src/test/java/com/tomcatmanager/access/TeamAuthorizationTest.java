package com.tomcatmanager.access;

import com.tomcatmanager.config.SecurityConfig;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only super users create, rename and delete teams; administrators read them; others are refused. */
@WebMvcTest(TeamController.class)
@Import({SecurityConfig.class, com.tomcatmanager.support.AllAccess.class})
class TeamAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccessAdminService service;
    @MockitoBean ManagedHostRepository hosts;

    @Test void operatorsAndViewersCannotOpenTeams() throws Exception {
        mvc.perform(get("/admin/teams").with(user("op").roles("DEPLOYMENT_OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/teams").with(user("vic").roles("VIEWER"))).andExpect(status().isForbidden());
    }

    @Test void administratorsReadButCannotChangeTeams() throws Exception {
        mvc.perform(get("/admin/teams").with(user("ann").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/admin/teams/new").with(user("ann").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/teams/save").with(csrf()).with(user("ann").roles("ADMIN")).param("name", "Green")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/teams/1/delete").with(csrf()).with(user("ann").roles("ADMIN"))).andExpect(status().isForbidden());
        verify(service, never()).saveTeam(any(), any(), any(), any());
        verify(service, never()).deleteTeam(anyLong());
    }

    @Test void superUsersCreateTeams() throws Exception {
        mvc.perform(get("/admin/teams/new").with(user("root").roles("SUPER_ADMIN", "ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Create team")));
        org.mockito.Mockito.when(service.saveTeam(any(), any(), any(), any())).thenAnswer(i -> {
            Team t = new Team(i.getArgument(1), null, "root");
            org.springframework.test.util.ReflectionTestUtils.setField(t, "id", 3L);
            return t;
        });
        mvc.perform(post("/admin/teams/save").with(csrf()).with(user("root").roles("SUPER_ADMIN", "ADMIN")).param("name", "Green"))
                .andExpect(status().is3xxRedirection());
        verify(service).saveTeam(null, "Green", null, "root");
    }

    @Test void anInvalidNameShowsTheFormAgain() throws Exception {
        org.mockito.Mockito.when(service.saveTeam(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("Another team already has this name."));
        mvc.perform(post("/admin/teams/save").with(csrf()).with(user("root").roles("SUPER_ADMIN", "ADMIN")).param("name", "Blue"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Another team already has this name.")))
                .andExpect(content().string(not(containsString("Exception"))));
    }
}
