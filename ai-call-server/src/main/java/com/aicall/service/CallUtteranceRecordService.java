package com.aicall.service;

import com.aicall.config.AiVoiceProperties;
import com.aicall.config.FreeSwitchProperties;
import com.aicall.util.FsHostOs;
import com.aicall.util.TelephonyWavUtil;
import com.aicall.util.TelephonyAsrAudioUtil;
import com.aicall.util.TelephonyVadUtil;
import com.aicall.util.TelephonyWavUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用户语音录音：优先 {@code uuid_execute record}（park 通道上比 uuid_record 可靠），
 * 失败时再回退 {@code uuid_record}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallUtteranceRecordService {

    private final AiVoiceProperties aiVoiceProperties;
    private final FreeSwitchProperties freeSwitchProperties;
    private final FreeSwitchEslService eslService;
    private final TelephonyAsrAudioUtil telephonyAsrAudioUtil;
    private final CallSessionRecordService callSessionRecordService;
    private final VoiceRuntimeSettingsService voiceRuntimeSettingsService;
    private final VoicePlaybackService voicePlaybackService;

    public record RecordPaths(String fsPath, Path localPath) {
    }

    public RecordPaths newRecordPaths(String uuid) throws java.io.IOException {
        String dir = resolveRecordDir();
        String fileName = "rec_" + uuid.replace("-", "") + "_" + System.currentTimeMillis() + ".wav";
        String fsPath = FsHostOs.joinRemotePath(dir, fileName);
        Path localPath = Path.of(dir.replace('/', '\\'), fileName);
        ensureRecordDir(dir, localPath);
        return new RecordPaths(fsPath, localPath);
    }

    /** 固定时长录音（秒），供对话短段 ASR */
    public Path recordFixedSeconds(String uuid, int seconds) throws Exception {
        if (callSessionRecordService.isSessionRecording(uuid)) {
            return recordFixedSecondsFromSession(uuid, seconds);
        }
        int sec = Math.max(1, seconds);
        RecordPaths paths = newRecordPaths(uuid);
        prepareRecordChannel(uuid);
        if (!recordViaTransfer(uuid, paths.fsPath(), sec) && !recordViaExecute(uuid, paths.fsPath(), sec)) {
            log.warn("[录音] uuid_execute 未成功，回退 uuid_record uuid={}", uuid);
            recordViaUuidRecord(uuid, paths.fsPath(), sec);
            Thread.sleep(sec * 1000L + 400L);
            stopUuidRecord(uuid, paths.fsPath());
        } else {
            Thread.sleep(sec * 1000L + 400L);
            eslService.api("uuid_break " + uuid + " all");
            reparkChannel(uuid);
        }
        waitForRecordFile(paths.localPath(), paths.fsPath(), 3000);
        return finishRecord(uuid, paths);
    }

    /**
     * 等用户说完：在 {@code uuid_execute record} 最长时长内用 VAD 检测句末静音后 break。
     */
    public Path recordUntilSilence(String uuid) throws Exception {
        if (callSessionRecordService.isSessionRecording(uuid)) {
            try {
                return recordUntilSilenceFromSession(uuid);
            } catch (NoSpeechDetectedException e) {
                log.warn("[录音] 全程录音 VAD 未检出客户语音，回退独立录音 uuid={}", uuid);
                return recordUntilSilenceDirect(uuid);
            }
        }
        return recordUntilSilenceDirect(uuid);
    }

    private Path recordUntilSilenceDirect(String uuid) throws Exception {
        RecordPaths paths = newRecordPaths(uuid);
        int maxMs = Math.max(3000, aiVoiceProperties.getAsrRecordMaxMs());
        int limitSec = Math.max(3, (maxMs + 999) / 1000);
        int silenceMs = voiceRuntimeSettingsService.resolveAsrVadSilenceMs(uuid);
        int minSpeechMs = Math.max(200, aiVoiceProperties.getAsrVadMinSpeechMs());
        int pollMs = Math.max(50, aiVoiceProperties.getAsrVadPollMs());
        int energy = aiVoiceProperties.getAsrVadEnergyThreshold();

        prepareRecordChannel(uuid);
        if (!recordViaTransfer(uuid, paths.fsPath(), limitSec)
                && !recordViaExecute(uuid, paths.fsPath(), limitSec)) {
            log.warn("[录音] VAD：transfer/execute 失败，回退 uuid_record uuid={}", uuid);
            eslRecord(uuid, "start", paths.fsPath(), limitSec);
        }

        long start = System.currentTimeMillis();
        boolean speechSeen = false;
        while (System.currentTimeMillis() - start < maxMs) {
            if (!eslService.uuidExists(uuid)) {
                break;
            }
            Thread.sleep(pollMs);
            if (!Files.exists(paths.localPath()) || Files.size(paths.localPath()) <= 44) {
                continue;
            }
            try {
                short[] samples = TelephonyAsrAudioUtil.readAvailablePcm16ForVad(paths.localPath());
                if (!speechSeen && TelephonyVadUtil.hasAnySpeech(samples, energy)) {
                    speechSeen = true;
                }
                if (speechSeen && TelephonyVadUtil.isUtteranceComplete(
                        samples, TelephonyWavUtil.TELEPHONY_RATE, silenceMs, minSpeechMs, energy)) {
                    log.info("[ASR-VAD] 客户已说完（句末静音约{}ms）uuid={}", silenceMs, uuid);
                    break;
                }
            } catch (Exception e) {
                log.trace("[ASR-VAD] 轮询 wav 暂不可解析: {}", e.getMessage());
            }
        }
        if (!speechSeen) {
            log.info("[ASR-VAD] 本轮未检测到客户有效语音，不送识别 uuid={}", uuid);
            throw new NoSpeechDetectedException(uuid);
        }

        eslService.api("uuid_break " + uuid + " all");
        reparkChannel(uuid);
        stopUuidRecord(uuid, paths.fsPath());
        waitForRecordFile(paths.localPath(), paths.fsPath(), 2500);
        return finishRecord(uuid, paths);
    }

    private Path recordUntilSilenceFromSession(String uuid) throws Exception {
        Path session = callSessionRecordService.getSessionLocalPath(uuid);
        if (session == null) {
            throw new java.io.IOException("全程录音未启动");
        }
        long mark = callSessionRecordService.getAsrReadOffset(uuid);
        int maxMs = Math.max(2000, aiVoiceProperties.getAsrRecordMaxMs());
        int silenceMs = voiceRuntimeSettingsService.resolveAsrVadSilenceMs(uuid);
        int minSpeechMs = Math.max(200, aiVoiceProperties.getAsrVadMinSpeechMs());
        int pollMs = Math.max(35, aiVoiceProperties.getAsrVadPollMs());
        int energy = aiVoiceProperties.getAsrVadEnergyThreshold();

        long start = System.currentTimeMillis();
        boolean speechSeen = false;
        int noSpeechGiveUpMs = Math.min(2600, Math.max(1500, maxMs / 3));
        while (System.currentTimeMillis() - start < maxMs) {
            if (!eslService.uuidExists(uuid)) {
                break;
            }
            if (!speechSeen && System.currentTimeMillis() - start >= noSpeechGiveUpMs) {
                log.info("[ASR-VAD] 全程录音未检测到客户语音 uuid={}", uuid);
                throw new NoSpeechDetectedException(uuid);
            }
            Thread.sleep(pollMs);
            if (!Files.exists(session) || Files.size(session) <= mark + 320) {
                continue;
            }
            try {
                short[] samples = readSessionPcmSinceMark(session, mark);
                if (samples.length == 0) {
                    continue;
                }
                if (!speechSeen && TelephonyVadUtil.hasAnySpeech(samples, energy)) {
                    speechSeen = true;
                    // 用户说话中：重置句末计时由 isUtteranceComplete 内部 trailing silence 保证
                }
                if (speechSeen && TelephonyVadUtil.isUtteranceComplete(
                        samples, TelephonyWavUtil.TELEPHONY_RATE, silenceMs, minSpeechMs, energy)) {
                    log.info("[ASR-VAD] 客户已说完（全程录音）uuid={} silenceMs={}", uuid, silenceMs);
                    break;
                }
            } catch (NoSpeechDetectedException e) {
                throw e;
            } catch (Exception e) {
                log.trace("[ASR-VAD] 全程录音轮询: {}", e.getMessage());
            }
        }
        if (!speechSeen) {
            log.info("[ASR-VAD] 全程录音未检测到客户语音 uuid={}", uuid);
            throw new NoSpeechDetectedException(uuid);
        }
        long endOffset = Files.size(session);
        Path slice = extractSessionSlice(session, mark, endOffset);
        callSessionRecordService.setAsrReadOffset(uuid, endOffset);
        log.info("[录音] 从全程文件切出 ASR 片段 uuid={} bytes={}", uuid, Files.size(slice));
        return telephonyAsrAudioUtil.prepareForAsr(slice);
    }

    /** 仅读取 mark 之后新增 PCM（VAD 用），最多取尾部若干秒避免把历史 TTS 算进静音检测 */
    private short[] readSessionPcmSinceMark(Path session, long mark) throws java.io.IOException {
        byte[] raw = Files.readAllBytes(session);
        if (raw.length < 44) {
            return new short[0];
        }
        int dataStart = findWavDataOffset(raw);
        int from = (int) Math.max(dataStart, mark);
        int to = raw.length;
        if (to - from < 320) {
            return new short[0];
        }
        int maxVadPcm = TelephonyWavUtil.TELEPHONY_RATE * 2 * 10;
        if (to - from > maxVadPcm) {
            from = to - maxVadPcm;
        }
        byte[] pcm = java.util.Arrays.copyOfRange(raw, from, to);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        short[] out = new short[pcm.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getShort();
        }
        return out;
    }

    private Path recordFixedSecondsFromSession(String uuid, int seconds) throws Exception {
        Path session = callSessionRecordService.getSessionLocalPath(uuid);
        if (session == null) {
            throw new java.io.IOException("全程录音未启动");
        }
        long mark = callSessionRecordService.getAsrReadOffset(uuid);
        Thread.sleep(seconds * 1000L + 200L);
        long endOffset = Files.exists(session) ? Files.size(session) : mark;
        Path slice = extractSessionSlice(session, mark, endOffset);
        callSessionRecordService.setAsrReadOffset(uuid, endOffset);
        return telephonyAsrAudioUtil.prepareForAsr(slice);
    }

    private Path extractSessionSlice(Path session, long fromOffset, long toOffset) throws java.io.IOException {
        byte[] raw = Files.readAllBytes(session);
        if (toOffset <= fromOffset || raw.length < 44) {
            throw new java.io.IOException("全程录音无新数据");
        }
        int dataStart = findWavDataOffset(raw);
        int start = (int) Math.max(dataStart, fromOffset);
        int end = (int) Math.min(raw.length, toOffset);
        if (end - start < 320) {
            throw new java.io.IOException("切片过短");
        }
        byte[] pcm = java.util.Arrays.copyOfRange(raw, start, end);
        int maxPcm = Math.max(16000, aiVoiceProperties.getAsrMaxSliceSec()
                * TelephonyWavUtil.TELEPHONY_RATE * 2);
        if (pcm.length > maxPcm) {
            pcm = java.util.Arrays.copyOfRange(pcm, pcm.length - maxPcm, pcm.length);
        }
        byte[] wav = TelephonyWavUtil.buildWavFromRawPcm(pcm, TelephonyWavUtil.TELEPHONY_RATE, 1, 16);
        Path out = session.getParent().resolve(
                "rec_slice_" + System.currentTimeMillis() + ".wav");
        Files.write(out, wav);
        return out;
    }

    private static int findWavDataOffset(byte[] raw) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.position(12);
        while (buf.remaining() >= 8) {
            byte[] tag = new byte[4];
            buf.get(tag);
            int size = buf.getInt();
            if (new String(tag).equals("data")) {
                return buf.position();
            }
            buf.position(buf.position() + Math.max(0, size));
        }
        return 44;
    }

    private Path finishRecord(String uuid, RecordPaths paths) throws java.io.IOException {
        if (!Files.exists(paths.localPath()) || Files.size(paths.localPath()) <= 44) {
            throw new java.io.IOException("录音文件无效 path=" + paths.localPath()
                    + " fsPath=" + paths.fsPath());
        }
        long bytes = Files.size(paths.localPath());
        log.info("[录音] 已写入 uuid={} path={} bytes={}", uuid, paths.localPath().getFileName(), bytes);
        return telephonyAsrAudioUtil.prepareForAsr(paths.localPath());
    }

    private void prepareRecordChannel(String uuid) {
        eslRecordStopAll(uuid, false);
        // dialplan 里 record_session=false 只关「全程自动录」，ASR 短录仍需显式 record / uuid_execute
        eslService.api("uuid_setvar " + uuid + " record_session true");
        eslService.api("uuid_setvar " + uuid + " RECORD_STEREO false");
        eslService.api("uuid_setvar " + uuid + " record_sample_rate 8000");
        eslService.api("uuid_setvar " + uuid + " enable_file_write_buffering false");
    }

    /** park 通道：inline record 后回到 park */
    private boolean recordViaTransfer(String uuid, String fsPath, int limitSec) {
        String path = fsPath.replace('\\', '/');
        String[] dests = {
                "-both record:" + path + " " + limitSec + " inline",
                "record:" + path + " " + limitSec + " inline"
        };
        for (String dest : dests) {
            String cmd = "uuid_transfer " + uuid + " " + dest;
            FreeSwitchEslService.EslResponse r = eslService.api(cmd);
            String reply = r.getReplyText() != null ? r.getReplyText() : r.getBody();
            if (r.isOk()) {
                log.info("[录音] uuid_transfer record 已下发 uuid={} sec={} reply={}",
                        uuid, limitSec, reply);
                return true;
            }
            log.warn("[录音] uuid_transfer record 失败 uuid={} dest={} reply={}", uuid, dest, reply);
        }
        return false;
    }

    private void reparkChannel(String uuid) {
        String app = freeSwitchProperties.getOriginateApplication();
        if (app == null || !app.contains("park")) {
            return;
        }
        eslService.api("uuid_transfer " + uuid + " -both park inline");
    }

    /** park 外呼：uuid_execute record 比 uuid_record 更易落盘 */
    private boolean recordViaExecute(String uuid, String fsPath, int limitSec) {
        String path = fsPath.replace('\\', '/');
        String[] cmds = {
                "uuid_execute " + uuid + " record " + path + " " + limitSec,
                "uuid_execute " + uuid + " record::" + path + " " + limitSec
        };
        for (String cmd : cmds) {
            FreeSwitchEslService.EslResponse r = eslService.api(cmd);
            String reply = r.getReplyText() != null ? r.getReplyText() : r.getBody();
            if (r.isOk()) {
                log.info("[录音] uuid_execute 已下发 uuid={} sec={} cmd={} reply={}",
                        uuid, limitSec, cmd.contains("record::") ? "record::" : "record", reply);
                return true;
            }
            log.warn("[录音] uuid_execute 失败 uuid={} reply={}", uuid, reply);
        }
        return false;
    }

    private void recordViaUuidRecord(String uuid, String fsPath, int limitSec) {
        eslRecord(uuid, "start", fsPath, limitSec);
    }

    private String resolveRecordDir() {
        String dir = aiVoiceProperties.getFsRecordDir();
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsSharedWavDir();
        }
        if (!StringUtils.hasText(dir)) {
            dir = aiVoiceProperties.getFsTempWavDir();
        }
        return dir;
    }

    private void ensureRecordDir(String dir, Path localPath) throws java.io.IOException {
        Files.createDirectories(localPath.getParent());
        eslService.bgapi("system " + FsHostOs.mkdirCommand(aiVoiceProperties.getFsHostOs(), dir));
    }

    private void eslRecord(String uuid, String action, String fsPath, int limitSec) {
        String cmd = "uuid_record " + uuid + " start " + fsPath + " " + limitSec;
        FreeSwitchEslService.EslResponse r = eslService.api(cmd);
        if (!r.isOk()) {
            log.warn("[录音] uuid_record start 失败 uuid={} path={} reply={}",
                    uuid, fsPath, r.getReplyText() != null ? r.getReplyText() : r.getBody());
        } else {
            log.info("[录音] uuid_record start uuid={} sec={} path={}", uuid, limitSec, fsPath);
        }
    }

    private void stopUuidRecord(String uuid, String fsPath) {
        FreeSwitchEslService.EslResponse r = eslService.api("uuid_record " + uuid + " stop " + fsPath);
        if (!r.isOk()) {
            eslRecordStopAll(uuid, false);
        }
    }

    private void eslRecordStopAll(String uuid, boolean warnOnFail) {
        FreeSwitchEslService.EslResponse r = eslService.api("uuid_record " + uuid + " stop all");
        if (!r.isOk() && warnOnFail) {
            log.debug("[录音] stop all uuid={} reply={}",
                    uuid, r.getReplyText() != null ? r.getReplyText() : r.getBody());
        }
    }

    private void waitForRecordFile(Path localPath, String fsPath, int maxWaitMs) throws InterruptedException {
        int steps = Math.max(1, maxWaitMs / 80);
        for (int i = 0; i < steps; i++) {
            try {
                if (Files.exists(localPath) && Files.size(localPath) > 44) {
                    return;
                }
                if (fsFileExists(fsPath)) {
                    Thread.sleep(80L);
                    if (Files.exists(localPath) && Files.size(localPath) > 44) {
                        return;
                    }
                }
            } catch (java.io.IOException ignored) {
            }
            Thread.sleep(80L);
        }
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

    /**
     * TTS/AI 播报期间：检测全程录音尾部是否有用户插嘴（本地能量，不送 ASR）。
     */
    public boolean detectBargeInFromSession(String uuid) {
        if (!aiVoiceProperties.isBargeInEnabled()) {
            return false;
        }
        if (!voicePlaybackService.mayDetectBargeIn(uuid)) {
            return false;
        }
        if (!callSessionRecordService.isSessionRecording(uuid)) {
            return false;
        }
        Path session = callSessionRecordService.getSessionLocalPath(uuid);
        if (session == null) {
            return false;
        }
        try {
            if (!Files.exists(session) || Files.size(session) <= 44) {
                return false;
            }
            long mark = callSessionRecordService.getAsrReadOffset(uuid);
            short[] sinceMark = readSessionPcmSinceMark(session, mark);
            if (sinceMark.length == 0) {
                return false;
            }
            int holdMs = aiVoiceProperties.resolveBargeInHoldMs();
            int tailSamples = Math.max(160, TelephonyWavUtil.TELEPHONY_RATE * holdMs / 1000);
            int from = Math.max(0, sinceMark.length - tailSamples);
            short[] tail = java.util.Arrays.copyOfRange(sinceMark, from, sinceMark.length);
            if (tail.length == 0) {
                return false;
            }
            int energy = (int) (aiVoiceProperties.resolveBargeInEnergyThreshold() * 1.5);
            return TelephonyVadUtil.hasAnySpeech(tail, energy);
        } catch (Exception e) {
            log.trace("[ASR-VAD] 插嘴检测失败 uuid={}: {}", uuid, e.getMessage());
            return false;
        }
    }

    private static short[] readSessionTailPcm(Path session, int tailMs) throws java.io.IOException {
        byte[] raw = Files.readAllBytes(session);
        if (raw.length < 44) {
            return new short[0];
        }
        int dataStart = findWavDataOffset(raw);
        int tailBytes = Math.max(320, TelephonyWavUtil.TELEPHONY_RATE * 2 * tailMs / 1000);
        int from = Math.max(dataStart, raw.length - tailBytes);
        if (raw.length - from < 320) {
            return new short[0];
        }
        byte[] pcm = java.util.Arrays.copyOfRange(raw, from, raw.length);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[pcm.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buf.getShort();
        }
        return samples;
    }
}
