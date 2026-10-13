from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "export_people_initial_data.py"
SPEC = importlib.util.spec_from_file_location("export_people_initial_data", SCRIPT)
assert SPEC and SPEC.loader
exporter = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = exporter
SPEC.loader.exec_module(exporter)


class ExportPeopleInitialDataTest(unittest.TestCase):
    def test_validates_closed_company_scoped_graph(self) -> None:
        organizations = [
            {
                "organizationCode": "ROOT",
                "name": "Root",
                "parentOrganizationCode": "",
                "organizationType": "COMPANY",
                "effectiveFrom": "2026-01-01",
            },
            {
                "organizationCode": "D001",
                "name": "Department",
                "parentOrganizationCode": "ROOT",
                "organizationType": "DEPARTMENT",
                "effectiveFrom": "2026-01-01",
            },
        ]
        employees = [
            {
                "employeeNumber": "000001",
                "externalEmployeeId": "EX-1",
                "displayName": "Synthetic Person",
                "effectiveFrom": "2026-01-01",
            }
        ]
        employments = [
            {
                "employeeNumber": "000001",
                "organizationCode": "D001",
                "startDate": "2026-01-01",
                "terminationDate": "",
            }
        ]
        exporter.validate_dataset(organizations, employees, employments, (2, 1, 1))

    def test_rejects_employment_outside_exported_organization_tree(self) -> None:
        organizations = [
            {
                "organizationCode": "ROOT",
                "name": "Root",
                "parentOrganizationCode": "",
                "organizationType": "COMPANY",
                "effectiveFrom": "2026-01-01",
            }
        ]
        employees = [
            {
                "employeeNumber": "1",
                "externalEmployeeId": "EX-1",
                "displayName": "Synthetic Person",
                "effectiveFrom": "2026-01-01",
            }
        ]
        employments = [
            {
                "employeeNumber": "1",
                "organizationCode": "MISSING",
                "startDate": "2026-01-01",
                "terminationDate": "",
            }
        ]
        with self.assertRaisesRegex(ValueError, "unknown organization"):
            exporter.validate_dataset(organizations, employees, employments, (1, 1, 1))

    def test_workbook_uses_exact_production_headers_and_text_cells(self) -> None:
        file_name, fields = exporter.WORKBOOKS["employees"]
        rows = [
            {
                "employeeNumber": "000001",
                "externalEmployeeId": "000000000000000001",
                "displayName": "Synthetic Person",
                "effectiveFrom": "2026-01-01",
            }
        ]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / file_name
            exporter.write_workbook(path, file_name, fields, rows)
            exporter.validate_workbook(path, fields, 1)
            workbook = exporter.load_workbook(path, read_only=True, data_only=False)
            try:
                values = [cell.value for cell in workbook["Data"][2]]
                self.assertEqual(values[0], "000001")
                self.assertEqual(values[1], "000000000000000001")
            finally:
                workbook.close()

    def test_sql_literal_rejects_injection_characters(self) -> None:
        self.assertEqual(exporter.sql_literal("SZSC"), "'SZSC'")
        with self.assertRaisesRegex(ValueError, "unsafe"):
            exporter.sql_literal("SZSC' OR 1=1 --")

    def test_queries_enforce_company_boundary_for_parent_and_employment(self) -> None:
        arguments = SimpleNamespace(source_company_code="SZSC")
        with patch.object(exporter, "mysql_json_rows", return_value=[]) as query:
            exporter.load_organizations(arguments)
            organization_sql = query.call_args.args[1]
        self.assertIn("poi.company_id = oi.company_id", organization_sql)
        self.assertIn("ov.parent_organization_id IS NULL OR poi.organization_id IS NOT NULL", organization_sql)

        with patch.object(exporter, "mysql_json_rows", return_value=[]) as query:
            exporter.load_employments(arguments)
            employment_sql = query.call_args.args[1]
        self.assertIn("oi.company_id = e.company_id", employment_sql)
        self.assertIn("oi.identity_status = 'ACTIVE'", employment_sql)

    def test_existing_archive_checksum_is_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            mysql = root / "mysql"
            defaults = root / "client.cnf"
            mysql.write_text("#!/bin/sh\n", encoding="utf-8")
            defaults.write_text("[client]\n", encoding="utf-8")
            mysql.chmod(0o700)
            defaults.chmod(0o600)
            output = root / "people-data"
            Path(f"{output.with_suffix('.tar.gz')}.sha256").write_text(
                "existing\n", encoding="utf-8"
            )
            arguments = SimpleNamespace(
                mysql_bin=mysql,
                defaults_extra_file=defaults,
                database="shenzhou_hr_dev",
                source_company_code="SZSC",
                target_company_code="szsemicon",
                output_dir=output,
                expected_organizations=1,
                expected_employees=1,
                expected_employments=1,
            )
            with self.assertRaisesRegex(ValueError, "checksum already exists"):
                exporter.validate_args(arguments)


if __name__ == "__main__":
    unittest.main()
