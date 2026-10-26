package com.qinghuan.workbench.service;

import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.entity.SysUser;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.mapper.SysUserMapper;
import com.qinghuan.workbench.mapper.TaskMapper;
import com.qinghuan.workbench.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceTest {
    private TaskMapper taskMapper;
    private TaskScopeService taskScopeService;
    private SysUserMapper ownerMapper;
    private DailySummaryCacheService summaryCache;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        taskScopeService = mock(TaskScopeService.class);
        ownerMapper = mock(SysUserMapper.class);
        summaryCache = mock(DailySummaryCacheService.class);
        service = new TaskService(taskMapper, taskScopeService, ownerMapper, summaryCache);
    }

    @Test
    void normalUserCreateForcesOwnershipToCurrentUser() {
        LoginUser user = loginUser(7L, "task:self:view", "task:self:manage");
        when(taskScopeService.canManageAll(user)).thenReturn(false);
        when(ownerMapper.selectById(7L)).thenReturn(enabledUser(7L));
        Task request = task(null, 99L);

        service.create(request, user);

        assertEquals(7L, request.getOwnerId());
        verify(ownerMapper).selectById(7L);
    }

    @Test
    void allTaskManagerMayAssignOwner() {
        LoginUser user = loginUser(2L, "task:view:all", "task:manage:all");
        when(taskScopeService.canManageAll(user)).thenReturn(true);
        when(ownerMapper.selectById(7L)).thenReturn(enabledUser(7L));
        Task request = task(null, 7L);

        service.create(request, user);

        assertEquals(7L, request.getOwnerId());
    }

    @Test
    void normalUserCannotUpdateAnotherUsersTask() {
        LoginUser user = loginUser(7L, "task:self:view", "task:self:manage");
        Task request = task(3L, 99L);
        when(taskScopeService.requireManageable(3L, user))
                .thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "无权限访问"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(request, user));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void createInvalidatesSummaryForTaskOwnerAndDate() {
        LoginUser user = loginUser(7L, "task:self:view", "task:self:manage");
        when(taskScopeService.canManageAll(user)).thenReturn(false);
        when(ownerMapper.selectById(7L)).thenReturn(enabledUser(7L));
        Task request = task(null, 7L);
        LocalDate date = LocalDate.of(2026, 10, 26);
        request.setTaskDate(date);

        service.create(request, user);

        verify(summaryCache).evictAfterCommit(7L, date);
    }

    @Test
    void deleteInvalidatesSummaryForDeletedTask() {
        LoginUser user = loginUser(7L, "task:self:view", "task:self:manage");
        Task current = task(3L, 7L);
        when(taskScopeService.requireManageable(3L, user)).thenReturn(current);
        when(taskMapper.deleteById(3L)).thenReturn(1);

        service.delete(3L, user);

        verify(summaryCache).evictAfterCommit(7L, current.getTaskDate());
    }

    @Test
    void updateInvalidatesOldAndNewSummaryScopes() {
        LoginUser user = loginUser(2L, "task:view:all", "task:manage:all");
        Task current = task(3L, 7L);
        current.setTaskDate(LocalDate.of(2026, 10, 25));
        Task request = task(3L, 8L);
        request.setTaskDate(LocalDate.of(2026, 10, 26));
        when(taskScopeService.requireManageable(3L, user)).thenReturn(current);
        when(taskScopeService.canManageAll(user)).thenReturn(true);
        when(ownerMapper.selectById(8L)).thenReturn(enabledUser(8L));

        service.update(request, user);

        verify(summaryCache).evictAfterCommit(7L, LocalDate.of(2026, 10, 25));
        verify(summaryCache).evictAfterCommit(8L, LocalDate.of(2026, 10, 26));
    }

    private static LoginUser loginUser(Long id, String... permissions) {
        return new LoginUser(id, "user" + id, "hash", "用户" + id,
                2L, "USER", "普通用户", true, Set.of(permissions));
    }

    private static Task task(Long id, Long ownerId) {
        Task task = new Task();
        task.setId(id);
        task.setOwnerId(ownerId);
        task.setTaskDate(LocalDate.now());
        task.setTaskType("审批");
        task.setStatus("未完成");
        return task;
    }

    private static SysUser enabledUser(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setDisplayName("用户" + id);
        user.setEnabled(true);
        return user;
    }
}
