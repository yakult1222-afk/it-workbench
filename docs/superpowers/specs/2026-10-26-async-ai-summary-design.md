# AI 日报异步化与 Redis 缓存设计

**日期：** 2026-10-26

## 目标

把当前同步等待 AI 的日报接口改成数据库驱动的异步任务，并把生成结果缓存到 Redis。当前项目规模下不引入 MQ。

## 核心决策

- 新增独立的 `ai_summary_job` 表，不复用业务 `task` 表。
- MySQL 保存任务状态、重试信息和最终日报正文，是事实来源。
- Redis 只作为缓存，不可用时回退 MySQL，不阻断日报查询。
- Spring Boot 应用内定时 Worker 从 MySQL 抢占任务，并支持多实例安全抢占。
- 任务创建、修改、删除后，在事务提交后失效对应日报缓存。
- 用户下次查看日报时创建或复用一条任务，采用前端轮询。
- Redis 缓存使用任务内容指纹校验，避免任务变化后返回旧日报。

## API

### 创建或复用任务

`POST /api/daily-summary/jobs?date=YYYY-MM-DD`

- 未传日期时使用服务端当天日期。
- 当前用户只能为本人创建日报任务。
- 缓存命中时直接返回 `SUCCEEDED`。
- 相同 `ownerId + date + inputHash` 已有任务时返回原任务。
- 相同指纹的历史任务若为 `FAILED/SUPERSEDED`，将其重置为 `QUEUED` 并重新执行，避免任务内容回退后复用没有正文的旧记录。
- 否则插入 `QUEUED` 任务并返回。

返回：

```json
{
  "jobId": 42,
  "date": "2026-10-26",
  "status": "QUEUED",
  "source": null,
  "taskCount": 5,
  "content": null,
  "errorMessage": null
}
```

### 查询任务

`GET /api/daily-summary/jobs/{jobId}`

- 只允许查询本人任务。
- 返回与创建接口相同的结构。
- 非法任务或他人任务统一返回 404，避免泄漏任务是否存在。

## 状态

| 状态 | 含义 | 是否终态 |
|---|---|---|
| `QUEUED` | 等待 Worker | 否 |
| `RUNNING` | 正在执行 | 否 |
| `RETRYING` | AI 失败，等待下一次尝试 | 否 |
| `SUCCEEDED` | AI 成功，或按预期直接使用模板 | 是 |
| `DEGRADED` | AI 已启用但重试耗尽，使用模板降级 | 是 |
| `FAILED` | 数据库、序列化或程序异常 | 是 |
| `SUPERSEDED` | 执行期间源任务发生变化，本次结果不再写入缓存 | 是 |

未配置 AI 或当天无任务时使用模板，状态为 `SUCCEEDED`、`source=template`。AI 已启用但失败并耗尽重试时使用模板，状态为 `DEGRADED`。

## 数据表

```sql
CREATE TABLE IF NOT EXISTS ai_summary_job (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id      BIGINT       NOT NULL,
    summary_date  DATE         NOT NULL,
    input_hash    CHAR(64)     NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    attempt_count INT          NOT NULL DEFAULT 0,
    next_run_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until   DATETIME(3)  NULL,
    content       VARCHAR(4000) NULL,
    source        VARCHAR(20)  NULL,
    task_count    INT          NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at   DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_summary_job_input (owner_id, summary_date, input_hash),
    KEY idx_ai_summary_job_claim (status, next_run_at),
    KEY idx_ai_summary_job_lease (status, lease_until),
    CONSTRAINT fk_ai_summary_job_owner
        FOREIGN KEY (owner_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='AI 日报异步任务';
```

## Worker

- 每 1 秒抢占一次。
- 最多同时执行 2 个 AI 调用。
- 租约 120 秒。
- 最多执行 3 次（首次执行加最多 2 次重试）。
- 第 1 次失败后等待 5 秒，第 2 次失败后等待 20 秒。
- 第 3 次仍失败时生成模板日报并标记 `DEGRADED`。
- 配置允许把最大退避时间调到 60 秒；当前 3 次尝试配置不会使用第 3 个等待值。
- 已完成的普通任务保留 30 天，每天清理一次；`RUNNING` 不参与清理。

Worker 先提交领取事务，再调用 AI，不能在 AI 调用期间持有数据库行锁。`RUNNING` 任务超过租约后可被重新领取。

## Redis 缓存

结果 key：

```text
workbench:ai-summary:{ownerId}:{date}:{inputHash}
```

当前缓存指针：

```text
workbench:ai-summary:pointer:{ownerId}:{date}
```

- 两个 key 的 TTL 都是 30 分钟。
- 值中保存完整 `DailySummaryJobView`。
- Worker 写入 Redis 前重新计算任务指纹；与任务记录不一致时标记 `SUPERSEDED`。
- 业务任务事务提交后，通过指针找到旧结果 key 并删除。
- 删除失败不影响正确性，因为新任务指纹会指向新的缓存 key；TTL 负责最终清理。
- Redis 读写异常只记录日志，查询回退 MySQL。

## 任务变更失效范围

- 新建任务：失效新任务的 `ownerId + taskDate`。
- 修改任务：同时失效修改前和修改后的 `ownerId + taskDate`。
- 删除任务：失效被删除任务的 `ownerId + taskDate`。
- 失效动作必须发生在数据库事务提交后。

## 前端体验

打开日报弹窗后：

1. 调用创建接口。
2. 收到终态时立即展示结果。
3. 否则每 1 秒轮询，最多 180 秒。
4. 关闭弹窗只停止轮询，不取消后台任务。
5. 超时后提示任务仍在后台整理，可关闭并稍后重新打开。
6. 如果等待期间源任务变化导致任务变成 `SUPERSEDED`，自动重新创建一次任务。

等待区使用“正在起草的日报纸面”视觉隐喻：三条错落的内容线配合呼吸动画，文字随真实任务状态变化。不显示伪造百分比，遵守 `prefers-reduced-motion`。

## 验收标准

- 创建日报请求不再等待 AI 完成。
- 服务重启后 `QUEUED/RETRYING/RUNNING` 任务可以继续或恢复。
- 重复请求不重复调用 AI。
- 修改任务后不会返回旧缓存。
- Redis 不可用时仍可从 MySQL 返回已完成日报。
- AI 最终失败时返回模板内容并明确标记降级。
- 后端测试、前端测试和生产构建全部通过。
