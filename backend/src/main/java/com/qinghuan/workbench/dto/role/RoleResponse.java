package com.qinghuan.workbench.dto.role;

import java.util.List;

public record RoleResponse(
        Long id,
        String code,
        String name,
        String description,
        boolean builtIn,
        long userCount,
        List<Long> permissionIds,
        List<String> permissionCodes) {
}
