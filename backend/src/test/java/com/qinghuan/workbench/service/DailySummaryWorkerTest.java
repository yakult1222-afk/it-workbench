package com.qinghuan.workbench.service;

import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.dto.summary.DailySummaryJobView;
import com.qinghuan.workbench.entity.AiSummaryJob;
import com.qinghuan.workbench.entity.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailySummaryWorkerTest {
    private final LocalDate date = LocalDate.of(2026, 10, 26);
    private DailySummaryJobService jobService;
    private TaskService taskService;
    private TaskFingerprintService fingerprint;
    private DailySummaryService summaryService;
    private DailySummaryCacheService cache;
    private DailySummaryWorker worker;

    @BeforeEach
    void setUp() {
        jobService = mock(DailySummaryJobService.class);
        taskService = mock(TaskService.class);
        fingerprint = mock(TaskFingerprintService.class);
        summaryService = mock(DailySummaryService.class);
        cache = mock(DailySummaryCacheService.class);
        worker = new DailySummaryWorker(
                jobService, taskService, fingerprint, summaryService, cache,
                3, 120, List.of(5L, 20L, 60L));
    }

    @Test
    void successfulAiResultIsStoredAndCached() throws Exception {
        AiSummaryJob job = runningJob("hash", 1);
        List<Task> tasks = List.of(task("完成", "修复打印机"));
        when(jobService.claimNext()).thenReturn(job);
        when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId())).thenReturn(tasks);
        when(fingerprint.fingerprint(tasks)).thenReturn("hash");
        when(summaryService.aiEnabled()).thenReturn(true);
        when(summaryService.callAi(job.getSummaryDate(), tasks)).thenReturn("AI 日报");
        when(jobService.complete(job, "AI 日报", "ai", "SUCCEEDED")).thenReturn(true);

        worker.runOne();

        verify(jobService).complete(job, "AI 日报", "ai", "SUCCEEDED");
        verify(cache).put(eq(job.getOwnerId()), any(DailySummaryJobView.class), eq("hash"));
    }

    @Test
    void changedFingerprintBeforeAiSupersedesJobWithoutCaching() {
        AiSummaryJob job = runningJob("old-hash", 1);
        List<Task> tasks = List.of(task("完成", "修复打印机"));
        when(jobService.claimNext()).thenReturn(job);
        when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId())).thenReturn(tasks);
        when(fingerprint.fingerprint(tasks)).thenReturn("new-hash");

        worker.runOne();

        verify(jobService).supersede(job);
        verify(cache, never()).put(any(), any(), anyString());
    }

    @Test
    void changedFingerprintDuringAiSupersedesWithoutWritingStaleCache() throws Exception {
        TaskFingerprintService realFingerprint = new TaskFingerprintService();
        List<Task> beforeAi = List.of(task("完成", "修复打印机"));
        List<Task> afterAi = List.of(task("未完成", "修复打印机"));
        String initialHash = realFingerprint.fingerprint(beforeAi);
        AiSummaryJob job = runningJob(initialHash, 1);
        DailySummaryWorker realWorker = new DailySummaryWorker(
                jobService, taskService, realFingerprint, summaryService, cache,
                3, 120, List.of(5L, 20L, 60L));
        when(jobService.claimNext()).thenReturn(job);
        when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId()))
                .thenReturn(beforeAi, afterAi);
        when(summaryService.aiEnabled()).thenReturn(true);
        when(summaryService.callAi(job.getSummaryDate(), beforeAi)).thenReturn("过期 AI 日报");

        realWorker.runOne();

        verify(jobService).supersede(job);
        verify(jobService, never()).complete(any(), anyString(), anyString(), anyString());
        verify(cache, never()).put(any(), any(), anyString());
    }

    @Test
    void missingAiConfigurationCompletesWithTemplate() {
        AiSummaryJob job = runningJob("hash", 1);
        List<Task> tasks = List.of(task("完成", "修复打印机"));
        when(jobService.claimNext()).thenReturn(job);
        when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId())).thenReturn(tasks);
        when(fingerprint.fingerprint(tasks)).thenReturn("hash");
        when(summaryService.aiEnabled()).thenReturn(false);
        when(summaryService.templateSummary(job.getSummaryDate(), tasks)).thenReturn("模板日报");
        when(jobService.complete(job, "模板日报", "template", "SUCCEEDED")).thenReturn(true);

        worker.runOne();

        verify(jobService).complete(job, "模板日报", "template", "SUCCEEDED");
    }

    @Test
    void transientAiFailureSchedulesRetry() throws Exception {
        AiSummaryJob job = runningJob("hash", 1);
        List<Task> tasks = List.of(task("完成", "修复打印机"));
        RuntimeException error = new RuntimeException("AI unavailable");
        when(jobService.claimNext()).thenReturn(job);
        when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId())).thenReturn(tasks);
        when(fingerprint.fingerprint(tasks)).thenReturn("hash");
        when(summaryService.aiEnabled()).thenReturn(true);
        when(summaryService.callAi(job.getSummaryDate(), tasks)).thenThrow(error);

        worker.runOne();

        verify(jobService).markRetrying(job, error);
        verify(jobService, never()).complete(any(), anyString(), anyString(), anyString());
    }

    @Test
    void exhaustedAiRetriesProduceDegradedTemplate() throws Exception {
        AiSummaryJob job = runningJob("hash", 3);
        List<Task> tasks = List.of(task("完成", "修复打印机"));
        when(jobService.claimNext()).thenReturn(job);
        when(taskService.listByDate(any(), anyLong())).thenReturn(tasks);
        when(fingerprint.fingerprint(tasks)).thenReturn("hash");
        when(summaryService.aiEnabled()).thenReturn(true);
        when(summaryService.callAi(any(), eq(tasks))).thenThrow(new RuntimeException("boom"));
        when(summaryService.templateSummary(any(), eq(tasks))).thenReturn("模板日报");
        when(jobService.complete(job, "模板日报", "template", "DEGRADED")).thenReturn(true);

        worker.runOne();

        verify(jobService).complete(job, "模板日报", "template", "DEGRADED");
        verify(cache).put(eq(job.getOwnerId()), any(DailySummaryJobView.class), eq("hash"));
    }

    @Test
    void unexpectedProgramErrorMarksJobFailed() {
        AiSummaryJob job = runningJob("hash", 1);
        when(jobService.claimNext()).thenReturn(job);
        when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId()))
                .thenThrow(new IllegalStateException("serialization failed"));

        worker.runOne();

        verify(jobService).fail(eq(job), any(IllegalStateException.class));
    }

    private AiSummaryJob runningJob(String hash, int attempt) {
        AiSummaryJob job = new AiSummaryJob();
        job.setId(31L);
        job.setOwnerId(7L);
        job.setSummaryDate(date);
        job.setInputHash(hash);
        job.setStatus("RUNNING");
        job.setAttemptCount(attempt);
        job.setTaskCount(1);
        return job;
    }

    private Task task(String status, String description) {
        Task task = new Task();
        task.setId(1L);
        task.setOwnerId(7L);
        task.setTaskDate(date);
        task.setTaskType("硬件问题");
        task.setDescription(description);
        task.setStatus(status);
        return task;
    }
}
