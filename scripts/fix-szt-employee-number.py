#!/usr/bin/env python3
"""employee 表仍是 SZT0687、花名册是 SZST0687 时，核算对不上加班单。
把 employee.employee_number 改成当前 ACTIVE version 的 SZST 工号。

  python3 /root/fix-szt-employee-number.py
  APPLY=1 python3 /root/fix-szt-employee-number.py

不回拨 OA 水位。改完整月重算 2026-08。
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


PREVIEW = f"""
SELECT e.employee_id,
       e.employee_number AS emp表,
       v.employee_number AS 花名册,
       v.display_name
FROM employee e
JOIN employee_version v
  ON v.employee_id = e.employee_id
 AND v.status = 'ACTIVE'
 AND v.effective_to IS NULL
WHERE e.company_id = '{COMPANY_ID}'
  AND e.employee_number LIKE 'SZT%'
  AND e.employee_number NOT LIKE 'SZST%'
  AND v.employee_number = CONCAT('SZST', SUBSTRING(e.employee_number, 4))
  AND SUBSTRING(e.employee_number, 4) REGEXP '^[0-9]+$'
ORDER BY v.employee_number;
"""

APPLY_SQL = f"""
UPDATE employee e
JOIN employee_version v
  ON v.employee_id = e.employee_id
 AND v.status = 'ACTIVE'
 AND v.effective_to IS NULL
SET e.employee_number = v.employee_number
WHERE e.company_id = '{COMPANY_ID}'
  AND e.employee_number LIKE 'SZT%'
  AND e.employee_number NOT LIKE 'SZST%'
  AND v.employee_number = CONCAT('SZST', SUBSTRING(e.employee_number, 4))
  AND SUBSTRING(e.employee_number, 4) REGEXP '^[0-9]+$';
"""


def main() -> int:
    if not os.environ.get("MYSQL_PWD"):
        raise SystemExit("先 export MYSQL_PWD")
    print("SZT 表工号对齐花名册 SZST。APPLY=%s" % int(APPLY))
    print("\n==== 预览 ====")
    run(PREVIEW)
    if not APPLY:
        print("确认含吕加军 SZT0687→SZST0687 后：")
        print("APPLY=1 python3 /root/fix-szt-employee-number.py")
        return 0
    print("\n==== APPLY ====")
    run(APPLY_SQL)
    print("\n==== 吕加军核对 ====")
    run(
        """
SELECT e.employee_number AS emp表, v.employee_number AS 花名册, v.display_name
FROM employee e
JOIN employee_version v
  ON v.employee_id = e.employee_id
 AND v.status = 'ACTIVE'
 AND v.effective_to IS NULL
WHERE v.employee_number = 'SZST0687';
"""
    )
    print("接着整月重算：COMPANY_ID=41000000-0000-0000-0000-000000000003 bash /root/recalculate-open-month.sh")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
