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
- 日报总结按钮（当前为占位：弹窗显示「等待后续开发」，并提供一键复制）

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
├── scripts/build-all.sh        一体化打包脚本
├── Dockerfile                  三阶段构建（前端 → 后端 → 运行镜像）
└── docker-compose.yml          MySQL + 后端一键部署
```

## 快速开始（本地开发）

要求：JDK 17、Maven 3.9+、Node 18+、MySQL 8。

**1. 初始化数据库**

```bash
mysql -uroot -p < backend/sql/init.sql
```

**2. 启动后端**（默认 8080 端口）

```bash
cd backend
mvn spring-boot:run
```

数据库连接可用环境变量覆盖：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DB`、`MYSQL_USER`、`MYSQL_PASSWORD`。
默认值：`localhost:3306/it_workbench`，账号 `root` / `root123456`。

**3. 启动前端**（默认 5173 端口，`/api` 由 Vite 代理到 8080）

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

## 设计规范

前端颜色、字体、圆角、间距等全部来自 `design-md/claude/DESIGN.md`：

- 画布 `#faf9f5`（奶油色，非纯白）、主色 `#cc785c`（珊瑚色，仅用于主操作）
- 标题为衬线字体、字重 400、负字距；正文为无衬线
- 圆角：按钮/输入框 8px、卡片 12px；深色面板 `#181715` 用于资讯与页脚
- 徽章为胶囊形，状态用语义色（完成绿 / 未完成橙 / 延期红）

## 已知说明

- 日报总结为占位功能，弹窗文案「等待后续开发」+ 一键复制，业务逻辑待后续开发。
- 硬件资讯源不可达时自动降级为内置示例数据（标题以「示例：」开头），不影响页面展示。
- CORS 当前放开全部来源，便于本机与内网联调；对外发布时建议收敛为具体域名。
