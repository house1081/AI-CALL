package com.aicall.controller;

import com.aicall.common.Result;
import com.aicall.config.PublicVoiceTrainingGuard;
import com.aicall.dto.DialogVoiceTrainStartResponse;
import com.aicall.dto.DialogVoiceTrainTurnResponse;
import com.aicall.entity.DialogKnowledgeBase;
import com.aicall.service.DialogKnowledgeBaseService;
import com.aicall.service.DialogVoiceTrainingService;
import com.aicall.service.OllamaChatService;
import com.aicall.service.VoiceRuntimeSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话训练公开接口：免登录，供手机浏览器独立打开 /h5/training 使用。
 */
@RestController
@RequestMapping("/api/public/voice-training")
@RequiredArgsConstructor
public class PublicVoiceTrainingController {

    private final DialogVoiceTrainingService dialogVoiceTrainingService;
    private final DialogKnowledgeBaseService dialogKnowledgeBaseService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final OllamaChatService ollamaChatService;
    private final PublicVoiceTrainingGuard publicVoiceTrainingGuard;

    @GetMapping("/knowledge-bases")
    public Result<List<DialogKnowledgeBase>> knowledgeBases() {
        return Result.ok(dialogKnowledgeBaseService.list(null, 1));
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> cfg = new LinkedHashMap<>(ollamaChatService.configInfo());
        cfg.remove("baseUrl");
        cfg.put("outboundDialogMode", voiceRuntimeSettingsService.getForAdmin().getOutboundDialogMode());
        return Result.ok(cfg);
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(ollamaChatService.healthCheck());
    }

    @PostMapping("/start")
    public Result<DialogVoiceTrainStartResponse> start(
            HttpServletRequest request,
            @RequestParam(required = false) Integer kbId) {
        publicVoiceTrainingGuard.checkStart(request);
        return Result.ok(dialogVoiceTrainingService.start(kbId));
    }

    @PostMapping("/turn")
    public Result<DialogVoiceTrainTurnResponse> turn(
            HttpServletRequest request,
            @RequestParam String sessionId,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(required = false) Boolean businessProbeThisTurn) throws Exception {
        publicVoiceTrainingGuard.checkTurn(request);
        return Result.ok(dialogVoiceTrainingService.voiceTurn(sessionId, audio, businessProbeThisTurn));
    }

    @PostMapping("/text-turn")
    public Result<DialogVoiceTrainTurnResponse> textTurn(@RequestBody Map<String, Object> body)
            throws Exception {
        return Result.ok(dialogVoiceTrainingService.textTurn(
                (String) body.get("sessionId"),
                (String) body.get("userText"),
                parseBoolean(body.get("businessProbeThisTurn"))));
    }

    @PostMapping("/end")
    public Result<Void> end(@RequestParam String sessionId) {
        dialogVoiceTrainingService.endSession(sessionId);
        return Result.ok();
    }

    private static Boolean parseBoolean(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString());
    }
}
