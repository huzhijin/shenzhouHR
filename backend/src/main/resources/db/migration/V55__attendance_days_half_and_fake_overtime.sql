-- Half-day attendance rate (0 / 0.5 / 1) and fake-overtime exception type.

ALTER TABLE attendance_report_daily_fact
    DROP CHECK ck_att_report_daily_actual_days;

ALTER TABLE attendance_report_daily_fact
    MODIFY COLUMN actual_attendance_days DECIMAL(3, 1) NOT NULL DEFAULT 0
        COMMENT '实际出勤天数：0 / 0.5 / 1';

ALTER TABLE attendance_report_daily_fact
    ADD CONSTRAINT ck_att_report_daily_actual_days
        CHECK (actual_attendance_days IN (0, 0.5, 1.0));
