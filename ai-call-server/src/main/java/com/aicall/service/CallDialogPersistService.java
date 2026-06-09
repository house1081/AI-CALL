package com.aicall.service;

import com.aicall.dto.AiChatMessage;
import com.aicall.entity.CallRecord;
import com.aicall.mapper.CallRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 将人机对话逐条写入 call_record.dialog_text，供后台通话记录展示。
 */
@Service
@RequiredArgsConstructor
public class CallDialogPersistService {

    private final CallRecordMapper callRecordMapper;

    public void appendAssistant(Integer callRecordId, String text) {
        append(callRecordId, "【AI】", text);
    }

    public void appendUser(Integer callRecordId, String text) {
        append(callRecordId, "【客户】", text);
    }

    public void appendSystem(Integer callRecordId, String text) {
        append(callRecordId, "【系统】", text);
    }

    private void append(Integer callRecordId, String rolePrefix, String text) {
        if (callRecordId == null || !StringUtils.hasText(text)) {
            return;
        }
        String line = rolePrefix + sanitizeLine(text);
        CallRecord existing = callRecordMapper.selectById(callRecordId);
        if (existing == null) {
            return;
        }
        String merged = StringUtils.hasText(existing.getDialogText())
                ? existing.getDialogText() + "\n" + line
                : line;
        CallRecord upd = new CallRecord();
        upd.setId(callRecordId);
        upd.setDialogText(merged);
        callRecordMapper.updateById(upd);
    }

    public String getDialogText(Integer callRecordId) {
        if (callRecordId == null) {
            return "";
        }
        CallRecord r = callRecordMapper.selectById(callRecordId);
        return r != null && r.getDialogText() != null ? r.getDialogText() : "";
    }

    /** 从已落库的 【客户】/【AI】 行构建大模型历史（整通上下文） */
    public List<AiChatMessage> loadChatHistory(Integer callRecordId) {
        return new ArrayList<>(IntentLevelService.parseDialog(getDialogText(callRecordId)));
    }

    private static String sanitizeLine(String text) {
        return text.trim().replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
    }
}
