#!/usr/bin/env python3
"""Fail-closed mutant tests for shared W3 runtime provenance."""

from __future__ import annotations

import copy
import shutil
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.qa import verify_wave3_payroll_zero as payroll
from scripts.qa import wave3_runtime_provenance as provenance


class Wave3RuntimeProvenanceTest(unittest.TestCase):
    def _frontend_binding_fixture(
        self,
    ) -> tuple[dict, dict]:
        digests = {
            key: character * 64
            for key, character in (
                ("commit", "a"),
                ("bundle", "b"),
                ("workspace", "c"),
                ("dependency", "d"),
                ("support", "e"),
                ("runtime", "f"),
                ("node", "1"),
                ("vite", "2"),
            )
        }
        listener = {
            "processStartedAtEpochNs": 200,
            "processBinding": {
                "executableRealpath": "/safe/bin/node",
                "executableSha256": digests["node"],
                "viteEntryRealpath": "/repo/frontend/node_modules/vite/bin/vite.js",
                "viteEntrySizeBytes": 123,
                "viteEntrySha256": digests["vite"],
            },
        }
        receipt = {
            "buildBundleCommitSha256": digests["commit"],
            "buildBundleManifestSha256": digests["bundle"],
            "buildCompletedAtEpochNs": 100,
            "toolchain": {
                "workspaceManifestSha256": digests["workspace"],
                "dependencyManifestSha256": digests["dependency"],
                "commandSupportManifestSha256": digests["support"],
                "toolRuntimeManifestSha256": digests["runtime"],
                "nodeExecutableRealpath": "/safe/bin/node",
                "nodeExecutableSha256": digests["node"],
                "frontendDependencyBinding": {
                    "dependencyManifestSha256": digests["dependency"],
                    "viteEntryRealpath": (
                        "/repo/frontend/node_modules/vite/bin/vite.js"
                    ),
                    "viteEntrySizeBytes": 123,
                    "viteEntrySha256": digests["vite"],
                },
            },
        }
        return listener, receipt

    def test_frontend_process_build_binding_rejects_other_valid_bytes(
        self,
    ) -> None:
        listener, receipt = self._frontend_binding_fixture()
        for label in ("normal frontend", "demo preview"):
            with self.subTest(label=label):
                result = provenance.validate_frontend_process_build_binding(
                    listener, receipt, label=label
                )
                self.assertEqual(result["verdict"], "PASS")
                other_node = copy.deepcopy(listener)
                other_node["processBinding"].update(
                    {
                        "executableRealpath": "/other/valid/bin/node",
                        "executableSha256": "3" * 64,
                    }
                )
                with self.assertRaisesRegex(
                    provenance.RuntimeProvenanceError,
                    "differ from the committed",
                ):
                    provenance.validate_frontend_process_build_binding(
                        other_node, receipt, label=label
                    )
                other_vite = copy.deepcopy(listener)
                other_vite["processBinding"].update(
                    {
                        "viteEntryRealpath": (
                            "/other/valid/node_modules/vite/bin/vite.js"
                        ),
                        "viteEntrySha256": "4" * 64,
                    }
                )
                with self.assertRaisesRegex(
                    provenance.RuntimeProvenanceError,
                    "differ from the committed",
                ):
                    provenance.validate_frontend_process_build_binding(
                        other_vite, receipt, label=label
                    )
                stale = copy.deepcopy(listener)
                stale["processStartedAtEpochNs"] = 99
                with self.assertRaisesRegex(
                    provenance.RuntimeProvenanceError, "predates"
                ):
                    provenance.validate_frontend_process_build_binding(
                        stale, receipt, label=label
                    )

    def test_demo_dist_receipt_rejects_content_and_bool_mutants(self) -> None:
        records = [
            {
                "path": "assets/app.js",
                "sizeBytes": 4,
                "sha256": payroll.sha256_bytes(b"safe"),
            }
        ]
        content_tree = payroll.sha256_bytes(
            b"".join(
                payroll.canonical_json_bytes(record)
                for record in records
            )
        )
        receipt = {
            "distFileCount": 1,
            "distContentTreeSha256": content_tree,
        }
        self.assertEqual(
            provenance.validate_dist_records_against_receipt(
                records, receipt
            )["contentTreeSha256"],
            content_tree,
        )
        changed = copy.deepcopy(records)
        changed[0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(
            provenance.RuntimeProvenanceError, "committed"
        ):
            provenance.validate_dist_records_against_receipt(
                changed, receipt
            )
        boolean_size = copy.deepcopy(records)
        boolean_size[0]["sizeBytes"] = True
        with self.assertRaisesRegex(
            provenance.RuntimeProvenanceError, "invalid"
        ):
            provenance.validate_dist_records_against_receipt(
                boolean_size, receipt
            )

    def test_private_process_material_is_removed_recursively(self) -> None:
        value = {
            "safe": 1,
            "_privateArgv": ["--secret"],
            "nested": {
                "_privateEnvironmentEntries": ["PASSWORD=secret"],
                "safe": "PASS",
            },
        }
        public = provenance.strip_private_keys(value)
        self.assertEqual(public, {"safe": 1, "nested": {"safe": "PASS"}})
        provenance.assert_public_document(public)
        with self.assertRaisesRegex(
            provenance.RuntimeProvenanceError, "private"
        ):
            provenance.assert_public_document(value)

    def test_normal_vite_cache_binds_complete_fresh_tree(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="w3-runtime-cache-"
        ) as temporary:
            repository = Path(temporary)
            deps = (
                repository
                / "frontend/node_modules/.cache/vite/deps"
            )
            deps.mkdir(parents=True)
            (repository / "frontend/vite.config.ts").write_text(
                "export default { cacheDir: 'node_modules/.cache/vite', };\n",
                encoding="utf-8",
            )
            (deps / "_metadata.json").write_text(
                "{}\n", encoding="utf-8"
            )
            (deps / "package.json").write_text(
                '{"type":"module"}\n', encoding="utf-8"
            )
            (deps / "app.js").write_text(
                "export default 1;\n", encoding="utf-8"
            )
            with patch.object(
                payroll, "REPOSITORY_ROOT", repository
            ):
                binding = provenance.normal_vite_cache_binding(
                    {"processStartedAtEpochNs": 1}
                )
                self.assertEqual(binding["fileCount"], 3)
                self.assertEqual(len(binding["records"]), 3)
                with self.assertRaisesRegex(
                    provenance.RuntimeProvenanceError, "predates"
                ):
                    provenance.normal_vite_cache_binding(
                        {
                            "processStartedAtEpochNs": (
                                time.time_ns() + 1_000_000_000
                            )
                        }
                    )
                (
                    repository
                    / "frontend/node_modules/.cache/vite/outside.txt"
                ).write_text("unsafe\n", encoding="utf-8")
                with self.assertRaisesRegex(
                    provenance.RuntimeProvenanceError, "fixed deps closure"
                ):
                    provenance.normal_vite_cache_binding(
                        {"processStartedAtEpochNs": 1}
                    )

    def test_bound_esm_parser_and_target_resolution_fail_closed(self) -> None:
        node = shutil.which("node")
        self.assertIsNotNone(node)
        assert node is not None
        rows = provenance.parse_module_imports(
            (
                'import value from "/src/value.js";\n'
                'const lazy = import("./lazy.js");\n'
                "const runtime = import(resolveRuntimeModule());\n"
            ),
            node_executable=str(Path(node).resolve(strict=True)),
        )
        self.assertEqual(
            [
                row["specifier"]
                for row in rows
                if "specifier" in row
            ],
            ["/src/value.js", "./lazy.js"],
        )
        nonliteral = [
            row
            for row in rows
            if row.get("kind") == "nonliteral-dynamic"
        ]
        self.assertEqual(len(nonliteral), 1)
        self.assertEqual(
            set(nonliteral[0]),
            {
                "kind",
                "expressionStart",
                "expressionEnd",
                "expressionSizeBytes",
                "expressionSha256",
                "importStatementStart",
                "importStatementEnd",
                "importStatementSizeBytes",
                "importStatementSha256",
                "viteIgnorePresent",
                "viteIgnoreMarkerStart",
                "viteIgnoreMarkerEnd",
            },
        )
        self.assertGreater(nonliteral[0]["expressionSizeBytes"], 0)
        self.assertFalse(nonliteral[0]["viteIgnorePresent"])
        self.assertEqual(nonliteral[0]["viteIgnoreMarkerStart"], -1)
        self.assertEqual(nonliteral[0]["viteIgnoreMarkerEnd"], -1)
        self.assertTrue(
            payroll.SHA256_PATTERN.fullmatch(
                nonliteral[0]["expressionSha256"]
            )
        )
        self.assertEqual(
            provenance.validate_nonliteral_dynamic_import_parent(
                "/@vite/client", nonliteral[0]
            ),
            "exact-sealed-vite-client-parent",
        )
        for unsafe_parent in (
            "/src/main.tsx",
            "/node_modules/arbitrary/raw.js",
            "/node_modules/.cache/vite/deps/runtime.js?v=1",
        ):
            with (
                self.subTest(nonliteral_parent=unsafe_parent),
                self.assertRaises(provenance.RuntimeProvenanceError),
            ):
                provenance.validate_nonliteral_dynamic_import_parent(
                    unsafe_parent, nonliteral[0]
                )
        marked_rows = provenance.parse_module_imports(
            "import(/* @vite-ignore */ runtimePath)",
            node_executable=str(Path(node).resolve(strict=True)),
        )
        marked = marked_rows[0]
        self.assertTrue(marked["viteIgnorePresent"])
        self.assertEqual(
            provenance.validate_nonliteral_dynamic_import_parent(
                "/node_modules/.cache/vite/deps/runtime.js?v=1",
                marked,
            ),
            "exact-sealed-vite-cache-dependency-parent",
        )
        mutant_output = payroll.canonical_json_bytes(
            [
                {
                    "kind": "nonliteral-dynamic",
                    "expressionStart": 7,
                    "expressionEnd": 18,
                    "expressionSizeBytes": 11,
                    "expressionSha256": "0" * 64,
                    "importStatementStart": 0,
                    "importStatementEnd": 19,
                    "importStatementSizeBytes": 19,
                    "importStatementSha256": payroll.sha256_bytes(
                        b"import(runtimePath)"
                    ),
                    "viteIgnorePresent": False,
                    "viteIgnoreMarkerStart": -1,
                    "viteIgnoreMarkerEnd": -1,
                }
            ]
        ).decode("utf-8")
        with (
            patch.object(
                provenance.subprocess,
                "run",
                return_value=provenance.subprocess.CompletedProcess(
                    args=(),
                    returncode=0,
                    stdout=mutant_output,
                    stderr="",
                ),
            ),
            self.assertRaisesRegex(
                provenance.RuntimeProvenanceError,
                "does not match the parent module body",
            ),
        ):
            provenance.parse_module_imports(
                "import(runtimePath)",
                node_executable=str(Path(node).resolve(strict=True)),
            )
        self.assertEqual(
            provenance.resolve_module_target(
                "http://127.0.0.1:5173",
                "/src/main.tsx",
                "./app/App.tsx",
            ),
            "/src/app/App.tsx",
        )
        self.assertEqual(
            provenance.resolve_module_target(
                "http://127.0.0.1:5173",
                "/src/main.tsx",
                (
                    "/node_modules/.cache/vite/deps/"
                    "react.js?v=1234"
                ),
            ),
            "/node_modules/.cache/vite/deps/react.js?v=1234",
        )
        self.assertEqual(
            provenance.resolve_module_target(
                "http://127.0.0.1:5173",
                "/src/main.tsx",
                "/node_modules/antd/dist/reset.css",
            ),
            "/node_modules/antd/dist/reset.css",
        )
        self.assertEqual(
            provenance.resolve_module_target(
                "http://127.0.0.1:5173",
                "/@vite/client",
                "/@vite/env",
            ),
            "/@vite/env",
        )
        for mutant in (
            "react",
            "http://127.0.0.1:8080/api/v1/session",
            "/@fs/etc/passwd",
            "/@id/unknown",
            "/unknown-same-origin.js",
            "/node_modules/.vite/deps/react.js?v=legacy",
            "/node_modules/.vite-temp/config.js",
        ):
            with self.subTest(import_target=mutant), self.assertRaises(
                provenance.RuntimeProvenanceError
            ):
                provenance.resolve_module_target(
                    "http://127.0.0.1:5173",
                    "/src/main.tsx",
                    mutant,
                )

    def test_transformed_graph_total_byte_bound_is_exact(self) -> None:
        with (
            patch.object(
                provenance, "MAX_TRANSFORMED_GRAPH_BYTES", 4
            ),
            patch.object(
                provenance,
                "parse_module_imports",
                return_value=[],
            ),
            patch.object(
                provenance,
                "fetch_transformed_module_text",
                return_value="aa",
            ),
        ):
            binding = provenance.verify_transformed_module_graph(
                "http://127.0.0.1:5173",
                node_executable="/bound/node",
            )
            self.assertEqual(binding["totalBodyBytes"], 4)
        with (
            patch.object(
                provenance, "MAX_TRANSFORMED_GRAPH_BYTES", 4
            ),
            patch.object(
                provenance,
                "parse_module_imports",
                return_value=[],
            ),
            patch.object(
                provenance,
                "fetch_transformed_module_text",
                return_value="aaa",
            ),
        ):
            with self.assertRaisesRegex(
                provenance.RuntimeProvenanceError, "byte bound"
            ):
                provenance.verify_transformed_module_graph(
                    "http://127.0.0.1:5173",
                    node_executable="/bound/node",
                )

    def test_transformed_module_byte_bound_accepts_large_current_shape(
        self,
    ) -> None:
        class FakeResponse:
            status = 200
            headers: dict[str, str] = {}

            def __init__(self, size: int, url: str) -> None:
                self.size = size
                self.url = url

            def read(self, limit: int) -> bytes:
                return b"x" * min(self.size, limit)

            def geturl(self) -> str:
                return self.url

        class FakeOpener:
            def __init__(self, size: int, url: str) -> None:
                self.size = size
                self.url = url

            def open(self, _request: object, *, timeout: int) -> FakeResponse:
                self.observed_timeout = timeout
                return FakeResponse(self.size, self.url)

        origin = "http://127.0.0.1:5173"
        target = "/node_modules/.cache/vite/deps/antd.js?v=current"
        self.assertEqual(
            provenance.MAX_TRANSFORMED_MODULE_BYTES,
            24 * 1024 * 1024,
        )
        for accepted_size in (
            8 * 1024 * 1024 + 1,
            provenance.MAX_TRANSFORMED_MODULE_BYTES,
        ):
            with (
                self.subTest(accepted_size=accepted_size),
                patch.object(
                    payroll,
                    "HTTP_OPENER",
                    FakeOpener(accepted_size, origin + target),
                ),
            ):
                body = provenance.fetch_transformed_module_text(
                    origin, target
                )
                self.assertEqual(len(body.encode("utf-8")), accepted_size)
        rejected_size = provenance.MAX_TRANSFORMED_MODULE_BYTES + 1
        with (
            patch.object(
                payroll,
                "HTTP_OPENER",
                FakeOpener(rejected_size, origin + target),
            ),
            self.assertRaisesRegex(
                provenance.RuntimeProvenanceError,
                (
                    r"target=/node_modules/\.cache/vite/deps/"
                    r"antd\.js\?v=current "
                    rf"observedSizeBytes={rejected_size} "
                    rf"exceeds boundBytes="
                    rf"{provenance.MAX_TRANSFORMED_MODULE_BYTES}"
                ),
            ),
        ):
            provenance.fetch_transformed_module_text(origin, target)

    def test_backend_class_receipt_rejects_bool_metadata(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="w3-runtime-classes-"
        ) as temporary:
            repository = Path(temporary)
            classes = repository / "backend/target/classes"
            classes.mkdir(parents=True)
            snapshot = {
                "rootRealpath": classes.resolve(strict=True).as_posix(),
                "fileCount": 1,
                "records": [
                    {
                        "path": (
                            "com/szsemicon/hr/"
                            "ShenzhouHrApplication.class"
                        ),
                        "mode": 0o644,
                        "sizeBytes": 4,
                        "sha256": payroll.sha256_bytes(b"main"),
                    }
                ],
            }
            with patch.object(
                payroll, "REPOSITORY_ROOT", repository
            ):
                receipt = (
                    payroll.backend_runtime_classes_receipt_from_snapshot(
                        snapshot
                    )
                )
                self.assertEqual(receipt["classFileCount"], 1)
                mutant = copy.deepcopy(snapshot)
                mutant["records"][0]["mode"] = True
                with self.assertRaisesRegex(
                    payroll.ZeroDiscoverabilityError, "invalid logical"
                ):
                    payroll.backend_runtime_classes_receipt_from_snapshot(
                        mutant
                    )

    def test_demo_preview_argv_requires_runner_in_fixed_order(self) -> None:
        executable = Path("/safe/bin/node")
        vite_entry = Path(
            "/repo/frontend/node_modules/vite/bin/vite.js"
        )
        exact = (
            "node",
            vite_entry.as_posix(),
            "preview",
            "--configLoader",
            "runner",
            "--host",
            "127.0.0.1",
            "--port",
            "4175",
            "--strictPort",
            "--outDir",
            "dist/demo",
        )
        payroll.validate_demo_frontend_argv_contract(
            exact, executable, vite_entry
        )
        without_runner = tuple(
            value
            for value in exact
            if value not in {"--configLoader", "runner"}
        )
        with self.assertRaisesRegex(
            payroll.ZeroDiscoverabilityError, "exact built-preview"
        ):
            payroll.validate_demo_frontend_argv_contract(
                without_runner, executable, vite_entry
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
