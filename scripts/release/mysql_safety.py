from __future__ import annotations

import ipaddress
import os
import re
from pathlib import Path

from scripts.release.common import ContractError, require_secure_secret_file

DATABASE_PATTERN = re.compile(r"^[a-z][a-z0-9_]{2,63}$")


def validate_database_name(database: str) -> str:
    if not DATABASE_PATTERN.fullmatch(database):
        raise ContractError("database name contains unsupported characters")
    return database


def validate_backup_source(database: str) -> str:
    validate_database_name(database)
    if database != "shenzhou_hr_test" and not database.endswith("_restore"):
        raise ContractError(
            "independent backup execution only permits shenzhou_hr_test or *_restore"
        )
    return database


def validate_restore_target(database: str) -> str:
    validate_database_name(database)
    if not database.startswith("shenzhou_hr_") or not database.endswith("_restore"):
        raise ContractError("restore target must be a shenzhou_hr_*_restore sandbox")
    if database in {"shenzhou_hr_dev", "shenzhou_hr_test"}:
        raise ContractError("restore target must not be the dev or test authority database")
    return database


def read_client_identity(path: Path, repository_root: Path) -> tuple[str, int]:
    secure_path = require_secure_secret_file(path, repository_root)
    section = ""
    values: dict[str, str] = {}
    for raw_line in secure_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip()
            continue
        if section != "client" or "=" not in line:
            continue
        name, value = line.split("=", 1)
        values[name.strip()] = value.strip()
    missing = {"host", "port", "user", "password"} - values.keys()
    if missing:
        raise ContractError(f"MySQL client file is missing keys: {sorted(missing)}")
    try:
        address = ipaddress.ip_address(values["host"])
    except ValueError as exc:
        raise ContractError("MySQL client host must be a literal loopback address") from exc
    if not address.is_loopback:
        raise ContractError("MySQL client host must be loopback")
    try:
        port = int(values["port"])
    except ValueError as exc:
        raise ContractError("MySQL client port must be numeric") from exc
    if not 1 <= port <= 65535:
        raise ContractError("MySQL client port is outside the valid range")
    if not values["user"] or not values["password"]:
        raise ContractError("MySQL client user and password must be non-empty")
    return values["host"], port


def require_executable(path_or_name: str) -> str:
    candidate = Path(path_or_name)
    if candidate.is_absolute():
        if not candidate.is_file() or not os.access(candidate, os.X_OK):
            raise ContractError(f"executable is unavailable: {candidate}")
        return str(candidate)
    if "/" in path_or_name:
        raise ContractError("relative executable paths are not permitted")
    return path_or_name


def prepare_output_directory(path: Path, repository_root: Path) -> Path:
    if not path.is_absolute() or path.is_symlink():
        raise ContractError("output directory must be an absolute non-symlink path")
    resolved = path.resolve()
    try:
        resolved.relative_to(repository_root.resolve())
    except ValueError:
        pass
    else:
        raise ContractError("backup output must remain outside the repository")
    resolved.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(resolved, 0o700)
    return resolved
