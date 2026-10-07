# 大连 2026 中秋/国庆日历（现场操作）

客户口径（已确认）：

- 中秋：9/25–9/27 放假
- 国庆：10/1–10/5 共 5 天
- **不调休、不补班**
- **10/6、10/7 正常上班**
- 9/20、10/10 若上班，仍要 **OA 加班单** 才出周末加班小时

全国安排是：国庆 10/1–7，且 9/20（日）、10/10（六）补班。大连和全国只差这几格。本操作只改**大连考勤组绑的那份日历**，不要改扬州/默认日历。

系统已有日类型，**不用发版代码**。改完必须发布日历版本，再重算江苏神州 9 月（10 月开月后再重算 10 月）。

## 1. 改完后这几天应是什么

| 日期 | 星期 | 全国默认 | 大连应写成 | 页面选项 |
| --- | --- | --- | --- | --- |
| 2026-09-20 | 日 | 特殊工作日（补班） | 周末休息 | **周末** |
| 2026-09-25 | 五 | 法定假日 | 法定假日 | 法定假日 |
| 2026-09-26 | 六 | 法定假日 | 法定假日 | 法定假日 |
| 2026-09-27 | 日 | 法定假日 | 法定假日 | 法定假日 |
| 2026-10-01～05 | 四～一 | 法定假日 | 法定假日 | 法定假日 |
| 2026-10-06 | 二 | 法定假日 | **正常上班** | **工作日** |
| 2026-10-07 | 三 | 法定假日 | **正常上班** | **工作日** |
| 2026-10-10 | 六 | 特殊工作日（补班） | 周末休息 | **周末** |

核算含义（现有规则，不用改引擎）：

- **周末 / 法定假日**：不应出勤、不漏刷。去了且有加班单 → 周末/节假日加班；有卡没单 → 不认加班。
- **工作日（10/6、10/7）**：正常出勤。不去按平时漏刷或请假，不是节假日加班。
- **特殊工作日** = 补班，按平时上班算。大连 9/20、10/10 **不能再停在这一档**。

## 2. 先核对：大连组是不是独立日历

9/14 曾把大连组从扬州日历克隆到 `DALIAN_STANDARD_2026`。若现场还绑着扬州那份，改这几天会把总部一起改掉。

页面核对：

1. 登录有 `ATTENDANCE_SETUP:MANAGE_CALENDAR` 的账号。
2. **考勤设置 → 考勤组**，公司选 **江苏神州半导体科技股份有限公司**。
3. 点开 **大连考勤组**（`DALIAN_ATTENDANCE`），看绑定的工作日历名称/编码。
4. 再打开 **考勤设置 → 工作日历**，同一公司、年度 **2026**，确认存在地点为 **大连** 的日历（编码类似 `DALIAN_STANDARD_2026`），且考勤组绑的就是它。

SQL 核对（只读）：

```sql
SELECT g.group_code,
       loc.location_name,
       cal.calendar_code,
       cal.location_id,
       r.revision_number,
       r.work_calendar_id
FROM attendance_group g
JOIN attendance_group_revision r
  ON r.attendance_group_id = g.attendance_group_id
JOIN location_revision lr ON lr.location_revision_id = r.location_revision_id
JOIN location loc ON loc.location_id = lr.location_id
JOIN work_calendar cal ON cal.work_calendar_id = r.work_calendar_id
WHERE g.group_code = 'DALIAN_ATTENDANCE'
ORDER BY r.revision_number DESC
LIMIT 3;
```

合格：`calendar_code` 是大连专用（不是扬州 `STANDARD_2026`），`location_name` 为大连。

不合格：立刻停手。先按 `docs/deployment/2026-09-14-fix-dalian-calendar-and-punches.sh` 的思路克隆大连日历并改绑，再回来改假期。不要在共用日历上改日类型。

当前这几天在已发布版本上是什么：

```sql
SELECT d.business_date, d.day_type
FROM work_calendar_day d
JOIN work_calendar_version v
  ON v.work_calendar_version_id = d.work_calendar_version_id
JOIN calendar_publication_timeline p
  ON p.work_calendar_version_id = v.work_calendar_version_id
 AND p.state = 'PUBLISHED'
JOIN attendance_group_revision r
  ON r.work_calendar_id = v.work_calendar_id
JOIN attendance_group g
  ON g.attendance_group_id = r.attendance_group_id
WHERE g.group_code = 'DALIAN_ATTENDANCE'
  AND d.business_date IN (
    '2026-09-20','2026-09-25','2026-09-26','2026-09-27',
    '2026-10-01','2026-10-02','2026-10-03','2026-10-04','2026-10-05',
    '2026-10-06','2026-10-07','2026-10-10'
  )
ORDER BY d.business_date;
```

预期改前：`2026-09-20`、`2026-10-10` 多为 `SPECIAL_WORKDAY`；`2026-10-06`、`2026-10-07` 多为 `PUBLIC_HOLIDAY`。

## 3. 页面怎么改（不要改已发布版本）

「保存日期覆盖」只对 **草稿** 开放。已发布版本不能原地改。正确路径：追加版本（会拷贝全年日期）→ 覆盖这几天 → 发布。

1. **考勤设置 → 工作日历**
2. 公司：**江苏神州**；日历年度：**2026**
3. 选中大连那份日历族，再选当前已发布版本，确认日期范围能看到 9–10 月：
   - 页面默认日期窗经常是当年 12/29–12/31
   - 把开始日期改成 `2026-09-20`、结束日期 `2026-10-10`，点 **加载日期**
4. 点 **追加工作日历版本**
   - 名称可写 `2026-大连中秋国庆不调休`
   - 生效日填 **2026-09-20**（这天起与全国不同；9/20 之前不用动）
   - 失效日保持当年日历结束（通常到 2027-01-01 前）
   - 原因：`大连中秋9/25-27、国庆10/1-5；9/20与10/10不补班；10/6-7正常上班`
   - 保存后应出现一条 **DRAFT** 版本，全年日期已从上一版拷来
5. 选中这条草稿，再点 **保存日期覆盖**
   - 用「添加日期覆盖」写入上表各天，类型按上表
   - 临时替换班次全部留空
   - 原因同上
   - 保存
6. 再加载 `2026-09-20`～`2026-10-10`，逐日核对类型
7. 对这条草稿点 **发布版本**，填写同样原因

不要点「发布日历族」（那是整族发布）。这里只发布这一条版本。

## 4. 发布后重算

日历不进已钉住的报表，必须重算江苏神州。

1. **报表中心**，公司选江苏神州，期间 `2026-09`
2. 有 `ATTENDANCE_REPORT:REFRESH` 的人点 **重新计算本月**
3. 等跑完再查大连人员

10 月：等 10 月窗口打开（或已有 OPEN 投影）后再 **重新计算本月** `2026-10`。现在先改日历即可，不必提前空算。

也可只重算大连组人员（现场脚本常用）：

```http
POST /api/v1/attendance-reports/recalculate
{"companyId":"<江苏神州>","period":"2026-09","employeeIds":["...大连组人员"]}
```

不要回拨 OA/得力水位，不要对扬州组重算。

## 5. 改完怎么验收

抽大连组至少 2 人（一个 9/20 可能加班、一个休息）：

| 日期 | 考勤明细 / 日报应看到 |
| --- | --- |
| 9/20 没去、没加班单 | 休息日，不应出勤，不漏刷 |
| 9/20 去了、有加班单 | 周末加班（小时跟单和覆盖打卡） |
| 9/20 去了、没加班单 | 不认加班 |
| 9/25–27、10/1–5 | 法定假日 |
| 10/6、10/7 | 普通工作日；不去则漏刷/请假 |
| 10/10 | 与 9/20 相同，按周末休息 |

扬州/总部抽 1 人：9/20、10/10 仍应是补班工作日，10/1–7 仍应是法定假。若总部也变了，说明改错了日历。

SQL 复核草稿已发布且日类型正确：把第 2 节查询再跑一遍，应对齐第 1 节。

## 6. 不要做的事

- 不要在扬州 `STANDARD_2026` 上改这几天
- 不要直接 `UPDATE work_calendar_day` 改已发布版本
- 不要停用大连地点或改班次模板
- 不要把 9/20、10/10 写成「特殊工作日」
- 不要指望有卡没加班单就出加班小时

手机打卡看地图是另一条功能，见 OpenSpec change `punch-gps-single-point-map`。本操作不涉及打卡坐标。
