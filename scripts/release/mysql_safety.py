from __future__ import annotations

import ipaddress
import json
import os
import re
import stat
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

from scripts.release.common import ContractError, require_secure_secret_file

DATABASE_PATTERN = re.compile(r"^[a-z][a-z0-9_]{2,63}$")
ENVIRONMENT_ID_PATTERN = re.compile(r"^nonprod:[a-z0-9][a-z0-9._-]{2,55}$")
MYSQL_USER_PATTERN = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
ALLOWED_CLIENT_KEYS = frozenset({"host", "port", "user", "password"})
IMPORT_PRIVILEGES = frozenset(
    {
        "ALTER",
        "CREATE",
        "CREATE ROUTINE",
        "CREATE TEMPORARY TABLES",
        "CREATE VIEW",
        "DROP",
        "EVENT",
        "EXECUTE",
        "INDEX",
        "INSERT",
        "LOCK TABLES",
        "REFERENCES",
        "SELECT",
        "SHOW VIEW",
        "TRIGGER",
    }
)
READ_ONLY_PRIVILEGES = frozenset({"SELECT", "SHOW VIEW"})


@dataclass(frozen=True)
class MySqlClientIdentity:
    host: str
    port: int
    user: str


def validate_database_name(database: str) -> str:
    if not DATABASE_PATTERN.fullmatch(database):
        raise ContractError("database name contains unsupported characters")
    return database


def validate_backup_source(database: str) -> str:
    validate_database_name(database)
    if database != "shenzhou_hr_test" and not (
        database.startswith("shenzhou_hr_") and database.endswith("_restore")
    ):
        raise ContractError(
            "independent backup execution only permits shenzhou_hr_test "
            "or shenzhou_hr_*_restore"
        )
    return database


def validate_restore_target(database: str) -> str:
    validate_database_name(database)
    if not database.startswith("shenzhou_hr_") or not database.endswith("_restore"):
        raise ContractError("restore target must be a shenzhou_hr_*_restore sandbox")
    if database in {"shenzhou_hr_dev", "shenzhou_hr_test"}:
        raise ContractError("restore target must not be the dev or test authority database")
    return database


def validate_server_uuid(value: str) -> str:
    try:
        parsed = uuid.UUID(value)
    except (AttributeError, TypeError, ValueError) as exc:
        raise ContractError("expected MySQL server_uuid must be a canonical UUID") from exc
    canonical = str(parsed)
    if value != canonical:
        raise ContractError("expected MySQL server_uuid must use canonical lowercase form")
    return canonical


def validate_environment_id(value: str) -> str:
    if not isinstance(value, str) or not ENVIRONMENT_ID_PATTERN.fullmatch(value):
        raise ContractError(
            "environment identity must use nonprod:<stable-lowercase-id>"
        )
    return value


def validate_server_authority(
    *,
    version: str,
    observed_server_uuid: str,
    observed_environment_id: str,
    expected_server_uuid: str,
    expected_environment_id: str,
) -> tuple[str, str]:
    expected_uuid = validate_server_uuid(expected_server_uuid)
    expected_environment = validate_environment_id(expected_environment_id)
    try:
        observed_uuid = str(uuid.UUID(observed_server_uuid))
    except (AttributeError, TypeError, ValueError) as exc:
        raise ContractError("MySQL returned an invalid server_uuid") from exc
    try:
        observed_environment = validate_environment_id(observed_environment_id)
    except ContractError as exc:
        raise ContractError(
            "MySQL release-control environment identity is invalid"
        ) from exc
    if not version.startswith("8.4."):
        raise ContractError(f"MySQL 8.4 LTS required, observed {version}")
    if observed_uuid != expected_uuid:
        raise ContractError("MySQL server_uuid does not match the approved authority")
    if observed_environment != expected_environment:
        raise ContractError(
            "MySQL environment identity does not match the approved authority"
        )
    return observed_uuid, observed_environment


def validate_sha256(value: object, label: str) -> str:
    if not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value):
        raise ContractError(f"{label} must be a lowercase SHA-256")
    return value


def read_client_identity(
    path: Path, repository_root: Path
) -> MySqlClientIdentity:
    secure_path = require_secure_secret_file(path, repository_root)
    section: str | None = None
    client_section_seen = False
    values: dict[str, str] = {}
    text = secure_path.read_text(encoding="utf-8")
    if "\x00" in text:
        raise ContractError("MySQL client file contains a NUL byte")
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("!"):
            raise ContractError(
                f"MySQL client include/directive is forbidden at line {line_number}"
            )
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip().lower()
            if section != "client":
                raise ContractError(
                    f"MySQL client file contains unsupported section at line {line_number}"
                )
            if client_section_seen:
                raise ContractError("MySQL client file contains duplicate [client] section")
            client_section_seen = True
            continue
        if section != "client":
            raise ContractError(
                f"MySQL client option appears outside [client] at line {line_number}"
            )
        if "=" not in line:
            raise ContractError(
                f"MySQL client option is malformed at line {line_number}"
            )
        name, value = line.split("=", 1)
        normalized_name = name.strip().lower()
        if normalized_name not in ALLOWED_CLIENT_KEYS:
            raise ContractError(
                f"MySQL client option {normalized_name!r} is not allowed"
            )
        if normalized_name in values:
            raise ContractError(
                f"MySQL client option {normalized_name!r} is duplicated"
            )
        normalized_value = value.strip()
        if not normalized_value:
            raise ContractError(
                f"MySQL client option {normalized_name!r} must be non-empty"
            )
        if len(normalized_value) > 1024:
            raise ContractError(
                f"MySQL client option {normalized_name!r} is too long"
            )
        values[normalized_name] = normalized_value
    missing = ALLOWED_CLIENT_KEYS - values.keys()
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
    if not MYSQL_USER_PATTERN.fullmatch(values["user"]):
        raise ContractError("MySQL client user contains unsupported characters")
    return MySqlClientIdentity(str(address), port, values["user"])


def mysql_connection_command(
    binary: str,
    client_file: Path,
    identity: MySqlClientIdentity,
    *args: str,
) -> list[str]:
    return [
        binary,
        f"--defaults-file={client_file}",
        "--no-login-paths",
        "--protocol=TCP",
        f"--host={identity.host}",
        f"--port={identity.port}",
        *args,
    ]


def require_executable(path_or_name: str) -> str:
    candidate = Path(path_or_name)
    if not candidate.is_absolute():
        raise ContractError("approved executable path must be absolute")
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as exc:
        raise ContractError("approved executable is unavailable") from exc
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        raise ContractError("approved executable is unavailable")
    return str(resolved)


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
    try:
        resolved.mkdir(mode=0o700, parents=True, exist_ok=True)
    except OSError as exc:
        raise ContractError(f"cannot create backup output directory: {exc}") from exc
    if resolved.is_symlink() or not resolved.is_dir():
        raise ContractError("backup output must resolve to a regular directory")
    directory_stat = resolved.stat()
    if directory_stat.st_uid != os.getuid():
        raise ContractError("backup output directory must be owned by the current user")
    if stat.S_IMODE(directory_stat.st_mode) != 0o700:
        raise ContractError("backup output directory mode must be exactly 0700")
    return resolved


def require_secure_artifact_file(
    path: Path,
    repository_root: Path,
    *,
    label: str,
) -> Path:
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        raise ContractError(f"{label} must be an absolute non-symlink regular file")
    resolved = path.resolve()
    try:
        resolved.relative_to(repository_root.resolve())
    except ValueError:
        pass
    else:
        raise ContractError(f"{label} must remain outside the repository")
    file_stat = resolved.stat()
    if file_stat.st_uid != os.getuid():
        raise ContractError(f"{label} must be owned by the current user")
    if stat.S_IMODE(file_stat.st_mode) != 0o600:
        raise ContractError(f"{label} mode must be exactly 0600")
    parent_stat = resolved.parent.stat()
    if parent_stat.st_uid != os.getuid():
        raise ContractError(f"{label} directory must be owned by the current user")
    if stat.S_IMODE(parent_stat.st_mode) != 0o700:
        raise ContractError(f"{label} directory mode must be exactly 0700")
    return resolved


def open_exclusive_secure_binary(path: Path) -> BinaryIO:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags, 0o600)
    except FileExistsError as exc:
        raise ContractError(f"refusing to overwrite existing artifact: {path.name}") from exc
    except OSError as exc:
        raise ContractError(f"cannot create secure artifact {path.name}: {exc}") from exc
    try:
        os.fchmod(descriptor, 0o600)
        return os.fdopen(descriptor, "wb")
    except BaseException:
        os.close(descriptor)
        raise


def publish_exclusive(partial_path: Path, final_path: Path) -> None:
    try:
        os.link(partial_path, final_path, follow_symlinks=False)
    except FileExistsError as exc:
        raise ContractError(
            f"refusing to overwrite existing artifact: {final_path.name}"
        ) from exc
    except OSError as exc:
        raise ContractError(
            f"cannot publish secure artifact {final_path.name}: {exc}"
        ) from exc
    partial_path.unlink()


def write_json_exclusive(path: Path, value: dict[str, object]) -> None:
    partial_path = path.parent / f".{path.name}.partial"
    try:
        with open_exclusive_secure_binary(partial_path) as output:
            output.write(
                (
                    json.dumps(
                        value,
                        ensure_ascii=False,
                        indent=2,
                        sort_keys=True,
                    )
                    + "\n"
                ).encode("utf-8")
            )
            output.flush()
            os.fsync(output.fileno())
        publish_exclusive(partial_path, path)
    except BaseException:
        partial_path.unlink(missing_ok=True)
        raise


def validate_restricted_grants(
    grants_output: str,
    *,
    target_database: str,
    mode: str,
) -> None:
    validate_restore_target(target_database)
    if mode == "import":
        allowed = IMPORT_PRIVILEGES
        required = frozenset({"CREATE", "INSERT"})
    elif mode == "read-only":
        allowed = READ_ONLY_PRIVILEGES
        required = frozenset({"SELECT"})
    else:
        raise ContractError("unsupported MySQL restricted grant mode")

    observed: set[str] = set()
    target_scope = f"`{target_database}`.*"
    lines = [line.strip() for line in grants_output.splitlines() if line.strip()]
    if not lines:
        raise ContractError(f"{mode} MySQL account returned no grants")
    for line in lines:
        upper_line = line.upper()
        if " WITH GRANT OPTION" in upper_line:
            raise ContractError(f"{mode} MySQL account must not have GRANT OPTION")
        match = re.match(r"^GRANT (.+) ON (.+) TO .+$", line, flags=re.IGNORECASE)
        if match is None:
            raise ContractError(
                f"{mode} MySQL account has a role, proxy or unsupported grant"
            )
        privileges = {
            value.strip().upper() for value in match.group(1).split(",")
        }
        scope = match.group(2).strip()
        if scope == "*.*" and privileges == {"USAGE"}:
            continue
        if scope != target_scope:
            raise ContractError(
                f"{mode} MySQL account has privileges outside the target schema"
            )
        unsupported = privileges - allowed
        if unsupported:
            raise ContractError(
                f"{mode} MySQL account has unsupported privileges: "
                f"{sorted(unsupported)}"
            )
        observed.update(privileges)
    missing = required - observed
    if missing:
        raise ContractError(
            f"{mode} MySQL account is missing required privileges: {sorted(missing)}"
        )
