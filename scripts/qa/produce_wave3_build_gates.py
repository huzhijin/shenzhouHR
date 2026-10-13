#!/usr/bin/env python3
"""Produce the nine frozen-source W3 backend/frontend build leaves.

Every claim is reconstructed from current-run, machine-readable output.  The
producer holds a run-wide lock, binds commands to immutable tool/source
receipts, archives the raw reports/build bytes inside the contract-approved
artifacts, and publishes only through exclusive renames plus a bundle marker.
"""

from __future__ import annotations

import argparse
import base64
import ctypes
import fcntl
import hashlib
import json
import os
import re
import select
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unicodedata
import xml.etree.ElementTree as ET
import zlib
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterable, Mapping, Sequence

try:
    from scripts.qa.verify_wave3_evidence import Wave3Evidence
except ModuleNotFoundError as error:
    if error.name != "scripts":
        raise
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from scripts.qa.verify_wave3_evidence import Wave3Evidence


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
EXPECTED_REPOSITORY_ROOT = REPOSITORY_ROOT
HOST_DISCOVERY_PATH = os.environ.get("PATH", "")
_HOST_TOOL_PATHS: dict[str, Path] | None = None
BACKEND_ROOT = REPOSITORY_ROOT / "backend"
FRONTEND_ROOT = REPOSITORY_ROOT / "frontend"
CONTEXT_PREFIX = "W3_EVIDENCE_CONTEXT=PASS"
FORBIDDEN_STATUS_TOKEN = "NOT_VERIFIED"
LOCK_FILENAME = ".wave3-build-gates.lock"
COMMIT_FILENAME = "wave3-build-gates.commit.json"
PROCESS_GROUP_TERM_GRACE_SECONDS = 1.0
PROCESS_GROUP_KILL_GRACE_SECONDS = 5.0
JAVA_PACKAGE = re.compile(
    r"(?m)^[ \t]*package[ \t]+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)[ \t]*;"
)
VITEST_PATTERNS = ("*.test.ts", "*.test.tsx", "*.spec.ts", "*.spec.tsx")
ARCHIVE_SUFFIX = "+canonical-json+zlib+base64"
EXCLUDED_NODE_CACHE_DIRECTORIES = frozenset(
    {".cache", ".tmp"}
)
EXPECTED_NPM_BIN_LINKS = 14
ISOLATED_SOURCE_EXCLUDED_DIRECTORIES = frozenset(
    {".git", ".npm-cache", "__pycache__", "dist", "node_modules", "target"}
)
ISOLATED_SOURCE_EXCLUDED_SUFFIXES = (".log", ".pyc")
COMMAND_SUPPORT_FILES = (
    ("api/openapi.yaml", "api/openapi.yaml"),
    ("deploy/mysql/flyway-maven.sh", "deploy/mysql/flyway-maven.sh"),
    (
        "deploy/mysql/wave2-local-mysql.sh",
        "deploy/mysql/wave2-local-mysql.sh",
    ),
    (
        "deploy/nginx/shenzhouhr.conf",
        "deploy/nginx/shenzhouhr.conf",
    ),
    (
        "docs/verification/wave2/UIUX.md",
        "docs/verification/wave2/UIUX.md",
    ),
    (
        "design/open-design/v1.9/v19-od-03-people-initial-import.html",
        "design/output/v1.9/v19-od-03-people-initial-import.html",
    ),
    (
        "design/open-design/v1.9/v19-od-04-organization-employment.html",
        "design/output/v1.9/v19-od-04-organization-employment.html",
    ),
    (
        "design/open-design/v1.9/route-to-artifact.json",
        "design/output/v1.9/route-to-artifact.json",
    ),
    (
        "design/open-design/v1.9/page-state-matrix.md",
        "design/output/v1.9/page-state-matrix.md",
    ),
    (
        "design/open-design/v1.9/design-system/DESIGN.md",
        "design/output/v1.9/design-system/DESIGN.md",
    ),
    (
        "design/open-design/v1.9/react-implementation-notes.md",
        "design/output/v1.9/react-implementation-notes.md",
    ),
    (
        "openspec/changes/wave3-attendance-setup-and-policies/"
        "specs/wave3-verification/oracles/w3-retained-registry-v1.json",
        "openspec/changes/wave3-attendance-setup-and-policies/"
        "specs/wave3-verification/oracles/w3-retained-registry-v1.json",
    ),
)
GENERATED_COMMAND_SUPPORT_FILES = (
    (".command-inputs/npm-userconfig", b""),
    (".command-inputs/npm-globalconfig", b""),
)
ARCHIVE_MEMBER_KEYS = {
    "wave3-surefire-xml-archive-v1": {
        "path",
        "sizeBytes",
        "sha256",
        "contentBase64",
    },
    "wave3-vitest-json-archive-v1": {
        "path",
        "sizeBytes",
        "sha256",
        "contentBase64",
    },
    "wave3-dist-content-archive-v1": {
        "path",
        "mode",
        "sizeBytes",
        "sha256",
        "contentBase64",
    },
}


@dataclass(frozen=True)
class Gate:
    evidence_id: str
    slug: str
    marker: str
    primary_filename: str
    secondary_filename: str | None = None


GATES = (
    Gate(
        "W3-VER-BACKEND-FULL",
        "backend-full",
        "W3_BACKEND_FULL=PASS",
        "backend-full-log.log",
        "backend-test-summary.json",
    ),
    Gate(
        "W3-VER-W1-REGRESSION",
        "w1-regression",
        "W3_W1_REGRESSION=PASS",
        "w1-regression-log.log",
    ),
    Gate(
        "W3-VER-W2-REGRESSION",
        "w2-regression",
        "W3_W2_REGRESSION=PASS",
        "w2-regression-log.log",
    ),
    Gate(
        "W3-VER-W3-REGRESSION",
        "w3-regression",
        "W3_W3_REGRESSION=PASS",
        "w3-regression-log.log",
    ),
    Gate(
        "W3-VER-FRONTEND-TYPECHECK",
        "frontend-typecheck",
        "W3_FRONTEND_TYPECHECK=PASS",
        "frontend-typecheck-log.log",
    ),
    Gate(
        "W3-VER-FRONTEND-LINT",
        "frontend-lint",
        "W3_FRONTEND_LINT=PASS",
        "frontend-lint-log.log",
    ),
    Gate(
        "W3-VER-FRONTEND-TESTS",
        "frontend-tests",
        "W3_FRONTEND_TESTS=PASS",
        "frontend-tests-log.log",
        "frontend-test-summary.json",
    ),
    Gate(
        "W3-VER-PROD-BUILD",
        "prod-build",
        "W3_PROD_BUILD=PASS",
        "prod-build-log.log",
        "prod-dist-inventory.json",
    ),
    Gate(
        "W3-VER-DEMO-BUILD",
        "demo-build",
        "W3_DEMO_BUILD=PASS",
        "demo-build-log.log",
        "demo-dist-inventory.json",
    ),
)


class BuildGateError(RuntimeError):
    """Expected fail-closed producer error."""


def fail(message: str) -> None:
    raise BuildGateError(message)


def strict_json_loads(raw: bytes, label: str) -> Any:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                fail(f"{label} contains duplicate JSON key {key!r}")
            result[key] = value
        return result

    def reject_nonstandard_constant(value: str) -> Any:
        fail(f"{label} contains invalid JSON constant {value}")

    try:
        text = raw.decode("utf-8", errors="strict")
        return json.loads(
            text,
            object_pairs_hook=reject_duplicates,
            parse_constant=reject_nonstandard_constant,
        )
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"{label} cannot be parsed: {error}")


def utc_now() -> str:
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )


def utc_iso_from_ns(epoch_ns: int) -> str:
    value = datetime.fromtimestamp(epoch_ns / 1_000_000_000, tz=timezone.utc)
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def reject_forbidden_values(value: Any, label: str) -> None:
    if value is None:
        fail(f"{label} contains a JSON null")
    if isinstance(value, str):
        if FORBIDDEN_STATUS_TOKEN in value:
            fail(f"{label} contains forbidden status token")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                fail(f"{label} contains a non-string object key")
            reject_forbidden_values(item, f"{label}.{key}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            reject_forbidden_values(item, f"{label}[{index}]")
        return
    if not isinstance(value, (bool, int, float)):
        fail(f"{label} contains unsupported value {type(value).__name__}")


def fsync_dir(path: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_CLOEXEC", 0)
    descriptor = os.open(path, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def assert_nofollow_chain(boundary: Path, path: Path, *, final: str) -> os.stat_result:
    """Reject lexical escape and every symlink/non-directory ancestor."""
    boundary_absolute = Path(os.path.abspath(boundary))
    path_absolute = Path(os.path.abspath(path))
    try:
        relative = path_absolute.relative_to(boundary_absolute)
    except ValueError:
        fail(f"path escapes its authority boundary: {path}")
    boundary_metadata = boundary_absolute.lstat()
    if (
        not stat.S_ISDIR(boundary_metadata.st_mode)
        or stat.S_ISLNK(boundary_metadata.st_mode)
    ):
        fail(f"authority boundary is not a real directory: {boundary}")
    current = boundary_absolute
    if not relative.parts:
        metadata = boundary_metadata
    else:
        for index, part in enumerate(relative.parts):
            if part in ("", ".", ".."):
                fail(f"path has an unsafe component: {path}")
            current /= part
            try:
                metadata = current.lstat()
            except OSError as error:
                fail(f"path component is unavailable: {current}: {error}")
            if stat.S_ISLNK(metadata.st_mode):
                fail(f"path component is symbolic: {current}")
            if index < len(relative.parts) - 1 and not stat.S_ISDIR(
                metadata.st_mode
            ):
                fail(f"path ancestor is not a directory: {current}")
    predicates = {
        "file": stat.S_ISREG,
        "dir": stat.S_ISDIR,
        "any": lambda _mode: True,
    }
    if final not in predicates or not predicates[final](metadata.st_mode):
        fail(f"path has the wrong final object type ({final}): {path}")
    return metadata


BOUND_METADATA_FIELDS = (
    "st_dev",
    "st_ino",
    "st_mode",
    "st_size",
    "st_mtime_ns",
    "st_ctime_ns",
)


def metadata_tuple(metadata: os.stat_result) -> tuple[int, ...]:
    return tuple(getattr(metadata, field) for field in BOUND_METADATA_FIELDS)


def require_same_metadata(
    before: os.stat_result,
    after: os.stat_result,
    label: str,
) -> None:
    if metadata_tuple(before) != metadata_tuple(after):
        fail(f"{label} identity or metadata changed")


def directory_anchor_identity(metadata: os.stat_result) -> tuple[int, ...]:
    identity = (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
    )
    if hasattr(metadata, "st_flags"):
        identity += (metadata.st_flags,)
    return identity


def require_same_directory_anchor(
    before: os.stat_result,
    after: os.stat_result,
    label: str,
) -> None:
    if (
        not stat.S_ISDIR(before.st_mode)
        or stat.S_ISLNK(before.st_mode)
        or not stat.S_ISDIR(after.st_mode)
        or stat.S_ISLNK(after.st_mode)
        or directory_anchor_identity(before)
        != directory_anchor_identity(after)
    ):
        fail(f"{label} identity or attributes changed")


def open_absolute_directory_nofollow(path: Path) -> tuple[int, os.stat_result]:
    """Open every absolute directory component through nofollow ``openat``."""
    absolute = Path(os.path.abspath(path))
    flags = (
        os.O_RDONLY
        | os.O_DIRECTORY
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_CLOEXEC", 0)
    )
    descriptor = os.open("/", flags)
    metadata = os.fstat(descriptor)
    try:
        for part in absolute.parts[1:]:
            before = os.stat(part, dir_fd=descriptor, follow_symlinks=False)
            if (
                not stat.S_ISDIR(before.st_mode)
                or stat.S_ISLNK(before.st_mode)
            ):
                fail(
                    "directory chain contains a symbolic/non-directory "
                    f"entry: {path}"
                )
            child = os.open(part, flags, dir_fd=descriptor)
            opened = os.fstat(child)
            try:
                require_same_directory_anchor(
                    before,
                    opened,
                    f"directory chain component {part}",
                )
                named_after = os.stat(
                    part,
                    dir_fd=descriptor,
                    follow_symlinks=False,
                )
                require_same_directory_anchor(
                    opened,
                    named_after,
                    f"directory chain named component {part}",
                )
            except BaseException:
                os.close(child)
                raise
            os.close(descriptor)
            descriptor = child
            metadata = opened
        return descriptor, metadata
    except BaseException:
        os.close(descriptor)
        raise


def read_regular_file_with_ancestors(
    path: Path,
    boundary: Path,
    *,
    boundary_descriptor: int | None = None,
    boundary_metadata: os.stat_result | None = None,
) -> tuple[bytes, os.stat_result, list[dict[str, Any]]]:
    """Read through a held nofollow ``openat`` chain and rebind every name."""
    path_absolute = Path(os.path.abspath(path))
    boundary_absolute = Path(os.path.abspath(boundary))
    try:
        relative = path_absolute.relative_to(boundary_absolute)
    except ValueError:
        fail(f"path escapes its authority boundary: {path}")
    if not relative.parts:
        fail(f"regular file path is the authority directory: {path}")

    directory_flags = (
        os.O_RDONLY
        | os.O_DIRECTORY
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_CLOEXEC", 0)
    )
    file_flags = (
        os.O_RDONLY
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_CLOEXEC", 0)
    )
    if boundary_descriptor is None:
        owned_boundary_descriptor, opened_boundary_metadata = (
            open_absolute_directory_nofollow(boundary_absolute)
        )
    else:
        owned_boundary_descriptor = os.dup(boundary_descriptor)
        opened_boundary_metadata = os.fstat(owned_boundary_descriptor)
        if boundary_metadata is not None:
            require_same_metadata(
                boundary_metadata,
                opened_boundary_metadata,
                f"authority boundary {boundary}",
            )
    descriptors = [owned_boundary_descriptor]
    directory_bindings: list[
        tuple[int, str, os.stat_result, int, str]
    ] = []
    ancestor_records: list[dict[str, Any]] = [
        {
            "relativePath": ".",
            "device": opened_boundary_metadata.st_dev,
            "inode": opened_boundary_metadata.st_ino,
            "mode": stat.S_IMODE(opened_boundary_metadata.st_mode),
            "mtimeEpochNs": opened_boundary_metadata.st_mtime_ns,
            "ctimeEpochNs": opened_boundary_metadata.st_ctime_ns,
        }
    ]
    current_descriptor = owned_boundary_descriptor
    current_parts: list[str] = []
    final_descriptor: int | None = None
    try:
        for part in relative.parts[:-1]:
            if part in {"", ".", ".."}:
                fail(f"path has an unsafe component: {path}")
            before = os.stat(
                part,
                dir_fd=current_descriptor,
                follow_symlinks=False,
            )
            if (
                not stat.S_ISDIR(before.st_mode)
                or stat.S_ISLNK(before.st_mode)
            ):
                fail(f"target ancestor is symbolic/non-directory: {path}")
            child = os.open(part, directory_flags, dir_fd=current_descriptor)
            opened = os.fstat(child)
            require_same_metadata(
                before,
                opened,
                f"target ancestor {part}",
            )
            current_parts.append(part)
            relative_name = PurePosixPath(*current_parts).as_posix()
            directory_bindings.append(
                (
                    current_descriptor,
                    part,
                    opened,
                    child,
                    relative_name,
                )
            )
            descriptors.append(child)
            current_descriptor = child
            ancestor_records.append(
                {
                    "relativePath": relative_name,
                    "device": opened.st_dev,
                    "inode": opened.st_ino,
                    "mode": stat.S_IMODE(opened.st_mode),
                    "mtimeEpochNs": opened.st_mtime_ns,
                    "ctimeEpochNs": opened.st_ctime_ns,
                }
            )

        final_name = relative.parts[-1]
        if final_name in {"", ".", ".."}:
            fail(f"path has an unsafe final component: {path}")
        before = os.stat(
            final_name,
            dir_fd=current_descriptor,
            follow_symlinks=False,
        )
        if not stat.S_ISREG(before.st_mode) or stat.S_ISLNK(before.st_mode):
            fail(f"path has the wrong final object type (file): {path}")
        final_descriptor = os.open(
            final_name,
            file_flags,
            dir_fd=current_descriptor,
        )
        opened = os.fstat(final_descriptor)
        require_same_metadata(before, opened, f"regular file {path}")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(final_descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        after = os.fstat(final_descriptor)
        require_same_metadata(opened, after, f"regular file {path}")

        named_after = os.stat(
            final_name,
            dir_fd=current_descriptor,
            follow_symlinks=False,
        )
        require_same_metadata(
            after,
            named_after,
            f"regular file named entry {path}",
        )
        for (
            parent_descriptor,
            name,
            expected,
            child_descriptor,
            relative_name,
        ) in reversed(directory_bindings):
            require_same_metadata(
                expected,
                os.fstat(child_descriptor),
                f"target ancestor fd {relative_name}",
            )
            require_same_metadata(
                expected,
                os.stat(
                    name,
                    dir_fd=parent_descriptor,
                    follow_symlinks=False,
                ),
                f"target ancestor named entry {relative_name}",
            )
        return b"".join(chunks), after, ancestor_records
    finally:
        if final_descriptor is not None:
            os.close(final_descriptor)
        for descriptor in reversed(descriptors):
            os.close(descriptor)


def read_regular_file(path: Path, boundary: Path) -> tuple[bytes, os.stat_result]:
    contents, metadata, _ancestors = read_regular_file_with_ancestors(
        path,
        boundary,
    )
    return contents, metadata


def sha256_file(path: Path, boundary: Path | None = None) -> str:
    contents, _metadata = read_regular_file(path, boundary or path.parent)
    return hashlib.sha256(contents).hexdigest()


def write_exclusive(path: Path, contents: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_CLOEXEC", 0)
    )
    try:
        descriptor = os.open(path, flags, 0o600)
    except FileExistsError:
        fail(f"refusing to overwrite build-gate artifact: {path}")
    try:
        view = memoryview(contents)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                fail(f"short write while creating artifact: {path}")
            view = view[written:]
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    fsync_dir(path.parent)


def write_json(path: Path, value: Any) -> None:
    reject_forbidden_values(value, path.name)
    write_exclusive(
        path,
        (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    )


def decode_canonical_base64(value: Any, label: str) -> bytes:
    if not isinstance(value, str):
        fail(f"{label} is not a base64 string")
    try:
        encoded = value.encode("ascii")
        decoded = base64.b64decode(encoded, validate=True)
    except (UnicodeEncodeError, ValueError) as error:
        fail(f"{label} is invalid base64: {error}")
    if base64.b64encode(decoded) != encoded:
        fail(f"{label} is not canonical base64")
    return decoded


def validate_archive_path(value: Any, label: str) -> str:
    if (
        not isinstance(value, str)
        or not value
        or "\x00" in value
        or "\\" in value
        or unicodedata.normalize("NFC", value) != value
    ):
        fail(f"{label} is not a canonical relative POSIX path")
    candidate = PurePosixPath(value)
    if (
        not candidate.parts
        or candidate.is_absolute()
        or candidate.as_posix() != value
        or any(part in ("", ".", "..") for part in candidate.parts)
    ):
        fail(f"{label} is not a safe relative POSIX path")
    return value


def validate_compressed_archive(envelope: Any) -> dict[str, Any]:
    """Strictly round-trip and authenticate one embedded raw-byte archive."""
    expected_envelope_keys = {
        "format",
        "memberCount",
        "uncompressedSizeBytes",
        "compressedSizeBytes",
        "uncompressedSha256",
        "compressedSha256",
        "payloadBase64",
    }
    if not isinstance(envelope, dict) or set(envelope) != expected_envelope_keys:
        fail("compressed archive envelope key closure differs")
    format_value = envelope["format"]
    if (
        not isinstance(format_value, str)
        or not format_value.endswith(ARCHIVE_SUFFIX)
    ):
        fail("compressed archive format is invalid")
    inner_format = format_value[: -len(ARCHIVE_SUFFIX)]
    member_keys = ARCHIVE_MEMBER_KEYS.get(inner_format)
    if member_keys is None:
        fail("compressed archive inner format is unsupported")
    for key in (
        "memberCount",
        "uncompressedSizeBytes",
        "compressedSizeBytes",
    ):
        if type(envelope[key]) is not int or envelope[key] < 0:
            fail(f"compressed archive {key} is invalid")
    for key in ("uncompressedSha256", "compressedSha256"):
        if (
            not isinstance(envelope[key], str)
            or re.fullmatch(r"[0-9a-f]{64}", envelope[key]) is None
        ):
            fail(f"compressed archive {key} is invalid")

    compressed = decode_canonical_base64(
        envelope["payloadBase64"], "compressed archive payloadBase64"
    )
    if len(compressed) != envelope["compressedSizeBytes"]:
        fail("compressed archive compressed size differs")
    if hashlib.sha256(compressed).hexdigest() != envelope["compressedSha256"]:
        fail("compressed archive compressed hash differs")
    decompressor = zlib.decompressobj()
    try:
        raw = decompressor.decompress(
            compressed, envelope["uncompressedSizeBytes"] + 1
        )
        if decompressor.unconsumed_tail:
            fail("compressed archive expands beyond its declared size")
        raw += decompressor.flush()
    except zlib.error as error:
        fail(f"compressed archive zlib payload is invalid: {error}")
    if (
        not decompressor.eof
        or decompressor.unused_data
        or decompressor.unconsumed_tail
    ):
        fail("compressed archive zlib stream is not exact")
    if len(raw) != envelope["uncompressedSizeBytes"]:
        fail("compressed archive uncompressed size differs")
    if hashlib.sha256(raw).hexdigest() != envelope["uncompressedSha256"]:
        fail("compressed archive uncompressed hash differs")
    try:
        decoded_text = raw.decode("utf-8")
        archive = json.loads(decoded_text)
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"compressed archive JSON is invalid: {error}")
    if canonical_json(archive) != raw:
        fail("compressed archive JSON is not canonical")
    if (
        not isinstance(archive, dict)
        or set(archive) != {"schemaVersion", "format", "members"}
        or archive["schemaVersion"] != 1
        or type(archive["schemaVersion"]) is not int
        or archive["format"] != inner_format
        or not isinstance(archive["members"], list)
    ):
        fail("compressed archive inner contract differs")
    members = archive["members"]
    if not members or len(members) != envelope["memberCount"]:
        fail("compressed archive member count differs")
    observed_paths: list[str] = []
    for index, member in enumerate(members):
        label = f"compressed archive member[{index}]"
        if not isinstance(member, dict) or set(member) != member_keys:
            fail(f"{label} key closure differs")
        path = validate_archive_path(member["path"], f"{label}.path")
        if path in observed_paths:
            fail(f"{label}.path is duplicated")
        observed_paths.append(path)
        if type(member["sizeBytes"]) is not int or member["sizeBytes"] < 0:
            fail(f"{label}.sizeBytes is invalid")
        if (
            not isinstance(member["sha256"], str)
            or re.fullmatch(r"[0-9a-f]{64}", member["sha256"]) is None
        ):
            fail(f"{label}.sha256 is invalid")
        if "mode" in member and (
            type(member["mode"]) is not int
            or member["mode"] < 0
            or member["mode"] > 0o777
        ):
            fail(f"{label}.mode is invalid")
        contents = decode_canonical_base64(
            member["contentBase64"], f"{label}.contentBase64"
        )
        if len(contents) != member["sizeBytes"]:
            fail(f"{label} raw byte size differs")
        if hashlib.sha256(contents).hexdigest() != member["sha256"]:
            fail(f"{label} raw byte hash differs")
    if observed_paths != sorted(observed_paths, key=lambda value: value.encode("utf-8")):
        fail("compressed archive member paths are not canonically ordered")
    return archive


def compressed_archive(format_name: str, members: Sequence[dict[str, Any]]) -> dict[str, Any]:
    archive = {
        "schemaVersion": 1,
        "format": format_name,
        "members": list(members),
    }
    raw = canonical_json(archive)
    compressed = zlib.compress(raw, level=9)
    envelope = {
        "format": f"{format_name}+canonical-json+zlib+base64",
        "memberCount": len(members),
        "uncompressedSizeBytes": len(raw),
        "compressedSizeBytes": len(compressed),
        "uncompressedSha256": hashlib.sha256(raw).hexdigest(),
        "compressedSha256": hashlib.sha256(compressed).hexdigest(),
        "payloadBase64": base64.b64encode(compressed).decode("ascii"),
    }
    validate_compressed_archive(envelope)
    return envelope


def load_bound_context(run_context_path: Path) -> tuple[Wave3Evidence, Path, dict[str, Any]]:
    if REPOSITORY_ROOT != EXPECTED_REPOSITORY_ROOT or Path.cwd().resolve() != REPOSITORY_ROOT:
        fail("PROJECT_ROOT_SCOPE_ERROR")
    if not run_context_path.is_absolute():
        fail("--run-context must be absolute")
    expected_runs_root = REPOSITORY_ROOT / "docs/verification/wave3/runs"
    assert_nofollow_chain(REPOSITORY_ROOT, expected_runs_root, final="dir")
    run_context_path = Path(os.path.abspath(run_context_path))
    context_bytes, _metadata = read_regular_file(run_context_path, expected_runs_root)
    resolved = run_context_path.resolve(strict=True)
    run_root = resolved.parent
    if resolved != run_root / "run-context.json":
        fail("run context filename must be run-context.json")
    if run_root.parent != expected_runs_root:
        fail("run context must be directly below the W3 runs root")
    verifier = Wave3Evidence(REPOSITORY_ROOT)
    expected_run_root = verifier.run_root(run_root.name)
    if expected_run_root.resolve(strict=True) != run_root.resolve(strict=True):
        fail("run context root differs from verifier resolution")
    try:
        parsed_context = json.loads(context_bytes)
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"run context is invalid JSON: {error}")
    context = verifier._context(run_root)
    if parsed_context != context:
        fail("run context changed between fd-bound read and verifier validation")
    assert_frozen_source(verifier, context)
    return verifier, run_root, context


def assert_frozen_source(verifier: Wave3Evidence, context: dict[str, Any]) -> None:
    snapshot = verifier.compute_source_snapshot()
    if snapshot.tree_hash != context["sourceTreeHash"]:
        fail("current normalized source tree differs from the run context")


def validate_gate_contract(verifier: Wave3Evidence) -> None:
    for gate in GATES:
        contract = verifier.leaf_contracts.get(gate.evidence_id)
        if not contract:
            fail(f"build gate is absent from the evidence contract: {gate.evidence_id}")
        expected_roles = [gate.primary_filename.removesuffix(".log")]
        if gate.secondary_filename:
            expected_roles.append(gate.secondary_filename.rsplit(".", 1)[0])
        if (
            contract["slug"] != gate.slug
            or contract["requiredMarkers"] != [gate.marker]
            or contract["primaryArtifactRole"] != expected_roles[0]
            or contract["requiredArtifactRoles"] != expected_roles
        ):
            fail(f"build gate contract drifted: {gate.evidence_id}")


def safe_environment() -> dict[str, str]:
    # HOME/TMPDIR/JAVA_HOME are deliberately supplied by each command policy.
    # Inheriting them here would reopen user rc/config files or select a Java
    # executable different from the PATH-resolved executable sealed below.
    allowed = ("SYSTEMROOT", "WINDIR")
    environment = {
        key: os.environ[key]
        for key in allowed
        if key in os.environ and os.environ[key]
    }
    environment.update(
        {
            "PATH": deterministic_command_path(),
            "CI": "1",
            "LC_ALL": "C",
            "LANG": "C",
            "TZ": "UTC",
        }
    )
    return environment


@dataclass(frozen=True)
class CommandResult:
    command: tuple[str, ...]
    cwd: Path
    started_at: str
    started_ns: int
    completed_at: str
    completed_ns: int
    stdout: str
    stderr: str
    exit_code: int
    toolchain: dict[str, Any]


def internal_npm_bin_symlink_record(
    authority: str,
    path: Path,
    boundary: Path,
    *,
    watch_target: (
        Callable[[Path, Path, int, os.stat_result], None] | None
    ) = None,
) -> dict[str, Any]:
    """Bind one ordinary npm ``node_modules/.bin`` link without escaping."""
    path = Path(os.path.abspath(path))
    boundary = Path(os.path.abspath(boundary))
    try:
        relative = path.relative_to(boundary)
    except ValueError:
        fail(f"npm executable link escapes its dependency boundary: {path}")
    if (
        len(relative.parts) != 2
        or relative.parts[0] != ".bin"
        or relative.parts[1] in {"", ".", ".."}
    ):
        fail(f"symbolic dependency entry is not an npm .bin link: {path}")
    boundary_descriptor, boundary_metadata = open_absolute_directory_nofollow(
        boundary
    )
    bin_descriptor: int | None = None
    link_descriptor: int | None = None
    try:
        bin_before = os.stat(
            ".bin",
            dir_fd=boundary_descriptor,
            follow_symlinks=False,
        )
        if (
            not stat.S_ISDIR(bin_before.st_mode)
            or stat.S_ISLNK(bin_before.st_mode)
        ):
            fail(f"npm .bin parent is symbolic/non-directory: {path.parent}")
        bin_descriptor = os.open(
            ".bin",
            os.O_RDONLY
            | os.O_DIRECTORY
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_CLOEXEC", 0),
            dir_fd=boundary_descriptor,
        )
        bin_metadata = os.fstat(bin_descriptor)
        require_same_metadata(
            bin_before,
            bin_metadata,
            f"npm .bin parent {path.parent}",
        )
        before = os.stat(
            path.name,
            dir_fd=bin_descriptor,
            follow_symlinks=False,
        )
        if not stat.S_ISLNK(before.st_mode):
            fail(f"npm executable entry is no longer symbolic: {path}")
        symlink_flag = getattr(os, "O_SYMLINK", 0)
        if not symlink_flag:
            fail("fd-bound npm link identity requires O_SYMLINK")
        link_descriptor = os.open(
            path.name,
            getattr(os, "O_EVTONLY", os.O_RDONLY)
            | symlink_flag
            | getattr(os, "O_CLOEXEC", 0),
            dir_fd=bin_descriptor,
        )
        opened = os.fstat(link_descriptor)
        if not stat.S_ISLNK(opened.st_mode):
            fail(f"npm executable link fd followed its target: {path}")
        require_same_metadata(before, opened, f"npm executable link {path}")
        target = os.readlink(path.name, dir_fd=bin_descriptor)
        require_same_metadata(
            opened,
            os.fstat(link_descriptor),
            f"npm executable link fd {path}",
        )
        named_after = os.stat(
            path.name,
            dir_fd=bin_descriptor,
            follow_symlinks=False,
        )
        require_same_metadata(
            opened,
            named_after,
            f"npm executable link named entry {path}",
        )
        if os.readlink(path.name, dir_fd=bin_descriptor) != target:
            fail(f"npm executable link text changed while being read: {path}")
        metadata = opened
        if not target or os.path.isabs(target):
            fail(f"npm .bin link target must be non-empty and relative: {path}")
        target_path = Path(os.path.normpath(os.path.join(path.parent, target)))
        try:
            target_relative = target_path.relative_to(boundary)
        except ValueError:
            fail(f"npm .bin link target escapes its dependency boundary: {path}")
        if not target_relative.parts:
            fail(f"npm .bin link target is not a regular dependency file: {path}")
        if any(
            part in EXCLUDED_NODE_CACHE_DIRECTORIES
            for part in target_relative.parts[:-1]
        ):
            fail(f"npm .bin link target enters an excluded cache tree: {path}")
        if watch_target is not None:
            watch_target(
                target_path,
                boundary,
                boundary_descriptor,
                boundary_metadata,
            )
        contents, target_metadata, target_ancestors = (
            read_regular_file_with_ancestors(
                target_path,
                boundary,
                boundary_descriptor=boundary_descriptor,
                boundary_metadata=boundary_metadata,
            )
        )

        require_same_metadata(
            boundary_metadata,
            os.fstat(boundary_descriptor),
            f"npm dependency boundary fd {boundary}",
        )
        require_same_metadata(
            bin_metadata,
            os.fstat(bin_descriptor),
            f"npm .bin parent fd {path.parent}",
        )
        require_same_metadata(
            bin_metadata,
            os.stat(
                ".bin",
                dir_fd=boundary_descriptor,
                follow_symlinks=False,
            ),
            f"npm .bin named parent {path.parent}",
        )
        require_same_metadata(
            metadata,
            os.fstat(link_descriptor),
            f"npm executable link final fd {path}",
        )
        require_same_metadata(
            metadata,
            os.stat(
                path.name,
                dir_fd=bin_descriptor,
                follow_symlinks=False,
            ),
            f"npm executable link final named entry {path}",
        )
        if os.readlink(path.name, dir_fd=bin_descriptor) != target:
            fail(f"npm executable link text changed before record completion: {path}")
        rebound_descriptor, rebound_metadata = open_absolute_directory_nofollow(
            boundary
        )
        try:
            require_same_metadata(
                boundary_metadata,
                rebound_metadata,
                f"npm dependency boundary named entry {boundary}",
            )
        finally:
            os.close(rebound_descriptor)

        return {
            "authority": authority,
            "path": path.as_posix(),
            "kind": "symlink",
            "target": target,
            "device": metadata.st_dev,
            "inode": metadata.st_ino,
            "mode": stat.S_IMODE(metadata.st_mode),
            "sizeBytes": metadata.st_size,
            "mtimeEpochNs": metadata.st_mtime_ns,
            "ctimeEpochNs": metadata.st_ctime_ns,
            "resolvedTargetRelativePath": target_relative.as_posix(),
            "resolvedTargetDevice": target_metadata.st_dev,
            "resolvedTargetInode": target_metadata.st_ino,
            "resolvedTargetMode": stat.S_IMODE(target_metadata.st_mode),
            "resolvedTargetSizeBytes": target_metadata.st_size,
            "resolvedTargetMtimeEpochNs": target_metadata.st_mtime_ns,
            "resolvedTargetCtimeEpochNs": target_metadata.st_ctime_ns,
            "resolvedTargetSha256": hashlib.sha256(contents).hexdigest(),
            "resolvedTargetAncestors": target_ancestors,
        }
    finally:
        if link_descriptor is not None:
            os.close(link_descriptor)
        if bin_descriptor is not None:
            os.close(bin_descriptor)
        os.close(boundary_descriptor)


def node_dependency_logical_manifest(node_modules: Path) -> dict[str, Any]:
    """Create an absolute-path-independent manifest of the active npm tree."""
    node_modules = Path(os.path.abspath(node_modules))
    root_metadata = assert_nofollow_chain(
        node_modules.parent,
        node_modules,
        final="dir",
    )
    records: list[dict[str, Any]] = []
    link_count = 0
    for directory, directory_names, file_names in os.walk(
        node_modules,
        followlinks=False,
    ):
        directory_path = Path(directory)
        relative_directory = directory_path.relative_to(node_modules)
        directory_metadata = directory_path.lstat()
        if (
            not stat.S_ISDIR(directory_metadata.st_mode)
            or stat.S_ISLNK(directory_metadata.st_mode)
        ):
            fail(f"npm manifest directory became symbolic: {directory_path}")
        records.append(
            {
                "path": (
                    "."
                    if not relative_directory.parts
                    else PurePosixPath(*relative_directory.parts).as_posix()
                ),
                "kind": "directory",
                "mode": stat.S_IMODE(directory_metadata.st_mode),
            }
        )

        retained_directories: list[str] = []
        for name in sorted(
            directory_names,
            key=lambda value: value.encode("utf-8"),
        ):
            candidate = directory_path / name
            metadata = candidate.lstat()
            if stat.S_ISLNK(metadata.st_mode):
                record = internal_npm_bin_symlink_record(
                    "dependency-handoff-node",
                    candidate,
                    node_modules,
                )
                relative = candidate.relative_to(node_modules).as_posix()
                records.append(
                    {
                        "path": relative,
                        "kind": "symlink",
                        "mode": record["mode"],
                        "sizeBytes": record["sizeBytes"],
                        "target": record["target"],
                        "resolvedTargetRelativePath": record[
                            "resolvedTargetRelativePath"
                        ],
                        "resolvedTargetMode": record["resolvedTargetMode"],
                        "resolvedTargetSizeBytes": record[
                            "resolvedTargetSizeBytes"
                        ],
                        "resolvedTargetSha256": record[
                            "resolvedTargetSha256"
                        ],
                    }
                )
                link_count += 1
                continue
            if not stat.S_ISDIR(metadata.st_mode):
                fail(f"npm manifest contains a special directory entry: {candidate}")
            if (
                not relative_directory.parts
                and name in EXCLUDED_NODE_CACHE_DIRECTORIES
            ):
                continue
            retained_directories.append(name)
        directory_names[:] = retained_directories

        for name in sorted(file_names, key=lambda value: value.encode("utf-8")):
            candidate = directory_path / name
            metadata = candidate.lstat()
            relative = candidate.relative_to(node_modules).as_posix()
            if stat.S_ISLNK(metadata.st_mode):
                record = internal_npm_bin_symlink_record(
                    "dependency-handoff-node",
                    candidate,
                    node_modules,
                )
                records.append(
                    {
                        "path": relative,
                        "kind": "symlink",
                        "mode": record["mode"],
                        "sizeBytes": record["sizeBytes"],
                        "target": record["target"],
                        "resolvedTargetRelativePath": record[
                            "resolvedTargetRelativePath"
                        ],
                        "resolvedTargetMode": record["resolvedTargetMode"],
                        "resolvedTargetSizeBytes": record[
                            "resolvedTargetSizeBytes"
                        ],
                        "resolvedTargetSha256": record[
                            "resolvedTargetSha256"
                        ],
                    }
                )
                link_count += 1
                continue
            if not stat.S_ISREG(metadata.st_mode):
                fail(f"npm manifest contains a special file entry: {candidate}")
            contents, opened = read_regular_file(candidate, node_modules)
            records.append(
                {
                    "path": relative,
                    "kind": "file",
                    "mode": stat.S_IMODE(opened.st_mode),
                    "sizeBytes": opened.st_size,
                    "sha256": hashlib.sha256(contents).hexdigest(),
                }
            )

    records.sort(
        key=lambda item: (
            item["path"].encode("utf-8"),
            item["kind"].encode("utf-8"),
        )
    )
    require_same_metadata(
        root_metadata,
        node_modules.lstat(),
        f"npm manifest root {node_modules}",
    )
    return {
        "schemaVersion": 1,
        "kind": "wave3-node-dependency-logical-manifest",
        "recordCount": len(records),
        "linkCount": link_count,
        "recordsSha256": hashlib.sha256(canonical_json(records)).hexdigest(),
        "records": records,
    }


def maven_dependency_logical_manifest(maven_home: Path) -> dict[str, Any]:
    """Bind the complete Maven home without copying absolute inode identities."""
    maven_home = Path(os.path.abspath(maven_home))
    assert_nofollow_chain(maven_home.parent, maven_home, final="dir")
    records: list[dict[str, Any]] = []
    for directory, directory_names, file_names in os.walk(
        maven_home,
        followlinks=False,
    ):
        directory_path = Path(directory)
        relative_directory = directory_path.relative_to(maven_home)
        directory_metadata = directory_path.lstat()
        records.append(
            {
                "path": (
                    "."
                    if not relative_directory.parts
                    else relative_directory.as_posix()
                ),
                "kind": "directory",
                "mode": stat.S_IMODE(directory_metadata.st_mode),
            }
        )
        retained_directories: list[str] = []
        for name in sorted(
            directory_names,
            key=lambda value: value.encode("utf-8"),
        ):
            candidate = directory_path / name
            metadata = candidate.lstat()
            if stat.S_ISLNK(metadata.st_mode):
                fail(f"Maven dependency manifest contains a symbolic directory: {candidate}")
            if not stat.S_ISDIR(metadata.st_mode):
                fail(f"Maven dependency manifest contains a special entry: {candidate}")
            retained_directories.append(name)
        directory_names[:] = retained_directories
        for name in sorted(file_names, key=lambda value: value.encode("utf-8")):
            candidate = directory_path / name
            metadata = candidate.lstat()
            if stat.S_ISLNK(metadata.st_mode):
                fail(f"Maven dependency manifest contains a symbolic file: {candidate}")
            if not stat.S_ISREG(metadata.st_mode):
                fail(f"Maven dependency manifest contains a special file: {candidate}")
            contents, opened = read_regular_file(candidate, maven_home)
            records.append(
                {
                    "path": candidate.relative_to(maven_home).as_posix(),
                    "kind": "file",
                    "mode": stat.S_IMODE(opened.st_mode),
                    "sizeBytes": opened.st_size,
                    "sha256": hashlib.sha256(contents).hexdigest(),
                }
            )
    records.sort(
        key=lambda item: (
            item["path"].encode("utf-8"),
            item["kind"].encode("utf-8"),
        )
    )
    if not records:
        fail("Maven dependency manifest has no active artifacts")
    return {
        "schemaVersion": 1,
        "kind": "wave3-maven-dependency-logical-manifest",
        "recordCount": len(records),
        "recordsSha256": hashlib.sha256(canonical_json(records)).hexdigest(),
        "records": records,
    }


def regular_tree_logical_manifest(
    root: Path,
    *,
    kind: str,
    excluded_top_level: frozenset[str] = frozenset(),
) -> dict[str, Any]:
    root = Path(os.path.abspath(root))
    assert_nofollow_chain(root.parent, root, final="dir")
    root_before = root.lstat()
    records: list[dict[str, Any]] = []
    for directory, directory_names, file_names in os.walk(
        root,
        followlinks=False,
    ):
        directory_path = Path(directory)
        relative_directory = directory_path.relative_to(root)
        directory_metadata = directory_path.lstat()
        if (
            not stat.S_ISDIR(directory_metadata.st_mode)
            or stat.S_ISLNK(directory_metadata.st_mode)
        ):
            fail(f"{kind} manifest contains an unsafe directory: {directory_path}")
        records.append(
            {
                "path": (
                    "."
                    if not relative_directory.parts
                    else relative_directory.as_posix()
                ),
                "kind": "directory",
                "mode": stat.S_IMODE(directory_metadata.st_mode),
            }
        )
        retained_directories: list[str] = []
        for name in sorted(
            directory_names,
            key=lambda value: value.encode("utf-8"),
        ):
            if (
                not relative_directory.parts
                and name in excluded_top_level
            ):
                continue
            candidate = directory_path / name
            metadata = candidate.lstat()
            if (
                not stat.S_ISDIR(metadata.st_mode)
                or stat.S_ISLNK(metadata.st_mode)
            ):
                fail(f"{kind} manifest contains an unsafe entry: {candidate}")
            retained_directories.append(name)
        directory_names[:] = retained_directories
        for name in sorted(
            file_names,
            key=lambda value: value.encode("utf-8"),
        ):
            candidate = directory_path / name
            metadata = candidate.lstat()
            if (
                not stat.S_ISREG(metadata.st_mode)
                or stat.S_ISLNK(metadata.st_mode)
            ):
                fail(f"{kind} manifest contains an unsafe file: {candidate}")
            contents, opened = read_regular_file(candidate, root)
            records.append(
                {
                    "path": candidate.relative_to(root).as_posix(),
                    "kind": "file",
                    "mode": stat.S_IMODE(opened.st_mode),
                    "sizeBytes": opened.st_size,
                    "sha256": hashlib.sha256(contents).hexdigest(),
                }
            )
    records.sort(
        key=lambda item: (
            item["path"].encode("utf-8"),
            item["kind"].encode("utf-8"),
        )
    )
    require_same_metadata(
        root_before,
        root.lstat(),
        f"{kind} manifest root {root}",
    )
    if not records:
        fail(f"{kind} manifest is empty")
    return {
        "schemaVersion": 1,
        "kind": kind,
        "recordCount": len(records),
        "recordsSha256": hashlib.sha256(
            canonical_json(records)
        ).hexdigest(),
    }


def tool_runtime_manifest(
    tool_paths: Mapping[str, Path] | None = None,
) -> dict[str, Any]:
    tools = (
        host_tool_paths()
        if tool_paths is None
        else {name: Path(path) for name, path in tool_paths.items()}
    )
    if set(tools) != {"java", "node", "npm"}:
        fail("tool runtime path closure is not exact")
    for name, path in tools.items():
        if (
            not path.is_absolute()
            or path.resolve(strict=True) != path
            or not path.is_file()
            or path.is_symlink()
            or not os.access(path, os.X_OK)
        ):
            fail(f"tool runtime executable path is unsafe: {name}={path}")
    java = regular_tree_logical_manifest(
        java_runtime_root(tools["java"]),
        kind="wave3-java-runtime-logical-manifest",
    )
    npm_root = npm_runtime_root(tools["npm"])
    npm_package = regular_tree_logical_manifest(
        npm_root,
        kind="wave3-npm-package-logical-manifest",
        excluded_top_level=frozenset({"node_modules"}),
    )
    npm_dependencies = node_dependency_logical_manifest(
        npm_root / "node_modules"
    )
    node = external_executable_receipt(tools["node"])
    logical = {
        "toolPaths": {
            name: tools[name].as_posix()
            for name in ("java", "node", "npm")
        },
        "node": node,
        "java": java,
        "npmPackage": npm_package,
        "npmDependencies": npm_dependencies,
    }
    return {
        "schemaVersion": 1,
        "kind": "wave3-tool-runtime-manifest",
        "logicalManifestSha256": hashlib.sha256(
            canonical_json(logical)
        ).hexdigest(),
        **logical,
    }


def require_equal_tool_runtime(
    expected: Mapping[str, Any],
    actual: Mapping[str, Any],
) -> None:
    if canonical_json(expected) != canonical_json(actual):
        fail("tool runtime logical manifest changed before seal linearization")


def dependency_handoff_manifest(
    node_modules: Path,
    maven_home: Path,
) -> dict[str, Any]:
    node = node_dependency_logical_manifest(node_modules)
    maven = maven_dependency_logical_manifest(maven_home)
    logical = {"node": node, "maven": maven}
    return {
        "schemaVersion": 1,
        "kind": "wave3-dependency-handoff-manifest",
        "logicalManifestSha256": hashlib.sha256(
            canonical_json(logical)
        ).hexdigest(),
        **logical,
    }


def require_equal_dependency_handoff(
    expected: Mapping[str, Any],
    actual: Mapping[str, Any],
) -> None:
    if canonical_json(expected) != canonical_json(actual):
        fail("origin and isolated dependency logical manifests differ")


def isolated_node_cache_anchors(node_modules: Path) -> dict[str, Any]:
    records: list[dict[str, Any]] = []
    for cache_name in sorted(EXCLUDED_NODE_CACHE_DIRECTORIES):
        path = node_modules / cache_name
        metadata = path.lstat()
        if (
            not stat.S_ISDIR(metadata.st_mode)
            or stat.S_ISLNK(metadata.st_mode)
        ):
            fail(f"isolated node cache root is symbolic/non-directory: {path}")
        if any(path.iterdir()):
            fail(f"isolated node cache root is not initially empty: {path}")
        records.append(
            {
                "path": cache_name,
                "device": metadata.st_dev,
                "inode": metadata.st_ino,
                "mode": stat.S_IMODE(metadata.st_mode),
            }
        )
    return {"schemaVersion": 1, "records": records}


def validate_isolated_node_cache_anchor_receipt(
    value: Mapping[str, Any],
) -> list[dict[str, Any]]:
    if (
        not isinstance(value, Mapping)
        or set(value) != {"schemaVersion", "records"}
        or type(value.get("schemaVersion")) is not int
        or value.get("schemaVersion") != 1
        or not isinstance(value.get("records"), list)
    ):
        fail("expected isolated cache anchor receipt is invalid")
    records: list[dict[str, Any]] = []
    expected_names: set[str] = set()
    for raw_record in value["records"]:
        if (
            not isinstance(raw_record, dict)
            or set(raw_record) != {
                "path",
                "device",
                "inode",
                "mode",
            }
        ):
            fail("expected isolated cache anchor record is invalid")
        record = dict(raw_record)
        name = record["path"]
        if (
            not isinstance(name, str)
            or name not in EXCLUDED_NODE_CACHE_DIRECTORIES
            or name in expected_names
            or any(
                type(record[key]) is not int
                for key in ("device", "inode", "mode")
            )
            or record["device"] < 0
            or record["inode"] <= 0
            or record["mode"] < 0
            or record["mode"] > 0o7777
        ):
            fail("expected isolated cache anchor record is invalid")
        expected_names.add(name)
        records.append(record)
    if expected_names != set(EXCLUDED_NODE_CACHE_DIRECTORIES):
        fail("expected isolated cache anchor closure is incomplete")
    return records


@dataclass(frozen=True)
class WatchedWorkspaceCandidate:
    authority: str
    path: Path
    root: Path
    recursive: bool
    strict_rescan: bool
    kind: str
    descriptor: int
    parent_descriptor: int
    name: str
    metadata: os.stat_result
    cache_anchor: bool


class WorkspaceSeal:
    """Two-phase Darwin kqueue seal for source and dependency authorities."""

    def __init__(
        self,
        authorities: Sequence[tuple[str, Path, bool, bool]],
        *,
        internal_npm_bin_boundaries: Mapping[str, Path] | None = None,
        ignored_recursive_directories: Mapping[str, frozenset[str]] | None = None,
        expected_cache_anchors: Mapping[Path, Mapping[str, Any]] | None = None,
    ) -> None:
        if sys.platform != "darwin" or not hasattr(select, "kqueue"):
            fail("transient workspace sealing requires Darwin kqueue")
        self._internal_npm_bin_boundaries = {
            authority: Path(os.path.abspath(boundary))
            for authority, boundary in (
                internal_npm_bin_boundaries or {}
            ).items()
        }
        self._ignored_recursive_directories = {
            authority: frozenset(names)
            for authority, names in (
                ignored_recursive_directories or {}
            ).items()
        }
        self._expected_cache_anchors = {
            Path(os.path.abspath(path)): dict(record)
            for path, record in (expected_cache_anchors or {}).items()
        }
        self._queue = select.kqueue()
        self._descriptors: list[int] = []
        self._watch_descriptors: dict[tuple[int, int, int], int] = {}
        self._descriptor_metadata: dict[int, tuple[int, ...]] = {}
        self._strict_records: list[tuple[Path, dict[str, Any]]] = []
        self._cache_records: list[
            tuple[Path, int, int, str, dict[str, int]]
        ] = []
        self._directory_anchor_descriptors: dict[Path, int] = {}
        self._directory_anchor_records: list[
            tuple[Path, int | None, int, str, dict[str, int]]
        ] = []
        self._closed = False
        self._watch_flags = (
            select.KQ_NOTE_DELETE
            | select.KQ_NOTE_WRITE
            | select.KQ_NOTE_EXTEND
            | select.KQ_NOTE_ATTRIB
            | select.KQ_NOTE_LINK
            | select.KQ_NOTE_RENAME
            | select.KQ_NOTE_REVOKE
        )
        # Cache contents are command-owned and writable. The anchor itself is not.
        self._cache_anchor_watch_flags = (
            select.KQ_NOTE_DELETE
            | select.KQ_NOTE_ATTRIB
            | select.KQ_NOTE_RENAME
            | select.KQ_NOTE_REVOKE
        )
        normalized_authorities = [
            (
                authority,
                Path(os.path.abspath(root)),
                recursive,
                strict_rescan,
            )
            for authority, root, recursive, strict_rescan in authorities
        ]
        candidates: list[WatchedWorkspaceCandidate] = []
        seen_candidates: set[tuple[str, Path]] = set()
        seen_cache_anchors: set[Path] = set()
        parent_descriptors: dict[Path, int] = {}
        try:
            # Phase 1: watch every namespace parent and every candidate first.
            # No content is sampled until this closure is complete.
            for _authority, root, _recursive, _strict in normalized_authorities:
                if root.parent not in parent_descriptors:
                    anchor_descriptor = self._watch_ancestor_chain(
                        root.parent
                    )
                    metadata = os.fstat(anchor_descriptor)
                    parent_descriptors[root.parent] = (
                        self._register_descriptor(
                            os.dup(anchor_descriptor),
                            metadata,
                        )
                    )
            for authority, root, recursive, strict_rescan in normalized_authorities:
                pending: list[tuple[Path, int, str, bool]] = [
                    (
                        root,
                        parent_descriptors[root.parent],
                        root.name,
                        False,
                    )
                ]
                while pending:
                    candidate, parent_descriptor, name, cache_requested = (
                        pending.pop()
                    )
                    identity_key = (authority, candidate)
                    if identity_key in seen_candidates:
                        continue
                    seen_candidates.add(identity_key)
                    (
                        kind,
                        metadata,
                        descriptor,
                        cache_anchor,
                    ) = self._open_and_watch_at(
                        parent_descriptor,
                        name,
                        candidate,
                        cache_anchor_requested=cache_requested,
                    )
                    watched = WatchedWorkspaceCandidate(
                        authority=authority,
                        path=candidate,
                        root=root,
                        recursive=recursive,
                        strict_rescan=strict_rescan,
                        kind=kind,
                        descriptor=descriptor,
                        parent_descriptor=parent_descriptor,
                        name=name,
                        metadata=metadata,
                        cache_anchor=cache_anchor,
                    )
                    candidates.append(watched)
                    if cache_anchor:
                        if (
                            candidate in self._expected_cache_anchors
                            and os.listdir(descriptor)
                        ):
                            fail(
                                "isolated node cache root is not initially "
                                f"empty: {candidate}"
                            )
                        self._bind_cache_anchor(watched)
                        seen_cache_anchors.add(candidate)
                        self._drain_initialization_events()
                        continue
                    if kind == "symlink":
                        if authority not in self._internal_npm_bin_boundaries:
                            fail(
                                "workspace seal encountered an unapproved "
                                f"symbolic object: {candidate}"
                            )
                        self._drain_initialization_events()
                        continue
                    if kind not in {"file", "directory"}:
                        fail(
                            f"workspace seal encountered special object: "
                            f"{candidate}"
                        )
                    if recursive and kind == "directory":
                        try:
                            names = os.listdir(descriptor)
                        except OSError as error:
                            fail(
                                "workspace directory cannot be enumerated "
                                f"through its watched fd: {candidate}: {error}"
                            )
                        if any(
                            not name
                            or name in {".", ".."}
                            or "/" in name
                            for name in names
                        ):
                            fail(
                                f"workspace directory returned an unsafe "
                                f"entry name: {candidate}"
                            )
                        ignored = self._ignored_recursive_directories.get(
                            authority,
                            frozenset(),
                        )
                        for child_name in sorted(
                            names,
                            key=lambda value: value.encode("utf-8"),
                            reverse=True,
                        ):
                            pending.append(
                                (
                                    candidate / child_name,
                                    descriptor,
                                    child_name,
                                    candidate == root
                                    and child_name in ignored,
                                )
                            )
                    self._drain_initialization_events()
            missing_cache_anchors = (
                set(self._expected_cache_anchors) - seen_cache_anchors
            )
            if missing_cache_anchors:
                fail(
                    "expected isolated cache anchors were not sealed: "
                    + ", ".join(
                        path.as_posix()
                        for path in sorted(
                            missing_cache_anchors,
                            key=lambda item: item.as_posix(),
                        )
                    )
                )
            self._drain_initialization_events()

            # Phase 2: every object is already watched. Sample one canonical
            # manifest, then reject any event raised anywhere during sampling.
            records = [
                self._sample_candidate(candidate)
                for candidate in sorted(
                    candidates,
                    key=lambda item: (
                        item.authority.encode("utf-8"),
                        item.path.as_posix().encode("utf-8"),
                    ),
                )
            ]
            self._drain_initialization_events()
        except BaseException:
            self.close()
            raise
        self.receipt = {
            "schemaVersion": 1,
            "sealType": "darwin-kqueue-vnode-two-phase-content-v2",
            "authorityRoots": sorted({label for label, *_rest in authorities}),
            "objectCount": len(records),
            "regularFileCount": sum(
                item["kind"] == "file" for item in records
            ),
            "cacheAnchorCount": len(self._cache_records),
            "contentManifestSha256": hashlib.sha256(
                canonical_json(records)
            ).hexdigest(),
        }

    def _drain_initialization_events(self) -> None:
        self._drain_events(
            "workspace changed while the two-phase seal was established"
        )

    @staticmethod
    def _event_descriptor_path(descriptor: int) -> str:
        try:
            value = fcntl.fcntl(
                descriptor,
                fcntl.F_GETPATH,
                b"\0" * 1024,
            )
            path = value.split(b"\0", 1)[0].decode(
                "utf-8", errors="strict"
            )
        except (OSError, UnicodeError, ValueError):
            return "<fd-path-unavailable>"
        return path if path else "<fd-path-empty>"

    @staticmethod
    def _event_flag_names(flags: int) -> str:
        known = (
            ("DELETE", select.KQ_NOTE_DELETE),
            ("WRITE", select.KQ_NOTE_WRITE),
            ("EXTEND", select.KQ_NOTE_EXTEND),
            ("ATTRIB", select.KQ_NOTE_ATTRIB),
            ("LINK", select.KQ_NOTE_LINK),
            ("RENAME", select.KQ_NOTE_RENAME),
            ("REVOKE", select.KQ_NOTE_REVOKE),
        )
        names = [name for name, value in known if flags & value]
        known_bits = 0
        for _name, value in known:
            known_bits |= value
        unknown = flags & ~known_bits
        if unknown:
            names.append(f"UNKNOWN_0x{unknown:x}")
        return "|".join(names) if names else "NONE"

    def _event_detail(self, event: Any, reason: str) -> str:
        descriptor = int(event.ident)
        path = self._event_descriptor_path(descriptor)
        flags = int(event.fflags)
        return (
            f"reason={reason},fd={descriptor},"
            f"flags={self._event_flag_names(flags)},path={path}"
        )

    def _register_descriptor(
        self,
        descriptor: int,
        metadata: os.stat_result,
        *,
        watch_flags: int | None = None,
    ) -> int:
        effective_flags = (
            self._watch_flags if watch_flags is None else watch_flags
        )
        identity = (metadata.st_dev, metadata.st_ino, effective_flags)
        retained = self._watch_descriptors.get(identity)
        if retained is not None:
            require_same_metadata(
                metadata,
                os.fstat(retained),
                "workspace shared watch descriptor",
            )
            os.close(descriptor)
            return retained
        try:
            self._queue.control(
                [
                    select.kevent(
                        descriptor,
                        filter=select.KQ_FILTER_VNODE,
                        flags=select.KQ_EV_ADD | select.KQ_EV_CLEAR,
                        fflags=effective_flags,
                    )
                ],
                0,
                0,
            )
        except BaseException:
            os.close(descriptor)
            raise
        self._descriptors.append(descriptor)
        self._watch_descriptors[identity] = descriptor
        self._descriptor_metadata[descriptor] = metadata_tuple(metadata)
        self._drain_initialization_events()
        return descriptor

    @staticmethod
    def _directory_anchor_identity(
        metadata: os.stat_result,
    ) -> dict[str, int]:
        identity = {
            "device": metadata.st_dev,
            "inode": metadata.st_ino,
            "mode": metadata.st_mode,
            "uid": metadata.st_uid,
            "gid": metadata.st_gid,
        }
        if hasattr(metadata, "st_flags"):
            identity["flags"] = metadata.st_flags
        return identity

    @staticmethod
    def _require_same_directory_anchor(
        before: os.stat_result,
        after: os.stat_result,
        label: str,
    ) -> None:
        require_same_directory_anchor(before, after, label)

    def _watch_ancestor_chain(self, path: Path) -> int:
        absolute = Path(os.path.abspath(path))
        directory_flags = (
            os.O_RDONLY
            | os.O_DIRECTORY
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_CLOEXEC", 0)
        )
        root = Path("/")
        current_descriptor = self._directory_anchor_descriptors.get(root)
        if current_descriptor is None:
            before = root.lstat()
            descriptor = os.open(root, directory_flags)
            opened = os.fstat(descriptor)
            current_descriptor = self._register_descriptor(
                descriptor,
                opened,
                watch_flags=self._cache_anchor_watch_flags,
            )
            rebound = os.fstat(current_descriptor)
            named_after = root.lstat()
            self._require_same_directory_anchor(
                before,
                opened,
                "workspace root anchor pre-watch",
            )
            self._require_same_directory_anchor(
                opened,
                rebound,
                "workspace root anchor watched fd",
            )
            self._require_same_directory_anchor(
                opened,
                named_after,
                "workspace root anchor named entry",
            )
            self._directory_anchor_descriptors[root] = current_descriptor
            self._directory_anchor_records.append(
                (
                    root,
                    None,
                    current_descriptor,
                    "",
                    self._directory_anchor_identity(opened),
                )
            )
        current = root
        for part in absolute.parts[1:]:
            child_path = current / part
            retained = self._directory_anchor_descriptors.get(child_path)
            if retained is not None:
                current = child_path
                current_descriptor = retained
                continue
            try:
                before = os.stat(
                    part,
                    dir_fd=current_descriptor,
                    follow_symlinks=False,
                )
            except OSError as error:
                fail(
                    f"workspace ancestor is unavailable: "
                    f"{child_path}: {error}"
                )
            if (
                not stat.S_ISDIR(before.st_mode)
                or stat.S_ISLNK(before.st_mode)
            ):
                fail(
                    f"workspace ancestor is symbolic/non-directory: "
                    f"{child_path}"
                )
            try:
                descriptor = os.open(
                    part,
                    directory_flags,
                    dir_fd=current_descriptor,
                )
            except OSError as error:
                fail(
                    f"workspace ancestor cannot be opened: "
                    f"{child_path}: {error}"
                )
            opened = os.fstat(descriptor)
            retained = self._register_descriptor(
                descriptor,
                opened,
                watch_flags=self._cache_anchor_watch_flags,
            )
            watched = os.fstat(retained)
            try:
                named_after = os.stat(
                    part,
                    dir_fd=current_descriptor,
                    follow_symlinks=False,
                )
            except OSError as error:
                fail(
                    f"workspace ancestor disappeared: "
                    f"{child_path}: {error}"
                )
            self._require_same_directory_anchor(
                before,
                opened,
                f"workspace ancestor pre-watch {child_path}",
            )
            self._require_same_directory_anchor(
                opened,
                watched,
                f"workspace ancestor watched fd {child_path}",
            )
            self._require_same_directory_anchor(
                opened,
                named_after,
                f"workspace ancestor named entry {child_path}",
            )
            self._directory_anchor_descriptors[child_path] = retained
            self._directory_anchor_records.append(
                (
                    child_path,
                    current_descriptor,
                    retained,
                    part,
                    self._directory_anchor_identity(opened),
                )
            )
            current = child_path
            current_descriptor = retained
        return current_descriptor

    def _open_and_watch_at(
        self,
        parent_descriptor: int,
        name: str,
        path: Path,
        *,
        cache_anchor_requested: bool = False,
    ) -> tuple[str, os.stat_result, int, bool]:
        descriptor: int | None = None
        try:
            try:
                before = os.stat(
                    name,
                    dir_fd=parent_descriptor,
                    follow_symlinks=False,
                )
            except OSError as error:
                fail(f"workspace candidate is unavailable: {path}: {error}")
            cache_anchor = (
                cache_anchor_requested
                and stat.S_ISDIR(before.st_mode)
                and not stat.S_ISLNK(before.st_mode)
            )
            if stat.S_ISLNK(before.st_mode):
                symlink_flag = getattr(os, "O_SYMLINK", 0)
                if not symlink_flag:
                    fail("fd-bound symbolic authority requires O_SYMLINK")
                flags = (
                    getattr(os, "O_EVTONLY", os.O_RDONLY)
                    | symlink_flag
                    | getattr(os, "O_CLOEXEC", 0)
                )
            else:
                flags = (
                    getattr(os, "O_EVTONLY", os.O_RDONLY)
                    | getattr(os, "O_NOFOLLOW", 0)
                    | getattr(os, "O_CLOEXEC", 0)
                )
            try:
                descriptor = os.open(
                    name,
                    flags,
                    dir_fd=parent_descriptor,
                )
            except OSError as error:
                fail(f"workspace candidate cannot be opened: {path}: {error}")
            opened = os.fstat(descriptor)
            descriptor = self._register_descriptor(
                descriptor,
                opened,
                watch_flags=(
                    self._cache_anchor_watch_flags
                    if cache_anchor
                    else self._watch_flags
                ),
            )
            watched = os.fstat(descriptor)
            try:
                named_after = os.stat(
                    name,
                    dir_fd=parent_descriptor,
                    follow_symlinks=False,
                )
            except OSError as error:
                fail(f"workspace candidate disappeared: {path}: {error}")
            if cache_anchor:
                self._require_same_directory_anchor(
                    before,
                    opened,
                    f"workspace cache anchor pre-watch {path}",
                )
                self._require_same_directory_anchor(
                    opened,
                    watched,
                    f"workspace cache anchor watched fd {path}",
                )
                self._require_same_directory_anchor(
                    opened,
                    named_after,
                    f"workspace cache anchor named entry {path}",
                )
            else:
                require_same_metadata(
                    before,
                    opened,
                    f"workspace candidate pre-watch {path}",
                )
                require_same_metadata(
                    opened,
                    watched,
                    f"workspace candidate watched fd {path}",
                )
                require_same_metadata(
                    opened,
                    named_after,
                    f"workspace candidate named entry {path}",
                )
            if stat.S_ISLNK(opened.st_mode):
                kind = "symlink"
            elif stat.S_ISDIR(opened.st_mode):
                kind = "directory"
            elif stat.S_ISREG(opened.st_mode):
                kind = "file"
            else:
                kind = "special"
            return kind, opened, descriptor, cache_anchor
        finally:
            # A registered descriptor is retained by the seal. Only close a
            # descriptor when registration never completed.
            if descriptor is not None and descriptor not in self._descriptors:
                try:
                    os.close(descriptor)
                except OSError:
                    pass

    def _bind_cache_anchor(
        self,
        candidate: WatchedWorkspaceCandidate,
    ) -> None:
        expected = {
            "device": candidate.metadata.st_dev,
            "inode": candidate.metadata.st_ino,
            "mode": stat.S_IMODE(candidate.metadata.st_mode),
        }
        declared = self._expected_cache_anchors.get(candidate.path)
        if declared is not None:
            declared_identity = {
                "device": declared.get("device"),
                "inode": declared.get("inode"),
                "mode": declared.get("mode"),
            }
            if declared_identity != expected:
                fail(
                    f"isolated node cache root identity changed: "
                    f"{candidate.path}"
                )
        self._cache_records.append(
            (
                candidate.path,
                candidate.parent_descriptor,
                candidate.descriptor,
                candidate.name,
                expected,
            )
        )

    def _sample_candidate(
        self,
        candidate: WatchedWorkspaceCandidate,
    ) -> dict[str, Any]:
        metadata = os.fstat(candidate.descriptor)
        try:
            named = os.stat(
                candidate.name,
                dir_fd=candidate.parent_descriptor,
                follow_symlinks=False,
            )
        except OSError as error:
            fail(
                f"workspace candidate disappeared before sampling: "
                f"{candidate.path}: {error}"
            )
        if candidate.cache_anchor:
            self._require_same_directory_anchor(
                candidate.metadata,
                metadata,
                f"workspace cache anchor pre-sample fd {candidate.path}",
            )
            self._require_same_directory_anchor(
                candidate.metadata,
                named,
                f"workspace cache anchor pre-sample name {candidate.path}",
            )
            identity = self._directory_anchor_identity(metadata)
            return {
                "authority": candidate.authority,
                "path": candidate.path.as_posix(),
                "kind": "writable-cache-anchor",
                **identity,
            }
        require_same_metadata(
            candidate.metadata,
            metadata,
            f"workspace candidate pre-sample fd {candidate.path}",
        )
        require_same_metadata(
            candidate.metadata,
            named,
            f"workspace candidate pre-sample name {candidate.path}",
        )
        if candidate.kind == "symlink":
            boundary = self._internal_npm_bin_boundaries.get(
                candidate.authority
            )
            if boundary is None:
                fail(
                    f"workspace symlink lost its policy: {candidate.path}"
                )
            record = internal_npm_bin_symlink_record(
                candidate.authority,
                candidate.path,
                boundary,
                watch_target=self._watch_target_chain,
            )
            self._strict_records.append((candidate.path, dict(record)))
            return record
        record = {
            "authority": candidate.authority,
            "path": candidate.path.as_posix(),
            "kind": candidate.kind,
            "device": metadata.st_dev,
            "inode": metadata.st_ino,
            "mode": stat.S_IMODE(metadata.st_mode),
            "sizeBytes": metadata.st_size,
            "mtimeEpochNs": metadata.st_mtime_ns,
            "ctimeEpochNs": metadata.st_ctime_ns,
        }
        if candidate.kind == "file":
            read_boundary = (
                candidate.root
                if candidate.recursive
                else candidate.root.parent
            )
            contents, opened = read_regular_file(
                candidate.path,
                read_boundary,
            )
            require_same_metadata(
                metadata,
                opened,
                f"workspace file {candidate.path}",
            )
            record["sha256"] = hashlib.sha256(contents).hexdigest()
        if candidate.strict_rescan:
            self._strict_records.append((candidate.path, dict(record)))
        return record

    def _watch_target_chain(
        self,
        path: Path,
        boundary: Path,
        boundary_descriptor: int,
        boundary_metadata: os.stat_result,
    ) -> None:
        path = Path(os.path.abspath(path))
        boundary = Path(os.path.abspath(boundary))
        try:
            relative = path.relative_to(boundary)
        except ValueError:
            fail(f"npm target watch escapes its boundary: {path}")
        duplicate = os.dup(boundary_descriptor)
        duplicate_metadata = os.fstat(duplicate)
        require_same_metadata(
            boundary_metadata,
            duplicate_metadata,
            f"npm target shared boundary fd {boundary}",
        )
        current_descriptor = self._register_descriptor(
            duplicate,
            duplicate_metadata,
        )
        current = boundary
        for index, part in enumerate(relative.parts):
            current /= part
            (
                kind,
                _metadata,
                current_descriptor,
                cache_anchor,
            ) = self._open_and_watch_at(
                current_descriptor,
                part,
                current,
            )
            if cache_anchor:
                fail(f"npm target watch entered a cache anchor: {current}")
            expected = (
                "file"
                if index == len(relative.parts) - 1
                else "directory"
            )
            if kind != expected:
                fail(
                    f"npm target watch expected {expected} at {current}, "
                    f"found {kind}"
                )
        self._drain_initialization_events()

    def bind_dependency_handoff(
        self,
        expected: Mapping[str, Any],
        actual: Mapping[str, Any],
    ) -> None:
        if self._closed:
            fail("workspace seal is already closed")
        require_equal_dependency_handoff(expected, actual)
        node = actual["node"]
        maven = actual["maven"]
        self.receipt["dependencyHandoff"] = {
            "schemaVersion": 1,
            "comparison": "exact-canonical-json",
            "equal": True,
            "logicalManifestSha256": actual["logicalManifestSha256"],
            "nodeRecordCount": node["recordCount"],
            "nodeLinkCount": node["linkCount"],
            "mavenRecordCount": maven["recordCount"],
        }

    def bind_command_support(
        self,
        expected: Mapping[str, Any],
        actual: Mapping[str, Any],
    ) -> None:
        if self._closed:
            fail("workspace seal is already closed")
        require_equal_command_support(expected, actual)
        self.receipt["commandSupport"] = {
            "schemaVersion": 1,
            "comparison": "exact-canonical-json",
            "equal": True,
            "recordCount": actual["recordCount"],
            "recordsSha256": actual["recordsSha256"],
        }

    def bind_tool_runtime(
        self,
        expected: Mapping[str, Any],
        actual: Mapping[str, Any],
    ) -> None:
        if self._closed:
            fail("workspace seal is already closed")
        require_equal_tool_runtime(expected, actual)
        java = actual["java"]
        npm_package = actual["npmPackage"]
        npm_dependencies = actual["npmDependencies"]
        self.receipt["toolRuntime"] = {
            "schemaVersion": 1,
            "comparison": "exact-canonical-json",
            "equal": True,
            "logicalManifestSha256": actual[
                "logicalManifestSha256"
            ],
            "javaExecutablePath": actual["toolPaths"]["java"],
            "nodeExecutablePath": actual["toolPaths"]["node"],
            "npmExecutablePath": actual["toolPaths"]["npm"],
            "nodeSha256": actual["node"]["sha256"],
            "javaRecordCount": java["recordCount"],
            "javaRecordsSha256": java["recordsSha256"],
            "npmPackageRecordCount": npm_package["recordCount"],
            "npmPackageRecordsSha256": npm_package["recordsSha256"],
            "npmDependencyRecordCount": npm_dependencies["recordCount"],
            "npmDependencyLinkCount": npm_dependencies["linkCount"],
            "npmDependencyRecordsSha256": npm_dependencies[
                "recordsSha256"
            ],
        }

    def _drain_events(self, message: str) -> None:
        event_count = 0
        while True:
            events = self._queue.control(None, 64, 0)
            if not events:
                return
            event_count += len(events)
            if event_count > 1_000_000:
                fail(f"{message}; eventCount exceeds bounded drain")
            fatal_events: list[str] = []
            for event in events:
                if event.fflags != select.KQ_NOTE_ATTRIB:
                    fatal_events.append(
                        self._event_detail(event, "non-attribute-event")
                    )
                    continue
                descriptor = int(event.ident)
                expected = self._descriptor_metadata.get(descriptor)
                if expected is None:
                    fatal_events.append(
                        self._event_detail(
                            event,
                            "attribute-event-without-sealed-descriptor",
                        )
                    )
                    continue
                try:
                    actual = metadata_tuple(os.fstat(descriptor))
                except OSError as error:
                    fatal_events.append(
                        self._event_detail(
                            event,
                            "attribute-event-descriptor-unavailable:"
                            f"{type(error).__name__}",
                        )
                    )
                    continue
                if actual != expected:
                    fatal_events.append(
                        self._event_detail(
                            event,
                            "protected-metadata-changed",
                        )
                    )
            if fatal_events:
                fail(
                    f"{message}; eventCount={event_count}; "
                    f"events={';'.join(fatal_events)}"
                )

    def _drain_runtime_events(self) -> None:
        # APFS may emit NOTE_ATTRIB when a command merely reads a dependency
        # and advances only its unsealed access time. Accept that notification
        # only when the fd-bound protected identity/metadata is still exact.
        # WRITE/EXTEND/DELETE/RENAME/LINK/REVOKE always fail, as does any
        # NOTE_ATTRIB event whose mode/size/mtime/ctime or identity changed.
        self._drain_events(
            "transient workspace/dependency mutation detected by kqueue"
        )

    def assert_unchanged(self) -> None:
        if self._closed:
            fail("workspace seal is already closed")
        self._drain_runtime_events()
        for (
            path,
            parent_descriptor,
            descriptor,
            name,
            expected,
        ) in self._directory_anchor_records:
            try:
                opened = os.fstat(descriptor)
                named = (
                    path.lstat()
                    if parent_descriptor is None
                    else os.stat(
                        name,
                        dir_fd=parent_descriptor,
                        follow_symlinks=False,
                    )
                )
            except OSError as error:
                fail(f"workspace ancestor anchor is unavailable: {path}: {error}")
            if (
                not stat.S_ISDIR(opened.st_mode)
                or stat.S_ISLNK(opened.st_mode)
                or self._directory_anchor_identity(opened) != expected
                or self._directory_anchor_identity(named) != expected
            ):
                fail(f"workspace ancestor anchor identity changed: {path}")
        for path, expected in self._strict_records:
            try:
                metadata = path.lstat()
            except OSError as error:
                fail(f"strict workspace object is unavailable: {path}: {error}")
            actual = {
                "authority": expected["authority"],
                "path": expected["path"],
                "kind": "symlink"
                if stat.S_ISLNK(metadata.st_mode)
                else (
                    "directory"
                    if stat.S_ISDIR(metadata.st_mode)
                    else "file"
                ),
            }
            if actual["kind"] == "symlink":
                boundary = self._internal_npm_bin_boundaries.get(
                    expected["authority"]
                )
                if boundary is None:
                    fail(f"strict workspace symlink lost its policy: {path}")
                actual = internal_npm_bin_symlink_record(
                    expected["authority"],
                    path,
                    boundary,
                )
            else:
                actual.update(
                    {
                        "device": metadata.st_dev,
                        "inode": metadata.st_ino,
                        "mode": stat.S_IMODE(metadata.st_mode),
                        "sizeBytes": metadata.st_size,
                        "mtimeEpochNs": metadata.st_mtime_ns,
                        "ctimeEpochNs": metadata.st_ctime_ns,
                    }
                )
                if actual["kind"] == "file":
                    contents, opened = read_regular_file(path, path.parent)
                    require_same_metadata(
                        metadata,
                        opened,
                        f"strict workspace file {path}",
                    )
                    actual["sha256"] = hashlib.sha256(contents).hexdigest()
            if actual != expected:
                fail(f"strict source/tool identity seal changed: {path}")
        for (
            path,
            parent_descriptor,
            descriptor,
            name,
            expected,
        ) in self._cache_records:
            try:
                named = os.stat(
                    name,
                    dir_fd=parent_descriptor,
                    follow_symlinks=False,
                )
                opened = os.fstat(descriptor)
            except OSError as error:
                fail(f"isolated cache anchor is unavailable: {path}: {error}")
            for metadata in (named, opened):
                actual = {
                    "device": metadata.st_dev,
                    "inode": metadata.st_ino,
                    "mode": stat.S_IMODE(metadata.st_mode),
                }
                if not stat.S_ISDIR(metadata.st_mode) or actual != expected:
                    fail(f"isolated cache anchor identity changed: {path}")
        self._drain_runtime_events()

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        for descriptor in self._descriptors:
            try:
                os.close(descriptor)
            except OSError:
                pass
        self._descriptors.clear()
        self._watch_descriptors.clear()
        self._descriptor_metadata.clear()
        self._directory_anchor_descriptors.clear()
        self._directory_anchor_records.clear()
        try:
            self._queue.close()
        except OSError:
            pass


def resolve_executable(command: str, cwd: Path) -> Path:
    candidate = Path(command)
    if os.path.dirname(command):
        candidate = candidate if candidate.is_absolute() else cwd / candidate
        if not candidate.exists():
            fail(f"command executable does not exist: {command}")
        resolved = candidate.resolve(strict=True)
    else:
        if command in {"java", "node", "npm"}:
            resolved = host_tool_paths()[command]
        else:
            located = shutil.which(
                command,
                path=deterministic_command_path(),
            )
            if not located:
                fail(f"command executable is not installed: {command}")
            resolved = Path(located).resolve(strict=True)
    if not resolved.is_file():
        fail(f"command executable is not a regular file: {resolved}")
    return resolved


def java_runtime_root(executable: Path) -> Path:
    executable = executable.resolve(strict=True)
    if executable.name != "java" or executable.parent.name != "bin":
        fail(f"Java executable has an unsupported runtime layout: {executable}")
    runtime = executable.parent.parent
    modules = runtime / "lib/modules"
    if (
        not runtime.is_dir()
        or runtime.is_symlink()
        or not modules.is_file()
        or modules.is_symlink()
    ):
        fail(f"Java runtime closure is incomplete/unsafe: {runtime}")
    return runtime


def npm_runtime_root(executable: Path) -> Path:
    executable = executable.resolve(strict=True)
    if executable.name not in {"npm", "npm-cli.js"}:
        fail(f"npm executable has an unsupported package layout: {executable}")
    runtime = executable.parent.parent
    package_json = runtime / "package.json"
    cli = runtime / "lib/cli.js"
    dependency_root = runtime / "node_modules"
    if (
        not runtime.is_dir()
        or runtime.is_symlink()
        or not package_json.is_file()
        or package_json.is_symlink()
        or not cli.is_file()
        or cli.is_symlink()
        or not dependency_root.is_dir()
        or dependency_root.is_symlink()
    ):
        fail(f"npm runtime closure is incomplete/unsafe: {runtime}")
    return runtime


def host_tool_paths() -> dict[str, Path]:
    global _HOST_TOOL_PATHS
    if _HOST_TOOL_PATHS is None:
        discovered: dict[str, Path] = {}
        for name in ("java", "node", "npm"):
            located = shutil.which(name, path=HOST_DISCOVERY_PATH)
            if not located:
                fail(f"required host tool is unavailable: {name}")
            try:
                resolved = Path(located).resolve(strict=True)
            except OSError as error:
                fail(f"required host tool cannot be resolved: {name}: {error}")
            if not resolved.is_file() or not os.access(resolved, os.X_OK):
                fail(f"required host tool is not executable: {resolved}")
            discovered[name] = resolved
        java_runtime_root(discovered["java"])
        npm_runtime_root(discovered["npm"])
        if discovered["node"].name != "node":
            fail(
                "Node executable has an unsupported resolved name: "
                f"{discovered['node']}"
            )
        _HOST_TOOL_PATHS = discovered
    return dict(_HOST_TOOL_PATHS)


def deterministic_command_path() -> str:
    return _command_path(
        (Path("/usr/bin"), Path("/bin"), Path("/usr/sbin"), Path("/sbin"))
    )


def _command_path(directories: Sequence[Path]) -> str:
    unique: list[str] = []
    for directory in directories:
        value = directory.as_posix()
        if value not in unique:
            unique.append(value)
    return os.pathsep.join(unique)


def maven_command_path() -> str:
    java = host_tool_paths()["java"]
    return _command_path(
        (
            java.parent,
            Path("/usr/bin"),
            Path("/bin"),
            Path("/usr/sbin"),
            Path("/sbin"),
        )
    )


def frontend_command_path() -> str:
    node = host_tool_paths()["node"]
    return _command_path(
        (
            node.parent,
            Path("/usr/bin"),
            Path("/bin"),
            Path("/usr/sbin"),
            Path("/sbin"),
        )
    )


def stable_tool_runtime_manifest(
    tool_paths: Mapping[str, Path],
) -> dict[str, Any]:
    baseline = tool_runtime_manifest(tool_paths)
    tools = {
        name: Path(path)
        for name, path in tool_paths.items()
    }
    npm_runtime = npm_runtime_root(tools["npm"])
    boundaries = {
        "tool-npm": npm_runtime / "node_modules",
    }
    seal = WorkspaceSeal(
        [
            ("tool-node", tools["node"].parent, False, True),
            ("tool-node", tools["node"], False, True),
            (
                "tool-npm",
                npm_runtime,
                True,
                True,
            ),
            (
                "tool-java",
                java_runtime_root(tools["java"]),
                True,
                True,
            ),
        ],
        internal_npm_bin_boundaries=boundaries,
    )
    try:
        current = tool_runtime_manifest(tools)
        seal.bind_tool_runtime(baseline, current)
        seal.assert_unchanged()
        return current
    finally:
        seal.close()


def stable_origin_dependency_manifest(
    node_modules: Path,
    maven_home: Path,
) -> dict[str, Any]:
    baseline = dependency_handoff_manifest(node_modules, maven_home)
    seal = WorkspaceSeal(
        [
            (
                "frontend-node-modules-origin",
                node_modules,
                True,
                False,
            ),
            (
                "maven-local-cache-origin",
                maven_home,
                True,
                False,
            ),
        ],
        internal_npm_bin_boundaries={
            "frontend-node-modules-origin": node_modules,
        },
        ignored_recursive_directories={
            "frontend-node-modules-origin": (
                EXCLUDED_NODE_CACHE_DIRECTORIES
            ),
        },
    )
    try:
        current = dependency_handoff_manifest(
            node_modules,
            maven_home,
        )
        seal.bind_dependency_handoff(baseline, current)
        seal.assert_unchanged()
        return current
    finally:
        seal.close()


def stable_repository_command_support_manifest() -> dict[str, Any]:
    baseline = command_support_manifest(
        REPOSITORY_ROOT,
        repository_layout=True,
    )
    authorities = [
        (
            "command-support-origin",
            REPOSITORY_ROOT.joinpath(
                *PurePosixPath(repository_relative).parts
            ),
            False,
            True,
        )
        for _destination_relative, repository_relative in COMMAND_SUPPORT_FILES
    ]
    seal = WorkspaceSeal(authorities)
    try:
        current = command_support_manifest(
            REPOSITORY_ROOT,
            repository_layout=True,
        )
        seal.bind_command_support(baseline, current)
        seal.assert_unchanged()
        return current
    finally:
        seal.close()


def receipt_file(path: Path, boundary: Path) -> dict[str, Any]:
    contents, metadata = read_regular_file(path, boundary)
    return {
        "path": path.relative_to(REPOSITORY_ROOT).as_posix(),
        "sizeBytes": metadata.st_size,
        "mode": stat.S_IMODE(metadata.st_mode),
        "device": metadata.st_dev,
        "inode": metadata.st_ino,
        "mtimeEpochNs": metadata.st_mtime_ns,
        "ctimeEpochNs": metadata.st_ctime_ns,
        "sha256": hashlib.sha256(contents).hexdigest(),
    }


def run_version(
    executable: Path,
    arguments: Sequence[str],
    cwd: Path,
    environment: dict[str, str],
) -> str:
    try:
        result = subprocess.run(
            [str(executable), *arguments],
            env=environment,
            cwd=cwd,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        fail(f"tool version receipt failed for {executable}: {error}")
    try:
        output = result.stdout.decode("utf-8", errors="strict").strip()
    except UnicodeError as error:
        fail(f"tool version output is not UTF-8 for {executable}: {error}")
    if result.returncode != 0 or not output or len(output) > 32_768:
        fail(f"tool version receipt is invalid for {executable}")
    return output


def external_executable_receipt(path: Path) -> dict[str, Any]:
    contents, metadata = read_regular_file(path, path.parent)
    return {
        "path": str(path),
        "sizeBytes": metadata.st_size,
        "mode": stat.S_IMODE(metadata.st_mode),
        "device": metadata.st_dev,
        "inode": metadata.st_ino,
        "mtimeEpochNs": metadata.st_mtime_ns,
        "ctimeEpochNs": metadata.st_ctime_ns,
        "sha256": hashlib.sha256(contents).hexdigest(),
    }


def toolchain_receipt(
    command: Sequence[str],
    cwd: Path,
    environment: dict[str, str],
) -> tuple[list[str], dict[str, Any]]:
    executable = resolve_executable(command[0], cwd)
    executable_bytes, executable_metadata = read_regular_file(
        executable, executable.parent
    )
    resolved_command = [str(executable), *command[1:]]
    is_maven = executable.name == "mvnw"
    version_executable = executable
    version_arguments: tuple[str, ...] = ("--version",)
    install_files: list[Path]
    if is_maven:
        java = resolve_executable("java", cwd)
        install_files = [
            cwd / "pom.xml",
            cwd / ".mvn/wrapper/maven-wrapper.properties",
        ]
        wrapper_jar = cwd / ".mvn/wrapper/maven-wrapper.jar"
        if wrapper_jar.exists():
            install_files.append(wrapper_jar)
    else:
        node = resolve_executable("node", cwd)
        npm = resolve_executable("npm", cwd)
        if executable not in (node, npm):
            fail("frontend gate must execute the installed npm entrypoint")
        version_executable = npm
        install_files = [cwd / "package.json", cwd / "package-lock.json"]
        installed_lock = cwd / "node_modules/.package-lock.json"
        if not installed_lock.exists():
            fail("frontend node_modules install-state lock is missing")
        install_files.append(installed_lock)
    install_receipts = [receipt_file(path, REPOSITORY_ROOT) for path in install_files]
    receipt = {
        "schemaVersion": 1,
        "resolvedExecutable": str(executable),
        "executableDevice": executable_metadata.st_dev,
        "executableInode": executable_metadata.st_ino,
        "executableMode": stat.S_IMODE(executable_metadata.st_mode),
        "executableSizeBytes": executable_metadata.st_size,
        "executableMtimeEpochNs": executable_metadata.st_mtime_ns,
        "executableCtimeEpochNs": executable_metadata.st_ctime_ns,
        "executableSha256": hashlib.sha256(executable_bytes).hexdigest(),
        "versionOutput": run_version(
            version_executable, version_arguments, cwd, environment
        ),
        "runtimeExecutables": (
            [external_executable_receipt(java)]
            if is_maven
            else [
                external_executable_receipt(node),
                external_executable_receipt(npm),
            ]
        ),
        "installState": install_receipts,
        "installStateSha256": hashlib.sha256(
            canonical_json(install_receipts)
        ).hexdigest(),
    }
    return resolved_command, receipt


def process_group_exists(process_group_id: int) -> bool:
    try:
        os.killpg(process_group_id, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        # EPERM still proves that a group with this ID exists.  Treat it as
        # live and let the bounded TERM→KILL/ESRCH proof fail closed if it
        # never disappears.
        return True
    return True


def signal_process_group(
    process_group_id: int,
    signal_number: int,
) -> bool:
    try:
        os.killpg(process_group_id, signal_number)
    except ProcessLookupError:
        return False
    except PermissionError:
        # A group containing only a not-yet-reaped leader/zombie can report
        # EPERM on Darwin.  Reaping the direct child below is required before
        # the final ESRCH proof.
        return True
    return True


def wait_for_process_group_absence(
    process_group_id: int,
    timeout_seconds: float,
) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while True:
        if not process_group_exists(process_group_id):
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.02)


def terminate_process_group(
    process: subprocess.Popen[bytes],
    process_group_id: int,
) -> tuple[bytes, bytes]:
    """TERM grace, unconditional KILL attempt, then prove ESRCH/absence."""
    signal_process_group(process_group_id, signal.SIGTERM)
    term_deadline = time.monotonic() + PROCESS_GROUP_TERM_GRACE_SECONDS
    while (
        process_group_exists(process_group_id)
        and time.monotonic() < term_deadline
    ):
        time.sleep(0.02)
    # This call is intentionally unconditional.  ESRCH means the whole group
    # disappeared during TERM grace; otherwise SIGKILL closes the race where
    # the leader/pipes disappeared while an ignoring descendant survived.
    signal_process_group(process_group_id, signal.SIGKILL)
    stdout, stderr = reap_process(process)
    if not wait_for_process_group_absence(
        process_group_id, PROCESS_GROUP_KILL_GRACE_SECONDS
    ):
        fail(
            f"process group survived TERM→KILL cleanup: "
            f"pgid={process_group_id}"
        )
    return stdout, stderr


def reap_process(
    process: subprocess.Popen[bytes],
) -> tuple[bytes, bytes]:
    try:
        return process.communicate(timeout=PROCESS_GROUP_KILL_GRACE_SECONDS)
    except subprocess.TimeoutExpired:
        fail(f"process leader could not be reaped: pid={process.pid}")


def run_command(
    command: Sequence[str],
    cwd: Path,
    timeout_seconds: int,
    workspace_seal: WorkspaceSeal,
    environment_overrides: dict[str, str] | None = None,
) -> CommandResult:
    workspace_seal.assert_unchanged()
    environment = safe_environment()
    if environment_overrides:
        environment.update(environment_overrides)
    resolved_command, before_toolchain = toolchain_receipt(
        command, cwd, environment
    )
    before_toolchain["workspaceSeal"] = workspace_seal.receipt
    workspace_seal.assert_unchanged()
    started_ns = time.time_ns()
    started_at = utc_iso_from_ns(started_ns)
    process: subprocess.Popen[bytes] | None = None
    process_group_id: int | None = None
    cleanup_started = False
    leader_reaped = False
    try:
        process = subprocess.Popen(
            resolved_command,
            cwd=cwd,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
        # start_new_session makes the child PID the stable process-group ID.
        # Retain it even after the leader exits; never infer group liveness from
        # Popen.poll(), which says nothing about surviving descendants.
        process_group_id = process.pid
        try:
            stdout_bytes, stderr_bytes = process.communicate(timeout=timeout_seconds)
            leader_reaped = True
        except subprocess.TimeoutExpired:
            cleanup_started = True
            stdout_bytes, stderr_bytes = terminate_process_group(
                process, process_group_id
            )
            leader_reaped = True
            fail(
                f"command process group timed out and was reaped after {timeout_seconds}s: "
                f"{shlex.join(resolved_command)}; stdoutBytes={len(stdout_bytes)} "
                f"stderrBytes={len(stderr_bytes)}"
            )
        if process_group_exists(process_group_id):
            cleanup_started = True
            terminate_process_group(process, process_group_id)
            fail(
                "command leader exited but left a live process-group descendant; "
                f"pgid={process_group_id}: {shlex.join(resolved_command)}"
            )
    except BaseException:
        if process is not None and process_group_id is not None:
            if not cleanup_started and process_group_exists(process_group_id):
                terminate_process_group(process, process_group_id)
                leader_reaped = True
            if not leader_reaped:
                reap_process(process)
        raise
    completed_ns = time.time_ns()
    workspace_seal.assert_unchanged()
    try:
        stdout = stdout_bytes.decode("utf-8", errors="strict")
        stderr = stderr_bytes.decode("utf-8", errors="strict")
    except UnicodeError as error:
        fail(f"command output is not strict UTF-8: {error}")
    _resolved_after, after_toolchain = toolchain_receipt(
        command, cwd, environment
    )
    after_toolchain["workspaceSeal"] = workspace_seal.receipt
    workspace_seal.assert_unchanged()
    if before_toolchain != after_toolchain:
        fail("toolchain or install state changed during the command")
    command_result = CommandResult(
        command=tuple(resolved_command),
        cwd=cwd,
        started_at=started_at,
        started_ns=started_ns,
        completed_at=utc_iso_from_ns(completed_ns),
        completed_ns=completed_ns,
        stdout=stdout,
        stderr=stderr,
        exit_code=process.returncode,
        toolchain=before_toolchain,
    )
    if command_result.exit_code != 0:
        fail(
            f"command failed with exit {command_result.exit_code}: "
            f"{shlex.join(command_result.command)}"
        )
    return command_result


def assert_command_window(metadata: os.stat_result, result: CommandResult, label: str) -> None:
    for field, value in (
        ("mtime", metadata.st_mtime_ns),
        ("ctime", metadata.st_ctime_ns),
    ):
        if value < result.started_ns or value > result.completed_ns:
            fail(
                f"{label} {field} is outside the producing command window: "
                f"{value} not in [{result.started_ns}, {result.completed_ns}]"
            )


def backend_runtime_classes_receipt(
    classes_root: Path,
    *,
    producing_result: CommandResult | None = None,
) -> dict[str, Any]:
    """Return an absolute-path-independent receipt for target/classes bytes."""
    classes_root = Path(os.path.abspath(classes_root))
    assert_nofollow_chain(REPOSITORY_ROOT, classes_root, final="dir")
    records: list[dict[str, Any]] = []
    for directory, directory_names, file_names in os.walk(
        classes_root,
        followlinks=False,
    ):
        directory_path = Path(directory)
        directory_metadata = directory_path.lstat()
        if (
            not stat.S_ISDIR(directory_metadata.st_mode)
            or stat.S_ISLNK(directory_metadata.st_mode)
        ):
            fail(
                "backend runtime classes contain an unsafe directory: "
                f"{directory_path}"
            )
        retained_directories: list[str] = []
        for name in sorted(
            directory_names,
            key=lambda value: value.encode("utf-8"),
        ):
            candidate = directory_path / name
            metadata = candidate.lstat()
            if (
                not stat.S_ISDIR(metadata.st_mode)
                or stat.S_ISLNK(metadata.st_mode)
            ):
                fail(
                    "backend runtime classes contain a symbolic/special "
                    f"directory: {candidate}"
                )
            retained_directories.append(name)
        directory_names[:] = retained_directories
        for name in sorted(
            file_names,
            key=lambda value: value.encode("utf-8"),
        ):
            candidate = directory_path / name
            metadata = candidate.lstat()
            if (
                not stat.S_ISREG(metadata.st_mode)
                or stat.S_ISLNK(metadata.st_mode)
            ):
                fail(
                    "backend runtime classes contain a symbolic/special "
                    f"file: {candidate}"
                )
            contents, opened = read_regular_file(candidate, classes_root)
            if producing_result is not None and candidate.suffix == ".class":
                assert_command_window(
                    opened,
                    producing_result,
                    (
                        "backend-full runtime class "
                        f"{candidate.relative_to(classes_root).as_posix()}"
                    ),
                )
            records.append(
                {
                    "path": candidate.relative_to(classes_root).as_posix(),
                    "mode": stat.S_IMODE(opened.st_mode),
                    "sizeBytes": len(contents),
                    "sha256": hashlib.sha256(contents).hexdigest(),
                }
            )
    records.sort(key=lambda item: item["path"].encode("utf-8"))
    class_records = [
        record for record in records if str(record["path"]).endswith(".class")
    ]
    main_class_path = "com/szsemicon/hr/ShenzhouHrApplication.class"
    main_class_records = [
        record for record in class_records if record["path"] == main_class_path
    ]
    if not records or not class_records or len(main_class_records) != 1:
        fail(
            "backend runtime classes receipt lacks a non-empty exact main "
            "class closure"
        )
    return {
        "schemaVersion": 1,
        "kind": "wave3-backend-runtime-classes-receipt",
        "logicalRoot": "backend/target/classes",
        "fileCount": len(records),
        "classFileCount": len(class_records),
        "totalSizeBytes": sum(record["sizeBytes"] for record in records),
        "recordsSha256": hashlib.sha256(canonical_json(records)).hexdigest(),
        "mainClassPath": main_class_path,
        "mainClassSha256": main_class_records[0]["sha256"],
        "records": records,
    }


def context_line(
    verifier: Wave3Evidence,
    context: dict[str, Any],
    evidence_id: str,
) -> str:
    line = verifier.context_line(context, evidence_id)
    if not line.startswith(CONTEXT_PREFIX):
        fail("verifier context-line contract drifted")
    return line


def render_primary_log(
    verifier: Wave3Evidence,
    context: dict[str, Any],
    gate: Gate,
    result: CommandResult,
    summary_lines: Iterable[str],
) -> bytes:
    sections = [
        context_line(verifier, context, gate.evidence_id),
        f"W3_GATE_STARTED_AT={result.started_at}",
        f"W3_GATE_STARTED_NS={result.started_ns}",
        f"W3_GATE_COMPLETED_AT={result.completed_at}",
        f"W3_GATE_COMPLETED_NS={result.completed_ns}",
        f"W3_GATE_WORKDIR={result.cwd.relative_to(REPOSITORY_ROOT).as_posix()}",
        f"W3_GATE_COMMAND={shlex.join(result.command)}",
        f"W3_TOOLCHAIN_RECEIPT_JSON={canonical_json(result.toolchain).decode('utf-8')}",
        "W3_GATE_STDOUT_BEGIN",
        result.stdout.rstrip("\n"),
        "W3_GATE_STDOUT_END",
        "W3_GATE_STDERR_BEGIN",
        result.stderr.rstrip("\n"),
        "W3_GATE_STDERR_END",
        f"W3_GATE_EXIT_CODE={result.exit_code}",
        *summary_lines,
        gate.marker,
        "",
    ]
    text = "\n".join(sections)
    if text.count(context_line(verifier, context, gate.evidence_id)) != 1:
        fail(f"{gate.evidence_id} primary context is not unique")
    if text.count(gate.marker) != 1:
        fail(f"{gate.evidence_id} primary marker is not unique")
    if FORBIDDEN_STATUS_TOKEN in text:
        fail(f"{gate.evidence_id} primary contains forbidden status token")
    return text.encode("utf-8")


def discover_java_tests(directory: Path) -> tuple[str, ...]:
    assert_nofollow_chain(REPOSITORY_ROOT, directory, final="dir")
    names: list[str] = []
    for path in sorted(directory.rglob("*Test.java"), key=lambda item: item.as_posix()):
        contents, _metadata = read_regular_file(path, REPOSITORY_ROOT)
        try:
            source = contents.decode("utf-8", errors="strict")
        except UnicodeError as error:
            fail(f"Java test source is not UTF-8: {path}: {error}")
        match = JAVA_PACKAGE.search(source)
        if not match:
            fail(f"Java test source lacks an explicit package: {path}")
        names.append(f"{match.group(1)}.{path.stem}")
    if not names or len(names) != len(set(names)):
        fail(f"test selection is empty or has duplicate FQCNs: {directory}")
    return tuple(names)


def discover_vitest_files() -> tuple[str, ...]:
    root = FRONTEND_ROOT / "src"
    assert_nofollow_chain(REPOSITORY_ROOT, root, final="dir")
    candidates: set[Path] = set()
    for pattern in VITEST_PATTERNS:
        candidates.update(root.rglob(pattern))
    files = tuple(
        path.relative_to(FRONTEND_ROOT).as_posix()
        for path in sorted(candidates, key=lambda item: item.as_posix())
        if path.is_file()
    )
    if not files or len(files) != len(set(files)):
        fail("frontend Vitest source-file closure is empty or duplicated")
    for path in files:
        read_regular_file(FRONTEND_ROOT / path, REPOSITORY_ROOT)
    return files


def secure_remove_tree(
    path: Path, boundary: Path, *, allow_internal_symlinks: bool = False
) -> None:
    """Remove only a no-symlink tree whose opened directory keeps its identity."""
    metadata = assert_nofollow_chain(boundary, path, final="dir")
    flags = os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        opened = os.fstat(descriptor)
        if (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino):
            fail(f"directory identity changed before removal: {path}")
        _remove_directory_contents(
            descriptor, path, allow_internal_symlinks=allow_internal_symlinks
        )
    finally:
        os.close(descriptor)
    current = path.lstat()
    if (current.st_dev, current.st_ino) != (metadata.st_dev, metadata.st_ino):
        fail(f"directory identity changed before final removal: {path}")
    os.rmdir(path)
    fsync_dir(path.parent)


def _remove_directory_contents(
    descriptor: int,
    display_path: Path,
    *,
    allow_internal_symlinks: bool,
) -> None:
    with os.scandir(descriptor) as entries:
        names = sorted((entry.name for entry in entries), key=lambda value: value.encode())
    for name in names:
        metadata = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
        child_display = display_path / name
        if stat.S_ISLNK(metadata.st_mode):
            if not allow_internal_symlinks:
                fail(
                    f"refusing to remove a tree containing a symlink: "
                    f"{child_display}"
                )
            current = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            if (current.st_dev, current.st_ino) != (
                metadata.st_dev,
                metadata.st_ino,
            ):
                fail(f"symlink changed before unlink: {child_display}")
            os.unlink(name, dir_fd=descriptor)
            continue
        if stat.S_ISDIR(metadata.st_mode):
            child_fd = os.open(
                name,
                os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=descriptor,
            )
            try:
                opened = os.fstat(child_fd)
                if (opened.st_dev, opened.st_ino) != (metadata.st_dev, metadata.st_ino):
                    fail(f"directory changed during removal: {child_display}")
                _remove_directory_contents(
                    child_fd,
                    child_display,
                    allow_internal_symlinks=allow_internal_symlinks,
                )
            finally:
                os.close(child_fd)
            current = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            if (current.st_dev, current.st_ino) != (metadata.st_dev, metadata.st_ino):
                fail(f"directory changed before removal: {child_display}")
            os.rmdir(name, dir_fd=descriptor)
        elif stat.S_ISREG(metadata.st_mode):
            current = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
            if (current.st_dev, current.st_ino) != (metadata.st_dev, metadata.st_ino):
                fail(f"file changed before removal: {child_display}")
            os.unlink(name, dir_fd=descriptor)
        else:
            fail(f"refusing to remove a special filesystem object: {child_display}")
    os.fsync(descriptor)


def clear_surefire_reports(backend_root: Path) -> None:
    target_root = backend_root / "target"
    if target_root.exists():
        assert_nofollow_chain(REPOSITORY_ROOT, target_root, final="dir")
    report_root = target_root / "surefire-reports"
    if report_root.exists() or report_root.is_symlink():
        secure_remove_tree(report_root, REPOSITORY_ROOT)


def maven_command(
    selected_classes: Sequence[str] | None,
    local_repository: Path | None = None,
) -> list[str]:
    command = ["./mvnw", "--offline"]
    if local_repository is not None:
        command.append(f"-Dmaven.repo.local={local_repository}")
    if selected_classes is None:
        command.append("clean")
    else:
        command.append(f"-Dtest={','.join(selected_classes)}")
    command.append("test")
    return command


def maven_environment(isolated_maven_home: Path) -> dict[str, str]:
    workspace = isolated_maven_home.parent
    return {
        "HOME": str(workspace),
        "PATH": maven_command_path(),
        "TMPDIR": str(workspace / "frontend/node_modules/.tmp"),
        "MAVEN_USER_HOME": str(isolated_maven_home),
        "MAVEN_SKIP_RC": "1",
        # target is created empty before the seal. Preserve that directory
        # identity while clean remains semantically fresh. Fix Java user.home
        # to the same private workspace so Maven settings/toolchains cannot
        # fall back to the operator's live ~/.m2.
        "MAVEN_OPTS": (
            f"-Duser.home={workspace} "
            "-Dmaven.clean.excludeDefaultDirectories=true"
        ),
    }


def frontend_environment(frontend_root: Path) -> dict[str, str]:
    workspace = frontend_root.parent
    command_inputs = workspace / ".command-inputs"
    return {
        "HOME": str(workspace),
        "PATH": frontend_command_path(),
        "TMPDIR": str(frontend_root / "node_modules/.tmp"),
        "NPM_CONFIG_CACHE": str(frontend_root / "node_modules/.cache"),
        "NPM_CONFIG_USERCONFIG": str(command_inputs / "npm-userconfig"),
        "NPM_CONFIG_GLOBALCONFIG": str(
            command_inputs / "npm-globalconfig"
        ),
    }


def _int_attribute(root: ET.Element, key: str, report_name: str) -> int:
    raw = root.attrib.get(key)
    if raw is None or not re.fullmatch(r"\d+", raw):
        fail(f"Surefire report has missing/invalid {key}: {report_name}")
    value = int(raw)
    if value < 0:
        fail(f"Surefire report has negative {key}: {report_name}")
    return value


def parse_surefire_reports(
    report_root: Path,
    expected_classes: Sequence[str],
    result: CommandResult | None = None,
) -> dict[str, Any]:
    assert_nofollow_chain(REPOSITORY_ROOT, report_root, final="dir")
    reports = sorted(report_root.glob("TEST-*.xml"), key=lambda item: item.name)
    if not reports:
        fail("Maven completed without Surefire XML reports")
    if len({report.name for report in reports}) != len(reports):
        fail("Surefire report filename closure is duplicated")
    suites: list[dict[str, Any]] = []
    archive_members: list[dict[str, Any]] = []
    total_tests = total_failures = total_errors = total_skipped = 0
    observed_classes: set[str] = set()
    for report in reports:
        raw, metadata = read_regular_file(report, REPOSITORY_ROOT)
        if result is not None:
            assert_command_window(metadata, result, report.name)
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as error:
            fail(f"Surefire report cannot be parsed: {report.name}: {error}")
        if root.tag != "testsuite":
            fail(f"Surefire report root is not testsuite: {report.name}")
        class_name = root.attrib.get("name", "")
        if not re.fullmatch(
            r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+Test", class_name
        ):
            fail(f"Surefire report has invalid FQCN suite name: {report.name}")
        values = {
            key: _int_attribute(root, key, report.name)
            for key in ("tests", "failures", "errors", "skipped")
        }
        testcases = list(root.findall("testcase"))
        child_counts = {"failures": 0, "errors": 0, "skipped": 0}
        for testcase in testcases:
            statuses = [
                child
                for child in testcase
                if child.tag in ("failure", "error", "skipped")
            ]
            if len(statuses) > 1:
                fail(f"Surefire testcase has multiple terminal states: {report.name}")
            if statuses:
                status_key = {
                    "failure": "failures",
                    "error": "errors",
                    "skipped": "skipped",
                }[statuses[0].tag]
                child_counts[status_key] += 1
        if values["tests"] <= 0 or len(testcases) != values["tests"]:
            fail(f"Surefire suite is empty or testcase count disagrees: {report.name}")
        if any(values[key] != child_counts[key] for key in child_counts):
            fail(f"Surefire suite attributes disagree with testcase children: {report.name}")
        if values["failures"] or values["errors"] or values["skipped"]:
            fail(f"Surefire XML contains failed, errored, or skipped tests: {report.name}")
        if class_name in observed_classes:
            fail(f"Surefire suite FQCN is duplicated: {class_name}")
        observed_classes.add(class_name)
        total_tests += values["tests"]
        total_failures += values["failures"]
        total_errors += values["errors"]
        total_skipped += values["skipped"]
        report_sha = hashlib.sha256(raw).hexdigest()
        try:
            report_relative = report.relative_to(BACKEND_ROOT).as_posix()
        except ValueError:
            report_relative = report.relative_to(REPOSITORY_ROOT).as_posix()
        suites.append(
            {
                "className": class_name,
                "tests": values["tests"],
                "failures": values["failures"],
                "errors": values["errors"],
                "skipped": values["skipped"],
                "reportPath": report_relative,
                "reportSizeBytes": len(raw),
                "reportMtimeEpochNs": metadata.st_mtime_ns,
                "reportCtimeEpochNs": metadata.st_ctime_ns,
                "reportSha256": report_sha,
            }
        )
        archive_members.append(
            {
                "path": report.name,
                "sizeBytes": len(raw),
                "sha256": report_sha,
                "contentBase64": base64.b64encode(raw).decode("ascii"),
            }
        )
    expected = set(expected_classes)
    if len(expected) != len(expected_classes) or observed_classes != expected:
        fail(
            "Maven test FQCN closure differs; "
            f"missing={sorted(expected - observed_classes)}, "
            f"extra={sorted(observed_classes - expected)}"
        )
    if total_tests <= 0 or total_failures or total_errors or total_skipped:
        fail("Maven gate does not prove non-empty zero-skip success")
    return {
        "suiteCount": len(suites),
        "tests": total_tests,
        "failures": total_failures,
        "errors": total_errors,
        "skipped": total_skipped,
        "expectedTestClasses": list(expected_classes),
        "suites": suites,
        "rawSurefireArchive": compressed_archive(
            "wave3-surefire-xml-archive-v1", archive_members
        ),
    }


def structured_test_summary(
    context: dict[str, Any],
    gate: Gate,
    result: CommandResult,
    parsed: dict[str, Any],
    node_type: str,
) -> dict[str, Any]:
    return {
        "schemaVersion": 2,
        "nodeType": node_type,
        "evidenceId": gate.evidence_id,
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "verdict": "PASS",
        "marker": gate.marker,
        "command": list(result.command),
        "startedAt": result.started_at,
        "startedAtEpochNs": result.started_ns,
        "completedAt": result.completed_at,
        "completedAtEpochNs": result.completed_ns,
        "toolchain": result.toolchain,
        **parsed,
    }


def parse_vitest_report(
    report_path: Path,
    expected_files: Sequence[str],
    result: CommandResult | None = None,
    frontend_root: Path = FRONTEND_ROOT,
) -> dict[str, Any]:
    raw, metadata = read_regular_file(report_path, REPOSITORY_ROOT)
    if result is not None:
        assert_command_window(metadata, result, report_path.name)
    report = strict_json_loads(raw, "Vitest JSON report")
    required = (
        "numTotalTestSuites",
        "numPassedTestSuites",
        "numFailedTestSuites",
        "numPendingTestSuites",
        "numTotalTests",
        "numPassedTests",
        "numFailedTests",
        "numPendingTests",
        "numTodoTests",
        "success",
        "testResults",
    )
    if not isinstance(report, dict) or any(key not in report for key in required):
        fail("Vitest JSON report is incomplete")
    integer_keys = (
        "numTotalTestSuites",
        "numPassedTestSuites",
        "numFailedTestSuites",
        "numPendingTestSuites",
        "numTotalTests",
        "numPassedTests",
        "numFailedTests",
        "numPendingTests",
        "numTodoTests",
    )
    for key in integer_keys:
        if type(report[key]) is not int or report[key] < 0:
            fail(f"Vitest JSON report has invalid {key}")
    test_results = report["testResults"]
    if not isinstance(test_results, list):
        fail("Vitest JSON testResults must be an array")
    observed_files: list[str] = []
    observed_assertions = 0
    for index, test_result in enumerate(test_results):
        if not isinstance(test_result, dict):
            fail(f"Vitest testResults[{index}] is not an object")
        name = test_result.get("name")
        assertions = test_result.get("assertionResults")
        if not isinstance(name, str) or not isinstance(assertions, list):
            fail(f"Vitest testResults[{index}] lacks name/assertionResults")
        if test_result.get("status") != "passed":
            fail(f"Vitest testResults[{index}] status is not passed")
        if not assertions:
            fail(f"Vitest testResults[{index}] has no assertions")
        candidate = Path(name)
        if not candidate.is_absolute():
            candidate = frontend_root / candidate
        try:
            relative = (
                candidate.resolve(strict=True)
                .relative_to(frontend_root.resolve(strict=True))
                .as_posix()
            )
        except (OSError, ValueError):
            fail(f"Vitest reported a file outside frontend: {name}")
        read_regular_file(FRONTEND_ROOT / relative, REPOSITORY_ROOT)
        observed_files.append(relative)
        for assertion in assertions:
            if not isinstance(assertion, dict) or assertion.get("status") != "passed":
                fail(f"Vitest assertion is not passed in {relative}")
        observed_assertions += len(assertions)
    expected = list(expected_files)
    if (
        len(observed_files) != len(set(observed_files))
        or set(observed_files) != set(expected)
    ):
        fail(
            "Vitest test-file closure differs; "
            f"missing={sorted(set(expected) - set(observed_files))}, "
            f"extra={sorted(set(observed_files) - set(expected))}"
        )
    if (
        report["success"] is not True
        or report["numTotalTestSuites"] <= 0
        or report["numPassedTestSuites"] != report["numTotalTestSuites"]
        or report["numFailedTestSuites"] != 0
        or report["numPendingTestSuites"] != 0
        or report["numTotalTests"] <= 0
        or report["numPassedTests"] != report["numTotalTests"]
        or report["numFailedTests"] != 0
        or report["numPendingTests"] != 0
        or report["numTodoTests"] != 0
        or observed_assertions != report["numTotalTests"]
    ):
        fail("Vitest JSON does not prove exact non-empty zero-pending success")
    archive_member = {
        "path": report_path.name,
        "sizeBytes": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "contentBase64": base64.b64encode(raw).decode("ascii"),
    }
    return {
        "suiteCount": report["numTotalTestSuites"],
        "passedSuites": report["numPassedTestSuites"],
        "failedSuites": report["numFailedTestSuites"],
        "pendingSuites": report["numPendingTestSuites"],
        "tests": report["numTotalTests"],
        "passed": report["numPassedTests"],
        "failed": report["numFailedTests"],
        "pending": report["numPendingTests"],
        "expectedTestFiles": expected,
        "observedTestFiles": observed_files,
        "rawVitestArchive": compressed_archive(
            "wave3-vitest-json-archive-v1", [archive_member]
        ),
    }


def dist_inventory(
    dist_root: Path,
    context: dict[str, Any],
    gate: Gate,
    result: CommandResult,
) -> dict[str, Any]:
    assert_nofollow_chain(REPOSITORY_ROOT, dist_root, final="dir")
    files: list[dict[str, Any]] = []
    archive_members: list[dict[str, Any]] = []
    for directory, directory_names, file_names in os.walk(dist_root, followlinks=False):
        directory_names.sort(key=lambda value: value.encode("utf-8"))
        file_names.sort(key=lambda value: value.encode("utf-8"))
        directory_path = Path(directory)
        assert_nofollow_chain(REPOSITORY_ROOT, directory_path, final="dir")
        for name in directory_names:
            assert_nofollow_chain(
                REPOSITORY_ROOT, directory_path / name, final="dir"
            )
        for name in file_names:
            candidate = directory_path / name
            contents, metadata = read_regular_file(candidate, REPOSITORY_ROOT)
            assert_command_window(metadata, result, candidate.as_posix())
            if (
                metadata.st_mtime_ns <= context["sourceMaximumMtimeNs"]
                or metadata.st_ctime_ns <= context["sourceMaximumMtimeNs"]
            ):
                fail(f"build output is not newer than frozen source: {candidate}")
            relative = candidate.relative_to(dist_root).as_posix()
            content_sha = hashlib.sha256(contents).hexdigest()
            files.append(
                {
                    "path": relative,
                    "sizeBytes": metadata.st_size,
                    "mode": stat.S_IMODE(metadata.st_mode),
                    "device": metadata.st_dev,
                    "inode": metadata.st_ino,
                    "mtimeEpochNs": metadata.st_mtime_ns,
                    "ctimeEpochNs": metadata.st_ctime_ns,
                    "sha256": content_sha,
                }
            )
            archive_members.append(
                {
                    "path": relative,
                    "mode": stat.S_IMODE(metadata.st_mode),
                    "sizeBytes": len(contents),
                    "sha256": content_sha,
                    "contentBase64": base64.b64encode(contents).decode("ascii"),
                }
            )
    files.sort(key=lambda item: item["path"].encode("utf-8"))
    archive_members.sort(key=lambda item: item["path"].encode("utf-8"))
    if not files or not any(item["path"] == "index.html" for item in files):
        fail("build inventory is empty or lacks index.html")
    archive = compressed_archive("wave3-dist-content-archive-v1", archive_members)
    manifest_sha = hashlib.sha256(canonical_json(files)).hexdigest()
    return {
        "schemaVersion": 2,
        "nodeType": "wave3-build-inventory",
        "evidenceId": gate.evidence_id,
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "verdict": "PASS",
        "marker": gate.marker,
        "mode": "demo" if gate.slug == "demo-build" else "production",
        "command": list(result.command),
        "startedAt": result.started_at,
        "startedAtEpochNs": result.started_ns,
        "completedAt": result.completed_at,
        "completedAtEpochNs": result.completed_ns,
        "toolchain": result.toolchain,
        "fileCount": len(files),
        "totalSizeBytes": sum(item["sizeBytes"] for item in files),
        "manifestSha256": manifest_sha,
        "files": files,
        "contentAddressedArchive": archive,
    }


def create_stage(run_root: Path) -> Path:
    stage = Path(tempfile.mkdtemp(prefix=".build-gates-tmp-", dir=run_root))
    assert_nofollow_chain(run_root, stage, final="dir")
    for gate in GATES:
        (stage / gate.slug).mkdir(mode=0o700)
    fsync_dir(run_root)
    fsync_dir(stage)
    return stage


def clone_or_copy_file(source: str, target: str) -> str:
    if sys.platform == "darwin":
        libc = ctypes.CDLL(None, use_errno=True)
        clonefile = getattr(libc, "clonefile", None)
        if clonefile is not None:
            clonefile.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
            clonefile.restype = ctypes.c_int
            if clonefile(os.fsencode(source), os.fsencode(target), 0) == 0:
                return target
            error_number = ctypes.get_errno()
            if error_number not in (18, 45, 95):
                raise OSError(error_number, os.strerror(error_number), target)
    return shutil.copy2(source, target)


def ignore_origin_node_caches(
    directory: str,
    names: list[str],
) -> set[str]:
    if Path(os.path.abspath(directory)) != FRONTEND_ROOT / "node_modules":
        return set()
    return set(names).intersection(EXCLUDED_NODE_CACHE_DIRECTORIES)


def command_support_manifest(
    root: Path,
    *,
    repository_layout: bool,
) -> dict[str, Any]:
    root = Path(os.path.abspath(root))
    records: list[dict[str, Any]] = []
    for destination_relative, repository_relative in COMMAND_SUPPORT_FILES:
        relative = (
            repository_relative
            if repository_layout
            else destination_relative
        )
        path = root.joinpath(*PurePosixPath(relative).parts)
        contents, metadata = read_regular_file(path, root)
        records.append(
            {
                "path": destination_relative,
                "mode": stat.S_IMODE(metadata.st_mode),
                "sizeBytes": len(contents),
                "sha256": hashlib.sha256(contents).hexdigest(),
            }
        )
    for destination_relative, generated_contents in (
        GENERATED_COMMAND_SUPPORT_FILES
    ):
        if repository_layout:
            contents = generated_contents
            mode = 0o600
        else:
            path = root.joinpath(*PurePosixPath(destination_relative).parts)
            contents, metadata = read_regular_file(path, root)
            mode = stat.S_IMODE(metadata.st_mode)
        records.append(
            {
                "path": destination_relative,
                "mode": mode,
                "sizeBytes": len(contents),
                "sha256": hashlib.sha256(contents).hexdigest(),
            }
        )
    records.sort(key=lambda item: item["path"].encode("utf-8"))
    return {
        "schemaVersion": 1,
        "kind": "wave3-command-support-manifest",
        "recordCount": len(records),
        "recordsSha256": hashlib.sha256(
            canonical_json(records)
        ).hexdigest(),
        "records": records,
    }


def require_equal_command_support(
    expected: Mapping[str, Any],
    actual: Mapping[str, Any],
) -> None:
    if canonical_json(expected) != canonical_json(actual):
        fail("isolated command support manifest differs from its baseline")


def copy_command_support_files(workspace: Path) -> dict[str, Any]:
    baseline = command_support_manifest(
        REPOSITORY_ROOT,
        repository_layout=True,
    )
    for destination_relative, repository_relative in COMMAND_SUPPORT_FILES:
        source = REPOSITORY_ROOT.joinpath(
            *PurePosixPath(repository_relative).parts
        )
        destination = workspace.joinpath(
            *PurePosixPath(destination_relative).parts
        )
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        clone_or_copy_file(str(source), str(destination))
    for destination_relative, generated_contents in (
        GENERATED_COMMAND_SUPPORT_FILES
    ):
        destination = workspace.joinpath(
            *PurePosixPath(destination_relative).parts
        )
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        descriptor = os.open(
            destination,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_CLOEXEC", 0),
            0o600,
        )
        try:
            with os.fdopen(descriptor, "wb", closefd=False) as stream:
                stream.write(generated_contents)
                stream.flush()
                os.fsync(stream.fileno())
        finally:
            os.close(descriptor)
        fsync_dir(destination.parent)
    actual = command_support_manifest(
        workspace,
        repository_layout=False,
    )
    require_equal_command_support(baseline, actual)
    return baseline


def create_command_workspace(
    stage: Path,
) -> tuple[Path, Path, Path, dict[str, Any], dict[str, Any]]:
    workspace = stage / ".command-workspace"
    workspace.mkdir(mode=0o700)
    isolated_backend = workspace / "backend"
    isolated_frontend = workspace / "frontend"
    shutil.copytree(
        BACKEND_ROOT,
        isolated_backend,
        symlinks=True,
        ignore=shutil.ignore_patterns(
            ".git",
            ".npm-cache",
            "__pycache__",
            "dist",
            "node_modules",
            "target",
            "*.log",
            "*.pyc",
        ),
        copy_function=clone_or_copy_file,
    )
    shutil.copytree(
        FRONTEND_ROOT,
        isolated_frontend,
        symlinks=True,
        ignore=shutil.ignore_patterns(
            ".git",
            ".npm-cache",
            "__pycache__",
            "dist",
            "node_modules",
            "target",
            "*.log",
            "*.pyc",
        ),
        copy_function=clone_or_copy_file,
    )
    # Keep output container identities stable while allowing command-owned
    # contents. Maven clean is configured below not to delete this empty root.
    (isolated_backend / "target").mkdir(mode=0o700)
    (isolated_frontend / "dist").mkdir(mode=0o700)
    shutil.copytree(
        FRONTEND_ROOT / "node_modules",
        isolated_frontend / "node_modules",
        symlinks=True,
        ignore=ignore_origin_node_caches,
        copy_function=clone_or_copy_file,
    )
    for cache_name in sorted(EXCLUDED_NODE_CACHE_DIRECTORIES):
        (isolated_frontend / "node_modules" / cache_name).mkdir(mode=0o700)
    cache_anchors = isolated_node_cache_anchors(
        isolated_frontend / "node_modules"
    )
    isolated_maven_home = workspace / ".m2"
    shutil.copytree(
        Path.home() / ".m2",
        isolated_maven_home,
        symlinks=True,
        copy_function=clone_or_copy_file,
    )
    support_manifest = copy_command_support_files(workspace)
    fsync_dir(isolated_backend)
    fsync_dir(isolated_frontend)
    fsync_dir(workspace)
    return (
        isolated_backend,
        isolated_frontend,
        isolated_maven_home,
        cache_anchors,
        support_manifest,
    )


def require_isolated_source_snapshot(
    expectations: Sequence[tuple[Path, Path, Mapping[str, Any]]],
    expected_directories: frozenset[Path] | None = None,
) -> frozenset[Path]:
    expected_by_root: dict[Path, set[Path]] = {}
    observed_directories: set[Path] = set()
    for isolated_path, isolated_root, record in expectations:
        expected_by_root.setdefault(isolated_root, set()).add(isolated_path)
        copied_contents, _copied_metadata = read_regular_file(
            isolated_path,
            isolated_root,
        )
        if (
            len(copied_contents) != record["sizeBytes"]
            or hashlib.sha256(copied_contents).hexdigest()
            != record["sha256"]
        ):
            fail(
                "isolated command source differs from frozen snapshot: "
                f"{record['path']}"
            )
    for isolated_root, expected_paths in expected_by_root.items():
        observed_paths: set[Path] = set()
        allowed_excluded_roots = (
            {
                isolated_root / "target",
            }
            if isolated_root.name == "backend"
            else {
                isolated_root / "dist",
                isolated_root / "node_modules",
            }
        )
        for directory, directory_names, file_names in os.walk(
            isolated_root,
            followlinks=False,
        ):
            directory_path = Path(directory)
            observed_directories.add(directory_path)
            retained_directories: list[str] = []
            for name in sorted(
                directory_names,
                key=lambda value: value.encode("utf-8"),
            ):
                candidate = directory_path / name
                metadata = candidate.lstat()
                if name in ISOLATED_SOURCE_EXCLUDED_DIRECTORIES:
                    if candidate not in allowed_excluded_roots:
                        fail(
                            "isolated command source contains an unexpected "
                            f"excluded directory: {candidate}"
                        )
                    if (
                        not stat.S_ISDIR(metadata.st_mode)
                        or stat.S_ISLNK(metadata.st_mode)
                    ):
                        fail(
                            "isolated excluded source root is "
                            f"symbolic/non-directory: {candidate}"
                        )
                    continue
                if (
                    not stat.S_ISDIR(metadata.st_mode)
                    or stat.S_ISLNK(metadata.st_mode)
                ):
                    fail(
                        f"isolated command source directory is unsafe: "
                        f"{candidate}"
                    )
                retained_directories.append(name)
            directory_names[:] = retained_directories
            for name in sorted(
                file_names,
                key=lambda value: value.encode("utf-8"),
            ):
                if name.endswith(ISOLATED_SOURCE_EXCLUDED_SUFFIXES):
                    fail(
                        "isolated command source contains an unexpected "
                        f"excluded file: {directory_path / name}"
                    )
                candidate = directory_path / name
                metadata = candidate.lstat()
                if (
                    not stat.S_ISREG(metadata.st_mode)
                    or stat.S_ISLNK(metadata.st_mode)
                ):
                    fail(
                        f"isolated command source file is unsafe: {candidate}"
                    )
                observed_paths.add(candidate)
        if observed_paths != expected_paths:
            fail(
                "isolated command source file closure differs from frozen "
                f"snapshot: missing={sorted(path.as_posix() for path in expected_paths - observed_paths)} "
                f"extra={sorted(path.as_posix() for path in observed_paths - expected_paths)}"
            )
    directory_closure = frozenset(observed_directories)
    if (
        expected_directories is not None
        and directory_closure != expected_directories
    ):
        fail("isolated command source directory closure changed")
    return directory_closure


def create_workspace_seal(
    verifier: Wave3Evidence,
    context: dict[str, Any],
    isolated_backend: Path | None = None,
    isolated_frontend: Path | None = None,
    isolated_maven_home: Path | None = None,
    *,
    include_origin_dependencies: bool,
    expected_tool_runtime_manifest: Mapping[str, Any],
    expected_dependency_manifest: Mapping[str, Any] | None = None,
    expected_cache_anchors: Mapping[str, Any] | None = None,
    expected_support_manifest: Mapping[str, Any] | None = None,
) -> WorkspaceSeal:
    snapshot = verifier.compute_source_snapshot()
    if snapshot.tree_hash != context["sourceTreeHash"]:
        fail("source changed before establishing the run-wide identity seal")
    source_paths: set[Path] = set()
    isolated_source_paths: set[Path] = set()
    isolated_source_expectations: list[
        tuple[Path, Path, Mapping[str, Any]]
    ] = []
    required_roots = [
        REPOSITORY_ROOT / root for root in verifier.contract["source"]["roots"]
    ]
    for record in snapshot.records:
        path = REPOSITORY_ROOT / record["path"]
        source_paths.add(path)
        pure_parts = Path(record["path"]).parts
        isolated_root: Path | None = None
        if isolated_backend is not None and pure_parts and pure_parts[0] == "backend":
            isolated_root = isolated_backend
        elif (
            isolated_frontend is not None
            and pure_parts
            and pure_parts[0] == "frontend"
        ):
            isolated_root = isolated_frontend
        if isolated_root is not None:
            isolated_path = isolated_root.joinpath(*pure_parts[1:])
            isolated_source_expectations.append(
                (isolated_path, isolated_root, record)
            )
            isolated_source_paths.add(isolated_path)
            current_isolated = isolated_path.parent
            while True:
                isolated_source_paths.add(current_isolated)
                if current_isolated == isolated_root:
                    break
                current_isolated = current_isolated.parent
        for root in required_roots:
            try:
                path.relative_to(root)
            except ValueError:
                continue
            current = path.parent
            while True:
                source_paths.add(current)
                if current == root:
                    break
                current = current.parent
            break
    isolated_source_directory_closure = require_isolated_source_snapshot(
        isolated_source_expectations
    )
    isolated_source_paths.update(isolated_source_directory_closure)
    home = Path.home()
    maven_cache = home / ".m2"
    if not maven_cache.is_dir() or maven_cache.is_symlink():
        fail("Maven cache authority is missing or symbolic")
    node_modules = FRONTEND_ROOT / "node_modules"
    if not node_modules.is_dir() or node_modules.is_symlink():
        fail("frontend node_modules authority is missing or symbolic")
    authorities: list[tuple[str, Path, bool, bool]] = [
        ("frozen-source", path, False, True)
        for path in sorted(source_paths, key=lambda item: item.as_posix())
    ]
    internal_npm_bin_boundaries: dict[str, Path] = {}
    ignored_recursive_directories: dict[str, frozenset[str]] = {}
    authorities.extend(
        ("isolated-command-source", path, False, True)
        for path in sorted(isolated_source_paths, key=lambda item: item.as_posix())
    )
    support_workspace: Path | None = None
    if expected_support_manifest is not None:
        if isolated_backend is None:
            fail("expected command support requires an isolated workspace")
        support_workspace = isolated_backend.parent
        actual_support_manifest = command_support_manifest(
            support_workspace,
            repository_layout=False,
        )
        require_equal_command_support(
            expected_support_manifest,
            actual_support_manifest,
        )
        support_paths: set[Path] = set()
        for record in expected_support_manifest["records"]:
            path = support_workspace.joinpath(
                *PurePosixPath(record["path"]).parts
            )
            support_paths.add(path)
            current = path.parent
            while True:
                support_paths.add(current)
                if current == support_workspace:
                    break
                current = current.parent
        authorities.extend(
            ("isolated-command-support", path, False, True)
            for path in sorted(
                support_paths,
                key=lambda item: item.as_posix(),
            )
        )
    if include_origin_dependencies:
        internal_npm_bin_boundaries[
            "frontend-node-modules-origin"
        ] = node_modules
        ignored_recursive_directories[
            "frontend-node-modules-origin"
        ] = EXCLUDED_NODE_CACHE_DIRECTORIES
        authorities.extend(
            [
                ("frontend-node-modules-origin", node_modules, True, False),
                ("maven-local-cache-origin", maven_cache, True, False),
            ]
        )
    if isolated_frontend is not None and isolated_maven_home is not None:
        internal_npm_bin_boundaries[
            "isolated-node-install"
        ] = isolated_frontend / "node_modules"
        ignored_recursive_directories[
            "isolated-node-install"
        ] = EXCLUDED_NODE_CACHE_DIRECTORIES
        authorities.append(
            (
                "isolated-node-install",
                isolated_frontend / "node_modules",
                True,
                False,
            )
        )
        authorities.append(
            (
                "isolated-maven-install",
                isolated_maven_home,
                True,
                False,
            )
        )
    cache_anchor_expectations: dict[Path, Mapping[str, Any]] = {}
    if expected_cache_anchors is not None:
        if isolated_frontend is None:
            fail("expected cache anchors require an isolated frontend")
        records = validate_isolated_node_cache_anchor_receipt(
            expected_cache_anchors
        )
        for record in records:
            name = record["path"]
            cache_anchor_expectations[
                isolated_frontend / "node_modules" / name
            ] = record
    node_executable = resolve_executable("node", REPOSITORY_ROOT)
    npm_executable = resolve_executable("npm", REPOSITORY_ROOT)
    java_executable = resolve_executable("java", REPOSITORY_ROOT)
    npm_runtime = npm_runtime_root(npm_executable)
    java_runtime = java_runtime_root(java_executable)
    internal_npm_bin_boundaries["tool-npm"] = (
        npm_runtime / "node_modules"
    )
    authorities.extend(
        [
            ("tool-node", node_executable.parent, False, True),
            ("tool-node", node_executable, False, True),
            ("tool-npm", npm_runtime, True, True),
            ("tool-java", java_runtime, True, True),
        ]
    )
    seal = WorkspaceSeal(
        authorities,
        internal_npm_bin_boundaries=internal_npm_bin_boundaries,
        ignored_recursive_directories=ignored_recursive_directories,
        expected_cache_anchors=cache_anchor_expectations,
    )
    try:
        # Recompute while all source objects are already watched. A durable
        # pre-watch replacement cannot become the accepted seal baseline.
        assert_frozen_source(verifier, context)
        require_isolated_source_snapshot(
            isolated_source_expectations,
            isolated_source_directory_closure,
        )
        if expected_dependency_manifest is not None:
            if isolated_frontend is not None and isolated_maven_home is not None:
                dependency_node_modules = (
                    isolated_frontend / "node_modules"
                )
                dependency_maven_home = isolated_maven_home
            elif include_origin_dependencies:
                dependency_node_modules = node_modules
                dependency_maven_home = maven_cache
            else:
                fail(
                    "expected dependency manifest requires an origin or "
                    "isolated dependency workspace"
                )
            actual_dependency_manifest = dependency_handoff_manifest(
                dependency_node_modules,
                dependency_maven_home,
            )
            seal.bind_dependency_handoff(
                expected_dependency_manifest,
                actual_dependency_manifest,
            )
        if expected_support_manifest is not None:
            if support_workspace is None:
                fail("command support workspace binding is unavailable")
            actual_support_manifest = command_support_manifest(
                support_workspace,
                repository_layout=False,
            )
            seal.bind_command_support(
                expected_support_manifest,
                actual_support_manifest,
            )
        seal.bind_tool_runtime(
            expected_tool_runtime_manifest,
            tool_runtime_manifest(),
        )
        if expected_cache_anchors is not None:
            actual_cache_anchors = isolated_node_cache_anchors(
                isolated_frontend / "node_modules"
            )
            if canonical_json(expected_cache_anchors) != canonical_json(
                actual_cache_anchors
            ):
                fail("isolated node cache root identity changed")
        seal.assert_unchanged()
        return seal
    except BaseException:
        seal.close()
        raise


def publish_backend_target(isolated_backend: Path) -> None:
    source = isolated_backend / "target"
    assert_nofollow_chain(REPOSITORY_ROOT, source, final="dir")
    target = BACKEND_ROOT / "target"
    if not target.exists():
        target.mkdir(mode=0o755)
        fsync_dir(BACKEND_ROOT)
    assert_nofollow_chain(REPOSITORY_ROOT, target, final="dir")
    for child in sorted(target.iterdir(), key=lambda item: item.name.encode("utf-8")):
        metadata = child.lstat()
        if stat.S_ISLNK(metadata.st_mode):
            fail(f"backend target contains a symbolic object: {child}")
        if stat.S_ISDIR(metadata.st_mode):
            secure_remove_tree(child, REPOSITORY_ROOT)
        elif stat.S_ISREG(metadata.st_mode):
            current = child.lstat()
            if (current.st_dev, current.st_ino) != (metadata.st_dev, metadata.st_ino):
                fail(f"backend target file identity changed: {child}")
            child.unlink()
        else:
            fail(f"backend target contains a special object: {child}")
    fsync_dir(target)
    for child in sorted(source.iterdir(), key=lambda item: item.name.encode("utf-8")):
        exclusive_rename(child, target / child.name)
    fsync_dir(target)


def gate_stage_dir(stage: Path, gate: Gate) -> Path:
    path = stage / gate.slug
    metadata = assert_nofollow_chain(stage, path, final="dir")
    if stat.S_IMODE(metadata.st_mode) != 0o700:
        fail(f"precreated gate stage directory mode differs: {gate.evidence_id}")
    if any(path.iterdir()):
        fail(f"precreated gate stage directory is not empty: {gate.evidence_id}")
    return path


def ensure_leaf_parent(run_root: Path, gate: Gate) -> Path:
    assert_nofollow_chain(
        REPOSITORY_ROOT / "docs/verification/wave3/runs", run_root, final="dir"
    )
    leaves_root = run_root / "leaves"
    if not leaves_root.exists():
        leaves_root.mkdir(mode=0o755)
        fsync_dir(run_root)
    assert_nofollow_chain(run_root, leaves_root, final="dir")
    leaf_root = leaves_root / gate.slug
    if not leaf_root.exists():
        leaf_root.mkdir(mode=0o755)
        fsync_dir(leaves_root)
    assert_nofollow_chain(run_root, leaf_root, final="dir")
    return leaf_root


def assert_destinations_absent(run_root: Path) -> None:
    commit_marker = run_root / COMMIT_FILENAME
    if commit_marker.exists() or commit_marker.is_symlink():
        fail(f"refusing an already committed build-gate bundle: {commit_marker}")
    for gate in GATES:
        target = ensure_leaf_parent(run_root, gate) / "raw"
        if target.exists() or target.is_symlink():
            fail(f"refusing to overwrite build-gate output: {target}")


def produce_backend_gate(
    verifier: Wave3Evidence,
    context: dict[str, Any],
    stage: Path,
    gate: Gate,
    expected_classes: Sequence[str],
    selected_classes: Sequence[str] | None,
    backend_root: Path,
    isolated_maven_home: Path,
    workspace_seal: WorkspaceSeal,
) -> dict[str, Any] | None:
    clear_surefire_reports(backend_root)
    result = run_command(
        maven_command(
            selected_classes, isolated_maven_home / "repository"
        ),
        backend_root,
        900,
        workspace_seal,
        maven_environment(isolated_maven_home),
    )
    parsed = parse_surefire_reports(
        backend_root / "target/surefire-reports", expected_classes, result
    )
    runtime_classes_receipt: dict[str, Any] | None = None
    if selected_classes is None:
        runtime_classes_receipt = backend_runtime_classes_receipt(
            backend_root / "target/classes",
            producing_result=result,
        )
        parsed = {
            **parsed,
            "runtimeClassesReceipt": runtime_classes_receipt,
        }
    output = gate_stage_dir(stage, gate)
    archive_json = canonical_json(parsed["rawSurefireArchive"]).decode("utf-8")
    write_exclusive(
        output / gate.primary_filename,
        render_primary_log(
            verifier,
            context,
            gate,
            result,
            [
                f"W3_MAVEN_SUITES={parsed['suiteCount']}",
                f"W3_MAVEN_TESTS={parsed['tests']}",
                f"W3_MAVEN_FAILURES={parsed['failures']}",
                f"W3_MAVEN_ERRORS={parsed['errors']}",
                f"W3_MAVEN_SKIPPED={parsed['skipped']}",
                f"W3_SUREFIRE_RAW_ARCHIVE_JSON={archive_json}",
            ],
        ),
    )
    if gate.secondary_filename:
        summary = structured_test_summary(
            context, gate, result, parsed, "wave3-backend-test-summary"
        )
        if runtime_classes_receipt is not None:
            summary["schemaVersion"] = 3
        write_json(
            output / gate.secondary_filename,
            summary,
        )
    return runtime_classes_receipt


def produce_frontend_command_gate(
    verifier: Wave3Evidence,
    context: dict[str, Any],
    stage: Path,
    gate: Gate,
    command: Sequence[str],
    frontend_root: Path,
    workspace_seal: WorkspaceSeal,
) -> None:
    result = run_command(
        command,
        frontend_root,
        600,
        workspace_seal,
        frontend_environment(frontend_root),
    )
    output = gate_stage_dir(stage, gate)
    write_exclusive(
        output / gate.primary_filename,
        render_primary_log(
            verifier, context, gate, result, ["W3_FRONTEND_COMMAND_EXIT=0"]
        ),
    )


def produce_frontend_tests(
    verifier: Wave3Evidence,
    context: dict[str, Any],
    stage: Path,
    gate: Gate,
    expected_files: Sequence[str],
    frontend_root: Path,
    workspace_seal: WorkspaceSeal,
) -> None:
    output = gate_stage_dir(stage, gate)
    raw_root = output / ".private-reports"
    raw_root.mkdir(mode=0o700)
    fsync_dir(output)
    raw_report = raw_root / "vitest-raw.json"
    result = run_command(
        [
            "npm",
            "run",
            "test",
            "--",
            "--reporter=json",
            f"--outputFile={raw_report}",
        ],
        frontend_root,
        600,
        workspace_seal,
        frontend_environment(frontend_root),
    )
    parsed = parse_vitest_report(
        raw_report, expected_files, result, frontend_root
    )
    write_exclusive(
        output / gate.primary_filename,
        render_primary_log(
            verifier,
            context,
            gate,
            result,
            [
                f"W3_VITEST_SUITES={parsed['suiteCount']}",
                f"W3_VITEST_TESTS={parsed['tests']}",
                f"W3_VITEST_FAILED={parsed['failed']}",
                f"W3_VITEST_PENDING={parsed['pending']}",
            ],
        ),
    )
    if gate.secondary_filename is None:
        fail("frontend test secondary contract is missing")
    write_json(
        output / gate.secondary_filename,
        structured_test_summary(
            context, gate, result, parsed, "wave3-frontend-test-summary"
        ),
    )
    secure_remove_tree(raw_root, stage)


def produce_build(
    verifier: Wave3Evidence,
    context: dict[str, Any],
    stage: Path,
    gate: Gate,
    frontend_root: Path,
    workspace_seal: WorkspaceSeal,
) -> None:
    mode = "demo" if gate.slug == "demo-build" else "prod"
    dist_parent = frontend_root / "dist"
    if dist_parent.exists():
        assert_nofollow_chain(REPOSITORY_ROOT, dist_parent, final="dir")
    else:
        dist_parent.mkdir(mode=0o755)
        fsync_dir(frontend_root)
    dist_root = dist_parent / mode
    if dist_root.exists() or dist_root.is_symlink():
        secure_remove_tree(dist_root, REPOSITORY_ROOT)
    result = run_command(
        ["npm", "run", "build:demo" if mode == "demo" else "build"],
        frontend_root,
        600,
        workspace_seal,
        frontend_environment(frontend_root),
    )
    published_parent = FRONTEND_ROOT / "dist"
    if not published_parent.exists():
        published_parent.mkdir(mode=0o755)
        fsync_dir(FRONTEND_ROOT)
    assert_nofollow_chain(REPOSITORY_ROOT, published_parent, final="dir")
    published_root = published_parent / mode
    if published_root.exists() or published_root.is_symlink():
        secure_remove_tree(published_root, REPOSITORY_ROOT)
    exclusive_rename(dist_root, published_root)
    workspace_seal.assert_unchanged()
    inventory = dist_inventory(published_root, context, gate, result)
    output = gate_stage_dir(stage, gate)
    write_exclusive(
        output / gate.primary_filename,
        render_primary_log(
            verifier,
            context,
            gate,
            result,
            [
                f"W3_BUILD_MODE={mode}",
                f"W3_BUILD_FILE_COUNT={inventory['fileCount']}",
                f"W3_BUILD_TOTAL_SIZE_BYTES={inventory['totalSizeBytes']}",
                f"W3_BUILD_MANIFEST_SHA256={inventory['manifestSha256']}",
                "W3_BUILD_CONTENT_ARCHIVE=EMBEDDED",
            ],
        ),
    )
    if gate.secondary_filename is None:
        fail("build secondary contract is missing")
    write_json(output / gate.secondary_filename, inventory)


def exclusive_rename(source: Path, target: Path) -> None:
    """Atomic no-clobber rename; unsupported platforms fail closed."""
    libc = ctypes.CDLL(None, use_errno=True)
    source_bytes = os.fsencode(source)
    target_bytes = os.fsencode(target)
    if sys.platform == "darwin":
        function = getattr(libc, "renameatx_np", None)
        if function is None:
            fail("Darwin renameatx_np(RENAME_EXCL) is unavailable")
        function.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        function.restype = ctypes.c_int
        result = function(-2, source_bytes, -2, target_bytes, 0x00000004)
    elif sys.platform.startswith("linux"):
        function = getattr(libc, "renameat2", None)
        if function is None:
            fail("Linux renameat2(RENAME_NOREPLACE) is unavailable")
        function.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        function.restype = ctypes.c_int
        result = function(-100, source_bytes, -100, target_bytes, 0x00000001)
    else:
        fail("exclusive rename is unsupported on this platform")
    if result != 0:
        error_number = ctypes.get_errno()
        if error_number == 17:
            fail(f"exclusive rename target already exists: {target}")
        raise OSError(error_number, os.strerror(error_number), str(target))
    fsync_dir(source.parent)
    if target.parent != source.parent:
        fsync_dir(target.parent)


def freeze_stage(stage: Path) -> dict[str, dict[str, Any]]:
    manifest: dict[str, dict[str, Any]] = {}
    for gate in GATES:
        directory = stage / gate.slug
        assert_nofollow_chain(stage, directory, final="dir")
        expected_names = [gate.primary_filename]
        if gate.secondary_filename:
            expected_names.append(gate.secondary_filename)
        actual_names = sorted(path.name for path in directory.iterdir())
        if actual_names != sorted(expected_names):
            fail(f"staged artifact closure differs for {gate.evidence_id}")
        for name in expected_names:
            path = directory / name
            contents, metadata = read_regular_file(path, stage)
            os.chmod(path, 0o400)
            immutable = path.lstat()
            manifest[f"{gate.slug}/{name}"] = {
                "sizeBytes": len(contents),
                "sha256": hashlib.sha256(contents).hexdigest(),
                "device": immutable.st_dev,
                "inode": immutable.st_ino,
                "mode": stat.S_IMODE(immutable.st_mode),
                "mtimeEpochNs": immutable.st_mtime_ns,
                "ctimeEpochNs": immutable.st_ctime_ns,
            }
        # Darwin renameatx_np(RENAME_EXCL) rejects a non-writable source
        # directory.  Files are already read-only; the directory is sealed to
        # 0500 immediately after its exclusive publication.
        os.chmod(directory, 0o700)
        fsync_dir(directory)
    fsync_dir(stage)
    return manifest


def verify_published(
    run_root: Path,
    manifest: dict[str, dict[str, Any]],
) -> None:
    expected_manifest_paths: set[str] = set()
    for gate in GATES:
        expected_names = [gate.primary_filename]
        if gate.secondary_filename:
            expected_names.append(gate.secondary_filename)
        expected_manifest_paths.update(
            f"{gate.slug}/{name}" for name in expected_names
        )
        raw_root = run_root / "leaves" / gate.slug / "raw"
        raw_metadata = assert_nofollow_chain(run_root, raw_root, final="dir")
        if stat.S_IMODE(raw_metadata.st_mode) != 0o500:
            fail(f"published raw directory is not sealed mode 0500: {gate.slug}")
        actual_names = sorted(
            child.name for child in raw_root.iterdir()
        )
        if actual_names != sorted(expected_names):
            fail(f"published raw artifact closure differs: {gate.slug}")
    if set(manifest) != expected_manifest_paths:
        fail("published artifact manifest path closure differs")
    for relative, expected in manifest.items():
        slug, filename = relative.split("/", 1)
        path = run_root / "leaves" / slug / "raw" / filename
        contents, metadata = read_regular_file(path, run_root)
        actual = {
            "sizeBytes": len(contents),
            "sha256": hashlib.sha256(contents).hexdigest(),
            "device": metadata.st_dev,
            "inode": metadata.st_ino,
            "mode": stat.S_IMODE(metadata.st_mode),
            "mtimeEpochNs": metadata.st_mtime_ns,
            "ctimeEpochNs": metadata.st_ctime_ns,
        }
        if actual != expected:
            fail(f"published artifact identity/content changed: {relative}")


def remove_identity_bound_tree(
    path: Path, run_root: Path, identity: tuple[int, int]
) -> None:
    metadata = assert_nofollow_chain(run_root, path, final="dir")
    if (metadata.st_dev, metadata.st_ino) != identity:
        fail(f"rollback target identity changed; refusing deletion: {path}")
    os.chmod(path, 0o700)
    for child in path.iterdir():
        if child.is_file() and not child.is_symlink():
            os.chmod(child, 0o600)
    secure_remove_tree(path, run_root)


def validate_owned_commit_marker(
    marker_path: Path,
    marker_identity: tuple[int, int],
    marker_value: dict[str, Any],
    run_root: Path,
    manifest: dict[str, dict[str, Any]],
) -> None:
    contents, metadata = read_regular_file(marker_path, run_root)
    if (
        (metadata.st_dev, metadata.st_ino) != marker_identity
        or stat.S_IMODE(metadata.st_mode) != 0o400
    ):
        fail("published build-gate commit marker identity/mode changed")
    try:
        actual = json.loads(contents)
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"published build-gate commit marker is invalid: {error}")
    if actual != marker_value:
        fail("published build-gate commit marker content changed")
    verify_published(run_root, manifest)


def rollback_planned_outputs(
    planned: Sequence[tuple[Path, Path, tuple[int, int]]],
    run_root: Path,
) -> list[str]:
    rollback_errors: list[str] = []
    for _source, target, source_identity in reversed(planned):
        try:
            try:
                metadata = target.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(
                metadata.st_mode
            ):
                fail(
                    f"planned rollback target became symbolic/non-directory: "
                    f"{target}"
                )
            if (metadata.st_dev, metadata.st_ino) != source_identity:
                fail(
                    f"planned rollback target has foreign identity: {target}"
                )
            remove_identity_bound_tree(target, run_root, source_identity)
        except BaseException as rollback_error:
            rollback_errors.append(f"{target}: {rollback_error}")
    return rollback_errors


def commit_outputs(
    stage: Path,
    run_root: Path,
    manifest: dict[str, dict[str, Any]],
    context: dict[str, Any],
) -> list[Path]:
    planned: list[tuple[Path, Path, tuple[int, int]]] = []
    for gate in GATES:
        staged = stage / gate.slug
        staged_metadata = assert_nofollow_chain(stage, staged, final="dir")
        target = ensure_leaf_parent(run_root, gate) / "raw"
        if target.exists() or target.is_symlink():
            fail(f"build-gate output appeared before commit planning: {target}")
        planned.append(
            (
                staged,
                target,
                (staged_metadata.st_dev, staged_metadata.st_ino),
            )
        )
    marker_stage = stage / ".bundle-commit.json"
    marker_value = {
        "schemaVersion": 1,
        "nodeType": "wave3-build-gates-commit",
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "gateIds": [gate.evidence_id for gate in GATES],
        "artifactManifest": manifest,
        "artifactManifestSha256": hashlib.sha256(canonical_json(manifest)).hexdigest(),
        "committedAt": utc_now(),
        "verdict": "PASS",
    }
    write_json(marker_stage, marker_value)
    os.chmod(marker_stage, 0o400)
    marker_metadata = marker_stage.lstat()
    marker_identity = (marker_metadata.st_dev, marker_metadata.st_ino)
    marker_target = run_root / COMMIT_FILENAME
    fsync_dir(stage)
    try:
        for staged, target, source_identity in planned:
            exclusive_rename(staged, target)
            moved_metadata = target.lstat()
            if (moved_metadata.st_dev, moved_metadata.st_ino) != source_identity:
                fail(f"exclusive raw rename changed object identity: {target}")
            os.chmod(target, 0o500)
            fsync_dir(target.parent)
        verify_published(run_root, manifest)
        exclusive_rename(marker_stage, marker_target)
        fsync_dir(run_root)
        stage.rmdir()
        fsync_dir(run_root)
        return [target for _source, target, _identity in planned]
    except BaseException as error:
        marker_is_ours = False
        if marker_target.exists() and not marker_target.is_symlink():
            try:
                current_marker = marker_target.lstat()
                marker_is_ours = (
                    current_marker.st_dev,
                    current_marker.st_ino,
                ) == marker_identity
            except OSError:
                marker_is_ours = False
        if marker_is_ours:
            try:
                validate_owned_commit_marker(
                    marker_target,
                    marker_identity,
                    marker_value,
                    run_root,
                    manifest,
                )
                fsync_dir(run_root)
                if stage.exists() and not any(stage.iterdir()):
                    stage.rmdir()
                    fsync_dir(run_root)
                # The exclusive marker rename is the commit point.  A
                # KeyboardInterrupt immediately afterward cannot turn a
                # complete, verifier-valid batch into marker/raw divergence.
                return [target for _source, target, _identity in planned]
            except BaseException as committed_validation_error:
                try:
                    current_marker = marker_target.lstat()
                    if (
                        current_marker.st_dev,
                        current_marker.st_ino,
                    ) != marker_identity:
                        fail(
                            "commit marker identity changed; refusing rollback"
                        )
                    marker_target.unlink()
                    fsync_dir(run_root)
                except BaseException as marker_rollback_error:
                    fail(
                        "post-commit interruption left a complete marker but "
                        "validation/identity-safe marker rollback failed; "
                        f"original={error}; validation={committed_validation_error}; "
                        f"markerRollback={marker_rollback_error}"
                    )
                rollback_errors = rollback_planned_outputs(planned, run_root)
                if rollback_errors:
                    fail(
                        "post-commit validation failed and raw rollback also "
                        f"failed: {rollback_errors}"
                    )
                raise committed_validation_error
        rollback_errors = rollback_planned_outputs(planned, run_root)
        if rollback_errors:
            fail(
                f"build-gate commit failed ({error}); identity-bound rollback also "
                f"failed: {rollback_errors}"
            )
        raise


def validate_existing_complete_bundle(
    run_root: Path,
    context: dict[str, Any],
    manifest: dict[str, dict[str, Any]],
) -> bool:
    marker_path = run_root / COMMIT_FILENAME
    if not marker_path.exists() and not marker_path.is_symlink():
        return False
    contents, metadata = read_regular_file(marker_path, run_root)
    if stat.S_IMODE(metadata.st_mode) != 0o400:
        fail("existing build-gate commit marker mode is not 0400")
    try:
        marker = json.loads(contents)
    except (UnicodeError, json.JSONDecodeError) as error:
        fail(f"existing build-gate commit marker is invalid: {error}")
    reject_forbidden_values(marker, "existing build-gate commit marker")
    expected_keys = {
        "schemaVersion",
        "nodeType",
        "runId",
        "sourceTreeHash",
        "databaseIdentity",
        "gateIds",
        "artifactManifest",
        "artifactManifestSha256",
        "committedAt",
        "verdict",
    }
    if not isinstance(marker, dict) or set(marker) != expected_keys:
        fail("existing build-gate commit marker key closure differs")
    expected_scalars = {
        "schemaVersion": 1,
        "nodeType": "wave3-build-gates-commit",
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "gateIds": [gate.evidence_id for gate in GATES],
        "artifactManifest": manifest,
        "artifactManifestSha256": hashlib.sha256(
            canonical_json(manifest)
        ).hexdigest(),
        "verdict": "PASS",
    }
    for key, expected in expected_scalars.items():
        if marker[key] != expected:
            fail(f"existing build-gate commit marker {key} differs")
    if type(marker["schemaVersion"]) is not int:
        fail("existing build-gate commit marker schemaVersion type differs")
    if not isinstance(marker["committedAt"], str):
        fail("existing build-gate commit marker committedAt is invalid")
    try:
        committed_at = datetime.fromisoformat(
            marker["committedAt"].replace("Z", "+00:00")
        )
    except ValueError as error:
        fail(f"existing build-gate committedAt cannot be parsed: {error}")
    if committed_at.tzinfo != timezone.utc:
        fail("existing build-gate committedAt is not UTC")
    verify_published(run_root, manifest)
    return True


def acquire_run_lock(
    run_root: Path,
    context: dict[str, Any],
) -> tuple[int, Path, tuple[int, int]]:
    lock_path = run_root / LOCK_FILENAME
    if lock_path.exists() or lock_path.is_symlink():
        fail(f"build-gate run lock already exists: {lock_path}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(lock_path, flags, 0o600)
    payload = canonical_json(
        {
            "schemaVersion": 1,
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "pid": os.getpid(),
            "startedAt": utc_now(),
            "state": "PREPARING",
        }
    )
    os.write(descriptor, payload)
    os.fsync(descriptor)
    metadata = os.fstat(descriptor)
    fsync_dir(run_root)
    return descriptor, lock_path, (metadata.st_dev, metadata.st_ino)


def release_run_lock(
    descriptor: int,
    lock_path: Path,
    lock_identity: tuple[int, int],
    *,
    committed: bool,
) -> None:
    os.close(descriptor)
    if committed:
        metadata = lock_path.lstat()
        if (
            not stat.S_ISREG(metadata.st_mode)
            or stat.S_ISLNK(metadata.st_mode)
            or (metadata.st_dev, metadata.st_ino) != lock_identity
        ):
            fail("run lock identity became unsafe")
        lock_path.unlink()
        fsync_dir(lock_path.parent)


def release_run_lock_from_bundle(
    descriptor: int,
    lock_path: Path,
    lock_identity: tuple[int, int],
    run_root: Path,
    context: dict[str, Any],
    manifest: dict[str, dict[str, Any]] | None,
) -> bool:
    """Derive lock release from the durable bundle, never a return-path flag."""
    committed = False
    try:
        committed = (
            manifest is not None
            and validate_existing_complete_bundle(run_root, context, manifest)
        )
        return committed
    finally:
        release_run_lock(
            descriptor,
            lock_path,
            lock_identity,
            committed=committed,
        )


def execute(run_context_path: Path) -> dict[str, Any]:
    verifier, run_root, context = load_bound_context(run_context_path)
    validate_gate_contract(verifier)
    assert_destinations_absent(run_root)
    lock_fd, lock_path, lock_identity = acquire_run_lock(run_root, context)
    stage: Path | None = None
    copy_seal: WorkspaceSeal | None = None
    workspace_seal: WorkspaceSeal | None = None
    manifest: dict[str, dict[str, Any]] | None = None
    try:
        stage = create_stage(run_root)
        if not (BACKEND_ROOT / "target").exists():
            (BACKEND_ROOT / "target").mkdir(mode=0o755)
            fsync_dir(BACKEND_ROOT)
        if not (FRONTEND_ROOT / "dist").exists():
            (FRONTEND_ROOT / "dist").mkdir(mode=0o755)
            fsync_dir(FRONTEND_ROOT)
        # Establish the dependency baseline before watcher registration. The
        # same manifest is recomputed by create_workspace_seal after the global
        # watcher closure is live, so a durable Phase-1 mutation cannot be
        # absorbed into the origin baseline.
        origin_tool_runtime_manifest = tool_runtime_manifest()
        origin_dependency_manifest = dependency_handoff_manifest(
            FRONTEND_ROOT / "node_modules",
            Path.home() / ".m2",
        )
        if (
            origin_dependency_manifest["node"]["linkCount"]
            != EXPECTED_NPM_BIN_LINKS
        ):
            fail(
                "frozen npm .bin link closure differs: "
                f"expected={EXPECTED_NPM_BIN_LINKS} "
                f"actual={origin_dependency_manifest['node']['linkCount']}"
            )
        copy_seal = create_workspace_seal(
            verifier,
            context,
            include_origin_dependencies=True,
            expected_tool_runtime_manifest=origin_tool_runtime_manifest,
            expected_dependency_manifest=origin_dependency_manifest,
        )
        copy_seal.assert_unchanged()
        (
            isolated_backend,
            isolated_frontend,
            isolated_maven_home,
            isolated_cache_anchors,
            isolated_support_manifest,
        ) = create_command_workspace(stage)
        copy_seal.assert_unchanged()
        copied_dependency_manifest = dependency_handoff_manifest(
            isolated_frontend / "node_modules",
            isolated_maven_home,
        )
        require_equal_dependency_handoff(
            origin_dependency_manifest,
            copied_dependency_manifest,
        )
        copy_seal.assert_unchanged()
        # The origin authority is no longer consumed after the exact copy
        # handoff. Close it before opening the isolated full-tree seal so the
        # two ~57k-fd closures never overlap under Darwin RLIMIT_NOFILE.
        copy_seal.close()
        copy_seal = None
        workspace_seal = create_workspace_seal(
            verifier,
            context,
            isolated_backend,
            isolated_frontend,
            isolated_maven_home,
            include_origin_dependencies=False,
            expected_tool_runtime_manifest=origin_tool_runtime_manifest,
            expected_dependency_manifest=origin_dependency_manifest,
            expected_cache_anchors=isolated_cache_anchors,
            expected_support_manifest=isolated_support_manifest,
        )
        all_backend_tests = discover_java_tests(BACKEND_ROOT / "src/test/java")
        selections = {
            "W3-VER-W1-REGRESSION": discover_java_tests(
                BACKEND_ROOT / "src/test/java/com/szsemicon/hr/wave1"
            ),
            "W3-VER-W2-REGRESSION": discover_java_tests(
                BACKEND_ROOT / "src/test/java/com/szsemicon/hr/wave2"
            ),
            "W3-VER-W3-REGRESSION": discover_java_tests(
                BACKEND_ROOT / "src/test/java/com/szsemicon/hr/wave3"
            ),
        }

        backend_classes_receipt: dict[str, Any] | None = None
        for gate in GATES[:4]:
            selected = selections.get(gate.evidence_id)
            expected = all_backend_tests if selected is None else selected
            produced_classes_receipt = produce_backend_gate(
                verifier,
                context,
                stage,
                gate,
                expected,
                selected,
                isolated_backend,
                isolated_maven_home,
                workspace_seal,
            )
            if produced_classes_receipt is not None:
                if backend_classes_receipt is not None:
                    fail("backend runtime classes receipt was produced twice")
                backend_classes_receipt = produced_classes_receipt
            assert_frozen_source(verifier, context)
        if backend_classes_receipt is None:
            fail("backend-full did not produce a runtime classes receipt")
        if (
            backend_runtime_classes_receipt(
                isolated_backend / "target/classes"
            )
            != backend_classes_receipt
        ):
            fail(
                "targeted backend gates changed the backend-full runtime "
                "classes receipt"
            )
        publish_backend_target(isolated_backend)
        if (
            backend_runtime_classes_receipt(BACKEND_ROOT / "target/classes")
            != backend_classes_receipt
        ):
            fail(
                "published backend target/classes differs from the committed "
                "backend-full receipt"
            )
        workspace_seal.assert_unchanged()
        produce_frontend_command_gate(
            verifier,
            context,
            stage,
            GATES[4],
            ["npm", "run", "typecheck"],
            isolated_frontend,
            workspace_seal,
        )
        assert_frozen_source(verifier, context)
        produce_frontend_command_gate(
            verifier,
            context,
            stage,
            GATES[5],
            ["npm", "run", "lint", "--", "--max-warnings=0"],
            isolated_frontend,
            workspace_seal,
        )
        assert_frozen_source(verifier, context)
        expected_vitest_files = discover_vitest_files()
        produce_frontend_tests(
            verifier,
            context,
            stage,
            GATES[6],
            expected_vitest_files,
            isolated_frontend,
            workspace_seal,
        )
        assert_frozen_source(verifier, context)
        produce_build(
            verifier,
            context,
            stage,
            GATES[7],
            isolated_frontend,
            workspace_seal,
        )
        assert_frozen_source(verifier, context)
        produce_build(
            verifier,
            context,
            stage,
            GATES[8],
            isolated_frontend,
            workspace_seal,
        )
        assert_frozen_source(verifier, context)
        workspace_seal.assert_unchanged()
        command_workspace = isolated_backend.parent
        workspace_seal.close()
        workspace_seal = None
        secure_remove_tree(
            command_workspace, stage, allow_internal_symlinks=True
        )
        workspace_seal = create_workspace_seal(
            verifier,
            context,
            include_origin_dependencies=False,
            expected_tool_runtime_manifest=origin_tool_runtime_manifest,
        )
        manifest = freeze_stage(stage)
        workspace_seal.assert_unchanged()
        committed = commit_outputs(stage, run_root, manifest, context)
        stage = None
        assert_frozen_source(verifier, context)
        return {
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "gateCount": len(committed),
            "commitMarker": COMMIT_FILENAME,
            "outputs": [
                path.relative_to(run_root).as_posix() for path in committed
            ],
        }
    except BaseException:
        if stage is not None and stage.exists() and not stage.is_symlink():
            try:
                secure_remove_tree(
                    stage, run_root, allow_internal_symlinks=True
                )
            except BaseException:
                pass
        raise
    finally:
        if copy_seal is not None:
            copy_seal.close()
        if workspace_seal is not None:
            workspace_seal.close()
        release_run_lock_from_bundle(
            lock_fd,
            lock_path,
            lock_identity,
            run_root,
            context,
            manifest,
        )


def self_test() -> dict[str, Any]:
    verifier = Wave3Evidence(REPOSITORY_ROOT)
    validate_gate_contract(verifier)
    expected = {
        "W3-VER-BACKEND-FULL",
        "W3-VER-W1-REGRESSION",
        "W3-VER-W2-REGRESSION",
        "W3-VER-W3-REGRESSION",
        "W3-VER-FRONTEND-TYPECHECK",
        "W3-VER-FRONTEND-LINT",
        "W3-VER-FRONTEND-TESTS",
        "W3-VER-PROD-BUILD",
        "W3-VER-DEMO-BUILD",
    }
    if (
        len(GATES) != 9
        or len({gate.evidence_id for gate in GATES}) != 9
        or {gate.evidence_id for gate in GATES} != expected
    ):
        fail("build-gate fixed ID closure drifted")
    backend_count = len(discover_java_tests(BACKEND_ROOT / "src/test/java"))
    frontend_count = len(discover_vitest_files())
    if backend_count != 39 or frontend_count != 26:
        fail(
            "reviewed test-source counts drifted; "
            f"backend={backend_count}, frontend={frontend_count}"
        )
    return {
        "marker": (
            "W3_BUILD_GATE_PRODUCER_SELF_TEST=PASS "
            f"tests=41 gates=9 backendClasses={backend_count} "
            f"frontendFiles={frontend_count}"
        ),
    }


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Produce frozen-source W3 backend/frontend build leaves."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("self-test")
    execute_parser = subparsers.add_parser("execute")
    execute_parser.add_argument("--run-context", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    arguments = parse_arguments(argv or sys.argv[1:])
    try:
        if arguments.command == "self-test":
            print(self_test()["marker"])
            return 0
        result = execute(arguments.run_context)
        print(
            "W3_BUILD_GATES=PASS "
            f"runId={result['runId']} gates={result['gateCount']} "
            f"sourceHash={result['sourceTreeHash']} "
            f"commitMarker={result['commitMarker']}"
        )
        return 0
    except (BuildGateError, OSError, ET.ParseError) as error:
        print(f"[produce-wave3-build-gates] ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
