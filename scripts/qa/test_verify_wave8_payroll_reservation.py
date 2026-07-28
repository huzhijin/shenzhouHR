#!/usr/bin/env python3
"""Dependency-free tests for the W8 reservation verifier."""

from __future__ import annotations

import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_wave8_payroll_reservation as verifier


class Wave8PayrollReservationVerifierTest(unittest.TestCase):
    def test_backend_and_migration_text_suffixes_are_in_scope(self) -> None:
        self.assertIn(".java", verifier.TEXT_SUFFIXES)
        self.assertIn(".sql", verifier.TEXT_SUFFIXES)

    def test_nfkc_casefold_catches_case_width_and_separator_variants(self) -> None:
        for value in (
            "PAYROLL",
            "Pay_Roll",
            "ＰａＹＲＯＬＬ",
            "pay/roll",
            "PAY-SLIPS",
            "薪资",
            "工资条",
        ):
            with self.subTest(value=value):
                self.assertGreaterEqual(verifier.deny_match_count(value), 1)
        self.assertEqual(verifier.deny_match_count("考勤设置"), 0)

    def test_scan_reports_only_safe_path_and_line(self) -> None:
        with tempfile.TemporaryDirectory(prefix="w8-scan-") as root:
            path = Path(root) / "surface.ts"
            sensitive_tail = "opaque-sensitive-value"
            path.write_text(
                "const safe = true;\n"
                f"const hidden = 'ＰＡＹＲＯＬＬ-{sensitive_tail}';\n",
                encoding="utf-8",
            )

            findings = verifier.scan_files((path,), verifier.DENY_PATTERN)
            rendered = verifier.format_findings("hit", findings)

            self.assertEqual(
                findings,
                [verifier.Finding("surface.ts", 2)],
            )
            self.assertNotIn(sensitive_tail, rendered)

    def test_prohibited_scope_pattern_detects_excluded_behaviors(self) -> None:
        for value in (
            "payslip",
            "tax",
            "social_insurance",
            "housing-fund",
            "bank account",
            "payment/file",
            "个税",
            "银行文件",
        ):
            with self.subTest(value=value):
                self.assertIsNotNone(
                    verifier.PROHIBITED_IMPLEMENTATION_PATTERN.search(
                        verifier.normalize(value)
                    )
                )

    def test_frontend_test_path_classification_is_exact(self) -> None:
        source = verifier.REPOSITORY_ROOT / verifier.FRONTEND_SOURCE
        self.assertTrue(verifier.is_frontend_test_file(
            source / "app" / "App.test.tsx"
        ))
        self.assertTrue(verifier.is_frontend_test_file(
            source / "test" / "setup.ts"
        ))
        self.assertFalse(verifier.is_frontend_test_file(
            source / "app" / "App.tsx"
        ))

    def test_pattern_does_not_match_attendance_control(self) -> None:
        self.assertIsNone(re.search(
            verifier.DENY_PATTERN,
            verifier.normalize("考勤月结只读快照"),
        ))


if __name__ == "__main__":
    unittest.main()
