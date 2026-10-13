# V1.9 本机 MySQL 开发与测试实施计划

> 阶段：`docs_confirm / AWAITING_HUMAN_CONFIRMATION`  
> 当前事实：`LOCAL_MYSQL_VALIDATION=NOT_RUN`、`MYSQL_8_4_RELEASE_VALIDATION=NOT_RUN`。  
> 本文件只增加后续实施授权、数据库设计边界和验收合同，不表示已经建库、建表、创建账号、执行迁移或完成真实联调。

## 1. 授权与非完成声明

- 后续获得对应实施波次授权后，可以直接连接本机 `localhost:3306` 的 MySQL；无需先配置 `mysql_config_editor` 或 login-path。
- 本轮只修订文档，没有连接 MySQL，没有读取或验证任何数据库凭据，也没有执行 SQL、Flyway、Spring Boot 或前后端联调。
- 本机 MySQL 授权只覆盖 `shenzhou_hr_dev` 和 `shenzhou_hr_test`。生产数据库、致远 OA 数据库、得力生产数据和其他本机数据库均不在授权范围。
- 数据库连接授权不等于源码实施授权。每个波次仍须单独获得明确授权，并同时交付 OpenAPI、V3+ 迁移、MyBatis、后端、权限、前端完整状态、自动化测试和真实本机 MySQL 证据。
- 不使用 Docker、Docker Compose、Podman 或 Testcontainers；真实集成门直接使用本机 MySQL。
- 正式生产基线仍为 MySQL 8.4 LTS；本机验证通过不能替代上线前的 MySQL 8.4 LTS 复验。

## 2. 本机 MySQL 拓扑

```text
前端开发服务器
  └─ /api 代理
      └─ Spring Boot（最小权限应用账号）
          ├─ shenzhou_hr_dev   开发与真实前后端联调
          └─ shenzhou_hr_test  自动化测试、迁移与约束验证

Flyway（独立本机迁移账号）
  ├─ shenzhou_hr_dev
  └─ shenzhou_hr_test

root（仅本机管理控制面，按需临时使用）
  └─ 建库、建账号、授权、迁移处置、测试库重建和本机故障排查
```

- 前端永远不持有数据库 URL、用户名或密码，只能通过后端 API 访问数据。
- Spring Boot 日常运行使用应用账号，不长期使用 root，也不使用迁移账号。
- 本机也采用“迁移账号 / 运行账号”分离，以提前验证生产最小权限模型。
- 正式部署必须使用环境独立的迁移账号和运行账号，二者均不得为 root。

## 3. 数据库范围与破坏性操作边界

| 数据库 | 用途 | 生命周期 | 允许的破坏性操作 |
|---|---|---|---|
| `shenzhou_hr_dev` | 本地开发、后端真实持久化、前后端 `/api` 联调 | 默认长期保留 | 默认禁止删除、清空或重建；确需执行时，必须再次核对精确库名、说明影响、生成可恢复备份并明确恢复步骤 |
| `shenzhou_hr_test` | 自动化测试、Flyway 迁移、唯一/外键/幂等/索引验证 | 可重复创建和重建 | 允许删除、清空、重建和重复迁移，但每次必须以精确库名保护并在报告中记录结束状态 |

硬边界：

1. 任何数据库命令执行前先查询当前连接的 host、port、server version 和 `DATABASE()`；目标不是上述两个库时立即停止。
2. 任何删除或重建命令不得使用未展开变量、通配符或模糊库名。
3. `shenzhou_hr_dev` 的破坏性操作必须先保留恢复文件及其校验值；恢复演练优先落到 `shenzhou_hr_test`，不得用生产或外部系统数据验证。
4. 禁止连接或修改生产、致远 OA、得力生产及任何未明确授权的数据库。

## 4. root 使用边界

本机 root 只允许用于：

- 创建 `shenzhou_hr_dev`、`shenzhou_hr_test`；
- 创建本机迁移、开发运行和测试运行账号；
- 对上述两个库授予或回收最小权限；
- 在授权实施波次执行或处置 Flyway 迁移；
- 删除并重建 `shenzhou_hr_test`；
- 经再次核对与备份后处置 `shenzhou_hr_dev`；
- 排查上述两个库的本机连接、锁、字符集、时区、索引和迁移问题。

root 不得用于：

- Spring Boot 日常运行、自动化业务测试或前端联调；
- 访问任何未授权库；
- 将凭据写入仓库、源码、配置模板、迁移、普通 SQL、测试夹具、README 或交付文档；
- 在命令行参数、shell trace、日志、测试报告或最终报告中回显密码。

执行纪律：

1. 凭据只在执行期间临时注入，执行前关闭 shell trace，执行后从进程环境和临时文件中清除。
2. 输出只记录变量名和 available/missing 状态，不记录值，也不记录可逆编码或掩码形式。
3. 建库和账号创建实际完成后，交付报告必须提醒用户更换已经出现在历史会话中的本机 root 密码；在实际完成前不得把该提醒写成“已经轮换”。

## 5. 账号与最小权限规划

账号名是实施合同中的建议名称；账号密码在实施时生成并仅保存到仓库外。

| 账号 | host 限制 | 授权对象 | 最小权限 |
|---|---|---|---|
| `shenzhou_hr_local_migrator` | `localhost` | `shenzhou_hr_dev.*`、`shenzhou_hr_test.*` | Flyway 所需 DDL/DML、索引和引用权限；无 `GRANT OPTION`，无全局管理权限 |
| `shenzhou_hr_dev_app` | `localhost` | `shenzhou_hr_dev.*` | `SELECT`、`INSERT`、`UPDATE`、`DELETE`；无建库、建表、授权和删除库权限 |
| `shenzhou_hr_test_app` | `localhost` | `shenzhou_hr_test.*` | 测试业务读写所需 `SELECT`、`INSERT`、`UPDATE`、`DELETE`；测试库重建仍由受控管理步骤完成 |

生产环境必须另建且严格分离：

- Flyway 迁移账号：仅迁移窗口启用，拥有目标 schema 的受控 DDL/DML 权限；
- 应用运行账号：仅拥有业务运行所需 DML 权限；
- 两者均不得拥有 `FILE`、`PROCESS`、`SUPER`、用户管理、授权传播或其他 schema 权限。

授权后必须用 `SHOW GRANTS` 验证实际权限；权限矩阵和负向测试结果进入波次报告，但不包含认证信息。

## 6. 凭据管理与连接参数

仓库当前使用的运行变量名继续保留：

- `SHENZHOUHR_DB_URL`
- `SHENZHOUHR_DB_USERNAME`
- `SHENZHOUHR_DB_PASSWORD`

后续为迁移账号增加独立的仓库外运行变量：

- `SHENZHOUHR_FLYWAY_URL`
- `SHENZHOUHR_FLYWAY_USERNAME`
- `SHENZHOUHR_FLYWAY_PASSWORD`

管理要求：

1. 凭据保存到操作系统凭据存储，或仓库外的本机环境文件；环境文件权限必须为仅当前用户可读写，例如 `0600`。
2. 仓库内只保留不含密码的变量名、配置模板和使用说明。
3. 禁止把任何数据库变量暴露为 Vite `VITE_*`、浏览器构建参数或前端运行配置。
4. 日志、异常、Actuator、测试输出和进程列表不得出现 URL 中嵌入的密码。
5. 执行报告只写连接目标、账号角色、迁移版本和结果，不写凭据值。

连接合同：

| 参数 | 开发 | 测试 |
|---|---|---|
| host / port | `localhost:3306` | `localhost:3306` |
| schema | `shenzhou_hr_dev` | `shenzhou_hr_test` |
| 字符集 | `utf8mb4` | `utf8mb4` |
| 排序规则 | `utf8mb4_0900_ai_ci` | `utf8mb4_0900_ai_ci` |
| 存储引擎 | InnoDB | InnoDB |
| 数据库会话时区 | UTC | UTC |
| 业务时区 | `Asia/Shanghai` | `Asia/Shanghai` |
| TLS | loopback 开发按本机能力显式配置 | loopback 测试按本机能力显式配置；生产必须加密 |

本机实际版本、驱动兼容性、`utf8mb4_0900_ai_ci` 支持和时区表状态当前均为 `NOT_RUN`。若本机版本不支持目标合同，不得静默换用不同排序规则并宣称兼容；应记录差异，在迁移中避免依赖不兼容特性，并在 MySQL 8.4 LTS 再验证。当前 `org.mariadb.jdbc.Driver` 是否继续用于 MySQL 8.4 也必须在后续依赖评审和真实连接测试中明确，不由本轮文档修订替换。

## 7. 建库方案

后续实施阶段按以下目标创建数据库；本段是待执行方案，不是已执行 SQL 记录：

```sql
CREATE DATABASE IF NOT EXISTS shenzhou_hr_dev
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS shenzhou_hr_test
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
```

建库后机械核验：

1. `INFORMATION_SCHEMA.SCHEMATA` 中只核验两个精确库名；
2. `DEFAULT_CHARACTER_SET_NAME=utf8mb4`；
3. `DEFAULT_COLLATION_NAME=utf8mb4_0900_ai_ci`；
4. 连接会话处于严格 SQL 模式，数据库 session time zone 为 UTC；
5. Java `Instant` 按 UTC 持久化，`LocalDate`、班次和业务日按 `Asia/Shanghai` 解释；
6. 运行账号不能创建、修改或删除表，迁移账号不能访问其他 schema。

不得通过 JPA、Hibernate、MyBatis 启动逻辑或测试 schema 自动建表代替 Flyway。

## 8. Flyway 与表结构合同

### 8.1 不可变基线

已实际存在：

- `V1__identity_organization_authorization_audit.sql`
- `V2__baseline_authorization_catalog.sql`

两份迁移禁止修改、重命名、覆盖或删除。发布后的任何迁移也适用同一规则；缺陷使用新的前向修复迁移处理，不回写历史 checksum。

Flyway 运行规则：

- 正式迁移位置只使用 `classpath:db/migration`；
- 开发合成种子继续与正式迁移隔离，不进入生产迁移位置；
- `baselineOnMigrate=false`，新库必须从 V1 开始；
- `flyway_schema_history` 必须位于各自目标库；
- 每波先 `validate`，再迁移；第二次 migrate 必须为 no-op，不能重复建表或重复种子数据；
- `shenzhou_hr_test` 通过受控删除/重建数据库获得全新状态，不把 Flyway `clean` 开放给开发或生产 profile；
- V1.9 只增加 V3 及以上迁移，不使用 ORM 或 Mapper 启动建表。

### 8.2 所有新表的横切字段与约束

| 主题 | 合同 |
|---|---|
| 主键 | 延续 `VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin` 精确标识；API 一律按 string 传输 |
| 外部标识 | 致远 19 位及以上 ID、设备原始 ID、第三方业务键使用 `VARCHAR`/精确类型和二进制或大小写明确的排序规则，禁止映射为 JavaScript number |
| 唯一与幂等 | 按公司、来源、业务键、幂等键、文件哈希和稳定指纹建立命名唯一索引 |
| 完整性 | 可建立外键时使用外键；跨边界无法使用外键时必须有事务校验、不可变引用和完整性巡检 |
| 状态 | 使用受控枚举/字典和服务端状态机；不能依赖任意文本 |
| 生效期 | 统一 `effective_from` / `effective_to` 或业务日期半开区间，并检查结束晚于开始 |
| 并发 | 可变聚合具有 `row_version` 或等价乐观锁字段；陈旧写入失败 |
| 审计 | 按对象性质保存 `created_by`、`updated_by`、`created_at`、`updated_at`、`request_id`；追加型事实不伪造 `updated_at` |
| 历史/冻结 | 发布、计算、月结、流水和原始事实保存版本/快照/冲正引用，不原位覆盖历史 |
| 查询 | 为 scope、状态、生效期、时间范围、排序和分页建立复合索引，并用真实查询计划验证 |

## 9. 各波次表与迁移映射

实际迁移注册表是版本号的唯一权威；每波授权时必须与
`backend/src/main/resources/db/migration` 已发布的最高版本核对，只能顺延，
不能占用或改写已发布版本。下表已按当前 V1～V11 注册表重排；更早确认材料
中的逻辑版本号只作历史规划，不覆盖实际注册表。

| 波次 | 逻辑迁移 | 主要新增/演进表 | 必须验证的约束与索引 |
|---|---|---|---|
| WAVE-1 | V3 identity/session；V4 policy foundation | `local_account`、`password_credential`、`login_failure_window`、`password_reset_grant`、`user_session`、`session_revocation`；复用/演进 `auth_principal`、`auth_role`、`auth_capability`、`auth_data_scope`、`auth_principal_role_assignment`、`audit_event`；新增 `policy_template`、`policy_version`、`policy_scope_binding`、`policy_publication_record`、`policy_rollback_record` | 用户名唯一、凭据只存哈希、reset/session token 只存摘要、失败窗口/锁定索引、session 到期/撤销索引、策略版本唯一、作用范围+生效期冲突、发布/停用/回滚不可变审计 |
| WAVE-2 | V5 people/import | `people_import_batch`、`people_import_file`、`people_import_row`、`people_import_issue`、`people_import_diff`、`people_import_publication`、`position`、`employment_period`、`prior_service_record`；复用并版本化 `organization_*`、`employee`、`employment_assignment` | 文件哈希和发布幂等唯一、原始行可追溯、员工工号/外部 ID 精确保真、任职周期不重叠、二次入职不覆盖、组织/岗位/员工有效期和审计索引 |
| WAVE-3 | V7 attendance setup | `attendance_group`、`attendance_group_assignment`、`location`、`work_calendar`、`work_calendar_day`、`shift_template`、`shift_version`、`attendance_policy_binding`；考勤专用策略承载晚餐扣除、宽限和单边缺卡规则 | 人员组适用期不冲突、班次版本无空档/重叠、日历日期唯一、地点状态受控、范围+生效期解析确定、12 月 31 日和跨日班次索引 |
| WAVE-4 | V8 source/evidence；V9 punch Excel | `attendance_source`、`attendance_sync_job`、`attendance_sync_watermark`、`source_device`、`device_person_binding`、`raw_attendance_fact`、`normalized_attendance_record`、`employee_match_decision`、`effective_attendance_event`、`evidence_link`、`oa_attendance_document`、`source_reversal_record`；`punch_mapping_profile`、`punch_import_batch`、`punch_import_file`、`punch_import_row`、`punch_import_issue`、`duplicate_review_group` | 来源业务键/水位唯一、文件哈希、原始 ID/稳定指纹和跨来源去重索引、设备绑定有效期、员工任职时点匹配、raw append-only、撤销/冲正引用、近似重复裁决前无 active event |
| WAVE-5/7 reporting | V10 formal attendance reporting | 月度不可变报表投影、日/异常/OA/时间账户报表事实和导出存储；不把尚未落库的日计算/月结编排写成已完成迁移 | 投影与事实只追加、公司与月份唯一链、导出授权和内容摘要；自动月结仍按独立接线门禁 |
| 横向公司边界 | V11 company dimension | `company`、所有当前 `company_id` 外键列和 `COMPANY` scope；稳定 ID 与业务行保持一对一 | V1～V10 文件不变、V10→V11 行数/ID digest 不变、旧 scope 原子迁移、跨公司负例 |
| WAVE-6 | V12 leave/time account（计划） | `leave_policy`、`leave_policy_version`、`annual_leave_qualification`、`annual_leave_tier`、`annual_leave_grant`、`time_account`、`time_account_ledger_entry`、`time_account_opening_batch`、`time_account_opening_row`、`time_account_expiry_record` | 假别版本/生效期唯一、资格与档位分离、周年 grant 幂等、有效期半开区间、余额只由流水重算、期初批次幂等、到期/冲正引用、冻结版本审计 |
| WAVE-7 | V13 self-service/reporting（计划） | `employee_feedback`、`employee_feedback_progress`、剩余自助/看板/报表对象；员工自助读取版本化领域事实，不复制新的权威余额/考勤表 | 本人/scope 授权索引、反馈状态和进度顺序、源版本/数据截至时间、聚合小样本和敏感字段隔离 |
| WAVE-8 PAYROLL | 未分配迁移版本；仅单独授权后 | 独立 `PAYROLL` capability/role 记录、`payroll_snapshot_ref`、`payroll_reservation_audit` | 默认拒绝、只引用已关闭考勤快照、普通角色零继承、前端发现性为 0；不建工资条/付款文件链路，不生成银行付款文件 |

## 10. 合成数据与四类数据隔离

| 数据类别 | 位置/生命周期 | 可否作为真实 MySQL 联调证据 |
|---|---|---|
| 正式 Flyway 迁移 | `db/migration`；生产可执行；不含个人数据和开发种子 | 迁移本身可以，仍需真实运行证据 |
| 开发合成种子 | 独立 `db/dev` 或仓库外受控导入；只进入 `shenzhou_hr_dev` | 可证明本机 API 数据流，但必须明确是合成数据 |
| 自动化测试数据 | 测试代码/夹具按用例创建并清理；只进入 `shenzhou_hr_test` | 可以，须报告用例、记录数和结束状态 |
| 前端 demo/mock | 前端显式 demo 模式，完全不写真实库 | 不可以作为数据库、API 或权限联调证据 |

禁止使用真实员工、打卡、位置、身份证、工资、个税或外部系统生产数据。合成数据使用明确测试标识、非真实联系方式和不可与真实员工混淆的编号。

H2 可以保留用于快速单元测试，但不能关闭、替代或降级本机 MySQL 验收门。

## 11. 开发与测试数据库生命周期

### 11.1 `shenzhou_hr_dev`

1. 创建库并验证字符集、排序规则、时区和权限；
2. 用迁移账号从 V1 迁移到当前授权波次；
3. 用独立开发种子装载合成数据；
4. Spring Boot 使用 `shenzhou_hr_dev_app` 运行；
5. 前端通过 `/api` 代理联调；
6. 默认保留库和合成数据；
7. 破坏性操作前备份、记录校验值和恢复命令，并再次核对库名和影响。

### 11.2 `shenzhou_hr_test`

1. 精确删除并重建 `shenzhou_hr_test`；
2. 从空库执行 V1→当前版本；
3. 装载当前测试所需的最小合成数据；
4. 执行迁移、约束、Mapper、API、权限和前端联调测试；
5. 再构造 V1/V2 快照并验证升级；
6. 第二次 migrate 证明无重复 DDL/数据；
7. 测试结束报告 `PRESERVED`、`EMPTY` 或 `DROPPED` 之一；不得含凭据。

## 12. 真实本机 MySQL 验证矩阵

当前所有条目均为 `NOT_RUN`。只有实际命令和证据存在时才能改为 `PASS` 或 `FAIL`。

| ID | 每波最低验证 | 目标 | 完成证据 | 当前 |
|---|---|---|---|---|
| MYSQL-01 | 记录实际 server version、字符集、排序规则、SQL mode、时区和连接目标 | 两库 | 脱敏命令与查询结果摘要 | NOT_RUN |
| MYSQL-02 | 全新测试库从 V1 迁移到当前最高版本 | test | `flyway_schema_history`、表数、迁移输出 | NOT_RUN |
| MYSQL-03 | V1/V2 状态升级到当前最高版本 | test | 升级前后版本和数据保持断言 | NOT_RUN |
| MYSQL-04 | `flyway validate` 通过 | dev/test | 实际 validate 输出 | NOT_RUN |
| MYSQL-05 | 第二次 migrate 不重复建表或插入目录数据 | test | no-op 输出、表/关键行数前后对比 | NOT_RUN |
| MYSQL-06 | Spring Boot 使用非 root 应用账号正常启动 | dev/test | 启动日志摘要、health 状态、账号 grants 证明 | NOT_RUN |
| MYSQL-07 | 后端 API 真实写入并读回 MySQL | dev/test | 请求/响应、数据库断言和 correlation id | NOT_RUN |
| MYSQL-08 | 唯一、外键/等效完整性、幂等、去重和版本冲突生效 | test | 命名负向测试和预期数据库错误/409 | NOT_RUN |
| MYSQL-09 | 查询、排序、分页和 scope 索引生效 | test | `EXPLAIN`/查询计划和有界数据集结果 | NOT_RUN |
| MYSQL-10 | 权限负向路径不能越权读写 | test | 服务端 401/403/404/409 与数据库无变更断言 | NOT_RUN |
| MYSQL-11 | 前端普通开发模式经 `/api` 连接真实后端 | dev | 浏览器网络记录与 API 数据库关联证明 | NOT_RUN |
| MYSQL-12 | 页面展示真实 API/MySQL 数据及 loading、empty、error、403、conflict、pagination | dev/test | 浏览器自动化与截图/断言 | NOT_RUN |
| MYSQL-13 | demo 与真实模式互不污染 | dev/test | demo 请求为 0、真实模式无 demo 标识、库记录前后断言 | NOT_RUN |
| MYSQL-14 | 报告命令、迁移、表、索引、测试数和测试库结束状态 | test | 波次验证报告，不含凭据 | NOT_RUN |
| MYSQL-15 | 在 MySQL 8.4 LTS 重跑迁移、约束、锁、索引、API 和恢复门 | release env | 独立 8.4 版本报告 | NOT_RUN |

任何未执行项必须保持 `NOT_RUN`；因环境缺失无法确认的生产/外部项标记 `NOT_VERIFIED`。demo、H2、旧 `target/` 或历史报告不得替代上述证据。

## 13. 备份、恢复与前向修复边界

- `shenzhou_hr_dev` 默认保留。删除、清空或重建前必须生成一致性备份、校验备份可读、记录恢复目标和验证查询；优先在 `shenzhou_hr_test` 演练恢复。
- `shenzhou_hr_test` 可随测试重建；需要验证 V1/V2 升级时保留版本化合成快照，不保留真实数据。
- 备份文件必须位于仓库外、权限仅当前用户可读；不得提交 Git。
- 数据库备份不代替原始文件/附件存储的独立备份，二者恢复后必须通过引用完整性检查。
- 已发布 Flyway 迁移没有“编辑后重跑”或破坏性 down migration。修复一律增加更高版本前向迁移，并验证空库路径和升级路径。
- 生产恢复必须在隔离环境演练；只生成备份、未实际恢复不能宣称恢复门通过。

## 14. MySQL 8.4 LTS 上线验证

正式上线前必须在与生产一致的 MySQL 8.4 LTS 环境重新验证：

1. 精确 server version、默认认证插件、TLS、字符集、排序规则、strict SQL mode 和 UTC session；
2. V1→最新、V2→最新、`validate` 和二次 migrate no-op；
3. `CHECK`、generated column、外键、唯一索引、索引长度、保留字和 DDL 行为；
4. 事务隔离、并发更新、死锁重试、幂等提交和月结/重算锁行为；
5. 关键查询在代表性 36 个月数据量下的执行计划、分页稳定性和 P95；
6. 应用账号最小权限、迁移账号窗口化权限、root 不参与日常运行；
7. 备份、隔离恢复、前向修复和 `flyway_schema_history` 完整性；
8. Spring Boot、Flyway MySQL 模块与最终 JDBC 驱动的受支持兼容性。

本机开发版本验证和 MySQL 8.4 LTS 上线验证必须分别报告，不能合并成一个“已通过”结论。

## 15. 每波数据库完成合同

每个实施波次只有同时具备以下证据才可完成：

1. 已冻结并实现的 OpenAPI 契约；
2. 新的 V3+ Flyway 增量迁移，且 V1/V2 checksum 不变；
3. 表、字段、主键、唯一、完整性、状态、生效期、版本、审计、分页/排序和幂等索引；
4. MyBatis Mapper、后端服务、服务端权限和审计；
5. 前端真实 route、真实 API 数据及完整异步/权限/冲突/分页状态；
6. QA 先行自动化测试；
7. 本机 `shenzhou_hr_test` 迁移/约束/权限验证；
8. 本机 `shenzhou_hr_dev` 的 Spring Boot、API 和前端真实联调；
9. 实际命令、迁移版本、表、索引、测试数量、结果和测试库结束状态报告；
10. 未执行项明确为 `NOT_RUN` 或 `NOT_VERIFIED`。

本轮没有执行上述实施步骤。流程必须停在 `docs_confirm / AWAITING_HUMAN_CONFIRMATION`，等待人工确认，不得自动进入 spec、frontend、backend、quality 或 delivery。
