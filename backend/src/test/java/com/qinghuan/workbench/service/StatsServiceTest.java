package com.qinghuan.workbench.service;

import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.security.LoginUser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatsServiceTest {
    private final TaskService taskService = mock(TaskService.class);
    private final StatsService service = new StatsService(taskService);

    @Test
    void statsUseTasksReturnedForCurrentUserScope() {
        LoginUser user = loginUser(7L, "task:self:view");
        when(taskService.listForStats(any(), eq(user))).thenReturn(List.of(
                task(1L, 7L, "完成"), task(2L, 7L, "未完成"), task(3L, 7L, "延期")));

        Map<String, Object> result = service.todayStats(user);

        assertEquals(3, result.get("total"));
        assertEquals(1, result.get("completedCount"));
        assertEquals(2, result.get("uncompletedCount"));
        assertEquals(33.3, result.get("completionRate"));
        assertEquals("self", result.get("scope"));
    }

    private static LoginUser loginUser(Long id, String... permissions) {
        return new LoginUser(id, "user" + id, "hash", "用户" + id,
                2L, "USER", "普通用户", true, Set.of(permissions));
    }

    private static Task task(Long id, Long ownerId, String status) {
        Task task = new Task();
        task.setId(id);
        task.setOwnerId(ownerId);
        task.setTaskDate(LocalDate.now());
        task.setStatus(status);
        return task;
    }
}
