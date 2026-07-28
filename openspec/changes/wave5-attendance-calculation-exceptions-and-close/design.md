## Context

神州 HR 是 Java 21 / Spring Boot / MyBatis / Flyway 的模块化单体。V1.9 PRD 将考勤计算定义为分钟精度、半开区间和工作段级标签的确定性过程，而不是一个可被覆盖的“日状态”。路线图要求 WAVE-5 交付工作段结果、解释链、版本差异、冻结保护和金标准；验收标准 AC-CALC-01～07 与 AC-CLOSE-01～04 进一步要求来源事实只追加、跨日按班次归属、证据切分、完整下钻、授权调整、同输入可复算和不可变 close snapshot。

WAVE-4 OpenSpec 目前只是未 FINAL 的上游设计。它计划提供：

- 统一 `raw fact → normalized record → match → effective event → evidence link/slice` 事实链；
- authoritative candidate business dates；
- 只追加、窄范围的 durable recalculation intents；
- 面向 W4 发布保护的 `AttendancePeriodProtectionPort`；
- `OPEN/FROZEN/CLOSED/REOPENED/UNKNOWN` 期间状态与 provider token。

这些表、Mapper、DTO、Controller 和端口签名在 W4 FINAL 前都不是可依赖事实。W5 当前必须能独立实现与验证纯领域语义，同时把真实适配清楚地留在边界上。当前迁移注册表同样只冻结到同步基线；W5 不能预占文档中过时的“V9/V10”或任何其他物理迁移号。

## Goals / Non-Goals

**Goals:**

- 建立不依赖 W4 物理实现的 W5 领域语言、输入快照和应用端口。
- 确定性计算计划工作段、打卡/单据/调整证据、迟到/早退/缺卡/旷工和认可加班。
- 保存完整解释链、不可变 calculation version 与稳定 input/result digest。
- 以窄范围、幂等批次重算，保留旧版本并生成可解释差异。
- 管理异常与补正/人工调整的追加式生命周期，不修改原始证据。
- 管理期间预检、关闭、反月结、冻结保护和不可变 close snapshot。
- 先以合成金标准和 RED-first tests 冻结合同，再让可独立领域测试转绿。
- 明确列出 W4 FINAL 后才能继续的适配、持久化、迁移、OpenAPI/Controller 与真实 HTTP/MySQL 验收。

**Non-Goals:**

- 不假定、创建、修改或回填 W4 表、Mapper、DTO、API 或 provider 实现。
- 不消费真实得力/OA/Excel 数据，不连接真实供应商，不引入个人数据。
- 不在 W4 FINAL 前创建 Flyway 文件、分配迁移号或修改主 OpenAPI 公共面。
- 不把纯领域骨架宣称为可运行的来源到月结纵向切片。
- 不实现 W6 假别余额/年假流水、W7 自助/看板/报表或 W8 PAYROLL。
- 不修改、跳过、弱化 W1～W4 retained、授权、数据范围、安全、MySQL 或独立证据门。

## Decisions

### 1. W5 核心保持纯 Java，适配层在 W4 FINAL 后接入

新增 `com.szsemicon.hr.attendance.calculation` 边界，依赖方向为：

```text
interfaces (future REST / message adapter)
  -> application commands/use cases
  -> domain calculation/exception/period models
  -> application ports
  <- future W2/W3/W4/MyBatis adapters
```

当前实现只创建：

- 领域值对象、聚合、纯计算器和冻结策略；
- 面向应用层的输入/输出 records；
- W2/W3/W4/clock/persistence 的 provider-neutral ports；
- 不带 Spring MVC 注解的 use-case 接口骨架；
- 合成测试夹具和纯领域实现。

当前不创建 Controller、Mapper/XML、Repository adapter、Flyway 或主 OpenAPI path。这样既能冻结 W5 语义，也不会把 W4 未 FINAL 的签名或 schema 固化进产品面。

备选方案是直接复用 W4 预期表和 Java 包。该方案被拒绝，因为 W4 可能在 FINAL 前更名、调整 cardinality 或改变 provider token；直接依赖会产生虚假完成和高成本返工。

### 2. 输入是不可变 `CalculationInputSnapshot`，不是对可变表的临时查询

每次员工业务日计算消费一个闭合快照：

- `legalEntityId`、`employeeId`、`employmentPeriodId`、`businessDate`；
- 业务 `ZoneId` 和知识时点 `knowledgeCutoff`；
- 一个或多个已解析的 `ScheduledWorkSegment`，均以 `[start,end)` instant 表示；
- W3 attendance-group/shift/calendar/policy snapshot references 与 digest；
- W4 evidence snapshot reference、有效 event/slice records 与 digest；
- 当前批准 adjustment facts 与 digest；
- period identity、period version/provider token；
- calculation algorithm version。

快照构造时执行：

- ID 非空且保持 string/UUID 精确语义；
- 时区必须显式；
- 所有 interval 必须 `start < end`；
- 工作段不得重叠且按 instant、segment ID 稳定排序；
- evidence/adjustment references 必须唯一；
- 业务日、员工、法人、期间必须一致；
- `UNKNOWN` provider 状态 fail closed。

canonical digest 使用明确字段顺序、UTC instant、UTF-8、枚举名称、半开区间和稳定 ID 排序；禁止依赖 Java 对象 hash、Map 迭代顺序、数据库默认排序或本地时区。

同一 canonical input digest + algorithm version 必须生成同一 result digest。若已有成功版本，应用层可精确重放既有结果；若不同请求产生相同输入，不能创建语义不同的结果。

备选方案是计算时逐表查询最新数据。该方案被拒绝，因为并发来源/规则变化会造成撕裂读取，无法满足 AC-CALC-07 和 close snapshot 复现。

### 3. 业务日与跨日由计划段归属，内部只使用 instant + ZoneId

`businessDate` 是班次/日历解析结果，不由 UTC 或证据自然日截断。一个跨午夜工作段仍属于同一业务日；`ScheduledWorkSegment` 可以结束于下一自然日。

W5 要求上游配置快照给出 authoritative segment instants 和 cross-midnight cutoff reference。W5 校验工作段归属和排序，但不重新猜测 W3 班次。打卡点只能匹配一个工作段用途；被用于前一业务日跨日离场的 event ID 会进入 consumed-reference set，不能同时作为次日到岗。

在 W4 FINAL 前，金标准直接构造合成 segment/evidence snapshot。真实 `AttendanceConfigurationResolverPort` 与 W4 candidate-date 的一致性属于后续 wiring gate。

### 4. 证据先切为原子区间，再按固定优先级解析

计算器收集计划段、有效 interval evidence、adjustment 和点证据的所有边界，生成不重叠 `[start,end)` atomic slices。固定优先级与 V1.9 一致：

1. 已批准 HR 裁定/工时调整；
2. 已通过销假、撤销或冲销；
3. 已通过请假、调休、外出、出差、免打卡、补签、加班；
4. 原始/有效打卡点；
5. 系统推导异常。

同级互斥 evidence 覆盖同一 slice 时结果为 `EVIDENCE_CONFLICT`，不选择 winner，不按更新时间或到达顺序覆盖。不同级别 evidence 保留 selected/rejected 解释；低级 evidence 不删除。

调整以 `AdjustmentFact` 追加：

- stable adjustment ID 与 supersedes/reverses reference；
- 明确 employee、业务日和 `[start,end)`；
- 受控 conclusion；
- actor/capability/scope decision reference；
- reason、request/correlation ID、approvedAt；
- period token 和 optimistic version。

领域层只验证闭合形状和优先级，不自行伪造“已授权”；应用层必须通过 authorization/audit ports 后才能把 adjustment 纳入快照。

### 5. 日结果由工作段 items 构成，汇总可重算

每个 calculation version 保存：

- `DailyAttendanceResult` 头：输入/结果 digest、状态、算法版本、创建时间；
- `AttendanceResultItem`：segment/slice、分钟、类别、reason code、selected evidence、异常引用；
- `AttendanceRuleHit`：规则/参数快照、命中输入、原始分钟、计入分钟；
- `EvidenceDecision`：候选、优先级、selected/rejected/conflict 和理由；
- `ExplanationNode/Edge`：从日汇总下钻到 item、rule hit、evidence、adjustment、request。

核心分钟指标遵循 PRD：

- `scheduledMinutes (S)`：计划工作段之和，不含休息、加班或自然日假；
- `confirmedScheduledWorkMinutes (W_in)`：计划内被打卡、有效外出/出差/免打卡确认的工作；
- `extendedPresenceMinutes (E)`：计划外由事实证明的停留，不等于认可加班；
- `recognizedOvertimeMinutes (O)`：计划外实际证据与有效加班单交集，扣适用餐休；
- `leaveOrTimeOffMinutes (L)`：有效请假/调休覆盖的计划工作段；
- `absenceMinutes (A)`：无工作、无有效单据且处理期限已过的计划分钟；
- `actualWorkMinutes = W_in + O`，不得再次加上加班转调休额度。

计算全程使用整数分钟，展示层才转换 0.01 小时。日/月汇总必须完全由同版本 result items 相加得到；不保存无法由 items 重算的第二套权威数字。

### 6. 打卡、迟到、早退、缺卡和加班采用显式决策记录

每个计划工作段独立匹配有效到岗/离岗点，并保存：

- 候选 event ID 列表与稳定排序；
- selected arrival/departure；
- 未选择原因；
- consumed event IDs；
- 原始迟到/早退分钟；
- 被 interval evidence 覆盖的分钟；
- 最终计入分钟。

同一 punch event 默认只消费一次。不能唯一匹配时产生阻断异常，不静默选择。

迟到：

- `late=0` 不命中豁免；
- `0 < late <= configuredMax` 可按员工、自然月、业务日/工作段顺序消费有限次数；
- 超限事件不消费机会；
- 原始分钟保留，豁免后计入分钟为 0。

月度豁免并发消费需要未来持久化的 employee+month lock/唯一序号；纯领域计算接收一个已排序的 `GraceConsumptionSnapshot` 并返回预期 consumption decision。真实并发写入在 W4/W3 provider 和迁移冻结后完成。

缺卡：

- 单边缺卡在处理期限内为 `MISSING_PUNCH_PENDING`，不虚构另一侧；
- 期限内首次提交且仍审批中的有效候选保持 pending；
- 期限后按版本化策略仅把未覆盖的缺失工作段转为旷工、整日旷工或待 HR 裁定；
- 默认 7 个自然日、仅缺失工作段转旷工；
- 有效补签/免打卡/调整触发新版本重算，不改旧版本。

加班：

- `E` 只证明延长在岗；
- `O` 必须是实际证据与有效加班单交集；
- 临时加班以实际结束至首次提交时间判断默认 48 小时；
- 跨日打卡本身不认可加班；
- 餐扣按版本化日类型/窗口/触发/分钟执行，每窗最多一次且结果不小于 0；
- 同一认可时段只能选择计薪或转调休语义之一；W5 只保存事实标签，不写 W6/W8 余额或工资。

### 7. 异常是追加式 case + transition，不是结果行上的可编辑状态

`AttendanceExceptionCase` 具有 stable case ID、employee/date/segment/slice、type、severity、blockingClose、firstCalculationVersion 和 current derived status。状态由 append-only transition 派生：

```text
OPEN -> PENDING_EVIDENCE | PENDING_REVIEW
OPEN/PENDING_* -> RESOLVED
RESOLVED -> REOPENED -> RESOLVED
```

稳定类型至少包括：

- `MISSING_PUNCH_PENDING`
- `MISSING_PUNCH_OVERDUE`
- `EVIDENCE_CONFLICT`
- `NO_ATTENDANCE_GROUP`
- `NO_SHIFT_OR_CALENDAR`
- `AMBIGUOUS_PUNCH_MATCH`
- `CROSS_MIDNIGHT_REVIEW_REQUIRED`
- `OVERTIME_DOCUMENT_MISSING_OR_LATE`
- `EARLY_RETURN_CANDIDATE`
- `INPUT_INTEGRITY_ERROR`

重算以稳定 fingerprint（法人、员工、业务日、segment/slice、type、关键 evidence refs）关联前后版本：

- 问题仍存在：沿用 case，追加 observation；
- 问题消失：追加 `RESOLVED_BY_RECALCULATION`；
- 已解决后同一问题再次出现：追加 `REOPENED_BY_RECALCULATION`；
- 不删除历史 case/transition。

员工 OA 补单由 W4 新 evidence 到达体现；HR 裁定由 W5 adjustment fact 体现。两者都只触发重算，不直接 UPDATE result/exception。

### 8. 重算批次窄范围、幂等、保留版本并输出差异

`RecalculationBatch` 接收：

- legal entity 与 period identity/version token；
- 去重且稳定排序的 `(employeeId,businessDate)` targets；
- upstream intent IDs / trigger references；
- reason、actor、request/correlation/idempotency；
- expected input/provider tokens。

状态：

```text
REQUESTED -> RUNNING -> SUCCEEDED | PARTIALLY_FAILED | FAILED | CANCELLED
```

同一 operation/resource/idempotency key + canonical request digest：

- 已成功时精确重放；
- payload 不同返回 conflict；
- 未提交失败可安全重试；
- 不允许把 `RUNNING` 当成功。

每个 target 在一套 input snapshot 上生成新 calculation version；若 input digest 与已成功版本完全相同，可引用已有版本并记录 `NO_CHANGE_REPLAY`，不得产生语义重复。无关 employee/date 的 current calculation version 必须保持不变。

`AttendanceResultDifference` 同时保存 machine-readable 与解释性差异：

- old/new calculation version、input/result digest；
- 新增/移除/修改的 result item、rule hit、evidence decision、exception；
- 分钟指标 delta；
- change reason categories：`EVIDENCE_CHANGED`、`RULE_CHANGED`、`SCHEDULE_CHANGED`、`ADJUSTMENT_CHANGED`、`ALGORITHM_CHANGED`、`PERIOD_REOPENED`；
- causative upstream IDs/request IDs。

差异使用稳定 business key 匹配 items，不依赖数据库自增 ID 或列表位置。

### 9. 期间聚合、关闭和反月结采用双重冻结校验

W5 领域期间状态与 W4 provider state 的兼容集合为：

```text
OPEN -> FROZEN_FOR_CLOSE -> CLOSED
CLOSED -> REOPENED
REOPENED -> FROZEN_FOR_CLOSE -> CLOSED
UNKNOWN (只读失败态，禁止 mutation)
```

`FROZEN_FOR_CLOSE` 是关闭预检/提交期间的写保护，不代表成功关闭。任何来源发布、调整、普通重算和新版本 current 指针更新必须：

1. 在命令入口读取 period token；
2. 在事务/聚合锁内再次读取并比较；
3. 只有 `OPEN/REOPENED` 且 token 未变化时提交。

预检 `ClosePrecheckReport` 至少验证：

- 范围内 employee/date 全覆盖且只有一个 current successful calculation version；
- 未解决 blocking exceptions 为 0；
- W4 未决 `EVIDENCE_CONFLICT` / quarantine/integrity blocker 为 0；
- 无 RUNNING 来源导入、重算或 adjustment publication；
- W4 committed watermark/freshness 满足受控阈值；
- W2/W3/W4/config/result provider tokens 与预检 token 一致；
- 同版本汇总由 result items 重算差异为 0；
- audit/idempotency dependency 可用。

close 在同一事务中锁 period，重验 precheck token，然后生成 immutable `AttendanceCloseSnapshot`，保存：

- legal entity、period range、scope/version；
- organization/employment/configuration/evidence/adjustment/result snapshot references 与 digests；
- 每个 employee/date 的 calculation version set digest；
- counts/control totals；
- actor/reason/request/correlation/idempotency；
- closedAt 和 snapshot digest。

close 后普通来源发布、调整、重算和 current result mutation返回稳定 conflict，旧 snapshot byte-identical。

reopen 需要独立 capability、原因、strong If-Match/idempotency 和主管授权事实。它：

- 不修改旧 snapshot；
- 追加 period transition；
- 生成递增的新 period version/provider token；
- 状态成为 `REOPENED`；
- 后续新 evidence/adjustment/recalculation 只写新版本；
- 关闭版本与新 current 结果之间产生 `POST_CLOSE_DIFFERENCE`。

“恢复前一 close snapshot”作为回滚语义只改变 current authoritative snapshot pointer/服务版本；不删除后来 snapshot、transition、difference 或 calculation history。

### 10. 冻结保护为可复用领域策略

`FrozenPeriodProtection` 是无框架纯策略，输入：

- requested mutation kind；
- authoritative period state/token；
- target period version；
- optional close snapshot reference。

输出 `Allowed` 或稳定拒绝：

- `ATTENDANCE_PERIOD_UNKNOWN`
- `ATTENDANCE_PERIOD_FROZEN`
- `ATTENDANCE_PERIOD_CLOSED`
- `ATTENDANCE_PERIOD_VERSION_STALE`
- `ATTENDANCE_CLOSE_SNAPSHOT_IMMUTABLE`

策略不信任客户端传入 state。未来 adapter 必须通过权威 provider 组装输入；Controller 不能直接调用纯策略并把 request 中的状态当真。

### 11. 当前接口骨架与 W4 FINAL 后精确适配点

当前创建 provider-neutral ports：

1. `AttendanceEvidenceSnapshotPort`
   - 输入：法人、员工、业务日、知识时点、expected provider token；
   - 输出：不可变 evidence snapshot ref/digest、point events、interval slices、reversal/conflict metadata；
   - 待 W4 FINAL 决定真实表/DTO/分页与知识时点读取。
2. `AttendanceConfigurationSnapshotPort`
   - 输入：员工、业务日、知识时点；
   - 输出：任职、考勤组、班次/工作段、日历、策略、时区 snapshot 与 digest；
   - 待 W2/W3 FINAL adapter，不允许 W5 复制解析规则。
3. `AttendanceRecalculationIntentInboxPort`
   - `claim`：按稳定顺序领取 W4 durable intents；
   - `acknowledge`：绑定 W5 batch/version；
   - `reject/retry`：保存稳定原因但不丢 intent；
   - 待 W4 FINAL 决定 intent identity、lease/version、candidate-date 与 transaction boundary。
4. `AttendancePeriodStatePort`
   - W5 对来源/导入侧提供 `OPEN/FROZEN/CLOSED/REOPENED/UNKNOWN + periodId/version/token/snapshotRef`；
   - 待适配 W4 计划中的 `AttendancePeriodProtectionPort`，token 必须在预检和提交两次比较。
5. `AttendanceCloseDependencyPort`
   - 读取 W4 watermark/freshness、运行中 job、quarantine/conflict blockers 和 immutable digest；
   - W4 FINAL 前只存在 synthetic fake。
6. `AttendanceCalculationVersionStore`、`AttendanceExceptionStore`、`AttendancePeriodStore`
   - 当前只冻结 transactional semantics 和 optimistic version；
   - Mapper/schema/索引在迁移注册表同步后实现。
7. `AttendanceAuthorizationPort`、`AttendanceAuditPort`、`AttendanceClock`
   - 复用 W1 能力/范围/审计语义的 adapter 待真实 wiring；纯领域测试显式传入批准事实或 fake。

不得在 W4 FINAL 前将任何当前 port 名声称为 W4 已实现接口。同步后优先写 anti-corruption adapter，而不是强迫 W4 改其 FINAL 合同。

### 12. 未来公共 API 族先冻结语义，不注册产品 path

计划的 operation families：

- `GET /attendance-days`
- `GET /attendance-days/{employeeId}/{businessDate}`
- `GET /attendance-calculations/{calculationVersionId}`
- `GET /attendance-calculations/{calculationVersionId}/explanation`
- `GET /attendance-exceptions`
- `GET /attendance-exceptions/{exceptionId}`
- `POST /attendance-adjustments`
- `POST /attendance-adjustments/{adjustmentId}/reverse`
- `POST /attendance-recalculations`
- `GET /attendance-recalculations/{batchId}`
- `GET /attendance-result-differences`
- `GET /attendance-periods`
- `POST /attendance-periods/{periodId}/precheck`
- `POST /attendance-periods/{periodId}/close`
- `POST /attendance-periods/{periodId}/reopen`
- `GET /attendance-close-snapshots/{snapshotId}`

所有 ID 以 string；列表 bounded/stable pagination；mutation 要求 CSRF、`Idempotency-Key`、`X-Change-Reason`、必要时 strong `If-Match`。错误至少闭合 400/401/403/404/409/422/429/503 与 correlation ID。

当前只实现 Java use-case/DTO skeleton 和 OpenSpec 合同，不修改 `api/openapi.yaml`，因为公开未接线 operation 会破坏 W1-W4 OpenAPI/Controller closure。W4 FINAL 同步后的首个任务是把 operation、capability、错误码和 Controller 双向一次性闭合。

### 13. 权限、数据范围和审计按动作拆分

计划 capability：

- `ATTENDANCE_CALCULATION:READ`
- `ATTENDANCE_CALCULATION:EXPLANATION_READ`
- `ATTENDANCE_EXCEPTION:READ`
- `ATTENDANCE_EXCEPTION:ASSIGN`
- `ATTENDANCE_ADJUSTMENT:CREATE`
- `ATTENDANCE_ADJUSTMENT:APPROVE`
- `ATTENDANCE_ADJUSTMENT:REVERSE`
- `ATTENDANCE_RECALCULATION:CREATE`
- `ATTENDANCE_RECALCULATION:READ`
- `ATTENDANCE_PERIOD:READ`
- `ATTENDANCE_PERIOD:PRECLOSE`
- `ATTENDANCE_PERIOD:CLOSE`
- `ATTENDANCE_PERIOD:REOPEN`
- `ATTENDANCE_CLOSE_SNAPSHOT:READ`

每个动作同时执行 legal entity + location/attendance-group/organization scope、字段策略、对象状态和期间 token。SYSTEM_ADMIN 不自动继承考勤详情或调整/月结能力；AUDITOR 默认只读；reopen 与 close 分权。

解释链根据 raw-row/file/location capability 分层返回；没有 raw 权限仍可看到允许的理由码和 digest，但看不到原始行、文件、精确位置或不必要假因。调整、重算、close/reopen、快照读取/导出均审计，且日志/审计不保存完整敏感 payload。

### 14. RED-first 与金标准策略

实现分三步提交：

1. OpenSpec apply-ready；
2. 接口骨架 + RED tests，明确记录失败测试；
3. 纯领域实现使可独立测试转绿。

金标准仅使用明显合成 ID/时间，至少覆盖：

- 上午正常 + 下午有效单据的混合日；
- `late=0`、`1`、`15`、`16` 分钟与月度一次豁免；
- 单边缺卡处理期内、第 7 日内提交第 9 日批准、逾期仅缺失段旷工；
- 02:00 回挂前日、同 punch 不得复用于次日；
- 加班 47:59/48:01、无单晚退、义务加班、餐窗边界；
- overlapping interval 原子切分和同级 `EVIDENCE_CONFLICT`；
- 相同输入不同顺序 result digest 相同；
- 输入变化生成 explainable difference，旁观员工版本不变；
- FROZEN/CLOSED/UNKNOWN/stale token 拒绝；
- close snapshot 不可变、reopen 新 period version 且旧 snapshot 可查。

当前 RED/green 只证明纯领域语义，不满足真实 API、MySQL、W4 evidence 或 W4 intent 消费验收。

### 15. 物理持久化与迁移号延后但逻辑模型固定

逻辑对象：

- `scheduled_work_segment`
- `attendance_calculation_version`
- `daily_attendance_result`
- `attendance_rule_hit`
- `attendance_result_item`
- `attendance_explanation_node/edge`
- `attendance_exception`
- `attendance_exception_transition`
- `attendance_adjustment`
- `attendance_adjustment_transition`
- `recalculation_batch/target`
- `attendance_period`
- `attendance_period_transition`
- `attendance_close_snapshot`
- `attendance_close_snapshot_member`
- `attendance_result_difference/item`

W4 FINAL 同步后先读取实际最高迁移和 immutable registry，再分配连续前向版本；绝不复用路线图中的逻辑 V9/V10，也不覆盖已发布文件。必须验证：

- empty baseline → latest；
- W4 FINAL latest → W5 migrations → latest；
- validate、second migrate no-op、retained checksum；
- MySQL 8.4.10 约束/索引/锁/rollback/least privilege；
- H2 不替代 MySQL gate。

### 16. 完成证据分层

当前隔离树可以完成：

- OpenSpec strict/apply readiness；
- 纯 Java 编译；
- RED-first 记录；
- 领域金标准、属性/顺序稳定性、冻结策略、差异模型测试；
- W1-W3/W4 source contract 静态 retained 不回退；
- 无迁移号、无主 OpenAPI 注册、无真实数据、PAYROLL 零新增。

只有同步 W4 FINAL 后才能完成：

- W4 FINAL manifest/source hash retained；
- evidence/slice/intent/period/watermark adapter；
- migrations/MyBatis/事务/并发；
- 主 OpenAPI/Controller/UI；
- real normal-mode HTTP/MySQL；
- 全月性能、月结并发、恢复；
- W5 frozen-source independent FINAL。

tasks 中这些项目必须保持未勾选并注明 `BLOCKED_ON_W4_FINAL`，不能用 fake 通过替代。

## Risks / Trade-offs

- [W4 FINAL 改变 evidence/intent/provider token] → 当前只依赖 provider-neutral records；同步后用 anti-corruption adapters 并重新跑 golden/contract tests。
- [纯领域模型与真实 SQL cardinality 不一致] → stores 只冻结事务语义，不提前冻结 Mapper；W4 FINAL 后先做 schema/cardinality design review 和 MySQL RED tests。
- [跨日和打卡匹配规则复杂] → 使用 instant/ZoneId、显式 consumed IDs、稳定候选排序和边界金标准；任何歧义 fail closed。
- [迟到豁免需要跨日计算并发状态] → 当前把已锁定 consumption snapshot 作为输入；真实 employee-month lock/unique invariant 推迟到 persistence phase。
- [close 期间来源或规则继续变化] → 入口和事务内双重 token 校验；close snapshot 保存全部 provider/reference digests。
- [延后 OpenAPI 可能导致接口漂移] → OpenSpec 先冻结 operation family、capability/error/idempotency；W4 FINAL 后以 Controller/OpenAPI 双向 closure 一次发布。
- [中间 RED commit 不是可发布状态] → 分阶段 commit 明确标注 RED，最终同分支实现独立领域 green；不会 push/merge 中间状态。
- [逻辑表名未来冲突] → 它们当前仅为设计名；实际迁移前检查 registry，冲突只能前向改名/顺延。

## Migration Plan

1. 在同步基线 `0c09fc375b1d2973915234e50a9aac10900446ca` 上完成并严格校验本 OpenSpec。
2. 提交 OpenSpec proposal/design/specs/tasks，保持所有真实 W4 wiring 与 migration tasks 未完成。
3. 添加 provider-neutral Java 接口骨架与合成 RED tests，记录预期失败。
4. 实现纯领域输入快照、计算/解释、异常、重算差异、期间状态和冻结保护，使独立测试转绿。
5. 运行 backend targeted/full tests 和仓库静态门；不声明 W5 纵向切片 FINAL。
6. 等待 W4 FINAL；同步其 commit、manifest、迁移 registry、Java/OpenAPI contracts。
7. 仅在无 retained 漂移后分配实际迁移号，实现 adapters/MyBatis/Controller/OpenAPI/UI 和真实 MySQL/HTTP gates。
8. close/reopen 缺陷只以前向代码/迁移修复；回滚应用时停止新 calculation version，并把 current authoritative close pointer 恢复到前一 snapshot，绝不删除历史。

## Open Questions

- W4 FINAL 的 evidence snapshot 查询是否支持知识时点，以及有效 event/slice/conflict 的最终 cardinality 和 digest 形状。
- W4 durable intent 的领取、lease、ack/retry、幂等键和与 W5 batch 的事务边界。
- W4 调用的 `AttendancePeriodProtectionPort` 最终 Java 签名、token 强度及同一事务可见性。
- W4 close precheck 所需 watermark/freshness、运行中 job、quarantine/conflict blocker 的正式查询合同。
- W3 FINAL 对月度迟到豁免并发消费是否已有可复用锁/流水，或需 W5 前向新增逻辑对象。
- W5 物理迁移的实际起始版本，只能由 W4 FINAL 后的 registry 决定。
- close/reopen 的最终职责分离角色映射与审批事实来源；当前只冻结独立 capability 与 fail-closed 语义。
