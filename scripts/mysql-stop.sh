#!/usr/bin/env bash
# 停止本机 MySQL（优雅 shutdown，数据保留）
set -euo pipefail

MYSQL_HOME="${MYSQL_HOME:-$HOME/tools/mysql-26.7.0-macos15-arm64}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"

MYSQLADMIN="$MYSQL_HOME/bin/mysqladmin"

if ! "$MYSQLADMIN" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -h127.0.0.1 -P"$MYSQL_PORT" ping >/dev/null 2>&1; then
  echo "MySQL 当前未运行"
  exit 0
fi

"$MYSQLADMIN" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -h127.0.0.1 -P"$MYSQL_PORT" shutdown 2>/dev/null
echo "MySQL 已停止（数据目录：${MYSQL_DATA:-$HOME/tools/mysql-data}）"
