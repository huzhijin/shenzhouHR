# 正式考勤报表投影发布接线

状态：`INTERNAL_USE_CASE_READY / AUTOMATIC_SETTLEMENT_WIRING_NOT_READY / TEMPORAL_IDENTITY_VALIDITY_NOT_VERIFIED`

## 1. 已可调用的内部入口

正式投影的受信任进程内入口是：

`AttendanceReportProjectionPublicationUseCase.publish(PublishCommand)`

Spring 实现为 `AttendanceReportProjectionPublisher`。结算服务或受控内部批处理可以注入该接口调用；本实现没有、也禁止新增“客户端提交事实”的 REST 接口。

调用按法人、自然月串行并在一个数据库事务中完成：

1. 校验并规范化投影 metadata、计算事实、OA 事实和时间账户事实；
2. 锁定法人行；
3. 按服务端 canonical 内容生成 `projection_digest` 与 `projection_version`；
4. 同法人、月份、摘要已存在且为 `PUBLISHED` 时幂等返回；
5. 锁内读取该法人、月份最新的已发布投影，拒绝更旧的 `dataAsOf` 和非法期间状态转换；
6. 写入 `DRAFT`；
7. 写入日事实、异常事实、OA 事实和时间账户事实；
8. 最后把同一投影从 `DRAFT` 转为 `PUBLISHED`。

任一步失败都会回滚整月投影。事实表只有插入路径；旧投影、旧闭月状态和旧事实不更新、不删除。

## 2. 受信任输入边界

- 日事实与异常事实必须由 `AttendanceReportFactProjector` 从已验证的 `DailyAttendanceResult` 产生，再绑定精确的 `employee_version_id` 和 `employment_assignment.assignment_id`。
- OA 事实只能包含标识、半开区间/时点、状态、来源版本和认定分钟，不能包含 OA 原因、备注、位置、目的地或原始表单。当前写入 SQL 会反查 `oa_attendance_document`、有效 normalized record、唯一 `MATCHED` 人员决定，并校验所提交人员、任职、组织和版本 ID 的关系一致性；它**尚未**证明这些版本在 OA 发生时有效。
- 时间账户事实只能包含账本版本和余额分量，不能包含调整原因或原始载荷。当前仓库尚无 Wave 6 账本持久化适配器、账本 snapshot 时点和任职周期引用，SQL 只能校验所提交人员与组织版本的关系，**不能**证明版本在账本快照时有效。调用方必须等该适配器提供已验证快照，不能用前端或临时表拼装。
- 日事实、异常事实的人员版本、任职、组织版本和法人会按 `business_date` 在写入 SQL 中形成唯一有效链。OA、时间账户目前只形成 ID/所属关系链，不等同于时点有效性校验。任一实际执行的事实写入行数不是 1 时，发布失败并回滚。
- `dataAsOf` 不得晚于发布时钟；摘要必须为小写 SHA-256；金额、分钟、标识长度与编码均 fail closed。
- 新内容的 `dataAsOf` 只能等于或晚于该月最新已发布版本。迟到的旧 snapshot 可以按原摘要幂等查询，但不能再次成为 latest。
- 期间状态转换只允许 `OPEN → OPEN/FROZEN/CLOSED`、`FROZEN → CLOSED`、`CLOSED → REOPENED`、`REOPENED → REOPENED/FROZEN/CLOSED`。`CLOSED → OPEN` 和已冻结/闭月后的不同内容普通重发均拒绝。`REOPENED` 必须来自上游已授权、已锁定的权威期间状态快照；本 use-case 不接受客户端状态。

## 3. 自动出数尚未接通

当前仓库有计算/闭月 use-case 和 port 定义，但没有可运行的月结编排实现，也没有以下完整上游：

1. 全法人、全月日结果持久化与读取适配器；
2. 闭月 snapshot 持久化及事务后的发布触发器；
3. OA 业务表到 canonical `oa_attendance_document` 的已签字查询/审批状态/主从键/时区适配器；
4. Wave 6 时间账户账本持久化与月度 snapshot 适配器。

因此，本文件不声称“月结后已自动生成报表”。完成上游后，接线位置应是结算编排器确认同一法人和月份的计算版本、OA 来源版本、账本版本与 period state 均已锁定之后，调用一次 `AttendanceReportProjectionPublicationUseCase`。不得从查询控制器、导出控制器、浏览器定时器或得力同步分页事务直接调用。

## 4. 时点身份锚点待确认

下列规则尚无已签字口径，不能由实现自行推断，也是自动接线的阻断项：

1. OA `POINT` 单据是否以 `point_instant` 作为人员版本、任职和组织版本的共同有效性锚点；
2. OA `INTERVAL`/`DATE_RANGE` 是否要求同一身份版本覆盖整个半开区间，还是在跨任职、跨组织版本时拆成多条事实；`DATE_RANGE` 转换为 instant 时使用哪个已冻结时区；
3. `employee_version` 的 `DATE` 有效期与任职/组织版本的 `DATETIME(6)` 有效期如何在法人员工跨时区场景中比较；
4. 时间账户以 ledger entry 的 `business_date`、close snapshot 时点、余额 `asOf` 还是其他不可变字段作为身份锚点；一个余额包含多任职周期时如何归属组织；
5. Wave 6 稳定合同需提供哪个 `employment_period_id`、ledger snapshot ID/digest 和 as-of 字段，报表写侧才能反查真实账本而非只信任调用方提供的 totals。

业务确认并完成适配后，应同时新增 SQL 有效期谓词和真实 MySQL 负例：过早/过期的 `employee_version_id`、`employment_assignment_id`、`organization_version_id` 必须导致事实写入为 0、整次事务回滚且不留下 `PUBLISHED` 投影。在此之前，不得把状态改为 `AUTOMATIC_SETTLEMENT_WIRING_READY`，也不得声称 OA/时间账户已完成发生时或快照时身份验证。

## 5. 接线验收条件

- 同一来源/计算/账本版本与同一事实内容重复触发，只返回同一投影；
- 任一事实内容变化产生新 `projection_version`，旧投影仍可按原版本读取；
- `FROZEN`、`CLOSED` 状态保存在各自不可变投影，导出绑定该版本；
- OA 引用或所提交员工/任职/组织版本 ID 关系不一致时，没有 `PUBLISHED` 投影残留；发生时有效性须在第 4 节口径签字并加入 SQL/MySQL 负例后另行验收；
- 时间账户所提交员工/组织版本 ID 关系不一致时，没有 `PUBLISHED` 投影残留；快照时有效性须在第 4 节口径签字并接入 Wave 6 snapshot 后另行验收；
- 没有 REST 路由能够反序列化 `PublishCommand` 或各类 `Verified*Fact`；
- 真实 MySQL 上验证事务回滚、并发幂等、闭月同版本读取和数据权限后，才可把状态改为 `AUTOMATIC_SETTLEMENT_WIRING_READY`。
