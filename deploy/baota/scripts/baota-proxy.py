#!/usr/bin/env python3
"""Validate the one BaoTa-managed reverse-proxy rule used by ShenzhouHR.

BaoTa owns the UI record in proxyfile.json.  The release owns the effective
Nginx rule body, but it must never fabricate or edit panel metadata.  On
success this helper prints the exact, already-registered Nginx rule path.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
from pathlib import Path
from typing import Any


MANAGED_MARKER = "# SHENZHOUHR-BAOTA-PROXY-V1"


class ContractError(RuntimeError):
    pass


def _safe_root_file(path: Path, label: str, *, enforce_root_owner: bool = True) -> None:
    try:
        info = path.lstat()
    except FileNotFoundError as exc:
        raise ContractError(f"{label} was not found: {path}") from exc
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise ContractError(f"{label} must be a real regular file: {path}")
    if (enforce_root_owner and info.st_uid != 0) or info.st_mode & 0o022:
        raise ContractError(
            f"{label} must be root-owned and not group/other writable: {path}"
        )


def _safe_root_directory(
    path: Path, label: str, *, enforce_root_owner: bool = True
) -> None:
    try:
        info = path.lstat()
    except FileNotFoundError as exc:
        raise ContractError(f"{label} was not found: {path}") from exc
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise ContractError(f"{label} must be a real directory: {path}")
    if (enforce_root_owner and info.st_uid != 0) or info.st_mode & 0o022:
        raise ContractError(
            f"{label} must be root-owned and not group/other writable: {path}"
        )


def _integer(value: Any, field: str) -> int:
    if isinstance(value, bool):
        raise ContractError(f"BaoTa proxy field {field} is invalid")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ContractError(f"BaoTa proxy field {field} is invalid") from exc
    return parsed


def _path(value: Any) -> str:
    if not isinstance(value, str):
        raise ContractError("BaoTa proxy directory is invalid")
    normalized = "/" + value.strip().strip("/")
    return normalized


def _target(value: Any) -> str:
    if not isinstance(value, str):
        raise ContractError("BaoTa proxy target is invalid")
    return value.strip().rstrip("/")


def _substitutions_are_empty(value: Any) -> bool:
    if value in (None, "", []):
        return True
    if not isinstance(value, list):
        return False
    for item in value:
        if not isinstance(item, dict):
            return False
        if str(item.get("sub1", "")).strip() or str(item.get("sub2", "")).strip():
            return False
    return True


def registered_proxy_path(
    metadata_file: Path,
    proxy_root: Path,
    site: str,
    proxy_name: str,
    expected_target: str,
    require_marker: bool,
    *,
    enforce_root_owner: bool = True,
) -> Path:
    _safe_root_file(
        metadata_file,
        "BaoTa proxy metadata",
        enforce_root_owner=enforce_root_owner,
    )
    try:
        raw = metadata_file.read_text(encoding="utf-8")
        metadata = json.loads(raw)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ContractError("BaoTa proxy metadata is not valid UTF-8 JSON") from exc
    if not isinstance(metadata, list):
        raise ContractError("BaoTa proxy metadata root must be a JSON array")

    site_records = [
        item for item in metadata
        if isinstance(item, dict) and item.get("sitename") == site
    ]
    if len(site_records) != 1:
        raise ContractError(
            f"BaoTa site {site} must have exactly one reverse-proxy record; "
            f"found {len(site_records)}"
        )
    record = site_records[0]
    if record.get("proxyname") != proxy_name:
        raise ContractError(f"BaoTa proxy must be named {proxy_name}")
    if _path(record.get("proxydir")) != "/api":
        raise ContractError("BaoTa proxy directory must be /api")
    if _target(record.get("proxysite")) != _target(expected_target):
        raise ContractError(f"BaoTa proxy target must be {expected_target}")
    if record.get("todomain") != "$host":
        raise ContractError("BaoTa proxy sent domain must be $host")
    if _integer(record.get("type"), "type") != 1:
        raise ContractError("BaoTa proxy must be enabled")
    if _integer(record.get("advanced"), "advanced") != 1:
        raise ContractError("BaoTa proxy advanced mode must be enabled")
    if _integer(record.get("cache"), "cache") != 0:
        raise ContractError("BaoTa proxy cache must be disabled")
    if not _substitutions_are_empty(record.get("subfilter")):
        raise ContractError("BaoTa proxy content replacements must be empty")

    _safe_root_directory(
        proxy_root,
        "BaoTa proxy root",
        enforce_root_owner=enforce_root_owner,
    )
    site_directory = proxy_root / site
    _safe_root_directory(
        site_directory,
        "BaoTa site proxy directory",
        enforce_root_owner=enforce_root_owner,
    )
    digest = hashlib.md5(proxy_name.encode("utf-8")).hexdigest()
    expected_file = site_directory / f"{digest}_{site}.conf"
    _safe_root_file(
        expected_file,
        "BaoTa proxy configuration",
        enforce_root_owner=enforce_root_owner,
    )

    active_files = []
    for candidate in site_directory.glob("*.conf"):
        _safe_root_file(
            candidate,
            "BaoTa active proxy configuration",
            enforce_root_owner=enforce_root_owner,
        )
        active_files.append(candidate)
    if active_files != [expected_file]:
        rendered = ", ".join(sorted(path.name for path in active_files)) or "none"
        raise ContractError(
            "BaoTa site proxy directory must contain only the registered rule; "
            f"found: {rendered}"
        )

    if require_marker:
        try:
            body = expected_file.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            raise ContractError("BaoTa proxy configuration cannot be read") from exc
        if len(body.encode("utf-8")) > 1024 * 1024:
            raise ContractError("BaoTa proxy configuration is unexpectedly large")
        required = (
            MANAGED_MARKER,
            "location ^~ /api/",
            "proxy_pass http://127.0.0.1:8080/api/;",
        )
        if any(fragment not in body for fragment in required):
            raise ContractError(
                "BaoTa proxy configuration is not the managed ShenzhouHR rule"
            )
    return expected_file


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--metadata-file",
        default="/www/server/panel/data/proxyfile.json",
    )
    parser.add_argument(
        "--proxy-root",
        default="/www/server/panel/vhost/nginx/proxy",
    )
    parser.add_argument("--site", default="192.168.160.226")
    parser.add_argument("--proxy-name", default="kaoqin-api")
    parser.add_argument(
        "--expected-target", default="http://127.0.0.1:8080/api"
    )
    parser.add_argument("--require-marker", action="store_true")
    args = parser.parse_args()
    try:
        path = registered_proxy_path(
            Path(args.metadata_file),
            Path(args.proxy_root),
            args.site,
            args.proxy_name,
            args.expected_target,
            args.require_marker,
        )
    except ContractError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(os.fspath(path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
