package com.aicall.dto;

import lombok.Data;

@Data
public class PrerecordMiningResultDto {
    private int scannedRecords;
    private int extractedUtterances;
    private int newFaqs;
    private int updatedFaqs;
    private int highFreqCount;
    private int coldCount;
}
