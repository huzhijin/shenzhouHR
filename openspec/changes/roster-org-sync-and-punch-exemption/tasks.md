## 1. Roster parse and live reconciliation

- [x] 1.1 Parse `神州花名册.xlsx` and `不打卡人员名单。.xlsx` with the frozen correction table (吕加军、杨玲、叶剑 `SZSZ0000`、赵子奇/张衡、丢弃晟州马雨/尹华凌/丁昊行；名单 `SZST0567`→`SZSZ0000`)
- [x] 1.2 Map live organization paths on `http://58.220.159.170:23273/` onto roster short-name paths one-to-one; fail the cutover on ambiguous matches
- [x] 1.3 Emit a read-only report: rename-in-place / new org / retire org / assignment move / number correction / terminate / create employee / account display or username sync / standing-exempt
- [x] 1.4 Include the six already-changed-password accounts (`SZST0007` `SZST0036` `SZST0381` `SZST0392` `SZST0615` `SZST0673`) as login-must-not-change

## 2. Organization versions in place

- [x] 2.1 For mapped nodes, insert a `2026-01-01` organization version on the existing `organization_id` with roster short name and parent; close the previous current version
- [ ] 2.2 Create identities only for roster paths that do not map (昇州维修部, extra 组别)
- [ ] 2.3 Retire unmapped empty nodes from `2026-01-01` without deleting identity; leave Jiangsu Xinyue unchanged
- [ ] 2.4 Rebuild closure/projection for the three companies; prove a second run is idempotent

## 3. Employees and assignments

- [x] 3.1 Keep every live `employee_id`; insert a new assignment only when the roster leaf identity differs
- [x] 3.2 Correct `SZT0687`→`SZST0687` and `SZT0709`→`SZST0709` on the live employee; do not insert a second person
- [x] 3.3 Move `SZSZ0000` 叶剑 to Jiangsu Shenzhou 总经办; move 马雨/尹华凌/丁昊 to the Shenzhou leaves; create `SZSZ0002`/`SZSZ0003` without accounts
- [x] 3.4 End employment for the nine absences on `2026-01-01` without deleting identity or punches
- [x] 3.5 Leave `attendance_group_assignment` on `employee_id`

## 4. Account-employee sync

- [x] 4.1 Keep `account_id`, `principal_id`, `employee_id`, password hash, and `session_epoch` for all 616 accounts
- [x] 4.2 Set `local_account.display_name` from the current employee name
- [x] 4.3 For number corrections with `first_password_change_required` and no `last_login_at`, set username to the new employee number; never change username for the six users who already completed first password change, or for `szsc_admin_faa41d5bd802`
- [x] 4.4 Do not disable the nine terminated people's accounts and do not bulk-provision accounts for new employees
- [ ] 4.5 Prove account screens show the post-sync employee department and status for the same username

## 5. Standing punch exemption

- [x] 5.1 Add `punch_exemption_assignment` and seed open-ended rows from `2026-01-01` for the standing list
- [x] 5.2 Calculator `punchExempt` is standing ∪ `EXECUTIVE`; publishing the list MUST NOT insert `EXECUTIVE` assignments
- [x] 5.3 Keep approved OA `EXEMPT_PUNCH` documents unchanged as a separate source
- [x] 5.4 Prove punch event rows and 2025 results are not rewritten

## 6. Report department tree

- [x] 6.1 Derive 一级/二级/三级 from closure, skipping COMPANY
- [x] 6.2 Change the report department filter to the current-organization tree; selection includes descendants
- [x] 6.3 Keep organization page and employee directory as trees with the new short names

## 7. Exempt day display

- [x] 7.1 Scheduled work + standing-or-executive + no punch + no leave displays as 正常, never `漏刷` / `旷工` / `无打卡`
- [x] 7.2 Rest days for exempt people still display 休息日
- [x] 7.3 Overtime still requires an approved overtime document

## 8. Verification

- [x] 8.1 Fixture tests: in-place org rename, assignment-only-when-moved, Lü/Yang merge+account username, Ye Jian account `SZSZ0000`, password hashes untouched, Xinyue isolation
- [ ] 8.2 After cutover, log in as `SZST0007` with the existing password and confirm organization/employee/report department short names
- [ ] 8.3 LIVE `2026-01` / `2026-08` samples match roster columns and exempt-normal cells; stored punch rows unchanged
- [ ] 8.4 Cutover report lists 2026 punch counts for the nine terminated people
