#!/usr/bin/env python3
"""Validate and narrowly repair the ShenzhouHR BaoTa SPA fallback.

The helper never edits the live vhost.  It validates the fixed customer site
identity and writes a candidate that the root-owned shell wrapper can test,
install atomically, and roll back.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path


CANONICAL_TRY_FILES = "try_files $uri $uri/ /index.html;"
REPAIRABLE_TRY_FILES = {
    "try_files $uri =404;",
    "try_files $uri $uri/ =404;",
}
UNSAFE_ROOT_LOCATION_DIRECTIVES = re.compile(
    r"(?m)^[ \t]*(?:proxy_pass|fastcgi_pass|uwsgi_pass|scgi_pass|"
    r"rewrite|return|alias|root)[ \t]+"
)


class RoutingError(RuntimeError):
    pass


def mask_comments_and_strings(text: str) -> str:
    """Return a same-length view with comments/string contents blanked."""

    chars = list(text)
    quote: str | None = None
    escaped = False
    in_comment = False
    for index, char in enumerate(text):
        if in_comment:
            if char == "\n":
                in_comment = False
            else:
                chars[index] = " "
            continue
        if quote is not None:
            if escaped:
                escaped = False
                chars[index] = " "
            elif char == "\\":
                escaped = True
                chars[index] = " "
            elif char == quote:
                quote = None
            else:
                chars[index] = " "
            continue
        if char == "#":
            in_comment = True
            chars[index] = " "
        elif char in {"'", '"'}:
            quote = char
    if quote is not None:
        raise RoutingError("Nginx vhost contains an unterminated quoted string")
    return "".join(chars)


def matching_brace(masked: str, opening: int, limit: int | None = None) -> int:
    if masked[opening] != "{":
        raise RoutingError("Internal parser error: expected an opening brace")
    depth = 0
    stop = len(masked) if limit is None else limit
    for index in range(opening, stop):
        if masked[index] == "{":
            depth += 1
        elif masked[index] == "}":
            depth -= 1
            if depth == 0:
                return index
            if depth < 0:
                break
    raise RoutingError("Nginx vhost contains an unmatched brace")


def top_level_view(masked: str, start: int, end: int) -> str:
    """Keep only direct-child text inside a block, preserving offsets."""

    result = list(masked[start:end])
    depth = 0
    for relative, char in enumerate(masked[start:end]):
        if char == "{":
            depth += 1
            result[relative] = " "
        elif char == "}":
            depth -= 1
            if depth < 0:
                raise RoutingError("Nginx vhost contains an unmatched brace")
            result[relative] = " "
        elif depth > 0 and char != "\n":
            result[relative] = " "
    if depth != 0:
        raise RoutingError("Nginx vhost contains an unmatched brace")
    return "".join(result)


def directive_values(view: str, name: str) -> list[str]:
    expression = re.compile(rf"(?m)^[ \t]*{re.escape(name)}[ \t]+([^;\n]+)[ \t]*;")
    return [match.group(1).strip() for match in expression.finditer(view)]


def locate_customer_server(
    text: str,
    masked: str,
    *,
    expected_port: str,
    expected_server_name: str,
    expected_root: str,
    expected_proxy_include: str,
) -> tuple[int, int]:
    candidates: list[tuple[int, int]] = []
    for match in re.finditer(r"(?m)^[ \t]*server[ \t]*\{", masked):
        opening = masked.find("{", match.start(), match.end())
        closing = matching_brace(masked, opening)
        view = top_level_view(masked, opening + 1, closing)
        listens = directive_values(view, "listen")
        names = directive_values(view, "server_name")
        if expected_port in listens and expected_server_name in names:
            candidates.append((opening, closing))

    if len(candidates) != 1:
        raise RoutingError(
            "Expected exactly one BaoTa server block for "
            f"{expected_server_name}:{expected_port}; found {len(candidates)}"
        )

    opening, closing = candidates[0]
    view = top_level_view(masked, opening + 1, closing)
    roots = directive_values(view, "root")
    if roots != [expected_root]:
        raise RoutingError(
            "Customer server root differs from the fixed deployment contract: "
            f"expected {expected_root!r}, found {roots!r}; "
            "refusing to alter an unknown site"
        )
    includes = directive_values(view, "include")
    if includes.count(expected_proxy_include) != 1:
        raise RoutingError(
            "Customer server must load exactly one expected BaoTa proxy include: "
            f"{expected_proxy_include}"
        )
    return opening, closing


def locate_root_location(
    masked: str, server_open: int, server_close: int
) -> tuple[int, int]:
    server_body = masked[server_open + 1 : server_close]
    matches = []
    depth = 0
    cursor = 0
    for match in re.finditer(r"(?m)^[ \t]*location[ \t]+/[ \t]*\{", server_body):
        for char in server_body[cursor : match.start()]:
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
        cursor = match.start()
        if depth == 0:
            matches.append(match)
    if len(matches) != 1:
        raise RoutingError(
            "Expected exactly one plain 'location /' in the customer server; "
            f"found {len(matches)}"
        )
    absolute_start = server_open + 1 + matches[0].start()
    absolute_open = masked.find("{", absolute_start, server_open + 1 + matches[0].end())
    absolute_close = matching_brace(masked, absolute_open, server_close)
    return absolute_open, absolute_close


def build_candidate(
    text: str,
    *,
    expected_port: str,
    expected_server_name: str,
    expected_root: str,
    expected_proxy_include: str,
) -> tuple[str, bool]:
    masked = mask_comments_and_strings(text)
    server_open, server_close = locate_customer_server(
        text,
        masked,
        expected_port=expected_port,
        expected_server_name=expected_server_name,
        expected_root=expected_root,
        expected_proxy_include=expected_proxy_include,
    )
    location_open, location_close = locate_root_location(
        masked, server_open, server_close
    )
    location_view = top_level_view(masked, location_open + 1, location_close)

    nested_block = re.search(r"[{}]", masked[location_open + 1 : location_close])
    if nested_block is not None:
        raise RoutingError("The root location contains an unsupported nested block")
    if UNSAFE_ROOT_LOCATION_DIRECTIVES.search(location_view):
        raise RoutingError(
            "The root location proxies, rewrites, returns, aliases, or overrides its root; "
            "refusing to alter an unknown site"
        )

    try_files_matches = list(
        re.finditer(r"(?m)^[ \t]*try_files[ \t]+[^;\n]+[ \t]*;", location_view)
    )
    if len(try_files_matches) > 1:
        raise RoutingError("The root location contains multiple try_files directives")

    if try_files_matches:
        match = try_files_matches[0]
        current = match.group(0).strip()
        if current == CANONICAL_TRY_FILES:
            return text, False
        if current not in REPAIRABLE_TRY_FILES:
            raise RoutingError(
                "The root location contains an unknown try_files directive; "
                f"refusing to replace it: {current}"
            )
        absolute_start = location_open + 1 + match.start()
        absolute_end = location_open + 1 + match.end()
        indentation = re.match(r"[ \t]*", match.group(0)).group(0)
        replacement = f"{indentation}{CANONICAL_TRY_FILES}"
        return text[:absolute_start] + replacement + text[absolute_end:], True

    opening_line_start = text.rfind("\n", 0, location_open) + 1
    opening_indent = re.match(r"[ \t]*", text[opening_line_start:location_open]).group(
        0
    )
    insertion = f"\n{opening_indent}    {CANONICAL_TRY_FILES}"
    return text[: location_open + 1] + insertion + text[location_open + 1 :], True


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create a validated candidate with the BaoTa SPA history fallback"
    )
    parser.add_argument("--vhost-file", required=True)
    parser.add_argument("--candidate-file", required=True)
    parser.add_argument("--expected-port", default="23272")
    parser.add_argument("--expected-server-name", default="192.168.160.226")
    parser.add_argument("--expected-root", default="/www/wwwroot/192.168.160.226")
    parser.add_argument(
        "--expected-proxy-include",
        default=("/www/server/panel/vhost/nginx/proxy/192.168.160.226/*.conf"),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    vhost = Path(args.vhost_file)
    candidate = Path(args.candidate_file)
    try:
        if not vhost.is_file() or vhost.is_symlink():
            raise RoutingError(f"Vhost is not a safe regular file: {vhost}")
        if candidate.exists() or candidate.is_symlink():
            raise RoutingError(f"Candidate path already exists: {candidate}")
        text = vhost.read_text(encoding="utf-8")
        repaired, changed = build_candidate(
            text,
            expected_port=args.expected_port,
            expected_server_name=args.expected_server_name,
            expected_root=args.expected_root,
            expected_proxy_include=args.expected_proxy_include,
        )
        descriptor = os.open(
            candidate,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
        )
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as output:
            output.write(repaired)
        print("changed" if changed else "unchanged")
        return 0
    except (OSError, UnicodeError, RoutingError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
