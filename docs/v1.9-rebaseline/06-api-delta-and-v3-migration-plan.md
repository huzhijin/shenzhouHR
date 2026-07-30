# V1.9 API 差异与 V3+ 数据迁移计划

## 1. 当前契约

`api/openapi.yaml` 当前只有：

- `GET /api/v1/me/capabilities`
- `GET /api/v1/organization-units`
- `GET /api/v1/employees`

三条端点保持兼容。后续先修复 `/employees` 的 400 声明和 SSO 待定描述，再逐波扩展。

本机 MySQL 的实施拓扑、账号分权、数据库生命周期与真实验证矩阵见 `../docs-confirm-v1.9/03-local-mysql-development-and-test-plan.md`。该授权只在后续已获授权的实施波次生效；本轮没有建库或执行迁移，当前状态为 `LOCAL_MYSQL_VALIDATION=NOT_RUN`。

## 2. 全局 API 合同

- Base path：`/api/v1`；JSON 使用明确 DTO，不直接暴露数据库实体。
- 认证：session cookie 固定为 host-only、`Path=/`、`HttpOnly`、`SameSite=Lax`；生产/HTTPS 必须 `Secure`，只有显式本地 loopback 开发 profile 可关闭 `Secure`。状态变更请求执行 CSRF 防护。
- 关联：接受或生成 `X-Correlation-ID`，审计保存 `request_id`。
- 幂等：批次创建/发布、调整、重算、月结等接受 `Idempotency-Key`；服务端按主体、动作和规范请求摘要判重。
- 并发：可变资源携带 `version`/ETag；陈旧写入返回 409。
- 分页：统一 `page/size/sort`，上限受控；越界参数返回 400。
- 错误：统一 `{code,message,correlationId,fieldErrors?,details?}`；不回显 SQL、凭据、文件路径或工资敏感值。
- 文件：上传限制 MIME、扩展、大小和解压风险；不执行宏或公式；原文件通过受控对象引用访问。
- 日期时间：API 使用 ISO-8601，业务日期与 instant 分开，时区显式。

## 3. 建议 API 家族

### 3.1 波次 1：账号、session、权限、规则底座

- `POST /auth/login`
- `POST /auth/password/change`
- `POST /auth/password-reset-requests`
- `POST /auth/password-resets`
- `POST /auth/logout`
- `GET /auth/session`
- `POST /access/accounts`、`PATCH /access/accounts/{accountId}`
- `POST /access/accounts/{accountId}/reset-password`
- `POST /access/accounts/{accountId}/sessions/revoke`
- `GET/POST /rule-policies`
- `GET/PATCH /rule-policies/{policyId}/versions/{versionId}`
- `POST .../validate`、`.../preview`、`.../publish`、`.../deactivate`、`.../rollback`

### 3.2 波次 2：组织/员工期初导入与本地维护

- `GET /people-imports/templates/{type}`
- `POST /people-imports`
- `PUT /people-imports/{batchId}/mapping`
- `POST /people-imports/{batchId}/precheck`
- `GET /people-imports/{batchId}/diff`
- `POST /people-imports/{batchId}/publish`
- `POST /people-imports/{batchId}/void`
- `GET /people-imports/{batchId}/errors`
- `POST/PATCH /organization-units`
- `POST/PATCH /employees`
- `POST /employees/{employeeId}/employment-periods`
- `POST /employees/{employeeId}/prior-service-adjustments`

### 3.3 波次 3：考勤设置

- `/attendance-groups`
- `/attendance-groups/{id}/assignments`
- `/shift-templates`、`/shift-versions`
- `/work-calendars`、`/locations`
- 类型化餐扣、宽限、缺卡策略继续通过 `/rule-policies` 管理。

### 3.4 波次 4：在线/离线来源与统一事实

- `GET /attendance-punch-imports/template`
- `GET/POST /attendance-punch-mapping-profiles`
- `POST /attendance-punch-imports`
- `PUT /attendance-punch-imports/{batchId}/mapping`
- `POST /attendance-punch-imports/{batchId}/precheck`
- `GET /attendance-punch-imports/{batchId}/preview`
- `GET /attendance-punch-imports/{batchId}/errors`
- `POST /attendance-punch-imports/{batchId}/publish`
- `POST /attendance-punch-imports/{batchId}/void-or-reverse`
- `GET /attendance-punch-imports/{batchId}/rows/{rowId}`
- `GET /attendance-events/{eventId}/evidence`
- `/attendance-sources/deli/*`、`/attendance-sources/oa/*`、`/attendance-source-jobs/*`

上传响应只创建草稿批次；未经预检与二次确认不得发布。冻结冲突返回 409 和明确 reason code。

### 3.5 波次 5～7：计算、月结、假期、自助

- `/attendance-daily-results`、`/attendance-exceptions`
- `/attendance-adjustments`
- `/attendance-recalculations`
- `POST /attendance-periods/{period}/freeze`（`freezeAttendancePeriod`）
- `POST /attendance-periods/{period}/close`（`closeAttendancePeriod`）
- `POST /attendance-periods/{period}/reopen`（`reopenAttendancePeriod`）
- `/leave-policy-versions`、`/annual-leave/previews`
- `/time-accounts`、`/time-account-ledger`
- `/time-account-opening-imports`
- `/me/attendance/*`、`/me/leave/*`、`/me/feedback`
- `/attendance-reports`

### 3.6 波次 8：薪资后端预留

仅在单独授权后增加内部 API，全部要求 `PAYROLL` capability，默认 feature flag 关闭。通用员工/组织 DTO 不得出现工资字段；本期不注册前端 route，不生成工资条或银行文件。

## 4. 典型离线打卡状态合同

| 状态 | 允许动作 | 禁止动作 |
|---|---|---|
| DRAFT | 上传、映射、删除未发布草稿 | 发布 |
| VALIDATING | 查询进度 | 修改映射、发布 |
| VALIDATION_FAILED | 查看/下载错误、修正后重新预检 | 发布 |
| AWAITING_CONFIRMATION | 预览、错误下载、再次预检、发布 | 未授权部分发布 |
| PUBLISHING | 查询进度 | 重复发布、修改 |
| PUBLISHED | 查看、受控作废/冲正 | 物理删除 |
| PARTIALLY_PUBLISHED | 查看成功/失败、错误下载、受控冲正 | 静默忽略错误 |
| PUBLISH_FAILED | 查看发布事务错误、按原因重试 | 假装发布成功 |
| VOIDED | 查看证据与冲正关系 | 恢复原记录 |
| BLOCKED_BY_FROZEN_PERIOD | 查看/下载；期间重开后再预检 | 直接发布或重算 |

## 5. V3+ 迁移序列

实际迁移注册表是版本号的唯一权威；以下表格已按当前仓库
`backend/src/main/resources/db/migration` 校正。已发布版本不可复用或改写，
未来版本只有在对应实现获授权并实际需要物理变更时才创建。

| 逻辑迁移 | 内容 | 依赖/回滚点 |
|---|---|---|
| V3 identity | account、credential、login failure、session、reset grant、权限域 | 回滚到 V2 应用版本；不删除新表数据 |
| V4 policy foundation | policy template/version/scope/assignment/snapshot/audit ref | 波次 1 验收后 |
| V5 people import | import template/batch/row/issue、employment period、prior service | 期初发布前可停止；已发布后只前向修复 |
| V6 people read prerequisite | 系统管理员最小人员读取前置能力 | V5 |
| V7 attendance setup | group、assignment、shift version、calendar、location、考勤专用策略 | 规则底座 |
| V8 evidence ingestion | source、raw fact、normalized、match、effective event、evidence link | 所有来源共享 |
| V9 punch Excel | mapping profile、punch batch/row、fingerprint、error report ref | V8 |
| V10 formal attendance reporting | 月度不可变报表投影、事实与导出存储 | V8/V9；不等同于自动月结编排完成 |
| V11 company boundary | 顶层公司表、所有当前公司外键列与 `COMPANY` scope 的一对一前向改名 | V1～V10；稳定 ID 和业务行不变 |
| V12 leave/time account | leave policy/version、annual leave qualification/tier/grant、account/ledger/opening import/expiry/reversal | 计划；以实际迁移注册表为准 |
| V13 self-service/reporting | feedback/progress、剩余自助/看板/报表能力 | 计划；以实际迁移注册表为准 |
| PAYROLL future | 独立 PAYROLL 权限与冻结快照引用 | 不预留版本号；单独授权且不得阻塞 P0-A |

## 6. V1/V2 兼容与退役

- 不编辑 V1/V2 文件或已存在 checksum。
- `MASTER_DATA:SYNC_PREVIEW` 不再授予新角色；V3+ 记录为 deprecated capability，待没有引用后前向停用。
- `formal_sync_batch_id`、`projection_batch_id` 保留兼容；新模型使用语义明确的 `initial_import_batch_id`、`local_version_id`。不得用改名迁移破坏历史引用。
- 迁移测试必须从空库执行 V1→当前版本，也要从真实 V2 快照升级。

## 7. 索引、唯一性与数据保护

- 账号用户名租户内唯一；在职员工主账号唯一。
- 任职周期禁止同员工重叠；具体通过数据库约束 + 事务校验实现。
- policy version、作用范围、生效期、发布状态建立查询索引。
- RawFact 的 source identity 和 record fingerprint 建唯一约束；近似重复不做唯一约束，只创建 review group。
- 文件 hash 唯一性包含租户和来源范围，允许重新预检但禁止重复发布事实。
- LedgerEntry、RawFact、AuditEvent 和已关闭 Snapshot 只追加/冲正。
- 工资、位置、假因和原始文件使用最小列选择、独立权限和访问审计。
- 所有新表使用显式主键、命名唯一/外键或等效完整性、受控状态、生效期、`row_version`、创建/修改主体与时间、查询/排序/分页索引以及幂等/去重索引。
- 致远 19 位及以上 ID、设备原始 ID 和外部系统标识使用 string/精确类型；API 不得把数据库 `BIGINT` 暴露为 JavaScript number。

## 8. 数据库验证门

每波必须在本机 `shenzhou_hr_test` 和 `shenzhou_hr_dev` 真实执行：

1. 记录实际 MySQL 版本、字符集、排序规则、strict SQL mode、UTC session 和目标库；
2. `flyway validate`；
3. 全新 `shenzhou_hr_test` 空库 V1→当前 migrate；
4. V1/V2 快照→当前升级；
5. 第二次 migrate 为 no-op，不重复建表或写目录数据；
6. Spring Boot 使用非 root 应用账号启动，API 真实读写本机 MySQL；
7. 唯一、外键/等效完整性、版本冲突、并发幂等、跨来源去重、事务回滚和索引测试；
8. 前端普通开发模式经 `/api` 展示真实 API/MySQL 数据，并覆盖 loading、empty、error、403、conflict 和 pagination；
9. demo 与真实模式互不污染；
10. 报告实际命令、迁移、表、索引、测试数、结果和测试库结束状态。

本机授权只覆盖 `shenzhou_hr_dev` 和 `shenzhou_hr_test`，不允许连接其他本机或外部数据库；不使用 Docker、Podman 或 Testcontainers。当前环境只有 MariaDB JDBC 配置和 H2 测试证据，本机 MySQL 和 MySQL 8.4 LTS 验证均为 `NOT_RUN`，不能宣称上述门已通过。本机通过后仍须在正式 MySQL 8.4 LTS 环境复验。
