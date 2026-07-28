#!/usr/bin/env python3

from __future__ import annotations

import copy
import hashlib
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch


QA_ROOT = Path(__file__).resolve().parent
if str(QA_ROOT) not in sys.path:
    sys.path.insert(0, str(QA_ROOT))

import capture_wave3_mysql_semantics as capture  # noqa: E402
import produce_wave3_semantic_gates as producer  # noqa: E402


def framed_w2_row(table: str, primary_key: str) -> dict[str, object]:
    framed = (
        f"R:{len(table.encode('utf-8'))}:{table}"
        f"1:S:{len(primary_key.encode('utf-8'))}:{primary_key}"
    )
    row_hash = hashlib.sha256(framed.encode("utf-8")).hexdigest()
    return {
        "table": table,
        "pk": [primary_key],
        "row": framed,
        "rowHash": row_hash,
        "snapshotLine": (
            f"L:{len(framed.encode('utf-8'))}:{framed}64:{row_hash}"
        ),
    }


def w2_snapshot(
    phase: str,
    keys: list[tuple[str, str]],
) -> dict[str, object]:
    rows = [framed_w2_row(table, key) for table, key in keys]
    counts = {table: 0 for table in producer.W2_FIXED_ROW_COUNTS}
    for table, _ in keys:
        counts[table] += 1
    return {
        "schemaVersion": 1,
        "nodeType": "w2-retained-snapshot",
        "phase": phase,
        "database": "shenzhou_hr_test",
        "registryPath": producer.W2_REGISTRY.relative_to(
            producer.REPOSITORY_ROOT
        ).as_posix(),
        "registrySha256": producer.W2_REGISTRY_SHA256,
        "rowCounts": counts,
        "rowCount": len(rows),
        "rows": rows,
        "finalHash": hashlib.sha256(
            "".join(str(row["snapshotLine"]) for row in rows).encode("utf-8")
        ).hexdigest(),
    }


def fixed_w2_snapshot(phase: str) -> dict[str, object]:
    fixture = producer.load_w2_fixed_row_oracle()
    rows = copy.deepcopy(fixture["canonicalRows"])
    return {
        "schemaVersion": 1,
        "nodeType": "w2-retained-snapshot",
        "phase": phase,
        "database": "shenzhou_hr_test",
        "registryPath": producer.W2_REGISTRY.relative_to(
            producer.REPOSITORY_ROOT
        ).as_posix(),
        "registrySha256": producer.W2_REGISTRY_SHA256,
        "rowCounts": copy.deepcopy(producer.W2_FIXED_ROW_COUNTS),
        "rowCount": 7,
        "rows": rows,
        "finalHash": fixture["finalHash"],
    }


class Wave3SemanticGateProducerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.openapi = producer.load_unique_yaml(
            producer.OPENAPI.read_text(encoding="utf-8")
        )
        cls.controllers = producer.controller_operation_export()
        cls.operations = producer.openapi_operation_export(cls.openapi)

    def test_plan_has_exact_first_seven_roles_and_safe_database_boundary(
        self,
    ) -> None:
        plan = producer.plan_document()
        self.assertEqual(plan["leafCount"], 7)
        self.assertEqual(tuple(plan["leafIds"]), producer.SEMANTIC_IDS)

        self.assertEqual(tuple(plan["requiredRoles"]), producer.SEMANTIC_IDS)
        self.assertEqual(
            plan["requiredRoles"]["W3-VER-W2-RETAINED"],
            [
                "retained-verification-log",
                "w2-registry",
                "v4-schema-oracle",
                "before-snapshot",
                "after-snapshot",
                "subset-diff",
                "w1-w2-regression-log",
            ],
        )
        self.assertFalse(plan["databaseActions"]["startsOrStopsMysql"])
        self.assertFalse(plan["databaseActions"]["connectsToPort3306"])
        self.assertEqual(
            plan["databaseActions"]["database"], "shenzhou_hr_test"
        )
        mysql_harness = producer.MYSQL_WAVE3.read_text(encoding="utf-8")
        self.assertIn(
            'v7_migrations=("${MIGRATION_DIR}"/V7__*.sql)',
            mysql_harness,
        )
        self.assertIn(
            '"${#v7_migrations[@]}" -eq 1',
            mysql_harness,
        )
        with tempfile.TemporaryDirectory() as temporary:
            migration_dir = Path(temporary)
            expected = (
                migration_dir
                / "V7__attendance_setup_and_base_policies.sql"
            )
            expected.write_text("-- reviewed V7\n", encoding="utf-8")
            self.assertEqual(
                producer.validate_unique_v7_migration(migration_dir),
                expected,
            )
            (migration_dir / "V7__surviving_extra.sql").write_text(
                "-- unauthorized second V7\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(
                producer.ProducerError, "exactly the reviewed V7"
            ):
                producer.validate_unique_v7_migration(migration_dir)

    def test_semantic_plan_loader_passes_bound_context(self) -> None:
        verifier = object()
        run_root = (
            producer.REPOSITORY_ROOT
            / "docs/verification/wave3/runs/test-run"
        )
        context = {"runId": "test-run"}
        leaves = [{"evidenceId": producer.SEMANTIC_IDS[0]}]
        with patch.object(
            producer,
            "load_plan_for_run",
            return_value=(run_root / "orchestration/leaf-plan.json", leaves),
        ) as loader:
            plan = producer.load_semantic_plan(verifier, run_root, context)
        loader.assert_called_once_with(verifier, run_root, context)
        self.assertEqual(plan, {"leaves": leaves})

    def test_public_contract_matches_nested_fixed_fixture(self) -> None:
        expected = producer.load_json(
            producer.PUBLIC_EXPECTED, "W2 public oracle"
        )
        actual = copy.deepcopy(expected["contract"])
        producer.require_public_contract_match(actual, expected)

        actual["boundary"]["violations"].append("mutated")
        with self.assertRaisesRegex(
            producer.ProducerError,
            "differs from the fixed oracle contract",
        ):
            producer.require_public_contract_match(actual, expected)

        with self.assertRaisesRegex(
            producer.ProducerError,
            "contract is missing or invalid",
        ):
            producer.require_public_contract_match(actual, {})

    def test_retained_canonical_golden_matches_current_schema(self) -> None:
        golden = producer.load_json(
            producer.CANONICAL_GOLDEN, "retained canonical golden"
        )
        validated = producer.validate_retained_canonical_golden(
            golden, producer.W3_REGISTRY
        )
        self.assertEqual(
            set(validated["framing"]),
            {"field", "row", "snapshotLine", "finalHashInput"},
        )

    def test_mysql_database_identity_tsv_is_exact(self) -> None:
        identity = (
            "mysql8410:533ca0bc-89c8-11f1-a08b-1ee9344ad44b:"
            "shenzhou_hr_test"
        )
        self.assertEqual(
            producer.expected_mysql_db_identity_tsv(identity),
            (
                f"db_identity\t{identity}\n"
                "server_uuid\t533ca0bc-89c8-11f1-a08b-1ee9344ad44b\n"
                "database\tshenzhou_hr_test\n"
            ).encode("utf-8"),
        )
        with self.assertRaisesRegex(
            producer.ProducerError, "database identity is invalid"
        ):
            producer.expected_mysql_db_identity_tsv(
                "mysql8410:not-a-uuid:shenzhou_hr_test"
            )

    def test_openapi_controller_and_request_schema_closure(self) -> None:
        comparison = producer.compare_operation_closure(
            self.controllers, self.operations, self.openapi
        )
        self.assertEqual(len(self.controllers), 54)
        self.assertEqual(len(self.operations), 54)
        self.assertEqual(comparison["differences"], [])
        self.assertEqual(comparison["verdict"], "PASS")
        self.assertTrue(producer.record_schema_report(self.openapi))
        reports = producer.instance_validation_report(
            self.openapi, self.operations
        )
        self.assertTrue(reports)
        self.assertTrue(all(value["verdict"] == "PASS" for value in reports))

    def test_openapi_mutants_fail_closed(self) -> None:
        missing_route = copy.deepcopy(self.operations)
        missing_route.pop()
        self.assertEqual(
            producer.compare_operation_closure(
                self.controllers, missing_route, self.openapi
            )["verdict"],
            "FAIL",
        )

        wrong_request = copy.deepcopy(self.operations)
        target = next(
            value for value in wrong_request
            if value["requestSchema"] is not None
        )
        target["requestSchema"] = {
            "$ref": "#/components/schemas/ApiError"
        }
        self.assertEqual(
            producer.compare_operation_closure(
                self.controllers, wrong_request, self.openapi
            )["verdict"],
            "FAIL",
        )

        nullable_required_value = copy.deepcopy(self.openapi)
        nullable_required_value["components"]["schemas"][
            "AttendancePolicyParameter"
        ]["properties"]["value"] = {}
        with self.assertRaisesRegex(
            producer.ProducerError, "request DTO/schema closure failed"
        ):
            producer.record_schema_report(nullable_required_value)

        optional_body = copy.deepcopy(self.operations)
        next(
            value for value in optional_body
            if value["requestSchema"] is not None
        )["requestRequired"] = False
        self.assertEqual(
            producer.compare_operation_closure(
                self.controllers, optional_body, self.openapi
            )["verdict"],
            "FAIL",
        )

        wrong_parameter_type = copy.deepcopy(self.operations)
        next(
            parameter
            for operation in wrong_parameter_type
            for parameter in operation["parameters"]
            if parameter["in"] == "query"
        )["schema"] = {"type": "boolean"}
        self.assertEqual(
            producer.compare_operation_closure(
                self.controllers, wrong_parameter_type, self.openapi
            )["verdict"],
            "FAIL",
        )

        optional_header = copy.deepcopy(self.operations)
        next(
            parameter
            for operation in optional_header
            for parameter in operation["parameters"]
            if parameter["in"] == "header"
            and parameter["name"] == "Idempotency-Key"
        )["required"] = False
        self.assertEqual(
            producer.compare_operation_closure(
                self.controllers, optional_header, self.openapi
            )["verdict"],
            "FAIL",
        )

        wrong_capability = copy.deepcopy(self.operations)
        next(
            value for value in wrong_capability
            if value["method"] == "GET"
        )["capability"] = "ATTENDANCE_SETUP:MANAGE_POLICY"
        self.assertEqual(
            producer.compare_operation_closure(
                self.controllers, wrong_capability, self.openapi
            )["verdict"],
            "FAIL",
        )

        open_response = copy.deepcopy(self.openapi)
        open_response["components"]["schemas"][
            "AttendanceLocationView"
        ]["additionalProperties"] = True
        self.assertEqual(
            producer.compare_operation_closure(
                self.controllers,
                producer.openapi_operation_export(open_response),
                open_response,
            )["verdict"],
            "FAIL",
        )

        controllers_without_lifecycle_binding = copy.deepcopy(
            self.controllers
        )
        operations_without_lifecycle_binding = copy.deepcopy(self.operations)
        for collection in (
            controllers_without_lifecycle_binding,
            operations_without_lifecycle_binding,
        ):
            for operation in collection:
                if (
                    "{templateId}" in operation["path"]
                    and "/policy-lifecycle/" in operation["path"]
                ):
                    operation["parameters"] = [
                        value
                        for value in operation["parameters"]
                        if not (
                            value["in"] == "query"
                            and value["name"] == "legalEntityId"
                        )
                        and not (
                            value["in"] == "header"
                            and value["name"] == "Idempotency-Key"
                        )
                    ]
        self.assertEqual(
            producer.compare_operation_closure(
                controllers_without_lifecycle_binding,
                operations_without_lifecycle_binding,
                self.openapi,
            )["verdict"],
            "FAIL",
        )

    def test_unknown_request_field_is_rejected(self) -> None:
        schema = self.openapi["components"]["schemas"][
            "AttendanceLocationRequest"
        ]
        legal = producer.generate_schema_instance(schema, self.openapi)
        self.assertEqual(
            producer.validate_schema_instance(
                legal, schema, self.openapi
            ),
            [],
        )
        illegal = dict(legal)
        illegal["unexpected"] = True
        self.assertTrue(
            producer.validate_schema_instance(
                illegal, schema, self.openapi
            )
        )

    def test_review_owned_api_oracle_rejects_linked_mutants(self) -> None:
        self.assertEqual(
            producer.compare_review_owned_api_oracle(
                self.openapi, self.controllers
            )["verdict"],
            "PASS",
        )

        linked_route_document = copy.deepcopy(self.openapi)
        linked_route_controllers = copy.deepcopy(self.controllers)
        old_path = "/attendance-setup/locations"
        new_path = "/attendance-setup/renamed-locations"
        linked_route_document["paths"][new_path] = (
            linked_route_document["paths"].pop(old_path)
        )
        for operation in linked_route_controllers:
            if operation["path"] == old_path:
                operation["path"] = new_path
        self.assertEqual(
            producer.compare_review_owned_api_oracle(
                linked_route_document, linked_route_controllers
            )["verdict"],
            "FAIL",
        )

        linked_request_document = copy.deepcopy(self.openapi)
        linked_request_controllers = copy.deepcopy(self.controllers)
        linked_request_document["paths"][old_path]["post"]["requestBody"][
            "content"
        ]["application/json"]["schema"] = {
            "$ref": "#/components/schemas/AttendanceGroupRequest"
        }
        next(
            operation
            for operation in linked_request_controllers
            if operation["method"] == "POST" and operation["path"] == old_path
        )["requestBody"]["javaType"] = "GroupRequest"
        self.assertEqual(
            producer.compare_review_owned_api_oracle(
                linked_request_document, linked_request_controllers
            )["verdict"],
            "FAIL",
        )

        linked_response_document = copy.deepcopy(self.openapi)
        linked_response_controllers = copy.deepcopy(self.controllers)
        linked_response_document["paths"][old_path]["post"]["responses"][
            "201"
        ]["content"]["application/json"]["schema"] = {
            "$ref": "#/components/schemas/AttendanceGroupView"
        }
        next(
            operation
            for operation in linked_response_controllers
            if operation["method"] == "POST" and operation["path"] == old_path
        )["returnType"] = "ResponseEntity<GroupView>"
        self.assertEqual(
            producer.compare_review_owned_api_oracle(
                linked_response_document, linked_response_controllers
            )["verdict"],
            "FAIL",
        )

        no_lifecycle_binding_document = copy.deepcopy(self.openapi)
        no_lifecycle_binding_controllers = copy.deepcopy(self.controllers)
        for operation in no_lifecycle_binding_controllers:
            if (
                "/policy-lifecycle/" in operation["path"]
                and "{templateId}" in operation["path"]
            ):
                operation["parameters"] = [
                    parameter
                    for parameter in operation["parameters"]
                    if parameter["name"] != "legalEntityId"
                ]
        for path, item in no_lifecycle_binding_document["paths"].items():
            if "/policy-lifecycle/" not in path or "{templateId}" not in path:
                continue
            for method, operation in item.items():
                if method not in producer.HTTP_METHODS:
                    continue
                operation["parameters"] = [
                    parameter
                    for parameter in operation.get("parameters", [])
                    if producer.resolve_parameter(
                        no_lifecycle_binding_document, parameter
                    ).get("name")
                    != "legalEntityId"
                ]
        self.assertEqual(
            producer.compare_review_owned_api_oracle(
                no_lifecycle_binding_document,
                no_lifecycle_binding_controllers,
            )["verdict"],
            "FAIL",
        )

        empty_correlation = copy.deepcopy(self.openapi)
        empty_correlation["paths"][old_path]["get"]["responses"]["200"][
            "headers"
        ]["X-Correlation-ID"] = {}
        self.assertEqual(
            producer.compare_review_owned_api_oracle(
                empty_correlation, self.controllers
            )["verdict"],
            "FAIL",
        )

        arbitrary_status = copy.deepcopy(self.openapi)
        arbitrary_status["paths"][old_path]["get"]["responses"]["418"] = {
            "$ref": "#/components/responses/AuthenticationRequired"
        }
        self.assertEqual(
            producer.compare_review_owned_api_oracle(
                arbitrary_status, self.controllers
            )["verdict"],
            "FAIL",
        )

        linked_dto_document = copy.deepcopy(self.openapi)
        location_request = linked_dto_document["components"]["schemas"][
            "AttendanceLocationRequest"
        ]
        location_request["properties"].pop("reason")
        location_request["required"].remove("reason")
        linked_records = producer.review_java_record_export()
        record = next(
            item
            for item in linked_records
            if item["record"] == "AttendanceGroupDtos.LocationRequest"
        )
        record["fields"] = [
            field
            for field in record["fields"]
            if not field.startswith("reason|")
        ]
        with patch.object(
            producer,
            "review_java_record_export",
            return_value=linked_records,
        ):
            self.assertEqual(
                producer.compare_review_owned_api_oracle(
                    linked_dto_document, self.controllers
                )["verdict"],
                "FAIL",
            )

    def test_retained_subset_requires_nonempty_unchanged_rows(self) -> None:
        context = {
            "runId": "w3-semantic-unit",
            "sourceTreeHash": "a" * 64,
            "databaseIdentity": (
                "mysql8410:533ca0bc-89c8-41f1-a08b-1ee9344ad44b:"
                "shenzhou_hr_test"
            ),
        }
        before = fixed_w2_snapshot("v6-before")
        after = fixed_w2_snapshot("latest-after")
        report = producer.subset_diff(context, before, after)
        self.assertEqual(report["verdict"], "PASS")
        self.assertEqual(report["unchangedRows"], 7)

        changed = copy.deepcopy(after)
        changed_row = changed["rows"][0]
        changed_row["row"] = str(changed_row["row"]) + "changed"
        changed_row["rowHash"] = hashlib.sha256(
            str(changed_row["row"]).encode("utf-8")
        ).hexdigest()
        changed_row["snapshotLine"] = (
            f"L:{len(str(changed_row['row']).encode('utf-8'))}:"
            f"{changed_row['row']}64:{changed_row['rowHash']}"
        )
        changed["finalHash"] = hashlib.sha256(
            "".join(
                str(row["snapshotLine"]) for row in changed["rows"]
            ).encode("utf-8")
        ).hexdigest()
        with self.assertRaisesRegex(
            producer.ProducerError, "exact fixed"
        ):
            producer.subset_diff(context, before, changed)

        extra = copy.deepcopy(after)
        extra["rows"].append(framed_w2_row("policy_version", "later"))
        extra["rowCounts"]["policy_version"] = 3
        extra["rowCount"] = 8
        extra["finalHash"] = hashlib.sha256(
            "".join(
                str(row["snapshotLine"]) for row in extra["rows"]
            ).encode("utf-8")
        ).hexdigest()
        with self.assertRaisesRegex(
            producer.ProducerError, "exact fixed"
        ):
            producer.subset_diff(context, before, extra)

        with self.assertRaisesRegex(
            producer.ProducerError, "fixed six-table/seven-row fixture"
        ):
            all_policy_versions = w2_snapshot(
                "v6-before",
                [
                    ("policy_version", f"all-policy-{index}")
                    for index in range(7)
                ],
            )
            producer.subset_diff(
                context,
                all_policy_versions,
                w2_snapshot(
                    "latest-after",
                    [
                        ("policy_version", f"all-policy-{index}")
                        for index in range(7)
                    ],
                ),
            )

    def test_structured_v4_flyway_and_snapshot_recomputation(self) -> None:
        expected_v4 = capture.expected_v4_schema()
        v4_capture = {
            **copy.deepcopy(expected_v4),
            "database": "shenzhou_hr_test",
            "actualTables": copy.deepcopy(expected_v4["tables"]),
            "differences": [],
            "verdict": "PASS",
        }
        producer.validate_v4_schema_capture(v4_capture)
        false_v4_pass = copy.deepcopy(v4_capture)
        false_v4_pass["actualTables"]["policy_version"]["columns"][0][
            "columnType"
        ] = "VARCHAR(1)"
        with self.assertRaisesRegex(
            producer.ProducerError, "structured recomputation"
        ):
            producer.validate_v4_schema_capture(false_v4_pass)

        flyway_rows = []
        for rank, path in enumerate(
            sorted(
                (
                    producer.REPOSITORY_ROOT
                    / "backend/src/main/resources/db/migration"
                ).glob("V[1-6]__*.sql")
            ),
            start=1,
        ):
            version, description = path.stem.split("__", 1)
            flyway_rows.append(
                {
                    "installedRank": str(rank),
                    "version": version.removeprefix("V"),
                    "description": description.replace("_", " "),
                    "type": "SQL",
                    "script": path.name,
                    "checksum": str(rank),
                    "success": "1",
                }
            )
        flyway = {
            "schemaVersion": 1,
            "nodeType": "flyway-history-snapshot",
            "phase": "v6-before",
            "database": "shenzhou_hr_test",
            "rows": flyway_rows,
            "rowCount": 6,
            "snapshotSha256": hashlib.sha256(
                producer.canonical_json_bytes(flyway_rows)
            ).hexdigest(),
        }
        producer.validate_flyway_capture(flyway, "v6-before")
        wrong_flyway = copy.deepcopy(flyway)
        wrong_flyway["rowCount"] = 2
        with self.assertRaisesRegex(
            producer.ProducerError, "semantic recomputation"
        ):
            producer.validate_flyway_capture(
                wrong_flyway, "v6-before"
            )
        wrong_script = copy.deepcopy(flyway)
        wrong_script["rows"][0]["script"] = "V1__linked-replacement.sql"
        wrong_script["snapshotSha256"] = hashlib.sha256(
            producer.canonical_json_bytes(wrong_script["rows"])
        ).hexdigest()
        with self.assertRaisesRegex(
            producer.ProducerError, "semantic recomputation"
        ):
            producer.validate_flyway_capture(
                wrong_script, "v6-before"
            )
        extra_key = copy.deepcopy(flyway)
        extra_key["claimedPass"] = True
        with self.assertRaisesRegex(
            producer.ProducerError, "key closure"
        ):
            producer.validate_flyway_capture(extra_key, "v6-before")

        snapshot = fixed_w2_snapshot("v6-before")
        producer.validate_w2_snapshot(
            snapshot, "v6-before", require_fixed_fixture=True
        )
        false_final_hash = copy.deepcopy(snapshot)
        false_final_hash["finalHash"] = "0" * 64
        with self.assertRaisesRegex(
            producer.ProducerError, "finalHash recomputation"
        ):
            producer.validate_w2_snapshot(
                false_final_hash,
                "v6-before",
                require_fixed_fixture=True,
            )

    def test_seed_capture_is_recomputed_from_fixed_fixture(self) -> None:
        fixture = json.loads(
            producer.SEED_ORACLE.read_text(encoding="utf-8")
        )
        rows = producer.expected_seed_export_rows(fixture)
        export = {
            "schemaVersion": 1,
            "nodeType": "target7-seed-database-export",
            "database": "shenzhou_hr_test",
            "fixtureSha256": producer.SEED_ORACLE_SHA256,
            "rows": copy.deepcopy(rows),
            "rowCount": 3,
            "verdict": "PASS",
        }
        digest = {
            "schemaVersion": 1,
            "nodeType": "target7-seed-digest-recomputation",
            "database": "shenzhou_hr_test",
            "algorithm": (
                "SHA-256(RFC8785-compatible canonical JSON UTF-8)"
            ),
            "rows": [
                {
                    "policyKind": row["policyKind"],
                    "canonicalSnapshotUtf8Bytes": len(
                        row["canonicalSnapshot"].encode("utf-8")
                    ),
                    "expectedDigest": row["snapshotDigest"],
                    "recomputedDigest": hashlib.sha256(
                        row["canonicalSnapshot"].encode("utf-8")
                    ).hexdigest(),
                    "match": True,
                }
                for row in rows
            ],
            "verdict": "PASS",
        }
        extras = {
            "schemaVersion": 1,
            "nodeType": "target7-seed-no-extras-query",
            "database": "shenzhou_hr_test",
            "counts": {
                "templates": 3,
                "scopes": 3,
                "versions": 3,
                "publishedLifecycleEvents": 3,
                "bindingFamilies": 0,
                "bindingRevisions": 0,
            },
            "scopedVersionStatusColumnCount": 0,
            "verdict": "PASS",
        }
        producer.validate_seed_captures(export, digest, extras, fixture)

        false_digest = copy.deepcopy(digest)
        false_digest["rows"][0]["recomputedDigest"] = "0" * 64
        with self.assertRaisesRegex(
            producer.ProducerError, "digest structured recomputation"
        ):
            producer.validate_seed_captures(
                export, false_digest, extras, fixture
            )
        false_row_count = copy.deepcopy(export)
        false_row_count["rowCount"] = 4
        with self.assertRaisesRegex(
            producer.ProducerError, "fixed-fixture semantics"
        ):
            producer.validate_seed_captures(
                false_row_count, digest, extras, fixture
            )
        false_extras = copy.deepcopy(extras)
        false_extras["counts"]["templates"] = 4
        with self.assertRaisesRegex(
            producer.ProducerError, "no-extras structured"
        ):
            producer.validate_seed_captures(
                export, digest, false_extras, fixture
            )

    def test_transcript_markers_require_exact_full_lines(self) -> None:
        specifications = {
            "W3_EXACT=PASS": (
                1,
                r"\[trusted\] W3_EXACT=PASS value=[0-9]+",
            )
        }
        producer.require_transcript_markers(
            "[trusted] W3_EXACT=PASS value=1\n",
            specifications,
            "unit transcript",
        )
        with self.assertRaisesRegex(
            producer.ProducerError, "marker cardinality differs"
        ):
            producer.require_transcript_markers(
                "prefix W3_EXACT=PASS value=1 suffix\n",
                specifications,
                "unit transcript",
            )
        with self.assertRaisesRegex(
            producer.ProducerError, "marker cardinality differs"
        ):
            producer.require_transcript_markers(
                (
                    "[trusted] W3_EXACT=PASS value=1\n"
                    "[trusted] W3_EXACT=PASS value=2\n"
                ),
                specifications,
                "unit transcript",
            )

    def test_raw_role_publication_is_per_leaf_atomic_and_rolls_back(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            run_root = root / "run"
            stage_root = root / "stage"
            run_root.mkdir()
            roles: dict[str, dict[str, str]] = {}
            for index, evidence_id in enumerate(producer.SEMANTIC_IDS):
                relative = f"leaves/{index}/raw/primary.txt"
                roles[evidence_id] = {"primary": relative}
                staged = stage_root / relative
                staged.parent.mkdir(parents=True, exist_ok=True)
                staged.write_text(evidence_id, encoding="utf-8")

            published = producer.publish_raw_directories(
                run_root, stage_root, roles
            )
            self.assertEqual(len(published), 7)
            self.assertTrue(
                all(
                    (run_root / f"leaves/{index}/raw/primary.txt").is_file()
                    for index in range(7)
                )
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            run_root = root / "run"
            stage_root = root / "stage"
            roles = {}
            for index, evidence_id in enumerate(producer.SEMANTIC_IDS):
                relative = f"leaves/{index}/raw/primary.txt"
                roles[evidence_id] = {"primary": relative}
                staged = stage_root / relative
                staged.parent.mkdir(parents=True, exist_ok=True)
                staged.write_text(evidence_id, encoding="utf-8")
            collision = run_root / "leaves/6/raw"
            collision.mkdir(parents=True)
            (collision / "sentinel").write_text("owned", encoding="utf-8")

            with self.assertRaisesRegex(
                producer.ProducerError, "refusing to overwrite"
            ):
                producer.publish_raw_directories(
                    run_root, stage_root, roles
                )
            self.assertTrue((collision / "sentinel").is_file())
            self.assertTrue(
                all(
                    not (run_root / f"leaves/{index}/raw").exists()
                    for index in range(6)
                )
            )

    def test_raw_publication_rejects_symlink_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            run_root = root / "run"
            stage_root = root / "stage"
            outside = root / "outside"
            run_root.mkdir()
            outside.mkdir()
            (run_root / "leaves").symlink_to(outside, target_is_directory=True)
            roles = {}
            for index, evidence_id in enumerate(producer.SEMANTIC_IDS):
                relative = f"leaves/{index}/raw/primary.txt"
                roles[evidence_id] = {"primary": relative}
                staged = stage_root / relative
                staged.parent.mkdir(parents=True, exist_ok=True)
                staged.write_text(evidence_id, encoding="utf-8")
            with self.assertRaisesRegex(
                producer.ProducerError, "symbolic/non-directory"
            ):
                producer.publish_raw_directories(
                    run_root, stage_root, roles
                )
            self.assertEqual(list(outside.iterdir()), [])

    def test_staged_raw_directory_rejects_all_non_role_entries(self) -> None:
        for mutation in ("extra-directory", "dangling-role-symlink"):
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary).resolve()
                    run_root = root / "run"
                    stage_root = root / "stage"
                    run_root.mkdir()
                    roles: dict[str, dict[str, str]] = {}
                    for index, evidence_id in enumerate(
                        producer.SEMANTIC_IDS
                    ):
                        relative = f"leaves/{index}/raw/primary.txt"
                        roles[evidence_id] = {"primary": relative}
                        staged = stage_root / relative
                        staged.parent.mkdir(parents=True, exist_ok=True)
                        staged.write_text(evidence_id, encoding="utf-8")
                    first_raw = stage_root / "leaves/0/raw"
                    if mutation == "extra-directory":
                        (first_raw / "unexpected").mkdir()
                    else:
                        (first_raw / "primary.txt").unlink()
                        (first_raw / "primary.txt").symlink_to(
                            first_raw / "missing"
                        )
                    with self.assertRaisesRegex(
                        producer.ProducerError,
                        "entry closure|non-regular",
                    ):
                        producer.publish_raw_directories(
                            run_root, stage_root, roles
                        )
                    self.assertFalse((run_root / "leaves/0/raw").exists())

    def test_rollback_preserves_replaced_published_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            run_root = root / "run"
            stage_root = root / "stage"
            run_root.mkdir()
            roles: dict[str, dict[str, str]] = {}
            for index, evidence_id in enumerate(producer.SEMANTIC_IDS):
                relative = f"leaves/{index}/raw/primary.txt"
                roles[evidence_id] = {"primary": relative}
                staged = stage_root / relative
                staged.parent.mkdir(parents=True, exist_ok=True)
                staged.write_text(evidence_id, encoding="utf-8")
            real_rename = producer.atomic_rename_exclusive_at
            calls = 0
            moved_original = root / "moved-original"
            replacement = run_root / "leaves/0/raw"

            def replace_then_fail(*args: object) -> None:
                nonlocal calls
                calls += 1
                if calls == 2:
                    replacement.rename(moved_original)
                    replacement.mkdir()
                    (replacement / "replacement-sentinel").write_text(
                        "preserve", encoding="utf-8"
                    )
                    raise producer.ProducerError("injected late failure")
                real_rename(*args)

            with patch.object(
                producer,
                "atomic_rename_exclusive_at",
                side_effect=replace_then_fail,
            ):
                with self.assertRaisesRegex(
                    producer.ProducerError,
                    "rollback preserved changed replacement",
                ):
                    producer.publish_raw_directories(
                        run_root, stage_root, roles
                    )
            self.assertEqual(
                (replacement / "replacement-sentinel").read_text(
                    encoding="utf-8"
                ),
                "preserve",
            )
            self.assertTrue((moved_original / "primary.txt").is_file())

    def test_publication_rejects_parent_swap_to_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            run_root = root / "run"
            stage_root = root / "stage"
            outside = root / "outside"
            run_root.mkdir()
            outside.mkdir()
            roles: dict[str, dict[str, str]] = {}
            for index, evidence_id in enumerate(producer.SEMANTIC_IDS):
                relative = f"leaves/{index}/raw/primary.txt"
                roles[evidence_id] = {"primary": relative}
                staged = stage_root / relative
                staged.parent.mkdir(parents=True, exist_ok=True)
                staged.write_text(evidence_id, encoding="utf-8")
            real_rename = producer.atomic_rename_exclusive_at
            moved_parent = root / "moved-parent"
            target_parent = run_root / "leaves/0"
            swapped = False

            def swap_parent(*args: object) -> None:
                nonlocal swapped
                if not swapped:
                    swapped = True
                    target_parent.rename(moved_parent)
                    target_parent.symlink_to(
                        outside, target_is_directory=True
                    )
                real_rename(*args)

            with patch.object(
                producer,
                "atomic_rename_exclusive_at",
                side_effect=swap_parent,
            ):
                with self.assertRaisesRegex(
                    producer.ProducerError,
                    "identity|symbolic|replacement",
                ):
                    producer.publish_raw_directories(
                        run_root, stage_root, roles
                    )
            self.assertFalse((outside / "raw").exists())
            self.assertTrue(target_parent.is_symlink())

    def test_capture_output_is_dirfd_anchored_and_detects_swap(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            runs_root = root / "runs"
            output_path = runs_root / "run-id" / "captures"
            outside = root / "outside"
            output_path.mkdir(parents=True)
            outside.mkdir()
            with patch.object(capture, "RUNS_ROOT", runs_root):
                output = capture.open_secure_capture_output(output_path)
                try:
                    capture.write_new_json(
                        output, "first.json", {"value": 1}
                    )
                    moved = root / "moved-captures"
                    output_path.rename(moved)
                    output_path.symlink_to(
                        outside, target_is_directory=True
                    )
                    with self.assertRaisesRegex(
                        capture.CaptureError,
                        "symbolic|identity",
                    ):
                        capture.write_new_json(
                            output, "escaped.json", {"value": 2}
                        )
                    self.assertFalse((outside / "escaped.json").exists())
                    self.assertTrue((moved / "first.json").is_file())
                finally:
                    output.close()

                root_swap_path = (
                    runs_root / "root-swap-run" / "captures"
                )
                root_swap_path.mkdir(parents=True)
                root_swap_output = capture.open_secure_capture_output(
                    root_swap_path
                )
                moved_runs_root = root / "moved-runs-root"
                try:
                    runs_root.rename(moved_runs_root)
                    runs_root.symlink_to(
                        outside, target_is_directory=True
                    )
                    (outside / "root-swap-run/captures").mkdir(
                        parents=True
                    )
                    with self.assertRaisesRegex(
                        capture.CaptureError,
                        "symbolic|identity",
                    ):
                        capture.write_new_json(
                            root_swap_output,
                            "escaped-root.json",
                            {"value": 3},
                        )
                    self.assertFalse(
                        (
                            outside
                            / "root-swap-run/captures/escaped-root.json"
                        ).exists()
                    )
                finally:
                    root_swap_output.close()
                    if runs_root.is_symlink():
                        runs_root.unlink()
                    if moved_runs_root.exists():
                        moved_runs_root.rename(runs_root)

                linked = runs_root / "linked"
                linked.symlink_to(outside, target_is_directory=True)
                (outside / "captures").mkdir()
                with self.assertRaisesRegex(
                    capture.CaptureError,
                    "symbolic/non-directory",
                ):
                    capture.open_secure_capture_output(
                        linked / "captures"
                    )

    def test_exclusive_rename_has_a_concurrent_no_clobber_winner(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            sources = [root / "source-a", root / "source-b"]
            for index, source in enumerate(sources):
                source.mkdir()
                (source / "value").write_text(str(index), encoding="utf-8")
            target = root / "target"
            barrier = threading.Barrier(2)
            outcomes: list[str] = []

            def contend(source: Path) -> None:
                barrier.wait()
                try:
                    producer.atomic_rename_exclusive(source, target)
                    outcomes.append("won")
                except producer.ProducerError:
                    outcomes.append("lost")

            threads = [
                threading.Thread(target=contend, args=(source,))
                for source in sources
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
            self.assertCountEqual(outcomes, ["won", "lost"])
            self.assertIn(
                (target / "value").read_text(encoding="utf-8"), {"0", "1"}
            )
            self.assertEqual(sum(source.exists() for source in sources), 1)

    def test_v4_schema_parser_and_framed_database_values(self) -> None:
        oracle = capture.expected_v4_schema()
        self.assertEqual(
            list(oracle["tables"]), list(capture.W2_POLICY_TABLES)
        )
        policy_version = oracle["tables"]["policy_version"]
        self.assertEqual(
            policy_version["table"],
            {"engine": "InnoDB", "collation": "utf8mb4_0900_ai_ci"},
        )
        created_at = next(
            value for value in policy_version["columns"]
            if value["name"] == "created_at"
        )
        self.assertEqual(created_at["columnType"], "DATETIME(6)")
        self.assertEqual(created_at["datetimePrecision"], 6)
        version_number = next(
            value for value in policy_version["columns"]
            if value["name"] == "version_number"
        )
        self.assertEqual(version_number["columnType"], "INT UNSIGNED")
        self.assertTrue(
            all(
                value["enforced"]
                for value in policy_version["checks"]
            )
        )
        self.assertEqual(
            [value["name"] for value in oracle["tables"]["policy_version"][
                "columns"
            ]],
            [
                "version_id",
                "template_id",
                "version_number",
                "status",
                "parameters_json",
                "effective_from",
                "effective_to",
                "change_reason",
                "validation_json",
                "snapshot_json",
                "snapshot_digest",
                "rollback_of_version_id",
                "row_version",
                "created_by",
                "created_at",
                "published_at",
                "updated_by",
                "updated_at",
            ],
        )
        self.assertEqual(capture.decode_hex_cell("N", "text?"), None)
        self.assertEqual(capture.decode_hex_cell("H3132", "uint"), 12)
        self.assertEqual(
            capture.decode_hex_cell("H7B2261223A317D", "json"),
            {"a": 1},
        )
        with self.assertRaises(capture.CaptureError):
            capture.decode_hex_cell("plain", "text")

    def test_fixed_w2_fixture_is_v6_only(self) -> None:
        with (
            patch.object(
                capture,
                "mysql_scalar",
                side_effect=["6", "1|2|1|1|1|1"],
            ),
            patch.object(capture, "mysql_query") as query,
        ):
            result = capture.seed_w2_fixture(
                Path("/fixed/mysql"),
                Path("/private/defaults"),
                capture.DATABASE,
            )
        query.assert_called_once()
        sql = query.call_args.args[3]
        self.assertIn("START TRANSACTION;", sql)
        self.assertIn("COMMIT;", sql)
        self.assertEqual(result["checkpoint"], 6)
        self.assertEqual(sum(result["cardinality"].values()), 7)

        with (
            patch.object(capture, "mysql_scalar", return_value="7"),
            patch.object(capture, "mysql_query") as query,
        ):
            with self.assertRaisesRegex(
                capture.CaptureError, "only at exact V6"
            ):
                capture.seed_w2_fixture(
                    Path("/fixed/mysql"),
                    Path("/private/defaults"),
                    capture.DATABASE,
                )
        query.assert_not_called()

    def test_seed_oracle_digests_and_cardinality_are_fixed(self) -> None:
        fixture = json.loads(
            capture.SEED_ORACLE.read_text(encoding="utf-8")
        )
        self.assertEqual(len(fixture["seeds"]), 3)
        self.assertEqual(
            len({value["templateId"] for value in fixture["seeds"]}), 3
        )
        self.assertEqual(
            len({value["scopeId"] for value in fixture["seeds"]}), 3
        )
        self.assertEqual(
            len({value["scopedVersionId"] for value in fixture["seeds"]}),
            3,
        )
        for seed in fixture["seeds"]:
            self.assertEqual(
                hashlib.sha256(
                    seed["canonicalSnapshot"].encode("utf-8")
                ).hexdigest(),
                seed["snapshotDigest"],
            )

    def test_oracle_hash_pins_reject_in_place_fixture_mutation(self) -> None:
        producer.validate_fixed_semantic_oracles()
        capture.load_fixed_w2_registry()
        capture.load_fixed_seed_oracle()
        with tempfile.TemporaryDirectory() as temporary:
            changed_seed = Path(temporary) / "seed.json"
            seed = json.loads(
                producer.SEED_ORACLE.read_text(encoding="utf-8")
            )
            seed["seeds"].pop()
            changed_seed.write_text(
                json.dumps(seed, ensure_ascii=False), encoding="utf-8"
            )
            with patch.object(producer, "SEED_ORACLE", changed_seed):
                with self.assertRaisesRegex(
                    producer.ProducerError, "seed oracle SHA256 drifted"
                ):
                    producer.validate_fixed_semantic_oracles()
            with patch.object(capture, "SEED_ORACLE", changed_seed):
                with self.assertRaisesRegex(
                    capture.CaptureError, "seed oracle SHA256 drifted"
                ):
                    capture.load_fixed_seed_oracle()
            changed_rows = Path(temporary) / "w2-rows.json"
            row_fixture = json.loads(
                producer.W2_FIXED_ROWS.read_text(encoding="utf-8")
            )
            row_fixture["sourceRows"].pop()
            changed_rows.write_text(
                json.dumps(row_fixture, ensure_ascii=False),
                encoding="utf-8",
            )
            with patch.object(producer, "W2_FIXED_ROWS", changed_rows):
                with self.assertRaisesRegex(
                    producer.ProducerError,
                    "row fixture SHA256 drifted",
                ):
                    producer.load_w2_fixed_row_oracle()
            changed_api = Path(temporary) / "api-oracle.json"
            api_oracle = json.loads(
                producer.API_SEMANTIC_ORACLE.read_text(encoding="utf-8")
            )
            api_oracle["operations"].pop()
            changed_api.write_text(
                json.dumps(api_oracle, ensure_ascii=False),
                encoding="utf-8",
            )
            with patch.object(
                producer, "API_SEMANTIC_ORACLE", changed_api
            ):
                with self.assertRaisesRegex(
                    producer.ProducerError,
                    "API semantic oracle SHA256 drifted",
                ):
                    producer.load_review_owned_api_oracle()


if __name__ == "__main__":
    unittest.main()
