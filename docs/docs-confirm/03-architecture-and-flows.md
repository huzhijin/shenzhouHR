# 总体架构、流程与状态机

## 1. 架构原则

- 首期为模块化单体：一个部署单元内以领域模块和公开端口隔离，事务不跨模块直接写表。
- 依赖单向：`Interface → Application → Domain ← Infrastructure`；外部适配器实现领域端口，外部 DTO 不进入领域模型。
- 查询已同步数据不依赖外部系统在线；同步、计算、月结和工资运行由持久化任务驱动。
- 同步写原始层和标准层、计算写结果层、人工动作写调整层、月结/发布写不可变快照；禁止反向覆盖。
- 敏感能力默认拒绝，鉴权在服务端应用服务入口、仓储查询范围、序列化字段、导出/附件/消息通道重复收口。

## 2. 系统上下文图

```mermaid
flowchart LR
  Employee[普通员工]
  Manager[部门负责人]
  HR[HR与考勤管理员]
  Payroll[薪资经办与复核发布]
  Admin[系统管理员与审计员]
  HRSystem[神州HR考勤与薪资核算系统]
  Seeyon[致远OA]
  Deli[得力E+云考勤]
  Excel[受控HR Excel]
  Map[经批准的地图服务]
  Notify[企业通知通道]

  Employee -->|自助查询、反馈、工资条| HRSystem
  Manager -->|授权范围团队查询| HRSystem
  HR -->|规则、同步、月结| HRSystem
  Payroll -->|采集、计算、复核、发布| HRSystem
  Admin -->|技术运维、审计查询| HRSystem
  Seeyon -->|标准接口与只读自建表| HRSystem
  Deli -->|官方API打卡数据| HRSystem
  Excel -->|受控导入| HRSystem
  HRSystem -->|经校验坐标、最小字段| Map
  HRSystem -->|无敏感金额的事件通知| Notify
```

信任边界：浏览器/移动端、外部系统、地图/通知、内部应用和数据存储分别独立；所有跨边界调用都需认证、超时、审计和字段最小化。

## 3. 领域模块图

```mermaid
flowchart TB
  Shell[接口与应用层]
  Auth[Authorization]
  Master[MasterData]
  Org[OrganizationVersioning]
  Bind[IdentityBinding]
  Integrate[IntegrationHub]
  Raw[RawData]
  Rule[RuleCatalog]
  Attend[Attendance]
  Close[AttendanceClosing]
  Payroll[Payroll]
  Report[Reporting]
  Audit[Audit]
  Ops[Operations]

  Shell --> Auth
  Shell --> Master
  Shell --> Attend
  Shell --> Payroll
  Shell --> Report
  Master --> Org
  Master --> Bind
  Integrate --> Raw
  Integrate --> Master
  Integrate --> Attend
  Attend --> Rule
  Attend --> Master
  Close --> Attend
  Payroll --> Close
  Payroll --> Master
  Payroll --> Rule
  Report --> Attend
  Report --> Payroll
  Auth --> Master
  Auth --> Org
  Shell --> Audit
  Integrate --> Ops
  Close --> Ops
  Payroll --> Ops
```

依赖说明：`Payroll` 只消费已冻结的考勤月结与主数据快照，不查询考勤可变表；`Reporting` 只能通过各模块只读公开模型；`Authorization` 使用组织投影和历史范围但不能改写主数据；`Audit` 由 Outbox 订阅关键事件并形成受保护链。

## 4. 组件架构图

```mermaid
flowchart LR
  subgraph Clients[客户端]
    PC[PC Web]
    Mobile[Mobile Web]
    Screen[Data Screen]
  end
  subgraph App[模块化单体]
    API[REST API与SSE网关]
    PEP[策略执行点]
    Cmd[命令应用服务]
    Query[范围化查询服务]
    Scheduler[任务调度器]
    Worker[持久化任务执行器]
    Domain[领域模块]
    Outbox[Transactional Outbox]
    Export[异步导出服务]
  end
  subgraph Adapters[外部适配器]
    SeeyonStd[SeeyonStandardAdapter]
    SeeyonCustom[SeeyonCustomTableReader]
    DeliAdapter[DeliAttendanceAdapter]
    MapAdapter[MapAdapter]
  end
  DB[(MySQL 8.4 LTS)]
  Object[(加密文件存储)]

  PC --> API
  Mobile --> API
  Screen --> API
  API --> PEP
  PEP --> Cmd
  PEP --> Query
  Cmd --> Domain
  Query --> Domain
  Scheduler --> Worker
  Worker --> Domain
  Worker --> SeeyonStd
  Worker --> SeeyonCustom
  Worker --> DeliAdapter
  Domain --> Outbox
  Domain --> DB
  Worker --> DB
  Export --> Object
  Query --> MapAdapter
```

事务边界：一个命令只修改一个领域聚合并同时写 Outbox；跨模块流程由持久化任务/领域事件推进。工资发布、月结关闭和组织正式版本创建使用数据库事务、乐观锁和唯一业务键；外部 API 不参与本地事务。

## 5. 部署架构图

```mermaid
flowchart TB
  User[企业终端]
  Nginx[Nginx / TLS / React静态资源]
  App[systemd / Spring Boot模块化单体]
  MySQL[(MySQL 8.4 LTS / InnoDB)]
  Store[(加密文件存储)]
  Secret[root-only环境文件或批准的密钥服务]
  Observe[OpenTelemetry / 指标日志追踪告警]
  Backup[binlog + 加密不可变备份]
  Seeyon[致远OA只读区]
  Deli[得力官方API]

  User --> Nginx
  Nginx -->|/api| App
  Nginx -->|静态资源| User
  App --> MySQL
  App --> Store
  Secret --> App
  App --> Observe
  MySQL --> Backup
  App --> Seeyon
  App --> Deli
```

首版部署在 Ubuntu Server 24.04 LTS：Nginx 托管 React 静态资源并把 `/api` 反向代理至仅监听回环地址的 Spring Boot 可执行 JAR，JAR 由 `systemd` 托管。调度、任务租约、幂等键和 Outbox 均持久化在 MySQL，不引入 Redis、消息队列、Docker 或 Kubernetes。开发、测试、预发布、生产使用独立数据库、文件存储、账号、密钥命名空间和日志索引；生产出站只允许批准域名/IP。前端静态资源不包含密钥；正式 Logo 从版本化本地资源发布，不远程热链。

## 6. 核心数据流图

```mermaid
flowchart LR
  OA[致远组织/人员/业务单据]
  DE[得力打卡]
  EX[HR Excel/系统维护]
  RAW[(原始层)]
  STD[(标准事实层)]
  VER[(主数据与规则版本)]
  CALC[(日考勤证据与结果)]
  ADJ[(人工调整)]
  CLOSE[(考勤月结快照)]
  PIN[(工资输入快照)]
  SEG[(PayrollSegment)]
  PUB[(已发布工资/工资条)]
  READ[授权查询/报表/大屏]

  OA --> RAW
  DE --> RAW
  EX --> PIN
  RAW --> STD
  STD --> CALC
  VER --> CALC
  CALC --> ADJ
  ADJ --> CLOSE
  CLOSE --> PIN
  VER --> PIN
  PIN --> SEG
  SEG --> PUB
  CALC --> READ
  CLOSE --> READ
  PUB --> READ
```

每条箭头均保留 `source_id/batch_id/input_snapshot_id/rule_version_id/correlation_id` 中适用字段。报表和大屏只读本地投影，外部故障不会阻断已同步数据查询。

## 7. 致远同步时序图

```mermaid
sequenceDiagram
  participant S as Scheduler
  participant J as SyncJob
  participant A as SeeyonStandardAdapter
  participant C as SeeyonCustomTableReader
  participant M as OAQueryMappingProfile
  participant D as LocalStore
  participant U as Audit

  S->>J: 创建带范围/水位/映射版本的任务
  J->>A: 拉取组织、人员、岗位标准对象
  A-->>J: 外部DTO和来源版本
  J->>C: 以只读事务查询自建表
  C-->>J: 原始行与查询批次
  J->>M: 按已发布映射版本转换逻辑字段
  alt 映射和身份校验通过
    M-->>J: 标准业务事实
    J->>D: 幂等写原始摘要、标准事实、同步指纹
    J->>U: 记录数量、水位、异常与关联ID
  else 字段缺失或CODE冲突
    M-->>J: 结构化错误
    J->>D: 隔离记录并保留原始摘要
    J->>U: 记录人工核验项，不推进失败范围水位
  end
```

安全约束：`SeeyonCustomTableReader` 使用独立只读账号、查询超时、允许表/视图清单和 SQL 模板签名；应用不提供任意 SQL 输入，不执行写语句、DDL 或存储过程。

## 8. 得力同步时序图

```mermaid
sequenceDiagram
  participant S as Scheduler
  participant J as SyncJob
  participant A as DeliAttendanceAdapter
  participant D as DeliOfficialAPI
  participant R as RawInbox
  participant N as Normalizer
  participant W as WatermarkStore

  S->>J: 读取上次成功next_id
  J->>A: query(next_id,page_size<=500)
  A->>D: POST JSON + App-Key + timestamp + MD5签名
  D-->>A: records,next_id或错误码
  alt 成功且有记录
    A-->>J: 原始响应与精确next_id
    J->>R: 按来源记录键幂等写入
    J->>N: 标准化人员、方式、位置状态
    N-->>J: 结果/冲突
    J->>W: 仅在整页提交成功后保存原样next_id
    J->>A: 查询下一页
  else 空页
    A-->>J: 当前批次完成
  else 超时/109/110/可重试错误
    A-->>J: 分类错误
    J->>J: 指数退避+抖动并尊重限流
    J-->>W: 不推进水位，登记补偿/人工重跑
  end
```

初始化接口只在首次建水位时受控执行；`next_id` 不做加一、不假设连续。禁止调用删除员工或会删除历史打卡的接口。

## 9. 日考勤计算时序图

```mermaid
sequenceDiagram
  participant Q as CalculationJob
  participant O as OrganizationSnapshot
  participant R as RuleCatalog
  participant F as AttendanceFacts
  participant E as EvidenceEngine
  participant C as DailyAttendance
  participant A as Audit

  Q->>O: 获取业务日有效任职与组织版本
  Q->>R: 获取个人>地区>总部的规则版本
  Q->>F: 获取标准打卡、OA单据、人工调整
  Q->>E: 构造冻结输入快照
  E->>E: 校验班段、重叠、48小时、补卡期限和提前返岗策略
  alt 关键输入完整
    E-->>C: 结果、异常、原因码、证据引用
    C->>C: 以employee+date+inputVersion幂等保存
    C->>A: 记录计算版本和差异
  else 关键输入缺失/身份冲突
    E-->>C: BLOCKED或NEEDS_REVIEW
    C->>A: 记录阻断原因，不生成正常结果
  end
```

## 10. 考勤月结状态机

```mermaid
stateDiagram-v2
  [*] --> OPEN
  OPEN --> CALCULATING: 发起计算并冻结输入水位
  CALCULATING --> READY_TO_CLOSE: 计算完成且无系统错误
  CALCULATING --> OPEN: 计算失败并回滚本次候选结果
  READY_TO_CLOSE --> CLOSED: 阻断项为0且二次确认
  READY_TO_CLOSE --> CALCULATING: 输入变化或请求重算
  CLOSED --> REOPENED: 授权重开并记录理由/影响范围
  REOPENED --> CALCULATING: 创建新版本重算
  CLOSED --> [*]
```

守卫条件：`CLOSED` 前必须锁定范围、规则版本、输入水位、覆盖人数和未决异常；重开不删除旧月结，生成新版本及逐员工差异。

## 11. 工资核算与发布状态机

```mermaid
stateDiagram-v2
  [*] --> OPEN
  OPEN --> COLLECTING: 开始采集
  COLLECTING --> INPUT_LOCKED: 校验通过并冻结快照
  INPUT_LOCKED --> CALCULATING: 创建PayrollRun
  CALCULATING --> REVIEWING: 分段和税费计算完成
  CALCULATING --> FAILED: 公式/输入/系统失败
  FAILED --> CALCULATING: 授权修复后新尝试
  REVIEWING --> READY_TO_PUBLISH: 独立复核通过
  REVIEWING --> INPUT_LOCKED: 驳回并生成新输入版本
  READY_TO_PUBLISH --> PUBLISHED: 发布者二次确认
  PUBLISHED --> CLOSED: 期间归档
  PUBLISHED --> ADJUSTING: 补发/补扣/冲销
  ADJUSTING --> REVIEWING: 新调整运行
  CLOSED --> [*]
```

发布守卫：运行输入快照、考勤月结、规则版本和分段均冻结；经办与复核/发布职责分离；发布前重新计算授权；`PUBLISHED` 数据不可 `UPDATE`，调整通过新 `PayrollRun`/记录体现。

## 12. 同步与异步边界

| 操作 | 执行方式 | 用户反馈 | 幂等/并发控制 |
|---|---|---|---|
| 列表、详情、权限预览、已同步报表 | 同步 | 目标 P95 见 NFR；返回数据版本和更新时间 | 范围化查询，不依赖外部在线 |
| 保存规则草稿、反馈提交、授权变更 | 同步事务 | 成功/失败及审计 ID | 乐观锁、请求幂等键 |
| 组织/打卡/OA 同步 | 异步任务 | 任务 ID、进度、部分成功、可重跑 | 业务唯一键、页提交、水位 CAS |
| 日计算、月结、工资计算/重算 | 异步任务 | 状态机、阻断项、差异 | 输入快照 ID + 运行版本唯一 |
| 批量导出 | 异步 | 进度、取消、一次性下载 | 授权快照 + 请求指纹 |
| 工资发布、月结关闭、重开 | 同步命令触发异步收尾 | 二次确认后返回运行 ID | 版本守卫、职责分离、不可变事件 |

## 13. 未来拆分触发条件

只有满足以下一项并经数据证明后才考虑拆服务：某模块需要独立扩缩容且持续占用总资源 40% 以上；发布节奏/合规隔离要求无法由模块与进程隔离满足；单模块故障持续影响核心 SLA；团队已具备独立 on-call 和数据所有权。优先候选是集成任务、异步导出或工资隔离区；拆分前必须通过 POC 验证分布式事务、审计链和权限上下文传递。
