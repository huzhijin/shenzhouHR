from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

from scripts.release.common import ContractError
from scripts.release.mysql_backup import backup_plan, run_backup
from scripts.release.mysql_restore import restore_plan, run_restore
from scripts.release.mysql_safety import read_client_identity, validate_restore_target

RUN_ID = "w9-backup-test-001"


class MySqlBackupRestoreTest(unittest.TestCase):
    def test_default_plans_send_no_connections_and_keep_release_unverified(self) -> None:
        backup = backup_plan(RUN_ID, "shenzhou_hr_test")
        restore = restore_plan(RUN_ID, "shenzhou_hr_w9_restore")

        self.assertEqual(0, backup["network_connections"])
        self.assertEqual("NOT_VERIFIED", backup["rpo_gate_status"])
        self.assertEqual(0, restore["network_connections"])
        self.assertEqual("NOT_VERIFIED", restore["recovery_gate_status"])

    def test_client_file_requires_loopback_outside_repo_and_0600(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = self._client_file(root)
            self.assertEqual(("127.0.0.1", 13306), read_client_identity(client, Path.cwd()))
            os.chmod(client, 0o640)
            with self.assertRaisesRegex(ContractError, "0600"):
                read_client_identity(client, Path.cwd())

    def test_client_symlink_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = self._client_file(root)
            link = root / "client-link.cnf"
            link.symlink_to(client)
            with self.assertRaisesRegex(ContractError, "symlink"):
                read_client_identity(link, Path.cwd())

    def test_restore_target_and_confirmation_are_checked_before_mysql(self) -> None:
        with self.assertRaisesRegex(ContractError, "restore"):
            validate_restore_target("shenzhou_hr_test")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = self._client_file(root)
            fake_mysql = self._fake_mysql(root)
            manifest, dump = self._backup_artifact(root)
            with self.assertRaisesRegex(ContractError, "confirmation"):
                run_restore(
                    repository_root=Path.cwd(),
                    run_id=RUN_ID,
                    target_database="shenzhou_hr_w9_restore",
                    confirmation="wrong",
                    client_file=client,
                    backup_manifest_path=manifest,
                    dump_path=dump,
                    mysql_binary=str(fake_mysql),
                    verification_sql=None,
                )

    def test_fake_backup_and_restore_preserve_not_verified_release_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = self._client_file(root)
            fake_mysql = self._fake_mysql(root)
            fake_dump = self._fake_dump(root)
            output = root / "evidence"
            backup = run_backup(
                repository_root=Path.cwd(),
                run_id=RUN_ID,
                database="shenzhou_hr_test",
                client_file=client,
                output_directory=output,
                mysql_binary=str(fake_mysql),
                mysqldump_binary=str(fake_dump),
            )
            self.assertEqual("PASS", backup["status"])
            self.assertEqual("NOT_VERIFIED", backup["rpo_gate_status"])

            verification = root / "verify.sql"
            verification.write_text("SELECT 1;\n", encoding="utf-8")
            restore = run_restore(
                repository_root=Path.cwd(),
                run_id=RUN_ID,
                target_database="shenzhou_hr_w9_restore",
                confirmation=f"shenzhou_hr_w9_restore:{RUN_ID}",
                client_file=client,
                backup_manifest_path=output / f"{RUN_ID}-backup-manifest.json",
                dump_path=output / backup["dump_file"],
                mysql_binary=str(fake_mysql),
                verification_sql=verification,
            )
            self.assertEqual("PASS", restore["automated_restore_status"])
            self.assertEqual("NOT_VERIFIED", restore["recovery_gate_status"])
            self.assertFalse(restore["drop_executed"])

    def test_tampered_dump_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            client = self._client_file(root)
            fake_mysql = self._fake_mysql(root)
            manifest, dump = self._backup_artifact(root)
            dump.write_text("tampered\n", encoding="utf-8")
            with self.assertRaisesRegex(ContractError, "SHA-256"):
                run_restore(
                    repository_root=Path.cwd(),
                    run_id=RUN_ID,
                    target_database="shenzhou_hr_w9_restore",
                    confirmation=f"shenzhou_hr_w9_restore:{RUN_ID}",
                    client_file=client,
                    backup_manifest_path=manifest,
                    dump_path=dump,
                    mysql_binary=str(fake_mysql),
                    verification_sql=None,
                )

    def _client_file(self, root: Path) -> Path:
        client = root / "client.cnf"
        client.write_text(
            "[client]\n"
            "host=127.0.0.1\n"
            "port=13306\n"
            "user=synthetic_migrator\n"
            "password=synthetic-secret\n",
            encoding="utf-8",
        )
        os.chmod(client, 0o600)
        return client

    def _fake_mysql(self, root: Path) -> Path:
        path = root / "fake-mysql"
        path.write_text(
            "#!/bin/sh\n"
            "case \"$*\" in\n"
            "  *\"SELECT VERSION()\"*) printf '8.4.10\\tsynthetic-uuid\\tgtid:1-42\\t1\\tROW\\n' ;;\n"
            "  *\"SHOW BINARY LOG STATUS\"*) printf 'binlog.000007\\t4242\\t\\t\\tgtid:1-42\\n' ;;\n"
            "  *\"information_schema.schemata\"*) printf '0\\n' ;;\n"
            "  *\"--database\"*) cat >/dev/null || true ;;\n"
            "  *) : ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        os.chmod(path, 0o700)
        return path

    def _fake_dump(self, root: Path) -> Path:
        path = root / "fake-mysqldump"
        path.write_text(
            "#!/bin/sh\nprintf '%s\\n' 'CREATE TABLE synthetic(id BIGINT);'\n",
            encoding="utf-8",
        )
        os.chmod(path, 0o700)
        return path

    def _backup_artifact(self, root: Path) -> tuple[Path, Path]:
        import hashlib

        dump = root / "backup.sql"
        dump.write_text("SELECT 1;\n", encoding="utf-8")
        manifest = root / "manifest.json"
        manifest.write_text(
            json.dumps(
                {
                    "schema_version": "shenzhouhr.w9.mysql-backup/v1",
                    "run_id": RUN_ID,
                    "dump_sha256": hashlib.sha256(dump.read_bytes()).hexdigest(),
                }
            ),
            encoding="utf-8",
        )
        return manifest, dump
