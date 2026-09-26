## ADDED Requirements

### Requirement: Work hours for leavers cover only employed days

月度工时统计表 SHALL sum scheduled, leave, overtime, and actual hours only on business dates where the employee's employment assignment is effective. Employment is a half-open interval: the 离职日期 itself still counts; the next calendar day does not. Days after `employment.effective_to` MUST NOT contribute scheduled hours or 漏刷. Leavers still appear on the month sheet if they had at least one employed day in the query range.

The 2026-08 Jiangsu roster SHALL close current assignments so `effective_to` is the calendar day after each 离职日期 below, then that company-month SHALL be recalculated:

| 离职日期 | 姓名 |
|---|---|
| 2026-08-07 | 陈柏宇、李恩琪、范康搏 |
| 2026-08-11 | 徐利民 |
| 2026-08-12 | 刘梓轩 |
| 2026-08-19 | 江梦圆 |
| 2026-08-21 | 崔雨、刘至宽、姚韩 |
| 2026-08-25 | 艾兵洁 |
| 2026-08-28 | 张泽、张自豪 |
| 2026-08-31 | 杜超、王文睿、杨旭涛 |

#### Scenario: Mid-month leaver is not a full August
- **WHEN** 陈柏宇's 离职日期 is 2026-08-07 and August is recalculated
- **THEN** 月度工时统计表 应出勤小时 equal scheduled work from 2026-08-01 through 2026-08-07 inclusive
- **AND** 2026-08-08 through 2026-08-31 add 0 scheduled hours

#### Scenario: Last-day-of-month leaver keeps that day
- **WHEN** 杜超's 离职日期 is 2026-08-31
- **THEN** 2026-08-31 is included in 月度工时统计表
- **AND** September dates are not

#### Scenario: Matrix has no 漏刷 after leave
- **WHEN** 刘梓轩 left on 2026-08-12
- **THEN** 考勤明细 cells after 2026-08-12 are not 漏刷 for lack of punches
