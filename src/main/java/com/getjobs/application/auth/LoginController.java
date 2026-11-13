package com.getjobs.application.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class LoginController {
    private final LoginService login;

    public LoginController(LoginService login) {
        this.login = login;
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        return Map.of("authenticated", login.authenticated(request.getSession(false)), "configured", login.configured());
    }

    @PostMapping("/code")
    public Map<String, Object> code(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        if (!login.authenticated(session)) session.setMaxInactiveInterval(5 * 60);
        login.sendCode(session);
        return Map.of("message", "验证码已发送至企业微信", "retryAfter", 60, "expiresIn", 300);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        login.verify(request.getSession(false), body.code());
        request.getSession(false).invalidate();
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(8 * 60 * 60);
        session.setAttribute(LoginService.AUTHENTICATED_AT, Instant.now().toEpochMilli());
        return Map.of("authenticated", true);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(LoginException.class)
    public ResponseEntity<Map<String, Object>> error(LoginException exception) {
        var response = ResponseEntity.status(exception.status());
        if (exception.retryAfter() > 0) response.header("Retry-After", Integer.toString(exception.retryAfter()));
        return response.body(Map.of("message", exception.getMessage(), "retryAfter", exception.retryAfter()));
    }

    public record LoginRequest(String code) {}
}
