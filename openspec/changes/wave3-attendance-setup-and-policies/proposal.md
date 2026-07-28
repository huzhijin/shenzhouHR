## Why

WAVE-2 已冻结通用 policy、组织、员工、任职周期和期初导入权威，但考勤计算仍缺少可历史重放的考勤组、班次、工作日历和法人隔离的考勤专用策略。早期 WAVE-3 实现既修改了 WAVE-2 `policy_version` 物理/API 合同，又把可变状态与历史内容混在同一行；这会同时破坏 W2 retained、法人隔离、零停机换版和旧日期 digest。

本 change 当前独立验收状态为 RED，apply 任务全部未完成。历史候选 `w3-20260726-1917` 为 `INVALIDATED_NON_FINAL`，只保留诊断价值；任何 `NOT_VERIFIED`、`null` evidence、过时 hash、H2-only、demo-only 或被取消轮次中的窄测试都不能完成本 change。

## What Changes

- 原样保留 V1～V6 与 WAVE-2 通用 policy aggregate/API/schema；W3 不再给 `policy_version` 增列、改唯一键、改 DTO 或通过通用 `PolicyApplicationService` 承载法人策略。
- 新增独立 `attendance_policy_template/scope/scoped_version/lifecycle_event/binding` aggregate。W3 scope/scoped version 以 `legalEntityId` 隔离，binding 只引用 W3 `attendance_policy_scoped_version`。
- 新增稳定地点、考勤组、班次、日历 identity；业务内容以不可变 revision/version 保存，future rollover/deactivate 通过只追加 timeline/lifecycle 事实表达，不原地改历史 `effective_to/status`。
- 考勤组 revision 引用稳定 shift family、calendar family 与不可变 location revision。服务端按 business date 恰好解析一个 group revision、calendar version/day、PUBLISHED shift version 和三类 W3 scoped policy。
- group create/enable 在一个事务内原子 provision `MEAL_DEDUCTION`、`LATE_GRACE`、`MONTHLY_LATE_EXEMPTION` 三类默认 binding；任一缺失或不唯一时整体回滚。
- binding family 稳定归属于 group identity + policy kind，revision 才引用 group revision；所有引用 location 的 group 路径先锁 location identity/current revision，location rollover 在外层锁内完成 group 全集枚举、binary 排序、锁定和二次全集验证，再按既定 group 锁序原子生成全部 successor；不允许快照后插入、跨 revision 悬空、重复 family 或歧义。
- assignment 写入拒绝 cross-legal-entity、inactive group、重叠期间或不可解析/不兼容的真实 shift/calendar/day override。
- 工作日历使用稳定 family、不可变 effective/year version 与不可变 day；day 保存 day type 与可选 explicit shift override，draft day API 明确为 PATCH/upsert。
- durable idempotency 使用 `actor + operation + resource + key + canonical request digest`，reason 进入 digest；业务锁后第二次回查，相同请求精确重放 status/headers/body，不同 payload/reason 冲突。
- simulation 请求只接受 employee、business date、知识时态 `correctionAsOf` 与 typed punches。服务端按该知识截点从 resolver 和只读 authoritative monthly-usage provider 推导配置、跨午夜时间，并按 LATE_GRACE→MONTHLY_LATE_EXEMPTION 的固定优先级解析 0/1/15/16 分钟真值表；客户端不能自报 lateMinutes、usage、policy/shift/calendar。
- OpenAPI 与 Controller 对 method/path/query/header/status/request/response/nullability/unknown-field 做精确双向闭合；所有非固定 catalog 列表稳定分页。
- 前端补齐 W3 lifecycle/history/impact/simulation、真实 403/404、role×capability route/menu/redirect matrix、normal proxy 与独立 prod/demo build；AUDITOR 只读。
- W2/W3 MySQL gate 使用 review-owned structured W2 oracle、全 W3 registry、无歧义长度前缀 retained golden 和唯一 seed oracle；scoped-version 状态只由 lifecycle facts 派生，MONTHLY seed 只接受含 `enabled:true`、`graceMinutes:15` 的 canonical JSON 及预锚定新 digest；同库 V6→target7→latest 与 empty→latest 均需通过。
- 源码冻结后才创建全新 runId；tasks checkbox 使用归一化 hash 与 detached completion metadata。证据严格按 20 个 pre-review leaf gates→registry→互不哈希的 matrix/report→只挑战该 20 项的独立 review→单向 final manifest→post-manifest integrity→20+review+integrity 共 22 项 FINAL 执行。

## Capabilities

### New Capabilities

- `attendance-groups`: 稳定地点/考勤组 identity、不可变 revision/timeline、原子 rollover、期限 assignment、并发、scope 与审计。
- `shift-work-calendars`: 稳定 shift/calendar family、不可变 published version/timeline、IANA 跨日、日历完整性与显式 override。
- `attendance-base-policies`: 独立 W3 scoped policy aggregate、三类默认 binding、权威试算、持久幂等、发布/停用/回滚。
- `wave3-verification`: W2 retained、迁移、MySQL 8.4、normal browser、PAYROLL、源码冻结与防伪证据硬门。

### Modified Capabilities

无。WAVE-2 通用 policy capability 明确不被本 change 修改。

## Impact

- OpenAPI server 保持 `/api/v1`，path item 使用 `/attendance-setup/**`，运行时 URL 为 `/api/v1/attendance-setup/**`。
- 只读核验确认 2026-07-26 授权本机 `shenzhou_hr_dev` 与 `shenzhou_hr_test` 的 Flyway history 均停在 V6；V7 尚未进入持久库，因此可在首次应用前一致重写。V1～V6 source/checksum 绝不修改。
- V7 新建 attendance identities/revisions/timelines、独立 W3 policy aggregate、durable idempotency、权限、索引/约束与固定 oracle seed；不得 ALTER W2 `policy_version`。
- H2 schema、Rows、Mapper/XML、domain DTO、OpenAPI 与 V7 的 W3 列/FK/CHECK/index 必须逐项同形；同时增加 W2 V4 全等门禁。
- 本 change 不实现 W4 来源接入、正式考勤事实/计算、异常/月结、假期、自助、报表、看板或 PAYROLL。
- W3 独立验收真实 PASS 后，本 change 仍不归档或修改 W4；自动交接下一 W4 change 继续总目标，无需等待额外提示。W3 未 PASS 前 W4 持续冻结。
