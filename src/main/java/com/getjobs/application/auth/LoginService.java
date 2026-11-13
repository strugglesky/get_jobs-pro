package com.getjobs.application.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

@Service
public class LoginService {
    public static final String AUTHENTICATED_AT = "getjobs.authenticatedAt";
    private static final String CHALLENGE = "getjobs.loginChallenge";
    private static final long CODE_LIFETIME = Duration.ofMinutes(5).toMillis();
    private final LoginCodeSender sender;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Deque<Long> sends = new ArrayDeque<>();
    private final Deque<Long> failures = new ArrayDeque<>();
    private long lastSendAttempt = Long.MIN_VALUE;

    @Autowired
    public LoginService(LoginCodeSender sender) {
        this(sender, Clock.systemUTC());
    }

    LoginService(LoginCodeSender sender, Clock clock) {
        this.sender = sender;
        this.clock = clock;
    }

    public boolean configured() {
        return sender.configured();
    }

    public synchronized void sendCode(HttpSession session) {
        long now = clock.millis();
        prune(sends, now - Duration.ofHours(1).toMillis());
        if (lastSendAttempt != Long.MIN_VALUE && now - lastSendAttempt < 60_000) {
            throw new LoginException(429, "验证码发送过于频繁，请稍后重试", (int) ((60_000 - (now - lastSendAttempt) + 999) / 1000));
        }
        if (sends.size() >= 20) {
            throw new LoginException(429, "已达到每小时验证码发送上限", 3600);
        }
        if (!configured()) {
            throw new LoginException(503, "请先在服务器 .env 中配置 LOGIN_WECOM_WEBHOOK，并重启服务");
        }
        lastSendAttempt = now;
        sends.addLast(now);
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        sender.send(code);
        session.setAttribute(CHALLENGE, new Challenge(digest(code), clock.millis() + CODE_LIFETIME));
    }

    public synchronized void verify(HttpSession session, String code) {
        long now = clock.millis();
        prune(failures, now - Duration.ofMinutes(10).toMillis());
        if (failures.size() >= 20) {
            throw new LoginException(429, "验证失败次数过多，请 10 分钟后重试", 600);
        }
        Object value = session == null ? null : session.getAttribute(CHALLENGE);
        Challenge challenge = value instanceof Challenge pending ? pending : null;
        if (challenge == null || now >= challenge.expiresAt || challenge.attempts >= 5) {
            failures.addLast(now);
            if (session != null) session.removeAttribute(CHALLENGE);
            throw new LoginException(400, "验证码已失效或尚未发送，请重新获取");
        }
        challenge.attempts++;
        if (code == null || !code.matches("[0-9]{6}") || !MessageDigest.isEqual(challenge.digest, digest(code))) {
            failures.addLast(now);
            if (challenge.attempts >= 5) session.removeAttribute(CHALLENGE);
            throw new LoginException(400, challenge.attempts >= 5 ? "验证码错误次数过多，请重新获取" : "验证码错误，请检查后重试");
        }
        session.removeAttribute(CHALLENGE);
    }

    public boolean authenticated(HttpSession session) {
        return session != null && session.getAttribute(AUTHENTICATED_AT) instanceof Long authenticatedAt
                && clock.millis() - authenticatedAt < Duration.ofHours(8).toMillis();
    }

    private static byte[] digest(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void prune(Deque<Long> timestamps, long cutoff) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) timestamps.removeFirst();
    }

    private static final class Challenge {
        private final byte[] digest;
        private final long expiresAt;
        private int attempts;

        private Challenge(byte[] digest, long expiresAt) {
            this.digest = digest;
            this.expiresAt = expiresAt;
        }
    }
}
