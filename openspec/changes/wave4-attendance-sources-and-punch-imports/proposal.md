## Why

WAVE-2 已建立本地员工、任职与组织权威，WAVE-3 已冻结考勤组、班次、日历、地点和基础策略，但系统仍没有可供后续考勤计算重放的来源事实。WAVE-4 必须把得力打卡、离线异构 Excel 与致远 OA 考勤单据收敛为同一条只追加、可追溯、可幂等重放的证据链，同时在真实计算和月结尚未进入 WAVE-5 前锁住来源边界。

## What Changes

- 新增来源实例、设备、人员绑定、同步任务和已提交水位；得力与 OA 仅通过端口及合成合同桩接入，失败页不推进水位，重试从最后已提交水位继续。
- 新增共享的 `raw fact → normalized record → employee/employment match → effective event/evidence link` 事实层；原始事实只追加，修改、撤销、补录与冲正均生成新事实或版本。
- 新增致远 OA 已通过考勤业务单据的只读规范化；未知状态、倒置时段、未匹配员工和同级互斥重叠进入隔离或 `EVIDENCE_CONFLICT`，不猜测、不覆盖。
- 新增版本化 `.xlsx` 打卡模板、mapping profile、上传/映射/预检/预览/错误报告/发布/受控部分发布/作废或冲正完整批次状态机。
- 新增文件哈希、来源记录 ID、稳定指纹、精确跨来源重复与 1～60 秒近似重复裁决；裁决前近似组产生 0 个 active event，裁决后按结论恰好产生 1 或 N 个，raw fact 永不删除。
- 上传、预检、发布、部分发布、作废/冲正、原文件、原始行、错误报告、重复裁决和重算请求分别执行 capability、地点/组织范围、record version 与期间状态校验。
- 开放期间只发布受影响员工/业务日期的重算请求；冻结/月结期间允许上传和预检但禁止发布。WAVE-4 只记录 durable recalculation intent，不实现 WAVE-5 日结果、异常或月结引擎。
- 新增数据来源与离线导入 React 页面，覆盖真实 API、状态机、分页、错误、403/404/409、frozen/partial/processing，以及 normal/demo 完全隔离。
- 新增 V7/V8 逻辑范围对应的前向 Flyway 迁移、MyBatis/事务/权限/审计、OpenAPI 双向闭合和真实 MySQL 8.4 验收。
- 新增得力/OA 合成契约夹具和明确的外部联调占位合同；契约桩通过不得声明真实供应商联调通过。

## Capabilities

### New Capabilities

- `attendance-source-ingestion`: 得力/OA 来源实例、设备/人员映射、同步任务、水位、重试、只读业务单据和外部契约桩。
- `attendance-evidence-ledger`: 三来源共享的只追加 raw/normalized/match/effective/evidence 模型、撤销、精确/近似重复、证据时段切分与重算意图。
- `attendance-punch-imports`: 版本化模板与 mapping、文件安全、预检/发布状态机、错误报告、部分发布、作废/冲正和批次追溯。
- `wave4-verification`: W1～W3 retained、OpenAPI、权限、MySQL 8.4、真实 UI、demo 隔离、PAYROLL 零发现和外部契约桩的独立验收门。

### Modified Capabilities

无。WAVE-2 人员/任职与 WAVE-3 考勤设置只作为解析输入，本 change 不修改其既有要求。

## Impact

- OpenAPI 在 `/api/v1` 下新增 `/attendance-sources/**`、`/attendance-source-jobs/**`、`/attendance-punch-imports/**`、`/attendance-events/**` 与重复裁决接口；现有 W1～W3 路径保持兼容。
- 新增来源/证据与 Excel 导入前向迁移；V1～当前 W3 迁移文件和 checksum 不得修改，实际迁移版本只能在实现时按已发布最高版本顺延。
- 后端新增 EvidenceIngestion/ImportManagement 边界、供应商端口与显式开发适配器；领域模型不依赖厂商 DTO、Excel 行对象或 Controller DTO。
- 前端新增 `/sources/online`、`/sources/oa`、`/sources/jobs`、`/sources/attendance-excel` 与 `/sources/attendance-excel/:batchId`，不注册组织持续同步或薪资入口。
- 文件存储、病毒扫描、真实得力/OA 凭据与脱敏样本仍是外部条件；内部合同和开发适配器可完成，但真实联调必须保持 `NOT_VERIFIED`，不得用 demo 或合成夹具冒充。
- 本 change 不实现 WAVE-5 正式日结果、异常处置、人工调整、重算执行、冻结/月结，也不实现假期、自助、报表或 PAYROLL。
