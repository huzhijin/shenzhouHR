-- 预览：上海昇州 / 晟州 谁留扬州或其他时段，谁改上海 9:00。
-- phpMyAdmin 选中 shenzhou_hr 后整段执行。只读，不改数据。

SET NAMES utf8mb4;

SELECT
  company.code AS company_code,
  company.name AS company_name,
  employee_version.employee_number,
  employee_version.display_name,
  attendance_group.group_code,
  group_revision.group_name,
  location.location_code,
  location_revision.location_name,
  shift_template.template_code,
  JSON_UNQUOTE(JSON_EXTRACT(shift_version.segments_json, '$.segments[0].startLocalTime')) AS work_start,
  CASE
    WHEN employee_version.employee_number IN ('SZJN0012', 'SZJN0021', 'SZJN0030')
      OR employee_version.display_name IN ('赵俊君', '时晨', '王颂雅')
      THEN 'KEEP_YANGZHOU_0830'
    WHEN attendance_group.group_code = 'SHANGHAI_0900'
      THEN 'ALREADY_SHANGHAI_0900'
    WHEN attendance_group.group_code <> 'DEFAULT_ATTENDANCE'
      THEN 'KEEP_OTHER_SHIFT'
    ELSE 'MOVE_SHANGHAI_0900'
  END AS planned_action
FROM employee employee
JOIN company company
  ON company.company_id = employee.company_id
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
 AND assignment.effective_from <= '2026-08-15'
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
JOIN location_revision location_revision
  ON location_revision.location_revision_id = group_revision.location_revision_id
JOIN location location
  ON location.location_id = location_revision.location_id
JOIN shift_template shift_template
  ON shift_template.shift_template_id = group_revision.shift_template_id
LEFT JOIN shift_version shift_version
  ON shift_version.shift_template_id = shift_template.shift_template_id
 AND shift_version.effective_from = (
        SELECT MAX(candidate.effective_from)
        FROM shift_version candidate
        WHERE candidate.shift_template_id = shift_template.shift_template_id
          AND candidate.effective_from <= '2026-08-15'
 )
WHERE employee.company_id IN (
        '41000000-0000-0000-0000-000000000001',
        '41000000-0000-0000-0000-000000000002'
      )
ORDER BY company.code, planned_action, employee_version.employee_number;
