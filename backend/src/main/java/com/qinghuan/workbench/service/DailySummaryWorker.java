package com.qinghuan.workbench.service;

import com.qinghuan.workbench.common.DailySummaryJobStatus;
import com.qinghuan.workbench.dto.summary.DailySummaryJobView;
import com.qinghuan.workbench.entity.AiSummaryJob;
import com.qinghuan.workbench.entity.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class DailySummaryWorker {
    private final DailySummaryJobService jobService;
    private final TaskService taskService;
    private final TaskFingerprintService fingerprintService;
    private final DailySummaryService summaryService;
    private final DailySummaryCacheService cacheService;
    private final int maxAttempts;
    private final List<Long> retryDelaysSeconds;
    private final boolean enabled;
    private final Executor executor;
    private final Semaphore slots;

    @Autowired
    public DailySummaryWorker(
            DailySummaryJobService jobService,
            TaskService taskService,
            TaskFingerprintService fingerprintService,
            DailySummaryService summaryService,
            DailySummaryCacheService cacheService,
            @Qualifier("dailySummaryExecutor") Executor executor,
            @Value("${workbench.daily-summary.worker.enabled:true}") boolean enabled,
            @Value("${workbench.daily-summary.worker.concurrency:2}") int concurrency,
            @Value("${workbench.daily-summary.worker.max-attempts:3}") int maxAttempts,
            @Value("${workbench.daily-summary.worker.retry-delays-seconds:5,20,60}")
            String retryDelays) {
        this(jobService, taskService, fingerprintService, summaryService, cacheService,
                maxAttempts, parseDelays(retryDelays), enabled, Runnable::run,
                new Semaphore(Math.max(1, concurrency)));
    }

    DailySummaryWorker(
            DailySummaryJobService jobService,
            TaskService taskService,
            TaskFingerprintService fingerprintService,
            DailySummaryService summaryService,
            DailySummaryCacheService cacheService,
            int maxAttempts,
            long leaseSeconds,
            List<Long> retryDelaysSeconds) {
        this(jobService, taskService, fingerprintService, summaryService, cacheService,
                maxAttempts, retryDelaysSeconds, true, Runnable::run, new Semaphore(1));
    }

    private DailySummaryWorker(
            DailySummaryJobService jobService,
            TaskService taskService,
            TaskFingerprintService fingerprintService,
            DailySummaryService summaryService,
            DailySummaryCacheService cacheService,
            int maxAttempts,
            List<Long> retryDelaysSeconds,
            boolean enabled,
            Executor executor,
            Semaphore slots) {
        this.jobService = jobService;
        this.taskService = taskService;
        this.fingerprintService = fingerprintService;
        this.summaryService = summaryService;
        this.cacheService = cacheService;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelaysSeconds = retryDelaysSeconds.isEmpty()
                ? List.of(5L)
                : retryDelaysSeconds;
        this.enabled = enabled;
        this.executor = executor;
        this.slots = slots;
    }

    @Scheduled(fixedDelayString = "${workbench.daily-summary.worker.poll-interval-ms:1000}")
    public void poll() {
        if (!enabled || !slots.tryAcquire()) {
            return;
        }
        try {
            AiSummaryJob job = jobService.claimNext();
            if (job == null) {
                slots.release();
                return;
            }
            executor.execute(() -> {
                try {
                    process(job);
                } finally {
                    slots.release();
                }
            });
        } catch (Exception e) {
            slots.release();
            log.error("领取 AI 日报任务失败", e);
        }
    }

    public void runOne() {
        AiSummaryJob job = jobService.claimNext();
        if (job != null) {
            process(job);
        }
    }

    @Scheduled(cron = "${workbench.daily-summary.cleanup-cron:0 0 3 * * *}")
    public void cleanup() {
        try {
            int deleted = jobService.cleanupFinishedBefore(LocalDateTime.now().minusDays(30));
            if (deleted > 0) {
                log.info("清理已结束 AI 日报任务 {} 条", deleted);
            }
        } catch (Exception e) {
            log.error("清理 AI 日报任务失败", e);
        }
    }

    private void process(AiSummaryJob job) {
        List<Task> tasks;
        try {
            tasks = taskService.listByDate(job.getSummaryDate(), job.getOwnerId());
            String currentHash = fingerprintService.fingerprint(tasks);
            if (!job.getInputHash().equals(currentHash)) {
                jobService.supersede(job);
                return;
            }

            if (!summaryService.aiEnabled() || tasks.isEmpty()) {
                finish(job, summaryService.templateSummary(job.getSummaryDate(), tasks),
                        "template", DailySummaryJobStatus.SUCCEEDED, job.getInputHash());
                return;
            }

            String content;
            try {
                content = summaryService.callAi(job.getSummaryDate(), tasks);
            } catch (Exception aiError) {
                handleAiFailure(job, tasks, aiError);
                return;
            }

            List<Task> latestTasks =
                    taskService.listByDate(job.getSummaryDate(), job.getOwnerId());
            String afterAiHash = fingerprintService.fingerprint(latestTasks);
            if (!job.getInputHash().equals(afterAiHash)) {
                jobService.supersede(job);
                return;
            }
            finish(job, content, "ai", DailySummaryJobStatus.SUCCEEDED, afterAiHash);
        } catch (Exception e) {
            log.error("AI 日报任务 {} 执行失败", job.getId(), e);
            jobService.fail(job, e);
        }
    }

    private void handleAiFailure(AiSummaryJob job, List<Task> tasks, Exception error) {
        int attempts = job.getAttemptCount() == null ? 1 : job.getAttemptCount();
        if (attempts < maxAttempts) {
            long delay = retryDelaysSeconds.get(
                    Math.min(attempts - 1, retryDelaysSeconds.size() - 1));
            job.setNextRunAt(LocalDateTime.now().plusSeconds(delay));
            jobService.markRetrying(job, error);
            log.warn("AI 日报任务 {} 第 {} 次执行失败，{} 秒后重试",
                    job.getId(), attempts, delay);
            return;
        }
        finish(job, summaryService.templateSummary(job.getSummaryDate(), tasks),
                "template", DailySummaryJobStatus.DEGRADED, job.getInputHash());
    }

    private void finish(AiSummaryJob job, String content, String source,
                        String status, String hash) {
        if (!jobService.complete(job, content, source, status)) {
            log.info("AI 日报任务 {} 状态已被其他实例更新，跳过缓存写入", job.getId());
            return;
        }
        cacheService.put(job.getOwnerId(),
                new DailySummaryJobView(job.getId(), job.getSummaryDate(), status,
                        source, job.getTaskCount(), content, null),
                hash);
    }

    private static List<Long> parseDelays(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Long::valueOf)
                .filter(value -> value >= 0)
                .toList();
    }
}
