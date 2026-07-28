#!/usr/bin/env python3

from __future__ import annotations

import copy
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.qa.orchestrate_wave3_leaves import (
    EXPECTED_REPOSITORY_ROOT,
    FIXED_PRE_REVIEW_IDS,
    OrchestrationError,
    build_leaf_plan,
    prepare_login_environment,
    registration_arguments,
    validate_raw_artifacts,
    validate_login_binding,
    validate_plan,
)
from scripts.qa.verify_wave3_evidence import EvidenceError, Wave3Evidence, sha256_file


DATABASE_IDENTITY = (
    "mysql8410:533ca0bc-89c8-41f1-a08b-1ee9344ad44b:"
    "shenzhou_hr_test"
)


class Wave3LeafOrchestratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.verifier = Wave3Evidence(EXPECTED_REPOSITORY_ROOT)
        cls.context = {
            "runId": "w3-orchestrator-test-20260727",
            "sourceTreeHash": "a" * 64,
            "databaseIdentity": DATABASE_IDENTITY,
            "contractSha256": sha256_file(cls.verifier.contract_path),
        }

    def test_exact_order_and_role_closure_come_from_strict_contract(self) -> None:
        self.assertEqual(
            unittest.defaultTestLoader.loadTestsFromTestCase(
                Wave3LeafOrchestratorTest
            ).countTestCases(),
            6,
        )
        self.assertEqual(
            tuple(self.verifier.pre_review_ids), FIXED_PRE_REVIEW_IDS
        )
        plan = build_leaf_plan(self.verifier, self.context)
        leaves = validate_plan(plan, self.verifier, self.context)
        self.assertEqual(len(leaves), 20)
        for leaf in leaves:
            contract = self.verifier.leaf_contracts[leaf["evidenceId"]]
            self.assertEqual(
                [artifact["role"] for artifact in leaf["artifacts"]],
                contract["requiredArtifactRoles"],
            )
            self.assertEqual(
                len({artifact["path"] for artifact in leaf["artifacts"]}),
                len(leaf["artifacts"]),
            )

    def test_plan_rejects_reorder_extra_role_and_path_escape(self) -> None:
        plan = build_leaf_plan(self.verifier, self.context)
        reordered = copy.deepcopy(plan)
        reordered["leaves"][0], reordered["leaves"][1] = (
            reordered["leaves"][1],
            reordered["leaves"][0],
        )
        with self.assertRaisesRegex(OrchestrationError, "order/set"):
            validate_plan(reordered, self.verifier, self.context)

        extra = copy.deepcopy(plan)
        extra["leaves"][0]["artifacts"].append(
            {
                "role": "unexpected-role",
                "path": "leaves/w2-retained/raw/unexpected.json",
            }
        )
        with self.assertRaisesRegex(OrchestrationError, "role order/set"):
            validate_plan(extra, self.verifier, self.context)

        escaped = copy.deepcopy(plan)
        escaped["leaves"][0]["artifacts"][0]["path"] = "../historical.log"
        with self.assertRaisesRegex(OrchestrationError, "safe run-relative"):
            validate_plan(escaped, self.verifier, self.context)

    def test_registration_argv_preserves_primary_and_contract_role_order(self) -> None:
        plan = build_leaf_plan(self.verifier, self.context)
        normal = plan["leaves"][18]
        arguments = registration_arguments(
            EXPECTED_REPOSITORY_ROOT,
            self.context["runId"],
            normal,
        )
        self.assertEqual(arguments[1:3], ["register", "--run-id"])
        self.assertEqual(
            arguments[arguments.index("--evidence-id") + 1],
            "W3-VER-NORMAL-BROWSER",
        )
        primary = arguments[arguments.index("--primary") + 1]
        self.assertEqual(
            primary,
            "normal-browser-matrix="
            "leaves/normal-browser/raw/route-matrix.json",
        )
        registered_roles = [primary.split("=", 1)[0]]
        registered_roles.extend(
            arguments[index + 1].split("=", 1)[0]
            for index, value in enumerate(arguments)
            if value == "--artifact"
        )
        self.assertEqual(
            registered_roles,
            normal["requiredArtifactRoles"],
        )

    def test_generated_login_env_is_external_0600_bound_and_not_returned(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="w3-login-env-test-"
        ) as temporary:
            output = Path(temporary).resolve() / "login.env"
            metadata = prepare_login_environment(
                self.context["runId"],
                DATABASE_IDENTITY,
                output,
                execute=True,
                generate=True,
                source_login_env=None,
            )
            binding = Path(metadata["bindingPath"])
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(binding.stat().st_mode), 0o600)
            contents = output.read_text(encoding="utf-8")
            username = contents.splitlines()[0].split("=", 1)[1]
            password = contents.splitlines()[1].split("=", 1)[1]
            self.assertNotIn(username, repr(metadata))
            self.assertNotIn(password, repr(metadata))
            validated = validate_login_binding(
                output,
                binding,
                self.context["runId"],
                DATABASE_IDENTITY,
            )
            self.assertEqual(
                validated["accountState"],
                "REQUIRES_EXPLICIT_PROVISIONING",
            )

    def test_login_env_refuses_overwrite_and_bad_mode_source(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="w3-login-source-test-"
        ) as temporary:
            root = Path(temporary).resolve()
            source = root / "source.env"
            source.write_text(
                "SHENZHOUHR_LOGIN_USERNAME=synthetic_admin\n"
                "SHENZHOUHR_LOGIN_PASSWORD=opaque_secret\n",
                encoding="utf-8",
            )
            os.chmod(source, 0o644)
            with self.assertRaisesRegex(OrchestrationError, "0600"):
                prepare_login_environment(
                    self.context["runId"],
                    DATABASE_IDENTITY,
                    root / "copied.env",
                    execute=True,
                    generate=False,
                    source_login_env=source,
                )
            os.chmod(source, 0o600)
            occupied = root / "occupied.env"
            occupied.write_text("owned\n", encoding="utf-8")
            os.chmod(occupied, 0o600)
            with self.assertRaisesRegex(OrchestrationError, "overwrite"):
                prepare_login_environment(
                    self.context["runId"],
                    DATABASE_IDENTITY,
                    occupied,
                    execute=True,
                    generate=False,
                    source_login_env=source,
                )

    def test_build_preflight_requires_strict_exact_nine_commit_marker(
        self,
    ) -> None:
        leaf = {
            "evidenceId": "W3-VER-BACKEND-FULL",
            "requiredArtifactRoles": [],
            "artifacts": [],
            "primaryArtifactRole": "backend-full-log",
            "requiredMarkers": ["W3_BACKEND_FULL=PASS"],
        }
        with tempfile.TemporaryDirectory(
            prefix="w3-orchestrator-build-marker-"
        ) as temporary:
            with mock.patch.object(
                self.verifier,
                "_validate_build_gate_commit",
                side_effect=EvidenceError("missing exact-nine marker"),
            ) as validate:
                with self.assertRaisesRegex(
                    OrchestrationError, "batch commit preflight"
                ):
                    validate_raw_artifacts(
                        self.verifier,
                        Path(temporary),
                        self.context,
                        [leaf],
                    )
            validate.assert_called_once()


if __name__ == "__main__":
    unittest.main()
