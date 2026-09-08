-- V57 allowed negative time_account.balance_hours for overused leave.
-- Report pins still stored opening_hours as UNSIGNED, so a company with a
-- negative opening cannot publish a month. Align the report fact column.

ALTER TABLE attendance_report_time_account_fact
    MODIFY opening_hours DECIMAL(16,2) NOT NULL;
