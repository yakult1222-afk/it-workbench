#!/usr/bin/env bash
# 启动后端：自动加载 backend/.env 中的环境变量（文件不存在则使用 application.yml 默认值）
# 用法：
#   ./scripts/start-backend.sh            # 正常启动（mvn spring-boot:run）
#   ./scripts/start-backend.sh --check    # 只打印解析后的配置，不启动
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/backend/.env"
BACKEND_DIR="$ROOT_DIR/backend"

if [ -f "$ENV_FILE" ]; then
  # 逐行加载；已存在的环境变量优先（遵循 dotenv 通用约定，便于临时覆盖）
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      '' | '#'*) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    # 跳过不合法的变量名与未完成的行
    case "$key" in
      *[!A-Za-z0-9_]*) continue ;;
    esac
    if [ -z "$(printenv "$key" 2>/dev/null || true)" ]; then
      export "$key=$value"
    fi
  done < "$ENV_FILE"
  ENV_SOURCE="已加载 $ENV_FILE"
else
  ENV_SOURCE="未找到 ${ENV_FILE}（使用 application.yml 默认值）"
  echo "提示：可复制模板创建配置文件 -> cp backend/.env.example backend/.env"
fi

if [ "${1:-}" = "--check" ]; then
  echo "环境文件：$ENV_SOURCE"
  echo "数据库：${MYSQL_USER:-root}@${MYSQL_HOST:-localhost}:${MYSQL_PORT:-3306}/${MYSQL_DB:-it_workbench}"
  echo "数据库密码：${MYSQL_PASSWORD:-root123456}"
  if [ -n "${AI_API_KEY:-}" ]; then
    echo "AI：已配置（${AI_MODEL:-deepseek-chat} @ ${AI_BASE_URL:-https://api.deepseek.com/v1}），key 长度 ${#AI_API_KEY}"
  else
    echo "AI：未配置 AI_API_KEY，日报将走模板汇总"
  fi
  exit 0
fi

echo "$ENV_SOURCE"
cd "$BACKEND_DIR"
exec mvn spring-boot:run
