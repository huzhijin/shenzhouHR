#!/usr/bin/env python3
"""2026-08 OA 漏数/填错人。默认预览。APPLY=1 只改李谭 8/17 那条 1.5h 挂到马锦涵。

  export MYSQL_PWD='...'
  python3 /root/fix-2026-08-oa-gaps.py
  APPLY=1 python3 /root/fix-2026-08-oa-gaps.py

不回拨 OA 水位。改挂后按人重算李谭、马锦涵。
"""
from __future__ import annotations

import os
import subprocess
import sys

HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
APPLY = os.environ.get("APPLY", "0") == "1"
COMPANY_ID = "41000000-0000-0000-0000-000000000003"


def mysql_bin() -> str:
    path = "/www/server/mysql/bin/mysql"
    return path if os.access(path, os.X_OK) else "mysql"


def run(sql: str, table: bool = True) -> str:
    argv = [
        mysql_bin(),
        "-h127.0.0.1",
        "-P3306",
        "-uroot",
        "--default-character-set=utf8mb4",
        HR_DB,
    ]
    argv.append("--table" if table else "-N")
    completed = subprocess.run(
        argv, input=sql, text=True, capture_output=True, env=os.environ.copy()
    )
    sys.stdout.write(completed.stdout)
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"mysql failed rc={completed.returncode}")
    return completed.stdout


def show(title: str, sql: str) -> None:
    print(f"\n==== {title} ====")
    text = run("SET NAMES utf8mb4;\n" + sql).rstrip()
    print(text if text else "(无行)")


def main() -> int:
    if not os.environ.get("MYSQL_PWD"):
        raise SystemExit("先 export MYSQL_PWD")
    print("2026-08 OA 漏数预览。APPLY=%s" % int(APPLY))

    show(
        "李谭 8/17 加班单（1.5h 应改挂马锦涵）",
        """
SELECT employee_version.employee_number AS 工号,
       employee_version.display_name AS 姓名,
       oa.document_type AS 单据,
       oa.source_status AS 状态,
       matched.match_status,
       CONVERT_TZ(n.interval_start, '+00:00', '+08:00') AS 开始上海,
       CONVERT_TZ(n.interval_end, '+00:00', '+08:00') AS 结束上海,
       TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end) AS 分钟,
       oa.source_business_key,
       matched.employee_match_decision_id
FROM oa_attendance_document oa
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id = n.normalized_attendance_record_id
JOIN employee_version employee_version
  ON employee_version.employee_id = matched.employee_id
 AND employee_version.status = 'ACTIVE'
 AND employee_version.effective_to IS NULL
WHERE oa.document_type = 'OVERTIME'
  AND (
        (CONVERT_TZ(n.interval_start, '+00:00', '+08:00') >= '2026-08-17 18:29:00'
         AND CONVERT_TZ(n.interval_start, '+00:00', '+08:00') < '2026-08-17 18:31:00')
        OR (n.interval_start >= '2026-08-17 18:29:00'
            AND n.interval_start < '2026-08-17 18:31:00')
      )
  AND employee_version.employee_number IN ('SZST0263', 'SZST0660')
ORDER BY 开始上海, 分钟;
""",
    )

    show(
        "郭松 8/24 加班单",
        """
SELECT employee_version.employee_number AS 工号,
       employee_version.display_name AS 姓名,
       CONVERT_TZ(n.interval_start, '+00:00', '+08:00') AS 开始上海,
       CONVERT_TZ(n.interval_end, '+00:00', '+08:00') AS 结束上海,
       TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end) AS 分钟,
       oa.source_status
FROM oa_attendance_document oa
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id = n.normalized_attendance_record_id
JOIN employee_version employee_version
  ON employee_version.employee_id = matched.employee_id
 AND employee_version.status = 'ACTIVE'
 AND employee_version.effective_to IS NULL
WHERE oa.document_type = 'OVERTIME'
  AND employee_version.employee_number = 'SZST0225'
  AND (
        DATE(CONVERT_TZ(n.interval_start, '+00:00', '+08:00')) = '2026-08-24'
        OR DATE(n.interval_start) = '2026-08-24'
      )
ORDER BY 开始上海;
""",
    )

    show(
        "吕加军工号与 8 月加班单",
        """
SELECT employee_version.employee_number AS 工号,
       employee_version.display_name AS 姓名,
       employee_version.effective_to AS 版本止,
       COUNT(oa.oa_attendance_document_id) AS 八月加班单
FROM employee_version employee_version
JOIN employee employee
  ON employee.employee_id = employee_version.employee_id
LEFT JOIN employee_match_decision matched
  ON matched.employee_id = employee.employee_id
 AND matched.match_status = 'MATCHED'
LEFT JOIN oa_attendance_document oa
  ON oa.normalized_attendance_record_id = matched.normalized_attendance_record_id
 AND oa.document_type = 'OVERTIME'
LEFT JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
 AND (
        (CONVERT_TZ(n.interval_start, '+00:00', '+08:00') >= '2026-08-01'
         AND CONVERT_TZ(n.interval_start, '+00:00', '+08:00') < '2026-09-01')
        OR (n.interval_start >= '2026-08-01' AND n.interval_start < '2026-09-01')
     )
WHERE employee.company_id = '41000000-0000-0000-0000-000000000003'
  AND (
        employee_version.employee_number IN ('SZT0687', 'SZST0687')
        OR employee_version.display_name = '吕加军'
      )
GROUP BY employee_version.employee_number, employee_version.display_name,
         employee_version.effective_to;
""",
    )

    show(
        "陈家辉 8 月加班单 vs 日事实",
        """
SELECT DATE(CONVERT_TZ(n.interval_start, '+00:00', '+08:00')) AS 日期,
       TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end) AS 单据分钟,
       oa.source_status,
       matched.match_status,
       fact.recognized_overtime_minutes AS 日事实加班分
FROM employee_version version
JOIN employee employee ON employee.employee_id = version.employee_id
LEFT JOIN employee_match_decision matched
  ON matched.employee_id = employee.employee_id
 AND matched.match_status = 'MATCHED'
LEFT JOIN oa_attendance_document oa
  ON oa.normalized_attendance_record_id = matched.normalized_attendance_record_id
 AND oa.document_type = 'OVERTIME'
LEFT JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
LEFT JOIN attendance_report_daily_fact fact
  ON fact.employee_id = employee.employee_id
 AND fact.business_date = DATE(CONVERT_TZ(n.interval_start, '+00:00', '+08:00'))
WHERE version.employee_number = 'SZST0718'
  AND version.status = 'ACTIVE'
  AND version.effective_to IS NULL
  AND n.interval_start IS NOT NULL
ORDER BY 日期;
""",
    )

    show(
        "红名是否有 8 月日事实",
        """
SELECT version.employee_number AS 工号,
       version.display_name AS 姓名,
       assignment.effective_from AS 任职起,
       assignment.effective_to AS 任职止,
       COUNT(DISTINCT fact.business_date) AS 八月有事实天数
FROM employee_version version
JOIN employee employee ON employee.employee_id = version.employee_id
LEFT JOIN employment_assignment assignment
  ON assignment.employee_id = employee.employee_id
 AND assignment.version_valid_to IS NULL
LEFT JOIN attendance_report_daily_fact fact
  ON fact.employee_id = employee.employee_id
 AND fact.business_date >= '2026-08-01'
 AND fact.business_date < '2026-09-01'
WHERE employee.company_id = '41000000-0000-0000-0000-000000000003'
  AND version.status = 'ACTIVE'
  AND version.effective_to IS NULL
  AND version.employee_number IN (
        'SZSTSX95','SZST0674','SZSTSX91','SZST0670','SZST0456',
        'SZST0645','SZST0721','SZST0722','SZSZ0003')
GROUP BY version.employee_number, version.display_name,
         assignment.effective_from, assignment.effective_to
ORDER BY version.employee_number;
""",
    )

    if not APPLY:
        print("\n预览结束。马锦涵已有自己的 1.5h，不能把李谭那条改挂过去。")
        print("作废李谭 OVERTIME:-5725798966656284587（90 分钟）后：")
        print("  APPLY=1 python3 /root/fix-2026-08-oa-gaps.py")
        return 0

    print("\n==== APPLY 作废李谭错填的 18:30-20:00 1.5h（马锦涵已有自己的单） ====")
    run(
        """
SET NAMES utf8mb4;
START TRANSACTION;
UPDATE oa_attendance_document oa
SET oa.source_status = 'REVOKED'
WHERE oa.source_business_key = 'OVERTIME:-5725798966656284587'
  AND oa.document_type = 'OVERTIME'
  AND oa.source_status IN ('APPROVED', 'UNKNOWN', 'MODIFIED', 'SUPPLEMENTED');
COMMIT;
""",
        table=False,
    )
    show(
        "改挂后李谭/马锦涵 8/17",
        """
SELECT employee_version.employee_number AS 工号,
       employee_version.display_name AS 姓名,
       CONVERT_TZ(n.interval_start, '+00:00', '+08:00') AS 开始上海,
       CONVERT_TZ(n.interval_end, '+00:00', '+08:00') AS 结束上海,
       TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end) AS 分钟,
       matched.match_reason
FROM oa_attendance_document oa
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id = n.normalized_attendance_record_id
JOIN employee_version employee_version
  ON employee_version.employee_id = matched.employee_id
 AND employee_version.status = 'ACTIVE'
 AND employee_version.effective_to IS NULL
WHERE oa.document_type = 'OVERTIME'
  AND employee_version.employee_number IN ('SZST0263', 'SZST0660')
  AND (
        DATE(CONVERT_TZ(n.interval_start, '+00:00', '+08:00')) = '2026-08-17'
        OR DATE(n.interval_start) = '2026-08-17'
      )
ORDER BY 工号, 开始上海;
""",
    )
    print("\nAPPLY 完成。李谭 8/17 只留 2.5h，马锦涵自己的 1.5h 不动。")
    print("请按人重算李谭 SZST0263 的 2026-08。不要回拨 OA 水位。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
