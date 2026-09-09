#!/usr/bin/env bash
# 仅换前端：SVG 图标数字 size + 去掉 meta CSP 的 frame-ancestors。
# 不换 jar，不覆盖 /opt/shenzhouhr/start-prod.sh，不跑 Flyway，不重算。
# 把本文件与 shenzhouhr-release-20260909-browser-compat.tar.gz 放到 /root 后：
#   bash /root/upgrade-browser-compat.sh

set -euo pipefail
export COPYFILE_DISABLE=1
cd /root
test -f /root/shenzhouhr-release-20260909-browser-compat.tar.gz
tar --no-same-owner -xzf /root/shenzhouhr-release-20260909-browser-compat.tar.gz
test -f /root/shenzhouhr-release-20260909-browser-compat/web/index.html
test -d /root/shenzhouhr-release-20260909-browser-compat/web/assets
if grep -q "frame-ancestors" /root/shenzhouhr-release-20260909-browser-compat/web/index.html; then
  echo '失败：index.html 仍含 frame-ancestors'
  exit 1
fi
if grep -R -q 'var(--size-icon' /root/shenzhouhr-release-20260909-browser-compat/web/assets; then
  echo '失败：前端包仍含 var(--size-icon'
  exit 1
fi

mkdir -p /opt/shenzhouhr/backups
TS=$(date +%Y%m%d%H%M%S)
tar -czf /opt/shenzhouhr/backups/web-192.168.160.226-before-browser-compat-$TS.tar.gz \
  -C /www/wwwroot 192.168.160.226

rsync -a --delete --exclude '.user.ini' \
  /root/shenzhouhr-release-20260909-browser-compat/web/ \
  /www/wwwroot/192.168.160.226/

test -f /www/wwwroot/192.168.160.226/index.html
if grep -q "frame-ancestors" /www/wwwroot/192.168.160.226/index.html; then
  echo '失败：已发布 index.html 仍含 frame-ancestors'
  exit 1
fi
echo '部署完成。只换了前端。浏览器 Ctrl+F5。用 360 打开登录页确认不再空白。'
