package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.entity.AiPrompt;
import com.aicall.mapper.AiPromptMapper;
import com.aicall.util.UploadedAudioConverter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 智能预录外呼：话术模板开场白/结束语真人录音上传与播放路径解析 */
@Service
@RequiredArgsConstructor
public class AiPromptRecordingService {

    private static final Path UPLOAD_DIR = Path.of("./uploads/tts/prompt-recordings");

    private final AiPromptMapper aiPromptMapper;

    public AiPrompt loadActivePrompt() {
        return aiPromptMapper.selectOne(
                new LambdaQueryWrapper<AiPrompt>().eq(AiPrompt::getIsActive, 1).last("LIMIT 1"));
    }

    public AiPrompt uploadOpening(Integer promptId, MultipartFile file) throws IOException {
        AiPrompt row = requirePrompt(promptId);
        row.setOpeningWavPath(storeTelephonyWav(promptId, "opening", file).toString().replace('\\', '/'));
        aiPromptMapper.updateById(row);
        return row;
    }

    public AiPrompt uploadEnding(Integer promptId, MultipartFile file) throws IOException {
        AiPrompt row = requirePrompt(promptId);
        row.setEndingWavPath(storeTelephonyWav(promptId, "ending", file).toString().replace('\\', '/'));
        aiPromptMapper.updateById(row);
        return row;
    }

    public Path resolveOpeningWav(AiPrompt prompt) {
        return resolveExistingWav(prompt != null ? prompt.getOpeningWavPath() : null);
    }

    public Path resolveEndingWav(AiPrompt prompt) {
        return resolveExistingWav(prompt != null ? prompt.getEndingWavPath() : null);
    }

    public boolean hasOpeningRecording(AiPrompt prompt) {
        return resolveOpeningWav(prompt) != null;
    }

    public boolean hasEndingRecording(AiPrompt prompt) {
        return resolveEndingWav(prompt) != null;
    }

    public static String toPublicUrl(String wavPath) {
        return RecordingOnlyPlaybackService.toPublicUrl(wavPath);
    }

    public Path resolveExistingWav(String wavPath) {
        if (!StringUtils.hasText(wavPath)) {
            return null;
        }
        Path p = Path.of(wavPath.replace('/', '\\'));
        try {
            return Files.exists(p) && Files.size(p) > 44 ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private AiPrompt requirePrompt(Integer promptId) {
        if (promptId == null) {
            throw new BizException("话术模板不存在");
        }
        AiPrompt row = aiPromptMapper.selectById(promptId);
        if (row == null) {
            throw new BizException("话术模板不存在");
        }
        return row;
    }

    private Path storeTelephonyWav(Integer promptId, String kind, MultipartFile file) throws IOException {
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
        Files.createDirectories(UPLOAD_DIR);
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.')).toLowerCase()
                : ".wav";
        Path temp = UPLOAD_DIR.resolve("prompt_" + promptId + "_" + kind + "_src_" + System.currentTimeMillis() + ext);
        Path out = UPLOAD_DIR.resolve("prompt_" + promptId + "_" + kind + "_" + System.currentTimeMillis() + ".wav");
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
}
