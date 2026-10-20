package com.aicall.controller;

import com.aicall.common.Result;
import com.aicall.service.CommercialHealthService;
import com.aicall.service.CommercialSlaMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商业级运维探活：K8s/脚本可打 /api/ops/*（免 JWT）；管理详情走 /api/admin/ops/*。
 */
@RestController
@RequiredArgsConstructor
public class CommercialOpsController {

    private final CommercialHealthService commercialHealthService;
    private final CommercialSlaMetricsService commercialSlaMetricsService;

    /** 存活探活：进程起来即 UP */
    @GetMapping("/api/ops/live")
    public ResponseEntity<Map<String, Object>> live() {
        return ResponseEntity.ok(commercialHealthService.live());
    }

    /** 就绪探活：DB/Redis/FS/模型/语音预热 */
    @GetMapping("/api/ops/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> body = commercialHealthService.ready();
        boolean ok = Boolean.TRUE.equals(body.get("ready"));
        return ResponseEntity.status(ok ? 200 : 503).body(body);
    }

    @GetMapping("/api/admin/ops/health")
    public Result<Map<String, Object>> adminHealth() {
        return Result.ok(commercialHealthService.ready());
    }

    @GetMapping("/api/admin/ops/sla")
    public Result<Map<String, Object>> adminSla() {
        return Result.ok(commercialSlaMetricsService.snapshot());
    }
}
