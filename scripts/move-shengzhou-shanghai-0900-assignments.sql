-- 晟州聚能：把默认组里除扬州特例外的人改到已建好的上海 9:00 考勤组。
-- 扬州特例不动：赵俊君 SZJN0012、时晨 SZJN0021、王颂雅 SZJN0030。
-- phpMyAdmin 选中 shenzhou_hr 后整段执行。可重复执行。

SET NAMES utf8mb4;

START TRANSACTION;

UPDATE attendance_group_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
JOIN employee_version employee_version
  ON employee_version.employee_id = employee.employee_id
 AND employee_version.status = 'ACTIVE'
 AND employee_version.effective_from <= '2026-08-15'
 AND (
        employee_version.effective_to IS NULL
        OR employee_version.effective_to > '2026-08-15'
 )
JOIN attendance_group_revision old_revision
  ON old_revision.attendance_group_revision_id
     = assignment.attendance_group_revision_id
JOIN attendance_group old_group
  ON old_group.attendance_group_id = old_revision.attendance_group_id
SET
  assignment.attendance_group_revision_id = '109ae5bd-356d-428b-85af-e58a475c0d4b',
  assignment.snapshot_digest = LOWER(SHA2(CONCAT(
      '2917e267-dec4-4f89-a2a9-dcd9a3c1c814', '|', assignment.employee_id, '|',
      DATE_FORMAT(assignment.effective_from, '%Y-%m-%d'), '|NULL'
  ), 256)),
  assignment.change_reason = '上海地区按9点上班编组'
WHERE employee.company_id = '41000000-0000-0000-0000-000000000002'
  AND old_group.company_id = '41000000-0000-0000-0000-000000000002'
  AND old_group.group_code = 'DEFAULT_ATTENDANCE'
  AND employee_version.employee_number NOT IN ('SZJN0012','SZJN0021','SZJN0030')
  AND employee_version.display_name NOT IN ('时晨','王颂雅','赵俊君')
  AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_assignment successor
        WHERE successor.supersedes_assignment_id
              = assignment.attendance_group_assignment_id
  );

SELECT ROW_COUNT() AS moved_rows;

SELECT
  employee_version.employee_number,
  employee_version.display_name,
  attendance_group.group_code,
  group_revision.group_name
FROM employee employee
JOIN employee_version employee_version
  ON employee_version.employee_id = employee.employee_id
 AND employee_version.status = 'ACTIVE'
 AND employee_version.effective_from <= '2026-08-15'
 AND (
        employee_version.effective_to IS NULL
        OR employee_version.effective_to > '2026-08-15'
 )
JOIN attendance_group_assignment assignment
  ON assignment.employee_id = employee.employee_id
 AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_assignment successor
        WHERE successor.supersedes_assignment_id
              = assignment.attendance_group_assignment_id
 )
JOIN attendance_group_revision group_revision
  ON group_revision.attendance_group_revision_id
     = assignment.attendance_group_revision_id
JOIN attendance_group attendance_group
  ON attendance_group.attendance_group_id = group_revision.attendance_group_id
WHERE employee.company_id = '41000000-0000-0000-0000-000000000002'
ORDER BY attendance_group.group_code, employee_version.employee_number;

COMMIT;
