-- Close listed 2026-08 Jiangsu Shenzhou employments.
-- Half-open: 离职当天计入, effective_to = 离职日次日 00:00.
-- Prefer scripts/close-2026-08-jiangsu-leavers.py (preview, APPLY=1).
-- Preview first. 崔雨 / 张自豪 only close when the active name is unique.
-- Company: 江苏神州 41000000-0000-0000-0000-000000000003

SET NAMES utf8mb4;
SET @company_id = '41000000-0000-0000-0000-000000000003';

SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       assignment.effective_from AS 任职起,
       assignment.effective_to AS 任职止
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
LEFT JOIN employment_assignment assignment
  ON assignment.employee_id = employee.employee_id
 AND assignment.effective_to IS NULL
WHERE employee.company_id = @company_id
  AND employee.employee_number IN (
        'SZST0641', 'SZST0662', 'SZST0598', 'SZST0674', 'SZST0638',
        'SZST0652', 'SZST0721', 'SZSTSX91', 'SZSTSX95', 'SZST0501',
        'SZST0645', 'SZST0722', 'SZST0138', 'SZST0216', 'SZST0531');

SELECT version.display_name AS 姓名,
       COUNT(DISTINCT employee.employee_id) AS 人数,
       GROUP_CONCAT(DISTINCT employee.employee_number ORDER BY employee.employee_number) AS 工号
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.company_id = @company_id
  AND version.display_name IN ('崔雨', '张自豪')
GROUP BY version.display_name;

UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = '2026-08-08 00:00:00.000000'
WHERE employee.company_id = @company_id
  AND employee.employee_number IN ('SZST0641', 'SZST0662', 'SZST0598')
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '2026-08-08 00:00:00.000000';

UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = '2026-08-12 00:00:00.000000'
WHERE employee.company_id = @company_id
  AND employee.employee_number = 'SZST0674'
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '2026-08-12 00:00:00.000000';

UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = '2026-08-13 00:00:00.000000'
WHERE employee.company_id = @company_id
  AND employee.employee_number = 'SZST0638'
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '2026-08-13 00:00:00.000000';

UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = '2026-08-20 00:00:00.000000'
WHERE employee.company_id = @company_id
  AND employee.employee_number = 'SZST0652'
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '2026-08-20 00:00:00.000000';

UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = '2026-08-22 00:00:00.000000'
WHERE employee.company_id = @company_id
  AND employee.employee_number IN ('SZSTSX91', 'SZSTSX95', 'SZST0721')
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '2026-08-22 00:00:00.000000';

UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = '2026-08-26 00:00:00.000000'
WHERE employee.company_id = @company_id
  AND employee.employee_number = 'SZST0501'
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '2026-08-26 00:00:00.000000';

UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = '2026-08-29 00:00:00.000000'
WHERE employee.company_id = @company_id
  AND employee.employee_number IN ('SZST0645', 'SZST0722')
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '2026-08-29 00:00:00.000000';

UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = '2026-09-01 00:00:00.000000'
WHERE employee.company_id = @company_id
  AND employee.employee_number IN ('SZST0138', 'SZST0216', 'SZST0531')
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '2026-09-01 00:00:00.000000';

SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       assignment.effective_to AS 任职止
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
LEFT JOIN employment_assignment assignment
  ON assignment.employee_id = employee.employee_id
 AND assignment.version_valid_to IS NULL
WHERE employee.company_id = @company_id
  AND employee.employee_number IN ('SZST0721', 'SZST0722');
