#!/usr/bin/env python3
"""Capture deterministic W2/W3 semantic snapshots from the bound MySQL gate.

This helper is intentionally not a server lifecycle tool.  The reviewed
``wave3-local-mysql.sh`` harness invokes it only after proving the isolated
MySQL 8.4.10 identity and after reaching an explicit Flyway boundary.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

try:
    from retained_canonicalizer import canonical_rows, load_registry
    from verify_w2_public_contract_v2 import (
        extract_table_block,
        normalize_sql_fragment,
        parse_column,
        split_sql_items,
        strip_sql_comments,
    )
except ImportError as error:  # pragma: no cover - import failure is fail-closed
    raise SystemExit(f"W3_MYSQL_SEMANTIC_CAPTURE=FAIL import: {error}") from error


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
ISOLATED_MYSQL_CLIENT = Path(
    "/Users/huzhijin/.local/share/shenzhouhr/"
    "mysql-8.4.10-isolated/install/bin/mysql"
)
RUNS_ROOT = REPOSITORY_ROOT / "docs/verification/wave3/runs"
W2_REGISTRY = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w2-retained-registry-v1.json"
)
W2_REGISTRY_SHA256 = (
    "1c83b61186c907eb020e26ef213a503f8f4fd9116edad6fef823672445fa0b06"
)
SEED_ORACLE = (
    REPOSITORY_ROOT
    / "openspec/changes/wave3-attendance-setup-and-policies/specs/"
    "wave3-verification/oracles/w3-seed-oracle-v1.json"
)
SEED_ORACLE_SHA256 = (
    "2f74955cf1bf8ac83a743f63666187a03e549e0fba5ebb120d61c1067dacea6d"
)
V4_MIGRATION = (
    REPOSITORY_ROOT
    / "backend/src/main/resources/db/migration/"
    "V4__versioned_policy_foundation.sql"
)
V4_SHA256 = "22b7f4b4b5ad21beec719c09aade3fc50b41a64d958951a690328ea38d2402a8"
DATABASE = "shenzhou_hr_test"
ALLOWED_PHASES = {
    "v6-before",
    "target7",
    "latest-after",
    "repeat-after",
    "empty-latest",
}
W2_POLICY_TABLES = (
    "policy_template",
    "policy_version",
    "policy_scope_binding",
    "policy_publication_record",
    "policy_rollback_record",
)
W2_RETAINED_TABLES = (*W2_POLICY_TABLES, "audit_event")
EXPECTED_SEED_KINDS = (
    "MEAL_DEDUCTION",
    "LATE_GRACE",
    "MONTHLY_LATE_EXEMPTION",
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class CaptureError(RuntimeError):
    """Expected fail-closed capture error."""


def fail(message: str) -> None:
    raise CaptureError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


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


@dataclass(frozen=True)
class DirectoryIdentity:
    device: int
    inode: int
    file_type: int


@dataclass
class SecureCaptureOutput:
    path: Path
    root_fd: int
    directory_fd: int
    relative_parts: tuple[str, ...]
    root_identities: tuple[DirectoryIdentity, ...]
    identities: tuple[DirectoryIdentity, ...]

    def close(self) -> None:
        os.close(self.directory_fd)
        os.close(self.root_fd)


def directory_identity(metadata: os.stat_result) -> DirectoryIdentity:
    return DirectoryIdentity(
        metadata.st_dev,
        metadata.st_ino,
        stat.S_IFMT(metadata.st_mode),
    )


def directory_flags() -> int:
    flags = os.O_RDONLY
    if hasattr(os, "O_DIRECTORY"):
        flags |= os.O_DIRECTORY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    return flags


def open_absolute_directory_no_symlinks(
    path: Path,
    label: str,
) -> tuple[int, tuple[DirectoryIdentity, ...]]:
    if not path.is_absolute():
        fail(f"{label} must be absolute")
    descriptor = os.open("/", directory_flags())
    identities: list[DirectoryIdentity] = []
    try:
        for part in path.parts[1:]:
            before = os.stat(part, dir_fd=descriptor, follow_symlinks=False)
            if not stat.S_ISDIR(before.st_mode) or stat.S_ISLNK(before.st_mode):
                fail(f"{label} traverses a symbolic/non-directory ancestor")
            child = os.open(part, directory_flags(), dir_fd=descriptor)
            identity = directory_identity(os.fstat(child))
            if identity != directory_identity(before):
                os.close(child)
                fail(f"{label} ancestor identity changed while opening")
            os.close(descriptor)
            descriptor = child
            identities.append(identity)
        return descriptor, tuple(identities)
    except BaseException:
        os.close(descriptor)
        raise


def open_directory_chain_at(
    root_fd: int,
    parts: Sequence[str],
    label: str,
    *,
    expected: tuple[DirectoryIdentity, ...] | None = None,
) -> tuple[int, tuple[DirectoryIdentity, ...]]:
    if any(part in {"", ".", ".."} or "/" in part for part in parts):
        fail(f"{label} contains an unsafe path component")
    descriptor = os.dup(root_fd)
    identities: list[DirectoryIdentity] = []
    try:
        for index, part in enumerate(parts):
            before = os.stat(part, dir_fd=descriptor, follow_symlinks=False)
            if not stat.S_ISDIR(before.st_mode) or stat.S_ISLNK(before.st_mode):
                fail(f"{label} traverses a symbolic/non-directory component")
            child = os.open(part, directory_flags(), dir_fd=descriptor)
            identity = directory_identity(os.fstat(child))
            if identity != directory_identity(before):
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
            fail(f"{label} depth changed")
        return descriptor, tuple(identities)
    except BaseException:
        os.close(descriptor)
        raise


def open_secure_capture_output(path: Path) -> SecureCaptureOutput:
    if not path.is_absolute():
        fail("output directory must be absolute")
    try:
        relative = path.relative_to(RUNS_ROOT)
    except ValueError:
        fail("output directory escapes the W3 run root")
    if (
        not relative.parts
        or any(part in {"", ".", ".."} for part in relative.parts)
    ):
        fail("output directory is not a safe current-run descendant")
    root_fd, root_chain = open_absolute_directory_no_symlinks(
        RUNS_ROOT, "W3 runs root"
    )
    if not root_chain:
        os.close(root_fd)
        fail("W3 runs root identity is unavailable")
    try:
        output_fd, identities = open_directory_chain_at(
            root_fd,
            relative.parts,
            "capture output directory",
        )
    except BaseException:
        os.close(root_fd)
        raise
    return SecureCaptureOutput(
        path=path,
        root_fd=root_fd,
        directory_fd=output_fd,
        relative_parts=tuple(relative.parts),
        root_identities=root_chain,
        identities=identities,
    )


def verify_capture_output_identity(output: SecureCaptureOutput) -> None:
    current_root_fd, current_root_chain = (
        open_absolute_directory_no_symlinks(RUNS_ROOT, "W3 runs root")
    )
    try:
        if (
            current_root_chain != output.root_identities
            or directory_identity(os.fstat(current_root_fd))
            != directory_identity(os.fstat(output.root_fd))
        ):
            fail("W3 runs root identity changed")
        descriptor, _ = open_directory_chain_at(
            current_root_fd,
            output.relative_parts,
            "capture output directory",
            expected=output.identities,
        )
        try:
            if directory_identity(os.fstat(descriptor)) != directory_identity(
                os.fstat(output.directory_fd)
            ):
                fail("capture output directory identity changed")
        finally:
            os.close(descriptor)
    finally:
        os.close(current_root_fd)


def load_fixed_w2_registry() -> dict[str, Any]:
    if sha256_file(W2_REGISTRY) != W2_REGISTRY_SHA256:
        fail("fixed W2 retained registry SHA256 drifted")
    try:
        document = json.loads(W2_REGISTRY.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"fixed W2 retained registry is invalid: {error}")
    if (
        not isinstance(document, dict)
        or document.get("version") != 1
        or [value.get("table") for value in document.get("tables", [])]
        != list(W2_RETAINED_TABLES)
    ):
        fail("fixed W2 retained registry table closure drifted")
    return document


def load_fixed_seed_oracle() -> dict[str, Any]:
    if sha256_file(SEED_ORACLE) != SEED_ORACLE_SHA256:
        fail("fixed target7 seed oracle SHA256 drifted")
    try:
        fixture = json.loads(SEED_ORACLE.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"fixed target7 seed oracle is invalid: {error}")
    if not isinstance(fixture, dict):
        fail("fixed target7 seed oracle root is not an object")
    seeds = fixture.get("seeds")
    if (
        fixture.get("version") != 1
        or fixture.get("baselineLegalEntityId")
        != "30000000-0000-0000-0000-000000000001"
        or fixture.get("effectiveFrom") != "1970-01-01"
        or fixture.get("effectiveTo") is not None
        or not isinstance(seeds, list)
        or len(seeds) != 3
        or tuple(value.get("policyKind") for value in seeds)
        != EXPECTED_SEED_KINDS
    ):
        fail("fixed target7 seed oracle root/cardinality drifted")
    for identifier in (
        "templateId",
        "scopeId",
        "scopedVersionId",
        "lifecycleEventId",
    ):
        values = [value.get(identifier) for value in seeds]
        if (
            len(set(values)) != 3
            or any(
                not isinstance(value, str)
                or not re.fullmatch(
                    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    r"[0-9a-f]{4}-[0-9a-f]{12}",
                    value,
                )
                for value in values
            )
        ):
            fail(f"fixed target7 seed oracle {identifier} closure drifted")
    for seed in seeds:
        snapshot = seed.get("canonicalSnapshot")
        digest = seed.get("snapshotDigest")
        if (
            not isinstance(snapshot, str)
            or not SHA256_PATTERN.fullmatch(str(digest))
            or sha256_bytes(snapshot.encode("utf-8")) != digest
        ):
            fail("fixed target7 seed oracle snapshot digest drifted")
    return fixture


def write_new_json(
    output: SecureCaptureOutput,
    filename: str,
    value: Any,
) -> None:
    if (
        not filename
        or filename in {".", ".."}
        or "/" in filename
        or "\x00" in filename
    ):
        fail("capture output filename is unsafe")
    verify_capture_output_identity(output)
    try:
        os.stat(
            filename,
            dir_fd=output.directory_fd,
            follow_symlinks=False,
        )
    except FileNotFoundError:
        pass
    else:
        fail(f"refusing to overwrite capture output: {filename}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    descriptor = os.open(
        filename,
        flags,
        0o600,
        dir_fd=output.directory_fd,
    )
    created_identity = directory_identity(os.fstat(descriptor))
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(canonical_json_bytes(value))
            stream.flush()
            os.fsync(stream.fileno())
        current = os.stat(
            filename,
            dir_fd=output.directory_fd,
            follow_symlinks=False,
        )
        if directory_identity(current) != created_identity:
            fail("capture output file identity changed while writing")
        os.fsync(output.directory_fd)
        verify_capture_output_identity(output)
    except BaseException:
        try:
            current = os.stat(
                filename,
                dir_fd=output.directory_fd,
                follow_symlinks=False,
            )
            if directory_identity(current) == created_identity:
                os.unlink(filename, dir_fd=output.directory_fd)
                os.fsync(output.directory_fd)
        except FileNotFoundError:
            pass
        raise


def validate_plain_file(path: Path, label: str) -> Path:
    if not path.is_absolute():
        fail(f"{label} must be absolute")
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
        fail(f"{label} must be a regular non-symbolic file")
    return path.resolve(strict=True)


def validate_inputs(
    arguments: argparse.Namespace,
) -> tuple[Path, SecureCaptureOutput]:
    if REPOSITORY_ROOT.resolve(strict=True) != Path.cwd().resolve(strict=True):
        fail("run from the exact repository root")
    client = validate_plain_file(arguments.mysql_client, "MySQL client")
    if client != ISOLATED_MYSQL_CLIENT or not os.access(client, os.X_OK):
        fail("MySQL client is not the fixed isolated executable")
    defaults = validate_plain_file(arguments.defaults_file, "defaults file")
    if stat.S_IMODE(defaults.stat().st_mode) != 0o600:
        fail("defaults file mode must be 0600")
    if arguments.database != DATABASE:
        fail("semantic capture is restricted to shenzhou_hr_test")
    return defaults, open_secure_capture_output(arguments.output_dir)


def mysql_query(
    client: Path,
    defaults: Path,
    database: str,
    sql: str,
    *,
    headers: bool = False,
) -> str:
    command = [
        str(client),
        f"--defaults-extra-file={defaults}",
        "--batch",
        "--raw",
        "--default-character-set=utf8mb4",
    ]
    command.append("--column-names" if headers else "--skip-column-names")
    if database:
        command.append(f"--database={database}")
    result = subprocess.run(
        command,
        input=sql,
        text=True,
        encoding="utf-8",
        check=False,
        capture_output=True,
        env={**os.environ, "LC_ALL": "C", "LANG": "C", "TZ": "UTC"},
    )
    if result.returncode != 0:
        detail = result.stderr.strip().replace("\n", " ")[:500]
        fail(f"MySQL semantic query failed (exit {result.returncode}): {detail}")
    return result.stdout


def mysql_scalar(client: Path, defaults: Path, database: str, sql: str) -> str:
    rows = [line for line in mysql_query(client, defaults, database, sql).splitlines()]
    if len(rows) != 1:
        fail(f"scalar query returned {len(rows)} rows")
    return rows[0]


def decode_hex_cell(cell: str, logical_type: str) -> Any:
    if cell == "N":
        return None
    if not cell.startswith("H") or not re.fullmatch(r"H[0-9A-F]*", cell):
        fail("database cell is not NULL/hex framed")
    try:
        text = bytes.fromhex(cell[1:]).decode("utf-8")
    except (ValueError, UnicodeDecodeError) as error:
        fail(f"database cell is not valid UTF-8 hex: {error}")
    base_type = logical_type.removesuffix("?")
    if base_type == "json":
        try:
            return json.loads(text)
        except json.JSONDecodeError as error:
            fail(f"database JSON cell is invalid: {error}")
    if base_type in {"uint", "integer"}:
        if not re.fullmatch(r"-?[0-9]+", text):
            fail("database integer cell is invalid")
        return int(text)
    if base_type in {"boolean", "bool"}:
        if text not in {"0", "1"}:
            fail("database boolean cell is invalid")
        return text == "1"
    return text


def hex_expression(column: str, logical_type: str) -> str:
    base_type = logical_type.removesuffix("?")
    quoted = f"`{column}`"
    if base_type == "instant":
        rendered = (
            "DATE_FORMAT("
            f"{quoted}, '%Y-%m-%dT%H:%i:%s.%fZ')"
        )
    elif base_type == "date":
        rendered = f"DATE_FORMAT({quoted}, '%Y-%m-%d')"
    elif base_type == "json":
        rendered = f"CAST({quoted} AS CHAR CHARACTER SET utf8mb4)"
    elif base_type == "binary":
        return f"IF({quoted} IS NULL, 'N', CONCAT('H', HEX({quoted})))"
    else:
        rendered = f"CAST({quoted} AS CHAR CHARACTER SET utf8mb4)"
    return f"IF({quoted} IS NULL, 'N', CONCAT('H', HEX({rendered})))"


def capture_w2_snapshot(
    client: Path, defaults: Path, database: str, phase: str
) -> dict[str, Any]:
    registry_document = load_fixed_w2_registry()
    registry = load_registry(W2_REGISTRY)
    table_names = [entry["table"] for entry in registry_document["tables"]]
    if table_names != list(registry):
        fail("W2 registry order drifted")
    source_rows: list[dict[str, Any]] = []
    counts: dict[str, int] = {}
    for table in table_names:
        definition = registry[table]
        select = ", ".join(
            hex_expression(column, logical_type)
            for column, logical_type in definition["columns"]
        )
        order = ", ".join(
            f"CAST(`{column}` AS BINARY)" for column in definition["pk"]
        )
        output = mysql_query(
            client,
            defaults,
            database,
            (
                "SET SESSION time_zone = '+00:00';\n"
                f"SELECT {select} FROM `{table}` ORDER BY {order};\n"
            ),
        )
        rows = output.splitlines()
        counts[table] = len(rows)
        for line in rows:
            cells = line.split("\t")
            if len(cells) != len(definition["columns"]):
                fail(f"{table} export column count mismatch")
            values: dict[str, Any] = {}
            for (column, logical_type), cell in zip(
                definition["columns"], cells, strict=True
            ):
                values[column] = decode_hex_cell(cell, logical_type)
            source_rows.append({"table": table, "values": values})
    canonical = canonical_rows(registry, source_rows)
    lines = [entry["snapshotLine"] for entry in canonical]
    return {
        "schemaVersion": 1,
        "nodeType": "w2-retained-snapshot",
        "phase": phase,
        "database": database,
        "registryPath": W2_REGISTRY.relative_to(REPOSITORY_ROOT).as_posix(),
        "registrySha256": sha256_file(W2_REGISTRY),
        "rowCounts": counts,
        "rowCount": len(canonical),
        "rows": canonical,
        "finalHash": sha256_bytes("".join(lines).encode("utf-8")),
    }


def normalize_type_name(value: str) -> str:
    upper = value.upper()
    return {
        "INTEGER": "INT",
        "DATETIME": "DATETIME",
        "TIMESTAMP": "DATETIME",
    }.get(upper, upper)


def normalize_check_expression(value: str) -> str:
    normalized = normalize_sql_fragment(value.replace("`", ""))
    while normalized.startswith("(") and normalized.endswith(")"):
        depth = 0
        encloses_all = True
        for index, character in enumerate(normalized):
            if character == "(":
                depth += 1
            elif character == ")":
                depth -= 1
                if depth == 0 and index != len(normalized) - 1:
                    encloses_all = False
                    break
        if not encloses_all or depth != 0:
            break
        normalized = normalize_sql_fragment(normalized[1:-1])
    return normalized


def expected_column_metadata(item: str) -> dict[str, Any]:
    parsed = parse_column(item)
    normalized = " ".join(item.split())
    match = re.match(
        rf"(?is)`?{re.escape(parsed['name'])}`?\s+"
        r"([a-z]+(?:\([0-9]+\))?(?:\s+unsigned)?)(?=\s|$)",
        normalized,
    )
    if not match:
        fail(f"cannot parse V4 column type: {parsed['name']}")
    column_type = " ".join(match.group(1).upper().split())
    data_type = column_type.split("(", 1)[0].split()[0]
    character_set: str | None = None
    collation: str | None = None
    if data_type in {"CHAR", "VARCHAR"}:
        charset_match = re.search(
            r"(?i)\bCHARACTER\s+SET\s+([a-z0-9_]+)", normalized
        )
        collation_match = re.search(
            r"(?i)\bCOLLATE\s+([a-z0-9_]+)", normalized
        )
        character_set = (
            charset_match.group(1).lower()
            if charset_match
            else "utf8mb4"
        )
        collation = (
            collation_match.group(1).lower()
            if collation_match
            else "utf8mb4_0900_ai_ci"
        )
    elif data_type == "JSON":
        character_set = "utf8mb4"
        collation = "utf8mb4_bin"
    default_match = re.search(
        r"(?is)\bDEFAULT\s+('(?:''|[^'])*'|[^\s,]+)", normalized
    )
    default_value: str | None = None
    if default_match:
        default_value = default_match.group(1)
        if default_value.startswith("'") and default_value.endswith("'"):
            default_value = default_value[1:-1].replace("''", "'")
    precision_match = re.fullmatch(r"DATETIME\(([0-9]+)\)", column_type)
    return {
        "name": parsed["name"],
        "dataType": data_type,
        "columnType": column_type,
        "characterMaximumLength": (
            parsed["length"]
            if data_type in {"CHAR", "VARCHAR"}
            else 4_294_967_295
            if data_type == "JSON"
            else None
        ),
        "nullable": parsed["nullable"],
        "defaultHex": (
            None
            if default_value is None
            else default_value.encode("utf-8").hex().upper()
        ),
        "datetimePrecision": (
            int(precision_match.group(1)) if precision_match else None
        ),
        "characterSet": character_set,
        "collation": collation,
        "extra": "",
        "generationExpression": "",
    }


def expected_v4_schema() -> dict[str, Any]:
    if sha256_file(V4_MIGRATION) != V4_SHA256:
        fail("immutable V4 migration SHA256 drifted")
    source = strip_sql_comments(V4_MIGRATION.read_text(encoding="utf-8"))
    tables: dict[str, Any] = {}
    for table in W2_POLICY_TABLES:
        body, _ = extract_table_block(source, table)
        columns: list[dict[str, Any]] = []
        indexes: list[dict[str, Any]] = []
        foreign_keys: list[dict[str, Any]] = []
        checks: list[dict[str, Any]] = []
        for item in split_sql_items(body):
            normalized = " ".join(item.split())
            upper = normalized.upper()
            if upper.startswith(("PRIMARY KEY", "UNIQUE KEY", "KEY ")):
                match = re.fullmatch(
                    r"(?is)(PRIMARY\s+KEY|UNIQUE\s+KEY|KEY)"
                    r"(?:\s+([a-z0-9_]+))?\s*\(([^)]*)\)",
                    normalized,
                )
                if not match:
                    fail(f"cannot parse V4 index: {table}")
                kind, name, raw_columns = match.groups()
                indexes.append(
                    {
                        "name": name or "PRIMARY",
                        "nonUnique": kind.upper() == "KEY",
                        "indexType": "BTREE",
                        "visible": True,
                        "parts": [
                            {
                                "column": token.strip().strip("`"),
                                "subPart": None,
                                "collation": "A",
                                "expression": None,
                            }
                            for token in raw_columns.split(",")
                        ],
                    }
                )
                continue
            if upper.startswith("CONSTRAINT "):
                fk = re.fullmatch(
                    r"(?is)CONSTRAINT\s+([a-z0-9_]+)\s+FOREIGN\s+KEY"
                    r"\s*\(([^)]*)\)\s+REFERENCES\s+([a-z0-9_]+)"
                    r"\s*\(([^)]*)\)",
                    normalized,
                )
                if fk:
                    name, raw_columns, referenced_table, raw_referenced = fk.groups()
                    foreign_keys.append(
                        {
                            "name": name,
                            "columns": [
                                value.strip().strip("`")
                                for value in raw_columns.split(",")
                            ],
                            "referencedTable": referenced_table,
                            "referencedColumns": [
                                value.strip().strip("`")
                                for value in raw_referenced.split(",")
                            ],
                            "updateRule": "RESTRICT",
                            "deleteRule": "RESTRICT",
                        }
                    )
                    continue
                check = re.fullmatch(
                    r"(?is)CONSTRAINT\s+([a-z0-9_]+)\s+CHECK\s*\((.*)\)",
                    normalized,
                )
                if check:
                    checks.append(
                        {
                            "name": check.group(1),
                            "expression": normalize_check_expression(check.group(2)),
                            "enforced": True,
                        }
                    )
                    continue
                fail(f"cannot parse V4 constraint: {table}")
            columns.append(expected_column_metadata(item))
        indexes.sort(key=lambda value: (value["name"] != "PRIMARY", value["name"]))
        foreign_keys.sort(key=lambda value: value["name"])
        checks.sort(key=lambda value: value["name"])
        tables[table] = {
            "table": {
                "engine": "InnoDB",
                "collation": "utf8mb4_0900_ai_ci",
            },
            "columns": columns,
            "indexes": indexes,
            "foreignKeys": foreign_keys,
            "checks": checks,
        }
    return {
        "schemaVersion": 1,
        "nodeType": "w2-v4-mysql-schema-oracle",
        "authorityPath": V4_MIGRATION.relative_to(REPOSITORY_ROOT).as_posix(),
        "authoritySha256": V4_SHA256,
        "tables": tables,
    }


def query_tsv_objects(
    client: Path,
    defaults: Path,
    database: str,
    sql: str,
    columns: Sequence[str],
) -> list[dict[str, str | None]]:
    output = mysql_query(client, defaults, database, sql)
    rows: list[dict[str, str | None]] = []
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) != len(columns):
            fail("information_schema export column count mismatch")
        rows.append(
            {
                column: None if value == "NULL" else value
                for column, value in zip(columns, fields, strict=True)
            }
        )
    return rows


def actual_v4_schema(
    client: Path, defaults: Path, database: str
) -> dict[str, Any]:
    table_filter = ", ".join(f"'{table}'" for table in W2_POLICY_TABLES)
    table_metadata = query_tsv_objects(
        client,
        defaults,
        "",
        f"""
        SELECT TABLE_NAME, ENGINE, TABLE_COLLATION
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = '{database}'
          AND TABLE_NAME IN ({table_filter})
        ORDER BY FIELD(TABLE_NAME, {table_filter});
        """,
        ("table", "engine", "collation"),
    )
    columns = query_tsv_objects(
        client,
        defaults,
        "",
        f"""
        SELECT TABLE_NAME, COLUMN_NAME, UPPER(DATA_TYPE), UPPER(COLUMN_TYPE),
               COALESCE(CHARACTER_MAXIMUM_LENGTH, 'NULL'),
               IS_NULLABLE,
               IF(COLUMN_DEFAULT IS NULL, 'NULL', HEX(COLUMN_DEFAULT)),
               COALESCE(DATETIME_PRECISION, 'NULL'),
               COALESCE(CHARACTER_SET_NAME, 'NULL'),
               COALESCE(COLLATION_NAME, 'NULL'),
               EXTRA, GENERATION_EXPRESSION,
               ORDINAL_POSITION
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '{database}'
          AND TABLE_NAME IN ({table_filter})
        ORDER BY FIELD(TABLE_NAME, {table_filter}), ORDINAL_POSITION;
        """,
        (
            "table",
            "name",
            "dataType",
            "columnType",
            "characterMaximumLength",
            "nullable",
            "defaultHex",
            "datetimePrecision",
            "characterSet",
            "collation",
            "extra",
            "generationExpression",
            "ordinal",
        ),
    )
    indexes = query_tsv_objects(
        client,
        defaults,
        "",
        f"""
        SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, INDEX_TYPE, IS_VISIBLE,
               COLUMN_NAME, SUB_PART, COLLATION, EXPRESSION, SEQ_IN_INDEX
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = '{database}'
          AND TABLE_NAME IN ({table_filter})
        ORDER BY FIELD(TABLE_NAME, {table_filter}), INDEX_NAME, SEQ_IN_INDEX;
        """,
        (
            "table",
            "name",
            "nonUnique",
            "indexType",
            "visible",
            "column",
            "subPart",
            "collation",
            "expression",
            "sequence",
        ),
    )
    foreign_keys = query_tsv_objects(
        client,
        defaults,
        "",
        f"""
        SELECT kcu.TABLE_NAME, kcu.CONSTRAINT_NAME, kcu.COLUMN_NAME,
               kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME,
               rc.UPDATE_RULE, rc.DELETE_RULE, kcu.ORDINAL_POSITION
        FROM information_schema.KEY_COLUMN_USAGE kcu
        JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
          ON rc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
         AND rc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
         AND rc.TABLE_NAME = kcu.TABLE_NAME
        WHERE kcu.CONSTRAINT_SCHEMA = '{database}'
          AND kcu.TABLE_NAME IN ({table_filter})
          AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
        ORDER BY FIELD(kcu.TABLE_NAME, {table_filter}),
                 kcu.CONSTRAINT_NAME, kcu.ORDINAL_POSITION;
        """,
        (
            "table",
            "name",
            "column",
            "referencedTable",
            "referencedColumn",
            "updateRule",
            "deleteRule",
            "ordinal",
        ),
    )
    checks = query_tsv_objects(
        client,
        defaults,
        "",
        f"""
        SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE, tc.ENFORCED
        FROM information_schema.TABLE_CONSTRAINTS tc
        JOIN information_schema.CHECK_CONSTRAINTS cc
          ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
         AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
        WHERE tc.CONSTRAINT_SCHEMA = '{database}'
          AND tc.TABLE_NAME IN ({table_filter})
          AND tc.CONSTRAINT_TYPE = 'CHECK'
        ORDER BY FIELD(tc.TABLE_NAME, {table_filter}), tc.CONSTRAINT_NAME;
        """,
        ("table", "name", "expression", "enforced"),
    )
    result: dict[str, Any] = {}
    for table in W2_POLICY_TABLES:
        table_columns = [
            {
                "name": row["name"],
                "dataType": normalize_type_name(str(row["dataType"])),
                "columnType": str(row["columnType"]),
                "characterMaximumLength": (
                    None
                    if row["characterMaximumLength"] is None
                    else int(row["characterMaximumLength"])
                ),
                "nullable": row["nullable"] == "YES",
                "defaultHex": row["defaultHex"],
                "datetimePrecision": (
                    None
                    if row["datetimePrecision"] is None
                    else int(row["datetimePrecision"])
                ),
                "characterSet": row["characterSet"],
                "collation": row["collation"],
                "extra": row["extra"],
                "generationExpression": row["generationExpression"],
            }
            for row in columns
            if row["table"] == table
        ]
        grouped_indexes: dict[str, dict[str, Any]] = {}
        for row in indexes:
            if row["table"] != table:
                continue
            name = str(row["name"])
            grouped_indexes.setdefault(
                name,
                {
                    "name": name,
                    "nonUnique": row["nonUnique"] == "1",
                    "indexType": row["indexType"],
                    "visible": row["visible"] == "YES",
                    "parts": [],
                },
            )["parts"].append(
                {
                    "column": row["column"],
                    "subPart": (
                        None
                        if row["subPart"] is None
                        else int(row["subPart"])
                    ),
                    "collation": row["collation"],
                    "expression": row["expression"],
                }
            )
        grouped_fks: dict[str, dict[str, Any]] = {}
        for row in foreign_keys:
            if row["table"] != table:
                continue
            name = str(row["name"])
            target = grouped_fks.setdefault(
                name,
                {
                    "name": name,
                    "columns": [],
                    "referencedTable": row["referencedTable"],
                    "referencedColumns": [],
                    "updateRule": row["updateRule"],
                    "deleteRule": row["deleteRule"],
                },
            )
            target["columns"].append(row["column"])
            target["referencedColumns"].append(row["referencedColumn"])
        result[table] = {
            "table": next(
                (
                    {
                        "engine": row["engine"],
                        "collation": row["collation"],
                    }
                    for row in table_metadata
                    if row["table"] == table
                ),
                None,
            ),
            "columns": table_columns,
            "indexes": [
                grouped_indexes[name]
                for name in sorted(
                    grouped_indexes,
                    key=lambda value: (value != "PRIMARY", value),
                )
            ],
            "foreignKeys": [
                grouped_fks[name] for name in sorted(grouped_fks)
            ],
            "checks": [
                {
                    "name": row["name"],
                    "expression": normalize_check_expression(
                        str(row["expression"])
                    ),
                    "enforced": row["enforced"] == "YES",
                }
                for row in checks
                if row["table"] == table
            ],
        }
    return result


def compare_v4_schema(
    client: Path, defaults: Path, database: str
) -> dict[str, Any]:
    expected = expected_v4_schema()
    actual_tables = actual_v4_schema(client, defaults, database)
    differences: list[str] = []
    for table in W2_POLICY_TABLES:
        expected_table = expected["tables"][table]
        actual_table = actual_tables.get(table)
        if actual_table is None:
            differences.append(f"{table}: missing")
            continue
        for key in ("table", "columns", "indexes", "foreignKeys", "checks"):
            if actual_table[key] != expected_table[key]:
                differences.append(f"{table}.{key}: differs")
    return {
        **expected,
        "database": database,
        "actualTables": actual_tables,
        "differences": differences,
        "verdict": "PASS" if not differences else "FAIL",
    }


def capture_flyway_history(
    client: Path, defaults: Path, database: str, phase: str
) -> dict[str, Any]:
    rows = query_tsv_objects(
        client,
        defaults,
        database,
        """
        SELECT installed_rank, COALESCE(version, 'NULL'), description, type,
               script, checksum, success
        FROM flyway_schema_history
        ORDER BY installed_rank;
        """,
        (
            "installedRank",
            "version",
            "description",
            "type",
            "script",
            "checksum",
            "success",
        ),
    )
    return {
        "schemaVersion": 1,
        "nodeType": "flyway-history-snapshot",
        "phase": phase,
        "database": database,
        "rows": rows,
        "rowCount": len(rows),
        "snapshotSha256": sha256_bytes(canonical_json_bytes(rows)),
    }


W2_FIXTURE_SQL = r"""
START TRANSACTION;
INSERT INTO policy_template (
  template_id, template_code, name, description, field_definitions_json,
  status, row_version, created_by, created_at, updated_by, updated_at
) VALUES (
  '24400000-0000-0000-0000-000000000001',
  'W3_RETAINED_W2_POLICY', 'W3 retained 通用策略', 'W3 独立 retained 非空夹具',
  JSON_OBJECT('enabled', JSON_OBJECT('type', 'BOOLEAN')),
  'ACTIVE', 7, '20000000-0000-0000-0000-000000000001',
  TIMESTAMP '2020-01-02 03:04:05.123456',
  '20000000-0000-0000-0000-000000000001',
  TIMESTAMP '2020-01-02 03:04:06.123456'
);
INSERT INTO policy_version (
  version_id, template_id, version_number, status, parameters_json,
  effective_from, effective_to, change_reason, validation_json, snapshot_json,
  snapshot_digest, rollback_of_version_id, row_version, created_by, created_at,
  published_at, updated_by, updated_at
) VALUES
(
  '24410000-0000-0000-0000-000000000001',
  '24400000-0000-0000-0000-000000000001', 1, 'PUBLISHED',
  JSON_OBJECT('enabled', TRUE), DATE '2020-01-01', DATE '2020-12-31',
  '初始发布', JSON_OBJECT('valid', TRUE, 'issues', JSON_ARRAY()),
  JSON_OBJECT('parameters', JSON_OBJECT('enabled', TRUE), 'versionNumber', 1),
  REPEAT('1', 64), NULL, 11,
  '20000000-0000-0000-0000-000000000001',
  TIMESTAMP '2020-01-02 03:04:07.123456',
  TIMESTAMP '2020-01-02 03:04:08.123456',
  '20000000-0000-0000-0000-000000000001',
  TIMESTAMP '2020-01-02 03:04:09.123456'
),
(
  '24410000-0000-0000-0000-000000000002',
  '24400000-0000-0000-0000-000000000001', 2, 'INACTIVE',
  JSON_OBJECT('enabled', FALSE), DATE '2021-01-01', NULL,
  '回滚留痕', JSON_OBJECT('valid', TRUE, 'issues', JSON_ARRAY()),
  JSON_OBJECT('parameters', JSON_OBJECT('enabled', FALSE), 'versionNumber', 2),
  REPEAT('2', 64), '24410000-0000-0000-0000-000000000001', 12,
  '20000000-0000-0000-0000-000000000001',
  TIMESTAMP '2020-12-02 03:04:07.123456', NULL,
  '20000000-0000-0000-0000-000000000001',
  TIMESTAMP '2020-12-02 03:04:09.123456'
);
INSERT INTO policy_scope_binding (
  binding_id, version_id, scope_type, scope_resource_id, priority,
  effective_from, effective_to, row_version
) VALUES (
  '24420000-0000-0000-0000-000000000001',
  '24410000-0000-0000-0000-000000000001',
  'COMPANY', '30000000-0000-0000-0000-000000000001', 100,
  DATE '2020-01-01', DATE '2020-12-31', 3
);
INSERT INTO policy_publication_record (
  publication_id, template_id, version_id, action, reason, actor_id,
  request_id, result, occurred_at, snapshot_digest
) VALUES (
  '24430000-0000-0000-0000-000000000001',
  '24400000-0000-0000-0000-000000000001',
  '24410000-0000-0000-0000-000000000001',
  'PUBLISH', '固定 retained 发布', '20000000-0000-0000-0000-000000000001',
  'w3-retained-publication', 'SUCCESS',
  TIMESTAMP '2020-01-02 03:04:08.123456', REPEAT('1', 64)
);
INSERT INTO policy_rollback_record (
  rollback_id, template_id, source_version_id, target_version_id,
  created_version_id, reason, actor_id, request_id, result, occurred_at
) VALUES (
  '24440000-0000-0000-0000-000000000001',
  '24400000-0000-0000-0000-000000000001',
  '24410000-0000-0000-0000-000000000002',
  '24410000-0000-0000-0000-000000000001',
  '24410000-0000-0000-0000-000000000002',
  '固定 retained 回滚', '20000000-0000-0000-0000-000000000001',
  'w3-retained-rollback', 'SUCCESS',
  TIMESTAMP '2020-12-02 03:04:10.123456'
);
INSERT INTO audit_event (
  event_id, occurred_at, actor_id_ref, actor_type, action_code,
  resource_type, resource_id_ref, scope_digest, purpose_code, result_code,
  reason_code, policy_version, before_digest, after_digest, correlation_id,
  request_id, previous_hash, event_hash, anchor_ref
) VALUES (
  '24450000-0000-0000-0000-000000000001',
  TIMESTAMP '2020-12-02 03:04:11.123456',
  '20000000-0000-0000-0000-000000000001', 'SYSTEM',
  'W3_RETAINED_W2_FIXTURE', 'POLICY_TEMPLATE',
  '24400000-0000-0000-0000-000000000001',
  REPEAT('3', 64), 'VERIFICATION', 'SUCCESS', 'FIXED_FIXTURE', 'W2-V4',
  NULL, REPEAT('4', 64), 'w3-retained-correlation',
  'w3-retained-request', NULL, REPEAT('5', 64), 'W3-RETAINED'
);
COMMIT;
"""


def seed_w2_fixture(
    client: Path, defaults: Path, database: str
) -> dict[str, Any]:
    checkpoint = mysql_scalar(
        client,
        defaults,
        database,
        """
        SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
        FROM flyway_schema_history
        WHERE type = 'SQL' AND success = 1;
        """,
    )
    if checkpoint != "6":
        fail("W2 retained fixture is authorized only at exact V6")
    mysql_query(client, defaults, database, W2_FIXTURE_SQL)
    counts = mysql_scalar(
        client,
        defaults,
        database,
        """
        SELECT CONCAT_WS('|',
          (SELECT COUNT(*) FROM policy_template
             WHERE template_id='24400000-0000-0000-0000-000000000001'),
          (SELECT COUNT(*) FROM policy_version
             WHERE template_id='24400000-0000-0000-0000-000000000001'),
          (SELECT COUNT(*) FROM policy_scope_binding
             WHERE binding_id='24420000-0000-0000-0000-000000000001'),
          (SELECT COUNT(*) FROM policy_publication_record
             WHERE publication_id='24430000-0000-0000-0000-000000000001'),
          (SELECT COUNT(*) FROM policy_rollback_record
             WHERE rollback_id='24440000-0000-0000-0000-000000000001'),
          (SELECT COUNT(*) FROM audit_event
             WHERE event_id='24450000-0000-0000-0000-000000000001')
        );
        """,
    )
    if counts != "1|2|1|1|1|1":
        fail(f"W2 retained fixture cardinality mismatch: {counts}")
    return {
        "schemaVersion": 1,
        "nodeType": "w2-retained-fixed-fixture",
        "database": database,
        "checkpoint": 6,
        "cardinality": {
            "policy_template": 1,
            "policy_version": 2,
            "policy_scope_binding": 1,
            "policy_publication_record": 1,
            "policy_rollback_record": 1,
            "audit_event": 1,
        },
        "fixtureSqlSha256": sha256_bytes(W2_FIXTURE_SQL.encode("utf-8")),
        "verdict": "PASS",
    }


def capture_seed_oracle(
    client: Path, defaults: Path, database: str
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    fixture = load_fixed_seed_oracle()
    actual_rows: list[dict[str, Any]] = []
    recomputed: list[dict[str, Any]] = []
    for expected in fixture["seeds"]:
        columns = (
            "templateId",
            "policyKind",
            "scopeId",
            "legalEntityId",
            "scopedVersionId",
            "versionNumber",
            "parametersJson",
            "effectiveFrom",
            "effectiveTo",
            "snapshotJson",
            "snapshotDigest",
            "statusColumnCount",
            "lifecycleEventId",
            "lifecycleAction",
            "lifecycleEffectiveFrom",
        )
        rows = query_tsv_objects(
            client,
            defaults,
            database,
            f"""
            SELECT template.policy_template_id, template.template_code,
                   scope.scope_id, scope.legal_entity_id,
                   version.scoped_version_id, version.version_number,
                   CAST(version.parameters_json AS CHAR CHARACTER SET utf8mb4),
                   DATE_FORMAT(version.effective_from, '%Y-%m-%d'),
                   COALESCE(DATE_FORMAT(version.effective_to, '%Y-%m-%d'), 'NULL'),
                   CAST(version.snapshot_json AS CHAR CHARACTER SET utf8mb4),
                   version.snapshot_digest,
                   (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = '{database}'
                      AND TABLE_NAME = 'attendance_policy_scoped_version'
                      AND COLUMN_NAME = 'status'),
                   event.lifecycle_event_id, event.action,
                   DATE_FORMAT(event.business_effective_from, '%Y-%m-%d')
            FROM attendance_policy_template template
            JOIN attendance_policy_scope scope
              ON scope.policy_template_id = template.policy_template_id
            JOIN attendance_policy_scoped_version version
              ON version.scope_id = scope.scope_id
            JOIN attendance_policy_lifecycle_event event
              ON event.scope_id = scope.scope_id
             AND event.scoped_version_id = version.scoped_version_id
            WHERE template.policy_template_id = '{expected["templateId"]}'
              AND scope.scope_id = '{expected["scopeId"]}'
              AND version.scoped_version_id = '{expected["scopedVersionId"]}'
              AND event.lifecycle_event_id = '{expected["lifecycleEventId"]}';
            """,
            columns,
        )
        if len(rows) != 1:
            fail(f"seed oracle row count differs for {expected['policyKind']}")
        row = rows[0]
        try:
            parameters = json.loads(str(row["parametersJson"]))
            snapshot = json.loads(str(row["snapshotJson"]))
        except json.JSONDecodeError as error:
            fail(f"seed JSON is invalid: {error}")
        canonical_snapshot = json.dumps(
            snapshot,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        digest = sha256_bytes(canonical_snapshot.encode("utf-8"))
        expected_shape = {
            "templateId": expected["templateId"],
            "policyKind": expected["policyKind"],
            "scopeId": expected["scopeId"],
            "legalEntityId": fixture["baselineLegalEntityId"],
            "scopedVersionId": expected["scopedVersionId"],
            "versionNumber": "1",
            "effectiveFrom": fixture["effectiveFrom"],
            "effectiveTo": None,
            "snapshotDigest": expected["snapshotDigest"],
            "statusColumnCount": "0",
            "lifecycleEventId": expected["lifecycleEventId"],
            "lifecycleAction": "PUBLISHED",
            "lifecycleEffectiveFrom": fixture["effectiveFrom"],
        }
        actual_shape = {
            key: row[key]
            for key in expected_shape
        }
        if actual_shape != expected_shape:
            fail(f"seed scalar mismatch for {expected['policyKind']}")
        if parameters != expected["parameters"]:
            fail(f"seed parameters mismatch for {expected['policyKind']}")
        if (
            canonical_snapshot != expected["canonicalSnapshot"]
            or digest != expected["snapshotDigest"]
        ):
            fail(f"seed digest mismatch for {expected['policyKind']}")
        actual_rows.append(
            {
                **actual_shape,
                "parameters": parameters,
                "canonicalSnapshot": canonical_snapshot,
            }
        )
        recomputed.append(
            {
                "policyKind": expected["policyKind"],
                "canonicalSnapshotUtf8Bytes": len(
                    canonical_snapshot.encode("utf-8")
                ),
                "expectedDigest": expected["snapshotDigest"],
                "recomputedDigest": digest,
                "match": True,
            }
        )
    if (
        len(actual_rows) != 3
        or len(recomputed) != 3
        or tuple(value["policyKind"] for value in actual_rows)
        != EXPECTED_SEED_KINDS
        or tuple(value["policyKind"] for value in recomputed)
        != EXPECTED_SEED_KINDS
    ):
        fail("target7 seed export/recomputation cardinality drifted")
    counts = mysql_scalar(
        client,
        defaults,
        database,
        """
        SELECT CONCAT_WS('|',
          (SELECT COUNT(*) FROM attendance_policy_template),
          (SELECT COUNT(*) FROM attendance_policy_scope),
          (SELECT COUNT(*) FROM attendance_policy_scoped_version),
          (SELECT COUNT(*) FROM attendance_policy_lifecycle_event
             WHERE action='PUBLISHED'),
          (SELECT COUNT(*) FROM attendance_policy_binding_family),
          (SELECT COUNT(*) FROM attendance_policy_binding_revision)
        );
        """,
    )
    if counts != "3|3|3|3|0|0":
        fail(f"target7 seed no-extras cardinality mismatch: {counts}")
    export = {
        "schemaVersion": 1,
        "nodeType": "target7-seed-database-export",
        "database": database,
        "fixtureSha256": sha256_file(SEED_ORACLE),
        "rows": actual_rows,
        "rowCount": len(actual_rows),
        "verdict": "PASS",
    }
    digest_report = {
        "schemaVersion": 1,
        "nodeType": "target7-seed-digest-recomputation",
        "database": database,
        "algorithm": "SHA-256(RFC8785-compatible canonical JSON UTF-8)",
        "rows": recomputed,
        "verdict": "PASS",
    }
    extras = {
        "schemaVersion": 1,
        "nodeType": "target7-seed-no-extras-query",
        "database": database,
        "counts": {
            "templates": 3,
            "scopes": 3,
            "versions": 3,
            "publishedLifecycleEvents": 3,
            "bindingFamilies": 0,
            "bindingRevisions": 0,
        },
        "scopedVersionStatusColumnCount": 0,
        "verdict": "PASS",
    }
    return export, digest_report, extras


def capture_phase(
    phase: str,
    client: Path,
    defaults: Path,
    database: str,
    output: SecureCaptureOutput,
) -> None:
    history = capture_flyway_history(client, defaults, database, phase)
    write_new_json(output, f"flyway-{phase}.json", history)
    if phase == "v6-before":
        fixture = seed_w2_fixture(client, defaults, database)
        write_new_json(output, "w2-fixed-fixture.json", fixture)
        schema = compare_v4_schema(client, defaults, database)
        if schema["verdict"] != "PASS":
            fail(f"W2 V4 schema differs: {schema['differences']}")
        write_new_json(output, "w2-v4-schema-oracle.json", schema)
        write_new_json(
            output,
            "w2-before-snapshot.json",
            capture_w2_snapshot(client, defaults, database, phase),
        )
    elif phase == "target7":
        export, digest, extras = capture_seed_oracle(
            client, defaults, database
        )
        write_new_json(output, "target7-database-export.json", export)
        write_new_json(output, "target7-digest-recomputation.json", digest)
        write_new_json(output, "target7-no-extras-query.json", extras)
    elif phase == "latest-after":
        write_new_json(
            output,
            "w2-after-snapshot.json",
            capture_w2_snapshot(client, defaults, database, phase),
        )


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser()
    value.add_argument("--mysql-client", type=Path, required=True)
    value.add_argument("--defaults-file", type=Path, required=True)
    value.add_argument("--database", required=True)
    value.add_argument("--output-dir", type=Path, required=True)
    value.add_argument("--phase", choices=sorted(ALLOWED_PHASES), required=True)
    return value


def main(argv: Sequence[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    output: SecureCaptureOutput | None = None
    try:
        defaults, output = validate_inputs(arguments)
        capture_phase(
            arguments.phase,
            ISOLATED_MYSQL_CLIENT,
            defaults,
            arguments.database,
            output,
        )
    except (CaptureError, OSError, ValueError, KeyError) as error:
        sys.stderr.write(f"W3_MYSQL_SEMANTIC_CAPTURE=FAIL {error}\n")
        return 1
    finally:
        if output is not None:
            output.close()
    sys.stdout.write(
        "W3_MYSQL_SEMANTIC_CAPTURE=PASS "
        f"phase={arguments.phase} database={arguments.database}\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
