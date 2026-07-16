package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.common.TtsSynthesisException;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.util.FsHostOs;
import com.aicall.util.OralScriptNormalizer;
import com.aicall.util.StreamTextSplitter;
import com.aicall.util.TelephonyWavUtil;
import com.aicall.util.WavDurationUtil;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一向 FreeSWITCH 通道播放音频（HTTP wav / tone / speak）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoicePlaybackService {

    private final AiVoiceProperties aiVoiceProperties;
    private final FreeSwitchEslService eslService;
    private final TtsAudioService ttsAudioService;
    private final LocalPromptWavService localPromptWavService;
    private final FreeSwitchProperties freeSwitchProperties;
    private final FsWavTransferService fsWavTransferService;
    private final CallSessionRecordService callSessionRecordService;
    private final DialogTurnRegistry dialogTurnRegistry;

    /** 每通道播放互斥，防止多线程叠音/串音 */
    private final ConcurrentHashMap<String, Object> channelPlayLocks = new ConcurrentHashMap<>();
    /** 当前播报起止，用于插嘴保护期（避免 TTS 回声误打断） */
    private final ConcurrentHashMap<String, PlaybackWindow> playbackWindows = new ConcurrentHashMap<>();

    /** park 通道上 uuid_execute 不可用（日志：Command not found），探测后跳过 */
    private volatile boolean uuidExecuteUnsupported;

    /**
     * 预合成 wav（供流式 TTS 首句播放期间并行合成剩余内容）。
     */
    public Path synthesizeToFile(String fsUuid, String text) throws Exception {
        return localPromptWavService.synthesizeToFile(fsUuid, text);
    }

    /** 播放已合成的 wav 文件 */
    public boolean playSynthesizedWav(String fsUuid, Path wav) {
        if (!StringUtils.hasText(fsUuid) || wav == null) {
            return false;
        }
        synchronized (channelLock(fsUuid)) {
            if (!eslService.uuidExists(fsUuid)) {
                return false;
            }
            stopChannelPlayback(fsUuid);
            boolean ok = playExistingWavInternal(fsUuid, wav);
            if (ok) {
                dialogTurnRegistry.markAiPlaybackStarted(fsUuid, "prerecord-wav");
            }
            return ok;
        }
    }

    /**
     * 金融外呼分句播报：每句单独 CosyVoice 合成，句间插入静音后合并一次播放（避免连续朗读感）。
     */
    public boolean playTextSequential(String fsUuid, String text) {
        if (!StringUtils.hasText(fsUuid) || !StringUtils.hasText(text)) {
            return false;
        }
        int maxChars = Math.max(16, aiVoiceProperties.getMaxSpeakChars());
        List<String> sentences = OralScriptNormalizer.splitForPlayback(text, maxChars);
        if (sentences.isEmpty()) {
            return false;
        }
        if (sentences.size() == 1) {
            return playOnChannel(fsUuid, sentences.get(0));
        }
        return playMergedSentencesWithPauses(fsUuid, sentences);
    }

    /**
     * 长话术：按句合成后合并为一条 wav 再播放，避免多次 displace/stop 造成中间空白。
     */
    public boolean playTextStreaming(String fsUuid, String text) {
        if (!StringUtils.hasText(fsUuid) || !StringUtils.hasText(text)) {
            return false;
        }
        if (aiVoiceProperties.isTtsSentenceSequentialEnabled()) {
            return playTextSequential(fsUuid, text);
        }
        if (!aiVoiceProperties.isTtsStreamEnabled()) {
            return playOnChannel(fsUuid, text);
        }
        int maxChars = Math.max(20, aiVoiceProperties.getMaxSpeakChars());
        List<String> sentences = StreamTextSplitter.splitForStreamTts(text, maxChars);
        if (sentences.size() <= 1) {
            return playOnChannel(fsUuid, text);
        }
        return playMergedSentencesWithPauses(fsUuid, sentences);
    }

    private boolean playMergedSentencesWithPauses(String fsUuid, List<String> sentences) {
        synchronized (channelLock(fsUuid)) {
            if (!eslService.uuidExists(fsUuid)) {
                return false;
            }
            stopChannelPlayback(fsUuid);
            List<byte[]> wavParts = new ArrayList<>();
            try {
                for (int i = 0; i < sentences.size(); i++) {
                    String sentence = sentences.get(i);
                    if (!eslService.uuidExists(fsUuid)) {
                        break;
                    }
                    Path wav = localPromptWavService.synthesizeToFile(fsUuid, sentence);
                    byte[] part = Files.readAllBytes(wav);
                    if (i < sentences.size() - 1) {
                        int pauseMs = resolveInterSentencePauseMs(sentence);
                        part = TelephonyWavUtil.appendSilenceMs(part, pauseMs);
                    }
                    wavParts.add(part);
                }
                if (wavParts.isEmpty()) {
                    return false;
                }
                byte[] merged = TelephonyWavUtil.concatenateWavs(wavParts);
                Path mergedFile = Files.createTempFile("aicall_merge_", ".wav");
                try {
                    Files.write(mergedFile, merged);
                    log.info("句级 TTS 已合并 uuid={} 段数={} 总时长约{}s",
                            fsUuid, sentences.size(),
                            String.format("%.2f", WavDurationUtil.durationSeconds(merged)));
                    return playExistingWavInternal(fsUuid, mergedFile);
                } finally {
                    Files.deleteIfExists(mergedFile);
                }
            } catch (TtsSynthesisException e) {
                if (e.isRateLimited()) {
                    log.warn("句级 TTS 限流，回退整段本地播报: {}", e.getMessage());
                    return playOnChannel(fsUuid, String.join("", sentences));
                }
                throw e;
            } catch (Exception e) {
                log.warn("合并句级 TTS 失败，回退整段播报: {}", e.getMessage());
                return playOnChannel(fsUuid, String.join("", sentences));
            }
        }
    }

    private int resolveInterSentencePauseMs(String sentence) {
        if (!aiVoiceProperties.isTtsSmartPauseEnabled()) {
            return Math.max(0, aiVoiceProperties.getTtsInterSentenceGapMs());
        }
        return OralScriptNormalizer.pauseMsAfter(
                sentence,
                aiVoiceProperties.getTtsClausePauseMs(),
                aiVoiceProperties.getTtsSentenceEndPauseMs(),
                aiVoiceProperties.getTtsInterSentenceGapMs());
    }

    /** 停止 TTS 播放（displace/break），录音前必须调用，否则听不到用户说话 */
    public void stopChannelPlayback(String fsUuid) {
        if (!StringUtils.hasText(fsUuid) || !eslService.uuidExists(fsUuid)) {
            return;
        }
        String path = sharedPlaybackPath(fsUuid);
        eslService.api("uuid_break " + fsUuid + " all");
        if (StringUtils.hasText(path)) {
            eslService.api("uuid_displace " + fsUuid + " stop " + path);
        }
        eslService.api("uuid_displace " + fsUuid + " stop");
    }

    /**
     * 按 FS 共享目录 wav 实际时长等待播报结束，并停止 displace。
     *
     * @return true 表示客户插嘴打断
     */
    public boolean waitPlaybackFinished(String fsUuid, String text,
                                        java.util.function.BooleanSupplier bargeInPoll) throws Exception {
        if (!StringUtils.hasText(fsUuid) || !eslService.uuidExists(fsUuid)) {
            log.info("通道已结束，跳过等待 TTS uuid={}", fsUuid);
            return false;
        }
        long ms = estimatePlaybackMs(fsUuid, text);
        log.info("等待 TTS 播完 uuid={} 约{}ms", fsUuid, ms);
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (bargeInPoll != null && bargeInPoll.getAsBoolean()) {
                stopChannelPlayback(fsUuid);
                log.info("[对话] 客户插嘴打断播报 uuid={}", fsUuid);
                return true;
            }
            if (!eslService.uuidExists(fsUuid)) {
                return false;
            }
            Thread.sleep(50);
        }
        if (eslService.uuidExists(fsUuid)) {
            stopChannelPlayback(fsUuid);
            Thread.sleep(Math.max(0, aiVoiceProperties.getPlaybackPostStopMs()));
        }
        return false;
    }

    public void waitPlaybackFinished(String fsUuid, String text) throws Exception {
        waitPlaybackFinished(fsUuid, text, null);
    }

    /** 播放已存在的本地 wav（如开场预热文件） */
    public boolean playExistingWav(String fsUuid, Path wav) {
        if (!StringUtils.hasText(fsUuid) || wav == null || !Files.exists(wav)) {
            return false;
        }
        synchronized (channelLock(fsUuid)) {
            stopChannelPlayback(fsUuid);
            return playExistingWavInternal(fsUuid, wav);
        }
    }

    /**
     * 外呼开场白：优先 uuid_broadcast（aleg），避免 both 双路叠音/回音；失败再 displace。
     */
    public boolean playOutboundOpeningWav(String fsUuid, Path wav) {
        if (!StringUtils.hasText(fsUuid) || wav == null || !Files.exists(wav)) {
            return false;
        }
        synchronized (channelLock(fsUuid)) {
            if (!eslService.uuidExists(fsUuid)) {
                return false;
            }
            stopChannelPlayback(fsUuid);
            eslService.ensureOutboundMediaReady(fsUuid);
            if (aiVoiceProperties.isFsFetchViaEsl() && tryFsSharedDirCopyForOpening(fsUuid, wav)) {
                return true;
            }
            String path = wav.toAbsolutePath().toString().replace('\\', '/');
            path = FsHostOs.normalizeLocalPlaybackPath(path);
            String media = buildPlaybackMedia(path);
            if (broadcastRaw(fsUuid, media, "opening-broadcast")) {
                log.info("[开场白] uuid_broadcast 已下发 uuid={}", fsUuid);
                return true;
            }
            log.warn("[开场白] broadcast 失败，回退 displace uuid={}", fsUuid);
            return playFileOnChannel(fsUuid, path, "opening-displace");
        }
    }

    private boolean tryFsSharedDirCopyForOpening(String fsUuid, Path localWav) {
        String shared = aiVoiceProperties.getFsSharedWavDir();
        if (!StringUtils.hasText(shared) || !FsHostOs.isWindows(aiVoiceProperties.getFsHostOs())) {
            return false;
        }
        try {
            String safeId = fsUuid.replace("-", "");
            String fileName = "aicall_" + safeId + ".wav";
            Path target = Path.of(shared.replace('/', '\\'), fileName);
            Files.createDirectories(target.getParent());
            Files.copy(localWav, target, StandardCopyOption.REPLACE_EXISTING);
            String fsPath = FsHostOs.joinRemotePath(shared, fileName);
            log.info("[开场白] 共享目录 wav uuid={} path={} bytes={}",
                    fsUuid, fsPath, Files.size(target));
            String media = buildPlaybackMedia(fsPath);
            if (broadcastRaw(fsUuid, media, "opening-fs-shared")) {
                return true;
            }
            return playFileOnChannel(fsUuid, fsPath, "opening-fs-displace");
        } catch (Exception e) {
            log.warn("[开场白] 共享目录写入失败 uuid={}: {}", fsUuid, e.getMessage());
            return false;
        }
    }

    public long estimatePlaybackMs(String fsUuid, String text) {
        int tail = Math.max(50, aiVoiceProperties.getPlaybackTailBufferMs());
        try {
            Path shared = Path.of(sharedPlaybackPath(fsUuid).replace('/', '\\'));
            if (Files.exists(shared) && Files.size(shared) > 44) {
                return (long) (WavDurationUtil.durationSeconds(shared) * 1000) + tail;
            }
        } catch (Exception ignored) {
        }
        List<String> parts = StreamTextSplitter.splitForStreamTts(text, 80);
        long ms = 400;
        int pause = Math.min(200, Math.max(100, aiVoiceProperties.getSentencePauseMs()));
        for (int i = 0; i < parts.size(); i++) {
            ms += Math.max(800, parts.get(i).length() * 120L);
            if (i < parts.size() - 1) {
                ms += pause;
            }
        }
        return Math.min(ms, 20000);
    }

    private String sharedPlaybackPath(String fsUuid) {
        String dir = aiVoiceProperties.getFsSharedWavDir();
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsTempWavDir();
        }
        return FsHostOs.joinRemotePath(dir, "aicall_" + fsUuid.replace("-", "") + ".wav");
    }

    private boolean playExistingWavInternal(String fsUuid, Path wav) {
        if (aiVoiceProperties.isFsFetchViaEsl() && tryFsLocalFilePlay(fsUuid, wav)) {
            return true;
        }
        if (StringUtils.hasText(aiVoiceProperties.getPlaybackBaseUrl())) {
            String base = normalizeBase(aiVoiceProperties.getPlaybackBaseUrl());
            String url = base + "/uploads/tts/" + wav.getFileName();
            return broadcastPlayback(fsUuid, url, "stream-http");
        }
        return playFileOnChannel(fsUuid, wav.toAbsolutePath().toString().replace('\\', '/'), "stream-wav");
    }

    /**
     * @return 是否至少一种方式已下发到 FS
     */
    public boolean playOnChannel(String fsUuid, String text) {
        if (!StringUtils.hasText(fsUuid)) {
            return false;
        }
        synchronized (channelLock(fsUuid)) {
            if (!eslService.uuidExists(fsUuid)) {
                log.warn("播报跳过：通道不存在 uuid={}", fsUuid);
                return false;
            }
            stopChannelPlayback(fsUuid);
            log.debug("开始向通道播报 uuid={} 字数={}", fsUuid, text.length());

            if (shouldUseHttpTts() && tryHttpTtsPlayback(fsUuid, text)) {
                return true;
            }
            if (tryLocalWavPlayback(fsUuid, text)) {
                return true;
            }
            if (aiVoiceProperties.isEnableToneFallback() && tryToneBeep(fsUuid)) {
                return true;
            }
            if (aiVoiceProperties.isEnableBuiltinSoundFallback() && tryFsBuiltinIvr(fsUuid)) {
                return true;
            }
            if (aiVoiceProperties.isEnableSpeakFallback()) {
                return trySpeakChain(fsUuid, text);
            }
            return false;
        }
    }

    /** 通道是否仍在估算的播报窗口内 */
    public boolean isPlaybackActive(String fsUuid) {
        if (!StringUtils.hasText(fsUuid)) {
            return false;
        }
        PlaybackWindow w = playbackWindows.get(fsUuid.trim());
        if (w == null) {
            return false;
        }
        return System.currentTimeMillis() - w.startMs < w.estimatedMs;
    }

    /** 播报期间是否允许插嘴检测（保护期内返回 false，保证用户听完整句） */
    public boolean mayDetectBargeIn(String fsUuid) {
        if (!StringUtils.hasText(fsUuid)) {
            return false;
        }
        PlaybackWindow w = playbackWindows.get(fsUuid.trim());
        if (w == null) {
            return true;
        }
        long elapsed = System.currentTimeMillis() - w.startMs;
        long minElapsed = Math.max(
                Math.max(0, aiVoiceProperties.getPlaybackBargeInGraceMs()),
                (long) (w.estimatedMs * Math.max(0.5, aiVoiceProperties.getPlaybackBargeInMinRatio())));
        return elapsed >= minElapsed;
    }

    /** 通话结束释放通道锁（避免线程池泄漏） */
    public void releaseChannel(String fsUuid) {
        if (StringUtils.hasText(fsUuid)) {
            String id = fsUuid.trim();
            channelPlayLocks.remove(id);
            playbackWindows.remove(id);
        }
    }

    private Object channelLock(String fsUuid) {
        return channelPlayLocks.computeIfAbsent(fsUuid.trim(), k -> new Object());
    }

    private boolean tryHttpTtsPlayback(String fsUuid, String text) {
        String url = ttsAudioService.synthesizePlaybackUrl(fsUuid, text);
        return StringUtils.hasText(url) && broadcastPlayback(fsUuid, url, "HTTP-TTS");
    }

    private boolean tryLocalWavPlayback(String fsUuid, String text) {
        if (!StringUtils.hasText(aiVoiceProperties.getPlaybackBaseUrl())) {
            return false;
        }
        try {
            Path wav = localPromptWavService.synthesizeToFile(fsUuid, text);
            String base = normalizeBase(aiVoiceProperties.getPlaybackBaseUrl());
            String url = base + "/uploads/tts/" + wav.getFileName();
            if (aiVoiceProperties.isFsFetchViaEsl()) {
                if (tryFsLocalFilePlay(fsUuid, wav)) {
                    return true;
                }
                log.warn("FS 本机 wav 不可用，尝试 HTTP 播放 url={}", url);
            }
            if (aiVoiceProperties.isPreferHttpPlayback()) {
                if (broadcastPlayback(fsUuid, url, "http-stream")) {
                    return true;
                }
            }
            return broadcastPlayback(fsUuid, url, "local-wav-http");
        } catch (TtsSynthesisException e) {
            throw e;
        } catch (Exception e) {
            if (TtsSynthesisException.isRateLimitedMessage(e.getMessage())) {
                throw new TtsSynthesisException("TTS 合成失败: " + e.getMessage(), e, true);
            }
            log.warn("本地 wav 生成失败: {}", e.getMessage());
            throw new TtsSynthesisException("TTS 合成失败: " + e.getMessage(), e, false);
        }
    }

    /**
     * 将 wav 放到 FS 本机后播放：优先 ESL 推送（不依赖 FS→Java HTTP），失败再 curl。
     */
    private boolean tryFsLocalFilePlay(String fsUuid, Path localWav) {
        String safeId = fsUuid.replace("-", "");
        String dir = aiVoiceProperties.getFsTempWavDir();
        String remotePath = FsHostOs.joinRemotePath(dir, "aicall_" + safeId + ".wav");
        if (tryFsSharedDirCopy(fsUuid, localWav, remotePath)) {
            return true;
        }
        if (aiVoiceProperties.isFsPushWavViaEsl()
                && fsWavTransferService.pushFileToFs(localWav, remotePath, aiVoiceProperties.getFsHostOs())) {
            return broadcastPlayback(fsUuid, remotePath, "fs-pushed-wav");
        }
        String base = normalizeBase(aiVoiceProperties.getPlaybackBaseUrl());
        String httpUrl = base + "/uploads/tts/" + localWav.getFileName();
        return tryFsEslCurlThenPlay(fsUuid, httpUrl, remotePath);
    }

    /** Java 与 Windows FS 同机：拷贝到共享目录后播放本地文件 */
    private boolean tryFsSharedDirCopy(String fsUuid, Path localWav, String remotePath) {
        String shared = aiVoiceProperties.getFsSharedWavDir();
        if (!StringUtils.hasText(shared) || !FsHostOs.isWindows(aiVoiceProperties.getFsHostOs())) {
            return false;
        }
        try {
            String fileName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
            Path target = Path.of(shared.replace('/', '\\'), fileName);
            Files.createDirectories(target.getParent());
            Files.copy(localWav, target, StandardCopyOption.REPLACE_EXISTING);
            String fsPath = FsHostOs.joinRemotePath(shared, fileName);
            double sec = WavDurationUtil.durationSeconds(target);
            log.info("Windows 共享目录已写入 wav path={} ({} bytes, 时长约 {}s)",
                    fsPath, Files.size(target), String.format("%.2f", sec));
            if (playFileOnChannel(fsUuid, fsPath, "fs-shared-wav")) {
                return true;
            }
            String base = normalizeBase(aiVoiceProperties.getPlaybackBaseUrl());
            String url = base + "/uploads/tts/" + localWav.getFileName();
            log.warn("共享目录文件播放失败，尝试 HTTP 播放 url={}", url);
            return broadcastPlayback(fsUuid, url, "fs-shared-http");
        } catch (Exception e) {
            log.warn("Windows 共享目录写入失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean tryFsEslCurlThenPlay(String fsUuid, String httpUrl, String localPath) {
        String os = aiVoiceProperties.getFsHostOs();
        String dir = aiVoiceProperties.getFsTempWavDir();
        eslService.bgapi("system " + FsHostOs.mkdirCommand(os, dir));
        String curl = FsHostOs.curlDownloadCommand(os, localPath, httpUrl);
        log.info("ESL 在 FS 本机拉取 wav: {}", truncate(httpUrl, 60));
        eslService.bgapi("system " + curl);
        try {
            Thread.sleep(Math.max(500, aiVoiceProperties.getFsCurlWaitMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!fsFileExists(localPath)) {
            log.warn("FS 本机 wav 不存在或 curl 失败 path={}，请确认 FS 能访问 {}", localPath, httpUrl);
            return false;
        }
        return broadcastPlayback(fsUuid, localPath, "fs-local-wav");
    }

    private boolean fsFileExists(String path) {
        try {
            FreeSwitchEslService.EslResponse r = eslService.api("file_exists " + path);
            String body = r.getBody() != null ? r.getBody() : "";
            return body.contains("true");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryToneBeep(String fsUuid) {
        String tone = "tone_stream://%(2000,0,400,450)";
        return broadcastRaw(fsUuid, tone, "tone");
    }

    private boolean tryFsBuiltinIvr(String fsUuid) {
        String path = aiVoiceProperties.getFsBuiltinSound();
        if (!StringUtils.hasText(path)) {
            return false;
        }
        return broadcastPlayback(fsUuid, path, "fs-builtin");
    }

    private boolean trySpeakChain(String fsUuid, String text) {
        String safe = sanitize(text);
        if (!containsCjk(safe)) {
            if (broadcastSpeak(fsUuid, aiVoiceProperties.getSpeakVoice(), safe)) {
                return true;
            }
        } else {
            log.warn("中文话术无法用 FS speak 朗读，请配置 tts-http-url 或 playback-base-url（Windows 可用 SAPI）");
        }
        if (StringUtils.hasText(aiVoiceProperties.getSpeakFallbackVoices())) {
            for (String voice : aiVoiceProperties.getSpeakFallbackVoices().split(",")) {
                if (broadcastSpeak(fsUuid, voice.trim(), safe)) {
                    return true;
                }
            }
        }
        return broadcastTransferFlite(fsUuid, safe);
    }

    private void prepareChannelForAiPlay(String fsUuid) {
        if (!eslService.uuidExists(fsUuid)) {
            return;
        }

        eslService.api("uuid_setvar " + fsUuid + " enable_ec true");
        eslService.api("uuid_setvar " + fsUuid + " ec_delay 60");
        eslService.api("uuid_setvar " + fsUuid + " ec_suppression 3");
        eslService.api("uuid_setvar " + fsUuid + " tts_say_while_listen true");
        eslService.api("uuid_setvar " + fsUuid + " rtp_enable_vad false");

        log.info("[播放前] 回声消除+全双工已开启 uuid={}", fsUuid);
    }

    private boolean playFileOnChannel(String fsUuid, String filePath, String tag) {
        if (!eslService.uuidExists(fsUuid)) {
            log.warn("播报跳过：通道已结束 uuid={}", fsUuid);
            return false;
        }
        prepareChannelForAiPlay(fsUuid);
        ensureOutboundPlayReady(fsUuid);
        String path = FsHostOs.normalizeLocalPlaybackPath(filePath);
        String media = buildPlaybackMedia(path);
        // park 外呼：本机 FS 通常仅 displace 可用（uuid_execute 会 Command not found）
        if (tryUuidDisplace(fsUuid, path, tag)) {
            markPlaybackStarted(fsUuid, path);
            return true;
        }
        if (broadcastRaw(fsUuid, media, tag + "-broadcast")) {
            markPlaybackStarted(fsUuid, path);
            return true;
        }
        if (tryUuidTransferPlayback(fsUuid, path, tag)) {
            markPlaybackStarted(fsUuid, path);
            return true;
        }
        if (trySchedBroadcast(fsUuid, path, tag)) {
            markPlaybackStarted(fsUuid, path);
            return true;
        }
        return false;
    }

    private void markPlaybackStarted(String fsUuid, String filePath) {
        if (!StringUtils.hasText(fsUuid)) {
            return;
        }
        long est = estimateMsForFile(filePath);
        playbackWindows.put(fsUuid.trim(), new PlaybackWindow(System.currentTimeMillis(), est));
        callSessionRecordService.markBargeInBaseline(fsUuid);
    }

    private long estimateMsForFile(String filePath) {
        int tail = Math.max(50, aiVoiceProperties.getPlaybackTailBufferMs());
        try {
            Path p = Path.of(filePath.replace('/', '\\'));
            if (Files.exists(p) && Files.size(p) > 44) {
                return (long) (WavDurationUtil.durationSeconds(p) * 1000) + tail;
            }
        } catch (Exception ignored) {
        }
        return 3000L;
    }

    private record PlaybackWindow(long startMs, long estimatedMs) {
    }

    /** 外呼 park：解除静音并确认媒体已建立，避免 displace/broadcast 无声 */
    private void ensureOutboundPlayReady(String fsUuid) {
        if (!eslService.uuidExists(fsUuid)) {
            return;
        }
        eslService.api("uuid_audio " + fsUuid + " start read write");
        eslService.api("uuid_setvar " + fsUuid + " mute_read false");
        eslService.api("uuid_setvar " + fsUuid + " mute_write false");
    }

    /**
     * inline playback：在 park 通道上执行 playback 应用（勿把文件路径当 dialplan 分机）。
     */
    private boolean tryUuidTransferPlayback(String fsUuid, String path, String tag) {
        String[] dests = {
                "-aleg playback:" + path + " inline",
                "playback:" + path + " inline"
        };
        for (String dest : dests) {
            String cmd = "uuid_transfer " + fsUuid + " " + dest;
            log.info("ESL uuid_transfer [{}] uuid={} dest={}", tag, fsUuid, truncate(dest, 80));
            FreeSwitchEslService.EslResponse resp = eslService.api(cmd);
            String reply = resp.getReplyText() != null ? resp.getReplyText() : resp.getBody();
            if (isEslOk(reply)) {
                return true;
            }
            log.warn("ESL uuid_transfer 失败 [{}] dest={} reply={}", tag, truncate(dest, 50), reply);
        }
        return false;
    }

    private boolean isEslOk(String reply) {
        if (!StringUtils.hasText(reply)) {
            return false;
        }
        String r = reply.trim();
        return r.contains("+OK") || r.contains("SUCCESS") || r.startsWith("202");
    }

    /** 在通道上直接执行 playback 应用（park 场景常比单独 broadcast 更响） */
    private boolean tryUuidExecutePlayback(String fsUuid, String path, String tag) {
        if (uuidExecuteUnsupported || !aiVoiceProperties.isPreferExecutePlayback()) {
            return false;
        }
        String arg = FsHostOs.normalizeLocalPlaybackPath(path);
        String cmd = "uuid_execute " + fsUuid + " playback " + arg;
        log.debug("ESL uuid_execute playback [{}] uuid={}", tag, fsUuid);
        FreeSwitchEslService.EslResponse resp = eslService.api(cmd);
        String reply = resp.getReplyText() != null ? resp.getReplyText() : resp.getBody();
        if (isEslOk(reply)) {
            log.info("ESL uuid_execute playback 成功 [{}] reply={}", tag, truncate(reply, 80));
            return true;
        }
        if (reply != null && reply.contains("Command not found")) {
            uuidExecuteUnsupported = true;
            log.info("ESL park 通道不支持 uuid_execute，后续直接使用 uuid_displace");
        } else {
            log.warn("ESL uuid_execute playback 失败 [{}] reply={}", tag, reply);
        }
        return false;
    }

    /** 在静音流/echo 保持的通道上叠加播文件，避免替换应用导致挂断 */
    private boolean trySchedBroadcast(String fsUuid, String path, String tag) {
        String media = buildPlaybackMedia(path);
        for (String leg : broadcastLegsOrdered()) {
            String cmd = "sched_broadcast +0 " + fsUuid + " " + media + " " + leg;
            log.info("ESL sched_broadcast [{}] uuid={} leg={} media={}", tag, fsUuid, leg, truncate(media, 70));
            FreeSwitchEslService.EslResponse resp = eslService.api(cmd);
            String reply = resp.getReplyText() != null ? resp.getReplyText() : resp.getBody();
            if (isEslOk(reply) || (reply != null && reply.contains("Scheduled"))) {
                return true;
            }
            log.warn("ESL sched_broadcast 失败 [{}] leg={} reply={}", tag, leg, reply);
        }
        return false;
    }

    private String[] broadcastLegsOrdered() {
        return new String[] {"aleg"};
    }

    private boolean tryUuidDisplace(String fsUuid, String path, String tag) {
        for (String mode : new String[] {"mux", "lr"}) {
            String cmd = "uuid_displace " + fsUuid + " start " + path + " 0 " + mode;
            log.info("ESL uuid_displace [{}] uuid={} mode={} file={}", tag, fsUuid, mode, truncate(path, 70));
            FreeSwitchEslService.EslResponse resp = eslService.api(cmd);
            String reply = resp.getReplyText() != null ? resp.getReplyText() : resp.getBody();
            if (isEslOk(reply)) {
                log.info("ESL uuid_displace 成功 [{}] mode={} reply={}", tag, mode, truncate(reply, 80));
                return true;
            }
            log.warn("ESL uuid_displace 失败 [{}] mode={} reply={}", tag, mode, reply);
        }
        return false;
    }

    private boolean broadcastPlayback(String fsUuid, String playbackArg, String tag) {
        return broadcastRaw(fsUuid, buildPlaybackMedia(playbackArg), tag);
    }

    private String buildPlaybackMedia(String playbackArg) {
        if (playbackArg.startsWith("http://") || playbackArg.startsWith("https://")
                || playbackArg.startsWith("tone_stream:")) {
            return "playback::" + playbackArg;
        }
        if (playbackArg.startsWith("playback::")) {
            return playbackArg;
        }
        return FsHostOs.normalizeLocalPlaybackPath(playbackArg);
    }

    private boolean broadcastRaw(String fsUuid, String media, String tag) {
        String legs = aiVoiceProperties.getBroadcastLegs();
        if (!StringUtils.hasText(legs)) {
            legs = "aleg";
        }
        boolean anyOk = false;
        for (String leg : legs.split(",")) {
            String l = leg.trim();
            if (!StringUtils.hasText(l)) {
                continue;
            }
            String cmd = "uuid_broadcast " + fsUuid + " " + media + " " + l;
            log.info("ESL 播放 [{}] uuid={} leg={} media={}", tag, fsUuid, l, truncate(media, 70));
            FreeSwitchEslService.EslResponse resp = eslService.api(cmd);
            String reply = resp.getReplyText() != null ? resp.getReplyText() : resp.getBody();
            if (isEslOk(reply) || (reply != null && reply.contains("Success"))) {
                anyOk = true;
            } else {
                log.warn("ESL 播放失败 [{}] leg={} reply={}", tag, l, reply);
            }
        }
        return anyOk;
    }

    private boolean broadcastSpeak(String fsUuid, String voice, String text) {
        if (!StringUtils.hasText(voice) || !StringUtils.hasText(text)) {
            return false;
        }
        String arg = "speak::" + voice + "|" + text;
        return broadcastRaw(fsUuid, arg, "speak-" + voice);
    }

    private boolean broadcastTransferFlite(String fsUuid, String text) {
        String snippet = text.length() > 60 ? text.substring(0, 60) : text;
        String cmd = "uuid_transfer " + fsUuid + " 'speak:flite|" + snippet + "' inline";
        log.info("ESL 备用 uuid_transfer flite uuid={}", fsUuid);
        FreeSwitchEslService.EslResponse resp = eslService.api(cmd);
        String reply = resp.getReplyText() != null ? resp.getReplyText() : resp.getBody();
        return reply != null && reply.contains("+OK");
    }

    private boolean shouldUseHttpTts() {
        String mode = aiVoiceProperties.getTtsMode();
        if ("cosyvoice".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("http".equalsIgnoreCase(mode)) {
            return StringUtils.hasText(aiVoiceProperties.getTtsHttpUrl());
        }
        if ("speak".equalsIgnoreCase(mode)) {
            return false;
        }
        return StringUtils.hasText(aiVoiceProperties.getTtsHttpUrl());
    }

    private String normalizeBase(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String sanitize(String text) {
        return text.replace("|", " ").replace("\n", " ").replace("'", " ").trim();
    }

    private boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
