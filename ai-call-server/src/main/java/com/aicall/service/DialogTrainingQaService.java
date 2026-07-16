package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.DialogTrainingDataType;
import com.aicall.config.DialogRagProperties;
import com.aicall.dto.DialogRagRetrieveResult;
import com.aicall.dto.DialogTrainingQaSaveRequest;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.mapper.DialogTrainingQaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aicall.util.UploadedAudioConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DialogTrainingQaService {

    private final DialogTrainingQaMapper dialogTrainingQaMapper;
    private final DialogRagRetrievalService dialogRagRetrievalService;
    private final DialogRagProperties dialogRagProperties;
    private final DialogScriptPackRegistry dialogScriptPackRegistry;
    private final KbAnswerWavCacheService kbAnswerWavCacheService;

    public List<DialogTrainingQa> list(Integer kbId, Integer dataType, Integer status) {
        LambdaQueryWrapper<DialogTrainingQa> q = new LambdaQueryWrapper<DialogTrainingQa>()
                .orderByDesc(DialogTrainingQa::getId);
        if (kbId != null) {
            q.eq(DialogTrainingQa::getKbId, kbId);
        }
        if (dataType != null) {
            q.eq(DialogTrainingQa::getDataType, dataType);
        }
        if (status != null) {
            q.eq(DialogTrainingQa::getStatus, status);
        }
        return dialogTrainingQaMapper.selectList(q);
    }

    public DialogTrainingQa get(Integer id) {
        return dialogTrainingQaMapper.selectById(id);
    }

    /** 按主线步骤 remark（如 flow:02）查问答及录音 */
    public DialogTrainingQa findByFlowStep(int kbId, String step) {
        if (!StringUtils.hasText(step)) {
            return null;
        }
        return dialogTrainingQaMapper.selectOne(new LambdaQueryWrapper<DialogTrainingQa>()
                .eq(DialogTrainingQa::getKbId, kbId)
                .eq(DialogTrainingQa::getRemark, "flow:" + step.trim())
                .eq(DialogTrainingQa::getStatus, 1)
                .last("LIMIT 1"));
    }

    /** 按标准答案文本匹配已上传录音（智能预录播放兜底，不走 CosyVoice） */
    public DialogTrainingQa findByAnswerText(int kbId, String answerText) {
        if (!StringUtils.hasText(answerText)) {
            return null;
        }
        String target = normalizeAnswerText(answerText);
        List<DialogTrainingQa> rows = dialogTrainingQaMapper.selectList(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kbId)
                        .eq(DialogTrainingQa::getStatus, 1)
                        .isNotNull(DialogTrainingQa::getAnswerWavPath)
                        .ne(DialogTrainingQa::getAnswerWavPath, ""));
        for (DialogTrainingQa row : rows) {
            if (!StringUtils.hasText(row.getStandardAnswer())) {
                continue;
            }
            for (String variant : row.getStandardAnswer().split("[；;]")) {
                if (target.equals(normalizeAnswerText(variant))) {
                    return row;
                }
            }
        }
        return null;
    }

    private static String normalizeAnswerText(String text) {
        return text.trim()
                .replaceAll("[\\s　]+", "")
                .replaceAll("[，,。.!！?？~～]+", "");
    }

    public DialogTrainingQa uploadAnswerAudio(Integer id, MultipartFile audio) throws IOException {
        if (id == null) {
            throw new BizException("记录不存在");
        }
        DialogTrainingQa row = dialogTrainingQaMapper.selectById(id);
        if (row == null) {
            throw new BizException("记录不存在");
        }
        Path wavPath = storeUploadedTelephonyWav(id, audio);
        row.setAnswerWavPath(wavPath.toString().replace('\\', '/'));
        dialogTrainingQaMapper.updateById(row);
        kbAnswerWavCacheService.invalidateQa(row.getId());
        kbAnswerWavCacheService.invalidateKbAnswer(row.getKbId(), row.getStandardAnswer());
        dialogScriptPackRegistry.reloadFromDb();
        return row;
    }

    public static String answerAudioUrl(DialogTrainingQa row) {
        if (row == null || !StringUtils.hasText(row.getAnswerWavPath())) {
            return null;
        }
        return RecordingOnlyPlaybackService.toPublicUrl(row.getAnswerWavPath());
    }

    public DialogTrainingQa save(DialogTrainingQaSaveRequest req) {
        validate(req);
        DialogTrainingQa row;
        if (req.getId() != null) {
            row = dialogTrainingQaMapper.selectById(req.getId());
            if (row == null) {
                throw new BizException("记录不存在");
            }
        } else {
            row = new DialogTrainingQa();
        }
        row.setKbId(req.getKbId() != null ? req.getKbId() : DialogCallContextService.DEFAULT_KB_ID);
        row.setQuestion(req.getQuestion().trim());
        row.setStandardAnswer(req.getStandardAnswer().trim());
        row.setDataType(req.getDataType() != null ? req.getDataType() : DialogTrainingDataType.MANUAL_CORRECTION);
        row.setWeight(req.getWeight() != null ? req.getWeight()
                : DialogRagRetrievalService.defaultWeightForType(row.getDataType(), dialogRagProperties));
        row.setStatus(req.getStatus() != null ? req.getStatus() : 1);
        row.setSourceCallId(req.getSourceCallId());
        row.setRemark(req.getRemark());
        if (row.getId() == null) {
            dialogTrainingQaMapper.insert(row);
        } else {
            dialogTrainingQaMapper.updateById(row);
        }
        if (row.getStatus() != null && row.getStatus() == 1) {
            dialogRagRetrievalService.indexOne(row);
        } else {
            dialogRagRetrievalService.removeFromIndex(row.getId());
        }
        dialogScriptPackRegistry.reloadFromDb();
        kbAnswerWavCacheService.invalidateQa(row.getId());
        if (StringUtils.hasText(row.getStandardAnswer())) {
            kbAnswerWavCacheService.invalidateKbAnswer(row.getKbId(), row.getStandardAnswer());
        }
        return row;
    }

    /** 保存主线节点（不参与 RAG 向量索引） */
    public DialogTrainingQa saveFlowRow(DialogTrainingQaSaveRequest req, Integer flowOrder) {
        validate(req);
        DialogTrainingQa row;
        if (req.getId() != null) {
            row = dialogTrainingQaMapper.selectById(req.getId());
            if (row == null) {
                throw new BizException("记录不存在");
            }
        } else {
            row = new DialogTrainingQa();
        }
        row.setKbId(req.getKbId() != null ? req.getKbId() : DialogCallContextService.DEFAULT_KB_ID);
        row.setQuestion(req.getQuestion().trim());
        row.setStandardAnswer(req.getStandardAnswer().trim());
        row.setDataType(req.getDataType() != null ? req.getDataType() : DialogTrainingDataType.QUALITY_SAMPLE);
        row.setWeight(req.getWeight() != null ? req.getWeight() : BigDecimal.valueOf(5.0));
        row.setStatus(req.getStatus() != null ? req.getStatus() : 1);
        row.setSourceCallId(req.getSourceCallId());
        row.setRemark(req.getRemark());
        row.setFlowOrder(flowOrder);
        if (row.getId() == null) {
            dialogTrainingQaMapper.insert(row);
        } else {
            dialogTrainingQaMapper.updateById(row);
        }
        dialogScriptPackRegistry.reloadFromDb();
        return row;
    }

    public void delete(Integer id) {
        dialogTrainingQaMapper.deleteById(id);
        dialogRagRetrievalService.removeFromIndex(id);
        dialogScriptPackRegistry.reloadFromDb();
    }

    public void rebuildIndex() {
        dialogRagRetrievalService.rebuildIndex();
    }

    public DialogRagRetrieveResult testRetrieve(String question, Integer kbId) {
        int kid = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        return dialogRagRetrievalService.retrieve(question, kid);
    }

    public Map<String, Object> stats(Integer kbId) {
        int kid = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", dialogRagProperties.isEnabled());
        m.put("kbId", kid);
        m.put("indexSize", dialogRagRetrievalService.indexSizeByKb(kid));
        m.put("total", dialogTrainingQaMapper.selectCount(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kid)
                        .eq(DialogTrainingQa::getStatus, 1)));
        m.put("manualCorrection", countByType(kid, DialogTrainingDataType.MANUAL_CORRECTION));
        m.put("qualitySample", countByType(kid, DialogTrainingDataType.QUALITY_SAMPLE));
        m.put("negative", countByType(kid, DialogTrainingDataType.NEGATIVE));
        m.put("mainFlowSize", dialogScriptPackRegistry.mainFlowSize(kid));
        m.put("fallbackRuleSize", dialogScriptPackRegistry.fallbackRuleSize(kid));
        m.put("directAnswerScore", dialogRagProperties.getDirectAnswerScore());
        m.put("minRetrieveScore", dialogRagProperties.getMinRetrieveScore());
        return m;
    }

    public int batchImport(List<DialogTrainingQaSaveRequest> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (DialogTrainingQaSaveRequest item : items) {
            save(item);
            n++;
        }
        return n;
    }

    public boolean existsQuestion(String question, Integer kbId) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        int kid = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        Long n = dialogTrainingQaMapper.selectCount(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kid)
                        .eq(DialogTrainingQa::getQuestion, question.trim())
                        .eq(DialogTrainingQa::getStatus, 1));
        return n != null && n > 0;
    }

    private long countByType(int kbId, int type) {
        return dialogTrainingQaMapper.selectCount(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kbId)
                        .eq(DialogTrainingQa::getDataType, type)
                        .eq(DialogTrainingQa::getStatus, 1));
    }

    private void validate(DialogTrainingQaSaveRequest req) {
        if (req == null || !StringUtils.hasText(req.getQuestion()) || !StringUtils.hasText(req.getStandardAnswer())) {
            throw new BizException("问题与标准答案不能为空");
        }
        if (req.getQuestion().trim().length() > 500) {
            throw new BizException("问题不超过500字");
        }
        if (req.getStandardAnswer().trim().length() > 2000) {
            throw new BizException("标准答案不超过2000字");
        }
    }

    private Path storeUploadedTelephonyWav(Integer qaId, MultipartFile file) throws IOException {
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
        Path dir = Path.of("./uploads/tts/kb-qa");
        Files.createDirectories(dir);
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.')).toLowerCase()
                : ".wav";
        Path temp = dir.resolve("qa_" + qaId + "_src_" + System.currentTimeMillis() + ext);
        Path out = dir.resolve("qa_" + qaId + "_" + System.currentTimeMillis() + ".wav");
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
