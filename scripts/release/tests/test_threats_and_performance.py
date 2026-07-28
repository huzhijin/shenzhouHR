from __future__ import annotations

import copy
import json
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from scripts.release.common import ContractError
from scripts.release.http_load import (
    capacity_status,
    run_scenario,
    validate_scenario_contract,
)
from scripts.release.verify_threats import build_report, validate_threat_matrix

COMMIT = "b" * 40


class _Handler(BaseHTTPRequestHandler):
    delay_seconds = 0.0
    status_code = 200

    def do_GET(self) -> None:  # noqa: N802
        time.sleep(self.delay_seconds)
        self.send_response(self.status_code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"synthetic":true}')

    def log_message(self, format: str, *args: object) -> None:
        return


class ContractFixture(unittest.TestCase):
    def test_threat_matrix_requires_all_categories(self) -> None:
        matrix = json.loads(
            (
                Path("scripts/release/contracts/threat-matrix-v1.json")
            ).read_text(encoding="utf-8")
        )
        validate_threat_matrix(matrix)
        matrix["cases"] = matrix["cases"][:-1]
        with self.assertRaisesRegex(ContractError, "categories differ"):
            validate_threat_matrix(matrix)

    def test_static_pass_still_leaves_dynamic_not_verified(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self._write_static_fixture(repository)
            report = build_report(
                repository,
                run_id="w9-test-0002",
                commit=COMMIT,
                environment_id="synthetic:unit-test",
                matrix_path=Path("scripts/release/contracts/threat-matrix-v1.json"),
            )

            self.assertEqual("PASS", report["static_status"])
            self.assertEqual("NOT_VERIFIED", report["dynamic_status"])
            self.assertEqual("NOT_VERIFIED", report["status"])
            self.assertFalse(report["release_authorized"])

    def test_static_failure_propagates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            self._write_static_fixture(repository)
            (repository / ".env.example").write_text(
                "JWT_SECRET=change-me\n", encoding="utf-8"
            )
            report = build_report(
                repository,
                run_id="w9-test-0003",
                commit=COMMIT,
                environment_id="synthetic:unit-test",
                matrix_path=Path("scripts/release/contracts/threat-matrix-v1.json"),
            )
            self.assertEqual("FAIL", report["static_status"])
            self.assertEqual("FAIL", report["status"])

    def _write_static_fixture(self, root: Path) -> None:
        files = {
            "deploy/nginx/shenzhouhr.conf": "\n".join(
                (
                    "listen 443 ssl http2;",
                    "server_tokens off;",
                    "Strict-Transport-Security",
                    "Content-Security-Policy",
                    "X-Frame-Options",
                    'set $shenzhouhr_cache_control "no-store, max-age=0";',
                    "proxy_hide_header Cache-Control;",
                    "etag off;",
                    "limit_req zone=shenzhouhr_api burst=40 nodelay;",
                    "limit_conn shenzhouhr_per_ip 20;",
                    "client_max_body_size 21m;",
                )
            ),
            "deploy/nginx/shenzhouhr-http.conf": (
                "limit_req_zone x;\nlimit_conn_zone x;\n"
                'log_format shenzhouhr_safe "$request_method $uri";\n'
            ),
            "deploy/systemd/shenzhouhr.service": "\n".join(
                (
                    "User=shenzhouhr",
                    "NoNewPrivileges=true",
                    "ProtectSystem=strict",
                    "ProtectHome=true",
                    "RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6",
                    "CapabilityBoundingSet=",
                    "SystemCallFilter=@system-service",
                    "StateDirectory=shenzhouhr",
                )
            ),
            "backend/src/main/resources/application.yml": (
                "development-principal:\n    enabled: false\n"
                "include-message: never\ninclude-stacktrace: never\n"
            ),
            "frontend/src/main.tsx": (
                "import { BrowserRouter } from 'react-router-dom';\n"
            ),
            "frontend/vite.config.ts": "export default {};\n",
            ".env.example": (
                "SHENZHOUHR_DB_URL=\nSHENZHOUHR_DB_USERNAME=\n"
                "SHENZHOUHR_DB_PASSWORD=\n"
            ),
        }
        for relative, content in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")


class PerformanceHarnessTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        _Handler.delay_seconds = 0.0
        _Handler.status_code = 200

    def test_contract_and_missing_capacity(self) -> None:
        contract = json.loads(
            Path(
                "scripts/release/contracts/performance-scenarios-v1.json"
            ).read_text(encoding="utf-8")
        )
        validate_scenario_contract(contract)
        status, missing = capacity_status(contract["capacity"])
        self.assertEqual("NOT_VERIFIED", status)
        self.assertIn("mysql_version", missing)

    def test_successful_loopback_measurement_remains_release_not_verified(self) -> None:
        result = run_scenario(self._scenario(), self.base_url)
        self.assertEqual("PASS", result["measurement_status"])
        self.assertEqual("NOT_VERIFIED", result["release_gate_status"])
        self.assertEqual(12, result["samples"])

    def test_slow_response_fails_threshold(self) -> None:
        _Handler.delay_seconds = 0.02
        scenario = self._scenario()
        scenario["p95_limit_ms"] = 1
        result = run_scenario(scenario, self.base_url)
        self.assertEqual("FAIL", result["measurement_status"])

    def test_non_2xx_fails_success_rate(self) -> None:
        _Handler.status_code = 503
        result = run_scenario(self._scenario(), self.base_url)
        self.assertEqual("FAIL", result["measurement_status"])
        self.assertEqual({"503": 12}, result["status_counts"])

    def test_no_samples_is_not_verified(self) -> None:
        scenario = self._scenario()
        scenario["total_requests"] = 0
        scenario["concurrency"] = 0
        result = run_scenario(scenario, self.base_url)
        self.assertEqual("NOT_VERIFIED", result["measurement_status"])

    def test_non_loopback_is_rejected_before_request(self) -> None:
        with self.assertRaisesRegex(ContractError, "loopback"):
            run_scenario(self._scenario(), "https://example.com")

    def _scenario(self) -> dict[str, object]:
        contract = json.loads(
            Path(
                "scripts/release/contracts/performance-scenarios-v1.json"
            ).read_text(encoding="utf-8")
        )
        scenario = copy.deepcopy(contract["scenarios"][0])
        scenario["path"] = "/synthetic"
        scenario["concurrency"] = 4
        scenario["total_requests"] = 12
        scenario["p95_limit_ms"] = 1000
        return scenario
