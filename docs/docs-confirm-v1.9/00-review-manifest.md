# 神州 HR V1.9 文档确认清单

> 阶段：`docs_confirm / AWAITING_HUMAN_CONFIRMATION`  
> 生成日期：2026-07-24  
> 正式候选基线：`docs/神州HR考勤与薪资核算系统_PRD_V1.9.docx` + 当前任务明确要求 + 本目录及 `docs/v1.9-rebaseline/`。  
> 门禁：**禁止进入源码实现**，等待人工确认。

本次恢复承接上一轮已经完成的 V1.9 文档包和 `FINAL_DOCS_CHECK_OK` 结果。上一轮因 Codex 网络流断开而中止，不是文档、代码、MySQL 或校验失败；本次只增量加入本机 MySQL 后续实施授权与验收合同，并重新执行最终文档一致性检查。

## 1. 版本声明

**V1.9 已取代 V1.7。**

- `docs/docs-confirm/` 保留为 V1.7 历史确认资料，其旧 `docs_confirm/PASSED` 不适用于 V1.9。
- `../open-design-prompts.md` 保留为 V1.7 历史提示词。
- PRD V1.7 和旧 UmaDev `t1/failed` 只作差异证据。
- 不删除、不覆盖上述历史文件。

## 2. 本轮交付物

### 审计与差异

- `../v1.9-rebaseline/00-evidence-inventory.md`
- `../v1.9-rebaseline/01-current-state-audit.md`
- `../v1.9-rebaseline/02-retain-change-add-retire-matrix.md`
- `../v1.9-rebaseline/04-v17-v19-delta-and-legacy-scan.md`

### 规格与架构

- `../v1.9-rebaseline/03-attendance-punch-excel-import.md`
- `../v1.9-rebaseline/05-domain-rule-permission-architecture.md`
- `../v1.9-rebaseline/06-api-delta-and-v3-migration-plan.md`
- `../v1.9-rebaseline/07-vertical-slice-roadmap.md`
- `../v1.9-rebaseline/08-open-design-v1.9-integration.md`

### 验收与追踪

- `../v1.9-rebaseline/09-acceptance-criteria.md`
- `../v1.9-rebaseline/10-requirement-test-traceability.md`

### 确认包

- `01-v1.9-specification-summary.md`
- `02-decisions-and-open-questions.md`
- `03-local-mysql-development-and-test-plan.md`
- `design-tokens.json`
- `design-tokens.css`

### Open Design 提示词

- `../open-design-prompts-v1.9.md`：已纠正事实优先级；Open Design 的“不解析 DOCX”只保留为工具局部约束。

## 3. 已核验事实

- PRD V1.9 正文已实际抽取，已有 40 页渲染逐页检查；未覆盖原文件。
- 离线考勤模板已实际检查工作表、字段、验证、样式、公式、宏和外链。
- Open Design V19-OD-00～16、route map、Token、组件、页面状态、演示链路和未验证清单已逐项读取。
- 当前前端、后端、V1/V2、OpenAPI、测试、演示模式、部署模板和 `.umadev` 状态已只读审计。
- 已再次确认 `V1__identity_organization_authorization_audit.sql` 与 `V2__baseline_authorization_catalog.sql` 实际存在；两者保持不可修改，V1.9 只允许 V3+ 前向增量迁移。
- 已新增本机 MySQL 的授权范围、账号分权、建库、Flyway、波次表映射、合成数据、生命周期、真实联调、恢复和 MySQL 8.4 LTS 上线验证合同。
- 全仓库旧口径已扫描，命中和未命中均有登记。

## 4. 未验证声明

本轮没有执行或宣称通过：

- 前端 lint/test/build 或后端 Maven test；
- 本机 `shenzhou_hr_dev` / `shenzhou_hr_test` 建库、账号、Flyway、Spring Boot、API 和前端真实联调，当前统一为 `NOT_RUN`；
- MySQL 8.4 LTS 上线复验；
- 致远、得力或生产数据库联调；
- Nginx/systemd 目标机验证；
- Open Design 真实浏览器多断点截图。

Open Design 当前仍为 `VISUAL_CAPTURE_NOT_AVAILABLE`；后续 `preview_confirm` 是强制门。

## 5. 人工确认建议

请按顺序审阅：

1. `01-v1.9-specification-summary.md`：业务边界是否准确；
2. `../v1.9-rebaseline/04-v17-v19-delta-and-legacy-scan.md`：V1.7 失效项是否完整；
3. `../v1.9-rebaseline/05-domain-rule-permission-architecture.md`：领域、规则和权限不变量；
4. `03-local-mysql-development-and-test-plan.md`：本机数据库范围、凭据边界、波次表映射和真实验证矩阵；
5. `../v1.9-rebaseline/06-api-delta-and-v3-migration-plan.md`：契约与迁移方向；
6. `../v1.9-rebaseline/08-open-design-v1.9-integration.md` 与 `design-tokens.json/css`：UI 接入合同、候选 Token 与后续 `preview_confirm` 门；
7. `../v1.9-rebaseline/09-acceptance-criteria.md` 与 `../v1.9-rebaseline/10-requirement-test-traceability.md`：是否可直接转测试；
8. `02-decisions-and-open-questions.md`：外部验证门是否可接受。

人工确认本包只关闭 `docs_confirm`，不自动授权任何源码实现；波次 1 仍须另行收到明确授权，且不得自动进入其后波次。
