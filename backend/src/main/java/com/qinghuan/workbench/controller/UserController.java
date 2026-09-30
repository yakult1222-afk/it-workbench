package com.qinghuan.workbench.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.dto.user.ResetPasswordRequest;
import com.qinghuan.workbench.dto.user.UserCreateRequest;
import com.qinghuan.workbench.dto.user.UserOption;
import com.qinghuan.workbench.dto.user.UserPageItem;
import com.qinghuan.workbench.dto.user.UserStatusRequest;
import com.qinghuan.workbench.dto.user.UserUpdateRequest;
import com.qinghuan.workbench.security.CurrentUserService;
import com.qinghuan.workbench.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final CurrentUserService currentUserService;

    @GetMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<IPage<UserPageItem>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long roleId) {
        return Result.ok(userService.page(page, size, username, displayName, enabled, roleId));
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('task:view:all')")
    public Result<List<UserOption>> options() {
        return Result.ok(userService.options());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<UserPageItem> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.ok(userService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> update(@PathVariable Long id,
                               @Valid @RequestBody UserUpdateRequest request) {
        userService.update(id, request, currentUserService.require().getId());
        return Result.ok();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> status(@PathVariable Long id,
                               @Valid @RequestBody UserStatusRequest request) {
        userService.changeStatus(id, request.enabled(), currentUserService.require().getId());
        return Result.ok();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('user:manage')")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.password());
        return Result.ok();
    }
}
