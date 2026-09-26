from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[3]
SPA_ROUTING_HELPER = REPOSITORY / "deploy/baota/scripts/spa-routing.py"
SPA_ROUTING_REPAIR = REPOSITORY / ("deploy/baota/scripts/repair-spa-routing.sh")
NGINX_TEMPLATES = (
    REPOSITORY / "deploy/baota/nginx/shenzhouhr-site-http.conf",
    REPOSITORY / "deploy/baota/nginx/shenzhouhr-site.conf",
)
VERIFY = REPOSITORY / "deploy/baota/scripts/verify.sh"


class BaotaSpaRoutingTest(unittest.TestCase):
    def test_release_templates_and_verify_require_spa_history_fallback(self) -> None:
        for template in NGINX_TEMPLATES:
            with self.subTest(template=template):
                text = template.read_text(encoding="utf-8")
                self.assertEqual(1, text.count("try_files $uri $uri/ /index.html;"))
                self.assertIn("location /assets/ {", text)
                self.assertIn("try_files $uri =404;", text)

        verify = VERIFY.read_text(encoding="utf-8")
        self.assertIn('"http://127.0.0.1:$CUSTOMER_SITE_PORT/workbench"', verify)
        self.assertIn('[[ "$SPA_ROUTE_STATUS" == "200" ]]', verify)

    def test_helper_repairs_only_known_history_fallbacks(self) -> None:
        variants = (
            ("        etag off;\n", "missing"),
            ("        try_files $uri =404;\n", "single-uri-404"),
            ("        try_files $uri $uri/ =404;\n", "directory-404"),
        )
        for root_location_body, label in variants:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / "customer.conf"
                candidate = root / "candidate.conf"
                source.write_text(
                    self._customer_vhost(root_location_body), encoding="utf-8"
                )

                result = self._run_helper(source, candidate)

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("changed", result.stdout.strip())
                repaired = candidate.read_text(encoding="utf-8")
                self.assertEqual(1, repaired.count("try_files $uri $uri/ /index.html;"))
                self.assertIn("etag off;", repaired)

    def test_helper_is_idempotent_and_refuses_unknown_sites(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "customer.conf"
            candidate = root / "candidate.conf"
            canonical = self._customer_vhost(
                "        try_files $uri $uri/ /index.html;\n"
            )
            source.write_text(canonical, encoding="utf-8")

            unchanged = self._run_helper(source, candidate)

            self.assertEqual(0, unchanged.returncode, unchanged.stderr)
            self.assertEqual("unchanged", unchanged.stdout.strip())
            self.assertEqual(canonical, candidate.read_text(encoding="utf-8"))

        unsafe_variants = (
            self._customer_vhost("        proxy_pass http://127.0.0.1:8080;\n"),
            self._customer_vhost("        try_files $uri /index.php?$query_string;\n"),
            self._customer_vhost("        etag off;\n").replace(
                "root /www/wwwroot/192.168.160.226;",
                "root /www/wwwroot/another-site;",
            ),
        )
        labels = ("root-proxy", "unknown-try-files", "wrong-root")
        for unsafe, label in zip(unsafe_variants, labels):
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / "customer.conf"
                candidate = root / "candidate.conf"
                source.write_text(unsafe, encoding="utf-8")

                refused = self._run_helper(source, candidate)

                self.assertNotEqual(0, refused.returncode)
                self.assertIn("refusing", refused.stderr.lower())
                self.assertFalse(candidate.exists())

    def test_repair_backs_up_tests_reloads_and_verifies_routes(self) -> None:
        repair = SPA_ROUTING_REPAIR.read_text(encoding="utf-8")

        self.assertIn('readonly CUSTOMER_SITE_PORT="23272"', repair)
        self.assertIn(
            'readonly VHOST_CONFIG="/www/server/panel/vhost/nginx/192.168.160.226.conf"',
            repair,
        )
        self.assertIn('MODE="check"', repair)
        self.assertIn('BACKUP_DIR="$BACKUP_ROOT/nginx-spa-$STAMP"', repair)
        self.assertIn('cp -a -- "$VHOST_CONFIG" "$BACKUP_FILE"', repair)
        self.assertIn("VHOST_REPLACED=1", repair)
        self.assertIn("cleanup_or_rollback", repair)
        self.assertIn('-c "$BAOTA_NGINX_CONFIG" -t', repair)
        self.assertIn('-c "$BAOTA_NGINX_CONFIG" -s reload', repair)
        self.assertIn("for attempt in 1 2 3 4 5", repair)
        self.assertIn("%{http_code}\\t%{content_type}", repair)
        self.assertIn("/assets/__shenzhouhr_missing_probe__.js", repair)
        self.assertIn("/api/v1/auth/session", repair)
        self.assertIn('&& "$route_type" == text/html*', repair)
        self.assertIn('&& "$asset_status" == "404"', repair)
        self.assertIn('&& "$api_status" == "401"', repair)
        self.assertNotIn("listen 23273", repair)
        self.assertNotIn("sed -i", repair)

    def _run_helper(
        self, source: Path, candidate: Path
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "python3",
                str(SPA_ROUTING_HELPER),
                "--vhost-file",
                str(source),
                "--candidate-file",
                str(candidate),
            ],
            cwd=REPOSITORY,
            capture_output=True,
            text=True,
            check=False,
        )

    def _customer_vhost(self, root_location_body: str) -> str:
        return (
            "server {\n"
            "    listen 23272;\n"
            "    server_name 192.168.160.226;\n"
            "    root /www/wwwroot/192.168.160.226;\n"
            "    include /www/server/panel/vhost/nginx/proxy/"
            "192.168.160.226/*.conf;\n"
            "    location /assets/ {\n"
            "        try_files $uri =404;\n"
            "    }\n"
            "    location / {\n"
            f"{root_location_body}"
            "        etag off;\n"
            "    }\n"
            "}\n"
        )


if __name__ == "__main__":
    unittest.main()
