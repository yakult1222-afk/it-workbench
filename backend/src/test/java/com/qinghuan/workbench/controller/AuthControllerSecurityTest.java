package com.qinghuan.workbench.controller;

import com.qinghuan.workbench.config.SecurityConfig;
import com.qinghuan.workbench.dto.auth.AuthResponse;
import com.qinghuan.workbench.security.CurrentUserService;
import com.qinghuan.workbench.security.JwtAuthenticationFilter;
import com.qinghuan.workbench.security.JwtService;
import com.qinghuan.workbench.security.LoginUserService;
import com.qinghuan.workbench.security.RestAccessDeniedHandler;
import com.qinghuan.workbench.security.RestAuthenticationEntryPoint;
import com.qinghuan.workbench.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class AuthControllerSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private CurrentUserService currentUserService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private LoginUserService loginUserService;

    @Test
    void loginIsPublic() throws Exception {
        when(authService.login(any())).thenReturn(new AuthResponse(
                "token", 1L, "admin", "超级管理员", 1L,
                "SUPER_ADMIN", "超级管理员", Set.of("user:manage")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"Admin@123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("token"));
    }

    @Test
    void protectedApiWithoutTokenIs401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void spaRoutesAndAssetsAreNotProtectedByApiRule() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/tasks"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/assets/app.js"))
                .andExpect(status().isOk());
    }
}
