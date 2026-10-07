# Business Rules Alignment 发布尾项就绪证据 — 2026-08-17

## 结论

本仓库和本机隔离范围内的部署合同、数据迁移与回滚准备均已完成可重复验证，但这些结果**不是预发布或生产部署证据**。OpenSpec 12.9～12.12 仍需真实排期通知、真实环境部署及经过时间门禁的运行证据，因此保持未完成。

本次检查绑定到当时的 Git HEAD `e32ae0740825069ec3b2badaf9b5f9e57c6431e3`；工作树包含大量尚未集成的修改，不能据此生成可交付候选制品。最终执行必须重新绑定干净 integrated commit 和制品 SHA-256。

## 授权与环境边界

- 执行位置：仓库 `/Users/huzhijin/Downloads/shenzhouHR` 和仓库已定义的本机隔离 MySQL 计划。
- 本机没有注入预发布或生产数据库、域名、部署工单、通知名单或客户凭据。
- 检查时没有运行 ShenzhouHR 后端、前端或 Spring Boot 进程。
- 本机现有 `127.0.0.1:3306` MySQL 未被本轮部署尾项检查连接或修改。
- 宝塔 `install.sh`、`upgrade.sh` 没有 dry-run，并固定要求真实 root、面板目录、MySQL 8.0.45 和登记站点；本机未执行这两个脚本。
- 所有完成项仅表示本地准备度，不提升 `staging`、`production` 或客户确认状态。

## 已执行的本地门禁

| 检查 | 命令或证据 | 结果 | 能证明什么 | 不能证明什么 |
|---|---|---|---|---|
| 宝塔脚本语法 | 对 `deploy/baota/**/*.sh` 逐一执行 `bash -n` | PASS | Shell 文件可解析 | 真实宝塔、Nginx、权限、网络和数据库可用 |
| 原生部署只读预检 | `python3 scripts/release/native_preflight.py` | `static_status=PASS`；`system_changes=0`；`network_requests=0`；`release_gate_status=NOT_VERIFIED` | Nginx/systemd 模板无开发绕过和自动切流 | 真实 `nginx -t`、systemd、Flyway、健康检查或切流 |
| 发布/备份/恢复合同测试 | `python3 -m unittest discover -v scripts/release/tests` | 138 tests，全部通过 | 发布包、宝塔代理、备份恢复、只读预检和证据合同的负向保护 | 正式制品或任何真实环境已经部署 |
| 隔离 MySQL 计划 | `bash deploy/mysql/mysql8410-isolated.sh plan` | PASS；明确 `127.0.0.1:13306`、plan-only、无创建/下载/进程动作 | 后续隔离演练不会碰现有 3306 的边界 | 本轮重新执行了数据库迁移 |
| V1→V48 迁移与回滚 | [migration-rehearsal-2026-08-17.md](migration-rehearsal-2026-08-17.md) | PASS | MySQL 8.4.10 隔离库完成 V1→V48、validate、结构核验及 V48 回滚；临时库和用户已清理 | 客户 MySQL 8.0.45 或预发布/生产数据库已经迁移 |
| 后端全量回归 | `cd backend && ./mvnw -q test` | 938 tests；0 failures；0 errors；1 skipped | 当前工作树中的业务规则实现通过全部后端自动化测试 | 正式候选制品、预发布或生产运行正确 |
| 前端全量回归与构建 | `npm run check`、`npm run typecheck`、`npm run build:demo` | 542 tests（538 passed、4 skipped）；ESLint、类型检查、正式构建和 demo 构建均 PASS | 当前前端源码通过静态、单测和构建门禁 | 真实浏览器、真实后端或客户环境联调成功 |
| OpenSpec 严格校验 | `npx -y @fission-ai/openspec@1.9.0 validate business-rules-alignment-2026-08 --strict` | PASS；递归识别 67 个 delta | 变更包的嵌套规格可由支持递归规格的 CLI 严格解析 | 旧版 OpenSpec 1.2.0 能正确执行 apply/sync/archive |
| 脏工作树发布保护 | `bash deploy/baota/build-release.sh <temporary-output>`，未设置 `ALLOW_DIRTY_RELEASE` | 预期拒绝，exit 1；命中“clean, committed source tree”保护 | 当前 363 项工作树变化不会被误打成客户包 | 正式候选构建、制品校验及在干净集成提交上的回归已完成 |
| 旧公式/总开关静态核对 | 搜索主代码中的分钟出勤率和 `businessRules2026_08` | 主代码仅命中 `rateByDays`；不存在该总开关 | 当前实现没有待关闭的同名总开关或主线分钟公式 | 已生产稳定运行满一个月 |

## OpenSpec 尾项关闭条件

### 12.9 排期并通知干系人 — `BLOCKED_EXTERNAL`

当前缺少实际变更窗口和通知送达证据。关闭前必须补齐：

1. 变更单/工单 ID、预发布窗口、生产窗口、时区和预计中断时间；
2. 发布、DBA、HR、考勤、运维、安全和回滚负责人；
3. 最终公告版本及收件范围；
4. 通知发送时间、渠道、送达或确认记录；
5. HR 对迟到转旷班生效时间和员工申诉渠道的批准。

仓库模板和本文件不能代替上述人员动作，因此 12.9 不勾选。

### 12.10 部署预发布并烟测 — `BLOCKED_EXTERNAL`

当前没有被授权且可识别的预发布环境。关闭前必须在真实预发布环境保存：

1. 干净 integrated commit、不可变制品路径及 SHA-256；
2. 环境 ID、变更单、备份 ID、部署开始/结束时间和执行人；
3. 目标 MySQL 精确版本、迁移前 Flyway 历史、V48 migrate/validate 和只读结构核验输出；
4. `/actuator/health`、登录/会话、公司边界、跨公司与组织范围外 403 的烟测；
5. 29/30 分钟边界、天数出勤率、补卡提交/配额/审批、三类加班、免打卡/外出、部门汇总和调岗拆行样例；
6. 得力开关、整点 cron、失败不推进水位及 30 天日志清理证据；
7. 应用制品回切和 V48 数据影响已评审的回滚演练记录。

本地迁移与合同测试只能作为预发布前置输入，因此 12.10 不勾选。

### 12.11 部署生产并监控 24 小时 — `BLOCKED_EXTERNAL`

当前没有生产授权、目标、凭据或变更单，也没有连续 24 小时观测区间。关闭前必须保存：

1. 生产部署批准、制品 SHA、备份 ID、执行人和实际切流时间；
2. 与预发布相同的迁移、健康、安全和业务烟测证据；
3. 从实际切流时间开始、连续至少 24 小时的起止时间；
4. 期间的健康状态、5xx/4xx 异常趋势、认证失败、数据库连接池/锁等待/慢查询、得力同步成功失败、水位、隔离记录和报表重算结果；
5. 事故、回滚或人工修正记录；若无事件，也要有明确的零事件签核；
6. HR、运维和发布负责人对观察结果的确认。

不得预填未来时间或用本地进程观察替代生产监控，因此 12.11 不勾选。

### 12.12 稳定一月后移除旧代码和开关 — `NOT_ELIGIBLE`

该任务的时间门禁从 12.11 的真实生产切流时间开始计算。当前 12.11 未完成，无法确定最早合格日期。关闭前必须：

1. 提供连续一个月稳定运行区间及业务/技术事件汇总；
2. 确认没有依赖旧分钟公式或旧开关的客户端、报表、运维手册和回滚操作；
3. 在届时的干净提交上重新执行静态扫描、全量测试、数据对账和发布门禁；
4. 若仍不存在 `businessRules2026_08` 等旧开关，应记录“无对象可删除”，不能伪造删除动作；
5. 更新最终发布说明和回滚基线后，由产品、HR、开发和运维签核。

当前代码已使用天数公式且没有该同名总开关，但时间门禁尚未开始，因此 12.12 不勾选。

## 下一次执行入口

- 发布清单：[deployment-checklist.md](../../business-rules-2026-08/deployment-checklist.md)
- 分阶段回滚：[rollback-plan.md](../../business-rules-2026-08/rollback-plan.md)
- 候选发布说明：[release-notes.md](../../business-rules-2026-08/release-notes.md)
- HR 沟通方案：[late-to-absence-communication-plan.md](../../business-rules-2026-08/late-to-absence-communication-plan.md)
