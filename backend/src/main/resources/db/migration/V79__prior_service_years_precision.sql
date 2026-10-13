-- V79: Add precise year tracking to prior_service_record for 365-day conversion
-- Enables accurate monthly tenure calculation and historical compatibility
ALTER TABLE prior_service_record
    ADD COLUMN amount_years DECIMAL(7,2) NULL COMMENT '原始年数输入(2位小数),新记录必填,历史NULL回退amount_days',
    ADD COLUMN conversion_basis VARCHAR(20) NULL COMMENT '换算依据: DAYS_PER_YEAR_365, DAYS_PER_YEAR_360, ACTUAL_DAYS, 历史NULL识别为LEGACY',
    ADD COLUMN conversion_version INT NULL COMMENT '换算规则版本号,当前为1';
-- Backfill excel-prior-20260930 records: mark as 360-day SET operations
-- Strict matching: exact request_id format, record_type, business_date, and reason
UPDATE prior_service_record
SET conversion_basis = 'DAYS_PER_YEAR_360',
    conversion_version = 1
WHERE conversion_basis IS NULL
  AND request_id = CONCAT('excel-prior-20260930:', employee_id)
  AND record_type IN ('OPENING_IMPORT', 'ADJUSTMENT')
  AND business_date = DATE '2026-07-31'
  AND reason = '2026加班调休及年假统计汇总表 公司外已证明工龄';
-- Create index for efficient replay queries
CREATE INDEX idx_prior_service_employee_occurred
    ON prior_service_record(employee_id, occurred_at, row_version);
