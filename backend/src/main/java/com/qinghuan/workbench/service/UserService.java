package com.qinghuan.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.dto.user.UserCreateRequest;
import com.qinghuan.workbench.dto.user.UserOption;
import com.qinghuan.workbench.dto.user.UserPageItem;
import com.qinghuan.workbench.dto.user.UserUpdateRequest;
import com.qinghuan.workbench.entity.SysRole;
import com.qinghuan.workbench.entity.SysUser;
import com.qinghuan.workbench.mapper.SysRoleMapper;
import com.qinghuan.workbench.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public IPage<UserPageItem> page(long page, long size, String username,
                                    String displayName, Boolean enabled, Long roleId) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(username), SysUser::getUsername, username)
                .like(StringUtils.hasText(displayName), SysUser::getDisplayName, displayName)
                .eq(enabled != null, SysUser::getEnabled, enabled)
                .eq(roleId != null, SysUser::getRoleId, roleId)
                .orderByDesc(SysUser::getId);
        IPage<SysUser> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        if (result.getRecords().isEmpty()) {
            return result.convert(user -> UserPageItem.from(user, null));
        }
        Map<Long, SysRole> roles = roleMapper.selectBatchIds(
                        result.getRecords().stream().map(SysUser::getRoleId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SysRole::getId, Function.identity()));
        return result.convert(user -> UserPageItem.from(user, roles.get(user.getRoleId())));
    }

    public UserPageItem create(UserCreateRequest request) {
        String username = request.username().trim();
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "用户名已存在");
        }
        validatePasswordNotUsername(request.password(), username);
        SysRole role = requireRole(request.roleId());
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setRoleId(role.getId());
        user.setEnabled(request.enabled() == null || request.enabled());
        userMapper.insert(user);
        return UserPageItem.from(user, role);
    }

    public void update(Long id, UserUpdateRequest request, Long operatorId) {
        SysUser target = requireUser(id);
        SysRole oldRole = requireRole(target.getRoleId());
        SysRole newRole = requireRole(request.roleId());
        boolean removingLastSuperAdmin = isSuperAdmin(oldRole)
                && !"SUPER_ADMIN".equals(newRole.getCode())
                && Boolean.TRUE.equals(target.getEnabled())
                && countEnabledSuperAdmins(oldRole.getId()) <= 1;
        if (removingLastSuperAdmin) {
            throw new BusinessException(HttpStatus.CONFLICT, "至少保留一个可用的超级管理员");
        }
        if (id.equals(operatorId) && !request.enabled()) {
            throw new BusinessException(HttpStatus.CONFLICT, "不能禁用当前登录账号");
        }
        target.setDisplayName(request.displayName().trim());
        target.setRoleId(newRole.getId());
        target.setEnabled(request.enabled());
        userMapper.updateById(target);
    }

    public void changeStatus(Long id, boolean enabled, Long operatorId) {
        SysUser target = requireUser(id);
        if (!enabled && id.equals(operatorId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "不能禁用当前登录账号");
        }
        if (!enabled && Boolean.TRUE.equals(target.getEnabled())) {
            SysRole role = requireRole(target.getRoleId());
            if (isSuperAdmin(role) && countEnabledSuperAdmins(role.getId()) <= 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "至少保留一个可用的超级管理员");
            }
        }
        target.setEnabled(enabled);
        userMapper.updateById(target);
    }

    public void resetPassword(Long id, String password) {
        SysUser user = requireUser(id);
        validatePasswordNotUsername(password, user.getUsername());
        user.setPasswordHash(passwordEncoder.encode(password));
        userMapper.updateById(user);
    }

    public List<UserOption> options() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getEnabled, true)
                        .orderByAsc(SysUser::getDisplayName))
                .stream().map(user -> new UserOption(user.getId(), user.getDisplayName()))
                .toList();
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "账号不存在");
        }
        return user;
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "角色不存在");
        }
        return role;
    }

    private boolean isSuperAdmin(SysRole role) {
        return "SUPER_ADMIN".equals(role.getCode());
    }

    private long countEnabledSuperAdmins(Long roleId) {
        return userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRoleId, roleId)
                .eq(SysUser::getEnabled, true));
    }

    private void validatePasswordNotUsername(String password, String username) {
        if (password != null && password.equalsIgnoreCase(username)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "密码不能与用户名相同");
        }
    }
}
