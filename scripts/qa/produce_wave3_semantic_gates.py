#!/usr/bin/env python3
"""Produce the first seven W3 pre-review leaves from current-run evidence.

The producer is deliberately strict:

* it accepts one frozen ``run-context.json`` and one bound private runtime env;
* it executes the existing W2 v2 oracle/canonicalizer and reviewed MySQL
  harnesses instead of importing historical PASS conclusions;
* it stages every raw role and publishes each complete role-set by one rename;
* it never registers leaves or advances source END/final acceptance.

The MySQL ``execute`` path is intentionally destructive only to the explicitly
confirmed ``shenzhou_hr_test`` table set through ``wave3-local-mysql.sh all``.
It never starts/stops either MySQL instance and never connects to port 3306.
"""

from __future__ import annotations

import argparse
import copy
import ctypes
import errno
import hashlib
import json
import os
import re
import secrets
import stat
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping, Sequence

try:
    import capture_wave3_mysql_semantics as semantic_capture
    import retained_canonicalizer as retained
    from orchestrate_wave3_leaves import (
        FIXED_PRE_REVIEW_IDS,
        load_plan_for_run,
        validate_runtime_environment,
    )
    from verify_w2_public_contract_v2 import (
        flatten_types,
        load_unique_yaml,
        parse_java,
    )
    from verify_wave3_evidence import (
        EvidenceError,
        FORBIDDEN_STRUCTURED_TOKEN,
        Wave3Evidence,
        sha256_file,
    )
except ImportError as error:  # pragma: no cover - import failure is fail-closed
    raise SystemExit(f"W3_SEMANTIC_GATE_PRODUCER=FAIL import: {error}") from error


REPOSITORY_ROOT = Path("/Users/huzhijin/Downloads/shenzhouHR")
WAVE3_RUNS_ROOT = REPOSITORY_ROOT / "docs/verification/wave3/runs"
CONTRACT_PATH = REPOSITORY_ROOT / "scripts/qa/wave3-evidence-contract-v1.json"
PUBLIC_DRIVER = REPOSITORY_ROOT / "scripts/qa/verify_w2_public_contract_v2.py"
PUBLIC_EXTRACTOR = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/extract-w2-public-contract-v2.sh"
)
PUBLIC_EXPECTED = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w2-public-contract-v2.json"
)
PUBLIC_SUPERSESSION = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w2-public-contract-v1-supersession-v2.json"
)
PUBLIC_EXPECTED_SHA256 = (
    "aae0e7a1daa4b18d8d75c34491923f6fc65adf8cbc15a5e528db24cca8c5b058"
)
PUBLIC_SUPERSESSION_SHA256 = (
    "188a9f6d46c6d9ebef3548f32e2b28f7bba6f0f0d12999df10031481f12b70a3"
)
W2_REGISTRY = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w2-retained-registry-v1.json"
)
W2_REGISTRY_SHA256 = (
    "1c83b61186c907eb020e26ef213a503f8f4fd9116edad6fef823672445fa0b06"
)
W2_FIXED_ROWS = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w2-retained-fixture-rows-v1.json"
)
W2_FIXED_ROWS_SHA256 = (
    "bb0282115c933afc5e752ab1e5dc18835026ff830ff38064b29c8615d2e37a0d"
)
W2_FIXTURE_SQL_SHA256 = (
    "41bfe9b0ee7f2d4a1cca26dfdfb0c8c243f06e3fc606c66ab129d9fdd2193f3b"
)
W3_REGISTRY = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w3-retained-registry-v1.json"
)
CANONICALIZER = REPOSITORY_ROOT / "scripts/qa/retained_canonicalizer.py"
CANONICAL_GOLDEN = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/retained-canonical-golden-v1.json"
)
SEED_ORACLE = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w3-seed-oracle-v1.json"
)
SEED_ORACLE_SHA256 = (
    "2f74955cf1bf8ac83a743f63666187a03e549e0fba5ebb120d61c1067dacea6d"
)
API_SEMANTIC_ORACLE = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w3-api-semantic-oracle-v1.json"
)
API_SEMANTIC_ORACLE_SHA256 = (
    "b0f04f6abd556ca81478b93a37ce5464396e6d37335b330552c9f43313da40e4"
)
OPENAPI = REPOSITORY_ROOT / "api/openapi.yaml"
MYSQL_ISOLATION = REPOSITORY_ROOT / "deploy/mysql/mysql8410-isolated.sh"
MYSQL_WAVE3 = REPOSITORY_ROOT / "deploy/mysql/wave3-local-mysql.sh"
MYSQL_LD_WRAPPER = REPOSITORY_ROOT / "deploy/mysql/mysql8410-ld-wrapper.sh"
MYSQL_CXX_WRAPPER = REPOSITORY_ROOT / "deploy/mysql/mysql8410-cxx-wrapper.sh"
MYSQL_ROOT = Path(
    "/Users/huzhijin/.local/share/shenzhouhr/mysql-8.4.10-isolated"
)
MYSQL_TARBALL = MYSQL_ROOT / "downloads/mysql-8.4.10.tar.gz"
MYSQL_TARBALL_SHA256 = (
    "d57a6730baef14ae118f7f4a6e02845b5b50933758df61fb06e104f27ccc8f96"
)
W3_REGISTRY_SHA256 = (
    "aa82a8d941bdb02b0f206b8715691d5f048fb4cfa34e01fc488e4263241803cb"
)
CANONICAL_GOLDEN_SHA256 = (
    "ca11e3d565d6abf5c754a102f6a40713a7fb441e65f1e16a930e8fc69cf9f33a"
)

SEMANTIC_IDS = FIXED_PRE_REVIEW_IDS[:7]
DATABASE_IDENTITY_PATTERN = re.compile(
    r"^mysql8410:"
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}:shenzhou_hr_test$"
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
HTTP_METHODS = {"get", "post", "put", "patch", "delete"}
CONTROLLERS = (
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest/"
    "AttendanceGroupController.java",
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest/"
    "AttendancePolicyController.java",
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest/"
    "AttendancePolicyLifecycleController.java",
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest/"
    "ShiftCalendarController.java",
)
DTO_FILES = (
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest/"
    "AttendanceGroupDtos.java",
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest/"
    "AttendancePolicyDtos.java",
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest/"
    "AttendancePolicyLifecycleController.java",
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest/"
    "AttendancePolicyLifecycleDtos.java",
    "backend/src/main/java/com/szsemicon/hr/attendance/interfaces/rest/"
    "ShiftCalendarDtos.java",
    "backend/src/main/java/com/szsemicon/hr/attendance/domain/"
    "AttendancePolicyCatalog.java",
)
CAPABILITIES = {
    "ATTENDANCE_SETUP:READ",
    "ATTENDANCE_SETUP:MANAGE_GROUP",
    "ATTENDANCE_SETUP:ASSIGN",
    "ATTENDANCE_SETUP:MANAGE_SHIFT",
    "ATTENDANCE_SETUP:MANAGE_CALENDAR",
    "ATTENDANCE_SETUP:MANAGE_POLICY",
}
W2_FIXED_ROW_COUNTS = {
    "policy_template": 1,
    "policy_version": 2,
    "policy_scope_binding": 1,
    "policy_publication_record": 1,
    "policy_rollback_record": 1,
    "audit_event": 1,
}
W2_FIXED_PRIMARY_KEYS = {
    ("policy_template", ("24400000-0000-0000-0000-000000000001",)),
    ("policy_version", ("24410000-0000-0000-0000-000000000001",)),
    ("policy_version", ("24410000-0000-0000-0000-000000000002",)),
    (
        "policy_scope_binding",
        ("24420000-0000-0000-0000-000000000001",),
    ),
    (
        "policy_publication_record",
        ("24430000-0000-0000-0000-000000000001",),
    ),
    (
        "policy_rollback_record",
        ("24440000-0000-0000-0000-000000000001",),
    ),
    ("audit_event", ("24450000-0000-0000-0000-000000000001",)),
}

REQUEST_RECORD_SCHEMAS = {
    "AttendanceGroupDtos.LocationRequest": "AttendanceLocationRequest",
    "AttendanceGroupDtos.GroupRequest": "AttendanceGroupRequest",
    "AttendanceGroupDtos.AssignmentRequest": "AttendanceGroupAssignmentRequest",
    "AttendanceGroupDtos.ReasonRequest": "ReasonRequest",
    "AttendancePolicyDtos.BindingRequest": "AttendancePolicyBindingRequest",
    "AttendancePolicyDtos.BindingPreviewRequest":
        "AttendancePolicyBindingPreviewRequest",
    "AttendancePolicyDtos.SimulationRequest":
        "AttendancePolicySimulationRequest",
    "AttendancePolicyLifecycleController.ParameterRequest":
        "AttendancePolicyParameter",
    "AttendancePolicyLifecycleController.DraftRequest":
        "AttendancePolicyDraftRequest",
    "AttendancePolicyLifecycleController.UpdateRequest":
        "AttendancePolicyDraftUpdateRequest",
    "AttendancePolicyLifecycleController.ReasonRequest": "ReasonRequest",
    "AttendancePolicyLifecycleController.DeactivateRequest":
        "AttendancePolicyDeactivateRequest",
    "AttendancePolicyLifecycleController.RollbackRequest":
        "AttendancePolicyRollbackRequest",
    "ShiftCalendarDtos.ShiftTemplateRequest": "ShiftTemplateRequest",
    "ShiftCalendarDtos.SegmentRequest": "ShiftSegment",
    "ShiftCalendarDtos.ShiftVersionRequest": "ShiftVersionRequest",
    "ShiftCalendarDtos.CalendarRequest": "WorkCalendarRequest",
    "ShiftCalendarDtos.CalendarVersionRequest":
        "WorkCalendarVersionRequest",
    "ShiftCalendarDtos.CalendarDayRequest": "WorkCalendarDayRequest",
    "ShiftCalendarDtos.ReasonRequest": "ReasonRequest",
    "ShiftCalendarDtos.FutureDeactivationRequest":
        "FutureDeactivationRequest",
    "ShiftCalendarDtos.ShiftTemplateStatusRequest":
        "ShiftTemplateStatusRequest",
    "ShiftCalendarDtos.ShiftVersionStatusRequest":
        "ShiftVersionStatusRequest",
    "ShiftCalendarDtos.CalendarStatusRequest":
        "WorkCalendarStatusRequest",
}

RESPONSE_RECORD_SCHEMAS = {
    "AttendanceGroupDtos.LocationView": "AttendanceLocationView",
    "AttendanceGroupDtos.LocationPage": "AttendanceLocationPage",
    "AttendanceGroupDtos.GroupView": "AttendanceGroupView",
    "AttendanceGroupDtos.GroupPage": "AttendanceGroupPage",
    "AttendanceGroupDtos.AssignmentView": "AttendanceGroupAssignmentView",
    "AttendanceGroupDtos.AssignmentPage": "AttendanceGroupAssignmentPage",
    "AttendancePolicyDtos.BindingView": "AttendancePolicyBindingView",
    "AttendancePolicyDtos.BindingPage": "AttendancePolicyBindingPage",
    "AttendancePolicyDtos.SimulationView":
        "AttendancePolicySimulationView",
    "AttendancePolicyDtos.SimulationBatchView":
        "AttendancePolicySimulationBatchView",
    "AttendancePolicyDtos.ImpactView": "AttendancePolicyImpactView",
    "AttendancePolicyDtos.ConfigurationView": "AttendanceConfigurationView",
    "AttendancePolicyLifecycleDtos.ParameterView":
        "AttendancePolicyParameter",
    "AttendancePolicyLifecycleDtos.ValidationIssueView":
        "AttendancePolicyValidationIssue",
    "AttendancePolicyLifecycleDtos.ValidationView":
        "AttendancePolicyValidation",
    "AttendancePolicyLifecycleDtos.VersionView":
        "AttendancePolicyVersionDetail",
    "AttendancePolicyLifecycleDtos.VersionPage":
        "AttendancePolicyVersionPage",
    "ShiftCalendarDtos.ShiftTemplateView": "ShiftTemplateView",
    "ShiftCalendarDtos.ShiftTemplatePage": "ShiftTemplatePage",
    "ShiftCalendarDtos.ShiftVersionView": "ShiftVersionView",
    "ShiftCalendarDtos.ShiftVersionPage": "ShiftVersionPage",
    "ShiftCalendarDtos.CalendarView": "WorkCalendarView",
    "ShiftCalendarDtos.CalendarPage": "WorkCalendarPage",
    "ShiftCalendarDtos.CalendarDayView": "WorkCalendarDayView",
    "ShiftCalendarDtos.CalendarDayPage": "WorkCalendarDayPage",
    "AttendancePolicyCatalog.TemplateDefinition":
        "AttendancePolicyCatalogEntry",
    "AttendancePolicyCatalog.FieldDefinition":
        "AttendancePolicyCatalogField",
}

REQUEST_TYPE_SCHEMAS = {
    "LocationRequest": "AttendanceLocationRequest",
    "GroupRequest": "AttendanceGroupRequest",
    "AssignmentRequest": "AttendanceGroupAssignmentRequest",
    "BindingRequest": "AttendancePolicyBindingRequest",
    "BindingPreviewRequest": "AttendancePolicyBindingPreviewRequest",
    "SimulationRequest": "AttendancePolicySimulationRequest",
    "DraftRequest": "AttendancePolicyDraftRequest",
    "UpdateRequest": "AttendancePolicyDraftUpdateRequest",
    "DeactivateRequest": "AttendancePolicyDeactivateRequest",
    "RollbackRequest": "AttendancePolicyRollbackRequest",
    "ShiftTemplateRequest": "ShiftTemplateRequest",
    "ShiftVersionRequest": "ShiftVersionRequest",
    "CalendarRequest": "WorkCalendarRequest",
    "CalendarVersionRequest": "WorkCalendarVersionRequest",
    "CalendarDayRequest": "WorkCalendarDayRequest",
    "FutureDeactivationRequest": "FutureDeactivationRequest",
    "ShiftTemplateStatusRequest": "ShiftTemplateStatusRequest",
    "ShiftVersionStatusRequest": "ShiftVersionStatusRequest",
    "CalendarStatusRequest": "WorkCalendarStatusRequest",
    "ReasonRequest": "ReasonRequest",
}

RESPONSE_TYPE_SCHEMAS = {
    "LocationPage": "AttendanceLocationPage",
    "LocationView": "AttendanceLocationView",
    "GroupPage": "AttendanceGroupPage",
    "GroupView": "AttendanceGroupView",
    "AssignmentPage": "AttendanceGroupAssignmentPage",
    "AssignmentView": "AttendanceGroupAssignmentView",
    "ShiftTemplatePage": "ShiftTemplatePage",
    "ShiftTemplateView": "ShiftTemplateView",
    "ShiftVersionPage": "ShiftVersionPage",
    "ShiftVersionView": "ShiftVersionView",
    "CalendarPage": "WorkCalendarPage",
    "CalendarView": "WorkCalendarView",
    "CalendarDayPage": "WorkCalendarDayPage",
    "BindingPage": "AttendancePolicyBindingPage",
    "BindingView": "AttendancePolicyBindingView",
    "SimulationBatchView": "AttendancePolicySimulationBatchView",
    "ImpactView": "AttendancePolicyImpactView",
    "ConfigurationView": "AttendanceConfigurationView",
    "AttendancePolicyLifecycleDtos.VersionPage":
        "AttendancePolicyVersionPage",
    "AttendancePolicyLifecycleDtos.VersionView":
        "AttendancePolicyVersionDetail",
    "AttendancePolicyLifecycleDtos.ValidationView":
        "AttendancePolicyValidation",
}


class ProducerError(RuntimeError):
    """Expected fail-closed producer error."""


def fail(message: str) -> None:
    raise ProducerError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def pretty_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def write_new(path: Path, contents: bytes, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() or path.is_symlink():
        fail(f"refusing to overwrite staged artifact: {path}")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(contents)
            output.flush()
            os.fsync(output.fileno())
    except BaseException:
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        raise


def write_json(path: Path, value: Any) -> None:
    write_new(path, pretty_json_bytes(value))


def copy_new(source: Path, target: Path) -> None:
    if not source.is_file() or source.is_symlink():
        fail(f"copy source is missing or symbolic: {source}")
    write_new(target, source.read_bytes())


def load_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"{label} is invalid: {error}")


def read_regular_bytes_at(
    directory_fd: int,
    filename: str,
    label: str,
    *,
    expected_identity: ObjectIdentity | None = None,
) -> bytes:
    if (
        not filename
        or filename in {".", ".."}
        or "/" in filename
        or "\x00" in filename
    ):
        fail(f"{label} filename is unsafe")
    before = os.stat(filename, dir_fd=directory_fd, follow_symlinks=False)
    if not stat.S_ISREG(before.st_mode) or stat.S_ISLNK(before.st_mode):
        fail(f"{label} is symbolic or not a regular file")
    if (
        expected_identity is not None
        and object_identity(before) != expected_identity
    ):
        fail(f"{label} identity changed before opening")
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    descriptor = os.open(filename, flags, dir_fd=directory_fd)
    try:
        identity = object_identity(os.fstat(descriptor))
        if identity != object_identity(before):
            fail(f"{label} identity changed while opening")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        if object_identity(os.fstat(descriptor)) != identity:
            fail(f"{label} identity changed while reading")
        contents = b"".join(chunks)
        if not contents:
            fail(f"{label} is empty")
        return contents
    finally:
        os.close(descriptor)


def load_json_at(
    directory_fd: int,
    filename: str,
    label: str,
    *,
    expected_identity: ObjectIdentity | None = None,
) -> Any:
    try:
        return json.loads(
            read_regular_bytes_at(
                directory_fd,
                filename,
                label,
                expected_identity=expected_identity,
            ).decode("utf-8", errors="strict")
        )
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"{label} is invalid: {error}")


def validate_fixed_semantic_oracles() -> tuple[dict[str, Any], dict[str, Any]]:
    if sha256_file(PUBLIC_EXPECTED) != PUBLIC_EXPECTED_SHA256:
        fail("fixed W2 public semantic oracle SHA256 drifted")
    if sha256_file(PUBLIC_SUPERSESSION) != PUBLIC_SUPERSESSION_SHA256:
        fail("fixed W2 public supersession oracle SHA256 drifted")
    if sha256_file(W2_REGISTRY) != W2_REGISTRY_SHA256:
        fail("fixed W2 retained registry SHA256 drifted")
    if sha256_file(W2_FIXED_ROWS) != W2_FIXED_ROWS_SHA256:
        fail("fixed W2 retained row fixture SHA256 drifted")
    if (
        sha256_bytes(semantic_capture.W2_FIXTURE_SQL.encode("utf-8"))
        != W2_FIXTURE_SQL_SHA256
    ):
        fail("W2 retained fixture SQL SHA256 drifted")
    if sha256_file(SEED_ORACLE) != SEED_ORACLE_SHA256:
        fail("fixed target7 seed oracle SHA256 drifted")
    w2 = load_json(W2_REGISTRY, "W2 retained registry")
    seed = load_json(SEED_ORACLE, "target7 seed oracle")
    expected_w2_tables = {
        "policy_template": "template_id",
        "policy_version": "version_id",
        "policy_scope_binding": "binding_id",
        "policy_publication_record": "publication_id",
        "policy_rollback_record": "rollback_id",
        "audit_event": "event_id",
    }
    w2_tables = w2.get("tables") if isinstance(w2, Mapping) else None
    if (
        not isinstance(w2, Mapping)
        or set(w2) != {"version", "authority", "tables"}
        or w2.get("version") != 1
        or w2.get("authority")
        != "Wave-3 design Decision 12 plus immutable V1/V3/V4 migrations"
        or not isinstance(w2_tables, list)
        or len(w2_tables) != 6
        or [
            value.get("table") if isinstance(value, Mapping) else None
            for value in w2_tables
        ]
        != list(expected_w2_tables)
        or any(
            not isinstance(value, Mapping)
            or set(value) != {"table", "pk", "columns"}
            or value.get("pk") != [expected_w2_tables[value.get("table")]]
            or not isinstance(value.get("columns"), list)
            or not value["columns"]
            or any(
                not isinstance(column, str) or ":" not in column
                for column in value["columns"]
            )
            for value in w2_tables
        )
        or sum(len(value["columns"]) for value in w2_tables) != 76
    ):
        fail("fixed W2 retained registry six-table/PK/76-column closure drifted")
    validate_unique_v7_migration()
    seeds = seed.get("seeds") if isinstance(seed, Mapping) else None
    expected_kinds = (
        "MEAL_DEDUCTION",
        "LATE_GRACE",
        "MONTHLY_LATE_EXEMPTION",
    )
    if (
        not isinstance(seeds, list)
        or len(seeds) != 3
        or tuple(value.get("policyKind") for value in seeds)
        != expected_kinds
    ):
        fail("fixed target7 seed oracle cardinality/kind closure drifted")
    for identifier in (
        "templateId",
        "scopeId",
        "scopedVersionId",
        "lifecycleEventId",
    ):
        if len({value.get(identifier) for value in seeds}) != 3:
            fail(f"fixed target7 seed oracle {identifier} closure drifted")
    for value in seeds:
        snapshot = value.get("canonicalSnapshot")
        digest = value.get("snapshotDigest")
        if (
            not isinstance(snapshot, str)
            or not isinstance(digest, str)
            or sha256_bytes(snapshot.encode("utf-8")) != digest
        ):
            fail("fixed target7 seed oracle digest closure drifted")
    load_review_owned_api_oracle()
    load_w2_fixed_row_oracle()
    return dict(w2), dict(seed)


def validate_unique_v7_migration(
    migration_dir: Path = (
        REPOSITORY_ROOT / "backend/src/main/resources/db/migration"
    ),
) -> Path:
    expected = migration_dir / "V7__attendance_setup_and_base_policies.sql"
    matches = sorted(migration_dir.glob("V7__*.sql"))
    if (
        matches != [expected]
        or not expected.is_file()
        or expected.is_symlink()
    ):
        fail(
            "migration directory must contain exactly the reviewed "
            "V7__attendance_setup_and_base_policies.sql"
        )
    return expected


def load_w2_fixed_row_oracle() -> dict[str, Any]:
    if sha256_file(W2_FIXED_ROWS) != W2_FIXED_ROWS_SHA256:
        fail("fixed W2 retained row fixture SHA256 drifted")
    document = require_exact_object(
        load_json(W2_FIXED_ROWS, "fixed W2 retained row fixture"),
        {
            "version",
            "authority",
            "registrySha256",
            "fixtureSqlSha256",
            "rowCounts",
            "sourceRows",
            "finalHash",
        },
        "fixed W2 retained row fixture",
    )
    if (
        document["version"] != 1
        or document["authority"]
        != (
            "Review-owned W2 V6 retained non-empty fixed fixture rows; "
            "independent of capture output"
        )
        or document["registrySha256"] != W2_REGISTRY_SHA256
        or document["fixtureSqlSha256"] != W2_FIXTURE_SQL_SHA256
        or document["rowCounts"] != W2_FIXED_ROW_COUNTS
        or not isinstance(document["sourceRows"], list)
    ):
        fail("fixed W2 retained row fixture root semantics differ")
    registry = retained.load_registry(W2_REGISTRY)
    normalized_source_rows: list[dict[str, Any]] = []
    for index, raw_row in enumerate(document["sourceRows"]):
        row = require_exact_object(
            raw_row,
            {"table", "values"},
            f"fixed W2 retained source row {index}",
        )
        table = row["table"]
        if not isinstance(table, str) or table not in registry:
            fail(f"fixed W2 retained source row {index} table differs")
        values = row["values"]
        expected_columns = [
            column for column, _ in registry[table]["columns"]
        ]
        if not isinstance(values, Mapping) or set(values) != set(
            expected_columns
        ):
            fail(
                f"fixed W2 retained source row {index} column closure differs"
            )
        normalized_source_rows.append(
            {
                "table": table,
                "values": {
                    column: values[column] for column in expected_columns
                },
            }
        )
    canonical = retained.canonical_rows(registry, normalized_source_rows)
    final_hash = sha256_bytes(
        "".join(row["snapshotLine"] for row in canonical).encode("utf-8")
    )
    actual_keys = {
        (row["table"], tuple(row["pk"])) for row in canonical
    }
    if (
        len(canonical) != 7
        or actual_keys != W2_FIXED_PRIMARY_KEYS
        or document["finalHash"] != final_hash
    ):
        fail("fixed W2 retained row fixture canonical recomputation differs")
    return {**document, "canonicalRows": canonical}


def ensure_no_forbidden(value: Any, label: str) -> None:
    if isinstance(value, str):
        if FORBIDDEN_STRUCTURED_TOKEN in value:
            fail(f"{label} contains forbidden status token")
        return
    if isinstance(value, Mapping):
        for key, nested in value.items():
            ensure_no_forbidden(key, label)
            ensure_no_forbidden(nested, label)
        return
    if isinstance(value, Sequence) and not isinstance(value, (bytes, bytearray)):
        for nested in value:
            ensure_no_forbidden(nested, label)


def run_command(
    command: Sequence[str],
    *,
    cwd: Path = REPOSITORY_ROOT,
    environment: Mapping[str, str] | None = None,
    label: str,
) -> tuple[str, int, int]:
    started = time.time_ns()
    result = subprocess.run(
        list(command),
        cwd=cwd,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env=dict(environment) if environment is not None else None,
    )
    ended = time.time_ns()
    output = result.stdout + result.stderr
    if result.returncode != 0:
        tail = "\n".join(output.splitlines()[-30:])
        fail(f"{label} failed with exit {result.returncode}:\n{tail}")
    if FORBIDDEN_STRUCTURED_TOKEN in output:
        fail(f"{label} emitted forbidden status token")
    return output, started, ended


def exact_context_line(context: Mapping[str, Any], evidence_id: str) -> str:
    return (
        "W3_EVIDENCE_CONTEXT=PASS "
        f"runId={context['runId']} "
        f"sourceHash={context['sourceTreeHash']} "
        f"dbIdentity={context['databaseIdentity']} "
        f"evidenceId={evidence_id}"
    )


def base_document(
    context: Mapping[str, Any],
    evidence_id: str,
    node_type: str,
    **values: Any,
) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "nodeType": node_type,
        "evidenceId": evidence_id,
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        **values,
    }


def parse_mapping_value(value: str) -> str:
    match = re.search(r'"([^"]*)"', value)
    if match:
        return match.group(1)
    stripped = value.strip()
    if re.fullmatch(r"[A-Za-z0-9_.-]+", stripped):
        return stripped
    return ""


def annotation_value(
    annotations: Mapping[str, str], suffix: str
) -> str | None:
    matches = [
        value for key, value in annotations.items()
        if key == suffix or key.endswith("." + suffix)
    ]
    if len(matches) > 1:
        fail(f"duplicate Java annotation suffix: {suffix}")
    return matches[0] if matches else None


def normalize_route(path: str) -> str:
    path = re.sub(r"\{([^}:]+):[^}]+\}", r"{\1}", path)
    path = re.sub(r"/+", "/", path)
    if not path.startswith("/"):
        path = "/" + path
    return path.rstrip("/") or "/"


def controller_base_path(source_text: str, relative: str) -> str:
    """Extract the class-level mapping omitted by the shared Java parser."""
    mappings = re.findall(
        r'@RequestMapping\s*\(\s*"([^"]+)"\s*\)',
        source_text,
    )
    if len(mappings) != 1:
        fail(
            "controller must have exactly one class-level RequestMapping: "
            f"{relative}; actual={len(mappings)}"
        )
    base_path = mappings[0]
    if not base_path.startswith("/api/v1/"):
        fail(f"controller mapping is outside /api/v1: {relative}")
    return base_path[len("/api/v1"):]


def controller_operation_export() -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    mapping_methods = {
        "GetMapping": "GET",
        "PostMapping": "POST",
        "PutMapping": "PUT",
        "PatchMapping": "PATCH",
        "DeleteMapping": "DELETE",
    }
    for relative in CONTROLLERS:
        source = REPOSITORY_ROOT / relative
        source_text = source.read_text(encoding="utf-8")
        parsed = parse_java(source_text)
        if len(parsed["types"]) != 1:
            fail(f"controller top-level type count differs: {relative}")
        controller = parsed["types"][0]
        base_path = controller_base_path(source_text, relative)
        for method in controller["methods"]:
            mappings = [
                (http_method, annotation_value(method["annotations"], annotation))
                for annotation, http_method in mapping_methods.items()
            ]
            mappings = [
                (http_method, value)
                for http_method, value in mappings
                if value is not None
            ]
            if not mappings:
                continue
            if len(mappings) != 1:
                fail(f"controller method has multiple HTTP mappings: {relative}")
            http_method, raw_path = mappings[0]
            route = normalize_route(base_path + parse_mapping_value(raw_path))
            parameters: list[dict[str, Any]] = []
            request_body: dict[str, Any] | None = None
            for parameter in method["parameters"]:
                annotations = parameter.get("annotations", {})
                path_value = annotation_value(annotations, "PathVariable")
                query_value = annotation_value(annotations, "RequestParam")
                header_value = annotation_value(annotations, "RequestHeader")
                body_value = annotation_value(annotations, "RequestBody")
                annotated = sum(
                    value is not None
                    for value in (path_value, query_value, header_value, body_value)
                )
                if annotated > 1:
                    fail(
                        f"ambiguous controller parameter annotation: "
                        f"{relative}#{method['name']}.{parameter['name']}"
                    )
                if path_value is not None:
                    parameters.append(
                        {
                            "name": parse_mapping_value(path_value)
                            or parameter["name"],
                            "in": "path",
                            "required": True,
                            "javaType": parameter["type"],
                        }
                    )
                elif query_value is not None:
                    parameters.append(
                        {
                            "name": parse_mapping_value(query_value)
                            or parameter["name"],
                            "in": "query",
                            "required": (
                                "required = false" not in query_value
                                and "defaultValue" not in query_value
                            ),
                            "javaType": parameter["type"],
                        }
                    )
                elif header_value is not None:
                    parameters.append(
                        {
                            "name": parse_mapping_value(header_value)
                            or parameter["name"],
                            "in": "header",
                            "required": "required = false" not in header_value,
                            "javaType": parameter["type"],
                        }
                    )
                elif body_value is not None:
                    if request_body is not None:
                        fail(f"multiple request bodies: {relative}#{method['name']}")
                    request_body = {
                        "javaType": parameter["type"],
                        "parameterName": parameter["name"],
                        "required": "required = false" not in body_value,
                    }
            operations.append(
                {
                    "method": http_method,
                    "path": route,
                    "controller": relative,
                    "javaMethod": method["name"],
                    "parameters": parameters,
                    "requestBody": request_body,
                    "returnType": method["returnType"],
                }
            )
    operations.sort(key=lambda value: (value["path"], value["method"]))
    keys = [(value["method"], value["path"]) for value in operations]
    if len(operations) != 54 or len(set(keys)) != 54:
        fail(
            "controller operation closure must be exactly 54 unique routes; "
            f"actual={len(operations)} unique={len(set(keys))}"
        )
    return operations


def json_pointer(document: Any, reference: str) -> Any:
    if not reference.startswith("#/"):
        fail(f"external or invalid OpenAPI reference: {reference}")
    value = document
    for token in reference[2:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        if not isinstance(value, Mapping) or token not in value:
            fail(f"unresolved OpenAPI reference: {reference}")
        value = value[token]
    return value


def collect_references(value: Any, output: set[str]) -> None:
    if isinstance(value, Mapping):
        reference = value.get("$ref")
        if reference is not None:
            if not isinstance(reference, str):
                fail("OpenAPI $ref is not a string")
            output.add(reference)
        for nested in value.values():
            collect_references(nested, output)
    elif isinstance(value, Sequence) and not isinstance(value, (str, bytes)):
        for nested in value:
            collect_references(nested, output)


REVIEW_SCHEMA_IGNORED_KEYS = frozenset(
    {"description", "examples", "example", "title"}
)


def review_schema_semantics(value: Any) -> Any:
    """Return the exact non-documentary JSON Schema semantics.

    The review-owned oracle deliberately retains constraints, defaults,
    formats, refs, unions, requiredness and object closure.  Only prose and
    examples are excluded so an editorial-only OpenAPI change does not require
    rotating the security oracle.
    """
    if isinstance(value, Mapping):
        normalized: dict[str, Any] = {}
        for key in sorted(value):
            if key in REVIEW_SCHEMA_IGNORED_KEYS:
                continue
            nested = value[key]
            if key in {"required", "type"} and isinstance(nested, list):
                normalized[key] = sorted(nested)
            elif key == "enum" and isinstance(nested, list):
                normalized[key] = sorted(
                    (
                        review_schema_semantics(item)
                        for item in nested
                    ),
                    key=lambda item: json.dumps(
                        item,
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    ),
                )
            else:
                normalized[key] = review_schema_semantics(nested)
        return normalized
    if isinstance(value, Sequence) and not isinstance(
        value, (str, bytes, bytearray)
    ):
        return [review_schema_semantics(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    fail(f"OpenAPI semantic value has unsupported type: {type(value).__name__}")


def resolve_openapi_object(
    document: Mapping[str, Any],
    value: Any,
    label: str,
) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        fail(f"{label} is not an object")
    if set(value) == {"$ref"}:
        resolved = json_pointer(document, str(value["$ref"]))
        if not isinstance(resolved, Mapping):
            fail(f"{label} reference is not an object")
        return resolved
    return value


def review_content_semantics(
    document: Mapping[str, Any],
    content: Any,
    label: str,
) -> list[dict[str, Any]]:
    if not isinstance(content, Mapping):
        fail(f"{label} content is not an object")
    result: list[dict[str, Any]] = []
    for media_type in sorted(content):
        media = content[media_type]
        if not isinstance(media, Mapping):
            fail(f"{label} media type is not an object: {media_type}")
        result.append(
            {
                "mediaType": str(media_type),
                "schema": review_schema_semantics(media.get("schema")),
            }
        )
    return result


def review_schema_token(value: Any) -> str:
    return json.dumps(
        review_schema_semantics(value),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )


def compact_controller_semantics(
    operation: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "source": operation["controller"],
        "javaMethod": operation["javaMethod"],
        "parameters": [
            "|".join(
                (
                    str(parameter["in"]),
                    str(parameter["name"]),
                    "required" if parameter["required"] else "optional",
                    str(parameter["javaType"]),
                )
            )
            for parameter in operation["parameters"]
        ],
        "requestBody": (
            None
            if operation["requestBody"] is None
            else "|".join(
                (
                    "required"
                    if operation["requestBody"]["required"]
                    else "optional",
                    str(operation["requestBody"]["javaType"]),
                )
            )
        ),
        "returnType": operation["returnType"],
        "semanticSha256": sha256_bytes(
            canonical_json_bytes(review_schema_semantics(operation))
        ),
    }


def review_response_contract_token(response: Mapping[str, Any]) -> str:
    content = ",".join(
        (
            f"{entry['mediaType']}:"
            f"{review_schema_token(entry['schema'])}"
        )
        for entry in response["content"]
    )
    headers = ",".join(
        ":".join(
            (
                header["name"],
                "required" if header["required"] else "optional",
                review_schema_token(header["schema"]),
            )
        )
        for header in response["headers"]
    )
    return f"content={content}|headers={headers}"


def compact_openapi_semantics(
    operation: Mapping[str, Any],
) -> dict[str, Any]:
    body = operation["requestBody"]

    return {
        "operationId": operation["operationId"],
        "security": review_schema_token(operation["security"]),
        "capability": operation["capability"],
        "dataScope": operation["dataScope"],
        "parameters": [
            "|".join(
                (
                    str(parameter["in"]),
                    str(parameter["name"]),
                    "required" if parameter["required"] else "optional",
                    review_schema_token(parameter["schema"]),
                )
            )
            for parameter in operation["parameters"]
        ],
        "requestBody": (
            None
            if body is None
            else {
                "required": body["required"],
                "content": [
                    (
                        f"{entry['mediaType']}|"
                        f"{review_schema_token(entry['schema'])}"
                    )
                    for entry in body["content"]
                ],
            }
        ),
        "successResponses": [
            (
                f"{response['status']}|"
                f"{review_response_contract_token(response)}"
            )
            for response in operation["responses"]
            if re.fullmatch(r"2[0-9][0-9]", response["status"])
        ],
        "errorStatuses": [
            response["status"]
            for response in operation["responses"]
            if not re.fullmatch(r"2[0-9][0-9]", response["status"])
        ],
        "semanticSha256": sha256_bytes(
            canonical_json_bytes(review_schema_semantics(operation))
        ),
    }


def review_openapi_operation_export(
    document: Mapping[str, Any],
) -> list[dict[str, Any]]:
    """Export OpenAPI independently of Controller/Java-derived expectations."""
    paths = document.get("paths")
    if not isinstance(paths, Mapping):
        fail("OpenAPI paths is not an object")
    result: list[dict[str, Any]] = []
    for raw_path, path_item in paths.items():
        path = str(raw_path)
        if not path.startswith("/attendance-setup/"):
            continue
        if not isinstance(path_item, Mapping):
            fail(f"OpenAPI path item is not an object: {path}")
        inherited = path_item.get("parameters", [])
        if not isinstance(inherited, list):
            fail(f"OpenAPI inherited parameters are not an array: {path}")
        for raw_method, raw_operation in path_item.items():
            if raw_method not in HTTP_METHODS:
                continue
            label = f"{raw_method.upper()} {path}"
            if not isinstance(raw_operation, Mapping):
                fail(f"OpenAPI operation is not an object: {label}")
            own_parameters = raw_operation.get("parameters", [])
            if not isinstance(own_parameters, list):
                fail(f"OpenAPI parameters are not an array: {label}")
            parameters: list[dict[str, Any]] = []
            for raw_parameter in [*inherited, *own_parameters]:
                parameter = resolve_openapi_object(
                    document, raw_parameter, f"{label} parameter"
                )
                parameters.append(
                    {
                        "in": parameter.get("in"),
                        "name": parameter.get("name"),
                        "required": bool(parameter.get("required", False)),
                        "schema": review_schema_semantics(
                            parameter.get("schema")
                        ),
                    }
                )
            raw_body = raw_operation.get("requestBody")
            request_body = None
            if raw_body is not None:
                body = resolve_openapi_object(
                    document, raw_body, f"{label} requestBody"
                )
                request_body = {
                    "required": bool(body.get("required", False)),
                    "content": review_content_semantics(
                        document,
                        body.get("content"),
                        f"{label} requestBody",
                    ),
                }
            raw_responses = raw_operation.get("responses")
            if not isinstance(raw_responses, Mapping):
                fail(f"OpenAPI responses are not an object: {label}")
            responses: list[dict[str, Any]] = []
            for raw_status in sorted(raw_responses, key=str):
                status = str(raw_status)
                response = resolve_openapi_object(
                    document,
                    raw_responses[raw_status],
                    f"{label} response {status}",
                )
                raw_headers = response.get("headers", {})
                if not isinstance(raw_headers, Mapping):
                    fail(f"{label} response {status} headers are not an object")
                headers: list[dict[str, Any]] = []
                for name in sorted(raw_headers):
                    header = resolve_openapi_object(
                        document,
                        raw_headers[name],
                        f"{label} response {status} header {name}",
                    )
                    headers.append(
                        {
                            "name": str(name),
                            "required": bool(header.get("required", False)),
                            "schema": review_schema_semantics(
                                header.get("schema")
                            ),
                        }
                    )
                content = response.get("content", {})
                responses.append(
                    {
                        "status": status,
                        "headers": headers,
                        "content": review_content_semantics(
                            document,
                            content,
                            f"{label} response {status}",
                        ),
                    }
                )
            result.append(
                {
                    "method": raw_method.upper(),
                    "path": normalize_route(path),
                    "operationId": raw_operation.get("operationId"),
                    "security": review_schema_semantics(
                        raw_operation.get("security")
                    ),
                    "capability": raw_operation.get("x-capability"),
                    "dataScope": raw_operation.get("x-data-scope"),
                    "parameters": parameters,
                    "requestBody": request_body,
                    "responses": responses,
                }
            )
    result.sort(key=lambda item: (item["path"], item["method"]))
    return result


def referenced_review_schema_names(
    document: Mapping[str, Any],
    operations: Sequence[Mapping[str, Any]],
) -> list[str]:
    references: set[str] = set()
    collect_references(operations, references)
    pending = {
        reference.rsplit("/", 1)[1]
        for reference in references
        if reference.startswith("#/components/schemas/")
    }
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, Mapping):
        fail("OpenAPI component schemas are not an object")
    completed: set[str] = set()
    while pending:
        name = pending.pop()
        if name in completed:
            continue
        schema = schemas.get(name)
        if not isinstance(schema, Mapping):
            fail(f"review API schema reference is missing: {name}")
        nested: set[str] = set()
        collect_references(schema, nested)
        for reference in nested:
            if reference.startswith("#/components/schemas/"):
                pending.add(reference.rsplit("/", 1)[1])
        completed.add(name)
    return sorted(completed)


def review_schema_export(
    document: Mapping[str, Any],
    operations: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    schemas = document["components"]["schemas"]
    result: list[dict[str, Any]] = []
    for name in referenced_review_schema_names(document, operations):
        schema = schemas[name]
        properties = schema.get("properties", {})
        if not isinstance(properties, Mapping):
            properties = {}
        result.append(
            {
                "name": name,
                "fieldOrder": list(properties),
                "required": sorted(schema.get("required", [])),
                "nullableFields": sorted(
                    field
                    for field, field_schema in properties.items()
                    if allows_null(field_schema, document)
                ),
                "additionalProperties": review_schema_semantics(
                    schema.get("additionalProperties")
                ),
                "semanticSha256": sha256_bytes(
                    canonical_json_bytes(review_schema_semantics(schema))
                ),
            }
        )
    return result


def java_record_required(component: Mapping[str, Any]) -> bool:
    return (
        component["type"]
        in {"boolean", "byte", "short", "int", "long", "float", "double"}
        or any(
            key.endswith(("NotNull", "NotBlank", "NotEmpty"))
            for key in component.get("annotations", {})
        )
    )


def review_java_record_export() -> list[dict[str, Any]]:
    records: dict[str, Mapping[str, Any]] = {}
    for relative in DTO_FILES:
        parsed = parse_java(
            (REPOSITORY_ROOT / relative).read_text(encoding="utf-8")
        )
        for value in flatten_types(parsed["types"]):
            if value["kind"] == "record":
                records[value["qualifiedName"]] = value
    result: list[dict[str, Any]] = []
    for surface, mappings in (
        ("request", REQUEST_RECORD_SCHEMAS),
        ("response", RESPONSE_RECORD_SCHEMAS),
    ):
        for record_name, schema_name in mappings.items():
            record = records.get(record_name)
            if record is None:
                fail(f"review API Java record is missing: {record_name}")
            result.append(
                {
                    "surface": surface,
                    "record": record_name,
                    "schema": schema_name,
                    "fields": [
                        "|".join(
                            (
                                component["name"],
                                component["type"],
                                (
                                    "required"
                                    if java_record_required(component)
                                    else "optional"
                                ),
                            )
                        )
                        for component in record["recordComponents"]
                    ],
                }
            )
    punch_name = "AttendancePolicyDtos.PunchSimulationInput"
    punch = records.get(punch_name)
    if punch is None:
        fail("review API punch simulation record is missing")
    result.append(
        {
            "surface": "request",
            "record": punch_name,
            "schema": (
                "AttendancePolicySimulationRequest"
                "#/properties/punches/items"
            ),
            "fields": [
                "|".join(
                    (
                        component["name"],
                        component["type"],
                        (
                            "required"
                            if java_record_required(component)
                            else "optional"
                        ),
                    )
                )
                for component in punch["recordComponents"]
            ],
        }
    )
    result.sort(
        key=lambda item: (item["surface"], item["schema"], item["record"])
    )
    return result


def build_review_owned_api_snapshot(
    document: Mapping[str, Any],
    controllers: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    openapi = review_openapi_operation_export(document)
    controller_by_key = {
        (item["method"], item["path"]): item for item in controllers
    }
    openapi_by_key = {
        (item["method"], item["path"]): item for item in openapi
    }
    keys = sorted(
        set(controller_by_key) | set(openapi_by_key),
        key=lambda item: (item[1], item[0]),
    )
    return {
        "version": 1,
        "authority": (
            "Wave-3 review-owned attendance setup API semantics; "
            "independent of Controller/OpenAPI/Java runtime derivation"
        ),
        "operationCount": len(keys),
        "errorResponseContracts": sorted(
            {
                review_response_contract_token(response)
                for operation in openapi
                for response in operation["responses"]
                if not re.fullmatch(r"2[0-9][0-9]", response["status"])
            }
        ),
        "operations": [
            {
                "method": method,
                "path": path,
                "controller": (
                    compact_controller_semantics(
                        controller_by_key[(method, path)]
                    )
                    if (method, path) in controller_by_key
                    else None
                ),
                "openapi": (
                    compact_openapi_semantics(openapi_by_key[(method, path)])
                    if (method, path) in openapi_by_key
                    else None
                ),
            }
            for method, path in keys
        ],
        "schemas": review_schema_export(document, openapi),
        "javaRecords": review_java_record_export(),
    }


def load_review_owned_api_oracle() -> dict[str, Any]:
    if sha256_file(API_SEMANTIC_ORACLE) != API_SEMANTIC_ORACLE_SHA256:
        fail("review-owned API semantic oracle SHA256 drifted")
    value = load_json(API_SEMANTIC_ORACLE, "review-owned API semantic oracle")
    if not isinstance(value, Mapping):
        fail("review-owned API semantic oracle root is not an object")
    expected_keys = {
        "version",
        "authority",
        "operationCount",
        "errorResponseContracts",
        "operations",
        "schemas",
        "javaRecords",
    }
    if (
        set(value) != expected_keys
        or value.get("version") != 1
        or value.get("operationCount") != 54
        or not isinstance(value.get("operations"), list)
        or len(value["operations"]) != 54
        or not isinstance(value.get("errorResponseContracts"), list)
        or len(value["errorResponseContracts"]) != 1
        or not isinstance(value.get("schemas"), list)
        or not isinstance(value.get("javaRecords"), list)
    ):
        fail("review-owned API semantic oracle root closure drifted")
    operation_keys = [
        (item.get("method"), item.get("path"))
        for item in value["operations"]
        if isinstance(item, Mapping)
    ]
    if (
        len(operation_keys) != 54
        or len(set(operation_keys)) != 54
        or operation_keys != sorted(operation_keys, key=lambda item: (item[1], item[0]))
        or any(
            not isinstance(item, Mapping)
            or set(item) != {"method", "path", "controller", "openapi"}
            or item.get("controller") is None
            or item.get("openapi") is None
            for item in value["operations"]
        )
    ):
        fail("review-owned API operation closure drifted")
    schema_names = [
        item.get("name")
        for item in value["schemas"]
        if isinstance(item, Mapping)
    ]
    if (
        len(schema_names) != len(value["schemas"])
        or schema_names != sorted(schema_names)
        or len(set(schema_names)) != len(schema_names)
    ):
        fail("review-owned API schema closure drifted")
    record_keys = [
        (item.get("surface"), item.get("schema"), item.get("record"))
        for item in value["javaRecords"]
        if isinstance(item, Mapping)
    ]
    if (
        len(record_keys) != len(value["javaRecords"])
        or record_keys != sorted(record_keys)
        or len(set(record_keys)) != len(record_keys)
    ):
        fail("review-owned API Java record closure drifted")
    return dict(value)


def compare_review_owned_api_oracle(
    document: Mapping[str, Any],
    controllers: Sequence[Mapping[str, Any]],
    oracle: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    expected = dict(oracle) if oracle is not None else load_review_owned_api_oracle()
    actual = build_review_owned_api_snapshot(document, controllers)
    differences: list[str] = []
    for key in (
        "operationCount",
        "errorResponseContracts",
        "operations",
        "schemas",
        "javaRecords",
    ):
        if actual[key] != expected.get(key):
            differences.append(key)
    return {
        "oracleSha256": (
            API_SEMANTIC_ORACLE_SHA256
            if oracle is None
            else sha256_bytes(canonical_json_bytes(expected))
        ),
        "differences": differences,
        "verdict": "PASS" if not differences else "FAIL",
    }


def schema_reference_name(schema: Any) -> str | None:
    if (
        isinstance(schema, Mapping)
        and set(schema) == {"$ref"}
        and isinstance(schema["$ref"], str)
        and schema["$ref"].startswith("#/components/schemas/")
    ):
        return schema["$ref"].rsplit("/", 1)[1]
    return None


def resolve_parameter(document: Mapping[str, Any], value: Any) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        fail("OpenAPI parameter is not an object")
    if set(value) == {"$ref"}:
        resolved = json_pointer(document, str(value["$ref"]))
        if not isinstance(resolved, Mapping):
            fail("OpenAPI parameter reference is not an object")
        return resolved
    return value


def openapi_operation_export(
    document: Mapping[str, Any],
) -> list[dict[str, Any]]:
    if document.get("openapi") != "3.1.0":
        fail("OpenAPI version must be 3.1.0")
    servers = document.get("servers")
    if servers != [{"url": "/api/v1"}]:
        fail("OpenAPI server must be exactly /api/v1")
    paths = document.get("paths")
    if not isinstance(paths, Mapping):
        fail("OpenAPI paths is not an object")
    operations: list[dict[str, Any]] = []
    for path, path_item in paths.items():
        if not str(path).startswith("/attendance-setup/"):
            continue
        if str(path).startswith("/api/") or not isinstance(path_item, Mapping):
            fail("invalid W3 OpenAPI path")
        inherited = path_item.get("parameters", [])
        for method, operation in path_item.items():
            if method not in HTTP_METHODS:
                continue
            if not isinstance(operation, Mapping):
                fail(f"OpenAPI operation is not an object: {method} {path}")
            raw_parameters = [*inherited, *operation.get("parameters", [])]
            parameters: list[dict[str, Any]] = []
            for raw in raw_parameters:
                parameter = resolve_parameter(document, raw)
                parameters.append(
                    {
                        "name": parameter.get("name"),
                        "in": parameter.get("in"),
                        "required": parameter.get("required", False),
                        "schema": parameter.get("schema"),
                    }
                )
            request_body = operation.get("requestBody")
            request_schema = None
            request_required: bool | None = None
            if request_body is not None:
                if not isinstance(request_body, Mapping):
                    fail(f"OpenAPI requestBody is not an object: {method} {path}")
                if set(request_body.get("content", {})) != {
                    "application/json"
                }:
                    fail(
                        "OpenAPI requestBody content must be exactly "
                        f"application/json: {method} {path}"
                    )
                request_schema = (
                    request_body["content"]["application/json"].get("schema")
                )
                if request_schema is None:
                    fail(
                        f"OpenAPI requestBody lacks schema: {method} {path}"
                    )
                request_required = bool(request_body.get("required", False))
            responses = operation.get("responses")
            if not isinstance(responses, Mapping):
                fail(f"OpenAPI operation lacks responses: {method} {path}")
            response_exports: list[dict[str, Any]] = []
            for status, response in responses.items():
                resolved_response = (
                    json_pointer(document, response["$ref"])
                    if isinstance(response, Mapping) and set(response) == {"$ref"}
                    else response
                )
                if not isinstance(resolved_response, Mapping):
                    fail("OpenAPI response is not an object")
                response_exports.append(
                    {
                        "status": str(status),
                        "schema": (
                            resolved_response.get("content", {})
                            .get("application/json", {})
                            .get("schema")
                        ),
                        "headers": sorted(
                            resolved_response.get("headers", {}).keys()
                        ),
                    }
                )
            operations.append(
                {
                    "method": method.upper(),
                    "path": normalize_route(str(path)),
                    "operationId": operation.get("operationId"),
                    "security": operation.get("security"),
                    "capability": operation.get("x-capability"),
                    "dataScope": operation.get("x-data-scope"),
                    "parameters": parameters,
                    "requestSchema": request_schema,
                    "requestRequired": request_required,
                    "responses": response_exports,
                }
            )
    operations.sort(key=lambda value: (value["path"], value["method"]))
    keys = [(value["method"], value["path"]) for value in operations]
    operation_ids = [value["operationId"] for value in operations]
    if (
        len(operations) != 54
        or len(set(keys)) != 54
        or any(not isinstance(value, str) or not value for value in operation_ids)
        or len(set(operation_ids)) != 54
    ):
        fail("OpenAPI W3 operation closure must be 54 unique routes/operationIds")
    return operations


def unwrap_response_type(return_type: str) -> str:
    match = re.fullmatch(r"ResponseEntity<(.+)>", return_type)
    if not match:
        fail(f"controller return type is not ResponseEntity<T>: {return_type}")
    return match.group(1)


def expected_response_schema(java_type: str) -> tuple[str, str | None]:
    if java_type == "List<AttendancePolicyCatalog.TemplateDefinition>":
        return "array", "AttendancePolicyCatalogEntry"
    schema = RESPONSE_TYPE_SCHEMAS.get(java_type)
    if schema is None:
        fail(f"unmapped W3 response Java type: {java_type}")
    return "object", schema


def expected_request_schema(java_type: str) -> tuple[str, str]:
    list_match = re.fullmatch(r"List<(.+)>", java_type)
    if list_match:
        item = REQUEST_TYPE_SCHEMAS.get(list_match.group(1))
        if item is None:
            fail(f"unmapped W3 request array Java type: {java_type}")
        return "array", item
    schema = REQUEST_TYPE_SCHEMAS.get(java_type)
    if schema is None:
        fail(f"unmapped W3 request Java type: {java_type}")
    return "object", schema


def success_status(method: str, path: str) -> str:
    if method != "POST":
        return "200"
    if path in {
        "/attendance-setup/locations",
        "/attendance-setup/groups",
        "/attendance-setup/shifts",
        "/attendance-setup/calendars",
        "/attendance-setup/policy-bindings",
    }:
        return "201"
    if path.endswith("/assignments") or path.endswith("/versions"):
        return "201"
    if path.endswith("/rollback"):
        return "201"
    return "200"


def is_read_only_post(path: str) -> bool:
    return path.endswith("/policy-simulations") or path.endswith(
        "/policy-impact-preview"
    )


def is_create(method: str, path: str) -> bool:
    return (
        success_status(method, path) == "201"
        and not path.endswith("/rollback")
    )


def expected_capability(method: str, path: str) -> str:
    if method == "GET":
        return "ATTENDANCE_SETUP:READ"
    if "/assignments" in path:
        return "ATTENDANCE_SETUP:ASSIGN"
    if path.startswith("/attendance-setup/calendars"):
        return "ATTENDANCE_SETUP:MANAGE_CALENDAR"
    if path.startswith("/attendance-setup/shifts"):
        return "ATTENDANCE_SETUP:MANAGE_SHIFT"
    if path.startswith("/attendance-setup/policy-"):
        return "ATTENDANCE_SETUP:MANAGE_POLICY"
    if path.startswith((
        "/attendance-setup/groups",
        "/attendance-setup/locations",
    )):
        return "ATTENDANCE_SETUP:MANAGE_GROUP"
    fail(f"unmapped W3 capability route: {method} {path}")


def parameter_schema_shape(
    schema: Any,
    document: Mapping[str, Any],
) -> tuple[frozenset[str], str | None]:
    if not isinstance(schema, Mapping):
        fail("OpenAPI parameter schema is not an object")
    if "$ref" in schema:
        return parameter_schema_shape(
            json_pointer(document, str(schema["$ref"])), document
        )
    types = frozenset(value for value in schema_types(schema) if value != "null")
    return types, schema.get("format")


def expected_parameter_shape(java_type: str) -> tuple[frozenset[str], str | None]:
    if java_type == "String":
        return frozenset({"string"}), None
    if java_type in {"int", "Integer"}:
        return frozenset({"integer"}), None
    if java_type == "LocalDate":
        return frozenset({"string"}), "date"
    fail(f"unmapped W3 parameter Java type: {java_type}")


def schema_object_closure_issues(
    schema: Any,
    document: Mapping[str, Any],
    label: str,
    visited: set[str] | None = None,
) -> list[str]:
    seen = visited if visited is not None else set()
    if not isinstance(schema, Mapping):
        return [f"{label}: schema is not an object"]
    if "$ref" in schema:
        reference = str(schema["$ref"])
        if reference in seen:
            return []
        seen.add(reference)
        return schema_object_closure_issues(
            json_pointer(document, reference), document, label, seen
        )
    issues: list[str] = []
    if schema.get("type") == "object" or "properties" in schema:
        properties = schema.get("properties")
        is_record_object = isinstance(properties, Mapping)
        if is_record_object and schema.get("additionalProperties") is not False:
            issues.append(f"{label}: object schema is not closed")
        for name, child in (
            properties.items() if is_record_object else ()
        ):
            issues.extend(
                schema_object_closure_issues(
                    child, document, f"{label}.{name}", seen
                )
            )
        additional = schema.get("additionalProperties")
        if not is_record_object and isinstance(additional, Mapping):
            issues.extend(
                schema_object_closure_issues(
                    additional,
                    document,
                    f"{label}{{value}}",
                    seen,
                )
            )
    if schema.get("type") == "array":
        issues.extend(
            schema_object_closure_issues(
                schema.get("items"), document, f"{label}[]", seen
            )
        )
    for keyword in ("allOf", "oneOf", "anyOf"):
        for index, child in enumerate(schema.get(keyword, [])):
            issues.extend(
                schema_object_closure_issues(
                    child,
                    document,
                    f"{label}.{keyword}[{index}]",
                    seen,
                )
            )
    return issues


def compare_operation_closure(
    controllers: Sequence[Mapping[str, Any]],
    openapi: Sequence[Mapping[str, Any]],
    document: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    if document is None:
        fail("OpenAPI document is required for semantic closure")
    controller_map = {
        (value["method"], value["path"]): value for value in controllers
    }
    openapi_map = {
        (value["method"], value["path"]): value for value in openapi
    }
    missing = sorted(set(controller_map) - set(openapi_map))
    extra = sorted(set(openapi_map) - set(controller_map))
    differences: list[str] = []
    if missing:
        differences.append(f"missingInOpenApi={missing}")
    if extra:
        differences.append(f"missingInController={extra}")
    for key in sorted(set(controller_map) & set(openapi_map)):
        controller = controller_map[key]
        operation = openapi_map[key]
        label = f"{key[0]} {key[1]}"
        if operation["security"] != [{"sessionCookie": []}]:
            differences.append(f"{label}: security")
        if (
            operation["capability"] not in CAPABILITIES
            or operation["capability"] != expected_capability(*key)
        ):
            differences.append(f"{label}: capability")
        if operation["dataScope"] != "ATTENDANCE_SETUP:LEGAL_ENTITY":
            differences.append(f"{label}: dataScope")
        controller_parameters = {
            (value["in"], value["name"]): value
            for value in controller["parameters"]
        }
        openapi_parameters = {
            (value["in"], value["name"]): value
            for value in operation["parameters"]
        }
        if len(controller_parameters) != len(controller["parameters"]):
            differences.append(f"{label}: duplicate controller parameter")
        if len(openapi_parameters) != len(operation["parameters"]):
            differences.append(f"{label}: duplicate OpenAPI parameter")
        controller_path_query = {
            key: value
            for key, value in controller_parameters.items()
            if key[0] in {"path", "query"}
        }
        openapi_path_query = {
            key: value
            for key, value in openapi_parameters.items()
            if key[0] in {"path", "query"}
        }
        if set(controller_path_query) != set(openapi_path_query):
            differences.append(f"{label}: path/query parameter set")
        for parameter_key in sorted(
            set(controller_parameters) & set(openapi_parameters)
        ):
            controller_parameter = controller_parameters[parameter_key]
            openapi_parameter = openapi_parameters[parameter_key]
            if (
                bool(controller_parameter["required"])
                != bool(openapi_parameter["required"])
            ):
                differences.append(
                    f"{label}: parameter required {parameter_key}"
                )
            expected_shape = expected_parameter_shape(
                controller_parameter["javaType"]
            )
            actual_shape = parameter_schema_shape(
                openapi_parameter["schema"], document
            )
            if actual_shape != expected_shape:
                differences.append(
                    f"{label}: parameter schema {parameter_key}"
                )
        expected_headers = {
            value["name"]
            for value in controller["parameters"]
            if value["in"] == "header"
        }
        if key[0] != "GET":
            expected_headers.add("X-CSRF-TOKEN")
        required_controller_headers: set[str] = set()
        if key[0] != "GET" and not is_read_only_post(key[1]):
            required_controller_headers.update(
                {"Idempotency-Key", "X-Change-Reason"}
            )
            if not is_create(*key):
                required_controller_headers.add("If-Match")
        missing_controller_headers = (
            required_controller_headers - expected_headers
        )
        if missing_controller_headers:
            differences.append(
                f"{label}: controller missing normative headers "
                f"{sorted(missing_controller_headers)}"
            )
        expected_headers.update(required_controller_headers)
        actual_headers = {
            value["name"]
            for value in operation["parameters"]
            if value["in"] == "header"
        }
        if expected_headers != actual_headers:
            differences.append(
                f"{label}: headers expected={sorted(expected_headers)} "
                f"actual={sorted(actual_headers)}"
            )
        normative_required_headers = set(required_controller_headers)
        if key[0] != "GET":
            normative_required_headers.add("X-CSRF-TOKEN")
        for header_name in sorted(normative_required_headers):
            parameter = openapi_parameters.get(("header", header_name))
            if parameter is None or not bool(parameter["required"]):
                differences.append(
                    f"{label}: required header is optional/missing "
                    f"{header_name}"
                )
            elif parameter_schema_shape(
                parameter["schema"], document
            ) != (frozenset({"string"}), None):
                differences.append(
                    f"{label}: required header schema {header_name}"
                )
        if (
            key[1].startswith("/attendance-setup/policy-lifecycle/")
            and "{templateId}" in key[1]
        ):
            lifecycle_parameter = ("query", "legalEntityId")
            if lifecycle_parameter not in controller_parameters:
                differences.append(
                    f"{label}: controller missing lifecycle legalEntityId"
                )
            if lifecycle_parameter not in openapi_parameters:
                differences.append(
                    f"{label}: OpenAPI missing lifecycle legalEntityId"
                )
        controller_body = controller["requestBody"]
        operation_body = operation["requestSchema"]
        if (controller_body is None) != (operation_body is None):
            differences.append(f"{label}: request body presence")
        elif controller_body is not None:
            if (
                bool(controller_body["required"])
                != bool(operation["requestRequired"])
            ):
                differences.append(f"{label}: request body required")
            body_kind, expected_schema = expected_request_schema(
                controller_body["javaType"]
            )
            if body_kind == "object":
                if schema_reference_name(operation_body) != expected_schema:
                    differences.append(f"{label}: request schema")
            else:
                if (
                    not isinstance(operation_body, Mapping)
                    or operation_body.get("type") != "array"
                    or schema_reference_name(operation_body.get("items"))
                    != expected_schema
                ):
                    differences.append(f"{label}: request array schema")
            differences.extend(
                schema_object_closure_issues(
                    operation_body, document, f"{label}: request"
                )
            )
        responses = {
            value["status"]: value for value in operation["responses"]
        }
        for status, response in responses.items():
            if re.fullmatch(r"2[0-9][0-9]", status):
                continue
            if schema_reference_name(response["schema"]) != "ApiError":
                differences.append(f"{label}: error response schema {status}")
            differences.extend(
                schema_object_closure_issues(
                    response["schema"],
                    document,
                    f"{label}: error response {status}",
                )
            )
        if "401" not in responses or "403" not in responses:
            differences.append(f"{label}: missing 401/403")
        expected_status = success_status(*key)
        success_responses = {
            status: value
            for status, value in responses.items()
            if re.fullmatch(r"2[0-9][0-9]", status)
        }
        if set(success_responses) != {expected_status}:
            differences.append(f"{label}: success status")
        else:
            success = success_responses[expected_status]
            if "X-Correlation-ID" not in success["headers"]:
                differences.append(f"{label}: X-Correlation-ID")
            expected_kind, expected_schema = expected_response_schema(
                unwrap_response_type(controller["returnType"])
            )
            actual_schema = success["schema"]
            if expected_kind == "object":
                if schema_reference_name(actual_schema) != expected_schema:
                    differences.append(f"{label}: response schema")
            elif (
                not isinstance(actual_schema, Mapping)
                or actual_schema.get("type") != "array"
                or schema_reference_name(actual_schema.get("items"))
                != expected_schema
            ):
                differences.append(f"{label}: response array schema")
            differences.extend(
                schema_object_closure_issues(
                    actual_schema, document, f"{label}: response"
                )
            )
        if key[0] != "GET" and not is_read_only_post(key[1]):
            if "Idempotency-Replayed" not in responses.get(
                expected_status, {}
            ).get("headers", []):
                differences.append(f"{label}: Idempotency-Replayed")
            if is_create(*key):
                if ("header", "If-Match") in openapi_parameters:
                    differences.append(f"{label}: unexpected If-Match")
            elif ("header", "If-Match") not in openapi_parameters:
                differences.append(f"{label}: missing If-Match")
    return {
        "controllerCount": len(controllers),
        "openApiCount": len(openapi),
        "missingInOpenApi": [
            {"method": method, "path": path} for method, path in missing
        ],
        "missingInController": [
            {"method": method, "path": path} for method, path in extra
        ],
        "differences": differences,
        "verdict": "PASS" if not differences else "FAIL",
    }


def allows_null(schema: Any, document: Mapping[str, Any]) -> bool:
    if not isinstance(schema, Mapping):
        return True
    if "$ref" in schema:
        return allows_null(json_pointer(document, schema["$ref"]), document)
    raw_type = schema.get("type")
    if isinstance(raw_type, list):
        return "null" in raw_type
    if raw_type == "null":
        return True
    if "anyOf" in schema:
        return any(allows_null(value, document) for value in schema["anyOf"])
    if "oneOf" in schema:
        return any(allows_null(value, document) for value in schema["oneOf"])
    if "not" in schema and schema["not"] == {"type": "null"}:
        return False
    return raw_type is None and not schema


def record_schema_report(document: Mapping[str, Any]) -> list[dict[str, Any]]:
    schemas = document["components"]["schemas"]
    records: dict[str, Mapping[str, Any]] = {}
    for relative in DTO_FILES:
        parsed = parse_java(
            (REPOSITORY_ROOT / relative).read_text(encoding="utf-8")
        )
        for value in flatten_types(parsed["types"]):
            if value["kind"] == "record":
                records[value["qualifiedName"]] = value
    report: list[dict[str, Any]] = []
    for record_name, schema_name in REQUEST_RECORD_SCHEMAS.items():
        record = records.get(record_name)
        schema = schemas.get(schema_name)
        if record is None or not isinstance(schema, Mapping):
            fail(f"request record/schema mapping is missing: {record_name}")
        properties = schema.get("properties")
        if not isinstance(properties, Mapping):
            fail(f"request schema lacks properties: {schema_name}")
        record_components = record["recordComponents"]
        record_fields = [value["name"] for value in record_components]
        schema_fields = list(properties)
        required = {
            value["name"]
            for value in record_components
            if (
                value["type"]
                in {"boolean", "byte", "short", "int", "long", "float", "double"}
                or any(
                    key.endswith(
                        ("NotNull", "NotBlank", "NotEmpty")
                    )
                    for key in value.get("annotations", {})
                )
            )
        }
        schema_required = set(schema.get("required", []))
        field_match = record_fields == schema_fields
        required_match = required == schema_required
        nullability_match = True
        nullability: list[dict[str, Any]] = []
        for component in record_components:
            nullable = allows_null(properties[component["name"]], document)
            expected_nullable = component["name"] not in required
            if nullable != expected_nullable:
                nullability_match = False
            nullability.append(
                {
                    "field": component["name"],
                    "javaRequired": not expected_nullable,
                    "schemaAllowsNull": nullable,
                    "match": nullable == expected_nullable,
                }
            )
        closed = schema.get("additionalProperties") is False
        entry = {
            "surface": "request",
            "record": record_name,
            "schema": schema_name,
            "recordFields": record_fields,
            "schemaFields": schema_fields,
            "requiredFields": sorted(required),
            "schemaRequiredFields": sorted(schema_required),
            "additionalPropertiesFalse": closed,
            "nullability": nullability,
            "verdict": (
                "PASS"
                if field_match
                and required_match
                and nullability_match
                and closed
                else "FAIL"
            ),
        }
        report.append(entry)
    for record_name, schema_name in RESPONSE_RECORD_SCHEMAS.items():
        record = records.get(record_name)
        schema = schemas.get(schema_name)
        if record is None or not isinstance(schema, Mapping):
            fail(f"response record/schema mapping is missing: {record_name}")
        properties = schema.get("properties")
        if not isinstance(properties, Mapping):
            fail(f"response schema lacks properties: {schema_name}")
        record_components = record["recordComponents"]
        record_fields = [value["name"] for value in record_components]
        schema_fields = list(properties)
        schema_required = set(schema.get("required", []))
        primitive_nullability = []
        primitive_ok = True
        for component in record_components:
            if component["type"] not in {
                "boolean",
                "byte",
                "short",
                "int",
                "long",
                "float",
                "double",
            }:
                continue
            nullable = allows_null(properties[component["name"]], document)
            if nullable:
                primitive_ok = False
            primitive_nullability.append(
                {
                    "field": component["name"],
                    "javaPrimitive": True,
                    "schemaAllowsNull": nullable,
                    "match": not nullable,
                }
            )
        closed = schema.get("additionalProperties") is False
        fields_match = set(record_fields) == set(schema_fields)
        required_match = schema_required == set(record_fields)
        report.append(
            {
                "surface": "response",
                "record": record_name,
                "schema": schema_name,
                "recordFields": record_fields,
                "schemaFields": schema_fields,
                "requiredFields": sorted(record_fields),
                "schemaRequiredFields": sorted(schema_required),
                "additionalPropertiesFalse": closed,
                "nullability": primitive_nullability,
                "verdict": (
                    "PASS"
                    if fields_match
                    and required_match
                    and primitive_ok
                    and closed
                    else "FAIL"
                ),
            }
        )
    punch_record = records.get("AttendancePolicyDtos.PunchSimulationInput")
    punch_schema = schemas["AttendancePolicySimulationRequest"]["properties"][
        "punches"
    ]["items"]
    if punch_record is None:
        fail("punch simulation record is missing")
    punch_fields = [
        value["name"] for value in punch_record["recordComponents"]
    ]
    punch_required = {
        value["name"]
        for value in punch_record["recordComponents"]
        if any(
            key.endswith(("NotNull", "NotBlank", "NotEmpty"))
            for key in value.get("annotations", {})
        )
    }
    punch_ok = (
        punch_fields == list(punch_schema.get("properties", {}))
        and punch_required == set(punch_schema.get("required", []))
        and punch_schema.get("additionalProperties") is False
    )
    report.append(
        {
            "surface": "request",
            "record": "AttendancePolicyDtos.PunchSimulationInput",
            "schema": "AttendancePolicySimulationRequest#/properties/punches/items",
            "recordFields": punch_fields,
            "schemaFields": list(punch_schema.get("properties", {})),
            "requiredFields": sorted(punch_required),
            "schemaRequiredFields": sorted(punch_schema.get("required", [])),
            "additionalPropertiesFalse":
                punch_schema.get("additionalProperties") is False,
            "nullability": [],
            "verdict": "PASS" if punch_ok else "FAIL",
        }
    )
    failures = [value for value in report if value["verdict"] != "PASS"]
    if failures:
        fail(
            "request DTO/schema closure failed: "
            + ", ".join(value["record"] for value in failures)
        )
    return report


def schema_types(schema: Mapping[str, Any]) -> set[str]:
    raw_type = schema.get("type")
    if isinstance(raw_type, str):
        return {raw_type}
    if isinstance(raw_type, list):
        return {str(value) for value in raw_type}
    return set()


def generate_schema_instance(
    schema: Any,
    document: Mapping[str, Any],
    *,
    depth: int = 0,
) -> Any:
    if depth > 40:
        fail("OpenAPI schema generation exceeded depth")
    if not isinstance(schema, Mapping) or not schema:
        return "value"
    if "$ref" in schema:
        return generate_schema_instance(
            json_pointer(document, schema["$ref"]),
            document,
            depth=depth + 1,
        )
    if "const" in schema:
        return copy.deepcopy(schema["const"])
    if "enum" in schema:
        values = [value for value in schema["enum"] if value is not None]
        return copy.deepcopy(values[0] if values else None)
    if "allOf" in schema:
        merged: dict[str, Any] = {}
        for nested in schema["allOf"]:
            generated = generate_schema_instance(
                nested, document, depth=depth + 1
            )
            if isinstance(generated, Mapping):
                merged.update(generated)
        return merged
    for keyword in ("oneOf", "anyOf"):
        if keyword in schema:
            candidates = [
                value for value in schema[keyword]
                if not (
                    isinstance(value, Mapping)
                    and schema_types(value) == {"null"}
                )
            ]
            return generate_schema_instance(
                candidates[0], document, depth=depth + 1
            )
    types = schema_types(schema)
    value_type = next(
        (value for value in types if value != "null"),
        "object" if "properties" in schema else "string",
    )
    if value_type == "object":
        properties = schema.get("properties", {})
        return {
            name: generate_schema_instance(
                properties[name], document, depth=depth + 1
            )
            for name in schema.get("required", [])
        }
    if value_type == "array":
        count = max(int(schema.get("minItems", 0)), 1)
        return [
            generate_schema_instance(
                schema.get("items", {}), document, depth=depth + 1
            )
            for _ in range(count)
        ]
    if value_type == "integer":
        return int(schema.get("minimum", 0))
    if value_type == "number":
        return schema.get("minimum", 0)
    if value_type == "boolean":
        return True
    if value_type == "null":
        return None
    if schema.get("format") == "date":
        return "2030-01-01"
    if schema.get("format") == "date-time":
        return "2030-01-01T00:00:00Z"
    minimum_length = max(int(schema.get("minLength", 0)), 1)
    pattern = schema.get("pattern")
    if isinstance(pattern, str):
        candidates = (
            "X",
            "ABC",
            "A_1",
            "a",
            "fieldName",
            "/x",
            "1",
            "1.0",
            '"1"',
            "0" * 64,
            "a" * 64,
            "file.xlsx",
        )
        maximum_length = int(schema.get("maxLength", 1_000_000))
        for candidate in candidates:
            if (
                minimum_length <= len(candidate) <= maximum_length
                and re.fullmatch(pattern, candidate)
            ):
                return candidate
        fail(f"cannot generate legal string for OpenAPI pattern: {pattern}")
    value = "x" * minimum_length
    maximum_length = schema.get("maxLength")
    if maximum_length is not None:
        value = value[: int(maximum_length)]
    return value


def validate_schema_instance(
    instance: Any,
    schema: Any,
    document: Mapping[str, Any],
    path: str = "$",
) -> list[str]:
    if not isinstance(schema, Mapping) or not schema:
        return []
    if "$ref" in schema:
        return validate_schema_instance(
            instance, json_pointer(document, schema["$ref"]), document, path
        )
    if "allOf" in schema:
        return [
            issue
            for nested in schema["allOf"]
            for issue in validate_schema_instance(instance, nested, document, path)
        ]
    if "anyOf" in schema or "oneOf" in schema:
        keyword = "anyOf" if "anyOf" in schema else "oneOf"
        results = [
            validate_schema_instance(instance, nested, document, path)
            for nested in schema[keyword]
        ]
        if keyword == "anyOf":
            return [] if any(not value for value in results) else [
                f"{path} matches no anyOf branch"
            ]
        return [] if sum(not value for value in results) == 1 else [
            f"{path} does not match exactly one oneOf branch"
        ]
    if "not" in schema and not validate_schema_instance(
        instance, schema["not"], document, path
    ):
        return [f"{path} matches forbidden schema"]
    if "const" in schema and instance != schema["const"]:
        return [f"{path} differs from const"]
    if "enum" in schema and instance not in schema["enum"]:
        return [f"{path} is outside enum"]
    types = schema_types(schema)
    if types:
        matches = (
            ("null" in types and instance is None)
            or ("object" in types and isinstance(instance, dict))
            or (
                "array" in types
                and isinstance(instance, list)
            )
            or (
                "string" in types
                and isinstance(instance, str)
            )
            or (
                "integer" in types
                and isinstance(instance, int)
                and not isinstance(instance, bool)
            )
            or (
                "number" in types
                and isinstance(instance, (int, float))
                and not isinstance(instance, bool)
            )
            or ("boolean" in types and isinstance(instance, bool))
        )
        if not matches:
            return [f"{path} has wrong type"]
    issues: list[str] = []
    if isinstance(instance, dict):
        properties = schema.get("properties", {})
        required = schema.get("required", [])
        for name in required:
            if name not in instance:
                issues.append(f"{path}.{name} is required")
        if schema.get("additionalProperties") is False:
            for name in set(instance) - set(properties):
                issues.append(f"{path}.{name} is additional")
        for name in set(instance) & set(properties):
            issues.extend(
                validate_schema_instance(
                    instance[name], properties[name], document, f"{path}.{name}"
                )
            )
    elif isinstance(instance, list):
        if len(instance) < int(schema.get("minItems", 0)):
            issues.append(f"{path} has too few items")
        if (
            "maxItems" in schema
            and len(instance) > int(schema["maxItems"])
        ):
            issues.append(f"{path} has too many items")
        for index, value in enumerate(instance):
            issues.extend(
                validate_schema_instance(
                    value, schema.get("items", {}), document, f"{path}[{index}]"
                )
            )
    elif isinstance(instance, str):
        if len(instance) < int(schema.get("minLength", 0)):
            issues.append(f"{path} is too short")
        if (
            "maxLength" in schema
            and len(instance) > int(schema["maxLength"])
        ):
            issues.append(f"{path} is too long")
        pattern = schema.get("pattern")
        if isinstance(pattern, str) and re.fullmatch(pattern, instance) is None:
            issues.append(f"{path} does not match pattern")
        if schema.get("format") == "date" and re.fullmatch(
            r"[0-9]{4}-[0-9]{2}-[0-9]{2}", instance
        ) is None:
            issues.append(f"{path} is not date")
        if schema.get("format") == "date-time" and "T" not in instance:
            issues.append(f"{path} is not date-time")
    elif isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if "minimum" in schema and instance < schema["minimum"]:
            issues.append(f"{path} is below minimum")
        if "maximum" in schema and instance > schema["maximum"]:
            issues.append(f"{path} is above maximum")
    return issues


def instance_validation_report(
    document: Mapping[str, Any],
    openapi_operations: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    unique_schemas: dict[str, Any] = {}
    for operation in openapi_operations:
        schema = operation["requestSchema"]
        if schema is None:
            continue
        name = schema_reference_name(schema)
        if name is None:
            if (
                isinstance(schema, Mapping)
                and schema.get("type") == "array"
                and schema_reference_name(schema.get("items")) is not None
            ):
                name = "array:" + str(schema_reference_name(schema["items"]))
            else:
                fail("request schema is not a closed component/array reference")
        unique_schemas[name] = schema
    report: list[dict[str, Any]] = []
    for name, schema in sorted(unique_schemas.items()):
        legal = generate_schema_instance(schema, document)
        legal_issues = validate_schema_instance(legal, schema, document)
        illegal = copy.deepcopy(legal)
        if isinstance(illegal, dict):
            illegal["__unexpected__"] = True
        elif isinstance(illegal, list) and illegal and isinstance(illegal[0], dict):
            illegal[0]["__unexpected__"] = True
        else:
            fail(f"request schema cannot receive unknown-field probe: {name}")
        illegal_issues = validate_schema_instance(illegal, schema, document)
        entry = {
            "schema": name,
            "legalInstanceAccepted": not legal_issues,
            "legalIssues": legal_issues,
            "unknownFieldRejected": bool(illegal_issues),
            "unknownFieldIssues": illegal_issues,
            "verdict": (
                "PASS"
                if not legal_issues and bool(illegal_issues)
                else "FAIL"
            ),
        }
        report.append(entry)
    failures = [value["schema"] for value in report if value["verdict"] != "PASS"]
    if failures:
        fail(f"OpenAPI legal/illegal instance validation failed: {failures}")
    return report


def produce_openapi_documents(
    context: Mapping[str, Any],
) -> dict[str, Any]:
    document = load_unique_yaml(OPENAPI.read_text(encoding="utf-8"))
    if not isinstance(document, Mapping):
        fail("OpenAPI root is not an object")
    references: set[str] = set()
    collect_references(document, references)
    for reference in sorted(references):
        json_pointer(document, reference)
    controllers = controller_operation_export()
    operations = openapi_operation_export(document)
    diff = compare_operation_closure(controllers, operations, document)
    if diff["verdict"] != "PASS":
        fail(
            "Controller/OpenAPI closure failed:\n"
            + "\n".join(diff["differences"][:30])
        )
    review_oracle = compare_review_owned_api_oracle(document, controllers)
    if review_oracle["verdict"] != "PASS":
        fail(
            "review-owned API semantic oracle differs: "
            + ", ".join(review_oracle["differences"])
        )
    record_report = record_schema_report(document)
    instance_report = instance_validation_report(document, operations)
    return {
        "controller": base_document(
            context,
            "W3-VER-OPENAPI-CLOSURE",
            "wave3-controller-operation-export",
            operationCount=len(controllers),
            operations=controllers,
            verdict="PASS",
        ),
        "openapi": base_document(
            context,
            "W3-VER-OPENAPI-CLOSURE",
            "wave3-openapi-operation-export",
            openApiVersion=document["openapi"],
            server="/api/v1",
            operationCount=len(operations),
            operations=operations,
            verdict="PASS",
        ),
        "diff": base_document(
            context,
            "W3-VER-OPENAPI-CLOSURE",
            "controller-openapi-operation-set-diff",
            reviewOwnedOracle=review_oracle,
            **diff,
        ),
        "report": base_document(
            context,
            "W3-VER-OPENAPI-CLOSURE",
            "openapi-resolved-ref-instance-report",
            resolvedReferenceCount=len(references),
            unresolvedReferences=[],
            dtoRecordSchemas=record_report,
            legalIllegalInstances=instance_report,
            strictUnknownProperties=True,
            reviewOwnedOracle=review_oracle,
            verdict="PASS",
        ),
    }


def artifact_map(plan: Mapping[str, Any]) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for leaf in plan["leaves"]:
        if leaf["evidenceId"] not in SEMANTIC_IDS:
            continue
        result[leaf["evidenceId"]] = {
            value["role"]: value["path"] for value in leaf["artifacts"]
        }
    if tuple(result) != tuple(SEMANTIC_IDS):
        fail("leaf plan does not contain the first seven semantic IDs in order")
    return result


def validate_build_regression_log(
    verifier: Wave3Evidence,
    context: Mapping[str, Any],
    plan: Mapping[str, Any],
    evidence_id: str,
    marker: str,
) -> Path:
    leaf = next(
        value for value in plan["leaves"] if value["evidenceId"] == evidence_id
    )
    primary_role = leaf["primaryArtifactRole"]
    relative = next(
        value["path"]
        for value in leaf["artifacts"]
        if value["role"] == primary_role
    )
    path = verifier.run_root(context["runId"]) / relative
    if not path.is_file() or path.is_symlink():
        fail(
            f"{evidence_id} current-run build output must exist before "
            "the W2 retained semantic producer"
        )
    text = path.read_text(encoding="utf-8")
    required_context = exact_context_line(context, evidence_id)
    lines = text.splitlines()
    if lines.count(required_context) != 1 or lines.count(marker) != 1:
        fail(f"{evidence_id} build output lacks exact current-run binding")
    if any(
        required_context in line and line != required_context
        or marker in line and line != marker
        for line in lines
    ):
        fail(f"{evidence_id} build output contains a non-exact marker line")
    if FORBIDDEN_STRUCTURED_TOKEN in text:
        fail(f"{evidence_id} build output contains forbidden status")
    if path.stat().st_mtime_ns < context["startedAtEpochNs"]:
        fail(f"{evidence_id} build output predates current run")
    return path


@dataclass(frozen=True)
class ObjectIdentity:
    device: int
    inode: int
    file_type: int


@dataclass(frozen=True)
class StagedRawDirectory:
    relative: PurePosixPath
    parent_identities: tuple[ObjectIdentity, ...]
    directory_identity: ObjectIdentity
    entry_identities: tuple[tuple[str, ObjectIdentity], ...]


@dataclass(frozen=True)
class PublishedRawDirectory:
    path: Path
    relative: PurePosixPath
    run_root_identity: ObjectIdentity
    parent_identities: tuple[ObjectIdentity, ...]
    directory_identity: ObjectIdentity
    entry_identities: tuple[tuple[str, ObjectIdentity], ...]


def object_identity(metadata: os.stat_result) -> ObjectIdentity:
    return ObjectIdentity(
        device=metadata.st_dev,
        inode=metadata.st_ino,
        file_type=stat.S_IFMT(metadata.st_mode),
    )


def directory_open_flags() -> int:
    flags = os.O_RDONLY
    if hasattr(os, "O_DIRECTORY"):
        flags |= os.O_DIRECTORY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    return flags


def validate_relative_parts(relative: PurePosixPath, label: str) -> None:
    if (
        relative.is_absolute()
        or not relative.parts
        or any(part in {"", ".", ".."} or "/" in part for part in relative.parts)
    ):
        fail(f"{label} is not a safe relative directory")


def open_absolute_directory_no_symlinks(
    path: Path,
    label: str,
) -> tuple[int, ObjectIdentity, tuple[ObjectIdentity, ...]]:
    if not path.is_absolute():
        fail(f"{label} must be absolute")
    descriptor = os.open("/", directory_open_flags())
    root_identity = object_identity(os.fstat(descriptor))
    identities: list[ObjectIdentity] = []
    try:
        for part in path.parts[1:]:
            before = os.stat(part, dir_fd=descriptor, follow_symlinks=False)
            if not stat.S_ISDIR(before.st_mode) or stat.S_ISLNK(before.st_mode):
                fail(f"{label} has a symbolic/non-directory ancestor: {part}")
            child = os.open(
                part,
                directory_open_flags(),
                dir_fd=descriptor,
            )
            after_identity = object_identity(os.fstat(child))
            if after_identity != object_identity(before):
                os.close(child)
                fail(f"{label} ancestor identity changed while opening: {part}")
            os.close(descriptor)
            descriptor = child
            identities.append(after_identity)
        return descriptor, root_identity, tuple(identities)
    except BaseException:
        os.close(descriptor)
        raise


def verify_absolute_directory_identity(
    path: Path,
    expected: ObjectIdentity,
    label: str,
) -> None:
    descriptor, _, identities = open_absolute_directory_no_symlinks(
        path, label
    )
    try:
        if not identities or identities[-1] != expected:
            fail(f"{label} identity changed")
    finally:
        os.close(descriptor)


def open_directory_chain_at(
    root_fd: int,
    relative: PurePosixPath,
    label: str,
    *,
    create: bool,
    expected: tuple[ObjectIdentity, ...] | None = None,
) -> tuple[int, tuple[ObjectIdentity, ...]]:
    if relative.parts:
        validate_relative_parts(relative, label)
    descriptor = os.dup(root_fd)
    identities: list[ObjectIdentity] = []
    try:
        for index, part in enumerate(relative.parts):
            if create:
                try:
                    os.mkdir(part, 0o700, dir_fd=descriptor)
                    os.fsync(descriptor)
                except FileExistsError:
                    pass
            before = os.stat(part, dir_fd=descriptor, follow_symlinks=False)
            if not stat.S_ISDIR(before.st_mode) or stat.S_ISLNK(before.st_mode):
                fail(f"{label} contains a symbolic/non-directory component")
            child = os.open(
                part,
                directory_open_flags(),
                dir_fd=descriptor,
            )
            identity = object_identity(os.fstat(child))
            if identity != object_identity(before):
                os.close(child)
                fail(f"{label} component identity changed while opening")
            if expected is not None and (
                index >= len(expected) or identity != expected[index]
            ):
                os.close(child)
                fail(f"{label} ancestor identity changed")
            os.close(descriptor)
            descriptor = child
            identities.append(identity)
        if expected is not None and len(identities) != len(expected):
            fail(f"{label} ancestor depth changed")
        return descriptor, tuple(identities)
    except BaseException:
        os.close(descriptor)
        raise


def open_child_directory_at(
    parent_fd: int,
    name: str,
    label: str,
    *,
    expected: ObjectIdentity | None = None,
) -> tuple[int, ObjectIdentity]:
    before = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    if not stat.S_ISDIR(before.st_mode) or stat.S_ISLNK(before.st_mode):
        fail(f"{label} is symbolic or not a directory")
    descriptor = os.open(name, directory_open_flags(), dir_fd=parent_fd)
    identity = object_identity(os.fstat(descriptor))
    if identity != object_identity(before) or (
        expected is not None and identity != expected
    ):
        os.close(descriptor)
        fail(f"{label} identity changed")
    return descriptor, identity


def create_private_stage_directory(
    run_root: Path,
    *,
    expected_run_root_identity: ObjectIdentity | None = None,
) -> tuple[Path, ObjectIdentity]:
    run_root_fd, _, run_chain = open_absolute_directory_no_symlinks(
        run_root, "semantic stage run root"
    )
    if not run_chain:
        os.close(run_root_fd)
        fail("semantic stage run root identity is unavailable")
    if (
        expected_run_root_identity is not None
        and (
            run_chain[-1] != expected_run_root_identity
            or object_identity(os.fstat(run_root_fd))
            != expected_run_root_identity
        )
    ):
        os.close(run_root_fd)
        fail("semantic stage run root identity changed")
    try:
        orchestration_fd, _ = open_directory_chain_at(
            run_root_fd,
            PurePosixPath("orchestration"),
            "semantic orchestration directory",
            create=True,
        )
        try:
            for _ in range(64):
                name = f".semantic-gates-stage-{secrets.token_hex(12)}"
                try:
                    os.mkdir(name, 0o700, dir_fd=orchestration_fd)
                except FileExistsError:
                    continue
                os.fsync(orchestration_fd)
                stage_fd, identity = open_child_directory_at(
                    orchestration_fd,
                    name,
                    "semantic stage directory",
                )
                try:
                    os.fchmod(stage_fd, 0o700)
                    os.fsync(stage_fd)
                finally:
                    os.close(stage_fd)
                return run_root / "orchestration" / name, identity
            fail("unable to allocate a unique semantic stage directory")
        finally:
            os.close(orchestration_fd)
    finally:
        os.close(run_root_fd)


def create_private_stage_subdirectory(
    stage_root: Path,
    expected_stage_identity: ObjectIdentity,
    relative: PurePosixPath,
    label: str,
) -> tuple[int, ObjectIdentity]:
    stage_fd, _, stage_chain = open_absolute_directory_no_symlinks(
        stage_root, f"{label} stage root"
    )
    try:
        if (
            not stage_chain
            or stage_chain[-1] != expected_stage_identity
            or object_identity(os.fstat(stage_fd)) != expected_stage_identity
        ):
            fail(f"{label} stage root identity changed")
        descriptor, identities = open_directory_chain_at(
            stage_fd,
            relative,
            label,
            create=True,
        )
        if not identities:
            os.close(descriptor)
            fail(f"{label} identity is unavailable")
        os.fsync(descriptor)
        return descriptor, identities[-1]
    finally:
        os.close(stage_fd)


def remove_directory_contents_at(directory_fd: int, label: str) -> None:
    for name in sorted(os.listdir(directory_fd)):
        metadata = os.stat(
            name, dir_fd=directory_fd, follow_symlinks=False
        )
        if stat.S_ISREG(metadata.st_mode):
            identity = object_identity(metadata)
            current = object_identity(
                os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            )
            if current != identity:
                fail(f"{label} file identity changed during cleanup")
            os.unlink(name, dir_fd=directory_fd)
            continue
        if stat.S_ISDIR(metadata.st_mode) and not stat.S_ISLNK(metadata.st_mode):
            child, identity = open_child_directory_at(
                directory_fd,
                name,
                f"{label}/{name}",
            )
            try:
                remove_directory_contents_at(child, f"{label}/{name}")
                os.fsync(child)
            finally:
                os.close(child)
            current = object_identity(
                os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            )
            if current != identity:
                fail(f"{label} directory identity changed during cleanup")
            os.rmdir(name, dir_fd=directory_fd)
            continue
        fail(f"{label} contains an unexpected non-regular entry: {name}")
    os.fsync(directory_fd)


def remove_private_stage_directory(
    run_root: Path,
    stage_root: Path,
    expected_identity: ObjectIdentity,
) -> None:
    try:
        relative = stage_root.relative_to(run_root)
    except ValueError:
        fail("semantic stage cleanup target escapes run root")
    if (
        len(relative.parts) != 2
        or relative.parts[0] != "orchestration"
        or not relative.parts[1].startswith(".semantic-gates-stage-")
    ):
        fail("semantic stage cleanup target is not an owned stage")
    run_root_fd, _, _ = open_absolute_directory_no_symlinks(
        run_root, "semantic stage cleanup run root"
    )
    try:
        orchestration_fd, _ = open_directory_chain_at(
            run_root_fd,
            PurePosixPath("orchestration"),
            "semantic stage cleanup parent",
            create=False,
        )
        try:
            stage_fd, identity = open_child_directory_at(
                orchestration_fd,
                relative.parts[1],
                "semantic stage cleanup directory",
                expected=expected_identity,
            )
            try:
                remove_directory_contents_at(
                    stage_fd, "semantic stage cleanup directory"
                )
            finally:
                os.close(stage_fd)
            current = object_identity(
                os.stat(
                    relative.parts[1],
                    dir_fd=orchestration_fd,
                    follow_symlinks=False,
                )
            )
            if identity != expected_identity or current != expected_identity:
                fail("semantic stage identity changed; replacement preserved")
            os.rmdir(relative.parts[1], dir_fd=orchestration_fd)
            os.fsync(orchestration_fd)
        finally:
            os.close(orchestration_fd)
    finally:
        os.close(run_root_fd)


def regular_directory_entries(
    directory_fd: int,
    expected_names: set[str],
    label: str,
    *,
    expected_identities: tuple[tuple[str, ObjectIdentity], ...] | None = None,
) -> tuple[tuple[str, ObjectIdentity], ...]:
    names = os.listdir(directory_fd)
    actual_names = set(names)
    if (
        len(names) != len(actual_names)
        or actual_names != expected_names
    ):
        fail(
            f"{label} entry closure failed; "
            f"missing={sorted(expected_names - actual_names)}, "
            f"extra={sorted(actual_names - expected_names)}"
        )
    identities: list[tuple[str, ObjectIdentity]] = []
    for name in sorted(names):
        metadata = os.stat(
            name, dir_fd=directory_fd, follow_symlinks=False
        )
        if not stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
            fail(f"{label} contains a non-regular entry: {name}")
        identities.append((name, object_identity(metadata)))
    result = tuple(identities)
    if expected_identities is not None and result != expected_identities:
        fail(f"{label} entry identity changed")
    return result


def atomic_rename_exclusive_at(
    source_parent: int,
    source_name: str,
    target_parent: int,
    target_name: str,
    target_label: str,
) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    source_bytes = os.fsencode(source_name)
    target_bytes = os.fsencode(target_name)
    ctypes.set_errno(0)
    if sys.platform == "darwin":
        rename = libc.renameatx_np
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        rename.restype = ctypes.c_int
        result = rename(
            source_parent,
            source_bytes,
            target_parent,
            target_bytes,
            0x00000004 | 0x00000010,
        )
    elif sys.platform.startswith("linux") and hasattr(libc, "renameat2"):
        rename = libc.renameat2
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        rename.restype = ctypes.c_int
        result = rename(
            source_parent,
            source_bytes,
            target_parent,
            target_bytes,
            0x1,
        )
    else:
        fail("platform lacks an exclusive atomic directory rename primitive")
    if result != 0:
        error_number = ctypes.get_errno()
        if error_number in {errno.EEXIST, errno.ENOTEMPTY}:
            fail(
                "refusing to overwrite existing raw evidence: "
                f"{target_label}"
            )
        raise OSError(error_number, os.strerror(error_number), target_label)
    os.fsync(source_parent)
    if target_parent != source_parent:
        os.fsync(target_parent)


def atomic_rename_exclusive(source: Path, target: Path) -> None:
    source_parent, _, _ = open_absolute_directory_no_symlinks(
        source.parent, "exclusive rename source parent"
    )
    try:
        target_parent, _, _ = open_absolute_directory_no_symlinks(
            target.parent, "exclusive rename target parent"
        )
        try:
            atomic_rename_exclusive_at(
                source_parent,
                source.name,
                target_parent,
                target.name,
                str(target),
            )
        finally:
            os.close(target_parent)
    finally:
        os.close(source_parent)


def remove_published_raw_at(
    run_root_fd: int,
    published: PublishedRawDirectory,
) -> None:
    if object_identity(os.fstat(run_root_fd)) != published.run_root_identity:
        fail("rollback root identity changed; replacement preserved")
    relative = published.relative
    if len(relative.parts) < 2:
        fail("refusing unsafe raw rollback target")
    parent_relative = PurePosixPath(*relative.parts[:-1])
    try:
        parent_fd, _ = open_directory_chain_at(
            run_root_fd,
            parent_relative,
            "raw rollback parent",
            create=False,
            expected=published.parent_identities,
        )
        try:
            raw_fd, raw_identity = open_child_directory_at(
                parent_fd,
                relative.parts[-1],
                "raw rollback directory",
                expected=published.directory_identity,
            )
            try:
                expected_entries = dict(published.entry_identities)
                regular_directory_entries(
                    raw_fd,
                    set(expected_entries),
                    "raw rollback directory",
                    expected_identities=published.entry_identities,
                )
            finally:
                os.close(raw_fd)
            if raw_identity != published.directory_identity:
                fail("rollback directory identity changed")
            raw_fd, _ = open_child_directory_at(
                parent_fd,
                relative.parts[-1],
                "raw rollback directory",
                expected=published.directory_identity,
            )
            try:
                regular_directory_entries(
                    raw_fd,
                    set(expected_entries),
                    "raw rollback directory",
                    expected_identities=published.entry_identities,
                )
                for name in sorted(expected_entries):
                    current = object_identity(
                        os.stat(
                            name,
                            dir_fd=raw_fd,
                            follow_symlinks=False,
                        )
                    )
                    if current != expected_entries[name]:
                        fail("rollback entry identity changed")
                    os.unlink(name, dir_fd=raw_fd)
                os.fsync(raw_fd)
            finally:
                os.close(raw_fd)
            current_directory = object_identity(
                os.stat(
                    relative.parts[-1],
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
            )
            if current_directory != published.directory_identity:
                fail("rollback directory identity changed before removal")
            os.rmdir(relative.parts[-1], dir_fd=parent_fd)
            os.fsync(parent_fd)
        finally:
            os.close(parent_fd)
    except (FileNotFoundError, NotADirectoryError, OSError, ProducerError) as error:
        fail(
            "published raw identity changed; replacement preserved: "
            f"{published.path}: {error}"
        )


def remove_published_raw_safely(
    run_root: Path,
    published: PublishedRawDirectory,
) -> None:
    run_root_fd, _, identities = open_absolute_directory_no_symlinks(
        run_root, "raw rollback run root"
    )
    try:
        if not identities or identities[-1] != published.run_root_identity:
            fail("rollback run root identity changed; replacement preserved")
        remove_published_raw_at(run_root_fd, published)
    finally:
        os.close(run_root_fd)


def publish_raw_directories(
    run_root: Path,
    stage_root: Path,
    roles: Mapping[str, Mapping[str, str]],
    *,
    expected_run_root_identity: ObjectIdentity | None = None,
    expected_stage_root_identity: ObjectIdentity | None = None,
) -> list[PublishedRawDirectory]:
    published: list[PublishedRawDirectory] = []
    targets: list[tuple[str, PurePosixPath, set[str]]] = []
    for evidence_id in SEMANTIC_IDS:
        role_paths = roles[evidence_id]
        raw_relatives = {
            PurePosixPath(relative).parent
            for relative in role_paths.values()
        }
        if len(raw_relatives) != 1:
            fail(f"{evidence_id} roles do not share one raw directory")
        relative_raw = next(iter(raw_relatives))
        if (
            relative_raw.is_absolute()
            or "." in relative_raw.parts
            or ".." in relative_raw.parts
        ):
            fail(f"{evidence_id} raw directory is not run-relative")
        expected_names = {
            PurePosixPath(value).name for value in role_paths.values()
        }
        if len(expected_names) != len(role_paths):
            fail(f"{evidence_id} role filenames are not unique")
        targets.append((evidence_id, relative_raw, expected_names))
    run_root_fd, _, run_chain = open_absolute_directory_no_symlinks(
        run_root, "raw publication run root"
    )
    stage_root_fd, _, stage_chain = open_absolute_directory_no_symlinks(
        stage_root, "raw publication stage root"
    )
    if not run_chain or not stage_chain:
        os.close(run_root_fd)
        os.close(stage_root_fd)
        fail("raw publication roots lack fixed identities")
    run_root_identity = run_chain[-1]
    if (
        expected_run_root_identity is not None
        and run_root_identity != expected_run_root_identity
    ):
        os.close(run_root_fd)
        os.close(stage_root_fd)
        fail("raw publication run root identity changed")
    if (
        expected_stage_root_identity is not None
        and stage_chain[-1] != expected_stage_root_identity
    ):
        os.close(run_root_fd)
        os.close(stage_root_fd)
        fail("raw publication stage root identity changed")
    staged: dict[str, StagedRawDirectory] = {}
    final_parents: dict[str, tuple[ObjectIdentity, ...]] = {}
    try:
        # Preflight all seven complete role directories before any publish.
        for evidence_id, relative_raw, expected_names in targets:
            parent_relative = PurePosixPath(*relative_raw.parts[:-1])
            source_parent, source_parent_identities = open_directory_chain_at(
                stage_root_fd,
                parent_relative,
                f"{evidence_id} staged raw parent",
                create=False,
            )
            try:
                source_raw, source_identity = open_child_directory_at(
                    source_parent,
                    relative_raw.parts[-1],
                    f"{evidence_id} staged raw",
                )
                try:
                    entry_identities = regular_directory_entries(
                        source_raw,
                        expected_names,
                        f"{evidence_id} staged raw",
                    )
                    os.fsync(source_raw)
                finally:
                    os.close(source_raw)
            finally:
                os.close(source_parent)
            staged[evidence_id] = StagedRawDirectory(
                relative=relative_raw,
                parent_identities=source_parent_identities,
                directory_identity=source_identity,
                entry_identities=entry_identities,
            )
            target_parent, target_parent_identities = open_directory_chain_at(
                run_root_fd,
                parent_relative,
                f"{evidence_id} target raw parent",
                create=True,
            )
            try:
                try:
                    os.stat(
                        relative_raw.parts[-1],
                        dir_fd=target_parent,
                        follow_symlinks=False,
                    )
                except FileNotFoundError:
                    pass
                else:
                    fail(
                        "refusing to overwrite existing raw evidence: "
                        f"{run_root.joinpath(*relative_raw.parts)}"
                    )
                os.fsync(target_parent)
            finally:
                os.close(target_parent)
            final_parents[evidence_id] = target_parent_identities

        for evidence_id, relative_raw, expected_names in targets:
            staged_record = staged[evidence_id]
            parent_relative = PurePosixPath(*relative_raw.parts[:-1])
            source_parent, _ = open_directory_chain_at(
                stage_root_fd,
                parent_relative,
                f"{evidence_id} staged raw parent",
                create=False,
                expected=staged_record.parent_identities,
            )
            target_parent, _ = open_directory_chain_at(
                run_root_fd,
                parent_relative,
                f"{evidence_id} target raw parent",
                create=False,
                expected=final_parents[evidence_id],
            )
            try:
                source_raw, source_identity = open_child_directory_at(
                    source_parent,
                    relative_raw.parts[-1],
                    f"{evidence_id} staged raw",
                    expected=staged_record.directory_identity,
                )
                try:
                    regular_directory_entries(
                        source_raw,
                        expected_names,
                        f"{evidence_id} staged raw",
                        expected_identities=staged_record.entry_identities,
                    )
                    os.fsync(source_raw)
                finally:
                    os.close(source_raw)
                os.fsync(source_parent)
                os.fsync(target_parent)
                atomic_rename_exclusive_at(
                    source_parent,
                    relative_raw.parts[-1],
                    target_parent,
                    relative_raw.parts[-1],
                    str(run_root.joinpath(*relative_raw.parts)),
                )
                target_raw, target_identity = open_child_directory_at(
                    target_parent,
                    relative_raw.parts[-1],
                    f"{evidence_id} published raw",
                    expected=source_identity,
                )
                try:
                    published_entries = regular_directory_entries(
                        target_raw,
                        expected_names,
                        f"{evidence_id} published raw",
                        expected_identities=staged_record.entry_identities,
                    )
                    os.fsync(target_raw)
                finally:
                    os.close(target_raw)
                os.fsync(target_parent)
                published_record = PublishedRawDirectory(
                    path=run_root.joinpath(*relative_raw.parts),
                    relative=relative_raw,
                    run_root_identity=run_root_identity,
                    parent_identities=final_parents[evidence_id],
                    directory_identity=target_identity,
                    entry_identities=published_entries,
                )
                published.append(published_record)
                verify_absolute_directory_identity(
                    run_root,
                    run_root_identity,
                    f"{evidence_id} run root namespace revalidation",
                )
                verify_absolute_directory_identity(
                    stage_root,
                    stage_chain[-1],
                    f"{evidence_id} stage root namespace revalidation",
                )
                current_target_parent, _ = open_directory_chain_at(
                    run_root_fd,
                    parent_relative,
                    f"{evidence_id} target namespace revalidation",
                    create=False,
                    expected=final_parents[evidence_id],
                )
                os.close(current_target_parent)
                current_source_parent, _ = open_directory_chain_at(
                    stage_root_fd,
                    parent_relative,
                    f"{evidence_id} stage namespace revalidation",
                    create=False,
                    expected=staged_record.parent_identities,
                )
                os.close(current_source_parent)
            finally:
                os.close(target_parent)
                os.close(source_parent)
        return published
    except BaseException as original:
        rollback_errors: list[str] = []
        for item in reversed(published):
            try:
                remove_published_raw_at(run_root_fd, item)
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        if rollback_errors:
            fail(
                f"raw publication failed ({original}); rollback preserved "
                f"changed replacement(s): {rollback_errors}"
            )
        raise
    finally:
        os.close(stage_root_fd)
        os.close(run_root_fd)


def stage_path(
    stage_root: Path,
    roles: Mapping[str, Mapping[str, str]],
    evidence_id: str,
    role: str,
) -> Path:
    return stage_root / roles[evidence_id][role]


def validate_primary_artifacts(
    verifier: Wave3Evidence,
    context: Mapping[str, Any],
    stage_root: Path,
    roles: Mapping[str, Mapping[str, str]],
) -> None:
    for evidence_id in SEMANTIC_IDS:
        contract = verifier.leaf_contracts[evidence_id]
        role_paths = roles[evidence_id]
        if list(role_paths) != contract["requiredArtifactRoles"]:
            fail(f"{evidence_id} role order/set drifted")
        for role, relative in role_paths.items():
            candidate = stage_root / relative
            if (
                not candidate.is_file()
                or candidate.is_symlink()
                or candidate.stat().st_size == 0
            ):
                fail(f"{evidence_id}/{role} is missing, symbolic or empty")
            contents = candidate.read_bytes()
            if FORBIDDEN_STRUCTURED_TOKEN.encode() in contents:
                fail(f"{evidence_id}/{role} contains forbidden status")
        primary = stage_root / role_paths[contract["primaryArtifactRole"]]
        verifier._validate_primary_artifact(  # strict shared contract validator
            primary,
            evidence_id,
            dict(context),
            contract["requiredMarkers"],
        )


def combine_regression_log(
    context: Mapping[str, Any],
    w1: Path,
    w2: Path,
) -> bytes:
    return (
        exact_context_line(context, "W3-VER-W2-RETAINED")
        + "\n"
        + "W3_W1_W2_REGRESSION_INPUTS=PASS "
        + f"w1Sha256={sha256_file(w1)} w2Sha256={sha256_file(w2)} "
        + "w1Marker=W3_W1_REGRESSION=PASS "
        + "w2Marker=W3_W2_REGRESSION=PASS\n"
    ).encode("utf-8")


def require_exact_object(
    value: Any,
    keys: set[str],
    label: str,
) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        fail(f"{label} is not an object")
    if set(value) != keys:
        fail(
            f"{label} key closure differs; "
            f"missing={sorted(keys - set(value))}, "
            f"extra={sorted(set(value) - keys)}"
        )
    return value


def validate_flyway_capture(
    value: Any,
    phase: str,
) -> Mapping[str, Any]:
    document = require_exact_object(
        value,
        {
            "schemaVersion",
            "nodeType",
            "phase",
            "database",
            "rows",
            "rowCount",
            "snapshotSha256",
        },
        f"Flyway {phase} capture",
    )
    rows = document["rows"]
    if not isinstance(rows, list):
        fail(f"Flyway {phase} rows are not an array")
    for index, row in enumerate(rows):
        require_exact_object(
            row,
            {
                "installedRank",
                "version",
                "description",
                "type",
                "script",
                "checksum",
                "success",
            },
            f"Flyway {phase} row {index}",
        )
    migration_dir = (
        REPOSITORY_ROOT / "backend/src/main/resources/db/migration"
    )
    migrations: list[tuple[int, str, str]] = []
    for path in migration_dir.glob("V*.sql"):
        match = re.fullmatch(r"V([0-9]+)__(.+)\.sql", path.name)
        if match is None:
            fail(f"unrecognized versioned migration filename: {path.name}")
        migrations.append(
            (
                int(match.group(1)),
                path.name,
                match.group(2).replace("_", " "),
            )
        )
    migrations.sort()
    if (
        not migrations
        or [version for version, _, _ in migrations]
        != list(range(1, migrations[-1][0] + 1))
    ):
        fail("versioned migration sequence is not contiguous and unique")
    cutoff = 6 if phase == "v6-before" else 7 if phase == "target7" else None
    expected_migrations = [
        item for item in migrations if cutoff is None or item[0] <= cutoff
    ]
    exact_rows = all(
        row["installedRank"] == str(index)
        and row["version"] == str(version)
        and row["description"] == description
        and row["type"] == "SQL"
        and row["script"] == script
        and isinstance(row["checksum"], str)
        and re.fullmatch(r"-?[0-9]+", row["checksum"]) is not None
        and row["success"] == "1"
        for index, (row, (version, script, description)) in enumerate(
            zip(rows, expected_migrations, strict=False),
            start=1,
        )
    )
    if (
        document["schemaVersion"] != 1
        or document["nodeType"] != "flyway-history-snapshot"
        or document["phase"] != phase
        or document["database"] != "shenzhou_hr_test"
        or len(rows) != len(expected_migrations)
        or not exact_rows
        or document["rowCount"] != len(rows)
        or document["snapshotSha256"]
        != sha256_bytes(canonical_json_bytes(rows))
    ):
        fail(f"Flyway {phase} capture semantic recomputation differs")
    return document


def validate_w2_fixed_fixture(value: Any) -> Mapping[str, Any]:
    document = require_exact_object(
        value,
        {
            "schemaVersion",
            "nodeType",
            "database",
            "checkpoint",
            "cardinality",
            "fixtureSqlSha256",
            "verdict",
        },
        "W2 fixed fixture capture",
    )
    cardinality = require_exact_object(
        document["cardinality"],
        set(W2_FIXED_ROW_COUNTS),
        "W2 fixed fixture cardinality",
    )
    if (
        document["schemaVersion"] != 1
        or document["nodeType"] != "w2-retained-fixed-fixture"
        or document["database"] != "shenzhou_hr_test"
        or document["checkpoint"] != 6
        or dict(cardinality) != W2_FIXED_ROW_COUNTS
        or document["fixtureSqlSha256"] != W2_FIXTURE_SQL_SHA256
        or document["verdict"] != "PASS"
    ):
        fail("W2 fixed fixture structured evidence differs")
    return document


def validate_w2_snapshot(
    value: Any,
    phase: str,
    *,
    require_fixed_fixture: bool,
) -> Mapping[str, Any]:
    document = require_exact_object(
        value,
        {
            "schemaVersion",
            "nodeType",
            "phase",
            "database",
            "registryPath",
            "registrySha256",
            "rowCounts",
            "rowCount",
            "rows",
            "finalHash",
        },
        f"W2 {phase} snapshot",
    )
    expected_tables = list(W2_FIXED_ROW_COUNTS)
    row_counts = require_exact_object(
        document["rowCounts"],
        set(expected_tables),
        f"W2 {phase} rowCounts",
    )
    if (
        document["schemaVersion"] != 1
        or document["nodeType"] != "w2-retained-snapshot"
        or document["phase"] != phase
        or document["database"] != "shenzhou_hr_test"
        or document["registryPath"]
        != W2_REGISTRY.relative_to(REPOSITORY_ROOT).as_posix()
        or document["registrySha256"] != W2_REGISTRY_SHA256
        or any(
            not isinstance(row_counts[table], int)
            or isinstance(row_counts[table], bool)
            or row_counts[table] < 0
            for table in expected_tables
        )
    ):
        fail(f"W2 {phase} snapshot root semantics differ")
    rows = document["rows"]
    if not isinstance(rows, list):
        fail(f"W2 {phase} rows are not an array")
    actual_counts = {table: 0 for table in expected_tables}
    actual_keys: list[tuple[str, tuple[str, ...]]] = []
    lines: list[str] = []
    for index, raw_row in enumerate(rows):
        row = require_exact_object(
            raw_row,
            {"table", "pk", "row", "rowHash", "snapshotLine"},
            f"W2 {phase} row {index}",
        )
        table = row["table"]
        primary_key = row["pk"]
        framed = row["row"]
        row_hash = row["rowHash"]
        snapshot_line = row["snapshotLine"]
        if (
            not isinstance(table, str)
            or table not in actual_counts
            or not isinstance(primary_key, list)
            or len(primary_key) != 1
            or any(not isinstance(item, str) or not item for item in primary_key)
            or not isinstance(framed, str)
            or not framed.startswith(
                f"R:{len(str(table).encode('utf-8'))}:{table}"
            )
            or not isinstance(row_hash, str)
            or not SHA256_PATTERN.fullmatch(row_hash)
            or row_hash != sha256_bytes(framed.encode("utf-8"))
            or snapshot_line
            != (
                f"L:{len(framed.encode('utf-8'))}:{framed}"
                f"64:{row_hash}"
            )
        ):
            fail(f"W2 {phase} row {index} framing/digest differs")
        actual_counts[str(table)] += 1
        actual_keys.append((str(table), tuple(primary_key)))
        lines.append(str(snapshot_line))
    if (
        len(set(actual_keys)) != len(actual_keys)
        or document["rowCount"] != len(rows)
        or sum(row_counts.values()) != len(rows)
        or dict(row_counts) != actual_counts
        or document["finalHash"]
        != sha256_bytes("".join(lines).encode("utf-8"))
    ):
        fail(f"W2 {phase} rowCount/finalHash recomputation differs")
    if require_fixed_fixture:
        fixed = load_w2_fixed_row_oracle()
        if (
            actual_counts != W2_FIXED_ROW_COUNTS
            or set(actual_keys) != W2_FIXED_PRIMARY_KEYS
            or rows != fixed["canonicalRows"]
            or document["finalHash"] != fixed["finalHash"]
        ):
            fail(
                f"W2 {phase} snapshot is not the exact fixed "
                "six-table/seven-row fixture"
            )
    return document


def validate_v4_schema_capture(value: Any) -> Mapping[str, Any]:
    expected = semantic_capture.expected_v4_schema()
    document = require_exact_object(
        value,
        {
            *expected,
            "database",
            "actualTables",
            "differences",
            "verdict",
        },
        "W2 V4 schema capture",
    )
    expected_root = {
        key: document[key] for key in expected
    }
    if (
        expected_root != expected
        or document["database"] != "shenzhou_hr_test"
        or document["actualTables"] != expected["tables"]
        or document["differences"] != []
        or document["verdict"] != "PASS"
    ):
        fail("W2 V4 schema structured recomputation differs")
    return document


def expected_seed_export_rows(
    fixture: Mapping[str, Any],
) -> list[dict[str, Any]]:
    return [
        {
            "templateId": seed["templateId"],
            "policyKind": seed["policyKind"],
            "scopeId": seed["scopeId"],
            "legalEntityId": fixture["baselineLegalEntityId"],
            "scopedVersionId": seed["scopedVersionId"],
            "versionNumber": "1",
            "effectiveFrom": fixture["effectiveFrom"],
            "effectiveTo": None,
            "snapshotDigest": seed["snapshotDigest"],
            "statusColumnCount": "0",
            "lifecycleEventId": seed["lifecycleEventId"],
            "lifecycleAction": "PUBLISHED",
            "lifecycleEffectiveFrom": fixture["effectiveFrom"],
            "parameters": seed["parameters"],
            "canonicalSnapshot": seed["canonicalSnapshot"],
        }
        for seed in fixture["seeds"]
    ]


def validate_seed_captures(
    export_value: Any,
    digest_value: Any,
    extras_value: Any,
    fixture: Mapping[str, Any],
) -> tuple[Mapping[str, Any], Mapping[str, Any], Mapping[str, Any]]:
    export = require_exact_object(
        export_value,
        {
            "schemaVersion",
            "nodeType",
            "database",
            "fixtureSha256",
            "rows",
            "rowCount",
            "verdict",
        },
        "target7 seed export",
    )
    expected_rows = expected_seed_export_rows(fixture)
    if not isinstance(export["rows"], list):
        fail("target7 seed export rows are not an array")
    expected_row_keys = set(expected_rows[0])
    for index, row in enumerate(export["rows"]):
        require_exact_object(
            row, expected_row_keys, f"target7 seed export row {index}"
        )
    for row in export["rows"]:
        canonical_snapshot = row["canonicalSnapshot"]
        digest = row["snapshotDigest"]
        if (
            not isinstance(canonical_snapshot, str)
            or not isinstance(digest, str)
            or digest
            != sha256_bytes(canonical_snapshot.encode("utf-8"))
        ):
            fail("target7 seed export digest recomputation differs")
    if (
        export["schemaVersion"] != 1
        or export["nodeType"] != "target7-seed-database-export"
        or export["database"] != "shenzhou_hr_test"
        or export["fixtureSha256"] != SEED_ORACLE_SHA256
        or export["rowCount"] != len(export["rows"])
        or export["rows"] != expected_rows
        or export["verdict"] != "PASS"
    ):
        fail("target7 seed export fixed-fixture semantics differ")

    digest_report = require_exact_object(
        digest_value,
        {
            "schemaVersion",
            "nodeType",
            "database",
            "algorithm",
            "rows",
            "verdict",
        },
        "target7 digest recomputation",
    )
    expected_digest_rows = [
        {
            "policyKind": row["policyKind"],
            "canonicalSnapshotUtf8Bytes": len(
                row["canonicalSnapshot"].encode("utf-8")
            ),
            "expectedDigest": row["snapshotDigest"],
            "recomputedDigest": sha256_bytes(
                row["canonicalSnapshot"].encode("utf-8")
            ),
            "match": True,
        }
        for row in expected_rows
    ]
    if not isinstance(digest_report["rows"], list):
        fail("target7 digest rows are not an array")
    for index, row in enumerate(digest_report["rows"]):
        require_exact_object(
            row,
            {
                "policyKind",
                "canonicalSnapshotUtf8Bytes",
                "expectedDigest",
                "recomputedDigest",
                "match",
            },
            f"target7 digest row {index}",
        )
    if (
        digest_report["schemaVersion"] != 1
        or digest_report["nodeType"]
        != "target7-seed-digest-recomputation"
        or digest_report["database"] != "shenzhou_hr_test"
        or digest_report["algorithm"]
        != "SHA-256(RFC8785-compatible canonical JSON UTF-8)"
        or digest_report["rows"] != expected_digest_rows
        or digest_report["verdict"] != "PASS"
    ):
        fail("target7 digest structured recomputation differs")

    extras = require_exact_object(
        extras_value,
        {
            "schemaVersion",
            "nodeType",
            "database",
            "counts",
            "scopedVersionStatusColumnCount",
            "verdict",
        },
        "target7 no-extras query",
    )
    counts = require_exact_object(
        extras["counts"],
        {
            "templates",
            "scopes",
            "versions",
            "publishedLifecycleEvents",
            "bindingFamilies",
            "bindingRevisions",
        },
        "target7 no-extras counts",
    )
    expected_counts = {
        "templates": 3,
        "scopes": 3,
        "versions": 3,
        "publishedLifecycleEvents": 3,
        "bindingFamilies": 0,
        "bindingRevisions": 0,
    }
    if (
        extras["schemaVersion"] != 1
        or extras["nodeType"] != "target7-seed-no-extras-query"
        or extras["database"] != "shenzhou_hr_test"
        or dict(counts) != expected_counts
        or extras["scopedVersionStatusColumnCount"] != 0
        or extras["verdict"] != "PASS"
    ):
        fail("target7 no-extras structured evidence differs")
    return export, digest_report, extras


def validate_retained_canonical_golden(
    value: Any,
    registry_path: Path,
) -> Mapping[str, Any]:
    document = require_exact_object(
        value,
        {
            "version",
            "framing",
            "fieldVectors",
            "sourceRows",
            "orderedRows",
            "finalHash",
        },
        "retained canonical golden",
    )
    framing = require_exact_object(
        document["framing"],
        {"value", "row", "line", "final"},
        "retained canonical golden framing",
    )
    if (
        document["version"] != 2
        or not all(isinstance(value, str) and value for value in framing.values())
        or not isinstance(document["fieldVectors"], list)
        or not isinstance(document["sourceRows"], list)
        or not isinstance(document["orderedRows"], list)
    ):
        fail("retained canonical golden root semantics differ")
    for index, vector in enumerate(document["fieldVectors"]):
        vector = require_exact_object(
            vector,
            (
                {"name", "type", "inputBase64", "expected"}
                if isinstance(vector, Mapping) and "inputBase64" in vector
                else {"name", "type", "input", "expected"}
            ),
            f"retained canonical field vector {index}",
        )
        input_value = vector.get("inputBase64", vector.get("input"))
        if retained.frame(vector["type"], input_value) != vector["expected"]:
            fail(f"retained canonical field vector {index} differs")
    registry = retained.load_registry(registry_path)
    actual_rows = retained.canonical_rows(
        registry, document["sourceRows"]
    )
    if actual_rows != document["orderedRows"]:
        fail("retained canonical ordered rows recomputation differs")
    final_hash = sha256_bytes(
        "".join(
            row["snapshotLine"] for row in actual_rows
        ).encode("utf-8")
    )
    if (
        document["finalHash"] != final_hash
        or not SHA256_PATTERN.fullmatch(str(document["finalHash"]))
    ):
        fail("retained canonical finalHash recomputation differs")
    return document


def subset_diff(
    context: Mapping[str, Any],
    before: Mapping[str, Any],
    after: Mapping[str, Any],
) -> dict[str, Any]:
    before = validate_w2_snapshot(
        before, "v6-before", require_fixed_fixture=True
    )
    after = validate_w2_snapshot(
        after, "latest-after", require_fixed_fixture=True
    )

    def keys(snapshot: Mapping[str, Any]) -> dict[tuple[str, tuple[str, ...]], str]:
        rows = snapshot.get("rows")
        if (
            snapshot.get("registrySha256") != W2_REGISTRY_SHA256
            or not isinstance(rows, list)
            or snapshot.get("rowCount") != len(rows)
        ):
            fail("W2 retained snapshot registry/cardinality binding differs")
        result = {
            (value["table"], tuple(value["pk"])): value["rowHash"]
            for value in rows
        }
        if len(result) != len(rows):
            fail("W2 retained snapshot contains duplicate primary keys")
        return result

    before_rows = keys(before)
    after_rows = keys(after)
    missing = sorted(set(before_rows) - set(after_rows))
    changed = sorted(
        key
        for key in set(before_rows) & set(after_rows)
        if before_rows[key] != after_rows[key]
    )
    if set(before_rows) != W2_FIXED_PRIMARY_KEYS:
        fail("W2 retained before snapshot fixed primary-key closure differs")
    if missing or changed:
        fail(f"W2 retained subset differs: missing={missing}, changed={changed}")
    return base_document(
        context,
        "W3-VER-W2-RETAINED",
        "w2-retained-subset-diff",
        beforeRowCount=len(before_rows),
        afterRowCount=len(after_rows),
        unchangedRows=len(before_rows),
        missingRows=[],
        changedRows=[],
        verdict="PASS",
    )


def flyway_max_version(history: Mapping[str, Any]) -> int:
    versions = [
        int(value["version"])
        for value in history["rows"]
        if (
            value["type"] == "SQL"
            and value["success"] == "1"
            and isinstance(value["version"], str)
            and value["version"].isdigit()
        )
    ]
    if not versions:
        fail("Flyway history has no successful SQL migration")
    return max(versions)


def filtered_lines(text: str, patterns: Iterable[str]) -> str:
    expressions = [re.compile(value) for value in patterns]
    lines = [
        line
        for line in text.splitlines()
        if any(expression.fullmatch(line) for expression in expressions)
    ]
    if not lines:
        fail("semantic transcript filter matched no lines")
    return "\n".join(lines) + "\n"


def require_transcript_markers(
    text: str,
    markers: Mapping[str, tuple[int, str]],
    label: str,
) -> dict[str, list[tuple[str, int]]]:
    lines_with_offsets: list[tuple[str, int]] = []
    offset = 0
    for raw_line in text.splitlines(keepends=True):
        line = raw_line.rstrip("\r\n")
        lines_with_offsets.append((line, offset))
        offset += len(raw_line.encode("utf-8"))
    differences: dict[str, Any] = {}
    result: dict[str, list[tuple[str, int]]] = {}
    for marker, (expected, raw_pattern) in markers.items():
        expression = re.compile(raw_pattern)
        suspicious = [
            (line, position)
            for line, position in lines_with_offsets
            if marker in line
        ]
        matches = [
            (line, position)
            for line, position in suspicious
            if expression.fullmatch(line)
        ]
        result[marker] = matches
        if len(matches) != expected or len(suspicious) != len(matches):
            differences[marker] = {
                "expectedExactLines": expected,
                "exactLines": len(matches),
                "markerContainingLines": len(suspicious),
            }
    if differences:
        fail(f"{label} marker cardinality differs: {differences}")
    return result


def parse_tsv_bytes(contents: bytes, label: str) -> dict[str, str]:
    try:
        text = contents.decode("utf-8", errors="strict")
    except UnicodeError as error:
        fail(f"invalid identity TSV encoding: {label}: {error}")
    values: dict[str, str] = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator != "=" or not key or key in values:
            fail(f"invalid identity TSV: {label}")
        values[key] = value
    return values


def produce(
    run_context_path: Path,
    runtime_env_path: Path,
    confirmation: str,
) -> list[PublishedRawDirectory]:
    if Path.cwd().resolve(strict=True) != REPOSITORY_ROOT.resolve(strict=True):
        fail("run from the exact repository root")
    fixed_w2_registry, fixed_seed_oracle = validate_fixed_semantic_oracles()
    verifier = Wave3Evidence(REPOSITORY_ROOT, CONTRACT_PATH)
    if not run_context_path.is_absolute():
        fail("--run-context must be absolute")
    if run_context_path.name != "run-context.json":
        fail("--run-context must name run-context.json")
    try:
        run_context_relative = run_context_path.relative_to(WAVE3_RUNS_ROOT)
    except ValueError:
        fail("--run-context escapes the fixed W3 runs root")
    if (
        len(run_context_relative.parts) != 2
        or run_context_relative.parts[1] != "run-context.json"
    ):
        fail("--run-context is not a direct current-run descriptor")
    run_root = run_context_path.parent
    run_root_fd, _, run_chain = open_absolute_directory_no_symlinks(
        run_root, "semantic producer run root"
    )
    if not run_chain:
        os.close(run_root_fd)
        fail("semantic producer run root identity is unavailable")
    run_root_identity = run_chain[-1]
    try:
        context_bytes_before = read_regular_bytes_at(
            run_root_fd, "run-context.json", "run context"
        )
    finally:
        os.close(run_root_fd)
    context = verifier._context(run_root)
    run_root_check_fd, _, run_check_chain = (
        open_absolute_directory_no_symlinks(
            run_root, "semantic producer run root"
        )
    )
    try:
        if (
            not run_check_chain
            or run_check_chain[-1] != run_root_identity
            or read_regular_bytes_at(
                run_root_check_fd, "run-context.json", "run context"
            )
            != context_bytes_before
        ):
            fail("run context/root identity changed while loading")
    finally:
        os.close(run_root_check_fd)
    if run_context_path != run_root / "run-context.json":
        fail("run-context path is not the active run descriptor")
    if not DATABASE_IDENTITY_PATTERN.fullmatch(context["databaseIdentity"]):
        fail("run context is not bound to exact MySQL 8.4.10 test identity")
    if confirmation != f"shenzhou_hr_test:{context['runId']}":
        fail(
            "execute requires exact --confirm-test-table-rebuild "
            f"shenzhou_hr_test:{context['runId']}"
        )
    if (run_root / "source/end.json").exists():
        fail("semantic leaves cannot be produced after source END")
    snapshot = verifier.compute_source_snapshot()
    if snapshot.tree_hash != context["sourceTreeHash"]:
        fail("current source differs from frozen run context before execution")
    runtime_values = validate_runtime_environment(
        runtime_env_path, context["databaseIdentity"]
    )
    if runtime_values["SHENZHOUHR_W3_DB_IDENTITY"] != context[
        "databaseIdentity"
    ]:
        fail("runtime environment database identity differs")
    if not runtime_values.get("SHENZHOUHR_DEV_DB_PASSWORD"):
        fail(
            "runtime environment requires SHENZHOUHR_DEV_DB_PASSWORD "
            "for the all-gate dev privilege probe"
        )
    plan = load_plan_for_run(verifier, run_root)
    roles = artifact_map(plan)
    for evidence_id, role_paths in roles.items():
        for relative in role_paths.values():
            candidate = run_root / relative
            if candidate.exists() or candidate.is_symlink():
                fail(f"refusing to overwrite raw evidence: {evidence_id}")
    w1_log = validate_build_regression_log(
        verifier,
        context,
        plan,
        "W3-VER-W1-REGRESSION",
        "W3_W1_REGRESSION=PASS",
    )
    w2_log = validate_build_regression_log(
        verifier,
        context,
        plan,
        "W3-VER-W2-REGRESSION",
        "W3_W2_REGRESSION=PASS",
    )
    stage_root, stage_root_identity = create_private_stage_directory(
        run_root,
        expected_run_root_identity=run_root_identity,
    )
    published: list[PublishedRawDirectory] = []
    mysql_capture_fd: int | None = None
    mysql_identity_fd: int | None = None
    try:
        # W2 v2 public-contract oracle.
        governance_output, _, _ = run_command(
            [
                sys.executable,
                str(PUBLIC_DRIVER),
                "--governance",
                "--repo",
                str(REPOSITORY_ROOT),
                "--expected",
                str(PUBLIC_EXPECTED),
                "--decision",
                str(PUBLIC_SUPERSESSION),
            ],
            label="W2 public oracle governance",
        )
        actual_a = stage_root / "internal/public-actual-a.json"
        actual_b = stage_root / "internal/public-actual-b.json"
        actual_a.parent.mkdir(parents=True, exist_ok=True)
        extract_a, _, _ = run_command(
            [str(PUBLIC_EXTRACTOR), str(REPOSITORY_ROOT), str(actual_a)],
            label="W2 public oracle extract A",
        )
        extract_b, _, _ = run_command(
            [str(PUBLIC_EXTRACTOR), str(REPOSITORY_ROOT), str(actual_b)],
            label="W2 public oracle extract B",
        )
        if actual_a.read_bytes() != actual_b.read_bytes():
            fail("W2 public oracle current-source exports are nondeterministic")
        exact_output, _, _ = run_command(
            [
                sys.executable,
                str(PUBLIC_DRIVER),
                "--actual",
                str(actual_a),
                "--expected",
                str(PUBLIC_EXPECTED),
            ],
            label="W2 public oracle exact diff",
        )
        mutant_output, _, _ = run_command(
            [
                str(PUBLIC_EXTRACTOR),
                "--self-test",
                str(REPOSITORY_ROOT),
            ],
            label="W2 public oracle source-mutant self-test",
        )
        require_transcript_markers(
            governance_output,
            {
                "W2_PUBLIC_CONTRACT_V1_SUPERSESSION=PASS": (
                    1,
                    (
                        r"W2_PUBLIC_CONTRACT_V1_SUPERSESSION=PASS "
                        r"status=INVALIDATED_SUPERSEDED v1Integrity=PASS "
                        r"authoritativeBaseline=[0-9a-f]{40} "
                        r"checkpointCommit=PASS baselineBlobs=7 "
                        r"baselineContract=PASS "
                        r"h2Adjustment=V4-PERIOD-CHECK-H2-"
                        r"SYNONYMOUS-MIRROR currentGate=v2"
                    ),
                )
            },
            "W2 public governance transcript",
        )
        require_transcript_markers(
            mutant_output,
            {
                "W2_V2_MUTATION_KILLED": (
                    20,
                    (
                        r"W2_V2_MUTATION_KILLED name=[^ ]+ "
                        r"source=[^ ]+ surface=[^ ]+ "
                        r"verifierExit=[1-9][0-9]*"
                    ),
                ),
                "W2_PUBLIC_CONTRACT_V2_SELF_TEST=PASS": (
                    1,
                    (
                        r"W2_PUBLIC_CONTRACT_V2_SELF_TEST=PASS "
                        r"productionPath=true sourceMutations=20"
                    ),
                ),
            },
            "W2 public source-mutant transcript",
        )
        actual_public = load_json(actual_a, "W2 public actual export")
        expected_public = load_json(PUBLIC_EXPECTED, "W2 public oracle")
        if actual_public != expected_public:
            fail("W2 public actual JSON differs from the fixed oracle")
        try:
            public_summary = json.loads(exact_output)
        except json.JSONDecodeError as error:
            fail(f"W2 public exact-diff summary is invalid JSON: {error}")
        require_exact_object(
            public_summary,
            {
                "gate",
                "status",
                "javaFiles",
                "mapperStatements",
                "openapiOperations",
                "h2Tables",
                "attendanceGroupScope",
            },
            "W2 public exact-diff summary",
        )
        if public_summary != {
            "gate": "w2-public-contract-v2",
            "status": "PASS",
            "javaFiles": len(actual_public["java"]),
            "mapperStatements": len(
                actual_public["mapper"]["statementClosure"][
                    "xmlStatementIds"
                ]
            ),
            "openapiOperations": len(
                actual_public["openapi"]["operations"]
            ),
            "h2Tables": len(actual_public["h2"]["tables"]),
            "attendanceGroupScope": "retained",
        }:
            fail("W2 public exact-diff structured summary differs")
        public_id = "W3-VER-W2-PUBLIC-ORACLE"
        public_log = (
            exact_context_line(context, public_id)
            + "\n"
            + governance_output
            + exact_output
            + "W3_W2_PUBLIC_ORACLE=PASS fixtureVersion=2 deterministic=PASS\n"
        )
        write_new(
            stage_path(stage_root, roles, public_id, "public-oracle-log"),
            public_log.encode("utf-8"),
        )
        write_json(
            stage_path(stage_root, roles, public_id, "v1-integrity-report"),
            base_document(
                context,
                public_id,
                "w2-public-v1-integrity-report",
                governanceOutputSha256=sha256_bytes(
                    governance_output.encode("utf-8")
                ),
                governanceOutput=governance_output.splitlines(),
                verdict="PASS",
            ),
        )
        copy_new(
            PUBLIC_SUPERSESSION,
            stage_path(
                stage_root, roles, public_id, "supersession-decision"
            ),
        )
        copy_new(
            PUBLIC_EXPECTED,
            stage_path(
                stage_root, roles, public_id, "v2-provenance-fixture"
            ),
        )
        extractor_hash = sha256_file(PUBLIC_EXTRACTOR)
        write_new(
            stage_path(stage_root, roles, public_id, "extractor-hash"),
            (
                f"{extractor_hash}  "
                f"{PUBLIC_EXTRACTOR.relative_to(REPOSITORY_ROOT).as_posix()}\n"
            ).encode(),
        )
        copy_new(
            actual_a,
            stage_path(stage_root, roles, public_id, "actual-export"),
        )
        write_new(
            stage_path(stage_root, roles, public_id, "exact-diff"),
            (
                "W2_PUBLIC_CONTRACT_V2_EXACT_DIFF=PASS\n"
                f"actualSha256={sha256_file(actual_a)}\n"
                f"repeatSha256={sha256_file(actual_b)}\n"
                f"extractAOutputSha256={sha256_bytes(extract_a.encode())}\n"
                f"extractBOutputSha256={sha256_bytes(extract_b.encode())}\n"
            ).encode(),
        )
        write_new(
            stage_path(stage_root, roles, public_id, "source-mutant-log"),
            mutant_output.encode("utf-8"),
        )

        # Retained canonicalizer against combined review-owned registries.
        if sha256_file(W3_REGISTRY) != W3_REGISTRY_SHA256:
            fail("review-owned W3 retained registry SHA256 drifted")
        if sha256_file(CANONICAL_GOLDEN) != CANONICAL_GOLDEN_SHA256:
            fail("review-owned retained golden SHA256 drifted")
        w2_registry = fixed_w2_registry
        w3_registry = load_json(W3_REGISTRY, "W3 retained registry")
        combined_tables = [
            *w2_registry["tables"],
            *w3_registry["tables"],
        ]
        combined_names = [value["table"] for value in combined_tables]
        if len(combined_names) != 28 or len(set(combined_names)) != 28:
            fail("combined retained registry must contain 28 unique tables")
        combined_registry = {
            "version": 1,
            "nodeType": "wave3-combined-retained-registry",
            "sources": [
                {
                    "path": W2_REGISTRY.relative_to(
                        REPOSITORY_ROOT
                    ).as_posix(),
                    "sha256": sha256_file(W2_REGISTRY),
                    "tables": 6,
                },
                {
                    "path": W3_REGISTRY.relative_to(
                        REPOSITORY_ROOT
                    ).as_posix(),
                    "sha256": sha256_file(W3_REGISTRY),
                    "tables": 22,
                },
            ],
            "tables": combined_tables,
        }
        canonical_id = "W3-VER-CANONICALIZER"
        combined_path = stage_path(
            stage_root, roles, canonical_id, "retained-registry"
        )
        write_json(combined_path, combined_registry)
        validate_retained_canonical_golden(
            load_json(CANONICAL_GOLDEN, "retained canonical golden"),
            combined_path,
        )
        base_command = [
            sys.executable,
            str(CANONICALIZER),
            "--fixture",
            str(CANONICAL_GOLDEN),
            "--registry",
            str(combined_path),
        ]
        environment_a = {
            **os.environ,
            "LC_ALL": "C",
            "LANG": "C",
            "TZ": "UTC",
        }
        environment_b = {
            **os.environ,
            "LC_ALL": "zh_CN.UTF-8",
            "LANG": "zh_CN.UTF-8",
            "TZ": "Asia/Shanghai",
        }
        canonical_a, _, _ = run_command(
            base_command,
            environment=environment_a,
            label="canonicalizer C/UTC",
        )
        canonical_b, _, _ = run_command(
            base_command,
            environment=environment_b,
            label="canonicalizer zh_CN/Asia-Shanghai",
        )
        if canonical_a != canonical_b:
            fail("retained canonicalizer output differs by locale/timezone")
        canonical_self, _, _ = run_command(
            [*base_command, "--self-test"],
            environment=environment_a,
            label="canonicalizer production self-test",
        )
        if (
            canonical_a
            != '{"marker":"W3_RETAINED_CANONICALIZER=PASS"}\n'
            or canonical_b != canonical_a
            or canonical_self
            != (
                '{"marker":"W3_RETAINED_CANONICALIZER_SELF_TEST=PASS",'
                '"locales":2}\n'
            )
        ):
            fail("retained canonicalizer emitted non-exact JSON markers")
        canonical_log = (
            exact_context_line(context, canonical_id)
            + "\n"
            + canonical_a
            + canonical_b
            + canonical_self
            + "W3_RETAINED_CANONICAL_GOLDENS=PASS\n"
        )
        write_new(
            stage_path(stage_root, roles, canonical_id, "canonicalizer-log"),
            canonical_log.encode("utf-8"),
        )
        write_new(
            stage_path(
                stage_root,
                roles,
                canonical_id,
                "canonicalizer-executable-hash",
            ),
            (
                f"{sha256_file(CANONICALIZER)}  "
                f"{CANONICALIZER.relative_to(REPOSITORY_ROOT).as_posix()}\n"
            ).encode(),
        )
        copy_new(
            CANONICAL_GOLDEN,
            stage_path(stage_root, roles, canonical_id, "golden-vectors"),
        )
        write_new(
            stage_path(
                stage_root,
                roles,
                canonical_id,
                "locale-timezone-repeat-diff",
            ),
            (
                "W3_CANONICAL_LOCALE_TIMEZONE_DIFF=PASS\n"
                f"cUtcSha256={sha256_bytes(canonical_a.encode())}\n"
                f"zhCnShanghaiSha256={sha256_bytes(canonical_b.encode())}\n"
                "byteIdentical=true\n"
            ).encode(),
        )

        # Controller/OpenAPI closure, independent of Maven output.
        openapi_documents = produce_openapi_documents(context)
        openapi_id = "W3-VER-OPENAPI-CLOSURE"
        openapi_log = (
            exact_context_line(context, openapi_id)
            + "\n"
            + "W3_OPENAPI_SEMANTIC_COUNTS "
            + f"controllers={openapi_documents['controller']['operationCount']} "
            + f"openapi={openapi_documents['openapi']['operationCount']} "
            + "unresolvedRefs=0 requestSchemas="
            + str(
                len(
                    openapi_documents["report"][
                        "legalIllegalInstances"
                    ]
                )
            )
            + "\n"
            + "W3_CONTROLLER_OPENAPI_CLOSURE=PASS\n"
        )
        write_new(
            stage_path(
                stage_root, roles, openapi_id, "openapi-closure-log"
            ),
            openapi_log.encode(),
        )
        for role, key in (
            ("controller-operation-export", "controller"),
            ("openapi-operation-export", "openapi"),
            ("operation-set-diff", "diff"),
            ("resolved-ref-instance-report", "report"),
        ):
            write_json(
                stage_path(stage_root, roles, openapi_id, role),
                openapi_documents[key],
            )

        # Current-run exact isolated identity. `verify` never starts/stops.
        mysql_identity_dir = stage_root / "internal/mysql-identity"
        mysql_identity_fd, identity_directory_identity = (
            create_private_stage_subdirectory(
                stage_root,
                stage_root_identity,
                PurePosixPath("internal/mysql-identity"),
                "isolated MySQL identity evidence directory",
            )
        )
        if os.listdir(mysql_identity_fd):
            fail("isolated MySQL identity evidence directory is not empty")
        mysql_verify_output, _, _ = run_command(
            [
                "bash",
                str(MYSQL_ISOLATION),
                "--execute",
                "--run-id",
                context["runId"],
                "--evidence-dir",
                str(mysql_identity_dir),
                "verify",
            ],
            label="isolated MySQL 8.4.10 current-run verify",
        )
        verify_absolute_directory_identity(
            mysql_identity_dir,
            identity_directory_identity,
            "isolated MySQL identity evidence directory",
        )
        if (
            object_identity(os.fstat(mysql_identity_fd))
            != identity_directory_identity
        ):
            fail("isolated MySQL identity evidence descriptor changed")
        identity_entry_identity_tuple = regular_directory_entries(
            mysql_identity_fd,
            {
                "verify-run-context.tsv",
                "verify.log",
                "verify-mysql8410-server-identity.tsv",
                "existing-mysql8034-verify-before.tsv",
                "existing-mysql8034-verify-after.tsv",
            },
            "isolated MySQL identity evidence directory",
        )
        identity_entry_identities = dict(identity_entry_identity_tuple)

        def read_identity_evidence(filename: str, label: str) -> bytes:
            return read_regular_bytes_at(
                mysql_identity_fd,
                filename,
                label,
                expected_identity=identity_entry_identities[filename],
            )

        verify_log_bytes = read_identity_evidence(
            "verify.log", "isolated MySQL verify transcript"
        )
        if verify_log_bytes != mysql_verify_output.encode("utf-8"):
            fail("isolated MySQL verify transcript/stdout differs byte-for-byte")
        verify_context_bytes = read_identity_evidence(
            "verify-run-context.tsv",
            "isolated MySQL verify run context",
        )
        verify_context = parse_tsv_bytes(
            verify_context_bytes, "verify-run-context.tsv"
        )
        expected_verify_context = {
            "command": "verify",
            "run_id": context["runId"],
            "source_archive": "mysql-8.4.10.tar.gz",
            "source_url": (
                "https://cdn.mysql.com/Downloads/MySQL-8.4/"
                "mysql-8.4.10.tar.gz"
            ),
            "source_sha256": MYSQL_TARBALL_SHA256,
            "ld_wrapper": str(MYSQL_LD_WRAPPER),
            "ld_wrapper_sha256": sha256_file(MYSQL_LD_WRAPPER),
            "cxx_wrapper": str(MYSQL_CXX_WRAPPER),
            "cxx_wrapper_sha256": sha256_file(MYSQL_CXX_WRAPPER),
            "root": str(MYSQL_ROOT),
            "basedir": str(MYSQL_ROOT / "install"),
            "datadir": str(MYSQL_ROOT / "data"),
            "host": "127.0.0.1",
            "port": "13306",
            "socket": str(MYSQL_ROOT / "run/mysql8410.sock"),
            "pid_file": str(MYSQL_ROOT / "run/mysql8410.pid"),
            "error_log": str(MYSQL_ROOT / "log/mysql8410.err"),
        }
        if verify_context != expected_verify_context:
            fail("isolated MySQL verify run-context TSV semantics differ")

        mysql_capture_dir = stage_root / "internal/mysql-semantic-captures"
        mysql_capture_fd, capture_identity = (
            create_private_stage_subdirectory(
                stage_root,
                stage_root_identity,
                PurePosixPath("internal/mysql-semantic-captures"),
                "MySQL semantic capture directory",
            )
        )
        if os.listdir(mysql_capture_fd):
            fail("MySQL semantic capture directory is not empty")
        mysql_all_output, _, _ = run_command(
            [
                "bash",
                str(MYSQL_WAVE3),
                "--env-file",
                str(runtime_env_path),
                "--execute",
                "--run-id",
                context["runId"],
                "--confirm-test-table-rebuild",
                confirmation,
                "--semantic-evidence-dir",
                str(mysql_capture_dir),
                "all",
            ],
            label="W3 current-run MySQL semantic all gate",
        )
        current_capture_fd, _, current_capture_chain = (
            open_absolute_directory_no_symlinks(
                mysql_capture_dir,
                "MySQL semantic capture directory",
            )
        )
        try:
            if (
                not current_capture_chain
                or current_capture_chain[-1] != capture_identity
                or object_identity(os.fstat(current_capture_fd))
                != object_identity(os.fstat(mysql_capture_fd))
            ):
                fail("MySQL semantic capture directory identity changed")
        finally:
            os.close(current_capture_fd)
        required_capture_names = {
            "flyway-v6-before.json",
            "flyway-target7.json",
            "flyway-latest-after.json",
            "flyway-repeat-after.json",
            "flyway-empty-latest.json",
            "w2-fixed-fixture.json",
            "w2-v4-schema-oracle.json",
            "w2-before-snapshot.json",
            "w2-after-snapshot.json",
            "target7-database-export.json",
            "target7-digest-recomputation.json",
            "target7-no-extras-query.json",
        }
        capture_entry_identity_tuple = regular_directory_entries(
            mysql_capture_fd,
            required_capture_names,
            "MySQL semantic capture directory",
        )
        capture_entry_identities = dict(capture_entry_identity_tuple)

        def read_capture(filename: str, label: str) -> bytes:
            return read_regular_bytes_at(
                mysql_capture_fd,
                filename,
                label,
                expected_identity=capture_entry_identities[filename],
            )

        def load_capture(filename: str, label: str) -> Any:
            return load_json_at(
                mysql_capture_fd,
                filename,
                label,
                expected_identity=capture_entry_identities[filename],
            )

        # W2 retained leaf.
        retained_id = "W3-VER-W2-RETAINED"
        before = load_capture(
            "w2-before-snapshot.json",
            "W2 before snapshot",
        )
        after = load_capture(
            "w2-after-snapshot.json",
            "W2 after snapshot",
        )
        schema_oracle = load_capture(
            "w2-v4-schema-oracle.json",
            "W2 V4 schema oracle",
        )
        validate_w2_fixed_fixture(
            load_capture(
                "w2-fixed-fixture.json",
                "W2 fixed fixture",
            )
        )
        schema_oracle = validate_v4_schema_capture(schema_oracle)
        difference = subset_diff(context, before, after)
        retained_log = (
            exact_context_line(context, retained_id)
            + "\n"
            + "W3_W2_V4_SCHEMA=PASS authoritySha256="
            + str(schema_oracle["authoritySha256"])
            + "\n"
            + f"W3_W2_RETAINED_ROWS=PASS before={before['rowCount']} "
            + f"after={after['rowCount']} unchanged={before['rowCount']}\n"
            + "W3_W2_RETAINED_SUBSET=PASS\n"
        )
        write_new(
            stage_path(
                stage_root, roles, retained_id, "retained-verification-log"
            ),
            retained_log.encode(),
        )
        copy_new(
            W2_REGISTRY,
            stage_path(stage_root, roles, retained_id, "w2-registry"),
        )
        write_new(
            stage_path(
                stage_root, roles, retained_id, "v4-schema-oracle"
            ),
            read_capture(
                "w2-v4-schema-oracle.json",
                "W2 V4 schema oracle",
            ),
        )
        write_new(
            stage_path(stage_root, roles, retained_id, "before-snapshot"),
            read_capture(
                "w2-before-snapshot.json",
                "W2 before snapshot",
            ),
        )
        write_new(
            stage_path(stage_root, roles, retained_id, "after-snapshot"),
            read_capture(
                "w2-after-snapshot.json",
                "W2 after snapshot",
            ),
        )
        write_json(
            stage_path(stage_root, roles, retained_id, "subset-diff"),
            difference,
        )
        write_new(
            stage_path(
                stage_root, roles, retained_id, "w1-w2-regression-log"
            ),
            combine_regression_log(context, w1_log, w2_log),
        )

        # Target7 seed oracle leaf.
        seed_id = "W3-VER-SEED-ORACLE"
        seed_export = load_capture(
            "target7-database-export.json",
            "target7 seed export",
        )
        seed_digest = load_capture(
            "target7-digest-recomputation.json",
            "target7 digest recomputation",
        )
        seed_extras = load_capture(
            "target7-no-extras-query.json",
            "target7 no-extras query",
        )
        seed_export, seed_digest, seed_extras = validate_seed_captures(
            seed_export,
            seed_digest,
            seed_extras,
            fixed_seed_oracle,
        )
        seed_log = (
            exact_context_line(context, seed_id)
            + "\n"
            + f"W3_SEED_FIXTURE_SHA256={sha256_file(SEED_ORACLE)}\n"
            + "W3_TARGET7_SEED_ORACLE=PASS templates=3 scopes=3 versions=3\n"
        )
        write_new(
            stage_path(stage_root, roles, seed_id, "seed-oracle-log"),
            seed_log.encode(),
        )
        copy_new(
            SEED_ORACLE,
            stage_path(
                stage_root, roles, seed_id, "immutable-seed-fixture"
            ),
        )
        for role, name in (
            ("target7-database-export", "target7-database-export.json"),
            ("digest-recomputation", "target7-digest-recomputation.json"),
            ("no-extras-query", "target7-no-extras-query.json"),
        ):
            write_new(
                stage_path(stage_root, roles, seed_id, role),
                read_capture(
                    name,
                    f"target7 capture {name}",
                ),
            )

        # Ordered migration paths leaf.
        migration_id = "W3-VER-MIGRATION-PATHS"
        phases = [
            "v6-before",
            "target7",
            "latest-after",
            "repeat-after",
            "empty-latest",
        ]
        histories = {
            phase: validate_flyway_capture(
                load_capture(
                    f"flyway-{phase}.json",
                    f"Flyway {phase}",
                ),
                phase,
            )
            for phase in phases
        }
        maxima = {
            phase: flyway_max_version(history)
            for phase, history in histories.items()
        }
        if (
            maxima["v6-before"] != 6
            or maxima["target7"] != 7
            or maxima["latest-after"] < 7
            or maxima["repeat-after"] != maxima["latest-after"]
            or maxima["empty-latest"] < 7
        ):
            fail(f"Flyway phase boundary mismatch: {maxima}")
        latest_rows = histories["latest-after"]["rows"]
        repeat_rows = histories["repeat-after"]["rows"]
        if latest_rows != repeat_rows:
            fail("repeat migrate changed Flyway history")
        marker_order = [
            "W3_TARGET7_SNAPSHOT_BEFORE_CHILD_LATEST=PASS",
            "W3_TARGET7_TO_LATEST=PASS",
            "W3_V6_TARGET7_LATEST=PASS",
            "W3_EMPTY_TO_LATEST=PASS",
        ]
        database_identity = re.escape(context["databaseIdentity"])
        migration_marker_specs = {
            "W3_TARGET7_SNAPSHOT_BEFORE_CHILD_LATEST=PASS": (
                1,
                (
                    r"\[shenzhouhr-mysql\] "
                    r"W3_TARGET7_SNAPSHOT_BEFORE_CHILD_LATEST=PASS "
                    r"database=shenzhou_hr_test "
                    r"registry_sha256=(?P<digest>[0-9a-f]{64}) "
                    r"child_latest_invoked=false db_identity="
                    + database_identity
                ),
            ),
            "W3_TARGET7_TO_LATEST=PASS": (
                1,
                (
                    r"\[shenzhouhr-mysql\] W3_TARGET7_TO_LATEST=PASS "
                    r"database=shenzhou_hr_test "
                    r"registry_sha256=(?P<digest>[0-9a-f]{64}) "
                    r"post_v7=allowed db_identity="
                    + database_identity
                ),
            ),
            "W3_V6_TARGET7_LATEST=PASS": (
                1,
                (
                    r"\[shenzhouhr-mysql\] W3_V6_TARGET7_LATEST=PASS "
                    r"database=shenzhou_hr_test repeat_noop=PASS "
                    r"registry_sha256=(?P<digest>[0-9a-f]{64}) "
                    r"db_identity="
                    + database_identity
                ),
            ),
            "W3_EMPTY_TO_LATEST=PASS": (
                1,
                (
                    r"\[shenzhouhr-mysql\] W3_EMPTY_TO_LATEST=PASS "
                    r"database=shenzhou_hr_test registry_tables=22 "
                    r"post_v7=allowed db_identity="
                    + database_identity
                ),
            ),
        }
        migration_matches = require_transcript_markers(
            mysql_all_output,
            migration_marker_specs,
            "W3 migration transcript",
        )
        marker_positions = [
            migration_matches[value][0][1] for value in marker_order
        ]
        if marker_positions != sorted(marker_positions):
            fail("migration semantic markers are missing, duplicated or reordered")
        migration_log = (
            exact_context_line(context, migration_id)
            + "\n"
            + mysql_all_output
        )
        write_new(
            stage_path(
                stage_root, roles, migration_id, "migration-paths-log"
            ),
            migration_log.encode("utf-8"),
        )
        identity_bytes = read_identity_evidence(
            "verify-mysql8410-server-identity.tsv",
            "isolated MySQL server identity",
        )
        identity = parse_tsv_bytes(
            identity_bytes, "verify-mysql8410-server-identity.tsv"
        )
        if set(identity) != {
            "version",
            "version_core",
            "port",
            "socket",
            "datadir",
            "hostname",
            "bind_address",
            "server_uuid",
            "db_identity",
            "pid",
            "mysqld_sha256",
        }:
            fail("isolated MySQL identity TSV key closure differs")
        expected_server_uuid = context["databaseIdentity"].split(":")[1]
        if (
            identity["version"] != "8.4.10"
            or identity["version_core"] != "8.4.10"
            or identity["port"] != "13306"
            or identity["socket"]
            != str(MYSQL_ROOT / "run/mysql8410.sock")
            or identity["datadir"] != f"{MYSQL_ROOT / 'data'}/"
            or not identity["hostname"]
            or identity["bind_address"] != "127.0.0.1"
            or identity["server_uuid"] != expected_server_uuid
            or identity["db_identity"] != context["databaseIdentity"]
            or not re.fullmatch(r"[1-9][0-9]*", identity["pid"])
            or identity["mysqld_sha256"]
            != sha256_file(MYSQL_ROOT / "install/bin/mysqld")
        ):
            fail("isolated MySQL 8.4.10 identity semantics differ")
        write_json(
            stage_path(
                stage_root, roles, migration_id, "database-identity"
            ),
            base_document(
                context,
                migration_id,
                "migration-database-identity",
                serverIdentity=identity,
                verdict="PASS",
            ),
        )
        write_json(
            stage_path(
                stage_root, roles, migration_id, "flyway-info-sequence"
            ),
            base_document(
                context,
                migration_id,
                "flyway-info-sequence",
                phases=[
                    {
                        "phase": phase,
                        "maximumVersion": maxima[phase],
                        "historySha256": sha256_bytes(
                            read_capture(
                                f"flyway-{phase}.json",
                                f"Flyway {phase}",
                            )
                        ),
                        "history": histories[phase]["rows"],
                    }
                    for phase in phases
                ],
                postV7Allowed=True,
                verdict="PASS",
            ),
        )
        write_json(
            stage_path(
                stage_root,
                roles,
                migration_id,
                "ordered-child-invocations",
            ),
            base_document(
                context,
                migration_id,
                "ordered-migration-invocations",
                invocations=[
                    {
                        "ordinal": index + 1,
                        "marker": marker,
                        "transcriptByteOffset": position,
                    }
                    for index, (marker, position) in enumerate(
                        zip(marker_order, marker_positions, strict=True)
                    )
                ],
                target7SnapshotBeforeLatest=True,
                verdict="PASS",
            ),
        )
        write_json(
            stage_path(
                stage_root, roles, migration_id, "retained-snapshots"
            ),
            base_document(
                context,
                migration_id,
                "migration-retained-snapshots",
                w2BeforeSha256=sha256_bytes(
                    read_capture(
                        "w2-before-snapshot.json",
                        "W2 before snapshot",
                    )
                ),
                w2AfterSha256=sha256_bytes(
                    read_capture(
                        "w2-after-snapshot.json",
                        "W2 after snapshot",
                    )
                ),
                target7SeedExportSha256=sha256_bytes(
                    read_capture(
                        "target7-database-export.json",
                        "target7 seed export",
                    )
                ),
                w2SubsetDiff=difference,
                verdict="PASS",
            ),
        )
        fingerprint_markers = [
            "W3_TARGET7_SNAPSHOT_BEFORE_CHILD_LATEST=PASS",
            "W3_TARGET7_TO_LATEST=PASS",
            "W3_V6_TARGET7_LATEST=PASS",
        ]
        fingerprints: list[tuple[str, str]] = []
        for marker in fingerprint_markers:
            line = migration_matches[marker][0][0]
            match = re.fullmatch(migration_marker_specs[marker][1], line)
            if match is None:
                fail(f"migration fingerprint marker syntax differs: {marker}")
            fingerprints.append(
                (marker.removesuffix("=PASS"), match.group("digest"))
            )
        if len({value[1] for value in fingerprints}) != 1:
            fail("target7/latest/repeat registry fingerprints differ")
        write_json(
            stage_path(
                stage_root,
                roles,
                migration_id,
                "repeat-noop-fingerprints",
            ),
            base_document(
                context,
                migration_id,
                "migration-repeat-noop-fingerprints",
                registryFingerprints=[
                    {"marker": name, "sha256": digest}
                    for name, digest in fingerprints
                ],
                flywayHistoryBeforeRepeatSha256=sha256_bytes(
                    canonical_json_bytes(latest_rows)
                ),
                flywayHistoryAfterRepeatSha256=sha256_bytes(
                    canonical_json_bytes(repeat_rows)
                ),
                repeatNoop=True,
                verdict="PASS",
            ),
        )

        # MySQL 8.4.10 current-run identity/schema/lock/DML/grant leaf.
        mysql_id = "W3-VER-MYSQL8410"
        require_transcript_markers(
            mysql_verify_output,
            {
                "W3_MYSQL8410=PASS": (
                    1,
                    (
                        r"\[shenzhouhr-mysql8410\] W3_MYSQL8410=PASS "
                        r"version=8\.4\.10 port=13306 db_identity="
                        + database_identity
                    ),
                )
            },
            "isolated MySQL verify transcript",
        )
        mysql_semantic_markers = {
            "W3_REGISTRY_DB_COMPARE=PASS": (
                4,
                (
                    r"W3_REGISTRY_DB_COMPARE=PASS "
                    r"tables=22 flyway_metadata=excluded"
                ),
            ),
            "W3_CONSTRAINT_INDEX_NAME_COMPARE=PASS": (
                2,
                (
                    r"W3_CONSTRAINT_INDEX_NAME_COMPARE=PASS "
                    r"foreign_keys=71 checks=25 explicit_indexes=84 "
                    r"implicit_fk_indexes=[0-9]+ db_identity="
                    + database_identity
                ),
            ),
            "W3_CONSTRAINT_CAPABILITY=PASS": (
                2,
                (
                    r"\[shenzhouhr-mysql\] "
                    r"W3_CONSTRAINT_CAPABILITY=PASS "
                    r"database=(?:shenzhou_hr_dev|shenzhou_hr_test) "
                    r"foreign_keys=71 checks=25 indexes=[0-9]+ "
                    r"checks_enforced=true capabilities=6 "
                    r"admin_grants=12 auditor_read=1 auditor_manage=0 "
                    r"db_identity="
                    + database_identity
                ),
            ),
            "W3_APP_PRIVILEGES=PASS": (
                2,
                (
                    r"\[shenzhouhr-mysql\] W3_APP_PRIVILEGES=PASS "
                    r"database=(?:shenzhou_hr_dev|shenzhou_hr_test) "
                    r"transactional_crud=PASS ddl=DENIED grant=DENIED "
                    r"cross_schema=DENIED system_schema=DENIED "
                    r"db_identity="
                    + database_identity
                ),
            ),
            "W3_QUERY_PLAN=PASS": (
                1,
                (
                    r"\[shenzhouhr-mysql\] W3_QUERY_PLAN=PASS "
                    r"database=shenzhou_hr_test "
                    r"query=idempotency_current "
                    r"index=ix_attendance_setup_idempotency_state "
                    r"fixture_rows=512 rolled_back=true db_identity="
                    + database_identity
                ),
            ),
            "W3_CURRENT_LOCK=PASS": (
                1,
                (
                    r"\[shenzhouhr-mysql\] W3_CURRENT_LOCK=PASS "
                    r"database=shenzhou_hr_test "
                    r"holder=SELECT_FOR_UPDATE challenger=LOCK_TIMEOUT "
                    r"rollback=true db_identity="
                    + database_identity
                ),
            ),
            "WAVE3_CONTRACT_AND_APP_ACCESS=PASS": (
                1,
                (
                    r"\[shenzhouhr-mysql\] "
                    r"WAVE3_CONTRACT_AND_APP_ACCESS=PASS"
                ),
            ),
        }
        semantic_matches = require_transcript_markers(
            mysql_all_output,
            mysql_semantic_markers,
            "W3 MySQL schema/lock/DML/grant transcript",
        )
        for marker in (
            "W3_CONSTRAINT_CAPABILITY=PASS",
            "W3_APP_PRIVILEGES=PASS",
        ):
            databases = {
                re.search(r" database=([^ ]+) ", line).group(1)
                for line, _ in semantic_matches[marker]
            }
            if databases != {"shenzhou_hr_dev", "shenzhou_hr_test"}:
                fail(f"{marker} database coverage differs")
        mysql_primary = (
            exact_context_line(context, mysql_id)
            + "\n"
            + mysql_verify_output
            + filtered_lines(
                mysql_all_output,
                (
                    mysql_semantic_markers[marker][1]
                    for marker in (
                        "W3_CONSTRAINT_INDEX_NAME_COMPARE=PASS",
                        "W3_CONSTRAINT_CAPABILITY=PASS",
                        "W3_APP_PRIVILEGES=PASS",
                        "W3_QUERY_PLAN=PASS",
                        "W3_CURRENT_LOCK=PASS",
                        "WAVE3_CONTRACT_AND_APP_ACCESS=PASS",
                    )
                ),
            )
        )
        write_new(
            stage_path(
                stage_root,
                roles,
                mysql_id,
                "mysql8410-verification-log",
            ),
            mysql_primary.encode(),
        )
        if sha256_file(MYSQL_TARBALL) != MYSQL_TARBALL_SHA256:
            fail("official MySQL 8.4.10 tarball SHA256 differs")
        write_new(
            stage_path(
                stage_root, roles, mysql_id, "source-tarball-sha256"
            ),
            (
                f"{MYSQL_TARBALL_SHA256}  mysql-8.4.10.tar.gz\n"
            ).encode(),
        )
        install_files = [
            MYSQL_ROOT / "install/bin/mysql",
            MYSQL_ROOT / "install/bin/mysqld",
            MYSQL_ROOT / "state/initialized-8.4.10",
            MYSQL_ROOT / "state/wave3-db-identity",
        ]
        install_records = []
        for path in install_files:
            if not path.is_file() or path.is_symlink():
                fail(f"reusable MySQL installation file is missing: {path}")
            install_records.append(
                {
                    "path": path.relative_to(MYSQL_ROOT).as_posix(),
                    "sizeBytes": path.stat().st_size,
                    "sha256": sha256_file(path),
                }
            )
        build_log = (
            "W3_MYSQL8410_REUSABLE_BUILD_INSTALL_INIT=PASS "
            "currentRunRevalidated=true historicalPassImported=false\n"
            + json.dumps(
                {
                    "sourceTarballSha256": MYSQL_TARBALL_SHA256,
                    "installation": install_records,
                    "runtimeIdentitySha256": sha256_bytes(identity_bytes),
                },
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        )
        write_new(
            stage_path(
                stage_root, roles, mysql_id, "build-install-init-log"
            ),
            build_log.encode(),
        )
        write_json(
            stage_path(
                stage_root, roles, mysql_id, "isolated-instance-identity"
            ),
            base_document(
                context,
                mysql_id,
                "mysql8410-isolated-instance-identity",
                identity=identity,
                exactVersion=True,
                exactPort=True,
                exactSocketAndDatadir=True,
                verdict="PASS",
            ),
        )
        write_new(
            stage_path(
                stage_root, roles, mysql_id, "schema-lock-dml-grant-log"
            ),
            filtered_lines(
                mysql_all_output,
                (
                    mysql_semantic_markers[marker][1]
                    for marker in (
                        "W3_REGISTRY_DB_COMPARE=PASS",
                        "W3_CONSTRAINT_INDEX_NAME_COMPARE=PASS",
                        "W3_CONSTRAINT_CAPABILITY=PASS",
                        "W3_APP_PRIVILEGES=PASS",
                        "W3_QUERY_PLAN=PASS",
                        "W3_CURRENT_LOCK=PASS",
                    )
                ),
            ).encode(),
        )
        before_existing = read_identity_evidence(
            "existing-mysql8034-verify-before.tsv",
            "existing MySQL 8.0.34 before identity",
        )
        after_existing = read_identity_evidence(
            "existing-mysql8034-verify-after.tsv",
            "existing MySQL 8.0.34 after identity",
        )
        if before_existing != after_existing:
            fail("existing MySQL 8.0.34 identity changed during verify")
        before_existing_values = parse_tsv_bytes(
            before_existing, "existing-mysql8034-verify-before.tsv"
        )
        after_existing_values = parse_tsv_bytes(
            after_existing, "existing-mysql8034-verify-after.tsv"
        )
        if (
            set(before_existing_values)
            != {
                "basedir",
                "basedir_link",
                "binary_sha256",
                "datadir",
                "pid",
                "ppid",
                "port",
                "port_listener_pid",
                "process_command",
                "service_label",
                "service_plist",
                "service_plist_sha256",
                "service_state",
                "socket",
                "socket_stat",
                "version",
            }
            or before_existing_values != after_existing_values
            or before_existing_values["basedir"] != "/usr/local/mysql"
            or not before_existing_values["basedir_link"].startswith(
                "mysql-8.0.34-"
            )
            or not SHA256_PATTERN.fullmatch(
                before_existing_values["binary_sha256"]
            )
            or before_existing_values["datadir"]
            != "/usr/local/mysql/data"
            or not re.fullmatch(
                r"[1-9][0-9]*", before_existing_values["pid"]
            )
            or before_existing_values["ppid"] != "1"
            or before_existing_values["port"] != "3306"
            or before_existing_values["port_listener_pid"]
            != before_existing_values["pid"]
            or "--basedir=/usr/local/mysql"
            not in before_existing_values["process_command"]
            or "--datadir=/usr/local/mysql/data"
            not in before_existing_values["process_command"]
            or before_existing_values["service_label"]
            != "com.oracle.oss.mysql.mysqld"
            or before_existing_values["service_plist"]
            != (
                "/Library/LaunchDaemons/"
                "com.oracle.oss.mysql.mysqld.plist"
            )
            or not SHA256_PATTERN.fullmatch(
                before_existing_values["service_plist_sha256"]
            )
            or before_existing_values["service_state"] != "running"
            or before_existing_values["socket"] != "/tmp/mysql.sock"
            or not before_existing_values["socket_stat"].startswith(
                "Socket|"
            )
            or before_existing_values["version"] != "8.0.34"
        ):
            fail("existing MySQL 8.0.34 identity TSV semantics differ")
        write_json(
            stage_path(
                stage_root,
                roles,
                mysql_id,
                "existing-instance-before-after",
            ),
            base_document(
                context,
                mysql_id,
                "mysql8034-existing-instance-before-after",
                before=before_existing_values,
                after=after_existing_values,
                beforeSha256=sha256_bytes(before_existing),
                afterSha256=sha256_bytes(after_existing),
                byteIdentical=True,
                verdict="PASS",
            ),
        )

        verify_absolute_directory_identity(
            mysql_identity_dir,
            identity_directory_identity,
            "isolated MySQL identity evidence directory final check",
        )
        verify_absolute_directory_identity(
            mysql_capture_dir,
            capture_identity,
            "MySQL semantic capture directory final check",
        )
        regular_directory_entries(
            mysql_identity_fd,
            set(identity_entry_identities),
            "isolated MySQL identity evidence directory final check",
            expected_identities=identity_entry_identity_tuple,
        )
        regular_directory_entries(
            mysql_capture_fd,
            set(capture_entry_identities),
            "MySQL semantic capture directory final check",
            expected_identities=capture_entry_identity_tuple,
        )
        current_snapshot = verifier.compute_source_snapshot()
        if current_snapshot.tree_hash != context["sourceTreeHash"]:
            fail("source changed during semantic gate execution")
        validate_primary_artifacts(
            verifier, context, stage_root, roles
        )
        published = publish_raw_directories(
            run_root,
            stage_root,
            roles,
            expected_run_root_identity=run_root_identity,
            expected_stage_root_identity=stage_root_identity,
        )
        final_snapshot = verifier.compute_source_snapshot()
        if final_snapshot.tree_hash != context["sourceTreeHash"]:
            fail("source changed during semantic raw publication")
        return published
    except BaseException as original:
        rollback_errors: list[str] = []
        for item in reversed(published):
            try:
                remove_published_raw_safely(run_root, item)
            except BaseException as rollback_error:
                rollback_errors.append(str(rollback_error))
        if rollback_errors:
            fail(
                f"semantic production failed ({original}); rollback "
                f"preserved changed replacement(s): {rollback_errors}"
            )
        raise
    finally:
        if mysql_capture_fd is not None:
            os.close(mysql_capture_fd)
        if mysql_identity_fd is not None:
            os.close(mysql_identity_fd)
        remove_private_stage_directory(
            run_root, stage_root, stage_root_identity
        )


def self_test() -> None:
    validate_fixed_semantic_oracles()
    controllers = controller_operation_export()
    document = load_unique_yaml(OPENAPI.read_text(encoding="utf-8"))
    operations = openapi_operation_export(document)
    comparison = compare_operation_closure(controllers, operations, document)
    if comparison["verdict"] != "PASS":
        fail("self-test production operation closure failed")
    review_comparison = compare_review_owned_api_oracle(
        document, controllers
    )
    if review_comparison["verdict"] != "PASS":
        fail("self-test review-owned API semantic oracle failed")
    record_schema_report(document)
    instance_validation_report(document, operations)
    mutated = copy.deepcopy(operations)
    mutated.pop()
    if (
        compare_operation_closure(controllers, mutated, document)["verdict"]
        != "FAIL"
    ):
        fail("self-test missing-route mutant survived")
    mutated = copy.deepcopy(operations)
    target = next(
        value for value in mutated if value["requestSchema"] is not None
    )
    target["requestSchema"] = {"$ref": "#/components/schemas/ApiError"}
    if (
        compare_operation_closure(controllers, mutated, document)["verdict"]
        != "FAIL"
    ):
        fail("self-test request-schema mutant survived")
    mutated = copy.deepcopy(operations)
    next(
        value for value in mutated if value["requestSchema"] is not None
    )["requestRequired"] = False
    if (
        compare_operation_closure(controllers, mutated, document)["verdict"]
        != "FAIL"
    ):
        fail("self-test optional-request-body mutant survived")
    mutated = copy.deepcopy(operations)
    next(
        parameter
        for operation in mutated
        for parameter in operation["parameters"]
        if parameter["in"] == "query"
    )["schema"] = {"type": "boolean"}
    if (
        compare_operation_closure(controllers, mutated, document)["verdict"]
        != "FAIL"
    ):
        fail("self-test parameter-schema mutant survived")
    mutated = copy.deepcopy(operations)
    next(
        parameter
        for operation in mutated
        for parameter in operation["parameters"]
        if parameter["in"] == "header"
        and parameter["name"] == "Idempotency-Key"
    )["required"] = False
    if (
        compare_operation_closure(controllers, mutated, document)["verdict"]
        != "FAIL"
    ):
        fail("self-test optional-header mutant survived")
    open_response_document = copy.deepcopy(document)
    open_response_document["components"]["schemas"][
        "AttendanceLocationView"
    ]["additionalProperties"] = True
    if (
        compare_operation_closure(
            controllers,
            openapi_operation_export(open_response_document),
            open_response_document,
        )["verdict"]
        != "FAIL"
    ):
        fail("self-test open-response mutant survived")
    lifecycle_controllers = copy.deepcopy(controllers)
    lifecycle_openapi = copy.deepcopy(operations)
    for collection in (lifecycle_controllers, lifecycle_openapi):
        for operation in collection:
            if (
                "/policy-lifecycle/" not in operation["path"]
                or "{templateId}" not in operation["path"]
            ):
                continue
            operation["parameters"] = [
                value
                for value in operation["parameters"]
                if not (
                    value["in"] == "query"
                    and value["name"] == "legalEntityId"
                )
                and not (
                    value["in"] == "header"
                    and value["name"] == "Idempotency-Key"
                )
            ]
    if (
        compare_operation_closure(
            lifecycle_controllers, lifecycle_openapi, document
        )["verdict"]
        != "FAIL"
    ):
        fail("self-test lifecycle-binding mutant survived")
    schema = document["components"]["schemas"]["AttendanceLocationRequest"]
    legal = generate_schema_instance(schema, document)
    if validate_schema_instance(legal, schema, document):
        fail("self-test generated legal instance was rejected")
    illegal = dict(legal)
    illegal["unexpected"] = True
    if not validate_schema_instance(illegal, schema, document):
        fail("self-test unknown-field mutant survived")
    w2 = load_json(W2_REGISTRY, "W2 registry")
    w3 = load_json(W3_REGISTRY, "W3 registry")
    names = [
        value["table"] for value in [*w2["tables"], *w3["tables"]]
    ]
    if len(names) != 28 or len(set(names)) != 28:
        fail("self-test retained registry closure failed")


def plan_document() -> dict[str, Any]:
    contract = load_json(CONTRACT_PATH, "evidence contract")
    return {
        "schemaVersion": 1,
        "nodeType": "wave3-semantic-gate-producer-plan",
        "leafCount": 7,
        "leafIds": list(SEMANTIC_IDS),
        "requires": {
            "freshFrozenRunContext": True,
            "preparedLeafPlan": True,
            "currentRunW1Regression": True,
            "currentRunW2Regression": True,
            "privateRuntimeEnvironment": True,
            "explicitTestTableRebuildConfirmation": True,
        },
        "databaseActions": {
            "harness": "deploy/mysql/wave3-local-mysql.sh all",
            "database": "shenzhou_hr_test",
            "startsOrStopsMysql": False,
            "connectsToPort3306": False,
            "captures": [
                "V6 W2 non-empty retained fixture/schema/rows",
                "target7 seed and Flyway state before latest",
                "latest/repeat/empty-latest state",
                "current MySQL 8.4.10 and unchanged MySQL 8.0.34 identity",
            ],
        },
        "contractSha256": sha256_file(CONTRACT_PATH),
        "fixedSemanticOracleSha256": {
            "w2PublicContract": PUBLIC_EXPECTED_SHA256,
            "w2PublicSupersession": PUBLIC_SUPERSESSION_SHA256,
            "w2RetainedRegistry": W2_REGISTRY_SHA256,
            "w2RetainedRows": W2_FIXED_ROWS_SHA256,
            "target7Seed": SEED_ORACLE_SHA256,
            "apiSemantics": API_SEMANTIC_ORACLE_SHA256,
        },
        "requiredRoles": {
            evidence_id: contract["leafContracts"][evidence_id][
                "requiredArtifactRoles"
            ]
            for evidence_id in SEMANTIC_IDS
        },
        "publication": (
            "stage all roles with exact regular-file entry closure; validate "
            "all seven; publish from fixed dirfd ancestors with exclusive "
            "atomic rename; retain device/inode identities; rollback only the "
            "published object and preserve any replacement; never register "
            "leaves"
        ),
    }


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser()
    subcommands = value.add_subparsers(dest="command", required=True)
    subcommands.add_parser("plan")
    subcommands.add_parser("self-test")
    execute = subcommands.add_parser("execute")
    execute.add_argument("--run-context", type=Path, required=True)
    execute.add_argument("--runtime-env", type=Path, required=True)
    execute.add_argument(
        "--confirm-test-table-rebuild",
        required=True,
    )
    return value


def main(argv: Sequence[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    try:
        if arguments.command == "plan":
            print(json.dumps(plan_document(), ensure_ascii=False, indent=2))
            return 0
        if arguments.command == "self-test":
            self_test()
            print(
                "W3_SEMANTIC_GATE_PRODUCER_SELF_TEST=PASS "
                "tests=20 leafCount=7"
            )
            return 0
        published = produce(
            arguments.run_context,
            arguments.runtime_env,
            arguments.confirm_test_table_rebuild,
        )
        print(
            "W3_SEMANTIC_GATE_PRODUCER=PASS "
            f"leaves={len(published)} runId={arguments.run_context.parent.name}"
        )
        return 0
    except (
        ProducerError,
        EvidenceError,
        OSError,
        ValueError,
        TypeError,
        KeyError,
        subprocess.SubprocessError,
    ) as error:
        sys.stderr.write(f"W3_SEMANTIC_GATE_PRODUCER=FAIL {error}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
