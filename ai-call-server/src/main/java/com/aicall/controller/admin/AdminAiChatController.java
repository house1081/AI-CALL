package com.aicall.controller.admin;

import com.aicall.common.Result;
import com.aicall.dto.AiChatRequest;
import com.aicall.dto.AiChatResponse;
import com.aicall.dto.AiCallSummaryRequest;
import com.aicall.dto.AiCallSummaryResponse;
import com.aicall.service.OllamaChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 管理后台：AI 对话训练（与线上 /api/ai/chat 共用模型配置与话术） */
@RestController
@RequestMapping("/api/admin/ai-chat")
@RequiredArgsConstructor
public class AdminAiChatController {

    private final OllamaChatService ollamaChatService;

    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.ok(ollamaChatService.configInfo());
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(ollamaChatService.healthCheck());
    }

    @PostMapping("/chat")
    public Result<AiChatResponse> chat(@RequestBody AiChatRequest req) {
        return Result.ok(ollamaChatService.chat(req));
    }

    @PostMapping("/summarize")
    public Result<AiCallSummaryResponse> summarize(@RequestBody AiCallSummaryRequest req) {
        return Result.ok(ollamaChatService.summarize(req));
    }
}
