# 得力打卡认人、孤儿事件与彭伟空格子

来源：session `01a023ba-e2a8-7d40-a5fa-f170e8c6f6a8`（2026-08-21～08-25）
叠加后续身份回放：`openspec/changes/deli-punch-identity-cleanup-2026-08/`

彭伟 `SZST0335` 只是核对样例，不是封闭名单。同一类问题对全员生效。

## 结论（先看这个）

报表格子空、或格子时间对不上得力 Excel，**通常不是公式把正常卡算成迟到**，也不是得力 JSON 乱了。是两套认人键混用，再加上重拉/回放删了原料却留下有效事件。

必须按这个顺序，缺一步格子就不会变：

1. 认人：确认雪花绑定优先于卡上工号；**禁止**用人员目录短 `id` 去对 CHECKIN `user_id`
2. 回放 8 月 raw（日常 00:00/12:00 增量不会回头）
3. 清掉「没有 `evidence_link` 的孤儿 `PUNCH_POINT`」（全公司该月，不是只删几个人）
4. 重算 2026-08，等到日志 `month calculation finished`，不要把 HTTP 409 当成失败后再连点
5. 用 SQL 对**全员**核对：有得力 raw 的人，有效事件是否挂在绑定员工上

只点「重新计算」补不出历史错挂。只重拉、不清孤儿事件，核算仍吃旧卡。只清孤儿、不回放雪花，彭伟 8/1–8/13 会变成全空。

## 得力侧有三套对不上的编号

CHECKIN 一条原始记录大致是：

```json
{
  "id": 17283763,
  "user_id": "218",
  "ext_id": "",
  "check_data": "{\"employee_num\":\"SZST0289\",\"member_name\":\"彭伟\",...}"
}
```

| 键 | 是什么 | 能不能当人 |
|---|---|---|
| 人员目录 `id` | 得力通讯录行号，例如目录 `387` = 彭伟 | **不能**对打卡。目录 `387` 是彭伟，CHECKIN `user_id=387` 实际是周步新 `SZJN0002` |
| CHECKIN `user_id` | 打卡账号。彭伟本人是 **`218`**，周步新是 **`387`** | 短号（长度 < 16）**不得**覆盖工号，也不得当目录 id |
| 雪花 `ext_id` / 长数字 id（≥16 位） | 七月冲突映射、人员目录里的账号 id。彭伟 = `939805188834107393` | **确认绑定后优先于卡上 empno** |
| `check_data.employee_num` | 打卡当时设备快照，不是 HR 主数据 | 唯一时可用；设备会打出别人的工号 |

代码落点：

- `DeliEplusClient.toPortRecord`：只有雪花人员 id 才用目录工号覆盖 empno；短 CHECKIN `user_id` 永不覆盖
- `EvidenceResolutionPolicy`：唯一 `CONFIRMED_BINDING`（`deli_user_id` **或** `deli_ext_id`）→ 花名册唯一 `member_name` → `EMPLOYEE_NUMBER`
- 彭伟 CHECKIN `user_id=218` 不是雪花 `939805188834107393`。只绑雪花、只认卡上工号，上半月会进赵艺娴。要用打卡 JSON 里的 `member_name=彭伟`（花名册唯一）认人。目录短 id `387` 仍然不能当打卡账号。

session `01a023ba` 里先改成「只认卡上工号、目录 id 不再覆盖」。这能修好周步新→彭伟串人，但会把彭伟上半月（卡上写成 `SZST0289`）记到赵艺娴。所以后来必须再加确认雪花绑定 + 回放。

## 彭伟本人 8 月卡（得力 CHECKIN，截止 2026-08-21）

HR 工号一直是 `SZST0335`，没有改过。同一得力账号 `user_id=218`，姓名始终「彭伟」，设备都是 2号楼A 三楼制造中心。变的只是卡上 `employee_num`：

- 8/1 09:04～8/13 11:04：卡上写成 **SZST0289**（赵艺娴的工号）
- 8/14 08:20 起：才写成 **SZST0335**

| 日期 | 上班 | 下班 | 卡上工号 |
|---|---|---|---|
| 08-01 六 | 09:04 | 17:50 | SZST0289 |
| 08-03 一 | 08:19 | 21:18 | SZST0289 |
| 08-04 二 | 08:21 | 18:56 | SZST0289 |
| 08-05 三 | 08:25 | 21:08 | SZST0289 |
| 08-06 四 | 08:24 | 20:02 | SZST0289 |
| 08-07 五 | 08:24 | 18:38 | SZST0289 |
| 08-09 日 | 08:56 | 17:31 | SZST0289 |
| 08-10 一 | 08:16 | 18:06 | SZST0289 |
| 08-11 二 | 08:20 | 21:41 | SZST0289 |
| 08-12 三 | — | 18:08 | SZST0289 |
| 08-13 四 | 08:29 | 11:04 | SZST0289 |
| 08-14 五 | 08:20 | 18:22 | SZST0335 |
| 08-15 六 | 08:58 | 17:35 | SZST0335 |
| 08-17 一 | 08:26 | 21:07 | SZST0335 |
| 08-18 二 | 08:23 | — | SZST0335 |
| 08-19 三 | 08:24 | 21:34 | SZST0335 |
| 08-20 四 | 08:26 | 20:34 | SZST0335 |
| 08-21 五 | 08:27 | — | SZST0335 |

没打卡：08-02、08-08、08-16。姚志淼、向佳文 8 月 CHECKIN/KQ 本身就是 0，不是漏页。

若报表上彭伟是 **08:39 / 18:18**，那是周步新 `user_id=387`，不是他。

| 日期 | 错格子（周步新） | 彭伟本人 |
|---|---|---|
| 08-04 | 08:39 / 18:18 | **08:21 / 18:56** |
| 08-05 | 08:39 / 20:03 | **08:25 / 21:08** |

同类串人（目录短 id = 打卡 user_id 数字碰巧相同）：

- 目录 `427` 姚志淼 ← 打卡 `427` 杨彬尉 `SZJN0010`
- 目录 `665` 向佳文 ← 打卡 `665` 袁萌 `SZJNSX06`

赵艺娴 8 月在这台 CHECKIN 机上是 0 条。不能把她当天末卡整段切给彭伟，只能按 `source_record_id` / 雪花重挂。

## 为什么重拉完格子还是旧的

session 里 `reload-deli-august-daily.sh` 删了 8 月得力 `raw_attendance_fact` 和挂在这些 raw 上的 `evidence_link`，**没有删掉** `effective_attendance_event`。

结果：

- 新拉的 `08:39:56` 已经匹配 **周步新 SZJN0002**
- 周步新在聚能、得力源是神州半导体，没有考勤组 → 新卡进隔离，**不生成新事件**
- 彭伟名下 8/21 错绑出来的旧事件还在（link 已空）
- 核算仍把孤儿事件当打卡，报表继续 08:39

当时全公司 8 月这种孤儿 `PUNCH_POINT` 是 **5646** 条（最早 8/1，最晚 8/21）。删完剩余 0，彭伟 8/4 剩余事件 0。这是全员清理，不是只删彭伟。

删孤儿**不会**动年假/调休额度，也不会动仍有 link 的新卡。

列名是 `evidence_link.link_type`，不是 `link_role`。

`point_instant` 按 UTC 存。上海 2026-08-04 = UTC `2026-08-03 16:00:00`～`2026-08-04 16:00:00`。

## 为什么清完孤儿后彭伟会全空

01a023ba 当时就写过：清掉错绑事件后，8/1–8/13 **可能变漏刷**，因为卡上工号是 SZST0289。那不是孤儿 SQL 的范围，是得力设备工号问题。

要格子回到上表的 08:21 / 18:56，必须：

1. `employee_source_binding` 里彭伟 `CONFIRMED` 雪花 `939805188834107393`
2. 回放 8 月 raw（`replayExistingIdentity`），把 SZST0289 那批 source_record 挪到彭伟
3. 再清一遍回放后新产生的孤儿事件（如果有）
4. 重算并发布月快照

绑定 CONFIRMED 但格子仍空，先查库，不要先再回放一遍：

- 彭伟 8 月有没有带 link 的有效事件
- 雪花/user_id=218 的 raw 匹配员工是不是他
- 月快照是否还是回放前的版本

看 **查询报表** `/api/v1/attendance-report-queries` 月矩阵，不要死等客户中心「考勤报表」（会走更重的 snapshot+calc）。`report-calc-1` 的 CPU 时间长时间不动 = 线程卡死，应停 Java 后再只跑重算，不要叠回放。

## 现网操作顺序（宝塔）

江苏神州 `company_id = 41000000-0000-0000-0000-000000000003`。
得力 cron 保持 00:00 / 12:00。不要改花名册工号，不要改 Excel。

密码、账号从服务器环境读，不要写进仓库、不要在对话里再打一遍。

### 1. 确认绑定（全目录种子 + 冲突 overlay）

见 `deploy/baota/scripts/README-deli-identity-replay.md`。
overlay：`deploy/mysql/deli-conflict-identity-bindings.sql`（工号+姓名对不上的不写）。

目录短 id（如居军目录 id `18`）种子会跳过，必须靠 overlay 雪花。

### 2. 回放 8 月 raw

`SHENZHOUHR_DELI_REPLAY_ENABLED=true` 重启 → 回放 → 改回 `false` 再重启。
回放不推进 CHECKIN/KQ 水位。

### 3. 清孤儿事件

先预览：

```bash
mysql --default-character-set=utf8mb4 shenzhou_hr \
  < deploy/mysql/clean-orphan-punch-events.sql
```

数字落在该月、量级几千到两万，再 apply：

```bash
mysql --default-character-set=utf8mb4 shenzhou_hr \
  -e "SET @apply := 1; SOURCE deploy/mysql/clean-orphan-punch-events.sql;"
```

### 4. 全员核对（不要只查几个名字）

```bash
mysql --default-character-set=utf8mb4 shenzhou_hr \
  < deploy/mysql/verify-deli-punch-identity-roster.sql
```

关注这些 section：

- `orphan_punch_events`：应为 0
- `raw_matched_to_other_than_confirmed_binding`：应为 0（设备工号错挂但绑定已确认的，匹配必须是绑定员工）
- `bound_employees_with_deli_raw_but_no_event`：有 raw、无有效事件的人；彭伟若在这里，回放没写成事件
- `canary_pengwei`：对照上面的本人时刻表

### 5. 只重算，等 finished

两个终端。终端 1：

```bash
tail -f /opt/shenzhouhr/logs/shenzhouhr.log | grep --line-buffered -E 'month calculation (starting|finished|failed)|still running after wait'
```

终端 2：`POST /api/v1/attendance-reports/recalculate`，`period=2026-08`，江苏神州。HTTP 最多等约 15 分钟会 409；后台可能还在跑。出现 `finished` 再强刷。不要连点。

`report-calc-1` CPU 时间冻住：停 Java，不要回放，只重算。

验收用互补 Excel 脚本：`deploy/baota/scripts/compare-deli-month-report.sh`。
闸门是「表有卡、矩阵全空」的人天，不是只看彭伟。`SZSTSX*` / `SZST067x–070x` 对不上花名册的，不计入已清。

## 不要做的事

- 用目录短 `id` 覆盖打卡 empno（会再次把周步新给彭伟）
- 只认 empno、不回放雪花（彭伟 8/1–13 会空，卡在赵艺娴）
- 按姓名或按天剪切末卡（赵艺娴自己的卡会被剪走）
- 把几个样例人写成封闭修复名单
- 改得力 cron、改花名册、改 Excel、伪造打卡
- 清 8 月得力时删年假/调休表
- 409 后连点重算；或在卡死的 `report-calc` 上再叠回放

## 相关文件

- `backend/.../DeliEplusClient.java` — 雪花才覆盖 empno
- `backend/.../EvidenceResolutionPolicy.java` — CONFIRMED_BINDING > empno
- `deploy/mysql/deli-conflict-identity-bindings.sql`
- `deploy/mysql/clean-orphan-punch-events.sql`
- `deploy/mysql/verify-deli-punch-identity-roster.sql`
- `deploy/baota/scripts/README-deli-identity-replay.md`
- `deploy/baota/scripts/compare-deli-month-report.sh`
- `openspec/changes/deli-punch-identity-cleanup-2026-08/`
