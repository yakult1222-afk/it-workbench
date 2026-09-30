package com.qinghuan.workbench.service;

import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.dto.auth.LoginRequest;
import com.qinghuan.workbench.security.JwtService;
import com.qinghuan.workbench.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthService service = new AuthService(manager, jwtService);
    private final LoginUser user = new LoginUser(
            2L, "admin", "hash", "超级管理员",
            1L, "SUPER_ADMIN", "超级管理员", true, Set.of("user:manage"));

    @Test
    void returnsTokenAndPermissionsOnSuccess() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(user);
        when(manager.authenticate(any())).thenReturn(auth);
        when(jwtService.generate(user)).thenReturn("jwt-token");

        var response = service.login(new LoginRequest("admin", "Admin@123456"));

        assertEquals("jwt-token", response.token());
        assertEquals("admin", response.username());
        assertEquals(Set.of("user:manage"), response.permissions());
    }

    @Test
    void invalidCredentialsReturn401() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(new LoginRequest("admin", "wrong")));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    void disabledAccountReturns403() {
        when(manager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(new LoginRequest("admin", "Admin@123456")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("账号已禁用", ex.getMessage());
    }
}
