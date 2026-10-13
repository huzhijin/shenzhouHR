# 宝塔升级包 20260923-leave-oa-sync

报表 slot 完成后同步年假/调休，窗口从 **2026-09-19** 起。只换 jar，并在 env 写入下界。不覆盖前端。

**不覆盖** `start-prod.sh`。不要回拨 OA 水位。不要开得力回放。不要整月重算。不要点「按 OA 重算」覆盖 9 月 18 日那批。

发布包：`release-candidates/shenzhouhr-release-20260923-leave-oa-sync.tar.gz`

## 本包做什么

| 项 | 说明 |
| --- | --- |
| 报表 slot 完成后 | 每家公司报表重建成功后，马上按 OA 同步年假、调休。原凌晨 01:00 仍作兜底。 |
| 同步下界 | 只处理 **2026-09-19 及之后** 的 OA 单据。`business_date` 为 2026-09-18 的那 184 条 `OA_SYNC` 不冲正。 |
| 手工调整 | `HR_MANUAL_ADJUSTMENT` / `HR_ADJUST` / `HR_OPENING_IMPORT` 不在冲正范围内。 |
| 配置 | jar 默认就是 `2026-09-19`。现场 `start-prod.sh` 不读 env，所以不能只改 env。脚本仍会把 `SHENZHOUHR_LEAVE_OA_SYNC_EFFECTIVE_FROM=2026-09-19` 写进 env。 |

9 月 18 日脚本的 OA 窗口写到 9 月 30 日。当时已经进库、日期在 9 月 19 日～30 日的单据，会在这次增量里再记一笔。9 月 18 日之后才进系统的单，本来就该补上。

## 1. 本机上传

```bash
scp /Users/huzhijin/Downloads/shenzhouHR/release-candidates/shenzhouhr-release-20260923-leave-oa-sync.tar.gz \
    /Users/huzhijin/Downloads/shenzhouHR/docs/deployment/2026-09-23-leave-oa-sync-upgrade.sh \
    root@服务器:/root/
```

服务器上改名：

```bash
mv /root/2026-09-23-leave-oa-sync-upgrade.sh /root/upgrade-leave-oa-sync.sh
```

## 2. 服务器部署

```bash
sha256sum /root/shenzhouhr-release-20260923-leave-oa-sync.tar.gz
# 必须与本说明底部校验和一致

chmod +x /root/upgrade-leave-oa-sync.sh
bash /root/upgrade-leave-oa-sync.sh
```

脚本会备份旧 jar、旧 env，写入：

```bash
SHENZHOUHR_LEAVE_OA_SYNC_EFFECTIVE_FROM=2026-09-19
```

健康检查出现 `401`，并且日志有 `OA leave sync effective from 2026-09-19`，才算完成。浏览器不用刷新。

## 3. 验收（不要整月重算）

```bash
grep 'OA leave sync effective from' /opt/shenzhouhr/logs/shenzhouhr.log | tail -3
grep '^SHENZHOUHR_LEAVE_OA_SYNC_EFFECTIVE_FROM=' /etc/shenzhouhr/shenzhouhr.env
```

两条都应是 `2026-09-19`。

不要点年假/调休页的「按 OA 重算」。那个按钮走同一套下界，但会按公司重写 9 月 19 日之后的增量流水。

## 4. 不要做的

- 不要覆盖 `start-prod.sh`
- 不要覆盖前端
- 不要回拨 OA 水位、不要跑 `run-oa-resync.sh`
- 不要改得力回放开关
- 不要整月重算
- 不要把 `SHENZHOUHR_LEAVE_OA_SYNC_EFFECTIVE_FROM` 改空

## 5. 回滚

```bash
pkill -15 -f 'shenzhou-hr.jar' || true
sleep 2
ls /opt/shenzhouhr/backups/shenzhou-hr.jar.before-leave-oa-sync-* | tail -1
# 把上面那份拷回
cp -a /opt/shenzhouhr/backups/shenzhou-hr.jar.before-leave-oa-sync-时间戳 \
  /opt/shenzhouhr/app/shenzhou-hr.jar
# env 备份在原文件旁 *.before-leave-oa-sync-时间戳
/opt/shenzhouhr/start-prod.sh
```

## 校验和

```
bdc30720d93ec73a7e22c6c118b355500ea380031e25075bb55d3b71bbbef2ab
```
