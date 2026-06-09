package com.aicall.dto;

import lombok.Data;

@Data
public class SensitiveWordMatch {
    private String word;
    /** violation / sensitive */
    private String wordType;
    private String wordTypeLabel;
}
