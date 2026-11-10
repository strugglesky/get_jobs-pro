package com.getjobs.worker.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.ConfigService;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotTest {
    @Test
    void enqueuesWeComTextAndSendsOnlyWhenEnabled() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger receivedMessage = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robot", exchange -> {
            requests.incrementAndGet();
            var payload = new ObjectMapper().readTree(exchange.getRequestBody());
            if ("text".equals(payload.path("msgtype").asText())
                    && payload.path("text").path("content").asText().equals("【投递通知】\n公司：测试\"公司\"\\😀\n投递状态：投递成功")) {
                receivedMessage.incrementAndGet();
            }
            byte[] response = "{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        ConfigService config = mock(ConfigService.class);
        Map<String, String> values = new HashMap<>();
        values.put("BOT_IS_SEND", "1");
        values.put("HOOK_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/robot");
        when(config.getAllConfigsAsMap()).thenAnswer(invocation -> Map.copyOf(values));

        var bot = new Bot(config, new ObjectMapper());
        try {
            bot.sendMessageInstance("【投递通知】\n公司：测试\"公司\"\\😀\n投递状态：投递成功");
            bot.dispatchNext();
            assertEquals(1, requests.get());
            assertEquals(1, receivedMessage.get());

            values.put("BOT_IS_SEND", "1");
            bot.sendMessageInstance("已排队但发送前关闭");
            values.put("BOT_IS_SEND", "0");
            bot.dispatchNext();
            assertEquals(1, requests.get());

            values.put("BOT_IS_SEND", "true");
            bot.sendMessageInstance("旧地址的排队消息");
            values.put("HOOK_URL", values.get("HOOK_URL") + "?new-destination");
            bot.dispatchNext();
            assertEquals(1, requests.get());

            bot.sendMessageInstance("【投递通知】\n公司：测试\"公司\"\\😀\n投递状态：投递成功");
            bot.dispatchNext();
            assertEquals(2, requests.get());

            assertEquals(2, receivedMessage.get());

            values.put("BOT_IS_SEND", "0");
            bot.sendMessageInstance("这条消息已关闭");
            bot.dispatchNext();
            assertEquals(2, requests.get());
        } finally {
            bot.close();
            server.stop(0);
        }
    }
}
