#!/usr/bin/env python3
"""Compare the structured W2 actual export with the review-owned contract fixture."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--actual", type=Path, required=True)
    parser.add_argument("--expected", type=Path, required=True)
    arguments = parser.parse_args()
    actual = json.loads(arguments.actual.read_text(encoding="utf-8"))
    expected = json.loads(arguments.expected.read_text(encoding="utf-8"))

    for surface_name, expected_digest in expected["structuredSurfaceSha256"].items():
        surface = json.dumps(
            actual[surface_name],
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        actual_digest = hashlib.sha256(surface).hexdigest()
        if actual_digest != expected_digest:
            raise AssertionError(
                f"W2 {surface_name} structured surface differs: "
                f"{actual_digest} != {expected_digest}"
            )

    expected_files = {f"{name}.java" for name in expected["javaSurfaces"]}
    if set(actual["java"]) != expected_files:
        raise AssertionError("W2 Java surface set differs from review-owned fixture")
    for file_name, surface in actual["java"].items():
        if not isinstance(surface["records"], list) or not isinstance(
                surface["publicMethods"], list):
            raise AssertionError(f"{file_name} is not a structured Java export")

    if actual["h2"]["policyTables"] != sorted(expected["h2Surfaces"]):
        raise AssertionError("W2 H2 policy table set differs")
    if actual["h2"]["effectiveCheck"] != expected["requiredPolicyVersionContract"][
            "effectivePeriod"]:
        raise AssertionError("W2 V4 effective-period CHECK differs")
    if actual["h2"]["auditEventHasRequestId"] is not True:
        raise AssertionError("final V6 audit_event.request_id is absent")

    required_mapper_ids = set(actual["mapper"]["ids"])
    if not required_mapper_ids or not actual["mapper"]["columns"]:
        raise AssertionError("W2 Mapper export is incomplete")
    if not actual["openapi"]["policyPaths"] or not actual["openapi"]["policySchemas"]:
        raise AssertionError("W2 OpenAPI export is incomplete")

    serialized = json.dumps(actual, ensure_ascii=False, sort_keys=True)
    for token in expected["forbiddenTokens"]:
        if token in serialized:
            raise AssertionError(f"forbidden W3 token entered W2 public export: {token}")
    sys.stdout.write(json.dumps({
        "gate": "w2-public-contract",
        "status": "PASS",
        "javaFiles": len(actual["java"]),
        "mapperIds": len(required_mapper_ids),
    }, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
