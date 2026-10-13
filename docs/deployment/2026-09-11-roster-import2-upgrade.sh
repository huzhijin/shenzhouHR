#!/usr/bin/env bash
# 花名册人员导入 + 重算加速（V67/V68）+ 360 SVG 兼容。
# 换 jar 和前端。不覆盖 start-prod.sh。不要回拨 OA 水位。不要改得力/OA cron。
# 把本文件与 shenzhouhr-release-20260911-roster-import2.tar.gz 放到 /root 后：
#   bash /root/upgrade-roster-import2.sh

set -euo pipefail
export COPYFILE_DISABLE=1
cd /root

PKG=/root/shenzhouhr-release-20260911-roster-import2.tar.gz
DIR=/root/shenzhouhr-release-20260911-roster-import
test -f "$PKG"
tar --no-same-owner -xzf "$PKG" || true
if [[ ! -s "$DIR/backend/shenzhou-hr.jar" || ! -f "$DIR/web/index.html" ]]; then
  python3 - <<'PY'
import tarfile
tarfile.open("/root/shenzhouhr-release-20260911-roster-import2.tar.gz").extractall("/root")
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

jar = Path("/root/shenzhouhr-release-20260911-roster-import2/backend/shenzhou-hr.jar")
with ZipFile(jar) as z:
    names = set(z.namelist())
    required = [
        "BOOT-INF/classes/db/migration/V67__oa_document_window_rank_index.sql",
        "BOOT-INF/classes/db/migration/V68__roster_import_batch.sql",
        "BOOT-INF/classes/com/szsemicon/hr/people/interfaces/rest/RosterImportController.class",
        "BOOT-INF/classes/com/szsemicon/hr/people/application/RosterImportService.class",
        "BOOT-INF/classes/com/szsemicon/hr/reporting/application/FactWriteBatches.class",
    ]
    missing = [name for name in required if name not in names]
    if missing:
        print("失败：jar 缺文件\n" + "\n".join(missing), file=sys.stderr)
        sys.exit(1)
    roster = z.read(
        "BOOT-INF/classes/com/szsemicon/hr/people/application/roster/RosterNames.class"
    )
    if "序号".encode("utf-8") not in roster and b"employeeNumber" not in roster:
        print("失败：jar 花名册表头类异常", file=sys.stderr)
        sys.exit(1)
print("package checks ok")
PY

if grep -q "frame-ancestors" "$DIR/web/index.html"; then
  echo '失败：index.html 仍含 frame-ancestors'
  exit 1
fi
if grep -R --include='*.js' -q 'size:"var(--size-icon' "$DIR/web/assets"; then
  echo '失败：JS 仍把 CSS 变量写进 SVG size'
  exit 1
fi

mkdir -p /opt/shenzhouhr/backups /opt/shenzhouhr/logs
TS=$(date +%Y%m%d%H%M%S)
if [[ -f /opt/shenzhouhr/app/shenzhou-hr.jar ]]; then
  cp -a /opt/shenzhouhr/app/shenzhou-hr.jar \
    /opt/shenzhouhr/backups/shenzhou-hr.jar.before-roster-import2-$TS
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
  tar -czf "/opt/shenzhouhr/backups/web-$(basename "$dest")-before-roster-import2-$TS.tar.gz" \
    -C "$(dirname "$dest")" "$(basename "$dest")" || true
  rsync -a --delete --exclude '.user.ini' "$DIR/web/" "$dest/"
  test -f "$dest/index.html"
done

if [[ -f "$DIR/september-2026-hires.xlsx" ]]; then
  cp -f "$DIR/september-2026-hires.xlsx" /root/september-2026-hires.xlsx
fi

LOG=/opt/shenzhouhr/logs/shenzhouhr.log
touch "$LOG"
START_LINE=$(wc -l < "$LOG")
ls -lh /opt/shenzhouhr/app/shenzhou-hr.jar
/opt/shenzhouhr/start-prod.sh

echo '等待启动（若尚未跑过 V67，加索引可能要几分钟）…'
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
  | grep -a -E 'Started ShenzhouHrApplication|Migrating schema to version|APPLICATION FAILED' \
  | tail -n 40

if [[ "$ok" != "1" ]]; then
  echo "失败：进程起来了但 /api/v1/auth/session 无 401/200。"
  echo "看日志：tail -n 80 /opt/shenzhouhr/logs/shenzhouhr.log"
  exit 1
fi

echo '部署完成。浏览器 Ctrl+F5。'
echo '接着：导入人员页上传 /root/september-2026-hires.xlsx 并确认发布。'
echo '不要覆盖 start-prod.sh。不要回拨 OA 水位。不要改 cron。'
