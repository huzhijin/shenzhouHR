-- 张海兰卡在哪：只读。宝塔整段执行。
SET NAMES utf8mb4;

SELECT 'roster' AS section,
       employee.employee_number,
       version.display_name,
       employee.employee_id
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.employee_number IN ('SZST0303','SZST0302');

SELECT 'all_time_activated' AS section,
       version.employee_number,
       version.display_name,
       COUNT(*) AS n,
       MIN(CONVERT_TZ(event.point_instant,'+00:00','+08:00')) AS first_sh,
       MAX(CONVERT_TZ(event.point_instant,'+00:00','+08:00')) AS last_sh
FROM effective_attendance_event event
JOIN employee_version version
  ON version.employee_id = event.employee_id
 AND version.effective_to IS NULL
WHERE event.event_kind = 'PUNCH_POINT'
  AND version.employee_number IN ('SZST0303','SZST0302')
  AND (
        SELECT lifecycle.lifecycle_type
        FROM effective_event_lifecycle_fact lifecycle
        WHERE lifecycle.effective_attendance_event_id =
              event.effective_attendance_event_id
        ORDER BY lifecycle.knowledge_at DESC,
                 lifecycle.effective_event_lifecycle_fact_id DESC
        LIMIT 1
      ) = 'ACTIVATED'
GROUP BY version.employee_number, version.display_name;

SELECT 'july_activated' AS section,
       version.employee_number,
       COUNT(*) AS n,
       MIN(CONVERT_TZ(event.point_instant,'+00:00','+08:00')) AS first_sh,
       MAX(CONVERT_TZ(event.point_instant,'+00:00','+08:00')) AS last_sh
FROM effective_attendance_event event
JOIN employee_version version
  ON version.employee_id = event.employee_id
 AND version.effective_to IS NULL
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.point_instant >= '2026-06-30 16:00:00'
  AND event.point_instant <  '2026-07-31 16:00:00'
  AND version.employee_number IN ('SZST0303','SZST0302')
  AND (
        SELECT lifecycle.lifecycle_type
        FROM effective_event_lifecycle_fact lifecycle
        WHERE lifecycle.effective_attendance_event_id =
              event.effective_attendance_event_id
        ORDER BY lifecycle.knowledge_at DESC,
                 lifecycle.effective_event_lifecycle_fact_id DESC
        LIMIT 1
      ) = 'ACTIVATED'
GROUP BY version.employee_number;

SELECT 'august_deli_raw_total' AS section,
       COUNT(*) AS n,
       MIN(CONVERT_TZ(raw.source_instant,'+00:00','+08:00')) AS first_sh,
       MAX(CONVERT_TZ(raw.source_instant,'+00:00','+08:00')) AS last_sh
FROM raw_attendance_fact raw
JOIN attendance_source source
  ON source.attendance_source_id = raw.attendance_source_id
 AND source.source_type = 'DELI_CLOUD'
WHERE raw.fact_kind = 'PUNCH_POINT'
  AND raw.source_instant >= '2026-07-31 16:00:00'
  AND raw.source_instant <  '2026-08-31 16:00:00';

SELECT 'legacy_snowflake_binding' AS section,
       version.employee_number,
       version.display_name,
       CONVERT(binding.deli_user_id USING utf8mb4) AS deli_user_id,
       CONVERT(binding.binding_status USING utf8mb4) AS status
FROM employee_source_binding binding
JOIN employee_version version
  ON version.employee_id = binding.employee_id
 AND version.effective_to IS NULL
WHERE binding.effective_to IS NULL
  AND (
        binding.deli_user_id = '967800501586812928'
        OR version.employee_number IN ('SZST0303','SZST0302')
      );
