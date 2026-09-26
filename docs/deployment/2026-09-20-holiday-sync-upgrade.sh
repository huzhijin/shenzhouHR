#!/usr/bin/env bash
# 法定假日补班核算 + 得力/OA 10 分钟增量同步。
# 换 jar 和前端，并改 env 里的 cron。不覆盖 start-prod.sh。
# 不要回拨 OA 水位。不要改得力回放开关。不要整月重算。
# 把本文件与 shenzhouhr-release-20260920-holiday-sync.tar.gz 放到 /root 后：
#   bash /root/upgrade-holiday-sync.sh

set -euo pipefail
export COPYFILE_DISABLE=1
cd /root

PKG=/root/shenzhouhr-release-20260920-holiday-sync.tar.gz
DIR=/root/shenzhouhr-release-20260920-holiday-sync
DELI_CRON='0 */10 * * * ?'
OA_CRON='0 5/10 * * * ?'

test -f "$PKG"
tar --no-same-owner -xzf "$PKG" || true
if [[ ! -s "$DIR/backend/shenzhou-hr.jar" || ! -f "$DIR/web/index.html" ]]; then
  python3 - <<'PY'
import tarfile
tarfile.open("/root/shenzhouhr-release-20260920-holiday-sync.tar.gz").extractall("/root")
print("python extract done")
PY
fi
test -s "$DIR/backend/shenzhou-hr.jar"
test -f "$DIR/web/index.html"
test -d "$DIR/web/assets"

python3 - <<'PY'
import sys
from pathlib import Path
from zipfile import ZipFile

jar = Path("/root/shenzhouhr-release-20260920-holiday-sync/backend/shenzhou-hr.jar")
with ZipFile(jar) as z:
    names = set(z.namelist())
    required = [
        "BOOT-INF/classes/application.yml",
        "BOOT-INF/classes/com/szsemicon/hr/reporting/infrastructure/orchestrator/FullCalculationEngineOrchestrator.class",
        "BOOT-INF/classes/com/szsemicon/hr/evidenceingestion/infrastructure/scheduler/DeliAutoSyncJob.class",
        "BOOT-INF/classes/com/szsemicon/hr/evidenceingestion/infrastructure/scheduler/OaAutoSyncJob.class",
    ]
    missing = [name for name in required if name not in names]
    if missing:
        print("失败：jar 缺文件\n" + "\n".join(missing), file=sys.stderr)
        sys.exit(1)
    yml = z.read("BOOT-INF/classes/application.yml").decode("utf-8", "replace")
    if "0 */10 * * * ?" not in yml or "0 5/10 * * * ?" not in yml:
        print("失败：jar 内 application.yml 未带 10 分钟增量 cron", file=sys.stderr)
        sys.exit(1)
    orchestrator = z.read(
        "BOOT-INF/classes/com/szsemicon/hr/reporting/infrastructure/"
        "orchestrator/FullCalculationEngineOrchestrator.class"
    )
    if b"weekendDayType" not in orchestrator:
        print("失败：jar 未含补班日核算修正", file=sys.stderr)
        sys.exit(1)
print("package checks ok")
PY

mkdir -p /opt/shenzhouhr/backups /opt/shenzhouhr/logs
TS=$(date +%Y%m%d%H%M%S)
if [[ -f /opt/shenzhouhr/app/shenzhou-hr.jar ]]; then
  cp -a /opt/shenzhouhr/app/shenzhou-hr.jar \
    /opt/shenzhouhr/backups/shenzhou-hr.jar.before-holiday-sync-$TS
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
  tar -czf "/opt/shenzhouhr/backups/web-$(basename "$dest")-before-holiday-sync-$TS.tar.gz" \
    -C "$(dirname "$dest")" "$(basename "$dest")" || true
  rsync -a --delete --exclude '.user.ini' "$DIR/web/" "$dest/"
  test -f "$dest/index.html"
done

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
  echo "警告：未找到 shenzhouhr.env，将依赖 jar 默认 cron。启动脚本若写死旧 cron，仍会覆盖默认值。"
else
  cp -a "$ENV_FILE" "${ENV_FILE}.before-holiday-sync-$TS"
  python3 - "$ENV_FILE" "$DELI_CRON" "$OA_CRON" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
deli = sys.argv[2]
oa = sys.argv[3]
text = path.read_text(encoding="utf-8")
lines = text.splitlines()
wanted = {
    "SHENZHOUHR_DELI_AUTO_SYNC_CRON": deli,
    "SHENZHOUHR_OA_AUTO_SYNC_CRON": oa,
}
seen = set()
out = []
for line in lines:
    stripped = line.strip()
    key = None
    if stripped and not stripped.startswith("#") and "=" in stripped:
        key = stripped.split("=", 1)[0].strip()
    if key in wanted:
        out.append(f"{key}={wanted[key]}")
        seen.add(key)
    else:
        out.append(line)
for key, value in wanted.items():
    if key not in seen:
        out.append(f"{key}={value}")
path.write_text("\n".join(out) + "\n", encoding="utf-8")
print(f"updated {path}")
print("SHENZHOUHR_DELI_AUTO_SYNC_CRON=" + deli)
print("SHENZHOUHR_OA_AUTO_SYNC_CRON=" + oa)
PY
fi

LOG=/opt/shenzhouhr/logs/shenzhouhr.log
touch "$LOG"
START_LINE=$(wc -l < "$LOG")
ls -lh /opt/shenzhouhr/app/shenzhou-hr.jar
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
  | grep -a -E 'Started ShenzhouHrApplication|Migrating schema to version|APPLICATION FAILED|Scheduled Deli|Scheduled OA' \
  | tail -n 40

if [[ "$ok" != "1" ]]; then
  echo "失败：进程起来了但 /api/v1/auth/session 无 401/200。"
  exit 1
fi

echo "部署完成。"
echo "不要覆盖 start-prod.sh。不要回拨 OA 水位。不要整月重算。"
echo "浏览器 Ctrl+F5。同步记录：得力 :00/:10/…，OA :05/:15/…。"
echo "加班单不需要报表重算，等打卡进 HR 即可提。"
