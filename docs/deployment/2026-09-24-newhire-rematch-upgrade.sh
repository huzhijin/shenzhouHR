#!/usr/bin/env bash
# 新人晚录入：后台补取得力隔离打卡并重算。只换 jar。
# 不覆盖 start-prod.sh，不覆盖前端，不回拨水位，不开手动回放开关。
# 把本文件与 shenzhouhr-release-20260924-newhire-rematch.tar.gz 放到 /root 后：
#   bash /root/upgrade-newhire-rematch.sh

set -euo pipefail
export COPYFILE_DISABLE=1
cd /root

PKG=/root/shenzhouhr-release-20260924-newhire-rematch.tar.gz
DIR=/root/shenzhouhr-release-20260924-newhire-rematch

test -f "$PKG"
tar --no-same-owner -xzf "$PKG" || true
if [[ ! -s "$DIR/backend/shenzhou-hr.jar" ]]; then
  python3 - <<'PY'
import tarfile
tarfile.open("/root/shenzhouhr-release-20260924-newhire-rematch.tar.gz").extractall("/root")
print("python extract done")
PY
fi
test -s "$DIR/backend/shenzhou-hr.jar"

python3 - <<'PY'
import sys
from pathlib import Path
from zipfile import ZipFile

jar = Path("/root/shenzhouhr-release-20260924-newhire-rematch/backend/shenzhou-hr.jar")
with ZipFile(jar) as z:
    names = set(z.namelist())
    required = [
        "BOOT-INF/classes/application.yml",
        "BOOT-INF/classes/com/szsemicon/hr/evidenceingestion/application/DeliQuarantineRematchService.class",
        "BOOT-INF/classes/com/szsemicon/hr/evidenceingestion/infrastructure/scheduler/DeliQuarantineRematchJob.class",
    ]
    missing = [name for name in required if name not in names]
    if missing:
        print("失败：jar 缺文件\n" + "\n".join(missing), file=sys.stderr)
        sys.exit(1)
    yml = z.read("BOOT-INF/classes/application.yml").decode("utf-8", "replace")
    if "quarantine-rematch-enabled: ${SHENZHOUHR_DELI_QUARANTINE_REMATCH_ENABLED:true}" not in yml:
        print("失败：jar 内未默认打开新人隔离补卡", file=sys.stderr)
        sys.exit(1)
    service = z.read(
        "BOOT-INF/classes/com/szsemicon/hr/evidenceingestion/application/"
        "DeliQuarantineRematchService.class"
    )
    if b"Deli quarantine rematch enabled=" not in service:
        print("失败：jar 未含新人隔离补卡启动日志", file=sys.stderr)
        sys.exit(1)
print("package checks ok")
PY

mkdir -p /opt/shenzhouhr/backups /opt/shenzhouhr/logs
TS=$(date +%Y%m%d%H%M%S)
if [[ -f /opt/shenzhouhr/app/shenzhou-hr.jar ]]; then
  cp -a /opt/shenzhouhr/app/shenzhou-hr.jar \
    /opt/shenzhouhr/backups/shenzhou-hr.jar.before-newhire-rematch-$TS
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
if [[ -n "$ENV_FILE" ]]; then
  cp -a "$ENV_FILE" "${ENV_FILE}.before-newhire-rematch-$TS"
  python3 - "$ENV_FILE" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
wanted = {
    "SHENZHOUHR_DELI_QUARANTINE_REMATCH_ENABLED": "true",
    "SHENZHOUHR_DELI_QUARANTINE_REMATCH_CRON": "0 30 21 * * ?",
    "SHENZHOUHR_DELI_QUARANTINE_REMATCH_LOOKBACK_DAYS": "31",
    "SHENZHOUHR_DELI_QUARANTINE_REMATCH_QUIET": "PT2M",
}
text = path.read_text(encoding="utf-8")
lines = text.splitlines()
seen = set()
out = []
for line in lines:
    stripped = line.strip()
    current = None
    if stripped and not stripped.startswith("#") and "=" in stripped:
        current = stripped.split("=", 1)[0].strip()
    if current in wanted:
        out.append(f"{current}={wanted[current]}")
        seen.add(current)
    else:
        out.append(line)
for key, value in wanted.items():
    if key not in seen:
        out.append(f"{key}={value}")
path.write_text("\n".join(out) + "\n", encoding="utf-8")
print(f"updated {path}")
PY
else
  echo "警告：未找到 shenzhouhr.env。jar 默认仍会打开新人隔离补卡。"
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
  | grep -a -E 'Started ShenzhouHrApplication|Deli quarantine rematch enabled|APPLICATION FAILED' \
  | tail -n 20

if [[ "$ok" != "1" ]]; then
  echo "失败：进程起来了但 /api/v1/auth/session 无 401/200。"
  exit 1
fi

if ! sed -n "$((START_LINE+1)),\$p" "$LOG" | grep -a 'Deli quarantine rematch enabled=true' >/dev/null; then
  echo "失败：启动日志没有 Deli quarantine rematch enabled=true"
  echo "不要回拨水位。不要开手动回放。先看日志再决定是否回滚。"
  exit 1
fi

echo "部署完成。"
echo "不覆盖 start-prod.sh。不覆盖前端。不回拨得力/OA 水位。不要打开 SHENZHOUHR_DELI_REPLAY_ENABLED。"
echo "新人保存约 2 分钟后自动补隔离打卡。每天 21:30 再扫最近 31 天。"
