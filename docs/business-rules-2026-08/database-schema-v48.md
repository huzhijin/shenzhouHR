# V48 业务规则对齐数据库结构说明

## 版本说明

早期规划草案曾以“V32”作为占位编号，但仓库已有 V32～V47。现 OpenSpec、迁移文件和部署文档均已统一为 V48；为保持 Flyway 迁移链只增不改，本次文件是：

- [V48__business_rules_alignment_schema.sql](../../backend/src/main/resources/db/migration/V48__business_rules_alignment_schema.sql)
- [ROLLBACK_V48__business_rules_alignment_schema.sql](../../backend/src/main/resources/db/migration/ROLLBACK_V48__business_rules_alignment_schema.sql)

部署、验收、审计和故障沟通必须统一称为 **V48**。不得把现有 `V32__attendance_rate_formula_correction.sql` 改名、覆盖或重新计算 checksum。V48 依赖 V46 的 `SYSTEM` 主体和 V47 的 `scheduled_attendance_days`、`actual_attendance_days` 字段。

本文只说明结构合同；本机隔离开发库的 V1→V48 迁移与回滚证据见[迁移演练记录](../verification/business-rules-alignment-2026-08/migration-rehearsal-2026-08-17.md)。该证据不代表预发布或生产已经迁移。

## 结构差异

| 对象 | V48 变化 | 关键约束或索引 | 业务目的 |
|---|---|---|---|
| `attendance_report_daily_fact.leave_type` | 新增可空 `VARCHAR(50)` | `ix_att_report_daily_leave_type` | 保留具体假别，支持病假单列 |
| `punch_correction_request` | 新增补卡申请表 | 主键；员工/月份/状态索引；员工/日期/状态唯一键；状态、方向、月份对齐检查 | 每人每自然月一次补卡配额及审批审计 |
| `auth_capability` / `auth_role_capability` | 新增并授予补卡读、创建、批准能力 | 能力码唯一约束沿用既有表 | 服务端权限校验 |
| `oa_enum_mapping` | 新增 OA 枚举映射表 | OA 表/字段/枚举唯一；业务含义唯一；枚举 ID 二选一检查 | 可审计地映射 OA 加班类别 |
| `deli_sync_log` | 新增得力同步日志表 | 状态/时间和最新记录索引；状态及完成时间检查 | 仅显示同步时间、记录数、状态和错误，不做红黄灯门禁 |
| `oa_attendance_document_context.overtime_type` | 新增可空 `VARCHAR(20)` | 索引；值限定为 `PAID`、`COMPENSATORY`、`VOLUNTARY` | 在 OA 证据上下文保留三类加班 |
| `attendance_report_daily_fact` 加班字段 | 新增四个非空无符号 `BIGINT` 分钟字段 | 总数必须等于三类之和 | 分别展示计薪、转调休、义务及汇总加班 |

## 补卡表合同

`punch_correction_request` 的核心字段如下：

| 字段 | 含义 |
|---|---|
| `punch_correction_request_id` | 36 位字符串申请 ID |
| `employee_id` | 申请对应员工 |
| `request_month` | `business_date` 所在自然月的月初日期 |
| `business_date` | 需要补卡的工作日 |
| `punch_side` | `ENTRY`、`EXIT` 或 `BOTH` |
| `correction_reason` | 员工补卡原因，最长 500 字符 |
| `status` | `PENDING`、`APPROVED`、`REJECTED` 或 `CANCELLED` |
| `requested_at` / `requested_by` | 提交时间和主体 |
| `reviewed_at` / `reviewed_by` / `review_notes` | 审批信息 |

配额由应用层在员工锁内计算；`PENDING` 和 `APPROVED` 占用当月一次配额，`REJECTED` 和 `CANCELLED` 不占用。数据库的月份对齐检查保证 `request_month` 与补卡业务日属于同一自然月。审批通过前不得影响缺卡结果。

V48 同时建立以下能力：

- `ATTENDANCE_PUNCH_CORRECTION:READ`
- `ATTENDANCE_PUNCH_CORRECTION:CREATE`
- `ATTENDANCE_PUNCH_CORRECTION:APPROVE`

员工本人、部门负责人、HR 和系统管理员获得读/创建能力；只有 HR 和系统管理员获得批准能力。最终授权仍以服务端 capability 与数据范围共同判定，前端可见性不能扩大权限。

## OA 加班映射种子

V48 为 `formson_0172.field0096` 写入三条已验证映射：

| OA 枚举 ID | 业务含义 | 显示值 | 验证记录数 |
|---:|---|---|---:|
| `-6539634143789166714` | `OVERTIME_PAID` | 加班费 | 112,022 |
| `5912806790045781226` | `OVERTIME_COMPENSATORY` | 调休 | 5,066 |
| `4337518111002608138` | `OVERTIME_VOLUNTARY` | 义务加班 | 89 |

空值或未知枚举不进入任何分类及汇总，必须隔离并记录。三类日事实分钟数之和必须等于 `total_overtime_minutes`。

## 只读迁移核验

以下 SQL 不修改业务数据。所有结果符合预期后，才能在部署清单中记录 V48 数据库门禁通过。

```sql
SELECT version, description, success
FROM flyway_schema_history
WHERE version IN ('46', '47', '48')
ORDER BY installed_rank;

SELECT table_name, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND (
    (table_name = 'attendance_report_daily_fact'
      AND column_name IN (
        'scheduled_attendance_days', 'actual_attendance_days', 'leave_type',
        'paid_overtime_minutes', 'compensatory_overtime_minutes',
        'voluntary_overtime_minutes', 'total_overtime_minutes'
      ))
    OR (table_name = 'oa_attendance_document_context'
      AND column_name = 'overtime_type')
  )
ORDER BY table_name, ordinal_position;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'punch_correction_request', 'oa_enum_mapping', 'deli_sync_log'
  )
ORDER BY table_name;

SELECT business_meaning, display_label, enum_id_bigint,
       verification_record_count, is_active
FROM oa_enum_mapping
WHERE oa_table_name = 'formson_0172'
  AND oa_field_name = 'field0096'
ORDER BY business_meaning;

SELECT capability_code
FROM auth_capability
WHERE capability_code LIKE 'ATTENDANCE_PUNCH_CORRECTION:%'
ORDER BY capability_code;
```

预期：V46、V47、V48 均成功；7 个日事实字段和 1 个 OA 上下文字段存在；3 张新表存在；OA 映射和补卡能力各 3 条。

## 数据与兼容性边界

- 新规则从部署切换后向前生效，不对历史月份自动回填或改写已发布投影。
- V48 是新增表、字段、索引、检查约束和权限种子的前向迁移；旧应用通常可忽略新增结构，因此代码回滚时优先保留 V48，而不是立即删表删列。
- MySQL DDL 可能隐式提交，不能把整份 V48 假设为单一可回滚事务。
- 回退脚本会删除补卡、同步日志、OA 映射和新报表字段中的数据，只能按[分阶段回滚方案](rollback-plan.md)在停写、备份和影响确认后执行。
- 不得使用 `flyway repair` 掩盖 checksum、执行中断或手工回退造成的历史差异。
