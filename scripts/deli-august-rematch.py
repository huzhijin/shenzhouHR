#!/usr/bin/env python3
"""Compare Deli employee/query + CHECKIN punches with the HR roster.

Run on the production server (宝塔 root). Preview by default; APPLY=1 writes
CONFIRMED snowflake bindings. Does not recalculate reports.

  python3 /root/deli-august-rematch.py
  APPLY=1 python3 /root/deli-august-rematch.py

Loads DELI_EPLUS_APP_KEY/SECRET from /etc/shenzhouhr/shenzhouhr.env.
Loads MYSQL_PWD from the environment.

Deli field map (official employee API vs punch API vs E+ UI):

  employee/query.id            得力E+员工id，现网是短号(929/358/359)，不能绑打卡
  employee/query.ext_id        外部系统id，现网多数空
  employee/query.employee_num  工号，对 HR employee_number
  employee/query.name          姓名
  checkin_query.user_id        打卡人员ID，与 E+ user_id 不一致；现网是截图「帐号」雪花
  checkin_query.ext_id         若设置了外部id才会有
  check_data.employee_num      设备工号
  check_data.member_name       打卡姓名
  E+ 人员详情「帐号」            = 打卡 user_id 雪花（马振雯 1293987480203825152）
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone

SHANGHAI = timezone(timedelta(hours=8))
FROM_DT = datetime(2026, 8, 1, tzinfo=SHANGHAI)
TO_DT = datetime(2026, 9, 1, tzinfo=SHANGHAI)
SOURCE_ID = "98aa08a6-fab6-46d3-a880-305746b9cc92"
EFFECTIVE_FROM = "2026-01-01 00:00:00.000000"
BASE_URL = os.environ.get("DELI_EPLUS_BASE_URL", "https://v2-api.delicloud.com")
ENV_FILE = os.environ.get("SHENZHOUHR_ENV_FILE", "/etc/shenzhouhr/shenzhouhr.env")
OUT_DIR = os.environ.get("OUT_DIR", "/root")
APPLY = os.environ.get("APPLY", "0") == "1"
FOCUS = ("马振雯", "赵子奇", "张衡", "张静", "黄兆隆")
PAGE_SLEEP = float(os.environ.get("DELI_PAGE_SLEEP", "0.15"))

# Official intern numbers that are not on the HR roster.
INTERN_TO_ROSTER = {
    "SZSTSX50": "SZST0498",
    "SZSTSX58": "SZST0554",
    "SZSTSX61": "SZST0677",
    "SZSTSX67": "SZST0654",
    "SZSTSX71": "SZST0680",
    "SZSTSX80": "SZST0699",
    "SZSTSX85": "SZST0701",
    "SZSZ003": "SZSZ0003",
}

# Screenshot / already-verified E+ 帐号. Never bind the short directory id.
MANUAL_SNOWFLAKES = (
    ("马振雯", "SZSTSX109", "1293987480203825152"),
    ("赵子奇", "SZSZ0002", "893562016190177280"),
    ("张衡", "SZSZ0003", "893561950264057857"),
)

JULY_CONFLICT = (
    ("陆玉蕾", "932303205680513024", "SZST0284"),
    ("彭伟", "939805188834107393", "SZST0335"),
    ("李扬", "947095089162633216", "SZST0291"),
    ("王善源", "1248197527100440576", "SZST0694"),
    ("方鹏", "1142820074358341634", "SZST0491"),
    ("张晨阳", "1278767800065257473", "SZST0663"),
    ("仇容轩", "1290356442827120641", "SZST0714"),
    ("居军", "646292787763654657", "SZST0017"),
)


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


def require_deli_credentials() -> tuple[str, str]:
    file_values = load_env_file(ENV_FILE)
    key = os.environ.get("DELI_EPLUS_APP_KEY") or file_values.get("DELI_EPLUS_APP_KEY")
    secret = os.environ.get("DELI_EPLUS_APP_SECRET") or file_values.get(
        "DELI_EPLUS_APP_SECRET"
    )
    if not key or not secret:
        sys.exit(f"missing DELI_EPLUS_APP_KEY/SECRET (looked at {ENV_FILE})")
    return key, secret


def sign(path: str, timestamp: str, app_key: str, app_secret: str) -> str:
    payload = f"{path}{timestamp}{app_key}{app_secret}"
    return hashlib.md5(payload.encode("utf-8")).hexdigest()


def deli_post(
    path: str,
    body: dict,
    app_key: str,
    app_secret: str,
    extra_headers: dict[str, str] | None = None,
) -> dict:
    timestamp = str(int(time.time() * 1000))
    headers = {
        "Content-Type": "application/json; charset=UTF-8",
        "App-Key": app_key,
        "App-Timestamp": timestamp,
        "App-Sig": sign(path, timestamp, app_key, app_secret),
    }
    if extra_headers:
        headers.update(extra_headers)
    request = urllib.request.Request(
        BASE_URL.rstrip("/") + path,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    last_error: Exception | None = None
    for attempt in range(1, 6):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if payload.get("code") in (109, 110):
                time.sleep(attempt)
                last_error = RuntimeError(f"Deli retryable code={payload.get('code')}")
                continue
            if payload.get("code") != 0:
                raise RuntimeError(
                    f"Deli {path} code={payload.get('code')} msg={payload.get('msg')}"
                )
            return payload
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:300]
            if exc.code in (429, 500, 502, 503, 504) and attempt < 5:
                time.sleep(attempt)
                last_error = RuntimeError(f"HTTP {exc.code} {detail}")
                continue
            raise RuntimeError(f"HTTP {exc.code} {path} {detail}") from exc
        except TimeoutError as exc:
            last_error = exc
            time.sleep(attempt)
    raise RuntimeError(f"Deli {path} failed: {last_error}")


def is_snowflake(value: str | None) -> bool:
    if value is None:
        return False
    text = str(value).strip()
    return len(text) >= 16 and text.isdigit()


def text(value) -> str | None:
    if value is None:
        return None
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        value = str(int(value)) if float(value).is_integer() else str(value)
    else:
        value = str(value)
    value = value.strip()
    return value or None


def mysql(sql: str) -> list[list[str]]:
    env = os.environ.copy()
    completed = subprocess.run(
        [
            "mysql",
            "--default-character-set=utf8mb4",
            "-uroot",
            "shenzhou_hr",
            "-N",
            "-B",
            "-e",
            sql,
        ],
        check=True,
        capture_output=True,
        text=True,
        env=env,
    )
    rows = []
    for line in completed.stdout.splitlines():
        if line:
            rows.append(line.split("\t"))
    return rows


def fetch_employees(app_key: str, app_secret: str) -> list[dict]:
    rows: list[dict] = []
    offset = 0
    limit = 100
    keys: set[str] = set()
    while True:
        payload = deli_post(
            "/v2.0/employee/query",
            {"offset": offset, "limit": limit},
            app_key,
            app_secret,
        )
        data = payload.get("data") or {}
        page = data.get("rows") or data.get("data") or []
        for row in page:
            if isinstance(row, dict):
                keys.update(row.keys())
                rows.append(row)
        if len(page) < limit:
            break
        offset += len(page)
        time.sleep(PAGE_SLEEP)
    print(f"employee/query rows={len(rows)} keys={sorted(keys)}")
    return rows


def parse_check_data(raw) -> dict:
    if raw is None:
        return {}
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        raw = raw.strip()
        if raw.startswith("{") and raw.endswith("}"):
            try:
                parsed = json.loads(raw)
                return parsed if isinstance(parsed, dict) else {}
            except json.JSONDecodeError:
                return {}
    return {}


def fetch_punches(app_key: str, app_secret: str, module: str) -> list[dict]:
    rows: list[dict] = []
    next_id = 0
    page_size = 500
    seen = {0}
    pages = 0
    while pages < 10_000:
        payload = deli_post(
            "/v2.0/cloudappapi",
            {"next_id": next_id, "page_size": page_size},
            app_key,
            app_secret,
            extra_headers={"Api-Module": module, "Api-Cmd": "checkin_query"},
        )
        data = payload.get("data") or {}
        page = data.get("data") or []
        if not page:
            break
        for row in page:
            if not isinstance(row, dict):
                continue
            check_time = row.get("check_time")
            try:
                instant = datetime.fromtimestamp(int(check_time), tz=timezone.utc)
            except (TypeError, ValueError, OSError):
                continue
            if instant < FROM_DT.astimezone(timezone.utc) or instant >= TO_DT.astimezone(
                timezone.utc
            ):
                continue
            check_data = parse_check_data(row.get("check_data"))
            rows.append(
                {
                    "id": text(row.get("id")),
                    "user_id": text(row.get("user_id")),
                    "ext_id": text(row.get("ext_id")),
                    "empno": text(row.get("empno"))
                    or text(check_data.get("employee_num")),
                    "name": text(row.get("name"))
                    or text(row.get("member_name"))
                    or text(row.get("user_name"))
                    or text(check_data.get("member_name")),
                    "check_time": instant.astimezone(SHANGHAI).strftime(
                        "%Y-%m-%d %H:%M:%S"
                    ),
                    "module": module,
                    "raw_keys": sorted(str(key) for key in row.keys()),
                }
            )
        pages += 1
        try:
            nxt = int(data.get("next_id"))
        except (TypeError, ValueError):
            break
        if nxt in seen or nxt == next_id:
            break
        seen.add(nxt)
        next_id = nxt
        if pages % 20 == 0:
            print(f"  {module} pages={pages} august_punches={len(rows)} next_id={next_id}")
        time.sleep(PAGE_SLEEP)
    print(f"{module} pages={pages} august_punches={len(rows)}")
    return rows


def unique_map(rows: list[list[str]], key_index: int) -> dict[str, list[list[str]]]:
    grouped: dict[str, list[list[str]]] = defaultdict(list)
    for row in rows:
        key = row[key_index].strip()
        if key:
            grouped[key].append(row)
    return grouped


def decide(
    name: str | None,
    empno: str | None,
    by_name: dict[str, list[list[str]]],
    by_number: dict[str, list[list[str]]],
) -> tuple[str, str, str, str] | None:
    roster_empno = INTERN_TO_ROSTER.get(empno or "", empno)
    named = by_name.get(name or "")
    numbered = by_number.get(roster_empno or "")
    named_one = named[0] if named and len(named) == 1 else None
    numbered_one = numbered[0] if numbered and len(numbered) == 1 else None
    if named_one and numbered_one and named_one[0] != numbered_one[0]:
        return named_one[0], named_one[1], named_one[2], "NAME_OVER_DEVICE_EMPNO"
    if numbered_one:
        return numbered_one[0], numbered_one[1], numbered_one[2], "EMPLOYEE_NUMBER"
    if named_one:
        return named_one[0], named_one[1], named_one[2], "DISPLAY_NAME"
    return None


def write_text(path: str, content: str) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)


def main() -> int:
    app_key, app_secret = require_deli_credentials()
    print("==== HR roster ====")
    roster = mysql(
        """
SET NAMES utf8mb4;
SELECT employee.employee_id,
       version.employee_number,
       version.display_name
FROM employee employee
JOIN employee_current_projection projection
  ON projection.employee_id = employee.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
"""
    )
    by_name = unique_map(roster, 2)
    by_number = unique_map(roster, 1)
    print(f"hr_employees={len(roster)}")

    current_bindings = mysql(
        f"""
SET NAMES utf8mb4;
SELECT CONVERT(binding.deli_user_id USING utf8mb4),
       binding.employee_id,
       version.employee_number,
       version.display_name
FROM employee_source_binding binding
JOIN employee_current_projection projection
  ON projection.employee_id = binding.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
WHERE binding.effective_to IS NULL
  AND binding.deli_attendance_source_id = '{SOURCE_ID}'
  AND binding.deli_user_id IS NOT NULL
"""
    )
    bound_by_snowflake = {row[0]: row for row in current_bindings}
    print(f"current_deli_bindings={len(current_bindings)}")

    print("==== Deli employee/query ====")
    employees = fetch_employees(app_key, app_secret)
    write_text(
        os.path.join(OUT_DIR, "deli-employee-query.json"),
        json.dumps(employees, ensure_ascii=False, indent=2),
    )

    print("==== focus people in employee/query ====")
    for row in employees:
        name = text(row.get("name"))
        empno = text(row.get("employee_num"))
        if name in FOCUS or empno in {"SZSTSX109", "SZSZ0002", "SZSZ0003", "SZST0442"}:
            print(json.dumps(row, ensure_ascii=False, sort_keys=True))

    print("==== Deli CHECKIN August ====")
    punches = fetch_punches(app_key, app_secret, "CHECKIN")
    print("==== Deli KQ August ====")
    punches.extend(fetch_punches(app_key, app_secret, "KQ"))

    people: dict[str, dict] = {}
    for punch in punches:
        user_id = punch["user_id"]
        if not user_id:
            continue
        item = people.setdefault(
            user_id,
            {
                "user_id": user_id,
                "ext_id": punch.get("ext_id"),
                "names": defaultdict(int),
                "empnos": defaultdict(int),
                "punches": 0,
                "first": punch["check_time"],
                "last": punch["check_time"],
                "snowflake": is_snowflake(user_id),
            },
        )
        item["punches"] += 1
        item["first"] = min(item["first"], punch["check_time"])
        item["last"] = max(item["last"], punch["check_time"])
        if punch.get("name"):
            item["names"][punch["name"]] += 1
        if punch.get("empno"):
            item["empnos"][punch["empno"]] += 1
        if punch.get("ext_id"):
            item["ext_id"] = punch["ext_id"]

    proposed: list[dict] = []
    skipped: list[str] = []
    focus_lines: list[str] = []

    forced = {snowflake: (name, empno) for name, empno, snowflake in MANUAL_SNOWFLAKES}
    forced.update({snowflake: (name, empno) for name, snowflake, empno in JULY_CONFLICT})

    for user_id, item in sorted(people.items(), key=lambda pair: pair[1]["punches"], reverse=True):
        names = sorted(item["names"], key=lambda name: (-item["names"][name], name))
        empnos = sorted(item["empnos"], key=lambda empno: (-item["empnos"][empno], empno))
        name = names[0] if names else None
        empno = empnos[0] if empnos else None
        if name in FOCUS or any(focus in names for focus in FOCUS):
            focus_lines.append(
                f"{user_id}\tsnowflake={item['snowflake']}\tname={name}\t"
                f"empno={empno}\tpunches={item['punches']}\t"
                f"{item['first']}..{item['last']}\tnames={dict(item['names'])}\t"
                f"empnos={dict(item['empnos'])}"
            )
        if user_id in forced:
            force_name, force_empno = forced[user_id]
            match = decide(force_name, force_empno, by_name, by_number)
            if match is None:
                skipped.append(f"{user_id} {force_name} {force_empno} forced-unmatched")
                continue
            employee_id, employee_number, display_name, reason = match
            proposed.append(
                {
                    "deli_user_id": user_id,
                    "employee_id": employee_id,
                    "employee_number": employee_number,
                    "display_name": display_name,
                    "reason": "MANUAL_OR_JULY:" + reason,
                    "punches": item["punches"],
                    "punch_name": name,
                    "punch_empno": empno,
                }
            )
            continue
        if not item["snowflake"]:
            skipped.append(
                f"{user_id} {name or '-'} {empno or '-'} short-id-not-bound punches={item['punches']}"
            )
            continue
        match = decide(name, empno, by_name, by_number)
        if match is None:
            skipped.append(
                f"{user_id} {name or '-'} {empno or '-'} unmatched-or-ambiguous punches={item['punches']}"
            )
            continue
        employee_id, employee_number, display_name, reason = match
        existing = bound_by_snowflake.get(user_id)
        if existing and existing[1] != employee_id:
            skipped.append(
                f"{user_id} {name} already-bound-to={existing[2]} {existing[3]}"
            )
            continue
        proposed.append(
            {
                "deli_user_id": user_id,
                "employee_id": employee_id,
                "employee_number": employee_number,
                "display_name": display_name,
                "reason": reason,
                "punches": item["punches"],
                "punch_name": name,
                "punch_empno": empno,
            }
        )

    for name, empno, snowflake in MANUAL_SNOWFLAKES:
        if any(row["deli_user_id"] == snowflake for row in proposed):
            continue
        match = decide(name, empno, by_name, by_number)
        if match is None:
            skipped.append(f"{snowflake} {name} {empno} screenshot-unmatched")
            continue
        employee_id, employee_number, display_name, reason = match
        proposed.append(
            {
                "deli_user_id": snowflake,
                "employee_id": employee_id,
                "employee_number": employee_number,
                "display_name": display_name,
                "reason": "SCREENSHOT:" + reason,
                "punches": people.get(snowflake, {}).get("punches", 0),
                "punch_name": name,
                "punch_empno": empno,
            }
        )

    collapsed: dict[str, dict] = {}
    for row in sorted(proposed, key=lambda item: item["punches"], reverse=True):
        collapsed.setdefault(row["employee_id"], row)
    proposed = list(collapsed.values())

    tsv_path = os.path.join(OUT_DIR, "deli-august-proposed-bindings.tsv")
    with open(tsv_path, "w", encoding="utf-8") as handle:
        handle.write(
            "employee_number\tdisplay_name\tdeli_user_id\treason\tpunches\tpunch_name\tpunch_empno\n"
        )
        for row in sorted(proposed, key=lambda item: item["employee_number"]):
            handle.write(
                f"{row['employee_number']}\t{row['display_name']}\t{row['deli_user_id']}\t"
                f"{row['reason']}\t{row['punches']}\t{row['punch_name']}\t{row['punch_empno']}\n"
            )

    skip_path = os.path.join(OUT_DIR, "deli-august-skipped.txt")
    write_text(skip_path, "\n".join(skipped) + ("\n" if skipped else ""))
    write_text(
        os.path.join(OUT_DIR, "deli-august-focus.txt"),
        "\n".join(focus_lines) + ("\n" if focus_lines else ""),
    )

    sql_path = os.path.join(OUT_DIR, "deli-august-bindings.sql")
    sql_lines = [
        "SET NAMES utf8mb4;",
        "SET time_zone = '+00:00';",
        "SET @apply := IFNULL(@apply, 0);",
        f"SET @source_id := '{SOURCE_ID}';",
        "SET @effective_from := '2026-01-01 00:00:00.000000';",
        "SET @actor_id := (",
        "    SELECT principal.principal_id FROM auth_principal principal",
        "    WHERE principal.principal_id = 'SYSTEM' LIMIT 1",
        ");",
        "DROP TEMPORARY TABLE IF EXISTS tmp_deli_august_bind;",
        "CREATE TEMPORARY TABLE tmp_deli_august_bind (",
        "    display_name VARCHAR(100) NOT NULL,",
        "    deli_user_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,",
        "    target_employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,",
        "    reason VARCHAR(200) NOT NULL,",
        "    PRIMARY KEY (deli_user_id)",
        ") ENGINE=InnoDB;",
    ]
    value_sql = []
    for row in proposed:
        name = row["display_name"].replace("'", "''")
        reason = row["reason"].replace("'", "''")
        value_sql.append(
            f"('{name}', '{row['deli_user_id']}', '{row['employee_number']}', '{reason}')"
        )
    if value_sql:
        sql_lines.append("INSERT INTO tmp_deli_august_bind VALUES")
        sql_lines.append(",\n".join(value_sql) + ";")
    sql_lines.extend(
        [
            """
SELECT 'preview_focus' AS section,
       map.target_employee_number,
       map.display_name,
       map.deli_user_id,
       map.reason
FROM tmp_deli_august_bind map
WHERE map.display_name IN ('马振雯','赵子奇','张衡','张静')
   OR map.target_employee_number IN ('SZSTSX109','SZSZ0002','SZSZ0003','SZST0442')
ORDER BY map.target_employee_number;

DROP TEMPORARY TABLE IF EXISTS tmp_deli_august_ready;
CREATE TEMPORARY TABLE tmp_deli_august_ready (
    display_name VARCHAR(100) NOT NULL,
    deli_user_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    target_employee_number VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    reason VARCHAR(200) NOT NULL,
    employee_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (deli_user_id),
    UNIQUE KEY uq_emp (employee_id)
) ENGINE=InnoDB;

INSERT INTO tmp_deli_august_ready
SELECT map.display_name,
       map.deli_user_id,
       map.target_employee_number,
       map.reason,
       MIN(version.employee_id)
FROM tmp_deli_august_bind map
JOIN employee_version version
  ON version.employee_number = map.target_employee_number
 AND version.display_name = map.display_name
JOIN employee_current_projection projection
  ON projection.current_version_id = version.employee_version_id
 AND projection.employee_id = version.employee_id
WHERE CHAR_LENGTH(map.deli_user_id) >= 16
  AND map.deli_user_id REGEXP '^[0-9]+$'
  AND NOT EXISTS (
        SELECT 1
        FROM employee_source_binding other
        WHERE other.deli_attendance_source_id = @source_id
          AND other.effective_to IS NULL
          AND other.deli_user_id = map.deli_user_id
          AND other.employee_id <> version.employee_id
  )
GROUP BY map.display_name, map.deli_user_id, map.target_employee_number, map.reason
HAVING COUNT(DISTINCT version.employee_id) = 1;

SELECT 'ready_count' AS section, COUNT(*) AS n FROM tmp_deli_august_ready;
SELECT 'ready_focus' AS section, tmp_deli_august_ready.* FROM tmp_deli_august_ready
WHERE display_name IN ('马振雯','赵子奇','张衡','张静');

UPDATE employee_source_binding binding
JOIN tmp_deli_august_ready ready
  ON ready.employee_id = binding.employee_id
SET binding.deli_user_id = ready.deli_user_id,
    binding.deli_ext_id = ready.deli_user_id,
    binding.deli_employee_num = ready.target_employee_number,
    binding.deli_attendance_source_id = @source_id,
    binding.binding_status = 'CONFIRMED',
    binding.confirmation_ref = CONCAT('AUGUST_PUNCH_USER_ID:', ready.deli_user_id),
    binding.deli_confirmed_by = @actor_id,
    binding.deli_confirmed_at = UTC_TIMESTAMP(6),
    binding.deli_change_reason = CONCAT('8月打卡 user_id 雪花按花名册确认绑定 ', ready.reason),
    binding.effective_from = IF(
        binding.effective_from IS NULL OR binding.effective_from > @effective_from,
        @effective_from,
        binding.effective_from),
    binding.deli_row_version = binding.deli_row_version + 1
WHERE @apply = 1
  AND binding.effective_to IS NULL;

INSERT INTO employee_source_binding (
    binding_id, employee_id, deli_user_id, deli_ext_id, deli_employee_num,
    deli_attendance_source_id, binding_status, effective_from, effective_to,
    source, confirmation_ref, deli_row_version, deli_confirmed_by,
    deli_confirmed_at, deli_change_reason
)
SELECT UUID(), ready.employee_id, ready.deli_user_id, ready.deli_user_id,
       ready.target_employee_number, @source_id, 'CONFIRMED', @effective_from,
       NULL, 'DELI', CONCAT('AUGUST_PUNCH_USER_ID:', ready.deli_user_id), 1,
       @actor_id, UTC_TIMESTAMP(6),
       CONCAT('8月打卡 user_id 雪花按花名册确认绑定 ', ready.reason)
FROM tmp_deli_august_ready ready
WHERE @apply = 1
  AND NOT EXISTS (
        SELECT 1
        FROM employee_source_binding binding
        WHERE binding.employee_id = ready.employee_id
          AND binding.effective_to IS NULL
      );

INSERT INTO deli_employee_binding_revision (
    deli_employee_binding_revision_id, binding_id, attendance_source_id,
    employee_id, deli_ext_id, deli_user_id, revision_action,
    binding_row_version, confirmation_ref, changed_by, changed_at, change_reason
)
SELECT UUID(), binding.binding_id, binding.deli_attendance_source_id,
       binding.employee_id, binding.deli_ext_id, binding.deli_user_id,
       'CONFIRMED', binding.deli_row_version, binding.confirmation_ref,
       @actor_id, UTC_TIMESTAMP(6), binding.deli_change_reason
FROM employee_source_binding binding
JOIN tmp_deli_august_ready ready
  ON ready.employee_id = binding.employee_id
WHERE @apply = 1
  AND binding.effective_to IS NULL
  AND binding.confirmation_ref = CONCAT('AUGUST_PUNCH_USER_ID:', ready.deli_user_id);

SELECT 'applied_focus' AS section,
       version.employee_number,
       version.display_name,
       CONVERT(binding.deli_user_id USING utf8mb4) AS deli_user_id,
       CONVERT(binding.binding_status USING utf8mb4) AS binding_status,
       CONVERT(binding.confirmation_ref USING utf8mb4) AS confirmation_ref
FROM employee_source_binding binding
JOIN employee_current_projection projection
  ON projection.employee_id = binding.employee_id
JOIN employee_version version
  ON version.employee_version_id = projection.current_version_id
WHERE binding.effective_to IS NULL
  AND version.display_name IN ('马振雯','赵子奇','张衡','张静');
"""
        ]
    )
    write_text(sql_path, "\n".join(sql_lines))

    print("==== summary ====")
    print(f"august_punches={len(punches)}")
    print(f"distinct_punch_user_ids={len(people)}")
    print(f"snowflake_user_ids={sum(1 for item in people.values() if item['snowflake'])}")
    print(f"short_user_ids={sum(1 for item in people.values() if not item['snowflake'])}")
    print(f"proposed_bindings={len(proposed)}")
    print(f"skipped={len(skipped)}")
    print("focus punch identities:")
    print("\n".join(focus_lines) if focus_lines else "(none in CHECKIN/KQ August)")
    print(f"wrote {tsv_path}")
    print(f"wrote {skip_path}")
    print(f"wrote {sql_path}")

    if APPLY:
        print("==== APPLY bindings ====")
        with open(sql_path, encoding="utf-8") as handle:
            sql_text = handle.read().replace(
                "SET @apply := IFNULL(@apply, 0);",
                "SET @apply := 1;",
            )
        subprocess.run(
            [
                "mysql",
                "--default-character-set=utf8mb4",
                "-uroot",
                "shenzhou_hr",
            ],
            check=True,
            input=sql_text,
            text=True,
            env=os.environ.copy(),
        )
        print("bindings applied. Next: identity replay August, recalculate:false")
    else:
        print("preview only. To write bindings:")
        print(f"  APPLY=1 python3 {os.path.abspath(__file__)}")
        print("or after reviewing SQL:")
        print(
            "  mysql --default-character-set=utf8mb4 -uroot shenzhou_hr "
            "-e \"SET @apply := 1; SOURCE /root/deli-august-bindings.sql;\""
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        sys.stderr.write(exc.stderr or str(exc))
        raise SystemExit(1)
