package com.qinghuan.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.entity.SysUser;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.mapper.SysUserMapper;
import com.qinghuan.workbench.mapper.TaskMapper;
import com.qinghuan.workbench.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskService {
    private final TaskMapper taskMapper;
    private final TaskScopeService taskScopeService;
    private final SysUserMapper ownerMapper;
    private final DailySummaryCacheService summaryCache;

    public IPage<Task> page(long page, long size, LocalDate taskDate,
                            String taskType, String status, Long ownerId,
                            LoginUser user) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
                .eq(taskDate != null, Task::getTaskDate, taskDate)
                .eq(StringUtils.hasText(taskType), Task::getTaskType, taskType)
                .eq(StringUtils.hasText(status), Task::getStatus, status)
                .orderByDesc(Task::getTaskDate)
                .orderByDesc(Task::getId);
        taskScopeService.applyViewScope(wrapper, user, ownerId);
        IPage<Task> result = taskMapper.selectPage(new Page<>(page, size), wrapper);
        enrichOwnerNames(result.getRecords());
        return result;
    }

    public Task getById(Long id, LoginUser user) {
        Task task = taskScopeService.requireViewable(id, user);
        enrichOwnerNames(List.of(task));
        return task;
    }

    @Transactional
    public Task create(Task task, LoginUser user) {
        Long ownerId = taskScopeService.canManageAll(user)
                ? (task.getOwnerId() == null ? user.getId() : task.getOwnerId())
                : user.getId();
        SysUser owner = requireEnabledOwner(ownerId);
        task.setId(null);
        task.setOwnerId(ownerId);
        task.setOwnerName(owner.getDisplayName());
        taskMapper.insert(task);
        summaryCache.evictAfterCommit(ownerId, task.getTaskDate());
        return task;
    }

    @Transactional
    public boolean update(Task task, LoginUser user) {
        Task current = taskScopeService.requireManageable(task.getId(), user);
        Long oldOwnerId = current.getOwnerId();
        LocalDate oldTaskDate = current.getTaskDate();
        if (!taskScopeService.canManageAll(user)) {
            task.setOwnerId(user.getId());
        } else if (task.getOwnerId() == null) {
            task.setOwnerId(current.getOwnerId());
        }
        SysUser owner;
        if (task.getOwnerId().equals(current.getOwnerId())) {
            owner = requireUser(task.getOwnerId());
        } else {
            owner = requireEnabledOwner(task.getOwnerId());
        }
        task.setOwnerName(owner.getDisplayName());
        taskMapper.updateById(task);
        summaryCache.evictAfterCommit(oldOwnerId, oldTaskDate);
        if (!task.getOwnerId().equals(oldOwnerId) || !task.getTaskDate().equals(oldTaskDate)) {
            summaryCache.evictAfterCommit(task.getOwnerId(), task.getTaskDate());
        }
        return true;
    }

    @Transactional
    public boolean delete(Long id, LoginUser user) {
        Task current = taskScopeService.requireManageable(id, user);
        boolean deleted = taskMapper.deleteById(id) > 0;
        if (deleted) {
            summaryCache.evictAfterCommit(current.getOwnerId(), current.getTaskDate());
        }
        return deleted;
    }

    public List<Task> listForStats(LocalDate date, LoginUser user) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
                .eq(Task::getTaskDate, date)
                .orderByAsc(Task::getId);
        taskScopeService.applyViewScope(wrapper, user, null);
        List<Task> tasks = taskMapper.selectList(wrapper);
        enrichOwnerNames(tasks);
        return tasks;
    }

    public List<Task> listByDate(LocalDate date, Long ownerId) {
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getTaskDate, date)
                .eq(Task::getOwnerId, ownerId)
                .orderByAsc(Task::getId));
        enrichOwnerNames(tasks);
        return tasks;
    }

    private SysUser requireUser(Long id) {
        SysUser user = ownerMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "归属账号不存在");
        }
        return user;
    }

    private SysUser requireEnabledOwner(Long id) {
        SysUser owner = requireUser(id);
        if (!Boolean.TRUE.equals(owner.getEnabled())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "归属账号已禁用");
        }
        return owner;
    }

    private void enrichOwnerNames(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        Set<Long> ownerIds = tasks.stream().map(Task::getOwnerId).collect(Collectors.toSet());
        Map<Long, String> names = ownerMapper.selectBatchIds(ownerIds).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getDisplayName));
        tasks.forEach(task -> task.setOwnerName(names.get(task.getOwnerId())));
    }
}
