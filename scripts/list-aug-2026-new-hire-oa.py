#!/usr/bin/env python3
"""列出 2026-08 新入职人员的 OA 单据状态。只读。不回拨水位。"""
from __future__ import annotations

import os
import subprocess
import sys

ENV_FILE = os.environ.get("SHENZHOUHR_ENV_FILE", "/etc/shenzhouhr/shenzhouhr.env")
HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
EMPNOS = [
    "SZST0709", "SZST0710", "SZST0711", "SZST0712", "SZST0713", "SZST0714",
    "SZST0715", "SZST0716", "SZST0717", "SZST0718", "SZST0719", "SZST0720",
    "SZST0721", "SZST0722", "SZST0723", "SZST0724", "SZST0725", "SZST0726",
    "SZST0727", "SZST0728", "SZST0729", "SZST0730", "SZSTSX109",
    "SZJN0040", "SZJN0041",
]


def load_env(path: str) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for raw in handle:
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip("'").strip('"')
    except FileNotFoundError:
        pass
    return values


def mysql_bin() -> str:
    path = "/www/server/mysql/bin/mysql"
    return path if os.access(path, os.X_OK) else "mysql"


def ensure_password() -> None:
    if os.environ.get("MYSQL_PWD"):
        return
    password = load_env(ENV_FILE).get("SHENZHOUHR_DB_PASSWORD")
    if password:
        os.environ["MYSQL_PWD"] = password
        return
    raise SystemExit("缺少 MYSQL_PWD 或 SHENZHOUHR_DB_PASSWORD")


def main() -> int:
    ensure_password()
    empno_sql = ", ".join("'" + n + "'" for n in EMPNOS)
    sql = f"""
SET NAMES utf8mb4;
SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       employee.onboard_date AS 入职日,
       oa.document_type AS 单据,
       oa.source_status AS 状态,
       COUNT(*) AS 条数
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
LEFT JOIN oa_attendance_document oa
  ON oa.employee_id = employee.employee_id
 AND oa.interval_start >= '2026-07-31 16:00:00'
 AND oa.interval_start < '2026-08-31 16:00:00'
WHERE employee.employee_number IN ({empno_sql})
GROUP BY employee.employee_number, version.display_name, employee.onboard_date,
         oa.document_type, oa.source_status
ORDER BY employee.employee_number, oa.document_type, oa.source_status;
"""
    cmd = [
        mysql_bin(),
        "-h127.0.0.1",
        "-P3306",
        "-uroot",
        "--default-character-set=utf8mb4",
        "--table",
        HR_DB,
    ]
    completed = subprocess.run(cmd, input=sql, text=True, capture_output=True)
    sys.stdout.write(completed.stdout)
    sys.stderr.write(completed.stderr)
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
