#!/usr/bin/env python3
"""Repeatable structural checks for customer Markdown and the local LLM wiki."""

from __future__ import annotations

import re
import sys
from collections import deque
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[2]
WIKI = ROOT / ".llm-wiki"
DELIVERY = ROOT / "docs" / "customer-delivery"
LINK_RE = re.compile(r"\[[^\]]+\]\(([^)]+)\)")


def markdown_table_issues(path: Path) -> list[str]:
    issues: list[str] = []
    lines = path.read_text(encoding="utf-8").splitlines()
    in_code = False
    index = 0
    while index < len(lines):
        stripped = lines[index].strip()
        if stripped.startswith("```"):
            in_code = not in_code
            index += 1
            continue
        if (
            not in_code
            and stripped.startswith("|")
            and index + 1 < len(lines)
            and re.match(r"^\s*\|?\s*:?-{3,}", lines[index + 1])
        ):
            expected = len(stripped.strip("|").split("|"))
            table_line = index + 1
            while table_line < len(lines) and lines[table_line].strip().startswith("|"):
                actual = len(lines[table_line].strip().strip("|").split("|"))
                if actual != expected:
                    issues.append(
                        f"{path.relative_to(ROOT)}:{table_line + 1}: "
                        f"table columns={actual}, expected={expected}"
                    )
                table_line += 1
            index = table_line
            continue
        index += 1
    return issues


def local_markdown_links(path: Path) -> list[Path]:
    links: list[Path] = []
    text = path.read_text(encoding="utf-8")
    for raw_target in LINK_RE.findall(text):
        target = unquote(raw_target.split("#", 1)[0].strip())
        if not target or target.startswith(("http://", "https://", "mailto:")):
            continue
        links.append((path.parent / target).resolve())
    return links


def wiki_issues() -> list[str]:
    issues: list[str] = []
    wiki_files = sorted(WIKI.rglob("*.md"))
    articles = {path.resolve() for path in wiki_files if not path.name.startswith("_")}

    graph: dict[Path, list[Path]] = {}
    for path in wiki_files:
        text = path.read_text(encoding="utf-8")
        links = local_markdown_links(path)
        graph[path.resolve()] = [link for link in links if WIKI.resolve() in link.parents]
        for link in links:
            if not link.exists():
                issues.append(
                    f"{path.relative_to(ROOT)}: broken link -> {link.relative_to(ROOT)}"
                )
        if not path.name.startswith("_"):
            if not text.startswith("---\n") or "\ntitle:" not in text or "\nupdated:" not in text:
                issues.append(f"{path.relative_to(ROOT)}: missing required frontmatter")
            if "\n## See Also\n" not in text:
                issues.append(f"{path.relative_to(ROOT)}: missing See Also")

            frontmatter = text.split("---", 2)[1] if text.count("---") >= 2 else ""
            in_sources = False
            source_count = 0
            for line in frontmatter.splitlines():
                if line == "sources:":
                    in_sources = True
                    continue
                if in_sources and line.startswith("  - "):
                    source_count += 1
                    source = ROOT / line[4:].strip()
                    if not source.exists():
                        issues.append(
                            f"{path.relative_to(ROOT)}: missing source -> {source.relative_to(ROOT)}"
                        )
                elif in_sources and line and not line.startswith(" "):
                    in_sources = False
            if source_count == 0:
                issues.append(f"{path.relative_to(ROOT)}: empty sources frontmatter")

    start = (WIKI / "_index.md").resolve()
    visited: set[Path] = set()
    queue: deque[Path] = deque([start])
    while queue:
        current = queue.popleft()
        if current in visited or current not in graph:
            continue
        visited.add(current)
        queue.extend(graph[current])
    for orphan in sorted(articles - visited):
        issues.append(f"{orphan.relative_to(ROOT)}: orphan article")
    return issues


def main() -> int:
    issues: list[str] = []
    markdown_files = sorted(DELIVERY.glob("*.md")) + sorted(WIKI.rglob("*.md"))
    for path in markdown_files:
        issues.extend(markdown_table_issues(path))
    issues.extend(wiki_issues())

    if issues:
        print("FAILED")
        print("\n".join(f"- {issue}" for issue in issues))
        return 1
    print(
        f"OK: {len(markdown_files)} Markdown files; "
        "tables, wiki links, frontmatter, sources, See Also, and reachability passed"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
