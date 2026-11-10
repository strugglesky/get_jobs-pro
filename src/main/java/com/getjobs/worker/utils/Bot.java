package com.getjobs.worker.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.DeliveryNotification;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 企业微信群机器人：异步发送、限制为每分钟不超过 20 次请求。 */
@Slf4j
@Service
public class Bot {
    private static volatile Bot INSTANCE;
    private final ConfigService configService;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(1000);
    private final java.util.concurrent.LinkedBlockingDeque<Pending> priorityQueue = new java.util.concurrent.LinkedBlockingDeque<>(1000);
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "wecom-notifications");
        thread.setDaemon(true);
        return thread;
    });

    public Bot(ConfigService configService, ObjectMapper mapper) {
        this.configService = configService;
        this.mapper = mapper;
    }

    @PostConstruct
    public void start() {
        INSTANCE = this;
        sender.scheduleWithFixedDelay(this::dispatchNext, 0, 3100, TimeUnit.MILLISECONDS);
    }

    public void notifyDelivery(DeliveryNotification notification) {
        sendMessageInstance(notification.toText());
    }

    /** 汇总优先排队；先发送任务结束消息，再上传并推送 Excel 附件。 */
    public synchronized void notifyReport(String summary, Path workbook) {
        Settings settings = readSettings();
        if (!settings.enabled()) return;
        String safeSummary = DeliveryNotification.truncateUtf8(summary, 2048);
        if (priorityQueue.remainingCapacity() < 2) {
            log.warn("企业微信汇总队列已满，报告 {} 未排队", workbook.getFileName());
            return;
        }
        priorityQueue.offerLast(new Pending(Kind.TEXT, safeSummary, workbook, null, settings.url(), 0));
        priorityQueue.offerLast(new Pending(Kind.UPLOAD, null, workbook, null, settings.url(), 0));
    }

    public static void sendMessage(String message) {
        Bot current = INSTANCE;
        if (current != null) current.sendMessageInstance(message);
    }

    public static void sendMessageByTime(String message) {
        Bot current = INSTANCE;
        if (current != null) current.sendMessageByTimeInstance(message);
    }

    public void sendMessageByTimeInstance(String message) {
        sendMessageInstance(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + message);
    }

    public void sendMessageInstance(String message) {
        // 入队和出队都读取最新配置：保存开关立即生效，关闭期间的投递不会积压后补发。
        Settings settings = readSettings();
        if (!settings.enabled()) return;
        if (!queue.offer(new Pending(Kind.TEXT, DeliveryNotification.truncateUtf8(message, 2048), null, null, settings.url(), 0))) {
            log.warn("企业微信通知队列已满，本条消息未入队；投递任务继续执行");
        }
    }

    private Settings readSettings() {
        try {
            Map<String, String> config = configService.getAllConfigsAsMap();
            String flag = config.getOrDefault("BOT_IS_SEND", "0").trim();
            String url = config.getOrDefault("HOOK_URL", "").trim();
            return new Settings(("1".equals(flag) || "true".equalsIgnoreCase(flag)) && !url.isEmpty(), url);
        } catch (Exception e) {
            log.warn("读取企业微信配置失败，本条通知不发送（{}）", e.getClass().getSimpleName());
            return new Settings(false, "");
        }
    }

    // 包级入口供本地 HTTP 集成测试驱动，无需启动真实定时任务。
    void dispatchNext() {
        Pending pending = priorityQueue.pollFirst();
        if (pending == null) pending = queue.poll();
        if (pending == null) return;
        Settings settings = readSettings();
        if (!settings.enabled()) {
            queue.clear();
            priorityQueue.clear();
            return;
        }
        if (!settings.url().equals(pending.url())) {
            queue.removeIf(item -> !settings.url().equals(item.url()));
            priorityQueue.removeIf(item -> !settings.url().equals(item.url()));
            return;
        }
        try {
            HttpRequest request = pending.kind() == Kind.TEXT
                    ? textRequest(pending, settings.url())
                    : pending.kind() == Kind.UPLOAD ? uploadRequest(pending, settings.url())
                    : fileRequest(pending, settings.url());
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                retry(pending, "HTTP " + status);
                return;
            }
            if (status < 200 || status >= 300) {
                log.warn("企业微信通知未被接受，HTTP {}", status);
                return;
            }
            var body = mapper.readTree(response.body());
            var error = body == null ? null : body.get("errcode");
            if (error == null || !error.isIntegralNumber()) {
                log.warn("企业微信通知返回了无法识别的响应");
            } else if (error.asInt() == 0 && pending.kind() == Kind.UPLOAD) {
                String mediaId = body.path("media_id").asText("");
                if (mediaId.isBlank()) {
                    log.warn("企业微信未返回媒体 ID，Excel 附件未发送");
                } else if (!priorityQueue.offerFirst(new Pending(Kind.FILE, null, pending.file(), mediaId, pending.url(), 0))) {
                    log.warn("企业微信文件消息队列已满，报告附件未发送");
                }
            } else if (error.asInt() == 0) {
                log.info("企业微信{}发送成功", pending.kind() == Kind.FILE ? "投递报告附件" : "投递通知");
            } else if (error.asInt() == 45009 || error.asInt() == -1) {
                retry(pending, "errcode=" + error.asInt());
            } else {
                log.warn("企业微信通知被拒绝，errcode={}", error.asInt());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 超时可能发生在平台已接受消息之后，不自动重发以避免重复；不输出含 key 的 URL。
            log.warn("企业微信通知发送异常（{}），投递任务继续执行", e.getClass().getSimpleName());
        }
    }

    private HttpRequest textRequest(Pending pending, String hookUrl) throws Exception {
        String json = mapper.writeValueAsString(Map.of("msgtype", "text", "text", Map.of("content", pending.text())));
        return HttpRequest.newBuilder(URI.create(hookUrl)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private HttpRequest uploadRequest(Pending pending, String hookUrl) throws Exception {
        long size = Files.size(pending.file());
        if (size < 6 || size > 20L * 1024 * 1024) throw new java.io.IOException("Workbook exceeds upload size limit");
        byte[] file = Files.readAllBytes(pending.file());
        String boundary = "----GetJobs" + java.util.UUID.randomUUID().toString().replace("-", "");
        byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"media\"; filename=\""
                + pending.file().getFileName() + "\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] multipart = new byte[header.length + file.length + footer.length];
        System.arraycopy(header, 0, multipart, 0, header.length);
        System.arraycopy(file, 0, multipart, header.length, file.length);
        System.arraycopy(footer, 0, multipart, header.length + file.length, footer.length);
        URI hook = URI.create(hookUrl);
        String path = hook.getRawPath();
        if (!path.endsWith("/cgi-bin/webhook/send")) throw new IllegalArgumentException("Unsupported webhook path");
        String uploadUrl = hook.getScheme() + "://" + hook.getRawAuthority()
                + path.substring(0, path.length() - "send".length()) + "upload_media?"
                + (hook.getRawQuery() == null ? "" : hook.getRawQuery() + "&") + "type=file";
        return HttpRequest.newBuilder(URI.create(uploadUrl)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart)).build();
    }

    private HttpRequest fileRequest(Pending pending, String hookUrl) throws Exception {
        String json = mapper.writeValueAsString(Map.of("msgtype", "file", "file", Map.of("media_id", pending.mediaId())));
        return HttpRequest.newBuilder(URI.create(hookUrl)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private void retry(Pending pending, String reason) {
        if (pending.retries() >= 2) {
            log.warn("企业微信通知发送失败（{}），已停止重试", reason);
            return;
        }
        Pending retry = new Pending(pending.kind(), pending.text(), pending.file(), pending.mediaId(), pending.url(), pending.retries() + 1);
        boolean queued = pending.file() == null ? queue.offer(retry) : priorityQueue.offerFirst(retry);
        if (queued) {
            log.warn("企业微信通知暂未接受（{}），已排队重试", reason);
        } else {
            log.warn("企业微信通知发送失败（{}），已停止重试", reason);
        }
    }

    @PreDestroy
    public void close() {
        sender.shutdownNow();
        int remaining = queue.size();
        queue.clear();
        remaining += priorityQueue.size();
        priorityQueue.clear();
        if (INSTANCE == this) INSTANCE = null;
        if (remaining > 0) log.warn("应用停止，{} 条尚未发送的企业微信通知已清除", remaining);
    }

    private record Settings(boolean enabled, String url) {}
    private enum Kind { TEXT, UPLOAD, FILE }
    private record Pending(Kind kind, String text, Path file, String mediaId, String url, int retries) {}
}
