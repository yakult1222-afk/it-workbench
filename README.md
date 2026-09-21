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
- 日报总结：将当日任务的描述/状态/总结交由 AI 汇总为约 200 字中文日报，弹窗展示并支持一键复制（未配置 AI 时自动降级为模板汇总）

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Vue Router + axios + 原生 CSS 设计令牌 |
| 后端 | Java 17 + Spring Boot 3.2 + MyBatis-Plus 3.5 + Lombok |
| 数据库 | MySQL 8（utf8mb4） |
| 构建 | Maven（后端）、npm（前端） |
| 部署 | 可执行 jar / Docker Compose |

## 目录结构

```
it-workbench/
├── backend/                    后端 Spring Boot 工程
│   ├── sql/init.sql            建库建表 + 示例数据
│   └── src/
│       ├── main/java/com/qinghuan/workbench/
│       │   ├── controller/     TaskController / StatsController / NewsController
│       │   ├── service/        TaskService / StatsService / NewsService
│       │   ├── mapper/         TaskMapper
│       │   ├── entity/         Task
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
│   └── mysql-stop.sh           停止本机 MySQL
├── Dockerfile                  三阶段构建（前端 → 后端 → 运行镜像）
└── docker-compose.yml          MySQL + 后端一键部署
```

## 快速开始（本地开发）

要求：JDK 17、Maven 3.9+、Node 18+、MySQL 8。

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

### 2. 初始化数据库（仅首次）

数据目录已初始化过，正常情况下跳过。若需重建：

```bash
mysql -uroot -proot123456 -h127.0.0.1 < backend/sql/init.sql
```

### 3. 启动后端（默认 8080 端口）

```bash
cd backend
mvn spring-boot:run
```

数据库连接可用环境变量覆盖：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD`。
默认值：`localhost:3306/it_workbench`，账号 `root` / `root123456`。

### 4. 启动前端（默认 5173 端口，`/api` 由 Vite 代理到 8080）

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173

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

MySQL 首次启动自动执行 `backend/sql/init.sql`。访问 http://localhost:8080
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
| GET | `/api/daily-summary?date=` | 日报总结（date 缺省为今天），返回 `{content, source, taskCount, date}` |

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
| `AI_BASE_URL`（即 `workbench.ai.base-url`） | `https://api.deepseek.com/v1` | AI 服务地址，任何 OpenAI 兼容接口均可（GLM / 通义 / OpenAI / 本地 Ollama 等） |
| `AI_API_KEY`（即 `workbench.ai.api-key`） | 空 | AI 服务密钥；**留空则日报走模板汇总，不调用 AI** |
| `AI_MODEL`（即 `workbench.ai.model`） | `deepseek-chat` | 模型名 |

> AI 调用发生在后端，密钥不会暴露给浏览器。接口层为 OpenAI 兼容协议，超时 30 秒；调用失败自动降级为模板汇总，前端会标注来源（AI 生成 / 模板生成）。

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
