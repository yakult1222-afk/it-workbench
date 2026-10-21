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
