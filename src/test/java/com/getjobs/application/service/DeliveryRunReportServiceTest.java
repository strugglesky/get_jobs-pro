package com.getjobs.application.service;

import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.utils.Bot;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryRunReportServiceTest {
    @TempDir Path directory;

    @Test
    void finishesOnceCapturesStopReasonAndQueuesSummaryWithWorkbook() throws Exception {
        Bot bot = mock(Bot.class);
        var service = new DeliveryRunReportService(new DeliveryReportWriter(), bot, directory);
        var session = service.begin(Platform.BOSS);
        Job job = new Job();
        job.setCompanyName("示例公司");
        job.setJobName("工程师");
        job.setHref("/job_detail/123");
        session.record(DeliveryNotification.of(Platform.BOSS, job,
                DeliveryNotification.Status.SUBMITTED, "待平台确认"));
        session.requestStop();

        service.finish(session, "任务完成");
        service.finish(session, "任务完成");

        List<Path> files;
        try (var stream = Files.list(directory)) { files = stream.toList(); }
        assertEquals(1, files.size());
        assertTrue(files.get(0).getFileName().toString().endsWith(".xlsx"));
        assertTrue(Files.size(files.get(0)) > 1000);
        verify(bot, times(1)).notifyReport(contains("结束原因：用户结束投递"), eq(files.get(0)));
    }
}
