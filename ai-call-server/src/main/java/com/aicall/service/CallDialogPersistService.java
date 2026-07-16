package com.aicall.service;

import com.aicall.dto.AiChatMessage;
import com.aicall.entity.CallRecord;
import com.aicall.mapper.CallRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将人机对话逐条写入 call_record.dialog_text。
 * 通话进行中先写内存缓冲，结束时一次性落库，避免每轮 SELECT+UPDATE。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallDialogPersistService {

    private final CallRecordMapper callRecordMapper;
    private final ConcurrentHashMap<Integer, StringBuilder> liveBuffers = new ConcurrentHashMap<>();

    public void bindCall(Integer callRecordId) {
        if (callRecordId == null) {
            return;
        }
        liveBuffers.computeIfAbsent(callRecordId, id -> {
            CallRecord existing = callRecordMapper.selectById(id);
            StringBuilder sb = new StringBuilder(512);
            if (existing != null && StringUtils.hasText(existing.getDialogText())) {
                sb.append(existing.getDialogText().trim());
            }
            return sb;
        });
    }

    public void unbindCall(Integer callRecordId) {
        if (callRecordId == null) {
            return;
        }
        liveBuffers.remove(callRecordId);
    }

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
        StringBuilder sb = liveBuffers.computeIfAbsent(callRecordId, this::loadBufferFromDb);
        synchronized (sb) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
    }

    /** 通话结束时将内存缓冲写入 DB（训练 sim 负 ID 不落库） */
    public void flushToDb(Integer callRecordId) {
        if (callRecordId == null || callRecordId < 0) {
            return;
        }
        StringBuilder sb = liveBuffers.get(callRecordId);
        if (sb == null) {
            return;
        }
        String merged;
        synchronized (sb) {
            merged = sb.toString();
        }
        if (!StringUtils.hasText(merged)) {
            return;
        }
        CallRecord upd = new CallRecord();
        upd.setId(callRecordId);
        upd.setDialogText(merged);
        callRecordMapper.updateById(upd);
    }

    public String getDialogText(Integer callRecordId) {
        if (callRecordId == null) {
            return "";
        }
        StringBuilder sb = liveBuffers.get(callRecordId);
        if (sb != null) {
            synchronized (sb) {
                return sb.toString();
            }
        }
        CallRecord r = callRecordMapper.selectById(callRecordId);
        return r != null && r.getDialogText() != null ? r.getDialogText() : "";
    }

    /** 从已落库的 【客户】/【AI】 行构建大模型历史（整通上下文） */
    public List<AiChatMessage> loadChatHistory(Integer callRecordId) {
        return new ArrayList<>(IntentLevelService.parseDialog(getDialogText(callRecordId)));
    }

    private StringBuilder loadBufferFromDb(Integer callRecordId) {
        CallRecord existing = callRecordMapper.selectById(callRecordId);
        StringBuilder sb = new StringBuilder(512);
        if (existing != null && StringUtils.hasText(existing.getDialogText())) {
            sb.append(existing.getDialogText().trim());
        }
        return sb;
    }

    private static String sanitizeLine(String text) {
        return text.trim().replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
    }
}
