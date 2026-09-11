-- Read-only comparison of the retired minute-based attendance rate and the
-- OpenSpec day-based attendance rate.
--
-- Optional inputs (set before sourcing this script):
--   SET @attendance_rate_projection_id = '<published projection id>';
--   SET @attendance_rate_company_id = '<company id>';
--
-- When no projection is supplied, the latest published projection is used.
-- A company id is still mandatory: company isolation must never be inferred
-- from an organization or employee identifier.

SET @attendance_rate_projection_id := COALESCE(
    NULLIF(@attendance_rate_projection_id, ''),
    (
        SELECT projection.attendance_report_projection_id
        FROM attendance_report_projection projection
        WHERE projection.status = 'PUBLISHED'
          AND projection.legal_entity_id = @attendance_rate_company_id
        ORDER BY
            projection.published_at DESC,
            projection.attendance_report_projection_id DESC
        LIMIT 1
    )
);

-- Input guard. A non-OK row means the comparison below is not valid evidence.
SELECT
    CASE
        WHEN @attendance_rate_company_id IS NULL
            OR @attendance_rate_company_id = ''
        THEN 'ERROR_COMPANY_ID_REQUIRED'
        WHEN @attendance_rate_projection_id IS NULL
            OR @attendance_rate_projection_id = ''
        THEN 'ERROR_PUBLISHED_PROJECTION_NOT_FOUND'
        WHEN NOT EXISTS (
            SELECT 1
            FROM attendance_report_projection projection
            WHERE projection.attendance_report_projection_id
                    = @attendance_rate_projection_id
              AND projection.legal_entity_id = @attendance_rate_company_id
              AND projection.status = 'PUBLISHED'
        )
        THEN 'ERROR_PROJECTION_SCOPE_OR_STATUS'
        ELSE 'OK'
    END AS validation_status,
    @attendance_rate_company_id AS company_id,
    @attendance_rate_projection_id AS projection_id;

-- Employee/department sample comparison. The new rate deliberately excludes
-- an employee from a department row when that employee has zero scheduled
-- attendance days in that department. Rest-day overtime therefore cannot
-- inflate the day-based numerator.
WITH employee_department_totals AS (
    SELECT
        fact.legal_entity_id,
        fact.organization_id,
        fact.employee_id,
        SUM(fact.scheduled_minutes) AS scheduled_minutes,
        SUM(fact.confirmed_scheduled_work_minutes)
            AS confirmed_scheduled_work_minutes,
        SUM(fact.scheduled_attendance_days) AS scheduled_attendance_days,
        SUM(CASE
            WHEN fact.scheduled_attendance_days > 0
            THEN fact.actual_attendance_days
            ELSE 0
        END) AS actual_attendance_days
    FROM attendance_report_daily_fact fact
    WHERE fact.attendance_report_projection_id
            = @attendance_rate_projection_id
      AND fact.legal_entity_id = @attendance_rate_company_id
    GROUP BY
        fact.legal_entity_id,
        fact.organization_id,
        fact.employee_id
), comparable_rows AS (
    SELECT
        totals.*,
        ROUND(
            totals.confirmed_scheduled_work_minutes
                / NULLIF(totals.scheduled_minutes, 0) * 100,
            2
        ) AS retired_minute_rate,
        ROUND(
            totals.actual_attendance_days
                / NULLIF(totals.scheduled_attendance_days, 0) * 100,
            2
        ) AS openspec_day_rate
    FROM employee_department_totals totals
    WHERE totals.scheduled_attendance_days > 0
)
SELECT
    legal_entity_id AS company_id,
    organization_id,
    employee_id,
    confirmed_scheduled_work_minutes,
    scheduled_minutes,
    actual_attendance_days,
    scheduled_attendance_days,
    retired_minute_rate,
    openspec_day_rate,
    ROUND(openspec_day_rate - retired_minute_rate, 2)
        AS percentage_point_delta
FROM comparable_rows
ORDER BY
    ABS(openspec_day_rate - retired_minute_rate) DESC,
    organization_id,
    employee_id;

-- Projection-level control totals. These counts make a zero-denominator or
-- out-of-scope population visible instead of silently converting it to 0%.
SELECT
    COUNT(DISTINCT fact.employee_id) AS employee_count,
    COUNT(DISTINCT CASE
        WHEN fact.scheduled_attendance_days > 0 THEN fact.employee_id
        ELSE NULL
    END) AS employees_with_scheduled_days,
    SUM(fact.actual_attendance_days) AS raw_actual_attendance_days,
    SUM(CASE
        WHEN fact.scheduled_attendance_days > 0
        THEN fact.actual_attendance_days
        ELSE 0
    END) AS rate_numerator_attendance_days,
    SUM(fact.scheduled_attendance_days) AS scheduled_attendance_days,
    ROUND(
        SUM(CASE
            WHEN fact.scheduled_attendance_days > 0
            THEN fact.actual_attendance_days
            ELSE 0
        END) / NULLIF(SUM(fact.scheduled_attendance_days), 0) * 100,
        2
    ) AS openspec_company_day_rate
FROM attendance_report_daily_fact fact
WHERE fact.attendance_report_projection_id = @attendance_rate_projection_id
  AND fact.legal_entity_id = @attendance_rate_company_id;
