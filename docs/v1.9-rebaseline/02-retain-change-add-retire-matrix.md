# V1.9 保留 / 修改 / 新增 / 退役矩阵

## 1. 保留

| 对象 | 证据 | 后续约束 |
|---|---|---|
| React 19 + TypeScript + Vite + Ant Design | `frontend/package.json` | 增量扩展现有 feature 分层 |
| AppShell 与响应式导航 | `frontend/src/shared/components/AppShell.tsx` | 管理端维持产品型密度；员工移动入口单独授权 |
| Tabler Icons | `@tabler/icons-react` | 全产品 2px stroke，不混用原型 SVG sprite |
| 语义设计 Token | `frontend/src/styles/tokens.css` | 以新 V1.9 canonical candidate 为审阅对象，人工确认后生效并在后续实测对比度 |
| API client / 关联 ID / no-store | `frontend/src/shared/api/apiClient.ts`、后端 filters | 所有新接口复用 |
| Spring Security 默认拒绝 | `SecurityConfiguration.java` | 正式本地认证上线前不得放开 |
| capability、角色、数据范围 | V1/V2、authorization 模块 | 扩展动作、字段、状态和 PAYROLL 域 |
| 组织版本、closure、任职有效期 | V1、organization/employee 模块 | 组织发布后本地维护；不再做持续同步 |
| Flyway | `backend/pom.xml`、V1/V2 | 只加 V3+，不改 V1/V2 |
| 模块化单体 | 现有包结构、ArchUnit | 保持单向依赖，避免提前微服务化 |
| 演示模式 | `runtimeMode.ts`、demo 数据 | 仅合成数据且持续显式标识 |
| Nginx/systemd 模板 | `deploy/` | 后续单独在目标环境验证 |

## 2. 修改

| 对象 | 当前口径 | V1.9 目标 |
|---|---|---|
| 需求基线 | README/旧确认包指向 V1.7 | 人工确认后统一指向 PRD V1.9 + `docs/docs-confirm-v1.9/` |
| 认证契约 | SSO 待定、dev header | 本地账号密码、强制改密、锁定、重置、session 撤销；dev header 仅开发 |
| 组织能力 | 同步预览 capability、sync 列 | 期初 Excel 一次发布 + 本地版本化维护；旧能力停止授予 |
| 前端路由 | 只有组织/员工 | 按确认后的 route map 分波次扩展；demo ID 全部参数化 |
| OpenAPI | 3 条只读端点 | 保持兼容并扩展统一认证、错误、幂等、任务、版本和审计合同 |
| 测试数据库 | H2 + 手写 schema | 保留快速测试并增加 MySQL/Flyway 集成门 |
| 规则默认值 | 尚未实现 | 只存在于已发布规则版本/受控参数，不散落在计算代码 |
| 设计 handoff | 声称 pixel-first/source of truth | 降级为 PRD 后的视觉与交互参考 |

## 3. 新增

| 领域 | 核心对象/能力 | API / 表 / 页面 / 测试影响 |
|---|---|---|
| 本地身份 | Account、Credential、PasswordPolicy、LoginAttempt、Session、ResetToken | `/auth/*`、`/access/accounts`；安全边界测试 |
| 期初组织人员 | ImportTemplate、ImportBatch、ImportRow、Diff、Publish、ReversalBoundary | `/imports/people/*`；V3+ 导入表；六步向导 |
| 任职周期 | EmploymentPeriod、PriorService | 员工详情、本地维护、二次入职与周年测试 |
| 统一规则 | PolicyTemplate、PolicyVersion、Scope、Assignment、Preview、Snapshot | `/rule-policies/*`；规则中心；冲突/回滚测试 |
| 考勤设置 | AttendanceGroup、ShiftVersion、Calendar、Location、MealDeduction、Grace、MissingPunch | `/attendance-settings/*`；版本/边界测试 |
| 统一事实层 | SourceBatch、RawFact、NormalizedRecord、MatchDecision、EffectiveEvent、EvidenceLink | 得力/OA/Excel 共用；跨来源幂等与证据追踪 |
| 离线考勤 Excel | MappingProfile、PunchImportBatch/Row、ErrorReport、Publish/Reversal | `/attendance-punch-imports/*`；OD10A |
| 考勤计算 | CalculationVersion、DailySegment、Exception、Adjustment、Recalculation | 日明细、异常、解释链、重算差异测试 |
| 月结 | AttendancePeriod、Freeze、Close、Reopen、Snapshot | `/attendance-periods/*`；冻结/重开/差异测试 |
| 假别/年假 | LeavePolicyVersion、AnnualLeaveQualification/Tier/Grant | 周年、闰日、离职、再入职、切换测试 |
| 时间账户 | Account、ImmutableLedgerEntry、OpeningImport | 期初余额、流水、重算一致性测试 |
| 审计 | AuditWriter、敏感查看/导出/高风险动作事件 | 查询 API、防篡改链和权限测试 |
| 薪资预留 | PayrollSnapshotRef、PayrollRun 边界、PAYROLL capability | 后端独立域、默认拒绝；前端零 route |

## 4. 退役或历史化

| 对象 | 处理 |
|---|---|
| PRD V1.7、`docs/docs-confirm/`、`docs/open-design-prompts.md` | 保留文件；在新清单中标为历史，禁止作为当前门状态 |
| SSO/IdP 待定结论 | 由本地账号密码明确替代 |
| 组织定时、手动持续、双向、变更同步 | 不建设页面、任务、API 或新表；旧 capability 停止使用 |
| 写死“夏令组/冬令组” | 改为同一考勤组下的班次版本和生效期 |
| 个人/城市硬编码特例 | 改为有期限专用组/班组/作用范围 |
| 旧年假“满一年固定 5 天” | 改为资格与累计工龄档位分离、周年发放/失效 |
| 当前薪资菜单、route、卡片、搜索、通知、报表、工资条 | 全部不注册；任何旧设计仅 `FUTURE_RESERVED` |
| Open Design 静态 HTML/JS 直接接入 React | 禁止；只提取 route、token、组件、状态和响应式合同 |
| `PROTOTYPE_SIMULATION` 作为完成证据 | 禁止；必须由真实 API、数据库、权限和自动化测试替代 |

## 5. 迁移原则

- 历史数据和冻结结果只追加新版本或冲正，不原位改写。
- 已发布/已月结对象保留当时规则、组织、任职、来源和计算快照。
- V3+ 迁移必须可重复验证、向前修复；不得通过回滚脚本删除用户数据。
- 每个实施波次仅认领该波次所需的窄文件和迁移面。
