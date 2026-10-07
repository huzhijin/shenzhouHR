# 宝塔升级包 20260920-holiday-sync

补班日按日历上班、得力打卡和 OA 单据 10 分钟增量同步。换 jar 和前端，并改 env 里的 cron。

**不覆盖** `start-prod.sh`。不要回拨 OA 水位。不要开得力回放。不要整月重算。szsc 加班插件不用重装。

发布包：`release-candidates/shenzhouhr-release-20260920-holiday-sync.tar.gz`

## 本包做什么

| 项 | 说明 |
| --- | --- |
| 法定假日/补班核算 | 日历标成工作日或特殊工作日时，不再按「今天是周日」打回休息。9/20、10/10 默认组按平时上班。现场日历数据本来就对，本包不改日历。大连不调休保持原样。 |
| 得力打卡 | 每 10 分钟水位增量（`:00/:10/:20/:30/:40/:50`）。OA 加班单读这些已激活卡，**不需要报表重算就能提**。 |
| OA 单据 | 每 10 分钟水位增量，错开在 `:05/:15/:25/:35/:45/:55`。提单本身不靠这次同步；HR 报表要看见单子才靠它。 |
| 报表重算 | 仍只 **0 点、12 点**自动算。本包部署后不要整月重算。 |
| 工作日历页 | 默认看今天附近，不再一打开跳到 12/29。 |

## 1. 本机上传

```bash
scp /Users/huzhijin/Downloads/shenzhouHR/release-candidates/shenzhouhr-release-20260920-holiday-sync.tar.gz \
    /Users/huzhijin/Downloads/shenzhouHR/docs/deployment/2026-09-20-holiday-sync-upgrade.sh \
    root@服务器:/root/
```

服务器上改名：

```bash
mv /root/2026-09-20-holiday-sync-upgrade.sh /root/upgrade-holiday-sync.sh
```

## 2. 服务器部署

```bash
sha256sum /root/shenzhouhr-release-20260920-holiday-sync.tar.gz
# 必须与本说明底部校验和一致

chmod +x /root/upgrade-holiday-sync.sh
bash /root/upgrade-holiday-sync.sh
```

脚本会备份旧 jar、旧前端、旧 env，改这两行（没有就追加）：

```bash
SHENZHOUHR_DELI_AUTO_SYNC_CRON=0 */10 * * * ?
SHENZHOUHR_OA_AUTO_SYNC_CRON=0 5/10 * * * ?
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

## 3. 验收（不要整月重算）

1. 同步记录：得力约每 10 分钟一条，OA 错开 5 分钟。
2. 得力打完卡，最多等约 10 分钟，OA 加班单应能看到卡并提交。不点「重新计算本月」。
3. 工作日历打开后日期窗应在今天附近，不是 12/29–12/31。
4. 默认组今天 9/20 仍是特殊工作日；大连组仍是周末。

## 4. 不要做的

- 不要覆盖 `start-prod.sh`
- 不要回拨 OA 水位、不要跑 `run-oa-resync.sh`
- 不要改得力回放开关
- 不要整月重算江苏神州
- 不要改大连中秋国庆日历
- 不要重装 szsc

## 5. 回滚

```bash
pkill -15 -f 'shenzhou-hr.jar' || true
sleep 2
ls /opt/shenzhouhr/backups/shenzhou-hr.jar.before-holiday-sync-* | tail -1
# 把上面那份拷回
cp -a /opt/shenzhouhr/backups/shenzhou-hr.jar.before-holiday-sync-时间戳 \
  /opt/shenzhouhr/app/shenzhou-hr.jar
# env 备份在原文件旁 *.before-holiday-sync-时间戳
/opt/shenzhouhr/start-prod.sh
```

前端备份在 `/opt/shenzhouhr/backups/web-*-before-holiday-sync-*.tar.gz`。

## 校验和

```
810d6206f3f5260739b456b9421c94074af1cdf6d6451901330d5ed0863aaa28
```
