#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import stat
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.release.common import ContractError, read_json, sha256_file  # noqa: E402

REQUIRED_ENVIRONMENT_KEYS = {
    "SPRING_PROFILES_ACTIVE",
    "SHENZHOUHR_DB_URL",
    "SHENZHOUHR_DB_USERNAME",
    "SHENZHOUHR_DB_PASSWORD",
    "SHENZHOUHR_FLYWAY_ENABLED",
}


@dataclass(frozen=True)
class PreflightCheck:
    check_id: str
    status: str
    detail: str


def _file_text(repository: Path, relative: str) -> str:
    try:
        return (repository / relative).read_text(encoding="utf-8")
    except OSError as exc:
        raise ContractError(f"cannot read {relative}: {exc}") from exc


def validate_deployment_templates(repository: Path) -> list[PreflightCheck]:
    nginx = _file_text(repository, "deploy/nginx/shenzhouhr.conf")
    nginx_http = _file_text(repository, "deploy/nginx/shenzhouhr-http.conf")
    service = _file_text(repository, "deploy/systemd/shenzhouhr.service")
    checks = [
        PreflightCheck(
            "DEPLOY-NGINX-CONTEXT",
            "PASS"
            if all(
                token in nginx_http
                for token in ("log_format shenzhouhr_safe", "limit_req_zone", "$uri")
            )
            and "$request_uri" not in nginx_http
            else "FAIL",
            "http-context logging and rate-limit zones",
        ),
        PreflightCheck(
            "DEPLOY-NGINX-SERVER",
            "PASS"
            if all(
                token in nginx
                for token in (
                    "listen 443 ssl http2;",
                    "server_tokens off;",
                    "client_max_body_size 21m;",
                    "limit_req zone=shenzhouhr_api",
                    'set $shenzhouhr_cache_control "no-store, max-age=0";',
                    'set $shenzhouhr_cache_control "public, max-age=31536000, immutable";',
                    "sub_filter __CSP_NONCE__ $request_id;",
                )
            )
            else "FAIL",
            "TLS, no-store, abuse limits, asset cache and CSP nonce",
        ),
        PreflightCheck(
            "DEPLOY-SYSTEMD-SANDBOX",
            "PASS"
            if all(
                token in service
                for token in (
                    "User=shenzhouhr",
                    "StateDirectory=shenzhouhr",
                    "ProtectSystem=strict",
                    "NoNewPrivileges=true",
                    "RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6",
                    "SystemCallFilter=@system-service",
                    "CapabilityBoundingSet=",
                )
            )
            and "User=root" not in service
            else "FAIL",
            "non-root systemd sandbox and bounded resources",
        ),
        PreflightCheck(
            "DEPLOY-NO-AUTOMATIC-CUTOVER",
            "PASS"
            if not any(
                token in nginx + service
                for token in (
                    "SPRING_PROFILES_ACTIVE=dev",
                    "X-Development-Principal",
                    "flyway clean",
                    "DROP DATABASE",
                )
            )
            else "FAIL",
            "templates contain no development bypass or destructive migration",
        ),
    ]
    return checks


def inspect_environment_file(path: Path) -> PreflightCheck:
    if not path.is_absolute() or path.is_symlink() or not path.is_file():
        return PreflightCheck(
            "DEPLOY-ENV-FILE", "FAIL", "environment file must be an absolute regular file"
        )
    mode = stat.S_IMODE(path.stat().st_mode)
    if path.stat().st_uid != os.getuid() or mode != 0o600:
        return PreflightCheck(
            "DEPLOY-ENV-FILE", "FAIL", "environment file must be current-user 0600"
        )
    names: set[str] = set()
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            return PreflightCheck(
                "DEPLOY-ENV-FILE", "FAIL", "environment file has an invalid line"
            )
        name, _ = line.split("=", 1)
        names.add(name)
    missing = REQUIRED_ENVIRONMENT_KEYS - names
    if missing:
        return PreflightCheck(
            "DEPLOY-ENV-FILE", "FAIL", f"missing variable names: {sorted(missing)}"
        )
    return PreflightCheck(
        "DEPLOY-ENV-FILE", "PASS", "required variable names present; values not emitted"
    )


def verify_artifact_manifest(root: Path, manifest_path: Path) -> list[PreflightCheck]:
    manifest = read_json(manifest_path)
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise ContractError("artifact manifest must contain an artifacts array")
    checks: list[PreflightCheck] = []
    resolved_root = root.resolve()
    for item in artifacts:
        if not isinstance(item, dict) or set(("path", "sha256")) - item.keys():
            raise ContractError("artifact entry requires path and sha256")
        relative = Path(str(item["path"]))
        if relative.is_absolute() or ".." in relative.parts:
            raise ContractError(f"unsafe artifact path: {relative}")
        candidate = (root / relative).resolve()
        try:
            candidate.relative_to(resolved_root)
        except ValueError as exc:
            raise ContractError(f"artifact escapes root: {relative}") from exc
        actual = sha256_file(candidate) if candidate.is_file() else "MISSING"
        checks.append(
            PreflightCheck(
                f"ARTIFACT-{relative.as_posix()}",
                "PASS" if actual == item["sha256"] else "FAIL",
                "SHA-256 matches" if actual == item["sha256"] else "missing or SHA mismatch",
            )
        )
    return checks


def build_plan(
    repository: Path,
    *,
    environment_file: Path | None = None,
    artifact_root: Path | None = None,
    artifact_manifest: Path | None = None,
) -> dict[str, Any]:
    checks = validate_deployment_templates(repository)
    if environment_file:
        checks.append(inspect_environment_file(environment_file))
    if artifact_root or artifact_manifest:
        if not artifact_root or not artifact_manifest:
            raise ContractError("artifact verification requires root and manifest")
        checks.extend(verify_artifact_manifest(artifact_root, artifact_manifest))
    static_status = "PASS" if all(check.status == "PASS" for check in checks) else "FAIL"
    return {
        "schema_version": "shenzhouhr.w9.native-preflight/v1",
        "mode": "READ_ONLY_PLAN",
        "system_changes": 0,
        "network_requests": 0,
        "static_status": static_status,
        "release_gate_status": "NOT_VERIFIED",
        "checks": [asdict(check) for check in checks],
        "native_commands_pending": [
            "nginx -t",
            "systemd-analyze verify /etc/systemd/system/shenzhouhr.service",
            "flyway validate",
            "flyway migrate",
            "flyway validate",
            "loopback actuator health check",
            "cutover and rollback rehearsal",
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only W9 native deployment preflight")
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--environment-file", type=Path)
    parser.add_argument("--artifact-root", type=Path)
    parser.add_argument("--artifact-manifest", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        result = build_plan(
            args.repo.resolve(),
            environment_file=args.environment_file,
            artifact_root=args.artifact_root,
            artifact_manifest=args.artifact_manifest,
        )
    except (ContractError, OSError) as exc:
        print(f"W9_NATIVE_PREFLIGHT_INVALID={exc}", file=sys.stderr)
        return 2
    rendered = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    print(f"W9_NATIVE_STATIC={result['static_status']}", file=sys.stderr)
    print("W9_NATIVE_RELEASE=NOT_VERIFIED", file=sys.stderr)
    return 0 if result["static_status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
