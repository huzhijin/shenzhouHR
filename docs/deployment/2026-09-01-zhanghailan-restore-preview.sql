-- 张海兰：8/31 回放把她 8 条卡 SUPERSEDED。只读预览。
SET NAMES utf8mb4;

SELECT 'D-这8条现在最新匹配' AS 段,
       CONVERT_TZ(raw.source_instant,'+00:00','+08:00') AS 上海,
       version.employee_number AS 工号,
       version.display_name AS 姓名,
       normalized.validation_status AS 校验,
       IFNULL(normalized.issue_code,'') AS 错误码,
       matched.match_reason AS 认人
FROM effective_attendance_event event
JOIN evidence_link link
  ON link.effective_attendance_event_id = event.effective_attendance_event_id
JOIN raw_attendance_fact raw
  ON raw.raw_attendance_fact_id = link.raw_attendance_fact_id
JOIN normalized_attendance_record normalized
  ON normalized.raw_attendance_fact_id = raw.raw_attendance_fact_id
 AND normalized.normalization_revision = (
        SELECT MAX(latest.normalization_revision)
        FROM normalized_attendance_record latest
        WHERE latest.raw_attendance_fact_id = raw.raw_attendance_fact_id
 )
JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id =
     normalized.normalized_attendance_record_id
LEFT JOIN employee_version version
  ON version.employee_id = matched.employee_id
 AND version.effective_to IS NULL
WHERE event.employee_id = '05f44bfa-c486-51ef-a605-fae3c8fec7e4'
ORDER BY raw.source_instant;

SELECT 'E-姜长波隔离卡时间' AS 段,
       matched.match_reason AS 认人,
       COUNT(*) AS 条数,
       MIN(CONVERT_TZ(raw.source_instant,'+00:00','+08:00')) AS 最早,
       MAX(CONVERT_TZ(raw.source_instant,'+00:00','+08:00')) AS 最晚
FROM employee_match_decision matched
JOIN normalized_attendance_record normalized
  ON normalized.normalized_attendance_record_id =
     matched.normalized_attendance_record_id
JOIN raw_attendance_fact raw
  ON raw.raw_attendance_fact_id = normalized.raw_attendance_fact_id
WHERE matched.employee_id = '8c85e441-2181-5385-87ba-15710bd12080'
  AND raw.fact_kind = 'PUNCH_POINT'
  AND normalized.validation_status = 'QUARANTINED'
GROUP BY matched.match_reason;

SELECT 'F-同时刻有没有别人已激活' AS 段,
       CONVERT_TZ(event.point_instant,'+00:00','+08:00') AS 上海,
       version.employee_number AS 工号,
       version.display_name AS 姓名,
       (
         SELECT lifecycle.lifecycle_type
         FROM effective_event_lifecycle_fact lifecycle
         WHERE lifecycle.effective_attendance_event_id =
               event.effective_attendance_event_id
         ORDER BY lifecycle.knowledge_at DESC,
                  lifecycle.effective_event_lifecycle_fact_id DESC
         LIMIT 1
       ) AS 最新状态
FROM effective_attendance_event event
JOIN employee_version version
  ON version.employee_id = event.employee_id
 AND version.effective_to IS NULL
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.point_instant IN (
        SELECT zhang.point_instant
        FROM effective_attendance_event zhang
        WHERE zhang.employee_id = '05f44bfa-c486-51ef-a605-fae3c8fec7e4'
      )
ORDER BY event.point_instant, version.employee_number;
