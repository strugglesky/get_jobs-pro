package com.getjobs.application.service;

import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.dto.DeliveryRunReport;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.Platform;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryReportWriterTest {
    @Test
    void writesReadableSummaryAndDetailSheetsWithoutTreatingJobTextAsFormula() throws Exception {
        Job job = new Job();
        job.setCompanyName("示例 & 公司");
        job.setJobName("=HYPERLINK(\"https://invalid\")");
        job.setHref("https://www.zhipin.com/job_detail/123");
        var now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        var notification = DeliveryNotification.of(Platform.BOSS, job,
                DeliveryNotification.Status.UNCONFIRMED, "当前确认不足 <待复核>");
        var report = new DeliveryRunReport("test-001", "Boss直聘", now, now.plusSeconds(12),
                "用户结束投递", List.of(notification));
        var output = new ByteArrayOutputStream();

        new DeliveryReportWriter().write(report, output);

        var entries = new java.util.HashMap<String, String>();
        try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertTrue(entries.containsKey("xl/workbook.xml"));
        var parser = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        parser.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        parser.setNamespaceAware(true);
        for (var entry : entries.entrySet()) {
            assertDoesNotThrow(() -> parser.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    entry.getKey());
        }
        assertTrue(entries.get("xl/workbook.xml").contains("投递汇总"));
        assertTrue(entries.get("xl/workbook.xml").contains("投递明细"));
        String details = entries.get("xl/worksheets/sheet2.xml");
        assertTrue(details.contains("示例 &amp; 公司"));
        assertTrue(details.contains("=HYPERLINK(\"https://invalid\")"));
        assertFalse(details.contains("<f>"));
        assertTrue(details.contains("未确认"));
        assertTrue(details.contains("https://www.zhipin.com/job_detail/123"));
    }
}
