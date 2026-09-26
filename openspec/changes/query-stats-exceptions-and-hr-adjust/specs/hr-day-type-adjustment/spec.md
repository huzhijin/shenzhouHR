## ADDED Requirements

### Requirement: Matrix day editor can set the day's type

On 考勤明细, a user with `ATTENDANCE_ADJUST:MANAGE` SHALL be able to set one or more day types for the selected person-day in the same dialog that already edits on-duty and off-duty times. Allowed types: 加班, 出差, 外出, 调休, 年假, 事假, 病假, 婚假, 丧假, 产假, 陪产假, 哺乳假, 工伤假, 旷工, 迟到, 早退, 漏打卡. Checking 外出 or 出差 SHALL inject approved outing/trip coverage for that calendar day so 漏刷 and 旷工 are not raised. Checking a leave name SHALL change the cell appearance and suppress punch-gap exceptions for covered slots; it MUST NOT debit leave quota. Checking 加班 without hours SHALL not invent overtime minutes; overtime hours remain the existing hours field. A reason remains required. Saving SHALL keep an audit row.

#### Scenario: Paper outing covers a weekday without OA
- **WHEN** HR sets type 外出 on 2026-08-12 for an employee with no OA outing and no punches
- **THEN** after save the matrix cell shows 外出
- **AND** 异常总览 has no 漏刷 or 旷工 for that person-day

#### Scenario: Leave type does not consume quota
- **WHEN** HR sets type 年假 on a day
- **THEN** the cell shows 年假
- **AND** the employee's annual-leave remaining hours are unchanged

### Requirement: Outing plus overtime displays overtime

When a person-day has both outing (OA or HR type) and recognized overtime or an overtime document that belongs on that date, 考勤明细 SHALL show 加班 as the cell type, not 外出. Hover MAY still mention 外出. The same rule applies when both types are checked in the editor.

#### Scenario: OA outing and overtime on the same date
- **WHEN** 2026-08-08 has an approved outing and an approved overtime document
- **THEN** the matrix cell type is 加班

#### Scenario: HR checks both outing and overtime
- **WHEN** HR checks 外出 and fills overtime hours greater than 0
- **THEN** the saved cell type is 加班

### Requirement: Save-and-recalculate is that person and nearby days only

Saving a matrix HR adjustment SHALL recalculate only that employee, for the selected business date and the previous and next calendar days (overnight adjacency). It MUST NOT recalculate the whole company-month. The adjustment row MUST be visible to that recalculation (`created_at` is not after `dataAsOf`). Cleared exception types in the dialog (迟到, 早退, 缺卡, 旷工, 未报加班, 加班结束晚于打卡, 长时在岗待审) SHALL all be accepted by the API. After a successful save the detail drawer stays open on the same employee, the edited day is visible, and the success message is 已更新该日考勤 (not a future-tense “将重算”).

#### Scenario: One person, three days
- **WHEN** HR saves an off-duty time for 张三 on 2026-08-12
- **THEN** the engine recalculates 张三 for 2026-08-11, 2026-08-12, and 2026-08-13
- **AND** other employees' published facts for August are copied, not recomputed

#### Scenario: Drawer stays on the edited day
- **WHEN** the save request succeeds
- **THEN** the month calendar drawer remains open for that employee
- **AND** a success toast 已更新该日考勤 is shown
- **AND** the 8月12日 cell reflects the new clocks or types without a manual re-query

#### Scenario: Just-saved adjustment is applied
- **WHEN** HR clears 迟到 and saves
- **THEN** the recalculated daily fact has 0 late minutes
- **AND** 异常总览 no longer lists that late row

#### Scenario: Undeclared-overtime clear is accepted
- **WHEN** HR checks 取消异常 未报加班 and saves with a reason
- **THEN** the API does not reject the type
- **AND** that exception is absent after recalc

### Requirement: Hire-day morning missed punch displays 08:29

On the employee's employment start date (`employment.effective_from`), when there is no on-duty punch, 考勤明细 SHALL display `08:29` for the morning slot instead of 漏刷. The system SHALL NOT raise 上班缺卡 for that morning. A real on-duty punch still displays its actual time. This rule applies to every hire date, not a one-off list.

#### Scenario: First day with no morning punch shows 08:29
- **WHEN** 张三's employment starts on 2026-08-03 and that morning has no punch
- **THEN** the morning cell shows 08:29
- **AND** the cell does not show 漏刷
- **AND** 异常总览 has no 上班缺卡 row for that person-day

#### Scenario: First day with a real punch still shows the time
- **WHEN** the same hire day has an on-duty punch at 08:41
- **THEN** the morning cell shows 08:41

