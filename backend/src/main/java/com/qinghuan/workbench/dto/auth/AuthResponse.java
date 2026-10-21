package com.qinghuan.workbench.dto.auth;

import com.qinghuan.workbench.security.LoginUser;

import java.util.Set;

public record AuthResponse(
        String token,
        Long id,
        String username,
        String displayName,
        Long roleId,
        String roleCode,
        String roleName,
        Set<String> permissions) {
    public static AuthResponse from(String token, LoginUser user) {
        return new AuthResponse(token, user.getId(), user.getUsername(),
                user.getDisplayName(), user.getRoleId(), user.getRoleCode(),
                user.getRoleName(), user.getPermissions());
    }
}
