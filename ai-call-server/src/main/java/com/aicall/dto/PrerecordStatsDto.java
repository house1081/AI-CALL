package com.aicall.dto;

import lombok.Data;

@Data
public class PrerecordStatsDto {
    private long highFreqFaqCount;
    private long coldFaqCount;
    private long totalMatchCount;
    private long totalTransferCount;
    private long clipCount;
    private long clipWithWavCount;
}
