package com.aicall.controller.admin;

import com.aicall.common.Result;
import com.aicall.dto.DialogRagRetrieveResult;
import com.aicall.dto.DialogTrainingQaSaveRequest;
import com.aicall.dto.MainFlowStepDto;
import com.aicall.dto.MainFlowStepSaveRequest;
import com.aicall.entity.DialogKnowledgeBase;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.dto.DialogTrainingImportFromCallRequest;
import com.aicall.service.DialogCallContextService;
import com.aicall.service.DialogKnowledgeBaseService;
import com.aicall.service.DialogMainFlowAdminService;
import com.aicall.service.DialogScriptPackImportService;
import com.aicall.service.DialogTrainingExtractService;
import com.aicall.service.DialogTrainingQaService;
import com.aicall.dto.DialogVoiceTrainStartResponse;
import com.aicall.dto.DialogVoiceTrainTurnResponse;
import com.aicall.service.DialogVoiceTrainingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 对话训练数据管理（RAG 热更新，按知识库隔离） */
@RestController
@RequestMapping("/api/admin/dialog-training")
@RequiredArgsConstructor
public class AdminDialogTrainingController {

    private final DialogTrainingQaService dialogTrainingQaService;
    private final DialogTrainingExtractService dialogTrainingExtractService;
    private final DialogScriptPackImportService dialogScriptPackImportService;
    private final DialogKnowledgeBaseService dialogKnowledgeBaseService;
    private final DialogMainFlowAdminService dialogMainFlowAdminService;
    private final DialogVoiceTrainingService dialogVoiceTrainingService;

    @GetMapping("/main-flow")
    public Result<List<MainFlowStepDto>> listMainFlow(
            @RequestParam(required = false) Integer kbId) {
        int kid = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        return Result.ok(dialogMainFlowAdminService.listSteps(kid));
    }

    @GetMapping("/main-flow/summary")
    public Result<Map<String, Object>> mainFlowSummary(@RequestParam(required = false) Integer kbId) {
        int kid = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        return Result.ok(dialogMainFlowAdminService.summary(kid));
    }

    @PostMapping("/main-flow/save")
    public Result<MainFlowStepDto> saveMainFlow(@RequestBody MainFlowStepSaveRequest req) {
        return Result.ok(dialogMainFlowAdminService.saveStep(req));
    }

    @PostMapping("/main-flow/reorder")
    public Result<Void> reorderMainFlow(@RequestBody Map<String, Object> body) {
        Integer kbId = body.get("kbId") != null ? Integer.valueOf(body.get("kbId").toString()) : null;
        int kid = kbId != null ? kbId : DialogCallContextService.DEFAULT_KB_ID;
        @SuppressWarnings("unchecked")
        List<Integer> ids = (List<Integer>) body.get("orderedIds");
        dialogMainFlowAdminService.reorderSteps(kid, ids);
        return Result.ok();
    }

    @DeleteMapping("/main-flow/{id:\\d+}")
    public Result<Void> deleteMainFlow(@PathVariable Integer id) {
        dialogMainFlowAdminService.deleteStep(id);
        return Result.ok();
    }

    @GetMapping("/knowledge-bases")
    public Result<List<DialogKnowledgeBase>> knowledgeBases(
            @RequestParam(required = false) Integer tenantId,
            @RequestParam(required = false) Integer status) {
        return Result.ok(dialogKnowledgeBaseService.list(tenantId, status));
    }

    @PostMapping("/knowledge-bases/save")
    public Result<DialogKnowledgeBase> saveKnowledgeBase(@RequestBody DialogKnowledgeBase kb) {
        return Result.ok(dialogKnowledgeBaseService.save(kb));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(required = false) Integer kbId) {
        return Result.ok(dialogTrainingQaService.stats(kbId));
    }

    @GetMapping("/list")
    public Result<List<DialogTrainingQa>> list(
            @RequestParam(required = false) Integer kbId,
            @RequestParam(required = false) Integer dataType,
            @RequestParam(required = false) Integer status) {
        return Result.ok(dialogTrainingQaService.list(kbId, dataType, status));
    }

    @GetMapping("/{id:\\d+}")
    public Result<DialogTrainingQa> get(@PathVariable Integer id) {
        return Result.ok(dialogTrainingQaService.get(id));
    }

    @PostMapping("/save")
    public Result<DialogTrainingQa> save(@RequestBody DialogTrainingQaSaveRequest req) {
        return Result.ok(dialogTrainingQaService.save(req));
    }

    @PostMapping("/{id:\\d+}/upload-audio")
    public Result<Map<String, Object>> uploadAnswerAudio(
            @PathVariable Integer id,
            @RequestParam("file") MultipartFile file) throws IOException {
        DialogTrainingQa row = dialogTrainingQaService.uploadAnswerAudio(id, file);
        Map<String, Object> body = new HashMap<>();
        body.put("id", row.getId());
        body.put("answerWavPath", row.getAnswerWavPath());
        body.put("audioUrl", DialogTrainingQaService.answerAudioUrl(row));
        return Result.ok(body);
    }

    @DeleteMapping("/{id:\\d+}")
    public Result<Void> delete(@PathVariable Integer id) {
        dialogTrainingQaService.delete(id);
        return Result.ok();
    }

    @PostMapping("/rebuild-index")
    public Result<Void> rebuildIndex() {
        dialogTrainingQaService.rebuildIndex();
        return Result.ok();
    }

    @PostMapping("/retrieve-test")
    public Result<DialogRagRetrieveResult> retrieveTest(@RequestBody Map<String, Object> body) {
        String question = body != null && body.get("question") != null ? body.get("question").toString() : null;
        Integer kbId = body != null && body.get("kbId") != null ? Integer.valueOf(body.get("kbId").toString()) : null;
        return Result.ok(dialogTrainingQaService.testRetrieve(question, kbId));
    }

    @PostMapping("/batch-import")
    public Result<Map<String, Object>> batchImport(@RequestBody List<DialogTrainingQaSaveRequest> items) {
        int n = dialogTrainingQaService.batchImport(items);
        return Result.ok(Map.of("imported", n));
    }

    @GetMapping("/from-call/{callRecordId}")
    public Result<Map<String, Object>> extractFromCall(@PathVariable Integer callRecordId) {
        return Result.ok(dialogTrainingExtractService.extractFromCall(callRecordId));
    }

    @PostMapping("/import-from-call")
    public Result<DialogTrainingQa> importFromCall(@RequestBody DialogTrainingImportFromCallRequest req) {
        return Result.ok(dialogTrainingExtractService.importFromCall(req));
    }

    @PostMapping("/import-quality-from-call/{callRecordId}")
    public Result<Map<String, Object>> importQualityFromCall(
            @PathVariable Integer callRecordId,
            @RequestParam(defaultValue = "true") boolean skipDuplicate) {
        return Result.ok(dialogTrainingExtractService.importQualitySamplesFromCall(callRecordId, skipDuplicate));
    }

    @PostMapping("/import-loan-pack")
    public Result<Map<String, Object>> importLoanPack(
            @RequestParam(defaultValue = "false") boolean replaceExisting,
            @RequestParam(required = false) Integer kbId) {
        int kid = kbId != null ? kbId : com.aicall.service.DialogCallContextService.DEFAULT_KB_ID;
        return Result.ok(dialogScriptPackImportService.importLoanPack(replaceExisting, kid));
    }

    /** 从 classpath 话术包同步 FAQ 兜底（新增/更新关键词与话术，不删已有录音） */
    @PostMapping("/sync-loan-pack-fallbacks")
    public Result<Map<String, Object>> syncLoanPackFallbacks(
            @RequestParam(required = false) Integer kbId) {
        int kid = kbId != null ? kbId : com.aicall.service.DialogCallContextService.DEFAULT_KB_ID;
        return Result.ok(dialogScriptPackImportService.syncFallbacksFromPack(kid));
    }

    /** 对话训练：语音+文字输入，模式由模型配置外呼模式决定 */
    @PostMapping("/voice/start")
    public Result<DialogVoiceTrainStartResponse> voiceTrainStart(
            @RequestParam(required = false) Integer kbId) {
        return Result.ok(dialogVoiceTrainingService.start(kbId));
    }

    @PostMapping("/voice/turn")
    public Result<DialogVoiceTrainTurnResponse> voiceTrainTurn(
            @RequestParam String sessionId,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(required = false) Boolean businessProbeThisTurn) throws Exception {
        return Result.ok(dialogVoiceTrainingService.voiceTurn(sessionId, audio, businessProbeThisTurn));
    }

    @PostMapping("/voice/text-turn")
    public Result<DialogVoiceTrainTurnResponse> voiceTrainTextTurn(@RequestBody Map<String, Object> body)
            throws Exception {
        return Result.ok(dialogVoiceTrainingService.textTurn(
                (String) body.get("sessionId"),
                (String) body.get("userText"),
                parseBoolean(body.get("businessProbeThisTurn"))));
    }

    private static Boolean parseBoolean(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString());
    }

    @PostMapping("/voice/end")
    public Result<Void> voiceTrainEnd(@RequestParam String sessionId) {
        dialogVoiceTrainingService.endSession(sessionId);
        return Result.ok();
    }

    /** 最近训练会话列表（全量落盘记录） */
    @GetMapping("/voice/records")
    public Result<List<Map<String, Object>>> voiceTrainRecords(
            @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(dialogVoiceTrainingService.listRecentRecords(limit));
    }

    /** 单次训练完整事件流（turns.jsonl 解析结果） */
    @GetMapping("/voice/records/{sessionId}")
    public Result<List<Map<String, Object>>> voiceTrainRecordDetail(@PathVariable String sessionId) {
        return Result.ok(dialogVoiceTrainingService.loadRecordEvents(sessionId));
    }
}
