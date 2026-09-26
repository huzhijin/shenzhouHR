## 1. 审批中 OA 生效

- [x] 1.1 将 `findEffectiveOaDocuments` / `ForEmployee` 与 `findReportableOaDocuments` / `ForEmployee` 的 `source_status` 扩为 `APPROVED, MODIFIED, SUPPLEMENTED, UNKNOWN`；仍取 `version_rank = 1`；`REVOKED` 不进
- [x] 1.2 加班仍要求 `activation_decision = ACTIVATED` 且 `overtime_type` 非空；更新 `OaDocumentLatestVersionSqlTest` 与 orchestrator 单测
- [x] 1.3 月历 `EFFECTIVE_OA_STATUSES` 加入 `UNKNOWN`；跨夜外出按日历日给上下午打「外出」（居军 8/19–20 夹具）
- [x] 1.4 计算器：审批中外出/请假/出差/免打卡覆盖的应出勤日 `missingPunchCount` / 旷工为 0，不写漏刷异常
- [x] 1.5 单测：pending outing 两天无卡 → 格子外出、异常无漏刷；该单批准后仍一行覆盖、不双计

## 2. 人事预加按种类让路

- [x] 2.1 核算当天若已有同种类 OA（外出/出差/假/加班/补签），忽略人事 `dayTypes` / `overtimeHours` / 对应补卡；其它种类人事仍生效
- [x] 2.2 无对应 OA 的人事行不改（夹具：张衡 `SZSZ0003` 8/11、8/26 外出仍显示外出）
- [x] 2.3 单测：同一天 HR 外出+OA 外出只出一份外出；同一天 HR 加班 4h + OA 2.5h → 2.5h 且外出人事仍在

## 3. 加班小时 0.5 网格

- [x] 3.1 从 `allocateFormOvertime`、`recognizedMinutes`、`formOvertimeFromEvidence` 去掉 `capToLastPunch`；无卡加班单仍出时长
- [x] 3.2 工作日继续扣已发布 WORK 与餐窗；结果分钟为 30 的倍数
- [x] 3.3 查询页 `minutesToHours` 与报表中心财务格改用 0.5 步长十进制展示，禁止 `63.400000000000006` 和 `{hours || ''}` 直接打 JS number
- [x] 3.4 单测：`18:00–21:00` 夏令有卡 21:12 仍 2.5；无卡仍 2.5；月合计单元格不是 float 尾巴

## 4. 叶剑挂昇州与月度工时全员

- [x] 4.1 任职修复：`SZSZ0000` 叶剑当前任职改到昇州；`employee_id` 与用户名不变；免打卡仍挂此人（写入修复脚本，见第 6 组）
- [x] 4.2 `work-hours` 人列表改为在职 directory left join 日事实；无事实的人仍出行动小时为 0
- [x] 4.3 免打卡应出勤无卡：月历白格正常出勤，不写漏刷（与神州免打卡一致）
- [x] 4.4 单测：无打卡免打卡人员出现在月度工时；directory 有而 daily_fact 无的人仍有行

## 5. 每日加班全公司

- [x] 5.1 部门为空时财务加班 SQL 不加 `organization_id IN`；去掉 `financeOvertimePeopleWhere` 里重复的 `dailyOvertimeTreatment`
- [x] 5.2 全公司第一页须在接口超时内返回；失败返回错误，禁止静默空表
- [x] 5.3 前端报表中心 / 查询页：不选部门仍请求并展示该公司加班人；选部门才收窄
- [x] 5.4 单测或契约：无 `organizationId` 时 count/list 仍按公司投影过滤且 `recognized_overtime_minutes > 0`

## 6. 诊断与修复脚本（进部署包）

- [x] 6.1 新增预览脚本：列出 OA 外出覆盖日仍漏刷、审批中加班未进财务、小时非 0.5、月度工时缺人、人事与 OA 同种类重叠；默认不写库
- [x] 6.2 `APPLY=1`：叶剑改挂昇州；人事让路只清「已有 OA 的种类」；禁止改张衡无 OA 外出行；禁止回拨 OA 水位
- [x] 6.3 `APPLY=1` 后按列出的 `employeeIds` + 日期窗口调现有重算接口，不要求点整公司整月
- [x] 6.4 部署说明：预览 → 确认张衡保留 → APPLY → 抽检居军/财务小时/叶剑/每日加班不选部门

## 7. 回归

- [x] 7.1 相关核算、月历、查询页、人事调整、SQL 测试全绿
- [x] 7.2 对照夹具：居军 8/19–20 外出；张衡 8/11、8/26 外出保留；邵泽祥类合计无 float 尾巴；叶剑在昇州月度工时
