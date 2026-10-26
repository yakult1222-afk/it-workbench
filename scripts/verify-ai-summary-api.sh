#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-qinghuan}"
PASSWORD="${PASSWORD:-Qinghuan@123456}"
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-180}"

if ! command -v jq >/dev/null 2>&1; then
  echo "需要 jq 解析接口响应" >&2
  exit 1
fi

login_response="$(
  curl -fsS -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "$(jq -cn --arg username "$USERNAME" --arg password "$PASSWORD" \
      '{username: $username, password: $password}')"
)"
token="$(jq -r '.data.token // empty' <<<"$login_response")"
if [[ -z "$token" ]]; then
  echo "登录失败：$(jq -r '.msg // "未返回 token"' <<<"$login_response")" >&2
  exit 1
fi

auth=(-H "Authorization: Bearer $token")
job_response="$(curl -fsS -X POST "$BASE_URL/api/daily-summary/jobs" "${auth[@]}")"
job_id="$(jq -r '.data.jobId // empty' <<<"$job_response")"
if [[ -z "$job_id" ]]; then
  echo "创建日报任务失败：$job_response" >&2
  exit 1
fi

job="$(jq '.data' <<<"$job_response")"
status="$(jq -r '.status // empty' <<<"$job")"
for ((elapsed = 0; elapsed <= MAX_WAIT_SECONDS; elapsed++)); do
  case "$status" in
    SUCCEEDED|DEGRADED|FAILED|SUPERSEDED)
      break
      ;;
  esac
  if ((elapsed == MAX_WAIT_SECONDS)); then
    echo "等待日报任务 $job_id 超时（${MAX_WAIT_SECONDS}s）" >&2
    exit 1
  fi
  sleep 1
  job_response="$(curl -fsS "$BASE_URL/api/daily-summary/jobs/$job_id" "${auth[@]}")"
  job="$(jq '.data' <<<"$job_response")"
  status="$(jq -r '.status // empty' <<<"$job")"
done

content="$(jq -r '.content // empty' <<<"$job")"
source="$(jq -r '.source // empty' <<<"$job")"
if [[ "$status" != "SUCCEEDED" && "$status" != "DEGRADED" ]]; then
  echo "日报任务未成功：$job" >&2
  exit 1
fi
if [[ -z "$content" ]]; then
  echo "日报正文为空：$job" >&2
  exit 1
fi
if [[ "$source" != "ai" && "$source" != "template" ]]; then
  echo "日报来源异常：$source" >&2
  exit 1
fi

second_response="$(curl -fsS -X POST "$BASE_URL/api/daily-summary/jobs" "${auth[@]}")"
second_job_id="$(jq -r '.data.jobId // empty' <<<"$second_response")"
if [[ "$second_job_id" != "$job_id" ]]; then
  echo "重复请求未复用任务：first=$job_id second=$second_job_id" >&2
  exit 1
fi

jq -n \
  --arg jobId "$job_id" \
  --arg status "$status" \
  --arg source "$source" \
  --arg contentPreview "${content:0:80}" \
  '{ok: true, jobId: $jobId, status: $status, source: $source, contentPreview: $contentPreview}'
