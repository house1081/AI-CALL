package com.aicall.service.prerecord;

import com.aicall.common.PrerecordClipType;
import com.aicall.common.TtsSynthesisException;
import com.aicall.config.AiVoiceProperties;
import com.aicall.entity.PrerecordAudioClip;
import com.aicall.entity.PrerecordFaq;
import com.aicall.mapper.PrerecordAudioClipMapper;
import com.aicall.util.OralScriptNormalizer;
import com.aicall.util.SpeakTextLimiter;
import com.aicall.util.TelephonyWavUtil;
import com.aicall.service.TtsPhraseCacheService;
import com.aicall.service.VoicePlaybackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrerecordPlaybackService {

    private final PrerecordClipMemoryService prerecordClipMemoryService;
    private final PrerecordAudioClipMapper prerecordAudioClipMapper;
    private final VoicePlaybackService voicePlaybackService;
    private final TtsPhraseCacheService ttsPhraseCacheService;
    private final AiVoiceProperties aiVoiceProperties;

    /**
     * 高频命中：缓冲 → 应答 → 收尾，合并为一条 wav 一次播放（停顿写入静音，不阻塞线程）。
     */
    public boolean playFaqAnswerSequence(String fsUuid, PrerecordFaq faq) throws Exception {
        List<byte[]> parts = new ArrayList<>();
        appendClipWav(parts, pickGlobal(PrerecordClipType.BUFFER), true);
        appendSilence(parts);
        appendClipWav(parts, pickAnswer(faq), false);
        appendSilence(parts);
        appendClipWav(parts, pickGlobal(PrerecordClipType.CLOSING), true);
        if (parts.isEmpty()) {
            throw new TtsSynthesisException("预录片段缺失", false);
        }
        byte[] merged = TelephonyWavUtil.concatenateWavs(parts);
        Path tmp = Path.of("./uploads/tts/prerecord/_play_" + fsUuid.replace("-", "") + ".wav");
        Files.createDirectories(tmp.getParent());
        Files.write(tmp, merged);
        boolean ok = voicePlaybackService.playSynthesizedWav(fsUuid, tmp);
        if (!ok) {
            throw new TtsSynthesisException("预录合并播放失败", false);
        }
        voicePlaybackService.waitPlaybackFinished(fsUuid, faq.getAnswerText(), null);
        return true;
    }

    public boolean playTransferSequence(String fsUuid) throws Exception {
        return playSingleClip(fsUuid, pickGlobal(PrerecordClipType.TRANSFER), "转接专业顾问");
    }

    public boolean playRefuseEnding(String fsUuid) throws Exception {
        PrerecordAudioClip clip = pickGlobal(PrerecordClipType.REFUSE);
        if (clip == null) {
            clip = pickGlobal(PrerecordClipType.END);
        }
        return playSingleClip(fsUuid, clip, clip != null ? clip.getTextContent() : "再见");
    }

    private boolean playSingleClip(String fsUuid, PrerecordAudioClip clip, String waitText) throws Exception {
        if (clip == null) {
            throw new TtsSynthesisException("预录片段缺失", false);
        }
        Path wav = resolveOrSynthesizeWav(clip);
        if (wav == null) {
            playTtsFallback(fsUuid, clip.getTextContent());
        } else {
            boolean ok = voicePlaybackService.playSynthesizedWav(fsUuid, wav);
            if (!ok) {
                playTtsFallback(fsUuid, clip.getTextContent());
            }
        }
        voicePlaybackService.waitPlaybackFinished(fsUuid, waitText, null);
        return true;
    }

    private void appendClipWav(List<byte[]> parts, PrerecordAudioClip clip, boolean optional) throws Exception {
        if (clip == null) {
            if (!optional) {
                throw new TtsSynthesisException("预录片段缺失", false);
            }
            return;
        }
        Path wav = resolveOrSynthesizeWav(clip);
        if (wav == null) {
            if (!optional) {
                throw new TtsSynthesisException("预录 wav 不可用", false);
            }
            return;
        }
        parts.add(Files.readAllBytes(wav));
    }

    private void appendSilence(List<byte[]> parts) throws Exception {
        int min = Math.max(300, aiVoiceProperties.getPrerecordPauseMinMs());
        int max = Math.max(min + 100, aiVoiceProperties.getPrerecordPauseMaxMs());
        int ms = ThreadLocalRandom.current().nextInt(min, max + 1);
        if (!parts.isEmpty()) {
            parts.set(parts.size() - 1, TelephonyWavUtil.appendSilenceMs(parts.get(parts.size() - 1), ms));
        }
    }

    private Path resolveOrSynthesizeWav(PrerecordAudioClip clip) {
        Path wav = resolveWav(clip);
        if (wav != null) {
            return wav;
        }
        if (!StringUtils.hasText(clip.getTextContent())) {
            return null;
        }
        return synthesizeClipToDisk(clip);
    }

    private void playTtsFallback(String fsUuid, String text) throws Exception {
        String safe = SpeakTextLimiter.limit(text, aiVoiceProperties.getMaxSpeakChars());
        safe = OralScriptNormalizer.normalize(safe);
        boolean ok = aiVoiceProperties.isTtsSentenceSequentialEnabled()
                ? voicePlaybackService.playTextSequential(fsUuid, safe)
                : voicePlaybackService.playOnChannel(fsUuid, safe);
        if (!ok) {
            throw new TtsSynthesisException("预录 TTS 回退失败", false);
        }
    }

    private Path resolveWav(PrerecordAudioClip clip) {
        if (clip == null || !StringUtils.hasText(clip.getWavPath())) {
            return null;
        }
        Path p = Path.of(clip.getWavPath().replace('/', '\\'));
        try {
            return Files.exists(p) && Files.size(p) > 44 ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private PrerecordAudioClip pickAnswer(PrerecordFaq faq) {
        PrerecordAudioClip clip = prerecordClipMemoryService.pickAnswer(faq.getId());
        if (clip != null) {
            return clip;
        }
        PrerecordAudioClip synthetic = new PrerecordAudioClip();
        synthetic.setTextContent(faq.getAnswerText());
        synthetic.setFaqId(faq.getId());
        synthetic.setClipType(PrerecordClipType.ANSWER);
        return synthetic;
    }

    private PrerecordAudioClip pickGlobal(String clipType) {
        return prerecordClipMemoryService.pickGlobal(clipType);
    }

    public Path synthesizeClipToDisk(PrerecordAudioClip clip) {
        if (clip == null || !StringUtils.hasText(clip.getTextContent()) || !ttsPhraseCacheService.isAvailable()) {
            return null;
        }
        try {
            Path dir = Path.of("./uploads/tts/prerecord");
            Files.createDirectories(dir);
            String name = clip.getClipType() + "_" + (clip.getFaqId() != null ? clip.getFaqId() : "g")
                    + "_v" + (clip.getVariantNo() != null ? clip.getVariantNo() : 1) + ".wav";
            Path out = dir.resolve(name);
            Path built = ttsPhraseCacheService.synthesizeFixedPhraseToFile(out, clip.getTextContent(), null);
            if (built != null && Files.exists(built) && clip.getId() != null) {
                PrerecordAudioClip upd = new PrerecordAudioClip();
                upd.setId(clip.getId());
                upd.setWavPath(built.toString());
                prerecordAudioClipMapper.updateById(upd);
                prerecordClipMemoryService.reload();
            }
            return built;
        } catch (Exception e) {
            log.warn("[预录外呼] 合成片段失败 clipId={}: {}", clip.getId(), e.getMessage());
        }
        return null;
    }
}
