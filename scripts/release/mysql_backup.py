#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.release.common import (  # noqa: E402
    ContractError,
    sha256_file,
    validate_run_id,
    write_json,
)
from scripts.release.mysql_safety import (  # noqa: E402
    prepare_output_directory,
    read_client_identity,
    require_executable,
    validate_backup_source,
)


def backup_plan(run_id: str, database: str) -> dict[str, Any]:
    validate_run_id(run_id)
    validate_backup_source(database)
    return {
        "schema_version": "shenzhouhr.w9.mysql-backup-plan/v1",
        "mode": "PLAN",
        "network_connections": 0,
        "database": database,
        "run_id": run_id,
        "steps": [
            "validate outside-repository 0600 MySQL client option file",
            "verify loopback MySQL 8.4 LTS identity, GTID and ROW binlog",
            "capture binary log start position",
            "create single-transaction logical dump without CREATE DATABASE",
            "write dump SHA-256 and provenance manifest",
        ],
        "rpo_gate_status": "NOT_VERIFIED",
    }


def _mysql_command(binary: str, client_file: Path, *args: str) -> list[str]:
    return [binary, f"--defaults-extra-file={client_file}", *args]


def _run_text(command: list[str]) -> str:
    process = subprocess.run(
        command,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if process.returncode != 0:
        raise ContractError(
            f"MySQL metadata command failed with exit {process.returncode}"
        )
    return process.stdout.strip()


def run_backup(
    *,
    repository_root: Path,
    run_id: str,
    database: str,
    client_file: Path,
    output_directory: Path,
    mysql_binary: str,
    mysqldump_binary: str,
) -> dict[str, Any]:
    validate_run_id(run_id)
    validate_backup_source(database)
    host, port = read_client_identity(client_file, repository_root)
    mysql_binary = require_executable(mysql_binary)
    mysqldump_binary = require_executable(mysqldump_binary)
    output_directory = prepare_output_directory(output_directory, repository_root)
    started_at = datetime.now(UTC)

    metadata_output = _run_text(
        _mysql_command(
            mysql_binary,
            client_file,
            "--batch",
            "--skip-column-names",
            "--execute",
            (
                "SELECT VERSION(),@@server_uuid,@@global.gtid_executed,"
                "@@global.log_bin,@@global.binlog_format"
            ),
        )
    )
    fields = metadata_output.split("\t")
    if len(fields) != 5:
        raise ContractError("unexpected MySQL metadata output")
    version, server_uuid, gtid_executed, log_bin, binlog_format = fields
    if not version.startswith("8.4."):
        raise ContractError(f"MySQL 8.4 LTS required, observed {version}")
    if log_bin != "1" or binlog_format.upper() != "ROW":
        raise ContractError("PITR requires log_bin=1 and binlog_format=ROW")

    binlog_output = _run_text(
        _mysql_command(
            mysql_binary,
            client_file,
            "--batch",
            "--skip-column-names",
            "--execute",
            "SHOW BINARY LOG STATUS",
        )
    )
    binlog_fields = binlog_output.split("\t")
    if len(binlog_fields) < 2:
        raise ContractError("binary log status did not include file and position")

    dump_path = output_directory / f"{run_id}-{database}.sql"
    partial_path = output_directory / f".{dump_path.name}.partial"
    command = [
        mysqldump_binary,
        f"--defaults-extra-file={client_file}",
        f"--host={host}",
        f"--port={port}",
        "--single-transaction",
        "--quick",
        "--routines",
        "--events",
        "--triggers",
        "--hex-blob",
        "--set-gtid-purged=COMMENTED",
        "--source-data=2",
        "--skip-comments",
        database,
    ]
    with partial_path.open("wb") as output:
        process = subprocess.run(
            command,
            check=False,
            stdout=output,
            stderr=subprocess.PIPE,
        )
    if process.returncode != 0:
        raise ContractError(f"mysqldump failed with exit {process.returncode}")
    if partial_path.stat().st_size == 0:
        raise ContractError("mysqldump produced an empty artifact")
    os.replace(partial_path, dump_path)
    os.chmod(dump_path, 0o600)
    completed_at = datetime.now(UTC)
    manifest = {
        "schema_version": "shenzhouhr.w9.mysql-backup/v1",
        "run_id": run_id,
        "status": "PASS",
        "rpo_gate_status": "NOT_VERIFIED",
        "rpo_reason": (
            "one backup and start position do not prove continuous binlog retention "
            "or a <=15 minute recovery point"
        ),
        "database": database,
        "database_identity": f"mysql84:{server_uuid}:{database}",
        "mysql_version": version,
        "host": host,
        "port": port,
        "started_at_utc": started_at.isoformat(),
        "completed_at_utc": completed_at.isoformat(),
        "gtid_executed_at_start": gtid_executed,
        "binlog_file_at_start": binlog_fields[0],
        "binlog_position_at_start": binlog_fields[1],
        "binlog_format": binlog_format,
        "dump_file": dump_path.name,
        "dump_bytes": dump_path.stat().st_size,
        "dump_sha256": sha256_file(dump_path),
        "credential_values_logged": False,
    }
    manifest_path = output_directory / f"{run_id}-backup-manifest.json"
    write_json(manifest_path, manifest)
    os.chmod(manifest_path, 0o600)
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Loopback-only W9 MySQL backup")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--run-id", default="w9-backup-plan")
    parser.add_argument("--database", default="shenzhou_hr_test")
    parser.add_argument("--client-file", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--mysql-bin", default="mysql")
    parser.add_argument("--mysqldump-bin", default="mysqldump")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    try:
        if not args.execute:
            print(
                json.dumps(
                    backup_plan(args.run_id, args.database),
                    ensure_ascii=False,
                    indent=2,
                    sort_keys=True,
                )
            )
            return 0
        if not args.client_file or not args.output_dir:
            raise ContractError("--execute requires --client-file and --output-dir")
        manifest = run_backup(
            repository_root=args.repo.resolve(),
            run_id=args.run_id,
            database=args.database,
            client_file=args.client_file,
            output_directory=args.output_dir,
            mysql_binary=args.mysql_bin,
            mysqldump_binary=args.mysqldump_bin,
        )
        print(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except (ContractError, OSError) as exc:
        print(f"W9_BACKUP_INVALID={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
