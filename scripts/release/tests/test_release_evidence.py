from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from scripts.release.common import ContractError, sha256_file
from scripts.release.release_evidence import (
    REQUIRED_RELEASE_GATES,
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

        with self.assertRaisesRegex(ContractError, "missing required gates"):
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

    def _manifest_with_evidence(
        self, root: Path, *, default_status: str
    ) -> dict[str, object]:
        gates: list[dict[str, object]] = []
        for gate_name in REQUIRED_RELEASE_GATES:
            relative_path = f"leaves/{gate_name}.json"
            evidence_path = root / relative_path
            evidence_path.parent.mkdir(parents=True, exist_ok=True)
            evidence_path.write_text(
                json.dumps(
                    {
                        "gate": gate_name,
                        "run_id": RUN_ID,
                        "commit": COMMIT,
                        "environment_id": ENVIRONMENT_ID,
                    },
                    sort_keys=True,
                )
                + "\n",
                encoding="utf-8",
            )
            gates.append(
                {
                    "gate": gate_name,
                    "status": default_status,
                    "run_id": RUN_ID,
                    "commit": COMMIT,
                    "environment_id": ENVIRONMENT_ID,
                    "evidence_path": relative_path,
                    "evidence_sha256": sha256_file(evidence_path),
                    "reason": "synthetic contract test",
                    "details": {"w7_commit": COMMIT}
                    if gate_name == "w7_final"
                    else {},
                }
            )
        return {
            "schema_version": "shenzhouhr.w9.release-evidence/v1",
            "run_id": RUN_ID,
            "commit": COMMIT,
            "dirty": False,
            "environment_id": ENVIRONMENT_ID,
            "gates": gates,
            "verdict": default_status,
        }
