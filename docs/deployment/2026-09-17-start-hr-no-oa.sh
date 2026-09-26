#!/bin/bash
# 不覆盖 start-prod.sh。OA 192.168.2.169:3308 不通，临时关掉 OA 才能起来。
set -u
ORIG=/opt/shenzhouhr/start-prod.sh
TMP=/tmp/start-prod-no-oa.sh
BASE=http://127.0.0.1:9090

sed \
  -e 's#nohup java -jar#nohup java -Djava.net.useSystemProxies=false -Djava.net.preferIPv4Stack=true -jar#' \
  -e 's#--shenzhouhr.integrations.oa-mysql.enabled=true#--shenzhouhr.integrations.oa-mysql.enabled=false#' \
  -e 's#--shenzhouhr.oa.auto-sync-enabled=true#--shenzhouhr.oa.auto-sync-enabled=false#' \
  "$ORIG" > "$TMP"
chmod +x "$TMP"
grep -E 'oa-mysql.enabled|oa.auto-sync-enabled|nohup java' "$TMP" | grep -viE 'password|secret|pepper|key|jdbc'

if pgrep -f 'shenzhou-hr.jar' >/dev/null; then
  pkill -15 -f 'shenzhou-hr.jar' || true
  sleep 3
  pkill -9 -f 'shenzhou-hr.jar' || true
fi

unset ALL_PROXY all_proxy HTTP_PROXY HTTPS_PROXY http_proxy https_proxy \
      SOCKS_PROXY socks_proxy JAVA_TOOL_OPTIONS _JAVA_OPTIONS

echo "[$(date '+%F %T')] start no-oa"
bash "$TMP"

for i in $(seq 1 36); do
  c=$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "$BASE/api/v1/auth/session" 2>/dev/null || true)
  echo "[$(date '+%F %T')] health $i => $c"
  case "$c" in 200|401|403) echo UP; exit 0 ;; esac
  sleep 5
done
echo FAIL
tail -15 /opt/shenzhouhr/logs/shenzhouhr.log
exit 1
