package com.qinghuan.workbench.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("ai_summary_job")
public class AiSummaryJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerId;
    private LocalDate summaryDate;
    private String inputHash;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextRunAt;
    private LocalDateTime leaseUntil;
    private String content;
    private String source;
    private Integer taskCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
