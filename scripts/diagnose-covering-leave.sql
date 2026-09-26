-- Find leave/time-off OA facts whose interval is the union of other same-person
-- leave facts (张晓冬 8/31 调休+事假 再出一条 UNKNOWN 事假汇总).
-- Preview only. Bind: company_id, from_date, to_date_exclusive.

SELECT
    employee.employee_number,
    employee.display_name,
    covering.oa_attendance_document_id AS covering_id,
    covering.source_status AS covering_status,
    covering.leave_type_code AS covering_type,
    covering.interval_start AS covering_start,
    covering.interval_end AS covering_end,
    covering.recognized_minutes AS covering_minutes,
    COUNT(*) AS contained_count,
    SUM(contained.recognized_minutes) AS contained_minutes,
    GROUP_CONCAT(
        CONCAT(
            COALESCE(contained.leave_type_code, contained.document_type),
            ' ',
            contained.recognized_minutes,
            'm ',
            contained.source_status)
        ORDER BY contained.interval_start
        SEPARATOR ' | ') AS contained_parts
FROM attendance_report_oa_fact covering
JOIN employee_version employee
  ON employee.employee_version_id = covering.employee_version_id
JOIN attendance_report_oa_fact contained
  ON contained.attendance_report_projection_id =
     covering.attendance_report_projection_id
 AND contained.employee_id = covering.employee_id
 AND contained.oa_attendance_document_id <>
     covering.oa_attendance_document_id
 AND contained.document_type IN ('LEAVE', 'TIME_OFF')
 AND contained.interval_start >= covering.interval_start
 AND contained.interval_end <= covering.interval_end
 AND (
        contained.interval_start > covering.interval_start
     OR contained.interval_end < covering.interval_end
 )
JOIN attendance_report_projection projection
  ON projection.attendance_report_projection_id =
     covering.attendance_report_projection_id
WHERE covering.company_id = '41000000-0000-0000-0000-000000000003'
  AND covering.document_type IN ('LEAVE', 'TIME_OFF')
  AND covering.interval_start >= '2026-08-01'
  AND covering.interval_start < '2026-09-01'
  AND projection.period_status IN ('OPEN', 'CLOSED')
GROUP BY
    employee.employee_number,
    employee.display_name,
    covering.oa_attendance_document_id,
    covering.source_status,
    covering.leave_type_code,
    covering.interval_start,
    covering.interval_end,
    covering.recognized_minutes
HAVING contained_count >= 2
    OR (
        covering.source_status = 'UNKNOWN'
        AND covering.recognized_minutes = contained_minutes
    )
ORDER BY employee.employee_number, covering.interval_start;
