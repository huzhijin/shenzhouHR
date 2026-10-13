#!/usr/bin/env python3
"""Parse the 神州花名册 / 不打卡名单 and reconcile against live org/people.

Default is dry-run: print a report and optionally write SQL. It never
updates punch events, OA rows, password hashes, or session_epoch.

Usage:
  python3 scripts/release/roster_org_cutover.py --live-json /tmp/szhr-live
  python3 scripts/release/roster_org_cutover.py --base-url http://host --username u --password p --sql-out /tmp/roster-cutover.sql
"""

from __future__ import annotations

import argparse
import json
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

ROSTER_XLSX = Path("/Users/huzhijin/Downloads/神州花名册.xlsx")
EXEMPT_XLSX = Path("/Users/huzhijin/Downloads/不打卡人员名单。.xlsx")

CUTOVER = "2026-01-01 00:00:00.000000"
SYSTEM_PRINCIPAL = "00000000-0000-0000-0000-000000000001"

NUMBER_CORRECTIONS = {
    "SZT0687": "SZST0687",
    "SZT0709": "SZST0709",
    "SZST0567": "SZSZ0000",
}
DROP_ROSTER_ROWS = {("上海晟州聚能", "SZST0019"), ("上海晟州聚能", "SZST0450"), ("上海晟州聚能", "SZJN0014")}
NEW_NUMBERS = {"赵子奇": "SZSZ0002", "张衡": "SZSZ0003"}
TERMINATIONS = {
    "SZST0598", "SZST0638", "SZST0641", "SZST0652", "SZST0662", "SZST0674",
    "SZJN0026", "SZJN0031", "SZJNSX05",
}
PROTECTED_USERNAMES = {
    "szsc_admin_faa41d5bd802",
    "SZST0007", "SZST0036", "SZST0381", "SZST0392", "SZST0615", "SZST0673",
}
XINYUE_PREFIX = "江苏芯越"
SHENZHOU = "江苏神州半导体科技股份有限公司"
JUNENG = "上海晟州聚能半导体科技有限公司"
SHENGZHOU = "上海昇州半导体科技有限公司"
ROSTER_COMPANY = {
    "江苏神州": SHENZHOU,
    "上海晟州聚能": JUNENG,
    "上海昇州": SHENGZHOU,
}


def _blank(value: Any) -> bool:
    if value is None:
        return True
    text = str(value).strip()
    return text in ("", "/", "-", "—", "无", "nan", "None")


def _text(value: Any) -> str | None:
    if _blank(value):
        return None
    return str(value).strip()


def excel_date(value: Any) -> str | None:
    if _blank(value):
        return None
    text = str(value).strip()
    if text.replace(".", "", 1).isdigit():
        serial = float(text)
        if serial > 20000:
            return (datetime(1899, 12, 30) + timedelta(days=serial)).strftime("%Y-%m-%d")
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
        try:
            return datetime.strptime(text[:19] if len(text) > 10 else text, fmt).strftime("%Y-%m-%d")
        except ValueError:
            continue
    return None


def short_name(name: str, parent_live: str | None, parent_short: str | None) -> str:
    if name == "董事长室":
        return "董事长"
    result = name
    for prefix in (parent_live, parent_short):
        if prefix and result.startswith(prefix + "-"):
            result = result[len(prefix) + 1 :]
    if result.startswith("上海") and result not in (SHENGZHOU, JUNENG):
        candidate = result[2:]
        if candidate:
            result = candidate
    return result


@dataclass
class RosterRow:
    company: str
    company_full: str
    employee_number: str
    name: str
    levels: tuple[str, ...]
    title: str | None
    hire_date: str | None


def parse_roster(path: Path) -> list[RosterRow]:
    import pandas as pd

    frame = pd.read_excel(path, header=1, dtype=str)
    rows: list[RosterRow] = []
    for _, raw in frame.iterrows():
        company = _text(raw.get("公司名称")) or ""
        name = _text(raw.get("姓名")) or ""
        number = _text(raw.get("工号"))
        if name in NEW_NUMBERS and number is None:
            number = NEW_NUMBERS[name]
        if number is None or company not in ROSTER_COMPANY:
            continue
        if (company, number) in DROP_ROSTER_ROWS:
            continue
        number = NUMBER_CORRECTIONS.get(number, number)
        levels = tuple(
            part
            for part in (
                _text(raw.get("一级部门")),
                _text(raw.get("二级部门")),
                _text(raw.get("三级部门")),
                _text(raw.get("组别")),
            )
            if part
        )
        rows.append(RosterRow(
            company=company,
            company_full=ROSTER_COMPANY[company],
            employee_number=number,
            name=name,
            levels=levels,
            title=_text(raw.get("职位")),
            hire_date=excel_date(raw.get("入职日期")),
        ))
    return rows


def parse_exempt(path: Path) -> list[tuple[str, str]]:
    import pandas as pd

    frame = pd.read_excel(path, header=None, dtype=str)
    people: list[tuple[str, str]] = []
    seen: set[str] = set()
    for _, raw in frame.iterrows():
        name = _text(raw.iloc[2] if len(raw) > 2 else None)
        number = _text(raw.iloc[3] if len(raw) > 3 else None)
        if not name or not number or name == "姓名" or number == "工号":
            continue
        number = NUMBER_CORRECTIONS.get(number, number)
        if number in seen:
            continue
        seen.add(number)
        people.append((number, name))
    return people


@dataclass
class LiveOrg:
    organization_id: str
    live_name: str
    short: str
    parent_id: str | None
    company: str
    path: tuple[str, ...]
    type: str
    row_version: int | None = None


def flatten_live_tree(nodes: list[dict[str, Any]], parent: LiveOrg | None = None) -> list[LiveOrg]:
    out: list[LiveOrg] = []
    for node in nodes:
        live_name = node["name"]
        company = parent.company if parent else live_name
        short = live_name if parent is None else short_name(
            live_name, parent.live_name, parent.short)
        path = () if parent is None else parent.path + (short,)
        if company == JUNENG and path[:1] == ("研发部",):
            path = path[1:]
        org = LiveOrg(
            organization_id=node["organizationId"],
            live_name=live_name,
            short=short,
            parent_id=parent.organization_id if parent else None,
            company=company,
            path=path,
            type=node.get("organizationType") or "DEPARTMENT",
            row_version=node.get("rowVersion"),
        )
        out.append(org)
        out.extend(flatten_live_tree(node.get("children") or [], org))
    return out


@dataclass
class Reconciliation:
    rename: list[dict[str, Any]] = field(default_factory=list)
    create_org: list[dict[str, Any]] = field(default_factory=list)
    retire_org: list[dict[str, Any]] = field(default_factory=list)
    move_assignment: list[dict[str, Any]] = field(default_factory=list)
    number_correction: list[dict[str, Any]] = field(default_factory=list)
    terminate: list[dict[str, Any]] = field(default_factory=list)
    create_employee: list[dict[str, Any]] = field(default_factory=list)
    account_sync: list[dict[str, Any]] = field(default_factory=list)
    standing_exempt: list[dict[str, Any]] = field(default_factory=list)
    protected_logins: list[str] = field(default_factory=list)
    ambiguities: list[str] = field(default_factory=list)


def reconcile(
    roster: list[RosterRow],
    exempt: list[tuple[str, str]],
    live_orgs: list[LiveOrg],
    employees: list[dict[str, Any]],
    accounts: list[dict[str, Any]],
) -> Reconciliation:
    result = Reconciliation(protected_logins=sorted(PROTECTED_USERNAMES))
    orgs_by_company: dict[str, list[LiveOrg]] = defaultdict(list)
    for org in live_orgs:
        if org.company.startswith(XINYUE_PREFIX):
            continue
        orgs_by_company[org.company].append(org)

    path_index: dict[tuple[str, tuple[str, ...]], list[LiveOrg]] = defaultdict(list)
    for org in live_orgs:
        if org.company.startswith(XINYUE_PREFIX) or org.type == "COMPANY":
            continue
        path_index[(org.company, org.path)].append(org)

    needed_paths: set[tuple[str, tuple[str, ...]]] = set()
    for row in roster:
        for depth in range(1, len(row.levels) + 1):
            needed_paths.add((row.company_full, row.levels[:depth]))

    for key, matches in path_index.items():
        if len(matches) > 1:
            result.ambiguities.append(
                f"ambiguous live path {key[0]} {'/'.join(key[1])}: "
                + ",".join(item.organization_id for item in matches)
            )
    if result.ambiguities:
        return result

    mapped_ids: set[str] = set()
    path_to_id: dict[tuple[str, tuple[str, ...]], str] = {}
    for key, matches in path_index.items():
        org = matches[0]
        if key in needed_paths:
            mapped_ids.add(org.organization_id)
            path_to_id[key] = org.organization_id
            if org.short != org.live_name or org.path != key[1]:
                result.rename.append({
                    "organizationId": org.organization_id,
                    "from": org.live_name,
                    "to": org.path[-1],
                    "path": "/".join(org.path),
                })

    for company, path in sorted(needed_paths):
        if (company, path) in path_to_id:
            continue
        result.create_org.append({
            "company": company,
            "path": "/".join(path),
            "name": path[-1],
            "parentPath": "/".join(path[:-1]),
            "type": "TEAM" if len(path) >= 4 else "DEPARTMENT",
        })

    for org in live_orgs:
        if org.company.startswith(XINYUE_PREFIX) or org.type == "COMPANY":
            continue
        if org.organization_id in mapped_ids:
            continue
        result.retire_org.append({
            "organizationId": org.organization_id,
            "name": org.live_name,
            "path": "/".join(org.path),
        })

    emp_by_number = {item["employeeNumber"]: item for item in employees}
    acct_by_user = {item["username"]: item for item in accounts}

    for old, new in NUMBER_CORRECTIONS.items():
        if old in emp_by_number:
            emp = emp_by_number[old]
            result.number_correction.append({
                "employeeId": emp["employeeId"],
                "from": old,
                "to": new,
                "name": emp.get("displayName"),
            })

    for row in roster:
        live = emp_by_number.get(row.employee_number)
        if live is None:
            for old, new in NUMBER_CORRECTIONS.items():
                if new == row.employee_number and old in emp_by_number:
                    live = emp_by_number[old]
                    break
        if live is None:
            result.create_employee.append({
                "employeeNumber": row.employee_number,
                "name": row.name,
                "company": row.company_full,
                "path": "/".join(row.levels),
                "hireDate": row.hire_date,
            })
            continue
        target_path = row.levels
        target_id = path_to_id.get((row.company_full, target_path))
        if target_id and live.get("organizationId") != target_id:
            result.move_assignment.append({
                "employeeId": live["employeeId"],
                "employeeNumber": row.employee_number,
                "name": row.name,
                "from": live.get("organizationName"),
                "to": "/".join(target_path),
                "toOrganizationId": target_id,
            })
        account = acct_by_user.get(live["employeeNumber"]) or acct_by_user.get(row.employee_number)
        if account is None:
            continue
        sync: dict[str, Any] = {
            "accountId": account["accountId"],
            "username": account["username"],
            "employeeId": live["employeeId"],
        }
        if account.get("displayName") != row.name:
            sync["displayName"] = row.name
        if (
            account["username"] != row.employee_number
            and account["username"] not in PROTECTED_USERNAMES
            and account.get("firstPasswordChangeRequired") is True
        ):
            sync["newUsername"] = row.employee_number
        if "displayName" in sync or "newUsername" in sync:
            result.account_sync.append(sync)

    for number in TERMINATIONS:
        live = emp_by_number.get(number)
        if live:
            result.terminate.append({
                "employeeId": live["employeeId"],
                "employeeNumber": number,
                "name": live.get("displayName"),
            })

    emp_ids = {item["employeeNumber"]: item for item in employees}
    for old, new in NUMBER_CORRECTIONS.items():
        if old in emp_ids:
            emp_ids[new] = emp_ids[old]
    for number, name in exempt:
        live = emp_ids.get(number)
        result.standing_exempt.append({
            "employeeNumber": number,
            "name": name,
            "employeeId": None if live is None else live["employeeId"],
        })
    return result


def emit_sql(result: Reconciliation) -> str:
    lines = [
        "-- roster-org-sync-and-punch-exemption cutover",
        "-- DOES NOT update punch events, OA documents, password_credential, session_epoch",
        "START TRANSACTION;",
        f"SET @cutover = '{CUTOVER}';",
        f"SET @actor = '{SYSTEM_PRINCIPAL}';",
        "",
        "-- 2.1 rename mapped organization current versions in place",
    ]
    for item in result.rename:
        name = item["to"].replace("'", "''")
        lines.append(
            "UPDATE organization_version SET name = '{name}', "
            "effective_from = LEAST(effective_from, @cutover) "
            "WHERE organization_id = '{id}' AND effective_to IS NULL;".format(
                name=name, id=item["organizationId"])
        )
    lines.append("")
    lines.append("-- 3.2 employee number corrections (same employee_id)")
    for item in result.number_correction:
        lines.append(
            "UPDATE employee_version SET employee_number = '{to}' "
            "WHERE employee_id = '{id}' AND effective_to IS NULL;".format(
                to=item["to"], id=item["employeeId"])
        )
    lines.append("")
    lines.append("-- 4.2 / 4.3 account display_name and unused username")
    lines.append("-- password_credential and session_epoch are not touched")
    for item in result.account_sync:
        sets = []
        if "displayName" in item:
            sets.append("display_name = '{0}'".format(item["displayName"].replace("'", "''")))
        if "newUsername" in item:
            sets.append("username = '{0}'".format(item["newUsername"]))
            sets.append("normalized_username = '{0}'".format(item["newUsername"]))
        if sets:
            lines.append(
                "UPDATE local_account SET {sets} "
                "WHERE account_id = '{id}' "
                "AND username NOT IN ({protected});".format(
                    sets=", ".join(sets),
                    id=item["accountId"],
                    protected=", ".join("'{0}'".format(name) for name in sorted(PROTECTED_USERNAMES)),
                )
            )
    lines.append("")
    lines.append("-- 3.1 / 3.3 point current assignment at the roster leaf")
    for item in result.move_assignment:
        if not item.get("toOrganizationId"):
            continue
        lines.append(
            "UPDATE employment_assignment SET organization_id = '{org}' "
            "WHERE employee_id = '{id}' AND effective_to IS NULL;".format(
                org=item["toOrganizationId"], id=item["employeeId"])
        )
        if item.get("employeeNumber") == "SZSZ0000":
            lines.append(
                "UPDATE employee SET company_id = ("
                "SELECT company_id FROM organization_identity "
                "WHERE organization_id = '{org}') "
                "WHERE employee_id = '{id}';".format(
                    org=item["toOrganizationId"], id=item["employeeId"])
            )
    lines.append("")
    lines.append("-- 3.4 terminate employment, keep accounts")
    for item in result.terminate:
        lines.append(
            "UPDATE employment_assignment SET effective_to = @cutover "
            "WHERE employee_id = '{id}' AND effective_to IS NULL;".format(
                id=item["employeeId"])
        )
        lines.append(
            "UPDATE employee_version SET status = 'TERMINATED' "
            "WHERE employee_id = '{id}' AND effective_to IS NULL;".format(
                id=item["employeeId"])
        )
    lines.append("")
    lines.append("-- 5.1 standing punch exemption from 2026-01-01")
    for item in result.standing_exempt:
        if not item["employeeId"]:
            lines.append(
                "-- skip {0} {1}: employee not in live roster".format(
                    item["employeeNumber"], item["name"])
            )
            continue
        lines.append(
            "INSERT INTO punch_exemption_assignment ("
            "exemption_id, employee_id, listed_employee_number, source, valid_from, valid_to"
            ") SELECT '{eid}', '{empid}', '{num}', 'STANDING_LIST', @cutover, NULL "
            "FROM DUAL WHERE NOT EXISTS ("
            "SELECT 1 FROM punch_exemption_assignment "
            "WHERE employee_id = '{empid}' AND source = 'STANDING_LIST' AND valid_to IS NULL"
            ");".format(
                eid=str(uuid.uuid5(uuid.NAMESPACE_URL, "exempt:" + item["employeeNumber"])),
                empid=item["employeeId"],
                num=item["employeeNumber"],
            )
        )
    lines.append("")
    lines.append("-- 2.2 / 2.3 create/retire listed in cutover report; apply after reviewing")
    for item in result.create_org:
        lines.append("-- CREATE_ORG {0} {1}".format(item["company"], item["path"]))
    for item in result.retire_org:
        lines.append(
            "-- RETIRE_ORG {0} {1}".format(item["organizationId"], item["name"])
        )
    lines.extend([
        "",
        "-- 5.4 punch / OA / password tables are intentionally omitted",
        "COMMIT;",
        "",
    ])
    return "\n".join(lines)


def load_live_json(folder: Path) -> tuple[list[LiveOrg], list[dict[str, Any]], list[dict[str, Any]]]:
    tree = json.loads((folder / "org-tree.json").read_text(encoding="utf-8"))
    employees = json.loads((folder / "employees.json").read_text(encoding="utf-8"))
    accounts = json.loads((folder / "accounts.json").read_text(encoding="utf-8"))
    return flatten_live_tree(tree), employees, accounts


def main() -> int:
    parser = argparse.ArgumentParser(description="Roster org cutover dry-run")
    parser.add_argument("--roster", type=Path, default=ROSTER_XLSX)
    parser.add_argument("--exempt", type=Path, default=EXEMPT_XLSX)
    parser.add_argument("--live-json", type=Path, default=Path("/tmp/szhr-live"))
    parser.add_argument("--sql-out", type=Path)
    parser.add_argument("--report-out", type=Path)
    args = parser.parse_args()

    roster = parse_roster(args.roster)
    exempt = parse_exempt(args.exempt)
    live_orgs, employees, accounts = load_live_json(args.live_json)
    result = reconcile(roster, exempt, live_orgs, employees, accounts)
    payload = {
        "rosterRows": len(roster),
        "exempt": len(exempt),
        "liveOrgs": len(live_orgs),
        "liveEmployees": len(employees),
        "liveAccounts": len(accounts),
        "ambiguities": result.ambiguities,
        "rename": result.rename,
        "createOrg": result.create_org,
        "retireOrg": result.retire_org,
        "moveAssignment": result.move_assignment,
        "numberCorrection": result.number_correction,
        "terminate": result.terminate,
        "createEmployee": result.create_employee,
        "accountSync": result.account_sync,
        "standingExempt": len(result.standing_exempt),
        "protectedLogins": result.protected_logins,
    }
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    print(text)
    if args.report_out:
        args.report_out.write_text(text, encoding="utf-8")
    if result.ambiguities:
        return 2
    if args.sql_out:
        args.sql_out.write_text(emit_sql(result), encoding="utf-8")
        print("wrote", args.sql_out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
