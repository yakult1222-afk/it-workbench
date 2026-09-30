package com.qinghuan.workbench.controller;

import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.security.CurrentUserService;
import com.qinghuan.workbench.security.LoginUser;
import com.qinghuan.workbench.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {
    private final StatsService statsService;
    private final CurrentUserService currentUserService;

    @GetMapping("/today")
    @PreAuthorize("hasAnyAuthority('task:self:view','task:view:all')")
    public Result<Map<String, Object>> today() {
        LoginUser user = currentUserService.require();
        return Result.ok(statsService.todayStats(user));
    }
}
