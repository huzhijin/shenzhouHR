-- Read-only cutover checks for V41/V42. Every anomaly result set must be empty.
-- Run against the target schema before Flyway applies V41.

-- 1. org_member.code maps to employee.employee_number without company input,
-- so the employee number must be globally unique, not merely company-unique.
SELECT employee_number, COUNT(*) AS employee_count
FROM employee
GROUP BY employee_number
HAVING COUNT(*) <> 1;

-- 2. Existing account balances must reconcile to immutable ledger entries.
SELECT
    account.time_account_id,
    account.employee_id,
    account.account_type,
    account.account_year,
    account.balance_hours,
    COALESCE(SUM(entry.amount_hours), 0.00) AS ledger_balance_hours
FROM time_account account
LEFT JOIN time_account_ledger_entry entry
  ON entry.time_account_id = account.time_account_id
WHERE account.account_type IN ('ANNUAL_LEAVE', 'TIME_OFF')
GROUP BY
    account.time_account_id,
    account.employee_id,
    account.account_type,
    account.account_year,
    account.balance_hours
HAVING account.balance_hours <> COALESCE(SUM(entry.amount_hours), 0.00);

-- 3. One account identity/year/type may not be duplicated.
SELECT
    employee_id,
    employment_period_id,
    account_type,
    account_year,
    COUNT(*) AS account_count
FROM time_account
WHERE account_type IN ('ANNUAL_LEAVE', 'TIME_OFF')
GROUP BY employee_id, employment_period_id, account_type, account_year
HAVING COUNT(*) <> 1;

-- 4. Current employment versions which overlap on today's business date are
-- ambiguous and are rejected by the routines.
SELECT employment.employee_id, COUNT(*) AS effective_employment_count
FROM employment_assignment employment
WHERE employment.current_version_marker = 1
  AND employment.record_status = 'ACTIVE'
  AND employment.effective_from < DATE_ADD(CURRENT_DATE(), INTERVAL 1 DAY)
  AND (
      employment.effective_to IS NULL
      OR employment.effective_to > CAST(CURRENT_DATE() AS DATETIME)
  )
GROUP BY employment.employee_id
HAVING COUNT(*) <> 1;

-- 5. At most one applicable published TIME_OFF policy is allowed at each
-- scope. Zero-policy checks depend on each employee/company/date and must also
-- be exercised through the deployment smoke call.
SELECT
    policy.scope_type,
    policy.company_id,
    policy.effective_from,
    COUNT(*) AS policy_count
FROM leave_policy_revision policy
JOIN leave_type type
  ON type.leave_type_id = policy.leave_type_id
 AND type.leave_code = 'TIME_OFF'
WHERE policy.status = 'PUBLISHED'
GROUP BY policy.scope_type, policy.company_id, policy.effective_from
HAVING COUNT(*) > 1;

-- 6. HR sign-off inventory. This is intentionally not an anomaly-only query:
-- export and sign this list as the cutover opening balance.
SELECT
    employee.employee_number,
    account.account_type,
    account.account_year,
    account.balance_hours,
    account.employment_period_id,
    account.policy_version_id,
    account.updated_at
FROM time_account account
JOIN employee employee
  ON employee.employee_id = account.employee_id
WHERE account.account_type IN ('ANNUAL_LEAVE', 'TIME_OFF')
ORDER BY employee.employee_number, account.account_type, account.account_year;
