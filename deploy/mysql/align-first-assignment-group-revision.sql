-- One-off: Jan-1 first assignments still pin the Aug-4 group revision,
-- but group_timeline ACTIVE rows are the current (Aug-5) revision.
-- Point the first assignment at the successor revision so July punches
-- can resolve. Do NOT run through Flyway.

SET NAMES utf8mb4;
START TRANSACTION;

SELECT 'before_mismatch' AS step, COUNT(*) AS cnt
FROM attendance_group_assignment first_asg
JOIN attendance_group_assignment successor
  ON successor.supersedes_assignment_id =
        first_asg.attendance_group_assignment_id
WHERE first_asg.effective_from = DATE('2026-01-01')
  AND first_asg.attendance_group_revision_id
        <> successor.attendance_group_revision_id;

UPDATE attendance_group_assignment first_asg
JOIN attendance_group_assignment successor
  ON successor.supersedes_assignment_id =
        first_asg.attendance_group_assignment_id
SET first_asg.attendance_group_revision_id =
        successor.attendance_group_revision_id
WHERE first_asg.effective_from = DATE('2026-01-01')
  AND first_asg.attendance_group_revision_id
        <> successor.attendance_group_revision_id;

SELECT 'after_mismatch' AS step, COUNT(*) AS cnt
FROM attendance_group_assignment first_asg
JOIN attendance_group_assignment successor
  ON successor.supersedes_assignment_id =
        first_asg.attendance_group_assignment_id
WHERE first_asg.effective_from = DATE('2026-01-01')
  AND first_asg.attendance_group_revision_id
        <> successor.attendance_group_revision_id;

SELECT 'yao_revision' AS step,
       first_asg.effective_from,
       first_asg.attendance_group_revision_id AS first_revision,
       successor.attendance_group_revision_id AS current_revision
FROM attendance_group_assignment first_asg
JOIN attendance_group_assignment successor
  ON successor.supersedes_assignment_id =
        first_asg.attendance_group_assignment_id
WHERE first_asg.employee_id = '3ae8fd69-5412-5ffb-a87e-870e183f4294'
ORDER BY first_asg.effective_from;

COMMIT;
