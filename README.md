# IT 工作台

IT 运维日常任务的登记、跟踪与当日完成率看板。前后端分离架构，前端视觉遵循 `design-md/claude/DESIGN.md` 的 Claude 设计语言（奶油画布 + 珊瑚色 + 衬线标题 + 深色面板）。

## 功能

**首页**
- 当日任务完成率（完成任务数 ÷ 当日任务总数）
- 当日已完成 / 未完成两个任务模块（未完成模块包含「未完成」与「延期」）
- PC 硬件资讯（抓取公开 RSS 源，按硬件关键词筛选，源不可达时降级为内置示例数据）

**任务列表**
- 任务增删改查，字段：日期、任务类型（审批 / 硬件问题 / 软件问题 / 网络问题）、任务描述、任务状态（完成 / 未完成 / 延期）、任务总结
- 按日期 / 类型 / 状态筛选 + 分页
- 日报总结：将当日任务的描述/状态/总结交由 AI 汇总为约 200 字中文日报，弹窗展示并支持一键复制
- 日报异步生成：请求立即进入 MySQL 任务表，后台 Worker 执行 AI 调用；等待弹窗通过阶段文字展示真实任务状态
- 日报缓存：生成结果按当前用户的日期和任务指纹写入 Redis，任务变更后自动失效
- 未配置 AI、AI 重试耗尽或当天无任务时自动使用模板日报

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Vue Router + axios + 原生 CSS 设计令牌 |
| 后端 | Java 17 + Spring Boot 3.2 + MyBatis-Plus 3.5 + Lombok + Spring Scheduling |
| 数据库 | MySQL 8（utf8mb4） |
| 缓存 | Redis 7（日报结果缓存，故障时回退 MySQL） |
| 构建 | Maven（后端）、npm（前端） |
| 部署 | 可执行 jar / Docker Compose |

## 目录结构

```
it-workbench/
├── backend/                    后端 Spring Boot 工程
│   ├── sql/init.sql            建库建表 + 示例数据
│   ├── sql/migrate-v3-ai-summary.sql  已有库新增 AI 日报任务表
│   └── src/
│       ├── main/java/com/qinghuan/workbench/
│       │   ├── controller/     TaskController / StatsController / NewsController
│       │   ├── service/        TaskService / DailySummaryWorker / StatsService / NewsService
│       │   ├── mapper/         TaskMapper / AiSummaryJobMapper
│       │   ├── entity/         Task / AiSummaryJob
│       │   ├── config/         CORS、一体化部署静态资源与前端路由回退
│       │   └── common/         Result 统一响应、全局异常处理
│       └── test/               NewsService 单元测试
├── frontend/                   前端 Vue 工程
│   └── src/
│       ├── assets/tokens.css   设计令牌（来自 DESIGN.md）
│       ├── components/         AppModal / TaskBadge
│       └── views/              HomeView（首页）/ TasksView（任务列表）
├── scripts/
│   ├── build-all.sh            一体化打包脚本
│   ├── mysql-start.sh          启动本机 MySQL
│   ├── mysql-stop.sh           停止本机 MySQL
│   └── verify-ai-summary-api.sh 异步日报 API smoke test
├── Dockerfile                  三阶段构建（前端 → 后端 → 运行镜像）
└── docker-compose.yml          MySQL + Redis + 后端一键部署
```

## 快速开始（本地开发）

要求：JDK 17、Maven 3.9+、Node 18+、MySQL 8、Redis 7。

### 1. 启动 MySQL

本机的 MySQL 是用**官方二进制包**安装的（不是 `brew services`，没有系统服务托管），需要手动拉起：

```bash
./scripts/mysql-start.sh     # 启动（幂等，已在运行会直接提示）
./scripts/mysql-stop.sh      # 停止（优雅 shutdown，数据保留）
```

脚本默认参数（可用环境变量覆盖：`MYSQL_HOME`、`MYSQL_DATA`、`MYSQL_PORT`、`MYSQL_USER`、`MYSQL_PASSWORD`）：

| 项 | 值 |
|---|---|
| 安装目录 | `~/tools/mysql-26.7.0-macos15-arm64` |
| 数据目录 | `~/tools/mysql-data` |
| 端口 | `127.0.0.1:3306` |
| 账号 | `root` / `root123456` |
| 库 | `it_workbench` |
| 错误日志 | `~/tools/mysql-data/mysqld.err` |

等价的原始命令（不用脚本时）：

```bash
~/tools/mysql-26.7.0-macos15-arm64/bin/mysqld \
  --datadir=$HOME/tools/mysql-data --port=3306 --bind-address=127.0.0.1 --skip-log-bin \
  --log-error=$HOME/tools/mysql-data/mysqld.err &

# 停止
~/tools/mysql-26.7.0-macos15-arm64/bin/mysqladmin -uroot -proot123456 -h127.0.0.1 shutdown
```

> 已按 `--skip-log-bin` 启动（本地开发库不需要二进制日志）。若需 PITR/主从，删掉该参数。

### 2. 启动 Redis

本地已有 Redis 时直接启动即可。没有本地 Redis 时，可使用 Docker：

```bash
docker compose up -d redis
```

默认连接 `localhost:6379`。Redis 不可用时，日报缓存读写会降级到 MySQL，不会阻断已完成日报的查询。

### 3. 初始化数据库（仅首次）

数据目录已初始化过，正常情况下跳过。若需重建：

```bash
mysql -uroot -proot123456 -h127.0.0.1 < backend/sql/init.sql
```

已有旧版本数据库需要升级到登录/RBAC 结构时，先备份再执行一次性迁移：

```bash
mysqldump -uroot -proot123456 -h127.0.0.1 it_workbench > /tmp/it-workbench-before-auth.sql
mysql -uroot -proot123456 -h127.0.0.1 it_workbench < backend/sql/migrate-v2-login-rbac.sql
./scripts/verify-auth-migration.sh
```

迁移会将全部旧任务归属到 `qinghuan` 账号，并创建系统角色、权限和初始化账号。

已有数据库还需要升级到异步日报结构：

```bash
mysql -uroot -proot123456 -h127.0.0.1 it_workbench \
  < backend/sql/migrate-v3-ai-summary.sql
```

该脚本只新增 `ai_summary_job` 表，可重复执行。

### 4. 启动后端（默认 8080 端口）

```bash
./scripts/start-backend.sh        # 推荐：自动加载 backend/.env，然后 mvn spring-boot:run
./scripts/start-backend.sh --check  # 只打印解析后的配置，不启动（排查配置问题用）
```

或手动启动：

```bash
cd backend
mvn spring-boot:run
```

### 环境变量配置（backend/.env）

配置项全部支持环境变量注入，**不需要**改任何被提交的文件。首次使用：

```bash
cp backend/.env.example backend/.env   # 复制模板
# 编辑 backend/.env 填入数据库密码、AI_API_KEY 等
```

- `backend/.env` 已被 `.gitignore` 排除，**不会提交到仓库**；`backend/.env.example` 是模板（无真实密钥），随仓库分发。
- 加载优先级：**外部环境变量 > `backend/.env` > `application.yml` 默认值**。所以临时覆盖不用改文件：
  `AI_API_KEY=sk-xxx ./scripts/start-backend.sh`
- 不创建 `.env` 也能跑，全部走 `application.yml` 里的本地开发默认值。

数据库连接可用环境变量覆盖：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD`。
默认值：`localhost:3306/it_workbench`，账号 `root` / `root123456`。

Redis 连接可用环境变量覆盖：`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_TIMEOUT_MS`。

登录与权限相关环境变量：

- `JWT_SECRET`：JWT 签名密钥，至少 32 字节；生产环境必须显式配置。
- `JWT_EXPIRE_HOURS`：登录令牌有效期，默认 8 小时。
- `CORS_ALLOWED_ORIGINS`：允许的前端来源，逗号分隔；本地默认包含 5173 和 8080。

### 5. 启动前端（默认 5173 端口，`/api` 由 Vite 代理到 8080）

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173

## 登录与权限

系统使用用户名密码登录，登录态为 8 小时有效的 JWT。登录后：

- 普通用户只能查看和管理自己的任务，日报只汇总当前登录人当天任务。
- 拥有“查看全部任务”权限的角色可以查看全部任务，并按归属人筛选。
- 拥有人员管理或角色管理权限的账号可以配置账号和角色权限。
- 一个账号只能绑定一个角色，一个角色可以配置多个权限。
- 超级管理员角色拥有全部权限，不能删除或移除权限。

初始化账号仅供本地首次建库或迁移使用：

- 普通用户：`qinghuan` / `Qinghuan@123456`，姓名“清欢”，旧任务全部归属该账号。
- 超级管理员：`admin` / `Admin@123456`。

非本地环境必须修改初始密码，并显式配置 `JWT_SECRET`。登录、JWT 和 CORS 配置可以通过 `backend/.env` 或环境变量覆盖。

权限编码：

- `task:self:view`
- `task:self:manage`
- `task:view:all`
- `task:manage:all`
- `user:manage`
- `role:manage`

数据库迁移完成后可以运行：

```bash
./scripts/verify-auth-migration.sh
./scripts/verify-auth-api.sh
```

## AI 日报异步任务

日报不引入 MQ，使用 MySQL `ai_summary_job` 表作为可靠任务队列：

- `POST /api/daily-summary/jobs?date=YYYY-MM-DD` 创建或复用日报任务。
- `GET /api/daily-summary/jobs/{jobId}` 查询任务状态和结果。
- Worker 每 1 秒通过 `SELECT ... FOR UPDATE SKIP LOCKED` 抢占任务，最多并发 2 个。
- 同一用户、日期和任务内容指纹只执行一次 AI。
- Worker 租约 120 秒，任务最多执行 3 次。
- 第 1、2 次 AI 失败后分别等待 5 秒和 20 秒；第 3 次失败后生成模板日报并标记 `DEGRADED`。
- `5,20,60` 是可配置的退避序列；当前最多执行 3 次，因此第三个等待值保留给后续扩大重试次数时使用。
- 已完成任务保留 30 天，每天凌晨 3 点清理。

状态包括 `QUEUED`、`RUNNING`、`RETRYING`、`SUCCEEDED`、`DEGRADED`、`FAILED` 和 `SUPERSEDED`。MySQL 保存最终正文，Redis 只是缓存；Redis 故障不会导致已完成日报无法查看。

启动服务后可以运行异步日报 smoke test：

```bash
./scripts/verify-ai-summary-api.sh
```

## 一体化打包（单端口运行）

前端产物内置到后端 jar，访问 http://localhost:8080 即可同时获得页面与接口：

```bash
./scripts/build-all.sh
java -jar backend/target/it-workbench-1.0.0.jar
```

## Docker 部署

```bash
docker compose up -d --build
```

MySQL 首次启动自动执行 `backend/sql/init.sql`，Redis 使用持久化数据卷。访问 http://localhost:8080
生产环境请修改 `docker-compose.yml` 中的数据库密码。

## 接口文档

统一响应结构：`{ "code": 0, "msg": "ok", "data": ... }`（`code != 0` 表示失败）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/stats/today` | 当日统计：任务总数、已完成数、未完成数、完成率、已完成/未完成列表 |
| GET | `/api/tasks` | 任务分页查询，参数 `page`、`size`、`taskDate`、`taskType`、`status` |
| GET | `/api/tasks/{id}` | 任务详情 |
| POST | `/api/tasks` | 新增任务 |
| PUT | `/api/tasks/{id}` | 修改任务 |
| DELETE | `/api/tasks/{id}` | 删除任务 |
| GET | `/api/news/hardware` | PC 硬件资讯列表 |
| POST | `/api/daily-summary/jobs?date=` | 创建或复用异步日报任务，date 缺省为今天 |
| GET | `/api/daily-summary/jobs/{jobId}` | 查询日报任务状态、正文和降级原因 |

新增/修改请求体示例：

```json
{
  "taskDate": "2026-09-20",
  "taskType": "硬件问题",
  "description": "打印机卡纸，需更换搓纸轮",
  "status": "完成",
  "summary": "已更换搓纸轮，测试打印正常"
}
```

## 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `workbench.news.sources` | IT之家、快科技、Tom's Hardware 的 RSS | 硬件资讯源，逗号分隔，按顺序尝试 |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` / `MYSQL_USER` / `MYSQL_PASSWORD` | localhost / 3306 / it_workbench / root / root123456 | 数据库连接 |
| `JWT_SECRET` | 本地开发默认值 | JWT 签名密钥，生产环境必须显式配置且至少 32 字节 |
| `JWT_EXPIRE_HOURS` | `8` | JWT 登录态有效小时数 |
| `CORS_ALLOWED_ORIGINS` | 本地 5173/8080 地址 | 允许跨域访问的来源，逗号分隔 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost / 6379 / 空 | Redis 连接；故障时日报缓存降级到 MySQL |
| `AI_SUMMARY_CACHE_TTL_SECONDS` | `1800` | 日报 Redis 缓存 TTL |
| `AI_SUMMARY_WORKER_ENABLED` | `true` | 是否启用应用内异步 Worker |
| `AI_SUMMARY_CONCURRENCY` | `2` | 同时执行的 AI 日报数量 |
| `AI_SUMMARY_MAX_ATTEMPTS` | `3` | 单个日报任务最多执行次数 |
| `AI_SUMMARY_RETRY_DELAYS_SECONDS` | `5,20,60` | 失败后的退避等待序列，单位秒 |
| `AI_BASE_URL`（即 `workbench.ai.base-url`） | `https://api.deepseek.com/v1` | AI 服务地址，任何 OpenAI 兼容接口均可（GLM / 通义 / OpenAI / 本地 Ollama 等） |
| `AI_API_KEY`（即 `workbench.ai.api-key`） | 空 | AI 服务密钥；**留空则日报走模板汇总，不调用 AI** |
| `AI_MODEL`（即 `workbench.ai.model`） | `deepseek-chat` | 模型名 |

> AI 调用发生在后端 Worker，密钥不会暴露给浏览器。接口层为 OpenAI 兼容协议，单次超时 30 秒；任务在后台执行和重试，最终失败时自动降级为模板汇总。

## 设计规范

前端颜色、字体、圆角、间距等全部来自 `design-md/claude/DESIGN.md`：

- 画布 `#faf9f5`（奶油色，非纯白）、主色 `#cc785c`（珊瑚色，仅用于主操作）
- 标题为衬线字体、字重 400、负字距；正文为无衬线
- 圆角：按钮/输入框 8px、卡片 12px；深色面板 `#181715` 用于资讯与页脚
- 徽章为胶囊形，状态用语义色（完成绿 / 未完成橙 / 延期红）

## 已知说明

- 硬件资讯源不可达时自动降级为内置示例数据（标题以「示例：」开头），不影响页面展示。
- 日报总结未配置 `AI_API_KEY` 或 AI 调用失败时，自动降级为模板汇总（前端来源徽章会显示「模板生成」）。
- CORS 当前放开全部来源，便于本机与内网联调；对外发布时建议收敛为具体域名。
