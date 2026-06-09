package com.aicall.dto;

import lombok.Data;

@Data
public class AiChatMessage {
    /** user / assistant */
    private String role;
    private String content;
}
