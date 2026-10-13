## Context

W6 位于 V1.9 依赖图的 W2 与 W5 之后。当前同步基线 `0c09fc375b1d2973915234e50a9aac10900446ca` 已包含 W2 的本地员工、半开任职周期和可重放入职前工龄，也包含 W3 的规则/日历基础；W5 FINAL 的计算版本、期间状态、close snapshot、reopen version 和冻结写保护尚不能作为本 worktree 的已知持久化合同。

PRD V1.9 8.1～8.5、12.1 要求：

- 假别使用已发布版本，默认按工作日；
- 年假先按最新任职周期判断本公司资格，再按累计工龄判断 5/10/15 天档位；
- 周年日先失效旧周期再发新 grant，有效区间为 `[anniversary,next_anniversary)`；
- 再入职更换周年锚点，2 月 29 日默认映射非闰年 2 月 28 日；
- 余额只由不可变流水重放，纠错通过引用原记录的冲正；
- 期初余额通过 Excel 预检/发布，余额报表与同版本明细一致。

当前 W2 对外和持久化的入职前工龄单位是 `amountDays/totalDays`，而 PRD 年假档位输入写作“既往累计工龄月数”。没有服务起算日时，天数不能无损转换为完整日历月。因此领域核心接受已裁决的 `priorServiceMonths`，后续 wiring 必须用显式版本化换算合同解决该单位差异，不能在 W6 中用 `days/30` 或 `days/365` 猜测。

## Goals / Non-Goals

**Goals:**

- 交付可单独编译、无框架依赖且可用固定日期完全复验的年假和时间账户领域核心。
- 使资格、档位、周年日期和流水重放成为彼此独立且可组合的决策。
- 完整定义假别、年假、期初余额、报表、权限、审计、迁移和 W5 接线的最终 W6 合同。
- 所有余额变化只追加事实；任职、规则和 close snapshot 均以 ID/版本引用而非复制。
- 使用具名金标准覆盖周年前日/当日、到期日、10/20 年、再入职、2 月 29 日、冲正与负余额。

**Non-Goals:**

- 本轮不创建或修改 Flyway 文件，不选择迁移版本号。
- W5 FINAL 前不实现 Mapper、Repository、Controller、真实 API、期间保护适配器、报表投影或 React route。
- 不伪造 W5 close snapshot、冻结状态、重开版本或计算结果。
- 不修改 W2 的既有表、OpenAPI 或工龄语义，不自行裁决 days→months 业务换算。
- 不实现 W7 员工自助、综合看板或浏览器验收，也不处理真实员工、假因、附件或凭据。

## Decisions

### 1. 以依赖门分两阶段实施

阶段 A 只实现 `leavetimeaccount.domain` 和纯单元测试；阶段 B 必须在同步 W5 FINAL 后才能实现迁移、应用端口、持久化、API、UI 和报表。阶段 B 开始前需记录 W5 commit/manifest、最高迁移版本、期间状态枚举、close snapshot ID/version、reopen 语义和写保护调用方式。

选择显式依赖门，而不是临时建表或内存 provider，是为了让当前可独立验证的领域规则前进，同时避免 W5 合并后出现双份 period/close 权威。

### 2. 用 `LocalDate` 和完整日历月表达司龄

本公司完整月数以最新任职 `startDate` 到 `asOfDate` 的日历锚点计算：先计算候选月份差，再以 `startDate + candidateMonths` 是否晚于 `asOfDate` 校正；禁止用天数除以 30/365。未来日期、负的入职前月数或无效策略立即返回领域校验错误。

2 月 29 日的月/年锚点由同一策略版本的 `FEBRUARY_28`（默认）或 `MARCH_1` 决定。选择显式策略而不是依赖 JDK 隐式截断，是为了同时支持默认值和可配置替代值。

### 3. 资格与档位是两个独立结果

`AnnualLeaveQualification` 只读取最新任职完整月数和门槛开关/月数；`AnnualLeaveTier` 只读取 `currentEmploymentCompletedMonths + priorServiceMonths`。组合器只有在资格为真且档位不是 `NONE` 时才返回额度。

默认档位是 `[12,120)=40h/5d`、`[120,240)=80h/10d`、`[240,+)=120h/15d`。区间以月表示，精确覆盖 1/10/20 年；策略允许未来发布新档位，但已产生 grant 始终引用发放时的策略版本。

选择两个值对象而不是一个 if/else，是为了证明“本公司 6 个月 + 外部 15 年仍无资格”和“再入职满 1 年后可按累计工龄进入 80h 档”不会互相污染。

### 4. 周年周期只锚定最新任职周期

周年服务输入 `employmentPeriodId + latestStartDate + policyVersion`，输出资格取得日、给定周年的 `validFrom`、`validUntilExclusive` 和 `expiresOn=validUntilExclusive-1 day`。给定业务日只有等于一个有效周年时才产生 grant 计划；周年日前一日没有新资格/发放。

再入职必须提供新的 employment period 和 start date，因此自然生成新周年。旧 period 的 grant/使用/失效流水仍按旧 `employmentPeriodId` 可查，绝不迁移到新账户周期或复活。

### 5. 周年日事件顺序是领域合同

周年计划按确定顺序返回：

1. 对上一周期剩余量追加 `EXPIRY`；
2. 对新周期追加 `GRANT`。

grant 的有效区间为 `[anniversary,nextAnniversary)`。周年业务日如果 W5 provider 返回 `FROZEN/CLOSED/UNKNOWN`，应用层不得调用追加端口；重开后以新的 period version 重新决策并保存差异/审计。纯领域层只生成计划，不假定计划已持久化。

### 6. 假别策略复用 W1 版本治理但保持类型化

假别策略保存 calendar basis、单位、最小值/步长、资格、材料、grant、validity、carry/expiry、cancel/reconciliation、conflict 和 insufficient-balance 参数。默认 `WORKDAY` 来自已发布版本；未知字段、脚本和表达式拒绝。已发布版本不可原位修改，冻结结果引用旧快照。

选择类型化快照而不是 JSON 脚本，是为了遵守 V1.9 统一规则安全边界，并允许计算、申请和报表引用完全相同的参数版本。

### 7. 流水发生额使用有符号小时且按类型校验

所有时间账户内部单位固定为小时，使用 `BigDecimal`，最多两位小数且禁止二进制浮点。`OPENING/GRANT/OVERTIME_CREDIT/RETURN` 必须为正；`USE/EXPIRY` 必须为负；`ADJUSTMENT/REVERSAL` 可正可负但不能为零。

每条不可变 `LedgerEntry` 至少保存 account、employment period、entry type、signed amount、source type/id、business date、effective/expiry、policy version、period/close version、reversal target、request ID 和严格递增 sequence。到期日自身不会隐式改余额；只有明确 `EXPIRY` 流水会减少余额。

### 8. 冲正精确反向且只追加一次

冲正工厂从目标流水复制 account、employment period 和单位，生成金额恰好为原发生额相反数的新 `REVERSAL`，并要求新的 entry ID、source/request、business date 和 `reversalOfEntryId`。重放拒绝缺失目标、跨账户目标、冲正另一条冲正、同一目标重复冲正、重复 entry ID/sequence 和不精确反向。

选择“目标引用 + 精确反向”而不是可编辑余额，是为了让任一知识时点可重放，并使批次作废、销假返还和人工纠错共享一致的审计语义。

### 9. 重放以 sequence 为事实顺序并保护负余额

重放先按 sequence 升序，逐条验证和求和；同序列或同 ID 阻断。对受余额控制的年假/调休账户，任一前缀余额小于零即失败；无余额控制的假别不创建时间账户。结果返回最终余额、分类型合计和最后 sequence，供后续报表投影复用。

使用事实 sequence 而不是业务日期排序，是因为补录/冲正可能具有回溯业务日期，但发生顺序和当时余额仍须确定。

### 10. W2 通过只读端口接入，单位差异必须先裁决

后续应用层定义：

- `EmploymentPeriodSnapshotPort`：返回业务日有效的唯一 employee/employment period、`startDate/endExclusive` 和版本；
- `PriorServiceResolutionPort`：返回已审计的累计值、原始单位、换算规则版本和最终 `priorServiceMonths`。

W2 当前只保证 `totalDays`，所以 `PriorServiceResolutionPort` 在换算规则未发布前必须 fail closed。禁止把旧任职本公司天数自动加入新任职；只有 HR 已作为 prior service 维护的历史值才能参与新档位。

### 11. W5 通过 fail-closed 期间端口接入

后续应用层定义：

- `PeriodProtectionPort`：`OPEN/REOPENED/FROZEN/CLOSED/UNKNOWN`、period version、close snapshot ID；
- `LeaveRecalculationIntentPort`：受销假、失效、期初发布/冲正影响的 employee/date/account 范围；
- `CloseSnapshotReferencePort`：报表和流水引用的 immutable snapshot/version。

任何 provider 缺失、返回 `UNKNOWN` 或版本变化都禁止正式 append/publish/reconcile。W6 不读取 W5 内部 Mapper，也不复制 W5 表。

### 12. 期初余额使用预检摘要和发布幂等

期初模板字段固定为 `batch_no, employee_no, employee_name, account_type, opening_hours, balance_as_of, valid_until, source_remark`。姓名只核对；员工编号和有效任职由服务端解析。发布幂等键为 `batch_no + employee_no + account_type + balance_as_of`，相同 key/digest 重放原结果，不同 digest 返回冲突。

每个成功行只追加一条 `OPENING`。批次未被消费时可以整批生成冲正；已被消费时仍只能逐条引用原 opening 生成反向流水，不删除或回写。期初值不得修改任职、工龄或资格。

### 13. 报表只读同一投影版本

余额摘要、到期风险和流水明细都由 ledger replay/close snapshot 投影生成，并携带 projection/period/close version。汇总下钻必须使用同一版本；员工只读本人，管理者同时满足 capability、scope 和字段白名单。敏感假因和附件不进入普通报表。

W6 交付管理端账户/流水/到期查询与导出后端合同；W7 消费它实现员工自助、综合看板和完整响应式交互。`<=50,000` 行同步、超过阈值异步，创建和下载均重新鉴权。

### 14. 迁移号延后到 W5 FINAL 同步后选择

设计只定义逻辑对象：leave type/revision/policy snapshot、annual qualification/tier/grant、time account、append-only ledger、opening import batch/row/issue/publication、report projection/access audit。实施阶段读取合并后的真实最高 Flyway 版本并整体顺延，绝不覆盖、重命名或修改既有迁移。

回滚通过停用新策略/API feature、停止新 append、追加冲正并恢复读取上一投影版本完成；已写 ledger/grant/import/audit 数据不物理删除。

### 15. TDD 与合成数据是当前交付门

当前先提交具名 RED 测试，再实现最小领域代码。测试固定 `LocalDate`/`ZoneId`，使用合成 `employee/account/employment/entry` ID，分别命名周年前日、周年日、到期日、10 年前日/当日、20 年、门槛优先、再入职、2 月 29 日默认/替代策略、余额重放、冲正和重复冲正；不得用一个参数化泛化用例替代所有边界。

## Risks / Trade-offs

- [W2 以天存储而 PRD 档位输入为月] → 领域层保持月语义；wiring 前要求版本化换算决策，未决时 fail closed，绝不使用隐式 30/365 换算。
- [W5 FINAL 的期间/快照形状变化] → 当前不建表、不预占迁移号、不实现 adapter；同步后以端口合同对齐并增加 retained tests。
- [周年自动任务重复执行] → 后续以 account + employment period + grant anniversary + policy version 唯一业务键和 durable idempotency 锁定，ledger append 与 audit 同事务。
- [业务日期回溯导致按日期重放不确定] → 以不可变 sequence 为事实顺序，业务日期只作业务归属和查询。
- [有符号发生额被调用方误用] → entry type 构造器验证方向、精度、单位和非零，REST/Mapper 不直接暴露任意构造。
- [周期中途升档或规则切换误补差] → grant 保存 policy/tier snapshot；默认只在下一周年重新解析，不重写既有 grant。
- [报表和余额读到不同版本] → 所有 query DTO 强制携带 projection/period/close version，下钻复用版本 token。
- [当前仅领域测试被误报为 W6 完成] → OpenSpec tasks 将纯领域完成与 W5-dependent persistence/API/UI/MySQL gates 分开，最终报告明确整体进度。

## Migration Plan

1. 当前阶段提交并验证 OpenSpec、领域 RED tests、纯领域实现和全量后端回归。
2. 等待 W5 FINAL；同步其 commit/manifest 后重新读取最高迁移号、period/close/reopen 合同和 retained tests。
3. 先解决 W2 `totalDays` 到 PRD `priorServiceMonths` 的版本化业务换算并冻结端口。
4. 添加 W6 RED migration/OpenAPI/security/DB tests，再选择未占用的前向迁移号并实现 append-only schema。
5. 实现 W2/W5 adapter、应用事务、权限/审计、期初导入、查询/报表和真实 UI。
6. 在批准的 MySQL 8.4 环境完成空库、升级、validate、no-op、约束、并发和恢复验证；再执行浏览器验收。
7. 回滚时关闭 W6 route/feature、停用策略和自动 append；已发布错误通过冲正和新投影版本前向修复，不删除历史。

## Open Questions

- W2 的 `totalDays` 应按哪一个已发布、可审计的制度换算为年假档位所需的 `priorServiceMonths`？在业务裁决前持久化 wiring 保持阻断。
- W5 FINAL 将暴露哪些确切的 period status、close snapshot、reopen version 和 recalculation intent 接口？同步上游后以实际合同更新本 design/tasks，当前不猜测。
- W6 逻辑迁移在 W5 FINAL 后对应的真实 Flyway 版本号是多少？只在同步后按最高已占用版本顺延。
