package com.aicall.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 通话对话转写日志：统一打印「客户语音→文字」与「AI 回答」全文。
 */
public final class DialogTranscriptLog {

    private static final Logger log = LoggerFactory.getLogger("com.aicall.dialog");

    private DialogTranscriptLog() {
    }

    public static void userSpeechToText(Integer callRecordId, String fsUuid, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        log.info("[客户语音→文字] {} {} | {}",
                idPart(callRecordId), uuidPart(fsUuid), text.trim());
    }

    public static void aiReply(Integer callRecordId, String fsUuid, String text, String model, Boolean hangup) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        log.info("[AI回答] {} {} model={} hangup={} | {}",
                idPart(callRecordId), uuidPart(fsUuid),
                model != null ? model : "-",
                hangup != null ? hangup : false,
                text.trim());
    }

    public static void opening(Integer callRecordId, String fsUuid, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        log.info("[AI开场白] {} {} | {}",
                idPart(callRecordId), uuidPart(fsUuid), text.trim());
    }

    private static String idPart(Integer callRecordId) {
        return callRecordId != null ? "recordId=" + callRecordId : "recordId=-";
    }

    private static String uuidPart(String fsUuid) {
        return StringUtils.hasText(fsUuid) ? "uuid=" + fsUuid : "uuid=-";
    }
}
