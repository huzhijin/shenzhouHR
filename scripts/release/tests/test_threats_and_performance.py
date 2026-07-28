from __future__ import annotations

import copy
import json
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from scripts.release import http_load
from scripts.release.common import ContractError
from scripts.release.http_load import (
    MAX_CONCURRENCY,
    MAX_RESPONSE_BODY_BYTES,
    MAX_TIMEOUT_SECONDS,
    MAX_TOTAL_REQUESTS,
    capacity_status,
    read_auth_header_file,
    run_scenario,
    validate_scenario,
    validate_scenario_contract,
)
from scripts.release.verify_threats import build_report, validate_threat_matrix

COMMIT = "b" * 40


class _Handler(BaseHTTPRequestHandler):
    delay_seconds = 0.0
    status_code = 200
    redirect_location = ""
    redirect_target_hits = 0

    def do_GET(self) -> None:  # noqa: N802
        time.sleep(self.delay_seconds)
        if self.headers.get("Cookie") != "session=synthetic-token":
            self.send_response(401)
            self.end_headers()
            self.wfile.write(b'{"error":"unauthorized"}')
            return
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", self.redirect_location)
            self.end_headers()
            self.wfile.write(b"redirect")
            return
        if self.path == "/redirect-target":
            type(self).redirect_target_hits += 1
        self.send_response(self.status_code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        report_type = (
            "ATTENDANCE_RATE"
            if "reportType=ATTENDANCE_RATE" in self.path
            else "ATTENDANCE_DETAIL"
        )
        size = 1 if "size=1" in self.path else 50
        self.wfile.write(
            json.dumps(
                {
                    "kind": "REPORT",
                    "reportType": report_type,
                    "rows": [],
                    "page": 0,
                    "size": size,
                }
            ).encode("utf-8")
        )

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
        self.auth_headers = {"Cookie": "session=synthetic-token"}

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        _Handler.delay_seconds = 0.0
        _Handler.status_code = 200
        _Handler.redirect_location = ""
        _Handler.redirect_target_hits = 0

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
        result = run_scenario(
            self._scenario(),
            self.base_url,
            self.auth_headers,
        )
        self.assertEqual("PASS", result["measurement_status"])
        self.assertEqual("NOT_VERIFIED", result["release_gate_status"])
        self.assertEqual(12, result["samples"])
        self.assertNotIn("synthetic-token", json.dumps(result))

    def test_slow_response_fails_threshold(self) -> None:
        _Handler.delay_seconds = 0.02
        scenario = self._scenario()
        scenario["p95_limit_ms"] = 1
        result = run_scenario(scenario, self.base_url, self.auth_headers)
        self.assertEqual("FAIL", result["measurement_status"])

    def test_non_2xx_fails_success_rate(self) -> None:
        _Handler.status_code = 503
        result = run_scenario(
            self._scenario(),
            self.base_url,
            self.auth_headers,
        )
        self.assertEqual("FAIL", result["measurement_status"])
        self.assertEqual({"503": 12}, result["status_counts"])

    def test_zero_request_or_concurrency_is_rejected(self) -> None:
        scenario = self._scenario()
        scenario["total_requests"] = 0
        with self.assertRaisesRegex(ContractError, "total_requests"):
            run_scenario(scenario, self.base_url, self.auth_headers)
        scenario = self._scenario()
        scenario["concurrency"] = 0
        with self.assertRaisesRegex(ContractError, "concurrency"):
            run_scenario(scenario, self.base_url, self.auth_headers)

    def test_non_loopback_is_rejected_before_request(self) -> None:
        with self.assertRaisesRegex(ContractError, "loopback"):
            run_scenario(
                self._scenario(),
                "https://example.com",
                self.auth_headers,
            )

    def test_base_url_must_be_origin_only(self) -> None:
        for suffix in ("/", "/prefix", "?mode=test", "#fragment"):
            with self.subTest(suffix=suffix):
                with self.assertRaisesRegex(ContractError, "only an origin"):
                    run_scenario(
                        self._scenario(),
                        self.base_url + suffix,
                        self.auth_headers,
                    )

    def test_redirect_is_recorded_but_never_followed(self) -> None:
        _Handler.redirect_location = f"{self.base_url}/redirect-target"
        scenario = self._scenario()
        scenario["path"] = "/redirect"
        result = run_scenario(scenario, self.base_url, self.auth_headers)

        self.assertEqual("FAIL", result["measurement_status"])
        self.assertEqual({"302": 12}, result["status_counts"])
        self.assertEqual(0, _Handler.redirect_target_hits)

    def test_response_body_read_is_bounded(self) -> None:
        class BoundedResponse:
            status = 200

            def __init__(self) -> None:
                self.read_sizes: list[int] = []

            def __enter__(self) -> "BoundedResponse":
                return self

            def __exit__(self, *args: object) -> None:
                return None

            def read(self, size: int = -1) -> bytes:
                self.read_sizes.append(size)
                return b"x" * min(size, 8)

        response = BoundedResponse()
        with mock.patch.object(http_load._HTTP_OPENER, "open", return_value=response):
            status, _, complete = http_load._single_request(
                f"{self.base_url}/synthetic",
                timeout_seconds=1.0,
                auth_headers=self.auth_headers,
                scenario=self._scenario(),
            )
        self.assertEqual(200, status)
        self.assertFalse(complete)
        self.assertEqual([MAX_RESPONSE_BODY_BYTES + 1], response.read_sizes)

    def test_scenario_numeric_limits_are_finite_positive_and_bounded(self) -> None:
        invalid_values = (
            ("concurrency", MAX_CONCURRENCY + 1),
            ("concurrency", True),
            ("total_requests", MAX_TOTAL_REQUESTS + 1),
            ("total_requests", 0),
            ("timeout_seconds", MAX_TIMEOUT_SECONDS + 0.1),
            ("timeout_seconds", 0),
            ("timeout_seconds", float("nan")),
            ("timeout_seconds", float("inf")),
        )
        for field, value in invalid_values:
            with self.subTest(field=field, value=value):
                scenario = self._scenario()
                scenario[field] = value
                with self.assertRaisesRegex(ContractError, field):
                    validate_scenario(scenario)

    def test_cross_origin_or_fragment_scenario_path_is_rejected(self) -> None:
        for path in ("//example.com/escape", "/synthetic#fragment"):
            with self.subTest(path=path):
                scenario = self._scenario()
                scenario["path"] = path
                with self.assertRaisesRegex(ContractError, "same-origin"):
                    validate_scenario(scenario)

    def test_contract_rejects_reduced_formal_load(self) -> None:
        contract = json.loads(
            Path(
                "scripts/release/contracts/performance-scenarios-v1.json"
            ).read_text(encoding="utf-8")
        )
        contract["scenarios"][0]["concurrency"] = 1
        with self.assertRaisesRegex(ContractError, "formal API"):
            validate_scenario_contract(contract)

    def test_export_requires_formal_external_producer(self) -> None:
        contract = json.loads(
            Path(
                "scripts/release/contracts/performance-scenarios-v1.json"
            ).read_text(encoding="utf-8")
        )
        scenario = next(
            item
            for item in contract["scenarios"]
            if item["id"] == "export-50000"
        )
        with self.assertRaisesRegex(ContractError, "formal external producer"):
            run_scenario(scenario, self.base_url, self.auth_headers)

    def test_execute_rejects_missing_authentication_before_network(self) -> None:
        with mock.patch.object(http_load._HTTP_OPENER, "open") as open_request:
            with self.assertRaisesRegex(ContractError, "credential header"):
                run_scenario(self._scenario(), self.base_url, {})
            open_request.assert_not_called()

    def test_auth_file_is_external_secure_and_never_echoes_secret(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            auth_file = root / "performance.auth"
            secret = "session=never-echo-this-value"
            auth_file.write_text(f"Cookie: {secret}\n", encoding="utf-8")
            auth_file.chmod(0o600)

            self.assertEqual(
                {"Cookie": secret},
                read_auth_header_file(auth_file, Path.cwd()),
            )

            auth_file.write_text(
                f"X-Unsafe-Header: {secret}\n",
                encoding="utf-8",
            )
            with self.assertRaises(ContractError) as raised:
                read_auth_header_file(auth_file, Path.cwd())
            self.assertNotIn(secret, str(raised.exception))

            auth_file.write_text(f"Cookie: {secret}\n", encoding="utf-8")
            auth_file.chmod(0o640)
            with self.assertRaisesRegex(ContractError, "0600"):
                read_auth_header_file(auth_file, Path.cwd())

    def test_auth_file_rejects_symlink_and_repository_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            auth_file = root / "performance.auth"
            auth_file.write_text(
                "Authorization: Bearer synthetic-token\n",
                encoding="utf-8",
            )
            auth_file.chmod(0o600)
            link = root / "performance-link.auth"
            link.symlink_to(auth_file)
            with self.assertRaisesRegex(ContractError, "non-symbolic"):
                read_auth_header_file(link, Path.cwd())

        repository_auth = Path.cwd() / ".synthetic-performance-auth"
        try:
            repository_auth.write_text(
                "Cookie: session=synthetic-token\n",
                encoding="utf-8",
            )
            repository_auth.chmod(0o600)
            with self.assertRaisesRegex(ContractError, "outside the repository"):
                read_auth_header_file(repository_auth, Path.cwd())
        finally:
            repository_auth.unlink(missing_ok=True)

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
