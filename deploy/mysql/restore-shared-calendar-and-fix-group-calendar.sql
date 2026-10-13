-- Undo the shared-calendar location move, then attach 周彦沛's
-- group to a calendar that already sits on his location.
-- Do NOT run through Flyway.

SET NAMES utf8mb4;
SET @yao = '3ae8fd69-5412-5ffb-a87e-870e183f4294';
SET @zhou = 'c47348ca-336f-51a2-b97d-ff40fd74bdce';
SET @cid = '41000000-0000-0000-0000-000000000003';
SET @d = DATE('2026-07-30');
SET @asof = NOW(6);
SET @shared_cal = '41030000-0000-0000-0000-000000000003';
SET @shared_cal_loc = '41010000-0000-0000-0000-000000000003';
SET @zhou_group_rev = '42041000-0000-0000-0000-000000000002';
SET @zhou_loc = 'b5ff3c02-d36f-ad1c-5b21-000d2e10a9d2';

START TRANSACTION;

-- 1) Put the shared calendar back on its original location.
UPDATE work_calendar
SET location_id = @shared_cal_loc
WHERE work_calendar_id = @shared_cal;

SELECT 'calendars_on_zhou_location' AS item,
       work_calendar_id, company_id, location_id
FROM work_calendar
WHERE location_id = @zhou_loc;

-- 2) If this location already has a calendar, point 周彦沛's group at it.
UPDATE attendance_group_revision gr
JOIN (
    SELECT work_calendar_id
    FROM work_calendar
    WHERE location_id = @zhou_loc
      AND company_id = @cid
    ORDER BY work_calendar_id
    LIMIT 1
) cal ON 1 = 1
SET gr.work_calendar_id = cal.work_calendar_id
WHERE gr.attendance_group_revision_id = @zhou_group_rev;

SELECT 'zhou_group_calendar' AS item,
       gr.attendance_group_revision_id,
       gr.work_calendar_id,
       wc.location_id
FROM attendance_group_revision gr
JOIN work_calendar wc
  ON wc.work_calendar_id = gr.work_calendar_id
WHERE gr.attendance_group_revision_id = @zhou_group_rev;

SELECT 'shared_calendar_location' AS item,
       wc.work_calendar_id, wc.location_id
FROM work_calendar wc
WHERE wc.work_calendar_id = @shared_cal;

SELECT 'authority' AS who, employee.employee_id, COUNT(*) AS cnt
FROM company company
JOIN employee employee
  ON employee.company_id = company.company_id
 AND employee.employee_id IN (@yao, @zhou)
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
  AND location_revision.time_zone = effective_shift.time_zone_snapshot
GROUP BY employee.employee_id;

-- Expect two rows (姚志淼, 周彦沛), each cnt >= 1.
-- If 周彦沛 is missing and calendars_on_zhou_location was empty, ROLLBACK.
COMMIT;
