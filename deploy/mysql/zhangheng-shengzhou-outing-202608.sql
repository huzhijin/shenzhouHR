-- 上海昇州 张衡 SZSZ0003：补 2026-08-11、2026-08-26 外出单（08:30-18:00）。
-- 不补打卡。格子显示「外出」并涂外出色。须先有 day_types 列（V66）。
-- Prefer scripts/zhangheng-shengzhou-outing-202608.py (preview, APPLY=1).
-- 重算昇州 2026-08 后明细才变。

SET NAMES utf8mb4;
SET @company_id = '41000000-0000-0000-0000-000000000001';
SET @actor_id = 'SYSTEM';

SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       employee.employee_id,
       employee.company_id
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.company_id = @company_id
  AND employee.employee_number = 'SZSZ0003'
  AND version.display_name = '张衡';

INSERT INTO attendance_hr_punch_adjustment (
    adjustment_id,
    company_id,
    employee_id,
    business_date,
    on_duty_at,
    off_duty_at,
    reason,
    created_by,
    created_at,
    overtime_minutes_override,
    cleared_exception_types,
    day_types
)
SELECT UUID(),
       employee.company_id,
       employee.employee_id,
       dates.business_date,
       NULL,
       NULL,
       '人事补外出单 08:30-18:00，无打卡',
       @actor_id,
       CURRENT_TIMESTAMP(6),
       NULL,
       NULL,
       'OUTING'
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
JOIN (
    SELECT DATE '2026-08-11' AS business_date
    UNION ALL
    SELECT DATE '2026-08-26'
) dates
WHERE employee.company_id = @company_id
  AND employee.employee_number = 'SZSZ0003'
  AND version.display_name = '张衡'
  AND NOT EXISTS (
        SELECT 1
        FROM attendance_hr_punch_adjustment existing
        WHERE existing.employee_id = employee.employee_id
          AND existing.business_date = dates.business_date
          AND existing.reversed_at IS NULL
          AND existing.day_types LIKE '%OUTING%'
  );

SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       adjustment.business_date AS 日期,
       adjustment.day_types AS 类型,
       adjustment.reason AS 原因
FROM attendance_hr_punch_adjustment adjustment
JOIN employee employee
  ON employee.employee_id = adjustment.employee_id
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.employee_number = 'SZSZ0003'
  AND adjustment.business_date IN ('2026-08-11', '2026-08-26')
  AND adjustment.reversed_at IS NULL
ORDER BY adjustment.business_date;
