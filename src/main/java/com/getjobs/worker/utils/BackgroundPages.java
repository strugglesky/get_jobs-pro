package com.getjobs.worker.utils;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;

import java.util.UUID;

/** 在项目使用的 Chrome 中后台打开任务详情页，避免每个岗位抢占用户当前标签页。 */
public final class BackgroundPages {
    private BackgroundPages() {}

    public static Page open(Page source) {
        BrowserContext context = source.context();
        CDPSession session;
        try {
            session = context.newCDPSession(source);
        } catch (RuntimeException unavailable) {
            return context.newPage();
        }
        if (session == null) {
            return context.newPage();
        }
        String marker = "about:blank#getjobs-" + UUID.randomUUID();
        JsonObject options = new JsonObject();
        options.addProperty("url", marker);
        options.addProperty("background", true);
        String[] targetId = {null};
        try {
            // 同时兼容持久化上下文和独立上下文，避免把新页建到其他用户会话。
            JsonObject info = session.send("Target.getTargetInfo").getAsJsonObject("targetInfo");
            if (info.has("browserContextId")) {
                options.add("browserContextId", info.get("browserContextId"));
            }
            return context.waitForPage(new BrowserContext.WaitForPageOptions()
                            .setTimeout(10000).setPredicate(candidate -> marker.equals(candidate.url())),
                    () -> targetId[0] = session.send("Target.createTarget", options).get("targetId").getAsString());
        } catch (RuntimeException error) {
            if (targetId[0] != null) {
                JsonObject target = new JsonObject();
                target.addProperty("targetId", targetId[0]);
                try { session.send("Target.closeTarget", target); } catch (RuntimeException ignored) {}
            }
            // 非 Chromium 后端、旧版驱动或受限 CDP 环境不支持后台创建时，
            // 保持原有行为，确保详情读取不会影响投递流程。
            return context.newPage();
        } finally {
            try { session.detach(); } catch (RuntimeException ignored) {}
        }
    }
}
