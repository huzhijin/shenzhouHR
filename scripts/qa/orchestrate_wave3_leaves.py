#!/usr/bin/env python3
"""Plan, preflight and register the exact W3 pre-review leaf set.

This helper deliberately does not implement semantic gates.  Real Maven,
frontend, migration and browser producers must create the raw artifacts.  The
helper only creates a run-bound role plan, validates those current-run outputs
and delegates registration to the existing strict evidence verifier.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import stat
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Sequence

try:
    from .verify_wave3_evidence import (
        BUILD_GATE_IDS,
        EvidenceError,
        FORBIDDEN_STRUCTURED_TOKEN,
        RUN_ID_PATTERN,
        Wave3Evidence,
        sha256_file,
    )
except ImportError:
    from verify_wave3_evidence import (  # type: ignore[no-redef]
        BUILD_GATE_IDS,
        EvidenceError,
        FORBIDDEN_STRUCTURED_TOKEN,
        RUN_ID_PATTERN,
        Wave3Evidence,
        sha256_file,
    )


EXPECTED_REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PLAN_RELATIVE_PATH = "orchestration/leaf-plan.json"
MYSQL_CLIENT = Path(
    "/Users/huzhijin/.local/share/shenzhouhr/"
    "mysql-8.4.10-isolated/install/bin/mysql"
)
DATABASE_IDENTITY_PATTERN = re.compile(
    r"^mysql8410:"
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}:shenzhou_hr_test$"
)

FIXED_PRE_REVIEW_IDS = (
    "W3-VER-W2-RETAINED",
    "W3-VER-W2-PUBLIC-ORACLE",
    "W3-VER-CANONICALIZER",
    "W3-VER-SEED-ORACLE",
    "W3-VER-MIGRATION-PATHS",
    "W3-VER-MYSQL8410",
    "W3-VER-OPENAPI-CLOSURE",
    "W3-VER-BACKEND-FULL",
    "W3-VER-W1-REGRESSION",
    "W3-VER-W2-REGRESSION",
    "W3-VER-W3-REGRESSION",
    "W3-VER-FRONTEND-TYPECHECK",
    "W3-VER-FRONTEND-LINT",
    "W3-VER-FRONTEND-TESTS",
    "W3-VER-PROD-BUILD",
    "W3-VER-DEMO-BUILD",
    "W3-VER-DEMO-ISOLATION",
    "W3-VER-W2-CURRENT-SMOKE",
    "W3-VER-NORMAL-BROWSER",
    "W3-VER-PAYROLL-ZERO",
)

POLICY = {
    "fixedOrder": True,
    "preflightAllArtifactsBeforeRegistration": True,
    "historicalArtifactImport": "FORBIDDEN",
    "semanticPassSynthesis": "FORBIDDEN",
    "stopsBeforeSourceEnd": True,
    "stopsBeforeIndependentReview": True,
    "stopsBeforeManifestIntegrityFinal": True,
}

PRIVATE_INPUTS = {
    "runtimeEnv": {
        "requiredFor": [
            "W3-VER-SEED-ORACLE",
            "W3-VER-MIGRATION-PATHS",
            "W3-VER-MYSQL8410",
            "W3-VER-DEMO-ISOLATION",
            "W3-VER-W2-CURRENT-SMOKE",
            "W3-VER-NORMAL-BROWSER",
            "W3-VER-PAYROLL-ZERO",
        ],
        "constraint": (
            "absolute repository-external owner-controlled regular non-symlink "
            "file with mode 0600 and exact MySQL 8.4.10 identity"
        ),
    },
    "loginEnv": {
        "requiredFor": [
            "W3-VER-W2-CURRENT-SMOKE",
            "W3-VER-NORMAL-BROWSER",
            "W3-VER-PAYROLL-ZERO",
        ],
        "constraint": (
            "absolute repository-external owner-controlled regular non-symlink "
            "file with mode 0600; values are never printed or stored in evidence"
        ),
    },
}

PRODUCER_CATALOG: dict[str, dict[str, Any]] = {
    "W3-VER-W2-RETAINED": {
        "producerId": "w2-retained-current-run",
        "producerClass": "DEDICATED_SEMANTIC_GATE_REQUIRED",
        "inputs": [
            "fixed W2 registry",
            "independent V4 schema oracle",
            "current-run before/after database snapshots",
            "W1/W2 regression output",
        ],
    },
    "W3-VER-W2-PUBLIC-ORACLE": {
        "producerId": "w2-public-contract-v2",
        "producerClass": "EXISTING_ORACLE_PLUS_CURRENT_RUN_ADAPTER",
        "inputs": [
            "extract-w2-public-contract-v2.sh",
            "verify_w2_public_contract_v2.py governance/exact-diff/self-test",
            "immutable checkpoint blobs and frozen v1 supersession decision",
        ],
    },
    "W3-VER-CANONICALIZER": {
        "producerId": "retained-canonicalizer-v1",
        "producerClass": "EXISTING_ORACLE_PLUS_CURRENT_RUN_ADAPTER",
        "inputs": [
            "retained_canonicalizer.py --self-test",
            "fixed retained registry",
            "review-owned canonical golden vectors",
        ],
    },
    "W3-VER-SEED-ORACLE": {
        "producerId": "target7-seed-current-run",
        "producerClass": "CURRENT_DATABASE_GATE_REQUIRED",
        "inputs": [
            "wave3-local-mysql.sh current-run target7 output",
            "immutable seed fixture and independent digest recomputation",
        ],
    },
    "W3-VER-MIGRATION-PATHS": {
        "producerId": "migration-paths-current-run",
        "producerClass": "CURRENT_DATABASE_GATE_REQUIRED",
        "inputs": [
            "wave3-local-mysql.sh current-run all output",
            "ordered Flyway invocations and repeat no-op fingerprints",
        ],
    },
    "W3-VER-MYSQL8410": {
        "producerId": "mysql8410-current-run",
        "producerClass": "EXISTING_MYSQL_HARNESS_PLUS_CURRENT_RUN_ADAPTER",
        "inputs": [
            "mysql8410-isolated.sh build/init/start/verify evidence",
            "wave3-local-mysql.sh schema/lock/DML/grant output",
            "existing MySQL 8.0.34 before/after snapshots",
        ],
    },
    "W3-VER-OPENAPI-CLOSURE": {
        "producerId": "controller-openapi-closure-current-run",
        "producerClass": "DEDICATED_SEMANTIC_GATE_REQUIRED",
        "inputs": [
            "Wave3OpenApiContractTest output",
            "controller and OpenAPI operation exports",
            "resolved-ref legal/illegal instance report",
        ],
    },
    "W3-VER-BACKEND-FULL": {
        "producerId": "wave3-frozen-source-build-gates",
        "producerClass": "EXISTING_BUILD_GATE_HARNESS",
        "inputs": ["full Maven test output and machine-readable test summary"],
    },
    "W3-VER-W1-REGRESSION": {
        "producerId": "wave3-frozen-source-build-gates",
        "producerClass": "EXISTING_BUILD_GATE_HARNESS",
        "inputs": ["complete W1 Maven suite output"],
    },
    "W3-VER-W2-REGRESSION": {
        "producerId": "wave3-frozen-source-build-gates",
        "producerClass": "EXISTING_BUILD_GATE_HARNESS",
        "inputs": ["complete W2 Maven suite output"],
    },
    "W3-VER-W3-REGRESSION": {
        "producerId": "wave3-frozen-source-build-gates",
        "producerClass": "EXISTING_BUILD_GATE_HARNESS",
        "inputs": ["complete W3 semantic Maven suite output"],
    },
    "W3-VER-FRONTEND-TYPECHECK": {
        "producerId": "wave3-frozen-source-build-gates",
        "producerClass": "EXISTING_BUILD_GATE_HARNESS",
        "inputs": ["npm run typecheck output"],
    },
    "W3-VER-FRONTEND-LINT": {
        "producerId": "wave3-frozen-source-build-gates",
        "producerClass": "EXISTING_BUILD_GATE_HARNESS",
        "inputs": ["npm run lint output with zero warnings/errors"],
    },
    "W3-VER-FRONTEND-TESTS": {
        "producerId": "wave3-frozen-source-build-gates",
        "producerClass": "EXISTING_BUILD_GATE_HARNESS",
        "inputs": ["npm test output and machine-readable test summary"],
    },
    "W3-VER-PROD-BUILD": {
        "producerId": "wave3-frozen-source-build-gates",
        "producerClass": "EXISTING_BUILD_GATE_HARNESS",
        "inputs": ["npm run build output and fresh prod dist inventory"],
    },
    "W3-VER-DEMO-BUILD": {
        "producerId": "wave3-frozen-source-build-gates",
        "producerClass": "EXISTING_BUILD_GATE_HARNESS",
        "inputs": ["npm run build:demo output and separate demo dist inventory"],
    },
    "W3-VER-DEMO-ISOLATION": {
        "producerId": "demo-isolation-runtime-harness",
        "producerClass": "EXISTING_RUNTIME_HARNESS",
        "inputs": [
            "verify-wave3-demo-isolation.mjs execute output",
            "frozen dist/demo preview",
            "isolated MySQL connection counter",
        ],
    },
    "W3-VER-W2-CURRENT-SMOKE": {
        "producerId": "w2-current-smoke-runtime-harness",
        "producerClass": "EXISTING_RUNTIME_HARNESS",
        "inputs": ["verify-wave3-w2-current-smoke.mjs execute output"],
    },
    "W3-VER-NORMAL-BROWSER": {
        "producerId": "normal-browser-runtime-harness",
        "producerClass": "EXISTING_RUNTIME_HARNESS",
        "inputs": [
            "verify-wave3-normal-browser.mjs execute output",
            "repository-external run-bound bootstrap login environment",
        ],
    },
    "W3-VER-PAYROLL-ZERO": {
        "producerId": "payroll-zero-current-run",
        "producerClass": "DEDICATED_SCAN_AND_RUNTIME_GATE_REQUIRED",
        "inputs": [
            "normalized deny scan",
            "line-addressed allowlist",
            "normal-role runtime probes",
        ],
    },
}

KNOWN_FILENAMES: dict[str, dict[str, str]] = {
    "W3-VER-W2-CURRENT-SMOKE": {
        "w2-current-smoke-log": "w2-current-smoke.log",
    },
    "W3-VER-NORMAL-BROWSER": {
        "normal-browser-matrix": "route-matrix.json",
        "normal-runtime-source-hash": "normal-runtime-source-hash.json",
        "database-flyway-identity": "database-flyway-identity.json",
        "principal-role-capability-export": "principal-role-capabilities.json",
        "playwright-trace-inventory": "traces.json",
        "screenshot-inventory": "screenshots.json",
        "http-request-response-log": "http-request-response-log.json",
        "backend-correlation-log": "backend-correlation.json",
        "database-read-marker": "db-read-marker.json",
        "axe-keyboard-report": "axe-keyboard-report.json",
    },
    "W3-VER-DEMO-ISOLATION": {
        "demo-isolation-log": "demo-isolation.log",
        "demo-network-har": "demo-network.har",
        "demo-database-connection-delta": "demo-database-connection-delta.json",
    },
}


class OrchestrationError(RuntimeError):
    """Expected fail-closed orchestration error."""


def fail(message: str) -> None:
    raise OrchestrationError(message)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def require_repository_root() -> Path:
    cwd = Path.cwd().resolve(strict=True)
    script_root = Path(__file__).resolve(strict=True).parents[2]
    if cwd != EXPECTED_REPOSITORY_ROOT or script_root != EXPECTED_REPOSITORY_ROOT:
        fail("PROJECT_ROOT_SCOPE_ERROR")
    return cwd


def validate_database_identity(value: str) -> None:
    if not DATABASE_IDENTITY_PATTERN.fullmatch(value):
        fail(
            "database identity must be "
            "mysql8410:<lowercase-server-uuid>:shenzhou_hr_test"
        )


def assert_no_forbidden_plan_values(value: Any, label: str) -> None:
    if value is None:
        fail(f"{label} contains JSON null")
    if isinstance(value, str):
        if FORBIDDEN_STRUCTURED_TOKEN in value:
            fail(f"{label} contains forbidden {FORBIDDEN_STRUCTURED_TOKEN}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            assert_no_forbidden_plan_values(item, f"{label}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                fail(f"{label} contains a non-string object key")
            assert_no_forbidden_plan_values(item, f"{label}.{key}")
        return
    if isinstance(value, (bool, int, float)):
        return
    fail(f"{label} contains unsupported value {type(value).__name__}")


def require_exact_keys(value: Any, expected: Iterable[str], label: str) -> None:
    if not isinstance(value, dict):
        fail(f"{label} must be an object")
    actual_keys = set(value)
    expected_keys = set(expected)
    if actual_keys != expected_keys:
        fail(
            f"{label} key closure failed; "
            f"missing={sorted(expected_keys - actual_keys)}, "
            f"extra={sorted(actual_keys - expected_keys)}"
        )


def default_filename(
    evidence_id: str, role: str, primary_role: str
) -> str:
    known = KNOWN_FILENAMES.get(evidence_id, {}).get(role)
    if known:
        return known
    if role == primary_role or role.endswith("-log"):
        return f"{role}.log"
    if role.endswith("-sha256") or role.endswith("-hash"):
        return f"{role}.sha256"
    if role.endswith("-diff"):
        return f"{role}.diff"
    if role.endswith("-har"):
        return f"{role}.har"
    return f"{role}.json"


def artifact_path(
    evidence_id: str, slug: str, role: str, primary_role: str
) -> str:
    return (
        f"leaves/{slug}/raw/"
        f"{default_filename(evidence_id, role, primary_role)}"
    )


def build_leaf_plan(
    verifier: Wave3Evidence, context: dict[str, Any]
) -> dict[str, Any]:
    if tuple(verifier.pre_review_ids) != FIXED_PRE_REVIEW_IDS:
        fail("evidence contract pre-review order differs from the fixed 20")
    if set(PRODUCER_CATALOG) != set(FIXED_PRE_REVIEW_IDS):
        fail("producer catalog does not close over the fixed 20")
    leaves: list[dict[str, Any]] = []
    for ordinal, evidence_id in enumerate(FIXED_PRE_REVIEW_IDS, start=1):
        contract = verifier.leaf_contracts[evidence_id]
        artifacts = [
            {
                "role": role,
                "path": artifact_path(
                    evidence_id,
                    contract["slug"],
                    role,
                    contract["primaryArtifactRole"],
                ),
            }
            for role in contract["requiredArtifactRoles"]
        ]
        leaves.append(
            {
                "ordinal": ordinal,
                "evidenceId": evidence_id,
                "slug": contract["slug"],
                "databaseBound": contract["databaseBound"],
                "requiredMarkers": contract["requiredMarkers"],
                "primaryArtifactRole": contract["primaryArtifactRole"],
                "requiredArtifactRoles": contract["requiredArtifactRoles"],
                "artifacts": artifacts,
                "producer": PRODUCER_CATALOG[evidence_id],
            }
        )
    plan = {
        "schemaVersion": 1,
        "nodeType": "wave3-pre-review-leaf-plan",
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "contractSha256": context["contractSha256"],
        "createdAt": utc_now(),
        "policy": POLICY,
        "privateInputs": PRIVATE_INPUTS,
        "leaves": leaves,
    }
    assert_no_forbidden_plan_values(plan, "leaf plan")
    return plan


def validate_artifact_relative_path(
    relative_path: str, slug: str, label: str
) -> None:
    if not isinstance(relative_path, str) or not relative_path:
        fail(f"{label} path must be a non-empty string")
    pure = PurePosixPath(relative_path)
    if (
        pure.is_absolute()
        or "." in pure.parts
        or ".." in pure.parts
        or relative_path != pure.as_posix()
    ):
        fail(f"{label} path is not a safe run-relative POSIX path")
    expected_prefix = f"leaves/{slug}/raw/"
    if not relative_path.startswith(expected_prefix):
        fail(f"{label} path must stay below {expected_prefix}")


def validate_plan(
    plan: Any, verifier: Wave3Evidence, context: dict[str, Any]
) -> list[dict[str, Any]]:
    assert_no_forbidden_plan_values(plan, "leaf plan")
    require_exact_keys(
        plan,
        {
            "schemaVersion",
            "nodeType",
            "runId",
            "sourceTreeHash",
            "databaseIdentity",
            "contractSha256",
            "createdAt",
            "policy",
            "privateInputs",
            "leaves",
        },
        "leaf plan",
    )
    expected_scalars = {
        "schemaVersion": 1,
        "nodeType": "wave3-pre-review-leaf-plan",
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "contractSha256": context["contractSha256"],
        "policy": POLICY,
        "privateInputs": PRIVATE_INPUTS,
    }
    for key, expected in expected_scalars.items():
        if plan[key] != expected:
            fail(f"leaf plan {key} mismatch")
    try:
        datetime.fromisoformat(plan["createdAt"].replace("Z", "+00:00"))
    except (AttributeError, ValueError):
        fail("leaf plan createdAt is invalid")
    leaves = plan["leaves"]
    if not isinstance(leaves, list) or len(leaves) != 20:
        fail("leaf plan must contain exactly 20 leaves")
    if [leaf.get("evidenceId") for leaf in leaves] != list(
        FIXED_PRE_REVIEW_IDS
    ):
        fail("leaf plan evidence order/set differs from the fixed 20")
    seen_paths: set[str] = set()
    for ordinal, leaf in enumerate(leaves, start=1):
        evidence_id = FIXED_PRE_REVIEW_IDS[ordinal - 1]
        contract = verifier.leaf_contracts[evidence_id]
        require_exact_keys(
            leaf,
            {
                "ordinal",
                "evidenceId",
                "slug",
                "databaseBound",
                "requiredMarkers",
                "primaryArtifactRole",
                "requiredArtifactRoles",
                "artifacts",
                "producer",
            },
            f"leaf plan {evidence_id}",
        )
        expected = {
            "ordinal": ordinal,
            "evidenceId": evidence_id,
            "slug": contract["slug"],
            "databaseBound": contract["databaseBound"],
            "requiredMarkers": contract["requiredMarkers"],
            "primaryArtifactRole": contract["primaryArtifactRole"],
            "requiredArtifactRoles": contract["requiredArtifactRoles"],
            "producer": PRODUCER_CATALOG[evidence_id],
        }
        for key, value in expected.items():
            if leaf[key] != value:
                fail(f"leaf plan {evidence_id} {key} mismatch")
        artifacts = leaf["artifacts"]
        if not isinstance(artifacts, list):
            fail(f"leaf plan {evidence_id} artifacts must be an array")
        roles = [artifact.get("role") for artifact in artifacts]
        if roles != contract["requiredArtifactRoles"]:
            fail(f"leaf plan {evidence_id} artifact role order/set mismatch")
        for artifact in artifacts:
            require_exact_keys(
                artifact, {"role", "path"}, f"leaf plan {evidence_id} artifact"
            )
            path = artifact["path"]
            validate_artifact_relative_path(
                path, contract["slug"], f"leaf plan {evidence_id}"
            )
            if path in seen_paths:
                fail(f"leaf plan duplicates artifact path: {path}")
            seen_paths.add(path)
    return leaves


def plan_path(run_root: Path) -> Path:
    return run_root / PLAN_RELATIVE_PATH


def read_json_file(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"{label} is not valid UTF-8 JSON: {error}")


def write_exclusive_json(path: Path, value: Any, mode: int = 0o644) -> None:
    contents = (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")
    descriptor = os.open(
        path,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL,
        mode,
    )
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


def load_bound_run(
    verifier: Wave3Evidence, run_id: str, database_identity: str
) -> tuple[Path, dict[str, Any]]:
    validate_database_identity(database_identity)
    run_root = verifier.run_root(run_id)
    context = verifier._context(run_root)
    if context["databaseIdentity"] != database_identity:
        fail("requested database identity differs from the run context")
    snapshot = verifier.compute_source_snapshot()
    if snapshot.tree_hash != context["sourceTreeHash"]:
        fail("current normalized source tree differs from the run context")
    if (run_root / "source/end.json").exists():
        fail("source END already exists; pre-review orchestration is closed")
    return run_root, context


def prepare_plan(
    verifier: Wave3Evidence,
    run_id: str,
    database_identity: str,
    execute: bool,
) -> Path:
    if not execute:
        fail("prepare requires --execute; no file was written")
    run_root, context = load_bound_run(verifier, run_id, database_identity)
    output = plan_path(run_root)
    parent = output.parent
    if parent.exists():
        if not parent.is_dir() or parent.is_symlink():
            fail("orchestration directory is invalid")
    else:
        parent.mkdir(mode=0o755)
    if output.exists() or output.is_symlink():
        fail("refusing to overwrite an existing leaf plan")
    plan = build_leaf_plan(verifier, context)
    write_exclusive_json(output, plan)
    return output


def load_plan_for_run(
    verifier: Wave3Evidence, run_root: Path, context: dict[str, Any]
) -> tuple[Path, list[dict[str, Any]]]:
    candidate = verifier._safe_run_file(run_root, PLAN_RELATIVE_PATH)
    plan = read_json_file(candidate, "leaf plan")
    return candidate, validate_plan(plan, verifier, context)


def reject_forbidden_artifact_token(path: Path, label: str) -> None:
    try:
        contents = path.read_bytes()
    except OSError as error:
        fail(f"{label} cannot be read: {error}")
    if not contents:
        fail(f"{label} is empty")
    if FORBIDDEN_STRUCTURED_TOKEN.encode("utf-8") in contents:
        fail(f"{label} contains forbidden {FORBIDDEN_STRUCTURED_TOKEN}")


def validate_optional_structured_context(
    path: Path,
    evidence_id: str,
    context: dict[str, Any],
    label: str,
) -> None:
    if path.suffix.lower() not in {".json", ".har"}:
        return
    document = read_json_file(path, label)
    serialized = json.dumps(document, ensure_ascii=False)
    if FORBIDDEN_STRUCTURED_TOKEN in serialized:
        fail(f"{label} contains forbidden {FORBIDDEN_STRUCTURED_TOKEN}")
    if not isinstance(document, dict):
        return
    expected_if_present = {
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "evidenceId": evidence_id,
    }
    for key, expected in expected_if_present.items():
        if key in document and document[key] != expected:
            fail(f"{label} {key} differs from the current run")


def validate_raw_artifacts(
    verifier: Wave3Evidence,
    run_root: Path,
    context: dict[str, Any],
    leaves: Sequence[dict[str, Any]],
) -> None:
    upper_bound_ns = time.time_ns()
    errors: list[str] = []
    if any(leaf["evidenceId"] in BUILD_GATE_IDS for leaf in leaves):
        try:
            verifier._validate_build_gate_commit(
                run_root, context, upper_bound_ns
            )
        except (EvidenceError, OSError) as error:
            fail(f"build-gate batch commit preflight failed: {error}")
    for leaf in leaves:
        evidence_id = leaf["evidenceId"]
        artifact_paths = {
            artifact["role"]: artifact["path"] for artifact in leaf["artifacts"]
        }
        for role in leaf["requiredArtifactRoles"]:
            relative_path = artifact_paths[role]
            label = f"{evidence_id} {role}"
            try:
                candidate = verifier._safe_run_file(run_root, relative_path)
                metadata = candidate.stat()
                if metadata.st_mtime_ns < context["startedAtEpochNs"]:
                    fail(f"{label} predates the current run")
                if metadata.st_mtime_ns > upper_bound_ns:
                    fail(f"{label} mtime is in the future")
                reject_forbidden_artifact_token(candidate, label)
                validate_optional_structured_context(
                    candidate, evidence_id, context, label
                )
            except (EvidenceError, OrchestrationError, OSError) as error:
                errors.append(str(error))
        primary_path = artifact_paths[leaf["primaryArtifactRole"]]
        try:
            primary = verifier._safe_run_file(run_root, primary_path)
            verifier._validate_primary_artifact(
                primary,
                evidence_id,
                context,
                leaf["requiredMarkers"],
            )
        except (EvidenceError, OrchestrationError, OSError) as error:
            errors.append(str(error))
    if errors:
        preview = "\n".join(f"- {message}" for message in errors[:30])
        remainder = len(errors) - min(len(errors), 30)
        suffix = f"\n- ... and {remainder} more" if remainder else ""
        fail(f"raw artifact preflight failed:\n{preview}{suffix}")


def compare_registered_leaf_to_plan(
    verifier: Wave3Evidence,
    run_root: Path,
    leaf_plan: dict[str, Any],
) -> None:
    evidence_id = leaf_plan["evidenceId"]
    registered = verifier._validate_leaf(run_root, evidence_id, time.time_ns())
    expected_paths = {
        artifact["role"]: artifact["path"] for artifact in leaf_plan["artifacts"]
    }
    actual_paths = {
        descriptor["artifactRole"]: descriptor["path"]
        for descriptor in registered["artifacts"]
    }
    if actual_paths != expected_paths:
        fail(f"{evidence_id} registered artifact paths differ from the leaf plan")


def check_run(
    verifier: Wave3Evidence,
    run_id: str,
    database_identity: str,
    runtime_env: Path | None,
    login_env: Path | None,
    login_binding: Path | None,
) -> tuple[Path, dict[str, Any], list[dict[str, Any]]]:
    run_root, context = load_bound_run(verifier, run_id, database_identity)
    _, leaves = load_plan_for_run(verifier, run_root, context)
    if runtime_env is not None:
        validate_runtime_environment(runtime_env, database_identity)
    if login_env is not None or login_binding is not None:
        if login_env is None or login_binding is None:
            fail("--login-env and --login-binding must be supplied together")
        validate_login_binding(
            login_env,
            login_binding,
            run_id,
            database_identity,
        )
    validate_raw_artifacts(verifier, run_root, context, leaves)
    after = verifier.compute_source_snapshot()
    if after.tree_hash != context["sourceTreeHash"]:
        fail("source changed while raw artifacts were being checked")
    return run_root, context, leaves


def registration_arguments(
    repository_root: Path,
    run_id: str,
    leaf: dict[str, Any],
) -> list[str]:
    by_role = {
        artifact["role"]: artifact["path"] for artifact in leaf["artifacts"]
    }
    primary_role = leaf["primaryArtifactRole"]
    arguments = [
        str(repository_root / "scripts/qa/verify-wave3.sh"),
        "register",
        "--run-id",
        run_id,
        "--evidence-id",
        leaf["evidenceId"],
        "--primary",
        f"{primary_role}={by_role[primary_role]}",
    ]
    for role in leaf["requiredArtifactRoles"]:
        if role == primary_role:
            continue
        arguments.extend(["--artifact", f"{role}={by_role[role]}"])
    return arguments


def safe_subprocess_environment() -> dict[str, str]:
    allowed = (
        "PATH",
        "HOME",
        "TMPDIR",
        "SYSTEMROOT",
        "WINDIR",
    )
    environment = {
        key: os.environ[key] for key in allowed if key in os.environ
    }
    environment.update({"LC_ALL": "C", "LANG": "C", "TZ": "UTC"})
    return environment


def register_run(
    verifier: Wave3Evidence,
    repository_root: Path,
    run_id: str,
    database_identity: str,
    runtime_env: Path,
    login_env: Path,
    login_binding: Path,
    execute: bool,
    resume: bool,
) -> int:
    if not execute:
        fail("register requires --execute; no leaf was registered")
    run_root, _, leaves = check_run(
        verifier,
        run_id,
        database_identity,
        runtime_env,
        login_env,
        login_binding,
    )
    existing = [
        leaf
        for leaf in leaves
        if (run_root / "leaves" / leaf["slug"] / "leaf.json").exists()
    ]
    if existing and not resume:
        fail(
            "registered leaves already exist; use --resume only after verifying "
            "this same plan"
        )
    if resume:
        for leaf in existing:
            compare_registered_leaf_to_plan(verifier, run_root, leaf)

    registered_count = 0
    for leaf in leaves:
        leaf_path = run_root / "leaves" / leaf["slug"] / "leaf.json"
        if leaf_path.exists():
            continue
        result = subprocess.run(
            registration_arguments(repository_root, run_id, leaf),
            cwd=repository_root,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
            env=safe_subprocess_environment(),
        )
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip()
            fail(
                f"registration stopped at {leaf['evidenceId']}: "
                f"exit={result.returncode}: {detail}"
            )
        compare_registered_leaf_to_plan(verifier, run_root, leaf)
        registered_count += 1
    return registered_count


def portable_mode(path: Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


def validate_private_file(path: Path, repository_root: Path, label: str) -> Path:
    if not path.is_absolute():
        fail(f"{label} path must be absolute")
    try:
        requested = path.lstat()
    except OSError as error:
        fail(f"{label} is unavailable: {error}")
    if not stat.S_ISREG(requested.st_mode) or stat.S_ISLNK(requested.st_mode):
        fail(f"{label} must be a regular non-symbolic file")
    resolved = path.resolve(strict=True)
    if resolved == repository_root or repository_root in resolved.parents:
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
    repository_root: Path,
    allowed_keys: set[str],
    required_keys: set[str],
    label: str,
) -> tuple[Path, dict[str, str]]:
    resolved = validate_private_file(path, repository_root, label)
    values: dict[str, str] = {}
    try:
        lines = resolved.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        fail(f"{label} is not valid UTF-8 text: {error}")
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
    return resolved, values


def validate_runtime_environment(path: Path, database_identity: str) -> dict[str, str]:
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
    _, values = parse_private_environment(
        path,
        EXPECTED_REPOSITORY_ROOT,
        allowed,
        required,
        "runtime environment",
    )
    expected = {
        "SHENZHOUHR_MYSQL_HOST": "127.0.0.1",
        "SHENZHOUHR_MYSQL_PORT": "13306",
        "SHENZHOUHR_MYSQL_CLIENT_BIN": str(MYSQL_CLIENT),
        "SHENZHOUHR_W3_DB_IDENTITY": database_identity,
    }
    for key, value in expected.items():
        if values.get(key) != value:
            fail(f"runtime environment {key} differs from the bound run")
    client = Path(values["SHENZHOUHR_MYSQL_CLIENT_BIN"])
    if (
        not client.is_file()
        or client.is_symlink()
        or not os.access(client, os.X_OK)
        or client.resolve(strict=True) != MYSQL_CLIENT
    ):
        fail("runtime environment MySQL client is unavailable or indirect")
    return values


def validate_login_environment(path: Path) -> tuple[Path, dict[str, str]]:
    return parse_private_environment(
        path,
        EXPECTED_REPOSITORY_ROOT,
        {"SHENZHOUHR_LOGIN_USERNAME", "SHENZHOUHR_LOGIN_PASSWORD"},
        {"SHENZHOUHR_LOGIN_USERNAME", "SHENZHOUHR_LOGIN_PASSWORD"},
        "login environment",
    )


def login_binding_path(login_env: Path) -> Path:
    return Path(f"{login_env}.binding.json")


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def validate_login_binding(
    login_env: Path,
    binding_path: Path,
    run_id: str,
    database_identity: str,
) -> dict[str, Any]:
    resolved_login, values = validate_login_environment(login_env)
    resolved_binding = validate_private_file(
        binding_path, EXPECTED_REPOSITORY_ROOT, "login binding"
    )
    binding = read_json_file(resolved_binding, "login binding")
    assert_no_forbidden_plan_values(binding, "login binding")
    require_exact_keys(
        binding,
        {
            "schemaVersion",
            "nodeType",
            "runId",
            "databaseIdentity",
            "loginEnvPath",
            "loginEnvSha256",
            "usernameSha256",
            "passwordSha256",
            "accountState",
            "createdAt",
        },
        "login binding",
    )
    expected = {
        "schemaVersion": 1,
        "nodeType": "wave3-login-env-binding",
        "runId": run_id,
        "databaseIdentity": database_identity,
        "loginEnvPath": str(resolved_login),
        "loginEnvSha256": sha256_file(resolved_login),
        "usernameSha256": sha256_text(values["SHENZHOUHR_LOGIN_USERNAME"]),
        "passwordSha256": sha256_text(values["SHENZHOUHR_LOGIN_PASSWORD"]),
    }
    for key, value in expected.items():
        if binding.get(key) != value:
            fail(f"login binding {key} mismatch")
    if binding["accountState"] not in {
        "REQUIRES_EXPLICIT_PROVISIONING",
        "EXISTING_BOOTSTRAP_TO_BE_PROVEN_BY_RUNTIME",
    }:
        fail("login binding accountState is invalid")
    return binding


def validate_new_external_target(path: Path, label: str) -> Path:
    if not path.is_absolute():
        fail(f"{label} must be an absolute path")
    if path.exists() or path.is_symlink():
        fail(f"refusing to overwrite existing {label}")
    parent = path.parent
    if not parent.is_dir() or parent.is_symlink():
        fail(f"{label} parent must be an existing non-symbolic directory")
    resolved_parent = parent.resolve(strict=True)
    if resolved_parent != parent:
        fail(f"{label} parent must not resolve through a symbolic path")
    candidate = resolved_parent / path.name
    if (
        candidate == EXPECTED_REPOSITORY_ROOT
        or EXPECTED_REPOSITORY_ROOT in candidate.parents
    ):
        fail(f"{label} must remain outside the repository")
    return candidate


def prepare_login_environment(
    run_id: str,
    database_identity: str,
    output: Path,
    execute: bool,
    generate: bool,
    source_login_env: Path | None,
) -> dict[str, Any]:
    if not execute:
        fail("prepare-login-env requires --execute; no credential file was written")
    if not RUN_ID_PATTERN.fullmatch(run_id):
        fail("runId is invalid")
    validate_database_identity(database_identity)
    if generate == (source_login_env is not None):
        fail("choose exactly one of --generate or --source-login-env")
    target = validate_new_external_target(output, "login environment target")
    binding_target = validate_new_external_target(
        login_binding_path(target), "login binding target"
    )
    if source_login_env is not None:
        source_resolved, values = validate_login_environment(source_login_env)
        if source_resolved == target:
            fail("source and target login environments must differ")
        account_state = "EXISTING_BOOTSTRAP_TO_BE_PROVEN_BY_RUNTIME"
    else:
        entropy = secrets.token_hex(32)
        username = "w3_bootstrap_" + sha256_text(
            f"{run_id}|{database_identity}|{entropy}"
        )[:20]
        password = "W3!Aa9-" + secrets.token_urlsafe(36)
        values = {
            "SHENZHOUHR_LOGIN_USERNAME": username,
            "SHENZHOUHR_LOGIN_PASSWORD": password,
        }
        account_state = "REQUIRES_EXPLICIT_PROVISIONING"
    contents = (
        f"SHENZHOUHR_LOGIN_USERNAME={values['SHENZHOUHR_LOGIN_USERNAME']}\n"
        f"SHENZHOUHR_LOGIN_PASSWORD={values['SHENZHOUHR_LOGIN_PASSWORD']}\n"
    )
    created: list[Path] = []
    try:
        descriptor = os.open(
            target,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
        )
        created.append(target)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output_file:
            output_file.write(contents)
            output_file.flush()
            os.fsync(output_file.fileno())
        os.chmod(target, 0o600)
        binding = {
            "schemaVersion": 1,
            "nodeType": "wave3-login-env-binding",
            "runId": run_id,
            "databaseIdentity": database_identity,
            "loginEnvPath": str(target.resolve(strict=True)),
            "loginEnvSha256": sha256_file(target),
            "usernameSha256": sha256_text(
                values["SHENZHOUHR_LOGIN_USERNAME"]
            ),
            "passwordSha256": sha256_text(
                values["SHENZHOUHR_LOGIN_PASSWORD"]
            ),
            "accountState": account_state,
            "createdAt": utc_now(),
        }
        assert_no_forbidden_plan_values(binding, "login binding")
        write_exclusive_json(binding_target, binding, mode=0o600)
        created.append(binding_target)
        os.chmod(binding_target, 0o600)
        validate_login_binding(
            target,
            binding_target,
            run_id,
            database_identity,
        )
    except BaseException:
        for created_path in reversed(created):
            try:
                created_path.unlink()
            except FileNotFoundError:
                pass
        raise
    return {
        "path": str(target),
        "sha256": sha256_file(target),
        "bindingPath": str(binding_target),
        "bindingSha256": sha256_file(binding_target),
        "accountState": account_state,
    }


def preview_document(verifier: Wave3Evidence) -> dict[str, Any]:
    if tuple(verifier.pre_review_ids) != FIXED_PRE_REVIEW_IDS:
        fail("evidence contract pre-review order differs from the fixed 20")
    return {
        "schemaVersion": 1,
        "nodeType": "wave3-pre-review-leaf-plan-preview",
        "mutating": False,
        "leafCount": 20,
        "policy": POLICY,
        "privateInputs": PRIVATE_INPUTS,
        "leaves": [
            {
                "ordinal": ordinal,
                "evidenceId": evidence_id,
                "slug": verifier.leaf_contracts[evidence_id]["slug"],
                "requiredMarkers": verifier.leaf_contracts[evidence_id][
                    "requiredMarkers"
                ],
                "primaryArtifactRole": verifier.leaf_contracts[evidence_id][
                    "primaryArtifactRole"
                ],
                "requiredArtifactRoles": verifier.leaf_contracts[evidence_id][
                    "requiredArtifactRoles"
                ],
                "producer": PRODUCER_CATALOG[evidence_id],
            }
            for ordinal, evidence_id in enumerate(
                FIXED_PRE_REVIEW_IDS, start=1
            )
        ],
        "nextBoundary": (
            "initialize separately, produce fresh current-run raw artifacts, "
            "prepare/check/register; this helper never crosses source END"
        ),
    }


def print_human_plan(verifier: Wave3Evidence) -> None:
    preview = preview_document(verifier)
    print("W3 exact pre-review leaf orchestration (read-only plan)")
    print("  default action       : no writes, no builds, no DB connection")
    print("  source freeze        : never invoked by this helper")
    print("  registration         : explicit --execute only, exact contract order")
    print("  post-registration    : stop before assemble/review/manifest/integrity/final")
    print("  historical evidence  : never imported; active-run paths only")
    print(
        "  private login env    : external 0600 + run/db binding; secrets never printed"
    )
    for leaf in preview["leaves"]:
        roles = ",".join(leaf["requiredArtifactRoles"])
        print(
            f"{leaf['ordinal']:02d}. {leaf['evidenceId']} "
            f"producer={leaf['producer']['producerId']} roles={roles}"
        )


def static_self_test(verifier: Wave3Evidence) -> None:
    preview = preview_document(verifier)
    if preview["leafCount"] != 20:
        fail("preview leaf cardinality drifted")
    if len({leaf["slug"] for leaf in preview["leaves"]}) != 20:
        fail("preview leaf slugs are not unique")
    fake_context = {
        "runId": "w3-self-test-20260727",
        "sourceTreeHash": "a" * 64,
        "databaseIdentity": (
            "mysql8410:533ca0bc-89c8-41f1-a08b-1ee9344ad44b:"
            "shenzhou_hr_test"
        ),
        "contractSha256": sha256_file(verifier.contract_path),
    }
    plan = build_leaf_plan(verifier, fake_context)
    validate_plan(plan, verifier, fake_context)
    arguments = registration_arguments(
        EXPECTED_REPOSITORY_ROOT,
        fake_context["runId"],
        plan["leaves"][0],
    )
    if arguments[1] != "register" or "--primary" not in arguments:
        fail("registration argument generation drifted")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description=(
            "Safe W3 exact-20 raw-artifact planning, preflight and registration"
        )
    )
    subcommands = result.add_subparsers(dest="command")

    plan = subcommands.add_parser("plan", help="print a read-only exact-20 plan")
    plan.add_argument("--json", action="store_true")

    prepare = subcommands.add_parser(
        "prepare", help="write a run-bound artifact-role plan only"
    )
    prepare.add_argument("--run-id", required=True)
    prepare.add_argument("--db-identity", required=True)
    prepare.add_argument("--execute", action="store_true")

    check = subcommands.add_parser(
        "check", help="read-only preflight all raw artifacts"
    )
    check.add_argument("--run-id", required=True)
    check.add_argument("--db-identity", required=True)
    check.add_argument("--runtime-env", type=Path)
    check.add_argument("--login-env", type=Path)
    check.add_argument("--login-binding", type=Path)

    register = subcommands.add_parser(
        "register", help="register the exact 20 after a complete preflight"
    )
    register.add_argument("--run-id", required=True)
    register.add_argument("--db-identity", required=True)
    register.add_argument("--runtime-env", type=Path, required=True)
    register.add_argument("--login-env", type=Path, required=True)
    register.add_argument("--login-binding", type=Path, required=True)
    register.add_argument("--execute", action="store_true")
    register.add_argument("--resume", action="store_true")

    login = subcommands.add_parser(
        "prepare-login-env",
        help="create a repository-external run-bound mode-0600 login env",
    )
    login.add_argument("--run-id", required=True)
    login.add_argument("--db-identity", required=True)
    login.add_argument("--output", type=Path, required=True)
    mode = login.add_mutually_exclusive_group(required=True)
    mode.add_argument("--generate", action="store_true")
    mode.add_argument("--source-login-env", type=Path)
    login.add_argument("--execute", action="store_true")

    subcommands.add_parser("self-test", help="run non-mutating static checks")
    return result


def main(argv: Sequence[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    command = arguments.command or "plan"
    try:
        repository_root = require_repository_root()
        verifier = Wave3Evidence(repository_root)
        if command == "plan":
            if getattr(arguments, "json", False):
                print(
                    json.dumps(
                        preview_document(verifier),
                        ensure_ascii=False,
                        indent=2,
                    )
                )
            else:
                print_human_plan(verifier)
        elif command == "prepare":
            path = prepare_plan(
                verifier,
                arguments.run_id,
                arguments.db_identity,
                arguments.execute,
            )
            print(
                "W3_LEAF_PLAN_PREPARED "
                f"runId={arguments.run_id} path={path} leafCount=20"
            )
        elif command == "check":
            _, _, leaves = check_run(
                verifier,
                arguments.run_id,
                arguments.db_identity,
                arguments.runtime_env,
                arguments.login_env,
                arguments.login_binding,
            )
            print(
                "W3_LEAF_PREFLIGHT_READY "
                f"runId={arguments.run_id} leafCount={len(leaves)} "
                "next=explicit-register"
            )
        elif command == "register":
            count = register_run(
                verifier,
                repository_root,
                arguments.run_id,
                arguments.db_identity,
                arguments.runtime_env,
                arguments.login_env,
                arguments.login_binding,
                arguments.execute,
                arguments.resume,
            )
            print(
                "W3_EXACT_20_REGISTERED "
                f"runId={arguments.run_id} newlyRegistered={count} total=20 "
                "STOP=source-end-and-independent-review-require-separate-actions"
            )
        elif command == "prepare-login-env":
            metadata = prepare_login_environment(
                arguments.run_id,
                arguments.db_identity,
                arguments.output,
                arguments.execute,
                arguments.generate,
                arguments.source_login_env,
            )
            print(
                "W3_LOGIN_ENV_PREPARED "
                f"path={metadata['path']} sha256={metadata['sha256']} "
                f"binding={metadata['bindingPath']} "
                f"bindingSha256={metadata['bindingSha256']} "
                f"accountState={metadata['accountState']}"
            )
        elif command == "self-test":
            static_self_test(verifier)
            print("W3_LEAF_ORCHESTRATOR_SELF_TEST=PASS tests=6 leafCount=20")
        else:
            fail(f"unsupported command: {command}")
        return 0
    except (EvidenceError, OrchestrationError) as error:
        print(f"[orchestrate-wave3-leaves] ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
