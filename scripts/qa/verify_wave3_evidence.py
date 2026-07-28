#!/usr/bin/env python3
"""Strict WAVE-3 source-freeze and evidence-DAG verifier.

This module deliberately does not decide whether a backend, browser, MySQL, or
other semantic gate passed.  A gate runner must emit the marker fixed by
``wave3-evidence-contract-v1.json``.  This verifier binds that raw evidence to
one fresh run, one normalized source tree, and one database identity, and then
builds the one-way evidence graph required by the W3 verification spec.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import time
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Sequence


RUN_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{5,63}$")
TASK_LINE_PATTERN = re.compile(
    rb"^(?P<prefix>[ \t]*- )\[(?P<token> |x)\] "
    rb"(?P<task_id>[0-9]+(?:\.[0-9]+)*) (?P<description>.*?)(?P<newline>\r?\n)?$"
)
TASK_CHECKBOX_PATTERN = re.compile(rb"(?m)^([ \t]*- )\[(?: |x)\]")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
FORBIDDEN_STRUCTURED_TOKEN = "NOT_VERIFIED"
HISTORICAL_RUN_ID = "w3-20260726-1917"
CONTEXT_PREFIX = "W3_EVIDENCE_CONTEXT=PASS"
BUILD_GATE_COMMIT_FILENAME = "wave3-build-gates.commit.json"
BUILD_GATE_ARTIFACTS: dict[str, tuple[str, tuple[tuple[str, str], ...]]] = {
    "W3-VER-BACKEND-FULL": (
        "backend-full",
        (
            ("backend-full-log", "backend-full-log.log"),
            ("backend-test-summary", "backend-test-summary.json"),
        ),
    ),
    "W3-VER-W1-REGRESSION": (
        "w1-regression",
        (("w1-regression-log", "w1-regression-log.log"),),
    ),
    "W3-VER-W2-REGRESSION": (
        "w2-regression",
        (("w2-regression-log", "w2-regression-log.log"),),
    ),
    "W3-VER-W3-REGRESSION": (
        "w3-regression",
        (("w3-regression-log", "w3-regression-log.log"),),
    ),
    "W3-VER-FRONTEND-TYPECHECK": (
        "frontend-typecheck",
        (("frontend-typecheck-log", "frontend-typecheck-log.log"),),
    ),
    "W3-VER-FRONTEND-LINT": (
        "frontend-lint",
        (("frontend-lint-log", "frontend-lint-log.log"),),
    ),
    "W3-VER-FRONTEND-TESTS": (
        "frontend-tests",
        (
            ("frontend-tests-log", "frontend-tests-log.log"),
            ("frontend-test-summary", "frontend-test-summary.json"),
        ),
    ),
    "W3-VER-PROD-BUILD": (
        "prod-build",
        (
            ("prod-build-log", "prod-build-log.log"),
            ("prod-dist-inventory", "prod-dist-inventory.json"),
        ),
    ),
    "W3-VER-DEMO-BUILD": (
        "demo-build",
        (
            ("demo-build-log", "demo-build-log.log"),
            ("demo-dist-inventory", "demo-dist-inventory.json"),
        ),
    ),
}
BUILD_GATE_IDS = tuple(BUILD_GATE_ARTIFACTS)
REQUIRED_SOURCE_ROOTS = [
    "backend",
    "frontend",
    "api",
    "openspec/changes/wave3-attendance-setup-and-policies",
    "scripts/qa",
    "deploy/mysql",
]
REQUIRED_ADDITIONAL_SOURCE_FILES = [
    "deploy/nginx/shenzhouhr.conf",
    "docs/verification/wave2/UIUX.md",
    "design/output/v1.9/v19-od-03-people-initial-import.html",
    "design/output/v1.9/v19-od-04-organization-employment.html",
    "design/output/v1.9/route-to-artifact.json",
    "design/output/v1.9/page-state-matrix.md",
    "design/output/v1.9/design-system/DESIGN.md",
    "design/output/v1.9/react-implementation-notes.md",
]
REQUIRED_EXCLUDED_DIRECTORIES = {
    ".git",
    ".npm-cache",
    "__pycache__",
    "dist",
    "node_modules",
    "target",
}

MIME_TYPES = {
    ".css": "text/css",
    ".har": "application/json",
    ".html": "text/html",
    ".json": "application/json",
    ".log": "text/plain",
    ".md": "text/markdown",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".tsv": "text/tab-separated-values",
    ".txt": "text/plain",
    ".webm": "video/webm",
    ".xml": "application/xml",
    ".yaml": "application/yaml",
    ".yml": "application/yaml",
    ".zip": "application/zip",
}

UPSTREAM_NODE_SPECS = (
    ("artifact-registry", "artifact-registry.json"),
    ("acceptance-matrix", "acceptance-matrix.json"),
    ("verification-report", "WAVE3-VERIFICATION.md"),
    ("independent-review", "review/independent-review.json"),
    ("detached-completion", "detached-completion.json"),
)

MECHANICAL_CHECKS = [
    "fresh-run-id",
    "permanent-historical-invalidation",
    "source-start-end-exact-match",
    "tasks-checkbox-token-normalization",
    "run-root-realpath-containment",
    "artifact-regular-file-no-symlink",
    "artifact-run-source-database-binding",
    "artifact-start-end-mtime-window",
    "artifact-sha256-and-type",
    "exact-20-pre-review-ids",
    "all-pre-review-verdicts-pass",
    "structured-null-and-status-token-rejection",
    "matrix-report-mutually-non-hashing",
    "independent-review-exact-20-read-only",
    "independent-review-current-process-source-before-after",
    "independent-review-every-raw-artifact-challenge",
    "manifest-exact-five-upstream-direction",
    "no-backward-manifest-reference",
    "acyclic-evidence-graph",
    "report-newer-than-source",
    "manifest-not-rewritten",
]


class EvidenceError(RuntimeError):
    """Fail-closed evidence validation error."""


def fail(message: str) -> None:
    raise EvidenceError(message)


def sha256_bytes(contents: bytes) -> str:
    return hashlib.sha256(contents).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def pretty_json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def utc_iso_from_ns(epoch_ns: int) -> str:
    value = datetime.fromtimestamp(epoch_ns / 1_000_000_000, tz=timezone.utc)
    return value.isoformat(timespec="microseconds").replace("+00:00", "Z")


def utc_now() -> str:
    return utc_iso_from_ns(time.time_ns())


def parse_utc(value: str, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        fail(f"{label} must be an RFC3339 UTC instant ending in Z")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        fail(f"{label} is not a valid RFC3339 instant: {error}")
    if parsed.tzinfo != timezone.utc:
        fail(f"{label} must be UTC")
    return parsed


def write_new_file(path: Path, contents: bytes) -> None:
    if path.exists() or path.is_symlink():
        fail(f"refusing to overwrite evidence node: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}-{time.time_ns()}")
    try:
        with temporary.open("xb") as stream:
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def replace_file_atomically(path: Path, contents: bytes) -> None:
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}-{time.time_ns()}")
    try:
        with temporary.open("xb") as stream:
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


EXACT_INTEGER_FIELD_NAMES = frozenset(
    {
        "schemaVersion",
        "pid",
        "parentPid",
        "device",
        "inode",
        "mode",
        "rowVersion",
    }
)
EXACT_INTEGER_FIELD_SUFFIXES = (
    "Count",
    "Bytes",
    "EpochNs",
    "MtimeNs",
)
EXACT_BOOLEAN_FIELD_NAMES = frozenset(
    {
        "databaseBound",
        "waiverAuthority",
        "rawArtifactsReviewed",
        "sourceReadOnlyUnchanged",
        "normalizationUnchanged",
    }
)


def assert_exact_named_json_scalar_types(
    value: Any, label: str, path: str = "$"
) -> None:
    if isinstance(value, list):
        for index, item in enumerate(value):
            assert_exact_named_json_scalar_types(
                item, label, f"{path}[{index}]"
            )
        return
    if not isinstance(value, dict):
        return
    for key, item in value.items():
        item_path = f"{path}.{key}"
        if (
            key in EXACT_INTEGER_FIELD_NAMES
            or key.endswith(EXACT_INTEGER_FIELD_SUFFIXES)
        ) and type(item) is not int:
            fail(f"{label} {item_path} must be an exact JSON integer")
        if key in EXACT_BOOLEAN_FIELD_NAMES and type(item) is not bool:
            fail(f"{label} {item_path} must be an exact JSON boolean")
        assert_exact_named_json_scalar_types(item, label, item_path)


def load_json(path: Path, label: str) -> Any:
    def reject_duplicate_keys(
        pairs: list[tuple[str, Any]],
    ) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, item in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON object key: {key}")
            result[key] = item
        return result

    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate_keys,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        fail(f"{label} is not valid UTF-8 JSON: {error}")
    assert_exact_named_json_scalar_types(value, label)
    return value


def require_exact_keys(value: dict[str, Any], expected: Iterable[str], label: str) -> None:
    actual_keys = set(value)
    expected_keys = set(expected)
    if actual_keys != expected_keys:
        missing = sorted(expected_keys - actual_keys)
        extra = sorted(actual_keys - expected_keys)
        fail(f"{label} key closure failed; missing={missing}, extra={extra}")


def assert_no_forbidden_structured_values(value: Any, label: str) -> None:
    if value is None:
        fail(f"{label} contains a forbidden JSON null")
    if isinstance(value, str):
        if FORBIDDEN_STRUCTURED_TOKEN in value:
            fail(f"{label} contains forbidden {FORBIDDEN_STRUCTURED_TOKEN}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            assert_no_forbidden_structured_values(item, f"{label}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                fail(f"{label} contains a non-string object key")
            assert_no_forbidden_structured_values(item, f"{label}.{key}")
        return
    if isinstance(value, (bool, int, float)):
        return
    fail(f"{label} contains unsupported structured value {type(value).__name__}")


def is_within(candidate: Path, parent: Path) -> bool:
    try:
        candidate.relative_to(parent)
        return True
    except ValueError:
        return False


def infer_mime_type(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix not in MIME_TYPES:
        fail(f"unsupported evidence artifact extension: {path.name}")
    return MIME_TYPES[suffix]


@dataclass(frozen=True)
class SourceSnapshot:
    tree_hash: str
    records: list[dict[str, Any]]
    maximum_mtime_ns: int


class Wave3Evidence:
    def __init__(self, repository_root: Path, contract_path: Path | None = None):
        requested_root = Path(os.path.abspath(repository_root))
        try:
            root_metadata = requested_root.lstat()
        except OSError as error:
            fail(f"repository root is unavailable: {error}")
        if (
            not stat.S_ISDIR(root_metadata.st_mode)
            or stat.S_ISLNK(root_metadata.st_mode)
            or requested_root.resolve(strict=True) != requested_root
        ):
            fail("repository root must be its exact lexical real directory")
        root_descriptor = os.open(
            requested_root,
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        try:
            opened_root = os.fstat(root_descriptor)
        finally:
            os.close(root_descriptor)
        if (opened_root.st_dev, opened_root.st_ino) != (
            root_metadata.st_dev,
            root_metadata.st_ino,
        ):
            fail("repository root identity changed during nofollow open")
        self.repository_root = requested_root
        default_contract = (
            self.repository_root / "scripts/qa/wave3-evidence-contract-v1.json"
        )
        requested_contract = Path(os.path.abspath(contract_path or default_contract))
        self.contract_path = requested_contract.resolve(strict=True)
        if not is_within(self.contract_path, self.repository_root):
            fail("evidence contract escapes the repository")
        self.contract = load_json(self.contract_path, "W3 evidence contract")
        self._validate_contract()
        self.evidence_root = (
            self.repository_root / "docs/verification/wave3/runs"
        )
        self._validate_evidence_root_chain(allow_missing=True)

    def _validate_evidence_root_chain(
        self,
        *,
        create_missing: bool = False,
        allow_missing: bool = False,
    ) -> None:
        """lstat/openat(O_NOFOLLOW) every lexical repo→docs→runs component."""
        expected = self.repository_root / "docs/verification/wave3/runs"
        if self.evidence_root != expected:
            fail("W3 evidence root lexical path drifted")
        parent_fd = os.open(
            self.repository_root,
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        current = self.repository_root
        missing_tail = False
        try:
            for part in ("docs", "verification", "wave3", "runs"):
                current /= part
                if missing_tail:
                    continue
                try:
                    metadata = os.stat(
                        part, dir_fd=parent_fd, follow_symlinks=False
                    )
                except FileNotFoundError:
                    if create_missing:
                        os.mkdir(part, mode=0o755, dir_fd=parent_fd)
                        metadata = os.stat(
                            part, dir_fd=parent_fd, follow_symlinks=False
                        )
                    elif allow_missing:
                        missing_tail = True
                        continue
                    else:
                        fail(f"W3 evidence path component is missing: {current}")
                if (
                    stat.S_ISLNK(metadata.st_mode)
                    or not stat.S_ISDIR(metadata.st_mode)
                ):
                    fail(
                        f"W3 evidence path component is symbolic/non-directory: "
                        f"{current}"
                    )
                child_fd = os.open(
                    part,
                    os.O_RDONLY
                    | getattr(os, "O_DIRECTORY", 0)
                    | getattr(os, "O_NOFOLLOW", 0),
                    dir_fd=parent_fd,
                )
                opened = os.fstat(child_fd)
                if (opened.st_dev, opened.st_ino) != (
                    metadata.st_dev,
                    metadata.st_ino,
                ):
                    os.close(child_fd)
                    fail(f"W3 evidence path identity changed: {current}")
                os.close(parent_fd)
                parent_fd = child_fd
        finally:
            os.close(parent_fd)
        if not missing_tail:
            resolved = self.evidence_root.resolve(strict=True)
            if resolved != self.evidence_root or not is_within(
                resolved, self.repository_root
            ):
                fail("W3 evidence root is not the exact repository-internal path")

    def _validate_run_root_authority(self, run_root: Path) -> None:
        self._validate_evidence_root_chain()
        run_root = Path(os.path.abspath(run_root))
        try:
            relative = run_root.relative_to(self.evidence_root)
        except ValueError:
            fail("run root is outside the W3 evidence authority")
        if len(relative.parts) != 1:
            fail("run root must be a direct evidence-root child")
        metadata = run_root.lstat()
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
            fail("run root is symbolic or not a directory")
        evidence_fd = os.open(
            self.evidence_root,
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        try:
            run_fd = os.open(
                relative.parts[0],
                os.O_RDONLY
                | getattr(os, "O_DIRECTORY", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=evidence_fd,
            )
            try:
                opened = os.fstat(run_fd)
            finally:
                os.close(run_fd)
        finally:
            os.close(evidence_fd)
        if (
            (opened.st_dev, opened.st_ino)
            != (metadata.st_dev, metadata.st_ino)
            or run_root.resolve(strict=True) != run_root
        ):
            fail("run root identity/realpath changed under nofollow validation")

    @property
    def pre_review_ids(self) -> list[str]:
        return list(self.contract["preReviewLeafIds"])

    @property
    def leaf_contracts(self) -> dict[str, dict[str, Any]]:
        return self.contract["leafContracts"]

    @property
    def final_child_ids(self) -> list[str]:
        return [
            *self.pre_review_ids,
            self.contract["reviewId"],
            self.contract["integrityId"],
        ]

    def _validate_contract(self) -> None:
        assert_no_forbidden_structured_values(self.contract, "evidence contract")
        required = {
            "schemaVersion",
            "contractId",
            "invalidatedRunIds",
            "source",
            "preReviewLeafIds",
            "leafContracts",
            "reviewId",
            "integrityId",
            "finalGateId",
            "finalMarker",
            "verificationTaskIds",
        }
        require_exact_keys(self.contract, required, "evidence contract")
        if type(self.contract["schemaVersion"]) is not int:
            fail("evidence contract schemaVersion is not an exact integer")
        if self.contract["schemaVersion"] != 1:
            fail("unsupported evidence contract schemaVersion")
        source_contract = self.contract["source"]
        require_exact_keys(
            source_contract,
            {
                "roots",
                "additionalFiles",
                "excludedDirectoryNames",
                "excludedFileSuffixes",
                "tasksPath",
                "checkboxNormalization",
            },
            "source contract",
        )
        if source_contract["roots"] != REQUIRED_SOURCE_ROOTS:
            fail(
                "source contract must bind the complete backend/frontend build "
                "trees plus the fixed API/spec/QA/MySQL roots"
            )
        if (
            source_contract["additionalFiles"]
            != REQUIRED_ADDITIONAL_SOURCE_FILES
        ):
            fail("source contract additional command inputs drifted")
        excluded_directories = source_contract["excludedDirectoryNames"]
        if (
            not isinstance(excluded_directories, list)
            or len(excluded_directories) != len(set(excluded_directories))
            or set(excluded_directories) != REQUIRED_EXCLUDED_DIRECTORIES
        ):
            fail("source contract excluded-directory set drifted")
        if source_contract["excludedFileSuffixes"] != [".log", ".pyc"]:
            fail("source contract excluded-file suffixes drifted")
        if source_contract["tasksPath"] != (
            "openspec/changes/wave3-attendance-setup-and-policies/tasks.md"
        ):
            fail("source contract tasks path drifted")
        leaf_ids = self.contract["preReviewLeafIds"]
        if not isinstance(leaf_ids, list) or len(leaf_ids) != 20:
            fail("evidence contract must contain exactly 20 pre-review IDs")
        if len(set(leaf_ids)) != 20:
            fail("pre-review evidence IDs must be unique")
        contracts = self.contract["leafContracts"]
        if not isinstance(contracts, dict) or set(contracts) != set(leaf_ids):
            fail("leafContracts must close exactly over the 20 pre-review IDs")
        slugs: list[str] = []
        for evidence_id in leaf_ids:
            leaf = contracts[evidence_id]
            require_exact_keys(
                leaf,
                {
                    "slug",
                    "requiredMarkers",
                    "primaryArtifactRole",
                    "requiredArtifactRoles",
                    "databaseBound",
                },
                f"leaf contract {evidence_id}",
            )
            slug = leaf["slug"]
            markers = leaf["requiredMarkers"]
            primary_role = leaf["primaryArtifactRole"]
            artifact_roles = leaf["requiredArtifactRoles"]
            if (
                not isinstance(slug, str)
                or not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,63}", slug)
            ):
                fail(f"invalid leaf slug for {evidence_id}")
            if (
                not isinstance(markers, list)
                or not markers
                or any(not isinstance(marker, str) or not marker for marker in markers)
            ):
                fail(f"invalid marker contract for {evidence_id}")
            if not isinstance(leaf["databaseBound"], bool):
                fail(f"databaseBound is not boolean for {evidence_id}")
            if (
                not isinstance(artifact_roles, list)
                or not artifact_roles
                or len(set(artifact_roles)) != len(artifact_roles)
                or any(
                    not isinstance(role, str)
                    or not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,63}", role)
                    for role in artifact_roles
                )
                or primary_role not in artifact_roles
            ):
                fail(f"artifact role contract is invalid for {evidence_id}")
            slugs.append(slug)
        if len(set(slugs)) != len(slugs):
            fail("leaf slugs must be unique")
        if len(self.final_child_ids) != 22 or len(set(self.final_child_ids)) != 22:
            fail("final dependency contract must be exactly 22 unique IDs")
        invalidated = self.contract["invalidatedRunIds"]
        if invalidated != [HISTORICAL_RUN_ID]:
            fail("the permanent W3 historical invalidation set drifted")

    def audit_historical_candidate(self) -> None:
        self._validate_evidence_root_chain()
        historical_root = self.evidence_root / HISTORICAL_RUN_ID
        report = historical_root / "WAVE3-VERIFICATION.md"
        invalidated = historical_root / "INVALIDATED.md"
        if not report.is_file() or report.is_symlink():
            fail("permanently invalidated W3 report is missing or symbolic")
        if not invalidated.is_file() or invalidated.is_symlink():
            fail("permanent INVALIDATED.md marker is missing or symbolic")
        report_text = report.read_text(encoding="utf-8")
        marker_position = report_text.find("INVALIDATED_NON_FINAL")
        if marker_position < 0:
            fail("historical W3 report lacks INVALIDATED_NON_FINAL")
        possible_positive_positions = [
            position
            for position in (
                report_text.find("PASS"),
                report_text.find("W3_FINAL_INDEPENDENT_ACCEPTANCE=PASS"),
                report_text.find("结论: `PASS`"),
                report_text.find("结论：`PASS`"),
            )
            if position >= 0
        ]
        if possible_positive_positions and marker_position > min(possible_positive_positions):
            fail("historical invalidation marker occurs after positive conclusion text")
        invalidated_text = invalidated.read_text(encoding="utf-8")
        if (
            "INVALIDATED" not in invalidated_text
            or not re.search(r"(?im)^\s*-\s*\*\*Final\*\*:\s*`false`\s*$", invalidated_text)
        ):
            fail("historical INVALIDATED.md does not explicitly remain non-final")

    def run_root(self, run_id: str, must_exist: bool = True) -> Path:
        self._validate_run_id(run_id)
        self._validate_evidence_root_chain(allow_missing=not must_exist)
        candidate = self.evidence_root / run_id
        if must_exist:
            try:
                self._validate_run_root_authority(candidate)
            except OSError as error:
                fail(f"run root is missing or invalid: {run_id}: {error}")
        return candidate

    def _validate_run_id(self, run_id: str) -> None:
        if not RUN_ID_PATTERN.fullmatch(run_id):
            fail("runId must be 6-64 lowercase letters, digits, dots, underscores or hyphens")
        if run_id in self.contract["invalidatedRunIds"]:
            fail(f"runId is permanently {self._historical_status(run_id)}")

    @staticmethod
    def _historical_status(run_id: str) -> str:
        if run_id == HISTORICAL_RUN_ID:
            return "INVALIDATED_NON_FINAL"
        return "invalidated"

    def _safe_run_file(self, run_root: Path, relative_path: str) -> Path:
        self._validate_run_root_authority(run_root)
        if not isinstance(relative_path, str) or not relative_path:
            fail("artifact path must be a non-empty run-relative string")
        if unicodedata.normalize("NFC", relative_path) != relative_path:
            fail(f"artifact path is not Unicode NFC: {relative_path}")
        pure = PurePosixPath(relative_path)
        if pure.is_absolute() or ".." in pure.parts or "." in pure.parts:
            fail(f"artifact path is not a safe run-relative path: {relative_path}")
        candidate = run_root.joinpath(*pure.parts)
        current = run_root
        for part in pure.parts:
            current = current / part
            if current.exists() or current.is_symlink():
                metadata = current.lstat()
                if stat.S_ISLNK(metadata.st_mode):
                    fail(f"artifact path traverses a symbolic link: {relative_path}")
        if not candidate.is_file() or candidate.is_symlink():
            fail(f"artifact is not a regular non-symbolic file: {relative_path}")
        resolved = candidate.resolve(strict=True)
        resolved_root = run_root.resolve(strict=True)
        if not is_within(resolved, resolved_root):
            fail(f"artifact escapes the current run root: {relative_path}")
        return candidate

    def _context(self, run_root: Path) -> dict[str, Any]:
        context_path = self._safe_run_file(run_root, "run-context.json")
        context = load_json(context_path, "run context")
        assert_no_forbidden_structured_values(context, "run context")
        require_exact_keys(
            context,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "implementerId",
                "implementerProcessId",
                "startedAt",
                "startedAtEpochNs",
                "sourceMaximumMtimeNs",
                "contractPath",
                "contractSha256",
            },
            "run context",
        )
        if context["schemaVersion"] != 1 or context["nodeType"] != "run-context":
            fail("run context schema/type mismatch")
        if context["runId"] != run_root.name:
            fail("run context runId mismatch")
        if not SHA256_PATTERN.fullmatch(context["sourceTreeHash"]):
            fail("run context source tree hash is invalid")
        if context["contractSha256"] != sha256_file(self.contract_path):
            fail("run context evidence contract hash is stale")
        parse_utc(context["startedAt"], "run startedAt")
        if type(context["startedAtEpochNs"]) is not int:
            fail("run startedAtEpochNs is invalid")
        return context

    def _source_file_entries(self) -> list[tuple[str, Path]]:
        source_contract = self.contract["source"]
        excluded_directories = set(source_contract["excludedDirectoryNames"])
        excluded_suffixes = tuple(source_contract["excludedFileSuffixes"])
        paths: dict[str, Path] = {}
        for root_name in source_contract["roots"]:
            root = self.repository_root.joinpath(*PurePosixPath(root_name).parts)
            if not root.is_dir() or root.is_symlink():
                fail(f"required source root is missing or symbolic: {root_name}")
            resolved_root = root.resolve(strict=True)
            if not is_within(resolved_root, self.repository_root):
                fail(f"source root escapes repository: {root_name}")
            for directory, directory_names, file_names in os.walk(root, followlinks=False):
                directory_path = Path(directory)
                kept_directories: list[str] = []
                for name in sorted(directory_names, key=lambda item: item.encode("utf-8")):
                    if name in excluded_directories:
                        continue
                    child = directory_path / name
                    if child.is_symlink():
                        fail(f"source directory symbolic links are forbidden: {child}")
                    kept_directories.append(name)
                directory_names[:] = kept_directories
                for name in sorted(file_names, key=lambda item: item.encode("utf-8")):
                    if name.endswith(excluded_suffixes):
                        continue
                    candidate = directory_path / name
                    if candidate.is_symlink():
                        resolved = candidate.resolve(strict=True)
                        if not is_within(resolved, self.repository_root):
                            fail(f"source file symbolic link escapes repository: {candidate}")
                        if not resolved.is_file():
                            fail(f"source symbolic link does not resolve to a file: {candidate}")
                    elif not candidate.is_file():
                        continue
                    relative = candidate.relative_to(self.repository_root).as_posix()
                    normalized = unicodedata.normalize("NFC", relative)
                    if normalized in paths:
                        fail(f"normalized source path collision: {normalized}")
                    paths[normalized] = candidate
        for relative in source_contract["additionalFiles"]:
            candidate = self.repository_root.joinpath(
                *PurePosixPath(relative).parts
            )
            if not candidate.is_file() or candidate.is_symlink():
                fail(
                    "required additional source file is missing or "
                    f"symbolic: {relative}"
                )
            normalized = unicodedata.normalize("NFC", relative)
            if normalized in paths:
                fail(
                    f"normalized additional source path collision: "
                    f"{normalized}"
                )
            paths[normalized] = candidate
        return sorted(paths.items(), key=lambda item: item[0].encode("utf-8"))

    def compute_source_snapshot(self) -> SourceSnapshot:
        tasks_path = self.contract["source"]["tasksPath"]
        records: list[dict[str, Any]] = []
        record_bytes: list[bytes] = []
        maximum_mtime_ns = 0
        for relative, candidate in self._source_file_entries():
            contents = candidate.read_bytes()
            if relative == tasks_path:
                contents = TASK_CHECKBOX_PATTERN.sub(rb"\1[~]", contents)
            content_sha256 = sha256_bytes(contents)
            relative_bytes = relative.encode("utf-8")
            framed = (
                str(len(relative_bytes)).encode("ascii")
                + b":"
                + relative_bytes
                + b"|"
                + str(len(contents)).encode("ascii")
                + b"|"
                + content_sha256.encode("ascii")
            )
            metadata = candidate.stat()
            maximum_mtime_ns = max(maximum_mtime_ns, metadata.st_mtime_ns)
            record_bytes.append(framed)
            records.append(
                {
                    "path": relative,
                    "sizeBytes": len(contents),
                    "sha256": content_sha256,
                }
            )
        if not records:
            fail("source snapshot is empty")
        return SourceSnapshot(
            tree_hash=sha256_bytes(b"".join(record_bytes)),
            records=records,
            maximum_mtime_ns=maximum_mtime_ns,
        )

    def _task_completion_metadata(
        self, run_id: str, source_hash: str, database_identity: str
    ) -> dict[str, Any]:
        tasks_relative = self.contract["source"]["tasksPath"]
        tasks_path = self.repository_root.joinpath(*PurePosixPath(tasks_relative).parts)
        contents = tasks_path.read_bytes()
        task_lines: dict[str, dict[str, Any]] = {}
        for line in contents.splitlines(keepends=True):
            match = TASK_LINE_PATTERN.match(line)
            if not match:
                continue
            task_id = match.group("task_id").decode("ascii")
            normalized_line = TASK_CHECKBOX_PATTERN.sub(rb"\1[~]", line)
            task_lines[task_id] = {
                "taskId": task_id,
                "initialState": (
                    "checked" if match.group("token") == b"x" else "unchecked"
                ),
                "desiredState": "checked",
                "normalizedLineSha256": sha256_bytes(normalized_line),
            }
        changes: list[dict[str, Any]] = []
        for task_id in self.contract["verificationTaskIds"]:
            if task_id not in task_lines:
                fail(f"verification task is missing from tasks.md: {task_id}")
            changes.append(task_lines[task_id])
        return {
            "schemaVersion": 1,
            "nodeType": "detached-completion",
            "runId": run_id,
            "sourceTreeHash": source_hash,
            "databaseIdentity": database_identity,
            "tasksPath": tasks_relative,
            "authorizationCondition": (
                "W3-VER-FINAL-GATE derives PASS from the exact 22-child set"
            ),
            "checkboxNormalization": self.contract["source"][
                "checkboxNormalization"
            ],
            "taskChanges": changes,
        }

    def initialize(
        self,
        run_id: str,
        database_identity: str,
        implementer_id: str,
        implementer_process_id: str,
    ) -> Path:
        self.audit_historical_candidate()
        self._validate_run_id(run_id)
        for label, value in (
            ("database identity", database_identity),
            ("implementer ID", implementer_id),
            ("implementer process ID", implementer_process_id),
        ):
            if (
                not isinstance(value, str)
                or len(value.strip()) < 4
                or len(value) > 240
                or "\n" in value
                or "\r" in value
                or FORBIDDEN_STRUCTURED_TOKEN in value
                or value.lower() in {"none", "null", "unknown", "pending"}
            ):
                fail(f"{label} is missing or invalid")
        self._validate_evidence_root_chain(create_missing=True)
        run_root = self.run_root(run_id, must_exist=False)
        if run_root.exists() or run_root.is_symlink():
            fail(f"refusing to reuse runId-bound evidence root: {run_id}")
        started_ns = time.time_ns()
        snapshot = self.compute_source_snapshot()
        run_root.mkdir(mode=0o755)
        source_root = run_root / "source"
        source_root.mkdir()
        context = {
            "schemaVersion": 1,
            "nodeType": "run-context",
            "runId": run_id,
            "sourceTreeHash": snapshot.tree_hash,
            "databaseIdentity": database_identity,
            "implementerId": implementer_id,
            "implementerProcessId": implementer_process_id,
            "startedAt": utc_iso_from_ns(started_ns),
            "startedAtEpochNs": started_ns,
            "sourceMaximumMtimeNs": snapshot.maximum_mtime_ns,
            "contractPath": self.contract_path.relative_to(
                self.repository_root
            ).as_posix(),
            "contractSha256": sha256_file(self.contract_path),
        }
        source_start = {
            "schemaVersion": 1,
            "nodeType": "source-start",
            "runId": run_id,
            "sourceTreeHash": snapshot.tree_hash,
            "databaseIdentity": database_identity,
            "recordedAt": utc_now(),
            "maximumSourceMtimeNs": snapshot.maximum_mtime_ns,
            "hashAlgorithm": (
                "SHA-256(concat(len(pathUtf8):pathUtf8|size|sha256(normalizedContent)))"
            ),
            "pathNormalization": "repo-relative UTF-8 NFC, slash separator, binary sort",
            "checkboxNormalization": self.contract["source"][
                "checkboxNormalization"
            ],
            "sourceRoots": self.contract["source"]["roots"],
            "additionalSourceFiles": self.contract["source"][
                "additionalFiles"
            ],
            "excludedDirectoryNames": self.contract["source"][
                "excludedDirectoryNames"
            ],
            "excludedFileSuffixes": self.contract["source"][
                "excludedFileSuffixes"
            ],
            "files": snapshot.records,
        }
        completion = self._task_completion_metadata(
            run_id, snapshot.tree_hash, database_identity
        )
        write_new_file(run_root / "run-context.json", pretty_json_bytes(context))
        write_new_file(source_root / "start.json", pretty_json_bytes(source_start))
        write_new_file(
            run_root / "detached-completion.json", pretty_json_bytes(completion)
        )
        return run_root

    def context_line(self, context: dict[str, Any], evidence_id: str) -> str:
        return (
            f"{CONTEXT_PREFIX} runId={context['runId']} "
            f"sourceHash={context['sourceTreeHash']} "
            f"dbIdentity={context['databaseIdentity']} evidenceId={evidence_id}"
        )

    def _artifact_descriptor(
        self,
        run_root: Path,
        relative_path: str,
        artifact_id: str,
        artifact_role: str,
        run_started_ns: int,
        upper_bound_ns: int,
    ) -> dict[str, Any]:
        candidate = self._safe_run_file(run_root, relative_path)
        metadata = candidate.stat()
        if metadata.st_mtime_ns < run_started_ns:
            fail(f"artifact predates the current run: {relative_path}")
        if metadata.st_mtime_ns > upper_bound_ns:
            fail(f"artifact mtime is after its evidence END: {relative_path}")
        return {
            "artifactId": artifact_id,
            "artifactRole": artifact_role,
            "artifactType": infer_mime_type(candidate),
            "path": relative_path,
            "realpath": candidate.resolve(strict=True).as_posix(),
            "sizeBytes": metadata.st_size,
            "mtime": utc_iso_from_ns(metadata.st_mtime_ns),
            "mtimeEpochNs": metadata.st_mtime_ns,
            "sha256": sha256_file(candidate),
        }

    def _validate_primary_artifact(
        self,
        primary: Path,
        evidence_id: str,
        context: dict[str, Any],
        required_markers: Sequence[str],
    ) -> None:
        contents = primary.read_bytes()
        for marker in required_markers:
            marker_bytes = marker.encode("utf-8")
            if contents.count(marker_bytes) != 1:
                fail(f"{evidence_id} primary artifact lacks marker: {marker}")
        if FORBIDDEN_STRUCTURED_TOKEN.encode("utf-8") in contents:
            fail(f"{evidence_id} primary artifact contains forbidden status token")
        if primary.suffix.lower() in {".json", ".har"}:
            document = load_json(primary, f"{evidence_id} primary artifact")
            if not isinstance(document, dict):
                fail(f"{evidence_id} structured primary artifact must be an object")
            for key, expected in (
                ("evidenceId", evidence_id),
                ("runId", context["runId"]),
                ("sourceTreeHash", context["sourceTreeHash"]),
                ("databaseIdentity", context["databaseIdentity"]),
                ("verdict", "PASS"),
            ):
                if document.get(key) != expected:
                    fail(f"{evidence_id} structured primary {key} mismatch")
            if len(required_markers) == 1:
                if document.get("marker") != required_markers[0]:
                    fail(f"{evidence_id} structured primary marker mismatch")
            elif document.get("markers") != list(required_markers):
                fail(f"{evidence_id} structured primary markers mismatch")
        else:
            required_context = self.context_line(context, evidence_id)
            text = contents.decode("utf-8", errors="strict")
            if text.count(required_context) != 1:
                fail(f"{evidence_id} primary artifact lacks exact run/source/DB context")

    @staticmethod
    def _parse_role_path(specification: str, label: str) -> tuple[str, str]:
        role, separator, relative_path = specification.partition("=")
        if (
            separator != "="
            or not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,63}", role)
            or not relative_path
        ):
            fail(f"{label} must use ARTIFACT_ROLE=RUN_RELATIVE_PATH")
        return role, relative_path

    def _validate_build_gate_commit(
        self,
        run_root: Path,
        context: dict[str, Any],
        upper_bound_ns: int,
    ) -> dict[str, Any]:
        """Bind every build leaf to the same last-written exact-nine marker."""
        marker_path = self._safe_run_file(run_root, BUILD_GATE_COMMIT_FILENAME)
        marker_metadata = marker_path.stat()
        if stat.S_IMODE(marker_metadata.st_mode) != 0o400:
            fail("build-gate commit marker must be immutable mode 0400")
        if (
            marker_metadata.st_mtime_ns < context["startedAtEpochNs"]
            or marker_metadata.st_mtime_ns > upper_bound_ns
            or marker_metadata.st_ctime_ns < context["startedAtEpochNs"]
            or marker_metadata.st_ctime_ns > upper_bound_ns
        ):
            fail("build-gate commit marker is outside the current-run window")
        marker = load_json(marker_path, "build-gate commit marker")
        assert_no_forbidden_structured_values(marker, "build-gate commit marker")
        require_exact_keys(
            marker,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "gateIds",
                "artifactManifest",
                "artifactManifestSha256",
                "committedAt",
                "verdict",
            },
            "build-gate commit marker",
        )
        expected_scalars = {
            "schemaVersion": 1,
            "nodeType": "wave3-build-gates-commit",
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "gateIds": list(BUILD_GATE_IDS),
            "verdict": "PASS",
        }
        for key, expected in expected_scalars.items():
            if marker[key] != expected:
                fail(f"build-gate commit marker {key} mismatch")
        committed_at = parse_utc(marker["committedAt"], "build-gate committedAt")
        committed_at_ns = int(committed_at.timestamp() * 1_000_000_000)
        if (
            committed_at_ns < context["startedAtEpochNs"]
            or committed_at_ns > upper_bound_ns
        ):
            fail("build-gate committedAt is outside the current-run window")
        manifest = marker["artifactManifest"]
        if not isinstance(manifest, dict):
            fail("build-gate artifactManifest must be an object")
        expected_manifest: dict[str, tuple[str, str]] = {}
        for evidence_id, (slug, artifacts) in BUILD_GATE_ARTIFACTS.items():
            contract = self.leaf_contracts.get(evidence_id)
            if (
                contract is None
                or contract["slug"] != slug
                or contract["requiredArtifactRoles"]
                != [role for role, _filename in artifacts]
            ):
                fail(f"build-gate verifier mapping drifted: {evidence_id}")
            for role, filename in artifacts:
                expected_manifest[f"{slug}/{filename}"] = (role, filename)
        if set(manifest) != set(expected_manifest):
            fail(
                "build-gate artifact manifest closure failed; "
                f"missing={sorted(set(expected_manifest) - set(manifest))}, "
                f"extra={sorted(set(manifest) - set(expected_manifest))}"
            )
        expected_manifest_sha = sha256_bytes(
            json.dumps(
                manifest,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        )
        if (
            not isinstance(marker["artifactManifestSha256"], str)
            or not SHA256_PATTERN.fullmatch(marker["artifactManifestSha256"])
            or marker["artifactManifestSha256"] != expected_manifest_sha
        ):
            fail("build-gate artifact manifest SHA256 mismatch")
        manifest_keys = {
            "sizeBytes",
            "sha256",
            "device",
            "inode",
            "mode",
            "mtimeEpochNs",
            "ctimeEpochNs",
        }
        for manifest_path, entry in manifest.items():
            if not isinstance(entry, dict):
                fail(f"build-gate manifest entry is not an object: {manifest_path}")
            require_exact_keys(
                entry, manifest_keys, f"build-gate manifest {manifest_path}"
            )
            slug, separator, filename = manifest_path.partition("/")
            if separator != "/" or "/" in filename:
                fail(f"build-gate manifest path is invalid: {manifest_path}")
            candidate = self._safe_run_file(
                run_root, f"leaves/{slug}/raw/{filename}"
            )
            raw_root = candidate.parent
            raw_metadata = raw_root.lstat()
            if (
                stat.S_ISLNK(raw_metadata.st_mode)
                or not stat.S_ISDIR(raw_metadata.st_mode)
                or stat.S_IMODE(raw_metadata.st_mode) != 0o500
            ):
                fail(f"build-gate raw directory is not sealed mode 0500: {slug}")
            metadata = candidate.stat()
            actual = {
                "sizeBytes": metadata.st_size,
                "sha256": sha256_file(candidate),
                "device": metadata.st_dev,
                "inode": metadata.st_ino,
                "mode": stat.S_IMODE(metadata.st_mode),
                "mtimeEpochNs": metadata.st_mtime_ns,
                "ctimeEpochNs": metadata.st_ctime_ns,
            }
            if entry != actual or actual["mode"] != 0o400:
                fail(
                    f"build-gate raw artifact differs from commit manifest: "
                    f"{manifest_path}"
                )
            if (
                metadata.st_mtime_ns < context["startedAtEpochNs"]
                or metadata.st_mtime_ns > marker_metadata.st_mtime_ns
                or metadata.st_ctime_ns < context["startedAtEpochNs"]
                or metadata.st_ctime_ns > marker_metadata.st_mtime_ns
            ):
                fail(
                    f"build-gate raw artifact is outside commit window: "
                    f"{manifest_path}"
                )
        return marker

    def register_leaf(
        self,
        run_id: str,
        evidence_id: str,
        primary_relative: str,
        artifact_relatives: Sequence[str],
        started_ns: int | None = None,
        ended_ns: int | None = None,
    ) -> Path:
        self.audit_historical_candidate()
        run_root = self.run_root(run_id)
        context = self._context(run_root)
        if (run_root / "source/end.json").exists():
            fail("pre-review leaves cannot be registered after source END")
        if evidence_id not in self.leaf_contracts:
            fail(f"unexpected pre-review evidence ID: {evidence_id}")
        contract = self.leaf_contracts[evidence_id]
        if evidence_id in BUILD_GATE_ARTIFACTS:
            self._validate_build_gate_commit(run_root, context, time.time_ns())
        leaf_root = run_root / "leaves" / contract["slug"]
        leaf_path = leaf_root / "leaf.json"
        if leaf_path.exists() or leaf_path.is_symlink():
            fail(f"duplicate pre-review evidence ID: {evidence_id}")
        primary_role, parsed_primary_relative = self._parse_role_path(
            primary_relative, "--primary"
        )
        if primary_role != contract["primaryArtifactRole"]:
            fail(f"{evidence_id} primary artifact role mismatch")
        provided: dict[str, str] = {primary_role: parsed_primary_relative}
        for specification in artifact_relatives:
            role, relative = self._parse_role_path(specification, "--artifact")
            if role in provided:
                fail(f"{evidence_id} duplicate artifact role: {role}")
            provided[role] = relative
        required_roles = contract["requiredArtifactRoles"]
        if set(provided) != set(required_roles):
            fail(
                f"{evidence_id} artifact role closure failed; "
                f"missing={sorted(set(required_roles) - set(provided))}, "
                f"extra={sorted(set(provided) - set(required_roles))}"
            )
        if evidence_id in BUILD_GATE_ARTIFACTS:
            slug, artifacts = BUILD_GATE_ARTIFACTS[evidence_id]
            expected_paths = {
                role: f"leaves/{slug}/raw/{filename}"
                for role, filename in artifacts
            }
            if provided != expected_paths:
                fail(f"{evidence_id} paths differ from the committed build bundle")
        current_ns = time.time_ns()
        effective_end_ns = ended_ns or current_ns
        if effective_end_ns > current_ns:
            fail(f"{evidence_id} END cannot be in the future")
        descriptors: list[dict[str, Any]] = []
        for role in required_roles:
            relative = provided[role]
            descriptor = self._artifact_descriptor(
                run_root,
                relative,
                f"{contract['slug']}-{role}",
                role,
                context["startedAtEpochNs"],
                effective_end_ns,
            )
            descriptors.append(descriptor)
        effective_start_ns = started_ns or min(
            descriptor["mtimeEpochNs"] for descriptor in descriptors
        )
        if effective_start_ns < context["startedAtEpochNs"]:
            fail(f"{evidence_id} START predates the current run")
        if effective_start_ns > effective_end_ns:
            fail(f"{evidence_id} START is after END")
        primary = self._safe_run_file(run_root, parsed_primary_relative)
        self._validate_primary_artifact(
            primary, evidence_id, context, contract["requiredMarkers"]
        )
        primary_descriptor = next(
            descriptor
            for descriptor in descriptors
            if descriptor["artifactRole"] == primary_role
        )
        leaf_record = {
            "schemaVersion": 1,
            "nodeType": "pre-review-leaf",
            "evidenceId": evidence_id,
            "verdict": "PASS",
            "runId": run_id,
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "databaseBound": contract["databaseBound"],
            "startedAt": utc_iso_from_ns(effective_start_ns),
            "startedAtEpochNs": effective_start_ns,
            "endedAt": utc_iso_from_ns(effective_end_ns),
            "endedAtEpochNs": effective_end_ns,
            "requiredMarkers": contract["requiredMarkers"],
            "primaryArtifactRole": contract["primaryArtifactRole"],
            "requiredArtifactRoles": contract["requiredArtifactRoles"],
            "primaryArtifactId": primary_descriptor["artifactId"],
            "artifacts": descriptors,
        }
        assert_no_forbidden_structured_values(leaf_record, f"{evidence_id} leaf")
        write_new_file(leaf_path, pretty_json_bytes(leaf_record))
        return leaf_path

    def capture_leaf(
        self, run_id: str, evidence_id: str, command: Sequence[str]
    ) -> Path:
        if not command:
            fail("capture requires a command after --")
        run_root = self.run_root(run_id)
        context = self._context(run_root)
        if evidence_id not in self.leaf_contracts:
            fail(f"unexpected pre-review evidence ID: {evidence_id}")
        contract = self.leaf_contracts[evidence_id]
        if len(contract["requiredArtifactRoles"]) != 1:
            fail(
                f"{evidence_id} requires multiple artifact roles; "
                "produce them first and use register"
            )
        slug = contract["slug"]
        leaf_root = run_root / "leaves" / slug
        leaf_root.mkdir(parents=True, exist_ok=True)
        output_path = leaf_root / "primary.log"
        if output_path.exists() or output_path.is_symlink():
            fail(f"capture output already exists for {evidence_id}")
        started_ns = time.time_ns()
        with output_path.open("xb") as output:
            output.write((self.context_line(context, evidence_id) + "\n").encode())
            output.write(
                (
                    f"W3_EVIDENCE_COMMAND=START evidenceId={evidence_id} "
                    f"startedAt={utc_iso_from_ns(started_ns)}\n"
                ).encode()
            )
            output.flush()
            result = subprocess.run(
                list(command),
                cwd=self.repository_root,
                stdout=output,
                stderr=subprocess.STDOUT,
                check=False,
            )
            command_ended_ns = time.time_ns()
            output.write(
                (
                    f"\nW3_EVIDENCE_COMMAND=END evidenceId={evidence_id} "
                    f"exitCode={result.returncode} "
                    f"endedAt={utc_iso_from_ns(command_ended_ns)}\n"
                ).encode()
            )
        ended_ns = time.time_ns()
        if result.returncode != 0:
            fail(
                f"{evidence_id} command exited {result.returncode}; "
                f"inspect {output_path.relative_to(run_root)}"
            )
        return self.register_leaf(
            run_id,
            evidence_id,
            (
                f"{contract['primaryArtifactRole']}="
                f"{output_path.relative_to(run_root).as_posix()}"
            ),
            [],
            started_ns=started_ns,
            ended_ns=ended_ns,
        )

    def _validate_artifact_descriptor(
        self,
        descriptor: dict[str, Any],
        run_root: Path,
        context: dict[str, Any],
        upper_bound_ns: int,
        label: str,
    ) -> Path:
        require_exact_keys(
            descriptor,
            {
                "artifactId",
                "artifactRole",
                "artifactType",
                "path",
                "realpath",
                "sizeBytes",
                "mtime",
                "mtimeEpochNs",
                "sha256",
            },
            label,
        )
        candidate = self._safe_run_file(run_root, descriptor["path"])
        metadata = candidate.stat()
        if (
            not isinstance(descriptor["artifactRole"], str)
            or not re.fullmatch(
                r"[a-z0-9][a-z0-9-]{2,63}", descriptor["artifactRole"]
            )
        ):
            fail(f"{label} artifact role is invalid")
        if descriptor["realpath"] != candidate.resolve(strict=True).as_posix():
            fail(f"{label} realpath mismatch")
        if descriptor["artifactType"] != infer_mime_type(candidate):
            fail(f"{label} artifact type mismatch")
        if descriptor["sizeBytes"] != metadata.st_size:
            fail(f"{label} size mismatch")
        if descriptor["mtimeEpochNs"] != metadata.st_mtime_ns:
            fail(f"{label} mtime mismatch")
        if descriptor["mtime"] != utc_iso_from_ns(metadata.st_mtime_ns):
            fail(f"{label} mtime text mismatch")
        if metadata.st_mtime_ns < context["startedAtEpochNs"]:
            fail(f"{label} predates the current run")
        if metadata.st_mtime_ns > upper_bound_ns:
            fail(f"{label} is newer than the allowed END")
        if descriptor["sha256"] != sha256_file(candidate):
            fail(f"{label} SHA256 mismatch")
        return candidate

    def _validate_node_descriptor(
        self,
        descriptor: dict[str, Any],
        run_root: Path,
        context: dict[str, Any],
        upper_bound_ns: int,
        label: str,
    ) -> Path:
        require_exact_keys(
            descriptor,
            {
                "nodeId",
                "artifactType",
                "path",
                "realpath",
                "sizeBytes",
                "mtime",
                "mtimeEpochNs",
                "sha256",
            },
            label,
        )
        candidate = self._safe_run_file(run_root, descriptor["path"])
        metadata = candidate.stat()
        if not isinstance(descriptor["nodeId"], str) or not descriptor["nodeId"]:
            fail(f"{label} nodeId is invalid")
        if descriptor["realpath"] != candidate.resolve(strict=True).as_posix():
            fail(f"{label} realpath mismatch")
        if descriptor["artifactType"] != infer_mime_type(candidate):
            fail(f"{label} artifact type mismatch")
        if descriptor["sizeBytes"] != metadata.st_size:
            fail(f"{label} size mismatch")
        if descriptor["mtimeEpochNs"] != metadata.st_mtime_ns:
            fail(f"{label} mtime mismatch")
        if descriptor["mtime"] != utc_iso_from_ns(metadata.st_mtime_ns):
            fail(f"{label} mtime text mismatch")
        if metadata.st_mtime_ns < context["startedAtEpochNs"]:
            fail(f"{label} predates the current run")
        if metadata.st_mtime_ns > upper_bound_ns:
            fail(f"{label} is newer than the allowed END")
        if descriptor["sha256"] != sha256_file(candidate):
            fail(f"{label} SHA256 mismatch")
        return candidate

    def _validate_leaf(
        self, run_root: Path, evidence_id: str, upper_bound_ns: int
    ) -> dict[str, Any]:
        context = self._context(run_root)
        if evidence_id in BUILD_GATE_ARTIFACTS:
            self._validate_build_gate_commit(run_root, context, upper_bound_ns)
        contract = self.leaf_contracts[evidence_id]
        leaf_relative = f"leaves/{contract['slug']}/leaf.json"
        leaf_path = self._safe_run_file(run_root, leaf_relative)
        leaf = load_json(leaf_path, f"{evidence_id} leaf")
        assert_no_forbidden_structured_values(leaf, f"{evidence_id} leaf")
        require_exact_keys(
            leaf,
            {
                "schemaVersion",
                "nodeType",
                "evidenceId",
                "verdict",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "databaseBound",
                "startedAt",
                "startedAtEpochNs",
                "endedAt",
                "endedAtEpochNs",
                "requiredMarkers",
                "primaryArtifactRole",
                "requiredArtifactRoles",
                "primaryArtifactId",
                "artifacts",
            },
            f"{evidence_id} leaf",
        )
        expected_scalars = {
            "schemaVersion": 1,
            "nodeType": "pre-review-leaf",
            "evidenceId": evidence_id,
            "verdict": "PASS",
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "databaseBound": contract["databaseBound"],
            "requiredMarkers": contract["requiredMarkers"],
            "primaryArtifactRole": contract["primaryArtifactRole"],
            "requiredArtifactRoles": contract["requiredArtifactRoles"],
        }
        for key, expected in expected_scalars.items():
            if leaf[key] != expected:
                fail(f"{evidence_id} leaf {key} mismatch")
        if not isinstance(leaf["artifacts"], list) or not leaf["artifacts"]:
            fail(f"{evidence_id} has no raw artifacts")
        if (
            type(leaf["startedAtEpochNs"]) is not int
            or type(leaf["endedAtEpochNs"]) is not int
            or leaf["startedAtEpochNs"] < context["startedAtEpochNs"]
            or leaf["startedAtEpochNs"] > leaf["endedAtEpochNs"]
            or leaf["endedAtEpochNs"] > upper_bound_ns
        ):
            fail(f"{evidence_id} START/END window is invalid")
        if leaf["startedAt"] != utc_iso_from_ns(leaf["startedAtEpochNs"]):
            fail(f"{evidence_id} START text mismatch")
        if leaf["endedAt"] != utc_iso_from_ns(leaf["endedAtEpochNs"]):
            fail(f"{evidence_id} END text mismatch")
        artifact_ids: set[str] = set()
        artifact_roles: list[str] = []
        primary_path: Path | None = None
        for index, descriptor in enumerate(leaf["artifacts"]):
            candidate = self._validate_artifact_descriptor(
                descriptor,
                run_root,
                context,
                leaf["endedAtEpochNs"],
                f"{evidence_id} artifact[{index}]",
            )
            artifact_id = descriptor["artifactId"]
            if not isinstance(artifact_id, str) or artifact_id in artifact_ids:
                fail(f"{evidence_id} artifact IDs are missing or duplicated")
            artifact_ids.add(artifact_id)
            artifact_roles.append(descriptor["artifactRole"])
            if artifact_id == leaf["primaryArtifactId"]:
                if descriptor["artifactRole"] != contract["primaryArtifactRole"]:
                    fail(f"{evidence_id} primary artifact role drifted")
                primary_path = candidate
        if primary_path is None:
            fail(f"{evidence_id} primary artifact ID is missing")
        if artifact_roles != contract["requiredArtifactRoles"]:
            fail(f"{evidence_id} artifact role order/set mismatch")
        self._validate_primary_artifact(
            primary_path, evidence_id, context, contract["requiredMarkers"]
        )
        leaf_metadata = leaf_path.stat()
        if leaf_metadata.st_mtime_ns < context["startedAtEpochNs"]:
            fail(f"{evidence_id} leaf record predates the run")
        if leaf_metadata.st_mtime_ns > upper_bound_ns:
            fail(f"{evidence_id} leaf record is outside the run window")
        return leaf

    def _collect_leaves(
        self, run_root: Path, upper_bound_ns: int
    ) -> list[dict[str, Any]]:
        leaves_root = run_root / "leaves"
        if not leaves_root.is_dir() or leaves_root.is_symlink():
            fail("pre-review leaves root is missing or symbolic")
        expected_slugs = {
            self.leaf_contracts[evidence_id]["slug"] for evidence_id in self.pre_review_ids
        }
        actual_directories = {
            child.name
            for child in leaves_root.iterdir()
            if child.is_dir() and not child.is_symlink()
        }
        unexpected = sorted(actual_directories - expected_slugs)
        if unexpected:
            fail(f"unexpected pre-review leaf directories: {unexpected}")
        leaves = [
            self._validate_leaf(run_root, evidence_id, upper_bound_ns)
            for evidence_id in self.pre_review_ids
        ]
        if len(leaves) != 20:
            fail("pre-review leaf count is not exactly 20")
        if {leaf["evidenceId"] for leaf in leaves} != set(self.pre_review_ids):
            fail("pre-review leaf ID set is not exact")
        return leaves

    def _describe_node(
        self, run_root: Path, relative_path: str, node_id: str
    ) -> dict[str, Any]:
        path = self._safe_run_file(run_root, relative_path)
        metadata = path.stat()
        return {
            "nodeId": node_id,
            "artifactType": infer_mime_type(path),
            "path": relative_path,
            "realpath": path.resolve(strict=True).as_posix(),
            "sizeBytes": metadata.st_size,
            "mtime": utc_iso_from_ns(metadata.st_mtime_ns),
            "mtimeEpochNs": metadata.st_mtime_ns,
            "sha256": sha256_file(path),
        }

    def _source_end(self, run_root: Path) -> dict[str, Any]:
        context = self._context(run_root)
        start_path = self._safe_run_file(run_root, "source/start.json")
        start = load_json(start_path, "source START")
        snapshot = self.compute_source_snapshot()
        if snapshot.tree_hash != context["sourceTreeHash"]:
            fail("source tree changed after START; current run is invalid")
        if start["sourceTreeHash"] != context["sourceTreeHash"]:
            fail("source START hash does not match run context")
        source_end = {
            "schemaVersion": 1,
            "nodeType": "source-end",
            "runId": context["runId"],
            "sourceTreeHash": snapshot.tree_hash,
            "databaseIdentity": context["databaseIdentity"],
            "recordedAt": utc_now(),
            "maximumSourceMtimeNs": snapshot.maximum_mtime_ns,
            "startRecordSha256": sha256_file(start_path),
            "files": snapshot.records,
        }
        write_new_file(run_root / "source/end.json", pretty_json_bytes(source_end))
        return source_end

    def assemble(self, run_id: str) -> None:
        self.audit_historical_candidate()
        run_root = self.run_root(run_id)
        context = self._context(run_root)
        for node in (
            "source/end.json",
            "artifact-registry.json",
            "acceptance-matrix.json",
            "WAVE3-VERIFICATION.md",
        ):
            if (run_root / node).exists() or (run_root / node).is_symlink():
                fail(f"assembly node already exists: {node}")
        assembly_ns = time.time_ns()
        leaves = self._collect_leaves(run_root, assembly_ns)
        source_end = self._source_end(run_root)
        registry_entries: list[dict[str, Any]] = []
        for evidence_id, leaf in zip(self.pre_review_ids, leaves, strict=True):
            slug = self.leaf_contracts[evidence_id]["slug"]
            leaf_node = self._describe_node(
                run_root, f"leaves/{slug}/leaf.json", f"{slug}-leaf-record"
            )
            registry_entries.append(
                {
                    "evidenceId": evidence_id,
                    "verdict": "PASS",
                    "runId": run_id,
                    "sourceTreeHash": context["sourceTreeHash"],
                    "databaseIdentity": context["databaseIdentity"],
                    "startedAt": leaf["startedAt"],
                    "endedAt": leaf["endedAt"],
                    "requiredMarkers": leaf["requiredMarkers"],
                    "leafRecord": leaf_node,
                    "rawArtifacts": leaf["artifacts"],
                }
            )
        registry = {
            "schemaVersion": 1,
            "nodeType": "artifact-registry",
            "runId": run_id,
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "generatedAt": utc_now(),
            "preReviewLeafCount": 20,
            "preReviewLeafIds": self.pre_review_ids,
            "entries": registry_entries,
        }
        assert_no_forbidden_structured_values(registry, "artifact registry")
        registry_path = run_root / "artifact-registry.json"
        write_new_file(registry_path, pretty_json_bytes(registry))
        registry_sha = sha256_file(registry_path)
        matrix = {
            "schemaVersion": 1,
            "nodeType": "acceptance-matrix",
            "runId": run_id,
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "generatedAt": utc_now(),
            "artifactRegistryPath": "artifact-registry.json",
            "artifactRegistrySha256": registry_sha,
            "preReviewLeafCount": 20,
            "criteria": [
                {
                    "evidenceId": evidence_id,
                    "verdict": "PASS",
                    "markerCount": len(
                        self.leaf_contracts[evidence_id]["requiredMarkers"]
                    ),
                }
                for evidence_id in self.pre_review_ids
            ],
        }
        assert_no_forbidden_structured_values(matrix, "acceptance matrix")
        matrix_path = run_root / "acceptance-matrix.json"
        write_new_file(matrix_path, pretty_json_bytes(matrix))
        report_lines = [
            "# WAVE-3 frozen pre-review evidence",
            "",
            f"- runId: `{run_id}`",
            f"- sourceTreeHash: `{context['sourceTreeHash']}`",
            f"- databaseIdentity: `{context['databaseIdentity']}`",
            f"- artifactRegistrySha256: `{registry_sha}`",
            "- source START/END: `MATCH`",
            "- pre-review leaf count: `20`",
            "",
            "| Evidence ID | Verdict | Required marker(s) |",
            "|---|---|---|",
        ]
        for evidence_id in self.pre_review_ids:
            markers = "<br>".join(self.leaf_contracts[evidence_id]["requiredMarkers"])
            report_lines.append(f"| `{evidence_id}` | `PASS` | `{markers}` |")
        report_lines.extend(
            [
                "",
                "This report is a pre-review node. It is not the final acceptance gate.",
                "",
            ]
        )
        report_path = run_root / "WAVE3-VERIFICATION.md"
        write_new_file(report_path, "\n".join(report_lines).encode("utf-8"))
        if report_path.stat().st_mtime_ns <= source_end["maximumSourceMtimeNs"]:
            fail("verification report is not newer than the frozen source")
        matrix_sha = sha256_file(matrix_path)
        report_sha = sha256_file(report_path)
        if report_sha.encode() in matrix_path.read_bytes():
            fail("acceptance matrix must not hash the report")
        if matrix_sha.encode() in report_path.read_bytes():
            fail("verification report must not hash the acceptance matrix")

    def _validate_source_pair(
        self, run_root: Path, recompute: bool = True
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        context = self._context(run_root)
        start = load_json(
            self._safe_run_file(run_root, "source/start.json"), "source START"
        )
        end = load_json(
            self._safe_run_file(run_root, "source/end.json"), "source END"
        )
        assert_no_forbidden_structured_values(start, "source START")
        assert_no_forbidden_structured_values(end, "source END")
        require_exact_keys(
            start,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "recordedAt",
                "maximumSourceMtimeNs",
                "hashAlgorithm",
                "pathNormalization",
                "checkboxNormalization",
                "sourceRoots",
                "additionalSourceFiles",
                "excludedDirectoryNames",
                "excludedFileSuffixes",
                "files",
            },
            "source START",
        )
        require_exact_keys(
            end,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "recordedAt",
                "maximumSourceMtimeNs",
                "startRecordSha256",
                "files",
            },
            "source END",
        )
        expected_start = {
            "schemaVersion": 1,
            "nodeType": "source-start",
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "maximumSourceMtimeNs": context["sourceMaximumMtimeNs"],
            "hashAlgorithm": (
                "SHA-256(concat(len(pathUtf8):pathUtf8|size|sha256(normalizedContent)))"
            ),
            "pathNormalization": (
                "repo-relative UTF-8 NFC, slash separator, binary sort"
            ),
            "checkboxNormalization": self.contract["source"][
                "checkboxNormalization"
            ],
            "sourceRoots": self.contract["source"]["roots"],
            "additionalSourceFiles": self.contract["source"][
                "additionalFiles"
            ],
            "excludedDirectoryNames": self.contract["source"][
                "excludedDirectoryNames"
            ],
            "excludedFileSuffixes": self.contract["source"][
                "excludedFileSuffixes"
            ],
        }
        expected_end = {
            "schemaVersion": 1,
            "nodeType": "source-end",
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "maximumSourceMtimeNs": context["sourceMaximumMtimeNs"],
        }
        for key, expected in expected_start.items():
            if start[key] != expected:
                fail(f"source START {key} mismatch")
        for key, expected in expected_end.items():
            if end[key] != expected:
                fail(f"source END {key} mismatch")
        parse_utc(start["recordedAt"], "source START recordedAt")
        parse_utc(end["recordedAt"], "source END recordedAt")
        if end.get("startRecordSha256") != sha256_file(
            run_root / "source/start.json"
        ):
            fail("source END does not hash the current START record")
        if start.get("files") != end.get("files"):
            fail("source START/END file records differ")
        files = start["files"]
        if not isinstance(files, list) or not files:
            fail("source START/END file inventory is empty or invalid")
        paths: list[str] = []
        for index, record in enumerate(files):
            if not isinstance(record, dict):
                fail(f"source file record[{index}] is not an object")
            require_exact_keys(
                record,
                {"path", "sizeBytes", "sha256"},
                f"source file record[{index}]",
            )
            if (
                not isinstance(record["path"], str)
                or unicodedata.normalize("NFC", record["path"])
                != record["path"]
                or type(record["sizeBytes"]) is not int
                or record["sizeBytes"] < 0
                or not isinstance(record["sha256"], str)
                or not SHA256_PATTERN.fullmatch(record["sha256"])
            ):
                fail(f"source file record[{index}] is invalid")
            paths.append(record["path"])
        if paths != sorted(paths, key=lambda item: item.encode("utf-8")):
            fail("source file records are not in exact binary path order")
        if len(paths) != len(set(paths)):
            fail("source file record paths are duplicated")
        if recompute:
            snapshot = self.compute_source_snapshot()
            if snapshot.tree_hash != context["sourceTreeHash"]:
                fail("current normalized source differs from frozen source")
            if (
                not self._verification_tasks_are_checked()
                and snapshot.maximum_mtime_ns
                != context["sourceMaximumMtimeNs"]
            ):
                fail("current source maximum mtime differs from frozen source")
            if files != snapshot.records:
                fail("source START/END inventory differs from current source")
        return start, end

    def _validate_detached_completion(
        self, run_root: Path
    ) -> dict[str, Any]:
        context = self._context(run_root)
        completion_path = self._safe_run_file(
            run_root, "detached-completion.json"
        )
        completion = load_json(completion_path, "detached completion")
        assert_no_forbidden_structured_values(completion, "detached completion")
        require_exact_keys(
            completion,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "tasksPath",
                "authorizationCondition",
                "checkboxNormalization",
                "taskChanges",
            },
            "detached completion",
        )
        expected_scalars = {
            "schemaVersion": 1,
            "nodeType": "detached-completion",
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "tasksPath": self.contract["source"]["tasksPath"],
            "authorizationCondition": (
                "W3-VER-FINAL-GATE derives PASS from the exact 22-child set"
            ),
            "checkboxNormalization": self.contract["source"][
                "checkboxNormalization"
            ],
        }
        for key, expected in expected_scalars.items():
            if completion[key] != expected:
                fail(f"detached completion {key} mismatch")
        changes = completion["taskChanges"]
        if (
            not isinstance(changes, list)
            or [change.get("taskId") for change in changes]
            != self.contract["verificationTaskIds"]
        ):
            fail("detached completion task set/order is not exact")
        for index, change in enumerate(changes):
            require_exact_keys(
                change,
                {
                    "taskId",
                    "initialState",
                    "desiredState",
                    "normalizedLineSha256",
                },
                f"detached completion taskChanges[{index}]",
            )
            if (
                change["initialState"] not in {"checked", "unchecked"}
                or change["desiredState"] != "checked"
                or not isinstance(change["normalizedLineSha256"], str)
                or not SHA256_PATTERN.fullmatch(change["normalizedLineSha256"])
            ):
                fail(
                    f"detached completion task state/hash is invalid: "
                    f"{change['taskId']}"
                )
        tasks_path = self.repository_root.joinpath(
            *PurePosixPath(completion["tasksPath"]).parts
        )
        current_lines: dict[str, str] = {}
        for line in tasks_path.read_bytes().splitlines(keepends=True):
            match = TASK_LINE_PATTERN.match(line)
            if not match:
                continue
            task_id = match.group("task_id").decode("ascii")
            current_lines[task_id] = sha256_bytes(
                TASK_CHECKBOX_PATTERN.sub(rb"\1[~]", line)
            )
        for change in changes:
            if current_lines.get(change["taskId"]) != change["normalizedLineSha256"]:
                fail(f"detached completion task line drift: {change['taskId']}")
        return completion

    def _validate_registry_matrix_report(
        self, run_root: Path, upper_bound_ns: int
    ) -> tuple[dict[str, Any], dict[str, Any], str]:
        context = self._context(run_root)
        leaves = self._collect_leaves(run_root, upper_bound_ns)
        registry_path = self._safe_run_file(run_root, "artifact-registry.json")
        matrix_path = self._safe_run_file(run_root, "acceptance-matrix.json")
        report_path = self._safe_run_file(run_root, "WAVE3-VERIFICATION.md")
        registry = load_json(registry_path, "artifact registry")
        matrix = load_json(matrix_path, "acceptance matrix")
        report = report_path.read_text(encoding="utf-8")
        assert_no_forbidden_structured_values(registry, "artifact registry")
        assert_no_forbidden_structured_values(matrix, "acceptance matrix")
        require_exact_keys(
            registry,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "generatedAt",
                "preReviewLeafCount",
                "preReviewLeafIds",
                "entries",
            },
            "artifact registry",
        )
        if (
            registry.get("schemaVersion") != 1
            or registry.get("nodeType") != "artifact-registry"
            or
            registry.get("runId") != context["runId"]
            or registry.get("sourceTreeHash") != context["sourceTreeHash"]
            or registry.get("databaseIdentity") != context["databaseIdentity"]
            or registry.get("preReviewLeafCount") != 20
            or registry.get("preReviewLeafIds") != self.pre_review_ids
        ):
            fail("artifact registry binding/count/order mismatch")
        entries = registry.get("entries")
        if not isinstance(entries, list) or len(entries) != 20:
            fail("artifact registry must contain exactly 20 entries")
        if [entry.get("evidenceId") for entry in entries] != self.pre_review_ids:
            fail("artifact registry evidence IDs are not exact")
        for entry, leaf in zip(entries, leaves, strict=True):
            require_exact_keys(
                entry,
                {
                    "evidenceId",
                    "verdict",
                    "runId",
                    "sourceTreeHash",
                    "databaseIdentity",
                    "startedAt",
                    "endedAt",
                    "requiredMarkers",
                    "leafRecord",
                    "rawArtifacts",
                },
                f"artifact registry entry {entry.get('evidenceId')}",
            )
            if (
                entry.get("verdict") != "PASS"
                or entry.get("runId") != context["runId"]
                or entry.get("sourceTreeHash") != context["sourceTreeHash"]
                or entry.get("databaseIdentity") != context["databaseIdentity"]
                or entry.get("requiredMarkers") != leaf["requiredMarkers"]
                or entry.get("rawArtifacts") != leaf["artifacts"]
            ):
                fail(f"artifact registry entry drift: {entry.get('evidenceId')}")
            descriptor = entry.get("leafRecord")
            if not isinstance(descriptor, dict):
                fail("artifact registry leaf record descriptor is missing")
            self._validate_node_descriptor(
                descriptor,
                run_root,
                context,
                upper_bound_ns,
                f"registry {entry['evidenceId']} leafRecord",
            )
        registry_sha = sha256_file(registry_path)
        criteria = matrix.get("criteria")
        require_exact_keys(
            matrix,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "generatedAt",
                "artifactRegistryPath",
                "artifactRegistrySha256",
                "preReviewLeafCount",
                "criteria",
            },
            "acceptance matrix",
        )
        if (
            matrix.get("schemaVersion") != 1
            or matrix.get("nodeType") != "acceptance-matrix"
            or
            matrix.get("runId") != context["runId"]
            or matrix.get("sourceTreeHash") != context["sourceTreeHash"]
            or matrix.get("databaseIdentity") != context["databaseIdentity"]
            or matrix.get("artifactRegistryPath") != "artifact-registry.json"
            or matrix.get("artifactRegistrySha256") != registry_sha
            or matrix.get("preReviewLeafCount") != 20
            or not isinstance(criteria, list)
            or len(criteria) != 20
            or [criterion.get("evidenceId") for criterion in criteria]
            != self.pre_review_ids
            or any(criterion.get("verdict") != "PASS" for criterion in criteria)
        ):
            fail("acceptance matrix binding/count/verdict mismatch")
        for index, criterion in enumerate(criteria):
            require_exact_keys(
                criterion,
                {"evidenceId", "verdict", "markerCount"},
                f"acceptance matrix criteria[{index}]",
            )
            if (
                type(criterion["markerCount"]) is not int
                or criterion["markerCount"]
                != len(leaves[index]["requiredMarkers"])
            ):
                fail(
                    f"acceptance matrix criteria[{index}] markerCount "
                    "is not exact"
                )
        for required_text in (
            f"runId: `{context['runId']}`",
            f"sourceTreeHash: `{context['sourceTreeHash']}`",
            f"databaseIdentity: `{context['databaseIdentity']}`",
            f"artifactRegistrySha256: `{registry_sha}`",
            "pre-review leaf count: `20`",
        ):
            if required_text not in report:
                fail(f"verification report lacks binding: {required_text}")
        for evidence_id in self.pre_review_ids:
            if report.count(f"`{evidence_id}`") != 1:
                fail(f"verification report ID count is not one: {evidence_id}")
        if FORBIDDEN_STRUCTURED_TOKEN in report:
            fail("verification report contains forbidden status token")
        matrix_sha = sha256_file(matrix_path)
        report_sha = sha256_file(report_path)
        if report_sha in matrix_path.read_text(encoding="utf-8"):
            fail("acceptance matrix hashes the report")
        if matrix_sha in report:
            fail("verification report hashes the acceptance matrix")
        return registry, matrix, report

    @staticmethod
    def _validate_challenge_notes(value: Any, label: str) -> None:
        if (
            not isinstance(value, list)
            or not value
            or any(
                not isinstance(note, str)
                or len(note.strip()) < 12
                or len(note) > 2_000
                for note in value
            )
        ):
            fail(f"{label} must contain substantive structured challenge notes")

    def _load_review_input(
        self,
        run_root: Path,
        context: dict[str, Any],
        registry: dict[str, Any],
        upper_bound_ns: int,
    ) -> tuple[dict[str, Any], Path, Path, str]:
        registry_path = self._safe_run_file(run_root, "artifact-registry.json")
        input_path = self._safe_run_file(
            run_root, "review/independent-review-input.json"
        )
        report_path = self._safe_run_file(run_root, "review/independent-review.md")
        review_input = load_json(input_path, "independent review input")
        assert_no_forbidden_structured_values(
            review_input, "independent review input"
        )
        require_exact_keys(
            review_input,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHashBefore",
                "databaseIdentity",
                "reviewerId",
                "reviewerMode",
                "reviewMethod",
                "waiverAuthority",
                "rawArtifactsReviewed",
                "challengedEvidenceIds",
                "rawEvidenceChallenges",
                "unresolvedFindings",
                "artifactRegistrySha256",
                "startedAt",
                "reportPath",
            },
            "independent review input",
        )
        expected_marker = (
            "W3_INDEPENDENT_REVIEW=PASS reviewerMode=read-only "
            f"sourceHash={context['sourceTreeHash']}"
        )
        expected_scalars = {
            "schemaVersion": 1,
            "nodeType": "independent-review-input",
            "runId": context["runId"],
            "sourceTreeHashBefore": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "reviewerMode": "read-only",
            "reviewMethod": "raw-artifact-semantic-challenge",
            "waiverAuthority": False,
            "rawArtifactsReviewed": True,
            "challengedEvidenceIds": self.pre_review_ids,
            "unresolvedFindings": [],
            "artifactRegistrySha256": sha256_file(registry_path),
            "reportPath": "review/independent-review.md",
        }
        for key, expected in expected_scalars.items():
            if review_input[key] != expected:
                fail(f"independent review input {key} mismatch")
        reviewer_id = review_input["reviewerId"]
        if (
            not isinstance(reviewer_id, str)
            or len(reviewer_id.strip()) < 4
            or len(reviewer_id) > 240
            or reviewer_id == context["implementerId"]
        ):
            fail("independent reviewer identity is not distinct")

        challenges = review_input["rawEvidenceChallenges"]
        if (
            not isinstance(challenges, list)
            or len(challenges) != 20
            or any(not isinstance(challenge, dict) for challenge in challenges)
        ):
            fail("independent review rawEvidenceChallenges must contain exactly 20")
        if [challenge.get("evidenceId") for challenge in challenges] != (
            self.pre_review_ids
        ):
            fail("independent review rawEvidenceChallenges ID order/set mismatch")
        for leaf_index, (challenge, entry) in enumerate(
            zip(challenges, registry["entries"], strict=True)
        ):
            require_exact_keys(
                challenge,
                {
                    "evidenceId",
                    "leafRecordSha256",
                    "verdict",
                    "challengeNotes",
                    "rawArtifacts",
                },
                f"independent review rawEvidenceChallenges[{leaf_index}]",
            )
            if (
                challenge["evidenceId"] != entry["evidenceId"]
                or challenge["leafRecordSha256"]
                != entry["leafRecord"]["sha256"]
                or challenge["verdict"] != "PASS"
            ):
                fail(
                    "independent review leaf challenge binding/verdict mismatch: "
                    f"{entry['evidenceId']}"
                )
            self._validate_challenge_notes(
                challenge["challengeNotes"],
                f"independent review leaf challenge {entry['evidenceId']}",
            )
            artifact_challenges = challenge["rawArtifacts"]
            expected_artifacts = entry["rawArtifacts"]
            if (
                not isinstance(artifact_challenges, list)
                or len(artifact_challenges) != len(expected_artifacts)
                or any(
                    not isinstance(artifact_challenge, dict)
                    for artifact_challenge in artifact_challenges
                )
            ):
                fail(
                    "independent review must challenge every raw artifact for "
                    f"{entry['evidenceId']}"
                )
            for artifact_index, (artifact_challenge, artifact) in enumerate(
                zip(artifact_challenges, expected_artifacts, strict=True)
            ):
                require_exact_keys(
                    artifact_challenge,
                    {
                        "artifactRole",
                        "sha256",
                        "verdict",
                        "challengeNotes",
                    },
                    (
                        "independent review raw artifact challenge "
                        f"{entry['evidenceId']}[{artifact_index}]"
                    ),
                )
                if (
                    artifact_challenge["artifactRole"]
                    != artifact["artifactRole"]
                    or artifact_challenge["sha256"] != artifact["sha256"]
                    or artifact_challenge["verdict"] != "PASS"
                ):
                    fail(
                        "independent review raw artifact challenge "
                        f"binding/verdict mismatch: {entry['evidenceId']}/"
                        f"{artifact['artifactRole']}"
                    )
                self._validate_challenge_notes(
                    artifact_challenge["challengeNotes"],
                    (
                        "independent review raw artifact challenge "
                        f"{entry['evidenceId']}/{artifact['artifactRole']}"
                    ),
                )

        started = parse_utc(
            review_input["startedAt"], "independent review input startedAt"
        )
        run_started = parse_utc(context["startedAt"], "run startedAt")
        if started < run_started:
            fail("independent review START predates the run")
        if int(started.timestamp() * 1_000_000_000) > upper_bound_ns:
            fail("independent review START is in the future")
        review_report = report_path.read_text(encoding="utf-8")
        if expected_marker not in review_report:
            fail("independent review report lacks its exact marker")
        for evidence_id in self.pre_review_ids:
            if review_report.count(evidence_id) != 1:
                fail(
                    f"independent review must challenge each pre-review ID once: "
                    f"{evidence_id}"
                )
        forbidden_review_targets = (
            self.contract["reviewId"],
            self.contract["integrityId"],
            self.contract["finalGateId"],
            self.contract["finalMarker"],
            "final-manifest.json",
        )
        body_without_marker = review_report.replace(expected_marker, "")
        input_text = input_path.read_text(encoding="utf-8")
        for forbidden in forbidden_review_targets:
            if forbidden in body_without_marker or forbidden in input_text:
                fail(
                    "independent review challenges/references forbidden node: "
                    f"{forbidden}"
                )
        if FORBIDDEN_STRUCTURED_TOKEN in review_report:
            fail("independent review contains forbidden status token")
        review_inputs_latest_ns = max(
            registry_path.stat().st_mtime_ns,
            (run_root / "acceptance-matrix.json").stat().st_mtime_ns,
            (run_root / "WAVE3-VERIFICATION.md").stat().st_mtime_ns,
        )
        if report_path.stat().st_mtime_ns < review_inputs_latest_ns:
            fail("independent review report predates its frozen upstream evidence")
        if input_path.stat().st_mtime_ns < report_path.stat().st_mtime_ns:
            fail("independent review input predates its report")
        if max(report_path.stat().st_mtime_ns, input_path.stat().st_mtime_ns) > (
            upper_bound_ns
        ):
            fail("independent review input/report is outside the run window")
        return review_input, input_path, report_path, expected_marker

    @staticmethod
    def _current_reviewer_process_identity() -> dict[str, Any]:
        return {
            "pid": os.getpid(),
            "parentPid": os.getppid(),
            "executableRealpath": Path(sys.executable).resolve(strict=True).as_posix(),
            "driverRealpath": Path(__file__).resolve(strict=True).as_posix(),
        }

    def _validate_review(self, run_root: Path, upper_bound_ns: int) -> dict[str, Any]:
        context = self._context(run_root)
        registry_path = self._safe_run_file(run_root, "artifact-registry.json")
        registry = load_json(registry_path, "artifact registry")
        (
            review_input,
            input_path,
            report_path,
            expected_marker,
        ) = self._load_review_input(
            run_root, context, registry, upper_bound_ns
        )
        review_path = self._safe_run_file(
            run_root, "review/independent-review.json"
        )
        review = load_json(review_path, "independent review")
        assert_no_forbidden_structured_values(review, "independent review")
        require_exact_keys(
            review,
            {
                "schemaVersion",
                "nodeType",
                "evidenceId",
                "verdict",
                "marker",
                "runId",
                "sourceTreeHash",
                "sourceTreeHashBefore",
                "sourceTreeHashAfter",
                "sourceReadOnlyUnchanged",
                "databaseIdentity",
                "reviewerId",
                "reviewerProcessId",
                "reviewerProcessIdentity",
                "reviewerMode",
                "implementationProcessId",
                "reviewMethod",
                "waiverAuthority",
                "rawArtifactsReviewed",
                "challengedEvidenceIds",
                "rawEvidenceChallenges",
                "unresolvedFindings",
                "artifactRegistrySha256",
                "startedAt",
                "endedAt",
                "reviewInputPath",
                "reviewInputSha256",
                "reportPath",
                "reportSha256",
            },
            "independent review",
        )
        expected_scalars = {
            "schemaVersion": 1,
            "nodeType": "independent-review",
            "evidenceId": self.contract["reviewId"],
            "verdict": "PASS",
            "marker": expected_marker,
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "sourceTreeHashBefore": context["sourceTreeHash"],
            "sourceTreeHashAfter": context["sourceTreeHash"],
            "sourceReadOnlyUnchanged": True,
            "databaseIdentity": context["databaseIdentity"],
            "reviewerId": review_input["reviewerId"],
            "reviewerMode": "read-only",
            "implementationProcessId": context["implementerProcessId"],
            "reviewMethod": "raw-artifact-semantic-challenge",
            "waiverAuthority": False,
            "rawArtifactsReviewed": True,
            "challengedEvidenceIds": self.pre_review_ids,
            "rawEvidenceChallenges": review_input["rawEvidenceChallenges"],
            "unresolvedFindings": [],
            "artifactRegistrySha256": sha256_file(registry_path),
            "startedAt": review_input["startedAt"],
            "reviewInputPath": "review/independent-review-input.json",
            "reviewInputSha256": sha256_file(input_path),
            "reportPath": "review/independent-review.md",
            "reportSha256": sha256_file(report_path),
        }
        for key, expected in expected_scalars.items():
            if review[key] != expected:
                fail(f"independent review {key} mismatch")
        process_identity = review["reviewerProcessIdentity"]
        if not isinstance(process_identity, dict):
            fail("independent reviewer process identity is missing")
        require_exact_keys(
            process_identity,
            {"pid", "parentPid", "executableRealpath", "driverRealpath"},
            "independent reviewer process identity",
        )
        pid = process_identity["pid"]
        parent_pid = process_identity["parentPid"]
        executable_realpath = process_identity["executableRealpath"]
        driver_realpath = process_identity["driverRealpath"]
        expected_process_id = f"pid:{pid}"
        if (
            not isinstance(pid, int)
            or isinstance(pid, bool)
            or pid <= 0
            or not isinstance(parent_pid, int)
            or isinstance(parent_pid, bool)
            or parent_pid <= 0
            or not isinstance(review["reviewerProcessId"], str)
            or review["reviewerProcessId"] != expected_process_id
            or review["reviewerProcessId"]
            in {
                context["implementerProcessId"],
                f"pid:{context['implementerProcessId']}",
            }
            or executable_realpath
            != Path(sys.executable).resolve(strict=True).as_posix()
            or driver_realpath != Path(__file__).resolve(strict=True).as_posix()
        ):
            fail("independent reviewer PID/process identity is invalid or not distinct")
        started = parse_utc(review["startedAt"], "review startedAt")
        ended = parse_utc(review["endedAt"], "review endedAt")
        run_started = parse_utc(context["startedAt"], "run startedAt")
        if started < run_started or ended < started:
            fail("independent review START/END window is invalid")
        if int(ended.timestamp() * 1_000_000_000) > upper_bound_ns:
            fail("independent review END is in the future")
        if review_path.stat().st_mtime_ns < max(
            report_path.stat().st_mtime_ns,
            input_path.stat().st_mtime_ns,
        ):
            fail("independent review JSON predates the inputs it hashes")
        if review_path.stat().st_mtime_ns > upper_bound_ns:
            fail("independent review is outside the run window")
        return review

    def accept_review(self, run_id: str) -> Path:
        self.audit_historical_candidate()
        run_root = self.run_root(run_id)
        review_path = run_root / "review/independent-review.json"
        if review_path.exists() or review_path.is_symlink():
            fail("independent review seal already exists")
        now_ns = time.time_ns()
        context = self._context(run_root)
        self._validate_source_pair(run_root)
        registry, _, _ = self._validate_registry_matrix_report(run_root, now_ns)
        source_before = self.compute_source_snapshot().tree_hash
        if source_before != context["sourceTreeHash"]:
            fail("source drifted before independent review sealing")
        (
            review_input,
            input_path,
            report_path,
            marker,
        ) = self._load_review_input(
            run_root, context, registry, now_ns
        )
        process_identity = self._current_reviewer_process_identity()
        reviewer_process_id = f"pid:{process_identity['pid']}"
        if reviewer_process_id in {
            context["implementerProcessId"],
            f"pid:{context['implementerProcessId']}",
        }:
            fail("independent review cannot be sealed by the implementation process")
        source_after = self.compute_source_snapshot().tree_hash
        if source_after != source_before:
            fail("source changed during read-only independent review sealing")
        review = {
            "schemaVersion": 1,
            "nodeType": "independent-review",
            "evidenceId": self.contract["reviewId"],
            "verdict": "PASS",
            "marker": marker,
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "sourceTreeHashBefore": source_before,
            "sourceTreeHashAfter": source_after,
            "sourceReadOnlyUnchanged": True,
            "databaseIdentity": context["databaseIdentity"],
            "reviewerId": review_input["reviewerId"],
            "reviewerProcessId": reviewer_process_id,
            "reviewerProcessIdentity": process_identity,
            "reviewerMode": "read-only",
            "implementationProcessId": context["implementerProcessId"],
            "reviewMethod": review_input["reviewMethod"],
            "waiverAuthority": review_input["waiverAuthority"],
            "rawArtifactsReviewed": review_input["rawArtifactsReviewed"],
            "challengedEvidenceIds": review_input["challengedEvidenceIds"],
            "rawEvidenceChallenges": review_input["rawEvidenceChallenges"],
            "unresolvedFindings": review_input["unresolvedFindings"],
            "artifactRegistrySha256": sha256_file(
                run_root / "artifact-registry.json"
            ),
            "startedAt": review_input["startedAt"],
            "endedAt": utc_now(),
            "reviewInputPath": "review/independent-review-input.json",
            "reviewInputSha256": sha256_file(input_path),
            "reportPath": "review/independent-review.md",
            "reportSha256": sha256_file(report_path),
        }
        assert_no_forbidden_structured_values(review, "independent review")
        created = False
        try:
            write_new_file(review_path, pretty_json_bytes(review))
            created = True
            committed_source = self.compute_source_snapshot().tree_hash
            if committed_source != source_before:
                fail("source changed while committing the read-only review seal")
            self._validate_review(run_root, time.time_ns())
        except Exception:
            if created:
                review_path.unlink(missing_ok=True)
            raise
        return review_path

    @staticmethod
    def _contains_manifest_back_reference(contents: bytes) -> bool:
        lowered = contents.lower()
        return (
            b"final-manifest.json" in lowered
            or b"finalmanifestsha256" in lowered
            or b"manifestsha256" in lowered
        )

    def create_manifest(self, run_id: str) -> Path:
        self.audit_historical_candidate()
        run_root = self.run_root(run_id)
        manifest_path = run_root / "final-manifest.json"
        if manifest_path.exists() or manifest_path.is_symlink():
            fail("final manifest already exists")
        now_ns = time.time_ns()
        context = self._context(run_root)
        self._validate_source_pair(run_root)
        self._validate_registry_matrix_report(run_root, now_ns)
        self._validate_review(run_root, now_ns)
        self._validate_detached_completion(run_root)
        upstream_nodes: list[dict[str, Any]] = []
        for node_id, relative in UPSTREAM_NODE_SPECS:
            node_path = self._safe_run_file(run_root, relative)
            contents = node_path.read_bytes()
            if self._contains_manifest_back_reference(contents):
                fail(f"upstream node contains backward manifest reference: {relative}")
            upstream_nodes.append(self._describe_node(run_root, relative, node_id))
        if len(upstream_nodes) != 5:
            fail("final manifest must hash exactly five upstream nodes")
        manifest = {
            "schemaVersion": 1,
            "nodeType": "final-manifest",
            "runId": run_id,
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "createdAt": utc_now(),
            "hashDirection": "upstream-to-manifest",
            "preReviewLeafCount": 20,
            "preReviewLeafIds": self.pre_review_ids,
            "reviewId": self.contract["reviewId"],
            "integrityId": self.contract["integrityId"],
            "finalGateId": self.contract["finalGateId"],
            "upstreamNodes": upstream_nodes,
        }
        assert_no_forbidden_structured_values(manifest, "final manifest")
        write_new_file(manifest_path, pretty_json_bytes(manifest))
        return manifest_path

    def _validate_manifest(
        self, run_root: Path, upper_bound_ns: int
    ) -> tuple[dict[str, Any], str]:
        context = self._context(run_root)
        manifest_path = self._safe_run_file(run_root, "final-manifest.json")
        manifest = load_json(manifest_path, "final manifest")
        assert_no_forbidden_structured_values(manifest, "final manifest")
        require_exact_keys(
            manifest,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "createdAt",
                "hashDirection",
                "preReviewLeafCount",
                "preReviewLeafIds",
                "reviewId",
                "integrityId",
                "finalGateId",
                "upstreamNodes",
            },
            "final manifest",
        )
        expected_scalars = {
            "schemaVersion": 1,
            "nodeType": "final-manifest",
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "hashDirection": "upstream-to-manifest",
            "preReviewLeafCount": 20,
            "preReviewLeafIds": self.pre_review_ids,
            "reviewId": self.contract["reviewId"],
            "integrityId": self.contract["integrityId"],
            "finalGateId": self.contract["finalGateId"],
        }
        for key, expected in expected_scalars.items():
            if manifest[key] != expected:
                fail(f"final manifest {key} mismatch")
        self._validate_detached_completion(run_root)
        parse_utc(manifest["createdAt"], "manifest createdAt")
        upstream = manifest["upstreamNodes"]
        if not isinstance(upstream, list) or len(upstream) != 5:
            fail("final manifest upstream count is not exactly five")
        expected_pairs = list(UPSTREAM_NODE_SPECS)
        actual_pairs = [(node.get("nodeId"), node.get("path")) for node in upstream]
        if actual_pairs != expected_pairs:
            fail("final manifest upstream set/order/direction mismatch")
        for index, node in enumerate(upstream):
            candidate = self._validate_node_descriptor(
                node,
                run_root,
                context,
                upper_bound_ns,
                f"manifest upstream[{index}]",
            )
            if self._contains_manifest_back_reference(candidate.read_bytes()):
                fail(f"manifest upstream contains a backward reference: {node['path']}")
        manifest_metadata = manifest_path.stat()
        if manifest_metadata.st_mtime_ns > upper_bound_ns:
            fail("final manifest is outside the run window")
        for node in upstream:
            if node["mtimeEpochNs"] > manifest_metadata.st_mtime_ns:
                fail("final manifest predates an upstream node")
        return manifest, sha256_file(manifest_path)

    @staticmethod
    def _assert_acyclic(nodes: Iterable[str], edges: Iterable[tuple[str, str]]) -> None:
        adjacency: dict[str, list[str]] = {node: [] for node in nodes}
        indegree: dict[str, int] = {node: 0 for node in nodes}
        for source, target in edges:
            if source not in adjacency or target not in adjacency:
                fail(f"evidence graph edge references unknown node: {source}->{target}")
            adjacency[source].append(target)
            indegree[target] += 1
        ready = sorted(node for node, degree in indegree.items() if degree == 0)
        visited = 0
        while ready:
            node = ready.pop(0)
            visited += 1
            for target in sorted(adjacency[node]):
                indegree[target] -= 1
                if indegree[target] == 0:
                    ready.append(target)
                    ready.sort()
        if visited != len(adjacency):
            fail("evidence graph contains a cycle")

    def _mechanical_graph(self) -> tuple[list[str], list[tuple[str, str]]]:
        nodes = [
            *self.pre_review_ids,
            "artifact-registry",
            "acceptance-matrix",
            "verification-report",
            self.contract["reviewId"],
            "detached-completion",
            "final-manifest",
            self.contract["integrityId"],
            self.contract["finalGateId"],
        ]
        edges: list[tuple[str, str]] = []
        edges.extend(
            (evidence_id, "artifact-registry")
            for evidence_id in self.pre_review_ids
        )
        edges.extend(
            [
                ("artifact-registry", "acceptance-matrix"),
                ("artifact-registry", "verification-report"),
                ("artifact-registry", self.contract["reviewId"]),
                ("acceptance-matrix", self.contract["reviewId"]),
                ("verification-report", self.contract["reviewId"]),
                ("artifact-registry", "final-manifest"),
                ("acceptance-matrix", "final-manifest"),
                ("verification-report", "final-manifest"),
                (self.contract["reviewId"], "final-manifest"),
                ("detached-completion", "final-manifest"),
                ("final-manifest", self.contract["integrityId"]),
            ]
        )
        edges.extend(
            (child, self.contract["finalGateId"]) for child in self.final_child_ids
        )
        return nodes, edges

    def create_integrity(self, run_id: str) -> Path:
        self.audit_historical_candidate()
        run_root = self.run_root(run_id)
        integrity_path = run_root / "evidence-integrity.json"
        if integrity_path.exists() or integrity_path.is_symlink():
            fail("evidence integrity node already exists")
        now_ns = time.time_ns()
        context = self._context(run_root)
        self._validate_source_pair(run_root)
        self._validate_registry_matrix_report(run_root, now_ns)
        self._validate_review(run_root, now_ns)
        manifest_path = self._safe_run_file(run_root, "final-manifest.json")
        manifest_before = manifest_path.read_bytes()
        _, manifest_sha = self._validate_manifest(run_root, now_ns)
        for _, relative in UPSTREAM_NODE_SPECS:
            contents = self._safe_run_file(run_root, relative).read_bytes()
            if manifest_sha.encode("ascii") in contents:
                fail(f"upstream node contains the final manifest full SHA256: {relative}")
        nodes, edges = self._mechanical_graph()
        self._assert_acyclic(nodes, edges)
        source_end = load_json(
            self._safe_run_file(run_root, "source/end.json"), "source END"
        )
        report_path = self._safe_run_file(run_root, "WAVE3-VERIFICATION.md")
        if report_path.stat().st_mtime_ns <= source_end["maximumSourceMtimeNs"]:
            fail("verification report is not newer than relevant frozen source")
        integrity = {
            "schemaVersion": 1,
            "nodeType": "evidence-integrity",
            "evidenceId": self.contract["integrityId"],
            "verdict": "PASS",
            "marker": "W3_EVIDENCE_INTEGRITY=PASS",
            "runId": run_id,
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "verifiedAt": utc_now(),
            "manifestPath": "final-manifest.json",
            "manifestSha256": manifest_sha,
            "preReviewLeafCount": 20,
            "preReviewLeafIds": self.pre_review_ids,
            "mechanicalChecks": MECHANICAL_CHECKS,
        }
        assert_no_forbidden_structured_values(integrity, "evidence integrity")
        write_new_file(integrity_path, pretty_json_bytes(integrity))
        if manifest_path.read_bytes() != manifest_before:
            fail("integrity validation rewrote the final manifest")
        return integrity_path

    def _validate_integrity(
        self, run_root: Path, upper_bound_ns: int
    ) -> dict[str, Any]:
        context = self._context(run_root)
        _, manifest_sha = self._validate_manifest(run_root, upper_bound_ns)
        integrity_path = self._safe_run_file(run_root, "evidence-integrity.json")
        integrity = load_json(integrity_path, "evidence integrity")
        assert_no_forbidden_structured_values(integrity, "evidence integrity")
        require_exact_keys(
            integrity,
            {
                "schemaVersion",
                "nodeType",
                "evidenceId",
                "verdict",
                "marker",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "verifiedAt",
                "manifestPath",
                "manifestSha256",
                "preReviewLeafCount",
                "preReviewLeafIds",
                "mechanicalChecks",
            },
            "evidence integrity",
        )
        expected = {
            "schemaVersion": 1,
            "nodeType": "evidence-integrity",
            "evidenceId": self.contract["integrityId"],
            "verdict": "PASS",
            "marker": "W3_EVIDENCE_INTEGRITY=PASS",
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "manifestPath": "final-manifest.json",
            "manifestSha256": manifest_sha,
            "preReviewLeafCount": 20,
            "preReviewLeafIds": self.pre_review_ids,
        }
        for key, value in expected.items():
            if integrity.get(key) != value:
                fail(f"evidence integrity {key} mismatch")
        if integrity.get("mechanicalChecks") != MECHANICAL_CHECKS:
            fail("evidence integrity mechanical check set/order drifted")
        if integrity_path.stat().st_mtime_ns < (
            run_root / "final-manifest.json"
        ).stat().st_mtime_ns:
            fail("evidence integrity predates the manifest")
        return integrity

    def audit(self, run_id: str) -> str:
        self.audit_historical_candidate()
        run_root = self.run_root(run_id)
        self._context(run_root)
        stage = "INITIALIZED"
        if (run_root / "source/end.json").exists():
            self._validate_source_pair(run_root)
            self._validate_registry_matrix_report(run_root, time.time_ns())
            stage = "PRE_REVIEW_ASSEMBLED"
        if (run_root / "review/independent-review.json").exists():
            self._validate_review(run_root, time.time_ns())
            stage = "INDEPENDENT_REVIEW_ACCEPTED"
        if (run_root / "final-manifest.json").exists():
            self._validate_manifest(run_root, time.time_ns())
            stage = "MANIFEST_CREATED"
        if (run_root / "evidence-integrity.json").exists():
            self._validate_integrity(run_root, time.time_ns())
            stage = "INTEGRITY_PASS"
        if (run_root / "final-gate.json").exists():
            self._validate_final_gate(run_root)
            stage = "FINAL_PASS"
        return stage

    def _validate_final_gate(
        self,
        run_root: Path,
        require_completion_applied: bool = True,
    ) -> dict[str, Any]:
        context = self._context(run_root)
        final_path = self._safe_run_file(run_root, "final-gate.json")
        proof_path = self._safe_run_file(
            run_root, "checkbox-normalization-proof.json"
        )
        final = load_json(final_path, "final gate")
        proof = load_json(proof_path, "checkbox normalization proof")
        assert_no_forbidden_structured_values(final, "final gate")
        assert_no_forbidden_structured_values(
            proof, "checkbox normalization proof"
        )
        require_exact_keys(
            proof,
            {
                "schemaVersion",
                "nodeType",
                "runId",
                "sourceTreeHashBefore",
                "sourceTreeHashAfter",
                "normalizationUnchanged",
                "appliedTaskIds",
                "tasksPath",
                "provedAt",
            },
            "checkbox normalization proof",
        )
        require_exact_keys(
            final,
            {
                "schemaVersion",
                "nodeType",
                "evidenceId",
                "verdict",
                "marker",
                "runId",
                "sourceTreeHash",
                "databaseIdentity",
                "derivedAt",
                "childCount",
                "childEvidenceIds",
                "children",
                "checkboxNormalizationProofPath",
                "checkboxNormalizationProofSha256",
            },
            "final gate",
        )
        expected = {
            "schemaVersion": 1,
            "nodeType": "final-gate",
            "evidenceId": self.contract["finalGateId"],
            "verdict": "PASS",
            "marker": self.contract["finalMarker"],
            "runId": context["runId"],
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "childCount": 22,
            "childEvidenceIds": self.final_child_ids,
            "checkboxNormalizationProofPath": "checkbox-normalization-proof.json",
            "checkboxNormalizationProofSha256": sha256_file(proof_path),
        }
        for key, value in expected.items():
            if final[key] != value:
                fail(f"final gate {key} mismatch")
        parse_utc(final["derivedAt"], "final gate derivedAt")
        children = final["children"]
        if not isinstance(children, list) or len(children) != 22:
            fail("final gate children count is not exactly 22")
        if [child.get("evidenceId") for child in children] != self.final_child_ids:
            fail("final gate child ID order/set mismatch")
        expected_record_paths = [
            *[
                f"leaves/{self.leaf_contracts[evidence_id]['slug']}/leaf.json"
                for evidence_id in self.pre_review_ids
            ],
            "review/independent-review.json",
            "evidence-integrity.json",
        ]
        for index, (child, expected_path) in enumerate(
            zip(children, expected_record_paths, strict=True)
        ):
            require_exact_keys(
                child,
                {"evidenceId", "verdict", "recordPath", "recordSha256"},
                f"final gate children[{index}]",
            )
            if child["verdict"] != "PASS":
                fail(f"final gate child is not PASS: {child['evidenceId']}")
            if child["recordPath"] != expected_path:
                fail(f"final gate child path mismatch: {child['evidenceId']}")
            record = self._safe_run_file(run_root, child["recordPath"])
            if child["recordSha256"] != sha256_file(record):
                fail(f"final gate child hash mismatch: {child['evidenceId']}")
        if (
            proof.get("schemaVersion") != 1
            or proof.get("nodeType") != "checkbox-normalization-proof"
            or proof.get("runId") != context["runId"]
            or proof.get("sourceTreeHashBefore") != context["sourceTreeHash"]
            or proof.get("sourceTreeHashAfter") != context["sourceTreeHash"]
            or proof.get("normalizationUnchanged") is not True
            or proof.get("appliedTaskIds")
            != self.contract["verificationTaskIds"]
            or proof.get("tasksPath") != self.contract["source"]["tasksPath"]
        ):
            fail("checkbox normalization proof is not exact")
        parse_utc(proof["provedAt"], "checkbox proof provedAt")
        if self.compute_source_snapshot().tree_hash != context["sourceTreeHash"]:
            fail("normalized source hash changed after FINAL")
        if require_completion_applied and not self._verification_tasks_are_checked():
            fail("FINAL verification task checkboxes are not all applied")
        return final

    def _verification_tasks_are_checked(self) -> bool:
        tasks_path = self.repository_root.joinpath(
            *PurePosixPath(self.contract["source"]["tasksPath"]).parts
        )
        states: dict[str, bytes] = {}
        for line in tasks_path.read_bytes().splitlines(keepends=True):
            match = TASK_LINE_PATTERN.match(line)
            if not match:
                continue
            task_id = match.group("task_id").decode("ascii")
            if task_id in self.contract["verificationTaskIds"]:
                states[task_id] = match.group("token")
        return (
            list(states) == self.contract["verificationTaskIds"]
            and all(token == b"x" for token in states.values())
        )

    def _prepare_completion(
        self, run_root: Path, context: dict[str, Any]
    ) -> tuple[Path, bytes, bytes, dict[str, Any]]:
        completion = self._validate_detached_completion(run_root)
        changes = completion["taskChanges"]
        tasks_path = self.repository_root.joinpath(
            *PurePosixPath(completion["tasksPath"]).parts
        )
        original = tasks_path.read_bytes()
        lines = original.splitlines(keepends=True)
        changes_by_id = {change["taskId"]: change for change in changes}
        seen: set[str] = set()
        rewritten: list[bytes] = []
        for line in lines:
            match = TASK_LINE_PATTERN.match(line)
            if not match:
                rewritten.append(line)
                continue
            task_id = match.group("task_id").decode("ascii")
            if task_id not in changes_by_id:
                rewritten.append(line)
                continue
            normalized_line = TASK_CHECKBOX_PATTERN.sub(rb"\1[~]", line)
            change = changes_by_id[task_id]
            if sha256_bytes(normalized_line) != change["normalizedLineSha256"]:
                fail(f"detached completion line drift: {task_id}")
            rewritten.append(TASK_CHECKBOX_PATTERN.sub(rb"\1[x]", line, count=1))
            seen.add(task_id)
        if seen != set(self.contract["verificationTaskIds"]):
            fail("not every detached verification task was found")
        rewritten_bytes = b"".join(rewritten)
        before_hash = self.compute_source_snapshot().tree_hash
        if before_hash != context["sourceTreeHash"]:
            fail("source drifted before detached completion was applied")
        normalized_original = TASK_CHECKBOX_PATTERN.sub(rb"\1[~]", original)
        normalized_rewritten = TASK_CHECKBOX_PATTERN.sub(
            rb"\1[~]", rewritten_bytes
        )
        if normalized_rewritten != normalized_original:
            fail("prepared checkbox completion changes normalized task content")
        proof = {
            "schemaVersion": 1,
            "nodeType": "checkbox-normalization-proof",
            "runId": context["runId"],
            "sourceTreeHashBefore": before_hash,
            "sourceTreeHashAfter": context["sourceTreeHash"],
            "normalizationUnchanged": True,
            "appliedTaskIds": self.contract["verificationTaskIds"],
            "tasksPath": completion["tasksPath"],
            "provedAt": utc_now(),
        }
        return tasks_path, original, rewritten_bytes, proof

    def _commit_completion(
        self,
        tasks_path: Path,
        original: bytes,
        rewritten: bytes,
        context: dict[str, Any],
    ) -> None:
        replace_file_atomically(tasks_path, rewritten)
        try:
            after_snapshot = self.compute_source_snapshot()
            if after_snapshot.tree_hash != context["sourceTreeHash"]:
                fail("checkbox completion changed the normalized source hash")
            if not self._verification_tasks_are_checked():
                fail("checkbox completion did not apply every verification task")
        except Exception:
            replace_file_atomically(tasks_path, original)
            raise

    def finalize(self, run_id: str, apply_completion: bool) -> Path:
        self.audit_historical_candidate()
        if not apply_completion:
            fail("FINAL marker requires --apply-completion and normalization proof")
        run_root = self.run_root(run_id)
        final_path = run_root / "final-gate.json"
        proof_path = run_root / "checkbox-normalization-proof.json"
        now_ns = time.time_ns()
        context = self._context(run_root)
        self._validate_source_pair(run_root)
        leaves = self._collect_leaves(run_root, now_ns)
        self._validate_registry_matrix_report(run_root, now_ns)
        review = self._validate_review(run_root, now_ns)
        integrity = self._validate_integrity(run_root, now_ns)
        if len(self.final_child_ids) != 22 or len(set(self.final_child_ids)) != 22:
            fail("FINAL dependency set is not exactly 22 unique IDs")
        child_records: list[dict[str, Any]] = []
        for evidence_id, leaf in zip(self.pre_review_ids, leaves, strict=True):
            slug = self.leaf_contracts[evidence_id]["slug"]
            child_records.append(
                {
                    "evidenceId": evidence_id,
                    "verdict": leaf["verdict"],
                    "recordPath": f"leaves/{slug}/leaf.json",
                    "recordSha256": sha256_file(
                        run_root / f"leaves/{slug}/leaf.json"
                    ),
                }
            )
        child_records.extend(
            [
                {
                    "evidenceId": self.contract["reviewId"],
                    "verdict": review["verdict"],
                    "recordPath": "review/independent-review.json",
                    "recordSha256": sha256_file(
                        run_root / "review/independent-review.json"
                    ),
                },
                {
                    "evidenceId": self.contract["integrityId"],
                    "verdict": integrity["verdict"],
                    "recordPath": "evidence-integrity.json",
                    "recordSha256": sha256_file(
                        run_root / "evidence-integrity.json"
                    ),
                },
            ]
        )
        if [child["evidenceId"] for child in child_records] != self.final_child_ids:
            fail("FINAL child evidence order/set mismatch")
        if any(child["verdict"] != "PASS" for child in child_records):
            fail("FINAL child evidence includes a non-PASS verdict")
        tasks_path, original_tasks, rewritten_tasks, proof = (
            self._prepare_completion(run_root, context)
        )
        final = {
            "schemaVersion": 1,
            "nodeType": "final-gate",
            "evidenceId": self.contract["finalGateId"],
            "verdict": "PASS",
            "marker": self.contract["finalMarker"],
            "runId": run_id,
            "sourceTreeHash": context["sourceTreeHash"],
            "databaseIdentity": context["databaseIdentity"],
            "derivedAt": utc_now(),
            "childCount": 22,
            "childEvidenceIds": self.final_child_ids,
            "children": child_records,
            "checkboxNormalizationProofPath": "checkbox-normalization-proof.json",
            "checkboxNormalizationProofSha256": sha256_bytes(
                pretty_json_bytes(proof)
            ),
        }
        assert_no_forbidden_structured_values(final, "final gate")
        proof_bytes = pretty_json_bytes(proof)
        final_bytes = pretty_json_bytes(final)
        proof_exists = proof_path.exists() or proof_path.is_symlink()
        final_exists = final_path.exists() or final_path.is_symlink()
        if proof_exists or final_exists:
            if not proof_exists or not final_exists:
                fail(
                    "incomplete pre-existing FINAL output; tasks were not modified"
                )
            self._validate_final_gate(
                run_root, require_completion_applied=False
            )
            self._commit_completion(
                tasks_path,
                original_tasks,
                rewritten_tasks,
                context,
            )
            self._validate_final_gate(run_root)
            return final_path
        created_proof = False
        created_final = False
        try:
            write_new_file(proof_path, proof_bytes)
            created_proof = True
            write_new_file(final_path, final_bytes)
            created_final = True
            self._validate_final_gate(
                run_root, require_completion_applied=False
            )
            self._commit_completion(
                tasks_path,
                original_tasks,
                rewritten_tasks,
                context,
            )
            self._validate_final_gate(run_root)
        except Exception:
            if tasks_path.read_bytes() != original_tasks:
                replace_file_atomically(tasks_path, original_tasks)
            if created_final:
                final_path.unlink(missing_ok=True)
            if created_proof:
                proof_path.unlink(missing_ok=True)
            raise
        return final_path


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Strict WAVE-3 evidence DAG verifier"
    )
    result.add_argument(
        "--repo",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help=argparse.SUPPRESS,
    )
    result.add_argument("--contract", type=Path, help=argparse.SUPPRESS)
    subcommands = result.add_subparsers(dest="command", required=True)

    init = subcommands.add_parser("init", help="create a fresh source-bound run")
    init.add_argument("--run-id", required=True)
    init.add_argument("--db-identity", required=True)
    init.add_argument("--implementer-id", required=True)
    init.add_argument("--implementer-process-id", required=True)

    context = subcommands.add_parser(
        "context", help="print the exact context line required in a leaf artifact"
    )
    context.add_argument("--run-id", required=True)
    context.add_argument("--evidence-id", required=True)

    register = subcommands.add_parser(
        "register", help="register an already-produced raw pre-review leaf"
    )
    register.add_argument("--run-id", required=True)
    register.add_argument("--evidence-id", required=True)
    register.add_argument("--primary", required=True)
    register.add_argument("--artifact", action="append", default=[])

    capture = subcommands.add_parser(
        "capture", help="capture a command whose output emits the fixed leaf marker"
    )
    capture.add_argument("--run-id", required=True)
    capture.add_argument("--evidence-id", required=True)
    capture.add_argument("leaf_command", nargs=argparse.REMAINDER)

    assemble = subcommands.add_parser(
        "assemble", help="seal source and create registry/matrix/report from exact 20"
    )
    assemble.add_argument("--run-id", required=True)

    review = subcommands.add_parser(
        "accept-review", help="validate a separately produced read-only review"
    )
    review.add_argument("--run-id", required=True)

    manifest = subcommands.add_parser(
        "manifest", help="hash the exact five upstream nodes in one direction"
    )
    manifest.add_argument("--run-id", required=True)

    integrity = subcommands.add_parser(
        "integrity", help="create the post-manifest mechanical integrity node"
    )
    integrity.add_argument("--run-id", required=True)

    audit = subcommands.add_parser("audit", help="validate the current run stage")
    audit.add_argument("--run-id", required=True)

    final = subcommands.add_parser(
        "final", help="derive FINAL from exact 22 and apply detached checkboxes"
    )
    final.add_argument("--run-id", required=True)
    final.add_argument("--apply-completion", action="store_true")

    subcommands.add_parser(
        "audit-history", help="validate the permanent invalidated historical run"
    )
    return result


def main(argv: Sequence[str] | None = None) -> int:
    arguments = parser().parse_args(argv)
    try:
        verifier = Wave3Evidence(arguments.repo, arguments.contract)
        command = arguments.command
        if command == "init":
            run_root = verifier.initialize(
                arguments.run_id,
                arguments.db_identity,
                arguments.implementer_id,
                arguments.implementer_process_id,
            )
            print(
                f"W3_EVIDENCE_RUN_INITIALIZED=PASS runId={arguments.run_id} "
                f"path={run_root}"
            )
        elif command == "context":
            run_root = verifier.run_root(arguments.run_id)
            context = verifier._context(run_root)
            if arguments.evidence_id not in verifier.leaf_contracts:
                fail(f"unexpected pre-review evidence ID: {arguments.evidence_id}")
            print(verifier.context_line(context, arguments.evidence_id))
        elif command == "register":
            path = verifier.register_leaf(
                arguments.run_id,
                arguments.evidence_id,
                arguments.primary,
                arguments.artifact,
            )
            print(
                f"W3_EVIDENCE_LEAF_REGISTERED=PASS "
                f"evidenceId={arguments.evidence_id} path={path}"
            )
        elif command == "capture":
            leaf_command = list(arguments.leaf_command)
            if leaf_command and leaf_command[0] == "--":
                leaf_command.pop(0)
            path = verifier.capture_leaf(
                arguments.run_id, arguments.evidence_id, leaf_command
            )
            print(
                f"W3_EVIDENCE_LEAF_CAPTURED=PASS "
                f"evidenceId={arguments.evidence_id} path={path}"
            )
        elif command == "assemble":
            verifier.assemble(arguments.run_id)
            print(
                f"W3_PRE_REVIEW_DAG=PASS runId={arguments.run_id} leafCount=20"
            )
        elif command == "accept-review":
            verifier.accept_review(arguments.run_id)
            print(
                f"W3_INDEPENDENT_REVIEW_CONTRACT=PASS runId={arguments.run_id}"
            )
        elif command == "manifest":
            path = verifier.create_manifest(arguments.run_id)
            print(f"W3_DETACHED_MANIFEST=PASS runId={arguments.run_id} path={path}")
        elif command == "integrity":
            path = verifier.create_integrity(arguments.run_id)
            print(f"W3_EVIDENCE_INTEGRITY=PASS runId={arguments.run_id} path={path}")
        elif command == "audit":
            stage = verifier.audit(arguments.run_id)
            print(f"W3_EVIDENCE_AUDIT=PASS runId={arguments.run_id} stage={stage}")
        elif command == "final":
            path = verifier.finalize(
                arguments.run_id, apply_completion=arguments.apply_completion
            )
            print(f"{verifier.contract['finalMarker']} runId={arguments.run_id} path={path}")
        elif command == "audit-history":
            verifier.audit_historical_candidate()
            print(
                "W3_HISTORICAL_CANDIDATE_GUARD=PASS "
                f"runId={HISTORICAL_RUN_ID} status=INVALIDATED_NON_FINAL"
            )
        else:
            fail(f"unsupported command: {command}")
        return 0
    except EvidenceError as error:
        print(f"[verify-wave3-evidence] ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
