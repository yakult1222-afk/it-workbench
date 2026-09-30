# Login and RBAC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JWT login, permission-configurable roles, account management, task ownership, owner filtering, and per-user daily summaries while migrating all existing tasks to 清欢.

**Architecture:** Spring Security authenticates stateless JWT requests and enforces permission annotations. MyBatis-Plus stores users, roles, permissions, role-permission links, and task ownership; a dedicated task scope service enforces row-level access in the service layer. Vue adds guarded routes, a lightweight auth store, and protected account/role management pages.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Security 6, JJWT 0.12.6, MyBatis-Plus 3.5.5, MySQL, Vue 3, Vue Router, Axios, Vite.

**Spec:** `docs/superpowers/specs/2026-09-30-login-rbac-design.md`

## Global Constraints

- Work on the current `feat/login` branch; do not create or switch branches.
- One account has exactly one role; one role has many permissions.
- Permission codes are exactly: `task:self:view`, `task:self:manage`, `task:view:all`, `task:manage:all`, `user:manage`, `role:manage`.
- `task:self:manage` requires `task:self:view`; `task:manage:all` requires `task:view:all`.
- `SUPER_ADMIN` always has every permission, cannot be deleted, and cannot change code or permissions.
- Existing tasks must remain unchanged in count/content and all receive `owner_id = qinghuan.id`.
- Bootstrap accounts are `qinghuan / Qinghuan@123456` and `admin / Admin@123456`; first login does not force a password change.
- Passwords are 8–64 characters, contain at least one letter and one digit, and cannot equal the username.
- JWT expires after 8 hours by default and uses `JWT_SECRET`.
- Responses never include password hashes.
- Accounts are disabled, not deleted; tasks use `owner_id` with `ON DELETE RESTRICT`.
- Daily summaries always use the current user's ID, even when that user can view all tasks.
- Preserve the existing visual language in `frontend/src/assets/tokens.css`.
- Every task ends with a focused commit after its tests pass.

## Review Focus

- The last enabled super administrator must not be disabled or moved to another role; test this in both status and role-edit paths.
- A normal user must not read or mutate another user's task by changing `ownerId` or task ID; test queries and mutations.
- An existing JWT must stop working when its account is disabled; test token authentication against current database state.
- Migration must not leave tasks with null or incorrect ownership if seed account insertion fails; verify counts and ownership before adding `NOT NULL`.
- Spring Security must protect `/api/**` without blocking SPA HTML, assets, `/login`, or `/tasks`; test API and static-route behavior together.

---

### Task 1: Database Schema, Seed Data, and Migration

**Files:**
- Modify: `backend/sql/init.sql`
- Create: `backend/sql/migrate-v2-login-rbac.sql`
- Create: `scripts/verify-auth-migration.sh`
- Modify: `README.md`
- Test: `scripts/verify-auth-migration.sh`

**Interfaces:**
- Consumes: existing MySQL database `it_workbench`.
- Produces: tables `sys_role`, `sys_permission`, `sys_user`, `sys_role_permission`; `task.owner_id`; seeded roles, permissions, and accounts.

- [ ] **Step 1: Write the migration verification script**

Create `scripts/verify-auth-migration.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

MYSQL_BIN="${MYSQL_BIN:-$HOME/tools/mysql-26.7.0-macos15-arm64/bin/mysql}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"
MYSQL_DB="${MYSQL_DB:-it_workbench}"

query() {
  "$MYSQL_BIN" -N -B -h"$MYSQL_HOST" -P"$MYSQL_PORT" \
    -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" -e "$1"
}

users="$(query "SELECT COUNT(*) FROM sys_user WHERE username IN ('qinghuan','admin')")"
roles="$(query "SELECT COUNT(*) FROM sys_role WHERE code IN ('SUPER_ADMIN','USER')")"
permissions="$(query "SELECT COUNT(*) FROM sys_permission")"
total_tasks="$(query "SELECT COUNT(*) FROM task")"
owned_tasks="$(query "SELECT COUNT(*) FROM task t JOIN sys_user u ON u.id=t.owner_id WHERE u.username='qinghuan'")"

[ "$users" = "2" ] || { echo "expected 2 bootstrap users, got $users"; exit 1; }
[ "$roles" = "2" ] || { echo "expected 2 built-in roles, got $roles"; exit 1; }
[ "$permissions" = "6" ] || { echo "expected 6 permissions, got $permissions"; exit 1; }
[ "$total_tasks" = "$owned_tasks" ] || { echo "tasks not fully owned by qinghuan: $owned_tasks/$total_tasks"; exit 1; }

echo "auth migration ok: users=$users roles=$roles permissions=$permissions tasks=$total_tasks"
```

Run it before implementing:

```bash
chmod +x scripts/verify-auth-migration.sh
./scripts/verify-auth-migration.sh
```

Expected: FAIL because `sys_user` does not exist.

- [ ] **Step 2: Replace `backend/sql/init.sql` with the final schema**

Use this complete SQL as the new `backend/sql/init.sql`:

```sql
CREATE DATABASE IF NOT EXISTS it_workbench
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
USE it_workbench;

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    built_in    TINYINT(1)   NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB COMMENT='角色表';

CREATE TABLE IF NOT EXISTS sys_permission (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_permission_code (code)
) ENGINE=InnoDB COMMENT='权限表';

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(50)  NOT NULL,
    role_id       BIGINT       NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_role (role_id),
    CONSTRAINT fk_sys_user_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB COMMENT='登录账号表';

CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='角色权限关联表';

CREATE TABLE IF NOT EXISTS task (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id    BIGINT       NOT NULL,
    task_date   DATE         NOT NULL,
    task_type   VARCHAR(20)  NOT NULL,
    description VARCHAR(1000) DEFAULT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT '未完成',
    summary     VARCHAR(2000) DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_task_owner_date (owner_id, task_date),
    KEY idx_task_owner_status (owner_id, status),
    CONSTRAINT fk_task_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='IT 工作台任务表';

INSERT INTO sys_permission (id, code, name, description) VALUES
    (1, 'task:self:view',   '查看本人任务', '查看本人任务和个人统计'),
    (2, 'task:self:manage', '管理本人任务', '新增、修改、删除本人任务'),
    (3, 'task:view:all',    '查看全部任务', '查看所有账号的任务和统计'),
    (4, 'task:manage:all',  '管理全部任务', '新增、修改、删除所有账号的任务'),
    (5, 'user:manage',      '人员管理',     '管理登录账号'),
    (6, 'role:manage',      '角色管理',     '管理角色与角色权限');

INSERT INTO sys_role (id, code, name, description, built_in) VALUES
    (1, 'SUPER_ADMIN', '超级管理员', '系统内置超级管理员', 1),
    (2, 'USER',        '普通用户',   '系统内置普通用户',   1);

INSERT INTO sys_role_permission (role_id, permission_id) VALUES
    (1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6),
    (2, 1), (2, 2);

INSERT INTO sys_user (id, username, password_hash, display_name, role_id, enabled) VALUES
    (1, 'qinghuan', '$2y$10$L1b.OgdolKEbVqXjsWOSoej4O0PIhWSKXx7CnlyaTPP66Fg8DC9MG', '清欢', 2, 1),
    (2, 'admin',    '$2y$10$GPbRdlM/B2HrfNS/Ap4uv.TnEqeZyfTsdhJOHNscKeMEIBcFiRzS2', '超级管理员', 1, 1);

INSERT INTO task (owner_id, task_date, task_type, description, status, summary) VALUES
    (1, CURDATE(), '硬件问题', '3 楼办公区打印机卡纸且无法进纸，需现场排查搓纸轮磨损情况', '完成', '更换搓纸轮组件，测试打印 50 页无卡纸'),
    (1, CURDATE(), '软件问题', '财务部 ERP 客户端打不开报表模块，报缺少运行库', '完成', '安装对应运行库并更新客户端补丁，报表正常导出'),
    (1, CURDATE(), '审批', '新员工入职账号开通审批（研发部 2 人）', '未完成', NULL),
    (1, CURDATE(), '网络问题', '会议室 Wi-Fi 间歇性掉线，需检查 AP 固件与信道干扰', '延期', 'AP 固件升级需走变更窗口，已预约周六晚处理'),
    (1, CURDATE(), '审批', '打印机采购审批流转至 IT 部门确认配置参数', '未完成', NULL);
```

- [ ] **Step 3: Write the existing-database migration**

Create `backend/sql/migrate-v2-login-rbac.sql`. Put the four `CREATE TABLE IF NOT EXISTS` blocks, seed `INSERT` blocks, and permission/role/user IDs from Step 2 at the top. Then append this task-specific migration block:

```sql
ALTER TABLE task ADD COLUMN owner_id BIGINT NULL AFTER id;

UPDATE task t
JOIN sys_user u ON u.username = 'qinghuan'
SET t.owner_id = u.id
WHERE t.owner_id IS NULL;

SET @unowned = (SELECT COUNT(*) FROM task WHERE owner_id IS NULL);
SELECT IF(@unowned = 0, 1,
    CAST('task ownership migration guard failed' AS UNSIGNED));

ALTER TABLE task
    MODIFY COLUMN owner_id BIGINT NOT NULL,
    ADD KEY idx_task_owner_date (owner_id, task_date),
    ADD KEY idx_task_owner_status (owner_id, status),
    ADD CONSTRAINT fk_task_owner FOREIGN KEY (owner_id) REFERENCES sys_user(id) ON DELETE RESTRICT;
```

This script is intentionally one-time. Do not add `IF NOT EXISTS` column syntax because MySQL 8 does not support it consistently.

- [ ] **Step 4: Document migration and configuration**

In `README.md`, add the migration sequence after the database initialization section:

```bash
mysqldump -uroot -proot123456 -h127.0.0.1 it_workbench > /tmp/it-workbench-before-auth.sql
mysql -uroot -proot123456 -h127.0.0.1 it_workbench < backend/sql/migrate-v2-login-rbac.sql
./scripts/verify-auth-migration.sh
```

Document `JWT_SECRET`, `JWT_EXPIRE_HOURS`, and `CORS_ALLOWED_ORIGINS` in the configuration table. Document the bootstrap account names and initial passwords, with a warning to change them outside local development.

- [ ] **Step 5: Verify SQL syntax and migration result**

Run with the local MySQL instance:

```bash
mysql -uroot -proot123456 -h127.0.0.1 it_workbench < backend/sql/migrate-v2-login-rbac.sql
./scripts/verify-auth-migration.sh
```

Expected: `auth migration ok: users=2 roles=2 permissions=6 tasks=<existing count>`.

Also run:

```bash
mysql -uroot -proot123456 -h127.0.0.1 it_workbench -e \
  "SELECT COUNT(*) AS null_owners FROM task WHERE owner_id IS NULL;"
```

Expected: `0`.

- [ ] **Step 6: Verify password hashes**

Run:

```bash
htpasswd -bnBC 10 '' 'Admin@123456' >/dev/null
mysql -uroot -proot123456 -h127.0.0.1 it_workbench -N -B -e \
  "SELECT username,password_hash FROM sys_user ORDER BY id;"
```

Expected: two BCrypt hashes beginning with `$2y$10$`; no plaintext passwords appear.

- [ ] **Step 7: Commit**

```bash
git add backend/sql/init.sql backend/sql/migrate-v2-login-rbac.sql scripts/verify-auth-migration.sh README.md
git commit -m "feat: add login rbac database migration"
```

---

### Task 2: Security Dependencies and JWT Core

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/qinghuan/workbench/common/BusinessException.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/security/LoginUser.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/security/JwtService.java`
- Create: `backend/src/test/java/com/qinghuan/workbench/security/JwtServiceTest.java`

**Interfaces:**
- Consumes: seeded `sys_user`, `sys_role`, `sys_permission`, and `sys_role_permission` tables.
- Produces: `JwtService.generate(LoginUser): String`, `JwtService.parseUserId(String): Long`, `BusinessException(HttpStatus, String)`.

- [ ] **Step 1: Write the failing JWT test**

Create `backend/src/test/java/com/qinghuan/workbench/security/JwtServiceTest.java`:

```java
package com.qinghuan.workbench.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private final JwtService jwtService = new JwtService(
            "test-secret-that-is-at-least-32-bytes-long", 8);

    @Test
    void generatesAndParsesUserId() {
        LoginUser user = new LoginUser(
                7L, "qinghuan", "$2y$10$hash", "清欢",
                2L, "USER", "普通用户", true, Set.of("task:self:view"));

        String token = jwtService.generate(user);

        assertEquals(7L, jwtService.parseUserId(token));
    }

    @Test
    void rejectsTamperedToken() {
        LoginUser user = new LoginUser(
                7L, "qinghuan", "$2y$10$hash", "清欢",
                2L, "USER", "普通用户", true, Set.of("task:self:view"));
        String token = jwtService.generate(user);

        assertThrows(Exception.class, () -> jwtService.parseUserId(token + "x"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -f backend/pom.xml -Dtest=JwtServiceTest test
```

Expected: FAIL because `JwtService` and `LoginUser` do not exist.

- [ ] **Step 3: Add dependencies and configuration**

Add to `backend/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

Add under `workbench` in `application.yml`:

```yaml
workbench:
  jwt:
    secret: ${JWT_SECRET:it-workbench-development-secret-key-32bytes}
    expire-hours: ${JWT_EXPIRE_HOURS:8}
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080}
```

Keep the existing `workbench.ai` block directly below these keys.

- [ ] **Step 4: Implement the JWT core**

Create `BusinessException.java`:

```java
package com.qinghuan.workbench.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BusinessException extends RuntimeException {
    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
```

Create `LoginUser.java`:

```java
package com.qinghuan.workbench.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Getter
public class LoginUser implements UserDetails {
    private final Long id;
    private final String username;
    private final String password;
    private final String displayName;
    private final Long roleId;
    private final String roleCode;
    private final String roleName;
    private final boolean enabled;
    private final Set<String> permissions;

    public LoginUser(Long id, String username, String password, String displayName,
                     Long roleId, String roleCode, String roleName,
                     boolean enabled, Set<String> permissions) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.roleId = roleId;
        this.roleCode = roleCode;
        this.roleName = roleName;
        this.enabled = enabled;
        this.permissions = Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
```

Create `JwtService.java`:

```java
package com.qinghuan.workbench.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration expireDuration;

    public JwtService(@Value("${workbench.jwt.secret}") String secret,
                      @Value("${workbench.jwt.expire-hours:8}") long expireHours) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireDuration = Duration.ofHours(expireHours);
    }

    public String generate(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireDuration)))
                .signWith(key)
                .compact();
    }

    public Long parseUserId(String token) {
        String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return Long.valueOf(subject);
    }
}
```

- [ ] **Step 5: Run the JWT tests**

Run:

```bash
mvn -f backend/pom.xml -Dtest=JwtServiceTest test
```

Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml \
  backend/src/main/java/com/qinghuan/workbench/common/BusinessException.java \
  backend/src/main/java/com/qinghuan/workbench/security/LoginUser.java \
  backend/src/main/java/com/qinghuan/workbench/security/JwtService.java \
  backend/src/test/java/com/qinghuan/workbench/security/JwtServiceTest.java
git commit -m "feat: add JWT security core"
```

---

### Task 3: Login API, Security Filter, and MVC Error Handling

**Files:**
- Create: `backend/src/main/java/com/qinghuan/workbench/entity/SysUser.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/entity/SysRole.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/entity/SysPermission.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/mapper/SysUserMapper.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/mapper/SysRoleMapper.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/mapper/SysPermissionMapper.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/mapper/SysRolePermissionMapper.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/security/LoginUserService.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/security/JwtAuthenticationFilter.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/security/CurrentUserService.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/security/RestAuthenticationEntryPoint.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/security/RestAccessDeniedHandler.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/config/SecurityConfig.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/config/CorsConfig.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/auth/LoginRequest.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/auth/AuthResponse.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/service/AuthService.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/controller/AuthController.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/common/GlobalExceptionHandler.java`
- Create: `backend/src/test/java/com/qinghuan/workbench/service/AuthServiceTest.java`
- Create: `backend/src/test/java/com/qinghuan/workbench/controller/AuthControllerSecurityTest.java`

**Interfaces:**
- Consumes: `JwtService.generate(LoginUser)`, `JwtService.parseUserId(String)`, `BusinessException`.
- Produces: `LoginUserService.loadById(Long): LoginUser`, `CurrentUserService.require(): LoginUser`, `AuthService.login(LoginRequest): AuthResponse`, public `POST /api/auth/login`, authenticated `GET /api/auth/me`, `POST /api/auth/logout`.

- [ ] **Step 1: Write failing authentication service tests**

Create `AuthServiceTest.java`:

```java
package com.qinghuan.workbench.service;

import com.qinghuan.workbench.common.BusinessException;
import com.qinghuan.workbench.dto.auth.LoginRequest;
import com.qinghuan.workbench.security.JwtService;
import com.qinghuan.workbench.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthService service = new AuthService(manager, jwtService);
    private final LoginUser user = new LoginUser(
            2L, "admin", "hash", "超级管理员",
            1L, "SUPER_ADMIN", "超级管理员", true, Set.of("user:manage"));

    @Test
    void returnsTokenAndPermissionsOnSuccess() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(user);
        when(manager.authenticate(any())).thenReturn(auth);
        when(jwtService.generate(user)).thenReturn("jwt-token");

        var response = service.login(new LoginRequest("admin", "Admin@123456"));

        assertEquals("jwt-token", response.token());
        assertEquals("admin", response.username());
        assertEquals(Set.of("user:manage"), response.permissions());
    }

    @Test
    void invalidCredentialsReturn401() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(new LoginRequest("admin", "wrong")));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    void disabledAccountReturns403() {
        when(manager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(new LoginRequest("admin", "Admin@123456")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("账号已禁用", ex.getMessage());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
mvn -f backend/pom.xml -Dtest=AuthServiceTest test
```

Expected: FAIL because authentication classes do not exist.

- [ ] **Step 3: Implement account entities and mappers**

Create `SysUser.java`:

```java
package com.qinghuan.workbench.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private Long roleId;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

Create `SysRole.java` and `SysPermission.java` with `@TableId(type = IdType.AUTO)`, snake-case fields matching the SQL, `Lombok @Data`, and `@TableName("sys_role")` / `@TableName("sys_permission")`.

Create the three BaseMapper interfaces:

```java
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {}

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {}

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {
    @Select("""
            SELECT p.* FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            WHERE rp.role_id = #{roleId}
            ORDER BY p.id
            """)
    List<SysPermission> selectByRoleId(Long roleId);
}
```

Create `SysRolePermissionMapper.java`:

```java
@Mapper
public interface SysRolePermissionMapper {
    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(Long roleId);

    @Insert("""
            <script>
            INSERT INTO sys_role_permission(role_id, permission_id) VALUES
            <foreach collection="permissionIds" item="permissionId" separator=",">
              (#{roleId}, #{permissionId})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("roleId") Long roleId,
                    @Param("permissionIds") List<Long> permissionIds);

    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId}")
    List<Long> selectPermissionIds(Long roleId);
}
```

Use the imports required by each annotation and collection type.

- [ ] **Step 4: Implement current-user loading**

Create `LoginUserService.java`:

```java
@Service
@RequiredArgsConstructor
public class LoginUserService implements UserDetailsService {
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;

    @Override
    public LoginUser loadUserByUsername(String username) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return toLoginUser(user);
    }

    public LoginUser loadById(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return toLoginUser(user);
    }

    private LoginUser toLoginUser(SysUser user) {
        SysRole role = roleMapper.selectById(user.getRoleId());
        if (role == null) {
            throw new UsernameNotFoundException("角色不存在");
        }
        Set<String> permissions = permissionMapper.selectByRoleId(role.getId())
                .stream().map(SysPermission::getCode).collect(Collectors.toSet());
        return new LoginUser(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getDisplayName(), role.getId(), role.getCode(), role.getName(),
                Boolean.TRUE.equals(user.getEnabled()), permissions);
    }
}
```

Create `CurrentUserService.java`:

```java
@Service
public class CurrentUserService {
    public LoginUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser user)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return user;
    }
}
```

- [ ] **Step 5: Implement JWT filter and security configuration**

Implement `JwtAuthenticationFilter` as a `OncePerRequestFilter`:

```java
if (header == null || !header.startsWith("Bearer ")) {
    chain.doFilter(request, response);
    return;
}
try {
    Long userId = jwtService.parseUserId(header.substring(7));
    LoginUser user = loginUserService.loadById(userId);
    if (!user.isEnabled()) {
        SecurityContextHolder.clearContext();
    } else {
        var auth = new UsernamePasswordAuthenticationToken(
                user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
} catch (RuntimeException ignored) {
    SecurityContextHolder.clearContext();
}
chain.doFilter(request, response);
```

Create `RestAuthenticationEntryPoint` and `RestAccessDeniedHandler` that write:

```java
response.setStatus(status);
response.setContentType(MediaType.APPLICATION_JSON_VALUE);
objectMapper.writeValue(response.getWriter(), Result.error(status, message));
```

For unauthorized use status `401` and message `请先登录`; for forbidden use `403` and message `无权限访问`.

Create `SecurityConfig.java`:

```java
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final LoginUserService loginUserService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint entryPoint;
    private final RestAccessDeniedHandler deniedHandler;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(loginUserService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
```

Modify `CorsConfig` to expose a `CorsConfigurationSource` bean using the comma-separated `workbench.cors.allowed-origins`, methods `GET,POST,PUT,PATCH,DELETE,OPTIONS`, all headers, and credentials disabled.

- [ ] **Step 6: Implement login DTOs, auth service, and controller**

Create `LoginRequest`:

```java
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password) {}
```

Create `AuthResponse`:

```java
public record AuthResponse(
        String token,
        Long id,
        String username,
        String displayName,
        Long roleId,
        String roleCode,
        String roleName,
        Set<String> permissions) {
    public static AuthResponse from(String token, LoginUser user) {
        return new AuthResponse(token, user.getId(), user.getUsername(),
                user.getDisplayName(), user.getRoleId(), user.getRoleCode(),
                user.getRoleName(), user.getPermissions());
    }
}
```

Implement `AuthService`:

```java
@Service
@RequiredArgsConstructor
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthResponse login(LoginRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.username().trim(), request.password()));
            LoginUser user = (LoginUser) auth.getPrincipal();
            return AuthResponse.from(jwtService.generate(user), user);
        } catch (DisabledException e) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "账号已禁用");
        } catch (AuthenticationException e) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
    }
}
```

Create `AuthController` with:

```java
@PostMapping("/login")
public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return Result.ok(authService.login(request));
}

@GetMapping("/me")
public Result<AuthResponse> me() {
    LoginUser user = currentUserService.require();
    return Result.ok(AuthResponse.from(null, user));
}

@PostMapping("/logout")
public Result<Void> logout() {
    return Result.ok();
}
```

- [ ] **Step 7: Update global exception handling**

Modify `GlobalExceptionHandler` so responses use `ResponseEntity<Result<Void>>`:

```java
@ExceptionHandler(BusinessException.class)
public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
    return ResponseEntity.status(e.getStatus())
            .body(Result.error(e.getStatus().value(), e.getMessage()));
}

@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
    FieldError error = e.getBindingResult().getFieldError();
    return ResponseEntity.badRequest()
            .body(Result.error(400, error != null ? error.getDefaultMessage() : "参数校验失败"));
}

@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<Result<Void>> handleDenied(AccessDeniedException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Result.error(403, "无权限访问"));
}
```

Keep the final generic handler at HTTP `500` and preserve server-side logging.

- [ ] **Step 8: Add security route tests**

Create `AuthControllerSecurityTest.java` using:

```java
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class AuthControllerSecurityTest {
    @Autowired MockMvc mockMvc;
    @MockBean AuthService authService;
    @MockBean CurrentUserService currentUserService;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean LoginUserService loginUserService;
}
```

Add these tests:

```java
@Test
void loginIsPublic() throws Exception {
    when(authService.login(any())).thenReturn(new AuthResponse(
            "token", 1L, "admin", "超级管理员", 1L,
            "SUPER_ADMIN", "超级管理员", Set.of("user:manage")));

    mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"username":"admin","password":"Admin@123456"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").value("token"));
}

@Test
void protectedApiWithoutTokenIs401() throws Exception {
    mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
}

@Test
void spaRouteIsNotProtectedByApiRule() throws Exception {
    mockMvc.perform(get("/login"))
            .andExpect(status().isNotFound());
}
```

- [ ] **Step 9: Run authentication tests**

Run:

```bash
mvn -f backend/pom.xml -Dtest=AuthServiceTest,AuthControllerSecurityTest test
```

Expected: PASS; invalid login returns `401`, disabled login returns `403`, protected API without token returns `401`, `/login` is not forced through API authentication.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/qinghuan/workbench \
  backend/src/test/java/com/qinghuan/workbench
git commit -m "feat: add JWT login and security filter chain"
```

---

### Task 4: Account Management API

**Files:**
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/user/UserCreateRequest.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/user/UserUpdateRequest.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/user/UserStatusRequest.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/user/ResetPasswordRequest.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/user/UserPageItem.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/user/UserOption.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/service/UserService.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/controller/UserController.java`
- Create: `backend/src/test/java/com/qinghuan/workbench/service/UserServiceTest.java`

**Interfaces:**
- Consumes: `SysUserMapper`, `SysRoleMapper`, `PasswordEncoder`, `LoginUser`, `BusinessException`.
- Produces: `UserService.page(...)`, `UserService.create(...)`, `UserService.update(...)`, `UserService.changeStatus(...)`, `UserService.resetPassword(...)`, `UserService.options()`, REST endpoints under `/api/users`.

- [ ] **Step 1: Write failing account tests**

Create `UserServiceTest.java` with mocked mappers and encoder. Include these exact tests:

```java
@Test
void rejectsDuplicateUsername() {
    when(userMapper.selectCount(any())).thenReturn(1L);

    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.create(new UserCreateRequest(
                    "admin", "Admin@123456", "新管理员", 1L, true)));

    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    assertEquals("用户名已存在", ex.getMessage());
}

@Test
void cannotDisableCurrentUser() {
    SysUser current = user(2L, "admin", 1L, true);
    when(userMapper.selectById(2L)).thenReturn(current);

    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeStatus(2L, false, 2L));

    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    assertEquals("不能禁用当前登录账号", ex.getMessage());
}

@Test
void cannotDisableLastEnabledSuperAdmin() {
    SysUser target = user(2L, "admin", 1L, true);
    when(userMapper.selectById(2L)).thenReturn(target);
    when(roleMapper.selectById(1L)).thenReturn(superRole());
    when(userMapper.selectCount(any())).thenReturn(1L);

    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeStatus(2L, false, 99L));

    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    assertEquals("至少保留一个可用的超级管理员", ex.getMessage());
}

@Test
void updateCannotDisableCurrentUser() {
    SysUser target = user(2L, "admin", 1L, true);
    when(userMapper.selectById(2L)).thenReturn(target);
    when(roleMapper.selectById(1L)).thenReturn(superRole());

    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.update(2L, new UserUpdateRequest("超级管理员", 1L, false), 2L));

    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    assertEquals("不能禁用当前登录账号", ex.getMessage());
}

@Test
void cannotMoveLastSuperAdminToAnotherRole() {
    SysUser target = user(2L, "admin", 1L, true);
    when(userMapper.selectById(2L)).thenReturn(target);
    when(roleMapper.selectById(1L)).thenReturn(superRole());
    when(roleMapper.selectById(2L)).thenReturn(userRole());
    when(userMapper.selectCount(any())).thenReturn(1L);

    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.update(2L, new UserUpdateRequest("超级管理员", 2L, true), 99L));

    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    assertEquals("至少保留一个可用的超级管理员", ex.getMessage());
}
```

Add helper methods that construct `SysUser` and `SysRole` instances.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn -f backend/pom.xml -Dtest=UserServiceTest test
```

Expected: FAIL because account DTOs and service do not exist.

- [ ] **Step 3: Implement account DTOs and validation**

Use Java records with Jakarta validation:

```java
public record UserCreateRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "密码需为 8-64 位且至少包含字母和数字") String password,
        @NotBlank @Size(max = 50) String displayName,
        @NotNull Long roleId,
        Boolean enabled) {}

public record UserUpdateRequest(
        @NotBlank @Size(max = 50) String displayName,
        @NotNull Long roleId,
        @NotNull Boolean enabled) {}

public record UserStatusRequest(@NotNull Boolean enabled) {}
public record ResetPasswordRequest(
        @NotBlank @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "密码需为 8-64 位且至少包含字母和数字") String password) {}
```

`UserPageItem` contains `id`, `username`, `displayName`, `roleId`, `roleName`, `enabled`, `createdAt`. `UserOption` contains `id`, `displayName`.

- [ ] **Step 4: Implement `UserService`**

Inject `SysUserMapper`, `SysRoleMapper`, `PasswordEncoder`.

Implement `page`:

```java
LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
        .like(StringUtils.hasText(username), SysUser::getUsername, username)
        .like(StringUtils.hasText(displayName), SysUser::getDisplayName, displayName)
        .eq(enabled != null, SysUser::getEnabled, enabled)
        .eq(roleId != null, SysUser::getRoleId, roleId)
        .orderByDesc(SysUser::getId);
IPage<SysUser> result = userMapper.selectPage(new Page<>(page, size), wrapper);
Map<Long, SysRole> roles = roleMapper.selectBatchIds(
        result.getRecords().stream().map(SysUser::getRoleId).collect(Collectors.toSet()))
        .stream().collect(Collectors.toMap(SysRole::getId, Function.identity()));
return result.convert(user -> UserPageItem.from(user, roles.get(user.getRoleId())));
```

Implement `create`:

```java
String username = request.username().trim();
if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
        .eq(SysUser::getUsername, username)) > 0) {
    throw new BusinessException(HttpStatus.CONFLICT, "用户名已存在");
}
validatePasswordNotUsername(request.password(), username);
SysRole role = requireRole(request.roleId());
SysUser user = new SysUser();
user.setUsername(username);
user.setPasswordHash(passwordEncoder.encode(request.password()));
user.setDisplayName(request.displayName().trim());
user.setRoleId(role.getId());
user.setEnabled(request.enabled() == null || request.enabled());
userMapper.insert(user);
return UserPageItem.from(user, role);
```

Implement `update`:

```java
SysUser target = requireUser(id);
SysRole newRole = requireRole(request.roleId());
boolean removingLastSuperAdmin = isSuperAdmin(target) && !"SUPER_ADMIN".equals(newRole.getCode())
        && target.getEnabled() && countEnabledSuperAdmins() <= 1;
if (removingLastSuperAdmin) {
    throw new BusinessException(HttpStatus.CONFLICT, "至少保留一个可用的超级管理员");
}
if (id.equals(operatorId) && !request.enabled()) {
    throw new BusinessException(HttpStatus.CONFLICT, "不能禁用当前登录账号");
}
target.setDisplayName(request.displayName().trim());
target.setRoleId(newRole.getId());
target.setEnabled(request.enabled());
userMapper.updateById(target);
```

Implement `changeStatus` with this order:

1. Load target.
2. Reject `id.equals(operatorId)` when disabling.
3. If disabling an enabled super admin, count enabled super admins and reject when count is at most one.
4. Update status.

Implement `resetPassword`:

```java
SysUser user = requireUser(id);
validatePasswordNotUsername(password, user.getUsername());
user.setPasswordHash(passwordEncoder.encode(password));
userMapper.updateById(user);
```

Implement `options()` returning enabled users ordered by display name.

- [ ] **Step 5: Implement the controller**

Create `UserController` with `@Validated @RestController @RequestMapping("/api/users")` and endpoint-level permissions:

```java
@GetMapping
@PreAuthorize("hasAuthority('user:manage')")
public Result<IPage<UserPageItem>> page(...)

@GetMapping("/options")
@PreAuthorize("hasAuthority('task:view:all')")
public Result<List<UserOption>> options()

@PostMapping
@PreAuthorize("hasAuthority('user:manage')")
public Result<UserPageItem> create(@Valid @RequestBody UserCreateRequest request)

@PutMapping("/{id}")
@PreAuthorize("hasAuthority('user:manage')")
public Result<Void> update(@PathVariable Long id,
                           @Valid @RequestBody UserUpdateRequest request)

@PatchMapping("/{id}/status")
@PreAuthorize("hasAuthority('user:manage')")
public Result<Void> status(@PathVariable Long id,
                           @Valid @RequestBody UserStatusRequest request)

@PostMapping("/{id}/reset-password")
@PreAuthorize("hasAuthority('user:manage')")
public Result<Void> resetPassword(@PathVariable Long id,
                                  @Valid @RequestBody ResetPasswordRequest request)
```

For update and status, pass `currentUserService.require().getId()` as the operator ID.

- [ ] **Step 6: Run account tests**

Run:

```bash
mvn -f backend/pom.xml -Dtest=UserServiceTest test
```

Expected: PASS for duplicate username, self-disable, last-super-admin disable, and last-super-admin role change.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/qinghuan/workbench/dto/user \
  backend/src/main/java/com/qinghuan/workbench/service/UserService.java \
  backend/src/main/java/com/qinghuan/workbench/controller/UserController.java \
  backend/src/test/java/com/qinghuan/workbench/service/UserServiceTest.java
git commit -m "feat: add account management API"
```

---

### Task 5: Role and Permission Management API

**Files:**
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/role/PermissionItem.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/role/RoleOption.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/role/RoleRequest.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/dto/role/RoleResponse.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/service/RoleService.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/controller/RoleController.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/controller/PermissionController.java`
- Create: `backend/src/test/java/com/qinghuan/workbench/service/RoleServiceTest.java`

**Interfaces:**
- Consumes: `SysRoleMapper`, `SysPermissionMapper`, `SysRolePermissionMapper`, `SysUserMapper`.
- Produces: `RoleService.list()`, `RoleService.options()`, `RoleService.permissions()`, `RoleService.create(RoleRequest)`, `RoleService.update(Long, RoleRequest)`, `RoleService.delete(Long)`, REST endpoints under `/api/roles` and `/api/permissions`.

- [ ] **Step 1: Write failing role tests**

Create `RoleServiceTest.java` with these cases:

```java
@Test
void rejectsManagePermissionWithoutViewPermission() {
    when(permissionMapper.selectBatchIds(any())).thenReturn(List.of(
            permission(2L, "task:self:manage")));

    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.create(new RoleRequest(
                    "IT_ADMIN", "IT 管理员", "测试", List.of(2L))));

    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    assertEquals("task:self:manage 需要同时选择 task:self:view", ex.getMessage());
}

@Test
void superAdminCannotBeDeleted() {
    when(roleMapper.selectById(1L)).thenReturn(superRole());

    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.delete(1L));

    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    assertEquals("内置超级管理员角色不能删除", ex.getMessage());
}

@Test
void roleInUseCannotBeDeleted() {
    SysRole custom = role(3L, "IT_ADMIN", false);
    when(roleMapper.selectById(3L)).thenReturn(custom);
    when(userMapper.selectCount(any())).thenReturn(1L);

    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.delete(3L));

    assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    assertEquals("角色仍被账号使用，不能删除", ex.getMessage());
}

@Test
void superAdminAlwaysSavesAllPermissions() {
    when(roleMapper.selectById(1L)).thenReturn(superRole());
    when(permissionMapper.selectList(any())).thenReturn(List.of(
            permission(1L, "task:self:view"), permission(6L, "role:manage")));

    service.update(1L, new RoleRequest(
            "SUPER_ADMIN", "超级管理员", "更新说明", List.of(1L)));

    verify(rolePermissionMapper).deleteByRoleId(1L);
    verify(rolePermissionMapper).insertBatch(1L, List.of(1L, 6L));
}
```

Add helpers for `SysRole` and `SysPermission`.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn -f backend/pom.xml -Dtest=RoleServiceTest test
```

Expected: FAIL because role service and DTOs do not exist.

- [ ] **Step 3: Implement role DTOs**

```java
public record PermissionItem(Long id, String code, String name, String description) {}

public record RoleOption(Long id, String name) {}

public record RoleRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$",
                message = "角色编码需以大写字母开头，仅包含大写字母、数字和下划线")
        String code,
        @NotBlank @Size(max = 50) String name,
        @Size(max = 255) String description,
        @NotNull List<@NotNull Long> permissionIds) {}

public record RoleResponse(
        Long id, String code, String name, String description,
        boolean builtIn, long userCount,
        List<Long> permissionIds, List<String> permissionCodes) {}
```

- [ ] **Step 4: Implement `RoleService`**

Inject the three mappers plus `SysUserMapper`.

Implement validation:

```java
private static final Map<String, String> REQUIRES = Map.of(
        "task:self:manage", "task:self:view",
        "task:manage:all", "task:view:all");
```

Implement `validatePermissionIds(List<Long> ids)`:

1. Remove duplicates.
2. Load permissions with `selectBatchIds`.
3. Reject when the loaded count differs from the requested count.
4. Build the code set.
5. For each dependency, reject if the managed code exists without the required code.

Implement `create`:

```java
String code = request.code().trim();
if (roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
        .eq(SysRole::getCode, code)) > 0) {
    throw new BusinessException(HttpStatus.CONFLICT, "角色编码已存在");
}
List<Long> permissionIds = validatePermissionIds(request.permissionIds());
SysRole role = new SysRole();
role.setCode(code);
role.setName(request.name().trim());
role.setDescription(request.description());
role.setBuiltIn(false);
roleMapper.insert(role);
if (!permissionIds.isEmpty()) {
    rolePermissionMapper.insertBatch(role.getId(), permissionIds);
}
return toResponse(role, permissionIds);
```

Implement `update`:

```java
SysRole role = requireRole(id);
if (role.getBuiltIn() && !role.getCode().equals(request.code().trim())) {
    throw new BusinessException(HttpStatus.CONFLICT, "内置角色编码不能修改");
}
List<Long> permissionIds = "SUPER_ADMIN".equals(role.getCode())
        ? permissionMapper.selectList(null).stream().map(SysPermission::getId).toList()
        : validatePermissionIds(request.permissionIds());
role.setName(request.name().trim());
role.setDescription(request.description());
roleMapper.updateById(role);
rolePermissionMapper.deleteByRoleId(id);
if (!permissionIds.isEmpty()) {
    rolePermissionMapper.insertBatch(id, permissionIds);
}
```

Implement `delete` with built-in and in-use checks. Implement `list`, `options`, and `permissions` using mapper queries and `RoleResponse`.

- [ ] **Step 5: Implement role controllers**

`RoleController` methods:

```java
@GetMapping
@PreAuthorize("hasAuthority('role:manage')")
Result<List<RoleResponse>> list()

@GetMapping("/options")
@PreAuthorize("hasAnyAuthority('user:manage','role:manage')")
Result<List<RoleOption>> options()

@GetMapping("/{id}")
@PreAuthorize("hasAuthority('role:manage')")
Result<RoleResponse> get(@PathVariable Long id)

@PostMapping
@PreAuthorize("hasAuthority('role:manage')")
Result<RoleResponse> create(@Valid @RequestBody RoleRequest request)

@PutMapping("/{id}")
@PreAuthorize("hasAuthority('role:manage')")
Result<RoleResponse> update(@PathVariable Long id,
                            @Valid @RequestBody RoleRequest request)

@DeleteMapping("/{id}")
@PreAuthorize("hasAuthority('role:manage')")
Result<Void> delete(@PathVariable Long id)
```

`PermissionController` exposes `GET /api/permissions` with `role:manage`.

- [ ] **Step 6: Run role tests**

Run:

```bash
mvn -f backend/pom.xml -Dtest=RoleServiceTest test
```

Expected: PASS for dependency validation, built-in protection, role-in-use protection, and super-admin permission preservation.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/qinghuan/workbench/dto/role \
  backend/src/main/java/com/qinghuan/workbench/service/RoleService.java \
  backend/src/main/java/com/qinghuan/workbench/controller/RoleController.java \
  backend/src/main/java/com/qinghuan/workbench/controller/PermissionController.java \
  backend/src/test/java/com/qinghuan/workbench/service/RoleServiceTest.java
git commit -m "feat: add role and permission management API"
```

---

### Task 6: Task Ownership and Service-Layer Data Scope

**Files:**
- Modify: `backend/src/main/java/com/qinghuan/workbench/entity/Task.java`
- Create: `backend/src/main/java/com/qinghuan/workbench/service/TaskScopeService.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/service/TaskService.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/controller/TaskController.java`
- Create: `backend/src/test/java/com/qinghuan/workbench/service/TaskScopeServiceTest.java`
- Create: `backend/src/test/java/com/qinghuan/workbench/service/TaskServiceTest.java`

**Interfaces:**
- Consumes: `LoginUser`, `TaskMapper`, `SysUserMapper`.
- Produces: `Task.getOwnerId()`, `Task.getOwnerName()`, `TaskScopeService.applyViewScope(...)`, `TaskScopeService.requireViewable(...)`, `TaskScopeService.requireManageable(...)`, task service methods that accept `LoginUser`.

- [ ] **Step 1: Write failing scope tests**

Create `TaskScopeServiceTest.java`:

```java
@Test
void normalUserScopeForcesCurrentOwnerEvenWhenOwnerIdIsSpoofed() {
    LoginUser user = loginUser(7L, "task:self:view");
    LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

    scopeService.applyViewScope(wrapper, user, 99L);

    assertTrue(wrapper.getSqlSegment().contains("owner_id"));
    assertTrue(wrapper.getParamNameValuePairs().containsValue(7L));
    assertFalse(wrapper.getParamNameValuePairs().containsValue(99L));
}

@Test
void allTaskViewerUsesRequestedOwnerFilter() {
    LoginUser user = loginUser(7L, "task:view:all");
    LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

    scopeService.applyViewScope(wrapper, user, 99L);

    assertTrue(wrapper.getParamNameValuePairs().containsValue(99L));
    assertFalse(wrapper.getParamNameValuePairs().containsValue(7L));
}

@Test
void normalUserCannotLoadAnotherUsersTask() {
    Task otherTask = task(3L, 99L);
    when(taskMapper.selectById(3L)).thenReturn(otherTask);

    BusinessException ex = assertThrows(BusinessException.class,
            () -> scopeService.requireViewable(3L, loginUser(7L, "task:self:view")));

    assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
}
```

Create helpers `loginUser(Long, String...)`, `task(Long, Long)`, and instantiate `TaskScopeService` with a mocked `TaskMapper`.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn -f backend/pom.xml -Dtest=TaskScopeServiceTest test
```

Expected: FAIL because `TaskScopeService` and task ownership fields do not exist.

- [ ] **Step 3: Add ownership fields to `Task`**

Add:

```java
private Long ownerId;

@TableField(exist = false)
private String ownerName;
```

`ownerId` must be included in inserts and updates. `ownerName` is response-only.

- [ ] **Step 4: Implement `TaskScopeService`**

Implement:

```java
@Service
@RequiredArgsConstructor
public class TaskScopeService {
    private final TaskMapper taskMapper;

    public boolean canViewAll(LoginUser user) {
        return user.hasPermission("task:view:all");
    }

    public boolean canManageAll(LoginUser user) {
        return user.hasPermission("task:manage:all");
    }

    public void applyViewScope(LambdaQueryWrapper<Task> wrapper,
                               LoginUser user,
                               Long requestedOwnerId) {
        if (canViewAll(user)) {
            wrapper.eq(requestedOwnerId != null, Task::getOwnerId, requestedOwnerId);
            return;
        }
        if (!user.hasPermission("task:self:view")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权限访问");
        }
        wrapper.eq(Task::getOwnerId, user.getId());
    }

    public Task requireViewable(Long id, LoginUser user) {
        Task task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "任务不存在");
        }
        if (!canViewAll(user) && !user.getId().equals(task.getOwnerId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权限访问");
        }
        return task;
    }

    public Task requireManageable(Long id, LoginUser user) {
        Task task = requireViewable(id, user);
        if (!canManageAll(user) && !user.hasPermission("task:self:manage")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "无权限访问");
        }
        return task;
    }
}
```

- [ ] **Step 5: Write failing task service tests**

Create `TaskServiceTest.java` with:

```java
@Test
void normalUserCreateForcesOwnershipToCurrentUser() {
    LoginUser user = loginUser(7L, "task:self:view", "task:self:manage");
    Task request = task(null, 99L);

    service.create(request, user);

    assertEquals(7L, request.getOwnerId());
    verify(ownerMapper).selectById(7L);
}

@Test
void allTaskManagerMayAssignOwner() {
    LoginUser user = loginUser(2L, "task:view:all", "task:manage:all");
    Task request = task(null, 7L);
    when(ownerMapper.selectById(7L)).thenReturn(enabledUser(7L));

    service.create(request, user);

    assertEquals(7L, request.getOwnerId());
}

@Test
void normalUserCannotUpdateAnotherUsersTask() {
    LoginUser user = loginUser(7L, "task:self:view", "task:self:manage");
    when(taskMapper.selectById(3L)).thenReturn(task(3L, 99L));

    BusinessException ex = assertThrows(BusinessException.class,
            () -> service.update(task(3L, 99L), user));

    assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
}
```

- [ ] **Step 6: Rewrite `TaskService` around `LoginUser`**

Inject `TaskScopeService` and `SysUserMapper`.

Implement page:

```java
public IPage<Task> page(long page, long size, LocalDate taskDate,
                        String taskType, String status, Long ownerId,
                        LoginUser user) {
    LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
            .eq(taskDate != null, Task::getTaskDate, taskDate)
            .eq(StringUtils.hasText(taskType), Task::getTaskType, taskType)
            .eq(StringUtils.hasText(status), Task::getStatus, status)
            .orderByDesc(Task::getTaskDate)
            .orderByDesc(Task::getId);
    taskScopeService.applyViewScope(wrapper, user, ownerId);
    IPage<Task> result = taskMapper.selectPage(new Page<>(page, size), wrapper);
    enrichOwnerNames(result.getRecords());
    return result;
}
```

Implement `create`:

```java
Long ownerId = canManageAll(user)
        ? (task.getOwnerId() == null ? user.getId() : task.getOwnerId())
        : user.getId();
SysUser owner = requireEnabledOwner(ownerId);
task.setId(null);
task.setOwnerId(ownerId);
task.setOwnerName(owner.getDisplayName());
taskMapper.insert(task);
return task;
```

Implement `update`:

```java
Task current = taskScopeService.requireManageable(task.getId(), user);
if (!taskScopeService.canManageAll(user)) {
    task.setOwnerId(user.getId());
} else if (task.getOwnerId() == null) {
    task.setOwnerId(current.getOwnerId());
}
SysUser owner;
if (task.getOwnerId().equals(current.getOwnerId())) {
    owner = requireUser(task.getOwnerId());
} else {
    owner = requireEnabledOwner(task.getOwnerId());
}
task.setOwnerName(owner.getDisplayName());
taskMapper.updateById(task);
return true;
```

Implement delete as:

```java
taskScopeService.requireManageable(id, user);
return taskMapper.deleteById(id) > 0;
```

Implement detail lookup:

```java
public Task getById(Long id, LoginUser user) {
    Task task = taskScopeService.requireViewable(id, user);
    enrichOwnerNames(List.of(task));
    return task;
}
```

Implement scoped lists:

```java
public List<Task> listForStats(LocalDate date, LoginUser user) {
    LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
            .eq(Task::getTaskDate, date)
            .orderByAsc(Task::getId);
    taskScopeService.applyViewScope(wrapper, user, null);
    List<Task> tasks = taskMapper.selectList(wrapper);
    enrichOwnerNames(tasks);
    return tasks;
}

public List<Task> listByDate(LocalDate date, Long ownerId) {
    List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
            .eq(Task::getTaskDate, date)
            .eq(Task::getOwnerId, ownerId)
            .orderByAsc(Task::getId));
    enrichOwnerNames(tasks);
    return tasks;
}
```

Implement owner helpers:

```java
private SysUser requireUser(Long id) {
    SysUser user = ownerMapper.selectById(id);
    if (user == null) {
        throw new BusinessException(HttpStatus.NOT_FOUND, "归属账号不存在");
    }
    return user;
}

private SysUser requireEnabledOwner(Long id) {
    SysUser owner = requireUser(id);
    if (!Boolean.TRUE.equals(owner.getEnabled())) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "归属账号已禁用");
    }
    return owner;
}

private void enrichOwnerNames(List<Task> tasks) {
    if (tasks.isEmpty()) {
        return;
    }
    Set<Long> ownerIds = tasks.stream().map(Task::getOwnerId).collect(Collectors.toSet());
    Map<Long, String> names = ownerMapper.selectBatchIds(ownerIds).stream()
            .collect(Collectors.toMap(SysUser::getId, SysUser::getDisplayName));
    tasks.forEach(task -> task.setOwnerName(names.get(task.getOwnerId())));
}
```

- [ ] **Step 7: Update `TaskController`**

Change page signature to include `Long ownerId` and call `currentUserService.require()`.

Use method permissions:

```java
@PreAuthorize("hasAnyAuthority('task:self:view','task:view:all')")
```

on page and detail,

```java
@PreAuthorize("hasAnyAuthority('task:self:manage','task:manage:all')")
```

on create/update/delete. Pass the current user into every task service method.

- [ ] **Step 8: Run task service tests**

Run:

```bash
mvn -f backend/pom.xml -Dtest=TaskScopeServiceTest,TaskServiceTest test
```

Expected: PASS. Verify normal-user owner spoofing is ignored and cross-user update is rejected.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/qinghuan/workbench/entity/Task.java \
  backend/src/main/java/com/qinghuan/workbench/service/TaskScopeService.java \
  backend/src/main/java/com/qinghuan/workbench/service/TaskService.java \
  backend/src/main/java/com/qinghuan/workbench/controller/TaskController.java \
  backend/src/test/java/com/qinghuan/workbench/service/TaskScopeServiceTest.java \
  backend/src/test/java/com/qinghuan/workbench/service/TaskServiceTest.java
git commit -m "feat: enforce task ownership and data scope"
```

---

### Task 7: Per-User Statistics and Daily Summary

**Files:**
- Modify: `backend/src/main/java/com/qinghuan/workbench/service/StatsService.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/controller/StatsController.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/service/DailySummaryService.java`
- Modify: `backend/src/main/java/com/qinghuan/workbench/controller/DailySummaryController.java`
- Create: `backend/src/test/java/com/qinghuan/workbench/service/StatsServiceTest.java`
- Modify: `backend/src/test/java/com/qinghuan/workbench/service/DailySummaryServiceTest.java`

**Interfaces:**
- Consumes: `TaskService.listForStats(LocalDate, LoginUser)`, `TaskService.listByDate(LocalDate, Long)`.
- Produces: `StatsService.todayStats(LoginUser)`, `DailySummaryService.summarize(LocalDate, Long)`.

- [ ] **Step 1: Write failing scope tests**

Create `StatsServiceTest.java`:

```java
@Test
void statsUseTasksReturnedForCurrentUserScope() {
    LoginUser user = loginUser(7L, "task:self:view");
    when(taskService.listForStats(any(), eq(user))).thenReturn(List.of(
            task(1L, 7L, "完成"), task(2L, 7L, "未完成"), task(3L, 7L, "延期")));

    Map<String, Object> result = service.todayStats(user);

    assertEquals(3, result.get("total"));
    assertEquals(1, result.get("completedCount"));
    assertEquals(2, result.get("uncompletedCount"));
    assertEquals(33.3, result.get("completionRate"));
}
```

Modify `DailySummaryServiceTest` helper so `taskService.listByDate(date, 7L)` returns the sample tasks and all calls use:

```java
svc.summarize(date, 7L)
```

Add:

```java
@Test
void dailySummaryAlwaysUsesRequestedOwner() {
    TaskService taskService = mock(TaskService.class);
    when(taskService.listByDate(date, 7L)).thenReturn(List.of());

    DailySummaryService svc = new DailySummaryService(
            taskService, "", "", "m", 5000);
    svc.summarize(date, 7L);

    verify(taskService).listByDate(date, 7L);
    verify(taskService, never()).listByDate(any());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn -f backend/pom.xml -Dtest=StatsServiceTest,DailySummaryServiceTest test
```

Expected: FAIL because signatures still use global data.

- [ ] **Step 3: Change `StatsService` to accept the current user**

Change the method to:

```java
public Map<String, Object> todayStats(LoginUser user) {
    List<Task> tasks = taskService.listForStats(LocalDate.now(), user);
    // Keep the existing completed, uncompleted, rate, and map construction.
}
```

Add `"scope"` to the response map:

```java
"scope", user.hasPermission("task:view:all") ? "all" : "self"
```

- [ ] **Step 4: Change daily summary to accept a user ID**

Change the public method to:

```java
public Map<String, Object> summarize(LocalDate date, Long ownerId) {
    List<Task> tasks = taskService.listByDate(date, ownerId);
    // Keep the existing AI/template branches and result map.
}
```

Remove the obsolete debug lines:

```java
// thsi is a test
String str = "test";
```

Add `"ownerId"` to the response map.

- [ ] **Step 5: Update controllers**

`StatsController.today()`:

```java
LoginUser user = currentUserService.require();
return Result.ok(statsService.todayStats(user));
```

Add `@PreAuthorize("hasAnyAuthority('task:self:view','task:view:all')")`.

`DailySummaryController.summary(...)`:

```java
LoginUser user = currentUserService.require();
return Result.ok(dailySummaryService.summarize(
        date != null ? date : LocalDate.now(), user.getId()));
```

Add `@PreAuthorize("hasAuthority('task:self:view')")`.

- [ ] **Step 6: Run scoped tests**

Run:

```bash
mvn -f backend/pom.xml -Dtest=StatsServiceTest,DailySummaryServiceTest test
```

Expected: PASS. Stats use the scoped task list; daily summary always queries the authenticated user's ID.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/qinghuan/workbench/service/StatsService.java \
  backend/src/main/java/com/qinghuan/workbench/controller/StatsController.java \
  backend/src/main/java/com/qinghuan/workbench/service/DailySummaryService.java \
  backend/src/main/java/com/qinghuan/workbench/controller/DailySummaryController.java \
  backend/src/test/java/com/qinghuan/workbench/service/StatsServiceTest.java \
  backend/src/test/java/com/qinghuan/workbench/service/DailySummaryServiceTest.java
git commit -m "feat: scope statistics and daily summaries by user"
```

---

### Task 8: Frontend Authentication Foundation and Login Page

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/src/auth/session.js`
- Create: `frontend/src/stores/auth.js`
- Create: `frontend/src/stores/auth.test.js`
- Modify: `frontend/src/api/index.js`
- Modify: `frontend/src/router/index.js`
- Modify: `frontend/src/App.vue`
- Create: `frontend/src/views/LoginView.vue`

**Interfaces:**
- Consumes: `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/logout`.
- Produces: `auth.isLoggedIn`, `auth.user`, `auth.hasPermission(code)`, `auth.login(username, password)`, `auth.logout()`, login route `/login`.

- [ ] **Step 1: Add Vitest and write a failing auth-store test**

Add to `frontend/package.json` scripts:

```json
"test": "vitest run"
```

Add dev dependency:

```json
"vitest": "^2.1.9"
```

Create `frontend/src/stores/auth.test.js`:

```js
import { beforeEach, describe, expect, it, vi } from 'vitest'

class MemoryStorage {
  constructor() { this.map = new Map() }
  getItem(key) { return this.map.has(key) ? this.map.get(key) : null }
  setItem(key, value) { this.map.set(key, String(value)) }
  removeItem(key) { this.map.delete(key) }
  clear() { this.map.clear() }
}

describe('auth store', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', new MemoryStorage())
    vi.resetModules()
  })

  it('persists login session and checks permissions', async () => {
    const { auth } = await import('./auth')

    auth.setSession({
      token: 'jwt-token',
      id: 7,
      username: 'qinghuan',
      displayName: '清欢',
      roleId: 2,
      roleCode: 'USER',
      roleName: '普通用户',
      permissions: ['task:self:view']
    })

    expect(auth.isLoggedIn.value).toBe(true)
    expect(auth.user.value.displayName).toBe('清欢')
    expect(auth.hasPermission('task:self:view')).toBe(true)
    expect(auth.hasPermission('user:manage')).toBe(false)
  })

  it('clears persisted session', async () => {
    const { auth } = await import('./auth')
    auth.setSession({ token: 'token', permissions: [] })

    auth.clear()

    expect(auth.isLoggedIn.value).toBe(false)
    expect(localStorage.getItem('it-workbench-session')).toBeNull()
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd frontend
npm install
npm test -- --run
```

Expected: FAIL because `src/stores/auth.js` does not exist.

- [ ] **Step 3: Implement session storage and auth store**

Create `frontend/src/auth/session.js`:

```js
const STORAGE_KEY = 'it-workbench-session'

export function getSession() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null')
  } catch {
    localStorage.removeItem(STORAGE_KEY)
    return null
  }
}

export function saveSession(session) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session))
}

export function clearSession() {
  localStorage.removeItem(STORAGE_KEY)
}

export function getToken() {
  return getSession()?.token || ''
}
```

Create `frontend/src/stores/auth.js`:

```js
import { computed, reactive } from 'vue'
import { authApi } from '../api'
import { clearSession, getSession, saveSession } from '../auth/session'

const initial = getSession()

const state = reactive({
  token: initial?.token || '',
  user: initial ? {
    id: initial.id,
    username: initial.username,
    displayName: initial.displayName,
    roleId: initial.roleId,
    roleCode: initial.roleCode,
    roleName: initial.roleName,
    permissions: initial.permissions || []
  } : null
})

function persist() {
  saveSession({
    token: state.token,
    ...(state.user || {})
  })
}

export const auth = {
  user: computed(() => state.user),
  isLoggedIn: computed(() => Boolean(state.token && state.user)),
  hasPermission(code) {
    return Boolean(state.user?.permissions?.includes(code))
  },
  setSession(response) {
    state.token = response.token || ''
    state.user = {
      id: response.id,
      username: response.username,
      displayName: response.displayName,
      roleId: response.roleId,
      roleCode: response.roleCode,
      roleName: response.roleName,
      permissions: response.permissions || []
    }
    persist()
  },
  async login(username, password) {
    const response = await authApi.login({ username, password })
    this.setSession(response)
    return response
  },
  async refresh() {
    const response = await authApi.me()
    this.setSession({ ...response, token: state.token })
    return response
  },
  logout() {
    this.clear()
    return authApi.logout().catch(() => null)
  },
  clear() {
    state.token = ''
    state.user = null
    clearSession()
  }
}
```

- [ ] **Step 4: Add Axios token and 401 handling**

Modify `frontend/src/api/index.js`:

```js
import { clearSession, getToken } from '../auth/session'

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && body.code !== 0) return Promise.reject(new Error(body.msg || '请求失败'))
    return body ? body.data : null
  },
  (err) => {
    const status = err.response?.status
    const msg = err.response?.data?.msg || err.message || '网络异常'
    if (status === 401) {
      clearSession()
      if (window.location.pathname !== '/login') window.location.assign('/login')
    }
    return Promise.reject(new Error(msg))
  }
)
```

Add APIs:

```js
export const authApi = {
  login(data) { return http.post('/auth/login', data) },
  me() { return http.get('/auth/me') },
  logout() { return http.post('/auth/logout') }
}

export const userApi = {
  page(params) { return http.get('/users', { params }) },
  options() { return http.get('/users/options') },
  create(data) { return http.post('/users', data) },
  update(id, data) { return http.put(`/users/${id}`, data) },
  status(id, enabled) { return http.patch(`/users/${id}/status`, { enabled }) },
  resetPassword(id, password) { return http.post(`/users/${id}/reset-password`, { password }) }
}

export const roleApi = {
  list() { return http.get('/roles') },
  options() { return http.get('/roles/options') },
  permissions() { return http.get('/permissions') },
  create(data) { return http.post('/roles', data) },
  update(id, data) { return http.put(`/roles/${id}`, data) },
  remove(id) { return http.delete(`/roles/${id}`) }
}
```

- [ ] **Step 5: Add routes and navigation guards**

Replace `frontend/src/router/index.js` with lazy routes:

```js
import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/tasks', name: 'tasks', component: () => import('../views/TasksView.vue') }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public) {
    return auth.isLoggedIn.value && to.name === 'login'
      ? { name: 'home' }
      : true
  }
  if (!auth.isLoggedIn.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.permission && !auth.hasPermission(to.meta.permission)) {
    return { name: 'home' }
  }
  return true
})

export default router
```

- [ ] **Step 6: Update `App.vue` shell and navigation**

Use `const authUser = auth.user` and `const loggedIn = auth.isLoggedIn`.

Template structure:

```vue
<router-view v-if="route.meta.public" />
<div v-else class="app-shell">
  <header class="top-nav">
    <div class="nav-inner">
      <router-link to="/" class="brand">...</router-link>
      <nav class="nav-links">
        <router-link to="/" class="nav-link">首页</router-link>
        <router-link to="/tasks" class="nav-link">任务列表</router-link>
        <!-- Task 10 and Task 11 add the permission-gated account and role links. -->
      </nav>
      <div class="account-box">
        <span>{{ authUser?.displayName }}</span>
        <span class="role-chip">{{ authUser?.roleName }}</span>
        <button class="btn btn-secondary btn-sm" @click="logout">退出</button>
      </div>
    </div>
  </header>
  <main class="main-container"><router-view /></main>
  <footer class="app-footer">...</footer>
</div>
```

Add:

```js
async function logout() {
  await auth.logout()
  router.push('/login')
}
```

Keep the existing active-link calculations.

- [ ] **Step 7: Create the login page**

Create `LoginView.vue` with reactive fields `username`, `password`, `error`, `loading`.

Submit handler:

```js
async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    router.push(route.query.redirect || '/')
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}
```

Use the existing `.card`, `.input`, `.btn-primary`, `.caption` styles. Provide labels, password input, disabled submit state, and an error paragraph. Default values must be empty; do not prefill bootstrap passwords.

- [ ] **Step 8: Run frontend tests and build**

Run:

```bash
cd frontend
npm test
npm run build
```

Expected: 2 auth-store tests pass and Vite production build exits `0`.

- [ ] **Step 9: Commit**

```bash
git add frontend/package.json frontend/package-lock.json \
  frontend/src/auth frontend/src/stores frontend/src/api/index.js \
  frontend/src/router/index.js frontend/src/App.vue frontend/src/views/LoginView.vue
git commit -m "feat: add frontend login and auth guard"
```

---

### Task 9: Task Owner Column, Filter, Editor, and Stats Scope Label

**Files:**
- Modify: `frontend/src/views/TasksView.vue`
- Modify: `frontend/src/views/HomeView.vue`
- Modify: `frontend/src/api/index.js`
- Test: `frontend/src/views/TasksView.vue` by production build and browser verification

**Interfaces:**
- Consumes: `auth.hasPermission(code)`, `userApi.options()`, `GET /api/tasks?ownerId=`, `GET /api/stats/today`.
- Produces: owner column, owner filter, owner selector in create/edit, and visible self/all statistics scope.

- [ ] **Step 1: Add owner data to `TasksView`**

Import:

```js
import { auth } from '../stores/auth'
import { taskApi, dailySummaryApi, userApi } from '../api'

const canViewAll = computed(() => auth.hasPermission('task:view:all'))
const canManageAll = computed(() => auth.hasPermission('task:manage:all'))
const owners = ref([])
```

Extend filters:

```js
const filters = reactive({
  taskDate: '',
  taskType: '',
  status: '',
  ownerId: ''
})
```

Add request parameter:

```js
ownerId: canViewAll.value && filters.ownerId ? Number(filters.ownerId) : undefined
```

Add loader:

```js
async function loadOwners() {
  if (!canViewAll.value) return
  owners.value = await userApi.options()
}
```

Call `loadOwners()` from `onMounted` before or alongside `load()`. Reset must set `filters.ownerId = ''`.

Extend form:

```js
const form = reactive({
  taskDate: new Date().toISOString().slice(0, 10),
  taskType: '',
  description: '',
  status: '未完成',
  summary: '',
  ownerId: ''
})
```

When `canManageAll` and opening create, default `ownerId` to the current user ID. When opening edit, set it from `row.ownerId`.

Before saving, remove the owner field for users without `task:manage:all`:

```js
const payload = { ...form }
if (!canManageAll.value) delete payload.ownerId
```

Add a filter select:

```vue
<select v-if="canViewAll" v-model="filters.ownerId" class="select">
  <option value="">全部归属人</option>
  <option v-for="owner in owners" :key="owner.id" :value="owner.id">
    {{ owner.displayName }}
  </option>
</select>
```

Add a form select with the same options and `v-if="canManageAll"`.

Add the table column and cell:

```vue
<th class="col-owner">归属人</th>
...
<td class="col-owner body-sm">{{ row.ownerName || '—' }}</td>
```

Update `colspan="6"` empty rows to `colspan="7"`.

- [ ] **Step 2: Add statistics scope text to `HomeView`**

Import `auth` and add:

```js
const scopeText = computed(() => {
  if (!stats.value) return ''
  return stats.value.scope === 'all' ? '全部人员' : '本人'
})
```

Below the page heading add:

```vue
<p v-if="scopeText" class="caption">当前统计范围：{{ scopeText }}</p>
```

In the daily summary modal, change the loading and metadata copy to explicitly say“当前登录人今日任务”, for example:

```vue
<span class="caption">基于当前登录人 {{ dailyData.taskCount }} 项任务 · {{ dailyData.date }}</span>
```

- [ ] **Step 3: Build and manually verify**

Run:

```bash
cd frontend
npm run build
```

Expected: build exits `0`.

Then open `/tasks` and verify:

- Normal user sees no owner filter and no owner selector.
- Admin sees owner filter and owner selector.
- Owner column shows“清欢”for migrated tasks.
- Filtering by owner changes the table without exposing an unfiltered list first.
- Home page displays“当前统计范围：本人”or“全部人员”.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/TasksView.vue frontend/src/views/HomeView.vue frontend/src/api/index.js
git commit -m "feat: add task owner filtering to frontend"
```

---

### Task 10: Frontend Account Management Page

**Files:**
- Create: `frontend/src/views/UsersView.vue`
- Modify: `frontend/src/router/index.js`
- Modify: `frontend/src/App.vue`
- Test: production build and browser verification

**Interfaces:**
- Consumes: `userApi.page`, `userApi.create`, `userApi.update`, `userApi.status`, `userApi.resetPassword`, `roleApi.options`, `auth.hasPermission('user:manage')`.
- Produces: route `/users` and account-management UI.

- [ ] **Step 1: Create the account management view**

Create `UsersView.vue` with this script logic:

```js
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { roleApi, userApi } from '../api'
import AppModal from '../components/AppModal.vue'

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const roles = ref([])
const filters = reactive({ username: '', displayName: '', enabled: '', roleId: '' })

const editorOpen = ref(false)
const editingId = ref(null)
const saving = ref(false)
const formError = ref('')
const form = reactive({ username: '', password: '', displayName: '', roleId: '', enabled: true })

const resetOpen = ref(false)
const resetTarget = ref(null)
const resetPassword = ref('')
const resetError = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

async function load() {
  loading.value = true
  try {
    const data = await userApi.page({
      page: page.value,
      size: size.value,
      username: filters.username || undefined,
      displayName: filters.displayName || undefined,
      enabled: filters.enabled === '' ? undefined : filters.enabled === 'true',
      roleId: filters.roleId || undefined
    })
    list.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    alert(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  roles.value = await roleApi.options()
}

function search() {
  page.value = 1
  load()
}

function resetFilters() {
  filters.username = ''
  filters.displayName = ''
  filters.enabled = ''
  filters.roleId = ''
  search()
}

function openCreate() {
  editingId.value = null
  form.username = ''
  form.password = ''
  form.displayName = ''
  form.roleId = roles.value[0]?.id || ''
  form.enabled = true
  formError.value = ''
  editorOpen.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.username = row.username
  form.password = ''
  form.displayName = row.displayName
  form.roleId = row.roleId
  form.enabled = row.enabled
  formError.value = ''
  editorOpen.value = true
}

async function save() {
  if (!form.displayName || !form.roleId) return (formError.value = '请填写姓名并选择角色')
  if (!editingId.value && !form.password) return (formError.value = '请填写初始密码')
  saving.value = true
  formError.value = ''
  try {
    if (editingId.value) {
      await userApi.update(editingId.value, {
        displayName: form.displayName,
        roleId: Number(form.roleId),
        enabled: form.enabled
      })
    } else {
      await userApi.create({
        username: form.username,
        password: form.password,
        displayName: form.displayName,
        roleId: Number(form.roleId),
        enabled: form.enabled
      })
    }
    editorOpen.value = false
    load()
  } catch (e) {
    formError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  const action = row.enabled ? '禁用' : '启用'
  if (!window.confirm(`确认${action}账号「${row.displayName}」？`)) return
  try {
    await userApi.status(row.id, !row.enabled)
    load()
  } catch (e) {
    alert(e.message || `${action}失败`)
  }
}

function openReset(row) {
  resetTarget.value = row
  resetPassword.value = ''
  resetError.value = ''
  resetOpen.value = true
}

async function submitReset() {
  resetError.value = ''
  try {
    await userApi.resetPassword(resetTarget.value.id, resetPassword.value)
    resetOpen.value = false
  } catch (e) {
    resetError.value = e.message || '重置失败'
  }
}

function prevPage() {
  if (page.value > 1) { page.value--; load() }
}
function nextPage() {
  if (page.value < totalPages.value) { page.value++; load() }
}

onMounted(() => {
  loadRoles()
  load()
})
</script>
```

Template requirements:

- Page heading `人员管理` and button `新增账号`.
- Filter row for username, display name, status, role.
- Table columns: 用户名、姓名、角色、状态、创建时间、操作.
- Operation buttons: 编辑、启用/禁用、重置密码.
- Pagination matching `TasksView`.
- Create/edit `AppModal` with username disabled in edit mode, initial password only in create mode, display name, role select, enabled checkbox.
- Reset-password `AppModal` with password input and error text.
- Use `class="card-canvas table-card"`, `.btn`, `.input`, `.select`, `.badge` from existing styles.

- [ ] **Step 2: Register the users route and navigation link**

Add to router:

```js
{
  path: '/users',
  name: 'users',
  component: () => import('../views/UsersView.vue'),
  meta: { permission: 'user:manage' }
}
```

Add to `App.vue` inside the nav:

```vue
<router-link
  v-if="auth.hasPermission('user:manage')"
  to="/users"
  class="nav-link"
>
  人员管理
</router-link>
```

- [ ] **Step 3: Build and verify**

Run:

```bash
cd frontend
npm run build
```

Expected: build exits `0`.

Browser checks:

- `qinghuan` cannot see `/users` navigation and is redirected away from `/users`.
- `admin` can create a test account, edit it, disable/enable it, and reset its password.
- Duplicate username and invalid password errors appear inside the modal.
- Disabling `admin` while logged in as `admin` is rejected.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/UsersView.vue frontend/src/router/index.js frontend/src/App.vue
git commit -m "feat: add frontend account management"
```

---

### Task 11: Frontend Role and Permission Management Page

**Files:**
- Create: `frontend/src/views/RolesView.vue`
- Modify: `frontend/src/router/index.js`
- Modify: `frontend/src/App.vue`
- Test: production build and browser verification

**Interfaces:**
- Consumes: `roleApi.list`, `roleApi.permissions`, `roleApi.create`, `roleApi.update`, `roleApi.remove`.
- Produces: route `/roles` and role/permission editing UI.

- [ ] **Step 1: Create the role management view**

Create `RolesView.vue` with:

```js
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { roleApi } from '../api'
import AppModal from '../components/AppModal.vue'

const list = ref([])
const permissions = ref([])
const loading = ref(false)
const editorOpen = ref(false)
const editing = ref(null)
const saving = ref(false)
const formError = ref('')
const form = reactive({ code: '', name: '', description: '', permissionIds: [] })

async function load() {
  loading.value = true
  try {
    const [roles, allPermissions] = await Promise.all([
      roleApi.list(),
      roleApi.permissions()
    ])
    list.value = roles
    permissions.value = allPermissions
  } catch (e) {
    alert(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.code = ''
  form.name = ''
  form.description = ''
  form.permissionIds = []
  formError.value = ''
  editorOpen.value = true
}

function openEdit(role) {
  editing.value = role
  form.code = role.code
  form.name = role.name
  form.description = role.description || ''
  form.permissionIds = [...role.permissionIds]
  formError.value = ''
  editorOpen.value = true
}

function togglePermission(id) {
  const ids = new Set(form.permissionIds)
  ids.has(id) ? ids.delete(id) : ids.add(id)
  form.permissionIds = [...ids]
}

async function save() {
  if (!form.code || !form.name) return (formError.value = '请填写角色编码和名称')
  saving.value = true
  formError.value = ''
  const payload = {
    code: form.code,
    name: form.name,
    description: form.description,
    permissionIds: form.permissionIds
  }
  try {
    if (editing.value) {
      await roleApi.update(editing.value.id, payload)
    } else {
      await roleApi.create(payload)
    }
    editorOpen.value = false
    load()
  } catch (e) {
    formError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function remove(role) {
  if (!window.confirm(`确认删除角色「${role.name}」？`)) return
  try {
    await roleApi.remove(role.id)
    load()
  } catch (e) {
    alert(e.message || '删除失败')
  }
}

onMounted(load)
</script>
```

Template requirements:

- Page heading `角色管理` and button `新增角色`.
- Table columns: 角色编码、角色名称、说明、账号数、权限、操作.
- Permission cells show permission names, not raw IDs.
- `SUPER_ADMIN` row shows a locked badge and no delete button.
- Create/edit modal fields: code, name, description, permission checkbox list.
- Code input is disabled when editing a built-in role.
- Permission checkboxes are disabled for `SUPER_ADMIN`.
- Save/delete errors appear in the modal or browser alert and never close the modal on failure.
- Use the existing card, table, button, input, and badge styles.

- [ ] **Step 2: Register the roles route and navigation link**

Add to router:

```js
{
  path: '/roles',
  name: 'roles',
  component: () => import('../views/RolesView.vue'),
  meta: { permission: 'role:manage' }
}
```

Add to `App.vue` inside the nav:

```vue
<router-link
  v-if="auth.hasPermission('role:manage')"
  to="/roles"
  class="nav-link"
>
  角色管理
</router-link>
```

- [ ] **Step 3: Build and verify**

Run:

```bash
cd frontend
npm run build
```

Expected: build exits `0`.

Browser checks:

- `qinghuan` cannot see or open `/roles`.
- `admin` can create a custom role with `task:self:view` only.
- Creating a role with `task:self:manage` but without `task:self:view` is rejected.
- `SUPER_ADMIN` permissions are visible but not editable.
- A role assigned to an account cannot be deleted.
- An unused custom role can be deleted.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/RolesView.vue frontend/src/router/index.js frontend/src/App.vue
git commit -m "feat: add frontend role management"
```

---

### Task 12: End-to-End Migration, Verification, and Documentation

**Files:**
- Create: `scripts/verify-auth-api.sh`
- Modify: `README.md`
- Test: backend, frontend, migration, API, and production bundle

**Interfaces:**
- Consumes: all previous tasks.
- Produces: verified migrated local database and verified authenticated API.

- [ ] **Step 1: Create the API verification script**

Create `scripts/verify-auth-api.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
PASSWORD_ADMIN="${PASSWORD_ADMIN:-Admin@123456}"
PASSWORD_QINGHUAN="${PASSWORD_QINGHUAN:-Qinghuan@123456}"

json_value() {
  python3 -c "import json,sys; print(json.load(sys.stdin)$1)"
}

login() {
  curl -fsS -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}

admin_json="$(login admin "$PASSWORD_ADMIN")"
admin_token="$(printf '%s' "$admin_json" | json_value "['data']['token']")"
qinghuan_json="$(login qinghuan "$PASSWORD_QINGHUAN")"
qinghuan_token="$(printf '%s' "$qinghuan_json" | json_value "['data']['token']")"

admin_tasks="$(curl -fsS "$BASE_URL/api/tasks?page=1&size=1" \
  -H "Authorization: Bearer $admin_token")"
qinghuan_tasks="$(curl -fsS "$BASE_URL/api/tasks?page=1&size=1" \
  -H "Authorization: Bearer $qinghuan_token")"
spoofed_tasks="$(curl -fsS "$BASE_URL/api/tasks?page=1&size=1&ownerId=2" \
  -H "Authorization: Bearer $qinghuan_token")"
summary="$(curl -fsS "$BASE_URL/api/daily-summary" \
  -H "Authorization: Bearer $qinghuan_token")"
users="$(curl -fsS "$BASE_URL/api/users?page=1&size=10" \
  -H "Authorization: Bearer $admin_token")"

admin_total="$(printf '%s' "$admin_tasks" | json_value "['data']['total']")"
qinghuan_total="$(printf '%s' "$qinghuan_tasks" | json_value "['data']['total']")"
spoofed_total="$(printf '%s' "$spoofed_tasks" | json_value "['data']['total']")"
summary_owner="$(printf '%s' "$summary" | json_value "['data']['ownerId']")"
user_total="$(printf '%s' "$users" | json_value "['data']['total']")"

[ "$admin_total" = "$qinghuan_total" ] || {
  echo "expected admin and qinghuan totals to match after migration, got $admin_total vs $qinghuan_total"
  exit 1
}
[ "$spoofed_total" = "$qinghuan_total" ] || {
  echo "normal user ownerId spoof changed result: $spoofed_total vs $qinghuan_total"
  exit 1
}
[ "$summary_owner" = "1" ] || {
  echo "daily summary owner should be qinghuan id=1, got $summary_owner"
  exit 1
}
[ "$user_total" -ge 2 ] || {
  echo "expected at least two managed users, got $user_total"
  exit 1
}

unauth_status="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/api/auth/me")"
[ "$unauth_status" = "401" ] || {
  echo "expected unauthenticated /api/auth/me to return 401, got $unauth_status"
  exit 1
}

echo "auth api ok: tasks=$admin_total users=$user_total summaryOwner=$summary_owner"
```

- [ ] **Step 2: Back up and migrate the existing database**

Run:

```bash
mysqldump -uroot -proot123456 -h127.0.0.1 it_workbench \
  > /tmp/it-workbench-before-auth-$(date +%Y%m%d%H%M%S).sql
mysql -uroot -proot123456 -h127.0.0.1 it_workbench \
  < backend/sql/migrate-v2-login-rbac.sql
./scripts/verify-auth-migration.sh
```

Expected: migration script exits `0`; verification prints `tasks=<existing count>`.

- [ ] **Step 3: Run backend tests**

Run:

```bash
mvn -f backend/pom.xml test
```

Expected: build succeeds with zero test failures. If Maven needs to download dependencies, allow network access and rerun.

- [ ] **Step 4: Run frontend tests and build**

Run:

```bash
cd frontend
npm test
npm run build
cd ..
```

Expected: Vitest exits `0`; Vite production build exits `0`.

- [ ] **Step 5: Build the single-port artifact**

Run:

```bash
./scripts/build-all.sh
```

Expected: `backend/target/it-workbench-1.0.0.jar` is generated and verifies the frontend embedded static resources.

- [ ] **Step 6: Restart the backend and verify APIs**

Stop the currently running backend on port `8080`, then run:

```bash
java -jar backend/target/it-workbench-1.0.0.jar
```

Wait for `Started ItWorkbenchApplication`, then in another terminal run:

```bash
chmod +x scripts/verify-auth-api.sh
./scripts/verify-auth-api.sh
```

Expected: `auth api ok: tasks=<existing count> users=2 summaryOwner=1`.

- [ ] **Step 7: Verify static routes and assets**

Run:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/login
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/tasks
```

Expected: each returns `200`.

- [ ] **Step 8: Perform browser acceptance checks**

Log in as `admin` and verify:

- All migrated tasks show `清欢`.
- Owner filtering returns only the selected user's tasks.
- Person management and role management are available.
- A custom role cannot bypass permission dependency validation.
- The last super administrator cannot be disabled or moved to another role.
- Homepage explicitly says statistics range is all people.

Log in as `qinghuan` and verify:

- Only 清欢's tasks are visible.
- `/users` and `/roles` are inaccessible.
- Owner filter is hidden.
- Homepage says statistics range is self.
- Daily summary says it covers the current user's tasks and only those tasks are included.

- [ ] **Step 9: Update README with login usage**

Add a `登录与权限` section containing:

- Bootstrap account names and passwords.
- JWT environment variables.
- The migration command.
- A warning to change bootstrap passwords in production.
- Permission codes and role behavior.
- The fact that daily summaries are always personal.

- [ ] **Step 10: Final verification and commit**

Run once more:

```bash
mvn -f backend/pom.xml test
cd frontend && npm test && npm run build
cd ..
./scripts/verify-auth-migration.sh
./scripts/verify-auth-api.sh
git diff --check
```

Expected: all commands exit `0`, with no whitespace errors.

Commit:

```bash
git add scripts/verify-auth-api.sh README.md
git commit -m "test: verify login rbac end to end"
```
