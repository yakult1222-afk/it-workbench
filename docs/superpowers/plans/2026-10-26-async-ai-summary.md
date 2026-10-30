# AI 日报异步化与 Redis 缓存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 AI 日报从同步请求改为 MySQL 任务表驱动的异步任务，并用 Redis 缓存带任务指纹的日报结果。

**Architecture:** Spring Boot 内定时 Worker 通过 `SELECT ... FOR UPDATE SKIP LOCKED` 抢占 `ai_summary_job`，在事务外调用 AI，将最终正文写回 MySQL 并同步到 Redis。Redis 只是派生缓存，MySQL 是任务状态和正文的事实来源；不引入 MQ。

**Tech Stack:** Java 17、Spring Boot 3.2、MyBatis-Plus、MySQL 8、Spring Data Redis、Vue 3、Vitest。

**Spec:** `docs/superpowers/specs/2026-10-26-async-ai-summary-design.md`

## Global Constraints

- 不引入 MQ；异步队列由 MySQL 任务表实现。
- Redis 不可用时必须回退 MySQL，日报功能不能整体失败。
- AI 日报只针对当前登录用户本人。
- Worker 每 1 秒轮询，并发 2，租约 120 秒，最多执行 3 次。
- 重试等待配置为 `5,20,60` 秒；因为最多执行 3 次，当前只会使用前两个等待值。
- Redis 结果缓存和指针缓存 TTL 均为 30 分钟。
- 轮询间隔 1 秒，前端最长等待 180 秒。
- 不显示伪造百分比进度。
- 所有新增前端动画必须支持 `prefers-reduced-motion`。
- 保留未配置 AI 和当天无任务时直接使用模板的现有行为。

## Review Focus

- **任务并发修改：** Worker 完成 AI 后、写缓存前源任务发生变化，旧内容不能写回 Redis。
- **事务边界：** 缓存失效不能在任务事务提交前执行；AI 调用不能持有 `ai_summary_job` 行锁。
- **Worker 重启：** `RUNNING` 任务租约过期后必须可恢复，已完成任务不能被清理器删除。
- **重复请求：** 同一用户、日期和任务指纹只能执行一次 AI；缓存不可用时仍应复用 MySQL 中的任务。
- **前端断线：** 关闭弹窗不能取消后台任务，重新打开应复用到进行中的任务。

---

### Task 1: 建立任务表、状态和持久化模型

**Files:**
- Create: `backend/sql/migrate-v3-ai-summary.sql`
- Modify: `backend/sql/init.sql`
- Create: `backend/src/main/java/com/qinghuan/workbench/common/DailySummaryJobStatus.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/entity/AiSummaryJob.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/mapper/AiSummaryJobMapper.java`
- Test: `backend/src/test/java/com/qinghuan/workbench/common/DailySummaryJobStatusTest.java`

**Interfaces:**
- Produces: `DailySummaryJobStatus.isActive(String): boolean`
- Produces: `DailySummaryJobStatus.isTerminal(String): boolean`
- Produces: `AiSummaryJob` fields matching the SQL columns.
- Produces: `AiSummaryJobMapper extends BaseMapper<AiSummaryJob>`.

- [ ] **Step 1: 写状态行为失败测试**

```java
package com.qinghuan.workbench.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailySummaryJobStatusTest {
    @Test
    void activeStatusesRepresentJobsThatStillNeedPolling() {
        assertTrue(DailySummaryJobStatus.isActive("QUEUED"));
        assertTrue(DailySummaryJobStatus.isActive("RUNNING"));
        assertTrue(DailySummaryJobStatus.isActive("RETRYING"));
        assertFalse(DailySummaryJobStatus.isActive("SUCCEEDED"));
        assertFalse(DailySummaryJobStatus.isActive("DEGRADED"));
        assertFalse(DailySummaryJobStatus.isActive("FAILED"));
        assertFalse(DailySummaryJobStatus.isActive("SUPERSEDED"));
    }

    @Test
    void terminalStatusesStopPolling() {
        assertTrue(DailySummaryJobStatus.isTerminal("SUCCEEDED"));
        assertTrue(DailySummaryJobStatus.isTerminal("DEGRADED"));
        assertTrue(DailySummaryJobStatus.isTerminal("FAILED"));
        assertTrue(DailySummaryJobStatus.isTerminal("SUPERSEDED"));
        assertFalse(DailySummaryJobStatus.isTerminal("QUEUED"));
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -q -Dtest=DailySummaryJobStatusTest test
```

Expected: 编译失败，`DailySummaryJobStatus` 尚不存在。

- [ ] **Step 3: 实现状态常量**

```java
package com.qinghuan.workbench.common;

import java.util.Set;

public final class DailySummaryJobStatus {
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String RETRYING = "RETRYING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String DEGRADED = "DEGRADED";
    public static final String FAILED = "FAILED";
    public static final String SUPERSEDED = "SUPERSEDED";

    private static final Set<String> ACTIVE = Set.of(QUEUED, RUNNING, RETRYING);
    private static final Set<String> TERMINAL =
            Set.of(SUCCEEDED, DEGRADED, FAILED, SUPERSEDED);

    private DailySummaryJobStatus() {
    }

    public static boolean isActive(String status) {
        return ACTIVE.contains(status);
    }

    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }
}
```

- [ ] **Step 4: 添加迁移和初始化 SQL**

创建 `backend/sql/migrate-v3-ai-summary.sql`，并把完全相同的 `CREATE TABLE` 放到 `backend/sql/init.sql` 的 `task` 表之后：

```sql
USE it_workbench;

CREATE TABLE IF NOT EXISTS ai_summary_job (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    owner_id      BIGINT        NOT NULL,
    summary_date  DATE          NOT NULL,
    input_hash    CHAR(64)      NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    attempt_count INT           NOT NULL DEFAULT 0,
    next_run_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until   DATETIME(3)   NULL,
    content       VARCHAR(4000) NULL,
    source        VARCHAR(20)   NULL,
    task_count    INT           NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at   DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_summary_job_input (owner_id, summary_date, input_hash),
    KEY idx_ai_summary_job_claim (status, next_run_at),
    KEY idx_ai_summary_job_lease (status, lease_until),
    CONSTRAINT fk_ai_summary_job_owner
        FOREIGN KEY (owner_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='AI 日报异步任务';
```

- [ ] **Step 5: 添加实体**

```java
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
```

- [ ] **Step 6: 添加 Mapper**

```java
package com.qinghuan.workbench.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qinghuan.workbench.entity.AiSummaryJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiSummaryJobMapper extends BaseMapper<AiSummaryJob> {
}
```

- [ ] **Step 7: 运行状态测试**

Run:

```bash
cd backend
mvn -q -Dtest=DailySummaryJobStatusTest test
```

Expected: 2 tests pass。

- [ ] **Step 8: 提交**

```bash
git add backend/sql backend/src/main/java/com/qinghuan/workbench/common/DailySummaryJobStatus.java \
  backend/src/main/java/com/qinghuan/workbench/entity/AiSummaryJob.java \
  backend/src/main/java/com/qinghuan/workbench/mapper/AiSummaryJobMapper.java \
  backend/src/test/java/com/qinghuan/workbench/common/DailySummaryJobStatusTest.java
git commit -m "feat: add ai summary job persistence"
```

---

### Task 2: 实现任务指纹和 Redis 缓存

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/summary/DailySummaryJobView.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/service/TaskFingerprintService.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/service/DailySummaryCacheService.java`
- Test: `backend/src/test/java/com/qinghuan/workbench/service/TaskFingerprintServiceTest.java`
- Test: `backend/src/test/java/com/qinghuan/workbench/service/DailySummaryCacheServiceTest.java`

**Interfaces:**
- Produces: `DailySummaryJobView(Long jobId, LocalDate date, String status, String source, Integer taskCount, String content, String errorMessage)`
- Produces: `TaskFingerprintService.fingerprint(List<Task>): String`
- Produces: `DailySummaryCacheService.get(Long, LocalDate, String): Optional<DailySummaryJobView>`
- Produces: `DailySummaryCacheService.put(Long, DailySummaryJobView, String): void`
- Produces: `DailySummaryCacheService.evictAfterCommit(Long, LocalDate): void`

- [ ] **Step 1: 写指纹测试**

```java
package com.qinghuan.workbench.service;

import com.qinghuan.workbench.entity.Task;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TaskFingerprintServiceTest {
    private final TaskFingerprintService service = new TaskFingerprintService();

    @Test
    void sameTaskContentProducesSameFingerprint() {
        assertEquals(service.fingerprint(List.of(task("完成", "修复打印机"))),
                service.fingerprint(List.of(task("完成", "修复打印机"))));
    }

    @Test
    void changedTaskSummaryChangesFingerprint() {
        Task before = task("完成", "修复打印机");
        Task after = task("完成", "修复打印机");
        after.setSummary("已更换搓纸轮");

        assertNotEquals(service.fingerprint(List.of(before)),
                service.fingerprint(List.of(after)));
    }

    private Task task(String status, String description) {
        Task task = new Task();
        task.setId(1L);
        task.setOwnerId(7L);
        task.setTaskDate(LocalDate.of(2026, 10, 26));
        task.setTaskType("硬件问题");
        task.setDescription(description);
        task.setStatus(status);
        return task;
    }
}
```

- [ ] **Step 2: 写缓存失效边界测试**

```java
package com.qinghuan.workbench.service;

import com.qinghuan.workbench.dto.summary.DailySummaryJobView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DailySummaryCacheServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final DailySummaryCacheService service =
            new DailySummaryCacheService(redis, new com.fasterxml.jackson.databind.ObjectMapper(), 1800);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void evictionRunsOnlyAfterCommitWhenTransactionIsActive() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("workbench:ai-summary:pointer:7:2026-10-26")).thenReturn("abc");
        TransactionSynchronizationManager.initSynchronization();

        service.evictAfterCommit(7L, LocalDate.of(2026, 10, 26));

        verify(redis, never()).delete(anyString());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(redis).delete("workbench:ai-summary:7:2026-10-26:abc");
    }

    @Test
    void redisFailureBecomesCacheMissInsteadOfUserError() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertTrue(service.get(7L, LocalDate.of(2026, 10, 26), "abc").isEmpty());
    }
}
```

- [ ] **Step 3: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -q -Dtest=TaskFingerprintServiceTest,DailySummaryCacheServiceTest test
```

Expected: 编译失败，两个服务尚不存在。

- [ ] **Step 4: 添加 Redis 依赖和配置**

`backend/pom.xml` 增加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

`application.yml` 合并以下配置，不要创建重复的顶层 `spring` 或 `workbench` key：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: ${REDIS_TIMEOUT_MS:2000}ms

workbench:
  daily-summary:
    cache-ttl-seconds: ${AI_SUMMARY_CACHE_TTL_SECONDS:1800}
```

- [ ] **Step 5: 实现 DTO 和指纹**

```java
public record DailySummaryJobView(
        Long jobId,
        LocalDate date,
        String status,
        String source,
        Integer taskCount,
        String content,
        String errorMessage) {
}
```

```java
@Service
public class TaskFingerprintService {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String fingerprint(List<Task> tasks) {
        List<Map<String, Object>> canonical = tasks.stream()
                .sorted(Comparator.comparing(Task::getId))
                .map(task -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", task.getId());
                    row.put("ownerId", task.getOwnerId());
                    row.put("date", task.getTaskDate());
                    row.put("type", task.getTaskType());
                    row.put("description", task.getDescription());
                    row.put("status", task.getStatus());
                    row.put("summary", task.getSummary());
                    return row;
                })
                .toList();
        try {
            return DigestUtils.sha256Hex(objectMapper.writeValueAsBytes(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法计算日报任务指纹", e);
        }
    }
}
```

- [ ] **Step 6: 实现 Redis 缓存**

缓存 key 必须使用设计文档中的格式。`put(ownerId, view, hash)` 同时写结果和指针；`evictAfterCommit` 在事务同步激活时注册 `afterCommit`，否则立即失效。类上增加 Lombok `@Slf4j`。

```java
@Service
public class DailySummaryCacheService {
    private static final String RESULT_PREFIX = "workbench:ai-summary:";
    private static final String POINTER_PREFIX = "workbench:ai-summary:pointer:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public Optional<DailySummaryJobView> get(Long ownerId, LocalDate date, String hash) {
        try {
            String json = redis.opsForValue().get(resultKey(ownerId, date, hash));
            return json == null ? Optional.empty()
                    : Optional.of(objectMapper.readValue(json, DailySummaryJobView.class));
        } catch (Exception e) {
            log.warn("读取日报缓存失败，回退 MySQL: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(Long ownerId, DailySummaryJobView view, String hash) {
        try {
            String json = objectMapper.writeValueAsString(view);
            redis.opsForValue().set(resultKey(ownerId, view.date(), hash), json, ttl);
            redis.opsForValue().set(pointerKey(ownerId, view.date()), hash, ttl);
        } catch (Exception e) {
            log.warn("写入日报缓存失败: {}", e.getMessage());
        }
    }

    public void evictAfterCommit(Long ownerId, LocalDate date) {
        Runnable eviction = () -> evictNow(ownerId, date);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            eviction.run();
                        }
                    });
        } else {
            eviction.run();
        }
    }
}
```

实现时把缓存方法的 owner 参数保持为 `Long ownerId`，不要从 DTO 中反推用户身份。

- [ ] **Step 7: 运行两个测试**

Run:

```bash
cd backend
mvn -q -Dtest=TaskFingerprintServiceTest,DailySummaryCacheServiceTest test
```

Expected: 全部通过。

- [ ] **Step 8: 提交**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml \
  backend/src/main/java/com/qinghuan/workbench/dto/summary/DailySummaryJobView.java \
  backend/src/main/java/com/qinghuan/workbench/service/TaskFingerprintService.java \
  backend/src/main/java/com/qinghuan/workbench/service/DailySummaryCacheService.java \
  backend/src/test/java/com/qinghuan/workbench/service/TaskFingerprintServiceTest.java \
  backend/src/test/java/com/qinghuan/workbench/service/DailySummaryCacheServiceTest.java
git commit -m "feat: cache ai summary by task fingerprint"
```

---

### Task 3: 实现日报任务创建、复用和查询接口

**Files:**
- Create: `backend/src/main/java/com/qinghuan/workbench/service/DailySummaryJobService.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/controller/DailySummaryController.java`
- Test: `backend/src/test/java/com/qinghuan/workbench/service/DailySummaryJobServiceTest.java`
- Test: `backend/src/test/java/com/qinghuan/workbench/controller/DailySummaryControllerSecurityTest.java`

**Interfaces:**
- Consumes: `TaskService.listByDate(LocalDate, Long): List<Task>`
- Consumes: `TaskFingerprintService.fingerprint(List<Task>): String`
- Consumes: `DailySummaryCacheService.get/put`
- Produces: `DailySummaryJobService.submit(Long ownerId, LocalDate date): DailySummaryJobView`
- Produces: `DailySummaryJobService.get(Long jobId, Long ownerId): DailySummaryJobView`

- [ ] **Step 1: 写缓存命中测试**

```java
@Test
void cacheHitReturnsImmediatelyWithoutCreatingJob() {
    LocalDate date = LocalDate.of(2026, 10, 26);
    List<Task> tasks = List.of(task(7L, date));
    DailySummaryJobView cached = new DailySummaryJobView(
            12L, date, "SUCCEEDED", "ai", 1, "缓存日报", null);
    when(taskService.listByDate(date, 7L)).thenReturn(tasks);
    when(fingerprint.fingerprint(tasks)).thenReturn("hash");
    when(cache.get(7L, date, "hash")).thenReturn(Optional.of(cached));

    DailySummaryJobView result = service.submit(7L, date);

    assertEquals(cached, result);
    verify(mapper, never()).insert(any());
}
```

- [ ] **Step 2: 写任务复用和越权查询测试**

```java
@Test
void sameFingerprintReusesExistingJob() {
    AiSummaryJob existing = job(31L, 7L, date, "hash", "QUEUED");
    when(mapper.selectOne(any())).thenReturn(existing);
    when(cache.get(7L, date, "hash")).thenReturn(Optional.empty());

    DailySummaryJobView result = service.submit(7L, date);

    assertEquals(31L, result.jobId());
    verify(mapper, never()).insert(any());
}

@Test
void anotherUsersJobIsReportedAsNotFound() {
    when(mapper.selectById(31L)).thenReturn(job(31L, 8L, date, "hash", "QUEUED"));

    BusinessException error = assertThrows(BusinessException.class,
            () -> service.get(31L, 7L));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
}

@Test
void supersededJobWithoutContentIsRequeuedForSameFingerprint() {
    AiSummaryJob existing = job(31L, 7L, date, "hash", "SUPERSEDED");
    when(mapper.selectOne(any())).thenReturn(existing);
    when(cache.get(7L, date, "hash")).thenReturn(Optional.empty());

    DailySummaryJobView result = service.submit(7L, date);

    assertEquals("QUEUED", result.status());
    verify(mapper).updateById(argThat(job ->
            "QUEUED".equals(job.getStatus()) && job.getAttemptCount() == 0));
}
```

- [ ] **Step 3: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -q -Dtest=DailySummaryJobServiceTest test
```

Expected: 编译失败，`DailySummaryJobService` 尚不存在。

- [ ] **Step 4: 实现 Job Service**

`submit` 的顺序必须是：

1. 查询当前用户当天任务。
2. 计算指纹。
3. 查 Redis。
4. 命中则返回。
5. 按 `(ownerId, date, inputHash)` 查询任务。
6. 已存在且状态为 `FAILED/SUPERSEDED` 时重置为 `QUEUED`、`attemptCount=0`、`nextRunAt=now` 并返回。
7. 已存在且为其他状态时直接返回。
8. 插入 `QUEUED`，`nextRunAt=now`、`attemptCount=0`。

插入必须捕获 `DuplicateKeyException` 并重新查询唯一键对应任务，处理两个请求同时创建同一条任务的竞态。

`get` 查询数据库，任务不存在或 `ownerId` 不同时抛 `BusinessException(HttpStatus.NOT_FOUND, "日报任务不存在")`。

新增任务的精确赋值：

```java
AiSummaryJob job = new AiSummaryJob();
job.setOwnerId(ownerId);
job.setSummaryDate(date);
job.setInputHash(hash);
job.setStatus(DailySummaryJobStatus.QUEUED);
job.setAttemptCount(0);
job.setNextRunAt(LocalDateTime.now());
job.setTaskCount(tasks.size());
mapper.insert(job);
return toView(job);
```

- [ ] **Step 5: 替换 Controller 接口**

```java
@PostMapping("/jobs")
@PreAuthorize("hasAuthority('task:self:view')")
public Result<DailySummaryJobView> submit(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    LoginUser user = currentUserService.require();
    LocalDate target = date == null ? LocalDate.now() : date;
    return Result.ok(jobService.submit(user.getId(), target));
}

@GetMapping("/jobs/{jobId}")
@PreAuthorize("hasAuthority('task:self:view')")
public Result<DailySummaryJobView> status(@PathVariable Long jobId) {
    LoginUser user = currentUserService.require();
    return Result.ok(jobService.get(jobId, user.getId()));
}
```

旧同步 `GET /api/daily-summary` 不再保留。

- [ ] **Step 6: 写 Controller 安全测试**

使用现有 `@WebMvcTest` 和 `@Import(SecurityConfig...)` 模式验证：

- 无 token 调用 `POST /api/daily-summary/jobs` 返回 401。
- 有权限的请求返回 `jobId`。
- 其他用户的任务透传 404。

- [ ] **Step 7: 运行服务与控制器测试**

Run:

```bash
cd backend
mvn -q -Dtest=DailySummaryJobServiceTest,DailySummaryControllerSecurityTest test
```

Expected: 全部通过。

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/qinghuan/workbench/service/DailySummaryJobService.java \
  backend/src/main/java/com/qinghuan/workbench/controller/DailySummaryController.java \
  backend/src/test/java/com/qinghuan/workbench/service/DailySummaryJobServiceTest.java \
  backend/src/test/java/com/qinghuan/workbench/controller/DailySummaryControllerSecurityTest.java
git commit -m "feat: add async ai summary job api"
```

---

### Task 4: 在业务任务变更后及时失效日报缓存

**Files:**
- Modify: `backend/src/main/java/com/qinghuan/workbench/service/TaskService.java`
- Modify: `backend/src/test/java/com/qinghuan/workbench/service/TaskServiceTest.java`

**Interfaces:**
- Consumes: `DailySummaryCacheService.evictAfterCommit(Long, LocalDate): void`
- Produces: task create/update/delete each wrap their DB mutation in `@Transactional`.

- [ ] **Step 1: 写新增和删除失效测试**

在 `TaskServiceTest` 增加：

```java
@Test
void createInvalidatesSummaryForTaskOwnerAndDate() {
    LoginUser user = loginUser(7L, "task:self:view", "task:self:manage");
    when(taskScopeService.canManageAll(user)).thenReturn(false);
    when(ownerMapper.selectById(7L)).thenReturn(enabledUser(7L));
    Task request = task(null, 7L);
    LocalDate date = LocalDate.of(2026, 10, 26);
    request.setTaskDate(date);

    service.create(request, user);

    verify(summaryCache).evictAfterCommit(7L, date);
}

@Test
void deleteInvalidatesSummaryForDeletedTask() {
    LoginUser user = loginUser(7L, "task:self:view", "task:self:manage");
    Task current = task(3L, 7L);
    when(taskScopeService.requireManageable(3L, user)).thenReturn(current);
    when(taskMapper.deleteById(3L)).thenReturn(1);

    service.delete(3L, user);

    verify(summaryCache).evictAfterCommit(7L, current.getTaskDate());
}
```

- [ ] **Step 2: 写负责人或日期变化同时失效两侧的测试**

```java
@Test
void updateInvalidatesOldAndNewSummaryScopes() {
    LoginUser user = loginUser(2L, "task:view:all", "task:manage:all");
    Task current = task(3L, 7L);
    current.setTaskDate(LocalDate.of(2026, 10, 25));
    Task request = task(3L, 8L);
    request.setTaskDate(LocalDate.of(2026, 10, 26));
    when(taskScopeService.requireManageable(3L, user)).thenReturn(current);
    when(taskScopeService.canManageAll(user)).thenReturn(true);
    when(ownerMapper.selectById(8L)).thenReturn(enabledUser(8L));

    service.update(request, user);

    verify(summaryCache).evictAfterCommit(7L, LocalDate.of(2026, 10, 25));
    verify(summaryCache).evictAfterCommit(8L, LocalDate.of(2026, 10, 26));
}
```

- [ ] **Step 3: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -q -Dtest=TaskServiceTest test
```

Expected: 构造器不匹配或失效方法调用未发生。

- [ ] **Step 4: 注入缓存服务并修改 TaskService**

增加字段 `private final DailySummaryCacheService summaryCache;`。

给 `create`、`update`、`delete` 增加 `@Transactional`，在数据库操作成功后调用 `evictAfterCommit`。`update` 在修改实体前保存旧 `ownerId/taskDate`；仅当 owner/date 发生变化才调用两次，相同则调用一次。

同步修改 `TaskServiceTest.setUp()`，增加 `summaryCache` mock 并传给新的 `TaskService` 构造器，避免所有既有测试因构造器变化而失败。

- [ ] **Step 5: 运行完整 TaskService 测试**

Run:

```bash
cd backend
mvn -q -Dtest=TaskServiceTest test
```

Expected: 所有测试通过。

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/qinghuan/workbench/service/TaskService.java \
  backend/src/test/java/com/qinghuan/workbench/service/TaskServiceTest.java
git commit -m "feat: invalidate summary cache after task changes"
```

---

### Task 5: 实现数据库抢占 Worker、重试和指纹保护

**Files:**
- Modify: `backend/src/main/java/com/qinghuan/workbench/mapper/AiSummaryJobMapper.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/service/DailySummaryJobService.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/service/DailySummaryWorker.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/config/DailySummaryWorkerConfig.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/ItWorkbenchApplication.java`
- Test: `backend/src/test/java/com/qinghuan/workbench/service/DailySummaryWorkerTest.java`

**Interfaces:**
- Consumes: `DailySummaryService.aiEnabled(): boolean`
- Consumes: `DailySummaryService.callAi(LocalDate, List<Task>): String`
- Consumes: `DailySummaryService.templateSummary(LocalDate, List<Task>): String`
- Produces: `DailySummaryJobService.claimNext(): AiSummaryJob`
- Produces: `DailySummaryJobService.markRetrying(AiSummaryJob, Throwable): void`
- Produces: `DailySummaryJobService.complete(AiSummaryJob, String, String, String): boolean`
- Produces: `DailySummaryJobService.supersede(AiSummaryJob): void`
- Produces: `DailySummaryJobService.fail(AiSummaryJob, Exception): void`

- [ ] **Step 1: 写 Worker 成功与旧指纹测试**

```java
@Test
void successfulAiResultIsStoredAndCached() {
    AiSummaryJob job = runningJob("hash");
    List<Task> tasks = List.of(task("完成", "修复打印机"));
    when(jobService.claimNext()).thenReturn(job);
    when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId())).thenReturn(tasks);
    when(fingerprint.fingerprint(tasks)).thenReturn("hash");
    when(summaryService.aiEnabled()).thenReturn(true);
    when(summaryService.callAi(job.getSummaryDate(), tasks)).thenReturn("AI 日报");

    worker.runOne();

    verify(jobService).complete(job, "AI 日报", "ai", "SUCCEEDED");
    verify(cache).put(eq(job.getOwnerId()), any(DailySummaryJobView.class), eq("hash"));
}

@Test
void changedFingerprintSupersedesJobWithoutCaching() {
    AiSummaryJob job = runningJob("old-hash");
    when(jobService.claimNext()).thenReturn(job);
    when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId())).thenReturn(tasks);
    when(fingerprint.fingerprint(tasks)).thenReturn("new-hash");

    worker.runOne();

    verify(jobService).supersede(job);
    verify(cache, never()).put(any(), any(), anyString());
}

@Test
void missingAiConfigurationCompletesWithTemplate() {
    AiSummaryJob job = runningJob("hash");
    List<Task> tasks = List.of(task("完成", "修复打印机"));
    when(jobService.claimNext()).thenReturn(job);
    when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId())).thenReturn(tasks);
    when(fingerprint.fingerprint(tasks)).thenReturn("hash");
    when(summaryService.aiEnabled()).thenReturn(false);
    when(summaryService.templateSummary(job.getSummaryDate(), tasks)).thenReturn("模板日报");

    worker.runOne();

    verify(jobService).complete(job, "模板日报", "template", "SUCCEEDED");
}

@Test
void unexpectedProgramErrorMarksJobFailed() {
    AiSummaryJob job = runningJob("hash");
    when(jobService.claimNext()).thenReturn(job);
    when(taskService.listByDate(job.getSummaryDate(), job.getOwnerId()))
            .thenThrow(new IllegalStateException("serialization failed"));

    worker.runOne();

    verify(jobService).fail(eq(job), any(IllegalStateException.class));
}
```

- [ ] **Step 2: 写重试耗尽降级测试**

```java
@Test
void exhaustedAiRetriesProduceDegradedTemplate() {
    AiSummaryJob job = runningJob("hash");
    when(jobService.claimNext()).thenReturn(job);
    when(taskService.listByDate(any(), anyLong())).thenReturn(tasks);
    when(fingerprint.fingerprint(tasks)).thenReturn("hash");
    when(summaryService.aiEnabled()).thenReturn(true);
    when(summaryService.callAi(any(), eq(tasks))).thenThrow(new RuntimeException("boom"));
    when(summaryService.templateSummary(any(), eq(tasks))).thenReturn("模板日报");

    worker.runOne();

    verify(jobService).complete(job, "模板日报", "template", "DEGRADED");
    verify(cache).put(eq(job.getOwnerId()), any(DailySummaryJobView.class), eq("hash"));
}
```

- [ ] **Step 3: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -q -Dtest=DailySummaryWorkerTest test
```

Expected: 编译失败，Worker 尚不存在。

- [ ] **Step 4: 添加原子抢占 SQL**

`AiSummaryJobMapper` 增加：

```java
@Select("""
        SELECT * FROM ai_summary_job
        WHERE (status IN ('QUEUED', 'RETRYING') AND next_run_at <= #{now})
           OR (status = 'RUNNING' AND lease_until < #{now})
        ORDER BY next_run_at, id
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """)
AiSummaryJob selectClaimable(@Param("now") LocalDateTime now);

@Update("""
        UPDATE ai_summary_job
        SET status = 'RUNNING',
            attempt_count = attempt_count + 1,
            lease_until = #{leaseUntil},
            updated_at = #{now}
        WHERE id = #{id}
        """)
int markRunning(@Param("id") Long id,
                @Param("leaseUntil") LocalDateTime leaseUntil,
                @Param("now") LocalDateTime now);
```

`DailySummaryJobService.claimNext()` 使用 `@Transactional`：短事务内 select + mark，提交后返回任务。不要在该事务里调用 AI。

增加单元测试 `claimNextMarksRunningWithLease()`：mock `selectClaimable` 返回任务，断言返回状态是 `RUNNING`、`attemptCount` 加 1，并验证 `markRunning` 收到未来的 `leaseUntil`。

- [ ] **Step 5: 实现 Worker 状态机**

执行顺序固定为：

1. `claimNext()`。
2. 加载当前任务列表并计算指纹。
3. 指纹与 `job.inputHash` 不一致：`supersede` 后返回。
4. 无 AI 或任务为空：生成模板，完成状态 `SUCCEEDED`。
5. AI 调用成功：再次计算指纹；指纹变化时标记 `SUPERSEDED`，否则完成 `SUCCEEDED`。
6. AI 异常且 `attemptCount < 3`：`markRetrying`，等待 5 或 20 秒。
7. AI 异常且 `attemptCount >= 3`：模板完成，状态 `DEGRADED`。
8. 只对最终成功/DEGRADED 结果写 Redis；Redis 失败不影响 MySQL 完成状态。
9. 非 AI、非并发竞争的程序异常标记 `FAILED`、写入 `errorMessage` 和 `finishedAt`；如果数据库本身不可用，记录错误并等待租约恢复。

`complete` 使用 `WHERE id=? AND status='RUNNING'` 条件更新。完成后再写缓存；业务任务在“最终指纹检查”和“缓存写入”之间变化时，新指纹不会命中旧缓存。

- [ ] **Step 6: 配置调度和线程池**

```yaml
workbench:
  daily-summary:
    worker:
      enabled: ${AI_SUMMARY_WORKER_ENABLED:true}
      poll-interval-ms: 1000
      concurrency: 2
      lease-seconds: 120
      max-attempts: 3
      retry-delays-seconds: 5,20,60
```

使用 `@EnableScheduling`、固定大小 2 的 `ThreadPoolTaskExecutor` 和一个信号量限制并发。没有空闲槽位时不抢占任务。应用关闭时等待正在执行的 AI 调用最多 30 秒。

- [ ] **Step 7: 实现 30 天清理**

每天凌晨 3 点删除 `finished_at < now - 30 days` 且状态为 `SUCCEEDED/DEGRADED/FAILED/SUPERSEDED` 的任务。

- [ ] **Step 8: 运行 Worker 测试和完整后端测试**

Run:

```bash
cd backend
mvn -q -Dtest=DailySummaryWorkerTest test
mvn test
```

Expected: 全部通过。

- [ ] **Step 9: 提交**

```bash
git add backend/src/main/java/com/qinghuan/workbench/mapper/AiSummaryJobMapper.java \
  backend/src/main/java/com/qinghuan/workbench/service/DailySummaryJobService.java \
  backend/src/main/java/com/qinghuan/workbench/service/DailySummaryWorker.java \
  backend/src/main/java/com/qinghuan/workbench/config/DailySummaryWorkerConfig.java \
  backend/src/main/java/com/qinghuan/workbench/ItWorkbenchApplication.java \
  backend/src/test/java/com/qinghuan/workbench/service/DailySummaryWorkerTest.java
git commit -m "feat: run ai summaries through database worker"
```

---

### Task 6: 前端异步轮询和等待动画

**Files:**
- Modify: `frontend/src/api/index.js`
- Create: `frontend/src/api/dailySummaryPolling.js`
- Modify: `frontend/src/views/TasksView.vue`
- Test: `frontend/src/api/dailySummaryPolling.test.js`

**Interfaces:**
- Consumes: `POST /api/daily-summary/jobs`
- Consumes: `GET /api/daily-summary/jobs/{jobId}`
- Produces: `pollDailySummary({ jobId, load, onUpdate, intervalMs, timeoutMs }): Promise<DailySummaryJobView>`
- Produces: `dailySummaryStageCopy(status, elapsedMs): string`

- [ ] **Step 1: 写轮询行为测试**

```javascript
import { describe, expect, it, vi } from 'vitest'
import { pollDailySummary, dailySummaryStageCopy } from './dailySummaryPolling'

describe('pollDailySummary', () => {
  it('continues through RETRYING and resolves on DEGRADED', async () => {
    const load = vi.fn()
      .mockResolvedValueOnce({ status: 'RETRYING' })
      .mockResolvedValueOnce({ status: 'DEGRADED', content: '模板日报' })
    const onUpdate = vi.fn()

    const result = await pollDailySummary({
      jobId: 1,
      load,
      onUpdate,
      intervalMs: 0,
      timeoutMs: 1000
    })

    expect(result.status).toBe('DEGRADED')
    expect(onUpdate).toHaveBeenCalledWith({ status: 'RETRYING' })
  })

  it('returns extended-wait copy after 45 seconds', () => {
    expect(dailySummaryStageCopy('RUNNING', 46000)).toContain('还需要一点时间')
  })

  it('stops polling when the caller closes the modal', async () => {
    const controller = new AbortController()
    controller.abort()

    await expect(pollDailySummary({
      jobId: 1,
      load: vi.fn(),
      signal: controller.signal,
      intervalMs: 0,
      timeoutMs: 1000
    })).rejects.toMatchObject({ code: 'DAILY_SUMMARY_POLL_ABORTED' })
  })
})
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd frontend
npm test -- dailySummaryPolling.test.js
```

Expected: 模块不存在。

- [ ] **Step 3: 修改 API 客户端**

```javascript
export const dailySummaryApi = {
  create(date) {
    return http.post('/daily-summary/jobs', null, { params: date ? { date } : {} })
  },
  status(jobId) {
    return http.get(`/daily-summary/jobs/${jobId}`)
  }
}
```

- [ ] **Step 4: 实现轮询模块**

- 终态：`SUCCEEDED/DEGRADED/FAILED/SUPERSEDED`。
- 活动状态：`QUEUED/RUNNING/RETRYING`。
- 每 1 秒查询一次。
- 超过 45 秒使用延长等待文案。
- 180 秒抛出具名错误 `DAILY_SUMMARY_POLL_TIMEOUT`。
- 轮询支持外部停止信号；关闭弹窗时停止定时器。
- 收到 `SUPERSEDED` 时自动重新调用一次创建接口；新任务仍为 `SUPERSEDED` 时停止并显示错误，防止无限重试。

状态文案必须包含：

```text
QUEUED    日报已进入整理队列…
RUNNING   正在翻阅今天的任务记录…
RUNNING   正在梳理完成、进行中和延期事项…
RETRYING  AI 有点忙，正在重新整理…
超时       日报还在后台整理，可以关闭弹窗稍后再看
```

- [ ] **Step 5: 接入 TasksView**

- `openDaily()` 先调用 `dailySummaryApi.create()`。
- 若返回终态，直接展示。
- 否则启动轮询并保存 `activeJobId`、开始时间和停止句柄。
- `SUPERSEDED` 自动创建新任务一次；`FAILED` 显示重试按钮。
- 关闭弹窗或组件卸载时停止轮询，不调用取消接口。
- 超时后显示“日报仍在后台整理”，保留关闭按钮。
- 重新打开时再次 `create()`，由后端复用同一任务。

- [ ] **Step 6: 实现等待视觉**

把原来的单行 spinner 替换成“正在起草的日报纸面”：

```text
┌──────────────────────────┐
│  ━━━━━━━━                │
│      ━━━━━━━━━━━━━        │
│  ━━━━━━━━━━━              │
│                          │
│  正在梳理今天的任务脉络…   │
│  日报只基于你今天的记录生成 │
└──────────────────────────┘
```

- 三条内容线按 1.4 秒错峰呼吸，不使用百分比。
- 主文案使用 `aria-live="polite"`。
- `prefers-reduced-motion: reduce` 时关闭呼吸和省略号动画，只显示静态状态文字。
- 保留现有奶油画布、珊瑚主色、衬线标题和 8/12/16px 圆角体系；不新增通用卡片套件。

- [ ] **Step 7: 运行前端测试和构建**

Run:

```bash
cd frontend
npm test
npm run build
```

Expected: 测试通过，Vite 构建成功。

- [ ] **Step 8: 提交**

```bash
git add frontend/src/api/index.js frontend/src/api/dailySummaryPolling.js \
  frontend/src/api/dailySummaryPolling.test.js frontend/src/views/TasksView.vue
git commit -m "feat: poll async daily summary with staged wait copy"
```

---

### Task 7: 部署配置、文档和端到端验收

**Files:**
- Modify: `docker-compose.yml`
- Modify: `README.md`
- Modify: `backend/.env.example`
- Create: `scripts/verify-ai-summary-api.sh`

**Interfaces:**
- Consumes: all APIs and configuration from Tasks 1-6.
- Produces: one-command Redis/MySQL/backend deployment and repeatable API smoke test.

- [ ] **Step 1: 加入 Redis 服务**

`docker-compose.yml` 增加：

```yaml
redis:
  image: redis:7-alpine
  container_name: it-workbench-redis
  restart: unless-stopped
  command: ["redis-server", "--appendonly", "yes"]
  ports:
    - "6379:6379"
  volumes:
    - redis-data:/data
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 5s
    timeout: 3s
    retries: 20
```

backend 增加 `REDIS_HOST=redis`、`REDIS_PORT=6379`，并在 `depends_on` 中要求 Redis healthy。新增 `redis-data` volume。

- [ ] **Step 2: 更新环境变量模板和 README**

文档必须说明：

- `REDIS_HOST/REDIS_PORT/REDIS_PASSWORD`
- 异步接口和状态含义
- 无 MQ，MySQL 任务表即队列
- Worker 参数及 30 天清理
- Redis 故障时回退 MySQL
- 旧库执行 `backend/sql/migrate-v3-ai-summary.sql`

- [ ] **Step 3: 添加可重复 smoke test**

`scripts/verify-ai-summary-api.sh` 使用环境变量接收 `BASE_URL`、`USERNAME`、`PASSWORD`：

1. 登录获取 JWT。
2. `POST /api/daily-summary/jobs`。
3. 每秒轮询，最多 180 秒。
4. 终态后断言 `content` 非空、`source` 为 `ai/template`。
5. 再次 POST，断言返回相同 `jobId` 或缓存任务。
6. 拒绝在失败时只打印 JSON 并退出 0。

- [ ] **Step 4: 执行迁移**

Run:

```bash
mysql -uroot -proot123456 -h127.0.0.1 it_workbench \
  < backend/sql/migrate-v3-ai-summary.sql
```

Expected: 表创建成功，重复执行仍成功。

- [ ] **Step 5: 启动基础设施和后端**

Run:

```bash
docker compose up -d mysql redis
./scripts/start-backend.sh
```

Expected: Redis PING 返回 PONG，后端日志出现调度器启动且没有 SQL 映射错误。

- [ ] **Step 6: 执行端到端验收**

Run:

```bash
./scripts/verify-ai-summary-api.sh
```

Expected: 返回终态，日报正文非空。

额外验证：

1. 打开任务页日报弹窗，确认阶段文案会随状态变化。
2. 关闭弹窗后任务继续执行；重新打开能复用任务。
3. 修改当天任务，再打开日报，不得返回旧内容。
4. 停止 Redis 后查询已完成任务，仍能从 MySQL 返回正文。
5. 将 AI 地址改成不可达地址，确认最多重试后展示 `DEGRADED` 模板日报。

- [ ] **Step 7: 运行全量验证**

Run:

```bash
cd backend
mvn test

cd ../frontend
npm test
npm run build

cd ..
./scripts/build-all.sh
```

Expected: 所有测试通过，两个构建成功；任何已有失败必须逐项报告。

- [ ] **Step 8: 提交**

```bash
git add docker-compose.yml README.md backend/.env.example scripts/verify-ai-summary-api.sh
git commit -m "docs: document async ai summary deployment"
```

## Self-Review

- 异步接口、MySQL 事实来源、应用内 Worker、惰性缓存刷新和 Redis 降级均覆盖。
- 任务负责人或日期变化会失效旧、新两个缓存范围。
- 指纹在 Worker 开始和结束各校验一次，覆盖 AI 执行期间源任务变化。
- `FAILED` 保留给程序异常；AI 重试耗尽走 `DEGRADED`。
- 前端 180 秒轮询与后端重试时长兼容，超时后任务仍在后台继续。
- 3 次总尝试与 `5,20,60` 配置的差异已在约束和 Worker 任务中明确，避免实现人员误以为会执行 3 次重试。
