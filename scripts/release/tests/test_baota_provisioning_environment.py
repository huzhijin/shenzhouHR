from __future__ import annotations

import base64
import os
import re
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from urllib.parse import unquote


REPOSITORY = Path(__file__).resolve().parents[3]
HELPER = REPOSITORY / "deploy/baota/scripts/provisioning-env.sh"
PROVISION = REPOSITORY / "deploy/baota/mysql/provision.sh"
VERIFY = REPOSITORY / "deploy/baota/scripts/verify.sh"
MIGRATE = REPOSITORY / "deploy/baota/scripts/migrate.sh"
DATABASE_PREFLIGHT = REPOSITORY / "deploy/baota/scripts/database-preflight.sh"
INITIAL_ADMIN = REPOSITORY / "deploy/baota/scripts/initial-admin.sh"
INSTALL = REPOSITORY / "deploy/baota/install.sh"
UPGRADE = REPOSITORY / "deploy/baota/upgrade.sh"
ENV_EXAMPLE = REPOSITORY / "deploy/baota/env/shenzhouhr.env.example"
BUILD_RELEASE = REPOSITORY / "deploy/baota/build-release.sh"
CUSTOMER_GUIDE = REPOSITORY / "docs/deployment/baota-deployment-guide.md"
NGINX_HTTP = REPOSITORY / "deploy/baota/nginx/shenzhouhr-site-http.conf"
NGINX_HTTPS = REPOSITORY / "deploy/baota/nginx/shenzhouhr-site.conf"
NGINX_HTTP_SNIPPET = REPOSITORY / "deploy/baota/nginx/nginx-http-snippet.conf"
SYNTHETIC_PEPPER_A = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
SYNTHETIC_PEPPER_B = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI"


class BaotaProvisioningEnvironmentTest(unittest.TestCase):
    def test_customer_ports_and_baota_manager_are_consistent_across_release(self) -> None:
        install = INSTALL.read_text(encoding="utf-8")
        provision = PROVISION.read_text(encoding="utf-8")
        verify = VERIFY.read_text(encoding="utf-8")
        upgrade = UPGRADE.read_text(encoding="utf-8")
        env_example = ENV_EXAMPLE.read_text(encoding="utf-8")
        build_release = BUILD_RELEASE.read_text(encoding="utf-8")

        self.assertIn('BACKEND_PORT="${BACKEND_PORT:-8080}"', install)
        self.assertIn('BACKEND_ADDRESS="${BACKEND_ADDRESS:-0.0.0.0}"', install)
        self.assertIn('SITE_PORT="${SITE_PORT:-23272}"', install)
        self.assertIn('PROCESS_MANAGER="${PROCESS_MANAGER:-baota}"', install)
        self.assertIn('--backend-port "$BACKEND_PORT"', install)
        self.assertIn('--backend-address "$BACKEND_ADDRESS"', install)
        self.assertIn('ss -H -ltn "sport = :$BACKEND_PORT"', install)
        self.assertIn('BACKEND_PORT="8080"', provision)
        self.assertIn('BACKEND_ADDRESS="0.0.0.0"', provision)
        self.assertIn('SESSION_COOKIE_SECURE="false"', provision)
        self.assertEqual(2, provision.count("SHENZHOUHR_SERVER_PORT=$BACKEND_PORT"))
        self.assertEqual(2, provision.count("SHENZHOUHR_SERVER_ADDRESS=$BACKEND_ADDRESS"))
        self.assertIn("SHENZHOUHR_SERVER_PORT:-8080", verify)
        self.assertIn("SHENZHOUHR_SERVER_PORT:-8080", upgrade)
        self.assertIn("SHENZHOUHR_SERVER_PORT=8080", env_example)
        self.assertIn("SHENZHOUHR_SERVER_ADDRESS=0.0.0.0", env_example)
        self.assertIn("SHENZHOUHR_SESSION_COOKIE_SECURE=false", env_example)
        self.assertIn("printf 'backend_port_default=%s\\n' '8080'", build_release)
        self.assertIn(
            "printf 'backend_address_default=%s\\n' '0.0.0.0'",
            build_release,
        )
        self.assertIn("printf 'site_port_default=%s\\n' '23272'", build_release)
        self.assertIn("printf 'process_manager_default=%s\\n' 'baota'", build_release)
        self.assertIn(
            "printf 'session_cookie_secure_default=%s\\n' 'false'",
            build_release,
        )

    def test_customer_package_rejects_systemd_and_excludes_its_template(self) -> None:
        install = INSTALL.read_text(encoding="utf-8")
        upgrade = UPGRADE.read_text(encoding="utf-8")
        build_release = BUILD_RELEASE.read_text(encoding="utf-8")

        self.assertIn("only supports PROCESS_MANAGER=baota", install)
        self.assertIn("only supports PROCESS_MANAGER=baota", upgrade)
        self.assertNotIn("systemctl enable --now", install)
        self.assertNotIn("systemctl start", upgrade)
        self.assertIn(
            'rm -rf -- "$RELEASE_ROOT/deploy/baota/systemd"', build_release
        )
        self.assertIn("Baota preparation completed", install)
        self.assertIn("Domain / external mapping: leave blank", install)
        self.assertIn('PROCESS_MANAGER="${PROCESS_MANAGER:-baota}"', upgrade)
        self.assertIn("Stop Java project 'kaoqinweb' in Baota", upgrade)

    def test_release_explicitly_excludes_customer_opening_business_data(self) -> None:
        install = INSTALL.read_text(encoding="utf-8")
        build_release = BUILD_RELEASE.read_text(encoding="utf-8")
        readme = (REPOSITORY / "deploy/baota/README.md").read_text(encoding="utf-8")

        self.assertIn("initial_business_data=%s", build_release)
        self.assertIn(
            "not_included_import_approved_files_separately",
            build_release,
        )
        self.assertIn("Opening business data is not embedded", install)
        self.assertIn("不包含开发机或浏览器演示中的组织", readme)
        self.assertIn("组织 → 员工 → 任职", readme)

    def test_migration_boots_loopback_web_context_without_background_worker(
        self,
    ) -> None:
        script = MIGRATE.read_text(encoding="utf-8")

        self.assertNotIn("--spring.main.web-application-type=none", script)
        self.assertIn("--server.address=127.0.0.1", script)
        self.assertIn("--server.port=0", script)
        self.assertIn(
            "--shenzhouhr.reporting.export-worker-enabled=false",
            script,
        )
        self.assertIn("spring-boot-flyway-", script)

    def test_baota_jdbc_url_supports_local_mysql_caching_sha2_auth(self) -> None:
        script = PROVISION.read_text(encoding="utf-8")

        self.assertIn("allowPublicKeyRetrieval=true", script)
        self.assertIn('"$DB_HOST" == "127.0.0.1"', script)
        self.assertIn('"$DB_HOST" == "localhost"', script)
        self.assertIn('--backend-address) BACKEND_ADDRESS="$2"', script)
        self.assertIn('"$BACKEND_ADDRESS" == "0.0.0.0"', script)
        self.assertIn("ALTER DATABASE", script)
        self.assertNotIn("CREATE DATABASE", script)
        self.assertIn("Database $DB_NAME does not exist", script)
        self.assertIn("Database $DB_NAME is not empty", script)
        self.assertIn("existing ShenzhouHR MySQL service account", script)
        self.assertNotIn("CREATE USER IF NOT EXISTS", script)
        self.assertNotIn("REVOKE ALL PRIVILEGES", script)
        self.assertIn("cleanup_provision", script)
        self.assertIn("DROP USER IF EXISTS", script)

    def test_install_requires_the_registered_baota_site_before_database_changes(
        self,
    ) -> None:
        install = INSTALL.read_text(encoding="utf-8")

        self.assertIn(
            'VHOST_CONFIG="$NGINX_VHOST_DIR/$DOMAIN.conf"', install
        )
        self.assertNotIn('VHOST_CONFIG="$NGINX_VHOST_DIR/shenzhouhr.conf"', install)
        self.assertIn("refusing to create an unmanaged vhost", install)
        self.assertIn("does not listen on $SITE_PORT", install)
        self.assertIn("does not use root $WEB_ROOT", install)
        self.assertIn("rollback_vhost_on_install_failure", install)
        self.assertLess(
            install.index("refusing to create an unmanaged vhost"),
            install.index('"$BAOTA_ROOT/mysql/provision.sh"'),
        )
        self.assertLess(
            install.rindex(
                '"$NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -t'
            ),
            install.index('"$BAOTA_ROOT/mysql/provision.sh"'),
        )

    def test_baota_nginx_does_not_log_query_strings(self) -> None:
        for template in (NGINX_HTTP, NGINX_HTTPS):
            with self.subTest(template=template):
                text = template.read_text(encoding="utf-8")
                self.assertIn("access_log off;", text)
                self.assertNotIn("access_log /www/wwwlogs/", text)
        snippet = NGINX_HTTP_SNIPPET.read_text(encoding="utf-8")
        self.assertNotIn('"$request"', snippet)
        self.assertIn("$request_method $uri $server_protocol", snippet)

    def test_verify_compares_numeric_flyway_version_to_release_manifest(self) -> None:
        script = VERIFY.read_text(encoding="utf-8")

        self.assertIn("BUILD-MANIFEST.txt", script)
        self.assertIn("MAX(CAST(version AS UNSIGNED))", script)
        self.assertIn('"$FLYWAY_VERSION" == "$EXPECTED_FLYWAY_VERSION"', script)

    def test_release_contains_only_current_customer_deployment_docs(self) -> None:
        script = BUILD_RELEASE.read_text(encoding="utf-8")

        self.assertIn(
            '"$REPO_ROOT/docs/deployment/baota-deployment-guide.md"', script
        )
        self.assertIn(
            '"$SCRIPT_DIR/README.md" "$RELEASE_ROOT/DEPLOYMENT-NOTES.md"',
            script,
        )
        self.assertIn("deployment_guide=%s", script)
        self.assertIn("deployment_notes=%s", script)
        self.assertIn("baota_min_version=%s", script)
        self.assertNotIn('"$RELEASE_ROOT/docs/user-guide"', script)
        self.assertNotIn('copy_markdown_tree', script)
        self.assertNotIn("2026-08-06-customer-deployment-readiness.md", script)
        self.assertNotIn("2026-08-06-reporting-business-confirmation.md", script)
        self.assertNotIn("2026-08-06-reporting-code-audit.md", script)

    def test_customer_guide_keeps_destructive_cleanup_fail_closed(self) -> None:
        guide = CUSTOMER_GUIDE.read_text(encoding="utf-8")

        self.assertIn("PRE-DROP-MATERIAL-READY", guide)
        self.assertIn("FINAL-SHA256SUMS", guide)
        self.assertIn("SHOW CREATE TABLE", guide)
        self.assertIn("逐表全量内容 SHA-256", guide)
        self.assertIn("information_schema.USER_PRIVILEGES", guide)
        self.assertIn("old-db-usernames.txt", guide)
        self.assertIn("NO-OLD-HR-NGINX", guide)
        self.assertIn("INFO：关联路径清单为空", guide)
        self.assertNotIn("STOP：关联路径清单为空", guide)

    def test_customer_guide_verifies_archive_before_extraction(self) -> None:
        guide = CUSTOMER_GUIDE.read_text(encoding="utf-8")
        build_release = BUILD_RELEASE.read_text(encoding="utf-8")

        outer_hash = guide.index('if ! sha256sum -c "$checksum_name"')
        extraction = guide.index('if ! tar --no-same-owner -xzf "$archive_name"')
        self.assertLess(outer_hash, extraction)
        self.assertIn("归档包含目标发布目录以外的路径", guide)
        self.assertIn("tar --uid 0 --gid 0 --uname root --gname root", build_release)
        self.assertIn("tar --owner=0 --group=0 --numeric-owner", build_release)

    def test_release_runs_full_build_gates_and_records_package_paths(self) -> None:
        script = BUILD_RELEASE.read_text(encoding="utf-8")

        self.assertIn("npm run check", script)
        self.assertIn("./mvnw clean package", script)
        self.assertNotIn("-DskipTests", script)
        self.assertIn(
            "README.md api backend frontend deploy docs scripts",
            script,
        )
        self.assertIn(
            "printf 'backend_jar=backend/shenzhou-hr.jar\\n'", script
        )
        self.assertIn("printf 'frontend_dir=web\\n'", script)

    def test_customer_release_rejects_dirty_source_by_default(self) -> None:
        script = BUILD_RELEASE.read_text(encoding="utf-8")
        readme = (REPOSITORY / "deploy/baota/README.md").read_text(
            encoding="utf-8"
        )

        self.assertIn('ALLOW_DIRTY_RELEASE="${ALLOW_DIRTY_RELEASE:-false}"', script)
        self.assertIn('true|false)', script)
        self.assertIn(
            '"$SOURCE_TREE_STATE" != "clean" '
            '&& "$ALLOW_DIRTY_RELEASE" != "true"',
            script,
        )
        self.assertIn("clean, committed source tree", script)
        self.assertIn("正式客户包只能从已经提交且工作树干净的源码生成", readme)
        self.assertIn("不要使用", readme)
        self.assertIn("ALLOW_DIRTY_RELEASE=true", readme)

    def test_customer_markdown_links_resolve_inside_release_package(self) -> None:
        package_markdown = {
            (REPOSITORY / "docs/deployment/baota-deployment-guide.md").resolve(),
            (REPOSITORY / "deploy/baota/README.md").resolve(),
        }
        markdown_link = re.compile(r"\]\(([^)]+)\)")

        for document in sorted(package_markdown):
            for raw_target in markdown_link.findall(
                document.read_text(encoding="utf-8")
            ):
                target = raw_target.strip().split(maxsplit=1)[0]
                if target.startswith(("#", "http://", "https://", "mailto:")):
                    continue
                relative_target = unquote(target.split("#", 1)[0])
                resolved_target = (document.parent / relative_target).resolve()
                with self.subTest(document=document, target=target):
                    self.assertIn(resolved_target, package_markdown)

    def test_credential_reading_entrypoints_disable_shell_trace(self) -> None:
        entrypoints = (
            PROVISION,
            MIGRATE,
            INITIAL_ADMIN,
            VERIFY,
            DATABASE_PREFLIGHT,
            INSTALL,
            UPGRADE,
        )

        for entrypoint in entrypoints:
            with self.subTest(entrypoint=entrypoint):
                first_lines = entrypoint.read_text(encoding="utf-8").splitlines()[:6]
                self.assertIn("set +x", first_lines)

    def test_install_and_upgrade_verify_release_before_loading_or_mutating(self) -> None:
        install = INSTALL.read_text(encoding="utf-8")
        upgrade = UPGRADE.read_text(encoding="utf-8")

        self.assertIn("sha256sum --check --quiet SHA256SUMS", install)
        self.assertIn("sha256sum --check --quiet SHA256SUMS", upgrade)
        self.assertLess(
            install.index("verify_release_integrity\nload_release_contract"),
            install.index('"$BAOTA_ROOT/mysql/provision.sh"'),
        )
        self.assertLess(
            upgrade.index("verify_release_integrity\nload_release_contract"),
            upgrade.index('source "$BAOTA_ROOT/scripts/provisioning-env.sh"'),
        )
        self.assertLess(
            upgrade.index('if ss -H -ltn "sport = :$BACKEND_PORT"'),
            upgrade.index('"$BAOTA_ROOT/scripts/database-preflight.sh"'),
        )

    def test_web_root_is_fixed_guarded_and_cleared_with_dotfiles(self) -> None:
        install = INSTALL.read_text(encoding="utf-8")
        upgrade = UPGRADE.read_text(encoding="utf-8")

        for script in (install, upgrade):
            self.assertIn('WEB_ROOT_BASE="/www/wwwroot"', script)
            self.assertIn('WEB_ROOT="$WEB_ROOT_BASE/$DOMAIN"', script)
            self.assertIn('! -L "$WEB_ROOT"', script)
            self.assertIn(
                'find "$WEB_ROOT" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +',
                script,
            )
            self.assertIn('chattr -i -- "$baota_user_ini"', script)
            self.assertIn("restore_baota_user_ini", script)
            self.assertNotIn('rm -rf -- "$WEB_ROOT"/*', script)
        self.assertNotIn('WEB_ROOT="${WEB_ROOT:-', upgrade)

    def test_database_preflight_accepts_exact_version_and_contiguous_history(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_env, fake_mysql, secret = self._database_preflight_fixture(
                root,
                mysql_version="8.0.45",
                flyway_status="30\t1\t30\t30\t0\t0",
            )

            result = subprocess.run(
                [
                    "bash",
                    "-x",
                    str(DATABASE_PREFLIGHT),
                    "--env-file",
                    str(app_env),
                    "--mysql-bin",
                    str(fake_mysql),
                    "--expected-mysql-version",
                    "8.0.45",
                    "--max-flyway-version",
                    "31",
                ],
                cwd=REPOSITORY,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Database preflight passed", result.stdout)
            self.assertNotIn(secret, result.stdout + result.stderr)

    def test_database_preflight_rejects_wrong_mysql_and_invalid_history(self) -> None:
        cases = (
            ("8.0.34", "30\t1\t30\t30\t0\t0", "expects exactly 8.0.45"),
            ("8.0.45", "2\t1\t3\t2\t0\t0", "contiguous, unique"),
            ("8.0.45", "30\t1\t30\t30\t0\t1", "failed migration"),
            ("8.0.45", "32\t1\t32\t32\t0\t0", "newer than release"),
        )
        for mysql_version, flyway_status, expected_error in cases:
            with self.subTest(expected_error=expected_error), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                app_env, fake_mysql, secret = self._database_preflight_fixture(
                    root,
                    mysql_version=mysql_version,
                    flyway_status=flyway_status,
                )

                result = subprocess.run(
                    [
                        "bash",
                        str(DATABASE_PREFLIGHT),
                        "--env-file",
                        str(app_env),
                        "--mysql-bin",
                        str(fake_mysql),
                        "--max-flyway-version",
                        "31",
                    ],
                    cwd=REPOSITORY,
                    capture_output=True,
                    text=True,
                    check=False,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected_error, result.stderr)
                self.assertNotIn(secret, result.stdout + result.stderr)

    def test_legacy_pair_is_populated_once_without_secret_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            app_env.write_text("APP_VALUE=present\n", encoding="utf-8")
            migrator_env.write_text("MIGRATOR_VALUE=present\n", encoding="utf-8")
            os.chmod(app_env, 0o640)
            os.chmod(migrator_env, 0o600)

            first = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )
            self.assertEqual(0, first.returncode, first.stderr)
            app_values = self._read_env(app_env)
            migrator_values = self._read_env(migrator_env)
            pepper = app_values["SHENZHOUHR_PROVISIONING_PEPPER"]
            self.assertEqual(pepper, migrator_values["SHENZHOUHR_PROVISIONING_PEPPER"])
            self.assertEqual(43, len(pepper))
            self.assertEqual(32, len(base64.urlsafe_b64decode(pepper + "=")))
            self.assertEqual("v1", app_values["SHENZHOUHR_PROVISIONING_KEY_ID"])
            self.assertEqual(
                "PT1H", app_values["SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW"]
            )
            self.assertNotIn(pepper, first.stdout + first.stderr)
            self.assertEqual(0o640, stat.S_IMODE(app_env.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(migrator_env.stat().st_mode))

            app_before = app_env.read_bytes()
            migrator_before = migrator_env.read_bytes()
            second = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual(app_before, app_env.read_bytes())
            self.assertEqual(migrator_before, migrator_env.read_bytes())
            self.assertNotIn(pepper, second.stdout + second.stderr)

    def test_existing_value_is_copied_to_missing_peer_without_rotation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            app_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_A), encoding="utf-8"
            )
            migrator_env.write_text("MIGRATOR_VALUE=present\n", encoding="utf-8")

            result = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                SYNTHETIC_PEPPER_A,
                self._read_env(migrator_env)["SHENZHOUHR_PROVISIONING_PEPPER"],
            )
            self.assertNotIn(SYNTHETIC_PEPPER_A, result.stdout + result.stderr)

    def test_mismatch_fails_without_modifying_or_leaking_either_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            app_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_A), encoding="utf-8"
            )
            migrator_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_B), encoding="utf-8"
            )
            app_before = app_env.read_bytes()
            migrator_before = migrator_env.read_bytes()

            result = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("SHENZHOUHR_PROVISIONING_PEPPER", result.stderr)
            self.assertNotIn(SYNTHETIC_PEPPER_A, result.stdout + result.stderr)
            self.assertNotIn(SYNTHETIC_PEPPER_B, result.stdout + result.stderr)
            self.assertEqual(app_before, app_env.read_bytes())
            self.assertEqual(migrator_before, migrator_env.read_bytes())

    def test_key_id_and_window_mismatches_fail_without_value_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            mismatches = (
                (
                    "SHENZHOUHR_PROVISIONING_KEY_ID=v1",
                    "SHENZHOUHR_PROVISIONING_KEY_ID=v2-private",
                    "SHENZHOUHR_PROVISIONING_KEY_ID",
                    "v2-private",
                ),
                (
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT2H",
                    "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW",
                    "PT2H",
                ),
            )
            for current, replacement, name, hidden_value in mismatches:
                with self.subTest(name=name):
                    app_env.write_text(
                        self._provisioning_text(SYNTHETIC_PEPPER_A),
                        encoding="utf-8",
                    )
                    migrator_env.write_text(
                        self._provisioning_text(SYNTHETIC_PEPPER_A).replace(
                            current, replacement
                        ),
                        encoding="utf-8",
                    )
                    result = self._run_helper(
                        "provisioning_sync_env_pair", app_env, migrator_env
                    )
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(name, result.stderr)
                    self.assertNotIn(hidden_value, result.stdout + result.stderr)

    def test_invalid_existing_recovery_window_fails_without_value_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            app_env = Path(directory) / "app.env"
            migrator_env = Path(directory) / "migrator.env"
            invalid_text = self._provisioning_text(SYNTHETIC_PEPPER_A).replace(
                "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H",
                "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT25H",
            )
            app_env.write_text(invalid_text, encoding="utf-8")
            migrator_env.write_text(invalid_text, encoding="utf-8")

            result = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW", result.stderr)
            self.assertNotIn("PT25H", result.stdout + result.stderr)

    def test_provision_writes_one_generated_secret_to_both_env_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_env = root / "app.env"
            migrator_env = root / "migrator.env"
            fake_mysql = root / "mysql"
            fake_mysql.write_text(
                "#!/usr/bin/env bash\n"
                "if [[ \"$*\" == *'SELECT SUBSTRING_INDEX'* ]]; then\n"
                "  printf '8.0.45\\n'\n"
                "elif [[ \"$*\" == *'information_schema.SCHEMATA'* ]]; then\n"
                "  printf '1\\n'\n"
                "elif [[ \"$*\" == *'information_schema.TABLES'* ]]; then\n"
                "  printf '0\\n'\n"
                "elif [[ \"$*\" == *\"User = 'shenzhouhr_panel' AND Host IN\"* ]]; then\n"
                "  printf '2\\n'\n"
                "elif [[ \"$*\" == *\"User = 'shenzhouhr_panel' AND Host NOT IN\"* ]]; then\n"
                "  printf '0\\n'\n"
                "elif [[ \"$*\" == *'COUNT(DISTINCT GRANTEE)'* ]]; then\n"
                "  printf '2\\n'\n"
                "elif [[ \"$*\" == *'SELECT (SELECT COUNT(*)'* ]]; then\n"
                "  printf '0\\n'\n"
                "elif [[ \"$*\" == *shenzhouhr_app*shenzhouhr_migrator* ]]; then\n"
                "  printf '0\\n'\n"
                "else\n"
                "  while IFS= read -r _line; do :; done\n"
                "fi\n",
                encoding="utf-8",
            )
            os.chmod(fake_mysql, 0o700)

            result = subprocess.run(
                [
                    "bash",
                    "-x",
                    str(PROVISION),
                    "--env-file",
                    str(app_env),
                    "--migrator-env-file",
                    str(migrator_env),
                    "--mysql-bin",
                    str(fake_mysql),
                    "--backend-address",
                    "0.0.0.0",
                ],
                cwd=REPOSITORY,
                input="root\nsynthetic-root-password\n",
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            app_values = self._read_env(app_env)
            migrator_values = self._read_env(migrator_env)
            pepper = app_values["SHENZHOUHR_PROVISIONING_PEPPER"]
            self.assertEqual("8080", app_values["SHENZHOUHR_SERVER_PORT"])
            self.assertEqual("8080", migrator_values["SHENZHOUHR_SERVER_PORT"])
            self.assertEqual("0.0.0.0", app_values["SHENZHOUHR_SERVER_ADDRESS"])
            self.assertEqual("0.0.0.0", migrator_values["SHENZHOUHR_SERVER_ADDRESS"])
            self.assertEqual(pepper, migrator_values["SHENZHOUHR_PROVISIONING_PEPPER"])
            self.assertEqual(43, len(pepper))
            self.assertEqual(32, len(base64.urlsafe_b64decode(pepper + "=")))
            self.assertNotIn(pepper, result.stdout + result.stderr)
            self.assertNotIn("synthetic-root-password", result.stdout + result.stderr)

    def test_provision_requires_an_existing_empty_panel_database(self) -> None:
        cases = (
            ("0", "0", "0", "does not exist"),
            ("1", "4", "0", "is not empty"),
            ("1", "0", "1", "service account was found"),
        )
        for exists, tables, accounts, expected_error in cases:
            with self.subTest(expected_error=expected_error), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                app_env = root / "app.env"
                migrator_env = root / "migrator.env"
                fake_mysql = root / "mysql"
                fake_mysql.write_text(
                    "#!/usr/bin/env bash\n"
                    "if [[ \"$*\" == *'SELECT SUBSTRING_INDEX'* ]]; then\n"
                    "  printf '8.0.45\\n'\n"
                    "elif [[ \"$*\" == *'information_schema.SCHEMATA'* ]]; then\n"
                    f"  printf '{exists}\\n'\n"
                    "elif [[ \"$*\" == *'information_schema.TABLES'* ]]; then\n"
                    f"  printf '{tables}\\n'\n"
                    "elif [[ \"$*\" == *\"User = 'shenzhouhr_panel' AND Host IN\"* ]]; then\n"
                    "  printf '2\\n'\n"
                    "elif [[ \"$*\" == *\"User = 'shenzhouhr_panel' AND Host NOT IN\"* ]]; then\n"
                    "  printf '0\\n'\n"
                    "elif [[ \"$*\" == *'COUNT(DISTINCT GRANTEE)'* ]]; then\n"
                    "  printf '2\\n'\n"
                    "elif [[ \"$*\" == *'SELECT (SELECT COUNT(*)'* ]]; then\n"
                    "  printf '0\\n'\n"
                    "elif [[ \"$*\" == *shenzhouhr_app*shenzhouhr_migrator* ]]; then\n"
                    f"  printf '{accounts}\\n'\n"
                    "else\n"
                    "  while IFS= read -r _line; do :; done\n"
                    "fi\n",
                    encoding="utf-8",
                )
                os.chmod(fake_mysql, 0o700)

                result = subprocess.run(
                    [
                        "bash",
                        str(PROVISION),
                        "--env-file",
                        str(app_env),
                        "--migrator-env-file",
                        str(migrator_env),
                        "--mysql-bin",
                        str(fake_mysql),
                    ],
                    cwd=REPOSITORY,
                    input="root\nsynthetic-root-password\n",
                    capture_output=True,
                    text=True,
                    check=False,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected_error, result.stderr)
                self.assertFalse(app_env.exists())
                self.assertFalse(migrator_env.exists())
                self.assertNotIn(
                    "synthetic-root-password", result.stdout + result.stderr
                )

    def test_provision_cleans_partial_service_accounts_after_sql_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_env = root / "app.env"
            migrator_env = root / "migrator.env"
            mysql_log = root / "mysql.log"
            fake_mysql = root / "mysql"
            fake_mysql.write_text(
                "#!/usr/bin/env bash\n"
                f"printf '%s\\n' \"$*\" >> {mysql_log!s}\n"
                "if [[ \"$*\" == *'SELECT SUBSTRING_INDEX'* ]]; then\n"
                "  printf '8.0.45\\n'\n"
                "elif [[ \"$*\" == *'information_schema.SCHEMATA'* ]]; then\n"
                "  printf '1\\n'\n"
                "elif [[ \"$*\" == *'information_schema.TABLES'* ]]; then\n"
                "  printf '0\\n'\n"
                "elif [[ \"$*\" == *\"User = 'shenzhouhr_panel' AND Host IN\"* ]]; then\n"
                "  printf '2\\n'\n"
                "elif [[ \"$*\" == *\"User = 'shenzhouhr_panel' AND Host NOT IN\"* ]]; then\n"
                "  printf '0\\n'\n"
                "elif [[ \"$*\" == *'COUNT(DISTINCT GRANTEE)'* ]]; then\n"
                "  printf '2\\n'\n"
                "elif [[ \"$*\" == *'SELECT (SELECT COUNT(*)'* ]]; then\n"
                "  printf '0\\n'\n"
                "elif [[ \"$*\" == *shenzhouhr_app*shenzhouhr_migrator* ]]; then\n"
                "  printf '0\\n'\n"
                "elif [[ \"$*\" == *'DROP USER IF EXISTS'* ]]; then\n"
                "  exit 0\n"
                "else\n"
                "  while IFS= read -r _line; do :; done\n"
                "  exit 23\n"
                "fi\n",
                encoding="utf-8",
            )
            os.chmod(fake_mysql, 0o700)

            result = subprocess.run(
                [
                    "bash",
                    str(PROVISION),
                    "--env-file",
                    str(app_env),
                    "--migrator-env-file",
                    str(migrator_env),
                    "--mysql-bin",
                    str(fake_mysql),
                ],
                cwd=REPOSITORY,
                input="root\nsynthetic-root-password\n",
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("DROP USER IF EXISTS", mysql_log.read_text())
            self.assertFalse(app_env.exists())
            self.assertFalse(migrator_env.exists())
            self.assertNotIn(
                "synthetic-root-password", result.stdout + result.stderr
            )

    def test_provision_refuses_existing_env_before_calling_mysql(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_env = root / "app.env"
            migrator_env = root / "migrator.env"
            marker = root / "mysql-was-called"
            fake_mysql = root / "mysql"
            app_env.write_text("EXISTING=true\n", encoding="utf-8")
            fake_mysql.write_text(
                "#!/usr/bin/env bash\n"
                f"touch {marker!s}\n",
                encoding="utf-8",
            )
            os.chmod(fake_mysql, 0o700)

            result = subprocess.run(
                [
                    "bash",
                    str(PROVISION),
                    "--env-file",
                    str(app_env),
                    "--migrator-env-file",
                    str(migrator_env),
                    "--mysql-bin",
                    str(fake_mysql),
                ],
                cwd=REPOSITORY,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Existing app env found", result.stderr)
            self.assertFalse(marker.exists())

    def test_verify_rejects_pair_mismatch_before_health_check_without_leak(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_env = root / "app.env"
            migrator_env = root / "migrator.env"
            app_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_A), encoding="utf-8"
            )
            migrator_env.write_text(
                self._provisioning_text(SYNTHETIC_PEPPER_B), encoding="utf-8"
            )

            result = subprocess.run(
                [
                    "bash",
                    str(VERIFY),
                    "--env-file",
                    str(app_env),
                    "--migrator-env-file",
                    str(migrator_env),
                    "--health-url",
                    "http://127.0.0.1:1/must-not-be-called",
                    "--mysql-bin",
                    "definitely-not-installed-mysql",
                ],
                cwd=REPOSITORY,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("SHENZHOUHR_PROVISIONING_PEPPER", result.stderr)
            self.assertNotIn(SYNTHETIC_PEPPER_A, result.stdout + result.stderr)
            self.assertNotIn(SYNTHETIC_PEPPER_B, result.stdout + result.stderr)
            self.assertNotIn("Checking application health", result.stdout)

    def _run_helper(
        self, function: str, app_env: Path, migrator_env: Path
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "bash",
                "-c",
                (
                    'set -Eeuo pipefail; source "$1"; '
                    f'if {function} "$2" "$3"; then '
                    "printf 'PASS\\n'; else printf 'ERROR: %s\\n' "
                    '"$PROVISIONING_ENV_ERROR" >&2; exit 1; fi'
                ),
                "_",
                str(HELPER),
                str(app_env),
                str(migrator_env),
            ],
            cwd=REPOSITORY,
            capture_output=True,
            text=True,
            check=False,
        )

    def _database_preflight_fixture(
        self,
        root: Path,
        *,
        mysql_version: str,
        flyway_status: str,
    ) -> tuple[Path, Path, str]:
        secret = "synthetic-preflight-password"
        app_env = root / "app.env"
        fake_mysql = root / "mysql"
        app_env.write_text(
            "SHENZHOUHR_DB_URL='jdbc:mysql://127.0.0.1:3306/shenzhou_hr?sslMode=DISABLED'\n"
            "SHENZHOUHR_DB_USERNAME=shenzhouhr_app\n"
            f"SHENZHOUHR_DB_PASSWORD={secret}\n",
            encoding="utf-8",
        )
        fake_mysql.write_text(
            "#!/usr/bin/env bash\n"
            f"printf '%s\\n' {mysql_version!r}\n"
            f"printf '%b\\n' {flyway_status!r}\n",
            encoding="utf-8",
        )
        os.chmod(fake_mysql, 0o700)
        return app_env, fake_mysql, secret

    def _provisioning_text(self, pepper: str) -> str:
        return (
            "BASE_VALUE=present\n"
            f"SHENZHOUHR_PROVISIONING_PEPPER={pepper}\n"
            "SHENZHOUHR_PROVISIONING_KEY_ID=v1\n"
            "SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=PT1H\n"
        )

    def _read_env(self, path: Path) -> dict[str, str]:
        return dict(
            line.split("=", 1)
            for line in path.read_text(encoding="utf-8").splitlines()
            if line and not line.startswith("#")
        )


if __name__ == "__main__":
    unittest.main()
