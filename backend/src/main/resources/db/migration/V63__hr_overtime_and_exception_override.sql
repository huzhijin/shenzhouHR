-- HR can override a day's overtime hours and clear selected exceptions.

ALTER TABLE attendance_hr_punch_adjustment
    ADD COLUMN overtime_minutes_override INT NULL,
    ADD COLUMN cleared_exception_types VARCHAR(128) NULL;

ALTER TABLE attendance_hr_punch_adjustment
    DROP CHECK ck_hr_punch_adj_clock;

ALTER TABLE attendance_hr_punch_adjustment
    ADD CONSTRAINT ck_hr_punch_adj_payload CHECK (
        on_duty_at IS NOT NULL
        OR off_duty_at IS NOT NULL
        OR overtime_minutes_override IS NOT NULL
        OR (cleared_exception_types IS NOT NULL
            AND cleared_exception_types <> '')
    );
