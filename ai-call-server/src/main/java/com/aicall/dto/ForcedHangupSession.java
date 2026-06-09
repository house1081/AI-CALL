package com.aicall.dto;

import lombok.Data;

@Data
public class ForcedHangupSession {
    private long connectedAtEpochSec;
    private int invalidChatRounds;
    private int probeFailures;
    private String fsUuid;
}
