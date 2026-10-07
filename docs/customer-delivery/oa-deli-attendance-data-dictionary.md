---
title: OA 与得力考勤数据字典及只读查询合同
updated: 2026-08-16
repository_root: /Users/huzhijin/Downloads/shenzhouHR
code_head: e32ae0740825069ec3b2badaf9b5f9e57c6431e3
database: MySQL 8 / Flyway V1-V40
document_status: portable-reference
worktree_status: dirty; this document and some cited delivery/wiki artifacts are not present in code_head alone
---

# OA 与得力考勤数据字典及只读查询合同

> 用途：提供给 OA、其他项目、实施人员或代码 Agent，作为神州 HR 中“得力打卡数据”和“OA 考勤单据候选字段”的单文件读取入口。
> 基线：2026-08-16 当前工作树；Git HEAD 为 `e32ae0740825069ec3b2badaf9b5f9e57c6431e3`。
> 安全：本文不包含数据库地址、账号、口令、App Key、Secret、真实姓名、真实工号或精确坐标样本。
> 路径：文末同时给出仓库相对路径和当前机器绝对路径。复制到其他项目后，绝对路径仅用于追溯原始机器，日常应按仓库相对路径和类名/表名重新定位。
> 可复现性：本文依据当前工作树而非仅 Git HEAD；单独克隆上述 commit 不能恢复未跟踪的 `docs/customer-delivery/`、`.llm-wiki/` 或被忽略的 `tmp/` 转写材料。跨项目交付时应把本文及批准的依赖文件作为同一脱敏快照归档。
> 引用安全：部分本机源文件仍含示例化人员信息或内部联调地址/账号注释。本文没有复制这些值；不得把被引用文件整份外发，外部交付前必须另做脱敏和授权审查。

## 1. 先看结论

### 1.1 得力打卡究竟存在哪张表

得力 E+ 打卡**没有单独的 `deli_punch` 表**，而是进入统一考勤证据链：

```text
attendance_source（source_type = DELI_CLOUD）
  → raw_attendance_fact                 原始打卡事实
  → normalized_attendance_record        规范化记录
  → employee_match_decision             员工/任职匹配结果
  → effective_attendance_event          有效打卡事件
  → effective_event_lifecycle_fact      有效事件生命周期
  → evidence_link                       原始到有效事件的血缘
      ├→ attendance_recalculation_intent 重算意图（当前无生产消费者）
      └→ 报表计算器 → attendance_report_daily_fact
```

不同用途应读取不同表：

| 用途 | 应读取的对象 | 原因 |
|---|---|---|
| 审计已持久化的原始接收记录 | `raw_attendance_fact` | 保留来源 ID、系统版本、规范化时间文本、时区和摘要；也包含已持久化的未匹配/隔离记录，但不是所有上游失败输入的完备日志 |
| OA 获取可用于业务的打卡流水 | `effective_attendance_event` | 只包含已匹配并通过权威配置、期间保护的有效事件；还必须检查最新生命周期为 `ACTIVATED` |
| 证明事件来自得力 | `evidence_link → raw_attendance_fact → attendance_source` | `effective_attendance_event` 本身不保存厂商来源；同一事件可能有多条证据，应使用 `EXISTS` 避免重复行 |
| 查看员工匹配失败 | `normalized_attendance_record + employee_match_decision` | 可区分 `VALID/QUARANTINED`、`MATCHED/UNMATCHED/AMBIGUOUS` |
| 查看日报首卡/末卡 | `attendance_report_daily_fact` | 这是投影汇总，不是完整打卡流水，不能还原所有物理打卡 |

因此，给 OA 提供打卡明细时，**不要直接开放 `raw_attendance_fact`，也不要用 `attendance_report_daily_fact` 冒充完整流水**。推荐使用第 5 节的最小只读查询或由 DBA 封装成版本化视图。

### 1.2 OA 源单据字典的当前性质

仓库记录了 7 类 OA 考勤表单、11 张候选物理表、104 个候选业务字段：102 个来自截图转录，另有 2 个系统小时字段来自 2026-08-14 用户确认的业务合同。

| 表单 | 物理表 |
|---|---|
| 出差 `TRIP` | `formmain_0265` |
| 请假 `LEAVE` | `formmain_0170` |
| 销假 `LEAVE_REVOCATION` | `formmain_0370` |
| 加班 `OVERTIME` | `formmain_0171`、`formson_0172` |
| 外出 `OUTING` | `formmain_0251`、`formson_0252` |
| 免打卡 `EXEMPT_PUNCH` | `formmain_0201`、`formson_0202` |
| 补卡 `PUNCH_CORRECTION` | `formmain_0203`、`formson_0204` |

这些字段与 Java 静态目录一致，但其状态仍是：

- 业务字段来自 OA 截图转录或用户明确的系统小时字段合同，属于候选字典；
- 2026-08-10 实库取证批次为 `PARTIAL`，不能更新为正式 `VERIFIED`；
- 实库只确认了部分加班字段以及 `org_member`、`col_summary`、`ctp_enum_item` 的相关列形状；
- 业务已确认请假 `formmain_0170.field0097` 与销假 `formmain_0370.field0099` 精确关联；一张请假可以有多张销假，每张表达一个连续返还区间，已批准区间不得重叠，累计 `field0107` 不得超过原请假已扣减 `field0103`；物理列、审批过滤和当前版本归并仍待实库验证；
- 审批状态业务含义、全部主从关系、封闭枚举、OA 时间语义和日期边界仍待正式签字；
- 当前代码没有七类单据的生产查询和同步服务。

所以第 6—9 节的 OA 内容应称为“**候选业务字段字典与取证边界**”，不能称为已经上线的完整 OA 物理 DDL。

## 2. 状态词和时间约定

| 状态 | 本文含义 |
|---|---|
| `CURRENT_CODE` | 当前 Java、Mapper 或 Flyway 直接证明的现行行为/结构 |
| `REVIEWED_TECHNICAL_EVIDENCE` | 实库截图或只读 SQL 已确认列形状、数量或样本分布，但不自动证明业务含义 |
| `SCREENSHOT_DECLARED` | 字段由 OA 表单截图转录，尚非实库签字合同 |
| `NOT_VERIFIED` | 结构、状态、枚举、外键、时区或边界尚未完成责任方签字 |
| `PROPOSED_OUTBOUND_CONTRACT` | 本文为 OA 提供的建议查询合同，当前仓库尚未创建对应数据库视图/API |
| `FAIL_CLOSED` | 未知、缺失、多匹配或矛盾时不猜测、不计入有效考勤 |

时间约定：

- 得力 `check_time` 是 Unix epoch 秒，代码转换为 Java UTC `Instant`；
- `source_time_text` 保存 `check_time` 经整数解析后再格式化的规范十进制 epoch 秒文本，不是载荷的字节级原文；
- `source_time_zone` 保存来源配置的 IANA 时区，默认配置为 `Asia/Shanghai`；
- 生产部署 JDBC 明确配置 UTC 会话，数据库 `DATETIME` 列自身不携带时区标签；
- 对外时间窗口统一使用半开区间 `[from_utc, to_utc)`；
- `CONVERT_TZ(point_instant, '+00:00', '+08:00')` 可用于中国标准时间显示；
- 中国自然日期不等于跨午夜班次的权威业务日期。当前正式月报仍按上海自然日简化分组，不能让 OA 把自然日自行解释为最终班次归属日。

## 3. 得力 E+ 上游接口与字段字典

### 3.1 员工目录接口

接口：`POST /v2.0/employee/query`，使用 `offset`、`limit` 分页，单页最大 100。目录先完整拉取，再开始打卡分页。

| 得力字段 | 必填 | 逻辑类型 | 系统用途 | 是否独立落库 |
|---|---:|---|---|---|
| `id` | 是 | 字符串/整数标识 | 得力用户 ID，建立内存映射 `user_id → employee_num` | 不保存目录快照；可能通过确认绑定表保存为 `deli_user_id` |
| `employee_num` | 是 | 精确字符串 | 员工工号；打卡匹配的首选输入 | 不直接保存在 raw 打卡行；成功后通过本地 `employee_id` 关联员工 |

无可用 `id` 或 `employee_num` 的目录行会跳过。当前目录只形成当次同步的内存映射，没有专门的“得力员工目录快照表”。

### 3.2 打卡接口

接口：`POST /v2.0/cloudappapi`。

固定请求头：

- `Api-Module: CHECKIN`
- `Api-Cmd: checkin_query`
- `App-Key`、毫秒时间戳和签名头

请求体：

| 字段 | 含义 |
|---|---|
| `next_id` | 内部/数据库按规范无符号十进制文本保管；请求时编码为任意精度 JSON integer，不自增 |
| `page_size` | 单页条数，1—500 |

响应要求：顶层 `code=0`；`data.data` 为数组；`data.next_id` 为合法游标；页面不得超过请求上限。

### 3.3 得力打卡字段到系统字段的完整血缘

| 得力字段/派生值 | 必填 | 系统字段 | 转换和落库含义 |
|---|---:|---|---|
| `id` | 是 | `sourceRecordId` | 来源打卡唯一编号；落 `raw_attendance_fact.source_business_key` |
| 系统计算的记录摘要 | 派生 | `sourceVersion` | 落 `raw_attendance_fact.source_version`；它是系统 SHA-256，不是得力提供的业务版本号 |
| `user_id` | 是 | 人员外部引用备选 | 无有效 `ext_id` 时作为 `DELI_USER_ID` 参与匹配；不作为 raw 独立列保存 |
| `ext_id` | 否 | `externalPersonRef` | 有值时优先于 `user_id`，类型为 `DELI_EXT_ID`；不作为 raw 独立列保存 |
| 员工目录中的 `employee_num` | 首选 | `employeeNumber` | 先用 `user_id` 查询完整目录映射，按精确字符串匹配本地员工版本 |
| `check_data.employee_num` | 回退 | `employeeNumber` | 目录无工号时才使用；只在 `check_data` 是可解析 JSON 编码字符串时读取 |
| `terminal_id` | 是 | `deviceRef` | 参与校验、`sourceVersion` 和原始摘要；当前没有独立事实列，不能从数据库直接查询原值 |
| `check_type` | 是 | `verificationMethod` | 参与校验、`sourceVersion` 和摘要；不转换为上班/下班方向，也没有独立事实列 |
| `check_time` | 是 | `punchInstant` | Unix epoch 秒转换为 UTC `Instant`，分别落 `raw.source_instant`、`normalized.point_instant`、`effective.point_instant` |
| `check_time` 规范文本 | 派生 | `originalTimeText` | 先解析为 `long`，再用 `Long.toString` 生成规范十进制文本，落 `raw_attendance_fact.source_time_text`；不是字节级原文 |
| 来源配置时区 | 配置 | `sourceTimeZone` | 落 `raw_attendance_fact.source_time_zone`；不是单条载荷字段 |
| 固定值 `AUTO` | 派生 | `direction` | 落规范化记录和有效事件的 `normalized_direction`；`AUTO` 不等于已判断上班卡/下班卡 |
| `check_data` | 否 | 摘要输入 | 外层 JSON 节点序列化后计算 SHA-256，正文不落库 |
| `check_data` 是否存在 | 派生 | `forbiddenPayloadDropped` | 非 `NULL` JSON 节点（包括空字符串）会置 `true` 并仅进入原始摘要；表示该节点正文未持久化，不证明存在非空敏感内容；没有独立数据库列 |
| 地点摘要 | 当前固定空 | `locationSummary` | 当前适配器写 `NULL` |
| 坐标系 | 当前固定 | `coordinateSystemTag` | 当前适配器为 `UNKNOWN`；V22 虽有坐标列，当前打卡事务未写原始坐标 |

`sourceVersion` 的逻辑输入为：

```text
SHA-256(
  id,
  user_id,
  ext_id,
  terminal_id,
  check_type,
  check_time_epoch,
  serialized_check_data_node_digest
)
```

这不是可直接照抄的字符串拼接公式。真实算法按上述顺序处理字段；`NULL ext_id/check_data digest` 先转为空字符串，然后对每个 UTF-8 字段写入 4 字节大端长度前缀及字段字节，最后计算 SHA-256。跨项目需要重算时必须复用代码或逐字节实现这一 framing。

重要限制：仅凭 `raw_attendance_fact` 无法恢复 `terminal_id`、`check_type`、`user_id/ext_id` 或 `check_data` 正文；这些值只参与匹配或单向摘要。

## 4. 得力本地落库表与字段含义

### 4.1 来源与同步控制表

#### `attendance_source`

| 字段 | SQL 类型 | 含义 |
|---|---|---|
| `attendance_source_id` | `VARCHAR(36)` | 来源主键 |
| `company_id` | `VARCHAR(36)` | 公司边界；V8 原名 `legal_entity_id`，V11 已改名 |
| `source_code` | `VARCHAR(64)` | 公司内稳定来源编码 |
| `source_type` | `VARCHAR(32)` | 得力来源固定为 `DELI_CLOUD` |
| `display_name` | `VARCHAR(100)` | 来源显示名称 |
| `status` | `VARCHAR(32)` | `ACTIVE` 或 `INACTIVE` |
| `row_version` | `BIGINT UNSIGNED` | 乐观锁版本 |
| `created_by` | `VARCHAR(36)` | 创建主体 |
| `created_at` | `DATETIME(6)` | 创建时间 |

#### `attendance_source_config_revision`

| 字段 | 含义 |
|---|---|
| `source_config_revision_id` | 配置修订主键 |
| `attendance_source_id` | 所属来源 |
| `revision_number` | 来源内递增修订号 |
| `endpoint_kind` | 得力固定为 `DELI_EPLUS_CHECKIN_QUERY` |
| `source_time_zone` | IANA 来源时区 |
| `page_size` | 打卡分页大小 |
| `rate_limit_per_minute` | 每分钟限流 |
| `backoff_seconds` | 重试退避秒数 |
| `secret_reference_name` | 密钥引用名；不是 Key/Secret 明文 |
| `adapter_settings_json` | 适配器策略，如确认绑定和敏感正文丢弃 |
| `effective_from` | 修订生效时刻 |
| `supersedes_config_revision_id` | 被替代配置 |
| `snapshot_digest` | 配置快照摘要 |
| `change_reason`、`created_by`、`created_at` | 变更审计字段 |

#### 同步作业表

| 表 | 关键字段 | 含义 |
|---|---|---|
| `attendance_sync_job` | `status`、`page_count`、`accepted_count`、`quarantined_count`、`safe_error_code` | 一次来源同步作业及最终计数 |
| `attendance_sync_job_page` | `page_number`、`input_cursor`、`next_cursor`、`record_count`、`page_digest`、`committed_at` | 已原子提交的单页事实 |
| `attendance_sync_watermark` | `committed_cursor`、`committed_page_digest`、`row_version`、`committed_at` | 来源已提交水位；只能在整页成功提交后推进 |

作业计数不能直接与持久化行数等同：相同来源身份的安全重放会计为 `accepted`，但不会新增 raw；期间状态无法确定的记录会在 raw 插入前计为 `quarantined`；如果非空页面全部隔离，整页会以 `ALL_RECORDS_QUARANTINED` 拒绝并回滚。因此 `quarantined_count` 与数据库中 `validation_status='QUARANTINED'` 的行数不一定相等，`raw_attendance_fact` 也不是所有失败输入的完备日志。

### 4.2 `raw_attendance_fact`：原始证据层

这是回答“得力原始打卡存在哪”的主表。当前最终结构需要组合 V8 建表、V11 公司字段改名和 V22 坐标扩展理解。

| 字段 | SQL 类型/可空 | 得力打卡含义 |
|---|---|---|
| `raw_attendance_fact_id` | `VARCHAR(36) NOT NULL` | 内部原始事实主键 |
| `attendance_source_id` | `VARCHAR(36) NOT NULL` | 得力来源 ID |
| `company_id` | `VARCHAR(36) NOT NULL` | 公司边界；V11 后最终列名 |
| `fact_kind` | `VARCHAR(32) NOT NULL` | 得力打卡固定 `PUNCH_POINT` |
| `source_business_key` | `VARCHAR(191) NULL` | 得力打卡记录 `id` |
| `source_version` | `VARCHAR(128) NULL` | 系统按原始字段计算的 SHA-256；不是得力业务版本号 |
| `stable_fingerprint` | `CHAR(64) NULL` | 无来源键时的稳定指纹；当前得力写 `NULL` |
| `source_time_text` | `VARCHAR(128) NOT NULL` | 得力 `check_time` 经整数解析后的规范十进制 epoch 秒文本，不是字节级原文 |
| `source_time_zone` | `VARCHAR(64) NOT NULL` | 来源配置时区 |
| `source_instant` | `DATETIME(6) NULL` | 转换后的 UTC 打卡时点 |
| `interval_start` | `DATETIME(6) NULL` | 区间开始；打卡点为 `NULL` |
| `interval_end` | `DATETIME(6) NULL` | 区间结束；打卡点为 `NULL` |
| `longitude_raw` | `VARCHAR(64) NULL` | 原始经度文本；当前得力适配器不填 |
| `latitude_raw` | `VARCHAR(64) NULL` | 原始纬度文本；当前得力适配器不填 |
| `source_coordinate_system` | `VARCHAR(16) NOT NULL` | `WGS84/GCJ02/BD09/UNKNOWN`；当前默认 `UNKNOWN` |
| `coordinate_validation_status` | `VARCHAR(32) NOT NULL` | 坐标校验状态；当前默认 `MISSING` |
| `coordinate_conversion_status` | `VARCHAR(24) NOT NULL` | 坐标转换状态；当前默认 `NOT_APPLICABLE` |
| `map_longitude` | `DECIMAL(11,8) NULL` | WGS84 展示经度；当前为空 |
| `map_latitude` | `DECIMAL(10,8) NULL` | WGS84 展示纬度；当前为空 |
| `map_coordinate_system` | `VARCHAR(16) NULL` | 地图坐标系；有效转换时为 `WGS84`，当前为空 |
| `canonical_payload_digest` | `CHAR(64) NOT NULL` | 原始证据规范摘要，用于幂等和来源身份冲突检测 |
| `raw_object_ref` | `VARCHAR(191) NULL` | 外部原始对象引用；当前得力写 `NULL` |
| `request_id` | `VARCHAR(64) NOT NULL` | 本次同步请求/关联 ID |
| `received_at` | `DATETIME(6) NOT NULL` | 系统接收时刻 |
| `created_by` | `VARCHAR(36) NOT NULL` | 写入主体；定时任务当前传字面量 `SYSTEM`，但迁移未创建同名 `auth_principal`，见本节阻塞项 |

唯一性：

- `(attendance_source_id, source_business_key, source_version)` 唯一；
- `(attendance_source_id, stable_fingerprint)` 唯一；
- 相同来源身份和相同摘要是安全重放；相同身份但摘要不同会触发 `DELI_SOURCE_IDENTITY_COLLISION`，不覆盖旧行。

### 4.3 `normalized_attendance_record`：规范化层

| 字段 | SQL 类型/可空 | 得力打卡含义 |
|---|---|---|
| `normalized_attendance_record_id` | `VARCHAR(36) NOT NULL` | 规范化记录主键 |
| `raw_attendance_fact_id` | `VARCHAR(36) NOT NULL` | 对应原始事实 |
| `normalization_revision` | `INT UNSIGNED NOT NULL` | 当前得力首次规范化固定为 `1` |
| `schema_version` | `VARCHAR(32) NOT NULL` | 当前为 `DELI_CHECKIN_V1` |
| `record_kind` | `VARCHAR(32) NOT NULL` | 固定 `PUNCH_POINT` |
| `normalized_direction` | `VARCHAR(16) NULL` | 当前固定 `AUTO`，不是上班/下班结论 |
| `point_instant` | `DATETIME(6) NULL` | UTC 打卡时点 |
| `interval_start`、`interval_end` | `DATETIME(6) NULL` | 点事件均为空 |
| `validation_status` | `VARCHAR(32) NOT NULL` | `VALID`、`QUARANTINED` 或后续 `SUPERSEDED` |
| `issue_code` | `VARCHAR(64) NULL` | 当前可能为 `EMPLOYEE_UNMATCHED`、`EMPLOYEE_AMBIGUOUS` |
| `canonical_digest` | `CHAR(64) NOT NULL` | 规范化记录摘要 |
| `supersedes_normalized_record_id` | `VARCHAR(36) NULL` | 被替代规范化记录 |
| `created_at` | `DATETIME(6) NOT NULL` | 创建时刻 |

### 4.4 `employee_match_decision`：人员归属层

| 字段 | 含义 |
|---|---|
| `employee_match_decision_id` | 匹配决策主键 |
| `normalized_attendance_record_id` | 对应规范化记录；当前一条规范化记录只允许一个匹配决策 |
| `match_status` | DDL 封闭值为 `MATCHED/UNMATCHED/AMBIGUOUS/OUT_OF_SCOPE`；当前得力解析策略只生成前三种 |
| `match_reason` | 匹配原因，例如精确工号或已确认得力绑定 |
| `employee_id` | 匹配成功的本地员工；失败时为空 |
| `employment_period_id` | 打卡发生时点的唯一有效任职；失败时为空 |
| `device_person_binding_id` | 通用设备人员绑定；当前得力正式事务写 `NULL` |
| `resolver_snapshot_digest` | 人员/任职解析快照摘要 |
| `created_at` | 决策创建时刻 |

匹配顺序：

1. 同公司、发生日期有效的 `employee_version.employee_number` 精确匹配；
2. 若 0 条，再按同来源、同公司、同外部引用、`CONFIRMED` 且有效期覆盖的 `employee_source_binding` 匹配；
3. 0 条为 `UNMATCHED`，多条为 `AMBIGUOUS`，均失败关闭；不使用姓名或部门模糊匹配。

#### 当前代码/DDL 阻塞项

- 解析 SQL 返回稳定的 `employment.employment_period_id`，页事务也把它写入 `employee_match_decision.employment_period_id`；但 V8 的 `fk_employee_match_employment` 仍把该列外键指向 `employment_assignment.assignment_id`。新任职的 period ID 与 assignment version ID 是两个不同 UUID，因此匹配成功的得力记录可能在插入匹配决策时触发外键失败。生产接入前必须通过前向 Flyway 迁移修正并在真实 schema 验证，不能靠文档或关闭外键规避。
- 定时任务以字面量 `SYSTEM` 创建 job、raw 和 lifecycle 审计字段，这些字段均外键到 `auth_principal`；V1—V40 未创建 `principal_id='SYSTEM'`。默认定时任务关闭，但启用前必须建立受控系统主体或修正主体模型，并用集成测试验证。本文不把自动同步标为已闭环。

### 4.5 `effective_attendance_event`：有效业务事件层

这是 OA 查询正式打卡明细的主对象。

| 字段 | SQL 类型/可空 | 得力打卡含义 |
|---|---|---|
| `effective_attendance_event_id` | `VARCHAR(36) NOT NULL` | 有效事件稳定主键，可作为对外分页 tie-breaker |
| `company_id` | `VARCHAR(36) NOT NULL` | 公司边界；V11 后最终列名 |
| `employee_id` | `VARCHAR(36) NOT NULL` | 已唯一匹配的本地员工 |
| `event_kind` | `VARCHAR(32) NOT NULL` | 得力打卡固定 `PUNCH_POINT` |
| `normalized_direction` | `VARCHAR(16) NULL` | 当前得力固定 `AUTO` |
| `point_instant` | `DATETIME(6) NULL` | UTC 打卡时点 |
| `interval_start`、`interval_end` | `DATETIME(6) NULL` | 点事件为空 |
| `canonical_digest` | `CHAR(64) NOT NULL` | 公司、员工、时点、方向形成的事件摘要 |
| `created_at` | `DATETIME(6) NOT NULL` | 有效事件创建时刻 |

精确事件去重键为“公司 + 员工 + 打卡时刻 + 方向”。当前方向固定 `AUTO`：

- 首条有效证据创建事件并记录 `PRIMARY`；
- 相同事件的后续原始证据不新增事件，记录 `EXACT_DUPLICATE`；
- 因此一个有效事件可能关联多条 raw 证据，对外查询不能直接普通 JOIN 后不去重。

### 4.6 生命周期和证据血缘

#### `effective_event_lifecycle_fact`

| 字段 | 含义 |
|---|---|
| `effective_event_lifecycle_fact_id` | 生命周期事实主键 |
| `effective_attendance_event_id` | 所属有效事件 |
| `lifecycle_type` | `ACTIVATED`、`RETRACTED` 或 `SUPERSEDED` |
| `related_event_id` | 相关替代事件 |
| `source_reversal_record_id` | 来源撤销事实 |
| `knowledge_at` | 系统知道该生命周期变化的时刻 |
| `actor_id`、`request_id`、`change_reason` | 审计信息 |
| `fact_digest` | 生命周期事实摘要 |

读取当前有效事件必须按 `(knowledge_at, effective_event_lifecycle_fact_id)` 倒序取最新一条，并要求 `lifecycle_type='ACTIVATED'`。

#### `evidence_link`

| 字段 | 含义 |
|---|---|
| `evidence_link_id` | 证据关系主键 |
| `effective_attendance_event_id` | 有效事件 |
| `raw_attendance_fact_id` | 原始事实 |
| `normalized_attendance_record_id` | 规范化记录 |
| `employee_match_decision_id` | 匹配决策 |
| `link_type` | `PRIMARY`、`EXACT_DUPLICATE`、`REVIEW_MEMBER` 或 `REVERSAL` |
| `created_at` | 关系创建时刻 |

#### `attendance_recalculation_intent`

| 字段 | 含义 |
|---|---|
| `attendance_recalculation_intent_id` | 重算意图主键 |
| `company_id`、`employee_id` | 受影响主体 |
| `business_date` | 候选受影响业务日期 |
| `reason_code` | 得力写入为 `DELI_PUNCH_INGESTED` |
| `effective_attendance_event_id` | 触发事件 |
| `resolver_snapshot_digest`、`period_version` | 解析和期间权威快照 |
| `request_id`、`intent_digest`、`created_at` | 幂等与审计 |

当前重算意图已写入，但尚无完整规则重算消费者。

### 4.7 不是得力打卡流水的相邻表

| 表 | 正确解释 |
|---|---|
| `employee_source_binding`、`deli_employee_binding_revision` | 得力外部人员引用与本地员工的确认绑定和历史，不是打卡流水 |
| `deli_source_operation`、`deli_source_schedule_slot` | 来源配置/计划任务操作，不是打卡流水 |
| `deli_source_connection_probe` | 连接探测结果，不是打卡流水 |
| `source_device`、`device_person_binding` | 通用设备/人员绑定预留；当前得力事务未用其保存 `terminal_id` |
| `punch_import_row` | 离线 Excel 打卡导入原始行，不是得力 API 表 |
| `attendance_report_daily_fact` | 每日计算投影，只保留首卡、末卡及汇总分钟，不是完整流水 |

## 5. 给 OA 的得力打卡只读查询合同

### 5.1 当前状态

状态：`PROPOSED_OUTBOUND_CONTRACT`。

当前仓库**没有**现成的 `v_oa_deli_effective_punch_v1` 视图，也没有已发布的“OA 反查得力打卡”API。下面 SQL 是建议的固定只读查询。生产使用前应由 DBA/安全/HR：

1. 确认输出字段、工号语义、时间窗口和数据保留范围；
2. 在目标 MySQL 版本和真实数据上验证执行计划及唯一性；
3. 封装为版本化只读视图或服务端固定 Mapper；
4. 只向 OA 专用账号授予该视图的 `SELECT`，不要授予底表权限。

本文 SQL 中 `:name` 是框架伪命名占位符：框架必须转换为 JDBC `?` 并绑定，不能直接粘贴到裸 MySQL 客户端，也不能人工字符串替换。服务端还必须强制最大时间窗、最大 `page_size`、statement timeout 和结果上限；5.3/5.5 的展示 SQL 不得无界直接对生产库执行，审计查询应优先走受控只读副本或低峰窗口并先 `EXPLAIN`。

MySQL 视图本身没有通用的按调用方动态行级权限。若 OA 直接连库，DBA 必须把批准的 `company_id` 固化进公司专属、固定 schema 视图（或为不同公司建立独立受控视图），不能只依赖 OA 调用方自觉传公司参数；若通过服务端固定 Mapper，则由服务端账号权限和公司白名单共同强制范围。下文的 `company_id` 条件仍应保留作为纵深校验。若采用 view-only grant，建议由受控、非 root 的专用 `DEFINER` 创建并明确 `SQL SECURITY DEFINER`，审计 definer 变更；若选择 `SQL SECURITY INVOKER`，调用账号仍需底表权限，不符合“只授视图”的默认方案。

当前 `effective_attendance_event` 的相关索引为 `(company_id, employee_id, point_instant, effective_attendance_event_id)`，中间的 `employee_id` 使它不能完整匹配公司级时间范围查询。生产开放前必须用真实量级 `EXPLAIN`/压测验证，并通过独立前向迁移补充 `(company_id, event_kind, point_instant, effective_attendance_event_id)` 或等效索引/查询方案；不得让 OA 账号自行建索引。

### 5.2 建议最小输出字段

| 输出字段 | 类型 | 含义 | 注意 |
|---|---|---|---|
| `punch_event_id` | 字符串 | 神州 HR 有效打卡事件 ID | 稳定分页 tie-breaker；不是得力原始 ID |
| `company_id` | 字符串 | 公司边界 | OA 必须被限制在批准公司范围 |
| `employee_number` | 字符串 | 当前本地员工工号 | v1 查询使用 `employee.employee_number`；若工号允许历史变更，须另行签署发生时点版本口径 |
| `punch_time_utc` | `DATETIME(6)` | UTC 打卡时间 | 查询会话必须按 UTC 解释 |
| `punch_time_cn` | `DATETIME(6)` | 中国标准时间显示值 | 由 `+00:00 → +08:00` 转换；不是权威跨午夜业务日期 |
| `punch_local_date` | `DATE` | 中国自然日期 | 只供显示/初筛，不代表班次业务日期 |
| `punch_direction` | 字符串 | 当前为 `AUTO` | 不得解释为上班卡或下班卡 |

默认不输出姓名、部门、任职、摘要、请求 ID、写入主体、原始/地图坐标、得力 `check_data` 或绑定 ID。

### 5.3 推荐固定查询

以下 `:company_id`、`:from_utc`、`:to_utc` 必须使用 PreparedStatement/框架参数绑定，不能字符串拼接：

```sql
SELECT
    event.effective_attendance_event_id AS punch_event_id,
    event.company_id,
    employee.employee_number,
    event.point_instant AS punch_time_utc,
    CONVERT_TZ(event.point_instant, '+00:00', '+08:00') AS punch_time_cn,
    DATE(CONVERT_TZ(event.point_instant, '+00:00', '+08:00'))
        AS punch_local_date,
    event.normalized_direction AS punch_direction
FROM effective_attendance_event event
JOIN employee employee
  ON employee.employee_id = event.employee_id
 AND employee.company_id = event.company_id
JOIN effective_event_lifecycle_fact lifecycle
  ON lifecycle.effective_attendance_event_id =
     event.effective_attendance_event_id
LEFT JOIN effective_event_lifecycle_fact newer_lifecycle
  ON newer_lifecycle.effective_attendance_event_id =
     lifecycle.effective_attendance_event_id
 AND (
        newer_lifecycle.knowledge_at > lifecycle.knowledge_at
        OR (
            newer_lifecycle.knowledge_at = lifecycle.knowledge_at
            AND newer_lifecycle.effective_event_lifecycle_fact_id >
                lifecycle.effective_event_lifecycle_fact_id
        )
 )
WHERE event.company_id = :company_id
  AND event.event_kind = 'PUNCH_POINT'
  AND event.point_instant >= :from_utc
  AND event.point_instant <  :to_utc
  AND newer_lifecycle.effective_event_lifecycle_fact_id IS NULL
  AND lifecycle.lifecycle_type = 'ACTIVATED'
  AND EXISTS (
      SELECT 1
      FROM evidence_link link
      JOIN raw_attendance_fact raw
        ON raw.raw_attendance_fact_id = link.raw_attendance_fact_id
      JOIN normalized_attendance_record normalized
        ON normalized.normalized_attendance_record_id =
           link.normalized_attendance_record_id
       AND normalized.raw_attendance_fact_id =
           link.raw_attendance_fact_id
      JOIN employee_match_decision matched
        ON matched.employee_match_decision_id =
           link.employee_match_decision_id
       AND matched.normalized_attendance_record_id =
           link.normalized_attendance_record_id
      JOIN attendance_source source
        ON source.attendance_source_id = raw.attendance_source_id
      WHERE link.effective_attendance_event_id =
            event.effective_attendance_event_id
        AND raw.company_id = event.company_id
        AND source.company_id = event.company_id
        AND source.source_type = 'DELI_CLOUD'
        AND raw.fact_kind = 'PUNCH_POINT'
        AND raw.source_instant = event.point_instant
        AND normalized.record_kind = 'PUNCH_POINT'
        AND normalized.validation_status = 'VALID'
        AND normalized.point_instant = event.point_instant
        AND normalized.normalized_direction =
            event.normalized_direction
        AND matched.match_status = 'MATCHED'
        AND matched.employee_id = event.employee_id
  )
ORDER BY event.point_instant,
         event.effective_attendance_event_id;
```

在当前受信写路径和上述额外链路一致性断言成立时，这个查询保证：

- 一行对应一个有效事件；
- 最新生命周期必须为 `ACTIVATED`；
- 至少存在一条得力来源证据；
- `EXACT_DUPLICATE` 不会导致输出重复；
- 未匹配、歧义和只存在 raw 的隔离记录不会进入结果；
- 时间范围采用 `[from_utc, to_utc)`。

### 5.4 固定窗口的 keyset 分页

以下游标只用于**同一次一致性读取/物化导出中的页内分页**，不是跨运行增量水位。固定一次抽取的 `:to_utc`，并在 `REPEATABLE READ` 一致性快照或等价物化结果上使用 `(punch_time_utc, punch_event_id)`：

```sql
SELECT
    punch_event_id,
    company_id,
    employee_number,
    punch_time_utc,
    punch_time_cn,
    punch_local_date,
    punch_direction
FROM v_oa_deli_effective_punch_v1
WHERE company_id = :company_id
  AND punch_time_utc >= :from_utc
  AND punch_time_utc <  :to_utc
  AND (
        :after_time_utc IS NULL
        OR punch_time_utc > :after_time_utc
        OR (
            punch_time_utc = :after_time_utc
            AND punch_event_id > :after_event_id
        )
  )
ORDER BY punch_time_utc, punch_event_id
LIMIT :page_size;
```

说明：`v_oa_deli_effective_punch_v1` 是建议视图名，当前数据库尚不存在。第一页两个游标必须同时为空；后续页二者必须同时非空并使用上一页最后一行的时间和事件 ID，服务端应拒绝半个游标。`page_size` 应设置固定上限，例如 500 或 1000。

不能把这组游标保存为下次同步水位：晚到或补拉的历史打卡可能带有早于游标的 `point_instant`，而后续 `RETRACTED/SUPERSEDED` 也不会出现在 active-only 视图中。跨运行持续同步必须另行签署基于事件 `created_at` 与 lifecycle `knowledge_at` 的变更/墓碑合同，或使用有重叠回看窗口并按 `punch_event_id` 做幂等全量状态对账；当前仓库没有这样的 OA change feed。

### 5.5 仅供实施/审计的来源追踪查询

以下查询会包含 `QUARANTINED` 和没有有效事件的原始证据，不得作为 OA 考勤计算输入：

```sql
SELECT
    source.source_code,
    raw.raw_attendance_fact_id,
    raw.source_business_key AS deli_checkin_id,
    raw.source_version AS system_source_version,
    raw.source_time_text AS deli_check_time_epoch_seconds,
    raw.source_time_zone,
    raw.source_instant AS punch_time_utc,
    normalized.normalization_revision,
    normalized.normalized_direction,
    normalized.validation_status,
    normalized.issue_code,
    matched.match_status,
    matched.match_reason,
    matched.employee_id,
    link.link_type,
    event.effective_attendance_event_id,
    raw.received_at
FROM raw_attendance_fact raw
JOIN attendance_source source
  ON source.attendance_source_id = raw.attendance_source_id
JOIN normalized_attendance_record normalized
  ON normalized.raw_attendance_fact_id = raw.raw_attendance_fact_id
JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id =
     normalized.normalized_attendance_record_id
LEFT JOIN evidence_link link
  ON link.raw_attendance_fact_id = raw.raw_attendance_fact_id
 AND link.normalized_attendance_record_id =
     normalized.normalized_attendance_record_id
 AND link.employee_match_decision_id =
     matched.employee_match_decision_id
LEFT JOIN effective_attendance_event event
  ON event.effective_attendance_event_id =
     link.effective_attendance_event_id
WHERE raw.company_id = :company_id
  AND source.source_type = 'DELI_CLOUD'
  AND raw.fact_kind = 'PUNCH_POINT'
  AND raw.source_instant >= :from_utc
  AND raw.source_instant <  :to_utc
ORDER BY raw.source_instant,
         raw.raw_attendance_fact_id,
         normalized.normalization_revision
LIMIT :page_size;
```

### 5.6 OA 出站查询仍需签署的五点

1. `employee_number` 是读取当前 `employee` 值，还是打卡发生时有效的 `employee_version` 值；本文 v1 查询明确选择“当前员工工号”。
2. OA 是否只需要得力有效流水，还是需要所有来源的合并有效流水；本文查询只保留存在 `DELI_CLOUD` 血缘的事件。
3. OA 是否需要已撤回/替代事件；本文查询只返回当前最新生命周期为 `ACTIVATED` 的事件。
4. OA 是否需要班次业务日期；本文只返回 UTC 时点和中国自然日期，跨午夜业务日期必须由权威考勤配置解析，不能由查询自行猜测。
5. 首次全量、晚到回看、变更水位、撤回墓碑和幂等对账如何约定；第 5.4 节游标只用于单次固定窗口分页，不是持续同步合同。

## 6. OA 七类表单候选字段字典

### 6.1 字段角色

下列“逻辑类型”来自 Java `SourceValueKind`，**不是已确认的 MySQL 物理类型**。

| 角色 | 含义 | 当前转换行为 |
|---|---|---|
| `SUBJECT_MEMBER_ID` | 主体人员候选 `org_member.id` | 严格正整数，定点查 `org_member` |
| `EMPLOYEE_CODE_CHECK_ONLY` | 表单工号复核 | 只与 `org_member.code` 做 Java `String.equals` 精确比较 |
| `TEMPORAL_START` / `TEMPORAL_END` | 区间或日期范围端点 | 要求已类型化的 `LocalDateTime` 或 `LocalDate` |
| `TEMPORAL_POINT` | 补卡时间点 | 要求已类型化的 `LocalDateTime` |
| `LEAVE_TYPE_ENUM` | 请假类型共享枚举 | 请假单 `field0089` 与销假单 `field0089/field0100` 共用业务枚举；当前转换器仍因实库枚举合同未签字而失败关闭 |
| `ENUM_NOT_VERIFIED` | 枚举 ID | 当前只在白名单声明，不读取、不映射、不输出 |
| `DURATION_CHECK_ONLY` | 时长/天数核验候选 | 当前只在白名单声明，未执行时长核验 |
| `SYSTEM_CALCULATED_HOURS` | OA 插件按 HR 规则计算并回填的小时 | 请假 `formmain_0170.field0103` 与销假 `formmain_0370.field0107`；当前 HR 行转换器只做表级白名单声明，目标 OA 的列存在性、数值精度和可写性仍须现场验证 |
| `CONTEXT_ONLY` | 事由、部门、岗位等上下文 | 当前只在白名单声明，不读取、不校验、不输出 |

### 6.2 出差 `TRIP` — `formmain_0265`

行角色：主表；时间形态：区间。

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0137` | 出差人选人 ID | `SUBJECT_MEMBER_ID` | `MEMBER_ID` |
| `field0148` | 开始时间 | `TEMPORAL_START` | `DATE_TIME` |
| `field0149` | 结束时间 | `TEMPORAL_END` | `DATE_TIME` |
| `field0140` | 事由 | `CONTEXT_ONLY` | `TEXT` |
| `field0141` | 累计天数 | `DURATION_CHECK_ONLY` | `DECIMAL` |
| `field0142` | 地点 | `CONTEXT_ONLY` | `TEXT` |
| `field0083` | 填表人 | `CONTEXT_ONLY` | `TEXT` |
| `field0084` | 填表部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0085` | 填表日期 | `CONTEXT_ONLY` | `DATE` |
| `field0138` | 出差人部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0139` | 出差人岗位 | `CONTEXT_ONLY` | `TEXT` |
| `field0154` | 所属岗位 | `CONTEXT_ONLY` | `TEXT` |
| `field0155` | 代理人 | `CONTEXT_ONLY` | `TEXT` |
| `field0156` | 所属部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0151` | 交通字段 1 | `CONTEXT_ONLY` | `TEXT` |
| `field0152` | 交通字段 2 | `CONTEXT_ONLY` | `TEXT` |
| `field0153` | 交通字段 3 | `CONTEXT_ONLY` | `TEXT` |

### 6.3 请假 `LEAVE` — `formmain_0170`

行角色：主表；时间形态：区间。

`field0103` 是 2026-08-14 用户确认的系统计算小时字段，不在此前截图转录集合中；目标 OA 的列存在性、数值精度和流程可写性仍为 `NOT_VERIFIED`。

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0097` | 请假单流水号；关联销假 `field0099` | `CONTEXT_ONLY` | `TEXT` |
| `field0083` | 请假人选人 ID | `SUBJECT_MEMBER_ID` | `MEMBER_ID` |
| `field0084` | 表单工号 1 | `EMPLOYEE_CODE_CHECK_ONLY` | `TEXT` |
| `field0086` | 请假开始 | `TEMPORAL_START` | `DATE_TIME` |
| `field0087` | 请假结束 | `TEMPORAL_END` | `DATE_TIME` |
| `field0088` | 天数/时长 | `DURATION_CHECK_ONLY` | `DECIMAL` |
| `field0103` | 系统计算小时 | `SYSTEM_CALCULATED_HOURS` | `DECIMAL` |
| `field0089` | 请假类别 | `LEAVE_TYPE_ENUM` | `ENUM` |
| `field0090` | 备注 | `CONTEXT_ONLY` | `TEXT` |
| `field0091` | 说明 | `CONTEXT_ONLY` | `TEXT` |
| `field0092` | 岗位 | `CONTEXT_ONLY` | `TEXT` |
| `field0093` | 级别 | `CONTEXT_ONLY` | `TEXT` |
| `field0094` | 代理人 | `CONTEXT_ONLY` | `TEXT` |
| `field0095` | 所属部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0096` | 表单工号 2 | `EMPLOYEE_CODE_CHECK_ONLY` | `TEXT` |
| `field0074` | 填表人 | `CONTEXT_ONLY` | `TEXT` |
| `field0075` | 填表部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0076` | 填表日期 | `CONTEXT_ONLY` | `DATE` |

### 6.4 销假 `LEAVE_REVOCATION` — `formmain_0370`

行角色：主表；时间形态：区间。静态目录现有 22 个字段：21 个来自 OA 数据字典截图，`field0107` 来自 2026-08-14 用户确认的系统返还小时合同。下表的“截图最终类型”仅适用于已有截图的字段，仍属 `SCREENSHOT_DECLARED`，不是实库 `information_schema` 已验证类型；`field0107` 的物理类型明确保持 `NOT_VERIFIED`。

`field0089`“请假类别”和 `field0100`“销假类型”均按业务确认复用请假单 `formmain_0170.field0089` 的同一套请假类型枚举。三列共享业务枚举不等于其物理枚举 ID 已现场验证；启用前仍需分别联 `ctp_enum_item` 核对 ID、`showvalue` 和空值/未知值分布。

| 字段 | 截图最终类型 | 显示名称/预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|---|
| `field0097` | `VARCHAR(100)` | 流水号 | `CONTEXT_ONLY` | `TEXT` |
| `field0074` | `VARCHAR(20)` | 填写人 | `CONTEXT_ONLY` | `TEXT` |
| `field0075` | `VARCHAR(20)` | 部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0076` | `DATE` | 填写日期 | `CONTEXT_ONLY` | `DATE` |
| `field0100` | `BIGINT` | 销假类型；复用请假类型枚举 | `LEAVE_TYPE_ENUM` | `ENUM` |
| `field0098` | `VARCHAR(100)` | 原请假单 | `CONTEXT_ONLY` | `TEXT` |
| `field0099` | `VARCHAR(100)` | 原请假单流水号 | `CONTEXT_ONLY` | `TEXT` |
| `field0083` | `VARCHAR(20)` | 请假人选人 ID | `SUBJECT_MEMBER_ID` | `MEMBER_ID` |
| `field0092` | `VARCHAR(20)` | 请假人岗位 | `CONTEXT_ONLY` | `TEXT` |
| `field0093` | `VARCHAR(20)` | 请假人职务级别 | `CONTEXT_ONLY` | `TEXT` |
| `field0085` | `VARCHAR(20)` | 请假人部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0084` | `VARCHAR(100)` | 请假人工号 | `EMPLOYEE_CODE_CHECK_ONLY` | `TEXT` |
| `field0089` | `BIGINT` | 请假类别；复用请假类型枚举 | `LEAVE_TYPE_ENUM` | `ENUM` |
| `field0086` | `DATETIME` | 实际请假开始时间 | `TEMPORAL_START` | `DATE_TIME` |
| `field0087` | `DATETIME` | 实际请假结束时间 | `TEMPORAL_END` | `DATE_TIME` |
| `field0088` | `DECIMAL(22,4)` | 共计天数 | `DURATION_CHECK_ONLY` | `DECIMAL` |
| `field0107` | `NOT_VERIFIED` | 系统计算返还小时 | `SYSTEM_CALCULATED_HOURS` | `DECIMAL` |
| `field0090` | `VARCHAR(100)` | 备注 | `CONTEXT_ONLY` | `TEXT` |
| `field0091` | `VARCHAR(255)` | 销假说明 | `CONTEXT_ONLY` | `TEXT` |
| `field0094` | `VARCHAR(20)` | 代理人 | `CONTEXT_ONLY` | `TEXT` |
| `field0095` | `VARCHAR(20)` | 所属部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0096` | `VARCHAR(100)` | 工号（二次核验） | `EMPLOYEE_CODE_CHECK_ONLY` | `TEXT` |

`field0098` 仍只是截图声明的文本上下文，不作为当前关联键。业务已明确 `field0099` 是原请假单流水号，并与请假 `formmain_0170.field0097` 关联；但这两列尚无实库 metadata 或声明式外键证据，当前也没有生产 Repository/Service 自动执行该联表、版本归并和返还事实装配。

#### 6.4.1 请假—销假的一对多关联、返还区间与累计小时

关联和时间选择合同如下：

```text
请假 formmain_0170.field0097
  =（精确字符串相等）
销假 formmain_0370.field0099

0 张已批准销假 → 有效请假片段仍为原 field0086 / field0087
N 张已批准销假 → 每张 field0086 / field0087 是一个连续返还区间
                  按时间排序并从原请假区间中逐段扣除
                  保留 N 条返还事实，不取“最后一张”覆盖原单
```

执行规则：

1. 关联比较使用 Java `String.equals` 语义；不 trim、不改大小写、不转数字，也不增删前导零。
2. 一张请假单允许 0～N 张当前销假业务单；调用方必须在同一一致水位取全并先归并同一业务单的历史版本，不能 `LIMIT 1` 或按最后更新时间只取一张。
3. 每张 `APPROVED_EFFECTIVE` 销假单的 `field0086/field0087` 表达一个连续返还区间，必须完全落在原请假区间内；区间按半开语义检查，相邻端点允许，任何实际重叠均失败关闭。
4. 每张已批准销假以其唯一业务单 ID 和 `field0107` 形成独立返还小时事实；累计返还小时不得超过原请假已扣减的 `field0103`。等于已扣减小时合法，超过时不得选任意子集继续；业务单 ID 缺失或重复必须失败关闭，重放沿用该单原 eventId 和摘要。
5. 明确为 `NOT_EFFECTIVE` 的销假不产生返还区间或返还小时；任一当前单的审批含义未知时失败关闭，不能猜测。
6. 关联流水号不一致、请假人与销假人不一致、流水号为空、返还区间越界、已批准区间重叠、小时不是 0.5 的整数倍或累计超额，均失败关闭。
7. `field0098`“原请假单”不参与本版关联；除非以后另行签署，不能用它或 OA 记录 `ID` 替代流水号。
8. 婚育、丧假是否属于同一业务事件、亲属关系等没有结构化字段的信息由审批人最终判断；代码和插件不得根据备注文本猜测。

当前 `OaLeaveEffectiveIntervalResolver` 已把精确关联、主体一致、审批未知、多张返还区间排序/不重叠、区间越界、累计返还不超已扣减小时以及剩余有效请假片段固化为纯领域策略；它不查询 OA、不归并同一业务单的历史版本，也不把 `col_summary.state` 数字解释成审批结果。调用方必须先确认原请假已批准，以签署后的固定查询和一致水位返回该流水号的**全部当前销假业务单**，完成版本归并，再把每张当前单的审批状态映射成明确决策。当前生产尚无该调用方，因此这是 `CURRENT_CODE` 的失败关闭规则，不是已经上线的 OA 同步闭环；单个分页返回 0 行不能证明“没有销假”。

### 6.5 加班 `OVERTIME`

时间形态：区间；主体人员和每次加班区间位于明细表。

#### 主表 `formmain_0171`

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0074` | 填表人 | `CONTEXT_ONLY` | `TEXT` |
| `field0075` | 填表部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0076` | 填表日期 | `CONTEXT_ONLY` | `DATE` |
| `field0102` | 明细最早时间 | `CONTEXT_ONLY` | `DATE_TIME` |
| `field0104` | 系统差值 | `DURATION_CHECK_ONLY` | `DECIMAL` |
| `field0105` | 系统差值文本 | `CONTEXT_ONLY` | `TEXT` |

#### 明细表 `formson_0172`

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0092` | 序号 | `CONTEXT_ONLY` | `DECIMAL` |
| `field0093` | 加班人选人 ID | `SUBJECT_MEMBER_ID` | `MEMBER_ID` |
| `field0094` | 表单工号 | `EMPLOYEE_CODE_CHECK_ONLY` | `TEXT` |
| `field0095` | 部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0096` | 加班类别 | `ENUM_NOT_VERIFIED` | `ENUM` |
| `field0100` | 加班开始 | `TEMPORAL_START` | `DATE_TIME` |
| `field0099` | 加班结束 | `TEMPORAL_END` | `DATE_TIME` |
| `field0101` | 总时长 | `DURATION_CHECK_ONLY` | `DECIMAL` |
| `field0103` | 原因 | `CONTEXT_ONLY` | `TEXT` |

字段名只在所属物理表内解释：`formmain_0170.field0103` 是请假系统计算小时，`formson_0172.field0103` 仍是加班原因文本，两者不冲突，也不得跨表复用类型或语义。

### 6.6 外出 `OUTING`

时间形态：区间。

#### 主表 `formmain_0251`

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0083` | 填表人 | `CONTEXT_ONLY` | `TEXT` |
| `field0084` | 填表部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0085` | 填表日期 | `CONTEXT_ONLY` | `DATE` |

#### 明细表 `formson_0252`

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0126` | 序号 | `CONTEXT_ONLY` | `DECIMAL` |
| `field0127` | 外出人选人 ID | `SUBJECT_MEMBER_ID` | `MEMBER_ID` |
| `field0130` | 部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0131` | 表单工号 | `EMPLOYEE_CODE_CHECK_ONLY` | `TEXT` |
| `field0132` | 外出开始 | `TEMPORAL_START` | `DATE_TIME` |
| `field0135` | 外出结束 | `TEMPORAL_END` | `DATE_TIME` |
| `field0133` | 事由 | `CONTEXT_ONLY` | `TEXT` |

### 6.7 免打卡 `EXEMPT_PUNCH`

时间形态：日期范围。

#### 主表 `formmain_0201`

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0083` | 填表人 | `CONTEXT_ONLY` | `TEXT` |
| `field0084` | 填表部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0085` | 填表日期 | `CONTEXT_ONLY` | `DATE` |

#### 明细表 `formson_0202`

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0126` | 序号 | `CONTEXT_ONLY` | `DECIMAL` |
| `field0127` | 免打卡人员 | `SUBJECT_MEMBER_ID` | `MEMBER_ID` |
| `field0129` | 岗位 | `CONTEXT_ONLY` | `TEXT` |
| `field0130` | 部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0131` | 表单工号 | `EMPLOYEE_CODE_CHECK_ONLY` | `TEXT` |
| `field0132` | 开始日期 | `TEMPORAL_START` | `DATE` |
| `field0134` | 结束日期 | `TEMPORAL_END` | `DATE` |
| `field0133` | 原因 | `CONTEXT_ONLY` | `TEXT` |

当前转换器只保留两个 `LocalDate` 原值，不判断结束日是否包含，也不自动扩展到 `23:59:59`。

### 6.8 补卡 `PUNCH_CORRECTION`

时间形态：单点。

#### 主表 `formmain_0203`

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0083` | 填表人 | `CONTEXT_ONLY` | `TEXT` |
| `field0084` | 填表部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0085` | 填表日期 | `CONTEXT_ONLY` | `DATE` |

#### 明细表 `formson_0204`

| 字段 | 预期含义 | 角色 | 逻辑类型 |
|---|---|---|---|
| `field0126` | 序号 | `CONTEXT_ONLY` | `DECIMAL` |
| `field0127` | 补卡人员 | `SUBJECT_MEMBER_ID` | `MEMBER_ID` |
| `field0129` | 岗位 | `CONTEXT_ONLY` | `TEXT` |
| `field0130` | 部门 | `CONTEXT_ONLY` | `TEXT` |
| `field0131` | 表单工号 | `EMPLOYEE_CODE_CHECK_ONLY` | `TEXT` |
| `field0132` | 补签时间 | `TEMPORAL_POINT` | `DATE_TIME` |
| `field0133` | 原因 | `CONTEXT_ONLY` | `TEXT` |
| `field0134` | 补卡类型 | `ENUM_NOT_VERIFIED` | `ENUM` |

## 7. OA 技术关联表与当前实库证据

### 7.1 业务字段与技术列必须分层

Java 静态目录只允许形如 `fieldNNNN` 的业务字段。它不包含：

- 主表/明细表的 `ID`；
- 明细候选关联列 `formmain_id`；
- `col_summary.form_recordid/state`；
- `ctp_enum_item.id/showvalue`。

`OaFormRowTransformer` 会把白名单之外的列记为 `UNKNOWN_COLUMN`。因此未来生产查询不能把 `SELECT *` 的整行直接交给转换器；必须把技术键/关联列用于 SQL 联接或游标，再只将允许的业务投影交给转换器。

### 7.2 已取证的加班相关物理列（部分证据）

列形状主要来自 `OA-B5-01A`；该证据因原图未显示证据编号和实例指纹仍为 `PARTIAL`。其中两个时间列另被完整 `REVIEWED` 的 `OA-B5-06A` 重复确认。下表逐行标明可依赖的证据状态。

| 表.列 | 实库类型 | 可空/键 | 当前证据状态 |
|---|---|---|---|
| `formmain_0171.ID` | `bigint` | `NOT NULL, PRI` | `OA-B5-01A PARTIAL` |
| `formmain_0171.field0102` | `datetime` | `NULL` | `OA-B5-01A PARTIAL` |
| `formson_0172.ID` | `bigint` | `NOT NULL, PRI` | `OA-B5-01A PARTIAL` |
| `formson_0172.formmain_id` | `bigint` | `NULL, MUL` | `OA-B5-01A PARTIAL` |
| `formson_0172.field0093` | `varchar(20)` | `NULL` | `OA-B5-01A PARTIAL` |
| `formson_0172.field0094` | `varchar(100)` | `NULL` | `OA-B5-01A PARTIAL` |
| `formson_0172.field0096` | `bigint` | `NULL` | `OA-B5-01A PARTIAL` |
| `formson_0172.field0100` | `datetime` | `NULL` | `OA-B5-06A REVIEWED`（亦见 01A） |
| `formson_0172.field0099` | `datetime` | `NULL` | `OA-B5-06A REVIEWED`（亦见 01A） |

当前快照中：42,951 条主单、117,207 条明细；`formmain_id` 无 NULL、无孤儿；26,741 单有一条明细，16,210 单有多条明细，单个主单最多 84 条。未发现声明式 FK。这支持 `formson_0172.formmain_id → formmain_0171.ID` 是当前数据的 1:N 候选关系，但最终运行合同仍需签字。

其余 9 张业务表及大部分加班字段没有实库 SQL 类型证据；其中新增 `formmain_0370` 的类型仅由本次截图声明。

### 7.3 `org_member`：OA 人员目录

现有代码唯一真正执行的 OA SQL：

```sql
SELECT id, code
FROM org_member
WHERE id = ?;
```

| 列 | 实库类型 | 用途 | 边界 |
|---|---|---|---|
| `ID` | `bigint NOT NULL PRI` | 表单主体候选引用键 | 尚未对七类全部主体字段签署“只存单一 member ID” |
| `CODE` | `varchar(500) NULL` | OA 人员编码/工号候选 | 与神州 HR/得力工号的跨系统等值口径未正式签字 |
| `STATE` | `smallint NULL` | 候选生命周期列 | 业务含义未签字 |
| `IS_ENABLE` | `smallint NULL` | 候选启用列 | 业务含义未签字 |
| `IS_DELETED` | `smallint NULL` | 候选删除列 | 业务含义未签字 |
| `STATUS` | `smallint NULL` | 候选状态列 | 业务含义未签字 |

当前快照：846 条成员；`code` 零长度 10 条；排除空编码后有 836 条候选，其中有一个逐字节重复编码组、涉及两条记录。当前适配器不读取生命周期字段，也不按公司/组织过滤，因此只能证明指定 ID 恰好返回一行且 code 非空。

### 7.4 `col_summary`：审批摘要

候选关联路径：

```text
formmain_xxxx.ID = col_summary.FORM_RECORDID
```

| 列 | 实库类型 | 用途 | 边界 |
|---|---|---|---|
| `STATE` | `smallint NULL MUL` | 原始流程状态 | 技术值已取证，业务语义未正式签字 |
| `FORM_RECORDID` | `bigint NULL MUL` | 候选关联主表 `ID` | 当前只对加班主表验证了 1:1 覆盖 |

全表状态分布：

| 原始状态 | 行数 | 历史/口述解释 | 当前合同状态 |
|---|---:|---|---|
| `3` | 259,653 | 流程结束，历史拟映射批准 | `NOT_VERIFIED` |
| `0` | 55,999 | 已发起/处理中 | `NOT_VERIFIED` |
| `2` | 1,754 | 撤销 | `NOT_VERIFIED` |
| `NULL` | 525 | 草稿/保存待发 | `NOT_VERIFIED` |
| `1` | 198 | 未知 | 必须隔离 |

加班子集中 `state=1` 只有 1 单，但这不是全表数量。不能仅凭分布把 `state=3` 写死为最终批准。

### 7.5 `ctp_enum_item`：枚举显示值

候选关联路径：

```text
表单枚举字段 = ctp_enum_item.ID
读取 ctp_enum_item.SHOWVALUE
```

| 列 | 实库类型 | 用途 | 边界 |
|---|---|---|---|
| `ID` | `bigint NOT NULL PRI` | 表单原始枚举 ID 关联键 | 结构已取证 |
| `SHOWVALUE` | `varchar(255) NULL` | 中文显示值 | 标签是否构成正式封闭业务字典仍待签字 |

#### 加班类别技术映射

| 原始枚举 ID | `SHOWVALUE` | 当前快照行数 |
|---:|---|---:|
| `-6539634143789166714` | 加班费 | 112,022 |
| `5912806790045781226` | 调休 | 5,066 |
| `4337518111002608138` | 义务加班 | 89 |
| `NULL` | 原始空值 | 30 |

#### 请假类型共享业务枚举的现有技术映射

下列快照来自请假单 `formmain_0170.field0089`。业务现已确认销假单 `formmain_0370.field0089` 与 `field0100` 复用同一套请假类型枚举；销假单两列仍需在实库中分别核验 ID/`showvalue` 分布后，才能把这一业务确认升级为可运行物理合同。

| 原始枚举 ID | `SHOWVALUE` | 当前快照行数 |
|---:|---|---:|
| `5959840635913392019` | 年假 | 3,930 |
| `-5949231195115077302` | 事假 | 3,790 |
| `-1336039252273314314` | 调休 | 1,715 |
| `-4813206556006068884` | 病假 | 219 |
| `399372756916521924` | 丧假 | 108 |
| `7766045108187057729` | 婚假 | 39 |
| `5096400378405801343` | 陪产假 | 36 |
| `825511723370040388` | 孕检假 | 19 |
| `6036514085623843175` | 哺乳假 | 16 |
| `8015931125935262011` | 其他 | 12 |
| `-4366659526086802845` | 产假 | 8 |
| `-8479271604082882449` | 计生假 | 2 |
| `NULL` | 原始空值 | 2 |

补卡 `formson_0204.field0134` 尚无实库枚举映射证据。任何 NULL、未知 ID、缺失 `SHOWVALUE` 或未签字标签必须失败关闭，不能映射为默认类别。

## 8. OA 身份、时间和查询边界

### 8.1 跨系统身份链

候选链路：

```text
OA 表单主体字段
  → org_member.ID
  → org_member.CODE
  ↔ 神州 HR employee/employee_version.employee_number
  ← 得力 employee_num
```

精确规则：

- 不 trim；
- 不转大小写；
- 不把数字字符串转数值；
- 不补零、不去前导零；
- 不用姓名、部门、岗位做兜底；
- 0 条、多条、空 code 或不一致均失败关闭。

当前 OA 转换器只走到 `org_member.code`，没有生产接上本地员工/任职解析。`org_member.code ↔ 得力/神州 HR employee_number` 仍需跨系统业务字典或脱敏对账签字。

### 8.2 OA 时间规则

- 区间字段必须已经是 `LocalDateTime`，结束严格晚于开始；
- 日期范围必须已经是 `LocalDate`，结束不得早于开始；
- 补卡点必须已经是 `LocalDateTime`；
- 转换器不解析任意字符串；
- 转换器不应用 OA 来源时区；
- 免打卡结束日期是否包含尚未签字；
- 出差、请假、销假、加班、外出的 `[开始,结束)` 时间边界仍未签字；请假—销假的精确关联、一对多返还事实、不重叠和累计不超已扣减小时的业务规则已确认，但 OA 审批映射、当前版本归并、时区和具体 SQL 仍待验证/签署。

### 8.3 当前不能直接发布的 OA 生产查询

目前没有可直接发布的七类 OA 业务单据查询，原因包括：

- 11 张表及 104 个候选字段未完成全量 metadata diff；其中 `formmain_0170.field0103`、`formmain_0370.field0107` 还须确认数值类型、精度和流程可写性；
- 只有加班主从候选关系得到部分技术取证；
- 审批状态语义未签字且存在未知 `state=1`；
- 请假 `field0097` 与销假 `field0099` 的物理类型、精确比较语义、空值/重复分布，以及全部当前销假查询/版本归并尚未完成实库验证；多张销假是合法业务形态，不能再用“关联数不大于 1”作为门禁；
- 请假类型与销假单两列的共享业务口径已确认，但销假单物理 ID 分布尚未取证；加班类别业务闭集仍待签字，补卡类别尚未取证；
- OA `DATETIME` 业务时区、日期范围边界和增量水位未签字；
- 当前静态转换器不接收 `ID/formmain_id/状态/枚举关联列` 的整行投影；
- 当前代码没有七类单据的生产 Repository、Service、分页、游标或同步任务。

因此，其他项目可以使用本文理解字段和准备 metadata/签字，但不能把候选关联直接当作生产 SQL 合同。

## 9. OA 正式取数前的初步预检模板

以下查询仅用于快速发现结构或分布差异，不代表七类生产同步已经接通，也**不足以单独形成正式可签字证据**：它们没有输出仓库证据规范要求的 `evidence_id`、`instance_fingerprint`，也没有覆盖全部主从基数、审批关联基数和枚举缺失形态。正式取证应执行文末引用的 `oa-live-resolution-queries.sql` / `oa-live-resolution-screenshot-queries.sql`，保存脱敏原图、摘要和实例指纹后再审阅。

示例中的 `:oa_schema` 是命名参数占位符，只适用于支持绑定参数的客户端/框架，不是可直接粘贴到裸 MySQL CLI 的语法。裸 CLI 应先连接到经批准的目标实例并执行 `USE <oa_schema>;`，再把该条件改为 `table_schema = DATABASE()`；下述未限定 schema 的表名也依赖当前数据库。数据库名不得由不可信外部输入直接拼接。

枚举和状态 `GROUP BY` 可能扫描大表。执行前应由 DBA 审阅 `EXPLAIN`，设置查询超时，优先使用只读副本/低峰窗口，并遵守正式取证脚本的脱敏与截图规则；本文不授权在生产库无界执行。

### 9.1 查看表和列结构

```sql
SELECT
    table_name,
    ordinal_position,
    column_name,
    column_type,
    is_nullable,
    column_key,
    column_default
FROM information_schema.columns
WHERE table_schema = :oa_schema
  AND table_name IN (
      'formmain_0265',
      'formmain_0170',
      'formmain_0370',
      'formmain_0171', 'formson_0172',
      'formmain_0251', 'formson_0252',
      'formmain_0201', 'formson_0202',
      'formmain_0203', 'formson_0204',
      'org_member', 'col_summary', 'ctp_enum_item'
  )
ORDER BY table_name, ordinal_position;
```

### 9.2 枚举技术映射

```sql
-- 加班类别
SELECT
    detail.field0096 AS raw_enum_id,
    enum_item.showvalue AS label,
    COUNT(*) AS row_count
FROM formson_0172 detail
LEFT JOIN ctp_enum_item enum_item
  ON enum_item.id = detail.field0096
GROUP BY detail.field0096, enum_item.showvalue
ORDER BY row_count DESC;

-- 请假单请假类别：共享请假类型枚举的已取证基准列
SELECT
    main.field0089 AS raw_enum_id,
    enum_item.showvalue AS label,
    COUNT(*) AS row_count
FROM formmain_0170 main
LEFT JOIN ctp_enum_item enum_item
  ON enum_item.id = main.field0089
GROUP BY main.field0089, enum_item.showvalue
ORDER BY row_count DESC;

-- 销假单请假类别：业务上复用同一枚举，仍须独立核验物理 ID
SELECT
    main.field0089 AS raw_enum_id,
    enum_item.showvalue AS label,
    COUNT(*) AS row_count
FROM formmain_0370 main
LEFT JOIN ctp_enum_item enum_item
  ON enum_item.id = main.field0089
GROUP BY main.field0089, enum_item.showvalue
ORDER BY row_count DESC;

-- 销假单销假类型：业务上复用同一枚举，仍须独立核验物理 ID
SELECT
    main.field0100 AS raw_enum_id,
    enum_item.showvalue AS label,
    COUNT(*) AS row_count
FROM formmain_0370 main
LEFT JOIN ctp_enum_item enum_item
  ON enum_item.id = main.field0100
GROUP BY main.field0100, enum_item.showvalue
ORDER BY row_count DESC;

-- 补卡类型：正式使用前必须同样执行并签字
SELECT
    detail.field0134 AS raw_enum_id,
    enum_item.showvalue AS label,
    COUNT(*) AS row_count
FROM formson_0204 detail
LEFT JOIN ctp_enum_item enum_item
  ON enum_item.id = detail.field0134
GROUP BY detail.field0134, enum_item.showvalue
ORDER BY row_count DESC;
```

### 9.3 审批状态技术分布

```sql
SELECT state, COUNT(*) AS row_count
FROM col_summary
GROUP BY state
ORDER BY row_count DESC;
```

仅凭这个结果不能把数字解释为批准、撤销或草稿；还需要 OA 流程配置和责任方签字。

### 9.4 请假—销假关联基数预检

下列 SQL 只用于在已授权只读环境中检查候选关联的空值、重复和孤儿数据，不是生产审批过滤查询。`BINARY` 用于避免数据库默认大小写不敏感比较；正式上线前仍须确认两列字符集、尾随空格行为和 Java `String.equals` 的一致性。大表执行前必须 `EXPLAIN`、限定环境并设置超时。

```sql
-- 请假流水号自身必须非空且唯一。
SELECT
    BINARY leave_form.field0097 AS leave_serial_exact,
    COUNT(*) AS leave_count
FROM formmain_0170 leave_form
GROUP BY BINARY leave_form.field0097
HAVING leave_serial_exact IS NULL
    OR OCTET_LENGTH(leave_serial_exact) = 0
    OR COUNT(*) <> 1;

-- 多张销假是合法业务形态；这里只输出原始关联数量分布供抽样，不能把 >1 当异常。
SELECT
    BINARY leave_form.field0097 AS leave_serial_exact,
    COUNT(revocation.field0099) AS revocation_count
FROM formmain_0170 leave_form
LEFT JOIN formmain_0370 revocation
  ON BINARY revocation.field0099 = BINARY leave_form.field0097
WHERE leave_form.field0097 IS NOT NULL
  AND leave_form.field0097 <> ''
GROUP BY BINARY leave_form.field0097
ORDER BY revocation_count DESC, leave_serial_exact;

-- 销假不得引用不存在的请假流水号。
SELECT
    revocation.field0099 AS orphan_original_leave_serial
FROM formmain_0370 revocation
LEFT JOIN formmain_0170 leave_form
  ON BINARY leave_form.field0097 = BINARY revocation.field0099
WHERE revocation.field0099 IS NULL
   OR revocation.field0099 = ''
   OR leave_form.field0097 IS NULL;
```

第一和第三个查询返回 0 行才通过流水号/孤儿结构预检；中间的数量查询是合法一对多分布，不是“必须为 0”的门禁。之后仍必须联 `col_summary`，按已签署的批准/有效状态过滤，在一致水位完成版本归并，并校验请假人与销假人一致。已批准集合还必须校验返还区间不重叠、完全位于原请假区间，以及 `SUM(field0107) <= 原请假已扣减 field0103`。已扣减小时位于 HR 账本，不能仅靠 OA 两表或猜测余额完成该校验；由于审批/版本合同当前仍为 `NOT_VERIFIED`，本文不伪造可直接投产的状态条件。

## 10. 安全与授权要求

考勤证据、请假原因和精确位置属于 S3 敏感数据。对 OA 或其他项目开放时必须满足：

1. 专用只读账号，只授批准视图/固定列的 `SELECT`；不使用 root、应用写账号或通配来源主机。
2. 数据库 GRANT 是第一道边界；JDBC `readOnly=true` 不能替代数据库权限。
3. 验证 `INSERT/UPDATE/DELETE/DDL` 以及非批准对象查询全部失败。
4. 只提供固定投影，禁止 `SELECT *`、动态表名、动态列名和外部输入 SQL 片段。
5. 参数必须绑定；先应用公司和半开时间窗口，再排序和分页。
6. 默认不开放摘要、请求 ID、actor、绑定 ID、坐标和敏感正文。
7. 凭据只放环境变量/KMS/权限为 `0600` 的仓库外文件，不进入 Git、Markdown、截图、聊天或日志。
8. 对外视图/API必须版本化；增加、删除或改变字段语义时发布新版本，不静默改变旧合同。
9. 使用合成/脱敏样本做契约测试，覆盖零行、多行、未知状态、未知枚举、跨公司和时间边界。

## 11. 已知限制与不得误读事项

- `raw_attendance_fact` 中存在的行不一定是有效打卡；未匹配/歧义记录会留在原始和规范化层。
- 作业 `accepted/quarantined` 计数不等于新增 raw/隔离行数：安全重放不新增 raw，期间未知可在 raw 前计隔离，全页隔离还会回滚。
- `effective_attendance_event` 只有事件，不直接保存“来自哪个厂商”；必须经 `evidence_link` 证明得力血缘。
- `AUTO` 只是未知进/出方向，不能解释为上班或下班。
- 得力 `terminal_id`、`check_type`、`user_id/ext_id` 和 `check_data` 没有独立 raw 列，不能通过摘要反推出原值。
- V22 坐标列存在不等于当前得力适配器已写坐标；当前适配器仍给地点空值和 `UNKNOWN`。
- `attendance_report_daily_fact` 只能表示投影中的每日首卡/末卡及汇总结果，不能还原全部打卡。
- `employee_match_decision.employment_period_id` 的当前代码语义与 V8 外键目标存在漂移；修复并验证前，匹配成功的得力同步可能失败。
- 定时同步传入的 `SYSTEM` 主体未由 V1—V40 创建；启用调度前必须补受控系统主体或修正主体模型。
- 当前没有与公司级时间范围出站查询完全匹配的索引，也没有向 OA 发送晚到/撤回变更的 change feed；均须在生产开放前单独交付。
- 当前 OA 自动同步未闭环；启用 OA 只读连接池也不会自动查询七类表单。
- OA 字段清单是截图候选合同；实库取证状态仍为 `PARTIAL`，不能自行升级为已签署生产字典。
- 签字矩阵与本文均采用 2026-08-10 证据台账的“18 项 `REVIEWED`、1 项 `PARTIAL`”状态；技术取证已审阅不等于七类运行合同签署，故总体仍维持 `NOT_VERIFIED`。
- 当前本机开发数据库在本次整理时未用于验证实时行数；本文描述的是代码/DDL 与已有只读取证，不声称当前生产已有多少得力打卡。

## 12. 完整来源索引

行号会随代码变化。迁移名、类名、方法名、表名和章节名比行号更稳定。

绝对路径只证明当前机器可定位，不代表文件已纳入 Git，也不代表允许外发。尤其是本机生成材料、PDF 转写和标注为“授权本机”的取证脚本，必须按交付对象重新脱敏和授权。

### 12.1 得力接口、转换与入库

| 内容 | 仓库相对路径 | 当前机器绝对路径 |
|---|---|---|
| 得力接口、字段解析、摘要（引用范围避开源文件内的人员示例） | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/deli/DeliEplusClient.java:27-29,69-178,236-274,338-515,545-603` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/deli/DeliEplusClient.java:27-29,69-178,236-274,338-515,545-603` |
| 得力标准端口记录 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/port/DeliPunchSourcePort.java:51-124` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/port/DeliPunchSourcePort.java:51-124` |
| 得力页事务、隔离计数与实际落库 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/application/DeliPunchPageTransaction.java:58-297,349-493` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/application/DeliPunchPageTransaction.java:58-297,349-493` |
| 证据链 Java 行模型 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/application/EvidenceRows.java:11-126` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/application/EvidenceRows.java:11-126` |
| 实际写入/追踪 Mapper | `backend/src/main/resources/mappers/AttendanceEvidenceMapper.xml:24-96,98-186,188-220` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/mappers/AttendanceEvidenceMapper.xml:24-96,98-186,188-220` |
| 人员工号/绑定及稳定任职 ID 解析 SQL | `backend/src/main/resources/mappers/EvidenceEmployeeResolverMapper.xml:7-87` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/mappers/EvidenceEmployeeResolverMapper.xml:7-87` |
| 同步来源、作业和水位 SQL | `backend/src/main/resources/mappers/AttendanceSourceSyncMapper.xml:57-162,206-329,450-600` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/mappers/AttendanceSourceSyncMapper.xml:57-162,206-329,450-600` |
| 定时同步 `SYSTEM` 主体调用 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/application/DeliPunchSyncApplicationService.java:80-101` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/application/DeliPunchSyncApplicationService.java:80-101` |
| 定时任务创建 job 的主体值 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/persistence/MyBatisAttendanceSourceSyncRepository.java:248-268` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/persistence/MyBatisAttendanceSourceSyncRepository.java:248-268` |
| 生产月报读取有效打卡 | `backend/src/main/resources/mappers/AttendanceReportCalculationMapper.xml:73-98` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/mappers/AttendanceReportCalculationMapper.xml:73-98` |
| 得力连接配置和默认时区 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/deli/DeliEplusProperties.java:7-22,84-127` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/deli/DeliEplusProperties.java:7-22,84-127` |
| 详细得力字段血缘说明 | `docs/customer-delivery/神州HR考勤与OA数据取数及计算逻辑说明.md:162-412` | `/Users/huzhijin/Downloads/shenzhouHR/docs/customer-delivery/神州HR考勤与OA数据取数及计算逻辑说明.md:162-412` |
| 得力联调记录 | `docs/contracts/deli-eplus-live-verification.md:1` | `/Users/huzhijin/Downloads/shenzhouHR/docs/contracts/deli-eplus-live-verification.md:1` |

### 12.2 神州 HR 数据库结构

| 内容 | 仓库相对路径 | 当前机器绝对路径 |
|---|---|---|
| 来源、raw、normalized、match、event、lifecycle、link、recalc 初始 DDL | `backend/src/main/resources/db/migration/V8__attendance_source_and_evidence.sql:1-135,240-350,352-436,512-572,682-706` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/db/migration/V8__attendance_source_and_evidence.sql:1-135,240-350,352-436,512-572,682-706` |
| `legal_entity_id → company_id` 最终改名 | `backend/src/main/resources/db/migration/V11__unify_company_dimension.sql:132-165` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/db/migration/V11__unify_company_dimension.sql:132-165` |
| raw 坐标扩展 | `backend/src/main/resources/db/migration/V22__deli_punch_map_coordinates.sql:1-68` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/db/migration/V22__deli_punch_map_coordinates.sql:1-68` |
| 员工版本和工号字段 | `backend/src/main/resources/db/migration/V5__people_initial_import_and_versioning.sql:194-241` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/db/migration/V5__people_initial_import_and_versioning.sql:194-241` |
| 新任职分别生成 period/assignment UUID | `backend/src/main/java/com/szsemicon/hr/people/application/PeopleManagementService.java:445-449` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/people/application/PeopleManagementService.java:445-449` |
| 得力来源范围绑定及历史 | `backend/src/main/resources/db/migration/V28__deli_binding_and_connectivity_status.sql:1-84` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/db/migration/V28__deli_binding_and_connectivity_status.sql:1-84` |
| 日报投影和首末卡字段 | `backend/src/main/resources/db/migration/V10__formal_attendance_reporting.sql:1-141` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/db/migration/V10__formal_attendance_reporting.sql:1-141` |
| 生产 UTC JDBC 配置 | `deploy/baota/mysql/provision.sh:217` | `/Users/huzhijin/Downloads/shenzhouHR/deploy/baota/mysql/provision.sh:217` |

### 12.3 OA 字段、转换器与实库证据

| 内容 | 仓库相对路径 | 当前机器绝对路径 |
|---|---|---|
| OA 七类/11 表签字矩阵 | `docs/contracts/oa-attendance-form-mapping-signoff-matrix.md` | `/Users/huzhijin/Downloads/shenzhouHR/docs/contracts/oa-attendance-form-mapping-signoff-matrix.md` |
| OA 七类逐字段中文说明 | `docs/customer-delivery/神州HR考勤与OA数据取数及计算逻辑说明.md` | `/Users/huzhijin/Downloads/shenzhouHR/docs/customer-delivery/神州HR考勤与OA数据取数及计算逻辑说明.md` |
| 销假单运行时白名单前向迁移 | `backend/src/main/resources/db/migration/V40__oa_leave_revocation_form_contract.sql:1-32` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/resources/db/migration/V40__oa_leave_revocation_form_contract.sql:1-32` |
| Java 七类静态表/列白名单（含销假） | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaStaticFormMappingCatalog.java:14-26,31-64,357-445,617-633` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaStaticFormMappingCatalog.java:14-26,31-64,357-445,617-633` |
| 请假/销假共享请假类型业务目录声明 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaLeaveTypeShowValueCatalog.java:6-29` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaLeaveTypeShowValueCatalog.java:6-29` |
| 请假—销假一对多、精确关联、不重叠返还和累计小时策略 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaLeaveEffectiveIntervalResolver.java` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaLeaveEffectiveIntervalResolver.java` |
| 请假—销假区间策略的正/负测试 | `backend/src/test/java/com/szsemicon/hr/wave4/OaLeaveEffectiveIntervalResolverTest.java` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/test/java/com/szsemicon/hr/wave4/OaLeaveEffectiveIntervalResolverTest.java` |
| OA 行转换与未知列拒绝 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaFormRowTransformer.java:157-193,214-315` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaFormRowTransformer.java:157-193,214-315` |
| `org_member` 只读查询 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/oa/OaMysqlOrgMemberDirectoryAdapter.java:13-65` | `/Users/huzhijin/Downloads/shenzhouHR/backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/oa/OaMysqlOrgMemberDirectoryAdapter.java:13-65` |
| 2026-08-10 OA 实库证据台账 | `docs/verification/oa-live/2026-08-10/EVIDENCE.md:1-22,74-190,193-328,330-552,605-660` | `/Users/huzhijin/Downloads/shenzhouHR/docs/verification/oa-live/2026-08-10/EVIDENCE.md:1-22,74-190,193-328,330-552,605-660` |
| OA 旧版只读取证 SQL（仅限授权本机；文件头含内部联调信息，禁止整份外发） | `docs/contracts/oa-live-resolution-queries.sql:31-226` | `/Users/huzhijin/Downloads/shenzhouHR/docs/contracts/oa-live-resolution-queries.sql:31-226` |
| OA 截图专用取证 SQL | `docs/contracts/oa-live-resolution-screenshot-queries.sql:1-489` | `/Users/huzhijin/Downloads/shenzhouHR/docs/contracts/oa-live-resolution-screenshot-queries.sql:1-489` |
| 致远 V80 厂商字典 PDF | 当前项目外文件 | `/Users/huzhijin/Downloads/V80数据字典.pdf` |
| V80 字典文本转写：`COL_SUMMARY`、`CTP_ENUM_ITEM`、`ORG_MEMBER` | `tmp/pdfs/V80数据字典.txt:2548-2588,3515-3529,11514-11525` | `/Users/huzhijin/Downloads/shenzhouHR/tmp/pdfs/V80数据字典.txt:2548-2588,3515-3529,11514-11525` |

### 12.4 安全与操作边界

| 内容 | 仓库相对路径 | 当前机器绝对路径 |
|---|---|---|
| S3 考勤证据分类 | `docs/docs-confirm/07-security-and-permissions.md:3-12` | `/Users/huzhijin/Downloads/shenzhouHR/docs/docs-confirm/07-security-and-permissions.md:3-12` |
| OA 联调最小权限步骤 | `docs/user-guide/10-外部数据接入与验收.md:95-127` | `/Users/huzhijin/Downloads/shenzhouHR/docs/user-guide/10-外部数据接入与验收.md:95-127` |
| 本机生成 Wiki 证据接入摘要（可选辅助材料，非源码权威） | `.llm-wiki/modules/evidence-ingestion.md:1` | `/Users/huzhijin/Downloads/shenzhouHR/.llm-wiki/modules/evidence-ingestion.md:1` |
| 本机生成 Wiki 数据流（可选辅助材料，非源码权威） | `.llm-wiki/architecture/data-flow.md:1` | `/Users/huzhijin/Downloads/shenzhouHR/.llm-wiki/architecture/data-flow.md:1` |

## 13. 给其他项目或 Agent 的最小读取指令

复制本文后，可把以下文字连同文件交给其他项目或 Agent：

```text
请以本 Markdown 的 CURRENT_CODE 和 Flyway/Mapper 引用为结构权威；
以 2026-08-10 EVIDENCE 为实库技术取证，但不要把技术分布自动解释为业务签字；
读取神州 HR 得力有效打卡时，使用 effective_attendance_event，
检查最新 lifecycle=ACTIVATED，并通过 evidence_link 证明 DELI_CLOUD 来源；
不要把 raw_attendance_fact 或 attendance_report_daily_fact 当完整有效流水；
	OA 七类表单字段仍是候选合同，未知状态、枚举、外键、时区和日期边界必须 fail closed；
	请假 field0097 与销假 field0099 精确关联：一张请假允许多张销假，
	每张已批准单形成一个连续返还区间和 field0107 小时事实；区间重叠、累计超过原 field0103 已扣减小时、错配或审批未知必须 fail closed，不能最后一张覆盖；
	所有生产 SQL 必须固定投影、参数绑定、公司范围过滤、半开时间窗和有界分页。
```
