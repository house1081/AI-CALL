package com.aicall.service;

import com.aicall.entity.*;
import com.aicall.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@DependsOn("sensitiveWordSchemaInitializer")
@RequiredArgsConstructor
public class RiskControlService {

    private static final String KEY_BLACKLIST = "risk:blacklist:phones";
    private static final String KEY_BLACKLIST_LOADED = "risk:blacklist:loaded";
    private static final String KEY_CONFIG = "risk:config:json";
    private static final String KEY_TENANT_DAILY = "tenant:daily:calls:";
    private static final Duration CONFIG_TTL = Duration.ofMinutes(5);
    private static final Duration BLACKLIST_TTL = Duration.ofHours(24);

    private final RiskConfigMapper riskConfigMapper;
    private final RiskLogMapper riskLogMapper;
    private final CallRecordMapper callRecordMapper;
    private final GlobalBlacklistMapper globalBlacklistMapper;
    private final StringRedisTemplate redisTemplate;

    @PostConstruct
    public void warmCache() {
        try {
            config();
            reloadBlacklistCache();
        } catch (Exception e) {
            log.warn("风控缓存预热失败（请启动 Redis 127.0.0.1:6379 密码 root）: {}", e.getMessage());
        }
    }

    public RiskConfig config() {
        String cached = redisTemplate.opsForValue().get(KEY_CONFIG);
        if (StringUtils.hasText(cached)) {
            try {
                return parseConfig(cached);
            } catch (Exception e) {
                log.warn("风控配置缓存解析失败，回源数据库");
            }
        }
        RiskConfig risk = riskConfigMapper.selectById(1);
        if (risk == null) {
            risk = defaultRiskConfig();
        }
        try {
            redisTemplate.opsForValue().set(KEY_CONFIG, serializeConfig(risk), CONFIG_TTL);
        } catch (Exception e) {
            log.warn("写入风控配置缓存失败: {}", e.getMessage());
        }
        return risk;
    }

    public void evictConfigCache() {
        redisTemplate.delete(KEY_CONFIG);
    }

    public boolean isGlobalBlacklisted(String phone) {
        ensureBlacklistLoaded();
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(KEY_BLACKLIST, phone));
    }

    public void evictBlacklistCache() {
        redisTemplate.delete(KEY_BLACKLIST);
        redisTemplate.delete(KEY_BLACKLIST_LOADED);
    }

    private void ensureBlacklistLoaded() {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(KEY_BLACKLIST_LOADED))) {
            return;
        }
        reloadBlacklistCache();
    }

    public void reloadBlacklistCache() {
        redisTemplate.delete(KEY_BLACKLIST);
        List<GlobalBlacklist> list = globalBlacklistMapper.selectList(null);
        if (!list.isEmpty()) {
            String[] phones = list.stream().map(GlobalBlacklist::getPhone).toArray(String[]::new);
            redisTemplate.opsForSet().add(KEY_BLACKLIST, phones);
        }
        redisTemplate.expire(KEY_BLACKLIST, BLACKLIST_TTL);
        redisTemplate.opsForValue().set(KEY_BLACKLIST_LOADED, "1", BLACKLIST_TTL);
    }

    public boolean inCallWindow() {
        RiskConfig risk = config();
        LocalTime now = LocalTime.now();
        LocalTime start = LocalTime.parse(risk.getCallStartTime());
        LocalTime end = LocalTime.parse(risk.getCallEndTime());
        return !now.isBefore(start) && !now.isAfter(end);
    }

    public boolean isLineBlocked(Integer lineId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("risk:line:block:" + lineId));
    }

    public void blockLineShortCall(Integer lineId, String phone) {
        redisTemplate.opsForValue().set("risk:line:block:" + lineId, "1", Duration.ofMinutes(5));
        RiskLog log = new RiskLog();
        log.setLineId(lineId);
        log.setPhone(phone);
        log.setRiskType("short_call");
        log.setRemark("短通话拦截，线路暂停5分钟");
        riskLogMapper.insert(log);
    }

    public boolean isHighComplaintArea(String province) {
        if (!StringUtils.hasText(province)) {
            return false;
        }
        RiskConfig risk = config();
        if (!StringUtils.hasText(risk.getHighComplaintArea())) {
            return false;
        }
        Set<String> areas = Arrays.stream(risk.getHighComplaintArea().split(","))
                .map(String::trim).filter(StringUtils::hasText).collect(Collectors.toSet());
        return areas.contains(province.trim());
    }

    public boolean lineDailyLimitReached(Line line) {
        return line.getTodayCallCount() >= line.getDailyCallLimit();
    }

    public int tenantTodayCallCount(Integer tenantId) {
        String key = tenantDailyKey(tenantId);
        String val = redisTemplate.opsForValue().get(key);
        if (StringUtils.hasText(val)) {
            return Integer.parseInt(val);
        }
        int dbCount = countTenantCallsFromDb(tenantId);
        redisTemplate.opsForValue().set(key, String.valueOf(dbCount), secondsUntilMidnight());
        return dbCount;
    }

    public void incrementTenantDailyCallCount(Integer tenantId) {
        try {
            String key = tenantDailyKey(tenantId);
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1) {
                redisTemplate.expire(key, secondsUntilMidnight());
            }
        } catch (Exception e) {
            log.warn("商户日呼计数 Redis 失败 tenantId={}: {}", tenantId, e.getMessage());
        }
    }

    public boolean tenantDailyLimitReached(Tenant tenant) {
        Integer limit = tenant.getDailyCallLimit();
        if (limit == null || limit <= 0) {
            return false;
        }
        return tenantTodayCallCount(tenant.getId()) >= limit;
    }

    private RiskConfig defaultRiskConfig() {
        RiskConfig c = new RiskConfig();
        c.setId(1);
        c.setCallInterval(10);
        c.setShortCallLimit(15);
        c.setCallStartTime("08:00");
        c.setCallEndTime("22:00");
        c.setSensitiveMonitorEnabled(1);
        c.setHumanTransferEnabled(0);
        c.setHumanTransferPrompt("检测到需要人工协助，正在为您转接，请稍候。");
        return c;
    }

    public void afterCallRiskCheck(Line line, CallRecord record) {
        if (record.getCallStatus() != 1) {
            return;
        }
        RiskConfig risk = config();
        if (record.getCallDuration() < risk.getShortCallLimit()) {
            blockLineShortCall(line.getId(), record.getCustomerPhone());
        }
    }

    public List<RiskLog> recentLogs(int limit) {
        return riskLogMapper.selectList(new LambdaQueryWrapper<RiskLog>()
                .orderByDesc(RiskLog::getId).last("LIMIT " + limit));
    }

    private int countTenantCallsFromDb(Integer tenantId) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        Long count = callRecordMapper.selectCount(new LambdaQueryWrapper<CallRecord>()
                .eq(CallRecord::getTenantId, tenantId)
                .ge(CallRecord::getCallTime, start));
        return count.intValue();
    }

    private String tenantDailyKey(Integer tenantId) {
        return KEY_TENANT_DAILY + tenantId + ":" + LocalDate.now();
    }

    private Duration secondsUntilMidnight() {
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();
        long sec = Duration.between(LocalDateTime.now(), end).getSeconds();
        return Duration.ofSeconds(Math.max(sec, 60));
    }

    private String serializeConfig(RiskConfig c) {
        return c.getCallStartTime() + "|" + c.getCallEndTime() + "|" + c.getCallInterval()
                + "|" + c.getShortCallLimit() + "|" + (c.getHighComplaintArea() != null ? c.getHighComplaintArea() : "")
                + "|" + flag(c.getSensitiveMonitorEnabled(), 1)
                + "|" + flag(c.getHumanTransferEnabled(), 0)
                + "|" + (c.getHumanTransferDest() != null ? c.getHumanTransferDest() : "")
                + "|" + (c.getHumanTransferPrompt() != null ? c.getHumanTransferPrompt() : "");
    }

    private static int flag(Integer v, int def) {
        return v != null ? v : def;
    }

    private RiskConfig parseConfig(String s) {
        String[] p = s.split("\\|", -1);
        RiskConfig c = new RiskConfig();
        c.setId(1);
        c.setCallStartTime(p[0]);
        c.setCallEndTime(p[1]);
        c.setCallInterval(Integer.parseInt(p[2]));
        c.setShortCallLimit(Integer.parseInt(p[3]));
        c.setHighComplaintArea(p.length > 4 ? p[4] : "");
        if (p.length > 5 && StringUtils.hasText(p[5])) {
            c.setSensitiveMonitorEnabled(Integer.parseInt(p[5]));
        } else {
            c.setSensitiveMonitorEnabled(1);
        }
        if (p.length > 6 && StringUtils.hasText(p[6])) {
            c.setHumanTransferEnabled(Integer.parseInt(p[6]));
        } else {
            c.setHumanTransferEnabled(0);
        }
        if (p.length > 7) {
            c.setHumanTransferDest(p[7]);
        }
        if (p.length > 8) {
            c.setHumanTransferPrompt(p[8]);
        }
        return c;
    }
}
