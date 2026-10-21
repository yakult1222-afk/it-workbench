package com.qinghuan.workbench.controller;

import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.dto.role.RoleOption;
import com.qinghuan.workbench.dto.role.RoleRequest;
import com.qinghuan.workbench.dto.role.RoleResponse;
import com.qinghuan.workbench.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {
    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<List<RoleResponse>> list() {
        return Result.ok(roleService.list());
    }

    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('user:manage','role:manage')")
    public Result<List<RoleOption>> options() {
        return Result.ok(roleService.options());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<RoleResponse> get(@PathVariable Long id) {
        return Result.ok(roleService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<RoleResponse> create(@Valid @RequestBody RoleRequest request) {
        return Result.ok(roleService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<RoleResponse> update(@PathVariable Long id,
                                       @Valid @RequestBody RoleRequest request) {
        return Result.ok(roleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.ok();
    }
}
