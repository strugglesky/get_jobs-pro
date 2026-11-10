package com.getjobs.worker.zhilian;

import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.utils.Bot;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ZhilianNotificationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturesJobFieldsAndDoesNotClaimSuccessAfterAClick(boolean clickFails) throws Exception {
        ZhilianService service = mock(ZhilianService.class);
        Bot bot = mock(Bot.class);
        ZhiLian worker = new ZhiLian(service, bot);
        Page page = mock(Page.class);
        when(page.context()).thenReturn(mock(BrowserContext.class));
        Locator absent = mock(Locator.class);
        Locator cards = mock(Locator.class);
        Locator card = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(absent);
        when(page.locator("div.joblist-box__item")).thenReturn(cards);
        when(cards.count()).thenReturn(1);
        when(cards.nth(0)).thenReturn(card);
        when(card.locator(anyString())).thenReturn(absent);
        Locator title = mock(Locator.class);
        when(card.locator("a.jobinfo__name")).thenReturn(title);
        when(title.count()).thenReturn(1);
        when(title.textContent()).thenReturn("Java工程师");
        when(title.getAttribute("href")).thenReturn("https://jobs.zhaopin.com/CC123456789J001.html");
        Locator company = mock(Locator.class);
        when(card.locator("div.companyinfo__name")).thenReturn(company);
        when(company.count()).thenReturn(1);
        when(company.textContent()).thenReturn("示例公司");
        Locator button = mock(Locator.class);
        when(card.locator("button.collect-and-apply__btn")).thenReturn(button);
        when(button.count()).thenReturn(1);
        if (clickFails) doThrow(new PlaywrightException("click timed out")).when(button).click();
        worker.setPage(page);

        var method = ZhiLian.class.getDeclaredMethod("deliverCurrentPage", String.class);
        method.setAccessible(true);
        try (var ignored = mockStatic(PlaywrightUtil.class)) { method.invoke(worker, "Java"); }

        var notification = ArgumentCaptor.forClass(DeliveryNotification.class);
        verify(bot).notifyDelivery(notification.capture());
        assertEquals("示例公司", notification.getValue().company());
        assertEquals("Java工程师", notification.getValue().title());
        assertEquals("https://jobs.zhaopin.com/CC123456789J001.html", notification.getValue().link());
        assertEquals(clickFails ? DeliveryNotification.Status.UNCONFIRMED : DeliveryNotification.Status.SUBMITTED,
                notification.getValue().status());
    }
}
