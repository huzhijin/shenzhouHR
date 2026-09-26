# 2026-08 考勤业务规则发布文档索引

本文档集交付 OpenSpec change `business-rules-alignment-2026-08` 的文档任务 12.1～12.8，并记录 12.9～12.12 的本地就绪证据与真实外部关闭条件。

## 口径与状态

- 业务规则和验收标准以 [OpenSpec change](../../openspec/changes/business-rules-alignment-2026-08/proposal.md) 及其 `specs/` 为准。
- [客户业务决策签字稿](../../.umadev/explore/FINAL-BUSINESS-DECISIONS-SIGNOFF.md) 中的 17 项决策均作为已确认事实。
- 技术对象以仓库当前实现和 OpenSpec 为准。早期草案曾以“V32”作为占位编号；现 OpenSpec、迁移文件和部署文档均统一为 `V48`。
- 本文档集已经准备部署和回滚步骤，但不表示已安排窗口、已部署预发布、已部署生产或已完成 24 小时观察。OpenSpec 12.9～12.12 必须由实际外部动作和证据分别关闭。

## 文档清单

| OpenSpec 任务 | 文档或契约 | 用途 |
|---|---|---|
| 12.1 | [OpenAPI 契约](../../api/openapi.yaml) | 补卡提交、配额查询和 HR 批准接口 |
| 12.2 | [V48 数据库结构说明](database-schema-v48.md) | 表、字段、约束、权限和只读核验 SQL |
| 12.3 | [候选发布说明](release-notes.md) | 公式变化、新规则、兼容性和上线门禁 |
| 12.4 | [迟到转旷班 HR 沟通方案](late-to-absence-communication-plan.md) | 统一公告口径、时间线和 FAQ |
| 12.5 | [补卡申请流程](punch-correction-workflow.md) | 提交、配额、审批、生效和人工例外 |
| 12.6 | [仓库 README](../../README.md) | 签字稿、OpenSpec 和本套文档的入口 |
| 12.7 | [部署检查清单](deployment-checklist.md) | V48 → 代码 → 得力调度配置的分阶段门禁 |
| 12.8 | [分阶段回滚方案](rollback-plan.md) | 配置、代码、数据和业务结果的逆序恢复 |

## 相关实现证据

- 正向迁移：[V48__business_rules_alignment_schema.sql](../../backend/src/main/resources/db/migration/V48__business_rules_alignment_schema.sql)
- 数据库回退脚本：[ROLLBACK_V48__business_rules_alignment_schema.sql](../../backend/src/main/resources/db/migration/ROLLBACK_V48__business_rules_alignment_schema.sql)
- 补卡人工例外：[punch-correction-manual-override.md](../operations/punch-correction-manual-override.md)
- 公式对比脚本：[compare-attendance-rate-formulas.sql](../../deploy/mysql/sql/compare-attendance-rate-formulas.sql)
- 发布尾项本地就绪证据：[release-tail-readiness-2026-08-17.md](../verification/business-rules-alignment-2026-08/release-tail-readiness-2026-08-17.md)
