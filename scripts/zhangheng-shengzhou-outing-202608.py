#!/usr/bin/env python3
"""上海昇州 张衡 SZSZ0003：补 2026-08-11、2026-08-26 外出单。

08:30-18:00，不补打卡。格子要「外出」色须先有 day_types 列（V66），
并且之后重算昇州 2026-08。本脚本不重算。

宝塔：

  python3 /root/zhangheng-shengzhou-outing-202608.py
  APPLY=1 python3 /root/zhangheng-shengzhou-outing-202608.py

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
COMPANY_ID = "41000000-0000-0000-0000-000000000001"
EMPNO = "SZSZ0003"
NAME = "张衡"
DATES = ("2026-08-11", "2026-08-26")
ACTOR_ID = "SYSTEM"


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


def mysql_scalar(sql: str) -> str | None:
    text = mysql_script(sql, table=False).strip()
    if not text or text in ("\\N", "NULL"):
        return None
    return text.splitlines()[0].split("\t")[0]


def mysql_show(title: str, sql: str) -> None:
    print(f"\n==== {title} ====")
    text = mysql_script(sql, table=True).rstrip()
    print(text if text else "(无行)")


def date_union_sql() -> str:
    parts = [f"SELECT DATE '{day}' AS business_date" for day in DATES]
    return "\n    UNION ALL\n    ".join(parts)


def require_schema() -> None:
    present = mysql_scalar(
        """
SELECT COUNT(*)
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'attendance_hr_punch_adjustment'
  AND COLUMN_NAME = 'day_types';
"""
    )
    if present != "1":
        raise SystemExit("缺少 attendance_hr_punch_adjustment.day_types，先确认 Flyway V66 已成功。")


def preview() -> None:
    mysql_show(
        "花名册",
        f"""
SET NAMES utf8mb4;
SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       employee.employee_id,
       employee.company_id
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number = '{EMPNO}'
  AND version.display_name = '{NAME}';
""",
    )
    mysql_show(
        "已有外出调整",
        f"""
SET NAMES utf8mb4;
SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       adjustment.business_date AS 日期,
       adjustment.day_types AS 类型,
       adjustment.reason AS 原因,
       adjustment.on_duty_at AS 上班,
       adjustment.off_duty_at AS 下班
FROM attendance_hr_punch_adjustment adjustment
JOIN employee employee
  ON employee.employee_id = adjustment.employee_id
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.employee_number = '{EMPNO}'
  AND adjustment.business_date IN ('{DATES[0]}', '{DATES[1]}')
  AND adjustment.reversed_at IS NULL
ORDER BY adjustment.business_date;
""",
    )


def apply_sql() -> str:
    dates = date_union_sql()
    return f"""
SET NAMES utf8mb4;
START TRANSACTION;
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
       '{ACTOR_ID}',
       CURRENT_TIMESTAMP(6),
       NULL,
       NULL,
       'OUTING'
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
JOIN (
    {dates}
) dates
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number = '{EMPNO}'
  AND version.display_name = '{NAME}'
  AND NOT EXISTS (
        SELECT 1
        FROM attendance_hr_punch_adjustment existing
        WHERE existing.employee_id = employee.employee_id
          AND existing.business_date = dates.business_date
          AND existing.reversed_at IS NULL
          AND existing.day_types LIKE '%OUTING%'
  );
COMMIT;
"""


def main() -> int:
    print("昇州 张衡 2026-08-11/26 外出单。不补打卡，不重算，不改 cron。")
    print(f"APPLY={int(APPLY)} company={COMPANY_ID} empno={EMPNO}")
    require_schema()
    preview()
    found = mysql_scalar(
        f"""
SELECT COUNT(*)
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number = '{EMPNO}'
  AND version.display_name = '{NAME}';
"""
    )
    if found != "1":
        raise SystemExit(f"花名册找不到唯一的 {EMPNO} {NAME}（company={COMPANY_ID}），不能 APPLY。")
    if not APPLY:
        print("\n预览结束。确认是昇州张衡后：")
        print("  APPLY=1 python3 /root/zhangheng-shengzhou-outing-202608.py")
        print("先不要重算。格子要等昇州 2026-08 OPEN 重算才变外出色。")
        return 0
    print("\n==== APPLY ====")
    mysql_script(apply_sql())
    preview()
    print("\nAPPLY 完成。没有重算，明细格子要等你之后跑 recalculate-open-month.sh 才会变。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
