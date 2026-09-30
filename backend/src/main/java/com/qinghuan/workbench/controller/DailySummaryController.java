package com.qinghuan.workbench.controller;

import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.security.CurrentUserService;
import com.qinghuan.workbench.security.LoginUser;
import com.qinghuan.workbench.service.DailySummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/daily-summary")
@RequiredArgsConstructor
public class DailySummaryController {
    private final DailySummaryService dailySummaryService;
    private final CurrentUserService currentUserService;

    @GetMapping
    @PreAuthorize("hasAuthority('task:self:view')")
    public Result<Map<String, Object>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LoginUser user = currentUserService.require();
        return Result.ok(dailySummaryService.summarize(
                date != null ? date : LocalDate.now(), user.getId()));
    }
}
