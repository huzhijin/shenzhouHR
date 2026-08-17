# Business Rules Alignment 2026-08

## Why

客户于 2026-08-16 对原文档 `docs/contracts/2026-08-06-reporting-business-confirmation.md` 中的 42 个业务确认项进行了正式决策，明确了 17 个核心业务规则（Q1-Q17）。本变更启动时的代码实现基于"暂行假设"，与客户最终决策存在多处不一致，包括核心的出勤率计算公式、旷班判定规则、加班分类等。这些差异会导致计算结果与业务预期不符，阻断生产上线。本次变更将代码实现与客户签字确认的业务规则对齐，确保系统满足实际业务需求。

## What Changes

### 🔴 P0 - 核心业务规则调整

1. **出勤率公式从分钟改为天数** - 客户选择公式 B（实际出勤天数 ÷ 应出勤天数），变更前代码使用分钟公式
2. **带薪假期处理方案** - 采用方案 A：带薪假期（含病假）算出勤且不从应出勤天数扣除，病假需单独在报表显示
3. **迟到≥30分钟算旷班** - 新增旷班判定规则：迟到超过 30 分钟不再计为迟到，而是计为旷班（不算出勤，罚全天工作分钟）
4. **每月1次补卡限制** - 新增补卡申请功能，每人每月只能提交 1 次补卡申请
5. **加班三分类统计** - 将加班分为计薪加班、转调休加班、义务加班三类，分别统计并汇总。OA 字段已验证：`formson_0172.field0096`

### 🟡 P1 - 考勤判定规则

6. **外出单据联合判定** - 外出必须同时有已批准单据 AND 打卡记录才算出勤；免打卡单据直接算出勤；出差不查单据
7. **缺卡次数确认** - 全天无打卡且无免打卡申请算 2 次缺卡（上下班各 1 次）
8. **免打卡结束日包含** - OA 免打卡申请的结束日期包含当天

### 🟢 P2 - 展示与监控

9. **得力同步简化** - 每小时同步 1 次，只显示同步记录（时间、记录数、状态），不需要告警阈值和红绿灯
10. **未月结报表标识** - 当月未月结可以查看报表，但标注"暂算/未月结"
11. **迟到显示优化** - 迟到用分钟显示，超过 60 分钟自动显示小时
12. **部门汇总按天数** - 部门出勤率 = SUM(实际出勤天数) ÷ SUM(应出勤天数)
13. **调岗月份拆行** - 调岗当月按发生时部门拆成两行显示

## Capabilities

### New Capabilities

- `attendance-calculation/attendance-rate-by-days`: 按天数计算出勤率的新公式和逻辑
- `attendance-calculation/absence-from-late`: 迟到超过阈值转为旷班的判定规则
- `attendance-evidence/punch-supplement`: 补卡申请功能，包括每月次数限制
- `attendance-calculation/overtime-classification`: 加班三分类（计薪/调休/义务）统计
- `attendance-evidence/exemption-and-outing`: 免打卡和外出单据的联合判定逻辑

### Modified Capabilities

- `attendance-calculation/leave-and-attendance`: 假别与出勤关系 - 所有带薪假（含病假）算出勤，病假需单独显示
- `attendance-calculation/missing-punch-count`: 缺卡次数计算 - 明确全天无卡算 2 次
- `attendance-reporting/department-aggregation`: 部门汇总规则 - 从分钟加权改为天数加权
- `attendance-reporting/job-transfer-allocation`: 调岗归属规则 - 明确按发生时部门拆行
- `attendance-sources/deli-sync-schedule`: 得力同步规则 - 简化为每小时同步，只记录不告警

## Impact

### 数据库变更
- **新增表**：
  - `punch_correction_request`（补卡申请与审批）
  - `oa_enum_mapping`（OA 物理枚举到业务语义的映射）
  - `deli_sync_log`（得力同步状态与水位）
- **新增字段**：
  - `attendance_report_daily_fact.leave_type`（日事实假别）
  - `oa_attendance_document.leave_type`（OA 假别证据）
  - `oa_attendance_document_context.overtime_type`（OA 加班分类）
  - `attendance_report_daily_fact.paid_overtime_minutes`（计薪加班分钟）
  - `attendance_report_daily_fact.compensatory_overtime_minutes`（转调休加班分钟）
  - `attendance_report_daily_fact.voluntary_overtime_minutes`（义务加班分钟）
  - `attendance_report_daily_fact.total_overtime_minutes`（汇总加班分钟）
- **兼容策略**：既有二分类加班字段不在 V48 中重命名或删除；新计算与报表使用上述三分类字段

### 代码变更
- **考勤计算域**：
  - 出勤率计算从 `rate(confirmedMinutes, scheduledMinutes)` 改为 `rateByDays(actualDays, scheduledDays)`
  - 新增旷班判定逻辑（迟到 ≥30 分钟）
  - 带薪假期出勤判定逻辑
  - 加班三分类逻辑（读取 OA `field0096` 并映射）
- **OA 单据处理**：
  - 新增外出单据查询和联合判定
  - 免打卡日期范围判定（包含结束日）
- **报表投影**：
  - 部门汇总从分钟加权改为天数加权
  - 新增病假天数单独显示列
  - 新增三类加班单独显示列
  - 调岗拆行查询逻辑

### API 变更
- **新增接口**：
  - `POST /api/v1/attendance/punch-corrections`（提交补卡申请，`/apply` 为兼容入口）
  - `GET /api/v1/attendance/punch-corrections/quota`（查询本月剩余补卡次数）
  - `PUT /api/v1/attendance/punch-corrections/{requestId}/approve`（HR 审批）
  - `/api/v1/attendance/punch-supplement` 与未版本化路径保留相同安全语义的兼容入口
- **响应变更**：
  - 报表接口新增 `sickLeaveDays`（病假天数）
  - 报表接口新增 `paidOvertimeMinutes`、`compensatoryOvertimeMinutes`、`voluntaryOvertimeMinutes`
  - 未月结报表响应增加 `status: "PRELIMINARY"`标识

### 测试影响
- **出勤率计算测试**：所有测试用例需要从分钟公式改为天数公式
- **旷班判定测试**：新增迟到 30 分钟阈值的测试用例
- **加班分类测试**：新增三类加班的映射和汇总测试
- **补卡限制测试**：新增每月 1 次限制的测试用例

### 发布与 OA 生产读取
- 新业务规则是当前制品中的唯一运行路径；不存在 `businessRules2026_08` 总开关，也不在生产请求中执行新旧公式双算。旧新公式对比只通过离线只读 SQL 生成验证证据。
- 宝塔升级采用部署前数据库/环境/旧制品备份、待发布 JAR 前向 Flyway 迁移和应用/前端制品切换。业务逻辑撤回依赖已验证的旧制品与恢复决策，不依赖未实现的 feature flag；已执行迁移不得自动逆向、`clean` 或无依据 `repair`。
- OA 生产库只使用独立 `SELECT` 账号和只读连接池。`OA_MYSQL_ENABLED` 与 `SHENZHOUHR_OA_AUTO_SYNC_ENABLED` 均默认 `false`；首次接入先开启读取器但保持自动同步关闭，经授权的单数据源手动同步和抽样对账通过后，才单独开启默认每 30 分钟的自动同步。

### 依赖
- ✅ **OA 字段已验证**：`formson_0172.field0096`（加班类型枚举 ID）已通过 `docs/verification/oa-live/2026-08-10/EVIDENCE.md` 验证
- ✅ **OA 状态映射已确认**：`col_summary.state` 映射已在 `~/.claude/memory/oa-approval-state-mapping.md` 确认
- ✅ **业务决策已签字**：`.umadev/explore/FINAL-BUSINESS-DECISIONS-SIGNOFF.md` 包含客户 17 个问题的最终决策

### 工作量评估
- P0 实现：5 个工作日（2 人并行）
- P1 实现：2 个工作日
- P2 实现：2 个工作日
- 回归测试：2 个工作日
- **总计**：11 个工作日（约 2.5 周）

### 风险
- 🔴 **高**：出勤率公式变更影响所有报表和历史数据对比
- 🟡 **中**：旷班规则变更可能影响员工考勤习惯
- 🟢 **低**：其他变更为新增功能或展示优化，风险可控
