-- One-off: backdate first attendance-group coverage to 2026-01-01
-- so July Deli punches can resolve. Do NOT run through Flyway.
--
-- Keeps the 2026-08-05 successor assignments. Only the earliest
-- assignment / first ACTIVE or PUBLISHED timeline per subject moves.
-- created_at / recorded_at stay unchanged.

SET NAMES utf8mb4;
SET @target_from = DATE('2026-01-01');
SET @setup_from = DATE('2026-08-04');
SET @setup_to = DATE('2026-08-05');

START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS tmp_first_group_assignment;
CREATE TEMPORARY TABLE tmp_first_group_assignment (
    attendance_group_assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    employee_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
);

INSERT INTO tmp_first_group_assignment (
    attendance_group_assignment_id,
    employee_id
)
SELECT a.attendance_group_assignment_id, a.employee_id
FROM attendance_group_assignment a
JOIN (
    SELECT employee_id, MIN(effective_from) AS first_from
    FROM attendance_group_assignment
    GROUP BY employee_id
) first_row
  ON first_row.employee_id = a.employee_id
 AND first_row.first_from = a.effective_from
WHERE a.effective_from BETWEEN @setup_from AND @setup_to;

SELECT 'preview_first_assignments' AS step, COUNT(*) AS cnt
FROM tmp_first_group_assignment;

UPDATE attendance_group_assignment a
JOIN tmp_first_group_assignment first_row
  ON first_row.attendance_group_assignment_id =
        a.attendance_group_assignment_id
SET a.effective_from = @target_from
WHERE a.effective_from BETWEEN @setup_from AND @setup_to;

UPDATE attendance_assignment_timeline timeline
JOIN tmp_first_group_assignment first_row
  ON first_row.attendance_group_assignment_id =
        timeline.attendance_group_assignment_id
SET timeline.business_effective_from = @target_from
WHERE timeline.state = 'ACTIVE'
  AND timeline.business_effective_from BETWEEN @setup_from AND @setup_to;

UPDATE attendance_group_timeline timeline
JOIN (
    SELECT attendance_group_id, MIN(business_effective_from) AS first_from
    FROM attendance_group_timeline
    WHERE state = 'ACTIVE'
    GROUP BY attendance_group_id
) first_row
  ON first_row.attendance_group_id = timeline.attendance_group_id
 AND first_row.first_from = timeline.business_effective_from
SET timeline.business_effective_from = @target_from
WHERE timeline.state = 'ACTIVE'
  AND timeline.business_effective_from BETWEEN @setup_from AND @setup_to;

UPDATE location_timeline timeline
JOIN (
    SELECT location_id, MIN(business_effective_from) AS first_from
    FROM location_timeline
    WHERE state = 'ACTIVE'
    GROUP BY location_id
) first_row
  ON first_row.location_id = timeline.location_id
 AND first_row.first_from = timeline.business_effective_from
SET timeline.business_effective_from = @target_from
WHERE timeline.state = 'ACTIVE'
  AND timeline.business_effective_from BETWEEN @setup_from AND @setup_to;

UPDATE calendar_publication_timeline timeline
JOIN (
    SELECT work_calendar_id, MIN(business_effective_from) AS first_from
    FROM calendar_publication_timeline
    WHERE state = 'PUBLISHED'
    GROUP BY work_calendar_id
) first_row
  ON first_row.work_calendar_id = timeline.work_calendar_id
 AND first_row.first_from = timeline.business_effective_from
SET timeline.business_effective_from = @target_from
WHERE timeline.state = 'PUBLISHED'
  AND timeline.business_effective_from BETWEEN @setup_from AND @setup_to;

UPDATE shift_publication_timeline timeline
JOIN (
    SELECT shift_template_id, MIN(business_effective_from) AS first_from
    FROM shift_publication_timeline
    WHERE state = 'PUBLISHED'
    GROUP BY shift_template_id
) first_row
  ON first_row.shift_template_id = timeline.shift_template_id
 AND first_row.first_from = timeline.business_effective_from
SET timeline.business_effective_from = @target_from
WHERE timeline.state = 'PUBLISHED'
  AND timeline.business_effective_from BETWEEN @setup_from AND @setup_to;

UPDATE attendance_group_revision revision
JOIN (
    SELECT attendance_group_id, MIN(effective_from) AS first_from
    FROM attendance_group_revision
    GROUP BY attendance_group_id
) first_row
  ON first_row.attendance_group_id = revision.attendance_group_id
 AND first_row.first_from = revision.effective_from
SET revision.effective_from = @target_from
WHERE revision.effective_from BETWEEN @setup_from AND @setup_to;

UPDATE location_revision revision
JOIN (
    SELECT location_id, MIN(effective_from) AS first_from
    FROM location_revision
    GROUP BY location_id
) first_row
  ON first_row.location_id = revision.location_id
 AND first_row.first_from = revision.effective_from
SET revision.effective_from = @target_from
WHERE revision.effective_from BETWEEN @setup_from AND @setup_to;

UPDATE shift_version version
JOIN (
    SELECT shift_template_id, MIN(effective_from) AS first_from
    FROM shift_version
    GROUP BY shift_template_id
) first_row
  ON first_row.shift_template_id = version.shift_template_id
 AND first_row.first_from = version.effective_from
SET version.effective_from = @target_from
WHERE version.effective_from BETWEEN @setup_from AND @setup_to;

UPDATE work_calendar_version version
JOIN (
    SELECT work_calendar_id, MIN(effective_from) AS first_from
    FROM work_calendar_version
    GROUP BY work_calendar_id
) first_row
  ON first_row.work_calendar_id = version.work_calendar_id
 AND first_row.first_from = version.effective_from
SET version.effective_from = @target_from
WHERE version.effective_from BETWEEN @setup_from AND @setup_to;

SELECT 'assignment_effective_from' AS item, effective_from, COUNT(*) AS cnt
FROM attendance_group_assignment
GROUP BY effective_from
ORDER BY effective_from;

SELECT 'assignment_timeline' AS item, state, business_effective_from, COUNT(*) AS cnt
FROM attendance_assignment_timeline
GROUP BY state, business_effective_from
ORDER BY business_effective_from, state;

SELECT 'group_timeline' AS item, state, business_effective_from, COUNT(*) AS cnt
FROM attendance_group_timeline
GROUP BY state, business_effective_from
ORDER BY business_effective_from, state;

SELECT employee_id, COUNT(*) AS first_rows
FROM tmp_first_group_assignment
GROUP BY employee_id
HAVING COUNT(*) > 1;

-- Expected: 615 first assignments at 2026-01-01, 615 successors still 2026-08-05.
-- If that is wrong, run: ROLLBACK;
COMMIT;
