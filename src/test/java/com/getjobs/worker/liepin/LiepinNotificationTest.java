package com.getjobs.worker.liepin;

import com.getjobs.application.entity.LiepinEntity;
import com.getjobs.application.service.LiepinService;
import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.utils.Bot;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiepinNotificationTest {
    @ParameterizedTest
    @ValueSource(strings = {"聊一聊", "继续聊", "点击异常"})
    @SuppressWarnings("unchecked")
    void sendsOnlyForCurrentAttemptAndIncludesJobSnapshot(String action) throws Exception {
        Liepin worker = new Liepin();
        Bot bot = mock(Bot.class);
        ReflectionTestUtils.setField(worker, "bot", bot);
        ReflectionTestUtils.setField(worker, "liepinService", mock(LiepinService.class));
        LiepinEntity entity = new LiepinEntity();
        entity.setJobId(12345L);
        entity.setJobTitle("Java工程师");
        entity.setCompName("猎聘示例公司");
        entity.setJobLink("https://www.liepin.com/job/12345.shtml");
        ((List<LiepinEntity>) ReflectionTestUtils.getField(worker, "lastApiEntities")).add(entity);
        Page page = mock(Page.class);
        Locator empty = mock(Locator.class);
        Locator cards = mock(Locator.class);
        Locator card = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(empty);
        when(page.locator(Locators.JOB_CARDS)).thenReturn(cards);
        when(cards.count()).thenReturn(1);
        when(cards.nth(0)).thenReturn(card);
        when(card.locator(anyString())).thenReturn(empty);
        Locator buttons = mock(Locator.class);
        Locator button = mock(Locator.class);
        when(card.locator("button.ant-btn.ant-btn-primary.ant-btn-round")).thenReturn(buttons);
        when(buttons.count()).thenReturn(1);
        when(buttons.nth(0)).thenReturn(button);
        when(button.isVisible()).thenReturn(true);
        when(button.textContent()).thenReturn("点击异常".equals(action) ? "聊一聊" : action);
        if ("点击异常".equals(action)) doThrow(new PlaywrightException("click timed out")).when(button).click();
        worker.setPage(page);

        var method = Liepin.class.getDeclaredMethod("submitJob");
        method.setAccessible(true);
        try (var ignored = mockStatic(PlaywrightUtil.class)) { method.invoke(worker); }

        if ("继续聊".equals(action)) verifyNoInteractions(bot);
        else {
            var notification = ArgumentCaptor.forClass(DeliveryNotification.class);
            verify(bot).notifyDelivery(notification.capture());
            assertEquals("猎聘示例公司", notification.getValue().company());
            assertEquals("Java工程师", notification.getValue().title());
            assertEquals(entity.getJobLink(), notification.getValue().link());
            assertEquals("点击异常".equals(action) ? DeliveryNotification.Status.UNCONFIRMED : DeliveryNotification.Status.SUBMITTED,
                    notification.getValue().status());
        }
    }
}
