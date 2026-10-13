from __future__ import annotations

import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.release.verify_wave9 import (
    EXTERNAL_GATES,
    GateResult,
    build_commands,
    run_orchestration,
    summarize,
)


class Wave9OrchestrationTest(unittest.TestCase):
    def test_command_set_is_local_and_contains_all_contract_gates(self) -> None:
        commands = build_commands(
            Path("."),
            run_id="w9-local-test",
            commit="a" * 40,
        )

        self.assertEqual(
            {command.gate for command in commands},
            {
                "openspec_strict",
                "w9_unit_contracts",
                "threat_static",
                "native_template_static",
                "browser_matrix_contract",
                "release_manifest_contract",
            },
        )
        joined = " ".join(part for command in commands for part in command.argv)
        self.assertNotIn("mysql_backup.py --execute", joined)
        self.assertNotIn("mysql_restore.py --execute", joined)
        self.assertNotIn("http_load.py --execute", joined)
        self.assertNotIn("https://", joined)

    def test_harness_pass_never_promotes_release(self) -> None:
        results = [
            GateResult("unit", ["unit"], 0, "PASS", "0" * 64, "ok"),
            GateResult("static", ["static"], 0, "PASS", "1" * 64, "ok"),
        ]

        summary = summarize(
            run_id="w9-local-test",
            commit="a" * 40,
            dirty=False,
            results=results,
        )

        self.assertEqual(summary["harness_status"], "PASS")
        self.assertTrue(summary["harness_ready"])
        self.assertEqual(summary["release_verdict"], "NOT_VERIFIED")
        self.assertFalse(summary["release_authorized"])
        self.assertEqual(
            [gate["gate"] for gate in summary["external_gates"]],
            list(EXTERNAL_GATES),
        )
        self.assertTrue(
            all(gate["status"] == "NOT_VERIFIED" for gate in summary["external_gates"])
        )

    def test_failed_local_gate_propagates_without_running_external_actions(self) -> None:
        calls: list[list[str]] = []

        def runner(
            argv: list[str] | tuple[str, ...], _repository: Path
        ) -> subprocess.CompletedProcess[str]:
            calls.append(list(argv))
            return subprocess.CompletedProcess(
                list(argv),
                1
                if any(part.endswith("/native_preflight.py") for part in argv)
                else 0,
                stdout="local output",
                stderr="",
            )

        with (
            patch(
                "scripts.release.verify_wave9.repository_commit",
                return_value="b" * 40,
            ),
            patch(
                "scripts.release.verify_wave9.repository_dirty",
                return_value=True,
            ),
        ):
            result = run_orchestration(
                Path("."),
                run_id="w9-local-test",
                runner=runner,
            )

        self.assertEqual(result["harness_status"], "FAIL")
        self.assertEqual(result["release_verdict"], "NOT_VERIFIED")
        self.assertEqual(len(calls), 6)
        self.assertTrue(
            all(
                "mysql_backup.py" not in command
                and "mysql_restore.py" not in command
                and "http_load.py" not in command
                for command in calls
            )
        )


if __name__ == "__main__":
    unittest.main()
