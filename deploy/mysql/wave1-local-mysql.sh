#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[shenzhouhr-mysql] ERROR: shell tracing must be disabled.\n' >&2
  exit 1
fi

readonly EXPECTED_REPOSITORY_ROOT="/Users/huzhijin/Downloads/shenzhouHR"
if [[ "$(pwd -P)" != "$EXPECTED_REPOSITORY_ROOT" ]]; then
  printf 'PROJECT_ROOT_SCOPE_ERROR\n' >&2
  exit 1
fi

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [[ "$(cd "${SCRIPT_DIR}/../.." && pwd -P)" != "$EXPECTED_REPOSITORY_ROOT" ]]; then
  printf 'PROJECT_ROOT_SCOPE_ERROR\n' >&2
  exit 1
fi
# shellcheck source=lib/mysql-safety.sh
source "${SCRIPT_DIR}/lib/mysql-safety.sh"

ENVIRONMENT_FILE=""
EXECUTE="false"
COMMAND="plan"
COMMAND_WAS_SET="false"

usage() {
  cat <<'USAGE'
Usage:
  wave1-local-mysql.sh [--env-file /absolute/outside/repo.env] [--execute] COMMAND

Default:
  With no arguments, prints a dry-run plan and does not connect to MySQL.

Read-only commands:
  plan                    Print command boundaries and required variables.
  probe                   Probe server/version/charset/collation/mode/timezone.
  grants                  Show and mechanically verify the three account grants.
  validate-test           Run Flyway validate against shenzhou_hr_test.

Explicit mutation/negative-test commands (require --execute):
  reset-databases          Rebuild both exact authorized local databases after live preflight.
  provision               Create the two databases and three least-privilege accounts.
  migrate-dev             Migrate dev V1 to latest, validate, and prove the second run is a no-op.
  migrate-fresh-test      Rebuild only test, migrate V1 to latest, validate, and prove no-op.
  upgrade-v2-test         Rebuild only test, migrate to V2, then upgrade to latest.
  verify-noop-test        Run migrate and prove history/table counts do not change.
  verify-app-privileges   Prove app-account DDL, GRANT, and cross-schema attempts fail.
  finalize-test           Apply PRESERVED/EMPTY/DROPPED test final-state policy.
  all                     Provision, migrate dev, run both test paths, privilege checks,
                          then apply the configured test final state.

The environment file must be an absolute, non-symlink path outside the repository,
owned by the current user, and mode 0600. Passwords are never command arguments.
USAGE
}

parse_arguments() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --env-file)
        [[ $# -ge 2 ]] || fail "--env-file requires a value."
        ENVIRONMENT_FILE="$2"
        shift 2
        ;;
      --execute)
        EXECUTE="true"
        shift
        ;;
      --help | -h)
        usage
        exit 0
        ;;
      -*)
        fail "Unknown option."
        ;;
      *)
        [[ "$COMMAND_WAS_SET" == "false" ]] || fail "Only one command may be supplied."
        COMMAND="$1"
        COMMAND_WAS_SET="true"
        shift
        ;;
    esac
  done
}

is_mutating_command() {
  case "$1" in
    reset-databases | provision | migrate-dev | migrate-fresh-test | upgrade-v2-test | \
    verify-noop-test | verify-app-privileges | finalize-test | all)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

require_execute_for_mutation() {
  if is_mutating_command "$COMMAND" && [[ "$EXECUTE" != "true" ]]; then
    fail "${COMMAND} requires the explicit --execute flag."
  fi
}

load_runtime() {
  load_private_environment_file "$ENVIRONMENT_FILE"
  require_mysql_client
}

root_defaults_file() {
  require_secret_variable SHENZHOUHR_MYSQL_ROOT_PASSWORD
  create_mysql_defaults_file "$ROOT_ACCOUNT" "$SHENZHOUHR_MYSQL_ROOT_PASSWORD"
}

migrator_defaults_file() {
  require_secret_variable SHENZHOUHR_FLYWAY_PASSWORD
  create_mysql_defaults_file "$MIGRATOR_ACCOUNT" "$SHENZHOUHR_FLYWAY_PASSWORD"
}

dev_app_defaults_file() {
  require_secret_variable SHENZHOUHR_DEV_DB_PASSWORD
  create_mysql_defaults_file "$DEV_APP_ACCOUNT" "$SHENZHOUHR_DEV_DB_PASSWORD"
}

test_app_defaults_file() {
  require_secret_variable SHENZHOUHR_TEST_DB_PASSWORD
  create_mysql_defaults_file "$TEST_APP_ACCOUNT" "$SHENZHOUHR_TEST_DB_PASSWORD"
}

print_plan() {
  cat <<'PLAN'
WAVE-1 local MySQL dry-run plan
  connection allowlist : localhost|127.0.0.1 : 3306
  database allowlist   : shenzhou_hr_dev, shenzhou_hr_test
  destructive allow   : the two exact authorized databases, after live preflight
  database lifecycle  : rebuild both, migrate, validate, and preserve both
  account host        : localhost
  migration source    : backend/src/main/resources/db/migration
  password transport  : private 0600 file -> private temporary client configs
  default action      : no connection and no mutation

Required secret variable names
  SHENZHOUHR_MYSQL_ROOT_PASSWORD
  SHENZHOUHR_FLYWAY_PASSWORD
  SHENZHOUHR_DEV_DB_PASSWORD
  SHENZHOUHR_TEST_DB_PASSWORD

No Docker, Docker Compose, Podman, Testcontainers, Flyway clean, wildcard schema,
or command-line password option is used.
PLAN
}

probe_server() {
  local root_defaults
  root_defaults="$(root_defaults_file)"
  log "CONNECTION_HOST=${SHENZHOUHR_MYSQL_HOST}"
  log "CONNECTION_PORT=${SHENZHOUHR_MYSQL_PORT}"
  mysql_query_with_headers \
    "$root_defaults" \
    "" \
    "$(cat "${SCRIPT_DIR}/sql/probe-server.sql")"
}

verify_schema_contract() {
  local root_defaults="$1"
  local schema_count
  local incompatible_schema_count
  local non_innodb_count

  schema_count="$(mysql_scalar "$root_defaults" "" "
    SELECT COUNT(*)
    FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME IN ('${DEV_DATABASE}', '${TEST_DATABASE}');
  ")"
  [[ "$schema_count" == "2" ]] || fail "The two exact authorized databases are not both present."

  incompatible_schema_count="$(mysql_scalar "$root_defaults" "" "
    SELECT COUNT(*)
    FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME IN ('${DEV_DATABASE}', '${TEST_DATABASE}')
      AND (
        DEFAULT_CHARACTER_SET_NAME <> 'utf8mb4'
        OR DEFAULT_COLLATION_NAME <> '${REQUIRED_COLLATION}'
      );
  ")"
  [[ "$incompatible_schema_count" == "0" ]] \
    || fail "An authorized database does not match utf8mb4/utf8mb4_0900_ai_ci."

  non_innodb_count="$(mysql_scalar "$root_defaults" "" "
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA IN ('${DEV_DATABASE}', '${TEST_DATABASE}')
      AND TABLE_TYPE = 'BASE TABLE'
      AND ENGINE <> 'InnoDB';
  ")"
  [[ "$non_innodb_count" == "0" ]] \
    || fail "A base table in an authorized database is not InnoDB."

  mysql_query_with_headers \
    "$root_defaults" \
    "" \
    "$(cat "${SCRIPT_DIR}/sql/verify-schema-contract.sql")"
  log "SCHEMA_CONTRACT=PASS"
}

provision_databases_and_accounts() {
  local root_defaults
  local collation_available
  local flyway_password_sql
  local dev_password_sql
  local test_password_sql

  require_secret_variable SHENZHOUHR_MYSQL_ROOT_PASSWORD
  require_secret_variable SHENZHOUHR_FLYWAY_PASSWORD
  require_secret_variable SHENZHOUHR_DEV_DB_PASSWORD
  require_secret_variable SHENZHOUHR_TEST_DB_PASSWORD
  root_defaults="$(root_defaults_file)"

  collation_available="$(mysql_scalar "$root_defaults" "" "
    SELECT COUNT(*)
    FROM information_schema.COLLATIONS
    WHERE COLLATION_NAME = '${REQUIRED_COLLATION}';
  ")"
  [[ "$collation_available" == "1" ]] || fail \
    "utf8mb4_0900_ai_ci is unavailable; no silent collation fallback is permitted."

  flyway_password_sql="$(sql_string_literal "$SHENZHOUHR_FLYWAY_PASSWORD")"
  dev_password_sql="$(sql_string_literal "$SHENZHOUHR_DEV_DB_PASSWORD")"
  test_password_sql="$(sql_string_literal "$SHENZHOUHR_TEST_DB_PASSWORD")"

  mysql_execute_quietly "$root_defaults" "" "
    SET SESSION time_zone = '+00:00';
    SET SESSION sql_mode =
      'STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

    CREATE DATABASE IF NOT EXISTS ${DEV_DATABASE}
      CHARACTER SET utf8mb4 COLLATE ${REQUIRED_COLLATION};
    CREATE DATABASE IF NOT EXISTS ${TEST_DATABASE}
      CHARACTER SET utf8mb4 COLLATE ${REQUIRED_COLLATION};

    CREATE USER IF NOT EXISTS '${MIGRATOR_ACCOUNT}'@'${ACCOUNT_HOST}'
      IDENTIFIED BY ${flyway_password_sql};
    ALTER USER '${MIGRATOR_ACCOUNT}'@'${ACCOUNT_HOST}'
      IDENTIFIED BY ${flyway_password_sql};
    REVOKE ALL PRIVILEGES, GRANT OPTION
      FROM '${MIGRATOR_ACCOUNT}'@'${ACCOUNT_HOST}';
    GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
      ON ${DEV_DATABASE}.* TO '${MIGRATOR_ACCOUNT}'@'${ACCOUNT_HOST}';
    GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
      ON ${TEST_DATABASE}.* TO '${MIGRATOR_ACCOUNT}'@'${ACCOUNT_HOST}';

    CREATE USER IF NOT EXISTS '${DEV_APP_ACCOUNT}'@'${ACCOUNT_HOST}'
      IDENTIFIED BY ${dev_password_sql};
    ALTER USER '${DEV_APP_ACCOUNT}'@'${ACCOUNT_HOST}'
      IDENTIFIED BY ${dev_password_sql};
    REVOKE ALL PRIVILEGES, GRANT OPTION
      FROM '${DEV_APP_ACCOUNT}'@'${ACCOUNT_HOST}';
    GRANT SELECT, INSERT, UPDATE, DELETE
      ON ${DEV_DATABASE}.* TO '${DEV_APP_ACCOUNT}'@'${ACCOUNT_HOST}';

    CREATE USER IF NOT EXISTS '${TEST_APP_ACCOUNT}'@'${ACCOUNT_HOST}'
      IDENTIFIED BY ${test_password_sql};
    ALTER USER '${TEST_APP_ACCOUNT}'@'${ACCOUNT_HOST}'
      IDENTIFIED BY ${test_password_sql};
    REVOKE ALL PRIVILEGES, GRANT OPTION
      FROM '${TEST_APP_ACCOUNT}'@'${ACCOUNT_HOST}';
    GRANT SELECT, INSERT, UPDATE, DELETE
      ON ${TEST_DATABASE}.* TO '${TEST_APP_ACCOUNT}'@'${ACCOUNT_HOST}';

    FLUSH PRIVILEGES;
  "

  verify_schema_contract "$root_defaults"
  show_and_verify_grants
  log "PROVISIONING=PASS"
}

show_and_verify_grants() {
  local root_defaults
  local grants
  root_defaults="$(root_defaults_file)"

  grants="$(
    mysql_query "$root_defaults" "" "
      SHOW GRANTS FOR '${MIGRATOR_ACCOUNT}'@'${ACCOUNT_HOST}';
      SHOW GRANTS FOR '${DEV_APP_ACCOUNT}'@'${ACCOUNT_HOST}';
      SHOW GRANTS FOR '${TEST_APP_ACCOUNT}'@'${ACCOUNT_HOST}';
    "
  )"
  printf '%s\n' "$grants"

  if printf '%s\n' "$grants" | grep -Eiq 'WITH GRANT OPTION'; then
    fail "An account unexpectedly has GRANT OPTION."
  fi
  if printf '%s\n' "$grants" \
      | grep -Ei 'GRANT .* ON \*\.\*' \
      | grep -Eiv '^GRANT USAGE ON \*\.\*' >/dev/null; then
    fail "An account unexpectedly has a global privilege."
  fi

  printf '%s\n' "$grants" | grep -F "${DEV_DATABASE}" >/dev/null \
    || fail "Expected dev database grant is missing."
  printf '%s\n' "$grants" | grep -F "${TEST_DATABASE}" >/dev/null \
    || fail "Expected test database grant is missing."

  local dev_grants
  local test_grants
  dev_grants="$(mysql_query "$root_defaults" "" \
    "SHOW GRANTS FOR '${DEV_APP_ACCOUNT}'@'${ACCOUNT_HOST}';")"
  test_grants="$(mysql_query "$root_defaults" "" \
    "SHOW GRANTS FOR '${TEST_APP_ACCOUNT}'@'${ACCOUNT_HOST}';")"

  if printf '%s\n' "$dev_grants" \
      | grep -Eiq '(^|[ ,])(CREATE|ALTER|DROP)([ ,]|$)|GRANT OPTION'; then
    fail "The dev app account unexpectedly has DDL or grant privileges."
  fi
  if printf '%s\n' "$test_grants" \
      | grep -Eiq '(^|[ ,])(CREATE|ALTER|DROP)([ ,]|$)|GRANT OPTION'; then
    fail "The test app account unexpectedly has DDL or grant privileges."
  fi
  printf '%s\n' "$dev_grants" | grep -F "$DEV_DATABASE" >/dev/null \
    || fail "The dev app account grant is missing."
  printf '%s\n' "$test_grants" | grep -F "$TEST_DATABASE" >/dev/null \
    || fail "The test app account grant is missing."
  if printf '%s\n' "$dev_grants" | grep -F "$TEST_DATABASE" >/dev/null; then
    fail "The dev app account unexpectedly has test database access."
  fi
  if printf '%s\n' "$test_grants" | grep -F "$DEV_DATABASE" >/dev/null; then
    fail "The test app account unexpectedly has dev database access."
  fi
  log "SHOW_GRANTS_CONTRACT=PASS"
}

reset_database() {
  local target="$1"
  local root_defaults
  assert_exact_database "$target"
  root_defaults="$(root_defaults_file)"
  destructive_database_preflight "$root_defaults" "$target"
  mysql_execute_quietly "$root_defaults" "" "
    DROP DATABASE IF EXISTS ${target};
    CREATE DATABASE ${target}
      CHARACTER SET utf8mb4 COLLATE ${REQUIRED_COLLATION};
  "
  log "DATABASE_RESET=PASS target=${target}"
}

reset_authorized_databases() {
  reset_database "$DEV_DATABASE"
  reset_database "$TEST_DATABASE"
  log "AUTHORIZED_DATABASE_RESET=PASS"
}

reset_test_database() {
  reset_database "$TEST_DATABASE"
}

migration_checksum_report() {
  local migration
  while IFS= read -r migration; do
    log "MIGRATION_SHA256 $(basename "$migration") $(hash_file "$migration")"
  done < <(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' | sort)
}

validate_test_database() {
  require_flyway_client
  run_flyway "$TEST_DATABASE" validate
  log "FLYWAY_VALIDATE_TEST=PASS"
}

verify_migrate_noop() {
  local database="$1"
  local migrator_defaults
  local before_history
  local before_tables
  local after_history
  local after_tables
  require_flyway_client
  assert_exact_database "$database"
  migrator_defaults="$(migrator_defaults_file)"

  before_history="$(mysql_scalar "$migrator_defaults" "$database" "
    SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;
  ")"
  before_tables="$(mysql_scalar "$migrator_defaults" "$database" "
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${database}' AND TABLE_TYPE = 'BASE TABLE';
  ")"

  run_flyway "$database" migrate

  after_history="$(mysql_scalar "$migrator_defaults" "$database" "
    SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;
  ")"
  after_tables="$(mysql_scalar "$migrator_defaults" "$database" "
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${database}' AND TABLE_TYPE = 'BASE TABLE';
  ")"

  [[ "$before_history" == "$after_history" ]] \
    || fail "Second migrate changed Flyway history."
  [[ "$before_tables" == "$after_tables" ]] \
    || fail "Second migrate changed the base-table count."
  log "SECOND_MIGRATE_NOOP=PASS database=${database} history=${after_history} tables=${after_tables}"
}

migrate_fresh_test_database() {
  local migrator_defaults
  local minimum_version
  require_flyway_client
  reset_test_database
  migration_checksum_report
  run_flyway "$TEST_DATABASE" migrate
  run_flyway "$TEST_DATABASE" validate
  migrator_defaults="$(migrator_defaults_file)"
  minimum_version="$(mysql_scalar "$migrator_defaults" "$TEST_DATABASE" "
    SELECT MIN(CAST(version AS UNSIGNED))
    FROM flyway_schema_history
    WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$minimum_version" == "1" ]] || fail "Fresh test migration did not start at V1."
  verify_migrate_noop "$TEST_DATABASE"
  log "FRESH_TEST_V1_TO_LATEST=PASS"
  log "TEST_FINAL_STATE=PRESERVED"
}

assert_v2_baseline_rows() {
  local migrator_defaults="$1"
  local role_count
  local capability_count
  role_count="$(mysql_scalar "$migrator_defaults" "$TEST_DATABASE" "
    SELECT COUNT(*)
    FROM auth_role
    WHERE role_id IN (
      '10000000-0000-0000-0000-000000000001',
      '10000000-0000-0000-0000-000000000002',
      '10000000-0000-0000-0000-000000000003'
    );
  ")"
  capability_count="$(mysql_scalar "$migrator_defaults" "$TEST_DATABASE" "
    SELECT COUNT(*)
    FROM auth_capability
    WHERE capability_id IN (
      '20000000-0000-0000-0000-000000000001',
      '20000000-0000-0000-0000-000000000002',
      '20000000-0000-0000-0000-000000000003',
      '20000000-0000-0000-0000-000000000004'
    );
  ")"
  [[ "$role_count" == "3" ]] || fail "V2 baseline roles were not preserved."
  [[ "$capability_count" == "4" ]] || fail "V2 baseline capabilities were not preserved."
}

upgrade_v2_test_database() {
  local migrator_defaults
  local version_at_checkpoint
  require_flyway_client
  reset_test_database
  run_flyway "$TEST_DATABASE" "-target=2" migrate
  migrator_defaults="$(migrator_defaults_file)"
  version_at_checkpoint="$(mysql_scalar "$migrator_defaults" "$TEST_DATABASE" "
    SELECT MAX(CAST(version AS UNSIGNED))
    FROM flyway_schema_history
    WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$version_at_checkpoint" == "2" ]] \
    || fail "The V1/V2 checkpoint did not stop at V2."
  assert_v2_baseline_rows "$migrator_defaults"

  run_flyway "$TEST_DATABASE" migrate
  run_flyway "$TEST_DATABASE" validate
  assert_v2_baseline_rows "$migrator_defaults"
  verify_migrate_noop "$TEST_DATABASE"
  log "V2_TO_LATEST_UPGRADE=PASS"
  log "TEST_FINAL_STATE=PRESERVED"
}

migrate_dev_database() {
  require_flyway_client
  run_flyway "$DEV_DATABASE" migrate
  run_flyway "$DEV_DATABASE" validate
  verify_migrate_noop "$DEV_DATABASE"
  log "DEV_V1_TO_LATEST_MIGRATION=PASS"
}

verify_app_account_crud() {
  local database="$1"
  local app_defaults="$2"
  local migrator_defaults
  local marker
  assert_exact_database "$database"
  migrator_defaults="$(migrator_defaults_file)"

  mysql_execute_quietly "$migrator_defaults" "$database" "
    DROP TABLE IF EXISTS wave1_app_privilege_probe;
    CREATE TABLE wave1_app_privilege_probe (
      id INT NOT NULL PRIMARY KEY,
      marker VARCHAR(32) NOT NULL
    ) ENGINE=InnoDB;
  "
  mysql_execute_quietly "$app_defaults" "$database" "
    INSERT INTO wave1_app_privilege_probe (id, marker) VALUES (1, 'inserted');
    UPDATE wave1_app_privilege_probe SET marker = 'updated' WHERE id = 1;
  "
  marker="$(mysql_scalar "$app_defaults" "$database" "
    SELECT marker FROM wave1_app_privilege_probe WHERE id = 1;
  ")"
  [[ "$marker" == "updated" ]] || fail "App-account CRUD verification returned an unexpected value."
  mysql_execute_quietly "$app_defaults" "$database" "
    DELETE FROM wave1_app_privilege_probe WHERE id = 1;
  "
  mysql_execute_quietly "$migrator_defaults" "$database" "
    DROP TABLE wave1_app_privilege_probe;
  "
  log "APP_ACCOUNT_CRUD=PASS database=${database}"
}

verify_app_account_privileges() {
  local dev_defaults
  local test_defaults
  local migrator_defaults
  dev_defaults="$(dev_app_defaults_file)"
  test_defaults="$(test_app_defaults_file)"
  migrator_defaults="$(migrator_defaults_file)"

  verify_app_account_crud "$DEV_DATABASE" "$dev_defaults"
  verify_app_account_crud "$TEST_DATABASE" "$test_defaults"

  mysql_execute_quietly "$migrator_defaults" "$DEV_DATABASE" "
    CREATE TABLE wave1_app_privilege_probe (id INT PRIMARY KEY) ENGINE=InnoDB;
  "
  mysql_execute_quietly "$migrator_defaults" "$TEST_DATABASE" "
    CREATE TABLE wave1_app_privilege_probe (id INT PRIMARY KEY) ENGINE=InnoDB;
  "

  expect_mysql_failure \
    "$dev_defaults" \
    "$DEV_DATABASE" \
    "CREATE TABLE shenzhouhr_permission_probe (id INT);" \
    "DEV_APP_CREATE_TABLE"
  expect_mysql_failure \
    "$test_defaults" \
    "$TEST_DATABASE" \
    "CREATE TABLE shenzhouhr_permission_probe (id INT);" \
    "TEST_APP_CREATE_TABLE"
  expect_mysql_failure \
    "$dev_defaults" \
    "$DEV_DATABASE" \
    "ALTER TABLE wave1_app_privilege_probe ADD COLUMN denied INT;" \
    "DEV_APP_ALTER_TABLE"
  expect_mysql_failure \
    "$test_defaults" \
    "$TEST_DATABASE" \
    "ALTER TABLE wave1_app_privilege_probe ADD COLUMN denied INT;" \
    "TEST_APP_ALTER_TABLE"
  expect_mysql_failure \
    "$dev_defaults" \
    "$DEV_DATABASE" \
    "DROP TABLE wave1_app_privilege_probe;" \
    "DEV_APP_DROP_TABLE"
  expect_mysql_failure \
    "$test_defaults" \
    "$TEST_DATABASE" \
    "DROP TABLE wave1_app_privilege_probe;" \
    "TEST_APP_DROP_TABLE"
  expect_mysql_failure \
    "$dev_defaults" \
    "" \
    "CREATE DATABASE ${TEST_DATABASE};" \
    "DEV_APP_CREATE_DATABASE"
  expect_mysql_failure \
    "$test_defaults" \
    "" \
    "CREATE DATABASE ${DEV_DATABASE};" \
    "TEST_APP_CREATE_DATABASE"
  expect_mysql_failure \
    "$dev_defaults" \
    "" \
    "DROP DATABASE ${TEST_DATABASE};" \
    "DEV_APP_DROP_DATABASE"
  expect_mysql_failure \
    "$test_defaults" \
    "" \
    "DROP DATABASE ${DEV_DATABASE};" \
    "TEST_APP_DROP_DATABASE"
  expect_mysql_failure \
    "$dev_defaults" \
    "$DEV_DATABASE" \
    "GRANT SELECT ON ${DEV_DATABASE}.* TO '${DEV_APP_ACCOUNT}'@'${ACCOUNT_HOST}';" \
    "DEV_APP_GRANT"
  expect_mysql_failure \
    "$test_defaults" \
    "$TEST_DATABASE" \
    "GRANT SELECT ON ${TEST_DATABASE}.* TO '${TEST_APP_ACCOUNT}'@'${ACCOUNT_HOST}';" \
    "TEST_APP_GRANT"
  expect_mysql_failure \
    "$dev_defaults" \
    "" \
    "SHOW TABLES FROM ${TEST_DATABASE};" \
    "DEV_APP_CROSS_SCHEMA"
  expect_mysql_failure \
    "$test_defaults" \
    "" \
    "SHOW TABLES FROM ${DEV_DATABASE};" \
    "TEST_APP_CROSS_SCHEMA"

  mysql_execute_quietly "$migrator_defaults" "$DEV_DATABASE" "
    DROP TABLE wave1_app_privilege_probe;
  "
  mysql_execute_quietly "$migrator_defaults" "$TEST_DATABASE" "
    DROP TABLE wave1_app_privilege_probe;
  "
  log "APP_ACCOUNT_NEGATIVE_PRIVILEGES=PASS"
}

finalize_test_database() {
  local root_defaults
  root_defaults="$(root_defaults_file)"
  case "$SHENZHOUHR_TEST_FINAL_STATE" in
    PRESERVED)
      log "TEST_FINAL_STATE=PRESERVED"
      ;;
    EMPTY)
      reset_test_database
      log "TEST_FINAL_STATE=EMPTY"
      ;;
    DROPPED)
      destructive_database_preflight "$root_defaults" "$TEST_DATABASE"
      mysql_execute_quietly "$root_defaults" "" \
        "DROP DATABASE IF EXISTS ${TEST_DATABASE};"
      log "TEST_FINAL_STATE=DROPPED"
      ;;
    *)
      fail "Unsupported test final state."
      ;;
  esac
}

run_all() {
  probe_server
  reset_authorized_databases
  provision_databases_and_accounts
  migrate_dev_database
  migrate_fresh_test_database
  upgrade_v2_test_database
  show_and_verify_grants
  verify_app_account_privileges
  finalize_test_database
  log "WAVE1_LOCAL_MYSQL_AUTOMATION=PASS"
}

dispatch() {
  case "$COMMAND" in
    plan)
      print_plan
      ;;
    probe)
      load_runtime
      probe_server
      ;;
    provision)
      load_runtime
      provision_databases_and_accounts
      ;;
    reset-databases)
      load_runtime
      reset_authorized_databases
      ;;
    grants)
      load_runtime
      show_and_verify_grants
      ;;
    migrate-dev)
      load_runtime
      migrate_dev_database
      ;;
    migrate-fresh-test)
      load_runtime
      migrate_fresh_test_database
      ;;
    upgrade-v2-test)
      load_runtime
      upgrade_v2_test_database
      ;;
    validate-test)
      load_runtime
      validate_test_database
      ;;
    verify-noop-test)
      load_runtime
      verify_migrate_noop "$TEST_DATABASE"
      ;;
    verify-app-privileges)
      load_runtime
      verify_app_account_privileges
      ;;
    finalize-test)
      load_runtime
      finalize_test_database
      ;;
    all)
      load_runtime
      run_all
      ;;
    *)
      fail "Unknown command. Run with --help."
      ;;
  esac
}

parse_arguments "$@"
require_execute_for_mutation
dispatch
