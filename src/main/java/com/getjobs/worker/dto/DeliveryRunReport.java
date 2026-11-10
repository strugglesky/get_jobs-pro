package com.getjobs.worker.dto;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 单次投递任务的最终快照，不从历史数据库推断本次结果。 */
public record DeliveryRunReport(String id, String platform, ZonedDateTime startedAt, ZonedDateTime endedAt,
                                String reason, List<DeliveryNotification> deliveries) {
    public DeliveryRunReport {
        deliveries = List.copyOf(deliveries);
    }

    public long count(DeliveryNotification.Status status) {
        return deliveries.stream().filter(delivery -> delivery.status() == status).count();
    }

    public String summary() {
        var format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return "【本次投递汇总】\n任务编号：" + id + "\n投递平台：" + platform
                + "\n结束原因：" + reason
                + "\n开始时间：" + startedAt.format(format)
                + "\n结束时间：" + endedAt.format(format) + "（北京时间）"
                + "\n任务时长：" + Duration.between(startedAt, endedAt).toSeconds() + " 秒"
                + "\n投递记录：" + deliveries.size() + " 条"
                + "\n涉及公司：" + companyCount() + " 家"
                + "\n确认成功：" + count(DeliveryNotification.Status.SUCCESS)
                + "\n已提交待确认：" + count(DeliveryNotification.Status.SUBMITTED)
                + "\n结果未确认：" + count(DeliveryNotification.Status.UNCONFIRMED)
                + "\n确认失败：" + count(DeliveryNotification.Status.FAILED)
                + "\n达到平台上限：" + count(DeliveryNotification.Status.LIMITED)
                + "\n统计仅包含本次已记录的投递尝试；待确认不计为成功。逐岗位明细见 Excel 附件。";
    }

    public long companyCount() {
        return deliveries.stream().map(DeliveryNotification::company)
                .filter(value -> value != null && !value.isBlank()).map(String::strip).distinct().count();
    }
}
