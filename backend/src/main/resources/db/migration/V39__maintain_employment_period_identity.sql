-- V39 closes the deployment window between the V38 one-time backfill and the
-- application change that transactionally creates each new stable employment
-- period identity before inserting its first assignment version.

-- Fail closed if an existing identity maps a stable period to a different
-- employee/company than its assignment history. The deliberate primary-key
-- collision makes Flyway stop instead of silently accepting corrupted lineage.
INSERT INTO employment_period_identity (
    employment_period_id,
    employee_id,
    company_id,
    created_at
)
SELECT
    assignment.employment_period_id,
    assignment.employee_id,
    employee.company_id,
    MIN(assignment.created_at)
FROM employment_assignment assignment
JOIN employee
  ON employee.employee_id = assignment.employee_id
JOIN employment_period_identity identity
  ON identity.employment_period_id = assignment.employment_period_id
WHERE identity.employee_id <> assignment.employee_id
   OR identity.company_id <> employee.company_id
GROUP BY
    assignment.employment_period_id,
    assignment.employee_id,
    employee.company_id;

-- Backfill periods created after V38 but before the transactionally maintained
-- write path was deployed. Multiple versions of one stable period collapse to
-- its earliest creation time; a period reused by two employees still fails on
-- the primary key and therefore cannot be hidden by IGNORE semantics.
INSERT INTO employment_period_identity (
    employment_period_id,
    employee_id,
    company_id,
    created_at
)
SELECT
    assignment.employment_period_id,
    assignment.employee_id,
    employee.company_id,
    MIN(assignment.created_at)
FROM employment_assignment assignment
JOIN employee
  ON employee.employee_id = assignment.employee_id
LEFT JOIN employment_period_identity identity
  ON identity.employment_period_id = assignment.employment_period_id
WHERE identity.employment_period_id IS NULL
GROUP BY
    assignment.employment_period_id,
    assignment.employee_id,
    employee.company_id;
