## Why

现场考勤报表中心导出当前报表失败；补签 8:30 被标迟到；周末午前下班被写成漏刷；每日加班在查询菜单里看不见；异常总览在 OA 已有请假/补签后仍挂着漏刷。人事无法按现网单据对账，只能手工改表。

## What Changes

- 报表中心「导出当前报表」必须能下载当前筛选下的可见报表（xlsx）。下载校验不得因 Content-Type 带 charset 丢掉文件；服务端导出失败时回退到页面已有的本地工作簿。
- 补签格子只显示补签标志（`补签HH:mm`）。补签 8:30 不标迟到。普通打卡 8:30 仍迟到。
- 登录菜单不再下发「纸质加班单」。路由与权限保留，直链不 404。
- 查询报表露出「每日加班查询」（及同批被白名单挡掉的加班日报、考勤日报、补签、请假汇总）。每日加班与报表中心每日加班表在节假日加班后增加「加班费」「转调休」两列。日历按加班类别分色。筛选接到类别与日期。
- 哺乳假：按单据开始时刻起固定 1 小时，每个工作日重复；周末/节假日不算。格子只涂该小时所在班段。
- 调休做成与每日加班同结构的人×日统计表（查询报表单独一项）。
- 首末卡不是同一张时，末卡即为下班，即使在当天 12:00 前。有加班单涂加班色，不得改写成下班漏刷。
- 异常总览：能盖住该类异常的 OA 单（请假/调休/补签/外出/出差/免打卡；休息日加班单只盖误标的下班漏刷）已入库即不显示，含审批中。批准并重算后异常事实不再生成。销假/驳回后可以再出现。
- 上海缺卡与 OA 请假遗漏：用现有导入和对账脚本查出缺口并补入；批准后的月份须重算。不改得力 cron 时刻表。

## Capabilities

### New Capabilities

- `customer-report-center-export`: 报表中心按当前筛选导出可见报表，下载合同与失败回退。
- `makeup-punch-not-late`: POINT 补签进入月矩阵；有补签则该侧不标迟到；普通 8:30 打卡仍迟到。
- `hide-paper-overtime-nav`: 纸质加班单不出现在登录菜单。
- `daily-overtime-query-report`: 查询菜单露出每日加班；加班费/转调休列；日历按类别分色；细筛。
- `breastfeeding-daily-fixed-hour`: 哺乳假按开始时刻+1 小时、每个工作日重复。
- `daily-time-off-report`: 每人每日调休查询表，结构对齐每日加班。
- `off-duty-punch-before-noon`: 首末卡不同则显示下班时刻，午前下班不得写成漏刷。
- `exception-overview-oa-coverage`: 覆盖单（含审批中已入库）从异常总览隐藏；批准后重算清事实。
- `attendance-source-gap-close`: 上海打卡导入与 OA 请假对账，补缺口后重算。

### Modified Capabilities

- （`openspec/specs/` 尚未归档主规格。下列行为覆盖既有 delta：`exception-dedupe-and-leave-suppression` 扩大到补签与审批中；`rest-day-overtime-display` 必须显示午前下班卡；`query-report-export` 补报表中心路径。）

## Impact

- 前端：`CustomerReportCenterPage` / `customerReportApi` / `wave7Gateway` 导出；`routeAuthorization` 查询白名单与纸质加班菜单；`QueryReportsPage` 每日加班/调休列与筛选、日历色；`customerReportWorkbook` 加班列。
- 后端：`AttendanceMonthMatrixAssembler` 取卡、补签 POINT、迟到；`AttendanceReportFactProjector` 与查询 `hideApprovedOaCoveredExceptions`；`LeaveHoursRecognizer` / `OaDocumentConverter` 哺乳假按日切片；日事实 `paid`/`compensatory` 加班分钟露出；登录 `AuthenticationController` 菜单。
- 数据：OPEN 月须重新计算后，补签/哺乳假/异常覆盖/午前下班口径才进 pin。关账月不自动重开。
- 运维：上海公司打卡导入、`outputs/check-oa-szoa-vs-hr.sh` 请假对账。不改得力/OA 同步 cron、不改工资引擎、不改 OA 表单填写。
