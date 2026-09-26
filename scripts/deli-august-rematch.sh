#!/usr/bin/env bash
# Preview Deli employee/query + August punches vs HR, optionally bind snowflakes,
# then identity-replay August. Does not recalculate.
#
#   bash /root/deli-august-rematch.sh
#   APPLY=1 bash /root/deli-august-rematch.sh
#   APPLY=1 REPLAY=1 bash /root/deli-august-rematch.sh
set -euo pipefail

export MYSQL_PWD="${MYSQL_PWD:?set MYSQL_PWD}"
export OUT_DIR="${OUT_DIR:-/root}"
export APPLY="${APPLY:-0}"
export REPLAY="${REPLAY:-0}"
BASE_URL="${BASE_URL:-http://127.0.0.1:9090}"
USERNAME="${USERNAME:-szsc_admin_faa41d5bd802}"
PASSWORD="${PASSWORD:-}"
FROM_DATE="${FROM_DATE:-2026-08-01}"
TO_DATE="${TO_DATE:-2026-08-31}"

echo "==== processlist (long SELECTs should be gone before replay) ===="
mysql --default-character-set=utf8mb4 -uroot shenzhou_hr -e "SHOW FULL PROCESSLIST\G" \
  | awk 'BEGIN{IGNORECASE=1} /Id:|User:|Time:|State:|Info:/{print}'

echo "==== Deli vs HR compare ===="
python3 /root/deli-august-rematch.py

if [[ "$REPLAY" != "1" ]]; then
  echo
  echo "preview/bindings done. After reviewing /root/deli-august-focus.txt :"
  echo "  APPLY=1 bash $0"
  echo "then rematch August punches (recalculate stays false):"
  echo "  APPLY=1 REPLAY=1 PASSWORD='...' MYSQL_PWD='...' bash $0"
  exit 0
fi
if [[ -z "$PASSWORD" ]]; then
  echo "REPLAY=1 needs PASSWORD"
  exit 1
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
cookie="$workdir/cookie"
headers="$workdir/login.headers"

echo "==== login $BASE_URL ===="
curl -sS -D "$headers" -o "$workdir/login.json" -c "$cookie" \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -X POST "$BASE_URL/api/v1/auth/login" \
  --data "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}"
CSRF="$(awk 'BEGIN{IGNORECASE=1} tolower($1)=="x-csrf-token:" {print $2}' "$headers" | tr -d '\r' | tail -n 1)"
if [[ -z "$CSRF" ]]; then
  echo "login failed:"
  cat "$workdir/login.json"
  exit 1
fi

echo "==== POST deli-identity-replay $FROM_DATE..$TO_DATE seedBindings=true recalculate=false ===="
http_code="$(
  curl -sS -o "$workdir/replay-out.json" -w '%{http_code}' \
    -b "$cookie" -c "$cookie" \
    -H 'Content-Type: application/json' -H 'Accept: application/json' \
    -H "X-CSRF-TOKEN: $CSRF" \
    --max-time 3600 \
    -X POST "$BASE_URL/api/v1/attendance-sources/deli-identity-replay" \
    --data "{\"fromDate\":\"$FROM_DATE\",\"toDate\":\"$TO_DATE\",\"throughToday\":false,\"seedBindings\":true,\"recalculate\":false}"
)"
echo "HTTP $http_code"
python3 -m json.tool "$workdir/replay-out.json" || cat "$workdir/replay-out.json"
if [[ "$http_code" != "200" ]]; then
  echo "回放失败。若 DELI_REPLAY_DISABLED，先临时 SHENZHOUHR_DELI_REPLAY_ENABLED=true 拉起 Java。"
  exit 1
fi
echo "August rematch finished. Recalc later when idle: 昇州 then 晟州 then 江苏."
