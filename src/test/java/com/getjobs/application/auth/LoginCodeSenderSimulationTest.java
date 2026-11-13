package com.getjobs.application.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class LoginCodeSenderSimulationTest {
    @ParameterizedTest
    @ValueSource(strings = {"success", "rate-limit", "invalid-key", "http-error", "malformed", "timeout"})
    void handlesRealHttpResponsesWithoutLeakingWebhook(String scenario) {
        String base = System.getenv("WECOM_SIMULATION_URL");
        assumeTrue(base != null && (base.startsWith("http://127.0.0.1:") || base.startsWith("http://localhost:")));
        var settings = mock(LoginSettings.class);
        when(settings.webhook()).thenReturn(base + "/cgi-bin/webhook/send?key=local-test&case=" + scenario);
        var sender = new LoginCodeSender(settings, new ObjectMapper());
        if (scenario.equals("success")) {
            assertDoesNotThrow(() -> sender.send("123456"));
        } else {
            var error = assertThrows(LoginException.class, () -> sender.send("123456"));
            assertEquals(502, error.status());
            assertFalse(error.getMessage().contains(base));
            assertNull(error.getCause());
        }
    }
}
