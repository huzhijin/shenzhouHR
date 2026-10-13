# 宝塔升级包 20260824-workbench-self

发布包：`release-candidates/shenzhouhr-release-20260824-workbench-self.tar.gz`

SHA256：`73fefa1edaf089687769de96bcc25b51314301c84bafd6628cf9aca3e43593ab`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-workbench-self.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260824-workbench-self.tar.gz`
- `release-candidates/upgrade-workbench-self.sh`

## 这次会改什么

- 休息日后的早到打卡记在打卡当天。吴根银周一 07:28 不再记到周日；周六白天加班卡仍在周六；工作日过夜离开 05:50 仍归前一工作日
- 人事工作台主看**昨天**异常（迟到、早退、漏签），去掉「阻断」。中午 12 点后才加今天的迟到和早上漏签
- 点异常进 `/attendance/queries/exceptions` 详情，不再跳正式总表
- 查询报表新增「补签」页；已批准 OA 补签按补签时间当打卡，消漏签
- 普通员工：我的考勤有日打卡，我的假期读年假/调休账户；菜单不再开放全公司报表
- Flyway **V60**：从 `EMPLOYEE_SELF` 去掉 `ATTENDANCE_REPORT:READ` 和 `ATTENDANCE_REPORT_QUERY:READ`
- 得力对照脚本：`/root/compare-deli-month-report.sh`

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。若日更脚本还在跑，等它跑完再部署。

## 部署后必须重算 2026-08

浏览器 **Ctrl+F5**。

OPEN 月要重新计算，新日切和补签才会进钉住结果。只重算江苏神州：

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

不要手工 `DELETE FROM attendance_report_exception_fact`。不要自动重开关账月。

## 验收

1. 吴根银 2026-08-10：上班 07:28 在 **10 日**，下班漏刷仍在 10 日；9 日不再挂这张卡
2. 人事工作台无「阻断」；点异常进异常总览
3. 12:00 前工作台不把今天没打卡的人列为漏签
4. 仅员工本人账号：我的考勤有日明细，我的假期不再「服务暂时不可用」
5. 查询报表 → 补签 能看到 OA 补签

## 得力对照（两份都要比）

把这两份都传到服务器：

- 《考勤月报》（日格上班/下班/漏刷，主对照）
- 《月度汇总表》（上下班时刻；没工号、没下班行、整天空白的跳过）

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

看输出里的 `DATE_SHIFT` / `DELI_ONLY` / `TIME_DIFF` / `MISS_MISMATCH` / `CHECK_WU`。
吴根银 10 日 07:28 在《考勤月报》里，不在《月度汇总表》（该表他是空行）。
