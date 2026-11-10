package com.getjobs.worker.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.DeliveryReportWriter;
import com.getjobs.worker.dto.DeliveryRunReport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotReportTest {
    @TempDir Path directory;

    @Test
    void summaryPrecedesMultipartUploadAndFileMessageAndReportOvertakesIndividualBacklog() throws Exception {
        List<String> kinds = new CopyOnWriteArrayList<>();
        List<String> multipart = new CopyOnWriteArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/webhook/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean upload = exchange.getRequestURI().getPath().endsWith("/upload_media");
            if (upload) {
                kinds.add("upload");
                multipart.add(body);
                multipart.add(exchange.getRequestURI().getRawQuery());
            } else kinds.add(mapper.readTree(body).path("msgtype").asText());
            byte[] response = (upload ? "{\"errcode\":0,\"media_id\":\"media-test-001\"}" : "{\"errcode\":0}")
                    .getBytes(StandardCharsets.UTF_8);
            if (!upload && "file".equals(mapper.readTree(body).path("msgtype").asText())) {
                multipart.add(mapper.readTree(body).path("file").path("media_id").asText());
            }
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        ConfigService config = mock(ConfigService.class);
        when(config.getAllConfigsAsMap()).thenReturn(Map.of("BOT_IS_SEND", "1", "HOOK_URL",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/cgi-bin/webhook/send?key=local-key%2Bvalue"));
        Bot bot = new Bot(config, mapper);
        try {
            Path workbook = directory.resolve("delivery-test.xlsx");
            try (var out = Files.newOutputStream(workbook)) {
                new DeliveryReportWriter().write(new DeliveryRunReport("id", "Boss直聘", ZonedDateTime.now(),
                        ZonedDateTime.now(), "用户结束投递", List.of()), out);
            }
            bot.sendMessageInstance("排队的逐岗位通知");
            bot.notifyReport("【本次投递汇总】", workbook);
            for (int i = 0; i < 4; i++) bot.dispatchNext();

            assertEquals(List.of("text", "upload", "file", "text"), kinds);
            assertTrue(multipart.get(0).contains("name=\"media\"; filename=\"delivery-test.xlsx\""));
            assertTrue(multipart.get(0).contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            assertTrue(multipart.get(0).contains("xl/workbook.xml"));
            assertEquals("key=local-key%2Bvalue&type=file", multipart.get(1));
            assertEquals("media-test-001", multipart.get(2));
        } finally {
            bot.close();
            server.stop(0);
        }
    }
}
