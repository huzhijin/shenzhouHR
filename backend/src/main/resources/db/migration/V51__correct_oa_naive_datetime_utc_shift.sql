-- OA DATETIME is Shanghai wall-clock. Older JDBC reads stored 08:30 as 08:30Z,
-- so reports rendered 16:30. Shift already-ingested OA intervals back 8 hours.
-- Idempotent: skip rows whose source_version already records the correction
-- or the new Asia/Shanghai ingest.

UPDATE normalized_attendance_record nar
INNER JOIN oa_attendance_document oa
    ON oa.normalized_attendance_record_id = nar.normalized_attendance_record_id
INNER JOIN raw_attendance_fact raw
    ON raw.raw_attendance_fact_id = nar.raw_attendance_fact_id
SET
    nar.interval_start = IF(
        nar.interval_start IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, nar.interval_start)),
    nar.interval_end = IF(
        nar.interval_end IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, nar.interval_end)),
    nar.point_instant = IF(
        nar.point_instant IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, nar.point_instant)),
    raw.interval_start = IF(
        raw.interval_start IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, raw.interval_start)),
    raw.interval_end = IF(
        raw.interval_end IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, raw.interval_end)),
    raw.source_instant = IF(
        raw.source_instant IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, raw.source_instant)),
    oa.first_submitted_at = IF(
        oa.first_submitted_at IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, oa.first_submitted_at)),
    oa.approved_at = IF(
        oa.approved_at IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, oa.approved_at)),
    oa.modified_at = IF(
        oa.modified_at IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, oa.modified_at)),
    oa.revoked_at = IF(
        oa.revoked_at IS NULL,
        NULL,
        TIMESTAMPADD(HOUR, -8, oa.revoked_at)),
    oa.source_version = CONCAT(oa.source_version, ':utc-minus-8'),
    raw.source_version = CONCAT(raw.source_version, ':utc-minus-8')
WHERE oa.source_version NOT LIKE '%:Asia/Shanghai'
  AND oa.source_version NOT LIKE '%:utc-minus-8'
  AND CHAR_LENGTH(oa.source_version) <= 112
  AND CHAR_LENGTH(raw.source_version) <= 112;
