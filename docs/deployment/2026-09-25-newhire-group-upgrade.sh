#!/usr/bin/env bash
# 新人录入选考勤组，10 分钟后只认本人隔离卡并按人重算。
# 换 jar 和前端。不回拨水位，不开手动回放，不整月重算全公司。
# 把本文件与 shenzhouhr-release-20260925-newhire-group.tar.gz 放到 /root 后：
#   bash /root/upgrade-newhire-group.sh

set -euo pipefail
export COPYFILE_DISABLE=1
cd /root

PKG=/root/shenzhouhr-release-20260925-newhire-group.tar.gz
DIR=/root/shenzhouhr-release-20260925-newhire-group

test -f "$PKG"
tar --no-same-owner -xzf "$PKG" || true
if [[ ! -s "$DIR/backend/shenzhou-hr.jar" || ! -f "$DIR/web/index.html" ]]; then
  python3 - <<'PY'
import tarfile
tarfile.open("/root/shenzhouhr-release-20260925-newhire-group.tar.gz").extractall("/root")
print("python extract done")
PY
fi
test -s "$DIR/backend/shenzhou-hr.jar"
test -f "$DIR/web/index.html"

python3 - <<'PY'
import sys
from pathlib import Path
from zipfile import ZipFile

jar = Path("/root/shenzhouhr-release-20260925-newhire-group/backend/shenzhou-hr.jar")
with ZipFile(jar) as z:
    names = set(z.namelist())
    required = [
        "BOOT-INF/classes/application.yml",
        "BOOT-INF/classes/com/szsemicon/hr/evidenceingestion/application/DeliQuarantineRematchService.class",
        "BOOT-INF/classes/com/szsemicon/hr/people/application/PeopleManagementService.class",
    ]
    missing = [name for name in required if name not in names]
    if missing:
        print("失败：jar 缺文件\n" + "\n".join(missing), file=sys.stderr)
        sys.exit(1)
    yml = z.read("BOOT-INF/classes/application.yml").decode("utf-8", "replace")
    if "quarantine-rematch-quiet: ${SHENZHOUHR_DELI_QUARANTINE_REMATCH_QUIET:PT10M}" not in yml:
        print("失败：jar 内等待时间不是 10 分钟", file=sys.stderr)
        sys.exit(1)
    service = z.read(
        "BOOT-INF/classes/com/szsemicon/hr/evidenceingestion/application/"
        "DeliQuarantineRematchService.class"
    )
    snapshot = z.read(
        "BOOT-INF/classes/com/szsemicon/hr/reporting/application/"
        "RealtimeAttendanceReportSnapshotService.class"
    )
    people = z.read(
        "BOOT-INF/classes/com/szsemicon/hr/people/interfaces/rest/"
        "PeopleManagementDtos$EmployeeCreateRequest.class"
    )
    if b"materializeEmployeeRange" not in snapshot:
        print("失败：jar 仍会整月重算", file=sys.stderr)
        sys.exit(1)
    if b"attendanceGroupId" not in people:
        print("失败：新建员工没有考勤组字段", file=sys.stderr)
        sys.exit(1)
    if b"Deli quarantine rematch enabled=" not in service:
        print("失败：jar 未含新人隔离补卡启动日志", file=sys.stderr)
        sys.exit(1)
web = Path("/root/shenzhouhr-release-20260925-newhire-group/web")
assets = list(web.glob("assets/*.js"))
if not any(b"attendanceGroupId" in path.read_bytes() for path in assets):
    print("失败：前端没有考勤组选择", file=sys.stderr)
    sys.exit(1)
print("package checks ok")
PY

mkdir -p /opt/shenzhouhr/backups /opt/shenzhouhr/logs
TS=$(date +%Y%m%d%H%M%S)
if [[ -f /opt/shenzhouhr/app/shenzhou-hr.jar ]]; then
  cp -a /opt/shenzhouhr/app/shenzhou-hr.jar \
    /opt/shenzhouhr/backups/shenzhou-hr.jar.before-newhire-group-$TS
fi

WEB_ROOTS=()
for candidate in \
    /www/wwwroot/192.168.160.226 \
    /www/wwwroot/58.220.159.170 \
    /www/wwwroot/shenzhouhr; do
  [[ -d "$candidate" ]] && WEB_ROOTS+=("$candidate")
done
while IFS= read -r conf; do
  [[ -z "$conf" ]] && continue
  root=$(awk '/listen[[:space:]]+23273/{f=1} f && /root[[:space:]]/{print $2; exit}' "$conf" | tr -d ';')
  if [[ -n "$root" && -d "$root" ]]; then
    WEB_ROOTS+=("$root")
  fi
done < <(ls /www/server/panel/vhost/nginx/*.conf 2>/dev/null || true)
if [[ ${#WEB_ROOTS[@]} -eq 0 ]]; then
  WEB_ROOTS+=(/www/wwwroot/192.168.160.226)
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
  cp -a "$ENV_FILE" "${ENV_FILE}.before-newhire-group-$TS"
  python3 - "$ENV_FILE" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
wanted = {
    "SHENZHOUHR_DELI_QUARANTINE_REMATCH_ENABLED": "true",
    "SHENZHOUHR_DELI_QUARANTINE_REMATCH_QUIET": "PT10M",
    "SHENZHOUHR_DELI_REPLAY_ENABLED": "false",
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
  echo "警告：未找到 shenzhouhr.env。jar 默认仍是等 10 分钟、只按人重算。"
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
printf '%s\n' "${WEB_ROOTS[@]}" | awk 'NF && !seen[$0]++' | while read -r dest; do
  echo "覆盖前端 $dest"
  tar -czf "/opt/shenzhouhr/backups/web-$(basename "$dest")-before-newhire-group-$TS.tar.gz" \
    -C "$(dirname "$dest")" "$(basename "$dest")" || true
  rsync -a --delete --exclude '.user.ini' "$DIR/web/" "$dest/"
  test -f "$dest/index.html"
done

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
echo "新建员工时要选考勤组。保存 10 分钟后只认这个人的隔离卡，并从最早一张卡算到当天。"
echo "不回拨水位。不要把 SHENZHOUHR_DELI_REPLAY_ENABLED 改成 true。不要整月重算全公司。"
