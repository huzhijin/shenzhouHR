from __future__ import annotations

import base64
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[3]
HELPER = REPOSITORY / "deploy/baota/scripts/provisioning-env.sh"
PROVISION = REPOSITORY / "deploy/baota/mysql/provision.sh"
VERIFY = REPOSITORY / "deploy/baota/scripts/verify.sh"
SYNTHETIC_PEPPER_A = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
SYNTHETIC_PEPPER_B = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI"


class BaotaProvisioningEnvironmentTest(unittest.TestCase):
    def test_legacy_pair_is_populated_once_without_secret_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            app_env.write_text("APP_VALUE=present\n", encoding="utf-8")
            migrator_env.write_text("MIGRATOR_VALUE=present\n", encoding="utf-8")
            os.chmod(app_env, 0o640)
            os.chmod(migrator_env, 0o600)

            first = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )
            self.assertEqual(0, first.returncode, first.stderr)
            app_values = self._read_env(app_env)
            migrator_values = self._read_env(migrator_env)
            pepper = app_values["SHENZHOUHR_PROVISIONING_PEPPER"]
            self.assertEqual(pepper, migrator_values["SHENZHOUHR_PROVISIONING_PEPPER"])
            self.assertEqual(43, len(pepper))
            self.assertEqual(32, len(base64.urlsafe_b64decode(pepper + "=")))
            self.assertEqual("v1", app_values["SHENZHOUHR_PROVISIONING_KEY_ID"])
            self.assertEqual(
                "PT1H", app_values["SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW"]
            )
            self.assertNotIn(pepper, first.stdout + first.stderr)
            self.assertEqual(0o640, stat.S_IMODE(app_env.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(migrator_env.stat().st_mode))

            app_before = app_env.read_bytes()
            migrator_before = migrator_env.read_bytes()
            second = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual(app_before, app_env.read_bytes())
            self.assertEqual(migrator_before, migrator_env.read_bytes())
            self.assertNotIn(pepper, second.stdout + second.stderr)

    def test_existing_value_is_copied_to_missing_peer_without_rotation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            app_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_A), encoding="utf-8"
            )
            migrator_env.write_text("MIGRATOR_VALUE=present\n", encoding="utf-8")

            result = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                SYNTHETIC_PEPPER_A,
                self._read_env(migrator_env)["SHENZHOUHR_PROVISIONING_PEPPER"],
            )
            self.assertNotIn(SYNTHETIC_PEPPER_A, result.stdout + result.stderr)

    def test_mismatch_fails_without_modifying_or_leaking_either_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            app_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_A), encoding="utf-8"
            )
            migrator_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_B), encoding="utf-8"
            )
            app_before = app_env.read_bytes()
            migrator_before = migrator_env.read_bytes()

            result = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("SHENZHOUHR_PROVISIONING_PEPPER", result.stderr)
            self.assertNotIn(SYNTHETIC_PEPPER_A, result.stdout + result.stderr)
            self.assertNotIn(SYNTHETIC_PEPPER_B, result.stdout + result.stderr)
            self.assertEqual(app_before, app_env.read_bytes())
            self.assertEqual(migrator_before, migrator_env.read_bytes())

    def test_key_id_and_window_mismatches_fail_without_value_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            mismatches = (
                (
                    "SHENZHOUHR_PROVISIONING_KEY_ID=v1",
                    "SHENZHOUHR_PROVISIONING_KEY_ID=v2-private",
                    "SHENZHOUHR_PROVISIONING_KEY_ID",
                    "v2-private",
                ),
                (
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT2H",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW",
                    "PT2H",
                ),
            )
            for current, replacement, name, hidden_value in mismatches:
                with self.subTest(name=name):
                    app_env.write_text(
                        self._provisioning_text(SYNTHETIC_PEPPER_A),
                        encoding="utf-8",
                    )
                    migrator_env.write_text(
                        self._provisioning_text(SYNTHETIC_PEPPER_A).replace(
                            current, replacement
                        ),
                        encoding="utf-8",
                    )
                    result = self._run_helper(
                        "provisioning_sync_env_pair", app_env, migrator_env
                    )
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(name, result.stderr)
                    self.assertNotIn(hidden_value, result.stdout + result.stderr)

    def test_invalid_existing_recovery_window_fails_without_value_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            invalid_text = self._provisioning_text(SYNTHETIC_PEPPER_A).replace(
                "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H",
                "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT25H",
            )
            app_env.write_text(invalid_text, encoding="utf-8")
            migrator_env.write_text(invalid_text, encoding="utf-8")

            result = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW", result.stderr)
            self.assertNotIn("PT25H", result.stdout + result.stderr)

    def test_provision_writes_one_generated_secret_to_both_env_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_env = root / "app.env"
            migrator_env = root / "migrator.env"
            fake_mysql = root / "mysql"
            fake_mysql.write_text(
                "#!/usr/bin/env bash\n"
                "if [[ \"$*\" == *'SELECT SUBSTRING_INDEX'* ]]; then\n"
                "  printf '8.0.45\\n'\n"
                "else\n"
                "  while IFS= read -r _line; do :; done\n"
                "fi\n",
                encoding="utf-8",
            )
            os.chmod(fake_mysql, 0o700)

            result = subprocess.run(
                [
                    "bash",
                    "-x",
                    str(PROVISION),
                    "--env-file",
                    str(app_env),
                    "--migrator-env-file",
                    str(migrator_env),
                    "--mysql-bin",
                    str(fake_mysql),
                ],
                cwd=REPOSITORY,
                input="root\nsynthetic-root-password\n",
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            app_values = self._read_env(app_env)
            migrator_values = self._read_env(migrator_env)
            pepper = app_values["SHENZHOUHR_PROVISIONING_PEPPER"]
            self.assertEqual(pepper, migrator_values["SHENZHOUHR_PROVISIONING_PEPPER"])
            self.assertEqual(43, len(pepper))
            self.assertEqual(32, len(base64.urlsafe_b64decode(pepper + "=")))
            self.assertNotIn(pepper, result.stdout + result.stderr)
            self.assertNotIn("synthetic-root-password", result.stdout + result.stderr)

    def test_verify_rejects_pair_mismatch_before_health_check_without_leak(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_env = root / "app.env"
            migrator_env = root / "migrator.env"
            app_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_A), encoding="utf-8"
            )
            migrator_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_B), encoding="utf-8"
            )

            result = subprocess.run(
                [
                    "bash",
                    str(VERIFY),
                    "--env-file",
                    str(app_env),
                    "--migrator-env-file",
                    str(migrator_env),
                    "--health-url",
                    "http://127.0.0.1:1/must-not-be-called",
                    "--mysql-bin",
                    "definitely-not-installed-mysql",
                ],
                cwd=REPOSITORY,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("SHENZHOUHR_PROVISIONING_PEPPER", result.stderr)
            self.assertNotIn(SYNTHETIC_PEPPER_A, result.stdout + result.stderr)
            self.assertNotIn(SYNTHETIC_PEPPER_B, result.stdout + result.stderr)
            self.assertNotIn("Checking application health", result.stdout)

    def _run_helper(
        self, function: str, app_env: Path, migrator_env: Path
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "bash",
                "-c",
                (
                    'set -Eeuo pipefail; source "$1"; '
                    f'if {function} "$2" "$3"; then '
                    "printf 'PASS\\n'; else printf 'ERROR: %s\\n' "
                    '"$PROVISIONING_ENV_ERROR" >&2; exit 1; fi'
                ),
                "_",
                str(HELPER),
                str(app_env),
                str(migrator_env),
            ],
            cwd=REPOSITORY,
            capture_output=True,
            text=True,
            check=False,
        )

    def _provisioning_text(self, pepper: str) -> str:
        return (
            "BASE_VALUE=present\n"
            f"SHENZHOUHR_PROVISIONING_PEPPER={pepper}\n"
            "SHENZHOUHR_PROVISIONING_KEY_ID=v1\n"
            "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H\n"
        )

    def _read_env(self, path: Path) -> dict[str, str]:
        return dict(
            line.split("=", 1)
            for line in path.read_text(encoding="utf-8").splitlines()
            if line and not line.startswith("#")
        )


if __name__ == "__main__":
    unittest.main()
