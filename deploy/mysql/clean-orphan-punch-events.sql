-- Delete August (or chosen month) PUNCH_POINT events that have no evidence_link.
-- Reload/replay can delete raw facts and links while leaving effective events;
-- calculation still treats those orphans as punches.
-- Scope is the whole company-month, not a named-people list.
-- Does not touch leave balances or events that still have a link.
--
-- Preview (default):
--   mysql --default-character-set=utf8mb4 shenzhou_hr \
--     < deploy/mysql/clean-orphan-punch-events.sql
-- Apply:
--   mysql --default-character-set=utf8mb4 shenzhou_hr \
--     -e "SET @apply := 1; SOURCE deploy/mysql/clean-orphan-punch-events.sql;"
--
-- Instant bounds are UTC. Shanghai 2026-08 = [2026-07-31 16:00, 2026-08-31 16:00).

SET NAMES utf8mb4;
SET time_zone = '+00:00';
SET @apply := IFNULL(@apply, 0);
SET @company_id := IFNULL(
    @company_id,
    '41000000-0000-0000-0000-000000000003');
SET @from_utc := IFNULL(@from_utc, '2026-07-31 16:00:00');
SET @to_utc := IFNULL(@to_utc, '2026-08-31 16:00:00');

DROP TEMPORARY TABLE IF EXISTS tmp_orphan_punch_event;
CREATE TEMPORARY TABLE tmp_orphan_punch_event (
    effective_attendance_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (effective_attendance_event_id)
) ENGINE=InnoDB;

INSERT INTO tmp_orphan_punch_event
SELECT event.effective_attendance_event_id
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

SELECT 'preview_orphan_count' AS section,
       COUNT(*) AS orphan_events,
       MIN(event.point_instant) AS earliest_utc,
       MAX(event.point_instant) AS latest_utc
FROM tmp_orphan_punch_event tmp
JOIN effective_attendance_event event
  ON event.effective_attendance_event_id = tmp.effective_attendance_event_id;

SELECT 'preview_by_employee' AS section,
       version.employee_number,
       version.display_name,
       COUNT(*) AS orphan_events
FROM tmp_orphan_punch_event tmp
JOIN effective_attendance_event event
  ON event.effective_attendance_event_id = tmp.effective_attendance_event_id
JOIN employee_current_projection projection
  ON projection.employee_id = event.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
GROUP BY version.employee_number, version.display_name
ORDER BY orphan_events DESC, version.employee_number
LIMIT 50;

UPDATE punch_import_row rowx
JOIN tmp_orphan_punch_event ev
  ON ev.effective_attendance_event_id = rowx.effective_attendance_event_id
SET rowx.effective_attendance_event_id = NULL,
    rowx.published_raw_fact_id = NULL
WHERE @apply = 1;

UPDATE source_reversal_record reversal
JOIN tmp_orphan_punch_event ev
  ON ev.effective_attendance_event_id = reversal.target_effective_event_id
SET reversal.target_effective_event_id = NULL
WHERE @apply = 1;

DELETE member
FROM duplicate_review_member member
JOIN tmp_orphan_punch_event ev
  ON ev.effective_attendance_event_id = member.prior_effective_event_id
WHERE @apply = 1;

DELETE intent
FROM attendance_recalculation_intent intent
JOIN tmp_orphan_punch_event ev
  ON ev.effective_attendance_event_id = intent.effective_attendance_event_id
WHERE @apply = 1;

DELETE slice
FROM evidence_interval_slice slice
JOIN tmp_orphan_punch_event ev
  ON ev.effective_attendance_event_id = slice.winner_event_id
WHERE @apply = 1;

DELETE fact
FROM effective_event_lifecycle_fact fact
JOIN tmp_orphan_punch_event ev
  ON ev.effective_attendance_event_id = fact.related_event_id
WHERE @apply = 1;

DELETE fact
FROM effective_event_lifecycle_fact fact
JOIN tmp_orphan_punch_event ev
  ON ev.effective_attendance_event_id = fact.effective_attendance_event_id
WHERE @apply = 1;

DELETE event
FROM effective_attendance_event event
JOIN tmp_orphan_punch_event ev
  ON ev.effective_attendance_event_id = event.effective_attendance_event_id
WHERE @apply = 1;

SELECT 'remaining_orphans' AS section,
       COUNT(*) AS remaining
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

SELECT 'apply_flag' AS section, @apply AS apply_flag;
