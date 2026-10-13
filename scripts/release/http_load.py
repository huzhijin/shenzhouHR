#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import os
import stat
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.release.common import (  # noqa: E402
    ContractError,
    read_json,
    require_loopback_url,
    validate_commit,
    validate_run_id,
)

SCHEMA_VERSION = "shenzhouhr.w9.performance-scenarios/v1"
MAX_CONCURRENCY = 100
MAX_TOTAL_REQUESTS = 10_000
MAX_TIMEOUT_SECONDS = 120.0
MAX_RESPONSE_BODY_BYTES = 64 * 1024
MAX_AUTH_FILE_BYTES = 8 * 1024
ALLOWED_AUTH_HEADERS = frozenset({"Authorization", "Cookie"})
EXPECTED_SCENARIO_ENDPOINTS = {
    "common-list": (
        "GET",
        "/api/v1/attendance-reports"
        "?reportType=ATTENDANCE_DETAIL&page=0&size=50",
        True,
        50,
        1000,
        0.99,
        2000,
    ),
    "personal-department-dashboard": (
        "GET",
        "/api/v1/attendance-reports"
        "?reportType=ATTENDANCE_RATE&page=0&size=50",
        True,
        50,
        1000,
        0.99,
        3000,
    ),
    "single-detail": (
        "GET",
        "/api/v1/attendance-reports"
        "?reportType=ATTENDANCE_DETAIL&page=0&size=1",
        True,
        50,
        1000,
        0.99,
        1000,
    ),
    "export-50000": (
        "POST",
        "/api/v1/attendance-reports/exports",
        False,
        1,
        1,
        1.0,
        60000,
    ),
}


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


def _finite_number(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError(f"{name} must be a finite number")
    normalized = float(value)
    if not math.isfinite(normalized):
        raise ContractError(f"{name} must be a finite number")
    return normalized


def _require_origin_url(base_url: str) -> str:
    if not isinstance(base_url, str) or not base_url or base_url != base_url.strip():
        raise ContractError("base URL must be a loopback origin")
    try:
        parsed = urlsplit(base_url)
        parsed.port
    except ValueError as exc:
        raise ContractError("base URL has an invalid port") from exc
    if parsed.path or parsed.query or parsed.fragment:
        raise ContractError("base URL must contain only an origin")
    return require_loopback_url(base_url)


def validate_scenario_contract(contract: dict[str, Any]) -> None:
    if contract.get("schema_version") != SCHEMA_VERSION:
        raise ContractError("unsupported performance scenario schema")
    capacity = contract.get("capacity")
    if not isinstance(capacity, dict):
        raise ContractError("capacity metadata must be an object")
    required_capacity = {
        "active_employees",
        "daily_peak_punches",
        "month_end_concurrency",
        "existing_database_bytes",
        "accumulation_months",
        "mysql_version",
        "flyway_version",
        "integrated_commit",
        "dataset_sha256",
    }
    if required_capacity - capacity.keys():
        raise ContractError("capacity metadata is incomplete")
    if capacity["accumulation_months"] != 36:
        raise ContractError("capacity model must cover exactly 36 months")

    scenarios = contract.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        raise ContractError("performance contract must contain scenarios")
    ids: set[str] = set()
    for scenario in scenarios:
        validate_scenario(scenario)
        if scenario["id"] in ids:
            raise ContractError(f"duplicate performance scenario: {scenario['id']}")
        ids.add(scenario["id"])
    if ids != {
        "common-list",
        "personal-department-dashboard",
        "single-detail",
        "export-50000",
    }:
        raise ContractError("performance contract must contain the four V1.9 scenarios")
    for scenario in scenarios:
        expected = EXPECTED_SCENARIO_ENDPOINTS[scenario["id"]]
        actual = (
            scenario["method"],
            scenario["path"],
            scenario["local_execution_supported"],
            scenario["concurrency"],
            scenario["total_requests"],
            scenario["minimum_success_rate"],
            scenario["p95_limit_ms"],
        )
        if actual != expected:
            raise ContractError(
                f"performance scenario endpoint differs from the formal API: "
                f"{scenario['id']}"
            )


def validate_scenario(scenario: dict[str, Any]) -> None:
    required = {
        "id",
        "method",
        "path",
        "concurrency",
        "total_requests",
        "timeout_seconds",
        "minimum_success_rate",
        "p95_limit_ms",
        "local_execution_supported",
        "description",
    }
    missing = required - scenario.keys()
    if missing:
        raise ContractError(f"scenario is missing fields: {sorted(missing)}")
    if scenario["method"] not in {"GET", "POST"}:
        raise ContractError("scenario method must be GET or POST")
    if not isinstance(scenario["local_execution_supported"], bool):
        raise ContractError("local_execution_supported must be boolean")
    if (
        scenario["method"] != "GET"
        and scenario["local_execution_supported"]
    ):
        raise ContractError(
            "the local HTTP harness cannot execute non-GET scenarios"
        )
    if not isinstance(scenario["path"], str):
        raise ContractError("scenario path must be an absolute HTTP path")
    try:
        parsed_path = urlsplit(scenario["path"])
    except ValueError as exc:
        raise ContractError("scenario path is invalid") from exc
    if (
        not scenario["path"].startswith("/")
        or scenario["path"].startswith("//")
        or parsed_path.scheme
        or parsed_path.netloc
        or parsed_path.fragment
    ):
        raise ContractError("scenario path must be an absolute same-origin HTTP path")

    concurrency = scenario["concurrency"]
    if (
        isinstance(concurrency, bool)
        or not isinstance(concurrency, int)
        or not 0 < concurrency <= MAX_CONCURRENCY
    ):
        raise ContractError(
            f"concurrency must be an integer in [1, {MAX_CONCURRENCY}]"
        )
    total_requests = scenario["total_requests"]
    if (
        isinstance(total_requests, bool)
        or not isinstance(total_requests, int)
        or not 0 < total_requests <= MAX_TOTAL_REQUESTS
    ):
        raise ContractError(
            f"total_requests must be an integer in [1, {MAX_TOTAL_REQUESTS}]"
        )
    timeout_seconds = _finite_number(
        scenario["timeout_seconds"], "timeout_seconds"
    )
    if not 0 < timeout_seconds <= MAX_TIMEOUT_SECONDS:
        raise ContractError(
            f"timeout_seconds must be in (0, {MAX_TIMEOUT_SECONDS}]"
        )
    minimum_success_rate = _finite_number(
        scenario["minimum_success_rate"], "minimum_success_rate"
    )
    if not 0 < minimum_success_rate <= 1:
        raise ContractError("minimum_success_rate must be in (0, 1]")
    if _finite_number(scenario["p95_limit_ms"], "p95_limit_ms") <= 0:
        raise ContractError("p95_limit_ms must be finite and positive")


def capacity_status(capacity: dict[str, Any]) -> tuple[str, list[str]]:
    missing = [
        name
        for name, value in capacity.items()
        if name != "accumulation_months" and (value is None or value == "")
    ]
    if capacity.get("accumulation_months") != 36:
        missing.append("accumulation_months=36")
    mysql_version = capacity.get("mysql_version")
    if mysql_version and not str(mysql_version).startswith("8.4."):
        missing.append("mysql_version=8.4.x")
    commit = capacity.get("integrated_commit")
    if commit:
        try:
            validate_commit(commit)
        except ContractError:
            missing.append("integrated_commit=40-hex-sha")
    return ("PASS", []) if not missing else ("NOT_VERIFIED", sorted(set(missing)))


def percentile(samples: list[float], fraction: float) -> float | None:
    if not samples:
        return None
    ordered = sorted(samples)
    rank = max(1, math.ceil(fraction * len(ordered)))
    return ordered[rank - 1]


def _validate_auth_headers(headers: dict[str, str] | None) -> dict[str, str]:
    if (
        not isinstance(headers, dict)
        or set(headers) not in ({header} for header in ALLOWED_AUTH_HEADERS)
    ):
        raise ContractError(
            "exactly one Authorization or Cookie credential header is required"
        )
    name, value = next(iter(headers.items()))
    if (
        not isinstance(value, str)
        or not value
        or value != value.strip()
        or len(value.encode("utf-8")) > MAX_AUTH_FILE_BYTES
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in value)
    ):
        raise ContractError(f"{name} credential header value is invalid")
    return {name: value}


def read_auth_header_file(
    path: Path,
    repository_root: Path,
) -> dict[str, str]:
    if not path.is_absolute():
        raise ContractError("authentication header file must use an absolute path")
    if path.is_symlink():
        raise ContractError(
            "authentication header file must be a non-symbolic regular file"
        )
    resolved_repository = repository_root.resolve()
    try:
        resolved_path = path.resolve(strict=True)
    except OSError as exc:
        raise ContractError("authentication header file is unavailable") from exc
    try:
        resolved_path.relative_to(resolved_repository)
    except ValueError:
        pass
    else:
        raise ContractError(
            "authentication header file must be outside the repository"
        )
    parent_stat = resolved_path.parent.stat()
    if (
        parent_stat.st_uid != os.getuid()
        or stat.S_IMODE(parent_stat.st_mode) & 0o022
    ):
        raise ContractError(
            "authentication header file parent must be current-user and "
            "not group/world writable"
        )
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(resolved_path, flags)
    except OSError as exc:
        raise ContractError(
            "authentication header file must be a non-symbolic regular file"
        ) from exc
    try:
        file_stat = os.fstat(descriptor)
        if (
            not stat.S_ISREG(file_stat.st_mode)
            or file_stat.st_uid != os.getuid()
            or stat.S_IMODE(file_stat.st_mode) != 0o600
        ):
            raise ContractError(
                "authentication header file must be current-user 0600"
            )
        content = os.read(descriptor, MAX_AUTH_FILE_BYTES + 1)
    finally:
        os.close(descriptor)
    if len(content) > MAX_AUTH_FILE_BYTES:
        raise ContractError("authentication header file is too large")
    try:
        text = content.decode("utf-8")
    except UnicodeError as exc:
        raise ContractError(
            "authentication header file must be valid UTF-8"
        ) from exc
    if text.endswith("\n"):
        text = text[:-1]
    if not text or "\n" in text or "\r" in text:
        raise ContractError(
            "authentication header file must contain exactly one header line"
        )
    for name in sorted(ALLOWED_AUTH_HEADERS):
        prefix = f"{name}: "
        if text.startswith(prefix):
            return _validate_auth_headers({name: text[len(prefix) :]})
    raise ContractError(
        "authentication header file permits only Authorization or Cookie"
    )


def _single_request(
    url: str,
    timeout_seconds: float,
    auth_headers: dict[str, str],
    scenario: dict[str, Any],
) -> tuple[int, float, bool]:
    started = time.perf_counter()
    status = 0
    body_complete = False
    try:
        request = urllib.request.Request(
            url,
            method="GET",
            headers={
                "Accept": "application/json",
                "User-Agent": "shenzhouhr-w9-stdlib-harness/1",
                **_validate_auth_headers(auth_headers),
            },
        )
        with _HTTP_OPENER.open(request, timeout=timeout_seconds) as response:
            status = int(response.status)
            body = response.read(MAX_RESPONSE_BODY_BYTES + 1)
            body_complete = (
                len(body) <= MAX_RESPONSE_BODY_BYTES
                and _response_matches_scenario(body, scenario)
            )
    except urllib.error.HTTPError as exc:
        try:
            status = int(exc.code)
            body_complete = (
                len(exc.read(MAX_RESPONSE_BODY_BYTES + 1))
                <= MAX_RESPONSE_BODY_BYTES
            )
        finally:
            exc.close()
    except (urllib.error.URLError, TimeoutError, OSError):
        status = 0
    return status, (time.perf_counter() - started) * 1000, body_complete


def _response_matches_scenario(
    body: bytes,
    scenario: dict[str, Any],
) -> bool:
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        return False
    expected_report_type = (
        "ATTENDANCE_RATE"
        if scenario["id"] == "personal-department-dashboard"
        else "ATTENDANCE_DETAIL"
    )
    expected_size = 1 if scenario["id"] == "single-detail" else 50
    return (
        isinstance(payload, dict)
        and payload.get("kind") == "REPORT"
        and payload.get("reportType") == expected_report_type
        and isinstance(payload.get("rows"), list)
        and payload.get("page") == 0
        and payload.get("size") == expected_size
    )


def run_scenario(
    scenario: dict[str, Any],
    base_url: str,
    auth_headers: dict[str, str],
) -> dict[str, Any]:
    validate_scenario(scenario)
    if (
        scenario["method"] != "GET"
        or scenario["local_execution_supported"] is not True
    ):
        raise ContractError(
            "this scenario requires the formal external producer and cannot "
            "be executed by the local HTTP harness"
        )
    validated_auth_headers = _validate_auth_headers(auth_headers)
    base_url = _require_origin_url(base_url)
    total_requests = scenario["total_requests"]
    url = f"{base_url}{scenario['path']}"
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=scenario["concurrency"]
    ) as executor:
        samples = list(
            executor.map(
                lambda _: _single_request(
                    url,
                    float(scenario["timeout_seconds"]),
                    validated_auth_headers,
                    scenario,
                ),
                range(total_requests),
            )
        )
    elapsed = max(time.perf_counter() - started, 0.000001)
    status_counts = Counter(status for status, _, _ in samples)
    durations = [duration for _, duration, _ in samples]
    complete_response_count = sum(1 for _, _, complete in samples if complete)
    successes = sum(
        1
        for status, _, complete in samples
        if 200 <= status < 300 and complete
    )
    success_rate = successes / len(samples)
    p95 = percentile(durations, 0.95)
    passed = (
        success_rate >= float(scenario["minimum_success_rate"])
        and complete_response_count == len(samples)
        and p95 is not None
        and p95 <= float(scenario["p95_limit_ms"])
    )
    return {
        "scenario_id": scenario["id"],
        "method": scenario["method"],
        "path": scenario["path"],
        "concurrency": scenario["concurrency"],
        "total_requests": total_requests,
        "measurement_status": "PASS" if passed else "FAIL",
        "release_gate_status": "NOT_VERIFIED",
        "reason": (
            "local harness measurement only; final integrated MySQL 8.4 run is required"
        ),
        "samples": len(samples),
        "complete_response_count": complete_response_count,
        "status_counts": {str(key): value for key, value in sorted(status_counts.items())},
        "success_rate": round(success_rate, 6),
        "minimum_success_rate": scenario["minimum_success_rate"],
        "throughput_rps": round(len(samples) / elapsed, 3),
        "p50_ms": round(percentile(durations, 0.50) or 0.0, 3),
        "p95_ms": round(p95 or 0.0, 3),
        "p99_ms": round(percentile(durations, 0.99) or 0.0, 3),
        "p95_limit_ms": scenario["p95_limit_ms"],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="W9 loopback-only HTTP load harness")
    parser.add_argument(
        "--contract",
        type=Path,
        default=Path(__file__).with_name("contracts")
        / "performance-scenarios-v1.json",
    )
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--base-url")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--run-id")
    parser.add_argument("--commit")
    parser.add_argument("--environment-id", default="local-loopback")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--auth-file", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)

    try:
        contract = read_json(args.contract)
        validate_scenario_contract(contract)
        scenario_by_id = {
            scenario["id"]: scenario for scenario in contract["scenarios"]
        }
        if args.scenario not in scenario_by_id:
            raise ContractError(f"unknown scenario: {args.scenario}")
        capacity_gate, missing_capacity = capacity_status(contract["capacity"])
        if not args.execute:
            plan = {
                "mode": "PLAN",
                "network_requests_sent": 0,
                "scenario": scenario_by_id[args.scenario],
                "capacity_gate_status": capacity_gate,
                "missing_capacity": missing_capacity,
                "release_gate_status": "NOT_VERIFIED",
            }
            print(json.dumps(plan, ensure_ascii=False, indent=2, sort_keys=True))
            return 0
        if (
            not args.base_url
            or not args.run_id
            or not args.commit
            or not args.auth_file
        ):
            raise ContractError(
                "--execute requires --base-url, --run-id, --commit and --auth-file"
            )
        validate_run_id(args.run_id)
        validate_commit(args.commit)
        auth_headers = read_auth_header_file(
            args.auth_file,
            args.repo.resolve(),
        )
        result = run_scenario(
            scenario_by_id[args.scenario],
            args.base_url,
            auth_headers,
        )
        report = {
            "schema_version": "shenzhouhr.w9.performance-evidence/v1",
            "run_id": args.run_id,
            "commit": args.commit,
            "environment_id": args.environment_id,
            "capacity_gate_status": capacity_gate,
            "missing_capacity": missing_capacity,
            "capacity": contract["capacity"],
            "result": result,
        }
    except ContractError as exc:
        print(f"W9_PERFORMANCE_INVALID={exc}", file=sys.stderr)
        return 2

    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0 if result["measurement_status"] != "FAIL" else 1


if __name__ == "__main__":
    raise SystemExit(main())
