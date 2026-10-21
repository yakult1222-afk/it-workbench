package com.qinghuan.workbench.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.security.CurrentUserService;
import com.qinghuan.workbench.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;
    private final CurrentUserService currentUserService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('task:self:view','task:view:all')")
    public Result<IPage<Task>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate taskDate,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long ownerId) {
        return Result.ok(taskService.page(page, size, taskDate, taskType, status, ownerId,
                currentUserService.require()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('task:self:view','task:view:all')")
    public Result<Task> getById(@PathVariable Long id) {
        return Result.ok(taskService.getById(id, currentUserService.require()));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('task:self:manage','task:manage:all')")
    public Result<Task> create(@Valid @RequestBody Task task) {
        return Result.ok(taskService.create(task, currentUserService.require()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('task:self:manage','task:manage:all')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody Task task) {
        task.setId(id);
        taskService.update(task, currentUserService.require());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('task:self:manage','task:manage:all')")
    public Result<Void> delete(@PathVariable Long id) {
        taskService.delete(id, currentUserService.require());
        return Result.ok();
    }
}
