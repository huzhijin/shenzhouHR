-- Per-row allocation rule for OA leave facts.
-- NULL: legacy per-form recognition (each form counted on its own work windows).
-- NET_WORK_V1: same-type leave of one person shares effective WORK time, so an
-- overlap is counted once across forms. Copy-forward keeps each row's value;
-- historical rows are not backfilled.
ALTER TABLE attendance_report_oa_fact
    ADD COLUMN allocation_semantics VARCHAR(32) NULL;
