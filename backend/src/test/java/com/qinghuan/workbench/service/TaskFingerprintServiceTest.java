package com.qinghuan.workbench.service;

import com.qinghuan.workbench.entity.Task;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TaskFingerprintServiceTest {
    private final TaskFingerprintService service = new TaskFingerprintService();

    @Test
    void sameTaskContentProducesSameFingerprint() {
        assertEquals(service.fingerprint(List.of(task("完成", "修复打印机"))),
                service.fingerprint(List.of(task("完成", "修复打印机"))));
    }

    @Test
    void changedTaskSummaryChangesFingerprint() {
        Task before = task("完成", "修复打印机");
        Task after = task("完成", "修复打印机");
        after.setSummary("已更换搓纸轮");

        assertNotEquals(service.fingerprint(List.of(before)),
                service.fingerprint(List.of(after)));
    }

    private Task task(String status, String description) {
        Task task = new Task();
        task.setId(1L);
        task.setOwnerId(7L);
        task.setTaskDate(LocalDate.of(2026, 10, 26));
        task.setTaskType("硬件问题");
        task.setDescription(description);
        task.setStatus(status);
        return task;
    }
}
