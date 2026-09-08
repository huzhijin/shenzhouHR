## Context

日报加班走 `formOvertimeFromEvidence` → `OvertimeMealDeductions.lookup(当天班次)`。周六无段时 `weekdayTemplateOff` 也是空的，晚餐退回 `17:30`。OA 事实走 `allocateFormOvertime` → `lookup(全月班次)`，夏令下班 18:00，同一张 `09:00–18:00` 单是 8 小时。导出读日报，所以颜倩少 0.5。

`AttendanceReportCalculator.workHours` 只聚合 `daily_fact`。查询页 `workHoursPage` 已 left join directory，但 directory 用月末 `asOf` + `employee_version.status = 'ACTIVE'`，月中离职和没事实的人仍对不齐。无考勤组的大连/武汉工作日被 `requireDayType` / `hasScheduledSegments` skip；月历用 OA-only 补人后把空工作日画成漏刷（霍岩）。

任职关岗脚本已有图 1 日期（`effective_to` = 离职次日），崔雨/张自豪原先按姓名。花名册 8/22 快照已拿掉 6 个早走的人，报表仍要出到离职当天。

## Goals / Non-Goals

**Goals:**

- 休息日加班晚餐窗与 OA 事实一致；颜倩 8/29 = 8.0。
- 花名册任职窗口内的人在个人月度工时、月度工时统计表、月历都有行。
- 高管免打卡、大连/武汉无机子：工作日全勤；武汉套扬州班次；加班请假覆盖。
- 图 1 十五人按工号截到离职当天；图 2 多余人不关岗。
- 深圳/合肥有卡走普通规则。

**Non-Goals:**

- 不给谭钊建档。
- 不把深圳/合肥改成全勤。
- 不改 OA 插件、得力水位、工资引擎。
- 不等周磊/杨涛/杨勇/李月工号；对上后再补，不阻塞本轮。
- 不把图 2 部门树写进 HR 任职。

## Decisions

### Decision 1: Rest-day dinner lookup always sees weekday template

**Choice:** `formOvertimeFromEvidence` 传入该员工计算窗口内全部 `shiftSegments`（与 `allocateFormOvertime` 相同），不要只传当天 `daySegments`。`weekendMealDeductions` 在休息日空段时同样用 `weekdayTemplateOff`，禁止 `orElse(17:30)`。

备选：导出改读 OA 事实小时。拒绝，因为月历/日报仍会 7.5。

### Decision 2: Query 月度工时 reuses report-center WORK_HOURS

**Choice:** 报表中心 `AttendanceReportCalculator.workHours` 是唯一数字来源（OA `applyLeaveBreakdown` + `个人实际出勤` 公式）。查询「月度工时统计表」读同一 pin 的 `WORK_HOURS` 数据集（或同一 calculator 输出），不要 `listEmployeeDailyAggregates` 再按 `leave_or_time_off_minutes` 拆假。列名对齐报表中心；可保留工号列。

人集合仍是任职窗口重叠；缺日事实的人靠 Decision 3 写出应出勤日事实后，两边自然同一批人、同一小时。禁止查询页单独 left join directory 填 0，造成「报表中心有数、查询全 0 / 假小时不对」。

备选：只改查询 SQL 的 leave 拆分。拒绝，因为还会和 OA 拆假再漂一次。

### Decision 3: No-clock Dalian/Wuhan and punch-exempt get synthetic scheduled days

**Choice:** **仅 2026-08**，部门路径含大连/武汉、或任意月 standing punch-exempt、当天没有考勤组班次/日类型的，仍写日事实：工作日类型来自公司工作日历，WORK 段来自站点模板（大连 4.5/3.5，武汉/高管扬州当季），`punchExempt=true` 走 `EXEMPT_WORK`，不写漏刷。**2026-09 起大连/武汉有打卡，不再合成全勤**，按普通须打卡人员算。

备选：只在报表组装时补小时、不写 fact。拒绝，因为月历、出勤率、请假贴数都要同一套日事实。

深圳/合肥不走这条；无组无卡就维持现状（有卡则普通核算）。

### Decision 4: Flip OA-only 漏刷 only for 大连/武汉

**Choice:** `AttendanceMonthMatrixAssembler` 里 `oaOnlyEmployee && weekday` 画漏刷的分支，对组织名含大连/武汉改为普通出勤。其它地点保持霍岩旧规格的漏刷，避免把无组的扬州人改成全勤。

### Decision 6: Punch-required OT is capped to last punch; no covering punch = 0 + 漏签

**Choice:** 须打卡人员有覆盖打卡时：把单据结束封到最后一卡（先向下整点/半点）再扣餐。无覆盖打卡：**2026-08 仍按整张单据出小时**（出报表），但异常和漏签照写，方便和得力对；**2026-09 起小时 0**。补签后按实际卡。大连/武汉/standing 免打卡不封顶。

李鑫 8/26：单 18:00–21:00，末卡 20:02 → 20:00，夏令扣晚餐 → 1.5。

备选：继续整张单出 2.5。拒绝，现场要对实际在岗。

### Decision 5: Close leavers by empno; extras are not leavers

**Choice:** 更新 `close-2026-08-jiangsu-leavers.py`：崔雨 `SZST0721`、张自豪 `SZST0722` 进工号表，去掉姓名兜底。谭钊/黄兆隆/唐家轩/张衡/赵子奇不写 `effective_to`。

## Risks / Trade-offs

- [Risk] 武汉无考勤组，合成日事实没有政策行 → 用公司默认考勤政策或扬州组政策拷贝；测艾兵洁在职日。
- [Risk] 全勤人数变多，江苏 500+ 行月度工时更长 → 业务要求；分页已有。
- [Risk] 霍岩旧验收「空工作日漏刷」失败 → 本轮明确翻案；更新夹具为全勤。
- [Risk] 旧 pin 仍是 7.5 和缺人 → OPEN 月重算；关账月不自动重开。
- [Risk] 周磊四人仍缺 → 工号到了再补，不进本轮关岗名单。

## Migration Plan

1. 发后端/前端（若月历组装在后端则前端可能不换）。
2. 预览关任职脚本，确认十五工号、崔雨/张自豪、不关黄兆隆。
3. `APPLY=1` 关岗；江苏神州 2026-08 OPEN 重算。
4. 抽检：颜倩 8/29 = 8；陈觉晓有应出勤；霍岩空工作日白格；崔雨到 8/21；黄兆隆仍在职。
5. 回滚 = 回退 jar；任职 `effective_to` 用脚本反向预览。不回滚 OA 水位。

## Open Questions

- 周磊 / 杨涛 / 杨勇 / 李月工号未到，本轮不对他们建行。
