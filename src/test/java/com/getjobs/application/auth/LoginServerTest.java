package com.getjobs.application.auth;

import com.getjobs.application.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = LoginServerTest.Application.class)
class LoginServerTest {
    @LocalServerPort
    private int port;
    @Autowired
    private LoginCodeSender sender;
    private final HttpClient client = HttpClient.newHttpClient();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({LoginController.class, LoginService.class, LoginFilter.class, CorsConfig.class})
    static class Application {
        @Bean
        LoginSettings settings() {
            var settings = mock(LoginSettings.class);
            when(settings.allowedOrigins()).thenReturn(List.of("http://localhost:6866"));
            when(settings.secureCookie()).thenReturn(true);
            return settings;
        }

        @Bean
        LoginCodeSender sender() {
            var sender = mock(LoginCodeSender.class);
            when(sender.configured()).thenReturn(true);
            return sender;
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    @Test
    void realServletEnforcesCorsSessionRotationAndCookieAttributes() throws Exception {
        var anonymous = client.send(request("/api/config").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
        var blocked = client.send(request("/api/auth/code").header("Origin", "https://attacker.example")
                .header("X-Requested-With", "GetJobs").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, blocked.statusCode());
        verify(sender, never()).send(anyString());

        var sent = client.send(request("/api/auth/code").header("Origin", "http://localhost:6866")
                .header("X-Requested-With", "GetJobs").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sent.statusCode());
        assertEquals("http://localhost:6866", sent.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        String pendingCookie = sent.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(pendingCookie.contains("GETJOBS_SESSION="));
        assertTrue(pendingCookie.contains("HttpOnly"));
        assertTrue(pendingCookie.contains("Secure"));
        assertTrue(pendingCookie.contains("SameSite=Strict"));
        var code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(code.capture());

        var loggedIn = client.send(request("/api/auth/login").header("Cookie", pendingCookie.split(";", 2)[0])
                .header("X-Requested-With", "GetJobs").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code.getValue() + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loggedIn.statusCode());
        String authenticatedCookie = loggedIn.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertNotEquals(pendingCookie.split(";", 2)[0], authenticatedCookie);
        var session = client.send(request("/api/auth/session").header("Cookie", authenticatedCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(session.body().contains("\"authenticated\":true"));
        var logout = client.send(request("/api/auth/logout").header("Cookie", authenticatedCookie)
                .header("X-Requested-With", "GetJobs").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, logout.statusCode());
        var expired = client.send(request("/api/auth/session").header("Cookie", authenticatedCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(expired.body().contains("\"authenticated\":false"));
    }
}
