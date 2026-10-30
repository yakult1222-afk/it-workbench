package com.qinghuan.workbench.service;

import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.dto.summary.DailySummaryJobView;
import com.qinghuan.workbench.entity.AiSummaryJob;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.mapper.AiSummaryJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailySummaryJobServiceTest {
    private final LocalDate date = LocalDate.of(2026, 10, 26);
    private TaskService taskService;
    private TaskFingerprintService fingerprint;
    private DailySummaryCacheService cache;
    private AiSummaryJobMapper mapper;
    private DailySummaryJobService service;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        fingerprint = mock(TaskFingerprintService.class);
        cache = mock(DailySummaryCacheService.class);
        mapper = mock(AiSummaryJobMapper.class);
        service = new DailySummaryJobService(taskService, fingerprint, cache, mapper);
    }

    @Test
    void cacheHitReturnsImmediatelyWithoutCreatingJob() {
        List<Task> tasks = List.of(task(7L, date));
        DailySummaryJobView cached = new DailySummaryJobView(
                12L, date, "SUCCEEDED", "ai", 1, "缓存日报", null);
        when(taskService.listByDate(date, 7L)).thenReturn(tasks);
        when(fingerprint.fingerprint(tasks)).thenReturn("hash");
        when(cache.get(7L, date, "hash")).thenReturn(Optional.of(cached));

        DailySummaryJobView result = service.submit(7L, date);

        assertEquals(cached, result);
        verify(mapper, never()).insert(any(AiSummaryJob.class));
    }

    @Test
    void sameFingerprintReusesExistingJob() {
        List<Task> tasks = List.of(task(7L, date));
        AiSummaryJob existing = job(31L, 7L, date, "hash", "QUEUED");
        when(taskService.listByDate(date, 7L)).thenReturn(tasks);
        when(fingerprint.fingerprint(tasks)).thenReturn("hash");
        when(cache.get(7L, date, "hash")).thenReturn(Optional.empty());
        when(mapper.selectOne(any())).thenReturn(existing);

        DailySummaryJobView result = service.submit(7L, date);

        assertEquals(31L, result.jobId());
        verify(mapper, never()).insert(any(AiSummaryJob.class));
    }

    @Test
    void anotherUsersJobIsReportedAsNotFound() {
        when(mapper.selectById(31L)).thenReturn(job(31L, 8L, date, "hash", "QUEUED"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.get(31L, 7L));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    @Test
    void supersededJobWithoutContentIsRequeuedForSameFingerprint() {
        List<Task> tasks = List.of(task(7L, date));
        AiSummaryJob existing = job(31L, 7L, date, "hash", "SUPERSEDED");
        when(taskService.listByDate(date, 7L)).thenReturn(tasks);
        when(fingerprint.fingerprint(tasks)).thenReturn("hash");
        when(cache.get(7L, date, "hash")).thenReturn(Optional.empty());
        when(mapper.selectOne(any())).thenReturn(existing);

        DailySummaryJobView result = service.submit(7L, date);

        assertEquals("QUEUED", result.status());
        verify(mapper).updateById(argThat((AiSummaryJob job) ->
                "QUEUED".equals(job.getStatus()) && job.getAttemptCount() == 0));
    }

    @Test
    void claimNextMarksRunningWithLease() {
        AiSummaryJob due = job(31L, 7L, date, "hash", "QUEUED");
        when(mapper.selectClaimable(any())).thenReturn(due);
        LocalDateTime before = LocalDateTime.now();

        AiSummaryJob claimed = service.claimNext();

        assertEquals("RUNNING", claimed.getStatus());
        assertEquals(1, claimed.getAttemptCount());
        verify(mapper).markRunning(any(), argThat(lease ->
                lease.isAfter(before.plusSeconds(110))), any());
    }

    private static AiSummaryJob job(Long id, Long ownerId, LocalDate date,
                                    String hash, String status) {
        AiSummaryJob job = new AiSummaryJob();
        job.setId(id);
        job.setOwnerId(ownerId);
        job.setSummaryDate(date);
        job.setInputHash(hash);
        job.setStatus(status);
        job.setAttemptCount(0);
        job.setTaskCount(1);
        return job;
    }

    private static Task task(Long ownerId, LocalDate date) {
        Task task = new Task();
        task.setId(1L);
        task.setOwnerId(ownerId);
        task.setTaskDate(date);
        task.setTaskType("审批");
        task.setStatus("未完成");
        return task;
    }
}
