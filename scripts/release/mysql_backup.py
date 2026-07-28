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
)
from scripts.release.mysql_safety import (  # noqa: E402
    MySqlClientIdentity,
    mysql_connection_command,
    open_exclusive_secure_binary,
    prepare_output_directory,
    publish_exclusive,
    read_client_identity,
    require_executable,
    validate_backup_source,
    validate_server_authority,
    write_json_exclusive,
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
            "verify approved non-production server_uuid and environment marker",
            "verify loopback MySQL 8.4 LTS, GTID and ROW binlog",
            "capture binary log start position",
            "create single-transaction logical dump without CREATE DATABASE",
            "create 0600 artifacts exclusively and write an atomic provenance manifest",
        ],
        "required_execution_parameters": [
            "expected_server_uuid",
            "expected_environment_id",
        ],
        "rpo_gate_status": "NOT_VERIFIED",
    }


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


def _read_authority(
    *,
    mysql_binary: str,
    client_file: Path,
    identity: MySqlClientIdentity,
    expected_server_uuid: str,
    expected_environment_id: str,
) -> dict[str, str]:
    metadata_output = _run_text(
        mysql_connection_command(
            mysql_binary,
            client_file,
            identity,
            "--batch",
            "--skip-column-names",
            "--execute",
            (
                "SELECT VERSION(),@@server_uuid,"
                "(SELECT environment_id "
                "FROM shenzhou_release_control.environment_identity "
                "WHERE singleton_id=1),@@global.gtid_executed,"
                "@@global.log_bin,@@global.binlog_format"
            ),
        )
    )
    fields = metadata_output.split("\t")
    if len(fields) != 6:
        raise ContractError("unexpected MySQL metadata output")
    (
        version,
        observed_server_uuid,
        observed_environment_id,
        gtid_executed,
        log_bin,
        binlog_format,
    ) = fields
    server_uuid, environment_id = validate_server_authority(
        version=version,
        observed_server_uuid=observed_server_uuid,
        observed_environment_id=observed_environment_id,
        expected_server_uuid=expected_server_uuid,
        expected_environment_id=expected_environment_id,
    )
    if log_bin != "1" or binlog_format.upper() != "ROW":
        raise ContractError("PITR requires log_bin=1 and binlog_format=ROW")
    return {
        "version": version,
        "server_uuid": server_uuid,
        "environment_id": environment_id,
        "gtid_executed": gtid_executed,
        "binlog_format": binlog_format.upper(),
    }


def _read_binlog_status(
    *,
    mysql_binary: str,
    client_file: Path,
    identity: MySqlClientIdentity,
) -> tuple[str, str]:
    binlog_output = _run_text(
        mysql_connection_command(
            mysql_binary,
            client_file,
            identity,
            "--batch",
            "--skip-column-names",
            "--execute",
            "SHOW BINARY LOG STATUS",
        )
    )
    binlog_fields = binlog_output.split("\t")
    if len(binlog_fields) < 2 or not binlog_fields[0] or not binlog_fields[1]:
        raise ContractError("binary log status did not include file and position")
    return binlog_fields[0], binlog_fields[1]


def run_backup(
    *,
    repository_root: Path,
    run_id: str,
    database: str,
    client_file: Path,
    output_directory: Path,
    mysql_binary: str,
    mysqldump_binary: str,
    expected_server_uuid: str,
    expected_environment_id: str,
) -> dict[str, Any]:
    validate_run_id(run_id)
    validate_backup_source(database)
    identity = read_client_identity(client_file, repository_root)
    mysql_binary = require_executable(mysql_binary)
    mysqldump_binary = require_executable(mysqldump_binary)
    output_directory = prepare_output_directory(output_directory, repository_root)
    dump_path = output_directory / f"{run_id}-{database}.sql"
    partial_path = output_directory / f".{dump_path.name}.partial"
    manifest_path = output_directory / f"{run_id}-backup-manifest.json"
    manifest_partial_path = output_directory / f".{manifest_path.name}.partial"
    for candidate in (
        dump_path,
        partial_path,
        manifest_path,
        manifest_partial_path,
    ):
        if candidate.exists() or candidate.is_symlink():
            raise ContractError(
                f"refusing to reuse an existing backup artifact: {candidate.name}"
            )
    started_at = datetime.now(UTC)

    initial_authority = _read_authority(
        mysql_binary=mysql_binary,
        client_file=client_file,
        identity=identity,
        expected_server_uuid=expected_server_uuid,
        expected_environment_id=expected_environment_id,
    )
    binlog_file_at_start, binlog_position_at_start = _read_binlog_status(
        mysql_binary=mysql_binary,
        client_file=client_file,
        identity=identity,
    )
    pre_dump_authority = _read_authority(
        mysql_binary=mysql_binary,
        client_file=client_file,
        identity=identity,
        expected_server_uuid=expected_server_uuid,
        expected_environment_id=expected_environment_id,
    )
    if pre_dump_authority["version"] != initial_authority["version"]:
        raise ContractError("MySQL authority changed before mysqldump")

    command = mysql_connection_command(
        mysqldump_binary,
        client_file,
        identity,
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
    )
    dump_published = False
    try:
        with open_exclusive_secure_binary(partial_path) as output:
            process = subprocess.run(
                command,
                check=False,
                stdout=output,
                stderr=subprocess.PIPE,
            )
            output.flush()
            os.fsync(output.fileno())
        if process.returncode != 0:
            raise ContractError(f"mysqldump failed with exit {process.returncode}")
        if partial_path.stat().st_size == 0:
            raise ContractError("mysqldump produced an empty artifact")
        post_dump_authority = _read_authority(
            mysql_binary=mysql_binary,
            client_file=client_file,
            identity=identity,
            expected_server_uuid=expected_server_uuid,
            expected_environment_id=expected_environment_id,
        )
        if post_dump_authority["version"] != pre_dump_authority["version"]:
            raise ContractError("MySQL authority changed during mysqldump")
        binlog_file_at_end, binlog_position_at_end = _read_binlog_status(
            mysql_binary=mysql_binary,
            client_file=client_file,
            identity=identity,
        )
        publish_exclusive(partial_path, dump_path)
        dump_published = True
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
            "database_identity": (
                f"mysql84:{post_dump_authority['server_uuid']}:{database}"
            ),
            "environment_id": post_dump_authority["environment_id"],
            "server_uuid": post_dump_authority["server_uuid"],
            "mysql_version": post_dump_authority["version"],
            "host": identity.host,
            "port": identity.port,
            "started_at_utc": started_at.isoformat(),
            "completed_at_utc": completed_at.isoformat(),
            "gtid_executed_at_start": initial_authority["gtid_executed"],
            "gtid_executed_at_end": post_dump_authority["gtid_executed"],
            "binlog_file_at_start": binlog_file_at_start,
            "binlog_position_at_start": binlog_position_at_start,
            "binlog_file_at_end": binlog_file_at_end,
            "binlog_position_at_end": binlog_position_at_end,
            "binlog_format": post_dump_authority["binlog_format"],
            "authority_check_count": 3,
            "authority_verified_before_and_after": True,
            "dump_file": dump_path.name,
            "dump_bytes": dump_path.stat().st_size,
            "dump_sha256": sha256_file(dump_path),
            "credential_values_logged": False,
        }
        write_json_exclusive(manifest_path, manifest)
    except BaseException:
        partial_path.unlink(missing_ok=True)
        if dump_published:
            dump_path.unlink(missing_ok=True)
        raise
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Loopback-only W9 MySQL backup")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--run-id", default="w9-backup-plan")
    parser.add_argument("--database", default="shenzhou_hr_test")
    parser.add_argument("--client-file", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--mysql-bin", default="/usr/bin/mysql")
    parser.add_argument("--mysqldump-bin", default="/usr/bin/mysqldump")
    parser.add_argument("--expected-server-uuid")
    parser.add_argument("--expected-environment-id")
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
        if not all(
            (
                args.client_file,
                args.output_dir,
                args.expected_server_uuid,
                args.expected_environment_id,
            )
        ):
            raise ContractError(
                "--execute requires --client-file, --output-dir, "
                "--expected-server-uuid and --expected-environment-id"
            )
        manifest = run_backup(
            repository_root=args.repo.resolve(),
            run_id=args.run_id,
            database=args.database,
            client_file=args.client_file,
            output_directory=args.output_dir,
            mysql_binary=args.mysql_bin,
            mysqldump_binary=args.mysqldump_bin,
            expected_server_uuid=args.expected_server_uuid,
            expected_environment_id=args.expected_environment_id,
        )
        print(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except (ContractError, OSError) as exc:
        print(f"W9_BACKUP_INVALID={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
