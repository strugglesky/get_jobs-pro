package com.getjobs.application.service;

import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.job51.Job51;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.*;
import com.getjobs.worker.utils.Bot;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.Platform;
import com.getjobs.worker.zhilian.ZhiLian;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryRunLifecycleTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"boss", "liepin", "51job", "zhilian"})
    @SuppressWarnings("unchecked")
    void waitsForInFlightResultThenSummarizesOnceAndStartsNextRunWithEmptyRecords(String platform) throws Exception {
        Bot bot = mock(Bot.class);
        DeliveryRunReportService reports = new DeliveryRunReportService(new DeliveryReportWriter(), bot, directory);
        PlaywrightManager manager = mock(PlaywrightManager.class);
        when(manager.isLoggedIn(anyString())).thenReturn(true);
        Page page = mock(Page.class);
        when(manager.getBossPage()).thenReturn(page);
        when(manager.getLiepinPage()).thenReturn(page);
        when(manager.getJob51Page()).thenReturn(page);
        when(manager.getZhilianPage()).thenReturn(page);
        when(manager.callOnPlaywright(any())).thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
        ConfigService config = mock(ConfigService.class);
        ObjectProvider<Object> provider = mock(ObjectProvider.class);
        DeliveryProgressService progress = new DeliveryProgressService();
        Object worker;
        JobPlatformService service;
        switch (platform) {
            case "boss" -> {
                worker = mock(Boss.class);
                service = new BossJobService(manager, config, (ObjectProvider<Boss>) (Object) provider, reports, progress);
            }
            case "liepin" -> {
                worker = mock(Liepin.class);
                service = new LiepinJobService(manager, config, (ObjectProvider<Liepin>) (Object) provider, reports, progress);
            }
            case "51job" -> {
                worker = mock(Job51.class);
                service = new Job51JobService(manager, (ObjectProvider<Job51>) (Object) provider, config, reports, progress);
            }
            default -> {
                worker = mock(ZhiLian.class);
                service = new ZhilianJobService(manager, (ObjectProvider<ZhiLian>) (Object) provider, config, reports, progress);
            }
        }
        when(provider.getObject()).thenReturn(worker);
        AtomicReference<Consumer<DeliveryNotification>> callback = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Job job = new Job();
        job.setCompanyName("本次公司");
        var delivery = DeliveryNotification.of(Platform.BOSS, job, DeliveryNotification.Status.SUBMITTED, "待确认");
        org.mockito.stubbing.Answer<Integer> execute = invocation -> {
            callback.get().accept(delivery);
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release worker");
            callback.get().accept(delivery);
            return 2;
        };
        if (worker instanceof Boss value) {
            doAnswer(invocation -> { callback.set(invocation.getArgument(0)); return null; }).when(value).setDeliveryCallback(any());
            when(value.execute()).thenAnswer(execute).thenReturn(0);
        } else if (worker instanceof Liepin value) {
            doAnswer(invocation -> { callback.set(invocation.getArgument(0)); return null; }).when(value).setDeliveryCallback(any());
            when(value.execute()).thenAnswer(execute).thenReturn(0);
        } else if (worker instanceof Job51 value) {
            doAnswer(invocation -> { callback.set(invocation.getArgument(0)); return null; }).when(value).setDeliveryCallback(any());
            when(value.execute()).thenAnswer(execute).thenReturn(0);
        } else if (worker instanceof ZhiLian value) {
            doAnswer(invocation -> { callback.set(invocation.getArgument(0)); return null; }).when(value).setDeliveryCallback(any());
            when(value.execute()).thenAnswer(execute).thenReturn(0);
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = executor.submit(() -> service.executeDelivery(event -> {}));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.stopDelivery();
            service.stopDelivery();
            service.executeDelivery(event -> {});
            verify(bot, never()).notifyReport(anyString(), any());
            assertTrue(service.isRunning());
            assertEquals(true, service.getStatus().get("isStopping"));
            assertEquals("stopping", progress.snapshot(platform).get("state"));
            assertEquals(1, progress.snapshot(platform).get("attempted"));
            release.countDown();
            task.get(5, TimeUnit.SECONDS);
            assertFalse(service.isRunning());
            assertEquals(false, service.getStatus().get("isStopping"));
            assertEquals("stopped", progress.snapshot(platform).get("state"));
            assertEquals(2, progress.snapshot(platform).get("attempted"));
            verify(bot).notifyReport(argThat(summary -> summary.contains("用户结束投递") && summary.contains("投递记录：2 条")), any());
            service.executeDelivery(event -> {});
            verify(bot).notifyReport(argThat(summary -> summary.contains("结束原因：任务完成") && summary.contains("投递记录：0 条")), any());
            assertEquals(0, progress.snapshot(platform).get("attempted"));
            assertEquals("completed", progress.snapshot(platform).get("state"));
            when(manager.isLoggedIn(platform)).thenReturn(false);
            service.executeDelivery(event -> {});
            assertFalse(service.isRunning());
            assertEquals("failed", progress.snapshot(platform).get("state"));
            verify(bot, times(2)).notifyReport(anyString(), any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
