## Why

得力表里有打卡、神州月报格子却漏刷，不是「没算」，是 **卡认错人或没进有效事件**。彭伟、陆玉蕾、李扬、王善源都是同一刀：8/1–8/12 本人格子空，8/14 起对新卡已经对上；彭伟早退时刻还能对上赵艺娴。水位已经走过，再点重新计算补不出历史卡。要对齐所有人的打卡，必须先清错挂、再按得力人员雪花 ID 回放 8 月前半段，最后重算。

## What Changes

- 打卡匹配：**已确认的得力人员雪花 ID（ext_id / 长 user_id）优先于条上 empno**。短 CHECKIN `user_id`（如 387）不得覆盖工号（目录 387 是彭伟，打卡 387 是周步新）。
- 为已知错挂人员 **写入/确认绑定**（七月冲突工号映射 + 得力人员目录），至少覆盖：陆玉蕾、彭伟、李扬、王善源，以及对比清单里 8/14 前同样空白的人。
- **回放 2026-08-01 起至绑定生效日** 的得力原始打卡：隔离记录、以及仅靠错误 empno 挂到别人的有效事件，按新绑定改挂到本人；不得重复计入同一得力 `source_record_id`。
- 回放完成后 **重算江苏神州 2026-08**（及回放触及的其他公司月）。
- 验收以两份得力 Excel **互补合并、按花名册姓名对齐工号**（黄凯=SZST0667、张晨阳=SZST0663；于跃以花名册 SZST0671 为准）。表有时刻的人天，月报首末必须一致。
- 不改得力 Excel、不改花名册工号。黄凯是表写错工号，系统已对；于跃是表 0664 / 花名册 0671，对账按姓名。

不在本 change 一次做完（单独跟踪，不假装已经一致）：

- 聚能月报矩阵接口 500（32 人格子拉不出来）
- 工作台午前把昨天当今天
- 外出日明细仍记旷工小时、加班单 0 小时（那是计算口径，不是打卡认人）

## Capabilities

### New Capabilities

- `deli-punch-identity`: 得力打卡认人：雪花人员 ID 绑定优先于设备 empno；短 user_id 不得覆盖。
- `deli-punch-replay`: 历史错挂/隔离打卡按绑定回放，幂等，回放后重算指定考勤月。

### Modified Capabilities

- （无主库 specs 可改；行为以本 change 的 delta specs 为准。）

## Impact

- 认人：`EvidenceResolutionPolicy`、`MyBatisEmployeeEmploymentResolver`、`DeliEplusClient`（目录雪花 → 工号，短 id 不覆盖）。
- 绑定表：`employee_binding` / `deli_user_id` / `deli_ext_id`（V28 已有结构）。
- 回放：得力 raw/normalized/match_decision/effective_attendance_event；不得只改投影。
- 报表：回放后走现有 `attendance-reports/recalculate`。
- 验收：两份 Excel 互补 vs 月报矩阵；样例人彭伟、陆玉蕾、李扬、王善源、居军、张晨阳。
- 得力同步 cron 仍 00:00 / 12:00；回放是一次性/可重复的运维路径，不替代日常增量。
