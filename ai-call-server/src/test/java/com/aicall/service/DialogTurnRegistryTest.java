package com.aicall.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogTurnRegistryTest {

    @Test
    void latencyPhasesDoNotThrow() {
        DialogTurnRegistry registry = new DialogTurnRegistry();
        registry.register("test-uuid");
        registry.markUserUtteranceEnded("test-uuid", 150);
        registry.markListenPhaseMs("test-uuid", 420);
        registry.markAsrPhaseMs("test-uuid", 280);
        registry.forceUserTurnReady("test-uuid", 150);
        assertTrue(registry.mayAiSpeak("test-uuid"));
        registry.aiTakesFloor("test-uuid");
        assertDoesNotThrow(() -> registry.markAiPlaybackStarted("test-uuid", "test"));
        registry.unregister("test-uuid");
    }
}
