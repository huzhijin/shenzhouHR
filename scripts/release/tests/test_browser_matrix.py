from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.release.browser_matrix import (
    FORMAL_BROWSER_PRODUCER,
    FORMAL_BROWSER_SOURCE,
    FORMAL_EXECUTION_SCHEMA_VERSION,
    FORMAL_RAW_SCHEMA_VERSION,
    REQUIRED_PLATFORMS,
    REQUIRED_VIEWPORTS,
    validate_browser_matrix,
)
from scripts.release.common import ContractError, sha256_file


def build_completed_matrix(
    template: dict,
    evidence_root: Path,
    *,
    run_id: str,
    commit: str,
    environment_id: str,
) -> tuple[dict, Path]:
    fixture = BrowserMatrixTest()
    fixture.template = copy.deepcopy(template)
    return fixture._completed_matrix(
        evidence_root,
        run_id=run_id,
        commit=commit,
        environment_id=environment_id,
    )


class BrowserMatrixTest(unittest.TestCase):
    def setUp(self) -> None:
        self.template = json.loads(
            Path(
                "docs/verification/wave9/browser/browser-matrix.template.json"
            ).read_text(encoding="utf-8")
        )

    def test_pending_template_is_complete_contract_but_not_verified(self) -> None:
        result = validate_browser_matrix(self.template)

        self.assertEqual("PASS", result["contract_status"])
        self.assertEqual("NOT_VERIFIED", result["browser_gate_status"])
        self.assertEqual(462, result["required_execution_count"])
        self.assertEqual(462, result["missing_execution_count"])

    def test_missing_viewport_is_invalid(self) -> None:
        matrix = copy.deepcopy(self.template)
        matrix["viewports"] = matrix["viewports"][:-1]
        with self.assertRaisesRegex(ContractError, "viewports"):
            validate_browser_matrix(matrix)

    def test_demo_source_mode_is_rejected(self) -> None:
        matrix = copy.deepcopy(self.template)
        matrix["source_mode"] = "DEMO"
        with self.assertRaisesRegex(ContractError, "source_mode"):
            validate_browser_matrix(matrix)

    def test_complete_grid_remains_not_verified_without_trusted_attestation(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            result = validate_browser_matrix(matrix, evidence_root=evidence_root)
            self.assertEqual("NOT_VERIFIED", result["browser_gate_status"])
            self.assertEqual(0, result["missing_execution_count"])
            self.assertEqual(
                FORMAL_BROWSER_PRODUCER["id"], result["producer_id"]
            )
            self.assertEqual(
                "NOT_VERIFIED", result["trusted_attestation_status"]
            )

    def test_self_reported_formal_mode_with_fake_producer_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            matrix["producer"] = {
                "id": "synthetic-browser-producer",
                "version": "1",
                "mode": "FORMAL_EXTERNAL",
            }
            with self.assertRaisesRegex(ContractError, "approved formal producer"):
                validate_browser_matrix(matrix, evidence_root=evidence_root)

    def test_duplicate_execution_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            matrix["cases"][0]["executions"].append(
                copy.deepcopy(matrix["cases"][0]["executions"][0])
            )
            with self.assertRaisesRegex(ContractError, "duplicate browser execution"):
                validate_browser_matrix(matrix, evidence_root=evidence_root)

    def test_reused_artifact_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            first, second = matrix["cases"][0]["executions"][:2]
            second["trace_path"] = first["trace_path"]
            second["trace_sha256"] = first["trace_sha256"]
            with self.assertRaisesRegex(ContractError, "duplicate browser artifact"):
                validate_browser_matrix(matrix, evidence_root=evidence_root)

    def test_tampered_result_artifact_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            result_path = evidence_root / matrix["cases"][0]["executions"][0][
                "result_path"
            ]
            result_path.write_text('{"tampered":true}\n', encoding="utf-8")
            with self.assertRaisesRegex(ContractError, "result SHA-256 mismatch"):
                validate_browser_matrix(matrix, evidence_root=evidence_root)

    def test_pass_rejects_console_or_page_errors(self) -> None:
        for field in ("console_errors", "page_errors"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                matrix, evidence_root = self._completed_matrix(Path(directory))
                matrix["cases"][0]["executions"][0][field] = ["unexpected"]
                with self.assertRaisesRegex(ContractError, "empty console and page"):
                    validate_browser_matrix(matrix, evidence_root=evidence_root)

    def test_pass_rejects_failed_or_incomplete_network_requests(self) -> None:
        for summary in (
            {"total_requests": 1, "completed_requests": 1, "failed_requests": 1},
            {"total_requests": 2, "completed_requests": 1, "failed_requests": 0},
        ):
            with (
                self.subTest(summary=summary),
                tempfile.TemporaryDirectory() as directory,
            ):
                matrix, evidence_root = self._completed_matrix(Path(directory))
                matrix["cases"][0]["executions"][0]["network_summary"] = summary
                with self.assertRaisesRegex(ContractError, "zero failures"):
                    validate_browser_matrix(matrix, evidence_root=evidence_root)

    def test_execution_status_must_match_raw_result(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            matrix["cases"][0]["executions"][0]["status"] = "FAIL"
            matrix["verdict"] = "FAIL"
            with self.assertRaisesRegex(ContractError, "status differs from matrix"):
                validate_browser_matrix(matrix, evidence_root=evidence_root)

    def test_case_role_and_state_are_bound_to_trusted_source(self) -> None:
        matrix = copy.deepcopy(self.template)
        matrix["cases"][0]["role"] = "HR"
        with self.assertRaisesRegex(ContractError, "trusted formal case set"):
            validate_browser_matrix(matrix)

    def _completed_matrix(
        self,
        evidence_root: Path,
        *,
        run_id: str = "w9-browser-test-001",
        commit: str = "c" * 40,
        environment_id: str = "nonprod:browser-contract-test",
    ) -> tuple[dict, Path]:
        matrix = copy.deepcopy(self.template)
        matrix["run_id"] = run_id
        matrix["commit"] = commit
        matrix["environment_id"] = environment_id
        matrix["source_mode"] = "REAL_INTEGRATED"
        matrix["producer"] = copy.deepcopy(FORMAL_BROWSER_PRODUCER)
        matrix["source"] = copy.deepcopy(FORMAL_BROWSER_SOURCE)
        matrix["verdict"] = "NOT_VERIFIED"
        raw_entries: list[dict[str, object]] = []
        for case in matrix["cases"]:
            case["identity"] = (
                "principal-sha256:"
                + hashlib.sha256(case["id"].encode("utf-8")).hexdigest()
            )
            executions = []
            for platform in REQUIRED_PLATFORMS:
                for viewport in REQUIRED_VIEWPORTS:
                    execution_id = f"{case['id']}::{platform}::{viewport}"
                    artifact_stem = hashlib.sha256(
                        execution_id.encode("utf-8")
                    ).hexdigest()
                    trace_path = (
                        evidence_root / "browser-artifacts" / f"{artifact_stem}.zip"
                    )
                    trace_path.parent.mkdir(parents=True, exist_ok=True)
                    trace_path.write_bytes(f"trace:{execution_id}".encode("utf-8"))
                    trace_relative = trace_path.relative_to(evidence_root).as_posix()
                    execution: dict[str, object] = {
                        "execution_id": execution_id,
                        "platform": platform,
                        "viewport": viewport,
                        "status": "PASS",
                        "run_id": matrix["run_id"],
                        "commit": matrix["commit"],
                        "environment_id": matrix["environment_id"],
                        "route": case["route"],
                        "role": case["role"],
                        "identity": case["identity"],
                        "state": case["state"],
                        "result_path": None,
                        "result_sha256": None,
                        "screenshot_path": None,
                        "screenshot_sha256": None,
                        "trace_path": trace_relative,
                        "trace_sha256": sha256_file(trace_path),
                        "console_errors": [],
                        "page_errors": [],
                        "network_summary": {
                            "total_requests": 1,
                            "completed_requests": 1,
                            "failed_requests": 0,
                        },
                        "reason": "formal browser producer completed the execution",
                    }
                    result_path = (
                        evidence_root / "browser-results" / f"{artifact_stem}.json"
                    )
                    result_path.parent.mkdir(parents=True, exist_ok=True)
                    result = {
                        "schema_version": FORMAL_EXECUTION_SCHEMA_VERSION,
                        "producer_id": FORMAL_BROWSER_PRODUCER["id"],
                        "source_sha256": FORMAL_BROWSER_SOURCE["sha256"],
                        "case_id": case["id"],
                        **{
                            field: value
                            for field, value in execution.items()
                            if field not in {"result_path", "result_sha256"}
                        },
                        "started_at_utc": "2026-07-29T00:10:00Z",
                        "completed_at_utc": "2026-07-29T00:11:00Z",
                    }
                    result_path.write_text(
                        json.dumps(result, sort_keys=True) + "\n",
                        encoding="utf-8",
                    )
                    execution["result_path"] = result_path.relative_to(
                        evidence_root
                    ).as_posix()
                    execution["result_sha256"] = sha256_file(result_path)
                    executions.append(execution)
                    raw_entries.append(
                        {
                            "execution_id": execution_id,
                            **{
                                field: execution[field]
                                for field in (
                                    "result_path",
                                    "result_sha256",
                                    "screenshot_path",
                                    "screenshot_sha256",
                                    "trace_path",
                                    "trace_sha256",
                                )
                            },
                        }
                    )
            case["executions"] = executions
        raw_path = evidence_root / "browser-raw-index.json"
        raw_path.write_text(
            json.dumps(
                {
                    "schema_version": FORMAL_RAW_SCHEMA_VERSION,
                    "run_id": matrix["run_id"],
                    "commit": matrix["commit"],
                    "environment_id": matrix["environment_id"],
                    "producer_id": FORMAL_BROWSER_PRODUCER["id"],
                    "source_sha256": FORMAL_BROWSER_SOURCE["sha256"],
                    "execution_artifacts": raw_entries,
                },
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )
        matrix["provenance"] = {
            "source_mode": "REAL_INTEGRATED",
            "integrated_commit": matrix["commit"],
            "environment_id": matrix["environment_id"],
            "started_at_utc": "2026-07-29T00:00:00Z",
            "completed_at_utc": "2026-07-29T01:00:00Z",
            "raw_artifact_path": raw_path.relative_to(evidence_root).as_posix(),
            "raw_artifact_sha256": sha256_file(raw_path),
            "credential_values_logged": False,
        }
        return matrix, evidence_root
