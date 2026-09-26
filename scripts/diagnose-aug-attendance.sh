#!/usr/bin/env bash
# 8 月考勤/得力/OA 排查。在服务器上执行，把全部终端输出发回即可。
# 用法：
#   bash diagnose-aug-attendance.sh
# 或先：export MYSQL_PWD='数据库密码'
set -euo pipefail
MYSQL=(mysql --default-character-set=utf8mb4 --batch --raw --table
  -ushenzhouhr_app shenzhou_hr)
if [[ -z "${MYSQL_PWD:-}" ]]; then
  echo "请输入 shenzhou_hr 库密码（shenzhouhr_app 或 root）："
  read -r -s MYSQL_PWD
  export MYSQL_PWD
  echo
fi

run() {
  echo
  echo "======== $1 ========"
  "${MYSQL[@]}" -e "$2" || echo "【本段失败，继续下一段】"
}

run "1 源与水位" "
SELECT
  s.source_type,
  s.source_code,
  s.status,
  DATE_ADD(w.committed_at, INTERVAL 8 HOUR) AS committed_cn,
  (SELECT DATE_ADD(MAX(j.finished_at), INTERVAL 8 HOUR)
     FROM attendance_sync_job j
    WHERE j.attendance_source_id = s.attendance_source_id
      AND j.status IN ('SUCCEEDED','PARTIALLY_QUARANTINED')) AS last_ok_cn,
  (SELECT DATE_ADD(MAX(j.finished_at), INTERVAL 8 HOUR)
     FROM attendance_sync_job j
    WHERE j.attendance_source_id = s.attendance_source_id
      AND j.status = 'FAILED') AS last_fail_cn
FROM attendance_source s
LEFT JOIN attendance_sync_watermark w
  ON w.attendance_source_id = s.attendance_source_id
WHERE s.source_type IN ('DELI_CLOUD','OA_ATTENDANCE')
ORDER BY s.source_type, s.source_code;
"

run "2 得力定时日志最近20次" "
SELECT
  DATE_ADD(sync_started_at, INTERVAL 8 HOUR) AS start_cn,
  DATE_ADD(sync_completed_at, INTERVAL 8 HOUR) AS end_cn,
  sync_status,
  record_count,
  LEFT(error_message, 120) AS error_message,
  execution_duration_ms
FROM deli_sync_log
ORDER BY sync_started_at DESC
LIMIT 20;
"

run "3 得力作业最近20次" "
SELECT
  j.status,
  j.safe_error_code,
  j.accepted_count,
  j.quarantined_count,
  DATE_ADD(j.finished_at, INTERVAL 8 HOUR) AS finished_cn
FROM attendance_sync_job j
JOIN attendance_source s
  ON s.attendance_source_id = j.attendance_source_id
WHERE s.source_type = 'DELI_CLOUD'
ORDER BY j.finished_at DESC
LIMIT 20;
"

run "4 8月每天有效打卡人数" "
SELECT
  DATE(DATE_ADD(event.point_instant, INTERVAL 8 HOUR)) AS biz_date,
  COUNT(*) AS punch_count,
  COUNT(DISTINCT e.employee_number) AS people_with_punch
FROM effective_attendance_event event
JOIN employee e ON e.employee_id = event.employee_id
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.point_instant >= '2026-07-31 16:00:00'
  AND event.point_instant <  '2026-08-20 16:00:00'
  AND (
    SELECT life.lifecycle_type
    FROM effective_event_lifecycle_fact life
    WHERE life.effective_attendance_event_id = event.effective_attendance_event_id
    ORDER BY life.knowledge_at DESC, life.effective_event_lifecycle_fact_id DESC
    LIMIT 1
  ) = 'ACTIVATED'
GROUP BY DATE(DATE_ADD(event.point_instant, INTERVAL 8 HOUR))
ORDER BY biz_date;
"

run "5 8月每天仅1次有效卡的人数" "
SELECT biz_date, COUNT(*) AS people_with_one_punch
FROM (
  SELECT
    e.employee_number,
    DATE(DATE_ADD(event.point_instant, INTERVAL 8 HOUR)) AS biz_date,
    COUNT(*) AS n
  FROM effective_attendance_event event
  JOIN employee e ON e.employee_id = event.employee_id
  WHERE event.event_kind = 'PUNCH_POINT'
    AND event.point_instant >= '2026-07-31 16:00:00'
    AND event.point_instant <  '2026-08-20 16:00:00'
    AND (
      SELECT life.lifecycle_type
      FROM effective_event_lifecycle_fact life
      WHERE life.effective_attendance_event_id = event.effective_attendance_event_id
      ORDER BY life.knowledge_at DESC, life.effective_event_lifecycle_fact_id DESC
      LIMIT 1
    ) = 'ACTIVATED'
  GROUP BY e.employee_number, DATE(DATE_ADD(event.point_instant, INTERVAL 8 HOUR))
  HAVING n = 1
) t
GROUP BY biz_date
ORDER BY biz_date;
"

run "6 打卡生命周期分布" "
SELECT COALESCE(life.lifecycle_type, 'NONE') AS lifecycle, COUNT(*) AS n
FROM effective_attendance_event event
LEFT JOIN (
  SELECT effective_attendance_event_id, lifecycle_type
  FROM effective_event_lifecycle_fact f1
  WHERE f1.effective_event_lifecycle_fact_id = (
    SELECT f2.effective_event_lifecycle_fact_id
    FROM effective_event_lifecycle_fact f2
    WHERE f2.effective_attendance_event_id = f1.effective_attendance_event_id
    ORDER BY f2.knowledge_at DESC, f2.effective_event_lifecycle_fact_id DESC
    LIMIT 1
  )
) life ON life.effective_attendance_event_id = event.effective_attendance_event_id
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.point_instant >= '2026-07-31 16:00:00'
  AND event.point_instant <  '2026-08-20 16:00:00'
GROUP BY COALESCE(life.lifecycle_type, 'NONE')
ORDER BY n DESC;
"

run "7 样本人最早最晚卡 姚芳伟SZST0040 8月6日" "
SELECT
  e.employee_number,
  DATE(DATE_ADD(event.point_instant, INTERVAL 8 HOUR)) AS biz_date,
  DATE_FORMAT(MIN(DATE_ADD(event.point_instant, INTERVAL 8 HOUR)), '%H:%i') AS morning,
  DATE_FORMAT(MAX(DATE_ADD(event.point_instant, INTERVAL 8 HOUR)), '%H:%i') AS afternoon,
  COUNT(*) AS punch_count
FROM effective_attendance_event event
JOIN employee e ON e.employee_id = event.employee_id
WHERE e.employee_number IN ('SZST0040','SZST0041','SZST0042')
  AND event.event_kind = 'PUNCH_POINT'
  AND DATE(DATE_ADD(event.point_instant, INTERVAL 8 HOUR)) BETWEEN '2026-08-01' AND '2026-08-20'
  AND (
    SELECT life.lifecycle_type
    FROM effective_event_lifecycle_fact life
    WHERE life.effective_attendance_event_id = event.effective_attendance_event_id
    ORDER BY life.knowledge_at DESC, life.effective_event_lifecycle_fact_id DESC
    LIMIT 1
  ) = 'ACTIVATED'
GROUP BY e.employee_number, DATE(DATE_ADD(event.point_instant, INTERVAL 8 HOUR))
ORDER BY e.employee_number, biz_date;
"

run "8 8月已批准加班单人数" "
SELECT
  DATE(DATE_ADD(n.interval_start, INTERVAL 8 HOUR)) AS biz_date,
  COUNT(*) AS ot_docs
FROM oa_attendance_document oa
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
WHERE oa.document_type = 'OVERTIME'
  AND oa.source_status = 'APPROVED'
  AND n.interval_start < '2026-08-20 16:00:00'
  AND n.interval_end > '2026-07-31 16:00:00'
GROUP BY DATE(DATE_ADD(n.interval_start, INTERVAL 8 HOUR))
ORDER BY biz_date;
"

echo
echo "======== 完成，请把上面全部输出发回 ========"
