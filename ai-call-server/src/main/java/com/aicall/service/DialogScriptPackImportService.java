package com.aicall.service;

import com.aicall.common.BizException;
import com.aicall.common.DialogTrainingDataType;
import com.aicall.config.DialogRagProperties;
import com.aicall.dto.DialogTrainingQaSaveRequest;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.mapper.DialogTrainingQaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 从 classpath 导入银行贷款标准话术包到指定知识库 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialogScriptPackImportService {

    private static final String PACK_PATH = "dialog-scripts/loan-pack.json";

    private final DialogTrainingQaService dialogTrainingQaService;
    private final DialogTrainingQaMapper dialogTrainingQaMapper;
    private final DialogRagRetrievalService dialogRagRetrievalService;
    private final DialogScriptPackRegistry dialogScriptPackRegistry;
    private final DialogRagProperties dialogRagProperties;
    private final ObjectMapper objectMapper;

    public Map<String, Object> importLoanPack(boolean replaceExisting) {
        return importLoanPack(replaceExisting, DialogCallContextService.DEFAULT_KB_ID);
    }

    public Map<String, Object> importLoanPack(boolean replaceExisting, int kbId) {
        JsonNode root = loadPack();
        if (replaceExisting) {
            dialogTrainingQaMapper.delete(new LambdaQueryWrapper<DialogTrainingQa>()
                    .eq(DialogTrainingQa::getKbId, kbId)
                    .and(w -> w.likeRight(DialogTrainingQa::getRemark, "flow:")
                            .or()
                            .likeRight(DialogTrainingQa::getRemark, "fallback:")));
        }
        int main = 0;
        int fallback = 0;
        JsonNode flows = root.path("mainFlow");
        if (flows.isArray()) {
            int order = 10;
            for (JsonNode n : flows) {
                String step = text(n, "step");
                String script = text(n, "script");
                if (!StringUtils.hasText(step) || !StringUtils.hasText(script)) {
                    continue;
                }
                saveFlowRow(kbId, step, text(n, "scene"), script, order);
                main++;
                order += 10;
            }
        }
        JsonNode backs = root.path("fallbacks");
        if (backs.isArray()) {
            for (JsonNode n : backs) {
                String keywords = text(n, "keywords");
                String answer = text(n, "answer");
                if (!StringUtils.hasText(keywords) || !StringUtils.hasText(answer)) {
                    continue;
                }
                int no = n.path("no").asInt(0);
                String q = no > 0 ? no + "." + keywords : keywords;
                int type = DialogTrainingDataType.MANUAL_CORRECTION;
                if (answer.contains("再见") && (keywords.contains("辱骂") || keywords.contains("捣乱")
                        || keywords.contains("同行"))) {
                    type = DialogTrainingDataType.NEGATIVE;
                }
                saveOrUpdateFallbackRow(kbId, no, q, answer, type, "fallback:" + no, BigDecimal.valueOf(3.5));
                fallback++;
            }
        }
        dialogRagRetrievalService.rebuildIndex();
        dialogScriptPackRegistry.reloadFromDb();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kbId", kbId);
        r.put("mainFlow", main);
        r.put("fallbacks", fallback);
        r.put("indexSize", dialogRagRetrievalService.indexSizeByKb(kbId));
        r.put("packVersion", text(root, "version"));
        return r;
    }

    /** 仅同步 FAQ 兜底条目（按 fallback 编号 upsert，保留已有录音路径） */
    public Map<String, Object> syncFallbacksFromPack(int kbId) {
        JsonNode root = loadPack();
        int synced = 0;
        int inserted = 0;
        int updated = 0;
        JsonNode backs = root.path("fallbacks");
        if (backs.isArray()) {
            for (JsonNode n : backs) {
                String keywords = text(n, "keywords");
                String answer = text(n, "answer");
                if (!StringUtils.hasText(keywords) || !StringUtils.hasText(answer)) {
                    continue;
                }
                int no = n.path("no").asInt(0);
                if (no <= 0) {
                    continue;
                }
                String q = no + "." + keywords;
                int type = DialogTrainingDataType.MANUAL_CORRECTION;
                if (answer.contains("再见") && (keywords.contains("辱骂") || keywords.contains("捣乱")
                        || keywords.contains("同行"))) {
                    type = DialogTrainingDataType.NEGATIVE;
                }
                boolean created = saveOrUpdateFallbackRow(kbId, no, q, answer, type, "fallback:" + no,
                        BigDecimal.valueOf(3.5));
                synced++;
                if (created) {
                    inserted++;
                } else {
                    updated++;
                }
            }
        }
        dialogRagRetrievalService.rebuildIndex();
        dialogScriptPackRegistry.reloadFromDb();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kbId", kbId);
        r.put("synced", synced);
        r.put("inserted", inserted);
        r.put("updated", updated);
        r.put("indexSize", dialogRagRetrievalService.indexSizeByKb(kbId));
        r.put("packVersion", text(root, "version"));
        return r;
    }

    /** 仅同步主线话术文案（按 flow:step upsert） */
    public Map<String, Object> syncMainFlowFromPack(int kbId) {
        JsonNode root = loadPack();
        int synced = 0;
        int inserted = 0;
        int updated = 0;
        JsonNode flows = root.path("mainFlow");
        if (flows.isArray()) {
            int order = 10;
            for (JsonNode n : flows) {
                String step = text(n, "step");
                String script = text(n, "script");
                if (!StringUtils.hasText(step) || !StringUtils.hasText(script)) {
                    continue;
                }
                boolean created = saveOrUpdateFlowRow(kbId, step, text(n, "scene"), script, order);
                synced++;
                if (created) {
                    inserted++;
                } else {
                    updated++;
                }
                order += 10;
            }
        }
        dialogScriptPackRegistry.reloadFromDb();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kbId", kbId);
        r.put("synced", synced);
        r.put("inserted", inserted);
        r.put("updated", updated);
        r.put("packVersion", text(root, "version"));
        return r;
    }

    private void saveFlowRow(int kbId, String step, String scene, String script, int flowOrder) {
        saveOrUpdateFlowRow(kbId, step, scene, script, flowOrder);
    }

    /** @return true 新建，false 更新已有 */
    private boolean saveOrUpdateFlowRow(int kbId, String step, String scene, String script, int flowOrder) {
        String remark = "flow:" + step;
        String question = "[主线" + step + "]" + scene;
        DialogTrainingQa existing = dialogTrainingQaMapper.selectOne(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kbId)
                        .eq(DialogTrainingQa::getRemark, remark)
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setQuestion(trim(question, 500));
            existing.setStandardAnswer(trim(script, 2000));
            existing.setStatus(1);
            dialogTrainingQaMapper.updateById(existing);
            return false;
        }
        DialogTrainingQaSaveRequest req = new DialogTrainingQaSaveRequest();
        req.setKbId(kbId);
        req.setQuestion(trim(question, 500));
        req.setStandardAnswer(trim(script, 2000));
        req.setDataType(DialogTrainingDataType.QUALITY_SAMPLE);
        req.setRemark(remark);
        req.setWeight(BigDecimal.valueOf(5.0));
        req.setStatus(1);
        dialogTrainingQaService.saveFlowRow(req, flowOrder);
        return true;
    }

    private void saveRow(int kbId, String question, String answer, int dataType, String remark, BigDecimal weight) {
        if (dialogTrainingQaService.existsQuestion(question, kbId)) {
            return;
        }
        DialogTrainingQaSaveRequest req = new DialogTrainingQaSaveRequest();
        req.setKbId(kbId);
        req.setQuestion(trim(question, 500));
        req.setStandardAnswer(trim(answer, 2000));
        req.setDataType(dataType);
        req.setRemark(remark);
        req.setWeight(weight);
        req.setStatus(1);
        dialogTrainingQaService.save(req);
    }

    /** @return true 新建，false 更新已有 */
    private boolean saveOrUpdateFallbackRow(int kbId, int no, String question, String answer, int dataType,
                                            String remark, BigDecimal weight) {
        DialogTrainingQa existing = dialogTrainingQaMapper.selectOne(
                new LambdaQueryWrapper<DialogTrainingQa>()
                        .eq(DialogTrainingQa::getKbId, kbId)
                        .eq(DialogTrainingQa::getRemark, remark)
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setQuestion(trim(question, 500));
            existing.setStandardAnswer(trim(answer, 2000));
            existing.setDataType(dataType);
            existing.setWeight(weight);
            existing.setStatus(1);
            dialogTrainingQaMapper.updateById(existing);
            return false;
        }
        DialogTrainingQaSaveRequest req = new DialogTrainingQaSaveRequest();
        req.setKbId(kbId);
        req.setQuestion(trim(question, 500));
        req.setStandardAnswer(trim(answer, 2000));
        req.setDataType(dataType);
        req.setRemark(remark);
        req.setWeight(weight);
        req.setStatus(1);
        dialogTrainingQaService.save(req);
        log.info("[话术包] 新增 FAQ 兜底 no={} kbId={}", no, kbId);
        return true;
    }

    private JsonNode loadPack() {
        try (InputStream in = new ClassPathResource(PACK_PATH).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (Exception e) {
            throw new BizException("话术包文件不存在或格式错误: " + PACK_PATH + " — " + e.getMessage());
        }
    }

    private static String text(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asText("").trim() : "";
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    public void importOnStartupIfEmpty() {
        if (!dialogRagProperties.isLoanPackAutoImport()) {
            dialogScriptPackRegistry.reloadFromDb();
            syncMainFlowOnStartupIfEnabled();
            syncFallbacksOnStartupIfEnabled();
            return;
        }
        int kbId = DialogCallContextService.DEFAULT_KB_ID;
        Long count = dialogTrainingQaMapper.selectCount(
                new LambdaQueryWrapper<DialogTrainingQa>().eq(DialogTrainingQa::getKbId, kbId));
        if (count != null && count > 0) {
            dialogScriptPackRegistry.reloadFromDb();
            syncMainFlowOnStartupIfEnabled();
            syncFallbacksOnStartupIfEnabled();
            return;
        }
        try {
            Map<String, Object> r = importLoanPack(false, kbId);
            log.info("[话术包] 首次启动自动导入完成 {}", r);
        } catch (Exception e) {
            log.warn("[话术包] 自动导入跳过: {}", e.getMessage());
        }
    }

    private void syncMainFlowOnStartupIfEnabled() {
        if (!dialogRagProperties.isLoanPackSyncMainFlowOnStartup()) {
            return;
        }
        try {
            Map<String, Object> r = syncMainFlowFromPack(DialogCallContextService.DEFAULT_KB_ID);
            log.info("[话术包] 主线话术同步完成 {}", r);
        } catch (Exception e) {
            log.warn("[话术包] 主线话术同步跳过: {}", e.getMessage());
        }
    }

    private void syncFallbacksOnStartupIfEnabled() {
        if (!dialogRagProperties.isLoanPackSyncFallbacksOnStartup()) {
            return;
        }
        try {
            Map<String, Object> r = syncFallbacksFromPack(DialogCallContextService.DEFAULT_KB_ID);
            log.info("[话术包] FAQ 兜底同步完成 {}", r);
        } catch (Exception e) {
            log.warn("[话术包] FAQ 兜底同步跳过: {}", e.getMessage());
        }
    }
}
