# 宝塔升级包 20260828-report-ot-leave

考勤报表导出、补签不迟到、周末午前下班、每日加班费/转调休、调休日报、哺乳假按日 1 小时、异常总览按已入库 OA 隐藏。

发布包：`release-candidates/shenzhouhr-release-20260828-report-ot-leave.tar.gz`

## 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260828-report-ot-leave.tar.gz \
    release-candidates/upgrade-report-ot-leave.sh \
    root@服务器:/root/
```

## 服务器

```bash
sha256sum /root/shenzhouhr-release-20260828-report-ot-leave.tar.gz
# 必须是
# 893a35b419c720e4e1c8ad0c781dd074a1034001ed400b0223c8bfeac1873b78

bash /root/upgrade-report-ot-leave.sh
```

出现「部署完成」后浏览器 **Ctrl+F5**。

无新 Flyway。不覆盖 `start-prod.sh`。

## 必须重算 OPEN 月

补签标志、哺乳假格子、午前下班、批准后异常事实，都要重算才进钉住结果。关账月不会自动重开。

```bash
nohup bash /root/recalculate-open-month.sh >> /root/recalculate-open-month.log 2>&1 &
tail -f /root/recalculate-open-month.log
```

也可以只对江苏神州在页面点「重新计算本月」。

审批中请假/补签只会从「异常总览」消失，工时和矩阵仍等批准后再算。

## 升完后核对

- 查询报表菜单有「每日加班查询」「加班日报」「调休日报」「请假汇总」「补签」「考勤日报」，没有「纸质加班单」（直链仍可用）
- 报表中心「每日加班」节假日加班后有「加班费」「转调休」；格子绿=加班费、黄=转调休、蓝=义务加班；hover 能列出同一天全部类别
- 「导出当前报表」能下 xlsx；服务端失败时文案为「当前屏幕导出」
- 补签 8:30 显示 `补签08:30`，不标迟到
- 周末两张卡（如 08:17 / 11:36）有加班单时下午显示 11:36，不是漏刷
- 调休额度页仍是额度，不是人×日表；人×日在「调休日报」

## 上海缺卡与 OA 请假（运维，不改 cron）

1. 日事实是否为空、得力绑定是否匹配：见包内 `docs/oa-leave-and-shanghai-gap.md`
2. 若上海昇州/晟州当月无卡：`/sources/attendance-excel` 导厂商月报并发布，再对该公司 OPEN 月重算
3. OA 请假对账：

```bash
bash /root/check-oa-szoa-vs-hr.sh
```

关注 OA `state=3` 已批准但 HR 无 `LEAVE:<formId>`，以及隔离日志里未映射的 `showvalue`。哺乳假应映射 `BREASTFEEDING_TIME`。补映射或 sync 后再重算。
