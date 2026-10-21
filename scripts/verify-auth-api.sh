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
