# 神州 HR 考勤与薪资核算系统

当前正式需求基线为 V1.9 `docs_confirm`。仓库包含按波次交付的访问控制、人员、规则与考勤能力；任何发布结论必须以最终 integrated commit 的门禁证据为准。

## 2026-08 已确认考勤业务规则

`business-rules-alignment-2026-08` 的业务规则和验收标准以 [OpenSpec change](openspec/changes/business-rules-alignment-2026-08/proposal.md) 及其 `specs/` 为准，[客户业务决策签字稿](.umadev/explore/FINAL-BUSINESS-DECISIONS-SIGNOFF.md) 中的 17 项决策均作为已确认事实。数据库规划阶段所称 V32 已按仓库连续迁移号落为 [V48](backend/src/main/resources/db/migration/V48__business_rules_alignment_schema.sql)。

本次规则的 API、数据库、发布说明、HR 沟通、补卡流程、部署检查和分阶段回滚入口见 [2026-08 考勤业务规则发布文档](docs/business-rules-2026-08/README.md)。文档与清单就绪不代表已经安排窗口、部署预发布或生产，也不代表完成生产观察；这些状态必须以实际执行证据更新。

## 公司数据边界

公司是员工、组织、考勤配置、外部来源、计算、报表和权限的唯一顶层业务维度。当前公开契约和运行时统一使用 `companyId`、`Company`、`COMPANY` 和“公司”；组织范围必须先证明属于一个明确公司，员工本人范围只从服务端会话解析。公司 HR、高管、部门负责人和员工本人仍需同时满足各自 capability 与明确的 `COMPANY`、`ORGANIZATION` 或 `SELF` 范围，角色名称和前端隐藏都不能扩大权限。

单个授权公司可以安全自动选中；存在多个授权公司时必须显式选择，系统不猜测第一家公司。V1～V8、V10 及冻结验证证据保留原始字节与历史术语；V9 仅对 MySQL 8.4 保留字 `row_number` 做了有记录的标识符引用修正，已有 V9 Flyway history 的数据库不得静默 `repair`。V11 是一次性前向迁移桥，最新数据库、后端、OpenAPI、前端和当前交付文档只使用公司语义。

面向普通员工、部门负责人、HR、高管、系统管理员和审计人员的客户使用说明见 [`docs/user-guide/README.md`](docs/user-guide/README.md)。当前部署、外部取数、期初数据和报表生产就绪度见 [`docs/contracts/2026-08-06-customer-deployment-readiness.md`](docs/contracts/2026-08-06-customer-deployment-readiness.md)；技术环境可运行不等于考勤业务链已经生产就绪。
需要客户拍板的出勤率、日报/周报/月报、汇总、工作台和导出口径集中在 [`docs/contracts/2026-08-06-reporting-business-confirmation.md`](docs/contracts/2026-08-06-reporting-business-confirmation.md)；本轮逐项代码结论和已修/未完范围记录在 [`docs/contracts/2026-08-06-reporting-code-audit.md`](docs/contracts/2026-08-06-reporting-code-audit.md)。

## 当前部署迁移状态

仓库现已恢复连续的 V1～V31 迁移链。V12～V28 来自原始 Git 对象；V29、V30 来自保留的原始工作树，并已逐字节核对旧构建产物，其 Flyway checksum 与本机历史库记录一致。不得修改已经执行过的迁移，也不得用 Flyway `repair` 掩盖 checksum 差异。全新客户库可以按 V1～V31 顺序迁移；下述现有本机基线收口脚本不属于空库安装流程。

[`deploy/mysql/usability-finalization-post-v30.sql`](deploy/mysql/usability-finalization-post-v30.sql) 只用于已经准确执行过 V30 且包含 W3 基线的现有数据库；干净数据库不得运行。它负责停用 W3 验证公司、停用旧合成管理员并撤销会话、移除制造中心主管/主任角色，以及按精确标识清理浏览器验收产生的合成员工与组织。脚本会先核对数据形状，发现真实授权或异常依赖时立即回滚；它保持为 Flyway 外的可重复执行收口脚本。

完成上述收口后，现有本机基线必须按固定顺序执行共享地点升级：先由 Flyway 执行并记录纯结构迁移 [`V31__shared_physical_location_catalog.sql`](backend/src/main/resources/db/migration/V31__shared_physical_location_catalog.sql)，再运行 [`deploy/mysql/four-company-finalization-post-v30.sql`](deploy/mysql/four-company-finalization-post-v30.sql)，将已确认的上海昇州、上海晟州聚能、江苏神州和江苏芯越建立为 4 家独立公司并初始化公司级班次、工作日历、考勤组、规则、年假策略和管理员公司范围；随后运行 [`deploy/mysql/shared-location-convergence-post-v30.sql`](deploy/mysql/shared-location-convergence-post-v30.sql)，将内部 28 条公司兼容投影收敛为 7 条集团共享地点主数据和 28 条公司可用关系；最后运行只读验收脚本 [`deploy/mysql/verify-four-company-finalization.sql`](deploy/mysql/verify-four-company-finalization.sql)。顺序必须是 `V31 → 四公司收口 → 共享地点收敛 → 只读验收`，全部检查均为 `PASS` 才可继续。这些脚本均有严格的版本和数据形状保护，不能用于空库或未经确认的环境。

## W9 发布加固与验收

W9 提供考勤 P0-A 的离线安全检查、50 并发/36 个月容量合同、原生 Nginx/systemd 预检、MySQL 备份恢复安全脚本、浏览器验收矩阵和严格 release evidence contract。当前状态是 **harness ready，release `NOT_VERIFIED`**：

- W9 依赖 W7 FINAL，不依赖 W8；
- 本 worktree 禁止连接生产，也不执行切流、恢复或 MySQL 8.4 人工演练；
- 本地门通过只说明工具合同可运行，不代表系统上线通过；
- 同步 W7 FINAL 后必须在新的干净 integrated commit 上重跑全部证据。

本地只读总编排：

```bash
python3 scripts/release/verify_wave9.py \
  --repo . \
  --run-id w9-local-20260728-01
```

验收索引见 [`docs/verification/wave9/README.md`](docs/verification/wave9/README.md)，当前真实验证记录见 [`docs/verification/wave9/LOCAL-VERIFICATION.md`](docs/verification/wave9/LOCAL-VERIFICATION.md)，最终清单模板见 [`docs/verification/wave9/release-manifest.template.json`](docs/verification/wave9/release-manifest.template.json)。

## 技术基线

- 前端：React 19、TypeScript、Vite、Ant Design、Tabler Icons
- 后端：OpenJDK 21、Spring Boot 4.1、Maven、MyBatis
- 数据库：仓库核心验证基线为 MySQL 8.4 LTS、Flyway 前向迁移；当前客户宝塔包另行固定为 MySQL 8.0.45。两者尚未统一，必须先选定客户权威版本并在同一最终提交上重跑 V1～V31、安装、升级和恢复验收，不能仅凭任一侧本地测试声明生产就绪。
- 本地联调：直接连接 `127.0.0.1:3306`，不使用 Docker、Podman 或 Testcontainers

## WAVE-1 路由

- 登录：`/login`
- 规则中心：`/rules`、`/rules/templates`、模板详情与版本详情
- 访问控制：`/access/accounts`、账号详情、`/access/roles`
- 审计：`/access/audit`、审计详情

菜单与生产路由由服务端返回的 capability 控制；前端隐藏不代替服务端鉴权。WAVE-1 前端不提供任何薪资入口。

## 本机 MySQL

数据库控制脚本只允许管理：

- `shenzhou_hr_dev`
- `shenzhou_hr_test`

先把 [`deploy/mysql/wave1-local.env.example`](deploy/mysql/wave1-local.env.example) 复制到仓库外的绝对路径，填入本机凭据并设置为当前用户所有的 `0600` 普通文件。凭据值不得写入仓库、命令参数、日志或报告。

默认命令只显示计划，不连接数据库：

```bash
bash deploy/mysql/wave1-local-mysql.sh
```

只读探测：

```bash
bash deploy/mysql/wave1-local-mysql.sh \
  --env-file /absolute/outside/repository/shenzhouhr-wave1.env \
  probe
```

执行建库、最小权限账号、V1 到最新迁移、V1/V2 升级、validate、重复 migrate no-op 与负向权限验证：

```bash
bash deploy/mysql/wave1-local-mysql.sh \
  --env-file /absolute/outside/repository/shenzhouhr-wave1.env \
  --execute all
```

脚本不会删除、清空或重建 `shenzhou_hr_dev`；只允许在破坏性预检通过后重建 `shenzhou_hr_test`。完整边界和最终测试库状态说明见 [`deploy/mysql/README.md`](deploy/mysql/README.md)。

## 后端运行

仓库中的 [`.env.example`](.env.example) 只声明变量名和无密码模板。数据库迁移必须先通过上一节的公司维度预检脚本完成；应用内 Flyway 默认关闭，禁止用后端启动绕过 V10→V11 门禁。通过终端、IDE 或本机进程管理器注入应用环境变量，且不得回显值：

- 迁移脚本使用 `shenzhou_hr_local_migrator`
- Spring Boot 使用 `shenzhou_hr_dev_app`
- Spring Boot 保持 `SHENZHOUHR_FLYWAY_ENABLED=false`
- Spring Boot 日常运行禁止使用 `root`
- 数据库会话时区为 UTC，业务时区为 `Asia/Shanghai`
- 批量开通账号必须注入稳定的 `SHENZHOUHR_PROVISIONING_PEPPER`：32 个随机字节、无填充 base64url 编码后恰好 43 位；实际值只放部署密钥管理器，不写入仓库。`SHENZHOUHR_PROVISIONING_KEY_ID` 默认 `v1`，轮换后尚未完成的批次须改走管理员密码重置。

```bash
cd backend
export JAVA_HOME=/path/to/openjdk-21
./mvnw spring-boot:run
```

开发合成管理员仅在 `dev` profile 且显式设置 `SHENZHOUHR_BOOTSTRAP_ENABLED=true` 时引导。用户名由 `SHENZHOUHR_BOOTSTRAP_ADMIN_USERNAME` 提供，初始密码只通过运行环境中的 `SHENZHOUHR_BOOTSTRAP_ADMIN_PASSWORD` 注入；不会进入迁移、源码或日志。引导完成后关闭该开关。默认合成用户名为 `synthetic.local.admin`，不得与真实员工账号混用。

后端监听 `http://127.0.0.1:8080`，健康检查为 `http://127.0.0.1:8080/actuator/health`。

## 前端运行

普通开发模式通过 Vite 把 `/api` 代理到 `127.0.0.1:8080`，使用真实 Spring Boot 与 MySQL 数据流：

```bash
cd frontend
npm ci
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`。

演示模式只使用明确标记的合成前端数据，不发出后端请求，也不写 MySQL：

```bash
cd frontend
npm run demo
```

## 质量门

```bash
python3 scripts/qa/verify_company_vocabulary.py --explain-allowlist

cd backend
./mvnw test

cd ../frontend
npm run lint
npm run test
npm run build
npm run build:demo
npm run check
```

H2 仅用于快速自动化测试，不能替代本机 MySQL 迁移、权限和真实浏览器联调验收。OpenAPI 合同位于 [`api/openapi.yaml`](api/openapi.yaml)，正式迁移只位于 `backend/src/main/resources/db/migration`。

## 目录

- `api/openapi.yaml`：V1.9 WAVE-1 API 契约
- `backend`：Spring Boot 模块化单体、MyBatis 与 Flyway
- `frontend`：产品型 React SPA
- `docs/docs-confirm-v1.9`：已批准的 V1.9 规格与 Token 合同
- `design/open-design/v1.9`：确认的视觉、布局、状态与响应式参考
- `deploy/mysql`：本机 MySQL 安全控制与验证
- `docs/decisions/OPEN-DECISIONS.md`：追加式待决策和外部条件登记
