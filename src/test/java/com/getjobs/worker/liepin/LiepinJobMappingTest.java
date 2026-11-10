package com.getjobs.worker.liepin;

import com.getjobs.application.service.LiepinService;
import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.utils.Bot;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiepinJobMappingTest {
    private static final String JOB_A = """
            {"job":{"jobId":111,"title":"role-a","link":"https://www.liepin.com/job/111.shtml","salary":"salary-a","dq":"area-a"},"comp":{"compName":"company-a"}}
            """;
    private static final String JOB_B = """
            {"job":{"jobId":222,"title":"role-b","link":"https://www.liepin.com/job/222.shtml","salary":"salary-b","dq":"area-b"},"comp":{"compName":"company-b"}}
            """;

    @ParameterizedTest
    @ValueSource(strings = {"reordered", "missing-id"})
    void keepsCurrentJobInNotificationsAndCallbacksWhenApiPositionsDiffer(String scenario) {
        var fixture = fixture("111", "https://www.liepin.com/job/111.shtml", true);
        String entries = scenario.equals("reordered") ? "[" + JOB_B + "," + JOB_A + "]"
                : "[{\"job\":{\"title\":\"role-a\"},\"comp\":{\"compName\":\"company-a\"}}," + JOB_B + "]";
        parse(fixture.worker(), entries);

        submit(fixture.worker());

        var notification = fixture.recorded().getFirst();
        assertEquals(1, fixture.recorded().size());
        assertEquals("company-a", notification.company());
        assertEquals("role-a", notification.title());
        assertEquals("https://www.liepin.com/job/111.shtml", notification.link());
        assertEquals("salary-a", notification.salary());
        assertEquals("area-a", notification.area());
        verify(fixture.bot()).notifyDelivery(notification);
        verify(fixture.service()).markDelivered(111L);
        verify(fixture.service(), never()).markDelivered(222L);
        for (var event : fixture.events()) {
            assertEquals("company-a", event.getCompany());
            assertEquals("role-a", event.getJobTitle());
            assertEquals(notification.link(), event.getJobLink());
        }
        assertTrue(fixture.events().stream().anyMatch(event -> event.getType().equals("matched")));
        assertTrue(fixture.events().stream().anyMatch(event -> event.getType().equals("delivering")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "external", "invalid-path"})
    void doesNotBorrowApiFieldsWhenTheCardHasNoUsableIdentity(String scenario) {
        String link = switch (scenario) {
            case "external" -> "https://example.com/job/222.shtml";
            case "invalid-path" -> "https://www.liepin.com/company/222.shtml";
            default -> null;
        };
        var fixture = fixture(null, link, false);
        parse(fixture.worker(), "[" + JOB_B + "]");

        submit(fixture.worker());

        var notification = fixture.recorded().getFirst();
        assertNull(notification.company());
        assertNull(notification.title());
        assertNull(notification.link());
        assertNull(notification.salary());
        assertNull(notification.area());
        assertTrue(notification.toText().contains("投递公司：未获取"));
        verify(fixture.service(), never()).markDelivered(anyLong());
    }

    @Test
    void matchesApiByTheJobIdInTheCardLinkWhenTrackingAttributesAreMissing() {
        var fixture = fixture(null, "/job/111.shtml", false);
        parse(fixture.worker(), "[" + JOB_B + "," + JOB_A + "]");

        submit(fixture.worker());

        var notification = fixture.recorded().getFirst();
        assertEquals("company-a", notification.company());
        assertEquals("role-a", notification.title());
        assertEquals("https://www.liepin.com/job/111.shtml", notification.link());
        assertEquals("salary-a", notification.salary());
        assertEquals("area-a", notification.area());
        verify(fixture.service()).markDelivered(111L);
    }

    @Test
    void usesTheCardJobIdWhenItsLinkPointsAtAnotherJob() {
        var fixture = fixture("111", "https://www.liepin.com/job/222.shtml", false);
        parse(fixture.worker(), "[" + JOB_B + "," + JOB_A + "]");

        submit(fixture.worker());

        var notification = fixture.recorded().getFirst();
        assertEquals("company-a", notification.company());
        assertEquals("https://www.liepin.com/job/111.shtml", notification.link());
        verify(fixture.service()).markDelivered(111L);
        verify(fixture.service(), never()).markDelivered(222L);
    }

    private record Fixture(Liepin worker, LiepinService service, Bot bot,
                           List<DeliveryNotification> recorded, List<JobProgressMessage> events) {}

    private Fixture fixture(String id, String link, boolean cardDetails) {
        Liepin worker = new Liepin();
        LiepinService service = mock(LiepinService.class);
        Bot bot = mock(Bot.class);
        ReflectionTestUtils.setField(worker, "liepinService", service);
        ReflectionTestUtils.setField(worker, "bot", bot);
        var recorded = new ArrayList<DeliveryNotification>();
        var events = new ArrayList<JobProgressMessage>();
        worker.setDeliveryCallback(recorded::add);
        worker.setActivityCallback(event -> { if (!"found".equals(event.getType())) events.add(event); });
        Page page = mock(Page.class);
        Locator empty = mock(Locator.class), cards = mock(Locator.class), card = mock(Locator.class);
        when(empty.first()).thenReturn(empty);
        when(page.locator(anyString())).thenReturn(empty);
        when(page.locator(Locators.JOB_CARDS)).thenReturn(cards);
        when(cards.count()).thenReturn(1);
        when(cards.nth(0)).thenReturn(card);
        when(card.locator(anyString())).thenReturn(empty);
        if (id != null) when(card.getAttribute("data-tlg-ext")).thenReturn("{\"jobId\":\"" + id + "\"}");
        Locator anchor = mock(Locator.class);
        when(card.locator("a[href*='/job/']")).thenReturn(anchor);
        when(anchor.count()).thenReturn(link == null ? 0 : 1);
        when(anchor.first()).thenReturn(anchor);
        when(anchor.getAttribute("href")).thenReturn(link);
        if (cardDetails) {
            cardField(card, ".job-title, [class*='job-title'], [class*='jobTitle']", "role-a");
            cardField(card, ".company-name, [class*='company-name'], [class*='companyName'], a[href*='/company/']", "company-a");
            cardField(card, ".job-salary, [class*='job-salary'], [class*='jobSalary']", "salary-a");
            cardField(card, ".job-dq-box, .job-area, [class*='job-area'], [class*='jobArea']", "area-a");
        }
        Locator buttons = mock(Locator.class), button = mock(Locator.class);
        when(card.locator("button.ant-btn.ant-btn-primary.ant-btn-round")).thenReturn(buttons);
        when(buttons.count()).thenReturn(1);
        when(buttons.nth(0)).thenReturn(button);
        when(button.isVisible()).thenReturn(true);
        when(button.textContent()).thenReturn("聊一聊");
        worker.setPage(page);
        return new Fixture(worker, service, bot, recorded, events);
    }

    private void cardField(Locator card, String selector, String text) {
        Locator field = mock(Locator.class);
        when(card.locator(selector)).thenReturn(field);
        when(field.count()).thenReturn(1);
        when(field.first()).thenReturn(field);
        when(field.innerText(any(Locator.InnerTextOptions.class))).thenReturn(text);
    }

    private void parse(Liepin worker, String entries) {
        ReflectionTestUtils.invokeMethod(worker, "parseAndPersistLiepinData", "{\"data\":{\"jobCardList\":" + entries + "}}");
    }

    private void submit(Liepin worker) {
        try (var ignored = mockStatic(PlaywrightUtil.class)) {
            ReflectionTestUtils.invokeMethod(worker, "submitJob");
        }
    }
}
