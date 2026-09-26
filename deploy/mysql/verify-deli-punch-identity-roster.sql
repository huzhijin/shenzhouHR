-- Whole-roster Deli identity check for one company-month.
-- Named people in canary sections are examples only; class queries have no name filter.
--
--   mysql --default-character-set=utf8mb4 shenzhou_hr \
--     < deploy/mysql/verify-deli-punch-identity-roster.sql
--
-- Instant bounds are UTC. Shanghai 2026-08 = [2026-07-31 16:00, 2026-08-31 16:00).
-- Shanghai 8/14 00:00 = UTC 2026-08-13 16:00.

SET NAMES utf8mb4;
SET time_zone = '+00:00';
SET @company_id := IFNULL(
    @company_id,
    '41000000-0000-0000-0000-000000000003');
SET @from_utc := IFNULL(@from_utc, '2026-07-31 16:00:00');
SET @to_utc := IFNULL(@to_utc, '2026-08-31 16:00:00');
SET @split_utc := IFNULL(@split_utc, '2026-08-13 16:00:00');

SELECT 'orphan_punch_events' AS section,
       COUNT(*) AS orphan_events,
       MIN(event.point_instant) AS earliest_utc,
       MAX(event.point_instant) AS latest_utc
FROM effective_attendance_event event
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.company_id = @company_id
  AND event.point_instant >= @from_utc
  AND event.point_instant < @to_utc
  AND NOT EXISTS (
        SELECT 1
        FROM evidence_link link
        WHERE link.effective_attendance_event_id = event.effective_attendance_event_id
  );

SELECT 'confirmed_deli_bindings' AS section,
       version.employee_number,
       version.display_name,
       CONVERT(binding.deli_user_id USING utf8mb4) AS deli_user_id,
       CONVERT(binding.deli_ext_id USING utf8mb4) AS deli_ext_id,
       CONVERT(binding.binding_status USING utf8mb4) AS binding_status
FROM employee_source_binding binding
JOIN employee employee
  ON employee.employee_id = binding.employee_id
JOIN employee_current_projection projection
  ON projection.employee_id = binding.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
WHERE employee.company_id = @company_id
  AND binding.effective_to IS NULL
  AND binding.binding_status = 'CONFIRMED'
  AND (
        binding.deli_user_id IS NOT NULL
        OR binding.deli_ext_id IS NOT NULL
      )
ORDER BY version.employee_number;

SELECT 'matched_valid_punches_without_event' AS section,
       version.employee_number,
       version.display_name,
       matched.match_reason,
       COUNT(*) AS raw_rows
FROM raw_attendance_fact raw
JOIN attendance_source source
  ON source.attendance_source_id = raw.attendance_source_id
JOIN normalized_attendance_record normalized
  ON normalized.raw_attendance_fact_id = raw.raw_attendance_fact_id
JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id = normalized.normalized_attendance_record_id
LEFT JOIN employee_current_projection projection
  ON projection.employee_id = matched.employee_id
LEFT JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
WHERE source.source_type = 'DELI_CLOUD'
  AND raw.fact_kind = 'PUNCH_POINT'
  AND normalized.record_kind = 'PUNCH_POINT'
  AND normalized.validation_status = 'VALID'
  AND matched.match_status = 'MATCHED'
  AND raw.source_instant >= @from_utc
  AND raw.source_instant < @to_utc
  AND NOT EXISTS (
        SELECT 1
        FROM evidence_link link
        JOIN effective_attendance_event event
          ON event.effective_attendance_event_id = link.effective_attendance_event_id
        WHERE link.raw_attendance_fact_id = raw.raw_attendance_fact_id
          AND event.event_kind = 'PUNCH_POINT'
          AND event.employee_id = matched.employee_id
  )
GROUP BY version.employee_number, version.display_name, matched.match_reason
ORDER BY raw_rows DESC, version.employee_number;

SELECT 'events_before_vs_after_aug14' AS section,
       version.employee_number,
       version.display_name,
       SUM(event.point_instant < @split_utc) AS events_aug1_13,
       SUM(event.point_instant >= @split_utc) AS events_aug14_on,
       COUNT(*) AS events_month
FROM effective_attendance_event event
JOIN employee_current_projection projection
  ON projection.employee_id = event.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.company_id = @company_id
  AND event.point_instant >= @from_utc
  AND event.point_instant < @to_utc
  AND EXISTS (
        SELECT 1
        FROM evidence_link link
        WHERE link.effective_attendance_event_id = event.effective_attendance_event_id
  )
GROUP BY version.employee_number, version.display_name
HAVING SUM(event.point_instant < @split_utc) = 0
   AND SUM(event.point_instant >= @split_utc) > 0
ORDER BY events_aug14_on DESC, version.employee_number;

SELECT 'confirmed_binding_match_reasons' AS section,
       version.employee_number,
       version.display_name,
       SUM(matched.match_reason = 'CONFIRMED_BINDING') AS confirmed_binding_hits,
       SUM(matched.match_reason = 'EMPLOYEE_NUMBER') AS employee_number_hits,
       COUNT(*) AS matched_raw
FROM employee_source_binding binding
JOIN employee employee
  ON employee.employee_id = binding.employee_id
JOIN employee_current_projection projection
  ON projection.employee_id = binding.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
JOIN employee_match_decision matched
  ON matched.employee_id = binding.employee_id
 AND matched.match_status = 'MATCHED'
JOIN normalized_attendance_record normalized
  ON normalized.normalized_attendance_record_id = matched.normalized_attendance_record_id
JOIN raw_attendance_fact raw
  ON raw.raw_attendance_fact_id = normalized.raw_attendance_fact_id
JOIN attendance_source source
  ON source.attendance_source_id = raw.attendance_source_id
WHERE employee.company_id = @company_id
  AND binding.effective_to IS NULL
  AND binding.binding_status = 'CONFIRMED'
  AND source.source_type = 'DELI_CLOUD'
  AND raw.fact_kind = 'PUNCH_POINT'
  AND raw.source_instant >= @from_utc
  AND raw.source_instant < @to_utc
GROUP BY version.employee_number, version.display_name
ORDER BY version.employee_number;

SELECT 'canary_pengwei_events' AS section,
       DATE(CONVERT_TZ(event.point_instant, '+00:00', '+08:00')) AS biz_date,
       TIME(CONVERT_TZ(event.point_instant, '+00:00', '+08:00')) AS clock_time,
       version.employee_number,
       version.display_name,
       CONVERT(link.link_type USING utf8mb4) AS link_type,
       matched.match_reason
FROM effective_attendance_event event
JOIN employee_current_projection projection
  ON projection.employee_id = event.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
LEFT JOIN evidence_link link
  ON link.effective_attendance_event_id = event.effective_attendance_event_id
LEFT JOIN employee_match_decision matched
  ON matched.employee_match_decision_id = link.employee_match_decision_id
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.company_id = @company_id
  AND event.point_instant >= @from_utc
  AND event.point_instant < @to_utc
  AND version.employee_number = 'SZST0335'
  AND version.display_name = '彭伟'
ORDER BY event.point_instant;

SELECT 'canary_pengwei_and_zhao_matched_raw' AS section,
       CONVERT_TZ(raw.source_instant, '+00:00', '+08:00') AS punch_shanghai,
       version.employee_number AS matched_empno,
       version.display_name AS matched_name,
       matched.match_status,
       matched.match_reason,
       CONVERT(normalized.validation_status USING utf8mb4) AS validation_status,
       CONVERT(normalized.issue_code USING utf8mb4) AS issue_code
FROM raw_attendance_fact raw
JOIN attendance_source source
  ON source.attendance_source_id = raw.attendance_source_id
JOIN normalized_attendance_record normalized
  ON normalized.raw_attendance_fact_id = raw.raw_attendance_fact_id
LEFT JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id = normalized.normalized_attendance_record_id
LEFT JOIN employee_current_projection projection
  ON projection.employee_id = matched.employee_id
LEFT JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
WHERE source.source_type = 'DELI_CLOUD'
  AND raw.fact_kind = 'PUNCH_POINT'
  AND raw.source_instant >= @from_utc
  AND raw.source_instant < @to_utc
  AND version.employee_number IN ('SZST0335', 'SZST0289')
ORDER BY raw.source_instant, version.employee_number;
