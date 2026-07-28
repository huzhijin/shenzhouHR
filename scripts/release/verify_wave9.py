#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Sequence

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.release.common import (  # noqa: E402
    ContractError,
    validate_commit,
    validate_run_id,
    write_json,
)

CHANGE_ID = "wave9-release-hardening-and-acceptance"
SCHEMA_VERSION = "shenzhouhr.w9.local-orchestration/v1"
EXTERNAL_GATES = (
    "w7_final_integrated_commit",
    "mysql_8_4_lts_and_36_month_capacity",
    "real_browser_device_matrix",
    "human_recovery_drill",
    "ubuntu_native_deployment_and_rollback",
)


@dataclass(frozen=True)
class GateCommand:
    gate: str
    argv: tuple[str, ...]


@dataclass(frozen=True)
class GateResult:
    gate: str
    command: list[str]
    exit_code: int
    status: str
    output_sha256: str
    output_tail: str


Runner = Callable[[Sequence[str], Path], subprocess.CompletedProcess[str]]


def repository_commit(repository: Path) -> str:
    process = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repository,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if process.returncode != 0:
        raise ContractError(f"cannot resolve repository commit: {process.stderr.strip()}")
    return validate_commit(process.stdout.strip())


def repository_dirty(repository: Path) -> bool:
    process = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=repository,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if process.returncode != 0:
        raise ContractError(f"cannot inspect repository state: {process.stderr.strip()}")
    return bool(process.stdout.strip())


def build_commands(
    repository: Path, *, run_id: str, commit: str
) -> tuple[GateCommand, ...]:
    validate_run_id(run_id)
    validate_commit(commit)
    python = sys.executable
    return (
        GateCommand(
            "openspec_strict",
            (
                "openspec",
                "validate",
                CHANGE_ID,
                "--type",
                "change",
                "--strict",
                "--json",
            ),
        ),
        GateCommand(
            "w9_unit_contracts",
            (
                python,
                "-m",
                "unittest",
                "discover",
                "-s",
                "scripts/release/tests",
                "-v",
            ),
        ),
        GateCommand(
            "threat_static",
            (
                python,
                "scripts/release/verify_threats.py",
                "--repo",
                ".",
                "--run-id",
                run_id,
                "--commit",
                commit,
            ),
        ),
        GateCommand(
            "native_template_static",
            (python, "scripts/release/native_preflight.py", "--repo", "."),
        ),
        GateCommand(
            "browser_matrix_contract",
            (
                python,
                "scripts/release/browser_matrix.py",
                "--matrix",
                "docs/verification/wave9/browser/browser-matrix.template.json",
            ),
        ),
        GateCommand(
            "release_manifest_contract",
            (
                python,
                "scripts/release/release_evidence.py",
                "verify",
                "--manifest",
                "docs/verification/wave9/release-manifest.template.json",
            ),
        ),
    )


def default_runner(
    argv: Sequence[str], repository: Path
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(argv),
        cwd=repository,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def _result(command: GateCommand, process: subprocess.CompletedProcess[str]) -> GateResult:
    combined = "\n".join(
        part.rstrip() for part in (process.stdout, process.stderr) if part.strip()
    )
    return GateResult(
        gate=command.gate,
        command=list(command.argv),
        exit_code=process.returncode,
        status="PASS" if process.returncode == 0 else "FAIL",
        output_sha256=hashlib.sha256(combined.encode("utf-8")).hexdigest(),
        output_tail=combined[-4000:],
    )


def summarize(
    *,
    run_id: str,
    commit: str,
    dirty: bool,
    results: Sequence[GateResult],
) -> dict[str, object]:
    harness_status = (
        "PASS" if results and all(result.status == "PASS" for result in results) else "FAIL"
    )
    return {
        "schema_version": SCHEMA_VERSION,
        "run_id": validate_run_id(run_id),
        "commit": validate_commit(commit),
        "dirty": dirty,
        "execution_scope": "LOCAL_OFFLINE_ONLY",
        "production_connections": 0,
        "harness_status": harness_status,
        "harness_ready": harness_status == "PASS",
        "release_verdict": "NOT_VERIFIED",
        "release_authorized": False,
        "explicit_non_dependencies": ["w8"],
        "external_gates": [
            {"gate": gate, "status": "NOT_VERIFIED"} for gate in EXTERNAL_GATES
        ],
        "results": [asdict(result) for result in results],
    }


def run_orchestration(
    repository: Path,
    *,
    run_id: str,
    runner: Runner = default_runner,
) -> dict[str, object]:
    repository = repository.resolve()
    commit = repository_commit(repository)
    dirty = repository_dirty(repository)
    results = [
        _result(command, runner(command.argv, repository))
        for command in build_commands(repository, run_id=run_id, commit=commit)
    ]
    return summarize(
        run_id=run_id,
        commit=commit,
        dirty=dirty,
        results=results,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Run W9 local/offline gates. This never authorizes a production release."
        )
    )
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        result = run_orchestration(args.repo, run_id=args.run_id)
    except (ContractError, OSError) as exc:
        print(f"W9_LOCAL_ORCHESTRATION_INVALID={exc}", file=sys.stderr)
        return 2

    rendered = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        write_json(args.output, result)
    else:
        print(rendered, end="")
    print(f"W9_HARNESS_STATUS={result['harness_status']}", file=sys.stderr)
    print("W9_RELEASE_VERDICT=NOT_VERIFIED", file=sys.stderr)
    return 0 if result["harness_status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
