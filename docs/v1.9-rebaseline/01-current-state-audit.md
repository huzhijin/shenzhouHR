# 神州 HR V1.9 当前状态审计

## 1. 审计结论

当前实现具备“组织/员工只读投影 + 权限数据范围 + 产品型 SPA”的可复用骨架，但与 V1.9 完整业务基线相比仍处于早期。后续应做增量纵向切片，不做整库重写。

| 维度 | 当前状态 | V1.9 判断 |
|---|---|---|
| 前端页面 | `/organization`、`/employees` | 保留并演进；其余 route 尚未实现 |
| 后端 API | 3 条只读 GET | 保留兼容；账号、导入、规则、考勤、假期等均需新增 |
| 数据库 | V1/V2 身份、组织、权限、审计骨架 | 不修改；从 V3 起增量扩展 |
| 认证 | dev header + 默认拒绝 | dev 适配器保留；新增正式本地账号密码与 session |
| 权限 | capability + 角色 + 数据范围 | 保留；扩展到动作、字段、状态和独立 PAYROLL 域 |
| 审计 | 有 `audit_event` 表，无完整写入服务 | 保留表；新增一致的业务审计写入链 |
| 外部集成 | 尚无得力/OA 业务接入 | 通过端口/适配器和开发契约桩新增 |
| 考勤/假期/时间账户 | 未实现 | 按规则版本、证据链、计算版本和不可变流水新增 |
| 薪资 | 仅 V1 可空 `payroll_plan_id` | 前端维持零可发现；后端仅在单独授权波次预留 |

## 2. 健康能力：必须复用

1. React 19、TypeScript strict、Vite、Ant Design 和唯一 Tabler Icons 图标体系。
2. AppShell、移动 Drawer、跳过导航、页面状态层和 Error Boundary。
3. 语义设计 Token、成对前景色、紧凑产品型布局。
4. same-origin API client、统一错误、关联 ID、敏感响应 `no-store`。
5. 服务端默认拒绝、capability、角色有效期和 SQL 数据范围。
6. 组织 identity/version/current projection/closure、来源绑定与任职有效期基础。
7. 外部精确 ID 的 string 保真约束。
8. MyBatis 分层、ArchUnit 依赖方向、Flyway、Actuator/Prometheus。
9. 明确标识的合成演示模式。
10. Nginx/systemd 部署和安全头模板。

## 3. 需要修正的现状

### 3.1 文档与契约漂移

- `README.md` 仍把 PRD V1.7 和 `docs/docs-confirm` 标为正式基线；在 V1.9 人工确认后应改为 V1.9。
- `api/openapi.yaml` 仍写“生产认证协议待客户 SSO 决策关闭后冻结”；V1.9 已确认本地账号密码。
- OpenAPI 声明 session cookie，但生产 session 实现不存在。
- `/employees` 的 400 错误未进 OpenAPI。
- `.umadev/adopt.json` 的技术栈和 API 数量错误，不作为现状证据。

### 3.2 持续组织同步残留

- `V2__baseline_authorization_catalog.sql` 和 `CapabilityCodes.java` 仍有 `MASTER_DATA:SYNC_PREVIEW`。
- V1 与 dev seed 的 `formal_sync_batch_id`、`projection_batch_id` 仍带旧语义。
- V1/V2 不得修改。后续通过 V3+ 增加期初导入/本地维护能力、停止授予旧 capability，并用兼容视图或弃用说明处理旧列。

### 3.3 本地可运行性

- Vite 没有 `/api` proxy，本地 `npm run dev` 与后端的真实连通性未证明。
- 后端测试绕过 Flyway并使用 H2 手写 schema；不能证明 MySQL 8.4。
- 仓库现存 `frontend/dist/`、`backend/target/`，均为生成物；不得修改或作为源码证据。

## 4. V1.9 明确不存在的实现

- 账号生命周期、密码哈希、首次改密、失败锁定、重置、session 撤销与退出。
- 组织/员工期初 Excel、批次预检、差异预览、发布、撤销边界和本地写维护。
- 二次入职任职周期与入职前累计工龄维护。
- 通用规则模板/版本/作用范围/试算/发布/回滚。
- 考勤组、班次版本、日历、餐扣、宽限、单边缺卡规则。
- 得力 API、OA 考勤业务单据、离线打卡 Excel 及统一事实层。
- 考勤计算、证据链、异常、补正、重算、月结、反月结。
- 假别、周年年假、时间账户与期初余额。
- PAYROLL 独立权限域和后端预留对象。
- V3+ 迁移与完整审计写入服务。

## 5. 当前测试覆盖与缺口

| 测试层 | 已有 | 关键缺口 |
|---|---|---|
| 前端单元/组件 | 会话、路由、API 错误、demo、精确 ID、安全配置 | 页面交互、导入向导、规则生命周期、冻结冲突、E2E |
| 后端单元/集成 | 安全状态、数据范围、组织树、分页、关联 ID、架构 | 本地认证、导入、规则、证据、计算、月结、时间账户、审计 |
| 数据库 | MyBatis XML 解析 | MySQL 8.4、Flyway migrate/validate、唯一约束和并发 |
| 契约 | 3 条端点人工对齐 | 全量 OpenAPI、状态机、错误码、幂等、前后端合同 |
| 浏览器 | 无 | 真实 route、响应式、键盘、焦点、像素与性能 |
| 外部集成 | 无 | 得力/OA 合同、重试、水位、撤销、跨来源重复 |

## 6. 首轮范围门禁

本轮允许写入的只有 V1.9 重基线与确认文档。以下均未获授权：

- `frontend/src/`、`backend/src/`、`api/openapi.yaml`、任何 Flyway 迁移；
- `backend/target/`、`frontend/dist/`、`node_modules/`、`.npm-cache/`、`.docx-qa/`；
- 生产数据库、致远、得力、部署、Git push、PR、真实人员或工资数据。

本审计不把任何未执行的构建、测试、联调或视觉检查写成“通过”。
