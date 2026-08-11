from __future__ import annotations

import hashlib
import io
import json
import os
import stat
import tempfile
import unittest
from collections import deque
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from scripts.release import export_deli_readonly_snapshot as exporter


RUN_ID = "deli-snapshot-test-001"
APP_KEY = "synthetic-app-key"
APP_SECRET = "synthetic-app-secret"


class QueueTransport:
    def __init__(self, responses: list[exporter.ApiResponse]) -> None:
        self.responses = deque(responses)
        self.requests: list[exporter.ApiRequest] = []

    def __call__(self, request: exporter.ApiRequest) -> exporter.ApiResponse:
        self.requests.append(request)
        if not self.responses:
            raise AssertionError("unexpected fake Deli request")
        return self.responses.popleft()


def response(payload: object, status: int = 200) -> exporter.ApiResponse:
    return exporter.ApiResponse(
        status_code=status,
        body=(
            json.dumps(
                payload,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            + "\n"
        ).encode("utf-8"),
    )


def directory_page(rows: object) -> exporter.ApiResponse:
    return response({"code": 0, "msg": "", "data": {"rows": rows}})


def offset_rows(prefix: str, start: int, count: int) -> list[dict[str, str]]:
    return [
        {
            "id": f"{prefix}-USER-{index:03d}",
            "employee_num": f"{prefix}-EMP-{index:03d}",
        }
        for index in range(start, start + count)
    ]


class ExportDeliReadonlySnapshotTest(unittest.TestCase):
    def _secure_env(self, root: Path) -> Path:
        os.chmod(root, 0o700)
        env_file = root / "deli.env"
        env_file.write_text(
            "\n".join(
                (
                    f"DELI_EPLUS_BASE_URL={exporter.OFFICIAL_ORIGIN}",
                    f"DELI_EPLUS_APP_KEY={APP_KEY}",
                    f"DELI_EPLUS_APP_SECRET={APP_SECRET}",
                    "DELI_EPLUS_ENABLED=true",
                    "DELI_EPLUS_PAGE_SIZE=500",
                    "DELI_EPLUS_SOURCE_TIME_ZONE=Asia/Shanghai",
                    "",
                )
            ),
            encoding="utf-8",
        )
        os.chmod(env_file, 0o600)
        return env_file

    def _happy_transport(self) -> QueueTransport:
        return QueueTransport(
            [
                directory_page(offset_rows("DEPT", 0, 100)),
                directory_page(offset_rows("DEPT", 100, 1)),
                directory_page(offset_rows("PERSON", 0, 100)),
                directory_page(offset_rows("PERSON", 100, 1)),
                response(
                    {
                        "code": 0,
                        "msg": "",
                        "data": {
                            "next_id": 42,
                            "data": [
                                {"id": "CHECK-2", "check_time": 1_700_000_100},
                                {"id": "CHECK-1", "check_time": "1700000000"},
                            ],
                        },
                    }
                ),
                response(
                    {
                        "code": 0,
                        "msg": "",
                        "data": {"next_id": 42, "data": []},
                    }
                ),
            ]
        )

    def test_plan_is_offline_and_source_contains_no_initialization_command(self) -> None:
        plan = exporter.readonly_plan(RUN_ID)

        self.assertEqual(0, plan["network_requests"])
        self.assertEqual(0, plan["initialization_requests"])
        self.assertEqual(0, plan["write_requests"])
        self.assertEqual(
            {
                exporter.DEPARTMENT_PATH,
                exporter.EMPLOYEE_PATH,
                exporter.CHECKIN_PATH,
            },
            {operation["path"] for operation in plan["allowed_operations"]},
        )
        forbidden_command = exporter.CHECKIN_COMMAND + "_init"
        self.assertNotIn(forbidden_command, Path(exporter.__file__).read_text())

        stdout = io.StringIO()
        with patch.object(exporter, "_http_post") as http_post:
            with redirect_stdout(stdout):
                exit_code = exporter.main(["--run-id", RUN_ID])
        self.assertEqual(0, exit_code)
        http_post.assert_not_called()
        self.assertEqual(0, json.loads(stdout.getvalue())["network_requests"])

    def test_credentials_require_current_user_0600_and_are_always_redacted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = self._secure_env(root)

            credentials = exporter.read_credentials(env_file, Path.cwd())

            self.assertEqual(APP_KEY, credentials.app_key)
            self.assertEqual(APP_SECRET, credentials.app_secret)
            self.assertNotIn(APP_KEY, repr(credentials))
            self.assertNotIn(APP_SECRET, repr(credentials))

            os.chmod(env_file, 0o640)
            with self.assertRaisesRegex(exporter.ContractError, "0600"):
                exporter.read_credentials(env_file, Path.cwd())

            os.chmod(env_file, 0o600)
            link = root / "deli-link.env"
            link.symlink_to(env_file)
            with self.assertRaisesRegex(exporter.ContractError, "symlink"):
                exporter.read_credentials(link, Path.cwd())

            env_file.write_text(
                f"DELI_EPLUS_APP_KEY={APP_KEY}\n"
                f"DELI_EPLUS_APP_SECRET={APP_SECRET}\n"
                "UNSAFE_SHELL_VALUE=$(id)\n",
                encoding="utf-8",
            )
            os.chmod(env_file, 0o600)
            with self.assertRaisesRegex(
                exporter.SnapshotError, "unsupported variable"
            ):
                exporter.read_credentials(env_file, Path.cwd())

    def test_complete_export_is_allowlisted_atomic_and_permission_hardened(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = self._secure_env(root)
            output = root / "snapshots"
            transport = self._happy_transport()

            result = exporter.export_snapshot(
                repository_root=Path.cwd(),
                run_id=RUN_ID,
                env_file=env_file,
                output_directory=output,
                transport=transport,
            )

            self.assertEqual(output / RUN_ID, result.bundle_directory)
            self.assertEqual(
                {
                    "departments": 101,
                    "employees": 101,
                    "checkins": 2,
                    "total_rows": 204,
                },
                result.manifest["counts"],
            )
            self.assertEqual(
                {"departments": 2, "employees": 2, "checkins": 2},
                result.manifest["page_counts"],
            )
            self.assertEqual(
                1_700_000_000,
                result.manifest["checkin_time_range"]["minimum_epoch_second"],
            )
            self.assertEqual(
                1_700_000_100,
                result.manifest["checkin_time_range"]["maximum_epoch_second"],
            )
            self.assertEqual(0, result.manifest["initialization_requests"])
            self.assertEqual(0, result.manifest["write_requests"])
            self.assertFalse(result.manifest["credential_values_persisted"])

            self.assertEqual(6, len(transport.requests))
            self.assertEqual(
                [
                    "departments",
                    "departments",
                    "employees",
                    "employees",
                    "checkins",
                    "checkins",
                ],
                [request.dataset for request in transport.requests],
            )
            self.assertEqual(
                {0, 100},
                {
                    request.payload["offset"]
                    for request in transport.requests
                    if request.dataset == "departments"
                },
            )
            self.assertEqual(
                [0, 42],
                [
                    request.payload["next_id"]
                    for request in transport.requests
                    if request.dataset == "checkins"
                ],
            )
            for request in transport.requests:
                self.assertEqual("POST", request.method)
                self.assertIn(
                    request.path,
                    {
                        exporter.DEPARTMENT_PATH,
                        exporter.EMPLOYEE_PATH,
                        exporter.CHECKIN_PATH,
                    },
                )
                self.assertNotIn(APP_KEY, repr(request))
                self.assertNotIn(APP_SECRET, repr(request))
                if request.dataset == "checkins":
                    self.assertEqual(
                        exporter.CHECKIN_MODULE,
                        request.headers["Api-Module"],
                    )
                    self.assertEqual(
                        exporter.CHECKIN_COMMAND,
                        request.headers["Api-Cmd"],
                    )
                else:
                    self.assertNotIn("Api-Module", request.headers)
                    self.assertNotIn("Api-Cmd", request.headers)

            self.assertEqual(0o700, stat.S_IMODE(output.stat().st_mode))
            for candidate in result.bundle_directory.rglob("*"):
                expected_mode = 0o700 if candidate.is_dir() else 0o600
                self.assertEqual(
                    expected_mode,
                    stat.S_IMODE(candidate.stat().st_mode),
                    candidate,
                )
            manifest_path = result.bundle_directory / "manifest.json"
            manifest_digest = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
            self.assertEqual(result.manifest_sha256, manifest_digest)
            self.assertEqual(
                f"{manifest_digest}  manifest.json\n",
                (result.bundle_directory / "manifest.sha256").read_text(),
            )
            for page in result.manifest["pages"]:
                raw_path = result.bundle_directory / page["file"]
                self.assertEqual(
                    page["response_sha256"],
                    hashlib.sha256(raw_path.read_bytes()).hexdigest(),
                )
            all_bytes = b"".join(
                candidate.read_bytes()
                for candidate in result.bundle_directory.rglob("*")
                if candidate.is_file()
            )
            self.assertNotIn(APP_KEY.encode(), all_bytes)
            self.assertNotIn(APP_SECRET.encode(), all_bytes)
            self.assertFalse(
                any("partial" in candidate.name for candidate in output.iterdir())
            )

    def test_invalid_later_page_removes_every_staged_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = self._secure_env(root)
            output = root / "snapshots"
            transport = QueueTransport(
                [
                    directory_page([]),
                    directory_page("not-a-row-array"),
                ]
            )

            with self.assertRaisesRegex(
                exporter.SnapshotError, "rows violate"
            ):
                exporter.export_snapshot(
                    repository_root=Path.cwd(),
                    run_id=RUN_ID,
                    env_file=env_file,
                    output_directory=output,
                    transport=transport,
                )

            self.assertFalse((output / RUN_ID).exists())
            self.assertEqual([], list(output.iterdir()))

    def test_cursor_loop_and_credential_reflection_never_publish(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = self._secure_env(root)
            output = root / "snapshots"
            loop_transport = QueueTransport(
                [
                    directory_page([]),
                    directory_page([]),
                    response(
                        {
                            "code": 0,
                            "data": {
                                "next_id": 0,
                                "data": [{"check_time": 1_700_000_000}],
                            },
                        }
                    ),
                ]
            )
            with self.assertRaisesRegex(exporter.SnapshotError, "cursor repeated"):
                exporter.export_snapshot(
                    repository_root=Path.cwd(),
                    run_id=RUN_ID,
                    env_file=env_file,
                    output_directory=output,
                    transport=loop_transport,
                )
            self.assertEqual([], list(output.iterdir()))

            reflected = QueueTransport(
                [
                    response(
                        {
                            "code": 0,
                            "data": {"rows": []},
                            "unsafe_echo": APP_SECRET,
                        }
                    )
                ]
            )
            with self.assertRaises(exporter.SnapshotError) as captured:
                exporter.export_snapshot(
                    repository_root=Path.cwd(),
                    run_id=RUN_ID,
                    env_file=env_file,
                    output_directory=output,
                    transport=reflected,
                )
            self.assertNotIn(APP_KEY, str(captured.exception))
            self.assertNotIn(APP_SECRET, str(captured.exception))
            self.assertEqual([], list(output.iterdir()))

    def test_existing_bundle_is_rejected_before_any_transport_call(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = self._secure_env(root)
            output = root / "snapshots"
            output.mkdir(mode=0o700)
            final = output / RUN_ID
            final.mkdir(mode=0o700)
            marker = final / "keep"
            marker.write_text("keep", encoding="utf-8")
            os.chmod(marker, 0o600)
            transport = QueueTransport([])

            with self.assertRaisesRegex(exporter.SnapshotError, "overwrite"):
                exporter.export_snapshot(
                    repository_root=Path.cwd(),
                    run_id=RUN_ID,
                    env_file=env_file,
                    output_directory=output,
                    transport=transport,
                )

            self.assertEqual([], transport.requests)
            self.assertEqual("keep", marker.read_text(encoding="utf-8"))

    def test_arbitrary_dataset_cannot_reach_request_builder(self) -> None:
        credentials = exporter.Credentials(APP_KEY, APP_SECRET)
        with self.assertRaisesRegex(exporter.SnapshotError, "unsupported"):
            exporter._build_request(
                "arbitrary-write-operation",
                {"offset": 0, "limit": 100},
                credentials,
            )


if __name__ == "__main__":
    unittest.main()
