#!/usr/bin/env python3
"""Fail-closed provenance capture for the three W3 runtime evidence leaves.

The CLI prints one compact JSON document containing only public, sanitized
bindings. Private process argv/environment snapshots are consumed in memory
and are never emitted.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Mapping, Sequence

try:
    from scripts.qa import verify_wave3_payroll_zero as payroll
except ModuleNotFoundError:
    import verify_wave3_payroll_zero as payroll


SCHEMA_VERSION = 1
NODE_TYPE = "wave3-runtime-provenance"
MODES = ("w2", "normal", "demo")
EXACT_ORIGINS = {
    "backend": "http://127.0.0.1:8080",
    "normal": "http://127.0.0.1:5173",
    "demo": "http://127.0.0.1:4175",
}
MAX_DEMO_FILE_BYTES = 64 * 1024 * 1024
MAX_TRANSFORMED_GRAPH_NODES = 512
MAX_TRANSFORMED_GRAPH_BYTES = 64 * 1024 * 1024
MAX_TRANSFORMED_MODULE_BYTES = 24 * 1024 * 1024
VITE_CACHE_DIR = "node_modules/.cache/vite"
VITE_CACHE_REPOSITORY_RELATIVE = f"frontend/{VITE_CACHE_DIR}"
VITE_CACHE_URL_PREFIX = "/node_modules/.cache/vite/deps/"


class RuntimeProvenanceError(RuntimeError):
    """A public-safe provenance validation failure."""


def fail(message: str) -> None:
    raise RuntimeProvenanceError(message)


def strip_private_keys(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: strip_private_keys(item)
            for key, item in value.items()
            if not str(key).startswith("_private")
        }
    if isinstance(value, list):
        return [strip_private_keys(item) for item in value]
    if isinstance(value, tuple):
        return [strip_private_keys(item) for item in value]
    return value


def assert_public_document(value: Any, label: str = "provenance") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or key.startswith("_private"):
                fail(f"{label} contains a private or non-text key")
            assert_public_document(item, f"{label}.{key}")
    elif isinstance(value, (list, tuple)):
        for index, item in enumerate(value):
            assert_public_document(item, f"{label}[{index}]")
    elif not isinstance(value, (str, int, float, bool, type(None))):
        fail(f"{label} contains a non-JSON value")


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def exact_origin(value: str | None, expected: str, label: str) -> str:
    if value is None:
        fail(f"{label} is required")
    observed = payroll.validate_loopback_origin(value, label)
    if observed != expected:
        fail(f"{label} must be exactly {expected}")
    return observed


def source_records(
    scopes: Mapping[str, Sequence[Path]],
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in scopes["frontend-product-source"]:
        contents, metadata, _ = payroll.stable_file_bytes(
            path, "runtime provenance frontend source"
        )
        records.append(
            {
                "scopeId": "frontend-product-source",
                "path": path.relative_to(
                    payroll.REPOSITORY_ROOT
                ).as_posix(),
                "sizeBytes": len(contents),
                "mtimeEpochNs": metadata.st_mtime_ns,
                "sha256": payroll.sha256_bytes(contents),
            }
        )
    return records


def validate_frontend_process_build_binding(
    listener: Mapping[str, Any],
    build_receipt: Mapping[str, Any],
    *,
    label: str,
) -> dict[str, Any]:
    process = payroll.require_object(
        listener.get("processBinding"), f"{label} process binding"
    )
    toolchain = payroll.require_object(
        build_receipt.get("toolchain"), f"{label} build toolchain"
    )
    dependency = payroll.require_object(
        toolchain.get("frontendDependencyBinding"),
        f"{label} frontend dependency binding",
    )
    completed_ns = build_receipt.get("buildCompletedAtEpochNs")
    expected = {
        "executableRealpath": toolchain.get("nodeExecutableRealpath"),
        "executableSha256": toolchain.get("nodeExecutableSha256"),
        "viteEntryRealpath": dependency.get("viteEntryRealpath"),
        "viteEntrySizeBytes": dependency.get("viteEntrySizeBytes"),
        "viteEntrySha256": dependency.get("viteEntrySha256"),
    }
    if any(process.get(key) != value for key, value in expected.items()):
        fail(
            f"{label} live Node/Vite bytes differ from the committed "
            "tool/dependency manifests"
        )
    if (
        type(completed_ns) is not int
        or completed_ns <= 0
        or type(listener.get("processStartedAtEpochNs")) is not int
        or listener["processStartedAtEpochNs"] < completed_ns
    ):
        fail(f"{label} listener predates its committed fresh build")
    manifest_fields = {
        "buildBundleCommitSha256": build_receipt.get(
            "buildBundleCommitSha256"
        ),
        "buildBundleManifestSha256": build_receipt.get(
            "buildBundleManifestSha256"
        ),
        "workspaceManifestSha256": toolchain.get(
            "workspaceManifestSha256"
        ),
        "dependencyManifestSha256": toolchain.get(
            "dependencyManifestSha256"
        ),
        "commandSupportManifestSha256": toolchain.get(
            "commandSupportManifestSha256"
        ),
        "toolRuntimeManifestSha256": toolchain.get(
            "toolRuntimeManifestSha256"
        ),
    }
    if any(
        not isinstance(value, str)
        or not payroll.SHA256_PATTERN.fullmatch(value)
        for value in manifest_fields.values()
    ):
        fail(f"{label} committed build manifest digests are invalid")
    if (
        dependency.get("dependencyManifestSha256")
        != manifest_fields["dependencyManifestSha256"]
        or toolchain.get("nodeExecutableSha256")
        != process.get("executableSha256")
    ):
        fail(f"{label} tool/dependency receipt cross-binding is invalid")
    return {
        **manifest_fields,
        "nodeExecutableRealpath": process["executableRealpath"],
        "nodeExecutableSha256": process["executableSha256"],
        "viteEntryRealpath": process["viteEntryRealpath"],
        "viteEntrySizeBytes": process["viteEntrySizeBytes"],
        "viteEntrySha256": process["viteEntrySha256"],
        "buildCompletedAtEpochNs": completed_ns,
        "listenerStartedAtEpochNs": listener["processStartedAtEpochNs"],
        "comparison": "exact-live-bytes-to-committed-manifests",
        "verdict": "PASS",
    }


def parse_module_imports(
    body: str,
    *,
    node_executable: str,
) -> list[dict[str, Any]]:
    program = r"""
import { init, parse } from 'es-module-lexer';
import { createHash } from 'node:crypto';
await init;
let source = '';
process.stdin.setEncoding('utf8');
for await (const chunk of process.stdin) source += chunk;
const [imports] = parse(source);
const output = imports
  .filter((entry) => entry.d !== -2)
  .map((entry) => {
    if (entry.d >= 0 && entry.n === undefined) {
      const expression = source.slice(entry.s, entry.e);
      const statement = source.slice(entry.ss, entry.se);
      const marker = '@vite-ignore';
      const markerRelativeStart = statement.indexOf(marker);
      return {
        kind: 'nonliteral-dynamic',
        expressionStart: entry.s,
        expressionEnd: entry.e,
        expressionSizeBytes: Buffer.byteLength(expression, 'utf8'),
        expressionSha256: createHash('sha256')
          .update(expression, 'utf8')
          .digest('hex'),
        importStatementStart: entry.ss,
        importStatementEnd: entry.se,
        importStatementSizeBytes: Buffer.byteLength(statement, 'utf8'),
        importStatementSha256: createHash('sha256')
          .update(statement, 'utf8')
          .digest('hex'),
        viteIgnorePresent: markerRelativeStart >= 0,
        viteIgnoreMarkerStart: markerRelativeStart < 0
          ? -1
          : entry.ss + markerRelativeStart,
        viteIgnoreMarkerEnd: markerRelativeStart < 0
          ? -1
          : entry.ss + markerRelativeStart + marker.length,
      };
    }
    return {
      specifier: entry.n,
      dynamic: entry.d >= 0,
      start: entry.s,
      end: entry.e,
    };
  });
process.stdout.write(JSON.stringify(output));
""".strip()
    try:
        result = subprocess.run(
            (
                node_executable,
                "--input-type=module",
                "--eval",
                program,
            ),
            cwd=payroll.REPOSITORY_ROOT / "frontend",
            env=payroll.safe_subprocess_environment(),
            input=body,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=payroll.SUBPROCESS_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        fail("bound ESM import parser could not execute")
    if result.returncode != 0 or result.stderr:
        fail("bound ESM import parser failed")
    try:
        value = payroll.strict_json_loads(
            result.stdout, "bound ESM import parser output"
        )
    except payroll.ZeroDiscoverabilityError as error:
        fail(str(error))
    rows = payroll.require_array(value, "bound ESM import parser output")
    parsed: list[dict[str, Any]] = []
    encoded_body_utf16: bytes | None = None
    for index, raw_row in enumerate(rows):
        if (
            isinstance(raw_row, dict)
            and raw_row.get("kind") == "nonliteral-dynamic"
        ):
            row = payroll.require_exact_keys(
                raw_row,
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
                f"bound ESM import parser row[{index}]",
            )
            start = row["expressionStart"]
            end = row["expressionEnd"]
            statement_start = row["importStatementStart"]
            statement_end = row["importStatementEnd"]
            if (
                type(start) is not int
                or type(end) is not int
                or type(row["expressionSizeBytes"]) is not int
                or not isinstance(row["expressionSha256"], str)
                or not payroll.SHA256_PATTERN.fullmatch(
                    row["expressionSha256"]
                )
                or type(statement_start) is not int
                or type(statement_end) is not int
                or type(row["importStatementSizeBytes"]) is not int
                or not isinstance(row["importStatementSha256"], str)
                or not payroll.SHA256_PATTERN.fullmatch(
                    row["importStatementSha256"]
                )
                or type(row["viteIgnorePresent"]) is not bool
                or type(row["viteIgnoreMarkerStart"]) is not int
                or type(row["viteIgnoreMarkerEnd"]) is not int
                or start < 0
                or end <= start
                or statement_start < 0
                or statement_end <= statement_start
                or not (
                    statement_start
                    <= start
                    < end
                    <= statement_end
                )
            ):
                fail(
                    "bound ESM import parser returned an invalid "
                    "nonliteral dynamic import"
                )
            if encoded_body_utf16 is None:
                try:
                    encoded_body_utf16 = body.encode(
                        "utf-16-le", errors="strict"
                    )
                except UnicodeError:
                    fail(
                        "bound ESM import parser parent body is not "
                        "strict Unicode"
                    )
            if statement_end * 2 > len(encoded_body_utf16):
                fail(
                    "bound ESM import parser returned an out-of-range "
                    "nonliteral dynamic import"
                )
            try:
                expression = encoded_body_utf16[
                    start * 2 : end * 2
                ].decode("utf-16-le", errors="strict")
                statement = encoded_body_utf16[
                    statement_start * 2 : statement_end * 2
                ].decode(
                    "utf-16-le", errors="strict"
                )
                expression_bytes = expression.encode(
                    "utf-8", errors="strict"
                )
                statement_bytes = statement.encode(
                    "utf-8", errors="strict"
                )
            except UnicodeError:
                fail(
                    "bound ESM import parser split a Unicode scalar in "
                    "a nonliteral dynamic import"
                )
            if (
                len(expression_bytes) != row["expressionSizeBytes"]
                or payroll.sha256_bytes(expression_bytes)
                != row["expressionSha256"]
            ):
                fail(
                    "bound ESM import parser nonliteral dynamic import "
                    "does not match the parent module body"
                )
            if (
                len(statement_bytes) != row["importStatementSizeBytes"]
                or payroll.sha256_bytes(statement_bytes)
                != row["importStatementSha256"]
            ):
                fail(
                    "bound ESM import parser import statement does not "
                    "match the parent module body"
                )
            marker_utf16 = "@vite-ignore".encode("utf-16-le")
            statement_utf16 = encoded_body_utf16[
                statement_start * 2 : statement_end * 2
            ]
            marker_offsets: list[int] = []
            search_offset = 0
            while True:
                marker_byte_offset = statement_utf16.find(
                    marker_utf16, search_offset
                )
                if marker_byte_offset < 0:
                    break
                if marker_byte_offset % 2:
                    fail(
                        "bound ESM import parser found a misaligned "
                        "Vite-ignore marker"
                    )
                marker_offsets.append(
                    statement_start + marker_byte_offset // 2
                )
                search_offset = marker_byte_offset + len(marker_utf16)
            marker_present = len(marker_offsets) == 1
            expected_marker_start = (
                marker_offsets[0] if marker_present else -1
            )
            expected_marker_end = (
                expected_marker_start + len("@vite-ignore")
                if marker_present
                else -1
            )
            if (
                len(marker_offsets) > 1
                or row["viteIgnorePresent"] != marker_present
                or row["viteIgnoreMarkerStart"]
                != expected_marker_start
                or row["viteIgnoreMarkerEnd"] != expected_marker_end
            ):
                fail(
                    "bound ESM import parser Vite-ignore marker "
                    "binding is invalid"
                )
            parsed.append(dict(row))
            continue
        row = payroll.require_exact_keys(
            raw_row,
            {"specifier", "dynamic", "start", "end"},
            f"bound ESM import parser row[{index}]",
        )
        if (
            not isinstance(row["specifier"], str)
            or not row["specifier"]
            or "\x00" in row["specifier"]
            or type(row["dynamic"]) is not bool
            or type(row["start"]) is not int
            or type(row["end"]) is not int
            or row["start"] < 0
            or row["end"] <= row["start"]
        ):
            fail("bound ESM import parser returned an invalid row")
        parsed.append(dict(row))
    return parsed


def validate_nonliteral_dynamic_import_parent(
    parent_target: str,
    row: Mapping[str, Any],
) -> str:
    path = urllib.parse.urlsplit(parent_target).path
    if path == "/@vite/client":
        return "exact-sealed-vite-client-parent"
    if path.startswith(VITE_CACHE_URL_PREFIX):
        if row.get("viteIgnorePresent") is not True:
            fail(
                "optimized dependency nonliteral dynamic import lacks "
                "its bound Vite-ignore marker"
            )
        return "exact-sealed-vite-cache-dependency-parent"
    if path == "/@react-refresh":
        if row.get("viteIgnorePresent") is not True:
            fail(
                "React-refresh nonliteral dynamic import lacks its "
                "bound Vite-ignore marker"
            )
        return "exact-sealed-react-refresh-parent"
    fail(
        "nonliteral dynamic import appears outside exact sealed "
        "runtime parent namespaces"
    )


def resolve_module_target(
    frontend_origin: str,
    current_target: str,
    specifier: str,
) -> str:
    if specifier.startswith(("data:", "node:", "\x00")):
        fail("transformed frontend graph contains an unbound import scheme")
    if not specifier.startswith(("/", "./", "../", "http://")):
        fail("transformed frontend graph contains an unresolved bare import")
    resolved = urllib.parse.urlsplit(
        urllib.parse.urljoin(frontend_origin + current_target, specifier)
    )
    decoded_path = urllib.parse.unquote(resolved.path)
    if (
        resolved.scheme != "http"
        or f"{resolved.scheme}://{resolved.netloc}" != frontend_origin
        or resolved.username
        or resolved.password
        or resolved.fragment
        or not resolved.path.startswith("/")
        or resolved.path.startswith("/api")
        or "\\" in resolved.path
        or "\x00" in decoded_path
        or "\\" in decoded_path
        or any(
            part in {"", ".", ".."}
            for part in Path(decoded_path).parts[1:]
        )
        or re.search(r"%(?:2e|2f|5c)", resolved.path, re.IGNORECASE)
    ):
        fail("transformed frontend graph import escapes its bound origin")
    non_cache_node_module = (
        resolved.path.startswith("/node_modules/")
        and not resolved.path.startswith("/node_modules/.")
    )
    allowed_path = (
        resolved.path.startswith("/src/")
        or resolved.path.startswith(VITE_CACHE_URL_PREFIX)
        or non_cache_node_module
        or resolved.path
        in {"/@vite/client", "/@vite/env", "/@react-refresh"}
    )
    if not allowed_path:
        fail(
            "transformed frontend graph import is outside fixed "
            "runtime namespaces"
        )
    return urllib.parse.urlunsplit(
        ("", "", resolved.path, resolved.query, "")
    )


def vite_cache_configuration_binding() -> dict[str, Any]:
    config_path = payroll.REPOSITORY_ROOT / "frontend/vite.config.ts"
    contents, _metadata, _realpath = payroll.stable_file_bytes(
        config_path, "normal Vite cacheDir configuration"
    )
    try:
        text = contents.decode("utf-8", errors="strict")
    except UnicodeError:
        fail("normal Vite cacheDir configuration is not strict UTF-8")
    declaration = f"cacheDir: '{VITE_CACHE_DIR}',"
    if text.count("cacheDir:") != 1 or text.count(declaration) != 1:
        fail("normal Vite cacheDir configuration is not exact")
    return {
        "configurationPath": "frontend/vite.config.ts",
        "configurationSha256": payroll.sha256_bytes(contents),
        "configuredCacheDir": VITE_CACHE_DIR,
        "runtimeUrlPrefix": VITE_CACHE_URL_PREFIX,
        "verdict": "PASS",
    }


def fetch_transformed_module_text(
    frontend_origin: str,
    target: str,
) -> str:
    request = urllib.request.Request(
        frontend_origin + target,
        headers={
            "Accept": "text/javascript,*/*;q=0.1",
            "Accept-Encoding": "identity",
            "Cache-Control": "no-cache",
        },
        method="GET",
    )
    try:
        response: Any = payroll.HTTP_OPENER.open(
            request, timeout=payroll.HTTP_TIMEOUT_SECONDS
        )
    except urllib.error.HTTPError as error:
        fail(f"transformed frontend module returned HTTP {error.code}")
    except (OSError, urllib.error.URLError, TimeoutError):
        fail("transformed frontend module could not reach bound Vite")
    try:
        body = response.read(MAX_TRANSFORMED_MODULE_BYTES + 1)
    except OSError:
        fail("transformed frontend module response is unreadable")
    requested = urllib.parse.urlsplit(frontend_origin + target)
    final = urllib.parse.urlsplit(response.geturl())
    safe_target = (
        target
        if len(target) <= 2048
        and re.fullmatch(
            r"/[A-Za-z0-9@._~!$&'()*+,;=:/?%+-]*", target
        )
        else "<invalid-target>"
    )
    if len(body) > MAX_TRANSFORMED_MODULE_BYTES:
        fail(
            "transformed frontend module "
            f"target={safe_target} observedSizeBytes={len(body)} "
            f"exceeds boundBytes={MAX_TRANSFORMED_MODULE_BYTES}"
        )
    if (
        int(response.status) != 200
        or final.scheme != requested.scheme
        or final.netloc != requested.netloc
        or final.path != requested.path
        or final.query != requested.query
        or final.fragment
        or response.headers.get("Content-Encoding") not in (None, "identity")
    ):
        fail("transformed frontend module response escaped or exceeded bounds")
    payroll.scan_response_headers_in_memory(
        response.headers, "transformed frontend module response"
    )
    try:
        return body.decode("utf-8", errors="strict")
    except UnicodeError:
        fail("transformed frontend module response is not strict UTF-8")


def verify_transformed_module_graph(
    frontend_origin: str,
    *,
    node_executable: str,
    reverse_roots: bool = False,
) -> dict[str, Any]:
    roots = ["/src/main.tsx", "/src/app/App.tsx"]
    if reverse_roots:
        roots.reverse()
    pending = list(roots)
    seen: set[str] = set()
    nodes: list[dict[str, Any]] = []
    total_bytes = 0
    nonliteral_dynamic_import_count = 0
    while pending:
        target = pending.pop(0)
        if target in seen:
            continue
        if len(seen) >= MAX_TRANSFORMED_GRAPH_NODES:
            fail("transformed frontend import graph exceeds its node bound")
        body = fetch_transformed_module_text(frontend_origin, target)
        encoded = body.encode("utf-8")
        total_bytes += len(encoded)
        if total_bytes > MAX_TRANSFORMED_GRAPH_BYTES:
            fail("transformed frontend import graph exceeds its byte bound")
        imports: list[dict[str, Any]] = []
        for row in parse_module_imports(
            body, node_executable=node_executable
        ):
            if row.get("kind") == "nonliteral-dynamic":
                imports.append(
                    {
                        **dict(row),
                        "acceptedParentPolicy": (
                            validate_nonliteral_dynamic_import_parent(
                                target, row
                            )
                        ),
                    }
                )
                nonliteral_dynamic_import_count += 1
                continue
            resolved = resolve_module_target(
                frontend_origin, target, row["specifier"]
            )
            imports.append(
                {
                    "specifier": row["specifier"],
                    "resolvedTarget": resolved,
                    "dynamic": row["dynamic"],
                    "start": row["start"],
                    "end": row["end"],
                }
            )
            if resolved not in seen and resolved not in pending:
                pending.append(resolved)
        nodes.append(
            {
                "target": target,
                "sizeBytes": len(encoded),
                "sha256": payroll.sha256_bytes(encoded),
                "imports": imports,
            }
        )
        seen.add(target)
    nodes.sort(key=lambda item: item["target"].encode("utf-8"))
    targets = {node["target"] for node in nodes}
    if not set(roots).issubset(targets):
        fail("transformed frontend graph lacks exact main/App roots")
    framed = b"".join(
        payroll.canonical_json_bytes(node) for node in nodes
    )
    return {
        "bindingKind": (
            "vite-transformed-esm-body-and-import-graph-equivalence"
        ),
        "origin": frontend_origin,
        "rootTargets": ["/src/main.tsx", "/src/app/App.tsx"],
        "nodeCount": len(nodes),
        "totalBodyBytes": total_bytes,
        "nonliteralDynamicImportCount": (
            nonliteral_dynamic_import_count
        ),
        "graphSha256": payroll.sha256_bytes(framed),
        "nodes": nodes,
        "verdict": "PASS",
    }


def normal_vite_cache_binding(
    listener: Mapping[str, Any],
) -> dict[str, Any]:
    configuration = vite_cache_configuration_binding()
    cache_root = (
        payroll.REPOSITORY_ROOT / VITE_CACHE_REPOSITORY_RELATIVE
    )
    try:
        root_metadata_before = cache_root.lstat()
    except OSError:
        fail("normal Vite cacheDir is missing")
    if (
        not cache_root.is_dir()
        or cache_root.is_symlink()
        or root_metadata_before.st_ctime_ns
        < listener["processStartedAtEpochNs"]
    ):
        fail(
            "normal Vite cacheDir is unsafe or predates the bound listener"
        )
    snapshot = payroll.stable_tree_snapshot(
        cache_root, "normal Vite runtime cacheDir"
    )
    root_metadata_after = cache_root.lstat()
    root_identity_before = (
        root_metadata_before.st_dev,
        root_metadata_before.st_ino,
        root_metadata_before.st_mode,
        root_metadata_before.st_mtime_ns,
        root_metadata_before.st_ctime_ns,
    )
    root_identity_after = (
        root_metadata_after.st_dev,
        root_metadata_after.st_ino,
        root_metadata_after.st_mode,
        root_metadata_after.st_mtime_ns,
        root_metadata_after.st_ctime_ns,
    )
    if root_identity_before != root_identity_after:
        fail("normal Vite cacheDir changed during its tree snapshot")
    records = payroll.require_array(
        snapshot.get("records"), "normal Vite runtime cache records"
    )
    paths = {str(record.get("path")) for record in records}
    if (
        "deps/_metadata.json" not in paths
        or "deps/package.json" not in paths
        or not any(path.startswith("deps/") and path.endswith(".js") for path in paths)
        or any(not path.startswith("deps/") for path in paths)
        or any(
            type(record.get("ctimeEpochNs")) is not int
            or record["ctimeEpochNs"]
            < listener["processStartedAtEpochNs"]
            for record in records
        )
    ):
        fail(
            "normal Vite cacheDir is incomplete, stale, or outside the "
            "fixed deps closure"
        )
    return {
        "bindingKind": "complete-vite-runtime-cache-tree-snapshot",
        "commandOwnedRuntimeOutput": True,
        "rootRealpath": snapshot["rootRealpath"],
        "rootDevice": root_metadata_before.st_dev,
        "rootInode": root_metadata_before.st_ino,
        "rootMode": root_metadata_before.st_mode & 0o7777,
        "rootMtimeEpochNs": root_metadata_before.st_mtime_ns,
        "rootCtimeEpochNs": root_metadata_before.st_ctime_ns,
        "listenerStartedAtEpochNs": listener["processStartedAtEpochNs"],
        "configuration": configuration,
        "fileCount": snapshot["fileCount"],
        "treeSha256": snapshot["treeSha256"],
        "minimumMtimeEpochNs": snapshot["minimumMtimeEpochNs"],
        "maximumMtimeEpochNs": snapshot["maximumMtimeEpochNs"],
        "minimumCtimeEpochNs": snapshot["minimumCtimeEpochNs"],
        "maximumCtimeEpochNs": snapshot["maximumCtimeEpochNs"],
        "records": snapshot["records"],
        "verdict": "PASS",
    }


def select_fresh_builds(
    freshness: Mapping[str, Any],
    *,
    include_prod: bool,
    include_demo: bool,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "buildInputFileCount": freshness["buildInputFileCount"],
        "buildInputMaximumMtimeEpochNs": freshness[
            "buildInputMaximumMtimeEpochNs"
        ],
        "buildInputMaximumCtimeEpochNs": freshness[
            "buildInputMaximumCtimeEpochNs"
        ],
        "buildInputFreshnessFloorEpochNs": freshness[
            "buildInputFreshnessFloorEpochNs"
        ],
    }
    if include_prod:
        result.update(
            {
                "prodDistMinimumMtimeEpochNs": freshness[
                    "prodDistMinimumMtimeEpochNs"
                ],
                "prodDistMinimumCtimeEpochNs": freshness[
                    "prodDistMinimumCtimeEpochNs"
                ],
                "prodBuildReceipt": freshness["prodBuildReceipt"],
            }
        )
    if include_demo:
        result.update(
            {
                "demoDistMinimumMtimeEpochNs": freshness[
                    "demoDistMinimumMtimeEpochNs"
                ],
                "demoDistMinimumCtimeEpochNs": freshness[
                    "demoDistMinimumCtimeEpochNs"
                ],
                "demoBuildReceipt": freshness["demoBuildReceipt"],
            }
        )
    result["verdict"] = "PASS"
    return result


def validate_dist_records_against_receipt(
    records: Sequence[Mapping[str, Any]],
    build_receipt: Mapping[str, Any],
) -> dict[str, Any]:
    normalized = [
        {
            "path": record.get("path"),
            "sizeBytes": record.get("sizeBytes"),
            "sha256": record.get("sha256"),
        }
        for record in records
    ]
    for index, record in enumerate(normalized):
        path_value = record["path"]
        if (
            not isinstance(path_value, str)
            or not path_value
            or unicodedata.normalize("NFC", path_value) != path_value
            or Path(path_value).is_absolute()
            or Path(path_value).as_posix() != path_value
            or "\\" in path_value
            or any(
                part in {"", ".", ".."} for part in Path(path_value).parts
            )
            or type(record["sizeBytes"]) is not int
            or record["sizeBytes"] < 0
            or not isinstance(record["sha256"], str)
            or not payroll.SHA256_PATTERN.fullmatch(record["sha256"])
        ):
            fail(f"demo dist record[{index}] is invalid")
    if normalized != sorted(
        normalized, key=lambda item: item["path"].encode("utf-8")
    ) or len({record["path"] for record in normalized}) != len(normalized):
        fail("demo dist records are not unique/canonically ordered")
    content_tree = payroll.sha256_bytes(
        b"".join(payroll.canonical_json_bytes(record) for record in normalized)
    )
    if (
        build_receipt.get("distFileCount") != len(normalized)
        or build_receipt.get("distContentTreeSha256") != content_tree
    ):
        fail("demo dist bytes differ from the committed demo build receipt")
    return {
        "fileCount": len(normalized),
        "totalSizeBytes": sum(
            int(record["sizeBytes"]) for record in normalized
        ),
        "contentTreeSha256": content_tree,
        "records": normalized,
    }


def fetch_exact_demo_file(
    frontend_origin: str,
    relative_path: str,
    expected_contents: bytes,
) -> None:
    if len(expected_contents) > MAX_DEMO_FILE_BYTES:
        fail("demo dist file exceeds the bounded served-byte verifier")
    target_path = "/" + urllib.parse.quote(relative_path, safe="/")
    request = urllib.request.Request(
        frontend_origin + target_path,
        headers={
            "Accept": "*/*",
            "Accept-Encoding": "identity",
            "Cache-Control": "no-cache",
        },
        method="GET",
    )
    try:
        response: Any = payroll.HTTP_OPENER.open(
            request, timeout=payroll.HTTP_TIMEOUT_SECONDS
        )
    except urllib.error.HTTPError as error:
        fail(f"demo served-byte request returned HTTP {error.code}")
    except (OSError, urllib.error.URLError, TimeoutError):
        fail("demo served-byte request could not reach the bound preview")
    try:
        body = response.read(len(expected_contents) + 1)
    except OSError:
        fail("demo served-byte response is unreadable")
    final_url = urllib.parse.urlsplit(response.geturl())
    if (
        int(response.status) != 200
        or final_url.scheme != "http"
        or f"{final_url.scheme}://{final_url.netloc}" != frontend_origin
        or urllib.parse.unquote(final_url.path) != f"/{relative_path}"
        or final_url.query
        or final_url.fragment
        or response.headers.get("Content-Encoding") not in (None, "identity")
    ):
        fail("demo served-byte response escaped or transformed its target")
    payroll.scan_response_headers_in_memory(
        response.headers, "demo served-byte response"
    )
    if body != expected_contents:
        fail("demo preview served bytes differ from current dist/demo")


def verify_demo_served_bytes(
    frontend_origin: str,
    demo_files: Sequence[Path],
    build_receipt: Mapping[str, Any],
) -> dict[str, Any]:
    dist_root = payroll.REPOSITORY_ROOT / "frontend/dist/demo"
    records: list[dict[str, Any]] = []
    for path in demo_files:
        before, before_metadata, _ = payroll.stable_file_bytes(
            path, "demo served-byte source file"
        )
        relative = path.relative_to(dist_root).as_posix()
        fetch_exact_demo_file(frontend_origin, relative, before)
        after, after_metadata, _ = payroll.stable_file_bytes(
            path, "demo served-byte source file final seal"
        )
        before_identity = (
            before_metadata.st_dev,
            before_metadata.st_ino,
            before_metadata.st_mode,
            before_metadata.st_size,
            before_metadata.st_mtime_ns,
            before_metadata.st_ctime_ns,
        )
        after_identity = (
            after_metadata.st_dev,
            after_metadata.st_ino,
            after_metadata.st_mode,
            after_metadata.st_size,
            after_metadata.st_mtime_ns,
            after_metadata.st_ctime_ns,
        )
        if before != after or before_identity != after_identity:
            fail("demo dist changed during served-byte verification")
        records.append(
            {
                "path": relative,
                "sizeBytes": len(before),
                "sha256": payroll.sha256_bytes(before),
            }
        )
    records.sort(key=lambda item: item["path"].encode("utf-8"))
    receipt_binding = validate_dist_records_against_receipt(
        records, build_receipt
    )
    return {
        "bindingKind": "every-dist-file-http-byte-equivalence",
        "origin": frontend_origin,
        **receipt_binding,
        "verdict": "PASS",
    }


def capture(
    *,
    mode: str,
    run_context: Path,
    runtime_env: Path,
    backend_url: str | None,
    frontend_url: str | None,
) -> dict[str, Any]:
    if mode not in MODES:
        fail("runtime provenance mode is invalid")
    payroll.require_repository_root()
    context = payroll.load_run_context(run_context)
    payroll.assert_frozen_source(context)
    runtime_environment = payroll.read_runtime_environment(
        runtime_env, context["databaseIdentity"]
    )
    binding: dict[str, Any]
    if mode == "w2":
        backend_origin = exact_origin(
            backend_url, EXACT_ORIGINS["backend"], "backend URL"
        )
        backend = payroll.listener_process_binding(
            backend_origin,
            component="backend",
            context=context,
            runtime_environment=runtime_environment,
        )
        binding = {
            "backend": strip_private_keys(backend),
            "verdict": "PASS",
        }
    elif mode == "normal":
        backend_origin = exact_origin(
            backend_url, EXACT_ORIGINS["backend"], "backend URL"
        )
        frontend_origin = exact_origin(
            frontend_url, EXACT_ORIGINS["normal"], "frontend URL"
        )
        scopes = payroll.collect_product_scope_files()
        freshness = payroll.validate_fresh_dist(context, scopes)
        backend = payroll.listener_process_binding(
            backend_origin,
            component="backend",
            context=context,
            runtime_environment=runtime_environment,
        )
        frontend = payroll.listener_process_binding(
            frontend_origin,
            component="frontend",
            context=context,
        )
        prod_receipt = payroll.require_object(
            freshness.get("prodBuildReceipt"),
            "normal production build receipt",
        )
        process_build = validate_frontend_process_build_binding(
            frontend,
            prod_receipt,
            label="normal frontend",
        )
        raw_source = payroll.verify_frontend_source_binding(
            frontend_origin,
            scopes,
            source_records(scopes),
        )
        frontend_process = payroll.require_object(
            frontend.get("processBinding"),
            "normal frontend process binding",
        )
        graph_forward = verify_transformed_module_graph(
            frontend_origin,
            node_executable=str(frontend_process["executableRealpath"]),
        )
        cache_forward = normal_vite_cache_binding(frontend)
        graph_reverse = verify_transformed_module_graph(
            frontend_origin,
            node_executable=str(frontend_process["executableRealpath"]),
            reverse_roots=True,
        )
        cache_reverse = normal_vite_cache_binding(frontend)
        if canonical_bytes(graph_forward) != canonical_bytes(
            graph_reverse
        ):
            fail(
                "normal Vite transformed module graph changed during "
                "provenance capture"
            )
        if canonical_bytes(cache_forward) != canonical_bytes(cache_reverse):
            fail(
                "normal Vite runtime cacheDir changed during provenance "
                "capture"
            )
        binding = {
            "backend": strip_private_keys(backend),
            "normalFrontend": strip_private_keys(frontend),
            "freshBuilds": strip_private_keys(
                select_fresh_builds(
                    freshness, include_prod=True, include_demo=True
                )
            ),
            "frontendProcessBuildBinding": process_build,
            "viteRawSourceBinding": raw_source,
            "viteTransformedModuleGraph": graph_forward,
            "viteRuntimeCacheBinding": cache_forward,
            "verdict": "PASS",
        }
    else:
        frontend_origin = exact_origin(
            frontend_url, EXACT_ORIGINS["demo"], "demo frontend URL"
        )
        scopes = payroll.collect_product_scope_files()
        freshness = payroll.validate_fresh_dist(context, scopes)
        preview = payroll.listener_process_binding(
            frontend_origin,
            component="demo",
            context=context,
        )
        demo_receipt = payroll.require_object(
            freshness.get("demoBuildReceipt"),
            "demo build receipt",
        )
        process_build = validate_frontend_process_build_binding(
            preview,
            demo_receipt,
            label="demo preview",
        )
        served = verify_demo_served_bytes(
            frontend_origin,
            scopes["frontend-demo-dist"],
            demo_receipt,
        )
        binding = {
            "demoPreview": strip_private_keys(preview),
            "freshDemoBuild": strip_private_keys(
                select_fresh_builds(
                    freshness, include_prod=False, include_demo=True
                )
            ),
            "frontendProcessBuildBinding": process_build,
            "servedDemoDistBinding": served,
            "verdict": "PASS",
        }
    payroll.assert_frozen_source(context)
    public_binding = strip_private_keys(binding)
    assert_public_document(public_binding)
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "nodeType": NODE_TYPE,
        "mode": mode,
        "runId": context["runId"],
        "sourceTreeHash": context["sourceTreeHash"],
        "databaseIdentity": context["databaseIdentity"],
        "binding": public_binding,
        "bindingSha256": payroll.sha256_bytes(
            canonical_bytes(public_binding)
        ),
        "verdict": "PASS",
    }
    assert_public_document(document)
    return document


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Capture sanitized W3 runtime provenance"
    )
    parser.add_argument("command", choices=("capture",))
    parser.add_argument("--mode", required=True, choices=MODES)
    parser.add_argument("--run-context", required=True, type=Path)
    parser.add_argument("--runtime-env", required=True, type=Path)
    parser.add_argument("--backend-url")
    parser.add_argument("--frontend-url")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    try:
        document = capture(
            mode=arguments.mode,
            run_context=arguments.run_context,
            runtime_env=arguments.runtime_env,
            backend_url=arguments.backend_url,
            frontend_url=arguments.frontend_url,
        )
    except (
        RuntimeProvenanceError,
        payroll.ZeroDiscoverabilityError,
        OSError,
        ValueError,
    ) as error:
        print(
            f"[wave3-runtime-provenance] ERROR: {error}",
            file=sys.stderr,
        )
        return 1
    sys.stdout.buffer.write(canonical_bytes(document) + b"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
