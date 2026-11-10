package com.getjobs.worker.utils;

import com.microsoft.playwright.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobDescriptionReaderTest {
    @ParameterizedTest
    @EnumSource(value = Platform.class, names = {"LIEPIN", "JOB51", "ZHILIAN"})
    void readsDescriptionAndAlwaysClosesDetailTabIncludingOnReadFailure(Platform platform) {
        String domain = switch (platform) {
            case LIEPIN -> "www.liepin.com";
            case JOB51 -> "we.51job.com";
            default -> "jobs.zhaopin.com";
        };
        Page source = mock(Page.class);
        Page detail = mock(Page.class);
        BrowserContext context = mock(BrowserContext.class);
        when(source.context()).thenReturn(context);
        when(context.newPage()).thenReturn(detail);
        when(source.url()).thenReturn("https://" + domain + "/search");
        when(detail.url()).thenReturn("https://" + domain + "/job/123");
        Locator description = mock(Locator.class);
        when(detail.locator(anyString())).thenReturn(description);
        when(description.first()).thenReturn(description);
        when(description.innerText(any(Locator.InnerTextOptions.class))).thenReturn("负责 Spring Boot 开发");
        assertEquals("负责 Spring Boot 开发", JobDescriptionReader.read(source, platform, "/job/123"));
        verify(detail).navigate(eq("https://" + domain + "/job/123"), any(Page.NavigateOptions.class));
        verify(detail).close();
        verify(detail, never()).locator("body");
        when(description.innerText(any(Locator.InnerTextOptions.class))).thenThrow(new PlaywrightException("description unavailable"));
        assertEquals("", JobDescriptionReader.read(source, platform, "/job/123"));
        verify(detail, times(2)).close();
    }

    @Test
    void missingOrUnrelatedLinkDoesNotOpenATab() {
        Page source = mock(Page.class);
        when(source.url()).thenReturn("https://www.liepin.com/search");
        assertEquals("", JobDescriptionReader.read(source, Platform.LIEPIN, null));
        assertEquals("", JobDescriptionReader.read(source, Platform.LIEPIN, "https://example.com/job/1"));
        verify(source, never()).context();
    }
}
