package com.getjobs.application.controller;

import com.getjobs.application.service.DeliveryFilterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/delivery-filter")
public class DeliveryFilterController {
    private final DeliveryFilterService service;

    public DeliveryFilterController(DeliveryFilterService service) {
        this.service = service;
    }

    @GetMapping
    public DeliveryFilterService.Settings get() {
        return service.settings();
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody DeliveryFilterService.Settings settings) {
        if (settings.titleIncludes() == null || settings.titleExcludes() == null
                || settings.contentIncludes() == null || settings.contentExcludes() == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请提交全部四项规则，留空请使用空字符串"));
        }
        try {
            service.save(settings);
            return ResponseEntity.ok(Map.of("success", true, "message", "投递筛选配置已保存"));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "保存失败，请重试"));
        }
    }
}
