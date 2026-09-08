## ADDED Requirements

### Requirement: August leavers close by employee number and still appear until that day

月度工时 and 考勤明细 SHALL include a leaver if they have at least one employed day in the window, even when the current Excel 花名册 snapshot already dropped them. Hours and 漏刷 stop after 离职当天. `employment.effective_to` SHALL be the next calendar day 00:00 (half-open).

Close Jiangsu Shenzhou 2026-08 assignments by employee number:

| 离职日期 | 工号 | 姓名 |
|---|---|---|
| 2026-08-07 | SZST0641、SZST0662、SZST0598 | 陈柏宇、李恩琪、范康搏 |
| 2026-08-11 | SZST0674 | 徐利民 |
| 2026-08-12 | SZST0638 | 刘梓轩 |
| 2026-08-19 | SZST0652 | 江梦圆 |
| 2026-08-21 | SZST0721、SZSTSX91、SZSTSX95 | 崔雨、刘至宽、姚韩 |
| 2026-08-25 | SZST0501 | 艾兵洁 |
| 2026-08-28 | SZST0645、SZST0722 | 张泽、张自豪 |
| 2026-08-31 | SZST0138、SZST0216、SZST0531 | 杜超、王文睿、杨旭涛 |

崔雨 and 张自豪 MUST match `SZST0721` and `SZST0722`, not name-only. `SZST0216` display name is 王文睿.

People on a side department sheet but not this table — 谭钊、黄兆隆、唐家轩、张衡、赵子奇 — MUST NOT be closed as leavers.

#### Scenario: Dropped roster leaver still has an August row
- **WHEN** 陈柏宇 `SZST0641` left on 2026-08-07 and is absent from the 2026-08-22 Excel roster
- **AND** August work hours are queried
- **THEN** 陈柏宇 still has a row
- **AND** 应出勤 covers 2026-08-01 through 2026-08-07 inclusive
- **AND** 2026-08-08 onward adds 0 scheduled hours

#### Scenario: Cui Yu Dalian leaver is full attendance only while employed
- **WHEN** 崔雨 `SZST0721` left on 2026-08-21
- **THEN** Dalian full-attendance weekdays apply through 2026-08-21
- **AND** dates after 2026-08-21 are not on the matrix as 漏刷 or full attendance

#### Scenario: Extra red names stay employed
- **WHEN** 黄兆隆 `SZST0670` is marked red on a side sheet but is not in the leaver table
- **THEN** employment is not closed in August
- **AND** Shenzhen ordinary punch rules still apply

#### Scenario: Screenshot red names still appear in August
- **WHEN** 2026-08 考勤报表 / 个人月度工时 is opened
- **THEN** 姚韩 `SZSTSX95` appears through 2026-08-21
- **AND** 徐利民 `SZST0674` appears through 2026-08-11
- **AND** 刘至宽 `SZSTSX91` appears through 2026-08-21
- **AND** 张泽 `SZST0645` appears through 2026-08-28
- **AND** 崔雨 `SZST0721` appears through 2026-08-21
- **AND** 黄兆隆 and 唐家轩 appear for the whole employed August
- **AND** 谭钊 is not created when the HR 花名册 has no employee number for that name

### Requirement: Last employment day without an off-duty punch is not 漏签
On the last employed calendar day (`employment.effective_to` = next day 00:00), when there is no afternoon off-duty punch, the system SHALL assign an off-duty clock one minute after the afternoon WORK segment end (Yangzhou summer `18:01`, winter `17:31`, Dalian `16:31`). The day SHALL NOT raise 漏签 / `MISSING_PUNCH`. A real off-duty punch still displays its actual time. Rest days are not assigned this clock.

#### Scenario: Xu Limin last day without off-duty punch
- **WHEN** 徐利民 `SZST0674` left on 2026-08-11
- **AND** that weekday has an on-duty punch and no off-duty punch
- **THEN** the afternoon cell shows 18:01 (or that site's shift-end plus one minute)
- **AND** the day is not 漏签
- **AND** a real off-duty punch would keep its own clock
