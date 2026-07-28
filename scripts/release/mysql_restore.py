#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.release.common import (  # noqa: E402
    ContractError,
    read_json,
    sha256_file,
    validate_run_id,
)
from scripts.release.mysql_safety import (  # noqa: E402
    read_client_identity,
    require_executable,
    validate_restore_target,
)


def restore_plan(run_id: str, target_database: str) -> dict[str, Any]:
    validate_run_id(run_id)
    validate_restore_target(target_database)
    return {
        "schema_version": "shenzhouhr.w9.mysql-restore-plan/v1",
        "mode": "PLAN",
        "network_connections": 0,
        "run_id": run_id,
        "target_database": target_database,
        "required_confirmation": f"{target_database}:{run_id}",
        "steps": [
            "verify backup manifest and dump SHA-256",
            "validate outside-repository 0600 client option file and loopback identity",
            "prove target sandbox does not exist",
            "create a new utf8mb4_0900_ai_ci *_restore database without DROP",
            "import dump and run the explicit verification hook",
            "record elapsed time; retain sandbox for human inspection",
        ],
        "recovery_gate_status": "NOT_VERIFIED",
    }


def _mysql_command(binary: str, client_file: Path, *args: str) -> list[str]:
    return [binary, f"--defaults-extra-file={client_file}", *args]


def _run(command: list[str], *, input_file: Path | None = None) -> str:
    input_handle = input_file.open("rb") if input_file else None
    try:
        process = subprocess.run(
            command,
            check=False,
            stdin=input_handle,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    finally:
        if input_handle:
            input_handle.close()
    if process.returncode != 0:
        raise ContractError(f"MySQL restore command failed with exit {process.returncode}")
    return process.stdout.decode("utf-8", errors="replace").strip()


def run_restore(
    *,
    repository_root: Path,
    run_id: str,
    target_database: str,
    confirmation: str,
    client_file: Path,
    backup_manifest_path: Path,
    dump_path: Path,
    mysql_binary: str,
    verification_sql: Path | None,
) -> dict[str, Any]:
    validate_run_id(run_id)
    validate_restore_target(target_database)
    if confirmation != f"{target_database}:{run_id}":
        raise ContractError("restore confirmation token does not match target and run_id")
    host, port = read_client_identity(client_file, repository_root)
    mysql_binary = require_executable(mysql_binary)
    manifest = read_json(backup_manifest_path)
    if manifest.get("schema_version") != "shenzhouhr.w9.mysql-backup/v1":
        raise ContractError("unsupported backup manifest")
    if manifest.get("run_id") != run_id:
        raise ContractError("backup manifest run_id differs from restore run_id")
    if not dump_path.is_file() or sha256_file(dump_path) != manifest.get("dump_sha256"):
        raise ContractError("backup dump is missing or SHA-256 does not match")

    query = (
        "SELECT COUNT(*) FROM information_schema.schemata "
        f"WHERE schema_name='{target_database}'"
    )
    exists = _run(
        _mysql_command(
            mysql_binary,
            client_file,
            "--batch",
            "--skip-column-names",
            "--execute",
            query,
        )
    )
    if exists != "0":
        raise ContractError("restore target already exists; the tool never drops databases")

    started = time.monotonic()
    _run(
        _mysql_command(
            mysql_binary,
            client_file,
            "--execute",
            (
                f"CREATE DATABASE `{target_database}` CHARACTER SET utf8mb4 "
                "COLLATE utf8mb4_0900_ai_ci"
            ),
        )
    )
    _run(
        _mysql_command(mysql_binary, client_file, "--database", target_database),
        input_file=dump_path,
    )
    verification_status = "NOT_VERIFIED"
    verification_reason = "no W7 FINAL business verification SQL was provided"
    verification_sha = None
    if verification_sql:
        if not verification_sql.is_file():
            raise ContractError("verification SQL does not exist")
        _run(
            _mysql_command(mysql_binary, client_file, "--database", target_database),
            input_file=verification_sql,
        )
        verification_status = "PASS"
        verification_reason = "explicit verification SQL completed"
        verification_sha = sha256_file(verification_sql)
    elapsed_seconds = round(time.monotonic() - started, 3)
    return {
        "schema_version": "shenzhouhr.w9.mysql-restore/v1",
        "run_id": run_id,
        "target_database": target_database,
        "host": host,
        "port": port,
        "dump_sha256": manifest["dump_sha256"],
        "automated_restore_status": verification_status,
        "verification_reason": verification_reason,
        "verification_sql_sha256": verification_sha,
        "elapsed_seconds": elapsed_seconds,
        "target_retained_for_inspection": True,
        "drop_executed": False,
        "recovery_gate_status": "NOT_VERIFIED",
        "recovery_reason": (
            "human MySQL 8.4 PITR, business reconciliation, RPO and RTO drill is required"
        ),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Loopback-only W9 restore sandbox")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--run-id", default="w9-restore-plan")
    parser.add_argument("--target-database", default="shenzhou_hr_w9_restore")
    parser.add_argument("--confirm")
    parser.add_argument("--client-file", type=Path)
    parser.add_argument("--backup-manifest", type=Path)
    parser.add_argument("--dump", type=Path)
    parser.add_argument("--mysql-bin", default="mysql")
    parser.add_argument("--verification-sql", type=Path)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    try:
        if not args.execute:
            print(
                json.dumps(
                    restore_plan(args.run_id, args.target_database),
                    ensure_ascii=False,
                    indent=2,
                    sort_keys=True,
                )
            )
            return 0
        if not all(
            (
                args.confirm,
                args.client_file,
                args.backup_manifest,
                args.dump,
            )
        ):
            raise ContractError(
                "--execute requires --confirm, --client-file, --backup-manifest and --dump"
            )
        result = run_restore(
            repository_root=args.repo.resolve(),
            run_id=args.run_id,
            target_database=args.target_database,
            confirmation=args.confirm,
            client_file=args.client_file,
            backup_manifest_path=args.backup_manifest,
            dump_path=args.dump,
            mysql_binary=args.mysql_bin,
            verification_sql=args.verification_sql,
        )
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except (ContractError, OSError) as exc:
        print(f"W9_RESTORE_INVALID={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
