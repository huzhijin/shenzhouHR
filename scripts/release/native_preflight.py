#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import re
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
    "SHENZHOUHR_SERVER_ADDRESS",
    "SHENZHOUHR_SESSION_COOKIE_SECURE",
    "SHENZHOUHR_DB_URL",
    "SHENZHOUHR_DB_USERNAME",
    "SHENZHOUHR_DB_PASSWORD",
    "SHENZHOUHR_FLYWAY_ENABLED",
    "SHENZHOUHR_PROVISIONING_PEPPER",
    "SHENZHOUHR_PROVISIONING_KEY_ID",
    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW",
}
ALLOWED_PRODUCTION_ENVIRONMENT_KEYS = frozenset(
    REQUIRED_ENVIRONMENT_KEYS
    | {
        "SHENZHOUHR_SERVER_PORT",
        "SHENZHOUHR_DB_POOL_SIZE",
        "SHENZHOUHR_DB_MIN_IDLE",
        "SHENZHOUHR_LOGIN_MAX_FAILURES",
        "SHENZHOUHR_LOGIN_FAILURE_WINDOW",
        "SHENZHOUHR_LOGIN_LOCK_DURATION",
        "SHENZHOUHR_SESSION_IDLE_TIMEOUT",
        "SHENZHOUHR_SESSION_ABSOLUTE_TIMEOUT",
        "SHENZHOUHR_PAYROLL_RESERVATION_ENABLED",
        "SHENZHOUHR_BOOTSTRAP_ENABLED",
        "SHENZHOUHR_DEV_PRINCIPAL_ENABLED",
        "DELI_EPLUS_ENABLED",
        "DELI_EPLUS_BASE_URL",
        "DELI_EPLUS_APP_KEY",
        "DELI_EPLUS_APP_SECRET",
        "DELI_EPLUS_PAGE_SIZE",
        "DELI_EPLUS_CONNECT_TIMEOUT",
        "DELI_EPLUS_REQUEST_TIMEOUT",
        "DELI_EPLUS_MAX_RESPONSE_BYTES",
        "DELI_EPLUS_SOURCE_TIME_ZONE",
        "DELI_EPLUS_CREDENTIAL_REFERENCE_NAME",
        "OA_MYSQL_ENABLED",
        "OA_MYSQL_JDBC_URL",
        "OA_MYSQL_USERNAME",
        "OA_MYSQL_PASSWORD",
        "OA_MYSQL_MAX_POOL_SIZE",
        "OA_MYSQL_CONNECTION_TIMEOUT",
        "OA_MYSQL_QUERY_TIMEOUT",
    }
)
REQUIRED_ENVIRONMENT_VALUES = {
    "SPRING_PROFILES_ACTIVE": "prod",
    "SHENZHOUHR_SERVER_ADDRESS": "127.0.0.1",
    "SHENZHOUHR_SESSION_COOKIE_SECURE": "true",
    "SHENZHOUHR_FLYWAY_ENABLED": "false",
    "SHENZHOUHR_BOOTSTRAP_ENABLED": "false",
    "SHENZHOUHR_DEV_PRINCIPAL_ENABLED": "false",
}
BOOLEAN_ENVIRONMENT_KEYS = frozenset(
    {
        "SHENZHOUHR_PAYROLL_RESERVATION_ENABLED",
        "DELI_EPLUS_ENABLED",
        "OA_MYSQL_ENABLED",
    }
)
POSITIVE_INTEGER_ENVIRONMENT_KEYS = frozenset(
    {
        "SHENZHOUHR_SERVER_PORT",
        "SHENZHOUHR_DB_POOL_SIZE",
        "SHENZHOUHR_DB_MIN_IDLE",
        "SHENZHOUHR_LOGIN_MAX_FAILURES",
        "DELI_EPLUS_PAGE_SIZE",
        "DELI_EPLUS_MAX_RESPONSE_BYTES",
        "OA_MYSQL_MAX_POOL_SIZE",
    }
)
ENVIRONMENT_NAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
PROVISIONING_PEPPER_PATTERN = re.compile(r"^[A-Za-z0-9_-]{43}$")
PROVISIONING_KEY_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")
PROVISIONING_RECOVERY_WINDOW_PATTERN = re.compile(
    r"^P(?:(?P<days>[0-9]+)D)?"
    r"(?:T(?:(?P<hours>[0-9]+)H)?(?:(?P<minutes>[0-9]+)M)?"
    r"(?:(?P<seconds>[0-9]+)S)?)?$"
)


@dataclass(frozen=True)
class PreflightCheck:
    check_id: str
    status: str
    detail: str


def _valid_provisioning_pepper(value: str) -> bool:
    if not PROVISIONING_PEPPER_PATTERN.fullmatch(value):
        return False
    try:
        decoded = base64.b64decode(
            value.replace("-", "+").replace("_", "/") + "=",
            altchars=None,
            validate=True,
        )
    except (binascii.Error, ValueError):
        return False
    canonical = base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=")
    return len(decoded) == 32 and canonical == value


def _valid_provisioning_recovery_window(value: str) -> bool:
    match = PROVISIONING_RECOVERY_WINDOW_PATTERN.fullmatch(value)
    if match is None:
        return False
    components = {
        name: int(component) if component is not None else 0
        for name, component in match.groupdict().items()
    }
    if not any(components.values()):
        return False
    if value.endswith("T"):
        return False
    seconds = (
        components["days"] * 86_400
        + components["hours"] * 3_600
        + components["minutes"] * 60
        + components["seconds"]
    )
    return 0 < seconds <= 86_400


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
    file_stat = path.stat()
    mode = stat.S_IMODE(file_stat.st_mode)
    if file_stat.st_uid != os.getuid() or mode != 0o600:
        return PreflightCheck(
            "DEPLOY-ENV-FILE", "FAIL", "environment file must be current-user 0600"
        )
    try:
        raw_lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        return PreflightCheck(
            "DEPLOY-ENV-FILE", "FAIL", "environment file cannot be read as UTF-8"
        )
    values: dict[str, str] = {}
    for raw_line in raw_lines:
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if raw_line != line or line.count("=") < 1:
            return PreflightCheck(
                "DEPLOY-ENV-FILE",
                "FAIL",
                "environment file contains unsupported assignment syntax",
            )
        name, value = line.split("=", 1)
        if not ENVIRONMENT_NAME_PATTERN.fullmatch(name):
            return PreflightCheck(
                "DEPLOY-ENV-FILE",
                "FAIL",
                "environment file contains an invalid variable name",
            )
        if name in values:
            return PreflightCheck(
                "DEPLOY-ENV-FILE",
                "FAIL",
                f"environment file contains duplicate variable name: {name}",
            )
        if (
            value != value.strip()
            or any(character in value for character in ("'", '"', "\\"))
            or any(ord(character) < 0x20 or ord(character) == 0x7F for character in value)
        ):
            return PreflightCheck(
                "DEPLOY-ENV-FILE",
                "FAIL",
                f"environment variable uses unsupported value syntax: {name}",
            )
        values[name] = value
    names = set(values)
    unknown = names - ALLOWED_PRODUCTION_ENVIRONMENT_KEYS
    if unknown:
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            f"environment file contains unknown variable names: {sorted(unknown)}",
        )
    missing = REQUIRED_ENVIRONMENT_KEYS - names
    if missing:
        return PreflightCheck(
            "DEPLOY-ENV-FILE", "FAIL", f"missing variable names: {sorted(missing)}"
        )
    empty = sorted(name for name, value in values.items() if not value)
    if empty:
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            f"configured variable values must be non-empty: {empty}",
        )
    invalid_fixed_values = sorted(
        name
        for name, expected in REQUIRED_ENVIRONMENT_VALUES.items()
        if name in values and values[name] != expected
    )
    if invalid_fixed_values:
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            "production safety variables have invalid values: "
            f"{invalid_fixed_values}",
        )
    if not _valid_provisioning_pepper(values["SHENZHOUHR_PROVISIONING_PEPPER"]):
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            "SHENZHOUHR_PROVISIONING_PEPPER must canonically encode exactly "
            "32 bytes as 43-character unpadded base64url",
        )
    if not PROVISIONING_KEY_ID_PATTERN.fullmatch(
        values["SHENZHOUHR_PROVISIONING_KEY_ID"]
    ):
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            "SHENZHOUHR_PROVISIONING_KEY_ID must use 1-64 safe identifier characters",
        )
    if not _valid_provisioning_recovery_window(
        values["SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW"]
    ):
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW must be greater than zero "
            "and no more than 24 hours",
        )
    invalid_booleans = sorted(
        name
        for name in BOOLEAN_ENVIRONMENT_KEYS & names
        if values[name] not in {"true", "false"}
    )
    if invalid_booleans:
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            f"boolean variables have invalid values: {invalid_booleans}",
        )
    invalid_positive_integers = sorted(
        name
        for name in POSITIVE_INTEGER_ENVIRONMENT_KEYS & names
        if not values[name].isdigit() or int(values[name]) <= 0
    )
    if invalid_positive_integers:
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            "positive integer variables have invalid values: "
            f"{invalid_positive_integers}",
        )
    if "SHENZHOUHR_SERVER_PORT" in values and int(
        values["SHENZHOUHR_SERVER_PORT"]
    ) > 65535:
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            "server port variable has an invalid value",
        )
    dependent_requirements = {
        "DELI_EPLUS_ENABLED": {
            "DELI_EPLUS_APP_KEY",
            "DELI_EPLUS_APP_SECRET",
        },
        "OA_MYSQL_ENABLED": {
            "OA_MYSQL_JDBC_URL",
            "OA_MYSQL_USERNAME",
            "OA_MYSQL_PASSWORD",
        },
    }
    for switch, required_names in dependent_requirements.items():
        if values.get(switch) == "true":
            missing_dependencies = sorted(required_names - names)
            if missing_dependencies:
                return PreflightCheck(
                    "DEPLOY-ENV-FILE",
                    "FAIL",
                    f"{switch} requires variable names: {missing_dependencies}",
                )
    if (
        values.get("DELI_EPLUS_ENABLED") == "true"
        and "DELI_EPLUS_BASE_URL" in values
        and not values["DELI_EPLUS_BASE_URL"].startswith("https://")
    ):
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            "DELI_EPLUS_BASE_URL must use HTTPS when the integration is enabled",
        )
    if (
        values.get("OA_MYSQL_ENABLED") == "true"
        and not values["OA_MYSQL_JDBC_URL"].startswith("jdbc:mysql://")
    ):
        return PreflightCheck(
            "DEPLOY-ENV-FILE",
            "FAIL",
            "OA_MYSQL_JDBC_URL must use the MySQL JDBC scheme",
        )
    return PreflightCheck(
        "DEPLOY-ENV-FILE",
        "PASS",
        "required unique variables and production safety values verified; "
        "values not emitted",
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
