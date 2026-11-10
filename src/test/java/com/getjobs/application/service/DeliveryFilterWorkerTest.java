package com.getjobs.application.service;

import com.getjobs.application.entity.LiepinEntity;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.job51.Job51;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.utils.*;
import com.microsoft.playwright.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryFilterWorkerTest {
    static Stream<Arguments> rejectedJobs() {
        return Stream.of("boss", "liepin", "zhilian", "51job").flatMap(platform ->
                Stream.of("titleInclude", "titleExclude", "contentInclude", "contentExclude").map(rule -> Arguments.of(platform, rule)));
    }

    @ParameterizedTest
    @MethodSource("rejectedJobs")
    @SuppressWarnings("unchecked")
    void skipsBeforeApplicationAndNotificationOnEveryPlatform(String platform, String rule) {
        var filter = new DeliveryFilterService.DeliveryFilter(
                rule.equals("titleInclude") ? List.of("Python") : List.of(),
                rule.equals("titleExclude") ? List.of("Java") : List.of(),
                rule.equals("contentInclude") ? List.of("Kubernetes") : List.of(),
                rule.equals("contentExclude") ? List.of("服务开发") : List.of());
        Bot bot = mock(Bot.class);
        Page page = mock(Page.class);
        Locator empty = mock(Locator.class);
        Locator cards = mock(Locator.class);
        Locator card = mock(Locator.class);
        Locator title = mock(Locator.class);
        Locator titles = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(empty);
        when(cards.count()).thenReturn(1);
        when(cards.nth(0)).thenReturn(card);
        when(card.locator(anyString())).thenReturn(empty);
        when(empty.first()).thenReturn(empty);
        when(title.first()).thenReturn(title);
        when(title.count()).thenReturn(1);
        when(title.textContent()).thenReturn("Java工程师");
        when(title.innerText(any(Locator.InnerTextOptions.class))).thenReturn("Java工程师");
        when(title.getAttribute("href")).thenReturn("https://jobs.zhaopin.com/123.html");
        when(title.evaluate(anyString())).thenReturn("https://we.51job.com/pc/jobdetail?jobId=123");
        when(titles.count()).thenReturn(1);
        when(titles.nth(0)).thenReturn(title);

        try (var descriptions = mockStatic(JobDescriptionReader.class); var sleeps = mockStatic(PlaywrightUtil.class)) {
            descriptions.when(() -> JobDescriptionReader.read(any(), any(), any())).thenReturn("负责 Java 服务开发");
            switch (platform) {
                case "boss" -> {
                    Boss worker = new Boss(mock(BossService.class), null, null, bot);
                    worker.setPage(page);
                    worker.setConfig(new BossConfig());
                    ReflectionTestUtils.setField(worker, "deliveryFilter", filter);
                    Job job = new Job();
                    job.setJobName("Java工程师");
                    job.setJobInfo("负责 Java 服务开发");
                    assertEquals(false, ReflectionTestUtils.invokeMethod(worker, "resumeSubmission", "Java", job));
                    verifyNoInteractions(page);
                }
                case "liepin" -> {
                    Liepin worker = new Liepin();
                    worker.setPage(page);
                    ReflectionTestUtils.setField(worker, "bot", bot);
                    ReflectionTestUtils.setField(worker, "deliveryFilter", filter);
                    LiepinEntity entity = new LiepinEntity();
                    entity.setJobTitle("Java工程师");
                    ((List<LiepinEntity>) ReflectionTestUtils.getField(worker, "lastApiEntities")).add(entity);
                    when(page.locator(com.getjobs.worker.liepin.Locators.JOB_CARDS)).thenReturn(cards);
                    when(card.locator(".job-title, [class*='job-title'], [class*='jobTitle']")).thenReturn(title);
                    ReflectionTestUtils.invokeMethod(worker, "submitJob");
                    verify(card, never()).locator("button.ant-btn.ant-btn-primary.ant-btn-round");
                }
                case "zhilian" -> {
                    ZhilianService service = mock(ZhilianService.class);
                    ZhiLian worker = new ZhiLian(service, bot);
                    worker.setPage(page);
                    ReflectionTestUtils.setField(worker, "deliveryFilter", filter);
                    when(page.locator("div.joblist-box__item")).thenReturn(cards);
                    when(card.locator("a.jobinfo__name")).thenReturn(title);
                    ReflectionTestUtils.invokeMethod(worker, "deliverCurrentPage", "Java");
                    verify(card, never()).locator("button.collect-and-apply__btn");
                    verify(service, never()).markDeliveredByJobId(anyString());
                }
                default -> {
                    Job51 worker = new Job51(mock(Job51Service.class), bot);
                    worker.setPage(page);
                    ReflectionTestUtils.setField(worker, "deliveryFilter", filter);
                    when(page.locator("div.ick")).thenReturn(cards);
                    when(page.locator("[class*='jname text-cut']")).thenReturn(titles);
                    ReflectionTestUtils.invokeMethod(worker, "deliverCurrentPage");
                    verify(page, never()).locator("div.tabs_in");
                    verify(card, never()).evaluate("el => el.click()");
                }
            }
            verifyNoInteractions(bot);
        }
    }
}
