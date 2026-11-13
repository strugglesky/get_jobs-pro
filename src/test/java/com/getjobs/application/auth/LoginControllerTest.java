package com.getjobs.application.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.config.CorsConfig;
import com.getjobs.application.controller.CookieController;
import com.getjobs.application.service.CookieService;
import com.getjobs.worker.manager.PlaywrightManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoginControllerTest {
    private LoginCodeSender sender;
    private CookieService cookies;
    private PlaywrightManager browser;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        sender = mock(LoginCodeSender.class);
        when(sender.configured()).thenReturn(true);
        cookies = mock(CookieService.class);
        browser = mock(PlaywrightManager.class);
        var login = new LoginService(sender);
        var settings = mock(LoginSettings.class);
        when(settings.allowedOrigins()).thenReturn(List.of("http://localhost:6866"));
        mvc = MockMvcBuilders.standaloneSetup(new LoginController(login), new CookieController(cookies, browser))
                .addFilters(new CorsConfig().corsFilter(settings).getFilter(), new LoginFilter(login, new ObjectMapper())).build();
    }

    private MockHttpServletRequestBuilder request(String method, String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path).servletPath(path);
    }

    private MockHttpSession authenticate() throws Exception {
        var response = mvc.perform(request("POST", "/api/auth/code").header("X-Requested-With", "GetJobs"))
                .andExpect(status().isOk()).andReturn();
        var pending = (MockHttpSession) response.getRequest().getSession(false);
        String originalId = pending.getId();
        var code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(code.capture());
        var result = mvc.perform(request("POST", "/api/auth/login").session(pending)
                        .header("X-Requested-With", "GetJobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code.getValue() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true)).andReturn();
        var authenticated = (MockHttpSession) result.getRequest().getSession(false);
        assertTrue(pending.isInvalid());
        assertNotEquals(originalId, authenticated.getId());
        return authenticated;
    }

    @Test
    void returnsSessionStateWithoutExposingSecrets() throws Exception {
        mvc.perform(request("GET", "/api/auth/session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false)).andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.code").doesNotExist()).andExpect(jsonPath("$.webhook").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/config", "/api/cookie", "/api/boss/login-status", "/api/51job/stream", "/api/jobs/login-status/stream", "/actuator/env", "/api/auth/session/extra"})
    void protectsAllPrivateEndpoints(String path) throws Exception {
        mvc.perform(request("GET", path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(cookies, browser);
    }

    @Test
    void rejectsMissingCsrfHeaderAndUntrustedOrigins() throws Exception {
        mvc.perform(request("POST", "/api/auth/code")).andExpect(status().isForbidden());
        mvc.perform(request("POST", "/api/auth/code").header("X-Requested-With", "GetJobs")
                .header("Origin", "https://attacker.example")).andExpect(status().isForbidden());
        verify(sender, never()).send(anyString());
    }

    @Test
    void allowsCredentialedCorsPreflightWithoutLogin() throws Exception {
        mvc.perform(request("OPTIONS", "/api/cookie/save").header("Origin", "http://localhost:6866")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-Requested-With"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:6866"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"boss", "liepin", "51job", "zhilian"})
    void authenticatedBrowserCanStillSavePlatformCookies(String platform) throws Exception {
        var session = authenticate();
        mvc.perform(request("POST", "/api/cookie/save").session(session).param("platform", platform)
                        .header("X-Requested-With", "GetJobs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(browser).saveCookiesToDb(platform, "manual save");
    }

    @Test
    void logoutOnlyInvalidatesBackendSessionNeverPlatformCookiesOrBrowser() throws Exception {
        var session = authenticate();
        mvc.perform(request("GET", "/api/auth/session").session(session))
                .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(request("POST", "/api/auth/logout").session(session).header("X-Requested-With", "GetJobs"))
                .andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        verifyNoInteractions(cookies, browser);
        mvc.perform(request("GET", "/api/cookie")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsControlledErrorWhenSendingFails() throws Exception {
        doThrow(new LoginException(502, "发送失败")).when(sender).send(anyString());
        mvc.perform(request("POST", "/api/auth/code").header("X-Requested-With", "GetJobs"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").doesNotExist());
    }
}
