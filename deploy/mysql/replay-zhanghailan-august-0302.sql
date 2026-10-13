-- Preview 张海兰 8 月旧 SZST0302 卡。不要把姜长波并过去。
-- 回放写入用 scripts/fix-zhanghailan-august-punches.py（APPLY=1）。
-- 本文件只核对，不改库。

SET NAMES utf8mb4;

SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       employee.employee_id,
       employee.company_id
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.employee_number IN ('SZST0303', 'SZST0302')
   OR version.display_name IN ('张海兰', '姜长波');

SELECT version.employee_number AS 工号,
       version.display_name AS 姓名,
       COUNT(*) AS 八月有效卡数,
       MIN(CONVERT_TZ(event.point_instant, '+00:00', '+08:00')) AS 最早上海,
       MAX(CONVERT_TZ(event.point_instant, '+00:00', '+08:00')) AS 最晚上海
FROM effective_attendance_event event
JOIN employee_version version
  ON version.employee_id = event.employee_id
 AND version.effective_to IS NULL
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.point_instant >= '2026-07-31 16:00:00'
  AND event.point_instant <  '2026-08-31 16:00:00'
  AND version.employee_number IN ('SZST0303', 'SZST0302')
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

SELECT CONVERT(binding.deli_employee_num USING utf8mb4) AS deli_empno,
       version.employee_number AS 花名册工号,
       version.display_name AS 姓名,
       CONVERT(binding.binding_status USING utf8mb4) AS 状态
FROM employee_source_binding binding
JOIN employee_version version
  ON version.employee_id = binding.employee_id
 AND version.effective_to IS NULL
WHERE binding.effective_to IS NULL
  AND (
        version.employee_number IN ('SZST0303', 'SZST0302')
        OR version.display_name IN ('张海兰', '姜长波')
      );
