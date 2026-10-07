-- Formal attendance-rate input. Historical projections default to zero paid
-- leave and remain readable; a refresh is required before they can claim the
-- V1 paid-leave attendance-rate formula.
ALTER TABLE attendance_report_daily_fact
    ADD COLUMN paid_leave_minutes INT UNSIGNED NOT NULL DEFAULT 0
        AFTER leave_or_time_off_minutes,
    ADD CONSTRAINT ck_att_report_daily_paid_leave
        CHECK (paid_leave_minutes <= leave_or_time_off_minutes);
