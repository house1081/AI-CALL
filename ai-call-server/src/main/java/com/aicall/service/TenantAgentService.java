package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.entity.TenantAgent;
import com.aicall.mapper.TenantAgentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TenantAgentService {

    public static final String DEST_EXT = "ext";
    public static final String DEST_MOBILE = "mobile";
    public static final String PRESENCE_OFFLINE = "offline";
    public static final String PRESENCE_AVAILABLE = "available";
    public static final String PRESENCE_BUSY = "busy";

    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");

    private final TenantAgentMapper tenantAgentMapper;

    public List<TenantAgent> listByTenant(Integer tenantId) {
        return tenantAgentMapper.selectList(new LambdaQueryWrapper<TenantAgent>()
                .eq(TenantAgent::getTenantId, tenantId)
                .orderByAsc(TenantAgent::getSortOrder)
                .orderByAsc(TenantAgent::getId));
    }

    public TenantAgent requireOwned(Integer tenantId, Integer agentId) {
        TenantAgent agent = tenantAgentMapper.selectById(agentId);
        if (agent == null || !tenantId.equals(agent.getTenantId())) {
            throw new BizException("坐席不存在");
        }
        return agent;
    }

    public TenantAgent create(Integer tenantId, TenantAgent input) {
        TenantAgent row = normalizeForSave(tenantId, input, null);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        if (!StringUtils.hasText(row.getPresence())) {
            row.setPresence(PRESENCE_OFFLINE);
        }
        if (row.getStatus() == null) {
            row.setStatus(1);
        }
        if (row.getSortOrder() == null) {
            row.setSortOrder(0);
        }
        tenantAgentMapper.insert(row);
        return row;
    }

    public TenantAgent update(Integer tenantId, TenantAgent input) {
        if (input.getId() == null) {
            throw new BizException("坐席ID不能为空");
        }
        TenantAgent old = requireOwned(tenantId, input.getId());
        TenantAgent row = normalizeForSave(tenantId, input, old);
        row.setId(old.getId());
        row.setUpdateTime(LocalDateTime.now());
        // 忙线中不允许通过编辑改 presence，避免抢状态
        if (PRESENCE_BUSY.equals(old.getPresence())) {
            row.setPresence(PRESENCE_BUSY);
        }
        tenantAgentMapper.updateById(row);
        return tenantAgentMapper.selectById(old.getId());
    }

    public void delete(Integer tenantId, Integer agentId) {
        TenantAgent old = requireOwned(tenantId, agentId);
        if (PRESENCE_BUSY.equals(old.getPresence())) {
            throw new BizException("坐席通话中，无法删除");
        }
        tenantAgentMapper.deleteById(agentId);
    }

    public TenantAgent updatePresence(Integer tenantId, Integer agentId, String presence) {
        TenantAgent old = requireOwned(tenantId, agentId);
        String p = presence == null ? "" : presence.trim().toLowerCase(Locale.ROOT);
        if (!PRESENCE_OFFLINE.equals(p) && !PRESENCE_AVAILABLE.equals(p)) {
            throw new BizException("presence 仅支持 offline / available");
        }
        if (PRESENCE_BUSY.equals(old.getPresence()) && PRESENCE_AVAILABLE.equals(p)) {
            // 允许强制签入空闲（异常恢复）
        } else if (PRESENCE_BUSY.equals(old.getPresence())) {
            throw new BizException("坐席通话中，请稍后再改状态");
        }
        TenantAgent upd = new TenantAgent();
        upd.setId(old.getId());
        upd.setPresence(p);
        upd.setUpdateTime(LocalDateTime.now());
        tenantAgentMapper.updateById(upd);
        return tenantAgentMapper.selectById(old.getId());
    }

    private TenantAgent normalizeForSave(Integer tenantId, TenantAgent input, TenantAgent old) {
        if (!StringUtils.hasText(input.getName())) {
            throw new BizException("请填写坐席名称");
        }
        String destType = StringUtils.hasText(input.getDestType())
                ? input.getDestType().trim().toLowerCase(Locale.ROOT)
                : DEST_EXT;
        if (!DEST_EXT.equals(destType) && !DEST_MOBILE.equals(destType)) {
            throw new BizException("destType 仅支持 ext / mobile");
        }
        String raw = StringUtils.hasText(input.getFsDest()) ? input.getFsDest().trim()
                : (input.getMobile() != null ? input.getMobile().trim() : "");
        if (!StringUtils.hasText(raw)) {
            throw new BizException("请填写分机或手机号");
        }
        TenantAgent row = new TenantAgent();
        row.setTenantId(tenantId);
        row.setName(input.getName().trim());
        row.setDestType(destType);
        row.setLineId(input.getLineId());
        row.setStatus(input.getStatus() != null ? input.getStatus()
                : (old != null ? old.getStatus() : 1));
        row.setPresence(StringUtils.hasText(input.getPresence()) ? input.getPresence().trim()
                : (old != null ? old.getPresence() : PRESENCE_OFFLINE));
        row.setSortOrder(input.getSortOrder() != null ? input.getSortOrder()
                : (old != null ? old.getSortOrder() : 0));
        if (DEST_MOBILE.equals(destType)) {
            String phone = raw.replaceAll("\\D", "");
            if (phone.startsWith("86") && phone.length() == 13) {
                phone = phone.substring(2);
            }
            if (!PHONE.matcher(phone).matches()) {
                throw new BizException("手机坐席须为11位手机号");
            }
            row.setFsDest(phone);
            row.setMobile(phone);
        } else {
            String dest = normalizeExtDest(raw);
            row.setFsDest(dest);
            row.setMobile(StringUtils.hasText(input.getMobile()) ? input.getMobile().trim() : null);
        }
        return row;
    }

    public static String normalizeExtDest(String raw) {
        String d = raw.trim();
        if (d.contains("/") || d.contains("@") || d.startsWith("sofia/") || d.startsWith("user/")) {
            return d;
        }
        return "user/" + d;
    }
}
