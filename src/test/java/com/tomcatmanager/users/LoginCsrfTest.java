package com.tomcatmanager.users;

import com.tomcatmanager.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A sign-in rejected by the CSRF check explains itself, with or without a context path; other requests stay 403. */
@WebMvcTest(value = AccountController.class, properties = "server.servlet.session.cookie.secure=true")
@Import(SecurityConfig.class)
class LoginCsrfTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserAccountService accounts;
    @MockitoBean UserBootstrap bootstrap;

    @Test void rejectedSignInReturnsToLoginPage() throws Exception {
        mvc.perform(post("/login").param("username", "admin").param("password", "admin"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?retry"));
    }

    @Test void rejectedSignInKeepsContextPath() throws Exception {
        mvc.perform(post("/tdm/login").contextPath("/tdm").param("username", "admin").param("password", "admin"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/tdm/login?retry"));
    }

    @Test void otherRequestsWithoutTokenAreStillForbidden() throws Exception {
        mvc.perform(post("/setup").param("token", "x")).andExpect(status().isForbidden());
    }

    @Test void loginPageCarriesHttpsWarningWhenCookieRequiresHttps() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("insecure-cookie-warning")));
    }
}
