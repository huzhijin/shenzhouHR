#!/usr/bin/env python3
"""Dependency-free unit tests for the W3 zero-discoverability producer."""

from __future__ import annotations

import errno
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_wave3_payroll_zero as producer


class ZeroDiscoverabilityProducerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        (
            producer.REPOSITORY_ROOT / "docs/verification/wave3/runs"
        ).mkdir(parents=True, exist_ok=True)

    def test_nfkc_casefold_matcher_catches_compatibility_and_case_variants(
        self,
    ) -> None:
        for value in (
            "PAYROLL",
            "Pay_Roll",
            "ＰａＹＲＯＬＬ",
            "PAY-SLIPS",
            "薪资",
            "工资核算",
        ):
            with self.subTest(value=value):
                self.assertGreaterEqual(producer.deny_match_count(value), 1)
        self.assertEqual(producer.deny_match_count("考勤设置"), 0)

    def test_normalization_self_test_is_stable(self) -> None:
        result = producer.normalization_self_test()
        self.assertEqual(result["verdict"], "PASS")
        self.assertEqual(result["normalization"], "Unicode NFKC then full casefold")
        self.assertRegex(result["vectorSetSha256"], r"^[0-9a-f]{64}$")

    def test_ps_lstart_is_parsed_as_safe_environment_utc(self) -> None:
        parsed = producer.parse_ps_lstart_utc(
            "Mon Jul 28 12:34:56 2026", "fixture start"
        )
        expected = producer.datetime(
            2026, 7, 28, 12, 34, 56, tzinfo=producer.timezone.utc
        )
        self.assertEqual(parsed, expected)
        self.assertEqual(producer.safe_subprocess_environment()["TZ"], "UTC")

    def test_darwin_procargs_parser_preserves_exact_argv_and_environment(
        self,
    ) -> None:
        contents = (
            producer.struct.pack("=i", 2)
            + b"/usr/local/bin/node\0\0"
            + b"node\0/app/vite.js\0"
            + b"SAFE=value\0SHENZHOUHR_DB_PASSWORD=private\0\0"
        )
        executable, argv, environment = producer.parse_darwin_procargs2(
            contents
        )
        self.assertEqual(executable, "/usr/local/bin/node")
        self.assertEqual(argv, ("node", "/app/vite.js"))
        self.assertIn(b"SHENZHOUHR_DB_PASSWORD=private", environment)

    def _runtime_fixture(self) -> dict:
        return {
            "roles": [
                {
                    "roleCode": role,
                    "frontendRouteProbes": [
                        {
                            "requestTarget": target,
                            "stateTokens": ["404"],
                            "bodyText": "真实 404",
                        }
                        for target in producer.FRONTEND_NEGATIVE_TARGETS
                    ],
                    "apiProbes": [
                        {
                            "requestTarget": target,
                            "status": 404,
                            "headers": [{"name": "cache-control", "value": "no-store"}],
                            "bodyText": '{"code":"RESOURCE_NOT_FOUND"}',
                        }
                        for target in producer.API_NEGATIVE_TARGETS
                    ],
                }
                for role in producer.ROLE_CODES
            ]
        }

    def test_request_target_exclusions_are_exact_twelve_pointers(self) -> None:
        runtime = self._runtime_fixture()
        exclusions = producer.build_request_target_exclusions(
            runtime,
            runtime_artifact_path=producer.RUNTIME_ARTIFACT_PATH,
            runtime_artifact_realpath="/tmp/current-run/runtime.json",
        )
        pointers = producer.validate_request_target_exclusions(
            runtime,
            exclusions,
            expected_artifact_realpath="/tmp/current-run/runtime.json",
        )
        self.assertEqual(len(pointers), 12)
        result = producer.scan_runtime_document(runtime, pointers)
        self.assertEqual(result["discoverableHitCount"], 0)
        self.assertEqual(result["excludedRequestTargetScalarCount"], 12)
        for field, value in (
            ("schemaVersion", True),
            ("entryCount", True),
        ):
            with self.subTest(exclusion_exact_int=field):
                mutant = json.loads(json.dumps(exclusions))
                mutant[field] = value
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError,
                    "schema version|entryCount",
                ):
                    producer.validate_request_target_exclusions(
                        runtime,
                        mutant,
                        expected_artifact_realpath=(
                            "/tmp/current-run/runtime.json"
                        ),
                    )

    def test_request_target_exclusion_never_hides_sibling_response(self) -> None:
        runtime = self._runtime_fixture()
        exclusions = producer.build_request_target_exclusions(
            runtime,
            runtime_artifact_path=producer.RUNTIME_ARTIFACT_PATH,
            runtime_artifact_realpath="/tmp/current-run/runtime.json",
        )
        pointers = producer.validate_request_target_exclusions(
            runtime,
            exclusions,
            expected_artifact_realpath="/tmp/current-run/runtime.json",
        )
        runtime["roles"][0]["apiProbes"][0]["bodyText"] = "PAYROLL exists"
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "runtime deny scan"
        ):
            producer.scan_runtime_document(runtime, pointers)

    def test_request_target_exclusion_rejects_stale_scalar_hash(self) -> None:
        runtime = self._runtime_fixture()
        exclusions = producer.build_request_target_exclusions(
            runtime,
            runtime_artifact_path=producer.RUNTIME_ARTIFACT_PATH,
            runtime_artifact_realpath="/tmp/current-run/runtime.json",
        )
        exclusions["entries"][0]["scalarSha256"] = "0" * 64
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "scalar SHA256"
        ):
            producer.validate_request_target_exclusions(
                runtime,
                exclusions,
                expected_artifact_realpath="/tmp/current-run/runtime.json",
            )

    def test_request_target_exclusion_rejects_wrong_artifact_path(self) -> None:
        runtime = self._runtime_fixture()
        exclusions = producer.build_request_target_exclusions(
            runtime,
            runtime_artifact_path=producer.RUNTIME_ARTIFACT_PATH,
            runtime_artifact_realpath="/tmp/current-run/runtime.json",
        )
        exclusions["entries"][0]["artifactPath"] = "wrong"
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "artifact path"
        ):
            producer.validate_request_target_exclusions(
                runtime,
                exclusions,
                expected_artifact_realpath="/tmp/current-run/runtime.json",
            )

    def test_artifact_roles_match_frozen_contract(self) -> None:
        contract_path = (
            producer.REPOSITORY_ROOT
            / "scripts/qa/wave3-evidence-contract-v1.json"
        )
        contract = json.loads(contract_path.read_text(encoding="utf-8"))
        leaf = contract["leafContracts"][producer.EVIDENCE_ID]
        self.assertEqual(leaf["slug"], "payroll-zero")
        self.assertEqual(
            leaf["primaryArtifactRole"], "zero-discoverability-report"
        )
        self.assertEqual(
            leaf["requiredArtifactRoles"],
            list(producer.ARTIFACT_ROLE_FILENAMES),
        )
        self.assertEqual(
            list(producer.ARTIFACT_ROLE_FILENAMES.values()),
            list(producer.ARTIFACT_FILENAMES),
        )
        producer.validate_evidence_contract(contract)
        mutated = json.loads(json.dumps(contract))
        mutated["leafContracts"][producer.EVIDENCE_ID][
            "requiredArtifactRoles"
        ] = ["wrong"]
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "leaf is not exact"
        ):
            producer.validate_evidence_contract(mutated)
        schema_mutant = json.loads(json.dumps(contract))
        schema_mutant["schemaVersion"] = True
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError,
            "schema version",
        ):
            producer.validate_evidence_contract(schema_mutant)

    def test_real_control_plane_allowlist_is_exact_four_entries(self) -> None:
        context = {
            "runId": "w3-test-20260728",
            "sourceTreeHash": "e" * 64,
            "databaseIdentity": (
                "mysql8410:a1b2c3d4-1234-4abc-8def-1234567890ab:"
                "shenzhou_hr_test"
            ),
        }
        report = producer.build_primary_report(
            context=context,
            product_file_count=1,
            product_line_count=1,
            runtime_role_count=3,
            prod_dist_sha256="f" * 64,
            demo_dist_sha256="1" * 64,
        )
        with tempfile.TemporaryDirectory(prefix="w3-zero-report-") as root:
            staged = Path(root) / "report.log"
            staged.write_text(report, encoding="utf-8")
            final = (
                producer.REPOSITORY_ROOT
                / "docs/verification/wave3/runs/w3-unit-allowlist/"
                "leaves/payroll-zero/raw/zero-discoverability-report.log"
            )
            allowlist = producer.build_line_allowlist(
                report_path=staged,
                report_text=report,
                report_run_relative=(
                    "leaves/payroll-zero/raw/"
                    "zero-discoverability-report.log"
                ),
                report_final_realpath=final.as_posix(),
            )
            result = producer.validate_line_allowlist(
                allowlist,
                staged_realpaths={final.as_posix(): staged},
            )
            self.assertEqual(result["entryCount"], 4)
            for field, value in (
                ("schemaVersion", True),
                ("entryCount", True),
            ):
                with self.subTest(allowlist_exact_int=field):
                    mutant = json.loads(json.dumps(allowlist))
                    mutant[field] = value
                    with self.assertRaisesRegex(
                        producer.ZeroDiscoverabilityError,
                        "schema version|strict four-entry",
                    ):
                        producer.validate_line_allowlist(
                            mutant,
                            staged_realpaths={final.as_posix(): staged},
                        )
            allowlist["entries"][0]["lineNumber"] = allowlist["entries"][1][
                "lineNumber"
            ]
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError,
                "bound literal|expected line",
            ):
                producer.validate_line_allowlist(
                    allowlist,
                    staged_realpaths={final.as_posix(): staged},
                )

    def test_private_environment_enforces_mode_and_symlink(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-private-") as root:
            path = Path(root) / "login.env"
            path.write_text(
                "SHENZHOUHR_LOGIN_USERNAME=user\n"
                "SHENZHOUHR_LOGIN_PASSWORD=opaque=value\n",
                encoding="utf-8",
            )
            os.chmod(path, 0o600)
            values = producer.read_login_environment(path)
            self.assertEqual(values["SHENZHOUHR_LOGIN_USERNAME"], "user")
            os.chmod(path, 0o644)
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "0600"
            ):
                producer.read_login_environment(path)
            os.chmod(path, 0o600)
            linked = Path(root) / "linked.env"
            linked.symlink_to(path)
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "non-symbolic"
            ):
                producer.read_login_environment(linked)

    def test_backend_jdbc_environment_is_bound_without_persisting_secret(
        self,
    ) -> None:
        runtime = {"SHENZHOUHR_TEST_DB_PASSWORD": "private-password"}
        entries = (
            b"SHENZHOUHR_DB_URL=jdbc:mysql://127.0.0.1:13306/"
            b"shenzhou_hr_test?connectionTimeZone=UTC",
            b"SHENZHOUHR_DB_USERNAME=shenzhou_hr_test_app",
            b"SHENZHOUHR_DB_PASSWORD=private-password",
        )
        result = producer.validate_backend_jdbc_environment(entries, runtime)
        self.assertTrue(result["passwordBoundInMemory"])
        self.assertNotIn("private-password", json.dumps(result))
        wrong = (*entries[:-1], b"SHENZHOUHR_DB_PASSWORD=wrong")
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "private runtime binding"
        ):
            producer.validate_backend_jdbc_environment(wrong, runtime)

    def test_runtime_argv_contract_rejects_precedence_and_demo_mutants(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-argv-") as root:
            backend = Path(root) / "backend"
            classes = backend / "target/classes"
            other = backend / "other"
            classes.mkdir(parents=True)
            other.mkdir()
            executable = Path(root) / "java"
            executable.write_bytes(b"java")
            main = "com.szsemicon.hr.ShenzhouHrApplication"
            valid = ("java", "-cp", str(classes), main)
            self.assertEqual(
                producer.validate_backend_argv_contract(
                    valid, executable, backend
                ),
                [classes.resolve()],
            )
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "not first"
            ):
                producer.validate_backend_argv_contract(
                    (
                        "java",
                        "-cp",
                        f"{other}{os.pathsep}{classes}",
                        main,
                    ),
                    executable,
                    backend,
                )
            vite = Path(root) / "frontend/node_modules/.bin/vite"
            normal = (
                "node",
                str(vite),
                "--configLoader",
                "runner",
                "--host",
                "127.0.0.1",
                "--port",
                "5173",
                "--strictPort",
            )
            producer.validate_frontend_argv_contract(
                normal, Path(root) / "node", vite
            )
            without_runner = (*normal[:2], *normal[4:])
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "normal-mode"
            ):
                producer.validate_frontend_argv_contract(
                    without_runner, Path(root) / "node", vite
                )
            producer.validate_frontend_process_environment(
                (
                    b"PATH=/usr/bin",
                    b"VITE_API_PROXY_TARGET=http://127.0.0.1:8080",
                )
            )
            demo = (*normal[:2], "--mode", "demo", *normal[2:])
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "normal-mode"
            ):
                producer.validate_frontend_argv_contract(
                    demo, Path(root) / "node", vite
                )
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "unknown VITE"
            ):
                producer.validate_frontend_process_environment(
                    (
                        b"VITE_API_PROXY_TARGET=http://127.0.0.1:8080",
                        b"VITE_UNBOUND=value",
                    )
                )

    def test_sensitive_response_headers_are_redacted(self) -> None:
        headers = Message()
        headers["Set-Cookie"] = "SESSION=super-secret"
        headers["X-CSRF-TOKEN"] = "csrf-secret"
        headers["X-Correlation-ID"] = "correlation-safe"
        headers["Cache-Control"] = "no-store"
        headers["Content-Security-Policy"] = (
            "script-src 'nonce-low-entropy-secret'"
        )
        document = producer.response_headers_document(headers)
        serialized = json.dumps(document)
        self.assertNotIn("super-secret", serialized)
        self.assertNotIn("csrf-secret", serialized)
        self.assertNotIn("correlation-safe", serialized)
        self.assertNotIn("low-entropy-secret", serialized)
        self.assertNotIn(
            producer.sha256_text("script-src 'nonce-low-entropy-secret'"),
            serialized,
        )
        self.assertEqual(
            [
                row["valuePresent"]
                for row in document
                if row["name"] == "set-cookie"
            ],
            [True],
        )
        self.assertEqual(
            [
                row["valuePolicy"]
                for row in document
                if row["name"] == "cache-control"
            ],
            ["scanned-in-memory-not-persisted"],
        )

    def test_redirect_handler_never_follows_first_hop(self) -> None:
        handler = producer.NoRedirectHandler()
        request = producer.urllib.request.Request("http://127.0.0.1:8080/a")
        self.assertIsNone(
            handler.redirect_request(
                request,
                None,
                302,
                "Found",
                Message(),
                "http://127.0.0.1:8080/b",
            )
        )

    def test_browser_network_requires_and_records_full_bound_origin(self) -> None:
        request = {
            "url": "http://127.0.0.1:5173/api/v1/me/capabilities?x=secret",
            "method": "GET",
            "status": 200,
            "resourceType": "Fetch",
            "responseHeaders": {"Cache-Control": "no-store"},
        }
        rows = producer.normalize_browser_requests(
            [request],
            allowed_origins=(
                "http://127.0.0.1:5173",
                "http://127.0.0.1:8080",
            ),
        )
        self.assertEqual(
            rows[0]["requestOrigin"], "http://127.0.0.1:5173"
        )
        self.assertTrue(rows[0]["queryPresent"])
        self.assertNotIn("secret", json.dumps(rows))
        request["url"] = "http://127.0.0.1:9999/escape"
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "escaped"
        ):
            producer.normalize_browser_requests(
                [request],
                allowed_origins=(
                    "http://127.0.0.1:5173",
                    "http://127.0.0.1:8080",
                ),
            )

    def test_vite_hmr_raw_wrapper_unwraps_exact_source(self) -> None:
        source = "export const safe = '考勤';\n"
        wrapped = (
            'import { createHotContext } from "/@vite/client";'
            "export default "
            + json.dumps(source, ensure_ascii=False)
            + ';\nimport "/@react-refresh";'
        )
        self.assertEqual(
            producer.unwrap_vite_raw_text(wrapped, "fixture"), source
        )

    def test_nonfeature_body_is_closed_and_secret_safe(self) -> None:
        headers = Message()
        headers["X-Correlation-ID"] = "corr-123"
        body = {
            "code": "RESOURCE_NOT_AVAILABLE",
            "message": "请求的资源不可用",
            "correlationId": "corr-123",
            "retryable": False,
        }
        response = producer.HttpResponse(
            status=404,
            body_text=json.dumps(body, ensure_ascii=False),
            body_json=body,
            headers=headers,
        )
        safe = producer.safe_nonfeature_body(response, "AUDITOR")
        self.assertNotIn(body["message"], json.dumps(safe, ensure_ascii=False))
        body["extra"] = "secret"
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "schema is not closed"
        ):
            producer.safe_nonfeature_body(response, "AUDITOR")

    def test_duplicate_correlation_headers_fail_closed(self) -> None:
        headers = Message()
        headers["X-Correlation-ID"] = "corr-123"
        headers["X-Correlation-ID"] = "corr-123"
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "exactly one"
        ):
            producer.exact_single_header_value(
                headers, "X-Correlation-ID", "fixture"
            )
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "correlation binding"
        ):
            producer.validate_browser_proxy_headers(
                [
                    ["x-correlation-id", "corr-123"],
                    ["X-Correlation-ID", "corr-123"],
                ],
                "corr-123",
                "AUDITOR",
            )

    def test_browser_404_contract_is_exact(self) -> None:
        state = {
            "requestTargetRetained": True,
            "heading": "页面不存在",
            "menuLabels": ["考勤设置"],
            "stateTokens": ["404"],
            "buttonTexts": ["返回首页"],
            "environmentText": "正常环境",
            "bodyText": "页面不存在 返回首页",
            "documentTitle": "神州人力",
            "mainPresent": True,
        }
        contract = producer.browser_nonfeature_ui_contract(
            state, "AUDITOR", "fixture"
        )
        self.assertEqual(contract["stateTokens"], ["404"])
        state["stateTokens"] = ["feature", "404"]
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "exact real 404"
        ):
            producer.browser_nonfeature_ui_contract(
                state, "AUDITOR", "fixture"
            )

    def test_agent_browser_may_use_a_resolved_executable_symlink(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-browser-") as root:
            directory = Path(root)
            binary_target = directory / "agent-browser-real"
            binary_target.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            os.chmod(binary_target, 0o700)
            binary_link = directory / "agent-browser"
            binary_link.symlink_to(binary_target)
            chrome = directory / "chrome"
            chrome.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            os.chmod(chrome, 0o700)
            runner = producer.AgentBrowserRunner(
                binary=binary_link,
                chrome=chrome,
                namespace="fixture",
                allowed_domains=["127.0.0.1"],
            )
            self.assertEqual(runner.binary, binary_target.resolve())

    def test_run_bound_credentials_are_deterministic_and_public_view_is_safe(
        self,
    ) -> None:
        context = {
            "runId": "w3-test-20260728",
            "sourceTreeHash": "a" * 64,
            "databaseIdentity": (
                "mysql8410:a1b2c3d4-1234-4abc-8def-1234567890ab:"
                "shenzhou_hr_test"
            ),
        }
        first = producer.derived_principal_secrets(
            "bootstrap-secret", context, "SYSTEM_ADMIN"
        )
        second = producer.derived_principal_secrets(
            "bootstrap-secret", context, "SYSTEM_ADMIN"
        )
        auditor = producer.derived_principal_secrets(
            "bootstrap-secret", context, "AUDITOR"
        )
        self.assertEqual(first, second)
        self.assertNotEqual(first["password"], auditor["password"])
        public = producer.public_role_document(
            {
                "roleCode": "SYSTEM_ADMIN",
                "roleId": "role-id",
                "roleName": "系统管理员",
                "accountId": "account-id",
                "username": first["username"],
                "password": first["password"],
                "created": False,
                "repaired": False,
                "capabilities": ["ATTENDANCE_SETUP:READ"],
                "menu": [{"key": "safe", "label": "安全", "path": "/safe"}],
            }
        )
        serialized = json.dumps(public, ensure_ascii=False)
        self.assertNotIn(first["password"], serialized)
        self.assertNotIn(first["username"], serialized)

    def test_account_row_version_rejects_bool_as_int(self) -> None:
        self.assertEqual(
            producer.require_account_row_version(0, "SYSTEM_ADMIN"), 0
        )
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "rowVersion"
        ):
            producer.require_account_row_version(True, "SYSTEM_ADMIN")

    def test_database_seal_uses_one_snapshot_and_rejects_principal_drift(
        self,
    ) -> None:
        account_id = "10000000-0000-4000-8000-000000000001"
        sql = producer.consistent_database_snapshot_sql(
            {
                account_id: (
                    "w3_system_admin",
                    "20000000-0000-4000-8000-000000000001",
                    "SYSTEM_ADMIN",
                )
            }
        )
        self.assertEqual(
            sql.count(
                "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY"
            ),
            1,
        )
        self.assertEqual(sql.count("COMMIT;"), 1)
        self.assertIn("@evidence_now", sql)
        database = {"databaseIdentity": "bound"}
        principal = {
            "bindingKind": "api-account-to-mysql-row",
            "rows": [{"roleCode": "SYSTEM_ADMIN"}],
        }
        runtime = {
            "databaseEnd": database,
            "backendDatabaseBinding": principal,
        }
        producer.assert_final_database_seal(runtime, database, principal)
        drifted = {
            **principal,
            "rows": [{"roleCode": "AUDITOR"}],
        }
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "principal database binding"
        ):
            producer.assert_final_database_seal(
                runtime, database, drifted
            )

    def test_flyway_snapshot_is_exactly_frozen_v1_through_v7(
        self,
    ) -> None:
        exact_history = [
            (
                "1",
                "1",
                "identity organization authorization audit",
                "SQL",
                "V1__identity_organization_authorization_audit.sql",
                "-517298571",
                "1",
            ),
            (
                "2",
                "2",
                "baseline authorization catalog",
                "SQL",
                "V2__baseline_authorization_catalog.sql",
                "-1339458194",
                "1",
            ),
            (
                "3",
                "3",
                "local identity session",
                "SQL",
                "V3__local_identity_session.sql",
                "-1266620108",
                "1",
            ),
            (
                "4",
                "4",
                "versioned policy foundation",
                "SQL",
                "V4__versioned_policy_foundation.sql",
                "712093963",
                "1",
            ),
            (
                "5",
                "5",
                "people initial import and versioning",
                "SQL",
                "V5__people_initial_import_and_versioning.sql",
                "-1637666734",
                "1",
            ),
            (
                "6",
                "6",
                "system admin people read prerequisite",
                "SQL",
                "V6__system_admin_people_read_prerequisite.sql",
                "557782197",
                "1",
            ),
            (
                "7",
                "7",
                "attendance setup and base policies",
                "SQL",
                "V7__attendance_setup_and_base_policies.sql",
                "-2074869941",
                "1",
            ),
        ]
        derived = producer.frozen_flyway_history()
        self.assertEqual(
            [
                (
                    str(row["installedRank"]),
                    str(row["version"]),
                    str(row["description"]),
                    str(row["type"]),
                    str(row["script"]),
                    str(row["checksum"]),
                    "1" if row["success"] is True else "0",
                )
                for row in derived
            ],
            exact_history,
        )

        server_uuid = "a1b2c3d4-1234-4abc-8def-1234567890ab"
        rows = [
            "\t".join(
                (
                    "SERVER",
                    "8.4.10",
                    "13306",
                    server_uuid,
                    (
                        producer.MYSQL_ISOLATED_ROOT
                        / "run/mysql.sock"
                    ).as_posix(),
                    (
                        producer.MYSQL_ISOLATED_ROOT
                        / "data"
                    ).as_posix()
                    + "/",
                    producer.MYSQL_DATABASE,
                )
            ),
            *[
                "\t".join(("FLYWAY", *history_row))
                for history_row in exact_history
            ],
            "MARKER\tlegalEntity\t1",
            "MARKER\tpolicyTemplates\t3",
            "MARKER\tpolicyVersions\t3",
            "MARKER\tgroupRevisions\t0",
            "MARKER\tshiftVersions\t0",
            "MARKER\tcalendarVersions\t0",
            "MARKER\torganizations\t0",
            "MARKER\temployees\t0",
            "MARKER\tpeopleImportBatches\t0",
            "MARKER\tretainedPolicyTemplates\t0",
        ]
        runtime_environment = {
            "SHENZHOUHR_W3_DB_IDENTITY": producer.database_identity_for(
                server_uuid
            )
        }
        database, principals = producer.parse_consistent_database_snapshot(
            rows,
            runtime_environment,
            {},
        )
        self.assertEqual(database["flyway"]["latestVersion"], "7")
        self.assertEqual(database["flyway"]["appliedCount"], 7)
        self.assertIsNone(principals)

        mutations = (
            (1, "8"),
            (2, "bogus"),
            (3, "bogus description"),
            (4, "JDBC"),
            (5, "V1__bogus.sql"),
            (6, "NULL"),
            (7, "0"),
        )
        for field_index, replacement in mutations:
            with self.subTest(field_index=field_index):
                mutated = list(rows)
                fields = mutated[1].split("\t")
                fields[field_index] = replacement
                mutated[1] = "\t".join(fields)
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError,
                    "Flyway history differs",
                ):
                    producer.parse_consistent_database_snapshot(
                        mutated,
                        runtime_environment,
                        {},
                    )

        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError,
            "exactly the frozen V1-V7 closure",
        ):
            producer.parse_consistent_database_snapshot(
                [
                    *rows[:8],
                    "FLYWAY\t8\t8\tfuture\tSQL\tV8__future.sql\t0\t1",
                    *rows[8:],
                ],
                runtime_environment,
                {},
            )

    def test_product_scan_rejects_normalized_content_and_path_hits(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-product-") as root:
            repository = Path(root)
            safe = repository / "frontend/src/Safe.ts"
            safe.parent.mkdir(parents=True)
            safe.write_text("export const value = '考勤';\n", encoding="utf-8")
            with patch.object(producer, "REPOSITORY_ROOT", repository):
                records, lines = producer.scan_product_scopes(
                    {"frontend-product-source": [safe]}
                )
                self.assertEqual(len(records), 1)
                self.assertEqual(lines, 1)
                safe.write_text(
                    "export const value = 'ＰＡＹＲＯＬＬ';\n",
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError, "product deny scan"
                ):
                    producer.scan_product_scopes(
                        {"frontend-product-source": [safe]}
                    )
                safe.write_text(
                    "export const value = 'pay\nroll';\n",
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError, "product deny scan"
                ):
                    producer.scan_product_scopes(
                        {"frontend-product-source": [safe]}
                    )

    def test_product_snapshot_recheck_rejects_changed_bytes(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-recheck-") as root:
            repository = Path(root)
            safe = repository / "frontend/src/Safe.ts"
            safe.parent.mkdir(parents=True)
            safe.write_text("safe\n", encoding="utf-8")
            scopes = {"frontend-product-source": [safe]}
            with (
                patch.object(producer, "REPOSITORY_ROOT", repository),
                patch.object(
                    producer,
                    "collect_product_scope_files",
                    return_value=scopes,
                ),
            ):
                records, _ = producer.scan_product_scopes(scopes)
                safe.write_text("changed\n", encoding="utf-8")
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError, "snapshot changed"
                ):
                    producer.assert_product_scan_snapshot_unchanged(
                        scopes, records
                    )

    def test_stable_tree_snapshot_binds_content_and_metadata(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-class-tree-") as root:
            repository = Path(root)
            classes = repository / "backend/target/classes"
            first = classes / "example/First.class"
            second = classes / "example/Second.class"
            first.parent.mkdir(parents=True)
            first.write_bytes(b"first")
            second.write_bytes(b"second")
            with patch.object(producer, "REPOSITORY_ROOT", repository):
                before = producer.stable_tree_snapshot(
                    classes, "fixture classes"
                )
                self.assertEqual(before["fileCount"], 2)
                self.assertTrue(
                    all(
                        {
                            "device",
                            "inode",
                            "mode",
                            "mtimeEpochNs",
                            "ctimeEpochNs",
                            "sha256",
                        }.issubset(record)
                        for record in before["records"]
                    )
                )
                second.write_bytes(b"changed")
                after = producer.stable_tree_snapshot(
                    classes, "fixture classes"
                )
                self.assertNotEqual(
                    before["treeSha256"], after["treeSha256"]
                )

    def test_staged_artifact_seal_rejects_tamper_and_secret_mutants(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="w3-zero-artifact-seal-"
        ) as root:
            artifact_root = Path(root)
            expected: dict[str, bytes] = {}
            for name in producer.ARTIFACT_FILENAMES:
                contents = (
                    b"safe report\n"
                    if name.endswith(".log")
                    else b'{"safe":"value"}\n'
                )
                (artifact_root / name).write_bytes(contents)
                expected[name] = contents
            seal, _ = producer.freeze_exact_artifact_bytes(
                artifact_root, expected, ("private-secret",)
            )
            self.assertEqual(seal["fileCount"], 6)
            target = artifact_root / "normal-role-runtime-probes.json"
            target.write_bytes(b'{"safe":"changed"}\n')
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "bytes changed"
            ):
                producer.freeze_exact_artifact_bytes(
                    artifact_root, expected, ("private-secret",)
                )
            target.write_bytes(
                b'{"safe":"private-secret"}\n'
            )
            secret_expected = {
                **expected,
                "normal-role-runtime-probes.json": target.read_bytes(),
            }
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "private runtime"
            ):
                producer.freeze_exact_artifact_bytes(
                    artifact_root,
                    secret_expected,
                    ("private-secret",),
                )

    def test_tree_snapshot_rejects_same_second_post_start_metadata(self) -> None:
        started = 1_000_000_000
        valid = {
            "records": [
                {
                    "mtimeEpochNs": started,
                    "ctimeEpochNs": started,
                }
            ]
        }
        producer.assert_tree_snapshot_predates_process(
            valid, started, "fixture classes"
        )
        changed_in_same_second = {
            "records": [
                {
                    "mtimeEpochNs": started + 1,
                    "ctimeEpochNs": started,
                }
            ]
        }
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "after process start"
        ):
            producer.assert_tree_snapshot_predates_process(
                changed_in_same_second, started, "fixture classes"
            )

    def test_frontend_test_filter_is_specific(self) -> None:
        root = Path("/tmp/frontend/src")
        self.assertTrue(
            producer.is_frontend_test_path(
                root / "app/routeAuthorization.test.ts", root
            )
        )
        self.assertTrue(
            producer.is_frontend_test_path(root / "test/setup.ts", root)
        )
        self.assertFalse(
            producer.is_frontend_test_path(root / "app/App.tsx", root)
        )

    def test_fixed_product_root_manifests_close_current_source(self) -> None:
        self.assertEqual(len(producer.FRONTEND_PRODUCT_MANIFEST), 79)
        self.assertEqual(len(set(producer.FRONTEND_PRODUCT_MANIFEST)), 79)
        scopes = producer.collect_product_scope_files()
        self.assertEqual(len(scopes["frontend-product-source"]), 79)
        self.assertEqual(len(scopes["backend-rest-interface-dto"]), 38)

    def test_fresh_dist_must_postdate_run_and_build_inputs(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-fresh-") as root:
            repository = Path(root)
            source = repository / "frontend/src/App.tsx"
            prod = repository / "frontend/dist/prod/index.js"
            demo = repository / "frontend/dist/demo/index.js"
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("safe\n", encoding="utf-8")
            run_start = time.time_ns()
            for path in (prod, demo):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("safe\n", encoding="utf-8")
            scopes = {
                "frontend-product-source": [source],
                "frontend-prod-dist": [prod],
                "frontend-demo-dist": [demo],
            }
            with (
                patch.object(producer, "REPOSITORY_ROOT", repository),
                patch.object(
                    producer,
                    "validate_build_receipt",
                    return_value={
                        "verdict": "PASS",
                        "buildBundleCommitSha256": "a" * 64,
                        "buildBundleManifestSha256": "b" * 64,
                        "toolchain": {
                            "workspaceManifestSha256": "c" * 64,
                        },
                    },
                ),
                patch.object(
                    producer,
                    "current_dependency_handoff_manifest",
                    return_value={"fixture": "dependency"},
                ),
                patch.object(
                    producer,
                    "current_command_support_manifest",
                    return_value={"fixture": "support"},
                ),
            ):
                result = producer.validate_fresh_dist(
                    {"startedAtEpochNs": run_start}, scopes
                )
                self.assertGreater(
                    result["prodDistMinimumMtimeEpochNs"],
                    result["buildInputFreshnessFloorEpochNs"],
                )
                source.write_text("changed\n", encoding="utf-8")
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError, "older"
                ):
                    producer.validate_fresh_dist(
                        {"startedAtEpochNs": run_start}, scopes
                    )

    def test_dist_receipt_rejects_stale_ctime_mutant(self) -> None:
        record = {
            "path": "index.html",
            "sizeBytes": 4,
            "mtimeEpochNs": 250,
            "ctimeEpochNs": 250,
            "sha256": "a" * 64,
        }
        inventory = [
            {
                "path": "index.html",
                "sizeBytes": 4,
                "mode": 0o644,
                "device": 1,
                "inode": 2,
                "mtimeEpochNs": 250,
                "ctimeEpochNs": 250,
                "sha256": "a" * 64,
            }
        ]
        record.update({"mode": 0o644, "device": 1, "inode": 2})
        producer.validate_dist_receipt_records(
            {"records": [record]},
            inventory,
            freshness_floor_ns=100,
            build_started_ns=200,
            build_completed_ns=300,
            label="fixture",
        )
        stale = {**record, "ctimeEpochNs": 100}
        stale_inventory = [{**inventory[0], "ctimeEpochNs": 100}]
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "stale"
        ):
            producer.validate_dist_receipt_records(
                {"records": [stale]},
                stale_inventory,
                freshness_floor_ns=100,
                build_started_ns=200,
                build_completed_ns=300,
                label="fixture",
            )

    def test_dist_archive_is_canonical_and_rejects_content_mutant(self) -> None:
        contents = b"safe-dist"
        digest = producer.sha256_bytes(contents)
        record = {
            "path": "index.html",
            "sizeBytes": len(contents),
            "mode": 0o644,
            "device": 1,
            "inode": 2,
            "mtimeEpochNs": 250,
            "ctimeEpochNs": 250,
            "sha256": digest,
        }

        def envelope(
            member_contents: bytes,
            *,
            schema_version: object = 1,
            receipt_contents: bytes = contents,
            member_mode: object = 0o644,
            member_size: object | None = None,
        ) -> dict:
            receipt_digest = producer.sha256_bytes(receipt_contents)
            archive = {
                "schemaVersion": schema_version,
                "format": "wave3-dist-content-archive-v1",
                "members": [
                    {
                        "path": "index.html",
                        "mode": member_mode,
                        "sizeBytes": (
                            len(receipt_contents)
                            if member_size is None
                            else member_size
                        ),
                        "sha256": receipt_digest,
                        "contentBase64": producer.base64.b64encode(
                            member_contents
                        ).decode("ascii"),
                    }
                ],
            }
            raw = producer.compact_canonical_json_bytes(archive)
            compressed = producer.zlib.compress(raw, level=9)
            return {
                "format": (
                    "wave3-dist-content-archive-v1"
                    "+canonical-json+zlib+base64"
                ),
                "memberCount": 1,
                "uncompressedSizeBytes": len(raw),
                "compressedSizeBytes": len(compressed),
                "uncompressedSha256": producer.sha256_bytes(raw),
                "compressedSha256": producer.sha256_bytes(compressed),
                "payloadBase64": producer.base64.b64encode(compressed).decode(
                    "ascii"
                ),
            }

        summary = producer.validate_dist_content_archive(
            envelope(contents),
            inventory_files=[record],
            snapshot={"records": [record]},
            label="fixture",
        )
        self.assertEqual(summary["memberCount"], 1)
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "member content"
        ):
            producer.validate_dist_content_archive(
                envelope(b"evil-dist"),
                inventory_files=[record],
                snapshot={"records": [record]},
                label="fixture",
            )
        one_byte = b"x"
        one_digest = producer.sha256_bytes(one_byte)
        for field, mode_value, size_value in (
            ("mode", True, 1),
            ("sizeBytes", 1, True),
        ):
            with self.subTest(bool_as_int_archive_member=field):
                one_record = {
                    **record,
                    "sizeBytes": 1,
                    "mode": 1,
                    "sha256": one_digest,
                }
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError,
                    "member/inventory binding",
                ):
                    producer.validate_dist_content_archive(
                        envelope(
                            one_byte,
                            receipt_contents=one_byte,
                            member_mode=mode_value,
                            member_size=size_value,
                        ),
                        inventory_files=[one_record],
                        snapshot={"records": [one_record]},
                        label="fixture",
                    )
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError,
            "schema/type",
        ):
            producer.validate_dist_content_archive(
                envelope(contents, schema_version=True),
                inventory_files=[record],
                snapshot={"records": [record]},
                label="fixture",
            )

    def test_build_toolchain_binds_run_private_install_clone(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-toolchain-") as root:
            repository = Path(root).resolve(strict=True)
            frontend = repository / "frontend"
            node_modules = frontend / "node_modules"
            node_modules.mkdir(parents=True)
            vite_entry = node_modules / "vite/bin/vite.js"
            vite_entry.parent.mkdir(parents=True)
            vite_entry.write_bytes(b"vite-entry")
            vite_bin = node_modules / ".bin"
            vite_bin.mkdir()
            (vite_bin / "vite").symlink_to("../vite/bin/vite.js")
            install_paths = (
                frontend / "package.json",
                frontend / "package-lock.json",
                node_modules / ".package-lock.json",
            )
            for index, path in enumerate(install_paths):
                path.write_text(
                    json.dumps({"fixture": index}) + "\n",
                    encoding="utf-8",
                )
            binaries = repository / "bin"
            binaries.mkdir()
            node = binaries / "node"
            npm = binaries / "npm-cli.js"
            java = binaries / "java"
            node.write_bytes(b"node")
            npm.write_bytes(b"npm")
            java.write_bytes(b"java")
            os.chmod(node, 0o700)
            os.chmod(npm, 0o700)
            os.chmod(java, 0o700)
            run_root = (
                repository
                / "docs/verification/wave3/runs/w3-toolchain-unit"
            )
            run_root.mkdir(parents=True)
            build_started = time.time_ns() + 1_000_000_000

            def receipt(path: Path, receipt_path: str) -> dict:
                metadata = path.stat()
                return {
                    "path": receipt_path,
                    "sizeBytes": metadata.st_size,
                    "mode": metadata.st_mode & 0o7777,
                    "device": metadata.st_dev,
                    "inode": metadata.st_ino,
                    "mtimeEpochNs": metadata.st_mtime_ns,
                    "ctimeEpochNs": metadata.st_ctime_ns,
                    "sha256": producer.sha256_bytes(path.read_bytes()),
                }

            npm_receipt = receipt(npm, str(npm))
            runtime = [
                receipt(node, str(node)),
                receipt(npm, str(npm)),
            ]
            stage = ".build-gates-tmp-fixture"
            run_relative = run_root.relative_to(repository)
            install = []
            for path in install_paths:
                suffix = (
                    "node_modules/.package-lock.json"
                    if path.name == ".package-lock.json"
                    else path.name
                )
                isolated = (
                    run_relative
                    / stage
                    / ".command-workspace/frontend"
                    / suffix
                ).as_posix()
                install.append(receipt(path, isolated))
            workspace = {
                "schemaVersion": 1,
                "sealType": "darwin-kqueue-vnode-two-phase-content-v2",
                "authorityRoots": [
                    "frozen-source",
                    "isolated-command-source",
                    "isolated-command-support",
                    "isolated-maven-install",
                    "isolated-node-install",
                    "tool-java",
                    "tool-node",
                    "tool-npm",
                ],
                "objectCount": 100,
                "regularFileCount": 80,
                "cacheAnchorCount": 2,
                "contentManifestSha256": "a" * 64,
                "dependencyHandoff": {
                    "schemaVersion": 1,
                    "comparison": "exact-canonical-json",
                    "equal": True,
                    "logicalManifestSha256": "b" * 64,
                    "nodeRecordCount": 50_052,
                    "nodeLinkCount": 14,
                    "mavenRecordCount": 6_977,
                },
                "commandSupport": {
                    "schemaVersion": 1,
                    "comparison": "exact-canonical-json",
                    "equal": True,
                    "recordCount": 14,
                    "recordsSha256": "c" * 64,
                },
                "toolRuntime": {
                    "schemaVersion": 1,
                    "comparison": "exact-canonical-json",
                    "equal": True,
                    "logicalManifestSha256": "d" * 64,
                    "javaExecutablePath": str(java),
                    "nodeExecutablePath": str(node),
                    "npmExecutablePath": str(npm),
                    "nodeSha256": receipt(node, str(node))["sha256"],
                    "javaRecordCount": 541,
                    "javaRecordsSha256": "f" * 64,
                    "npmPackageRecordCount": 900,
                    "npmPackageRecordsSha256": "1" * 64,
                    "npmDependencyRecordCount": 1_800,
                    "npmDependencyLinkCount": 9,
                    "npmDependencyRecordsSha256": "2" * 64,
                },
            }
            current_tool_runtime = {
                "logicalManifestSha256": "d" * 64,
                "java": {
                    "recordCount": 541,
                    "recordsSha256": "f" * 64,
                },
                "npmPackage": {
                    "recordCount": 900,
                    "recordsSha256": "1" * 64,
                },
                "npmDependencies": {
                    "recordCount": 1_800,
                    "linkCount": 9,
                    "recordsSha256": "2" * 64,
                },
            }
            toolchain = {
                "schemaVersion": 1,
                "resolvedExecutable": str(npm),
                "executableDevice": npm_receipt["device"],
                "executableInode": npm_receipt["inode"],
                "executableMode": npm_receipt["mode"],
                "executableSizeBytes": npm_receipt["sizeBytes"],
                "executableMtimeEpochNs": npm_receipt["mtimeEpochNs"],
                "executableCtimeEpochNs": npm_receipt["ctimeEpochNs"],
                "executableSha256": npm_receipt["sha256"],
                "versionOutput": "11.0.0",
                "runtimeExecutables": runtime,
                "installState": install,
                "installStateSha256": producer.sha256_bytes(
                    producer.compact_canonical_json_bytes(install)
                ),
                "workspaceSeal": workspace,
            }

            def resolve(name: str, _label: str) -> Path:
                return {
                    "java": java,
                    "node": node,
                    "npm": npm,
                }[name]

            validation_kwargs = {
                "expected_executable": npm,
                "context": {"runRoot": run_root},
                "build_started_ns": build_started,
                "label": "fixture",
                "current_dependency_manifest": {
                    "logicalManifestSha256": "b" * 64,
                    "node": {
                        "recordCount": 50_052,
                        "linkCount": 14,
                        "records": [
                            {
                                "path": ".bin/vite",
                                "kind": "symlink",
                                "mode": (
                                    (vite_bin / "vite").lstat().st_mode
                                    & 0o7777
                                ),
                                "sizeBytes": (
                                    (vite_bin / "vite").lstat().st_size
                                ),
                                "target": "../vite/bin/vite.js",
                                "resolvedTargetRelativePath": (
                                    "vite/bin/vite.js"
                                ),
                                "resolvedTargetMode": (
                                    vite_entry.stat().st_mode & 0o7777
                                ),
                                "resolvedTargetSizeBytes": (
                                    vite_entry.stat().st_size
                                ),
                                "resolvedTargetSha256": (
                                    producer.sha256_bytes(
                                        vite_entry.read_bytes()
                                    )
                                ),
                            },
                            {
                                "path": "vite/bin/vite.js",
                                "kind": "file",
                                "mode": vite_entry.stat().st_mode & 0o7777,
                                "sizeBytes": vite_entry.stat().st_size,
                                "sha256": producer.sha256_bytes(
                                    vite_entry.read_bytes()
                                ),
                            },
                        ],
                    },
                    "maven": {"recordCount": 6_977},
                },
                "current_support_manifest": {
                    "recordCount": 14,
                    "recordsSha256": "c" * 64,
                },
            }
            with (
                patch.object(producer, "REPOSITORY_ROOT", repository),
                patch.object(
                    producer,
                    "resolve_safe_executable",
                    side_effect=resolve,
                ),
                patch.object(
                    producer,
                    "current_tool_runtime_manifest",
                    return_value=current_tool_runtime,
                ),
            ):
                summary = producer.validate_build_toolchain(
                    toolchain,
                    **validation_kwargs,
                )
                self.assertEqual(
                    summary["workspaceManifestSha256"], "a" * 64
                )
                self.assertEqual(
                    summary["dependencyManifestSha256"], "b" * 64
                )
                self.assertEqual(
                    summary["commandSupportManifestSha256"], "c" * 64
                )
                self.assertEqual(
                    summary["toolRuntimeManifestSha256"], "d" * 64
                )
                self.assertEqual(
                    summary["frontendDependencyBinding"][
                        "viteEntrySha256"
                    ],
                    producer.sha256_bytes(vite_entry.read_bytes()),
                )
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError,
                    "receipt-selected npm differs from current authority",
                ):
                    producer.validate_build_toolchain(
                        toolchain,
                        **{
                            **validation_kwargs,
                            "expected_executable": node,
                        },
                    )
                forged = json.loads(json.dumps(toolchain))
                forged["installState"][0]["path"] = "frontend/package.json"
                forged["installStateSha256"] = producer.sha256_bytes(
                    producer.compact_canonical_json_bytes(
                        forged["installState"]
                    )
                )
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError, "run-private clone"
                ):
                    producer.validate_build_toolchain(
                        forged,
                        **validation_kwargs,
                    )
                forged_handoff = json.loads(json.dumps(toolchain))
                forged_handoff["workspaceSeal"]["dependencyHandoff"][
                    "equal"
                ] = False
                with self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError,
                    "workspace-seal receipt",
                ):
                    producer.validate_build_toolchain(
                        forged_handoff,
                        **validation_kwargs,
                    )
                for section, field, value in (
                    (
                        "dependencyHandoff",
                        "logicalManifestSha256",
                        "9" * 64,
                    ),
                    ("commandSupport", "recordsSha256", "8" * 64),
                    ("toolRuntime", "nodeSha256", "7" * 64),
                ):
                    with self.subTest(recomputed_manifest=section):
                        forged_manifest = json.loads(
                            json.dumps(toolchain)
                        )
                        forged_manifest["workspaceSeal"][section][
                            field
                        ] = value
                        with self.assertRaisesRegex(
                            producer.ZeroDiscoverabilityError,
                            "workspace-seal receipt",
                        ):
                            producer.validate_build_toolchain(
                                forged_manifest,
                                **validation_kwargs,
                            )
                exact_integer_mutants = (
                    ("schemaVersion", None, True),
                    ("cacheAnchorCount", "workspaceSeal", 2.0),
                    (
                        "nodeLinkCount",
                        "dependencyHandoff",
                        14.0,
                    ),
                    ("schemaVersion", "commandSupport", 1.0),
                    ("recordCount", "commandSupport", 14.0),
                    ("schemaVersion", "toolRuntime", 1.0),
                    ("javaRecordCount", "toolRuntime", 541.0),
                    (
                        "npmDependencyLinkCount",
                        "toolRuntime",
                        9.0,
                    ),
                )
                for key, section, value in exact_integer_mutants:
                    with self.subTest(
                        exact_integer_field=key,
                        section=section,
                    ):
                        forged_integer = json.loads(json.dumps(toolchain))
                        if section is None:
                            forged_integer[key] = value
                        elif section == "workspaceSeal":
                            forged_integer["workspaceSeal"][key] = value
                        else:
                            forged_integer["workspaceSeal"][section][
                                key
                            ] = value
                        with self.assertRaisesRegex(
                            producer.ZeroDiscoverabilityError,
                            "workspace-seal receipt|toolchain receipt|"
                            "toolchain scalar contract",
                        ):
                            producer.validate_build_toolchain(
                                forged_integer,
                                **validation_kwargs,
                            )

    def test_build_bundle_commit_rejects_committed_artifact_tamper(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-build-bundle-") as root:
            repository = Path(root)
            run_root = (
                repository
                / "docs/verification/wave3/runs/w3-bundle-unit"
            )
            run_root.mkdir(parents=True)
            context = {
                "runRoot": run_root,
                "runId": "w3-bundle-unit",
                "sourceTreeHash": "b" * 64,
                "databaseIdentity": "mysql8410:unit:shenzhou_hr_test",
                "startedAtEpochNs": 1,
            }
            manifest: dict[str, dict] = {}
            raw_directories: list[Path] = []
            artifact_paths: list[Path] = []
            for slug, evidence_id, marker, filenames in producer.BUILD_GATE_BUNDLE:
                raw = run_root / "leaves" / slug / "raw"
                raw.mkdir(parents=True)
                raw_directories.append(raw)
                for index, filename in enumerate(filenames):
                    path = raw / filename
                    if index == 0:
                        path.write_text(
                            f"{producer.CONTEXT_PREFIX} "
                            f"runId={context['runId']} "
                            f"sourceHash={context['sourceTreeHash']} "
                            f"dbIdentity={context['databaseIdentity']} "
                            f"evidenceId={evidence_id}\n"
                            f"{marker}\n",
                            encoding="utf-8",
                        )
                    else:
                        path.write_text('{"safe":"value"}\n', encoding="utf-8")
                    os.chmod(path, 0o400)
                    artifact_paths.append(path)
                    manifest[f"{slug}/{filename}"] = (
                        producer.build_artifact_descriptor(
                            path.read_bytes(), path.stat()
                        )
                    )
                os.chmod(raw, 0o500)
            committed_at = producer.utc_now()
            marker_value = {
                "schemaVersion": 1,
                "nodeType": "wave3-build-gates-commit",
                "runId": context["runId"],
                "sourceTreeHash": context["sourceTreeHash"],
                "databaseIdentity": context["databaseIdentity"],
                "gateIds": [
                    evidence_id
                    for _slug, evidence_id, _marker, _files
                    in producer.BUILD_GATE_BUNDLE
                ],
                "artifactManifest": manifest,
                "artifactManifestSha256": producer.sha256_bytes(
                    producer.compact_canonical_json_bytes(manifest)
                ),
                "committedAt": committed_at,
                "verdict": "PASS",
            }
            commit_path = run_root / producer.BUILD_GATE_COMMIT_FILENAME
            commit_path.write_text(
                json.dumps(marker_value, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            os.chmod(commit_path, 0o400)
            try:
                with patch.object(producer, "REPOSITORY_ROOT", repository):
                    result = producer.validate_build_bundle_commit(context)
                    self.assertEqual(result["artifactCount"], 13)
                    schema_mutant = dict(marker_value)
                    schema_mutant["schemaVersion"] = True
                    os.chmod(commit_path, 0o600)
                    commit_path.write_text(
                        json.dumps(
                            schema_mutant,
                            ensure_ascii=False,
                            indent=2,
                        )
                        + "\n",
                        encoding="utf-8",
                    )
                    os.chmod(commit_path, 0o400)
                    with self.assertRaisesRegex(
                        producer.ZeroDiscoverabilityError,
                        "schemaVersion type",
                    ):
                        producer.validate_build_bundle_commit(context)
                    os.chmod(commit_path, 0o600)
                    commit_path.write_text(
                        json.dumps(
                            marker_value,
                            ensure_ascii=False,
                            indent=2,
                        )
                        + "\n",
                        encoding="utf-8",
                    )
                    os.chmod(commit_path, 0o400)
                    target = artifact_paths[-1]
                    os.chmod(target, 0o600)
                    target.write_text("tampered\n", encoding="utf-8")
                    os.chmod(target, 0o400)
                    with self.assertRaisesRegex(
                        producer.ZeroDiscoverabilityError,
                        "differs from manifest",
                    ):
                        producer.validate_build_bundle_commit(context)
            finally:
                os.chmod(commit_path, 0o600)
                for directory in raw_directories:
                    os.chmod(directory, 0o700)
                for path in artifact_paths:
                    os.chmod(path, 0o600)

    def test_primary_has_one_exact_context_and_marker(self) -> None:
        context = {
            "runId": "w3-test-20260728",
            "sourceTreeHash": "b" * 64,
            "databaseIdentity": (
                "mysql8410:a1b2c3d4-1234-4abc-8def-1234567890ab:"
                "shenzhou_hr_test"
            ),
        }
        report = producer.build_primary_report(
            context=context,
            product_file_count=12,
            product_line_count=34,
            runtime_role_count=3,
            prod_dist_sha256="c" * 64,
            demo_dist_sha256="d" * 64,
        )
        self.assertEqual(report.count(producer.context_line(context)), 1)
        self.assertEqual(report.count(producer.PASS_MARKER), 1)

    def test_atomic_output_requires_exact_leaf_directory(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-output-") as root:
            run_root = Path(root) / "docs/verification/wave3/runs/w3-test"
            run_root.mkdir(parents=True)
            context = {"runRoot": run_root}
            requested = run_root / "leaves/payroll-zero/raw"
            output = producer.AtomicOutput(context, requested)
            (output.temporary_path / "fixture.txt").write_text(
                "safe\n", encoding="utf-8"
            )
            output.commit()
            self.assertTrue((requested / "fixture.txt").is_file())
            self.assertEqual(output.recovery_paths, [])
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "exactly"
            ):
                producer.AtomicOutput(
                    context, run_root / "leaves/wrong/raw"
                )

    def test_atomic_output_concurrent_publish_has_one_winner(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-race-") as root:
            run_root = Path(root) / "docs/verification/wave3/runs/w3-test"
            run_root.mkdir(parents=True)
            context = {"runRoot": run_root}
            requested = run_root / "leaves/payroll-zero/raw"
            outputs = [
                producer.AtomicOutput(context, requested),
                producer.AtomicOutput(context, requested),
            ]
            for index, output in enumerate(outputs):
                (output.temporary_path / "fixture.txt").write_text(
                    f"winner-{index}\n", encoding="utf-8"
                )
            barrier = threading.Barrier(2)
            outcomes: list[str] = []

            def publish(output: producer.AtomicOutput) -> None:
                barrier.wait()
                try:
                    output.commit()
                    outcomes.append("PASS")
                except producer.ZeroDiscoverabilityError:
                    outcomes.append("FAIL")

            threads = [
                threading.Thread(target=publish, args=(output,))
                for output in outputs
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(timeout=5)
                self.assertFalse(thread.is_alive())
            self.assertCountEqual(outcomes, ["PASS", "FAIL"])
            self.assertIn(
                (requested / "fixture.txt").read_text(encoding="utf-8"),
                {"winner-0\n", "winner-1\n"},
            )
            for output in outputs:
                output.cleanup()

    def test_atomic_output_rejects_empty_directory_and_symlink_races(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-noclobber-") as root:
            run_root = Path(root) / "docs/verification/wave3/runs/w3-test"
            run_root.mkdir(parents=True)
            context = {"runRoot": run_root}
            requested = run_root / "leaves/payroll-zero/raw"
            output = producer.AtomicOutput(context, requested)
            (output.temporary_path / "fixture.txt").write_text(
                "safe\n", encoding="utf-8"
            )
            requested.mkdir()
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "appeared"
            ):
                output.commit()
            self.assertTrue(requested.is_dir())
            output.cleanup()
            self.assertFalse(output.temporary_path.exists())
            self.assertEqual(len(output.recovery_paths), 1)
            self.assertEqual(
                (output.recovery_paths[0] / "fixture.txt").read_text(
                    encoding="utf-8"
                ),
                "safe\n",
            )

        with tempfile.TemporaryDirectory(prefix="w3-zero-symlink-") as root:
            run_root = Path(root) / "docs/verification/wave3/runs/w3-test"
            run_root.mkdir(parents=True)
            context = {"runRoot": run_root}
            requested = run_root / "leaves/payroll-zero/raw"
            sentinel = Path(root) / "sentinel"
            sentinel.write_text("untouched\n", encoding="utf-8")
            output = producer.AtomicOutput(context, requested)
            (output.temporary_path / "fixture.txt").write_text(
                "safe\n", encoding="utf-8"
            )
            native_rename = producer.darwin_rename_exclusive

            def inject_symlink(*arguments: object) -> None:
                requested.symlink_to(sentinel)
                native_rename(*arguments)

            with (
                patch.object(
                    producer,
                    "darwin_rename_exclusive",
                    side_effect=inject_symlink,
                ),
                self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError, "appeared"
                ),
            ):
                output.commit()
            self.assertTrue(requested.is_symlink())
            self.assertEqual(sentinel.read_text(encoding="utf-8"), "untouched\n")
            output.cleanup()

    def test_atomic_output_rollback_refuses_replaced_identity(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w3-zero-rollback-") as root:
            run_root = Path(root) / "docs/verification/wave3/runs/w3-test"
            run_root.mkdir(parents=True)
            context = {"runRoot": run_root}
            requested = run_root / "leaves/payroll-zero/raw"
            output = producer.AtomicOutput(context, requested)
            (output.temporary_path / "fixture.txt").write_text(
                "original\n", encoding="utf-8"
            )
            output.commit()
            original = requested.with_name("original-committed")
            requested.rename(original)
            requested.mkdir()
            (requested / "replacement.txt").write_text(
                "keep\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError, "identity changed"
            ):
                output.rollback_committed()
            self.assertEqual(
                (requested / "replacement.txt").read_text(encoding="utf-8"),
                "keep\n",
            )

    def test_atomic_output_rollback_preserves_replacement_after_identity_check(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="w3-zero-rollback-race-"
        ) as root:
            run_root = Path(root) / "docs/verification/wave3/runs/w3-test"
            run_root.mkdir(parents=True)
            context = {"runRoot": run_root}
            requested = run_root / "leaves/payroll-zero/raw"
            output = producer.AtomicOutput(context, requested)
            (output.temporary_path / "fixture.txt").write_text(
                "original\n", encoding="utf-8"
            )
            output.commit()
            original = requested.with_name("original-committed")
            native_rename = producer.darwin_rename_exclusive
            injected = False

            def inject_replacement(
                source_parent_fd: int,
                source_name: str,
                destination_parent_fd: int,
                destination_name: str,
            ) -> None:
                nonlocal injected
                if (
                    not injected
                    and source_name == requested.name
                    and destination_name.startswith(".zero-recovery-")
                ):
                    injected = True
                    requested.rename(original)
                    requested.mkdir()
                    (requested / "replacement.txt").write_text(
                        "keep\n", encoding="utf-8"
                    )
                native_rename(
                    source_parent_fd,
                    source_name,
                    destination_parent_fd,
                    destination_name,
                )

            with (
                patch.object(
                    producer,
                    "darwin_rename_exclusive",
                    side_effect=inject_replacement,
                ),
                self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError,
                    "foreign replacement preserved",
                ),
            ):
                output.rollback_committed()
            self.assertTrue(injected)
            self.assertEqual(
                (requested / "replacement.txt").read_text(encoding="utf-8"),
                "keep\n",
            )
            self.assertEqual(
                (original / "fixture.txt").read_text(encoding="utf-8"),
                "original\n",
            )

    def test_atomic_output_recovery_never_uses_final_rmdir_mutant(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="w3-zero-recovery-rmdir-"
        ) as root:
            run_root = Path(root) / "docs/verification/wave3/runs/w3-test"
            run_root.mkdir(parents=True)
            context = {"runRoot": run_root}
            requested = run_root / "leaves/payroll-zero/raw"
            output = producer.AtomicOutput(context, requested)
            (output.temporary_path / "fixture.txt").write_text(
                "owned\n", encoding="utf-8"
            )
            output.commit()
            native_rename = producer.darwin_rename_exclusive
            injected = False

            def inject_foreign_after_recovery(
                source_parent_fd: int,
                source_name: str,
                destination_parent_fd: int,
                destination_name: str,
            ) -> None:
                nonlocal injected
                native_rename(
                    source_parent_fd,
                    source_name,
                    destination_parent_fd,
                    destination_name,
                )
                if (
                    not injected
                    and source_name == requested.name
                    and destination_name.startswith(".zero-recovery-")
                ):
                    injected = True
                    os.mkdir(source_name, mode=0o700, dir_fd=source_parent_fd)
                    foreign_directory_fd = os.open(
                        source_name,
                        producer.directory_open_flags(),
                        dir_fd=source_parent_fd,
                    )
                    try:
                        foreign_fd = os.open(
                            "foreign.txt",
                            os.O_WRONLY
                            | os.O_CREAT
                            | os.O_EXCL
                            | getattr(os, "O_NOFOLLOW", 0),
                            0o600,
                            dir_fd=foreign_directory_fd,
                        )
                        try:
                            os.write(foreign_fd, b"foreign\n")
                        finally:
                            os.close(foreign_fd)
                    finally:
                        os.close(foreign_directory_fd)

            with (
                patch.object(
                    producer.os,
                    "rmdir",
                    side_effect=AssertionError("rmdir must never be called"),
                ) as rmdir_call,
                patch.object(
                    producer,
                    "darwin_rename_exclusive",
                    side_effect=inject_foreign_after_recovery,
                ),
                self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError,
                    "source path was replaced.*foreign replacement preserved",
                ),
            ):
                output.rollback_committed()
            rmdir_call.assert_not_called()
            self.assertTrue(injected)
            self.assertFalse(output.committed)
            self.assertEqual(
                (requested / "foreign.txt").read_text(encoding="utf-8"),
                "foreign\n",
            )
            self.assertEqual(len(output.recovery_paths), 1)
            self.assertEqual(
                (
                    output.recovery_paths[0] / "fixture.txt"
                ).read_text(encoding="utf-8"),
                "owned\n",
            )

    def test_atomic_output_recovery_never_uses_file_unlink_mutant(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="w3-zero-recovery-unlink-"
        ) as root:
            run_root = Path(root) / "docs/verification/wave3/runs/w3-test"
            run_root.mkdir(parents=True)
            context = {"runRoot": run_root}
            requested = run_root / "leaves/payroll-zero/raw"
            output = producer.AtomicOutput(context, requested)
            deep = output.temporary_path / "level-one/level-two"
            deep.mkdir(parents=True)
            (deep / "fixture.txt").write_text(
                "owned\n", encoding="utf-8"
            )
            (deep / "stable-owned.txt").write_text(
                "stable\n", encoding="utf-8"
            )
            sibling = output.temporary_path / "sibling"
            sibling.mkdir()
            (sibling / "also-owned.txt").write_text(
                "also-stable\n", encoding="utf-8"
            )
            output.commit()
            native_rename = producer.darwin_rename_exclusive
            injected = False

            def inject_file_replacement_in_recovery(
                source_parent_fd: int,
                source_name: str,
                destination_parent_fd: int,
                destination_name: str,
            ) -> None:
                nonlocal injected
                native_rename(
                    source_parent_fd,
                    source_name,
                    destination_parent_fd,
                    destination_name,
                )
                if (
                    not injected
                    and source_name == requested.name
                    and destination_name.startswith(".zero-recovery-")
                ):
                    injected = True
                    recovery_fd = os.open(
                        destination_name,
                        producer.directory_open_flags(),
                        dir_fd=destination_parent_fd,
                    )
                    level_one_fd = -1
                    level_two_fd = -1
                    try:
                        level_one_fd = os.open(
                            "level-one",
                            producer.directory_open_flags(),
                            dir_fd=recovery_fd,
                        )
                        level_two_fd = os.open(
                            "level-two",
                            producer.directory_open_flags(),
                            dir_fd=level_one_fd,
                        )
                        os.rename(
                            "fixture.txt",
                            "owned-original.txt",
                            src_dir_fd=level_two_fd,
                            dst_dir_fd=level_two_fd,
                        )
                        foreign_fd = os.open(
                            "fixture.txt",
                            os.O_WRONLY
                            | os.O_CREAT
                            | os.O_EXCL
                            | getattr(os, "O_NOFOLLOW", 0),
                            0o600,
                            dir_fd=level_two_fd,
                        )
                        try:
                            os.write(foreign_fd, b"foreign\n")
                        finally:
                            os.close(foreign_fd)
                    finally:
                        if level_two_fd >= 0:
                            os.close(level_two_fd)
                        if level_one_fd >= 0:
                            os.close(level_one_fd)
                        os.close(recovery_fd)

            with (
                patch.object(
                    producer.os,
                    "unlink",
                    side_effect=AssertionError("unlink must never be called"),
                ) as unlink_call,
                patch.object(
                    producer.os,
                    "rmdir",
                    side_effect=AssertionError("rmdir must never be called"),
                ) as rmdir_call,
                patch.object(
                    producer,
                    "darwin_rename_exclusive",
                    side_effect=inject_file_replacement_in_recovery,
                ),
            ):
                output.rollback_committed()
            unlink_call.assert_not_called()
            rmdir_call.assert_not_called()
            self.assertTrue(injected)
            self.assertFalse(requested.exists())
            self.assertFalse(output.committed)
            self.assertEqual(len(output.recovery_paths), 1)
            recovery = output.recovery_paths[0]
            self.assertEqual(
                (
                    recovery / "level-one/level-two/owned-original.txt"
                ).read_text(encoding="utf-8"),
                "owned\n",
            )
            self.assertEqual(
                (
                    recovery / "level-one/level-two/fixture.txt"
                ).read_text(encoding="utf-8"),
                "foreign\n",
            )
            self.assertEqual(
                (
                    recovery / "level-one/level-two/stable-owned.txt"
                ).read_text(encoding="utf-8"),
                "stable\n",
            )
            self.assertEqual(
                (
                    recovery / "sibling/also-owned.txt"
                ).read_text(encoding="utf-8"),
                "also-stable\n",
            )
            with self.assertRaisesRegex(
                producer.ZeroDiscoverabilityError,
                "fresh run.*manually recover",
            ):
                producer.AtomicOutput(context, requested)

    def test_atomic_output_final_parent_fsync_failure_rolls_back_and_resyncs(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(
            prefix="w3-zero-final-fsync-"
        ) as root:
            run_root = Path(root) / "docs/verification/wave3/runs/w3-test"
            run_root.mkdir(parents=True)
            context = {"runRoot": run_root}
            requested = run_root / "leaves/payroll-zero/raw"
            output = producer.AtomicOutput(context, requested)
            (output.temporary_path / "fixture.txt").write_text(
                "safe\n", encoding="utf-8"
            )
            parent_fd = output._parent_fd
            native_fsync = producer.os.fsync
            parent_fsync_calls = 0

            def fail_second_parent_fsync(descriptor: int) -> None:
                nonlocal parent_fsync_calls
                if descriptor == parent_fd:
                    parent_fsync_calls += 1
                    if parent_fsync_calls == 2:
                        raise OSError(errno.EIO, "injected final fsync failure")
                native_fsync(descriptor)

            with (
                patch.object(
                    producer.os,
                    "fsync",
                    side_effect=fail_second_parent_fsync,
                ),
                self.assertRaisesRegex(
                    producer.ZeroDiscoverabilityError,
                    "final parent fsync failed.*non-PASS recovery",
                ),
            ):
                output.commit()
            self.assertGreaterEqual(parent_fsync_calls, 3)
            self.assertFalse(requested.exists())
            self.assertFalse(requested.is_symlink())
            self.assertFalse(output.temporary_path.exists())
            self.assertFalse(output.committed)
            self.assertEqual(len(output.recovery_paths), 1)
            recovery = output.recovery_paths[0]
            self.assertEqual(
                (recovery / "fixture.txt").read_text(encoding="utf-8"),
                "safe\n",
            )
            self.assertEqual(recovery.stat().st_mode & 0o777, 0o700)

    def test_structured_null_and_status_token_are_rejected(self) -> None:
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "JSON null"
        ):
            producer.assert_no_null_or_forbidden({"value": None}, "fixture")
        with self.assertRaisesRegex(
            producer.ZeroDiscoverabilityError, "status token"
        ):
            producer.assert_no_null_or_forbidden(
                {"value": "NOT_VERIFIED"}, "fixture"
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
