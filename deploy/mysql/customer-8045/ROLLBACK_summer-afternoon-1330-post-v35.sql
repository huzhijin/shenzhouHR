-- ============================================================================
-- 回滚：夏令时下午 13:30-18:00 → 13:00-18:00
-- 对应脚本：summer-afternoon-1330-post-v35.sql
-- 大连 / 成都固定班仍不改。
-- ============================================================================

SET NAMES utf8mb4;
SET SESSION group_concat_max_len = 8192;

START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS summer_afternoon_rollback_target;
CREATE TEMPORARY TABLE summer_afternoon_rollback_target (
    shift_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_idx INT NOT NULL,
    break_idx INT NULL,
    PRIMARY KEY (shift_version_id)
) ENGINE=InnoDB;

INSERT INTO summer_afternoon_rollback_target (
    shift_version_id, work_idx, break_idx
)
SELECT work.shift_version_id,
       work.ord - 1 AS work_idx,
       MIN(brk.ord) - 1 AS break_idx
FROM shift_version version
JOIN shift_template template
  ON template.shift_template_id = version.shift_template_id
JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) work
  ON work.segment_type = 'WORK'
 AND TIME(work.start_time) = TIME('13:30:00')
 AND TIME(work.end_time) = TIME('18:00:00')
LEFT JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) brk
  ON brk.segment_type = 'BREAK'
 AND TIME(brk.end_time) = TIME('13:30:00')
LEFT JOIN shift_seasonal_schedule season
  ON season.summer_version_id = version.shift_version_id
LEFT JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) morning
  ON morning.segment_type = 'WORK'
 AND TIME(morning.start_time) = TIME('08:30:00')
 AND TIME(morning.end_time) = TIME('12:00:00')
WHERE template.template_code <> 'CHENGDU_FIXED'
  AND (season.summer_version_id IS NOT NULL OR morning.ord IS NOT NULL)
GROUP BY work.shift_version_id, work.ord;

SELECT company.code AS company_code,
       template.template_code,
       version.version_number,
       version.effective_from,
       version.shift_version_id,
       '13:00-18:00' AS will_become
FROM summer_afternoon_rollback_target target
JOIN shift_version version
  ON version.shift_version_id = target.shift_version_id
JOIN shift_template template
  ON template.shift_template_id = version.shift_template_id
LEFT JOIN company
  ON company.company_id = template.company_id
ORDER BY company.code, template.template_code, version.version_number;

UPDATE shift_version version
JOIN summer_afternoon_rollback_target target
  ON target.shift_version_id = version.shift_version_id
SET version.segments_json = JSON_SET(
        version.segments_json,
        CONCAT('$.segments[', target.work_idx, '].startLocalTime'),
        '13:00:00'),
    version.change_reason = LEFT(
        CONCAT(
            IFNULL(version.change_reason, ''),
            ' | 回滚夏令时下午 13:30-18:00 为 13:00-18:00'),
        500);

UPDATE shift_version version
JOIN summer_afternoon_rollback_target target
  ON target.shift_version_id = version.shift_version_id
SET version.segments_json = JSON_SET(
        version.segments_json,
        CONCAT('$.segments[', target.break_idx, '].endLocalTime'),
        '13:00:00')
WHERE target.break_idx IS NOT NULL;

UPDATE shift_version version
JOIN summer_afternoon_rollback_target target
  ON target.shift_version_id = version.shift_version_id
JOIN (
    SELECT source.shift_version_id,
           COUNT(*) AS segment_count,
           GROUP_CONCAT(
               CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
                   'S:', OCTET_LENGTH(segment.segment_type), ':', segment.segment_type,
                   'S:', OCTET_LENGTH(TIME_FORMAT(TIME(segment.start_time), '%H:%i')),
                       ':', TIME_FORMAT(TIME(segment.start_time), '%H:%i'),
                   'S:', OCTET_LENGTH(segment.start_offset), ':', segment.start_offset,
                   'S:', OCTET_LENGTH(TIME_FORMAT(TIME(segment.end_time), '%H:%i')),
                       ':', TIME_FORMAT(TIME(segment.end_time), '%H:%i'),
                   'S:', OCTET_LENGTH(segment.end_offset), ':', segment.end_offset)
               ORDER BY segment.ord SEPARATOR '') AS segment_body
    FROM shift_version source
    JOIN summer_afternoon_rollback_target digest_target
      ON digest_target.shift_version_id = source.shift_version_id
    JOIN JSON_TABLE(source.segments_json, '$.segments[*]' COLUMNS (
            ord FOR ORDINALITY,
            segment_type VARCHAR(32) PATH '$.segmentType',
            start_time VARCHAR(16) PATH '$.startLocalTime',
            start_offset INT PATH '$.startDayOffset',
            end_time VARCHAR(16) PATH '$.endLocalTime',
            end_offset INT PATH '$.endDayOffset'
        )) segment ON TRUE
    GROUP BY source.shift_version_id
) segments
  ON segments.shift_version_id = version.shift_version_id
SET version.snapshot_digest = SHA2(CONCAT(_utf8mb4'' COLLATE utf8mb4_bin,
        'S:17:shift-snapshot-v1',
        'S:36:', version.shift_template_id,
        'S:36:', version.shift_version_id,
        'S:', OCTET_LENGTH(version.version_number), ':', version.version_number,
        'S:10:', version.effective_from,
        CASE
            WHEN JSON_EXTRACT(version.segments_json, '$.effectiveTo') IS NULL
              OR JSON_UNQUOTE(JSON_EXTRACT(version.segments_json, '$.effectiveTo')) IN ('', 'null')
            THEN 'N:0:'
            ELSE CONCAT(
                'S:',
                OCTET_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(version.segments_json, '$.effectiveTo'))),
                ':',
                JSON_UNQUOTE(JSON_EXTRACT(version.segments_json, '$.effectiveTo')))
        END,
        'S:', OCTET_LENGTH(version.time_zone_snapshot), ':', version.time_zone_snapshot,
        'S:', OCTET_LENGTH(segments.segment_count), ':', segments.segment_count,
        segments.segment_body), 256);

COMMIT;

SELECT 'rolled_back' AS status,
       COUNT(*) AS rows_touched
FROM summer_afternoon_rollback_target;
