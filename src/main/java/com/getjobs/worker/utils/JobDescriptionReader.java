package com.getjobs.worker.utils;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

/** 只读取岗位详情的职责描述区域，避免用列表摘要、推荐岗位或公司信息代替工作内容。 */
@Slf4j
public final class JobDescriptionReader {
    private JobDescriptionReader() {}

    public static String read(Page source, Platform platform, String link) {
        Page detail = null;
        try {
            if (link == null || link.isBlank()) return "";
            URI address = URI.create(source.url()).resolve(link);
            if (!isPlatformAddress(address, platform)) return "";
            String selector = switch (platform) {
                case LIEPIN -> ".job-intro-container .paragraph, .job-intro-container, .job-description";
                case ZHILIAN -> ".describtion__detail-content, .job-description, .job-detail-description";
                case JOB51 -> ".job_msg, .job-description, .job-describe, .job-detail-description";
                default -> "";
            };
            if (selector.isEmpty()) return "";
            detail = BackgroundPages.open(source);
            detail.navigate(address.toString(), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(10000));
            if (!isPlatformAddress(URI.create(detail.url()), platform)) return "";
            var description = detail.locator(selector).first();
            return description.innerText(new com.microsoft.playwright.Locator.InnerTextOptions().setTimeout(5000));
        } catch (Exception e) {
            log.info("{}岗位工作内容未获取，按内容筛选规则跳过（{}）", platform.getPlatformName(), e.getClass().getSimpleName());
            return "";
        } finally {
            if (detail != null) {
                try { detail.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean isPlatformAddress(URI uri, Platform platform) {
        String domain = switch (platform) {
            case LIEPIN -> "liepin.com";
            case ZHILIAN -> "zhaopin.com";
            case JOB51 -> "51job.com";
            default -> "";
        };
        String host = uri.getHost();
        return !domain.isEmpty() && host != null && (host.equalsIgnoreCase(domain)
                || host.toLowerCase(java.util.Locale.ROOT).endsWith("." + domain))
                && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
    }
}
