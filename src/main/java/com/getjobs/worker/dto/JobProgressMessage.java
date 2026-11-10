package com.getjobs.worker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务进度消息DTO
 * 用于SSE推送任务执行进度给前端
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobProgressMessage {
    /**
     * 平台名称 (boss, liepin, job51, zhilian)
     */
    private String platform;

    /**
     * 消息类型 (info, progress, success, error, warning)
     */
    private String type;

    /**
     * 消息内容
     */
    private String message;

    /**
     * 当前进度（已处理数量）
     */
    private Integer current;

    /**
     * 总数
     */
    private Integer total;

    /**
     * 时间戳
     */
    private Long timestamp;

    private String company;
    private String jobTitle;
    private String jobLink;
    private String deliveryStatus;

    public JobProgressMessage(String platform, String type, String message, Integer current, Integer total, Long timestamp) {
        this.platform = platform;
        this.type = type;
        this.message = message;
        this.current = current;
        this.total = total;
        this.timestamp = timestamp;
    }

    /**
     * 创建进度消息
     */
    public static JobProgressMessage progress(String platform, String message, int current, int total) {
        return new JobProgressMessage(platform, "progress", message, current, total, System.currentTimeMillis());
    }

    /**
     * 创建信息消息
     */
    public static JobProgressMessage info(String platform, String message) {
        return new JobProgressMessage(platform, "info", message, null, null, System.currentTimeMillis());
    }

    /**
     * 创建成功消息
     */
    public static JobProgressMessage success(String platform, String message) {
        return new JobProgressMessage(platform, "success", message, null, null, System.currentTimeMillis());
    }

    /**
     * 创建错误消息
     */
    public static JobProgressMessage error(String platform, String message) {
        return new JobProgressMessage(platform, "error", message, null, null, System.currentTimeMillis());
    }

    /**
     * 创建警告消息
     */
    public static JobProgressMessage warning(String platform, String message) {
        return new JobProgressMessage(platform, "warning", message, null, null, System.currentTimeMillis());
    }

    public static JobProgressMessage delivery(String platform, DeliveryNotification notification) {
        JobProgressMessage message = new JobProgressMessage(platform, "delivery",
                notification.detail(), null, null, System.currentTimeMillis());
        message.company = notification.company();
        message.jobTitle = notification.title();
        message.jobLink = notification.link();
        message.deliveryStatus = notification.status().label();
        return message;
    }

    public static JobProgressMessage activity(String platform, String type, String company, String title,
                                              String link, String detail) {
        JobProgressMessage message = info(platform, detail);
        message.type = type;
        message.company = company;
        message.jobTitle = title;
        message.jobLink = DeliveryNotification.normalizeLink(switch (platform) {
            case "boss" -> com.getjobs.worker.utils.Platform.BOSS;
            case "liepin" -> com.getjobs.worker.utils.Platform.LIEPIN;
            case "51job" -> com.getjobs.worker.utils.Platform.JOB51;
            case "zhilian" -> com.getjobs.worker.utils.Platform.ZHILIAN;
            default -> throw new IllegalArgumentException("Unknown platform: " + platform);
        }, link);
        return message;
    }

    public static JobProgressMessage found(String platform, int count) {
        return new JobProgressMessage(platform, "found", "当前列表发现 " + count + " 个岗位，正在逐一筛选", count, null,
                System.currentTimeMillis());
    }
}
