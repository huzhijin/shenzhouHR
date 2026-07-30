# OA 考勤六类表单映射与联调签字矩阵

## 1. 当前结论

- 文档日期：2026-07-29
- 当前状态：`OA_LIVE=NOT_VERIFIED`
- 已有证据：用户提供的 OA 表单截图及字段转录
- 未有证据：OA 实库只读查询、系统审批列、审批值、主从外键、业务枚举、时区、版本/撤销语义
- 代码边界：已实现编译期静态表/列白名单、纯行转换、可选只读 OA MySQL 连接池，以及固定参数 SQL 的 `org_member.id -> code` 查询；审批状态、主从 FK 等未签字，因此尚未实现六类业务表查询，也没有真实 OA 凭据或连接验收

截图字段可作为联调候选合同，但不能证明实库表结构、字段类型、审批状态或关联关系。`org_member` 适配器只执行固定的
`SELECT id, code FROM org_member WHERE id = ?`，最多读取两行以暴露歧义。
任何标为 `NOT_VERIFIED` 的项目都必须阻断有效考勤证据，不得使用猜测值继续。

## 2. 不可变安全规则

1. 主体选人字段必须是一个严格 ASCII 正整数；不接受空格、正负号、小数、逗号分隔、多选数组、JSON、全角数字或名称。
2. 人员只能按主体选人值查询 `org_member.id`，并要求恰好一行。
3. 查询结果只取 `org_member.code` 作为人员外部编码；不得 `trim`、改大小写或转数字，必须保留前导零和大小写。
4. 表单工号只与 `org_member.code` 做逐字节一致性核验；不得用表单工号、姓名、部门、岗位反查或猜选员工。
5. 零行、多行、ID 回传不一致、空白 code、工号不一致都 fail closed。
6. `INTERVAL` 只接收两个已类型化的本地日期时间且结束严格晚于开始；本骨架不猜时区，不转 `Instant`。
7. `DATE_RANGE` 保留两个 `LocalDate` 原值；不得把结束日期扩展到 `23:59:59`，也不得在签字前猜包含/不包含边界。
8. `POINT` 只保留一个已类型化的本地日期时间；补签不得伪造成零长度区间。
9. 未确认审批状态列和值、主从 FK、业务枚举、时区或日期边界时，不得产生 `effectiveCandidate=true`。
10. 只允许代码内静态表名、静态列名和显式投影；不得接受调用方提供的 SQL、表名、列名、表达式、函数或脚本。

## 3. 截图字段静态目录

`SCREENSHOT_DECLARED` 只表示字段由截图转录，不表示已在 OA 实库确认。

| 表单 | 物理表 | 行角色 | 时间形态 | 主体选人 | 表单工号（仅核验） | 时间字段 | 其他截图字段 |
|---|---|---|---|---|---|---|---|
| TRIP | `formmain_0265` | MAIN | `INTERVAL` | `field0137` | 无 | 开始 `field0148`；结束 `field0149`（DATETIME） | 事由 `field0140`；累计天数 `field0141`（仅核验）；地点 `field0142`；填表人/部门/日期 `field0083/0084/0085`；出差人部门/岗位 `field0138/0139`；所属岗位/代理人/所属部门 `field0154/0155/0156`；交通字段 `field0151/0152/0153` |
| LEAVE | `formmain_0170` | MAIN | `INTERVAL` | `field0083` | `field0084`、`field0096` | 开始 `field0086`；结束 `field0087` | 天数 `field0088`（仅核验）；类别 `field0089`（枚举未确认）；备注/说明 `field0090/0091`；岗位/级别/代理人/所属部门 `field0092/0093/0094/0095`；填表人/部门/日期 `field0074/0075/0076` |
| OVERTIME | `formmain_0171` | MAIN | `INTERVAL` | 见明细 | 见明细 | 明细最早时间 `field0102`（仅上下文核验） | 填表人/部门/日期 `field0074/0075/0076`；系统差值 `field0104`（仅核验）；系统差值文本 `field0105` |
| OVERTIME | `formson_0172` | DETAIL | `INTERVAL` | `field0093` | `field0094` | 开始 `field0100`；结束 `field0099` | 序号 `field0092`；部门 `field0095`；类别 `field0096`（枚举未确认）；总时长 `field0101`（仅核验）；原因 `field0103` |
| OUTING | `formmain_0251` | MAIN | `INTERVAL` | 见明细 | 见明细 | 见明细 | 填表人/部门/日期 `field0083/0084/0085` |
| OUTING | `formson_0252` | DETAIL | `INTERVAL` | `field0127` | `field0131` | 开始 `field0132`；结束 `field0135` | 序号 `field0126`；部门 `field0130`；事由 `field0133` |
| EXEMPT_PUNCH | `formmain_0201` | MAIN | `DATE_RANGE` | 见明细 | 见明细 | 见明细 | 填表人/部门/日期 `field0083/0084/0085` |
| EXEMPT_PUNCH | `formson_0202` | DETAIL | `DATE_RANGE` | `field0127` | `field0131` | 开始日期 `field0132`；结束日期 `field0134` | 序号 `field0126`；岗位 `field0129`；部门 `field0130`；原因 `field0133` |
| PUNCH_CORRECTION | `formmain_0203` | MAIN | `POINT` | 见明细 | 见明细 | 见明细 | 填表人/部门/日期 `field0083/0084/0085` |
| PUNCH_CORRECTION | `formson_0204` | DETAIL | `POINT` | `field0127` | `field0131` | 补签时间 `field0132` | 序号 `field0126`；岗位 `field0129`；部门 `field0130`；原因 `field0133`；补卡类型 `field0134`（枚举未确认） |

## 4. 每类表单的生效门禁

| 表单 | 截图字段 | 实库结构 | 审批合同 | 主从 FK | 枚举合同 | 时区/边界 | 当前可生效 |
|---|---|---|---|---|---|---|---|
| TRIP | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_APPLICABLE` | `NOT_APPLICABLE` | 时区 `NOT_VERIFIED` | 否 |
| LEAVE | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_APPLICABLE` | `field0089 NOT_VERIFIED` | 时区 `NOT_VERIFIED` | 否 |
| OVERTIME | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `field0096 NOT_VERIFIED` | 时区 `NOT_VERIFIED` | 否 |
| OUTING | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_APPLICABLE` | 时区 `NOT_VERIFIED` | 否 |
| EXEMPT_PUNCH | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_APPLICABLE` | 日期范围边界 `NOT_VERIFIED` | 否 |
| PUNCH_CORRECTION | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `field0134 NOT_VERIFIED` | 时区 `NOT_VERIFIED` | 否 |

## 5. OA 实库联调签字矩阵

以下每一项均需可复核的脱敏证据。口头确认、截图推断、本地 fixture 或 H2 测试不能将状态改为 `VERIFIED`。

| ID | 待确认合同 | 当前状态 | 最低证据要求 | OA/DBA | HR 产品 | 后端 | QA/安全 | 日期/证据编号 |
|---|---|---|---|---|---|---|---|---|
| OA-SCHEMA-01 | 10 张物理表存在，且表名大小写与目录完全一致 | `NOT_VERIFIED` | 批准环境的只读 metadata；脱敏且带实例/库标识 |  |  |  |  |  |
| OA-SCHEMA-02 | 目录内各 `fieldNNNN` 存在，顺序、SQL 类型、可空性明确 | `NOT_VERIFIED` | 每表精确列清单与类型；与本文逐列 diff |  |  |  |  |  |
| OA-SCHEMA-03 | 主体选人字段实际保存单一 `org_member.id` | `NOT_VERIFIED` | 至少零/一/多选边界的脱敏样本及字段配置 |  |  |  |  |  |
| OA-MEMBER-01 | `org_member.id` 的精确 SQL 类型、唯一约束和索引 | `NOT_VERIFIED` | metadata、约束、代表性 explain |  |  |  |  |  |
| OA-MEMBER-02 | `org_member.code` 是目标人员编码且大小写/前导零具有业务意义 | `NOT_VERIFIED` | 脱敏样本、业务字典、唯一性和大小写校对规则 |  |  |  |  |  |
| OA-MEMBER-03 | 表单工号字段只作一致性核验，空值策略明确 | `NOT_VERIFIED` | 每类表单的空/一致/不一致脱敏样本与处置签字 |  |  |  |  |  |
| OA-FK-01 | `formmain_0171` 与 `formson_0172` 的真实 FK/关联列 | `NOT_VERIFIED` | metadata、DDL 或批准查询的 0/1/N 基数证明 |  |  |  |  |  |
| OA-FK-02 | `formmain_0251` 与 `formson_0252` 的真实 FK/关联列 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-FK-03 | `formmain_0201` 与 `formson_0202` 的真实 FK/关联列 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-FK-04 | `formmain_0203` 与 `formson_0204` 的真实 FK/关联列 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-FK-05 | 候选列名 `formmain_id` 是否真实、类型是否一致 | `NOT_VERIFIED` | 仅作调查提示；确认前不得进入代码或 SQL |  |  |  |  |  |
| OA-STATUS-01 | 系统审批状态列名、SQL 类型、全部原始值 | `NOT_VERIFIED` | 全量 distinct 值、数量、脱敏样本和 OA 流程配置 |  |  |  |  |  |
| OA-STATUS-02 | 哪些值精确代表最终批准，撤回/拒绝/终止/草稿语义 | `NOT_VERIFIED` | OA 产品/流程管理员签字的封闭映射及负例 |  |  |  |  |  |
| OA-STATUS-03 | 修改、补录、撤销、重复提交和乱序版本语义 | `NOT_VERIFIED` | 同一业务单据的版本链脱敏样本与排序规则 |  |  |  |  |  |
| OA-KEY-01 | 每类单据稳定业务主键、版本键和更新时间水位 | `NOT_VERIFIED` | 重放、修改、撤销、分页、水位边界样本 |  |  |  |  |  |
| OA-ENUM-01 | LEAVE `field0089` 完整原始值到请假类别的封闭映射 | `NOT_VERIFIED` | distinct 值、业务字典、未知值负例 |  |  |  |  |  |
| OA-ENUM-02 | OVERTIME `field0096` 完整原始值到加班类别的封闭映射 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-ENUM-03 | PUNCH_CORRECTION `field0134` 完整原始值到补卡类型的封闭映射 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-TIME-01 | OA DATETIME 的时区来源、夏令时和无时区值解释 | `NOT_VERIFIED` | OA/数据库配置、业务地点规则、边界样本 |  |  |  |  |  |
| OA-TIME-02 | EXEMPT_PUNCH 日期范围结束边界包含/不包含语义 | `NOT_VERIFIED` | 同日/跨日脱敏样本及业务签字 |  |  |  |  |  |
| OA-TIME-03 | TRIP/LEAVE/OVERTIME/OUTING 结束时间按半开区间转换的合同 | `NOT_VERIFIED` | 相邻区间与零/负区间负例 |  |  |  |  |  |
| OA-ACCESS-01 | OA 账户只读、最小表权限、无组织同步和无写回能力 | `NOT_VERIFIED` | GRANT/拒绝写入证明；不包含凭据值 |  |  |  |  |  |
| OA-SCOPE-01 | 公司/组织/人员的数据范围字段和服务端过滤规则 | `NOT_VERIFIED` | SQL 前置范围过滤、跨范围负例和计数不泄露证据 |  |  |  |  |  |
| OA-SAFE-01 | 固定投影、参数绑定、超时、分页上限、水位与只读事务 | `NOT_VERIFIED` | 代码审查、查询计划、超时/重试/游标负例 |  |  |  |  |  |
| OA-PRIVACY-01 | 地点、事由、备注、代理人等字段的最小化与审计策略 | `NOT_VERIFIED` | 字段分级、保留期、日志/响应脱敏证明 |  |  |  |  |  |

## 6. 未来适配器的静态查询约束

联调签字完成后，生产适配器仍必须满足：

- 每类表单使用代码内独立、固定的查询模板和固定投影，不使用 `SELECT *`。
- 表名、主从表、列名只能来自 `OaStaticFormMappingCatalog`；请求、配置文件、数据库行或用户输入不得提供标识符。
- 所有值条件必须使用参数绑定；任何 API 不得接收 SQL 或 SQL 片段。
- 主从关联列只有 OA-FK-01～04 对应项签字后才能写入代码；当前候选 `formmain_id` 不得使用。
- 审批列及值只有 OA-STATUS-01～03 签字后才能进入封闭映射；未知状态进入隔离且不生效。
- 枚举只接受签字后的封闭值集；未知值进入隔离且不降级为默认类别。
- 人员查找必须返回 0/1/N 基数并显式处理，不得 `LIMIT 1` 隐藏歧义。
- 数据范围必须在 SQL 查询、计数、排序和分页之前应用。

## 7. 当前自动化覆盖

当前纯单元测试覆盖：

- 六类表单、10 张物理表、全部截图字段的精确静态白名单；
- OA MySQL 默认关闭且不注册 `DataSource`，不影响主数据源自动配置；
- `org_member` 查询使用独立小连接池、服务端只读传播、固定投影和单一参数；
- `INTERVAL`、`DATE_RANGE`、`POINT` 形态；
- 严格单一数值主体 ID；
- `org_member.id -> code` 的零行、多行和 ID 不一致 fail closed；
- code 的前导零/大小写保留；
- 表单工号只做精确一致性核验；
- 未知列、字符串时间解析、非法区间拒绝；
- 审批、主从 FK、枚举、时区和日期边界 `NOT_VERIFIED` 门禁；
- 非白名单表名及 SQL 片段拒绝。

目标命令：

```text
cd backend
./mvnw -q \
  -Dtest=OaMysqlConfigurationTest,OaMysqlPropertiesTest,OaReadOnlyConnectionPoolTest,OaMysqlOrgMemberDirectoryAdapterTest,OaStaticFormMappingCatalogTest,OaFormRowTransformerTest,OaDocumentDataScopeSqlContractTest \
  test
```

该测试通过只表示静态骨架自洽，不表示 `OA_CONTRACT_STUB=PASS`，更不表示 `OA_LIVE=PASS`。
