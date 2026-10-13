-- 现网 8 月应出勤 160：缺的是 2026-08-14（周五）日事实，不是 8/31。
-- 张泽 SZST0645 保持 160（8/28 离职）。
-- 代码已对「工作日无发布班次」回退扬州/大连模板；部署后按人重算即可。
-- 本脚本只预览。

SELECT employee.employee_number,
       version.display_name,
       fact.business_date,
       fact.day_type,
       fact.scheduled_minutes
FROM attendance_report_daily_fact fact
JOIN employee ON employee.employee_id = fact.employee_id
JOIN employee_version version
  ON version.employee_version_id = fact.employee_version_id
WHERE fact.company_id = '41000000-0000-0000-0000-000000000003'
  AND fact.business_date IN ('2026-08-14', '2026-08-24', '2026-08-31')
  AND employee.employee_number IN (
        'SZST0020', 'SZST0067', 'SZST0075', 'SZST0227',
        'SZST0252', 'SZST0311', 'SZST0401', 'SZST0548',
        'SZST0554', 'SZST0599', 'SZST0645', 'SZST0671',
        'SZSZ0000', 'SZWX0003')
ORDER BY employee.employee_number, fact.business_date;

-- 这些人 8 月应出勤分钟合计（160 = 9600 分钟）
SELECT employee.employee_number,
       MAX(version.display_name) AS name,
       SUM(fact.scheduled_minutes) / 60 AS scheduled_hours
FROM attendance_report_daily_fact fact
JOIN employee ON employee.employee_id = fact.employee_id
JOIN employee_version version
  ON version.employee_version_id = fact.employee_version_id
WHERE fact.company_id = '41000000-0000-0000-0000-000000000003'
  AND fact.business_date >= '2026-08-01'
  AND fact.business_date < '2026-09-01'
  AND employee.employee_number IN (
        'SZST0020', 'SZST0067', 'SZST0075', 'SZST0227',
        'SZST0252', 'SZST0311', 'SZST0401', 'SZST0548',
        'SZST0554', 'SZST0599', 'SZST0645', 'SZST0671',
        'SZSZ0000', 'SZWX0003')
GROUP BY employee.employee_number
ORDER BY employee.employee_number;
