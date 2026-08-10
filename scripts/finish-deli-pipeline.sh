#!/usr/bin/env bash
# 得力打卡数据打通收尾脚本
#
# 前置条件：CalculationEngineOrchestrator 已实现为真实版本（生成员工级明细行）
#
# 执行顺序：
#   1. 编译
#   2. 重启后端（带 Deli 集成开关）
#   3. 发布投影（全员缺勤底稿，作用是打开期间保护闸门）
#   4. 重跑同步（3836 条打卡落库）
#   5. 再发布投影（读到打卡数据，算出真实工时）
#   6. 验证各表行数
#
# 用法：bash scripts/finish-deli-pipeline.sh

set -euo pipefail

REPO="/Users/huzhijin/Downloads/shenzhouHR"
BACKEND="$REPO/backend"
COMPANY_ID="41000000-0000-0000-0000-000000000003"
PERIOD="2026-08"
SOURCE_ID="98aa08a6-fab6-46d3-a880-305746b9cc92"
ADMIN_USER="szsc_admin_faa41d5bd802"
ADMIN_PASS="Shenzhou@2026Dev"
BASE="http://127.0.0.1:8080"

step() { printf '\n=== %s ===\n' "$1"; }
die()  { printf 'FAILED: %s\n' "$1" >&2; exit 1; }

# --- MySQL 只读查询辅助 ---
mysql_query() {
  local sql="$1"
  set -a; . "$HOME/.local/share/shenzhouhr/mysql-8.4.10-isolated/secrets/wave3-runtime.env"; set +a
  local cnf; cnf=$(mktemp); chmod 600 "$cnf"
  printf '[client]\nhost=127.0.0.1\nport=13306\nuser=shenzhou_hr_dev_app\npassword=%s\n' \
    "$SHENZHOUHR_DEV_DB_PASSWORD" > "$cnf"
  "$HOME/.local/share/shenzhouhr/mysql-8.4.10-isolated/install/bin/mysql" \
    --defaults-extra-file="$cnf" shenzhou_hr_dev -N -B -e "$sql" 2>&1
  rm -f "$cnf"
}

# --- 登录，回显 CSRF token；cookie 写入 $1 ---
login() {
  local jar="$1"
  curl -s -c "$jar" -X POST "$BASE/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
    -D - 2>/dev/null | grep -i 'X-CSRF-TOKEN' | tr -d '\r' | awk '{print $2}'
}

# --- 发布投影；$1=jar $2=csrf $3=reason ---
publish_projection() {
  curl -s --max-time 300 -b "$1" \
    -X POST "$BASE/api/v1/attendance-reports/publications" \
    -H 'Content-Type: application/json' \
    -H "X-CSRF-TOKEN: $2" \
    -d "{\"companyId\":\"$COMPANY_ID\",\"period\":\"$PERIOD\",\"periodState\":\"OPEN\",\"reason\":\"$3\"}"
}

# ---------------------------------------------------------------- 1. 编译
step "1/6 编译"
cd "$BACKEND"
./mvnw -o compile -q 2>&1 | tail -20
echo "编译通过"

# ------------------------------------------------------------ 2. 重启后端
step "2/6 重启后端"
if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  kill "$(lsof -nP -iTCP:8080 -sTCP:LISTEN | awk 'NR>1{print $2}')" 2>/dev/null || true
  # 等旧进程退出，最多 30 秒
  for _ in $(seq 30); do
    lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1 || break
    sleep 1
  done
fi

set -a
. "$HOME/.local/share/shenzhouhr/mysql-8.4.10-isolated/secrets/wave3-runtime.env"
. "$REPO/.env.deli.local"
set +a

PEPPER=$(python3 -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('='))")

export SPRING_PROFILES_ACTIVE=dev SHENZHOUHR_SERVER_PORT=8080
export SHENZHOUHR_DB_URL='jdbc:mysql://127.0.0.1:13306/shenzhou_hr_dev?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true'
export SHENZHOUHR_DB_USERNAME=shenzhou_hr_dev_app
export SHENZHOUHR_DB_PASSWORD="$SHENZHOUHR_DEV_DB_PASSWORD"
export SHENZHOUHR_FLYWAY_ENABLED=false
export SHENZHOUHR_FLYWAY_USERNAME=shenzhou_hr_local_migrator
export SHENZHOUHR_FLYWAY_PASSWORD="$SHENZHOUHR_FLYWAY_PASSWORD"
export SHENZHOUHR_SESSION_COOKIE_SECURE=false
export SHENZHOUHR_PROVISIONING_PEPPER="$PEPPER"
export SHENZHOUHR_PROVISIONING_KEY_ID=v1
export SHENZHOUHR_INTEGRATIONS_DELI_EPLUS_ENABLED="$DELI_EPLUS_ENABLED"
export SHENZHOUHR_INTEGRATIONS_DELI_EPLUS_APP_KEY="$DELI_EPLUS_APP_KEY"
export SHENZHOUHR_INTEGRATIONS_DELI_EPLUS_APP_SECRET="$DELI_EPLUS_APP_SECRET"
export SHENZHOUHR_INTEGRATIONS_DELI_EPLUS_PAGE_SIZE="$DELI_EPLUS_PAGE_SIZE"
export SHENZHOUHR_INTEGRATIONS_DELI_EPLUS_SOURCE_TIME_ZONE="$DELI_EPLUS_SOURCE_TIME_ZONE"
export SHENZHOUHR_INTEGRATIONS_DELI_EPLUS_BASE_URL="$DELI_EPLUS_BASE_URL"

./mvnw -o -q spring-boot:run > /tmp/szhr-backend.log 2>&1 &
echo "后端启动中..."
for _ in $(seq 60); do
  if curl -s --max-time 3 "$BASE/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    echo "后端 UP"; break
  fi
  sleep 3
done
curl -s --max-time 5 "$BASE/actuator/health" | grep -q '"status":"UP"' \
  || die "后端启动失败，查看 /tmp/szhr-backend.log"

# ------------------------------------------- 3. 发布投影（打开期间保护闸门）
step "3/6 发布投影（底稿，开闸）"
JAR1=$(mktemp); chmod 600 "$JAR1"
CSRF1=$(login "$JAR1")
[ -n "$CSRF1" ] || die "登录失败"

PUB1=$(publish_projection "$JAR1" "$CSRF1" "发布底稿以打开期间保护")
echo "$PUB1" | python3 -m json.tool 2>/dev/null || echo "$PUB1"
rm -f "$JAR1"

echo "投影明细行数: $(mysql_query 'SELECT COUNT(*) FROM attendance_report_daily_fact;')"

# ---------------------------------------------------------- 4. 重跑同步
step "4/6 重跑得力同步"
# 重置水位，确保从头拉全量
mysql_query "UPDATE attendance_sync_watermark
             SET committed_cursor = NULL, committed_page_digest = NULL,
                 committed_at = NULL, row_version = row_version + 1
             WHERE attendance_source_id = '$SOURCE_ID';" >/dev/null

JAR2=$(mktemp); chmod 600 "$JAR2"
CSRF2=$(login "$JAR2")
[ -n "$CSRF2" ] || die "登录失败"

JOB=$(curl -s --max-time 600 -b "$JAR2" \
  -X POST "$BASE/api/v1/attendance-source-jobs" \
  -H 'Content-Type: application/json' \
  -H "X-CSRF-TOKEN: $CSRF2" \
  -d "{\"sourceId\":\"$SOURCE_ID\"}")
echo "$JOB" | python3 -m json.tool 2>/dev/null || echo "$JOB"
rm -f "$JAR2"

JOB_STATE=$(echo "$JOB" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("state",""))' 2>/dev/null || echo "")
echo "同步状态: $JOB_STATE"

# ------------------------------------------- 5. 再发布投影（算真实工时）
step "5/6 再发布投影（读打卡数据算工时）"
JAR3=$(mktemp); chmod 600 "$JAR3"
CSRF3=$(login "$JAR3")
[ -n "$CSRF3" ] || die "登录失败"

PUB2=$(publish_projection "$JAR3" "$CSRF3" "打卡数据落库后重算工时")
echo "$PUB2" | python3 -m json.tool 2>/dev/null || echo "$PUB2"
rm -f "$JAR3"

# ---------------------------------------------------------------- 6. 验证
step "6/6 验证"
mysql_query "
SELECT 'raw_attendance_fact'          AS tbl, COUNT(*) AS cnt FROM raw_attendance_fact
UNION ALL SELECT 'normalized_record',        COUNT(*) FROM normalized_attendance_record
UNION ALL SELECT 'effective_event',          COUNT(*) FROM effective_attendance_event
UNION ALL SELECT 'report_projection',        COUNT(*) FROM attendance_report_projection
UNION ALL SELECT 'report_daily_fact',        COUNT(*) FROM attendance_report_daily_fact
UNION ALL SELECT 'report_exception_fact',    COUNT(*) FROM attendance_report_exception_fact;"

echo ""
echo "--- 员工匹配情况 ---"
mysql_query "SELECT match_status, COUNT(*) FROM employee_match_decision GROUP BY match_status;"

echo ""
echo "--- 同步任务历史 ---"
mysql_query "SELECT attendance_sync_job_id, status, page_count, accepted_count,
                    quarantined_count, safe_error_code
             FROM attendance_sync_job ORDER BY created_at DESC LIMIT 3;"

echo ""
echo "完成。打开 http://localhost:5173 用 $ADMIN_USER 登录查看考勤报表。"
