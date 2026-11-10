package com.getjobs.application.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryFilterServiceTest {
    @Test
    void excludesBeforeIncludesAndMatchesCaseInsensitively() {
        ConfigService config = mock(ConfigService.class);
        when(config.getAllConfigsAsMap()).thenReturn(Map.of(
                "DELIVERY_FILTER_TITLE_INCLUDE", "Java,后端",
                "DELIVERY_FILTER_TITLE_EXCLUDE", "外包",
                "DELIVERY_FILTER_CONTENT_INCLUDE", "Spring Boot",
                "DELIVERY_FILTER_CONTENT_EXCLUDE", "驻场"));
        var filter = new DeliveryFilterService(config).snapshot();
        assertTrue(filter.evaluate("Java开发工程师", "负责 SPRING BOOT 服务开发").accepted());
        assertFalse(filter.evaluate("Java外包开发", "Spring Boot").accepted());
        assertFalse(filter.evaluate("产品经理", "Spring Boot").accepted());
        assertFalse(filter.evaluate("Java开发", "驻场支持").accepted());
        assertFalse(filter.evaluate("Java开发", "").accepted());
    }

    @Test
    void supportsChineseCommasNewlinesAndLiteralPunctuationWithOrWithinEachField() {
        ConfigService config = mock(ConfigService.class);
        when(config.getAllConfigsAsMap()).thenReturn(Map.of(
                DeliveryFilterService.TITLE_INCLUDE, "  C++，.NET\r\nJava\n\n ",
                DeliveryFilterService.CONTENT_INCLUDE, "Kubernetes,Spring Boot"));
        var filter = new DeliveryFilterService(config).snapshot();
        assertEquals(List.of("C++", ".NET", "Java"), filter.titleIncludes());
        assertTrue(filter.evaluate("高级 .net 工程师", "负责 Kubernetes 集群维护").accepted());
        assertFalse(filter.evaluate("C++ 工程师", "仅负责文档编写").accepted());
        assertFalse(filter.evaluate("C 工程师", "Kubernetes").accepted());
    }

    @Test
    void emptyRulesKeepExistingBehaviorAndConfiguredMissingFieldsAreSkipped() {
        assertTrue(DeliveryFilterService.empty().evaluate(null, null).accepted());
        var content = new DeliveryFilterService.DeliveryFilter(List.of(), List.of(), List.of(), List.of("驻场"));
        assertFalse(content.evaluate("Java工程师", "  ").accepted());
        assertTrue(content.evaluate("Java工程师", "负责后端开发").accepted());
        var title = new DeliveryFilterService.DeliveryFilter(List.of(), List.of("销售"), List.of(), List.of());
        assertFalse(title.evaluate(null, "负责后端开发").accepted());
    }

    @Test
    void fetchesDescriptionOnlyForEligibleTitlesWithContentRulesAndDropsReadFailures() {
        var filter = new DeliveryFilterService.DeliveryFilter(List.of("Java"), List.of("外包"), List.of("Spring"), List.of());
        assertFalse(filter.evaluateWithContent("Java外包", () -> { fail("不应读取被排除岗位详情"); return ""; }).accepted());
        assertFalse(filter.evaluateWithContent("产品经理", () -> { fail("不应读取不匹配岗位详情"); return ""; }).accepted());
        assertTrue(filter.evaluateWithContent("Java工程师", () -> "Spring Boot").accepted());
        assertFalse(filter.evaluateWithContent("Java工程师", () -> { throw new IllegalStateException("详情不可读"); }).accepted());
        assertTrue(DeliveryFilterService.empty().evaluateWithContent("岗位", () -> { fail("无规则不应读取详情"); return ""; }).accepted());
    }

    @Test
    void reportsSaveFailureAndAllowsEmptyStringsToClearAllRules() {
        ConfigService config = mock(ConfigService.class);
        var service = new DeliveryFilterService(config);
        var settings = new DeliveryFilterService.Settings("", "", "", "");
        when(config.batchUpdateConfigs(anyMap())).thenReturn(3);
        assertThrows(IllegalStateException.class, () -> service.save(settings));
        when(config.batchUpdateConfigs(anyMap())).thenReturn(4);
        assertDoesNotThrow(() -> service.save(settings));
        verify(config, times(2)).batchUpdateConfigs(Map.of(
                DeliveryFilterService.TITLE_INCLUDE, "", DeliveryFilterService.TITLE_EXCLUDE, "",
                DeliveryFilterService.CONTENT_INCLUDE, "", DeliveryFilterService.CONTENT_EXCLUDE, ""));
    }
}
