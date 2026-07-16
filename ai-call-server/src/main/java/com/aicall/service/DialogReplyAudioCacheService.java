package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.util.TtsAudioCacheKeyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 问答级音频缓存：相同用户问题 + 相同音色 → 直接复用已合成 wav，跳过 LLM 与 TTS。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogReplyAudioCacheService {

    private final AiVoiceProperties aiVoiceProperties;
    private final DashScopeVoiceTtsService dashScopeVoiceTtsService;
    private final TtsPhraseCacheService ttsPhraseCacheService;
    private final RecordingOnlyPlaybackService recordingOnlyPlaybackService;
    private final ObjectMapper objectMapper;

    private volatile long lookupHits;
    private volatile long lookupMisses;

    private final Map<String, CachedReply> memoryIndex = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedReply> eldest) {
            return size() > maxMemoryEntries();
        }
    };

    public record CachedReply(String replyText, Path wavPath, String userQuestion) {
    }

    public boolean isEnabled() {
        return aiVoiceProperties.isDialogReplyAudioCacheEnabled();
    }

    public boolean canStore() {
        return isEnabled();
    }

    public Optional<CachedReply> lookup(String userText, String voiceId) {
        if (!isEnabled() || !eligibleUserText(userText)) {
            return Optional.empty();
        }
        String key = questionKey(userText, voiceId);
        synchronized (memoryIndex) {
            CachedReply hit = memoryIndex.get(key);
            if (hit != null && isValidWav(hit.wavPath())) {
                lookupHits++;
                log.info("[问答缓存] 命中 question={} replyLen={} hits={} misses={}",
                        abbreviate(hit.userQuestion()), hit.replyText().length(), lookupHits, lookupMisses);
                return Optional.of(hit);
            }
        }
        Path wav = cacheDir().resolve(key + ".wav");
        Path meta = cacheDir().resolve(key + ".json");
        if (!isValidWav(wav) || !Files.exists(meta)) {
            lookupMisses++;
            log.debug("[问答缓存] 未命中 question={} hits={} misses={}，走 LLM+TTS",
                    abbreviate(userText), lookupHits, lookupMisses);
            return Optional.empty();
        }
        try {
            Meta m = objectMapper.readValue(Files.readString(meta, StandardCharsets.UTF_8), Meta.class);
            if (!StringUtils.hasText(m.reply)) {
                return Optional.empty();
            }
            CachedReply cached = new CachedReply(m.reply.trim(), wav, abbreviate(m.question));
            synchronized (memoryIndex) {
                memoryIndex.put(key, cached);
            }
            lookupHits++;
            log.info("[问答缓存] 磁盘命中 question={} replyLen={} hits={} misses={}",
                    cached.userQuestion(), cached.replyText().length(), lookupHits, lookupMisses);
            return Optional.of(cached);
        } catch (Exception e) {
            log.debug("[问答缓存] 读取元数据失败 key={}: {}", key.substring(0, 8), e.getMessage());
            return Optional.empty();
        }
    }

    public void store(String userText, String replyText, String voiceId) {
        if (!canStore() || !eligibleUserText(userText) || !eligibleReplyText(replyText)) {
            return;
        }
        String normalizedReply = TtsAudioCacheKeyUtil.normalizeReplyText(
                replyText, aiVoiceProperties.getMaxSpeakChars());
        if (!StringUtils.hasText(normalizedReply)) {
            return;
        }
        String key = questionKey(userText, voiceId);
        Path wav = cacheDir().resolve(key + ".wav");
        Path meta = cacheDir().resolve(key + ".json");
        if (isValidWav(wav) && Files.exists(meta)) {
            return;
        }
        try {
            Files.createDirectories(cacheDir());
            Optional<Path> phraseHit = ttsPhraseCacheService.findCachedWav(normalizedReply, voiceId);
            Path source = phraseHit.orElse(null);
            if (source == null && ttsPhraseCacheService.isAvailable()) {
                Path tmp = cacheDir().resolve(key + ".part.wav");
                source = ttsPhraseCacheService.synthesizeToFile(tmp, normalizedReply, voiceId);
            }
            if (!isValidWav(source)) {
                Files.deleteIfExists(cacheDir().resolve(key + ".part.wav"));
                log.debug("[问答缓存] 无可用 wav，跳过存储 question={}", abbreviate(userText));
                return;
            }
            Files.copy(source, wav, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(cacheDir().resolve(key + ".part.wav"));
            Meta m = new Meta();
            m.question = TtsAudioCacheKeyUtil.normalizeUserQuestion(userText);
            m.reply = normalizedReply;
            m.voice = voiceSignature(voiceId);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(meta.toFile(), m);
            CachedReply cached = new CachedReply(normalizedReply, wav, abbreviate(m.question));
            synchronized (memoryIndex) {
                memoryIndex.put(key, cached);
            }
            log.info("[问答缓存] 已存储 question={} replyLen={} bytes={}",
                    cached.userQuestion(), normalizedReply.length(), Files.size(wav));
        } catch (Exception e) {
            log.debug("[问答缓存] 存储失败 question={}: {}", abbreviate(userText), e.getMessage());
        }
    }

    /** RAG 重建时预热：FAQ 已有录音 → 常见问句直接命中，跳过 LLM+TTS */
    public void warmFromFaq(DialogTrainingQa row) {
        if (!isEnabled() || row == null || !StringUtils.hasText(row.getQuestion())) {
            return;
        }
        String remark = row.getRemark() != null ? row.getRemark().trim() : "";
        if (remark.startsWith("flow:")) {
            return;
        }
        if (!StringUtils.hasText(row.getAnswerWavPath()) || !StringUtils.hasText(row.getStandardAnswer())) {
            return;
        }
        if (!eligibleUserText(row.getQuestion())) {
            return;
        }
        Path wav = recordingOnlyPlaybackService.resolveExistingWav(row.getAnswerWavPath().trim());
        if (!isValidWav(wav)) {
            return;
        }
        String reply = TtsAudioCacheKeyUtil.normalizeReplyText(
                row.getStandardAnswer(), aiVoiceProperties.getMaxSpeakChars());
        if (!StringUtils.hasText(reply)) {
            return;
        }
        String key = questionKey(row.getQuestion(), null);
        CachedReply cached = new CachedReply(reply, wav, abbreviate(row.getQuestion()));
        synchronized (memoryIndex) {
            memoryIndex.put(key, cached);
        }
        log.debug("[问答缓存] FAQ 预热 question={} qaId={}", cached.userQuestion(), row.getId());
    }

    public void warmFromFaqList(List<DialogTrainingQa> rows) {
        if (!isEnabled() || rows == null || rows.isEmpty()) {
            return;
        }
        int warmed = 0;
        for (DialogTrainingQa row : rows) {
            int before = memoryIndexSize();
            warmFromFaq(row);
            if (memoryIndexSize() > before) {
                warmed++;
            }
        }
        if (warmed > 0) {
            log.info("[问答缓存] FAQ 预热完成 entries={} warmed={}/{}", memoryIndex.size(), warmed, rows.size());
        }
    }

    private int memoryIndexSize() {
        synchronized (memoryIndex) {
            return memoryIndex.size();
        }
    }

    private boolean eligibleUserText(String userText) {
        if (!StringUtils.hasText(userText)) {
            return false;
        }
        String norm = TtsAudioCacheKeyUtil.normalizeUserQuestion(userText);
        int min = Math.max(1, aiVoiceProperties.getDialogReplyAudioCacheMinUserChars());
        int max = Math.max(min, aiVoiceProperties.getDialogReplyAudioCacheMaxUserChars());
        return norm.length() >= min && norm.length() <= max;
    }

    private boolean eligibleReplyText(String replyText) {
        if (!StringUtils.hasText(replyText)) {
            return false;
        }
        String norm = TtsAudioCacheKeyUtil.normalizeReplyText(
                replyText, aiVoiceProperties.getMaxSpeakChars());
        return norm.length() >= 2 && norm.length() <= aiVoiceProperties.getMaxSpeakChars() + 16;
    }

    private String questionKey(String userText, String voiceId) {
        String voice = voiceSignature(voiceId);
        String question = TtsAudioCacheKeyUtil.normalizeUserQuestion(userText);
        String raw = voice + "|q|" + question;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    private String voiceSignature(String voiceId) {
        return StringUtils.hasText(voiceId)
                ? dashScopeVoiceTtsService.voiceCacheSignature(voiceId)
                : dashScopeVoiceTtsService.voiceCacheSignature();
    }

    private Path cacheDir() {
        String dir = aiVoiceProperties.getDialogReplyAudioCacheDir();
        if (!StringUtils.hasText(dir)) {
            dir = "./uploads/tts/reply-cache";
        }
        return Path.of(dir.replace('/', '\\'));
    }

    private int maxMemoryEntries() {
        return Math.max(64, aiVoiceProperties.getDialogReplyAudioCacheMaxEntries());
    }

    private static boolean isValidWav(Path wav) {
        if (wav == null || !Files.exists(wav)) {
            return false;
        }
        try {
            return Files.size(wav) > 44;
        } catch (Exception e) {
            return false;
        }
    }

    private static String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String s = text.trim();
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }

    private static class Meta {
        public String question;
        public String reply;
        public String voice;
    }
}
