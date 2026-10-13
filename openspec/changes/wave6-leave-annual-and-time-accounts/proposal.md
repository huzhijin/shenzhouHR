## Why

V1.9 已由 W2 提供任职周期与入职前累计工龄，并要求 W5 最终提供冻结/月结边界，但系统仍缺少可执行的假别、周年年假和不可变时间账户合同。W6 需要把“资格先判断、累计工龄再定档”和“余额只能由流水重放”固化为可独立测试的领域核心，同时把依赖 W5 FINAL 的持久化、关闭期保护与报表接线明确隔离，避免提前假定上游表或快照形状。

## What Changes

- 新增版本化假别策略合同，默认按工作日，配置计量单位、最小粒度、资格、材料、发放、有效期、结转、失效、销假/取消、冲突和余额不足行为。
- 新增周年年假领域模型：资格仅按最新任职周期的完整日历月判断，档位按“本次任职完整月数 + HR 维护的入职前累计月数”判断，严格覆盖 1/10/20 年边界。
- 新增以最新入职日为锚点的周年周期与 grant 计划，明确周年日前一日不发、周年日先失效旧周期再发新 grant、有效区间为 `[anniversary,next_anniversary)`，并覆盖再入职和 2 月 29 日策略。
- 新增不可变时间账户流水与确定性重放；余额只允许由期初、发放、返还、调整、使用、失效和冲正发生额汇总，冲正引用且精确反向原流水，原记录不删除。
- 新增工时/调休/年假期初余额 `.xlsx` 的模板、预检、差异、幂等发布、错误报告与冲正合同；期初值不得修改任职、工龄、资格或重复叠加切换日前发放。
- 新增余额、到期、流水和同版本汇总/明细报表合同；导出复用页面范围和字段白名单，W7 只消费 W6 的稳定查询投影与访问合同。
- 新增 W6 的 OpenAPI、权限、审计、MySQL、前端真实状态和回归验收计划，但只有在 W5 FINAL 的 period/close snapshot 合同可验证后才允许接线。
- 本轮先以 TDD 实现不依赖 W5 的纯 Java 领域核心；不创建迁移、不选择迁移号、不实现假持久化或临时 close snapshot。

## Capabilities

### New Capabilities

- `leave-policy-catalog`: 版本化假别目录、默认工作日口径、单位/粒度/资格/材料/有效期/冲突/取消和余额保护。
- `annual-leave-entitlement`: 周年年假资格、累计工龄档位、周年周期、grant/失效顺序、再入职、闰日、规则版本与冻结边界。
- `time-account-ledger`: 工时、调休、年假账户的不可变发生额、冲正、余额重放、来源/版本/有效期追溯和负余额保护。
- `time-account-opening-imports`: 期初余额模板、匹配、单位换算、预检、幂等发布、错误报告与批次冲正。
- `leave-time-account-reporting`: 余额、到期与流水的同版本汇总/明细查询、范围控制、字段最小化和受控导出。
- `wave6-verification`: W2/W5 前置证明、领域金标准、OpenAPI/权限/MySQL/UI/回归及不越界实施门。

### Modified Capabilities

无。W2 的任职/工龄和 W5 的期间/close snapshot 只作为只读上游合同；W6 不修改其既有要求，也不在本 change 内代建上游持久化。

## Impact

- 后端将新增 `leavetimeaccount` 模块，依赖方向保持 `interface → application → domain/port → infrastructure`；本轮只新增无 Spring/MyBatis/REST 依赖的 domain 代码与测试。
- 后续 OpenAPI 将在现有 `PLANNED_NON_CALLABLE` 边界下闭合 `/leave-types`、`/leave-requests`、`/leave-reconciliations`、`/time-accounts`、ledger/replay、期初导入和报表操作；W5 FINAL 前不得把这些操作声明为 callable。
- 后续 Flyway 迁移必须在同步 W5 FINAL 后读取真实最高版本再顺延，V1～当前所有迁移与 checksum 不变；本 proposal 不预占任何版本号。
- 后续持久化需只读接入 W2 的 employee/employment/prior-service 权威和 W5 的 period status/close snapshot/reopen version，不复制或猜测这些表。
- 前端最终新增假期与工时管理/查询状态，但员工自助和综合看板由 W7 消费；本轮不新增 route、demo 数据或静态页面。
- 全部测试仅使用合成员工、任职、账户和流水标识，不包含真实员工、假因、附件、位置或凭据。
