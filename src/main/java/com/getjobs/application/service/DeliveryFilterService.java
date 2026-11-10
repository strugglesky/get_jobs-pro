package com.getjobs.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** 投递前的岗位名称、工作内容筛选。规则来自 config 表，支持逗号、中文逗号和换行。 */
@Service
public class DeliveryFilterService {
    public static final String TITLE_INCLUDE = "DELIVERY_FILTER_TITLE_INCLUDE";
    public static final String TITLE_EXCLUDE = "DELIVERY_FILTER_TITLE_EXCLUDE";
    public static final String CONTENT_INCLUDE = "DELIVERY_FILTER_CONTENT_INCLUDE";
    public static final String CONTENT_EXCLUDE = "DELIVERY_FILTER_CONTENT_EXCLUDE";

    private final ConfigService configService;

    public DeliveryFilterService(ConfigService configService) {
        this.configService = configService;
    }

    public DeliveryFilter snapshot() {
        Settings settings = settings();
        return new DeliveryFilter(parse(settings.titleIncludes()), parse(settings.titleExcludes()),
                parse(settings.contentIncludes()), parse(settings.contentExcludes()));
    }

    public Settings settings() {
        Map<String, String> values = configService.getAllConfigsAsMap();
        return new Settings(value(values.get(TITLE_INCLUDE)), value(values.get(TITLE_EXCLUDE)),
                value(values.get(CONTENT_INCLUDE)), value(values.get(CONTENT_EXCLUDE)));
    }

    /** 四项规则一起保存，任何一项失败均回滚，避免启用一半规则。 */
    @Transactional
    public void save(Settings settings) {
        int updated = configService.batchUpdateConfigs(Map.of(
                TITLE_INCLUDE, settings.titleIncludes().strip(), TITLE_EXCLUDE, settings.titleExcludes().strip(),
                CONTENT_INCLUDE, settings.contentIncludes().strip(), CONTENT_EXCLUDE, settings.contentExcludes().strip()));
        if (updated != 4) throw new IllegalStateException("投递筛选配置未全部保存");
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    public record Settings(String titleIncludes, String titleExcludes, String contentIncludes, String contentExcludes) {}

    public static DeliveryFilter empty() {
        return new DeliveryFilter(List.of(), List.of(), List.of(), List.of());
    }

    private static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.replace('，', ',').split("[,\\r\\n]+"))
                .map(String::strip).filter(value -> !value.isEmpty())
                .distinct().toList();
    }

    public record DeliveryFilter(List<String> titleIncludes, List<String> titleExcludes,
                                 List<String> contentIncludes, List<String> contentExcludes) {
        public DeliveryFilter {
            titleIncludes = List.copyOf(titleIncludes);
            titleExcludes = List.copyOf(titleExcludes);
            contentIncludes = List.copyOf(contentIncludes);
            contentExcludes = List.copyOf(contentExcludes);
        }

        public Decision evaluate(String title, String content) {
            String safeTitle = normalize(title);
            String safeContent = normalize(content);
            if (safeTitle.isEmpty() && (!titleIncludes.isEmpty() || !titleExcludes.isEmpty())) {
                return new Decision(false, "未获取岗位名称，无法应用名称筛选");
            }
            String matched = firstMatch(titleExcludes, safeTitle);
            if (matched != null) return new Decision(false, "岗位名称命中排除词：" + matched);
            matched = firstMatch(contentExcludes, safeContent);
            if (matched != null) return new Decision(false, "工作内容命中排除词：" + matched);
            if (safeContent.isEmpty() && (!contentIncludes.isEmpty() || !contentExcludes.isEmpty())) {
                return new Decision(false, "未获取工作内容，无法安全应用工作内容筛选");
            }
            if (!titleIncludes.isEmpty() && firstMatch(titleIncludes, safeTitle) == null) {
                return new Decision(false, "岗位名称未命中包含词");
            }
            if (!contentIncludes.isEmpty() && firstMatch(contentIncludes, safeContent) == null) {
                return new Decision(false, safeContent.isEmpty() ? "未获取工作内容，无法命中包含词" : "工作内容未命中包含词");
            }
            return new Decision(true, "");
        }

        /** 名称不符合时不打开详情；没有内容规则时也不增加详情读取。 */
        public Decision evaluateWithContent(String title, Supplier<String> content) {
            String normalized = normalize(title);
            if (normalized.isEmpty() && hasTitleRules()) {
                return new Decision(false, "未获取岗位名称，无法应用名称筛选");
            }
            String excluded = firstMatch(titleExcludes, normalized);
            if (excluded != null) return new Decision(false, "岗位名称命中排除词：" + excluded);
            if (!titleIncludes.isEmpty() && firstMatch(titleIncludes, normalized) == null) {
                return new Decision(false, "岗位名称未命中包含词");
            }
            try {
                return evaluate(title, requiresContent() ? content.get() : "");
            } catch (RuntimeException e) {
                return new Decision(false, "未获取工作内容，无法应用内容筛选");
            }
        }

        public boolean requiresContent() {
            return !contentIncludes.isEmpty() || !contentExcludes.isEmpty();
        }

        public boolean hasTitleRules() {
            return !titleIncludes.isEmpty() || !titleExcludes.isEmpty();
        }

        public boolean isActive() {
            return hasTitleRules() || requiresContent();
        }

        private static String firstMatch(List<String> terms, String value) {
            for (String term : terms) {
                if (value.contains(normalize(term))) return term;
            }
            return null;
        }

        private static String normalize(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).strip();
        }
    }

    public record Decision(boolean accepted, String reason) {}
}
