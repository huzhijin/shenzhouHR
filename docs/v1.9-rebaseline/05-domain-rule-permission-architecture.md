# V1.9 统一领域、规则与权限架构

## 1. 架构决策

继续采用现有 Java 21 / Spring Boot / MyBatis 模块化单体和 React feature 分层。V1.9 的复杂性来自版本、证据、时间区间、权限和冻结历史，不来自独立部署需求；本轮不引入微服务、消息队列、Redis 或任意脚本规则。

依赖方向保持：

`interface/controller → application/use case → domain/port → infrastructure adapter`

外部系统、Excel、数据库、文件存储和时间服务均通过端口进入；领域模型不得依赖厂商 DTO、Excel 行对象或 Controller DTO。

## 2. 限界上下文

| 上下文 | 责任 | 关键对象 | 不负责 |
|---|---|---|---|
| IdentityAccess | 本地账号、密码、session、角色、能力和范围 | Account、Credential、PasswordPolicy、Session、RoleAssignment、Capability | 组织业务规则、考勤计算 |
| PeopleOrganization | 法人、组织、岗位、员工、任职周期和工龄 | OrganizationVersion、Employee、EmploymentPeriod、PriorService | OA 持续组织同步 |
| ImportManagement | 模板、上传、预检、差异、发布、作废/冲正 | ImportTemplate、ImportBatch、ImportRow、ValidationIssue | 直接覆盖业务结果 |
| PolicyManagement | 类型化规则、版本、作用范围、试算和发布 | PolicyTemplate、PolicyVersion、PolicyScope、PolicyAssignment、Snapshot | 执行任意脚本 |
| AttendanceSetup | 考勤组、班次版本、日历、地点和策略关联 | AttendanceGroup、ShiftVersion、WorkCalendar、Location | 日结果计算 |
| EvidenceIngestion | 原始来源、标准化、匹配、规范事件和证据 | SourceBatch、RawFact、NormalizedRecord、MatchDecision、EffectiveEvent、EvidenceLink | 直接判断迟到/旷工 |
| AttendanceCalculation | 工作段、日结果、异常、调整和重算 | ScheduledSegment、DailyResult、Exception、Adjustment、CalculationVersion | 改写原始事实 |
| PeriodClose | 预结、冻结、月结、重开和快照 | AttendancePeriod、Freeze、CloseSnapshot、ReopenRecord | 静默改写已关闭结果 |
| LeaveTimeAccount | 假别、周年年假、时间账户和不可变流水 | LeavePolicyVersion、AnnualLeaveRule、TimeAccount、LedgerEntry、Grant | 可覆盖余额字段 |
| AuditOperations | 审计、防篡改链、任务和运行状态 | AuditEvent、OperationJob、RequestCorrelation | 获取业务越权明细 |
| PayrollReserved | 只消费冻结考勤快照的 P0-B 后端边界 | PayrollPeriodRef、PayrollSnapshotRef、PayrollRun | 前端发现、工资条、银行文件 |

## 3. 核心领域模型

### 3.1 身份与账号

- `Account`：用户名、员工/服务主体绑定、状态、首次改密、密码过期。
- `Credential`：仅保存现代自适应密码哈希、算法和参数版本；不保存明文或可逆密文。
- `LoginFailureWindow`：失败计数、窗口、锁定至；成功登录原子清零。
- `Session`：随机不透明 token 的服务端摘要、创建/最近活动/绝对到期/撤销时间和原因。
- `PasswordResetGrant`：一次性、短时、摘要存储；使用后失效。
- 离职、停用、改密、重置、管理员强制退出和权限撤销必须使相关 session 失效并写审计。

### 3.2 组织、员工与任职

- 期初导入发布生成本地组织版本和员工/任职记录，来源文件与行可追溯。
- `EmploymentPeriod` 全仓统一使用半开区间 `[start_date, end_exclusive)`；员工离职日仍属任职期，写入时把业务离职日转换为 `end_exclusive = termination_date + 1 day`。查询、导入匹配、组织归属和工龄均使用同一规则。
- 二次入职新建任职周期；旧周期不可覆盖。
- `PriorService` 是 HR 维护的入职前累计工龄发生额/快照，维护、导入、原因和操作者均审计。
- 任意时点匹配员工时必须先定位 employee，再解析当时有效的 employment period。

### 3.3 统一规则

```text
PolicyTemplate
  └─ PolicyVersion (DRAFT → VALIDATED → PUBLISHED → INACTIVE)
       ├─ TypedParameter[]
       ├─ PolicyScope[]
       ├─ EffectivePeriod
       ├─ Priority
       ├─ ValidationResult
       └─ ImmutableSnapshot
PolicyAssignment
  └─ company/location/attendance-group/policy-group + effective period
```

规则参数只允许受控类型：enum、boolean、integer、decimal、duration、time window、calendar basis、threshold、scope reference。禁止用户脚本、表达式注入和将默认值散落到计算分支。

发布前必须执行：

1. schema/枚举/范围校验；
2. 生效期和作用范围冲突检测；
3. 优先级确定性检查；
4. 影响员工、日期、冻结期间预览；
5. 合成样例试算；
6. 变更原因与审计确认。

已发布版本不可原位编辑；修改产生新版本。回滚不是删除，而是发布一个指向上个可用快照的新版本。已冻结期间继续引用当时快照。

### 3.4 规则解析顺序

1. 解析员工在业务时点的公司、地点、考勤组和政策组；
2. 筛选命中作用范围且生效的已发布版本；
3. 先按 scope specificity，再按显式 priority，再按 version id 稳定排序；
4. 同优先级且参数冲突则阻断发布或计算，不静默任选；
5. 个人特殊情况通过有期限专用组表达，不提供隐藏 `employee_id` 特例。

### 3.5 考勤事实与证据

```text
RawFact (append-only)
  → NormalizedRecord
  → MatchDecision (employee + employment period)
  → EffectiveEvent (canonical)
  → ScheduledSegment + EvidenceLink[]
  → CalculationVersion
  → DailyResult / Exception
```

- 得力 API、设备 Excel 和 OA 业务单据共享 RawFact 与 EvidenceLink。
- 来源撤销生成新的撤销事实；不删除旧事实。
- 补签、人工调整只新增证据/调整版本，不伪造设备打卡。
- 重叠单据先按 `[start,end)` 边界切分为不重叠时段，再固定按“已批准 HR 裁定/调整 → 已通过销假/撤销/冲销 → 已通过请假/调休/外出/出差/免打卡/补签/加班 → 原始打卡 → 系统推导异常”逐段计算。同级互斥单据重叠进入 `EVIDENCE_CONFLICT`，不得按最后写入覆盖。

### 3.6 时间账户

余额只允许由流水汇总：

`余额 = 期初 + 发放 + 返还 + 调整 - 使用 - 失效 ± 冲正`

`LedgerEntry` 至少包含 account、source、amount、unit、business_date、effective/expiry、freeze/version、reversal_of、request_id。禁止 UPDATE 覆盖最终余额作为业务事实。

## 4. V1.9 默认规则快照

| 规则 | 默认值 | 可配置项 |
|---|---|---|
| 晚餐扣除 | 所有考勤组启用 | 开关、窗口、时长、触发、工作日/周末/节假日 |
| 迟到宽限 | 自然月 1 次；`0 < late <= 15 min`；跨组不重置 | 次数、阈值、周期、范围、跨组、命中顺序 |
| 单边缺卡 | 7 个自然日内待补正；逾期仅对应工作段旷工 | 期限、口径、逾期结果、范围、管理员修正 |
| 加班餐扣 | 所有班组适用 | 工作日/周末/节假日窗口与时长 |
| 假别计算 | 工作日 | 单位、最小粒度、资格、材料、发放、结转、失效、销假 |
| 年假资格 | 最新任职周期本公司连续 12 完整日历月 | 开关、月份、闰日口径 |
| 年假档位 | 累计工龄 `[1,10)=5d/40h`、`[10,20)=10d/80h`、`[20,+)=15d/120h` | 档位版本 |
| 年假发放 | 最新入职周年日；到下一周年日前一天 | 跨档生效、离职/再入职、规则切换 |

### 4.1 年假日期与版本默认口径

- 周年以 `LocalDate` 日历比较，不用天数除以 365；2 月 29 日入职者在非闰年的默认周年为 2 月 28 日，同一策略版本可整体改为 3 月 1 日。
- 周年日前一日无新资格/发放；周年日 00:00（企业时区）先失效上周期剩余量，再按当日累计工龄档位发放；有效区间为 `[anniversary, next_anniversary)`。
- 离职日仍可使用已获余额；从 `end_exclusive` 起禁止新申请，默认生成“离职失效”流水清零当前任职周期剩余年假，不自动折现。制度要求结算时只能通过已发布离职策略和独立流水处理。
- 二次入职新建任职周期并重置资格/周年；旧任职的 grant、使用、失效流水和快照不复活、不覆盖。历史工龄只有经 HR 作为 `PriorService` 维护后才参与新周期档位。
- 已产生 grant 始终引用发放时的策略版本；周期中途切换规则不补发/追扣，下一周年读取周年日生效版本。若周年业务日已冻结或月结，自动任务不得补写，必须重开后以新版本生成 grant、差异和审计。

## 5. 权限模型

授权决策统一为：

`主体 × capability × 业务动作 × 数据范围 × 字段策略 × 记录状态 × 会话条件`

### 5.1 权限域

- `IDENTITY`：账号创建、重置、锁定/解锁、强制退出。
- `PEOPLE`：组织/员工导入、本地维护、任职、工龄。
- `POLICY`：草稿、校验、试算、发布、停用、回滚。
- `ATTENDANCE`：来源、打卡、计算、异常、重算、月结。
- `LEAVE`：假别、年假、时间账户。
- `LOCATION`：精确位置查看，独立于考勤明细。
- `AUDIT`：审计查询与导出。
- `OPERATIONS`：任务、健康和配置，不自动获得业务明细。
- `PAYROLL`：独立域、默认拒绝，不被系统管理员或普通 HR 自动继承。

### 5.2 离线考勤导入动作

分别授权：

`TEMPLATE_DOWNLOAD`、`UPLOAD`、`PRECHECK`、`PUBLISH`、`PARTIAL_PUBLISH`、`VOID_OR_REVERSE`、`RAW_FILE_READ`、`RAW_ROW_READ`、`ERROR_REPORT_DOWNLOAD`、`RECALCULATE`。

每个动作再受公司/地点/考勤组范围和期间状态限制。系统管理员默认不能凭技术角色读取原始考勤文件。

### 5.3 服务端不变量

- 员工本人对象从 session 推导，不信任客户端 employee number。
- 前端隐藏不是授权。
- 无读取权限时按资源暴露策略返回 404；认证失败 401；已认证但动作禁止 403；状态冲突 409。
- 高风险动作二次确认不能替代服务端重验 capability、scope、record version 和 period status。
- 所有敏感查看、导出、规则发布、批次发布/作废、调整、月结/重开和权限变更写审计。

## 6. 冻结与历史保护

- 规则、组织、任职、事实、计算和余额都保存版本/快照引用。
- 冻结或已月结期间允许上传/预检新事实，但默认禁止发布和重算。
- 重开生成新的 period version；重算生成新的 calculation version 和差异，不覆盖旧结果。
- PayrollReserved 只能读取明确关闭的 attendance close snapshot id。

## 7. 后续详细设计门

本文件锁定领域边界和不变量，不代表 API、表或源码已实现。每一波在 `docs_confirm` 人工通过后，先冻结该波 OpenAPI 与 V3+ 迁移，再写后端和前端。
