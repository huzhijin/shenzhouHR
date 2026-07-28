#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from scripts.release.common import (  # noqa: E402
    ContractError,
    read_json,
    safe_relative_file,
    sha256_file,
    validate_commit,
    validate_run_id,
    validate_status,
    write_json,
)

SCHEMA_VERSION = "shenzhouhr.w9.release-evidence/v1"
ZERO_COMMIT = "0" * 40
REQUIRED_RELEASE_GATES = (
    "w7_final",
    "threat_dynamic",
    "performance_50_concurrency",
    "capacity_36_month",
    "mysql_8_4_lts",
    "browser_matrix",
    "recovery_drill",
    "native_deployment",
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
TRUSTED_GIT_EXECUTABLE = Path("/usr/bin/git")
EXPECTED_THREAT_CASE_IDS = frozenset(
    {
        "THREAT-BOLA-EMPLOYEE",
        "THREAT-SCOPE-ORGANIZATION",
        "THREAT-ENUMERATION",
        "THREAT-BULK-EXPORT",
        "THREAT-CACHE-REVOCATION",
        "THREAT-LOCATION",
        "THREAT-DEV-ENTRY",
        "THREAT-LOG-LEAK",
        "THREAT-SECURITY-HEADERS",
        "THREAT-PAYROLL-ZERO",
    }
)
FORMAL_PERFORMANCE_SCHEMA_VERSION = (
    "shenzhouhr.w9.formal-performance-evidence/v1"
)
FORMAL_NATIVE_SCHEMA_VERSION = (
    "shenzhouhr.w9.formal-native-deployment-evidence/v1"
)
FORMAL_PERFORMANCE_PRODUCER = {
    "id": "shenzhouhr-w9-integrated-performance-producer",
    "version": "1",
    "mode": "FORMAL_EXTERNAL",
}
FORMAL_PERFORMANCE_SOURCE = {
    "schema_version": "shenzhouhr.w9.performance-scenarios/v1",
    "path": "scripts/release/contracts/performance-scenarios-v1.json",
    "sha256": "34cb5106390506c5ae9d917f4714fdb08d1e6a1f247261eee4d81819b74b9a7c",
}
EXPECTED_PERFORMANCE_SCENARIOS = {
    "common-list": {
        "method": "GET",
        "path": (
            "/api/v1/attendance-reports"
            "?reportType=ATTENDANCE_DETAIL&page=0&size=50"
        ),
        "concurrency": 50,
        "total_requests": 1000,
        "minimum_success_rate": 0.99,
        "p95_limit_ms": 2000,
    },
    "personal-department-dashboard": {
        "method": "GET",
        "path": (
            "/api/v1/attendance-reports"
            "?reportType=ATTENDANCE_RATE&page=0&size=50"
        ),
        "concurrency": 50,
        "total_requests": 1000,
        "minimum_success_rate": 0.99,
        "p95_limit_ms": 3000,
    },
    "single-detail": {
        "method": "GET",
        "path": (
            "/api/v1/attendance-reports"
            "?reportType=ATTENDANCE_DETAIL&page=0&size=1"
        ),
        "concurrency": 50,
        "total_requests": 1000,
        "minimum_success_rate": 0.99,
        "p95_limit_ms": 1000,
    },
    "export-50000": {
        "method": "POST",
        "path": "/api/v1/attendance-reports/exports",
        "concurrency": 1,
        "total_requests": 1,
        "minimum_success_rate": 1.0,
        "p95_limit_ms": 60000,
    },
}
FORMAL_NATIVE_PRODUCER = {
    "id": "shenzhouhr-w9-ubuntu-native-deployment-producer",
    "version": "1",
    "mode": "FORMAL_EXTERNAL",
}
FORMAL_NATIVE_SOURCE = {
    "templates": [
        {
            "path": "deploy/nginx/shenzhouhr.conf",
            "sha256": "b7898d462b7e3b3c82d4691f25f2d822cdd5c033a917cc57e13105953137a2d7",
        },
        {
            "path": "deploy/nginx/shenzhouhr-http.conf",
            "sha256": "c06dc200a8c60231c74d96ecb1aae20f92fb8388c8f360636793033385c82f7e",
        },
        {
            "path": "deploy/systemd/shenzhouhr.service",
            "sha256": "2597a187b1041f306209fb7d457df22b8e5bde8cfea568000d2406704bf618b6",
        },
    ]
}
EXPECTED_NATIVE_CHECK_IDS = frozenset(
    {
        "NATIVE-ARTIFACT-SHA256",
        "NATIVE-ARTIFACT-PERMISSIONS",
        "NATIVE-ENVIRONMENT-FILE",
        "NATIVE-NGINX-TEST",
        "NATIVE-SYSTEMD-VERIFY",
        "NATIVE-FLYWAY-PRE-VALIDATE",
        "NATIVE-FLYWAY-MIGRATE",
        "NATIVE-FLYWAY-POST-VALIDATE",
        "NATIVE-LOOPBACK-HEALTH",
        "NATIVE-CUTOVER",
        "NATIVE-ROLLBACK",
        "NATIVE-POST-ROLLBACK-HEALTH",
    }
)


@dataclass(frozen=True)
class GateEvidenceContract:
    schema_version: str
    status_path: tuple[str, ...]


GATE_EVIDENCE_CONTRACTS = {
    "w7_final": GateEvidenceContract(
        "shenzhouhr.w7.final-evidence/v1", ("status",)
    ),
    "threat_dynamic": GateEvidenceContract(
        "shenzhouhr.w9.threat-evidence/v1", ("dynamic_status",)
    ),
    "performance_50_concurrency": GateEvidenceContract(
        FORMAL_PERFORMANCE_SCHEMA_VERSION,
        ("release_gate_status",),
    ),
    "capacity_36_month": GateEvidenceContract(
        FORMAL_PERFORMANCE_SCHEMA_VERSION, ("capacity_gate_status",)
    ),
    "mysql_8_4_lts": GateEvidenceContract(
        "shenzhouhr.w9.mysql-8.4-evidence/v1", ("status",)
    ),
    "browser_matrix": GateEvidenceContract(
        "shenzhouhr.w9.browser-matrix/v2", ("verdict",)
    ),
    "recovery_drill": GateEvidenceContract(
        "shenzhouhr.w9.mysql-restore/v2", ("recovery_gate_status",)
    ),
    "native_deployment": GateEvidenceContract(
        FORMAL_NATIVE_SCHEMA_VERSION, ("status",)
    ),
}


def aggregate_status(statuses: Iterable[str]) -> str:
    normalized = [validate_status(status) for status in statuses]
    if any(status == "FAIL" for status in normalized):
        return "FAIL"
    if not normalized or any(status == "NOT_VERIFIED" for status in normalized):
        return "NOT_VERIFIED"
    return "PASS"


def _finite_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError(f"{label} must be a finite number")
    normalized = float(value)
    if not math.isfinite(normalized):
        raise ContractError(f"{label} must be a finite number")
    return normalized


def _require_fields(
    evidence: dict[str, Any],
    fields: set[str],
    *,
    gate_name: str,
) -> None:
    missing = fields - evidence.keys()
    if missing:
        raise ContractError(
            f"{gate_name}: PASS evidence is missing fields: {sorted(missing)}"
        )


def _require_exact_fields(
    value: dict[str, Any],
    fields: set[str],
    *,
    label: str,
) -> None:
    actual = set(value)
    if actual != fields:
        raise ContractError(
            f"{label} fields differ from the exact contract: "
            f"missing={sorted(fields - actual)} "
            f"unexpected={sorted(actual - fields)}"
        )


def _validate_utc_interval(
    provenance: dict[str, Any],
    *,
    gate_name: str,
) -> None:
    parsed: list[datetime] = []
    for field in ("started_at_utc", "completed_at_utc"):
        value = provenance.get(field)
        if not isinstance(value, str) or not value.endswith("Z"):
            raise ContractError(
                f"{gate_name}: {field} must be an RFC3339 UTC timestamp"
            )
        try:
            timestamp = datetime.fromisoformat(value[:-1] + "+00:00")
        except ValueError as exc:
            raise ContractError(
                f"{gate_name}: {field} must be an RFC3339 UTC timestamp"
            ) from exc
        if timestamp.tzinfo != timezone.utc:
            raise ContractError(f"{gate_name}: {field} must use UTC")
        parsed.append(timestamp)
    if parsed[1] < parsed[0]:
        raise ContractError(f"{gate_name}: evidence timestamps are reversed")


def _validate_raw_artifact(
    provenance: dict[str, Any],
    *,
    run_root: Path,
    gate_name: str,
) -> dict[str, Any]:
    raw_path = provenance.get("raw_artifact_path")
    raw_sha256 = provenance.get("raw_artifact_sha256")
    if not isinstance(raw_sha256, str) or not SHA256_PATTERN.fullmatch(raw_sha256):
        raise ContractError(f"{gate_name}: raw artifact SHA-256 is invalid")
    artifact = safe_relative_file(run_root, raw_path)
    if sha256_file(artifact) != raw_sha256:
        raise ContractError(f"{gate_name}: raw artifact SHA-256 mismatch")
    raw = read_json(artifact)
    if not isinstance(raw, dict):
        raise ContractError(f"{gate_name}: raw artifact must be a JSON object")
    return raw


def _validate_formal_performance_provenance(
    evidence: dict[str, Any],
    *,
    manifest: dict[str, Any],
    run_root: Path,
) -> dict[str, Any]:
    if evidence.get("producer") != FORMAL_PERFORMANCE_PRODUCER:
        raise ContractError(
            "performance evidence producer is not the approved formal producer"
        )
    if evidence.get("source") != FORMAL_PERFORMANCE_SOURCE:
        raise ContractError(
            "performance evidence source contract provenance is invalid"
        )
    provenance = evidence.get("provenance")
    if not isinstance(provenance, dict):
        raise ContractError("performance evidence provenance must be an object")
    _require_exact_fields(
        provenance,
        {
            "source_mode",
            "integrated_commit",
            "environment_id",
            "mysql_version",
            "dataset_sha256",
            "started_at_utc",
            "completed_at_utc",
            "raw_artifact_path",
            "raw_artifact_sha256",
            "credential_values_logged",
        },
        label="performance provenance",
    )
    if (
        provenance["source_mode"] != "REAL_INTEGRATED"
        or provenance["integrated_commit"] != manifest["commit"]
        or provenance["environment_id"] != manifest["environment_id"]
        or not str(provenance["mysql_version"]).startswith("8.4.")
        or not isinstance(provenance["dataset_sha256"], str)
        or not SHA256_PATTERN.fullmatch(provenance["dataset_sha256"])
        or provenance["credential_values_logged"] is not False
    ):
        raise ContractError("performance evidence provenance is invalid")
    _validate_utc_interval(provenance, gate_name="performance")
    raw = _validate_raw_artifact(
        provenance,
        run_root=run_root,
        gate_name="performance",
    )
    _require_exact_fields(
        raw,
        {
            "schema_version",
            "run_id",
            "commit",
            "environment_id",
            "producer_id",
            "scenario_sample_set_sha256",
        },
        label="performance raw artifact",
    )
    if (
        raw["schema_version"]
        != "shenzhouhr.w9.formal-performance-raw/v1"
        or raw["run_id"] != manifest["run_id"]
        or raw["commit"] != manifest["commit"]
        or raw["environment_id"] != manifest["environment_id"]
        or raw["producer_id"] != FORMAL_PERFORMANCE_PRODUCER["id"]
    ):
        raise ContractError(
            "performance raw artifact provenance is invalid"
        )
    return raw


def _validate_formal_performance_scenarios(
    evidence: dict[str, Any],
    *,
    raw_artifact: dict[str, Any],
) -> None:
    scenarios = evidence.get("scenarios")
    if not isinstance(scenarios, list):
        raise ContractError("performance scenarios must be an array")
    by_id: dict[str, dict[str, Any]] = {}
    for scenario in scenarios:
        if not isinstance(scenario, dict) or not isinstance(
            scenario.get("scenario_id"), str
        ):
            raise ContractError("performance scenario result is invalid")
        scenario_id = scenario["scenario_id"]
        if scenario_id in by_id:
            raise ContractError(f"duplicate performance scenario: {scenario_id}")
        by_id[scenario_id] = scenario
    if set(by_id) != set(EXPECTED_PERFORMANCE_SCENARIOS):
        raise ContractError(
            "performance evidence does not contain the exact formal scenario set"
        )

    common_fields = {
        "scenario_id",
        "method",
        "path",
        "concurrency",
        "total_requests",
        "measurement_status",
        "release_gate_status",
        "samples",
        "complete_response_count",
        "status_counts",
        "success_rate",
        "minimum_success_rate",
        "p95_ms",
        "p95_limit_ms",
        "response_sample_set_sha256",
    }
    export_fields = common_fields | {
        "completion_status",
        "requested_row_count",
        "export_row_count",
        "download_content_length_bytes",
        "download_sha256",
        "reauthenticated",
    }
    for scenario_id, expected in EXPECTED_PERFORMANCE_SCENARIOS.items():
        scenario = by_id[scenario_id]
        _require_exact_fields(
            scenario,
            export_fields if scenario_id == "export-50000" else common_fields,
            label=f"performance scenario {scenario_id}",
        )
        for field in (
            "method",
            "path",
            "concurrency",
            "total_requests",
            "minimum_success_rate",
            "p95_limit_ms",
        ):
            if scenario[field] != expected[field]:
                raise ContractError(
                    f"performance scenario {scenario_id} {field} differs "
                    "from the formal contract"
                )
        samples = scenario["samples"]
        complete = scenario["complete_response_count"]
        success_rate = _finite_number(
            scenario["success_rate"],
            f"{scenario_id} success_rate",
        )
        minimum_success_rate = _finite_number(
            scenario["minimum_success_rate"],
            f"{scenario_id} minimum_success_rate",
        )
        p95_ms = _finite_number(scenario["p95_ms"], f"{scenario_id} p95_ms")
        p95_limit_ms = _finite_number(
            scenario["p95_limit_ms"],
            f"{scenario_id} p95_limit_ms",
        )
        status_counts = scenario["status_counts"]
        if (
            scenario["measurement_status"] != "PASS"
            or scenario["release_gate_status"] != "PASS"
            or isinstance(samples, bool)
            or not isinstance(samples, int)
            or samples != expected["total_requests"]
            or isinstance(complete, bool)
            or not isinstance(complete, int)
            or complete != samples
            or not 0 <= success_rate <= 1
            or not 0 < minimum_success_rate <= 1
            or success_rate < minimum_success_rate
            or not 0 < p95_ms <= p95_limit_ms
            or not isinstance(status_counts, dict)
            or any(
                not isinstance(code, str)
                or not code.isdigit()
                or not 200 <= int(code) < 300
                or isinstance(count, bool)
                or not isinstance(count, int)
                or count < 0
                for code, count in status_counts.items()
            )
            or sum(status_counts.values()) != samples
            or not isinstance(scenario["response_sample_set_sha256"], str)
            or not SHA256_PATTERN.fullmatch(
                scenario["response_sample_set_sha256"]
            )
        ):
            raise ContractError(
                f"performance scenario {scenario_id} does not prove PASS"
            )
        if scenario_id == "export-50000" and (
            scenario["completion_status"] != "READY"
            or scenario["requested_row_count"] != 50_000
            or isinstance(scenario["export_row_count"], bool)
            or not isinstance(scenario["export_row_count"], int)
            or scenario["export_row_count"] != 50_000
            or isinstance(scenario["download_content_length_bytes"], bool)
            or not isinstance(scenario["download_content_length_bytes"], int)
            or scenario["download_content_length_bytes"] <= 0
            or not isinstance(scenario["download_sha256"], str)
            or not SHA256_PATTERN.fullmatch(scenario["download_sha256"])
            or scenario["reauthenticated"] is not True
        ):
            raise ContractError(
                "export-50000 does not prove completed authenticated download"
            )
    raw_hashes = raw_artifact["scenario_sample_set_sha256"]
    expected_hashes = {
        scenario_id: result["response_sample_set_sha256"]
        for scenario_id, result in by_id.items()
    }
    if raw_hashes != expected_hashes:
        raise ContractError(
            "performance raw samples do not match the formal scenario summary"
        )


def _validate_specialized_pass_evidence(
    gate_name: str,
    evidence: dict[str, Any],
    *,
    manifest: dict[str, Any],
    run_root: Path,
) -> None:
    if gate_name == "w7_final":
        details = evidence.get("details")
        if not isinstance(details, dict):
            raise ContractError("w7_final: PASS evidence requires details")
        validate_commit(details.get("w7_commit"))
        return

    if gate_name == "threat_dynamic":
        _require_fields(
            evidence,
            {
                "data_policy",
                "static_status",
                "dynamic_status",
                "status",
                "dynamic_cases",
            },
            gate_name=gate_name,
        )
        if (
            evidence["data_policy"] != "SYNTHETIC_ONLY"
            or evidence["static_status"] != "PASS"
            or evidence["dynamic_status"] != "PASS"
            or evidence["status"] != "PASS"
        ):
            raise ContractError("threat_dynamic: PASS safety statuses are inconsistent")
        cases = evidence["dynamic_cases"]
        if not isinstance(cases, list):
            raise ContractError("threat_dynamic: dynamic_cases must be an array")
        case_ids: set[str] = set()
        for case in cases:
            if (
                not isinstance(case, dict)
                or not isinstance(case.get("id"), str)
                or case.get("status") != "PASS"
            ):
                raise ContractError(
                    "threat_dynamic: every required dynamic case must PASS"
                )
            case_ids.add(case["id"])
        if case_ids != EXPECTED_THREAT_CASE_IDS:
            raise ContractError(
                "threat_dynamic: PASS evidence does not cover the exact threat set"
            )
        return

    if gate_name in {"performance_50_concurrency", "capacity_36_month"}:
        _require_fields(
            evidence,
            {
                "producer",
                "source",
                "provenance",
                "release_gate_status",
                "capacity_gate_status",
                "missing_capacity",
                "capacity",
                "scenarios",
            },
            gate_name=gate_name,
        )
        raw_artifact = _validate_formal_performance_provenance(
            evidence,
            manifest=manifest,
            run_root=run_root,
        )
        _validate_formal_performance_scenarios(
            evidence,
            raw_artifact=raw_artifact,
        )
        if gate_name == "performance_50_concurrency":
            if evidence["release_gate_status"] != "PASS":
                raise ContractError(
                    "performance_50_concurrency: formal scenario suite did not PASS"
                )
            return

        capacity = evidence["capacity"]
        if (
            evidence["capacity_gate_status"] != "PASS"
            or evidence["missing_capacity"] != []
            or not isinstance(capacity, dict)
        ):
            raise ContractError("capacity_36_month: PASS capacity gate is inconsistent")
        _require_fields(
            capacity,
            {
                "active_employees",
                "daily_peak_punches",
                "month_end_concurrency",
                "existing_database_bytes",
                "accumulation_months",
                "mysql_version",
                "flyway_version",
                "integrated_commit",
                "dataset_sha256",
            },
            gate_name=gate_name,
        )
        if (
            capacity["accumulation_months"] != 36
            or capacity["integrated_commit"] != manifest["commit"]
            or not str(capacity["mysql_version"]).startswith("8.4.")
            or capacity["mysql_version"]
            != evidence["provenance"]["mysql_version"]
            or not isinstance(capacity["dataset_sha256"], str)
            or not SHA256_PATTERN.fullmatch(capacity["dataset_sha256"])
            or capacity["dataset_sha256"]
            != evidence["provenance"]["dataset_sha256"]
        ):
            raise ContractError(
                "capacity_36_month: PASS capacity provenance is invalid"
            )
        for field in (
            "active_employees",
            "daily_peak_punches",
            "month_end_concurrency",
            "existing_database_bytes",
        ):
            if _finite_number(capacity[field], f"capacity {field}") <= 0:
                raise ContractError(
                    f"capacity_36_month: {field} must be positive"
                )
        return

    if gate_name == "mysql_8_4_lts":
        _require_fields(
            evidence,
            {"status", "mysql_version", "server_uuid", "authority_verified"},
            gate_name=gate_name,
        )
        if (
            evidence["status"] != "PASS"
            or not str(evidence["mysql_version"]).startswith("8.4.")
            or not isinstance(evidence["server_uuid"], str)
            or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                r"[89ab][0-9a-f]{3}-[0-9a-f]{12}",
                evidence["server_uuid"],
            )
            or evidence["authority_verified"] is not True
        ):
            raise ContractError("mysql_8_4_lts: PASS authority evidence is invalid")
        return

    if gate_name == "browser_matrix":
        raise ContractError(
            "browser_matrix: PASS is disabled until a trusted signed "
            "CI/device-farm attestation verifier is implemented"
        )

    if gate_name == "recovery_drill":
        _require_fields(
            evidence,
            {
                "automated_restore_status",
                "recovery_gate_status",
                "admin_used_for_import",
                "verification_account_read_only",
                "drop_executed",
                "pitr_status",
                "business_reconciliation_status",
                "rpo_minutes",
                "rto_minutes",
                "source_server_uuid",
                "restore_server_uuid",
                "source_environment_id",
                "restore_environment_id",
                "mysql_version",
                "target_database",
                "target_retained_for_inspection",
                "residual_state",
                "authority_check_count",
                "authority_verified_before_and_after",
            },
            gate_name=gate_name,
        )
        if (
            evidence["automated_restore_status"] != "PASS"
            or evidence["recovery_gate_status"] != "PASS"
            or evidence["admin_used_for_import"] is not False
            or evidence["verification_account_read_only"] is not True
            or evidence["drop_executed"] is not False
            or evidence["pitr_status"] != "PASS"
            or evidence["business_reconciliation_status"] != "PASS"
            or evidence["target_retained_for_inspection"] is not True
            or evidence["residual_state"] != "COMPLETE_SANDBOX_RETAINED"
            or evidence["authority_verified_before_and_after"] is not True
            or isinstance(evidence["authority_check_count"], bool)
            or not isinstance(evidence["authority_check_count"], int)
            or evidence["authority_check_count"] < 3
            or not str(evidence["mysql_version"]).startswith("8.4.")
            or not isinstance(evidence["target_database"], str)
            or not evidence["target_database"].endswith("_restore")
            or not isinstance(evidence["source_environment_id"], str)
            or not evidence["source_environment_id"].startswith("nonprod:")
            or not isinstance(evidence["restore_environment_id"], str)
            or not evidence["restore_environment_id"].startswith("nonprod:")
            or not all(
                isinstance(evidence[field], str)
                and re.fullmatch(
                    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}",
                    evidence[field],
                )
                for field in ("source_server_uuid", "restore_server_uuid")
            )
            or not 0 <= _finite_number(
                evidence["rpo_minutes"], "recovery rpo_minutes"
            )
            <= 15
            or not 0 < _finite_number(
                evidence["rto_minutes"], "recovery rto_minutes"
            )
            <= 240
        ):
            raise ContractError("recovery_drill: PASS recovery evidence is invalid")
        return

    if gate_name == "native_deployment":
        _require_fields(
            evidence,
            {
                "status",
                "ubuntu_version",
                "rollback_status",
                "producer",
                "source",
                "provenance",
                "checks",
            },
            gate_name=gate_name,
        )
        if evidence["producer"] != FORMAL_NATIVE_PRODUCER:
            raise ContractError(
                "native_deployment: unapproved formal evidence producer"
            )
        if evidence["source"] != FORMAL_NATIVE_SOURCE:
            raise ContractError(
                "native_deployment: deployment template provenance is invalid"
            )
        provenance = evidence["provenance"]
        if not isinstance(provenance, dict):
            raise ContractError(
                "native_deployment: provenance must be an object"
            )
        _require_exact_fields(
            provenance,
            {
                "source_mode",
                "integrated_commit",
                "environment_id",
                "architecture",
                "candidate_artifact_sha256",
                "started_at_utc",
                "completed_at_utc",
                "raw_artifact_path",
                "raw_artifact_sha256",
                "credential_values_logged",
            },
            label="native deployment provenance",
        )
        if (
            provenance["source_mode"] != "REAL_UBUNTU_24_04"
            or provenance["integrated_commit"] != manifest["commit"]
            or provenance["environment_id"] != manifest["environment_id"]
            or provenance["architecture"] not in {"x86_64", "aarch64"}
            or not isinstance(provenance["candidate_artifact_sha256"], str)
            or not SHA256_PATTERN.fullmatch(
                provenance["candidate_artifact_sha256"]
            )
            or provenance["credential_values_logged"] is not False
        ):
            raise ContractError(
                "native_deployment: formal provenance is invalid"
            )
        _validate_utc_interval(provenance, gate_name="native_deployment")
        raw_artifact = _validate_raw_artifact(
            provenance,
            run_root=run_root,
            gate_name="native_deployment",
        )
        checks = evidence["checks"]
        check_ids: set[str] = set()
        if isinstance(checks, list):
            for check in checks:
                if not isinstance(check, dict):
                    raise ContractError(
                        "native_deployment: every check must be an object"
                    )
                _require_exact_fields(
                    check,
                    {"id", "status", "evidence_sha256"},
                    label="native deployment check",
                )
                check_id = check["id"]
                if (
                    not isinstance(check_id, str)
                    or check_id in check_ids
                    or check["status"] != "PASS"
                    or not isinstance(check["evidence_sha256"], str)
                    or not SHA256_PATTERN.fullmatch(check["evidence_sha256"])
                ):
                    raise ContractError(
                        "native_deployment: check result is invalid"
                    )
                check_ids.add(check_id)
        _require_exact_fields(
            raw_artifact,
            {
                "schema_version",
                "run_id",
                "commit",
                "environment_id",
                "producer_id",
                "check_evidence_sha256",
            },
            label="native deployment raw artifact",
        )
        expected_check_hashes = {
            check["id"]: check["evidence_sha256"]
            for check in checks
        } if isinstance(checks, list) else {}
        if (
            raw_artifact["schema_version"]
            != "shenzhouhr.w9.formal-native-deployment-raw/v1"
            or raw_artifact["run_id"] != manifest["run_id"]
            or raw_artifact["commit"] != manifest["commit"]
            or raw_artifact["environment_id"] != manifest["environment_id"]
            or raw_artifact["producer_id"] != FORMAL_NATIVE_PRODUCER["id"]
            or raw_artifact["check_evidence_sha256"]
            != expected_check_hashes
        ):
            raise ContractError(
                "native_deployment: raw check evidence does not match summary"
            )
        if (
            evidence["status"] != "PASS"
            or evidence["ubuntu_version"] != "24.04"
            or evidence["rollback_status"] != "PASS"
            or not isinstance(checks, list)
            or check_ids != EXPECTED_NATIVE_CHECK_IDS
        ):
            raise ContractError(
                "native_deployment: PASS deployment/rollback evidence is invalid"
            )
        return

    raise ContractError(f"no specialized PASS validator for gate {gate_name}")


def not_verified_gate(
    gate: str,
    *,
    run_id: str,
    commit: str,
    environment_id: str,
    reason: str,
) -> dict[str, Any]:
    return {
        "gate": gate,
        "status": "NOT_VERIFIED",
        "run_id": run_id,
        "commit": commit,
        "environment_id": environment_id,
        "evidence_path": None,
        "evidence_sha256": None,
        "reason": reason,
        "details": {},
    }


def new_not_verified_manifest(
    *, run_id: str, commit: str, dirty: bool, environment_id: str
) -> dict[str, Any]:
    validate_run_id(run_id)
    validate_commit(commit)
    reasons = {
        "w7_final": "W7 FINAL has not been synchronized into an integrated commit",
        "threat_dynamic": "authorized dynamic threat matrix has not run",
        "performance_50_concurrency": "50-concurrency integrated performance run has not run",
        "capacity_36_month": "production baseline and 36-month POC are unavailable",
        "mysql_8_4_lts": "approved MySQL 8.4 LTS environment has not been verified",
        "browser_matrix": "real browser and device matrix has not run",
        "recovery_drill": "human MySQL 8.4 PITR/RPO/RTO drill has not run",
        "native_deployment": "Ubuntu 24.04 candidate preflight and rollback have not run",
    }
    gates = [
        not_verified_gate(
            gate,
            run_id=run_id,
            commit=commit,
            environment_id=environment_id,
            reason=reasons[gate],
        )
        for gate in REQUIRED_RELEASE_GATES
    ]
    return {
        "schema_version": SCHEMA_VERSION,
        "run_id": run_id,
        "commit": commit,
        "dirty": dirty,
        "environment_id": environment_id,
        "gates": gates,
        "verdict": "NOT_VERIFIED",
        "explicit_non_dependencies": ["w8"],
    }


def _validate_gate(
    gate: dict[str, Any],
    *,
    manifest: dict[str, Any],
    run_root: Path | None,
) -> dict[str, Any] | None:
    required = {
        "gate",
        "status",
        "run_id",
        "commit",
        "environment_id",
        "evidence_path",
        "evidence_sha256",
        "reason",
    }
    missing = required - gate.keys()
    if missing:
        raise ContractError(f"gate is missing fields: {sorted(missing)}")
    if not isinstance(gate["gate"], str) or not gate["gate"]:
        raise ContractError("gate name must be a non-empty string")
    if gate["gate"] not in GATE_EVIDENCE_CONTRACTS:
        raise ContractError(f"unexpected release gate: {gate['gate']}")
    status = validate_status(gate["status"])
    if gate["run_id"] != manifest["run_id"]:
        raise ContractError(f"{gate['gate']}: run_id differs from manifest")
    if gate["commit"] != manifest["commit"]:
        raise ContractError(f"{gate['gate']}: commit differs from manifest")
    if gate["environment_id"] != manifest["environment_id"]:
        raise ContractError(f"{gate['gate']}: environment_id differs from manifest")
    if not isinstance(gate["reason"], str) or not gate["reason"].strip():
        raise ContractError(f"{gate['gate']}: reason must explain the result")

    evidence_path = gate["evidence_path"]
    evidence_sha256 = gate["evidence_sha256"]
    if status == "NOT_VERIFIED":
        if evidence_path is not None or evidence_sha256 is not None:
            raise ContractError(
                f"{gate['gate']}: NOT_VERIFIED evidence path/hash must be null"
            )
        return None

    if run_root is None:
        raise ContractError(
            f"{gate['gate']}: run root is required to validate PASS/FAIL evidence"
        )
    evidence_file = safe_relative_file(run_root, evidence_path)
    actual_sha = sha256_file(evidence_file)
    if actual_sha != evidence_sha256:
        raise ContractError(f"{gate['gate']}: evidence SHA-256 mismatch")
    evidence = read_json(evidence_file)
    contract = GATE_EVIDENCE_CONTRACTS[gate["gate"]]
    if evidence.get("schema_version") != contract.schema_version:
        raise ContractError(f"{gate['gate']}: unsupported evidence schema")
    if evidence.get("run_id") != manifest["run_id"]:
        raise ContractError(f"{gate['gate']}: evidence run_id differs from manifest")
    if evidence.get("commit") != manifest["commit"]:
        raise ContractError(f"{gate['gate']}: evidence commit differs from manifest")
    if evidence.get("environment_id") != manifest["environment_id"]:
        raise ContractError(
            f"{gate['gate']}: evidence environment_id differs from manifest"
        )
    evidence_status: Any = evidence
    for field in contract.status_path:
        if not isinstance(evidence_status, dict) or field not in evidence_status:
            raise ContractError(
                f"{gate['gate']}: evidence is missing status field "
                f"{'.'.join(contract.status_path)}"
            )
        evidence_status = evidence_status[field]
    if validate_status(evidence_status) != status:
        raise ContractError(f"{gate['gate']}: evidence status differs from gate")
    if status == "PASS":
        _validate_specialized_pass_evidence(
            gate["gate"],
            evidence,
            manifest=manifest,
            run_root=run_root,
        )
    return evidence


def _verify_w7_ancestor(
    manifest: dict[str, Any], w7_evidence: dict[str, Any], repo: Path
) -> None:
    details = w7_evidence.get("details")
    if not isinstance(details, dict):
        raise ContractError("w7_final PASS evidence requires details")
    w7_commit = validate_commit(details.get("w7_commit"))
    process = _run_trusted_git(
        repo,
        "merge-base",
        "--is-ancestor",
        w7_commit,
        manifest["commit"],
    )
    if process.returncode != 0:
        raise ContractError("W7 FINAL commit is not an ancestor of integrated commit")


def _trusted_git_environment() -> dict[str, str]:
    environment = {
        name: value
        for name, value in os.environ.items()
        if not name.startswith("GIT_")
    }
    environment.update(
        {
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_NO_REPLACE_OBJECTS": "1",
        }
    )
    return environment


def _run_trusted_git(
    repo: Path,
    *arguments: str,
) -> subprocess.CompletedProcess[str]:
    try:
        git_binary = TRUSTED_GIT_EXECUTABLE.resolve(strict=True)
    except OSError as exc:
        raise ContractError("cannot resolve the Git executable") from exc
    if not git_binary.is_file() or not os.access(git_binary, os.X_OK):
        raise ContractError("resolved Git executable is unavailable")
    return subprocess.run(
        [
            str(git_binary),
            "--no-replace-objects",
            *arguments,
        ],
        cwd=repo,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=_trusted_git_environment(),
    )


def _verify_live_repository(manifest: dict[str, Any], repo: Path) -> None:
    if not repo.is_dir() or repo.is_symlink():
        raise ContractError("PASS repository must be an existing directory")
    resolved_repo = repo.resolve()

    def run_git(*arguments: str) -> str:
        process = _run_trusted_git(resolved_repo, *arguments)
        if process.returncode != 0:
            raise ContractError(
                f"cannot verify PASS repository with git {' '.join(arguments)}"
            )
        return process.stdout.strip()

    top_level = Path(run_git("rev-parse", "--show-toplevel")).resolve()
    if top_level != resolved_repo:
        raise ContractError("PASS repository must be the exact Git worktree root")
    head_before = run_git("rev-parse", "--verify", "HEAD")
    if head_before != manifest["commit"]:
        raise ContractError("manifest commit differs from repository HEAD")
    worktree_status = run_git(
        "status", "--porcelain=v1", "--untracked-files=all"
    )
    if worktree_status:
        raise ContractError("PASS requires the real repository worktree to be clean")
    head_after = run_git("rev-parse", "--verify", "HEAD")
    if head_after != head_before:
        raise ContractError("repository HEAD changed during PASS verification")


def validate_manifest(
    manifest: dict[str, Any],
    *,
    run_root: Path | None = None,
    repo: Path | None = None,
) -> str:
    required = {
        "schema_version",
        "run_id",
        "commit",
        "dirty",
        "environment_id",
        "gates",
        "verdict",
    }
    missing = required - manifest.keys()
    if missing:
        raise ContractError(f"manifest is missing fields: {sorted(missing)}")
    if manifest["schema_version"] != SCHEMA_VERSION:
        raise ContractError(f"unsupported schema_version: {manifest['schema_version']!r}")
    validate_run_id(manifest["run_id"])
    validate_commit(manifest["commit"])
    if not isinstance(manifest["dirty"], bool):
        raise ContractError("dirty must be boolean")
    if (
        not isinstance(manifest["environment_id"], str)
        or not manifest["environment_id"].strip()
    ):
        raise ContractError("environment_id must be a non-empty string")
    if not isinstance(manifest["gates"], list):
        raise ContractError("gates must be an array")

    gate_by_name: dict[str, dict[str, Any]] = {}
    evidence_by_name: dict[str, dict[str, Any]] = {}
    for gate in manifest["gates"]:
        if not isinstance(gate, dict):
            raise ContractError("each gate must be an object")
        name = gate.get("gate")
        if name in gate_by_name:
            raise ContractError(f"duplicate gate: {name}")
        evidence = _validate_gate(gate, manifest=manifest, run_root=run_root)
        gate_by_name[str(name)] = gate
        if evidence is not None:
            evidence_by_name[str(name)] = evidence

    required_gate_set = set(REQUIRED_RELEASE_GATES)
    actual_gate_set = set(gate_by_name)
    if actual_gate_set != required_gate_set:
        raise ContractError(
            "release gates differ from exact required set: "
            f"missing={sorted(required_gate_set - actual_gate_set)} "
            f"unexpected={sorted(actual_gate_set - required_gate_set)}"
        )

    derived = aggregate_status(
        gate_by_name[name]["status"] for name in REQUIRED_RELEASE_GATES
    )
    declared = validate_status(manifest["verdict"])
    if declared != derived:
        raise ContractError(
            f"declared verdict {declared} does not match derived verdict {derived}"
        )
    if derived == "PASS":
        if manifest["dirty"]:
            raise ContractError("PASS requires a clean integrated worktree")
        if manifest["commit"] == ZERO_COMMIT:
            raise ContractError("PASS cannot use the zero placeholder commit")
        if repo is None:
            raise ContractError("PASS requires repository ancestry verification")
        resolved_repo = repo.resolve()
        _verify_live_repository(manifest, resolved_repo)
        _verify_w7_ancestor(
            manifest,
            evidence_by_name["w7_final"],
            resolved_repo,
        )
    return derived


def render_summary(manifest: dict[str, Any]) -> str:
    lines = [
        "# W9 Release Evidence Summary",
        "",
        f"- Run ID: `{manifest['run_id']}`",
        f"- Integrated commit: `{manifest['commit']}`",
        f"- Environment: `{manifest['environment_id']}`",
        f"- Release verdict: `{manifest['verdict']}`",
        "- W8 dependency: `NOT_REQUIRED`",
        "",
        "## Gates",
        "",
    ]
    for gate in manifest["gates"]:
        lines.append(
            f"- `{gate['gate']}`: `{gate['status']}` — {gate['reason'].strip()}"
        )
    if manifest["verdict"] != "PASS":
        lines.extend(
            [
                "",
                "> This evidence does not authorize production release. "
                "All NOT_VERIFIED gates must be rerun on the final integrated commit.",
            ]
        )
    return "\n".join(lines) + "\n"


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate W9 release evidence")
    subparsers = parser.add_subparsers(dest="command", required=True)

    template = subparsers.add_parser("template")
    template.add_argument("--run-id", required=True)
    template.add_argument("--commit", required=True)
    template.add_argument("--environment-id", required=True)
    template.add_argument("--dirty", action="store_true")
    template.add_argument("--output", type=Path)
    template.add_argument("--summary", type=Path)

    verify = subparsers.add_parser("verify")
    verify.add_argument("--manifest", type=Path, required=True)
    verify.add_argument("--run-root", type=Path)
    verify.add_argument("--repo", type=Path)
    verify.add_argument("--summary", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        if args.command == "template":
            manifest = new_not_verified_manifest(
                run_id=args.run_id,
                commit=args.commit,
                dirty=args.dirty,
                environment_id=args.environment_id,
            )
            if args.output:
                write_json(args.output, manifest)
            else:
                print(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True))
            if args.summary:
                args.summary.write_text(render_summary(manifest), encoding="utf-8")
            return 0

        manifest = read_json(args.manifest)
        verdict = validate_manifest(
            manifest,
            run_root=args.run_root,
            repo=args.repo,
        )
        if args.summary:
            args.summary.write_text(render_summary(manifest), encoding="utf-8")
        print(f"W9_RELEASE_VERDICT={verdict}")
        return 0
    except ContractError as exc:
        print(f"W9_EVIDENCE_INVALID={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
