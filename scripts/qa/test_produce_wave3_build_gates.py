#!/usr/bin/env python3
"""Filesystem/process mutant tests for the W3 build-gate producer."""

from __future__ import annotations

import base64
import copy
import hashlib
import json
import os
import re
import select
import shutil
import stat
import sys
import tempfile
import time
import types
import unittest
import zlib
from contextlib import contextmanager
from pathlib import Path
from unittest import mock

from scripts.qa.produce_wave3_build_gates import (
    BACKEND_ROOT,
    COMMIT_FILENAME,
    COMMAND_SUPPORT_FILES,
    EXCLUDED_NODE_CACHE_DIRECTORIES,
    FRONTEND_ROOT,
    GENERATED_COMMAND_SUPPORT_FILES,
    LOCK_FILENAME,
    BuildGateError,
    CommandResult,
    GATES,
    REPOSITORY_ROOT,
    WorkspaceSeal,
    assert_destinations_absent,
    assert_nofollow_chain,
    acquire_run_lock,
    backend_runtime_classes_receipt,
    canonical_json,
    command_support_manifest,
    commit_outputs,
    copy_command_support_files,
    discover_java_tests,
    discover_vitest_files,
    dist_inventory,
    deterministic_command_path,
    frontend_environment,
    frontend_command_path,
    host_tool_paths,
    exclusive_rename,
    freeze_stage,
    internal_npm_bin_symlink_record,
    isolated_node_cache_anchors,
    java_runtime_root,
    maven_command,
    maven_command_path,
    maven_environment,
    npm_runtime_root,
    dependency_handoff_manifest,
    parse_surefire_reports,
    parse_vitest_report,
    produce_backend_gate,
    release_run_lock_from_bundle,
    require_equal_command_support,
    require_equal_dependency_handoff,
    require_isolated_source_snapshot,
    read_regular_file,
    resolve_executable,
    run_command,
    secure_remove_tree,
    self_test,
    structured_test_summary,
    stable_tool_runtime_manifest,
    stable_repository_command_support_manifest,
    utc_iso_from_ns,
    validate_compressed_archive,
    validate_existing_complete_bundle,
    validate_gate_contract,
    validate_isolated_node_cache_anchor_receipt,
)
from scripts.qa.verify_wave3_evidence import Wave3Evidence


class Wave3BuildGateProducerTest(unittest.TestCase):
    def test_01_fixed_gate_set_matches_strict_evidence_contract(self) -> None:
        verifier = Wave3Evidence(REPOSITORY_ROOT)
        validate_gate_contract(verifier)
        self.assertEqual(len(GATES), 9)
        self.assertEqual(
            self_test()["marker"],
            (
                "W3_BUILD_GATE_PRODUCER_SELF_TEST=PASS tests=41 gates=9 "
                "backendClasses=39 frontendFiles=26"
            ),
        )

    def test_02_full_maven_cleans_and_targeted_uses_exact_fqcns(self) -> None:
        self.assertEqual(
            maven_command(None), ["./mvnw", "--offline", "clean", "test"]
        )
        self.assertEqual(
            maven_command(("a.AlphaTest", "b.BetaTest")),
            [
                "./mvnw",
                "--offline",
                "-Dtest=a.AlphaTest,b.BetaTest",
                "test",
            ],
        )
        self.assertEqual(
            resolve_executable("./mvnw", BACKEND_ROOT),
            (BACKEND_ROOT / "mvnw").resolve(strict=True),
        )
        isolated_maven_home = REPOSITORY_ROOT / ".test-isolated-m2"
        workspace = isolated_maven_home.parent
        self.assertEqual(
            maven_environment(isolated_maven_home),
            {
                "HOME": str(workspace),
                "PATH": maven_command_path(),
                "TMPDIR": str(workspace / "frontend/node_modules/.tmp"),
                "MAVEN_USER_HOME": str(isolated_maven_home),
                "MAVEN_SKIP_RC": "1",
                "MAVEN_OPTS": (
                    f"-Duser.home={workspace} "
                    "-Dmaven.clean.excludeDefaultDirectories=true"
                ),
            },
        )
        isolated_frontend = workspace / "frontend"
        self.assertEqual(
            frontend_environment(isolated_frontend),
            {
                "HOME": str(workspace),
                "PATH": frontend_command_path(),
                "TMPDIR": str(isolated_frontend / "node_modules/.tmp"),
                "NPM_CONFIG_CACHE": str(
                    isolated_frontend / "node_modules/.cache"
                ),
                "NPM_CONFIG_USERCONFIG": str(
                    workspace / ".command-inputs/npm-userconfig"
                ),
                "NPM_CONFIG_GLOBALCONFIG": str(
                    workspace / ".command-inputs/npm-globalconfig"
                ),
            },
        )
        java_path = shutil.which("java")
        npm_path = shutil.which("npm")
        self.assertIsNotNone(java_path)
        self.assertIsNotNone(npm_path)
        assert java_path is not None
        assert npm_path is not None
        self.assertTrue(
            java_runtime_root(
                Path(java_path).resolve(strict=True)
            ).joinpath("lib/modules").is_file()
        )
        self.assertTrue(
            npm_runtime_root(
                Path(npm_path).resolve(strict=True)
            ).joinpath("lib/cli.js").is_file()
        )
        fixed_path = deterministic_command_path().split(os.pathsep)
        tools = host_tool_paths()
        self.assertEqual(
            fixed_path,
            ["/usr/bin", "/bin", "/usr/sbin", "/sbin"],
        )
        self.assertEqual(
            maven_command_path().split(os.pathsep)[0],
            tools["java"].parent.as_posix(),
        )
        self.assertEqual(
            frontend_command_path().split(os.pathsep)[0],
            tools["node"].parent.as_posix(),
        )
        self.assertNotIn(
            tools["java"].parent.as_posix(),
            frontend_command_path().split(os.pathsep),
        )
        self.assertNotIn(
            tools["node"].parent.as_posix(),
            maven_command_path().split(os.pathsep),
        )
        self.assertNotIn(str(Path.home() / ".dotnet"), fixed_path)
        self.assertNotIn(str(Path.home() / ".local/bin"), fixed_path)
        scripts = json.loads(
            (FRONTEND_ROOT / "package.json").read_text(encoding="utf-8")
        )["scripts"]
        self.assertEqual(
            scripts["typecheck"],
            "tsc -b --force --pretty false",
        )
        self.assertEqual(
            scripts["test"],
            "vitest run --configLoader runner --cache=false",
        )
        self.assertIn("tsc -b --force", scripts["build"])
        self.assertIn("--configLoader runner", scripts["build"])
        self.assertIn("--configLoader runner", scripts["build:demo"])
        vite_config = (FRONTEND_ROOT / "vite.config.ts").read_text(
            encoding="utf-8"
        )
        self.assertIn("cacheDir: 'node_modules/.cache/vite'", vite_config)
        self.assertIn("client: { enabled: false }", vite_config)
        self.assertIn("ssr: { enabled: false }", vite_config)
        self.assertIn("fsModuleCache: false", vite_config)
        sealed_tool_runtime = stable_tool_runtime_manifest(tools)
        self.assertEqual(
            sealed_tool_runtime["toolPaths"]["node"],
            tools["node"].as_posix(),
        )
        self.assertGreater(
            sealed_tool_runtime["java"]["recordCount"],
            0,
        )
        self.assertGreater(
            sealed_tool_runtime["npmDependencies"]["recordCount"],
            0,
        )
        sealed_support = stable_repository_command_support_manifest()
        self.assertEqual(
            sealed_support["recordCount"],
            len(COMMAND_SUPPORT_FILES) + len(GENERATED_COMMAND_SUPPORT_FILES),
        )
        self.assertEqual(
            sealed_support,
            command_support_manifest(
                REPOSITORY_ROOT,
                repository_layout=True,
            ),
        )

    def test_03_test_source_discovery_has_reviewed_exact_counts(self) -> None:
        backend = discover_java_tests(BACKEND_ROOT / "src/test/java")
        frontend = discover_vitest_files()
        self.assertEqual(len(backend), 39)
        self.assertEqual(len(frontend), 26)
        self.assertTrue(all("." in name and name.endswith("Test") for name in backend))
        self.assertEqual(len(set(backend)), len(backend))
        self.assertEqual(len(set(frontend)), len(frontend))

    def test_04_surefire_requires_exact_fqcn_closure(self) -> None:
        with self._repo_temporary("w3-surefire-closure-") as reports:
            self._write_suite(reports / "TEST-a.AlphaTest.xml", "a.AlphaTest", 2)
            self._write_suite(reports / "TEST-b.BetaTest.xml", "b.BetaTest", 3)
            parsed = parse_surefire_reports(
                reports, ("a.AlphaTest", "b.BetaTest")
            )
            self.assertEqual(parsed["tests"], 5)
            self.assertEqual(parsed["suiteCount"], 2)
            self.assertEqual(parsed["rawSurefireArchive"]["memberCount"], 2)
            with self.assertRaisesRegex(BuildGateError, "FQCN closure differs"):
                parse_surefire_reports(reports, ("a.AlphaTest",))

    def test_05_surefire_rejects_attribute_child_count_forgery(self) -> None:
        with self._repo_temporary("w3-surefire-count-") as reports:
            path = reports / "TEST-a.AlphaTest.xml"
            path.write_text(
                (
                    '<testsuite name="a.AlphaTest" tests="2" failures="0" '
                    'errors="0" skipped="0">'
                    '<testcase classname="a.AlphaTest" name="one"/>'
                    "</testsuite>"
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(BuildGateError, "count disagrees"):
                parse_surefire_reports(reports, ("a.AlphaTest",))

    def test_06_surefire_rejects_skipped_test_even_when_exit_was_zero(self) -> None:
        with self._repo_temporary("w3-surefire-skip-") as reports:
            self._write_suite(
                reports / "TEST-a.AlphaTest.xml",
                "a.AlphaTest",
                1,
                skipped=1,
            )
            with self.assertRaisesRegex(BuildGateError, "skipped"):
                parse_surefire_reports(reports, ("a.AlphaTest",))

    def test_07_surefire_rejects_empty_suite(self) -> None:
        with self._repo_temporary("w3-surefire-empty-") as reports:
            self._write_suite(reports / "TEST-a.AlphaTest.xml", "a.AlphaTest", 0)
            with self.assertRaisesRegex(BuildGateError, "empty"):
                parse_surefire_reports(reports, ("a.AlphaTest",))

    def test_08_surefire_rejects_embedded_failure_hidden_by_root_counts(self) -> None:
        with self._repo_temporary("w3-surefire-hidden-fail-") as reports:
            path = reports / "TEST-a.AlphaTest.xml"
            path.write_text(
                (
                    '<testsuite name="a.AlphaTest" tests="1" failures="0" '
                    'errors="0" skipped="0"><testcase classname="a.AlphaTest" '
                    'name="one"><failure>boom</failure></testcase></testsuite>'
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(BuildGateError, "attributes disagree"):
                parse_surefire_reports(reports, ("a.AlphaTest",))

    def test_09_vitest_accepts_exact_zero_pending_file_closure(self) -> None:
        expected = discover_vitest_files()[:2]
        with self._repo_temporary("w3-vitest-pass-") as directory:
            report = directory / "report.json"
            self._write_vitest(
                report,
                expected,
                assertions_per_file=2,
                suite_count=len(expected) + 1,
            )
            parsed = parse_vitest_report(report, expected)
            self.assertEqual(parsed["tests"], 4)
            self.assertEqual(parsed["suiteCount"], 3)
            self.assertEqual(parsed["pending"], 0)
            self.assertEqual(parsed["rawVitestArchive"]["memberCount"], 1)

    def test_10_vitest_rejects_pending_suite_or_test(self) -> None:
        expected = discover_vitest_files()[:1]
        with self._repo_temporary("w3-vitest-pending-") as directory:
            report = directory / "report.json"
            self._write_vitest(report, expected, assertions_per_file=1)
            value = json.loads(report.read_text(encoding="utf-8"))
            value.update(
                {
                    "numPassedTestSuites": 0,
                    "numPendingTestSuites": 1,
                    "numPassedTests": 0,
                    "numPendingTests": 1,
                }
            )
            value["testResults"][0]["assertionResults"][0]["status"] = "pending"
            report.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "assertion is not passed"):
                parse_vitest_report(report, expected)
            self._write_vitest(report, expected, assertions_per_file=1)
            value = json.loads(report.read_text(encoding="utf-8"))
            value["testResults"][0]["status"] = "failed"
            report.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "status is not passed"):
                parse_vitest_report(report, expected)

    def test_11_vitest_rejects_missing_or_extra_source_file(self) -> None:
        expected = discover_vitest_files()[:2]
        discovered = discover_vitest_files()[:3]
        with self._repo_temporary("w3-vitest-closure-") as directory:
            report = directory / "report.json"
            self._write_vitest(report, expected[:1], assertions_per_file=1)
            with self.assertRaisesRegex(BuildGateError, "test-file closure differs"):
                parse_vitest_report(report, expected)
            self._write_vitest(report, discovered, assertions_per_file=1)
            with self.assertRaisesRegex(BuildGateError, "test-file closure differs"):
                parse_vitest_report(report, expected)
            self._write_vitest(report, expected, assertions_per_file=1)
            value = json.loads(report.read_text(encoding="utf-8"))
            value["testResults"].append(value["testResults"][0])
            report.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "test-file closure differs"):
                parse_vitest_report(report, expected)

    def test_12_vitest_rejects_boolean_or_reconciled_count_forgery(self) -> None:
        expected = discover_vitest_files()[:1]
        with self._repo_temporary("w3-vitest-count-") as directory:
            report = directory / "report.json"
            self._write_vitest(report, expected, assertions_per_file=1)
            value = json.loads(report.read_text(encoding="utf-8"))
            value["numTotalTests"] = True
            report.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "invalid numTotalTests"):
                parse_vitest_report(report, expected)
            self._write_vitest(report, expected, assertions_per_file=1)
            value = json.loads(report.read_text(encoding="utf-8"))
            value["numTotalTests"] = 2
            value["numPassedTests"] = 2
            report.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "exact non-empty"):
                parse_vitest_report(report, expected)
            self._write_vitest(report, expected, assertions_per_file=1)
            value = json.loads(report.read_text(encoding="utf-8"))
            value["numTodoTests"] = 1
            report.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "exact non-empty"):
                parse_vitest_report(report, expected)
            self._write_vitest(report, expected, assertions_per_file=1)
            value = json.loads(report.read_text(encoding="utf-8"))
            value["testResults"][0]["assertionResults"] = []
            report.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "has no assertions"):
                parse_vitest_report(report, expected)
            self._write_vitest(report, expected, assertions_per_file=1)
            contents = report.read_text(encoding="utf-8")
            contents = contents.replace(
                '"numTodoTests": 0',
                '"numTodoTests": 1, "numTodoTests": 0',
                1,
            )
            report.write_text(contents, encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "duplicate JSON key"):
                parse_vitest_report(report, expected)
            self._write_vitest(report, expected, assertions_per_file=1)
            contents = report.read_text(encoding="utf-8")
            contents = contents.replace(
                '"success": true',
                '"ignoredNonStandard": NaN, "success": true',
                1,
            )
            report.write_text(contents, encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "invalid JSON constant"):
                parse_vitest_report(report, expected)

    def test_13_dist_inventory_is_content_addressed_and_fd_bound(self) -> None:
        with self._repo_temporary("w3-dist-pass-") as root:
            started = time.time_ns()
            index = root / "index.html"
            index.write_text("<!doctype html>\n", encoding="utf-8")
            assets = root / "assets"
            assets.mkdir()
            asset = assets / "app.js"
            asset.write_bytes(b"console.log('wave3');\n")
            completed = time.time_ns()
            context = self._context(
                min(index.stat().st_mtime_ns, asset.stat().st_mtime_ns) - 1
            )
            inventory = dist_inventory(
                root,
                context,
                GATES[7],
                self._result(started, completed),
            )
            self.assertEqual(inventory["fileCount"], 2)
            self.assertEqual(
                inventory["manifestSha256"],
                hashlib.sha256(canonical_json(inventory["files"])).hexdigest(),
            )
            self.assertEqual(
                inventory["contentAddressedArchive"]["memberCount"], 2
            )
            envelope = inventory["contentAddressedArchive"]
            decoded = validate_compressed_archive(envelope)
            self.assertEqual(decoded["format"], "wave3-dist-content-archive-v1")
            self.assertEqual(len(decoded["members"]), 2)
            self.assertEqual(
                [member["path"] for member in decoded["members"]],
                ["assets/app.js", "index.html"],
            )
            index_member = next(
                member
                for member in decoded["members"]
                if member["path"] == "index.html"
            )
            self.assertEqual(
                base64.b64decode(index_member["contentBase64"]),
                index.read_bytes(),
            )

            def envelope_for(raw: bytes) -> dict[str, object]:
                compressed = zlib.compress(raw, level=9)
                return {
                    "format": envelope["format"],
                    "memberCount": envelope["memberCount"],
                    "uncompressedSizeBytes": len(raw),
                    "compressedSizeBytes": len(compressed),
                    "uncompressedSha256": hashlib.sha256(raw).hexdigest(),
                    "compressedSha256": hashlib.sha256(compressed).hexdigest(),
                    "payloadBase64": base64.b64encode(compressed).decode("ascii"),
                }

            def repack(inner: dict[str, object]) -> dict[str, object]:
                return envelope_for(canonical_json(inner))

            corrupt_base64 = copy.deepcopy(envelope)
            corrupt_base64["payloadBase64"] = "%%%%"
            compressed_hash = copy.deepcopy(envelope)
            compressed_hash["compressedSha256"] = "0" * 64
            compressed_size = copy.deepcopy(envelope)
            compressed_size["compressedSizeBytes"] += 1
            uncompressed_hash = copy.deepcopy(envelope)
            uncompressed_hash["uncompressedSha256"] = "0" * 64
            uncompressed_size = copy.deepcopy(envelope)
            uncompressed_size["uncompressedSizeBytes"] += 1

            corrupt_zlib_bytes = bytearray(
                base64.b64decode(envelope["payloadBase64"])
            )
            corrupt_zlib_bytes[-1] ^= 1
            corrupt_zlib = copy.deepcopy(envelope)
            corrupt_zlib["payloadBase64"] = base64.b64encode(
                corrupt_zlib_bytes
            ).decode("ascii")
            corrupt_zlib["compressedSizeBytes"] = len(corrupt_zlib_bytes)
            corrupt_zlib["compressedSha256"] = hashlib.sha256(
                corrupt_zlib_bytes
            ).hexdigest()

            noncanonical_raw = json.dumps(
                decoded, ensure_ascii=False, indent=2, sort_keys=True
            ).encode("utf-8")
            noncanonical = envelope_for(noncanonical_raw)

            unsafe_path_inner = copy.deepcopy(decoded)
            unsafe_path_inner["members"][0]["path"] = "../app.js"
            unsafe_path = repack(unsafe_path_inner)

            raw_bytes_inner = copy.deepcopy(decoded)
            original_contents = base64.b64decode(
                raw_bytes_inner["members"][0]["contentBase64"]
            )
            changed_contents = bytes([original_contents[0] ^ 1]) + original_contents[1:]
            raw_bytes_inner["members"][0]["contentBase64"] = base64.b64encode(
                changed_contents
            ).decode("ascii")
            raw_bytes = repack(raw_bytes_inner)

            member_size_inner = copy.deepcopy(decoded)
            member_size_inner["members"][0]["sizeBytes"] += 1
            member_size = repack(member_size_inner)
            member_hash_inner = copy.deepcopy(decoded)
            member_hash_inner["members"][0]["sha256"] = "0" * 64
            member_hash = repack(member_hash_inner)
            member_contract_inner = copy.deepcopy(decoded)
            member_contract_inner["members"][0]["unexpected"] = "forgery"
            member_contract = repack(member_contract_inner)
            member_count = copy.deepcopy(envelope)
            member_count["memberCount"] += 1

            mutants = {
                "base64": corrupt_base64,
                "zlib": corrupt_zlib,
                "compressed hash": compressed_hash,
                "compressed size": compressed_size,
                "uncompressed hash": uncompressed_hash,
                "uncompressed size": uncompressed_size,
                "canonical JSON": noncanonical,
                "member count": member_count,
                "member contract": member_contract,
                "member size": member_size,
                "member hash": member_hash,
                "member path": unsafe_path,
                "member raw bytes": raw_bytes,
            }
            for label, mutant in mutants.items():
                with self.subTest(mutant=label):
                    with self.assertRaises(BuildGateError):
                        validate_compressed_archive(mutant)

    def test_14_dist_inventory_rejects_mtime_or_ctime_outside_command(self) -> None:
        with self._repo_temporary("w3-dist-stale-") as root:
            index = root / "index.html"
            index.write_text("<!doctype html>\n", encoding="utf-8")
            metadata = index.stat()
            context = self._context(metadata.st_mtime_ns - 1)
            result = self._result(metadata.st_mtime_ns + 1, time.time_ns() + 1)
            with self.assertRaisesRegex(BuildGateError, "outside.*window"):
                dist_inventory(root, context, GATES[7], result)

    def test_15_nofollow_chain_rejects_symbolic_ancestor(self) -> None:
        with self._repo_temporary("w3-chain-") as root:
            outside = root / "outside"
            outside.mkdir()
            link = root / "link"
            link.symlink_to(outside, target_is_directory=True)
            file_path = outside / "value"
            file_path.write_text("x", encoding="utf-8")
            with self.assertRaisesRegex(BuildGateError, "symbolic"):
                assert_nofollow_chain(root, link / "value", final="file")

    def test_16_secure_tree_removal_refuses_internal_symlink(self) -> None:
        with self._repo_temporary("w3-remove-") as root:
            victim = root / "victim"
            outside = root / "outside"
            victim.mkdir()
            outside.mkdir()
            (victim / "link").symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(BuildGateError, "containing a symlink"):
                secure_remove_tree(victim, root)
            self.assertTrue(outside.is_dir())

    def test_17_native_exclusive_rename_never_clobbers_target(self) -> None:
        with self._repo_temporary("w3-rename-") as root:
            source = root / "source"
            target = root / "target"
            source.write_text("new", encoding="utf-8")
            target.write_text("old", encoding="utf-8")
            with self.assertRaises((BuildGateError, FileExistsError, OSError)):
                exclusive_rename(source, target)
            self.assertEqual(target.read_text(encoding="utf-8"), "old")
            self.assertEqual(source.read_text(encoding="utf-8"), "new")

    def test_18_bundle_commit_writes_marker_last_and_rolls_back_on_race(self) -> None:
        with self._runs_temporary("w3-commit-") as run_root:
            (run_root / "leaves").mkdir()
            stage = run_root / ".stage"
            stage.mkdir()
            for gate in GATES:
                parent = run_root / "leaves" / gate.slug
                parent.mkdir()
                output = stage / gate.slug
                output.mkdir()
                (output / gate.primary_filename).write_text("primary", encoding="utf-8")
                if gate.secondary_filename:
                    (output / gate.secondary_filename).write_text(
                        "secondary", encoding="utf-8"
                    )
            manifest = freeze_stage(stage)
            original = __import__(
                "scripts.qa.produce_wave3_build_gates", fromlist=["exclusive_rename"]
            ).exclusive_rename
            calls = 0

            def fail_third(source: Path, target: Path) -> None:
                nonlocal calls
                calls += 1
                if calls == 3:
                    raise BuildGateError("injected commit race")
                original(source, target)

            with mock.patch(
                "scripts.qa.produce_wave3_build_gates.exclusive_rename",
                side_effect=fail_third,
            ):
                with self.assertRaisesRegex(BuildGateError, "injected"):
                    commit_outputs(stage, run_root, manifest, self._context(0))
            self.assertFalse((run_root / COMMIT_FILENAME).exists())
            self.assertFalse(any((run_root / "leaves" / gate.slug / "raw").exists() for gate in GATES))

        with self._runs_temporary("w3-commit-point-") as run_root:
            (run_root / "leaves").mkdir()
            stage = run_root / ".stage"
            stage.mkdir()
            for gate in GATES:
                parent = run_root / "leaves" / gate.slug
                parent.mkdir()
                output = stage / gate.slug
                output.mkdir()
                (output / gate.primary_filename).write_text(
                    "primary", encoding="utf-8"
                )
                if gate.secondary_filename:
                    (output / gate.secondary_filename).write_text(
                        "secondary", encoding="utf-8"
                    )
            manifest = freeze_stage(stage)
            original = __import__(
                "scripts.qa.produce_wave3_build_gates",
                fromlist=["exclusive_rename"],
            ).exclusive_rename

            def interrupt_after_marker(source: Path, target: Path) -> None:
                original(source, target)
                if target.name == COMMIT_FILENAME:
                    raise KeyboardInterrupt("after marker commit point")

            with mock.patch(
                "scripts.qa.produce_wave3_build_gates.exclusive_rename",
                side_effect=interrupt_after_marker,
            ):
                committed = commit_outputs(
                    stage, run_root, manifest, self._context(0)
                )
            self.assertEqual(len(committed), len(GATES))
            self.assertTrue((run_root / COMMIT_FILENAME).is_file())
            self.assertTrue(
                all(
                    (run_root / "leaves" / gate.slug / "raw").is_dir()
                    for gate in GATES
                )
            )

        with self._runs_temporary("w3-raw-interrupt-") as run_root:
            (run_root / "leaves").mkdir()
            stage = run_root / ".stage"
            stage.mkdir()
            for gate in GATES:
                parent = run_root / "leaves" / gate.slug
                parent.mkdir()
                output = stage / gate.slug
                output.mkdir()
                (output / gate.primary_filename).write_text(
                    "primary", encoding="utf-8"
                )
                if gate.secondary_filename:
                    (output / gate.secondary_filename).write_text(
                        "secondary", encoding="utf-8"
                    )
            manifest = freeze_stage(stage)
            original = __import__(
                "scripts.qa.produce_wave3_build_gates",
                fromlist=["exclusive_rename"],
            ).exclusive_rename
            raw_renames = 0

            def interrupt_after_native_raw_rename(
                source: Path, target: Path
            ) -> None:
                nonlocal raw_renames
                original(source, target)
                if target.name == "raw":
                    raw_renames += 1
                    if raw_renames == 3:
                        raise KeyboardInterrupt(
                            "after native raw rename before registration"
                        )

            with mock.patch(
                "scripts.qa.produce_wave3_build_gates.exclusive_rename",
                side_effect=interrupt_after_native_raw_rename,
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "before registration"
                ):
                    commit_outputs(
                        stage, run_root, manifest, self._context(0)
                    )
            self.assertFalse((run_root / COMMIT_FILENAME).exists())
            self.assertFalse(
                any(
                    (run_root / "leaves" / gate.slug / "raw").exists()
                    for gate in GATES
                )
            )

        with self._runs_temporary("w3-return-interrupt-") as run_root:
            (run_root / "leaves").mkdir()
            context = self._context(0)
            lock_fd, lock_path, lock_identity = acquire_run_lock(
                run_root, context
            )
            stage = run_root / ".stage"
            stage.mkdir()
            for gate in GATES:
                parent = run_root / "leaves" / gate.slug
                parent.mkdir()
                output = stage / gate.slug
                output.mkdir()
                (output / gate.primary_filename).write_text(
                    "primary", encoding="utf-8"
                )
                if gate.secondary_filename:
                    (output / gate.secondary_filename).write_text(
                        "secondary", encoding="utf-8"
                    )
            manifest = freeze_stage(stage)
            released_as_committed = False

            def interrupt_before_caller_assignment() -> None:
                commit_outputs(stage, run_root, manifest, context)
                raise KeyboardInterrupt(
                    "after commit return before caller assignment"
                )

            try:
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "before caller assignment"
                ):
                    interrupt_before_caller_assignment()
            finally:
                released_as_committed = release_run_lock_from_bundle(
                    lock_fd,
                    lock_path,
                    lock_identity,
                    run_root,
                    context,
                    manifest,
                )
            self.assertTrue(released_as_committed)
            self.assertFalse((run_root / LOCK_FILENAME).exists())
            self.assertTrue((run_root / COMMIT_FILENAME).is_file())
            self.assertTrue(
                all(
                    (run_root / "leaves" / gate.slug / "raw").is_dir()
                    for gate in GATES
                )
            )

    def test_19_receipt_text_is_exactly_derived_from_epoch_nanoseconds(self) -> None:
        started = 1_774_656_000_123_456_789
        completed = started + 987_654_321
        result = self._result(started, completed)
        result = CommandResult(
            **{
                **result.__dict__,
                "started_at": utc_iso_from_ns(started),
                "completed_at": utc_iso_from_ns(completed),
            }
        )
        summary = structured_test_summary(
            self._context(0),
            GATES[0],
            result,
            {"suiteCount": 1, "tests": 1},
            "unit-test-summary",
        )
        self.assertEqual(
            summary["startedAt"], utc_iso_from_ns(summary["startedAtEpochNs"])
        )
        self.assertEqual(
            summary["completedAt"], utc_iso_from_ns(summary["completedAtEpochNs"])
        )
        forged = dict(summary, startedAt="2026-01-01T00:00:00.000000Z")
        self.assertNotEqual(
            forged["startedAt"], utc_iso_from_ns(forged["startedAtEpochNs"])
        )

    def test_20_transient_write_restore_is_detected_by_identity_seal(self) -> None:
        with self._repo_temporary("w3-seal-write-") as root:
            source = root / "source.txt"
            source.write_text("original\n", encoding="utf-8")
            seal = WorkspaceSeal(
                [
                    ("mutant-source-dir", root, False, True),
                    ("mutant-source-file", source, False, True),
                ]
            )
            try:
                source.write_text("transient\n", encoding="utf-8")
                source.write_text("original\n", encoding="utf-8")
                with self.assertRaisesRegex(BuildGateError, "mutation|seal changed"):
                    seal.assert_unchanged()
            finally:
                seal.close()
            support_workspace = root / "command-support"
            support_workspace.mkdir()
            support_baseline = copy_command_support_files(
                support_workspace
            )
            self.assertEqual(
                support_baseline["recordCount"],
                len(COMMAND_SUPPORT_FILES)
                + len(GENERATED_COMMAND_SUPPORT_FILES),
            )
            self.assertEqual(support_baseline["recordCount"], 14)
            self.assertEqual(
                command_support_manifest(
                    support_workspace,
                    repository_layout=False,
                ),
                support_baseline,
            )
            user_config = (
                support_workspace / ".command-inputs/npm-userconfig"
            )
            user_config.write_text(
                "script-shell=/tmp/evil\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                BuildGateError,
                "support manifest differs",
            ):
                require_equal_command_support(
                    support_baseline,
                    command_support_manifest(
                        support_workspace,
                        repository_layout=False,
                    ),
                )

    def test_20p_access_time_attribute_event_requires_exact_fd_metadata(
        self,
    ) -> None:
        with self._repo_temporary("w3-seal-atime-") as root:
            source = root / "source.txt"
            source.write_text("original\n", encoding="utf-8")
            seal = WorkspaceSeal(
                [
                    ("mutant-source-dir", root, False, True),
                    ("mutant-source-file", source, False, True),
                ]
            )
            try:
                source_metadata = source.stat()
                source_descriptor = next(
                    descriptor
                    for descriptor, identity in seal._descriptor_metadata.items()
                    if identity[:2]
                    == (source_metadata.st_dev, source_metadata.st_ino)
                )
                attribute_event = types.SimpleNamespace(
                    ident=source_descriptor,
                    fflags=select.KQ_NOTE_ATTRIB,
                )
                seal._queue.control(None, 64, 0)

                class EventQueue:
                    def __init__(self, batches: list[list[object]]) -> None:
                        self.batches = iter(batches)

                    def control(self, *_args: object) -> list[object]:
                        return next(self.batches)

                real_queue = seal._queue
                try:
                    seal._queue = EventQueue(
                        [[attribute_event], []]
                    )
                    seal._drain_runtime_events()
                    with self.assertRaisesRegex(
                        BuildGateError,
                        r"flags=WRITE\|ATTRIB.*path="
                        + re.escape(source.as_posix()),
                    ):
                        seal._queue = EventQueue(
                            [
                                [
                                    types.SimpleNamespace(
                                        ident=source_descriptor,
                                        fflags=(
                                            select.KQ_NOTE_WRITE
                                            | select.KQ_NOTE_ATTRIB
                                        ),
                                    )
                                ],
                                [],
                            ]
                        )
                        seal._drain_runtime_events()
                    with self.assertRaisesRegex(
                        BuildGateError,
                        "flags=WRITE.*path="
                        + re.escape(source.as_posix()),
                    ):
                        seal._queue = EventQueue(
                            [
                                [
                                    types.SimpleNamespace(
                                        ident=source_descriptor,
                                        fflags=select.KQ_NOTE_WRITE,
                                    )
                                ],
                                [],
                            ]
                        )
                        seal._drain_runtime_events()
                    with self.assertRaisesRegex(
                        BuildGateError,
                        "attribute-event-without-sealed-descriptor.*"
                        "path=<fd-path-unavailable>",
                    ):
                        seal._queue = EventQueue(
                            [
                                [
                                    types.SimpleNamespace(
                                        ident=source_descriptor + 10_000_000,
                                        fflags=select.KQ_NOTE_ATTRIB,
                                    )
                                ],
                                [],
                            ]
                        )
                        seal._drain_runtime_events()
                    current_mode = source.stat().st_mode
                    os.chmod(
                        source,
                        stat.S_IMODE(current_mode) ^ stat.S_IXUSR,
                    )
                    with self.assertRaisesRegex(
                        BuildGateError,
                        "reason=protected-metadata-changed.*path="
                        + re.escape(source.as_posix()),
                    ):
                        seal._queue = EventQueue(
                            [[attribute_event], []]
                        )
                        seal._drain_runtime_events()
                    os.chmod(source, stat.S_IMODE(current_mode))
                finally:
                    seal._queue = real_queue
                    seal._queue.control(None, 64, 0)
            finally:
                seal.close()

    def test_20a_internal_npm_bin_links_are_bound_and_mutants_rejected(
        self,
    ) -> None:
        with self._repo_temporary("w3-seal-npm-bin-") as root:
            node_modules = root / "node_modules"
            executable_directory = node_modules / "package/bin"
            executable_directory.mkdir(parents=True)
            executable = executable_directory / "cli.js"
            executable.write_text("safe\n", encoding="utf-8")
            alternate = executable_directory / "alternate.js"
            alternate.write_text("alternate\n", encoding="utf-8")
            bin_directory = node_modules / ".bin"
            bin_directory.mkdir()
            link = bin_directory / "tool"
            link.symlink_to("../package/bin/cli.js")
            seal = WorkspaceSeal(
                [("isolated-node-install", node_modules, True, False)],
                internal_npm_bin_boundaries={
                    "isolated-node-install": node_modules
                },
            )
            try:
                seal.assert_unchanged()
                link.unlink()
                link.symlink_to("../package/bin/alternate.js")
                with self.assertRaisesRegex(
                    BuildGateError, "mutation|seal changed"
                ):
                    seal.assert_unchanged()
            finally:
                seal.close()

        invalid_targets = (
            ("external", "../../outside.js"),
            ("broken", "../package/bin/missing.js"),
            ("absolute", "/tmp/w3-forbidden-npm-bin-target"),
        )
        for label, target in invalid_targets:
            with self.subTest(label=label):
                with self._repo_temporary(
                    f"w3-seal-npm-{label}-"
                ) as root:
                    node_modules = root / "node_modules"
                    (node_modules / ".bin").mkdir(parents=True)
                    (node_modules / "package/bin").mkdir(parents=True)
                    (root / "outside.js").write_text(
                        "outside\n", encoding="utf-8"
                    )
                    link = node_modules / ".bin/tool"
                    link.symlink_to(target)
                    with self.assertRaises(BuildGateError):
                        WorkspaceSeal(
                            [
                                (
                                    "isolated-node-install",
                                    node_modules,
                                    True,
                                    False,
                                )
                            ],
                            internal_npm_bin_boundaries={
                                "isolated-node-install": node_modules
                            },
                        )

        with self._repo_temporary("w3-seal-npm-non-bin-") as root:
            node_modules = root / "node_modules"
            (node_modules / "package/bin").mkdir(parents=True)
            (node_modules / "package/bin/cli.js").write_text(
                "safe\n", encoding="utf-8"
            )
            (node_modules / "tool").symlink_to("package/bin/cli.js")
            with self.assertRaisesRegex(BuildGateError, "not an npm .bin"):
                WorkspaceSeal(
                    [("isolated-node-install", node_modules, True, False)],
                    internal_npm_bin_boundaries={
                        "isolated-node-install": node_modules
                    },
                )

    def test_20b_npm_link_identity_swap_is_rejected(self) -> None:
        with self._repo_temporary("w3-link-identity-race-") as root:
            node_modules = root / "node_modules"
            (node_modules / ".bin").mkdir(parents=True)
            (node_modules / "safe").mkdir()
            (node_modules / "evil").mkdir()
            (node_modules / "safe/cli.js").write_text("safe\n", encoding="utf-8")
            (node_modules / "evil/cli.js").write_text("evil\n", encoding="utf-8")
            link = node_modules / ".bin/tool"
            link.symlink_to("../safe/cli.js")
            real_readlink = os.readlink
            swapped = False

            def swap_before_readlink(
                path: str | bytes,
                *,
                dir_fd: int | None = None,
            ) -> str:
                nonlocal swapped
                if not swapped:
                    swapped = True
                    link.unlink()
                    link.symlink_to("../evil/cli.js")
                return real_readlink(path, dir_fd=dir_fd)

            with (
                mock.patch(
                    "scripts.qa.produce_wave3_build_gates.os.readlink",
                    side_effect=swap_before_readlink,
                ),
                self.assertRaisesRegex(
                    BuildGateError,
                    "identity or metadata changed",
                ),
            ):
                internal_npm_bin_symlink_record(
                    "isolated-node-install",
                    link,
                    node_modules,
                )

    def test_20c_openat_target_ancestor_swap_is_rejected(self) -> None:
        with self._repo_temporary("w3-openat-ancestor-race-") as root:
            node_modules = root / "node_modules"
            package = node_modules / "package"
            (package / "bin").mkdir(parents=True)
            (package / "bin/cli.js").write_text("safe\n", encoding="utf-8")
            (node_modules / ".bin").mkdir()
            link = node_modules / ".bin/tool"
            link.symlink_to("../package/bin/cli.js")
            outside_package = root / "outside-package"
            (outside_package / "bin").mkdir(parents=True)
            (outside_package / "bin/cli.js").write_text(
                "evil\n",
                encoding="utf-8",
            )
            backup = node_modules / "package.original"
            real_open = os.open
            swapped = False

            def swap_ancestor_before_final_open(
                path: str | bytes,
                flags: int,
                mode: int = 0o777,
                *,
                dir_fd: int | None = None,
            ) -> int:
                nonlocal swapped
                if path == "cli.js" and dir_fd is not None and not swapped:
                    swapped = True
                    package.rename(backup)
                    package.symlink_to(outside_package, target_is_directory=True)
                    try:
                        return real_open(path, flags, mode, dir_fd=dir_fd)
                    finally:
                        package.unlink()
                        backup.rename(package)
                return real_open(path, flags, mode, dir_fd=dir_fd)

            with (
                mock.patch(
                    "scripts.qa.produce_wave3_build_gates.os.open",
                    side_effect=swap_ancestor_before_final_open,
                ),
                self.assertRaisesRegex(
                    BuildGateError,
                    "identity or metadata changed",
                ),
            ):
                internal_npm_bin_symlink_record(
                    "isolated-node-install",
                    link,
                    node_modules,
                )

    def test_20d_cache_targets_and_directory_symlinks_are_rejected(self) -> None:
        for cache_name in sorted(EXCLUDED_NODE_CACHE_DIRECTORIES):
            with self.subTest(cache_name=cache_name):
                with self._repo_temporary(
                    f"w3-cache-target-{cache_name[1:]}-"
                ) as root:
                    node_modules = root / "node_modules"
                    target = node_modules / cache_name / "deep/cli.js"
                    target.parent.mkdir(parents=True)
                    target.write_text("cache\n", encoding="utf-8")
                    (node_modules / ".bin").mkdir()
                    link = node_modules / ".bin/tool"
                    link.symlink_to(f"../{cache_name}/deep/cli.js")
                    with self.assertRaisesRegex(
                        BuildGateError,
                        "excluded cache tree",
                    ):
                        internal_npm_bin_symlink_record(
                            "isolated-node-install",
                            link,
                            node_modules,
                        )

        with self._repo_temporary("w3-directory-symlink-") as root:
            node_modules = root / "node_modules"
            real = node_modules / "real"
            real.mkdir(parents=True)
            (node_modules / "directory-link").symlink_to(
                real,
                target_is_directory=True,
            )
            with self.assertRaisesRegex(
                BuildGateError,
                "not an npm .bin",
            ):
                WorkspaceSeal(
                    [("isolated-node-install", node_modules, True, False)],
                    internal_npm_bin_boundaries={
                        "isolated-node-install": node_modules
                    },
                    ignored_recursive_directories={
                        "isolated-node-install": frozenset(
                            EXCLUDED_NODE_CACHE_DIRECTORIES
                        )
                    },
                )

    def test_20e_dependency_handoff_rejects_isolated_retarget(self) -> None:
        with self._repo_temporary("w3-dependency-handoff-") as root:
            origin_node = root / "origin/node_modules"
            (origin_node / ".bin").mkdir(parents=True)
            (origin_node / "safe").mkdir()
            (origin_node / "evil").mkdir()
            (origin_node / "safe/cli.js").write_text("safe\n", encoding="utf-8")
            (origin_node / "evil/cli.js").write_text("evil\n", encoding="utf-8")
            (origin_node / ".bin/tool").symlink_to("../safe/cli.js")
            origin_maven = root / "origin/.m2"
            (origin_maven / "repository/example").mkdir(parents=True)
            (origin_maven / "repository/example/a.jar").write_bytes(b"jar")

            isolated_node = root / "isolated/node_modules"
            isolated_maven = root / "isolated/.m2"
            shutil.copytree(origin_node, isolated_node, symlinks=True)
            shutil.copytree(origin_maven, isolated_maven, symlinks=True)
            expected = dependency_handoff_manifest(
                origin_node,
                origin_maven,
            )
            isolated_link = isolated_node / ".bin/tool"
            isolated_link.unlink()
            isolated_link.symlink_to("../evil/cli.js")
            actual = dependency_handoff_manifest(
                isolated_node,
                isolated_maven,
            )
            with self.assertRaisesRegex(
                BuildGateError,
                "logical manifests differ",
            ):
                require_equal_dependency_handoff(expected, actual)

    def test_20f_initialization_mutation_after_watch_is_rejected(self) -> None:
        with self._repo_temporary("w3-seal-init-watch-") as root:
            source = root / "source.txt"
            source.write_text("safe\n", encoding="utf-8")
            real_read = read_regular_file
            mutated = False

            def transient_mutation(path: Path, boundary: Path):
                nonlocal mutated
                if path == source and not mutated:
                    mutated = True
                    source.write_text("evil\n", encoding="utf-8")
                    source.write_text("safe\n", encoding="utf-8")
                return real_read(path, boundary)

            with (
                mock.patch(
                    "scripts.qa.produce_wave3_build_gates.read_regular_file",
                    side_effect=transient_mutation,
                ),
                self.assertRaises(BuildGateError),
            ):
                WorkspaceSeal(
                    [("mutant-source", source, False, True)]
                )

    def test_20g_real_npm_bin_link_closure_is_positive(self) -> None:
        node_modules = FRONTEND_ROOT / "node_modules"
        links = sorted(
            (node_modules / ".bin").iterdir(),
            key=lambda path: path.name.encode("utf-8"),
        )
        links = [path for path in links if path.is_symlink()]
        self.assertEqual(len(links), 14)
        for path in links:
            record = internal_npm_bin_symlink_record(
                "frontend-node-modules-origin",
                path,
                node_modules,
            )
            self.assertEqual(record["kind"], "symlink")
            self.assertGreater(record["inode"], 0)
            self.assertEqual(
                len(record["resolvedTargetSha256"]),
                64,
            )

    def test_20h_open_to_watch_mutation_is_rejected(self) -> None:
        with self._repo_temporary("w3-seal-prewatch-") as root:
            source = root / "source.txt"
            source.write_text("safe\n", encoding="utf-8")
            real_open = os.open
            mutated = False

            def mutate_after_open(
                path: str | bytes | os.PathLike[str] | os.PathLike[bytes],
                flags: int,
                mode: int = 0o777,
                *,
                dir_fd: int | None = None,
            ) -> int:
                nonlocal mutated
                descriptor = real_open(
                    path,
                    flags,
                    mode,
                    dir_fd=dir_fd,
                )
                if path == source.name and dir_fd is not None and not mutated:
                    mutated = True
                    source.write_text("evil\n", encoding="utf-8")
                return descriptor

            with (
                mock.patch(
                    "scripts.qa.produce_wave3_build_gates.os.open",
                    side_effect=mutate_after_open,
                ),
                self.assertRaisesRegex(
                    BuildGateError,
                    "pre-watch|identity or metadata changed",
                ),
            ):
                WorkspaceSeal(
                    [("mutant-source", source, False, True)]
                )
            self.assertTrue(mutated)

    def test_20i_two_phase_late_sibling_mutation_is_rejected(self) -> None:
        with self._repo_temporary("w3-seal-two-phase-") as root:
            first = root / "a.txt"
            late = root / "z.txt"
            first.write_text("first\n", encoding="utf-8")
            late.write_text("late\n", encoding="utf-8")
            real_read = read_regular_file
            mutated = False

            def mutate_late_sibling(path: Path, boundary: Path):
                nonlocal mutated
                if path == first and not mutated:
                    mutated = True
                    late.write_text("evil\n", encoding="utf-8")
                    late.write_text("late\n", encoding="utf-8")
                return real_read(path, boundary)

            with (
                mock.patch(
                    "scripts.qa.produce_wave3_build_gates.read_regular_file",
                    side_effect=mutate_late_sibling,
                ),
                self.assertRaisesRegex(BuildGateError, "two-phase|changed"),
            ):
                WorkspaceSeal(
                    [("mutant-source", root, True, True)]
                )
            self.assertTrue(mutated)

    def test_20j_cache_anchor_allows_contents_but_rejects_mutation(
        self,
    ) -> None:
        with self._repo_temporary("w3-cache-anchor-positive-") as root:
            node_modules = root / "node_modules"
            node_modules.mkdir()
            for name in sorted(EXCLUDED_NODE_CACHE_DIRECTORIES):
                (node_modules / name).mkdir(mode=0o700)
            anchor_receipt = isolated_node_cache_anchors(node_modules)
            self.assertEqual(
                validate_isolated_node_cache_anchor_receipt(anchor_receipt),
                anchor_receipt["records"],
            )
            for field, value in (
                ("schemaVersion", True),
                ("device", True),
            ):
                with self.subTest(bool_as_int_cache_anchor=field):
                    mutant = copy.deepcopy(anchor_receipt)
                    if field == "schemaVersion":
                        mutant[field] = value
                    else:
                        mutant["records"][0][field] = value
                    with self.assertRaisesRegex(
                        BuildGateError, "cache anchor"
                    ):
                        validate_isolated_node_cache_anchor_receipt(
                            mutant
                        )
            expectations = {
                node_modules / record["path"]: record
                for record in anchor_receipt["records"]
            }
            seal = WorkspaceSeal(
                [("isolated-node-install", node_modules, True, False)],
                ignored_recursive_directories={
                    "isolated-node-install": frozenset(
                        EXCLUDED_NODE_CACHE_DIRECTORIES
                    )
                },
                expected_cache_anchors=expectations,
            )
            try:
                (node_modules / ".cache/runtime.bin").write_bytes(b"cache")
                (node_modules / ".tmp/runtime.bin").write_bytes(b"cache")
                seal.assert_unchanged()
                self.assertEqual(seal.receipt["cacheAnchorCount"], 2)
            finally:
                seal.close()

        for transient in (False, True):
            with self.subTest(transient=transient):
                with self._repo_temporary(
                    f"w3-cache-anchor-mode-{int(transient)}-"
                ) as root:
                    node_modules = root / "node_modules"
                    node_modules.mkdir()
                    for name in sorted(EXCLUDED_NODE_CACHE_DIRECTORIES):
                        (node_modules / name).mkdir(mode=0o700)
                    receipt = isolated_node_cache_anchors(node_modules)
                    expectations = {
                        node_modules / record["path"]: record
                        for record in receipt["records"]
                    }
                    seal = WorkspaceSeal(
                        [
                            (
                                "isolated-node-install",
                                node_modules,
                                True,
                                False,
                            )
                        ],
                        ignored_recursive_directories={
                            "isolated-node-install": frozenset(
                                EXCLUDED_NODE_CACHE_DIRECTORIES
                            )
                        },
                        expected_cache_anchors=expectations,
                    )
                    try:
                        os.chmod(node_modules / ".cache", 0o755)
                        if transient:
                            os.chmod(node_modules / ".cache", 0o700)
                        with self.assertRaisesRegex(
                            BuildGateError,
                            "mutation|cache anchor",
                        ):
                            seal.assert_unchanged()
                    finally:
                        seal.close()

    def test_20k_dependency_handoff_summary_is_bound_to_receipt(self) -> None:
        with self._repo_temporary("w3-handoff-receipt-") as root:
            source = root / "source.txt"
            source.write_text("source\n", encoding="utf-8")
            node_modules = root / "node_modules"
            (node_modules / "package").mkdir(parents=True)
            (node_modules / "package/index.js").write_text(
                "module\n",
                encoding="utf-8",
            )
            maven_home = root / ".m2"
            (maven_home / "repository/example").mkdir(parents=True)
            (maven_home / "repository/example/a.jar").write_bytes(b"jar")
            manifest = dependency_handoff_manifest(
                node_modules,
                maven_home,
            )
            seal = WorkspaceSeal(
                [("manifest-source", source, False, True)]
            )
            try:
                seal.bind_dependency_handoff(manifest, copy.deepcopy(manifest))
                handoff = seal.receipt["dependencyHandoff"]
                self.assertTrue(handoff["equal"])
                self.assertEqual(
                    handoff["logicalManifestSha256"],
                    manifest["logicalManifestSha256"],
                )
                self.assertEqual(
                    handoff["nodeRecordCount"],
                    manifest["node"]["recordCount"],
                )
                self.assertEqual(
                    handoff["mavenRecordCount"],
                    manifest["maven"]["recordCount"],
                )
            finally:
                seal.close()

    def test_20l_prelinearization_dependency_change_misses_baseline(
        self,
    ) -> None:
        with self._repo_temporary("w3-dependency-prebaseline-") as root:
            node_modules = root / "node_modules"
            node_modules.mkdir()
            first = node_modules / "a.txt"
            late = node_modules / "z.txt"
            first.write_text("first\n", encoding="utf-8")
            late.write_text("safe\n", encoding="utf-8")
            maven_home = root / ".m2"
            (maven_home / "repository").mkdir(parents=True)
            (maven_home / "repository/a.pom").write_text(
                "pom\n",
                encoding="utf-8",
            )
            baseline = dependency_handoff_manifest(
                node_modules,
                maven_home,
            )
            real_open = os.open
            mutated = False

            def mutate_unvisited_dependency(
                path: str | bytes | os.PathLike[str] | os.PathLike[bytes],
                flags: int,
                mode: int = 0o777,
                *,
                dir_fd: int | None = None,
            ) -> int:
                nonlocal mutated
                descriptor = real_open(
                    path,
                    flags,
                    mode,
                    dir_fd=dir_fd,
                )
                if path == first.name and dir_fd is not None and not mutated:
                    mutated = True
                    late.write_text("evil\n", encoding="utf-8")
                return descriptor

            with mock.patch(
                "scripts.qa.produce_wave3_build_gates.os.open",
                side_effect=mutate_unvisited_dependency,
            ):
                seal = WorkspaceSeal(
                    [
                        (
                            "frontend-node-modules-origin",
                            node_modules,
                            True,
                            False,
                        ),
                        (
                            "maven-local-cache-origin",
                            maven_home,
                            True,
                            False,
                        ),
                    ]
                )
            try:
                self.assertTrue(mutated)
                current = dependency_handoff_manifest(
                    node_modules,
                    maven_home,
                )
                with self.assertRaisesRegex(
                    BuildGateError,
                    "logical manifests differ",
                ):
                    seal.bind_dependency_handoff(baseline, current)
            finally:
                seal.close()

    def test_20m_unwatched_ancestor_rebind_is_rejected(self) -> None:
        with self._repo_temporary("w3-ancestor-rebind-") as root:
            base = root / "top/base"
            authority = base / "outer/tree"
            authority.mkdir(parents=True)
            (authority / "safe.txt").write_text(
                "safe\n",
                encoding="utf-8",
            )
            seal = WorkspaceSeal(
                [("dependency-authority", authority, True, False)]
            )
            try:
                old_base = root / "top/base-old"
                base.rename(old_base)
                replacement = root / "top/base/outer/tree"
                replacement.mkdir(parents=True)
                (replacement / "evil.txt").write_text(
                    "evil\n",
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(
                    BuildGateError,
                    "mutation|ancestor anchor",
                ):
                    seal.assert_unchanged()
            finally:
                seal.close()

    def test_20n_isolated_source_snapshot_recheck_rejects_change(
        self,
    ) -> None:
        with self._repo_temporary("w3-isolated-source-recheck-") as root:
            isolated = root / "isolated"
            isolated.mkdir()
            source = isolated / "source.txt"
            source.write_text("safe\n", encoding="utf-8")
            expected = {
                "path": "backend/source.txt",
                "sizeBytes": len(b"safe\n"),
                "sha256": hashlib.sha256(b"safe\n").hexdigest(),
            }
            expectations = [(source, isolated, expected)]
            require_isolated_source_snapshot(expectations)
            source.write_text("evil\n", encoding="utf-8")
            with self.assertRaisesRegex(
                BuildGateError,
                "isolated command source differs",
            ):
                require_isolated_source_snapshot(expectations)
            source.write_text("safe\n", encoding="utf-8")
            extra = isolated / "extra.java"
            extra.write_text(
                "extra\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                BuildGateError,
                "file closure differs",
            ):
                require_isolated_source_snapshot(expectations)
            extra.unlink()
            injected_log = isolated / "injected.log"
            injected_log.write_text("payload\n", encoding="utf-8")
            with self.assertRaisesRegex(
                BuildGateError,
                "unexpected excluded file",
            ):
                require_isolated_source_snapshot(expectations)
            injected_log.unlink()
            injected_target = isolated / "target"
            injected_target.mkdir()
            (injected_target / "payload.js").write_text(
                "payload\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                BuildGateError,
                "unexpected excluded directory",
            ):
                require_isolated_source_snapshot(expectations)

    def test_20o_precreated_output_containers_are_command_writable(
        self,
    ) -> None:
        with self._repo_temporary("w3-output-containers-") as root:
            project = root / "project"
            source_directory = project / "src"
            source_directory.mkdir(parents=True)
            source = source_directory / "source.txt"
            source.write_text("safe\n", encoding="utf-8")
            public = project / "public"
            public.mkdir()
            target = project / "target"
            dist = project / "dist"
            target.mkdir()
            dist.mkdir()
            seal = WorkspaceSeal(
                [
                    ("isolated-command-source", project, False, True),
                    (
                        "isolated-command-source",
                        source_directory,
                        False,
                        True,
                    ),
                    ("isolated-command-source", source, False, True),
                    ("isolated-command-source", public, False, True),
                ]
            )
            try:
                (target / "classes").mkdir()
                (target / "classes/App.class").write_bytes(b"class")
                (dist / "prod").mkdir()
                (dist / "prod/index.js").write_text(
                    "build\n",
                    encoding="utf-8",
                )
                seal.assert_unchanged()
                (public / "injected.txt").write_text(
                    "evil\n",
                    encoding="utf-8",
                )
                with self.assertRaisesRegex(
                    BuildGateError,
                    "mutation|seal changed|directory closure",
                ):
                    seal.assert_unchanged()
            finally:
                seal.close()

    def test_21_transient_rename_restore_is_detected_by_directory_seal(self) -> None:
        with self._repo_temporary("w3-seal-rename-") as root:
            source = root / "source.txt"
            backup = root / "backup.txt"
            replacement = root / "replacement.txt"
            source.write_text("original\n", encoding="utf-8")
            seal = WorkspaceSeal(
                [
                    ("mutant-source-dir", root, False, True),
                    ("mutant-source-file", source, False, True),
                ]
            )
            try:
                source.rename(backup)
                replacement.write_text("replacement\n", encoding="utf-8")
                replacement.rename(source)
                source.unlink()
                backup.rename(source)
                with self.assertRaisesRegex(BuildGateError, "mutation|seal changed"):
                    seal.assert_unchanged()
            finally:
                seal.close()

    def test_22_backend_gate_uses_one_precreated_stage_directory(self) -> None:
        with self._repo_temporary("w3-backend-flow-") as stage:
            verifier = Wave3Evidence(REPOSITORY_ROOT)
            context = self._context(0)
            (stage / GATES[0].slug).mkdir(mode=0o700)
            result = self._result(time.time_ns(), time.time_ns() + 1)
            parsed = {
                "suiteCount": 1,
                "tests": 1,
                "failures": 0,
                "errors": 0,
                "skipped": 0,
                "expectedTestClasses": ["a.AlphaTest"],
                "suites": [],
                "rawSurefireArchive": {
                    "format": "unit",
                    "memberCount": 1,
                    "uncompressedSizeBytes": 1,
                    "compressedSizeBytes": 1,
                    "uncompressedSha256": "a" * 64,
                    "compressedSha256": "b" * 64,
                    "payloadBase64": "eA==",
                },
            }
            with (
                mock.patch(
                    "scripts.qa.produce_wave3_build_gates.clear_surefire_reports"
                ),
                mock.patch(
                    "scripts.qa.produce_wave3_build_gates.run_command",
                    return_value=result,
                ),
                mock.patch(
                    "scripts.qa.produce_wave3_build_gates.parse_surefire_reports",
                    return_value=parsed,
                ),
                mock.patch(
                    "scripts.qa.produce_wave3_build_gates.backend_runtime_classes_receipt",
                    return_value={
                        "schemaVersion": 1,
                        "kind": "wave3-backend-runtime-classes-receipt",
                    },
                ),
            ):
                produce_backend_gate(
                    verifier,
                    context,
                    stage,
                    GATES[0],
                    ("a.AlphaTest",),
                    None,
                    BACKEND_ROOT,
                    stage / ".m2",
                    mock.Mock(),
                )
            output = stage / GATES[0].slug
            self.assertTrue((output / GATES[0].primary_filename).is_file())
            self.assertTrue((output / GATES[0].secondary_filename).is_file())

    def test_22a_backend_runtime_classes_receipt_binds_exact_bytes(self) -> None:
        with self._repo_temporary("w3-backend-classes-") as root:
            classes = root / "backend/target/classes"
            main = (
                classes
                / "com/szsemicon/hr/ShenzhouHrApplication.class"
            )
            resource = classes / "application.yml"
            main.parent.mkdir(parents=True)
            main.write_bytes(b"main-class-v1")
            resource.write_bytes(b"fixture: true\n")
            receipt = backend_runtime_classes_receipt(classes)
            self.assertEqual(receipt["fileCount"], 2)
            self.assertEqual(receipt["classFileCount"], 1)
            self.assertEqual(
                receipt["mainClassSha256"],
                hashlib.sha256(b"main-class-v1").hexdigest(),
            )
            main.write_bytes(b"main-class-v2")
            self.assertNotEqual(
                backend_runtime_classes_receipt(classes)["recordsSha256"],
                receipt["recordsSha256"],
            )
            linked = classes / "linked.class"
            linked.symlink_to(main)
            with self.assertRaisesRegex(
                BuildGateError, "symbolic/special"
            ):
                backend_runtime_classes_receipt(classes)

    def test_22b_existing_commit_rejects_bool_schema_version(self) -> None:
        with self._runs_temporary("w3-marker-bool-") as run_root:
            (run_root / "leaves").mkdir()
            stage = run_root / ".stage"
            stage.mkdir()
            for gate in GATES:
                parent = run_root / "leaves" / gate.slug
                parent.mkdir()
                output = stage / gate.slug
                output.mkdir()
                (output / gate.primary_filename).write_text(
                    "primary", encoding="utf-8"
                )
                if gate.secondary_filename:
                    (output / gate.secondary_filename).write_text(
                        "secondary", encoding="utf-8"
                    )
            context = self._context(0)
            manifest = freeze_stage(stage)
            commit_outputs(stage, run_root, manifest, context)
            marker_path = run_root / COMMIT_FILENAME
            marker = json.loads(marker_path.read_text(encoding="utf-8"))
            marker["schemaVersion"] = True
            os.chmod(marker_path, 0o600)
            marker_path.write_bytes(canonical_json(marker) + b"\n")
            os.chmod(marker_path, 0o400)
            with self.assertRaisesRegex(
                BuildGateError, "schemaVersion"
            ):
                validate_existing_complete_bundle(
                    run_root, context, manifest
                )

    def test_23_isolated_dependency_cache_write_does_not_touch_origin(self) -> None:
        with self._repo_temporary("w3-cache-isolation-") as root:
            origin = root / "origin"
            isolated = root / "isolated"
            origin.mkdir()
            isolated.mkdir()
            cache = origin / "cache.bin"
            cache.write_bytes(b"immutable-origin")
            seal = WorkspaceSeal(
                [
                    ("dependency-origin-dir", origin, False, True),
                    ("dependency-origin-file", cache, False, True),
                ]
            )
            try:
                isolated_cache = isolated / "cache.bin"
                isolated_cache.write_bytes(cache.read_bytes())
                isolated_cache.write_bytes(b"runtime-cache-write")
                seal.assert_unchanged()
                self.assertEqual(cache.read_bytes(), b"immutable-origin")
            finally:
                seal.close()

    def test_24_destination_preflight_rejects_symbolic_leaf_parent(self) -> None:
        with self._runs_temporary("w3-destination-") as run_root:
            outside = run_root / "outside"
            outside.mkdir()
            leaves = run_root / "leaves"
            leaves.mkdir()
            (leaves / GATES[0].slug).symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(BuildGateError, "symbolic"):
                assert_destinations_absent(run_root)

    def test_25_real_process_groups_leave_no_timeout_or_daemon_descendant(
        self,
    ) -> None:
        class Seal:
            receipt = {"schemaVersion": 1}

            @staticmethod
            def assert_unchanged() -> None:
                return

        seal = Seal()
        with self._repo_temporary("w3-real-pgid-") as root:
            daemon_script = root / "daemon.py"
            daemon_pid = root / "daemon.pid"
            daemon_script.write_text(
                "\n".join(
                    [
                        "import os, signal, sys, time",
                        "pid_path = sys.argv[1]",
                        "child = os.fork()",
                        "if child == 0:",
                        "    signal.signal(signal.SIGTERM, signal.SIG_IGN)",
                        "    os.close(1)",
                        "    os.close(2)",
                        "    with open(pid_path, 'w', encoding='utf-8') as stream:",
                        "        stream.write(str(os.getpid()))",
                        "        stream.flush()",
                        "        os.fsync(stream.fileno())",
                        "    while True:",
                        "        time.sleep(1)",
                        "for _ in range(500):",
                        "    if os.path.exists(pid_path):",
                        "        break",
                        "    time.sleep(0.01)",
                        "os._exit(0)",
                        "",
                    ]
                ),
                encoding="utf-8",
            )
            timeout_script = root / "timeout.py"
            timeout_pid = root / "timeout.pid"
            timeout_script.write_text(
                "\n".join(
                    [
                        "import os, signal, sys, time",
                        "pid_path = sys.argv[1]",
                        "signal.signal(signal.SIGTERM, signal.SIG_IGN)",
                        "child = os.fork()",
                        "if child == 0:",
                        "    signal.signal(signal.SIGTERM, signal.SIG_IGN)",
                        "    os.close(1)",
                        "    os.close(2)",
                        "    with open(pid_path, 'w', encoding='utf-8') as stream:",
                        "        stream.write(str(os.getpid()))",
                        "        stream.flush()",
                        "        os.fsync(stream.fileno())",
                        "    while True:",
                        "        time.sleep(1)",
                        "while True:",
                        "    time.sleep(1)",
                        "",
                    ]
                ),
                encoding="utf-8",
            )

            def execute_real(script: Path, pid_path: Path, timeout: float) -> str:
                resolved = [sys.executable, str(script), str(pid_path)]
                with mock.patch(
                    "scripts.qa.produce_wave3_build_gates.toolchain_receipt",
                    return_value=(resolved, {"schemaVersion": 1}),
                ):
                    with self.assertRaises(BuildGateError) as raised:
                        run_command(["fixture"], root, timeout, seal)
                self.assertTrue(pid_path.is_file())
                child_pid = int(pid_path.read_text(encoding="utf-8"))
                self._assert_pid_gone(child_pid)
                return str(raised.exception)

            daemon_error = execute_real(daemon_script, daemon_pid, 5)
            self.assertIn("left a live process-group descendant", daemon_error)
            timeout_error = execute_real(timeout_script, timeout_pid, 2.0)
            self.assertIn("timed out and was reaped", timeout_error)

    @staticmethod
    def _context(source_maximum_mtime_ns: int) -> dict[str, object]:
        return {
            "runId": "w3-build-selftest",
            "sourceTreeHash": "a" * 64,
            "databaseIdentity": "mysql8410:selftest:shenzhou_hr_test",
            "sourceMaximumMtimeNs": source_maximum_mtime_ns,
        }

    @staticmethod
    def _result(started_ns: int, completed_ns: int) -> CommandResult:
        return CommandResult(
            command=("npm", "run", "build"),
            cwd=FRONTEND_ROOT,
            started_at="2026-07-28T00:00:00.000000Z",
            started_ns=started_ns,
            completed_at="2026-07-28T00:00:01.000000Z",
            completed_ns=completed_ns,
            stdout="",
            stderr="",
            exit_code=0,
            toolchain={"receipt": "unit-test"},
        )

    @staticmethod
    def _write_suite(
        path: Path,
        name: str,
        tests: int,
        *,
        failures: int = 0,
        errors: int = 0,
        skipped: int = 0,
    ) -> None:
        cases: list[str] = []
        for index in range(tests):
            body = ""
            if index < failures:
                body = "<failure>failed</failure>"
            elif index < failures + errors:
                body = "<error>errored</error>"
            elif index < failures + errors + skipped:
                body = "<skipped/>"
            cases.append(
                f'<testcase classname="{name}" name="test-{index}">{body}</testcase>'
            )
        path.write_text(
            (
                f'<testsuite name="{name}" tests="{tests}" failures="{failures}" '
                f'errors="{errors}" skipped="{skipped}">'
                f"{''.join(cases)}</testsuite>\n"
            ),
            encoding="utf-8",
        )

    @staticmethod
    def _write_vitest(
        path: Path,
        files: tuple[str, ...],
        *,
        assertions_per_file: int,
        suite_count: int | None = None,
    ) -> None:
        test_results = []
        for relative in files:
            test_results.append(
                {
                    "name": str(FRONTEND_ROOT / relative),
                    "status": "passed",
                    "assertionResults": [
                        {"status": "passed", "title": f"test-{index}"}
                        for index in range(assertions_per_file)
                    ],
                }
            )
        tests = len(files) * assertions_per_file
        total_suites = len(files) if suite_count is None else suite_count
        value = {
            "numTotalTestSuites": total_suites,
            "numPassedTestSuites": total_suites,
            "numFailedTestSuites": 0,
            "numPendingTestSuites": 0,
            "numTotalTests": tests,
            "numPassedTests": tests,
            "numFailedTests": 0,
            "numPendingTests": 0,
            "numTodoTests": 0,
            "success": True,
            "testResults": test_results,
        }
        path.write_text(json.dumps(value), encoding="utf-8")

    @staticmethod
    @contextmanager
    def _repo_temporary(prefix: str):
        with tempfile.TemporaryDirectory(
            prefix=prefix, dir=REPOSITORY_ROOT
        ) as temporary:
            yield Path(temporary)

    @staticmethod
    def _assert_pid_gone(process_id: int) -> None:
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                os.kill(process_id, 0)
            except ProcessLookupError:
                return
            time.sleep(0.02)
        raise AssertionError(f"descendant process survived cleanup: {process_id}")

    @staticmethod
    @contextmanager
    def _runs_temporary(prefix: str):
        runs_root = REPOSITORY_ROOT / "docs/verification/wave3/runs"
        with tempfile.TemporaryDirectory(
            prefix=prefix, dir=runs_root
        ) as temporary:
            yield Path(temporary)


if __name__ == "__main__":
    unittest.main(verbosity=2)
