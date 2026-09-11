#!/usr/bin/env python3
"""2026-09-01 年假/调休额度重算并写回 HR。

口径：
- 年假汇总标注是天，实际按小时用（不再 ×8）
- 调休汇总剩余按小时
- OA 已审核 state=3，单据开始时间 [2026-08-01, 2026-09-01)
- 年假/调休请假小时：系统小时为空则天数 ×8
- 调休加班按加班明细小时
- 不在汇总里的人按 0 再加减 OA；负数保留
- 跳过 zhanglana
- 忽略此前 CUTOVER/UNITFIX，把 2026 账户余额调到本次目标

材料可和脚本放一起，或都放 /root：
  01_年假汇总.md  02_调休汇总.md  03_八月入职.tsv  roster-empno-name.tsv

服务器：
  python3 /root/refresh_leave_opening_20260901.py
"""
from __future__ import annotations

import csv
import os
import re
import subprocess
import sys
import tempfile
from collections import defaultdict
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path

HERE = Path(__file__).resolve().parent
INPUT_DIRS = [
    Path("/root"),
    HERE,
    Path.cwd(),
]
OUT_DIR = Path(os.environ.get("OUT_DIR", "/root/leave-opening-20260901"))
FROM_AT = os.environ.get("FROM_AT", "2026-08-01 00:00:00")
TO_AT = os.environ.get("TO_AT", "2026-09-01 00:00:00")
REQUEST_PREFIX = "CUTOVER20260901"
ACCOUNT_YEAR = 2026
Q = Decimal("0.01")
H8 = Decimal("8")
EMP_RE = re.compile(r"^[A-Za-z]{2,6}\d{3,}$")
SKIP_NAMES = {"zhanglana"}
NAME_CODE = {
    "张衡": "SZSZ0003",
    "赵子奇": "SZSZ0002",
    "徐赛杰": "SZJN0031",
}
ANNUAL_ENUM = "5959840635913392019"
TIMEOFF_LEAVE_ENUM = "-1336039252273314314"
TIMEOFF_OT_ENUM = "5912806790045781226"

MYSQL_BIN = os.environ.get("MYSQL_BIN", "/www/server/mysql/bin/mysql")
HR_HOST = os.environ.get("HR_HOST", "127.0.0.1")
HR_PORT = os.environ.get("HR_PORT", "3306")
HR_DB = os.environ.get("HR_DB", "shenzhou_hr")
HR_USER = os.environ.get("HR_USER", "root")
HR_PWD = os.environ.get("HR_PWD", "e0e17f3df673a9f8")
OA_HOST = os.environ.get("OA_HOST", "192.168.2.169")
OA_PORT = os.environ.get("OA_PORT", "3308")
OA_DB = os.environ.get("OA_DB", "szoa")
OA_USER = os.environ.get("OA_USER", "")
OA_PWD = os.environ.get("OA_PWD", "")


def die(msg: str) -> None:
    print("ERROR:", msg, file=sys.stderr)
    raise SystemExit(1)


def D(v) -> Decimal:
    if v is None:
        return Decimal("0")
    s = str(v).strip().replace(",", "")
    if s in ("", "None", "NULL", r"\N", "nan", "NaN", "/"):
        return Decimal("0")
    try:
        return Decimal(s)
    except InvalidOperation:
        raise ValueError(s)


def q2(v: Decimal) -> Decimal:
    return v.quantize(Q, rounding=ROUND_HALF_UP)


def emp_ok(code: str) -> bool:
    return bool(EMP_RE.fullmatch(code or ""))


def run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=check, text=True, capture_output=True)


def mysql_cmd(host, port, user, password, db) -> list[str]:
    bin_path = MYSQL_BIN if Path(MYSQL_BIN).exists() else "mysql"
    return [
        bin_path,
        f"-h{host}",
        f"-P{port}",
        f"-u{user}",
        f"--password={password}",
        "--default-character-set=utf8mb4",
        "--batch",
        "--raw",
        db,
    ]


def mysql_query(cmd: list[str], sql: str, skip_names: bool = False) -> str:
    args = list(cmd)
    if skip_names:
        args.append("--skip-column-names")
    args.extend(["-e", sql])
    proc = run(args)
    if proc.returncode != 0:
        die(proc.stderr.strip() or "mysql failed")
    return proc.stdout.replace("\r", "")


def parse_start_prod(path: Path) -> dict[str, str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    out: dict[str, str] = {}
    for key in ("jdbc-url", "username", "password"):
        for pat in (
            rf"--shenzhouhr\.integrations\.oa-mysql\.{re.escape(key)}='([^']*)'",
            rf"--shenzhouhr\.integrations\.oa-mysql\.{re.escape(key)}=(\S+)",
        ):
            m = re.search(pat, text)
            if m:
                out[key] = m.group(1).strip().strip("'").strip('"')
                break
    return out


def load_oa_creds() -> None:
    global OA_HOST, OA_PORT, OA_DB, OA_USER, OA_PWD
    for f in (
        Path("/opt/shenzhouhr/start-prod.sh"),
        Path("/opt/shenzhouhr/conf/start-prod.sh"),
        Path("/etc/shenzhouhr/shenzhouhr.env"),
    ):
        if not f.is_file():
            continue
        cfg = parse_start_prod(f)
        jdbc = cfg.get("jdbc-url", "")
        if jdbc:
            m = re.search(r"jdbc:mysql://([^:/]+):(\d+)/([^?]+)", jdbc)
            if m:
                OA_HOST, OA_PORT, OA_DB = m.group(1), m.group(2), m.group(3)
        if not OA_USER:
            OA_USER = cfg.get("username", "")
        if not OA_PWD:
            OA_PWD = cfg.get("password", "")
        if OA_USER and OA_PWD:
            break
    if not OA_USER:
        OA_USER = "kaoqin2026"
    if not OA_PWD:
        OA_PWD = HR_PWD


def parse_md(path: Path) -> list[list[str]]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not cells or cells[0] in ("序号", ":---:"):
            continue
        if set(cells[0]) <= set(":-"):
            continue
        rows.append(cells)
    return rows


def load_tsv_text(text: str) -> list[dict[str, str]]:
    lines = [ln for ln in text.splitlines() if ln.strip()]
    if not lines:
        return []
    header = lines[0].split("\t")
    return [dict(zip(header, ln.split("\t"))) for ln in lines[1:]]


def find_input(name: str) -> Path:
    for folder in INPUT_DIRS:
        candidate = folder / name
        if candidate.is_file():
            return candidate
    die(f"缺少 {name}，请放到 /root 或脚本同目录")


def load_local_tsv(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    return load_tsv_text(path.read_text(encoding="utf-8"))


def hours_from_doc(system_hours, days) -> Decimal:
    raw = "" if system_hours is None else str(system_hours).strip()
    if raw not in ("", "NULL", r"\N", "None"):
        h = D(raw)
        if h is not None:
            return q2(h)
    return q2(D(days) * H8)


def resolve_code(code: str, name: str, roster: dict[str, str], exceptions: list) -> str | None:
    name = (name or "").strip()
    code = (code or "").strip()
    if name in SKIP_NAMES:
        exceptions.append(("跳过", name, code))
        return None
    if emp_ok(code):
        return code
    if name in NAME_CODE:
        return NAME_CODE[name]
    if name in roster:
        return roster[name]
    exceptions.append(("工号无法解析", code, name))
    return None


def main() -> None:
    global MYSQL_BIN
    if not Path(MYSQL_BIN).is_file():
        found = run(["bash", "-lc", "command -v mysql"], check=False)
        if found.returncode != 0 or not found.stdout.strip():
            die("找不到 mysql 客户端")
        MYSQL_BIN = found.stdout.strip()

    annual_md = find_input("01_年假汇总.md")
    timeoff_md = find_input("02_调休汇总.md")
    hires_tsv = find_input("03_八月入职.tsv")
    roster_tsv = find_input("roster-empno-name.tsv")
    print("材料:")
    for p in (annual_md, timeoff_md, hires_tsv, roster_tsv):
        print(" ", p)

    load_oa_creds()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    os.chmod(OUT_DIR, 0o700)

    hr = mysql_cmd(HR_HOST, HR_PORT, HR_USER, HR_PWD, HR_DB)
    oa = mysql_cmd(OA_HOST, OA_PORT, OA_USER, OA_PWD, OA_DB)
    mysql_query(hr, "SELECT 1", skip_names=True)
    mysql_query(oa, "SELECT 1", skip_names=True)

    def has_col(table: str, col: str) -> bool:
        n = mysql_query(
            oa,
            "SELECT COUNT(*) FROM information_schema.columns "
            f"WHERE table_schema=DATABASE() AND table_name='{table}' "
            f"AND column_name='{col}'",
            skip_names=True,
        ).strip()
        return n == "1"

    leave_h = "m.field0103" if has_col("formmain_0170", "field0103") else "NULL"
    revoke_h = "r.field0107" if has_col("formmain_0370", "field0107") else "NULL"
    ot_h = "s.field0101" if has_col("formson_0172", "field0101") else "NULL"

    print(f"HR={HR_USER}@{HR_HOST}:{HR_PORT}/{HR_DB}")
    print(f"OA={OA_USER}@{OA_HOST}:{OA_PORT}/{OA_DB}")
    print(f"WINDOW=[{FROM_AT}, {TO_AT})")
    print(f"field0103={leave_h} field0107={revoke_h} field0101={ot_h}")

    exceptions: list[tuple[str, str, str]] = []
    roster: dict[str, str] = {}
    for row in load_local_tsv(roster_tsv):
        name = (row.get("name") or "").strip()
        code = (row.get("employee_number") or "").strip()
        if name and emp_ok(code) and name not in roster:
            roster[name] = code

    annual_excel: dict[str, dict] = {}
    for cells in parse_md(annual_md):
        if len(cells) < 7:
            continue
        code = resolve_code(cells[1], cells[4], roster, exceptions)
        if not code:
            continue
        hours = D(cells[6])
        annual_excel[code] = {"name": cells[4].strip(), "excel_hours": q2(hours)}

    timeoff_excel: dict[str, dict] = {}
    for cells in parse_md(timeoff_md):
        if len(cells) < 7:
            continue
        code = resolve_code(cells[1], cells[3], roster, exceptions)
        if not code:
            continue
        remain_raw = cells[6].strip()
        if remain_raw:
            remain = D(remain_raw)
        else:
            remain = D(cells[4]) - D(cells[5])
        timeoff_excel[code] = {"name": cells[3].strip(), "excel_hours": q2(remain)}

    hires: dict[str, str] = {}
    for row in load_local_tsv(hires_tsv):
        code = (row.get("employee_number") or "").strip()
        name = (row.get("name") or "").strip()
        if emp_ok(code):
            hires[code] = name
            annual_excel.setdefault(code, {"name": name, "excel_hours": Decimal("0")})
            timeoff_excel.setdefault(code, {"name": name, "excel_hours": Decimal("0")})

    def dump_oa(name: str, sql: str) -> list[dict[str, str]]:
        text = mysql_query(oa, sql)
        (OUT_DIR / name).write_text(text, encoding="utf-8")
        return load_tsv_text(text)

    annual_leave_rows = dump_oa(
        "01_annual_leave_docs.tsv",
        f"""
SELECT
  m.field0084 AS employee_code, e.showvalue AS leave_type, m.field0097 AS leave_serial,
  m.field0086 AS start_dt, m.field0087 AS end_dt, m.field0088 AS days,
  {leave_h} AS system_hours, cs.finish_date, cs.state, m.id AS form_id
FROM formmain_0170 m
JOIN col_summary cs ON cs.form_recordid = m.id
LEFT JOIN ctp_enum_item e ON e.id = m.field0089
WHERE cs.state = 3
  AND m.field0089 = {ANNUAL_ENUM}
  AND m.field0086 >= '{FROM_AT}'
  AND m.field0086 < '{TO_AT}'
ORDER BY m.field0084, m.field0086
""",
    )
    timeoff_leave_rows = dump_oa(
        "02_time_off_leave_docs.tsv",
        f"""
SELECT
  m.field0084 AS employee_code, e.showvalue AS leave_type, m.field0097 AS leave_serial,
  m.field0086 AS start_dt, m.field0087 AS end_dt, m.field0088 AS days,
  {leave_h} AS system_hours, cs.finish_date, cs.state, m.id AS form_id
FROM formmain_0170 m
JOIN col_summary cs ON cs.form_recordid = m.id
LEFT JOIN ctp_enum_item e ON e.id = m.field0089
WHERE cs.state = 3
  AND m.field0089 = {TIMEOFF_LEAVE_ENUM}
  AND m.field0086 >= '{FROM_AT}'
  AND m.field0086 < '{TO_AT}'
ORDER BY m.field0084, m.field0086
""",
    )
    ot_rows = dump_oa(
        "03_time_off_ot_docs.tsv",
        f"""
SELECT
  s.field0094 AS employee_code, s.field0100 AS start_dt, s.field0099 AS end_dt,
  {ot_h} AS duration_hours, cs.finish_date, cs.state, s.id AS formson_id, m.id AS formmain_id
FROM formson_0172 s
JOIN formmain_0171 m ON m.id = s.formmain_id
JOIN col_summary cs ON cs.form_recordid = m.id
WHERE cs.state = 3
  AND s.field0096 = {TIMEOFF_OT_ENUM}
  AND s.field0100 >= '{FROM_AT}'
  AND s.field0100 < '{TO_AT}'
ORDER BY s.field0094, s.field0100
""",
    )
    rev_rows = dump_oa(
        "04_revocation_docs.tsv",
        f"""
SELECT
  r.field0084 AS employee_code, r.field0099 AS original_leave_serial,
  leave_e.showvalue AS original_leave_type, leave_m.field0089 AS original_leave_enum,
  r.field0086 AS actual_start, r.field0087 AS actual_end, r.field0088 AS days,
  {revoke_h} AS return_hours, cs.finish_date, cs.state, r.id AS form_id
FROM formmain_0370 r
JOIN col_summary cs ON cs.form_recordid = r.id
JOIN formmain_0170 leave_m ON leave_m.field0097 = r.field0099
LEFT JOIN ctp_enum_item leave_e ON leave_e.id = leave_m.field0089
WHERE cs.state = 3
  AND leave_m.field0089 IN ({ANNUAL_ENUM}, {TIMEOFF_LEAVE_ENUM})
  AND r.field0086 >= '{FROM_AT}'
  AND r.field0086 < '{TO_AT}'
ORDER BY r.field0084, r.field0086
""",
    )

    def sum_hours(rows, code_key, hours_fn):
        acc: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
        for row in rows:
            raw_code = (row.get(code_key) or "").strip()
            code = raw_code if emp_ok(raw_code) else None
            if not code:
                exceptions.append(("OA工号非法", raw_code, str(row.get("leave_serial", ""))))
                continue
            acc[code] += hours_fn(row)
        return acc

    annual_used = sum_hours(
        annual_leave_rows, "employee_code",
        lambda r: hours_from_doc(r.get("system_hours"), r.get("days")),
    )
    timeoff_used = sum_hours(
        timeoff_leave_rows, "employee_code",
        lambda r: hours_from_doc(r.get("system_hours"), r.get("days")),
    )
    ot_credit = sum_hours(
        ot_rows, "employee_code",
        lambda r: q2(D(r.get("duration_hours"))),
    )
    annual_return: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    timeoff_return: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    for row in rev_rows:
        code = (row.get("employee_code") or "").strip()
        if not emp_ok(code):
            exceptions.append(("销假工号非法", code, ""))
            continue
        h = hours_from_doc(row.get("return_hours"), row.get("days"))
        enum = str(row.get("original_leave_enum") or "").strip()
        label = str(row.get("original_leave_type") or "")
        if enum == ANNUAL_ENUM or label in ("年假", "年休假"):
            annual_return[code] += h
        elif enum == TIMEOFF_LEAVE_ENUM or label in ("调休", "调休假"):
            timeoff_return[code] += h
        else:
            exceptions.append(("销假假别未识别", code, enum + " " + label))

    targets: list[tuple[str, str, Decimal]] = []
    annual_rows = []
    for code in sorted(set(annual_excel) | set(annual_used) | set(annual_return)):
        base = annual_excel.get(code, {"name": hires.get(code, ""), "excel_hours": Decimal("0")})
        used = annual_used.get(code, Decimal("0"))
        ret = annual_return.get(code, Decimal("0"))
        opening = q2(base["excel_hours"] - used + ret)
        flag = ""
        if code not in annual_excel:
            flag = "OA有8月年假但汇总无此人"
            exceptions.append((flag, code, ""))
        if opening < 0:
            exceptions.append(("年假滚后为负", code, str(opening)))
        annual_rows.append([code, base["name"], base["excel_hours"], used, ret, opening, flag])
        if opening != 0:
            targets.append((code, "ANNUAL_LEAVE", opening))
        else:
            targets.append((code, "ANNUAL_LEAVE", Decimal("0")))

    timeoff_rows = []
    for code in sorted(set(timeoff_excel) | set(timeoff_used) | set(ot_credit) | set(timeoff_return) | set(hires)):
        base = timeoff_excel.get(code, {"name": hires.get(code, annual_excel.get(code, {}).get("name", "")), "excel_hours": Decimal("0")})
        used = timeoff_used.get(code, Decimal("0"))
        credit = ot_credit.get(code, Decimal("0"))
        ret = timeoff_return.get(code, Decimal("0"))
        opening = q2(base["excel_hours"] + credit - used + ret)
        flag = ""
        if code not in timeoff_excel and (used or credit or ret):
            flag = "OA有8月调休变动但汇总无此人"
            exceptions.append((flag, code, ""))
        if opening < 0:
            exceptions.append(("调休滚后为负", code, str(opening)))
        timeoff_rows.append([code, base["name"], base["excel_hours"], credit, used, ret, opening, flag])
        targets.append((code, "TIME_OFF", opening))

    def write_tsv(path: Path, header, rows):
        with path.open("w", encoding="utf-8", newline="") as f:
            w = csv.writer(f, delimiter="\t", lineterminator="\n")
            w.writerow(header)
            w.writerows(rows)

    write_tsv(OUT_DIR / "05_annual_opening.tsv",
              ["employee_code", "name", "excel_hours", "aug_used_hours", "aug_return_hours", "opening_hours", "flag"],
              annual_rows)
    write_tsv(OUT_DIR / "06_time_off_opening.tsv",
              ["employee_code", "name", "excel_hours", "aug_ot_credit_hours", "aug_used_hours", "aug_return_hours", "opening_hours", "flag"],
              timeoff_rows)
    write_tsv(OUT_DIR / "07_exceptions.tsv",
              ["kind", "employee_code", "detail"], exceptions)

    nonzero = [(c, t, h) for c, t, h in targets if h != 0]
    values = ",\n".join(f"('{c}','{t}',{h})" for c, t, h in targets)
    sql = f"""SET NAMES utf8mb4;
START TRANSACTION;
CREATE TEMPORARY TABLE tmp_sep1_target (
    employee_number VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    account_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_hours DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (employee_number, account_type)
);
INSERT INTO tmp_sep1_target (employee_number, account_type, target_hours) VALUES
{values};

DROP TEMPORARY TABLE IF EXISTS tmp_sep1_resolved;
CREATE TEMPORARY TABLE tmp_sep1_resolved AS
SELECT
    src.employee_number,
    src.account_type,
    src.target_hours,
    unique_employment.employee_id,
    unique_employment.employment_period_id,
    unique_employment.company_id,
    CASE WHEN src.account_type = 'ANNUAL_LEAVE' THEN 'ANNUAL_LEAVE_DEFAULT_V1'
         ELSE 'TIME_OFF_DEFAULT_V1' END AS policy_version_id,
    CONCAT('{REQUEST_PREFIX}:', src.account_type, ':', src.employee_number) AS request_id
FROM tmp_sep1_target src
JOIN (
    SELECT employee.employee_number, employee.employee_id,
           MAX(employment.employment_period_id) AS employment_period_id,
           employee.company_id
    FROM employee employee
    JOIN employment_assignment employment
      ON employment.employee_id = employee.employee_id
     AND employment.current_version_marker = 1
     AND employment.record_status = 'ACTIVE'
     AND employment.effective_from <= CURRENT_TIMESTAMP(6)
     AND (employment.effective_to IS NULL OR employment.effective_to > CURRENT_TIMESTAMP(6))
    WHERE employee.employment_status = 'ACTIVE'
    GROUP BY employee.employee_number, employee.employee_id, employee.company_id
    HAVING COUNT(*) = 1
) unique_employment ON unique_employment.employee_number = src.employee_number;

ALTER TABLE tmp_sep1_resolved ADD PRIMARY KEY (employee_number, account_type);

INSERT INTO time_account (
    time_account_id, employee_id, employment_period_id, company_id,
    account_type, account_year, balance_hours, policy_version_id,
    row_version, created_at, updated_at
)
SELECT UUID(), resolved.employee_id, resolved.employment_period_id, resolved.company_id,
       resolved.account_type, {ACCOUNT_YEAR}, 0.00, resolved.policy_version_id,
       0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM tmp_sep1_resolved resolved
LEFT JOIN time_account existing
  ON existing.employee_id = resolved.employee_id
 AND existing.employment_period_id = resolved.employment_period_id
 AND existing.account_type = resolved.account_type
 AND existing.account_year = {ACCOUNT_YEAR}
WHERE existing.time_account_id IS NULL;

DROP TEMPORARY TABLE IF EXISTS tmp_sep1_delta;
CREATE TEMPORARY TABLE tmp_sep1_delta AS
SELECT
    resolved.employee_number,
    resolved.account_type,
    resolved.target_hours,
    account.time_account_id,
    account.policy_version_id,
    account.balance_hours AS current_hours,
    (resolved.target_hours - account.balance_hours) AS delta_hours,
    resolved.request_id
FROM tmp_sep1_resolved resolved
JOIN time_account account
  ON account.employee_id = resolved.employee_id
 AND account.employment_period_id = resolved.employment_period_id
 AND account.account_type = resolved.account_type
 AND account.account_year = {ACCOUNT_YEAR}
WHERE (resolved.target_hours - account.balance_hours) <> 0;

ALTER TABLE tmp_sep1_delta ADD PRIMARY KEY (employee_number, account_type);

INSERT IGNORE INTO time_account_ledger_entry (
    time_account_ledger_entry_id, time_account_id, sequence_no, entry_type,
    amount_hours, source_type, source_id, business_date, effective_from, expires_on,
    policy_version_id, period_version_id, close_snapshot_id, request_id,
    reversal_of_entry_id, actor_id, occurred_at
)
SELECT
    UUID(), d.time_account_id, COALESCE(seq.max_seq, 0) + 1, 'ADJUSTMENT',
    d.delta_hours, 'HR_OPENING_IMPORT', d.request_id,
    DATE '2026-09-01', DATE '2026-09-01', DATE '2026-12-31',
    d.policy_version_id, NULL, NULL, d.request_id,
    NULL, 'SYSTEM', CURRENT_TIMESTAMP(6)
FROM tmp_sep1_delta d
LEFT JOIN (
    SELECT time_account_id, MAX(sequence_no) AS max_seq
    FROM time_account_ledger_entry
    GROUP BY time_account_id
) seq ON seq.time_account_id = d.time_account_id;

UPDATE time_account account
JOIN (
    SELECT entry.time_account_id, SUM(entry.amount_hours) AS ledger_hours
    FROM time_account_ledger_entry entry
    GROUP BY entry.time_account_id
) ledger ON ledger.time_account_id = account.time_account_id
JOIN tmp_sep1_resolved resolved
  ON resolved.employee_id = account.employee_id
 AND resolved.employment_period_id = account.employment_period_id
 AND resolved.account_type = account.account_type
SET
    account.balance_hours = ledger.ledger_hours,
    account.row_version = account.row_version + 1,
    account.updated_at = CURRENT_TIMESTAMP(6)
WHERE account.account_year = {ACCOUNT_YEAR};

SELECT 'applied_adjustments' AS report_kind, COUNT(*) AS n
FROM time_account_ledger_entry
WHERE request_id LIKE '{REQUEST_PREFIX}:%';

SELECT 'unmatched_employee_number' AS report_kind,
       src.employee_number, src.account_type, src.target_hours
FROM tmp_sep1_target src
LEFT JOIN tmp_sep1_resolved resolved
  ON resolved.employee_number = src.employee_number
 AND resolved.account_type = src.account_type
WHERE resolved.employee_number IS NULL
ORDER BY src.account_type, src.employee_number;

SELECT 'sample_after' AS report_kind,
       employee.employee_number, employee.display_name,
       account.account_type, account.balance_hours,
       ROUND(account.balance_hours / 8, 2) AS balance_days
FROM time_account account
JOIN employee employee ON employee.employee_id = account.employee_id
WHERE account.account_year = {ACCOUNT_YEAR}
  AND account.account_type IN ('ANNUAL_LEAVE','TIME_OFF')
  AND employee.employee_number IN (
        'SZST0000','SZST0010','SZJN0002','SZJN0006','SZJN0010','SZSZ0002','SZSZ0003','SZST0709'
      )
ORDER BY employee.employee_number, account.account_type;

SELECT 'negative_after' AS report_kind,
       employee.employee_number, employee.display_name,
       account.account_type, account.balance_hours,
       ROUND(account.balance_hours / 8, 2) AS balance_days
FROM time_account account
JOIN employee employee ON employee.employee_id = account.employee_id
WHERE account.account_year = {ACCOUNT_YEAR}
  AND account.account_type IN ('ANNUAL_LEAVE','TIME_OFF')
  AND account.balance_hours < 0
ORDER BY account.account_type, account.balance_hours, employee.employee_number;

COMMIT;
"""
    sql_path = OUT_DIR / "apply_20260901.sql"
    sql_path.write_text(sql, encoding="utf-8")

    print(f"年假目标行 {len(annual_rows)} 调休目标行 {len(timeoff_rows)} 非零 {len(nonzero)} 异常 {len(exceptions)}")
    print(f"SQL {sql_path}")
    print("==== 写回 HR ====")
    proc = subprocess.run(
        hr + ["--table"],
        input=sql,
        text=True,
        capture_output=True,
    )
    sys.stdout.write(proc.stdout)
    if proc.returncode != 0:
        die(proc.stderr or "apply failed")
    print("完成。明细在", OUT_DIR)


if __name__ == "__main__":
    main()
