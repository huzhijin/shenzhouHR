## ADDED Requirements

### Requirement: Employees SHALL see their own attendance details and exceptions

A principal with `ATTENDANCE_SELF:READ` and a bound employee SHALL load 我的考勤 as that employee's daily attendance details (dates, on/off punches, late/miss labels). 我的考勤 MUST NOT depend on a successful organization dashboard parse. Failures MUST be an empty or error state that names attendance, not a generic 服务暂时不可用 with no records when facts exist.

#### Scenario: Bound employee with published facts sees August days
- **WHEN** 黄金鑫 (SZST0673) opens 我的考勤 for 2026-08
- **THEN** the page lists the same daily first/last punches as the month matrix for SZST0673
- **AND** the page is not blank solely because `/me/attendance-dashboard` JSON failed a strict parse

#### Scenario: Self scope with company id still authorizes
- **WHEN** the EMPLOYEE_SELF assignment uses scope type SELF and a non-null company id
- **THEN** the self attendance query still resolves the bound employee
- **AND** MUST NOT require `auth_data_scope.company_id IS NULL`

### Requirement: 我的假期 SHALL read annual leave and time-off accounts

我的假期 SHALL call the employee's annual-leave and time-off account APIs (or an equivalent `/me` leave account API) under `LEAVE_SELF:READ`. It MUST NOT synthesize a fake account from dashboard leave minutes. A zero balance with no ledger SHALL render as 0 remaining, not as a failed request.

#### Scenario: Zero balance is a valid empty state
- **WHEN** the bound employee has year 2026 annual leave balance 0 and no ledger entries
- **THEN** 我的假期 shows 年假 remaining 0
- **AND** does not show 请求未成功完成

### Requirement: EMPLOYEE_SELF SHALL NOT open company-wide reports

The EMPLOYEE_SELF role MUST NOT grant `ATTENDANCE_REPORT:READ` or `ATTENDANCE_REPORT_QUERY:READ` for company-wide sheets. Ordinary employees reach details through 我的考勤 and exceptions through the self workbench. HR_ADMIN and DEPARTMENT_HEAD keep org-scoped query reports.

#### Scenario: Plain employee menu
- **WHEN** an account has only EMPLOYEE_SELF
- **THEN** the menu includes 我的考勤 and 我的假期
- **AND** does not include 考勤报表 or 异常总览

#### Scenario: Self exceptions stay on the self workbench
- **WHEN** that employee has a yesterday missed off-duty punch
- **THEN** the self workbench lists that exception for themselves only
