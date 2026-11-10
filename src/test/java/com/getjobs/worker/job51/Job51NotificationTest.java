package com.getjobs.worker.job51;

import com.getjobs.application.entity.Job51Entity;
import com.getjobs.application.service.Job51Service;
import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.utils.Bot;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Job51NotificationTest {
    @ParameterizedTest
    @CsvSource({"1,0,SUCCESS", "0,0,UNCONFIRMED", "0,1,FAILED"})
    void submitsEachEligibleJobBeforeProcessingTheNextAndNotifiesItsOwnResult(int succeeded, int failed,
                                                                     DeliveryNotification.Status status) throws Exception {
        Job51Service service = mock(Job51Service.class);
        Bot bot = mock(Bot.class);
        Job51 worker = new Job51(service, bot);
        Page page = mock(Page.class);
        Locator absent = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(absent);
        Locator selections = mock(Locator.class);
        when(page.locator("div.ick")).thenReturn(selections);
        when(selections.count()).thenReturn(2);
        Locator titles = mock(Locator.class);
        when(page.locator("[class*='jname text-cut']")).thenReturn(titles);
        when(titles.count()).thenReturn(2);
        Locator companies = mock(Locator.class);
        when(page.locator("[class*='cname text-cut']")).thenReturn(companies);
        when(companies.count()).thenReturn(2);
        for (int i = 0; i < 2; i++) {
            Locator checkbox = mock(Locator.class);
            when(selections.nth(i)).thenReturn(checkbox);
            Locator title = mock(Locator.class);
            when(titles.nth(i)).thenReturn(title);
            when(title.textContent()).thenReturn("职位" + i);
            String link = "https://we.51job.com/pc/jobdetail?jobId=" + (10000 + i);
            when(title.evaluate(anyString())).thenReturn(link);
            Locator company = mock(Locator.class);
            when(companies.nth(i)).thenReturn(company);
            when(company.textContent()).thenReturn("公司" + i);
            Job51Entity snapshot = new Job51Entity();
            snapshot.setJobId(10000L + i);
            snapshot.setJobTitle("职位" + i);
            snapshot.setCompName("公司" + i);
            snapshot.setJobLink(link);
            when(service.findJobById(10000L + i)).thenReturn(snapshot);
        }
        Locator toolbar = mock(Locator.class);
        Locator buttons = mock(Locator.class);
        Locator submit = mock(Locator.class);
        when(page.locator("div.tabs_in")).thenReturn(toolbar);
        when(toolbar.locator("button.p_but")).thenReturn(buttons);
        when(buttons.count()).thenReturn(2);
        when(buttons.nth(1)).thenReturn(submit);
        Locator dialog = mock(Locator.class);
        when(page.locator(".el-dialog__body")).thenReturn(dialog);
        when(dialog.count()).thenReturn(1);
        when(dialog.first()).thenReturn(dialog);
        when(dialog.innerText()).thenReturn("投递成功" + succeeded + "个，未投递" + failed + "个");
        worker.setPage(page);

        var method = Job51.class.getDeclaredMethod("deliverCurrentPage");
        method.setAccessible(true);
        try (var ignored = mockStatic(PlaywrightUtil.class)) {
            method.invoke(worker);
        }
        var notifications = ArgumentCaptor.forClass(DeliveryNotification.class);
        verify(bot, times(2)).notifyDelivery(notifications.capture());
        var all = notifications.getAllValues();
        for (int i = 0; i < 2; i++) {
            assertEquals("前程无忧", all.get(i).platform());
            assertEquals("公司" + i, all.get(i).company());
            assertEquals("职位" + i, all.get(i).title());
            assertTrue(all.get(i).link().endsWith(String.valueOf(10000 + i)));
            assertEquals(status, all.get(i).status());
        }
        var order = inOrder(submit, titles.nth(1));
        order.verify(submit).click();
        order.verify(titles.nth(1)).textContent();
        order.verify(submit).click();
        if (status == DeliveryNotification.Status.SUCCESS) {
            verify(service).markDeliveredBatch(List.of(10000L));
            verify(service).markDeliveredBatch(List.of(10001L));
        } else verify(service, never()).markDeliveredBatch(anyList());
    }
}
