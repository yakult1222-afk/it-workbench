package com.qinghuan.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskMapper taskMapper;

    /**
     * 分页查询，支持按日期/类型/状态过滤
     */
    public IPage<Task> page(long page, long size, LocalDate taskDate, String taskType, String status) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
                .eq(taskDate != null, Task::getTaskDate, taskDate)
                .eq(StringUtils.hasText(taskType), Task::getTaskType, taskType)
                .eq(StringUtils.hasText(status), Task::getStatus, status)
                .orderByDesc(Task::getTaskDate)
                .orderByDesc(Task::getId);
        return taskMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public Task getById(Long id) {
        return taskMapper.selectById(id);
    }

    public Task create(Task task) {
        task.setId(null);
        taskMapper.insert(task);
        return task;
    }

    public boolean update(Task task) {
        if (task.getId() == null || taskMapper.selectById(task.getId()) == null) {
            return false;
        }
        taskMapper.updateById(task);
        return true;
    }

    public boolean delete(Long id) {
        return taskMapper.deleteById(id) > 0;
    }

    /**
     * 当日任务（全部）
     */
    public List<Task> listToday() {
        return listByDate(LocalDate.now());
    }

    /**
     * 指定日期的任务（全部），供日报汇总使用
     */
    public List<Task> listByDate(LocalDate date) {
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getTaskDate, date)
                .orderByAsc(Task::getId));
    }
}
