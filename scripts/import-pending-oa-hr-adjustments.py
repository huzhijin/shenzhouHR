#!/usr/bin/env python3
"""把未结束 OA 查询 Excel 写成人事调整，全部写完后再按人按天重算。

走前台同一套接口：
  POST /api/v1/attendance/hr-adjustments/punches/batch   recalculate=false
  POST /api/v1/attendance-reports/recalculate            employeeIds + fromDate/toDate

默认只预览。真正写入：

  APPLY=1 BASE_URL=https://考勤域名 \\
    HR_USERNAME=人事账号 HR_PASSWORD=密码 \\
    python3 scripts/import-pending-oa-hr-adjustments.py

需要 ATTENDANCE_ADJUST:MANAGE 和 ATTENDANCE_REPORT:REFRESH。
不要把 Excel 当 OA 单据导入。人事调整和以后正式 OA 同步不是同一套主键。
加班小时是覆盖当天总数；调休加班在 OA 通过前会先记成加班费。
"""
from __future__ import annotations

import json
import os
import ssl
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from http.cookiejar import CookieJar
from pathlib import Path

import certifi
import pandas as pd

SHANGHAI = timezone(timedelta(hours=8))
REASON = "未结束OA预入账-20260902"

# 昇州张衡：未在 OA 查询表里，8/11、8/26 全天外出，格子要显示「外出」。
EXTRA_OUTINGS = (
    ("SZSZ0003", "张衡", "2026-08-11"),
    ("SZSZ0003", "张衡", "2026-08-26"),
)

def excel_dir() -> Path:
    env = os.environ.get("EXCEL_DIR")
    candidates = []
    if env:
        candidates.append(Path(env))
    candidates.extend(
        [
            Path("/root"),
            Path("/Users/huzhijin/Downloads"),
            Path(__file__).resolve().parent,
        ]
    )
    for folder in candidates:
        if (folder / "请假单查询.xlsx").exists():
            return folder
    return candidates[0]


EXCEL_DIR = excel_dir()
EXCELS = {
    "请假": EXCEL_DIR / "请假单查询.xlsx",
    "补签": EXCEL_DIR / "未打卡补签表查询.xlsx",
    "外出": EXCEL_DIR / "外出登记表查询.xlsx",
    "加班": EXCEL_DIR / "加班单查询.xlsx",
}
LEAVE_TYPE = {
    "年假": "ANNUAL_LEAVE",
    "事假": "PERSONAL_LEAVE",
    "病假": "SICK_LEAVE",
    "调休": "TIME_OFF",
    "婚假": "MARRIAGE_LEAVE",
    "丧假": "BEREAVEMENT_LEAVE",
    "产假": "MATERNITY_LEAVE",
    "陪产假": "PATERNITY_LEAVE",
}


def text(value) -> str:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return ""
    raw = str(value).strip()
    return "" if raw.lower() in {"nan", "none", "nat"} else raw


def parse_dt(value) -> datetime | None:
    raw = text(value)
    if not raw:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(raw, fmt).replace(tzinfo=SHANGHAI)
        except ValueError:
            continue
    return None


def load_sheet(path: Path) -> pd.DataFrame:
    df = pd.read_excel(path, header=None, dtype=str)
    headers = [text(x) or f"col{i}" for i, x in enumerate(df.iloc[2].tolist())]
    data = df.iloc[3:].copy()
    data.columns = headers
    return data.dropna(how="all").reset_index(drop=True)


def iso_clock(day: str, hour: int, minute: int) -> str:
    local = datetime.strptime(day, "%Y-%m-%d").replace(
        hour=hour, minute=minute, tzinfo=SHANGHAI
    )
    return local.isoformat()


def days_between(start: datetime, end: datetime, workdays_only: bool) -> list[str]:
    days = []
    cur = start.date()
    last = (end - timedelta(seconds=1)).date()
    while cur <= last:
        if not workdays_only or cur.weekday() < 5:
            days.append(cur.isoformat())
        cur += timedelta(days=1)
    return days


def round_half(hours: float) -> float:
    return round(hours * 2) / 2


def recommended_ot_hours(name: str, start: datetime, hours: float) -> float:
    if (
        name == "魏超"
        and start.strftime("%Y-%m-%d %H:%M") == "2026-08-31 18:30"
        and abs(hours - 3.0) < 1e-9
    ):
        return 0.0
    return hours


class Api:
    def __init__(self, base: str, username: str, password: str) -> None:
        self.base = base.rstrip("/")
        self.csrf = ""
        ctx = ssl.create_default_context(cafile=certifi.where())
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(CookieJar()),
            urllib.request.HTTPSHandler(context=ctx),
        )
        self._login(username, password)

    def _login(self, username: str, password: str) -> None:
        code, headers, body = self.request(
            "POST",
            "/api/v1/auth/login",
            {"username": username, "password": password},
            csrf=False,
        )
        if code != 200:
            raise SystemExit(f"登录失败 HTTP {code}: {body[:400]}")
        self.csrf = headers.get("X-CSRF-TOKEN") or ""
        if not self.csrf:
            raise SystemExit("登录成功但没有 X-CSRF-TOKEN")
        print(f"已登录 {self.base}")

    def request(
        self,
        method: str,
        path: str,
        payload=None,
        csrf: bool = True,
        timeout: int = 120,
    ):
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": "shenzhouhr-pending-oa-import/1",
        }
        if csrf and self.csrf:
            headers["X-CSRF-TOKEN"] = self.csrf
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
        req = urllib.request.Request(
            self.base + path, data=data, headers=headers, method=method
        )
        try:
            with self.opener.open(req, timeout=timeout) as response:
                raw = response.read().decode("utf-8", "replace")
                return response.status, response.headers, raw
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", "replace")
            return error.code, error.headers, raw

    def find_employee(self, empno: str, name: str) -> dict | None:
        code, _, body = self.request(
            "GET",
            f"/api/v1/employees?query={urllib.parse.quote(empno)}&size=20&asOf=2026-08-15",
            csrf=False,
        )
        if code != 200:
            print(f"  查人失败 {empno} HTTP {code}: {body[:200]}")
            return None
        items = json.loads(body).get("items") or []
        for item in items:
            if text(item.get("employeeNumber")) == empno:
                return item
        if len(items) == 1 and text(items[0].get("displayName")) == name:
            return items[0]
        return None


def build_adjustments() -> dict[tuple[str, str], dict]:
    by_key: dict[tuple[str, str], dict] = {}

    def row(empno: str, name: str, day: str) -> dict:
        key = (empno, day)
        item = by_key.get(key)
        if item is None:
            item = {
                "empno": empno,
                "name": name,
                "businessDate": day,
                "dayTypes": set(),
                "cleared": set(),
                "overtimeHours": 0.0,
                "onDutyAt": None,
                "offDutyAt": None,
                "notes": [],
            }
            by_key[key] = item
        return item

    leave = load_sheet(EXCELS["请假"])
    for _, rec in leave.iterrows():
        empno = text(rec.get("请假人工号"))
        name = text(rec.get("请假人"))
        start = parse_dt(rec.get("请假开始时间"))
        end = parse_dt(rec.get("请假结束时间"))
        kind = LEAVE_TYPE.get(text(rec.get("请假类别")))
        if not empno or start is None or end is None or kind is None:
            continue
        for day in days_between(start, end, workdays_only=True):
            item = row(empno, name, day)
            item["dayTypes"].add(kind)
            item["cleared"].update({"ABSENCE", "MISSING_PUNCH", "LATE", "EARLY_DEPARTURE"})
            item["notes"].append(f"请假{text(rec.get('请假类别'))}")

    outing = load_sheet(EXCELS["外出"])
    for _, rec in outing.iterrows():
        empno = text(rec.get("工号"))
        name = text(rec.get("姓名"))
        start = parse_dt(rec.get("外出时间"))
        end = parse_dt(rec.get("回岗时间"))
        if not empno or start is None or end is None:
            continue
        for day in days_between(start, end, workdays_only=False):
            item = row(empno, name, day)
            item["dayTypes"].add("OUTING")
            item["cleared"].update({"ABSENCE", "MISSING_PUNCH"})
            item["notes"].append("外出")

    makeup = load_sheet(EXCELS["补签"])
    for _, rec in makeup.iterrows():
        empno = text(rec.get("工号"))
        name = text(rec.get("姓名"))
        claimed = parse_dt(rec.get("时间"))
        side = text(rec.get("补卡类型"))
        if not empno or claimed is None:
            continue
        day = claimed.strftime("%Y-%m-%d")
        item = row(empno, name, day)
        clock = iso_clock(day, claimed.hour, claimed.minute)
        if side == "上班":
            item["onDutyAt"] = clock
        else:
            item["offDutyAt"] = clock
        item["cleared"].add("MISSING_PUNCH")
        item["notes"].append(f"补签{side}")

    overtime = load_sheet(EXCELS["加班"])
    for _, rec in overtime.iterrows():
        empno = text(rec.get("工号"))
        name = text(rec.get("加班人"))
        start = parse_dt(rec.get("加班开始时间"))
        try:
            hours = float(text(rec.get("加班时间总计")) or 0)
        except ValueError:
            hours = 0.0
        if not empno or start is None:
            continue
        hours = recommended_ot_hours(name, start, hours)
        if hours <= 0:
            continue
        day = start.strftime("%Y-%m-%d")
        item = row(empno, name, day)
        item["overtimeHours"] = round_half(item["overtimeHours"] + hours)
        item["notes"].append(f"加班{hours}h")

    for empno, name, day in EXTRA_OUTINGS:
        item = row(empno, name, day)
        item["dayTypes"].add("OUTING")
        item["cleared"].update({"ABSENCE", "MISSING_PUNCH"})
        item["onDutyAt"] = iso_clock(day, 8, 29)
        item["offDutyAt"] = iso_clock(day, 18, 0)
        item["notes"].append("外出08:29-18:00")

    return by_key


def to_payload(item: dict, employee_id: str) -> dict:
    payload = {
        "employeeId": employee_id,
        "businessDate": item["businessDate"],
        "reason": REASON + " " + ",".join(item["notes"])[:420],
        "recalculate": False,
    }
    if item["onDutyAt"]:
        payload["onDutyAt"] = item["onDutyAt"]
    if item["offDutyAt"]:
        payload["offDutyAt"] = item["offDutyAt"]
    if item["overtimeHours"] > 0:
        payload["overtimeHours"] = item["overtimeHours"]
    if item["cleared"]:
        payload["clearedExceptionTypes"] = sorted(item["cleared"])
    if item["dayTypes"]:
        payload["dayTypes"] = sorted(item["dayTypes"])
    return payload


def main() -> None:
    apply = os.environ.get("APPLY", "0") == "1"
    base = os.environ.get("BASE_URL", "").rstrip("/")
    username = os.environ.get("HR_USERNAME", "")
    password = os.environ.get("HR_PASSWORD", "")
    adjustments = build_adjustments()
    print(f"合并后人事调整 {len(adjustments)} 人日 / {len({k[0] for k in adjustments})} 人")
    for key in sorted(adjustments):
        item = adjustments[key]
        print(
            f"  {item['empno']} {item['name']} {item['businessDate']} "
            f"OT={item['overtimeHours']} types={sorted(item['dayTypes'])} "
            f"on={item['onDutyAt'] or '-'} off={item['offDutyAt'] or '-'} "
            f"{','.join(item['notes'])}"
        )
    if not apply:
        print("\n预览结束。确认后：APPLY=1 BASE_URL=... HR_USERNAME=... HR_PASSWORD=... 再跑。")
        return
    if not base or not username or not password:
        raise SystemExit("APPLY=1 时需要 BASE_URL、HR_USERNAME、HR_PASSWORD")

    api = Api(base, username, password)
    directory: dict[str, dict] = {}
    missing = []
    for empno, name in sorted({(item["empno"], item["name"]) for item in adjustments.values()}):
        found = api.find_employee(empno, name)
        if found is None:
            missing.append(f"{empno} {name}")
        else:
            directory[empno] = found
            print(f"  命中 {empno} {found.get('displayName')} {found.get('employeeId')}")
    if missing:
        raise SystemExit("未找到员工: " + "、".join(missing))

    payloads = []
    for item in adjustments.values():
        payloads.append(to_payload(item, directory[item["empno"]]["employeeId"]))

    print(f"\n写入 {len(payloads)} 条，recalculate=false")
    code, _, body = api.request(
        "POST",
        "/api/v1/attendance/hr-adjustments/punches/batch",
        {"items": payloads},
        timeout=180,
    )
    if code not in (200, 201):
        raise SystemExit(f"批量写入失败 HTTP {code}: {body[:800]}")
    saved = json.loads(body)
    print(f"已写入 {saved.get('savedCount')} 条")

    by_company: dict[str, dict] = defaultdict(lambda: {"ids": set(), "dates": []})
    for row in saved.get("items") or []:
        company_id = row.get("companyId")
        employee_id = row.get("employeeId")
        day = row.get("businessDate")
        if not company_id or not employee_id or not day:
            continue
        by_company[company_id]["ids"].add(employee_id)
        by_company[company_id]["dates"].append(day)

    for company_id, group in by_company.items():
        dates = sorted(group["dates"])
        ids = sorted(group["ids"])
        payload = {
            "companyId": company_id,
            "period": "2026-08",
            "employeeIds": ids,
            "fromDate": dates[0],
            "toDate": dates[-1],
        }
        print(
            f"重算公司 {company_id} 人={len(ids)} {dates[0]}~{dates[-1]}"
        )
        code, _, body = api.request(
            "POST",
            "/api/v1/attendance-reports/recalculate",
            payload,
            timeout=1800,
        )
        if code != 200:
            raise SystemExit(f"重算失败 {company_id} HTTP {code}: {body[:800]}")
        result = json.loads(body)
        print(
            f"  完成 version={result.get('projectionVersion')} "
            f"dataAsOf={result.get('dataAsOf')}"
        )
    print("全部完成。去报表中心导出 2026-08。")


if __name__ == "__main__":
    main()
