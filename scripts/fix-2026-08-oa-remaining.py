#!/usr/bin/env python3
"""剩余数据：核对李谭作废、作废郭松 8/24 多出来的 2h、查吕加军 context、陈家辉未入库日期、徐利民/黄兆隆版本。

  export MYSQL_PWD='...'
  python3 /root/fix-2026-08-oa-remaining.py
  APPLY=1 python3 /root/fix-2026-08-oa-remaining.py

APPLY 只作废郭松 8/24 18:30-20:30（120 分钟）。李谭若仍是 APPROVED 会再作废一次。
不重算、不回拨 OA 水位。
"""
from __future__ import annotations

import os
import subprocess
import sys

HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
APPLY = os.environ.get("APPLY", "0") == "1"


def mysql_bin() -> str:
    path = "/www/server/mysql/bin/mysql"
    return path if os.access(path, os.X_OK) else "mysql"


def run(sql: str) -> str:
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
    return completed.stdout


def show(title: str, sql: str) -> None:
    print(f"\n==== {title} ====")
    text = run(sql).rstrip()
    print(text if text else "(无行)")


def main() -> int:
    if not os.environ.get("MYSQL_PWD"):
        raise SystemExit("先 export MYSQL_PWD")
    print("剩余 OA 数据。APPLY=%s" % int(APPLY))

    show(
        "李谭错单是否已 REVOKED",
        """
SELECT oa.source_business_key,
       oa.source_status,
       COUNT(*) AS 行数
FROM oa_attendance_document oa
WHERE oa.source_business_key = 'OVERTIME:-5725798966656284587'
GROUP BY oa.source_business_key, oa.source_status;
""",
    )

    show(
        "郭松 8/24 单据主键",
        """
SELECT oa.source_business_key,
       oa.source_status,
       TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end) AS 分钟,
       CONVERT_TZ(n.interval_start, '+00:00', '+08:00') AS 开始上海,
       CONVERT_TZ(n.interval_end, '+00:00', '+08:00') AS 结束上海
FROM oa_attendance_document oa
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id = n.normalized_attendance_record_id
JOIN employee_version v
  ON v.employee_id = matched.employee_id
 AND v.status = 'ACTIVE'
 AND v.effective_to IS NULL
WHERE v.employee_number = 'SZST0225'
  AND oa.document_type = 'OVERTIME'
  AND (
        DATE(CONVERT_TZ(n.interval_start, '+00:00', '+08:00')) = '2026-08-24'
        OR DATE(n.interval_start) = '2026-08-24'
      )
ORDER BY 分钟, oa.source_status;
""",
    )

    show(
        "吕加军加班单 context（没 ACTIVATED 或没 overtime_type 则核算为 0）",
        """
SELECT oa.source_status,
       ctx.activation_decision,
       ctx.overtime_type,
       COUNT(DISTINCT oa.source_business_key) AS 单据主键数
FROM employee_version v
JOIN employee_match_decision matched
  ON matched.employee_id = v.employee_id
 AND matched.match_status = 'MATCHED'
JOIN oa_attendance_document oa
  ON oa.normalized_attendance_record_id = matched.normalized_attendance_record_id
 AND oa.document_type = 'OVERTIME'
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
LEFT JOIN oa_attendance_document_context ctx
  ON ctx.oa_attendance_document_id = oa.oa_attendance_document_id
WHERE v.employee_number = 'SZST0687'
  AND v.status = 'ACTIVE'
  AND v.effective_to IS NULL
  AND (
        (CONVERT_TZ(n.interval_start, '+00:00', '+08:00') >= '2026-08-01'
         AND CONVERT_TZ(n.interval_start, '+00:00', '+08:00') < '2026-09-01')
        OR (n.interval_start >= '2026-08-01' AND n.interval_start < '2026-09-01')
      )
GROUP BY oa.source_status, ctx.activation_decision, ctx.overtime_type;
""",
    )

    show(
        "陈家辉 8 月加班单去重日期",
        """
SELECT DATE(CONVERT_TZ(n.interval_start, '+00:00', '+08:00')) AS 日期,
       MIN(TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end)) AS 最短分钟,
       MAX(TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end)) AS 最长分钟,
       GROUP_CONCAT(DISTINCT oa.source_status) AS 状态
FROM employee_version v
JOIN employee_match_decision matched
  ON matched.employee_id = v.employee_id
 AND matched.match_status = 'MATCHED'
JOIN oa_attendance_document oa
  ON oa.normalized_attendance_record_id = matched.normalized_attendance_record_id
 AND oa.document_type = 'OVERTIME'
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
WHERE v.employee_number = 'SZST0718'
  AND v.status = 'ACTIVE'
  AND v.effective_to IS NULL
  AND n.interval_start IS NOT NULL
GROUP BY DATE(CONVERT_TZ(n.interval_start, '+00:00', '+08:00'))
ORDER BY 日期;
""",
    )

    show(
        "徐利民/黄兆隆/崔雨 员工版本",
        """
SELECT v.employee_number AS 工号,
       v.display_name AS 姓名,
       v.status AS 版本状态,
       v.effective_from AS 版本起,
       v.effective_to AS 版本止,
       a.effective_from AS 任职起,
       a.effective_to AS 任职止
FROM employee e
JOIN employee_version v ON v.employee_id = e.employee_id
LEFT JOIN employment_assignment a
  ON a.employee_id = e.employee_id
 AND a.version_valid_to IS NULL
WHERE e.company_id = '41000000-0000-0000-0000-000000000003'
  AND (
        v.employee_number IN ('SZST0674','SZST0670','SZST0662','SZST0721','SZST0456')
        OR v.display_name IN ('徐利民','黄兆隆','崔雨')
      )
ORDER BY v.employee_number, v.effective_from;
""",
    )

    if not APPLY:
        print("\n预览结束。确认郭松 8/24 的 120 分钟是多余单后：")
        print("  APPLY=1 python3 /root/fix-2026-08-oa-remaining.py")
        return 0

    print("\n==== APPLY 再作废李谭 90 分钟（若仍 APPROVED）+ 郭松 120 分钟 ====")
    argv = [
        mysql_bin(), "-h127.0.0.1", "-P3306", "-uroot",
        "--default-character-set=utf8mb4", "-N", HR_DB,
    ]
    sql = """
SET NAMES utf8mb4;
START TRANSACTION;
UPDATE oa_attendance_document oa
SET oa.source_status = 'REVOKED'
WHERE oa.source_business_key = 'OVERTIME:-5725798966656284587'
  AND oa.document_type = 'OVERTIME'
  AND oa.source_status IN ('APPROVED','UNKNOWN','MODIFIED','SUPPLEMENTED');

UPDATE oa_attendance_document oa
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id = n.normalized_attendance_record_id
JOIN employee_version v
  ON v.employee_id = matched.employee_id
 AND v.status = 'ACTIVE'
 AND v.effective_to IS NULL
SET oa.source_status = 'REVOKED'
WHERE v.employee_number = 'SZST0225'
  AND oa.document_type = 'OVERTIME'
  AND TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end) BETWEEN 119 AND 121
  AND (
        DATE(CONVERT_TZ(n.interval_start, '+00:00', '+08:00')) = '2026-08-24'
        OR DATE(n.interval_start) = '2026-08-24'
      )
  AND oa.source_status IN ('APPROVED','UNKNOWN','MODIFIED','SUPPLEMENTED');
COMMIT;
"""
    completed = subprocess.run(
        argv, input=sql, text=True, capture_output=True, env=os.environ.copy()
    )
    sys.stdout.write(completed.stdout)
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"mysql failed rc={completed.returncode}")

    show(
        "李谭错单状态",
        """
SELECT source_business_key, source_status, COUNT(*) AS 行数
FROM oa_attendance_document
WHERE source_business_key = 'OVERTIME:-5725798966656284587'
GROUP BY source_business_key, source_status;
""",
    )
    show(
        "郭松 8/24 状态",
        """
SELECT oa.source_status,
       TIMESTAMPDIFF(MINUTE, n.interval_start, n.interval_end) AS 分钟,
       COUNT(*) AS 行数
FROM oa_attendance_document oa
JOIN normalized_attendance_record n
  ON n.normalized_attendance_record_id = oa.normalized_attendance_record_id
JOIN employee_match_decision matched
  ON matched.normalized_attendance_record_id = n.normalized_attendance_record_id
JOIN employee_version v
  ON v.employee_id = matched.employee_id
 AND v.status = 'ACTIVE' AND v.effective_to IS NULL
WHERE v.employee_number = 'SZST0225'
  AND oa.document_type = 'OVERTIME'
  AND (DATE(CONVERT_TZ(n.interval_start,'+00:00','+08:00'))='2026-08-24'
       OR DATE(n.interval_start)='2026-08-24')
GROUP BY oa.source_status, 分钟
ORDER BY 分钟, oa.source_status;
""",
    )
    print("\nAPPLY 完成。先不要重算。把吕加军 context、陈家辉日期、徐利民版本结果发我。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
