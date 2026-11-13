package com.getjobs.application.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginServiceTest {
    private LoginCodeSender sender;
    private LoginService login;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);

    @BeforeEach
    void setup() {
        sender = mock(LoginCodeSender.class);
        when(sender.configured()).thenReturn(true);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(invocation -> now.get());
        login = new LoginService(sender, clock);
    }

    private String send(MockHttpSession session) {
        login.sendCode(session);
        var code = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).send(code.capture());
        return code.getValue();
    }

    @Test
    void acceptsSixDigitCodeOnlyOnceAndDoesNotAuthenticateOnSend() {
        var session = new MockHttpSession();
        String code = send(session);
        assertTrue(code.matches("[0-9]{6}"));
        assertFalse(login.authenticated(session));
        assertDoesNotThrow(() -> login.verify(session, code));
        assertEquals(400, assertThrows(LoginException.class, () -> login.verify(session, code)).status());
    }

    @Test
    void bindsCodeToRequestingBrowser() {
        var owner = new MockHttpSession();
        String code = send(owner);
        assertThrows(LoginException.class, () -> login.verify(new MockHttpSession(), code));
        assertDoesNotThrow(() -> login.verify(owner, code));
    }

    @Test
    void expiresCodeAfterFiveMinutes() {
        var session = new MockHttpSession();
        String code = send(session);
        now.addAndGet(Duration.ofMinutes(5).toMillis());
        assertThrows(LoginException.class, () -> login.verify(session, code));
    }

    @Test
    void invalidatesCodeAfterFiveWrongAttempts() {
        var session = new MockHttpSession();
        String code = send(session);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(LoginException.class, () -> login.verify(session, "invalid"));
        }
        assertThrows(LoginException.class, () -> login.verify(session, code));
    }

    @Test
    void globallyLimitsAttemptsAcrossAnonymousSessions() {
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThrows(LoginException.class, () -> login.verify(new MockHttpSession(), "123456"));
        }
        assertEquals(429, assertThrows(LoginException.class, () -> login.verify(null, "123456")).status());
        now.addAndGet(Duration.ofMinutes(10).toMillis());
        assertEquals(400, assertThrows(LoginException.class, () -> login.verify(null, "123456")).status());
    }

    @Test
    void globallyLimitsSendingAndAllowsResendAfterSixtySeconds() {
        send(new MockHttpSession());
        var error = assertThrows(LoginException.class, () -> login.sendCode(new MockHttpSession()));
        assertEquals(429, error.status());
        assertEquals(60, error.retryAfter());
        now.addAndGet(60_000);
        assertDoesNotThrow(() -> login.sendCode(new MockHttpSession()));
    }

    @Test
    void limitsHourlySendsAcrossBrowsers() {
        for (int attempt = 0; attempt < 20; attempt++) {
            login.sendCode(new MockHttpSession());
            now.addAndGet(60_000);
        }
        assertEquals(429, assertThrows(LoginException.class, () -> login.sendCode(new MockHttpSession())).status());
        now.addAndGet(Duration.ofHours(1).toMillis());
        assertDoesNotThrow(() -> login.sendCode(new MockHttpSession()));
    }

    @Test
    void failsClosedWithoutWebhook() {
        when(sender.configured()).thenReturn(false);
        assertEquals(503, assertThrows(LoginException.class, () -> login.sendCode(new MockHttpSession())).status());
        verify(sender, never()).send(anyString());
    }

    @Test
    void failedDeliveryDoesNotCreateValidCodeAndStillLimitsRetries() {
        doThrow(new LoginException(502, "delivery failed")).when(sender).send(anyString());
        var session = new MockHttpSession();
        assertEquals(502, assertThrows(LoginException.class, () -> login.sendCode(session)).status());
        assertEquals(400, assertThrows(LoginException.class, () -> login.verify(session, "123456")).status());
        assertEquals(429, assertThrows(LoginException.class, () -> login.sendCode(session)).status());
    }

    @Test
    void sessionHasAnAbsoluteEightHourLifetime() {
        var session = new MockHttpSession();
        session.setAttribute(LoginService.AUTHENTICATED_AT, now.get());
        assertTrue(login.authenticated(session));
        now.addAndGet(Duration.ofHours(8).toMillis());
        assertFalse(login.authenticated(session));
        assertFalse(login.authenticated(null));
    }
}
