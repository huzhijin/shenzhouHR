from __future__ import annotations

import copy
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.release.tests.test_browser_matrix import build_completed_matrix
from scripts.release.common import ContractError, sha256_file
from scripts.release.release_evidence import (
    EXPECTED_NATIVE_CHECK_IDS,
    EXPECTED_PERFORMANCE_SCENARIOS,
    EXPECTED_THREAT_CASE_IDS,
    FORMAL_NATIVE_PRODUCER,
    FORMAL_NATIVE_SOURCE,
    FORMAL_PERFORMANCE_PRODUCER,
    FORMAL_PERFORMANCE_SOURCE,
    GATE_EVIDENCE_CONTRACTS,
    REQUIRED_RELEASE_GATES,
    _verify_live_repository,
    _verify_w7_ancestor,
    aggregate_status,
    new_not_verified_manifest,
    validate_manifest,
)

RUN_ID = "w9-test-0001"
COMMIT = "a" * 40
ENVIRONMENT_ID = "synthetic:unit-test"


class ReleaseEvidenceContractTest(unittest.TestCase):
    def test_not_verified_template_is_valid_without_w8(self) -> None:
        manifest = new_not_verified_manifest(
            run_id=RUN_ID,
            commit=COMMIT,
            dirty=True,
            environment_id=ENVIRONMENT_ID,
        )

        self.assertEqual("NOT_VERIFIED", validate_manifest(manifest))
        self.assertNotIn("w8", {gate["gate"] for gate in manifest["gates"]})

    def test_status_precedence(self) -> None:
        self.assertEqual("PASS", aggregate_status(["PASS", "PASS"]))
        self.assertEqual("NOT_VERIFIED", aggregate_status(["PASS", "NOT_VERIFIED"]))
        self.assertEqual("FAIL", aggregate_status(["NOT_VERIFIED", "FAIL"]))

    def test_fail_propagates_with_valid_leaf_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest_with_evidence(root, default_status="PASS")
            manifest["gates"][2]["status"] = "FAIL"
            manifest["gates"][2]["reason"] = "synthetic threshold failure"
            self._update_evidence_status(root, manifest["gates"][2], "FAIL")
            manifest["verdict"] = "FAIL"

            self.assertEqual("FAIL", validate_manifest(manifest, run_root=root))

    def test_cross_commit_leaf_is_rejected(self) -> None:
        manifest = new_not_verified_manifest(
            run_id=RUN_ID,
            commit=COMMIT,
            dirty=True,
            environment_id=ENVIRONMENT_ID,
        )
        manifest["gates"][0]["commit"] = "b" * 40

        with self.assertRaisesRegex(ContractError, "commit differs"):
            validate_manifest(manifest)

    def test_duplicate_gate_is_rejected(self) -> None:
        manifest = new_not_verified_manifest(
            run_id=RUN_ID,
            commit=COMMIT,
            dirty=True,
            environment_id=ENVIRONMENT_ID,
        )
        manifest["gates"].append(copy.deepcopy(manifest["gates"][0]))

        with self.assertRaisesRegex(ContractError, "duplicate gate"):
            validate_manifest(manifest)

    def test_path_escape_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest_with_evidence(root, default_status="PASS")
            manifest["gates"][0]["evidence_path"] = "../escape.json"
            manifest["verdict"] = "PASS"

            with self.assertRaisesRegex(ContractError, "unsafe evidence path"):
                validate_manifest(manifest, run_root=root)

    def test_tampered_evidence_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest_with_evidence(root, default_status="PASS")
            evidence = root / manifest["gates"][0]["evidence_path"]
            evidence.write_text('{"tampered":true}\n', encoding="utf-8")

            with self.assertRaisesRegex(ContractError, "SHA-256 mismatch"):
                validate_manifest(manifest, run_root=root)

    def test_missing_required_gate_is_rejected(self) -> None:
        manifest = new_not_verified_manifest(
            run_id=RUN_ID,
            commit=COMMIT,
            dirty=True,
            environment_id=ENVIRONMENT_ID,
        )
        manifest["gates"] = manifest["gates"][:-1]

        with self.assertRaisesRegex(ContractError, "release gates differ"):
            validate_manifest(manifest)

    def test_unexpected_gate_is_rejected(self) -> None:
        manifest = new_not_verified_manifest(
            run_id=RUN_ID,
            commit=COMMIT,
            dirty=True,
            environment_id=ENVIRONMENT_ID,
        )
        unexpected = copy.deepcopy(manifest["gates"][0])
        unexpected["gate"] = "optional_gate"
        manifest["gates"].append(unexpected)

        with self.assertRaisesRegex(ContractError, "unexpected release gate: optional_gate"):
            validate_manifest(manifest)

    def test_false_pass_is_rejected(self) -> None:
        manifest = new_not_verified_manifest(
            run_id=RUN_ID,
            commit=COMMIT,
            dirty=False,
            environment_id=ENVIRONMENT_ID,
        )
        manifest["verdict"] = "PASS"

        with self.assertRaisesRegex(ContractError, "does not match derived"):
            validate_manifest(manifest)

    def test_leaf_schema_status_and_provenance_are_verified(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest_with_evidence(root, default_status="PASS")
            gate = manifest["gates"][0]
            evidence_path = root / gate["evidence_path"]
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))

            evidence["schema_version"] = "self-reported-but-unsupported/v1"
            evidence_path.write_text(
                json.dumps(evidence, sort_keys=True) + "\n", encoding="utf-8"
            )
            gate["evidence_sha256"] = sha256_file(evidence_path)
            with self.assertRaisesRegex(ContractError, "unsupported evidence schema"):
                validate_manifest(manifest, run_root=root)

            evidence = self._evidence_document("w7_final", "PASS", COMMIT)
            evidence["run_id"] = "different-run"
            evidence_path.write_text(
                json.dumps(evidence, sort_keys=True) + "\n", encoding="utf-8"
            )
            gate["evidence_sha256"] = sha256_file(evidence_path)
            with self.assertRaisesRegex(ContractError, "evidence run_id differs"):
                validate_manifest(manifest, run_root=root)

            evidence = self._evidence_document("w7_final", "FAIL", COMMIT)
            evidence_path.write_text(
                json.dumps(evidence, sort_keys=True) + "\n", encoding="utf-8"
            )
            gate["evidence_sha256"] = sha256_file(evidence_path)
            with self.assertRaisesRegex(ContractError, "evidence status differs"):
                validate_manifest(manifest, run_root=root)

    def test_pass_is_bound_to_live_head_and_clean_worktree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            commit = self._initialize_repository(repo)
            evidence_root = root / "evidence"
            manifest = {"commit": commit}
            _verify_live_repository(manifest, repo)
            _verify_w7_ancestor(
                manifest,
                {"details": {"w7_commit": commit}},
                repo,
            )

            (repo / "untracked.txt").write_text("dirty\n", encoding="utf-8")
            with self.assertRaisesRegex(ContractError, "worktree to be clean"):
                _verify_live_repository(manifest, repo)

    def test_pass_rejects_manifest_commit_that_is_not_live_head(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            self._initialize_repository(repo)
            manifest = {"commit": COMMIT}

            with self.assertRaisesRegex(ContractError, "differs from repository HEAD"):
                _verify_live_repository(manifest, repo)

    def test_pass_uses_exact_repo_root_and_ignores_git_environment_redirects(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            commit = self._initialize_repository(repo)
            other_repo = root / "other-repo"
            self._initialize_repository(other_repo)
            manifest = {"commit": commit}

            with patch.dict(
                os.environ,
                {
                    "GIT_DIR": str(other_repo / ".git"),
                    "GIT_WORK_TREE": str(other_repo),
                    "GIT_REPLACE_REF_BASE": "refs/replace/hostile",
                },
            ):
                _verify_live_repository(manifest, repo)

            nested = repo / "nested"
            nested.mkdir()
            with self.assertRaisesRegex(ContractError, "exact Git worktree root"):
                _verify_live_repository(manifest, nested)

    def test_browser_pass_leaf_is_rejected_without_signed_attestation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest_with_evidence(root, default_status="PASS")
            gate = next(
                gate
                for gate in manifest["gates"]
                if gate["gate"] == "browser_matrix"
            )
            evidence_path = root / "leaves/browser_matrix.json"
            evidence = self._evidence_document(
                "browser_matrix",
                "PASS",
                COMMIT,
                root=root,
            )
            evidence["verdict"] = "PASS"
            evidence_path.write_text(
                json.dumps(evidence, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            gate.update(
                {
                    "status": "PASS",
                    "evidence_path": "leaves/browser_matrix.json",
                    "evidence_sha256": sha256_file(evidence_path),
                }
            )
            manifest["verdict"] = "PASS"

            with self.assertRaisesRegex(
                ContractError,
                "PASS is disabled until a trusted signed",
            ):
                validate_manifest(manifest, run_root=root)

    def test_pass_leaf_is_specialized_even_when_manifest_fails_elsewhere(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest_with_evidence(root, default_status="PASS")
            failed_gate = manifest["gates"][0]
            failed_gate["status"] = "FAIL"
            failed_gate["reason"] = "independent synthetic failure"
            self._update_evidence_status(root, failed_gate, "FAIL")
            manifest["verdict"] = "FAIL"

            performance_gate = next(
                gate
                for gate in manifest["gates"]
                if gate["gate"] == "performance_50_concurrency"
            )
            evidence_path = root / str(performance_gate["evidence_path"])
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            evidence["scenarios"][0]["concurrency"] = 1
            evidence_path.write_text(
                json.dumps(evidence, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            performance_gate["evidence_sha256"] = sha256_file(evidence_path)

            with self.assertRaisesRegex(ContractError, "concurrency differs"):
                validate_manifest(manifest, run_root=root)

    def test_local_performance_output_cannot_be_formal_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest_with_evidence(root, default_status="PASS")
            gate = next(
                gate
                for gate in manifest["gates"]
                if gate["gate"] == "performance_50_concurrency"
            )
            evidence_path = root / str(gate["evidence_path"])
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            evidence["schema_version"] = "shenzhouhr.w9.performance-evidence/v1"
            evidence_path.write_text(
                json.dumps(evidence, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            gate["evidence_sha256"] = sha256_file(evidence_path)

            with self.assertRaisesRegex(ContractError, "unsupported evidence schema"):
                validate_manifest(manifest, run_root=root)

    def test_native_pass_requires_exact_checks_and_formal_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest_with_evidence(root, default_status="PASS")
            gate = next(
                gate
                for gate in manifest["gates"]
                if gate["gate"] == "native_deployment"
            )
            evidence_path = root / str(gate["evidence_path"])
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            evidence["checks"] = evidence["checks"][:-1]
            evidence_path.write_text(
                json.dumps(evidence, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            gate["evidence_sha256"] = sha256_file(evidence_path)

            with self.assertRaisesRegex(ContractError, "raw check evidence"):
                validate_manifest(manifest, run_root=root)

    def test_recovery_pass_requires_authority_and_residual_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest_with_evidence(root, default_status="PASS")
            gate = next(
                gate
                for gate in manifest["gates"]
                if gate["gate"] == "recovery_drill"
            )
            evidence_path = root / str(gate["evidence_path"])
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            evidence["authority_verified_before_and_after"] = False
            evidence["residual_state"] = "UNKNOWN"
            evidence_path.write_text(
                json.dumps(evidence, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            gate["evidence_sha256"] = sha256_file(evidence_path)

            with self.assertRaisesRegex(ContractError, "recovery evidence is invalid"):
                validate_manifest(manifest, run_root=root)

    def _manifest_with_evidence(
        self,
        root: Path,
        *,
        default_status: str,
        commit: str = COMMIT,
    ) -> dict[str, object]:
        gates: list[dict[str, object]] = []
        for gate_name in REQUIRED_RELEASE_GATES:
            effective_status = (
                "NOT_VERIFIED"
                if gate_name == "browser_matrix" and default_status == "PASS"
                else default_status
            )
            relative_path = f"leaves/{gate_name}.json"
            evidence_path = root / relative_path
            evidence_sha256 = None
            recorded_path = None
            if effective_status != "NOT_VERIFIED":
                evidence_path.parent.mkdir(parents=True, exist_ok=True)
                evidence_path.write_text(
                    json.dumps(
                        self._evidence_document(
                            gate_name,
                            effective_status,
                            commit,
                            root=root,
                        ),
                        sort_keys=True,
                    ) + "\n",
                    encoding="utf-8",
                )
                recorded_path = relative_path
                evidence_sha256 = sha256_file(evidence_path)
            gates.append(
                {
                    "gate": gate_name,
                    "status": effective_status,
                    "run_id": RUN_ID,
                    "commit": commit,
                    "environment_id": ENVIRONMENT_ID,
                    "evidence_path": recorded_path,
                    "evidence_sha256": evidence_sha256,
                    "reason": "synthetic contract test",
                    "details": {},
                }
            )
        return {
            "schema_version": "shenzhouhr.w9.release-evidence/v1",
            "run_id": RUN_ID,
            "commit": commit,
            "dirty": False,
            "environment_id": ENVIRONMENT_ID,
            "gates": gates,
            "verdict": (
                "NOT_VERIFIED" if default_status == "PASS" else default_status
            ),
        }

    def _evidence_document(
        self,
        gate_name: str,
        status: str,
        commit: str,
        *,
        root: Path | None = None,
    ) -> dict[str, object]:
        contract = GATE_EVIDENCE_CONTRACTS[gate_name]
        evidence: dict[str, object] = {
            "schema_version": contract.schema_version,
            "run_id": RUN_ID,
            "commit": commit,
            "environment_id": ENVIRONMENT_ID,
        }
        target = evidence
        for field in contract.status_path[:-1]:
            nested: dict[str, object] = {}
            target[field] = nested
            target = nested
        target[contract.status_path[-1]] = status
        if gate_name == "w7_final":
            evidence["details"] = {"w7_commit": commit}
        if status != "PASS":
            return evidence

        if gate_name == "threat_dynamic":
            evidence.update(
                {
                    "data_policy": "SYNTHETIC_ONLY",
                    "static_status": "PASS",
                    "status": "PASS",
                    "dynamic_cases": [
                        {"id": case_id, "status": "PASS"}
                        for case_id in sorted(EXPECTED_THREAT_CASE_IDS)
                    ],
                }
            )
        elif gate_name in {
            "performance_50_concurrency",
            "capacity_36_month",
        }:
            if root is None:
                raise AssertionError(
                    "formal performance PASS fixture requires an evidence root"
                )
            scenarios: list[dict[str, object]] = []
            sample_hashes: dict[str, str] = {}
            for index, (scenario_id, expected) in enumerate(
                EXPECTED_PERFORMANCE_SCENARIOS.items(),
                start=1,
            ):
                sample_hash = f"{index:x}" * 64
                sample_hashes[scenario_id] = sample_hash
                scenario: dict[str, object] = {
                    "scenario_id": scenario_id,
                    **expected,
                    "measurement_status": "PASS",
                    "release_gate_status": "PASS",
                    "samples": expected["total_requests"],
                    "complete_response_count": expected["total_requests"],
                    "status_counts": {"200": expected["total_requests"]},
                    "success_rate": 1.0,
                    "p95_ms": min(100, expected["p95_limit_ms"]),
                    "response_sample_set_sha256": sample_hash,
                }
                if scenario_id == "export-50000":
                    scenario.update(
                        {
                            "completion_status": "READY",
                            "requested_row_count": 50_000,
                            "export_row_count": 50_000,
                            "download_content_length_bytes": 1_000_000,
                            "download_sha256": "e" * 64,
                            "reauthenticated": True,
                        }
                    )
                scenarios.append(scenario)
            raw_path = root / "formal-raw" / f"{gate_name}.json"
            raw_path.parent.mkdir(parents=True, exist_ok=True)
            raw_path.write_text(
                json.dumps(
                    {
                        "schema_version": (
                            "shenzhouhr.w9.formal-performance-raw/v1"
                        ),
                        "run_id": RUN_ID,
                        "commit": commit,
                        "environment_id": ENVIRONMENT_ID,
                        "producer_id": FORMAL_PERFORMANCE_PRODUCER["id"],
                        "scenario_sample_set_sha256": sample_hashes,
                    },
                    sort_keys=True,
                )
                + "\n",
                encoding="utf-8",
            )
            evidence.update(
                {
                    "producer": FORMAL_PERFORMANCE_PRODUCER,
                    "source": FORMAL_PERFORMANCE_SOURCE,
                    "provenance": {
                        "source_mode": "REAL_INTEGRATED",
                        "integrated_commit": commit,
                        "environment_id": ENVIRONMENT_ID,
                        "mysql_version": "8.4.0",
                        "dataset_sha256": "d" * 64,
                        "started_at_utc": "2026-07-29T00:00:00Z",
                        "completed_at_utc": "2026-07-29T00:10:00Z",
                        "raw_artifact_path": raw_path.relative_to(root).as_posix(),
                        "raw_artifact_sha256": sha256_file(raw_path),
                        "credential_values_logged": False,
                    },
                    "release_gate_status": "PASS",
                    "capacity_gate_status": "PASS",
                    "missing_capacity": [],
                    "capacity": {
                        "active_employees": 1000,
                        "daily_peak_punches": 5000,
                        "month_end_concurrency": 50,
                        "existing_database_bytes": 1_000_000,
                        "accumulation_months": 36,
                        "mysql_version": "8.4.0",
                        "flyway_version": "10.0.0",
                        "integrated_commit": commit,
                        "dataset_sha256": "d" * 64,
                    },
                    "scenarios": scenarios,
                }
            )
        elif gate_name == "mysql_8_4_lts":
            evidence.update(
                {
                    "mysql_version": "8.4.0",
                    "server_uuid": "123e4567-e89b-42d3-a456-426614174000",
                    "authority_verified": True,
                }
            )
        elif gate_name == "browser_matrix":
            if root is None:
                raise AssertionError("browser PASS fixture requires an evidence root")
            template = json.loads(
                Path(
                    "docs/verification/wave9/browser/browser-matrix.template.json"
                ).read_text(encoding="utf-8")
            )
            evidence, _ = build_completed_matrix(
                template,
                root,
                run_id=RUN_ID,
                commit=commit,
                environment_id=ENVIRONMENT_ID,
            )
        elif gate_name == "recovery_drill":
            evidence.update(
                {
                    "automated_restore_status": "PASS",
                    "admin_used_for_import": False,
                    "verification_account_read_only": True,
                    "drop_executed": False,
                    "pitr_status": "PASS",
                    "business_reconciliation_status": "PASS",
                    "rpo_minutes": 10,
                    "rto_minutes": 60,
                    "source_server_uuid": "123e4567-e89b-42d3-a456-426614174000",
                    "restore_server_uuid": "123e4567-e89b-42d3-a456-426614174001",
                    "source_environment_id": "nonprod:source",
                    "restore_environment_id": "nonprod:restore",
                    "mysql_version": "8.4.0",
                    "target_database": "shenzhou_hr_w9_restore",
                    "target_retained_for_inspection": True,
                    "residual_state": "COMPLETE_SANDBOX_RETAINED",
                    "authority_check_count": 8,
                    "authority_verified_before_and_after": True,
                }
            )
        elif gate_name == "native_deployment":
            if root is None:
                raise AssertionError(
                    "formal native PASS fixture requires an evidence root"
                )
            checks = [
                {
                    "id": check_id,
                    "status": "PASS",
                    "evidence_sha256": f"{index:x}" * 64,
                }
                for index, check_id in enumerate(
                    sorted(EXPECTED_NATIVE_CHECK_IDS),
                    start=1,
                )
            ]
            raw_path = root / "formal-raw" / "native-deployment.json"
            raw_path.parent.mkdir(parents=True, exist_ok=True)
            raw_path.write_text(
                json.dumps(
                    {
                        "schema_version": (
                            "shenzhouhr.w9.formal-native-deployment-raw/v1"
                        ),
                        "run_id": RUN_ID,
                        "commit": commit,
                        "environment_id": ENVIRONMENT_ID,
                        "producer_id": FORMAL_NATIVE_PRODUCER["id"],
                        "check_evidence_sha256": {
                            check["id"]: check["evidence_sha256"]
                            for check in checks
                        },
                    },
                    sort_keys=True,
                )
                + "\n",
                encoding="utf-8",
            )
            evidence.update(
                {
                    "ubuntu_version": "24.04",
                    "rollback_status": "PASS",
                    "producer": FORMAL_NATIVE_PRODUCER,
                    "source": FORMAL_NATIVE_SOURCE,
                    "provenance": {
                        "source_mode": "REAL_UBUNTU_24_04",
                        "integrated_commit": commit,
                        "environment_id": ENVIRONMENT_ID,
                        "architecture": "x86_64",
                        "candidate_artifact_sha256": "f" * 64,
                        "started_at_utc": "2026-07-29T00:00:00Z",
                        "completed_at_utc": "2026-07-29T00:10:00Z",
                        "raw_artifact_path": raw_path.relative_to(root).as_posix(),
                        "raw_artifact_sha256": sha256_file(raw_path),
                        "credential_values_logged": False,
                    },
                    "checks": checks,
                }
            )
        return evidence

    def _update_evidence_status(
        self, root: Path, gate: dict[str, object], status: str
    ) -> None:
        evidence_path = root / str(gate["evidence_path"])
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        target = evidence
        contract = GATE_EVIDENCE_CONTRACTS[str(gate["gate"])]
        for field in contract.status_path[:-1]:
            target = target[field]
        target[contract.status_path[-1]] = status
        evidence_path.write_text(
            json.dumps(evidence, sort_keys=True) + "\n", encoding="utf-8"
        )
        gate["evidence_sha256"] = sha256_file(evidence_path)

    def _initialize_repository(self, repo: Path) -> str:
        repo.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
        subprocess.run(
            ["git", "config", "user.email", "release-test@example.invalid"],
            cwd=repo,
            check=True,
        )
        subprocess.run(
            ["git", "config", "user.name", "Release Contract Test"],
            cwd=repo,
            check=True,
        )
        (repo / "tracked.txt").write_text("clean\n", encoding="utf-8")
        subprocess.run(["git", "add", "tracked.txt"], cwd=repo, check=True)
        subprocess.run(
            ["git", "commit", "-q", "-m", "fixture"],
            cwd=repo,
            check=True,
        )
        return subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repo,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        ).stdout.strip()
