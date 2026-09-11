-- Diagnose company/location mismatch for SZST0512, then align
-- group / shift / calendar to the employee's company and the
-- group's location. Do NOT run through Flyway.

SET NAMES utf8mb4;
SET @eid = 'c47348ca-336f-51a2-b97d-ff40fd74bdce';
SET @cid = '41000000-0000-0000-0000-000000000003';
SET @d = DATE('2026-07-30');
SET @asof = NOW(6);

SELECT 'employee_company' AS item, e.company_id, ev.employee_number, ev.display_name
FROM employee e
JOIN employee_version ev
  ON ev.employee_id = e.employee_id AND ev.status = 'ACTIVE'
WHERE e.employee_id = @eid
LIMIT 1;

SELECT 'group' AS item,
       g.attendance_group_id,
       g.company_id AS group_company,
       gr.attendance_group_revision_id,
       gr.shift_template_id,
       gr.work_calendar_id,
       gr.location_revision_id
FROM attendance_group_assignment aga
JOIN attendance_group_revision gr
  ON gr.attendance_group_revision_id = aga.attendance_group_revision_id
JOIN attendance_group g
  ON g.attendance_group_id = gr.attendance_group_id
WHERE aga.employee_id = @eid
  AND aga.effective_from <= @d
  AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_assignment suc
        WHERE suc.supersedes_assignment_id = aga.attendance_group_assignment_id
          AND suc.effective_from <= @d
  );

SELECT 'location' AS item,
       loc.location_id,
       loc.company_id AS loc_company,
       lr.location_revision_id
FROM attendance_group_assignment aga
JOIN attendance_group_revision gr
  ON gr.attendance_group_revision_id = aga.attendance_group_revision_id
JOIN location_revision lr
  ON lr.location_revision_id = gr.location_revision_id
JOIN location loc
  ON loc.location_id = lr.location_id
WHERE aga.employee_id = @eid
  AND aga.effective_from <= @d
  AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_assignment suc
        WHERE suc.supersedes_assignment_id = aga.attendance_group_assignment_id
          AND suc.effective_from <= @d
  );

SELECT 'calendar' AS item,
       wc.work_calendar_id,
       wc.company_id AS cal_company,
       wc.location_id AS cal_location
FROM attendance_group_assignment aga
JOIN attendance_group_revision gr
  ON gr.attendance_group_revision_id = aga.attendance_group_revision_id
JOIN work_calendar wc
  ON wc.work_calendar_id = gr.work_calendar_id
WHERE aga.employee_id = @eid
  AND aga.effective_from <= @d
  AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_assignment suc
        WHERE suc.supersedes_assignment_id = aga.attendance_group_assignment_id
          AND suc.effective_from <= @d
  );

SELECT 'shift_template' AS item,
       st.shift_template_id,
       st.company_id AS shift_company,
       st.location_id AS shift_location
FROM attendance_group_assignment aga
JOIN attendance_group_revision gr
  ON gr.attendance_group_revision_id = aga.attendance_group_revision_id
JOIN shift_template st
  ON st.shift_template_id = gr.shift_template_id
WHERE aga.employee_id = @eid
  AND aga.effective_from <= @d
  AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_assignment suc
        WHERE suc.supersedes_assignment_id = aga.attendance_group_assignment_id
          AND suc.effective_from <= @d
  );

SELECT 'assignees_by_company' AS item, e.company_id, COUNT(*) AS cnt
FROM attendance_group_assignment aga
JOIN employee e ON e.employee_id = aga.employee_id
WHERE aga.attendance_group_revision_id = '42041000-0000-0000-0000-000000000002'
GROUP BY e.company_id;

-- Align only if the group's current location/shift/calendar can be
-- pointed at the employee's company. Preview first; COMMIT at end.

START TRANSACTION;

UPDATE attendance_group g
JOIN attendance_group_revision gr
  ON gr.attendance_group_id = g.attendance_group_id
 AND gr.attendance_group_revision_id = '42041000-0000-0000-0000-000000000002'
SET g.company_id = @cid
WHERE g.company_id <> @cid;

UPDATE location loc
JOIN location_revision lr
  ON lr.location_id = loc.location_id
 AND lr.location_revision_id = 'e695875b-c6a4-ac32-6329-c14490235e50'
SET loc.company_id = @cid
WHERE loc.company_id <> @cid;

UPDATE shift_template st
JOIN location_revision lr
  ON lr.location_revision_id = 'e695875b-c6a4-ac32-6329-c14490235e50'
SET st.company_id = @cid,
    st.location_id = lr.location_id
WHERE st.shift_template_id = '42020000-0000-0000-0000-000000000002';

UPDATE work_calendar wc
JOIN location_revision lr
  ON lr.location_revision_id = 'e695875b-c6a4-ac32-6329-c14490235e50'
SET wc.company_id = @cid,
    wc.location_id = lr.location_id
WHERE wc.work_calendar_id = '41030000-0000-0000-0000-000000000003';

SELECT 'after_group' AS item, g.company_id
FROM attendance_group g
JOIN attendance_group_revision gr
  ON gr.attendance_group_id = g.attendance_group_id
WHERE gr.attendance_group_revision_id = '42041000-0000-0000-0000-000000000002';

SELECT 'after_shift' AS item, st.company_id, st.location_id
FROM shift_template st
WHERE st.shift_template_id = '42020000-0000-0000-0000-000000000002';

SELECT 'after_calendar' AS item, wc.company_id, wc.location_id
FROM work_calendar wc
WHERE wc.work_calendar_id = '41030000-0000-0000-0000-000000000003';

SELECT 'yao_style_full_authority' AS check_name, COUNT(*) AS cnt
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

-- yao_style_full_authority must be >= 1. If 0, ROLLBACK and paste output.
COMMIT;
