package com.aicall.service;

import com.aicall.util.FsHostOs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 经 ESL 将 wav 写入 FS 本机，不依赖 FS 访问 Java HTTP（跨网段 curl 常失败）。
 * ESL 单条命令有长度上限，分片需较小（约 800 字符）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FsWavTransferService {

    /** ESL api 命令长度上限约 4k，预留 shell 包装 */
    private static final int B64_CHUNK_CHARS = 800;

    private final FreeSwitchEslService eslService;

    public boolean pushFileToFs(Path localWav, String remoteWavPath, String fsHostOs) {
        if (FsHostOs.isWindows(fsHostOs)) {
            log.debug("Windows FS 请使用 fs-shared-wav-dir，跳过 ESL 分片推送");
            return false;
        }
        try {
            byte[] data = Files.readAllBytes(localWav);
            if (data.length < 44) {
                log.warn("wav 过短，跳过推送 path={}", localWav);
                return false;
            }
            String b64 = Base64.getEncoder().encodeToString(data);
            String b64Path = remoteWavPath + ".b64";
            String dir = parentDir(remoteWavPath);
            if (!eslOk(eslService.api("system " + FsHostOs.mkdirCommand(fsHostOs, dir)))) {
                log.warn("ESL mkdir 失败 dir={}", dir);
                return false;
            }
            eslService.api("system " + FsHostOs.deleteFilesCommand(fsHostOs, remoteWavPath, b64Path));
            int chunks = 0;
            int totalChunks = (b64.length() + B64_CHUNK_CHARS - 1) / B64_CHUNK_CHARS;
            for (int i = 0; i < b64.length(); i += B64_CHUNK_CHARS) {
                String part = b64.substring(i, Math.min(i + B64_CHUNK_CHARS, b64.length()));
                String cmd = "system printf '%s' '" + part + "' >> " + shellQuote(b64Path);
                FreeSwitchEslService.EslResponse r = eslService.api(cmd);
                if (!eslOk(r)) {
                    log.warn("ESL 写入 b64 分片失败 chunk={}/{} cmdLen={} reply={}",
                            chunks, totalChunks, cmd.length(), replyOf(r));
                    return false;
                }
                chunks++;
                if (chunks % 25 == 0) {
                    log.info("ESL 推送进度 {}/{}", chunks, totalChunks);
                }
            }
            FreeSwitchEslService.EslResponse dec = eslService.api(
                    "system (base64 -d " + shellQuote(b64Path) + " > " + shellQuote(remoteWavPath)
                            + " 2>/dev/null || base64 --decode " + shellQuote(b64Path) + " > "
                            + shellQuote(remoteWavPath) + ") && rm -f " + shellQuote(b64Path));
            if (!eslOk(dec)) {
                log.warn("ESL base64 解码失败 reply={}", replyOf(dec));
                return false;
            }
            FreeSwitchEslService.EslResponse exists = eslService.api("file_exists " + shellQuote(remoteWavPath));
            String body = exists.getBody() != null ? exists.getBody() : "";
            boolean ok = body.contains("true");
            if (ok) {
                log.info("ESL 已推送 wav 到 FS {} ({} bytes, {} 分片)", remoteWavPath, data.length, chunks);
            } else {
                log.warn("ESL 推送后 FS 文件不存在 path={}", remoteWavPath);
            }
            return ok;
        } catch (Exception e) {
            log.warn("ESL 推送 wav 异常: {}", e.getMessage());
            return false;
        }
    }

    private static boolean eslOk(FreeSwitchEslService.EslResponse r) {
        String reply = replyOf(r);
        if (reply == null) {
            return false;
        }
        if (reply.contains("-ERR")) {
            return false;
        }
        return reply.contains("+OK") || reply.contains("Job-UUID");
    }

    private static String replyOf(FreeSwitchEslService.EslResponse r) {
        if (r.getReplyText() != null) {
            return r.getReplyText();
        }
        return r.getBody();
    }

    private static String parentDir(String path) {
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : "/tmp";
    }

    private static String shellQuote(String path) {
        if (path.indexOf(' ') < 0 && path.indexOf('\'') < 0) {
            return path;
        }
        return "'" + path.replace("'", "'\\''") + "'";
    }
}
