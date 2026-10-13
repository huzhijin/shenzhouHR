# 神州 HR 考勤与 OA 数据取数及计算逻辑说明

> 文档用途：研发、实施、运维、HR 验收和审计共同使用  
> 代码基线：截至 2026-08-16 的当前工作树  
> 补充资料：包含销假单截图、枚举，以及 2026-08-14 确认的系统小时字段和请假—多张销假返还业务口径  
> 结论优先级：当前可执行代码 > 数据库迁移与测试 > 实库只读取证 > 历史决策/项目记忆 > 旧设计文档  
> 安全说明：本文不记录任何真实地址、账号、口令、App Key、Secret 或员工个人样本。

## 1. 执行摘要

### 1.1 当前系统有两套不同的考勤口径

当前代码中同时存在：

1. **完整确定性计算器**：能够处理班次分段、打卡窗口、请假、调休、外出、出差、补卡、免打卡、加班授权、餐时扣减、迟到宽限、月度迟到豁免、缺卡待补、证据冲突等。
2. **生产报表发布编排器 `PUNCH_SPAN_V1`**：当前实际进入正式报表投影的算法，只按上海自然日将最早打卡到最晚打卡的跨度记为工时，奇数条打卡记 1 次缺卡，其余大部分指标写 0。

完整计算器目前只有领域代码和单元测试调用，**没有接入生产月报发布链路**。因此本说明将两套口径分开记录，不能用“代码中已经实现”推断“客户报表已经执行”。

### 1.2 当前 OA 不是生产同步闭环

当前 OA 代码已具备：

- 独立只读 MySQL 连接池；
- `org_member.id → org_member.code` 定点查询；
- 七类 OA 表单、11 张物理表、104 个候选业务字段（102 个截图字段加 2 个用户声明的系统小时字段）的编译期静态白名单；
- 纯行转换器及严格失败关闭逻辑；
- 请假流水号与销假原单流水号的纯领域有效区间解析器；
- 来源、同步、原始事实、匹配、OA 文档、撤销、证据、游标等数据库结构；
- 已经入库 OA 文档的只读列表接口和页面。

当前 OA 代码不具备：

- 七类表单（包含销假 `LEAVE_REVOCATION`/`formmain_0370`）已签署、可启用的生产查询合同；仓库虽有默认关闭的 `OaMysqlAttendanceDocumentAdapter` 查询草案，但它尚未投影请假 `field0103`、销假 `field0107` 及原请假关联事实，也未完成当前版本集合归并，不能作为本合同已闭环的证据；
- 主从表、流程状态和枚举联表的已签署运行合同；
- 增量游标、分页同步、页事务、重试和回放应用服务；
- `org_member.code → 本系统员工/任职` 的生产接线；
- 请假—销假的审批过滤、当前版本归并、生产联表和多返还事实装配调用；
- OA 证据进入正式考勤计算和月度投影的编排。

仅设置 `OA_MYSQL_ENABLED=true` 只会创建只读连接池和成员查询适配器，不会自动产生 OA 单据，也不会改变考勤结果。

销假单已按正式类型 `LEAVE_REVOCATION` 进入 `OaStaticFormMappingCatalog` 和纯行转换器；请假 `formmain_0170.field0097/field0103`、销假 `formmain_0370.field0107` 也已加入表级白名单。纯领域 `OaLeaveEffectiveIntervalResolver` 允许一张请假对应多张当前销假，固定了严格流水号比较、主体一致、未知审批失败关闭、返还区间不重叠、累计返还不超原已扣减小时以及剩余请假片段规则。V40 也已将 `formmain_0370` 扩入本地元数据/运行合同门禁。静态目录和解析器自身不查 OA；现有默认关闭的查询适配器草案也未传递系统小时/原单关联，生产同步 caller、审批过滤、版本归并及原请假单联表/返还链仍未接线。

### 1.3 当前在线打卡链路比 OA 完整，但仍有断点

得力 E+ 在线打卡已实现来源注册、人员目录拉取、打卡分页、工号/确认绑定匹配、严格幂等、精确去重、隔离、有效事件和重算意图。仍未闭环的部分包括：

- 近似重复人工复核；
- 面向用户的证据追踪接口；
- 重算意图消费者；
- 完整确定性计算结果落库；
- 打卡跨日业务日期在正式月报中的应用。

## 2. 事实来源和状态术语

### 2.1 事实来源分级

| 级别 | 来源 | 使用方式 |
|---|---|---|
| A | 当前 Java/TypeScript/Mapper/Controller | 认定现行可执行行为 |
| B | 当前 Flyway 迁移和初始化 SQL | 认定表结构、种子权限和约束；如与 Java 不一致，记录为漂移 |
| C | 当前自动化测试 | 认定已经被测试固定的边界 |
| D | 2026-08-10 OA 实库只读取证 | 只证明物理列、数量和样本分布，不自动证明业务含义 |
| E | 项目记忆、OpenSpec、历史决策和未合入分支 | 作为待确认目标，不作为当前生产事实 |

### 2.2 本文状态标识

| 状态 | 定义 |
|---|---|
| 已生产接线 | 有正式 Controller/Service/Repository，且真实运行路径可达 |
| 有条件接线 | 代码可达，但默认关闭或依赖运维配置、权限、数据准备 |
| 仅领域实现 | 领域算法或解析器存在，但生产服务没有调用 |
| 仅结构/契约 | 只有表结构、类型、接口或静态映射 |
| 未验证 | 实库含义、状态、枚举、边界或时区未完成签署 |
| 失败关闭 | 不满足唯一性、合同或完整性时不猜测、不计入有效结果 |

## 3. 总体数据链路

### 3.1 目标链路

```text
外部原始证据
  ├─ 得力 E+ 打卡
  ├─ 打卡 Excel
  └─ OA 表单
       ↓
来源身份、版本和原始摘要
       ↓
规范化记录
       ↓
员工及发生时点任职匹配
       ↓
有效事件 / OA 文档 / 撤销事实
       ↓
证据生命周期与关联
       ↓
受影响员工和业务日期重算意图
       ↓
考勤配置解析
       ↓
确定性日计算
       ↓
月度不可变报表投影
       ↓
工作台、九类报表和导出
```

### 3.2 当前真正贯通的部分

| 链路 | 当前状态 |
|---|---|
| 得力原始记录 → 规范化 → 匹配 → 有效打卡事件 | 已生产接线，默认需显式启用 |
| 有效打卡事件 → 重算意图 | 已生产接线 |
| 重算意图 → 完整规则重算 | 未接线 |
| 有效打卡事件 → `PUNCH_SPAN_V1` 月度投影 | 已生产接线，但为简化口径 |
| OA 实库表 → OA 文档 | 未接线 |
| 已有 OA 文档 → 页面列表 | 已生产接线，只读 |
| OA 文档 → 完整规则计算/月度投影 | 未接线 |
| 打卡 Excel 文件 → 解析器 | 仅领域实现和测试 |
| 打卡 Excel → 预检/发布/有效事件 | 未接线 |

## 4. 共同身份、时间和版本原则

### 4.1 工号是主匹配键，姓名和部门不是

在线打卡和 OA 的目标身份口径都围绕员工工号：

- 精确字符串相等；
- 不自动去首尾空格；
- 不忽略大小写；
- 不把数字字符串转成数值；
- 不自动补零或去掉前导零；
- 不使用姓名或部门做模糊唯一匹配；
- 必须在事件发生时点存在唯一有效员工版本和任职。

这样做会让错误数据进入隔离，而不是错误归属到相似姓名员工。

### 4.2 有效期采用半开区间

多数员工、任职、组织、班次、日历、考勤组和规则有效期按：

```text
[effectiveFrom, effectiveTo)
```

处理，即开始时刻包含，结束时刻不包含。`effectiveTo` 为空通常表示尚未结束。

### 4.3 业务日期和知识时点是两个维度

- **业务日期/事件时刻**回答“当时适用哪名员工、哪个部门、哪套规则”。
- **知识时点**回答“在本次发布时，系统已经知道哪些版本和撤销”。

正式投影应固定数据截止时间和版本摘要，以便以后重现。不能只按当前表的最新一行覆盖历史。

### 4.4 唯一性失败时不任选一条

以下情况均应失败关闭：

- 同工号在同公司同一发生时点匹配到多名员工；
- 同一员工同日存在两个有效考勤组分配；
- 同一日期存在相互冲突的发布日历或班次；
- 同一来源身份键出现不同原始摘要；
- 同优先级证据在同一时间片给出互斥结论；
- OA 主体 ID 查询到零条、多条或返回 ID 不一致。

## 5. 得力 E+ 在线打卡取数

### 5.1 数据源注册

接口：`POST /api/v1/attendance-sources`。需要 `ATTENDANCE_SOURCE:CONFIGURE`、`Idempotency-Key` 和变更原因。

| 请求字段 | 业务含义 | 校验/转换 | 目标 |
|---|---|---|---|
| `companyId` | 数据归属公司 | 必须为启用公司且调用人有范围 | `attendance_source.company_id` |
| `sourceCode` | 来源编码 | `[A-Z0-9][A-Z0-9_-]{1,63}` | 稳定来源标识 |
| `displayName` | 页面名称 | 非空 | 来源显示名称 |
| `sourceTimeZone` | 上游时间所属时区 | 合法 IANA ZoneId | 来源配置时区 |
| `pageSize` | 打卡每页条数 | 1—500 | 同步配置 |
| `rateLimitPerMinute` | 每分钟请求上限 | 60—10000 | 同步节流 |
| `backoffSeconds` | 重试退避秒数 | 0—5 | 重试策略 |
| `secretReferenceName` | 密钥引用名 | `[A-Z][A-Z0-9_]{2,127}` | 只保存引用，不保存明文 |
| `reason` | 配置原因 | 必填 | 审计/幂等记录 |

固定值：

- `sourceType = DELI_CLOUD`
- 接口类型为得力 E+ 考勤查询；
- 默认匹配策略为确认绑定；
- 禁止保留敏感原始正文，策略为丢弃。

前端“考勤机数据”当前是只读概览，没有注册、编辑或首次运行按钮。首次注册和启用属于实施/API 操作。HR 默认可运行和重试，但不能配置；系统管理员可配置。

### 5.2 客户端通用安全约束

配置前缀：`shenzhouhr.integrations.deli-eplus`。

- 默认 `enabled=false`；
- 只接受官方 HTTPS 基地址；
- 默认连接超时 5 秒、读取超时 15 秒；
- 响应体最大 4 MiB；
- 默认时区 `Asia/Shanghai`；
- App Key/Secret 通过环境或安全密钥引用提供；
- 日志和配置对象必须脱敏，本文也不记录真实值。

### 5.3 员工目录请求

接口：`POST /v2.0/employee/query`。

请求体使用 `offset` 和 `limit`，单页最大 100；请求头包含签名所需的 App Key、时间戳和签名，不使用考勤命令头。

响应兼容三种数据包装：`data.rows`、`data.data` 或直接数组。目录必须完整拉取后，才开始打卡匹配。

| 上游字段 | 必填 | 系统用途 | 异常处理 |
|---|---|---|---|
| `id` | 是 | 得力用户 ID，建立 `user_id → employee_num` 映射 | 缺失则跳过该目录行 |
| `employee_num` | 是 | 员工工号 | 缺失则跳过该目录行 |

无效目录行会被跳过，但上游返回的原始行数仍用于判断分页是否继续。同一 `id` 重复时，当前实现由后读取值覆盖先读取值，未产生专门冲突记录，这是需监控的数据质量风险。

### 5.4 打卡请求和响应分页

接口：`POST /v2.0/cloudappapi`。

请求头：

- `Api-Module: CHECKIN`
- `Api-Cmd: checkin_query`
- App Key、时间戳和签名头

请求体：

- `next_id`：上一页游标；
- `page_size`：最大 500。

响应要求：

- 顶层 `code = 0`；
- `data.data` 是数组且不超过请求页大小；
- `data.next_id` 必须能规范化为无符号整数游标；
- 非法结构、超限页、非法游标均不提交水位。

### 5.5 打卡字段逐项血缘

| 得力字段/派生值 | 必填 | 系统标准字段 | 处理逻辑 |
|---|---|---|---|
| `id` | 是 | `sourceRecordId` | 来源记录唯一编号 |
| `user_id` | 是 | 人员外部引用备选 | 无 `ext_id` 时使用 |
| `ext_id` | 否 | `externalPersonRef` | 有值时优先于 `user_id` |
| 是否存在 `ext_id` | 派生 | `externalPersonRefKind` | 有值为 `DELI_EXT_ID`，否则 `DELI_USER_ID` |
| 目录中 `user_id` 对应 `employee_num` | 首选 | `employeeNumber` | 人员目录结果优先 |
| `check_data.employee_num` | 回退 | `employeeNumber` | 目录找不到时才使用 |
| `terminal_id` | 是 | 内存字段 `deviceRef` | 仅参与输入完整性校验、`sourceVersion` 和原始摘要；当前员工匹配 SQL 不使用设备编号，原始/规范化事实表也没有设备编号独立列 |
| `check_type` | 是 | 内存字段 `verificationMethod` | 原值参与来源版本和原始摘要，不转换成进/出方向；当前事实表没有验证方式独立列 |
| `check_time` | 是 | `punchInstant` | 按 Unix epoch 秒转换为 UTC `Instant` |
| `check_time` 原文本 | 派生 | `originalTimeText` | 保存 epoch 秒文本 |
| 来源配置时区 | 配置 | `sourceTimeZone` | 不从单条载荷动态取时区 |
| 固定值 | 派生 | `direction` | 一律 `AUTO` |
| 固定空值 | 派生 | 地点摘要/坐标 | 地点摘要空、坐标状态 `UNKNOWN` |
| `check_data` | 否 | 原始摘要输入 | 直接对响应中的外层 `JsonNode` 做 Jackson 序列化后计算 SHA-256，不保存正文；若它是 JSON 编码字符串，内部属性顺序和文本格式仍会影响摘要 |
| `check_data` 是否存在 | 派生 | `forbiddenPayloadDropped` | 响应中的 `check_data` 节点引用只要不是 Java `null` 即为 `true`，空字符串也会命中；该值仅参与 `rawDigest`，没有独立数据库列，不能解释成“正文一定非空” |

`sourceVersion` 由系统计算，不直接相信上游版本：

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

`check_data.employee_num` 只有在 `check_data` 是可解析的 JSON 编码字符串时才会被读取；解析失败不使整条打卡失败，只是失去该工号回退值。`terminal_id` 与 `check_type` 都参与 `sourceVersion`/摘要计算，但摘要是单向哈希，不能从摘要反推出原值；只有已经掌握候选原值时，才能按同一算法重算并核验。二者也不能作为数据库中的独立业务字段查询。

### 5.6 员工匹配顺序

匹配被限定在来源绑定公司和打卡发生时点。

#### 第一级：精确工号

查询发生时点有效的员工版本和任职，使用工号二进制精确等值，最多取 2 条：

- 1 条：`MATCHED`；
- 多条：`AMBIGUOUS`，立即隔离，不再回退外部绑定；
- 0 条：进入第二级。

#### 第二级：已确认外部绑定

按以下全部条件查询：

- 同一来源；
- 同一公司；
- 同一外部引用种类和值；
- 状态为 `CONFIRMED`；
- 存在确认引用；
- 绑定有效期覆盖打卡时点；
- 员工和任职在该时点有效。

结果仍按 1 条、0 条、多条分别判定为匹配、未匹配、歧义。

姓名、部门、设备名称、相似工号不会进入自动匹配。

### 5.7 来源幂等和身份冲突

来源身份键：

```text
(sourceId, sourceRecordId, sourceVersion)
```

- 相同身份键且原始摘要相同：安全重放，不重复入账；
- 相同身份键但原始摘要不同：`DELI_SOURCE_IDENTITY_COLLISION`，终止处理；
- 不允许用新载荷静默覆盖旧原始事实。

### 5.8 精确事件去重

有效事件重复键：

```text
公司 + 员工 + 打卡时刻 + 方向
```

因为得力方向固定为 `AUTO`：

- 已存在恰好 1 个有效事件：保留本次原始证据，但证据关系记为 `EXACT_DUPLICATE`，不新增有效事件，不发重算；
- 不存在：新增 `PUNCH_POINT`，生命周期记 `ACTIVATED`，证据关系记 `PRIMARY`；
- 已存在多条：视为内部状态异常，页事务失败。

近似重复和跨来源重复的领域算法、查询结构已存在，但得力生产页事务没有调用，人工复核接口仍返回 501。当前只有“完全相同时刻”去重真正接线。

### 5.9 记录级隔离与页级失败

员工匹配隔离会写原始事实、规范化记录和匹配决策，但不会生成有效打卡事件：

| 原因 | 结果 |
|---|---|
| 工号和绑定均无唯一匹配 | 写入 `EMPLOYEE_UNMATCHED` 隔离证据 |
| 工号或绑定匹配到多条 | 写入 `EMPLOYEE_AMBIGUOUS` 隔离证据 |
| 已匹配员工，但配置解析异常或非权威 | `ATTENDANCE_CONFIGURATION_UNAVAILABLE`；终止并回滚整页，不是记录级隔离 |
| 已匹配员工，但投影期间状态未知/不可解析 | 计为该记录 `QUARANTINED`，但异常发生在 raw 写入前，因此当前实现不会留下该条 raw/normalized/match 行 |
| 已匹配员工，期间为 `FROZEN/CLOSED` | `ATTENDANCE_PERIOD_PROTECTED`；终止并回滚整页 |

处理顺序很重要：代码先完成员工匹配；只有匹配成功时，才在写 raw 之前解析考勤配置和期间保护。若非空页全部都是“期间未知”记录，`acceptedCount=0` 会触发 `DELI_ALL_RECORDS_QUARANTINED`，整页仍失败且不推进水位；只有同页至少一条记录被接受时，才可能连同隔离计数提交。期间未知记录没有逐条证据落表是当前实现边界，不能把它描述成完整隔离台账。

页级失败会回滚整页且不推进游标，包括：

- 作业/来源状态和顺序不一致；
- 输入游标不是已提交水位；
- 页号不连续；
- 页面摘要冲突；
- 游标原地不动或形成循环；
- 配置解析非权威，或期间已 `FROZEN`/`CLOSED`；
- 非空页没有任何可接受记录；
- 数据库原子提交不完整。

完整且游标有效的页面允许部分记录隔离后提交，因此最终作业可为 `PARTIALLY_QUARANTINED`。

### 5.10 页事务和作业编排

每页使用独立新事务：

1. 锁定作业、来源和水位；
2. 校验作业为 `RUNNING`；
3. 校验页号和输入游标连续；
4. 逐条校验来源身份并执行员工匹配；
5. 对匹配成功记录先解析权威配置和基于已发布投影的期间保护；
6. 在权威检查通过后写原始事实、规范化记录和匹配决策；员工未匹配/歧义记录直接写为隔离证据；
7. 新增或关联有效事件；
8. 为候选业务日期写重算意图；
9. 写页面提交记录和摘要；
10. 原子推进来源水位并累加成功/隔离计数。

同步编排器还会：

- 保证同一来源只有一个活动作业；
- 恢复超时作业；
- 先完整拉取人员目录；
- 单页最多尝试 3 次可重试错误；
- 按配置限速；
- 单次最多 10,000 页；
- 检查重复游标和游标循环；
- 最终标记 `SUCCEEDED`、`PARTIALLY_QUARANTINED` 或 `FAILED`。

自动同步由独立开关和 cron 控制，默认关闭；默认计划为每小时，但只有明确启用后才执行。

### 5.11 落库证据链

当前真实表名和关系：

```text
attendance_source
  → attendance_source_config_revision / attendance_sync_watermark
  → attendance_sync_job / attendance_sync_job_page
  → raw_attendance_fact
  → normalized_attendance_record
  → employee_match_decision
  → effective_attendance_event
  → effective_event_lifecycle_fact
  → evidence_link
  → attendance_recalculation_intent
```

关键内容：

- `raw_attendance_fact` 保存来源键、版本、原始时间文本、来源时区、UTC 时点和摘要，不保存 `check_data` 正文；
- `normalized_attendance_record` 使用 `DELI_CHECKIN_V1`，状态为 `VALID` 或 `QUARANTINED`；
- `employee_match_decision` 保存匹配方式、员工、任职、状态和决策摘要；
- `effective_attendance_event` 只有匹配成功且期间允许时才创建，类型为 `PUNCH_POINT`；
- `effective_event_lifecycle_fact` 首次写 `ACTIVATED`；
- `evidence_link` 区分 `PRIMARY` 和 `EXACT_DUPLICATE`；
- `attendance_recalculation_intent` 记录 `DELI_PUNCH_INGESTED` 及受影响日期。

证据追踪 SQL 已存在，但公开证据接口仍返回 501；重算意图也没有生产消费者。

## 6. 打卡 Excel 取数逻辑

### 6.1 当前状态

已实现：模板下载、安全校验、工作簿解析、字段识别、表结构、批次/行/错误只读查询。

未实现：正式上传 Controller、映射保存闭环、预检执行、预览、发布、部分发布、作废、冲正、重复复核和重算。解析器当前主要被测试调用。

### 6.2 文件安全限制

| 项目 | 限制 |
|---|---:|
| 格式 | 仅 `.xlsx` |
| 文件大小 | 最大 20 MiB |
| 数据行 | 最大 50,000 行 |
| ZIP 条目 | 最大 256 |
| 解压总量 | 最大 100 MiB |
| 压缩比 | 最大 100 |

此外：

- 必须有合法 ZIP 文件头；
- 拒绝路径穿越、外部关联、宏和 OLE 嵌入；
- 任意公式单元格直接拒绝；
- 只拒绝禁止导入的计算结果列；普通未知表头当前会被静默忽略；
- 全空行跳过；
- 单元格通过格式化器转成文本后 `trim()`；
- 每行增加原始 Excel 行号 `_rowNumber`。

### 6.3 工作表和字段

模板包含：导入说明、考勤打卡导入、设备人员映射（可选）、字段说明、枚举值、示例数据。

虽然名称写“设备人员映射（可选）”，当前解析器仍要求该工作表存在，只允许没有数据行。

#### 打卡表字段

| 字段 | 用途 | 是否参与身份/计算 |
|---|---|---|
| `employeeNumber` | 系统员工工号 | 身份匹配首选 |
| `devicePersonRef` | 设备侧人员编号 | 可用于外部绑定 |
| `employeeNameForComparison` | 人工对比姓名 | 不参与唯一匹配 |
| `departmentForComparison` | 人工对比部门 | 不参与唯一匹配 |
| `punchTime` | 打卡时间 | 原始事件时间 |
| `direction` | `AUTO/IN/OUT` | 打卡方向 |
| `verificationMethod` | `CARD/PIN/MOBILE/OTHER` | 验证方式 |
| `sourceRecordId` | 来源记录号 | 来源幂等 |
| `sourceVersion` | 来源版本 | 来源幂等 |
| `eventCode` | 事件编码 | 上下文 |
| `deviceRef` | 设备编号 | 来源上下文 |
| `locationCode` | 地点编码 | 配置/比对上下文 |
| `sourceTimeZone` | 来源时区 | 时间转换 |
| `note` | 备注 | 非计算上下文 |

模板“字段说明”页把 `punchTime`、`direction`、`sourceTimeZone` 标为必填；但当前 `parse()` 尚未强制检查这些值是否存在，也未执行枚举、日期、时区等值级校验。实际发布链路接通前必须补齐校验，并要求有足够身份字段形成唯一匹配。

#### 设备人员映射字段

| 字段 | 用途 |
|---|---|
| `companyCode` | 公司编码 |
| `locationCode` | 地点编码 |
| `deviceRef` | 设备编号 |
| `deviceName` | 设备名称，可空 |
| `devicePersonRef` | 设备人员编号 |
| `employeeNumber` | 系统工号 |
| `effectiveFrom` | 映射开始 |
| `effectiveTo` | 映射结束，可空 |
| `sourceTimeZone` | 来源时区 |

### 6.4 禁止导入计算结果

以下字段及忽略空格、下划线、连字符和大小写后的变体均被拒绝：

- `lateMinutes`
- `absence`
- `absenceMinutes`
- `recognizedOvertime`
- `exceptionResult`
- `dailyResult`
- `calculationResult`

原则是：Excel 只能提供原始事实，不能直接写入迟到、缺勤、加班或工资结论。

### 6.5 设计状态机

数据库和领域状态机描述：

```text
DRAFT → VALIDATING
VALIDATING → VALIDATION_FAILED / AWAITING_CONFIRMATION /
             BLOCKED_BY_FROZEN_PERIOD
VALIDATION_FAILED → VALIDATING
BLOCKED_BY_FROZEN_PERIOD → VALIDATING
AWAITING_CONFIRMATION → VALIDATING / PUBLISHING
PUBLISHING → PUBLISHED / PARTIALLY_PUBLISHED / PUBLISH_FAILED
PUBLISH_FAILED → VALIDATING / PUBLISHING
PUBLISHED / PARTIALLY_PUBLISHED → VOIDED
```

其中，校验失败和冻结期间阻断都不能直接发布，必须满足各自前置条件并重新进入 `VALIDATING`；只有 `AWAITING_CONFIRMATION` 可直接进入 `PUBLISHING`。`PUBLISH_FAILED` 直接重试发布还要求已确认回滚且请求保持完全相同和幂等。

关键幂等概念是“公司 + 来源 + 文件内容摘要 + 合同摘要”。这些表和状态不能证明当前生产已经能够上传和发布。

## 7. OA MySQL 连接与安全边界

### 7.1 环境配置

| 环境变量 | 默认值 | 作用 |
|---|---|---|
| `OA_MYSQL_ENABLED` | `false` | 是否创建 OA 独立只读连接池 |
| `OA_MYSQL_JDBC_URL` | 空 | 带库名的 MySQL JDBC URL |
| `OA_MYSQL_USERNAME` | 空 | OA 只读账号 |
| `OA_MYSQL_PASSWORD` | 空 | OA 只读口令 |
| `OA_MYSQL_MAX_POOL_SIZE` | `2` | 最大连接数，代码允许 1—4 |
| `OA_MYSQL_CONNECTION_TIMEOUT` | `PT5S` | 建连超时，允许 250ms—30s |
| `OA_MYSQL_QUERY_TIMEOUT` | `PT3S` | 查询超时，允许 1—30s |

真实值必须通过服务器环境或密钥管理系统注入，不能写在版本库、客户手册、截图或前端表单。

### 7.2 JDBC URL 和凭据校验

当前 `OaMysqlProperties` 要求：

- URL 以 `jdbc:mysql://` 开头；
- 必须包含 host 和数据库 path；
- 禁止在 URL 内嵌用户名和密码；
- 禁止 fragment 和控制字符；
- 禁止通过 URL 注入 `user`、`password`、`propertiesTransform`、`useConfigs`、`readOnlyPropagatesToServer`、`useLocalSessionState`、`allowMultiQueries`；
- 用户名和密码非空、最长 512、不得含控制字符；
- `toString()` 对 URL、用户名和密码脱敏。

### 7.3 只读连接保护

独立池设置：

- 池名 `shenzhouhr-oa-readonly`；
- `readOnly=true`；
- `autoCommit=true`；
- `readOnlyPropagatesToServer=true`；
- `useLocalSessionState=false`；
- `allowMultiQueries=false`；
- `minimumIdle=0`；
- 每次借出连接重新 `setReadOnly(true)` 并校验，失败则关闭连接。

仍必须在 OA 数据库端只授予所需表的 `SELECT` 权限。JDBC 只读标记不能代替数据库最小授权。

### 7.4 当前开启后的真实效果

条件配置只创建：

1. OA 只读连接池；
2. `org_member` 查询适配器。

不会创建单据查询适配器、同步应用服务、定时任务、来源注册、合同发布或连接测试页面。

## 8. 七类 OA 表单逐字段映射

静态目录 `OaStaticFormMappingCatalog` 的注释明确说明：七类表单的字段来自截图抄录，不是已经签署的实库合同；目录不包含已核验的审批列、主从外键和实库枚举 ID 映射。因此下面的表是“当前代码期待什么”，不是“生产已经读取什么”。

当前纯领域转换器返回 `candidate`、`effectiveCandidate` 和 `issues`：`candidate` 中包含 `formKind`、解析后的 `org_member.id/code` 以及时间区间/日期范围/时间点；`effectiveCandidate` 是布尔标记，仅在 `candidate != null` 且问题列表为空时为 `true`；`issues` 是问题列表。除主体、工号复核和时间字段外，`CONTEXT_ONLY`、`DURATION_CHECK_ONLY`、`ENUM_NOT_VERIFIED`、`LEAVE_TYPE_ENUM` 字段目前都只是静态白名单/业务目录归属声明：转换器不读取其业务值、不校验声明类型、不输出，也不参与匹配或计算。下表的“当前转换”严格按这一事实描述。

### 8.1 出差 `TRIP`

主表：`formmain_0265`；时间形态：区间。

| OA 字段 | 代码角色 | 预期业务含义 | 当前转换 |
|---|---|---|---|
| `field0137` | `SUBJECT_MEMBER_ID` | 出差人选人 ID | 解析为正整数，查 `org_member` |
| `field0148` | `TEMPORAL_START` | 开始时间 | 必须已是 `LocalDateTime` |
| `field0149` | `TEMPORAL_END` | 结束时间 | 必须晚于开始时间 |
| `field0140` | `CONTEXT_ONLY` | 事由 | 仅白名单；不读取、校验或输出 |
| `field0141` | `DURATION_CHECK_ONLY` | 累计天数 | 仅白名单；当前未执行时长校验，也不输出 |
| `field0142` | `CONTEXT_ONLY` | 地点 | 仅白名单；不读取、校验或输出 |
| `field0083` | `CONTEXT_ONLY` | 填表人 | 仅白名单；不读取、校验或输出 |
| `field0084` | `CONTEXT_ONLY` | 填表部门 | 仅白名单；不读取、校验或输出 |
| `field0085` | `CONTEXT_ONLY` | 填表日期 | 仅白名单；不读取、校验或输出 |
| `field0138` | `CONTEXT_ONLY` | 出差人部门 | 仅白名单；当前没有部门比对逻辑 |
| `field0139` | `CONTEXT_ONLY` | 出差人岗位 | 仅白名单；不读取、校验或输出 |
| `field0154` | `CONTEXT_ONLY` | 所属岗位 | 仅白名单；不读取、校验或输出 |
| `field0155` | `CONTEXT_ONLY` | 代理人 | 仅白名单；不读取、校验或输出 |
| `field0156` | `CONTEXT_ONLY` | 所属部门 | 仅白名单；不读取、校验或输出 |
| `field0151` | `CONTEXT_ONLY` | 交通字段 1 | 仅白名单；不读取、校验或输出 |
| `field0152` | `CONTEXT_ONLY` | 交通字段 2 | 仅白名单；不读取、校验或输出 |
| `field0153` | `CONTEXT_ONLY` | 交通字段 3 | 仅白名单；不读取、校验或输出 |

### 8.2 请假 `LEAVE`

主表：`formmain_0170`；时间形态：区间。

`field0103` 是 2026-08-14 用户确认的系统计算小时字段；目标 OA 的列存在性、数值精度和流程可写性仍为 `NOT_VERIFIED`。

| OA 字段 | 代码角色 | 预期业务含义 | 当前转换 |
|---|---|---|---|
| `field0097` | `CONTEXT_ONLY` | 请假单流水号 | 已进静态白名单；行转换器不输出，但纯领域区间解析器将它与销假 `formmain_0370.field0099` 做不 trim、不改大小写、不转数字的精确字符串比较 |
| `field0083` | 主体 ID | 请假人选人 ID | 查 `org_member` |
| `field0084` | 工号复核 | 表单工号 1 | 与 `org_member.code` 精确比较 |
| `field0096` | 工号复核 | 表单工号 2 | 与 `org_member.code` 精确比较 |
| `field0086` | 开始 | 请假开始 | `LocalDateTime` |
| `field0087` | 结束 | 请假结束 | 严格晚于开始 |
| `field0088` | `DURATION_CHECK_ONLY` | 天数/时长 | 仅白名单；当前未执行时长校验，也不输出 |
| `field0103` | `SYSTEM_CALCULATED_HOURS` | 系统计算小时 | 已进表级静态白名单；当前 HR 行转换器不读取、不计算、不输出，启用前须核验目标 OA 物理列 |
| `field0089` | `LEAVE_TYPE_ENUM` | 请假类别 | 声明复用请假类型业务目录；当前转换器仍不读取枚举值、不调用类别目录、不输出 |
| `field0090` | `CONTEXT_ONLY` | 备注 | 仅白名单；不读取、校验或输出 |
| `field0091` | `CONTEXT_ONLY` | 说明 | 仅白名单；不读取、校验或输出 |
| `field0092` | `CONTEXT_ONLY` | 岗位 | 仅白名单；不读取、校验或输出 |
| `field0093` | `CONTEXT_ONLY` | 级别 | 仅白名单；不读取、校验或输出 |
| `field0094` | `CONTEXT_ONLY` | 代理人 | 仅白名单；不读取、校验或输出 |
| `field0095` | `CONTEXT_ONLY` | 所属部门 | 仅白名单；不读取、校验或输出 |
| `field0074` | `CONTEXT_ONLY` | 填表人 | 仅白名单；不读取、校验或输出 |
| `field0075` | `CONTEXT_ONLY` | 填表部门 | 仅白名单；不读取、校验或输出 |
| `field0076` | `CONTEXT_ONLY` | 填表日期 | 仅白名单；不读取、校验或输出 |

### 8.3 加班 `OVERTIME`

主表：`formmain_0171`；明细表：`formson_0172`；时间形态：区间。人员和每次加班区间在明细表。

#### 主表

| OA 字段 | 代码角色 | 预期含义 | 当前转换 |
|---|---|---|---|
| `field0074` | `CONTEXT_ONLY` | 填表人 | 仅白名单；不读取、校验或输出 |
| `field0075` | `CONTEXT_ONLY` | 填表部门 | 仅白名单；不读取、校验或输出 |
| `field0076` | `CONTEXT_ONLY` | 填表日期 | 仅白名单；不读取、校验或输出 |
| `field0102` | `CONTEXT_ONLY` | 明细最早时间 | 仅白名单；不读取，也不替代明细起点 |
| `field0104` | `DURATION_CHECK_ONLY` | 系统差值 | 仅白名单；当前未执行时长校验，也不输出 |
| `field0105` | `CONTEXT_ONLY` | 系统差值文本 | 仅白名单；不读取、校验或输出 |

#### 明细表

| OA 字段 | 代码角色 | 预期含义 | 当前转换 |
|---|---|---|---|
| `field0092` | `CONTEXT_ONLY` | 序号 | 仅白名单；不读取、校验或输出 |
| `field0093` | 主体 ID | 加班人选人 ID | 查 `org_member` |
| `field0094` | 工号复核 | 表单工号 | 精确比较 |
| `field0095` | `CONTEXT_ONLY` | 部门 | 仅白名单；不读取、校验或输出 |
| `field0096` | `ENUM_NOT_VERIFIED` | 加班类别 | 仅白名单；不读取枚举值、不调用类别目录、不输出 |
| `field0100` | 开始 | 加班开始 | `LocalDateTime` |
| `field0099` | 结束 | 加班结束 | 必须晚于开始 |
| `field0101` | `DURATION_CHECK_ONLY` | 总时长 | 仅白名单；当前未执行时长校验，也不输出 |
| `field0103` | `CONTEXT_ONLY` | 原因 | 仅白名单；不读取、校验或输出 |

字段名按物理表隔离：`formmain_0170.field0103` 是请假系统计算小时，`formson_0172.field0103` 仍是加班原因文本；不得因列名相同而跨表复用类型或语义。

2026-08-10 只读取证确认：主表和明细表均以 `id` 为 bigint 主键，明细 `formmain_id` 可空但有索引；主体 ID 是短文本、工号是文本、类别是 bigint、起止是 datetime。该取证仍未把所有业务含义签署为生产合同。

### 8.4 外出 `OUTING`

主表：`formmain_0251`；明细表：`formson_0252`；时间形态：区间。

主表公共字段（均为 `CONTEXT_ONLY`，当前转换器只把它们列入白名单，不读取、校验或输出）：

- `field0083`：填表人；
- `field0084`：填表部门；
- `field0085`：填表日期。

明细表：

| OA 字段 | 代码角色 | 预期含义 | 当前转换 |
|---|---|---|---|
| `field0126` | `CONTEXT_ONLY` | 序号 | 仅白名单；不读取、校验或输出 |
| `field0127` | 主体 ID | 外出人选人 ID | 解析并查 `org_member` |
| `field0130` | `CONTEXT_ONLY` | 部门 | 仅白名单；不读取、校验或输出 |
| `field0131` | 工号复核 | 表单工号 | 与 `org_member.code` 精确比较 |
| `field0132` | 开始 | 外出开始时间 | 必须是 `LocalDateTime` |
| `field0135` | 结束 | 外出结束时间 | 必须晚于开始时间 |
| `field0133` | `CONTEXT_ONLY` | 事由 | 仅白名单；不读取、校验或输出 |

### 8.5 免打卡 `EXEMPT_PUNCH`

主表：`formmain_0201`；明细表：`formson_0202`；时间形态：日期范围。

主表公共字段仍是 `field0083/0084/0085`；均为 `CONTEXT_ONLY`，当前只进入白名单，不读取、校验或输出。

| OA 字段 | 代码角色 | 预期含义 | 当前转换 |
|---|---|---|---|
| `field0126` | `CONTEXT_ONLY` | 序号 | 仅白名单；不读取、校验或输出 |
| `field0127` | 主体 ID | 免打卡人员 | 查 `org_member` |
| `field0129` | `CONTEXT_ONLY` | 岗位 | 仅白名单；不读取、校验或输出 |
| `field0130` | `CONTEXT_ONLY` | 部门 | 仅白名单；不读取、校验或输出 |
| `field0131` | 工号复核 | 表单工号 | 精确比较 |
| `field0132` | 日期开始 | 开始日期 | 必须是 `LocalDate` |
| `field0134` | 日期结束 | 结束日期 | 不得早于开始 |
| `field0133` | `CONTEXT_ONLY` | 原因 | 仅白名单；不读取、校验或输出 |

当前转换器不判断结束日期是包含还是不包含，也不会擅自扩展到 23:59:59。此边界必须由客户签署后才能转换成考勤区间。

### 8.6 补卡 `PUNCH_CORRECTION`

主表：`formmain_0203`；明细表：`formson_0204`；时间形态：单点。

主表公共字段仍是 `field0083/0084/0085`；均为 `CONTEXT_ONLY`，当前只进入白名单，不读取、校验或输出。

| OA 字段 | 代码角色 | 预期含义 | 当前转换 |
|---|---|---|---|
| `field0126` | `CONTEXT_ONLY` | 序号 | 仅白名单；不读取、校验或输出 |
| `field0127` | 主体 ID | 补卡人员 | 查 `org_member` |
| `field0129` | `CONTEXT_ONLY` | 岗位 | 仅白名单；不读取、校验或输出 |
| `field0130` | `CONTEXT_ONLY` | 部门 | 仅白名单；不读取、校验或输出 |
| `field0131` | 工号复核 | 表单工号 | 精确比较 |
| `field0132` | 时间点 | 补签时间 | 必须是 `LocalDateTime` |
| `field0133` | `CONTEXT_ONLY` | 原因 | 仅白名单；不读取、校验或输出 |
| `field0134` | `ENUM_NOT_VERIFIED` | 补卡类型 | 仅白名单；不读取枚举值、不转换、不输出 |

### 8.7 销假 `LEAVE_REVOCATION`

静态目录主表：`formmain_0370`，现有 22 个候选业务字段：21 个来自“主表字段”截图，`field0107` 来自 2026-08-14 用户确认的系统返还小时合同。代码已将它们纳入封闭白名单和纯行转换；截图字段证据是 `SCREENSHOT_DECLARED`，`field0107` 的物理列以及枚举实库映射等仍是 `NOT_VERIFIED`。“截图字段类型/长度”和“截图最终类型”是界面展示值，不是已核对的 MySQL metadata。

| OA 字段 | 截图字段类型/长度 | 显示名称 | 字段输入类型 | 截图最终类型 | 代码角色/当前转换 |
|---|---|---|---|---|---|
| `field0097` | `VARCHAR / 100` | 流水号 | 文本 | `VARCHAR(100)` | 候选业务键上下文；稳定性未验证 |
| `field0074` | `VARCHAR / 20` | 填写人 | 选人 | `VARCHAR(20)` | 候选上下文；值是否为 `org_member.id` 未验证 |
| `field0075` | `VARCHAR / 20` | 部门 | 选部门 | `VARCHAR(20)` | 候选上下文 |
| `field0076` | `TIMESTAMP / 255` | 填写日期 | 日期 | `DATE` | 截图的字段类型与最终类型不一致，必须用 metadata 核对 |
| `field0100` | `DECIMAL / 20` | 销假类型 | 下拉 | `BIGINT` | `LEAVE_TYPE_ENUM`；与请假单 `formmain_0170.field0089` 共用同一套请假类型业务目录；转换器不读取，原始 ID/标签集合仍 `NOT_VERIFIED` |
| `field0098` | `VARCHAR / 100` | 原请假单 | 文本 | `VARCHAR(100)` | 原单关联候选字段；关联键语义未验证 |
| `field0099` | `VARCHAR / 100` | 原请假单流水号 | 文本 | `VARCHAR(100)` | `CONTEXT_ONLY`；纯领域区间解析器将它与请假 `formmain_0170.field0097` 做精确字符串比较；物理关联/唯一性和生产联表未验证 |
| `field0083` | `VARCHAR / 20` | 请假人 | 选人 | `VARCHAR(20)` | 候选主体 ID；是否保存单一 `org_member.id` 未验证 |
| `field0092` | `VARCHAR / 20` | 请假人岗位 | 选岗位 | `VARCHAR(20)` | 候选上下文 |
| `field0093` | `VARCHAR / 20` | 请假人职务级别 | 选职务级别 | `VARCHAR(20)` | 候选上下文 |
| `field0085` | `VARCHAR / 20` | 请假人部门 | 选部门 | `VARCHAR(20)` | 候选上下文 |
| `field0084` | `VARCHAR / 100` | 请假人工号 | 文本 | `VARCHAR(100)` | 候选工号复核字段；不得用于反查人员 |
| `field0089` | `DECIMAL / 20` | 请假类别 | 下拉 | `BIGINT` | `LEAVE_TYPE_ENUM`；与请假单 `formmain_0170.field0089` 共用同一套请假类型业务目录；转换器不读取，原始 ID/标签集合仍 `NOT_VERIFIED` |
| `field0086` | `DATETIME / 255` | 实际请假开始时间 | 日期时间 | `DATETIME` | 候选销假区间开始；时区未验证 |
| `field0087` | `DATETIME / 255` | 实际请假结束时间 | 日期时间 | `DATETIME` | 候选销假区间结束；区间边界未验证 |
| `field0088` | `DECIMAL / 22` | 共计天数 | 文本 | `DECIMAL(22,4)` | 候选时长复核字段；不得直接代替时间求交 |
| `field0107` | 用户声明，未取证 | 系统计算返还小时 | 系统回填 | `NOT_VERIFIED` | `SYSTEM_CALCULATED_HOURS`；目标 OA 的列存在性、数值精度和流程可写性必须现场验证 |
| `field0090` | `VARCHAR / 100` | 备注 | 文本 | `VARCHAR(100)` | 候选上下文 |
| `field0091` | `VARCHAR / 255` | 销假说明 | 文本域 | `VARCHAR(255)` | 候选上下文；属于隐私最小化字段 |
| `field0094` | `VARCHAR / 20` | 代理人 | 选人 | `VARCHAR(20)` | 候选上下文 |
| `field0095` | `VARCHAR / 20` | 所属部门 | 选部门 | `VARCHAR(20)` | 候选上下文 |
| `field0096` | `VARCHAR / 100` | 工号 | 文本 | `VARCHAR(100)` | 第二候选工号复核字段；不得用于反查人员 |

当前必须保留以下门禁：

- 没有 `formmain_0170.field0097/field0103` 和 `formmain_0370.field0099/field0107` 的完整 live metadata，因此实际 SQL 类型、可空性、精确比较语义、小时精度和流程可写性都是 `NOT_VERIFIED`；多张销假是合法业务形态，不再要求物理基数不大于 1；
- 没有 `formmain_0370.id → col_summary.form_recordid` 的实库基数证据，审批状态列、原始值及生效/撤回语义都是 `NOT_VERIFIED`；
- `field0089`、`field0100` 均与 `formmain_0170.field0089` 共用同一套请假类别枚举，这是 2026-08-13 用户明确的业务口径；不等于三个字段的原始枚举 ID 和 `ctp_enum_item.showvalue` 已经实库核对；
- `field0086/field0087` 的 OA 时区和结束边界仍未签署；请假—销假多返还区间和累计小时业务规则已由用户明确，并已有纯领域解析器，但没有生产调用方；
- 当前没有固定投影查询、审批过滤、分页/水位、人员解析、版本链、请假—销假生产联表/返还事实装配或生产同步闭环；未完成取证和接线前必须 fail closed。

### 8.8 请假—销假关联、多返还区间与累计小时规则

2026-08-14 用户明确了以下业务规则，当前纯领域 `OaLeaveEffectiveIntervalResolver` 也已固定对应的失败关闭边界：

1. 关联键是请假 `formmain_0170.field0097` 与销假 `formmain_0370.field0099`，必须使用 Java `String.equals` 语义严格相等；不 trim、不改大小写、不转数字。
2. 一张请假单允许 0～N 张当前销假业务单；调用方必须在同一一致水位取全并归并同一业务单的历史版本，不能 `LIMIT 1`、只取最后更新时间的一张，或让最后一张覆盖整张原请假。
3. 每张 `APPROVED_EFFECTIVE` 销假单的 `formmain_0370.field0086/field0087` 是一个连续返还区间，必须完全位于原请假区间内；相邻端点允许，已批准区间发生实际重叠时失败关闭。
4. 每张已批准销假以唯一销假业务单 ID 和 `field0107` 形成独立返还小时事实；业务单 ID 缺失或重复必须失败关闭，并用于关联原 eventId/摘要安全重放；累计返还不得超过原请假已经按 `field0103` 扣减的小时，等于已扣减小时合法，超过时不得挑选部分单据继续。
5. 明确未生效的销假不形成返还事实；关联流水号不一致、主体不一致、审批结论未验证、小时不是 0.5 的整数倍、区间越界/重叠或累计超额时，均失败关闭。
6. 婚育、丧假是否属于同一业务事件、亲属关系等没有结构化字段的信息由审批人最终判断；代码不读取备注文本进行猜测。

上述是 `USER_DECLARED` 业务规则和纯领域代码行为，不是已经运行的 OA 取数闭环。解析器本身不查询 OA、不归并同一业务单的历史版本，也不把 OA 原始状态数字直接解释为审批结论；当前没有已签署的审批查询或生产联表/解析器 caller。这些都保持 `NOT_VERIFIED`/未接线。

## 9. OA 主体、工号和时间转换

### 9.1 主体 ID 接受范围

转换器只接受：

- 仅由 ASCII 数字组成的字符串；
- `BigInteger`；
- `Byte`、`Short`、`Integer`、`Long`；
- 数值必须大于 0。

以下均判无效：前后空格、`+123`、负数、0、小数、全角数字、列表/JSON、任何非数字字符。

### 9.2 唯一 OA 查询

```sql
SELECT id, code
FROM org_member
WHERE id = ?
```

执行约束：

- 使用 PreparedStatement；
- ID 按 `BigDecimal` 绑定，避免 64 位溢出；
- 查询超时；
- 最多返回 2 行；
- 连接必须只读。

该 SQL 只按 `id` 定点查询，不读取或过滤 `state`、`is_enable`、`is_deleted`、`status`，也没有公司或组织范围条件。因此它只能证明“该 ID 恰好返回一行且 `code` 非空”，不能证明该成员当前有效、启用、未删除或属于目标公司。正式启用前必须补充并签署生命周期与范围口径。

### 9.3 查询结果判定

| 条件 | 问题码/结果 |
|---|---|
| 查询异常 | `ORG_MEMBER_LOOKUP_FAILED` |
| 0 行 | `ORG_MEMBER_NOT_FOUND` |
| 多行 | `ORG_MEMBER_AMBIGUOUS` |
| 返回 ID 与请求不一致 | `ORG_MEMBER_LOOKUP_ID_MISMATCH` |
| `code` 为空或空白 | `ORG_MEMBER_CODE_INVALID` |
| 恰好一行、ID 相同、code 非空 | 得到 OA 工号候选 |

### 9.4 表单工号复核

如果表单含工号字段：

- 必须是字符串；
- 使用 Java `String.equals` 与 `org_member.code` 精确比较；
- 不 trim、不转大小写、不做数值转换；
- 存在但不一致时，候选无效并记录问题。

表单工号字段完全缺失，或字段存在但 SQL 值为 `NULL`，都会跳过该字段的复核，不会仅凭这一点报错；空字符串则是实际字符串，会与 `org_member.code` 精确比较并通常产生不一致。主体和所有未签署合同仍必须通过。

### 9.5 纯领域转换器内的身份链只走到 OA 工号

只有在调用方**显式调用** `OaFormRowTransformer` 时，纯领域转换器才会执行：

```text
表单主体字段 → org_member.id → org_member.code
```

当前主代码没有生产调用方把七类 OA 表单行交给该转换器。条件配置开启后也只创建只读连接池和成员查询适配器，不会查询包括 `formmain_0370` 在内的七类业务单据。因此下面的本地员工链既未接上，前面的 `org_member` 解析也不能描述成生产同步已经执行：

```text
org_member.code
  → employee_version.employee_number
  → employee_id
  → 发生时点有效 employment_assignment
  → organization_id / organization_version
```

仓库中虽有精确员工/任职解析器，但 OA 转换器和 OA 条件配置没有调用它。

### 9.6 时间转换规则

- 区间字段必须已经是 `LocalDateTime`，不自动解析任意字符串；
- 区间结束必须严格晚于开始；
- 日期范围字段必须是 `LocalDate`；
- 日期结束可以等于开始，但不能早于开始；
- 补卡点必须是 `LocalDateTime`；
- 当前转换器不应用 OA 来源时区；
- 日期范围不自动把结束日期加一天。

### 9.7 白名单和失败关闭

转换器步骤：

1. 按表单类型加载固定白名单；
2. 复制主表/明细表输入；
3. 检查是否应有主表、是否应有明细；
4. 拒绝所有白名单外字段；
5. 解析主体 OA 成员 ID；
6. 定点查询 `org_member`；
7. 解析区间、日期范围或时间点；
8. 精确复核表单工号；
9. 添加所有未签署合同问题；
10. 只有主体、时间存在且问题列表为空，才设 `effectiveCandidate=true`。

七类静态映射目前都还带有 schema、状态、主从外键、枚举、时区或日期边界的 `NOT_VERIFIED`。转换器会把它们转成问题，因此按当前代码，真实行即使主体和时间正确也会失败关闭，不能计入考勤。销假单已经具备可调用的纯结构转换路径，请假—销假也有纯领域多返还区间/累计小时解析器；现有查询适配器仍是默认关闭且未验收的草案，没有已签署的审批/枚举解析、版本归并或生产联表/返还事实 caller。

## 10. OA 流程状态和枚举

### 10.1 当前 Java/数据库状态漂移

当前 Java 端口识别：

- `APPROVED`
- `DRAFT`
- `REJECTED`
- `UNKNOWN`
- `MODIFIED`
- `SUPPLEMENTED`
- `REVOKED`

数据库迁移还允许 `PENDING`，而当前 Java 端口、前端类型和报表发布模型未统一接受它。这是合同漂移，正式启用前必须统一。

### 10.2 历史实库状态证据

2026-08-10 只读取证确认：

```text
formmain_0171.id = col_summary.form_recordid
```

可唯一关联加班主单。样本状态分布为 `3` 最多，其次 `0`、`2`、`NULL`，并有极少量 `1`。

项目记忆中的解释是：

| `col_summary.state` | 历史解释 | 当前代码状态 |
|---|---|---|
| `3` | 流程结束/最终态，历史上拟映射批准 | 未签署，当前转换器未读取 |
| `0` | 已发起/处理中 | 未签署 |
| `2` | 撤销 | 未签署 |
| `NULL` | 草稿/保存未发送 | 未签署 |
| `1` | 含义未知 | 必须隔离 |

这些含义属于“实库线索 + 历史记忆”，不能写成当前生产程序已经执行的映射。

### 10.3 枚举联表目标

历史取证确认正确的显示值表是 `ctp_enum_item`，拟按原始枚举 ID 关联 `ctp_enum_item.id` 并读取 `showvalue`。当前未形成已签署、可启用的七类生产查询合同；默认关闭的适配器草案也没有完成这一整套转换，销假单的两个枚举列仅被标为共享 `LEAVE_TYPE_ENUM`，并不代表已解析或已可生效。

### 10.4 请假类别目录

当前代码目录定义：

| `showvalue` | 内部代码 | 当前带薪出勤目录 |
|---|---|---|
| 年假、年休假 | `ANNUAL_LEAVE` | 是 |
| 病假 | `SICK_LEAVE` | 否 |
| 事假 | `PERSONAL_LEAVE` | 否 |
| 调休、调休假 | `TIME_OFF` | 是 |
| 婚假、结婚假 | `MARRIAGE_LEAVE` | 是 |
| 产假 | `MATERNITY_LEAVE` | 是 |
| 陪产假 | `PATERNITY_LEAVE` | 是 |
| 丧假 | `BEREAVEMENT_LEAVE` | 是 |
| 工伤、工伤假 | `WORK_INJURY_LEAVE` | 是 |
| 护理假 | `NURSING_LEAVE` | 是 |
| 哺乳时间、哺乳假 | `BREASTFEEDING_TIME` | 是 |
| 产检时间、孕检假 | `PRENATAL_EXAM_TIME` | 是 |
| 计生假 | `FAMILY_PLANNING_LEAVE` | 否 |
| 其他 | `OTHER_LEAVE` | 否 |

该目录当前没有被 OA 转换器、生产入库服务或出勤率计算器调用。“目录标记为带薪”不等于当前报表已经把它计入出勤率。

2026-08-13 用户明确：销假表 `formmain_0370.field0089`、`formmain_0370.field0100` 与请假表 `formmain_0170.field0089` 共用上述同一套请假类别业务目录。这是业务口径，不是实库验证结果；正式查询仍需分别统计三个字段的原始 ID，联表取 `showvalue`，并核对三个值集完全一致。

### 10.5 加班类别目录

| `showvalue` | 内部代码 | 辅助语义 |
|---|---|---|
| 加班费 | `COMPENSATED_OVERTIME` | 薪资项目语义为真 |
| 调休 | `TIME_OFF_IN_LIEU` | 记调休额度语义为真 |
| 义务加班 | `OBLIGATORY_OVERTIME` | 不生成前两类辅助语义 |

当前目录同样没有接入生产 OA 转换和正式计算；薪资能力也不对当前前端会话公开。

## 11. OA 当前页面、数据结构和缺失链路

### 11.1 当前页面实际行为

菜单路径 `/sources/oa`，要求 `ATTENDANCE_SOURCE:READ`。页面只执行：

1. 查询考勤来源列表；
2. 取第一条 `sourceType=OA_ATTENDANCE`；
3. 查询该来源前 100 条符合读取条件的已入库 OA 文档；
4. 显示外部单号、类型、状态、是否计入考勤和有效时间范围。

读取 SQL 还要求 `employee_match_decision.match_status='MATCHED'`，记录能关联员工及任职，并通过当前调用人的公司/组织/本人数据范围；因此页面不是该来源全部 OA 文档的原始清单。“计入考勤”只是页面按 `source_status ∈ {APPROVED, MODIFIED, SUPPLEMENTED}` 即时计算的标识，不代表单据已进入当前月报；生产发布编排器仍向计算命令传入空 OA 列表。

当前还有两项显示漂移：后端类型为 `TRIP`、`PUNCH_CORRECTION`，前端标签却匹配 `TRAVEL`、`CORRECTION`，所以这两类以及 `EXEMPT_PUNCH`、`TIME_OFF`、`LEAVE_REVOCATION` 会回退显示为“其他考勤单据”；查询不返回 `point_instant`，补卡即使有点时间，页面仍显示“未形成有效区间”。

页面没有 OA 来源/公司切换、OA 来源注册、连接测试、合同编辑、启动同步或字段映射操作。

当前同步 Controller 注入的是得力专用同步服务，SQL 又限定 `source_type='DELI_CLOUD'`；不能使用“同步记录”的得力重试路径去重试 OA。

### 11.2 预留表结构

数据库预留目标链：

```text
attendance_source
  → raw_attendance_fact
  → normalized_attendance_record
  → employee_match_decision
  → oa_attendance_document
  → effective_attendance_event
  → effective_event_lifecycle_fact
  → evidence_link
  → attendance_recalculation_intent
```

`oa_attendance_document` 预留：

- 来源 ID、来源业务键、来源版本；
- 单据类型、来源状态；
- 规范化记录 ID；
- 知识排序；
- 被替代旧文档 ID；
- 首次提交、批准、修改、撤销和创建时间。

类型允许请假、请假撤销、加班、出差、外出、补卡、调休、免打卡。

运行合同表还可以保存主键、主从关系、审批列、状态原始值集合、更新时间游标、稳定游标、枚举 JSON、时区和日期边界。但当前没有 Java Repository/Service/Controller 使用这些合同表。

### 11.3 当前缺失的生产步骤

1. OA 来源注册和公司/考勤组范围绑定；
2. 元数据探针和合同草稿；
3. 签署七类表单的七张主表与四张明细表生产查询并补齐 Repository/Service caller；静态目录已覆盖请假 `formmain_0170.field0097/field0103` 和销假 `formmain_0370` 22 个候选字段，现有默认关闭适配器草案未携带系统小时/原单关联且不能直接启用；
4. `col_summary` 状态联表；
5. `ctp_enum_item` 显示值联表；
6. 每类表单的稳定增量游标；
7. 页级事务和水位推进；
8. 原始身份幂等；
9. 旧版本 supersede 和撤销；
10. 请假 `field0097` 与销假 `field0099` 的参数化生产联表、全部当前销假版本归并、审批过滤、返还区间/小时装配和 `OaLeaveEffectiveIntervalResolver` caller；
11. `org_member.code` 到本地员工/任职解析；
12. 规范 OA 事件和重算意图；
13. 完整计算及月报投影。

### 11.4 历史未合入方案仅作参考

历史分支曾设计六类表单各自的复合游标 `(updateValue, stableValue)`、keyset 查询、每页最多 3 次拉取尝试（首次调用加最多 2 次重试）、每页新事务、相同来源键摘要校验、修改/撤销追加事实等。这些代码未进入当前构建，且当时的标识符白名单仍无法覆盖实库中的 `id`、`formmain_id`、`col_summary` 和 `ctp_enum_item`，不能视为已有功能。

## 12. 考勤配置解析

### 12.1 关联关系

```text
公司
  → 有效员工版本
  → 有效任职及组织版本
  → 员工考勤组分配
  → 考勤组修订
      → 公司地点修订及其 ACTIVE 时间线
      → 工作日历族和已发布版本
      → 默认班次模板
  → 指定日历日
      → 可选班次版本覆盖
      → 否则使用考勤组默认班次的已发布版本
  → 规则绑定族和绑定修订
      → 精确规则版本
```

### 12.2 权威解析顺序

`MyBatisAttendanceConfigurationResolver` 是得力打卡入账时使用的配置解析器。它对目标公司、员工和事件时点依次要求：

1. 唯一有效员工版本；
2. 唯一当前物理版本且在事件时点有效的任职；
3. 唯一有效组织版本；
4. 唯一有效考勤组分配，且不存在同时生效的后继冲突；
5. 唯一、有效、与分配一致且 timeline 状态为 `ACTIVE` 的考勤组修订；考勤组没有发布生命周期；
6. 公司地点投影的修订在该日有效，且 `location_timeline` 最新状态为 `ACTIVE`；当前 SQL 不连接或校验 `shared_location_revision`、`company_location_availability`；
7. 工作日历存在已发布版本；
8. 指定日历日存在且属于该版本；
9. 如果日历日指定班次覆盖，则优先使用覆盖版本；否则使用考勤组默认班次；
10. 班次版本已经发布并在该日有效；
11. 公司、地点、日历、班次时区一致；
12. 形成不可变配置摘要和权威性结论。

任一层为零条、多条、跨公司、版本不连续、未发布或时区不一致，均不应任选一条继续计算。

该解析器到班次为止，**不读取任何考勤规则绑定或规则版本**。上面的整体关系图把规则绑定画出，是因为规则试算/配置服务另有 `AttendanceConfigurationService` 链路；不能据此认为得力入账配置摘要已经包含五类规则。

### 12.3 跨午夜候选业务日期

得力入账时会合并：

- 解析器给出的候选日期；
- 打卡在配置解析所得 `businessTimeZone`（地点/考勤业务时区）的自然日期，而不是数据源配置的 `sourceTimeZone`；
- 若本地时间早于 06:00，再加入前一天；
- 去重后最多保留 4 个日期。

每个候选日期都检查考勤期间保护：

- `OPEN` 或 `REOPENED`：允许继续；
- 未知/不存在：该记录隔离；
- `FROZEN` 或 `CLOSED`：整页失败，保护已冻结结果。

注意：该 06:00 规则只用于打卡入账配置和重算日期候选；当前生产月报仍按 `Asia/Shanghai` 自然日分组，没有使用该业务日规则。

### 12.4 得力入账期间保护的实际权威

当前得力入账**不读取** V18 的 `attendance_period`。`ProjectionBackedAttendancePeriodProtection` 实际查询覆盖业务日期的最新一条 `PUBLISHED` `attendance_report_projection`，并使用其中的 `period_state`、投影版本、投影摘要以及当日员工/任职/组织身份形成期间保护结论：

- 恰好一条合法权威记录，且 `period_state` 为 `OPEN/REOPENED`：允许生成有效事件；
- `FROZEN/CLOSED`：保护期间，整页失败；
- 没有已发布投影、身份锚点无效或员工/任职解析不唯一：返回 `UNKNOWN`，按上一节所述进入“期间保护不可用”路径。

这形成一个重要启动条件：首次正式导入得力打卡前，目标日期必须已经有可用的已发布报表投影；否则即使员工匹配成功，也无法生成有效打卡事件。该“先有投影再允许入账”的依赖应在上线前专项验证。

### 12.5 五类当前 Java/UI 规则

| `PolicyKind` | 核心字段 | 当前校验 |
|---|---|---|
| `MEAL_DEDUCTION` | 启用；餐时起止；扣减分钟；触发分钟；适用日类型；可选周六/周日/节假日午晚餐覆盖 | 区间和分钟须合法，按日类型选窗口 |
| `LATE_GRACE` | 启用；宽限分钟 | 当前固定要求 15 分钟 |
| `MONTHLY_LATE_EXEMPTION` | 启用；宽限分钟；月次数；换组是否重置 | 当前固定 15 分钟、每月 1 次 |
| `PUNCH_WINDOW` | 到岗前/后、离岗前/后分钟 | 每项 0—720 |
| `PERIOD_CLOSE` | 次月关账日、是否允许重开、是否需审批、最多重开次数 | 关账日 1—28，最多次数 0—99 |

初始化基线中，打卡窗口大致为到岗前后各 60 分钟、离岗前 60/后 120 分钟；月结为次月 5 日、允许重开。它们只是初始化值，正式采用前必须由客户确认并发布公司范围版本。

### 12.6 SQL 规则目录与 Java 漂移

后续 SQL 还增加了：

- `EARLY_DEPARTURE`
- `MISSING_PUNCH`
- `PUNCH_ARBITRATION`
- `OVERTIME_RECOGNITION`
- `ATTENDANCE_RATE`

但当前 Java `PolicyKind` 没有这五项，Repository 读取时直接 `valueOf`。如果通过当前 Java 规则接口读到这些 SQL 类型，可能发生枚举转换异常。因此客户页面的正式可编辑目录仍只能按五类描述；额外 SQL 模板不能当作已接线规则。

## 13. 完整确定性考勤计算器：已实现但未生产接线

### 13.1 输入快照

完整计算器输入包含：

- 公司、员工、任职期间；
- 业务日期、`businessZone`（业务时区）、知识截止时间；
- 不重叠的计划工作分段；
- 每段到岗和离岗打卡窗口；
- 有效打卡事件；
- OA/调整/撤销等区间或点证据；
- 月度迟到豁免已用次数；
- 补卡截止时间、加班提交截止等策略；
- 配置、证据、调整摘要；
- `periodId`、`periodVersion`、`periodToken`；输入模型没有 `periodStatus` 字段；
- 算法版本、请求 ID、关联 ID。

输出包含各类分钟、规则命中、证据决策、解释图、被消费打卡 ID、异常稳定指纹、输入摘要和结果摘要。

### 13.2 证据优先级

数字越小越优先：

| 优先级 | 证据 |
|---:|---|
| 1 | 已批准人工调整 |
| 2 | 撤销/冲正 |
| 3 | 请假、调休、外出、出差、免打卡、补卡、加班 |
| 4 | 打卡 |
| 5 | 系统发现 |

计算器在班次分段与证据边界上切片。每个时间片：

- 选择最高优先级有效证据；
- 同优先级、同结果类别的重复证据按稳定顺序选择；
- 同优先级但结论互斥时生成 `EVIDENCE_CONFLICT`，该片分钟为 0；
- 不按“最后写入覆盖”掩盖冲突。

### 13.3 OA 证据映射到计划时间片

| 证据 | 计划内结果类别 | 对确认出勤的影响 |
|---|---|---|
| `LEAVE` | `LEAVE` | 记请假，不记确认出勤 |
| `TIME_OFF` | `TIME_OFF` | 记调休，不记确认出勤 |
| `OUTING` | `OUTING_WORK` | 可记确认工作 |
| `TRIP` | `TRIP_WORK` | 可记确认工作 |
| `EXEMPT_PUNCH` | `EXEMPT_WORK` | 可记确认工作 |
| 区间补卡 | `EXEMPT_WORK` | 可覆盖缺卡工作片 |
| `OVERTIME` | 不直接覆盖计划内类别 | 作为计划外加班授权 |
| `REVERSAL` | 撤销较低优先区间证据 | 把时间片放回“未覆盖”集合，随后进入打卡/缺卡处理；不会继续选择下一条低优先级区间证据 |

### 13.4 上下班打卡选择

对每个计划工作分段：

- 到岗候选：在到岗窗口内，方向不是 `EXIT`；选择最早时刻；
- 离岗候选：在离岗窗口内，方向不是 `ENTRY`；选择最晚时刻；
- 窗口端点包含；
- 已被一个分段消费的打卡不能被另一分段重复使用。

歧义包括：

- 同一候选时刻有多条记录；
- 同一个有效事件同时成为到岗和离岗。

同一打卡落入多个班段的窗口时，当前算法不自动报歧义：班段按顺序计算，先处理的班段消费该打卡，后续班段不能再使用。这是确定性的“先到先得”，并不等于业务上已解决窗口重叠问题。

### 13.5 正常出勤和延长在场

若一段同时存在唯一到岗和离岗：

- 未被更高优先证据覆盖的计划内切片记 `SCHEDULED_WORK`；
- 对每个班段分别判断：离岗晚于该班段结束的在场部分可记 `EXTENDED_PRESENCE`，不要求晚于全日最后一个计划班段；
- 仅延长在场不自动等于认可加班，仍需有效加班授权和配对逻辑。

### 13.6 迟到

原始迟到分钟为：

```text
实际到岗时刻 - 首个未被高优先证据覆盖的工作起点
```

使用整分钟向下取整。若：

- `rawLateMinutes <= lateGraceMaxMinutes`；并且
- 当月可用豁免次数尚未用完，

则本班段处罚分钟为 0，并生成 `MONTHLY_LATE_GRACE_CONSUMED` 规则命中。计算器只读取输入的同一份 `GraceConsumptionSnapshot.used`，不会在内部递增或持久化已用次数；因此当前只能说“生成消费命中”，不能说它已可靠消费且只消费一次。超出阈值时处罚完整原始迟到分钟，不是只罚超过阈值的部分。

测试边界固定：15 分钟在 15 分钟阈值内可豁免，16 分钟处罚完整 16 分钟。

### 13.7 早退

原始早退分钟为最后计划工作段结束与实际离岗之间、且未被更高优先证据覆盖的交集。当前完整计算器没有类似迟到的月度早退豁免，直接记录完整分钟。

### 13.8 缺卡和缺勤

如果到岗/离岗缺失或歧义：

- 在补卡截止前：`MISSING_PUNCH_PENDING`，未覆盖计划片暂不记缺勤；
- 员工已在截止内提交补卡，即使仍待审批，也暂不记缺勤；
- 超过截止且无有效补卡：未被请假、出差、外出等覆盖的计划分钟记缺勤；
- 同时生成稳定异常指纹，便于后续冲正而不重复计数。

### 13.9 加班授权、打卡配对和餐时扣减

对每条有效加班证据：

1. 只取授权区间内尚未被排班消费的打卡；
2. 打卡数必须为偶数；
3. 若全部方向为 `AUTO`，按时间顺序两两配对；
4. 若有明确方向，每对必须严格 `ENTRY → EXIT`；
5. 将每个在场段裁剪到授权区间；
6. 汇总实际加班在场分钟；
7. 检查申请提交及时性；
8. 若及时，认可分钟先等于实际分钟；
9. 对适用日类型和餐时窗口逐个应用扣减；
10. 只有在场满足规则覆盖/重叠和触发分钟才扣；
11. 每个窗口最多扣一次，最终最低为 0；
12. 申请过晚或缺少批准时，认可加班为 0。

测试边界包括：47 小时 59 分钟内提交可认可，48 小时 01 分钟不认可；奇数个 `AUTO` 打卡不计算；扣餐不会产生负分钟。

出差或外出超过最后计划结束，但没有覆盖超出部分的加班授权，会命中 `OUTING_OVERTIME_UNDECLARED` 一类待复核规则，认可加班仍为 0。

### 13.10 完整计算器指标

```text
scheduledMinutes
  = 所有计划工作分段分钟之和

confirmedAttendanceMinutes
  = SCHEDULED_WORK
  + OUTING_WORK
  + TRIP_WORK
  + EXEMPT_WORK

recognizedOvertimeMinutes
  = 通过授权、在场、及时性和餐扣规则后的加班分钟

leaveMinutes
  = LEAVE + TIME_OFF 时间片

absenceMinutes
  = 超过补卡截止后仍无证据覆盖的计划分钟

actualAttendanceMinutes
  = confirmedAttendanceMinutes
  + recognizedOvertimeMinutes
```

迟到、早退是单独指标；当前完整计算器并不简单使用“计划分钟 - 迟到 - 早退”来计算确认出勤。

## 14. 当前生产月报实际算法：`PUNCH_SPAN_V1`

### 14.1 输入数据

当前 `CalculationEngineOrchestrator` 读取：

- 目标公司、月份内的有效员工版本：工号、姓名；
- 当前有效任职；
- 组织及组织版本；
- 已激活 `PUNCH_POINT` 的 `point_instant`；
- 已发布工作日历日。

它当前不读取：

- 考勤组分配；
- 班次及打卡窗口；
- 五类规则；
- OA 文档；
- 异常事实；
- 年假/调休账户；
- 完整计算器输出。

### 14.2 员工和组织选择

对每名员工/日期要求恰好一个身份和任职归属。零条或多条时跳过，不猜测组织。月中调岗可能按发生时点组织及版本拆成不同汇总行。

### 14.3 自然日分组

所有有效打卡先转换到 `Asia/Shanghai`，按自然日 `00:00—24:00` 分组。

- 不使用得力入账事务和配置解析器中固定的 06:00“前一日候选”逻辑；该常量并不来自数据源配置；
- 不使用班次归属日；
- 夜班凌晨下班打卡会进入第二个自然日。

### 14.4 每日首末跨度

设当天打卡集合为 `P`：

```text
firstPunch = min(P.pointInstant)
lastPunch  = max(P.pointInstant)

if count(P) >= 2:
    workedMinutes = floor(minutes(lastPunch - firstPunch))
else:
    workedMinutes = 0

missingPunchCount = 1 if count(P) is odd else 0
```

具体含义：

- 0 条：工时 0，缺卡 0；
- 1 条：工时 0，缺卡 1；
- 2 条：首末跨度，缺卡 0；
- 3/5/... 条：仍按首末跨度，缺卡 1；
- 4/6/... 条：仍按首末跨度，缺卡 0；
- 中间午休、外出和离场不会被扣除；
- 打卡方向完全忽略。

### 14.5 固定日事实字段

编排器内部局部变量 `workedMinutes` 保存首末打卡跨度，但 `DailyFact` 和数据库没有同名字段；它只被赋给下面两个实际落表字段。

| 字段 | 当前生产值 |
|---|---|
| `scheduledMinutes` | 0 |
| `confirmedScheduledWorkMinutes` | 等于首末跨度 |
| `recognizedOvertimeMinutes` | 0 |
| `leaveOrTimeOffMinutes` | 0 |
| `absenceMinutes` | 0 |
| `actualWorkMinutes` | 等于首末跨度 |
| `lateMinutes` | 0 |
| `penalizedLateMinutes` | 0 |
| `earlyDepartureMinutes` | 0 |
| `missingPunchCount` | 奇数条打卡为 1，否则 0 |
| `shiftLabel` | `未匹配班次` |
| 计算规则版本 | `ATTENDANCE.PUNCH_SPAN:V1` |

发布命令中的 OA、异常和时间账户事实当前传空列表。

### 14.6 日历日类型

优先读取已发布日历：

- `WORKDAY` → 工作日；
- `SPECIAL_WORKDAY` → 调整工作日；
- `PUBLIC_HOLIDAY` → 法定节假日；
- `WEEKEND` → 再按日期区分周六/周日。

如果日历缺失或多个发布日历对同一天结论不一致，回退到普通星期规则。

### 14.7 为什么很多报表字段为 0

不是因为系统确认“没有迟到、请假、加班或缺勤”，而是当前生产发布编排器没有取这些输入并执行完整规则。验收时必须把“0”与“未参与计算”区分。

## 15. 九类正式报表的取数和计算

### 15.1 共同读取原则

正式报表读取不可变投影：

- `attendance_report_projection`
- `attendance_report_daily_fact`
- `attendance_report_oa_fact`
- `attendance_report_exception_fact`
- `attendance_report_time_account_fact`

请求公司和月份必须在调用人的有效数据范围内，并且存在已发布投影。没有发布或投影未准备时返回冲突/未准备错误，而不是即时查询外部原表。

### 15.2 考勤明细

逐员工、逐日期读取日事实，显示组织版本、日类型、班次标签、首末打卡、各类分钟和缺卡。当前生产数据主要由 `PUNCH_SPAN_V1` 生成。

### 15.3 请假报表

只选择：

- `documentType` 为 `LEAVE` 或 `TIME_OFF`；
- 来源状态为 `APPROVED`、`MODIFIED`、`SUPPLEMENTED`；
- 半开区间与所选上海自然月有交集。

报表显示投影中已经存储的 `recognizedMinutes`，不在查询时重新与班次求交。因此如果 OA 事实没有进入投影，请假报表为空；如果上游认可分钟口径错误，查询层也不会自行修复。

### 15.4 加班报表

聚合日事实中的 `recognizedOvertimeMinutes`，按日类型分为：

- 工作日/调整工作日；
- 周六；
- 周日；
- 法定节假日。

当前 `PUNCH_SPAN_V1` 把认可加班写 0，所以加班报表不会体现完整计算器的授权、配对和餐扣结果。

### 15.5 工时报表

按员工 + 组织 + 组织版本汇总：

- 应出勤分钟；
- 确认出勤分钟；
- 认可加班分钟；
- 请假/调休分钟；
- 缺勤分钟；
- 实际工时。

投影事实约束：

```text
actualWorkMinutes
= confirmedScheduledWorkMinutes
+ recognizedOvertimeMinutes
```

### 15.6 异常报表

读取 `attendance_report_exception_fact`。当前生产发布传空异常列表，所以页面无异常不能被解释为“全员无异常”。

### 15.7 迟到报表

对 `lateMinutes > 0` 的员工日计次数，汇总原始迟到分钟和处罚分钟。当前简化发布固定写 0。

### 15.8 缺卡报表

汇总 `missingPunchCount`。当前值仅由“当天打卡条数是否为奇数”决定，并不是完整计算器按班次段、方向和补卡截止得出的缺卡。

### 15.9 出勤率报表

当前 Java 实际公式：

```text
attendanceRate
= confirmedScheduledWorkMinutes
  / scheduledMinutes
  × 100%
```

- 按员工 + 发生时组织/版本聚合；
- 两位小数；
- `HALF_UP`；
- `scheduledMinutes = 0` 时为 `N/A`；
- 不把认可加班放入分子。

部分 SQL 元数据和旧说明写过“实际/规定出勤”“带薪假计入”，数据库也曾增加 `paid_leave_minutes`。但当前 Java 日事实模型、写入 Mapper 和报表计算器没有使用带薪假字段，实际分子仍只有确认的计划内出勤分钟。当前简化发布又把应出勤写 0，因此大多数结果会是 `N/A`。

### 15.10 年假报表

按时间账户事实汇总：

```text
balanceHours
= openingHours
+ grantedHours
+ overtimeCreditHours
+ manualIncreaseHours
- usedHours
- expiredHours
+ returnedHours
- manualDeductionHours
```

结果保留两位小数，等价天数按 8 小时/天。当前生产发布器没有把业务年假台账转换成报表时间账户事实，所以员工详情能看到年假并不代表年假报表已经有数。
这里的 `manualIncreaseHours/manualDeductionHours` 是报表聚合字段名，不是允许写入
`time_account_ledger_entry.entry_type` 的台账类型；人工增减在业务台账中统一记为
带正负金额的 `ADJUSTMENT`。

## 16. 年假业务台账当前逻辑和边界

### 16.1 接口和权限

- `GET /api/v1/employees/{employeeId}/annual-leave`：`ANNUAL_LEAVE:READ`
- `POST .../opening`：`ANNUAL_LEAVE:ADJUST`
- `POST .../adjust`：`ANNUAL_LEAVE:ADJUST`

两个写接口都强制要求调用方提供合法 `Idempotency-Key`。该键按当前审计主体唯一；
同键同载荷返回已完成结果，同键改员工、任职、动作或参数会冲突，不会再次写账。
HR 管理员有读写；审计角色有读能力，但默认缺少进入员工列表/详情的前置能力，当前 UI 不可达。

### 16.2 期初余额

- 当前业务时点必须恰好存在一条有效任职；无有效任职或多条重叠任职均失败关闭；
- 账户按员工、当前 `employment_period_id` 和年度精确确定，不会随机命中二次入职前的旧账户；
- 优先读取已发布年假政策，否则使用默认政策；
- 默认 8 小时/天；
- 服务逻辑将期初日放在当年 8 月 1 日、到期日 12 月 31 日；
- 输入 0—9999 小时，原因 2—500 字；
- 首笔非零期初写 `OPENING`；再次设置按 `新期初 - 已导入期初合计` 写
  `ADJUSTMENT`；目标为零也会完成独立幂等记录；
- 账户行先 `FOR UPDATE`，锁内分配 `sequence_no`；提交前要求账户余额等于不可变台账合计。

### 16.3 手工调整

- 范围 -9999—9999 小时；
- 正数增加，负数扣减；
- 每次非零调整写一笔正或负的 `ADJUSTMENT` 和审计原因；不再使用
  `MANUAL_INCREASE/MANUAL_DEDUCTION`；
- 不做 `MAX(0, ...)` 截断。扣减超过余额，或 OA 活动预占使调整后
  `available_hours` 小于零时，整笔事务拒绝；
- 写前校验 `balance_hours = SUM(ledger.amount_hours)`，账台不一致时失败关闭。

### 16.4 并发、幂等和异常边界

`V44` 保存管理员期初/调整的请求摘要和已完成结果，幂等记录、台账追加和余额更新
处于同一个 InnoDB 事务。账户锁覆盖台账勾稽、活动预占检查、序号分配和余额更新；
任何一步失败都回滚，不允许通过直接修改 `balance_hours` 补偿。当前人工调整输入沿用原
接口的两位小数规则；OA 九个公开过程另行严格要求正数且为 0.5 小时整数倍。

## 17. 关账、重开和重算

### 17.1 完整领域策略

关账策略要求：

- 期间为 `OPEN` 或 `REOPENED`；
- 员工日期覆盖完整；
- 无未解决阻断异常；
- 无运行中数据源作业、导入或重算；
- 来源不过期；
- 控制总数和摘要一致。

重开要求当前为 `CLOSED`、引用原关账快照、有审批引用、期间版本递增并重新形成摘要。

### 17.2 当前生产断点

数据库已有期间、冻结、关账快照、阻断项、重开审批、关账后差异等表，领域策略也存在；但没有完整 REST Controller、应用服务和 Mapper。OpenAPI 声明不能视为真实处理器。

得力入账会写 `attendance_recalculation_intent`，但当前找不到消费者、完整日结果持久化和完成状态更新链路。因此“已写重算意图”不等于“已经按完整规则重新计算”。

报表发布接口接收期间状态，但没有替代完整关账策略的所有检查。

## 18. 关键不一致和风险清单

| 编号 | 现状 | 影响 | 建议 |
|---:|---|---|---|
| 1 | 完整计算器未接生产发布 | 规则页面与报表结果不一致 | 将计算器接入日结果和月投影，并做双算对账 |
| 2 | 生产按自然日首末跨度 | 夜班跨日拆分、休息不扣 | 接入业务日和班次窗口 |
| 3 | 生产不取 OA | 请假、加班、出差等报表为空或为 0 | 完成 OA 运行合同和同步 |
| 4 | OA 静态映射全部未验证 | 转换器必然失败关闭 | 实库签署后发布版本化合同 |
| 5 | `PENDING` 状态前后端漂移 | 可能无法读取或发布待办状态 | 统一 Java、DB、前端枚举 |
| 6 | OA 状态 3/0/2/NULL/1 含义未签署 | 错把进行中或撤销当批准 | 业务方书面确认，未知值隔离 |
| 7 | OA 类别目录未接线 | 假别和加班处理不进入计算 | 在合同层按 `ctp_enum_item.showvalue` 转换 |
| 8 | OA 身份只到 `org_member.code` | 不能生成本地员工事实 | 接精确员工/任职解析并处理重复工号 |
| 9 | 近似重复未生产接线 | 跨来源近似打卡可能双计 | 完成人工复核流程 |
| 10 | 证据查询接口 501 | 客户无法从页面追溯计算来源 | 接入只读证据链 API |
| 11 | 重算意图无消费者 | 新打卡不能自动刷新完整结果 | 实现幂等重算 worker |
| 12 | Excel 上传发布无后端 | 页面不能正式补数 | 完成上传、预检、发布、反转链 |
| 13 | Java 仅 5 类规则，SQL 又增 5 类 | 读取规则可能枚举异常 | 统一规则目录或隔离旧模板 |
| 14 | 年假台账类型与 DB CHECK 不一致（已修复） | 原实现可能保存失败 | 已统一为 `OPENING/ADJUSTMENT` 并增加合同测试 |
| 15 | 年假余额截断与台账差额（已修复） | 原实现可能破坏审计勾稽 | 已取消截断；超扣、预占下调和账台不符均失败关闭 |
| 16 | 带薪假未进入出勤率分子 | 与部分旧元数据不一致 | 客户签署公式后统一模型、Mapper、报表 |
| 17 | 系统管理员默认无报表权限 | “最高权限”文案易误导 | 保持最小授权并修正文案 |
| 18 | 反馈管理能力无可达读取菜单 | HR/负责人无法正式处理反馈 | 补管理查询和路由或移除误导菜单 |
| 19 | 审计有年假读能力但无员工详情前置权限 | UI 不可达 | 增加受控只读入口或调整 capability |
| 20 | 报表刷新无前端入口 | 原始打卡不自动出现于报表 | 提供受控发布入口和状态提示 |

## 19. OA 正式启用前验收顺序

1. DBA 创建只授予必要表 `SELECT` 的 OA 账号。
2. 运维通过环境安全注入连接变量，不记录明文。
3. 验证只读连接和服务器只读属性。
4. 对七张候选主表、四张明细表、`org_member`、`col_summary`、`ctp_enum_item` 做元数据探针，必须覆盖 `formmain_0170.field0097/field0103`、`formmain_0370` 的 21 个截图字段及用户声明的 `field0107`。
5. 签署主键、主从外键、审批关联、更新时间游标和稳定排序键；另行证明 `formmain_0170.field0097 = formmain_0370.field0099` 的精确比较语义，以及同一一致水位返回全部当前销假并完成版本归并。多张销假是合法业务形态，不能再要求关联基数不大于 1。
6. 签署 `col_summary.state` 全量值的业务含义；未知值必须隔离。
7. 签署 `ctp_enum_item.id → showvalue`，同时签署请假、加班类别及补卡 `field0134` 枚举映射（包括 OA-ENUM-03）；对 `formmain_0170.field0089`、`formmain_0370.field0089`、`formmain_0370.field0100` 的原始 ID/标签值集做完整一致性核对。
8. 签署 OA 时区、免打卡结束日期边界，以及出差/请假/销假/加班/外出五类区间按半开区间 `[开始, 结束)` 解释的 OA-TIME-03；请假—销假的精确关联、多返还区间和累计小时规则已确认，仍须签署审批映射、版本归并、时区及返还生效边界。
9. 统计 `org_member.code` 空值、重复值以及与本系统工号的零/多匹配；同时签署 `org_member.state/is_enable/is_deleted/status` 的生命周期语义和公司/组织范围口径。当前查询只有 `WHERE id = ?`，这些门禁尚未落入 SQL。
10. 发布版本化 OA 运行合同，而不是直接修改静态 Java 常量。
11. 注册 OA 来源并限定公司和考勤组范围。
12. 先同步小时间窗和少量人员，检查原始、规范化、匹配、隔离和事件。
13. 验证同一来源键重放、修改、撤销和乱序版本。
14. 验证请假没有销假、恰好一张及多张不重叠已批准销假、端点相邻、区间重叠/越界、累计返还刚好/超额、流水号/主体不一致、非生效及未知审批；多张合法场景必须保留逐单返还事实，不得最后一张覆盖。
15. 验证区间跨月、跨日、空时间、结束早于开始和跨午夜。
16. 接入完整计算器，核对请假、销假冲销、出差、外出、补卡、免打卡和加班。
17. 发布测试月投影，逐级核对 OA 页面、日明细、九类报表和工作台。
18. 先实现并发布可变或可派生的 OA 集成状态机制，同时更新后端、OpenAPI 和前端合同；全部门禁通过并完成客户签字后，才能解除当前硬编码的 `OA_LIVE = NOT_VERIFIED`，不能把它当作现有开关直接切换。

## 20. 考勤月度验收样例

至少覆盖：

| 场景 | 输入 | 当前简化算法预期 | 完整目标算法预期 |
|---|---|---|---|
| 普通两次打卡 | 08:30、17:30 | 540 分钟 | 按班次、窗口和覆盖证据计算 |
| 三次打卡 | 08:30、12:00、17:30 | 540 分钟，缺卡 1 | 按方向/窗口判断是否缺哪一端 |
| 午休离场 | 08:30、12:00、13:00、17:30 | 540 分钟，不扣午休 | 计划分段或规则决定是否扣除 |
| 夜班 | 20:00、次日 05:00 | 分到两个自然日，两个单打卡 | 归入班次业务日并配对 |
| 迟到 16 分钟 | 有班次和两次打卡 | 迟到 0 | 原始/处罚均 16，若阈值 15 |
| 只有一条打卡 | 单条 | 工时 0，缺卡 1 | 截止前待补，截止后按未覆盖计划计缺勤 |
| 已批准请假 | OA 区间覆盖上午 | OA 不进入发布 | 上午请假，剩余片按打卡计算 |
| 加班 4 条 AUTO 打卡 | 有有效加班授权 | 加班 0 | 两两配对、裁剪授权、餐扣、及时性后认可 |
| 加班奇数打卡 | 有授权但 3 条 AUTO | 加班 0 | 配对无效，加班 0 并记录原因 |
| 出差超下班无加班单 | 出差覆盖至晚间 | OA 不进入发布 | 超出部分不认可加班，产生待复核规则 |
| 同工号两名员工 | 打卡工号重复 | 入口应隔离 | 失败关闭，不任选一人 |
| 相同来源键不同摘要 | 重放修改载荷 | 页面失败 | 身份冲突，不覆盖历史 |

## 21. 代码证据索引

以下文件是本文主要事实来源，行号会随以后修改移动，复核时以类名、方法名和迁移名为准。

### 21.1 权限和菜单

- `backend/src/main/java/com/szsemicon/hr/identityaccess/interfaces/rest/AuthenticationController.java`
- `backend/src/main/java/com/szsemicon/hr/identityaccess/application/RoleScopeMatrix.java`
- `backend/src/main/resources/mappers/CapabilityMapper.xml`
- `frontend/src/app/routeAuthorization.ts`
- `backend/src/main/resources/db/migration/V10__formal_attendance_reporting.sql`
- `backend/src/main/resources/db/migration/V19__oa_mysql_runtime_contract.sql`

### 21.2 得力和证据入账

- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/deli/DeliEplusClient.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/application/DeliPunchPageTransaction.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/SourcePageCommitPolicy.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/EvidenceResolutionPolicy.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/persistence/MyBatisEmployeeEmploymentResolver.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/persistence/ProjectionBackedAttendancePeriodProtection.java`
- `backend/src/main/resources/mappers/AttendanceEvidenceMapper.xml`
- `backend/src/main/resources/db/migration/V8__attendance_source_and_evidence.sql`

### 21.3 OA

- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaStaticFormMappingCatalog.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/domain/oa/OaFormRowTransformer.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/oa/OaMysqlProperties.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/oa/OaReadOnlyConnectionPool.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/oa/OaMysqlOrgMemberDirectoryAdapter.java`
- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/port/OaAttendanceDocumentSourcePort.java`
- `backend/src/main/resources/mappers/AttendanceSourceReadMapper.xml`
- `frontend/src/features/attendanceSources/OaSourcesPage.tsx`
- `backend/src/main/resources/db/migration/V19__oa_mysql_runtime_contract.sql`
- `docs/verification/oa-live/2026-08-10/EVIDENCE.md`

### 21.4 配置和计算

- `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/persistence/MyBatisAttendanceConfigurationResolver.java`
- `backend/src/main/java/com/szsemicon/hr/attendance/domain/AttendancePolicyModels.java`
- `backend/src/main/java/com/szsemicon/hr/attendance/calculation/domain/AttendanceCalculationModels.java`
- `backend/src/main/java/com/szsemicon/hr/attendance/calculation/domain/DeterministicAttendanceCalculator.java`
- `backend/src/main/java/com/szsemicon/hr/reporting/infrastructure/orchestrator/CalculationEngineOrchestrator.java`
- `backend/src/main/java/com/szsemicon/hr/reporting/domain/AttendanceReportCalculator.java`

### 21.5 Excel、年假和期间

- `backend/src/main/java/com/szsemicon/hr/punchimport/infrastructure/excel/PunchWorkbookPolicy.java`
- `backend/src/main/java/com/szsemicon/hr/punchimport/infrastructure/excel/PoiPunchWorkbookGateway.java`
- `backend/src/main/java/com/szsemicon/hr/punchimport/domain/PunchImportStateMachine.java`
- `backend/src/main/java/com/szsemicon/hr/leavetimeaccount/interfaces/rest/AnnualLeaveManagementController.java`
- `backend/src/main/java/com/szsemicon/hr/leavetimeaccount/application/AnnualLeaveManagementService.java`
- `backend/src/main/java/com/szsemicon/hr/attendance/calculation/domain/AttendancePeriodClosePolicy.java`

## 22. 最终结论

截至本文代码基线：

- 得力 E+ 打卡已经具备较严格的正式采集、精确匹配、幂等、精确去重和隔离链路；
- 打卡 Excel 只有模板、解析和只读骨架，正式写入尚未闭环；
- OA 有安全连接骨架、成员定点查询、默认关闭且未签署的七类表单查询适配器草案，以及 11 张物理表/104 个候选字段的静态目录、纯失败关闭转换和只读文档页面；销假 `LEAVE_REVOCATION/formmain_0370`、请假 `field0097/field0103`、销假 `field0107` 及请假—销假多返还区间/累计小时纯领域解析器已进入代码，但草案未携带新小时/原单关联且没有生产同步 caller，审批/枚举解析、版本归并和请假—销假生产联表/返还事实装配仍未闭环；
- 完整确定性考勤计算器已经实现大量业务规则，但没有进入生产报表发布；
- 当前正式月报实际执行 `PUNCH_SPAN_V1`，即上海自然日首末打卡跨度；
- 出勤率当前代码是“确认的计划内出勤分钟 ÷ 应出勤分钟”，带薪假未进入分子；
- 年假调整、规则目录、状态枚举和若干 UI 路径仍存在明确合同漂移。

客户验收、月结和审计必须记录使用的投影版本、算法版本、数据截止时间和未接线项，不能把设计能力、表结构或页面占位当作已上线结果。
