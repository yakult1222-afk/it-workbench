package com.qinghuan.workbench.common;

import java.util.Set;

public final class DailySummaryJobStatus {
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String RETRYING = "RETRYING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String DEGRADED = "DEGRADED";
    public static final String FAILED = "FAILED";
    public static final String SUPERSEDED = "SUPERSEDED";

    private static final Set<String> ACTIVE = Set.of(QUEUED, RUNNING, RETRYING);
    private static final Set<String> TERMINAL =
            Set.of(SUCCEEDED, DEGRADED, FAILED, SUPERSEDED);

    private DailySummaryJobStatus() {
    }

    public static boolean isActive(String status) {
        return ACTIVE.contains(status);
    }

    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }
}
