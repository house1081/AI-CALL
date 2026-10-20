package com.aicall.service;

import com.aicall.entity.Line;
import com.aicall.entity.TenantAgent;
import com.aicall.mapper.LineMapper;
import com.aicall.mapper.TenantAgentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 坐席路由：选空闲坐席、拼 FS dest、占用/释放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRoutingService {

    private final TenantAgentMapper tenantAgentMapper;
    private final LineMapper lineMapper;
    private final LineOutboundRouteService lineOutboundRouteService;

    public TenantAgent pickAvailable(Integer tenantId) {
        if (tenantId == null) {
            return null;
        }
        List<TenantAgent> list = tenantAgentMapper.selectList(new LambdaQueryWrapper<TenantAgent>()
                .eq(TenantAgent::getTenantId, tenantId)
                .eq(TenantAgent::getStatus, 1)
                .eq(TenantAgent::getPresence, TenantAgentService.PRESENCE_AVAILABLE)
                .orderByAsc(TenantAgent::getSortOrder)
                .orderByAsc(TenantAgent::getId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 解析可拨打的 FS 目标。手机坐席按 lineId（或默认启用线路）拼 sofia/gateway。
     */
    public String resolveFsDialDest(TenantAgent agent, Integer fallbackLineId) {
        if (agent == null || !StringUtils.hasText(agent.getFsDest())) {
            return null;
        }
        if (TenantAgentService.DEST_EXT.equalsIgnoreCase(agent.getDestType())) {
            return TenantAgentService.normalizeExtDest(agent.getFsDest());
        }
        String phone = agent.getFsDest().trim();
        Integer lineId = agent.getLineId() != null ? agent.getLineId() : fallbackLineId;
        if (lineId == null) {
            Line line = firstEnabledLine();
            if (line == null) {
                throw new com.aicall.common.BizException("手机坐席外呼需要可用线路");
            }
            lineId = line.getId();
        }
        LineOutboundRouteService.LineRoute route = lineOutboundRouteService.requireRoute(lineId);
        return "sofia/gateway/" + route.getGatewayName() + "/" + phone;
    }

    public Line firstEnabledLine() {
        List<Line> lines = lineMapper.selectList(new LambdaQueryWrapper<Line>()
                .eq(Line::getStatus, 1)
                .orderByAsc(Line::getId)
                .last("LIMIT 1"));
        return lines.isEmpty() ? null : lines.get(0);
    }

    public List<Line> listEnabledLines() {
        return lineMapper.selectList(new LambdaQueryWrapper<Line>()
                .eq(Line::getStatus, 1)
                .orderByAsc(Line::getId));
    }

    public boolean markBusy(Integer agentId) {
        if (agentId == null) {
            return false;
        }
        TenantAgent upd = new TenantAgent();
        upd.setId(agentId);
        upd.setPresence(TenantAgentService.PRESENCE_BUSY);
        upd.setUpdateTime(LocalDateTime.now());
        return tenantAgentMapper.updateById(upd) > 0;
    }

    public boolean markAvailable(Integer agentId) {
        if (agentId == null) {
            return false;
        }
        TenantAgent current = tenantAgentMapper.selectById(agentId);
        if (current == null) {
            return false;
        }
        // 签出的不要自动拉回 available
        if (TenantAgentService.PRESENCE_OFFLINE.equals(current.getPresence())
                && !TenantAgentService.PRESENCE_BUSY.equals(current.getPresence())) {
            return true;
        }
        TenantAgent upd = new TenantAgent();
        upd.setId(agentId);
        upd.setPresence(TenantAgentService.PRESENCE_AVAILABLE);
        upd.setUpdateTime(LocalDateTime.now());
        return tenantAgentMapper.updateById(upd) > 0;
    }

    public void releaseAfterCall(Integer agentId) {
        if (agentId == null) {
            return;
        }
        TenantAgent current = tenantAgentMapper.selectById(agentId);
        if (current == null) {
            log.warn("[坐席] 释放失败：坐席不存在 agentId={}", agentId);
            return;
        }
        String before = current.getPresence();
        if (!TenantAgentService.PRESENCE_BUSY.equals(before)) {
            log.info("[坐席] 释放跳过 agentId={} presence={}", agentId, before);
            return;
        }
        int n = tenantAgentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TenantAgent>()
                .eq(TenantAgent::getId, agentId)
                .eq(TenantAgent::getPresence, TenantAgentService.PRESENCE_BUSY)
                .set(TenantAgent::getPresence, TenantAgentService.PRESENCE_AVAILABLE)
                .set(TenantAgent::getUpdateTime, LocalDateTime.now()));
        log.info("[坐席] 通话结束释放 busy->available agentId={} rows={}", agentId, n);
    }
}
