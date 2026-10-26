package com.qinghuan.workbench.dto.summary;

import java.time.LocalDate;

public record DailySummaryJobView(
        Long jobId,
        LocalDate date,
        String status,
        String source,
        Integer taskCount,
        String content,
        String errorMessage) {
}
