# ADR：WAVE-3 至上线验收的连续交付契约

- **状态**：Accepted
- **日期**：2026-07-26
- **决策范围**：WAVE-3、WAVE-4、考勤计算与月结、假期与时间账户、员工自助/报表/看板，以及最终上线验收
- **绑定契约**：`api/openapi.yaml`
- **机器可读基线**：`docs/verification/continued-delivery/baseline-index.json`

## 背景与当前事实

本 ADR 只锁定后续实现必须遵守的架构、接口、迁移和依赖，不宣告任何尚未验收的阶段完成。

1. WAVE-1、WAVE-2 作为已验收基线保留；后续每个阶段都必须回归。旧 `.umadev/plan.json` 或 workflow-state 中的 WAVE-2 断点不构成回滚或重建授权。
2. 当前 WAVE-3 OpenSpec `wave3-attendance-setup-and-policies` 为 `ready`，任务进度为 `0/45`。`w3-20260726-1709` 与 `w3-20260726-1917` 均已失效，不可作为晋级证据。
3. V1～V6 已存在并有历史校验值，必须保持字节级不变。V7 文件已存在，但已查证的持久数据库历史只到 V6；V7 在首次真实应用前仍须完成本 ADR 所列一致性修订。
4. 当前可见 MySQL 是 8.0.34；MySQL 8.4 LTS 的迁移、锁、索引、最小权限和恢复证据为 `NOT_VERIFIED`，不得由 H2、8.0 或模拟结果替代。
5. 本轮没有 PAYROLL 授权。该领域不分配接口、迁移、capability、前端路由或可发现入口。

## 决策

### 1. 需求与证据优先级

发生冲突时按以下顺序裁决：

1. 当前用户明确授权与边界；
2. `docs/docs-confirm-v1.9/`；
3. `design/open-design/v1.9/` 与 `docs/v1.9-rebaseline/` 的需求 ID、验收条件及可追溯矩阵；
4. `api/openapi.yaml`；
5. 当前 OpenSpec 变更；
6. 源码与未失效的运行证据。

源码、旧计划或历史证据与更高优先级来源冲突时，只能作为待修现状，不能反向降低需求。

### 2. OpenAPI 是前后端唯一绑定接口

`api/openapi.yaml` 的顶层 `paths` 只包含当前可调用接口。`x-delivery-contract` 锁定 WAVE-4 至员工自助的计划路径、method、请求/响应 schema 名、错误状态、数据模型、迁移和阶段依赖；扩展中的计划项不可调用，也不得提前生成前端入口或后端控制器。

计划接口晋升到 `paths` 必须同时满足：

- 前置阶段真实验收为 PASS；
- request/response schema 已加入 `components`，且前后端生成类型或显式 DTO 一致；
- Controller operation 与 OpenAPI operation 精确闭包，无缺失、无多余路由；
- 服务端 capability、法人/组织/对象 scope 负向测试通过；
- 状态变更的 CSRF、`Idempotency-Key`、`If-Match`/`ETag`、审计和 period status 测试通过；
- 401、403、404、409、412、422 等约定错误使用统一 `ApiError` envelope，并返回 `X-Correlation-ID`。

未知路由必须真实返回 404；前端不得用重定向掩盖。所有 ID 使用 string 传输，时间戳使用 RFC 3339，业务日期使用 ISO 日期，19 位以上外部 ID 不得数值化。

考勤组集合的绑定路径为：

| 层 | 路径 |
|---|---|
| OpenAPI `paths` | `/attendance-setup/groups` |
| `servers` 合成后的运行路径 | `/api/v1/attendance-setup/groups` |
| 前端 `basePath + /groups` | `/api/v1/attendance-setup/groups` |
| Spring `@RequestMapping + @GetMapping/@PostMapping` | `/api/v1/attendance-setup/groups` |

评审门禁提及的 `/api/attendance/groups` 缺少版本段与 `attendance-setup` 限界上下文段，明确标记为 `NOT_CALLABLE`，必须返回真实 404；不得为了满足字符串检查而注册一个没有正式需求依据的影子 Controller。该拒绝别名和 canonical 映射记录在 `x-delivery-contract.routeDecisions`，使门禁可机械检查而不伪造可调用接口。

### 3. 模块边界与依赖方向

每个业务域独立采用：

`Controller/DTO → Application Service/Use Case → Domain → Repository Port → Infrastructure Adapter`

非协商约束：

- Controller 只做协议映射和输入校验，不承载业务规则或事务编排。
- Application Service 的 public 方法对应单一用例，并作为事务边界。
- Domain 不依赖 Spring、MyBatis、HTTP DTO、Excel 或外部供应商 SDK。
- Repository Port 由领域或应用层定义；MyBatis、外部 HTTP、只读 OA、Excel 等适配器位于基础设施层。
- 跨域只通过已发布的应用接口或不可变事件/快照协作，不直接访问其他域的 Mapper。
- 权限在服务端用例入口和对象加载前校验；前端隐藏只改善体验，不构成授权。
- 原始来源事实、规则快照、组织快照、月结快照和账户流水只追加；更正通过新版本、冲正或失效记录表达。

模块依次为：

- `attendance-setup`：地点、考勤组与 assignment、班次/版本、日历/日覆盖、策略模板/版本/绑定和合成模拟；
- `attendance-source`：得力 E+、致远 OA、Excel 来源配置、凭据引用、同步批次、水位、raw evidence、normalized event 和人员绑定；
- `attendance-calculation`：有效事件、日结果、异常、调整、重算、差异和月结果；
- `period-close`：冻结、关闭、重开及组织/规则/事实/结果快照，并实现真实 month-close provider；
- `leave-time-account`：假别版本、申请/销假/补录、对账、账户和只追加流水；
- `self-service-reporting`：本人、主管、HR、导出和大屏的服务端投影与逐对象授权。

### 4. 阶段依赖 DAG

```text
WAVE-1/WAVE-2 accepted
        │
        ▼
WAVE-3 attendance setup
        │
        ▼
WAVE-4 source and offline import
        │
        ▼
attendance calculation
        │
        ▼
period close
        │
        ▼
leave and time account
        │
        ▼
self-service, reports and display
        │
        ▼
full release acceptance
```

任何阶段只能在直接前置阶段和 WAVE-1/WAVE-2 回归真实通过后晋级。阶段内部顺序固定为：契约/迁移 → QA 独立编写失败测试 → 后端 → 前端 → MySQL/API/浏览器运行验收 → 证据一致性；失败必须修复后重跑，不能把“已执行”当成 PASS。

### 5. 迁移顺序

| 版本 | 阶段 | 绑定内容 |
|---|---|---|
| V1～V6 | 已验收基线 | 不可修改，checksum 必须保持 |
| V7 | WAVE-3 | 考勤配置与四类基础策略存储 |
| V8 | WAVE-4 | 来源连接/映射版本、凭据引用、同步批次/水位、raw/normalized evidence、EmployeeSourceBinding |
| V9 | WAVE-4 | 离线打卡模板、导入批次/行、预检错误、发布/冲正审计 |
| V10 | 考勤计算 | effective event、规则/组织快照、日/月结果、异常、调整、重算和差异 |
| V11 | 月结 | period 状态、close/reopen operation 与不可变 close snapshot |
| V12 | 假期与时间账户 | 假别版本、申请/对账、账户、占用/释放/扣减/发放/失效/冲正流水 |
| V13 | 自助/报表/大屏 | feedback、导出任务/下载审计、大屏发布审计 |

禁止空迁移、复用版本或改写已落库迁移。若某阶段不需要物理变更，不创建迁移。PAYROLL 不预留迁移号；其未来版本只能在单独授权时确定。

### 6. WAVE-3 策略种类冲突的裁决

高优先级需求 `AC-ATTSET-03～05` 同时要求：

- `0 < late <= 15` 才可能消费自然月一次宽限，`late > 15` 不消费；
- 换考勤组不重置自然月上下文；
- 本阶段不持久化或暴露单侧缺卡策略；该能力留待后续明确授权。

当前 W3 OpenSpec/V7 将迟到拆为候选窗口与自然月豁免两个独立事实。裁决如下：

- 存储、解析器与运行 API 恰好使用三个 policy kind：`MEAL_DEDUCTION`、`LATE_GRACE`、`MONTHLY_LATE_EXEMPTION`；
- 不保留第二套 `ATTENDANCE_*` 兼容投影；
- 创建/启用考勤组时必须原子 provision 三类有效 binding；缺失、不唯一或互相冲突时整体回滚；
- OpenSpec、V7、后端 catalog/解析器、前端类型和测试 oracle 必须在 V7 首次落库前原子对齐；
- WAVE-3 合成模拟不得写正式 calculation result，也不得冒充 WAVE-5 计算。

单侧缺卡不属于本 W3 callable/storage 范围，不得在产品可发现面出现。

### 7. 各阶段绑定能力与需求追踪

| 阶段 | API/模型边界 | 绑定需求 ID |
|---|---|---|
| WAVE-3 | `paths` 下 `/attendance-setup/**`；八张主表、版本/生命周期、resolve/preview/simulation | `AC-POL-01～07`、`AC-ATTSET-01～05`、`AC-AUTH-11`、`AC-UI-01～07`、`AC-DB-04～13` |
| WAVE-4 | `/attendance-sources`、`/attendance-source-jobs`、`/employee-source-bindings`、`/oa-query-mapping-profiles`、`/attendance-punch-imports`、raw evidence | `AC-PUNCH-01～15`、`AC-SOURCE-01`、`AC-CALC-01～04`、`AC-AUTH-10～11` |
| 考勤计算 | `/attendance-daily-results`、`/attendance-exceptions`、`/attendance-recalculations`、`/attendance-monthly-results` | `AC-ATTSET-06～09`、`AC-CALC-01～07`、`AC-AUTH-11` |
| 月结 | `/attendance-periods/{periodId}/freeze|close|reopen`、close snapshot/provider | `AC-CLOSE-01～04`、`AC-AUTH-11` |
| 假期与时间账户 | `/leave-types`、`/leave-requests`、`/leave-reconciliations`、`/time-accounts`/ledger/replay | `AC-LEAVE-01～04`、`AC-ANNUAL-01～09`、`AC-TIME-01～04`、`AC-AUTH-11` |
| 自助/报表/看板 | `/me/**`、`/team/attendance`、`/attendance-reports`、`/attendance-report-exports`、`/attendance-dashboards`、`/display/attendance` | `AC-SELF-01`、`AC-FEEDBACK-01`、`AC-RANK-01～02`、`AC-POLICYCTR-01`、`AC-LOCATION-01`、`AC-DASH-01`、`AC-REPORT-01`、`AC-UI-01～07`、`AC-NFR-01～09` |

`api/openapi.yaml#/x-delivery-contract/stages` 是上述 path、method、schema 名和错误状态的机器可读版本。

### 8. 安全默认值

- 认证使用服务端 session cookie；生产/HTTPS 必须 `Secure`、`HttpOnly`、host-only、`SameSite=Lax`。CSRF token 不进入持久浏览器存储。
- 授权默认拒绝。每个对象读取/写入都校验 capability、法人、组织、地点、人员和历史快照 scope，防止 IDOR/BOLA。
- 创建和动作接口要求 `Idempotency-Key`；可变资源要求 `If-Match`/`ETag`；冲突返回 409 或 412，不做最后写入覆盖。
- 状态变更写包含 actor、request/correlation ID、原因、before/after digest 和结果的审计。失败审计不能被业务事务回滚吞掉。
- 得力/OA 连接只保存仓库外 secret reference；日志、报告、截图、命令参数和数据库不得出现明文凭据。OA 只读语句使用解析后的 allowlist，禁止 DDL/DML、多语句和危险函数。
- raw payload、原始文件和哈希不可变；撤销/补录/作废只追加新事实或冲正。
- Excel 上传在读取前验证扩展名、MIME/signature、20MB/50,000 行限制、宏、外链、公式和压缩炸弹；错误报告与原文件分别鉴权和审计。
- 月结 provider 未实现时 fail-closed：当前或过去生效的策略发布拒绝，只允许安全的未来版本。V11 provider 上线后仍以 period 状态作为服务端硬门禁。
- demo 模式不得访问 API 或改变 MySQL；普通模式不得读取 demo 数据。
- 没有真实外部租户、凭据、MySQL 8.4 或生产恢复环境时，对应外部项只能标记 `NOT_VERIFIED`，其余本地契约、合成服务器、适配器、MySQL/API/浏览器和安全测试继续执行。

### 9. PAYROLL 排除

本轮 PAYROLL 为显式 out-of-scope：

- 前端菜单、路由、首页卡片、搜索、通知、导出、员工入口和埋点可发现性必须为零；
- `api/openapi.yaml` 不分配可调用或计划接口；
- V7～V13 不包含工资、银行、税社保、工资条或薪资结果表；
- 通用 DTO、看板、报表和导出字段白名单不得携带薪资数据；
- 不创建 PAYROLL capability 或给既有角色隐式授权；
- `AC-AUTH-08` 与 `AC-PAY-01～04` 只作为持续排除门禁，不能被解释为本轮实现授权。

### 10. 证据与阶段晋级

每次验证生成唯一 run ID，证据至少包含命令、退出码、测试数量、源文件/迁移 checksum、服务版本、服务地址、浏览器视口、截图哈希、权限负向结果和 `NOT_VERIFIED` 条件。报告必须引用同一源代码状态；源码变化后旧 run 不得继续作为最终证据。

阶段晋级条件是“全部可在当前环境完成的验收真实 PASS，外部条件逐项诚实标记，且无高危未解决缺陷”。工具超时、自动流程停止、文件存在或任务被勾选均不是 PASS。

## 后果

- 前后端可以围绕同一 OpenAPI contract 分阶段实现，不会因提前暴露未来路由破坏 WAVE-3 Controller/OpenAPI 闭包。
- V7 首次落库前必须处理四类策略一致性；此工作属于后续 WAVE-3 实现步骤，不在本 ADR 步骤内修改。
- WAVE-4 至员工自助的接口名与迁移号已保留，但在满足依赖并晋升到 `paths` 前均不可调用。
- 缺少 MySQL 8.4、得力/OA真实租户或生产恢复环境不会阻断可完成的本地工作，也不会被伪造为已验证。
