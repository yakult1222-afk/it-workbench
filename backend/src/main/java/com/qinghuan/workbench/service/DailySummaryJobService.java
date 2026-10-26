package com.qinghuan.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.common.DailySummaryJobStatus;
import com.qinghuan.workbench.dto.summary.DailySummaryJobView;
import com.qinghuan.workbench.entity.AiSummaryJob;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.mapper.AiSummaryJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DailySummaryJobService {
    private final TaskService taskService;
    private final TaskFingerprintService fingerprintService;
    private final DailySummaryCacheService cacheService;
    private final AiSummaryJobMapper jobMapper;

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
