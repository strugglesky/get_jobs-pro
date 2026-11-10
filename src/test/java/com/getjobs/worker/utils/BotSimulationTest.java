package com.getjobs.worker.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.DeliveryReportWriter;
import com.getjobs.worker.dto.DeliveryRunReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Optional real HTTP run against the committed Postman simulation, never a real webhook. */
class BotSimulationTest {
    @TempDir Path directory;

    @Test
    void sendsSummaryUploadsWorkbookAndSendsFileMessage() throws Exception {
        String base = System.getenv("WECOM_SIMULATION_URL");
        assumeTrue(base != null && (base.startsWith("http://127.0.0.1:") || base.startsWith("http://localhost:")));
        ConfigService config = mock(ConfigService.class);
        when(config.getAllConfigsAsMap()).thenReturn(Map.of("BOT_IS_SEND", "1", "HOOK_URL",
                base + "/cgi-bin/webhook/send?key=local-test"));
        Path workbook = directory.resolve("delivery-simulation.xlsx");
        var now = ZonedDateTime.now();
        var report = new DeliveryRunReport("simulation", "Boss直聘", now, now, "用户结束投递", List.of());
        try (var out = Files.newOutputStream(workbook)) {
            new DeliveryReportWriter().write(report, out);
        }
        Logger logger = (Logger) LoggerFactory.getLogger(Bot.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try (var bot = new CloseableBot(config)) {
            bot.instance.notifyReport(report.summary(), workbook);
            for (int i = 0; i < 4; i++) bot.instance.dispatchNext();
            assertTrue(events.list.stream().anyMatch(event -> event.getFormattedMessage().equals("企业微信投递报告附件发送成功")));
            assertFalse(events.list.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"success,2", "rate-limit,4", "invalid-key,2", "http-error,4", "malformed,2", "timeout,2"})
    void handlesWebhookResponsesWithoutInterruptingDelivery(String scenario, int settingsReads) {
        String base = System.getenv("WECOM_SIMULATION_URL");
        assumeTrue(base != null && (base.startsWith("http://127.0.0.1:") || base.startsWith("http://localhost:")));
        ConfigService config = mock(ConfigService.class);
        when(config.getAllConfigsAsMap()).thenReturn(Map.of("BOT_IS_SEND", "1", "HOOK_URL",
                base + "/cgi-bin/webhook/send?key=local-test&case=" + scenario));
        Logger logger = (Logger) LoggerFactory.getLogger(Bot.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try (var bot = new CloseableBot(config)) {
            bot.instance.sendMessageInstance("平台：Boss直聘\n公司：测试\"公司\"\\\n岗位：Java 😀");
            // A production scheduler spaces these attempts by at least 3.1 seconds.
            for (int i = 0; i < 5; i++) assertDoesNotThrow(bot.instance::dispatchNext);
            verify(config, times(settingsReads)).getAllConfigsAsMap();
            boolean loggedSuccess = events.list.stream().anyMatch(event -> event.getFormattedMessage().contains("发送成功"));
            if ("success".equals(scenario)) assertTrue(loggedSuccess);
            else {
                assertFalse(loggedSuccess);
                assertTrue(events.list.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)));
            }
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    private static class CloseableBot implements AutoCloseable {
        final Bot instance;
        CloseableBot(ConfigService config) { instance = new Bot(config, new ObjectMapper()); }
        public void close() { instance.close(); }
    }
}
