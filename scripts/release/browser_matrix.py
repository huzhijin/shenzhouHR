#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.release.common import (  # noqa: E402
    ContractError,
    read_json,
    safe_relative_file,
    sha256_file,
    validate_commit,
    validate_run_id,
    validate_status,
)
from scripts.release.release_evidence import ZERO_COMMIT, aggregate_status  # noqa: E402

SCHEMA_VERSION = "shenzhouhr.w9.browser-matrix/v2"
FORMAL_RAW_SCHEMA_VERSION = "shenzhouhr.w9.formal-browser-raw/v1"
FORMAL_EXECUTION_SCHEMA_VERSION = "shenzhouhr.w9.formal-browser-execution/v1"
SOURCE_CONTRACT_PATH = (
    Path(__file__).resolve().parent / "contracts/browser-matrix-cases-v1.json"
)
SOURCE_CONTRACT_SHA256 = (
    "c9e3adecbdae69a46d08536624d35c427f150493595d4783c420fed9946cf047"
)
FORMAL_BROWSER_PRODUCER = {
    "id": "shenzhouhr-w9-integrated-browser-producer",
    "version": "1",
    "mode": "FORMAL_EXTERNAL",
}
FORMAL_BROWSER_SOURCE = {
    "schema_version": "shenzhouhr.w9.browser-matrix-cases/v1",
    "path": "scripts/release/contracts/browser-matrix-cases-v1.json",
    "sha256": SOURCE_CONTRACT_SHA256,
}
ALLOWED_SOURCE_MODES = frozenset({"PENDING_W7_FINAL", "REAL_INTEGRATED"})
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
FORMAL_IDENTITY_PATTERN = re.compile(r"^principal-sha256:[0-9a-f]{64}$")


def _load_source_contract() -> dict[str, Any]:
    if sha256_file(SOURCE_CONTRACT_PATH) != SOURCE_CONTRACT_SHA256:
        raise ContractError("trusted browser source contract SHA-256 mismatch")
    contract = read_json(SOURCE_CONTRACT_PATH)
    if not isinstance(contract, dict):
        raise ContractError("trusted browser source contract must be an object")
    if contract.get("schema_version") != FORMAL_BROWSER_SOURCE["schema_version"]:
        raise ContractError("trusted browser source contract schema is unsupported")
    return contract


SOURCE_CONTRACT = _load_source_contract()
REQUIRED_PLATFORMS = tuple(SOURCE_CONTRACT["platforms"])
REQUIRED_VIEWPORTS = tuple(SOURCE_CONTRACT["viewports"])
REQUIRED_CASES = tuple(SOURCE_CONTRACT["cases"])
REQUIRED_COVERAGE = frozenset(case["coverage"] for case in REQUIRED_CASES)
REQUIRED_CASE_FIELDS = {
    "id",
    "route",
    "role",
    "identity",
    "state",
    "coverage",
    "expected",
    "executions",
}
EXECUTION_FIELDS = {
    "execution_id",
    "platform",
    "viewport",
    "status",
    "run_id",
    "commit",
    "environment_id",
    "route",
    "role",
    "identity",
    "state",
    "result_path",
    "result_sha256",
    "screenshot_path",
    "screenshot_sha256",
    "trace_path",
    "trace_sha256",
    "console_errors",
    "page_errors",
    "network_summary",
    "reason",
}
NETWORK_FIELDS = {"total_requests", "completed_requests", "failed_requests"}
RESULT_FIELDS = {
    "schema_version",
    "producer_id",
    "source_sha256",
    "execution_id",
    "case_id",
    "platform",
    "viewport",
    "status",
    "run_id",
    "commit",
    "environment_id",
    "route",
    "role",
    "identity",
    "state",
    "screenshot_path",
    "screenshot_sha256",
    "trace_path",
    "trace_sha256",
    "console_errors",
    "page_errors",
    "network_summary",
    "reason",
    "started_at_utc",
    "completed_at_utc",
}
RAW_INDEX_FIELDS = {
    "schema_version",
    "run_id",
    "commit",
    "environment_id",
    "producer_id",
    "source_sha256",
    "execution_artifacts",
}
RAW_INDEX_ENTRY_FIELDS = {
    "execution_id",
    "result_path",
    "result_sha256",
    "screenshot_path",
    "screenshot_sha256",
    "trace_path",
    "trace_sha256",
}
PROVENANCE_FIELDS = {
    "source_mode",
    "integrated_commit",
    "environment_id",
    "started_at_utc",
    "completed_at_utc",
    "raw_artifact_path",
    "raw_artifact_sha256",
    "credential_values_logged",
}
TOP_LEVEL_FIELDS = {
    "schema_version",
    "run_id",
    "commit",
    "environment_id",
    "source_mode",
    "producer",
    "source",
    "provenance",
    "platforms",
    "viewports",
    "cases",
    "verdict",
}


def _require_exact_fields(
    value: dict[str, Any], fields: set[str], *, label: str
) -> None:
    actual = set(value)
    if actual != fields:
        raise ContractError(
            f"{label} fields differ from the exact contract: "
            f"missing={sorted(fields - actual)} "
            f"unexpected={sorted(actual - fields)}"
        )


def _parse_utc(value: Any, *, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ContractError(f"{label} must be an RFC3339 UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise ContractError(f"{label} must be an RFC3339 UTC timestamp") from exc
    if parsed.tzinfo != timezone.utc:
        raise ContractError(f"{label} must use UTC")
    return parsed


def _validate_interval(
    value: dict[str, Any], *, label: str
) -> tuple[datetime, datetime]:
    started = _parse_utc(value.get("started_at_utc"), label=f"{label} started_at_utc")
    completed = _parse_utc(
        value.get("completed_at_utc"), label=f"{label} completed_at_utc"
    )
    if completed < started:
        raise ContractError(f"{label} timestamps are reversed")
    return started, completed


def _validate_network_summary(value: Any, *, status: str) -> None:
    if not isinstance(value, dict):
        raise ContractError("browser network summary must be an object")
    _require_exact_fields(value, NETWORK_FIELDS, label="browser network summary")
    for field in NETWORK_FIELDS:
        item = value[field]
        if isinstance(item, bool) or not isinstance(item, int) or item < 0:
            raise ContractError(f"browser network summary {field} is invalid")
    if value["completed_requests"] > value["total_requests"]:
        raise ContractError("browser completed requests exceed total requests")
    if value["failed_requests"] > value["completed_requests"]:
        raise ContractError("browser failed requests exceed completed requests")
    if status == "PASS" and (
        value["total_requests"] <= 0
        or value["completed_requests"] != value["total_requests"]
        or value["failed_requests"] != 0
    ):
        raise ContractError(
            "PASS browser execution requires complete network evidence with zero failures"
        )


def _artifact(
    value: dict[str, Any],
    prefix: str,
    *,
    evidence_root: Path | None,
    required: bool,
    seen_paths: set[str],
    seen_hashes: set[str],
) -> Path | None:
    path_value = value.get(f"{prefix}_path")
    sha_value = value.get(f"{prefix}_sha256")
    if path_value is None and sha_value is None:
        if required:
            raise ContractError(f"{prefix} artifact is required")
        return None
    if not isinstance(path_value, str) or not path_value:
        raise ContractError(f"{prefix} path and SHA-256 must be provided together")
    if not isinstance(sha_value, str) or not SHA256_PATTERN.fullmatch(sha_value):
        raise ContractError(f"{prefix} SHA-256 is invalid")
    if path_value in seen_paths or sha_value in seen_hashes:
        raise ContractError(f"duplicate browser artifact: {prefix}")
    if evidence_root is None:
        raise ContractError(f"evidence root is required for {prefix} validation")
    artifact = safe_relative_file(evidence_root, path_value)
    if sha256_file(artifact) != sha_value:
        raise ContractError(f"{prefix} SHA-256 mismatch")
    seen_paths.add(path_value)
    seen_hashes.add(sha_value)
    return artifact


def _validate_formal_provenance(
    matrix: dict[str, Any], *, evidence_root: Path | None
) -> tuple[dict[str, Any], tuple[datetime, datetime]]:
    if matrix.get("producer") != FORMAL_BROWSER_PRODUCER:
        raise ContractError("browser evidence producer is not the approved formal producer")
    if matrix.get("source") != FORMAL_BROWSER_SOURCE:
        raise ContractError("browser evidence source contract provenance is invalid")
    provenance = matrix.get("provenance")
    if not isinstance(provenance, dict):
        raise ContractError("browser evidence provenance must be an object")
    _require_exact_fields(
        provenance, PROVENANCE_FIELDS, label="browser evidence provenance"
    )
    if (
        provenance["source_mode"] != "REAL_INTEGRATED"
        or provenance["integrated_commit"] != matrix["commit"]
        or provenance["environment_id"] != matrix["environment_id"]
        or provenance["credential_values_logged"] is not False
    ):
        raise ContractError("browser evidence provenance is invalid")
    interval = _validate_interval(provenance, label="browser evidence provenance")
    raw_sha256 = provenance["raw_artifact_sha256"]
    if not isinstance(raw_sha256, str) or not SHA256_PATTERN.fullmatch(raw_sha256):
        raise ContractError("browser raw artifact SHA-256 is invalid")
    if evidence_root is None:
        raise ContractError("evidence root is required for browser raw artifact")
    raw_path = safe_relative_file(evidence_root, provenance["raw_artifact_path"])
    if sha256_file(raw_path) != raw_sha256:
        raise ContractError("browser raw artifact SHA-256 mismatch")
    raw = read_json(raw_path)
    if not isinstance(raw, dict):
        raise ContractError("browser raw artifact must be a JSON object")
    _require_exact_fields(raw, RAW_INDEX_FIELDS, label="browser raw artifact")
    if (
        raw["schema_version"] != FORMAL_RAW_SCHEMA_VERSION
        or raw["run_id"] != matrix["run_id"]
        or raw["commit"] != matrix["commit"]
        or raw["environment_id"] != matrix["environment_id"]
        or raw["producer_id"] != FORMAL_BROWSER_PRODUCER["id"]
        or raw["source_sha256"] != FORMAL_BROWSER_SOURCE["sha256"]
        or not isinstance(raw["execution_artifacts"], list)
    ):
        raise ContractError("browser raw artifact provenance is invalid")
    return raw, interval


def _validate_execution_result(
    result_path: Path,
    *,
    execution: dict[str, Any],
    case: dict[str, Any],
    matrix: dict[str, Any],
    provenance_interval: tuple[datetime, datetime],
) -> None:
    result = read_json(result_path)
    if not isinstance(result, dict):
        raise ContractError("browser execution result must be a JSON object")
    _require_exact_fields(result, RESULT_FIELDS, label="browser execution result")
    expected = {
        "schema_version": FORMAL_EXECUTION_SCHEMA_VERSION,
        "producer_id": FORMAL_BROWSER_PRODUCER["id"],
        "source_sha256": FORMAL_BROWSER_SOURCE["sha256"],
        "execution_id": execution["execution_id"],
        "case_id": case["id"],
        **{field: execution[field] for field in EXECUTION_FIELDS if field not in {
            "execution_id",
            "result_path",
            "result_sha256",
        }},
    }
    for field, expected_value in expected.items():
        if result.get(field) != expected_value:
            raise ContractError(
                f"browser execution result {field} differs from matrix"
            )
    started, completed = _validate_interval(
        result, label=f"browser execution result {execution['execution_id']}"
    )
    if started < provenance_interval[0] or completed > provenance_interval[1]:
        raise ContractError("browser execution timestamps fall outside provenance")


def _expected_execution_id(case_id: str, platform: str, viewport: str) -> str:
    return f"{case_id}::{platform}::{viewport}"


def validate_browser_matrix(
    matrix: dict[str, Any], *, evidence_root: Path | None = None
) -> dict[str, Any]:
    if not isinstance(matrix, dict):
        raise ContractError("browser matrix must be an object")
    _require_exact_fields(matrix, TOP_LEVEL_FIELDS, label="browser matrix")
    if matrix.get("schema_version") != SCHEMA_VERSION:
        raise ContractError("unsupported browser matrix schema")
    validate_run_id(matrix.get("run_id"))
    validate_commit(matrix.get("commit"))
    environment_id = matrix.get("environment_id")
    if not isinstance(environment_id, str) or not environment_id:
        raise ContractError("browser environment_id must be non-empty")
    source_mode = matrix.get("source_mode")
    if source_mode not in ALLOWED_SOURCE_MODES:
        raise ContractError("unsupported browser source_mode")
    if tuple(matrix.get("platforms", ())) != REQUIRED_PLATFORMS:
        raise ContractError("browser platforms do not match the required ordered set")
    if tuple(matrix.get("viewports", ())) != REQUIRED_VIEWPORTS:
        raise ContractError("browser viewports do not match the required ordered set")
    cases = matrix.get("cases")
    if not isinstance(cases, list):
        raise ContractError("browser matrix cases must be an array")
    if len(cases) != len(REQUIRED_CASES):
        raise ContractError("browser cases do not match the trusted formal case set")

    if source_mode == "PENDING_W7_FINAL":
        if (
            matrix["commit"] != ZERO_COMMIT
            or matrix["producer"] is not None
            or matrix["source"] is not None
            or matrix["provenance"] is not None
        ):
            raise ContractError("pending browser matrix must not claim formal provenance")
        raw_index: dict[str, Any] | None = None
        provenance_interval: tuple[datetime, datetime] | None = None
    else:
        if matrix["commit"] == ZERO_COMMIT:
            raise ContractError("formal browser matrix commit must not be zero")
        raw_index, provenance_interval = _validate_formal_provenance(
            matrix, evidence_root=evidence_root
        )

    case_ids: set[str] = set()
    coverage: set[str] = set()
    execution_by_key: dict[tuple[str, str, str], dict[str, Any]] = {}
    raw_entries_by_id: dict[str, dict[str, Any]] = {}
    seen_artifact_paths: set[str] = (
        {matrix["provenance"]["raw_artifact_path"]}
        if raw_index is not None
        else set()
    )
    seen_artifact_hashes: set[str] = (
        {matrix["provenance"]["raw_artifact_sha256"]}
        if raw_index is not None
        else set()
    )
    statuses: list[str] = []

    if raw_index is not None:
        for entry in raw_index["execution_artifacts"]:
            if not isinstance(entry, dict):
                raise ContractError("browser raw artifact entry must be an object")
            _require_exact_fields(
                entry, RAW_INDEX_ENTRY_FIELDS, label="browser raw artifact entry"
            )
            execution_id = entry["execution_id"]
            if not isinstance(execution_id, str) or execution_id in raw_entries_by_id:
                raise ContractError("duplicate browser raw execution identity")
            raw_entries_by_id[execution_id] = entry

    for case, required_case in zip(cases, REQUIRED_CASES, strict=True):
        if not isinstance(case, dict):
            raise ContractError("browser case must be an object")
        _require_exact_fields(case, REQUIRED_CASE_FIELDS, label="browser case")
        for field, expected_value in required_case.items():
            if case[field] != expected_value:
                raise ContractError(
                    f"browser case {case.get('id')} {field} differs "
                    "from the trusted formal case set"
                )
        case_id = case["id"]
        if case_id in case_ids:
            raise ContractError(f"duplicate browser case: {case_id}")
        case_ids.add(case_id)
        coverage.add(case["coverage"])
        if source_mode == "PENDING_W7_FINAL":
            if case["identity"] != "NOT_VERIFIED" or case["executions"] != []:
                raise ContractError(
                    "pending browser case must not claim identity or executions"
                )
        elif (
            not isinstance(case["identity"], str)
            or not FORMAL_IDENTITY_PATTERN.fullmatch(case["identity"])
        ):
            raise ContractError("formal browser case identity is invalid")
        if not isinstance(case["executions"], list):
            raise ContractError("browser case executions must be an array")

        for execution in case["executions"]:
            if not isinstance(execution, dict):
                raise ContractError("browser execution must be an object")
            _require_exact_fields(
                execution, EXECUTION_FIELDS, label="browser execution"
            )
            platform = execution["platform"]
            viewport = execution["viewport"]
            if platform not in REQUIRED_PLATFORMS or viewport not in REQUIRED_VIEWPORTS:
                raise ContractError("browser execution has an unknown platform or viewport")
            key = (case_id, platform, viewport)
            if key in execution_by_key:
                raise ContractError(f"duplicate browser execution: {key}")
            execution_by_key[key] = execution
            execution_id = _expected_execution_id(case_id, platform, viewport)
            if execution["execution_id"] != execution_id:
                raise ContractError("browser execution identity is invalid")
            if execution["run_id"] != matrix["run_id"]:
                raise ContractError("browser execution run_id differs from matrix")
            if execution["commit"] != matrix["commit"]:
                raise ContractError("browser execution commit differs from matrix")
            if execution["environment_id"] != environment_id:
                raise ContractError("browser execution environment differs from matrix")
            for field in ("route", "role", "identity", "state"):
                if execution[field] != case[field]:
                    raise ContractError(
                        f"browser execution {field} differs from case"
                    )
            status = validate_status(execution["status"])
            statuses.append(status)
            if not isinstance(execution["reason"], str) or not execution["reason"]:
                raise ContractError("browser execution reason must be non-empty")
            if not all(
                isinstance(execution[name], list)
                for name in ("console_errors", "page_errors")
            ):
                raise ContractError("browser error evidence has invalid types")
            _validate_network_summary(execution["network_summary"], status=status)

            if status == "NOT_VERIFIED":
                for prefix in ("result", "screenshot", "trace"):
                    if (
                        execution[f"{prefix}_path"] is not None
                        or execution[f"{prefix}_sha256"] is not None
                    ):
                        raise ContractError(
                            "NOT_VERIFIED browser execution must not claim artifacts"
                        )
                continue

            result_path = _artifact(
                execution,
                "result",
                evidence_root=evidence_root,
                required=True,
                seen_paths=seen_artifact_paths,
                seen_hashes=seen_artifact_hashes,
            )
            _artifact(
                execution,
                "screenshot",
                evidence_root=evidence_root,
                required=False,
                seen_paths=seen_artifact_paths,
                seen_hashes=seen_artifact_hashes,
            )
            _artifact(
                execution,
                "trace",
                evidence_root=evidence_root,
                required=True,
                seen_paths=seen_artifact_paths,
                seen_hashes=seen_artifact_hashes,
            )
            if status == "PASS" and (
                execution["console_errors"] or execution["page_errors"]
            ):
                raise ContractError(
                    "PASS browser execution requires empty console and page errors"
                )
            if raw_index is None or provenance_interval is None:
                raise ContractError("browser execution lacks formal raw provenance")
            raw_entry = raw_entries_by_id.get(execution_id)
            expected_raw_entry = {
                "execution_id": execution_id,
                **{
                    field: execution[field]
                    for field in RAW_INDEX_ENTRY_FIELDS
                    if field != "execution_id"
                },
            }
            if raw_entry != expected_raw_entry:
                raise ContractError(
                    "browser raw artifact entry differs from execution"
                )
            assert result_path is not None
            _validate_execution_result(
                result_path,
                execution=execution,
                case=case,
                matrix=matrix,
                provenance_interval=provenance_interval,
            )

    if coverage != REQUIRED_COVERAGE:
        raise ContractError("browser coverage differs from the trusted formal set")
    expected_keys = {
        (case_id, platform, viewport)
        for case_id in case_ids
        for platform in REQUIRED_PLATFORMS
        for viewport in REQUIRED_VIEWPORTS
    }
    missing_executions = expected_keys - execution_by_key.keys()
    unexpected_raw_ids = set(raw_entries_by_id) - {
        execution["execution_id"] for execution in execution_by_key.values()
    }
    if unexpected_raw_ids or len(raw_entries_by_id) != len(execution_by_key):
        raise ContractError("browser raw artifact index differs from executions")
    execution_status = (
        aggregate_status(statuses)
        if not missing_executions
        else (
            "FAIL"
            if any(status == "FAIL" for status in statuses)
            else "NOT_VERIFIED"
        )
    )
    # JSON declarations and ordinary hashed files prove structural consistency,
    # not that a trusted CI/browser-device farm produced them. Until a signed
    # attestation verifier exists, a non-failing matrix must remain NOT_VERIFIED.
    gate_status = (
        "FAIL" if execution_status == "FAIL" else "NOT_VERIFIED"
    )
    declared = validate_status(matrix.get("verdict"))
    if declared != gate_status:
        raise ContractError(
            f"declared browser verdict {declared} differs from derived {gate_status}"
        )
    return {
        "contract_status": "PASS",
        "browser_gate_status": gate_status,
        "case_count": len(case_ids),
        "required_execution_count": len(expected_keys),
        "recorded_execution_count": len(execution_by_key),
        "missing_execution_count": len(missing_executions),
        "source_mode": source_mode,
        "producer_id": (
            FORMAL_BROWSER_PRODUCER["id"]
            if source_mode == "REAL_INTEGRATED"
            else None
        ),
        "trusted_attestation_status": "NOT_VERIFIED",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate W9 browser release matrix")
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path)
    args = parser.parse_args(argv)
    try:
        result = validate_browser_matrix(
            read_json(args.matrix), evidence_root=args.evidence_root
        )
    except ContractError as exc:
        print(f"W9_BROWSER_MATRIX_INVALID={exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
