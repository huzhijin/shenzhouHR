#!/usr/bin/env python3
"""作废误建的江苏神州 叶剑 SZST0567。正式叶剑是昇州 SZSZ0000，不要动。

宝塔：

  python3 /root/void-szst0567-yejian.py
  APPLY=1 python3 /root/void-szst0567-yejian.py

默认预览。APPLY=1 才写库。不重算、不改 cron。
密码：MYSQL_PWD，或 /etc/shenzhouhr/shenzhouhr.env 里的 SHENZHOUHR_DB_PASSWORD。
"""
from __future__ import annotations

import os
import subprocess
import sys

ENV_FILE = os.environ.get("SHENZHOUHR_ENV_FILE", "/etc/shenzhouhr/shenzhouhr.env")
HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
APPLY = os.environ.get("APPLY", "0") == "1"

EMPLOYEE_ID = "5d69bc56-1312-4548-91f4-8caaf61bf36b"
EMPLOYEE_NUMBER = "SZST0567"
KEEP_ID = "7b3125de-0ff2-5b2b-bed2-be5a8c39dd6a"
KEEP_NUMBER = "SZSZ0000"
VOID_NAME = "叶剑（误建作废）"
REASON = "误建作废：叶剑正式工号是昇州 SZSZ0000"


def load_env_file(path: str) -> dict[str, str]:
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


def ensure_mysql_password() -> None:
    if os.environ.get("MYSQL_PWD"):
        return
    password = load_env_file(ENV_FILE).get("SHENZHOUHR_DB_PASSWORD")
    if password:
        os.environ["MYSQL_PWD"] = password
        return
    raise SystemExit(
        f"缺少 MYSQL_PWD。先 export，或在 {ENV_FILE} 写 SHENZHOUHR_DB_PASSWORD。"
    )


def mysql_argv(table: bool) -> list[str]:
    argv = [
        mysql_bin(),
        "-h127.0.0.1",
        "-P3306",
        "-uroot",
        "--default-character-set=utf8mb4",
        HR_DB,
    ]
    if table:
        argv.append("--table")
    else:
        argv.extend(["-N", "-B"])
    return argv


def mysql_script(sql: str, table: bool = True) -> str:
    ensure_mysql_password()
    completed = subprocess.run(
        mysql_argv(table),
        input=sql,
        check=False,
        capture_output=True,
        text=True,
        env=os.environ.copy(),
    )
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"mysql failed rc={completed.returncode}")
    return completed.stdout


def mysql_show(title: str, sql: str) -> None:
    print(f"\n==== {title} ====")
    text = mysql_script(sql, table=True).rstrip()
    print(text if text else "(无行)")


def preview() -> None:
    mysql_show(
        "误建 SZST0567（应作废）",
        f"""
SET NAMES utf8mb4;
SELECT employee.employee_id,
       employee.employee_number,
       version.display_name,
       version.status,
       version.effective_from,
       version.effective_to,
       employee.company_id
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.employee_id = '{EMPLOYEE_ID}'
  AND employee.employee_number = '{EMPLOYEE_NUMBER}';
""",
    )
    mysql_show(
        "正式叶剑 SZSZ0000（禁止改）",
        f"""
SET NAMES utf8mb4;
SELECT employee.employee_id,
       employee.employee_number,
       version.display_name,
       version.status,
       version.effective_from,
       version.effective_to,
       employee.company_id
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.employee_id = '{KEEP_ID}'
   OR employee.employee_number = '{KEEP_NUMBER}';
""",
    )


def apply_sql() -> str:
    return f"""
SET NAMES utf8mb4;
START TRANSACTION;

SELECT COUNT(*) INTO @target_ok
FROM employee
WHERE employee_id = '{EMPLOYEE_ID}'
  AND employee_number = '{EMPLOYEE_NUMBER}'
  AND employee_id <> '{KEEP_ID}';

SELECT COUNT(*) INTO @keep_ok
FROM employee
WHERE employee_id = '{KEEP_ID}'
  AND employee_number = '{KEEP_NUMBER}';

SELECT IF(@target_ok = 1 AND @keep_ok = 1, 'ok', 'guard-failed') AS guard_status;

UPDATE employee
SET employment_status = 'TERMINATED',
    display_name = '{VOID_NAME}',
    row_version = row_version + 1,
    updated_at = UTC_TIMESTAMP(6)
WHERE employee_id = '{EMPLOYEE_ID}'
  AND employee_number = '{EMPLOYEE_NUMBER}'
  AND employee_id <> '{KEEP_ID}'
  AND @target_ok = 1
  AND @keep_ok = 1;

UPDATE employee_version version
JOIN employee employee
  ON employee.employee_id = version.employee_id
SET version.status = 'TERMINATED',
    version.display_name = '{VOID_NAME}',
    version.effective_to = DATE_ADD(version.effective_from, INTERVAL 1 DAY),
    version.change_reason = '{REASON}'
WHERE version.employee_id = '{EMPLOYEE_ID}'
  AND employee.employee_number = '{EMPLOYEE_NUMBER}'
  AND version.employee_number = '{EMPLOYEE_NUMBER}'
  AND version.employee_id <> '{KEEP_ID}'
  AND @target_ok = 1
  AND @keep_ok = 1
  AND (version.effective_to IS NULL
       OR version.effective_to > DATE_ADD(version.effective_from, INTERVAL 1 DAY));

UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = DATE_ADD(DATE(assignment.effective_from), INTERVAL 1 DAY)
WHERE assignment.employee_id = '{EMPLOYEE_ID}'
  AND employee.employee_number = '{EMPLOYEE_NUMBER}'
  AND assignment.employee_id <> '{KEEP_ID}'
  AND assignment.version_valid_to IS NULL
  AND @target_ok = 1
  AND @keep_ok = 1
  AND (assignment.effective_to IS NULL
       OR assignment.effective_to > DATE_ADD(DATE(assignment.effective_from), INTERVAL 1 DAY));

COMMIT;
"""


def main() -> int:
    preview()
    if not APPLY:
        print("\n预览。确认后：APPLY=1 python3 /root/void-szst0567-yejian.py")
        print("只会改 SZST0567。SZSZ0000 叶剑不动。")
        return 0
    out = mysql_script(apply_sql(), table=True)
    print(out)
    preview()
    print("作废完成。不要再建 SZST0567。正式叶剑仍是 SZSZ0000。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
