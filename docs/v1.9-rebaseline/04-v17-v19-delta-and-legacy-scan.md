# V1.7 → V1.9 差异矩阵与旧口径扫描

## 1. 裁决规则

冲突按以下顺序裁决：

1. 当前任务明确要求；
2. PRD V1.9；
3. 本轮经人工确认的 V1.9 规格、验收与决策；
4. 经人工确认的 Open Design V1.9，仅限视觉、布局、交互和响应式；
5. 现有代码、OpenAPI、迁移和测试；
6. PRD V1.7、旧确认包、旧提示词和旧 UmaDev 状态。

## 2. 主要差异矩阵

| 主题 | V1.7 / 旧资料 | V1.9 基线 | 受影响需求/文档 | API | 表/迁移 | 页面 | 测试与处理建议 |
|---|---|---|---|---|---|---|---|
| 登录 | SSO/IdP 待定 | 独立账号密码；首次改密、锁定、重置、失效、退出 | FR-09；旧 open decision、OpenAPI | 新 `/auth/*`；删除新契约中的 SSO 待定 | V3+ account/credential/session | `/login`、`/access/accounts` | 密码、锁定、撤权、CSRF、会话固定攻击 |
| 组织来源 | 持续同步、正式同步版本 | 致远 Excel 仅期初；发布后本地权威 | FR-01/02；旧架构、wireframe | 退役 `/organization-syncs*`；新增期初导入/本地写 | V1 兼容保留；V3+ import/local version | `/people/import`、organization | 无同步任务；发布幂等、本地维护、审计 |
| OA 边界 | 组织和业务数据均持续接入 | 仅考勤业务单据只读持续接入 | FR-04 | `/sources/oa` 只含请假/加班/外出/出差/补卡 | RawFact/Watermark/SourceDocument | `/sources/oa` | 撤销、补录、重叠、跨日、水位 |
| 任职 | 组织版本附带任职 | 二次入职建新任职周期，历史不覆盖 | FR-01 | employee employment period API | employment_period/prior_service | 员工详情 | 离职空档、再入职、时点匹配 |
| 规则模型 | 规则分散、个人/地区优先 | 类型化模板、版本、作用范围、生效期、试算、发布/回滚 | FR-03/05/07 | `/rule-policies/*` | policy_* | `/rules` | 冲突、优先级、快照、冻结保护 |
| 夏冬令 | 可能以夏令组/冬令组表达 | 同组班次版本 + 生效日期 | FR-03 | shifts/versions | shift_version | `/rules/shifts` | 12/31、切换日、跨日 |
| 餐扣 | 非所有组默认 | 所有组默认启用；工作日/周末/节假日分别配置 | FR-03/05 | meal-deduction config | policy version payload/typed table | `/rules/attendance-policy/:id` | 窗口、时长、触发条件组合 |
| 迟到宽限 | 旧月度豁免口径不完整 | 每自然月 1 次，`0 < late <= 15`，跨组默认不重置 | FR-03/05 | grace policy | usage ledger + policy snapshot | policy/detail/explanation | 0、15、15+、跨组、并发命中 |
| 单边缺卡 | 易落为整日旷工 | 7 自然日内待补正；逾期仅对应工作段旷工 | FR-05/06 | missing-punch/recalc | evidence/segment/exception | daily/exceptions | 单段、多段、有效单据、逾期、管理员修正 |
| 证据链 | 来源接入与计算耦合 | 原始事实→标准化/匹配→有效事件→统一计算 | FR-04/05 | source/import/evidence APIs | raw/normalized/effective/evidence | sources/daily/evidence | 追加不改写、重叠切分、撤销、补录 |
| 离线打卡 Excel | 不存在 | V1.9 补充基线；标准/厂商 `.xlsx`、映射、跨来源去重 | 当前要求 + 03 专题 | `/attendance-punch-imports/*` | batch/row/mapping/fingerprint | `/sources/attendance-excel` | 哈希、指纹、50k、部分成功、冻结 |
| 假别 | 年假为主，其他细节不足 | 所有假别版本化，默认工作日 | FR-07 | leave policies | leave_policy_version | `/rules/leave` | 单位、最小粒度、资格、材料、结转、销假 |
| 年假 | 满一年固定 5 天，跨年待定 | 本公司 12 完整月资格；累计工龄 5/10/15；周年发放/失效 | FR-07 | annual leave preview/grant | qualification/tier/grant/ledger | `/rules/annual-leave`、`/me/leave` | 闰日、周年前日/当日、跨档、再入职、冻结 |
| 时间账户 | 最终余额倾向 | 期初导入 + 不可变流水 | FR-07 | time-account imports/ledger | account/ledger/opening batch | `/rules/time-accounts` | 重复发布、单位换算、余额重算 |
| 薪资 | 完整 P0 前台、税社保、工资条 | P0-B 后端预留；前端完全不可发现 | FR-PAY 边界重写 | PAYROLL 独立域默认拒绝 | 后续独立 V3+ 波次 | 无当前 route | 前端 route/search/menu 扫描为 0；服务端越权拒绝 |
| Open Design | 旧 V1.7 页面可见薪资/同步 | V19 页面只作参考，原型动作均非实现 | design handoff | route 需参数化/鉴权 | 无 | OD01–15 参考；OD16 不生产 | preview_confirm 真实浏览器验收 |

## 3. 全仓库旧口径扫描

扫描排除了 `node_modules/`、`frontend/dist/`、`backend/target/`、`.docx-qa/`、DOCX/XLSX/ZIP 二进制和临时缓存。

### 3.1 明确命中

- `README.md:3`：仍以 PRD V1.7 和 `docs/docs-confirm` 为正式基线。
- `api/openapi.yaml:99`：仍写 SSO 决策待冻结。
- `docs/decisions/OPEN-DECISIONS.md`：SSO/IdP 待定项已被 V1.9 裁决，应历史关闭。
- `V2__baseline_authorization_catalog.sql`、`CapabilityCodes.java`：`MASTER_DATA:SYNC_PREVIEW`。
- `V1__identity_organization_authorization_audit.sql`、dev seed：`formal_sync_batch_id`、`projection_batch_id` 旧同步命名。
- `docs/docs-confirm/`：同步 API/流程、完整薪资前台、旧年假、旧 UI 和旧验收。
- `docs/open-design-prompts.md`：SSO 待定、持续组织同步、薪资导航/核算/工资条均属 V1.7 历史。
- `scripts/build_prd.py:761,819-821,963,985` 等：历史 PRD 生成器仍含组织持续同步、旧 5 天年假和完整薪资前台口径；该脚本不再是 V1.9 生成源，保留作历史证据，后续若重新启用必须先按 V1.9 重写。
- `output/shenzhouHR-architecture.md:125`：历史架构输出仍称生产 SSO 待定；标记为 V1.7 非权威产物，不得覆盖本地账号密码裁决。

### 3.2 未在当前产品源码命中

- 未发现写死“夏令组/冬令组”或 `SUMMER_GROUP/WINTER_GROUP`。
- 未发现已实现的旧年假算法。
- 未发现当前可发现的薪资菜单、route、页面、普通 API 或 PAYROLL capability。
- 前端产品名含“薪资核算系统”不等于功能入口；后续仍需持续扫描菜单、route、搜索、通知、导出和埋点。

### 3.3 Open Design 命中及解释

- V19 产品 artifact 未发现 SSO 待定、持续组织同步或当前薪资入口。
- 原型内的 demo ID、localStorage、模拟发布/重算/月结和 `data-prototype-unwired` 都是原型证据，生产必须参数化并接真实 API。
- `design/DESIGN-HANDOFF.md` 的 production source-of-truth 表述与本轮优先级冲突，按本轮要求降级处理。

## 4. 旧资料处理

- `docs/docs-confirm/` 和 `docs/open-design-prompts.md` 原文件不删除、不覆盖。
- 新 `docs/docs-confirm-v1.9/00-review-manifest.md` 明确它们“已由 V1.9 取代”。
- 后续实现不得引用旧 `docs_confirm/PASSED` 作为 V1.9 进入源码的授权。
- V1/V2 旧列和 capability 只做兼容退役，不修改已执行迁移。

## 5. 扫描门禁

每个后续波次至少执行：

1. 当前前端 route/menu/search/export/payroll 发现性扫描；
2. 组织同步、SSO 待定、夏冬令硬编码、旧年假常量扫描；
3. 新 V3+ 迁移文件面和 V1/V2 哈希不变检查；
4. 合成数据、凭据名和值、敏感工资/员工信息检查；
5. 实际测试/构建结果与文档声明一致性检查。
