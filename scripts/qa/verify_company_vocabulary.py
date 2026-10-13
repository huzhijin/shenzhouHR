#!/usr/bin/env python3
"""Fail when current product surfaces retain the superseded top-level vocabulary."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
TEXT_SUFFIXES = {
    ".env",
    ".java",
    ".js",
    ".json",
    ".jsx",
    ".md",
    ".properties",
    ".py",
    ".sh",
    ".sql",
    ".ts",
    ".tsx",
    ".xml",
    ".yaml",
    ".yml",
}

# Keep the forbidden words out of this gate's own source so self-scans remain useful.
LEGACY_BOUNDARY = re.compile(
    "legal" + r"(?:[_ -]?entit(?:y|ies))|" + "\u6cd5\u4eba",
    re.IGNORECASE,
)

ROOT_CURRENT_FILES = {
    ".env.example",
    "CLIENT_DEMO_GUIDE.md",
    "README.md",
}

HISTORICAL_PREFIXES = (
    "docs/docs-confirm/",
    "docs/verification/continued-delivery/",
    "docs/verification/wave1/",
    "docs/verification/wave2/",
    "docs/verification/wave3/runs/",
)

HISTORICAL_FILES = {
    "docs/contracts/wave5-w4-final-handoff.md",
    "docs/open-design-prompts.md",
    "docs/v1.9-rebaseline/00-evidence-inventory.md",
    (
        "openspec/changes/wave3-attendance-setup-and-policies/"
        "specs/wave3-verification/spec.md"
    ),
}

CURRENT_QA_FILES = {
    "scripts/qa/capture_wave3_mysql_semantics.py",
    "scripts/qa/produce_wave3_semantic_gates.py",
    "scripts/qa/verify-wave2-runtime-api.mjs",
    "scripts/qa/verify-wave3-normal-browser.mjs",
    "scripts/qa/wave3-runtime-common.mjs",
    "scripts/qa/wave3_runtime_provenance.py",
}

HISTORICAL_QA_FILES = {
    # This producer proves an immutable V1-V7 W3 evidence closure. Current
    # runtime provenance imports only its generic hashing/process helpers.
    "scripts/qa/verify_wave3_payroll_zero.py",
}

CHANGE_DEFINITION_PREFIX = "openspec/changes/company-dimension-unification/"

MIGRATION_PREFIX = "backend/src/main/resources/db/migration/"
MIGRATION_NAME = re.compile(r"^V(?P<version>[1-9][0-9]*)__.*[.]sql$")

V11_BRIDGE_LINES = (
    re.compile(r"^\s*RENAME TABLE legal_entity TO company;\s*$"),
    re.compile(r"^\s*RENAME COLUMN legal_entity_id TO company_id,\s*$"),
    re.compile(
        r"^\s*RENAME INDEX [A-Za-z0-9_]*legal_entity[A-Za-z0-9_]*"
        r"(?:\s+TO\s+[A-Za-z0-9_]+;)?\s*$"
    ),
    re.compile(r"^\s*WHERE scope_type = 'LEGAL_ENTITY';\s*$"),
)

CURRENT_TRANSITION_LINES = {
    "deploy/mysql/wave1-local-mysql.sh": (
        re.compile(r"^\s*AND TABLE_NAME = 'legal_entity';$"),
        re.compile(r"^\s*AND COLUMN_NAME = 'legal_entity_id';$"),
        re.compile(r"^\s*WHERE scope_type = 'LEGAL_ENTITY';$"),
        re.compile(r"^\s*\(scope_type = 'LEGAL_ENTITY'$"),
        re.compile(
            r"^\s*AND legal_entity_id IS NOT NULL "
            r"AND organization_id IS NULL\)$"
        ),
        re.compile(
            r"^\s*AND legal_entity_id IS NULL "
            r"AND organization_id IS NOT NULL\)$"
        ),
        re.compile(
            r"^\s*AND legal_entity_id IS NULL "
            r"AND organization_id IS NULL\)$"
        ),
    ),
    "deploy/mysql/wave2-local-mysql.sh": (
        re.compile(r"^\s*AND TABLE_NAME = 'legal_entity';?$"),
        re.compile(r"^\s*AND COLUMN_NAME = 'legal_entity_id';$"),
        re.compile(r"^\s*WHERE scope_type = 'LEGAL_ENTITY';$"),
        re.compile(r'^\s*printf .+ "legal_entity legal_entity_id"$'),
        re.compile(r"^\s*'fk_people_import_legal_entity',$"),
        re.compile(r"^\s*'fk_people_publication_legal_entity',$"),
        re.compile(r"^\s*\(scope_type = 'LEGAL_ENTITY'$"),
        re.compile(
            r"^\s*AND legal_entity_id IS NOT NULL "
            r"AND organization_id IS NULL\)$"
        ),
        re.compile(
            r"^\s*AND legal_entity_id IS NULL "
            r"AND organization_id IS NOT NULL\)$"
        ),
        re.compile(
            r"^\s*AND legal_entity_id IS NULL "
            r"AND organization_id IS NULL\)$"
        ),
    ),
    "deploy/mysql/wave3-local-mysql.sh": (
        re.compile(r'^\s*"min\(legal_entity_id\)",$'),
        re.compile(
            r'^\s*if schema_mode == "latest" '
            r'and column == "legal_entity_id":$'
        ),
        re.compile(r'^\s*target7\) company_column="legal_entity_id" ;;$'),
        re.compile(
            r"^(?:MEAL_DEDUCTION|LATE_GRACE|MONTHLY_LATE_EXEMPTION)"
            r"\|.*\"legalEntityId\":.*$"
        ),
        re.compile(
            r'^\s*and name == "ix_attendance_policy_scope_legal_entity"$'
        ),
        re.compile(r"^bootstrap_fixed_legal_entity_at_v6\(\) \{$"),
        re.compile(
            r'^    \|\| fail "The fixed legal-entity bootstrap is '
            r'authorized only at the exact V6 boundary\."$'
        ),
        re.compile(r"^\s*INSERT IGNORE INTO legal_entity \($"),
        re.compile(r"^\s*legal_entity_id, code, name, status, created_at$"),
        re.compile(r"^\s*'W3_BASELINE_LEGAL_ENTITY',$"),
        re.compile(r"^\s*'W3 verification baseline legal entity',$"),
        re.compile(r"^\s*FROM legal_entity$"),
        re.compile(
            r"^\s*WHERE legal_entity_id = "
            r"'30000000-0000-0000-0000-000000000001'$"
        ),
        re.compile(r"^\s*AND code = 'W3_BASELINE_LEGAL_ENTITY'$"),
        re.compile(
            r"^\s*AND name = 'W3 verification baseline legal entity'$"
        ),
        re.compile(
            r'^    \|\| fail "The exact fixed legal-entity fixture conflicts '
            r'with existing V6 data\."$'
        ),
        re.compile(
            r'^  log "W3_FIXED_TENANT_BOOTSTRAP=PASS .*'
            r'boundary=V6 legal_entity_id=.*"$'
        ),
        re.compile(
            r'^\s*bootstrap_fixed_legal_entity_at_v6 "\$(?:TEST|DEV)_DATABASE"$'
        ),
    ),
    "scripts/qa/produce_wave3_semantic_gates.py": (
        re.compile(
            r'^\s*"legalEntityId": fixture\["baselineLegalEntityId"\],$'
        ),
    ),
    "scripts/qa/capture_wave3_mysql_semantics.py": (
        re.compile(r'^\s*or fixture\.get\("baselineLegalEntityId"\)$'),
        re.compile(r'^\s*"legalEntityId",$'),
        re.compile(r"^\s*scope\.scope_id, scope\.legal_entity_id,$"),
        re.compile(
            r'^\s*"legalEntityId": fixture\["baselineLegalEntityId"\],$'
        ),
    ),
}

CURRENT_TRANSITION_EXACT_LINES = {
    "deploy/mysql/lib/company-dimension-cutover.sh": frozenset(
        {
            'readonly COMPANY_CUTOVER_V10_TABLE_PATTERN="^(legal_entity|employee|organization_identity|auth_data_scope|people_import_batch|people_import_publication|location|shift_template|work_calendar|attendance_group|attendance_policy_scope|attendance_source|source_device|device_person_binding|attendance_evidence_subject_lock|raw_attendance_fact|effective_attendance_event|duplicate_review_group|evidence_interval_slice|attendance_recalculation_intent|punch_mapping_profile|punch_import_batch|punch_import_file|attendance_report_projection|attendance_report_daily_fact|attendance_report_oa_fact|attendance_report_exception_fact|attendance_report_time_account_fact|attendance_report_export_job)$"',
            '      boundary_table="legal_entity"',
            '      boundary_column="legal_entity_id"',
            "        (scope_type = 'LEGAL_ENTITY'",
            "          AND legal_entity_id IS NOT NULL",
            "          AND legal_entity_id IS NULL",
            "        WHEN (TABLE_NAME = 'legal_entity'",
            "                AND INDEX_NAME = 'uq_legal_entity_code')",
            "                AND INDEX_NAME = 'ix_employee_legal_entity_status')",
            "                AND INDEX_NAME = 'ix_organization_identity_legal_entity')",
            "                AND INDEX_NAME = 'ix_attendance_policy_scope_legal_entity')",
            "          'uq_legal_entity_code',",
            "          'ix_employee_legal_entity_status',",
            "          'ix_organization_identity_legal_entity',",
            "          'ix_attendance_policy_scope_legal_entity',",
            "          AND COLUMN_NAME = 'legal_entity_id'",
            "    WHERE scope_type NOT IN ('LEGAL_ENTITY', 'ORGANIZATION', 'SELF')",
            "            (scope_type = 'LEGAL_ENTITY'",
            "              AND legal_entity_id IS NOT NULL",
            "              AND legal_entity_id IS NULL",
            "          AND TABLE_NAME = 'legal_entity'",
            "        COLUMN_NAME = 'legal_entity_id'",
            "        OR REFERENCED_COLUMN_NAME = 'legal_entity_id'",
            "        OR REFERENCED_TABLE_NAME = 'legal_entity'",
        }
    ),
    "deploy/mysql/wave3-local-mysql.sh": frozenset(
        {
            'readonly W3_COMPANY_V10_TABLE_PATTERN="^(legal_entity|employee|organization_identity|auth_data_scope|people_import_batch|people_import_publication|location|shift_template|work_calendar|attendance_group|attendance_policy_scope|attendance_source|source_device|device_person_binding|attendance_evidence_subject_lock|raw_attendance_fact|effective_attendance_event|duplicate_review_group|evidence_interval_slice|attendance_recalculation_intent|punch_mapping_profile|punch_import_batch|punch_import_file|attendance_report_projection|attendance_report_daily_fact|attendance_report_oa_fact|attendance_report_exception_fact|attendance_report_time_account_fact|attendance_report_export_job)$"',
            '      boundary_table="legal_entity"',
            '      boundary_column="legal_entity_id"',
            '        boundary_column="legal_entity_id"',
            "company|legal_entity|company|legal_entity_id|company_id",
            "attendance_evidence_subject_lock|attendance_evidence_subject_lock|attendance_evidence_subject_lock|legal_entity_id, employee_id|company_id, employee_id",
            "        (scope_type = 'LEGAL_ENTITY'",
            "          AND legal_entity_id IS NOT NULL",
            "          AND legal_entity_id IS NULL",
            "        WHEN (TABLE_NAME = 'legal_entity'",
            "                AND INDEX_NAME = 'uq_legal_entity_code')",
            "                AND INDEX_NAME = 'ix_employee_legal_entity_status')",
            "                AND INDEX_NAME = 'ix_organization_identity_legal_entity')",
            "                AND INDEX_NAME = 'ix_attendance_policy_scope_legal_entity')",
            "          'uq_legal_entity_code',",
            "          'ix_employee_legal_entity_status',",
            "          'ix_organization_identity_legal_entity',",
            "          'ix_attendance_policy_scope_legal_entity',",
            "          AND COLUMN_NAME = 'legal_entity_id'",
            "      AND TABLE_NAME <> 'legal_entity'",
            "      AND COLUMN_NAME = 'legal_entity_id'",
            "      AND REFERENCED_TABLE_NAME = 'legal_entity'",
            "      AND REFERENCED_COLUMN_NAME = 'legal_entity_id';",
            "    WHERE scope_type NOT IN ('LEGAL_ENTITY', 'ORGANIZATION', 'SELF')",
            "            (scope_type = 'LEGAL_ENTITY'",
            "              AND legal_entity_id IS NOT NULL",
            "              AND legal_entity_id IS NULL",
            "          (scope_type = 'LEGAL_ENTITY'",
            "            AND legal_entity_id IS NOT NULL",
            "            AND legal_entity_id IS NULL",
            "        WHEN TABLE_NAME = 'legal_entity'",
            "          AND INDEX_NAME = 'uq_legal_entity_code'",
            "          AND INDEX_NAME = 'uq_legal_entity_code_probe'",
            "        ALTER TABLE legal_entity",
            "          RENAME INDEX uq_legal_entity_code_probe",
            "          TO uq_legal_entity_code;",
            "        ALTER TABLE legal_entity DROP INDEX uq_company_code;",
            "      scope_id, scope_type, legal_entity_id, organization_id,",
            "          AND legal_entity_id IS NOT NULL)",
            "    ALTER TABLE legal_entity",
            "      RENAME INDEX uq_legal_entity_code",
            "      TO uq_legal_entity_code_probe;",
            "      RENAME INDEX uq_legal_entity_code_probe",
            "      TO uq_legal_entity_code;",
            "    CREATE INDEX uq_company_code ON legal_entity (status);",
            "    ALTER TABLE legal_entity DROP INDEX uq_company_code;",
            "      employee_id, legal_entity_id, employee_number, display_name,",
            "          AND TABLE_NAME = 'legal_entity'",
            "        COLUMN_NAME = 'legal_entity_id'",
            "        OR REFERENCED_COLUMN_NAME = 'legal_entity_id'",
            "        OR REFERENCED_TABLE_NAME = 'legal_entity'",
            "verify_fixed_legal_entity_before_v11() {",
            "    INSERT INTO legal_entity (",
            "      organization_id, legal_entity_id, identity_status, created_at",
            "        'LEGAL_ENTITY',",
            "      attendance_source_id, legal_entity_id, source_code, source_type,",
            "      legal_entity_id, employee_id, touched_at",
            "      raw_attendance_fact_id, attendance_source_id, legal_entity_id,",
            "      attendance_report_projection_id, legal_entity_id,",
            "      legal_entity_id, employee_id, employee_version_id,",
            "      period_start, legal_entity_id, organization_id, employee_id,",
            "      (SELECT COUNT(*) FROM legal_entity",
            "       WHERE legal_entity_id IN (",
            "    verify_fixed_legal_entity_before_v11 \\",
            '    row = row.replace("legal_entity_id", "company_id")',
            '    row = row.replace("legal_entity", "company")',
            '        row = row.replace("legal_entity_id", "company_id")',
            '        row = row.replace("legal_entity", "company")',
        }
    ),
}

SCAN_ROOTS = (
    "api",
    "backend/src/main",
    "backend/src/test/resources",
    "frontend/src",
    "docs",
    "deploy",
    "openspec/changes",
)


def relative(path: Path) -> str:
    return path.relative_to(REPOSITORY_ROOT).as_posix()


def is_text_candidate(path: Path) -> bool:
    return path.is_file() and (
        path.name in ROOT_CURRENT_FILES or path.suffix.lower() in TEXT_SUFFIXES
    )


def iter_files() -> list[Path]:
    files: set[Path] = set()
    for name in ROOT_CURRENT_FILES:
        candidate = REPOSITORY_ROOT / name
        if candidate.is_file():
            files.add(candidate)
    for root_name in SCAN_ROOTS:
        root = REPOSITORY_ROOT / root_name
        if not root.exists():
            continue
        for candidate in root.rglob("*"):
            if is_text_candidate(candidate):
                files.add(candidate)
    for path_name in CURRENT_QA_FILES | HISTORICAL_QA_FILES:
        candidate = REPOSITORY_ROOT / path_name
        if candidate.is_file():
            files.add(candidate)
    return sorted(files, key=relative)


def migration_version(path: str) -> int | None:
    if not path.startswith(MIGRATION_PREFIX):
        return None
    match = MIGRATION_NAME.fullmatch(Path(path).name)
    if match is None:
        return -1
    return int(match.group("version"))


def classification(path: str) -> str:
    version = migration_version(path)
    if version is not None:
        if 1 <= version <= 10:
            return "historical-migration"
        if version == 11:
            return "v11-bridge"
        return "current"

    if path in HISTORICAL_FILES or path.startswith(HISTORICAL_PREFIXES):
        return "historical-evidence"
    if path in HISTORICAL_QA_FILES:
        return "historical-qa-harness"
    if path in CURRENT_QA_FILES:
        return "current"
    if "/oracles/" in path and path.startswith("openspec/changes/"):
        return "historical-oracle"
    if path.startswith(CHANGE_DEFINITION_PREFIX):
        return "change-definition"

    if path in ROOT_CURRENT_FILES:
        return "current"
    if path.startswith("api/"):
        return "current"
    if path.startswith("backend/src/main/"):
        return "current"
    if path.startswith("backend/src/test/resources/"):
        return "current"
    if path.startswith("frontend/src/"):
        if "/test/" in path or ".test." in path:
            return "ignored-test-fixture"
        return "current"
    if path.startswith("docs/"):
        return "current"
    if path.startswith("deploy/"):
        if path.startswith("deploy/mysql/") and Path(path).suffix == ".sh":
            return "current"
        return "current" if Path(path).suffix.lower() == ".md" else "ignored-script"
    if path.startswith("openspec/changes/"):
        return "current" if Path(path).suffix.lower() == ".md" else "ignored-oracle-data"
    return "ignored"


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def legacy_hits(path: Path) -> list[tuple[int, str]]:
    hits: list[tuple[int, str]] = []
    for line_number, line in enumerate(read_lines(path), start=1):
        for match in LEGACY_BOUNDARY.finditer(line):
            hits.append((line_number, match.group(0)))
    return hits


def valid_v11_bridge_line(line: str) -> bool:
    return any(pattern.fullmatch(line) for pattern in V11_BRIDGE_LINES)


def valid_current_transition_line(path: str, line: str) -> bool:
    return line in CURRENT_TRANSITION_EXACT_LINES.get(path, ()) or any(
        pattern.fullmatch(line)
        for pattern in CURRENT_TRANSITION_LINES.get(path, ())
    )


def render_violation(path: str, line_number: int, line: str) -> str:
    return f"{path}:{line_number}:{line}"


def explain_allowlist() -> None:
    print("COMPANY_VOCABULARY_ALLOWLIST")
    print("- V1-V10 SQL under backend/src/main/resources/db/migration/: pinned history")
    print("- V11 SQL: only exact RENAME TABLE/COLUMN/INDEX and scope UPDATE bridge lines")
    print("- V12+ SQL: no legacy boundary vocabulary")
    for prefix in HISTORICAL_PREFIXES:
        print(f"- {prefix}: frozen historical evidence")
    for path in sorted(HISTORICAL_FILES):
        print(f"- {path}: exact historical evidence")
    print("- openspec/changes/**/oracles/: retained oracle data")
    print(f"- {CHANGE_DEFINITION_PREFIX}: the change definition names the removed contract")
    for path in sorted(HISTORICAL_QA_FILES):
        print(f"- {path}: exact frozen pre-V11 QA harness")
    for path in sorted(CURRENT_TRANSITION_LINES):
        print(f"- {path}: exact pre-V11 checkpoint/absence-check bridge lines only")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify that current product surfaces use only the company boundary."
    )
    parser.add_argument(
        "--explain-allowlist",
        action="store_true",
        help="print the exact historical and migration exceptions before scanning",
    )
    args = parser.parse_args()

    if args.explain_allowlist:
        explain_allowlist()

    violations: list[tuple[str, int, str]] = []
    scanned_current_files = 0
    allowlisted_files_with_hits = 0
    v11_bridge_hits = 0

    for path in iter_files():
        path_text = relative(path)
        category = classification(path_text)
        hits = legacy_hits(path)
        if not hits:
            if category == "current":
                scanned_current_files += 1
            continue

        if category in {
            "historical-migration",
            "historical-evidence",
            "historical-oracle",
            "historical-qa-harness",
            "change-definition",
        }:
            allowlisted_files_with_hits += 1
            continue

        if category == "v11-bridge":
            lines = read_lines(path)
            for line_number, _token in hits:
                line = lines[line_number - 1]
                if valid_v11_bridge_line(line):
                    v11_bridge_hits += 1
                else:
                    violations.append((path_text, line_number, line))
            continue

        if category == "current":
            scanned_current_files += 1
            lines = read_lines(path)
            for line_number, _token in hits:
                line = lines[line_number - 1]
                if not valid_current_transition_line(path_text, line):
                    violations.append((path_text, line_number, line))

    if violations:
        print("COMPANY_VOCABULARY_GATE=FAIL", file=sys.stderr)
        for path, line_number, line in violations:
            print(render_violation(path, line_number, line), file=sys.stderr)
        print(
            "Only the printed historical paths and exact V11 bridge lines are allowed.",
            file=sys.stderr,
        )
        return 1

    print(
        "COMPANY_VOCABULARY_GATE=PASS "
        f"current_files={scanned_current_files} "
        f"allowlisted_files_with_hits={allowlisted_files_with_hits} "
        f"v11_bridge_hits={v11_bridge_hits}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
