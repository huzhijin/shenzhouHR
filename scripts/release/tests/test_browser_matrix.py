from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.release.browser_matrix import (
    REQUIRED_PLATFORMS,
    REQUIRED_VIEWPORTS,
    validate_browser_matrix,
)
from scripts.release.common import ContractError


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

    def test_demo_results_cannot_pass_release_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            matrix["source_mode"] = "DEMO"
            matrix["verdict"] = "NOT_VERIFIED"
            result = validate_browser_matrix(matrix, evidence_root=evidence_root)
            self.assertEqual("NOT_VERIFIED", result["browser_gate_status"])

    def test_real_integrated_complete_grid_can_pass_browser_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            result = validate_browser_matrix(matrix, evidence_root=evidence_root)
            self.assertEqual("PASS", result["browser_gate_status"])
            self.assertEqual(0, result["missing_execution_count"])

    def test_duplicate_execution_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            matrix["cases"][0]["executions"].append(
                copy.deepcopy(matrix["cases"][0]["executions"][0])
            )
            with self.assertRaisesRegex(ContractError, "duplicate browser execution"):
                validate_browser_matrix(matrix, evidence_root=evidence_root)

    def test_tampered_trace_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            matrix, evidence_root = self._completed_matrix(Path(directory))
            (evidence_root / "synthetic-trace.zip").write_bytes(b"tampered")
            with self.assertRaisesRegex(ContractError, "SHA-256 mismatch"):
                validate_browser_matrix(matrix, evidence_root=evidence_root)

    def _completed_matrix(self, evidence_root: Path) -> tuple[dict, Path]:
        trace = evidence_root / "synthetic-trace.zip"
        trace.write_bytes(b"synthetic-browser-evidence")
        trace_sha = hashlib.sha256(trace.read_bytes()).hexdigest()
        matrix = copy.deepcopy(self.template)
        matrix["run_id"] = "w9-browser-test-001"
        matrix["commit"] = "c" * 40
        matrix["environment_id"] = "synthetic:browser-contract-test"
        matrix["source_mode"] = "REAL_INTEGRATED"
        matrix["verdict"] = "PASS"
        for case in matrix["cases"]:
            executions = []
            for platform in REQUIRED_PLATFORMS:
                for viewport in REQUIRED_VIEWPORTS:
                    executions.append(
                        {
                            "platform": platform,
                            "viewport": viewport,
                            "status": "PASS",
                            "run_id": matrix["run_id"],
                            "commit": matrix["commit"],
                            "environment_id": matrix["environment_id"],
                            "route": case["route"],
                            "identity": case["identity"],
                            "screenshot_path": None,
                            "screenshot_sha256": None,
                            "trace_path": "synthetic-trace.zip",
                            "trace_sha256": trace_sha,
                            "console_errors": [],
                            "page_errors": [],
                            "network_summary": {"failed_requests": 0},
                            "reason": "synthetic validator test",
                        }
                    )
            case["executions"] = executions
        return matrix, evidence_root
