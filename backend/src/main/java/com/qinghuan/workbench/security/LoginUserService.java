package com.qinghuan.workbench.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qinghuan.workbench.entity.SysPermission;
import com.qinghuan.workbench.entity.SysRole;
import com.qinghuan.workbench.entity.SysUser;
import com.qinghuan.workbench.mapper.SysPermissionMapper;
import com.qinghuan.workbench.mapper.SysRoleMapper;
import com.qinghuan.workbench.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LoginUserService implements UserDetailsService {
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;

    @Override
    public LoginUser loadUserByUsername(String username) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return toLoginUser(user);
    }

    public LoginUser loadById(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return toLoginUser(user);
    }

    private LoginUser toLoginUser(SysUser user) {
        SysRole role = roleMapper.selectById(user.getRoleId());
        if (role == null) {
            throw new UsernameNotFoundException("角色不存在");
        }
        Set<String> permissions = permissionMapper.selectByRoleId(role.getId())
                .stream().map(SysPermission::getCode).collect(Collectors.toSet());
        return new LoginUser(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getDisplayName(), role.getId(), role.getCode(), role.getName(),
                Boolean.TRUE.equals(user.getEnabled()), permissions);
    }
}
