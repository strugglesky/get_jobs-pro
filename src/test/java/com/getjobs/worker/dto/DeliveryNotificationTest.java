package com.getjobs.worker.dto;

import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.Platform;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryNotificationTest {
    @Test
    void formatsAllDeliveryDetailsAndResolvesRelativeLink() {
        Job job = new Job();
        job.setCompanyName("示例公司");
        job.setJobName("Java 工程师");
        job.setHref("/job/123");
        job.setSalary("20-30K");
        job.setJobArea("上海");

        String message = DeliveryNotification.of(Platform.LIEPIN, job,
                DeliveryNotification.Status.SUBMITTED, "平台尚未确认送达").toText();

        assertTrue(message.contains("投递平台：猎聘"));
        assertTrue(message.contains("投递公司：示例公司"));
        assertTrue(message.contains("岗位名称：Java 工程师"));
        assertTrue(message.contains("https://www.liepin.com/job/123"));
        assertTrue(message.contains("投递时间："));
        assertTrue(message.contains("投递状态：已提交，待平台确认"));
        assertTrue(message.contains("薪资：20-30K"));
        assertTrue(message.contains("工作地点：上海"));
        assertTrue(message.contains("平台尚未确认送达"));
    }

    @Test
    void retainsEveryFieldWithinTheProviderTextLimit() {
        Job job = new Job();
        job.setCompanyName("公司".repeat(200));
        job.setJobName("职位😀".repeat(200));
        job.setJobArea("上海".repeat(200));
        job.setSalary("薪资".repeat(200));
        job.setHref("https://www.zhipin.com/job_detail/" + "x".repeat(2000));
        String text = DeliveryNotification.of(Platform.BOSS, job,
                DeliveryNotification.Status.SUBMITTED, "结果详情".repeat(200)).toText();
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 2048);
        assertTrue(text.contains("投递链接：https://www.zhipin.com/job_detail/"));
    }

    @Test
    void refusesUntrustedLinkSchemesAndTruncatesAtUtf8CodePointBoundary() {
        Job job = new Job();
        job.setHref("javascript:alert(1)");
        var notification = DeliveryNotification.of(Platform.BOSS, job,
                DeliveryNotification.Status.FAILED, "结果中断 🔐");

        assertTrue(notification.toText().contains("投递链接：未获取"));
        String truncated = DeliveryNotification.truncateUtf8("职位😀".repeat(2000), 1024);
        assertTrue(truncated.endsWith("..."));
        assertTrue(truncated.getBytes(StandardCharsets.UTF_8).length <= 1024);
        assertFalse(truncated.substring(0, truncated.length() - 3).endsWith("\uD83D"));
    }
}
