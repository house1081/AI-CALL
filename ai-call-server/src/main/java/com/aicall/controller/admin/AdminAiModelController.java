package com.aicall.controller.admin;

import com.aicall.common.BizException;
import com.aicall.common.Result;
import com.aicall.dto.AiModelConfigVO;
import com.aicall.entity.AiModelConfig;
import com.aicall.mapper.AiModelConfigMapper;
import com.aicall.service.AiModelConfigService;
import com.aicall.service.LlmInvokeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai-model")
@RequiredArgsConstructor
public class AdminAiModelController {

    private final AiModelConfigService aiModelConfigService;
    private final AiModelConfigMapper aiModelConfigMapper;
    private final LlmInvokeService llmInvokeService;

    @GetMapping("/list")
    public Result<List<AiModelConfigVO>> list() {
        return Result.ok(aiModelConfigService.listAll());
    }

    @GetMapping("/active")
    public Result<AiModelConfigVO> active() {
        AiModelConfig cfg = aiModelConfigService.requireActive();
        if (cfg.getId() != null && cfg.getId() > 0) {
            return Result.ok(aiModelConfigService.getVo(cfg.getId()));
        }
        AiModelConfigVO vo = new AiModelConfigVO();
        vo.setConfigName(cfg.getConfigName());
        vo.setProvider(cfg.getProvider());
        vo.setBaseUrl(cfg.getBaseUrl());
        vo.setModelName(cfg.getModelName());
        vo.setIsActive(1);
        return Result.ok(vo);
    }

    @GetMapping("/{id}")
    public Result<AiModelConfigVO> detail(@PathVariable Integer id) {
        return Result.ok(aiModelConfigService.getVo(id));
    }

    @PostMapping("/save")
    public Result<Void> save(@RequestBody AiModelConfig cfg) {
        aiModelConfigService.save(cfg);
        return Result.ok();
    }

    @PostMapping("/activate/{id}")
    public Result<Void> activate(@PathVariable Integer id) {
        aiModelConfigService.activate(id);
        return Result.ok();
    }

    @PostMapping("/test/{id}")
    public Result<Map<String, Object>> test(@PathVariable Integer id) {
        AiModelConfig cfg = aiModelConfigMapper.selectById(id);
        if (cfg == null) {
            throw new BizException("配置不存在");
        }
        return Result.ok(llmInvokeService.healthCheck(cfg));
    }
}
