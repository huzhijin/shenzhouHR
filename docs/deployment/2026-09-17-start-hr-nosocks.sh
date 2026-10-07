#!/bin/bash
# 不覆盖 /opt/shenzhouhr/start-prod.sh
# 只生成 /tmp/start-prod-nosocks.sh：给 JVM 关掉系统代理，强制 IPv4
set -u
ORIG=/opt/shenzhouhr/start-prod.sh
TMP=/tmp/start-prod-nosocks.sh
HR_LOG=/opt/shenzhouhr/logs/shenzhouhr.log
BASE=http://127.0.0.1:9090

echo "[$(date '+%F %T')] java proxy settings:"
java -XshowSettings:properties -version 2>&1 | grep -iE 'proxy|socks' || echo '(none)'

echo "[$(date '+%F %T')] make temp start script"
# 在 java -jar 前插入 -D，不改原文件
sed 's#nohup java -jar#nohup java -Djava.net.useSystemProxies=false -Djava.net.preferIPv4Stack=true -jar#' \
  "$ORIG" > "$TMP"
chmod +x "$TMP"
grep -n 'nohup java' "$TMP" | grep -viE 'password|secret|pepper|key|jdbc'

if pgrep -f 'shenzhou-hr.jar' >/dev/null; then
  echo "[$(date '+%F %T')] stop stuck java"
  pkill -15 -f 'shenzhou-hr.jar' || true
  sleep 3
  pkill -9 -f 'shenzhou-hr.jar' || true
fi

unset ALL_PROXY all_proxy HTTP_PROXY HTTPS_PROXY http_proxy https_proxy \
      SOCKS_PROXY socks_proxy JAVA_TOOL_OPTIONS _JAVA_OPTIONS

echo "[$(date '+%F %T')] start temp"
bash "$TMP"

for i in $(seq 1 36); do
  c=$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "$BASE/api/v1/auth/session" 2>/dev/null || true)
  echo "[$(date '+%F %T')] health $i => $c"
  case "$c" in 200|401|403) echo UP; exit 0 ;; esac
  sleep 5
done

echo FAIL
ls -l --time-style=full-iso "$HR_LOG"
grep -E 'Started |Connect timed out|CommunicationsException' "$HR_LOG" | tail -10
exit 1
