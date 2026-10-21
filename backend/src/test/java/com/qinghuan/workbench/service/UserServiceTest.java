package com.qinghuan.workbench.service;

import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.dto.user.UserCreateRequest;
import com.qinghuan.workbench.dto.user.UserUpdateRequest;
import com.qinghuan.workbench.entity.SysRole;
import com.qinghuan.workbench.entity.SysUser;
import com.qinghuan.workbench.mapper.SysRoleMapper;
import com.qinghuan.workbench.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {
    private SysUserMapper userMapper;
    private SysRoleMapper roleMapper;
    private PasswordEncoder passwordEncoder;
    private UserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new UserService(userMapper, roleMapper, passwordEncoder);
    }

    @Test
    void rejectsDuplicateUsername() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(new UserCreateRequest(
                        "admin", "Admin@123456", "新管理员", 1L, true)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("用户名已存在", ex.getMessage());
    }

    @Test
    void cannotDisableCurrentUser() {
        SysUser current = user(2L, "admin", 1L, true);
        when(userMapper.selectById(2L)).thenReturn(current);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStatus(2L, false, 2L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("不能禁用当前登录账号", ex.getMessage());
    }

    @Test
    void cannotDisableLastEnabledSuperAdmin() {
        SysUser target = user(2L, "admin", 1L, true);
        when(userMapper.selectById(2L)).thenReturn(target);
        when(roleMapper.selectById(1L)).thenReturn(superRole());
        when(userMapper.selectList(any())).thenReturn(List.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeStatus(2L, false, 99L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("至少保留一个可用的超级管理员", ex.getMessage());
    }

    @Test
    void updateCannotDisableLastEnabledSuperAdminWithSameRole() {
        SysUser target = user(2L, "admin", 1L, true);
        when(userMapper.selectById(2L)).thenReturn(target);
        when(roleMapper.selectById(1L)).thenReturn(superRole());
        when(userMapper.selectList(any())).thenReturn(List.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(2L, new UserUpdateRequest("超级管理员", 1L, false), 99L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("至少保留一个可用的超级管理员", ex.getMessage());
    }

    @Test
    void updateCannotDisableCurrentUser() {
        SysUser target = user(2L, "admin", 1L, true);
        when(userMapper.selectById(2L)).thenReturn(target);
        when(roleMapper.selectById(1L)).thenReturn(superRole());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(2L, new UserUpdateRequest("超级管理员", 1L, false), 2L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("不能禁用当前登录账号", ex.getMessage());
    }

    @Test
    void cannotMoveLastSuperAdminToAnotherRole() {
        SysUser target = user(2L, "admin", 1L, true);
        when(userMapper.selectById(2L)).thenReturn(target);
        when(roleMapper.selectById(1L)).thenReturn(superRole());
        when(roleMapper.selectById(2L)).thenReturn(userRole());
        when(userMapper.selectList(any())).thenReturn(List.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(2L, new UserUpdateRequest("超级管理员", 2L, true), 99L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("至少保留一个可用的超级管理员", ex.getMessage());
    }

    private static SysUser user(Long id, String username, Long roleId, boolean enabled) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setRoleId(roleId);
        user.setEnabled(enabled);
        return user;
    }

    private static SysRole superRole() {
        SysRole role = new SysRole();
        role.setId(1L);
        role.setCode("SUPER_ADMIN");
        role.setName("超级管理员");
        return role;
    }

    private static SysRole userRole() {
        SysRole role = new SysRole();
        role.setId(2L);
        role.setCode("USER");
        role.setName("普通用户");
        return role;
    }
}
