#!/usr/bin/env bash
set -euo pipefail

MYSQL_BIN="${MYSQL_BIN:-$HOME/tools/mysql-26.7.0-macos15-arm64/bin/mysql}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"
TEST_DB="it_workbench_migration_guard_test"

mysql_exec() {
  "$MYSQL_BIN" -h"$MYSQL_HOST" -P"$MYSQL_PORT" \
    -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$@"
}

cleanup() {
  mysql_exec -e "DROP DATABASE IF EXISTS $TEST_DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mysql_exec -e "DROP DATABASE IF EXISTS $TEST_DB; CREATE DATABASE $TEST_DB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql_exec "$TEST_DB" -e "
CREATE TABLE task (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    task_date   DATE         NOT NULL,
    task_type   VARCHAR(20)  NOT NULL,
    description VARCHAR(1000) DEFAULT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT '未完成',
    summary     VARCHAR(2000) DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_task_date (task_date),
    KEY idx_status (status)
) ENGINE=InnoDB;
INSERT INTO task (task_date, task_type, description, status) VALUES
  (CURDATE(), '审批', '迁移保护测试 1', '未完成'),
  (CURDATE(), '审批', '迁移保护测试 2', '完成');
"

tmp_sql="$(mktemp)"
trap 'rm -f "$tmp_sql"; cleanup' EXIT
TEST_DB="$TEST_DB" PATH_TO_SQL="$tmp_sql" python3 - <<'PY2'
import os
from pathlib import Path
source = Path('backend/sql/migrate-v2-login-rbac.sql').read_text()
source = source.replace('USE it_workbench;', f"USE {os.environ['TEST_DB']};", 1)
source = source.replace("'qinghuan'", "'qinghuan_missing'", 1)
import re
source = re.sub(r",\s*ADD CONSTRAINT fk_task_owner\s+FOREIGN KEY \(owner_id\) REFERENCES sys_user\(id\) ON DELETE RESTRICT;", ";", source)
Path(os.environ['PATH_TO_SQL']).write_text("SET SESSION sql_mode='';\n" + source)
PY2

set +e
mysql_exec --force < "$tmp_sql" >/tmp/auth-migration-guard-test.log 2>&1
set -e

nullable="$(mysql_exec -N -B -e "
SELECT IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='$TEST_DB' AND TABLE_NAME='task' AND COLUMN_NAME='owner_id';")"
fk_count="$(mysql_exec -N -B -e "
SELECT COUNT(*)
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA='$TEST_DB' AND TABLE_NAME='task'
  AND CONSTRAINT_NAME='fk_task_owner' AND CONSTRAINT_TYPE='FOREIGN KEY';")"

[ "$nullable" = "YES" ] || {
  echo "guard failed: owner_id became nullable=$nullable after invalid seed"
  cat /tmp/auth-migration-guard-test.log
  exit 1
}
[ "$fk_count" = "0" ] || {
  echo "guard failed: foreign key was added after invalid seed"
  cat /tmp/auth-migration-guard-test.log
  exit 1
}

echo "auth migration guard ok: invalid seed stopped before NOT NULL/FK"
