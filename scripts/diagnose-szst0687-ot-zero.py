#!/usr/bin/env python3
"""吕加军 SZST0687 加班单已激活但月度工时为 0：查工号层和班次是否把加班吃掉。

  export MYSQL_PWD='...'
  python3 /root/diagnose-szst0687-ot-zero.py
"""
from __future__ import annotations

import os
import subprocess
import sys

HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
EMPNO = os.environ.get("EMPNO", "SZST0687")
COMPANY_ID = "41000000-0000-0000-0000-000000000003"


def mysql_bin() -> str:
    path = "/www/server/mysql/bin/mysql"
    return path if os.access(path, os.X_OK) else "mysql"


def run(sql: str) -> None:
    argv = [
        mysql_bin(), "-h127.0.0.1", "-P3306", "-uroot",
        "--default-character-set=utf8mb4", "--table", HR_DB,
    ]
    completed = subprocess.run(
        argv, input="SET NAMES utf8mb4;\n" + sql,
        text=True, capture_output=True, env=os.environ.copy(),
    )
    sys.stdout.write(completed.stdout)
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"mysql failed rc={completed.returncode}")


def show(title: str, sql: str) -> None:
    print(f"\n==== {title} ====")
    run(sql)


def main() -> int:
    if not os.environ.get("MYSQL_PWD"):
        raise SystemExit("先 export MYSQL_PWD")
    show(
        "employee 表工号 vs version 工号",
        f"""
SELECT e.employee_id,
       e.employee_number AS emp表工号,
       v.employee_number AS version工号,
       v.display_name,
       v.effective_from,
       v.effective_to
FROM employee e
JOIN employee_version v
  ON v.employee_id = e.employee_id
 AND v.status = 'ACTIVE'
WHERE e.company_id = '{COMPANY_ID}'
  AND (e.employee_number IN ('{EMPNO}','SZT0687')
       OR v.employee_number IN ('{EMPNO}','SZT0687'))
ORDER BY v.effective_from;
""",
    )
    show(
        "核算读取加班单时用的 employee.employee_number",
        f"""
SELECT DISTINCT e.employee_number AS 核算用工号,
       v.employee_number AS 花名册工号,
       COUNT(*) AS 张数
FROM oa_attendance_document oa
JOIN employee_match_decision m
  ON m.normalized_attendance_record_id = oa.normalized_attendance_record_id
 AND m.match_status = 'MATCHED'
JOIN employee e ON e.employee_id = m.employee_id
JOIN employee_version v
  ON v.employee_id = m.employee_id AND v.status = 'ACTIVE'
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
WHERE oa.document_type = 'OVERTIME'
  AND n.interval_start >= '2026-07-31 16:00:00'
  AND n.interval_start <  '2026-08-31 16:00:00'
  AND (e.employee_number IN ('{EMPNO}','SZT0687')
       OR v.employee_number IN ('{EMPNO}','SZT0687'))
GROUP BY e.employee_number, v.employee_number;
""",
    )
    show(
        "最新钉住里吕加军每日加班分钟",
        f"""
SELECT fact.business_date,
       fact.day_type,
       fact.recognized_overtime_minutes AS 认定加班,
       fact.paid_overtime_minutes AS 加班费分钟,
       fact.scheduled_minutes AS 应出勤
FROM attendance_report_daily_fact fact
JOIN attendance_report_projection pin
  ON pin.attendance_report_projection_id = fact.attendance_report_projection_id
JOIN employee_version v
  ON v.employee_version_id = fact.employee_version_id
WHERE pin.company_id = '{COMPANY_ID}'
  AND pin.period_start = '2026-08-01'
  AND pin.period_end_exclusive = '2026-09-01'
  AND pin.status = 'PUBLISHED'
  AND v.employee_number = '{EMPNO}'
  AND pin.published_at = (
        SELECT MAX(p2.published_at)
        FROM attendance_report_projection p2
        WHERE p2.company_id = pin.company_id
          AND p2.period_start = pin.period_start
          AND p2.period_end_exclusive = pin.period_end_exclusive
          AND p2.status = 'PUBLISHED'
  )
ORDER BY fact.business_date;
""",
    )
    show(
        "任职与考勤组",
        f"""
SELECT v.employee_number,
       v.display_name,
       a.organization_id,
       a.effective_from,
       a.effective_to
FROM employment_assignment a
JOIN employee e ON e.employee_id = a.employee_id
JOIN employee_version v
  ON v.employee_id = e.employee_id AND v.status = 'ACTIVE'
WHERE e.company_id = '{COMPANY_ID}'
  AND v.employee_number IN ('{EMPNO}','SZT0687')
  AND a.version_valid_to IS NULL
ORDER BY a.effective_from;
""",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
