#!/usr/bin/env python3
"""2026-08 新人：入职日早上漏刷补 08:29；打星的人入职次日也补。

第一天：现网核算已按任职开始日注入 08:29（有真实上班卡仍用真实时刻）。
本脚本不为第一天写卡。

打星（入职次日早上缺卡也按正常上班补 08:29）：
  王威 SZST0710  8/3 → 次日 8/4
  胡乐雅 SZST0715 8/10 → 次日 8/11
  龚航航 SZST0719 8/10 → 次日 8/11
  张磊 SZST0725  8/17 → 次日 8/18
  柏杨 SZST0726  8/24 → 次日 8/25
有真实上午卡则不补。

郑伟琦 SZST0730：单独诊断为何报表没数据。

宝塔：

  python3 /root/aug-2026-new-hire-morning-0829.py
  APPLY=1 python3 /root/aug-2026-new-hire-morning-0829.py

默认预览。APPLY=1 才写库。不重算，不改 cron。
密码：MYSQL_PWD，或 /etc/shenzhouhr/shenzhouhr.env 里的 SHENZHOUHR_DB_PASSWORD。
"""
from __future__ import annotations

import os
import subprocess
import sys
from datetime import date, datetime, timedelta, timezone

ENV_FILE = os.environ.get("SHENZHOUHR_ENV_FILE", "/etc/shenzhouhr/shenzhouhr.env")
HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
APPLY = os.environ.get("APPLY", "0") == "1"
COMPANY_ID = "41000000-0000-0000-0000-000000000003"
ACTOR_ID = "SYSTEM"
SHANGHAI = timezone(timedelta(hours=8))
REASON = "入职次日早上缺卡按正常上班补 08:29"

HIRES = [
    ("SZST0709", "杨玲", date(2026, 8, 3), False),
    ("SZST0710", "王威", date(2026, 8, 3), True),
    ("SZST0711", "李汪智", date(2026, 8, 3), False),
    ("SZST0712", "张海尔", date(2026, 8, 3), False),
    ("SZST0713", "王俊杰", date(2026, 8, 3), False),
    ("SZST0714", "仇容轩", date(2026, 8, 3), False),
    ("SZST0715", "胡乐雅", date(2026, 8, 10), True),
    ("SZST0716", "何伟豪", date(2026, 8, 10), False),
    ("SZST0717", "钱龙", date(2026, 8, 10), False),
    ("SZST0718", "陈家辉", date(2026, 8, 10), False),
    ("SZST0719", "龚航航", date(2026, 8, 10), True),
    ("SZST0720", "叶凯", date(2026, 8, 10), False),
    ("SZST0723", "汪洋", date(2026, 8, 17), False),
    ("SZST0724", "袁秋阳", date(2026, 8, 17), False),
    ("SZST0725", "张磊", date(2026, 8, 17), True),
    ("SZST0726", "柏杨", date(2026, 8, 24), True),
    ("SZST0727", "徐东", date(2026, 8, 24), False),
    ("SZST0728", "汤润豪", date(2026, 8, 24), False),
    ("SZST0729", "王锦龙", date(2026, 8, 24), False),
    ("SZST0730", "郑伟琦", date(2026, 8, 24), False),
    ("SZSTSX109", "马振雯", date(2026, 8, 11), False),
]


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


def mysql_rows(sql: str) -> list[list[str]]:
    rows = []
    for line in mysql_script(sql, table=False).splitlines():
        if line:
            rows.append(line.split("\t"))
    return rows


def sql_in(values: list[str]) -> str:
    return ", ".join("'" + value.replace("'", "''") + "'" for value in values)


def utc_0829(day: date) -> str:
    instant = datetime(day.year, day.month, day.day, 8, 29, tzinfo=SHANGHAI)
    return instant.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.000000")


def empnos() -> list[str]:
    return [row[0] for row in HIRES]


def preview_roster() -> None:
    mysql_show(
        "花名册 / 任职起 / 考勤组",
        f"""
SET NAMES utf8mb4;
SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       DATE(CONVERT_TZ(assignment.effective_from, '+00:00', '+08:00')) AS 任职起上海,
       assignment.effective_to AS 任职止,
       grp.attendance_group_revision_id IS NOT NULL AS 有考勤组
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
LEFT JOIN employment_assignment assignment
  ON assignment.employee_id = employee.employee_id
 AND assignment.version_valid_to IS NULL
LEFT JOIN attendance_group_assignment grp
  ON grp.employee_id = employee.employee_id
 AND NOT EXISTS (
        SELECT 1
        FROM attendance_group_assignment successor
        WHERE successor.supersedes_assignment_id =
              grp.attendance_group_assignment_id
 )
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number IN ({sql_in(empnos())})
ORDER BY employee.employee_number;
""",
    )
    found = {
        row[0]
        for row in mysql_rows(
            f"""
SELECT employee.employee_number
FROM employee employee
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number IN ({sql_in(empnos())});
"""
        )
    }
    missing = [empno for empno, name, _, _ in HIRES if empno not in found]
    print("\n==== 名单里现网没有的工号 ====")
    if missing:
        for empno, name, _, _ in HIRES:
            if empno in missing:
                print(f"  {empno} {name}")
        print("这些人要先跑 import-aug-2026-new-hires.py")
    else:
        print("(名单工号都在花名册)")


def preview_punches() -> None:
    mysql_show(
        "8月有效卡（上海月）",
        f"""
SET NAMES utf8mb4;
SELECT version.employee_number AS 工号,
       version.display_name AS 姓名,
       COUNT(*) AS 八月有效卡,
       MIN(CONVERT_TZ(event.point_instant, '+00:00', '+08:00')) AS 最早,
       MAX(CONVERT_TZ(event.point_instant, '+00:00', '+08:00')) AS 最晚
FROM effective_attendance_event event
JOIN employee_version version
  ON version.employee_id = event.employee_id
 AND version.effective_to IS NULL
WHERE event.event_kind = 'PUNCH_POINT'
  AND event.point_instant >= '2026-07-31 16:00:00'
  AND event.point_instant <  '2026-08-31 16:00:00'
  AND version.employee_number IN ({sql_in(empnos())})
  AND (
        SELECT lifecycle.lifecycle_type
        FROM effective_event_lifecycle_fact lifecycle
        WHERE lifecycle.effective_attendance_event_id =
              event.effective_attendance_event_id
        ORDER BY lifecycle.knowledge_at DESC,
                 lifecycle.effective_event_lifecycle_fact_id DESC
        LIMIT 1
      ) = 'ACTIVATED'
GROUP BY version.employee_number, version.display_name
ORDER BY version.employee_number;
""",
    )


def starred_rows() -> list[tuple[str, str, date]]:
    return [
        (empno, name, hire + timedelta(days=1))
        for empno, name, hire, starred in HIRES
        if starred
    ]


def preview_starred_day2() -> None:
    cases = "\n    UNION ALL\n    ".join(
        f"SELECT '{empno}' AS empno, DATE '{day.isoformat()}' AS day2"
        for empno, _, day in starred_rows()
    )
    mysql_show(
        "打星人次日上午卡 / 已有人事补上班",
        f"""
SET NAMES utf8mb4;
SELECT listed.empno AS 工号,
       version.display_name AS 姓名,
       listed.day2 AS 次日,
       SUM(CASE
             WHEN CONVERT_TZ(event.point_instant, '+00:00', '+08:00') >= listed.day2
              AND CONVERT_TZ(event.point_instant, '+00:00', '+08:00')
                  < DATE_ADD(listed.day2, INTERVAL 12 HOUR)
             THEN 1 ELSE 0
           END) AS 上午有效卡,
       MAX(adjustment.on_duty_at IS NOT NULL) AS 已有人事上班
FROM (
    {cases}
) listed
LEFT JOIN employee employee
  ON employee.company_id = '{COMPANY_ID}'
 AND employee.employee_number = listed.empno
LEFT JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
LEFT JOIN effective_attendance_event event
  ON event.employee_id = employee.employee_id
 AND event.event_kind = 'PUNCH_POINT'
 AND (
        SELECT lifecycle.lifecycle_type
        FROM effective_event_lifecycle_fact lifecycle
        WHERE lifecycle.effective_attendance_event_id =
              event.effective_attendance_event_id
        ORDER BY lifecycle.knowledge_at DESC,
                 lifecycle.effective_event_lifecycle_fact_id DESC
        LIMIT 1
      ) = 'ACTIVATED'
LEFT JOIN attendance_hr_punch_adjustment adjustment
  ON adjustment.employee_id = employee.employee_id
 AND adjustment.business_date = listed.day2
 AND adjustment.reversed_at IS NULL
GROUP BY listed.empno, version.display_name, listed.day2
ORDER BY listed.empno;
""",
    )


def diagnose_zhengweiqi() -> None:
    mysql_show(
        "郑伟琦 花名册",
        f"""
SET NAMES utf8mb4;
SELECT employee.employee_number AS 工号,
       version.display_name AS 姓名,
       employee.employee_id,
       employee.company_id,
       assignment.effective_from AS 任职起,
       assignment.effective_to AS 任职止
FROM employee employee
LEFT JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
LEFT JOIN employment_assignment assignment
  ON assignment.employee_id = employee.employee_id
 AND assignment.version_valid_to IS NULL
WHERE version.display_name = '郑伟琦'
   OR employee.employee_number = 'SZST0730';
""",
    )
    mysql_show(
        "郑伟琦 得力绑定",
        """
SET NAMES utf8mb4;
SELECT version.employee_number AS 工号,
       version.display_name AS 姓名,
       CONVERT(binding.deli_user_id USING utf8mb4) AS deli_user_id,
       CONVERT(binding.deli_employee_num USING utf8mb4) AS deli_empno,
       CONVERT(binding.binding_status USING utf8mb4) AS 状态
FROM employee_source_binding binding
JOIN employee_version version
  ON version.employee_id = binding.employee_id
 AND version.effective_to IS NULL
WHERE binding.effective_to IS NULL
  AND (version.employee_number = 'SZST0730'
       OR version.display_name = '郑伟琦');
""",
    )
    mysql_show(
        "郑伟琦 全部有效卡",
        """
SET NAMES utf8mb4;
SELECT version.employee_number AS 工号,
       CONVERT_TZ(event.point_instant, '+00:00', '+08:00') AS 上海时刻,
       event.normalized_direction AS 方向
FROM effective_attendance_event event
JOIN employee_version version
  ON version.employee_id = event.employee_id
 AND version.effective_to IS NULL
WHERE event.event_kind = 'PUNCH_POINT'
  AND (version.employee_number = 'SZST0730'
       OR version.display_name = '郑伟琦')
  AND (
        SELECT lifecycle.lifecycle_type
        FROM effective_event_lifecycle_fact lifecycle
        WHERE lifecycle.effective_attendance_event_id =
              event.effective_attendance_event_id
        ORDER BY lifecycle.knowledge_at DESC,
                 lifecycle.effective_event_lifecycle_fact_id DESC
        LIMIT 1
      ) = 'ACTIVATED'
ORDER BY event.point_instant;
""",
    )
    mysql_show(
        "郑伟琦 8月报表日事实",
        """
SET NAMES utf8mb4;
SELECT version.employee_number AS 工号,
       version.display_name AS 姓名,
       fact.business_date AS 日期,
       fact.shift_label AS 班次,
       CONVERT_TZ(fact.first_punch_at, '+00:00', '+08:00') AS 上班,
       CONVERT_TZ(fact.last_punch_at, '+00:00', '+08:00') AS 下班,
       fact.missing_punch_count AS 漏刷次数,
       pin.status AS pin状态,
       pin.period_state AS 账期
FROM attendance_report_daily_fact fact
JOIN attendance_report_projection pin
  ON pin.attendance_report_projection_id =
     fact.attendance_report_projection_id
JOIN employee_version version
  ON version.employee_version_id = fact.employee_version_id
WHERE pin.period_start = '2026-08-01'
  AND pin.period_end_exclusive = '2026-09-01'
  AND pin.status = 'PUBLISHED'
  AND (version.employee_number = 'SZST0730'
       OR version.display_name = '郑伟琦')
ORDER BY pin.published_at DESC, fact.business_date
LIMIT 40;
""",
    )


def apply_sql() -> str:
    statements = ["SET NAMES utf8mb4;", "START TRANSACTION;"]
    for empno, name, day2 in starred_rows():
        on_duty = utc_0829(day2)
        day_start = datetime(
            day2.year, day2.month, day2.day, tzinfo=SHANGHAI
        ).astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        noon = datetime(
            day2.year, day2.month, day2.day, 12, tzinfo=SHANGHAI
        ).astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        statements.append(
            f"""
UPDATE attendance_hr_punch_adjustment adjustment
JOIN employee employee
  ON employee.employee_id = adjustment.employee_id
SET adjustment.on_duty_at = '{on_duty}',
    adjustment.reason = CONCAT(IFNULL(adjustment.reason, ''), '；{REASON}')
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number = '{empno}'
  AND adjustment.business_date = '{day2.isoformat()}'
  AND adjustment.reversed_at IS NULL
  AND adjustment.on_duty_at IS NULL;
"""
        )
        statements.append(
            f"""
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
       DATE '{day2.isoformat()}',
       '{on_duty}',
       NULL,
       '{REASON}（{name}）',
       '{ACTOR_ID}',
       CURRENT_TIMESTAMP(6),
       NULL,
       NULL,
       NULL
FROM employee employee
JOIN employee_version version
  ON version.employee_id = employee.employee_id
 AND version.effective_to IS NULL
WHERE employee.company_id = '{COMPANY_ID}'
  AND employee.employee_number = '{empno}'
  AND NOT EXISTS (
        SELECT 1
        FROM attendance_hr_punch_adjustment existing
        WHERE existing.employee_id = employee.employee_id
          AND existing.business_date = DATE '{day2.isoformat()}'
          AND existing.reversed_at IS NULL
          AND existing.on_duty_at IS NOT NULL
  )
  AND NOT EXISTS (
        SELECT 1
        FROM effective_attendance_event event
        WHERE event.employee_id = employee.employee_id
          AND event.event_kind = 'PUNCH_POINT'
          AND event.point_instant >= '{day_start}'
          AND event.point_instant <  '{noon}'
          AND (
                SELECT lifecycle.lifecycle_type
                FROM effective_event_lifecycle_fact lifecycle
                WHERE lifecycle.effective_attendance_event_id =
                      event.effective_attendance_event_id
                ORDER BY lifecycle.knowledge_at DESC,
                         lifecycle.effective_event_lifecycle_fact_id DESC
                LIMIT 1
              ) = 'ACTIVATED'
  );
"""
        )
    statements.append("COMMIT;")
    return "\n".join(statements)


def main() -> int:
    print("2026-08 新人早上 08:29。第一天走现网入职规则；打星的人次日补人事上班卡。")
    print(f"APPLY={int(APPLY)} company={COMPANY_ID}")
    print("打星次日：王威 8/4、胡乐雅 8/11、龚航航 8/11、张磊 8/18、柏杨 8/25")
    preview_roster()
    preview_punches()
    preview_starred_day2()
    diagnose_zhengweiqi()
    if not APPLY:
        print("\n预览结束。确认打星 5 人次日上午确实没卡后：")
        print("  APPLY=1 python3 /root/aug-2026-new-hire-morning-0829.py")
        print("第一天 08:29 不用写库，重算江苏 2026-08 OPEN 后自动出。")
        print("郑伟琦若花名册没有，先跑 import-aug-2026-new-hires.py。")
        print("先不要重算。")
        return 0
    print("\n==== APPLY 打星次日 08:29 ====")
    mysql_script(apply_sql())
    preview_starred_day2()
    print("\nAPPLY 完成。没有重算，格子要等江苏 2026-08 OPEN 重算才变。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
