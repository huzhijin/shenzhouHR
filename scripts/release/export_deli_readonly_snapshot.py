#!/usr/bin/env python3
"""Export a fail-closed, read-only Deli E+ authority snapshot.

The executable surface is deliberately fixed: department and employee offset
queries plus the CHECKIN checkin_query cursor command. There are no command-line
options or transport hooks that can select another vendor path or command.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.release.common import (  # noqa: E402
    ContractError,
    require_secure_secret_file,
    validate_run_id,
)

SCHEMA_VERSION = "shenzhouhr.deli-readonly-snapshot/v1"
OFFICIAL_ORIGIN = "https://v2-api.delicloud.com"
DEPARTMENT_PATH = "/v2.0/department/query"
EMPLOYEE_PATH = "/v2.0/employee/query"
CHECKIN_PATH = "/v2.0/cloudappapi"
CHECKIN_MODULE = "CHECKIN"
CHECKIN_COMMAND = "checkin_query"
OFFSET_PAGE_SIZE = 100
CHECKIN_PAGE_SIZE = 500
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_ENV_BYTES = 16 * 1024
MAX_PAGES_PER_DATASET = 10_000
REQUEST_TIMEOUT_SECONDS = 15.0
ALLOWED_ENV_KEYS = frozenset(
    {
        "DELI_EPLUS_APP_KEY",
        "DELI_EPLUS_APP_SECRET",
        "DELI_EPLUS_BASE_URL",
        "DELI_EPLUS_ENABLED",
        "DELI_EPLUS_PAGE_SIZE",
        "DELI_EPLUS_SOURCE_TIME_ZONE",
    }
)


class SnapshotError(ContractError):
    """A safe, credential-free snapshot contract failure."""


@dataclass(frozen=True, repr=False)
class Credentials:
    app_key: str
    app_secret: str

    def __repr__(self) -> str:
        return "Credentials[app_key=<redacted>, app_secret=<redacted>]"


@dataclass(frozen=True)
class EndpointSpec:
    dataset: str
    path: str
    row_field: str
    api_module: str | None = None
    api_command: str | None = None


ENDPOINTS = {
    "departments": EndpointSpec(
        dataset="departments",
        path=DEPARTMENT_PATH,
        row_field="rows",
    ),
    "employees": EndpointSpec(
        dataset="employees",
        path=EMPLOYEE_PATH,
        row_field="rows",
    ),
    "checkins": EndpointSpec(
        dataset="checkins",
        path=CHECKIN_PATH,
        row_field="data",
        api_module=CHECKIN_MODULE,
        api_command=CHECKIN_COMMAND,
    ),
}


@dataclass(frozen=True, repr=False)
class ApiRequest:
    dataset: str
    method: str
    path: str
    headers: dict[str, str]
    payload: dict[str, int]
    body: bytes

    def __repr__(self) -> str:
        return (
            "ApiRequest[dataset="
            f"{self.dataset}, method={self.method}, path={self.path}, "
            f"header_names={sorted(self.headers)}, body=<redacted>]"
        )


@dataclass(frozen=True, repr=False)
class ApiResponse:
    status_code: int
    body: bytes

    def __repr__(self) -> str:
        return f"ApiResponse[status_code={self.status_code}, body=<redacted>]"


@dataclass(frozen=True)
class SnapshotResult:
    bundle_directory: Path
    manifest: dict[str, Any]
    manifest_sha256: str


Transport = Callable[[ApiRequest], ApiResponse]


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(
        self,
        req: urllib.request.Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> None:
        return None


_HTTP_OPENER = urllib.request.build_opener(
    urllib.request.ProxyHandler({}),
    _NoRedirectHandler(),
)


def readonly_plan(run_id: str) -> dict[str, Any]:
    validate_run_id(run_id)
    return {
        "schema_version": "shenzhouhr.deli-readonly-snapshot-plan/v1",
        "run_id": run_id,
        "mode": "PLAN",
        "network_requests": 0,
        "execute_required": True,
        "official_origin": OFFICIAL_ORIGIN,
        "allowed_operations": [
            {"method": "POST", "path": DEPARTMENT_PATH},
            {"method": "POST", "path": EMPLOYEE_PATH},
            {
                "method": "POST",
                "path": CHECKIN_PATH,
                "api_module": CHECKIN_MODULE,
                "api_command": CHECKIN_COMMAND,
            },
        ],
        "initialization_requests": 0,
        "write_requests": 0,
        "credential_values_logged": False,
        "publication": "atomic 0700 directory containing only 0600 files",
    }


def _read_secure_file(path: Path, maximum_bytes: int) -> bytes:
    flags = os.O_RDONLY
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise SnapshotError("credential file could not be opened safely") from exc
    try:
        file_stat = os.fstat(descriptor)
        if (
            not stat.S_ISREG(file_stat.st_mode)
            or file_stat.st_uid != os.getuid()
            or stat.S_IMODE(file_stat.st_mode) != 0o600
        ):
            raise SnapshotError("credential file must be current-user 0600")
        chunks: list[bytes] = []
        remaining = maximum_bytes + 1
        while remaining > 0:
            chunk = os.read(descriptor, min(4096, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        content = b"".join(chunks)
    finally:
        os.close(descriptor)
    if len(content) > maximum_bytes:
        raise SnapshotError("credential file is too large")
    return content


def _safe_credential(value: str) -> bool:
    return (
        0 < len(value) <= 512
        and value == value.strip()
        and all(
            ord(character) >= 32 and ord(character) not in {127, 0x2028, 0x2029}
            for character in value
        )
    )


def read_credentials(
    env_file: Path,
    repository_root: Path,
) -> Credentials:
    secure_path = require_secure_secret_file(env_file, repository_root)
    try:
        text = _read_secure_file(secure_path, MAX_ENV_BYTES).decode("utf-8")
    except UnicodeError as exc:
        raise SnapshotError("credential file must be valid UTF-8") from exc

    values: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line or line.startswith("export "):
            raise SnapshotError("credential file contains an invalid assignment")
        name, value = line.split("=", 1)
        if name not in ALLOWED_ENV_KEYS:
            raise SnapshotError("credential file contains an unsupported variable")
        if name in values:
            raise SnapshotError("credential file contains a duplicate variable")
        if value != value.strip() or "\x00" in value:
            raise SnapshotError("credential file contains an invalid value")
        values[name] = value

    app_key = values.get("DELI_EPLUS_APP_KEY", "")
    app_secret = values.get("DELI_EPLUS_APP_SECRET", "")
    if not _safe_credential(app_key) or not _safe_credential(app_secret):
        raise SnapshotError("credential file does not contain safe Deli credentials")
    configured_origin = values.get("DELI_EPLUS_BASE_URL", OFFICIAL_ORIGIN)
    if configured_origin.rstrip("/") != OFFICIAL_ORIGIN:
        raise SnapshotError("credential file must use the official Deli HTTPS origin")
    enabled = values.get("DELI_EPLUS_ENABLED")
    if enabled is not None and enabled.lower() not in {"true", "false"}:
        raise SnapshotError("credential file has an invalid enabled flag")
    page_size = values.get("DELI_EPLUS_PAGE_SIZE")
    if page_size is not None:
        if not page_size.isdigit() or not 1 <= int(page_size) <= CHECKIN_PAGE_SIZE:
            raise SnapshotError("credential file has an invalid page size")
    source_time_zone = values.get("DELI_EPLUS_SOURCE_TIME_ZONE")
    if source_time_zone is not None and (
        not source_time_zone
        or len(source_time_zone) > 64
        or any(ord(character) < 33 for character in source_time_zone)
    ):
        raise SnapshotError("credential file has an invalid source time zone")
    return Credentials(app_key=app_key, app_secret=app_secret)


def _prepare_output_parent(path: Path, repository_root: Path) -> Path:
    if not path.is_absolute() or path.is_symlink():
        raise SnapshotError("output directory must be an absolute non-symlink path")
    resolved_repository = repository_root.resolve()
    existed = path.exists()
    try:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        resolved = path.resolve(strict=True)
    except OSError as exc:
        raise SnapshotError("output directory could not be created safely") from exc
    try:
        resolved.relative_to(resolved_repository)
    except ValueError:
        pass
    else:
        raise SnapshotError("snapshot output must remain outside the repository")
    if resolved.is_symlink() or not resolved.is_dir():
        raise SnapshotError("output path must resolve to a regular directory")
    if not existed:
        os.chmod(resolved, 0o700)
    directory_stat = resolved.stat()
    if directory_stat.st_uid != os.getuid():
        raise SnapshotError("output directory must be owned by the current user")
    if stat.S_IMODE(directory_stat.st_mode) != 0o700:
        raise SnapshotError("output directory mode must be exactly 0700")
    return resolved


def _secure_mkdir(path: Path) -> None:
    try:
        path.mkdir(mode=0o700)
        os.chmod(path, 0o700)
    except OSError as exc:
        raise SnapshotError("snapshot staging directory could not be created") from exc
    if path.is_symlink() or not path.is_dir():
        raise SnapshotError("snapshot staging path is not a regular directory")


def _write_secure_bytes(path: Path, content: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags, 0o600)
    except OSError as exc:
        raise SnapshotError("snapshot artifact could not be created exclusively") from exc
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            descriptor = -1
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _fsync_directory(path: Path) -> None:
    flags = os.O_RDONLY
    if hasattr(os, "O_DIRECTORY"):
        flags |= os.O_DIRECTORY
    descriptor = os.open(path, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _timestamp_millis() -> str:
    value = str(time.time_ns() // 1_000_000)
    if len(value) != 13 or not value.isdigit():
        raise SnapshotError("system clock cannot produce a Deli timestamp")
    return value


def _sign(path: str, timestamp: str, credentials: Credentials) -> str:
    material = (
        path + timestamp + credentials.app_key + credentials.app_secret
    ).encode("utf-8")
    return hashlib.md5(material, usedforsecurity=False).hexdigest()


def _validate_payload(dataset: str, payload: dict[str, int]) -> None:
    if dataset in {"departments", "employees"}:
        if set(payload) != {"offset", "limit"}:
            raise SnapshotError("offset query payload differs from the read-only contract")
        offset = payload["offset"]
        limit = payload["limit"]
        if (
            isinstance(offset, bool)
            or not isinstance(offset, int)
            or offset < 0
            or limit != OFFSET_PAGE_SIZE
        ):
            raise SnapshotError("offset query payload is invalid")
        return
    if dataset == "checkins":
        if set(payload) != {"next_id", "page_size"}:
            raise SnapshotError("check-in payload differs from the read-only contract")
        next_id = payload["next_id"]
        if (
            isinstance(next_id, bool)
            or not isinstance(next_id, int)
            or next_id < 0
            or payload["page_size"] != CHECKIN_PAGE_SIZE
        ):
            raise SnapshotError("check-in query payload is invalid")
        return
    raise SnapshotError("unsupported Deli snapshot dataset")


def _build_request(
    dataset: str,
    payload: dict[str, int],
    credentials: Credentials,
) -> ApiRequest:
    spec = ENDPOINTS.get(dataset)
    if spec is None:
        raise SnapshotError("unsupported Deli snapshot dataset")
    _validate_payload(dataset, payload)
    timestamp = _timestamp_millis()
    headers = {
        "Accept": "application/json",
        "Content-Type": "application/json; charset=UTF-8",
        "App-Key": credentials.app_key,
        "App-Timestamp": timestamp,
        "App-Sig": _sign(spec.path, timestamp, credentials),
        "User-Agent": "shenzhouhr-deli-readonly-snapshot/1",
    }
    if spec.api_module is not None:
        headers["Api-Module"] = spec.api_module
        headers["Api-Cmd"] = spec.api_command or ""
    body = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    request = ApiRequest(
        dataset=dataset,
        method="POST",
        path=spec.path,
        headers=headers,
        payload=dict(payload),
        body=body,
    )
    _validate_request_contract(request)
    return request


def _validate_request_contract(request: ApiRequest) -> None:
    spec = ENDPOINTS.get(request.dataset)
    if (
        spec is None
        or request.method != "POST"
        or request.path != spec.path
        or not request.path.startswith("/v2.0/")
    ):
        raise SnapshotError("Deli request is outside the read-only allowlist")
    _validate_payload(request.dataset, request.payload)
    if request.dataset == "checkins":
        if (
            request.headers.get("Api-Module") != CHECKIN_MODULE
            or request.headers.get("Api-Cmd") != CHECKIN_COMMAND
        ):
            raise SnapshotError("Deli check-in command is outside the read-only allowlist")
    elif "Api-Module" in request.headers or "Api-Cmd" in request.headers:
        raise SnapshotError("directory query must not carry cloud-app commands")


def _http_post(request: ApiRequest) -> ApiResponse:
    _validate_request_contract(request)
    http_request = urllib.request.Request(
        OFFICIAL_ORIGIN + request.path,
        data=request.body,
        headers=request.headers,
        method="POST",
    )
    try:
        with _HTTP_OPENER.open(
            http_request,
            timeout=REQUEST_TIMEOUT_SECONDS,
        ) as response:
            status_code = int(response.status)
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as exc:
        status_code = int(exc.code)
        exc.close()
        raise SnapshotError(
            f"Deli request failed with HTTP status {status_code}"
        ) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise SnapshotError("Deli request could not be completed") from None
    if len(body) > MAX_RESPONSE_BYTES:
        raise SnapshotError("Deli response exceeded the safe size limit")
    return ApiResponse(status_code=status_code, body=body)


def _invoke(
    request: ApiRequest,
    credentials: Credentials,
    transport: Transport,
) -> bytes:
    try:
        response = transport(request)
    except SnapshotError:
        raise
    except Exception:
        raise SnapshotError("Deli request could not be completed") from None
    if not isinstance(response, ApiResponse):
        raise SnapshotError("Deli transport returned an invalid response")
    if response.status_code != 200:
        raise SnapshotError(
            f"Deli request failed with HTTP status {response.status_code}"
        )
    if not isinstance(response.body, bytes) or len(response.body) > MAX_RESPONSE_BYTES:
        raise SnapshotError("Deli response exceeded the safe size limit")
    for credential in (credentials.app_key, credentials.app_secret):
        if credential.encode("utf-8") in response.body:
            raise SnapshotError("Deli response contained credential material")
    return response.body


def _parse_vendor_data(body: bytes) -> dict[str, Any]:
    try:
        root = json.loads(body.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        raise SnapshotError("Deli returned an invalid JSON response") from None
    if not isinstance(root, dict):
        raise SnapshotError("Deli response root must be an object")
    code = root.get("code")
    if isinstance(code, bool) or not isinstance(code, int):
        raise SnapshotError("Deli response code is invalid")
    if code != 0:
        raise SnapshotError(f"Deli rejected the request with code {code}")
    data = root.get("data")
    if not isinstance(data, dict):
        raise SnapshotError("Deli response data must be an object")
    return data


def _validate_rows(rows: Any, *, maximum: int, dataset: str) -> list[dict[str, Any]]:
    if not isinstance(rows, list) or len(rows) > maximum:
        raise SnapshotError(f"Deli {dataset} rows violate the page contract")
    if any(not isinstance(row, dict) for row in rows):
        raise SnapshotError(f"Deli {dataset} page contains a non-object row")
    return rows


def _write_raw_page(
    staging: Path,
    dataset: str,
    page_number: int,
    body: bytes,
) -> tuple[str, str, int]:
    relative = Path(dataset) / f"page-{page_number:06d}.response.json"
    path = staging / relative
    _write_secure_bytes(path, body)
    return relative.as_posix(), hashlib.sha256(body).hexdigest(), len(body)


def _export_offset_dataset(
    *,
    dataset: str,
    staging: Path,
    credentials: Credentials,
    transport: Transport,
) -> tuple[int, list[dict[str, Any]]]:
    spec = ENDPOINTS[dataset]
    offset = 0
    total_rows = 0
    pages: list[dict[str, Any]] = []
    for page_number in range(1, MAX_PAGES_PER_DATASET + 1):
        request = _build_request(
            dataset,
            {"offset": offset, "limit": OFFSET_PAGE_SIZE},
            credentials,
        )
        body = _invoke(request, credentials, transport)
        data = _parse_vendor_data(body)
        rows = _validate_rows(
            data.get(spec.row_field),
            maximum=OFFSET_PAGE_SIZE,
            dataset=dataset,
        )
        row_count = len(rows)
        next_offset = offset + row_count
        terminal = row_count < OFFSET_PAGE_SIZE
        relative, digest, byte_count = _write_raw_page(
            staging, dataset, page_number, body
        )
        pages.append(
            {
                "dataset": dataset,
                "page_number": page_number,
                "file": relative,
                "input_cursor": str(offset),
                "output_cursor": str(next_offset),
                "row_count": row_count,
                "response_bytes": byte_count,
                "response_sha256": digest,
                "terminal": terminal,
            }
        )
        total_rows += row_count
        if terminal:
            return total_rows, pages
        if next_offset <= offset:
            raise SnapshotError(f"Deli {dataset} offset did not advance")
        offset = next_offset
    raise SnapshotError(f"Deli {dataset} exceeded the safe page limit")


def _epoch_second(value: Any) -> int:
    if isinstance(value, bool):
        raise SnapshotError("Deli check-in time is invalid")
    if isinstance(value, int):
        epoch_second = value
    elif isinstance(value, str) and value.isdigit():
        epoch_second = int(value)
    else:
        raise SnapshotError("Deli check-in time is invalid")
    if epoch_second < 0:
        raise SnapshotError("Deli check-in time is invalid")
    try:
        datetime.fromtimestamp(epoch_second, UTC)
    except (OverflowError, OSError, ValueError):
        raise SnapshotError("Deli check-in time is invalid") from None
    return epoch_second


def _cursor(value: Any) -> str:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise SnapshotError("Deli check-in cursor is invalid")
    return str(value)


def _export_checkins(
    *,
    staging: Path,
    credentials: Credentials,
    transport: Transport,
) -> tuple[int, list[dict[str, Any]], int | None, int | None]:
    cursor = "0"
    seen_cursors = {cursor}
    total_rows = 0
    minimum_time: int | None = None
    maximum_time: int | None = None
    pages: list[dict[str, Any]] = []
    for page_number in range(1, MAX_PAGES_PER_DATASET + 1):
        request = _build_request(
            "checkins",
            {"next_id": int(cursor), "page_size": CHECKIN_PAGE_SIZE},
            credentials,
        )
        body = _invoke(request, credentials, transport)
        data = _parse_vendor_data(body)
        rows = _validate_rows(
            data.get(ENDPOINTS["checkins"].row_field),
            maximum=CHECKIN_PAGE_SIZE,
            dataset="checkins",
        )
        next_cursor = _cursor(data.get("next_id"))
        for row in rows:
            check_time = _epoch_second(row.get("check_time"))
            minimum_time = (
                check_time if minimum_time is None else min(minimum_time, check_time)
            )
            maximum_time = (
                check_time if maximum_time is None else max(maximum_time, check_time)
            )
        terminal = not rows and next_cursor == cursor
        relative, digest, byte_count = _write_raw_page(
            staging, "checkins", page_number, body
        )
        pages.append(
            {
                "dataset": "checkins",
                "page_number": page_number,
                "file": relative,
                "input_cursor": cursor,
                "output_cursor": next_cursor,
                "row_count": len(rows),
                "response_bytes": byte_count,
                "response_sha256": digest,
                "terminal": terminal,
            }
        )
        total_rows += len(rows)
        if terminal:
            return total_rows, pages, minimum_time, maximum_time
        if next_cursor in seen_cursors:
            raise SnapshotError("Deli check-in cursor repeated before completion")
        seen_cursors.add(next_cursor)
        cursor = next_cursor
    raise SnapshotError("Deli check-ins exceeded the safe page limit")


def _time_range(minimum_time: int | None, maximum_time: int | None) -> Any:
    if minimum_time is None or maximum_time is None:
        return None
    return {
        "minimum_epoch_second": minimum_time,
        "maximum_epoch_second": maximum_time,
        "minimum_utc": datetime.fromtimestamp(minimum_time, UTC).isoformat(),
        "maximum_utc": datetime.fromtimestamp(maximum_time, UTC).isoformat(),
    }


def _snapshot_digest(
    pages: list[dict[str, Any]],
    counts: dict[str, int],
    checkin_time_range: Any,
) -> str:
    canonical = json.dumps(
        {
            "pages": pages,
            "counts": counts,
            "checkin_time_range": checkin_time_range,
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def export_snapshot(
    *,
    repository_root: Path,
    run_id: str,
    env_file: Path,
    output_directory: Path,
    transport: Transport | None = None,
) -> SnapshotResult:
    validate_run_id(run_id)
    output_parent = _prepare_output_parent(output_directory, repository_root)
    final_directory = output_parent / run_id
    if final_directory.exists() or final_directory.is_symlink():
        raise SnapshotError("refusing to overwrite an existing snapshot bundle")
    credentials = read_credentials(env_file, repository_root)
    effective_transport = transport or _http_post
    staging = Path(
        tempfile.mkdtemp(
            prefix=f".{run_id}.partial-",
            dir=output_parent,
        )
    )
    os.chmod(staging, 0o700)
    published = False
    try:
        for dataset in ENDPOINTS:
            _secure_mkdir(staging / dataset)
        started_at = datetime.now(UTC)
        department_count, department_pages = _export_offset_dataset(
            dataset="departments",
            staging=staging,
            credentials=credentials,
            transport=effective_transport,
        )
        employee_count, employee_pages = _export_offset_dataset(
            dataset="employees",
            staging=staging,
            credentials=credentials,
            transport=effective_transport,
        )
        (
            checkin_count,
            checkin_pages,
            minimum_time,
            maximum_time,
        ) = _export_checkins(
            staging=staging,
            credentials=credentials,
            transport=effective_transport,
        )
        completed_at = datetime.now(UTC)
        pages = department_pages + employee_pages + checkin_pages
        counts = {
            "departments": department_count,
            "employees": employee_count,
            "checkins": checkin_count,
            "total_rows": department_count + employee_count + checkin_count,
        }
        page_counts = {
            dataset: sum(1 for page in pages if page["dataset"] == dataset)
            for dataset in ENDPOINTS
        }
        checkin_time_range = _time_range(minimum_time, maximum_time)
        manifest: dict[str, Any] = {
            "schema_version": SCHEMA_VERSION,
            "run_id": run_id,
            "status": "PASS",
            "mode": "READ_ONLY",
            "official_origin": OFFICIAL_ORIGIN,
            "started_at_utc": started_at.isoformat(),
            "completed_at_utc": completed_at.isoformat(),
            "allowed_operations": readonly_plan(run_id)["allowed_operations"],
            "initialization_requests": 0,
            "write_requests": 0,
            "credential_values_logged": False,
            "credential_values_persisted": False,
            "counts": counts,
            "page_counts": page_counts,
            "checkin_time_range": checkin_time_range,
            "pages": pages,
            "snapshot_sha256": _snapshot_digest(
                pages, counts, checkin_time_range
            ),
            "manifest_sha256_file": "manifest.sha256",
        }
        manifest_bytes = (
            json.dumps(
                manifest,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
            + "\n"
        ).encode("utf-8")
        manifest_digest = hashlib.sha256(manifest_bytes).hexdigest()
        _write_secure_bytes(staging / "manifest.json", manifest_bytes)
        _write_secure_bytes(
            staging / "manifest.sha256",
            f"{manifest_digest}  manifest.json\n".encode("ascii"),
        )
        for dataset in ENDPOINTS:
            _fsync_directory(staging / dataset)
        _fsync_directory(staging)
        if final_directory.exists() or final_directory.is_symlink():
            raise SnapshotError("refusing to overwrite an existing snapshot bundle")
        os.rename(staging, final_directory)
        published = True
        return SnapshotResult(
            bundle_directory=final_directory,
            manifest=manifest,
            manifest_sha256=manifest_digest,
        )
    except BaseException:
        if not published:
            shutil.rmtree(staging, ignore_errors=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fail-closed read-only Deli E+ snapshot exporter"
    )
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--run-id", default="deli-readonly-plan")
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    try:
        if not args.execute:
            print(
                json.dumps(
                    readonly_plan(args.run_id),
                    ensure_ascii=False,
                    indent=2,
                    sort_keys=True,
                )
            )
            return 0
        if args.env_file is None or args.output_dir is None:
            raise SnapshotError("--execute requires --env-file and --output-dir")
        result = export_snapshot(
            repository_root=args.repo.resolve(),
            run_id=args.run_id,
            env_file=args.env_file,
            output_directory=args.output_dir,
        )
        print(
            json.dumps(
                {
                    "status": "PASS",
                    "bundle_directory": str(result.bundle_directory),
                    "manifest_sha256": result.manifest_sha256,
                    "counts": result.manifest["counts"],
                    "credential_values_logged": False,
                },
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
        )
        return 0
    except ContractError as exc:
        print(f"DELI_READONLY_SNAPSHOT_FAILED={exc}", file=sys.stderr)
        return 2
    except OSError:
        print(
            "DELI_READONLY_SNAPSHOT_FAILED=secure filesystem operation failed",
            file=sys.stderr,
        )
        return 2
    except Exception:
        print(
            "DELI_READONLY_SNAPSHOT_FAILED=unexpected safe failure",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
