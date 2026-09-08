## Context

当前报表 GET 走 `RealtimeAttendanceReportSnapshotService`：请求时整月核算，结果只放在进程内最多 30 分钟的缓存里，`projectionVersion` 实际是 `LIVE-{digest}`。得力/OA 默认同步已是每天 00:00 与 12:00，但查询仍会因为缓存过期、多实例、跨日 `dataAsOf` 窗口和预览结果而改变数字。

仓库里已有追加式 `attendance_report_projection` 与 `AttendanceReportProjectionPublisher`（公司行锁、按摘要幂等、旧版不改）。实时改造后默认读取不再用这些表。方案 B 把「完整核算结果」重新落到这套表上，但读取语义是「最新钉住快照」，不是人工发布门。

参见 [proposal.md](proposal.md) 与 [specs/realtime-report-query/spec.md](specs/realtime-report-query/spec.md)。

## Goals / Non-Goals

**Goals:**

- 同一公司月在重新计算之前，报表、月矩阵、看板和导出看到同一组业务数字，跨进程重启仍保持。
- 没有快照时第一次查询自动完整核算并钉住；用户不必先点发布。
- 「重新计算」仅 `ATTENDANCE_REPORT:REFRESH` 可执行（系统管理员、HR 管理员）；高管、部门经理、普通员工不可见也不可调用。普通 GET / 页面重载不重算。
- 来源水位新于快照时只提示，不改数字。同步任务不触发重算。
- 每次请求重新解析授权，快照内存的是未过滤的公司月事实。

**Non-Goals:**

- 不按员工日增量补丁（方案 D）。
- 不把关账月做成不可再重新计算的冻结（方案 A）。
- 不改得力/OA 同步 cron，也不在同步成功后自动重新计算报表。
- 不修失败日 skip 的确定性（单独跟踪），但预览/超时结果不得落库。
- 不把员工自助「今日打卡/本人记录」改成钉住快照；那条链路仍可读最新已提交证据。
- 不引入 Redis 或其他新外部缓存。

## Decisions

### Decision 1: Reuse projection tables as the pin store

钉住快照写入现有 `attendance_report_projection` 及日/异常/OA/账户事实表，经现有 publisher：公司锁、摘要幂等、只追加、旧行不更新。查询默认读该公司月最新 `PUBLISHED` 且 `formula_catalog_version` 等于当前计算器版本的投影。

不新建第三套事实表，避免实时结果与落库结果再次分叉。人工 `POST /attendance-reports/publications` 仍可兼容保留，但不作为查看前置，也不在默认界面暴露。

备选：独立 pin 表。拒绝，因为现有投影已经是不可变公司月事实，且查询/导出 Mapper 已能按版本读。

### Decision 2: GET materializes once; recalculate is a separate write and a separate button

```
GET 报表/月矩阵/看板
        │
        ├─ 有合格 pin ──► 读库存事实 → 按当前 scope 过滤 → 返回
        │                    另读当前得力/OA 水位，只用于「来源已更新」
        │
        └─ 无合格 pin ──► 完整核算 → publisher 落库 → 返回
                              公司月串行，预览不得写入

POST 重新计算(company, period)
        │
        └─ 完整核算 → publisher 追加新版本 → 默认查询切到该版本
```

页面上的「刷新数据」不得再触发整月重算（它最多是重新 GET 已钉住的快照）。HR 管理员和系统管理员单独增加「重新计算」按钮。新增 `POST /api/v1/attendance-reports/recalculate`，必须同时具备 `ATTENDANCE_REPORT:READ` 与 `ATTENDANCE_REPORT:REFRESH`，body/query 为 `companyId` + `period`。无 REFRESH 时返回 403，不核算、不写投影。成功返回新 token 与来源截止；随后 GET 不带旧 token 时读最新 pin。

角色绑定：

| 角色 | 看报表 | 「重新计算」 |
|------|--------|----------------|
| `SYSTEM_ADMIN` 系统管理员 | 是 | 是 |
| `HR_ADMIN` HR 管理员 | 是 | 是 |
| `EXECUTIVE` 高管 | 是 | 否 |
| `DEPARTMENT_HEAD` 部门经理 | 是 | 否 |
| `EMPLOYEE_SELF` 普通员工 | 是（本人范围） | 否 |
| `MANUFACTURING_CENTER_SUPERVISOR` | 是 | 否 |

V14 已把 `ATTENDANCE_REPORT:REFRESH` 授给 `SYSTEM_ADMIN` 与 `HR_ADMIN`。本轮不再扩授；高管、部门经理、普通员工、制造中心主管都不得获得该能力。

没有合格 pin 时，具备 `ATTENDANCE_REPORT:READ` 的第一次 GET 仍可物化一份快照（这不是用户点的「重新计算」）。之后只有 REFRESH 角色能替换它。界面上「重新计算」仅在当前会话含 `ATTENDANCE_REPORT:REFRESH` 时显示；响应 `allowedActions` 含 `REPORT_RECALCULATE` 供前端判断。没有该动作的人看不到该按钮。

携带 `expectedProjectionVersion` 的 GET 仍只返回该版本或 `ATTENDANCE_REPORT_SNAPSHOT_CHANGED`，避免翻页混版。

备选：把现有「刷新数据」改成重算。拒绝，因为刷新语义会被理解成重新加载页面，且高管/经理也可能去点。备选：GET `?refresh=true`。拒绝，避免缓存/重试把只读请求变成写。

### Decision 3: Freeze dataAsOf inside the pin

核算时的 `dataAsOf` 写入快照后不再随「今天」前进。当月窗口在重新计算前保持钉住当天的知识截止，避免隔夜打开报表自动多出一天。重新计算用新的请求时间作为新快照的 `dataAsOf`。publisher 已拒绝更旧的 `dataAsOf` 后继，重新计算路径必须使用不早于当前 pin 的截止时间。

### Decision 4: Stale means source watermark newer than pin, not auto-recalc

GET 在读 pin 之外再查一次当前已提交得力/OA 水位（已有 source version 查询）。水位新于 pin 记录的截止时，响应带出来源新鲜度；仅对有 `ATTENDANCE_REPORT:REFRESH` 的人显示「来源已更新，可重新计算」。00:00/12:00 同步只推进水位，不调用核算。

比较对象是已提交水位，不是远程供应商时钟。未配置年龄阈值时，不把 pin 标成过期错误，只提示可重新计算。

### Decision 5: Only a complete current-formula snapshot is a pin

预览、超时、inflight 半成品不得调用 publisher。重新计算失败时保留旧 pin。

升级后若最新投影的 `formula_catalog_version` 不是当前计算器版本，视为无合格 pin，第一次 GET 重新核算并追加。这样不会把实时改造前的空/零值投影当成钉住结果。当前公式版本是 `FULL_CALCULATION_OVERTIME_CLASSIFICATION_V2`。

### Decision 6: Authorization stays off the stored facts

库存的是整公司月不可变事实。每个 GET/重新计算仍先校验能力与公司授权，再按当前组织/本人范围和请求筛选取交集后组装报表。公司级合计只有 COMPANY scope 可见。不把 principal 或 scope 写入投影。

### Decision 7: One latest pin for official consumers

`AttendanceReportQueryService`、月矩阵、看板、导出创建都通过同一「读最新合格 pin / 否则物化」入口。员工自助今日记录不走该入口。

进程内 30 分钟缓存可保留为读 pin 之后的装配缓存，但不再作为唯一真相；重启后以数据库 pin 为准。

## Risks / Trade-offs

- [第一次查询仍可能很慢] → 冷查询仍是整月核算，预算保持 5 秒 p95 目标；超时不落库，前端继续等待/失败提示，不用预览冒充 pin。
- [并发首次 GET 重复核算] → 沿用公司月 inflight future + publisher 公司锁；相同摘要幂等返回已有投影。
- [失败日 skip 被钉进快照] → 本轮不修 skip；有权限的人可点重新计算。若整月失败关闭则不覆盖旧 pin。
- [写路径出现在首次 GET] → 仅无合格 pin 时写入；重新计算走 POST。只读用户第一次打开会触发系统落库，不视为人工发布。
- [旧零值投影被当成 pin] → 按当前 formula 版本过滤；不匹配则重新物化。
- [中午同步后数字仍旧] → 这是目标行为；用「来源已更新」提示，避免午餐后报表悄悄变。

## Migration Plan

1. 部署后不跑一次性全量核算。某公司月第一次被查询时物化。
2. 保留原投影表和 publisher；回退旧制品后实时查询恢复为纯内存核算，已写入的投影行不删除。
3. 前端为 HR/系统管理员增加「重新计算」（POST recalculate）；不再用「刷新数据」触发整月重算。
4. 得力/OA 同步保持 00:00 与 12:00，不在本变更中改 cron。

## Open Questions

无阻塞项。月结后禁止重新计算（方案 A）留待后续；若现场要把 12:00 同步后自动出新表，再另开变更把「同步成功触发重新计算」做成可选开关，默认关闭。
