#!/usr/bin/env bash
# 启动本机 MySQL（官方二进制包方式安装，非 brew services）
# 用法：./scripts/mysql-start.sh      停止：./scripts/mysql-stop.sh
# 说明：本地开发库不需要 binlog，默认以 --skip-log-bin 启动（如需 PITR 请删除该参数）
set -euo pipefail

MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/mysql-26.7.0-macos15-arm64}"
MYSQL_DATA="${MYSQL_DATA:-$HOME/tools/mysql-data}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"

MYSQLD="$MYSQL_HOME/bin/mysqld"
MYSQLADMIN="$MYSQL_HOME/bin/mysqladmin"

[ -x "$MYSQLD" ] || { echo "未找到 mysqld：$MYSQLD"; echo "请设置 MYSQL_HOME 指向 MySQL 安装目录"; exit 1; }
[ -d "$MYSQL_DATA" ] || { echo "数据目录不存在：$MYSQL_DATA"; exit 1; }

# 已在运行则直接退出
if "$MYSQLADMIN" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -h127.0.0.1 -P"$MYSQL_PORT" ping >/dev/null 2>&1; then
  echo "MySQL 已在运行（127.0.0.1:${MYSQL_PORT}）"
  exit 0
fi

echo "启动 MySQL ..."
nohup "$MYSQLD" \
  --datadir="$MYSQL_DATA" \
  --port="$MYSQL_PORT" \
  --bind-address=127.0.0.1 \
  --skip-log-bin \
  --log-error="$MYSQL_DATA/mysqld.err" \
  >/dev/null 2>&1 &

# 等待就绪
for i in $(seq 1 30); do
  if "$MYSQLADMIN" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -h127.0.0.1 -P"$MYSQL_PORT" ping >/dev/null 2>&1; then
    echo "MySQL 已就绪：127.0.0.1:${MYSQL_PORT}（数据库 it_workbench）"
    echo "日志：${MYSQL_DATA}/mysqld.err"
    exit 0
  fi
  sleep 1
done

echo "启动超时，请查看日志：$MYSQL_DATA/mysqld.err"
exit 1
