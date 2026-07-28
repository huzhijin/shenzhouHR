# 神州 HR V1.9 重基线证据清单

> 阶段：`docs / docs_confirm 待人工确认`  
> 审计日期：2026-07-24  
> 范围：只读审计与规格重基线；未修改前端、后端、数据库迁移、部署文件或 PRD V1.9 原文件。

## 1. 版本效力与运行边界

**V1.9 已取代 V1.7。** 当前任务、PRD V1.9、本轮新规格与决策依次优先；Open Design V1.9 只提供视觉、布局、交互与响应式参考；代码、OpenAPI、迁移和测试只描述现状；V1.7 资料与旧 UmaDev 状态仅作历史证据。

- `.umadev/tasks.json` 中旧任务 `t1` 状态为 `failed`；当前 V1.9 任务为独立的 `t3/running`。
- `.umadev/workflow-state.json` 当前为 `phase=docs`，尚无有效 `active_gate`；本轮只能推进到新的 `docs_confirm`。
- `.umadev/adopt.json` 仍记录 `stack=none`、`api_endpoints=0`，与实际仓库矛盾，不作为事实来源。
- 工作区没有可用 Git 元数据，不能用 Git 差异证明范围；本轮以受控文件清单和源码目录未写入校验为准。

## 2. 必读输入及核验结果

| 输入 | 实际核验 | 结论 |
|---|---|---|
| `AGENTS.md`、`README.md`、`UMADEV.md` | 已读 | `README.md` 仍声明 V1.7 为正式基线，是待后续文档修正项；`UMADEV.md` 已识别实际技术栈和 V1.9 边界 |
| `docs/神州HR考勤与薪资核算系统_PRD_V1.9.docx` | 已抽取 1498 行正文；已有 40 页最终渲染逐页检查；SHA-256 `6c8f5e9237612e953cd46fb6d170995fe94bf478c302f00be810df6f527616e9` | 内容和版式可读；Office `app.xml` 页数/字数元数据陈旧，不作为证据 |
| `docs/v1.9-rebaseline/03-attendance-punch-excel-import.md` | 已读 | 离线考勤 Excel 是“当前明确要求 + V1.9 补充规格”，补齐了 PRD V1.9 正文未完整覆盖的能力 |
| `outputs/attendance-import-template-v1/神州HR_考勤打卡导入模板_V1.0.xlsx` | 已检查 OOXML、工作表、字段、样式、验证、公式、宏和外链；SHA-256 `69e385e7dbf682ebaac1a4f50047d31f817c3a30af4b1cffc0f670cc8abdd054` | 6 个工作表、14 个打卡字段、9 个设备映射字段、4 行合成示例；无公式、宏或外链 |
| `docs/open-design-prompts-v1.9.md` | 已读 | 业务事实基本对齐，但事实优先级错误地把整理后的事实包置于 PRD V1.9 之前，本轮修正 |
| `design/DESIGN-MANIFEST.json`、`design/DESIGN-HANDOFF.md` | 已读 | 顶层 handoff 的 “production source of truth / pixel-first” 表述不得覆盖本轮事实优先级 |
| `design/open-design/v1.9/` | 已逐项读取 | 该路径是指向 `design/output/v1.9/` 的符号链接；18 个 HTML、设计系统、路由、状态、演示流及 QA 资料均可读 |
| `docs/docs-confirm/`、`docs/open-design-prompts.md` | 已全量扫描 | 均为 V1.7 历史资料；旧 `docs_confirm/PASSED` 不继承 |
| `docs/decisions/OPEN-DECISIONS.md` | 已读 | SSO 待定项已被 V1.9 明确裁决；外部环境、样本、坐标和存储条件仍是后续验证门 |
| `api/openapi.yaml` | 已读 | 仅 3 条只读 API；仍含 “SSO 决策待冻结”旧口径 |
| `frontend/package.json`、`frontend/src/`、前端测试 | 已审计 | 33 个源码文件；实际业务页面仅组织、员工 |
| `backend/pom.xml`、`backend/src/`、迁移、后端测试 | 已审计 | 57 个源码/资源文件；正式迁移仅 V1/V2；无 V3+ |
| `.umadev` | 只读检查 | 仅用于识别旧失败流程、当前 docs 阶段和治理风险，不续跑旧 V1.7 状态 |

## 3. 当前产品与工程证据

### 3.1 前端

- 技术栈：React `19.2.7`、TypeScript `5.9.3`、Vite `8.1.5`、Ant Design `6.5.1`、Tabler Icons `3.45.0`，见 `frontend/package.json`。
- 实际路由只有 `/organization`、`/employees`，见 `frontend/src/app/App.tsx`。
- 已有 AppShell、桌面侧栏、移动 Drawer、跳过导航、统一图标 `stroke=2`，见 `frontend/src/shared/components/AppShell.tsx`。
- 组织与员工页面具备 loading、empty、401/404、通用错误和重试；全局有 `AppErrorBoundary`。
- 真实请求为：
  - `GET /api/v1/me/capabilities`
  - `GET /api/v1/organization-units`
  - `GET /api/v1/employees`
- `apiClient.ts` 已处理 same-origin cookie、关联 ID、非法 JSON和网络错误；敏感响应的 `no-store` 证据来自后端响应策略、OpenAPI 与 Nginx，而不是客户端自行设置或校验。
- `frontend/vite.config.ts` 没有 `/api` 开发代理；README 所述本地开发链路尚未被实际连通性证明。

### 3.2 后端

- Java 21、Spring Boot 4.1.0、Spring Security、MyBatis 4.0.0、Flyway、MariaDB Connector、Actuator/Prometheus，见 `backend/pom.xml`。
- 模块按 interfaces → application → domain/infrastructure 分层，现有范围为 authorization、organization、employee、shared。
- 仅实现与 OpenAPI 对应的 3 个 GET 控制器。
- 已有服务端 capability 校验、角色有效期、法人/组织/后代数据范围、开发身份防冒用、统一错误响应、关联 ID、敏感响应 `no-store`。
- 正式生产账号密码/session 认证、审计写入服务及 V1.9 业务域均尚不存在。

### 3.3 数据库与迁移

- `V1__identity_organization_authorization_audit.sql`：法人、员工、组织身份/版本/当前投影/closure/来源绑定、任职、principal/role/capability/data scope、审计事件等 17 张基础表。
- `V2__baseline_authorization_catalog.sql`：基础角色与 `MASTER_DATA:READ`、`MASTER_DATA:SYNC_PREVIEW`、`AUDIT:READ`、`OPERATIONS:READ`。
- `db/dev/V1000__development_seed.sql` 仅为开发合成数据。
- V1/V2 不得修改；当前没有 V3+ 正式迁移。

### 3.4 API

- `api/openapi.yaml` 为 OpenAPI 3.1.0，版本 `0.1.0`，只覆盖 3 条只读端点。
- 契约声明 `SHENZHOUHR_SESSION` cookie，但后端还没有生产 session 认证实现。
- `/employees` 实现可返回 400 `INVALID_REQUEST`，OpenAPI 尚未声明 400。
- 外部精确 ID 使用 string，员工摘要不含工资、证件或精确位置，可保留。

### 3.5 测试

- 前端：9 个测试文件、22 个 `it(...)`，覆盖会话、路由白名单、API 错误、演示数据、精确 ID、CSP/Nginx 合同；没有浏览器 E2E。
- 后端：9 个测试类、22 个 `@Test`，覆盖 401/404、开发身份、数据范围、组织树、分页、能力、精确 ID、关联 ID、ArchUnit 和 MyBatis XML。
- 后端测试使用 H2 MySQL mode、关闭 Flyway并加载手写 test schema，因此没有证明 MySQL 8.4、V1/V2 migrate/validate 或测试 schema 一致性。
- 本轮未执行构建、lint 或测试；不能把历史 `target/`、`dist/` 当作当前通过证据。

### 3.6 演示模式

- `npm run demo` 使用 `MODE=demo`，完全短路后端。
- 14 个合成组织节点、24 名合成员工及真实分页；页面明确显示“演示环境 · 合成数据”。
- 演示模式是可保留的开发/展示能力，但不得作为真实接口、数据库、权限或外部系统联调证据。

### 3.7 部署模板

- Nginx 已定义 TLS、安全头、CSP nonce、`/api/` 反代和 SPA fallback。
- systemd 使用非 root、`NoNewPrivileges`、`ProtectSystem=strict` 等限制。
- 生产模板可保留；当前环境没有完成 Nginx、MySQL 8.4 或目标 Ubuntu 的真实验证。

## 4. Open Design V1.9 证据

- `route-to-artifact.json` 登记 36 条建议 route，对应 V19-OD-01～16；V19-OD-00 是项目 brief。
- V19-OD-00～16 实际页面全部存在。生产接入时必须把 demo 员工、策略、批次、事件和月份改为参数路由；`/qa/handoff` 不进入产品，`/display/attendance` 是独立只读面。
- 五条 demo flow 均为 `PROTOTYPE_SIMULATION`；真实认证、XLSX、外部接口、规则引擎、冻结、重算、月结、权限、性能、安全均为 `ENGINEERING_ASSUMPTION / NOT_VERIFIED`。
- 当前声明为 `STATIC_LAYOUT_VERIFIED / VISUAL_CAPTURE_NOT_AVAILABLE`；后续 `preview_confirm` 必须在真实 React 与真实 API 状态上完成浏览器多断点像素验收。

## 5. 结论

现有仓库是健康但很窄的首阶段组织/员工只读骨架，不是 V1.9 完整实现。V1.9 后续应增量复用现有架构、权限、审计表、关联 ID、AppShell、Token、演示模式和部署模板；不得依据旧 V1.7 文档或静态原型宣称功能已经实现。
