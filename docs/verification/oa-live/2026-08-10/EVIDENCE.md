# OA 实库只读解析证据台账（2026-08-10）

## 1. 批次状态

| 字段 | 当前值 |
|---|---|
| 批次日期 | 2026-08-10 |
| 执行时区 | 执行端 `Asia/Shanghai`；数据库会话 `+08:00`、系统标签 `CST`（业务时区语义未签字） |
| 目标数据库 | 日志及 `OA-B5-00` 均显示 `szoa` |
| 批准环境名称/来源 | `NOT_RECEIVED` |
| 脱敏实例指纹 | `dfaaa4be76e74040`（`OA-B5-00`） |
| 执行端脚本哈希证明 | `NOT_RECEIVED` |
| 首次连接日志时间 | 2026-08-10 14:55:19 |
| 原始日志查询执行时间段 | 2026-08-10 15:02:59–15:03:38 |
| 当前批次状态 | `PARTIAL`（原 19 项中 18 项已审阅、1 项部分完成；追加调查 05D/05E 已审阅） |
| 可否更新签字矩阵为 VERIFIED | 否 |

用户最初回传了 DataGrip/数据库客户端执行日志；该日志只包含 SQL 文本、成功/失败状态及
“检索到 N 行”，不包含结果表格里的实际单元格值。随后用户逐项回传了脱敏结果截图，
本页分别保存原始日志事实、截图转录、原图哈希和技术判断。数据形状与计数可标为已审阅，
但枚举、审批状态、跨系统人员绑定、生命周期值或时区的业务含义不会仅凭 SQL 结果标成
已确认。

## 2. 执行脚本溯源

用户报告本次执行的是仓库外文件：

`/Users/huzhijin/Downloads/oa-live-resolution-queries.sql`

收到日志后进行事后核对时，当时的外部文件与当时仓内工作副本逐字节一致，该副本的
SHA-256 为：

```text
a0b155fba16910eabce8562bbda05568ad28f1ada5a27318e4d08838953d25c1
```

日志本身没有绑定脚本哈希，因此上述值只能作为事后溯源线索，不能证明执行瞬间的
确切字节。严格签字前仍需执行端回传实际运行文件的哈希。

随后已在仓内脚本修复 `5b` 缺失的 `FROM org_member`。最终复核时仓库外原路径已不存在；
不得把先前旧副本当作修正版重跑，否则 `5b` 仍会报 1054。后续应使用仓内截图专用脚本。
当前两份后续采集文件的 SHA-256 为：

| 文件 | SHA-256 |
|---|---|
| `docs/contracts/oa-live-resolution-queries.sql`（已修复 5b） | `58b138391784d375b1d624563383ab3700c0f58dd0323a54dcd7daebefd3bceb` |
| `docs/contracts/oa-live-resolution-screenshot-queries.sql` | `f3c194f93127d9bdd076197b6088554c1562376723bb3d6013c20fa43eda0b1b` |

## 3. 已收到的执行事实

### OA-B5-00 环境结果

收到时间：2026-08-10 16:04（Asia/Shanghai）。两张截图是同一个一行结果的横向互补视图；
重叠列 `mysql_version/current_db` 的值一致，合并后六个字段均可读取。

| 字段 | 转录值 |
|---|---|
| `evidence_id` | `OA-B5-00` |
| `instance_fingerprint` | `dfaaa4be76e74040` |
| `mysql_version` | `8.0.26` |
| `current_db` | `szoa` |
| `session_time_zone` | `+08:00` |
| `system_time_zone` | `CST` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-00-p01.png`](screenshots/oa-b5-00-p01.png) | `fa3699b23129e19babe82f798aa4d2dd0cfa5bcfbfa917d575cb56d7ea6e6b03` |
| [`screenshots/oa-b5-00-p02.png`](screenshots/oa-b5-00-p02.png) | `87bc2671581d22dcd7e6ed8192b516dfc627ea657bcc4adba17600f346c308a7` |

技术判断：数据库会话明确使用 `+08:00`；`CST` 标签本身存在多种时区解释，且数据库配置
不能单独证明 OA 无时区 `DATETIME` 的业务语义。因此该项可作为环境上下文，不能单独关闭
OA-TIME-01。

### OA-B5-01A 加班主/明细相关列 metadata

收到时间：2026-08-10 16:10（Asia/Shanghai）。两张截图是同一个九行结果的横向互补视图：
第一张显示 `table_name` 至 `is_nullable`，第二张显示 `is_nullable`、`column_key` 和
`column_default`；九行顺序及重叠的 `is_nullable` 值一致。

| table_name | ordinal_position | column_name | column_type | is_nullable | column_key | column_default |
|---|---:|---|---|---|---|---|
| `formmain_0171` | 1 | `ID` | `bigint` | `NO` | `PRI` | `<NULL>` |
| `formmain_0171` | 17 | `field0102` | `datetime` | `YES` | `<EMPTY>` | `<NULL>` |
| `formson_0172` | 1 | `ID` | `bigint` | `NO` | `PRI` | `<NULL>` |
| `formson_0172` | 2 | `formmain_id` | `bigint` | `YES` | `MUL` | `<NULL>` |
| `formson_0172` | 5 | `field0093` | `varchar(20)` | `YES` | `<EMPTY>` | `<NULL>` |
| `formson_0172` | 7 | `field0096` | `bigint` | `YES` | `<EMPTY>` | `<NULL>` |
| `formson_0172` | 8 | `field0100` | `datetime` | `YES` | `<EMPTY>` | `<NULL>` |
| `formson_0172` | 9 | `field0099` | `datetime` | `YES` | `<EMPTY>` | `<NULL>` |
| `formson_0172` | 10 | `field0094` | `varchar(100)` | `YES` | `<EMPTY>` | `<NULL>` |

`<EMPTY>` 表示截图中的空白 `column_key` 单元格，`<NULL>` 表示客户端显示的 SQL NULL。

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-01a-p01.png`](screenshots/oa-b5-01a-p01.png) | `017434e96d798a4bad72530599955b274d3f43d46e2f47fa1fd2b6c0293fde4b` |
| [`screenshots/oa-b5-01a-p02.png`](screenshots/oa-b5-01a-p02.png) | `d30f8c559b8680377d8ae2ddd2f451263f0557f4429c4c1ae9ac85e717ba24f1` |

技术判断：截图支持候选列 `formmain_id` 实际存在、类型为 `bigint`、允许 NULL 且带普通索引，
也支持本轮相关时间、枚举和人员字段的上述 metadata 转录；`MUL` 不能证明声明式外键或真实
1:N 语义。两张图均未显示左侧 `evidence_id` 和 `instance_fingerprint`，因此只能按上下文
对应到 `OA-B5-01A` 并保持 `PARTIAL`。签字前仍须补齐这两列或提供等价的查询/实例一致性
证据；当前不要求立即重拍，其余证据项已可独立审阅。

### OA-B5-01C 声明式外键摘要

收到时间：2026-08-10 16:11（Asia/Shanghai）。两张截图是同一个一行结果的横向互补视图；
第二张补全了右侧 `declared_fk_mappings` 列，重叠的计数与映射值一致。

| 字段 | 转录值 |
|---|---|
| `evidence_id` | `OA-B5-01C` |
| `instance_fingerprint` | `dfaaa4be76e74040` |
| `declared_fk_count` | `0` |
| `declared_fk_mappings` | `<NONE>` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-01c-p01.png`](screenshots/oa-b5-01c-p01.png) | `904bc542e7ea68af41a3c3add53667fcf0bab9aaf278b77d61a7313d2c9f14e4` |
| [`screenshots/oa-b5-01c-p02.png`](screenshots/oa-b5-01c-p02.png) | `0362ab12640c0aa6551bdf3967c886ff72d49b72834e9ae7e3ab8bdca023bbe5` |

技术判断：该实例中，`formmain_0171` 与 `formson_0172` 没有被本查询命中的声明式外键；
这不能否定 OA 通过约定列维护主从关系，也不能单独证明候选 `formmain_id` 的真实关联语义。

### OA-B5-01D 候选关联列覆盖率

收到时间：2026-08-10 16:14（Asia/Shanghai）。两张截图是同一个一行结果的横向互补视图；
两图通过 `detail_rows=117207` 重叠对齐，合并后七个字段均可读取。

| 字段 | 转录值 |
|---|---:|
| `evidence_id` | `OA-B5-01D` |
| `instance_fingerprint` | `dfaaa4be76e74040` |
| `main_rows` | 42951 |
| `detail_rows` | 117207 |
| `distinct_nonnull_parent_refs` | 42951 |
| `null_parent_detail_rows` | 0 |
| `nonnull_orphan_detail_rows` | 0 |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-01d-p01.png`](screenshots/oa-b5-01d-p01.png) | `5f1e210309826bebc4604f66043f848d8e3f936ad3b608fdfe7d6da7e2b93520` |
| [`screenshots/oa-b5-01d-p02.png`](screenshots/oa-b5-01d-p02.png) | `ea31f16b9c47f9837094892ee2a49659a1a3ac3dcbe438fc99beb1e667b9b3ef` |

技术判断：在本次实例快照中，117207 条明细的候选 `formmain_id` 全部非 NULL 且均能命中
主表；42951 个不同父引用与 42951 条主表记录相等，说明每条现有主表记录至少被一条明细
引用。该结果支持候选关联列具备完整覆盖；随后 `OA-B5-01E` 已补齐每主单明细条数摘要并
确认当前快照存在 1:N 基数。

### OA-B5-01E 候选关联列 1:N 摘要

入库时间：2026-08-10 16:27（Asia/Shanghai）。两张截图是同一个一行结果的横向互补视图；
通过 `main_rows_without_details=0` 的重叠值对齐，合并后六个字段均可读取。

| 字段 | 转录值 |
|---|---|
| `evidence_id` | `OA-B5-01E` |
| `instance_fingerprint` | `dfaaa4be76e74040` |
| `main_rows_without_details` | `0` |
| `documents_with_one_detail` | `26741` |
| `documents_with_multiple_details` | `16210` |
| `max_details_per_document` | `84` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-01e-p01.png`](screenshots/oa-b5-01e-p01.png) | `2d9d50949b2e83ae51eb94bfc267fdc4c82136036f479e4eefa25a043474dd73` |
| [`screenshots/oa-b5-01e-p02.png`](screenshots/oa-b5-01e-p02.png) | `58ee1e32ee7b967a20c55d2bb6593eca58fd73e5884032ba3e6755fcc41d21fb` |

技术判断：26741 个单明细主单加 16210 个多明细主单等于 42951 条主表记录，且无零明细
主单；当前数据中确实存在 1:N，单个主单最多 84 条明细。结合 01D，这支持
`formmain_id` 是现有数据的完整主从关联候选；OA-FK-01/05 的最终签字仍受 01A 证据完整性
及责任方确认约束。

### OA-B5-02A 审批关联列/状态列 metadata

入库时间：2026-08-10 16:27（Asia/Shanghai）。两张截图横向互补；通过
`ordinal_position/column_name/column_type` 重叠对齐，两行的全部字段均可读取。

| evidence_id | instance_fingerprint | ordinal_position | column_name | column_type | is_nullable | column_key | column_default |
|---|---|---:|---|---|---|---|---|
| `OA-B5-02A` | `dfaaa4be76e74040` | 2 | `STATE` | `smallint` | `YES` | `MUL` | `<NULL>` |
| `OA-B5-02A` | `dfaaa4be76e74040` | 31 | `FORM_RECORDID` | `bigint` | `YES` | `MUL` | `<NULL>` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-02a-p01.png`](screenshots/oa-b5-02a-p01.png) | `85408024996d78c1d33bff98dbeceb77df81196857b792b5d030cd344bf35bba` |
| [`screenshots/oa-b5-02a-p02.png`](screenshots/oa-b5-02a-p02.png) | `4e5aa4b64e4078ae6d0b2aa2712c515fcd93d3f9ce17824b9ee3c2ae563ee0b5` |

技术判断：`col_summary.STATE` 是可空 `smallint`，`FORM_RECORDID` 是可空 `bigint`，两列
均显示普通索引标志 `MUL`。这确认了本轮相关列的名称与类型，但 `MUL` 本身不证明唯一性、
外键约束或状态业务语义。

### OA-B5-02B 全库审批状态分布

入库时间：2026-08-10 16:27（Asia/Shanghai）。两张截图显示同一五行结果；所有行的
`evidence_id`、实例指纹、状态值和 NULL 标志一致，第二张补全 `row_count`。

| raw_state | state_is_null | row_count |
|---|---:|---:|
| `3` | 0 | 259653 |
| `0` | 0 | 55999 |
| `2` | 0 | 1754 |
| `<NULL>` | 1 | 525 |
| `1` | 0 | 198 |

共同 `evidence_id=OA-B5-02B`，`instance_fingerprint=dfaaa4be76e74040`；五组共 318129 行。

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-02b-p01.png`](screenshots/oa-b5-02b-p01.png) | `0eeb06263f8d9071aa7f81f2812c980da6ab6a0d4546e2bb5c41f8257595257d` |
| [`screenshots/oa-b5-02b-p02.png`](screenshots/oa-b5-02b-p02.png) | `c8496b7e40a30c25a59a1477f3fd7a28ffa6474fe9ea1f96b61ea19b7f5b46bd` |

技术判断：实库 `col_summary.state` 当前共有 `3/0/2/NULL/1` 五组；额外的 `state=1`
共有 198 行，其业务语义未知。该结果建立了原始值分布，但没有形成“最终批准/撤销/拒绝/
草稿”等封闭业务映射，OA-STATUS-01/02 仍不得据此标记 `VERIFIED`。

### OA-B5-02C 加班单与审批摘要覆盖率

入库时间：2026-08-10 16:27（Asia/Shanghai）。三张截图是同一个一行结果的连续横向视图；
通过 `matched_documents`、`unmatched_documents` 等重叠列对齐，七个字段均可读取。

| 字段 | 转录值 |
|---|---|
| `evidence_id` | `OA-B5-02C` |
| `instance_fingerprint` | `dfaaa4be76e74040` |
| `overtime_main_rows` | `42951` |
| `matched_documents` | `42951` |
| `unmatched_documents` | `0` |
| `documents_with_multiple_summary_rows` | `0` |
| `max_summary_rows_per_document` | `1` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-02c-p01.png`](screenshots/oa-b5-02c-p01.png) | `21502f9f516aecb852f7c23e7d5ea29442041d52ecd009ab2896a0e7a1f6e746` |
| [`screenshots/oa-b5-02c-p02.png`](screenshots/oa-b5-02c-p02.png) | `e3be00235d850e57ea5706372441be4868fdea69145aca3474394dad61708cc5` |
| [`screenshots/oa-b5-02c-p03.png`](screenshots/oa-b5-02c-p03.png) | `4e41d57edaf455437e92e4c54c8a8e706ab4cf09db0843f845a0f6481a8352e9` |

技术判断：42951 条加班主单全部关联到 `col_summary`，没有未匹配主单，也没有一单多条
summary；每单最多且实际恰有一条 summary。该结果支持 `m.id = c.form_recordid` 在当前数据
中的 1:1 覆盖，不证明各 `state` 值的业务语义。

### OA-B5-02D 加班单审批状态分布

入库时间：2026-08-10 16:27（Asia/Shanghai）。两张截图显示同一五行结果；第一张给出
状态及 NULL 标志，第二张补全 join 行数与去重单据数，行序和 NULL 标志重叠一致。

| raw_state | state_is_null | joined_rows | distinct_overtime_documents |
|---|---:|---:|---:|
| `3` | 0 | 42422 | 42422 |
| `0` | 0 | 316 | 316 |
| `2` | 0 | 158 | 158 |
| `<NULL>` | 1 | 54 | 54 |
| `1` | 0 | 1 | 1 |

共同 `evidence_id=OA-B5-02D`，`instance_fingerprint=dfaaa4be76e74040`；五组去重单据合计
42951。

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-02d-p01.png`](screenshots/oa-b5-02d-p01.png) | `3efb45cac546e654170f0b6001f0ec74d981f16150c6bd2b136db741300c9b6e` |
| [`screenshots/oa-b5-02d-p02.png`](screenshots/oa-b5-02d-p02.png) | `fcbb21b0fd9e8863491bdc4e86da0a4d714698072bca1e47a8701be909e5fde2` |

技术判断：五组 `joined_rows` 均等于对应去重单据数，且合计 42951；结合 02C，可确认当前
每条加班主单恰有一条 summary。加班单中额外 `state=1` 只有 1 单，但其语义仍未知；不能
将它猜测为批准、拒绝、撤销或其他状态，也不能仅凭分布确认 `state=3` 是最终批准。

### OA-B5-03A 枚举表关键列 metadata

入库时间：2026-08-10 17:09（Asia/Shanghai）。两张截图横向互补，通过两行的
`column_type` 重叠对齐，全部 metadata 字段均可读取。

| evidence_id | instance_fingerprint | ordinal_position | column_name | column_type | is_nullable | column_key | character_set_name | collation_name |
|---|---|---:|---|---|---|---|---|---|
| `OA-B5-03A` | `dfaaa4be76e74040` | 1 | `ID` | `bigint` | `NO` | `PRI` | `<NULL>` | `<NULL>` |
| `OA-B5-03A` | `dfaaa4be76e74040` | 3 | `SHOWVALUE` | `varchar(255)` | `YES` | `<EMPTY>` | `utf8mb4` | `utf8mb4_0900_ai_ci` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-03a-p01.png`](screenshots/oa-b5-03a-p01.png) | `02c3cff8e1e5ad76c55f41502f8b7bbdbece3cde1cd0c568c5a2fb1c4da20ee7` |
| [`screenshots/oa-b5-03a-p02.png`](screenshots/oa-b5-03a-p02.png) | `bd26b10ea40e8f2a9afcd1078a3adbbc9e23cc058ccb311c5042dd7f80cfd6b6` |

技术判断：枚举项主键 `ID` 为非空 `bigint` 主键，显示值 `SHOWVALUE` 为可空
`varchar(255)`；该 metadata 支持后续按 ID 关联显示值，但不单独证明显示值的业务含义。

### OA-B5-03C 加班类别完整映射

入库时间：2026-08-10 17:09（Asia/Shanghai）。单张截图完整显示四行及全部列。

| raw_enum_id | label | row_count |
|---|---|---:|
| `-6539634143789166714` | 加班费 | 112022 |
| `5912806790045781226` | 调休 | 5066 |
| `4337518111002608138` | 义务加班 | 89 |
| `<NULL>` | `<RAW_NULL>` | 30 |

共同 `evidence_id=OA-B5-03C`，`instance_fingerprint=dfaaa4be76e74040`；四组合计 117207，
与 01D 的明细总数一致。

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-03c.png`](screenshots/oa-b5-03c.png) | `811f10570d82420b8551abe8dfd1ebb223a44dc441a76714f66f1ba726c94ef3` |

技术判断：当前快照中，非空 `field0096` 全部落在“加班费/调休/义务加班”三个显示值，另有
30 行原始 NULL。该结果确认技术映射及分布；显示值是否构成获批的业务封闭字典仍需相应
责任方确认，NULL 行必须保持不生效或进入隔离，不能降级为默认类别。

### OA-B5-03D 加班类别 NULL/未映射计数

入库时间：2026-08-10 17:09（Asia/Shanghai）。三张截图为同一个一行结果的连续横向
视图，通过 `raw_null_rows` 与 `missing_enum_item_rows` 重叠对齐。

| 字段 | 转录值 |
|---|---|
| `evidence_id` | `OA-B5-03D` |
| `instance_fingerprint` | `dfaaa4be76e74040` |
| `raw_null_rows` | `30` |
| `missing_enum_item_rows` | `0` |
| `null_label_rows` | `0` |
| `empty_or_blank_label_rows` | `0` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-03d-p01.png`](screenshots/oa-b5-03d-p01.png) | `7d66950b89ff3f6480ad095f7c4400d33df973a211f111be112d342b313e766c` |
| [`screenshots/oa-b5-03d-p02.png`](screenshots/oa-b5-03d-p02.png) | `b2fb6842f234b1ac75af90c5c6b62243707b78b06b5b5c3f15e368b6ff439f1c` |
| [`screenshots/oa-b5-03d-p03.png`](screenshots/oa-b5-03d-p03.png) | `ca761f516213e450493629e6ecc9c05c61fec21d1020350c48238fbb6a32db71` |

技术判断：除 30 行原始 NULL 外，没有缺失枚举项、NULL 标签或空白标签；这与 03C 一致，
但不改变原始 NULL 行的 fail-closed 要求。

### OA-B5-04A 请假类别完整映射

首次入库时间：2026-08-10 17:09（Asia/Shanghai）。最初单图仅显示第 8–13 行，随后于
17:14 收到两张有重叠的完整截图：`p01` 显示第 1–11 行，`p02` 显示第 4–13 行；重叠的
第 4–11 行逐项一致，现已闭合全部 13 行。

| raw_enum_id | label | row_count |
|---|---|---:|
| `5959840635913392019` | 年假 | 3930 |
| `-5949231195115077302` | 事假 | 3790 |
| `-1336039252273314314` | 调休 | 1715 |
| `-4813206556006068884` | 病假 | 219 |
| `399372756916521924` | 丧假 | 108 |
| `7766045108187057729` | 婚假 | 39 |
| `5096400378405801343` | 陪产假 | 36 |
| `825511723370040388` | 孕检假 | 19 |
| `6036514085623843175` | 哺乳假 | 16 |
| `8015931125935262011` | 其他 | 12 |
| `-4366659526086802845` | 产假 | 8 |
| `-8479271604082882449` | 计生假 | 2 |
| `<NULL>` | `<RAW_NULL>` | 2 |

共同 `evidence_id=OA-B5-04A`，`instance_fingerprint=dfaaa4be76e74040`；13 组合计 9896 行。

| 原图 | 用途 | SHA-256 |
|---|---|---|
| [`screenshots/oa-b5-04a.png`](screenshots/oa-b5-04a.png) | 初始部分截图，第 8–13 行 | `acb21a89649f97206d08e610c0c3eccaaee64b14e533b79d1e93fa9c43cd82b0` |
| [`screenshots/oa-b5-04a-p01.png`](screenshots/oa-b5-04a-p01.png) | 完整证据第 1–11 行 | `5b6bf262e4776e6872bf026af50489fad188697cfde56feb1938032289b46099` |
| [`screenshots/oa-b5-04a-p02.png`](screenshots/oa-b5-04a-p02.png) | 完整证据第 4–13 行 | `f20fb814ace3e5657bf1b7c18e2948eee2d16758a2f9a5ac0d0e6fc986dc8489` |

技术判断：当前快照的 13 组请假类别原始值、显示标签和数量已经完整转录，其中 2 行原始
NULL。该结果形成技术映射；标签的正式业务口径和未知值处置仍不得在缺少责任方确认时
擅自标记 `VERIFIED`。

### OA-B5-04B 请假类别 NULL/未映射计数

入库时间：2026-08-10 17:09（Asia/Shanghai）。两张截图横向互补，通过
`raw_null_rows=2` 和 `missing_enum_item_rows=0` 重叠对齐。

| 字段 | 转录值 |
|---|---|
| `evidence_id` | `OA-B5-04B` |
| `instance_fingerprint` | `dfaaa4be76e74040` |
| `raw_null_rows` | `2` |
| `missing_enum_item_rows` | `0` |
| `null_label_rows` | `0` |
| `empty_or_blank_label_rows` | `0` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-04b-p01.png`](screenshots/oa-b5-04b-p01.png) | `546f7cb5980d9ec08ef2c4f350450eb72f6926f6cfb3e41bcefe0481bdfd478a` |
| [`screenshots/oa-b5-04b-p02.png`](screenshots/oa-b5-04b-p02.png) | `82717b82f04d1448fecf4258adc80dc12af53a420a88509ec402e0a0ff90f5b7` |

技术判断：除 2 行原始 NULL 外，没有缺失枚举项、NULL 标签或空白标签；与 04A 的完整
映射相互勾稽，原始 NULL 仍不能映射为默认请假类别。

### OA-B5-05A 人员编码及候选生命周期列 metadata

入库时间：2026-08-10 17:09（Asia/Shanghai）。三张截图为六行 metadata 的连续横向
视图，通过相邻截图中的列名、顺序和值重叠对齐。

| evidence_id | instance_fingerprint | ordinal_position | column_name | column_type | is_nullable | column_key | character_set_name | collation_name |
|---|---|---:|---|---|---|---|---|---|
| `OA-B5-05A` | `dfaaa4be76e74040` | 1 | `ID` | `bigint` | `NO` | `PRI` | `<NULL>` | `<NULL>` |
| `OA-B5-05A` | `dfaaa4be76e74040` | 3 | `CODE` | `varchar(500)` | `YES` | `<EMPTY>` | `utf8mb4` | `utf8mb4_0900_ai_ci` |
| `OA-B5-05A` | `dfaaa4be76e74040` | 10 | `STATE` | `smallint` | `YES` | `<EMPTY>` | `<NULL>` | `<NULL>` |
| `OA-B5-05A` | `dfaaa4be76e74040` | 11 | `IS_ENABLE` | `smallint` | `YES` | `<EMPTY>` | `<NULL>` | `<NULL>` |
| `OA-B5-05A` | `dfaaa4be76e74040` | 12 | `IS_DELETED` | `smallint` | `YES` | `<EMPTY>` | `<NULL>` | `<NULL>` |
| `OA-B5-05A` | `dfaaa4be76e74040` | 13 | `STATUS` | `smallint` | `YES` | `<EMPTY>` | `<NULL>` | `<NULL>` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-05a-p01.png`](screenshots/oa-b5-05a-p01.png) | `7f47a9145212498c1e22f019b73c551c410f637d1bcc56712f26590ad8dfd175` |
| [`screenshots/oa-b5-05a-p02.png`](screenshots/oa-b5-05a-p02.png) | `a7f6a63df8a1192d67d152cb6d611917259eaedd72c89a80758d5ed0ddbf4327` |
| [`screenshots/oa-b5-05a-p03.png`](screenshots/oa-b5-05a-p03.png) | `d3c5c9d8d6dfdc0bf1255b186f69466c7cea8413a36a7e06ea4e4bc65e754aba` |

技术判断：`CODE` 是可空 `varchar(500)`，并存在四个名称上可能与生命周期有关的可空
`smallint` 字段。metadata 只确认列形状；`STATE/IS_ENABLE/IS_DELETED/STATUS` 各取值的
业务语义仍未知，不能仅按列名推断当前有效成员。

### OA-B5-05B 人员编码形状摘要

入库时间：2026-08-10 17:09（Asia/Shanghai）。单张截图完整显示十项纵向指标，不包含人员
姓名或编码明文。

| metric | metric_value |
|---|---:|
| `total_members` | 846 |
| `distinct_codes_db_collation` | 836 |
| `distinct_codes_byte_exact` | 836 |
| `null_codes` | 0 |
| `zero_length_codes` | 10 |
| `whitespace_only_codes` | 0 |
| `min_usable_len` | 6 |
| `max_usable_len` | 9 |
| `leading_zero_codes` | 0 |
| `non_numeric_codes` | 833 |

共同 `evidence_id=OA-B5-05B`，`instance_fingerprint=dfaaa4be76e74040`。

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-05b.png`](screenshots/oa-b5-05b.png) | `7547889b30309341a3559c9efe8e0d98ffa9cc358752d251cea800f79a1929a1` |

技术判断：当前快照共有 846 条成员记录，其中 NULL 编码 0 条、零长度编码 10 条、纯空白
编码 0 条；排除这三类后有 836 条候选记录。833 条编码包含非数字字符，此处只记录形状，
不据此推断其有效性或跨系统含义。

业务口径确认（用户，2026-08-10）：`org_member.code` 为 NULL、零长度或纯空白的人员均
忽略，不建立绑定、不参与后续处理，并禁止使用姓名或其他字段兜底。按本快照因此排除
10 条、保留 836 条候选记录。该确认只关闭“无 code 如何处置”的口径，不证明 `code`
就是得力 `employee_number`，也不证明生命周期字段或重复记录的业务语义。

### OA-B5-05C 人员编码重复摘要

入库时间：2026-08-10 17:09（Asia/Shanghai）。保留早先只显示数据库排序规则单支结果的
两张截图，并以随后完整执行 `UNION ALL` 的两张截图作为规范结果；两组截图均不披露编码
明文。

| comparison_mode | duplicate_code_groups | members_in_duplicate_groups | max_members_per_code |
|---|---:|---:|---:|
| `BYTE_EXACT` | 1 | 2 | 2 |
| `DATABASE_COLLATION` | 1 | 2 | 2 |

共同 `evidence_id=OA-B5-05C`，`instance_fingerprint=dfaaa4be76e74040`。早先单支结果为
`DATABASE_COLLATION / 1 / 2 / 2`，与规范结果对应行一致。

| 原图 | 用途 | SHA-256 |
|---|---|---|
| [`screenshots/oa-b5-05c-p01.png`](screenshots/oa-b5-05c-p01.png) | 早先单支结果左侧 | `2204e3d2da7c66a4199453aeb1930e5df311ad4d2060f28f761a68c375c3cfb0` |
| [`screenshots/oa-b5-05c-p02.png`](screenshots/oa-b5-05c-p02.png) | 早先单支结果右侧 | `9d4ac3d7518d5f42cbb1c9d068b1217726ee016f08cf6c7da1fa75221ce0cf18` |
| [`screenshots/oa-b5-05c-p03.png`](screenshots/oa-b5-05c-p03.png) | 规范双口径结果左侧 | `585ba3d0588699923fca57b6d6f94ec6ab6d7e097168c5737fa635e2e57d7e51` |
| [`screenshots/oa-b5-05c-p04.png`](screenshots/oa-b5-05c-p04.png) | 规范双口径结果右侧 | `681873d7b398b9f8b70e677224da2331362325743def5e576dbda4b5168171d8` |

技术判断：数据库默认排序规则和逐字节比较都命中 1 个重复编码组，涉及 2 条成员记录，
每组最大 2 条；这确认重复不是仅由数据库排序规则折叠导致。是否构成当前有效成员绑定
冲突，还必须结合 05D/05E 的客观分组和经签字的生命周期语义判断。

### OA-B5-05D 人员生命周期组合分布（追加调查）

入库时间：2026-08-10 17:12（Asia/Shanghai）。两张截图为同一四行结果的横向互补视图，
共同列及行序一致。

| member_state | is_enable | is_deleted | member_status | member_rows | usable_code_rows |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 1 | 621 | 617 |
| 2 | 0 | 0 | 1 | 203 | 202 |
| 1 | 0 | 1 | 1 | 12 | 7 |
| 1 | 0 | 0 | 1 | 10 | 10 |

共同 `evidence_id=OA-B5-05D`，`instance_fingerprint=dfaaa4be76e74040`；四组合计 846 条
成员记录、836 条可用编码记录，与 05B 相互勾稽。

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-05d-p01.png`](screenshots/oa-b5-05d-p01.png) | `bb68497f0921c004c9d34483e9fcab1ad5091b7dfb43bb88f11e8892e38e5a19` |
| [`screenshots/oa-b5-05d-p02.png`](screenshots/oa-b5-05d-p02.png) | `827c13ff0d97cf794600b875bbae9beb86fe731170e617b2e2bc6221a5b50a5a` |

技术判断：结果只证明四个取值组合及计数；在 OA 管理员或业务责任方确认之前，不把任一
`state/status/is_enable/is_deleted` 组合命名为“在职”“有效”或“已删除”。

### OA-B5-05E 重复编码所在生命周期组合（追加调查）

入库时间：2026-08-10 17:12（Asia/Shanghai）。两张截图为同一一行结果的横向互补视图，
重叠字段和值一致。

| member_state | is_enable | is_deleted | member_status | duplicate_member_rows | duplicated_code_groups |
|---:|---:|---:|---:|---:|---:|
| 1 | 0 | 1 | 1 | 2 | 1 |

共同 `evidence_id=OA-B5-05E`，`instance_fingerprint=dfaaa4be76e74040`。

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-05e-p01.png`](screenshots/oa-b5-05e-p01.png) | `4330ac4ef0424b6bc8d17765f39b0edf6ab7a3462d6f635e2b0079cb090b2eb5` |
| [`screenshots/oa-b5-05e-p02.png`](screenshots/oa-b5-05e-p02.png) | `ec01c336511d9b249c47e0ff64408231076396cae87135ce2c527dc0dd9ea94e` |

技术判断：05C 的唯一重复编码组所涉及两条记录均落在
`member_state=1/is_enable=0/is_deleted=1/member_status=1` 这一客观取值组合。由于这些取值
的业务语义未签字，仍不能自行断言该重复组当前有效或无效，也不能据此关闭人员绑定合同。

### OA-B5-06A 加班时间列 metadata

入库时间：2026-08-10 17:09（Asia/Shanghai）。两张截图横向互补，通过行序和重叠字段
对齐。

| evidence_id | instance_fingerprint | ordinal_position | column_name | column_type | is_nullable | column_default |
|---|---|---:|---|---|---|---|
| `OA-B5-06A` | `dfaaa4be76e74040` | 8 | `field0100` | `datetime` | `YES` | `<NULL>` |
| `OA-B5-06A` | `dfaaa4be76e74040` | 9 | `field0099` | `datetime` | `YES` | `<NULL>` |

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-06a-p01.png`](screenshots/oa-b5-06a-p01.png) | `c60648e58043eb1bb80c38810bd5d424027811eb3703fc3665744c1f37cb675d` |
| [`screenshots/oa-b5-06a-p02.png`](screenshots/oa-b5-06a-p02.png) | `1683b5a285ee061d5943b77b9c75b14edbbba48a582d779dcdcba580747e9ccb` |

技术判断：两个候选端点列均为可空、默认 NULL 的无时区 `datetime`。该 metadata 不证明
两个字段谁是开始/结束，也不证明其业务时区；这些解释仍需合同或责任方确认。

### OA-B5-06B 加班时间范围与异常摘要

入库时间：2026-08-10 17:09（Asia/Shanghai）。单张截图完整显示七项纵向指标。

| metric | metric_value |
|---|---|
| `earliest_start` | `2001-02-03 18:00:00` |
| `latest_start` | `2026-08-10 06:30:00` |
| `earliest_end` | `2001-02-21 20:30:00` |
| `latest_end` | `2026-08-16 16:00:00` |
| `rows_with_missing_endpoint` | `31` |
| `non_positive_intervals` | `391` |
| `cross_midnight_rows` | `1985` |

共同 `evidence_id=OA-B5-06B`，`instance_fingerprint=dfaaa4be76e74040`。

| 原图 | SHA-256 |
|---|---|
| [`screenshots/oa-b5-06b.png`](screenshots/oa-b5-06b.png) | `76fff8f46c1a97be3ccf5c82d4216bdc3a00a89c5fae61897de21521066d4ae2` |

技术判断：当前快照有 31 行缺少至少一个端点、391 行非正区间，均须在计算前隔离或按
fail-closed 规则处置；1985 行跨自然日，需要明确的跨日计算口径，但不能仅因跨日就视为
异常。四个极值是各列独立聚合，不能推断为同一条明细的成对端点。时间字段业务时区仍未
签字。

### 原始执行日志的返回行数（不含结果单元格值）

| 原节 | 日志事实 | 仅凭原日志可作的判断 |
|---|---|---|
| 0 | 环境查询返回 1 行 | 值未知，等待 `OA-B5-00` |
| 1a | `formmain_0171` metadata 返回 19 行 | 列值未知，不能确认结构 |
| 1b | `formson_0172` metadata 返回 12 行 | 列值未知，不能确认结构 |
| 1c | 声明式外键查询返回 0 行 | 可知没有命中声明式 FK；不能据此否定约定式关联 |
| 1d | 基数摘要返回 1 行 | 计数未知 |
| 1e | 分布查询返回 20 行 | 原 SQL 有 `LIMIT 20`，可能被截断；不能视为完整分布 |
| 2a | `col_summary` metadata 返回 64 行 | 相关列值未知 |
| 2b | 全库 `state` 分布返回 5 行 | 出现 5 个分组；具体值未知，尚不能建立封闭映射 |
| 2c | 加班单关联覆盖率返回 1 行 | 计数未知 |
| 2d | 加班单 `state` 分布返回 5 行 | 具体状态和计数未知 |
| 3a | `ctp_enum_item` metadata 返回 17 行 | 相关列值未知 |
| 3b | `field0096` 原始分布返回 4 行 | 第四组可能为 NULL 或未知值，等待结果 |
| 3c | 加班类别映射返回 4 行 | 标签和计数未知，不能确认三项闭集 |
| 3d | COUNT 查询返回 1 行 | 返回 1 行不等于 `unmapped_rows=0`；值未知 |
| 4 | 请假类别映射返回 13 行 | 标签和计数未知 |
| 5a | `org_member` metadata 返回 61 行 | 相关列值未知 |
| 5b | `[42S22][1054] Unknown column 'code' in 'field list'` | 原 SQL 缺少 `FROM org_member`；不是缺列证据 |
| 5c | 重复分布查询返回 1 行 | 表明数据库默认比较口径下至少命中一种重复次数档位；具体重复规模未知 |
| 6 | 时间范围摘要返回 1 行 | 时间范围和异常计数未知 |

两项结果行数形成了必须核对的潜在合同差异信号：`state` 有 5 个分组，多于口述的
`3/0/2/NULL` 四值集合；加班类别有 4 个分组，多于代码目录声明的三个中文标签。
加班类别的第四组可能只是原始 NULL，但在看到结果值前不能假定。当前不修改运行时代码。

## 4. 修正与补强

原 `5b` 的聚合表达式没有表来源，已补为：

```sql
FROM org_member;
```

截图专用脚本还补强了以下边界：

- 每条结果自带稳定 `evidence_id` 和脱敏 `instance_fingerprint`，避免截图与查询或实例错配；
- 将原始 NULL 显式显示为 `<NULL>`，避免与空字符串混淆；
- 关联覆盖率按主单聚合，暴露一单多条 `col_summary` 导致的行膨胀；
- 加班/请假枚举分别统计缺失枚举项、NULL 标签以及空白标签；
- `org_member.code` 同时按数据库默认排序规则和逐字节口径检查重复；
- `org_member.code` 将 NULL、零长度和纯空白分别计数，并按纵向指标输出以便完整截图；
- `org_member` metadata 同时筛出可能的有效/停用/删除状态列，避免把历史成员重复误判为当前在职绑定冲突；
- 时间摘要单独统计缺失端点，避免 NULL 被异常区间统计静默忽略；
- 时间和 code 宽表改为纵向指标，减少横向截列风险；
- 用定向 metadata 取代 64/61 行全表结构截图，减少泄露面和漏页风险。

`formmain_id` 仅出现在批准的只读取证脚本中，用于验证候选关联列；在 OA-FK-01/05
签字前仍不得进入生产查询或运行时代码。

## 5. 逐张接收台账

| 顺序 | evidence_id | 对应合同 | 状态 | 结果转录/初步判断 | 证据文件 |
|---:|---|---|---|---|---|
| 1 | `OA-B5-00` | OA-TIME-01（部分） | `REVIEWED` | MySQL `8.0.26`；`szoa`；会话 `+08:00`；系统 `CST`；不能单独证明业务时区语义 | [`p01`](screenshots/oa-b5-00-p01.png)、[`p02`](screenshots/oa-b5-00-p02.png) |
| 2 | `OA-B5-01A` | OA-FK-01/05、OA-SCHEMA-02（部分） | `PARTIAL` | 已转录 9 行 metadata；缺少 `evidence_id`/`instance_fingerprint`，且 `MUL` 不证明 FK 或 1:N | [`p01`](screenshots/oa-b5-01a-p01.png)、[`p02`](screenshots/oa-b5-01a-p02.png) |
| 3 | `OA-B5-01C` | OA-FK-01 | `REVIEWED` | 声明式 FK 数量为 `0`；不能否定约定关联或单独关闭 OA-FK-01 | [`p01`](screenshots/oa-b5-01c-p01.png)、[`p02`](screenshots/oa-b5-01c-p02.png) |
| 4 | `OA-B5-01D` | OA-FK-01/05 | `REVIEWED` | 117207 条明细父引用非空且无孤儿；42951 个不同父引用覆盖全部主表记录；01E 已进一步确认 1:N 分布 | [`p01`](screenshots/oa-b5-01d-p01.png)、[`p02`](screenshots/oa-b5-01d-p02.png) |
| 5 | `OA-B5-01E` | OA-FK-01/05 | `REVIEWED` | 26741 个单明细主单、16210 个多明细主单、0 个零明细主单；合计 42951，最大 84 条明细 | [`p01`](screenshots/oa-b5-01e-p01.png)、[`p02`](screenshots/oa-b5-01e-p02.png) |
| 6 | `OA-B5-02A` | OA-STATUS-01 | `REVIEWED` | `STATE smallint NULL/MUL`；`FORM_RECORDID bigint NULL/MUL`；索引标志不证明状态语义 | [`p01`](screenshots/oa-b5-02a-p01.png)、[`p02`](screenshots/oa-b5-02a-p02.png) |
| 7 | `OA-B5-02B` | OA-STATUS-01 | `REVIEWED` | 全库状态为 `3/0/2/NULL/1`；额外 `state=1` 共 198 行且语义未知，封闭映射未确认 | [`p01`](screenshots/oa-b5-02b-p01.png)、[`p02`](screenshots/oa-b5-02b-p02.png) |
| 8 | `OA-B5-02C` | OA-STATUS-01 | `REVIEWED` | 42951 条主单全部且各自仅关联一条 summary；状态业务语义未确认 | [`p01`](screenshots/oa-b5-02c-p01.png)、[`p02`](screenshots/oa-b5-02c-p02.png)、[`p03`](screenshots/oa-b5-02c-p03.png) |
| 9 | `OA-B5-02D` | OA-STATUS-01 | `REVIEWED` | 五组共 42951 单且 join/去重数一致；`state=1` 有 1 单，所有状态语义仍待签字 | [`p01`](screenshots/oa-b5-02d-p01.png)、[`p02`](screenshots/oa-b5-02d-p02.png) |
| 10 | `OA-B5-03A` | OA-ENUM-01/02 | `REVIEWED` | `ID bigint PRI`；`SHOWVALUE varchar(255) NULL`；只确认列形状 | [`p01`](screenshots/oa-b5-03a-p01.png)、[`p02`](screenshots/oa-b5-03a-p02.png) |
| 11 | `OA-B5-03C` | OA-ENUM-02 | `REVIEWED` | 加班费 112022、调休 5066、义务加班 89、原始 NULL 30；合计 117207 | [`图`](screenshots/oa-b5-03c.png) |
| 12 | `OA-B5-03D` | OA-ENUM-02 | `REVIEWED` | 原始 NULL 30；缺失枚举项、NULL 标签、空白标签均为 0 | [`p01`](screenshots/oa-b5-03d-p01.png)、[`p02`](screenshots/oa-b5-03d-p02.png)、[`p03`](screenshots/oa-b5-03d-p03.png) |
| 13 | `OA-B5-04A` | OA-ENUM-01 | `REVIEWED` | 13 组完整映射已闭合，合计 9896；含原始 NULL 2 行 | [`p01`](screenshots/oa-b5-04a-p01.png)、[`p02`](screenshots/oa-b5-04a-p02.png) |
| 14 | `OA-B5-04B` | OA-ENUM-01 | `REVIEWED` | 原始 NULL 2；缺失枚举项、NULL 标签、空白标签均为 0 | [`p01`](screenshots/oa-b5-04b-p01.png)、[`p02`](screenshots/oa-b5-04b-p02.png) |
| 15 | `OA-B5-05A` | OA-MEMBER-01/02 | `REVIEWED` | 已确认 `ID/CODE/STATE/IS_ENABLE/IS_DELETED/STATUS` metadata；生命周期语义未知 | [`p01`](screenshots/oa-b5-05a-p01.png)、[`p02`](screenshots/oa-b5-05a-p02.png)、[`p03`](screenshots/oa-b5-05a-p03.png) |
| 16 | `OA-B5-05B` | OA-MEMBER-02 | `REVIEWED` | 总数 846；零长度 code 10，NULL/纯空白 0；按用户确认口径排除 10、候选 836 | [`图`](screenshots/oa-b5-05b.png) |
| 17 | `OA-B5-05C` | OA-MEMBER-02 | `REVIEWED` | 两种比较口径均为 1 个重复组、2 条记录、组内最大 2；未披露编码明文 | [`p01`](screenshots/oa-b5-05c-p01.png)、[`p02`](screenshots/oa-b5-05c-p02.png)、[`p03`](screenshots/oa-b5-05c-p03.png)、[`p04`](screenshots/oa-b5-05c-p04.png) |
| 18 | `OA-B5-06A` | OA-TIME-01、OA-SCHEMA-02（部分） | `REVIEWED` | `field0100/field0099` 均为可空 `datetime`；端点角色和时区语义未签字 | [`p01`](screenshots/oa-b5-06a-p01.png)、[`p02`](screenshots/oa-b5-06a-p02.png) |
| 19 | `OA-B5-06B` | OA-TIME-01/03（部分） | `REVIEWED` | 缺端点 31、非正区间 391、跨日 1985；须隔离异常并确认跨日/时区口径 | [`图`](screenshots/oa-b5-06b.png) |

追加调查不计入原 19 项：

| evidence_id | 状态 | 结果转录/初步判断 | 证据文件 |
|---|---|---|---|
| `OA-B5-05D` | `REVIEWED` | 四个生命周期取值组合合计 846 条、可用 code 836 条；各取值业务语义未知 | [`p01`](screenshots/oa-b5-05d-p01.png)、[`p02`](screenshots/oa-b5-05d-p02.png) |
| `OA-B5-05E` | `REVIEWED` | 唯一重复组的两条记录均在 `1/0/1/1` 组合；不能自行解释其有效性 | [`p01`](screenshots/oa-b5-05e-p01.png)、[`p02`](screenshots/oa-b5-05e-p02.png) |

## 6. 不能由本轮 SQL 单独关闭的事项

- `state=3` 是否精确代表最终批准，仍需 OA 流程配置或管理员/业务签字。
- `ctp_enum_item.showvalue` 的中文标签是否构成业务封闭字典，仍需业务签字和未知值处置确认。
- `org_member.code` 是否就是得力 `employee_number`，仍需跨系统业务字典或脱敏对账证明。
- `org_member` 的生命周期字段值仍未获得业务解释。05E 只证明重复组落在精确的
  `member_state=1/is_enable=0/is_deleted=1/member_status=1` 组合，不能自行将该组合解释为
  当前有效或已失效。
- 数据库/会话时区配置不能单独证明无时区 `DATETIME` 的业务解释；`field0100/field0099`
  的开始/结束角色、跨日处理和异常隔离口径也仍需确认。
- `OA-B5-01A` 原图没有显示 `evidence_id` 或 `instance_fingerprint`，签字前仍需补充查询与
  实例一致性证明；当前上下文对应关系只足以维持 `PARTIAL`。
- 日志没有绑定执行瞬间的脚本哈希，批准环境名称/来源也尚未收到。
- 本轮只覆盖 B-5 相关表和列，不能关闭六类表单的全部 OA-SCHEMA、FK、状态、版本和水位合同。

## 7. 下一步

本轮原 19 项已形成 18 项 `REVIEWED`、1 项 `PARTIAL`，追加的 `OA-B5-05D/05E` 也均为
`REVIEWED`。截图、转录和 SHA-256 已入库；当前无需继续按清单逐项截图。

剩余的技术证据缺口是 `OA-B5-01A`：暂不要求立即重拍，但签字前仍须补齐
`evidence_id`、`instance_fingerprint` 或等价的查询/实例一致性证据。除此之外，审批状态、
枚举业务含义、跨系统人员绑定、生命周期值、时间端点与时区等仍是业务/管理员签字事项，
不能因截图采集完成而自动标记 `VERIFIED`。

## See Also

- [OA 实库证据索引](../README.md)
- [OA 表单映射签字矩阵](../../../contracts/oa-attendance-form-mapping-signoff-matrix.md)
