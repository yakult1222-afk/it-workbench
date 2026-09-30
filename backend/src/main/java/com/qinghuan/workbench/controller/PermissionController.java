package com.qinghuan.workbench.controller;

import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.dto.role.PermissionItem;
import com.qinghuan.workbench.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {
    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<List<PermissionItem>> list() {
        return Result.ok(roleService.permissions());
    }
}
