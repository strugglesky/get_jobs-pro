package com.getjobs.application.config;

import com.getjobs.application.auth.LoginSettings;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS跨域配置
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(LoginSettings settings) {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(settings.allowedOrigins());

        // 允许所有请求头
        config.addAllowedHeader("*");

        // 允许所有请求方法
        config.addAllowedMethod("*");

        // 允许携带凭证
        config.setAllowCredentials(true);

        // 预检请求的有效期，单位为秒
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        var registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(-200);
        return registration;
    }

    @Bean
    public ServletContextInitializer loginSessionCookie(LoginSettings settings) {
        return context -> {
            context.getSessionCookieConfig().setHttpOnly(true);
            context.getSessionCookieConfig().setSecure(settings.secureCookie());
        };
    }
}
