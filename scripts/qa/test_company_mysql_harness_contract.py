#!/usr/bin/env python3
"""Static, read-only contracts for company-aware local MySQL harnesses."""

from __future__ import annotations

import ast
import re
import shlex
import subprocess
import textwrap
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WAVE1 = REPOSITORY_ROOT / "deploy/mysql/wave1-local-mysql.sh"
WAVE2 = REPOSITORY_ROOT / "deploy/mysql/wave2-local-mysql.sh"
WAVE3 = REPOSITORY_ROOT / "deploy/mysql/wave3-local-mysql.sh"
CUTOVER = (
    REPOSITORY_ROOT
    / "deploy/mysql/lib/company-dimension-cutover.sh"
)
WAVE2_RUNTIME = REPOSITORY_ROOT / "scripts/qa/verify-wave2-runtime-api.mjs"
PAYROLL_ZERO = REPOSITORY_ROOT / "scripts/qa/verify_wave3_payroll_zero.py"
RUNTIME_PROVENANCE = REPOSITORY_ROOT / "scripts/qa/wave3_runtime_provenance.py"
V11 = (
    REPOSITORY_ROOT
    / "backend/src/main/resources/db/migration/V11__unify_company_dimension.sql"
)
APPLICATION_YAML = (
    REPOSITORY_ROOT / "backend/src/main/resources/application.yml"
)
LEGACY_BOUNDARY = re.compile(r"legal_entity|legalEntity|LEGAL_ENTITY")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def bash_function(source: str, name: str) -> str:
    start = source.index(f"{name}() {{")
    end = source.index("\n}\n", start) + 2
    return source[start:end]


def bash_subshell_function(source: str, name: str) -> str:
    start = source.index(f"{name}() (")
    end = source.index("\n)\n", start) + 2
    return source[start:end]


class CompanyMysqlHarnessContractTest(unittest.TestCase):
    def run_cutover_bash(self, body: str) -> subprocess.CompletedProcess[str]:
        source = (
            "set -Eeuo pipefail\n"
            "assert_exact_database() { :; }\n"
            "log() { :; }\n"
            "fail() { printf '%s\\n' \"$*\" >&2; return 1; }\n"
            f"source {shlex.quote(CUTOVER.as_posix())}\n"
            + textwrap.dedent(body)
        )
        return subprocess.run(
            ("bash", "-c", source),
            cwd=REPOSITORY_ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_shell_and_python_sources_parse_without_execution(self) -> None:
        for script in (WAVE1, WAVE2, WAVE3, CUTOVER):
            with self.subTest(script=script.name):
                result = subprocess.run(
                    ("bash", "-n", script.as_posix()),
                    cwd=REPOSITORY_ROOT,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
        ast.parse(read(PAYROLL_ZERO), filename=PAYROLL_ZERO.as_posix())
        ast.parse(
            read(RUNTIME_PROVENANCE),
            filename=RUNTIME_PROVENANCE.as_posix(),
        )

    def test_wave3_v11_preflight_checks_exact_index_pairs_and_recovers_probes(
            self,
    ) -> None:
        source = read(WAVE3)
        index_contract = bash_function(
                source, "company_boundary_index_contract_matches")
        preflight = bash_function(source, "company_dimension_preflight_v10")
        cleanup = bash_function(
                source, "cleanup_company_preflight_negative_probes")
        probes = bash_subshell_function(
                source, "verify_company_preflight_negative_probes")

        for old_table, old_index, new_table, new_index in (
            (
                "legal_entity",
                "uq_legal_entity_code",
                "company",
                "uq_company_code",
            ),
            (
                "employee",
                "ix_employee_legal_entity_status",
                "employee",
                "ix_employee_company_status",
            ),
            (
                "organization_identity",
                "ix_organization_identity_legal_entity",
                "organization_identity",
                "ix_organization_identity_company",
            ),
            (
                "attendance_policy_scope",
                "ix_attendance_policy_scope_legal_entity",
                "attendance_policy_scope",
                "ix_attendance_policy_scope_company",
            ),
            (
                "attendance_source",
                "uq_att_source_id_entity",
                "attendance_source",
                "uq_att_source_id_company",
            ),
        ):
            with self.subTest(old_index=old_index):
                self.assertIn(f"TABLE_NAME = '{old_table}'", index_contract)
                self.assertIn(f"INDEX_NAME = '{old_index}'", index_contract)
                self.assertIn(f"TABLE_NAME = '{new_table}'", index_contract)
                self.assertIn(f"INDEX_NAME = '{new_index}'", index_contract)

        self.assertIn('[[ "$index_shape" == "5|5|0|0" ]]', index_contract)
        self.assertIn('[[ "$index_shape" == "0|0|5|5" ]]', index_contract)
        self.assertIn(
                "company_boundary_index_contract_matches", preflight)
        self.assertIn("missing source boundary index", probes)
        self.assertIn("occupied target boundary index", probes)
        self.assertIn("uq_legal_entity_code_probe", cleanup)
        self.assertIn("ALTER TABLE legal_entity DROP INDEX uq_company_code", cleanup)
        self.assertIn(
                "RENAME INDEX uq_legal_entity_code_probe", cleanup)
        for function_name in (
                "upgrade_development_company_dimension",
                "upgrade_v6_target7_latest_test",
                "empty_to_latest_test",
        ):
            function = bash_function(source, function_name)
            with self.subTest(function=function_name):
                self.assertLess(
                        function.index("company_dimension_preflight_v10"),
                        function.index('"-target=11" migrate'),
                )

    def test_latest_migration_contract_is_enforced_by_wave1_and_wave2(self) -> None:
        v11 = read(V11)
        rename_tables = re.findall(
            r"ALTER TABLE ([a-z_]+)\s+"
            r"RENAME COLUMN legal_entity_id TO company_id,\s+"
            r"ALGORITHM = INPLACE;",
            v11,
        )
        self.assertEqual(len(rename_tables), 29)
        self.assertEqual(len(set(rename_tables)), 29)
        self.assertEqual(v11.count("ALGORITHM = INPLACE;"), 29)
        self.assertNotIn("ALGORITHM = COPY", v11)
        for script in (WAVE1, WAVE2):
            source = read(script)
            contract = bash_function(
                source, "verify_company_dimension_contract"
            )
            with self.subTest(script=script.name):
                self.assertIn(
                    "company_cutover_verify_latest", contract
                )
                self.assertIn("TABLE_NAME = 'company'", contract)
                self.assertIn("COLUMN_NAME = 'company_id'", contract)
                self.assertIn(
                    "scope_type NOT IN ('COMPANY', 'ORGANIZATION', 'SELF')",
                    contract,
                )
                self.assertIn("version = '11'", contract)
                self.assertIn(
                    '[[ "$company_column_count" == "29"',
                    contract,
                )
                self.assertIn("COMPANY_DIMENSION_CONTRACT=PASS", contract)

    def test_application_never_auto_migrates_around_the_cutover_gate(
            self,
    ) -> None:
        application = read(APPLICATION_YAML)
        self.assertIn(
            "enabled: ${SHENZHOUHR_FLYWAY_ENABLED:false}",
            application,
        )

    def test_wave1_and_wave2_delegate_to_shared_exact_cutover(self) -> None:
        for script in (WAVE1, WAVE2):
            source = read(script)
            preflight = bash_function(
                source, "verify_company_cutover_preflight"
            )
            with self.subTest(script=script.name):
                self.assertIn(
                    'source "${SCRIPT_DIR}/lib/company-dimension-cutover.sh"',
                    source,
                )
                self.assertIn(
                    "company_cutover_verify_v10",
                    preflight,
                )

    def test_shared_cutover_requires_exact_v10_and_latest_contracts(self) -> None:
        source = read(CUTOVER)
        v10 = bash_function(source, "company_cutover_verify_v10")
        latest = bash_function(source, "company_cutover_verify_latest")
        scope = bash_function(
            source, "company_cutover_scope_check_matches"
        )
        auxiliary_checks = bash_function(
            source,
            "company_cutover_auxiliary_check_contract_matches",
        )
        indexes = bash_function(
            source, "company_cutover_index_contract_matches"
        )
        relationships = bash_function(
            source, "company_cutover_relationship_contract_matches"
        )
        migrate = bash_function(
            source, "company_cutover_migrate_to_v11"
        )

        self.assertIn('[[ "$schema_shape" == "29|29|0|0" ]]', v10)
        self.assertIn('[[ "$schema_shape" == "29|29|0|0" ]]', latest)
        self.assertIn('[[ "$v11_history_count" == "0" ]]', v10)
        self.assertIn("partial V11 history", v10)
        self.assertIn('[[ "$v11_history_count" == "1" ]]', latest)
        self.assertIn("company_cutover_relationship_contract_matches", v10)
        self.assertIn("company_cutover_relationship_contract_matches", latest)
        self.assertIn('[[ "$relationship_shape" == "28|29|29|29" ]]',
                      relationships)
        self.assertIn("TABLE_NAME <> '${boundary_table}'", relationships)
        self.assertIn(
            "REFERENCED_COLUMN_NAME = '${boundary_column}'",
            relationships,
        )
        for table, parent in (
            ("employee", "${boundary_table}"),
            ("source_device", "attendance_source"),
            ("raw_attendance_fact", "attendance_source"),
            ("punch_import_batch", "attendance_source"),
            ("punch_import_file", "attendance_source"),
            ("device_person_binding", "source_device"),
            ("device_person_binding", "attendance_source"),
            (
                "attendance_report_daily_fact",
                "attendance_report_projection",
            ),
            (
                "attendance_report_oa_fact",
                "attendance_report_projection",
            ),
            (
                "attendance_report_exception_fact",
                "attendance_report_projection",
            ),
            (
                "attendance_report_time_account_fact",
                "attendance_report_projection",
            ),
        ):
            with self.subTest(table=table, parent=parent):
                self.assertIn(f"'{table}'", relationships)
                self.assertIn(f"'{parent}'", relationships)

        self.assertIn("tc.ENFORCED = 'YES'", scope)
        self.assertIn("actual == expected", source)
        expected_auxiliary_checks = re.findall(
            r"'([a-z_]+[.]ck_[a-z0-9_]+)'",
            auxiliary_checks,
        )
        self.assertEqual(len(expected_auxiliary_checks), 44)
        self.assertEqual(len(set(expected_auxiliary_checks)), 44)
        self.assertIn(
            '[[ "$check_shape" == "44|44|44" ]]',
            auxiliary_checks,
        )
        self.assertIn(
            "company_cutover_auxiliary_check_contract_matches",
            v10,
        )
        self.assertIn(
            "company_cutover_auxiliary_check_contract_matches",
            latest,
        )
        self.assertIn('[[ "$index_shape" == "5|5|0|0" ]]', indexes)
        self.assertIn('[[ "$index_shape" == "0|0|5|5" ]]', indexes)
        self.assertIn("company_cutover_boundary_orphan_count", v10)
        self.assertIn("company_cutover_boundary_orphan_count", latest)
        self.assertIn("WHERE state = 'STARTED';", v10)
        self.assertIn("WHERE status = 'STARTED';", v10)
        self.assertLess(
            migrate.index('"-target=10" migrate'),
            migrate.index("company_cutover_verify_v10"),
        )
        self.assertLess(
            migrate.index("company_cutover_verify_v10"),
            migrate.index('"-target=11" migrate'),
        )
        self.assertLess(
            migrate.index('"-target=11" migrate'),
            migrate.index("company_cutover_verify_latest"),
        )

    def test_shared_relationship_gate_rejects_missing_extra_or_wrong_edges(
            self,
    ) -> None:
        result = self.run_cutover_bash(
            r"""
            MOCK_RELATIONSHIP_SHAPE="28|29|29|29"
            mysql_scalar() {
              printf '%s' "$MOCK_RELATIONSHIP_SHAPE"
            }
            company_cutover_relationship_contract_matches db defaults v10
            for MOCK_RELATIONSHIP_SHAPE in \
                "27|29|29|29" \
                "28|28|28|28" \
                "28|30|29|30" \
                "28|29|28|29" \
                "28|29|29|30"; do
              if company_cutover_relationship_contract_matches \
                  db defaults v10; then
                exit 91
              fi
            done
            """
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_shared_auxiliary_check_gate_rejects_inventory_or_state_drift(
            self,
    ) -> None:
        result = self.run_cutover_bash(
            r"""
            MOCK_CHECK_SHAPE="44|44|44"
            mysql_scalar() {
              printf '%s' "$MOCK_CHECK_SHAPE"
            }
            company_cutover_auxiliary_check_contract_matches \
              db defaults v10
            for MOCK_CHECK_SHAPE in \
                "43|43|43" \
                "45|45|44" \
                "44|43|44" \
                "44|44|43"; do
              if company_cutover_auxiliary_check_contract_matches \
                  db defaults v10; then
                exit 97
              fi
            done
            """
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_shared_scope_gate_requires_enforced_exact_ast(self) -> None:
        result = self.run_cutover_bash(
            r"""
            MOCK_CONSTRAINT_SHAPE="1|1"
            VALID_CHECK_CLAUSE="
              (scope_type = 'COMPANY'
                AND company_id IS NOT NULL
                AND organization_id IS NULL)
              OR (scope_type = 'ORGANIZATION'
                AND company_id IS NULL
                AND organization_id IS NOT NULL)
              OR (scope_type = 'SELF'
                AND company_id IS NULL
                AND organization_id IS NULL)"
            MOCK_CHECK_CLAUSE="$VALID_CHECK_CLAUSE"
            mysql_scalar() {
              case "$3" in
                *"SELECT CONCAT_WS"*)
                  printf '%s' "$MOCK_CONSTRAINT_SHAPE"
                  ;;
                *"SELECT cc.CHECK_CLAUSE"*)
                  printf '%s' "$MOCK_CHECK_CLAUSE"
                  ;;
                *)
                  exit 92
                  ;;
              esac
            }
            company_cutover_scope_check_matches db defaults latest
            MOCK_CHECK_CLAUSE="
              (scope_type = _utf8mb4\\'COMPANY\\'
                AND company_id IS NOT NULL
                AND organization_id IS NULL)
              OR (scope_type = _utf8mb4\\'ORGANIZATION\\'
                AND company_id IS NULL
                AND organization_id IS NOT NULL)
              OR (scope_type = _utf8mb4\\'SELF\\'
                AND company_id IS NULL
                AND organization_id IS NULL)"
            company_cutover_scope_check_matches db defaults latest
            MOCK_CONSTRAINT_SHAPE="1|0"
            if company_cutover_scope_check_matches db defaults latest; then
              exit 93
            fi
            MOCK_CONSTRAINT_SHAPE="1|1"
            MOCK_CHECK_CLAUSE="${VALID_CHECK_CLAUSE/COMPANY/COMPANY_MUTATED}"
            if company_cutover_scope_check_matches db defaults latest; then
              exit 94
            fi
            MOCK_CHECK_CLAUSE="${VALID_CHECK_CLAUSE/AND organization_id IS NULL/}"
            if company_cutover_scope_check_matches db defaults latest; then
              exit 95
            fi
            MOCK_CHECK_CLAUSE="$VALID_CHECK_CLAUSE"
            MOCK_CHECK_CLAUSE+=" OR (scope_type = 'UNSUPPORTED')"
            if company_cutover_scope_check_matches db defaults latest; then
              exit 96
            fi
            """
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_shared_migration_is_strictly_target10_gate_target11(self) -> None:
        result = self.run_cutover_bash(
            r"""
            MOCK_VERSION=9
            CALLS=""
            company_cutover_current_version() {
              printf '%s' "$MOCK_VERSION"
            }
            run_flyway() {
              case "$2" in
                "-target=10")
                  MOCK_VERSION=10
                  CALLS+="target10,"
                  ;;
                "-target=11")
                  MOCK_VERSION=11
                  CALLS+="target11,"
                  ;;
                *)
                  exit 95
                  ;;
              esac
            }
            company_cutover_verify_v10() {
              [[ "$MOCK_VERSION" == "10" ]]
              CALLS+="gate10,"
            }
            company_cutover_verify_latest() {
              [[ "$MOCK_VERSION" -ge 11 ]]
              CALLS+="latest,"
            }
            company_cutover_migrate_to_v11 db defaults
            [[ "$CALLS" == "target10,gate10,target11,latest," ]]
            MOCK_VERSION=11
            CALLS=""
            company_cutover_migrate_to_v11 db defaults
            [[ "$CALLS" == "latest," ]]
            """
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_every_wave1_latest_path_checks_company_shape(self) -> None:
        source = read(WAVE1)
        for function_name in (
            "migrate_fresh_test_database",
            "upgrade_v2_test_database",
            "migrate_dev_database",
        ):
            function = bash_function(source, function_name)
            with self.subTest(function=function_name):
                self.assertLess(
                    function.index("company_cutover_migrate_to_v11"),
                    function.index("verify_company_dimension_contract"),
                )
                self.assertIn(
                    "company_cutover_migrate_to_v11", function
                )
        self.assertIn(
            "company_cutover_verify_latest",
            bash_function(source, "verify_migrate_noop"),
        )
        self.assertIn(
            '"-target=11" migrate',
            bash_function(source, "verify_migrate_noop"),
        )

    def test_wave2_fingerprint_normalizes_pre_v11_and_latest_names(self) -> None:
        source = read(WAVE2)
        names = bash_function(source, "company_dimension_names")
        fingerprint = bash_function(source, "core_data_fingerprint")
        row_counts = bash_function(source, "core_row_counts")
        self.assertIn('printf \'%s\\n\' "company company_id"', names)
        self.assertIn(
            'printf \'%s\\n\' "legal_entity legal_entity_id"', names
        )
        self.assertIn(
            "CONCAT_WS('|', 'company', ${boundary_id_column}",
            fingerprint,
        )
        self.assertIn("FROM ${boundary_table}", fingerprint)
        self.assertIn("FROM ${boundary_table}", row_counts)
        self.assertNotIn("FROM legal_entity", fingerprint)
        self.assertNotIn("FROM legal_entity", row_counts)

    def test_wave2_latest_paths_check_company_shape(self) -> None:
        source = read(WAVE2)
        for function_name in (
            "migrate_dev",
            "migrate_fresh_test",
            "upgrade_v4_test",
            "verify_contract_for_database",
        ):
            function = bash_function(source, function_name)
            with self.subTest(function=function_name):
                self.assertIn("verify_company_dimension_contract", function)
                if function_name != "verify_contract_for_database":
                    self.assertIn(
                        "company_cutover_migrate_to_v11", function
                    )
        self.assertIn(
            "company_cutover_verify_latest",
            bash_function(source, "verify_second_migrate_noop"),
        )
        self.assertIn(
            '"-target=11" migrate',
            bash_function(source, "verify_second_migrate_noop"),
        )

    def test_wave2_runtime_api_uses_only_current_company_contract(self) -> None:
        source = read(WAVE2_RUNTIME)
        self.assertIsNone(LEGACY_BOUNDARY.search(source))
        self.assertIn("const COMPANY_ID =", source)
        self.assertGreaterEqual(source.count("companyId:"), 7)
        self.assertIn("scopeType: 'COMPANY'", source)

    def test_legacy_shell_tokens_are_bounded_to_transition_checks_and_metadata(
        self,
    ) -> None:
        allowed_wave1 = (
            re.compile(r"^\s*AND TABLE_NAME = 'legal_entity';?$"),
            re.compile(r"^\s*AND COLUMN_NAME = 'legal_entity_id';$"),
            re.compile(r"^\s*WHERE scope_type = 'LEGAL_ENTITY';$"),
            re.compile(r"^\s*\(scope_type = 'LEGAL_ENTITY'$"),
            re.compile(
                r"^\s*AND legal_entity_id IS NOT NULL "
                r"AND organization_id IS NULL\)$"
            ),
            re.compile(
                r"^\s*AND legal_entity_id IS NULL "
                r"AND organization_id IS NOT NULL\)$"
            ),
            re.compile(
                r"^\s*AND legal_entity_id IS NULL "
                r"AND organization_id IS NULL\)$"
            ),
        )
        allowed_wave2 = allowed_wave1 + (
            re.compile(r'^\s*printf .+ "legal_entity legal_entity_id"$'),
            re.compile(r"^\s*'fk_people_import_legal_entity',$"),
            re.compile(r"^\s*'fk_people_publication_legal_entity',$"),
        )
        for script, allowed in (
            (WAVE1, allowed_wave1),
            (WAVE2, allowed_wave2),
        ):
            violations = [
                f"{number}:{line}"
                for number, line in enumerate(read(script).splitlines(), 1)
                if LEGACY_BOUNDARY.search(line)
                and not any(pattern.fullmatch(line) for pattern in allowed)
            ]
            with self.subTest(script=script.name):
                self.assertEqual(violations, [])

    def test_v1_v7_payroll_harness_is_explicitly_historical_and_not_called(
        self,
    ) -> None:
        payroll = read(PAYROLL_ZERO)
        provenance = read(RUNTIME_PROVENANCE)
        self.assertIn("FROZEN_FLYWAY_MIGRATIONS = (", payroll)
        self.assertIn(
            '"""Derive exact V1-V7 history from independently pinned '
            'migration bytes."""',
            payroll,
        )
        self.assertIn("FROM legal_entity", payroll)
        for forbidden_call in (
            "payroll.create_consistent_database_snapshot(",
            "payroll.parse_consistent_database_snapshot(",
            "payroll.load_role_principals(",
        ):
            self.assertNotIn(forbidden_call, provenance)


if __name__ == "__main__":
    unittest.main()
