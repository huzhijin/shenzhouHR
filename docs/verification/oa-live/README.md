# OA 实库只读证据索引

本目录保存 OA 考勤表单映射的脱敏、可复核实库证据。查询合同在
[`../../contracts/oa-live-resolution-queries.sql`](../../contracts/oa-live-resolution-queries.sql)，
截图专用查询在
[`../../contracts/oa-live-resolution-screenshot-queries.sql`](../../contracts/oa-live-resolution-screenshot-queries.sql)，
最终签字状态以
[`../../contracts/oa-attendance-form-mapping-signoff-matrix.md`](../../contracts/oa-attendance-form-mapping-signoff-matrix.md)
为准。

## 证据批次

| 批次 | 状态 | 说明 |
|---|---|---|
| [2026-08-10](2026-08-10/EVIDENCE.md) | `PARTIAL` | 原 19 项中 18 项已审阅；`OA-B5-01A` 缺少证据编号/实例指纹而保持部分完成；追加调查 `OA-B5-05D/05E` 已审阅，仍不能形成完整验证结论 |

## 截图回传规则

1. 每次执行一个以分号结束的完整 statement，并回传一个 `evidence_id`；推荐从 `OA-B5-00` 开始。`OA-B5-05C` 必须从首个 `SELECT` 一直执行到 `UNION ALL` 第二支后的分号，不能只选第一支。
2. 截图必须同时可辨认：执行的 SQL（或明确的脚本文件/编号）、`evidence_id`、`instance_fingerprint`、全部列名、全部结果行、执行成功状态和返回行数。
3. 先最大化结果区、收起无关侧栏并使用 Fit columns。横向不能截断列；`NULL` 必须能与空字符串区分。
4. 本轮有 19 个 evidence item，预计至少 19 张 PNG。若结果超出一屏，使用 `-p01`、`-p02` 分页，每页重复表头，并在相邻页保留 1 行重叠；全部页收齐前，该单项保持 `PARTIAL`。
5. 文件名使用小写，例如 `oa-b5-03c.png`；分页使用 `oa-b5-05a-p01.png`；`-v2` 只表示同一页重拍，不表示下一页，也不覆盖旧图。
6. 不得出现数据库密码、连接配置、姓名、工号明文或其他人员明细。
7. SQL 结果只证明数据形状或分布。审批语义、业务枚举含义、时区解释和跨系统人员绑定仍需相应业务/管理员签字，不能仅凭分布查询改成 `VERIFIED`。

## 推荐逐张顺序

| 顺序 | evidence_id | 内容 | 预计行数 |
|---:|---|---|---:|
| 1 | `OA-B5-00` | 环境和时区配置 | 1 |
| 2 | `OA-B5-01A` | 加班主/明细相关列 metadata | ≤ 9 |
| 3 | `OA-B5-01C` | 声明式外键摘要 | 1 |
| 4 | `OA-B5-01D` | 候选关联列覆盖率 | 1 |
| 5 | `OA-B5-01E` | 候选关联列 1:N 摘要 | 1 |
| 6 | `OA-B5-02A` | 审批关联列/状态列 metadata | 2 |
| 7 | `OA-B5-02B` | 全库审批状态分布 | 以实库为准 |
| 8 | `OA-B5-02C` | 加班单与审批摘要覆盖率 | 1 |
| 9 | `OA-B5-02D` | 加班单审批状态分布 | 以实库为准 |
| 10 | `OA-B5-03A` | 枚举表关键列 metadata | 2 |
| 11 | `OA-B5-03C` | 加班类别完整映射 | 以实库为准 |
| 12 | `OA-B5-03D` | 加班类别 NULL/未映射计数 | 1 |
| 13 | `OA-B5-04A` | 请假类别完整映射 | 以实库为准 |
| 14 | `OA-B5-04B` | 请假类别 NULL/未映射计数 | 1 |
| 15 | `OA-B5-05A` | `org_member.id/code` 及候选生命周期列 metadata | 以实库列名为准 |
| 16 | `OA-B5-05B` | `code` 形状摘要（纵向） | 10 |
| 17 | `OA-B5-05C` | `code` 两种比较口径的重复摘要 | 2 |
| 18 | `OA-B5-06A` | 加班时间列 metadata | 2 |
| 19 | `OA-B5-06B` | 加班时间范围与异常摘要（纵向） | 7 |

`OA-B5-05C` 发现重复后已追加 `OA-B5-05D/05E`：重复组只出现在
`member_state=1/is_enable=0/is_deleted=1/member_status=1` 组合，但这些生命周期值的业务语义
尚未签字，不能自行将其解释为当前有效或无效，也不得据此关闭 OA-MEMBER-02。

2026-08-10 用户已确认人员编码处理口径：`org_member.code` 为 NULL、零长度或纯空白时，
该人员直接排除，不建立绑定、不参与后续处理，并禁止使用姓名或其他字段兜底。当前快照
共 846 条成员记录，NULL/零长度/纯空白分别为 0/10/0，因此排除 10 条、保留 836 条候选记录。

## 与原脚本编号的对应关系

- 原 `01a`、`01b` 的本轮相关列合并为定向 metadata `OA-B5-01A`。
- 原 `03b` 的原始值已经包含在 `OA-B5-03C`，不再单独截图。
- 原 `4` 拆为映射 `OA-B5-04A` 和空值/未映射计数 `OA-B5-04B`。
- 原 `6` 拆为字段类型 `OA-B5-06A` 和时间摘要 `OA-B5-06B`。
- 原脚本完整表结构仍可作为补充附件，但不是本轮 19 项精简采集的必需截图。

## 状态词

批次状态：

- `RECEIVED_LOG_ONLY`：只有执行日志，没有任何结果值。
- `PARTIAL`：至少收到一个结果项，但 19 项尚未全部完成。
- `CAPTURE_COMPLETE`：19 项均有可持久复核证据；不等于业务合同已签字。

单项状态：

- `AWAITING_SCREENSHOT`：已列入采集清单，等待可读截图。
- `PARTIAL`：多页或关联证据只收到一部分。
- `TRANSCRIBED_ONLY`：聊天图片已转录，但没有可持久附件引用或原图哈希，不能独立复核。
- `REVIEWED`：原图或持久附件可复核，且转录值已完成技术一致性检查。
- `REJECTED`：截图不完整、泄露敏感信息、SQL 不一致或结果不可复核。

合同状态 `VERIFIED` 只在满足签字矩阵最低证据并由要求的责任方确认后使用。

## See Also

- [2026-08-10 证据台账](2026-08-10/EVIDENCE.md)
- [OA 表单映射签字矩阵](../../contracts/oa-attendance-form-mapping-signoff-matrix.md)
