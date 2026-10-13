# OA 考勤七类表单候选映射与联调签字矩阵

## 1. 当前结论

- 文档日期：2026-08-16
- 当前状态：`OA_LIVE=NOT_VERIFIED`
- 已有证据：用户提供的 OA 表单截图及字段转录；2026-08-14 业务决策补充请假 `formmain_0170.field0103`“系统计算小时”、销假 `formmain_0370.field0107`“系统计算返还小时”，并确认一张请假可以关联多张不重叠销假、累计返还不得超过原请假已扣减小时；2026-08-10 实库证据批次当前为 [`PARTIAL`](../verification/oa-live/2026-08-10/EVIDENCE.md)，原 19 项为 18 项 `REVIEWED`、1 项 `PARTIAL`，追加调查 `OA-B5-05D/05E` 也已 `REVIEWED`
- 仍未形成已签署运行合同：七类表的完整实库结构、系统审批状态业务语义、其余主从/原单关联、封闭业务枚举、OA 时间语义和版本/撤销语义；请假—销假业务规则已由用户明确，但物理唯一约束、审批查询和生产联表仍未验证/未接线
- 代码边界：当前代码已实现七类表单、11 张物理表、104 个候选业务字段（102 个截图转录字段加 2 个用户声明的系统小时字段）的编译期静态白名单和纯行转换，正式销假类型为 `LEAVE_REVOCATION`；V40 迁移已将 `formmain_0370` 和该类型加入本地元数据/运行合同门禁。可选只读 OA MySQL 连接池和固定参数的 `org_member.id -> code` 查询仍只是骨架；尚无七类业务表的生产查询/同步 caller、枚举实际转换和请假—销假生产关联/冲销链，也没有真实 OA 凭据或连接验收

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
| LEAVE | `formmain_0170` | MAIN | `INTERVAL` | `field0083` | `field0084`、`field0096` | 开始 `field0086`；结束 `field0087` | 流水号 `field0097`（与销假 `field0099` 严格相等的关联候选）；天数 `field0088`（仅核验）；系统计算小时 `field0103`（`SYSTEM_CALCULATED_HOURS`，实库列/精度/可写性未确认）；类别 `field0089`（代码内 `LEAVE_TYPE_ENUM`，实库枚举仍未确认）；备注/说明 `field0090/0091`；岗位/级别/代理人/所属部门 `field0092/0093/0094/0095`；填表人/部门/日期 `field0074/0075/0076` |
| LEAVE_REVOCATION | `formmain_0370` | MAIN | `INTERVAL` | `field0083` | `field0084`、`field0096` | 返还区间开始 `field0086`；返还区间结束 `field0087` | 流水号 `field0097`；填写人/部门/日期 `field0074/0075/0076`；销假类型 `field0100` 与请假类别 `field0089`（两者皆为代码内 `LEAVE_TYPE_ENUM`；用户声明与 LEAVE `field0089` 共用同一枚举，实库未验证）；原请假单 `field0098`（上下文）、原请假单流水号 `field0099`（与 LEAVE `field0097` 严格相等的关联候选）；岗位/级别/部门 `field0092/0093/0085`；共计天数 `field0088`（仅核验）；系统计算返还小时 `field0107`（`SYSTEM_CALCULATED_HOURS`，实库列/精度/可写性未确认）；备注/销假说明 `field0090/0091`；代理人/所属部门 `field0094/0095` |
| OVERTIME | `formmain_0171` | MAIN | `INTERVAL` | 见明细 | 见明细 | 明细最早时间 `field0102`（仅上下文核验） | 填表人/部门/日期 `field0074/0075/0076`；系统差值 `field0104`（仅核验）；系统差值文本 `field0105` |
| OVERTIME | `formson_0172` | DETAIL | `INTERVAL` | `field0093` | `field0094` | 开始 `field0100`；结束 `field0099` | 序号 `field0092`；部门 `field0095`；类别 `field0096`（枚举未确认）；总时长 `field0101`（仅核验）；原因 `field0103` |
| OUTING | `formmain_0251` | MAIN | `INTERVAL` | 见明细 | 见明细 | 见明细 | 填表人/部门/日期 `field0083/0084/0085` |
| OUTING | `formson_0252` | DETAIL | `INTERVAL` | `field0127` | `field0131` | 开始 `field0132`；结束 `field0135` | 序号 `field0126`；部门 `field0130`；事由 `field0133` |
| EXEMPT_PUNCH | `formmain_0201` | MAIN | `DATE_RANGE` | 见明细 | 见明细 | 见明细 | 填表人/部门/日期 `field0083/0084/0085` |
| EXEMPT_PUNCH | `formson_0202` | DETAIL | `DATE_RANGE` | `field0127` | `field0131` | 开始日期 `field0132`；结束日期 `field0134` | 序号 `field0126`；岗位 `field0129`；部门 `field0130`；原因 `field0133` |
| PUNCH_CORRECTION | `formmain_0203` | MAIN | `POINT` | 见明细 | 见明细 | 见明细 | 填表人/部门/日期 `field0083/0084/0085` |
| PUNCH_CORRECTION | `formson_0204` | DETAIL | `POINT` | `field0127` | `field0131` | 补签时间 `field0132` | 序号 `field0126`；岗位 `field0129`；部门 `field0130`；原因 `field0133`；补卡类型 `field0134`（枚举未确认） |

字段名只在所属物理表内解释：`formmain_0170.field0103` 是请假系统计算小时；`formson_0172.field0103` 仍是加班原因文本，不得跨表套用类型或语义。

## 4. 每类表单的生效门禁

| 表单 | 截图字段 | 实库结构 | 审批合同 | 主从 FK | 枚举合同 | 时区/边界 | 当前可生效 |
|---|---|---|---|---|---|---|---|
| TRIP | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_APPLICABLE` | `NOT_APPLICABLE` | 时区 `NOT_VERIFIED` | 否 |
| LEAVE | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_APPLICABLE` | `field0089 NOT_VERIFIED` | 时区 `NOT_VERIFIED` | 否 |
| LEAVE_REVOCATION | `SCREENSHOT_DECLARED`/代码白名单已固定 | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_APPLICABLE` | `field0089/field0100` 共用 LEAVE 枚举口径为 `USER_DECLARED`，代码定义为 `LEAVE_TYPE_ENUM`，三个字段的实库映射仍 `NOT_VERIFIED` | 时区、区间边界 `NOT_VERIFIED` | 否 |
| OVERTIME | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `field0096 NOT_VERIFIED` | 时区 `NOT_VERIFIED` | 否 |
| OUTING | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_APPLICABLE` | 时区 `NOT_VERIFIED` | 否 |
| EXEMPT_PUNCH | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_APPLICABLE` | 日期范围边界 `NOT_VERIFIED` | 否 |
| PUNCH_CORRECTION | `SCREENSHOT_DECLARED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `NOT_VERIFIED` | `field0134 NOT_VERIFIED` | 时区 `NOT_VERIFIED` | 否 |

## 5. OA 实库联调签字矩阵

以下每一项均需可复核的脱敏证据。口头确认、截图推断、本地 fixture 或 H2 测试不能将状态改为 `VERIFIED`。

| ID | 待确认合同 | 当前状态 | 最低证据要求 | OA/DBA | HR 产品 | 后端 | QA/安全 | 日期/证据编号 |
|---|---|---|---|---|---|---|---|---|
| OA-SCHEMA-01 | 11 张候选物理表存在，且表名大小写与目录完全一致 | `NOT_VERIFIED` | 批准环境的只读 metadata；脱敏且带实例/库标识；必须覆盖新增 `formmain_0370` |  |  |  |  |  |
| OA-SCHEMA-02 | 候选目录内各 `fieldNNNN` 存在，顺序、SQL 类型、可空性明确 | `NOT_VERIFIED` | 每表精确列清单与类型；`formmain_0370` 必须与截图转录的 21 个字段逐列 diff，并单独确认 `formmain_0170.field0097/field0103`、`formmain_0370.field0107`；两个小时字段还须验证为数值列、精度满足 0.5 小时且按流程配置可回填 |  |  |  |  |  |
| OA-SCHEMA-03 | 主体选人字段实际保存单一 `org_member.id` | `NOT_VERIFIED` | 至少零/一/多选边界的脱敏样本及字段配置 |  |  |  |  |  |
| OA-MEMBER-01 | `org_member.id` 的精确 SQL 类型、唯一约束和索引 | `NOT_VERIFIED` | metadata、约束、代表性 explain |  |  |  |  |  |
| OA-MEMBER-02 | `org_member.code` 是目标人员编码且大小写/前导零具有业务意义 | `NOT_VERIFIED` | 脱敏样本、业务字典、唯一性和大小写校对规则 |  |  |  |  |  |
| OA-MEMBER-03 | 表单工号字段只作一致性核验，空值策略明确 | `NOT_VERIFIED` | 每类表单的空/一致/不一致脱敏样本与处置签字 |  |  |  |  |  |
| OA-FK-01 | `formmain_0171` 与 `formson_0172` 的真实 FK/关联列 | `NOT_VERIFIED` | metadata、DDL 或批准查询的 0/1/N 基数证明 |  |  |  |  |  |
| OA-FK-02 | `formmain_0251` 与 `formson_0252` 的真实 FK/关联列 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-FK-03 | `formmain_0201` 与 `formson_0202` 的真实 FK/关联列 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-FK-04 | `formmain_0203` 与 `formson_0204` 的真实 FK/关联列 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-FK-05 | 候选列名 `formmain_id` 是否真实、类型是否一致 | `NOT_VERIFIED` | 仅可在批准的只读取证 SQL 中验证；确认前不得进入生产查询或运行时代码 |  |  |  |  |  |
| OA-STATUS-01 | 系统审批状态列名、SQL 类型、全部原始值 | `NOT_VERIFIED` | 全量 distinct 值、数量、脱敏样本和 OA 流程配置 |  |  |  |  |  |
| OA-STATUS-02 | 哪些值精确代表最终批准，撤回/拒绝/终止/草稿语义 | `VERBAL_CONFIRMED` | state=3 结束/批准；NULL 草稿；0 发起中；2 撤销 — 客户口头确认 2026-08-10；state=1（1条）未说明，代码 fail-closed | 待补 |  | 2026-08-10 |  | 客户口头确认 |
| OA-STATUS-03 | 修改、补录、撤销、重复提交和乱序版本语义 | `NOT_VERIFIED` | 同一业务单据的版本链脱敏样本与排序规则 |  |  |  |  |  |
| OA-KEY-01 | 每类单据稳定业务主键、版本键和更新时间水位 | `NOT_VERIFIED` | 重放、修改、撤销、分页、水位边界样本 |  |  |  |  |  |
| OA-REVOCATION-01 | 请假 `field0097` 与销假 `field0099` 严格相等；一张请假允许多张当前销假业务单，每张表达一个连续返还区间。已批准返还区间不得重叠，累计 `field0107` 不得超过原请假已扣减 `field0103`；不得取最后一张覆盖整单。明确未生效的销假不形成返还事实，关联/主体不一致或审批未知时失败关闭；婚育、丧假等缺少结构化事件/关系信息时由审批人判断，代码不猜测 | `USER_DECLARED` | 2026-08-14 用户明确的业务规则；待形成客户书面签字和多单、重叠、累计超额正/负验收样例 |  |  |  |  | 2026-08-14 用户明确 |
| OA-REVOCATION-02 | `formmain_0170.field0097/field0103` 与 `formmain_0370.field0099/field0107` 的物理列/类型/精确比较语义、审批过滤、当前版本归并及生产联表/累计返还 | `NOT_VERIFIED` | 两表 metadata；0/1/N 张脱敏样本；多张不重叠、端点相邻、重叠、累计等于/超过已扣减小时、未知/撤回/草稿状态负例；固定投影参数化 SQL、查询计划、代码和自动化证据 |  |  |  |  |  |
| OA-ENUM-01 | LEAVE `formmain_0170.field0089` 与 LEAVE_REVOCATION `formmain_0370.field0089/field0100` 共用同一套请假类别封闭映射；“三个字段共用”是 2026-08-13 用户业务声明，当前代码已将三者归为 `LEAVE_TYPE_ENUM` | `NOT_VERIFIED` | 三个字段各自的 distinct 原始值、`ctp_enum_item` 标签、集合差异、业务字典及未知值负例；截图、业务声明和编译期目录不能代替实库证据 |  |  |  |  |  |
| OA-ENUM-02 | OVERTIME `field0096` 完整原始值到加班类别的封闭映射 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-ENUM-03 | PUNCH_CORRECTION `field0134` 完整原始值到补卡类型的封闭映射 | `NOT_VERIFIED` | 同上 |  |  |  |  |  |
| OA-TIME-01 | OA DATETIME 的时区来源、夏令时和无时区值解释 | `NOT_VERIFIED` | OA/数据库配置、业务地点规则、边界样本 |  |  |  |  |  |
| OA-TIME-02 | EXEMPT_PUNCH 日期范围结束边界包含/不包含语义 | `NOT_VERIFIED` | 同日/跨日脱敏样本及业务签字 |  |  |  |  |  |
| OA-TIME-03 | TRIP/LEAVE/LEAVE_REVOCATION/OVERTIME/OUTING 结束时间按半开区间转换的合同 | `NOT_VERIFIED` | 相邻区间与零/负区间负例；销假需同时证明“实际请假”区间如何冲销原单 |  |  |  |  |  |
| OA-ACCESS-01 | OA 账户只读、最小表权限、无组织同步和无写回能力 | `NOT_VERIFIED` | GRANT/拒绝写入证明；不包含凭据值 |  |  |  |  |  |
| OA-SCOPE-01 | 公司/组织/人员的数据范围字段和服务端过滤规则 | `NOT_VERIFIED` | SQL 前置范围过滤、跨范围负例和计数不泄露证据 |  |  |  |  |  |
| OA-SAFE-01 | 固定投影、参数绑定、超时、分页上限、水位与只读事务 | `NOT_VERIFIED` | 代码审查、查询计划、超时/重试/游标负例 |  |  |  |  |  |
| OA-PRIVACY-01 | 地点、事由、备注、代理人等字段的最小化与审计策略 | `NOT_VERIFIED` | 字段分级、保留期、日志/响应脱敏证明 |  |  |  |  |  |

## 6. 未来适配器的静态查询约束

联调签字完成后，生产适配器仍必须满足：

- 每类表单使用代码内独立、固定的查询模板和固定投影，不使用 `SELECT *`。
- 请假—销假只能以 `formmain_0170.field0097` 与 `formmain_0370.field0099` 逐字节严格相等为关联候选，不 trim、不转大小写、不转数字；审批语义及 OA-REVOCATION-02 签字前不得进入生产查询。
- 同一请假的全部当前销假必须在同一一致水位完成版本归并；不能 `LIMIT 1`、按最后更新时间取一张或用最后一张覆盖原请假。已批准返还应建模为多个不重叠区间和逐单返还小时事实。
- 表名、主从表、列名只能来自 `OaStaticFormMappingCatalog`；请求、配置文件、数据库行或用户输入不得提供标识符。
- 所有值条件必须使用参数绑定；任何 API 不得接收 SQL 或 SQL 片段。
- 主从关联列只有 OA-FK-01～04 对应项签字后才能写入代码；当前候选 `formmain_id` 不得使用。
- 审批列及值只有 OA-STATUS-01～03 签字后才能进入封闭映射；未知状态进入隔离且不生效。
- 枚举只接受签字后的封闭值集；未知值进入隔离且不降级为默认类别。
- 人员查找必须返回 0/1/N 基数并显式处理，不得 `LIMIT 1` 隐藏歧义。
- 数据范围必须在 SQL 查询、计数、排序和分页之前应用。

## 7. 当前自动化覆盖

当前纯单元测试覆盖：

- 七类表单、11 张物理表、104 个候选字段的精确静态白名单，包含请假 `field0103` 系统小时和销假 `field0107` 系统返还小时；加班明细表同名 `field0103` 仍严格保持“原因”文本语义；
- `LEAVE_REVOCATION`/`formmain_0370` 的 22 字段白名单、区间转换、主体解析、两个工号精确复核和未验证合同 fail closed；
- 请假 `field0097` 与销假 `field0099` 的精确字符串关联、0/1/N 当前单、销假业务单 ID 非空且唯一、主体一致、审批未知、多张不重叠返还、累计等于/超过已扣减小时以及剩余有效请假片段的纯领域解析器覆盖；每条返还事实保留其销假业务单 ID，供原 eventId/摘要安全重放；该解析器不查 OA，不代表生产联表已接线；
- V40 本地数据库合同允许 11 张表和 `LEAVE_REVOCATION`，但不把 live schema、审批、枚举、时区或原单冲销语义改为 `VERIFIED`；
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
  -Dtest=OaMysqlConfigurationTest,OaMysqlPropertiesTest,OaReadOnlyConnectionPoolTest,OaMysqlOrgMemberDirectoryAdapterTest,OaStaticFormMappingCatalogTest,OaLeaveEffectiveIntervalResolverTest,OaFormRowTransformerTest,OaEnumShowValueCatalogTest,OaLeaveRevocationMigrationContractTest,Wave4SourceAdapterContractTest,OaDocumentDataScopeSqlContractTest \
  test
```

该测试通过只表示静态骨架自洽，不表示 `OA_CONTRACT_STUB=PASS`，更不表示 `OA_LIVE=PASS`。

## 8. 客户口述线索与待执行的解析查询（2026-08-08）

以下三条由客户在 2026-08-08 口述提供。**口述不是实库证据**，故第 5 节对应行保持
`NOT_VERIFIED`；本节只记录解析路径，使具备内网访问者可以直接执行确认。

| 线索 ID | 客户口述内容 | 对应签字行 | 当前状态 |
|---|---|---|---|
| LEAD-ENUM-01 | 枚举值不直接存中文，需按枚举 id 查 `ctp_enum_item`，再取 `showvalue` 字段判断 | OA-ENUM-01/02/03 | `LEAD_ONLY` |
| LEAD-STATUS-01 | 审批状态在 `col_summary`，按 `formmain_xxxx.id = col_summary.form_recordid` 关联；`state` 取值 3=结束（有效）、0=发起中、2=撤销、NULL=保存待发 | OA-STATUS-01/02 | `LEAD_ONLY` |
| LEAD-MEMBER-01 | 工号绑定为 `org_member.code` ↔ 得力 `employee_number`；OA 表单内存 `org_member.id`，须关联查询 | OA-MEMBER-02 | `LEAD_ONLY` |

加班类别按客户口述为三个封闭选项：`义务加班`、`加班费`、`调休`。
在 `showvalue` 的原始值到这三项的映射经签字前，未知值必须 fail closed，不得归入任一类别。

### 待执行的只读解析查询

连接参数记录在仓库外的 `.env.oa.local`（变量名 `OA_MYSQL_*`，值不入仓库）。
以下全部为 `SELECT`，OA 侧一律只读：

```sql
-- 1. OA-ENUM-02：加班类别 field0096 的全量原始值及中文标签
SELECT DISTINCT s.field0096 AS raw_value, e.showvalue AS label, COUNT(*) AS row_count
FROM formson_0172 s
LEFT JOIN ctp_enum_item e ON e.id = s.field0096
GROUP BY s.field0096, e.showvalue
ORDER BY row_count DESC;

-- 2. OA-ENUM-01：请假类别 field0089
SELECT DISTINCT m.field0089 AS raw_value, e.showvalue AS label, COUNT(*) AS row_count
FROM formmain_0170 m
LEFT JOIN ctp_enum_item e ON e.id = m.field0089
GROUP BY m.field0089, e.showvalue
ORDER BY row_count DESC;

-- 2a. OA-SCHEMA-02/OA-REVOCATION-02：先核对请假流水号列；不将字段存在视为关联合同已签署
SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'szoa'
  AND TABLE_NAME = 'formmain_0170'
  AND COLUMN_NAME = 'field0097';

-- 2b. OA-SCHEMA-01/02：必须先核对销假表和截图列；存在/类型不等于业务含义已签署
SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'szoa' AND TABLE_NAME = 'formmain_0370'
ORDER BY ORDINAL_POSITION;

-- 2c. OA-ENUM-01：销假单请假类别；仅在 2b 证明列存在后执行，并与请假单结果做集合差异核验
SELECT DISTINCT m.field0089 AS raw_value, e.showvalue AS label, COUNT(*) AS row_count
FROM formmain_0370 m
LEFT JOIN ctp_enum_item e ON e.id = m.field0089
GROUP BY m.field0089, e.showvalue
ORDER BY row_count DESC;

-- 2d. OA-ENUM-01：销假类型；同样仅在 2b 通过后执行，并与请假单 field0089 做集合差异核验
SELECT DISTINCT m.field0100 AS raw_value, e.showvalue AS label, COUNT(*) AS row_count
FROM formmain_0370 m
LEFT JOIN ctp_enum_item e ON e.id = m.field0100
GROUP BY m.field0100, e.showvalue
ORDER BY row_count DESC;

-- 2e. OA-REVOCATION-02：只统计原始关联基数；审批语义签署前不得把这些行视为有效销假
SELECT l.field0097 AS leave_serial,
       COUNT(r.id) AS linked_revocation_rows
FROM formmain_0170 l
LEFT JOIN formmain_0370 r
  ON BINARY r.field0099 = BINARY l.field0097
GROUP BY l.field0097
HAVING COUNT(r.id) > 1
ORDER BY linked_revocation_rows DESC;

-- 2f. OA-STATUS-01/02、OA-REVOCATION-02：销假原始审批状态分布；只在 id/form_recordid 物理关系取证后执行
SELECT c.state, COUNT(*) AS row_count
FROM formmain_0370 r
LEFT JOIN col_summary c ON c.form_recordid = r.id
GROUP BY c.state
ORDER BY row_count DESC;

-- 3. OA-STATUS-01：审批状态全量取值分布
SELECT c.state, COUNT(*) AS row_count
FROM formmain_0171 m
JOIN col_summary c ON c.form_recordid = m.id
GROUP BY c.state
ORDER BY row_count DESC;

-- 4. OA-FK-01：加班主从关联列的真实名称（先看列清单，禁止假设 formmain_id）
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'szoa' AND TABLE_NAME = 'formson_0172'
ORDER BY ORDINAL_POSITION;

-- 5. OA-FK-01 基数证明：确认候选列后代入 <fk_column>
SELECT s.<fk_column>, COUNT(*) AS detail_rows
FROM formson_0172 s
GROUP BY s.<fk_column>
ORDER BY detail_rows DESC
LIMIT 20;
```

### 本机可达性结论（2026-08-08）

从当前开发机对 `192.168.2.169:3308` 的 `nc -z` 探测返回 OPEN，但**该结果为假阳性**：
对同一主机确定关闭的 3999 端口探测同样返回 OPEN，说明链路上存在接受任意 TCP
连接的中间设备。MySQL 握手实际在 `reading initial communication packet` 阶段失败。
因此 OA 实库在当前网络位置不可达，上述查询必须在具备内网访问的环境执行。
