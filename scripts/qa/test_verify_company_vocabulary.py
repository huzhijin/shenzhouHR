from __future__ import annotations

import unittest

from scripts.qa import verify_company_vocabulary as gate


class VerifyCompanyVocabularyTest(unittest.TestCase):
    def test_legacy_pattern_covers_runtime_forms(self) -> None:
        for value in (
            "legal_entity_id",
            "legalEntityId",
            "LegalEntityDirectory",
            "LEGAL_ENTITY",
            "legal-entity",
            "legal entity",
            "\u6cd5\u4eba",
        ):
            with self.subTest(value=value):
                self.assertIsNotNone(gate.LEGACY_BOUNDARY.search(value))

        self.assertIsNone(gate.LEGACY_BOUNDARY.search("companyId"))

    def test_v11_only_allows_exact_bridge_lines(self) -> None:
        allowed = (
            "RENAME TABLE legal_entity TO company;",
            "    RENAME COLUMN legal_entity_id TO company_id,",
            "    RENAME INDEX uq_legal_entity_code TO uq_company_code;",
            "    RENAME INDEX ix_employee_legal_entity_status",
            "WHERE scope_type = 'LEGAL_ENTITY';",
        )
        for line in allowed:
            with self.subTest(line=line):
                self.assertTrue(gate.valid_v11_bridge_line(line))

        rejected = (
            "SELECT * FROM legal_entity;",
            "SET company_id = legal_entity_id;",
            "WHERE scope_type IN ('LEGAL_ENTITY', 'COMPANY');",
            "-- legal entity compatibility alias",
        )
        for line in rejected:
            with self.subTest(line=line):
                self.assertFalse(gate.valid_v11_bridge_line(line))

    def test_migration_classification_is_version_bounded(self) -> None:
        self.assertEqual(
            gate.classification(
                "backend/src/main/resources/db/migration/V1__baseline.sql"
            ),
            "historical-migration",
        )
        self.assertEqual(
            gate.classification(
                "backend/src/main/resources/db/migration/V10__reporting.sql"
            ),
            "historical-migration",
        )
        self.assertEqual(
            gate.classification(
                "backend/src/main/resources/db/migration/"
                "V11__unify_company_dimension.sql"
            ),
            "v11-bridge",
        )
        self.assertEqual(
            gate.classification(
                "backend/src/main/resources/db/migration/V12__future.sql"
            ),
            "current",
        )

    def test_historical_allowlist_is_exact_and_current_docs_are_scanned(self) -> None:
        self.assertEqual(
            gate.classification(
                "docs/contracts/wave5-w4-final-handoff.md"
            ),
            "historical-evidence",
        )
        self.assertEqual(
            gate.classification(
                "openspec/changes/wave3-attendance-setup-and-policies/"
                "specs/wave3-verification/oracles/retained.json"
            ),
            "historical-oracle",
        )
        self.assertEqual(
            gate.classification(
                "docs/contracts/v1.9-formal-functional-handoff.md"
            ),
            "current",
        )
        self.assertEqual(
            gate.classification("docs/docs-confirmed/not-an-allowlist-entry.md"),
            "current",
        )
        self.assertEqual(
            gate.classification(
                "openspec/changes/wave4-attendance-sources-and-punch-imports/"
                "design.md"
            ),
            "current",
        )
        self.assertEqual(
            gate.classification("deploy/mysql/wave1-local-mysql.sh"),
            "current",
        )
        self.assertEqual(
            gate.classification("deploy/mysql/wave3-local-mysql.sh"),
            "current",
        )
        self.assertEqual(
            gate.classification(
                "scripts/qa/verify-wave2-runtime-api.mjs"
            ),
            "current",
        )
        self.assertEqual(
            gate.classification(
                "scripts/qa/produce_wave3_semantic_gates.py"
            ),
            "current",
        )
        self.assertEqual(
            gate.classification(
                "scripts/qa/verify_wave3_payroll_zero.py"
            ),
            "historical-qa-harness",
        )

    def test_current_transition_exceptions_are_path_and_line_exact(self) -> None:
        allowed = (
            (
                "deploy/mysql/wave1-local-mysql.sh",
                "      AND COLUMN_NAME = 'legal_entity_id';",
            ),
            (
                "deploy/mysql/wave2-local-mysql.sh",
                '    printf \'%s\\n\' "legal_entity legal_entity_id"',
            ),
            (
                "deploy/mysql/wave3-local-mysql.sh",
                '    target7) company_column="legal_entity_id" ;;',
            ),
            (
                "deploy/mysql/wave3-local-mysql.sh",
                '    row = row.replace("legal_entity_id", "company_id")',
            ),
            (
                "deploy/mysql/wave3-local-mysql.sh",
                '    row = row.replace("legal_entity", "company")',
            ),
            (
                "deploy/mysql/wave3-local-mysql.sh",
                '        row = row.replace("legal_entity_id", "company_id")',
            ),
            (
                "deploy/mysql/wave3-local-mysql.sh",
                '        row = row.replace("legal_entity", "company")',
            ),
            (
                "scripts/qa/produce_wave3_semantic_gates.py",
                '            "legalEntityId": '
                'fixture["baselineLegalEntityId"],',
            ),
            (
                "scripts/qa/capture_wave3_mysql_semantics.py",
                "                   scope.scope_id, scope.legal_entity_id,",
            ),
            (
                "scripts/qa/capture_wave3_mysql_semantics.py",
                '        or fixture.get("baselineLegalEntityId")',
            ),
        )
        for path, line in allowed:
            with self.subTest(path=path, line=line):
                self.assertTrue(
                    gate.valid_current_transition_line(path, line)
                )

        rejected = (
            (
                "deploy/mysql/wave1-local-mysql.sh",
                "SELECT * FROM legal_entity;",
            ),
            (
                "deploy/mysql/wave3-local-mysql.sh",
                '    latest) company_column="legal_entity_id" ;;',
            ),
            (
                "deploy/mysql/wave3-local-mysql.sh",
                'normalized = normalized.replace("legal_entity", "company")',
            ),
            (
                "deploy/mysql/wave3-local-mysql.sh",
                'row = row.replace("legal_entity", "company")',
            ),
            (
                "deploy/mysql/wave3-local-mysql.sh",
                '    row = row.replace("legal_entity", "tenant")',
            ),
            (
                "deploy/mysql/wave1-local-mysql.sh",
                '    row = row.replace("legal_entity", "company")',
            ),
            (
                "scripts/qa/verify-wave2-runtime-api.mjs",
                "body: { legalEntityId: COMPANY_ID },",
            ),
            (
                "scripts/qa/produce_wave3_semantic_gates.py",
                'lifecycle_parameter = ("query", "legalEntityId")',
            ),
            (
                "scripts/qa/capture_wave3_mysql_semantics.py",
                "SELECT scope.legal_entity_id FROM current_runtime_scope;",
            ),
        )
        for path, line in rejected:
            with self.subTest(path=path, line=line):
                self.assertFalse(
                    gate.valid_current_transition_line(path, line)
                )

    def test_wave3_v10_v11_bridge_exact_lines_are_frozen(self) -> None:
        path = "deploy/mysql/wave3-local-mysql.sh"
        exact_lines = gate.CURRENT_TRANSITION_EXACT_LINES[path]
        self.assertEqual(len(exact_lines), 68)
        for line in exact_lines:
            with self.subTest(line=line):
                self.assertIsNotNone(gate.LEGACY_BOUNDARY.search(line))
                self.assertTrue(
                    gate.valid_current_transition_line(path, line)
                )

        rejected = (
            '      boundary_table="legal_entity_copy"',
            "        ALTER TABLE legal_entity ADD COLUMN compatibility_id INT;",
            "    SELECT * FROM legal_entity;",
            "        'LEGAL_ENTITY_ALIAS',",
            "      period_start, legal_entity_id, arbitrary_id, employee_id,",
        )
        for line in rejected:
            with self.subTest(line=line):
                self.assertFalse(
                    gate.valid_current_transition_line(path, line)
                )

    def test_explicit_current_qa_files_are_scanned(self) -> None:
        scanned = {
            gate.relative(path)
            for path in gate.iter_files()
        }
        self.assertTrue(gate.CURRENT_QA_FILES <= scanned)
        self.assertTrue(gate.HISTORICAL_QA_FILES <= scanned)

    def test_violation_output_is_exact_path_line_text(self) -> None:
        self.assertEqual(
            gate.render_violation(
                "docs/current.md",
                17,
                "  forbidden compatibility sentence",
            ),
            "docs/current.md:17:  forbidden compatibility sentence",
        )

    def test_change_definition_is_not_a_runtime_exception(self) -> None:
        self.assertEqual(
            gate.classification(
                "openspec/changes/company-dimension-unification/specs/"
                "company-dimension-boundary/spec.md"
            ),
            "change-definition",
        )
        self.assertEqual(
            gate.classification("backend/src/main/java/example/Company.java"),
            "current",
        )


if __name__ == "__main__":
    unittest.main()
