#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[shenzhouhr-wave2-mysql] ERROR: shell tracing must be disabled.\n' >&2
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
# shellcheck source=lib/company-dimension-cutover.sh
source "${SCRIPT_DIR}/lib/company-dimension-cutover.sh"

ENVIRONMENT_FILE=""
EXECUTE="false"
COMMAND="plan"
WAVE2_PROBE_CLEANUP_ENABLED="false"
WAVE2_MIGRATOR_DEFAULTS=""

cleanup_wave2() {
  if [[ "$WAVE2_PROBE_CLEANUP_ENABLED" == "true"
        && -n "$WAVE2_MIGRATOR_DEFAULTS"
        && -f "$WAVE2_MIGRATOR_DEFAULTS"
        && -n "${SHENZHOUHR_MYSQL_CLIENT_BIN:-}" ]]; then
    local database
    for database in "$DEV_DATABASE" "$TEST_DATABASE"; do
      printf '%s\n' "DROP TABLE IF EXISTS wave2_app_privilege_probe;" \
        | "$SHENZHOUHR_MYSQL_CLIENT_BIN" \
          "--defaults-extra-file=${WAVE2_MIGRATOR_DEFAULTS}" \
          "--database=${database}" \
          --batch --raw --silent >/dev/null 2>/dev/null || true
    done
  fi
  cleanup_mysql_safety
}

trap cleanup_wave2 EXIT

usage() {
  cat <<'USAGE'
Usage:
  wave2-local-mysql.sh --env-file /absolute/outside/repo.env [--execute] COMMAND

Commands:
  plan                  Print the bounded, rootless WAVE-2 verification plan.
  migrate-dev           Forward-migrate dev and prove WAVE-1 row counts are preserved.
  migrate-fresh-test    Rebuild only test tables, migrate V1 to latest, validate, and prove no-op.
  upgrade-v4-test       Rebuild only test tables, migrate to V4, then latest, validate, and prove no-op.
  verify-contract       Verify WAVE-2 tables, indexes, foreign keys, and checks.
  verify-app-privileges Prove app-account CRUD and DDL/GRANT/cross-schema denial.
  all                   Run every WAVE-2 migration and privilege verification.

The script never drops a database, never resets dev, and never needs the MySQL
root account. It uses the existing least-privilege migrator and app accounts.
USAGE
}

parse_arguments() {
  local command_set="false"
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
        [[ "$command_set" == "false" ]] || fail "Only one command may be supplied."
        COMMAND="$1"
        command_set="true"
        shift
        ;;
    esac
  done
}

require_execute() {
  case "$COMMAND" in
    migrate-dev | migrate-fresh-test | upgrade-v4-test | verify-app-privileges | all)
      [[ "$EXECUTE" == "true" ]] || fail "${COMMAND} requires --execute."
      ;;
  esac
}

load_runtime() {
  load_private_environment_file "$ENVIRONMENT_FILE"
  require_mysql_client
  require_flyway_client
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
WAVE-2 local MySQL verification plan
  connection allowlist : localhost|127.0.0.1 : 3306
  dev database         : shenzhou_hr_dev, forward migration only
  test database        : shenzhou_hr_test, table-level rebuild allowed
  migration account    : existing shenzhou_hr_local_migrator
  application accounts : existing non-root dev/test accounts
  migration paths      : empty test V1->latest and V1-V4->latest
  repeatability        : flyway validate plus second migrate no-op
  immutability         : pinned V1-V4 SHA-256 values
  privilege proof      : CRUD succeeds; DDL, GRANT, and cross-schema access fail
  password transport   : private 0600 env file to private mktemp client configs
  production/deploy    : forbidden
PLAN
}

verify_server_contract() {
  local defaults_file
  local server_version
  local server_hostname
  local server_port
  local server_charset
  local server_collation
  local default_engine
  local schema_count
  local incompatible_schema_count
  local non_innodb_count
  defaults_file="$(migrator_defaults_file)"

  server_version="$(mysql_scalar "$defaults_file" "" "SELECT VERSION();")"
  server_hostname="$(mysql_scalar "$defaults_file" "" "SELECT @@hostname;")"
  server_port="$(mysql_scalar "$defaults_file" "" "SELECT @@port;")"
  server_charset="$(mysql_scalar "$defaults_file" "" "SELECT @@character_set_server;")"
  server_collation="$(mysql_scalar "$defaults_file" "" "SELECT @@collation_server;")"
  default_engine="$(mysql_scalar "$defaults_file" "" "SELECT @@default_storage_engine;")"

  [[ -n "$server_version" && -n "$server_hostname" ]] \
    || fail "MySQL server identity could not be verified."
  [[ "$server_port" == "$SHENZHOUHR_MYSQL_PORT" ]] \
    || fail "MySQL server port differs from the authorized local port."
  [[ "$server_charset" == "utf8mb4" ]] \
    || fail "MySQL server character set is not utf8mb4."
  [[ "$server_collation" == "$REQUIRED_COLLATION" ]] \
    || fail "MySQL server collation is not utf8mb4_0900_ai_ci."
  case "$default_engine" in
    InnoDB | INNODB | innodb) ;;
    *) fail "MySQL default storage engine is not InnoDB." ;;
  esac

  schema_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME IN ('${DEV_DATABASE}', '${TEST_DATABASE}');
  ")"
  [[ "$schema_count" == "2" ]] \
    || fail "The two exact authorized databases are not both present."

  incompatible_schema_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME IN ('${DEV_DATABASE}', '${TEST_DATABASE}')
      AND (DEFAULT_CHARACTER_SET_NAME <> 'utf8mb4'
        OR DEFAULT_COLLATION_NAME <> '${REQUIRED_COLLATION}');
  ")"
  [[ "$incompatible_schema_count" == "0" ]] \
    || fail "An authorized database has an incompatible charset or collation."

  non_innodb_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA IN ('${DEV_DATABASE}', '${TEST_DATABASE}')
      AND TABLE_TYPE = 'BASE TABLE' AND ENGINE <> 'InnoDB';
  ")"
  [[ "$non_innodb_count" == "0" ]] \
    || fail "An authorized database contains a non-InnoDB base table."

  log "MYSQL_SERVER_CONTRACT=PASS version=${server_version} hostname=${server_hostname} port=${server_port} charset=${server_charset} collation=${server_collation} default_engine=${default_engine}"
}

assert_migration_source_checksums() {
  local expected
  local migration
  local actual
  while IFS='|' read -r migration expected; do
    actual="$(hash_file "${MIGRATION_DIR}/${migration}")"
    [[ "$actual" == "$expected" ]] \
      || fail "Immutable migration checksum changed: ${migration}."
    log "IMMUTABLE_MIGRATION_SHA256=PASS file=${migration} sha256=${actual}"
  done <<'CHECKSUMS'
V1__identity_organization_authorization_audit.sql|5f5cdd3367ef7ab128974b7fcad89a44bcfc5631008bd066806f77d33f85c440
V2__baseline_authorization_catalog.sql|28934279faafa2154faccd470976aa6ab2c4a5058f7d1b08a989c271da5241b4
V3__local_identity_session.sql|75120a31c594f2a974c031ed400d028028f83fb915df1b457902df27a971f9f9
V4__versioned_policy_foundation.sql|22b7f4b4b5ad21beec719c09aade3fc50b41a64d958951a690328ea38d2402a8
V5__people_initial_import_and_versioning.sql|6fd13a0e31a37d27fb7d9f71f8117acf7d5fb0bd38d4d117303b2b8582b6b589
V6__system_admin_people_read_prerequisite.sql|11762c79e34bea2ab6aa790a6b32c6466658eec03fd7a97b23fe540cf985c003
CHECKSUMS
}

assert_schema_exists() {
  local database="$1"
  local defaults_file="$2"
  local count
  assert_exact_database "$database"
  count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME = '${database}';
  ")"
  [[ "$count" == "1" ]] || fail "Required local database is missing: ${database}."
}

reset_test_tables() {
  local defaults_file
  local object_name
  local views
  local tables
  local sql="SET FOREIGN_KEY_CHECKS = 0;"
  defaults_file="$(migrator_defaults_file)"
  assert_schema_exists "$TEST_DATABASE" "$defaults_file"

  views="$(mysql_query "$defaults_file" "" "
    SELECT TABLE_NAME FROM information_schema.VIEWS
    WHERE TABLE_SCHEMA = '${TEST_DATABASE}' ORDER BY TABLE_NAME;
  ")"
  while IFS= read -r object_name; do
    [[ -z "$object_name" ]] && continue
    [[ "$object_name" =~ ^[A-Za-z0-9_]+$ ]] \
      || fail "Unexpected view name in the exact test schema."
    sql+=" DROP VIEW IF EXISTS \`${object_name}\`;"
  done <<< "$views"

  tables="$(mysql_query "$defaults_file" "" "
    SELECT TABLE_NAME FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${TEST_DATABASE}' AND TABLE_TYPE = 'BASE TABLE'
    ORDER BY TABLE_NAME;
  ")"
  while IFS= read -r object_name; do
    [[ -z "$object_name" ]] && continue
    [[ "$object_name" =~ ^[A-Za-z0-9_]+$ ]] \
      || fail "Unexpected table name in the exact test schema."
    sql+=" DROP TABLE IF EXISTS \`${object_name}\`;"
  done <<< "$tables"
  sql+=" SET FOREIGN_KEY_CHECKS = 1;"

  mysql_execute_quietly "$defaults_file" "$TEST_DATABASE" "$sql"
  log "TEST_TABLE_REBUILD=PASS database=${TEST_DATABASE}"
}

verify_second_migrate_noop() {
  local database="$1"
  local defaults_file
  local before_history
  local before_tables
  local after_history
  local after_tables
  defaults_file="$(migrator_defaults_file)"
  before_history="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;
  ")"
  before_tables="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${database}' AND TABLE_TYPE = 'BASE TABLE';
  ")"
  company_cutover_verify_latest "$database" "$defaults_file"
  run_flyway "$database" "-target=11" migrate
  after_history="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;
  ")"
  after_tables="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${database}' AND TABLE_TYPE = 'BASE TABLE';
  ")"
  [[ "$before_history" == "$after_history" && "$before_tables" == "$after_tables" ]] \
    || fail "Second migrate changed schema history or table count."
  log "SECOND_MIGRATE_NOOP=PASS database=${database} history=${after_history} tables=${after_tables}"
}

verify_company_cutover_preflight() {
  local database="$1"
  local defaults_file="$2"
  company_cutover_verify_v10 "$database" "$defaults_file"
}

company_dimension_names() {
  local defaults_file="$1"
  local company_table_count
  local legacy_table_count
  company_table_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${DEV_DATABASE}'
      AND TABLE_NAME = 'company'
      AND TABLE_TYPE = 'BASE TABLE';
  ")"
  legacy_table_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${DEV_DATABASE}'
      AND TABLE_NAME = 'legal_entity'
      AND TABLE_TYPE = 'BASE TABLE';
  ")"
  if [[ "$company_table_count" == "1" && "$legacy_table_count" == "0" ]]; then
    printf '%s\n' "company company_id"
    return
  fi
  if [[ "$company_table_count" == "0" && "$legacy_table_count" == "1" ]]; then
    printf '%s\n' "legal_entity legal_entity_id"
    return
  fi
  fail "Dev schema has an ambiguous top-level boundary shape."
}

verify_company_dimension_contract() {
  local database="$1"
  local defaults_file="$2"
  local company_table_count
  local legacy_table_count
  local company_column_count
  local legacy_column_count
  local invalid_scope_count
  local legacy_scope_count
  local company_scope_check_count
  local v11_count
  assert_exact_database "$database"
  company_cutover_verify_latest "$database" "$defaults_file"

  company_table_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${database}'
      AND TABLE_NAME = 'company'
      AND TABLE_TYPE = 'BASE TABLE';
  ")"
  legacy_table_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${database}'
      AND TABLE_NAME = 'legal_entity';
  ")"
  company_column_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = '${database}'
      AND COLUMN_NAME = 'company_id'
      AND TABLE_NAME IN (
        'company', 'employee', 'organization_identity', 'auth_data_scope',
        'people_import_batch', 'people_import_publication', 'location',
        'shift_template', 'work_calendar', 'attendance_group',
        'attendance_policy_scope', 'attendance_source', 'source_device',
        'device_person_binding', 'attendance_evidence_subject_lock',
        'raw_attendance_fact', 'effective_attendance_event',
        'duplicate_review_group', 'evidence_interval_slice',
        'attendance_recalculation_intent', 'punch_mapping_profile',
        'punch_import_batch', 'punch_import_file',
        'attendance_report_projection', 'attendance_report_daily_fact',
        'attendance_report_oa_fact', 'attendance_report_exception_fact',
        'attendance_report_time_account_fact', 'attendance_report_export_job'
      );
  ")"
  legacy_column_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = '${database}'
      AND COLUMN_NAME = 'legal_entity_id';
  ")"
  invalid_scope_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM auth_data_scope
    WHERE scope_type NOT IN ('COMPANY', 'ORGANIZATION', 'SELF');
  ")"
  legacy_scope_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM auth_data_scope
    WHERE scope_type = 'LEGAL_ENTITY';
  ")"
  company_scope_check_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND CONSTRAINT_NAME = 'ck_auth_scope_target'
      AND UPPER(CHECK_CLAUSE) LIKE '%COMPANY%'
      AND LOWER(CHECK_CLAUSE) LIKE '%company_id%';
  ")"
  v11_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM flyway_schema_history
    WHERE version = '11' AND type = 'SQL' AND success = 1;
  ")"

  [[ "$company_table_count" == "1" && "$legacy_table_count" == "0" ]] \
    || fail "Latest schema does not expose exactly one company table."
  [[ "$company_column_count" == "29" && "$legacy_column_count" == "0" ]] \
    || fail "Latest schema does not expose the complete company_id boundary."
  [[ "$invalid_scope_count" == "0" && "$legacy_scope_count" == "0" ]] \
    || fail "Latest schema contains a non-company authorization scope value."
  [[ "$company_scope_check_count" == "1" ]] \
    || fail "Latest company authorization scope check is missing."
  [[ "$v11_count" == "1" ]] || fail "V11 company migration is not successful."
  log "COMPANY_DIMENSION_CONTRACT=PASS database=${database} company_columns=${company_column_count} scopes=COMPANY,ORGANIZATION,SELF"
}

core_row_counts() {
  local defaults_file="$1"
  local boundary_table
  boundary_table="$(company_dimension_names "$defaults_file")"
  boundary_table="${boundary_table%% *}"
  mysql_query "$defaults_file" "$DEV_DATABASE" "
    SELECT CONCAT(
      (SELECT COUNT(*) FROM ${boundary_table}), '|',
      (SELECT COUNT(*) FROM organization_identity), '|',
      (SELECT COUNT(*) FROM employee), '|',
      (SELECT COUNT(*) FROM employment_assignment), '|',
      (SELECT COUNT(*) FROM audit_event), '|',
      (SELECT COUNT(*) FROM policy_template
       WHERE template_code NOT IN (
         'ATTENDANCE_MEAL_DEDUCTION',
         'ATTENDANCE_LATE_GRACE',
         'ATTENDANCE_SINGLE_MISSING_PUNCH'
       ))
    );
  "
}

core_data_fingerprint() {
  local defaults_file="$1"
  local boundary_table
  local boundary_id_column
  local snapshot_file
  read -r boundary_table boundary_id_column \
    <<< "$(company_dimension_names "$defaults_file")"
  snapshot_file="$(make_private_temporary_file)"
  mysql_query "$defaults_file" "$DEV_DATABASE" "
    SELECT stable_row
    FROM (
      SELECT CONCAT_WS('|', 'company', ${boundary_id_column}, code, name, status, created_at)
        AS stable_row
      FROM ${boundary_table}
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'employee', employee_id, ${boundary_id_column}, display_name,
        employment_status, COALESCE(CAST(onboard_date AS CHAR), '<NULL>'),
        created_at, updated_at)
      FROM employee
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'organization_identity', organization_id, ${boundary_id_column},
        identity_status, created_at)
      FROM organization_identity
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'organization_version', organization_version_id, organization_id,
        COALESCE(parent_organization_id, '<NULL>'), code, name, org_type,
        effective_from, COALESCE(CAST(effective_to AS CHAR), '<NULL>'),
        COALESCE(formal_sync_batch_id, '<NULL>'), row_version)
      FROM organization_version
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'organization_projection', organization_id, current_version_id,
        projection_batch_id, projected_at)
      FROM organization_current_projection
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'organization_closure', ancestor_organization_id,
        descendant_organization_id, depth, projection_batch_id)
      FROM organization_current_closure
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'employment_assignment', assignment_id, employee_id, organization_id,
        COALESCE(position_id, '<NULL>'), COALESCE(payroll_plan_id, '<NULL>'),
        COALESCE(cost_center_id, '<NULL>'), effective_from,
        COALESCE(CAST(effective_to AS CHAR), '<NULL>'))
      FROM employment_assignment
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'employee_source_binding', binding_id, employee_id,
        COALESCE(seeyon_person_id, '<NULL>'), COALESCE(seeyon_oa_code, '<NULL>'),
        COALESCE(deli_user_id, '<NULL>'), COALESCE(deli_ext_id, '<NULL>'),
        COALESCE(deli_employee_num, '<NULL>'), binding_status, effective_from,
        COALESCE(CAST(effective_to AS CHAR), '<NULL>'), source,
        COALESCE(confirmation_ref, '<NULL>'))
      FROM employee_source_binding
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'policy_template', template_id, template_code, name, description,
        CAST(field_definitions_json AS CHAR), status, row_version, created_by,
        created_at, updated_by, updated_at)
      FROM policy_template
      WHERE template_code NOT IN (
        'ATTENDANCE_MEAL_DEDUCTION',
        'ATTENDANCE_LATE_GRACE',
        'ATTENDANCE_SINGLE_MISSING_PUNCH'
      )
    ) stable
    ORDER BY stable_row;
  " > "$snapshot_file"
  hash_file "$snapshot_file"
}

repair_failed_wave2_migration() {
  local defaults_file
  local failed_count
  local partial_tables
  local remaining_failed_count
  defaults_file="$(migrator_defaults_file)"
  failed_count="$(mysql_scalar "$defaults_file" "$DEV_DATABASE" "
    SELECT COUNT(*) FROM flyway_schema_history
    WHERE version = '5' AND success = 0;
  ")"
  [[ "$failed_count" == "0" || "$failed_count" == "1" ]] \
    || fail "Unexpected number of failed V5 history rows."
  [[ "$failed_count" == "1" ]] || return 0

  partial_tables="$(mysql_query "$defaults_file" "" "
    SELECT TABLE_NAME FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${DEV_DATABASE}'
      AND TABLE_NAME IN (
        'people_import_batch', 'people_import_file', 'people_import_diff',
        'people_import_issue', 'people_import_publication', 'people_import_rollback',
        'people_idempotency_record', 'employee_version',
        'employee_current_projection', 'prior_service_record'
      )
    ORDER BY TABLE_NAME;
  ")"
  [[ "$partial_tables" == $'people_import_batch\npeople_import_file' ]] \
    || fail "Failed V5 left an unexpected partial object set; automatic repair stopped."

  run_flyway "$DEV_DATABASE" repair
  remaining_failed_count="$(mysql_scalar "$defaults_file" "$DEV_DATABASE" "
    SELECT COUNT(*) FROM flyway_schema_history
    WHERE version = '5' AND success = 0;
  ")"
  [[ "$remaining_failed_count" == "0" ]] \
    || fail "Flyway repair did not remove the failed V5 history row."
  log "FAILED_V5_REPAIR=PASS preserved_partial_tables=people_import_batch,people_import_file"
}

migrate_dev() {
  local defaults_file
  local before
  local after
  local before_fingerprint
  local after_fingerprint
  defaults_file="$(migrator_defaults_file)"
  assert_schema_exists "$DEV_DATABASE" "$defaults_file"
  assert_migration_source_checksums
  repair_failed_wave2_migration
  before="$(core_row_counts "$defaults_file")"
  before_fingerprint="$(core_data_fingerprint "$defaults_file")"
  company_cutover_migrate_to_v11 "$DEV_DATABASE" "$defaults_file"
  run_flyway "$DEV_DATABASE" validate
  verify_company_dimension_contract "$DEV_DATABASE" "$defaults_file"
  after="$(core_row_counts "$defaults_file")"
  after_fingerprint="$(core_data_fingerprint "$defaults_file")"
  [[ "$before" == "$after" ]] || fail "WAVE-1 core row counts changed during dev migration."
  [[ "$before_fingerprint" == "$after_fingerprint" ]] \
    || fail "WAVE-1 stable core data changed during dev migration."
  verify_second_migrate_noop "$DEV_DATABASE"
  log "DEV_FORWARD_MIGRATION=PASS wave1_core_rows=${after} stable_sha256=${after_fingerprint}"
}

migrate_fresh_test() {
  local defaults_file
  local minimum_version
  reset_test_tables
  assert_migration_source_checksums
  defaults_file="$(migrator_defaults_file)"
  company_cutover_migrate_to_v11 "$TEST_DATABASE" "$defaults_file"
  run_flyway "$TEST_DATABASE" validate
  verify_company_dimension_contract "$TEST_DATABASE" "$defaults_file"
  minimum_version="$(mysql_scalar "$defaults_file" "$TEST_DATABASE" "
    SELECT MIN(CAST(version AS UNSIGNED))
    FROM flyway_schema_history WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$minimum_version" == "1" ]] || fail "Fresh test migration did not begin at V1."
  verify_second_migrate_noop "$TEST_DATABASE"
  log "FRESH_TEST_V1_TO_LATEST=PASS"
}

upgrade_v4_test() {
  local defaults_file
  local checkpoint
  reset_test_tables
  assert_migration_source_checksums
  run_flyway "$TEST_DATABASE" "-target=4" migrate
  defaults_file="$(migrator_defaults_file)"
  checkpoint="$(mysql_scalar "$defaults_file" "$TEST_DATABASE" "
    SELECT MAX(CAST(version AS UNSIGNED))
    FROM flyway_schema_history WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$checkpoint" == "4" ]] || fail "Upgrade checkpoint did not stop at V4."
  company_cutover_migrate_to_v11 "$TEST_DATABASE" "$defaults_file"
  run_flyway "$TEST_DATABASE" validate
  verify_company_dimension_contract "$TEST_DATABASE" "$defaults_file"
  verify_second_migrate_noop "$TEST_DATABASE"
  log "V1_V4_TO_LATEST_UPGRADE=PASS"
}

verify_contract_for_database() {
  local database="$1"
  local defaults_file
  local table_count
  local index_count
  local foreign_key_count
  local check_count
  local migration_versions
  local retired_sync_capability_count
  assert_exact_database "$database"
  defaults_file="$(migrator_defaults_file)"
  assert_schema_exists "$database" "$defaults_file"
  verify_company_dimension_contract "$database" "$defaults_file"

  table_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${database}'
      AND TABLE_NAME IN (
        'people_import_batch', 'people_import_file', 'people_import_diff',
        'people_import_issue', 'people_import_publication', 'people_import_rollback',
        'people_idempotency_record', 'employee_version',
        'employee_current_projection', 'prior_service_record'
      );
  ")"
  [[ "$table_count" == "10" ]] || fail "A required WAVE-2 table is missing."

  index_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(DISTINCT INDEX_NAME)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = '${database}'
      AND INDEX_NAME IN (
        'ix_people_import_scope_status',
        'ix_people_import_file_hash',
        'uq_people_import_file_batch',
        'ix_people_import_file_sha',
        'ix_people_import_diff_batch_category',
        'ix_people_import_issue_batch_severity',
        'uq_people_publication_batch',
        'uq_people_publication_scoped_file_hash',
        'uq_people_publication_idempotency',
        'uq_people_rollback_publication',
        'uq_people_rollback_idempotency',
        'uq_people_idempotency_actor_action_key',
        'ix_org_version_source_batch',
        'uq_employee_number',
        'uq_employee_version_current',
        'ix_employee_version_period',
        'ix_employee_version_number',
        'ix_employee_version_source_batch',
        'uq_employee_current_version',
        'ix_assignment_source_batch',
        'ix_assignment_period_version',
        'uq_assignment_current_period',
        'uq_prior_service_request',
        'uq_prior_service_sequence',
        'ix_prior_service_employee_time',
        'ix_prior_service_source_batch'
      );
  ")"
  [[ "$index_count" == "26" ]] || fail "A required WAVE-2 index is missing."

  foreign_key_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND CONSTRAINT_NAME IN (
        'fk_people_import_legal_entity',
        'fk_people_import_created_by',
        'fk_people_import_updated_by',
        'fk_people_import_duplicate_publication',
        'fk_people_import_file_batch',
        'fk_people_import_file_actor',
        'fk_people_import_diff_batch',
        'fk_people_import_issue_batch',
        'fk_people_publication_batch',
        'fk_people_publication_legal_entity',
        'fk_people_publication_actor',
        'fk_people_rollback_batch',
        'fk_people_rollback_publication',
        'fk_people_rollback_actor',
        'fk_people_idempotency_actor',
        'fk_org_version_source_batch',
        'fk_org_version_created_by',
        'fk_employee_version_employee',
        'fk_employee_version_source_batch',
        'fk_employee_version_created_by',
        'fk_employee_projection_employee',
        'fk_employee_projection_version',
        'fk_assignment_source_batch',
        'fk_assignment_created_by',
        'fk_prior_service_employee',
        'fk_prior_service_source_batch',
        'fk_prior_service_reversal',
        'fk_prior_service_actor'
      );
  ")"
  [[ "$foreign_key_count" == "28" ]] || fail "WAVE-2 foreign-key contract is incomplete."

  check_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND CONSTRAINT_TYPE = 'CHECK'
      AND CONSTRAINT_NAME IN (
        'ck_people_import_template_type',
        'ck_people_import_status',
        'ck_people_import_file_size',
        'ck_people_import_diff_category',
        'ck_people_import_issue_severity',
        'ck_org_version_source_authority',
        'ck_org_version_status',
        'ck_employee_version_status',
        'ck_employee_version_period',
        'ck_employee_version_source',
        'ck_assignment_version_period',
        'ck_assignment_record_status',
        'ck_prior_service_type'
      );
  ")"
  [[ "$check_count" == "13" ]] || fail "WAVE-2 check-constraint contract is incomplete."

  migration_versions="$(mysql_scalar "$defaults_file" "$database" "
    SELECT GROUP_CONCAT(version ORDER BY installed_rank SEPARATOR ',')
    FROM flyway_schema_history
    WHERE type = 'SQL' AND success = 1
      AND version IN ('1', '2', '3', '4', '5', '6');
  ")"
  [[ "$migration_versions" == "1,2,3,4,5,6" ]] \
    || fail "The retained database does not contain exactly the V1-V6 migration path."

  retired_sync_capability_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM auth_capability
    WHERE capability_code = 'MASTER_DATA:SYNC_PREVIEW';
  ")"
  [[ "$retired_sync_capability_count" == "0" ]] \
    || fail "The retired organization synchronization capability is still present."

  log "WAVE2_SCHEMA_CONTRACT=PASS database=${database} migrations=${migration_versions} tables=${table_count} indexes=${index_count} foreign_keys=${foreign_key_count} checks=${check_count} retired_sync_capability=0"
}

verify_contract() {
  verify_contract_for_database "$DEV_DATABASE"
  verify_contract_for_database "$TEST_DATABASE"
}

verify_databases_preserved() {
  local defaults_file
  local schema_count
  defaults_file="$(migrator_defaults_file)"
  schema_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME IN ('${DEV_DATABASE}', '${TEST_DATABASE}');
  ")"
  [[ "$schema_count" == "2" ]] \
    || fail "The two authorized databases were not both preserved."
  log "FINAL_DATABASE_STATE=PASS dev=PRESERVED test=PRESERVED"
}

verify_app_privileges() {
  local migrator_defaults
  local dev_defaults
  local test_defaults
  local database
  local app_defaults
  local app_account
  local marker
  migrator_defaults="$(migrator_defaults_file)"
  WAVE2_MIGRATOR_DEFAULTS="$migrator_defaults"
  WAVE2_PROBE_CLEANUP_ENABLED="true"
  dev_defaults="$(dev_app_defaults_file)"
  test_defaults="$(test_app_defaults_file)"

  for database in "$DEV_DATABASE" "$TEST_DATABASE"; do
    if [[ "$database" == "$DEV_DATABASE" ]]; then
      app_defaults="$dev_defaults"
      app_account="$DEV_APP_ACCOUNT"
    else
      app_defaults="$test_defaults"
      app_account="$TEST_APP_ACCOUNT"
    fi
    mysql_execute_quietly "$migrator_defaults" "$database" "
      DROP TABLE IF EXISTS wave2_app_privilege_probe;
      CREATE TABLE wave2_app_privilege_probe (
        id INT NOT NULL PRIMARY KEY,
        marker VARCHAR(32) NOT NULL
      ) ENGINE=InnoDB;
    "
    mysql_execute_quietly "$app_defaults" "$database" "
      INSERT INTO wave2_app_privilege_probe (id, marker) VALUES (1, 'created');
      UPDATE wave2_app_privilege_probe SET marker = 'updated' WHERE id = 1;
    "
    marker="$(mysql_scalar "$app_defaults" "$database" "
      SELECT marker FROM wave2_app_privilege_probe WHERE id = 1;
    ")"
    [[ "$marker" == "updated" ]] || fail "App CRUD probe returned an unexpected value."
    mysql_execute_quietly "$app_defaults" "$database" "
      DELETE FROM wave2_app_privilege_probe WHERE id = 1;
    "
    expect_mysql_failure "$app_defaults" "$database" \
      "CREATE TABLE denied_wave2_probe (id INT);" "${database}_APP_CREATE"
    expect_mysql_failure "$app_defaults" "$database" \
      "ALTER TABLE wave2_app_privilege_probe ADD denied INT;" "${database}_APP_ALTER"
    expect_mysql_failure "$app_defaults" "$database" \
      "DROP TABLE wave2_app_privilege_probe;" "${database}_APP_DROP"
    expect_mysql_failure "$app_defaults" "$database" \
      "GRANT SELECT ON ${database}.* TO '${app_account}'@'${ACCOUNT_HOST}';" \
      "${database}_APP_GRANT"
    mysql_execute_quietly "$migrator_defaults" "$database" \
      "DROP TABLE wave2_app_privilege_probe;"
  done

  expect_mysql_failure "$dev_defaults" "" \
    "SHOW TABLES FROM ${TEST_DATABASE};" "DEV_APP_CROSS_SCHEMA"
  expect_mysql_failure "$test_defaults" "" \
    "SHOW TABLES FROM ${DEV_DATABASE};" "TEST_APP_CROSS_SCHEMA"
  WAVE2_PROBE_CLEANUP_ENABLED="false"
  log "APP_ACCOUNT_PRIVILEGES=PASS"
}

run_all() {
  verify_server_contract
  migrate_dev
  migrate_fresh_test
  verify_contract
  upgrade_v4_test
  verify_contract
  verify_app_privileges
  verify_databases_preserved
  log "WAVE2_LOCAL_MYSQL_VERIFICATION=PASS"
}

dispatch() {
  case "$COMMAND" in
    plan)
      print_plan
      ;;
    migrate-dev)
      load_runtime
      migrate_dev
      ;;
    migrate-fresh-test)
      load_runtime
      migrate_fresh_test
      ;;
    upgrade-v4-test)
      load_runtime
      upgrade_v4_test
      ;;
    verify-contract)
      load_runtime
      verify_contract
      ;;
    verify-app-privileges)
      load_runtime
      verify_app_privileges
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
require_execute
dispatch
