package com.getjobs.application.controller;

import com.getjobs.application.service.DeliveryProgressService;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.Job51JobService;
import com.getjobs.worker.service.LiepinJobService;
import com.getjobs.worker.service.ZhilianJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class DeliveryProgressController {
    private final DeliveryProgressService progress;
    private final BossJobService boss;
    private final LiepinJobService liepin;
    private final Job51JobService job51;
    private final ZhilianJobService zhilian;

    @GetMapping(value = "/delivery-progress/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String platform) {
        if (!java.util.Set.of("boss", "liepin", "51job", "zhilian").contains(platform)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        return progress.subscribe(platform);
    }

    @GetMapping("/delivery-status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam String platform) {
        Map<String, Object> status = switch (platform) {
            case "boss" -> boss.getStatus();
            case "liepin" -> liepin.getStatus();
            case "51job" -> job51.getStatus();
            case "zhilian" -> zhilian.getStatus();
            default -> null;
        };
        if (status == null) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "未知平台"));
        Map<String, Object> result = new java.util.HashMap<>(status);
        result.put("progress", progress.snapshot(platform));
        return ResponseEntity.ok(result);
    }
}
