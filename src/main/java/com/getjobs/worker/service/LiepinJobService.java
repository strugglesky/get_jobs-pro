package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.DeliveryRunReportService;
import com.getjobs.application.service.DeliveryProgressService;
import com.getjobs.worker.utils.Platform;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 猎聘任务服务
 * 负责猎聘平台的自动投递任务管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiepinJobService implements JobPlatformService {

    private static final String PLATFORM = "liepin";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Liepin> liepinProvider;
    private final DeliveryRunReportService reports;
    private final DeliveryProgressService deliveryProgress;
    private final java.util.concurrent.atomic.AtomicBoolean executing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile DeliveryRunReportService.Session activeReport;

    // 运行状态标志
    private volatile boolean isRunning = false;

    // 停止请求标志
    private volatile boolean shouldStop = false;

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
            // 浏览器可能已被手动关闭 / 崩溃，先确保可用，必要时重新拉起
            updates.accept(JobProgressMessage.info(PLATFORM, "检查浏览器状态..."));
            playwrightManager.ensureReady();

            Page page = playwrightManager.getLiepinPage();
            if (page == null) {
                throw new IllegalStateException("猎聘页面未初始化");
            }

            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                throw new IllegalStateException("请先登录猎聘");
            }

            report = reports.begin(Platform.LIEPIN);
            activeReport = report;
            if (shouldStop) report.requestStop();
            isRunning = true;

            // 暂停后台登录监控，避免并发访问冲突
            playwrightManager.pauseLiepinMonitoring();

            // 加载配置（统一通过 ConfigService 从专表读取）
            LiepinConfig config = configService.getLiepinConfig();
            updates.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            updates.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建并执行 Bean
            Liepin.ProgressCallback cb = (message, current, total) -> {
                if (current != null && total != null) {
                    JobProgressMessage update = JobProgressMessage.progress(PLATFORM, message, current, total);
                    updates.accept(update);
                } else {
                    JobProgressMessage update = JobProgressMessage.info(PLATFORM, message);
                    updates.accept(update);
                }
            };

            Liepin liepin = liepinProvider.getObject();
            var recordingSession = report;
            liepin.setDeliveryCallback(notification -> {
                recordingSession.record(notification);
                deliveryProgress.publish(JobProgressMessage.delivery(PLATFORM, notification));
            });
            liepin.setActivityCallback(deliveryProgress::publish);
            liepin.setPage(page);
            liepin.setConfig(config);
            liepin.setProgressCallback(cb);
            liepin.setShouldStopCallback(this::shouldStop);

            // 必须跑在 Playwright 专用线程上，否则会和后台登录监控并发操作同一条连接
            int deliveredCount = playwrightManager.callOnPlaywright(liepin::execute);

            updates.accept(JobProgressMessage.success(PLATFORM,
                String.format("投递任务完成，共发起%d个聊天", deliveredCount)));
        } catch (Exception e) {
            endReason = "任务异常（" + e.getClass().getSimpleName() + "）";
            log.error("猎聘投递任务执行失败", e);
            updates.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            try {
                if (report != null) reports.finish(report, endReason);
            } catch (Exception error) {
                endReason = "任务异常: report failed";
                updates.accept(JobProgressMessage.error(PLATFORM, "Report failed: " + error.getMessage()));
            } finally {
                try {
                    playwrightManager.resumeLiepinMonitoring();
                } catch (Exception ignored) {}
                synchronized (this) {
                    deliveryProgress.finish(PLATFORM, shouldStop, endReason.startsWith("任务异常"));
                    activeReport = null;
                    isRunning = false;
                    shouldStop = false;
                    executing.set(false);
                }
            }
        }
    }

    @Override
    public synchronized void stopDelivery() {
        if (!executing.get()) {
            log.warn("猎聘任务未在运行，无需停止");
            return;
        }
        log.info("收到停止猎聘任务请求");
        var report = activeReport;
        if (report != null) report.requestStop();
        shouldStop = true;
        deliveryProgress.stopping(PLATFORM);
    }

    /**
     * 获取任务状态
     */
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

    public boolean shouldStop() {
        return shouldStop;
    }

    
}
