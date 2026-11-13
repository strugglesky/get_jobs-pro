package com.getjobs.application.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class LoginCodeSender {
    private final LoginSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public LoginCodeSender(LoginSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public boolean configured() {
        return !settings.webhook().isBlank();
    }

    public void send(String code) {
        if (!configured()) {
            throw new LoginException(503, "请先在服务器 .env 中配置 LOGIN_WECOM_WEBHOOK，并重启服务");
        }
        try {
            String content = "【Get Jobs 登录验证】\n验证码：" + code
                    + "\n有效期 5 分钟，仅可使用一次。请勿向他人提供。\n如非本人操作，请忽略本消息。";
            var request = HttpRequest.newBuilder(URI.create(settings.webhook()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("msgtype", "text", "text", Map.of("content", content)))))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var body = mapper.readTree(response.body());
            if (response.statusCode() != 200 || body == null || !body.path("errcode").isIntegralNumber()
                    || body.path("errcode").intValue() != 0) {
                throw new LoginException(502, "验证码发送失败，请检查企业微信机器人配置后重试");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LoginException(502, "验证码发送中断，请稍后重试");
        } catch (IOException | IllegalArgumentException exception) {
            throw new LoginException(502, "验证码发送失败，请检查企业微信机器人配置后重试");
        }
    }
}
