## 1. 午前下班与漏刷显示

- [x] 1.1 `DayAccumulator.afternoonPunchAt`：`first != last` 时返回 last，禁止因 `morningInstant(last)` 返回 null
- [x] 1.2 `composeSlot`：last 存在时，有加班单不得把下午写成「漏刷」
- [x] 1.3 日报 `journalRemark` / `offDuty`：仅当首末卡相同或 last 为空才「下班漏刷」
- [x] 1.4 单测：周六 08:17+11:36 有加班单 → 上午 08:17、下午 11:36，不是漏刷；仅一张 08:17 仍可下班漏刷
- [x] 1.5 诊断查询或测试夹具：列出 `first != last` 且 last 在 12:00 前的人日，确认显示不再是漏刷

## 2. 补签 8:30 不迟到

- [x] 2.1 矩阵组装器接收 `PUNCH_CORRECTION` POINT（`endExclusive` 可空），按 start 落上午/下午
- [x] 2.2 `composeSlot`：该侧已补签则只出 `补签HH:mm`，不走迟到分支
- [x] 2.3 `markLateFromWorkWindows`：该侧已补签则跳过
- [x] 2.4 单测：补签 08:30 → `补签08:30` 无迟到；普通打卡 08:30 → `08:30 迟到`

## 3. 异常总览 OA 覆盖

- [x] 3.1 确认请假/补签/外出/出差/免打卡在 `UNKNOWN` 时已写入 `oa_attendance_document`；缺的类型补同步
- [x] 3.2 查询 SQL `hideApprovedOaCoveredExceptions` 改为按覆盖表 JOIN 已入库单（含 UNKNOWN/DRAFT/APPROVED/MODIFIED/SUPPLEMENTED）；补签按侧；加班单仅休息日下班漏刷
- [x] 3.3 `AttendanceReportFactProjector`：生效单覆盖后不再写出对应异常事实
- [x] 3.4 单测：审批中请假盖住当天 → 总览无漏刷；批准并投影 → pin 中无该异常；销假后可再出现；驳回/撤销不隐藏

## 4. 报表中心导出

- [x] 4.1 `wave7Gateway.downloadReportExport`：Content-Type 用前缀匹配，允许 charset
- [x] 4.2 `exportCustomerReport` / `handleExport`：创建或下载失败时回退 `downloadCustomerReportWorkbook`，文案区分屏幕导出
- [x] 4.3 核对 `fromDate/toDate` 与 fingerprint，避免无谓 BINDING_STALE
- [x] 4.4 前端/契约测试：带 charset 的 xlsx 能下；绑定失败仍得到本地 xlsx

## 5. 菜单

- [x] 5.1 `AuthenticationController` 不再下发纸质加班单菜单项
- [x] 5.2 `routeAuthorization.capabilitiesByPath` 补 leave-summary、overtime-daily、finance-overtime、daily-journal、makeup、time-off-daily
- [x] 5.3 测试：有查询权限时菜单含每日加班查询；有 `PAPER_OVERTIME:MANAGE` 时菜单无纸质加班单

## 6. 每日加班列、筛选、日历色

- [x] 6.1 查询 `overtime-daily` / `finance-overtime` 与报表中心每日加班：节假日加班后加「加班费」「转调休」列（日事实 paid/compensatory 分钟转小时）
- [x] 6.2 导出编码器与 `customerReportWorkbook` 同步这两列
- [x] 6.3 日历按 paid / compensatory / voluntary 分色；多类同一天主色取分钟最多，hover 列全
- [x] 6.4 `overtimeTreatment` 筛选接到这两列；加班日报补齐细筛
- [x] 6.5 测试：4h 加班费 + 2h 转调休列正确；转调休周六色 ≠ 加班费色

## 7. 调休日报

- [x] 7.1 后端 sheet `time-off-daily`：人×日调休小时，分页与导出
- [x] 7.2 登录菜单 + `QueryReportsPage` 列与部门/员工/日期筛选
- [x] 7.3 测试：两天调休两行；额度页 `time-off` 行为不变

## 8. 哺乳假按日 1 小时

- [x] 8.1 转换层将 `BREASTFEEDING_TIME` 展成每个工作日 `[startClock, startClock+1h]`，跳过周末与节假日
- [x] 8.2 `LeaveHoursRecognizer` / 矩阵涂格使用切片，不把跨日大区间当全天假
- [x] 8.3 产检假保持原区间
- [x] 8.4 单测：8/3 08:30–8/31 09:30 → 工作日仅 08:30–09:30；开始 09:00 → 每天 09:00–10:00；周六无哺乳分钟

## 9. 上海打卡与 OA 请假对账

- [x] 9.1 查上海昇州/晟州当月日事实与得力绑定，确认缺的是未导入还是未匹配
- [x] 9.2 用现有 Excel 导入发布上海打卡，对该公司 OPEN 月重算
- [x] 9.3 跑 `outputs/check-oa-szoa-vs-hr.sh`（或等价窗口），列出 OA 有 HR 无的请假；补映射/sync 后重算
- [x] 9.4 把对账结果与未映射 `showvalue` 记在发布说明，不改 cron

## 10. 回归

- [x] 10.1 跑矩阵组装器、导出、查询页、请假识别、异常投影相关测试
- [x] 10.2 发布说明：OPEN 月须重新计算；关账月不自动重开；审批中只藏总览不改工时
