# 宝塔升级包 20260825-clock-workbench

合并两段改动：

- 工作台昨日看板白屏修复（session `01a035eb-55a0-7d62-bade-b3034ae49295`）
- 月报 12 点前后最早/最晚打卡、OA 补签投影、得力工号匹配（本段对话）

发布包：`release-candidates/shenzhouhr-release-20260825-clock-workbench.tar.gz`

SHA256：`43f9b88438b9551551e38a5968b6d7a51f19fed68672f26db36e195e6fe9de54`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-clock-workbench.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260825-clock-workbench.tar.gz`
- `release-candidates/upgrade-clock-workbench.sh`

## 这次会改什么

工作台：

- 修复打开后「请求未成功完成 / 考勤工作台数据暂时无法显示」
- 接口按**昨天**看板日期发布；异常图形汇总 + 本月名单；完整明细进「异常总览」

月报打卡：

- 12 点前取最早一刷当上班；没有卡就是上午漏刷
- 12 点后取最晚一刷当天下班；没有卡就是下午漏刷
- 中间多刷当重复卡丢掉
- 工作日交接约 06:00：07:12 当天上班，05:50 过夜离开仍归前一天；周五不再把周六早上卡当末卡
- OA 补签点单据进入月报格子（补签文案和颜色）
- 得力雪花 user_id 覆盖设备上撞车的旧工号

没有新的 Flyway 脚本。

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。若日更脚本还在跑，等它跑完再部署。

## 部署后必须重算 2026-08

浏览器 **Ctrl+F5**。

工作台白屏修好后马上能看。月报格子要等重算，12 点规则和补签才会进钉住结果。只重算江苏神州：

```bash
nohup env COMPANY_ID=41000000-0000-0000-0000-000000000003 \
  bash /root/recalculate-open-month.sh >> /root/recalculate-js.log 2>&1 &
tail -f /root/recalculate-js.log
```

全部 OPEN 公司：

```bash
nohup bash /root/recalculate-open-month.sh >> /root/recalculate-open-month.log 2>&1 &
tail -f /root/recalculate-open-month.log
```

成功行类似：`完成 ... 成功=1 失败=0`。看完 `Ctrl+C` 停掉 tail，不要停后台重算。

不要手工 `DELETE FROM attendance_report_exception_fact`。不要自动重开关账月。

## 验收

1. `/workbench` 能打开，有昨日异常，不再是重试空页；点完整明细进异常总览
2. 月报：同一上午多次打卡只显示最早；同一下午多次只显示最晚
3. 只有上午卡、12 点后没卡 → 下午漏刷；12 点前没卡 → 上午漏刷
4. 张理想 / 殷琚杰 8/4 清晨卡在 8/4，不在 8/3
5. 查询报表 → 补签 能看到 OA 补签；月报格子有补签字样

## 得力对照（可选）

```bash
BASE_URL=http://127.0.0.1:9090 \
USERNAME=szsc_admin_faa41d5bd802 \
PASSWORD='请填现网密码' \
COMPANY_ID=41000000-0000-0000-0000-000000000003 \
PERIOD=2026-08 \
bash /root/compare-deli-month-report.sh \
  "/root/考勤月报-2026年08月01日至2026年08月23日-1787567523402.xlsx" \
  "/root/月度汇总表_20260801_20260824.xlsx"
```
