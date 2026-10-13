from __future__ import annotations

import importlib.util
import json
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "export_four_company_people.py"
SPEC = importlib.util.spec_from_file_location("export_four_company_people", SCRIPT)
assert SPEC and SPEC.loader
aggregator = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = aggregator
SPEC.loader.exec_module(aggregator)


class ExportFourCompanyPeopleTest(unittest.TestCase):
    def test_contract_matches_signed_catalog_and_approved_counts(self) -> None:
        contracts = aggregator.load_signed_company_catalog(
            aggregator.DEFAULT_COMPANY_CATALOG
        )

        self.assertEqual(
            [
                ("SZSZ", "上海昇州半导体科技有限公司", (2, 1, 1)),
                ("SZJN", "上海晟州聚能半导体科技有限公司", (17, 35, 35)),
                ("SZSC", "江苏神州半导体科技股份有限公司", (130, 571, 571)),
                ("SZXY", "江苏芯越半导体科技有限公司", (7, 8, 8)),
            ],
            [(item.code, item.name, item.counts) for item in contracts],
        )
        self.assertEqual(156, sum(item.organizations for item in contracts))
        self.assertEqual(615, sum(item.employees for item in contracts))
        self.assertEqual(615, sum(item.employments for item in contracts))

    def test_rejects_company_catalog_byte_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            catalog = Path(temporary) / "companies.tsv"
            catalog.write_bytes(
                aggregator.expected_catalog_bytes().replace(
                    "江苏芯越半导体科技有限公司".encode(),
                    "漂移公司".encode(),
                )
            )

            with self.assertRaisesRegex(ValueError, "signed four-company"):
                aggregator.load_signed_company_catalog(catalog)

    def test_active_source_company_set_and_names_must_match_exactly(self) -> None:
        args = SimpleNamespace(
            mysql_bin=Path("/trusted/mysql"),
            defaults_extra_file=Path("/trusted/client.cnf"),
            database="shenzhou_hr",
            output_dir=Path("four-company-people"),
        )
        rows = [
            {
                "companyCode": contract.code,
                "companyName": contract.name,
                "status": "ACTIVE",
            }
            for contract in reversed(aggregator.COMPANY_CONTRACTS)
        ]
        with patch.object(
            aggregator.single_exporter, "mysql_json_rows", return_value=rows
        ) as mysql_rows:
            aggregator.load_active_company_set(args, aggregator.COMPANY_CONTRACTS)
        self.assertIn("WHERE status = 'ACTIVE'", mysql_rows.call_args.args[1])

        exact_rows = list(rows)
        rows[0] = {**rows[0], "companyName": "错误名称"}
        with patch.object(
            aggregator.single_exporter, "mysql_json_rows", return_value=rows
        ):
            with self.assertRaisesRegex(ValueError, "codes or names"):
                aggregator.load_active_company_set(args, aggregator.COMPANY_CONTRACTS)

        extra_rows = exact_rows + [
            {
                "companyCode": "EXTRA",
                "companyName": "不应出现的启用公司",
                "status": "ACTIVE",
            }
        ]
        with patch.object(
            aggregator.single_exporter, "mysql_json_rows", return_value=extra_rows
        ):
            with self.assertRaisesRegex(ValueError, "codes or names"):
                aggregator.load_active_company_set(args, aggregator.COMPANY_CONTRACTS)

    def test_collect_company_reuses_single_company_exporter_contract(self) -> None:
        contract = aggregator.COMPANY_CONTRACTS[0]
        args = SimpleNamespace(
            mysql_bin=Path("/trusted/mysql"),
            defaults_extra_file=Path("/trusted/client.cnf"),
            database="shenzhou_hr",
            output_dir=Path("four-company-people"),
        )
        organizations = [{}, {}]
        employees = [{}]
        employments = [{}]
        company = {
            "companyCode": contract.code,
            "companyName": contract.name,
            "status": "ACTIVE",
        }
        with (
            patch.object(
                aggregator.single_exporter, "load_company", return_value=company
            ),
            patch.object(
                aggregator.single_exporter,
                "load_organizations",
                return_value=organizations,
            ) as load_organizations,
            patch.object(
                aggregator.single_exporter,
                "load_employees",
                return_value=employees,
            ),
            patch.object(
                aggregator.single_exporter,
                "load_employments",
                return_value=employments,
            ),
            patch.object(
                aggregator.single_exporter, "validate_dataset"
            ) as validate_dataset,
        ):
            result = aggregator.collect_company(args, contract)

        self.assertEqual(contract, result.contract)
        self.assertEqual(
            contract.code, load_organizations.call_args.args[0].source_company_code
        )
        validate_dataset.assert_called_once_with(
            organizations, employees, employments, (2, 1, 1)
        )

    def test_success_publishes_only_complete_package_with_strict_permissions(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(
                database="shenzhou_hr",
                company_catalog=aggregator.DEFAULT_COMPANY_CATALOG,
                output_dir=root / "four-company-people",
            )
            contracts = self._small_contracts()
            companies = [self._collected(contract) for contract in contracts]

            with patch.object(aggregator, "COMPANY_CONTRACTS", contracts):
                output = aggregator.publish_package(args, companies)

            self.assertEqual(args.output_dir, output)
            self.assertEqual(
                {
                    "SZSZ",
                    "SZJN",
                    "SZSC",
                    "SZXY",
                    "MANIFEST.json",
                    "RUNBOOK.txt",
                    "SHA256SUMS",
                },
                {path.name for path in output.iterdir()},
            )
            workbook_names = {
                definition[0]
                for definition in aggregator.single_exporter.WORKBOOKS.values()
            }
            for contract in contracts:
                company_dir = output / contract.code
                self.assertEqual(
                    workbook_names, {path.name for path in company_dir.iterdir()}
                )

            manifest = json.loads(
                (output / "MANIFEST.json").read_text(encoding="utf-8")
            )
            self.assertEqual(4, manifest["companyCount"])
            self.assertEqual(12, manifest["importBatchCount"])
            self.assertEqual(
                {"organizations": 8, "employees": 4, "employments": 4},
                manifest["totals"],
            )
            for company in manifest["companies"]:
                for workbook in company["files"]:
                    self.assertEqual(
                        company["code"], workbook["targetCompanyCode"]
                    )
                    self.assertEqual(
                        company["name"], workbook["targetCompanyName"]
                    )
                    self.assertIn(
                        workbook["templateType"],
                        {"ORGANIZATION", "EMPLOYEE", "EMPLOYMENT"},
                    )
                    self.assertGreater(workbook["rowCount"], 0)
                    self.assertRegex(workbook["sha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(
                14,
                len((output / "SHA256SUMS").read_text(encoding="utf-8").splitlines()),
            )
            self._assert_permissions(output)
            self.assertEqual([], list(root.glob(".four-company-people.staging-*")))

    def test_staging_failure_leaves_no_partial_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(
                database="shenzhou_hr",
                company_catalog=aggregator.DEFAULT_COMPANY_CATALOG,
                output_dir=root / "four-company-people",
            )
            contracts = self._small_contracts()
            companies = [self._collected(contract) for contract in contracts]
            original_write_workbook = aggregator.single_exporter.write_workbook
            workbook_calls = 0

            def fail_after_one_workbook(*arguments: object) -> None:
                nonlocal workbook_calls
                workbook_calls += 1
                if workbook_calls == 2:
                    raise RuntimeError("synthetic workbook failure")
                original_write_workbook(*arguments)

            with (
                patch.object(aggregator, "COMPANY_CONTRACTS", contracts),
                patch.object(
                    aggregator.single_exporter,
                    "write_workbook",
                    side_effect=fail_after_one_workbook,
                ),
            ):
                with self.assertRaisesRegex(RuntimeError, "synthetic workbook"):
                    aggregator.publish_package(args, companies)

            self.assertFalse(args.output_dir.exists())
            self.assertEqual([], list(root.glob(".four-company-people.staging-*")))

    def _small_contracts(self) -> tuple[aggregator.CompanyContract, ...]:
        return tuple(
            aggregator.CompanyContract(contract.code, contract.name, 2, 1, 1)
            for contract in aggregator.COMPANY_CONTRACTS
        )

    def _collected(
        self, contract: aggregator.CompanyContract
    ) -> aggregator.CollectedCompany:
        organizations = [
            {
                "organizationCode": "ROOT",
                "name": contract.name,
                "parentOrganizationCode": "",
                "organizationType": "COMPANY",
                "effectiveFrom": "2026-08-05",
            },
            {
                "organizationCode": "D001",
                "name": "Synthetic Department",
                "parentOrganizationCode": "ROOT",
                "organizationType": "DEPARTMENT",
                "effectiveFrom": "2026-08-05",
            },
        ]
        employees = [
            {
                "employeeNumber": f"{contract.code}0001",
                "externalEmployeeId": "",
                "displayName": "Synthetic Person",
                "effectiveFrom": "2026-08-05",
            }
        ]
        employments = [
            {
                "employeeNumber": f"{contract.code}0001",
                "organizationCode": "D001",
                "startDate": "2026-08-05",
                "terminationDate": "",
            }
        ]
        return aggregator.CollectedCompany(
            contract, organizations, employees, employments
        )

    def _assert_permissions(self, output: Path) -> None:
        for path in [output, *output.rglob("*")]:
            expected = 0o700 if path.is_dir() else 0o600
            self.assertEqual(expected, stat.S_IMODE(path.stat().st_mode), path)


if __name__ == "__main__":
    unittest.main()
