#!/usr/bin/env bash
# 重算加速试跑：只换 jar。Flyway 会执行 V67。
# 不覆盖 /opt/shenzhouhr/start-prod.sh，不换前端，不重算。
# 把本文件与 shenzhouhr-release-20260909-recalc-perf.tar.gz 放到 /root 后：
#   bash /root/upgrade-recalc-perf.sh

set -euo pipefail
export COPYFILE_DISABLE=1
cd /root
test -f /root/shenzhouhr-release-20260909-recalc-perf.tar.gz
tar --no-same-owner -xzf /root/shenzhouhr-release-20260909-recalc-perf.tar.gz
test -f /root/shenzhouhr-release-20260909-recalc-perf/backend/shenzhou-hr.jar

mkdir -p /opt/shenzhouhr/backups /opt/shenzhouhr/logs
TS=$(date +%Y%m%d%H%M%S)
cp -a /opt/shenzhouhr/app/shenzhou-hr.jar \
  /opt/shenzhouhr/backups/shenzhou-hr.jar.before-recalc-perf-$TS

pkill -15 -f 'shenzhou-hr.jar' || true
sleep 3
pkill -9 -f 'shenzhou-hr.jar' || true
sleep 1
if pgrep -f 'shenzhou-hr.jar' >/dev/null; then
  echo '失败：进程仍在'
  pgrep -af 'shenzhou-hr.jar'
  exit 1
fi

cp -f /root/shenzhouhr-release-20260909-recalc-perf/backend/shenzhou-hr.jar \
  /opt/shenzhouhr/app/shenzhou-hr.jar

LOG=/opt/shenzhouhr/logs/shenzhouhr.log
touch "$LOG"
START_LINE=$(wc -l < "$LOG")
/opt/shenzhouhr/start-prod.sh

echo '等待启动（V67 加索引可能要几分钟）…'
for i in $(seq 1 60); do
  NEW=$(sed -n "$((START_LINE+1)),\$p" "$LOG" || true)
  if printf '%s\n' "$NEW" | grep -a 'Started ShenzhouHrApplication' >/dev/null; then
    echo "已启动，约 $((i*5))s"
    break
  fi
  if printf '%s\n' "$NEW" | grep -a 'APPLICATION FAILED TO START' >/dev/null; then
    echo '失败：应用启动失败'
    printf '%s\n' "$NEW" | grep -a -E 'APPLICATION FAILED TO START|Caused by:' | tail -n 20
    exit 1
  fi
  echo "等待启动… $((i*5))s"
  sleep 5
done

curl -sS -m 5 -o /dev/null -w 'health %{http_code}\n' \
  http://127.0.0.1:9090/api/v1/auth/session || true
sed -n "$((START_LINE+1)),\$p" "$LOG" \
  | grep -a -E 'Started ShenzhouHrApplication|Migrating schema to version|APPLICATION FAILED' \
  | tail -n 40
if ! sed -n "$((START_LINE+1)),\$p" "$LOG" | grep -a 'Started ShenzhouHrApplication' >/dev/null; then
  echo '失败：未看到 Started'
  exit 1
fi
echo '部署完成。不要覆盖 start-prod.sh。接下来只对一家公司跑 recalculate-open-month.sh，然后 grep recalc-stage / recalc-persist。'
