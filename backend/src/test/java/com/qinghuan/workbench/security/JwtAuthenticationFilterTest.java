package com.qinghuan.workbench.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {
    private final JwtService jwtService = mock(JwtService.class);
    private final LoginUserService loginUserService = mock(LoginUserService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, loginUserService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void disabledAccountTokenDoesNotEstablishAuthenticatedContext() throws Exception {
        LoginUser disabled = new LoginUser(7L, "qinghuan", "hash", "清欢",
                2L, "USER", "普通用户", false, Set.of("task:self:view"));
        when(jwtService.parseUserId("token")).thenReturn(7L);
        when(loginUserService.loadById(7L)).thenReturn(disabled);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("Authorization", "Bearer token");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
