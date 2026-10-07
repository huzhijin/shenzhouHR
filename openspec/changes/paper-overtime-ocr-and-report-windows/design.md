## Context

OA 与得力定时任务默认仍是 `0 0 0,12 * * ?`，两边都已按水位线增量。自动重算绑在 12 小时槽且必须 OA、得力都成功后才整月跑。考勤报表中心只能选月，「重新计算」只吃 `companyId + YearMonth`，引擎从月初算到今天并整月覆盖发布。加班报表按人聚合；查询加班统计已是单据行，但只含 OA。

纸质加班申请单（人员、日期、起止、事由、勾选加班类型、签字栏）没有入口。OCR 不在本仓库；庆峰插件已有百度云高精度 OCR（注释为 PaddleOCR）和腾讯云备选。员工解析对无人值守入库是精确工号/精确姓名、重名隔离；纸质单必须交互确认。

## Goals / Non-Goals

**Goals:**

- OA 每小时、得力 8/12/18/0，继续增量。
- 纸质加班：主表附件+识别，明细一单一行；手填；模糊匹配；保存门槛；人事可写。
- 加班查询与报表中心加班页改单据明细（含纸质）。
- 报表中心起止日期；近 3 天 / 近一周 / 本月局部重算；自动重算默认近 3 天。

**Non-Goals:**

- 不改 OA 插件填单、得力身份绑定算法、关账月强制重开、工资引擎。
- 不在本仓库提交庆峰或 Paddle 密钥。
- 不把月度工时改成单据明细（工时仍按日事实加总）。
- 不自动把多张照片归并成同一张单。
- 不提供冲突强制保存。

## Decisions

### Decision 1: Cron defaults, watermarks unchanged

| 来源 | 新默认 cron（Asia/Shanghai） |
|---|---|
| OA | `0 0 * * * ?` |
| 得力 | `0 0 0,8,12,18 * * ?` |

只改默认与文档/测试钉死值。`attendance_sync_watermark` / `kq_committed_cursor` 语义不变。部署 env 示例同步改。

备选：只改生产 env 不改代码默认。拒绝，现场会漏配。

### Decision 2: Auto-recalculate per successful scheduled sync, last 3 days

`AttendanceReportAutoRecalcService` 不再用 12 小时双源门。`onScheduledDeliSuccess` 与 `onScheduledOaSuccess` 各自把一次 **LAST_3_DAYS** 任务排进延迟队列（默认 `PT10M`，可配）。延迟窗口内多次成功合并为一次。手工点同步仍不自动重算。关账月跳过。

备选：保持双源门只改 cron。拒绝，18 点得力会落进已完成的下午槽。

### Decision 3: Paper overtime is OVERTIME evidence with origin PAPER

保存后每条明细写入与 OA 加班相同的证据链：`raw_attendance_fact` → `normalized_attendance_record` → `oa_attendance_document`（`document_type=OVERTIME`，`source_status=APPROVED`），`source_business_key` 形如 `PAPER:<lineId>`，独立 `attendance_source`（`PAPER_OVERTIME`）或在公司 OA 源上带 origin 字段。核算已读加班单据，这样查询、日事实、调休入账可复用。

录入过程用 **批次主表 + 明细行**：

- `paper_overtime_batch`：公司、录入人、状态
- `paper_overtime_attachment`：存储对象、文件名、OCR 原文
- `paper_overtime_line`：确认后的人、区间、类型、事由、匹配快照

保存成功才 ingest。附件留在批次上供对照，不按文件各算一笔小时。

备选：只存附件表、核算另写。拒绝，加班统计会看不到。

### Decision 4: OCR adapter is Baidu accurate API labeled Paddle

实现 `OcrPort`：PDF 用 PDFBox 栅格化，图片直送。默认 provider = 百度 `https://aip.baidubce.com/rest/2.0/ocr/v1/accurate`（与庆峰 `BaiduOcrProvider` 同协议）。凭证仅：

```
SHENZHOUHR_OCR_BAIDU_API_KEY
SHENZHOUHR_OCR_BAIDU_SECRET_KEY
```

试跑可把庆峰本地 properties 注入进程环境，**不写入本仓库**。字段解析针对加班申请单：姓名、部门、日期（无年则当年）、起止、事由、加班费/调休/义务加班勾选。勾选 0 或 ≥2 或置信不足 → 类型空。

备选：本机 PaddleOCR Python。拒绝，部署面大；用户明确会给 cloud key。

### Decision 5: Matching is a suggest API, not fail-closed ingest

`POST .../paper-overtime/recognize` 返回每行候选，不写证据。匹配：精确姓名 → 包含/模糊；部门用组织名与路径的规范化包含 + 编辑距离。同分名全部返回（工号、部门全称、在职）。保存时只接受 HR 选定的 `employeeId`。

得力/OA 无人值守解析保持隔离策略，不复用这条交互匹配。

### Decision 6: Date-level overlap, no force save

冲突键 = `employeeId` + 取整区间覆盖到的每个 `Asia/Shanghai` 自然日。与已通过 OA/纸质单、以及同批次未保存行比较。API 无 override。校验失败整批不 ingest。

### Decision 7: Partial month merge in the publisher

`RecalculateRequest` 增加 `window`: `LAST_3_DAYS` | `LAST_7_DAYS` | `MONTH`。引擎按窗口切日，发布时：

1. 读当前 pin 的日事实。
2. 窗口外保留。
3. 窗口内重算并替换（上下文多看 1 天班次/跨夜卡，若该日不在窗口则不写回）。
4. 月宽限：窗口前各日用已存日事实计数，窗口内继续累加。
5. OA/纸质加班单据事实按区间重投影后与日事实对齐。
6. 月合计、工时公式列从合并后的日事实重加。
7. 新 `projectionVersion` 整月替换，禁止新旧日混读。

跨月窗口对每个相交月独立走上述步骤；`CLOSED`/`FROZEN` 跳过并在响应里列出。

报表中心 GET 增加 `fromDate`/`toDate`：只切展示与展示合计，不改 pin。

备选：局部重算仍从月初跑打卡、只少写几天。拒绝，与「计算量要小」不符。

### Decision 8: Report overtime sheet switches to document facts

`AttendanceReportCalculator.overtime` 改为列出 `OaDocumentFact` 中 `OVERTIME`（含纸质），不再 `aggregateDaily` 一人一行。前端去掉部门汇总块。导出跟明细行走。月度工时仍 `aggregateDaily`。

### Decision 9: Capability

新能力 `PAPER_OVERTIME:MANAGE`（及识别/附件读）默认只授 `HR_ADMIN`（`SYSTEM_ADMIN` 可运维需要则授）。查询加班仍用 `ATTENDANCE_REPORT_QUERY:READ`。重算仍用 `ATTENDANCE_REPORT:REFRESH`。

## Risks / Trade-offs

[Risk] 小时级 OA 同步 + 3 天重算仍可能叠请求。→ 延迟合并；窗口远小于整月。

[Risk] OCR 勾选不稳。→ 空类型不能存，人事手选。

[Risk] 正反面变成两行。→ 不自动合并；人事删行。

[Risk] 同日两段加班（下午+晚上）被日期重叠拦住。→ 已确认按日冲突，不按时段。

[Risk] 纸质调休入账与 OA 不完全同路径。→ 保存后走同一 overtime 单据投影与 TIME_OFF credit。

[Risk] 密钥进 git。→ 只读环境变量；设计与示例用占位符。

## Migration Plan

1. 配新 cron 与 OCR 环境变量（不提交密钥）。
2. Flyway：批次/附件/明细、来源 origin、能力种子。
3. 部署后端（同步、OCR、保存、局部重算、加班明细）与前端。
4. OPEN 月用「近 3 天」或「本月」刷新后，纸质单才进 pin。关账月不动。
5. 回滚：停用纸质 API 与 OCR；cron 改回 `0,12`；局部重算 API 不传 window 则保持整月。

## Open Questions

无。探索阶段已确认：不强制保存、类型空不能存、主表附件识别、明细带出、按日重叠报错、重算相对今天、跨月只写未关闭月、合计由窗口日事实重加。
