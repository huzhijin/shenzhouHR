-- KQ (云考勤) uses a separate next_id space from CHECKIN (综合签到).
-- Keep both cursors on the same DELI_CLOUD source so scheduled sync
-- continues both streams without a second attendance_source row.
ALTER TABLE attendance_sync_watermark
    ADD COLUMN kq_committed_cursor VARCHAR(512) COLLATE utf8mb4_bin NULL
        AFTER committed_at,
    ADD COLUMN kq_committed_page_digest CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER kq_committed_cursor,
    ADD COLUMN kq_committed_at DATETIME(6) NULL
        AFTER kq_committed_page_digest;
