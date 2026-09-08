#!/usr/bin/env bash
# 查询报表样式 + OA 单据可读列 + 启动修复。换 jar 和前端。含 Flyway V59。
# 不要覆盖 /opt/shenzhouhr/start-prod.sh。

set -euo pipefail
export COPYFILE_DISABLE=1
cd /root
test -f /root/shenzhouhr-release-20260824-query-pages3.tar.gz
tar --no-same-owner -xzf /root/shenzhouhr-release-20260824-query-pages3.tar.gz
test -f /root/shenzhouhr-release-20260824-query-pages3/backend/shenzhou-hr.jar
test -f /root/shenzhouhr-release-20260824-query-pages3/web/index.html
test -f /root/shenzhouhr-release-20260824-query-pages3/db/migration/V59__oa_document_list_indexes.sql
grep -q 'query-report__groups' /root/shenzhouhr-release-20260824-query-pages3/web/assets/*.css
grep -q '外出' /root/shenzhouhr-release-20260824-query-pages3/web/assets/*.js
grep -q 'c5dff0' /root/shenzhouhr-release-20260824-query-pages3/web/assets/*.css

mkdir -p /opt/shenzhouhr/backups
TS=$(date +%Y%m%d%H%M%S)
cp -a /opt/shenzhouhr/app/shenzhou-hr.jar \
  /opt/shenzhouhr/backups/shenzhou-hr.jar.before-query-pages3-$TS
tar -czf /opt/shenzhouhr/backups/web-192.168.160.226-before-query-pages3-$TS.tar.gz \
  -C /www/wwwroot 192.168.160.226

pkill -15 -f 'shenzhou-hr.jar' || true
sleep 3
pkill -9 -f 'shenzhou-hr.jar' || true
sleep 1
if pgrep -f 'shenzhou-hr.jar' >/dev/null; then
  echo '失败：进程仍在'
  pgrep -af 'shenzhou-hr.jar'
  exit 1
fi

cp -f /root/shenzhouhr-release-20260824-query-pages3/backend/shenzhou-hr.jar \
  /opt/shenzhouhr/app/shenzhou-hr.jar
rsync -a --delete --exclude '.user.ini' \
  /root/shenzhouhr-release-20260824-query-pages3/web/ \
  /www/wwwroot/192.168.160.226/

/opt/shenzhouhr/start-prod.sh
sleep 25
curl -sS -m 5 -o /dev/null -w 'health %{http_code}\n' \
  http://127.0.0.1:9090/api/v1/auth/session || true
pgrep -af 'shenzhou-hr.jar' | awk '{print $1,$2,$3}'
test -f /www/wwwroot/192.168.160.226/index.html
echo '部署完成。浏览器 Ctrl+F5。'
