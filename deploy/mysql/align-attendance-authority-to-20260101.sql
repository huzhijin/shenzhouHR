-- One-off. Do NOT run through Flyway.
-- Align group / location / calendar / shift versions used by the
-- Jan-1 first assignments so July punches can resolve.
-- Organization identity has no business-date filter in the authority
-- query; this script only reports it.

SET NAMES utf8mb4;
SET @target_from = DATE('2026-01-01');
SET @eid = '3ae8fd69-5412-5ffb-a87e-870e183f4294';
SET @cid = '41000000-0000-0000-0000-000000000003';
SET @d = DATE('2026-07-30');
SET @asof = NOW(6);

START TRANSACTION;

SELECT 'org_identity' AS item, identity_status, COUNT(*) AS cnt
FROM organization_identity
GROUP BY identity_status;

SELECT 'group_revision_dates' AS item, effective_from, COUNT(*) AS cnt
FROM attendance_group_revision
GROUP BY effective_from
ORDER BY 1;

SELECT 'location_revision_dates' AS item, effective_from, COUNT(*) AS cnt
FROM location_revision
GROUP BY effective_from
ORDER BY 1;

SELECT 'shift_version_dates' AS item, effective_from, COUNT(*) AS cnt
FROM shift_version
GROUP BY effective_from
ORDER BY 1;

SELECT 'calendar_version_dates' AS item, effective_from, effective_to, COUNT(*) AS cnt
FROM work_calendar_version
GROUP BY effective_from, effective_to
ORDER BY 1;

SELECT 'group_timeline' AS item, state, business_effective_from, COUNT(*) AS cnt
FROM attendance_group_timeline
GROUP BY state, business_effective_from
ORDER BY 2, 1;

SELECT 'location_timeline' AS item, state, business_effective_from, COUNT(*) AS cnt
FROM location_timeline
GROUP BY state, business_effective_from
ORDER BY 2, 1;

SELECT 'calendar_pub' AS item, state, business_effective_from, COUNT(*) AS cnt
FROM calendar_publication_timeline
GROUP BY state, business_effective_from
ORDER BY 2, 1;

SELECT 'shift_pub' AS item, state, business_effective_from, COUNT(*) AS cnt
FROM shift_publication_timeline
GROUP BY state, business_effective_from
ORDER BY 2, 1;

-- 1) First assignment must use the current successor group revision.
UPDATE attendance_group_assignment first_asg
JOIN attendance_group_assignment successor
  ON successor.supersedes_assignment_id =
        first_asg.attendance_group_assignment_id
SET first_asg.attendance_group_revision_id =
        successor.attendance_group_revision_id
WHERE first_asg.effective_from = @target_from
  AND first_asg.attendance_group_revision_id
        <> successor.attendance_group_revision_id;

-- 2) Group timeline for those exact revisions starts Jan 1.
UPDATE attendance_group_timeline timeline
JOIN attendance_group_assignment first_asg
  ON first_asg.attendance_group_revision_id =
        timeline.attendance_group_revision_id
 AND first_asg.effective_from = @target_from
SET timeline.business_effective_from = @target_from
WHERE timeline.state = 'ACTIVE'
  AND timeline.business_effective_from > @target_from;

-- 3) Location timeline for the location revision those groups use.
UPDATE location_timeline timeline
JOIN attendance_group_revision revision
  ON revision.location_revision_id = timeline.location_revision_id
JOIN attendance_group_assignment first_asg
  ON first_asg.attendance_group_revision_id =
        revision.attendance_group_revision_id
 AND first_asg.effective_from = @target_from
SET timeline.business_effective_from = @target_from
WHERE timeline.state = 'ACTIVE'
  AND timeline.business_effective_from > @target_from;

-- 4) Calendar publication used by those groups.
UPDATE calendar_publication_timeline timeline
JOIN attendance_group_revision revision
  ON revision.work_calendar_id = timeline.work_calendar_id
JOIN attendance_group_assignment first_asg
  ON first_asg.attendance_group_revision_id =
        revision.attendance_group_revision_id
 AND first_asg.effective_from = @target_from
SET timeline.business_effective_from = @target_from
WHERE timeline.state = 'PUBLISHED'
  AND timeline.business_effective_from > @target_from;

-- 5) Shift publication used by those groups.
UPDATE shift_publication_timeline timeline
JOIN attendance_group_revision revision
  ON revision.shift_template_id = timeline.shift_template_id
JOIN attendance_group_assignment first_asg
  ON first_asg.attendance_group_revision_id =
        revision.attendance_group_revision_id
 AND first_asg.effective_from = @target_from
SET timeline.business_effective_from = @target_from
WHERE timeline.state = 'PUBLISHED'
  AND timeline.business_effective_from > @target_from;

-- 6) Version effective_from for the same calendar / shift / location / group.
UPDATE attendance_group_revision revision
JOIN attendance_group_assignment first_asg
  ON first_asg.attendance_group_revision_id =
        revision.attendance_group_revision_id
 AND first_asg.effective_from = @target_from
SET revision.effective_from = @target_from
WHERE revision.effective_from > @target_from;

UPDATE location_revision revision
JOIN attendance_group_revision group_revision
  ON group_revision.location_revision_id = revision.location_revision_id
JOIN attendance_group_assignment first_asg
  ON first_asg.attendance_group_revision_id =
        group_revision.attendance_group_revision_id
 AND first_asg.effective_from = @target_from
SET revision.effective_from = @target_from
WHERE revision.effective_from > @target_from;

UPDATE shift_version version
JOIN attendance_group_revision group_revision
  ON group_revision.shift_template_id = version.shift_template_id
JOIN attendance_group_assignment first_asg
  ON first_asg.attendance_group_revision_id =
        group_revision.attendance_group_revision_id
 AND first_asg.effective_from = @target_from
SET version.effective_from = @target_from
WHERE version.effective_from > @target_from;

UPDATE work_calendar_version version
JOIN attendance_group_revision group_revision
  ON group_revision.work_calendar_id = version.work_calendar_id
JOIN attendance_group_assignment first_asg
  ON first_asg.attendance_group_revision_id =
        group_revision.attendance_group_revision_id
 AND first_asg.effective_from = @target_from
SET version.effective_from = @target_from
WHERE version.effective_from > @target_from
  AND version.effective_to > @target_from;

SELECT 'revision_mismatch_left' AS check_name, COUNT(*) AS cnt
FROM attendance_group_assignment first_asg
JOIN attendance_group_assignment successor
  ON successor.supersedes_assignment_id =
        first_asg.attendance_group_assignment_id
WHERE first_asg.effective_from = @target_from
  AND first_asg.attendance_group_revision_id
        <> successor.attendance_group_revision_id;

SELECT 'yao_step06_group_timeline' AS check_name, COUNT(*) AS cnt
FROM attendance_group_assignment aga
JOIN attendance_group_revision gr
  ON gr.attendance_group_revision_id = aga.attendance_group_revision_id
JOIN attendance_group g
  ON g.attendance_group_id = gr.attendance_group_id
 AND g.company_id = @cid
JOIN attendance_group_timeline gt
  ON gt.attendance_group_id = g.attendance_group_id
 AND gt.attendance_group_revision_id = gr.attendance_group_revision_id
 AND gt.state = 'ACTIVE'
 AND gt.business_effective_from <= @d
 AND gt.recorded_at <= @asof
 AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_timeline newer
        WHERE newer.attendance_group_id = gt.attendance_group_id
          AND newer.business_effective_from <= @d
          AND newer.recorded_at <= @asof
          AND newer.event_sequence > gt.event_sequence
 )
WHERE aga.employee_id = @eid
  AND aga.effective_from <= @d
  AND aga.created_at <= @asof
  AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_assignment suc
        WHERE suc.supersedes_assignment_id = aga.attendance_group_assignment_id
          AND suc.effective_from <= @d
          AND suc.created_at <= @asof
  );

SELECT 'yao_full_authority' AS check_name, COUNT(*) AS cnt
FROM company company
JOIN employee employee
  ON employee.company_id = company.company_id
 AND employee.employee_id = @eid
JOIN employee_version employee_version
  ON employee_version.employee_id = employee.employee_id
 AND employee_version.status = 'ACTIVE'
 AND employee_version.effective_from <= @d
 AND (employee_version.effective_to IS NULL
      OR employee_version.effective_to > @d)
 AND employee_version.created_at <= @asof
JOIN employment_assignment employment
  ON employment.employee_id = employee.employee_id
 AND employment.version_valid_to IS NULL
 AND employment.record_status = 'ACTIVE'
 AND DATE(employment.effective_from) <= @d
 AND (employment.effective_to IS NULL
      OR DATE(employment.effective_to) > @d)
 AND employment.created_at <= @asof
JOIN organization_identity organization
  ON organization.organization_id = employment.organization_id
 AND organization.company_id = company.company_id
 AND organization.identity_status = 'ACTIVE'
JOIN attendance_group_assignment attendance_assignment
  ON attendance_assignment.employee_id = employee.employee_id
 AND attendance_assignment.effective_from <= @d
 AND attendance_assignment.created_at <= @asof
 AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_assignment successor
        WHERE successor.supersedes_assignment_id =
                attendance_assignment.attendance_group_assignment_id
          AND successor.effective_from <= @d
          AND successor.created_at <= @asof
 )
JOIN attendance_assignment_timeline assignment_timeline
  ON assignment_timeline.attendance_group_assignment_id =
        attendance_assignment.attendance_group_assignment_id
 AND assignment_timeline.employee_id = employee.employee_id
 AND assignment_timeline.state = 'ACTIVE'
 AND assignment_timeline.business_effective_from <= @d
 AND assignment_timeline.recorded_at <= @asof
JOIN attendance_group_revision group_revision
  ON group_revision.attendance_group_revision_id =
        attendance_assignment.attendance_group_revision_id
JOIN attendance_group attendance_group
  ON attendance_group.attendance_group_id =
        group_revision.attendance_group_id
 AND attendance_group.company_id = company.company_id
JOIN attendance_group_timeline group_timeline
  ON group_timeline.attendance_group_id = attendance_group.attendance_group_id
 AND group_timeline.attendance_group_revision_id =
        group_revision.attendance_group_revision_id
 AND group_timeline.state = 'ACTIVE'
 AND group_timeline.business_effective_from <= @d
 AND group_timeline.recorded_at <= @asof
JOIN location_revision location_revision
  ON location_revision.location_revision_id =
        group_revision.location_revision_id
JOIN location location
  ON location.location_id = location_revision.location_id
 AND location.company_id = company.company_id
JOIN location_timeline location_timeline
  ON location_timeline.location_id = location.location_id
 AND location_timeline.location_revision_id =
        location_revision.location_revision_id
 AND location_timeline.state = 'ACTIVE'
 AND location_timeline.business_effective_from <= @d
 AND location_timeline.recorded_at <= @asof
JOIN work_calendar work_calendar
  ON work_calendar.work_calendar_id = group_revision.work_calendar_id
 AND work_calendar.company_id = company.company_id
 AND work_calendar.location_id = location.location_id
JOIN calendar_publication_timeline calendar_publication
  ON calendar_publication.work_calendar_id = work_calendar.work_calendar_id
 AND calendar_publication.state = 'PUBLISHED'
 AND calendar_publication.business_effective_from <= @d
 AND calendar_publication.recorded_at <= @asof
JOIN work_calendar_version calendar_version
  ON calendar_version.work_calendar_version_id =
        calendar_publication.work_calendar_version_id
 AND calendar_version.work_calendar_id = work_calendar.work_calendar_id
 AND calendar_version.effective_from <= @d
 AND calendar_version.effective_to > @d
JOIN work_calendar_day calendar_day
  ON calendar_day.work_calendar_version_id =
        calendar_version.work_calendar_version_id
 AND calendar_day.business_date = @d
 AND calendar_day.created_at <= @asof
JOIN shift_version effective_shift
  ON effective_shift.shift_template_id = group_revision.shift_template_id
 AND effective_shift.effective_from <= @d
 AND effective_shift.created_at <= @asof
JOIN shift_template effective_shift_template
  ON effective_shift_template.shift_template_id =
        effective_shift.shift_template_id
 AND effective_shift_template.company_id = company.company_id
 AND effective_shift_template.location_id = location.location_id
JOIN shift_publication_timeline shift_publication
  ON shift_publication.shift_template_id =
        effective_shift.shift_template_id
 AND shift_publication.shift_version_id = effective_shift.shift_version_id
 AND shift_publication.state = 'PUBLISHED'
 AND shift_publication.business_effective_from <= @d
 AND shift_publication.recorded_at <= @asof
WHERE company.company_id = @cid
  AND company.status = 'ACTIVE'
  AND location_revision.time_zone = calendar_version.time_zone_snapshot
  AND location_revision.time_zone = effective_shift.time_zone_snapshot;

-- yao_step06_group_timeline and yao_full_authority must both be 1.
-- If not, run ROLLBACK; otherwise this script COMMITs.
COMMIT;
