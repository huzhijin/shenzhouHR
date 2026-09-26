#!/usr/bin/env python3
"""8 月考勤数据对账：外出仍漏刷、加班单有格子但每日加班没小时、叶剑任职、人事/OA 重叠。

默认只预览。真正改库：

  APPLY=1 python3 diagnose-pending-oa-and-roster.py

APPLY 只会：把 SZSZ0000 叶剑当前任职改到昇州（若仍在江苏神州）；
不会改张衡 SZSZ0003 无 OA 外出行；不会回拨 OA 水位。
重算请用脚本打印的 employeeIds 调现有 recalculate 接口。
"""
from __future__ import annotations

import os
import subprocess
import sys
from datetime import date

ENV_FILE = os.environ.get("SHENZHOUHR_ENV_FILE", "/etc/shenzhouhr/shenzhouhr.env")
HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
APPLY = os.environ.get("APPLY", "0") == "1"
PERIOD_START = os.environ.get("PERIOD_START", "2026-08-01")
PERIOD_END = os.environ.get("PERIOD_END", "2026-09-01")
YE_JIAN = "SZSZ0000"
ZHANG_HENG = "SZSZ0003"
KEEP_HR_OUTING = ("2026-08-11", "2026-08-26")


def load_env_file(path: str) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        with open(path, encoding="utf8") as handle:
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


def mysql_credentials() -> tuple[str, str]:
    file_env = load_env_file(ENV_FILE)
    user = (
        os.environ.get("MYSQL_USER")
        or file_env.get("SHENZHOUHR_DB_USERNAME")
        or "root"
    )
    password = os.environ.get("MYSQL_PWD") or file_env.get("SHENZHOUHR_DB_PASSWORD")
    if not password:
        raise SystemExit(
            "缺少 MYSQL_PWD。可 export MYSQL_PWD=root密码 MYSQL_USER=root，"
            f"或在 {ENV_FILE} 写 SHENZHOUHR_DB_USERNAME / SHENZHOUHR_DB_PASSWORD。"
        )
    if not os.environ.get("MYSQL_PWD"):
        os.environ["MYSQL_PWD"] = password
    return user, password


def mysql_script(sql: str, table: bool = True) -> str:
    user, _password = mysql_credentials()
    argv = [
        mysql_bin(),
        "-h127.0.0.1",
        "-P3306",
        f"-u{user}",
        "--default-character-set=utf8mb4",
        HR_DB,
    ]
    argv.append("--table") if table else argv.extend(["-N", "-B"])
    completed = subprocess.run(
        argv,
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


def main() -> None:
    print(f"窗口 {PERIOD_START} .. {PERIOD_END} exclusive。APPLY={int(APPLY)}")
    mysql_show(
        "公司",
        "SELECT company_id, code, name FROM company ORDER BY code",
    )
    mysql_show(
        "叶剑任职",
        f"""
SELECT employee.company_id,
       employee_version.employee_number,
       employee_version.display_name,
       organization_version.name AS org_name,
       employment.effective_from,
       employment.effective_to,
       employment.assignment_id
FROM employee
JOIN employee_version
  ON employee_version.employee_id = employee.employee_id
 AND employee_version.status = 'ACTIVE'
 AND employee_version.effective_to IS NULL
JOIN employment_assignment employment
  ON employment.employee_id = employee.employee_id
 AND employment.version_valid_to IS NULL
 AND employment.record_status = 'ACTIVE'
 AND (employment.effective_to IS NULL OR employment.effective_to > CURDATE())
JOIN organization_version
  ON organization_version.organization_id = employment.organization_id
 AND organization_version.status = 'ACTIVE'
 AND organization_version.effective_to IS NULL
WHERE employee_version.employee_number = '{YE_JIAN}'
""",
    )
    mysql_show(
        "OA 外出覆盖日仍有漏刷异常（含审批中 UNKNOWN）",
        f"""
SELECT employee_version.employee_number,
       employee_version.display_name,
       organization_version.name AS org_name,
       fact.business_date,
       fact.exception_type,
       oa.document_type,
       oa.source_status
FROM attendance_report_exception_fact fact
JOIN attendance_report_projection projection
  ON projection.attendance_report_projection_id =
     fact.attendance_report_projection_id
 AND projection.status = 'PUBLISHED'
 AND projection.period_start = DATE '{PERIOD_START}'
 AND projection.period_end_exclusive = DATE '{PERIOD_END}'
JOIN employee_version
  ON employee_version.employee_version_id = fact.employee_version_id
JOIN organization_version
  ON organization_version.organization_version_id = fact.organization_version_id
JOIN attendance_report_oa_fact oa
  ON oa.attendance_report_projection_id = fact.attendance_report_projection_id
 AND oa.employee_id = fact.employee_id
 AND oa.document_type IN ('OUTING', 'TRIP', 'LEAVE', 'TIME_OFF', 'EXEMPT_PUNCH')
 AND oa.source_status IN ('APPROVED', 'MODIFIED', 'SUPPLEMENTED', 'UNKNOWN')
 AND DATE(DATE_ADD(oa.interval_start, INTERVAL 8 HOUR)) <= fact.business_date
 AND DATE(DATE_ADD(DATE_SUB(oa.interval_end, INTERVAL 1 SECOND), INTERVAL 8 HOUR))
     >= fact.business_date
WHERE fact.exception_type IN (
        'MISSING_PUNCH', 'MISSING_ON_DUTY', 'MISSING_OFF_DUTY', 'ABSENCE')
  AND fact.state <> 'RESOLVED'
ORDER BY employee_version.employee_number, fact.business_date
LIMIT 80
""",
    )
    mysql_show(
        "加班单已进 pin、日事实认可分钟为 0（DC/无卡典型：格子有加班、每日加班空白）",
        f"""
SELECT employee_version.employee_number,
       employee_version.display_name,
       organization_version.name AS org_name,
       DATE(DATE_ADD(oa.interval_start, INTERVAL 8 HOUR)) AS oa_start_date,
       oa.source_status,
       oa.recognized_minutes AS oa_minutes,
       daily.recognized_overtime_minutes AS daily_minutes
FROM attendance_report_oa_fact oa
JOIN attendance_report_projection projection
  ON projection.attendance_report_projection_id =
     oa.attendance_report_projection_id
 AND projection.status = 'PUBLISHED'
 AND projection.period_start = DATE '{PERIOD_START}'
 AND projection.period_end_exclusive = DATE '{PERIOD_END}'
JOIN employee_version
  ON employee_version.employee_version_id = oa.employee_version_id
JOIN organization_version
  ON organization_version.organization_version_id = oa.organization_version_id
LEFT JOIN attendance_report_daily_fact daily
  ON daily.attendance_report_projection_id = oa.attendance_report_projection_id
 AND daily.employee_id = oa.employee_id
 AND daily.business_date = DATE(DATE_ADD(oa.interval_start, INTERVAL 8 HOUR))
WHERE oa.document_type = 'OVERTIME'
  AND oa.source_status IN ('APPROVED', 'MODIFIED', 'SUPPLEMENTED', 'UNKNOWN')
  AND COALESCE(daily.recognized_overtime_minutes, 0) = 0
  AND oa.interval_start >= TIMESTAMP '{PERIOD_START} 00:00:00'
  AND oa.interval_start < TIMESTAMP '{PERIOD_END} 00:00:00'
ORDER BY organization_version.name, oa.interval_start
LIMIT 120
""",
    )
    mysql_show(
        "8/28–8/31 加班单 vs 日事实（服务中心/DC 优先看）",
        f"""
SELECT employee_version.employee_number,
       employee_version.display_name,
       organization_version.name AS org_name,
       DATE(DATE_ADD(oa.interval_start, INTERVAL 8 HOUR)) AS day,
       oa.source_status,
       ROUND(oa.recognized_minutes / 60, 1) AS oa_hours,
       ROUND(COALESCE(daily.recognized_overtime_minutes, 0) / 60, 1) AS daily_hours
FROM attendance_report_oa_fact oa
JOIN attendance_report_projection projection
  ON projection.attendance_report_projection_id =
     oa.attendance_report_projection_id
 AND projection.status = 'PUBLISHED'
 AND projection.period_start = DATE '{PERIOD_START}'
 AND projection.period_end_exclusive = DATE '{PERIOD_END}'
JOIN employee_version
  ON employee_version.employee_version_id = oa.employee_version_id
JOIN organization_version
  ON organization_version.organization_version_id = oa.organization_version_id
LEFT JOIN attendance_report_daily_fact daily
  ON daily.attendance_report_projection_id = oa.attendance_report_projection_id
 AND daily.employee_id = oa.employee_id
 AND daily.business_date = DATE(DATE_ADD(oa.interval_start, INTERVAL 8 HOUR))
WHERE oa.document_type = 'OVERTIME'
  AND DATE(DATE_ADD(oa.interval_start, INTERVAL 8 HOUR))
      BETWEEN DATE '2026-08-28' AND DATE '2026-08-31'
ORDER BY organization_version.name LIKE '%DC%' DESC,
         daily.recognized_overtime_minutes IS NULL DESC,
         employee_version.employee_number
LIMIT 150
""",
    )
    mysql_show(
        "日事实加班小时不是 0.5 网格",
        f"""
SELECT employee_version.employee_number,
       employee_version.display_name,
       fact.business_date,
       fact.recognized_overtime_minutes,
       ROUND(fact.recognized_overtime_minutes / 60, 2) AS hours
FROM attendance_report_daily_fact fact
JOIN attendance_report_projection projection
  ON projection.attendance_report_projection_id =
     fact.attendance_report_projection_id
 AND projection.status = 'PUBLISHED'
 AND projection.period_start = DATE '{PERIOD_START}'
 AND projection.period_end_exclusive = DATE '{PERIOD_END}'
JOIN employee_version
  ON employee_version.employee_version_id = fact.employee_version_id
WHERE fact.recognized_overtime_minutes > 0
  AND MOD(fact.recognized_overtime_minutes, 30) <> 0
ORDER BY fact.business_date, employee_version.employee_number
LIMIT 80
""",
    )
    mysql_show(
        "人事预加与 OA 同种类重叠（不含张衡无 OA 外出）",
        f"""
SELECT employee_version.employee_number,
       employee_version.display_name,
       adj.business_date,
       adj.day_types,
       adj.overtime_minutes_override,
       adj.reason,
       GROUP_CONCAT(DISTINCT oa.document_type) AS oa_types
FROM attendance_hr_punch_adjustment adj
JOIN employee_version
  ON employee_version.employee_id = adj.employee_id
 AND employee_version.status = 'ACTIVE'
 AND employee_version.effective_to IS NULL
LEFT JOIN attendance_report_oa_fact oa
  ON oa.employee_id = adj.employee_id
 AND oa.source_status IN ('APPROVED', 'MODIFIED', 'SUPPLEMENTED', 'UNKNOWN')
 AND DATE(DATE_ADD(oa.interval_start, INTERVAL 8 HOUR)) <= adj.business_date
 AND DATE(DATE_ADD(DATE_SUB(oa.interval_end, INTERVAL 1 SECOND), INTERVAL 8 HOUR))
     >= adj.business_date
WHERE adj.reversed_at IS NULL
  AND adj.business_date >= DATE '{PERIOD_START}'
  AND adj.business_date < DATE '{PERIOD_END}'
  AND NOT (
        employee_version.employee_number = '{ZHANG_HENG}'
        AND adj.business_date IN {KEEP_HR_OUTING}
      )
GROUP BY adj.adjustment_id
HAVING oa_types IS NOT NULL
ORDER BY adj.business_date
LIMIT 80
""",
    )
    print(
        "\n张衡 SZSZ0003 8/11、8/26 外出人事行应保留。"
        "换包后对上面「加班单已进 pin、日事实为 0」的人按 employeeIds 重算 8 月，"
        "不要点整公司整月。"
    )
    if not APPLY:
        print("预览结束。确认叶剑要改挂昇州后再 APPLY=1。")
        return
    print("APPLY=1：叶剑任职改挂由运维在确认目标 organization_id 后手工执行，本脚本不自动改任职。")
    print("禁止回拨 OA 水位。禁止改张衡外出行。")


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
