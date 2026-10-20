package com.aicall.controller.tenant;

import com.aicall.common.Result;
import com.aicall.context.UserContext;
import com.aicall.entity.Line;
import com.aicall.entity.TenantAgent;
import com.aicall.service.AgentRoutingService;
import com.aicall.service.ClickCallService;
import com.aicall.service.TenantAgentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tenant/portal")
@RequiredArgsConstructor
public class TenantAgentController {

    private final TenantAgentService tenantAgentService;
    private final AgentRoutingService agentRoutingService;
    private final ClickCallService clickCallService;

    private Integer tenantId() {
        return UserContext.get().getTenantId();
    }

    @GetMapping("/agents")
    public Result<List<TenantAgent>> listAgents() {
        return Result.ok(tenantAgentService.listByTenant(tenantId()));
    }

    @PostMapping("/agents")
    public Result<TenantAgent> createAgent(@RequestBody TenantAgent body) {
        return Result.ok(tenantAgentService.create(tenantId(), body));
    }

    @PutMapping("/agents/{id}")
    public Result<TenantAgent> updateAgent(@PathVariable Integer id, @RequestBody TenantAgent body) {
        body.setId(id);
        return Result.ok(tenantAgentService.update(tenantId(), body));
    }

    @DeleteMapping("/agents/{id}")
    public Result<Void> deleteAgent(@PathVariable Integer id) {
        tenantAgentService.delete(tenantId(), id);
        return Result.ok(null);
    }

    @PostMapping("/agents/{id}/presence")
    public Result<TenantAgent> presence(@PathVariable Integer id, @RequestBody PresenceReq req) {
        return Result.ok(tenantAgentService.updatePresence(tenantId(), id, req.getPresence()));
    }

    @GetMapping("/outbound-lines")
    public Result<List<Line>> outboundLines() {
        return Result.ok(agentRoutingService.listEnabledLines());
    }

    @PostMapping("/click-call")
    public Result<Map<String, Object>> clickCall(@RequestBody ClickCallService.ClickCallRequest req) {
        return Result.ok(clickCallService.start(tenantId(), req));
    }

    @Data
    public static class PresenceReq {
        private String presence;
    }
}
