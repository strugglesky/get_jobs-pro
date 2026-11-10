package com.getjobs.worker.dto;

import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.Platform;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** 投递发生时的不可变快照；发送排队不会改变投递时间和岗位信息。 */
public record DeliveryNotification(String platform, String company, String title, String link,
                                   String salary, String area, ZonedDateTime time,
                                   Status status, String detail) {
    public enum Status {
        SUCCESS("投递成功"), SUBMITTED("已提交，待平台确认"),
        UNCONFIRMED("结果未确认"), FAILED("投递失败"), LIMITED("未投递：达到平台上限");

        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static DeliveryNotification of(Platform platform, Job job, Status status, String detail) {
        return new DeliveryNotification(platform.getPlatformName(), job.getCompanyName(), job.getJobName(),
                normalizeLink(platform, job.getHref()), job.getSalary(), job.getJobArea(),
                ZonedDateTime.now(ZoneId.of("Asia/Shanghai")), status, detail);
    }

    public static String normalizeLink(Platform platform, String link) {
        if (link == null || link.isBlank()) return null;
        String base = switch (platform) {
            case BOSS -> "https://www.zhipin.com/";
            case LIEPIN -> "https://www.liepin.com/";
            case ZHILIAN -> "https://www.zhaopin.com/";
            case JOB51 -> "https://we.51job.com/";
            default -> "";
        };
        try {
            var uri = java.net.URI.create(base).resolve(link.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())
                    ? uri.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String toText() {
        return "【投递通知】\n投递平台：" + field(platform, 80)
                + "\n投递公司：" + field(company, 180)
                + "\n岗位名称：" + field(title, 180)
                + "\n投递时间：" + time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "（北京时间）"
                + "\n投递状态：" + status.label
                + "\n薪资：" + field(salary, 80)
                + "\n工作地点：" + field(area, 100)
                + "\n说明：" + field(detail, 300)
                + "\n投递链接：" + field(link, 700);
    }

    private static String field(String value, int maxBytes) {
        return truncateUtf8(value == null || value.isBlank() ? "未获取" : value.strip(), maxBytes);
    }

    /** 企业微信文本上限为 2048 字节，按码点截断以保留完整中文和 emoji。 */
    public static String truncateUtf8(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            String character = new String(Character.toChars(cp));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes - 3) break;
            result.append(character);
            bytes += size;
            offset += Character.charCount(cp);
        }
        return result.append("...").toString();
    }
}
