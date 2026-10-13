#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.release.common import (  # noqa: E402
    ContractError,
    read_json,
    validate_commit,
    validate_run_id,
)
from scripts.release.release_evidence import aggregate_status  # noqa: E402


@dataclass(frozen=True)
class StaticCheck:
    check_id: str
    passed: bool
    detail: str


def _read(repository: Path, relative: str) -> str:
    path = repository / relative
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ContractError(f"cannot read required threat input {relative}: {exc}") from exc


def _contains_all(text: str, tokens: tuple[str, ...]) -> bool:
    return all(token in text for token in tokens)


def run_static_checks(repository: Path) -> list[StaticCheck]:
    nginx = _read(repository, "deploy/nginx/shenzhouhr.conf")
    nginx_http = _read(repository, "deploy/nginx/shenzhouhr-http.conf")
    systemd = _read(repository, "deploy/systemd/shenzhouhr.service")
    application = _read(repository, "backend/src/main/resources/application.yml")
    env_example = _read(repository, ".env.example")
    frontend_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((repository / "frontend/src").rglob("*"))
        if path.is_file() and path.suffix in {".ts", ".tsx"}
    )
    frontend_sources += "\n" + _read(repository, "frontend/vite.config.ts")

    checks: tuple[tuple[str, Callable[[], bool], str], ...] = (
        (
            "STATIC-NGINX-TRANSPORT",
            lambda: _contains_all(
                nginx,
                (
                    "listen 443 ssl http2;",
                    "Strict-Transport-Security",
                    "Content-Security-Policy",
                    "X-Frame-Options",
                    "server_tokens off;",
                ),
            ),
            "TLS and browser security headers",
        ),
        (
            "STATIC-NGINX-NO-STORE",
            lambda: _contains_all(
                nginx,
                (
                    'set $shenzhouhr_cache_control "no-store, max-age=0";',
                    "proxy_hide_header Cache-Control;",
                    "etag off;",
                ),
            ),
            "API and SPA cache prevention",
        ),
        (
            "STATIC-NGINX-ABUSE-LIMITS",
            lambda: _contains_all(
                nginx,
                (
                    "limit_req zone=shenzhouhr_api",
                    "limit_conn shenzhouhr_per_ip",
                    "client_max_body_size 21m;",
                ),
            )
            and _contains_all(
                nginx_http,
                ("limit_req_zone", "limit_conn_zone", "log_format shenzhouhr_safe"),
            ),
            "API rate, connection and upload limits",
        ),
        (
            "STATIC-NGINX-QUERY-REDACTION",
            lambda: "$uri" in nginx_http and "$request_uri" not in nginx_http,
            "access log excludes query strings",
        ),
        (
            "STATIC-SYSTEMD-SANDBOX",
            lambda: _contains_all(
                systemd,
                (
                    "User=shenzhouhr",
                    "NoNewPrivileges=true",
                    "ProtectSystem=strict",
                    "ProtectHome=true",
                    "RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6",
                    "CapabilityBoundingSet=",
                    "SystemCallFilter=@system-service",
                    "StateDirectory=shenzhouhr",
                ),
            ),
            "non-root service and systemd sandbox",
        ),
        (
            "STATIC-PROD-DEV-BYPASS-OFF",
            lambda: "development-principal:\n    enabled: false" in application
            and "include-message: never" in application
            and "include-stacktrace: never" in application,
            "production application defaults disable development bypass and error detail",
        ),
        (
            "STATIC-ENV-TEMPLATE-HYGIENE",
            lambda: not any(
                forbidden in env_example
                for forbidden in (
                    "JWT_SECRET=",
                    "change-me",
                    "postgresql://",
                    "REDIS_URL=",
                    "SMTP_URL=",
                )
            ),
            "repository environment template has no stale secret-bearing stack",
        ),
        (
            "STATIC-REACT-ROUTER-NO-RSC",
            lambda: not any(
                forbidden in frontend_sources
                for forbidden in (
                    "@react-router/dev",
                    "react-router/server",
                    "react-server-dom-",
                    "unstable_RSC",
                    "RSCHydratedRouter",
                    "RSCStaticRouter",
                )
            ),
            "SPA/library mode does not use unstable React Router RSC APIs",
        ),
    )
    return [
        StaticCheck(check_id, bool(check()), detail)
        for check_id, check, detail in checks
    ]


def validate_threat_matrix(matrix: dict[str, object]) -> None:
    if matrix.get("schema_version") != "shenzhouhr.w9.threat-matrix/v1":
        raise ContractError("unsupported threat matrix schema")
    if matrix.get("data_policy") != "SYNTHETIC_ONLY":
        raise ContractError("threat matrix must require synthetic data")
    if matrix.get("authorization_required") is not True:
        raise ContractError("dynamic threat matrix must require authorization")
    cases = matrix.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ContractError("threat matrix must contain cases")
    ids: set[str] = set()
    categories: set[str] = set()
    for case in cases:
        if not isinstance(case, dict):
            raise ContractError("threat case must be an object")
        required = {"id", "category", "mutation", "expected"}
        if required - case.keys():
            raise ContractError("threat case is missing required fields")
        case_id = str(case["id"])
        if case_id in ids:
            raise ContractError(f"duplicate threat case: {case_id}")
        ids.add(case_id)
        categories.add(str(case["category"]))
    required_categories = {
        "BOLA_IDOR",
        "CROSS_SCOPE",
        "OBJECT_ENUMERATION",
        "BULK_EXPORT",
        "CACHE_SESSION",
        "SENSITIVE_FIELD",
        "DEVELOPMENT_BYPASS",
        "SENSITIVE_LOGGING",
        "BROWSER_BOUNDARY",
        "PAYROLL_NON_DISCOVERY",
    }
    if categories != required_categories:
        raise ContractError(
            f"threat categories differ: missing={sorted(required_categories - categories)} "
            f"unexpected={sorted(categories - required_categories)}"
        )


def build_report(
    repository: Path,
    *,
    run_id: str,
    commit: str,
    environment_id: str,
    matrix_path: Path,
) -> dict[str, object]:
    validate_run_id(run_id)
    validate_commit(commit)
    matrix = read_json(matrix_path)
    validate_threat_matrix(matrix)
    static_checks = run_static_checks(repository)
    static_status = (
        "PASS" if static_checks and all(check.passed for check in static_checks) else "FAIL"
    )
    dynamic_status = "NOT_VERIFIED"
    return {
        "schema_version": "shenzhouhr.w9.threat-evidence/v1",
        "run_id": run_id,
        "commit": commit,
        "environment_id": environment_id,
        "data_policy": "SYNTHETIC_ONLY",
        "static_status": static_status,
        "dynamic_status": dynamic_status,
        "status": aggregate_status((static_status, dynamic_status)),
        "static_checks": [asdict(check) for check in static_checks],
        "dynamic_cases": [
            {
                "id": case["id"],
                "category": case["category"],
                "status": "NOT_VERIFIED",
                "reason": "requires W7 FINAL, explicit authorization and synthetic identities",
            }
            for case in matrix["cases"]
        ],
        "release_authorized": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run offline W9 threat contracts")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument(
        "--matrix",
        type=Path,
        default=Path(__file__).with_name("contracts") / "threat-matrix-v1.json",
    )
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--environment-id", default="local-static")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)

    try:
        report = build_report(
            args.repo.resolve(),
            run_id=args.run_id,
            commit=args.commit,
            environment_id=args.environment_id,
            matrix_path=args.matrix,
        )
    except ContractError as exc:
        print(f"W9_THREAT_INVALID={exc}", file=sys.stderr)
        return 2

    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    print(f"W9_THREAT_STATIC={report['static_status']}", file=sys.stderr)
    print(f"W9_THREAT_RELEASE={report['status']}", file=sys.stderr)
    return 0 if report["static_status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
