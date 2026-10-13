#!/usr/bin/env bash
# Seed confirmed Deli snowflake bindings, replay 2026-08-01..2026-08-13
# (or through today), then recalculate. Does not change the 00:00/12:00 cron.
#
# Required:
#   BASE_URL   e.g. http://127.0.0.1:8080  or http://58.220.159.170:23273
#   USERNAME   HR admin
#   PASSWORD
# Optional:
#   SOURCE_ID, FROM_DATE=2026-08-01, TO_DATE=2026-08-13
#   THROUGH_TODAY=false SEED_BINDINGS=true RECALCULATE=true
#   CONFIRM=YES  (required to actually POST)
#
# The API stays closed until SHENZHOUHR_DELI_REPLAY_ENABLED=true on the server.
set -euo pipefail

BASE_URL="${BASE_URL:?set BASE_URL}"
USERNAME="${USERNAME:?set USERNAME}"
PASSWORD="${PASSWORD:?set PASSWORD}"
FROM_DATE="${FROM_DATE:-2026-08-01}"
TO_DATE="${TO_DATE:-2026-08-13}"
THROUGH_TODAY="${THROUGH_TODAY:-false}"
SEED_BINDINGS="${SEED_BINDINGS:-true}"
RECALCULATE="${RECALCULATE:-true}"
SOURCE_ID="${SOURCE_ID:-}"
CONFIRM="${CONFIRM:-}"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
cookie="$workdir/cookie"
headers="$workdir/login.headers"

csrf_from_headers() {
  awk 'BEGIN{IGNORECASE=1} tolower($1)=="x-csrf-token:" {print $2}' "$1" \
    | tr -d '\r' | tail -n 1
}

echo "==== login $BASE_URL as $USERNAME ===="
curl -sS -D "$headers" -o "$workdir/login.json" -c "$cookie" \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -X POST "$BASE_URL/api/v1/auth/login" \
  --data "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}"
CSRF="$(csrf_from_headers "$headers")"
if [[ -z "$CSRF" ]]; then
  echo "没有 X-CSRF-TOKEN，登录失败："
  cat "$workdir/login.json"
  exit 1
fi

body="$workdir/replay.json"
{
  printf '{'
  if [[ -n "$SOURCE_ID" ]]; then
    printf '"sourceId":"%s",' "$SOURCE_ID"
  fi
  printf '"fromDate":"%s","toDate":"%s","throughToday":%s,"seedBindings":%s,"recalculate":%s}' \
    "$FROM_DATE" "$TO_DATE" "$THROUGH_TODAY" "$SEED_BINDINGS" "$RECALCULATE"
} > "$body"

echo "==== replay body ===="
cat "$body"
echo
if [[ "$CONFIRM" != "YES" ]]; then
  echo "preview 结束。确认窗口和名单后执行："
  echo "  CONFIRM=YES BASE_URL=... USERNAME=... PASSWORD=... bash $0"
  echo "服务器需 SHENZHOUHR_DELI_REPLAY_ENABLED=true，跑完后改回 false。"
  echo "不要只点「重新计算」。不要改得力 00:00/12:00 cron。"
  exit 0
fi

echo "==== POST /api/v1/attendance-sources/deli-identity-replay ===="
http_code="$(
  curl -sS -o "$workdir/replay-out.json" -w '%{http_code}' \
    -b "$cookie" -c "$cookie" \
    -H 'Content-Type: application/json' -H 'Accept: application/json' \
    -H "X-CSRF-TOKEN: $CSRF" \
    -X POST "$BASE_URL/api/v1/attendance-sources/deli-identity-replay" \
    --data @"$body"
)"
echo "HTTP $http_code"
python3 -m json.tool "$workdir/replay-out.json" || cat "$workdir/replay-out.json"
if [[ "$http_code" != "200" ]]; then
  echo "回放失败。若 code=DELI_REPLAY_DISABLED，先打开 SHENZHOUHR_DELI_REPLAY_ENABLED。"
  exit 1
fi
echo "回放完成。接着用 compare-deli-month-report.sh 跑互补 Excel 验收。"
