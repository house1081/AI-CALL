package com.aicall.service.prerecord;

import com.aicall.common.BizException;
import com.aicall.common.PrerecordClipType;
import com.aicall.dto.PrerecordFaqDto;
import com.aicall.dto.PrerecordStatsDto;
import com.aicall.entity.PrerecordAudioClip;
import com.aicall.entity.PrerecordFaq;
import com.aicall.mapper.PrerecordAudioClipMapper;
import com.aicall.mapper.PrerecordFaqMapper;
import com.aicall.util.UploadedAudioConverter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PrerecordAdminService {

    private final PrerecordFaqMapper prerecordFaqMapper;
    private final PrerecordAudioClipMapper prerecordAudioClipMapper;
    private final PrerecordPlaybackService prerecordPlaybackService;
    private final PrerecordFaqSeedService prerecordFaqSeedService;
    private final PrerecordClipMemoryService prerecordClipMemoryService;

    public List<PrerecordFaqDto> listFaqs(String tier) {
        LambdaQueryWrapper<PrerecordFaq> q = new LambdaQueryWrapper<PrerecordFaq>()
                .orderByDesc(PrerecordFaq::getHitCount)
                .orderByDesc(PrerecordFaq::getMatchCount);
        if (StringUtils.hasText(tier)) {
            q.eq(PrerecordFaq::getTier, tier.trim());
        }
        return prerecordFaqMapper.selectList(q).stream().map(this::toDto).toList();
    }

    public PrerecordFaqDto saveFaq(PrerecordFaqDto dto) {
        PrerecordFaq faq = dto.getId() != null ? prerecordFaqMapper.selectById(dto.getId()) : new PrerecordFaq();
        if (faq == null) {
            faq = new PrerecordFaq();
        }
        faq.setQuestionDisplay(dto.getQuestionDisplay());
        faq.setQuestionNorm(com.aicall.util.TtsAudioCacheKeyUtil.normalizeUserQuestion(dto.getQuestionDisplay()));
        faq.setCategory(StringUtils.hasText(dto.getCategory()) ? dto.getCategory() : "其他");
        faq.setKeywords(dto.getKeywords());
        faq.setAnswerText(dto.getAnswerText());
        faq.setTier(StringUtils.hasText(dto.getTier()) ? dto.getTier() : "high_freq");
        faq.setEnabled(Boolean.FALSE.equals(dto.getEnabled()) ? 0 : 1);
        faq.setUpdateTime(LocalDateTime.now());
        if (faq.getId() == null) {
            faq.setHitCount(0);
            faq.setMatchCount(0);
            faq.setTransferCount(0);
            faq.setCreateTime(LocalDateTime.now());
            prerecordFaqMapper.insert(faq);
            if (StringUtils.hasText(faq.getAnswerText())) {
                PrerecordAudioClip clip = new PrerecordAudioClip();
                clip.setFaqId(faq.getId());
                clip.setClipType(PrerecordClipType.ANSWER);
                clip.setTextContent(faq.getAnswerText());
                clip.setVariantNo(1);
                clip.setEnabled(1);
                clip.setCreateTime(LocalDateTime.now());
                clip.setUpdateTime(LocalDateTime.now());
                prerecordAudioClipMapper.insert(clip);
            }
        } else {
            prerecordFaqMapper.updateById(faq);
        }
        prerecordClipMemoryService.reload();
        return toDto(prerecordFaqMapper.selectById(faq.getId()));
    }

    /** 新建 FAQ 并上传真人应答录音 */
    public PrerecordFaqDto createFaqWithAudio(PrerecordFaqDto dto, MultipartFile audio) throws IOException {
        if (dto == null || !StringUtils.hasText(dto.getQuestionDisplay())) {
            throw new BizException("请填写客户问题");
        }
        if (audio == null || audio.isEmpty()) {
            throw new BizException("请上传应答录音");
        }
        PrerecordFaqDto saved = saveFaq(dto);
        return uploadAnswerAudio(saved.getId(), audio);
    }

    /** 为已有 FAQ 上传/替换真人应答录音 */
    public PrerecordFaqDto uploadAnswerAudio(Long faqId, MultipartFile audio) throws IOException {
        if (faqId == null) {
            throw new BizException("FAQ 不存在");
        }
        PrerecordFaq faq = prerecordFaqMapper.selectById(faqId);
        if (faq == null) {
            throw new BizException("FAQ 不存在");
        }
        Path wavPath = storeUploadedTelephonyWav(faqId, audio);
        PrerecordAudioClip clip = findOrCreateAnswerClip(faqId, faq.getAnswerText());
        clip.setWavPath(wavPath.toString().replace('\\', '/'));
        clip.setUpdateTime(LocalDateTime.now());
        if (clip.getId() == null) {
            prerecordAudioClipMapper.insert(clip);
        } else {
            prerecordAudioClipMapper.updateById(clip);
        }
        prerecordClipMemoryService.reload();
        return toDto(prerecordFaqMapper.selectById(faqId));
    }

    private PrerecordAudioClip findOrCreateAnswerClip(Long faqId, String answerText) {
        List<PrerecordAudioClip> clips = prerecordAudioClipMapper.selectList(
                new LambdaQueryWrapper<PrerecordAudioClip>()
                        .eq(PrerecordAudioClip::getFaqId, faqId)
                        .eq(PrerecordAudioClip::getClipType, PrerecordClipType.ANSWER)
                        .eq(PrerecordAudioClip::getEnabled, 1)
                        .orderByAsc(PrerecordAudioClip::getVariantNo));
        if (!clips.isEmpty()) {
            return clips.get(0);
        }
        PrerecordAudioClip clip = new PrerecordAudioClip();
        clip.setFaqId(faqId);
        clip.setClipType(PrerecordClipType.ANSWER);
        clip.setTextContent(StringUtils.hasText(answerText) ? answerText : "");
        clip.setVariantNo(1);
        clip.setEnabled(1);
        clip.setCreateTime(LocalDateTime.now());
        return clip;
    }

    private Path storeUploadedTelephonyWav(Long faqId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择录音文件");
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.wav";
        if (!UploadedAudioConverter.isSupportedUploadName(original)) {
            throw new BizException("请上传 wav / mp3 / m4a / mp4 格式录音");
        }
        if (file.getSize() > 15 * 1024 * 1024) {
            throw new BizException("录音文件不能超过 15MB");
        }
        Path dir = Path.of("./uploads/tts/prerecord/uploads");
        Files.createDirectories(dir);
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.')).toLowerCase()
                : ".wav";
        Path temp = dir.resolve("faq_" + faqId + "_src_" + System.currentTimeMillis() + ext);
        Path out = dir.resolve("faq_" + faqId + "_" + System.currentTimeMillis() + ".wav");
        Files.write(temp, file.getBytes());
        try {
            UploadedAudioConverter.convertToTelephony8k(temp, out);
        } catch (Exception e) {
            Files.deleteIfExists(out);
            String msg = e.getMessage() != null ? e.getMessage() : "录音转换失败";
            throw new BizException(msg);
        } finally {
            Files.deleteIfExists(temp);
        }
        if (Files.size(out) <= 44) {
            Files.deleteIfExists(out);
            throw new BizException("录音过短或无效");
        }
        return out;
    }

    public int precacheFaqClips(Long faqId) {
        if (faqId == null) {
            return 0;
        }
        List<PrerecordAudioClip> clips = prerecordAudioClipMapper.selectList(
                new LambdaQueryWrapper<PrerecordAudioClip>()
                        .eq(PrerecordAudioClip::getFaqId, faqId)
                        .eq(PrerecordAudioClip::getEnabled, 1));
        int ok = 0;
        for (PrerecordAudioClip clip : clips) {
            if (prerecordPlaybackService.synthesizeClipToDisk(clip) != null) {
                ok++;
            }
        }
        prerecordClipMemoryService.reload();
        return ok;
    }

    public int precacheAllGlobalClips() {
        List<PrerecordAudioClip> clips = prerecordAudioClipMapper.selectList(
                new LambdaQueryWrapper<PrerecordAudioClip>()
                        .isNull(PrerecordAudioClip::getFaqId)
                        .eq(PrerecordAudioClip::getEnabled, 1));
        int ok = 0;
        for (PrerecordAudioClip clip : clips) {
            if (prerecordPlaybackService.synthesizeClipToDisk(clip) != null) {
                ok++;
            }
        }
        prerecordClipMemoryService.reload();
        return ok;
    }

    public PrerecordStatsDto stats() {
        PrerecordStatsDto dto = new PrerecordStatsDto();
        dto.setHighFreqFaqCount(prerecordFaqMapper.selectCount(
                new LambdaQueryWrapper<PrerecordFaq>().eq(PrerecordFaq::getTier, "high_freq")));
        dto.setColdFaqCount(prerecordFaqMapper.selectCount(
                new LambdaQueryWrapper<PrerecordFaq>().eq(PrerecordFaq::getTier, "cold")));
        List<PrerecordFaq> all = prerecordFaqMapper.selectList(null);
        long match = 0;
        long transfer = 0;
        for (PrerecordFaq f : all) {
            match += f.getMatchCount() != null ? f.getMatchCount() : 0;
            transfer += f.getTransferCount() != null ? f.getTransferCount() : 0;
        }
        dto.setTotalMatchCount(match);
        dto.setTotalTransferCount(transfer);
        List<PrerecordAudioClip> clips = prerecordAudioClipMapper.selectList(
                new LambdaQueryWrapper<PrerecordAudioClip>().eq(PrerecordAudioClip::getEnabled, 1));
        dto.setClipCount(clips.size());
        dto.setClipWithWavCount(clips.stream().filter(c -> StringUtils.hasText(c.getWavPath())).count());
        return dto;
    }

    public void reseedDefaults() {
        prerecordFaqSeedService.seedDefaults();
        prerecordClipMemoryService.reload();
    }

    private PrerecordFaqDto toDto(PrerecordFaq faq) {
        PrerecordFaqDto dto = new PrerecordFaqDto();
        dto.setId(faq.getId());
        dto.setQuestionDisplay(faq.getQuestionDisplay());
        dto.setQuestionNorm(faq.getQuestionNorm());
        dto.setCategory(faq.getCategory());
        dto.setKeywords(faq.getKeywords());
        dto.setAnswerText(faq.getAnswerText());
        dto.setTier(faq.getTier());
        dto.setHitCount(faq.getHitCount());
        dto.setMatchCount(faq.getMatchCount());
        dto.setTransferCount(faq.getTransferCount());
        dto.setEnabled(faq.getEnabled() != null && faq.getEnabled() == 1);
        long answerClips = prerecordAudioClipMapper.selectCount(new LambdaQueryWrapper<PrerecordAudioClip>()
                .eq(PrerecordAudioClip::getFaqId, faq.getId())
                .eq(PrerecordAudioClip::getClipType, PrerecordClipType.ANSWER)
                .isNotNull(PrerecordAudioClip::getWavPath));
        dto.setHasAnswerClip(answerClips > 0);
        PrerecordAudioClip clip = prerecordAudioClipMapper.selectOne(
                new LambdaQueryWrapper<PrerecordAudioClip>()
                        .eq(PrerecordAudioClip::getFaqId, faq.getId())
                        .eq(PrerecordAudioClip::getClipType, PrerecordClipType.ANSWER)
                        .isNotNull(PrerecordAudioClip::getWavPath)
                        .orderByAsc(PrerecordAudioClip::getVariantNo)
                        .last("LIMIT 1"));
        if (clip != null && StringUtils.hasText(clip.getWavPath())) {
            String p = clip.getWavPath().replace('\\', '/');
            if (p.startsWith("./")) {
                p = p.substring(1);
            }
            if (!p.startsWith("/")) {
                p = "/" + p;
            }
            dto.setAudioUrl(p);
        }
        return dto;
    }
}
