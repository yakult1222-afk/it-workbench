package com.qinghuan.workbench.service;

import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.dto.auth.AuthResponse;
import com.qinghuan.workbench.dto.auth.LoginRequest;
import com.qinghuan.workbench.security.JwtService;
import com.qinghuan.workbench.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthResponse login(LoginRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.username().trim(), request.password()));
            LoginUser user = (LoginUser) auth.getPrincipal();
            return AuthResponse.from(jwtService.generate(user), user);
        } catch (DisabledException e) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "账号已禁用");
        } catch (AuthenticationException e) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
    }
}
