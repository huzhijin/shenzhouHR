-- ============================================================================
-- 夏令时下午班次配置更正：13:00-18:00 → 13:30-18:00
--
-- 只改数据库里的班次时段配置，不改任何计算/特殊规则代码。
--
-- 改什么
--   shift_version.segments_json
--     下午 WORK  13:00-18:00  →  13:30-18:00
--     紧挨着的午休 BREAK 结束 13:00  →  13:30（避免 13:00-13:30 空档）
--   同步重算 snapshot_digest，避免班次快照校验失败
--
-- 不改什么
--   大连固定班：07:30-12:00 / 13:00-16:30
--   成都固定班：09:00-12:00 / 13:00-18:00
--   冬令时下午：13:00-17:30
--   用餐扣减、迟到、打卡窗口等策略
--
-- 命中范围
--   各分公司默认冬夏令班次的夏令版本
--   上海复制扬州夏令的版本
--   后来按扬州夏令手工建的办事处班次（上午 08:30-12:00、下午 13:00-18:00）
--   或登记在 shift_seasonal_schedule.summer_version_id 中的夏令版本
--
-- 幂等：已是 13:30-18:00 的行不会再改。可重复执行。
-- 回滚：ROLLBACK_summer-afternoon-1330-post-v35.sql
--
-- 执行前请先备份：
--   mysqldump -uroot -p --single-transaction shenzhou_hr \
--     shift_version > /opt/shenzhouhr/backup/shift_version-before-1330-$(date +%Y%m%d%H%M).sql
--
-- 宝塔 phpMyAdmin：选中 shenzhou_hr 后，把本文件一次性贴进 SQL 窗口执行。
-- 命令行：
--   mysql -uroot -p shenzhou_hr < summer-afternoon-1330-post-v35.sql
-- ============================================================================

SET NAMES utf8mb4;
SET SESSION group_concat_max_len = 8192;

START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS summer_afternoon_fix_target;
CREATE TEMPORARY TABLE summer_afternoon_fix_target (
    shift_version_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    work_idx INT NOT NULL,
    break_idx INT NULL,
    PRIMARY KEY (shift_version_id)
) ENGINE=InnoDB;

INSERT INTO summer_afternoon_fix_target (
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
 AND TIME(work.start_time) = TIME('13:00:00')
 AND TIME(work.end_time) = TIME('18:00:00')
LEFT JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) brk
  ON brk.segment_type = 'BREAK'
 AND TIME(brk.end_time) = TIME('13:00:00')
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

-- 预览：即将修改的班次
SELECT company.code AS company_code,
       company.name AS company_name,
       template.template_code,
       version.version_number,
       version.effective_from,
       JSON_UNQUOTE(JSON_EXTRACT(version.segments_json, '$.effectiveTo')) AS effective_to,
       TIME_FORMAT(TIME(work.start_time), '%H:%i') AS afternoon_from,
       TIME_FORMAT(TIME(work.end_time), '%H:%i') AS afternoon_to,
       TIME_FORMAT(TIME(brk.end_time), '%H:%i') AS break_end,
       version.shift_version_id,
       '13:30-18:00' AS will_become
FROM summer_afternoon_fix_target target
JOIN shift_version version
  ON version.shift_version_id = target.shift_version_id
JOIN shift_template template
  ON template.shift_template_id = version.shift_template_id
LEFT JOIN company
  ON company.company_id = template.company_id
JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) work
  ON work.ord - 1 = target.work_idx
LEFT JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) brk
  ON target.break_idx IS NOT NULL
 AND brk.ord - 1 = target.break_idx
ORDER BY company.code, template.template_code, version.version_number;

UPDATE shift_version version
JOIN summer_afternoon_fix_target target
  ON target.shift_version_id = version.shift_version_id
SET version.segments_json = JSON_SET(
        version.segments_json,
        CONCAT('$.segments[', target.work_idx, '].startLocalTime'),
        '13:30:00'),
    version.change_reason = LEFT(
        CONCAT(
            IFNULL(version.change_reason, ''),
            ' | 2026-08-18 夏令时下午 13:00-18:00 更正为 13:30-18:00'),
        500);

UPDATE shift_version version
JOIN summer_afternoon_fix_target target
  ON target.shift_version_id = version.shift_version_id
SET version.segments_json = JSON_SET(
        version.segments_json,
        CONCAT('$.segments[', target.break_idx, '].endLocalTime'),
        '13:30:00')
WHERE target.break_idx IS NOT NULL;

UPDATE shift_version version
JOIN summer_afternoon_fix_target target
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
    JOIN summer_afternoon_fix_target digest_target
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

-- 校验：目标行现在必须是 13:30-18:00，摘要必须能对上
SELECT 'updated_rows' AS check_name,
       COUNT(*) AS actual_count,
       SUM(CASE
               WHEN TIME(work.start_time) = TIME('13:30:00')
                AND TIME(work.end_time) = TIME('18:00:00')
               THEN 1 ELSE 0 END) AS afternoon_ok,
       SUM(CASE
               WHEN target.break_idx IS NULL
                 OR TIME(brk.end_time) = TIME('13:30:00')
               THEN 1 ELSE 0 END) AS break_ok
FROM summer_afternoon_fix_target target
JOIN shift_version version
  ON version.shift_version_id = target.shift_version_id
JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) work
  ON work.ord - 1 = target.work_idx
LEFT JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) brk
  ON target.break_idx IS NOT NULL
 AND brk.ord - 1 = target.break_idx;

SELECT 'remaining_yangzhou_summer_1300_1800' AS check_name,
       COUNT(*) AS should_be_zero
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
 AND TIME(work.start_time) = TIME('13:00:00')
 AND TIME(work.end_time) = TIME('18:00:00')
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
  AND (season.summer_version_id IS NOT NULL OR morning.ord IS NOT NULL);

SELECT 'chengdu_unchanged' AS check_name,
       COUNT(*) AS should_stay
FROM shift_template template
JOIN shift_version version
  ON version.shift_template_id = template.shift_template_id
JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) work
  ON work.segment_type = 'WORK'
 AND TIME(work.start_time) = TIME('13:00:00')
 AND TIME(work.end_time) = TIME('18:00:00')
WHERE template.template_code = 'CHENGDU_FIXED';

SELECT 'dalian_unchanged' AS check_name,
       COUNT(*) AS should_stay
FROM shift_template template
JOIN shift_version version
  ON version.shift_template_id = template.shift_template_id
JOIN JSON_TABLE(version.segments_json, '$.segments[*]' COLUMNS (
        ord FOR ORDINALITY,
        segment_type VARCHAR(32) PATH '$.segmentType',
        start_time VARCHAR(16) PATH '$.startLocalTime',
        end_time VARCHAR(16) PATH '$.endLocalTime'
    )) work
  ON work.segment_type = 'WORK'
 AND TIME(work.start_time) = TIME('13:00:00')
 AND TIME(work.end_time) = TIME('16:30:00')
WHERE template.template_code = 'DALIAN_FIXED';

COMMIT;

SELECT 'done' AS status,
       '夏令时下午已改为 13:30-18:00。实时报表下次查询按新班次计算；已缓存的核算结果请在页面重新刷新。' AS note;
