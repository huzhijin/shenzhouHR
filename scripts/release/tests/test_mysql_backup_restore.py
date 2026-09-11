from __future__ import annotations

import hashlib
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.release.common import ContractError
from scripts.release.mysql_backup import backup_plan, run_backup
from scripts.release.mysql_restore import restore_plan, run_restore
from scripts.release.mysql_safety import (
    MySqlClientIdentity,
    mysql_connection_command,
    prepare_output_directory,
    read_client_identity,
    require_executable,
    validate_restricted_grants,
    validate_restore_target,
)

RUN_ID = "w9-backup-test-001"
SOURCE_DATABASE = "shenzhou_hr_test"
TARGET_DATABASE = "shenzhou_hr_w9_restore"
SERVER_UUID = "11111111-2222-3333-4444-555555555555"
ENVIRONMENT_ID = "nonprod:w9-synthetic"


class MySqlBackupRestoreTest(unittest.TestCase):
    def test_default_plans_send_no_connections_and_keep_release_unverified(self) -> None:
        backup = backup_plan(RUN_ID, SOURCE_DATABASE)
        restore = restore_plan(RUN_ID, TARGET_DATABASE)

        self.assertEqual(0, backup["network_connections"])
        self.assertEqual("NOT_VERIFIED", backup["rpo_gate_status"])
        self.assertEqual(0, restore["network_connections"])
        self.assertEqual("NOT_VERIFIED", restore["recovery_gate_status"])
        self.assertIn("expected_server_uuid", backup["required_execution_parameters"])
        self.assertIn(
            "exact target schema",
            " ".join(restore["required_external_controls"]),
        )

    def test_client_file_is_strict_loopback_only_and_0600(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = self._client_file(root, "admin.cnf", "synthetic_admin")
            self.assertEqual(
                MySqlClientIdentity("127.0.0.1", 13306, "synthetic_admin"),
                read_client_identity(client, Path.cwd()),
            )

            command = mysql_connection_command(
                "mysql",
                client,
                MySqlClientIdentity("127.0.0.1", 13306, "synthetic_admin"),
                "--execute",
                "SELECT 1",
            )
            self.assertEqual(f"--defaults-file={client}", command[1])
            self.assertEqual("--no-login-paths", command[2])
            self.assertNotIn(
                f"--defaults-extra-file={client}",
                command,
            )
            with self.assertRaisesRegex(ContractError, "absolute"):
                require_executable("mysql")

            os.chmod(client, 0o640)
            with self.assertRaisesRegex(ContractError, "0600"):
                read_client_identity(client, Path.cwd())

            insecure_directory = root / "insecure"
            insecure_directory.mkdir(mode=0o755)
            insecure_client = self._client_file(
                insecure_directory,
                "admin.cnf",
                "synthetic_admin",
            )
            with self.assertRaisesRegex(ContractError, "directory mode"):
                read_client_identity(insecure_client, Path.cwd())

    def test_client_file_rejects_include_unknown_duplicate_and_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases = {
                "include": (
                    "[client]\nhost=127.0.0.1\nport=13306\n"
                    "user=synthetic\npassword=secret\n!include /tmp/remote.cnf\n"
                ),
                "unknown": (
                    "[client]\nhost=127.0.0.1\nport=13306\n"
                    "user=synthetic\npassword=secret\ninit-command=DROP DATABASE prod\n"
                ),
                "duplicate": (
                    "[client]\nhost=127.0.0.1\nhost=127.0.0.2\nport=13306\n"
                    "user=synthetic\npassword=secret\n"
                ),
            }
            for name, content in cases.items():
                with self.subTest(name=name):
                    client = root / f"{name}.cnf"
                    client.write_text(content, encoding="utf-8")
                    os.chmod(client, 0o600)
                    with self.assertRaises(ContractError):
                        read_client_identity(client, Path.cwd())

            valid = self._client_file(root, "valid.cnf", "synthetic")
            link = root / "client-link.cnf"
            link.symlink_to(valid)
            with self.assertRaisesRegex(ContractError, "symlink"):
                read_client_identity(link, Path.cwd())

    def test_output_directory_requires_current_owner_and_exact_0700(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "evidence"
            output.mkdir(mode=0o755)
            with self.assertRaisesRegex(ContractError, "0700"):
                prepare_output_directory(output, Path.cwd())

    def test_fake_backup_and_restore_use_partitioned_accounts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            admin = self._client_file(root, "admin.cnf", "synthetic_admin")
            importer = self._client_file(root, "import.cnf", "synthetic_import")
            verifier = self._client_file(root, "verify.cnf", "synthetic_verify")
            fake_mysql = self._fake_mysql(root)
            fake_dump = self._fake_dump(root)
            output = root / "evidence"

            backup = self._run_backup(
                client=admin,
                output=output,
                fake_mysql=fake_mysql,
                fake_dump=fake_dump,
            )
            dump = output / str(backup["dump_file"])
            manifest = output / f"{RUN_ID}-backup-manifest.json"
            self.assertEqual("PASS", backup["status"])
            self.assertEqual("NOT_VERIFIED", backup["rpo_gate_status"])
            self.assertEqual(SERVER_UUID, backup["server_uuid"])
            self.assertEqual(ENVIRONMENT_ID, backup["environment_id"])
            self.assertEqual(0o600, stat.S_IMODE(dump.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(manifest.stat().st_mode))

            verification = root / "verify.sql"
            verification.write_text("SELECT COUNT(*) FROM employee;\n", encoding="utf-8")
            os.chmod(verification, 0o600)
            verification_sha = hashlib.sha256(verification.read_bytes()).hexdigest()
            restore = run_restore(
                repository_root=Path.cwd(),
                run_id=RUN_ID,
                target_database=TARGET_DATABASE,
                confirmation=self._confirmation(str(backup["dump_sha256"])),
                client_file=admin,
                import_client_file=importer,
                verification_client_file=verifier,
                backup_manifest_path=manifest,
                dump_path=dump,
                mysql_binary=str(fake_mysql),
                verification_sql=verification,
                verification_sql_sha256=verification_sha,
                expected_source_database=SOURCE_DATABASE,
                expected_dump_sha256=str(backup["dump_sha256"]),
                expected_backup_server_uuid=SERVER_UUID,
                expected_backup_environment_id=ENVIRONMENT_ID,
                expected_restore_server_uuid=SERVER_UUID,
                expected_restore_environment_id=ENVIRONMENT_ID,
            )
            self.assertEqual("PASS", restore["automated_restore_status"])
            self.assertEqual("NOT_VERIFIED", restore["recovery_gate_status"])
            self.assertFalse(restore["drop_executed"])
            self.assertFalse(restore["admin_used_for_import"])
            self.assertTrue(restore["verification_account_read_only"])

    def test_backup_rejects_authority_mismatch_and_existing_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            admin = self._client_file(root, "admin.cnf", "synthetic_admin")
            fake_mysql = self._fake_mysql(root)
            fake_dump = self._fake_dump(root)
            output = root / "evidence"

            with self.assertRaisesRegex(ContractError, "server_uuid"):
                run_backup(
                    repository_root=Path.cwd(),
                    run_id=RUN_ID,
                    database=SOURCE_DATABASE,
                    client_file=admin,
                    output_directory=output,
                    mysql_binary=str(fake_mysql),
                    mysqldump_binary=str(fake_dump),
                    expected_server_uuid="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    expected_environment_id=ENVIRONMENT_ID,
                )

            backup = self._run_backup(
                client=admin,
                output=output,
                fake_mysql=fake_mysql,
                fake_dump=fake_dump,
            )
            self.assertEqual("PASS", backup["status"])
            with self.assertRaisesRegex(ContractError, "existing backup artifact"):
                self._run_backup(
                    client=admin,
                    output=output,
                    fake_mysql=fake_mysql,
                    fake_dump=fake_dump,
                )

    def test_backup_never_follows_partial_symlink_and_cleans_failed_partial(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            admin = self._client_file(root, "admin.cnf", "synthetic_admin")
            fake_mysql = self._fake_mysql(root)
            output = root / "evidence"
            output.mkdir(mode=0o700)
            victim = root / "victim"
            victim.write_text("keep", encoding="utf-8")
            partial = output / f".{RUN_ID}-{SOURCE_DATABASE}.sql.partial"
            partial.symlink_to(victim)
            with self.assertRaisesRegex(ContractError, "existing backup artifact"):
                self._run_backup(
                    client=admin,
                    output=output,
                    fake_mysql=fake_mysql,
                    fake_dump=self._fake_dump(root),
                )
            self.assertEqual("keep", victim.read_text(encoding="utf-8"))

            partial.unlink()
            failing_dump = self._fake_dump(root, fail=True)
            with self.assertRaisesRegex(ContractError, "mysqldump failed"):
                self._run_backup(
                    client=admin,
                    output=output,
                    fake_mysql=fake_mysql,
                    fake_dump=failing_dump,
                )
            self.assertFalse(partial.exists())
            self.assertFalse((output / f"{RUN_ID}-{SOURCE_DATABASE}.sql").exists())

    def test_backup_rechecks_authority_and_removes_dump_when_manifest_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            admin = self._client_file(root, "admin.cnf", "synthetic_admin")
            fake_mysql = self._fake_mysql(root)
            fake_dump = self._fake_dump(root)
            output = root / "evidence"
            authority = {
                "version": "8.4.10",
                "server_uuid": SERVER_UUID,
                "environment_id": ENVIRONMENT_ID,
                "gtid_executed": "gtid:1-42",
                "binlog_format": "ROW",
            }
            changed = dict(authority, version="8.4.11")
            with patch(
                "scripts.release.mysql_backup._read_authority",
                side_effect=(authority, authority, changed),
            ):
                with self.assertRaisesRegex(ContractError, "changed during"):
                    self._run_backup(
                        client=admin,
                        output=output,
                        fake_mysql=fake_mysql,
                        fake_dump=fake_dump,
                    )
            self.assertFalse(
                (output / f"{RUN_ID}-{SOURCE_DATABASE}.sql").exists()
            )

            with patch(
                "scripts.release.mysql_backup.write_json_exclusive",
                side_effect=ContractError("synthetic manifest failure"),
            ):
                with self.assertRaisesRegex(ContractError, "manifest failure"):
                    self._run_backup(
                        client=admin,
                        output=output,
                        fake_mysql=fake_mysql,
                        fake_dump=fake_dump,
                    )
            self.assertFalse(
                (output / f"{RUN_ID}-{SOURCE_DATABASE}.sql").exists()
            )

    def test_restore_rejects_tampered_manifest_symlink_and_unsafe_grants(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            admin = self._client_file(root, "admin.cnf", "synthetic_admin")
            importer = self._client_file(root, "import.cnf", "synthetic_import")
            fake_mysql = self._fake_mysql(root)
            output = root / "evidence"
            backup = self._run_backup(
                client=admin,
                output=output,
                fake_mysql=fake_mysql,
                fake_dump=self._fake_dump(root),
            )
            manifest = output / f"{RUN_ID}-backup-manifest.json"
            dump = output / str(backup["dump_file"])
            dump_link = root / "dump-link.sql"
            dump_link.symlink_to(dump)
            with self.assertRaisesRegex(ContractError, "non-symlink"):
                self._run_restore_without_verification(
                    admin=admin,
                    importer=importer,
                    fake_mysql=fake_mysql,
                    manifest=manifest,
                    dump=dump_link,
                    dump_sha=str(backup["dump_sha256"]),
                )

            validate_restricted_grants(
                (
                    "GRANT USAGE ON *.* TO 'synthetic'@'localhost'\n"
                    "GRANT SELECT ON `shenzhou_hr_w9_restore`.* "
                    "TO 'synthetic'@'localhost'"
                ),
                target_database=TARGET_DATABASE,
                mode="read-only",
            )
            with self.assertRaisesRegex(ContractError, "outside"):
                validate_restricted_grants(
                    "GRANT SELECT ON `shenzhou_hr_prod`.* TO 'bad'@'localhost'",
                    target_database=TARGET_DATABASE,
                    mode="read-only",
                )
            with self.assertRaisesRegex(ContractError, "unsupported"):
                validate_restricted_grants(
                    (
                        "GRANT CREATE, INSERT, FILE ON "
                        "`shenzhou_hr_w9_restore`.* TO 'bad'@'localhost'"
                    ),
                    target_database=TARGET_DATABASE,
                    mode="import",
                )

    def test_restore_requires_read_only_account_with_verification_sql(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            admin = self._client_file(root, "admin.cnf", "synthetic_admin")
            importer = self._client_file(root, "import.cnf", "synthetic_import")
            fake_mysql = self._fake_mysql(root)
            output = root / "evidence"
            backup = self._run_backup(
                client=admin,
                output=output,
                fake_mysql=fake_mysql,
                fake_dump=self._fake_dump(root),
            )
            verification = root / "verify.sql"
            verification.write_text("SELECT 1;\n", encoding="utf-8")
            os.chmod(verification, 0o600)
            with self.assertRaisesRegex(ContractError, "required together"):
                run_restore(
                    repository_root=Path.cwd(),
                    run_id=RUN_ID,
                    target_database=TARGET_DATABASE,
                    confirmation=self._confirmation(str(backup["dump_sha256"])),
                    client_file=admin,
                    import_client_file=importer,
                    verification_client_file=None,
                    backup_manifest_path=output / f"{RUN_ID}-backup-manifest.json",
                    dump_path=output / str(backup["dump_file"]),
                    mysql_binary=str(fake_mysql),
                    verification_sql=verification,
                    verification_sql_sha256=hashlib.sha256(
                        verification.read_bytes()
                    ).hexdigest(),
                    expected_source_database=SOURCE_DATABASE,
                    expected_dump_sha256=str(backup["dump_sha256"]),
                    expected_backup_server_uuid=SERVER_UUID,
                    expected_backup_environment_id=ENVIRONMENT_ID,
                    expected_restore_server_uuid=SERVER_UUID,
                    expected_restore_environment_id=ENVIRONMENT_ID,
                )

    def test_restore_target_and_confirmation_are_checked_before_mysql(self) -> None:
        with self.assertRaisesRegex(ContractError, "restore"):
            validate_restore_target("shenzhou_hr_test")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            admin = self._client_file(root, "admin.cnf", "synthetic_admin")
            importer = self._client_file(root, "import.cnf", "synthetic_import")
            fake_mysql = self._fake_mysql(root)
            output = root / "evidence"
            backup = self._run_backup(
                client=admin,
                output=output,
                fake_mysql=fake_mysql,
                fake_dump=self._fake_dump(root),
            )
            with self.assertRaisesRegex(ContractError, "confirmation"):
                run_restore(
                    repository_root=Path.cwd(),
                    run_id=RUN_ID,
                    target_database=TARGET_DATABASE,
                    confirmation="wrong",
                    client_file=admin,
                    import_client_file=importer,
                    verification_client_file=None,
                    backup_manifest_path=output / f"{RUN_ID}-backup-manifest.json",
                    dump_path=output / str(backup["dump_file"]),
                    mysql_binary=str(fake_mysql),
                    verification_sql=None,
                    verification_sql_sha256=None,
                    expected_source_database=SOURCE_DATABASE,
                    expected_dump_sha256=str(backup["dump_sha256"]),
                    expected_backup_server_uuid=SERVER_UUID,
                    expected_backup_environment_id=ENVIRONMENT_ID,
                    expected_restore_server_uuid=SERVER_UUID,
                    expected_restore_environment_id=ENVIRONMENT_ID,
                )

    def test_restore_failure_reports_retained_sandbox(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            admin = self._client_file(root, "admin.cnf", "synthetic_admin")
            importer = self._client_file(root, "import.cnf", "synthetic_import")
            output = root / "evidence"
            backup = self._run_backup(
                client=admin,
                output=output,
                fake_mysql=self._fake_mysql(root),
                fake_dump=self._fake_dump(root),
            )
            with self.assertRaisesRegex(ContractError, "may remain"):
                self._run_restore_without_verification(
                    admin=admin,
                    importer=importer,
                    fake_mysql=self._fake_mysql(root, fail_import=True),
                    manifest=output / f"{RUN_ID}-backup-manifest.json",
                    dump=output / str(backup["dump_file"]),
                    dump_sha=str(backup["dump_sha256"]),
                )

    def _run_backup(
        self,
        *,
        client: Path,
        output: Path,
        fake_mysql: Path,
        fake_dump: Path,
    ) -> dict[str, object]:
        return run_backup(
            repository_root=Path.cwd(),
            run_id=RUN_ID,
            database=SOURCE_DATABASE,
            client_file=client,
            output_directory=output,
            mysql_binary=str(fake_mysql),
            mysqldump_binary=str(fake_dump),
            expected_server_uuid=SERVER_UUID,
            expected_environment_id=ENVIRONMENT_ID,
        )

    def _run_restore_without_verification(
        self,
        *,
        admin: Path,
        importer: Path,
        fake_mysql: Path,
        manifest: Path,
        dump: Path,
        dump_sha: str,
    ) -> dict[str, object]:
        return run_restore(
            repository_root=Path.cwd(),
            run_id=RUN_ID,
            target_database=TARGET_DATABASE,
            confirmation=self._confirmation(dump_sha),
            client_file=admin,
            import_client_file=importer,
            verification_client_file=None,
            backup_manifest_path=manifest,
            dump_path=dump,
            mysql_binary=str(fake_mysql),
            verification_sql=None,
            verification_sql_sha256=None,
            expected_source_database=SOURCE_DATABASE,
            expected_dump_sha256=dump_sha,
            expected_backup_server_uuid=SERVER_UUID,
            expected_backup_environment_id=ENVIRONMENT_ID,
            expected_restore_server_uuid=SERVER_UUID,
            expected_restore_environment_id=ENVIRONMENT_ID,
        )

    def _confirmation(self, dump_sha: str) -> str:
        return ":".join(
            (
                TARGET_DATABASE,
                RUN_ID,
                SERVER_UUID,
                ENVIRONMENT_ID,
                SERVER_UUID,
                ENVIRONMENT_ID,
                dump_sha,
            )
        )

    def _client_file(self, root: Path, name: str, user: str) -> Path:
        client = root / name
        client.write_text(
            "[client]\n"
            "host=127.0.0.1\n"
            "port=13306\n"
            f"user={user}\n"
            "password=synthetic-secret\n",
            encoding="utf-8",
        )
        os.chmod(client, 0o600)
        return client

    def _fake_mysql(self, root: Path, *, fail_import: bool = False) -> Path:
        path = root / ("fake-mysql-import-fail" if fail_import else "fake-mysql")
        state = root / "fake-restore-created"
        import_behavior = "exit 9" if fail_import else "cat >/dev/null || true"
        path.write_text(
            "#!/bin/sh\n"
            f"state='{state}'\n"
            "case \"$*\" in\n"
            "  *\"--protocol=TCP\"*\"--host=127.0.0.1\"*\"--port=13306\"*) : ;;\n"
            "  *) exit 77 ;;\n"
            "esac\n"
            "case \"$*\" in\n"
            f"  *\"gtid_executed\"*) printf '8.4.10\\t{SERVER_UUID}\\t"
            f"{ENVIRONMENT_ID}\\tgtid:1-42\\t1\\tROW\\n' ;;\n"
            f"  *\"SELECT VERSION()\"*) printf '8.4.10\\t{SERVER_UUID}\\t"
            f"{ENVIRONMENT_ID}\\n' ;;\n"
            f"  *\"SELECT @@server_uuid\"*) printf '{SERVER_UUID}\\n' ;;\n"
            "  *\"SHOW BINARY LOG STATUS\"*) "
            "printf 'binlog.000007\\t4242\\t\\t\\tgtid:1-42\\n' ;;\n"
            "  *\"import.cnf\"*\"SHOW GRANTS\"*) "
            "printf '%s\\n' "
            "\"GRANT USAGE ON *.* TO 'synthetic_import'@'localhost'\" "
            "\"GRANT CREATE, INSERT, ALTER, DROP, INDEX, SELECT, TRIGGER "
            "ON \\`shenzhou_hr_w9_restore\\`.* "
            "TO 'synthetic_import'@'localhost'\" ;;\n"
            "  *\"verify.cnf\"*\"SHOW GRANTS\"*) "
            "printf '%s\\n' "
            "\"GRANT USAGE ON *.* TO 'synthetic_verify'@'localhost'\" "
            "\"GRANT SELECT, SHOW VIEW ON \\`shenzhou_hr_w9_restore\\`.* "
            "TO 'synthetic_verify'@'localhost'\" ;;\n"
            "  *\"CREATE DATABASE\"*) : > \"$state\" ;;\n"
            "  *\"information_schema.schemata\"*) "
            "if [ -f \"$state\" ]; then printf '1\\n'; else printf '0\\n'; fi ;;\n"
            f"  *\"--database\"*) {import_behavior} ;;\n"
            "  *) : ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        os.chmod(path, 0o700)
        return path

    def _fake_dump(self, root: Path, *, fail: bool = False) -> Path:
        name = "fake-mysqldump-fail" if fail else "fake-mysqldump"
        path = root / name
        if fail:
            source = "#!/bin/sh\nprintf 'partial-sensitive-data\\n'\nexit 9\n"
        else:
            source = (
                "#!/bin/sh\n"
                "case \"$*\" in\n"
                "  *\"--protocol=TCP\"*\"--host=127.0.0.1\"*"
                "\"--port=13306\"*) : ;;\n"
                "  *) exit 77 ;;\n"
                "esac\n"
                "printf '%s\\n' 'CREATE TABLE synthetic(id BIGINT);'\n"
            )
        path.write_text(source, encoding="utf-8")
        os.chmod(path, 0o700)
        return path


if __name__ == "__main__":
    unittest.main()
