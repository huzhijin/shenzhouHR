## ADDED Requirements

### Requirement: Dalian and Wuhan without punches count as full attendance

For business dates in **2026-08 only**, employees whose HR department path contains `大连` or `武汉` SHALL appear on 考勤报表月历, 个人月度工时, and 月度工时统计表 for every employed day even when they have no punches and no attendance-group daily facts. Scheduled workdays with no covering OA document SHALL be treated as full attendance (white normal attendance, not `漏刷`, not empty).

From **2026-09** onward those sites have punch data and SHALL follow ordinary punch rules (late, 漏刷, overtime capped to last punch). The August full-attendance synthesis MUST NOT apply.

Scheduled minutes per such workday SHALL equal that site's shift WORK minutes:

- 大连：上午 4.5 小时、下午 3.5 小时
- 武汉：扬州当季班次（夏令上午 3.5、下午 4.5；冬令上午 3.5、下午 4.5 对应扬州冬令上下午）

Effective OA overtime, leave, outing, and trip on those days SHALL still display and SHALL enter monthly-hour columns. Rest days remain rest days unless an overtime document covers them.

深圳办事处 and 合肥办事处 are out of this rule: if punches, makeup, or OA exist they follow ordinary calculation; the system MUST NOT invent full-attendance hours for them.

#### Scenario: Huo Yan weekdays are full attendance
- **WHEN** 霍岩 `SZST0445` is in 客户现场服务部-大连办事处
- **AND** 2026-08 is queried
- **AND** 2026-08-01 has an effective overtime document
- **AND** other August weekdays have no punches
- **THEN** the month matrix includes 霍岩
- **AND** 2026-08-01 uses overtime legend color
- **AND** other employed weekdays before as-of are white full attendance, not `漏刷`
- **AND** 个人月度工时 has a row whose 应出勤工时 includes those weekday template minutes

#### Scenario: Wuhan uses Yangzhou shift minutes
- **WHEN** 艾兵洁 `SZST0501` is in 武汉产品服务组 and still employed on a summer weekday
- **AND** that weekday has no punches and no leave
- **THEN** scheduled minutes for that day are Yangzhou summer WORK minutes (3.5 + 4.5)
- **AND** 个人实际出勤 includes that day as present before overtime and leave adjustments

#### Scenario: OA leave and overtime still overlay full attendance
- **WHEN** a Dalian employee has approved leave on a weekday and overtime on a Saturday
- **THEN** the weekday shows leave (not full-attendance white and not `漏刷`)
- **AND** the Saturday shows overtime hours
- **AND** monthly 事假+病假+其他 / 年假 / 加班时数 include those documents
- **AND** a Wuhan August leave or overtime day uses the same document colors

#### Scenario: September Dalian uses punches not full attendance
- **WHEN** 霍岩 has punches on a 2026-09 weekday
- **THEN** late, missing-punch, and overtime follow ordinary punch-required rules
- **AND** the day is not synthesized as August-style full attendance

#### Scenario: Shenzhen with punches is ordinary
- **WHEN** 黄兆隆 `SZST0670` has Deli punches in August
- **THEN** late, missing-punch, and hours follow ordinary Yangzhou-site rules
- **AND** empty days are not filled as Dalian/Wuhan full attendance
