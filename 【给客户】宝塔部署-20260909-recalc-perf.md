# 宝塔试跑 20260909-recalc-perf（重算加速）

**只换 jar。** 不换前端，不覆盖 `start-prod.sh`，不改 cron。启动会跑 Flyway V67（OA 单据表加索引）。

## 1. 上传

传到 `/root/`：

- `shenzhouhr-release-20260909-recalc-perf.tar.gz`
- `upgrade-recalc-perf.sh`

## 2. 部署

```bash
chmod +x /root/upgrade-recalc-perf.sh
bash /root/upgrade-recalc-perf.sh
```

等 `Started ShenzhouHrApplication`。日志里应有 `Migrating schema to version 67`。加索引可能要几分钟，不要中途杀进程。

## 3. 试一次整月重算（一家公司）

```bash
COMPANY_ID=41000000-0000-0000-0000-000000000003 \
  nohup bash /root/recalculate-open-month.sh \
  >> /root/recalculate-20260909-perf.log 2>&1 &

tail -f /root/recalculate-20260909-perf.log
```

另开一个终端：

```bash
grep -E 'recalc-stage|recalc-persist' /opt/shenzhouhr/logs/shenzhouhr.log | tail -20
```

记下开始/结束时间和 `oaMs`、`persistMs`。以前整月大约 20–30 分钟、OA 查询大约 89 秒。

## 4. 回滚（若启动失败）

```bash
pkill -15 -f 'shenzhou-hr.jar' || true
sleep 2
ls /opt/shenzhouhr/backups/shenzhou-hr.jar.before-recalc-perf-* | tail -1
cp -a /opt/shenzhouhr/backups/shenzhou-hr.jar.before-recalc-perf-时间戳 \
  /opt/shenzhouhr/app/shenzhou-hr.jar
/opt/shenzhouhr/start-prod.sh
```

V67 索引若已加上，留着无害，不必删。
