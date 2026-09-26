from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "roster_org_cutover.py"
SPEC = importlib.util.spec_from_file_location("roster_org_cutover", SCRIPT)
assert SPEC and SPEC.loader
cutover = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = cutover
SPEC.loader.exec_module(cutover)


class RosterOrgCutoverTest(unittest.TestCase):
    def test_short_name_strips_parent_prefix(self) -> None:
        self.assertEqual(
            cutover.short_name("服务中心-工程一部", "服务中心", "服务中心"),
            "工程一部",
        )
        self.assertEqual(
            cutover.short_name("工程一部-MATCH组", "服务中心-工程一部", "工程一部"),
            "MATCH组",
        )
        self.assertEqual(
            cutover.short_name(
                "工程一部-MATCH组-MATCH1组",
                "工程一部-MATCH组",
                "MATCH组",
            ),
            "MATCH1组",
        )
        self.assertEqual(
            cutover.short_name("工程二部-RF-B组", "服务中心-工程二部", "工程二部"),
            "RF-B组",
        )
        self.assertEqual(cutover.short_name("董事长室", None, None), "董事长")
        self.assertEqual(
            cutover.short_name("上海测试组", "上海RD1", "RD1"),
            "测试组",
        )

    def test_flatten_builds_roster_path(self) -> None:
        tree = [{
            "organizationId": "co",
            "name": "江苏神州半导体科技股份有限公司",
            "organizationType": "COMPANY",
            "children": [{
                "organizationId": "svc",
                "name": "服务中心",
                "organizationType": "DEPARTMENT",
                "children": [{
                    "organizationId": "eng",
                    "name": "服务中心-工程一部",
                    "organizationType": "DEPARTMENT",
                    "children": [{
                        "organizationId": "match",
                        "name": "工程一部-MATCH组",
                        "organizationType": "DEPARTMENT",
                        "children": [{
                            "organizationId": "m1",
                            "name": "工程一部-MATCH组-MATCH1组",
                            "organizationType": "TEAM",
                            "children": [],
                        }],
                    }],
                }],
            }],
        }]
        flat = cutover.flatten_live_tree(tree)
        leaf = next(item for item in flat if item.organization_id == "m1")
        self.assertEqual(leaf.path, ("服务中心", "工程一部", "MATCH组", "MATCH1组"))

    def test_juneng_drops_research_wrapper(self) -> None:
        tree = [{
            "organizationId": "co",
            "name": "上海晟州聚能半导体科技有限公司",
            "organizationType": "COMPANY",
            "children": [{
                "organizationId": "rd",
                "name": "上海研发部",
                "organizationType": "DEPARTMENT",
                "children": [{
                    "organizationId": "rd1",
                    "name": "上海RD1",
                    "organizationType": "DEPARTMENT",
                    "children": [{
                        "organizationId": "test",
                        "name": "上海测试组",
                        "organizationType": "DEPARTMENT",
                        "children": [],
                    }],
                }],
            }],
        }]
        flat = cutover.flatten_live_tree(tree)
        leaf = next(item for item in flat if item.organization_id == "test")
        self.assertEqual(leaf.path, ("RD1", "测试组"))

    def test_protected_logins_and_corrections(self) -> None:
        self.assertEqual(cutover.NUMBER_CORRECTIONS["SZT0687"], "SZST0687")
        self.assertEqual(cutover.NUMBER_CORRECTIONS["SZST0567"], "SZSZ0000")
        self.assertIn("SZST0007", cutover.PROTECTED_USERNAMES)
        self.assertIn("SZST0598", cutover.TERMINATIONS)

    def test_reconcile_terminations_keep_employee_id(self) -> None:
        result = cutover.reconcile(
            roster=[],
            exempt=[],
            live_orgs=[
                cutover.LiveOrg(
                    "co",
                    "江苏神州半导体科技股份有限公司",
                    "江苏神州半导体科技股份有限公司",
                    None,
                    "江苏神州半导体科技股份有限公司",
                    (),
                    "COMPANY",
                ),
            ],
            employees=[{
                "employeeId": "e1",
                "employeeNumber": "SZST0598",
                "displayName": "范康搏",
                "organizationId": "x",
                "organizationName": "装配组",
            }],
            accounts=[{
                "accountId": "a1",
                "username": "SZST0598",
                "displayName": "范康搏",
                "firstPasswordChangeRequired": True,
            }],
        )
        self.assertEqual(result.terminate[0]["employeeId"], "e1")
        self.assertEqual(result.account_sync, [])


if __name__ == "__main__":
    unittest.main()
