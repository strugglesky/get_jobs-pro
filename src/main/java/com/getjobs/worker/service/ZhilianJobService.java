package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.application.service.DeliveryRunReportService;
import com.getjobs.application.service.DeliveryProgressService;
import com.getjobs.worker.utils.Platform;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.zhilian.ZhilianConfig;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 智联招聘任务服务
 * 管理智联招聘平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhilianJobService implements JobPlatformService {
    private static final String PLATFORM = "zhilian";

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<ZhiLian> zhilianProvider;
    private final ConfigService configService;
    private final DeliveryRunReportService reports;
    private final DeliveryProgressService deliveryProgress;
    private final java.util.concurrent.atomic.AtomicBoolean executing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile DeliveryRunReportService.Session activeReport;

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
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

            // 获取智联招聘页面实例
            Page page = playwrightManager.getZhilianPage();
            if (page == null) {
                throw new IllegalStateException("智联招聘页面未初始化");
            }

            // 检查是否已登录
            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                throw new IllegalStateException("请先登录智联招聘");
            }

            // 通过校验后再标记运行
            report = reports.begin(Platform.ZHILIAN);
            activeReport = report;
            if (shouldStop) report.requestStop();
            isRunning = true;

            // 暂停后台登录监控，避免与投递流程并发访问同一Page
            playwrightManager.pauseZhilianMonitoring();

            // 加载配置（统一从 zhilian_config 专表读取）
            ZhilianConfig config = configService.getZhilianConfig();
            updates.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));

            updates.accept(JobProgressMessage.info(PLATFORM, "开始投递任务..."));

            // 创建ZhiLian实例并执行投递
            ZhiLian.ProgressCallback zhilianCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    JobProgressMessage update = JobProgressMessage.progress(PLATFORM, message, current, total);
                    updates.accept(update);
                } else {
                    JobProgressMessage update = JobProgressMessage.info(PLATFORM, message);
                    updates.accept(update);
                }
            };

            ZhiLian zhilian = zhilianProvider.getObject();
            var recordingSession = report;
            zhilian.setDeliveryCallback(notification -> {
                recordingSession.record(notification);
                deliveryProgress.publish(JobProgressMessage.delivery(PLATFORM, notification));
            });
            zhilian.setActivityCallback(deliveryProgress::publish);
            zhilian.setPage(page);
            zhilian.setConfig(config);
            zhilian.setProgressCallback(zhilianCallback);
            zhilian.setShouldStopCallback(this::shouldStop);
            zhilian.prepare();

            // 必须跑在 Playwright 专用线程上，否则会和后台登录监控并发操作同一条连接
            int deliveredCount = playwrightManager.callOnPlaywright(zhilian::execute);

            updates.accept(JobProgressMessage.success(PLATFORM,
                String.format("投递任务完成，共投递%d个职位", deliveredCount)));
        } catch (Exception e) {
            endReason = "任务异常（" + e.getClass().getSimpleName() + "）";
            log.error("智联招聘投递任务执行失败", e);
            updates.accept(JobProgressMessage.error(PLATFORM, "投递失败: " + e.getMessage()));
        } finally {
            try {
                if (report != null) reports.finish(report, endReason);
            } catch (Exception error) {
                endReason = "任务异常: report failed";
                updates.accept(JobProgressMessage.error(PLATFORM, "Report failed: " + error.getMessage()));
            } finally {
                try {
                    playwrightManager.resumeZhilianMonitoring();
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
        if (executing.get()) {
            log.info("收到停止智联招聘投递任务的请求");
            var report = activeReport;
            if (report != null) report.requestStop();
            shouldStop = true;
            deliveryProgress.stopping(PLATFORM);
        }
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
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    
}
