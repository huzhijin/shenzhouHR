# 神州HR 首轮文档交付清单

> 阶段：`docs_confirm / PASSED`；首次生成日期：2026-07-19；人工确认日期：2026-07-20；正式需求基线：`神州HR考勤与薪资核算系统_PRD_V1.7.docx`。本目录是从 PRD V1.7 提炼的设计输入，不替代、不修改原始 PRD。

## 阶段边界

- 首轮已完成需求校准、总体架构与 UI/UX 设计；2026-07-20 用户明确确认全部文档并授权进入后续实现。
- 当前门状态：`docs_confirm / PASSED`。技术栈已冻结，执行计划见 `11-phased-implementation-plan.md`。
- 真实致远、得力和生产数据库仍未连接、写入或修改；生产部署、推送或 PR 仍需后续明确授权。

## 已读取资料

| 资料 | 实际读取范围 | 结论 |
|---|---|---|
| `AGENTS.md` | 全文 | 遵守现有仓库增量变更约束 |
| `docs/神州HR考勤与薪资核算系统_PRD_V1.7.docx` | 正文、72 个表格、5 个嵌入图、附录、验收标准、架构准入结论 | 可完整读取；作为正式基线 |
| `/Users/huzhijin/Downloads/V80数据字典.pdf` | 433 页文本索引；重点核对组织、人员、关系、岗位、附件与文件表；抽查相应页面图像 | 标准对象 ID 为 `BIGINT`；附件引用和文件对象分离；不能据此推断自建表字段 |
| 致远开发平台官方文档 | 数据字典说明、组织模型 REST、附件集成、数据库规范 | 官方不建议直接操作数据库；标准对象优先走接口；本项目自建单据只读且逻辑映射 |
| 得力 E+ 云考勤官方文档 | 鉴权、初始化、游标查询、人员字段、错误码、破坏性接口 | `next_id` 必须原样持久化；单页上限 500；禁止删除员工接口；坐标系未明确 |
| 企业官网与官方 Logo | 官网品牌上下文；SVG 视图、宽高比和色值 | Logo 比例约 163.0405:36.87；蓝 `#25449A`、红 `#E60012`，仅作设计参考 |

## 42 项交付物索引

| # | 交付物 | 路径与章节 |
|---:|---|---|
| 1 | PRD 理解与差异说明 | `01-prd-understanding.md` |
| 2 | 需求追踪矩阵 | `02-requirements-traceability.md` |
| 3–12 | 上下文/领域/组件/部署/数据流/同步与计算时序/月结与工资状态机 | `03-architecture-and-flows.md` |
| 13–18 | 员工绑定、组织、考勤、工资、权限、ER 模型 | `04-domain-data-model.md` |
| 19–21 | 外部适配、可靠同步、OpenAPI 规划 | `05-integrations-and-openapi.md` |
| 22–24 | 威胁模型、权限矩阵、审计方案 | `07-security-and-permissions.md` |
| 25–28 | 可观测性、备份恢复、技术选型、ADR | `06-operations-technology-adr.md` |
| 29–34 | UI/UX、品牌、Token、页面、工作台、导航 | `08-uiux-specification.md`、`ui/design-tokens.json`、`ui/design-tokens.css` |
| 35 | 核心页面线框 | `09-wireframes.md` |
| 36–38 | 组件状态、响应式、UI 权限与脱敏 | `08-uiux-specification.md` |
| 39 | 分阶段实施计划与依赖 DAG | `11-phased-implementation-plan.md` |
| 40 | 可转测试用例的验收标准 | `10-acceptance-test-catalog.md` |
| 41 | 风险清单 | `11-phased-implementation-plan.md` |
| 42 | 待确认事项 | `11-phased-implementation-plan.md`、`../decisions/OPEN-DECISIONS.md` |

## 架构准入结论

**已通过，可以进入实现。** PRD V1.7 已具备模块边界、核心规则、权限基线、验收条目和状态机输入；技术栈与阶段计划已经人工确认。致远自建表物理字段、真实接口凭据、网络白名单、坐标系、法定政策数据和生产资产按 `../decisions/OPEN-DECISIONS.md` 管理：它们不阻断工程骨架与领域端口实现，但会阻断对应真实联调、工资发布或生产上线。

## 文档校验口径

- `00` 文件对 42 项输出逐项建立路径索引。
- Mermaid 图使用 `flowchart`、`sequenceDiagram`、`stateDiagram-v2`、`erDiagram` 标准语法。
- 需求矩阵每个 PRD 功能组均落到模块、页面、API 族与验收 ID。
- 设计 Token 同时提供 JSON 与 CSS；每个颜色表面拥有 `on-*` 前景并记录对比度。
- 待确认事项不超过 5 项，并同步登记于追加式决策台账。
