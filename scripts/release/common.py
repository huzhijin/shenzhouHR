from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import re
import stat
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

ALLOWED_STATUSES = frozenset({"PASS", "FAIL", "NOT_VERIFIED"})
RUN_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{7,63}$")
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")


class ContractError(ValueError):
    """Raised when a W9 safety or evidence contract is violated."""


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(f"cannot read JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ContractError(f"JSON root must be an object: {path}")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_run_id(run_id: Any) -> str:
    if not isinstance(run_id, str) or not RUN_ID_PATTERN.fullmatch(run_id):
        raise ContractError(
            "run_id must be 8-64 lowercase letters, digits, dots, underscores or hyphens"
        )
    return run_id


def validate_commit(commit: Any) -> str:
    if not isinstance(commit, str) or not COMMIT_PATTERN.fullmatch(commit):
        raise ContractError("commit must be a lowercase 40-character Git SHA")
    return commit


def validate_status(status: Any) -> str:
    if status not in ALLOWED_STATUSES:
        raise ContractError(
            f"status must be one of {sorted(ALLOWED_STATUSES)}, got {status!r}"
        )
    return str(status)


def safe_relative_file(root: Path, relative_value: Any) -> Path:
    if not isinstance(relative_value, str) or not relative_value:
        raise ContractError("evidence_path must be a non-empty relative path")
    relative = Path(relative_value)
    if relative.is_absolute() or ".." in relative.parts:
        raise ContractError(f"unsafe evidence path: {relative_value}")

    resolved_root = root.resolve()
    candidate = root / relative
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ContractError(f"evidence path traverses a symlink: {relative_value}")
    try:
        candidate.resolve().relative_to(resolved_root)
    except ValueError as exc:
        raise ContractError(f"evidence path escapes run root: {relative_value}") from exc
    if not candidate.is_file():
        raise ContractError(f"evidence file does not exist: {relative_value}")
    return candidate


def require_secure_secret_file(path: Path, repository_root: Path) -> Path:
    if not path.is_absolute():
        raise ContractError("credential file must use an absolute path")
    if path.is_symlink():
        raise ContractError("credential file must not be a symlink")
    if not path.is_file():
        raise ContractError("credential file must be a regular file")

    resolved = path.resolve()
    try:
        resolved.relative_to(repository_root.resolve())
    except ValueError:
        pass
    else:
        raise ContractError("credential file must remain outside the repository")

    file_stat = path.stat()
    if file_stat.st_uid != os.getuid():
        raise ContractError("credential file must be owned by the current user")
    if stat.S_IMODE(file_stat.st_mode) != 0o600:
        raise ContractError("credential file mode must be exactly 0600")
    return resolved


def require_loopback_url(base_url: str) -> str:
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"}:
        raise ContractError("target URL must use http or https")
    if parsed.username or parsed.password:
        raise ContractError("target URL must not contain credentials")
    if not parsed.hostname:
        raise ContractError("target URL must include a host")
    try:
        address = ipaddress.ip_address(parsed.hostname)
    except ValueError as exc:
        raise ContractError("target host must be a literal loopback address") from exc
    if not address.is_loopback:
        raise ContractError("network execution is restricted to loopback targets")
    return base_url.rstrip("/")
