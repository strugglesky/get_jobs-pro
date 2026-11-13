package com.getjobs.application.auth;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class LoginSettings {
    private final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    public String webhook() {
        return value("LOGIN_WECOM_WEBHOOK", "");
    }

    public boolean secureCookie() {
        return Boolean.parseBoolean(value("LOGIN_COOKIE_SECURE", "false"));
    }

    public List<String> allowedOrigins() {
        return Arrays.stream(value("LOGIN_ALLOWED_ORIGINS", "http://localhost:6866,http://127.0.0.1:6866").split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList();
    }

    private String value(String key, String fallback) {
        String value = dotenv.get(key);
        return value == null ? fallback : value.trim();
    }
}
