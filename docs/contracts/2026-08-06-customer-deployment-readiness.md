# 2026-08-06 客户部署与业务数据就绪度

## 1. 文档目的

本文用于客户环境部署、外部数据联调和上线范围决策。状态以 2026-08-06
当前工作树中的正式代码、迁移、运维手册和已记录验证为依据，不以演示数据、页面样例、
数据库中存在起步记录或单元测试通过代替生产验收。

状态定义：

- `READY`：在本文限定范围内，正式代码、运行链路和生产级验收证据均已闭环。
- `CONDITIONAL`：正式能力已存在，但必须完成客户数据、环境、权限或现场验收前置条件后才可启用。
- `NOT_READY`：仍缺少关键生产接口、适配器、自动任务、持久化链路或已签字业务合同，不能上线启用。

本文不记录任何数据库密码、管理员密码、得力密钥或 OA 凭据。实际值只能进入客户服务器的
根权限环境文件或密钥管理器。

## 2. 总体结论

当前没有任何证据支持“只连接数据库”“只填写得力 AppKey/Secret”或“只配置 OA JDBC”后，
首页、报表和年假就会自动显示。正式考勤链路必须依次完成：

```text
公司/组织/员工/任职
        +
地点/班次/日历/考勤组/已发布规则
        +
得力打卡、离线打卡、OA 单据等已验收证据
        ↓
人员匹配、标准化、去重和隔离
        ↓
每日确定性计算和异常状态持久化
        ↓
月结复核与不可变 PUBLISHED 投影发布
        ↓
首页工作台、九类报表和受控导出
```

正式链路说明见
[`docs/user-guide/09-上线数据准备.md`](../user-guide/09-%E4%B8%8A%E7%BA%BF%E6%95%B0%E6%8D%AE%E5%87%86%E5%A4%87.md)
和
[`docs/reporting/formal-projection-publication-wiring.md`](../reporting/formal-projection-publication-wiring.md)。

## 3. 就绪度总表

下表状态是“客户生产启用状态”，不是“仓库中是否存在部分代码”。

| 范围 | 状态 | 当前可用边界 | 上线前仍须完成 | 主要依据 |
| --- | --- | --- | --- | --- |
| 公司、组织、员工、任职及人员目录 | `CONDITIONAL` | 组织/员工期初导入、本地维护、当前任职、公司/部门树、员工直接所在部门和按组织子树查询均有正式接口与代码。当前本机批准数据曾核对为 4 家公司、156 个组织、615 名员工及 615 条当前任职。 | 客户必须签字确认公司、组织树、稳定工号、当前/历史任职及二次入职边界；在目标数据库重新导入、预检、发布并核对数量。客户已再次反馈人员树展示不符合预期，因此必须以最新构建做真实浏览器验收，不能仅凭自动化测试关闭。 | `frontend/src/features/employee/EmployeesPage.tsx`；`backend/src/main/resources/mappers/EmployeeReadMapper.xml`；`api/openapi.yaml`；仓库内补充记录 `docs/2026-08-05-attendance-ui-and-data-resolution.md`（客户包不附源码证据） |
| 本地账号登录及“登录误报” | `CONDITIONAL` | 后端具备独立账号密码、失败锁定、首次改密、会话和审计；前端当前代码区分网络不可达、服务端 5xx、非预期 4xx、账号锁定和 401 凭据失败。401 统一显示“用户名或密码错误”是防账号枚举设计；其他 4xx 不再伪装成密码错误。 | 将当前修正纳入最终交付构建，并在客户 Nginx、HTTPS/内网 HTTP、Secure Cookie、数据库账号和真实管理员上验证。若服务端或代理错误仍被显示为密码错误，必须按服务端日志和关联 ID 修复，不能让用户反复试密码触发锁定。 | `frontend/src/features/auth/LoginPage.tsx`；`backend/src/main/java/com/szsemicon/hr/identityaccess/application/AuthenticationService.java`；`backend/src/main/resources/application.yml`；仓库内验证记录 `docs/verification/wave1/runtime/WAVE1-RUNTIME-VERIFICATION.md`（客户包不附运行证据） |
| 考勤基础配置 | `CONDITIONAL` | 公司级地点、班次、工作日历、考勤组、人员归属具备版本化维护能力。共享地点、公司可用关系及特殊考勤组已有受控初始化脚本。 | 客户逐公司确认地点时区、冬夏令/跨日班次、年度日历、考勤组、人员归属和生效日；任何核算日都必须唯一解析到有效配置。客户库不得直接复制本机四公司数据。 | [`docs/user-guide/05-考勤初始化.md`](../user-guide/05-%E8%80%83%E5%8B%A4%E5%88%9D%E5%A7%8B%E5%8C%96.md)；[`docs/user-guide/08-部署前检查.md`](../user-guide/08-%E9%83%A8%E7%BD%B2%E5%89%8D%E6%A3%80%E6%9F%A5.md) |
| 考勤规则新增、编辑和发布 | `CONDITIONAL` | “用餐扣除、迟到宽限、每月迟到豁免”3 类规则已经接入页面、生命周期、解析和考勤组绑定。已发布版本不可原地编辑；需创建未来生效草稿、保存、校验、发布，再绑定考勤组。 | 操作人须有 `ATTENDANCE_SETUP:MANAGE_POLICY` 和目标公司范围，先显式选择公司；客户须签字确认参数。早退、缺卡与补签、打卡配对、加班认定、出勤率等其余 5 类目前只有数据库起步记录或不完整闭环，若纳入首期范围则整体改为 `NOT_READY`。 | `frontend/src/features/attendanceSetup/AttendancePolicyPage.tsx`；`backend/src/main/java/com/szsemicon/hr/attendance/application/AttendancePolicyLifecycleFacade.java`；仓库内补充记录 `docs/2026-08-05-attendance-ui-and-data-resolution.md`（客户包不附源码证据）；[`docs/user-guide/07-常见问题.md`](../user-guide/07-%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98.md) |
| 得力 E+ 在线打卡 | `NOT_READY` | 服务端已有 HTTPS 白名单、签名、游标分页、有界重试、整页事务、水位 CAS、来源注册和手工同步入口；默认开关为 `false`。 | 缺正式员工—得力人员绑定维护、租户初始化确认、设备/地点绑定、每日调度和告警、真实租户有效记录及端到端对账。即使 API 返回记录，未唯一匹配或目标期间不是 `OPEN/REOPENED` 仍会隔离或阻断。 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/deli/DeliEplusClient.java`；`backend/src/main/java/com/szsemicon/hr/evidenceingestion/application/DeliPunchSyncApplicationService.java`；[`docs/contracts/deli-runtime-authority-guards.md`](./deli-runtime-authority-guards.md)；[`docs/user-guide/10-外部数据接入与验收.md`](../user-guide/10-%E5%A4%96%E9%83%A8%E6%95%B0%E6%8D%AE%E6%8E%A5%E5%85%A5%E4%B8%8E%E9%AA%8C%E6%94%B6.md) |
| OA 六类考勤业务单据 | `NOT_READY` | 已有默认关闭的独立只读连接池、静态表/字段白名单、标准化合同，以及固定参数 SQL 的 `org_member.id -> code` 查询。 | 缺客户实库六类业务查询、审批/撤回映射、主从键、稳定水位、封闭枚举、时区/日期边界、同步任务和影子对账。只填写 JDBC 地址、账号和密码不会产生页面数据。 | `backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/oa/OaMysqlConfiguration.java`；`backend/src/main/java/com/szsemicon/hr/evidenceingestion/infrastructure/oa/OaMysqlOrgMemberDirectoryAdapter.java`；[`docs/contracts/oa-attendance-form-mapping-signoff-matrix.md`](./oa-attendance-form-mapping-signoff-matrix.md) |
| 离线/异构考勤机 Excel 打卡导入 | `NOT_READY` | 前端有流程展示；后端已有模板下载及批次、行、错误的只读查询，工作簿安全解析组件存在。 | 缺正式上传、预检、字段/设备人员映射、API/Excel 跨来源去重、确认发布、作废/冲正和写侧审计接口。不能作为得力/OA 未接通时的临时生产替代，也不能由 DBA 直接插表。 | `backend/src/main/java/com/szsemicon/hr/punchimport/interfaces/rest/PunchTemplateController.java`；`backend/src/main/java/com/szsemicon/hr/punchimport/interfaces/rest/PunchImportReadController.java`；仓库内设计记录 `docs/v1.9-rebaseline/03-attendance-punch-excel-import.md`（客户包不附源码证据）；[`docs/user-guide/10-外部数据接入与验收.md`](../user-guide/10-%E5%A4%96%E9%83%A8%E6%95%B0%E6%8D%AE%E6%8E%A5%E5%85%A5%E4%B8%8E%E9%AA%8C%E6%94%B6.md) |
| 年假/调休/认可加班期初余额 | `NOT_READY` | 领域中有年假算法、时间账户和 `OPENING` 台账类型；报表模型能够消费已发布的不可变时间账户事实。 | 没有可用的期初余额页面、REST 接口、Excel 预检/发布、正式账本持久化和月度 snapshot。客户从年中切换时必须提供期初余额；若确认为零，也必须书面签字，不能以“缺数据”代替零。禁止直接更新余额字段。 | `backend/src/main/java/com/szsemicon/hr/leavetimeaccount/domain/TimeAccountLedger.java`；`backend/src/main/java/com/szsemicon/hr/leavetimeaccount/domain/AnnualLeaveCalculator.java`；[`docs/user-guide/09-上线数据准备.md`](../user-guide/09-%E4%B8%8A%E7%BA%BF%E6%95%B0%E6%8D%AE%E5%87%86%E5%A4%87.md) |
| 每日自动计算、月结和正式投影发布 | `NOT_READY` | 确定性计算域、日事实投影器和受信任的进程内 `publish(PublishCommand)` 事务边界已经存在。 | 缺正式输入快照装配器实现、全月日结果存储/读取、异常 current-case 存储、OA 事实适配、时间账户 snapshot、闭月后触发和可运行的月结编排。数据库迁移中的 refresh 表和 capability 不等同于运行能力。 | `backend/src/main/java/com/szsemicon/hr/attendance/calculation/application/AttendanceCalculationPorts.java`；`backend/src/main/java/com/szsemicon/hr/reporting/application/AttendanceReportProjectionPublicationUseCase.java`；`backend/src/main/java/com/szsemicon/hr/reporting/application/AttendanceReportProjectionPublisher.java`；[`docs/reporting/formal-projection-publication-wiring.md`](../reporting/formal-projection-publication-wiring.md) |
| 首页工作台、九类报表和导出 | `NOT_READY` | 下游查询、权限过滤、九类报表计算、月矩阵及受控 XLSX 导出边界已存在。 | 正式页面只读授权公司/月的最新 `PUBLISHED` 投影；工作台还要求投影 `dataAsOf` 覆盖当天，个人工作台要求登录主体唯一绑定员工和 `SELF` 范围。自动投影链未闭环时，连库、员工数据或原始打卡都不足以显示。出勤率最终公式和真实 MySQL/大数据量验收也须关闭。 | `backend/src/main/java/com/szsemicon/hr/reporting/application/AttendanceReportQueryService.java`；`backend/src/main/java/com/szsemicon/hr/reporting/application/AttendanceDashboardService.java`；`backend/src/main/java/com/szsemicon/hr/reporting/domain/AttendanceReportCalculator.java`；[`docs/contracts/formal-attendance-report-formula-signoff.md`](./formal-attendance-report-formula-signoff.md) |
| 数据库迁移与宝塔部署 | `NOT_READY` | 当前工作树声明有连续 V1～V31 迁移链，并有打包、校验、安装、升级、健康检查和密钥不回显脚本；全新库与已有库路径被明确区分。 | 必须先统一 MySQL 权威基线：仓库根说明仍以 MySQL 8.4 LTS 为核心验证基线，而 `deploy/baota/README.md` 当前要求并强制 8.0.45。统一后需在同一最终提交和目标版本上完成全新安装、升级、备份恢复、Flyway validate、权限、浏览器和回滚演练。已有库禁止 `repair`、`clean` 或套用仅供 W3 本机基线的收口脚本。 | 仓库根 `README.md`（客户包不附工程说明）；[`deploy/baota/README.md`](../../deploy/baota/README.md)；[`docs/user-guide/08-部署前检查.md`](../user-guide/08-%E9%83%A8%E7%BD%B2%E5%89%8D%E6%A3%80%E6%9F%A5.md)；`backend/src/main/resources/db/migration` |

## 4. 明确不能采用的上线方式

以下做法均不能形成可验收的正式数据链：

1. **不能只连接神州 HR 数据库。** 页面不会扫描任意业务表自行推导考勤；报表和工作台只读取已发布正式投影。
2. **不能只填写得力 AppKey/Secret。** 还需要租户初始化、公司来源、人员唯一绑定、权威配置、开放期间、调度、隔离处置、日计算和投影发布。
3. **不能只配置 OA JDBC。** 当前没有六类客户业务表的正式查询和同步；连接成功只证明网络和凭据可用。
4. **不能用离线 Excel 页面绕过缺失链路。** 正式写侧尚未交付；前端流程或模板存在不代表可以发布数据。
5. **不能由 DBA 直接插入原始打卡、OA 单据、日结果、年假余额或报表投影。** 这样会绕过人员匹配、幂等、版本、权限、审计、冻结和冲正机制。
6. **不能把演示模式或合成数据作为生产证据。** 演示模式不请求正式后端，也不写客户数据库。
7. **不能用 Flyway `repair`、`clean` 或修改已执行迁移解决版本不一致。** 必须先备份、校验历史和选择批准的前向迁移或恢复方案。

## 5. 客户必须提供并签字的资料

### 5.1 基础人事、权限和考勤配置

- 每家公司全称、简称、稳定编码、启停状态和上线范围；
- 完整组织树、部门稳定编码、上级关系、生效日和停用边界；
- 员工稳定工号、姓名、状态、当前及历史任职、入离职、调岗和二次入职边界；
- 需要登录的管理员、HR、高管、部门负责人、员工本人和审计人员名单，以及精确公司/组织范围；
- 物理地点、时区、公司可用关系、班次工作段、休息/用餐、跨日归属和年度工作日历；
- 考勤组、人员归属及生效时间线；
- 迟到、早退、缺卡、补签、加班、请假、出勤率和周末/节假日餐扣制度及审批人。

### 5.2 得力资料

- 专用联调或正式租户、授权范围和凭据交付责任人；
- 租户是否已经完成官方一次性初始化的证明；
- 每家公司对应的来源实例、历史追溯起点和允许的同步频率/API 限额；
- 得力人员 `ext_id/user_id` 与内部稳定工号的完整映射；
- 正常、重复、跨日、迟到、缺卡、修正和无法匹配等脱敏打卡样本；
- 故障补数、隔离处置和业务对账责任人。

实际 AppKey/Secret 不进入本文、Git、聊天、截图或命令历史。

### 5.3 OA 资料

- 数据库产品/版本、网络白名单和专用最小只读账号；
- 六类表单主表/明细表的正式 DDL 或 metadata；
- 稳定业务主键、主从外键、创建/修改水位和撤回/版本链；
- 最终批准、拒绝、撤回、作废、补录和修改后的权威状态值；
- 请假、加班、补签等完整封闭枚举；
- `DATETIME` 权威时区、日期区间结束日是否包含；
- 人员选择字段与 `org_member` 的关系，以及跨任职、多人、空人员等脱敏样本；
- 客户 DBA、OA 负责人和 HR 的联合签字人。

### 5.4 离线打卡和期初余额

- 每种设备/厂商的脱敏原始文件、字段含义、时区、唯一记录 ID 或稳定重复键；
- 设备人员标识与内部工号映射、来源地点、发布责任人和冲正责任人；
- 年假、调休、认可加班期初余额，包含工号、账户类型、小时、基准日、到期日、公司/任职周期、政策版本和来源说明；
- 若全部账户从零起算，提供 HR 书面零余额确认。

### 5.5 部署与验收

- 目标服务器 OS、CPU/内存/磁盘、内外网模式、域名/IP、TLS 证书和 NTP/时区；
- 最终批准的 MySQL 精确版本、全新库或历史库类型、历史 `flyway_schema_history` 导出；
- 备份、binlog/PITR、RPO/RTO、监控告警、日志留存和故障联系人；
- 上线日、历史追溯起点、并行对账周期、抽样范围和 HR/IT/实施方验收人；
- 报表列顺序、出勤率公式、组织汇总公式、导出角色、用途、留存和传递要求。

上述报表项目应使用
[`2026-08-06 报表业务口径确认单`](./2026-08-06-reporting-business-confirmation.md)
逐项选择并附金标准样例，不能只回复“按系统默认”。

## 6. 上线阻断项

### 6.1 P0：未关闭不得声明生产上线

1. **部署基线冲突。** 统一 MySQL 8.4 LTS 与宝塔 8.0.45 的口径，并在选定版本重跑迁移、安装、升级和恢复证据。
2. **目标数据库未验收。** 全新库必须完整执行 V1～V31；历史库必须先备份和 `validate`，不得套用不匹配的 W3 本机收口脚本。
3. **真实管理员登录未验收。** 必须验证 Nginx `/api` 代理、HTTP/HTTPS 与 Secure Cookie、首次改密、锁定/解锁、退出和会话失效；登录 5xx/网络错误不得误报为密码错误。
4. **人员和权限基线未签字。** 公司、组织、员工、当前任职、账号角色和数据范围不一致时，不得接入正式考勤。
5. **考勤配置不完整。** 任一员工/日期不能唯一解析地点、班次、日历、考勤组和已发布规则时，不得计算。
6. **正式证据来源未闭环。** 首期若使用得力、OA 或离线文件，对应来源必须完成本文要求的适配、人员匹配、影子同步和负例验收；目前三者均不能作为完整生产入口。
7. **自动计算/投影未闭环。** 必须补齐正式输入快照、日结果持久化、异常状态、月结和发布编排，或交付经过审批、可重复、可审计的内部批处理。不得由浏览器或 DBA 提交事实。
8. **期初余额缺失。** 首期包含年假/调休时，必须交付受控期初台账导入和月快照；否则相关页面、年假报表和余额承诺必须从上线范围移除。
9. **业务公式未统一。** 出勤率最终公式、带薪假处理、零分母和组织汇总必须与迁移、计算器、报表公式版本及测试一致。
10. **首页/报表无正式投影。** 每个启用公司和月份必须存在最新 `PUBLISHED` 投影；工作台还须通过当天新鲜度、本人绑定及权限验收。
11. **最新人员树/规则页面未现场通过。** 客户已重新反馈人员树不符合目标样式，规则编辑仍有疑问；必须用最终构建和真实授权账号走完公司/部门筛选与规则草稿—校验—发布—绑定流程。

### 6.2 P1：可从首期范围移除，但启用对应功能前必须关闭

1. 补齐得力人员绑定、初始化确认、设备/地点绑定、每日调度、失败告警和补数管理页面。
2. 补齐离线打卡上传、预检、映射、发布、跨来源去重、冲正和原始行审计。
3. 补齐早退、缺卡与补签、打卡配对、加班认定、出勤率等其余规则的可维护运行闭环。
4. 补齐 OA 六类固定查询、水位同步、撤回链、隔离处置和签字证据。
5. 补齐时间账户正式账本、期初导入、月度 snapshot 和员工自助假期生产网关。
6. 完成九类报表、首页、导出在真实 MySQL 上的 36 个月容量、并发、5 万行同步/异步边界和浏览器验收。
7. 在客户代理环境验证非预期 4xx、5xx、断网和会话 Cookie 四类错误映射及服务端关联日志，确认安全文案不泄露账号存在性。
8. 完成监控、告警、备份恢复、切流和回滚演练，并留存不含敏感值的发布证据。

## 7. 登录误报的现场判定

登录页面的安全文案不能代替故障分类。现场应按以下顺序判断：

| 现象 | 应归类 | 现场检查 |
| --- | --- | --- |
| HTTP 401，错误码为 `INVALID_CREDENTIALS` | 凭据失败或故意不区分账号是否存在 | 核对账号状态、密码交付、键盘输入和失败次数；不要从响应推断账号是否存在。 |
| `ACCOUNT_LOCKED`/`LOCKED` | 账号锁定 | 由有权限管理员核对审计并解锁，不要继续重复登录。 |
| HTTP 状态 0、请求未到服务端 | 网络或浏览器连接失败 | 检查域名/IP、Nginx、证书、DNS、防火墙和浏览器控制台。 |
| HTTP 5xx | 登录服务或依赖不可用 | 检查后端健康、数据库、Nginx upstream 和服务端日志；前端应显示“登录服务暂时不可用”。 |
| 非预期 HTTP 4xx（不是凭据失败/锁定） | 请求被安全策略、校验或代理拒绝 | 页面应显示“登录请求未被接受”，检查 CSRF、请求格式、代理规则和公开错误码，不能让用户误以为密码错误。 |
| 登录 POST 成功但随后返回登录页 | 会话恢复失败 | 检查 `/api/v1/auth/session`、Cookie Domain/Path/SameSite/Secure、HTTP/HTTPS 模式和代理头。 |
| HTTP 403/404 出现在会话恢复 | 会话不可见、撤销或网关安全收敛 | 结合服务端关联 ID 和审计判断，不应直接认定用户密码错误。 |

关联 ID 只用于服务端诊断，不在员工界面公开内部错误详情。每次现场验收至少保留时间、环境、
HTTP 状态、公开错误码、服务端关联 ID 和处理结论，不记录密码或 Cookie 值。

## 8. 可批准的分阶段范围

- **基础 HR 管理试运行：** 只有在迁移、登录、公司/组织/员工/任职、权限及有限的 3 类规则现场验收通过后，才可将这些限定能力从 `CONDITIONAL` 转为 `READY`。
- **在线考勤试运行：** 当前为 `NOT_READY`。必须先关闭得力或其他正式打卡入口、OA（若纳入）、自动日计算和投影发布 P0。
- **年假与时间账户：** 当前为 `NOT_READY`。没有受控期初台账和月 snapshot 时不得上线。
- **首页、九类报表和导出：** 当前为 `NOT_READY`。只有下游页面代码存在或数据库连通，不构成启用条件。

任何状态提升都应在同一最终 Git 提交、同一发布包、同一客户环境和同一验收 run ID 下重新取证，
由客户 HR、IT/DBA 和实施负责人共同签字。

## 9. 前端依赖安全复核

本轮已将开发/构建链中的 `brace-expansion`、`postcss` 和 `undici` 更新到已修复版本，
并将 `react-router-dom`/`react-router` 更新到 7.18.2。当前 `npm audit --omit=dev`
仍把 [GHSA-qwww-vcr4-c8h2](https://github.com/advisories/GHSA-qwww-vcr4-c8h2)
分别计在 `react-router` 和 `react-router-dom` 两个包节点上。该公告只涉及 React Server
Components 模式下的服务端 Action；本项目是静态 Vite SPA，只使用浏览器路由，不运行 React
Router 的 RSC/服务端 Action 接口，因此当前交付路径不具备该漏洞触发面。

这不是“审计已清零”的声明。上线前必须二选一并记录审批：

1. 客户安全基线允许基于不可利用性做限期例外：明确禁止启用 RSC/服务端 Action，每次依赖升级
   重新执行审计，并在 2026-09-06 前复核或迁移；
2. 客户要求任何生产依赖 `high=0`：迁移至 React Router 8.3 或更高已修复版本，并在 Node
   22.22+、React 19.2.7+ 上完成全量路由、登录、权限和浏览器回归后再交付。

不得为了让审计数字好看而降级到 7.11.0；该版本在当前公告库中还命中普通链接/导航、SSR、
路由匹配和反序列化等多条漏洞，风险边界更差。
