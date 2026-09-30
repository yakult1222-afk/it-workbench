package com.qinghuan.workbench.service;

import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.dto.role.RoleRequest;
import com.qinghuan.workbench.entity.SysPermission;
import com.qinghuan.workbench.entity.SysRole;
import com.qinghuan.workbench.mapper.SysPermissionMapper;
import com.qinghuan.workbench.mapper.SysRoleMapper;
import com.qinghuan.workbench.mapper.SysRolePermissionMapper;
import com.qinghuan.workbench.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleServiceTest {
    private SysRoleMapper roleMapper;
    private SysPermissionMapper permissionMapper;
    private SysRolePermissionMapper rolePermissionMapper;
    private SysUserMapper userMapper;
    private RoleService service;

    @BeforeEach
    void setUp() {
        roleMapper = mock(SysRoleMapper.class);
        permissionMapper = mock(SysPermissionMapper.class);
        rolePermissionMapper = mock(SysRolePermissionMapper.class);
        userMapper = mock(SysUserMapper.class);
        service = new RoleService(roleMapper, permissionMapper, rolePermissionMapper, userMapper);
    }

    @Test
    void rejectsManagePermissionWithoutViewPermission() {
        when(permissionMapper.selectBatchIds(any())).thenReturn(List.of(
                permission(2L, "task:self:manage")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(new RoleRequest(
                        "IT_ADMIN", "IT 管理员", "测试", List.of(2L))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("task:self:manage 需要同时选择 task:self:view", ex.getMessage());
    }

    @Test
    void superAdminCannotBeDeleted() {
        when(roleMapper.selectById(1L)).thenReturn(superRole());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(1L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("内置超级管理员角色不能删除", ex.getMessage());
    }

    @Test
    void roleInUseCannotBeDeleted() {
        SysRole custom = role(3L, "IT_ADMIN", false);
        when(roleMapper.selectById(3L)).thenReturn(custom);
        when(userMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(3L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("角色仍被账号使用，不能删除", ex.getMessage());
    }

    @Test
    void superAdminAlwaysSavesAllPermissions() {
        when(roleMapper.selectById(1L)).thenReturn(superRole());
        when(permissionMapper.selectList(any())).thenReturn(List.of(
                permission(1L, "task:self:view"), permission(6L, "role:manage")));

        service.update(1L, new RoleRequest(
                "SUPER_ADMIN", "超级管理员", "更新说明", List.of(1L)));

        verify(rolePermissionMapper).deleteByRoleId(1L);
        verify(rolePermissionMapper).insertBatch(1L, List.of(1L, 6L));
    }

    private static SysRole superRole() {
        return role(1L, "SUPER_ADMIN", true);
    }

    private static SysRole role(Long id, String code, boolean builtIn) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCode(code);
        role.setName(code);
        role.setBuiltIn(builtIn);
        return role;
    }

    private static SysPermission permission(Long id, String code) {
        SysPermission permission = new SysPermission();
        permission.setId(id);
        permission.setCode(code);
        permission.setName(code);
        return permission;
    }
}
