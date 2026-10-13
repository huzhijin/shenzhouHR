#!/usr/bin/env python3
"""
期初年假余额导入脚本
---------------------
从 Excel 文件读取员工年假期初余额，通过 HR 系统 API 批量写入。

Excel 格式（.xlsx），必须包含以下列（列名不区分大小写，忽略其余列）：
  员工编号 / employee_number  : 员工工号，如 SZST0001
  期初余额(小时) / balance_hours : 期初剩余小时数，如 40.00
  原因 / reason               : （可选）备注原因

用法:
  python3 scripts/import-annual-leave-opening.py \\
      --file 年假期初2026.xlsx \\
      --base-url http://127.0.0.1:8080 \\
      --username szsc_admin_faa41d5bd802 \\
      --password "$SHENZHOUHR_ADMIN_PASSWORD" \\
      --year 2026 \\
      [--dry-run]
"""
import argparse, csv, sys, time, uuid, re
from decimal import Decimal, InvalidOperation
from pathlib import Path

try:
    import openpyxl
    import requests
except ImportError:
    print("缺少依赖，请先安装: pip install openpyxl requests", file=sys.stderr)
    sys.exit(1)

# ── 列名别名 ──────────────────────────────────────────────────────────────────
COL_ALIASES = {
    "employee_number": ["员工编号", "employee_number", "工号", "emp_no"],
    "balance_hours":   ["期初余额(小时)", "balance_hours", "余额小时", "小时数", "opening_balance"],
    "reason":          ["原因", "reason", "备注"],
}

def find_col(header_row: list, field: str) -> int | None:
    aliases = [a.lower() for a in COL_ALIASES[field]]
    for idx, cell in enumerate(header_row):
        if str(cell).strip().lower() in aliases:
            return idx
    return None

def load_excel(path: Path) -> list[dict]:
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    ws = wb.active
    rows = list(ws.iter_rows(values_only=True))
    if not rows:
        raise ValueError("Excel 文件为空")
    headers = [str(c) if c is not None else "" for c in rows[0]]
    col_emp = find_col(headers, "employee_number")
    col_bal = find_col(headers, "balance_hours")
    col_rsn = find_col(headers, "reason")
    if col_emp is None:
        raise ValueError("找不到员工编号列（期望名称：" + " / ".join(COL_ALIASES["employee_number"]) + "）")
    if col_bal is None:
        raise ValueError("找不到期初余额列（期望名称：" + " / ".join(COL_ALIASES["balance_hours"]) + "）")
    records = []
    for row_idx, row in enumerate(rows[1:], start=2):
        emp = str(row[col_emp]).strip() if row[col_emp] is not None else ""
        bal_raw = row[col_bal]
        if not emp:
            continue
        try:
            bal = round(float(bal_raw), 2)
        except (TypeError, ValueError):
            print(f"  跳过第 {row_idx} 行：余额 '{bal_raw}' 无法解析", file=sys.stderr)
            continue
        if bal < 0:
            print(f"  跳过第 {row_idx} 行（{emp}）：余额不得为负数 {bal}", file=sys.stderr)
            continue
        reason_val = str(row[col_rsn]).strip() if col_rsn is not None and row[col_rsn] else "期初年假余额导入"
        if len(reason_val) < 2:
            reason_val = "期初年假余额导入"
        records.append({"employee_number": emp, "balance_hours": bal, "reason": reason_val})
    return records

class HrApiClient:
    def __init__(self, base_url: str, username: str, password: str):
        self.base = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update({"Content-Type": "application/json"})
        self._login(username, password)

    def _login(self, username: str, password: str):
        resp = self.session.post(f"{self.base}/api/v1/auth/login",
                                 json={"username": username, "password": password},
                                 timeout=10)
        resp.raise_for_status()
        data = resp.json()
        if "sessionId" not in data:
            raise RuntimeError(f"登录失败: {data}")
        print(f"  已登录为 {data.get('username', username)}")

    def get_employee_id(self, employee_number: str) -> str | None:
        resp = self.session.get(f"{self.base}/api/v1/employees",
                                params={"employeeNumber": employee_number},
                                timeout=10)
        if resp.status_code == 404:
            return None
        resp.raise_for_status()
        items = resp.json().get("items", [])
        for item in items:
            if item.get("employeeNumber") == employee_number:
                return item["employeeId"]
        return None

    def set_opening_balance(self, employee_id: str, balance_hours: float,
                            year: int, opening_date: str, reason: str) -> dict:
        idempotency_key = str(uuid.uuid4())
        resp = self.session.post(
            f"{self.base}/api/v1/employees/{employee_id}/annual-leave/opening",
            json={
                "balanceHours": balance_hours,
                "year": year,
                "openingDate": opening_date,
                "reason": reason,
            },
            headers={"Idempotency-Key": idempotency_key},
            timeout=15)
        resp.raise_for_status()
        return resp.json()


def run(args):
    path = Path(args.file)
    if not path.exists():
        print(f"文件不存在: {path}", file=sys.stderr)
        sys.exit(1)

    print(f"读取 Excel: {path}")
    records = load_excel(path)
    print(f"有效行数: {len(records)}")

    if args.dry_run:
        print("\n[DRY RUN] 仅展示前 10 行，不提交任何数据：")
        for r in records[:10]:
            print(f"  {r['employee_number']:12s}  {r['balance_hours']:8.2f}h  {r['reason'][:40]}")
        sys.exit(0)

    client = HrApiClient(args.base_url, args.username, args.password)

    ok, skip, fail = 0, 0, 0
    for idx, rec in enumerate(records, 1):
        emp_num = rec["employee_number"]
        try:
            emp_id = client.get_employee_id(emp_num)
            if emp_id is None:
                print(f"  [{idx}/{len(records)}] {emp_num} 未找到，跳过")
                skip += 1
                continue
            result = client.set_opening_balance(
                emp_id, rec["balance_hours"], args.year, args.opening_date, rec["reason"])
            bal = result.get("balanceHours", "?")
            print(f"  [{idx}/{len(records)}] {emp_num:<12s} ✓  余额={bal:.2f}h")
            ok += 1
        except Exception as exc:
            print(f"  [{idx}/{len(records)}] {emp_num} ✗ 失败: {exc}", file=sys.stderr)
            fail += 1
        time.sleep(0.05)   # 避免过快

    print(f"\n完成：成功 {ok}，跳过 {skip}，失败 {fail}")
    if fail:
        sys.exit(1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="导入期初年假余额")
    parser.add_argument("--file", required=True, help="Excel 文件路径 (.xlsx)")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--year", type=int, default=2026)
    parser.add_argument(
        "--opening-date", default="2026-08-01",
        help="期初记账日期（默认 2026-08-01，即年假周期起始日）",
    )
    parser.add_argument("--dry-run", action="store_true", help="仅解析，不提交")
    run(parser.parse_args())
