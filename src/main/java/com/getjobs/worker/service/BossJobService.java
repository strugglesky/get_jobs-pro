package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.DeliveryRunReportService;
import com.getjobs.application.service.DeliveryProgressService;
import com.getjobs.worker.utils.Platform;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Boss直聘任务服务
 * 管理Boss平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;
    private final DeliveryRunReportService reports;
    private final DeliveryProgressService deliveryProgress;
    private final java.util.concurrent.atomic.AtomicBoolean executing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile DeliveryRunReportService.Session activeReport;

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;
    // 本次任务的开始时间（毫秒），用于判断任务是不是卡死了
    private volatile long runningSince = 0L;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        synchronized (this) {
            if (!executing.compareAndSet(false, true)) {
                progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
                return;
            }
            shouldStop = false;
        }

        Consumer<JobProgressMessage> updates = update -> {
            deliveryProgress.publish(update);
            progressCallback.accept(update);
        };
        DeliveryRunReportService.Session report = null;
        String endReason = "任务完成";
        try {
            deliveryProgress.begin(PLATFORM);
            if (shouldStop) deliveryProgress.stopping(PLATFORM);
            // 浏览器可能已经被关掉（手动关窗口 / Chrome 崩溃），这时各字段仍非空，
            // 但每个操作都会抛 TargetClosedError。先确保浏览器可用，必要时重新拉起。
            updates.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            playwrightManager.ensureReady();

            // 获取Boss页面实例
            Page page = playwrightManager.getBossPage();
            if (page == null) {
                throw new IllegalStateException("Boss页面未初始化");
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                throw new IllegalStateException("请先登录Boss直聘");
            }

            // 通过校验后再标记运行
            runningSince = System.currentTimeMillis();
            report = reports.begin(Platform.BOSS);
            activeReport = report;
            if (shouldStop) report.requestStop();
            isRunning = true;

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pauseBossMonitoring();

            // 加载配置（统一从 boss_config 专表读取）
            BossConfig config = configService.getBossConfig();
            updates.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            updates.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建Boss实例并执行投递
            Boss.ProgressCallback bossCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    JobProgressMessage update = JobProgressMessage.progress(PLATFORM, message, current, total);
                    updates.accept(update);
                } else {
                    JobProgressMessage update = JobProgressMessage.info(PLATFORM, message);
                    updates.accept(update);
                }
            };

            Boss boss = bossProvider.getObject();
            var recordingSession = report;
            boss.setDeliveryCallback(notification -> {
                recordingSession.record(notification);
                deliveryProgress.publish(JobProgressMessage.delivery(PLATFORM, notification));
            });
            boss.setActivityCallback(deliveryProgress::publish);
            boss.setPage(page);
            boss.setConfig(config);
            boss.setProgressCallback(bossCallback);
            boss.setShouldStopCallback(this::shouldStop);
            boss.prepare();

            // 整个投递流程必须跑在 Playwright 专用线程上。
            // 原来跑在 ForkJoinPool 上，会和 scheduling-1 的登录监控同时操作同一条
            // Playwright 连接，导航被打断报 net::ERR_ABORTED，整个任务直接挂掉。
            int deliveredCount = playwrightManager.callOnPlaywright(boss::execute);

            updates.accept(JobProgressMessage.success(PLATFORM,
                String.format("投递任务完成，共发起%d个聊天", deliveredCount)));
        } catch (Exception e) {
            endReason = "任务异常（" + e.getClass().getSimpleName() + "）";
            log.error("Boss投递任务执行失败", e);
            updates.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            try {
                if (report != null) reports.finish(report, endReason);
            } catch (Exception error) {
                endReason = "任务异常: report failed";
                updates.accept(JobProgressMessage.error(PLATFORM, "Report failed: " + error.getMessage()));
            } finally {
                try {
                    playwrightManager.resumeBossMonitoring();
                } catch (Exception ignored) {}
                synchronized (this) {
                    deliveryProgress.finish(PLATFORM, shouldStop, endReason.startsWith("任务异常"));
                    activeReport = null;
                    isRunning = false;
                    shouldStop = false;
                    runningSince = 0L;
                    executing.set(false);
                }
            }
        }
    }

    @Override
    public synchronized void stopDelivery() {
        if (!executing.get() || shouldStop) {
            return;
        }
        log.info("收到停止Boss投递任务的请求");
        DeliveryRunReportService.Session stoppingReport = activeReport;
        if (stoppingReport != null) stoppingReport.requestStop();
        shouldStop = true;
        deliveryProgress.stopping(PLATFORM);

        // 停止是协作式的；实际退出前不复位运行状态，避免启动新任务及提前汇总遗漏结果。
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + FORCE_RESET_GRACE_MS;
            while (isRunning && activeReport == stoppingReport && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (isRunning && activeReport == stoppingReport) {
                log.warn("Boss投递任务在 {} 秒内尚未退出，继续等待当前操作收尾后生成完整报告",
                        FORCE_RESET_GRACE_MS / 1000);
            }
        }, "boss-stop-watchdog").start();
    }

    /** 停止指令发出后，等待任务自行退出的宽限时间 */
    private static final long FORCE_RESET_GRACE_MS = 15_000L;

    /** 任务已经跑了多久（毫秒）；没在跑返回 0 */
    public long runningForMillis() {
        long since = runningSince;
        return since == 0L ? 0L : System.currentTimeMillis() - since;
    }

    @Override
    public synchronized Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", executing.get());
        status.put("isStopping", shouldStop);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return executing.get();
    }

    /**
     * 检查是否应该停止
     * 供Boss.java调用
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    
}
