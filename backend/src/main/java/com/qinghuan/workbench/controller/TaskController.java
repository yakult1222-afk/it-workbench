package com.qinghuan.workbench.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 任务 CRUD 接口
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 分页列表：可按日期/类型/状态过滤 */
    @GetMapping
    public Result<IPage<Task>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate taskDate,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status) {
        return Result.ok(taskService.page(page, size, taskDate, taskType, status));
    }

    @GetMapping("/{id}")
    public Result<Task> getById(@PathVariable Long id) {
        Task task = taskService.getById(id);
        if (task == null) {
            return Result.error("任务不存在");
        }
        return Result.ok(task);
    }

    @PostMapping
    public Result<Task> create(@Valid @RequestBody Task task) {
        return Result.ok(taskService.create(task));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody Task task) {
        task.setId(id);
        if (!taskService.update(task)) {
            return Result.error("任务不存在");
        }
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (!taskService.delete(id)) {
            return Result.error("任务不存在");
        }
        return Result.ok();
    }
}
