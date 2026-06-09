package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.CallStatus;
import com.aicall.common.DialogTrainingDataType;
import com.aicall.dto.AiChatMessage;
import com.aicall.dto.CallDialogQaPairDto;
import com.aicall.dto.DialogTrainingImportFromCallRequest;
import com.aicall.dto.DialogTrainingQaSaveRequest;
import com.aicall.entity.CallRecord;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.mapper.CallRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从通话记录提取问答并导入训练知识库 */
@Service
@RequiredArgsConstructor
public class DialogTrainingExtractService {

    private final CallRecordMapper callRecordMapper;
    private final CallDialogPersistService callDialogPersistService;
    private final DialogTrainingQaService dialogTrainingQaService;

    public Map<String, Object> extractFromCall(Integer callRecordId) {
        CallRecord record = requireCall(callRecordId);
        String dialogText = callDialogPersistService.getDialogText(callRecordId);
        List<CallDialogQaPairDto> pairs = parseQaPairs(dialogText);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callRecordId", callRecordId);
        m.put("customerPhone", record.getCustomerPhone());
        m.put("callStatus", record.getCallStatus());
        m.put("callDuration", record.getCallDuration());
        m.put("dialogText", dialogText);
        m.put("pairs", pairs);
        m.put("pairCount", pairs.size());
        return m;
    }

    public DialogTrainingQa importFromCall(DialogTrainingImportFromCallRequest req) {
        if (req == null || req.getCallRecordId() == null) {
            throw new BizException("callRecordId 不能为空");
        }
        requireCall(req.getCallRecordId());
        if (!StringUtils.hasText(req.getQuestion()) || !StringUtils.hasText(req.getStandardAnswer())) {
            throw new BizException("问题与标准答案不能为空");
        }
        DialogTrainingQaSaveRequest save = new DialogTrainingQaSaveRequest();
        save.setQuestion(req.getQuestion().trim());
        save.setStandardAnswer(req.getStandardAnswer().trim());
        save.setDataType(req.getDataType() != null ? req.getDataType() : DialogTrainingDataType.MANUAL_CORRECTION);
        save.setWeight(req.getWeight());
        save.setSourceCallId(req.getCallRecordId());
        save.setStatus(1);
        CallRecord record = requireCall(req.getCallRecordId());
        save.setKbId(record.getKbId() != null ? record.getKbId() : DialogCallContextService.DEFAULT_KB_ID);
        String remark = buildRemark(req);
        save.setRemark(remark);
        return dialogTrainingQaService.save(save);
    }

    /**
     * 批量导入优质通话样本：接通且有时长、每轮 user→AI 作为优质样本（跳过与已有问题完全重复的）。
     */
    public Map<String, Object> importQualitySamplesFromCall(Integer callRecordId, boolean skipDuplicate) {
        CallRecord record = requireCall(callRecordId);
        if (record.getCallStatus() == null || record.getCallStatus() != CallStatus.CONNECTED) {
            throw new BizException("仅支持接通通话导入优质样本");
        }
        List<CallDialogQaPairDto> pairs = parseQaPairs(callDialogPersistService.getDialogText(callRecordId));
        int imported = 0;
        int skipped = 0;
        for (CallDialogQaPairDto pair : pairs) {
            Integer kbId = record.getKbId() != null ? record.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
            if (skipDuplicate && dialogTrainingQaService.existsQuestion(pair.getUserText(), kbId)) {
                skipped++;
                continue;
            }
            DialogTrainingImportFromCallRequest req = new DialogTrainingImportFromCallRequest();
            req.setCallRecordId(callRecordId);
            req.setDataType(DialogTrainingDataType.QUALITY_SAMPLE);
            req.setQuestion(pair.getUserText());
            req.setStandardAnswer(pair.getAiText());
            req.setOriginalAiAnswer(pair.getAiText());
            req.setTurnIndex(pair.getTurnIndex());
            importFromCall(req);
            imported++;
        }
        return Map.of("imported", imported, "skipped", skipped, "totalPairs", pairs.size());
    }

    public static List<CallDialogQaPairDto> parseQaPairs(String dialogText) {
        List<AiChatMessage> hist = IntentLevelService.parseDialog(dialogText);
        List<CallDialogQaPairDto> pairs = new ArrayList<>();
        for (int i = 0; i < hist.size() - 1; i++) {
            AiChatMessage cur = hist.get(i);
            AiChatMessage next = hist.get(i + 1);
            if (!"user".equalsIgnoreCase(cur.getRole()) || !"assistant".equalsIgnoreCase(next.getRole())) {
                continue;
            }
            if (!StringUtils.hasText(cur.getContent()) || !StringUtils.hasText(next.getContent())) {
                continue;
            }
            CallDialogQaPairDto p = new CallDialogQaPairDto();
            p.setTurnIndex(pairs.size());
            p.setUserText(cur.getContent().trim());
            p.setAiText(next.getContent().trim());
            pairs.add(p);
        }
        return pairs;
    }

    private CallRecord requireCall(Integer callRecordId) {
        CallRecord record = callRecordMapper.selectById(callRecordId);
        if (record == null) {
            throw new BizException("通话记录不存在");
        }
        return record;
    }

    private static String buildRemark(DialogTrainingImportFromCallRequest req) {
        StringBuilder sb = new StringBuilder("来源通话#").append(req.getCallRecordId());
        if (req.getTurnIndex() != null) {
            sb.append(" 轮次").append(req.getTurnIndex());
        }
        if (StringUtils.hasText(req.getOriginalAiAnswer())
                && !req.getOriginalAiAnswer().trim().equals(
                req.getStandardAnswer() != null ? req.getStandardAnswer().trim() : "")) {
            sb.append(" 原AI:").append(truncate(req.getOriginalAiAnswer(), 120));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
