#!/usr/bin/env python3
"""Produce the W3 zero-discoverability leaf from frozen source and live runtime.

The producer is intentionally fail closed.  It publishes no output directory
unless source, build, database, role, browser, API, Unicode scan, allowlist and
request-target-exclusion checks all finish successfully.
"""

from __future__ import annotations

import argparse
import base64
import ctypes
import ctypes.util
import errno
import hashlib
import hmac
import io
import json
import os
import re
import shlex
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib
from dataclasses import dataclass
from datetime import datetime, timezone
from email.message import Message
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, Sequence


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
EXPECTED_REPOSITORY_ROOT = REPOSITORY_ROOT
EVIDENCE_ID = "W3-VER-PAYROLL-ZERO"
PASS_MARKER = "W3_PAYROLL_ZERO_DISCOVERABILITY=PASS"
CONTEXT_PREFIX = "W3_EVIDENCE_CONTEXT=PASS"
HISTORICAL_RUN_ID = "w3-20260726-1917"
FORBIDDEN_STATUS_TOKEN = "NOT_VERIFIED"
EXPECTED_NPM_BIN_LINKS = 14
EXPECTED_BUILD_COMMAND_SUPPORT_FILES = 14
EVIDENCE_CONTRACT_RELATIVE_PATH = (
    "scripts/qa/wave3-evidence-contract-v1.json"
)

RUN_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{5,63}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    re.IGNORECASE,
)
DENY_PATTERN = re.compile(r"pay[\s._/-]*roll|pay[\s._/-]*slips?|薪资|工资(?:条|核算)?")  # deny-regex-declaration: payroll|payslip|薪资|工资

ROLE_CODES = ("SYSTEM_ADMIN", "HR_ADMIN", "AUDITOR")
MANAGER_CAPABILITIES = (
    "ATTENDANCE_SETUP:READ",
    "ATTENDANCE_SETUP:MANAGE_GROUP",
    "ATTENDANCE_SETUP:ASSIGN",
    "ATTENDANCE_SETUP:MANAGE_SHIFT",
    "ATTENDANCE_SETUP:MANAGE_CALENDAR",
    "ATTENDANCE_SETUP:MANAGE_POLICY",
)
AUDITOR_CAPABILITIES = ("ATTENDANCE_SETUP:READ",)
EXPECTED_W3_MENU = {
    "attendance-groups": "/rules/attendance-groups",
    "attendance-shifts": "/rules/shifts",
    "attendance-calendars": "/rules/calendars",
    "attendance-policies": "/rules/attendance-policy",
}

FRONTEND_NEGATIVE_TARGETS = ("/payroll", "/me/payslips")
API_NEGATIVE_TARGETS = ("/api/v1/payroll", "/api/v1/me/payslips")
ALL_NEGATIVE_TARGETS = frozenset(
    (*FRONTEND_NEGATIVE_TARGETS, *API_NEGATIVE_TARGETS)
)

MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = "13306"
MYSQL_VERSION = "8.4.10"
MYSQL_DATABASE = "shenzhou_hr_test"
MYSQL_APPLICATION_USERNAME = "shenzhou_hr_test_app"
MYSQL_CLIENT = Path(
    "/Users/huzhijin/.local/share/shenzhouhr/"
    "mysql-8.4.10-isolated/install/bin/mysql"
)
MYSQL_ISOLATED_ROOT = Path(
    "/Users/huzhijin/.local/share/shenzhouhr/mysql-8.4.10-isolated"
)
LEGAL_ENTITY_ID = "30000000-0000-0000-0000-000000000001"
FLYWAY_MIGRATION_ROOT = (
    REPOSITORY_ROOT / "backend/src/main/resources/db/migration"
)
FROZEN_FLYWAY_MIGRATIONS = (
    (
        "V1__identity_organization_authorization_audit.sql",
        "5f5cdd3367ef7ab128974b7fcad89a44bcfc5631008bd066806f77d33f85c440",
        -517298571,
    ),
    (
        "V2__baseline_authorization_catalog.sql",
        "28934279faafa2154faccd470976aa6ab2c4a5058f7d1b08a989c271da5241b4",
        -1339458194,
    ),
    (
        "V3__local_identity_session.sql",
        "75120a31c594f2a974c031ed400d028028f83fb915df1b457902df27a971f9f9",
        -1266620108,
    ),
    (
        "V4__versioned_policy_foundation.sql",
        "22b7f4b4b5ad21beec719c09aade3fc50b41a64d958951a690328ea38d2402a8",
        712093963,
    ),
    (
        "V5__people_initial_import_and_versioning.sql",
        "6fd13a0e31a37d27fb7d9f71f8117acf7d5fb0bd38d4d117303b2b8582b6b589",
        -1637666734,
    ),
    (
        "V6__system_admin_people_read_prerequisite.sql",
        "11762c79e34bea2ab6aa790a6b32c6466658eec03fd7a97b23fe540cf985c003",
        557782197,
    ),
    (
        "V7__attendance_setup_and_base_policies.sql",
        "3d37209aa462b49b71d4a51baf504f7eefab180b59a97d18cf64501e15ff57f2",
        -2074869941,
    ),
)

DEFAULT_AGENT_BROWSER = Path("/Users/huzhijin/.local/bin/agent-browser")
DEFAULT_CHROME = Path(
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
)
HTTP_TIMEOUT_SECONDS = 15
SUBPROCESS_TIMEOUT_SECONDS = 30
MAX_HTTP_BODY_BYTES = 2 * 1024 * 1024
ALLOWLIST_EXPIRES_AT = "2027-01-31T00:00:00Z"
ALLOWLIST_OWNER = "wave3-verification-owner"

ARTIFACT_ROLE_FILENAMES = {
    "zero-discoverability-report": "zero-discoverability-report.log",
    "scan-scope-manifest": "scan-scope-manifest.json",
    "request-target-exclusions": "request-target-exclusions.json",
    "line-addressed-allowlist": "line-addressed-allowlist.json",
    "normalized-scan-output": "normalized-scan-output.json",
    "normal-role-runtime-probes": "normal-role-runtime-probes.json",
}
ARTIFACT_FILENAMES = tuple(ARTIFACT_ROLE_FILENAMES.values())
RUNTIME_ARTIFACT_PATH = (
    "leaves/payroll-zero/raw/normal-role-runtime-probes.json"
)
REQUEST_TARGET_EXCLUSION_POLICY = (
    "Only the scalar at each exact JSON Pointer is excluded; "
    "all sibling response/status/body/header/UI fields remain scanned."
)

TEXT_SOURCE_SUFFIXES = {
    ".css",
    ".html",
    ".js",
    ".json",
    ".jsx",
    ".map",
    ".mjs",
    ".svg",
    ".ts",
    ".tsx",
    ".txt",
    ".webmanifest",
    ".xml",
    ".yaml",
    ".yml",
}

DARWIN_CTL_KERN = 1
DARWIN_KERN_PROCARGS2 = 49
DARWIN_PROC_PIDPATHINFO_MAXSIZE = 4 * 1024
DARWIN_RENAME_EXCL = 0x00000004

FRONTEND_PRODUCT_MANIFEST = (
    "frontend/index.html",
    "frontend/src/app/App.tsx",
    "frontend/src/app/routeAuthorization.ts",
    "frontend/src/assets/shenzhou-logo.svg",
    "frontend/src/features/access/AccessComponents.tsx",
    "frontend/src/features/access/AccountDetailPage.tsx",
    "frontend/src/features/access/AccountsPage.tsx",
    "frontend/src/features/access/RolesPage.tsx",
    "frontend/src/features/access/accessApi.ts",
    "frontend/src/features/attendanceSetup/AttendanceGroupDialogs.tsx",
    "frontend/src/features/attendanceSetup/AttendanceGroupsPage.tsx",
    "frontend/src/features/attendanceSetup/AttendancePolicyLifecyclePanel.tsx",
    "frontend/src/features/attendanceSetup/AttendancePolicyPage.tsx",
    "frontend/src/features/attendanceSetup/AttendanceSetupNotice.tsx",
    "frontend/src/features/attendanceSetup/CalendarDialogs.tsx",
    "frontend/src/features/attendanceSetup/CalendarsPage.tsx",
    "frontend/src/features/attendanceSetup/PolicyBindingDialog.tsx",
    "frontend/src/features/attendanceSetup/PolicySimulationPanel.tsx",
    "frontend/src/features/attendanceSetup/ShiftDialogs.tsx",
    "frontend/src/features/attendanceSetup/ShiftsPage.tsx",
    "frontend/src/features/attendanceSetup/attendancePolicyLifecycleDates.ts",
    "frontend/src/features/attendanceSetup/attendanceSetupApi.ts",
    "frontend/src/features/attendanceSetup/attendanceSetupDemo.ts",
    "frontend/src/features/attendanceSetup/attendanceSetupFeedback.ts",
    "frontend/src/features/attendanceSetup/attendanceSetupTypes.ts",
    "frontend/src/features/audit/AuditDetailPage.tsx",
    "frontend/src/features/audit/AuditPage.tsx",
    "frontend/src/features/audit/auditApi.ts",
    "frontend/src/features/auth/LoginPage.tsx",
    "frontend/src/features/auth/authApi.ts",
    "frontend/src/features/employee/EmployeeDetailPage.tsx",
    "frontend/src/features/employee/EmployeesPage.tsx",
    "frontend/src/features/employee/demoEmployees.ts",
    "frontend/src/features/employee/employeeApi.ts",
    "frontend/src/features/organization/OrganizationPage.tsx",
    "frontend/src/features/organization/demoOrganization.ts",
    "frontend/src/features/organization/organizationApi.ts",
    "frontend/src/features/organization/organizationTree.ts",
    "frontend/src/features/people/PeopleCommon.tsx",
    "frontend/src/features/peopleImport/ImportDialogs.tsx",
    "frontend/src/features/peopleImport/ImportResults.tsx",
    "frontend/src/features/peopleImport/ImportWizard.tsx",
    "frontend/src/features/peopleImport/PeopleImportPage.tsx",
    "frontend/src/features/peopleImport/demoPeopleImport.ts",
    "frontend/src/features/peopleImport/peopleImportApi.ts",
    "frontend/src/features/peopleImport/peopleImportFileValidation.ts",
    "frontend/src/features/peopleImport/peopleImportTypes.ts",
    "frontend/src/features/policy/PolicyComponents.tsx",
    "frontend/src/features/policy/PolicyTemplateDetailPage.tsx",
    "frontend/src/features/policy/PolicyTemplatesPage.tsx",
    "frontend/src/features/policy/PolicyVersionPage.tsx",
    "frontend/src/features/policy/RulesHomePage.tsx",
    "frontend/src/features/policy/policyApi.ts",
    "frontend/src/features/session/demoSession.ts",
    "frontend/src/features/session/sessionApi.ts",
    "frontend/src/features/session/useSession.ts",
    "frontend/src/main.tsx",
    "frontend/src/shared/api/apiClient.ts",
    "frontend/src/shared/components/AccessibleButton.tsx",
    "frontend/src/shared/components/AppErrorBoundary.tsx",
    "frontend/src/shared/components/AppShell.tsx",
    "frontend/src/shared/components/BrandLogo.tsx",
    "frontend/src/shared/components/DataTable.tsx",
    "frontend/src/shared/components/FeedbackComponents.tsx",
    "frontend/src/shared/components/PagePrimitives.tsx",
    "frontend/src/shared/components/StatePanel.tsx",
    "frontend/src/shared/components/dataTableColumns.ts",
    "frontend/src/shared/config/runtimeMode.ts",
    "frontend/src/shared/files/peopleImportFilePolicy.ts",
    "frontend/src/shared/hooks/useAsyncResource.ts",
    "frontend/src/shared/i18n/i18n.ts",
    "frontend/src/shared/i18n/messages.ts",
    "frontend/src/shared/security/cspNonce.ts",
    "frontend/src/shared/security/peopleImportCapabilities.ts",
    "frontend/src/styles/design-tokens.css",
    "frontend/src/styles/design-tokens.json",
    "frontend/src/styles/global.css",
    "frontend/src/styles/tokens.css",
    "frontend/src/vite-env.d.ts",
)

FRONTEND_TEST_EXCLUSION_MANIFEST = (
    "frontend/src/app/App.test.tsx",
    "frontend/src/app/routeAuthorization.test.ts",
    "frontend/src/features/access/AccessComponents.test.tsx",
    "frontend/src/features/attendanceSetup/AttendancePolicyPage.test.tsx",
    "frontend/src/features/attendanceSetup/AttendancePolicyRouteSafety.test.tsx",
    "frontend/src/features/attendanceSetup/AttendanceSetupNotice.test.tsx",
    "frontend/src/features/attendanceSetup/AttendanceSetupPages.test.tsx",
    "frontend/src/features/attendanceSetup/AttendanceSetupPagination.test.tsx",
    "frontend/src/features/attendanceSetup/attendanceSetupApi.test.ts",
    "frontend/src/features/attendanceSetup/attendanceSetupFeedback.test.ts",
    "frontend/src/features/employee/demoEmployees.test.ts",
    "frontend/src/features/organization/demoOrganization.test.ts",
    "frontend/src/features/organization/organizationTree.test.ts",
    "frontend/src/features/people/PeopleCommon.test.tsx",
    "frontend/src/features/peopleImport/ImportWizard.test.tsx",
    "frontend/src/features/peopleImport/peopleImportApi.test.ts",
    "frontend/src/features/peopleImport/peopleImportFileValidation.test.ts",
    "frontend/src/features/session/useSession.test.tsx",
    "frontend/src/shared/api/apiClient.test.ts",
    "frontend/src/shared/components/AppErrorBoundary.test.tsx",
    "frontend/src/shared/config/deployNginxContract.test.ts",
    "frontend/src/shared/i18n/i18n.test.ts",
    "frontend/src/shared/security/cspNonce.test.ts",
    "frontend/src/test/setup.ts",
    "frontend/src/test/wave1SourceContract.test.ts",
    "frontend/src/test/wave2SourceContract.test.ts",
    "frontend/src/test/wave3SourceContract.test.ts",
)

BACKEND_REST_DTO_ROOTS = (
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest",
    "backend/src/main/java/com/szsemicon/hr/audit/interfaces/rest",
    "backend/src/main/java/com/szsemicon/hr/authorization/interfaces/rest",
    "backend/src/main/java/com/szsemicon/hr/employee/interfaces/rest",
    "backend/src/main/java/com/szsemicon/hr/identityaccess/interfaces/rest",
    "backend/src/main/java/com/szsemicon/hr/organization/interfaces/rest",
    "backend/src/main/java/com/szsemicon/hr/people/interfaces/rest",
    "backend/src/main/java/com/szsemicon/hr/policy/interfaces/rest",
    "backend/src/main/java/com/szsemicon/hr/shared/web",
)

BUILD_RECEIPTS = {
    "prod": {
        "slug": "prod-build",
        "evidenceId": "W3-VER-PROD-BUILD",
        "marker": "W3_PROD_BUILD=PASS",
        "mode": "production",
        "arguments": ["run", "build"],
        "primary": "prod-build-log.log",
        "inventory": "prod-dist-inventory.json",
    },
    "demo": {
        "slug": "demo-build",
        "evidenceId": "W3-VER-DEMO-BUILD",
        "marker": "W3_DEMO_BUILD=PASS",
        "mode": "demo",
        "arguments": ["run", "build:demo"],
        "primary": "demo-build-log.log",
        "inventory": "demo-dist-inventory.json",
    },
}
BUILD_GATE_BUNDLE = (
    (
        "backend-full",
        "W3-VER-BACKEND-FULL",
        "W3_BACKEND_FULL=PASS",
        ("backend-full-log.log", "backend-test-summary.json"),
    ),
    (
        "w1-regression",
        "W3-VER-W1-REGRESSION",
        "W3_W1_REGRESSION=PASS",
        ("w1-regression-log.log",),
    ),
    (
        "w2-regression",
        "W3-VER-W2-REGRESSION",
        "W3_W2_REGRESSION=PASS",
        ("w2-regression-log.log",),
    ),
    (
        "w3-regression",
        "W3-VER-W3-REGRESSION",
        "W3_W3_REGRESSION=PASS",
        ("w3-regression-log.log",),
    ),
    (
        "frontend-typecheck",
        "W3-VER-FRONTEND-TYPECHECK",
        "W3_FRONTEND_TYPECHECK=PASS",
        ("frontend-typecheck-log.log",),
    ),
    (
        "frontend-lint",
        "W3-VER-FRONTEND-LINT",
        "W3_FRONTEND_LINT=PASS",
        ("frontend-lint-log.log",),
    ),
    (
        "frontend-tests",
        "W3-VER-FRONTEND-TESTS",
        "W3_FRONTEND_TESTS=PASS",
        ("frontend-tests-log.log", "frontend-test-summary.json"),
    ),
    (
        "prod-build",
        "W3-VER-PROD-BUILD",
        "W3_PROD_BUILD=PASS",
        ("prod-build-log.log", "prod-dist-inventory.json"),
    ),
    (
        "demo-build",
        "W3-VER-DEMO-BUILD",
        "W3_DEMO_BUILD=PASS",
        ("demo-build-log.log", "demo-dist-inventory.json"),
    ),
)
BUILD_GATE_COMMIT_FILENAME = "wave3-build-gates.commit.json"
MAX_BUILD_ARCHIVE_BYTES = 64 * 1024 * 1024
DYNAMIC_RESPONSE_KEYS = {
    "correlationid",
    "requestid",
    "timestamp",
    "traceid",
}


class ZeroDiscoverabilityError(RuntimeError):
    """Expected fail-closed producer error."""


def fail(message: str) -> None:
    raise ZeroDiscoverabilityError(message)


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Turn every first-hop redirect into an observable HTTPError."""

    def redirect_request(
        self,
        request: urllib.request.Request,
        file_pointer: Any,
        code: int,
        message: str,
        headers: Message,
        new_url: str,
    ) -> None:
        return None


HTTP_OPENER = urllib.request.build_opener(NoRedirectHandler())


def utc_now() -> str:
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )


def parse_utc(value: str, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        fail(f"{label} must be an RFC3339 UTC instant ending in Z")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        fail(f"{label} is not a valid RFC3339 UTC instant")
    if parsed.tzinfo != timezone.utc:
        fail(f"{label} must use UTC")
    return parsed


def utc_iso_from_ns(epoch_ns: int, label: str) -> str:
    if type(epoch_ns) is not int or epoch_ns <= 0:
        fail(f"{label} epoch nanoseconds are invalid")
    try:
        value = datetime.fromtimestamp(
            epoch_ns / 1_000_000_000, tz=timezone.utc
        )
    except (OverflowError, OSError, ValueError):
        fail(f"{label} epoch nanoseconds are out of range")
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_text(value: str) -> str:
    return sha256_bytes(value.encode("utf-8"))


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def compact_canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def pretty_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")


def assert_no_null_or_forbidden(value: Any, label: str) -> None:
    if value is None:
        fail(f"{label} contains a forbidden JSON null")
    if isinstance(value, str):
        if FORBIDDEN_STATUS_TOKEN in value:
            fail(f"{label} contains forbidden status token")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            assert_no_null_or_forbidden(item, f"{label}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                fail(f"{label} contains a non-string object key")
            assert_no_null_or_forbidden(item, f"{label}.{key}")
        return
    if isinstance(value, (bool, int, float)):
        return
    fail(f"{label} contains unsupported value {type(value).__name__}")


def write_exclusive(path: Path, contents: bytes) -> None:
    try:
        with path.open("xb") as output:
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())
    except FileExistsError:
        fail(f"refusing to overwrite evidence artifact: {path.name}")


def strict_json_loads(value: str, label: str) -> Any:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, item in pairs:
            if key in result:
                fail(f"{label} contains duplicate JSON key {key!r}")
            result[key] = item
        return result

    try:
        return json.loads(value, object_pairs_hook=reject_duplicates)
    except json.JSONDecodeError:
        fail(f"{label} is not valid JSON")


def load_json(path: Path, label: str) -> Any:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        fail(f"{label} is not valid UTF-8 JSON")
    return strict_json_loads(text, label)


def require_exact_keys(
    value: Any, expected: Iterable[str], label: str
) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(f"{label} must be an object")
    actual = set(value)
    wanted = set(expected)
    if actual != wanted:
        fail(
            f"{label} key closure failed; "
            f"missing={sorted(wanted - actual)}, extra={sorted(actual - wanted)}"
        )
    return value


def is_within(candidate: Path, parent: Path) -> bool:
    try:
        candidate.relative_to(parent)
        return True
    except ValueError:
        return False


def assert_regular_file_no_symlink(path: Path, label: str) -> Path:
    try:
        metadata = path.lstat()
    except OSError:
        fail(f"{label} is unavailable")
    if not stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
        fail(f"{label} must be a regular non-symbolic file")
    return path.resolve(strict=True)


def assert_regular_executable(path: Path, label: str) -> Path:
    resolved = assert_regular_file_no_symlink(path, label)
    if not os.access(resolved, os.X_OK):
        fail(f"{label} must be executable")
    return resolved


def assert_no_symlink_path(root: Path, target: Path) -> None:
    resolved_root = root.resolve(strict=True)
    try:
        relative = target.absolute().relative_to(resolved_root)
    except ValueError:
        fail("path escapes its allowed root")
    current = resolved_root
    for part in relative.parts:
        current = current / part
        if current.exists() or current.is_symlink():
            if current.is_symlink():
                fail(f"path traverses a symbolic link: {current}")


def normalized_casefold(value: str) -> str:
    return unicodedata.normalize("NFKC", value).casefold()


def deny_match_count(value: str) -> int:
    return sum(1 for _ in DENY_PATTERN.finditer(normalized_casefold(value)))


def require_no_deny_match(value: str, label: str) -> None:
    if deny_match_count(value):
        fail(f"deny scan found a discoverable token at {label}")


def normalization_self_test() -> dict[str, Any]:
    positive = (
        "PAYROLL",
        "pay_roll",
        "ＰａＹＲＯＬＬ",
        "Pay-Slips",
        "薪资",
        "工资条",
    )
    negative = (
        "attendance",
        "salary-free-neutral-word",
        "考勤设置",
    )
    if not all(deny_match_count(value) >= 1 for value in positive):
        fail("Unicode deny matcher failed a positive self-test")
    if any(deny_match_count(value) for value in negative):
        fail("Unicode deny matcher failed a negative self-test")
    return {
        "normalization": "Unicode NFKC then full casefold",
        "positiveVectorCount": len(positive),
        "negativeVectorCount": len(negative),
        "vectorSetSha256": sha256_text(
            "\n".join((*positive, "--", *negative)) + "\n"
        ),
        "verdict": "PASS",
    }


def safe_subprocess_environment(extra: Mapping[str, str] | None = None) -> dict[str, str]:
    allowed = (
        "PATH",
        "HOME",
        "TMPDIR",
        "LANG",
        "LC_ALL",
        "TZ",
        "SYSTEMROOT",
        "WINDIR",
    )
    result = {key: os.environ[key] for key in allowed if key in os.environ}
    result.update({"LC_ALL": "C", "LANG": "C", "TZ": "UTC"})
    if extra:
        result.update(extra)
    return result


def require_repository_root() -> Path:
    try:
        cwd = Path.cwd().resolve(strict=True)
    except OSError:
        fail("PROJECT_ROOT_SCOPE_ERROR")
    if cwd != EXPECTED_REPOSITORY_ROOT or REPOSITORY_ROOT != EXPECTED_REPOSITORY_ROOT:
        fail("PROJECT_ROOT_SCOPE_ERROR")
    return cwd


def validate_evidence_contract(contract: Mapping[str, Any]) -> None:
    if (
        type(contract.get("schemaVersion")) is not int
        or contract.get("schemaVersion") != 1
    ):
        fail("evidence contract schema version is invalid")
    if contract.get("contractId") != "wave3-evidence-contract-v1":
        fail("evidence contract ID is invalid")
    invalidated = contract.get("invalidatedRunIds")
    if (
        not isinstance(invalidated, list)
        or HISTORICAL_RUN_ID not in invalidated
        or any(not isinstance(value, str) for value in invalidated)
    ):
        fail("evidence contract invalidated-run closure is invalid")
    pre_review = contract.get("preReviewLeafIds")
    if (
        not isinstance(pre_review, list)
        or pre_review.count(EVIDENCE_ID) != 1
    ):
        fail("evidence contract pre-review closure is invalid")
    leaf_contracts = contract.get("leafContracts")
    if not isinstance(leaf_contracts, dict):
        fail("evidence contract leaf closure is invalid")
    leaf = leaf_contracts.get(EVIDENCE_ID)
    expected_leaf = {
        "slug": "payroll-zero",
        "requiredMarkers": [PASS_MARKER],
        "primaryArtifactRole": "zero-discoverability-report",
        "requiredArtifactRoles": list(ARTIFACT_ROLE_FILENAMES),
        "databaseBound": True,
    }
    if leaf != expected_leaf:
        fail("payroll-zero evidence contract leaf is not exact")


def load_run_context(path: Path) -> dict[str, Any]:
    if not path.is_absolute():
        fail("--run-context must be absolute")
    resolved = assert_regular_file_no_symlink(path, "run context")
    runs_root = (REPOSITORY_ROOT / "docs/verification/wave3/runs").resolve(
        strict=True
    )
    if not is_within(resolved, runs_root):
        fail("run context must be inside the W3 run root")
    if resolved.name != "run-context.json":
        fail("run context filename must be run-context.json")
    context = require_exact_keys(
        load_json(resolved, "run context"),
        {
            "schemaVersion",
            "nodeType",
            "runId",
            "sourceTreeHash",
            "databaseIdentity",
            "implementerId",
            "implementerProcessId",
            "startedAt",
            "startedAtEpochNs",
            "sourceMaximumMtimeNs",
            "contractPath",
            "contractSha256",
        },
        "run context",
    )
    assert_no_null_or_forbidden(context, "run context")
    if (
        type(context["schemaVersion"]) is not int
        or context["schemaVersion"] != 1
        or context["nodeType"] != "run-context"
    ):
        fail("run context schema/type mismatch")
    if not RUN_ID_PATTERN.fullmatch(context["runId"]):
        fail("run context runId is invalid")
    if context["runId"] == HISTORICAL_RUN_ID:
        fail("historical invalidated runId is forbidden")
    if resolved.parent.name != context["runId"]:
        fail("run context directory/runId mismatch")
    if not SHA256_PATTERN.fullmatch(context["sourceTreeHash"]):
        fail("run context sourceTreeHash is invalid")
    if (
        not isinstance(context["databaseIdentity"], str)
        or len(context["databaseIdentity"]) < 4
        or len(context["databaseIdentity"]) > 240
    ):
        fail("run context databaseIdentity is invalid")
    started = parse_utc(context["startedAt"], "run context startedAt")
    if (
        type(context["startedAtEpochNs"]) is not int
        or context["startedAtEpochNs"] <= 0
        or started.timestamp() > time.time() + 1
    ):
        fail("run context START is invalid")
    if context["startedAtEpochNs"] > time.time_ns():
        fail("run context START is in the future")
    if (
        type(context["sourceMaximumMtimeNs"]) is not int
        or context["sourceMaximumMtimeNs"] <= 0
    ):
        fail("run context source maximum mtime is invalid")
    expected_started_at = (
        datetime.fromtimestamp(
            context["startedAtEpochNs"] / 1_000_000_000,
            tz=timezone.utc,
        )
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )
    if context["startedAt"] != expected_started_at:
        fail("run context START text/epoch binding is invalid")
    if context["contractPath"] != EVIDENCE_CONTRACT_RELATIVE_PATH:
        fail("run context evidence contract path is not the official contract")
    contract_path = (REPOSITORY_ROOT / context["contractPath"]).resolve(
        strict=True
    )
    expected_contract_path = (
        REPOSITORY_ROOT / EVIDENCE_CONTRACT_RELATIVE_PATH
    ).resolve(strict=True)
    if contract_path != expected_contract_path:
        fail("run context evidence contract realpath is not official")
    if not is_within(contract_path, REPOSITORY_ROOT):
        fail("run context evidence contract escapes the repository")
    assert_regular_file_no_symlink(contract_path, "evidence contract")
    if sha256_file(contract_path) != context["contractSha256"]:
        fail("run context evidence contract hash is stale")
    contract = load_json(contract_path, "evidence contract")
    contract_object = require_object(contract, "evidence contract")
    validate_evidence_contract(contract_object)
    invalidated = contract_object["invalidatedRunIds"]
    if context["runId"] in invalidated:
        fail("run context is invalidated")
    source_start_path = resolved.parent / "source/start.json"
    assert_regular_file_no_symlink(source_start_path, "source START record")
    source_start = require_object(
        load_json(source_start_path, "source START record"),
        "source START record",
    )
    assert_no_null_or_forbidden(source_start, "source START record")
    expected_start_values = {
        "schemaVersion": 1,
        "nodeType": "source-start",
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "maximumSourceMtimeNs": context["sourceMaximumMtimeNs"],
    }
    for key, expected_value in expected_start_values.items():
        if source_start.get(key) != expected_value:
            fail(f"source START record {key} mismatch")
    if type(source_start.get("schemaVersion")) is not int:
        fail("source START record schemaVersion type mismatch")
    if type(source_start.get("maximumSourceMtimeNs")) is not int:
        fail("source START maximumSourceMtimeNs type mismatch")
    if (resolved.parent / "source/end.json").exists():
        fail("source END already exists; pre-review production is closed")
    context = dict(context)
    context["path"] = resolved
    context["runRoot"] = resolved.parent
    return context


def current_source_tree_hash() -> str:
    try:
        from verify_wave3_evidence import Wave3Evidence
    except ImportError:
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from verify_wave3_evidence import Wave3Evidence

    digest = Wave3Evidence(REPOSITORY_ROOT).compute_source_snapshot().tree_hash
    if not SHA256_PATTERN.fullmatch(digest):
        fail("source-tree verifier returned an invalid digest")
    return digest


def assert_frozen_source(context: Mapping[str, Any]) -> None:
    if current_source_tree_hash() != context["sourceTreeHash"]:
        fail("current normalized source tree differs from the run context")


def context_line(context: Mapping[str, Any]) -> str:
    return (
        f"{CONTEXT_PREFIX} runId={context['runId']} "
        f"sourceHash={context['sourceTreeHash']} "
        f"dbIdentity={context['databaseIdentity']} evidenceId={EVIDENCE_ID}"
    )


def portable_mode(path: Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


def validate_private_file(path: Path, label: str) -> Path:
    if not path.is_absolute():
        fail(f"{label} path must be absolute")
    resolved = assert_regular_file_no_symlink(path, label)
    if resolved == REPOSITORY_ROOT or is_within(resolved, REPOSITORY_ROOT):
        fail(f"{label} must be outside the repository")
    metadata = resolved.stat()
    if portable_mode(resolved) != 0o600:
        fail(f"{label} mode must be exactly 0600")
    if hasattr(os, "getuid") and metadata.st_uid != os.getuid():
        fail(f"{label} must be owned by the current user")
    if metadata.st_size > 64 * 1024:
        fail(f"{label} must be at most 64 KiB")
    return resolved


def parse_private_environment(
    path: Path,
    *,
    allowed_keys: set[str],
    required_keys: set[str],
    label: str,
) -> dict[str, str]:
    resolved = validate_private_file(path, label)
    try:
        lines = resolved.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        fail(f"{label} is not valid UTF-8 text")
    values: dict[str, str] = {}
    for index, line in enumerate(lines, start=1):
        if not line or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if (
            separator != "="
            or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key)
            or key not in allowed_keys
        ):
            fail(f"{label} contains an unsupported entry at line {index}")
        if key in values:
            fail(f"{label} contains a duplicate entry at line {index}")
        if not value or "\x00" in value:
            fail(f"{label} contains an invalid value at line {index}")
        values[key] = value
    missing = required_keys - set(values)
    if missing:
        fail(f"{label} is missing required keys: {sorted(missing)}")
    return values


def read_runtime_environment(
    path: Path, expected_database_identity: str
) -> dict[str, str]:
    allowed = {
        "SHENZHOUHR_MYSQL_HOST",
        "SHENZHOUHR_MYSQL_PORT",
        "SHENZHOUHR_MYSQL_CLIENT_BIN",
        "SHENZHOUHR_FLYWAY_BIN",
        "SHENZHOUHR_TEST_FINAL_STATE",
        "SHENZHOUHR_W3_DB_IDENTITY",
        "SHENZHOUHR_MYSQL_ROOT_PASSWORD",
        "SHENZHOUHR_FLYWAY_PASSWORD",
        "SHENZHOUHR_DEV_DB_PASSWORD",
        "SHENZHOUHR_TEST_DB_PASSWORD",
    }
    required = {
        "SHENZHOUHR_MYSQL_HOST",
        "SHENZHOUHR_MYSQL_PORT",
        "SHENZHOUHR_MYSQL_CLIENT_BIN",
        "SHENZHOUHR_W3_DB_IDENTITY",
        "SHENZHOUHR_FLYWAY_PASSWORD",
        "SHENZHOUHR_TEST_DB_PASSWORD",
    }
    values = parse_private_environment(
        path,
        allowed_keys=allowed,
        required_keys=required,
        label="runtime environment",
    )
    expected = {
        "SHENZHOUHR_MYSQL_HOST": MYSQL_HOST,
        "SHENZHOUHR_MYSQL_PORT": MYSQL_PORT,
        "SHENZHOUHR_MYSQL_CLIENT_BIN": str(MYSQL_CLIENT),
        "SHENZHOUHR_W3_DB_IDENTITY": expected_database_identity,
    }
    for key, value in expected.items():
        if values.get(key) != value:
            fail(f"runtime environment {key} differs from the bound run")
    resolved_client = assert_regular_executable(
        Path(values["SHENZHOUHR_MYSQL_CLIENT_BIN"]),
        "isolated MySQL client",
    )
    if resolved_client != MYSQL_CLIENT:
        fail("runtime environment MySQL client is indirect")
    return values


def read_login_environment(path: Path) -> dict[str, str]:
    keys = {"SHENZHOUHR_LOGIN_USERNAME", "SHENZHOUHR_LOGIN_PASSWORD"}
    return parse_private_environment(
        path,
        allowed_keys=keys,
        required_keys=keys,
        label="login environment",
    )


def validate_loopback_origin(value: str, label: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(value)
    except ValueError:
        fail(f"{label} is invalid")
    if (
        parsed.scheme != "http"
        or parsed.hostname not in {"127.0.0.1", "localhost"}
        or parsed.username
        or parsed.password
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
        or parsed.port is None
    ):
        fail(f"{label} must be a credential-free loopback HTTP origin")
    return f"http://{parsed.hostname}:{parsed.port}"


def run_readonly_process_command(
    command: Sequence[str], label: str
) -> str:
    try:
        result = subprocess.run(
            tuple(command),
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
            timeout=SUBPROCESS_TIMEOUT_SECONDS,
            env=safe_subprocess_environment(),
        )
    except (OSError, subprocess.TimeoutExpired):
        fail(f"{label} could not execute")
    if result.returncode != 0:
        fail(f"{label} failed")
    return result.stdout


def parse_ps_lstart_utc(value: str, label: str) -> datetime:
    try:
        parsed = datetime.strptime(
            " ".join(value.split()), "%a %b %d %H:%M:%S %Y"
        )
    except ValueError:
        fail(f"{label} is malformed")
    return parsed.replace(tzinfo=timezone.utc)


def parse_darwin_procargs2(
    contents: bytes,
) -> tuple[str, tuple[str, ...], tuple[bytes, ...]]:
    if len(contents) < struct.calcsize("=i") + 2:
        fail("Darwin process argument buffer is truncated")
    argument_count = struct.unpack_from("=i", contents)[0]
    if argument_count < 1 or argument_count > 4096:
        fail("Darwin process argument count is invalid")
    position = struct.calcsize("=i")

    def next_field(label: str) -> bytes:
        nonlocal position
        try:
            ending = contents.index(b"\0", position)
        except ValueError:
            fail(f"Darwin process {label} is unterminated")
        field = contents[position:ending]
        position = ending + 1
        return field

    executable_bytes = next_field("executable")
    if not executable_bytes:
        fail("Darwin process executable is empty")
    while position < len(contents) and contents[position] == 0:
        position += 1
    argument_bytes = [
        next_field(f"argument[{index}]") for index in range(argument_count)
    ]
    if any(not value for value in argument_bytes):
        fail("Darwin process contains an empty argument")
    while position < len(contents) and contents[position] == 0:
        position += 1
    environment_entries: list[bytes] = []
    while position < len(contents):
        field = next_field("environment entry")
        if not field:
            break
        environment_entries.append(field)
    try:
        executable = executable_bytes.decode("utf-8", errors="strict")
        arguments = tuple(
            value.decode("utf-8", errors="strict")
            for value in argument_bytes
        )
    except UnicodeError:
        fail("Darwin process executable/argv is not strict UTF-8")
    return executable, arguments, tuple(environment_entries)


def darwin_process_snapshot(pid: int) -> dict[str, Any]:
    if sys.platform != "darwin":
        fail("exact runtime process binding requires Darwin")
    proc_library_path = ctypes.util.find_library("proc")
    c_library_path = ctypes.util.find_library("c")
    if not proc_library_path or not c_library_path:
        fail("Darwin process inspection libraries are unavailable")
    proc_library = ctypes.CDLL(proc_library_path, use_errno=True)
    proc_pidpath = proc_library.proc_pidpath
    proc_pidpath.argtypes = [
        ctypes.c_int,
        ctypes.c_void_p,
        ctypes.c_uint32,
    ]
    proc_pidpath.restype = ctypes.c_int
    path_buffer = ctypes.create_string_buffer(
        DARWIN_PROC_PIDPATHINFO_MAXSIZE
    )
    ctypes.set_errno(0)
    path_length = proc_pidpath(pid, path_buffer, len(path_buffer))
    if path_length <= 0:
        fail("Darwin process executable path is unavailable")
    try:
        executable_path_text = path_buffer.raw[:path_length].decode(
            "utf-8", errors="strict"
        )
    except UnicodeError:
        fail("Darwin process executable path is not strict UTF-8")
    executable_path = Path(executable_path_text)
    try:
        executable_realpath = executable_path.resolve(strict=True)
        executable_metadata = executable_realpath.stat()
    except OSError:
        fail("Darwin process executable disappeared")
    if (
        not stat.S_ISREG(executable_metadata.st_mode)
        or not os.access(executable_realpath, os.X_OK)
    ):
        fail("Darwin process executable is not a regular executable")

    c_library = ctypes.CDLL(c_library_path, use_errno=True)
    sysctl = c_library.sysctl
    sysctl.argtypes = [
        ctypes.POINTER(ctypes.c_int),
        ctypes.c_uint,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t),
        ctypes.c_void_p,
        ctypes.c_size_t,
    ]
    sysctl.restype = ctypes.c_int
    mib = (ctypes.c_int * 3)(
        DARWIN_CTL_KERN,
        DARWIN_KERN_PROCARGS2,
        pid,
    )
    buffer_size = ctypes.c_size_t(0)
    ctypes.set_errno(0)
    if sysctl(mib, 3, None, ctypes.byref(buffer_size), None, 0) != 0:
        fail("Darwin process argv size query failed")
    if buffer_size.value < 8 or buffer_size.value > 4 * 1024 * 1024:
        fail("Darwin process argv size is invalid")
    argument_buffer = ctypes.create_string_buffer(buffer_size.value)
    ctypes.set_errno(0)
    if (
        sysctl(
            mib,
            3,
            argument_buffer,
            ctypes.byref(buffer_size),
            None,
            0,
        )
        != 0
    ):
        fail("Darwin process argv query failed")
    raw_executable, arguments, environment_entries = (
        parse_darwin_procargs2(
            argument_buffer.raw[: buffer_size.value]
        )
    )
    try:
        raw_executable_realpath = Path(raw_executable).resolve(strict=True)
    except OSError:
        fail("Darwin process argv executable disappeared")
    if raw_executable_realpath != executable_realpath:
        fail("Darwin process executable sources disagree")
    executable_bytes, executable_stable_metadata, _ = stable_file_bytes(
        executable_realpath, "runtime process executable"
    )
    return {
        "executableRealpath": executable_realpath.as_posix(),
        "executableDevice": executable_stable_metadata.st_dev,
        "executableInode": executable_stable_metadata.st_ino,
        "executableSizeBytes": len(executable_bytes),
        "executableMtimeEpochNs": executable_stable_metadata.st_mtime_ns,
        "executableCtimeEpochNs": executable_stable_metadata.st_ctime_ns,
        "executableSha256": sha256_bytes(executable_bytes),
        "argv": arguments,
        "_privateEnvironmentEntries": environment_entries,
    }


def validate_argv_zero(
    argv_zero: str, executable_realpath: Path, expected_name: str
) -> None:
    if "/" in argv_zero:
        try:
            if Path(argv_zero).resolve(strict=True) != executable_realpath:
                fail("runtime argv[0] differs from the executable")
        except OSError:
            fail("runtime argv[0] is unavailable")
    elif argv_zero != expected_name:
        fail("runtime argv[0] is not exact")


def validate_backend_jdbc_environment(
    environment_entries: Sequence[bytes],
    runtime_environment: Mapping[str, str],
) -> dict[str, Any]:
    required = {
        "SHENZHOUHR_DB_URL",
        "SHENZHOUHR_DB_USERNAME",
        "SHENZHOUHR_DB_PASSWORD",
    }
    forbidden_override_keys = {
        "SPRING_APPLICATION_JSON",
        "SPRING_DATASOURCE_URL",
        "SPRING_DATASOURCE_USERNAME",
        "SPRING_DATASOURCE_PASSWORD",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
    }
    values: dict[str, bytes] = {}
    for entry in environment_entries:
        key_bytes, separator, value = entry.partition(b"=")
        if separator != b"=":
            continue
        try:
            key = key_bytes.decode("ascii", errors="strict")
        except UnicodeError:
            continue
        if key in forbidden_override_keys:
            fail("backend process environment contains an override channel")
        if key in required:
            if key in values:
                fail("backend process environment duplicates a JDBC key")
            values[key] = value
    if set(values) != required:
        fail("backend process environment lacks the exact JDBC key set")
    try:
        url = values["SHENZHOUHR_DB_URL"].decode("utf-8", errors="strict")
        username = values["SHENZHOUHR_DB_USERNAME"].decode(
            "utf-8", errors="strict"
        )
        password = values["SHENZHOUHR_DB_PASSWORD"].decode(
            "utf-8", errors="strict"
        )
    except UnicodeError:
        fail("backend JDBC process environment is not strict UTF-8")
    if not url.startswith("jdbc:mysql://"):
        fail("backend JDBC URL is not the fixed MySQL driver URL")
    try:
        parsed = urllib.parse.urlsplit(url.removeprefix("jdbc:"))
    except ValueError:
        fail("backend JDBC URL is malformed")
    if (
        parsed.scheme != "mysql"
        or parsed.hostname != MYSQL_HOST
        or parsed.port != int(MYSQL_PORT)
        or parsed.path != f"/{MYSQL_DATABASE}"
        or parsed.username
        or parsed.password
        or parsed.fragment
    ):
        fail("backend JDBC URL differs from the bound MySQL database")
    if username != MYSQL_APPLICATION_USERNAME:
        fail("backend JDBC username differs from the least-privilege account")
    if not hmac.compare_digest(
        password,
        runtime_environment["SHENZHOUHR_TEST_DB_PASSWORD"],
    ):
        fail("backend JDBC password differs from the private runtime binding")
    return {
        "driver": "jdbc:mysql",
        "host": MYSQL_HOST,
        "port": int(MYSQL_PORT),
        "database": MYSQL_DATABASE,
        "usernameSha256": sha256_text(username),
        "passwordBoundInMemory": True,
        "overrideChannelCount": 0,
        "verdict": "PASS",
    }


def validate_backend_argv_contract(
    argv: Sequence[str],
    executable: Path,
    expected_cwd: Path,
) -> list[Path]:
    values = tuple(argv)
    if not values:
        fail("backend Java argv is empty")
    validate_argv_zero(values[0], executable, "java")
    main_class = "com.szsemicon.hr.ShenzhouHrApplication"
    if (
        len(values) != 4
        or values[1] not in {"-cp", "-classpath", "--class-path"}
        or values[3] != main_class
    ):
        fail("backend Java argv is not the exact classpath/main-class form")
    classpath_values = values[2].split(os.pathsep)
    if not classpath_values or any(not value for value in classpath_values):
        fail("backend Java classpath is empty or contains an empty entry")
    resolved_entries: list[Path] = []
    for raw_value in classpath_values:
        value = Path(raw_value)
        if not value.is_absolute():
            value = expected_cwd / value
        try:
            resolved_entries.append(value.resolve(strict=True))
        except OSError:
            fail("backend Java classpath contains an unavailable entry")
    expected_classes = (expected_cwd / "target/classes").resolve(strict=True)
    if resolved_entries[0] != expected_classes:
        fail("backend target/classes is not first in classpath precedence")
    if len(set(resolved_entries)) != len(resolved_entries):
        fail("backend Java classpath contains duplicate entries")
    return resolved_entries


def validate_frontend_argv_contract(
    argv: Sequence[str],
    executable: Path,
    vite_link: Path,
) -> None:
    values = tuple(argv)
    if not values:
        fail("frontend Vite argv is empty")
    validate_argv_zero(values[0], executable, "node")
    expected_argv = (
        "node",
        vite_link.as_posix(),
        "--configLoader",
        "runner",
        "--host",
        "127.0.0.1",
        "--port",
        "5173",
        "--strictPort",
    )
    if values != expected_argv:
        fail("frontend Vite argv is not the exact normal-mode command")


def validate_demo_frontend_argv_contract(
    argv: Sequence[str],
    executable: Path,
    vite_entry: Path,
) -> None:
    values = tuple(argv)
    if not values:
        fail("demo Vite preview argv is empty")
    validate_argv_zero(values[0], executable, "node")
    expected_argv = (
        "node",
        vite_entry.as_posix(),
        "preview",
        "--configLoader",
        "runner",
        "--host",
        "127.0.0.1",
        "--port",
        "4175",
        "--strictPort",
        "--outDir",
        "dist/demo",
    )
    if values != expected_argv:
        fail("demo Vite argv is not the exact built-preview command")


def validate_frontend_process_environment(
    environment_entries: Sequence[bytes],
) -> dict[str, Any]:
    proxy_values: list[str] = []
    for entry in environment_entries:
        key_bytes, separator, value_bytes = entry.partition(b"=")
        if separator != b"=":
            continue
        try:
            key = key_bytes.decode("ascii", errors="strict")
        except UnicodeError:
            continue
        if key in {"NODE_OPTIONS", "BABEL_ENV"}:
            fail("frontend process environment contains a Node override")
        if key.startswith("VITE_"):
            if key != "VITE_API_PROXY_TARGET":
                fail("frontend process environment contains an unknown VITE key")
            try:
                proxy_values.append(
                    value_bytes.decode("utf-8", errors="strict")
                )
            except UnicodeError:
                fail("frontend Vite proxy target is not strict UTF-8")
    if proxy_values != ["http://127.0.0.1:8080"]:
        fail("frontend Vite proxy target is not exactly the bound backend")
    return {
        "apiProxyTarget": "http://127.0.0.1:8080",
        "viteEnvironmentKeyCount": 1,
        "nodeOverrideCount": 0,
        "verdict": "PASS",
    }


def validate_demo_frontend_process_environment(
    environment_entries: Sequence[bytes],
) -> dict[str, Any]:
    vite_key_count = 0
    node_override_count = 0
    for entry in environment_entries:
        key_bytes, separator, _value_bytes = entry.partition(b"=")
        if separator != b"=":
            continue
        try:
            key = key_bytes.decode("ascii", errors="strict")
        except UnicodeError:
            continue
        if key in {"NODE_OPTIONS", "BABEL_ENV"}:
            node_override_count += 1
        if key.startswith("VITE_"):
            vite_key_count += 1
    if node_override_count:
        fail("demo frontend process environment contains a Node override")
    if vite_key_count:
        fail("demo frontend process environment contains a VITE override")
    return {
        "viteEnvironmentKeyCount": 0,
        "nodeOverrideCount": 0,
        "verdict": "PASS",
    }


def validate_backend_process_snapshot(
    process: Mapping[str, Any],
    expected_cwd: Path,
    process_started_ns: int,
    runtime_environment: Mapping[str, str],
) -> dict[str, Any]:
    if (
        process["executableMtimeEpochNs"] > process_started_ns
        or process["executableCtimeEpochNs"] > process_started_ns
    ):
        fail("backend Java executable changed after process start")
    java_path = shutil.which("java")
    if not java_path:
        fail("expected Java executable is unavailable")
    expected_java = Path(java_path).resolve(strict=True)
    executable = Path(str(process["executableRealpath"]))
    if executable != expected_java:
        fail("backend listener uses an unexpected Java executable")
    argv = tuple(process["argv"])
    main_class = "com.szsemicon.hr.ShenzhouHrApplication"
    resolved_entries = validate_backend_argv_contract(
        argv, executable, expected_cwd
    )
    expected_classes = (expected_cwd / "target/classes").resolve(strict=True)
    maven_root = (Path.home() / ".m2/repository").resolve(strict=True)
    dependency_records: list[dict[str, Any]] = []
    for index, entry in enumerate(resolved_entries[1:], start=1):
        if (
            entry.suffix != ".jar"
            or not is_within(entry, maven_root)
            or entry.is_symlink()
        ):
            fail("backend Java dependency classpath is not fixed Maven JARs")
        contents, metadata, realpath = stable_file_bytes(
            entry, f"backend classpath dependency[{index}]"
        )
        if (
            metadata.st_mtime_ns > process_started_ns
            or metadata.st_ctime_ns > process_started_ns
        ):
            fail("backend classpath dependency changed after process start")
        try:
            with zipfile.ZipFile(io.BytesIO(contents)) as archive:
                if any(
                    name.endswith(".class")
                    and (
                        name.startswith("com/szsemicon/hr/")
                        or "/com/szsemicon/hr/" in name
                    )
                    for name in archive.namelist()
                ):
                    fail(
                        "backend dependency JAR shadows the application package"
                    )
        except zipfile.BadZipFile:
            fail("backend classpath dependency is not a valid JAR")
        dependency_records.append(
            {
                "ordinal": index,
                "realpath": realpath.as_posix(),
                "device": metadata.st_dev,
                "inode": metadata.st_ino,
                "mode": stat.S_IMODE(metadata.st_mode),
                "sizeBytes": len(contents),
                "mtimeEpochNs": metadata.st_mtime_ns,
                "ctimeEpochNs": metadata.st_ctime_ns,
                "sha256": sha256_bytes(contents),
            }
        )
    environment_entries = process["_privateEnvironmentEntries"]
    jdbc = validate_backend_jdbc_environment(
        environment_entries, runtime_environment
    )
    public_process = {
        key: value
        for key, value in process.items()
        if key not in {"argv", "_privateEnvironmentEntries"}
    }
    public_process.update(
        {
            "argvCount": len(argv),
            "argvSha256": sha256_bytes(canonical_json_bytes(list(argv))),
            "mainClass": main_class,
            "classpathEntryCount": len(resolved_entries),
            "classpathFirstRealpath": expected_classes.as_posix(),
            "dependencyClasspathSha256": sha256_bytes(
                b"".join(
                    canonical_json_bytes(record)
                    for record in dependency_records
                )
            ),
            "jdbc": jdbc,
            "_privateArgv": argv,
            "_privateDependencyRecords": dependency_records,
        }
    )
    return public_process


def validate_frontend_process_snapshot(
    process: Mapping[str, Any],
    expected_cwd: Path,
    process_started_ns: int,
    *,
    mode: str = "normal",
) -> dict[str, Any]:
    if mode not in {"normal", "demo"}:
        fail("frontend process snapshot mode is invalid")
    if (
        process["executableMtimeEpochNs"] > process_started_ns
        or process["executableCtimeEpochNs"] > process_started_ns
    ):
        fail("frontend Node executable changed after process start")
    environment_names = (
        (
            ".env",
            ".env.local",
            ".env.development",
            ".env.development.local",
        )
        if mode == "normal"
        else (
            ".env",
            ".env.local",
            ".env.production",
            ".env.production.local",
            ".env.demo",
            ".env.demo.local",
        )
    )
    for name in environment_names:
        candidate = expected_cwd / name
        if candidate.exists() or candidate.is_symlink():
            fail(
                f"{mode} frontend Vite runtime has an unbound "
                "environment file"
            )
    node_path = shutil.which("node")
    if not node_path:
        fail("expected Node executable is unavailable")
    expected_node = Path(node_path).resolve(strict=True)
    executable = Path(str(process["executableRealpath"]))
    if executable != expected_node:
        fail("frontend listener uses an unexpected Node executable")
    vite_link = expected_cwd / "node_modules/.bin/vite"
    try:
        vite_entry = vite_link.resolve(strict=True)
    except OSError:
        fail("fixed Vite entry is unavailable")
    expected_vite_entry = (
        expected_cwd / "node_modules/vite/bin/vite.js"
    ).resolve(strict=True)
    if vite_entry != expected_vite_entry:
        fail("frontend Vite entry resolves outside the fixed package")
    argv = tuple(process["argv"])
    if mode == "normal":
        validate_frontend_argv_contract(argv, executable, vite_link)
        environment_binding = validate_frontend_process_environment(
            process["_privateEnvironmentEntries"]
        )
    else:
        validate_demo_frontend_argv_contract(
            argv, executable, expected_vite_entry
        )
        environment_binding = validate_demo_frontend_process_environment(
            process["_privateEnvironmentEntries"]
        )
    contents, metadata, realpath = stable_file_bytes(
        vite_entry, "fixed Vite runtime entry"
    )
    if (
        metadata.st_mtime_ns > process_started_ns
        or metadata.st_ctime_ns > process_started_ns
    ):
        fail("frontend Vite entry changed after process start")
    public_process = {
        key: value
        for key, value in process.items()
        if key not in {"argv", "_privateEnvironmentEntries"}
    }
    public_process.update(
        {
            "argvCount": len(argv),
            "argvSha256": sha256_bytes(canonical_json_bytes(list(argv))),
            "viteEntryRealpath": realpath.as_posix(),
            "viteEntrySizeBytes": len(contents),
            "viteEntryMtimeEpochNs": metadata.st_mtime_ns,
            "viteEntryCtimeEpochNs": metadata.st_ctime_ns,
            "viteEntrySha256": sha256_bytes(contents),
            "environment": environment_binding,
            "_privateArgv": argv,
        }
    )
    return public_process


def listener_process_binding(
    origin: str,
    *,
    component: str,
    context: Mapping[str, Any],
    runtime_environment: Mapping[str, str] | None = None,
) -> dict[str, Any]:
    if component not in {"backend", "frontend", "demo"}:
        fail("runtime listener component is invalid")
    parsed = urllib.parse.urlsplit(origin)
    port = parsed.port
    if port is None:
        fail(f"{component} runtime origin lacks a port")
    lsof = shutil.which("lsof")
    ps = shutil.which("ps")
    if not lsof or not ps:
        fail("runtime listener inspection tools are unavailable")
    pid_rows = run_readonly_process_command(
        (
            lsof,
            "-nP",
            f"-iTCP:{port}",
            "-sTCP:LISTEN",
            "-t",
        ),
        f"{component} listener PID inspection",
    ).splitlines()
    try:
        pids = sorted({int(value.strip()) for value in pid_rows if value.strip()})
    except ValueError:
        fail(f"{component} listener PID output is malformed")
    if len(pids) != 1 or pids[0] <= 1:
        fail(f"{component} origin must have exactly one listener PID")
    pid = pids[0]
    listener_names = [
        line[1:]
        for line in run_readonly_process_command(
            (
                lsof,
                "-nP",
                "-a",
                "-p",
                str(pid),
                f"-iTCP:{port}",
                "-sTCP:LISTEN",
                "-Fn",
            ),
            f"{component} listener address inspection",
        ).splitlines()
        if line.startswith("n")
    ]
    if listener_names != [f"127.0.0.1:{port}"]:
        fail(f"{component} listener is not bound to the exact loopback origin")
    cwd_rows = [
        line[1:]
        for line in run_readonly_process_command(
            (lsof, "-a", "-p", str(pid), "-d", "cwd", "-Fn"),
            f"{component} listener cwd inspection",
        ).splitlines()
        if line.startswith("n")
    ]
    if len(cwd_rows) != 1:
        fail(f"{component} listener cwd is ambiguous")
    expected_cwd = (
        REPOSITORY_ROOT
        / ("frontend" if component in {"frontend", "demo"} else "backend")
    ).resolve(strict=True)
    try:
        actual_cwd = Path(cwd_rows[0]).resolve(strict=True)
    except OSError:
        fail(f"{component} listener cwd is unavailable")
    if actual_cwd != expected_cwd:
        fail(f"{component} listener cwd is outside this repository")
    started_text = " ".join(
        run_readonly_process_command(
            (ps, "-p", str(pid), "-o", "lstart="),
            f"{component} listener start inspection",
        ).split()
    )
    process_started = parse_ps_lstart_utc(
        started_text, f"{component} listener start time"
    )
    process_started_ns = int(process_started.timestamp()) * 1_000_000_000
    if process_started_ns < context["startedAtEpochNs"]:
        fail(f"{component} listener predates the current evidence run")
    if process_started_ns < context["sourceMaximumMtimeNs"]:
        fail(f"{component} listener predates the frozen source")

    entry: Path
    binding: dict[str, Any] = {}
    native_process = darwin_process_snapshot(pid)
    if component in {"frontend", "demo"}:
        process_binding = validate_frontend_process_snapshot(
            native_process,
            expected_cwd,
            process_started_ns,
            mode="normal" if component == "frontend" else "demo",
        )
        if component == "frontend":
            entry = expected_cwd / "src/main.tsx"
            build_inputs = collect_frontend_build_inputs(
                {"frontend-product-source": walk_regular_files(
                    expected_cwd / "src", "frontend listener source"
                )}
            )
            maximum_input_mtime = max(
                stable_file_bytes(
                    path, "frontend listener build input"
                )[1].st_mtime_ns
                for path in build_inputs
            )
            if process_started_ns < maximum_input_mtime:
                fail("frontend listener predates a current build input")
            binding["buildInputMaximumMtimeEpochNs"] = maximum_input_mtime
        else:
            entry = expected_cwd / "dist/demo/index.html"
            demo_snapshot = stable_tree_snapshot(
                expected_cwd / "dist/demo",
                "demo preview dist",
            )
            assert_tree_snapshot_predates_process(
                demo_snapshot,
                process_started_ns,
                "demo preview dist",
            )
            binding.update(
                {
                    "distFileCount": demo_snapshot["fileCount"],
                    "distTreeSha256": demo_snapshot["treeSha256"],
                    "distMinimumMtimeEpochNs": demo_snapshot[
                        "minimumMtimeEpochNs"
                    ],
                    "distMaximumMtimeEpochNs": demo_snapshot[
                        "maximumMtimeEpochNs"
                    ],
                    "distMinimumCtimeEpochNs": demo_snapshot[
                        "minimumCtimeEpochNs"
                    ],
                    "distMaximumCtimeEpochNs": demo_snapshot[
                        "maximumCtimeEpochNs"
                    ],
                    "_privateDemoDistSnapshot": demo_snapshot,
                }
            )
    else:
        if runtime_environment is None:
            fail("backend listener binding lacks the private runtime environment")
        process_binding = validate_backend_process_snapshot(
            native_process,
            expected_cwd,
            process_started_ns,
            runtime_environment,
        )
        entry = (
            expected_cwd
            / "target/classes/com/szsemicon/hr/ShenzhouHrApplication.class"
        )
        source_entry = (
            expected_cwd
            / "src/main/java/com/szsemicon/hr/ShenzhouHrApplication.java"
        )
        _, source_metadata, _ = stable_file_bytes(
            source_entry, "backend source entry"
        )
        class_snapshot = stable_tree_snapshot(
            expected_cwd / "target/classes", "backend runtime classes"
        )
        newest_java_mtime = max(
            stable_file_bytes(path, "backend Java source")[1].st_mtime_ns
            for path in walk_regular_files(
                expected_cwd / "src/main/java", "backend Java source"
            )
            if path.suffix == ".java"
        )
        if class_snapshot["maximumMtimeEpochNs"] < newest_java_mtime:
            fail("backend runtime classes are older than current Java source")
        class_records = [
            record
            for record in class_snapshot["records"]
            if str(record["path"]).endswith(".class")
        ]
        if not class_records:
            fail("backend runtime class snapshot contains no class files")
        if min(
            record["ctimeEpochNs"] for record in class_records
        ) < context["startedAtEpochNs"]:
            fail(
                "backend runtime classes were not clean-built in this run"
            )
        assert_tree_snapshot_predates_process(
            class_snapshot,
            process_started_ns,
            "backend runtime class snapshot",
        )
        backend_full_receipt = validate_backend_runtime_classes_receipt(
            context,
            class_snapshot,
        )
        binding.update(
            {
                "classFileCount": class_snapshot["fileCount"],
                "classTreeSha256": class_snapshot["treeSha256"],
                "classMinimumMtimeEpochNs": class_snapshot[
                    "minimumMtimeEpochNs"
                ],
                "classMaximumMtimeEpochNs": class_snapshot[
                    "maximumMtimeEpochNs"
                ],
                "classMinimumCtimeEpochNs": class_snapshot[
                    "minimumCtimeEpochNs"
                ],
                "classMaximumCtimeEpochNs": class_snapshot[
                    "maximumCtimeEpochNs"
                ],
                "javaSourceMaximumMtimeEpochNs": newest_java_mtime,
                "sourceEntryMtimeEpochNs": source_metadata.st_mtime_ns,
                "backendFullClassesReceipt": backend_full_receipt,
                "_privateRuntimeClassSnapshot": class_snapshot,
            }
        )
    private_process_binding = {
        key: binding_value
        for key, binding_value in process_binding.items()
        if key.startswith("_private")
    }
    process_binding = {
        key: binding_value
        for key, binding_value in process_binding.items()
        if not key.startswith("_private")
    }
    _, entry_metadata, entry_realpath = stable_file_bytes(
        entry, f"{component} runtime entry"
    )
    if entry_metadata.st_mtime_ns > process_started_ns:
        fail(f"{component} runtime entry is newer than its listener process")
    return {
        "component": component,
        "origin": origin,
        "listenerPid": pid,
        "cwd": actual_cwd.as_posix(),
        "processStartedAt": process_started.astimezone(timezone.utc)
        .isoformat(timespec="seconds")
        .replace("+00:00", "Z"),
        "processStartedAtEpochNs": process_started_ns,
        "entryRealpath": entry_realpath.as_posix(),
        "entryMtimeEpochNs": entry_metadata.st_mtime_ns,
        "processBinding": process_binding,
        "_privateProcessBinding": private_process_binding,
        **binding,
        "verdict": "PASS",
    }


def fetch_runtime_text(origin: str, target: str, label: str) -> str:
    request = urllib.request.Request(
        origin + target,
        headers={
            "Accept": "text/plain,*/*;q=0.1",
            "Accept-Encoding": "identity",
            "Cache-Control": "no-cache",
        },
        method="GET",
    )
    try:
        response: Any = HTTP_OPENER.open(
            request, timeout=HTTP_TIMEOUT_SECONDS
        )
    except urllib.error.HTTPError as error:
        fail(f"{label} returned HTTP {error.code}")
    except (OSError, urllib.error.URLError, TimeoutError):
        fail(f"{label} could not reach the bound frontend")
    try:
        body = response.read(MAX_HTTP_BODY_BYTES + 1)
    except OSError:
        fail(f"{label} response is unreadable")
    if int(response.status) != 200 or len(body) > MAX_HTTP_BODY_BYTES:
        fail(f"{label} response is not a bounded HTTP 200")
    scan_response_headers_in_memory(response.headers, label)
    try:
        text = body.decode("utf-8")
    except UnicodeError:
        fail(f"{label} response is not UTF-8")
    require_no_deny_match(text, f"{label}/body")
    return text


def unwrap_vite_raw_text(response_text: str, label: str) -> str:
    prefix = "export default "
    start = response_text.find(prefix)
    if start < 0:
        fail(f"{label} is not an exact Vite raw module")
    try:
        value, _ = json.JSONDecoder().raw_decode(
            response_text[start + len(prefix) :]
        )
    except json.JSONDecodeError:
        fail(f"{label} Vite raw module is malformed")
    if not isinstance(value, str):
        fail(f"{label} Vite raw module is not text")
    return value


def normalize_vite_index_html(value: str, label: str) -> str:
    refresh = """<script type="module" nonce="__CSP_NONCE__">import { injectIntoGlobalHook } from "/@react-refresh";
injectIntoGlobalHook(window);
window.$RefreshReg$ = () => {};
window.$RefreshSig$ = () => (type) => type;</script>"""
    normalized, refresh_count = re.subn(
        r"\s*" + re.escape(refresh) + r"\s*", "\n", value
    )
    normalized, client_count = re.subn(
        r'\s*<script type="module" src="/@vite/client" '
        r'nonce="__CSP_NONCE__"></script>\s*',
        "\n",
        normalized,
    )
    normalized, meta_count = re.subn(
        r'\s*<meta property="csp-nonce" nonce="__CSP_NONCE__">\s*',
        "\n",
        normalized,
    )
    normalized, entry_count = re.subn(
        r'src="/src/main\.tsx\?t=\d+" nonce="__CSP_NONCE__"',
        'src="/src/main.tsx"',
        normalized,
    )
    if (refresh_count, client_count, meta_count, entry_count) != (1, 1, 1, 1):
        fail(f"{label} Vite HTML transform closure is invalid")
    return "\n".join(
        line.strip() for line in normalized.splitlines() if line.strip()
    )


def verify_frontend_source_binding(
    frontend_origin: str,
    scopes: Mapping[str, Sequence[Path]],
    product_records: Sequence[Mapping[str, Any]],
    *,
    reverse: bool = False,
) -> dict[str, Any]:
    records = {
        str(record["path"]): record
        for record in product_records
        if record["scopeId"] == "frontend-product-source"
    }
    response_records: list[tuple[str, str]] = []
    source_paths = list(scopes["frontend-product-source"])
    if reverse:
        source_paths.reverse()
    for path in source_paths:
        relative = unicodedata.normalize(
            "NFC", path.relative_to(REPOSITORY_ROOT).as_posix()
        )
        record = records.get(relative)
        if record is None:
            fail("frontend source binding lacks a scanned record")
        contents, _, _ = stable_file_bytes(
            path, f"frontend source binding {relative}"
        )
        if sha256_bytes(contents) != record["sha256"]:
            fail("frontend source changed before runtime binding")
        expected_text = decode_required_bytes(path, contents)
        if relative == "frontend/index.html":
            response_text = fetch_runtime_text(
                frontend_origin,
                "/index.html",
                "frontend Vite index binding",
            )
            if normalize_vite_index_html(
                response_text, "frontend Vite index"
            ) != "\n".join(
                line.strip()
                for line in expected_text.splitlines()
                if line.strip()
            ):
                fail("frontend Vite index differs from frozen source")
        else:
            if relative.startswith("frontend/src/"):
                request_path = "/" + relative.removeprefix("frontend/")
            elif relative.startswith("frontend/public/"):
                request_path = "/" + relative.removeprefix(
                    "frontend/public/"
                )
            else:
                fail("frontend source binding path is outside fixed roots")
            response_text = fetch_runtime_text(
                frontend_origin,
                urllib.parse.quote(request_path, safe="/") + "?raw",
                f"frontend Vite raw binding {relative}",
            )
            if response_text != expected_text:
                response_text = unwrap_vite_raw_text(
                    response_text, f"frontend Vite raw binding {relative}"
                )
            if response_text != expected_text:
                fail("frontend Vite raw response differs from frozen source")
        response_records.append((relative, record["sha256"]))
    framed = b"".join(
        f"{path}\0{digest}\n".encode("utf-8")
        for path, digest in sorted(response_records)
    )
    return {
        "bindingKind": "vite-raw-source-byte-equivalence",
        "origin": frontend_origin,
        "fileCount": len(response_records),
        "sourceBindingSha256": sha256_bytes(framed),
        "sourceTreeHash": current_source_tree_hash(),
        "verdict": "PASS",
    }


def mysql_option_escape(value: str) -> str:
    return (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
    )


def create_mysql_defaults_file(
    directory: Path, name: str, user: str, password: str
) -> Path:
    path = directory / name
    contents = "\n".join(
        (
            "[client]",
            f'host="{mysql_option_escape(MYSQL_HOST)}"',
            f'port="{mysql_option_escape(MYSQL_PORT)}"',
            "protocol=tcp",
            f'user="{mysql_option_escape(user)}"',
            f'password="{mysql_option_escape(password)}"',
            "",
        )
    )
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(contents)
        output.flush()
        os.fsync(output.fileno())
    os.chmod(path, 0o600)
    return path


def mysql_query(
    environment: Mapping[str, str], defaults_path: Path, sql: str
) -> str:
    try:
        result = subprocess.run(
            (
                environment["SHENZHOUHR_MYSQL_CLIENT_BIN"],
                f"--defaults-extra-file={defaults_path}",
                "--batch",
                "--skip-column-names",
                f"--database={MYSQL_DATABASE}",
                "--execute",
                sql,
            ),
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
            timeout=SUBPROCESS_TIMEOUT_SECONDS,
            env=safe_subprocess_environment(),
        )
    except (OSError, subprocess.TimeoutExpired):
        fail("MySQL evidence query could not execute")
    if result.returncode != 0:
        fail("MySQL evidence query failed")
    return result.stdout


def database_identity_for(server_uuid: str) -> str:
    if not UUID_PATTERN.fullmatch(server_uuid):
        fail("MySQL server UUID is invalid")
    return f"mysql8410:{server_uuid.lower()}:{MYSQL_DATABASE}"


def expected_database_principals(
    principals: Sequence[Mapping[str, Any]],
) -> dict[str, tuple[str, str, str]]:
    if len(principals) != len(ROLE_CODES):
        fail("backend/database binding requires the exact three principals")
    expected: dict[str, tuple[str, str, str]] = {}
    for index, principal in enumerate(principals):
        role_code = require_string(
            principal.get("roleCode"), "binding principal role code"
        )
        account_id = require_string(
            principal.get("accountId"), "binding principal account ID"
        )
        username = require_string(
            principal.get("username"), "binding principal username"
        )
        role_id = require_string(
            principal.get("roleId"), "binding principal role ID"
        )
        if role_code != ROLE_CODES[index]:
            fail("backend/database binding role order is invalid")
        identifier_pattern = (
            r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
        )
        if not re.fullmatch(
            identifier_pattern, account_id, re.IGNORECASE
        ) or not re.fullmatch(identifier_pattern, role_id, re.IGNORECASE):
            fail("backend/database binding returned a non-UUID identifier")
        if not re.fullmatch(r"[a-z0-9_]{8,128}", username):
            fail("backend/database binding username is not safely derived")
        if account_id in expected:
            fail("backend/database binding duplicates an account ID")
        expected[account_id] = (username, role_id, role_code)
    return expected


def flyway_utf8_crc32(contents: bytes, label: str) -> int:
    """Match Flyway's line-by-line UTF-8 CRC32 and Java signed int."""
    try:
        text = contents.decode("utf-8", errors="strict")
    except UnicodeError:
        fail(f"{label} is not strict UTF-8")
    checksum = 0
    for index, line in enumerate(text.splitlines()):
        if index == 0 and line.startswith("\ufeff"):
            line = line.removeprefix("\ufeff")
        checksum = zlib.crc32(line.encode("utf-8"), checksum)
    unsigned = checksum & 0xFFFFFFFF
    return unsigned if unsigned < 0x80000000 else unsigned - 0x100000000


def frozen_flyway_history() -> list[dict[str, Any]]:
    """Derive exact V1-V7 history from independently pinned migration bytes."""
    expected_names = [entry[0] for entry in FROZEN_FLYWAY_MIGRATIONS]
    try:
        migration_metadata = FLYWAY_MIGRATION_ROOT.lstat()
        actual_names = sorted(
            path.name
            for path in FLYWAY_MIGRATION_ROOT.iterdir()
            if path.name.startswith("V") and path.suffix == ".sql"
        )
    except OSError:
        fail("frozen Flyway migration directory is unavailable")
    if (
        not stat.S_ISDIR(migration_metadata.st_mode)
        or stat.S_ISLNK(migration_metadata.st_mode)
        or actual_names != sorted(expected_names)
    ):
        fail("frozen Flyway migration V1-V7 file closure differs")

    history: list[dict[str, Any]] = []
    for installed_rank, (
        filename,
        expected_sha256,
        expected_checksum,
    ) in enumerate(FROZEN_FLYWAY_MIGRATIONS, start=1):
        match = re.fullmatch(r"V([1-7])__(.+)\.sql", filename)
        if match is None or int(match.group(1)) != installed_rank:
            fail("frozen Flyway migration filename/version closure differs")
        contents, _metadata, _resolved = stable_file_bytes(
            FLYWAY_MIGRATION_ROOT / filename,
            f"frozen Flyway migration {filename}",
        )
        if sha256_bytes(contents) != expected_sha256:
            fail(f"frozen Flyway migration source SHA256 differs: {filename}")
        checksum = flyway_utf8_crc32(contents, filename)
        if checksum != expected_checksum:
            fail(f"frozen Flyway migration checksum differs: {filename}")
        history.append(
            {
                "installedRank": installed_rank,
                "version": str(installed_rank),
                "description": match.group(2).replace("_", " "),
                "type": "SQL",
                "script": filename,
                "checksum": str(checksum),
                "success": True,
            }
        )
    return history


def consistent_database_snapshot_sql(
    expected_principals: Mapping[str, tuple[str, str, str]],
) -> str:
    statements = [
        "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ",
        "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY",
        "SET @evidence_now = UTC_TIMESTAMP(6)",
        (
            "SELECT 'SERVER', VERSION(), @@port, @@server_uuid, @@socket, "
            "@@datadir, DATABASE()"
        ),
        (
            "SELECT 'FLYWAY', installed_rank, version, description, type, "
            "script, COALESCE(CAST(checksum AS CHAR), 'NULL'), success "
            "FROM flyway_schema_history ORDER BY installed_rank"
        ),
        (
            "SELECT 'MARKER', 'legalEntity', COUNT(*) FROM legal_entity "
            f"WHERE legal_entity_id = '{LEGAL_ENTITY_ID}'"
        ),
        (
            "SELECT 'MARKER', 'policyTemplates', COUNT(*) "
            "FROM attendance_policy_template"
        ),
        (
            "SELECT 'MARKER', 'policyVersions', COUNT(*) "
            "FROM attendance_policy_scoped_version"
        ),
        (
            "SELECT 'MARKER', 'groupRevisions', COUNT(*) "
            "FROM attendance_group_revision"
        ),
        "SELECT 'MARKER', 'shiftVersions', COUNT(*) FROM shift_version",
        (
            "SELECT 'MARKER', 'calendarVersions', COUNT(*) "
            "FROM work_calendar_version"
        ),
        (
            "SELECT 'MARKER', 'organizations', COUNT(*) "
            "FROM organization_current_projection"
        ),
        (
            "SELECT 'MARKER', 'employees', COUNT(*) "
            "FROM employee_current_projection"
        ),
        (
            "SELECT 'MARKER', 'peopleImportBatches', COUNT(*) "
            "FROM people_import_batch"
        ),
        (
            "SELECT 'MARKER', 'retainedPolicyTemplates', COUNT(*) "
            "FROM policy_template"
        ),
    ]
    if expected_principals:
        account_literals = ", ".join(
            f"'{value}'" for value in expected_principals
        )
        statements.append(
            "SELECT 'PRINCIPAL', la.account_id, la.username, ar.role_id, "
            "ar.role_code, ads.scope_type, "
            "COALESCE(ads.legal_entity_id, '') "
            "FROM local_account la "
            "JOIN auth_principal_role_assignment apra "
            "ON apra.principal_id = la.principal_id "
            "JOIN auth_role ar ON ar.role_id = apra.role_id "
            "JOIN auth_data_scope ads "
            "ON ads.scope_id = apra.data_scope_id "
            f"WHERE la.account_id IN ({account_literals}) "
            "AND apra.valid_from <= @evidence_now "
            "AND (apra.valid_to IS NULL OR apra.valid_to > @evidence_now) "
            "ORDER BY la.account_id"
        )
    statements.append("COMMIT")
    return ";\n".join(statements) + ";\n"


def parse_consistent_database_snapshot(
    rows: Sequence[str],
    runtime_environment: Mapping[str, str],
    expected_principals: Mapping[str, tuple[str, str, str]],
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    server_rows: list[list[str]] = []
    history_rows: list[list[str]] = []
    marker_rows: list[list[str]] = []
    principal_rows: list[list[str]] = []
    for line in rows:
        fields = line.split("\t")
        if not fields:
            continue
        if fields[0] == "SERVER":
            server_rows.append(fields[1:])
        elif fields[0] == "FLYWAY":
            history_rows.append(fields[1:])
        elif fields[0] == "MARKER":
            marker_rows.append(fields[1:])
        elif fields[0] == "PRINCIPAL":
            principal_rows.append(fields[1:])
        else:
            fail("consistent database snapshot returned an unknown row type")
    if len(server_rows) != 1 or len(server_rows[0]) != 6:
        fail("database server identity row is malformed")
    (
        reported_version,
        reported_port,
        server_uuid,
        socket,
        datadir,
        database,
    ) = server_rows[0]
    version_match = re.match(r"^(\d+\.\d+\.\d+)", reported_version)
    version_core = version_match.group(1) if version_match else ""
    if version_core != MYSQL_VERSION:
        fail(f"MySQL SemVer core must be exactly {MYSQL_VERSION}")
    if reported_port != MYSQL_PORT:
        fail(f"MySQL reported port must be exactly {MYSQL_PORT}")
    if database != MYSQL_DATABASE:
        fail(f"MySQL database must be exactly {MYSQL_DATABASE}")
    if (
        not Path(socket).as_posix().startswith(
            MYSQL_ISOLATED_ROOT.as_posix() + "/"
        )
        or not Path(datadir).as_posix().startswith(
            MYSQL_ISOLATED_ROOT.as_posix() + "/"
        )
    ):
        fail("MySQL socket/datadir are outside the isolated root")
    identity = database_identity_for(server_uuid)
    if runtime_environment["SHENZHOUHR_W3_DB_IDENTITY"] != identity:
        fail("runtime environment database identity differs from MySQL")

    expected_history = frozen_flyway_history()
    if len(history_rows) != len(expected_history):
        fail("Flyway history must be exactly the frozen V1-V7 closure")
    history: list[dict[str, Any]] = []
    for index, (fields, expected) in enumerate(
        zip(history_rows, expected_history, strict=True),
        start=1,
    ):
        expected_fields = [
            str(expected["installedRank"]),
            str(expected["version"]),
            str(expected["description"]),
            str(expected["type"]),
            str(expected["script"]),
            str(expected["checksum"]),
            "1",
        ]
        if len(fields) != 7 or fields != expected_fields:
            fail(
                "Flyway history differs from frozen source at "
                f"installed rank {index}"
            )
        history.append(dict(expected))
    history_text = "\n".join(
        "\t".join(
            (
                str(row["installedRank"]),
                row["version"],
                row["description"],
                row["type"],
                row["script"],
                row["checksum"],
                "1",
            )
        )
        for row in history
    ) + "\n"

    marker_names = (
        "legalEntity",
        "policyTemplates",
        "policyVersions",
        "groupRevisions",
        "shiftVersions",
        "calendarVersions",
        "organizations",
        "employees",
        "peopleImportBatches",
        "retainedPolicyTemplates",
    )
    if (
        len(marker_rows) != len(marker_names)
        or tuple(row[0] for row in marker_rows) != marker_names
        or any(len(row) != 2 for row in marker_rows)
    ):
        fail("application database marker query is incomplete")
    try:
        marker_values = [int(row[1]) for row in marker_rows]
    except ValueError:
        fail("application database marker count is invalid")
    if any(value < 0 for value in marker_values):
        fail("application database marker count is negative")
    if marker_values[0] != 1:
        fail("fixed legal entity is missing from the bound database")
    if marker_values[1] != 3 or marker_values[2] < 3:
        fail("W3 policy seed database marker is invalid")
    database_binding = {
        "databaseIdentity": identity,
        "snapshotKind": "single-repeatable-read-consistent-snapshot",
        "server": {
            "version": reported_version,
            "versionCore": version_core,
            "host": MYSQL_HOST,
            "port": int(reported_port),
            "serverUuid": server_uuid.lower(),
            "socket": socket,
            "datadir": datadir,
            "database": database,
        },
        "flyway": {
            "latestVersion": history[-1]["version"],
            "appliedCount": len(history),
            "historySha256": sha256_text(history_text),
        },
        "applicationReadMarker": dict(
            zip(marker_names, marker_values, strict=True)
        ),
    }

    if not expected_principals:
        if principal_rows:
            fail("unexpected principal rows in database-only snapshot")
        return database_binding, None
    if len(principal_rows) != len(expected_principals) or any(
        len(row) != 6 for row in principal_rows
    ):
        fail("backend/API principals do not bind exactly to the current database")
    evidence_rows: list[dict[str, Any]] = []
    seen: set[str] = set()
    for (
        account_id,
        username,
        role_id,
        role_code,
        scope_type,
        entity_id,
    ) in principal_rows:
        if account_id in seen or expected_principals.get(account_id) != (
            username,
            role_id,
            role_code,
        ):
            fail("backend/API principal differs from the database row")
        if scope_type != "LEGAL_ENTITY" or entity_id != LEGAL_ENTITY_ID:
            fail("backend/API principal database scope is invalid")
        seen.add(account_id)
        evidence_rows.append(
            {
                "roleCode": role_code,
                "accountIdSha256": sha256_text(account_id),
                "usernameSha256": sha256_text(username),
                "roleIdSha256": sha256_text(role_id),
                "scopeType": scope_type,
                "legalEntityId": entity_id,
            }
        )
    if seen != set(expected_principals):
        fail("backend/API principal database binding is incomplete")
    evidence_rows.sort(key=lambda row: ROLE_CODES.index(row["roleCode"]))
    principal_binding = {
        "bindingKind": "api-account-to-mysql-row",
        "snapshotKind": "same-transaction-as-database-binding",
        "rowCount": len(evidence_rows),
        "rows": evidence_rows,
        "verdict": "PASS",
    }
    return database_binding, principal_binding


def create_consistent_database_snapshot(
    runtime_environment: Mapping[str, str],
    principals: Sequence[Mapping[str, Any]] = (),
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    expected_principals = (
        expected_database_principals(principals) if principals else {}
    )
    with tempfile.TemporaryDirectory(
        prefix="shenzhouhr-w3-zero-consistent-db-"
    ) as temporary:
        defaults = create_mysql_defaults_file(
            Path(temporary),
            "app.cnf",
            MYSQL_APPLICATION_USERNAME,
            runtime_environment["SHENZHOUHR_TEST_DB_PASSWORD"],
        )
        rows = [
            line
            for line in mysql_query(
                runtime_environment,
                defaults,
                consistent_database_snapshot_sql(expected_principals),
            ).splitlines()
            if line
        ]
    return parse_consistent_database_snapshot(
        rows, runtime_environment, expected_principals
    )


def create_database_binding(
    runtime_environment: Mapping[str, str]
) -> dict[str, Any]:
    database, principal_binding = create_consistent_database_snapshot(
        runtime_environment
    )
    if principal_binding is not None:
        fail("database-only snapshot unexpectedly returned principals")
    return database


def verify_backend_database_binding(
    runtime_environment: Mapping[str, str],
    principals: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    _, principal_binding = create_consistent_database_snapshot(
        runtime_environment, principals
    )
    if principal_binding is None:
        fail("backend/database snapshot omitted principal rows")
    return principal_binding


def assert_database_binding(
    context: Mapping[str, Any], binding: Mapping[str, Any]
) -> None:
    if binding["databaseIdentity"] != context["databaseIdentity"]:
        fail("actual MySQL identity differs from the run context")


def assert_final_database_seal(
    runtime_document: Mapping[str, Any],
    database_binding: Mapping[str, Any],
    principal_binding: Mapping[str, Any] | None,
) -> None:
    if principal_binding is None:
        fail("final database seal omitted principal/API row binding")
    if canonical_json_bytes(database_binding) != canonical_json_bytes(
        runtime_document["databaseEnd"]
    ):
        fail("database identity/Flyway binding changed before publication")
    if canonical_json_bytes(principal_binding) != canonical_json_bytes(
        runtime_document["backendDatabaseBinding"]
    ):
        fail("backend/API principal database binding changed before publication")


def safe_response_header_record(name: str, value: str) -> dict[str, Any]:
    safe_name = str(name).strip().lower()
    safe_value = str(value).replace("\r", " ").replace("\n", " ").strip()
    return {
        "name": safe_name,
        "valuePresent": bool(safe_value),
        "valuePolicy": "scanned-in-memory-not-persisted",
    }


def response_headers_document(headers: Message) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for name, value in headers.items():
        records.append(safe_response_header_record(str(name), str(value)))
    return sorted(
        records,
        key=lambda item: (
            str(item["name"]),
            str(item.get("value", item.get("valuePresent", ""))),
        ),
    )


def scan_response_headers_in_memory(headers: Message, label: str) -> None:
    for name, value in headers.items():
        require_no_deny_match(str(name), f"{label}/header-name")
        require_no_deny_match(str(value), f"{label}/header-value")


def header_value(headers: Message, name: str) -> str:
    value = headers.get(name, "")
    return str(value).replace("\r", " ").replace("\n", " ").strip()


def exact_single_header_value(
    headers: Message, name: str, label: str
) -> str:
    values = headers.get_all(name, [])
    if len(values) != 1:
        fail(f"{label} must contain exactly one {name} header")
    value = (
        str(values[0]).replace("\r", " ").replace("\n", " ").strip()
    )
    if not value:
        fail(f"{label} contains an empty {name} header")
    return value


@dataclass(frozen=True)
class HttpResponse:
    status: int
    body_text: str
    body_json: Any
    headers: Message


class ApiSession:
    """Small cookie/CSRF session whose diagnostics never include request bodies."""

    def __init__(self, backend_origin: str):
        self.backend_origin = backend_origin
        self.cookie = ""
        self.csrf_token = ""

    def request(
        self,
        label: str,
        path: str,
        *,
        method: str = "GET",
        body: Any = ...,
        headers: Mapping[str, str] | None = None,
        expected_statuses: Sequence[int] = (200,),
        include_authentication: bool = True,
        include_csrf: bool = True,
    ) -> HttpResponse:
        if not path.startswith("/api/v1/") or "\\" in path:
            fail(f"invalid API request target for {label}")
        request_headers = {
            "Accept": "application/json",
            **dict(headers or {}),
        }
        if include_authentication and self.cookie:
            request_headers["Cookie"] = self.cookie
        if (
            method not in {"GET", "HEAD", "OPTIONS"}
            and include_csrf
            and self.csrf_token
        ):
            request_headers["X-CSRF-TOKEN"] = self.csrf_token
        payload: bytes | None = None
        if body is not ...:
            request_headers["Content-Type"] = "application/json"
            payload = json.dumps(
                body, ensure_ascii=False, separators=(",", ":")
            ).encode("utf-8")
        request = urllib.request.Request(
            self.backend_origin + path,
            data=payload,
            headers=request_headers,
            method=method,
        )
        try:
            response: Any = HTTP_OPENER.open(
                request, timeout=HTTP_TIMEOUT_SECONDS
            )
        except urllib.error.HTTPError as error:
            response = error
        except (OSError, urllib.error.URLError, TimeoutError):
            fail(f"{label} could not reach the loopback backend")
        try:
            body_bytes = response.read(MAX_HTTP_BODY_BYTES + 1)
        except OSError:
            fail(f"{label} returned an unreadable response")
        if len(body_bytes) > MAX_HTTP_BODY_BYTES:
            fail(f"{label} response exceeds the evidence size limit")
        try:
            body_text = body_bytes.decode("utf-8")
        except UnicodeError:
            fail(f"{label} response is not UTF-8")
        scan_response_headers_in_memory(response.headers, f"{label}/response")
        require_no_deny_match(body_text, f"{label}/response-body")
        set_cookie = header_value(response.headers, "Set-Cookie")
        if set_cookie:
            self.cookie = set_cookie.split(";", 1)[0]
        received_csrf = header_value(response.headers, "X-CSRF-TOKEN")
        if received_csrf:
            self.csrf_token = received_csrf
        content_type = header_value(response.headers, "Content-Type").lower()
        body_json: Any = ""
        if body_text and "json" in content_type:
            body_json = strict_json_loads(body_text, f"{label} response")
        status = int(response.status)
        if status not in expected_statuses:
            fail(
                f"{label} returned unexpected HTTP status {status}; "
                f"expected {'/'.join(map(str, expected_statuses))}"
            )
        return HttpResponse(
            status=status,
            body_text=body_text,
            body_json=body_json,
            headers=response.headers,
        )

    def login(
        self,
        username: str,
        password: str,
        label: str,
    ) -> HttpResponse:
        return self.request(
            label,
            "/api/v1/auth/login",
            method="POST",
            body={"username": username, "password": password},
            include_authentication=False,
            include_csrf=False,
        )


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(f"{label} must be a JSON object")
    return value


def require_array(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        fail(f"{label} must be a JSON array")
    return value


def require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        fail(f"{label} must be a non-empty string")
    return value


def derived_principal_secrets(
    admin_password: str, context: Mapping[str, Any], role_code: str
) -> dict[str, str]:
    if not admin_password or role_code not in ROLE_CODES:
        fail("synthetic principal derivation input is invalid")
    binding = (
        f"{context['runId']}|{context['sourceTreeHash']}|"
        f"{context['databaseIdentity']}|{role_code}"
    ).encode("utf-8")

    def digest(label: str, encoding: str) -> str:
        value = hmac.new(
            admin_password.encode("utf-8"),
            label.encode("ascii") + b"|" + binding,
            hashlib.sha256,
        ).digest()
        if encoding == "hex":
            return value.hex()
        import base64

        return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")

    return {
        "username": f"w3_{role_code.lower()}_{digest('username', 'hex')[:16]}",
        "password": f"W3!aA9-{digest('password', 'base64')[:36]}",
        "temporaryPassword": (
            f"W3!tT9-{digest('temporary', 'base64')[:36]}"
        ),
    }


def normalized_menu(view: Mapping[str, Any], role_code: str) -> list[dict[str, str]]:
    raw_menu = require_array(view.get("menu"), f"{role_code} menu")
    if not raw_menu:
        fail(f"{role_code} authorized menu must not be empty")
    menu: list[dict[str, str]] = []
    seen: set[str] = set()
    for index, raw_item in enumerate(raw_menu):
        item = require_object(raw_item, f"{role_code} menu[{index}]")
        key = require_string(item.get("key"), f"{role_code} menu key")
        label = require_string(item.get("label"), f"{role_code} menu label")
        path = require_string(item.get("path"), f"{role_code} menu path")
        if key in seen:
            fail(f"{role_code} menu contains duplicate keys")
        seen.add(key)
        menu.append({"key": key, "label": label, "path": path})
    for key, path in EXPECTED_W3_MENU.items():
        matching = [item for item in menu if item["key"] == key]
        if len(matching) != 1 or matching[0]["path"] != path:
            fail(f"{role_code} required W3 menu item is missing or invalid")
    return menu


def normalized_capabilities(
    view: Mapping[str, Any], role_code: str
) -> list[str]:
    raw = require_array(view.get("capabilities"), f"{role_code} capabilities")
    capabilities = [
        require_string(value, f"{role_code} capability") for value in raw
    ]
    if len(capabilities) != len(set(capabilities)):
        fail(f"{role_code} capabilities contain duplicates")
    if role_code == "AUDITOR":
        attendance = sorted(
            value
            for value in capabilities
            if value.startswith("ATTENDANCE_SETUP:")
        )
        if tuple(attendance) != AUDITOR_CAPABILITIES:
            fail("AUDITOR attendance capability set is not read-only")
    else:
        missing = [
            capability
            for capability in MANAGER_CAPABILITIES
            if capability not in capabilities
        ]
        if missing:
            fail(f"{role_code} lacks required attendance capabilities")
    return sorted(capabilities)


def account_role_matches(account: Mapping[str, Any], role_code: str) -> bool:
    raw_roles = account.get("roles")
    if not isinstance(raw_roles, list) or len(raw_roles) != 1:
        return False
    assignment = raw_roles[0]
    return bool(
        isinstance(assignment, dict)
        and assignment.get("roleCode") == role_code
        and assignment.get("scopeType") == "LEGAL_ENTITY"
        and assignment.get("scopeResourceId") == LEGAL_ENTITY_ID
    )


def require_account_row_version(value: Any, role_code: str) -> int:
    if type(value) is not int or value < 0:
        fail(f"{role_code} account rowVersion is invalid")
    return value


def load_role_principals(
    *,
    backend_origin: str,
    login_environment: Mapping[str, str],
    context: Mapping[str, Any],
    provision_synthetic_accounts: bool,
) -> list[dict[str, Any]]:
    admin = ApiSession(backend_origin)
    admin_login = require_object(
        admin.login(
            login_environment["SHENZHOUHR_LOGIN_USERNAME"],
            login_environment["SHENZHOUHR_LOGIN_PASSWORD"],
            "bootstrap-admin-login",
        ).body_json,
        "bootstrap login response",
    )
    if admin_login.get("firstPasswordChangeRequired") is not False:
        fail("bootstrap admin must have completed first password change")
    roles_body = require_array(
        admin.request(
            "bootstrap-role-catalog", "/api/v1/access/roles"
        ).body_json,
        "role catalog",
    )
    roles: dict[str, dict[str, Any]] = {}
    for raw_role in roles_body:
        role = require_object(raw_role, "role catalog item")
        role_code_value = role.get("roleCode", role.get("code"))
        if not isinstance(role_code_value, str):
            continue
        roles[role_code_value] = {
            "roleId": require_string(
                role.get("roleId"), f"{role_code_value} roleId"
            ),
            "roleCode": role_code_value,
            "roleName": require_string(
                role.get("roleName", role.get("name")),
                f"{role_code_value} roleName",
            ),
        }
    if any(role_code not in roles for role_code in ROLE_CODES):
        fail("required V2 role catalog is incomplete")

    principals: list[dict[str, Any]] = []
    for role_code in ROLE_CODES:
        role = roles[role_code]
        secrets = derived_principal_secrets(
            login_environment["SHENZHOUHR_LOGIN_PASSWORD"],
            context,
            role_code,
        )
        encoded_query = urllib.parse.quote(secrets["username"], safe="")
        account_page = require_object(
            admin.request(
                f"find-{role_code.lower()}-account",
                f"/api/v1/access/accounts?query={encoded_query}&page=0&size=20",
            ).body_json,
            f"{role_code} account search",
        )
        items = require_array(
            account_page.get("items"), f"{role_code} account search items"
        )
        matches = [
            require_object(item, f"{role_code} account search item")
            for item in items
            if isinstance(item, dict)
            and item.get("username") == secrets["username"]
        ]
        if len(matches) > 1:
            fail(f"{role_code} synthetic username is not unique")
        created = False
        repaired = False
        if not matches:
            if not provision_synthetic_accounts:
                fail(
                    f"{role_code} run-bound account is absent; "
                    "explicit provisioning is required"
                )
            account = require_object(
                admin.request(
                    f"create-{role_code.lower()}-account",
                    "/api/v1/access/accounts",
                    method="POST",
                    body={
                        "username": secrets["username"],
                        "displayName": f"W3 验收 {role_code}",
                        "temporaryPassword": secrets["temporaryPassword"],
                        "roleAssignments": [
                            {
                                "roleId": role["roleId"],
                                "scopeType": "LEGAL_ENTITY",
                                "scopeResourceId": LEGAL_ENTITY_ID,
                                "validFrom": "2000-01-01T00:00:00Z",
                                "validTo": None,
                            }
                        ],
                    },
                    expected_statuses=(201,),
                ).body_json,
                f"{role_code} account create response",
            )
            created = True
        else:
            summary = matches[0]
            account_id = require_string(
                summary.get("accountId"), f"{role_code} accountId"
            )
            account = require_object(
                admin.request(
                    f"get-{role_code.lower()}-account",
                    f"/api/v1/access/accounts/"
                    f"{urllib.parse.quote(account_id, safe='')}",
                ).body_json,
                f"{role_code} account detail",
            )
        if account.get("status") != "ACTIVE":
            fail(f"{role_code} run-bound account is not ACTIVE")
        if not account_role_matches(account, role_code):
            if not provision_synthetic_accounts:
                fail(f"{role_code} run-bound role assignment is invalid")
            account_id = require_string(
                account.get("accountId"), f"{role_code} accountId"
            )
            row_version = require_account_row_version(
                account.get("rowVersion"), role_code
            )
            account = require_object(
                admin.request(
                    f"repair-{role_code.lower()}-role-assignment",
                    f"/api/v1/access/accounts/"
                    f"{urllib.parse.quote(account_id, safe='')}"
                    "/role-assignments",
                    method="PUT",
                    body={
                        "assignments": [
                            {
                                "roleId": role["roleId"],
                                "scopeType": "LEGAL_ENTITY",
                                "scopeResourceId": LEGAL_ENTITY_ID,
                                "validFrom": "2000-01-01T00:00:00Z",
                                "validTo": None,
                            }
                        ],
                        "reason": (
                            f"W3 {context['runId']} 本地验收角色修复"
                        ),
                        "expectedVersion": row_version,
                    },
                ).body_json,
                f"{role_code} role repair response",
            )
            repaired = True
        if not account_role_matches(account, role_code):
            fail(f"{role_code} exact role assignment could not be established")

        role_session = ApiSession(backend_origin)
        login_password = (
            secrets["temporaryPassword"]
            if account.get("firstPasswordChangeRequired") is True
            else secrets["password"]
        )
        login = require_object(
            role_session.login(
                secrets["username"],
                login_password,
                f"{role_code.lower()}-login",
            ).body_json,
            f"{role_code} login response",
        )
        if login.get("firstPasswordChangeRequired") is True:
            if not provision_synthetic_accounts:
                fail(f"{role_code} requires an explicit first-password change")
            role_session.request(
                f"{role_code.lower()}-first-password-change",
                "/api/v1/auth/password/first-change",
                method="POST",
                body={
                    "currentPassword": secrets["temporaryPassword"],
                    "newPassword": secrets["password"],
                },
                expected_statuses=(204,),
            )
            role_session = ApiSession(backend_origin)
            login = require_object(
                role_session.login(
                    secrets["username"],
                    secrets["password"],
                    f"{role_code.lower()}-permanent-login",
                ).body_json,
                f"{role_code} permanent login response",
            )
        if (
            login.get("firstPasswordChangeRequired") is not False
            or login.get("username") != secrets["username"]
        ):
            fail(f"{role_code} session identity is invalid")
        capabilities_view = require_object(
            role_session.request(
                f"{role_code.lower()}-capabilities",
                "/api/v1/me/capabilities",
            ).body_json,
            f"{role_code} capability response",
        )
        capabilities = normalized_capabilities(capabilities_view, role_code)
        menu = normalized_menu(capabilities_view, role_code)
        principals.append(
            {
                "roleCode": role_code,
                "roleId": role["roleId"],
                "roleName": role["roleName"],
                "accountId": require_string(
                    account.get("accountId"), f"{role_code} accountId"
                ),
                "username": secrets["username"],
                "password": secrets["password"],
                "capabilities": capabilities,
                "menu": menu,
                "created": created,
                "repaired": repaired,
                "session": role_session,
            }
        )
    if tuple(principal["roleCode"] for principal in principals) != ROLE_CODES:
        fail("runtime role order/set differs from the fixed three")
    return principals


def scrub_dynamic_response(value: Any) -> Any:
    if isinstance(value, list):
        return [scrub_dynamic_response(item) for item in value]
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, item in sorted(value.items()):
            if normalized_casefold(str(key)).replace("_", "") in DYNAMIC_RESPONSE_KEYS:
                result[key] = "[DYNAMIC]"
            else:
                result[key] = scrub_dynamic_response(item)
        return result
    return value


def nonfeature_contract_sha256(response: HttpResponse) -> str:
    if response.body_json != "":
        body = scrub_dynamic_response(response.body_json)
        body_bytes = canonical_json_bytes(body)
    else:
        body_bytes = response.body_text.encode("utf-8")
    return sha256_bytes(
        str(response.status).encode("ascii") + b"\n" + body_bytes
    )


def safe_nonfeature_body(
    response: HttpResponse, role_code: str
) -> dict[str, Any]:
    body = require_object(
        response.body_json, f"{role_code} negative API response"
    )
    if set(body) != {"code", "message", "correlationId", "retryable"}:
        fail(f"{role_code} negative API response schema is not closed")
    code = require_string(body.get("code"), "negative API response code")
    if code not in {"RESOURCE_NOT_AVAILABLE", "RESOURCE_NOT_FOUND"}:
        fail(f"{role_code} negative API response code is not a non-feature")
    message = require_string(
        body.get("message"), "negative API response message"
    )
    correlation = require_string(
        body.get("correlationId"), "negative API response correlation ID"
    )
    if body.get("retryable") is not False:
        fail(f"{role_code} negative API response must not be retryable")
    header_correlation = exact_single_header_value(
        response.headers,
        "X-Correlation-ID",
        f"{role_code} negative API response",
    )
    if header_correlation != correlation:
        fail(
            f"{role_code} negative API response correlation binding is invalid"
        )
    return {
        "code": code,
        "messageSha256": sha256_text(message),
        "correlationIdSha256": sha256_text(correlation),
        "retryable": False,
    }


def api_negative_probe(
    session: ApiSession, role_code: str, target: str
) -> dict[str, Any]:
    response = session.request(
        f"{role_code.lower()}-negative-api-probe",
        target,
        expected_statuses=(404,),
    )
    safe_body = safe_nonfeature_body(response, role_code)
    return {
        "probeKind": "api-non-feature",
        "method": "GET",
        "requestTarget": target,
        "status": response.status,
        "headers": response_headers_document(response.headers),
        "body": safe_body,
        "bodySha256": sha256_text(response.body_text),
        "correlationIdPresent": True,
        "nonFeatureContractSha256": nonfeature_contract_sha256(response),
        "verdict": "PASS",
    }


def unwrap_agent_browser(value: Any) -> Any:
    if isinstance(value, dict) and "success" in value:
        if value.get("success") is not True:
            fail("agent-browser command returned a failure envelope")
        return value.get("data", {})
    return value


class AgentBrowserRunner:
    def __init__(
        self,
        *,
        binary: Path,
        chrome: Path,
        namespace: str,
        allowed_domains: Sequence[str],
    ):
        if not binary.is_absolute() or not chrome.is_absolute():
            fail("browser executable paths must be absolute")
        try:
            binary_target = binary.resolve(strict=True)
        except OSError:
            fail("agent-browser is unavailable")
        if not binary_target.is_file() or not os.access(binary_target, os.X_OK):
            fail("agent-browser must resolve to a regular executable")
        self.binary = binary_target
        requested_chrome = assert_regular_file_no_symlink(chrome, "Chrome")
        if not os.access(requested_chrome, os.X_OK):
            fail("Chrome must be executable")
        self.chrome = requested_chrome
        self.namespace = namespace
        self.allowed_domains = ",".join(dict.fromkeys(allowed_domains))

    def environment(self) -> dict[str, str]:
        return safe_subprocess_environment(
            {
                "AGENT_BROWSER_ALLOWED_DOMAINS": self.allowed_domains,
                "AGENT_BROWSER_CONTENT_BOUNDARIES": "false",
                "AGENT_BROWSER_HIDE_SCROLLBARS": "true",
            }
        )

    def _run(
        self,
        session: str,
        arguments: Sequence[str],
        *,
        sensitive: bool = False,
    ) -> Any:
        command = (
            str(self.binary),
            "--session",
            session,
            "--namespace",
            self.namespace,
            "--executable-path",
            str(self.chrome),
            *arguments,
            "--json",
        )
        try:
            result = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=True,
                timeout=SUBPROCESS_TIMEOUT_SECONDS,
                env=self.environment(),
            )
        except (OSError, subprocess.TimeoutExpired):
            fail(f"agent-browser {arguments[0]} could not execute")
        if result.returncode != 0:
            fail(f"agent-browser {arguments[0]} failed")
        if sensitive:
            return True
        parsed = strict_json_loads(
            result.stdout, f"agent-browser {arguments[0]} response"
        )
        return unwrap_agent_browser(parsed)

    def command(self, session: str, arguments: Sequence[str]) -> Any:
        return self._run(session, arguments)

    def sensitive_command(
        self, session: str, arguments: Sequence[str]
    ) -> None:
        self._run(session, arguments, sensitive=True)

    def evaluate(self, session: str, expression: str) -> Any:
        value = self.command(session, ("eval", expression))
        if isinstance(value, dict) and "result" in value:
            return value["result"]
        return value

    def try_close(self, session: str) -> None:
        try:
            self.command(session, ("close",))
        except ZeroDiscoverabilityError:
            pass

    def version(self) -> str:
        try:
            result = subprocess.run(
                (str(self.binary), "--version"),
                check=False,
                capture_output=True,
                text=True,
                timeout=10,
                env=self.environment(),
            )
        except (OSError, subprocess.TimeoutExpired):
            fail("agent-browser --version could not execute")
        version = result.stdout.strip()
        if result.returncode != 0 or not re.fullmatch(
            r"agent-browser \d+\.\d+\.\d+", version
        ):
            fail("agent-browser version output is invalid")
        return version


def browser_state_expression(target: str) -> str:
    target_json = json.dumps(target, ensure_ascii=False)
    return f"""(() => {{
      const target = {target_json};
      const visible = (element) => {{
        if (!(element instanceof HTMLElement)) return false;
        const style = getComputedStyle(element);
        return style.display !== 'none' && style.visibility !== 'hidden'
          && element.getClientRects().length > 0;
      }};
      const text = (element) => (element?.textContent ?? '')
        .replace(/\\s+/g, ' ').trim();
      const unique = (items) => [...new Set(items.filter(Boolean))];
      return {{
        requestTargetRetained: location.pathname + location.search === target,
        heading: text([...document.querySelectorAll('h1')].find(visible)),
        menuLabels: unique([...document.querySelectorAll('.ant-menu-title-content')]
          .filter(visible).map(text)),
        stateTokens: unique([...document.querySelectorAll('.async-state[data-state]')]
          .filter(visible).map((element) => element.dataset.state)),
        buttonTexts: unique([...document.querySelectorAll('button,[role="button"]')]
          .filter(visible).map(text)),
        environmentText: text(document.querySelector('.app-environment')),
        bodyText: text(document.body),
        documentTitle: document.title,
        mainPresent: document.querySelector('#main-content') !== null
      }};
    }})()"""


def browser_login_state_expression() -> str:
    return """(() => {
      const text = (element) => (element?.textContent ?? '')
        .replace(/\\s+/g, ' ').trim();
      return {
        leftLogin: location.pathname !== '/login',
        environmentText: text(document.querySelector('.app-environment')),
        bodyText: text(document.body)
      };
    })()"""


def browser_nonfeature_ui_contract(
    state: Mapping[str, Any], role_code: str, label: str
) -> dict[str, Any]:
    expected_keys = {
        "requestTargetRetained",
        "heading",
        "menuLabels",
        "stateTokens",
        "buttonTexts",
        "environmentText",
        "bodyText",
        "documentTitle",
        "mainPresent",
    }
    if set(state) != expected_keys:
        fail(f"{role_code} {label} browser state schema is not closed")
    if state.get("requestTargetRetained") is not True:
        fail(f"{role_code} {label} route did not retain its request target")
    tokens = state.get("stateTokens")
    if tokens != ["404"]:
        fail(f"{role_code} {label} route is not the exact real 404 state")
    if state.get("mainPresent") is not True:
        fail(f"{role_code} {label} route lacks the main landmark")
    heading = require_string(
        state.get("heading"), f"{role_code} {label} heading"
    )
    body_text = require_string(
        state.get("bodyText"), f"{role_code} {label} body"
    )
    environment_text = require_string(
        state.get("environmentText"), f"{role_code} {label} environment"
    )
    if "演示" in environment_text:
        fail(f"{role_code} {label} route is not in normal runtime mode")

    def string_list(key: str) -> list[str]:
        values = require_array(
            state.get(key), f"{role_code} {label} {key}"
        )
        return [
            require_string(value, f"{role_code} {label} {key} value")
            for value in values
        ]

    contract = {
        "heading": heading,
        "menuLabels": string_list("menuLabels"),
        "stateTokens": ["404"],
        "buttonTexts": string_list("buttonTexts"),
        "environmentText": environment_text,
        "bodyText": body_text,
        "documentTitle": require_string(
            state.get("documentTitle"),
            f"{role_code} {label} document title",
        ),
        "mainPresent": True,
    }
    require_no_deny_match(
        canonical_json_bytes(contract).decode("utf-8"),
        f"runtime/{role_code}/{label}-ui-contract",
    )
    return contract


def normalize_browser_requests(
    value: Any, *, allowed_origins: Sequence[str]
) -> list[dict[str, Any]]:
    if isinstance(value, list):
        requests = value
    elif isinstance(value, dict):
        requests = (
            value.get("requests")
            or (
                value.get("data", {}).get("requests")
                if isinstance(value.get("data"), dict)
                else []
            )
            or []
        )
    else:
        requests = []
    if not isinstance(requests, list):
        fail("agent-browser network result is malformed")
    normalized: list[dict[str, Any]] = []
    for raw in requests:
        if not isinstance(raw, dict):
            fail("agent-browser network row is malformed")
        nested_request = (
            raw.get("request") if isinstance(raw.get("request"), dict) else {}
        )
        response = (
            raw.get("response") if isinstance(raw.get("response"), dict) else {}
        )
        url_value = raw.get("url", nested_request.get("url", ""))
        try:
            parsed = urllib.parse.urlsplit(str(url_value))
            require_no_deny_match(
                str(url_value), "browser-network/request-url"
            )
            if (
                parsed.scheme != "http"
                or parsed.hostname is None
                or parsed.port is None
                or parsed.username
                or parsed.password
            ):
                fail("browser network request has an invalid absolute origin")
            request_origin = (
                f"{parsed.scheme}://{parsed.hostname}:{parsed.port}"
            )
            if request_origin not in set(allowed_origins):
                fail("browser network request escaped the bound origins")
            path = parsed.path
            query = parsed.query
        except ValueError:
            fail("browser network request URL is malformed")
        status_value = raw.get("status", response.get("status", -1))
        try:
            status = int(status_value)
        except (TypeError, ValueError):
            status = -1
        response_headers = raw.get(
            "responseHeaders", response.get("headers", {})
        )
        headers: list[dict[str, Any]] = []
        if isinstance(response_headers, dict):
            for name, header_value_item in sorted(response_headers.items()):
                safe_name = str(name).lower()
                safe_value = str(header_value_item)
                require_no_deny_match(
                    safe_name, "browser-network/response-header-name"
                )
                require_no_deny_match(
                    safe_value, "browser-network/response-header-value"
                )
                headers.append(
                    safe_response_header_record(safe_name, safe_value)
                )
        elif isinstance(response_headers, list):
            for item in response_headers:
                if not isinstance(item, dict):
                    continue
                safe_name = str(item.get("name", "")).lower()
                safe_value = str(item.get("value", ""))
                require_no_deny_match(
                    safe_name, "browser-network/response-header-name"
                )
                require_no_deny_match(
                    safe_value, "browser-network/response-header-value"
                )
                headers.append(
                    safe_response_header_record(safe_name, safe_value)
                )
            headers.sort(
                key=lambda item: (
                    str(item["name"]),
                    str(item.get("value", item.get("valuePresent", ""))),
                )
            )
        normalized.append(
            {
                "method": str(raw.get("method", nested_request.get("method", ""))),
                "requestOrigin": request_origin,
                "requestPathPresent": bool(path),
                "queryPresent": bool(query),
                "status": status,
                "resourceType": str(
                    raw.get("resourceType", raw.get("type", ""))
                ),
                "responseHeaders": headers,
            }
        )
    return normalized


def browser_proxy_binding_expression(correlation_id: str) -> str:
    correlation_json = json.dumps(correlation_id)
    return f"""(async () => {{
      const response = await fetch('/api/v1/me/capabilities', {{
        method: 'GET',
        credentials: 'same-origin',
        headers: {{
          'Accept': 'application/json',
          'X-Correlation-ID': {correlation_json}
        }}
      }});
      return {{
        responseUrl: response.url,
        status: response.status,
        headers: [...response.headers.entries()],
        bodyText: await response.text()
      }};
    }})()"""


def validate_browser_proxy_headers(
    raw_headers: Sequence[Any],
    correlation: str,
    role_code: str,
) -> None:
    correlation_values: list[str] = []
    for index, raw_header in enumerate(raw_headers):
        header = require_array(
            raw_header, f"frontend proxy response header[{index}]"
        )
        if len(header) != 2:
            fail("frontend proxy response header pair is malformed")
        name = require_string(header[0], "frontend proxy header name")
        value = require_string(header[1], "frontend proxy header value")
        require_no_deny_match(name, "frontend-proxy/header-name")
        require_no_deny_match(value, "frontend-proxy/header-value")
        if name.lower() == "x-correlation-id":
            correlation_values.append(value)
    if correlation_values != [correlation]:
        fail(f"{role_code} frontend proxy correlation binding failed")


def verify_frontend_backend_proxy_binding(
    *,
    browser: AgentBrowserRunner,
    session_name: str,
    principal: Mapping[str, Any],
    frontend_origin: str,
    backend_origin: str,
    context: Mapping[str, Any],
) -> dict[str, Any]:
    role_code = str(principal["roleCode"])
    correlation = (
        "w3-zero-"
        + sha256_text(f"{context['runId']}|{role_code}|proxy")[:32]
    )
    direct = principal["session"].request(
        f"{role_code.lower()}-direct-proxy-binding",
        "/api/v1/me/capabilities",
        headers={"X-Correlation-ID": correlation},
    )
    if (
        exact_single_header_value(
            direct.headers,
            "X-Correlation-ID",
            f"{role_code} direct backend response",
        )
        != correlation
    ):
        fail(f"{role_code} direct backend correlation binding failed")
    browser_result = browser.evaluate(
        session_name, browser_proxy_binding_expression(correlation)
    )
    if not isinstance(browser_result, dict) or set(browser_result) != {
        "responseUrl",
        "status",
        "headers",
        "bodyText",
    }:
        fail(f"{role_code} frontend proxy binding result is malformed")
    if browser_result.get("status") != 200:
        fail(f"{role_code} frontend proxy binding did not return HTTP 200")
    response_url = require_string(
        browser_result.get("responseUrl"), "frontend proxy response URL"
    )
    try:
        parsed = urllib.parse.urlsplit(response_url)
    except ValueError:
        fail(f"{role_code} frontend proxy response URL is invalid")
    response_origin = (
        f"{parsed.scheme}://{parsed.hostname}:{parsed.port}"
        if parsed.hostname and parsed.port
        else ""
    )
    if (
        response_origin != frontend_origin
        or parsed.path != "/api/v1/me/capabilities"
        or parsed.query
        or parsed.fragment
    ):
        fail(f"{role_code} frontend proxy response origin/path is invalid")
    raw_headers = require_array(
        browser_result.get("headers"), "frontend proxy response headers"
    )
    validate_browser_proxy_headers(raw_headers, correlation, role_code)
    body_text = require_string(
        browser_result.get("bodyText"), "frontend proxy response body"
    )
    require_no_deny_match(body_text, "frontend-proxy/response-body")
    if body_text.encode("utf-8") != direct.body_text.encode("utf-8"):
        fail(
            f"{role_code} frontend proxy body differs from the bound backend"
        )
    return {
        "bindingKind": "same-correlation-byte-equal-capability-response",
        "frontendOrigin": frontend_origin,
        "backendOrigin": backend_origin,
        "requestPath": "/api/v1/me/capabilities",
        "status": 200,
        "correlationIdSha256": sha256_text(correlation),
        "bodySha256": sha256_text(body_text),
        "verdict": "PASS",
    }


def frontend_negative_probes(
    *,
    browser: AgentBrowserRunner,
    frontend_origin: str,
    backend_origin: str,
    principal: Mapping[str, Any],
    context: Mapping[str, Any],
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    role_code = principal["roleCode"]
    session_name = (
        f"w3-zero-{context['runId']}-{role_code.lower()}"
    )
    try:
        browser.command(session_name, ("open", f"{frontend_origin}/login"))
        browser.command(
            session_name, ("wait", 'input[autocomplete="username"]')
        )
        browser.sensitive_command(
            session_name,
            (
                "fill",
                'input[autocomplete="username"]',
                principal["username"],
            ),
        )
        browser.sensitive_command(
            session_name,
            (
                "fill",
                'input[autocomplete="current-password"]',
                principal["password"],
            ),
        )
        browser.command(session_name, ("click", 'button[type="submit"]'))
        browser.command(session_name, ("wait", "800"))
        login_state = browser.evaluate(
            session_name, browser_login_state_expression()
        )
        if not isinstance(login_state, dict) or login_state.get("leftLogin") is not True:
            fail(f"{role_code} browser login did not leave the login page")
        environment_text = str(login_state.get("environmentText", ""))
        if not environment_text or "演示" in environment_text:
            fail(f"{role_code} browser is not in normal runtime mode")
        require_no_deny_match(
            str(login_state.get("bodyText", "")),
            f"runtime/{role_code}/login-body",
        )
        proxy_binding = verify_frontend_backend_proxy_binding(
            browser=browser,
            session_name=session_name,
            principal=principal,
            frontend_origin=frontend_origin,
            backend_origin=backend_origin,
            context=context,
        )

        control_target = (
            "/__w3_non_feature_control_"
            + sha256_text(f"{context['runId']}|{role_code}")[:16]
        )
        browser.command(
            session_name, ("open", frontend_origin + control_target)
        )
        browser.command(session_name, ("wait", "700"))
        raw_control_state = browser.evaluate(
            session_name, browser_state_expression(control_target)
        )
        if not isinstance(raw_control_state, dict):
            fail(f"{role_code} frontend control returned malformed state")
        control_contract = browser_nonfeature_ui_contract(
            raw_control_state, role_code, "unknown-control"
        )
        control_sha256 = sha256_bytes(
            canonical_json_bytes(control_contract)
        )

        probes: list[dict[str, Any]] = []
        for target in FRONTEND_NEGATIVE_TARGETS:
            browser.command(session_name, ("network", "requests", "--clear"))
            browser.command(session_name, ("open", frontend_origin + target))
            browser.command(session_name, ("wait", "700"))
            state = browser.evaluate(
                session_name, browser_state_expression(target)
            )
            if not isinstance(state, dict):
                fail(f"{role_code} frontend probe returned malformed state")
            target_contract = browser_nonfeature_ui_contract(
                state, role_code, "negative-target"
            )
            if (
                canonical_json_bytes(target_contract)
                != canonical_json_bytes(control_contract)
            ):
                fail(
                    f"{role_code} negative route differs from the independent "
                    "unknown-route UI contract"
                )
            requests = normalize_browser_requests(
                browser.command(
                    session_name,
                    ("network", "requests", "--type", "xhr,fetch"),
                ),
                allowed_origins=(frontend_origin, backend_origin),
            )
            probe = {
                "probeKind": "frontend-real-404",
                "requestTarget": target,
                "requestTargetRetained": True,
                "controlRequestTarget": control_target,
                "controlUiContractSha256": control_sha256,
                "uiContractSha256": sha256_bytes(
                    canonical_json_bytes(target_contract)
                ),
                "stateTokens": target_contract["stateTokens"],
                "heading": target_contract["heading"],
                "menuLabels": target_contract["menuLabels"],
                "buttonTexts": target_contract["buttonTexts"],
                "environmentText": target_contract["environmentText"],
                "bodyTextSha256": sha256_text(
                    target_contract["bodyText"]
                ),
                "documentTitle": target_contract["documentTitle"],
                "mainPresent": True,
                "networkRequests": requests,
                "verdict": "PASS",
            }
            probes.append(probe)
        return proxy_binding, probes
    finally:
        browser.try_close(session_name)


def is_frontend_test_path(path: Path, source_root: Path) -> bool:
    relative = path.relative_to(source_root)
    if any(part in {"test", "tests", "__tests__"} for part in relative.parts):
        return True
    return bool(re.search(r"\.(?:test|spec)\.[^.]+$", path.name))


def walk_regular_files(root: Path, label: str) -> list[Path]:
    if not root.is_dir() or root.is_symlink():
        fail(f"required scan root is missing or symbolic: {label}")
    resolved_root = root.resolve(strict=True)
    if not is_within(resolved_root, REPOSITORY_ROOT.resolve(strict=True)):
        fail(f"required scan root escapes the repository: {label}")
    files: list[Path] = []
    for directory, directory_names, file_names in os.walk(
        root, followlinks=False
    ):
        directory_path = Path(directory)
        kept: list[str] = []
        for name in sorted(directory_names, key=lambda value: value.encode()):
            child = directory_path / name
            if child.is_symlink():
                fail(f"scan root traverses a symbolic directory: {child}")
            kept.append(name)
        directory_names[:] = kept
        for name in sorted(file_names, key=lambda value: value.encode()):
            candidate = directory_path / name
            if candidate.is_symlink() or not candidate.is_file():
                fail(f"scan root contains a non-regular file: {candidate}")
            resolved = candidate.resolve(strict=True)
            if not is_within(resolved, resolved_root):
                fail(f"scan file escapes its required root: {candidate}")
            files.append(candidate)
    return sorted(
        files,
        key=lambda path: path.relative_to(REPOSITORY_ROOT)
        .as_posix()
        .encode("utf-8"),
    )


def collect_product_scope_files() -> dict[str, list[Path]]:
    frontend_source_root = REPOSITORY_ROOT / "frontend/src"
    frontend_source_all = walk_regular_files(
        frontend_source_root, "frontend source closure"
    )
    frontend_public_root = REPOSITORY_ROOT / "frontend/public"
    if frontend_public_root.exists() or frontend_public_root.is_symlink():
        frontend_public_all = walk_regular_files(
            frontend_public_root, "frontend public assets"
        )
    else:
        # Git does not preserve empty directories. An absent public root is
        # therefore the reproducible representation of the fixed empty public
        # manifest; any future public file must still be declared explicitly.
        frontend_public_all = []
    frontend_index = REPOSITORY_ROOT / "frontend/index.html"
    assert_regular_file_no_symlink(frontend_index, "frontend index")
    observed_frontend = {
        path.relative_to(REPOSITORY_ROOT).as_posix()
        for path in (*frontend_source_all, *frontend_public_all, frontend_index)
    }
    expected_frontend = {
        *FRONTEND_PRODUCT_MANIFEST,
        *FRONTEND_TEST_EXCLUSION_MANIFEST,
    }
    if (
        len(FRONTEND_PRODUCT_MANIFEST) != 79
        or len(set(FRONTEND_PRODUCT_MANIFEST)) != 79
        or len(set(FRONTEND_TEST_EXCLUSION_MANIFEST))
        != len(FRONTEND_TEST_EXCLUSION_MANIFEST)
        or observed_frontend != expected_frontend
    ):
        fail(
            "frontend required-root closure differs from the fixed "
            "79-file product manifest"
        )
    frontend_source = [
        REPOSITORY_ROOT / relative for relative in FRONTEND_PRODUCT_MANIFEST
    ]
    for path in frontend_source:
        assert_regular_file_no_symlink(path, "fixed frontend product file")

    prod_dist = walk_regular_files(
        REPOSITORY_ROOT / "frontend/dist/prod", "production dist"
    )
    demo_dist = walk_regular_files(
        REPOSITORY_ROOT / "frontend/dist/demo", "demo dist"
    )
    openapi = REPOSITORY_ROOT / "api/openapi.yaml"
    assert_regular_file_no_symlink(openapi, "OpenAPI contract")

    java_root = REPOSITORY_ROOT / "backend/src/main/java"
    all_java_root_files = walk_regular_files(
        java_root, "backend Java source closure"
    )
    discovered_rest_roots: set[str] = set()
    for path in all_java_root_files:
        parts = path.relative_to(REPOSITORY_ROOT).parts
        for index in range(len(parts) - 1):
            if parts[index : index + 2] in {
                ("interfaces", "rest"),
                ("shared", "web"),
            }:
                discovered_rest_roots.add(
                    Path(*parts[: index + 2]).as_posix()
                )
    if discovered_rest_roots != set(BACKEND_REST_DTO_ROOTS):
        fail("backend REST/DTO root closure differs from the fixed roots")
    backend_interfaces: list[Path] = []
    for relative_root in BACKEND_REST_DTO_ROOTS:
        for path in walk_regular_files(
            REPOSITORY_ROOT / relative_root,
            f"fixed backend REST/DTO root {relative_root}",
        ):
            if path.suffix != ".java":
                fail("backend REST/DTO root contains a non-Java file")
            backend_interfaces.append(path)
    if not frontend_source:
        fail("frontend product source scope is empty")
    if not prod_dist or not demo_dist:
        fail("production and demo dist scopes must both be non-empty")
    if not backend_interfaces:
        fail("backend REST/interface/DTO scope is empty")
    return {
        "frontend-product-source": sorted(frontend_source),
        "frontend-prod-dist": prod_dist,
        "frontend-demo-dist": demo_dist,
        "openapi": [openapi],
        "backend-rest-interface-dto": backend_interfaces,
    }


def collect_frontend_build_inputs(
    scopes: Mapping[str, Sequence[Path]]
) -> list[Path]:
    inputs = list(scopes["frontend-product-source"])
    for name in (
        "package.json",
        "package-lock.json",
        "tsconfig.json",
        "tsconfig.app.json",
        "tsconfig.node.json",
        "vite.config.ts",
    ):
        candidate = REPOSITORY_ROOT / "frontend" / name
        if candidate.exists():
            assert_regular_file_no_symlink(
                candidate, f"frontend build input {name}"
            )
            inputs.append(candidate)
    unique = {path.resolve(strict=True): path for path in inputs}
    return sorted(
        unique.values(),
        key=lambda path: path.relative_to(REPOSITORY_ROOT)
        .as_posix()
        .encode("utf-8"),
    )


def tree_inventory(
    files: Sequence[Path], *, root: Path, include_records: bool = True
) -> dict[str, Any]:
    records: list[dict[str, Any]] = []
    framed: list[bytes] = []
    mtimes: list[int] = []
    for path in files:
        resolved = assert_regular_file_no_symlink(path, "inventory file")
        if not is_within(resolved, root.resolve(strict=True)):
            fail("inventory file escapes its root")
        relative = unicodedata.normalize(
            "NFC", path.relative_to(root).as_posix()
        )
        contents = path.read_bytes()
        relative_bytes = relative.encode("utf-8")
        digest = sha256_bytes(contents)
        framed.append(
            str(len(relative_bytes)).encode("ascii")
            + b":"
            + relative_bytes
            + b"|"
            + str(len(contents)).encode("ascii")
            + b"|"
            + digest.encode("ascii")
        )
        metadata = path.stat()
        mtimes.append(metadata.st_mtime_ns)
        records.append(
            {
                "path": relative,
                "sizeBytes": len(contents),
                "sha256": digest,
                "mtimeEpochNs": metadata.st_mtime_ns,
            }
        )
    if not files:
        return {
            "fileCount": 0,
            "treeSha256": sha256_bytes(b""),
            "minimumMtimeEpochNs": 0,
            "maximumMtimeEpochNs": 0,
            "files": [],
        }
    return {
        "fileCount": len(records),
        "treeSha256": sha256_bytes(b"".join(framed)),
        "minimumMtimeEpochNs": min(mtimes),
        "maximumMtimeEpochNs": max(mtimes),
        "files": records if include_records else [],
    }


def stable_tree_snapshot(root: Path, label: str) -> dict[str, Any]:
    files = walk_regular_files(root, label)
    if not files:
        fail(f"{label} is empty")
    records: list[dict[str, Any]] = []
    framed: list[bytes] = []
    for path in files:
        contents, metadata, resolved = stable_file_bytes(
            path, f"{label} file"
        )
        relative = unicodedata.normalize(
            "NFC", path.relative_to(root).as_posix()
        )
        record = {
            "path": relative,
            "realpath": resolved.as_posix(),
            "device": metadata.st_dev,
            "inode": metadata.st_ino,
            "mode": stat.S_IMODE(metadata.st_mode),
            "sizeBytes": len(contents),
            "mtimeEpochNs": metadata.st_mtime_ns,
            "ctimeEpochNs": metadata.st_ctime_ns,
            "sha256": sha256_bytes(contents),
        }
        records.append(record)
        framed.append(canonical_json_bytes(record))
    return {
        "rootRealpath": root.resolve(strict=True).as_posix(),
        "fileCount": len(records),
        "treeSha256": sha256_bytes(b"".join(framed)),
        "minimumMtimeEpochNs": min(
            record["mtimeEpochNs"] for record in records
        ),
        "maximumMtimeEpochNs": max(
            record["mtimeEpochNs"] for record in records
        ),
        "minimumCtimeEpochNs": min(
            record["ctimeEpochNs"] for record in records
        ),
        "maximumCtimeEpochNs": max(
            record["ctimeEpochNs"] for record in records
        ),
        "records": records,
    }


def assert_tree_snapshot_predates_process(
    snapshot: Mapping[str, Any],
    process_started_ns: int,
    label: str,
) -> None:
    records = require_array(snapshot.get("records"), f"{label} records")
    if any(
        not isinstance(record, dict)
        or type(record.get("mtimeEpochNs")) is not int
        or type(record.get("ctimeEpochNs")) is not int
        for record in records
    ):
        fail(f"{label} metadata is malformed")
    if any(
        record["mtimeEpochNs"] > process_started_ns
        or record["ctimeEpochNs"] > process_started_ns
        for record in records
    ):
        fail(f"{label} contains a file changed after process start")


BUILD_FILE_RECEIPT_KEYS = {
    "path",
    "sizeBytes",
    "mode",
    "device",
    "inode",
    "mtimeEpochNs",
    "ctimeEpochNs",
    "sha256",
}


def validate_build_file_receipt(
    value: Any,
    label: str,
    *,
    absolute_path: bool = False,
) -> dict[str, Any]:
    record = require_exact_keys(value, BUILD_FILE_RECEIPT_KEYS, label)
    path_value = record.get("path")
    if not isinstance(path_value, str) or not path_value:
        fail(f"{label} path is invalid")
    normalized_path = unicodedata.normalize("NFC", path_value)
    if normalized_path != path_value or "\x00" in path_value:
        fail(f"{label} path is not canonical NFC text")
    candidate = Path(path_value)
    if absolute_path:
        if (
            not candidate.is_absolute()
            or candidate.as_posix() != path_value
        ):
            fail(f"{label} path must be absolute")
    elif (
        candidate.is_absolute()
        or candidate.as_posix() != path_value
        or "\\" in path_value
        or any(part in ("", ".", "..") for part in candidate.parts)
    ):
        fail(f"{label} path is not a canonical relative path")
    for key in (
        "sizeBytes",
        "mode",
        "device",
        "inode",
        "mtimeEpochNs",
        "ctimeEpochNs",
    ):
        if type(record.get(key)) is not int or record[key] < 0:
            fail(f"{label} {key} is invalid")
    if (
        record["mode"] > 0o7777
        or record["inode"] <= 0
        or record["mtimeEpochNs"] <= 0
        or record["ctimeEpochNs"] <= 0
    ):
        fail(f"{label} metadata is invalid")
    if not isinstance(record.get("sha256"), str) or not SHA256_PATTERN.fullmatch(
        record["sha256"]
    ):
        fail(f"{label} SHA256 is invalid")
    return record


def stable_build_file_receipt(
    path: Path,
    *,
    receipt_path: str,
    label: str,
) -> tuple[dict[str, Any], bytes]:
    contents, metadata, _resolved = stable_file_bytes(path, label)
    return (
        {
            "path": receipt_path,
            "sizeBytes": len(contents),
            "mode": stat.S_IMODE(metadata.st_mode),
            "device": metadata.st_dev,
            "inode": metadata.st_ino,
            "mtimeEpochNs": metadata.st_mtime_ns,
            "ctimeEpochNs": metadata.st_ctime_ns,
            "sha256": sha256_bytes(contents),
        },
        contents,
    )


def validate_dist_receipt_records(
    snapshot: Mapping[str, Any],
    inventory_files: Sequence[Mapping[str, Any]],
    *,
    freshness_floor_ns: int,
    build_started_ns: int,
    build_completed_ns: int,
    label: str,
) -> None:
    records = require_array(snapshot.get("records"), f"{label} snapshot")
    if len(records) != len(inventory_files):
        fail(f"{label} receipt file count differs from current dist")
    if not records or build_started_ns <= freshness_floor_ns:
        fail(f"{label} build receipt predates the frozen run/source")
    if build_completed_ns < build_started_ns:
        fail(f"{label} build receipt time window is invalid")
    for index, (record, raw_inventory) in enumerate(
        zip(records, inventory_files, strict=True)
    ):
        inventory = require_exact_keys(
            raw_inventory,
            BUILD_FILE_RECEIPT_KEYS,
            f"{label} inventory file[{index}]",
        )
        expected = {
            "path": record["path"],
            "sizeBytes": record["sizeBytes"],
            "mode": record["mode"],
            "device": record["device"],
            "inode": record["inode"],
            "mtimeEpochNs": record["mtimeEpochNs"],
            "ctimeEpochNs": record["ctimeEpochNs"],
            "sha256": record["sha256"],
        }
        validate_build_file_receipt(
            inventory, f"{label} inventory file[{index}]"
        )
        if inventory != expected:
            fail(f"{label} receipt differs from current dist bytes/metadata")
        if (
            record["mtimeEpochNs"] <= freshness_floor_ns
            or record["ctimeEpochNs"] <= freshness_floor_ns
            or record["mtimeEpochNs"] < build_started_ns
            or record["ctimeEpochNs"] < build_started_ns
            or record["mtimeEpochNs"] > build_completed_ns
            or record["ctimeEpochNs"] > build_completed_ns
        ):
            fail(f"{label} dist contains stale or out-of-window metadata")


def strict_base64_bytes(
    value: Any,
    label: str,
    *,
    maximum_bytes: int,
) -> bytes:
    if (
        not isinstance(value, str)
        or len(value) > ((maximum_bytes + 2) // 3) * 4 + 4
    ):
        fail(f"{label} base64 payload is invalid or too large")
    try:
        decoded = base64.b64decode(value.encode("ascii"), validate=True)
    except (UnicodeEncodeError, ValueError):
        fail(f"{label} is not strict base64")
    if len(decoded) > maximum_bytes:
        fail(f"{label} decoded payload is too large")
    return decoded


def bounded_zlib_decompress(
    compressed: bytes,
    *,
    expected_size: int,
    label: str,
) -> bytes:
    if expected_size < 0 or expected_size > MAX_BUILD_ARCHIVE_BYTES:
        fail(f"{label} declared uncompressed size is invalid")
    decompressor = zlib.decompressobj()
    pieces: list[bytes] = []
    produced = 0
    pending = compressed
    try:
        while pending:
            chunk, pending = pending[:64 * 1024], pending[64 * 1024 :]
            allowance = expected_size + 1 - produced
            if allowance <= 0:
                fail(f"{label} expands beyond its declared size")
            output = decompressor.decompress(chunk, allowance)
            pieces.append(output)
            produced += len(output)
            if decompressor.unconsumed_tail:
                pending = decompressor.unconsumed_tail + pending
        allowance = expected_size + 1 - produced
        if allowance <= 0 and not decompressor.eof:
            fail(f"{label} expands beyond its declared size")
        tail = decompressor.flush(max(1, allowance))
    except zlib.error:
        fail(f"{label} is not a valid zlib stream")
    pieces.append(tail)
    raw = b"".join(pieces)
    if (
        len(raw) != expected_size
        or not decompressor.eof
        or decompressor.unused_data
        or decompressor.unconsumed_tail
    ):
        fail(f"{label} zlib framing/size is invalid")
    return raw


def validate_dist_content_archive(
    value: Any,
    *,
    inventory_files: Sequence[Mapping[str, Any]],
    snapshot: Mapping[str, Any],
    label: str,
) -> dict[str, Any]:
    envelope = require_exact_keys(
        value,
        {
            "format",
            "memberCount",
            "uncompressedSizeBytes",
            "compressedSizeBytes",
            "uncompressedSha256",
            "compressedSha256",
            "payloadBase64",
        },
        f"{label} archive envelope",
    )
    if (
        envelope["format"]
        != "wave3-dist-content-archive-v1+canonical-json+zlib+base64"
    ):
        fail(f"{label} archive format is invalid")
    for key in (
        "memberCount",
        "uncompressedSizeBytes",
        "compressedSizeBytes",
    ):
        if type(envelope.get(key)) is not int or envelope[key] < 0:
            fail(f"{label} archive {key} is invalid")
    for key in ("uncompressedSha256", "compressedSha256"):
        if (
            not isinstance(envelope.get(key), str)
            or not SHA256_PATTERN.fullmatch(envelope[key])
        ):
            fail(f"{label} archive {key} is invalid")
    compressed = strict_base64_bytes(
        envelope["payloadBase64"],
        f"{label} archive payload",
        maximum_bytes=MAX_BUILD_ARCHIVE_BYTES,
    )
    if (
        len(compressed) != envelope["compressedSizeBytes"]
        or sha256_bytes(compressed) != envelope["compressedSha256"]
    ):
        fail(f"{label} archive compressed bytes are not receipt-bound")
    raw = bounded_zlib_decompress(
        compressed,
        expected_size=envelope["uncompressedSizeBytes"],
        label=f"{label} archive",
    )
    if sha256_bytes(raw) != envelope["uncompressedSha256"]:
        fail(f"{label} archive uncompressed hash is invalid")
    try:
        archive_text = raw.decode("utf-8", errors="strict")
    except UnicodeError:
        fail(f"{label} archive is not strict UTF-8 JSON")
    archive_value = strict_json_loads(archive_text, f"{label} archive")
    if compact_canonical_json_bytes(archive_value) != raw:
        fail(f"{label} archive is not canonical JSON")
    archive = require_exact_keys(
        archive_value,
        {"schemaVersion", "format", "members"},
        f"{label} archive",
    )
    if (
        type(archive["schemaVersion"]) is not int
        or archive["schemaVersion"] != 1
        or archive["format"] != "wave3-dist-content-archive-v1"
    ):
        fail(f"{label} archive schema/type is invalid")
    members = require_array(archive["members"], f"{label} archive members")
    current_records = require_array(
        snapshot.get("records"), f"{label} current dist records"
    )
    if (
        envelope["memberCount"] != len(members)
        or len(members) != len(inventory_files)
        or len(members) != len(current_records)
        or not members
    ):
        fail(f"{label} archive member closure is invalid")
    observed_paths: set[str] = set()
    total_member_bytes = 0
    for index, (raw_member, raw_inventory, current) in enumerate(
        zip(members, inventory_files, current_records, strict=True)
    ):
        member = require_exact_keys(
            raw_member,
            {"path", "mode", "sizeBytes", "sha256", "contentBase64"},
            f"{label} archive member[{index}]",
        )
        inventory = validate_build_file_receipt(
            raw_inventory, f"{label} inventory file[{index}]"
        )
        if (
            not isinstance(member.get("path"), str)
            or type(member.get("mode")) is not int
            or member["mode"] < 0
            or member["mode"] > 0o7777
            or type(member.get("sizeBytes")) is not int
            or member["sizeBytes"] < 0
            or member["path"] in observed_paths
            or member["path"] != inventory["path"]
            or member.get("mode") != inventory["mode"]
            or member.get("sizeBytes") != inventory["sizeBytes"]
            or member.get("sha256") != inventory["sha256"]
        ):
            fail(f"{label} archive member/inventory binding is invalid")
        observed_paths.add(member["path"])
        if (
            current.get("path") != member["path"]
            or current.get("mode") != member["mode"]
            or current.get("sizeBytes") != member["sizeBytes"]
            or current.get("sha256") != member["sha256"]
        ):
            fail(f"{label} archive member differs from current dist")
        member_bytes = strict_base64_bytes(
            member["contentBase64"],
            f"{label} archive member[{index}] content",
            maximum_bytes=MAX_BUILD_ARCHIVE_BYTES,
        )
        total_member_bytes += len(member_bytes)
        if (
            total_member_bytes > MAX_BUILD_ARCHIVE_BYTES
            or len(member_bytes) != member["sizeBytes"]
            or sha256_bytes(member_bytes) != member["sha256"]
        ):
            fail(f"{label} archive member content is invalid")
    return {
        "format": envelope["format"],
        "memberCount": len(members),
        "uncompressedSha256": envelope["uncompressedSha256"],
        "compressedSha256": envelope["compressedSha256"],
    }


BUILD_ARTIFACT_DESCRIPTOR_KEYS = {
    "sizeBytes",
    "sha256",
    "device",
    "inode",
    "mode",
    "mtimeEpochNs",
    "ctimeEpochNs",
}


def assert_nofollow_directory_chain(
    boundary: Path,
    path: Path,
    label: str,
) -> os.stat_result:
    boundary_absolute = Path(os.path.abspath(boundary))
    path_absolute = Path(os.path.abspath(path))
    try:
        relative = path_absolute.relative_to(boundary_absolute)
    except ValueError:
        fail(f"{label} escapes its authority root")
    current = boundary_absolute
    final_metadata: os.stat_result | None = None
    for part in (".", *relative.parts):
        if part != ".":
            current /= part
        try:
            metadata = current.lstat()
        except OSError:
            fail(f"{label} path component is unavailable")
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(
            metadata.st_mode
        ):
            fail(f"{label} path component is symbolic or non-directory")
        final_metadata = metadata
    if final_metadata is None:
        fail(f"{label} directory chain is empty")
    return final_metadata


def build_artifact_descriptor(
    contents: bytes, metadata: os.stat_result
) -> dict[str, Any]:
    return {
        "sizeBytes": len(contents),
        "sha256": sha256_bytes(contents),
        "device": metadata.st_dev,
        "inode": metadata.st_ino,
        "mode": stat.S_IMODE(metadata.st_mode),
        "mtimeEpochNs": metadata.st_mtime_ns,
        "ctimeEpochNs": metadata.st_ctime_ns,
    }


def validate_build_artifact_descriptor(
    value: Any, label: str
) -> dict[str, Any]:
    descriptor = require_exact_keys(
        value, BUILD_ARTIFACT_DESCRIPTOR_KEYS, label
    )
    for key in (
        "sizeBytes",
        "device",
        "inode",
        "mode",
        "mtimeEpochNs",
        "ctimeEpochNs",
    ):
        if type(descriptor.get(key)) is not int or descriptor[key] < 0:
            fail(f"{label} {key} is invalid")
    if (
        descriptor["inode"] <= 0
        or descriptor["mode"] != 0o400
        or descriptor["mtimeEpochNs"] <= 0
        or descriptor["ctimeEpochNs"] <= 0
        or not isinstance(descriptor.get("sha256"), str)
        or not SHA256_PATTERN.fullmatch(descriptor["sha256"])
    ):
        fail(f"{label} immutable metadata/hash is invalid")
    return descriptor


def validate_build_bundle_commit(
    context: Mapping[str, Any],
) -> dict[str, Any]:
    run_root = Path(context["runRoot"])
    assert_nofollow_directory_chain(
        REPOSITORY_ROOT / "docs/verification/wave3/runs",
        run_root,
        "build-gates run root",
    )
    marker_path = run_root / BUILD_GATE_COMMIT_FILENAME
    marker_bytes, marker_metadata, _ = stable_file_bytes(
        marker_path, "build-gates commit marker"
    )
    if stat.S_IMODE(marker_metadata.st_mode) != 0o400:
        fail("build-gates commit marker mode must be exactly 0400")
    try:
        marker_text = marker_bytes.decode("utf-8", errors="strict")
    except UnicodeError:
        fail("build-gates commit marker is not strict UTF-8 JSON")
    marker_value = strict_json_loads(
        marker_text, "build-gates commit marker"
    )
    marker = require_exact_keys(
        marker_value,
        {
            "schemaVersion",
            "nodeType",
            "runId",
            "sourceTreeHash",
            "databaseIdentity",
            "gateIds",
            "artifactManifest",
            "artifactManifestSha256",
            "committedAt",
            "verdict",
        },
        "build-gates commit marker",
    )
    assert_no_null_or_forbidden(marker, "build-gates commit marker")
    gate_ids = [evidence_id for _slug, evidence_id, _marker, _files in BUILD_GATE_BUNDLE]
    expected_scalars = {
        "schemaVersion": 1,
        "nodeType": "wave3-build-gates-commit",
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "gateIds": gate_ids,
        "verdict": "PASS",
    }
    if type(marker["schemaVersion"]) is not int:
        fail("build-gates commit marker schemaVersion type mismatch")
    for key, expected in expected_scalars.items():
        if marker.get(key) != expected:
            fail(f"build-gates commit marker {key} mismatch")
    committed_at = parse_utc(
        marker["committedAt"], "build-gates committedAt"
    )
    committed_ns = int(committed_at.timestamp() * 1_000_000_000)
    if (
        committed_ns < context["startedAtEpochNs"]
        or committed_ns > time.time_ns()
        or marker_metadata.st_mtime_ns < committed_ns
        or marker_metadata.st_ctime_ns < committed_ns
    ):
        fail("build-gates commit time is outside its bound window")
    expected_artifacts = {
        f"{slug}/{filename}"
        for slug, _evidence_id, _marker, filenames in BUILD_GATE_BUNDLE
        for filename in filenames
    }
    manifest = require_object(
        marker["artifactManifest"], "build-gates artifact manifest"
    )
    if set(manifest) != expected_artifacts:
        fail("build-gates artifact manifest closure differs")
    if (
        not isinstance(marker["artifactManifestSha256"], str)
        or marker["artifactManifestSha256"]
        != sha256_bytes(compact_canonical_json_bytes(manifest))
    ):
        fail("build-gates artifact manifest hash is invalid")
    observed: dict[str, tuple[dict[str, Any], bytes]] = {}
    for slug, evidence_id, pass_marker, filenames in BUILD_GATE_BUNDLE:
        raw_root = run_root / "leaves" / slug / "raw"
        raw_metadata = assert_nofollow_directory_chain(
            run_root, raw_root, f"{evidence_id} raw directory"
        )
        if stat.S_IMODE(raw_metadata.st_mode) != 0o500:
            fail(f"{evidence_id} raw directory mode must be exactly 0500")
        try:
            names = {
                child.name
                for child in raw_root.iterdir()
                if child.name not in (".", "..")
            }
        except OSError:
            fail(f"{evidence_id} raw directory cannot be enumerated")
        if names != set(filenames):
            fail(f"{evidence_id} raw artifact closure differs")
        for index, filename in enumerate(filenames):
            relative = f"{slug}/{filename}"
            path = raw_root / filename
            contents, metadata, _ = stable_file_bytes(
                path, f"{evidence_id} committed artifact"
            )
            actual = build_artifact_descriptor(contents, metadata)
            expected = validate_build_artifact_descriptor(
                manifest[relative], f"{evidence_id} artifact manifest entry"
            )
            if actual != expected:
                fail(f"{evidence_id} committed artifact differs from manifest")
            if (
                actual["mtimeEpochNs"] > committed_ns
                or actual["ctimeEpochNs"] > committed_ns
            ):
                fail(f"{evidence_id} artifact postdates its bundle commit")
            if index == 0:
                try:
                    text = contents.decode("utf-8", errors="strict")
                except UnicodeError:
                    fail(f"{evidence_id} primary is not strict UTF-8")
                required_context = (
                    f"{CONTEXT_PREFIX} runId={context['runId']} "
                    f"sourceHash={context['sourceTreeHash']} "
                    f"dbIdentity={context['databaseIdentity']} "
                    f"evidenceId={evidence_id}"
                )
                if (
                    text.count(required_context) != 1
                    or text.count(pass_marker) != 1
                    or FORBIDDEN_STATUS_TOKEN in text
                ):
                    fail(f"{evidence_id} primary context/marker is invalid")
            observed[relative] = (actual, contents)
    marker_bytes_after, marker_metadata_after, _ = stable_file_bytes(
        marker_path, "build-gates commit marker final seal"
    )
    if (
        marker_bytes_after != marker_bytes
        or build_artifact_descriptor(
            marker_bytes_after, marker_metadata_after
        )
        != build_artifact_descriptor(marker_bytes, marker_metadata)
    ):
        fail("build-gates commit marker changed during validation")
    for relative, (expected, expected_bytes) in observed.items():
        slug, filename = relative.split("/", 1)
        path = run_root / "leaves" / slug / "raw" / filename
        contents, metadata, _ = stable_file_bytes(
            path, f"{relative} final bundle seal"
        )
        if (
            contents != expected_bytes
            or build_artifact_descriptor(contents, metadata) != expected
        ):
            fail("build-gates artifact changed during final bundle seal")
    return {
        "commitMarkerSha256": sha256_bytes(marker_bytes),
        "committedAt": marker["committedAt"],
        "artifactCount": len(observed),
        "artifactManifestSha256": marker["artifactManifestSha256"],
        "manifest": manifest,
    }


BACKEND_RUNTIME_CLASSES_RECEIPT_KEYS = {
    "schemaVersion",
    "kind",
    "logicalRoot",
    "fileCount",
    "classFileCount",
    "totalSizeBytes",
    "recordsSha256",
    "mainClassPath",
    "mainClassSha256",
    "records",
}
BACKEND_RUNTIME_CLASS_RECORD_KEYS = {
    "path",
    "mode",
    "sizeBytes",
    "sha256",
}


def backend_runtime_classes_receipt_from_snapshot(
    snapshot: Mapping[str, Any],
) -> dict[str, Any]:
    expected_root = (
        REPOSITORY_ROOT / "backend/target/classes"
    ).resolve(strict=True)
    if snapshot.get("rootRealpath") != expected_root.as_posix():
        fail("backend runtime class snapshot root is not exact")
    raw_records = require_array(
        snapshot.get("records"), "backend runtime class snapshot records"
    )
    records: list[dict[str, Any]] = []
    for index, raw_record in enumerate(raw_records):
        record = require_object(
            raw_record, f"backend runtime class snapshot record[{index}]"
        )
        path_value = record.get("path")
        mode = record.get("mode")
        size_bytes = record.get("sizeBytes")
        digest = record.get("sha256")
        if (
            not isinstance(path_value, str)
            or not path_value
            or unicodedata.normalize("NFC", path_value) != path_value
            or Path(path_value).is_absolute()
            or Path(path_value).as_posix() != path_value
            or "\\" in path_value
            or any(
                part in {"", ".", ".."} for part in Path(path_value).parts
            )
            or type(mode) is not int
            or mode < 0
            or mode > 0o7777
            or type(size_bytes) is not int
            or size_bytes < 0
            or not isinstance(digest, str)
            or not SHA256_PATTERN.fullmatch(digest)
        ):
            fail(
                "backend runtime class snapshot contains an invalid "
                "logical record"
            )
        records.append(
            {
                "path": path_value,
                "mode": mode,
                "sizeBytes": size_bytes,
                "sha256": digest,
            }
        )
    records.sort(key=lambda item: item["path"].encode("utf-8"))
    if len({record["path"] for record in records}) != len(records):
        fail("backend runtime class snapshot paths are duplicated")
    class_records = [
        record for record in records if record["path"].endswith(".class")
    ]
    main_class_path = "com/szsemicon/hr/ShenzhouHrApplication.class"
    main_class_records = [
        record for record in class_records
        if record["path"] == main_class_path
    ]
    if (
        not records
        or not class_records
        or len(main_class_records) != 1
        or snapshot.get("fileCount") != len(records)
    ):
        fail("backend runtime class snapshot closure is invalid")
    return {
        "schemaVersion": 1,
        "kind": "wave3-backend-runtime-classes-receipt",
        "logicalRoot": "backend/target/classes",
        "fileCount": len(records),
        "classFileCount": len(class_records),
        "totalSizeBytes": sum(record["sizeBytes"] for record in records),
        "recordsSha256": sha256_bytes(
            compact_canonical_json_bytes(records)
        ),
        "mainClassPath": main_class_path,
        "mainClassSha256": main_class_records[0]["sha256"],
        "records": records,
    }


def validate_backend_runtime_classes_receipt(
    context: Mapping[str, Any],
    live_snapshot: Mapping[str, Any],
) -> dict[str, Any]:
    bundle = validate_build_bundle_commit(context)
    relative = "backend-full/backend-test-summary.json"
    path = (
        Path(context["runRoot"])
        / "leaves/backend-full/raw/backend-test-summary.json"
    )
    contents, metadata, _ = stable_file_bytes(
        path, "backend-full committed test summary"
    )
    expected_descriptor = validate_build_artifact_descriptor(
        require_object(
            bundle["manifest"], "build-gates artifact manifest"
        ).get(relative),
        "backend-full test summary manifest entry",
    )
    if build_artifact_descriptor(contents, metadata) != expected_descriptor:
        fail("backend-full test summary differs from the build bundle")
    try:
        text = contents.decode("utf-8", errors="strict")
    except UnicodeError:
        fail("backend-full test summary is not strict UTF-8 JSON")
    summary = require_exact_keys(
        strict_json_loads(text, "backend-full test summary"),
        {
            "schemaVersion",
            "nodeType",
            "evidenceId",
            "runId",
            "sourceTreeHash",
            "databaseIdentity",
            "verdict",
            "marker",
            "command",
            "startedAt",
            "startedAtEpochNs",
            "completedAt",
            "completedAtEpochNs",
            "toolchain",
            "suiteCount",
            "tests",
            "failures",
            "errors",
            "skipped",
            "expectedTestClasses",
            "suites",
            "rawSurefireArchive",
            "runtimeClassesReceipt",
        },
        "backend-full test summary",
    )
    expected_scalars = {
        "schemaVersion": 3,
        "nodeType": "wave3-backend-test-summary",
        "evidenceId": "W3-VER-BACKEND-FULL",
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "verdict": "PASS",
        "marker": "W3_BACKEND_FULL=PASS",
        "failures": 0,
        "errors": 0,
        "skipped": 0,
    }
    if any(
        summary.get(key) != expected
        for key, expected in expected_scalars.items()
    ):
        fail("backend-full test summary binding/scalars are invalid")
    if any(
        type(summary[key]) is not int
        for key in (
            "schemaVersion",
            "startedAtEpochNs",
            "completedAtEpochNs",
            "suiteCount",
            "tests",
            "failures",
            "errors",
            "skipped",
        )
    ):
        fail("backend-full test summary integer types are invalid")
    raw_receipt = require_exact_keys(
        summary["runtimeClassesReceipt"],
        BACKEND_RUNTIME_CLASSES_RECEIPT_KEYS,
        "backend-full runtime classes receipt",
    )
    raw_records = require_array(
        raw_receipt["records"], "backend-full runtime classes records"
    )
    validated_records: list[dict[str, Any]] = []
    for index, raw_record in enumerate(raw_records):
        record = require_exact_keys(
            raw_record,
            BACKEND_RUNTIME_CLASS_RECORD_KEYS,
            f"backend-full runtime class record[{index}]",
        )
        path_value = record["path"]
        if (
            not isinstance(path_value, str)
            or not path_value
            or unicodedata.normalize("NFC", path_value) != path_value
            or Path(path_value).is_absolute()
            or Path(path_value).as_posix() != path_value
            or "\\" in path_value
            or any(
                part in {"", ".", ".."} for part in Path(path_value).parts
            )
            or type(record["mode"]) is not int
            or record["mode"] < 0
            or record["mode"] > 0o7777
            or type(record["sizeBytes"]) is not int
            or record["sizeBytes"] < 0
            or not isinstance(record["sha256"], str)
            or not SHA256_PATTERN.fullmatch(record["sha256"])
        ):
            fail("backend-full runtime class record is invalid")
        validated_records.append(dict(record))
    if validated_records != sorted(
        validated_records, key=lambda item: item["path"].encode("utf-8")
    ) or len(
        {record["path"] for record in validated_records}
    ) != len(validated_records):
        fail(
            "backend-full runtime class records are not unique/canonical"
        )
    class_records = [
        record for record in validated_records
        if record["path"].endswith(".class")
    ]
    main_class_records = [
        record for record in class_records
        if record["path"]
        == "com/szsemicon/hr/ShenzhouHrApplication.class"
    ]
    expected_receipt_scalars = {
        "schemaVersion": 1,
        "kind": "wave3-backend-runtime-classes-receipt",
        "logicalRoot": "backend/target/classes",
        "fileCount": len(validated_records),
        "classFileCount": len(class_records),
        "totalSizeBytes": sum(
            record["sizeBytes"] for record in validated_records
        ),
        "recordsSha256": sha256_bytes(
            compact_canonical_json_bytes(validated_records)
        ),
        "mainClassPath": (
            "com/szsemicon/hr/ShenzhouHrApplication.class"
        ),
        "mainClassSha256": (
            main_class_records[0]["sha256"]
            if len(main_class_records) == 1
            else None
        ),
    }
    if any(
        raw_receipt.get(key) != expected
        for key, expected in expected_receipt_scalars.items()
    ) or any(
        type(raw_receipt[key]) is not int
        for key in (
            "schemaVersion",
            "fileCount",
            "classFileCount",
            "totalSizeBytes",
        )
    ):
        fail("backend-full runtime classes receipt is invalid")
    live_receipt = backend_runtime_classes_receipt_from_snapshot(
        live_snapshot
    )
    if compact_canonical_json_bytes(raw_receipt) != (
        compact_canonical_json_bytes(live_receipt)
    ):
        fail(
            "live backend target/classes differs from the committed "
            "backend-full receipt"
        )
    return {
        "evidenceId": "W3-VER-BACKEND-FULL",
        "summarySha256": sha256_bytes(contents),
        "buildBundleCommitSha256": bundle["commitMarkerSha256"],
        "buildBundleManifestSha256": bundle[
            "artifactManifestSha256"
        ],
        "fileCount": live_receipt["fileCount"],
        "classFileCount": live_receipt["classFileCount"],
        "totalSizeBytes": live_receipt["totalSizeBytes"],
        "recordsSha256": live_receipt["recordsSha256"],
        "mainClassSha256": live_receipt["mainClassSha256"],
        "verdict": "PASS",
    }


def resolve_safe_executable(name: str, label: str) -> Path:
    located = shutil.which(name, path=safe_subprocess_environment().get("PATH"))
    if not located:
        fail(f"{label} executable is unavailable")
    try:
        resolved = Path(located).resolve(strict=True)
    except OSError:
        fail(f"{label} executable cannot be resolved")
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        fail(f"{label} executable is not a regular executable")
    return resolved


def recorded_absolute_executable(value: Any, label: str) -> Path:
    if (
        not isinstance(value, str)
        or not value
        or unicodedata.normalize("NFC", value) != value
    ):
        fail(f"{label} recorded executable path is invalid")
    path = Path(value)
    if not path.is_absolute() or ".." in path.parts:
        fail(f"{label} recorded executable path is not canonical absolute")
    try:
        resolved = path.resolve(strict=True)
    except OSError:
        fail(f"{label} recorded executable cannot be resolved")
    if (
        resolved != path
        or path.is_symlink()
        or not path.is_file()
        or not os.access(path, os.X_OK)
    ):
        fail(f"{label} recorded executable is not a current real executable")
    return path


def current_tool_runtime_manifest(
    *,
    java: Path,
    node: Path,
    npm: Path,
) -> dict[str, Any]:
    try:
        try:
            from scripts.qa.produce_wave3_build_gates import (
                stable_tool_runtime_manifest,
            )
        except ModuleNotFoundError:
            from produce_wave3_build_gates import (
                stable_tool_runtime_manifest,
            )
        return stable_tool_runtime_manifest(
            {"java": java, "node": node, "npm": npm}
        )
    except Exception as error:
        fail(
            "current tool runtime could not be sealed/recomputed: "
            f"{type(error).__name__}: {error}"
        )


def current_dependency_handoff_manifest() -> dict[str, Any]:
    try:
        try:
            from scripts.qa.produce_wave3_build_gates import (
                stable_origin_dependency_manifest,
            )
        except ModuleNotFoundError:
            from produce_wave3_build_gates import (
                stable_origin_dependency_manifest,
            )
        return stable_origin_dependency_manifest(
            REPOSITORY_ROOT / "frontend/node_modules",
            Path.home() / ".m2",
        )
    except Exception as error:
        fail(
            "current dependency handoff could not be sealed/recomputed: "
            f"{type(error).__name__}: {error}"
        )


def current_command_support_manifest() -> dict[str, Any]:
    try:
        try:
            from scripts.qa.produce_wave3_build_gates import (
                stable_repository_command_support_manifest,
            )
        except ModuleNotFoundError:
            from produce_wave3_build_gates import (
                stable_repository_command_support_manifest,
            )
        return stable_repository_command_support_manifest()
    except Exception as error:
        fail(
            "current command support could not be sealed/recomputed: "
            f"{type(error).__name__}: {error}"
        )


def validate_live_file_receipt(
    raw_record: Any,
    *,
    path: Path,
    receipt_path: str,
    label: str,
    absolute_path: bool = False,
) -> dict[str, Any]:
    record = validate_build_file_receipt(
        raw_record, label, absolute_path=absolute_path
    )
    actual, _contents = stable_build_file_receipt(
        path, receipt_path=receipt_path, label=f"{label} current file"
    )
    if actual != record:
        fail(f"{label} differs from the current file identity/content")
    return record


def frontend_runtime_dependency_binding(
    current_dependency_manifest: Mapping[str, Any],
) -> dict[str, Any]:
    node_manifest = require_object(
        current_dependency_manifest.get("node"),
        "current frontend Node dependency manifest",
    )
    records = require_array(
        node_manifest.get("records"),
        "current frontend Node dependency records",
    )
    vite_entry_records = [
        require_object(record, "current Vite entry record")
        for record in records
        if isinstance(record, dict)
        and record.get("path") == "vite/bin/vite.js"
        and record.get("kind") == "file"
    ]
    vite_link_records = [
        require_object(record, "current Vite executable link record")
        for record in records
        if isinstance(record, dict)
        and record.get("path") == ".bin/vite"
        and record.get("kind") == "symlink"
    ]
    if len(vite_entry_records) != 1 or len(vite_link_records) != 1:
        fail("current frontend dependency manifest lacks exact Vite entries")
    entry_record = vite_entry_records[0]
    link_record = vite_link_records[0]
    vite_link = REPOSITORY_ROOT / "frontend/node_modules/.bin/vite"
    try:
        vite_entry = vite_link.resolve(strict=True)
    except OSError:
        fail("current Vite executable link is unavailable")
    expected_entry = (
        REPOSITORY_ROOT / "frontend/node_modules/vite/bin/vite.js"
    ).resolve(strict=True)
    if (
        vite_entry != expected_entry
        or not vite_link.is_symlink()
        or link_record.get("resolvedTargetRelativePath")
        != "vite/bin/vite.js"
    ):
        fail("current Vite executable link target is not exact")
    contents, metadata, realpath = stable_file_bytes(
        vite_entry, "current Vite dependency entry"
    )
    expected_file_fields = {
        "mode": stat.S_IMODE(metadata.st_mode),
        "sizeBytes": len(contents),
        "sha256": sha256_bytes(contents),
    }
    if any(
        entry_record.get(key) != expected
        for key, expected in expected_file_fields.items()
    ) or any(
        link_record.get(key) != expected
        for key, expected in {
            "resolvedTargetMode": expected_file_fields["mode"],
            "resolvedTargetSizeBytes": expected_file_fields["sizeBytes"],
            "resolvedTargetSha256": expected_file_fields["sha256"],
        }.items()
    ):
        fail("current Vite dependency receipt differs from the live entry")
    return {
        "dependencyManifestSha256": current_dependency_manifest.get(
            "logicalManifestSha256"
        ),
        "viteEntryRealpath": realpath.as_posix(),
        "viteEntrySizeBytes": len(contents),
        "viteEntrySha256": sha256_bytes(contents),
        "viteExecutableLink": "frontend/node_modules/.bin/vite",
        "viteExecutableResolvedTarget": "vite/bin/vite.js",
        "verdict": "PASS",
    }


def validate_build_toolchain(
    value: Any,
    *,
    expected_executable: Path,
    context: Mapping[str, Any],
    build_started_ns: int,
    label: str,
    current_dependency_manifest: Mapping[str, Any],
    current_support_manifest: Mapping[str, Any],
) -> dict[str, Any]:
    current_java_executable = resolve_safe_executable(
        "java", f"{label} current Java authority"
    )
    current_node_executable = resolve_safe_executable(
        "node", f"{label} current Node authority"
    )
    current_npm_executable = resolve_safe_executable(
        "npm", f"{label} current npm authority"
    )
    if expected_executable != current_npm_executable:
        fail(f"{label} receipt-selected npm differs from current authority")
    toolchain = require_exact_keys(
        value,
        {
            "schemaVersion",
            "resolvedExecutable",
            "executableDevice",
            "executableInode",
            "executableMode",
            "executableSizeBytes",
            "executableMtimeEpochNs",
            "executableCtimeEpochNs",
            "executableSha256",
            "versionOutput",
            "runtimeExecutables",
            "installState",
            "installStateSha256",
            "workspaceSeal",
        },
        f"{label} toolchain",
    )
    if (
        type(toolchain["schemaVersion"]) is not int
        or toolchain["schemaVersion"] != 1
        or toolchain["resolvedExecutable"] != str(expected_executable)
        or not isinstance(toolchain["versionOutput"], str)
        or not toolchain["versionOutput"].strip()
        or len(toolchain["versionOutput"]) > 32_768
        or FORBIDDEN_STATUS_TOKEN in toolchain["versionOutput"]
    ):
        fail(f"{label} toolchain scalar contract is invalid")
    executable_receipt, _contents = stable_build_file_receipt(
        expected_executable,
        receipt_path=str(expected_executable),
        label=f"{label} command executable",
    )
    expected_executable_fields = {
        "executableDevice": executable_receipt["device"],
        "executableInode": executable_receipt["inode"],
        "executableMode": executable_receipt["mode"],
        "executableSizeBytes": executable_receipt["sizeBytes"],
        "executableMtimeEpochNs": executable_receipt["mtimeEpochNs"],
        "executableCtimeEpochNs": executable_receipt["ctimeEpochNs"],
        "executableSha256": executable_receipt["sha256"],
    }
    if any(
        toolchain.get(key) != expected
        for key, expected in expected_executable_fields.items()
    ):
        fail(f"{label} command executable receipt is stale")
    runtime_receipts = require_array(
        toolchain["runtimeExecutables"], f"{label} runtime executables"
    )
    if len(runtime_receipts) != 2:
        fail(f"{label} runtime executable closure is invalid")
    expected_runtime_paths = tuple(
        recorded_absolute_executable(
            require_object(
                raw_receipt,
                f"{label} runtime executable[{index}]",
            ).get("path"),
            f"{label} runtime executable[{index}]",
        )
        for index, raw_receipt in enumerate(runtime_receipts)
    )
    if (
        expected_runtime_paths[0].name != "node"
        or expected_runtime_paths[1] != expected_executable
        or expected_runtime_paths
        != (current_node_executable, current_npm_executable)
    ):
        fail(f"{label} runtime executable identity/order is invalid")
    validated_runtime_receipts: list[dict[str, Any]] = []
    for index, (raw_receipt, path) in enumerate(
        zip(runtime_receipts, expected_runtime_paths, strict=True)
    ):
        validated_runtime_receipts.append(
            validate_live_file_receipt(
                raw_receipt,
                path=path,
                receipt_path=str(path),
                label=f"{label} runtime executable[{index}]",
                absolute_path=True,
            )
        )
    install_paths = (
        REPOSITORY_ROOT / "frontend/package.json",
        REPOSITORY_ROOT / "frontend/package-lock.json",
        REPOSITORY_ROOT / "frontend/node_modules/.package-lock.json",
    )
    install_state = require_array(
        toolchain["installState"], f"{label} install state"
    )
    if len(install_state) != len(install_paths):
        fail(f"{label} install-state closure is invalid")
    try:
        run_relative = Path(context["runRoot"]).relative_to(
            REPOSITORY_ROOT
        )
    except ValueError:
        fail(f"{label} run root is outside the repository")
    validated_install: list[dict[str, Any]] = []
    isolated_stage_name: str | None = None
    for index, (raw_receipt, path) in enumerate(
        zip(install_state, install_paths, strict=True)
    ):
        receipt = validate_build_file_receipt(
            raw_receipt, f"{label} install state[{index}]"
        )
        receipt_parts = Path(receipt["path"]).parts
        prefix = run_relative.parts
        expected_tail = (
            (".command-workspace", "frontend", "node_modules", ".package-lock.json")
            if path.name == ".package-lock.json"
            else (".command-workspace", "frontend", path.name)
        )
        if (
            len(receipt_parts) != len(prefix) + 1 + len(expected_tail)
            or receipt_parts[: len(prefix)] != prefix
            or receipt_parts[-len(expected_tail) :] != expected_tail
            or not re.fullmatch(
                r"\.build-gates-tmp-[A-Za-z0-9._-]+",
                receipt_parts[len(prefix)],
            )
        ):
            fail(f"{label} install-state path is not the run-private clone")
        stage_name = receipt_parts[len(prefix)]
        if isolated_stage_name is None:
            isolated_stage_name = stage_name
        elif isolated_stage_name != stage_name:
            fail(f"{label} install-state paths use different workspaces")
        current, _contents = stable_build_file_receipt(
            path,
            receipt_path=path.relative_to(REPOSITORY_ROOT).as_posix(),
            label=f"{label} origin install state[{index}]",
        )
        if any(
            receipt[key] != current[key]
            for key in ("sizeBytes", "mode", "sha256")
        ):
            fail(f"{label} isolated install state differs from its origin")
        if (
            receipt["mtimeEpochNs"] > build_started_ns
            or receipt["ctimeEpochNs"] > build_started_ns
        ):
            fail(f"{label} install state postdates command start")
        validated_install.append(receipt)
    if (
        not isinstance(toolchain["installStateSha256"], str)
        or toolchain["installStateSha256"]
        != sha256_bytes(compact_canonical_json_bytes(validated_install))
    ):
        fail(f"{label} install-state manifest hash is invalid")
    workspace = require_exact_keys(
        toolchain["workspaceSeal"],
        {
            "schemaVersion",
            "sealType",
            "authorityRoots",
            "objectCount",
            "regularFileCount",
            "cacheAnchorCount",
            "contentManifestSha256",
            "dependencyHandoff",
            "commandSupport",
            "toolRuntime",
        },
        f"{label} workspace seal",
    )
    dependency_handoff = require_exact_keys(
        workspace["dependencyHandoff"],
        {
            "schemaVersion",
            "comparison",
            "equal",
            "logicalManifestSha256",
            "nodeRecordCount",
            "nodeLinkCount",
            "mavenRecordCount",
        },
        f"{label} dependency handoff",
    )
    command_support = require_exact_keys(
        workspace["commandSupport"],
        {
            "schemaVersion",
            "comparison",
            "equal",
            "recordCount",
            "recordsSha256",
        },
        f"{label} command support",
    )
    tool_runtime = require_exact_keys(
        workspace["toolRuntime"],
        {
            "schemaVersion",
            "comparison",
            "equal",
            "logicalManifestSha256",
            "javaExecutablePath",
            "nodeExecutablePath",
            "npmExecutablePath",
            "nodeSha256",
            "javaRecordCount",
            "javaRecordsSha256",
            "npmPackageRecordCount",
            "npmPackageRecordsSha256",
            "npmDependencyRecordCount",
            "npmDependencyLinkCount",
            "npmDependencyRecordsSha256",
        },
        f"{label} tool runtime",
    )
    java_executable = recorded_absolute_executable(
        tool_runtime["javaExecutablePath"],
        f"{label} Java runtime",
    )
    node_executable = recorded_absolute_executable(
        tool_runtime["nodeExecutablePath"],
        f"{label} Node runtime",
    )
    npm_executable = recorded_absolute_executable(
        tool_runtime["npmExecutablePath"],
        f"{label} npm runtime",
    )
    current_tool_runtime = current_tool_runtime_manifest(
        java=current_java_executable,
        node=current_node_executable,
        npm=current_npm_executable,
    )
    current_java = current_tool_runtime["java"]
    current_npm_package = current_tool_runtime["npmPackage"]
    current_npm_dependencies = current_tool_runtime["npmDependencies"]
    frontend_dependency_binding = frontend_runtime_dependency_binding(
        current_dependency_manifest
    )
    if (
        type(workspace["schemaVersion"]) is not int
        or workspace["schemaVersion"] != 1
        or workspace["sealType"]
        != "darwin-kqueue-vnode-two-phase-content-v2"
        or workspace["authorityRoots"]
        != [
            "frozen-source",
            "isolated-command-source",
            "isolated-command-support",
            "isolated-maven-install",
            "isolated-node-install",
            "tool-java",
            "tool-node",
            "tool-npm",
        ]
        or type(workspace["objectCount"]) is not int
        or type(workspace["regularFileCount"]) is not int
        or workspace["objectCount"] <= 0
        or workspace["regularFileCount"] <= 0
        or workspace["regularFileCount"] > workspace["objectCount"]
        or type(workspace["cacheAnchorCount"]) is not int
        or workspace["cacheAnchorCount"] != 2
        or not isinstance(workspace["contentManifestSha256"], str)
        or not SHA256_PATTERN.fullmatch(workspace["contentManifestSha256"])
        or type(dependency_handoff["schemaVersion"]) is not int
        or dependency_handoff["schemaVersion"] != 1
        or dependency_handoff["comparison"] != "exact-canonical-json"
        or dependency_handoff["equal"] is not True
        or not isinstance(
            dependency_handoff["logicalManifestSha256"], str
        )
        or not SHA256_PATTERN.fullmatch(
            dependency_handoff["logicalManifestSha256"]
        )
        or type(dependency_handoff["nodeRecordCount"]) is not int
        or dependency_handoff["nodeRecordCount"] <= 0
        or type(dependency_handoff["nodeLinkCount"]) is not int
        or dependency_handoff["nodeLinkCount"] != EXPECTED_NPM_BIN_LINKS
        or type(dependency_handoff["mavenRecordCount"]) is not int
        or dependency_handoff["mavenRecordCount"] <= 0
        or dependency_handoff["logicalManifestSha256"]
        != current_dependency_manifest.get("logicalManifestSha256")
        or dependency_handoff["nodeRecordCount"]
        != require_object(
            current_dependency_manifest.get("node"),
            f"{label} current Node dependency manifest",
        ).get("recordCount")
        or dependency_handoff["nodeLinkCount"]
        != require_object(
            current_dependency_manifest.get("node"),
            f"{label} current Node dependency manifest",
        ).get("linkCount")
        or dependency_handoff["mavenRecordCount"]
        != require_object(
            current_dependency_manifest.get("maven"),
            f"{label} current Maven dependency manifest",
        ).get("recordCount")
        or type(command_support["schemaVersion"]) is not int
        or command_support["schemaVersion"] != 1
        or command_support["comparison"] != "exact-canonical-json"
        or command_support["equal"] is not True
        or type(command_support["recordCount"]) is not int
        or command_support["recordCount"]
        != EXPECTED_BUILD_COMMAND_SUPPORT_FILES
        or not isinstance(command_support["recordsSha256"], str)
        or not SHA256_PATTERN.fullmatch(command_support["recordsSha256"])
        or command_support["recordCount"]
        != current_support_manifest.get("recordCount")
        or command_support["recordsSha256"]
        != current_support_manifest.get("recordsSha256")
        or type(tool_runtime["schemaVersion"]) is not int
        or tool_runtime["schemaVersion"] != 1
        or tool_runtime["comparison"] != "exact-canonical-json"
        or tool_runtime["equal"] is not True
        or java_executable != current_java_executable
        or node_executable != current_node_executable
        or npm_executable != current_npm_executable
        or node_executable != expected_runtime_paths[0]
        or npm_executable != expected_runtime_paths[1]
        or java_executable.name != "java"
        or not isinstance(tool_runtime["logicalManifestSha256"], str)
        or not SHA256_PATTERN.fullmatch(
            tool_runtime["logicalManifestSha256"]
        )
        or not isinstance(tool_runtime["nodeSha256"], str)
        or not SHA256_PATTERN.fullmatch(tool_runtime["nodeSha256"])
        or tool_runtime["nodeSha256"]
        != validated_runtime_receipts[0]["sha256"]
        or type(tool_runtime["javaRecordCount"]) is not int
        or tool_runtime["javaRecordCount"] <= 0
        or not isinstance(tool_runtime["javaRecordsSha256"], str)
        or not SHA256_PATTERN.fullmatch(
            tool_runtime["javaRecordsSha256"]
        )
        or type(tool_runtime["npmPackageRecordCount"]) is not int
        or tool_runtime["npmPackageRecordCount"] <= 0
        or not isinstance(
            tool_runtime["npmPackageRecordsSha256"],
            str,
        )
        or not SHA256_PATTERN.fullmatch(
            tool_runtime["npmPackageRecordsSha256"]
        )
        or type(tool_runtime["npmDependencyRecordCount"]) is not int
        or tool_runtime["npmDependencyRecordCount"] <= 0
        or type(tool_runtime["npmDependencyLinkCount"]) is not int
        or tool_runtime["npmDependencyLinkCount"] <= 0
        or not isinstance(
            tool_runtime["npmDependencyRecordsSha256"],
            str,
        )
        or not SHA256_PATTERN.fullmatch(
            tool_runtime["npmDependencyRecordsSha256"]
        )
        or tool_runtime["logicalManifestSha256"]
        != current_tool_runtime["logicalManifestSha256"]
        or tool_runtime["javaRecordCount"]
        != current_java["recordCount"]
        or tool_runtime["javaRecordsSha256"]
        != current_java["recordsSha256"]
        or tool_runtime["npmPackageRecordCount"]
        != current_npm_package["recordCount"]
        or tool_runtime["npmPackageRecordsSha256"]
        != current_npm_package["recordsSha256"]
        or tool_runtime["npmDependencyRecordCount"]
        != current_npm_dependencies["recordCount"]
        or tool_runtime["npmDependencyLinkCount"]
        != current_npm_dependencies["linkCount"]
        or tool_runtime["npmDependencyRecordsSha256"]
        != current_npm_dependencies["recordsSha256"]
    ):
        fail(f"{label} workspace-seal receipt is invalid")
    return {
        "resolvedExecutable": str(expected_executable),
        "executableSha256": executable_receipt["sha256"],
        "nodeExecutableRealpath": str(expected_runtime_paths[0]),
        "nodeExecutableSha256": validated_runtime_receipts[0]["sha256"],
        "frontendDependencyBinding": frontend_dependency_binding,
        "installStateSha256": toolchain["installStateSha256"],
        "workspaceManifestSha256": workspace["contentManifestSha256"],
        "dependencyManifestSha256": dependency_handoff[
            "logicalManifestSha256"
        ],
        "commandSupportManifestSha256": command_support["recordsSha256"],
        "toolRuntimeManifestSha256": tool_runtime[
            "logicalManifestSha256"
        ],
    }


def validate_build_receipt(
    context: Mapping[str, Any],
    *,
    mode: str,
    dist_root: Path,
    build_input_maximum_ns: int,
    current_dependency_manifest: Mapping[str, Any],
    current_support_manifest: Mapping[str, Any],
) -> dict[str, Any]:
    specification = BUILD_RECEIPTS.get(mode)
    if specification is None:
        fail("build receipt mode is invalid")
    bundle = validate_build_bundle_commit(context)
    raw_root = (
        Path(context["runRoot"])
        / "leaves"
        / specification["slug"]
        / "raw"
    )
    primary_path = raw_root / str(specification["primary"])
    inventory_path = raw_root / str(specification["inventory"])
    primary_bytes, primary_metadata, _ = stable_file_bytes(
        primary_path, f"{mode} build primary receipt"
    )
    inventory_bytes, inventory_metadata, _ = stable_file_bytes(
        inventory_path, f"{mode} build inventory receipt"
    )
    try:
        primary_text = primary_bytes.decode("utf-8", errors="strict")
        inventory_text = inventory_bytes.decode(
            "utf-8", errors="strict"
        )
    except UnicodeError:
        fail(f"{mode} build receipt is not strict UTF-8/JSON")
    inventory_value = strict_json_loads(
        inventory_text, f"{mode} build inventory"
    )
    inventory = require_exact_keys(
        inventory_value,
        {
            "schemaVersion",
            "nodeType",
            "evidenceId",
            "runId",
            "sourceTreeHash",
            "databaseIdentity",
            "verdict",
            "marker",
            "mode",
            "command",
            "startedAt",
            "startedAtEpochNs",
            "completedAt",
            "completedAtEpochNs",
            "toolchain",
            "fileCount",
            "totalSizeBytes",
            "manifestSha256",
            "files",
            "contentAddressedArchive",
        },
        f"{mode} build inventory",
    )
    raw_toolchain = require_object(
        inventory["toolchain"],
        f"{mode} build toolchain",
    )
    recorded_npm = recorded_absolute_executable(
        raw_toolchain.get("resolvedExecutable"),
        f"{mode} build npm",
    )
    npm = resolve_safe_executable("npm", f"{mode} current npm authority")
    if recorded_npm != npm:
        fail(
            f"{mode} build receipt-selected npm differs from current "
            "authority"
        )
    expected_command = [str(npm), *specification["arguments"]]
    expected_scalars = {
        "schemaVersion": 2,
        "nodeType": "wave3-build-inventory",
        "evidenceId": specification["evidenceId"],
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "verdict": "PASS",
        "marker": specification["marker"],
        "mode": specification["mode"],
        "command": expected_command,
    }
    for key, expected in expected_scalars.items():
        if inventory.get(key) != expected:
            fail(f"{mode} build inventory {key} mismatch")
    assert_no_null_or_forbidden(inventory, f"{mode} build inventory")
    started_ns = inventory["startedAtEpochNs"]
    completed_ns = inventory["completedAtEpochNs"]
    if (
        type(started_ns) is not int
        or type(completed_ns) is not int
        or inventory["startedAt"]
        != utc_iso_from_ns(started_ns, f"{mode} build START")
        or inventory["completedAt"]
        != utc_iso_from_ns(completed_ns, f"{mode} build completion")
        or completed_ns < started_ns
        or completed_ns > time.time_ns()
    ):
        fail(f"{mode} build receipt time binding is invalid")
    toolchain_summary = validate_build_toolchain(
        inventory["toolchain"],
        expected_executable=npm,
        context=context,
        build_started_ns=started_ns,
        label=f"{mode} build",
        current_dependency_manifest=current_dependency_manifest,
        current_support_manifest=current_support_manifest,
    )
    files = require_array(inventory["files"], f"{mode} build files")
    if (
        type(inventory["fileCount"]) is not int
        or type(inventory["totalSizeBytes"]) is not int
        or inventory["fileCount"] != len(files)
        or inventory["fileCount"] <= 0
        or inventory["totalSizeBytes"]
        != sum(
            int(require_object(item, "build inventory file")["sizeBytes"])
            for item in files
        )
        or not isinstance(inventory["manifestSha256"], str)
        or inventory["manifestSha256"]
        != sha256_bytes(compact_canonical_json_bytes(files))
    ):
        fail(f"{mode} build inventory aggregate is invalid")
    snapshot = stable_tree_snapshot(dist_root, f"{mode} current dist")
    freshness_floor = max(
        context["startedAtEpochNs"],
        context["sourceMaximumMtimeNs"],
        build_input_maximum_ns,
    )
    validate_dist_receipt_records(
        snapshot,
        files,
        freshness_floor_ns=freshness_floor,
        build_started_ns=started_ns,
        build_completed_ns=completed_ns,
        label=f"{mode} build",
    )
    archive_summary = validate_dist_content_archive(
        inventory["contentAddressedArchive"],
        inventory_files=files,
        snapshot=snapshot,
        label=f"{mode} build",
    )
    if (
        primary_metadata.st_mtime_ns <= completed_ns
        or primary_metadata.st_ctime_ns <= completed_ns
        or inventory_metadata.st_mtime_ns <= completed_ns
        or inventory_metadata.st_ctime_ns <= completed_ns
    ):
        fail(f"{mode} build receipt artifacts predate build completion")
    primary_lines = primary_text.splitlines()
    expected_primary_lines = (
        f"W3_GATE_STARTED_AT={inventory['startedAt']}",
        f"W3_GATE_STARTED_NS={started_ns}",
        f"W3_GATE_COMPLETED_AT={inventory['completedAt']}",
        f"W3_GATE_COMPLETED_NS={completed_ns}",
        f"W3_GATE_COMMAND={shlex.join(expected_command)}",
        (
            "W3_TOOLCHAIN_RECEIPT_JSON="
            f"{compact_canonical_json_bytes(inventory['toolchain']).decode('utf-8')}"
        ),
        "W3_GATE_EXIT_CODE=0",
        f"W3_BUILD_MODE={'demo' if mode == 'demo' else 'prod'}",
        f"W3_BUILD_FILE_COUNT={inventory['fileCount']}",
        f"W3_BUILD_TOTAL_SIZE_BYTES={inventory['totalSizeBytes']}",
        f"W3_BUILD_MANIFEST_SHA256={inventory['manifestSha256']}",
        "W3_BUILD_CONTENT_ARCHIVE=EMBEDDED",
    )
    if any(
        primary_lines.count(expected_line) != 1
        for expected_line in expected_primary_lines
    ):
        fail(f"{mode} build primary/inventory receipt binding is invalid")
    primary_relative = (
        f"{specification['slug']}/{specification['primary']}"
    )
    inventory_relative = (
        f"{specification['slug']}/{specification['inventory']}"
    )
    if (
        build_artifact_descriptor(primary_bytes, primary_metadata)
        != bundle["manifest"][primary_relative]
        or build_artifact_descriptor(inventory_bytes, inventory_metadata)
        != bundle["manifest"][inventory_relative]
    ):
        fail(f"{mode} build receipts differ from the bundle commit")
    content_records = [
        {
            "path": record["path"],
            "sizeBytes": record["sizeBytes"],
            "sha256": record["sha256"],
        }
        for record in snapshot["records"]
    ]
    return {
        "evidenceId": specification["evidenceId"],
        "receiptPrimarySha256": sha256_bytes(primary_bytes),
        "receiptInventorySha256": sha256_bytes(inventory_bytes),
        "buildBundleCommitSha256": bundle["commitMarkerSha256"],
        "buildBundleManifestSha256": bundle["artifactManifestSha256"],
        "distFileCount": snapshot["fileCount"],
        "distContentTreeSha256": sha256_bytes(
            b"".join(
                canonical_json_bytes(record) for record in content_records
            )
        ),
        "distMetadataTreeSha256": snapshot["treeSha256"],
        "minimumMtimeEpochNs": snapshot["minimumMtimeEpochNs"],
        "minimumCtimeEpochNs": snapshot["minimumCtimeEpochNs"],
        "buildStartedAt": inventory["startedAt"],
        "buildStartedAtEpochNs": started_ns,
        "buildCompletedAt": inventory["completedAt"],
        "buildCompletedAtEpochNs": completed_ns,
        "inventoryManifestSha256": inventory["manifestSha256"],
        "archive": archive_summary,
        "toolchain": toolchain_summary,
        "verdict": "PASS",
    }


def validate_fresh_dist(
    context: Mapping[str, Any],
    scopes: Mapping[str, Sequence[Path]],
) -> dict[str, Any]:
    build_inputs = collect_frontend_build_inputs(scopes)
    source_maximum_mtime = max(
        path.stat().st_mtime_ns for path in build_inputs
    )
    source_maximum_ctime = max(
        path.stat().st_ctime_ns for path in build_inputs
    )
    source_maximum = max(source_maximum_mtime, source_maximum_ctime)
    result: dict[str, Any] = {
        "buildInputFileCount": len(build_inputs),
        "buildInputMaximumMtimeEpochNs": source_maximum_mtime,
        "buildInputMaximumCtimeEpochNs": source_maximum_ctime,
        "buildInputFreshnessFloorEpochNs": source_maximum,
    }
    dependency_manifest = current_dependency_handoff_manifest()
    support_manifest = current_command_support_manifest()
    for scope_id, label in (
        ("frontend-prod-dist", "prod"),
        ("frontend-demo-dist", "demo"),
    ):
        files = scopes[scope_id]
        minimum_mtime = min(path.stat().st_mtime_ns for path in files)
        minimum_ctime = min(path.stat().st_ctime_ns for path in files)
        if (
            minimum_mtime <= source_maximum
            or minimum_ctime <= source_maximum
        ):
            fail(f"{label} dist is older than a relevant frontend build input")
        if (
            minimum_mtime <= context["startedAtEpochNs"]
            or minimum_ctime <= context["startedAtEpochNs"]
        ):
            fail(f"{label} dist predates the fresh evidence run")
        result[f"{label}DistMinimumMtimeEpochNs"] = minimum_mtime
        result[f"{label}DistMinimumCtimeEpochNs"] = minimum_ctime
        result[f"{label}BuildReceipt"] = validate_build_receipt(
            context,
            mode=label,
            dist_root=REPOSITORY_ROOT / f"frontend/dist/{label}",
            build_input_maximum_ns=source_maximum,
            current_dependency_manifest=dependency_manifest,
            current_support_manifest=support_manifest,
        )
    prod_receipt = require_object(
        result["prodBuildReceipt"], "production build receipt result"
    )
    demo_receipt = require_object(
        result["demoBuildReceipt"], "demo build receipt result"
    )
    if (
        prod_receipt.get("buildBundleCommitSha256")
        != demo_receipt.get("buildBundleCommitSha256")
        or prod_receipt.get("buildBundleManifestSha256")
        != demo_receipt.get("buildBundleManifestSha256")
        or require_object(
            prod_receipt.get("toolchain"), "production build toolchain result"
        ).get("workspaceManifestSha256")
        != require_object(
            demo_receipt.get("toolchain"), "demo build toolchain result"
        ).get("workspaceManifestSha256")
        or require_object(
            prod_receipt.get("toolchain"), "production build toolchain result"
        ).get("dependencyManifestSha256")
        != require_object(
            demo_receipt.get("toolchain"), "demo build toolchain result"
        ).get("dependencyManifestSha256")
        or require_object(
            prod_receipt.get("toolchain"), "production build toolchain result"
        ).get("commandSupportManifestSha256")
        != require_object(
            demo_receipt.get("toolchain"), "demo build toolchain result"
        ).get("commandSupportManifestSha256")
        or require_object(
            prod_receipt.get("toolchain"), "production build toolchain result"
        ).get("toolRuntimeManifestSha256")
        != require_object(
            demo_receipt.get("toolchain"), "demo build toolchain result"
        ).get("toolRuntimeManifestSha256")
    ):
        fail("prod/demo builds are not bound to one build-gates bundle/seal")
    return result


def stable_file_bytes(
    path: Path, label: str
) -> tuple[bytes, os.stat_result, Path]:
    resolved = assert_regular_file_no_symlink(path, label)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError:
        fail(f"{label} could not be opened without following links")
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            fail(f"{label} changed to a non-regular file")
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            contents = source.read()
        after = os.fstat(descriptor)
    except OSError:
        fail(f"{label} could not be read as one stable snapshot")
    finally:
        os.close(descriptor)
    fingerprint = (
        "st_dev",
        "st_ino",
        "st_mode",
        "st_size",
        "st_mtime_ns",
        "st_ctime_ns",
    )
    if any(getattr(before, key) != getattr(after, key) for key in fingerprint):
        fail(f"{label} changed while it was being read")
    try:
        current = path.lstat()
    except OSError:
        fail(f"{label} disappeared after it was read")
    if any(getattr(after, key) != getattr(current, key) for key in fingerprint):
        fail(f"{label} was replaced while it was being read")
    if len(contents) != after.st_size:
        fail(f"{label} byte count differs from its stable metadata")
    if path.resolve(strict=True) != resolved:
        fail(f"{label} realpath changed while it was being read")
    return contents, after, resolved


def decode_required_bytes(path: Path, contents: bytes) -> str:
    if path.suffix.lower() not in TEXT_SOURCE_SUFFIXES:
        fail(
            "required product scope contains an unsupported non-text artifact: "
            f"{path.relative_to(REPOSITORY_ROOT).as_posix()}"
        )
    try:
        return contents.decode("utf-8")
    except UnicodeError:
        fail(
            "required product scope is not strict UTF-8 text: "
            f"{path.relative_to(REPOSITORY_ROOT).as_posix()}"
        )


def scan_product_scopes(
    scopes: Mapping[str, Sequence[Path]]
) -> tuple[list[dict[str, Any]], int]:
    file_records: list[dict[str, Any]] = []
    scanned_lines = 0
    seen: set[Path] = set()
    for scope_id, files in scopes.items():
        for path in files:
            resolved = path.resolve(strict=True)
            if resolved in seen:
                continue
            seen.add(resolved)
            relative = unicodedata.normalize(
                "NFC", path.relative_to(REPOSITORY_ROOT).as_posix()
            )
            require_no_deny_match(relative, f"{relative}:path")
            contents, metadata, stable_realpath = stable_file_bytes(
                path, f"product scan file {relative}"
            )
            if stable_realpath != resolved:
                fail("product scan file realpath changed before scanning")
            text = decode_required_bytes(path, contents)
            normalized = normalized_casefold(text)
            match = DENY_PATTERN.search(normalized)
            if match is not None:
                line_number = normalized.count("\n", 0, match.start()) + 1
                fail(
                    "product deny scan found a discoverable token at "
                    f"{relative}:{line_number}"
                )
            line_count = max(1, len(text.splitlines()))
            scanned_lines += line_count
            file_records.append(
                {
                    "scopeId": scope_id,
                    "path": relative,
                    "realpath": resolved.as_posix(),
                    "sizeBytes": len(contents),
                    "sha256": sha256_bytes(contents),
                    "normalizedNfkcCasefoldSha256": sha256_text(normalized),
                    "lineCount": line_count,
                    "mtimeEpochNs": metadata.st_mtime_ns,
                }
            )
    file_records.sort(
        key=lambda item: (item["scopeId"], item["path"].encode("utf-8"))
    )
    return file_records, scanned_lines


def assert_product_scan_snapshot_unchanged(
    scopes: Mapping[str, Sequence[Path]],
    product_records: Sequence[Mapping[str, Any]],
) -> None:
    current_scopes = collect_product_scope_files()
    expected_membership = {
        scope_id: tuple(
            unicodedata.normalize(
                "NFC", path.relative_to(REPOSITORY_ROOT).as_posix()
            )
            for path in paths
        )
        for scope_id, paths in scopes.items()
    }
    current_membership = {
        scope_id: tuple(
            unicodedata.normalize(
                "NFC", path.relative_to(REPOSITORY_ROOT).as_posix()
            )
            for path in paths
        )
        for scope_id, paths in current_scopes.items()
    }
    if current_membership != expected_membership:
        fail("required product scan scope membership changed before publication")
    records = {
        (str(record["scopeId"]), str(record["path"])): record
        for record in product_records
    }
    for scope_id, paths in current_scopes.items():
        for path in paths:
            relative = unicodedata.normalize(
                "NFC", path.relative_to(REPOSITORY_ROOT).as_posix()
            )
            record = records.get((scope_id, relative))
            if record is None:
                fail("required product scan record is missing")
            contents, metadata, resolved = stable_file_bytes(
                path, f"product snapshot recheck {relative}"
            )
            expected = {
                "realpath": resolved.as_posix(),
                "sizeBytes": len(contents),
                "sha256": sha256_bytes(contents),
                "mtimeEpochNs": metadata.st_mtime_ns,
            }
            if any(record.get(key) != value for key, value in expected.items()):
                fail(
                    "product scan snapshot changed before publication: "
                    f"{relative}"
                )


def json_pointer_escape(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")


def json_pointer_resolve(document: Any, pointer: str) -> Any:
    if pointer == "":
        return document
    if not pointer.startswith("/"):
        fail("JSON Pointer must start with '/'")
    current = document
    for encoded in pointer[1:].split("/"):
        token = encoded.replace("~1", "/").replace("~0", "~")
        if isinstance(current, list):
            if not token.isdigit():
                fail("JSON Pointer list token is invalid")
            index = int(token)
            if index >= len(current):
                fail("JSON Pointer list index is out of range")
            current = current[index]
        elif isinstance(current, dict):
            if token not in current:
                fail("JSON Pointer object token is missing")
            current = current[token]
        else:
            fail("JSON Pointer traverses a scalar")
    return current


def iter_json_strings(
    value: Any, pointer: str = ""
) -> Iterator[tuple[str, str, str]]:
    if isinstance(value, str):
        yield ("value", pointer, value)
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            child = f"{pointer}/{index}"
            yield from iter_json_strings(item, child)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            key_pointer = f"{pointer}/{json_pointer_escape(key)}"
            yield ("key", key_pointer, key)
            yield from iter_json_strings(item, key_pointer)


def build_request_target_exclusions(
    runtime_document: Mapping[str, Any],
    *,
    runtime_artifact_path: str,
    runtime_artifact_realpath: str,
) -> dict[str, Any]:
    if runtime_artifact_path != RUNTIME_ARTIFACT_PATH:
        fail("runtime artifact path is not the fixed contract path")
    entries: list[dict[str, Any]] = []
    roles = require_array(runtime_document.get("roles"), "runtime roles")
    if len(roles) != len(ROLE_CODES):
        fail("runtime role probe count is not exactly three")
    ordinal = 0
    for role_index, raw_role in enumerate(roles):
        role = require_object(raw_role, f"runtime role[{role_index}]")
        if role.get("roleCode") != ROLE_CODES[role_index]:
            fail("runtime role order/set differs from the fixed three")
        for collection_name, expected_targets in (
            ("frontendRouteProbes", FRONTEND_NEGATIVE_TARGETS),
            ("apiProbes", API_NEGATIVE_TARGETS),
        ):
            probes = require_array(
                role.get(collection_name),
                f"runtime role[{role_index}] {collection_name}",
            )
            if len(probes) != len(expected_targets):
                fail("negative probe target cardinality is invalid")
            for probe_index, expected_target in enumerate(expected_targets):
                pointer = (
                    f"/roles/{role_index}/{collection_name}/"
                    f"{probe_index}/requestTarget"
                )
                target = json_pointer_resolve(runtime_document, pointer)
                if target != expected_target:
                    fail("negative probe request target/order is invalid")
                ordinal += 1
                entries.append(
                    {
                        "entryId": f"request-target-{ordinal:02d}",
                        "artifactPath": runtime_artifact_path,
                        "artifactRealpath": runtime_artifact_realpath,
                        "jsonPointer": pointer,
                        "scalarSha256": sha256_text(target),
                        "owner": ALLOWLIST_OWNER,
                        "reason": "negative-route-probe-request-target-only",
                        "expiresAt": ALLOWLIST_EXPIRES_AT,
                    }
                )
    expected_count = len(ROLE_CODES) * (
        len(FRONTEND_NEGATIVE_TARGETS) + len(API_NEGATIVE_TARGETS)
    )
    if len(entries) != expected_count:
        fail("request-target exclusion count is not exact")
    return {
        "schemaVersion": 1,
        "nodeType": "exact-request-target-exclusions",
        "policy": REQUEST_TARGET_EXCLUSION_POLICY,
        "entryCount": len(entries),
        "entries": entries,
    }


def validate_request_target_exclusions(
    runtime_document: Mapping[str, Any],
    exclusions: Mapping[str, Any],
    *,
    expected_artifact_realpath: str,
) -> set[str]:
    if (
        type(exclusions.get("schemaVersion")) is not int
        or exclusions.get("schemaVersion") != 1
    ):
        fail("request-target exclusion schema version is invalid")
    if exclusions.get("nodeType") != "exact-request-target-exclusions":
        fail("request-target exclusion node type is invalid")
    if exclusions.get("policy") != REQUEST_TARGET_EXCLUSION_POLICY:
        fail("request-target exclusion policy is invalid")
    entries = require_array(exclusions.get("entries"), "request exclusions")
    expected: list[tuple[str, str]] = []
    roles = require_array(runtime_document.get("roles"), "runtime roles")
    if len(roles) != len(ROLE_CODES):
        fail("runtime role probe count is not exactly three")
    for role_index, raw_role in enumerate(roles):
        role = require_object(raw_role, f"runtime role[{role_index}]")
        if role.get("roleCode") != ROLE_CODES[role_index]:
            fail("runtime role order/set differs from the fixed three")
        for collection_name, targets in (
            ("frontendRouteProbes", FRONTEND_NEGATIVE_TARGETS),
            ("apiProbes", API_NEGATIVE_TARGETS),
        ):
            probes = require_array(
                role.get(collection_name),
                f"runtime role[{role_index}] {collection_name}",
            )
            if len(probes) != len(targets):
                fail("negative probe target cardinality is invalid")
            for probe_index, target in enumerate(targets):
                expected.append(
                    (
                        f"/roles/{role_index}/{collection_name}/"
                        f"{probe_index}/requestTarget",
                        target,
                    )
                )
    if (
        type(exclusions.get("entryCount")) is not int
        or exclusions.get("entryCount") != len(expected)
    ):
        fail("request-target exclusion entryCount is invalid")
    if len(entries) != len(expected):
        fail("request-target exclusion entry array is not exact")
    pointers: set[str] = set()
    for index, (raw_entry, expected_item) in enumerate(
        zip(entries, expected, strict=True)
    ):
        entry = require_object(raw_entry, f"request exclusion[{index}]")
        expected_pointer, expected_target = expected_item
        if entry.get("entryId") != f"request-target-{index + 1:02d}":
            fail("request-target exclusion ID/order is invalid")
        if entry.get("artifactPath") != RUNTIME_ARTIFACT_PATH:
            fail("request-target exclusion artifact path is not exact")
        pointer = require_string(
            entry.get("jsonPointer"), "request exclusion JSON Pointer"
        )
        if pointer != expected_pointer:
            fail("request-target exclusion canonical pointer/order is invalid")
        if pointer in pointers:
            fail("request-target exclusions contain a duplicate JSON Pointer")
        if not pointer.endswith("/requestTarget"):
            fail("request-target exclusion does not name its exact scalar")
        if entry.get("artifactRealpath") != expected_artifact_realpath:
            fail("request-target exclusion realpath is not exact")
        value = json_pointer_resolve(runtime_document, pointer)
        if value != expected_target:
            fail("request-target exclusion resolves to an invalid scalar")
        if entry.get("scalarSha256") != sha256_text(value):
            fail("request-target exclusion scalar SHA256 is stale")
        if (
            entry.get("owner") != ALLOWLIST_OWNER
            or entry.get("reason")
            != "negative-route-probe-request-target-only"
        ):
            fail("request-target exclusion governance metadata is invalid")
        if parse_utc(
            require_string(entry.get("expiresAt"), "request exclusion expiry"),
            "request exclusion expiry",
        ) <= datetime.now(timezone.utc):
            fail("request-target exclusion is expired")
        pointers.add(pointer)
    if len(pointers) != len(expected):
        fail("request-target exclusion pointer set is not exact")
    return pointers


def scan_runtime_document(
    runtime_document: Mapping[str, Any],
    excluded_pointers: set[str],
) -> dict[str, Any]:
    scanned_strings = 0
    excluded_scalars = 0
    used_exclusions: set[str] = set()
    for kind, pointer, value in iter_json_strings(runtime_document):
        if kind == "value" and pointer in excluded_pointers:
            excluded_scalars += 1
            used_exclusions.add(pointer)
            continue
        scanned_strings += 1
        if deny_match_count(value):
            fail(f"runtime deny scan found a discoverable token at {pointer}")
    if used_exclusions != excluded_pointers:
        fail("not every request-target exclusion was used exactly")
    return {
        "scannedStringCount": scanned_strings,
        "excludedRequestTargetScalarCount": excluded_scalars,
        "discoverableHitCount": 0,
        "verdict": "PASS",
    }


def find_unique_source_line(
    path: Path,
    *,
    expected_stripped: str,
    label: str,
) -> tuple[int, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        fail(f"{label} source is not UTF-8")
    matches = [
        (index, line)
        for index, line in enumerate(lines, start=1)
        if line.strip() == expected_stripped
    ]
    if len(matches) != 1:
        fail(f"{label} declaration is missing or ambiguous")
    return matches[0]


def make_line_allowlist_entry(
    *,
    entry_id: str,
    path: Path,
    path_kind: str,
    line_number: int,
    line: str,
    literal: str,
    reason: str,
    path_display: str = "",
    realpath_display: str = "",
) -> dict[str, Any]:
    if deny_match_count(line) < 1:
        fail(f"allowlist entry {entry_id} does not address a deny hit")
    if literal not in line:
        fail(f"allowlist entry {entry_id} literal is not exact")
    return {
        "entryId": entry_id,
        "addressType": "line",
        "pathKind": path_kind,
        "path": path_display
        or (
            path.relative_to(REPOSITORY_ROOT).as_posix()
            if path_kind == "repo-relative"
            else path.as_posix()
        ),
        "realpath": realpath_display or path.resolve(strict=True).as_posix(),
        "lineNumber": line_number,
        "lineSha256": sha256_text(line),
        "literalSha256": sha256_text(literal),
        "owner": ALLOWLIST_OWNER,
        "reason": reason,
        "expiresAt": ALLOWLIST_EXPIRES_AT,
    }


def build_line_allowlist(
    *,
    report_path: Path,
    report_text: str,
    report_run_relative: str,
    report_final_realpath: str,
) -> dict[str, Any]:
    producer_path = Path(__file__).resolve(strict=True)
    marker_line_number, marker_line = find_unique_source_line(
        producer_path,
        expected_stripped=f'PASS_MARKER = "{PASS_MARKER}"',
        label="marker",
    )
    producer_lines = producer_path.read_text(encoding="utf-8").splitlines()
    regex_matches = [
        (index, line)
        for index, line in enumerate(producer_lines, start=1)
        if line.startswith("DENY_PATTERN = re.compile(")
        and "# deny-regex-declaration:" in line
    ]
    if len(regex_matches) != 1:
        fail("deny-regex declaration is missing or ambiguous")
    regex_line_number, regex_line = regex_matches[0]
    if "DENY_PATTERN = re.compile(" not in regex_line:
        fail("deny-regex declaration is not the executable matcher")

    report_lines = report_text.splitlines()
    expected_context = context_line_from_report(report_text)
    context_matches = [
        (index, line)
        for index, line in enumerate(report_lines, start=1)
        if line == expected_context
    ]
    marker_matches = [
        (index, line)
        for index, line in enumerate(report_lines, start=1)
        if line == PASS_MARKER
    ]
    if len(context_matches) != 1 or len(marker_matches) != 1:
        fail("primary report control lines are missing or ambiguous")
    report_context_number, report_context_line = context_matches[0]
    report_marker_number, report_marker_line = marker_matches[0]

    entries = [
        make_line_allowlist_entry(
            entry_id="marker-declaration",
            path=producer_path,
            path_kind="repo-relative",
            line_number=marker_line_number,
            line=marker_line,
            literal=PASS_MARKER,
            reason="exact-marker-declaration",
        ),
        make_line_allowlist_entry(
            entry_id="deny-regex-declaration",
            path=producer_path,
            path_kind="repo-relative",
            line_number=regex_line_number,
            line=regex_line,
            literal="payroll",
            reason="executable-deny-regex-declaration",
        ),
        make_line_allowlist_entry(
            entry_id="report-evidence-id-declaration",
            path=report_path,
            path_kind="run-relative",
            line_number=report_context_number,
            line=report_context_line,
            literal=EVIDENCE_ID,
            reason="exact-verifier-context-line",
            path_display=report_run_relative,
            realpath_display=report_final_realpath,
        ),
        make_line_allowlist_entry(
            entry_id="report-marker-line",
            path=report_path,
            path_kind="run-relative",
            line_number=report_marker_number,
            line=report_marker_line,
            literal=PASS_MARKER,
            reason="exact-report-marker-line",
            path_display=report_run_relative,
            realpath_display=report_final_realpath,
        ),
    ]
    return {
        "schemaVersion": 1,
        "nodeType": "line-addressed-minimum-allowlist",
        "policy": (
            "Each exception binds one exact realpath, line, line digest, "
            "literal digest, owner, reason and expiry; no file-wide entry exists."
        ),
        "entryCount": len(entries),
        "entries": entries,
    }


def context_line_from_report(report_text: str) -> str:
    lines = report_text.splitlines()
    matching = [line for line in lines if line.startswith(CONTEXT_PREFIX + " ")]
    if len(matching) != 1:
        fail("primary report does not contain one exact evidence context line")
    return matching[0]


def validate_line_allowlist(
    allowlist: Mapping[str, Any],
    *,
    staged_realpaths: Mapping[str, Path] | None = None,
) -> dict[str, Any]:
    policy = (
        "Each exception binds one exact realpath, line, line digest, "
        "literal digest, owner, reason and expiry; no file-wide entry exists."
    )
    if (
        type(allowlist.get("schemaVersion")) is not int
        or allowlist.get("schemaVersion") != 1
    ):
        fail("line-addressed allowlist schema version is invalid")
    if allowlist.get("nodeType") != "line-addressed-minimum-allowlist":
        fail("line-addressed allowlist node type is invalid")
    if allowlist.get("policy") != policy:
        fail("line-addressed allowlist policy is invalid")
    expected_entries = {
        "marker-declaration": {
            "literal": PASS_MARKER,
            "literalSha256": sha256_text(PASS_MARKER),
            "reason": "exact-marker-declaration",
            "pathKind": "repo-relative",
            "path": "scripts/qa/verify_wave3_payroll_zero.py",
        },
        "deny-regex-declaration": {
            "literal": "payroll",
            "literalSha256": sha256_text("payroll"),
            "reason": "executable-deny-regex-declaration",
            "pathKind": "repo-relative",
            "path": "scripts/qa/verify_wave3_payroll_zero.py",
        },
        "report-evidence-id-declaration": {
            "literal": EVIDENCE_ID,
            "literalSha256": sha256_text(EVIDENCE_ID),
            "reason": "exact-verifier-context-line",
            "pathKind": "run-relative",
            "path": (
                "leaves/payroll-zero/raw/"
                "zero-discoverability-report.log"
            ),
        },
        "report-marker-line": {
            "literal": PASS_MARKER,
            "literalSha256": sha256_text(PASS_MARKER),
            "reason": "exact-report-marker-line",
            "pathKind": "run-relative",
            "path": (
                "leaves/payroll-zero/raw/"
                "zero-discoverability-report.log"
            ),
        },
    }
    expected_order = tuple(expected_entries)
    entries = require_array(allowlist.get("entries"), "line allowlist")
    if (
        type(allowlist.get("entryCount")) is not int
        or allowlist.get("entryCount") != 4
        or len(entries) != 4
    ):
        fail("line-addressed allowlist is not the strict four-entry set")
    entry_ids: set[str] = set()
    hit_count = 0
    for index, raw_entry in enumerate(entries):
        entry = require_object(raw_entry, f"allowlist entry[{index}]")
        entry_id = require_string(entry.get("entryId"), "allowlist entry ID")
        if entry_id != expected_order[index]:
            fail("line-addressed allowlist ID/order is invalid")
        if entry_id in entry_ids:
            fail("line-addressed allowlist contains duplicate IDs")
        entry_ids.add(entry_id)
        expected_entry = expected_entries.get(entry_id)
        if expected_entry is None:
            fail("line-addressed allowlist contains an unexpected entry ID")
        if entry.get("addressType") != "line":
            fail("line-addressed allowlist contains a non-line entry")
        for key, expected_value in expected_entry.items():
            if key == "literal":
                continue
            if entry.get(key) != expected_value:
                fail(
                    "line-addressed allowlist entry "
                    f"{entry_id} has invalid {key}"
                )
        realpath = Path(
            require_string(entry.get("realpath"), "allowlist realpath")
        )
        if not realpath.is_absolute():
            fail("allowlist realpath must be absolute")
        if entry_id in {"marker-declaration", "deny-regex-declaration"}:
            expected_realpath = Path(__file__).resolve(strict=True)
            if realpath != expected_realpath:
                fail("source allowlist realpath is not the producer")
        else:
            runs_root = (
                REPOSITORY_ROOT / "docs/verification/wave3/runs"
            ).resolve(strict=True)
            candidate = realpath.resolve(strict=False)
            if not is_within(candidate, runs_root):
                fail("report allowlist realpath is outside the W3 run root")
            run_relative = candidate.relative_to(runs_root)
            if (
                len(run_relative.parts) != 5
                or run_relative.parts[1:]
                != (
                    "leaves",
                    "payroll-zero",
                    "raw",
                    "zero-discoverability-report.log",
                )
            ):
                fail("report allowlist realpath is not the exact artifact")
        staged_path = (staged_realpaths or {}).get(realpath.as_posix())
        if staged_path is not None:
            resolved = assert_regular_file_no_symlink(
                staged_path, f"allowlist entry {entry_id} staged file"
            )
        else:
            resolved = assert_regular_file_no_symlink(
                realpath, f"allowlist entry {entry_id} file"
            )
            if resolved.as_posix() != realpath.as_posix():
                fail("allowlist realpath is indirect")
        line_number = entry.get("lineNumber")
        if type(line_number) is not int or line_number < 1:
            fail("allowlist line number is invalid")
        try:
            lines = resolved.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeError):
            fail("allowlist target is not UTF-8")
        if line_number > len(lines):
            fail("allowlist line number is out of range")
        line = lines[line_number - 1]
        literal = str(expected_entry["literal"])
        if literal not in line:
            fail("allowlist target line lacks its exact bound literal")
        if entry_id == "marker-declaration":
            expected_number, expected_line = find_unique_source_line(
                Path(__file__).resolve(strict=True),
                expected_stripped=f'PASS_MARKER = "{PASS_MARKER}"',
                label="marker",
            )
        elif entry_id == "deny-regex-declaration":
            candidates = [
                (line_index, source_line)
                for line_index, source_line in enumerate(lines, start=1)
                if source_line.startswith("DENY_PATTERN = re.compile(")
                and "# deny-regex-declaration:" in source_line
            ]
            if len(candidates) != 1:
                fail("deny-regex allowlist target is missing or ambiguous")
            expected_number, expected_line = candidates[0]
        elif entry_id == "report-evidence-id-declaration":
            expected_line = context_line_from_report("\n".join(lines) + "\n")
            expected_number = lines.index(expected_line) + 1
        else:
            marker_lines = [
                (line_index, report_line)
                for line_index, report_line in enumerate(lines, start=1)
                if report_line == PASS_MARKER
            ]
            if len(marker_lines) != 1:
                fail("report marker allowlist target is missing or ambiguous")
            expected_number, expected_line = marker_lines[0]
        if line_number != expected_number or line != expected_line:
            fail("allowlist entry does not bind its unique expected line")
        if entry.get("lineSha256") != sha256_text(line):
            fail("allowlist line SHA256 is stale")
        matches = deny_match_count(line)
        if matches < 1:
            fail("allowlist target no longer contains a deny token")
        hit_count += matches
        if entry.get("owner") != ALLOWLIST_OWNER:
            fail("allowlist governance metadata is invalid")
        if parse_utc(
            require_string(entry.get("expiresAt"), "allowlist expiry"),
            "allowlist expiry",
        ) <= datetime.now(timezone.utc):
            fail("line-addressed allowlist is expired")
    if entry_ids != set(expected_entries):
        fail("line-addressed allowlist is not the exact control-plane set")
    return {
        "entryCount": len(entries),
        "allowlistedNormalizedHitCount": hit_count,
        "verdict": "PASS",
    }


def sensitive_runtime_values(
    runtime_environment: Mapping[str, str],
    login_environment: Mapping[str, str],
    principals: Sequence[Mapping[str, Any]],
) -> tuple[str, ...]:
    values: set[str] = {
        value
        for key, value in runtime_environment.items()
        if key.endswith("_PASSWORD") and value
    }
    login_password = login_environment.get("SHENZHOUHR_LOGIN_PASSWORD")
    if login_password:
        values.add(login_password)
    for principal in principals:
        for key in ("password",):
            value = principal.get(key)
            if isinstance(value, str) and value:
                values.add(value)
        session = principal.get("session")
        if isinstance(session, ApiSession):
            if session.cookie:
                values.add(session.cookie)
            if session.csrf_token:
                values.add(session.csrf_token)
    return tuple(sorted(values, key=lambda value: value.encode("utf-8")))


def assert_no_sensitive_artifact_value(
    contents: bytes,
    decoded_strings: Sequence[str],
    sensitive_values: Sequence[str],
    label: str,
) -> None:
    for sensitive in sensitive_values:
        encoded = sensitive.encode("utf-8")
        digest = sha256_text(sensitive).encode("ascii")
        if encoded in contents or digest in contents:
            fail(f"{label} contains a private runtime value or its digest")
        if any(sensitive in value for value in decoded_strings):
            fail(f"{label} contains a decoded private runtime value")


def freeze_exact_artifact_bytes(
    root: Path,
    expected_bytes: Mapping[str, bytes],
    sensitive_values: Sequence[str],
) -> tuple[dict[str, Any], dict[str, bytes]]:
    expected_names = tuple(sorted(expected_bytes))
    try:
        actual_entries = tuple(
            sorted(path.name for path in root.iterdir())
        )
    except OSError:
        fail("evidence artifact root is unavailable")
    if actual_entries != expected_names:
        fail("evidence artifact set differs from the exact six roles")
    records: list[dict[str, Any]] = []
    contents_by_name: dict[str, bytes] = {}
    for name in expected_names:
        path = root / name
        contents, metadata, _ = stable_file_bytes(
            path, f"evidence artifact {name}"
        )
        if contents != expected_bytes[name]:
            fail(f"staged evidence artifact bytes changed: {name}")
        try:
            text = contents.decode("utf-8", errors="strict")
        except UnicodeError:
            fail(f"evidence artifact is not strict UTF-8: {name}")
        decoded_strings: list[str] = [text]
        if name.endswith(".json"):
            document = strict_json_loads(
                text, f"evidence artifact {name}"
            )
            decoded_strings.extend(
                value for _, _, value in iter_json_strings(document)
            )
        assert_no_sensitive_artifact_value(
            contents, decoded_strings, sensitive_values, name
        )
        contents_by_name[name] = contents
        records.append(
            {
                "path": name,
                "device": metadata.st_dev,
                "inode": metadata.st_ino,
                "mode": stat.S_IMODE(metadata.st_mode),
                "sizeBytes": len(contents),
                "mtimeEpochNs": metadata.st_mtime_ns,
                "ctimeEpochNs": metadata.st_ctime_ns,
                "sha256": sha256_bytes(contents),
            }
        )
    return (
        {
            "fileCount": len(records),
            "treeSha256": sha256_bytes(
                b"".join(canonical_json_bytes(record) for record in records)
            ),
            "records": records,
        },
        contents_by_name,
    )


def validate_exact_evidence_artifacts(
    *,
    root: Path,
    expected_bytes: Mapping[str, bytes],
    sensitive_values: Sequence[str],
    context: Mapping[str, Any],
    final_root: Path,
) -> dict[str, Any]:
    seal, contents = freeze_exact_artifact_bytes(
        root, expected_bytes, sensitive_values
    )
    documents = {
        name: require_object(
            strict_json_loads(
                contents[name].decode("utf-8"),
                f"sealed artifact {name}",
            ),
            f"sealed artifact {name}",
        )
        for name in expected_bytes
        if name.endswith(".json")
    }
    primary = contents["zero-discoverability-report.log"].decode("utf-8")
    if (
        primary.count(context_line(context)) != 1
        or primary.count(PASS_MARKER) != 1
        or FORBIDDEN_STATUS_TOKEN in primary
    ):
        fail("sealed primary report schema/context is invalid")
    scope_manifest = require_exact_keys(
        documents["scan-scope-manifest.json"],
        {
            "schemaVersion",
            "nodeType",
            "verdict",
            "runId",
            "sourceTreeHash",
            "databaseIdentity",
            "normalization",
            "scopePolicy",
            "excludedNonProductScopes",
            "freshBuildChecks",
            "prodDistSha256",
            "demoDistSha256",
            "scopeCount",
            "productFileCount",
            "scopes",
        },
        "sealed scope manifest",
    )
    normalized = require_exact_keys(
        documents["normalized-scan-output.json"],
        {
            "schemaVersion",
            "nodeType",
            "verdict",
            "runId",
            "sourceTreeHash",
            "databaseIdentity",
            "normalizer",
            "denyMatcherSha256",
            "normalizationSelfTest",
            "productScan",
            "runtimeScan",
            "controlPlaneAllowlistScan",
            "unscannedRequiredRootCount",
        },
        "sealed normalized scan output",
    )
    runtime = documents["normal-role-runtime-probes.json"]
    exclusions = documents["request-target-exclusions.json"]
    excluded_pointers = validate_request_target_exclusions(
        runtime,
        exclusions,
        expected_artifact_realpath=(
            final_root / "normal-role-runtime-probes.json"
        ).as_posix(),
    )
    runtime_scan = scan_runtime_document(runtime, excluded_pointers)
    if canonical_json_bytes(runtime_scan) != canonical_json_bytes(
        normalized["runtimeScan"]
    ):
        fail("sealed runtime deny scan differs from normalized evidence")
    allowlist = documents["line-addressed-allowlist.json"]
    allowlist_scan = validate_line_allowlist(
        allowlist,
        staged_realpaths={
            (
                final_root / "zero-discoverability-report.log"
            ).as_posix(): root / "zero-discoverability-report.log"
        }
        if root != final_root
        else None,
    )
    if canonical_json_bytes(allowlist_scan) != canonical_json_bytes(
        normalized["controlPlaneAllowlistScan"]
    ):
        fail("sealed allowlist scan differs from normalized evidence")
    artifact_documents_have_exact_context(
        (
            scope_manifest,
            exclusions,
            allowlist,
            normalized,
            runtime,
        ),
        context,
    )
    return seal


def darwin_rename_exclusive(
    source_directory_fd: int,
    source_name: str,
    destination_directory_fd: int,
    destination_name: str,
) -> None:
    if sys.platform != "darwin":
        fail("atomic exclusive evidence publication requires Darwin")
    if (
        not source_name
        or not destination_name
        or "/" in source_name
        or "/" in destination_name
        or source_name in {".", ".."}
        or destination_name in {".", ".."}
    ):
        fail("atomic exclusive rename received an unsafe entry name")
    c_library_path = ctypes.util.find_library("c")
    if not c_library_path:
        fail("Darwin C library is unavailable for atomic publication")
    c_library = ctypes.CDLL(c_library_path, use_errno=True)
    try:
        renameatx_np = c_library.renameatx_np
    except AttributeError:
        fail("Darwin renameatx_np is unavailable")
    renameatx_np.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameatx_np.restype = ctypes.c_int
    ctypes.set_errno(0)
    result = renameatx_np(
        source_directory_fd,
        os.fsencode(source_name),
        destination_directory_fd,
        os.fsencode(destination_name),
        DARWIN_RENAME_EXCL,
    )
    if result == 0:
        return
    error_number = ctypes.get_errno()
    if error_number in {errno.EEXIST, errno.ENOTEMPTY}:
        fail("evidence output appeared during atomic publication")
    if error_number == errno.EXDEV:
        fail("evidence staging/output are not on one filesystem")
    if error_number <= 0:
        fail("renameatx_np failed without a valid errno")
    fail(
        "renameatx_np failed closed: "
        f"errno={error_number} ({os.strerror(error_number)})"
    )


def directory_open_flags() -> int:
    return (
        os.O_RDONLY
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )


def filesystem_identity(metadata: os.stat_result) -> tuple[int, int]:
    return metadata.st_dev, metadata.st_ino


def open_identity_bound_directory_at(
    parent_fd: int,
    name: str,
    expected_identity: tuple[int, int],
    label: str,
) -> int:
    try:
        before = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        fail(f"{label} disappeared")
    if (
        not stat.S_ISDIR(before.st_mode)
        or stat.S_ISLNK(before.st_mode)
        or filesystem_identity(before) != expected_identity
    ):
        fail(f"{label} identity changed; foreign replacement preserved")
    try:
        descriptor = os.open(
            name,
            directory_open_flags(),
            dir_fd=parent_fd,
        )
    except OSError:
        fail(f"{label} could not be opened without following links")
    after = os.fstat(descriptor)
    if (
        not stat.S_ISDIR(after.st_mode)
        or filesystem_identity(after) != expected_identity
        or filesystem_identity(after) != filesystem_identity(before)
    ):
        os.close(descriptor)
        fail(f"{label} identity changed while opening")
    return descriptor


def move_identity_bound_directory_to_recovery_at(
    parent_fd: int,
    name: str,
    expected_identity: tuple[int, int],
    label: str,
) -> tuple[str, bool]:
    """Move one exact directory to durable recovery without deleting bytes."""
    try:
        before = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        fail(f"{label} disappeared before recovery")
    if (
        not stat.S_ISDIR(before.st_mode)
        or stat.S_ISLNK(before.st_mode)
        or filesystem_identity(before) != expected_identity
    ):
        fail(f"{label} identity changed; foreign replacement preserved")

    recovery_name = f".zero-recovery-{os.urandom(24).hex()}"
    darwin_rename_exclusive(
        parent_fd,
        name,
        parent_fd,
        recovery_name,
    )
    try:
        moved = os.stat(
            recovery_name,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
    except FileNotFoundError:
        fail(f"{label} disappeared after recovery rename")
    if (
        not stat.S_ISDIR(moved.st_mode)
        or stat.S_ISLNK(moved.st_mode)
        or filesystem_identity(moved) != expected_identity
    ):
        restored = False
        try:
            darwin_rename_exclusive(
                parent_fd,
                recovery_name,
                parent_fd,
                name,
            )
            restored = True
        except ZeroDiscoverabilityError:
            pass
        location = (
            "at its original name"
            if restored
            else f"under recovery name {recovery_name}"
        )
        fail(
            f"{label} identity changed during recovery rename; "
            f"foreign replacement preserved {location}"
        )
    descriptor = open_identity_bound_directory_at(
        parent_fd,
        recovery_name,
        expected_identity,
        label,
    )
    try:
        os.fchmod(descriptor, 0o700)
        after_chmod = os.fstat(descriptor)
        if (
            not stat.S_ISDIR(after_chmod.st_mode)
            or filesystem_identity(after_chmod) != expected_identity
        ):
            fail(f"{label} identity changed while securing recovery")
        os.fsync(descriptor)
    finally:
        os.close(descriptor)

    recovered = os.stat(
        recovery_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    if (
        not stat.S_ISDIR(recovered.st_mode)
        or stat.S_ISLNK(recovered.st_mode)
        or filesystem_identity(recovered) != expected_identity
        or stat.S_IMODE(recovered.st_mode) != 0o700
    ):
        fail(f"{label} recovery identity changed; all entries preserved")
    source_replaced = False
    try:
        os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        pass
    else:
        source_replaced = True
    os.fsync(parent_fd)
    return recovery_name, source_replaced


class AtomicOutput:
    def __init__(self, context: Mapping[str, Any], requested: Path):
        if not requested.is_absolute():
            fail("--output-dir must be absolute")
        run_root = Path(context["runRoot"]).resolve(strict=True)
        final_path = requested.resolve(strict=False)
        expected = run_root / "leaves/payroll-zero/raw"
        if final_path != expected:
            fail("--output-dir must be exactly leaves/payroll-zero/raw")
        if final_path.exists() or final_path.is_symlink():
            fail("refusing to overwrite an existing evidence output directory")
        if (expected.parent / "leaf.json").exists():
            fail("the zero-discoverability leaf is already registered")
        expected.parent.mkdir(parents=True, exist_ok=True)
        assert_no_symlink_path(run_root, expected.parent)
        try:
            self._parent_fd = os.open(
                expected.parent,
                directory_open_flags(),
            )
        except OSError:
            fail("evidence output parent could not be opened safely")
        self._parent_metadata = os.fstat(self._parent_fd)
        self._parent_path = expected.parent
        self.final_path = final_path
        try:
            parent_names = os.listdir(self._parent_fd)
        except OSError:
            self._close_parent_fd()
            fail("evidence output parent could not be inspected safely")
        if any(name.startswith(".zero-recovery-") for name in parent_names):
            self._close_parent_fd()
            fail(
                "recoverable failed output exists; use a fresh run or "
                "manually recover and clean it before retrying"
            )
        self.recovery_paths: list[Path] = []
        try:
            self.temporary_path = Path(
                tempfile.mkdtemp(prefix=".zero-tmp-", dir=expected.parent)
            )
        except BaseException:
            os.close(self._parent_fd)
            self._parent_fd = -1
            raise
        stage_metadata = self.temporary_path.lstat()
        if (
            not stat.S_ISDIR(stage_metadata.st_mode)
            or stat.S_ISLNK(stage_metadata.st_mode)
            or stage_metadata.st_dev != self._parent_metadata.st_dev
        ):
            self._close_parent_fd()
            fail("evidence staging directory is unsafe or cross-filesystem")
        self._stage_identity = filesystem_identity(stage_metadata)
        self._committed_identity: tuple[int, int] | None = None
        self.committed = False

    def _close_parent_fd(self) -> None:
        if getattr(self, "_parent_fd", -1) >= 0:
            os.close(self._parent_fd)
            self._parent_fd = -1

    def _validate_parent_identity(self, descriptor: int) -> None:
        current = os.fstat(descriptor)
        if filesystem_identity(current) != filesystem_identity(
            self._parent_metadata
        ):
            fail("evidence output parent identity changed")

    def _validate_parent_namespace(self) -> None:
        try:
            current = self._parent_path.lstat()
        except OSError:
            fail("evidence output parent namespace disappeared")
        if (
            not stat.S_ISDIR(current.st_mode)
            or stat.S_ISLNK(current.st_mode)
            or filesystem_identity(current)
            != filesystem_identity(self._parent_metadata)
        ):
            fail("evidence output parent namespace identity changed")

    def _open_parent_for_rollback(self) -> int:
        try:
            descriptor = os.open(
                self._parent_path,
                directory_open_flags(),
            )
        except OSError:
            fail("evidence output parent is unavailable for rollback")
        try:
            self._validate_parent_identity(descriptor)
            self._validate_parent_namespace()
        except BaseException:
            os.close(descriptor)
            raise
        return descriptor

    def _record_recovery_at(
        self,
        parent_fd: int,
        name: str,
        identity: tuple[int, int],
        label: str,
    ) -> Path:
        recovery_name, source_replaced = (
            move_identity_bound_directory_to_recovery_at(
                parent_fd,
                name,
                identity,
                label,
            )
        )
        recovery_path = self._parent_path / recovery_name
        self.recovery_paths.append(recovery_path)
        self._validate_parent_namespace()
        if source_replaced:
            fail(
                f"{label} source path was replaced; foreign replacement "
                f"preserved and owned output retained at {recovery_path}"
            )
        return recovery_path

    def _recover_owned_directory(
        self,
        name: str,
        identity: tuple[int, int],
        label: str,
        *,
        missing_ok: bool,
    ) -> Path | None:
        parent_fd = self._open_parent_for_rollback()
        try:
            try:
                os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
            except FileNotFoundError:
                if missing_ok:
                    return None
                fail(f"{label} disappeared before recovery")
            recovery_path = self._record_recovery_at(
                parent_fd,
                name,
                identity,
                label,
            )
            self._validate_parent_namespace()
            return recovery_path
        finally:
            os.close(parent_fd)

    def commit(self) -> None:
        if self._parent_fd < 0:
            fail("evidence output parent descriptor is closed")
        try:
            current_parent = os.fstat(self._parent_fd)
            self._validate_parent_identity(self._parent_fd)
            self._validate_parent_namespace()
            stage_metadata = os.stat(
                self.temporary_path.name,
                dir_fd=self._parent_fd,
                follow_symlinks=False,
            )
            if (
                not stat.S_ISDIR(stage_metadata.st_mode)
                or (stage_metadata.st_dev, stage_metadata.st_ino)
                != self._stage_identity
                or stage_metadata.st_dev != current_parent.st_dev
            ):
                fail("evidence staging directory identity changed")
            stage_fd = os.open(
                self.temporary_path.name,
                directory_open_flags(),
                dir_fd=self._parent_fd,
            )
            try:
                os.fsync(stage_fd)
            finally:
                os.close(stage_fd)
            os.fsync(self._parent_fd)
            darwin_rename_exclusive(
                self._parent_fd,
                self.temporary_path.name,
                self._parent_fd,
                self.final_path.name,
            )
            self.committed = True
            self._committed_identity = self._stage_identity
            try:
                os.fsync(self._parent_fd)
            except OSError as durability_error:
                recovery_count = len(self.recovery_paths)
                try:
                    recovery_path = self._record_recovery_at(
                        self._parent_fd,
                        self.final_path.name,
                        self._stage_identity,
                        "committed evidence after final fsync failure",
                    )
                    self._validate_parent_namespace()
                except BaseException as recovery_error:
                    if len(self.recovery_paths) > recovery_count:
                        self.committed = False
                        self._committed_identity = None
                    fail(
                        "atomic evidence publication final parent fsync failed "
                        "and no-delete recovery did not close cleanly; "
                        f"fsyncErrno={durability_error.errno}; "
                        f"recovery={recovery_error}"
                    )
                self.committed = False
                self._committed_identity = None
                fail(
                    "atomic evidence publication final parent fsync failed; "
                    "renamed output was retained as non-PASS recovery at "
                    f"{recovery_path}"
                )
        except OSError as error:
            fail(f"atomic evidence publication failed: errno={error.errno}")
        finally:
            self._close_parent_fd()

    def cleanup(self) -> None:
        self._close_parent_fd()
        self._recover_owned_directory(
            self.temporary_path.name,
            self._stage_identity,
            "evidence staging directory cleanup",
            missing_ok=True,
        )

    def rollback_committed(self) -> None:
        self._close_parent_fd()
        if not self.committed:
            return
        if self._committed_identity is None:
            fail("committed evidence identity is unavailable for rollback")
        recovery_count = len(self.recovery_paths)
        try:
            self._recover_owned_directory(
                self.final_path.name,
                self._committed_identity,
                "committed evidence",
                missing_ok=False,
            )
        finally:
            if len(self.recovery_paths) > recovery_count:
                self.committed = False
                self._committed_identity = None


def public_role_document(principal: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "roleCode": principal["roleCode"],
        "roleId": principal["roleId"],
        "roleName": principal["roleName"],
        "accountIdSha256": sha256_text(principal["accountId"]),
        "usernameSha256": sha256_text(principal["username"]),
        "accountCreatedInThisRun": principal["created"],
        "accountAssignmentRepairedInThisRun": principal["repaired"],
        "capabilities": list(principal["capabilities"]),
        "menu": list(principal["menu"]),
    }


def execute_runtime_probes(
    *,
    context: Mapping[str, Any],
    runtime_environment: Mapping[str, str],
    login_environment: Mapping[str, str],
    backend_origin: str,
    frontend_origin: str,
    agent_browser_binary: Path,
    chrome_binary: Path,
    provision_synthetic_accounts: bool,
    listener_bindings: Mapping[str, Any],
    frontend_source_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    database_start = create_database_binding(runtime_environment)
    assert_database_binding(context, database_start)
    principals = load_role_principals(
        backend_origin=backend_origin,
        login_environment=login_environment,
        context=context,
        provision_synthetic_accounts=provision_synthetic_accounts,
    )
    after_principal_database, backend_database_binding = (
        create_consistent_database_snapshot(runtime_environment, principals)
    )
    assert_database_binding(context, after_principal_database)
    if canonical_json_bytes(database_start) != canonical_json_bytes(
        after_principal_database
    ):
        fail("database identity/Flyway/read-marker changed during role loading")
    if backend_database_binding is None:
        fail("role loading snapshot omitted principal database binding")
    browser = AgentBrowserRunner(
        binary=agent_browser_binary,
        chrome=chrome_binary,
        namespace=f"w3-zero-{context['runId']}",
        allowed_domains=(
            urllib.parse.urlsplit(frontend_origin).hostname or "",
            urllib.parse.urlsplit(backend_origin).hostname or "",
        ),
    )
    browser_version = browser.version()
    started_at = utc_now()
    roles: list[dict[str, Any]] = []
    contract_signatures: dict[str, str] = {}
    for principal in principals:
        role_code = principal["roleCode"]
        api_probes = [
            api_negative_probe(principal["session"], role_code, target)
            for target in API_NEGATIVE_TARGETS
        ]
        for probe in api_probes:
            target = probe["requestTarget"]
            signature = probe["nonFeatureContractSha256"]
            prior = contract_signatures.setdefault(target, signature)
            if prior != signature:
                fail(
                    "negative API contract differs by role for one request target"
                )
        proxy_binding, frontend_probes = frontend_negative_probes(
            browser=browser,
            frontend_origin=frontend_origin,
            backend_origin=backend_origin,
            principal=principal,
            context=context,
        )
        role_document = public_role_document(principal)
        role_document["frontendBackendProxyBinding"] = proxy_binding
        role_document["frontendRouteProbes"] = frontend_probes
        role_document["apiProbes"] = api_probes
        role_document["verdict"] = "PASS"
        roles.append(role_document)
    database_end, ending_principal_binding = (
        create_consistent_database_snapshot(runtime_environment, principals)
    )
    assert_database_binding(context, database_end)
    if canonical_json_bytes(database_start) != canonical_json_bytes(database_end):
        fail("database identity/Flyway/read-marker binding changed during probes")
    if canonical_json_bytes(
        backend_database_binding
    ) != canonical_json_bytes(ending_principal_binding):
        fail("backend/API principal database binding changed during probes")
    ended_at = utc_now()
    document = {
        "schemaVersion": 1,
        "nodeType": "normal-role-zero-discoverability-runtime-probes",
        "verdict": "PASS",
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "transport": "normal",
        "backendOrigin": backend_origin,
        "frontendOrigin": frontend_origin,
        "roleOrder": list(ROLE_CODES),
        "roleCount": len(roles),
        "browser": {
            "tool": "agent-browser",
            "version": browser_version,
            "engine": "chrome",
        },
        "listenerBindings": dict(listener_bindings),
        "frontendSourceBinding": dict(frontend_source_binding),
        "databaseStart": database_start,
        "backendDatabaseBinding": backend_database_binding,
        "databaseEnd": database_end,
        "startedAt": started_at,
        "endedAt": ended_at,
        "roles": roles,
    }
    return document, principals


def build_scope_manifest(
    *,
    context: Mapping[str, Any],
    scopes: Mapping[str, Sequence[Path]],
    product_records: Sequence[Mapping[str, Any]],
    freshness: Mapping[str, Any],
) -> dict[str, Any]:
    records_by_key = {
        (str(record["scopeId"]), str(record["path"])): dict(record)
        for record in product_records
    }
    scope_documents: list[dict[str, Any]] = []
    for scope_id, files in scopes.items():
        records: list[dict[str, Any]] = []
        framed: list[bytes] = []
        for path in files:
            relative = unicodedata.normalize(
                "NFC", path.relative_to(REPOSITORY_ROOT).as_posix()
            )
            record = records_by_key.get((scope_id, relative))
            if record is None:
                fail("scope manifest lacks the exact scanned file snapshot")
            relative_bytes = relative.encode("utf-8")
            framed.append(
                str(len(relative_bytes)).encode("ascii")
                + b":"
                + relative_bytes
                + b"|"
                + str(record["sizeBytes"]).encode("ascii")
                + b"|"
                + str(record["sha256"]).encode("ascii")
            )
            records.append(
                {
                    "path": relative,
                    "sizeBytes": record["sizeBytes"],
                    "sha256": record["sha256"],
                    "mtimeEpochNs": record["mtimeEpochNs"],
                }
            )
        mtimes = [int(record["mtimeEpochNs"]) for record in records]
        scope_documents.append(
            {
                "scopeId": scope_id,
                "rootPolicy": (
                    "repo-relative regular non-symlink strict UTF-8 files"
                ),
                "fileCount": len(records),
                "treeSha256": sha256_bytes(b"".join(framed)),
                "minimumMtimeEpochNs": min(mtimes),
                "maximumMtimeEpochNs": max(mtimes),
                "files": records,
            }
        )
    prod = next(
        scope for scope in scope_documents if scope["scopeId"] == "frontend-prod-dist"
    )
    demo = next(
        scope for scope in scope_documents if scope["scopeId"] == "frontend-demo-dist"
    )
    return {
        "schemaVersion": 1,
        "nodeType": "product-discoverability-scan-scope",
        "verdict": "PASS",
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "normalization": "Unicode NFKC then full casefold",
        "scopePolicy": (
            "Only product/user-discoverable frontend source and both current "
            "builds, OpenAPI, REST/interface/DTO source, and live normal "
            "runtime outputs are product scan inputs."
        ),
        "excludedNonProductScopes": [
            {
                "scope": "frontend unit/spec fixtures",
                "reason": "test-only code is not shipped in either build",
            },
            {
                "scope": "backend application/domain/persistence internals",
                "reason": (
                    "the binding requirement names REST controllers, "
                    "interfaces, routes and DTOs"
                ),
            },
            {
                "scope": "OpenSpec, PRD, QA history and invalidated runs",
                "reason": (
                    "governance/history text is not a product discovery surface"
                ),
            },
        ],
        "freshBuildChecks": dict(freshness),
        "prodDistSha256": prod["treeSha256"],
        "demoDistSha256": demo["treeSha256"],
        "scopeCount": len(scope_documents),
        "productFileCount": len(product_records),
        "scopes": scope_documents,
    }


def build_primary_report(
    *,
    context: Mapping[str, Any],
    product_file_count: int,
    product_line_count: int,
    runtime_role_count: int,
    prod_dist_sha256: str,
    demo_dist_sha256: str,
) -> str:
    lines = [
        context_line(context),
        (
            "W3_ZERO_STATIC_SCOPE=PASS "
            f"files={product_file_count} lines={product_line_count}"
        ),
        (
            "W3_ZERO_BUILD_HASHES=PASS "
            f"prod={prod_dist_sha256} demo={demo_dist_sha256}"
        ),
        (
            "W3_ZERO_NORMAL_RUNTIME=PASS "
            f"roles={runtime_role_count} frontendRoutes=6 apiRoutes=6"
        ),
        "W3_ZERO_EXCLUSIONS=PASS requestTargets=12 lineAllowlistEntries=4",
        PASS_MARKER,
        "",
    ]
    report = "\n".join(lines)
    if report.count(context_line(context)) != 1:
        fail("primary report exact context line count is invalid")
    if report.count(PASS_MARKER) != 1:
        fail("primary report exact marker count is invalid")
    if FORBIDDEN_STATUS_TOKEN in report:
        fail("primary report contains a forbidden status token")
    return report


def artifact_documents_have_exact_context(
    documents: Sequence[Mapping[str, Any]],
    context: Mapping[str, Any],
) -> None:
    for index, document in enumerate(documents):
        assert_no_null_or_forbidden(document, f"artifact document[{index}]")
        for key, expected in (
            ("runId", context["runId"]),
            ("sourceTreeHash", context["sourceTreeHash"]),
            ("databaseIdentity", context["databaseIdentity"]),
        ):
            if key in document and document[key] != expected:
                fail(f"artifact document[{index}] {key} mismatch")


def execute_producer(
    *,
    run_context: Path,
    runtime_env: Path,
    login_env: Path,
    backend_url: str,
    frontend_url: str,
    output_dir: Path,
    agent_browser_bin: Path = DEFAULT_AGENT_BROWSER,
    chrome_bin: Path = DEFAULT_CHROME,
    provision_synthetic_accounts: bool = False,
) -> Path:
    require_repository_root()
    self_test = normalization_self_test()
    context = load_run_context(run_context)
    assert_frozen_source(context)
    runtime_environment = read_runtime_environment(
        runtime_env, context["databaseIdentity"]
    )
    login_environment = read_login_environment(login_env)
    backend_origin = validate_loopback_origin(backend_url, "backend URL")
    frontend_origin = validate_loopback_origin(frontend_url, "frontend URL")
    if backend_origin != "http://127.0.0.1:8080":
        fail("normal backend origin must be exactly http://127.0.0.1:8080")
    if frontend_origin != "http://127.0.0.1:5173":
        fail("normal frontend origin must be exactly http://127.0.0.1:5173")
    output = AtomicOutput(context, output_dir)
    try:
        scopes = collect_product_scope_files()
        freshness = validate_fresh_dist(context, scopes)
        product_records, product_line_count = scan_product_scopes(scopes)
        backend_listener_binding = listener_process_binding(
            backend_origin,
            component="backend",
            context=context,
            runtime_environment=runtime_environment,
        )
        backend_class_snapshot = backend_listener_binding.pop(
            "_privateRuntimeClassSnapshot"
        )
        backend_process_snapshot = backend_listener_binding.pop(
            "_privateProcessBinding"
        )
        frontend_listener_binding = listener_process_binding(
            frontend_origin, component="frontend", context=context
        )
        frontend_process_snapshot = frontend_listener_binding.pop(
            "_privateProcessBinding"
        )
        listener_bindings = {
            "backend": backend_listener_binding,
            "frontend": frontend_listener_binding,
        }
        frontend_source_binding = verify_frontend_source_binding(
            frontend_origin, scopes, product_records
        )
        scope_manifest = build_scope_manifest(
            context=context,
            scopes=scopes,
            product_records=product_records,
            freshness=freshness,
        )

        runtime_document, runtime_principals = execute_runtime_probes(
            context=context,
            runtime_environment=runtime_environment,
            login_environment=login_environment,
            backend_origin=backend_origin,
            frontend_origin=frontend_origin,
            agent_browser_binary=agent_browser_bin,
            chrome_binary=chrome_bin,
            provision_synthetic_accounts=provision_synthetic_accounts,
            listener_bindings=listener_bindings,
            frontend_source_binding=frontend_source_binding,
        )
        runtime_name = "normal-role-runtime-probes.json"
        runtime_staged = output.temporary_path / runtime_name
        runtime_run_relative = (
            f"leaves/payroll-zero/raw/{runtime_name}"
        )
        runtime_final = output.final_path / runtime_name
        write_exclusive(runtime_staged, pretty_json_bytes(runtime_document))

        exclusions = build_request_target_exclusions(
            runtime_document,
            runtime_artifact_path=runtime_run_relative,
            runtime_artifact_realpath=runtime_final.as_posix(),
        )
        excluded_pointers = validate_request_target_exclusions(
            runtime_document,
            exclusions,
            expected_artifact_realpath=runtime_final.as_posix(),
        )
        runtime_scan = scan_runtime_document(
            runtime_document, excluded_pointers
        )

        report_name = "zero-discoverability-report.log"
        report_staged = output.temporary_path / report_name
        report_final = output.final_path / report_name
        report_run_relative = (
            f"leaves/payroll-zero/raw/{report_name}"
        )
        report_text = build_primary_report(
            context=context,
            product_file_count=len(product_records),
            product_line_count=product_line_count,
            runtime_role_count=runtime_document["roleCount"],
            prod_dist_sha256=scope_manifest["prodDistSha256"],
            demo_dist_sha256=scope_manifest["demoDistSha256"],
        )
        write_exclusive(report_staged, report_text.encode("utf-8"))
        allowlist = build_line_allowlist(
            report_path=report_staged,
            report_text=report_text,
            report_run_relative=report_run_relative,
            report_final_realpath=report_final.as_posix(),
        )
        allowlist_scan = validate_line_allowlist(
            allowlist,
            staged_realpaths={report_final.as_posix(): report_staged},
        )

        normalized_scan_output = {
            "schemaVersion": 1,
            "nodeType": "normalized-zero-discoverability-scan-output",
            "verdict": "PASS",
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "normalizer": "Unicode NFKC then full casefold",
            "denyMatcherSha256": sha256_text(DENY_PATTERN.pattern),
            "normalizationSelfTest": self_test,
            "productScan": {
                "fileCount": len(product_records),
                "lineCount": product_line_count,
                "discoverableHitCount": 0,
                "files": product_records,
                "verdict": "PASS",
            },
            "runtimeScan": runtime_scan,
            "controlPlaneAllowlistScan": allowlist_scan,
            "unscannedRequiredRootCount": 0,
        }
        exclusions_document = {
            **exclusions,
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "verdict": "PASS",
        }
        allowlist_document = {
            **allowlist,
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "verdict": "PASS",
        }
        artifact_documents_have_exact_context(
            (
                scope_manifest,
                exclusions_document,
                allowlist_document,
                normalized_scan_output,
                runtime_document,
            ),
            context,
        )
        write_exclusive(
            output.temporary_path / "scan-scope-manifest.json",
            pretty_json_bytes(scope_manifest),
        )
        write_exclusive(
            output.temporary_path / "request-target-exclusions.json",
            pretty_json_bytes(exclusions_document),
        )
        write_exclusive(
            output.temporary_path / "line-addressed-allowlist.json",
            pretty_json_bytes(allowlist_document),
        )
        write_exclusive(
            output.temporary_path / "normalized-scan-output.json",
            pretty_json_bytes(normalized_scan_output),
        )
        expected_artifact_bytes = {
            "zero-discoverability-report.log": report_text.encode("utf-8"),
            "scan-scope-manifest.json": pretty_json_bytes(scope_manifest),
            "request-target-exclusions.json": pretty_json_bytes(
                exclusions_document
            ),
            "line-addressed-allowlist.json": pretty_json_bytes(
                allowlist_document
            ),
            "normalized-scan-output.json": pretty_json_bytes(
                normalized_scan_output
            ),
            "normal-role-runtime-probes.json": pretty_json_bytes(
                runtime_document
            ),
        }
        actual_names = tuple(
            sorted(path.name for path in output.temporary_path.iterdir())
        )
        if actual_names != tuple(sorted(ARTIFACT_FILENAMES)):
            fail("producer did not create the exact six-artifact set")
        if any(
            not path.is_file() or path.is_symlink()
            for path in output.temporary_path.iterdir()
        ):
            fail("evidence output contains a non-regular artifact")

        ending_database, ending_principal_binding = (
            create_consistent_database_snapshot(
                runtime_environment, runtime_principals
            )
        )
        assert_database_binding(context, ending_database)
        assert_final_database_seal(
            runtime_document,
            ending_database,
            ending_principal_binding,
        )

        ending_frontend_forward = verify_frontend_source_binding(
            frontend_origin, scopes, product_records
        )
        ending_frontend_reverse = verify_frontend_source_binding(
            frontend_origin,
            scopes,
            product_records,
            reverse=True,
        )
        if (
            canonical_json_bytes(ending_frontend_forward)
            != canonical_json_bytes(frontend_source_binding)
            or canonical_json_bytes(ending_frontend_reverse)
            != canonical_json_bytes(frontend_source_binding)
        ):
            fail("two-pass Vite raw source binding changed before publication")
        ending_freshness = validate_fresh_dist(context, scopes)
        if canonical_json_bytes(ending_freshness) != canonical_json_bytes(
            freshness
        ):
            fail("build receipts or dist seal changed before publication")
        assert_product_scan_snapshot_unchanged(scopes, product_records)
        assert_frozen_source(context)
        sensitive_values = sensitive_runtime_values(
            runtime_environment,
            login_environment,
            runtime_principals,
        )
        staged_seal = validate_exact_evidence_artifacts(
            root=output.temporary_path,
            expected_bytes=expected_artifact_bytes,
            sensitive_values=sensitive_values,
            context=context,
            final_root=output.final_path,
        )

        ending_backend_binding = listener_process_binding(
            backend_origin,
            component="backend",
            context=context,
            runtime_environment=runtime_environment,
        )
        ending_backend_class_snapshot = ending_backend_binding.pop(
            "_privateRuntimeClassSnapshot"
        )
        ending_backend_process_snapshot = ending_backend_binding.pop(
            "_privateProcessBinding"
        )
        ending_frontend_binding = listener_process_binding(
            frontend_origin, component="frontend", context=context
        )
        ending_frontend_process_snapshot = ending_frontend_binding.pop(
            "_privateProcessBinding"
        )
        final_freshness = validate_fresh_dist(context, scopes)
        if canonical_json_bytes(final_freshness) != canonical_json_bytes(
            freshness
        ):
            fail("final build receipt/dist seal changed before publication")
        assert_product_scan_snapshot_unchanged(scopes, product_records)
        assert_frozen_source(context)
        last_backend_binding = listener_process_binding(
            backend_origin,
            component="backend",
            context=context,
            runtime_environment=runtime_environment,
        )
        last_backend_class_snapshot = last_backend_binding.pop(
            "_privateRuntimeClassSnapshot"
        )
        last_backend_process_snapshot = last_backend_binding.pop(
            "_privateProcessBinding"
        )
        last_frontend_binding = listener_process_binding(
            frontend_origin, component="frontend", context=context
        )
        last_frontend_process_snapshot = last_frontend_binding.pop(
            "_privateProcessBinding"
        )
        if canonical_json_bytes(ending_backend_binding) != canonical_json_bytes(
            listener_bindings["backend"]
        ) or canonical_json_bytes(last_backend_binding) != canonical_json_bytes(
            listener_bindings["backend"]
        ):
            fail("backend listener binding changed before publication")
        if canonical_json_bytes(ending_frontend_binding) != canonical_json_bytes(
            listener_bindings["frontend"]
        ) or canonical_json_bytes(last_frontend_binding) != canonical_json_bytes(
            listener_bindings["frontend"]
        ):
            fail("frontend listener binding changed before publication")
        if (
            canonical_json_bytes(ending_backend_class_snapshot)
            != canonical_json_bytes(backend_class_snapshot)
            or canonical_json_bytes(last_backend_class_snapshot)
            != canonical_json_bytes(backend_class_snapshot)
        ):
            fail("backend runtime class tree changed before publication")
        if (
            canonical_json_bytes(ending_backend_process_snapshot)
            != canonical_json_bytes(backend_process_snapshot)
            or canonical_json_bytes(last_backend_process_snapshot)
            != canonical_json_bytes(backend_process_snapshot)
            or canonical_json_bytes(ending_frontend_process_snapshot)
            != canonical_json_bytes(frontend_process_snapshot)
            or canonical_json_bytes(last_frontend_process_snapshot)
            != canonical_json_bytes(frontend_process_snapshot)
        ):
            fail("runtime executable/argv/classpath binding changed")
        last_staged_seal = validate_exact_evidence_artifacts(
            root=output.temporary_path,
            expected_bytes=expected_artifact_bytes,
            sensitive_values=sensitive_values,
            context=context,
            final_root=output.final_path,
        )
        if canonical_json_bytes(last_staged_seal) != canonical_json_bytes(
            staged_seal
        ):
            fail("staged evidence artifact seal changed before publication")
        output.commit()
        try:
            committed_seal = validate_exact_evidence_artifacts(
                root=output.final_path,
                expected_bytes=expected_artifact_bytes,
                sensitive_values=sensitive_values,
                context=context,
                final_root=output.final_path,
            )
            if canonical_json_bytes(committed_seal) != canonical_json_bytes(
                staged_seal
            ):
                fail("committed evidence differs from the staged artifact seal")
        except BaseException:
            output.rollback_committed()
            raise
        return output.final_path
    except BaseException:
        output.cleanup()
        raise


def static_self_test() -> dict[str, Any]:
    normalization = normalization_self_test()
    sample = {
        "roles": [
            {
                "roleCode": role_code,
                "frontendRouteProbes": [
                    {"requestTarget": target, "bodyText": "真实 404"}
                    for target in FRONTEND_NEGATIVE_TARGETS
                ],
                "apiProbes": [
                    {
                        "requestTarget": target,
                        "status": 404,
                        "bodyText": '{"code":"RESOURCE_NOT_FOUND"}',
                    }
                    for target in API_NEGATIVE_TARGETS
                ],
            }
            for role_code in ROLE_CODES
        ]
    }
    exclusions = build_request_target_exclusions(
        sample,
        runtime_artifact_path=RUNTIME_ARTIFACT_PATH,
        runtime_artifact_realpath="/tmp/current-run/runtime.json",
    )
    pointers = validate_request_target_exclusions(
        sample,
        exclusions,
        expected_artifact_realpath="/tmp/current-run/runtime.json",
    )
    scan = scan_runtime_document(sample, pointers)
    if scan["excludedRequestTargetScalarCount"] != 12:
        fail("request-target exclusion self-test failed")
    return {
        "normalization": normalization,
        "requestTargetExclusionCount": len(pointers),
        "runtimeScannedStringCount": scan["scannedStringCount"],
        "verdict": "PASS",
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Strict W3 zero-discoverability evidence producer"
    )
    subcommands = result.add_subparsers(dest="command", required=True)
    subcommands.add_parser("self-test", help="run dependency-free pure checks")
    execute = subcommands.add_parser(
        "execute", help="produce the six current-run raw artifacts"
    )
    execute.add_argument("--run-context", type=Path, required=True)
    execute.add_argument("--runtime-env", type=Path, required=True)
    execute.add_argument("--login-env", type=Path, required=True)
    execute.add_argument("--backend-url", required=True)
    execute.add_argument("--frontend-url", required=True)
    execute.add_argument("--output-dir", type=Path, required=True)
    execute.add_argument(
        "--agent-browser-bin", type=Path, default=DEFAULT_AGENT_BROWSER
    )
    execute.add_argument("--chrome-bin", type=Path, default=DEFAULT_CHROME)
    execute.add_argument(
        "--provision-synthetic-accounts",
        action="store_true",
        help=(
            "explicitly permit create/repair of only the three deterministic "
            "run-bound local test accounts"
        ),
    )
    return result


def main(argv: Sequence[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    try:
        if arguments.command == "self-test":
            result = static_self_test()
            print(
                "W3_ZERO_DISCOVERABILITY_PRODUCER_SELF_TEST=PASS "
                f"requestTargetExclusions={result['requestTargetExclusionCount']}"
            )
            return 0
        path = execute_producer(
            run_context=arguments.run_context,
            runtime_env=arguments.runtime_env,
            login_env=arguments.login_env,
            backend_url=arguments.backend_url,
            frontend_url=arguments.frontend_url,
            output_dir=arguments.output_dir,
            agent_browser_bin=arguments.agent_browser_bin,
            chrome_bin=arguments.chrome_bin,
            provision_synthetic_accounts=(
                arguments.provision_synthetic_accounts
            ),
        )
        print(f"W3_ZERO_DISCOVERABILITY_PRODUCER=PASS path={path}")
        return 0
    except ZeroDiscoverabilityError as error:
        print(f"[wave3-zero-discoverability] ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
