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
INITIAL_COMPANY_CATALOG = (
    REPOSITORY / "deploy/baota/config/initial-companies.tsv"
)
INITIAL_ADMIN_COMMAND = REPOSITORY / (
    "backend/src/main/java/com/szsemicon/hr/identityaccess/infrastructure/"
    "bootstrap/InitialProductionAdminCommand.java"
)
INSTALL = REPOSITORY / "deploy/baota/install.sh"
UPGRADE = REPOSITORY / "deploy/baota/upgrade.sh"
ENV_EXAMPLE = REPOSITORY / "deploy/baota/env/shenzhouhr.env.example"
BUILD_RELEASE = REPOSITORY / "deploy/baota/build-release.sh"
CUSTOMER_GUIDE = REPOSITORY / "docs/deployment/baota-deployment-guide.md"
NGINX_HTTP = REPOSITORY / "deploy/baota/nginx/shenzhouhr-site-http.conf"
NGINX_HTTPS = REPOSITORY / "deploy/baota/nginx/shenzhouhr-site.conf"
NGINX_PROXY = REPOSITORY / "deploy/baota/nginx/shenzhouhr-api-proxy.conf"
BAOTA_PROXY_HELPER = REPOSITORY / "deploy/baota/scripts/baota-proxy.py"
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

    def test_customer_provisioning_never_embeds_external_service_credentials(self) -> None:
        provision = PROVISION.read_text(encoding="utf-8")

        self.assertEqual(2, provision.count("OA_MYSQL_ENABLED=false"))
        self.assertEqual(1, provision.count("OA_MYSQL_JDBC_URL="))
        self.assertEqual(1, provision.count("OA_MYSQL_USERNAME="))
        self.assertEqual(1, provision.count("OA_MYSQL_PASSWORD="))
        self.assertEqual(
            2, provision.count("SHENZHOUHR_OA_AUTO_SYNC_ENABLED=false")
        )
        self.assertEqual(1, provision.count("OA_MYSQL_SOURCE_TIME_ZONE=Asia/Shanghai"))
        self.assertEqual(1, provision.count("SHENZHOUHR_OA_AUTO_SYNC_ZONE=Asia/Shanghai"))
        self.assertNotRegex(provision, r"(?m)^OA_MYSQL_JDBC_URL=.+$")
        self.assertNotRegex(provision, r"(?m)^OA_MYSQL_USERNAME=.+$")
        self.assertNotRegex(provision, r"(?m)^OA_MYSQL_PASSWORD=.+$")

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

    def test_signed_catalog_drives_atomic_four_company_admin_initialization(
        self,
    ) -> None:
        expected_catalog = (
            "SZSZ\t上海昇州半导体科技有限公司\n"
            "SZJN\t上海晟州聚能半导体科技有限公司\n"
            "SZSC\t江苏神州半导体科技股份有限公司\n"
            "SZXY\t江苏芯越半导体科技有限公司\n"
        )
        initial_admin = INITIAL_ADMIN.read_text(encoding="utf-8")
        install = INSTALL.read_text(encoding="utf-8")
        command = INITIAL_ADMIN_COMMAND.read_text(encoding="utf-8")
        verify = VERIFY.read_text(encoding="utf-8")
        build_release = BUILD_RELEASE.read_text(encoding="utf-8")

        self.assertEqual(expected_catalog, INITIAL_COMPANY_CATALOG.read_text(
            encoding="utf-8"
        ))
        self.assertIn("--company-catalog", initial_admin)
        self.assertIn("SHENZHOUHR_INIT_COMPANY_CATALOG", initial_admin)
        self.assertNotIn("SHENZHOUHR_INIT_COMPANY_CODE", initial_admin)
        self.assertNotIn("SHENZHOUHR_INIT_COMPANY_NAME", initial_admin)
        self.assertNotIn("Company code [CUSTOMER]", initial_admin)
        self.assertNotIn("Company name:", initial_admin)
        self.assertIn("SZSZ,SZJN,SZSC,SZXY", initial_admin)

        self.assertIn("initial_company_catalog=", install)
        self.assertIn('--company-catalog "$INITIAL_COMPANY_CATALOG"', install)
        self.assertNotIn("SHENZHOUHR_INIT_COMPANY_CODE", install)
        self.assertNotIn("SHENZHOUHR_INIT_COMPANY_NAME", install)
        self.assertNotIn("Company code [CUSTOMER]", install)
        self.assertNotIn("Company name:", install)

        self.assertIn("Connection.TRANSACTION_SERIALIZABLE", command)
        self.assertIn("assertFreshMigratedDatabase(connection)", command)
        self.assertIn("connection.rollback()", command)
        self.assertIn("INITIAL_ADMIN_COMPANY_COUNT=", command)
        self.assertIn("INITIAL_ADMIN_SCOPE_COUNT=", command)
        self.assertIn("INITIAL_ADMIN_ROLE_ASSIGNMENT_COUNT=", command)
        self.assertNotIn("findOrCreateCompany", command)
        self.assertNotIn("SHENZHOUHR_INIT_COMPANY_CODE", command)
        self.assertNotIn("SHENZHOUHR_INIT_COMPANY_NAME", command)

        self.assertIn("EXPECTED_BOOTSTRAP_STATUS=$'4\\t4\\t2\\t1", verify)
        self.assertIn("COUNT(DISTINCT role.role_code) = 2", verify)
        self.assertIn("initial_company_catalog=%s", build_release)
        self.assertIn("initial_company_codes=%s", build_release)
        self.assertIn("initial_company_count=%s", build_release)
        self.assertIn("initial_company_scope_count=%s", build_release)
        self.assertIn("initial_admin_assignment_count=%s", build_release)
        self.assertIn(
            "find . -type f ! -name SHA256SUMS",
            build_release,
        )

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

    def test_v48_migration_and_runtime_grants_are_least_privilege(self) -> None:
        provision = PROVISION.read_text(encoding="utf-8")
        migrate = MIGRATE.read_text(encoding="utf-8")
        verify = VERIFY.read_text(encoding="utf-8")
        install = INSTALL.read_text(encoding="utf-8")
        upgrade = UPGRADE.read_text(encoding="utf-8")

        self.assertIn(
            "REFERENCES, CREATE VIEW, CREATE ROUTINE ON \\`$DB_NAME\\`.* "
            "TO '$MIGRATOR_USER'@'$ACCOUNT_HOST'",
            provision,
        )
        self.assertNotIn("ALTER ROUTINE ON \\`$DB_NAME\\`.*", provision)
        self.assertNotIn("EXECUTE ON \\`$DB_NAME\\`.*", provision)
        self.assertIn("@@GLOBAL.automatic_sp_privileges", migrate)
        self.assertIn(
            "GRANT CREATE VIEW, CREATE ROUTINE ON \\`$DB_NAME\\`.* "
            "TO '$MIGRATOR_USER'@'$ACCOUNT_HOST';",
            migrate,
        )
        self.assertIn(
            "GRANT EXECUTE ON PROCEDURE \\`$DB_NAME\\`.\\`$YEAR_END_PROCEDURE\\` "
            "TO '$APP_USER'@'$ACCOUNT_HOST';",
            migrate,
        )
        self.assertNotRegex(
            migrate,
            r"GRANT\s+EXECUTE\s+ON\s+(?!PROCEDURE\s+\\`\$DB_NAME\\`\.\\`\$YEAR_END_PROCEDURE\\`)",
        )
        self.assertIn("EXPECTED_PRE_MIGRATION_GRANTS=", migrate)
        self.assertIn("APP_ROUTINE_GRANTS", migrate)
        self.assertIn("EXPECTED_APP_ROUTINE_GRANT=", verify)
        self.assertIn("EXPECTED_MIGRATOR_GRANT_STATUS=", verify)
        for entrypoint in (install, upgrade):
            self.assertIn('--app-env-file "$ENV_ROOT/shenzhouhr.env"', entrypoint)
            self.assertIn('--mysql-bin "$MYSQL_BIN"', entrypoint)

    def test_migrate_applies_two_phase_grants_without_secret_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar_path = root / "app.jar"
            app_env = root / "app.env"
            migrator_env = root / "migrator.env"
            fake_java = root / "java"
            fake_jar = root / "jar"
            fake_mysql = root / "mysql"
            mysql_log = root / "mysql.sql"
            root_secret = "synthetic-root-migration-secret"
            app_secret = "synthetic-app-secret"
            migrator_secret = "synthetic-migrator-secret"
            oa_secret = "synthetic-oa-secret"
            db_url = (
                "jdbc:mysql://127.0.0.1:3306/shenzhou_hr?"
                "sslMode=DISABLED"
            )

            jar_path.write_bytes(b"synthetic jar")
            app_env.write_text(
                "SHENZHOUHR_DB_URL='" + db_url + "'\n"
                "SHENZHOUHR_DB_USERNAME=shenzhouhr_app\n"
                f"SHENZHOUHR_DB_PASSWORD={app_secret}\n"
                "SHENZHOUHR_FLYWAY_ENABLED=false\n"
                "OA_MYSQL_ENABLED=true\n"
                f"OA_MYSQL_PASSWORD={oa_secret}\n",
                encoding="utf-8",
            )
            migrator_env.write_text(
                "SHENZHOUHR_DB_URL='" + db_url + "'\n"
                "SHENZHOUHR_DB_USERNAME=shenzhouhr_migrator\n"
                f"SHENZHOUHR_DB_PASSWORD={migrator_secret}\n"
                "SHENZHOUHR_FLYWAY_ENABLED=true\n"
                "SHENZHOUHR_FLYWAY_URL='" + db_url + "'\n",
                encoding="utf-8",
            )
            fake_java.write_text(
                "#!/usr/bin/env bash\n"
                "if [[ \"${1:-}\" == '-version' ]]; then\n"
                "  printf 'openjdk version \"21.0.1\"\\n' >&2\n"
                "else\n"
                "  [[ -z \"${OA_MYSQL_PASSWORD:-}\" ]] || exit 92\n"
                "  printf 'Started ShenzhouHrApplication\\n'\n"
                "  sleep 2\n"
                "fi\n",
                encoding="utf-8",
            )
            fake_jar.write_text(
                "#!/usr/bin/env bash\n"
                "printf 'BOOT-INF/lib/spring-boot-flyway-3.5.0.jar\\n'\n",
                encoding="utf-8",
            )
            fake_mysql.write_text(
                "#!/usr/bin/env bash\n"
                "if [[ \"$*\" == *'SUBSTRING_INDEX(VERSION()'* ]]; then\n"
                "  printf '8.0.45\\n'\n"
                "elif [[ \"$*\" == *'automatic_sp_privileges'* ]]; then\n"
                "  printf '1\\t1\\t1\\t1\\n'\n"
                "elif [[ \"$*\" == *'GROUP_CONCAT(PRIVILEGE_TYPE'* ]]; then\n"
                "  printf 'DELETE,INSERT,SELECT,UPDATE\\tALTER,CREATE,CREATE ROUTINE,CREATE VIEW,DELETE,DROP,INDEX,INSERT,REFERENCES,SELECT,UPDATE\\t0\\t0\\t0\\t0\\n'\n"
                "elif [[ \"$*\" == *'information_schema.ROUTINES'* ]]; then\n"
                "  printf '1\\n'\n"
                "elif [[ \"$*\" == *'mysql.procs_priv'* ]]; then\n"
                "  printf '1\\t1\\n'\n"
                "else\n"
                "  while IFS= read -r line; do\n"
                "    printf '%s\\n' \"$line\" >> \"$FAKE_MYSQL_LOG\"\n"
                "  done\n"
                "fi\n",
                encoding="utf-8",
            )
            for executable in (fake_java, fake_jar, fake_mysql):
                os.chmod(executable, 0o700)

            result = subprocess.run(
                [
                    "bash",
                    "-x",
                    str(MIGRATE),
                    "--jar",
                    str(jar_path),
                    "--app-env-file",
                    str(app_env),
                    "--migrator-env-file",
                    str(migrator_env),
                    "--mysql-bin",
                    str(fake_mysql),
                    "--timeout-sec",
                    "5",
                ],
                cwd=REPOSITORY,
                env={
                    **os.environ,
                    "JAVA_BIN": str(fake_java),
                    "JAR_BIN": str(fake_jar),
                    "FAKE_MYSQL_LOG": str(mysql_log),
                },
                input=f"root\n{root_secret}\n",
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            sql = mysql_log.read_text(encoding="utf-8")
            self.assertIn(
                "GRANT CREATE VIEW, CREATE ROUTINE ON `shenzhou_hr`.* "
                "TO 'shenzhouhr_migrator'@'127.0.0.1';",
                sql,
            )
            self.assertIn(
                "GRANT EXECUTE ON PROCEDURE `shenzhou_hr`."
                "`szsc_oa_time_off_expire` TO "
                "'shenzhouhr_app'@'127.0.0.1';",
                sql,
            )
            self.assertNotIn("GRANT EXECUTE ON `shenzhou_hr`.*", sql)
            for secret in (root_secret, app_secret, migrator_secret, oa_secret):
                self.assertNotIn(secret, result.stdout + result.stderr)

    def test_upgrade_env_convergence_preserves_oa_values_without_leaking(self) -> None:
        upgrade = UPGRADE.read_text(encoding="utf-8")
        synthetic_oa_secret = "synthetic-oa-secret-must-not-appear"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app_env = root / "app.env"
            migrator_env = root / "migrator.env"
            app_before = (
                "BASE_VALUE=present\n"
                "OA_MYSQL_ENABLED=true\n"
                "OA_MYSQL_JDBC_URL='jdbc:mysql://oa.internal:3306/oa?sslMode=VERIFY_IDENTITY'\n"
                "OA_MYSQL_USERNAME=readonly_oa\n"
                f"OA_MYSQL_PASSWORD={synthetic_oa_secret}\n"
                "OA_MYSQL_MAX_POOL_SIZE=2\n"
                "OA_MYSQL_CONNECTION_TIMEOUT=PT5S\n"
                "OA_MYSQL_QUERY_TIMEOUT=PT3S\n"
            )
            app_env.write_text(app_before, encoding="utf-8")
            migrator_env.write_text("MIGRATOR_VALUE=present\n", encoding="utf-8")
            os.chmod(app_env, 0o640)
            os.chmod(migrator_env, 0o600)

            result = self._run_helper(
                "provisioning_sync_env_pair", app_env, migrator_env
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(app_env.read_text(encoding="utf-8").startswith(app_before))
            self.assertNotIn(
                synthetic_oa_secret,
                result.stdout + result.stderr,
            )
            self.assertNotIn(
                synthetic_oa_secret,
                migrator_env.read_text(encoding="utf-8"),
            )
        self.assertIn("provisioning_sync_env_pair", upgrade)
        self.assertNotIn("OA_MYSQL_PASSWORD", upgrade)

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

    def test_api_proxy_is_a_panel_registered_site_rule_not_a_raw_vhost_location(
        self,
    ) -> None:
        include = (
            "include /www/server/panel/vhost/nginx/proxy/"
            "__DOMAIN__/*.conf;"
        )
        for template in (NGINX_HTTP, NGINX_HTTPS):
            with self.subTest(template=template):
                text = template.read_text(encoding="utf-8")
                self.assertIn(include, text)
                self.assertNotRegex(text, r"(?m)^\s*location\s+(?:\^~\s+)?/api/")

        proxy = NGINX_PROXY.read_text(encoding="utf-8")
        self.assertIn("# SHENZHOUHR-BAOTA-PROXY-V1", proxy)
        self.assertIn("location ^~ /api/", proxy)
        self.assertIn(
            "proxy_pass http://127.0.0.1:__BACKEND_PORT__/api/;", proxy
        )
        self.assertNotIn("add_header", proxy)
        self.assertNotIn("proxy_cache", proxy)
        self.assertNotIn("$http_upgrade", proxy)

    def test_install_upgrade_and_verify_preserve_the_panel_proxy_contract(
        self,
    ) -> None:
        install = INSTALL.read_text(encoding="utf-8")
        upgrade = UPGRADE.read_text(encoding="utf-8")
        verify = VERIFY.read_text(encoding="utf-8")
        build = BUILD_RELEASE.read_text(encoding="utf-8")
        guide = CUSTOMER_GUIDE.read_text(encoding="utf-8")

        self.assertTrue(BAOTA_PROXY_HELPER.is_file())
        self.assertIn('CUSTOMER_PROXY_NAME="kaoqin-api"', install)
        self.assertIn('CUSTOMER_PROXY_NAME="kaoqin-api"', upgrade)
        self.assertIn('CUSTOMER_PROXY_NAME="kaoqin-api"', verify)
        self.assertIn('PROXY_BACKUP="$BACKUP_DIR/', install)
        self.assertIn('PROXY_BACKUP="$BACKUP_DIR/', upgrade)
        self.assertIn('cp -a -- "$PROXY_BACKUP" "$PROXY_CONFIG"', install)
        self.assertIn('cp -a -- "$PROXY_BACKUP" "$PROXY_CONFIG"', upgrade)
        self.assertIn("--require-marker", upgrade)
        self.assertIn("--require-marker", verify)
        self.assertLess(
            install.index('scripts/baota-proxy.py'),
            install.index('"$BAOTA_ROOT/mysql/provision.sh"'),
        )
        self.assertIn("python3 -m unittest discover -v scripts/release/tests", build)
        self.assertIn("baota_proxy_name=%s", build)
        self.assertIn("baota_proxy_target=%s", build)
        self.assertIn("代理名称 | `kaoqin-api`", guide)
        self.assertIn("目标 URL | `http://127.0.0.1:8080/api`", guide)
        self.assertIn("不要在顶层「反向代理项目」页", guide)
        self.assertIn("Java 项目的“绑定域名/外网", guide)

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
