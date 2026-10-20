package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.config.DashScopeProperties;
import com.aicall.config.FreeSwitchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商业级统一健康聚合：DB / Redis / FS ESL / DashScope / 外呼就绪。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommercialHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final FreeSwitchProperties freeSwitchProperties;
    private final FreeSwitchEslService freeSwitchEslService;
    private final OutboundVoiceReadinessService outboundVoiceReadinessService;
    private final DashScopeApiKeyResolver dashScopeApiKeyResolver;
    private final DashScopeProperties dashScopeProperties;
    private final FsOutboundSocketServer fsOutboundSocketServer;
    private final AiVoiceProperties aiVoiceProperties;

    public Map<String, Object> live() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("service", "ai-call-server");
        return m;
    }

    public Map<String, Object> ready() {
        Map<String, Object> checks = probeAll();
        boolean ok = Boolean.TRUE.equals(checks.get("overallOk"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", ok ? "READY" : "NOT_READY");
        m.put("ready", ok);
        m.put("checks", checks);
        return m;
    }

    public Map<String, Object> probeAll() {
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean dbOk = probeDb(checks);
        boolean redisOk = probeRedis(checks);
        boolean fsOk = probeFs(checks);
        boolean dashOk = probeDashScope(checks);
        boolean voiceOk = probeVoiceReady(checks);
        boolean overall = dbOk && redisOk && (!freeSwitchProperties.isEnabled() || fsOk) && dashOk && voiceOk;
        checks.put("overallOk", overall);
        return checks;
    }

    private boolean probeDb(Map<String, Object> checks) {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            boolean ok = one != null && one == 1;
            checks.put("mysql", ok ? "UP" : "DOWN");
            return ok;
        } catch (Exception e) {
            checks.put("mysql", "DOWN");
            checks.put("mysqlError", e.getMessage());
            return false;
        }
    }

    private boolean probeRedis(Map<String, Object> checks) {
        try {
            var factory = stringRedisTemplate.getConnectionFactory();
            if (factory == null) {
                checks.put("redis", "DOWN");
                return false;
            }
            try (var conn = factory.getConnection()) {
                String pong = conn.ping();
                boolean ok = pong != null;
                checks.put("redis", ok ? "UP" : "DOWN");
                return ok;
            }
        } catch (Exception e) {
            checks.put("redis", "DOWN");
            checks.put("redisError", e.getMessage());
            return false;
        }
    }

    private boolean probeFs(Map<String, Object> checks) {
        checks.put("freeswitchEnabled", freeSwitchProperties.isEnabled());
        checks.put("outboundMode", freeSwitchProperties.getOutboundMode());
        checks.put("fsSocketRunning", fsOutboundSocketServer.isRunning());
        if (!freeSwitchProperties.isEnabled()) {
            checks.put("esl", "SKIPPED");
            return true;
        }
        try {
            boolean ok = freeSwitchEslService.reachable();
            checks.put("esl", ok ? "UP" : "DOWN");
            checks.put("eslEndpoint", freeSwitchProperties.eslEndpoint());
            return ok;
        } catch (Exception e) {
            checks.put("esl", "DOWN");
            checks.put("eslError", e.getMessage());
            return false;
        }
    }

    private boolean probeDashScope(Map<String, Object> checks) {
        boolean configured = dashScopeApiKeyResolver.isConfigured();
        checks.put("dashscopeConfigured", configured);
        checks.put("dashscopeEnabled", dashScopeProperties.isEnabled());
        checks.put("dashscope", configured ? "UP" : "DOWN");
        return configured || !dashScopeProperties.isEnabled();
    }

    private boolean probeVoiceReady(Map<String, Object> checks) {
        boolean gate = aiVoiceProperties.isOutboundReadyGateEnabled();
        checks.put("outboundReadyGate", gate);
        boolean ready = outboundVoiceReadinessService.isReady();
        checks.put("outboundReady", ready);
        if (!ready) {
            checks.put("outboundPending", outboundVoiceReadinessService.pendingPartsPublic());
        }
        return !gate || ready;
    }
}
