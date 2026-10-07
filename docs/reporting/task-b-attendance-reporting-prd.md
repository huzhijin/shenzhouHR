# Task B 产品定义：考勤核算与报表交付

> 依据 2026-08-07 用户需求（A–G / B1–B5）与仓库现状核实结果编写。
> 本文是产品定义与拆分依据，不是实施记录。

## 1. 交付对象与核心任务

| 角色 | 核心任务（job to be done） | 使用频率 |
|---|---|---|
| HR 考勤专员（主用户） | 月末把打卡数据和 OA 单据核对平，出九张报表交财务 | 每月集中 3–5 天 |
| 部门主管 | 查本部门异常、确认下属加班与请假 | 每周 |
| 员工 | 自助查本人考勤、发起异常反馈 | 偶发 |
| 系统管理员 | 配置多台考勤机凭据、监控同步任务 | 初装 + 故障时 |

主用户是 HR 考勤专员，产品成败取决于**月末那几天她能不能把账对平**。九张报表不是装饰，是她交付给财务的凭证。

## 2. Register：product

这是内部核算工作台，不是营销页。用户来**完成**月度核算，不是来欣赏界面。

- 字体：系统中性 UI 字体，固定 rem 阶梯（比例 1.125–1.2），字重仅 400/500/600
- 动效：无入场编排，动效仅确认用户操作（≤150ms）
- 颜色：语义信号（异常/选中/危险）而非装饰，表面 token 扁平
- 密度是优点：一屏容纳更多真实行，组内 8–12px，组间 24–48px
- 布局遵循惯例，不为"看起来有想法"破坏对称

现有 `frontend/src/features/reports/customerReports.css` 与 `@tabler/icons-react` 已确立单一图标库，沿用不改。

## 3. 现状核实结论（决定拆分方式）

已核实（读代码与 migration 得到，非推断）：

| 能力 | 状态 |
|---|---|
| Flyway migration | `backend/src/main/resources/db/migration/` 共 31 个（V1–V31），含 V8 证据、V9 打卡导入、V10 报表、V16/V30 出勤率、V19 OA 契约、V27/V28 得力 |
| 考勤核算域 | `DeterministicAttendanceCalculator`、`AttendanceExceptionReconciler`、`AttendancePeriodClosePolicy` 已实现 |
| 得力客户端 | `DeliEplusClient` / `DeliEplusSigner` / `JdkDeliEplusHttpTransport` 为真实实现 |
| OA 只读接入 | `OaMysqlOrgMemberDirectoryAdapter` + `OaReadOnlyConnectionPool` 为真实实现 |
| 报表后端 | `ReportType` 九值与前端九 tab 一一对应，`/api/v1/attendance-reports` 及 `/month-matrix`、`/exports` 均已就绪，`ReportField` 46 列覆盖九张报表所需字段 |
| 报表前端 | 九张 tab UI 全部完成，但 `CustomerReportCenterPage.tsx`（1579 行）**零 API 调用**，全部读 `customerReportDemo.ts`（992 行）硬编码数据 |
| 期初余额 | `docs/contracts/2026-08-06-customer-deployment-readiness.md:57` 记为 `NOT_READY`：无页面、无 REST、无预检发布、无正式台账 |

关键结论：**报表的后端已建好，缺的只是前端接线。** `reports/` 是唯一没有 `xxxApi.ts` 的 feature 目录，其余 feature（`punchImport`、`attendanceSources`）都已按 `demoMode ? demo : requestJson` 约定接通真实路由。

## 4. 拆分（五块，每块独立可验证）

按你「报表优先」的要求排序。每块可独立演示、独立验证，不互相阻塞。

### B-1 九张报表接通真实后端（最高优先）
纯前端改动，不动后端。新建 `reports/customerReportApi.ts`，按仓库既有约定 `demoMode ? demo : requestJson` 分流；把后端 `ReportRow.values`（`Map<String,String>`）映射到九个既有行类型；`attendance-detail` 日历矩阵走 `/month-matrix`；导出改走 `/api/v1/attendance-reports/exports`。
**验收**：九张 tab 在真实后端下各自返回真实行；loading / empty / error 三态齐全；`npm run check` 绿。

### B-2 得力多机凭据前台配置（管理员）
现状 `DeliEplusProperties` 是**进程级单套**凭据（`application.yml:79-89` 走环境变量）。需求是多台考勤机各自不同 key、且管理员可在前台配置。数据层已按 `attendance_source_id` 建模（V27/V28），改造范围可控：凭据下移到按源存储 + 加密静态存储 + 管理员配置页 + 连通性探测（`deli_source_connection_probe` 表已存在）。
**验收**：可新增/编辑多个考勤机源，各自独立 key；探测按源返回结果；凭据值不出现在响应、日志与仓库中。

### B-3 打卡 Excel 导入闭环
后端 `punchimport`（`PoiPunchWorkbookGateway`、V9 migration）与前端 `PunchImportsPage` / `PunchImportDetailPage` 已存在。本块是补全闭环：列映射、校验预检、失败行下载、导入结果入库并参与核算。
**验收**：真实 xlsx 上传后可见逐行校验结果，成功行进入本地库并被报表读到。

### B-4 期初数据录入
唯一从零开始的一块（`NOT_READY`）。期初余额（年假/调休/认可加班）录入页 + REST + Excel 预检发布 + 月度 snapshot。其余增减以此为基准。部门人员期初已由 `peopleImport` 覆盖。
**验收**：期初可录入并发布为不可变台账；报表余额从期初起算；确认为零也需书面签字，不以"缺数据"代替零。

### B-5 核算规则收口
早退（打卡晚于下班时间）、缺卡补签（OA 补签单 `formmain_0203`）、加班认定（按 OA 加班单枚举：义务加班/加班费/调休，且需与实际打卡时间比对而非以填报为准）、出勤率（应出勤天数 ÷ 实际出勤天数，带薪假计入出勤）。
**验收**：每条规则有可复核的用例；加班认定同时体现单据授权与打卡证据两侧。

## 5. 硬阻塞（影响 B-5 与 B-2，不影响 B-1）

`docs/contracts/oa-attendance-form-mapping-signoff-matrix.md` 把 OA 侧关键项全部标为 `NOT_VERIFIED`，且第 13 行明确要求：**标为 `NOT_VERIFIED` 的项目必须阻断有效考勤证据，不得使用猜测值继续**。

其中直接卡住加班认定的是：

- `OA-FK-01`：`formmain_0171`（加班单主表）与 `formson_0172`（明细）的真实关联列未确认，候选列名 `formmain_id` 明确不得使用
- `OA-ENUM-02` 类：加班类别 `field0096` 枚举未确认 —— 即你说的义务加班/加班费/调休三选项，表和字段已记录，但**原始值到三个选项的映射没有**
- `OA-STATUS-01/02`：审批状态列名与"最终批准"的确切取值未确认

所以你提供给 codex 的数据字典**已经在仓库里**（`docs/contracts/oa-attendance-form-mapping-signoff-matrix.md`，含 LEAVE `formmain_0170`、OVERTIME `formmain_0171`+`formson_0172`、补签 `formmain_0203` 等表字段），不需要重新提供。缺的是**枚举原始值与主从关联列**这两类，需要从真实 OA 库取脱敏样本才能确认。

B-1 完全不受此阻塞：它读的是本系统自己的报表投影，不直接读 OA 表。

## 6. 非功能红线

- 凭据（得力 key/secret、OA 连接）只以变量名与 available/missing 状态记录，值不入仓库、不入日志、不入响应
- OA 侧一律只读，禁止写回
- 报表口径变更必须带 `formulaVersion`，历史投影不可静默改写
- 每个交互元素备 hover / focus-visible / active / disabled；每个异步面备 loading / empty / error
