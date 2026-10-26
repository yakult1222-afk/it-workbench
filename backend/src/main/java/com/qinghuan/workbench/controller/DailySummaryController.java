package com.qinghuan.workbench.controller;

import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.dto.summary.DailySummaryJobView;
import com.qinghuan.workbench.security.CurrentUserService;
import com.qinghuan.workbench.security.LoginUser;
import com.qinghuan.workbench.service.DailySummaryJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/daily-summary")
@RequiredArgsConstructor
public class DailySummaryController {
    private final DailySummaryJobService dailySummaryJobService;
    private final CurrentUserService currentUserService;

    @PostMapping("/jobs")
    @PreAuthorize("hasAuthority('task:self:view')")
    public Result<DailySummaryJobView> submit(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LoginUser user = currentUserService.require();
        LocalDate targetDate = date != null ? date : LocalDate.now();
        return Result.ok(dailySummaryJobService.submit(user.getId(), targetDate));
    }

    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("hasAuthority('task:self:view')")
    public Result<DailySummaryJobView> status(@PathVariable Long jobId) {
        LoginUser user = currentUserService.require();
        return Result.ok(dailySummaryJobService.get(jobId, user.getId()));
    }
}
