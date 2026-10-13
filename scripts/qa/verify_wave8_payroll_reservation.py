#!/usr/bin/env python3
"""Fail-closed W8 scope and Unicode zero-discoverability verifier."""

from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PASS_MARKER = "W8_PAYROLL_ZERO_DISCOVERABILITY=PASS"
TEXT_SUFFIXES = {
    ".css",
    ".html",
    ".java",
    ".js",
    ".json",
    ".jsx",
    ".map",
    ".mjs",
    ".svg",
    ".sql",
    ".ts",
    ".tsx",
    ".txt",
    ".webmanifest",
    ".properties",
    ".xml",
    ".yaml",
    ".yml",
}
DENY_PATTERN = re.compile(
    r"pay[\s._/-]*roll|pay[\s._/-]*slips?|薪资|工资(?:条|核算)?"
)
PROHIBITED_IMPLEMENTATION_PATTERN = re.compile(
    r"pay[\s._/-]*slips?|"
    r"\btax(?:ation|able)?\b|"
    r"social[\s._/-]*insurance|"
    r"housing[\s._/-]*fund|"
    r"bank[\s._/-]*(?:account|file)|"
    r"payment[\s._/-]*file|"
    r"工资条|个税|社保|公积金|银行文件"
)

FRONTEND_INDEX = Path("frontend/index.html")
FRONTEND_SOURCE = Path("frontend/src")
FRONTEND_BUILDS = (Path("frontend/dist/prod"), Path("frontend/dist/demo"))
BACKEND_REST_ROOT = Path("backend/src/main/java/com/szsemicon/hr")
OPENAPI_DOCUMENT = Path("api/openapi.yaml")
PAYROLL_MAIN_ROOT = Path(
    "backend/src/main/java/com/szsemicon/hr/payroll"
)
MIGRATION_ROOT = Path("backend/src/main/resources/db/migration")


class VerificationError(RuntimeError):
    pass


@dataclass(frozen=True)
class Finding:
    relative_path: str
    line_number: int


def normalize(value: str) -> str:
    return unicodedata.normalize("NFKC", value).casefold()


def deny_match_count(value: str) -> int:
    return len(tuple(DENY_PATTERN.finditer(normalize(value))))


def iter_text_files(root: Path) -> Iterator[Path]:
    if root.is_file():
        if root.suffix.lower() in TEXT_SUFFIXES:
            yield root
        return
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.suffix.lower() in TEXT_SUFFIXES:
            yield path


def is_frontend_test_file(path: Path) -> bool:
    relative = path.relative_to(REPOSITORY_ROOT / FRONTEND_SOURCE)
    return (
        "test" in relative.parts
        or ".test." in path.name
        or ".spec." in path.name
    )


def scan_files(
    paths: Iterable[Path],
    pattern: re.Pattern[str],
) -> list[Finding]:
    findings: list[Finding] = []
    for path in sorted(set(paths)):
        text = path.read_text(encoding="utf-8", errors="strict")
        for line_number, line in enumerate(text.splitlines(), start=1):
            if pattern.search(normalize(line)):
                findings.append(
                    Finding(repository_relative(path), line_number)
                )
    return findings


def repository_relative(path: Path) -> str:
    resolved = path.resolve()
    try:
        return resolved.relative_to(REPOSITORY_ROOT.resolve()).as_posix()
    except ValueError:
        return path.name


def frontend_product_files() -> tuple[list[Path], list[Path]]:
    index = REPOSITORY_ROOT / FRONTEND_INDEX
    source = REPOSITORY_ROOT / FRONTEND_SOURCE
    if not index.is_file():
        raise VerificationError(f"required product file is missing: {FRONTEND_INDEX}")
    if not source.is_dir():
        raise VerificationError(f"required product root is missing: {FRONTEND_SOURCE}")
    source_files = [index]
    source_files.extend(
        path for path in iter_text_files(source)
        if not is_frontend_test_file(path)
    )
    if len(source_files) < 2:
        raise VerificationError("frontend product source scope is unexpectedly empty")
    build_files: list[Path] = []
    for relative in FRONTEND_BUILDS:
        root = REPOSITORY_ROOT / relative
        if root.exists():
            if not root.is_dir():
                raise VerificationError(
                    f"frontend build root is not a directory: {relative}"
                )
            current = list(iter_text_files(root))
            if not current:
                raise VerificationError(
                    f"frontend build root has no text assets: {relative}"
                )
            build_files.extend(current)
    return source_files, build_files


def public_contract_files() -> list[Path]:
    openapi = REPOSITORY_ROOT / OPENAPI_DOCUMENT
    if not openapi.is_file():
        raise VerificationError(f"required public contract is missing: {OPENAPI_DOCUMENT}")
    roots = sorted(
        path
        for path in BACKEND_REST_ROOT.rglob("rest")
        if path.is_dir() and "interfaces" in path.parts
    )
    files = [openapi]
    for root in roots:
        files.extend(iter_text_files(root))
    return files


def verify_no_rest_or_grant() -> None:
    forbidden_rest = REPOSITORY_ROOT / PAYROLL_MAIN_ROOT / "interfaces" / "rest"
    if forbidden_rest.exists():
        raise VerificationError(
            "payroll REST surface exists: "
            + repository_relative(forbidden_rest)
        )
    grant_findings = scan_files(
        iter_text_files(REPOSITORY_ROOT / MIGRATION_ROOT),
        re.compile(r"payroll:reservation_read"),
    )
    if grant_findings:
        raise VerificationError(format_findings(
            "PAYROLL reservation capability is seeded or granted",
            grant_findings,
        ))


def format_findings(label: str, findings: Sequence[Finding]) -> str:
    safe_locations = ", ".join(
        f"{finding.relative_path}:{finding.line_number}"
        for finding in findings
    )
    return f"{label}: {safe_locations}"


def verify() -> str:
    source_files, build_files = frontend_product_files()
    frontend_findings = scan_files(
        (*source_files, *build_files),
        DENY_PATTERN,
    )
    if frontend_findings:
        raise VerificationError(format_findings(
            "frontend Unicode discoverability hit",
            frontend_findings,
        ))

    contracts = public_contract_files()
    public_findings = scan_files(contracts, DENY_PATTERN)
    if public_findings:
        raise VerificationError(format_findings(
            "public REST/OpenAPI discoverability hit",
            public_findings,
        ))

    verify_no_rest_or_grant()
    payroll_sources = list(iter_text_files(REPOSITORY_ROOT / PAYROLL_MAIN_ROOT))
    if not payroll_sources:
        raise VerificationError("payroll reservation implementation is missing")
    prohibited_findings = scan_files(
        payroll_sources,
        PROHIBITED_IMPLEMENTATION_PATTERN,
    )
    if prohibited_findings:
        raise VerificationError(format_findings(
            "prohibited W8 scope hit",
            prohibited_findings,
        ))

    return (
        f"{PASS_MARKER} "
        f"sourceFiles={len(source_files)} "
        f"buildFiles={len(build_files)} "
        f"publicContractFiles={len(contracts)}"
    )


def self_test() -> None:
    positives = (
        "PAYROLL",
        "Pay_Roll",
        "ＰａＹＲＯＬＬ",
        "PAY-SLIPS",
        "薪资",
        "工资核算",
    )
    for value in positives:
        if deny_match_count(value) < 1:
            raise VerificationError("normalization self-test failed")
    if deny_match_count("考勤设置") != 0:
        raise VerificationError("normalization control self-test failed")
    print("W8_PAYROLL_NORMALIZATION_SELF_TEST=PASS")


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "command",
        choices=("verify", "self-test"),
        nargs="?",
        default="verify",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        if args.command == "self-test":
            self_test()
        else:
            print(verify())
        return 0
    except (OSError, UnicodeError, VerificationError) as error:
        print(f"W8_PAYROLL_RESERVATION=FAIL {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
