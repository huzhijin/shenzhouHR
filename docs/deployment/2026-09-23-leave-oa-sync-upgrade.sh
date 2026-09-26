#!/usr/bin/env bash
# 年假/调休：报表 slot 完成后同步，窗口从 2026-09-19 起。
# 只换 jar，并在 env 写入 SHENZHOUHR_LEAVE_OA_SYNC_EFFECTIVE_FROM。
# 不覆盖 start-prod.sh，不覆盖前端，不回拨 OA 水位，不整月重算。
# 把本文件与 shenzhouhr-release-20260923-leave-oa-sync.tar.gz 放到 /root 后：
#   bash /root/upgrade-leave-oa-sync.sh

set -euo pipefail
export COPYFILE_DISABLE=1
cd /root

PKG=/root/shenzhouhr-release-20260923-leave-oa-sync.tar.gz
DIR=/root/shenzhouhr-release-20260923-leave-oa-sync
EFFECTIVE_FROM=2026-09-19

test -f "$PKG"
tar --no-same-owner -xzf "$PKG" || true
if [[ ! -s "$DIR/backend/shenzhou-hr.jar" ]]; then
  python3 - <<'PY'
import tarfile
tarfile.open("/root/shenzhouhr-release-20260923-leave-oa-sync.tar.gz").extractall("/root")
print("python extract done")
PY
fi
test -s "$DIR/backend/shenzhou-hr.jar"

python3 - <<'PY'
import sys
from pathlib import Path
from zipfile import ZipFile

jar = Path("/root/shenzhouhr-release-20260923-leave-oa-sync/backend/shenzhou-hr.jar")
with ZipFile(jar) as z:
    names = set(z.namelist())
    required = [
        "BOOT-INF/classes/application.yml",
        "BOOT-INF/classes/com/szsemicon/hr/reporting/application/ReportSlotCompletionListener.class",
        "BOOT-INF/classes/com/szsemicon/hr/leavetimeaccount/infrastructure/scheduler/LeaveAccountOaRecalculateJob.class",
        "BOOT-INF/classes/com/szsemicon/hr/leavetimeaccount/application/LeaveAccountOaRecalculateService.class",
    ]
    missing = [name for name in required if name not in names]
    if missing:
        print("失败：jar 缺文件\n" + "\n".join(missing), file=sys.stderr)
        sys.exit(1)
    yml = z.read("BOOT-INF/classes/application.yml").decode("utf-8", "replace")
    if "SHENZHOUHR_LEAVE_OA_SYNC_EFFECTIVE_FROM:2026-09-19" not in yml:
        print("失败：jar 内 application.yml 未绑定 2026-09-19", file=sys.stderr)
        sys.exit(1)
    service = z.read(
        "BOOT-INF/classes/com/szsemicon/hr/leavetimeaccount/application/"
        "LeaveAccountOaRecalculateService.class"
    )
    if b"OA leave sync effective from" not in service:
        print("失败：jar 未含额度同步下界日志", file=sys.stderr)
        sys.exit(1)
print("package checks ok")
PY

mkdir -p /opt/shenzhouhr/backups /opt/shenzhouhr/logs
TS=$(date +%Y%m%d%H%M%S)
if [[ -f /opt/shenzhouhr/app/shenzhou-hr.jar ]]; then
  cp -a /opt/shenzhouhr/app/shenzhou-hr.jar \
    /opt/shenzhouhr/backups/shenzhou-hr.jar.before-leave-oa-sync-$TS
fi

ENV_FILE=""
for candidate in \
    /etc/shenzhouhr/shenzhouhr.env \
    /opt/shenzhouhr/shenzhouhr.env \
    /opt/shenzhouhr/env/shenzhouhr.env; do
  if [[ -f "$candidate" ]]; then
    ENV_FILE="$candidate"
    break
  fi
done
if [[ -z "$ENV_FILE" ]]; then
  echo "警告：未找到 shenzhouhr.env。jar 默认仍是 ${EFFECTIVE_FROM}。"
else
  cp -a "$ENV_FILE" "${ENV_FILE}.before-leave-oa-sync-$TS"
  python3 - "$ENV_FILE" "$EFFECTIVE_FROM" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
effective = sys.argv[2]
text = path.read_text(encoding="utf-8")
lines = text.splitlines()
key = "SHENZHOUHR_LEAVE_OA_SYNC_EFFECTIVE_FROM"
seen = False
out = []
for line in lines:
    stripped = line.strip()
    current = None
    if stripped and not stripped.startswith("#") and "=" in stripped:
        current = stripped.split("=", 1)[0].strip()
    if current == key:
        out.append(f"{key}={effective}")
        seen = True
    else:
        out.append(line)
if not seen:
    out.append(f"{key}={effective}")
path.write_text("\n".join(out) + "\n", encoding="utf-8")
print(f"updated {path}")
print(f"{key}={effective}")
PY
fi

echo "停止 shenzhou-hr.jar"
pkill -15 -f 'shenzhou-hr.jar' || true
sleep 3
pkill -9 -f 'shenzhou-hr.jar' || true
sleep 1
if pgrep -f 'shenzhou-hr.jar' >/dev/null; then
  echo '失败：进程仍在'
  pgrep -af 'shenzhou-hr.jar'
  exit 1
fi

cp -f "$DIR/backend/shenzhou-hr.jar" /opt/shenzhouhr/app/shenzhou-hr.jar
ls -lh /opt/shenzhouhr/app/shenzhou-hr.jar

LOG=/opt/shenzhouhr/logs/shenzhouhr.log
touch "$LOG"
START_LINE=$(wc -l < "$LOG")
/opt/shenzhouhr/start-prod.sh

echo '等待启动…'
ok=0
for i in $(seq 1 60); do
  NEW=$(sed -n "$((START_LINE+1)),\$p" "$LOG" || true)
  if printf '%s\n' "$NEW" | grep -a 'APPLICATION FAILED TO START' >/dev/null; then
    echo '失败：应用启动失败'
    printf '%s\n' "$NEW" | grep -a -E 'APPLICATION FAILED TO START|Caused by:' | tail -n 20
    exit 1
  fi
  code=$(curl -sS -m 5 -o /dev/null -w '%{http_code}' \
    http://127.0.0.1:9090/api/v1/auth/session || true)
  echo "health try $i => $code"
  if [[ "$code" =~ ^(200|401|403)$ ]]; then
    ok=1
    break
  fi
  code=$(curl -sS -m 5 -o /dev/null -w '%{http_code}' \
    http://127.0.0.1:8080/api/v1/auth/session || true)
  echo "health 8080 try $i => $code"
  if [[ "$code" =~ ^(200|401|403)$ ]]; then
    ok=1
    break
  fi
  echo "等待启动… $((i*5))s"
  sleep 5
done

sed -n "$((START_LINE+1)),\$p" "$LOG" \
  | grep -a -E 'Started ShenzhouHrApplication|OA leave sync effective from|APPLICATION FAILED' \
  | tail -n 20

if [[ "$ok" != "1" ]]; then
  echo "失败：进程起来了但 /api/v1/auth/session 无 401/200。"
  exit 1
fi

if ! sed -n "$((START_LINE+1)),\$p" "$LOG" | grep -a 'OA leave sync effective from 2026-09-19' >/dev/null; then
  echo "失败：启动日志没有 OA leave sync effective from 2026-09-19"
  echo "不要整月重算。先看日志再决定是否回滚。"
  exit 1
fi

echo "部署完成。"
echo "不覆盖 start-prod.sh。不覆盖前端。不回拨 OA 水位。不要整月重算。"
echo "额度同步下界 2026-09-19。9 月 18 日那批 OA_SYNC 不会被冲正。"
