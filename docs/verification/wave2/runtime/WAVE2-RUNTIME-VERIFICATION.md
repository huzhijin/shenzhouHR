# 神州 HR WAVE-2 真实运行验收报告

- 验收日期：2026-07-26
- 运行证据 ID：`ms17xm8i0bb61873`
- 项目根目录：`/Users/huzhijin/Downloads/shenzhouHR`
- 验收边界：仅 WAVE-2 组织、员工、任职、入职前累计工龄与期初导入；未开始 WAVE-3/WAVE-4
- 运行时验收结论：AC-PEOPLE-01～12 均有真实 API、MySQL、自动化或浏览器证据，结论为 `PASS`
- 静态最终口径：最终重跑后端 87/87、定向 OpenAPI 8/8、前端 16 个测试文件 126/126，production/demo 构建与治理扫描均通过；[`static-verification.log`](static-verification.log) 以 `WAVE2_STATIC_VERIFICATION=PASS` 结束
- 交付 gate：本报告、[`ac-people-01-12.json`](ac-people-01-12.json)、[`open-design-manifest.json`](open-design-manifest.json) 和截图已由 `scripts/qa/verify-wave2.sh evidence` 完成结构、对象绑定与文件完整性校验，结果为 `WAVE2_DELIVERY_EVIDENCE=PASS`

## 证据分层

本报告严格区分以下三类证据，避免把客户端状态模拟写成真实后端行为：

| 层级 | 含义 | 可证明内容 | 不能证明内容 |
| --- | --- | --- | --- |
| `API_REAL` | 当前源码构建的 8080 后端、真实 `shenzhou_hr_dev` MySQL 和真实 HTTP 请求 | 状态码、错误码、版本、幂等、导入、回滚、审计和数据库写入结果 | 浏览器布局、焦点或视觉呈现 |
| `BROWSER_REAL` | 5173 的真实 React 页面调用真实 `/api`，数据来自 8080 和 MySQL | 路由、真实数据呈现、六视口、控制台、键盘、normal 请求链、真实成功/阻断状态 | 未实际导航到的错误页面 |
| `BROWSER_FAULT_INJECTION` | 真实 React 页面中仅对指定请求做延迟、空结果或网络失败注入 | loading、partial-loading、empty、network-error 等客户端状态呈现 | 对应响应曾由真实后端产生，或 MySQL 发生了相同业务结果 |

完整状态清单及每项层级见 [`state-coverage.json`](state-coverage.json)。

## 实际服务与真实链路

- 后端：`http://127.0.0.1:8080`
- 普通前端：`http://127.0.0.1:5173`
- 隔离 demo 前端：`http://127.0.0.1:5174`
- 运行数据库：`shenzhou_hr_dev`
- 健康检查：`GET /actuator/health` 返回 200
- 普通模式：共捕获 923 个浏览器请求，其中 132 个为 `Fetch/XHR` 业务 `/api` 请求，均来自真实 React 页面；完整网络证据见 [`browser-normal-network.json`](browser-normal-network.json)
- 普通模式控制台错误与页面错误：均为 0；见 [`browser-normal-console.json`](browser-normal-console.json) 和 [`browser-normal-errors.json`](browser-normal-errors.json)

业务 API 的主证据为：

- [`runtime-api.tsv`](runtime-api.tsv)：健康、登录、会话、能力、模板、批次、组织和员工基本链路
- [`runtime-business-api.tsv`](runtime-business-api.tsv)：鉴权负向、组织/员工版本、任职、工龄、导入、幂等、作废、回滚、五分类和审计链路
- [`runtime-api-business.json`](runtime-api-business.json)：本轮运行时事实汇总
- [`runtime-context.json`](runtime-context.json)：浏览器复核所使用的本轮对象上下文，不含凭据

## MySQL、Flyway 与最小权限

- MySQL 版本：8.0.34
- `shenzhou_hr_dev` 和 `shenzhou_hr_test` 均为 Flyway v6，共 6 个迁移
- dev 前向迁移、空 test 从 V1 到最新、test 从 V1～V4 到最新均通过
- 两库 `validate` 通过，第二次 `migrate` 均为 no-op
- V1～V4 的 SHA-256 保持不变
- 每库 WAVE-2 schema 契约：10 张 WAVE-2 表、26 个索引、28 个外键、13 个 CHECK
- 旧组织同步 capability 行数：dev 0、test 0
- 应用账号 CRUD 正向验证通过；DDL、`GRANT` 和跨 schema 访问均按预期被拒绝
- 最终数据库状态：dev、test 均保留

证据：

- [`mysql/wave2-local-mysql.log`](mysql/wave2-local-mysql.log)
- [`mysql/verify-wave2-mysql.log`](mysql/verify-wave2-mysql.log)
- [`mysql/flyway-history.tsv`](mysql/flyway-history.tsv)
- [`mysql/tables.tsv`](mysql/tables.tsv)
- [`mysql/indexes.tsv`](mysql/indexes.tsv)
- [`mysql/foreign-keys.tsv`](mysql/foreign-keys.tsv)
- [`mysql/constraints.tsv`](mysql/constraints.tsv)

## AC-PEOPLE-01～12

机器可读结论与事实见 [`ac-people-01-12.json`](ac-people-01-12.json)。以下为人工复核摘要。

| AC | 结论 | 真实证据摘要 |
| --- | --- | --- |
| AC-PEOPLE-01 | PASS | 4 类版本化模板可发现；组织/员工模板均真实下载；预检前后 6 张正式表计数完全相同，正式表写入增量为 0 |
| AC-PEOPLE-02 | PASS | 预检产生 ADDED、UPDATED、UNCHANGED、CONFLICT、ERROR 各 1 条；2 条阻断问题；错误报告 4007 bytes；发布返回 409 |
| AC-PEOPLE-03 | PASS | 首次发布非去重；同幂等键重放命中原发布；同文件 hash 的新批次同样命中原发布，未重复生成正式记录 |
| AC-PEOPLE-04 | PASS | Excel 发布组织先保留 `INITIAL_EXCEL`/来源批次，随后本地修改建立 `LOCAL` 权威并形成第 2 版；组织同步 route、button、job 发现性为 0 |
| AC-PEOPLE-05 | PASS | 导入后组织与员工的本地维护均新增版本和审计；前后重读确认来源文件 hash 与发布 snapshot digest 保持不变 |
| AC-PEOPLE-06 | PASS | 首次任职和二次入职具有两个不同周期 ID，历史周期仍保留 |
| AC-PEOPLE-07 | PASS | 重叠任职返回 409；离职空档时点返回 0 条任职，不猜测归属 |
| AC-PEOPLE-08 | PASS | 累计工龄为 365 天、1 条记录；原因、操作者、时间和请求标识均留存；重复重算 digest 稳定 |
| AC-PEOPLE-09 | PASS | 4 个相同显示名员工可以并存；多人候选数为 2，并进入错误报告 |
| AC-PEOPLE-10 | PASS | 旧同步 capability 在两库均为 0；受限 AUDITOR 调用导入 API 返回 403；同步发现性为 0 |
| AC-PEOPLE-11 | PASS | 草稿作废后仍可读；已发布批次 DELETE 返回 405；满足条件的组织撤销恢复 1 个版本；有后续版本的员工撤销返回 409 |
| AC-PEOPLE-12 | PASS | 任职使用 `[start_date,end_exclusive)`；业务离职日匹配 1 条，次日匹配 0 条 |

## 状态覆盖

### 真实 API 状态

- 401：未认证访问 people API 返回 `AUTHENTICATION_REQUIRED`
- 403：CSRF 返回 `CSRF_VALIDATION_FAILED`；受限角色返回 `ACCESS_DENIED`
- 404：未知员工和跨 scope 请求返回 `RESOURCE_NOT_AVAILABLE`
- 409：版本过期、任职重叠、阻断发布和不允许回滚均返回对应稳定错误码
- processing：真实预检请求返回 202；另以窄范围延迟注入验证 React processing 对话框、`aria-busy` 和响应式布局，不声称自然慢请求曾显示该对话框
- partial-success / frozen：五分类中有效行和阻断行并存，阻断发布返回 409
- success：组织/员工版本、本地维护、任职、工龄、发布、作废和满足条件的撤销均成功

404 的结论仅为 `API_REAL`。一次浏览器尝试只重定向到默认路由，没有形成可用的 WAVE-2 404 页面证据，该无效产物已移除，因此本报告不声称 404 UI 已被真实浏览器验证。

### 真实浏览器状态

- normal：四个生产路由均由真实 API 数据驱动
- 401 会话回退：无会话访问受保护路由后到达真实登录页，见 [`browser-unauth-state.json`](browser-unauth-state.json) 和 [`screenshots/12-unauthenticated-login-1440x900.png`](screenshots/12-unauthenticated-login-1440x900.png)
- 预检阻断与 partial-success：真实五分类批次，见 [`screenshots/07-validation-failed-1440x900.png`](screenshots/07-validation-failed-1440x900.png)
- success：真实已发布批次在浏览器显示为已发布，见 [`browser-published-state.json`](browser-published-state.json) 和 [`screenshots/13-published-success-1440x900.png`](screenshots/13-published-success-1440x900.png)
- 幂等命中仅由 `API_REAL` 证明；浏览器截图不承担幂等断言
- 本地权威切换、员工版本、二次入职、累计工龄和审计事实由真实 API trace 证明；组织与员工详情截图只证明对应真实页面呈现

### 浏览器故障注入状态

以下只验证真实 React 的状态呈现，不用于证明真实后端返回了该响应：

- loading：[`browser-loading-state.json`](browser-loading-state.json)
- partial-loading：[`browser-partial-loading-state.json`](browser-partial-loading-state.json)
- empty：[`browser-empty-state.json`](browser-empty-state.json)
- network-error / error surface：[`browser-network-error-state.json`](browser-network-error-state.json)
- processing：[`browser-processing-state.json`](browser-processing-state.json) 和 [`screenshots/16-processing-import-1440x900.png`](screenshots/16-processing-import-1440x900.png)
- 故障注入会话控制台错误和页面错误均为 0：[`browser-fault-console.json`](browser-fault-console.json)、[`browser-fault-errors.json`](browser-fault-errors.json)

## 六视口与响应式

六个要求视口全部使用真实 React 页面完成：

| 视口 | 路由 | 页面级横向溢出 | 触控目标违规 | 无 label 控件 |
| --- | --- | ---: | ---: | ---: |
| 390×844 | `/people/import` | 0 | 0 | 0 |
| 768×1024 | `/people/organization` | 0 | 0 | 0 |
| 1024×768 | `/people/employees` | 0 | 0 | 0 |
| 1366×768 | `/people/employees/:employeeId` | 0 | 0 | 0 |
| 1440×900 | `/people/import` | 0 | 0 | 0 |
| 1920×1080 | `/people/employees/:employeeId` | 0 | 0 | 0 |

机器证据见 [`browser-matrix.json`](browser-matrix.json)。

闭合控件之外还单独展开验证了移动端员工卡片链接、Select 选项、移动导航菜单、历史 Drawer 和 DatePicker 日期格，所有目标均达到 44px；Drawer 宽度为 390px，日期弹层也保持在 390px 视口内，均无页面级横向溢出。证据见 [`touch-select-card.json`](touch-select-card.json)、[`touch-mobile-menu.json`](touch-mobile-menu.json)、[`touch-history-drawer.json`](touch-history-drawer.json) 与 [`touch-date-picker.json`](touch-date-picker.json)。

## 键盘、焦点和无障碍

- skip link 可聚焦，焦点轮廓为 3px，并可将焦点移动到 `#main-content`
- 导入批次 Drawer 可通过键盘打开和关闭
- 证据见 [`keyboard-evidence.json`](keyboard-evidence.json) 和 [`screenshots/09-keyboard-focus-1440x900.png`](screenshots/09-keyboard-focus-1440x900.png)
- 七份 axe 扫描自动化违规数为 0
- axe 仍有 4 个 `incomplete`，均为 `color-contrast` 且由组件叠层使工具无法自动确定背景色；`incomplete` 不被计作自动化 PASS
- 这些项目只通过批准的语义 token 配对和人工对比度复核补强：正文/表面 14.68:1、次级文字/表面 7.58:1、次级文字/画布 7.18:1、选中树文字/信息表面 10.46:1
- axe 原始证据为 `a11y-*.json`，人工对比度摘要见 [`open-design-manifest.json`](open-design-manifest.json) 和 [`../UIUX.md`](../UIUX.md)

## normal / demo 隔离

请求计数口径为 `Fetch/XHR` 且 URL pathname 以 `/api/` 开头。Vite 加载的 `/src/shared/api/apiClient.ts` 是源码模块，不是业务 API。

| 项目 | normal 5173 | demo 5174 |
| --- | ---: | ---: |
| 浏览器总请求 | 923 | 278 |
| 真实业务 `/api` 请求 | 132 | 0 |
| 指向 8080 的请求 | 真实业务链路存在 | 0 |
| console error | 0 | 0 |
| page error | 0 | 0 |

demo 前后观测 7 张业务/审计表，合计行数为 518 → 518，MySQL 增量为 0：

- [`demo-mysql-before.tsv`](demo-mysql-before.tsv)
- [`demo-mysql-after.tsv`](demo-mysql-after.tsv)
- [`browser-demo-network.json`](browser-demo-network.json)
- [`browser-demo-console.json`](browser-demo-console.json)
- [`browser-demo-errors.json`](browser-demo-errors.json)

## 截图

### 六视口基线

1. [`01-people-import-390x844.png`](screenshots/01-people-import-390x844.png)
2. [`02-people-organization-768x1024.png`](screenshots/02-people-organization-768x1024.png)
3. [`03-people-employees-1024x768.png`](screenshots/03-people-employees-1024x768.png)
4. [`04-employee-detail-1366x768.png`](screenshots/04-employee-detail-1366x768.png)
5. [`05-people-import-1440x900.png`](screenshots/05-people-import-1440x900.png)
6. [`06-employee-detail-1920x1080.png`](screenshots/06-employee-detail-1920x1080.png)

### 业务、键盘与状态补充

7. [`07-validation-failed-1440x900.png`](screenshots/07-validation-failed-1440x900.png) — `BROWSER_REAL`
8. [`08-demo-isolation-1440x900.png`](screenshots/08-demo-isolation-1440x900.png) — demo 隔离
9. [`09-keyboard-focus-1440x900.png`](screenshots/09-keyboard-focus-1440x900.png) — `BROWSER_REAL`
10. [`10-empty-employees-1440x900.png`](screenshots/10-empty-employees-1440x900.png) — `BROWSER_FAULT_INJECTION`
11. [`11-network-error-1440x900.png`](screenshots/11-network-error-1440x900.png) — `BROWSER_FAULT_INJECTION`
12. [`12-unauthenticated-login-1440x900.png`](screenshots/12-unauthenticated-login-1440x900.png) — `BROWSER_REAL`
13. [`13-published-success-1440x900.png`](screenshots/13-published-success-1440x900.png) — `BROWSER_REAL`
14. [`14-loading-employees-1440x900.png`](screenshots/14-loading-employees-1440x900.png) — `BROWSER_FAULT_INJECTION`
15. [`15-partial-loading-employees-1440x900.png`](screenshots/15-partial-loading-employees-1440x900.png) — `BROWSER_FAULT_INJECTION`
16. [`16-processing-import-1440x900.png`](screenshots/16-processing-import-1440x900.png) — `BROWSER_FAULT_INJECTION`

## 静态验证与治理

最终静态统计以最终重跑后的 [`static-verification.log`](static-verification.log) 为准：后端全量 87/87、定向 OpenAPI 8/8、前端 16 个测试文件 126/126。日志覆盖并通过：

- 后端全量测试与 WAVE-2 OpenAPI contract
- 前端 lint、test、production build、demo build 和 check
- V1～V4 迁移源码 checksum
- OpenAPI、后端和前端契约
- 项目根目录范围核验
- 源码和交付目录 secret scan
- PAYROLL 发现性为 0
- WAVE-3/WAVE-4 公共 route 为 0
- 组织同步发现性为 0

治理扫描只允许项目根目录内明确列出的源码与交付范围。本轮没有扫描用户主目录、其他下载目录、兄弟项目或第三方应用，也未把这些位置的问题计入神州 HR。

## 完成判定与限制

- AC-PEOPLE-01～12：全部 `PASS`
- MySQL/Flyway：`PASS`
- 真实 API：`PASS`
- 六视口 normal 浏览器：`PASS`
- normal/demo 隔离：`PASS`
- demo MySQL 增量：0
- 404：真实 API 已验证；没有可用的 404 页面浏览器证据，不作 404 UI 完成声明
- axe：自动化违规为 0；4 个工具无法自动判断的 `color-contrast` 项仅由 token 与人工对比度证据补强
- 生产数据库、容器、Git 推送、PR、部署和 WAVE-3/WAVE-4 均不在本轮授权范围
- 最终 `static-verification.log` 与 delivery evidence gate 均已为 `PASS`；WAVE-2 在本报告边界内完成
