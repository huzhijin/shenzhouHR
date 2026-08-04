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
    _SYNTHETIC_PROVISIONING_PEPPER = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"

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
                self._valid_environment_text(),
                encoding="utf-8",
            )
            os.chmod(env_file, 0o600)
            result = inspect_environment_file(env_file)

            self.assertEqual("PASS", result.status)
            self.assertNotIn("secret-", result.detail)

    def test_environment_file_rejects_duplicates_and_invalid_names(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            env_file.write_text(
                self._valid_environment_text()
                + "SHENZHOUHR_DB_PASSWORD=second-secret\n",
                encoding="utf-8",
            )
            os.chmod(env_file, 0o600)
            result = inspect_environment_file(env_file)
            self.assertEqual("FAIL", result.status)
            self.assertIn("duplicate variable name", result.detail)
            self.assertNotIn("second-secret", result.detail)

            env_file.write_text(
                self._valid_environment_text() + "INVALID-NAME=value\n",
                encoding="utf-8",
            )
            result = inspect_environment_file(env_file)
            self.assertEqual("FAIL", result.status)
            self.assertIn("invalid variable name", result.detail)
            self.assertNotIn("value", result.detail)

    def test_environment_file_rejects_unsupported_assignment_syntax(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            os.chmod(Path(directory), 0o700)

            for invalid_line in (
                "export EXTRA=value",
                " EXTRA=value",
                'EXTRA="quoted-secret"',
                "EXTRA=back\\slash-secret",
            ):
                with self.subTest(invalid_line=invalid_line.split("=", 1)[0]):
                    env_file.write_text(
                        self._valid_environment_text() + invalid_line + "\n",
                        encoding="utf-8",
                    )
                    os.chmod(env_file, 0o600)
                    result = inspect_environment_file(env_file)
                    self.assertEqual("FAIL", result.status)
                    self.assertNotIn("quoted-secret", result.detail)
                    self.assertNotIn("slash-secret", result.detail)

    def test_environment_file_rejects_unknown_override_channels_without_values(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            os.chmod(env_file.parent, 0o700)
            unknown_assignments = (
                "SPRING_PROFILES_INCLUDE=dev-secret",
                "SPRING_APPLICATION_JSON={secret-json}",
                "JAVA_TOOL_OPTIONS=-Dsecret.override=true",
            )
            for assignment in unknown_assignments:
                name, secret_value = assignment.split("=", 1)
                with self.subTest(name=name):
                    env_file.write_text(
                        self._valid_environment_text() + assignment + "\n",
                        encoding="utf-8",
                    )
                    os.chmod(env_file, 0o600)
                    result = inspect_environment_file(env_file)
                    self.assertEqual("FAIL", result.status)
                    self.assertIn("unknown variable names", result.detail)
                    self.assertIn(name, result.detail)
                    self.assertNotIn(secret_value, result.detail)

    def test_environment_file_allows_declared_oa_and_deli_production_keys(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            env_file.write_text(
                self._valid_environment_text()
                + "\n".join(
                    (
                        "SHENZHOUHR_SERVER_PORT=8080",
                        "SHENZHOUHR_DB_POOL_SIZE=12",
                        "SHENZHOUHR_SESSION_IDLE_TIMEOUT=PT30M",
                        "DELI_EPLUS_ENABLED=true",
                        "DELI_EPLUS_BASE_URL=https://v2-api.delicloud.com",
                        "DELI_EPLUS_APP_KEY=synthetic-key",
                        "DELI_EPLUS_APP_SECRET=synthetic-secret",
                        "OA_MYSQL_ENABLED=true",
                        "OA_MYSQL_JDBC_URL=jdbc:mysql://oa.internal/hr",
                        "OA_MYSQL_USERNAME=synthetic-user",
                        "OA_MYSQL_PASSWORD=synthetic-password",
                    )
                )
                + "\n",
                encoding="utf-8",
            )
            os.chmod(env_file, 0o600)
            result = inspect_environment_file(env_file)
            self.assertEqual("PASS", result.status)
            self.assertNotIn("synthetic-secret", result.detail)
            self.assertNotIn("synthetic-password", result.detail)

    def test_environment_file_rejects_development_and_bootstrap_overrides(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            for name in (
                "SHENZHOUHR_DEV_PRINCIPAL_ENABLED",
                "SHENZHOUHR_BOOTSTRAP_ENABLED",
            ):
                with self.subTest(name=name):
                    env_file.write_text(
                        self._valid_environment_text() + f"{name}=true\n",
                        encoding="utf-8",
                    )
                    os.chmod(env_file, 0o600)
                    result = inspect_environment_file(env_file)
                    self.assertEqual("FAIL", result.status)
                    self.assertIn("production safety variables", result.detail)

    def test_environment_file_requires_non_empty_values_and_fixed_production_values(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            os.chmod(env_file.parent, 0o700)
            replacements = {
                "SPRING_PROFILES_ACTIVE=prod": "SPRING_PROFILES_ACTIVE=dev-secret",
                "SHENZHOUHR_SERVER_ADDRESS=127.0.0.1": (
                    "SHENZHOUHR_SERVER_ADDRESS=0.0.0.0-secret"
                ),
                "SHENZHOUHR_SESSION_COOKIE_SECURE=true": (
                    "SHENZHOUHR_SESSION_COOKIE_SECURE=false-secret"
                ),
                "SHENZHOUHR_FLYWAY_ENABLED=false": (
                    "SHENZHOUHR_FLYWAY_ENABLED=true-secret"
                ),
            }
            for expected, replacement in replacements.items():
                with self.subTest(name=expected.split("=", 1)[0]):
                    env_file.write_text(
                        self._valid_environment_text().replace(
                            expected, replacement
                        ),
                        encoding="utf-8",
                    )
                    os.chmod(env_file, 0o600)
                    result = inspect_environment_file(env_file)
                    self.assertEqual("FAIL", result.status)
                    self.assertIn("production safety variables", result.detail)
                    self.assertNotIn("-secret", result.detail)

            env_file.write_text(
                self._valid_environment_text().replace(
                    "SHENZHOUHR_DB_PASSWORD=secret-password",
                    "SHENZHOUHR_DB_PASSWORD=",
                ),
                encoding="utf-8",
            )
            os.chmod(env_file, 0o600)
            result = inspect_environment_file(env_file)
            self.assertEqual("FAIL", result.status)
            self.assertIn("must be non-empty", result.detail)

    def test_environment_file_requires_provisioning_recovery_configuration(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            for name in (
                "SHENZHOUHR_PROVISIONING_PEPPER",
                "SHENZHOUHR_PROVISIONING_KEY_ID",
                "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW",
            ):
                with self.subTest(name=name):
                    lines = [
                        line
                        for line in self._valid_environment_text().splitlines()
                        if not line.startswith(f"{name}=")
                    ]
                    env_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
                    os.chmod(env_file, 0o600)
                    result = inspect_environment_file(env_file)
                    self.assertEqual("FAIL", result.status)
                    self.assertIn("missing variable names", result.detail)
                    self.assertIn(name, result.detail)
                    self.assertNotIn(self._SYNTHETIC_PROVISIONING_PEPPER, result.detail)

    def test_environment_file_validates_provisioning_values_without_leaking_them(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            replacements = (
                (
                    f"SHENZHOUHR_PROVISIONING_PEPPER={self._SYNTHETIC_PROVISIONING_PEPPER}",
                    "SHENZHOUHR_PROVISIONING_PEPPER=pepper-secret-that-must-not-leak",
                    "SHENZHOUHR_PROVISIONING_PEPPER",
                    "pepper-secret-that-must-not-leak",
                ),
                (
                    "SHENZHOUHR_PROVISIONING_KEY_ID=v1",
                    "SHENZHOUHR_PROVISIONING_KEY_ID=unsafe/key-id-secret",
                    "SHENZHOUHR_PROVISIONING_KEY_ID",
                    "unsafe/key-id-secret",
                ),
                (
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT25H",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW",
                    "PT25H",
                ),
                (
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT0S",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW",
                    "PT0S",
                ),
            )
            for current, replacement, name, secret_value in replacements:
                with self.subTest(name=name, replacement=replacement.split("=", 1)[1]):
                    env_file.write_text(
                        self._valid_environment_text().replace(current, replacement),
                        encoding="utf-8",
                    )
                    os.chmod(env_file, 0o600)
                    result = inspect_environment_file(env_file)
                    self.assertEqual("FAIL", result.status)
                    self.assertIn(name, result.detail)
                    self.assertNotIn(secret_value, result.detail)

    def test_environment_file_accepts_exactly_twenty_four_hour_recovery_window(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "shenzhouhr.env"
            env_file.write_text(
                self._valid_environment_text().replace(
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=P1D",
                ),
                encoding="utf-8",
            )
            os.chmod(env_file, 0o600)
            self.assertEqual("PASS", inspect_environment_file(env_file).status)

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

    def _valid_environment_text(self) -> str:
        return (
            "\n".join(
                (
                    "SPRING_PROFILES_ACTIVE=prod",
                    "SHENZHOUHR_SERVER_ADDRESS=127.0.0.1",
                    "SHENZHOUHR_SESSION_COOKIE_SECURE=true",
                    "SHENZHOUHR_DB_URL=secret-url",
                    "SHENZHOUHR_DB_USERNAME=secret-user",
                    "SHENZHOUHR_DB_PASSWORD=secret-password",
                    "SHENZHOUHR_FLYWAY_ENABLED=false",
                    f"SHENZHOUHR_PROVISIONING_PEPPER={self._SYNTHETIC_PROVISIONING_PEPPER}",
                    "SHENZHOUHR_PROVISIONING_KEY_ID=v1",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H",
                )
            )
            + "\n"
        )
