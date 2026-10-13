-- NULL means a legacy projection has no per-day allocation. Do not infer zero
-- or assign a cross-month leave form's full recognised total to its starting month.
ALTER TABLE attendance_report_oa_fact
    ADD COLUMN recognized_minutes_by_date_json JSON NULL;
