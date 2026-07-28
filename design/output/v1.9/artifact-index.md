# 神州 HR V1.9 · Artifact Index

更新日期：2026-07-22  
正式基线：PRD V1.9  
设计规范模式：`PROJECT_EMBEDDED`  
平台 Active Design System：`NOT_ACTIVE`（未作激活声明；当前直接读取项目内 `design-system/DESIGN.md` 与 `tokens.css`）

## 状态定义

- `ACTIVE_BASELINE`：唯一允许后续继续的当前 V1.9 基线。
- `COMPLETE`：已按活动基线生成并完成本轮自检的增量 artifact。
- `SUPERSEDED`：失败或已被替代的 V1.9 尝试；保留原件但不继续使用。
- `MISSING`：尚未生成，不能视为已完成页面。
- `NOT_STARTED`：按硬停止边界尚未开始，不代表缺失或失败。
- `WAITING_FOR_MANUAL_SWITCH_TO_FREEFORM_LIVE_ARTIFACT`：必须由用户切换任务类型后另行生成。
- `KEEP`：仅表示视觉或组件可重新核验后复用，不继承 V1.7 业务规则。
- `MODIFY`：存在可参考结构，但必须按 V1.9 重构。
- `RETIRE`：不进入 V1.9 导航、路由和当前实现。
- `FUTURE_RESERVED`：仅后端或未来预留，当前前端完全不可发现。
- `UNKNOWN`：缺少可读原文件或无法核验状态，禁止猜测。

## V1.9 交付队列

| 编号 | 名称 | 状态 | 文件 / 交付说明 |
|---|---|---|---|
| V19-OD-00-PRECHECK | 同项目基线切换审计 | SUPERSEDED | 历史预检：`../../../v19-od-00-precheck.html`；不作为后续基线 |
| V19-OD-00 | V1.9 项目设计 brief | ACTIVE_BASELINE | `v19-od-00-project-brief.html`；唯一活动基线 |
| V19-OD-01 | 本地登录与全局壳层 | COMPLETE | `v19-od-01-login-shell.html`；本地认证、响应式壳层与系统状态 |
| V19-OD-02 | 角色工作台与三级看板 | COMPLETE | `v19-od-02-role-workbench.html`；七类角色、三级范围与联动待办 |
| V19-OD-03 | 组织与员工期初导入 | COMPLETE | `v19-od-03-people-initial-import.html`；独立六步向导与发布审计 |
| V19-OD-04 | 本地组织、员工与任职周期 | COMPLETE | `v19-od-04-organization-employment.html`；本地维护、二次入职与累计工龄 |
| V19-OD-05 | 统一规则配置中心 | COMPLETE | `v19-od-05-rule-center.html`；模板、版本、范围、冲突、试算与发布 |
| V19-OD-06 | 考勤组、班次与工作日历 | COMPLETE | `v19-od-06-attendance-groups-shifts.html`；季节版本、地点、生效期与日历 |
| V19-OD-07 | 晚餐、迟到宽限与缺卡策略 | COMPLETE | `v19-od-07-attendance-policies.html`；默认口径、边界试算与发布确认 |
| V19-OD-08 | 假别与周年年假策略 | COMPLETE | `v19-od-08-leave-annual-policy.html`；资格与档位分离、周年发放解释 |
| V19-OD-09 | 时间账户与期初余额导入 | COMPLETE | `v19-od-09-time-accounts.html`；独立余额批次、流水与幂等审计 |
| V19-OD-10A | 离线考勤打卡 Excel 导入 | COMPLETE | `v19-od-10a-offline-punch-import.html`；原始打卡独立向导、跨来源去重与追溯 |
| V19-OD-10B | 在线考勤机与 OA 考勤业务单据接入 | COMPLETE | `v19-od-10b-online-oa-sources.html`；在线打卡与 OA 只读单据，不含组织同步 |
| V19-OD-11 | 考勤工作台、日明细与证据链 | COMPLETE | `v19-od-11-attendance-evidence.html`；工作段、来源、规则与“为什么” |
| V19-OD-12 | 异常、重算、月结与报表 | COMPLETE | `v19-od-12-exceptions-close-reports.html`；阻断驱动重算、月结和受控报表 |
| V19-OD-13 | 员工自助与移动端 | COMPLETE | `v19-od-13-employee-mobile.html`；独立移动 IA、本人范围与解释反馈 |
| V19-OD-14 | 权限、账号、审计与系统运维 | COMPLETE | `v19-od-14-access-audit-ops.html`；动作权限、数据范围、只读审计与健康状态 |
| V19-OD-15 | 独立考勤数据大屏 | COMPLETE | `v19-od-15-attendance-screen.html`；自由形式 Live artifact，1920×1080 / 3840×2160 只读聚合大屏 |
| V19-OD-16 | 全项目 QA 与设计交接 | COMPLETE | `v19-od-16-qa-handoff.html`；全量文件、路由、链路、口径、响应式、无障碍与工程边界终检 |

## V1.7 历史引用与处理建议

当前项目内未发现任何可读取的 V1.7 原 artifact 文件。以下行只保留旧编号关系和迁移建议；实际内容状态全部为 `MISSING / UNKNOWN`，不得视为 V1.9 已完成页面。

| 旧编号 | V1.9 历史引用名 | 实际文件状态 | 建议 | 预计对应 V1.9 artifact | 迁移说明 |
|---|---|---|---|---|---|
| OD-00 | LEGACY-V17-OD-00 | MISSING / UNKNOWN | MODIFY | V19-OD-00 | 只保留审计框架，全部基线改为 V1.9 |
| OD-01 | LEGACY-V17-OD-01 | MISSING / UNKNOWN | MODIFY | V19-OD-01 | 改为独立账号密码登录，不再保留 SSO/IdP 待定口径 |
| OD-02 | LEGACY-V17-OD-02 | MISSING / UNKNOWN | MODIFY | V19-OD-02 | 看板按七类角色、三级口径和数据新鲜度重构 |
| OD-03 | LEGACY-V17-OD-03 | MISSING / UNKNOWN | MODIFY | V19-OD-02 | 可参考工作台结构，指标与权限重新核验 |
| OD-04 | LEGACY-V17-OD-04 | MISSING / UNKNOWN | MODIFY | V19-OD-13 | 员工端改为四项移动 IA，薪资前台不可发现 |
| OD-05 | LEGACY-V17-OD-05 | MISSING / UNKNOWN | MODIFY | V19-OD-03、V19-OD-04 | 仅保留期初导入与本地维护，不保留持续组织同步 |
| OD-06 | LEGACY-V17-OD-06 | MISSING / UNKNOWN | RETIRE | V19-OD-10A、V19-OD-10B | 组织同步结构退役；只迁移合法的数据接入任务 |
| OD-07 | LEGACY-V17-OD-07 | MISSING / UNKNOWN | MODIFY | V19-OD-05～V19-OD-07、V19-OD-11 | 规则生命周期、季节版本、晚餐、宽限和缺卡口径重构 |
| OD-08 | LEGACY-V17-OD-08 | MISSING / UNKNOWN | MODIFY | V19-OD-12 | 异常、重算、月结与冻结保护统一重构 |
| OD-09 | LEGACY-V17-OD-09 | MISSING / UNKNOWN | MODIFY | V19-OD-08、V19-OD-13 | 假别配置化；年假资格与累计工龄档位分离 |
| OD-10 | LEGACY-V17-OD-10 | MISSING / UNKNOWN | FUTURE_RESERVED | 无当前前端对应项 | 旧薪资后台只作未来后端预留 |
| OD-11 | LEGACY-V17-OD-11 | MISSING / UNKNOWN | FUTURE_RESERVED | 无当前前端对应项 | 旧薪资核算前端不进入导航、路由、搜索或首页 |
| OD-12 | LEGACY-V17-OD-12 | MISSING / UNKNOWN | FUTURE_RESERVED | 无当前前端对应项 | 旧工资条相关设计不进入员工端 |
| OD-13 | LEGACY-V17-OD-13 | MISSING / UNKNOWN | FUTURE_RESERVED | 无当前前端对应项 | 薪资相关报表/占位入口均不可发现 |
| OD-14 | LEGACY-V17-OD-14 | MISSING / UNKNOWN | MODIFY | V19-OD-14 | 权限拆分到查看、编辑、上传、预检、发布、导出、作废等动作 |
| OD-15 | LEGACY-V17-OD-15 | MISSING / UNKNOWN | MODIFY | V19-OD-15 | 只读聚合大屏，不展示个人敏感信息或薪资 |
| OD-16 | LEGACY-V17-OD-16 | MISSING / UNKNOWN | MODIFY | V19-OD-16 | QA 必须按实际文件核验，未生成项保持 MISSING |

## Web Prototype 批次登记

- 路由注册表：`route-to-artifact.json`；36 条已注册稳定路由，V19-OD-15 使用独立 `/display/attendance` Live artifact 路由，V19-OD-16 使用仅供设计交接的 `/qa/handoff`，二者均不进入产品导航。
- 合成数据注册表：`demo-data-registry.json`；统一 2026-07、部门、员工、规则、批次、异常、余额与月结锚点。
- 客户演示链路：`demo-flow.md`；五条主链路均使用 `PROTOTYPE_SIMULATION`。
- 共享实现：`assets/v19-prototype.css`、`assets/v19-prototype.js`；仅供 V19-OD-02～14 的原型联动。

## 当前边界

- 唯一活动基线：V19-OD-00 项目设计 brief 及其配套索引、交接、假设和 QA 记录。
- 最新完成：V19-OD-16 全项目 QA 与设计交接；V19-OD-01～15 均为 `COMPLETE`，不改变 V19-OD-00 的活动基线身份。
- 已替代：V19-OD-00-PRECHECK；原文件保留不修改。
- 当前 V1.9 设计交付已闭环；不存在自动继续执行的下一项。
- 当前前端禁区：薪资、工资条及任何暗示其存在的导航、路由、搜索结果、通知、报表、占位页或员工入口。
