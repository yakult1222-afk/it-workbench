package com.qinghuan.workbench.service;

import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatsService {
    private final TaskService taskService;

    public Map<String, Object> todayStats(LoginUser user) {
        List<Task> tasks = taskService.listForStats(LocalDate.now(), user);
        List<Task> completed = tasks.stream()
                .filter(t -> "完成".equals(t.getStatus())).toList();
        List<Task> uncompleted = tasks.stream()
                .filter(t -> !"完成".equals(t.getStatus())).toList();

        int total = tasks.size();
        int done = completed.size();
        double rate = total == 0 ? 0 : Math.round(done * 1000.0 / total) / 10.0;

        return Map.of(
                "date", LocalDate.now().toString(),
                "scope", user.hasPermission("task:view:all") ? "all" : "self",
                "total", total,
                "completedCount", done,
                "uncompletedCount", uncompleted.size(),
                "completionRate", rate,
                "completedList", completed,
                "uncompletedList", uncompleted
        );
    }
}
