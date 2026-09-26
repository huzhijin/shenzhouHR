#!/usr/bin/env bash
# Compare Deli 考勤月报 + 月度汇总表 with:
#   1) live month-matrix API  = 前台查询报表同一份钉住结果
#   2) MySQL daily_fact       = 仅宝塔本机有库时自动加一层（本机 Mac 会跳过）
#
# Usage（本机对前台）:
#   BASE_URL=http://58.220.159.170:23273 \
#   USERNAME=szsc_admin_... \
#   PASSWORD='...' \
#   COMPANY_ID=41000000-0000-0000-0000-000000000003 \
#   PERIOD=2026-08 \
#   bash deploy/baota/scripts/compare-deli-month-report.sh \
#     "/path/to/考勤月报-....xlsx" \
#     "/path/to/月度汇总表_....xlsx"
#
# 在宝塔 /root 跑时再加库对照（默认连 127.0.0.1 的 shenzhou_hr）:
#   BASE_URL=http://127.0.0.1:9090 ... bash /root/compare-deli-month-report.sh 月报.xlsx 汇总.xlsx
#
# 考勤月报是主对照（日格上班/下班/漏刷）。月度汇总表对照上下班时刻，
# 没有工号、下一行不是下班、或整天都空的行会跳过。
#
# Prints:
#   DATE_SHIFT     得力日历日 != 我们 firstPunch 所在日
#   DELI_ONLY      得力有时刻，我们没有
#   OURS_ONLY      我们有时刻，得力该格为空（非休息空）
#   TIME_DIFF      同一日上班时刻不一致
#   MISS_MISMATCH  得力漏刷，我们该日有下班卡且 missingPunches=0
#   CHECK_WU       吴根银 10 日专项
#   SUMMARY_SKIP   月度汇总表跳过的空行人数
set -euo pipefail

XLSX="${1:?need Deli 考勤月报 xlsx path}"
SUMMARY_XLSX="${2:-}"
BASE_URL="${HR_BASE_URL:-${BASE_URL:?set BASE_URL or HR_BASE_URL}}"
USERNAME="${HR_USER:-${USERNAME:?set USERNAME or HR_USER}}"
PASSWORD="${HR_PASSWORD:-${PASSWORD:?set PASSWORD or HR_PASSWORD}}"
COMPANY_ID="${COMPANY_ID:?set COMPANY_ID}"
PERIOD="${PERIOD:-2026-08}"
OUTPUT="${OUTPUT:-$PWD/compare-deli-${PERIOD}.tsv}"

export BASE_URL USERNAME PASSWORD HR_PASSWORD COMPANY_ID PERIOD OUTPUT XLSX SUMMARY_XLSX MYSQL_PWD HR_DB MYSQL_HOST HR_MYSQL
python3 - "$XLSX" "$SUMMARY_XLSX" <<'PY'
import json, os, re, shutil, subprocess, sys, time, urllib.error, urllib.request, http.cookiejar
from datetime import datetime, timezone, timedelta, date as date_cls
from openpyxl import load_workbook

base = os.environ["BASE_URL"]
user = os.environ["USERNAME"]
password = os.environ.get("HR_PASSWORD") or os.environ["PASSWORD"]
company_id = os.environ["COMPANY_ID"]
period = os.environ.get("PERIOD", "2026-08")
output = os.environ.get("OUTPUT", "compare-deli.tsv")
xlsx, summary = sys.argv[1:3]
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
shanghai = timezone(timedelta(hours=8))
year, month = (int(part) for part in period.split("-"))
clock_re = re.compile(r"(\d{1,2}:\d{2})")


def api(method, path, body=None, timeout=90, retries=4):
    data = None if body is None else json.dumps(body).encode()
    last_error = None
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(
            base.rstrip("/") + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with opener.open(req, timeout=timeout) as resp:
                raw = resp.read()
                return json.loads(raw.decode()) if raw else None
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:300]
            if exc.code == 401 and path != "/api/v1/auth/login" and attempt < retries:
                print(f"session expired, re-login then retry {path}")
                api("POST", "/api/v1/auth/login", {"username": user, "password": password})
                last_error = RuntimeError(f"HTTP {exc.code} {path} {detail}")
                time.sleep(1)
                continue
            raise RuntimeError(f"HTTP {exc.code} {path} {detail}") from exc
        except (TimeoutError, urllib.error.URLError) as exc:
            last_error = exc
            print(f"timeout {path} try {attempt}/{retries}")
            time.sleep(2 * attempt)
    raise RuntimeError(f"timeout {path}") from last_error


def clock(iso):
    if not iso:
        return None
    instant = datetime.fromisoformat(str(iso).replace("Z", "+00:00"))
    return instant.astimezone(shanghai).strftime("%H:%M")


def parse_clock(value):
    if value is None or value == "" or value == "-":
        return None
    if value == "漏刷":
        return "MISS"
    text = str(value).strip()
    match = clock_re.search(text)
    if not match:
        return None
    hour, minute = match.group(1).split(":")
    return f"{int(hour):02d}:{minute}"


def prev_iso(iso):
    y, m, d = (int(part) for part in iso.split("-"))
    return (date_cls(y, m, d) - timedelta(days=1)).isoformat()


def parse_month_report(path):
    wb = load_workbook(path, data_only=True)
    ws = wb.active
    header = [cell.value for cell in next(ws.iter_rows(min_row=4, max_row=4))]
    date_cols = []
    for idx, name in enumerate(header):
        if isinstance(name, str) and "/" in name and name[:2].isdigit():
            day = int(name.split("/")[0])
            date_cols.append((idx, f"{year}-{month:02d}-{day:02d}"))
    people = {}
    skipped = 0
    rows = list(ws.iter_rows(min_row=6, values_only=True))
    i = 0
    while i < len(rows):
        row = rows[i]
        empno = str(row[1]).strip() if row and row[1] else ""
        name = row[2] if row else None
        if name:
            out = rows[i + 1] if i + 1 < len(rows) else None
            if not empno:
                skipped += 1
                i += 2
                continue
            days = {}
            for idx, iso in date_cols:
                inn = row[idx] if row and idx < len(row) else None
                off = out[idx] if out and idx < len(out) else None
                days[iso] = (parse_clock(inn), parse_clock(off), inn, off)
            people[empno] = {
                "name": name,
                "days": days,
                "late": row[5],
                "early": row[6],
                "miss": row[7],
            }
            i += 2
        else:
            i += 1
    wb.close()
    return people, skipped, [iso for _, iso in date_cols]


def parse_summary(path):
    if not path:
        return {}, 0, []
    wb = load_workbook(path, data_only=True)
    ws = wb.active
    header = [ws.cell(2, col).value for col in range(1, ws.max_column + 1)]
    date_cols = []
    for idx, name in enumerate(header):
        if not isinstance(name, str):
            continue
        text = name.replace("\n", " ")
        match = re.search(r"(\d{2})-(\d{2})-(\d{2})", text)
        if not match:
            continue
        century, mon, day = match.groups()
        date_cols.append((idx, f"20{century}-{mon}-{day}"))
    people = {}
    skipped = 0
    row_i = 3
    while row_i <= ws.max_row:
        name = ws.cell(row_i, 1).value
        empno_raw = ws.cell(row_i, 2).value
        empno = str(empno_raw).strip() if empno_raw else ""
        next_name = ws.cell(row_i + 1, 1).value if row_i + 1 <= ws.max_row else "x"
        if not name:
            row_i += 1
            continue
        paired = next_name in (None, "")
        if empno in ("", "-") or not paired:
            skipped += 1
            row_i += 1 if not paired else 2
            continue
        days = {}
        empty = True
        for idx, iso in date_cols:
            inn = parse_clock(ws.cell(row_i, idx + 1).value)
            off = parse_clock(ws.cell(row_i + 1, idx + 1).value)
            if inn or off:
                empty = False
            days[iso] = (inn, off, inn, off)
        if empty:
            skipped += 1
        else:
            people[empno] = {"name": name, "days": days}
        row_i += 2
    wb.close()
    return people, skipped, [iso for _, iso in date_cols]


def load_matrix():
    ours = {}
    page = 0
    size = 20
    total = None
    while True:
        path = (
            f"/api/v1/attendance-report-queries/matrix?period={period}"
            f"&companyId={company_id}&page={page}&size={size}"
        )
        print(f"matrix page {page + 1}" + (f"/{((total - 1) // size) + 1}" if total else "") + " ...")
        body = api("GET", path, timeout=90, retries=5)
        rows = body.get("rows") or []
        total = int(body.get("rowCount") or 0)
        for item in rows:
            empno = item.get("employeeNumber")
            if not empno:
                continue
            days = {}
            for day in item.get("days") or []:
                days[day.get("date")] = day
            ours[empno] = {
                "name": item.get("employeeName"),
                "days": days,
            }
        page += 1
        print(f"  got {len(ours)}/{total}")
        if not rows or page * size >= total:
            break
    return ours


def load_db():
    mysql = os.environ.get("HR_MYSQL") or (
        "/www/server/mysql/bin/mysql"
        if os.path.exists("/www/server/mysql/bin/mysql")
        else shutil.which("mysql")
    )
    if not mysql:
        print("skip DB: 本机没有 mysql 客户端（Mac 对前台即可；库对照请在宝塔跑）")
        return None
    env = os.environ.copy()
    env.setdefault("MYSQL_PWD", env.get("MYSQL_PWD", ""))
    db = env.get("HR_DB", "shenzhou_hr")
    host = env.get("MYSQL_HOST", "127.0.0.1")
    period_start = f"{period}-01"
    sql = f"""
SELECT e.employee_number,
       DATE_FORMAT(f.business_date, '%Y-%m-%d') AS business_date,
       DATE_FORMAT(CONVERT_TZ(f.first_punch_at, '+00:00', '+08:00'), '%H:%i') AS first_hhmm,
       DATE_FORMAT(CONVERT_TZ(f.last_punch_at, '+00:00', '+08:00'), '%H:%i') AS last_hhmm,
       f.missing_punch_count,
       f.late_minutes
FROM attendance_report_projection p
JOIN attendance_report_daily_fact f
  ON f.attendance_report_projection_id = p.attendance_report_projection_id
JOIN employee e
  ON e.employee_id = f.employee_id
WHERE p.attendance_report_projection_id = (
        SELECT p2.attendance_report_projection_id
        FROM attendance_report_projection p2
        WHERE p2.company_id = '{company_id}'
          AND p2.period_start = '{period_start}'
          AND p2.status = 'PUBLISHED'
          AND p2.formula_catalog_version = 'FULL_CALCULATION_OA_HALF_HOUR_V4'
        ORDER BY p2.published_at DESC, p2.attendance_report_projection_id DESC
        LIMIT 1
      )
ORDER BY e.employee_number, f.business_date;
"""
    try:
        out = subprocess.check_output(
            [mysql, "-h", host, "-uroot", "--batch", "-N", "--default-character-set=utf8mb4", db, "-e", sql],
            env=env,
            stderr=subprocess.STDOUT,
            timeout=120,
        ).decode()
    except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired) as exc:
        print(f"skip DB: {exc}")
        return None
    db_people = {}
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 5:
            continue
        empno, iso, first, last, miss = parts[:5]
        if first in ("NULL", "None", ""):
            first = None
        if last in ("NULL", "None", ""):
            last = None
        db_people.setdefault(empno, {"days": {}})["days"][iso] = {
            "firstPunchAt": None,
            "lastPunchAt": None,
            "first_hhmm": first,
            "last_hhmm": last,
            "missingPunches": int(miss or 0),
        }
    print(f"db employees={len(db_people)}")
    return db_people


def hhmm_from(ours_day):
    if ours_day.get("first_hhmm") or ours_day.get("last_hhmm"):
        return ours_day.get("first_hhmm"), ours_day.get("last_hhmm")
    return clock(ours_day.get("firstPunchAt")), clock(ours_day.get("lastPunchAt"))


def has_clock(value):
    return value not in (None, "", "MISS")


HR_NAME_EMPNO = {
    "黄凯": "SZST0667",
    "张晨阳": "SZST0663",
    "于跃": "SZST0671",
}
ACCEPT_EMPNOS = (
    "SZST0335", "SZST0284", "SZST0291", "SZST0694", "SZST0017", "SZST0663",
)
ZHAO_EMPNO = "SZST0289"


def remap_to_hr(people, hr_name_to_empno):
    """Align Excel rows to the HR roster. Never rewrite the workbook."""
    remapped = {}
    for excel_empno, row in people.items():
        name = str(row.get("name") or "").strip()
        target = HR_NAME_EMPNO.get(name)
        if not target and name in hr_name_to_empno:
            target = hr_name_to_empno[name]
        if not target:
            target = excel_empno
        incoming_days = dict(row.get("days") or {})
        existing = remapped.get(target)
        if existing is None:
            remapped[target] = {
                "name": name or row.get("name"),
                "days": incoming_days,
                "excelEmpno": excel_empno,
            }
            continue
        for iso, day in incoming_days.items():
            old = existing["days"].get(iso)
            if old is None or (
                    not has_clock(old[0]) and not has_clock(old[1])):
                existing["days"][iso] = day
    return remapped


def merge_deli(month_people, summary_people):
    """月报有时刻用月报；空/漏刷时用汇总表。每人每日只比一次。"""
    merged = {}
    for empno in set(month_people) | set(summary_people):
        month_row = month_people.get(empno) or {"name": "", "days": {}}
        summary_row = summary_people.get(empno) or {"name": "", "days": {}}
        name = month_row.get("name") or summary_row.get("name") or empno
        dates = set(month_row.get("days") or {}) | set(summary_row.get("days") or {})
        days = {}
        origin = {}
        for iso in dates:
            summary_day = (summary_row.get("days") or {}).get(iso)
            month_day = (month_row.get("days") or {}).get(iso)
            if month_day and (
                    has_clock(month_day[0]) or has_clock(month_day[1])):
                days[iso] = month_day
                origin[iso] = "月报"
            elif summary_day and (
                    has_clock(summary_day[0]) or has_clock(summary_day[1])):
                days[iso] = summary_day
                origin[iso] = "汇总表"
            elif month_day:
                days[iso] = month_day
                origin[iso] = "月报"
        merged[empno] = {
            "name": name,
            "days": days,
            "origin": origin,
            "excelEmpno": month_row.get("excelEmpno") or summary_row.get("excelEmpno"),
        }
    return merged


rows_out = []


def add_row(kind, source, empno, name, iso, deli_in, deli_out, ours_in, ours_out, miss, note=""):
    rows_out.append((
        source, kind, empno, name or "", iso or "",
        deli_in or "", deli_out or "",
        ours_in or "", ours_out or "",
        "" if miss is None else str(miss),
        note,
    ))


def compare(label, deli, ours):
    counts = {
        "DATE_SHIFT": 0,
        "DELI_ONLY": 0,
        "TIME_DIFF": 0,
        "OUT_DIFF": 0,
        "MISS_MISMATCH": 0,
        "NO_MATRIX": 0,
        "OK_DAYS": 0,
        "PEOPLE": len(deli),
    }
    print(f"\n===== {label} 全员 {len(deli)} 人 =====")
    for empno, row in sorted(deli.items()):
        person = ours.get(empno)
        if person is None:
            if str(empno).startswith("SZJN"):
                counts.setdefault("SKIP_OTHER_COMPANY", 0)
                counts["SKIP_OTHER_COMPANY"] = counts.get("SKIP_OTHER_COMPANY", 0) + 1
                continue
            add_row("NO_MATRIX", label, empno, row["name"], "", "", "", "", "", None, "花名册/钉住结果没有此人")
            counts["NO_MATRIX"] += 1
            continue
        days = person.get("days") or {}
        origin = row.get("origin") or {}
        for iso, (inn, out, raw_in, raw_out) in row["days"].items():
            ours_day = days.get(iso) or {}
            ours_in, ours_out = hhmm_from(ours_day)
            miss = ours_day.get("missingPunches") or 0
            src_note = origin.get(iso) or ""
            hit = False
            if inn and inn != "MISS":
                if ours_in and ours_in != inn:
                    add_row("TIME_DIFF", label, empno, row["name"], iso, inn, out, ours_in, ours_out, miss, src_note)
                    counts["TIME_DIFF"] += 1
                    hit = True
                if not ours_in:
                    prev = days.get(prev_iso(iso)) or {}
                    prev_in, prev_out = hhmm_from(prev)
                    if prev_in == inn or prev_out == inn:
                        add_row(
                            "DATE_SHIFT", label, empno, row["name"], iso,
                            inn, out, ours_in, ours_out, miss,
                            f"{src_note} 卡在前一日 {prev_iso(iso)} {prev_in}/{prev_out}".strip(),
                        )
                        counts["DATE_SHIFT"] += 1
                    else:
                        add_row("DELI_ONLY", label, empno, row["name"], iso, inn, out, ours_in, ours_out, miss, src_note)
                        counts["DELI_ONLY"] += 1
                    hit = True
            if out and out != "MISS" and ours_out and ours_out != out:
                add_row("OUT_DIFF", label, empno, row["name"], iso, inn, out, ours_in, ours_out, miss, src_note)
                counts["OUT_DIFF"] += 1
                hit = True
            if out == "MISS" and miss == 0 and ours_out:
                add_row("MISS_MISMATCH", label, empno, row["name"], iso, inn, "漏刷", ours_in, ours_out, miss, src_note)
                counts["MISS_MISMATCH"] += 1
                hit = True
            if not hit and ((inn and inn != "MISS") or out == "MISS"):
                counts["OK_DAYS"] += 1
    extra = ""
    skipped_other = counts.get("SKIP_OTHER_COMPANY") or 0
    if skipped_other:
        extra = f" 跳过其他公司={skipped_other}"
    print(
        f"合计 DATE_SHIFT={counts['DATE_SHIFT']} DELI_ONLY={counts['DELI_ONLY']} "
        f"TIME_DIFF={counts['TIME_DIFF']} OUT_DIFF={counts['OUT_DIFF']} "
        f"MISS_MISMATCH={counts['MISS_MISMATCH']} NO_MATRIX={counts['NO_MATRIX']} "
        f"一致日格={counts['OK_DAYS']}{extra}"
    )
    return counts


api("POST", "/api/v1/auth/login", {"username": user, "password": password})
month_people, month_skipped, month_dates = parse_month_report(xlsx)
summary_people, summary_skipped, summary_dates = parse_summary(summary)
print(
    f"parsed 考勤月报 people={len(month_people)} skipped_no_empno={month_skipped} "
    f"dates={month_dates[0] if month_dates else '-'}..{month_dates[-1] if month_dates else '-'}"
)
print(
    f"parsed 月度汇总表 people={len(summary_people)} skipped_empty={summary_skipped} "
    f"dates={summary_dates[0] if summary_dates else '-'}..{summary_dates[-1] if summary_dates else '-'}"
)
print("loading matrix pages...")
ours = load_matrix()
print(f"matrix employees={len(ours)}")
hr_name_to_empno = {}
for empno, person in ours.items():
    name = str(person.get("name") or "").strip()
    if name and name not in hr_name_to_empno:
        hr_name_to_empno[name] = empno
month_people = remap_to_hr(month_people, hr_name_to_empno)
summary_people = remap_to_hr(summary_people, hr_name_to_empno)
print(
    "姓名对齐 黄凯→SZST0667 张晨阳→SZST0663 于跃→SZST0671；"
    "其余按花名册姓名"
)
merged = merge_deli(month_people, summary_people)
print(
    f"互补合并后 people={len(merged)} "
    f"（月报有时刻用月报，空/漏刷用汇总表；每人每日只比一次）"
)
api_counts = compare("得力互补合并 vs 前台API", merged, ours)

db = load_db()
if db:
    compare("得力互补合并 vs 数据库", merged, db)
    api_db = 0
    for empno, person in ours.items():
        name = person.get("name") or ""
        db_person = db.get(empno) or {"days": {}}
        for iso, day in (person.get("days") or {}).items():
            api_in, api_out = hhmm_from(day)
            db_day = (db_person.get("days") or {}).get(iso) or {}
            db_in, db_out = hhmm_from(db_day)
            if api_in != db_in or api_out != db_out:
                add_row(
                    "API_DB_DIFF", "前台API vs 数据库", empno, name, iso,
                    api_in, api_out, db_in, db_out,
                    db_day.get("missingPunches"),
                    "前台和库不一致",
                )
                api_db += 1
    print(f"合计 前台API vs 数据库 diffs={api_db}")

window_start = f"{year}-{month:02d}-01"
window_end = f"{year}-{month:02d}-12"
zhao_end = f"{year}-{month:02d}-13"
print(f"\n===== 验收闸门 {window_start}..{window_end} =====")
gate_fail = 0
for empno in ACCEPT_EMPNOS:
    row = merged.get(empno) or {"name": empno, "days": {}}
    person = ours.get(empno) or {"days": {}}
    days = person.get("days") or {}
    empty_when_excel_has_clock = 0
    excel_clock_days = 0
    for iso, day in (row.get("days") or {}).items():
        if iso < window_start or iso > window_end:
            continue
        inn, off = day[0], day[1]
        if not has_clock(inn) and not has_clock(off):
            continue
        excel_clock_days += 1
        ours_day = days.get(iso) or {}
        ours_in, ours_out = hhmm_from(ours_day)
        if not ours_in and not ours_out:
            empty_when_excel_has_clock += 1
            add_row(
                "ACCEPT_FAIL", "验收闸门", empno, row.get("name"), iso,
                inn, off, ours_in, ours_out, ours_day.get("missingPunches"),
                "表有卡系统空",
            )
    print(
        f"  {empno} {row.get('name')} excel有卡={excel_clock_days} "
        f"系统空={empty_when_excel_has_clock}"
    )

zhao = ours.get(ZHAO_EMPNO) or {"name": "赵艺娴", "days": {}}
zhao_days_with_punch = 0
for iso, day in (zhao.get("days") or {}).items():
    if iso < window_start or iso > zhao_end:
        continue
    ours_in, ours_out = hhmm_from(day)
    if ours_in or ours_out:
        zhao_days_with_punch += 1
print(f"  {ZHAO_EMPNO} 赵艺娴 {window_start}..{zhao_end} 自己有卡天数={zhao_days_with_punch}")
if zhao_days_with_punch == 0:
    gate_fail += 1
    add_row(
        "ACCEPT_FAIL", "验收闸门", ZHAO_EMPNO, "赵艺娴", "",
        "", "", "", "", None, "挪走彭伟后赵艺娴 8/1–8/13 自己的卡也没了",
    )

remain = [
    r for r in rows_out
    if r[1] == "DELI_ONLY" and str(r[2]).startswith(("SZST067", "SZST068", "SZST069", "SZST070"))
]
print(f"\n===== 回放后仍表有卡系统空（067x–070x，不计入本 change 已清）===== {len(remain)} 行")
remain_people = sorted({r[2] for r in remain})
print("人员: " + (", ".join(remain_people) if remain_people else "(无)"))

header = [
    "source", "kind", "empno", "name", "date",
    "deli_in", "deli_out", "ours_in", "ours_out", "ours_miss", "note",
]
with open(output, "w", encoding="utf-8") as fh:
    fh.write("\t".join(header) + "\n")
    for row in rows_out:
        fh.write("\t".join(str(col) for col in row) + "\n")
print(f"\n全员差异表已写出 {len(rows_out)} 行：{output}")
print("用 Excel 打开该 tsv。kind=DATE_SHIFT/TIME_DIFF/DELI_ONLY/MISS_MISMATCH 即需要对的格子。")
wu_rows = [r for r in rows_out if r[2] == "SZST0009"]
print(f"其中吴根银 SZST0009 差异 {len(wu_rows)} 行（全员都在表里，不只他）")
full_deli_only = (api_counts or {}).get("DELI_ONLY") or 0
print(
    f"全员 DELI_ONLY={full_deli_only}（得力表有卡、花名册矩阵空；不只抽查那几人）"
)
gate_fail += full_deli_only
print(f"验收闸门失败人天={gate_fail}")
if gate_fail:
    raise SystemExit(2)
PY
