package com.qinghuan.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.common.DailySummaryJobStatus;
import com.qinghuan.workbench.dto.summary.DailySummaryJobView;
import com.qinghuan.workbench.entity.AiSummaryJob;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.mapper.AiSummaryJobMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class DailySummaryJobService {
    private final TaskService taskService;
    private final TaskFingerprintService fingerprintService;
    private final DailySummaryCacheService cacheService;
    private final AiSummaryJobMapper jobMapper;
    private final long leaseSeconds;

    @Autowired
    public DailySummaryJobService(
            TaskService taskService,
            TaskFingerprintService fingerprintService,
            DailySummaryCacheService cacheService,
            AiSummaryJobMapper jobMapper,
            @Value("${workbench.daily-summary.worker.lease-seconds:120}") long leaseSeconds) {
        this.taskService = taskService;
        this.fingerprintService = fingerprintService;
        this.cacheService = cacheService;
        this.jobMapper = jobMapper;
        this.leaseSeconds = leaseSeconds;
    }

    DailySummaryJobService(
            TaskService taskService,
            TaskFingerprintService fingerprintService,
            DailySummaryCacheService cacheService,
            AiSummaryJobMapper jobMapper) {
        this(taskService, fingerprintService, cacheService, jobMapper, 120);
    }

    public DailySummaryJobView submit(Long ownerId, LocalDate date) {
        List<Task> tasks = taskService.listByDate(date, ownerId);
        String hash = fingerprintService.fingerprint(tasks);
        Optional<DailySummaryJobView> cached = cacheService.get(ownerId, date, hash);
        if (cached.isPresent()) {
            return cached.get();
        }

        AiSummaryJob existing = findByInput(ownerId, date, hash);
        if (existing != null) {
            return reuseOrRequeue(existing, tasks.size());
        }

        AiSummaryJob job = new AiSummaryJob();
        job.setOwnerId(ownerId);
        job.setSummaryDate(date);
        job.setInputHash(hash);
        job.setStatus(DailySummaryJobStatus.QUEUED);
        job.setAttemptCount(0);
        job.setNextRunAt(LocalDateTime.now());
        job.setTaskCount(tasks.size());
        try {
            jobMapper.insert(job);
            return toView(job);
        } catch (DuplicateKeyException e) {
            AiSummaryJob winner = findByInput(ownerId, date, hash);
            if (winner == null) {
                throw e;
            }
            return reuseOrRequeue(winner, tasks.size());
        }
    }

    public DailySummaryJobView get(Long jobId, Long ownerId) {
        AiSummaryJob job = jobMapper.selectById(jobId);
        if (job == null || !ownerId.equals(job.getOwnerId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "日报任务不存在");
        }
        return toView(job);
    }

    @Transactional
    public AiSummaryJob claimNext() {
        LocalDateTime now = LocalDateTime.now();
        AiSummaryJob job = jobMapper.selectClaimable(now);
        if (job == null) {
            return null;
        }
        int attemptCount = job.getAttemptCount() == null ? 1 : job.getAttemptCount() + 1;
        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
        jobMapper.markRunning(job.getId(), leaseUntil, now);
        job.setStatus(DailySummaryJobStatus.RUNNING);
        job.setAttemptCount(attemptCount);
        job.setLeaseUntil(leaseUntil);
        return job;
    }

    @Transactional
    public void markRetrying(AiSummaryJob job, Throwable error) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRunAt = job.getNextRunAt() == null
                ? now.plusSeconds(5)
                : job.getNextRunAt();
        String message = errorMessage(error);
        jobMapper.markRetrying(job.getId(), nextRunAt, message, now);
        job.setStatus(DailySummaryJobStatus.RETRYING);
        job.setNextRunAt(nextRunAt);
        job.setLeaseUntil(null);
        job.setErrorMessage(message);
    }

    @Transactional
    public boolean complete(AiSummaryJob job, String content, String source, String status) {
        LocalDateTime finishedAt = LocalDateTime.now();
        int updated = jobMapper.markComplete(job.getId(), status, content, source, finishedAt);
        if (updated == 0) {
            return false;
        }
        job.setStatus(status);
        job.setContent(content);
        job.setSource(source);
        job.setErrorMessage(null);
        job.setLeaseUntil(null);
        job.setFinishedAt(finishedAt);
        return true;
    }

    @Transactional
    public void supersede(AiSummaryJob job) {
        terminate(job, DailySummaryJobStatus.SUPERSEDED, "任务内容已变化");
    }

    @Transactional
    public void fail(AiSummaryJob job, Exception error) {
        terminate(job, DailySummaryJobStatus.FAILED, errorMessage(error));
    }

    public int cleanupFinishedBefore(LocalDateTime before) {
        return jobMapper.deleteFinishedBefore(before);
    }

    private DailySummaryJobView reuseOrRequeue(AiSummaryJob job, int taskCount) {
        if (DailySummaryJobStatus.FAILED.equals(job.getStatus())
                || DailySummaryJobStatus.SUPERSEDED.equals(job.getStatus())) {
            job.setStatus(DailySummaryJobStatus.QUEUED);
            job.setAttemptCount(0);
            job.setNextRunAt(LocalDateTime.now());
            job.setTaskCount(taskCount);
            job.setContent(null);
            job.setSource(null);
            job.setErrorMessage(null);
            job.setLeaseUntil(null);
            job.setFinishedAt(null);
            jobMapper.updateById(job);
        }
        return toView(job);
    }

    private AiSummaryJob findByInput(Long ownerId, LocalDate date, String hash) {
        return jobMapper.selectOne(new LambdaQueryWrapper<AiSummaryJob>()
                .eq(AiSummaryJob::getOwnerId, ownerId)
                .eq(AiSummaryJob::getSummaryDate, date)
                .eq(AiSummaryJob::getInputHash, hash));
    }

    private void terminate(AiSummaryJob job, String status, String message) {
        LocalDateTime finishedAt = LocalDateTime.now();
        jobMapper.markTerminal(job.getId(), status, message, finishedAt);
        job.setStatus(status);
        job.setErrorMessage(message);
        job.setLeaseUntil(null);
        job.setFinishedAt(finishedAt);
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        String safe = message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
        return safe.length() <= 1000 ? safe : safe.substring(0, 1000);
    }

    private DailySummaryJobView toView(AiSummaryJob job) {
        return new DailySummaryJobView(
                job.getId(),
                job.getSummaryDate(),
                job.getStatus(),
                job.getSource(),
                job.getTaskCount(),
                job.getContent(),
                job.getErrorMessage());
    }
}
