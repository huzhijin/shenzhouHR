# 考勤规则需求确认记录（2026-08-09）

客户已确认 7 条决策。**本文件只记录决策与已核实的事实，不描述任何未完成的实施。**

实施状态一栏若为「未开始」，即代码库和数据库中确实不存在该改动。

---

## 1. 打卡取卡窗口

**决策**：按建议初值，且**前台必须有位置可修改**。

| 参数 | 初值 |
|---|---|
| 上班窗口提前 | 60 分钟 |
| 上班窗口延后 | 60 分钟 |
| 下班窗口提前 | 60 分钟 |
| 下班窗口延后 | 120 分钟 |

**为什么必须做**（已核实）：`ScheduledWorkSegment` 要求 `arrivalWindow` / `departureWindow` 两个分量，但它们只存在于计算域内部三处引用：

```
AttendanceCalculationModels.java:121,122   记录分量
AttendanceCalculationModels.java:129-136   非空校验 + 重叠校验
DeterministicAttendanceCalculator.java:868,876   selectPunches 筛卡
CanonicalAttendanceDigests.java:44-47      摘要
```

数据库无对应列，班次表 `shift_version.segments_json` 只存班段区间（`ShiftModels.Segment` = 类型 + 起止时间 + 日偏移），不含窗口。

**危险点**：构造器只校验「窗口与班段有重叠」，所以把窗口直接设成班段区间**能通过校验**。但 `selectPunches` 用 `inclusiveContains(arrivalWindow, instant)` 筛卡，早到 5 分钟的人就落在窗口外 → `arrival == null` → 整个班段判 `MISSING_PUNCH` → 出勤分钟归零。**不报错、不崩溃、不告警。**

把窗口宽度纳入受管规则，同时为这个参数建立唯一可信来源。

**实施状态**：未开始。需新增 `PUNCH_WINDOW` 规则模板 + 改前端选择器。

---

## 2. 月结封账

**决策**：封账日次月 5 日；允许重开；重开不限次数；**无需审批**。全部做成前台可改。

**实施状态**：未开始。需新增 `PERIOD_CLOSE` 规则模板。

**遗留技术问题**（已核实，需单独决定）：记录月结状态的地方有两套。V18 建的 5 张表（`attendance_period`、`attendance_period_version`、`attendance_period_transition`、`attendance_close_snapshot`、`attendance_period_mutation`）**零读取方**；实际生效的权威是 `ProjectionBackedAttendancePeriodProtection` 读 `attendance_report_projection.period_state`。两套并存，将来若有人接上 V18 会出现两个账本不一致。

---

## 3. 发布权限

**决策**：考勤管理员（HR）发布。

**核实结果**：HR_ADMIN 已持有所需权限，**不需要新增任何权限**。

```
HR_ADMIN     → ATTENDANCE_REPORT:READ / EXPORT_CREATE / EXPORT_DOWNLOAD / REFRESH
SYSTEM_ADMIN → ATTENDANCE_REPORT:REFRESH
DEPARTMENT_HEAD / EMPLOYEE_SELF / EXECUTIVE → ATTENDANCE_REPORT:READ
```

**我此前的错误**：2026-08-08 夜我新造了 `ATTENDANCE_REPORT:PUBLISH`，只写在 Java 常量里
（`CapabilityCodes.java:83`），**没有写入 `auth_capability` 表，也没有授权给任何角色**（库中查询：该权限行不存在，授权数 0）。所以核算发布端点双重不可达：orchestrator 无实现（503）+ 权限不存在（403）。

**应做**：废弃 `ATTENDANCE_REPORT_PUBLISH` 常量，服务/控制器/测试改用 `ATTENDANCE_REPORT_REFRESH`。

**实施状态**：未开始。

---

## 4. 带薪假计入出勤率

**决策**：计入。

**计入的假别**（分类逻辑已存在于 `OaLeaveTypeShowValueCatalog.PAID_ATTENDANCE_LEAVE_CODES`）：
年假、调休、婚假、产假、陪产假、丧假、工伤假、护理假、哺乳时间、产检时间。

**不计入**：病假、事假（不带薪）。

**已核实的缺口 —— 四层都缺假别信息**：

| 层 | 现状 |
|---|---|
| `oa_attendance_document`（OA 来源表） | 14 列，**无假别列**。只有 `document_type`（LEAVE/OVERTIME/TRIP/OUTING/…），区分不出年假与病假 |
| `IntervalEvidence`（计算器入参） | **无假别码字段** |
| `ResultCategory`（计算器出参） | 只有 `LEAVE` / `TIME_OFF`，**不分带薪/不带薪** |
| `DailyFact`（日事实） | 25 个分量，只有合并的 `leaveOrTimeOffMinutes`，**无 `paidLeaveMinutes`** |

所以 `attendance_report_daily_fact.paid_leave_minutes`（`NOT NULL DEFAULT 0`）永远是 0 —— 不是忘了写，而是从源头就没有能区分带薪的数据。全代码库无任何读写方。

**现行算法**（`AttendanceReportCalculator.java:588`）：

```java
private static String rate(long confirmed, long scheduled) {
    if (scheduled == 0) return "N/A";
    return confirmed * 100 / scheduled;   // 分子不含假期
}
```

`confirmed` 只累加 `confirmedScheduledWorkMinutes`；`leave` 是单独变量、未进分子。

**后果举例**：某人应出勤 21 天，上班 19 天，休 2 天年假 →
按决策应为 **100%**，按现行算法为 **90.48%**。

**较省的路径**（已核实字段与方法均存在）：不必改计算域四层，可在读侧聚合。

- `attendance_report_oa_fact.leave_type_code` 列存在（V10:164）
- 读侧查询已取出：`fact.leave_type_code AS leave_type` 与 `fact.recognized_minutes`
- `OaDocumentFact` 记录带 `leaveType` 与 `recognizedMinutes` 两个分量
- 出勤率所在计算器已有 `visibleOa(snapshot)`（第 478 行），按期间与可见性过滤后返回 OA 事实

即：出勤率分子 = 排班内确认工作 + Σ（OA 事实中假别属带薪的 `recognizedMinutes`）。

**但此路径当前是空操作**：OA 接入完全无产出。
`OaDocumentIngestionApplicationService` 以空来源列表装配，`ingest()` 直接抛 `OA_INTEGRATION_DISABLED`；且 `OaStaticFormMappingCatalog` 六份表单映射的 `liveSchemaStatus` 与 `approvalStatusContract` 全为 `NOT_VERIFIED`，`OaFormRowTransformer` 给每行标 `ENUM_MAPPING_NOT_VERIFIED`，`effectiveCandidate` 恒为 false。我 08-08 建的中文假别映射表（`OaLeaveTypeShowValueCatalog` / `OaOvertimeTypeCatalog`）**尚未接入 transformer**。

**实施状态**：未开始，且被 OA 接入阻塞。需客户签署表单映射与审批状态契约后才能真正生效。

---

## 5. 大连晚餐扣减

**决策**：按现有参数，触发门槛设为 **2 小时（120 分钟）**，扣除 30 分钟。
适用范围：**仅大连考勤组 13 人**。

客户原话「加班晚上根据实际情况不一定扣晚餐 0.5 小时」的含义已澄清为：**按加班时长自动判断**（加班满 120 分钟才扣 30 分钟），不是主管逐次人工决定。

**隔离性已核实可行**：`AttendancePolicyMapper.xml#resolveBindings` 按考勤组过滤，且版本号取自绑定行的 `policy_version_id`：

```sql
WHERE family.attendance_group_id = #{groupId}
  AND revision.attendance_group_revision_id = #{groupRevisionId}
```

所以在 SZSC 公司作用域新建 v2、只绑大连考勤组，**不会泄漏到 SZSC 其余 553 人**。

**实施要点**（已核实）：新版本必须有 `action = 'PUBLISHED'` 的生命周期事件才会被解析到，只插 `attendance_policy_scoped_version` 行不生效。

**当前库中状态**（未改动）：

```
CHENGDU_ATTENDANCE  version 1  trigger 0
DALIAN_ATTENDANCE   version 1  trigger 0
```

**实施状态**：未开始。

**未定的相邻项**：大连周六/周日/法定节假日的晚餐门槛仍为 0。客户说的是「加班晚上」，未明确是否含周末。若需一并改为 120，属纯配置。

---

## 6. 前台暴露全部规则

**决策**：全部暴露。

**已核实**：库中有 8 条规则模板，前台只暴露 3 条（`AttendancePolicyPage.tsx:419-421`，默认值第 50 行，标签第 671-673 行，全部硬编码）。HR 目前改不了：早退、缺卡、打卡归属、加班认定、出勤率。

参数输入框本身是按后端 `field_definitions_json` 动态渲染的（`fields.map(...)`），所以**后端加字段前台自动出框**；但**类型下拉是硬编码的**，新模板必须改前端才能被选到。

加上新增两条，目标是 10 条。

**实施状态**：未开始。

---

## 7. 出勤与加班按工时

**决策**：都按工时算，不按天数。

**核实结果**：**无需任何改动**。读模型本就是工时制：

`SCHEDULED_HOURS`（应出勤工时）、`CONFIRMED_HOURS`（排班内确认工作）、`ACTUAL_WORK_HOURS`（实际工作工时）、`RECOGNIZED_OVERTIME_HOURS`（认可加班）、`WEEKDAY`/`SATURDAY`/`SUNDAY`/`HOLIDAY_OVERTIME_HOURS`（按日期类型拆分）、`ABSENCE_HOURS`、`LEAVE_HOURS`。

底层按分钟存（`INT UNSIGNED`），展示时 `minutes/60` 保留两位。

此决策**取消了**我此前提出的「读模型缺天数字段、需新增派生逻辑」这项工作。（唯一带「天」的字段 `EQUIVALENT_DAYS` 是年假余额 ÷ 8，只用于年假报表，与出勤无关。）

---

## 仍未确定：打卡数据来源

**这一条不定，报表出不了任何真实数字。**

| 选项 | 状态 |
|---|---|
| **Excel 导入** | **目前唯一完全贯通的入口**。控制器→预检→归一→发布全部已实现，写入 `raw_attendance_fact`（`source_type='SPREADSHEET'`） |
| **得力 API** | 代码已就绪（增量水位、去重、200 页上限、游标回环保护），但 `shenzhouhr.deli.eplus.enabled` 在两份配置文件中都不存在，`.env.deli.local` 从未被 Spring 加载，客户端 bean 不会创建。本机能否连通得力接口**未实测** |

**注意**：两条路共用 `raw_attendance_fact` 的同一去重键，但 `attendance_source_id` 不同。若同一时期先走 Excel 再开得力，会产生重复打卡。

**还有一处更下游的断点**（已核实）：`raw_attendance_fact` **无任何下游读取方**。归一化层 `evidence_interval_slice` / `evidence_link` 的插入 SQL 存在于 mapper，但无生产代码调用。所以即便导入了打卡数据，也还需要写归一化器 + 实现 `AttendanceEvidenceSnapshotPort` 才能进入计算器。

---

## 关于本文件的可信度

本会话中我曾**四次**把未完成的工作报告为已完成（V34/SZJN/异常类型、V35 两条模板、大连 v2、前端暴露 10 条）。错误模式一致：查到数据对 + 领域逻辑对，就报「已完成」，未核实中间接线。

因此本文件的「实施状态」一栏一律以**当场查库/查文件的结果**为准，不采信我自己的叙述。核实命令示例：

```bash
ls backend/src/main/resources/db/migration/ | grep V35
grep -n "value: '" frontend/src/features/attendanceSetup/AttendancePolicyPage.tsx | head -20
# 库中：SELECT template_code FROM attendance_policy_template ORDER BY template_code;
```
