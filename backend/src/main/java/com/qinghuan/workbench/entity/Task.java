package com.qinghuan.workbench.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 任务实体
 * task_type: 审批 / 硬件问题 / 软件问题 / 网络问题
 * status:    完成 / 未完成 / 延期
 */
@Data
@TableName("task")
public class Task {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务日期 */
    @NotNull(message = "任务日期不能为空")
    private LocalDate taskDate;

    /** 任务类型：审批/硬件问题/软件问题/网络问题 */
    @NotBlank(message = "任务类型不能为空")
    @Pattern(regexp = "审批|硬件问题|软件问题|网络问题", message = "任务类型必须为：审批/硬件问题/软件问题/网络问题")
    private String taskType;

    /** 任务描述 */
    @Size(max = 1000, message = "任务描述不能超过 1000 字")
    private String description;

    /** 任务状态：完成/未完成/延期 */
    @NotBlank(message = "任务状态不能为空")
    @Pattern(regexp = "完成|未完成|延期", message = "任务状态必须为：完成/未完成/延期")
    private String status;

    /** 任务总结 */
    @Size(max = 2000, message = "任务总结不能超过 2000 字")
    private String summary;

    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
