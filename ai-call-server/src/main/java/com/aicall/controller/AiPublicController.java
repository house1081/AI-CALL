package com.aicall.controller;

import com.aicall.common.Result;
import com.aicall.dto.*;
import com.aicall.entity.AiPrompt;
import com.aicall.service.OllamaChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 本地 AI 层接口（内网免登录）：话术 + Ollama 对话
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiPublicController {

    private final OllamaChatService ollamaChatService;

    @GetMapping("/active-prompt")
    public Result<AiPrompt> activePrompt() {
        return Result.ok(ollamaChatService.activePrompt());
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.ok(ollamaChatService.configInfo());
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(ollamaChatService.healthCheck());
    }

    /**
     * 电话对话：首轮 firstTurn=true 返回开场白；否则带 history + userText 调 qwen
     */
    @PostMapping("/chat")
    public Result<AiChatResponse> chat(@RequestBody AiChatRequest req) {
        return Result.ok(ollamaChatService.chat(req));
    }

    /**
     * 通话结束：从对话文本提取意向五字段
     */
    @PostMapping("/summarize")
    public Result<AiCallSummaryResponse> summarize(@RequestBody AiCallSummaryRequest req) {
        return Result.ok(ollamaChatService.summarize(req));
    }

    @GetMapping("/hangup-rules")
    public Result<Map<String, Object>> hangupRules() {
        return Result.ok(ollamaChatService.hangupRulesInfo());
    }
}
