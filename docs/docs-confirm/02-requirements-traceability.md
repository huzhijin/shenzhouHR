# 需求追踪矩阵

> 来源：PRD V1.7 的 FR/AC 条目和当前明确确认项。API 为待冻结的契约族，不表示接口已经实现。

## 1. PRD 功能组追踪

| PRD 功能组 | 范围 | 架构模块 | 页面/终端 | API 契约族 | 验收引用 |
|---|---|---|---|---|---|
| FR-01 | 组织、人员、来源绑定、任职历史 | `MasterData`、`IdentityBinding`、`OrganizationVersioning` | 组织架构、人员冲突、同步影响预览 | `/org-units`、`/employees`、`/source-bindings`、`/organization-syncs` | AC-D01–D11、AC-O01–O25 |
| FR-02 | 致远 OA 标准对象与自建单据只读接入 | `SeeyonIntegration`、`OAQueryMapping` | 同步任务、原始数据、映射配置 | `/integration-profiles`、`/sync-jobs/seeyon`、`/raw-records` | AC-C、AC-S01–S13 |
| FR-03 | 得力 E+ 打卡接入 | `DeliIntegration`、`SyncControl` | 同步任务、原始打卡、重跑 | `/sync-jobs/deli`、`/checkins/raw`、`/sync-watermarks` | AC-C、AC-S01–S13、AC-A01–A17 |
| FR-04 | 标准化、班次与规则配置 | `AttendanceNormalization`、`RuleCatalog`、`Scheduling` | 班次、地区/个人规则、版本对比 | `/attendance-rules`、`/shifts`、`/work-schedules` | AC-R、AC-A01–A17 |
| FR-05 | 日考勤计算与异常 | `DailyAttendance`、`EvidenceTrace`、`ExceptionCase` | 考勤工作台、日明细、异常处理 | `/attendance-days`、`/attendance-calculations`、`/attendance-exceptions` | AC-A01–A17、AC-W01–W12 |
| FR-06 | 请假/加班/外出/出差/补卡及反馈 | `AttendanceFact`、`Feedback` | OA 证据层、异常反馈、移动进度 | `/attendance-facts`、`/feedback-cases`、`/reissue-evaluations` | AC-L、AC-A01–A17、AC-M01–M12 |
| FR-07 | 地图、日报/月报、汇总穿透 | `LocationEvidence`、`Reporting` | 地图抽屉、明细/汇总报表、大屏 | `/checkin-locations`、`/attendance-reports`、`/screen/attendance` | AC-L、AC-U01–U11、AC-P01–P14 |
| FR-08 | 考勤月结、重开、重算 | `AttendanceClosing` | 月结范围、阻断、记录和差异 | `/attendance-closures`、`/reopen`、`/recalculate` | AC-A01–A17、AC-W01–W12 |
| FR-09 | 年假余额、制度和自助 | `LeaveBalance`、`PolicyContent` | 年假余额、制度、个人月报 | `/leave-balances/me`、`/policies`、`/attendance-reports/me` | AC-L、AC-M01–M12 |
| FR-10 | 工资采集、项目、方案、分段与成本 | `PayrollInput`、`PayrollCatalog`、`PayrollCalculation` | 工资采集、校验、明细、分段追溯 | `/payroll-input-batches`、`/payroll-items`、`/payroll-plans`、`/payroll-runs` | AC-PAY01–PAY31 |
| FR-11 | 社保、公积金、个税、复核、发布、工资条 | `StatutoryCalculation`、`PayrollPublication`、`Payslip` | 待复核、发布、补发补扣、工资条 | `/payroll-runs/{id}/review`、`/publish`、`/adjustments`、`/payslips/me` | AC-PAY01–PAY31、AC-PSEC01–PSEC21 |
| FR-12 | 权限、审计、配置、监控和扩展 | `Authorization`、`Audit`、`Operations`、`Configuration` | 权限管理、任务、日志、监控告警 | `/permission-policies`、`/audit-events`、`/jobs`、`/health` | AC-SEC、AC-PSEC01–PSEC21、AC-W01–W12 |

## 2. 当前明确 26 个模块覆盖

| # | 业务模块 | 对应功能组 | 主页面 | 关键验收证据 |
|---:|---|---|---|---|
| 1 | 组织与人员主数据 | FR-01 | 组织架构、人员 | 19 位 ID JSON 往返不变 |
| 2 | 组织版本和任职历史 | FR-01 | 版本时间轴、变更对比 | 调动日历史查询归属正确 |
| 3 | 致远 OA 数据接入 | FR-02 | 映射配置、任务详情 | 数据源账号仅有只读权限 |
| 4 | 得力 E+ 数据接入 | FR-03 | 同步任务 | 同页重放不新增打卡 |
| 5 | 同步任务中心 | FR-02/03/12 | 任务中心 | 失败页不推进水位，可人工重跑 |
| 6 | 原始数据管理 | FR-02/03 | 原始数据 | 批次、来源 ID、摘要/哈希可查 |
| 7 | 考勤标准化 | FR-04 | 考勤证据层 | 原始记录可追到标准化记录 |
| 8 | 班次和规则配置 | FR-04 | 规则/班次 | 新版本不改写历史计算 |
| 9 | 日考勤计算 | FR-05 | 日明细 | 输入与规则版本冻结可复算 |
| 10 | 异常识别 | FR-05 | 异常清单 | 原因码与证据完整 |
| 11 | 异常反馈互动 | FR-06 | PC/移动反馈 | 状态、进度、结果可见且可审计 |
| 12 | OA 假勤与补卡关联 | FR-06 | OA 证据层 | 重叠区间和审批状态参与计算 |
| 13 | 打卡位置地图 | FR-07 | 地图抽屉 | 未确认坐标不渲染定位点 |
| 14 | 报表 | FR-07 | 日/月/明细/汇总 | 聚合可穿透且范围复算 |
| 15 | 月结 | FR-08 | 月结 | 未决阻断时关闭请求失败 |
| 16 | 年假与制度 | FR-09 | 自助 | 余额计算版本与来源可追溯 |
| 17 | 工资采集 | FR-10 | 采集/校验 | Excel 逐行错误且整体原子策略明确 |
| 18 | 工资项目和方案 | FR-10 | 项目/方案 | 版本有效期不重叠 |
| 19 | 工资分段 | FR-10 | 分段明细 | 月中调动至少生成两段且边界无重叠 |
| 20 | 工资成本分摊 | FR-10 | 成本构成 | 分段成本合计等于员工期内成本 |
| 21 | 社保公积金个税 | FR-11 | 公式追溯 | 使用冻结政策版本 |
| 22 | 工资发布和工资条 | FR-11 | 发布/工资条 | 已发布不可改写；查看留审计 |
| 23 | 权限与安全审计 | FR-12 | 权限、审计 | 员工/主管/系统管理员越权统一拒绝 |
| 24 | PC、移动和大屏 | FR-05–12 | 三类终端 | 指定视口无严重布局问题 |
| 25 | 配置任务日志监控告警 | FR-12 | 系统管理 | 失败任务告警含关联 ID、无敏感值 |
| 26 | 后续扩展接口 | FR-12 | 无独立 UI | 模块公开端口不泄漏外部字段 |

## 3. 横切需求追踪

| 约束 | 架构落点 | API/数据约束 | 测试目录 |
|---|---|---|---|
| 服务端权限全覆盖 | `PolicyEnforcementPoint` 位于每个应用服务入口与导出/附件/消息通道 | 统一对象不存在/无权响应；字段白名单序列化 | AUTH-01–12 |
| 工资默认拒绝 | 独立 `PAYROLL` 域、权限管理员与业务查看职责分离 | 无工资权限时响应体不存在金额字段 | PAYSEC-01–09 |
| 历史不可覆盖 | 有效期模型、快照和追加式调整 | `effective_from`/`effective_to`，发布后只增调整 | DATA-01–08、PAY-01–08 |
| 外部可靠性 | Inbox、幂等键、精确水位、退避重试、补偿任务 | `Idempotency-Key`、任务版本、失败页重跑 | SYNC-01–10 |
| 坐标可信度 | 原始坐标/坐标系/校验/转换四字段分离 | 未验证返回状态而非伪造点 | LOC-01–05 |
| 品牌和可访问性 | `BrandConfig`、语义 Token、Tabler Icons 单一图标库 | Logo 本地化前不进入生产 | UI-01–12 |
| 可恢复性 | PITR、跨故障域备份、季度演练 | RPO≤15 分钟、RTO≤4 小时 | OPS-01–08 |

## 4. 追踪完整性规则

实现阶段每个变更必须引用至少一个 `FR-*` 和一个 `AC-*`/本目录测试 ID；每个 OpenAPI operation 必须声明权限域、数据范围、字段级响应和审计事件；每个测试结果必须可反查到规则版本、输入快照或接口样本。任何新增路由、表、依赖或源文件都需在后续确认后的执行计划中显式声明。
