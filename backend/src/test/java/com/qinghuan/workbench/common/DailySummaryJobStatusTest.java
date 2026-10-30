package com.qinghuan.workbench.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailySummaryJobStatusTest {
    @Test
    void activeStatusesRepresentJobsThatStillNeedPolling() {
        assertTrue(DailySummaryJobStatus.isActive("QUEUED"));
        assertTrue(DailySummaryJobStatus.isActive("RUNNING"));
        assertTrue(DailySummaryJobStatus.isActive("RETRYING"));
        assertFalse(DailySummaryJobStatus.isActive("SUCCEEDED"));
        assertFalse(DailySummaryJobStatus.isActive("DEGRADED"));
        assertFalse(DailySummaryJobStatus.isActive("FAILED"));
        assertFalse(DailySummaryJobStatus.isActive("SUPERSEDED"));
    }

    @Test
    void terminalStatusesStopPolling() {
        assertTrue(DailySummaryJobStatus.isTerminal("SUCCEEDED"));
        assertTrue(DailySummaryJobStatus.isTerminal("DEGRADED"));
        assertTrue(DailySummaryJobStatus.isTerminal("FAILED"));
        assertTrue(DailySummaryJobStatus.isTerminal("SUPERSEDED"));
        assertFalse(DailySummaryJobStatus.isTerminal("QUEUED"));
    }
}
