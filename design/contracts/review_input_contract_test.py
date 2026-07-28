from __future__ import annotations

import os
import re
import stat
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
ARCHITECTURE = PROJECT_ROOT / "output" / "shenzhouHR-architecture.md"
UIUX = PROJECT_ROOT / "output" / "shenzhouHR-uiux.md"
OPENAPI = PROJECT_ROOT / "api" / "openapi.yaml"
LAUNCHER = PROJECT_ROOT / "scripts" / "run_umadev"

SECURITY_SOURCE_CONTRACTS = {
    "backend/src/main/java/com/szsemicon/hr/shared/security/SecurityConfiguration.java": (
        ".anyRequest().authenticated()",
        "HttpServletResponse.SC_UNAUTHORIZED",
        "HttpServletResponse.SC_NOT_FOUND",
    ),
    "backend/src/main/java/com/szsemicon/hr/shared/security/DevelopmentPrincipalFilter.java": (
        "@ConditionalOnProperty(",
        'havingValue = "true"',
        "allowedPrincipalId.equals(candidate)",
    ),
    "backend/src/main/java/com/szsemicon/hr/employee/application/EmployeeListQueryService.java": (
        "MAX_PAGE_SIZE = 100",
        "capabilityService.require(CapabilityCodes.MASTER_DATA_READ)",
        "principalProvider.currentPrincipalId()",
    ),
    "backend/src/main/resources/mappers/EmployeeReadMapper.xml": (
        "#{principalId}",
        "#{capabilityCode}",
        "LIMIT #{limit} OFFSET #{offset}",
    ),
}

SOURCE_EXTENSIONS = {
    ".astro",
    ".c",
    ".cc",
    ".cpp",
    ".cs",
    ".css",
    ".dart",
    ".ex",
    ".exs",
    ".go",
    ".h",
    ".hpp",
    ".html",
    ".java",
    ".js",
    ".jsx",
    ".kt",
    ".less",
    ".mjs",
    ".php",
    ".py",
    ".rb",
    ".rs",
    ".sass",
    ".scala",
    ".scss",
    ".svelte",
    ".swift",
    ".ts",
    ".tsx",
    ".vue",
}
SKIPPED_DIRECTORIES = {
    ".git",
    ".next",
    ".pytest_cache",
    "__pycache__",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "out",
    "output",
    "target",
    "vendor",
}


def source_files(directory: Path, depth: int = 0) -> list[Path]:
    """Mirror UmaDev 1.0.64's no-follow source walk and OS directory order."""
    if depth > 16:
        return []
    files: list[Path] = []
    with os.scandir(directory) as entries:
        for entry in entries:
            path = Path(entry.path)
            if entry.is_symlink():
                continue
            if entry.is_dir(follow_symlinks=False):
                if entry.name.startswith(".") or entry.name in SKIPPED_DIRECTORIES:
                    continue
                files.extend(source_files(path, depth + 1))
            elif entry.is_file(follow_symlinks=False) and path.suffix in SOURCE_EXTENSIONS:
                files.append(path)
    return files


def review_digest(limit: int = 60_000) -> str:
    parts: list[str] = []
    size = 0
    for path in source_files(PROJECT_ROOT)[:40]:
        relative = path.relative_to(PROJECT_ROOT).as_posix()
        content = path.read_text(encoding="utf-8")[:4_000]
        chunk = f"\n// ===== {relative} =====\n{content}\n"
        parts.append(chunk)
        size += len(chunk.encode("utf-8"))
        if size >= limit:
            break
    return "".join(parts)


def openapi_operations() -> set[tuple[str, str]]:
    text = OPENAPI.read_text(encoding="utf-8")
    server_match = re.search(r"(?m)^\s*- url: (\S+)\s*$", text)
    if server_match is None:
        raise AssertionError("OpenAPI server URL is missing")
    prefix = server_match.group(1).rstrip("/")
    operations: set[tuple[str, str]] = set()
    current_path: str | None = None
    for line in text.splitlines():
        path_match = re.match(r"^  (/[^:]+):\s*$", line)
        if path_match:
            current_path = path_match.group(1)
            continue
        method_match = re.match(r"^    (get|post|put|patch|delete):\s*$", line)
        if current_path and method_match:
            operations.add((method_match.group(1).upper(), f"{prefix}{current_path}"))
    return operations


class ReviewInputContractTest(unittest.TestCase):
    def test_expected_blackboard_documents_are_present(self) -> None:
        self.assertTrue(ARCHITECTURE.is_file())
        self.assertTrue(UIUX.is_file())
        self.assertIn("## Visual direction\n\n`product`", UIUX.read_text(encoding="utf-8"))

    def test_architecture_declares_every_openapi_operation(self) -> None:
        architecture = ARCHITECTURE.read_text(encoding="utf-8")
        operations = openapi_operations()
        self.assertGreater(len(operations), 0)
        for method, path in operations:
            self.assertIn(f"| {method} | `{path}` |", architecture)

    def test_architecture_exposes_backend_and_security_evidence(self) -> None:
        architecture = ARCHITECTURE.read_text(encoding="utf-8")
        for relative_path in SECURITY_SOURCE_CONTRACTS:
            self.assertTrue((PROJECT_ROOT / relative_path).is_file())
            self.assertIn(relative_path, architecture)

    def test_backend_security_contracts_are_present_in_real_source(self) -> None:
        for relative_path, required_fragments in SECURITY_SOURCE_CONTRACTS.items():
            source = (PROJECT_ROOT / relative_path).read_text(encoding="utf-8")
            for fragment in required_fragments:
                self.assertIn(fragment, source, f"{relative_path} must contain {fragment}")

    def test_review_digest_starts_with_real_product_source(self) -> None:
        digest = review_digest()
        first_16k = digest.encode("utf-8")[:16_000].decode("utf-8", errors="ignore")
        self.assertNotIn("design/open-design/v1.9/", digest)
        self.assertNotIn("design/output/v1.9/", digest)
        self.assertIn("SecurityConfiguration.java", first_16k)
        self.assertIn("EmployeeReadMapper.xml", first_16k)
        self.assertIn("frontend/src/", first_16k)
        self.assertIn("backend/src/", digest)

    def test_launcher_sets_the_real_review_timeout_override(self) -> None:
        launcher = LAUNCHER.read_text(encoding="utf-8")
        self.assertIn('UMADEV_REVIEW_TURN_TIMEOUT_SECS:=300', launcher)
        self.assertNotIn("model_reasoning_effort", launcher)
        self.assertTrue(LAUNCHER.stat().st_mode & stat.S_IXUSR)

    def test_failed_reasoning_workaround_is_absent(self) -> None:
        self.assertFalse((PROJECT_ROOT / ".codex" / "config.toml").exists())


if __name__ == "__main__":
    unittest.main()
