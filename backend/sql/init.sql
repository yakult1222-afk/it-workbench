-- IT 工作台数据库初始化脚本
CREATE DATABASE IF NOT EXISTS it_workbench
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE it_workbench;

CREATE TABLE IF NOT EXISTS task (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_date   DATE         NOT NULL COMMENT '任务日期',
    task_type   VARCHAR(20)  NOT NULL COMMENT '任务类型：审批/硬件问题/软件问题/网络问题',
    description VARCHAR(1000)         DEFAULT NULL COMMENT '任务描述',
    status      VARCHAR(20)  NOT NULL DEFAULT '未完成' COMMENT '任务状态：完成/未完成/延期',
    summary     VARCHAR(2000)         DEFAULT NULL COMMENT '任务总结',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_task_date (task_date),
    KEY idx_status (status)
) ENGINE = InnoDB COMMENT = 'IT 工作台任务表';

-- 示例数据（可删除）
INSERT INTO task (task_date, task_type, description, status, summary) VALUES
    (CURDATE(), '硬件问题', '3 楼办公区打印机卡纸且无法进纸，需现场排查搓纸轮磨损情况', '完成', '更换搓纸轮组件，测试打印 50 页无卡纸'),
    (CURDATE(), '软件问题', '财务部 ERP 客户端打不开报表模块，报缺少运行库', '完成', '安装对应运行库并更新客户端补丁，报表正常导出'),
    (CURDATE(), '审批', '新员工入职账号开通审批（研发部 2 人）', '未完成', NULL),
    (CURDATE(), '网络问题', '会议室 Wi-Fi 间歇性掉线，需检查 AP 固件与信道干扰', '延期', 'AP 固件升级需走变更窗口，已预约周六晚处理'),
    (CURDATE(), '审批', '打印机采购审批流转至 IT 部门确认配置参数', '未完成', NULL);
