package com.aicall.controller;

import com.aicall.common.Result;
import com.aicall.entity.CallRecord;
import com.aicall.config.AiVoiceProperties;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.dto.AiChatRequest;
import com.aicall.dto.AiChatResponse;
import com.aicall.service.CallAiVoiceService;
import com.aicall.service.CallHangupService;
import com.aicall.service.CallSessionService;
import com.aicall.service.ForcedHangupService;
import com.aicall.service.FreeSwitchDialService;
import com.aicall.service.FreeSwitchEslService;
import com.aicall.service.FsOutboundSocketServer;
import com.aicall.service.VoiceDiagService;
import com.aicall.service.DashScopeApiKeyResolver;
import com.aicall.dto.HangupDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * FreeSWITCH / 网关层回调桩（无需 JWT，可选 X-Callback-Secret）
 */
@RestController
@RequestMapping("/api/callback/fs")
@RequiredArgsConstructor
public class FreeSwitchCallbackController {

    private final CallSessionService callSessionService;
    private final FreeSwitchDialService freeSwitchDialService;
    private final FreeSwitchProperties freeSwitchProperties;
    private final CallHangupService callHangupService;
    private final ForcedHangupService forcedHangupService;
    private final FreeSwitchEslService freeSwitchEslService;
    private final CallAiVoiceService callAiVoiceService;
    private final AiVoiceProperties aiVoiceProperties;
    private final VoiceDiagService voiceDiagService;
    private final FsOutboundSocketServer fsOutboundSocketServer;
    private final DashScopeApiKeyResolver dashScopeApiKeyResolver;

    /** 接通瞬间：创建进行中通话记录，返回 callRecordId 供实时扣费使用 */
    @PostMapping("/call-start")
    public Result<Map<String, Object>> callStart(@RequestBody CallSessionService.StartReq req) {
        CallRecord record = callSessionService.startSession(req);
        Map<String, Object> data = new HashMap<>();
        data.put("callRecordId", record.getId());
        data.put("tenantId", record.getTenantId());
        data.put("customerPhone", record.getCustomerPhone());
        return Result.ok(data);
    }

    /** 挂断/未接通结束：结算账单（支持已预扣差额） */
    @PostMapping("/call-end")
    public Result<Void> callEnd(@RequestBody CallSessionService.EndReq req) {
        callSessionService.endSession(req);
        return Result.ok();
    }

    /** 每秒校验通话时长（达 90 秒返回 shouldHangup） */
    @GetMapping("/call-tick")
    public Result<HangupDecision> callTick(@RequestParam Integer callRecordId) {
        return Result.ok(forcedHangupService.checkDurationOnly(callRecordId, null));
    }

    /**
     * 强制挂断：先播结束语后由 FS 调本接口结算（PRD /api/hangup 落地为 FS 回调路径）
     */
    @PostMapping("/hangup")
    public Result<Map<String, Object>> hangup(@RequestBody CallHangupService.HangupReq req) {
        return Result.ok(callHangupService.executeHangup(req));
    }

    /** 发起外呼（桩）：返回 fsUuid，实际拨号由 FS 或模拟任务完成 */
    @PostMapping("/originate")
    public Result<FreeSwitchDialService.DialResult> originate(@RequestBody FreeSwitchDialService.DialRequest req) {
        return Result.ok(freeSwitchDialService.originate(req));
    }

    /**
     * 语音轮次：FunASR 识别文本 → 大模型 → ESL 播报（FS 脚本循环调用）
     */
    @PostMapping("/voice-turn")
    public Result<AiChatResponse> voiceTurn(@RequestBody AiChatRequest req) {
        return Result.ok(callAiVoiceService.voiceTurn(req));
    }

    /** 测试播报：FS 或联调时传入在通话中的 uuid */
    @PostMapping("/play-test")
    public Result<Map<String, Object>> playTest(@RequestBody Map<String, String> body) {
        String fsUuid = body.get("fsUuid");
        String text = body.getOrDefault("text", "您好，这是语音测试。");
        callAiVoiceService.playText(fsUuid, text);
        return Result.ok(Map.of("fsUuid", fsUuid, "text", text, "hint", "已下发 ESL 播报，请查 Java 日志"));
    }

    /**
     * 离线语音诊断：不拨号、不走 SIP，仅测 SAPI 合成 + 共享目录 + FS 能否读到 wav。
     * 例：GET /api/callback/fs/voice-diag?text=您好测试
     */
    @GetMapping("/voice-diag")
    public Result<Map<String, Object>> voiceDiag(
            @RequestParam(required = false) String text) {
        return Result.ok(voiceDiagService.run(text));
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "ok");
        m.put("service", "ai-call-callback");
        m.put("enabled", freeSwitchProperties.isEnabled());
        m.put("sipEndpoint", freeSwitchProperties.sipEndpoint());
        m.put("eslEndpoint", freeSwitchProperties.eslEndpoint());
        m.put("gateway", freeSwitchProperties.getGateway());
        m.put("callbackBaseUrl", freeSwitchProperties.getCallbackBaseUrl());
        m.put("aiVoiceEnabled", aiVoiceProperties.isEnabled());
        m.put("aiVoiceTtsMode", aiVoiceProperties.getTtsMode());
        m.put("aiVoicePlaybackBase", aiVoiceProperties.getPlaybackBaseUrl());
        m.put("aiVoiceAutoPlayOnAnswer", aiVoiceProperties.isAutoPlayAfterOriginate());
        m.put("outboundMode", freeSwitchProperties.getOutboundMode());
        m.put("fsSocketEnabled", aiVoiceProperties.isFsSocketEnabled());
        m.put("fsSocketPort", aiVoiceProperties.getFsSocketPort());
        m.put("fsSocketRunning", fsOutboundSocketServer.isRunning());
        m.put("asrHttpUrl", aiVoiceProperties.getAsrHttpUrl());
        m.put("asrDashScopeConfigured", dashScopeApiKeyResolver.isConfigured());
        if (freeSwitchProperties.isEnabled()) {
            m.put("eslReachable", freeSwitchEslService.reachable());
        }
        return Result.ok(m);
    }
}
