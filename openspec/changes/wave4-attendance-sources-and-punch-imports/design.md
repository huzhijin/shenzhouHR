## Context

神州 HR 使用 Java 21 / Spring Boot / MyBatis / Flyway 模块化单体与 React 19 / TypeScript / Vite。WAVE-1 提供本地认证、capability、data scope、CSRF、持久幂等和审计；WAVE-2 提供本地权威员工、`[start_date,end_exclusive)` 任职周期与外部人员标识；WAVE-3 提供法人隔离的地点、考勤组、班次、日历和策略解析。

WAVE-4 的输入是三类来源：

1. 得力云原始打卡增量；
2. 致远 OA 已通过、修改或撤销的考勤业务单据；
3. 标准或异构设备 `.xlsx` 原始打卡。

三者必须共享同一事实层，且 WAVE-4 不能提前实现 WAVE-5 日结果、异常处置、重算执行和月结。真实得力/OA 字段、凭据、异构设备样本和生产文件存储当前不是仓库内事实，因此内部端口与合成合同夹具是本波强制交付，真实供应商联调单独保持 `NOT_VERIFIED`。

## Goals / Non-Goals

**Goals**

- 建立来源实例、设备/人员映射、同步任务、水位、重试与隔离记录。
- 建立跨来源共享且只追加的 raw、normalized、match、effective event、evidence link 和 reversal 模型。
- 实现版本化 `.xlsx` 模板、mapping profile、预检、发布、部分发布、作废/冲正与错误报告。
- 在并发到达时精确实现来源幂等、稳定指纹、跨来源精确重复和 1～60 秒近似重复裁决。
- 把 OA 时段标准化为 `[start,end)`，形成确定性证据切片和同级互斥冲突。
- 对每个高风险动作执行服务端 capability、数据范围、对象状态、版本、期间保护和审计。
- 形成 OpenAPI/Controller/MyBatis/MySQL/UI/demo/外部契约桩可机械复验的纵向切片。

**Non-Goals**

- 不连接或写入真实致远/得力生产环境，不保存真实凭据或个人数据。
- 不写回 OA/得力，不同步组织，不采集生物模板，不把 OA 部门用于本地授权。
- 不计算迟到、早退、缺卡、旷工、认可加班、余额或月结结果。
- 不允许 Excel 导入计算结果或冒充人工补签。
- 不修改 W1～W3 迁移、API、业务要求、已有事实或 checksum。
- 不建设假期、员工自助、看板、报表或 PAYROLL。

## Decisions

### 1. 模块边界与依赖方向

后端新增两个边界：

- `evidenceingestion`：来源、同步、水位、raw/normalized/match/effective/evidence、重复裁决与重算意图；
- `punchimport`：模板、mapping、文件、批次、预检、发布、错误报告与作废/冲正。

依赖方向保持 `controller → application → domain/port → infrastructure adapter`。领域对象不得依赖得力 DTO、OA 物理字段、Apache POI 行对象、MultipartFile 或 REST DTO。

W4 只读调用：

- W2 `EmployeeEmploymentResolverPort`：按员工编号/外部绑定及业务时点解析恰好一个 employee + employment period；
- W3 `AttendanceConfigurationResolverPort`：按员工与 instant 解析地点、考勤组、业务时区、可能的业务日/前一跨日业务日；
- `AttendancePeriodProtectionPort`：返回 `OPEN/FROZEN/CLOSED/REOPENED/UNKNOWN`、snapshot/version token；
- `RecalculationIntentPort`：只记录受影响范围，不在 W4 执行计算。

任一 resolver 返回零个、多个或 `UNKNOWN` 时 fail closed。客户端不能提交 employeeId、employmentPeriodId、businessDate、period status 或组/班次结论来覆盖服务端解析。

选择端口而不是直接依赖 W5 表，是为了让 W4 在 W5 尚不存在时完成内部合同，同时保证 W5 只需替换 provider，不改变来源 API 或事实形状。

### 2. 两个前向迁移与 retained 边界

W3 当前占用 V7，因此 W4 固定新增：

- `V8__attendance_source_and_evidence.sql`
- `V9__attendance_punch_import.sql`

实现开始前必须重新确认 V8/V9 未被其他已发布迁移占用；若已占用，只能整体顺延并同步更新本 change，绝不能覆盖已发布文件。V1～V7 文件名、内容和 checksum 不变。

V8 逻辑对象：

- `attendance_source`
- `attendance_source_config_revision`
- `attendance_sync_job`
- `attendance_sync_job_page`
- `attendance_sync_watermark`
- `source_device`
- `source_device_revision`
- `device_person_binding`
- `attendance_evidence_subject_lock`
- `raw_attendance_fact`
- `normalized_attendance_record`
- `employee_match_decision`
- `effective_attendance_event`
- `effective_event_lifecycle_fact`
- `evidence_link`
- `oa_attendance_document`
- `source_reversal_record`
- `duplicate_review_group`
- `duplicate_review_member`
- `duplicate_review_resolution`
- `evidence_interval_slice`
- `attendance_recalculation_intent`
- `attendance_ingestion_idempotency`

V9 逻辑对象：

- `punch_mapping_profile`
- `punch_mapping_profile_version`
- `punch_import_batch`
- `punch_import_file`
- `punch_import_row`
- `punch_import_normalization_attempt`
- `punch_import_issue`
- `punch_import_precheck`
- `punch_import_publication`
- `punch_import_state_event`
- `punch_import_error_report`

所有 ID 与外部标识经 API 以 string 传输。新表使用明确 PK、FK/等效完整性、命名唯一索引、受控状态、UTC timestamp、业务时区、`row_version`、actor/request/correlation 字段及有界分页索引。raw fact、lifecycle、evidence、resolution、publication、state event 和 recalculation intent 只追加。

选择两次迁移而不是单次大迁移，是为了让共享事实层可独立验证，并让 Excel 导入只依赖稳定的 V8 写入端口。

### 3. 来源身份、配置与水位

`attendance_source` 是稳定来源实例，包含 `legal_entity_id`、受控 `source_type`：

- `DELI_CLOUD`
- `OA_ATTENDANCE`
- `DEVICE_EXCEL`
- `STANDARD_XLSX`

来源配置以 immutable revision 保存；凭据只保存仓库外 secret reference 名称，不保存值。配置 revision 记录 endpoint kind、source timezone、page size、rate-limit/backoff policy、mapping version 和生效时间。

同步任务状态：

`QUEUED → RUNNING → SUCCEEDED / PARTIALLY_QUARANTINED / FAILED / CANCELLED`

每次拉取按 page transaction 执行：

1. 锁 source identity 和已提交 watermark；
2. 以该 watermark 请求 adapter；
3. 校验 page/cursor 与合同；
4. 按来源业务键幂等追加 raw，完成规范化、匹配、effective/duplicate/reversal 处理；
5. 保存 page 计数、隔离计数、requestId 与响应摘要；
6. 仅在整页数据库事务提交时推进 committed watermark。

单条未知状态、未映射人员或业务校验失败可作为 raw + quarantine 提交并推进该完整页；传输失败、无法解析整个 page、cursor 回退/循环或数据库失败不得推进水位。重试从最后 committed watermark 开始，相同 source key/version 不新增 raw 或 event。

选择 page-level commit 而不是 record-level watermark，是为了避免失败页越过；允许隔离行随页提交，是为了避免一条坏记录永久阻塞后续增量。

### 4. 得力与 OA 外部端口

`DeliPunchSourcePort` 的内部 canonical page 至少包含：

- source record ID（string）；
- external person ref/employee number；
- punch instant、原始时间文本、source timezone；
- `AUTO/IN/OUT` direction；
- verification method；
- device ref、location summary、可选 coordinate-system tag；
- source revision/version 与 next cursor。

不得采集人脸、指纹模板或其他生物模板。未知坐标系只保存受控标签与原始摘要，不转换、不绘图。

`OaAttendanceDocumentSourcePort` 的 canonical page 至少包含：

- source business key 与 source version；
- external person ref/employee number；
- `LEAVE/LEAVE_REVOCATION/OVERTIME/TRIP/OUTING/PUNCH_CORRECTION/TIME_OFF/EXEMPT_PUNCH`；
- source status、`start/end`、source timezone；
- first-submitted/approved/modified/revoked instant；
- source batch/cursor。

只有经版本化状态映射明确为 `APPROVED` 的单据才生成 active business-document evidence。草稿、驳回和未知状态不参与有效证据；未知状态必须隔离。修改、销假、撤销或补录追加新 source version/raw fact 和 lifecycle/reversal，不更新旧 raw。

合成 adapter fixture 必须覆盖分页、重复页、限流、失败后重试、未知状态、修改、撤销、补录、19 位以上 ID、DST/显式时区和 out-of-order version。真实 adapter 与 fixture 共用同一 consumer contract suite。

### 5. 只追加统一事实层

统一流为：

```text
SourceBatch / PunchPublication
  → RawAttendanceFact
  → NormalizedAttendanceRecord
  → EmployeeMatchDecision
  → EffectiveAttendanceEvent
  → EvidenceLink
  → EvidenceIntervalSlice / RecalculationIntent
```

`raw_attendance_fact` 保存 source instance、受控 fact kind、source business key/version、source time/timezone、received time、canonical payload digest、受控原始对象引用和 requestId。数据库权限与 Mapper contract 禁止 UPDATE/DELETE raw rows。

`normalized_attendance_record` 保存 normalization schema version、canonical typed values 和 validation status；重新规范化产生新记录并通过 supersedes 引用旧记录。

`employee_match_decision` 是不可变决策。匹配顺序：

1. 有员工工号时按该工号唯一匹配并验证事实时点的有效任职；
2. 否则按地点 + 设备 + 设备人员编号的有期限 binding；
3. OA/得力已确认 external-person binding 可作为显式 mapping；
4. 姓名、部门只显示核对，绝不自动决定员工。

多人、零人、离职空档、越权法人/地点、任职重叠或 W3 配置不唯一均产生明确 issue/quarantine，不猜测。

`effective_attendance_event` 保存不可变 point/interval payload；active 状态由 `effective_event_lifecycle_fact` 的 `ACTIVATED/RETRACTED/SUPERSEDED` 事实派生。`evidence_link` 允许一个 effective event 引用多个跨来源 raw facts。来源撤销追加 reversal raw + lifecycle fact；旧 event/raw/link 不删除。

选择 lifecycle fact 而不是 event 上 mutable status，是为了让任一知识时点可以重放“当时为何有效”。

### 6. 精确与近似重复的确定性协议

四层键：

1. 文件：`legal entity + source scope + SHA-256(content)`；
2. 来源记录：`source instance + source business key + source version`；
3. 无来源 ID 的稳定指纹：`legal entity + location + device + device person/employee + normalized instant + direction` 的 canonical bytes SHA-256；
4. effective candidate：`legal entity + employee + exact instant + normalized direction`。

同一文件换名重传返回原批次或创建指向原文件的重新预检 attempt，但不得重复发布 raw。相同来源记录/version 不新增 raw。不同来源的相同设备流水、或同员工/同精确 instant，保留各自 raw，并原子合并为恰好一个 active effective event。

不同来源、同员工、同标准化 direction、时间差 `1～60` 秒进入一个 `PENDING_DUPLICATE_REVIEW` group：

- group 创建时若已有相关 active event，同一事务追加 RETRACTED lifecycle；
- 待裁决期间该 group 的 active event 数为 0；
- `SAME_FACT` 裁决生成恰好 1 个 active event并链接全部成员；
- `DISTINCT_FACTS` 为每个 raw member 生成恰好 1 个 active event；
- resolution 保存 actor、reason、If-Match、idempotency、版本和审计；
- raw/member/resolution 均不可删除或改写。

窗口默认 60 秒且版本化。新配置只影响新的 ingestion/precheck attempt，不重写已发布或已裁决组。

在线和批次并发统一锁 `attendance_evidence_subject_lock(legal_entity_id,employee_id)`，多员工按 UUID binary order。批次再按 row number/source key 排序，duplicate group/member 按 binary key 排序；锁内第二次检查 exact/near candidates。选择较粗的 employee lock 是用可预测的串行化换取去重正确性，后续通过 50,000 行和并发测试验证可接受性。

### 7. OA 时段切分与证据优先级

所有 interval 统一为业务时区的 `[start,end)`，同时保留 source instant/timezone。`start >= end` 阻断该记录的有效化。

规范化器收集同员工受影响窗口的所有边界，排序后切分为互不重叠的 atomic slices。每个 slice 保存候选 evidence ID、source version、类型、优先级与解析状态。

固定优先级槽位：

1. W5 将来的已批准 HR 裁定/调整；
2. 已通过销假、撤销或冲销；
3. 已通过请假、调休、外出、出差、免打卡、补签、加班；
4. raw punch point evidence；
5. W5 将来的系统推导异常。

W4 只形成 slice 和 winner/conflict 输入，不产生迟到/旷工/加班结果。同级互斥业务单据重叠时 slice 为 `EVIDENCE_CONFLICT` 且 winner 为空，不按更新时间或最后写入选择。撤销/冲销只影响其明确引用的单据和时段。

这在 W4 关闭 AC-CALC-04 的“标准化与确定性证据切分”部分；W5 仍须验证该切片被日计算正确消费。

### 8. Excel 模板、mapping 与文件安全

模板 endpoint 提供受控 `.xlsx`，至少包含：

- `导入说明`
- `考勤打卡导入`
- `设备人员映射（可选）`
- `字段说明`
- `枚举值`
- `示例数据`

主表 14 个 canonical 字段、设备映射 9 个字段和纯合成示例与 V1.9 补充规格一致。工作簿提供机器可读 `templateVersion` 与 canonical field-contract SHA-256；文件本身以 ETag/SHA-256 版本化。服务端不依赖样式、下拉或表范围校验 50,000 行。

只接受 OOXML `.xlsx`。上传层校验扩展名、MIME/空 MIME 容错、ZIP magic、OOXML content types、大小、行数、entry 数、解压总量和压缩比；拒绝：

- `.xls`/CSV；
- VBA/宏部件；
- 任意公式单元格；
- external links、DDE/OLE/embedded package；
- 路径穿越或异常 ZIP；
- 迟到、旷工、认可加班、异常结论等计算结果列。

默认最大 20 MiB、50,000 data rows，均由服务端受控参数给出且在 API 暴露实际 limit。前端 `accept` 只作体验，不能替代服务端内容校验。

mapping profile 是 stable identity + immutable version，scope 包含法人、厂商、型号和可选地点。转换只允许登记的列映射、日期格式、source timezone、trim policy 与 enum map；禁止脚本、表达式、任意函数和把姓名作为唯一键。已被批次引用的 version 不可原位修改。

### 9. 批次状态机、预检 token 与事务

批次状态由 append-only `punch_import_state_event` 派生：

```text
DRAFT
  → VALIDATING
  → VALIDATION_FAILED | AWAITING_CONFIRMATION | BLOCKED_BY_FROZEN_PERIOD
AWAITING_CONFIRMATION
  → PUBLISHING
  → PUBLISHED | PARTIALLY_PUBLISHED | PUBLISH_FAILED
PUBLISHED | PARTIALLY_PUBLISHED
  → VOIDED
```

`VALIDATION_FAILED`、`BLOCKED_BY_FROZEN_PERIOD`、`PUBLISH_FAILED` 是不同枚举和原因码：

- validation failed：修正文件/mapping 后重新预检；
- frozen blocked：期间重开后重新预检，旧 token 失效；
- publish failed：事务没有 committed raw fact，可按稳定原因重试发布。

预检建立 immutable attempt，逐行保存 raw/normalized value、match、issue、重复结论、period guard、影响员工/日期和计数。返回短期 `precheckToken`，token 摘要绑定 batch version、file hash、mapping version、窗口配置、W2/W3 resolver digest、period snapshot/version 和全部 row result digest。

发布必须：

1. 重验 capability/scope/CSRF/If-Match/idempotency/reason；
2. 锁 batch 并二次校验 token/digest；
3. 重读 period provider；不是 `OPEN/REOPENED` 或 token 变化则转 frozen blocked；
4. 按固定顺序锁 source、employee subject、duplicate keys；
5. 严格模式在一个事务发布全部可发布行；任何 blocker 时不得进入 PUBLISHING；
6. 受控部分模式只发布 valid rows，必须具有独立 capability、显式 mode/reason/确认摘要；
7. 保存 publication、raw/evidence、state、success audit 和 exact affected-range recalculation intents 后一次 commit。

严格事务失败回滚全部 raw/effective/intents/success audit，并追加独立 failure audit；状态为 `PUBLISH_FAILED`。部分成功不是事务失败的替代词。

作废/冲正只允许已发布且 provider 为 `OPEN/REOPENED` 的批次。它追加 reversal raw/effective lifecycle、publication/state/audit 和同一受影响范围 intent，不删除原文件、row、raw 或 event。冻结/关闭期间返回 409。

### 10. 受影响范围而非提前计算

每个 published/reversed/duplicate-resolved/change source version 生成去重的 `attendance_recalculation_intent`：

- legal entity；
- employee ID；
- candidate business dates；
- source/evidence IDs；
- reason；
- resolver snapshot digest；
- request/correlation ID。

业务日期由 W3 authoritative resolver 根据 punch instant、timezone、shift/cross-midnight 计算；无法唯一解析时进入 issue，不按自然日猜测。OA 使用新旧 interval 并集。

W4 验证只断言 intent 精确覆盖受影响员工/日期、无关员工无 intent，并通过 fake port 证明不会调用广泛整月重算。W5 接管后再证明 calculation version 变化和日结果。

### 11. 权限、数据范围、字段最小化与审计

新增 capability：

- `ATTENDANCE_SOURCE:READ`
- `ATTENDANCE_SOURCE:CONFIGURE`
- `ATTENDANCE_SOURCE:RUN`
- `ATTENDANCE_SOURCE:RETRY`
- `ATTENDANCE_SOURCE:QUARANTINE_READ`
- `ATTENDANCE_PUNCH_IMPORT:READ`
- `ATTENDANCE_PUNCH_IMPORT:TEMPLATE_DOWNLOAD`
- `ATTENDANCE_PUNCH_IMPORT:UPLOAD`
- `ATTENDANCE_PUNCH_IMPORT:PRECHECK`
- `ATTENDANCE_PUNCH_IMPORT:PUBLISH`
- `ATTENDANCE_PUNCH_IMPORT:PARTIAL_PUBLISH`
- `ATTENDANCE_PUNCH_IMPORT:VOID_OR_REVERSE`
- `ATTENDANCE_PUNCH_IMPORT:RAW_FILE_READ`
- `ATTENDANCE_PUNCH_IMPORT:RAW_ROW_READ`
- `ATTENDANCE_PUNCH_IMPORT:ERROR_REPORT_DOWNLOAD`
- `ATTENDANCE_PUNCH_IMPORT:DUPLICATE_REVIEW`
- `ATTENDANCE_PUNCH_IMPORT:RECALCULATE`

每个 action 还要命中 legal entity + location/attendance-group/organization data scope。列表查询在 SQL/Mapper 层限定 scope 后再分页，不取全量后过滤。无读取权限按资源暴露策略 404；未认证 401；已认证但 action 禁止 403；版本/状态/期间冲突 409。

SYSTEM_ADMIN 不因技术角色自动拥有 raw file、raw row 或业务详情。AUDITOR 是否读取 raw row 由显式 capability 决定且保持只读。下载必须由后端重验权限，使用受控 object reference、`no-store`、安全 Content-Disposition 和下载审计，不暴露本机路径或长期公开 URL。

审计至少覆盖 source 配置、任务开始/完成/失败/重试、上传、预检、发布、部分发布、作废/冲正、重复裁决、raw file/row 查看、错误报告下载和重算 intent。审计保存 requestId/correlationId、actor、scope digest、result/reason、before/after digest，不保存凭据、完整原始行、精确坐标或不必要假因。

### 12. Durable idempotency 与并发顺序

mutation 唯一键：

`actor_id + operation_code + resource_type + resource_id + idempotency_key`

canonical request digest 包含 reason、If-Match、batch/source ID、file SHA、mapping/precheck version、publish mode、duplicate resolution 和全部 payload。相同 key + 相同 digest 只在首次事务已 `COMPLETED_SUCCESS` 后精确重放 status/headers/body；相同 key + 不同 digest 返回 409。失败回滚后的相同请求可安全重试，不能把 STARTED 当成功。

统一锁序：

1. source job 或 import batch aggregate；
2. source identity/config revision；
3. watermark（在线来源）；
4. evidence subject locks（employee UUID binary order）；
5. source record keys / import row number；
6. duplicate group keys；
7. recalculation intent keys。

锁内必须二次执行 source-record/exact/near-duplicate、batch token、period token 和 idempotency 回查。不同 key 竞争同一 invariant 时只能一个 winner；loser 无 success audit。

### 13. API 与 OpenAPI

OpenAPI server 保持 `/api/v1`，path item 不带 `/api/v1` 前缀。至少冻结以下 operation families：

- `GET/POST /attendance-sources`
- `GET/PATCH /attendance-sources/{sourceId}`
- `GET /attendance-sources/{sourceId}/watermark`
- `GET/POST /attendance-sources/{sourceId}/devices`
- `GET/POST /attendance-sources/{sourceId}/device-person-bindings`
- `GET /attendance-sources/{sourceId}/documents`
- `GET /attendance-sources/{sourceId}/quarantine`
- `GET/POST /attendance-source-jobs`
- `GET /attendance-source-jobs/{jobId}`
- `POST /attendance-source-jobs/{jobId}/retry`
- `GET/POST /attendance-punch-mapping-profiles`
- `GET /attendance-punch-mapping-profiles/{profileId}/versions`
- `GET /attendance-punch-imports/template`
- `GET/POST /attendance-punch-imports`
- `GET /attendance-punch-imports/{batchId}`
- `PUT /attendance-punch-imports/{batchId}/mapping`
- `POST /attendance-punch-imports/{batchId}/precheck`
- `GET /attendance-punch-imports/{batchId}/preview`
- `GET /attendance-punch-imports/{batchId}/errors`
- `GET /attendance-punch-imports/{batchId}/error-report`
- `GET /attendance-punch-imports/{batchId}/file`
- `GET /attendance-punch-imports/{batchId}/rows`
- `GET /attendance-punch-imports/{batchId}/rows/{rowId}`
- `POST /attendance-punch-imports/{batchId}/publish`
- `POST /attendance-punch-imports/{batchId}/void-or-reverse`
- `POST /attendance-punch-imports/{batchId}/recalculation-intents`
- `GET /attendance-events/{eventId}/evidence`
- `GET /attendance-duplicate-reviews`
- `GET /attendance-duplicate-reviews/{groupId}`
- `POST /attendance-duplicate-reviews/{groupId}/resolve`

所有列表 bounded pagination + stable sort + immutable ID tie-breaker。所有 mutation 要求 CSRF、`Idempotency-Key`、`X-Change-Reason`；已有 aggregate mutation还要求 strong `If-Match`。上传为 multipart file + closed JSON metadata。Jackson 拒绝 unknown properties，OpenAPI request `additionalProperties:false`。

Controller/OpenAPI 以 method + normalized path 双向全等，并逐 operation 校验 path/query/header、multipart part、status、request/response、enum、nullability、401/403/404/409/413/415 和 `X-Correlation-ID`。前后端不得维护另一套状态字符串。

### 14. React 路由、状态与 demo 隔离

生产路由：

- `/sources/online`
- `/sources/oa`
- `/sources/jobs`
- `/sources/attendance-excel`
- `/sources/attendance-excel/:batchId`

页面按服务端 capability 与 scope 决定 route/menu/action；角色 query string、localStorage 和 demo role 不参与授权。不存在组织持续同步 route/button/job。

每个异步页覆盖 loading、empty、error、401/session-expired、403、404；来源任务覆盖 running/retrying/partial/failed/stale；导入覆盖 mapping-required、validating、validation-failed、awaiting-confirmation、processing、partial-success、publish-failed、frozen-blocked、voided、stale If-Match 和 duplicate review。

窄屏 360/390/430px 不产生页面级横向滚动；mapping/row table 转字段卡或局部滚动。Dialog/sheet 支持 focus trap、Escape、restore，触控目标至少 44px。只用 semantic token 与 Tabler 2px。

normal mode 只使用真实 API/MySQL 完成授权读写；demo 在 API client 之前短路并只使用标记明确的合成来源/批次，不产生业务网络请求或 DB 写入。prod/demo 独立 build inventory；真实模式不得 import demo fixtures。

### 15. 文件存储与外部条件

定义 `StoredObjectPort` 与 `MalwareScanPort`。本地开发 adapter 只允许仓库外受控目录、随机 object ID、权限收紧、路径归一化和合成文件；测试 adapter 在内存或临时隔离目录。业务表只保存 opaque object ref、SHA-256、size、MIME、scan status。

生产对象存储、病毒扫描、真实得力/OA endpoint/凭据/脱敏样本保持外部验证项。W4 内部 FINAL 需要 port consumer contract 与 fail-closed adapter test 全绿，但报告必须分别写：

- `DELI_CONTRACT_STUB=PASS`
- `OA_CONTRACT_STUB=PASS`
- `FILE_PORT_CONTRACT=PASS`
- `DELI_LIVE=NOT_VERIFIED`
- `OA_LIVE=NOT_VERIFIED`
- `PRODUCTION_FILE_STORAGE=NOT_VERIFIED`

后三项不是内部 change 的伪失败或伪通过，也不能被省略。

### 16. 完成证据

W4 实施前必须先证明当前 W3 final manifest 为 PASS 且 source/checksum 与 W4 起点一致。任何 W1～W3 retained 漂移使 W4 RED。

W4 完成需要同一 frozen source/runId 下的独立叶子证据：迁移/retained、OpenAPI closure、backend full/W1/W2/W3/W4、frontend typecheck/lint/tests、prod/demo build、XLSX、得力 stub、OA stub、统一证据/并发、权限、MySQL 8.4、normal browser、demo isolation、PAYROLL zero、secret/sensitive scan。独立 reviewer 只读挑战原始证据；manifest 后再做完整性校验。历史 target/dist、H2-only、demo-only、`NOT_VERIFIED`、空 evidence 或被取消命令不能完成任务。

## Risks / Trade-offs

- [真实供应商合同未知] → 以稳定 canonical port + 合成 consumer contract 隔离；未知字段/状态 fail closed，真实联调单列状态。
- [50,000 行严格发布事务较大] → staging/precheck 在事务外完成，发布使用批量 SQL、确定锁序和受控上限；真实 MySQL 记录耗时与锁证据。
- [跨来源并发去重复杂] → 统一 employee evidence lock、唯一键、锁内二次检查和双线程 MySQL 测试。
- [W5 未实现期间状态与重算] → 只读 provider + durable intent；UNKNOWN 阻断，禁止客户端自报或提前写日结果。
- [文件攻击面] → OOXML allowlist、ZIP 限额、公式/宏/外链拒绝、opaque object ref、独立下载权限与审计。
- [粗粒度 employee lock 降低吞吐] → 正确性优先；按 employee binary order 批量锁并用代表性并发/50k 数据评估，必要时后续前向细化而不改事实合同。
- [隔离记录推进水位可能隐藏问题] → 每页保存 quarantine count/原因并产生可观测任务状态；只有不可解析整页或事务失败才不推进。

## Migration Plan

1. 保持 W4 tasks 全未勾选；验证 W3 FINAL、V1～V7 checksum 与 W4 OpenSpec strict。
2. QA 先写 V8/V9 registry、OpenAPI、权限、XLSX、来源 stub、去重/撤销/切分和 UI RED tests。
3. 新增 V8 共享来源/证据层并闭合 H2/MySQL schema、Rows、Mapper/XML、domain 和 source adapters。
4. 新增 V9 punch import 层并实现 template/mapping/precheck/publish/reversal。
5. 实现 capability/scope/audit/idempotency、period/recalc ports 与 OpenAPI。
6. 实现 normal React routes、全部状态、响应式与 demo 独立 adapter。
7. 在空库 V1→V9 与同库 V7→V8→V9→latest 验证 migrate/validate/no-op、retained、约束、并发、最小权限和前后端真实读写。
8. 在隔离 MySQL 8.4.10 与 normal browser 执行完整 frozen-source 验收和独立 review。
9. 全部内部硬门 PASS 后才勾选任务并交接 W5。回滚仅停止 source jobs、关闭 route/feature 和发布前一应用；不删除 raw、批次、审计或已应用迁移，缺陷只用更高版本前向修复。

## Open Questions

- 致远 OA 真实单据字段、状态/撤销/补录版本语义和只读接入方式：不阻塞内部 port，阻塞 `OA_LIVE=PASS`。
- 得力测试租户、官方分页/水位/限流合同、来源 ID 与坐标系：不阻塞内部 port，阻塞 `DELI_LIVE=PASS`。
- 至少一个脱敏异构厂商 `.xlsx` 与 `.xls`/CSV 的长期支持决策：W4 仍只承诺标准 `.xlsx` 和可版本化 mapping。
- 生产对象存储、病毒扫描和保留策略：不阻塞本地受控 adapter，阻塞生产文件链路声明。
