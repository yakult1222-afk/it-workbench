package com.qinghuan.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.mapper.TaskMapper;
import com.qinghuan.workbench.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskScopeService {
    private final TaskMapper taskMapper;

    public boolean canViewAll(LoginUser user) {
        return user.hasPermission("task:view:all");
    }

    public boolean canManageAll(LoginUser user) {
        return user.hasPermission("task:manage:all");
    }

    public void applyViewScope(LambdaQueryWrapper<Task> wrapper,
                               LoginUser user,
                               Long requestedOwnerId) {
        if (canViewAll(user)) {
            wrapper.eq(requestedOwnerId != null, Task::getOwnerId, requestedOwnerId);
            return;
        }
        if (!user.hasPermission("task:self:view")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权限访问");
        }
        wrapper.eq(Task::getOwnerId, user.getId());
    }

    public Task requireViewable(Long id, LoginUser user) {
        Task task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "任务不存在");
        }
        if (!canViewAll(user) && !user.getId().equals(task.getOwnerId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权限访问");
        }
        return task;
    }

    public Task requireManageable(Long id, LoginUser user) {
        Task task = requireViewable(id, user);
        if (canManageAll(user)) {
            return task;
        }
        boolean ownsTask = user.getId().equals(task.getOwnerId());
        if (!user.hasPermission("task:self:manage") || !ownsTask) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权限访问");
        }
        return task;
    }
}
