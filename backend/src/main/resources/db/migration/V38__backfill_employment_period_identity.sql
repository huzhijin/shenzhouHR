-- V38: Backfill employment_period_identity from employment_assignment.
-- This table drives the FK on time_account.  For any installation that has
-- employment_assignment rows already in place this migration makes them visible
-- to the time-account sub-system without any data loss.

INSERT IGNORE INTO employment_period_identity (
    employment_period_id,
    employee_id,
    company_id,
    created_at
)
SELECT
    ea.employment_period_id,
    ea.employee_id,
    e.company_id,
    ea.created_at
FROM employment_assignment ea
JOIN employee e ON e.employee_id = ea.employee_id;
