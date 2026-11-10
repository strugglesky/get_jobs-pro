package com.getjobs.application.service;

import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.Platform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryProgressServiceTest {
    @Test
    void retainsCountsLatestResultAndSeparatesPlatforms() {
        var service = new DeliveryProgressService();
        service.begin("boss");
        service.publish(JobProgressMessage.found("boss", 12));
        service.publish(JobProgressMessage.activity("boss", "matched", "公司", "开发", "https://example.com/job", "通过筛选"));
        service.publish(JobProgressMessage.activity("boss", "filtered", "公司2", "销售", null, "命中排除词"));
        service.publish(JobProgressMessage.activity("boss", "delivering", "公司", "开发", "https://example.com/job", "正在投递"));
        Job job = new Job();
        job.setCompanyName("公司");
        job.setJobName("开发");
        job.setHref("https://example.com/job");
        service.publish(JobProgressMessage.delivery("boss", DeliveryNotification.of(Platform.BOSS, job,
                DeliveryNotification.Status.SUBMITTED, "已提交")));
        var snapshot = service.snapshot("boss");
        assertEquals(12, snapshot.get("found"));
        assertEquals(1, snapshot.get("matched"));
        assertEquals(1, snapshot.get("filtered"));
        assertEquals(1, snapshot.get("attempted"));
        var records = (List<?>) snapshot.get("records");
        assertEquals(1, records.size());
        assertEquals("已提交，待平台确认", ((JobProgressMessage) records.getFirst()).getDeliveryStatus());
        assertEquals(0, service.snapshot("liepin").get("found"));
        service.stopping("boss");
        service.publish(JobProgressMessage.found("boss", 3));
        assertEquals("stopping", service.snapshot("boss").get("state"));
        service.finish("boss", true, false);
        assertEquals("stopped", service.snapshot("boss").get("state"));
        service.begin("boss");
        var next = service.snapshot("boss");
        assertNotEquals(snapshot.get("runId"), next.get("runId"));
        assertEquals(0, next.get("found"));
        assertTrue(((List<?>) next.get("records")).isEmpty());
        assertTrue((long) next.get("revision") > (long) snapshot.get("revision"));
        assertEquals(snapshot.get("instanceId"), next.get("instanceId"));
        assertNotEquals(next.get("instanceId"), new DeliveryProgressService().snapshot("boss").get("instanceId"));
    }

    @Test
    void boundsRetainedHistoryWithoutLosingCountersOrPreviousSnapshot() {
        var service = new DeliveryProgressService();
        service.begin("51job");
        var original = service.snapshot("51job");
        for (int i = 0; i < 150; i++) service.publish(JobProgressMessage.activity("51job", "delivering",
                "company", "job" + i, "https://example.com/" + i, "submitting"));
        var snapshot = service.snapshot("51job");
        assertEquals(100, ((List<?>) snapshot.get("records")).size());
        assertEquals(50, ((List<?>) snapshot.get("events")).size());
        assertTrue(((List<?>) original.get("records")).isEmpty());
        service.finish("51job", false, true);
        assertEquals("failed", service.snapshot("51job").get("state"));
    }

    @Test
    void keepsMatchedAndSkippedJobsVisibleUntilTheirOutcomeUpdatesTheRecord() {
        var service = new DeliveryProgressService();
        service.begin("zhilian");
        service.publish(JobProgressMessage.activity("zhilian", "matched", "示例公司", "Java工程师",
                "https://jobs.zhaopin.com/123", "通过筛选，准备提交投递"));
        service.publish(JobProgressMessage.activity("zhilian", "skipped", "示例公司", "Java工程师",
                "https://jobs.zhaopin.com/123", "未找到立即投递按钮，未投递"));

        var records = (List<?>) service.snapshot("zhilian").get("records");
        assertEquals(1, records.size());
        JobProgressMessage record = (JobProgressMessage) records.getFirst();
        assertEquals("skipped", record.getType());
        assertEquals("未找到立即投递按钮，未投递", record.getMessage());
    }

    @Test
    void reconcilesRelativeLinksAndDoesNotLeavePendingRecordsAfterFinish() {
        var service = new DeliveryProgressService();
        service.begin("boss");
        service.publish(JobProgressMessage.activity("boss", "delivering", "公司", "岗位", "/job_detail/123", "正在投递"));
        Job job = new Job();
        job.setHref("/job_detail/123");
        job.setCompanyName("公司");
        job.setJobName("岗位");
        service.publish(JobProgressMessage.delivery("boss", DeliveryNotification.of(Platform.BOSS, job,
                DeliveryNotification.Status.SUCCESS, "投递成功")));
        service.publish(JobProgressMessage.activity("boss", "delivering", "公司2", "岗位2", "/job_detail/456", "正在投递"));
        service.finish("boss", true, false);
        var records = (List<?>) service.snapshot("boss").get("records");
        assertEquals(2, records.size());
        assertEquals("结果未确认", ((JobProgressMessage) records.getFirst()).getDeliveryStatus());
        assertEquals("投递成功", ((JobProgressMessage) records.getLast()).getDeliveryStatus());
    }
}
