package com.qinghuan.workbench.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 当日统计
 */
@Service
public class StatsService {

    private final TaskService taskService;

    public StatsService(TaskService taskService) {
        this.taskService = taskService;
    }

    public Map<String, Object> todayStats() {
        List<com.qinghuan.workbench.entity.Task> tasks = taskService.listToday();
        List<com.qinghuan.workbench.entity.Task> completed = tasks.stream()
                .filter(t -> "完成".equals(t.getStatus())).toList();
        // 未完成模块包含「未完成」与「延期」两类未办结任务
        List<com.qinghuan.workbench.entity.Task> uncompleted = tasks.stream()
                .filter(t -> !"完成".equals(t.getStatus())).toList();

        int total = tasks.size();
        int done = completed.size();
        // 完成率 = 完成任务数 / 当日全部任务数（无任务时为 0）
        double rate = total == 0 ? 0 : Math.round(done * 1000.0 / total) / 10.0;

        return Map.of(
                "date", LocalDate.now().toString(),
                "total", total,
                "completedCount", done,
                "uncompletedCount", uncompleted.size(),
                "completionRate", rate,
                "completedList", completed,
                "uncompletedList", uncompleted
        );
    }
}
