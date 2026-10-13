# 宝塔升级包 20260825-deli-identity

得力打卡认人清理：彭伟 / 陆玉蕾 / 李扬 / 王善源 8 月前半段漏刷，是设备工号错挂 + 历史 raw 没回放，**只点重新计算补不出来**。

发布包：`release-candidates/shenzhouhr-release-20260825-deli-identity.tar.gz`

SHA256：`dd36a18fd3518bf8fd259a43901c52bb46f97e1e43558f72a27413378952537c`

两个文件都传到 `/root` 后，宝塔终端只跑一行（**不用再输账号密码**）：

```bash
bash /root/upgrade-deli-identity.sh
```

回放可能要 10–40 分钟。怕终端断线就改成：

```bash
nohup bash /root/upgrade-deli-identity.sh >> /root/upgrade-deli-identity.log 2>&1 &
tail -f /root/upgrade-deli-identity.log
```

看完日志 `Ctrl+C` 只停 tail，不要停后台。

文件：

- `release-candidates/shenzhouhr-release-20260825-deli-identity.tar.gz`
- `release-candidates/upgrade-deli-identity.sh`

只要换 jar、先不回放：

```bash
SKIP_REPLAY=1 bash /root/upgrade-deli-identity.sh
```

然后再：

```bash
nohup bash /root/run-deli-identity.sh >> /root/deli-identity-replay.log 2>&1 &
tail -f /root/deli-identity-replay.log
```

## 这一行会做什么

1. 备份并替换 `/opt/shenzhouhr/app/shenzhou-hr.jar`
2. **临时**用环境变量打开回放（不改 `start-prod.sh`，不改得力 00:00/12:00 cron）
3. 按得力人员目录×花名册给**全员**写雪花绑定（设备工号是别人的，按姓名认到本人；七月那几人只是已核对的覆盖）
4. 回放 2026-08-01 起到今天的得力打卡（错挂挪走、隔离转有效）
5. 重算江苏神州 2026-08
6. 按原 `start-prod.sh` 再拉起一次，回放开关关掉
7. 若 `/root` 有两份得力 Excel，自动对账，结果 `/root/compare-deli-2026-08-after-identity.tsv`

前端不用换。没有专门为这次新增的 Flyway。若库还停在 V59，启动时会执行已有的 V60（员工自助不再看公司月报），那是之前工作台改动。

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 启动：`/opt/shenzhouhr/start-prod.sh`
- 前端：`/www/wwwroot/192.168.160.226`

不要覆盖启动脚本。若日更脚本还在跑，等它跑完再部署。

## 验收

浏览器 **Ctrl+F5**，江苏神州 2026-08 月报：

- 彭伟 `SZST0335`、陆玉蕾 `SZST0284`、李扬 `SZST0291`：8/1–8/12 得力表有卡的日子，格子不能再全空
- 赵艺娴 8/1–8/13 自己的上班卡还在
- 居军 `SZST0017`、张晨阳 `SZST0663` 仍按花名册对
- 黄凯按 `SZST0667`，不用改 Excel
- 脚本列出的 `SZST067x–070x` 残留不算这次已经清掉

成功日志里应有回放 HTTP 200，并出现 `identityMovedCount` / `identityReplayedCount`。`stillQuarantined` 可以有，但要看 reason，不能当没看见。
