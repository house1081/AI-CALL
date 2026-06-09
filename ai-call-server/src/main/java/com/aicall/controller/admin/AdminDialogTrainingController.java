package com.aicall.controller.admin;

import com.aicall.common.Result;
import com.aicall.dto.DialogRagRetrieveResult;
import com.aicall.dto.DialogTrainingQaSaveRequest;
import com.aicall.entity.DialogKnowledgeBase;
import com.aicall.entity.DialogTrainingQa;
import com.aicall.dto.DialogTrainingImportFromCallRequest;
import com.aicall.service.DialogKnowledgeBaseService;
import com.aicall.service.DialogScriptPackImportService;
import com.aicall.service.DialogTrainingExtractService;
import com.aicall.service.DialogTrainingQaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
}
