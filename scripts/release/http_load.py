#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any

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
        "description",
    }
    missing = required - scenario.keys()
    if missing:
        raise ContractError(f"scenario is missing fields: {sorted(missing)}")
    if scenario["method"] != "GET":
        raise ContractError("independent HTTP harness only permits GET")
    if not isinstance(scenario["path"], str) or not scenario["path"].startswith("/"):
        raise ContractError("scenario path must be an absolute HTTP path")
    for name in ("concurrency", "total_requests"):
        if not isinstance(scenario[name], int) or scenario[name] < 0:
            raise ContractError(f"{name} must be a non-negative integer")
    if scenario["concurrency"] == 0 and scenario["total_requests"] > 0:
        raise ContractError("concurrency must be positive when requests are scheduled")
    if not 0 < float(scenario["minimum_success_rate"]) <= 1:
        raise ContractError("minimum_success_rate must be in (0, 1]")
    if float(scenario["p95_limit_ms"]) <= 0:
        raise ContractError("p95_limit_ms must be positive")


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


def _single_request(url: str, timeout_seconds: float) -> tuple[int, float]:
    started = time.perf_counter()
    status = 0
    try:
        request = urllib.request.Request(
            url,
            method="GET",
            headers={
                "Accept": "application/json",
                "User-Agent": "shenzhouhr-w9-stdlib-harness/1",
            },
        )
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            status = int(response.status)
            response.read()
    except urllib.error.HTTPError as exc:
        status = int(exc.code)
        exc.read()
    except (urllib.error.URLError, TimeoutError, OSError):
        status = 0
    return status, (time.perf_counter() - started) * 1000


def run_scenario(scenario: dict[str, Any], base_url: str) -> dict[str, Any]:
    validate_scenario(scenario)
    base_url = require_loopback_url(base_url)
    total_requests = scenario["total_requests"]
    if total_requests == 0:
        return {
            "scenario_id": scenario["id"],
            "measurement_status": "NOT_VERIFIED",
            "release_gate_status": "NOT_VERIFIED",
            "reason": "no requests were scheduled",
            "samples": 0,
            "status_counts": {},
            "success_rate": 0.0,
            "throughput_rps": 0.0,
            "p50_ms": None,
            "p95_ms": None,
            "p99_ms": None,
        }

    url = f"{base_url}{scenario['path']}"
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=scenario["concurrency"]
    ) as executor:
        samples = list(
            executor.map(
                lambda _: _single_request(url, float(scenario["timeout_seconds"])),
                range(total_requests),
            )
        )
    elapsed = max(time.perf_counter() - started, 0.000001)
    status_counts = Counter(status for status, _ in samples)
    durations = [duration for _, duration in samples]
    successes = sum(count for status, count in status_counts.items() if 200 <= status < 300)
    success_rate = successes / len(samples)
    p95 = percentile(durations, 0.95)
    passed = (
        success_rate >= float(scenario["minimum_success_rate"])
        and p95 is not None
        and p95 <= float(scenario["p95_limit_ms"])
    )
    return {
        "scenario_id": scenario["id"],
        "measurement_status": "PASS" if passed else "FAIL",
        "release_gate_status": "NOT_VERIFIED",
        "reason": (
            "local harness measurement only; final integrated MySQL 8.4 run is required"
        ),
        "samples": len(samples),
        "status_counts": {str(key): value for key, value in sorted(status_counts.items())},
        "success_rate": round(success_rate, 6),
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
        if not args.base_url or not args.run_id or not args.commit:
            raise ContractError(
                "--execute requires --base-url, --run-id and --commit"
            )
        validate_run_id(args.run_id)
        validate_commit(args.commit)
        result = run_scenario(scenario_by_id[args.scenario], args.base_url)
        report = {
            "schema_version": "shenzhouhr.w9.performance-evidence/v1",
            "run_id": args.run_id,
            "commit": args.commit,
            "environment_id": args.environment_id,
            "capacity_gate_status": capacity_gate,
            "missing_capacity": missing_capacity,
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
