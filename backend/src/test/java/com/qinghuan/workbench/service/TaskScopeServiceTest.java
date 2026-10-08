package com.qinghuan.workbench.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.entity.Task;
import com.qinghuan.workbench.mapper.TaskMapper;
import com.qinghuan.workbench.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskScopeServiceTest {
    private TaskMapper taskMapper;
    private TaskScopeService scopeService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Task.class);
        taskMapper = mock(TaskMapper.class);
        scopeService = new TaskScopeService(taskMapper);
    }

    @Test
    void normalUserScopeForcesCurrentOwnerEvenWhenOwnerIdIsSpoofed() {
        LoginUser user = loginUser(7L, "task:self:view");
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

        scopeService.applyViewScope(wrapper, user, 99L);

        assertTrue(wrapper.getSqlSegment().contains("owner_id"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
        assertFalse(wrapper.getParamNameValuePairs().containsValue(99L));
    }

    @Test
    void allTaskViewerUsesRequestedOwnerFilter() {
        LoginUser user = loginUser(7L, "task:view:all");
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

        scopeService.applyViewScope(wrapper, user, 99L);

        assertTrue(wrapper.getSqlSegment().contains("owner_id"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(99L));
        assertFalse(wrapper.getParamNameValuePairs().containsValue(7L));
    }

    @Test
    void viewAllSelfManageCannotManageAnotherOwnersTask() {
        Task otherTask = task(3L, 99L);
        when(taskMapper.selectById(3L)).thenReturn(otherTask);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> scopeService.requireManageable(
                        3L, loginUser(7L, "task:view:all", "task:self:manage")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void normalUserCannotLoadAnotherUsersTask() {
        Task otherTask = task(3L, 99L);
        when(taskMapper.selectById(3L)).thenReturn(otherTask);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> scopeService.requireViewable(3L, loginUser(7L, "task:self:view")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
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
        return task;
    }
}
