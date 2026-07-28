#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterable

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
    write_json,
)

SCHEMA_VERSION = "shenzhouhr.w9.release-evidence/v1"
ZERO_COMMIT = "0" * 40
REQUIRED_RELEASE_GATES = (
    "w7_final",
    "threat_dynamic",
    "performance_50_concurrency",
    "capacity_36_month",
    "mysql_8_4_lts",
    "browser_matrix",
    "recovery_drill",
    "native_deployment",
)


def aggregate_status(statuses: Iterable[str]) -> str:
    normalized = [validate_status(status) for status in statuses]
    if any(status == "FAIL" for status in normalized):
        return "FAIL"
    if not normalized or any(status == "NOT_VERIFIED" for status in normalized):
        return "NOT_VERIFIED"
    return "PASS"


def not_verified_gate(
    gate: str,
    *,
    run_id: str,
    commit: str,
    environment_id: str,
    reason: str,
) -> dict[str, Any]:
    return {
        "gate": gate,
        "status": "NOT_VERIFIED",
        "run_id": run_id,
        "commit": commit,
        "environment_id": environment_id,
        "evidence_path": None,
        "evidence_sha256": None,
        "reason": reason,
        "details": {},
    }


def new_not_verified_manifest(
    *, run_id: str, commit: str, dirty: bool, environment_id: str
) -> dict[str, Any]:
    validate_run_id(run_id)
    validate_commit(commit)
    reasons = {
        "w7_final": "W7 FINAL has not been synchronized into an integrated commit",
        "threat_dynamic": "authorized dynamic threat matrix has not run",
        "performance_50_concurrency": "50-concurrency integrated performance run has not run",
        "capacity_36_month": "production baseline and 36-month POC are unavailable",
        "mysql_8_4_lts": "approved MySQL 8.4 LTS environment has not been verified",
        "browser_matrix": "real browser and device matrix has not run",
        "recovery_drill": "human MySQL 8.4 PITR/RPO/RTO drill has not run",
        "native_deployment": "Ubuntu 24.04 candidate preflight and rollback have not run",
    }
    gates = [
        not_verified_gate(
            gate,
            run_id=run_id,
            commit=commit,
            environment_id=environment_id,
            reason=reasons[gate],
        )
        for gate in REQUIRED_RELEASE_GATES
    ]
    return {
        "schema_version": SCHEMA_VERSION,
        "run_id": run_id,
        "commit": commit,
        "dirty": dirty,
        "environment_id": environment_id,
        "gates": gates,
        "verdict": "NOT_VERIFIED",
        "explicit_non_dependencies": ["w8"],
    }


def _validate_gate(
    gate: dict[str, Any],
    *,
    manifest: dict[str, Any],
    run_root: Path | None,
) -> None:
    required = {
        "gate",
        "status",
        "run_id",
        "commit",
        "environment_id",
        "evidence_path",
        "evidence_sha256",
        "reason",
    }
    missing = required - gate.keys()
    if missing:
        raise ContractError(f"gate is missing fields: {sorted(missing)}")
    if not isinstance(gate["gate"], str) or not gate["gate"]:
        raise ContractError("gate name must be a non-empty string")
    status = validate_status(gate["status"])
    if gate["run_id"] != manifest["run_id"]:
        raise ContractError(f"{gate['gate']}: run_id differs from manifest")
    if gate["commit"] != manifest["commit"]:
        raise ContractError(f"{gate['gate']}: commit differs from manifest")
    if gate["environment_id"] != manifest["environment_id"]:
        raise ContractError(f"{gate['gate']}: environment_id differs from manifest")
    if not isinstance(gate["reason"], str) or not gate["reason"].strip():
        raise ContractError(f"{gate['gate']}: reason must explain the result")

    evidence_path = gate["evidence_path"]
    evidence_sha256 = gate["evidence_sha256"]
    if status == "NOT_VERIFIED":
        if evidence_path is not None or evidence_sha256 is not None:
            raise ContractError(
                f"{gate['gate']}: NOT_VERIFIED evidence path/hash must be null"
            )
        return

    if run_root is None:
        raise ContractError(
            f"{gate['gate']}: run root is required to validate PASS/FAIL evidence"
        )
    evidence_file = safe_relative_file(run_root, evidence_path)
    actual_sha = sha256_file(evidence_file)
    if actual_sha != evidence_sha256:
        raise ContractError(f"{gate['gate']}: evidence SHA-256 mismatch")


def _verify_w7_ancestor(
    manifest: dict[str, Any], gate_by_name: dict[str, dict[str, Any]], repo: Path
) -> None:
    w7_gate = gate_by_name["w7_final"]
    details = w7_gate.get("details")
    if not isinstance(details, dict):
        raise ContractError("w7_final PASS evidence requires details")
    w7_commit = validate_commit(details.get("w7_commit"))
    process = subprocess.run(
        ["git", "merge-base", "--is-ancestor", w7_commit, manifest["commit"]],
        cwd=repo,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if process.returncode != 0:
        raise ContractError("W7 FINAL commit is not an ancestor of integrated commit")


def validate_manifest(
    manifest: dict[str, Any],
    *,
    run_root: Path | None = None,
    repo: Path | None = None,
) -> str:
    required = {
        "schema_version",
        "run_id",
        "commit",
        "dirty",
        "environment_id",
        "gates",
        "verdict",
    }
    missing = required - manifest.keys()
    if missing:
        raise ContractError(f"manifest is missing fields: {sorted(missing)}")
    if manifest["schema_version"] != SCHEMA_VERSION:
        raise ContractError(f"unsupported schema_version: {manifest['schema_version']!r}")
    validate_run_id(manifest["run_id"])
    validate_commit(manifest["commit"])
    if not isinstance(manifest["dirty"], bool):
        raise ContractError("dirty must be boolean")
    if (
        not isinstance(manifest["environment_id"], str)
        or not manifest["environment_id"].strip()
    ):
        raise ContractError("environment_id must be a non-empty string")
    if not isinstance(manifest["gates"], list):
        raise ContractError("gates must be an array")

    gate_by_name: dict[str, dict[str, Any]] = {}
    for gate in manifest["gates"]:
        if not isinstance(gate, dict):
            raise ContractError("each gate must be an object")
        name = gate.get("gate")
        if name in gate_by_name:
            raise ContractError(f"duplicate gate: {name}")
        _validate_gate(gate, manifest=manifest, run_root=run_root)
        gate_by_name[str(name)] = gate

    missing_gates = set(REQUIRED_RELEASE_GATES) - gate_by_name.keys()
    if missing_gates:
        raise ContractError(f"missing required gates: {sorted(missing_gates)}")
    if "w8" in gate_by_name:
        raise ContractError("W8 must not be modeled as a W9 required release gate")

    derived = aggregate_status(
        gate_by_name[name]["status"] for name in REQUIRED_RELEASE_GATES
    )
    declared = validate_status(manifest["verdict"])
    if declared != derived:
        raise ContractError(
            f"declared verdict {declared} does not match derived verdict {derived}"
        )
    if derived == "PASS":
        if manifest["dirty"]:
            raise ContractError("PASS requires a clean integrated worktree")
        if manifest["commit"] == ZERO_COMMIT:
            raise ContractError("PASS cannot use the zero placeholder commit")
        if repo is None:
            raise ContractError("PASS requires repository ancestry verification")
        _verify_w7_ancestor(manifest, gate_by_name, repo)
    return derived


def render_summary(manifest: dict[str, Any]) -> str:
    lines = [
        "# W9 Release Evidence Summary",
        "",
        f"- Run ID: `{manifest['run_id']}`",
        f"- Integrated commit: `{manifest['commit']}`",
        f"- Environment: `{manifest['environment_id']}`",
        f"- Release verdict: `{manifest['verdict']}`",
        "- W8 dependency: `NOT_REQUIRED`",
        "",
        "## Gates",
        "",
    ]
    for gate in manifest["gates"]:
        lines.append(
            f"- `{gate['gate']}`: `{gate['status']}` — {gate['reason'].strip()}"
        )
    if manifest["verdict"] != "PASS":
        lines.extend(
            [
                "",
                "> This evidence does not authorize production release. "
                "All NOT_VERIFIED gates must be rerun on the final integrated commit.",
            ]
        )
    return "\n".join(lines) + "\n"


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate W9 release evidence")
    subparsers = parser.add_subparsers(dest="command", required=True)

    template = subparsers.add_parser("template")
    template.add_argument("--run-id", required=True)
    template.add_argument("--commit", required=True)
    template.add_argument("--environment-id", required=True)
    template.add_argument("--dirty", action="store_true")
    template.add_argument("--output", type=Path)
    template.add_argument("--summary", type=Path)

    verify = subparsers.add_parser("verify")
    verify.add_argument("--manifest", type=Path, required=True)
    verify.add_argument("--run-root", type=Path)
    verify.add_argument("--repo", type=Path)
    verify.add_argument("--summary", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        if args.command == "template":
            manifest = new_not_verified_manifest(
                run_id=args.run_id,
                commit=args.commit,
                dirty=args.dirty,
                environment_id=args.environment_id,
            )
            if args.output:
                write_json(args.output, manifest)
            else:
                print(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True))
            if args.summary:
                args.summary.write_text(render_summary(manifest), encoding="utf-8")
            return 0

        manifest = read_json(args.manifest)
        verdict = validate_manifest(
            manifest,
            run_root=args.run_root,
            repo=args.repo,
        )
        if args.summary:
            args.summary.write_text(render_summary(manifest), encoding="utf-8")
        print(f"W9_RELEASE_VERDICT={verdict}")
        return 0
    except ContractError as exc:
        print(f"W9_EVIDENCE_INVALID={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
