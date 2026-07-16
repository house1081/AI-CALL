package com.aicall.controller.admin;

import com.aicall.common.BizException;
import com.aicall.common.Result;
import com.aicall.context.UserContext;
import com.aicall.dto.CosyVoiceVoiceEnrollRequest;
import com.aicall.dto.CosyVoiceVoiceItemDto;
import com.aicall.dto.FixedVoicePrecacheStatusDto;
import com.aicall.dto.VoiceRuntimeConfigDto;
import com.aicall.entity.*;
import com.aicall.mapper.*;
import com.aicall.service.AiPromptRecordingService;
import com.aicall.service.BillingService;
import com.aicall.service.CosyVoiceVoiceEnrollmentService;
import com.aicall.service.FixedPhraseVoiceAdminService;
import com.aicall.service.OpeningVoiceCacheService;
import com.aicall.service.EndingVoiceCacheService;
import com.aicall.service.RiskControlService;
import com.aicall.service.SensitiveWordMonitorService;
import com.aicall.service.VoiceRuntimeSettingsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminConfigController {

    private final PriceConfigMapper priceConfigMapper;
    private final PriceChangeLogMapper priceChangeLogMapper;
    private final RiskConfigMapper riskConfigMapper;
    private final GlobalBlacklistMapper globalBlacklistMapper;
    private final AiPromptMapper aiPromptMapper;
    private final OpeningVoiceCacheService openingVoiceCacheService;
    private final EndingVoiceCacheService endingVoiceCacheService;
    private final FixedPhraseVoiceAdminService fixedPhraseVoiceAdminService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final AiPromptRecordingService aiPromptRecordingService;
    private final CosyVoiceVoiceEnrollmentService cosyVoiceVoiceEnrollmentService;
    private final com.aicall.config.AiVoiceProperties aiVoiceProperties;
    private final BillingService billingService;
    private final RiskControlService riskControlService;
    private final SensitiveWordMapper sensitiveWordMapper;
    private final SensitiveWordMonitorService sensitiveWordMonitorService;

    @GetMapping("/price/list")
    public Result<List<PriceConfig>> priceList() {
        return Result.ok(priceConfigMapper.selectList(null));
    }

    @PostMapping("/price/save")
    public Result<Void> priceSave(@RequestBody PriceConfig cfg) {
        billingService.validateSellPrice(cfg.getDefaultSellPrice());
        PriceConfig old = priceConfigMapper.selectById(cfg.getId());
        PriceChangeLog log = new PriceChangeLog();
        log.setTargetType(1);
        log.setTargetId(cfg.getPriceType());
        log.setOldPrice(old.getDefaultSellPrice());
        log.setNewPrice(cfg.getDefaultSellPrice());
        log.setOperator(UserContext.get().getUsername());
        priceChangeLogMapper.insert(log);
        priceConfigMapper.updateById(cfg);
        return Result.ok();
    }

    @GetMapping("/price/log")
    public Result<List<PriceChangeLog>> priceLog() {
        return Result.ok(priceChangeLogMapper.selectList(
                new LambdaQueryWrapper<PriceChangeLog>().orderByDesc(PriceChangeLog::getId).last("LIMIT 50")));
    }

    @GetMapping("/risk")
    public Result<RiskConfig> risk() {
        return Result.ok(riskConfigMapper.selectById(1));
    }

    @PostMapping("/risk/save")
    public Result<Void> riskSave(@RequestBody RiskConfig cfg) {
        if (cfg.getCallInterval() < 10 || cfg.getCallInterval() > 60) {
            throw new BizException("拨打间隔须在10-60秒之间");
        }
        if (cfg.getShortCallLimit() < 5 || cfg.getShortCallLimit() > 60) {
            throw new BizException("短通话阈值须在5-60秒之间");
        }
        if (Integer.valueOf(1).equals(cfg.getHumanTransferEnabled())
                && (cfg.getHumanTransferDest() == null || cfg.getHumanTransferDest().isBlank())) {
            throw new BizException("启用转人工时须填写 FS 转接目标");
        }
        cfg.setId(1);
        riskConfigMapper.updateById(cfg);
        riskControlService.evictConfigCache();
        return Result.ok();
    }

    @GetMapping("/sensitive-word/list")
    public Result<List<SensitiveWord>> sensitiveWordList() {
        return Result.ok(sensitiveWordMapper.selectList(
                new LambdaQueryWrapper<SensitiveWord>().orderByDesc(SensitiveWord::getId)));
    }

    @PostMapping("/sensitive-word/save")
    public Result<Void> sensitiveWordSave(@RequestBody SensitiveWord word) {
        if (word == null || word.getWord() == null || word.getWord().isBlank()) {
            throw new BizException("词条不能为空");
        }
        word.setWord(word.getWord().trim());
        if (word.getWord().length() > 64) {
            throw new BizException("词条长度不能超过64字");
        }
        if (word.getWordType() == null) {
            word.setWordType(SensitiveWordMonitorService.TYPE_SENSITIVE);
        }
        if (word.getStatus() == null) {
            word.setStatus(1);
        }
        long dup = sensitiveWordMapper.selectCount(
                new LambdaQueryWrapper<SensitiveWord>().eq(SensitiveWord::getWord, word.getWord()));
        if (word.getId() != null) {
            SensitiveWord old = sensitiveWordMapper.selectById(word.getId());
            if (old == null) {
                throw new BizException("词条不存在");
            }
            if (dup > 0 && !old.getWord().equals(word.getWord())) {
                throw new BizException("该词条已存在");
            }
            sensitiveWordMapper.updateById(word);
        } else {
            if (dup > 0) {
                throw new BizException("该词条已存在");
            }
            sensitiveWordMapper.insert(word);
        }
        sensitiveWordMonitorService.reload();
        return Result.ok();
    }

    @DeleteMapping("/sensitive-word/{id}")
    public Result<Void> sensitiveWordDelete(@PathVariable Integer id) {
        sensitiveWordMapper.deleteById(id);
        sensitiveWordMonitorService.reload();
        return Result.ok();
    }

    @GetMapping("/blacklist")
    public Result<List<GlobalBlacklist>> blacklist() {
        return Result.ok(globalBlacklistMapper.selectList(
                new LambdaQueryWrapper<GlobalBlacklist>().orderByDesc(GlobalBlacklist::getId)));
    }

    @PostMapping("/blacklist/add")
    public Result<Void> blacklistAdd(@RequestBody GlobalBlacklist b) {
        long c = globalBlacklistMapper.selectCount(
                new LambdaQueryWrapper<GlobalBlacklist>().eq(GlobalBlacklist::getPhone, b.getPhone()));
        if (c > 0) {
            throw new BizException("手机号已在黑名单");
        }
        globalBlacklistMapper.insert(b);
        riskControlService.evictBlacklistCache();
        return Result.ok();
    }

    @DeleteMapping("/blacklist/{id}")
    public Result<Void> blacklistDel(@PathVariable Integer id) {
        globalBlacklistMapper.deleteById(id);
        riskControlService.evictBlacklistCache();
        return Result.ok();
    }

    @GetMapping("/ai-prompt/list")
    public Result<List<AiPrompt>> aiPromptList() {
        return Result.ok(aiPromptMapper.selectList(
                new LambdaQueryWrapper<AiPrompt>().orderByDesc(AiPrompt::getId)));
    }

    @GetMapping("/ai-prompt")
    public Result<AiPrompt> aiPrompt() {
        AiPrompt p = aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
        return Result.ok(p);
    }

    @PostMapping("/ai-prompt/save")
    public Result<Void> aiPromptSave(@RequestBody AiPrompt prompt) {
        if (prompt.getId() != null) {
            aiPromptMapper.updateById(prompt);
        } else {
            if (prompt.getIsActive() == null) {
                prompt.setIsActive(0);
            }
            aiPromptMapper.insert(prompt);
        }
        if (!voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            openingVoiceCacheService.regenerateAsync(prompt.getId());
            endingVoiceCacheService.regenerateAsync(prompt.getId());
        }
        return Result.ok();
    }

    @PostMapping("/ai-prompt/{id:\\d+}/upload-opening-audio")
    public Result<Map<String, Object>> uploadOpeningAudio(
            @PathVariable Integer id,
            @RequestParam("file") MultipartFile file) throws IOException {
        AiPrompt row = aiPromptRecordingService.uploadOpening(id, file);
        Map<String, Object> body = new HashMap<>();
        body.put("id", row.getId());
        body.put("openingWavPath", row.getOpeningWavPath());
        body.put("audioUrl", AiPromptRecordingService.toPublicUrl(row.getOpeningWavPath()));
        return Result.ok(body);
    }

    @PostMapping("/ai-prompt/{id:\\d+}/upload-ending-audio")
    public Result<Map<String, Object>> uploadEndingAudio(
            @PathVariable Integer id,
            @RequestParam("file") MultipartFile file) throws IOException {
        AiPrompt row = aiPromptRecordingService.uploadEnding(id, file);
        Map<String, Object> body = new HashMap<>();
        body.put("id", row.getId());
        body.put("endingWavPath", row.getEndingWavPath());
        body.put("audioUrl", AiPromptRecordingService.toPublicUrl(row.getEndingWavPath()));
        return Result.ok(body);
    }

    @PostMapping("/ai-prompt/activate/{id}")
    public Result<Void> aiPromptActivate(@PathVariable Integer id) {
        aiPromptMapper.selectList(null).forEach(p -> {
            p.setIsActive(p.getId().equals(id) ? 1 : 0);
            aiPromptMapper.updateById(p);
        });
        if (!voiceRuntimeSettingsService.isSmartPrerecordMode()) {
            openingVoiceCacheService.regenerateAsync(id);
            endingVoiceCacheService.regenerateAsync(id);
        }
        return Result.ok();
    }

    /** 固定话术预录音状态（接通/挂断播放用） */
    @GetMapping("/ai-prompt/fixed-voice-status")
    public Result<FixedVoicePrecacheStatusDto> fixedVoiceStatus() {
        return Result.ok(fixedPhraseVoiceAdminService.getStatus());
    }

    /** 按当前 CosyVoice 模型/音色同步预生成启用话术的开场白与结束语 */
    @PostMapping("/ai-prompt/precache-fixed-voice")
    public Result<FixedVoicePrecacheStatusDto> precacheFixedVoice() {
        return Result.ok(fixedPhraseVoiceAdminService.precacheActiveSync());
    }

    /** @deprecated 请使用 precache-fixed-voice */
    @PostMapping("/ai-prompt/precache-opening")
    public Result<FixedVoicePrecacheStatusDto> precacheOpening() {
        return Result.ok(fixedPhraseVoiceAdminService.precacheActiveSync());
    }

    /** 外呼对话链路：分段 ASR+LLM+TTS */
    @GetMapping("/voice-runtime")
    public Result<VoiceRuntimeConfigDto> voiceRuntime() {
        return Result.ok(voiceRuntimeSettingsService.getForAdmin());
    }

    @PostMapping("/voice-runtime/save")
    public Result<Void> voiceRuntimeSave(@RequestBody VoiceRuntimeConfigDto dto) {
        voiceRuntimeSettingsService.saveFromAdmin(dto);
        return Result.ok();
    }

    /** CosyVoice 已复刻音色列表（与当前 tts-model 匹配项标记 compatible） */
    @GetMapping("/cosyvoice-voice/list")
    public Result<List<CosyVoiceVoiceItemDto>> cosyVoiceList() {
        return Result.ok(cosyVoiceVoiceEnrollmentService.listVoices());
    }

    /** 创建 CosyVoice 复刻音色 */
    @PostMapping("/cosyvoice-voice/enroll")
    public Result<CosyVoiceVoiceItemDto> cosyVoiceEnroll(@RequestBody CosyVoiceVoiceEnrollRequest req) {
        CosyVoiceVoiceItemDto created = cosyVoiceVoiceEnrollmentService.enroll(req);
        openingVoiceCacheService.regenerateAsync(null);
        endingVoiceCacheService.regenerateAsync(null);
        return Result.ok(created);
    }

    /** 删除 CosyVoice 复刻音色 */
    @DeleteMapping("/cosyvoice-voice/{voiceId}")
    public Result<Void> cosyVoiceDelete(@PathVariable String voiceId) {
        cosyVoiceVoiceEnrollmentService.deleteVoice(voiceId);
        return Result.ok();
    }

    /**
     * 上传参考音频到本机 uploads，返回公网 URL（DashScope 复刻须能访问）。
     * 若 playback-base-url 为 127.0.0.1，请改用 OSS/公网 URL。
     */
    @PostMapping("/cosyvoice-voice/upload-audio")
    public Result<Map<String, String>> cosyVoiceUploadAudio(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择音频文件");
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.wav";
        String lower = original.toLowerCase();
        if (!lower.endsWith(".wav") && !lower.endsWith(".mp3") && !lower.endsWith(".m4a")
                && !lower.endsWith(".mpeg")) {
            throw new BizException("仅支持 wav / mp3 / m4a 格式");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new BizException("音频文件不能超过 10MB");
        }
        Path dir = Paths.get("./uploads/voice-clone/");
        Files.createDirectories(dir);
        String ext = lower.contains(".") ? lower.substring(lower.lastIndexOf('.')) : ".wav";
        String name = UUID.randomUUID() + ext;
        Files.write(dir.resolve(name), file.getBytes());
        String relative = "/uploads/voice-clone/" + name;
        String base = aiVoiceProperties.getPlaybackBaseUrl();
        if (!StringUtils.hasText(base)) {
            base = "http://127.0.0.1:8081";
        }
        base = base.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        Map<String, String> data = new HashMap<>();
        data.put("relativeUrl", relative);
        data.put("publicUrl", base + relative);
        if (base.contains("127.0.0.1") || base.contains("localhost")) {
            data.put("hint", "当前 playback-base-url 为本地地址，DashScope 可能无法拉取；请改用公网/OSS URL");
        }
        return Result.ok(data);
    }
}
