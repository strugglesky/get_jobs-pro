package com.getjobs.application.service;

import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.dto.DeliveryRunReport;
import com.getjobs.worker.utils.Bot;
import com.getjobs.worker.utils.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class DeliveryRunReportService {
    private final DeliveryReportWriter writer;
    private final Bot bot;
    private final Path directory;

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryRunReportService(DeliveryReportWriter writer, Bot bot) {
        this(writer, bot, Path.of("data", "delivery-reports"));
    }

    DeliveryRunReportService(DeliveryReportWriter writer, Bot bot, Path directory) {
        this.writer = writer;
        this.bot = bot;
        this.directory = directory;
    }

    public Session begin(Platform platform) { return new Session(platform.getPlatformName()); }

    /** 实际工作线程已退出后调用；重复停止/重复收尾不会重复生成和推送报告。 */
    public void finish(Session session, String reason) {
        DeliveryRunReport report = session.finish(reason);
        if (report == null) return;
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("delivery-" + report.startedAt().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + "-" + report.id() + ".xlsx");
            try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) { writer.write(report, output); }
            bot.notifyReport(report.summary(), file);
            log.info("本次投递报告已生成：{}（{} 条记录）", file, report.deliveries().size());
        } catch (Exception e) {
            log.error("生成或排队投递报告失败（{}），投递任务收尾继续执行", e.getClass().getSimpleName());
            bot.sendMessageInstance(report.summary() + "\nExcel 附件生成失败，请查看后端日志。");
        }
    }

    public static final class Session {
        private final String id = UUID.randomUUID().toString();
        private final String platform;
        private final ZonedDateTime startedAt = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        private final List<DeliveryNotification> deliveries = new ArrayList<>();
        private boolean finished;
        private String stopReason;

        private Session(String platform) { this.platform = platform; }

        public synchronized void record(DeliveryNotification delivery) {
            if (!finished) deliveries.add(delivery);
        }

        public synchronized void requestStop() { requestStop("用户结束投递"); }

        public synchronized void requestStop(String reason) {
            if (!finished && stopReason == null) stopReason = reason;
        }

        private synchronized DeliveryRunReport finish(String reason) {
            if (finished) return null;
            finished = true;
            return new DeliveryRunReport(id, platform, startedAt, ZonedDateTime.now(ZoneId.of("Asia/Shanghai")),
                    stopReason != null ? stopReason : reason, deliveries);
        }
    }
}
