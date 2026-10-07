# 核心领域模型与数据库逻辑模型

## 1. 建模约定

- 内部 ID 使用不可枚举 UUID/ULID；外部 ID 用 `varchar` 保存原文，API 类型为 `string`。
- 有效期统一为半开区间 `[effective_from, effective_to)`；`effective_to = null` 表示当前有效。
- 金额用定点十进制并带币种；工时/天数用定点十进制；时间存 UTC 瞬时并另存业务时区/业务日期。
- 原始负载可加密保存或只保留受控对象存储引用；普通日志只记录摘要、分类和关联 ID。
- 所有可配置规则均有 `draft/published/retired` 生命周期、版本号、生效期、发布人与审计事件。

## 2. 员工身份绑定模型

```mermaid
classDiagram
  class Employee {
    string employee_id
    string legal_entity_id
    string status
    date onboard_date
  }
  class EmployeeSourceBinding {
    string binding_id
    string employee_id
    string seeyon_person_id
    string seeyon_oa_code
    string deli_user_id
    string deli_ext_id
    string deli_employee_num
    string binding_status
    datetime effective_from
    datetime effective_to
    string source
    string confirmation_ref
  }
  class IdentityMatchCase {
    string case_id
    string match_key
    string reason_code
    string status
    string resolved_by
  }
  Employee "1" --> "0..*" EmployeeSourceBinding
  EmployeeSourceBinding "0..*" --> "0..1" IdentityMatchCase
```

`EmployeeSourceBinding` 唯一性约束按来源和有效期执行；CODE 缺失、重复、变化或得力字段冲突时，绑定状态为 `NEEDS_REVIEW`，相关记录保留在原始层但不自动归属。解除或更换绑定只关闭旧有效期并新增记录，不能重写历史事实的 `employee_id`。

## 3. 组织身份、版本和任职历史模型

```mermaid
classDiagram
  class OrganizationIdentity {
    string organization_id
    string legal_entity_id
    string identity_status
  }
  class OrganizationVersion {
    string organization_version_id
    string organization_id
    string parent_organization_id
    string code
    string name
    string org_type
    datetime effective_from
    datetime effective_to
    string formal_sync_batch_id
  }
  class OrganizationSourceBinding {
    string source_binding_id
    string organization_id
    string source_system
    string source_org_id
    datetime effective_from
    datetime effective_to
  }
  class EmploymentAssignment {
    string assignment_id
    string employee_id
    string organization_id
    string position_id
    string payroll_plan_id
    string cost_center_id
    datetime effective_from
    datetime effective_to
  }
  class CurrentOrganizationProjection {
    string organization_id
    string current_version_id
    string projection_batch_id
  }
  OrganizationIdentity "1" --> "1..*" OrganizationVersion
  OrganizationIdentity "1" --> "1..*" OrganizationSourceBinding
  OrganizationIdentity "1" --> "0..*" EmploymentAssignment
  OrganizationIdentity "1" --> "0..1" CurrentOrganizationProjection
```

自动同步刷新当前投影并保留批次、指纹、计数和异常；管理员执行“组织架构变更同步”时把差异集固化为正式版本。拆分、合并或来源 ID 复用通过新身份/绑定和变更关系表达，不复用旧身份。历史查询以业务发生时间解析有效版本。

## 4. 考勤领域模型

```mermaid
classDiagram
  class RawSourceRecord {
    string raw_record_id
    string source_system
    string source_record_id
    string sync_batch_id
    datetime received_at
    string payload_digest
    string payload_ref
  }
  class StandardCheckin {
    string checkin_id
    string employee_id
    datetime occurred_at
    string checkin_type
    decimal longitude_raw
    decimal latitude_raw
    string coordinate_system
    string coordinate_validation_status
    string coordinate_conversion_status
  }
  class AttendanceFact {
    string fact_id
    string employee_id
    string fact_type
    datetime start_at
    datetime end_at
    string approval_status
    string source_ref
  }
  class RuleVersion {
    string rule_version_id
    string rule_type
    string scope_type
    string scope_id
    datetime effective_from
    datetime effective_to
  }
  class AttendanceInputSnapshot {
    string snapshot_id
    string employee_id
    date business_date
    string organization_version_id
    string rule_version_set_id
    string input_watermark
  }
  class DailyAttendanceResult {
    string result_id
    string snapshot_id
    string status
    decimal payable_hours
    decimal overtime_hours
    string reason_codes
  }
  class ManualAdjustment {
    string adjustment_id
    string prior_result_id
    string reason
    string evidence_ref
  }
  class AttendanceClosureVersion {
    string closure_version_id
    string period
    string scope_id
    string status
    string input_watermark
  }
  RawSourceRecord "1" --> "0..*" StandardCheckin
  RawSourceRecord "1" --> "0..*" AttendanceFact
  StandardCheckin "0..*" --> "1" AttendanceInputSnapshot
  AttendanceFact "0..*" --> "1" AttendanceInputSnapshot
  RuleVersion "1..*" --> "0..*" AttendanceInputSnapshot
  AttendanceInputSnapshot "1" --> "1..*" DailyAttendanceResult
  DailyAttendanceResult "1" --> "0..*" ManualAdjustment
  DailyAttendanceResult "0..*" --> "1" AttendanceClosureVersion
```

位置模型必须保存经度、纬度、位置名称、打卡方式、原始坐标字符串、坐标系、校验状态、转换状态和来源。坐标系为 `UNKNOWN`、缺失、越界或未验证时，接口返回状态和原始证据，但地图层不得产生定位点。

## 5. 工资分段和工资项目模型

```mermaid
classDiagram
  class PayrollPeriod {
    string payroll_period_id
    string period
    string status
  }
  class PayrollInputSnapshot {
    string payroll_input_snapshot_id
    string payroll_period_id
    string attendance_closure_version_id
    string master_data_snapshot_id
    string rule_version_set_id
    string digest
  }
  class PayrollRun {
    string payroll_run_id
    string input_snapshot_id
    string run_type
    string status
    int version
  }
  class PayrollSegment {
    string payroll_segment_id
    string payroll_run_id
    string employee_id
    datetime effective_from
    datetime effective_to
    string organization_id
    string position_id
    string payroll_plan_id
    string cost_center_id
    decimal workdays
    decimal actual_hours
  }
  class PayrollItemDefinition {
    string item_definition_id
    string code
    string category
    string sensitivity
    string formula_version_id
  }
  class PayrollSegmentItem {
    string segment_item_id
    string payroll_segment_id
    string item_definition_id
    decimal amount
    string currency
    string calculation_trace_ref
  }
  class Payslip {
    string payslip_id
    string payroll_run_id
    string employee_id
    string publication_status
  }
  class PayrollAdjustment {
    string adjustment_id
    string source_payroll_run_id
    string adjustment_type
    string reason
  }
  PayrollPeriod "1" --> "1..*" PayrollInputSnapshot
  PayrollInputSnapshot "1" --> "1..*" PayrollRun
  PayrollRun "1" --> "1..*" PayrollSegment
  PayrollSegment "1" --> "1..*" PayrollSegmentItem
  PayrollItemDefinition "1" --> "0..*" PayrollSegmentItem
  PayrollRun "1" --> "0..*" Payslip
  PayrollRun "1" --> "0..*" PayrollAdjustment
```

分段边界取任职、岗位、工资方案、成本归属或计薪口径的有效期并集。实际工作日和实际工时是两种显式计薪基数，公式不得混用。员工月中从销售部调至采购部时，调动生效日归新部门，两个分段分别展示工作日/工时、项目金额和成本，分段之和与员工期间金额严格相等。

## 6. 权限与数据范围模型

```mermaid
classDiagram
  class Principal {
    string principal_id
    string employee_id
    string status
  }
  class RoleAssignment {
    string assignment_id
    string role_code
    datetime valid_from
    datetime valid_to
  }
  class PermissionGrant {
    string permission_code
    string domain
    string action
    string purpose
  }
  class DataScope {
    string scope_id
    string scope_type
    string organization_version_ref
    string object_filter
    datetime valid_from
    datetime valid_to
  }
  class FieldPolicy {
    string field_policy_id
    string resource_type
    string field_name
    string effect
    string mask_strategy
  }
  class ExportGrant {
    string export_grant_id
    string resource_type
    string watermark_policy
  }
  class AuthorizationDecision {
    string decision_id
    string effect
    string reason_code
    string policy_version
  }
  Principal "1" --> "0..*" RoleAssignment
  RoleAssignment "1" --> "1..*" PermissionGrant
  RoleAssignment "1" --> "0..*" DataScope
  RoleAssignment "1" --> "0..*" FieldPolicy
  RoleAssignment "1" --> "0..*" ExportGrant
  Principal "1" --> "0..*" AuthorizationDecision
```

最终允许集合是身份状态、RBAC、组织范围、对象关系、字段、目的、时间、操作和导出权限的交集；任一缺失即拒绝。策略预览使用同一决策引擎但仅返回解释，不签发可用于真实请求的授权。

## 7. 数据库逻辑 ER 图

```mermaid
erDiagram
  EMPLOYEE ||--o{ EMPLOYEE_SOURCE_BINDING : has
  EMPLOYEE ||--o{ EMPLOYMENT_ASSIGNMENT : holds
  ORGANIZATION_IDENTITY ||--o{ ORGANIZATION_VERSION : versions
  ORGANIZATION_IDENTITY ||--o{ ORGANIZATION_SOURCE_BINDING : binds
  ORGANIZATION_IDENTITY ||--o{ EMPLOYMENT_ASSIGNMENT : scopes
  SYNC_BATCH ||--o{ RAW_SOURCE_RECORD : receives
  OA_MAPPING_PROFILE ||--o{ RAW_SOURCE_RECORD : interprets
  RAW_SOURCE_RECORD ||--o{ STANDARD_CHECKIN : normalizes
  RAW_SOURCE_RECORD ||--o{ ATTENDANCE_FACT : normalizes
  ATTENDANCE_INPUT_SNAPSHOT ||--o{ DAILY_ATTENDANCE_RESULT : calculates
  RULE_VERSION ||--o{ ATTENDANCE_INPUT_SNAPSHOT : freezes
  DAILY_ATTENDANCE_RESULT ||--o{ MANUAL_ADJUSTMENT : adjusts
  ATTENDANCE_CLOSURE_VERSION ||--o{ DAILY_ATTENDANCE_RESULT : closes
  PAYROLL_PERIOD ||--o{ PAYROLL_INPUT_SNAPSHOT : freezes
  ATTENDANCE_CLOSURE_VERSION ||--o{ PAYROLL_INPUT_SNAPSHOT : feeds
  PAYROLL_INPUT_SNAPSHOT ||--o{ PAYROLL_RUN : executes
  PAYROLL_RUN ||--o{ PAYROLL_SEGMENT : splits
  PAYROLL_SEGMENT ||--o{ PAYROLL_SEGMENT_ITEM : contains
  PAYROLL_ITEM_DEFINITION ||--o{ PAYROLL_SEGMENT_ITEM : defines
  PAYROLL_RUN ||--o{ PAYSLIP : publishes
  PRINCIPAL ||--o{ ROLE_ASSIGNMENT : receives
  ROLE_ASSIGNMENT ||--o{ DATA_SCOPE : limits
  AUDIT_EVENT ||--o{ AUDIT_EVENT_LINK : chains
```

## 8. 核心实体定义与唯一约束

| 实体 | 含义 | 关键唯一/检查约束 |
|---|---|---|
| `Employee` | 永久员工身份 | `employee_id` 不可变；不以工号为主键 |
| `EmployeeSourceBinding` | 员工与来源身份的带时态绑定 | 同来源外部 ID 的有效期不得重叠；冲突不激活 |
| `OrganizationIdentity` | 不随名称/编码变化的组织身份 | 法人范围内不可复用 |
| `OrganizationVersion` | 某时间段有效的组织属性 | 同组织版本有效期不重叠；父级不得形成环 |
| `OAQueryMappingProfile` | 自建表物理字段到逻辑契约的发布版本 | 同业务类型同生效时点仅一个发布版本 |
| `RawSourceRecord` | 不可变来源证据 | `(source_system, source_record_id, source_revision)` 唯一 |
| `SyncWatermark` | 外部增量读取位置 | 按租户/接口/范围唯一，CAS 更新，不做算术 |
| `DailyAttendanceResult` | 某快照下的日计算版本 | `(employee_id, date, snapshot_id, algorithm_version)` 唯一 |
| `AttendanceClosureVersion` | 月结冻结版本 | 同范围/期间仅一个当前关闭版本；旧版本保留 |
| `PayrollInputSnapshot` | 工资计算所有输入的冻结引用 | 内容摘要不可变 |
| `PayrollSegment` | 员工期间内同一归属/方案的有效段 | 同员工/运行内段不重叠、无空段 |
| `AuditEvent` | 安全和业务审计事件 | 追加写；包含前一事件哈希、事件哈希和签名/受保护存储证据 |

## 9. 数据生命周期

- 原始同步证据、月结、已发布工资、工资条和安全审计按企业合规策略长期保留；具体年限在上线前由法务/财务确认。
- 临时导出文件默认最长 24 小时，一次性链接默认 10 分钟；到期删除对象但保留不含敏感内容的导出审计。
- 缓存仅保存可重建、已授权且最小化的数据；工资金额不进入共享缓存或浏览器持久存储。
- 删除请求以法定保留和审计完整性为前提，优先停用/隔离/加密擦除，绝不级联删除历史考勤和工资事实。
