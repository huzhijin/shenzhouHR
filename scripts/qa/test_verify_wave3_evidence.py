#!/usr/bin/env python3
"""Pure-filesystem self-tests for the strict W3 evidence DAG."""

from __future__ import annotations

import json
import os
import shutil
import stat
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_wave3_evidence as evidence_module
from verify_wave3_evidence import (
    BUILD_GATE_ARTIFACTS,
    BUILD_GATE_COMMIT_FILENAME,
    BUILD_GATE_IDS,
    EvidenceError,
    HISTORICAL_RUN_ID,
    Wave3Evidence,
    pretty_json_bytes,
    sha256_file,
    sha256_bytes,
    utc_now,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_SOURCE = REPOSITORY_ROOT / "scripts/qa/wave3-evidence-contract-v1.json"
RUN_ID = "w3-selftest-20260727"
DB_IDENTITY = "mysql8410:selftest-db-0001"


class EvidenceFixture:
    def __init__(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="w3-evidence-selftest-")
        self.root = Path(self.temporary.name).resolve(strict=True)
        contract = json.loads(CONTRACT_SOURCE.read_text(encoding="utf-8"))
        for source_root in contract["source"]["roots"]:
            path = self.root.joinpath(*source_root.split("/"))
            path.mkdir(parents=True, exist_ok=True)
            if source_root != "openspec/changes/wave3-attendance-setup-and-policies":
                (path / "fixture-source.txt").write_text(
                    f"source-root={source_root}\n", encoding="utf-8"
                )
        for additional_file in contract["source"]["additionalFiles"]:
            path = self.root.joinpath(*additional_file.split("/"))
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                f"additional-source={additional_file}\n",
                encoding="utf-8",
            )
        contract_target = self.root / "scripts/qa/wave3-evidence-contract-v1.json"
        shutil.copyfile(CONTRACT_SOURCE, contract_target)
        tasks = self.root / contract["source"]["tasksPath"]
        tasks.parent.mkdir(parents=True, exist_ok=True)
        task_lines = [
            f"- [ ] {task_id} Self-test verification task {task_id}\n"
            for task_id in contract["verificationTaskIds"]
        ]
        task_lines.insert(0, "- [ ] 1.1 Non-verification fixture task\n")
        tasks.write_text("".join(task_lines), encoding="utf-8")
        historical = (
            self.root
            / "docs/verification/wave3/runs"
            / HISTORICAL_RUN_ID
        )
        historical.mkdir(parents=True)
        (historical / "WAVE3-VERIFICATION.md").write_text(
            "# INVALIDATED_NON_FINAL — historical fixture\n\n"
            "- 结论: `INVALIDATED_NON_FINAL`\n",
            encoding="utf-8",
        )
        (historical / "INVALIDATED.md").write_text(
            "# INVALIDATED\n\n"
            "- **Status**: `INVALIDATED`\n"
            "- **Final**: `false`\n",
            encoding="utf-8",
        )
        self.verifier = Wave3Evidence(self.root)
        self.run_root: Path | None = None

    def close(self) -> None:
        self.temporary.cleanup()

    def initialize(self) -> Path:
        self.run_root = self.verifier.initialize(
            RUN_ID,
            DB_IDENTITY,
            "selftest-implementation-agent",
            "selftest-implementation-process",
        )
        return self.run_root

    def register_leaf(self, evidence_id: str, *, wrong_context: bool = False) -> None:
        if self.run_root is None:
            raise AssertionError("fixture is not initialized")
        context = self.verifier._context(self.run_root)
        contract = self.verifier.leaf_contracts[evidence_id]
        if evidence_id in BUILD_GATE_ARTIFACTS:
            if wrong_context:
                raise AssertionError("wrong-context fixture is not supported for build bundle")
            self.ensure_build_bundle()
            slug, artifact_mapping = BUILD_GATE_ARTIFACTS[evidence_id]
            paths = {
                role: self.run_root / "leaves" / slug / "raw" / filename
                for role, filename in artifact_mapping
            }
            primary = paths[contract["primaryArtifactRole"]]
            artifact_specs = [
                f"{role}={path.relative_to(self.run_root).as_posix()}"
                for role, path in paths.items()
                if role != contract["primaryArtifactRole"]
            ]
            self.verifier.register_leaf(
                RUN_ID,
                evidence_id,
                (
                    f"{contract['primaryArtifactRole']}="
                    f"{primary.relative_to(self.run_root).as_posix()}"
                ),
                artifact_specs,
            )
            return
        leaf_root = self.run_root / "leaves" / contract["slug"]
        leaf_root.mkdir(parents=True, exist_ok=True)
        primary = leaf_root / "primary.log"
        context_line = self.verifier.context_line(context, evidence_id)
        if wrong_context:
            context_line = context_line.replace(DB_IDENTITY, "mysql8410:wrong-db")
        primary.write_text(
            "\n".join([context_line, *contract["requiredMarkers"], ""]) ,
            encoding="utf-8",
        )
        artifact_specs: list[str] = []
        for role in contract["requiredArtifactRoles"]:
            if role == contract["primaryArtifactRole"]:
                continue
            artifact = leaf_root / f"{role}.log"
            artifact.write_text(
                f"{context_line}\nartifactRole={role}\n",
                encoding="utf-8",
            )
            artifact_specs.append(
                f"{role}={artifact.relative_to(self.run_root).as_posix()}"
            )
        self.verifier.register_leaf(
            RUN_ID,
            evidence_id,
            (
                f"{contract['primaryArtifactRole']}="
                f"{primary.relative_to(self.run_root).as_posix()}"
            ),
            artifact_specs,
        )

    def ensure_build_bundle(self) -> None:
        if self.run_root is None:
            raise AssertionError("fixture is not initialized")
        marker_path = self.run_root / BUILD_GATE_COMMIT_FILENAME
        if marker_path.exists():
            return
        context = self.verifier._context(self.run_root)
        manifest: dict[str, dict[str, object]] = {}
        for evidence_id, (slug, artifact_mapping) in BUILD_GATE_ARTIFACTS.items():
            contract = self.verifier.leaf_contracts[evidence_id]
            raw_root = self.run_root / "leaves" / slug / "raw"
            raw_root.mkdir(parents=True)
            for role, filename in artifact_mapping:
                artifact = raw_root / filename
                if role == contract["primaryArtifactRole"]:
                    artifact.write_text(
                        "\n".join(
                            [
                                self.verifier.context_line(context, evidence_id),
                                *contract["requiredMarkers"],
                                "",
                            ]
                        ),
                        encoding="utf-8",
                    )
                else:
                    artifact.write_text(
                        f"artifactRole={role}\nfixture=build-bundle\n",
                        encoding="utf-8",
                    )
                os.chmod(artifact, 0o400)
                metadata = artifact.stat()
                manifest[f"{slug}/{filename}"] = {
                    "sizeBytes": metadata.st_size,
                    "sha256": sha256_file(artifact),
                    "device": metadata.st_dev,
                    "inode": metadata.st_ino,
                    "mode": stat.S_IMODE(metadata.st_mode),
                    "mtimeEpochNs": metadata.st_mtime_ns,
                    "ctimeEpochNs": metadata.st_ctime_ns,
                }
            os.chmod(raw_root, 0o500)
        marker = {
            "schemaVersion": 1,
            "nodeType": "wave3-build-gates-commit",
            "runId": RUN_ID,
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": DB_IDENTITY,
            "gateIds": list(BUILD_GATE_IDS),
            "artifactManifest": manifest,
            "artifactManifestSha256": sha256_bytes(
                json.dumps(
                    manifest,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
            ),
            "committedAt": utc_now(),
            "verdict": "PASS",
        }
        marker_path.write_bytes(pretty_json_bytes(marker))
        os.chmod(marker_path, 0o400)

    def register_all(self, *, omit: str = "") -> None:
        for evidence_id in self.verifier.pre_review_ids:
            if evidence_id != omit:
                self.register_leaf(evidence_id)

    def assemble(self) -> None:
        self.verifier.assemble(RUN_ID)

    def write_review(
        self,
        *,
        challenged_ids: list[str] | None = None,
        waiver_authority: bool = False,
        unresolved_findings: list[str] | None = None,
    ) -> None:
        if self.run_root is None:
            raise AssertionError("fixture is not initialized")
        context = self.verifier._context(self.run_root)
        ids = challenged_ids or self.verifier.pre_review_ids
        review_root = self.run_root / "review"
        review_root.mkdir(parents=True, exist_ok=True)
        started = utc_now()
        marker = (
            "W3_INDEPENDENT_REVIEW=PASS reviewerMode=read-only "
            f"sourceHash={context['sourceTreeHash']}"
        )
        report = review_root / "independent-review.md"
        report.write_text(
            "\n".join(
                [
                    "# Independent raw-artifact review",
                    "",
                    marker,
                    "",
                    *[f"- {evidence_id}" for evidence_id in ids],
                    "",
                ]
            ),
            encoding="utf-8",
        )
        registry = json.loads(
            (self.run_root / "artifact-registry.json").read_text(encoding="utf-8")
        )
        review_input = {
            "schemaVersion": 1,
            "nodeType": "independent-review-input",
            "runId": RUN_ID,
            "sourceTreeHashBefore": context["sourceTreeHash"],
            "databaseIdentity": DB_IDENTITY,
            "reviewerId": "selftest-read-only-reviewer",
            "reviewerMode": "read-only",
            "reviewMethod": "raw-artifact-semantic-challenge",
            "waiverAuthority": waiver_authority,
            "rawArtifactsReviewed": True,
            "challengedEvidenceIds": ids,
            "rawEvidenceChallenges": [
                {
                    "evidenceId": entry["evidenceId"],
                    "leafRecordSha256": entry["leafRecord"]["sha256"],
                    "verdict": "PASS",
                    "challengeNotes": [
                        (
                            "Reviewed the leaf record and all registered raw "
                            f"artifacts for {entry['evidenceId']}."
                        )
                    ],
                    "rawArtifacts": [
                        {
                            "artifactRole": artifact["artifactRole"],
                            "sha256": artifact["sha256"],
                            "verdict": "PASS",
                            "challengeNotes": [
                                (
                                    "Inspected the raw artifact bytes, context "
                                    f"binding, and markers for {artifact['artifactRole']}."
                                )
                            ],
                        }
                        for artifact in entry["rawArtifacts"]
                    ],
                }
                for entry in registry["entries"]
                if entry["evidenceId"] in ids
            ],
            "unresolvedFindings": unresolved_findings or [],
            "artifactRegistrySha256": sha256_file(
                self.run_root / "artifact-registry.json"
            ),
            "startedAt": started,
            "reportPath": "review/independent-review.md",
        }
        time.sleep(0.001)
        (review_root / "independent-review-input.json").write_bytes(
            pretty_json_bytes(review_input)
        )

    def through_review(self) -> None:
        self.initialize()
        self.register_all()
        self.assemble()
        self.write_review()
        self.verifier.accept_review(RUN_ID)

    def through_manifest(self) -> None:
        self.through_review()
        self.verifier.create_manifest(RUN_ID)

    def through_integrity(self) -> None:
        self.through_manifest()
        self.verifier.create_integrity(RUN_ID)

    @property
    def tasks_path(self) -> Path:
        return self.root / self.verifier.contract["source"]["tasksPath"]


class Wave3EvidenceSelfTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = EvidenceFixture()

    def tearDown(self) -> None:
        self.fixture.close()

    def test_complete_one_way_dag_and_exact_final_22(self) -> None:
        self.assertEqual(
            unittest.defaultTestLoader.loadTestsFromTestCase(
                Wave3EvidenceSelfTest
            ).countTestCases(),
            22,
        )
        self.fixture.through_manifest()
        manifest = self.fixture.run_root / "final-manifest.json"
        manifest_before = manifest.read_bytes()
        self.fixture.verifier.create_integrity(RUN_ID)
        self.assertEqual(manifest_before, manifest.read_bytes())
        self.fixture.verifier.finalize(RUN_ID, apply_completion=True)
        final = json.loads(
            (self.fixture.run_root / "final-gate.json").read_text(encoding="utf-8")
        )
        self.assertEqual(22, final["childCount"])
        self.assertEqual(
            self.fixture.verifier.final_child_ids,
            final["childEvidenceIds"],
        )
        self.assertEqual(
            "W3_FINAL_INDEPENDENT_ACCEPTANCE=PASS",
            final["marker"],
        )
        tasks = (
            self.fixture.root
            / self.fixture.verifier.contract["source"]["tasksPath"]
        ).read_text(encoding="utf-8")
        for task_id in self.fixture.verifier.contract["verificationTaskIds"]:
            self.assertIn(f"- [x] {task_id} ", tasks)
        self.assertEqual("FINAL_PASS", self.fixture.verifier.audit(RUN_ID))

    def test_review_seal_binds_current_process_source_and_every_raw_artifact(
        self,
    ) -> None:
        self.fixture.through_review()
        context = self.fixture.verifier._context(self.fixture.run_root)
        review = json.loads(
            (
                self.fixture.run_root / "review/independent-review.json"
            ).read_text(encoding="utf-8")
        )
        registry = json.loads(
            (self.fixture.run_root / "artifact-registry.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(f"pid:{os.getpid()}", review["reviewerProcessId"])
        self.assertEqual(os.getpid(), review["reviewerProcessIdentity"]["pid"])
        self.assertEqual(
            Path(sys.executable).resolve(strict=True).as_posix(),
            review["reviewerProcessIdentity"]["executableRealpath"],
        )
        self.assertEqual(
            context["sourceTreeHash"], review["sourceTreeHashBefore"]
        )
        self.assertEqual(
            context["sourceTreeHash"], review["sourceTreeHashAfter"]
        )
        self.assertIs(True, review["sourceReadOnlyUnchanged"])
        self.assertEqual(20, len(review["rawEvidenceChallenges"]))
        for challenge, entry in zip(
            review["rawEvidenceChallenges"],
            registry["entries"],
            strict=True,
        ):
            self.assertEqual("PASS", challenge["verdict"])
            self.assertEqual(
                len(entry["rawArtifacts"]), len(challenge["rawArtifacts"])
            )
            for artifact_challenge in challenge["rawArtifacts"]:
                self.assertEqual("PASS", artifact_challenge["verdict"])
                self.assertTrue(artifact_challenge["challengeNotes"])

    def test_rejects_missing_twentieth_leaf(self) -> None:
        self.fixture.initialize()
        omitted = self.fixture.verifier.pre_review_ids[-1]
        self.fixture.register_all(omit=omitted)
        with self.assertRaisesRegex(EvidenceError, "missing|artifact|leaf"):
            self.fixture.verifier.assemble(RUN_ID)

    def test_rejects_source_drift_but_normalizes_checkbox_tokens(self) -> None:
        self.fixture.initialize()
        context = self.fixture.verifier._context(self.fixture.run_root)
        tasks = (
            self.fixture.root
            / self.fixture.verifier.contract["source"]["tasksPath"]
        )
        tasks.write_text(
            tasks.read_text(encoding="utf-8").replace("- [ ] 6.3.1", "- [x] 6.3.1"),
            encoding="utf-8",
        )
        self.assertEqual(
            context["sourceTreeHash"],
            self.fixture.verifier.compute_source_snapshot().tree_hash,
        )
        (self.fixture.root / "api/fixture-source.txt").write_text(
            "non-checkbox source drift\n", encoding="utf-8"
        )
        self.fixture.register_all()
        with self.assertRaisesRegex(EvidenceError, "source tree changed"):
            self.fixture.verifier.assemble(RUN_ID)

    def test_source_snapshot_binds_backend_and_frontend_build_inputs(self) -> None:
        self.fixture.initialize()
        context = self.fixture.verifier._context(self.fixture.run_root)

        backend_manifest = self.fixture.root / "backend/pom.xml"
        backend_manifest.write_text("<project/>\n", encoding="utf-8")
        self.assertNotEqual(
            context["sourceTreeHash"],
            self.fixture.verifier.compute_source_snapshot().tree_hash,
        )
        backend_manifest.unlink()
        self.assertEqual(
            context["sourceTreeHash"],
            self.fixture.verifier.compute_source_snapshot().tree_hash,
        )

        frontend_lock = self.fixture.root / "frontend/package-lock.json"
        frontend_lock.write_text('{"lockfileVersion": 3}\n', encoding="utf-8")
        self.assertNotEqual(
            context["sourceTreeHash"],
            self.fixture.verifier.compute_source_snapshot().tree_hash,
        )

    def test_rejects_structured_null_and_forbidden_status(self) -> None:
        self.fixture.initialize()
        self.fixture.register_all()
        evidence_id = self.fixture.verifier.pre_review_ids[0]
        slug = self.fixture.verifier.leaf_contracts[evidence_id]["slug"]
        leaf_path = self.fixture.run_root / "leaves" / slug / "leaf.json"
        leaf = json.loads(leaf_path.read_text(encoding="utf-8"))
        leaf["forbidden"] = None
        leaf_path.write_bytes(pretty_json_bytes(leaf))
        with self.assertRaisesRegex(EvidenceError, "JSON null"):
            self.fixture.verifier.assemble(RUN_ID)
        leaf["forbidden"] = "NOT_VERIFIED"
        leaf_path.write_bytes(pretty_json_bytes(leaf))
        with self.assertRaisesRegex(EvidenceError, "NOT_VERIFIED"):
            self.fixture.verifier.assemble(RUN_ID)

    def test_rejects_boolean_integer_mutants_at_every_dag_stage(self) -> None:
        contract_path = (
            self.fixture.root / "scripts/qa/wave3-evidence-contract-v1.json"
        )
        contract = json.loads(contract_path.read_text(encoding="utf-8"))
        contract["schemaVersion"] = True
        contract_path.write_bytes(pretty_json_bytes(contract))
        with self.assertRaisesRegex(EvidenceError, "exact JSON integer"):
            Wave3Evidence(self.fixture.root)

        self.fixture.close()
        self.fixture = EvidenceFixture()
        contract_path = (
            self.fixture.root / "scripts/qa/wave3-evidence-contract-v1.json"
        )
        contract_text = contract_path.read_text(encoding="utf-8")
        contract_path.write_text(
            contract_text.replace(
                '"schemaVersion": 1,',
                '"schemaVersion": 1,\n  "schemaVersion": 1,',
                1,
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(EvidenceError, "duplicate JSON object key"):
            Wave3Evidence(self.fixture.root)

        self.fixture.close()
        self.fixture = EvidenceFixture()
        self.fixture.initialize()
        context_path = self.fixture.run_root / "run-context.json"
        context = json.loads(context_path.read_text(encoding="utf-8"))
        context["startedAtEpochNs"] = True
        context_path.write_bytes(pretty_json_bytes(context))
        with self.assertRaisesRegex(EvidenceError, "exact JSON integer"):
            self.fixture.verifier._context(self.fixture.run_root)

        self.fixture.close()
        self.fixture = EvidenceFixture()
        self.fixture.initialize()
        evidence_id = self.fixture.verifier.pre_review_ids[0]
        self.fixture.register_leaf(evidence_id)
        slug = self.fixture.verifier.leaf_contracts[evidence_id]["slug"]
        leaf_path = self.fixture.run_root / "leaves" / slug / "leaf.json"
        leaf = json.loads(leaf_path.read_text(encoding="utf-8"))
        leaf["startedAtEpochNs"] = True
        leaf_path.write_bytes(pretty_json_bytes(leaf))
        with self.assertRaisesRegex(EvidenceError, "exact JSON integer"):
            self.fixture.verifier._validate_leaf(
                self.fixture.run_root, evidence_id, time.time_ns()
            )

        self.fixture.close()
        self.fixture = EvidenceFixture()
        self.fixture.initialize()
        self.fixture.register_all()
        self.fixture.assemble()
        source_start_path = self.fixture.run_root / "source/start.json"
        source_start_bytes = source_start_path.read_bytes()
        source_start = json.loads(source_start_bytes)
        source_start["unsealedField"] = "mutant"
        source_start_path.write_bytes(pretty_json_bytes(source_start))
        with self.assertRaisesRegex(EvidenceError, "key closure"):
            self.fixture.verifier._validate_source_pair(
                self.fixture.run_root
            )
        source_start_path.write_bytes(source_start_bytes)

        registry_path = self.fixture.run_root / "artifact-registry.json"
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
        registry["preReviewLeafCount"] = True
        registry_path.write_bytes(pretty_json_bytes(registry))
        with self.assertRaisesRegex(EvidenceError, "exact JSON integer"):
            self.fixture.verifier._validate_registry_matrix_report(
                self.fixture.run_root, time.time_ns()
            )

        self.fixture.close()
        self.fixture = EvidenceFixture()
        self.fixture.through_integrity()
        self.fixture.verifier.finalize(RUN_ID, apply_completion=True)
        proof_path = (
            self.fixture.run_root / "checkbox-normalization-proof.json"
        )
        proof = json.loads(proof_path.read_text(encoding="utf-8"))
        proof["schemaVersion"] = True
        proof_path.write_bytes(pretty_json_bytes(proof))
        with self.assertRaisesRegex(EvidenceError, "exact JSON integer"):
            self.fixture.verifier._validate_final_gate(
                self.fixture.run_root,
                require_completion_applied=True,
            )

    def test_rejects_wrong_database_context_and_stale_artifact(self) -> None:
        self.fixture.initialize()
        first = self.fixture.verifier.pre_review_ids[0]
        with self.assertRaisesRegex(EvidenceError, "context"):
            self.fixture.register_leaf(first, wrong_context=True)

        second = self.fixture.verifier.pre_review_ids[1]
        context = self.fixture.verifier._context(self.fixture.run_root)
        contract = self.fixture.verifier.leaf_contracts[second]
        leaf_root = self.fixture.run_root / "leaves" / contract["slug"]
        leaf_root.mkdir(parents=True, exist_ok=True)
        primary = leaf_root / "primary.log"
        primary.write_text(
            "\n".join(
                [
                    self.fixture.verifier.context_line(context, second),
                    *contract["requiredMarkers"],
                    "",
                ]
            ),
            encoding="utf-8",
        )
        stale_ns = context["startedAtEpochNs"] - 10_000_000_000
        os.utime(primary, ns=(stale_ns, stale_ns))
        with self.assertRaisesRegex(EvidenceError, "predates"):
            extra_specs: list[str] = []
            for role in contract["requiredArtifactRoles"]:
                if role == contract["primaryArtifactRole"]:
                    continue
                artifact = leaf_root / f"{role}.log"
                artifact.write_text("stale fixture extra\n", encoding="utf-8")
                extra_specs.append(
                    f"{role}={artifact.relative_to(self.fixture.run_root).as_posix()}"
                )
            self.fixture.verifier.register_leaf(
                RUN_ID,
                second,
                (
                    f"{contract['primaryArtifactRole']}="
                    f"{primary.relative_to(self.fixture.run_root).as_posix()}"
                ),
                extra_specs,
            )

    def test_rejects_non_exact_or_waiving_review(self) -> None:
        self.fixture.initialize()
        self.fixture.register_all()
        self.fixture.assemble()
        self.fixture.write_review(
            challenged_ids=self.fixture.verifier.pre_review_ids[:-1]
        )
        with self.assertRaisesRegex(EvidenceError, "challengedEvidenceIds"):
            self.fixture.verifier.accept_review(RUN_ID)

        shutil.rmtree(self.fixture.run_root / "review")
        self.fixture.write_review(waiver_authority=True)
        with self.assertRaisesRegex(EvidenceError, "waiverAuthority"):
            self.fixture.verifier.accept_review(RUN_ID)

        shutil.rmtree(self.fixture.run_root / "review")
        self.fixture.write_review()
        input_path = (
            self.fixture.run_root / "review/independent-review-input.json"
        )
        review_input = json.loads(input_path.read_text(encoding="utf-8"))
        review_input["waiverAuthority"] = 0
        input_path.write_bytes(pretty_json_bytes(review_input))
        with self.assertRaisesRegex(EvidenceError, "exact JSON boolean"):
            self.fixture.verifier.accept_review(RUN_ID)

    def test_rejects_review_missing_one_raw_artifact_challenge(self) -> None:
        self.fixture.initialize()
        self.fixture.register_all()
        self.fixture.assemble()
        self.fixture.write_review()
        input_path = (
            self.fixture.run_root / "review/independent-review-input.json"
        )
        review_input = json.loads(input_path.read_text(encoding="utf-8"))
        review_input["rawEvidenceChallenges"][0]["rawArtifacts"].pop()
        input_path.write_bytes(pretty_json_bytes(review_input))
        with self.assertRaisesRegex(EvidenceError, "every raw artifact"):
            self.fixture.verifier.accept_review(RUN_ID)
        self.assertFalse(
            (self.fixture.run_root / "review/independent-review.json").exists()
        )

    def test_review_seal_rejects_source_change_between_before_and_after(
        self,
    ) -> None:
        self.fixture.initialize()
        self.fixture.register_all()
        self.fixture.assemble()
        self.fixture.write_review()
        original_identity = (
            self.fixture.verifier._current_reviewer_process_identity
        )

        def mutate_source_during_review() -> dict[str, object]:
            (self.fixture.root / "api/fixture-source.txt").write_text(
                "source changed during review seal\n",
                encoding="utf-8",
            )
            return original_identity()

        with patch.object(
            self.fixture.verifier,
            "_current_reviewer_process_identity",
            side_effect=mutate_source_during_review,
        ):
            with self.assertRaisesRegex(
                EvidenceError, "source changed during read-only"
            ):
                self.fixture.verifier.accept_review(RUN_ID)
        self.assertFalse(
            (self.fixture.run_root / "review/independent-review.json").exists()
        )

    def test_rejects_backward_manifest_reference(self) -> None:
        self.fixture.initialize()
        self.fixture.register_all()
        self.fixture.assemble()
        report = self.fixture.run_root / "WAVE3-VERIFICATION.md"
        report.write_text(
            report.read_text(encoding="utf-8") + "final-manifest.json\n",
            encoding="utf-8",
        )
        self.fixture.write_review()
        self.fixture.verifier.accept_review(RUN_ID)
        with self.assertRaisesRegex(EvidenceError, "backward manifest reference"):
            self.fixture.verifier.create_manifest(RUN_ID)

    def test_rejects_manifest_with_not_exactly_five_upstream_nodes(self) -> None:
        self.fixture.through_manifest()
        manifest_path = self.fixture.run_root / "final-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["upstreamNodes"].pop()
        manifest_path.write_bytes(pretty_json_bytes(manifest))
        with self.assertRaisesRegex(EvidenceError, "exactly five"):
            self.fixture.verifier.create_integrity(RUN_ID)

    def test_final_marker_requires_detached_checkbox_application(self) -> None:
        self.fixture.through_manifest()
        self.fixture.verifier.create_integrity(RUN_ID)
        with self.assertRaisesRegex(EvidenceError, "--apply-completion"):
            self.fixture.verifier.finalize(RUN_ID, apply_completion=False)

    def test_preexisting_partial_final_output_never_changes_tasks(self) -> None:
        self.fixture.through_integrity()
        tasks_before = self.fixture.tasks_path.read_bytes()
        proof_path = (
            self.fixture.run_root / "checkbox-normalization-proof.json"
        )
        proof_path.write_text("{}\n", encoding="utf-8")
        with self.assertRaisesRegex(
            EvidenceError, "incomplete pre-existing FINAL output"
        ):
            self.fixture.verifier.finalize(RUN_ID, apply_completion=True)
        self.assertEqual(tasks_before, self.fixture.tasks_path.read_bytes())
        self.assertEqual(b"{}\n", proof_path.read_bytes())
        self.assertFalse(
            (self.fixture.run_root / "final-gate.json").exists()
        )

    def test_final_output_write_failure_rolls_back_without_checking_tasks(
        self,
    ) -> None:
        self.fixture.through_integrity()
        tasks_before = self.fixture.tasks_path.read_bytes()
        original_write = evidence_module.write_new_file

        def fail_final_write(path: Path, contents: bytes) -> None:
            if path.name == "final-gate.json":
                raise OSError("injected final write failure")
            original_write(path, contents)

        with patch.object(
            evidence_module,
            "write_new_file",
            side_effect=fail_final_write,
        ):
            with self.assertRaisesRegex(OSError, "injected final write failure"):
                self.fixture.verifier.finalize(
                    RUN_ID, apply_completion=True
                )
        self.assertEqual(tasks_before, self.fixture.tasks_path.read_bytes())
        self.assertFalse(
            (
                self.fixture.run_root / "checkbox-normalization-proof.json"
            ).exists()
        )
        self.assertFalse(
            (self.fixture.run_root / "final-gate.json").exists()
        )

    def test_final_recovers_from_outputs_committed_before_checkbox_write(
        self,
    ) -> None:
        self.fixture.through_integrity()
        tasks_before = self.fixture.tasks_path.read_bytes()
        with patch.object(
            self.fixture.verifier,
            "_commit_completion",
            side_effect=KeyboardInterrupt("injected hard interruption"),
        ):
            with self.assertRaises(KeyboardInterrupt):
                self.fixture.verifier.finalize(
                    RUN_ID, apply_completion=True
                )
        self.assertEqual(tasks_before, self.fixture.tasks_path.read_bytes())
        self.assertTrue(
            (
                self.fixture.run_root / "checkbox-normalization-proof.json"
            ).is_file()
        )
        self.assertTrue(
            (self.fixture.run_root / "final-gate.json").is_file()
        )

        self.fixture.verifier.finalize(RUN_ID, apply_completion=True)
        for task_id in self.fixture.verifier.contract["verificationTaskIds"]:
            self.assertIn(
                f"- [x] {task_id} ",
                self.fixture.tasks_path.read_text(encoding="utf-8"),
            )
        self.assertEqual("FINAL_PASS", self.fixture.verifier.audit(RUN_ID))

    def test_permanently_rejects_historical_run_id(self) -> None:
        with self.assertRaisesRegex(EvidenceError, "INVALIDATED_NON_FINAL"):
            self.fixture.verifier.initialize(
                HISTORICAL_RUN_ID,
                DB_IDENTITY,
                "selftest-implementation-agent",
                "selftest-implementation-process",
            )

    def test_rejects_run_root_symlink(self) -> None:
        outside = self.fixture.root / "outside-run"
        outside.mkdir()
        evidence_root = self.fixture.root / "docs/verification/wave3/runs"
        (evidence_root / RUN_ID).symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(EvidenceError, "reuse"):
            self.fixture.verifier.initialize(
                RUN_ID,
                DB_IDENTITY,
                "selftest-implementation-agent",
                "selftest-implementation-process",
            )
        (evidence_root / RUN_ID).unlink()

        verification = self.fixture.root / "docs/verification"
        outside = self.fixture.root / "outside-evidence-authority"
        outside.mkdir()
        moved = outside / "verification-real"
        verification.rename(moved)
        verification.symlink_to(moved, target_is_directory=True)
        with self.assertRaisesRegex(EvidenceError, "symbolic"):
            Wave3Evidence(self.fixture.root)

        verification.unlink()
        verification.symlink_to(
            outside / "missing-verification", target_is_directory=True
        )
        with self.assertRaisesRegex(EvidenceError, "symbolic"):
            Wave3Evidence(self.fixture.root)

    def test_build_leaf_manual_register_cannot_bypass_missing_commit_marker(
        self,
    ) -> None:
        self.fixture.initialize()
        evidence_id = BUILD_GATE_IDS[0]
        contract = self.fixture.verifier.leaf_contracts[evidence_id]
        raw_root = (
            self.fixture.run_root / "leaves" / contract["slug"] / "raw"
        )
        raw_root.mkdir(parents=True)
        primary = raw_root / "backend-full-log.log"
        context = self.fixture.verifier._context(self.fixture.run_root)
        primary.write_text(
            "\n".join(
                [
                    self.fixture.verifier.context_line(context, evidence_id),
                    *contract["requiredMarkers"],
                    "",
                ]
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(EvidenceError, "commit"):
            self.fixture.verifier.register_leaf(
                RUN_ID,
                evidence_id,
                "backend-full-log=leaves/backend-full/raw/backend-full-log.log",
                [
                    "backend-test-summary="
                    "leaves/backend-full/raw/backend-test-summary.json"
                ],
            )

    def test_build_commit_marker_rejects_manifest_hash_mutation(self) -> None:
        self.fixture.initialize()
        self.fixture.ensure_build_bundle()
        marker_path = self.fixture.run_root / BUILD_GATE_COMMIT_FILENAME
        os.chmod(marker_path, 0o600)
        marker = json.loads(marker_path.read_text(encoding="utf-8"))
        marker["artifactManifestSha256"] = "0" * 64
        marker_path.write_bytes(pretty_json_bytes(marker))
        os.chmod(marker_path, 0o400)
        with self.assertRaisesRegex(EvidenceError, "manifest SHA256"):
            self.fixture.register_leaf(BUILD_GATE_IDS[0])

    def test_registered_build_leaf_revalidates_raw_bundle_identity(self) -> None:
        self.fixture.initialize()
        evidence_id = BUILD_GATE_IDS[0]
        self.fixture.register_leaf(evidence_id)
        slug, artifacts = BUILD_GATE_ARTIFACTS[evidence_id]
        raw_root = self.fixture.run_root / "leaves" / slug / "raw"
        artifact = raw_root / artifacts[0][1]
        os.chmod(raw_root, 0o700)
        os.chmod(artifact, 0o600)
        artifact.write_bytes(artifact.read_bytes() + b"tamper\n")
        os.chmod(artifact, 0o400)
        os.chmod(raw_root, 0o500)
        with self.assertRaisesRegex(EvidenceError, "commit manifest"):
            self.fixture.verifier._validate_leaf(
                self.fixture.run_root, evidence_id, time.time_ns()
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
