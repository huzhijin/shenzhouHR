#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
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

SCHEMA_VERSION = "shenzhouhr.w9.browser-matrix/v1"
REQUIRED_PLATFORMS = (
    "chrome-current",
    "chrome-previous",
    "edge-current",
    "edge-previous",
    "ios-safari-approved",
    "android-chrome-approved",
)
REQUIRED_VIEWPORTS = (
    "390x844",
    "768x1024",
    "1024x768",
    "1366x768",
    "1440x900",
    "1920x1080",
    "3840x2160",
)
REQUIRED_COVERAGE = frozenset(
    {
        "role-allow",
        "role-deny",
        "loading",
        "empty",
        "error-retry",
        "forbidden-403",
        "frozen",
        "keyboard-focus",
        "overflow",
        "cache-revocation",
        "payroll-zero",
    }
)
ALLOWED_SOURCE_MODES = frozenset({"PENDING_W7_FINAL", "DEMO", "REAL_INTEGRATED"})


def _validate_artifact(
    execution: dict[str, Any], prefix: str, evidence_root: Path | None
) -> bool:
    path_value = execution.get(f"{prefix}_path")
    sha_value = execution.get(f"{prefix}_sha256")
    if path_value is None and sha_value is None:
        return False
    if path_value is None or sha_value is None:
        raise ContractError(f"{prefix} path and SHA-256 must be provided together")
    if evidence_root is None:
        raise ContractError(f"evidence root is required for {prefix} validation")
    artifact = safe_relative_file(evidence_root, path_value)
    if sha256_file(artifact) != sha_value:
        raise ContractError(f"{prefix} SHA-256 mismatch")
    return True


def validate_browser_matrix(
    matrix: dict[str, Any], *, evidence_root: Path | None = None
) -> dict[str, Any]:
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
    if not isinstance(cases, list) or not cases:
        raise ContractError("browser matrix must contain cases")

    case_ids: set[str] = set()
    coverage: set[str] = set()
    execution_by_key: dict[tuple[str, str, str], dict[str, Any]] = {}
    statuses: list[str] = []
    for case in cases:
        if not isinstance(case, dict):
            raise ContractError("browser case must be an object")
        required = {"id", "route", "identity", "state", "coverage", "expected", "executions"}
        missing = required - case.keys()
        if missing:
            raise ContractError(f"browser case is missing fields: {sorted(missing)}")
        case_id = str(case["id"])
        if case_id in case_ids:
            raise ContractError(f"duplicate browser case: {case_id}")
        case_ids.add(case_id)
        coverage.add(str(case["coverage"]))
        if not isinstance(case["executions"], list):
            raise ContractError("browser case executions must be an array")
        for execution in case["executions"]:
            if not isinstance(execution, dict):
                raise ContractError("browser execution must be an object")
            execution_required = {
                "platform",
                "viewport",
                "status",
                "run_id",
                "commit",
                "environment_id",
                "route",
                "identity",
                "screenshot_path",
                "screenshot_sha256",
                "trace_path",
                "trace_sha256",
                "console_errors",
                "page_errors",
                "network_summary",
                "reason",
            }
            missing_execution = execution_required - execution.keys()
            if missing_execution:
                raise ContractError(
                    f"browser execution is missing fields: {sorted(missing_execution)}"
                )
            platform = execution["platform"]
            viewport = execution["viewport"]
            if platform not in REQUIRED_PLATFORMS or viewport not in REQUIRED_VIEWPORTS:
                raise ContractError("browser execution has an unknown platform or viewport")
            key = (case_id, platform, viewport)
            if key in execution_by_key:
                raise ContractError(f"duplicate browser execution: {key}")
            execution_by_key[key] = execution
            if execution["run_id"] != matrix["run_id"]:
                raise ContractError("browser execution run_id differs from matrix")
            if execution["commit"] != matrix["commit"]:
                raise ContractError("browser execution commit differs from matrix")
            if execution["environment_id"] != environment_id:
                raise ContractError("browser execution environment differs from matrix")
            if execution["route"] != case["route"] or execution["identity"] != case["identity"]:
                raise ContractError("browser execution route/identity differs from case")
            status = validate_status(execution["status"])
            statuses.append(status)
            if not isinstance(execution["reason"], str) or not execution["reason"]:
                raise ContractError("browser execution reason must be non-empty")
            if not all(
                isinstance(execution[name], list)
                for name in ("console_errors", "page_errors")
            ) or not isinstance(execution["network_summary"], dict):
                raise ContractError("browser error/network evidence has invalid types")
            if status == "NOT_VERIFIED":
                for field in (
                    "screenshot_path",
                    "screenshot_sha256",
                    "trace_path",
                    "trace_sha256",
                ):
                    if execution[field] is not None:
                        raise ContractError(
                            "NOT_VERIFIED browser execution must not claim artifacts"
                        )
            else:
                has_screenshot = _validate_artifact(
                    execution, "screenshot", evidence_root
                )
                has_trace = _validate_artifact(execution, "trace", evidence_root)
                if not has_screenshot and not has_trace:
                    raise ContractError("PASS/FAIL browser execution requires evidence")

    missing_coverage = REQUIRED_COVERAGE - coverage
    unexpected_coverage = coverage - REQUIRED_COVERAGE
    if missing_coverage or unexpected_coverage:
        raise ContractError(
            f"browser coverage differs: missing={sorted(missing_coverage)} "
            f"unexpected={sorted(unexpected_coverage)}"
        )
    expected_keys = {
        (case_id, platform, viewport)
        for case_id in case_ids
        for platform in REQUIRED_PLATFORMS
        for viewport in REQUIRED_VIEWPORTS
    }
    missing_executions = expected_keys - execution_by_key.keys()
    execution_status = (
        aggregate_status(statuses)
        if not missing_executions
        else (
            "FAIL"
            if any(status == "FAIL" for status in statuses)
            else "NOT_VERIFIED"
        )
    )
    gate_status = execution_status
    if source_mode != "REAL_INTEGRATED" or matrix["commit"] == ZERO_COMMIT:
        if gate_status != "FAIL":
            gate_status = "NOT_VERIFIED"
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
