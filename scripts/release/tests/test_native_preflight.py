from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path

from scripts.release.common import ContractError
from scripts.release.native_preflight import (
    build_plan,
    inspect_environment_file,
    verify_artifact_manifest,
)


class NativePreflightTest(unittest.TestCase):
    def test_repository_templates_pass_static_plan_without_changes(self) -> None:
        plan = build_plan(Path.cwd())

        self.assertEqual("PASS", plan["static_status"])
        self.assertEqual("NOT_VERIFIED", plan["release_gate_status"])
        self.assertEqual(0, plan["system_changes"])
        self.assertEqual(0, plan["network_requests"])

    def test_environment_file_checks_names_without_emitting_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            env_file.write_text(
                "\n".join(
                    (
                        "SPRING_PROFILES_ACTIVE=prod",
                        "SHENZHOUHR_DB_URL=secret-url",
                        "SHENZHOUHR_DB_USERNAME=secret-user",
                        "SHENZHOUHR_DB_PASSWORD=secret-password",
                        "SHENZHOUHR_FLYWAY_ENABLED=false",
                    )
                )
                + "\n",
                encoding="utf-8",
            )
            os.chmod(env_file, 0o600)
            result = inspect_environment_file(env_file)

            self.assertEqual("PASS", result.status)
            self.assertNotIn("secret-", result.detail)

    def test_wide_environment_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            env_file.write_text("SPRING_PROFILES_ACTIVE=prod\n", encoding="utf-8")
            os.chmod(env_file, 0o640)

            self.assertEqual("FAIL", inspect_environment_file(env_file).status)

    def test_artifact_manifest_detects_tampering_and_escape(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "release.jar"
            artifact.write_bytes(b"synthetic")
            manifest = root / "manifest.json"
            manifest.write_text(
                json.dumps(
                    {
                        "artifacts": [
                            {
                                "path": "release.jar",
                                "sha256": hashlib.sha256(b"synthetic").hexdigest(),
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            self.assertEqual(
                "PASS", verify_artifact_manifest(root, manifest)[0].status
            )
            artifact.write_bytes(b"tampered")
            self.assertEqual(
                "FAIL", verify_artifact_manifest(root, manifest)[0].status
            )

            manifest.write_text(
                json.dumps(
                    {"artifacts": [{"path": "../escape", "sha256": "0" * 64}]}
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ContractError, "unsafe artifact path"):
                verify_artifact_manifest(root, manifest)
