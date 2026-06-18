package com.aicall.controller.admin;

import com.aicall.common.Result;
import com.aicall.dto.PrerecordFaqDto;
import com.aicall.dto.PrerecordMiningResultDto;
import com.aicall.dto.PrerecordStatsDto;
import com.aicall.service.prerecord.PrerecordAdminService;
import com.aicall.service.prerecord.PrerecordMiningService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/prerecord")
@RequiredArgsConstructor
public class AdminPrerecordController {

    private final PrerecordAdminService prerecordAdminService;
    private final PrerecordMiningService prerecordMiningService;

    @GetMapping("/stats")
    public Result<PrerecordStatsDto> stats() {
        return Result.ok(prerecordAdminService.stats());
    }

    @GetMapping("/faq/list")
    public Result<List<PrerecordFaqDto>> listFaqs(@RequestParam(required = false) String tier) {
        return Result.ok(prerecordAdminService.listFaqs(tier));
    }

    @PostMapping("/faq/save")
    public Result<PrerecordFaqDto> saveFaq(@RequestBody PrerecordFaqDto dto) {
        return Result.ok(prerecordAdminService.saveFaq(dto));
    }

    /** 新建 FAQ 并上传真人应答录音（multipart） */
    @PostMapping("/faq/upload")
    public Result<PrerecordFaqDto> uploadFaq(
            @RequestParam String questionDisplay,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keywords,
            @RequestParam(required = false) String answerText,
            @RequestParam(required = false, defaultValue = "high_freq") String tier,
            @RequestParam(required = false, defaultValue = "true") Boolean enabled,
            @RequestParam("file") MultipartFile file) throws IOException {
        PrerecordFaqDto dto = new PrerecordFaqDto();
        dto.setQuestionDisplay(questionDisplay);
        dto.setCategory(category);
        dto.setKeywords(keywords);
        dto.setAnswerText(answerText);
        dto.setTier(tier);
        dto.setEnabled(enabled);
        return Result.ok(prerecordAdminService.createFaqWithAudio(dto, file));
    }

    /** 为已有 FAQ 上传/替换真人应答录音 */
    @PostMapping("/faq/{id}/upload-audio")
    public Result<PrerecordFaqDto> uploadFaqAudio(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) throws IOException {
        return Result.ok(prerecordAdminService.uploadAnswerAudio(id, file));
    }

    @PostMapping("/faq/{id}/precache")
    public Result<Map<String, Object>> precacheFaq(@PathVariable Long id) {
        int n = prerecordAdminService.precacheFaqClips(id);
        return Result.ok(Map.of("precached", n));
    }

    @PostMapping("/clip/precache-global")
    public Result<Map<String, Object>> precacheGlobal() {
        int n = prerecordAdminService.precacheAllGlobalClips();
        return Result.ok(Map.of("precached", n));
    }

    @PostMapping("/mine")
    public Result<PrerecordMiningResultDto> mine(@RequestBody MineReq req) {
        int days = req != null && req.getDays() != null ? req.getDays() : 30;
        int limit = req != null && req.getLimit() != null ? req.getLimit() : 500;
        return Result.ok(prerecordMiningService.mineFromHistory(days, limit));
    }

    @PostMapping("/reseed")
    public Result<Void> reseed() {
        prerecordAdminService.reseedDefaults();
        return Result.ok();
    }

    @Data
    public static class MineReq {
        private Integer days;
        private Integer limit;
    }
}
