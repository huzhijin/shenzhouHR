# 神州 OA 插件：HR 时间账户部署契约

本文对应 Flyway `V41`—`V44`。它只描述 OA 与 HR 数据库之间的余额操作边界，不替代 OA 插件自己的部署说明。所有示例中的库名、主机和口令均为占位符。

## 上线边界

本次迁移不会读取历史 OA 单据猜测余额，也不会自动改写任何既有 `time_account.balance_hours`。

启用 OA 事件前，HR 必须书面确认：

1. 每个 `ANNUAL_LEAVE`、`TIME_OFF` 账户的 `balance_hours` 是切换时真实余额；
2. 余额与 `time_account_ledger_entry` 的金额合计完全一致；
3. 历史已批准请假、加班和销假是否回补已经单独确认；
4. 工号 `employee.employee_number` 在全库唯一，且有效任职和已发布调休策略无歧义。

缺少 V41 预占记录的历史销假会以 `SZSC_ORIGINAL_RESERVATION_NOT_FOUND` 阻断，必须由 HR 使用现有人工调整能力处理，不能由 OA 自动返还。

先执行只读预检脚本：

```bash
mysql --protocol=TCP \
  -h '<HR_DB_HOST>' -P '<HR_DB_PORT>' \
  -u '<HR_ADMIN_USER>' -p \
  '<HR_DB_NAME>' \
  < deploy/mysql/sql/verify-szsc-oa-time-account-cutover.sql
```

预检每个结果集都必须是零行；账户汇总结果必须由 HR 签字确认。该命令只读，不会执行迁移。

随后由既有 Flyway 发布链运行 V41、V42，不要把两个文件拆开手工执行。V41 建表、视图和专用审计主体；V42 建立存储过程。专用主体固定 UUID `41000000-0000-0000-0000-000000000001`，语义为 `SEEYON_OA_INTEGRATION`，不代表任何员工。

## OA 数据库账号最小权限

以下命令由 DBA 替换占位符后执行。建议限制为 OA 应用服务器的固定地址并强制 TLS：

```sql
CREATE USER 'szsc_oa'@'<OA_SERVER_IP>'
    IDENTIFIED BY '<LONG_RANDOM_PASSWORD>' REQUIRE SSL;

GRANT SELECT ON `<HR_DB_NAME>`.`szsc_oa_time_account_balance_v`
    TO 'szsc_oa'@'<OA_SERVER_IP>';

GRANT EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_leave_reserve`
    TO 'szsc_oa'@'<OA_SERVER_IP>';
GRANT EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_leave_confirm`
    TO 'szsc_oa'@'<OA_SERVER_IP>';
GRANT EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_leave_consume`
    TO 'szsc_oa'@'<OA_SERVER_IP>';
GRANT EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_leave_release`
    TO 'szsc_oa'@'<OA_SERVER_IP>';
GRANT EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_leave_return`
    TO 'szsc_oa'@'<OA_SERVER_IP>';
GRANT EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_time_off_credit`
    TO 'szsc_oa'@'<OA_SERVER_IP>';
GRANT EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_time_off_expire`
    TO 'szsc_oa'@'<OA_SERVER_IP>';
GRANT EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_time_off_can_reverse`
    TO 'szsc_oa'@'<OA_SERVER_IP>';
GRANT EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_time_off_reverse`
    TO 'szsc_oa'@'<OA_SERVER_IP>';
```

不要授予 V42 中 `szsc_oa_private_*` 过程的 `EXECUTE`，也不要授予任何基础表的 `SELECT`、`INSERT`、`UPDATE` 或 `DELETE`。

紧急停用时先解绑 OA 流程事件，再撤销执行权限；保留 V41 的操作和流水表用于审计：

```sql
REVOKE EXECUTE ON PROCEDURE `<HR_DB_NAME>`.`szsc_oa_leave_reserve`
    FROM 'szsc_oa'@'<OA_SERVER_IP>';
-- 对其余八个公开过程执行同样的 REVOKE。
REVOKE SELECT ON `<HR_DB_NAME>`.`szsc_oa_time_account_balance_v`
    FROM 'szsc_oa'@'<OA_SERVER_IP>';
```

版本化迁移没有自动 down migration。不要通过删除 V41 表来“回滚”，因为这会删除预占、幂等和返还审计证据。

## 工号、账户和事务规则

- OA 传入 `org_member.code`，HR 只按 `employee.employee_number` 全库精确匹配；0 人、多人或非在职均阻断。
- 业务日期必须恰好命中一个有效任职周期；过程不使用 `LIMIT 1` 掩盖歧义。
- 年假、调休请假只使用当前任职周期、对应年度的账户。
- 年假账户缺失时阻断，绝不自动授予年假。
- 调休加班入账时，若 `TIME_OFF` 账户缺失，会在唯一有效任职和唯一已发布策略下原子创建；人员、任职或策略为 0 条/多条时阻断。
- 每次余额变更前均检查 `balance_hours = SUM(ledger.amount_hours)`；不相等时返回 `SZSC_BALANCE_LEDGER_MISMATCH`。
- OA 过程自己开始和提交事务。调用方不得在同一连接上假设它参与 OA 数据库事务。

## 过程签名

参数类型为 MySQL 类型：

```text
szsc_oa_leave_reserve(
  VARCHAR(128) requestId,
  VARCHAR(128) employeeNo,
  VARCHAR(32) accountType,
  SMALLINT UNSIGNED accountYear,
  DECIMAL(12,2) hours,
  DATE businessDate,
  CHAR(64) payloadDigest)

szsc_oa_leave_confirm(
  VARCHAR(128) requestId,
  VARCHAR(128) eventId,
  CHAR(64) payloadDigest)

szsc_oa_leave_consume(
  VARCHAR(128) requestId,
  VARCHAR(128) eventId,
  CHAR(64) payloadDigest)

szsc_oa_leave_release(
  VARCHAR(128) requestId,
  VARCHAR(128) eventId,
  CHAR(64) payloadDigest)

szsc_oa_leave_return(
  VARCHAR(128) originalRequestId,
  VARCHAR(128) revocationRequestId,
  VARCHAR(128) eventId,
  DECIMAL(12,2) hours,
  DATE businessDate,
  CHAR(64) payloadDigest)

szsc_oa_time_off_credit(
  VARCHAR(128) overtimeLineId,
  VARCHAR(128) employeeNo,
  SMALLINT UNSIGNED accountYear,
  DECIMAL(12,2) hours,
  DATE overtimeBusinessDate,
  VARCHAR(128) eventId,
  CHAR(64) payloadDigest)

szsc_oa_time_off_expire(
  VARCHAR(128) employeeNo,
  SMALLINT UNSIGNED accountYear,
  VARCHAR(128) eventId,
  CHAR(64) payloadDigest)

szsc_oa_time_off_can_reverse(
  VARCHAR(128) overtimeLineId,
  CHAR(64) payloadDigest)

szsc_oa_time_off_reverse(
  VARCHAR(128) overtimeLineId,
  VARCHAR(128) eventId,
  CHAR(64) payloadDigest)
```

每个过程成功时恰好返回一个结果集、一行、以下八列：

```text
source_request_id
operation_status
account_type
account_year
affected_hours
balance_hours
reserved_hours
available_hours
```

除 `NO_CREDIT`、`NOOP` 的 `account_year` 为 `NULL` 外，其他列非 `NULL`。四个小时字段均为非负 `DECIMAL(12,2)`；检测到负可用或勾稽异常时直接抛错而不是返回负数。调用方必须校验结果集存在、只有一行、列齐全，并按动作校验状态白名单。

状态白名单：

| 过程 | 正常状态 |
|---|---|
| `leave_reserve` | `RESERVED`、幂等重放时可能为 `CONFIRMED` |
| `leave_confirm` | `CONFIRMED`；迟到的相同语义通知可能为 `CONSUMED` |
| `leave_consume` | `CONSUMED` |
| `leave_release` | `RELEASED` |
| `leave_return` | `RETURNED` |
| `time_off_credit` | `CREDITED`、`EXPIRED`；若先前已撤销则返回 `REVERSED`，审批端应视为冲突 |
| `time_off_expire` | `EXPIRED` |
| `time_off_can_reverse` | `NO_CREDIT`、`REVERSIBLE`、`ALREADY_REVERSED` |
| `time_off_reverse` | `NOOP`、`REVERSED` |

`NO_CREDIT`/`NOOP` 表示该加班明细从未入账，取消审批中单据时应按成功幂等处理。`can_reverse` 只是 OA 取消前的只读预检；`reverse` 会在事务里再次检查，最终检查失败必须阻止或告警跨库补偿，不能忽略。

## 摘要和幂等规范

摘要为规范串的 UTF-8 字节做 SHA-256，再输出小写 64 位十六进制。小时固定两位小数，日期固定 `yyyy-MM-dd`，字段以 ASCII `|` 连接：

```text
RESERVE|formId|employeeNo|accountType|year|hours|businessDate
CONFIRM|formId|eventId
CONSUME|formId|eventId
RELEASE|formId|eventId
RETURN|revocationFormId|originalFormId|hours|businessDate|eventId
CREDIT|lineId|employeeNo|TIME_OFF|year|hours|businessDate|eventId
EXPIRY|employeeNo|year|eventId
REVERSE|lineId|eventId
CAN_REVERSE|lineId
```

数据库不重算 Java 规范串，只验证格式，并对同一个动作、请求和事件比较摘要。相同幂等键携带不同摘要会抛出 `SZSC_IDEMPOTENCY_CONFLICT`。

原请假撤回/驳回后可以使用同一 `formId` 修改并重提；数据库会重新核对余额、递增内部 `reservation_cycle` 并建立新预占。生命周期幂等记录自动按该轮次隔离，因此旧版 OA 即使无法提供流程实例 ID，也不会把新一轮通知误认成上一轮重放；能取得流程实例 ID 时仍建议放入 `eventId`，方便排查。

## 生命周期和年末行为

```text
发起前 reserve
发起成功 confirm
审批通过 consume
驳回或撤回 release
销假审批通过 return

调休加班审批通过 credit
加班撤销前 can_reverse
加班撤销完成 reverse
```

- `expire` 只冲销 `balance_hours - reserved_hours`，审批中的预占在年末仍被保护。
- 次年批准该预占时，`consume` 同时减少余额和预占；次年驳回/撤回时，`release` 同事务写 `EXPIRY`，不会让已过期调休重新可用。
- 上年度加班到次年才批准时，`credit` 在一个事务内同时写 `OVERTIME_CREDIT` 和 `EXPIRY`，可用余额净增 `0`。
- 已过期年度的调休销假返还会在同一事务写 `RETURN` 和 `EXPIRY`，可用余额净增 `0`。
- 加班额度处于预占或已被消费、导致当前可用不足时，`can_reverse` 和 `reverse` 都以 `SZSC_TIME_OFF_CREDIT_NOT_REVERSIBLE` 阻断。

## 常用错误码

所有业务拒绝使用 SQLSTATE `45000`，`MESSAGE_TEXT` 为 `SZSC_*`。调用方不得依赖本地化数据库错误全文，只映射代码：

- `SZSC_EMPLOYEE_NOT_UNIQUE_OR_INACTIVE`
- `SZSC_EMPLOYMENT_NOT_UNIQUE_OR_INACTIVE`
- `SZSC_TIME_ACCOUNT_NOT_UNIQUE_OR_MISSING`
- `SZSC_TIME_OFF_POLICY_NOT_UNIQUE`
- `SZSC_BALANCE_LEDGER_MISMATCH`
- `SZSC_INSUFFICIENT_AVAILABLE_BALANCE`
- `SZSC_TIME_OFF_ACCOUNT_EXPIRED`
- `SZSC_HOURS_MUST_BE_HALF_HOUR_MULTIPLE`
- `SZSC_IDEMPOTENCY_CONFLICT`
- `SZSC_RESERVATION_NOT_CONFIRMED`
- `SZSC_ORIGINAL_RESERVATION_NOT_FOUND`
- `SZSC_RETURN_EXCEEDS_CONSUMED_HOURS`
- `SZSC_TIME_OFF_CREDIT_NOT_REVERSIBLE`

任何未列出的 SQL 异常同样必须 fail-closed，并由管理员依据关联的 OA 单号排查。

## Flyway 与测试库部署顺序

现场只允许由迁移账号通过既有 Flyway 链顺序执行，不能拆开复制存储过程：

1. 先运行 `verify-szsc-oa-time-account-cutover.sql`，所有异常结果集为零行并签署期初余额清单；
2. 在全新临时 MySQL 8 实例从 V1 执行到最新版本，确认迁移和 `validate` 均成功；
3. 在隔离测试库依次发布 V41（OA 集成表/视图）、V42（九个公开过程）、V43（年结运行/明细/集群租约）和 V44（年假管理员操作幂等记录）；
4. 以测试专用账号完成预占、确认、扣减、释放、返还、加班入账、失效、冲销、并发和重放验收；
5. 验收完成前保持 OA 事件绑定和 HR 年结任务关闭；生产发布仍由正式变更流程另行批准。

任何一步失败都停止，不得执行 `repair` 掩盖校验和差异，也不得手工修改
`flyway_schema_history`。

## 年假与调休期初余额准备

- 年假账户必须绑定员工在业务日期唯一有效的 `employment_period_id` 和已发布年假策略。通过 HR 年假期初能力建立时，台账使用 `OPENING`；同一个 `Idempotency-Key` 的重放不得重复写账。
- 历史调休期初余额不从 OA 单据反推。现场应形成签字清单，在一次 HR 管理事务中同时建立 `TIME_OFF` 账户和等额 `OPENING` 台账；账户余额必须等于台账合计。OA 数据库账号不得执行这项准备。
- 没有历史调休余额的员工不需要预建零余额账户。首笔已批准的调休类加班会在唯一人员、任职和策略前提下创建账户并写 `OVERTIME_CREDIT`。
- 年假账户缺失始终阻断；不得借助调休的首次入账逻辑自动授予年假。
- 任何导入或人工调整完成后，都重新执行余额/台账勾稽和负可用余额检查。禁止只改 `balance_hours`，也禁止以 `MAX(0, ...)` 隐藏差异。

## TIME_OFF 年结任务

HR 年结调度器默认关闭，只有完成上述测试库验收后才允许配置：

```text
SHENZHOUHR_TIME_OFF_YEAR_END_ENABLED=false
SHENZHOUHR_TIME_OFF_YEAR_END_CRON=0 30 2 2 1 *
SHENZHOUHR_TIME_OFF_YEAR_END_ZONE=Asia/Shanghai
SHENZHOUHR_TIME_OFF_YEAR_END_LOCK_LEASE=PT30M
SHENZHOUHR_TIME_OFF_YEAR_END_NODE_ID=<UNIQUE_HR_NODE_ID>
```

启用后，任务在次年处理上一年度 `TIME_OFF` 账户。集群节点通过
`time_off_year_end_lock` 的带期限租约竞争同一年度；每个员工调用
`szsc_oa_time_off_expire`。事件 ID 为
`HR_TIME_OFF_YEAR_END_V1:<year>:<sha256(employeeNo|year)前32位>`，摘要仍按
`EXPIRY|employeeNo|year|eventId` 计算，因此重跑不会重复失效。

运行汇总保存在 `time_off_year_end_run`，逐工号结果保存在
`time_off_year_end_run_item`。单个员工失败会记录 `FAILED` 后继续其他员工，批次最终以
`COMPLETED_WITH_FAILURES` 报错，不能把部分成功伪装成全成功；重复处理成功员工记为
`SKIPPED/IDEMPOTENT_REPLAY`。

错过定时窗口或修复单个失败后，可用一次性非 Web 进程安全重放指定年度。定时开关保持
关闭，命令退出后检查运行表和日志；数据库变量仍使用现场密钥注入，不写入命令历史或
文档：

```bash
java -jar shenzhou-hr.jar \
  --spring.main.web-application-type=none \
  --shenzhouhr.time-off-year-end.replay-year='<YEAR>' \
  --shenzhouhr.time-off-year-end.exit-after-replay=true
```

若只重放一个原事件，应由 OA 原重试/outbox 或年结运行明细提供原 `eventId` 和摘要，
再调用对应公开过程。不得生成一个“看起来合理”的新事件来绕过冲突。

## 跨库对账与故障处理

OA 与 HR 不使用 XA。日常先运行：

```bash
mysql --protocol=TCP \
  -h '<HR_DB_HOST>' -P '<HR_DB_PORT>' \
  -u '<HR_READONLY_USER>' -p \
  '<HR_DB_NAME>' \
  < deploy/mysql/sql/reconcile-szsc-oa-time-account.sql
```

脚本只读列出长期 `RESERVED/CONFIRMED`、已有 V41 预占但尚未 `CONSUMED` 的 HR
同步批准单、待 OA 侧分类的批准销假候选、余额台账不一致、负可用、返还累计差异、
调休入账/失效/冲销指针异常以及年结失败工号。HR 的通用 OA 投影尚未保留足以区分
余额假与非余额假的假别/原请假关联，因此“从未产生预占的批准请假”和“无 RETURN 的
批准销假”必须结合签署后的 OA 侧当前版本查询分类，不能把候选行直接当成余额异常。
OA 文档同步本身也必须先证明水位新鲜；否则“查询为空”不能证明 OA 没有待处理单据。

故障恢复遵循以下顺序：

1. 记录 OA 单号、原动作 `eventId`、原 SHA-256 摘要和 HR 错误码；
2. 先排除人员/任职/政策歧义及余额台账差异，不直接修改余额；
3. 使用原参数调用同一个公开过程。已完成的动作返回原结果，相同键不同摘要以
   `SZSC_IDEMPOTENCY_CONFLICT` 阻断；
4. 再运行对账脚本，确认台账、集成事实和 OA 最终状态一致；
5. 历史切换前单据缺少 V41 预占时走签字后的 HR 人工调整流程，不能伪造预占或
   `RETURN`。

已存在幂等事件的安全重放示例（变量必须来自原记录，不得猜测）：

```sql
SELECT source_event_id, payload_digest
FROM oa_time_account_operation_event
WHERE operation_kind = 'LEAVE_CONSUME'
  AND source_request_id = '<ORIGINAL_OA_REQUEST_ID>'
ORDER BY recorded_at DESC;

CALL szsc_oa_leave_consume(
    '<ORIGINAL_OA_REQUEST_ID>',
    '<ORIGINAL_EVENT_ID>',
    '<ORIGINAL_PAYLOAD_DIGEST>'
);
```

`RETURN`、`TIME_OFF_CREDIT`、`TIME_OFF_EXPIRY` 和 `TIME_OFF_REVERSE` 同理调用各自
公开过程。补偿只能形成规范台账，禁止 `UPDATE time_account SET balance_hours = ...`。

## 得力考勤数据准备门禁

得力同步保持两层默认关闭：`DELI_EPLUS_ENABLED=false` 且
`SHENZHOUHR_DELI_AUTO_SYNC_ENABLED=false`。授权未恢复时只运行离线合成 fixture 和
单元测试，不发起供应商请求；同步失败必须保留任务 `FAILED` 与安全错误码。

测试库需要验证持久化证据链时，只能在已经完成全部 Flyway 迁移的空白隔离库中执行一次
`backend/src/test/resources/fixtures/deli-eplus/evidence-chain.synthetic.sql`。该脚本仅含带
固定 `d311...` 标识的合成 `INSERT`；不得重复执行、不得用于共享测试库或生产库。配套 API
响应位于 `backend/src/test/resources/fixtures/deli-eplus/checkin-page.synthetic.json`。

API 数据的完整证据链为：

```text
attendance_sync_job/page
  -> raw_attendance_fact
  -> normalized_attendance_record
  -> employee_match_decision
  -> effective_attendance_event
  -> effective_event_lifecycle_fact + evidence_link
  -> attendance_recalculation_intent
```

授权恢复后，运维通过现有考勤源查询中的 `committedWatermark` 和
`lastSuccessfulSyncAt` 判断同步进度，并运行只读脚本。脚本另外区分仅 `SUCCEEDED`
的 `last_fully_successful_sync_at` 与包含 `PARTIALLY_QUARANTINED` 的时间，并单列最新
隔离数、安全错误码、历史隔离总数、全部隔离分页及隔离/未匹配事实；后续成功任务不会
隐藏历史隔离分页，部分隔离绝不能证明业务日期已完整：

```bash
mysql --protocol=TCP \
  -h '<HR_DB_HOST>' -P '<HR_DB_PORT>' \
  -u '<HR_READONLY_USER>' -p \
  '<HR_DB_NAME>' \
  < deploy/mysql/sql/verify-deli-attendance-evidence-chain.sql
```

水位或最近成功时间为空、过旧，或证据链异常查询非空，都表示业务日期尚未被证明完整
落库；不能据此结论“员工没有打卡”。测试 fixture 明确标记为
`SYNTHETIC_TEST_ONLY_NOT_VENDOR_DATA`，不得导入生产或作为考勤事实。
