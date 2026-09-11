## Context

核算 SQL `findEffectiveOaDocuments` / `findReportableOaDocuments` 只取 `source_status = 'APPROVED'`。审批中 OA（`col_summary.state=0` → `UNKNOWN`）会入库，但不盖漏刷、不算加班小时。月历 `EFFECTIVE_OA_STATUSES` 同样只要已批准。9/2 用人事调整把未结束 Excel 预入账（原因 `未结束OA预入账-20260902`），加班小时会覆盖当天；外出 `dayTypes` 与以后的 OA 会叠。张衡 `SZSZ0003` 8/11、8/26 外出是脚本额外写入的，查询表里没有 OA。

加班小时路径在 `OvertimeMealDeductions` 注释里写不看打卡，但 `capToLastPunch` 仍按午后到次日 06:00 的最后一刷截区间：无卡则小时 0，有卡则出现 `.2` / `.7`。财务合计用 `double` 累加，报表中心把原始 number 打到单元格，出现 `63.400000000000006`。

月度工时从 `attendance_report_daily_fact` 聚合出人。没有日事实的人（免打卡无班次、叶剑被改挂江苏神州）从昇州表里消失。每日加班全公司查询在空部门过滤时过重，失败时看起来像必须先选部门。

## Goals / Non-Goals

**Goals:**

- 审批中最新版 OA 与已批准一样参与覆盖和加班小时；同一 `source_business_key` 批准后覆盖，不双计。
- 人事预加按种类让路给 OA；无 OA 的人事行保留。
- 加班小时 = 取整区间 − 工作日 WORK − 餐，必为 0.5 倍数；展示无浮点尾巴。
- 叶剑 `SZSZ0000` 挂昇州；月度工时该公司在职全员都有行。
- 每日加班不选部门也能出该公司全量。
- 部署包脚本能预览并修复现网数据缺口，再按人重算。

**Non-Goals:**

- 不把 Excel 当 OA 单据导入，不伪造批准。
- 不回拨 OA / 得力水位。
- 不改 OA 插件填单、工资引擎、芯越组织。
- 不把四家公司合成一张每日加班。
- 不撤张衡无 OA 的外出人事行。
- 不要求操作员再点一次「整公司整月重算」作为唯一修复手段。

## Decisions

### Decision 1: Latest OA version is effective when pending or approved

把 `findEffectiveOaDocuments` 与 `findReportableOaDocuments` 的 `source_status = 'APPROVED'` 扩成 `IN ('APPROVED','MODIFIED','SUPPLEMENTED','UNKNOWN')`。仍按 `source_business_key` 取 `version_rank = 1`。撤销（`REVOKED`）最新版不生效。加班仍要求 context 已激活且有类型。

月历 `EFFECTIVE_OA_STATUSES` 同步加入 `UNKNOWN`。异常查询已有 `hideApprovedOaCoveredExceptions` 含 `UNKNOWN`，核算日事实的缺卡/旷工也必须在计算器侧被覆盖单据压掉，否则 pin 里仍写漏刷。

备选：继续只靠人事预加。拒绝，因为预加覆盖不全（居军不在 5 条外出里），批准后还要人工撤。

### Decision 2: Same-day HR yields by kind, not by blanket reverse

核算时：

- 当天已有 OA `OUTING`/`TRIP`/`LEAVE`/`TIME_OFF`/`EXEMPT_PUNCH`/`OVERTIME`/`PUNCH_CORRECTION` 覆盖，则忽略人事调整里同种类的 `dayTypes` / `overtimeHours` / 补卡时刻。
- 该种类没有 OA，人事行照旧（张衡外出）。
- 不按原因整批 `reversed_at` 掉 `未结束OA预入账-20260902`，避免误伤张衡和其它无 OA 预加。

运行时让路优于删行：脚本可把「已被 OA 盖住的种类」从人事 JSON 里清掉以便对账，但无 OA 的行禁止改。

### Decision 3: Drop last-punch capping; format hours as decimals on a 0.5 grid

从 `allocateFormOvertime`、`recognizedMinutes`、`formOvertimeFromEvidence` 去掉 `capToLastPunch`。无卡加班单仍出时长。工作日继续扣已发布 WORK 与餐窗。

查询与报表中心小时：分钟用整数；展示用 `BigDecimal` 或「分/60 后按 1 位小数且必须是 0.5 步长」的格式化（查询页已有 `formatFinanceHours`，报表中心必须同样用，禁止 `{hours || ''}` 直接打 JS number）。若计算后不是 30 的倍数，视为回归。

### Decision 4: Work-hours rows come from roster, facts fill numbers

`work-hours` 的人列表改为与 directory 相同的在职任职（公司 + 授权范围 + 窗口内有效），再 left join 日事实汇总。没有事实的人：小时 0，行仍在。免打卡有班次的人照常出应出勤分钟，格子白。叶剑任职改回昇州后自然进昇州 directory。

叶剑：关江苏神州总经办当前任职，开昇州对应总经办/维修部叶子（以现网昇州组织树为准，脚本预览打印目标 `organization_id`）。`employee_id` 与用户名 `SZSZ0000` 不变；免打卡仍挂此人。

### Decision 5: Company-wide finance overtime must complete without a department

空部门 = 不按 `organization_id IN (...)` 过滤。优化 `countFinanceOvertimePeople` / `listFinanceOvertimePeople`（去掉重复 `dailyOvertimeTreatment`、保证投影+日期+分钟索引路径），分页保持。前端超时与后端查询对齐；失败要报错，不能静默空表。报表中心 `loadFinanceQuerySheet` 全公司第一页必须成功。

### Decision 6: Diagnose/repair scripts ship in the BaoTa package

一个诊断脚本（可拆子命令，默认全跑）+ 明确的 `APPLY=1`：

| 检查 | 修复（仅 APPLY） |
|---|---|
| OA 外出覆盖日仍漏刷 | 不造单；列出 status/pin；触发该人日期重算 |
| 审批中加班未进财务 | 同上，靠代码认 UNKNOWN 后重算 |
| 小时非 0.5 倍数 | 重算（去掉截卡后） |
| 叶剑不在昇州 | 改任职 |
| 月度工时缺在职人员 | 代码改出人后重算该公司受影响人 |
| 人事与 OA 同种类并存 | 让路规则；预览打印；不改张衡无 OA 行 |

读 `/etc/shenzhouhr/shenzhouhr.env`。默认 stdout 预览。禁止 `SET` OA cursor。重算走已有 `POST /api/v1/attendance-reports/recalculate` 的 `employeeIds` + `fromDate`/`toDate`。

## Risks / Trade-offs

[Risk] 审批中加班进财务后，单子被拒 → 下一轮同步最新版 REVOKED，小时消失。→ 可接受；明细仍能在 OA 侧看到历史。

[Risk] 认 UNKNOWN 后与 9/2 人事预加双计。→ Decision 2 按种类让路；脚本列出重叠人日。

[Risk] 月度工时全员让江苏 500+ 行变长、无班次的人小时为 0。→ 业务要求；免打卡有班次的应出勤仍算。

[Risk] 全公司每日加班仍超时。→ 先修 SQL/分页；必要时后端聚合缓存同一 pin，但第一页必须有数。

[Risk] 叶剑改挂后江苏报表少一人。→ 这是要的；脚本预览打印 from/to 组织。

[Risk] 旧 pin 仍是漏刷和 `.2`。→ 部署后对 OPEN 月按脚本列出的人重算，不默认整公司按钮。

## Migration Plan

1. 发后端/前端 jar + 脚本 + 升级说明。
2. 服务器先 `python3 diagnose-*.py` 预览（居军、叶剑、非 0.5 小时、人事/OA 重叠）。
3. 确认张衡 8/11、8/26 在「保留」列表。
4. `APPLY=1` 任职与让路；再按人日期重算江苏/昇州/聚能 8 月 OPEN。
5. 抽检：居军 19–20 外出；财务无浮点尾巴；叶剑在昇州月度工时；每日加班不选部门有数。
6. 回滚=回退 jar；任职脚本提供反向预览。不回滚 OA 水位。

## Open Questions

无。公司选择器保留；「全部公司的」= 选中该公司后全部部门，不是四家合成一张。
