## Why

人事工作台把「没两笔卡」标成「阻断」，普通员工看不懂；今天未打完的卡又被当成漏签。吴根银这类休息日后早到（10 日 07:28）被算进前一天。我的考勤/我的假期打同一条易碎看板接口，页面直接失败。OA 补签单既不进查询报表，核算也没当成那一时刻的打卡。

## What Changes

- 工作台只显示有问题的人：迟到、早退、缺卡等。去掉「阻断 / ERROR / WARNING」文案。
- **主看昨天**（完整一天）。今天卡还没打完时不报漏签；上海时间 **12:00 之后**：有迟到显示迟到，还没有上班卡才显示早上漏签。下班漏签今天不报。
- 人事工作台点异常进 **异常总览/详情**，不再跳正式总表。
- 普通员工可看 **本人考勤明细和异常**（工作台 + 我的考勤）。菜单不开放全公司报表。我的假期读真实年假/调休账户。
- 休息日不得把次日班前早到吃进前一天。过夜离开卡仍归前一工作日。
- 查询报表展示 OA **补签**；已批准补签按补签时间计入打卡（消漏签、明细打「补签」、迟到/工时跟这张卡）。
- 提供对照脚本：得力《考勤月报》对服务器考勤明细。
- 不加密得力 cron（仍 0 点 / 12 点）。

## Capabilities

### New Capabilities

- `attendance-workbench-exceptions`: 人事/员工工作台异常列表、昨天口径、中午后今天迟到/早上漏签、人事进异常详情
- `self-attendance-leave-and-exceptions`: 我的考勤明细、我的假期账户、本人异常；EMPLOYEE_SELF 不看全公司报表
- `rest-day-early-arrival-date`: 休息日后早到记在次日，不记在休息日
- `oa-punch-correction-as-punch`: OA 补签出现在查询报表，核算当补签时刻打卡

### Modified Capabilities

- （主规格库尚无已归档能力。本变更收紧先前「班前卡一律归前一天」的过夜规则，仅限过夜离开，不含休息日后早到。）

## Impact

- 工作台：`AttendanceDashboardWorkbenchAssembler`、`DashboardPage`、跳转路径、本人看板。
- 本人页：`SelfAttendanceDashboardService` / Mapper（SELF 范围）、`wave7Gateway` 我的考勤/假期、年假调休读接口。
- 权限：`EMPLOYEE_SELF` 菜单与 `ATTENDANCE_REPORT:READ` / `ATTENDANCE_REPORT_QUERY:READ` 范围。
- 核算：`FullCalculationEngineOrchestrator` 休息日证据窗、OA `PUNCH_CORRECTION` 转打卡（不再当免打卡）。
- 查询报表：补签 sheet；OA 单据列表 409。
- 脚本：`deploy/` 下对照 `sh`，读《考勤月报》和线上 matrix API。
- 已钉住月份须人事「重新计算」后日切和补签才进报表。
