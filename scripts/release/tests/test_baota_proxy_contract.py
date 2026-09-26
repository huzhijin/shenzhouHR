from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[3]
HELPER_PATH = REPOSITORY / "deploy/baota/scripts/baota-proxy.py"
SPEC = importlib.util.spec_from_file_location("baota_proxy", HELPER_PATH)
assert SPEC is not None and SPEC.loader is not None
baota_proxy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(baota_proxy)


class BaotaProxyContractTest(unittest.TestCase):
    site = "192.168.160.226"
    proxy_name = "kaoqin-api"
    target = "http://127.0.0.1:8080/api"

    def _fixture(self, root: Path, record: dict | None = None) -> tuple[Path, Path, Path]:
        metadata = root / "proxyfile.json"
        proxy_root = root / "proxy"
        site_dir = proxy_root / self.site
        site_dir.mkdir(parents=True, mode=0o700)
        record = record or {
            "proxyname": self.proxy_name,
            "sitename": self.site,
            "proxydir": "/api",
            "proxysite": self.target,
            "todomain": "$host",
            "type": 1,
            "cache": 0,
            "subfilter": [
                {"sub1": "", "sub2": ""},
                {"sub1": "", "sub2": ""},
            ],
            "advanced": 1,
            "cachetime": 1,
        }
        metadata.write_text(json.dumps([record]), encoding="utf-8")
        metadata.chmod(0o600)
        digest = hashlib.md5(self.proxy_name.encode("utf-8")).hexdigest()
        config = site_dir / f"{digest}_{self.site}.conf"
        config.write_text(
            "# SHENZHOUHR-BAOTA-PROXY-V1\n"
            "location ^~ /api/ {\n"
            "proxy_pass http://127.0.0.1:8080/api/;\n"
            "}\n",
            encoding="utf-8",
        )
        config.chmod(0o600)
        return metadata, proxy_root, config

    def _resolve(
        self,
        metadata: Path,
        proxy_root: Path,
        *,
        require_marker: bool = False,
    ) -> Path:
        return baota_proxy.registered_proxy_path(
            metadata,
            proxy_root,
            self.site,
            self.proxy_name,
            self.target,
            require_marker,
            enforce_root_owner=False,
        )

    def test_accepts_the_one_exact_panel_record_and_managed_rule(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            metadata, proxy_root, config = self._fixture(Path(directory))

            self.assertEqual(
                config,
                self._resolve(metadata, proxy_root, require_marker=True),
            )

    def test_rejects_wrong_or_unsafe_panel_metadata(self) -> None:
        mutations = (
            ("proxyname", "other"),
            ("sitename", "other-site"),
            ("proxydir", "/"),
            ("proxysite", "http://127.0.0.1:8080"),
            ("todomain", "127.0.0.1"),
            ("type", 0),
            ("cache", 1),
            ("advanced", 0),
            ("subfilter", [{"sub1": "secret", "sub2": "replacement"}]),
        )
        for key, value in mutations:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                metadata, proxy_root, _ = self._fixture(root)
                records = json.loads(metadata.read_text(encoding="utf-8"))
                records[0][key] = value
                metadata.write_text(json.dumps(records), encoding="utf-8")
                metadata.chmod(0o600)

                with self.assertRaises(baota_proxy.ContractError):
                    self._resolve(metadata, proxy_root)

    def test_rejects_duplicate_records_or_extra_active_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            metadata, proxy_root, config = self._fixture(Path(directory))
            records = json.loads(metadata.read_text(encoding="utf-8"))
            metadata.write_text(json.dumps(records + records), encoding="utf-8")
            metadata.chmod(0o600)
            with self.assertRaisesRegex(baota_proxy.ContractError, "exactly one"):
                self._resolve(metadata, proxy_root)

            metadata.write_text(json.dumps(records), encoding="utf-8")
            metadata.chmod(0o600)
            extra = config.parent / "stale.conf"
            extra.write_text("server {}", encoding="utf-8")
            extra.chmod(0o600)
            with self.assertRaisesRegex(baota_proxy.ContractError, "only the registered"):
                self._resolve(metadata, proxy_root)

    def test_rejects_symlink_and_missing_managed_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            metadata, proxy_root, config = self._fixture(Path(directory))
            config.write_text("location /api/ {}", encoding="utf-8")
            config.chmod(0o600)
            with self.assertRaisesRegex(baota_proxy.ContractError, "not the managed"):
                self._resolve(metadata, proxy_root, require_marker=True)

            target = config.parent / "target"
            config.rename(target)
            os.symlink(target.name, config)
            with self.assertRaisesRegex(baota_proxy.ContractError, "real regular file"):
                self._resolve(metadata, proxy_root)


if __name__ == "__main__":
    unittest.main()
