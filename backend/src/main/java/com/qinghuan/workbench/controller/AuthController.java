package com.qinghuan.workbench.controller;

import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.dto.auth.AuthResponse;
import com.qinghuan.workbench.dto.auth.LoginRequest;
import com.qinghuan.workbench.security.CurrentUserService;
import com.qinghuan.workbench.security.LoginUser;
import com.qinghuan.workbench.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final CurrentUserService currentUserService;

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @GetMapping("/me")
    public Result<AuthResponse> me() {
        LoginUser user = currentUserService.require();
        return Result.ok(AuthResponse.from(null, user));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.ok();
    }
}
