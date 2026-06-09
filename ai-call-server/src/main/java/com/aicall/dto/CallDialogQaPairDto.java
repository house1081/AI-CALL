package com.aicall.dto;

import lombok.Data;

/** 从通话记录解析出的问答轮次 */
@Data
public class CallDialogQaPairDto {
    private int turnIndex;
    private String userText;
    private String aiText;
}
