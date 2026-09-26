#!/usr/bin/env python3
"""江苏神州 2026-08 离职关任职。只改 employment_assignment，不重算。

半开区间：离职当天计入，effective_to = 离职日次日 00:00。
崔雨 SZST0721、张自豪 SZST0722 按工号关。不关谭钊/黄兆隆/唐家轩/张衡/赵子奇。

宝塔：

  python3 /root/close-2026-08-jiangsu-leavers.py
  APPLY=1 python3 /root/close-2026-08-jiangsu-leavers.py

默认预览。APPLY=1 才写库。不点重新计算，不改 cron。
密码：MYSQL_PWD，或 /etc/shenzhouhr/shenzhouhr.env 里的 SHENZHOUHR_DB_PASSWORD。
"""
from __future__ import annotations

import os
import subprocess
import sys

ENV_FILE = os.environ.get("SHENZHOUHR_ENV_FILE", "/etc/shenzhouhr/shenzhouhr.env")
HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
APPLY = os.environ.get("APPLY", "0") == "1"
COMPANY_ID = "41000000-0000-0000-0000-000000000003"

# 工号关岗：effective_to = 离职日次日 00:00（当天计入）
EMPNO_CLOSES = [
    ("SZST0641", "2026-08-08 00:00:00.000000"),
    ("SZST0662", "2026-08-08 00:00:00.000000"),
    ("SZST0598", "2026-08-08 00:00:00.000000"),
    ("SZST0674", "2026-08-12 00:00:00.000000"),
    ("SZST0638", "2026-08-13 00:00:00.000000"),
    ("SZST0652", "2026-08-20 00:00:00.000000"),
    ("SZST0721", "2026-08-22 00:00:00.000000"),
    ("SZSTSX91", "2026-08-22 00:00:00.000000"),
    ("SZSTSX95", "2026-08-22 00:00:00.000000"),
    ("SZST0501", "2026-08-26 00:00:00.000000"),
    ("SZST0645", "2026-08-29 00:00:00.000000"),
    ("SZST0722", "2026-08-29 00:00:00.000000"),
    ("SZST0138", "2026-09-01 00:00:00.000000"),
    ("SZST0216", "2026-09-01 00:00:00.000000"),
    ("SZST0531", "2026-09-01 00:00:00.000000"),
]
NAME_CLOSES = []


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


def empno_list_sql() -> str:
    return ", ".join("'" + empno.replace("'", "''") + "'" for empno, _ in EMPNO_CLOSES)


def planned_case_sql() -> str:
    branches = "\n       ".join(
        f"WHEN '{empno}' THEN '{close_at}'" for empno, close_at in EMPNO_CLOSES
    )
    return f"CASE employee.employee_number\n       {branches}\n     END"


def preview() -> None:
    planned = planned_case_sql()
    mysql_show(
        "工号名单当前任职",
        f"""
SET NAMES utf8mb4;
SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       assignment.effective_from AS 任职起,
       assignment.effective_to AS 任职止,
       {planned} AS 计划任职止
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
LEFT JOIN employment_assignment assignment
  ON assignment.employee_id = employee.employee_id
 AND assignment.version_valid_to IS NULL
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number IN ({empno_list_sql()})
ORDER BY employee.employee_number;
""",
    )
    mysql_show(
        "崔雨/张自豪 工号核对",
        f"""
SET NAMES utf8mb4;
SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       assignment.effective_to AS 任职止
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
LEFT JOIN employment_assignment assignment
  ON assignment.employee_id = employee.employee_id
 AND assignment.version_valid_to IS NULL
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number IN ('SZST0721', 'SZST0722');
""",
    )


def apply_sql() -> str:
    statements = [
        "SET NAMES utf8mb4;",
        "START TRANSACTION;",
    ]
    for empno, close_at in EMPNO_CLOSES:
        statements.append(
            f"""
UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
SET assignment.effective_to = '{close_at}'
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number = '{empno}'
  AND assignment.version_valid_to IS NULL
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '{close_at}';
"""
        )
    for name, close_at in NAME_CLOSES:
        statements.append(
            f"""
UPDATE employment_assignment assignment
JOIN employee employee
  ON employee.employee_id = assignment.employee_id
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
JOIN (
    SELECT version.display_name AS display_name,
           MIN(employee.employee_id) AS employee_id
    FROM employee employee
    JOIN employee_version version
      ON version.employee_id = employee.employee_id
     AND version.effective_to IS NULL
    WHERE employee.company_id = '{COMPANY_ID}'
      AND version.display_name = '{name}'
    GROUP BY version.display_name
    HAVING COUNT(DISTINCT employee.employee_id) = 1
) unique_name
  ON unique_name.employee_id = employee.employee_id
SET assignment.effective_to = '{close_at}'
WHERE employee.company_id = '{COMPANY_ID}'
  AND assignment.version_valid_to IS NULL
  AND assignment.effective_to IS NULL
  AND assignment.effective_from < '{close_at}';
"""
        )
    statements.append("COMMIT;")
    return "\n".join(statements)


def main() -> int:
    print("江苏神州 2026-08 离职关任职。不重算，不改 cron。")
    print(f"APPLY={int(APPLY)} company={COMPANY_ID}")
    preview()
    if not APPLY:
        print("\n预览结束。确认工号/姓名无误后：")
        print("  APPLY=1 python3 /root/close-2026-08-jiangsu-leavers.py")
        print("先不要重算。")
        return 0
    print("\n==== APPLY ====")
    mysql_script(apply_sql())
    preview()
    mysql_show(
        "崔雨/张自豪 未关（0 人或重名）",
        f"""
SET NAMES utf8mb4;
SELECT '崔雨/张自豪 未关（0 人或重名）' AS note,
       version.display_name AS 姓名,
       COUNT(DISTINCT employee.employee_id) AS 人数
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.company_id = '{COMPANY_ID}'
  AND version.display_name IN ('崔雨', '张自豪')
GROUP BY version.display_name
HAVING COUNT(DISTINCT employee.employee_id) <> 1;
""",
    )
    print("\nAPPLY 完成。没有重算，月度工时要等你之后跑 OPEN 月重算才会变。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
