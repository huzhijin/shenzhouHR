# 2026-09-09 重算加速试跑（V67 + 批量写入）

只换 jar。不覆盖 `start-prod.sh`，不换前端，不改 OA/得力 cron。启动时 Flyway 会跑 **V67**（`oa_attendance_document` 加窗口索引，`INPLACE, LOCK=NONE`）。选低峰。

发布包：`release-candidates/shenzhouhr-release-20260909-recalc-perf.tar.gz`

升级脚本：`docs/deployment/2026-09-09-recalc-perf-trial.sh`

## 试什么

对照以前整月重算 20–30 分钟。这次看：

1. 启动日志是否 `Migrating schema to version 67`
2. 一次整月重算墙钟时间
3. 日志 `recalc-stage`（班次/打卡/OA/内存）和 `recalc-persist`（落库）

先试 **一家公司一个月**，不要一次全公司。

## 服务器部署

```bash
sha256sum /root/shenzhouhr-release-20260909-recalc-perf.tar.gz
chmod +x /root/upgrade-recalc-perf.sh
bash /root/upgrade-recalc-perf.sh
```

健康检查出现 `401` 后，确认日志有 V67 且 `Started ShenzhouHrApplication`。

## 试跑重算

现网已有 `/root/recalculate-open-month.sh`。江苏神州：

```bash
COMPANY_ID=41000000-0000-0000-0000-000000000003 \
  nohup bash /root/recalculate-open-month.sh \
  >> /root/recalculate-20260909-perf.log 2>&1 &

tail -f /root/recalculate-20260909-perf.log
```

同时看应用日志：

```bash
grep -E 'recalc-stage|recalc-persist|Migrating schema to version 67' \
  /opt/shenzhouhr/logs/shenzhouhr.log | tail -50
```

`recalc-stage` 里 `oaMs` 应明显小于以前的 ~89 秒；整段墙钟相对 20–30 分钟下降才算这次试跑有效。

## 不要做

- 不要覆盖 `start-prod.sh`
- 不要改超时 3600→300
- 不要同一窗口对所有公司连着重算
- 关账月不要重开
