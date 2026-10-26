package com.qinghuan.workbench.controller;

import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.common.GlobalExceptionHandler;
import com.qinghuan.workbench.config.SecurityConfig;
import com.qinghuan.workbench.dto.summary.DailySummaryJobView;
import com.qinghuan.workbench.security.CurrentUserService;
import com.qinghuan.workbench.security.JwtAuthenticationFilter;
import com.qinghuan.workbench.security.JwtService;
import com.qinghuan.workbench.security.LoginUser;
import com.qinghuan.workbench.security.LoginUserService;
import com.qinghuan.workbench.security.RestAccessDeniedHandler;
import com.qinghuan.workbench.security.RestAuthenticationEntryPoint;
import com.qinghuan.workbench.service.DailySummaryJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DailySummaryController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
class DailySummaryControllerSecurityTest {
    private static final String TOKEN = "valid-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DailySummaryJobService jobService;
    @MockBean
    private CurrentUserService currentUserService;
    @MockBean
    private JwtService jwtService;
    @MockBean
    private LoginUserService loginUserService;

    @Test
    void jobEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/daily-summary/jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanSubmitTodaySummary() throws Exception {
        mockAuthenticatedUser();
        LocalDate today = LocalDate.now();
        when(jobService.submit(7L, today)).thenReturn(new DailySummaryJobView(
                42L, today, "QUEUED", null, 3, null, null));

        mockMvc.perform(post("/api/daily-summary/jobs")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(42))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void anotherUsersJobReturnsNotFound() throws Exception {
        mockAuthenticatedUser();
        when(jobService.get(99L, 7L))
                .thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "日报任务不存在"));

        mockMvc.perform(get("/api/daily-summary/jobs/99")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.msg").value("日报任务不存在"));
    }

    private void mockAuthenticatedUser() {
        LoginUser user = new LoginUser(
                7L, "qinghuan", "hash", "清欢", 2L,
                "USER", "普通用户", true, Set.of("task:self:view"));
        when(jwtService.parseUserId(TOKEN)).thenReturn(7L);
        when(loginUserService.loadById(7L)).thenReturn(user);
        when(currentUserService.require()).thenReturn(user);
    }
}
