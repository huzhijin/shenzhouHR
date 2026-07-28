# 神州 HR WAVE-1 真实运行验收报告

- 验收日期：2026-07-24
- 结论：WAVE-1 已完成
- 验收边界：仅 WAVE-1；未连接生产、未使用容器、未推送 Git、未创建 PR、未部署、未实施 WAVE-2
- 真实运行地址：
  - 后端：`http://127.0.0.1:8080`
  - 前端：`http://127.0.0.1:5173`
  - 隔离演示模式：`http://127.0.0.1:5174`

## MySQL 与最小权限

- 服务端：MySQL `8.0.34`，`huzhijindeMacBook-Pro.local:3306`
- 默认存储引擎：`InnoDB`
- 服务端字符集/排序规则：`utf8mb4` / `utf8mb4_0900_ai_ci`
- SQL mode：`ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`
- 系统时区：`CST`；Spring JDBC 会话时区：`+00:00`
- `shenzhou_hr_dev`：已按授权范围重建并保留，V1～V4 完整
- `shenzhou_hr_test`：已按授权范围重建并保留，V1～V4 完整
- `shenzhou_hr_local_migrator`：仅两个目标 schema 的 Flyway DDL/DML 权限，无全局权限、无 `GRANT OPTION`
- `shenzhou_hr_dev_app`：仅 `shenzhou_hr_dev.*` 的 `SELECT, INSERT, UPDATE, DELETE`
- `shenzhou_hr_test_app`：仅 `shenzhou_hr_test.*` 的 `SELECT, INSERT, UPDATE, DELETE`
- 负向权限验证：应用账号创建/删除数据库、创建/修改/删除表、执行 `GRANT`、跨 schema 访问均被拒绝
- 实际 `SHOW GRANTS`：[`grants.tsv`](mysql/grants.tsv)
- 服务端元数据：[`server-metadata.tsv`](mysql/server-metadata.tsv)

## Flyway 与 schema

- `shenzhou_hr_dev`：空库 V1→V4 成功；`validate` 成功；第二次 `migrate` 为 no-op
- `shenzhou_hr_test`：空库 V1→V4 成功；`validate` 成功；第二次 `migrate` 为 no-op
- `shenzhou_hr_test` 重建后：构造 V1/V2 状态再升级至 V4 成功，V2 基线授权数据仍存在
- V1/V2 文件未修改、覆盖、重命名或删除
- 每个 schema：28 张表、96 条索引清单记录、93 条约束清单记录、42 条外键清单记录
- WAVE-1 表：`audit_event`、`auth_capability`、`auth_data_scope`、`auth_principal`、`auth_principal_role_assignment`、`auth_role`、`auth_role_capability`、`employee`、`employee_source_binding`、`employment_assignment`、`flyway_schema_history`、`legal_entity`、`local_account`、`login_failure_window`、`organization_current_closure`、`organization_current_projection`、`organization_identity`、`organization_source_binding`、`organization_version`、`password_credential`、`password_reset_grant`、`policy_publication_record`、`policy_rollback_record`、`policy_scope_binding`、`policy_template`、`policy_version`、`session_revocation`、`user_session`
- 迁移历史：[`flyway-history.tsv`](mysql/flyway-history.tsv)
- 完整清单：[`tables.tsv`](mysql/tables.tsv)、[`indexes.tsv`](mysql/indexes.tsv)、[`constraints.tsv`](mysql/constraints.tsv)、[`foreign-keys.tsv`](mysql/foreign-keys.tsv)

## 后端、前端与真实数据追溯

- Spring Profile：`dev`
- Spring 运行账号：`shenzhou_hr_dev_app`；`root` 未用于运行时
- Flyway 运行开关：迁移后为 `false`
- bootstrap：合成数据创建后关闭，再启动健康检查为 200
- 合成管理员：`synthetic.local.admin`
- 登录密码安全文件：`/Users/huzhijin/.config/shenzhouhr/wave1-login.env`，权限 `0600`
- 安全读取：在可信本机终端执行 `set -a; source /Users/huzhijin/.config/shenzhouhr/wave1-login.env; set +a`，不要打印环境变量
- 真实 MySQL 追溯：JDBC 用户为 `shenzhou_hr_dev_app@localhost`，数据库为 `shenzhou_hr_dev`，规则版本 V1～V5 与审计 correlation ID 可在 [`mysql-trace.tsv`](backend/mysql-trace.tsv) 复核
- 普通前端 `/api` 代理目标为 `127.0.0.1:8080`；代理请求产生的审计行可在 [`proxy-mysql-trace.tsv`](frontend/proxy-mysql-trace.tsv) 复核
- demo 模式浏览器网络记录中 `/api/` 请求数为 0；真实 MySQL `audit_event` 前后增量为 0

## 自动化与真实业务链路

- 后端：`./mvnw test`，53/53 通过
- 前端：`npm run lint` 通过；Vitest 12 个文件、104/104 通过；`npm run build`、`npm run build:demo`、`npm run check` 均通过
- 登录安全：正确登录、错误密码、用户名不可枚举、5 次失败锁定、锁定后拒绝均通过
- 密码流程：首次改密、普通改密、密码重置、旧密码拒绝、旧会话失效均通过
- 首次改密隔离：会话仅返回空能力集，受保护 API 返回 403，刷新/直达受保护路由返回登录改密流程
- 会话：空闲到期、主动撤销、退出登录、安全 Cookie 属性、CSRF 正反向均通过
- HTTP 安全：401、403、404 均按 API 错误契约通过
- 账号权限：创建、停用、启用、锁定、解锁、角色授权均通过
- 审计：列表、详情、只读权限、`wave1-real-chain-correlation` 从 API 到 MySQL 可追踪
- 规则：草稿、校验、冲突、影响预览、试算、发布、停用、回滚均通过

## Open Design 路由验收

| 设计基线 | 真实路由 | 结果 |
| --- | --- | --- |
| V19-OD-01 登录与全局壳层 | `/login`、全局导航/页头/面包屑 | 通过 |
| V19-OD-05 规则中心 | `/rules`、`/rules/templates`、模板详情、版本详情 | 通过 |
| V19-OD-14 账号、权限与审计 | `/access/accounts`、`/access/roles`、`/access/audit`、审计详情 | 通过 |

- 已对照 `route-to-artifact.json`、`page-state-matrix.md`、`responsive-screenshot-checklist.md` 和已批准设计 Token
- 390×844、768×1024、1024×768、1366×768、1440×900、1920×1080 均无页面级横向溢出
- 键盘首焦点为“跳到主要内容”，可见焦点轮廓为 3px
- 实测正文对比度 16.80:1，主按钮前景/背景对比度 8.85:1
- 最终干净浏览器会话依次访问规则、账号、角色、审计路由，控制台 `error`/`warn` 均为 0

## 真实截图

1. [`01-login-desktop-1440x900.png`](01-login-desktop-1440x900.png)
2. [`02-login-mobile-390x844.png`](02-login-mobile-390x844.png)
3. [`03-first-password-change-1440x900.png`](03-first-password-change-1440x900.png)
4. [`04-login-error-1440x900.png`](04-login-error-1440x900.png)
5. [`05-account-locked-1440x900.png`](05-account-locked-1440x900.png)
6. [`06-rules-center-desktop-1440x900.png`](06-rules-center-desktop-1440x900.png)
7. [`07-rules-center-mobile-390x844.png`](07-rules-center-mobile-390x844.png)
8. [`08-draft-edit-1440x900.png`](08-draft-edit-1440x900.png)
9. [`09-conflict-state-1440x900.png`](09-conflict-state-1440x900.png)
10. [`10-impact-preview-1440x900.png`](10-impact-preview-1440x900.png)
11. [`11-publication-confirmation-1440x900.png`](11-publication-confirmation-1440x900.png)
12. [`12-account-list-1366x768.png`](12-account-list-1366x768.png)
13. [`13-role-permissions-1920x1080.png`](13-role-permissions-1920x1080.png)
14. [`14-audit-list-1440x900.png`](14-audit-list-1440x900.png)
15. [`15-audit-detail-1440x900.png`](15-audit-detail-1440x900.png)
16. [`16-loading-real-request-1440x900.png`](16-loading-real-request-1440x900.png)
17. [`17-empty-state-1440x900.png`](17-empty-state-1440x900.png)
18. [`18-error-state-1440x900.png`](18-error-state-1440x900.png)
19. [`19-forbidden-403-1440x900.png`](19-forbidden-403-1440x900.png)
20. [`20-success-state-1440x900.png`](20-success-state-1440x900.png)
21. [`21-rules-tablet-768x1024.png`](21-rules-tablet-768x1024.png)
22. [`22-accounts-tablet-1024x768.png`](22-accounts-tablet-1024x768.png)

## 未执行项与已知限制

- 未执行项：生产数据库连接、容器化运行、Git 推送、PR、部署和 WAVE-2；均为本轮明确禁止项。
- 本机验收使用 HTTP，因此开发 Profile 的 Cookie `Secure=false`；生产默认配置仍为 `Secure=true`。`HttpOnly`、`SameSite=Lax` 和 `Path=/` 已实测。
- MySQL `system_time_zone=CST`；应用 JDBC 连接显式为 UTC，业务展示按 `Asia/Shanghai`。
