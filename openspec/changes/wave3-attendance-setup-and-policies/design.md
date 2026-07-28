## Context

工程为 Java 21 / Spring Boot / MyBatis / Flyway 模块化单体与 React 19 前端。WAVE-1 提供本地认证、服务端 capability、data scope 与审计；WAVE-2 提供组织、员工、任职、期初导入和通用 policy aggregate。

当前 change 为 WAVE-3 RED，任务全部未完成。`w3-20260726-1917` 为 `INVALIDATED_NON_FINAL`。2026-07-26 已使用授权 least-privilege migrator 只读核验 `shenzhou_hr_dev` 和 `shenzhou_hr_test`：两库 Flyway history 都只包含 V1～V6，V7 未应用。因此本轮可以在首次持久应用前重写 V7；一旦 V7 冻结并应用，后续只能新增迁移。

## Goals / Non-Goals

**Goals**

- 字节级保留 W2 V4 `policy_version` 表列/索引/约束、W2 Java/API DTO 与现有 W2 行。
- 建立独立 W3 法人 scoped policy aggregate，不从 W2 通用 lifecycle 泄漏数据或权限。
- 以稳定 identity、不可变 content revision/version 和只追加 timeline/lifecycle fact 保证历史重放。
- 原子协调 group revision、assignment successor 和三类默认 binding successor。
- 以 employee/date/punches 和只读 authoritative usage provider 完成服务端权威 simulation。
- 形成 Controller/OpenAPI closure、MySQL 8.4、normal browser、W1/W2/W3 retained 和防伪证据硬门。

**Non-Goals**

- 不修改 V1～V6，不扩展 W2 通用 policy 法人字段，不把 W3 template 放入 W2 通用目录。
- 不接入打卡来源，不写 raw facts、正式 day/month result 或正式 exemption usage。
- 不实现 W4、异常/月结、假期、自助、报表、看板或 PAYROLL。
- 不用 H2、MySQL 8.0、demo 或 `NOT_VERIFIED` 替代完成硬门。

## Decisions

### 1. W2 物理、Java 与 OpenAPI 合同冻结

V4 `policy_version` 保持：

- 无 `legal_entity_id`；
- `UNIQUE(template_id, version_number)`；
- 原 effective period CHECK、FK、列 nullability 和列序；
- 通用 `PolicyModels/Rows/Repository/Mapper/XML/Draft/Lifecycle/Application/RecordSupport/Dtos/Controller` 均保持 W2 既有形状；
- W2 OpenAPI request/response 不增加 W3 字段。

W3 attendance 模块不得注入 `PolicyApplicationService` 或 `PolicyRepository` 承载 scoped version。它可以只读 W2 employee/legal-entity authority，但 W2 不反向依赖 attendance。

门禁从 V4 DDL 独立 oracle 重建 expected schema，并对 V6 before 与 target7/latest after 执行 W2 full-row retained subset。任何 W2 列、索引、约束、API schema 或行 hash 漂移使 W3 失败。

### 2. 独立 W3 scoped policy aggregate

V7 新建：

- `attendance_policy_template`：恰好三个 W3 专用 template，不进入 W2 通用 policy catalog。
- `attendance_policy_scope(scope_id PK, policy_template_id FK, legal_entity_id, row_version, audit..., UNIQUE(policy_template_id, legal_entity_id))`。
- `attendance_policy_scoped_version(scoped_version_id PK, scope_id FK, version_number, parameters_json, effective_from, effective_to, validation_json, snapshot_json, snapshot_digest, rollback_of_scoped_version_id, row_version, audit..., UNIQUE(scope_id, version_number))`。`status` 不属于版本列；状态由 append-only lifecycle facts 在指定 business/knowledge time 派生。
- `attendance_policy_lifecycle_event`：只追加 `DRAFT_CREATED/VALIDATED/PUBLISHED/DEACTIVATE_SCHEDULED/ROLLED_BACK` 事实，引用 immutable scoped version、predecessor event、business-effective time、actor/reason/request。
- `attendance_policy_binding_family`：稳定 `(attendance_group_id, policy_kind)` identity，唯一键固定在 group identity + kind，绝不随 group revision rollover 重建 family。
- `attendance_policy_binding_revision`：不可变引用 `attendance_group_revision_id` 与 scoped-version、business effective start、supersedes revision、digest/audit；group revision rollover 只追加同一 family 下的新 revision；不保存或解析 priority。
- `attendance_setup_idempotency`：持久请求/响应重放。

`attendance_policy_binding_revision.attendance_policy_scoped_version_id` 必须 FK 到 W3 scoped version；不得引用 W2 `policy_version`。

三类 W3 template/kind 固定为：

- `MEAL_DEDUCTION`
- `LATE_GRACE`
- `MONTHLY_LATE_EXEMPTION`

group create/enable 对三类各 provision 一个合法默认 binding。任一 scope/version 缺失、非 PUBLISHED、日期不命中或不唯一时返回 `POLICY_MISSING/POLICY_AMBIGUOUS` 并整体回滚。

### 3. 不可变 content 与只追加 effective lifecycle

location/group/assignment/shift/calendar/binding/scoped-policy 的业务 content row 一经插入不更新、不删除。`effective_to/status` 属于历史 content 时不得原地修改；可变 lifecycle metadata 不进入 content digest。

统一模型：

1. stable identity row 只保存 ID、法人、不可变 code；
2. content revision/version 保存完整 snapshot、`effective_from`、`supersedes_*_id`、revision/version number 与 digest；
3. publish/deactivate/rollover 追加 timeline/lifecycle fact；不改旧 fact；
4. 对 business date，resolver 取该 identity 的未分叉 successor chain 中最后一个 `effective_from <= businessDate` 的 fact，并显式断言 exactly one；不是用 `LIMIT 1` 掩盖多行；
5. derived end 是下一 successor 的 `effective_from`，所以旧行无需改 `effective_to`；
6. stable identity lock、唯一 `(identity_id, revision_number)`、唯一 `(identity_id, effective_from)` 和 predecessor 单后继约束防止 timeline 分叉。

future deactivate 追加 `INACTIVE` successor/timeline fact；当前、过去、frozen 或 month-closed 日期拒绝。旧 business date 仍沿旧 timeline 解析，旧 content digest 字节级不变。

Draft edit 也不原地更新 business content：创建新的 immutable draft/scoped-version revision。validation 写独立 validation/lifecycle fact；publish 引用已验证的 immutable content。

### 4. 稳定 attendance identities 与 timeline

V7 使用：

- `location` + `location_revision` + `location_timeline`
- `attendance_group` + `attendance_group_revision` + `attendance_group_timeline`
- `attendance_group_assignment` + `attendance_assignment_timeline`
- `shift_template` + immutable `shift_version` + `shift_publication_timeline`
- `work_calendar` + immutable `work_calendar_version` + `calendar_publication_timeline`
- immutable `work_calendar_day`

group revision 精确引用 immutable `location_revision_id`，并引用 stable `shift_template_id` 与 `work_calendar_id`，不钉 concrete version。location future timezone rollover 若存在引用 group，必须在同一事务生成 matching group successors，或因影响/freeze 不满足而整体拒绝。

configuration digest 包含 legal entity、location revision/timezone、group revision、calendar family/version/day、shift family/version/segments、三类 binding/scoped version 和 monthly context；timeline metadata 不混入 content digest。

### 5. Group rollover 的固定锁序和原子协调

固定锁顺序（所有 create/rollover/shorten/deactivate 路径共用，禁止按查询返回顺序临时加锁）：

`group identity -> current group revision -> affected employee identities（UUID binary order） -> active assignment timelines（assignment ID binary order） -> three binding families（policy kind 固定 MEAL_DEDUCTION、LATE_GRACE、MONTHLY_LATE_EXEMPTION 顺序）`

group create/enable 在一个事务内：

1. 锁 group identity；
2. 验证 location/calendar/shift family 和 exactly-one scoped baselines；
3. 插入 group revision/timeline；
4. 按稳定 group identity + kind 查找或插入三类 binding family，并插入引用当前 group revision 的 binding revision；
5. 完成 idempotency response 与一个 success audit；
6. 任一步失败全部回滚，仅独立 failure audit 留存。

group rollover/收缩/future deactivate 在同一事务：

1. locked current read 与第二次 idempotency 回查；
2. freeze/month-close 和 real impact preview 校验；
3. 插入 successor group revision/timeline；
4. 对跨 boundary assignment 插入明确 successor，指向新 group revision；
5. 在原三个稳定 binding family 下插入 successor revision，指向新 group revision；不得生成第二个同 kind family；
6. 验证 boundary 两侧 exactly-one 且无悬空；
7. 完成 response/audit。

不同 key 并发最多一个 winner；loser 只有 stable failure audit。location rollover 使用下列更外层且唯一的锁序：

`location identity -> current location revision -> affected group identities（UUID binary order） -> 对每个 group 依次执行 group identity -> current group revision -> affected employee identities（UUID binary order） -> active assignment timelines（assignment ID binary order） -> three binding families（固定 kind 顺序）`

所有会引用 location 的 group create/change/rollover/deactivate 都必须先锁 `location identity -> current location revision`，再进入既定 group 锁序。location rollover 从枚举开始即持有这两把外层锁；锁内查询完整引用集合、按 UUID 16-byte binary 升序排序、逐个锁 group identity，并以同一谓词二次查询和比较完整 group-ID 集合。集合不完全相同即 409 且回滚；因此并发 group create/change 不能在快照后提交仍引用旧 location revision 的 group。

location rollover 在外层锁释放前，为每个 group 执行 `group identity -> current group revision -> affected employee identities（binary order） -> assignment timelines（assignment ID binary order） -> binding families（固定 kind 顺序）`。枚举、排序、锁定、二次验证、所有 successor、idempotency completion 与 success audit 处于同一数据库事务且只有一个 commit。任一 freeze/month-close、影响、配置、并发、集合漂移或 successor 校验失败，location 与所有 group 写入及成功审计全部回滚；禁止分批提交或跳过 group。

### 6. Shift 与 calendar publication

shift segment 使用 IANA timezone、本地墙上时间、`startDayOffset/endDayOffset`，offset 只能为 0/1。golden schedule：

- `20:00 day0 WORK -> 01:00 day1`
- `01:00 day1 BREAK -> 01:15 day1`
- `01:15 day1 WORK -> 04:00 day1`

publish 前锁 shift template，分配 immutable version number，验证 non-empty、顺序、无重叠、至少一个 WORK 和同 family publication timeline 无分叉/overlap/gap。publish/deactivate 追加 timeline fact，不修改旧 PUBLISHED content。

calendar family/version 同理。每个 published effective/year version 必须覆盖 interval 内全部 business date；day 唯一。Draft day API 为 PATCH/upsert，只产生新的 draft content，不删除 published day。

day override 可空。未提供时使用 group shift family按 date exactly-one PUBLISHED version；提供时必须是同法人、地点、timezone、business date 有效且 PUBLISHED 的 immutable shift version。

同 calendar family 的 published timeline overlap/gap 拒绝；同年度 rollover 原子。两个 group 可在同一法人/地点/timezone/date 引用不同 calendar families 并得到不同正确 snapshot。

### 7. W3 scoped lifecycle、binding 与 resolution

W3 repository/facade 只读写 `attendance_policy_*`。`ATTENDANCE_SETUP:LEGAL_ENTITY` 贯穿 query、write、DTO、audit 和 negative object-scope test。HR_ADMIN/SYSTEM_ADMIN manage，AUDITOR read-only。

scoped version resolution 条件：

- binding revision 的 derived interval 命中 business date；
- group revision derived interval 命中；
- scoped version 的 `[effective_from,effective_to)` 命中；
- lifecycle timeline 在 business date 为 PUBLISHED 且没有 effective deactivation；
- scope legal entity、group legal entity、kind 一致。

每个稳定 `(attendance_group_id, policy_kind)` 必须恰好一个 binding family；business date 在该 family 下必须恰好解析一个引用当前 group revision 的 binding revision。resolver 返回完整候选集合并断言 cardinality=1；零个或多个均 fail closed。`priority` 不属于 W3 binding 模型，也不存在“同 kind/date 多候选后按 priority 选 winner”的第二套模型。mapper 不返回可能多行的单值，不做无日期 `findPublishedVersionIdByKind`。

binding activation/rollover 必须携带同 canonical digest 的 current impact-preview token。binding period 必须被 group/scoped version derived interval 包含。

month-close provider 未实现前，publish/deactivate/rollover 仅允许 future effective；current/historical 一律 fail closed。失败审计使用独立 transaction/connection，外层 rollback 不得写 success audit。

### 8. Durable idempotency 与锁内 concurrency

唯一键：

`actor_id + operation_code + resource_type + resource_id + idempotency_key`

request digest 使用 canonical JSON，reason、If-Match、legal entity 和所有 payload 字段都进入摘要；correlation/requestId 与 key 分离。

协议：

1. 可选快速回查；
2. 锁 stable resource/family；
3. 第二次回查或占用 STARTED record；
4. locked current read + expectedVersion；
5. mutation/audit-success；
6. 持久保存精确 status、业务 headers、body；
7. commit。

只有已提交成功并处于 `COMPLETED_SUCCESS` 的记录才能 same key/same digest 精确 replay 首次 status/headers/body；same key/different payload 或 reason 稳定 409；different key 竞争 invariant 仅一个 winner。事务失败必须回滚业务 mutation、success audit 和未提交 completion，占位记录不得被误判为可 replay；独立事务写入的 failure audit 以 requestId/correlationId 追踪失败，但不是可重放的业务响应。相同 key/same digest 在失败回滚或可证明过期的 `STARTED` 被安全接管后允许重试。适用于 scoped lifecycle、binding、assignment。查询必须按完整唯一粒度，不能多结果。

### 9. 服务端权威 simulation

Request 仅含：

- `employeeId`
- `businessDate`
- `correctionAsOf`
- typed punches：direction、RFC3339 instant、可选 work-segment association

禁止 client-supplied `lateMinutes`、usage count、policy kind/version、shift/calendar、meal match 或 fixed offset。

流程：

1. capability + employee/legal-entity/object scope；
2. resolver 取得 immutable group/location/calendar/day/shift/三类 policy；
3. 用 resolved IANA timezone 和 actual instant 生成本地跨日语义，EXIT 使用真实 next-day date；
4. 从 scheduled WORK start 与 ENTRY punch 推导 late minutes；
5. 调用 `AttendanceMonthlyExemptionUsageProvider` 读取 employee+nature-month 的 authoritative usage。

W3 不产生正式 usage。默认 runtime provider 基于“W3 内不存在任何正式 usage 写路径”的可验证系统不变量返回 zero-used，并携带 provenance；used/group-change 场景通过可注入只读 provider contract 验证，客户端不能自报。后续计算 change 用真实 read projection 替换 provider，不改 simulation contract。

typed punches 是本次 simulation 的 hypothetical request input，不是服务端 raw fact，也没有 `recordedAt`；它们全部参与本次计算，`correctionAsOf` 不筛选或更正这些输入。`correctionAsOf` 是服务端配置与 authoritative usage projection 的知识时态：必须是带 offset 的 RFC3339 instant，不能早于 business date 在 resolved timezone 的日开始，也不能晚于请求处理时刻；resolver 只读取 `recorded_at <= correctionAsOf` 且业务有效期命中 businessDate 的配置和 usage knowledge。配置/usage 的未来知识不可见。同一 employee/businessDate/correctionAsOf/typed-punch input 必须产生同一 snapshot digest。

迟到组合合同固定如下，`rawLateMinutes=max(0, firstValidEntry-scheduledWorkStart)`，不允许客户端提供：

| rawLateMinutes | LATE_GRACE（enabled, grace=15） | MONTHLY_LATE_EXEMPTION（enabled, max=15, uses=1） | authoritative used | result | consume |
|---:|---|---|---:|---|---:|
| 0 | 不形成迟到候选 | 不评估 | 0/1 | `ON_TIME` | 0 |
| 1..15 | 形成 grace-eligible 候选 | 阈值一致且可豁免 | 0 | `EXEMPTED` | 1（仅语义预测，不在 W3 写正式 usage） |
| 1..15 | 形成 grace-eligible 候选 | 阈值一致但次数已用 | 1 | `LATE` | 0 |
| >=16 | 超出 grace | 不得覆盖 grace 结果 | 0/1 | `LATE` | 0 |

优先级是先用 `LATE_GRACE` 判定候选窗口，再用 `MONTHLY_LATE_EXEMPTION` 判定该候选能否豁免；monthly policy 不能把超出 grace 的迟到改为豁免。两个 enabled policy 的阈值必须一致，`monthlyUses` 在 W3 只允许 1，`resetOnGroupChange` 必须为 false；不一致、缺失、禁用组合或 usage provenance/knowledge-time 不满足时 simulation fail closed 并返回真实 issue，不猜默认值。边界因此为 0 不匹配，1/15 在未使用时匹配，16 不匹配且不消费；group change 不重置 employee+nature-month identity。全部 simulation read-only，业务/审计正式状态 delta=0。

### 10. API、OpenAPI、DTO 与前端

OpenAPI server `/api/v1`；path item 无 `/api`。Controller mapping 与 OpenAPI 以 method + normalized template 双向精确集合比较，并逐 operation 校验 query/path/header、status、request/response schema、401/403、`X-Correlation-ID`。

所有 mutation 要求 `Idempotency-Key` 与 `X-Change-Reason`；已有 aggregate mutation还要求 strong `If-Match`。required primitive 使用 nullable wrapper + Jakarta validation。Jackson request 全局拒绝 unknown property；OpenAPI request `additionalProperties:false`，不使用冲突 closed `allOf`。

所有非固定三项 catalog list 使用 bounded pagination、稳定 sort key 与 immutable ID tie-breaker。

前端 route：

- `/rules/attendance-groups`
- `/rules/shifts`
- `/rules/calendars`
- `/rules/attendance-policy/:versionId?`

`/`、`/login`、`/rules*`、四个 W3 直达页只按 V2 真实角色 `HR_ADMIN`、`SYSTEM_ADMIN`、`AUDITOR` 的 role×capability×menu matrix 决策；authorized menu 不为空，AUDITOR 只跳只读允许页，W3 breadcrumb 不依赖 `POLICY:READ`。未知 client/API route 真实 404。不得在 W3 验收中制造 `HR_MANAGER/HR_OPERATOR/EMPLOYEE` 测试角色。

AUDITOR 无 simulation/impact/manage affordance且服务端拒绝。lifecycle UI 展示服务端 status/issues/conflicts，不硬编码 `PUBLISHED`。normal mode 有可用 proxy/启动说明，demo 在业务 API 前短路，prod/demo 独立 dist。

治理修复以语义为准：不机械替换 `===`、`.map` 或拆字符串；a11y 以真实 label/name/keyboard/axe 验证；上传 extension/MIME/content 前后端一致并合理处理空 MIME。

### 11. 独立 seed oracle

固定 baseline legal entity：`30000000-0000-0000-0000-000000000001`。V7 只能对这个明确 ID 条件 seed，绝不使用 `MIN(legal_entity_id)`；该 ID 不存在时不得选择其他法人，group provisioning fail closed，部署 bootstrap 必须显式创建 scope。

固定 IDs：

| kind | templateId | scopeId | scopedVersionId |
|---|---|---|---|
| MEAL_DEDUCTION | `25000000-0000-0000-0000-000000000001` | `25100000-0000-0000-0000-000000000001` | `25200000-0000-0000-0000-000000000001` |
| LATE_GRACE | `25000000-0000-0000-0000-000000000002` | `25100000-0000-0000-0000-000000000002` | `25200000-0000-0000-0000-000000000002` |
| MONTHLY_LATE_EXEMPTION | `25000000-0000-0000-0000-000000000003` | `25100000-0000-0000-0000-000000000003` | `25200000-0000-0000-0000-000000000003` |

Oracle 使用 RFC 8785-compatible UTF-8 canonical JSON、sorted object keys、array order preserved、无额外空白，SHA-256 小写 hex。`MONTHLY_LATE_EXEMPTION.parameters` 明确且仅含 `enabled:true, graceMinutes:15, monthlyUses:1, resetOnGroupChange:false`；`enabled` 必须包含。scoped-version 本体没有 `status` 列，PUBLISHED 由 seed lifecycle fact 派生。三个完整 snapshot golden vector 由 verification fixture 常量保存，不从 V7 SQL读取 expected：

- MEAL：`4279b3f40121f995d09245f3450e1087b4d5757e237ded4f8d4e0343fb210981`
- LATE：`ea11c63a991838a20b61d6d3e9d0281035263b8529b2fb767fa0b2e631306ce9`
- MONTHLY：`b0a533500852c464c7065812fd56519f0c571ebbf453834a8b189388e29f1a51`

当前 V7 的 `maximumLateMinutes/minimumLateMinutes`、旧 MONTHLY digest `643f6bff05feddc128e0f0aaf6fe52da47058f7e629a63ca50ce40ce91925310` 和任何 scoped-version `status` 列都是任务 1.2/1.5 的首批 RED；不得迁移为 expected 或复用旧 digest。

完整 canonical JSON 字符串在 verification spec 声明。target7 gate 断言恰好 +3 W3 templates、+3 scopes、+3 PUBLISHED v1 scoped versions且无 extras。

### 12. Retained canonicalizer 与迁移

W2/W3 共用 registry。registry 必须显式枚举 W2 retained 表及精确 PK/列序：

- `policy_template(template_id)`：`template_id,template_code,name,description,field_definitions_json,status,row_version,created_by,created_at,updated_by,updated_at`
- `policy_version(version_id)`：`version_id,template_id,version_number,status,parameters_json,effective_from,effective_to,change_reason,validation_json,snapshot_json,snapshot_digest,rollback_of_version_id,row_version,created_by,created_at,published_at,updated_by,updated_at`
- `policy_scope_binding(binding_id)`：`binding_id,version_id,scope_type,scope_resource_id,priority,effective_from,effective_to,row_version`
- `policy_publication_record(publication_id)`：`publication_id,template_id,version_id,action,reason,actor_id,request_id,result,occurred_at,snapshot_digest`
- `policy_rollback_record(rollback_id)`：`rollback_id,template_id,source_version_id,target_version_id,created_version_id,reason,actor_id,request_id,result,occurred_at`
- `audit_event(event_id)`：`event_id,occurred_at,actor_id_ref,actor_type,action_code,resource_type,resource_id_ref,scope_digest,purpose_code,result_code,reason_code,policy_version,before_digest,after_digest,correlation_id,request_id,previous_hash,event_hash,anchor_ref`。这是 V3 加入 `request_id` 后的最终 V6 真实形状，不得退回 V1 列表。

W3 expected registry 固定在 review-owned `specs/wave3-verification/oracles/w3-retained-registry-v1.json`；它枚举 location/group/revision/assignment、shift/calendar/timeline、scoped policy/binding 与 idempotency 的所有表、PK、列序和逻辑类型。被测 V7、current schema、H2 schema 或运行时生成 registry 都只能作为 actual，严禁从它们反推或改写 expected。启动时 expected 与 information_schema 双向全等，未知/缺失/换序/类型漂移均 RED。实际迁移文件名唯一固定为 `backend/src/main/resources/db/migration/V7__attendance_setup_and_base_policies.sql`；计划、脚本和证据不得使用另一 V7 名称。行 framing：

每个 field 使用 `tag:utf8-byte-length:value`；row 使用 `R:table-utf8-byte-length:table:field-count:field-frames`；snapshot line 使用 `L:row-utf8-byte-length:row:64:lowercase-row-sha256`。delimiter 只可能是 length-framed payload，绝不承担分隔语义。

规则：

- NULL=`N:0:`；empty string=`S:0:`，不可混同；
- UTF-8 NFC，保留大小写与尾随空格；
- JSON 按 RFC 8785-compatible canonical form；
- DATE=`YYYY-MM-DD`；DATETIME/TIMESTAMP 转 UTC、6 位微秒、`Z`；
- DECIMAL 用无指数最短精确十进制，保留数值而非显示 scale；
- integer/boolean 分别 `I`/`B(0|1)`；
- binary 为小写 hex；
- PK 与 rows 按 binary byte order 排序；
- row hash=`SHA256(framed full row)`；final hash 输入是按稳定 PK 顺序拼接的完整 snapshot-line bytes，无额外分隔符或平台换行。

独立 review-owned fixture `openspec/changes/wave3-attendance-setup-and-policies/specs/wave3-verification/oracles/retained-canonical-golden-v1.json` 固定至少两表/三行/多列的每个 field frame、row frame、row hash、排序后的 snapshot lines 与 final hash；expected 由规范常量维护且禁止从 SQL、registry 或被测 canonicalizer 生成。golden vectors覆盖 NULL/empty、Unicode 组合字符、JSON key order、decimal（`1.2300` 与 `1.23` 数值等价）、UTC microseconds、boolean、binary 和包含 `|:=` 的 payload；canonicalizer `--self-test` 在 `LC_ALL=C,TZ=UTC` 及另一可用 locale/non-UTC timezone 下必须 exact-diff 同一 fixture。

W2 public oracle 必须是 repo-relative、W2 范围隔离的 structured parser/export/exact-diff，而非整文件 SHA 清单。固定 extractor 只读取明确列出的 W2 Java、W2 `PolicyMapper.xml`、W2 OpenAPI operations/schemas 及 H2 V4 `policy_*`/最终 V6 `audit_event`，排除 attendance/W3，输出排序 JSON且不含绝对路径；`--self-test` 用内置微型 fixtures 验证 Java public signature/record、XML statement/result/column、OpenAPI 和 DDL/CHECK parser。V4 CHECK 精确为 `effective_to IS NULL OR effective_to >= effective_from`，最终 V6 `audit_event` 含 V3 `request_id`。fixture/extractor 只能新版本更新并由独立 W2 reviewer 重新预锚定 SHA256。

同一 test DB：

1. empty→V6，插入独立固定 test tenant fixture；
2. 取得 W2 retained before；
3. migrate target7；
4. 在任何 child latest migrate 前取得 W3 target7 snapshot；
5. 验证 seed oracle；
6. target7→latest，允许 V8+；
7. W2 before⊆latest、W3 target7⊆latest；
8. repeat/no-op fingerprint。

另执行真实 V6 persistent-shape→target7→latest。outer orchestrator 拥有 DB identity/runId，child 不得抢先 migrate latest。

### 13. MySQL 8.4 与证据

现有 `/usr/local/mysql` 8.0.34（PID/version/socket/datadir/service）只读记录 before/after，绝不停止、改 PATH/symlink/datadir/launchd、占用 3306 或 `/tmp/mysql.sock`。

官方 MySQL 8.4.10 source tarball 固定文件名 `mysql-8.4.10.tar.gz`、URL `https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.10.tar.gz`、SHA256 `d57a6730baef14ae118f7f4a6e02845b5b50933758df61fb06e104f27ccc8f96`。隔离 basedir/datadir/socket/pid/log，绑定 `127.0.0.1:13306`，只用绝对路径。真实 `SELECT VERSION()` 的 SemVer core 必须精确等于 `8.4.10`；8.4.0～8.4.9、8.4.11+ 或任意其他 8.4.x 均 RED。

source tree hash 及 tasks 完成协议：

- include `backend/src`, `frontend/src`, `frontend/public`, `api`, `openspec/changes/wave3-attendance-setup-and-policies`, `scripts/qa`, `deploy/mysql`；
- exclude build outputs、run artifacts、logs、node_modules、target、dist、`.git`；
- realpath 必须位于 repo root，symlink 越界拒绝；
- path 使用 repo-relative UTF-8 NFC、`/` separator、binary sort；
- file record=`len(path):path|size|sha256(content)`，tree hash 为 records 连结后的 SHA-256。
- `tasks.md` 进入 hash 前只把 checkbox token `- [ ]`/`- [x]` 规范化为 `- [~]`，其他字节仍参与 hash；任务勾选状态另写 run root 中 detached completion metadata。只有 FINAL-GATE 已由全部 children 推导 PASS 后才可从该 metadata 回写 checkbox；回写后重算 normalized tree hash必须不变，非 checkbox 内容变化仍使 run 失效。

run 记录 START/END；结束时重算 tree hash且必须等于开始值。artifact realpath 必须在当前 run root，mtime 位于窗口内。唯一可执行证据顺序为：固定 20 个 pre-review leaf gates → `artifact-registry.json` → 互不哈希的 `acceptance-matrix.json`/`WAVE3-VERIFICATION.md` → 只挑战这 20 项及原始证据的独立 review → 单向哈希 registry/matrix/report/review/detached metadata 的 `final-manifest.json` → post-manifest `W3-VER-EVIDENCE-INTEGRITY` 机械校验 → 从 20+review+integrity 共 22 项派生 FINAL。review 不挑战自身、manifest、integrity 或 FINAL；integrity 不修改 manifest；任何被 manifest 哈希的节点不得反向引用 manifest SHA256。报告时间晚于相关 source 最大 mtime。

W3 PASS 需要独立语义 evidence、W1/W2/W3 regression、MySQL 8.4、normal browser 六视口、prod/demo、PAYROLL 零可发现和独立复核全部 PASS；`NOT_VERIFIED/null` 使整体 RED。

### 14. 当前实现阶段的首批 RED

strict 规格 PASS 不放行既有实现。TDD 首批 RED 必须证明：`AttendancePolicyMapper`/XML 与 lifecycle Controller/OpenAPI 不得读写或复用 W2 `policy_*`/`PolicyDtos`；V7/H2 必须拆为 `attendance_policy_binding_family`/`attendance_policy_binding_revision`；旧 `Wave3MigrationContractTest` 的 ALTER/seed W2 断言必须重写；outer orchestrator 不得在 target7 snapshot 前调用会 migrate latest 的 W2 child 且须支持 V8+；当前 V7 的 `maximumLateMinutes/minimumLateMinutes` 与旧 digest 是待修复项，最终 seed 只接受 `graceMinutes` 并重新独立预计算 digest。

### 15. 完成与交接

`isComplete:true` 只表示 OpenSpec 工件齐全。任务只在对应硬门真实 PASS 后勾选。任何 source/migration/spec 变化使先前 run invalid。

W3 PASS 后本 change 不实现或修改 W4，也不在此处归档；自动交接到下一 W4 change 继续用户总目标。W3 RED 时 W4 始终冻结。

## Risks / Trade-offs

- 表数增加：这是 W2 隔离与不可变 timeline 的最低复杂度，使用明确 aggregate/lock order/registry 约束。
- MySQL 无 exclusion constraint：stable identity lock + predecessor 单后继 + current read + MySQL 双线程证据共同保证。
- baseline tenant 固定：明确 ID 存在时才 seed；不存在绝不猜测，部署 bootstrap 显式处理。
- W3 无正式 usage：authoritative provider 以可替换只读端口表达，客户端无自报字段，W3 runtime 基于“无正式写路径”证明 zero-used。
- 本机 8.4 source build 成本高：它是完成硬门，失败保持 RED，不干扰现有 8.0。

## Migration Plan

1. 保持 tasks 全未勾选，严格校验 OpenSpec。
2. 恢复 W2 Java/H2/OpenAPI/V4 形状并先运行 W2 retained/API 红绿门。
3. 因 V7 未持久应用，重写 V7 为独立 W3 aggregate；H2/Rows/Mapper/OpenAPI 同步。
4. 按 identity/timeline、group atomic rollover、shift/calendar、scoped policy/idempotency、authoritative simulation 的顺序 TDD。
5. 闭合 Controller/OpenAPI 与 frontend/runtime。
6. 冻结 V7 后确定 checksum，完成 retained/oracle/MySQL 8.4 gate。
7. 最终冻结 source，创建全新 runId，运行完整 backend/frontend/build/normal browser/PAYROLL/evidence/independent review。
8. 全部硬门 PASS 才逐项勾选并交接 W4；不做破坏性 down migration。
