package com.qinghuan.workbench.controller;

import com.qinghuan.workbench.common.Result;
import com.qinghuan.workbench.service.DailySummaryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 日报总结接口：将当日任务的描述/状态/总结交由 AI 汇总（未配置 AI 时为模板汇总）
 */
@RestController
@RequestMapping("/api/daily-summary")
public class DailySummaryController {

    private final DailySummaryService dailySummaryService;

    public DailySummaryController(DailySummaryService dailySummaryService) {
        this.dailySummaryService = dailySummaryService;
    }

    /** date 可选，缺省为今天 */
    @GetMapping
    public Result<Map<String, Object>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(dailySummaryService.summarize(date != null ? date : LocalDate.now()));
    }
}
