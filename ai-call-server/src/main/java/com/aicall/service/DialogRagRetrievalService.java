package com.aicall.service;

import com.aicall.common.DialogTrainingDataType;
import com.aicall.common.DialogSlotHelper;
import com.aicall.common.ForcedHangupRules;
import com.aicall.config.AiVoiceProperties;
import com.aicall.config.DialogRagProperties;
import com.aicall.dto.DialogRagHitDto;
import com.aicall.dto.DialogRagRetrieveResult;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.mapper.DialogTrainingQaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 对话训练 RAG 检索：热更新向量索引 + 直出/注入/兜底判定 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogRagRetrievalService {

    private final DialogRagProperties dialogRagProperties;
    private final AiVoiceProperties aiVoiceProperties;
    private final LocalDialogRagVectorStore vectorStore;
    private final DashScopeEmbeddingService embeddingService;
    private final DialogTrainingQaMapper dialogTrainingQaMapper;
    private final DialogScriptPackRegistry dialogScriptPackRegistry;

    private volatile boolean indexLoaded;

    @EventListener(ContextRefreshedEvent.class)
    public void loadIndexOnStartup() {
        if (indexLoaded) {
            return;
        }
        rebuildIndex();
    }

    public void rebuildIndex() {
        indexLoaded = false;
        vectorStore.clear();
        List<DialogTrainingQa> rows = dialogTrainingQaMapper.selectList(
                new LambdaQueryWrapper<DialogTrainingQa>().eq(DialogTrainingQa::getStatus, 1));
        int ok = 0;
        for (DialogTrainingQa row : rows) {
            if (indexOne(row)) {
                ok++;
            }
        }
        log.info("[RAG] 向量索引重建完成 入库={}/{}", ok, rows.size());
        dialogScriptPackRegistry.reloadFromDb();
        indexLoaded = true;
    }

    public boolean isIndexReady() {
        return indexLoaded;
    }

    public boolean indexOne(DialogTrainingQa row) {
        if (row == null || row.getId() == null || !StringUtils.hasText(row.getQuestion())) {
            return false;
        }
        String remark = row.getRemark() != null ? row.getRemark().trim() : "";
        if (remark.startsWith("flow:")) {
            return false;
        }
        if (row.getStatus() != null && row.getStatus() != 1) {
            vectorStore.remove(row.getId());
            return false;
        }
        float[] vec = embeddingService.embed(row.getQuestion());
        if (vec.length == 0) {
            return false;
        }
        int kbId = row.getKbId() != null ? row.getKbId() : DialogCallContextService.DEFAULT_KB_ID;
        vectorStore.upsert(row.getId(), kbId, row.getQuestion().trim(),
                row.getStandardAnswer() != null ? row.getStandardAnswer().trim() : "",
                vec, row.getDataType() != null ? row.getDataType() : DialogTrainingDataType.MANUAL_CORRECTION,
                resolveWeight(row));
        return true;
    }

    public void removeFromIndex(Integer id) {
        if (id != null) {
            vectorStore.remove(id);
        }
    }

    public DialogRagRetrieveResult retrieve(String userText) {
        return retrieve(userText, DialogCallContextService.DEFAULT_KB_ID);
    }

    /** 仅关键词匹配（供外呼在身份/槽位快答之前优先命中 FAQ） */
    public DialogRagRetrieveResult tryKeywordDirectAnswer(String userText, int kbId) {
        DialogRagRetrieveResult result = new DialogRagRetrieveResult();
        long start = System.currentTimeMillis();
        if (!dialogRagProperties.isEnabled() || !StringUtils.hasText(userText)) {
            result.setRetrieveMs(System.currentTimeMillis() - start);
            return result;
        }
        return applyKeywordHit(result, userText.trim(), kbId, start);
    }

    public DialogRagRetrieveResult retrieve(String userText, int kbId) {
        DialogRagRetrieveResult result = new DialogRagRetrieveResult();
        long start = System.currentTimeMillis();
        if (!dialogRagProperties.isEnabled() || !StringUtils.hasText(userText)) {
            result.setRetrieveMs(System.currentTimeMillis() - start);
            return result;
        }
        String trimmed = userText.trim();
        if (DialogSlotHelper.shouldBypassRag(trimmed)) {
            result.setRetrieveMs(System.currentTimeMillis() - start);
            log.info("[RAG] 跳过检索（问候/语气词） kb={} user={}", kbId, truncate(trimmed));
            return result;
        }
        DialogRagRetrieveResult keywordResult = applyKeywordHit(result, trimmed, kbId, start);
        if (keywordResult.isDirectAnswer()) {
            return keywordResult;
        }
        float[] queryVec = embeddingService.embed(userText.trim());
        if (queryVec.length == 0) {
            if (dialogRagProperties.isStrictNoMatchFallback() && vectorStore.sizeByKb(kbId) > 0) {
                result.setNoMatchFallback(true);
            }
            result.setRetrieveMs(System.currentTimeMillis() - start);
            return result;
        }
        List<DialogRagHitDto> all = vectorStore.search(queryVec, kbId, Math.max(5, dialogRagProperties.getTopK() * 2));
        List<DialogRagHitDto> positive = new ArrayList<>();
        List<DialogRagHitDto> negative = new ArrayList<>();
        for (DialogRagHitDto h : all) {
            if (h.getDataType() != null && h.getDataType() == DialogTrainingDataType.NEGATIVE) {
                negative.add(h);
            } else if (DialogTrainingDataType.isPositive(h.getDataType())) {
                positive.add(h);
            }
        }
        int k = Math.max(1, dialogRagProperties.getTopK());
        result.setHits(positive.size() <= k ? positive : positive.subList(0, k));
        result.setNegativeHits(negative.size() <= 2 ? negative : negative.subList(0, 2));

        DialogRagHitDto best = result.bestPositive();
        if (best != null && best.getWeightedScore() >= dialogRagProperties.getMinRetrieveScore()) {
            result.setHasPositiveMatch(true);
            if (best.getWeightedScore() >= dialogRagProperties.getDirectAnswerScore()
                    && StringUtils.hasText(best.getStandardAnswer())) {
                result.setDirectAnswer(true);
                result.setDirectAnswerText(best.getStandardAnswer().trim());
            }
        } else if (dialogRagProperties.isStrictNoMatchFallback() && vectorStore.sizeByKb(kbId) > 0) {
            result.setNoMatchFallback(true);
        }
        result.setRetrieveMs(System.currentTimeMillis() - start);
        log.info("[RAG] 检索 kb={} user={} hits={} bestScore={} direct={} ms={}",
                kbId, truncate(userText), result.getHits().size(),
                best != null ? String.format("%.3f", best.getWeightedScore()) : "-",
                result.isDirectAnswer(), result.getRetrieveMs());
        return result;
    }

    /** 注入 LLM 的 RAG 约束块 + 检索到的标准问答 */
    public String buildRagPromptBlock(DialogRagRetrieveResult rag) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【知识库参考】优先参考下方人工训练标准问答；可略作口语化但保持核心信息，禁止编造利率/承诺放款。");
        sb.append("\n【训练风格约束】短句口语、电话外呼适配、礼貌合规，单次回复不超过")
                .append(aiVoiceProperties.getMaxSpeakChars()).append("字，每句必须说完整。");
        if (rag.getNegativeHits() != null && !rag.getNegativeHits().isEmpty()) {
            sb.append("\n【禁止输出（负样本）】以下为用户曾触发的不标准/错误回复类型，严禁类似表述：");
            for (DialogRagHitDto n : rag.getNegativeHits()) {
                sb.append("\n- 错误示例问题：").append(n.getQuestion());
                if (StringUtils.hasText(n.getStandardAnswer())) {
                    sb.append(" → 禁止回复：").append(n.getStandardAnswer());
                }
            }
        }
        if (rag.isHasPositiveMatch() && rag.getHits() != null && !rag.getHits().isEmpty()) {
            sb.append("\n【人工训练标准问答（优先匹配）】");
            int i = 1;
            for (DialogRagHitDto h : rag.getHits()) {
                sb.append("\n").append(i++).append(". 客户问：").append(h.getQuestion())
                        .append("\n   标准答：").append(h.getStandardAnswer())
                        .append(" （").append(h.getDataTypeLabel()).append("，匹配度")
                        .append(String.format("%.2f", h.getWeightedScore())).append("）");
            }
            sb.append("\n请优先选用匹配度最高的标准答，可略作口语化，勿增删核心信息。");
        } else {
            sb.append("\n【无匹配】知识库未命中，请结合本通电话上下文自然作答，禁止编造具体利率或承诺放款。");
        }
        return sb.toString();
    }

    public String noMatchFallbackText() {
        return ForcedHangupRules.knowledgeNoMatchFallbackReply();
    }

    public int indexSize() {
        return vectorStore.size();
    }

    public int indexSizeByKb(int kbId) {
        return vectorStore.sizeByKb(kbId);
    }

    private double resolveWeight(DialogTrainingQa row) {
        if (row.getWeight() != null && row.getWeight().doubleValue() > 0) {
            return row.getWeight().doubleValue();
        }
        int type = row.getDataType() != null ? row.getDataType() : DialogTrainingDataType.MANUAL_CORRECTION;
        return switch (type) {
            case DialogTrainingDataType.MANUAL_CORRECTION -> dialogRagProperties.getManualCorrectionWeight();
            case DialogTrainingDataType.QUALITY_SAMPLE -> dialogRagProperties.getQualitySampleWeight();
            case DialogTrainingDataType.NEGATIVE -> dialogRagProperties.getNegativeSampleWeight();
            default -> 1.0;
        };
    }

    public static BigDecimal defaultWeightForType(int dataType, DialogRagProperties props) {
        return BigDecimal.valueOf(switch (dataType) {
            case DialogTrainingDataType.MANUAL_CORRECTION -> props.getManualCorrectionWeight();
            case DialogTrainingDataType.QUALITY_SAMPLE -> props.getQualitySampleWeight();
            case DialogTrainingDataType.NEGATIVE -> props.getNegativeSampleWeight();
            default -> 1.0;
        });
    }

    private DialogRagRetrieveResult applyKeywordHit(DialogRagRetrieveResult result, String userText, int kbId,
                                                    long start) {
        var keywordHit = dialogScriptPackRegistry.matchKeyword(userText, kbId);
        if (keywordHit != null && StringUtils.hasText(keywordHit.answer())) {
            result.setDirectAnswer(true);
            result.setDirectAnswerText(keywordHit.answer().trim());
            result.setKeywordMatched(true);
            result.setRetrieveMs(System.currentTimeMillis() - start);
            log.info("[RAG] 关键词兜底直出 kb={} user={} ruleId={} ms={}",
                    kbId, truncate(userText), keywordHit.id(), result.getRetrieveMs());
        } else {
            result.setRetrieveMs(System.currentTimeMillis() - start);
        }
        return result;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 32 ? s.substring(0, 32) + "..." : s;
    }
}
