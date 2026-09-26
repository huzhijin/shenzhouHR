-- Seed the KQ (云考勤) opening cursor just before 2026-08-01 so the first
-- incremental job ingests August as the opening window, without touching
-- the existing CHECKIN watermark.
--
-- KQ is the whole 云考勤 stream: every device the API returns (科技园 1/2 号楼,
-- 总部 1/2 号门, and any other 13750C SN). There is no 科技园-only filter.
--
-- 12848301274 is the vendor next_id after the last July 31 KQ page.
-- Records before 2026-08-01 are also dropped in the client even if this
-- seed is skipped; seeding only avoids scanning May–July on first run.

UPDATE attendance_sync_watermark watermark
JOIN attendance_source source
  ON source.attendance_source_id = watermark.attendance_source_id
SET watermark.kq_committed_cursor = '12848301274',
    watermark.kq_committed_at = NULL,
    watermark.row_version = watermark.row_version + 1
WHERE source.source_type = 'DELI_CLOUD'
  AND source.status = 'ACTIVE'
  AND watermark.kq_committed_cursor IS NULL;

SELECT source.attendance_source_id,
       source.display_name,
       watermark.committed_cursor AS checkin_cursor,
       watermark.kq_committed_cursor AS kq_cursor
FROM attendance_source source
LEFT JOIN attendance_sync_watermark watermark
  ON watermark.attendance_source_id = source.attendance_source_id
WHERE source.source_type = 'DELI_CLOUD';
