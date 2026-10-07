#!/usr/bin/env python3
"""Atomically export the approved four-company people baseline.

This is an orchestration layer over ``export_people_initial_data``. It performs
only read-only source queries, validates the signed company and row-count
contracts, and publishes the complete twelve-workbook directory in one rename.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

if __package__:
    from . import export_people_initial_data as single_exporter
else:  # pragma: no cover - exercised by direct CLI invocation
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import export_people_initial_data as single_exporter


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_COMPANY_CATALOG = (
    REPOSITORY_ROOT / "deploy/baota/config/initial-companies.tsv"
)
SAFE_OUTPUT_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
MANIFEST_NAME = "MANIFEST.json"
RUNBOOK_NAME = "RUNBOOK.txt"
CHECKSUM_NAME = "SHA256SUMS"
TEMPLATE_TYPES = {
    "organizations": "ORGANIZATION",
    "employees": "EMPLOYEE",
    "employments": "EMPLOYMENT",
}


@dataclass(frozen=True)
class CompanyContract:
    code: str
    name: str
    organizations: int
    employees: int
    employments: int

    @property
    def counts(self) -> tuple[int, int, int]:
        return (self.organizations, self.employees, self.employments)


@dataclass(frozen=True)
class CollectedCompany:
    contract: CompanyContract
    organizations: list[dict[str, Any]]
    employees: list[dict[str, Any]]
    employments: list[dict[str, Any]]


COMPANY_CONTRACTS: tuple[CompanyContract, ...] = (
    CompanyContract("SZSZ", "上海昇州半导体科技有限公司", 2, 1, 1),
    CompanyContract("SZJN", "上海晟州聚能半导体科技有限公司", 17, 35, 35),
    CompanyContract("SZSC", "江苏神州半导体科技股份有限公司", 130, 571, 571),
    CompanyContract("SZXY", "江苏芯越半导体科技有限公司", 7, 8, 8),
)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export the signed four-company people baseline atomically."
    )
    parser.add_argument("--mysql-bin", required=True, type=Path)
    parser.add_argument("--defaults-extra-file", required=True, type=Path)
    parser.add_argument("--database", required=True)
    parser.add_argument(
        "--company-catalog", type=Path, default=DEFAULT_COMPANY_CATALOG
    )
    parser.add_argument("--output-dir", required=True, type=Path)
    return parser.parse_args(argv)


def expected_catalog_bytes() -> bytes:
    return "".join(
        f"{contract.code}\t{contract.name}\n" for contract in COMPANY_CONTRACTS
    ).encode("utf-8")


def load_signed_company_catalog(path: Path) -> tuple[CompanyContract, ...]:
    if path.is_symlink() or not path.is_file() or not os.access(path, os.R_OK):
        raise ValueError(
            f"company catalog must be a readable regular file, not a symlink: {path}"
        )
    expected = expected_catalog_bytes()
    if path.stat().st_size != len(expected) or path.read_bytes() != expected:
        raise ValueError("company catalog differs from the signed four-company contract")
    return COMPANY_CONTRACTS


def company_arguments(
    args: argparse.Namespace, contract: CompanyContract, output_dir: Path
) -> argparse.Namespace:
    return argparse.Namespace(
        mysql_bin=args.mysql_bin,
        defaults_extra_file=args.defaults_extra_file,
        database=args.database,
        source_company_code=contract.code,
        target_company_code=contract.code,
        output_dir=output_dir,
        expected_organizations=contract.organizations,
        expected_employees=contract.employees,
        expected_employments=contract.employments,
    )


def validate_args(
    args: argparse.Namespace,
) -> tuple[CompanyContract, ...]:
    contracts = load_signed_company_catalog(args.company_catalog)
    if not SAFE_OUTPUT_NAME.fullmatch(args.output_dir.name):
        raise ValueError("output directory name contains unsafe characters")
    if os.path.lexists(args.output_dir):
        raise ValueError(f"output directory already exists: {args.output_dir}")
    for contract in contracts:
        single_exporter.validate_args(
            company_arguments(args, contract, args.output_dir / contract.code)
        )
    return contracts


def load_active_company_set(
    args: argparse.Namespace, contracts: tuple[CompanyContract, ...]
) -> None:
    query_args = company_arguments(args, contracts[0], args.output_dir / contracts[0].code)
    rows = single_exporter.mysql_json_rows(
        query_args,
        "SELECT JSON_OBJECT("
        "'companyCode', code, 'companyName', name, 'status', status) "
        "FROM company WHERE status = 'ACTIVE' ORDER BY BINARY code",
    )
    expected = {contract.code: contract.name for contract in contracts}
    actual: dict[str, str] = {}
    for row in rows:
        code = row.get("companyCode")
        name = row.get("companyName")
        status = row.get("status")
        if not isinstance(code, str) or not isinstance(name, str) or status != "ACTIVE":
            raise ValueError("active company query returned an invalid row")
        if code in actual:
            raise ValueError(f"active company query returned duplicate code: {code}")
        actual[code] = name
    if actual != expected:
        raise ValueError(
            "active company codes or names differ from the signed four-company contract"
        )


def collect_company(
    args: argparse.Namespace, contract: CompanyContract
) -> CollectedCompany:
    source_args = company_arguments(args, contract, args.output_dir / contract.code)
    company = single_exporter.load_company(source_args)
    if (
        company.get("companyCode") != contract.code
        or company.get("companyName") != contract.name
        or company.get("status") != "ACTIVE"
    ):
        raise ValueError(f"source company identity drifted for {contract.code}")
    organizations = single_exporter.load_organizations(source_args)
    employees = single_exporter.load_employees(source_args)
    employments = single_exporter.load_employments(source_args)
    single_exporter.validate_dataset(
        organizations, employees, employments, contract.counts
    )
    return CollectedCompany(contract, organizations, employees, employments)


def collect_all_companies(
    args: argparse.Namespace, contracts: tuple[CompanyContract, ...]
) -> list[CollectedCompany]:
    load_active_company_set(args, contracts)
    collected = [collect_company(args, contract) for contract in contracts]
    load_active_company_set(args, contracts)
    return collected


def dataset_map(company: CollectedCompany) -> dict[str, list[dict[str, Any]]]:
    return {
        "organizations": company.organizations,
        "employees": company.employees,
        "employments": company.employments,
    }


def write_company_workbooks(
    staging_dir: Path, company: CollectedCompany
) -> list[dict[str, Any]]:
    company_dir = staging_dir / company.contract.code
    company_dir.mkdir(mode=0o700)
    os.chmod(company_dir, 0o700)
    datasets = dataset_map(company)
    actual_counts = tuple(len(datasets[key]) for key in single_exporter.WORKBOOKS)
    if actual_counts != company.contract.counts:
        raise ValueError(
            f"staged counts drifted for {company.contract.code}: "
            f"expected {company.contract.counts}, got {actual_counts}"
        )

    records: list[dict[str, Any]] = []
    for key, rows in datasets.items():
        file_name, fields = single_exporter.WORKBOOKS[key]
        workbook_path = company_dir / file_name
        single_exporter.write_workbook(workbook_path, file_name, fields, rows)
        os.chmod(workbook_path, 0o600)
        single_exporter.validate_workbook(workbook_path, fields, len(rows))
        records.append(
            {
                "path": workbook_path.relative_to(staging_dir).as_posix(),
                "mediaType": single_exporter.XLSX_MEDIA_TYPE,
                "sha256": single_exporter.sha256(workbook_path),
                "sizeBytes": workbook_path.stat().st_size,
                "dataset": key,
                "templateType": TEMPLATE_TYPES[key],
                "targetCompanyCode": company.contract.code,
                "targetCompanyName": company.contract.name,
                "rowCount": len(rows),
            }
        )
    return records


def write_manifest(
    staging_dir: Path,
    args: argparse.Namespace,
    companies: list[CollectedCompany],
    records_by_company: dict[str, list[dict[str, Any]]],
) -> Path:
    totals = {
        "organizations": sum(company.contract.organizations for company in companies),
        "employees": sum(company.contract.employees for company in companies),
        "employments": sum(company.contract.employments for company in companies),
    }
    manifest = {
        "formatVersion": 1,
        "packageType": "FOUR_COMPANY_PEOPLE_INITIAL_DATA",
        "generatedAtUtc": datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat(),
        "sourceDatabase": args.database,
        "companyCatalogSha256": hashlib.sha256(expected_catalog_bytes()).hexdigest(),
        "companyCount": len(companies),
        "importBatchCount": len(companies) * len(single_exporter.WORKBOOKS),
        "scope": ["ORGANIZATION", "EMPLOYEE", "EMPLOYMENT"],
        "totals": totals,
        "companies": [
            {
                "code": company.contract.code,
                "name": company.contract.name,
                "directory": company.contract.code,
                "counts": {
                    "organizations": company.contract.organizations,
                    "employees": company.contract.employees,
                    "employments": company.contract.employments,
                },
                "files": records_by_company[company.contract.code],
            }
            for company in companies
        ],
        "excluded": [
            "accounts",
            "credentials",
            "sessions",
            "auditHistory",
            "sourceAttachments",
            "attendanceConfiguration",
            "punches",
            "oaDocuments",
            "reportProjections",
        ],
    }
    path = staging_dir / MANIFEST_NAME
    path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.chmod(path, 0o600)
    return path


def write_runbook(staging_dir: Path, companies: list[CollectedCompany]) -> Path:
    total_organizations = sum(
        company.contract.organizations for company in companies
    )
    total_employees = sum(company.contract.employees for company in companies)
    total_employments = sum(company.contract.employments for company in companies)
    lines = [
        "神州 HR 四公司期初人员数据导入运行手册",
        "",
        "安全要求：本包含员工个人信息，只能使用批准的安全通道传输。",
        "先在客户数据库完成全量备份，再执行 sha256sum -c SHA256SUMS。",
        "四家公司必须已经存在；不得在导入过程中创建、改名或合并公司。",
        "每个文件必须直接上传原文件，禁止用 Excel/WPS 打开后另存。",
        "",
        "12 个批次必须严格按以下顺序逐个完成：创建 → 上传 → 映射 → 预检 → 发布。",
        "每次预检只允许出现下列新增数量；更新、无变化、冲突和阻断都必须为 0。",
        "",
    ]
    sequence = 1
    labels = {
        "organizations": "组织",
        "employees": "员工",
        "employments": "任职",
    }
    for company in companies:
        for key in single_exporter.WORKBOOKS:
            file_name = single_exporter.WORKBOOKS[key][0]
            rows = len(dataset_map(company)[key])
            lines.append(
                f"{sequence:02d}. {company.contract.code} {company.contract.name} / "
                f"{file_name} / 类型“{labels[key]}” / 预期新增 {rows}"
            )
            sequence += 1
    lines.extend(
        [
            "",
            f"全部发布后应为：{len(companies)} 家公司、{total_organizations} 个组织、"
            f"{total_employees} 名员工、{total_employments} 条当前任职。",
            "任一公司、名称、数量、哈希、字段映射或预检结果不一致时立即停止，不得继续后续批次。",
            "本包不包含账号、密码、会话、考勤配置、打卡、OA 单据或报表结果。",
            "",
        ]
    )
    path = staging_dir / RUNBOOK_NAME
    path.write_text("\n".join(lines), encoding="utf-8")
    os.chmod(path, 0o600)
    return path


def write_checksums(staging_dir: Path) -> Path:
    files = sorted(
        path
        for path in staging_dir.rglob("*")
        if path.is_file() and path.name != CHECKSUM_NAME
    )
    checksum_path = staging_dir / CHECKSUM_NAME
    checksum_path.write_text(
        "".join(
            f"{single_exporter.sha256(path)}  "
            f"{path.relative_to(staging_dir).as_posix()}\n"
            for path in files
        ),
        encoding="utf-8",
    )
    os.chmod(checksum_path, 0o600)
    return checksum_path


def harden_permissions(staging_dir: Path) -> None:
    for path in [staging_dir, *staging_dir.rglob("*")]:
        if path.is_symlink():
            raise ValueError(f"symbolic links are forbidden in export package: {path}")
        if path.is_dir():
            os.chmod(path, 0o700)
        elif path.is_file():
            os.chmod(path, 0o600)
        else:
            raise ValueError(f"unexpected filesystem object in export package: {path}")


def validate_manifest(staging_dir: Path) -> None:
    manifest = json.loads((staging_dir / MANIFEST_NAME).read_text(encoding="utf-8"))
    expected_totals = {
        "organizations": sum(item.organizations for item in COMPANY_CONTRACTS),
        "employees": sum(item.employees for item in COMPANY_CONTRACTS),
        "employments": sum(item.employments for item in COMPANY_CONTRACTS),
    }
    if (
        manifest.get("formatVersion") != 1
        or manifest.get("packageType") != "FOUR_COMPANY_PEOPLE_INITIAL_DATA"
        or manifest.get("companyCatalogSha256")
        != hashlib.sha256(expected_catalog_bytes()).hexdigest()
        or manifest.get("companyCount") != 4
        or manifest.get("importBatchCount") != 12
        or manifest.get("totals") != expected_totals
    ):
        raise ValueError("top-level manifest differs from the four-company contract")

    companies = manifest.get("companies")
    if not isinstance(companies, list) or len(companies) != 4:
        raise ValueError("top-level manifest must describe exactly four companies")
    for contract, company in zip(COMPANY_CONTRACTS, companies, strict=True):
        expected_counts = {
            "organizations": contract.organizations,
            "employees": contract.employees,
            "employments": contract.employments,
        }
        if (
            company.get("code") != contract.code
            or company.get("name") != contract.name
            or company.get("directory") != contract.code
            or company.get("counts") != expected_counts
        ):
            raise ValueError(f"manifest company identity drifted for {contract.code}")
        records = company.get("files")
        if not isinstance(records, list) or len(records) != 3:
            raise ValueError(f"manifest workbook list is incomplete for {contract.code}")
        records_by_dataset = {record.get("dataset"): record for record in records}
        if set(records_by_dataset) != set(single_exporter.WORKBOOKS):
            raise ValueError(f"manifest dataset list is invalid for {contract.code}")
        for dataset, row_count in expected_counts.items():
            record = records_by_dataset[dataset]
            file_name = single_exporter.WORKBOOKS[dataset][0]
            relative_path = f"{contract.code}/{file_name}"
            workbook_path = staging_dir / relative_path
            if (
                record.get("path") != relative_path
                or record.get("mediaType") != single_exporter.XLSX_MEDIA_TYPE
                or record.get("templateType") != TEMPLATE_TYPES[dataset]
                or record.get("targetCompanyCode") != contract.code
                or record.get("targetCompanyName") != contract.name
                or record.get("rowCount") != row_count
                or record.get("sizeBytes") != workbook_path.stat().st_size
                or record.get("sha256") != single_exporter.sha256(workbook_path)
            ):
                raise ValueError(
                    f"manifest workbook record is invalid for {relative_path}"
                )


def validate_staged_package(staging_dir: Path) -> None:
    expected_company_codes = {contract.code for contract in COMPANY_CONTRACTS}
    expected_top_level = expected_company_codes | {
        MANIFEST_NAME,
        RUNBOOK_NAME,
        CHECKSUM_NAME,
    }
    if {path.name for path in staging_dir.iterdir()} != expected_top_level:
        raise ValueError("staged package has an unexpected top-level layout")
    expected_workbooks = {value[0] for value in single_exporter.WORKBOOKS.values()}
    for contract in COMPANY_CONTRACTS:
        company_dir = staging_dir / contract.code
        if not company_dir.is_dir() or company_dir.is_symlink():
            raise ValueError(f"company export directory is missing: {contract.code}")
        if {path.name for path in company_dir.iterdir()} != expected_workbooks:
            raise ValueError(f"company workbook layout is incomplete: {contract.code}")

    validate_manifest(staging_dir)
    checksum_lines = (staging_dir / CHECKSUM_NAME).read_text(
        encoding="utf-8"
    ).splitlines()
    if len(checksum_lines) != 14:
        raise ValueError("SHA256SUMS must cover twelve workbooks, manifest, and runbook")
    checked_paths: set[str] = set()
    for line in checksum_lines:
        digest, separator, relative = line.partition("  ")
        if separator != "  " or relative in checked_paths:
            raise ValueError("SHA256SUMS contains an invalid or duplicate entry")
        path = staging_dir / relative
        if (
            not path.is_file()
            or path.is_symlink()
            or single_exporter.sha256(path) != digest
        ):
            raise ValueError(f"SHA256SUMS verification failed for {relative}")
        checked_paths.add(relative)

    for path in [staging_dir, *staging_dir.rglob("*")]:
        expected_mode = 0o700 if path.is_dir() else 0o600
        if path.stat().st_mode & 0o777 != expected_mode:
            raise ValueError(f"package permission contract failed for {path.name}")


def publish_package(
    args: argparse.Namespace, companies: list[CollectedCompany]
) -> Path:
    parent = args.output_dir.parent
    parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    if parent.is_symlink() or not parent.is_dir():
        raise ValueError(f"output parent must be a real directory: {parent}")
    if os.path.lexists(args.output_dir):
        raise ValueError(f"output directory already exists: {args.output_dir}")

    staging_dir = Path(
        tempfile.mkdtemp(prefix=f".{args.output_dir.name}.staging-", dir=parent)
    )
    published = False
    try:
        records_by_company = {
            company.contract.code: write_company_workbooks(staging_dir, company)
            for company in companies
        }
        write_manifest(staging_dir, args, companies, records_by_company)
        write_runbook(staging_dir, companies)
        write_checksums(staging_dir)
        harden_permissions(staging_dir)
        validate_staged_package(staging_dir)
        if os.path.lexists(args.output_dir):
            raise ValueError(f"output directory appeared during export: {args.output_dir}")
        os.rename(staging_dir, args.output_dir)
        published = True
        return args.output_dir
    finally:
        if not published and staging_dir.exists():
            shutil.rmtree(staging_dir)


def main(argv: Sequence[str] | None = None) -> None:
    previous_umask = os.umask(0o077)
    try:
        args = parse_args(argv)
        contracts = validate_args(args)
        companies = collect_all_companies(args, contracts)
        output_dir = publish_package(args, companies)
        print(
            json.dumps(
                {
                    "outputDirectory": str(output_dir),
                    "companyCount": 4,
                    "importBatchCount": 12,
                    "totals": {
                        "organizations": 156,
                        "employees": 615,
                        "employments": 615,
                    },
                },
                ensure_ascii=False,
            )
        )
    finally:
        os.umask(previous_umask)


if __name__ == "__main__":
    main()
