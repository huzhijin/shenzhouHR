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
    MySqlClientIdentity,
    mysql_connection_command,
    read_client_identity,
    require_executable,
    require_secure_artifact_file,
    validate_backup_source,
    validate_environment_id,
    validate_restore_target,
    validate_restricted_grants,
    validate_server_authority,
    validate_server_uuid,
    validate_sha256,
)

AUTHORITY_QUERY = (
    "SELECT VERSION(),@@server_uuid,"
    "(SELECT environment_id "
    "FROM shenzhou_release_control.environment_identity "
    "WHERE singleton_id=1)"
)
MAX_METADATA_SECONDS = 30
MAX_IMPORT_SECONDS = 4 * 60 * 60
MAX_VERIFICATION_SECONDS = 5 * 60
REQUIRED_BACKUP_MANIFEST_FIELDS = frozenset(
    {
        "schema_version",
        "run_id",
        "status",
        "rpo_gate_status",
        "database",
        "database_identity",
        "environment_id",
        "server_uuid",
        "mysql_version",
        "host",
        "port",
        "started_at_utc",
        "completed_at_utc",
        "gtid_executed_at_start",
        "gtid_executed_at_end",
        "binlog_file_at_start",
        "binlog_position_at_start",
        "binlog_file_at_end",
        "binlog_position_at_end",
        "binlog_format",
        "authority_check_count",
        "authority_verified_before_and_after",
        "dump_file",
        "dump_bytes",
        "dump_sha256",
        "credential_values_logged",
    }
)


def restore_plan(run_id: str, target_database: str) -> dict[str, Any]:
    validate_run_id(run_id)
    validate_restore_target(target_database)
    return {
        "schema_version": "shenzhouhr.w9.mysql-restore-plan/v2",
        "mode": "PLAN",
        "network_connections": 0,
        "run_id": run_id,
        "target_database": target_database,
        "required_confirmation_format": (
            "<target>:<run_id>:<backup_server_uuid>:<backup_environment_id>:"
            "<restore_server_uuid>:<restore_environment_id>:<dump_sha256>"
        ),
        "steps": [
            "verify secure backup manifest, complete provenance and out-of-band dump SHA-256",
            "verify approved non-production server_uuid and environment marker",
            "validate distinct admin, target-schema import and read-only verification accounts",
            "prove target sandbox does not exist",
            "create a new utf8mb4_0900_ai_ci *_restore database without DROP",
            "import with the target-schema-only account and binary client commands disabled",
            "run optional verification with a separate target-schema read-only account",
            "record elapsed time; retain sandbox for human inspection",
        ],
        "required_external_controls": [
            "pre-provisioned import account with grants only on the exact target schema",
            "pre-provisioned verification account with SELECT/SHOW VIEW only",
            "release-control environment marker maintained outside this script",
            "no concurrent privilege changes during the restore",
        ],
        "recovery_gate_status": "NOT_VERIFIED",
    }


def _run(
    command: list[str],
    *,
    input_file: Path | None = None,
    timeout_seconds: int = MAX_METADATA_SECONDS,
) -> str:
    input_handle = input_file.open("rb") if input_file else None
    try:
        process = subprocess.run(
            command,
            check=False,
            stdin=input_handle,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as exc:
        raise ContractError("MySQL restore command exceeded its safety timeout") from exc
    finally:
        if input_handle:
            input_handle.close()
    if process.returncode != 0:
        raise ContractError(f"MySQL restore command failed with exit {process.returncode}")
    return process.stdout.decode("utf-8", errors="replace").strip()


def _connection_authority(
    *,
    mysql_binary: str,
    client_file: Path,
    identity: MySqlClientIdentity,
    expected_server_uuid: str,
    expected_environment_id: str,
) -> tuple[str, str, str]:
    output = _run(
        mysql_connection_command(
            mysql_binary,
            client_file,
            identity,
            "--batch",
            "--skip-column-names",
            "--execute",
            AUTHORITY_QUERY,
        )
    )
    fields = output.split("\t")
    if len(fields) != 3:
        raise ContractError("unexpected MySQL authority metadata output")
    version, observed_server_uuid, observed_environment_id = fields
    server_uuid, environment_id = validate_server_authority(
        version=version,
        observed_server_uuid=observed_server_uuid,
        observed_environment_id=observed_environment_id,
        expected_server_uuid=expected_server_uuid,
        expected_environment_id=expected_environment_id,
    )
    return version, server_uuid, environment_id


def _require_same_authority_endpoint(
    primary: MySqlClientIdentity,
    restricted: MySqlClientIdentity,
    *,
    label: str,
) -> None:
    if (restricted.host, restricted.port) != (primary.host, primary.port):
        raise ContractError(f"{label} MySQL account must use the approved endpoint")
    if restricted.user == primary.user:
        raise ContractError(f"{label} MySQL account must differ from the admin account")


def _verify_account_server_uuid(
    *,
    mysql_binary: str,
    client_file: Path,
    identity: MySqlClientIdentity,
    expected_server_uuid: str,
) -> None:
    output = _run(
        mysql_connection_command(
            mysql_binary,
            client_file,
            identity,
            "--batch",
            "--skip-column-names",
            "--execute",
            "SELECT @@server_uuid",
        )
    )
    try:
        observed = validate_server_uuid(output)
    except ContractError as exc:
        raise ContractError("restricted MySQL account returned invalid server_uuid") from exc
    if observed != expected_server_uuid:
        raise ContractError("restricted MySQL account reached a different server_uuid")


def _verify_account_grants(
    *,
    mysql_binary: str,
    client_file: Path,
    identity: MySqlClientIdentity,
    target_database: str,
    mode: str,
) -> None:
    grants = _run(
        mysql_connection_command(
            mysql_binary,
            client_file,
            identity,
            "--batch",
            "--skip-column-names",
            "--execute",
            "SHOW GRANTS FOR CURRENT_USER()",
        )
    )
    validate_restricted_grants(
        grants,
        target_database=target_database,
        mode=mode,
    )


def _target_database_exists(
    *,
    mysql_binary: str,
    client_file: Path,
    identity: MySqlClientIdentity,
    target_database: str,
) -> bool:
    query = (
        "SELECT COUNT(*) FROM information_schema.schemata "
        f"WHERE schema_name='{target_database}'"
    )
    observed = _run(
        mysql_connection_command(
            mysql_binary,
            client_file,
            identity,
            "--batch",
            "--skip-column-names",
            "--execute",
            query,
        )
    )
    if observed not in {"0", "1"}:
        raise ContractError("restore target existence query returned an invalid result")
    return observed == "1"


def _validate_backup_manifest(
    manifest: dict[str, Any],
    *,
    run_id: str,
    expected_source_database: str,
    expected_backup_server_uuid: str,
    expected_backup_environment_id: str,
    expected_dump_sha256: str,
    dump_path: Path,
) -> None:
    missing = REQUIRED_BACKUP_MANIFEST_FIELDS - manifest.keys()
    if missing:
        raise ContractError(
            f"backup manifest is missing required fields: {sorted(missing)}"
        )
    if manifest.get("schema_version") != "shenzhouhr.w9.mysql-backup/v1":
        raise ContractError("unsupported backup manifest")
    if manifest.get("run_id") != run_id:
        raise ContractError("backup manifest run_id differs from restore run_id")
    if manifest.get("status") != "PASS":
        raise ContractError("backup manifest status must be PASS")
    if manifest.get("rpo_gate_status") not in {"PASS", "NOT_VERIFIED"}:
        raise ContractError("backup manifest has an invalid RPO status")
    source_database = validate_backup_source(str(manifest.get("database")))
    if source_database != expected_source_database:
        raise ContractError("backup source database differs from approved source")
    if manifest.get("server_uuid") != expected_backup_server_uuid:
        raise ContractError("backup manifest server_uuid differs from approved authority")
    if manifest.get("environment_id") != expected_backup_environment_id:
        raise ContractError(
            "backup manifest environment identity differs from approved authority"
        )
    if manifest.get("database_identity") != (
        f"mysql84:{expected_backup_server_uuid}:{source_database}"
    ):
        raise ContractError("backup manifest database identity is inconsistent")
    if not str(manifest.get("mysql_version", "")).startswith("8.4."):
        raise ContractError("backup manifest does not identify MySQL 8.4 LTS")
    if (
        not isinstance(manifest.get("host"), str)
        or not manifest["host"]
        or isinstance(manifest.get("port"), bool)
        or not isinstance(manifest.get("port"), int)
        or not 1 <= manifest["port"] <= 65535
    ):
        raise ContractError("backup manifest source endpoint is invalid")
    if manifest.get("binlog_format") != "ROW":
        raise ContractError("backup manifest binlog format must be ROW")
    if (
        manifest.get("authority_verified_before_and_after") is not True
        or isinstance(manifest.get("authority_check_count"), bool)
        or not isinstance(manifest.get("authority_check_count"), int)
        or manifest["authority_check_count"] < 3
    ):
        raise ContractError(
            "backup manifest does not prove before/after authority verification"
        )
    for name in (
        "started_at_utc",
        "completed_at_utc",
        "gtid_executed_at_start",
        "gtid_executed_at_end",
        "binlog_file_at_start",
        "binlog_position_at_start",
        "binlog_file_at_end",
        "binlog_position_at_end",
    ):
        if not isinstance(manifest.get(name), str) or not manifest[name]:
            raise ContractError(f"backup manifest field {name} must be non-empty")
    if manifest.get("dump_file") != dump_path.name:
        raise ContractError("backup manifest dump filename does not match supplied dump")
    dump_bytes = manifest.get("dump_bytes")
    if (
        isinstance(dump_bytes, bool)
        or not isinstance(dump_bytes, int)
        or dump_bytes <= 0
        or dump_bytes != dump_path.stat().st_size
    ):
        raise ContractError("backup manifest dump size does not match supplied dump")
    manifest_sha = validate_sha256(
        manifest.get("dump_sha256"), "backup manifest dump digest"
    )
    if manifest_sha != expected_dump_sha256:
        raise ContractError("backup manifest digest differs from approved dump digest")
    if manifest.get("credential_values_logged") is not False:
        raise ContractError("backup manifest does not prove credential-log hygiene")


def _confirmation_token(
    *,
    target_database: str,
    run_id: str,
    expected_backup_server_uuid: str,
    expected_backup_environment_id: str,
    expected_restore_server_uuid: str,
    expected_restore_environment_id: str,
    expected_dump_sha256: str,
) -> str:
    return ":".join(
        (
            target_database,
            run_id,
            expected_backup_server_uuid,
            expected_backup_environment_id,
            expected_restore_server_uuid,
            expected_restore_environment_id,
            expected_dump_sha256,
        )
    )


def run_restore(
    *,
    repository_root: Path,
    run_id: str,
    target_database: str,
    confirmation: str,
    client_file: Path,
    import_client_file: Path,
    verification_client_file: Path | None,
    backup_manifest_path: Path,
    dump_path: Path,
    mysql_binary: str,
    verification_sql: Path | None,
    verification_sql_sha256: str | None,
    expected_source_database: str,
    expected_dump_sha256: str,
    expected_backup_server_uuid: str,
    expected_backup_environment_id: str,
    expected_restore_server_uuid: str,
    expected_restore_environment_id: str,
) -> dict[str, Any]:
    validate_run_id(run_id)
    validate_restore_target(target_database)
    expected_source_database = validate_backup_source(expected_source_database)
    expected_dump_sha256 = validate_sha256(
        expected_dump_sha256, "approved dump digest"
    )
    expected_backup_server_uuid = validate_server_uuid(
        expected_backup_server_uuid
    )
    expected_backup_environment_id = validate_environment_id(
        expected_backup_environment_id
    )
    expected_restore_server_uuid = validate_server_uuid(
        expected_restore_server_uuid
    )
    expected_restore_environment_id = validate_environment_id(
        expected_restore_environment_id
    )
    expected_confirmation = _confirmation_token(
        target_database=target_database,
        run_id=run_id,
        expected_backup_server_uuid=expected_backup_server_uuid,
        expected_backup_environment_id=expected_backup_environment_id,
        expected_restore_server_uuid=expected_restore_server_uuid,
        expected_restore_environment_id=expected_restore_environment_id,
        expected_dump_sha256=expected_dump_sha256,
    )
    if confirmation != expected_confirmation:
        raise ContractError("restore confirmation token does not match approved authority")
    if (verification_sql is None) != (verification_client_file is None):
        raise ContractError(
            "verification SQL and read-only verification client file are required together"
        )
    if (verification_sql is None) != (verification_sql_sha256 is None):
        raise ContractError(
            "verification SQL and its approved SHA-256 are required together"
        )

    admin_identity = read_client_identity(client_file, repository_root)
    import_identity = read_client_identity(import_client_file, repository_root)
    _require_same_authority_endpoint(
        admin_identity,
        import_identity,
        label="import",
    )
    verification_identity: MySqlClientIdentity | None = None
    if verification_client_file is not None:
        verification_identity = read_client_identity(
            verification_client_file, repository_root
        )
        _require_same_authority_endpoint(
            admin_identity,
            verification_identity,
            label="verification",
        )
        if verification_identity.user == import_identity.user:
            raise ContractError(
                "verification MySQL account must differ from the import account"
            )

    mysql_binary = require_executable(mysql_binary)
    manifest_path = require_secure_artifact_file(
        backup_manifest_path,
        repository_root,
        label="backup manifest",
    )
    secure_dump_path = require_secure_artifact_file(
        dump_path,
        repository_root,
        label="backup dump",
    )
    if manifest_path.parent != secure_dump_path.parent:
        raise ContractError("backup manifest and dump must share one secure directory")
    manifest = read_json(manifest_path)
    _validate_backup_manifest(
        manifest,
        run_id=run_id,
        expected_source_database=expected_source_database,
        expected_backup_server_uuid=expected_backup_server_uuid,
        expected_backup_environment_id=expected_backup_environment_id,
        expected_dump_sha256=expected_dump_sha256,
        dump_path=secure_dump_path,
    )
    if sha256_file(secure_dump_path) != expected_dump_sha256:
        raise ContractError("backup dump SHA-256 does not match approved digest")

    secure_verification_sql: Path | None = None
    if verification_sql is not None:
        secure_verification_sql = require_secure_artifact_file(
            verification_sql,
            repository_root,
            label="verification SQL",
        )
        approved_verification_sha = validate_sha256(
            verification_sql_sha256,
            "approved verification SQL digest",
        )
        if sha256_file(secure_verification_sql) != approved_verification_sha:
            raise ContractError("verification SQL SHA-256 does not match approved digest")

    version, server_uuid, environment_id = _connection_authority(
        mysql_binary=mysql_binary,
        client_file=client_file,
        identity=admin_identity,
        expected_server_uuid=expected_restore_server_uuid,
        expected_environment_id=expected_restore_environment_id,
    )
    _verify_account_server_uuid(
        mysql_binary=mysql_binary,
        client_file=import_client_file,
        identity=import_identity,
        expected_server_uuid=expected_restore_server_uuid,
    )
    _verify_account_grants(
        mysql_binary=mysql_binary,
        client_file=import_client_file,
        identity=import_identity,
        target_database=target_database,
        mode="import",
    )
    if verification_identity is not None and verification_client_file is not None:
        _verify_account_server_uuid(
            mysql_binary=mysql_binary,
            client_file=verification_client_file,
            identity=verification_identity,
            expected_server_uuid=expected_restore_server_uuid,
        )
        _verify_account_grants(
            mysql_binary=mysql_binary,
            client_file=verification_client_file,
            identity=verification_identity,
            target_database=target_database,
            mode="read-only",
        )

    if _target_database_exists(
        mysql_binary=mysql_binary,
        client_file=client_file,
        identity=admin_identity,
        target_database=target_database,
    ):
        raise ContractError("restore target already exists; the tool never drops databases")

    version, server_uuid, environment_id = _connection_authority(
        mysql_binary=mysql_binary,
        client_file=client_file,
        identity=admin_identity,
        expected_server_uuid=expected_restore_server_uuid,
        expected_environment_id=expected_restore_environment_id,
    )
    started = time.monotonic()
    _run(
        mysql_connection_command(
            mysql_binary,
            client_file,
            admin_identity,
            "--execute",
            (
                f"CREATE DATABASE `{target_database}` CHARACTER SET utf8mb4 "
                "COLLATE utf8mb4_0900_ai_ci"
            ),
        )
    )
    authority_check_count = 2
    try:
        version, server_uuid, environment_id = _connection_authority(
            mysql_binary=mysql_binary,
            client_file=client_file,
            identity=admin_identity,
            expected_server_uuid=expected_restore_server_uuid,
            expected_environment_id=expected_restore_environment_id,
        )
        authority_check_count += 1
        if not _target_database_exists(
            mysql_binary=mysql_binary,
            client_file=client_file,
            identity=admin_identity,
            target_database=target_database,
        ):
            raise ContractError(
                "created restore target is absent from the approved authority"
            )
        _verify_account_server_uuid(
            mysql_binary=mysql_binary,
            client_file=import_client_file,
            identity=import_identity,
            expected_server_uuid=expected_restore_server_uuid,
        )
        authority_check_count += 1
        _verify_account_grants(
            mysql_binary=mysql_binary,
            client_file=import_client_file,
            identity=import_identity,
            target_database=target_database,
            mode="import",
        )
        _run(
            mysql_connection_command(
                mysql_binary,
                import_client_file,
                import_identity,
                "--binary-mode=1",
                "--skip-auto-rehash",
                "--database",
                target_database,
            ),
            input_file=secure_dump_path,
            timeout_seconds=MAX_IMPORT_SECONDS,
        )
        _verify_account_server_uuid(
            mysql_binary=mysql_binary,
            client_file=import_client_file,
            identity=import_identity,
            expected_server_uuid=expected_restore_server_uuid,
        )
        authority_check_count += 1
        version, server_uuid, environment_id = _connection_authority(
            mysql_binary=mysql_binary,
            client_file=client_file,
            identity=admin_identity,
            expected_server_uuid=expected_restore_server_uuid,
            expected_environment_id=expected_restore_environment_id,
        )
        authority_check_count += 1
        if not _target_database_exists(
            mysql_binary=mysql_binary,
            client_file=client_file,
            identity=admin_identity,
            target_database=target_database,
        ):
            raise ContractError(
                "restored target is absent from the approved authority"
            )

        verification_status = "NOT_VERIFIED"
        verification_reason = (
            "no approved read-only business verification SQL was provided"
        )
        verification_sha = None
        if (
            secure_verification_sql is not None
            and verification_identity is not None
            and verification_client_file is not None
        ):
            _verify_account_server_uuid(
                mysql_binary=mysql_binary,
                client_file=verification_client_file,
                identity=verification_identity,
                expected_server_uuid=expected_restore_server_uuid,
            )
            authority_check_count += 1
            _verify_account_grants(
                mysql_binary=mysql_binary,
                client_file=verification_client_file,
                identity=verification_identity,
                target_database=target_database,
                mode="read-only",
            )
            _run(
                mysql_connection_command(
                    mysql_binary,
                    verification_client_file,
                    verification_identity,
                    "--binary-mode=1",
                    "--skip-auto-rehash",
                    "--database",
                    target_database,
                ),
                input_file=secure_verification_sql,
                timeout_seconds=MAX_VERIFICATION_SECONDS,
            )
            _verify_account_server_uuid(
                mysql_binary=mysql_binary,
                client_file=verification_client_file,
                identity=verification_identity,
                expected_server_uuid=expected_restore_server_uuid,
            )
            authority_check_count += 1
            verification_status = "PASS"
            verification_reason = (
                "approved verification SQL completed through the target-schema "
                "read-only account"
            )
            verification_sha = sha256_file(secure_verification_sql)
        version, server_uuid, environment_id = _connection_authority(
            mysql_binary=mysql_binary,
            client_file=client_file,
            identity=admin_identity,
            expected_server_uuid=expected_restore_server_uuid,
            expected_environment_id=expected_restore_environment_id,
        )
        authority_check_count += 1
    except Exception as exc:
        raise ContractError(
            "restore did not complete; the newly created sandbox may remain for "
            f"manual inspection and must not be reused automatically: {target_database}"
        ) from exc
    elapsed_seconds = round(time.monotonic() - started, 3)
    return {
        "schema_version": "shenzhouhr.w9.mysql-restore/v2",
        "run_id": run_id,
        "target_database": target_database,
        "source_database": expected_source_database,
        "source_environment_id": expected_backup_environment_id,
        "source_server_uuid": expected_backup_server_uuid,
        "restore_environment_id": environment_id,
        "restore_server_uuid": server_uuid,
        "mysql_version": version,
        "host": admin_identity.host,
        "port": admin_identity.port,
        "dump_sha256": expected_dump_sha256,
        "automated_restore_status": verification_status,
        "verification_reason": verification_reason,
        "verification_sql_sha256": verification_sha,
        "elapsed_seconds": elapsed_seconds,
        "target_retained_for_inspection": True,
        "residual_state": "COMPLETE_SANDBOX_RETAINED",
        "drop_executed": False,
        "admin_used_for_import": False,
        "verification_account_read_only": verification_status == "PASS",
        "authority_check_count": authority_check_count,
        "authority_verified_before_and_after": True,
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
    parser.add_argument("--import-client-file", type=Path)
    parser.add_argument("--verification-client-file", type=Path)
    parser.add_argument("--backup-manifest", type=Path)
    parser.add_argument("--dump", type=Path)
    parser.add_argument("--mysql-bin", default="/usr/bin/mysql")
    parser.add_argument("--verification-sql", type=Path)
    parser.add_argument("--verification-sql-sha256")
    parser.add_argument("--expected-source-database")
    parser.add_argument("--expected-dump-sha256")
    parser.add_argument("--expected-backup-server-uuid")
    parser.add_argument("--expected-backup-environment-id")
    parser.add_argument("--expected-restore-server-uuid")
    parser.add_argument("--expected-restore-environment-id")
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
                args.import_client_file,
                args.backup_manifest,
                args.dump,
                args.expected_source_database,
                args.expected_dump_sha256,
                args.expected_backup_server_uuid,
                args.expected_backup_environment_id,
                args.expected_restore_server_uuid,
                args.expected_restore_environment_id,
            )
        ):
            raise ContractError(
                "--execute requires --confirm, --client-file, --import-client-file, "
                "--backup-manifest, --dump, --expected-source-database, "
                "--expected-dump-sha256, --expected-backup-server-uuid, "
                "--expected-backup-environment-id, --expected-restore-server-uuid "
                "and --expected-restore-environment-id"
            )
        result = run_restore(
            repository_root=args.repo.resolve(),
            run_id=args.run_id,
            target_database=args.target_database,
            confirmation=args.confirm,
            client_file=args.client_file,
            import_client_file=args.import_client_file,
            verification_client_file=args.verification_client_file,
            backup_manifest_path=args.backup_manifest,
            dump_path=args.dump,
            mysql_binary=args.mysql_bin,
            verification_sql=args.verification_sql,
            verification_sql_sha256=args.verification_sql_sha256,
            expected_source_database=args.expected_source_database,
            expected_dump_sha256=args.expected_dump_sha256,
            expected_backup_server_uuid=args.expected_backup_server_uuid,
            expected_backup_environment_id=args.expected_backup_environment_id,
            expected_restore_server_uuid=args.expected_restore_server_uuid,
            expected_restore_environment_id=args.expected_restore_environment_id,
        )
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except (ContractError, OSError) as exc:
        print(f"W9_RESTORE_INVALID={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
