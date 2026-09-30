package com.qinghuan.workbench.dto.user;

import com.qinghuan.workbench.entity.SysRole;
import com.qinghuan.workbench.entity.SysUser;

import java.time.LocalDateTime;

public record UserPageItem(
        Long id,
        String username,
        String displayName,
        Long roleId,
        String roleName,
        Boolean enabled,
        LocalDateTime createdAt) {
    public static UserPageItem from(SysUser user, SysRole role) {
        return new UserPageItem(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRoleId(), role == null ? null : role.getName(),
                user.getEnabled(), user.getCreatedAt());
    }
}
