package com.getjobs.application.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Component
@Order(-100)
public class LoginFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of("/api/auth/session", "/api/auth/code", "/api/auth/login", "/api/auth/logout");
    private final LoginService login;
    private final ObjectMapper mapper;

    public LoginFilter(LoginService login, ObjectMapper mapper) {
        this.login = login;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (!(path.equals("/api") || path.startsWith("/api/") || path.equals("/actuator") || path.startsWith("/actuator/"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!PUBLIC_ENDPOINTS.contains(path) && !login.authenticated(request.getSession(false))) {
            reject(response, 401, "登录已过期，请重新登录");
            return;
        }
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                && !"GetJobs".equals(request.getHeader("X-Requested-With"))) {
            reject(response, 403, "请求缺少安全校验头");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), Map.of("message", message));
    }
}
