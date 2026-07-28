# 考勤异常总览报表目录

状态：V1.9 首版目录；直接产生范围与待接线上游已分开标注

报表标识：`EXCEPTIONS`

接口：`GET /api/v1/attendance-reports?reportType=EXCEPTIONS&period=YYYY-MM&legalEntityId=...`

## 1. 权威来源与计算边界

异常报表读取已发布的月度报表投影，不读取浏览器夹具，也不在前端推算异常。异常事实来自同一计算版本的 stable case/current state；同一 stable case 的历史 observation、transition 和旧计算版本不重复计数。

当前报表只返回安全摘要，不返回原始打卡载荷、精确坐标、设备密钥、附件、敏感请假原因、OA 备注全文或其他组织是否存在的信息。

## 2. 当前计算结果可直接产生

当前 `DeterministicAttendanceCalculator → AttendanceReportFactProjector` 链路可以直接产生：

- `LATE`
- `EARLY_DEPARTURE`
- `MISSING_PUNCH_PENDING`
- `MISSING_PUNCH_OVERDUE`
- `AMBIGUOUS_PUNCH_MATCH`
- `EVIDENCE_CONFLICT`
- `OVERTIME_DOCUMENT_MISSING_OR_LATE`

`AMBIGUOUS_PUNCH_MATCH_PENDING` 和 `AMBIGUOUS_PUNCH_MATCH_OVERDUE` 的计算 reason code 统一投影为 `AMBIGUOUS_PUNCH_MATCH`，不会再因 `ResultCategory.ABSENCE` 被误写为 `MISSING_PUNCH_OVERDUE`。

## 3. 已定义目录与可信 current-case 投影

下表类型均已在 `AttendanceExceptionModels.ExceptionType` 定义，`AttendanceReportFactProjector.projectCurrentException(...)` 可以把 reconciler 的 current state 投影为安全异常事实，正式发布 use-case 也能在没有日事实时写入该 current-case 事实。

但当前仓库尚无异常 case 持久化/读取适配器和月结编排接线。因此，除上一节列出的计算器直接类型外，下表其他类型属于“目录与可信投影已支持、自动产生未接线”，不能据此声称生产数据已经生成。

| 稳定代码 | 业务含义 | 默认月结影响 | 主要来源 |
| --- | --- | --- | --- |
| `LATE` | 迟到；保留原始迟到与计罚迟到两个口径 | 不阻断 | 日结果/规则命中 |
| `EARLY_DEPARTURE` | 早退 | 不阻断 | 日结果/规则命中 |
| `MISSING_PUNCH_PENDING` | 单边缺卡，仍在补正期限内 | 阻断 | 有效打卡与班次工作段 |
| `MISSING_PUNCH_OVERDUE` | 单边缺卡已超过补正期限 | 阻断 | 有效打卡与班次工作段 |
| `ABSENCE` | 无工作、无有效单据且已超过处理期限的缺勤 | 阻断 | 日结果 |
| `NO_ATTENDANCE_GROUP` | 人员没有可解析的考勤组 | 阻断 | 考勤组分配 |
| `NO_SHIFT_OR_CALENDAR` | 业务日没有唯一班次或工作日历 | 阻断 | 排班/日历 |
| `AMBIGUOUS_PUNCH_MATCH` | 打卡无法唯一归属工作段 | 阻断 | 打卡匹配 |
| `CROSS_MIDNIGHT_REVIEW_REQUIRED` | 跨日归属无法自动裁决 | 阻断 | 截止时间/班次 |
| `OVERTIME_DOCUMENT_MISSING_OR_LATE` | 存在计划外在岗证据，但没有有效加班单或已超期 | 不阻断 | 得力打卡与 OA 加班单 |
| `LEAVE_PUNCH_CONFLICT` | 请假与打卡证据发生冲突 | 不阻断 | OA 请假与得力打卡 |
| `EVIDENCE_CONFLICT` | 互斥证据重叠且不能唯一裁决 | 阻断 | 多来源证据 |
| `OUTING_OR_TRIP_INCOMPLETE` | 外出/出差手续缺少必要时间、地点或事由 | 阻断 | OA 外出/出差 |
| `OA_APPROVAL_STATUS_UNKNOWN` | OA 审批状态未映射或状态合同变化 | 阻断 | OA |
| `OA_PERSON_REFERENCE_INVALID` | OA 选人 ID 无法唯一映射到 `org_member.code` | 阻断 | OA/人员映射 |
| `EMPLOYEE_UNMATCHED` | 外部工号无法唯一匹配内部员工 | 阻断 | 得力/OA/员工主数据 |
| `DUPLICATE_SOURCE_RECORD` | 来源业务键重复或版本冲突 | 阻断 | 来源接入 |
| `SOURCE_SCHEMA_CHANGED` | 来源表/API 结构不符合已签字合同 | 阻断 | 来源接入 |
| `SOURCE_SYNC_STALE` | 来源水位超过新鲜度阈值 | 阻断 | 同步批次 |
| `POST_CLOSE_SOURCE_CHANGE` | 月结后来源发生变化 | 阻断 | 月结/重算 |
| `INPUT_INTEGRITY_ERROR` | 时间逆序、非法区间或其他输入完整性错误 | 阻断 | 标准化 |
| `EARLY_RETURN_CANDIDATE` | 有效单据或最终裁定前的候选早退 | 不阻断 | 日结果/重算 |

`EARLY_RETURN_CANDIDATE` 确定后映射到正式早退或被重算解决。

## 4. 首版字段

- 业务日期、工号、姓名、发生时组织；
- 异常类型、严重度、当前状态、异常分钟；
- 不含敏感原文的证据摘要；
- 不透明行引用及计算版本下钻引用。

允许状态为 `OPEN`、`PENDING_EVIDENCE`、`PENDING_REVIEW`、`RESOLVED`。查询状态只可缩小已授权结果。

## 5. 数据权限不变量

1. 读取必须具备 `ATTENDANCE_REPORT:READ`。
2. 服务端根据当前主体、有效角色和有效数据范围计算可见集合；请求中的组织/员工只能缩小范围。
3. 法人、当前组织树、员工当前有效任职、部门负责人范围和本人范围均在 SQL 层先过滤，再进入聚合、计数和分页；发生时组织仅用于事实展示与汇总，不扩大负责人当前可见人员集合。
4. `SELF` 仅使用会话绑定员工，不接受客户端提交员工 ID 作为授权依据。
5. 系统管理员没有隐式越权；没有明确报表能力和有效数据范围时不得读取。
6. 列表与导出绑定同一投影版本、公式版本、范围摘要、筛选和查询指纹。
7. 导出创建和下载分别要求 `ATTENDANCE_REPORT:EXPORT_CREATE` 与 `ATTENDANCE_REPORT:EXPORT_DOWNLOAD`，两次操作都要密码复核；下载前再次解析当前数据范围。
8. 权限撤销、范围缩小、投影变化、文件校验失败或任务过期后，不返回导出内容。

## 6. 后续候选（未实现）

以下类型先记录，不以固定零值或“正常”状态代替：

- 地理围栏异常、设备可信度异常、代打卡风险；
- 连续多日异常、异常 SLA、责任队列和重复发生热点；
- 假期内打卡、单据重叠的细分类；
- 销假未同步、调休账户透支、年假过期前预警；
- 组织连续性未知、层级循环/孤儿、身份复用；
- 小样本抑制后的跨组织趋势和管理驾驶舱异常率。

候选转为正式类型前，需要冻结稳定代码、来源映射、严重度、月结影响、字段白名单和跨范围负向测试。
