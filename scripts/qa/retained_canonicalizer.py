#!/usr/bin/env python3
"""Deterministic retained-row canonicalization used by both W2 and W3 gates."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import subprocess
import sys
import unicodedata
from datetime import date, datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any


TYPE_TAGS = {
    "text": "S",
    "id": "S",
    "code": "S",
    "json": "J",
    "decimal": "D",
    "instant": "T",
    "boolean": "B",
    "binary": "X",
    "integer": "I",
    "uint": "I",
    "date": "A",
    "digest": "S",
    "bool": "B",
}


def frame(value_type: str, value: Any) -> str:
    nullable = value_type.endswith("?")
    logical_type = value_type.removesuffix("?")
    if value is None:
        if not nullable:
            raise ValueError(f"{value_type} does not permit NULL")
        return "N:0:"
    normalized = normalize(logical_type, value)
    tag = TYPE_TAGS[logical_type]
    return f"{tag}:{len(normalized.encode('utf-8'))}:{normalized}"


def normalize(value_type: str, value: Any) -> str:
    if value_type in {"text", "id", "code", "digest"}:
        return unicodedata.normalize("NFC", str(value))
    if value_type == "json":
        return json.dumps(
            normalize_json(value),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    if value_type == "decimal":
        decimal_value = Decimal(str(value))
        result = format(decimal_value, "f")
        if "." in result:
            result = result.rstrip("0").rstrip(".")
        return result if result not in {"", "-0"} else "0"
    if value_type == "instant":
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        utc_value = parsed.astimezone(timezone.utc)
        return utc_value.strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    if value_type in {"boolean", "bool"}:
        if not isinstance(value, bool):
            raise ValueError("boolean input must be bool")
        return "1" if value else "0"
    if value_type == "binary":
        raw = base64.b64decode(value, validate=True) if isinstance(value, str) else bytes(value)
        return raw.hex()
    if value_type in {"integer", "uint"}:
        integer = int(value)
        if value_type == "uint" and integer < 0:
            raise ValueError("uint input must be non-negative")
        return str(integer)
    if value_type == "date":
        parsed = date.fromisoformat(str(value))
        return parsed.isoformat()
    raise ValueError(f"unsupported logical type: {value_type}")


def normalize_json(value: Any) -> Any:
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)
    if isinstance(value, list):
        return [normalize_json(item) for item in value]
    if isinstance(value, dict):
        return {
            unicodedata.normalize("NFC", str(key)): normalize_json(item)
            for key, item in value.items()
        }
    return value


def row_frame(table: str, fields: list[tuple[str, Any]]) -> str:
    framed_fields = "".join(frame(value_type, value) for value_type, value in fields)
    return f"R:{len(table.encode('utf-8'))}:{table}{len(fields)}:{framed_fields}"


def snapshot_line(row: str) -> str:
    digest = hashlib.sha256(row.encode("utf-8")).hexdigest()
    return f"L:{len(row.encode('utf-8'))}:{row}64:{digest}"


def parse_column(column: str) -> tuple[str, str]:
    name, separator, value_type = column.partition(":")
    if not name or not separator or not value_type:
        raise ValueError(f"invalid registry column: {column}")
    return name, value_type


def load_registry(registry_path: Path) -> dict[str, dict[str, Any]]:
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    tables: dict[str, dict[str, Any]] = {}
    for table in registry["tables"]:
        name = table["table"]
        if name in tables:
            raise ValueError(f"duplicate registry table: {name}")
        columns = [parse_column(column) for column in table["columns"]]
        column_names = [column[0] for column in columns]
        if len(column_names) != len(set(column_names)):
            raise ValueError(f"duplicate registry column: {name}")
        if not table["pk"] or any(key not in column_names for key in table["pk"]):
            raise ValueError(f"invalid registry primary key: {name}")
        tables[name] = {"pk": table["pk"], "columns": columns}
    return tables


def canonical_rows(
    registry: dict[str, dict[str, Any]], rows: list[dict[str, Any]]
) -> list[dict[str, str]]:
    canonical: list[tuple[bytes, dict[str, str]]] = []
    for source in rows:
        table = source["table"]
        if table not in registry:
            raise ValueError(f"fixture table absent from registry: {table}")
        definition = registry[table]
        values = source["values"]
        expected_names = [name for name, _ in definition["columns"]]
        if list(values) != expected_names:
            raise ValueError(f"fixture column order differs from registry: {table}")
        row = row_frame(
            table,
            [(value_type, values[name]) for name, value_type in definition["columns"]],
        )
        pk_frames = "".join(
            frame(dict(definition["columns"])[name], values[name])
            for name in definition["pk"]
        ).encode("utf-8")
        canonical.append((table.encode("utf-8") + b"\0" + pk_frames, {
            "table": table,
            "pk": [normalize(dict(definition["columns"])[name].removesuffix("?"), values[name])
                   for name in definition["pk"]],
            "row": row,
            "rowHash": hashlib.sha256(row.encode("utf-8")).hexdigest(),
            "snapshotLine": snapshot_line(row),
        }))
    canonical.sort(key=lambda item: item[0])
    return [item[1] for item in canonical]


def verify_fixture(fixture_path: Path, registry_path: Path) -> None:
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    registry = load_registry(registry_path)
    for vector in fixture["fieldVectors"]:
        input_value = vector.get("inputBase64", vector.get("input"))
        actual = frame(vector["type"], input_value)
        if actual != vector["expected"]:
            raise AssertionError(f"{vector['name']}: {actual!r} != {vector['expected']!r}")

    actual_rows = canonical_rows(registry, fixture["sourceRows"])
    if actual_rows != fixture["orderedRows"]:
        raise AssertionError("canonical rows differ from review-owned golden")
    lines = [row["snapshotLine"] for row in actual_rows]
    final_hash = hashlib.sha256("".join(lines).encode("utf-8")).hexdigest()
    if final_hash != fixture["finalHash"]:
        raise AssertionError(f"final hash mismatch: {final_hash}")


def self_test(fixture_path: Path, registry_path: Path) -> None:
    for locale_name, timezone_name in (
        ("C", "UTC"),
        ("zh_CN.UTF-8", "Asia/Shanghai"),
    ):
        environment = os.environ.copy()
        environment.update({"LC_ALL": locale_name, "TZ": timezone_name})
        subprocess.run(
            [
                sys.executable,
                str(Path(__file__).resolve()),
                "--fixture",
                str(fixture_path),
                "--registry",
                str(registry_path),
            ],
            check=True,
            env=environment,
            stdout=subprocess.DEVNULL,
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--registry", type=Path, required=True)
    arguments = parser.parse_args()
    if arguments.self_test:
        self_test(arguments.fixture, arguments.registry)
        sys.stdout.write(json.dumps({
            "marker": "W3_RETAINED_CANONICALIZER_SELF_TEST=PASS",
            "locales": 2,
        }, separators=(",", ":")) + "\n")
    else:
        verify_fixture(arguments.fixture, arguments.registry)
        sys.stdout.write(json.dumps({
            "marker": "W3_RETAINED_CANONICALIZER=PASS",
        }, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
