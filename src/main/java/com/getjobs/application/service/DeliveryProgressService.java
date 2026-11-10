package com.getjobs.application.service;

import com.getjobs.worker.dto.JobProgressMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 保存本次投递快照，供实时推送、重连和状态查询使用。 */
@Service
public class DeliveryProgressService {
    private final String instanceId = UUID.randomUUID().toString();
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Map<SseEmitter, String> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> pendingSnapshots = new ConcurrentHashMap<>();
    private static final int RECORD_LIMIT = 100;

    private static class Run {
        String id = "";
        long revision;
        String state = "idle";
        int found, matched, filtered, attempted;
        JobProgressMessage latest;
        final LinkedHashMap<String, JobProgressMessage> records = new LinkedHashMap<>();
        final Deque<JobProgressMessage> events = new ArrayDeque<>();
    }

    public synchronized void begin(String platform) {
        Run previous = runs.get(platform);
        Run run = new Run();
        run.id = UUID.randomUUID().toString();
        run.revision = previous == null ? 0 : previous.revision;
        run.state = "starting";
        runs.put(platform, run);
        publish(JobProgressMessage.info(platform, "任务已启动，正在准备搜索"));
    }

    public synchronized void stopping(String platform) {
        Run run = runs.computeIfAbsent(platform, ignored -> new Run());
        run.state = "stopping";
        publish(JobProgressMessage.info(platform, "已收到结束请求，等待当前投递操作完成并生成汇总"));
    }

    public synchronized void finish(String platform, boolean stopped, boolean failed) {
        Run run = runs.computeIfAbsent(platform, ignored -> new Run());
        run.state = failed ? "failed" : stopped ? "stopped" : "completed";
        run.records.replaceAll((key, record) -> {
            if (!"delivering".equals(record.getType())) return record;
            JobProgressMessage unconfirmed = JobProgressMessage.activity(platform, "delivery",
                    record.getCompany(), record.getJobTitle(), record.getJobLink(), "任务已结束，未取得该岗位的最终反馈");
            unconfirmed.setDeliveryStatus("结果未确认");
            return unconfirmed;
        });
        publish(JobProgressMessage.info(platform, failed ? "任务异常结束，请查看进度记录"
                : stopped ? "本次投递已结束" : "本次投递已完成"));
    }

    public synchronized void publish(JobProgressMessage message) {
        Run run = runs.computeIfAbsent(message.getPlatform(), ignored -> new Run());
        run.revision++;
        run.latest = message;
        switch (message.getType()) {
            case "found" -> {
                run.found += Math.max(0, Optional.ofNullable(message.getCurrent()).orElse(0));
                if (!"stopping".equals(run.state)) run.state = "running";
            }
            case "matched" -> run.matched++;
            case "filtered" -> run.filtered++;
            case "delivery" -> run.attempted++;
        }
        if (Set.of("matched", "skipped", "delivery", "delivering").contains(message.getType())) {
            String fallbackKey = message.getCompany() + "|" + message.getJobTitle();
            String key = message.getJobLink() == null || message.getJobLink().isBlank()
                    ? fallbackKey : message.getJobLink();
            // 部分平台直到提交时才拿到链接，替换此前按公司和岗位保存的记录。
            if (!key.equals(fallbackKey)) run.records.remove(fallbackKey);
            run.records.remove(key);
            run.records.put(key, message);
            while (run.records.size() > RECORD_LIMIT) run.records.remove(run.records.keySet().iterator().next());
        }
        run.events.addLast(message);
        while (run.events.size() > 50) run.events.removeFirst();
        pendingSnapshots.put(message.getPlatform(), snapshot(message.getPlatform()));
    }

    /** 合并密集事件；浏览器连接缓慢时不阻塞投递线程和停止请求。 */
    @Scheduled(fixedDelay = 250)
    public void flush() {
        pendingSnapshots.forEach((platform, snapshot) -> {
            if (!pendingSnapshots.remove(platform, snapshot)) return;
            subscribers.forEach((emitter, subscribedPlatform) -> {
                if (platform.equals(subscribedPlatform)) send(emitter, "snapshot", snapshot);
            });
        });
    }

    public synchronized Map<String, Object> snapshot(String platform) {
        Run run = runs.computeIfAbsent(platform, ignored -> new Run());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("runId", run.id);
        result.put("revision", run.revision);
        result.put("state", run.state);
        result.put("found", run.found);
        result.put("matched", run.matched);
        result.put("filtered", run.filtered);
        result.put("attempted", run.attempted);
        result.put("latest", run.latest);
        result.put("records", new ArrayList<>(run.records.values()).reversed());
        result.put("events", new ArrayList<>(run.events).reversed());
        return result;
    }

    public SseEmitter subscribe(String platform) {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.put(emitter, platform);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        send(emitter, "snapshot", snapshot(platform));
        return emitter;
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        subscribers.forEach((emitter, platform) -> send(emitter, "ping", "keep-alive"));
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception error) {
            subscribers.remove(emitter);
            try { emitter.complete(); } catch (RuntimeException ignored) {}
        }
    }
}
