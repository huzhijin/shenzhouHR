#!/usr/bin/env python3
"""
Tests for register-deployment.py

覆盖真实register-deployment.py入口的临时文件测试。
"""

import json
import os
import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timezone
import threading
import importlib.util
import hashlib


spec = importlib.util.spec_from_file_location(
    "register_deployment",
    Path(__file__).parent / "register-deployment.py"
)
rd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rd)


class TestRegisterDeployment(unittest.TestCase):

    def setUp(self):
        """Create temporary directory for each test."""
        self.test_dir = tempfile.mkdtemp()
        self.test_path = Path(self.test_dir)
        self.history_path = self.test_path / "deployment-history.json"
        self.history_path.write_text('{"deployments":[]}')

    def tearDown(self):
        """Clean up temporary directory."""
        import shutil
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def create_mock_release(self, version="v1.0.0", jar_sha=None, include_web=True):
        """创建mock release目录结构"""
        release_dir = self.test_path / f"release-{version}"
        release_dir.mkdir(parents=True)

        backend_dir = release_dir / "backend"
        backend_dir.mkdir()

        if jar_sha is None:
            jar_sha = hashlib.sha256(f"mock-jar-{version}".encode()).hexdigest()

        jar_path = backend_dir / "shenzhou-hr.jar"
        jar_content = f"mock-jar-{version}-{jar_sha[:8]}".encode()
        jar_path.write_bytes(jar_content)
        actual_jar_sha = hashlib.sha256(jar_content).hexdigest()

        manifest = {
            "releaseName": version,
            "backend_jar": "backend/shenzhou-hr.jar",
            "frontend_dir": "web",
            "jarSha256": actual_jar_sha
        }
        (release_dir / "BUILD-MANIFEST.json").write_text(json.dumps(manifest, indent=2))

        sha256sums_lines = [
            f"{actual_jar_sha}  backend/shenzhou-hr.jar",
        ]

        manifest_sha = hashlib.sha256(
            json.dumps(manifest, indent=2).encode()
        ).hexdigest()
        sha256sums_lines.append(f"{manifest_sha}  BUILD-MANIFEST.json")

        frontend_files = []
        if include_web:
            web_dir = release_dir / "web"
            web_dir.mkdir()

            index_content = f"<html>{version}</html>".encode()
            index_path = web_dir / "index.html"
            index_path.write_bytes(index_content)
            index_sha = hashlib.sha256(index_content).hexdigest()
            sha256sums_lines.append(f"{index_sha}  web/index.html")
            frontend_files.append({"path": "index.html", "sha256": index_sha})

        (release_dir / "SHA256SUMS").write_text('\n'.join(sha256sums_lines) + '\n')

        return release_dir, actual_jar_sha, frontend_files

    def create_test_receipt(self, release_dir, version, jar_sha, installed_jar,
                           installed_web=None, timestamp=None):
        """创建测试receipt"""
        if timestamp is None:
            timestamp = datetime.now(timezone.utc).isoformat()

        receipt = {
            "releaseName": version,
            "jarSha256": jar_sha,
            "installedAtUtc": timestamp,
            "pid": os.getpid(),
            "base": "http://localhost:8080",
            "backupDirectory": str(self.test_path / "backup"),
            "web": []
        }

        if installed_web:
            receipt["web"] = [{"root": str(installed_web), "backup": "/tmp/backup.tar.gz"}]

        receipt_path = self.test_path / f"receipt-{version.replace('.', '-')}.json"
        receipt_path.write_text(json.dumps(receipt, indent=2))
        return receipt_path

    def create_installed_artifacts(self, release_dir, jar_sha, frontend_files, suffix=""):
        """创建已安装的artifacts"""
        installed_dir = self.test_path / f"installed{suffix}"
        installed_dir.mkdir(parents=True, exist_ok=True)

        jar_path = installed_dir / "shenzhou-hr.jar"
        jar_content = (release_dir / "backend" / "shenzhou-hr.jar").read_bytes()
        jar_path.write_bytes(jar_content)

        web_path = None
        if frontend_files:
            web_path = installed_dir / "web"
            web_path.mkdir(exist_ok=True)
            for f in frontend_files:
                src = release_dir / "web" / f['path']
                dst = web_path / f['path']
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(src.read_bytes())

        return jar_path, web_path

    def test_successful_txt_package_registration(self):
        """测试常规txt包成功注册"""
        release_dir, jar_sha, frontend_files = self.create_mock_release("v1.0.0")
        installed_jar, installed_web = self.create_installed_artifacts(
            release_dir, jar_sha, frontend_files
        )

        success = rd.register_deployment(
            release_root=release_dir,
            title="Test Release",
            description="Test description",
            details="- Fix A\n- Fix B",
            history_path=self.history_path,
            installed_jar=installed_jar,
            installed_web=installed_web,
            installed_at="2026-10-12T10:00:00+08:00",
            verified_at="2026-10-12T10:05:00+08:00",
            runtime_verify=False
        )

        self.assertTrue(success)
        history = rd.load_history_locked(self.history_path)
        self.assertEqual(len(history["deployments"]), 1)
        self.assertEqual(history["deployments"][0]["version"], "v1.0.0")
        self.assertEqual(history["deployments"][0]["jarSha256"], jar_sha)

    def test_receipt_web_dict_format(self):
        """测试receipt web字典格式成功处理"""
        release_dir, jar_sha, frontend_files = self.create_mock_release("v1.0.1")
        installed_jar, installed_web = self.create_installed_artifacts(
            release_dir, jar_sha, frontend_files
        )

        receipt_path = self.create_test_receipt(
            release_dir, "v1.0.1", jar_sha, installed_jar, installed_web
        )

        success = rd.register_deployment(
            release_root=release_dir,
            title="Test Web Dict",
            description="",
            details="",
            history_path=self.history_path,
            receipt_path=receipt_path,
            installed_jar=installed_jar,
            installed_web=installed_web,
            installed_at="2026-10-12T10:10:00+08:00",
            verified_at="2026-10-12T10:15:00+08:00",
            runtime_verify=False
        )

        self.assertTrue(success)

    def test_jar_hash_mismatch_rejected(self):
        """测试jar hash不符被拒绝"""
        release_dir, jar_sha, frontend_files = self.create_mock_release("v1.0.2")
        installed_jar, installed_web = self.create_installed_artifacts(
            release_dir, jar_sha, frontend_files
        )

        installed_jar.write_bytes(b"corrupted-jar-content")

        success = rd.register_deployment(
            release_root=release_dir,
            title="Corrupted Jar",
            description="",
            details="",
            history_path=self.history_path,
            installed_jar=installed_jar,
            installed_web=installed_web,
            installed_at="2026-10-12T11:00:00+08:00",
            verified_at="2026-10-12T11:05:00+08:00",
            runtime_verify=False
        )

        self.assertFalse(success)

    def test_missing_web_index_rejected(self):
        """测试缺少web/index.html被拒绝"""
        release_dir, jar_sha, _ = self.create_mock_release("v1.0.3", include_web=True)

        # 删除SHA256SUMS中的web/index.html行，模拟包缺少该文件
        sha256sums = (release_dir / "SHA256SUMS").read_text()
        lines = [line for line in sha256sums.split('\n') if 'web/index.html' not in line]
        (release_dir / "SHA256SUMS").write_text('\n'.join(lines))

        installed_jar, _ = self.create_installed_artifacts(release_dir, jar_sha, [])

        success = rd.register_deployment(
            release_root=release_dir,
            title="Missing Web",
            description="",
            details="",
            history_path=self.history_path,
            installed_jar=installed_jar,
            installed_at="2026-10-12T12:00:00+08:00",
            verified_at="2026-10-12T12:05:00+08:00",
            runtime_verify=False
        )

        self.assertFalse(success)

    def test_manifest_checksum_mismatch_rejected(self):
        """测试manifest checksum不符被拒绝"""
        release_dir, jar_sha, frontend_files = self.create_mock_release("v1.0.4")

        sha256sums = (release_dir / "SHA256SUMS").read_text()
        corrupted = sha256sums.replace("BUILD-MANIFEST.json", "BUILD-MANIFEST.json  ")
        (release_dir / "SHA256SUMS").write_text(corrupted.replace(
            sha256sums.split('\n')[1].split()[0], "0" * 64
        ))

        installed_jar, installed_web = self.create_installed_artifacts(
            release_dir, jar_sha, frontend_files
        )

        success = rd.register_deployment(
            release_root=release_dir,
            title="Bad Manifest",
            description="",
            details="",
            history_path=self.history_path,
            installed_jar=installed_jar,
            installed_web=installed_web,
            installed_at="2026-10-12T13:00:00+08:00",
            verified_at="2026-10-12T13:05:00+08:00",
            runtime_verify=False
        )

        self.assertFalse(success)

    def test_same_instant_different_offset_idempotent(self):
        """测试同一instant不同offset幂等"""
        release_dir, jar_sha, frontend_files = self.create_mock_release("v1.0.5")
        installed_jar, installed_web = self.create_installed_artifacts(
            release_dir, jar_sha, frontend_files
        )

        success1 = rd.register_deployment(
            release_root=release_dir,
            title="Deploy 1",
            description="",
            details="",
            history_path=self.history_path,
            installed_jar=installed_jar,
            installed_web=installed_web,
            installed_at="2026-10-12T14:00:00+08:00",
            verified_at="2026-10-12T14:05:00+08:00",
            runtime_verify=False
        )

        success2 = rd.register_deployment(
            release_root=release_dir,
            title="Deploy 1",
            description="",
            details="",
            history_path=self.history_path,
            installed_jar=installed_jar,
            installed_web=installed_web,
            installed_at="2026-10-12T06:00:00+00:00",
            verified_at="2026-10-12T06:05:00+00:00",
            runtime_verify=False
        )

        self.assertTrue(success1)
        self.assertTrue(success2)
        history = rd.load_history_locked(self.history_path)
        self.assertEqual(len(history["deployments"]), 1)

    def test_same_event_different_artifact_rejected(self):
        """测试同事件不同artifact被拒绝"""
        release_dir1, jar_sha1, ff1 = self.create_mock_release("v1.0.6")
        installed_jar1, installed_web1 = self.create_installed_artifacts(
            release_dir1, jar_sha1, ff1, suffix="-1"
        )

        rd.register_deployment(
            release_root=release_dir1,
            title="Deploy A",
            description="",
            details="",
            history_path=self.history_path,
            installed_jar=installed_jar1,
            installed_web=installed_web1,
            installed_at="2026-10-12T15:00:00+08:00",
            verified_at="2026-10-12T15:05:00+08:00",
            runtime_verify=False
        )

        release_dir2, jar_sha2, ff2 = self.create_mock_release("v1.0.7")
        installed_jar2, installed_web2 = self.create_installed_artifacts(
            release_dir2, jar_sha2, ff2, suffix="-2"
        )

        success = rd.register_deployment(
            release_root=release_dir2,
            title="Deploy B",
            description="",
            details="",
            history_path=self.history_path,
            installed_jar=installed_jar2,
            installed_web=installed_web2,
            installed_at="2026-10-12T15:00:00+08:00",
            verified_at="2026-10-12T15:05:00+08:00",
            runtime_verify=False
        )

        self.assertFalse(success)

    def test_concurrent_first_write_safety(self):
        """测试并发首次写入安全性"""
        def register_in_thread(idx):
            release_dir, jar_sha, ff = self.create_mock_release(f"v1.0.{idx}")
            installed_jar, installed_web = self.create_installed_artifacts(
                release_dir, jar_sha, ff, suffix=f"-{idx}"
            )

            rd.register_deployment(
                release_root=release_dir,
                title=f"Deploy {idx}",
                description="",
                details="",
                history_path=self.history_path,
                installed_jar=installed_jar,
                installed_web=installed_web,
                installed_at=f"2026-10-12T16:0{idx}:00+08:00",
                verified_at=f"2026-10-12T16:0{idx}:05+08:00",
                runtime_verify=False
            )

        threads = []
        for i in range(5):
            t = threading.Thread(target=register_in_thread, args=(i,))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        history = rd.load_history_locked(self.history_path)
        self.assertEqual(len(history["deployments"]), 5)

    def test_corrupted_history_preserved(self):
        """测试坏history原样保留"""
        self.history_path.write_text("{invalid json")

        release_dir, jar_sha, ff = self.create_mock_release("v1.0.8")
        installed_jar, installed_web = self.create_installed_artifacts(
            release_dir, jar_sha, ff
        )

        success = rd.register_deployment(
            release_root=release_dir,
            title="Test",
            description="",
            details="",
            history_path=self.history_path,
            installed_jar=installed_jar,
            installed_web=installed_web,
            installed_at="2026-10-12T17:00:00+08:00",
            verified_at="2026-10-12T17:05:00+08:00",
            runtime_verify=False
        )

        self.assertFalse(success)
        self.assertEqual(self.history_path.read_text(), "{invalid json")


if __name__ == '__main__':
    unittest.main()
