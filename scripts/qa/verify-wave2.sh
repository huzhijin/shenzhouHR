#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[verify-wave2] ERROR: shell tracing must be disabled.\n' >&2
  exit 1
fi

readonly EXPECTED_REPOSITORY_ROOT="/Users/huzhijin/Downloads/shenzhouHR"
readonly INVOCATION_ROOT="$(pwd -P)"
if [[ "$INVOCATION_ROOT" != "$EXPECTED_REPOSITORY_ROOT" ]]; then
  printf 'PROJECT_ROOT_SCOPE_ERROR\n' >&2
  exit 1
fi

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd -P)"
if [[ "$REPOSITORY_ROOT" != "$EXPECTED_REPOSITORY_ROOT" ]]; then
  printf 'PROJECT_ROOT_SCOPE_ERROR\n' >&2
  exit 1
fi
# shellcheck source=../../deploy/mysql/lib/mysql-safety.sh
source "${REPOSITORY_ROOT}/deploy/mysql/lib/mysql-safety.sh"

ENVIRONMENT_FILE=""
LOGIN_ENVIRONMENT_FILE="/Users/huzhijin/.config/shenzhouhr/wave1-login.env"
EXECUTE="false"
COMMAND="static"
COMMAND_SET="false"
readonly RUNTIME_ROOT="${REPOSITORY_ROOT}/docs/verification/wave2/runtime"
readonly MYSQL_EVIDENCE_ROOT="${RUNTIME_ROOT}/mysql"

usage() {
  cat <<'USAGE'
Usage:
  verify-wave2.sh [--env-file /absolute/wave2-runtime.env]
                  [--login-env-file /absolute/wave1-login.env]
                  [--execute] COMMAND

Commands:
  static    Run OpenAPI contract, all backend tests, every requested frontend
            command, immutable checksums, and source-boundary scans.
  mysql     Run the real WAVE-2 MySQL all path and capture detailed evidence.
  runtime   Mutate the dev database through real WAVE-2 APIs and verify the
            current 8080 backend with the synthetic administrator.
  evidence  Verify AC-PEOPLE-01..12, screenshots, manifest, and final report.
  all       Intentionally rejected: browser evidence and runId-bound delivery
            artifacts must be captured after runtime in a staged workflow.

The mysql, runtime, and all commands require --execute.
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
      --login-env-file)
        [[ $# -ge 2 ]] || fail "--login-env-file requires a value."
        LOGIN_ENVIRONMENT_FILE="$2"
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
        [[ "$COMMAND_SET" == "false" ]] || fail "Only one command may be supplied."
        COMMAND="$1"
        COMMAND_SET="true"
        shift
        ;;
    esac
  done
}

require_execute() {
  case "$COMMAND" in
    mysql | runtime | all)
      [[ "$EXECUTE" == "true" ]] || fail "${COMMAND} requires --execute."
      ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1."
}

assert_allowed_scan_target() {
  local requested_path="$1"
  local resolved_path
  case "$requested_path" in
    "${REPOSITORY_ROOT}/api" | \
    "${REPOSITORY_ROOT}/backend/src" | \
    "${REPOSITORY_ROOT}/frontend/src" | \
    "${REPOSITORY_ROOT}/frontend/package.json" | \
    "${REPOSITORY_ROOT}/frontend/package-lock.json" | \
    "${REPOSITORY_ROOT}/deploy" | \
    "${REPOSITORY_ROOT}/scripts/qa" | \
    "${REPOSITORY_ROOT}/docs/verification/wave1" | \
    "${REPOSITORY_ROOT}/docs/verification/wave2" | \
    "${REPOSITORY_ROOT}/design/open-design/v1.9" | \
    "${REPOSITORY_ROOT}/README.md" | \
    "${REPOSITORY_ROOT}/AGENTS.md" | \
    "${REPOSITORY_ROOT}/umadev.yaml")
      ;;
    *)
      fail "Scan target is outside the explicit WAVE-2 allowlist."
      ;;
  esac
  resolved_path="$(ruby -e 'print File.realpath(ARGV.fetch(0))' "$requested_path")" \
    || fail "Cannot resolve an allowed scan target."
  case "$resolved_path" in
    "$REPOSITORY_ROOT" | "$REPOSITORY_ROOT"/*)
      ;;
    *)
      fail "Resolved scan target is outside the project root."
      ;;
  esac
  printf '%s' "$resolved_path"
}

verify_allowed_scan_scope() {
  local target
  for target in \
    "${REPOSITORY_ROOT}/api" \
    "${REPOSITORY_ROOT}/backend/src" \
    "${REPOSITORY_ROOT}/frontend/src" \
    "${REPOSITORY_ROOT}/frontend/package.json" \
    "${REPOSITORY_ROOT}/frontend/package-lock.json" \
    "${REPOSITORY_ROOT}/deploy" \
    "${REPOSITORY_ROOT}/scripts/qa" \
    "${REPOSITORY_ROOT}/docs/verification/wave1" \
    "${REPOSITORY_ROOT}/docs/verification/wave2" \
    "${REPOSITORY_ROOT}/design/open-design/v1.9" \
    "${REPOSITORY_ROOT}/README.md" \
    "${REPOSITORY_ROOT}/AGENTS.md" \
    "${REPOSITORY_ROOT}/umadev.yaml"; do
    assert_allowed_scan_target "$target" >/dev/null
  done
  log "PROJECT_ROOT_SCOPE=PASS root=${REPOSITORY_ROOT}"
}

run_forbidden_scan() {
  local findings_file="$1"
  local failure_message="$2"
  local report_files="$3"
  local status
  shift 3

  : >"$findings_file"
  if rg "$@" >"$findings_file"; then
    status=0
  else
    status=$?
  fi

  case "$status" in
    0)
      if [[ "$report_files" == "true" ]]; then
        while IFS= read -r path; do
          [[ -n "$path" ]] && log "GOVERNANCE_FINDING_FILE=${path}"
        done <"$findings_file"
      fi
      fail "$failure_message"
      ;;
    1)
      ;;
    *)
      fail "Governance scan failed with exit status ${status}: ${failure_message}"
      ;;
  esac
}

run_static_verification() {
  require_command rg
  require_command npm
  require_command ruby
  require_command shasum

  (cd "${REPOSITORY_ROOT}/backend" \
    && ./mvnw -Dtest=com.szsemicon.hr.wave2.Wave2OpenApiContractTest test)
  (cd "${REPOSITORY_ROOT}/backend" && ./mvnw test)
  (cd "${REPOSITORY_ROOT}/frontend" && npm run lint)
  (cd "${REPOSITORY_ROOT}/frontend" && npm run test)
  (cd "${REPOSITORY_ROOT}/frontend" && npm run build)
  (cd "${REPOSITORY_ROOT}/frontend" && npm run build:demo)
  (cd "${REPOSITORY_ROOT}/frontend" && npm run check)

  assert_immutable_migration_checksums
  verify_source_boundaries
  log "WAVE2_STATIC_VERIFICATION=PASS"
}

assert_immutable_migration_checksums() {
  local migration
  local expected
  local actual
  while IFS='|' read -r migration expected; do
    actual="$(shasum -a 256 "${REPOSITORY_ROOT}/backend/src/main/resources/db/migration/${migration}" | awk '{print $1}')"
    [[ "$actual" == "$expected" ]] || fail "Immutable migration changed: ${migration}."
  done <<'CHECKSUMS'
V1__identity_organization_authorization_audit.sql|5f5cdd3367ef7ab128974b7fcad89a44bcfc5631008bd066806f77d33f85c440
V2__baseline_authorization_catalog.sql|28934279faafa2154faccd470976aa6ab2c4a5058f7d1b08a989c271da5241b4
V3__local_identity_session.sql|75120a31c594f2a974c031ed400d028028f83fb915df1b457902df27a971f9f9
V4__versioned_policy_foundation.sql|22b7f4b4b5ad21beec719c09aade3fc50b41a64d958951a690328ea38d2402a8
V5__people_initial_import_and_versioning.sql|6fd13a0e31a37d27fb7d9f71f8117acf7d5fb0bd38d4d117303b2b8582b6b589
V6__system_admin_people_read_prerequisite.sql|11762c79e34bea2ab6aa790a6b32c6466658eec03fd7a97b23fe540cf985c003
CHECKSUMS
  log "WAVE1_WAVE2_MIGRATION_SOURCE_CHECKSUMS=PASS"
}

verify_source_boundaries() {
  local findings
  local open_design_root
  verify_allowed_scan_scope
  findings="$(make_private_temporary_file)"
  open_design_root="$(assert_allowed_scan_target \
    "${REPOSITORY_ROOT}/design/open-design/v1.9")"

  run_forbidden_scan "$findings" \
    "PAYROLL discoverability exists in frontend production source." false \
    -l -i '/payroll\b|/payslips\b|工资条|薪资核算' \
    "${REPOSITORY_ROOT}/frontend/src" \
    -g '*.ts' -g '*.tsx' -g '!*.test.*'

  run_forbidden_scan "$findings" \
    "A PAYROLL public route exists in the OpenAPI contract." false \
    -l -i \
    '^[[:space:]]*/(payroll|payslips)' \
    "${REPOSITORY_ROOT}/api/openapi.yaml"

  run_forbidden_scan "$findings" \
    "Organization synchronization remains discoverable in production source." false \
    -l -i \
    'organization[-_/ ]?sync|syncOrganization|MASTER_DATA_SYNC_PREVIEW|组织.*同步|双向同步|持续同步' \
    "${REPOSITORY_ROOT}/api/openapi.yaml" \
    "${REPOSITORY_ROOT}/backend/src/main/java" \
    "${REPOSITORY_ROOT}/frontend/src" \
    -g '*.java' -g '*.ts' -g '*.tsx' -g '!*.test.*'

  run_forbidden_scan "$findings" \
    "User-visible Chinese is hardcoded in a production TSX component." false \
    -l '[\p{Han}]' \
    "${REPOSITORY_ROOT}/frontend/src" \
    -g '*.tsx' -g '!*.test.tsx'

  run_forbidden_scan "$findings" \
    "A database credential assignment exists in the scanned source/delivery scope." true \
    -l --hidden --no-ignore --pcre2 \
    'SHENZHOUHR_(MYSQL_ROOT_PASSWORD|FLYWAY_PASSWORD|DEV_DB_PASSWORD|TEST_DB_PASSWORD)[[:space:]]*\x3d[[:space:]]*\S+' \
      "${REPOSITORY_ROOT}/api" \
      "${REPOSITORY_ROOT}/backend/src" \
      "${REPOSITORY_ROOT}/frontend/src" \
      "${REPOSITORY_ROOT}/frontend/package.json" \
      "${REPOSITORY_ROOT}/frontend/package-lock.json" \
      "${REPOSITORY_ROOT}/deploy" \
      "${REPOSITORY_ROOT}/scripts/qa" \
      "${REPOSITORY_ROOT}/docs/verification/wave1" \
      "${REPOSITORY_ROOT}/docs/verification/wave2" \
      "$open_design_root" \
      "${REPOSITORY_ROOT}/README.md" \
      "${REPOSITORY_ROOT}/AGENTS.md" \
      "${REPOSITORY_ROOT}/umadev.yaml"

  run_forbidden_scan "$findings" \
    "A login credential assignment exists in the scanned source/delivery scope." true \
    -l --hidden --no-ignore --pcre2 \
    'SHENZHOUHR_LOGIN_PASSWORD[[:space:]]*\x3d[[:space:]]*\S+' \
      "${REPOSITORY_ROOT}/api" \
      "${REPOSITORY_ROOT}/backend/src" \
      "${REPOSITORY_ROOT}/frontend/src" \
      "${REPOSITORY_ROOT}/frontend/package.json" \
      "${REPOSITORY_ROOT}/frontend/package-lock.json" \
      "${REPOSITORY_ROOT}/deploy" \
      "${REPOSITORY_ROOT}/scripts/qa" \
      "${REPOSITORY_ROOT}/docs/verification/wave1" \
      "${REPOSITORY_ROOT}/docs/verification/wave2" \
      "$open_design_root" \
      "${REPOSITORY_ROOT}/README.md" \
      "${REPOSITORY_ROOT}/AGENTS.md" \
      "${REPOSITORY_ROOT}/umadev.yaml"

  run_forbidden_scan "$findings" \
    "A private key, known token, or credential-bearing URL exists in the scanned source/delivery scope." true \
    -l --hidden --no-ignore --pcre2 \
    '(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:sk-(?:proj-)?|ghp_|github_pat_|xox[baprs]-|AIza)[A-Za-z0-9_=-]{16,}|[a-z][a-z0-9+.-]*://[^/@[:space:]:]+:[^/@[:space:]]+@)' \
      "${REPOSITORY_ROOT}/api" \
      "${REPOSITORY_ROOT}/backend/src" \
      "${REPOSITORY_ROOT}/frontend/src" \
      "${REPOSITORY_ROOT}/frontend/package.json" \
      "${REPOSITORY_ROOT}/frontend/package-lock.json" \
      "${REPOSITORY_ROOT}/deploy" \
      "${REPOSITORY_ROOT}/scripts/qa" \
      "${REPOSITORY_ROOT}/docs/verification/wave1" \
      "${REPOSITORY_ROOT}/docs/verification/wave2" \
      "$open_design_root" \
      "${REPOSITORY_ROOT}/README.md" \
      "${REPOSITORY_ROOT}/AGENTS.md" \
      "${REPOSITORY_ROOT}/umadev.yaml"

  log "PAYROLL_DISCOVERABILITY=ZERO"
  log "PAYROLL_PUBLIC_ROUTES=ZERO"
  log "ORGANIZATION_SYNC_DISCOVERABILITY=ZERO"
  log "USER_VISIBLE_CJK_HARDCODING=ZERO"
  log "SOURCE_SECRET_SCAN=PASS signatures=db-password,login-password,private-key,known-token,credential-url scope=api,backend/src,frontend/src,frontend/package.json,frontend/package-lock.json,deploy,scripts/qa,docs/verification/wave1,docs/verification/wave2,design/open-design/v1.9,README.md,AGENTS.md,umadev.yaml"
}

run_mysql_verification() {
  [[ -n "$ENVIRONMENT_FILE" ]] || fail "--env-file is required for mysql verification."
  mkdir -p "$MYSQL_EVIDENCE_ROOT"
  bash "${REPOSITORY_ROOT}/deploy/mysql/wave2-local-mysql.sh" \
    --env-file "$ENVIRONMENT_FILE" \
    --execute all >"${MYSQL_EVIDENCE_ROOT}/wave2-local-mysql.log" 2>&1
  capture_mysql_evidence
  log "WAVE2_MYSQL_EVIDENCE=PASS path=${MYSQL_EVIDENCE_ROOT}"
}

capture_mysql_evidence() {
  local migrator_defaults
  local dev_defaults
  local test_defaults
  load_private_environment_file "$ENVIRONMENT_FILE"
  migrator_defaults="$(create_mysql_defaults_file "$MIGRATOR_ACCOUNT" "$SHENZHOUHR_FLYWAY_PASSWORD")"
  dev_defaults="$(create_mysql_defaults_file "$DEV_APP_ACCOUNT" "$SHENZHOUHR_DEV_DB_PASSWORD")"
  test_defaults="$(create_mysql_defaults_file "$TEST_APP_ACCOUNT" "$SHENZHOUHR_TEST_DB_PASSWORD")"

  mysql_query_with_headers "$migrator_defaults" "" "
    SELECT 'server_version' AS metric, VERSION() AS value
    UNION ALL SELECT 'server_hostname', @@hostname
    UNION ALL SELECT 'server_port', CAST(@@port AS CHAR)
    UNION ALL SELECT 'character_set_server', @@character_set_server
    UNION ALL SELECT 'collation_server', @@collation_server
    UNION ALL SELECT 'default_storage_engine', @@default_storage_engine;
  " >"${MYSQL_EVIDENCE_ROOT}/server-metadata.tsv"

  mysql_query_with_headers "$migrator_defaults" "" "
    SELECT 'shenzhou_hr_dev' AS database_name, installed_rank, version,
           description, type, script, checksum, installed_on, execution_time, success
    FROM shenzhou_hr_dev.flyway_schema_history
    UNION ALL
    SELECT 'shenzhou_hr_test', installed_rank, version,
           description, type, script, checksum, installed_on, execution_time, success
    FROM shenzhou_hr_test.flyway_schema_history
    ORDER BY database_name, installed_rank;
  " >"${MYSQL_EVIDENCE_ROOT}/flyway-history.tsv"

  mysql_query_with_headers "$migrator_defaults" "" "
    SELECT TABLE_SCHEMA, TABLE_NAME, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA IN ('${DEV_DATABASE}', '${TEST_DATABASE}')
      AND TABLE_NAME IN (
        'people_import_batch', 'people_import_file', 'people_import_diff',
        'people_import_issue', 'people_import_publication', 'people_import_rollback',
        'people_idempotency_record', 'employee_version',
        'employee_current_projection', 'prior_service_record'
      )
    ORDER BY TABLE_SCHEMA, TABLE_NAME;
  " >"${MYSQL_EVIDENCE_ROOT}/tables.tsv"

  mysql_query_with_headers "$migrator_defaults" "" "
    SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, NON_UNIQUE,
           GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS columns_list
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA IN ('${DEV_DATABASE}', '${TEST_DATABASE}')
      AND (TABLE_NAME LIKE 'people_%'
        OR TABLE_NAME IN ('organization_version','employee_version',
                          'employee_current_projection','employment_assignment',
                          'prior_service_record'))
    GROUP BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, NON_UNIQUE
    ORDER BY TABLE_SCHEMA, TABLE_NAME, INDEX_NAME;
  " >"${MYSQL_EVIDENCE_ROOT}/indexes.tsv"

  mysql_query_with_headers "$migrator_defaults" "" "
    SELECT CONSTRAINT_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME,
           REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
    FROM information_schema.KEY_COLUMN_USAGE
    WHERE CONSTRAINT_SCHEMA IN ('${DEV_DATABASE}', '${TEST_DATABASE}')
      AND REFERENCED_TABLE_NAME IS NOT NULL
      AND (TABLE_NAME LIKE 'people_%'
        OR TABLE_NAME IN ('organization_version','employee_version',
                          'employee_current_projection','employment_assignment',
                          'prior_service_record'))
    ORDER BY CONSTRAINT_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;
  " >"${MYSQL_EVIDENCE_ROOT}/foreign-keys.tsv"

  mysql_query_with_headers "$migrator_defaults" "" "
    SELECT constraint_info.CONSTRAINT_SCHEMA, constraint_info.TABLE_NAME,
           constraint_info.CONSTRAINT_NAME, checks.CHECK_CLAUSE
    FROM information_schema.TABLE_CONSTRAINTS constraint_info
    JOIN information_schema.CHECK_CONSTRAINTS checks
      ON checks.CONSTRAINT_SCHEMA = constraint_info.CONSTRAINT_SCHEMA
     AND checks.CONSTRAINT_NAME = constraint_info.CONSTRAINT_NAME
    WHERE constraint_info.CONSTRAINT_SCHEMA IN ('${DEV_DATABASE}', '${TEST_DATABASE}')
      AND constraint_info.CONSTRAINT_TYPE = 'CHECK'
      AND (constraint_info.TABLE_NAME LIKE 'people_%'
        OR constraint_info.TABLE_NAME IN ('organization_version','employee_version',
                          'employee_current_projection','employment_assignment',
                          'prior_service_record'))
    ORDER BY constraint_info.CONSTRAINT_SCHEMA, constraint_info.TABLE_NAME,
             constraint_info.CONSTRAINT_NAME;
  " >"${MYSQL_EVIDENCE_ROOT}/constraints.tsv"

  mysql_query_with_headers "$migrator_defaults" "" "SHOW GRANTS FOR CURRENT_USER;" \
    >"${MYSQL_EVIDENCE_ROOT}/grants-migrator.tsv"
  mysql_query_with_headers "$dev_defaults" "" "SHOW GRANTS FOR CURRENT_USER;" \
    >"${MYSQL_EVIDENCE_ROOT}/grants-dev-app.tsv"
  mysql_query_with_headers "$test_defaults" "" "SHOW GRANTS FOR CURRENT_USER;" \
    >"${MYSQL_EVIDENCE_ROOT}/grants-test-app.tsv"
}

load_login_environment() {
  local resolved_path
  local mode
  local owner_uid
  local line
  local key
  local value
  local line_number=0
  local seen_username="false"
  local seen_password="false"
  [[ "$LOGIN_ENVIRONMENT_FILE" = /* ]] || fail "Login environment path must be absolute."
  [[ -f "$LOGIN_ENVIRONMENT_FILE" && ! -L "$LOGIN_ENVIRONMENT_FILE" ]] \
    || fail "Login environment file is unavailable."
  resolved_path="$(canonical_file_path "$LOGIN_ENVIRONMENT_FILE")"
  case "$resolved_path" in
    "$REPOSITORY_ROOT" | "$REPOSITORY_ROOT"/*)
      fail "Login environment file must be outside the repository."
      ;;
  esac
  mode="$(portable_mode "$resolved_path")"
  [[ "$mode" == "600" ]] \
    || fail "Login environment file mode must be 0600."
  owner_uid="$(portable_owner_uid "$resolved_path")"
  [[ "$owner_uid" == "$(id -u)" ]] \
    || fail "Login environment file must be owned by the current user."
  [[ "$(wc -c <"$resolved_path")" -le 65536 ]] \
    || fail "Login environment file must be smaller than 64 KiB."

  unset SHENZHOUHR_LOGIN_USERNAME SHENZHOUHR_LOGIN_PASSWORD
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" == *=* ]] \
      || fail "Invalid login environment entry at line ${line_number}."
    key="${line%%=*}"
    value="${line#*=}"
    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] \
      || fail "Invalid login environment key at line ${line_number}."
    case "$key" in
      SHENZHOUHR_LOGIN_USERNAME)
        [[ "$seen_username" == "false" ]] \
          || fail "Duplicate login environment key at line ${line_number}."
        seen_username="true"
        printf -v "$key" '%s' "$value"
        ;;
      SHENZHOUHR_LOGIN_PASSWORD)
        [[ "$seen_password" == "false" ]] \
          || fail "Duplicate login environment key at line ${line_number}."
        seen_password="true"
        printf -v "$key" '%s' "$value"
        ;;
      *)
        fail "Unsupported login environment key at line ${line_number}."
        ;;
    esac
  done <"$resolved_path"
  [[ -n "${SHENZHOUHR_LOGIN_USERNAME:-}" ]] || fail "SHENZHOUHR_LOGIN_USERNAME is missing."
  [[ -n "${SHENZHOUHR_LOGIN_PASSWORD:-}" ]] || fail "SHENZHOUHR_LOGIN_PASSWORD is missing."
}

run_runtime_verification() {
  local cookie_jar
  local login_body
  local response_body
  local response_headers
  local runtime_trace
  local status
  local template_type
  local template_version
  require_command curl
  require_command node
  require_command jq
  load_private_environment_file "$ENVIRONMENT_FILE"
  require_secret_variable SHENZHOUHR_DEV_DB_PASSWORD
  load_login_environment
  mkdir -p "$RUNTIME_ROOT"

  cookie_jar="$(make_private_temporary_file)"
  login_body="$(make_private_temporary_file)"
  response_body="$(make_private_temporary_file)"
  response_headers="$(make_private_temporary_file)"
  runtime_trace="${RUNTIME_ROOT}/runtime-api.tsv"
  printf 'probe\tmethod\tpath\tstatus\tcorrelation_id\n' >"$runtime_trace"

  printf '%s\0%s' "$SHENZHOUHR_LOGIN_USERNAME" "$SHENZHOUHR_LOGIN_PASSWORD" \
    | node -e "let b='';process.stdin.on('data',d=>b+=d);process.stdin.on('end',()=>{const p=b.split('\\0');process.stdout.write(JSON.stringify({username:p[0],password:p[1]}));});" \
    >"$login_body"

  status="$(curl --silent --show-error --output "$response_body" \
    --connect-timeout 5 --max-time 20 \
    --dump-header "$response_headers" --write-out '%{http_code}' \
    http://127.0.0.1:8080/actuator/health)"
  record_runtime_probe "$runtime_trace" "health" "GET" "/actuator/health" "$status" "$response_headers" "200"

  status="$(curl --silent --show-error --output "$response_body" \
    --connect-timeout 5 --max-time 20 \
    --dump-header "$response_headers" --write-out '%{http_code}' \
    http://127.0.0.1:8080/api/v1/me/capabilities)"
  record_runtime_probe "$runtime_trace" "unauthenticated" "GET" "/api/v1/me/capabilities" "$status" "$response_headers" "401"

  status="$(curl --silent --show-error --output "$response_body" \
    --connect-timeout 5 --max-time 20 \
    --dump-header "$response_headers" --cookie-jar "$cookie_jar" \
    --header 'Content-Type: application/json' --request POST \
    --data-binary "@${login_body}" --write-out '%{http_code}' \
    http://127.0.0.1:8080/api/v1/auth/login)"
  record_runtime_probe "$runtime_trace" "login" "POST" "/api/v1/auth/login" "$status" "$response_headers" "200"

  runtime_get_probe "$runtime_trace" "$cookie_jar" "$response_body" "$response_headers" \
    "session" "/api/v1/auth/session" "200"
  runtime_get_probe "$runtime_trace" "$cookie_jar" "$response_body" "$response_headers" \
    "capabilities" "/api/v1/me/capabilities" "200"
  runtime_get_probe "$runtime_trace" "$cookie_jar" "$response_body" "$response_headers" \
    "templates" "/api/v1/people-imports/templates?page=0&size=20" "200"

  template_type="$(jq -r '.items[0].templateType // empty' "$response_body")"
  template_version="$(jq -r '.items[0].templateVersion // empty' "$response_body")"
  [[ -n "$template_type" && -n "$template_version" ]] \
    || fail "No versioned WAVE-2 import template was returned."
  runtime_get_probe "$runtime_trace" "$cookie_jar" "$response_body" "$response_headers" \
    "template-download" "/api/v1/people-imports/templates/${template_type}/versions/${template_version}" "200"
  [[ -s "$response_body" ]] || fail "Template download returned an empty file."

  runtime_get_probe "$runtime_trace" "$cookie_jar" "$response_body" "$response_headers" \
    "batches" "/api/v1/people-imports?page=0&size=20&sort=updatedAt" "200"
  runtime_get_probe "$runtime_trace" "$cookie_jar" "$response_body" "$response_headers" \
    "organization" "/api/v1/organization-units?page=0&size=20" "200"
  runtime_get_probe "$runtime_trace" "$cookie_jar" "$response_body" "$response_headers" \
    "employees" "/api/v1/employees?page=0&size=20" "200"
  if ! node "${REPOSITORY_ROOT}/scripts/qa/verify-wave2-runtime-api.mjs" \
      --runtime-env "$ENVIRONMENT_FILE" \
      --login-env "$LOGIN_ENVIRONMENT_FILE" \
      >"${RUNTIME_ROOT}/runtime-business-api.log" 2>&1; then
    fail "WAVE-2 business API verification failed; see the bounded runtime log."
  fi
  log "WAVE2_RUNTIME_API=PASS trace=${runtime_trace}"
  log "WAVE2_RUNTIME_BUSINESS_API=PASS trace=${RUNTIME_ROOT}/runtime-business-api.tsv"
}

runtime_get_probe() {
  local trace="$1"
  local cookie_jar="$2"
  local response_body="$3"
  local response_headers="$4"
  local label="$5"
  local path="$6"
  local expected="$7"
  local status
  status="$(curl --silent --show-error --output "$response_body" \
    --connect-timeout 5 --max-time 20 \
    --dump-header "$response_headers" --cookie "$cookie_jar" \
    --write-out '%{http_code}' "http://127.0.0.1:8080${path}")"
  record_runtime_probe "$trace" "$label" "GET" "$path" "$status" "$response_headers" "$expected"
}

record_runtime_probe() {
  local trace="$1"
  local label="$2"
  local method="$3"
  local path="$4"
  local actual="$5"
  local headers="$6"
  local expected="$7"
  local correlation_id
  correlation_id="$(awk 'BEGIN{IGNORECASE=1} /^X-Correlation-ID:/ {gsub("\\r", "", $2); print $2}' "$headers" | tail -n 1)"
  [[ "$actual" == "$expected" ]] \
    || fail "Runtime probe ${label} returned ${actual}, expected ${expected}."
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$label" "$method" "$path" "$actual" "${correlation_id:-<none>}" >>"$trace"
}

verify_delivery_evidence() {
  require_command node
  node - "$RUNTIME_ROOT" <<'NODE'
const fs = require('fs');
const path = require('path');
const expectedRoot = '/Users/huzhijin/Downloads/shenzhouHR/docs/verification/wave2/runtime';
const root = fs.realpathSync(process.argv[2]);
if (root !== expectedRoot) throw new Error('runtime evidence root is outside the approved project scope');

function regularFile(relativePath, expectedDirectory = root) {
  if (typeof relativePath !== 'string'
      || relativePath.length === 0
      || path.isAbsolute(relativePath)
      || relativePath.includes('\0')) {
    throw new Error(`invalid evidence path ${String(relativePath)}`);
  }
  const normalized = path.normalize(relativePath);
  if (normalized === '..' || normalized.startsWith(`..${path.sep}`)) {
    throw new Error(`evidence path escapes the runtime root: ${relativePath}`);
  }
  const candidate = path.resolve(root, normalized);
  const metadata = fs.lstatSync(candidate);
  if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size === 0) {
    throw new Error(`evidence must be a non-empty regular file: ${relativePath}`);
  }
  const resolved = fs.realpathSync(candidate);
  const allowedDirectory = fs.realpathSync(expectedDirectory);
  if (resolved !== allowedDirectory && !resolved.startsWith(`${allowedDirectory}${path.sep}`)) {
    throw new Error(`evidence resolves outside its approved directory: ${relativePath}`);
  }
  return resolved;
}

function regularDirectory(relativePath) {
  if (typeof relativePath !== 'string' || path.isAbsolute(relativePath)) {
    throw new Error(`invalid evidence directory ${String(relativePath)}`);
  }
  const candidate = path.resolve(root, path.normalize(relativePath));
  const metadata = fs.lstatSync(candidate);
  if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
    throw new Error(`evidence directory must be a real directory: ${relativePath}`);
  }
  const resolved = fs.realpathSync(candidate);
  if (resolved !== root && !resolved.startsWith(`${root}${path.sep}`)) {
    throw new Error(`evidence directory resolves outside the runtime root: ${relativePath}`);
  }
  return resolved;
}

function jsonEvidence(relativePath, expectedDirectory) {
  return JSON.parse(fs.readFileSync(regularFile(relativePath, expectedDirectory), 'utf8'));
}

function countBusinessApiRequests(network) {
  return network.data.requests.filter((request) => {
    if (!['Fetch', 'XHR'].includes(request.resourceType)) return false;
    const url = new URL(request.url);
    return url.pathname.startsWith('/api/');
  }).length;
}

function assertSanitizedNetwork(network, label) {
  if (!network?.success || !Array.isArray(network?.data?.requests)) {
    throw new Error(`${label} network evidence is malformed`);
  }
  const sensitiveHeaders = new Set([
    'authorization',
    'cookie',
    'set-cookie',
    'x-csrf-token',
    'proxy-authorization',
  ]);
  for (const request of network.data.requests) {
    const url = new URL(request.url);
    if (url.username || url.password) {
      throw new Error(`${label} network evidence contains URL credentials`);
    }
    for (const headers of [request.headers ?? {}, request.responseHeaders ?? {}]) {
      for (const header of Object.keys(headers)) {
        if (sensitiveHeaders.has(header.toLowerCase())) {
          throw new Error(`${label} network evidence contains a session-bearing header`);
        }
      }
    }
  }
}

function consoleErrorCount(evidence) {
  if (!evidence?.success || !Array.isArray(evidence?.data?.messages)) {
    throw new Error('console evidence is malformed');
  }
  return evidence.data.messages.filter((message) => message.type === 'error').length;
}

function pageErrorCount(evidence) {
  if (!evidence?.success || !Array.isArray(evidence?.data?.errors)) {
    throw new Error('page-error evidence is malformed');
  }
  return evidence.data.errors.length;
}

function parseMysqlRows(relativePath) {
  const contents = fs.readFileSync(regularFile(relativePath), 'utf8');
  const lines = contents.trim().split(/\r?\n/);
  if (lines.shift() !== 'table_name\trow_count' || lines.length === 0) {
    throw new Error(`invalid MySQL observation ${relativePath}`);
  }
  const rows = new Map();
  for (const line of lines) {
    const [table, countText, extra] = line.split('\t');
    const count = Number(countText);
    if (!table || extra !== undefined || !Number.isSafeInteger(count) || count < 0 || rows.has(table)) {
      throw new Error(`invalid MySQL observation row in ${relativePath}`);
    }
    rows.set(table, count);
  }
  return { contents, rows, total: [...rows.values()].reduce((sum, count) => sum + count, 0) };
}

regularFile('WAVE2-RUNTIME-VERIFICATION.md');
regularFile('state-coverage.json');
const ac = jsonEvidence('ac-people-01-12.json');
const items = Array.isArray(ac) ? ac : ac.acceptanceCriteria;
if (!Array.isArray(items) || items.length !== 12) throw new Error('AC evidence must contain 12 items');
const ids = new Set(items.map((item) => item.id));
for (let i = 1; i <= 12; i += 1) {
  const id = `AC-PEOPLE-${String(i).padStart(2, '0')}`;
  if (!ids.has(id)) throw new Error(`missing ${id}`);
}
if (items.some((item) => item.status !== 'PASS')) throw new Error('every AC must be a real PASS');
if (JSON.stringify(ac).includes('NOT_RUN') || JSON.stringify(ac).includes('NOT_VERIFIED')) {
  throw new Error('unresolved evidence cannot be reported as complete');
}
for (const item of items) {
  if (!Array.isArray(item.evidenceFiles) || item.evidenceFiles.length === 0) {
    throw new Error(`${item.id} has no evidence files`);
  }
  for (const evidenceFile of item.evidenceFiles) regularFile(evidenceFile);
}

const manifest = jsonEvidence('open-design-manifest.json');
const stateCoverage = jsonEvidence('state-coverage.json');
const runtimeContext = jsonEvidence('runtime-context.json');
const runtimeBusiness = jsonEvidence('runtime-api-business.json');
const runIds = [
  ac.runId,
  manifest.runId,
  stateCoverage.runId,
  runtimeContext.runId,
  runtimeBusiness.runId,
];
if (runIds.some((runId) => typeof runId !== 'string' || runId.length < 8)
    || new Set(runIds).size !== 1) {
  throw new Error('runtime evidence files do not share one non-empty runId');
}
if (runtimeContext.employeeId !== runtimeBusiness.localMaintenance?.employeeId
    || runtimeContext.blockingBatchId !== runtimeBusiness.imports?.fiveCategory?.batchId) {
  throw new Error('runtime context does not match the business verification result');
}

const criteria = new Map(items.map((item) => [item.id, item]));
function fact(id, key) {
  return criteria.get(id)?.facts?.[key];
}
function sameJson(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}
const formalBefore = runtimeBusiness.formalCounts?.beforePrecheck;
const formalAfter = runtimeBusiness.formalCounts?.afterPrecheck;
const fiveCategory = runtimeBusiness.imports?.fiveCategory;
const employeePublish = runtimeBusiness.imports?.employeePublish;
const organizationMaintenance = runtimeBusiness.imports?.organizationLocalMaintenance;
const localMaintenance = runtimeBusiness.localMaintenance;
const employment = runtimeBusiness.employment;
const priorService = runtimeBusiness.priorService;
const audit = runtimeBusiness.audit;
if (fact('AC-PEOPLE-01', 'openApiTemplateCount') !== runtimeBusiness.probes?.openapiTemplateCount
    || !sameJson(formalBefore, formalAfter)
    || fact('AC-PEOPLE-01', 'formalTableWriteDeltaDuringPrecheck') !== 0
    || !sameJson(fact('AC-PEOPLE-02', 'categoryCounts'), fiveCategory?.categoryCounts)
    || fact('AC-PEOPLE-02', 'blockingIssueCount') !== fiveCategory?.summary?.blockingIssueCount
    || fact('AC-PEOPLE-02', 'errorReportBytes') !== fiveCategory?.errorReportBytes
    || fact('AC-PEOPLE-02', 'blockedPublishStatus') !== fiveCategory?.blockedPublishStatus
    || fact('AC-PEOPLE-03', 'initialPublishDeduplicated') !== employeePublish?.initialPublishDeduplicated
    || fact('AC-PEOPLE-03', 'repeatedIdempotencyDeduplicated') !== employeePublish?.repeatedIdempotencyDeduplicated
    || fact('AC-PEOPLE-03', 'fileHashDeduplicated') !== employeePublish?.fileHashDeduplicated
    || fact('AC-PEOPLE-03', 'duplicatePointsToOriginalPublication')
      !== (employeePublish?.duplicateOfPublicationId === employeePublish?.publicationId)
    || fact('AC-PEOPLE-04', 'localOrganizationVersions') !== organizationMaintenance?.versionTotal
    || fact('AC-PEOPLE-04', 'importedSourceAuthority')
      !== organizationMaintenance?.importedSourceAuthority
    || fact('AC-PEOPLE-04', 'localSourceAuthority')
      !== organizationMaintenance?.localSourceAuthority
    || fact('AC-PEOPLE-04', 'sourceBatchReleasedAfterLocalEdit')
      !== (organizationMaintenance?.localSourceBatchId === null)
    || fact('AC-PEOPLE-04', 'sourceFileHashPreserved')
      !== organizationMaintenance?.fileSha256Preserved
    || fact('AC-PEOPLE-04', 'publicationSnapshotDigestPreserved')
      !== organizationMaintenance?.publicationSnapshotDigestPreserved
    || fact('AC-PEOPLE-05', 'organizationVersions') !== organizationMaintenance?.versionTotal
    || fact('AC-PEOPLE-05', 'employeeVersions') !== localMaintenance?.employeeVersions
    || fact('AC-PEOPLE-05', 'employeeAuditEvents') !== audit?.total
    || fact('AC-PEOPLE-05', 'organizationVersionAuditEvents')
      !== organizationMaintenance?.versionAuditEventCount
    || fact('AC-PEOPLE-05', 'auditCorrelationIdsPresent') !== audit?.correlationIdsPresent
    || fact('AC-PEOPLE-05', 'organizationSourceFileHashPreserved')
      !== organizationMaintenance?.fileSha256Preserved
    || fact('AC-PEOPLE-05', 'organizationPublicationSnapshotPreserved')
      !== organizationMaintenance?.publicationSnapshotDigestPreserved
    || fact('AC-PEOPLE-05', 'employeeSourceFileHashPreserved')
      !== employeePublish?.fileSha256Preserved
    || fact('AC-PEOPLE-05', 'employeePublicationSnapshotPreserved')
      !== employeePublish?.publicationSnapshotDigestPreserved
    || fact('AC-PEOPLE-06', 'employmentPeriodCount') !== employment?.historyTotalWithoutAsOf
    || fact('AC-PEOPLE-06', 'periodIdsDistinct')
      !== employment?.distinctPeriodIds
    || fact('AC-PEOPLE-06', 'historyTotalWithoutAsOf')
      !== employment?.historyTotalWithoutAsOf
    || fact('AC-PEOPLE-07', 'overlapStatus') !== employment?.overlapStatus
    || fact('AC-PEOPLE-07', 'gapAssignments') !== employment?.gapAssignments
    || fact('AC-PEOPLE-08', 'totalDays') !== priorService?.totalDays
    || fact('AC-PEOPLE-08', 'amountDays') !== priorService?.amountDays
    || fact('AC-PEOPLE-08', 'recordCount') !== priorService?.recordCount
    || fact('AC-PEOPLE-08', 'reasonPreserved') !== priorService?.reasonPreserved
    || fact('AC-PEOPLE-08', 'actorRecorded') !== priorService?.actorRecorded
    || fact('AC-PEOPLE-08', 'occurredAtRecorded') !== priorService?.occurredAtRecorded
    || fact('AC-PEOPLE-08', 'requestIdRecorded') !== priorService?.requestIdRecorded
    || fact('AC-PEOPLE-09', 'sameDisplayNameEmployeeCount')
      !== localMaintenance?.duplicateDisplayNameEmployeeIds?.length
    || fact('AC-PEOPLE-09', 'ambiguousCandidateCount') !== fiveCategory?.ambiguousCandidateCount
    || fact('AC-PEOPLE-09', 'issueCount') !== fiveCategory?.issueCount
    || fact('AC-PEOPLE-10', 'restrictedRole') !== runtimeBusiness.security?.restrictedRoleCode
    || fact('AC-PEOPLE-10', 'restrictedCapabilityStatus') !== runtimeBusiness.security?.capabilityStatus
    || fact('AC-PEOPLE-11', 'draftStatus') !== runtimeBusiness.imports?.draftVoid?.status
    || fact('AC-PEOPLE-11', 'publishedDeleteStatus') !== employeePublish?.physicalDeleteStatus
    || fact('AC-PEOPLE-11', 'organizationRollbackRestoredVersionCount')
      !== runtimeBusiness.imports?.organizationRollback?.restoredVersionCount
    || fact('AC-PEOPLE-11', 'laterVersionRollbackStatus')
      !== employeePublish?.rollbackConflictStatus
    || fact('AC-PEOPLE-11', 'laterVersionRollbackCode') !== employeePublish?.rollbackConflictCode
    || fact('AC-PEOPLE-12', 'firstEndExclusive') !== employment?.firstEndExclusive
    || fact('AC-PEOPLE-12', 'terminationDayAssignments') !== employment?.terminationDayAssignments
    || fact('AC-PEOPLE-12', 'dayAfterTerminationAssignments') !== employment?.dayAfterTerminationAssignments) {
  throw new Error('AC facts do not match the real runtime business result');
}

if (!Array.isArray(manifest.screenshots) || manifest.screenshots.length < 6) {
  throw new Error('manifest must contain real screenshots for all six viewports');
}
const screenshotsRoot = regularDirectory('screenshots');
const screenshotPaths = new Set();
const screenshotViewports = new Set();
for (const item of manifest.screenshots) {
  if (screenshotPaths.has(item.path)) throw new Error(`duplicate screenshot path ${item.path}`);
  screenshotPaths.add(item.path);
  const file = regularFile(item.path, screenshotsRoot);
  const png = fs.readFileSync(file);
  if (png.length < 24 || png.subarray(1, 4).toString('ascii') !== 'PNG') {
    throw new Error(`screenshot is not a PNG: ${item.path}`);
  }
  const actualViewport = `${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`;
  if (item.viewport !== actualViewport) {
    throw new Error(`screenshot viewport mismatch for ${item.path}`);
  }
  screenshotViewports.add(actualViewport);
}
const requiredViewports = new Set([
  '390x844',
  '768x1024',
  '1024x768',
  '1366x768',
  '1440x900',
  '1920x1080',
]);
for (const viewport of requiredViewports) {
  if (!screenshotViewports.has(viewport)) throw new Error(`missing screenshot viewport ${viewport}`);
}

const evidenceFiles = manifest.evidenceFiles ?? {};
const matrix = jsonEvidence(evidenceFiles.responsiveMatrix);
if (!Array.isArray(matrix) || matrix.length !== 6) {
  throw new Error('responsive matrix must contain exactly six route/viewport checks');
}
const matrixViewports = new Set();
const expectedMatrixRoutes = new Map([
  ['390x844', /^\/people\/import$/],
  ['768x1024', /^\/people\/organization$/],
  ['1024x768', /^\/people\/employees$/],
  ['1366x768', /^\/people\/employees\/[0-9a-f-]{36}$/],
  ['1440x900', /^\/people\/import$/],
  ['1920x1080', /^\/people\/employees\/[0-9a-f-]{36}$/],
]);
for (const entry of matrix) {
  const metrics = entry.metrics;
  if (!metrics?.mainPresent
      || metrics.horizontalOverflow !== false
      || !Array.isArray(metrics.touchTargetViolations)
      || metrics.touchTargetViolations.length !== 0
      || metrics.unlabeledControlCount !== 0) {
    throw new Error(`responsive matrix check failed for ${entry.label}`);
  }
  const viewport = `${metrics.viewport?.width}x${metrics.viewport?.height}`;
  if (matrixViewports.has(viewport)) throw new Error(`duplicate responsive viewport ${viewport}`);
  if (!expectedMatrixRoutes.get(viewport)?.test(entry.route)
      || metrics.path !== entry.route
      || !manifest.screenshots.some((screenshot) => (
        screenshot.viewport === viewport
          && screenshot.route === entry.route
          && screenshot.path === entry.screenshot
      ))) {
    throw new Error(`responsive route/screenshot mismatch for ${viewport}`);
  }
  matrixViewports.add(viewport);
  regularFile(entry.screenshot, screenshotsRoot);
}
if (matrixViewports.size !== requiredViewports.size
    || [...requiredViewports].some((viewport) => !matrixViewports.has(viewport))) {
  throw new Error('responsive matrix does not cover the exact six required viewports');
}

function pathAndSearch(url) {
  const parsed = new URL(url, 'http://127.0.0.1');
  return `${parsed.pathname}${parsed.search}`;
}
const expectedEmployeeDetailRoute = `/people/employees/${runtimeContext.employeeId}`;
for (const viewport of ['1366x768', '1920x1080']) {
  const entry = matrix.find((candidate) => (
    `${candidate.metrics?.viewport?.width}x${candidate.metrics?.viewport?.height}` === viewport
  ));
  if (entry?.route !== expectedEmployeeDetailRoute
      || entry.metrics?.path !== expectedEmployeeDetailRoute) {
    throw new Error(`employee-detail browser evidence is not bound to runtimeContext.employeeId for ${viewport}`);
  }
}

const expectedValidationRoute = `/people/import?batch=${runtimeContext.blockingBatchId}`;
const validationScreenshot = manifest.screenshots.find((item) => (
  item.path === 'screenshots/07-validation-failed-1440x900.png'
));
const validationState = jsonEvidence('browser-validation-failed-state.json');
const validationAxe = jsonEvidence('a11y-validation-failed-1440x900.json');
if (validationScreenshot?.route !== expectedValidationRoute
    || pathAndSearch(validationState?.data?.origin) !== expectedValidationRoute
    || pathAndSearch(validationAxe?.data?.url) !== expectedValidationRoute
    || validationState?.data?.result?.status !== runtimeContext.blockingBatchStatus) {
  throw new Error('validation-failed browser evidence is not bound to runtimeContext.blockingBatchId');
}

const publishedBatchId = runtimeBusiness.imports?.employeePublish?.batchId;
const expectedPublishedRoute = `/people/import?batch=${publishedBatchId}`;
const publishedScreenshot = manifest.screenshots.find((item) => (
  item.path === 'screenshots/13-published-success-1440x900.png'
));
const publishedState = jsonEvidence('browser-published-state.json');
if (typeof publishedBatchId !== 'string'
    || publishedScreenshot?.route !== expectedPublishedRoute
    || pathAndSearch(publishedState?.data?.origin) !== expectedPublishedRoute
    || publishedState?.data?.result?.path !== expectedPublishedRoute
    || publishedState?.data?.result?.batchStatus !== 'PUBLISHED') {
  throw new Error('published browser evidence is not bound to the runtime employee publish batch');
}

const normalNetwork = jsonEvidence(evidenceFiles.normalNetwork);
const demoNetwork = jsonEvidence(evidenceFiles.demoNetwork);
assertSanitizedNetwork(normalNetwork, 'normal');
assertSanitizedNetwork(demoNetwork, 'demo');
const normalApiRequests = countBusinessApiRequests(normalNetwork);
const demoApiRequests = countBusinessApiRequests(demoNetwork);
const demoBackendRequests = demoNetwork.data.requests.filter((request) => {
  const url = new URL(request.url);
  return url.hostname === '127.0.0.1' && url.port === '8080';
}).length;
if (normalNetwork.data.requests.length !== manifest.normalTotalRequests
    || normalApiRequests !== manifest.normalApiRequests
    || normalApiRequests < 1
    || demoNetwork.data.requests.length !== manifest.demoTotalRequests
    || demoApiRequests !== manifest.demoApiRequests
    || demoApiRequests !== 0
    || demoBackendRequests !== manifest.demoBackend8080Requests
    || demoBackendRequests !== 0) {
  throw new Error('normal/demo request isolation does not match the raw network evidence');
}

const normalConsoleErrors = consoleErrorCount(jsonEvidence(evidenceFiles.normalConsole));
const demoConsoleErrors = consoleErrorCount(jsonEvidence(evidenceFiles.demoConsole));
const normalPageErrors = pageErrorCount(jsonEvidence(evidenceFiles.normalPageErrors));
const demoPageErrors = pageErrorCount(jsonEvidence(evidenceFiles.demoPageErrors));
if (normalConsoleErrors !== 0
    || demoConsoleErrors !== 0
    || normalPageErrors !== 0
    || demoPageErrors !== 0
    || normalConsoleErrors !== manifest.normalConsoleErrors
    || demoConsoleErrors !== manifest.demoConsoleErrors
    || normalPageErrors !== manifest.normalPageErrors
    || demoPageErrors !== manifest.demoPageErrors) {
  throw new Error('console or page-error evidence is not clean');
}

const mysqlBefore = parseMysqlRows(evidenceFiles.demoMysqlBefore);
const mysqlAfter = parseMysqlRows(evidenceFiles.demoMysqlAfter);
const expectedMysqlTables = [
  'audit_event',
  'employee_current_projection',
  'employee_version',
  'employment_assignment',
  'organization_current_projection',
  'organization_version',
  'prior_service_record',
];
if (mysqlBefore.rows.size !== expectedMysqlTables.length
    || expectedMysqlTables.some((table) => !mysqlBefore.rows.has(table))
    || mysqlBefore.contents !== mysqlAfter.contents
    || mysqlBefore.total !== manifest.demoMysqlObservedRowsBefore
    || mysqlAfter.total !== manifest.demoMysqlObservedRowsAfter
    || manifest.demoMysqlWriteDelta !== 0) {
  throw new Error('demo MySQL isolation does not match the raw before/after evidence');
}

const axeFiles = manifest.accessibility?.axeFiles;
if (!Array.isArray(axeFiles) || axeFiles.length < 6 || new Set(axeFiles).size !== axeFiles.length) {
  throw new Error('accessibility evidence must cover at least six audited states');
}
let automatedViolationCount = 0;
for (const axeFile of axeFiles) {
  const axe = jsonEvidence(axeFile);
  if (!axe?.success || !Array.isArray(axe?.data?.violations) || !Array.isArray(axe?.data?.incomplete)) {
    throw new Error(`malformed axe evidence ${axeFile}`);
  }
  automatedViolationCount += axe.data.violations.length;
  const unresolved = axe.data.incomplete.filter((item) => (
    item.id !== 'color-contrast' && ['critical', 'serious'].includes(item.impact)
  ));
  if (unresolved.length > 0) {
    throw new Error(`unresolved serious accessibility result in ${axeFile}`);
  }
}
if (automatedViolationCount !== 0
    || automatedViolationCount !== manifest.accessibility.automatedViolationCount) {
  throw new Error('automated accessibility violations remain');
}
const contrastRatios = Object.values(manifest.accessibility?.manualContrastReview ?? {})
  .map((value) => Number.parseFloat(String(value)));
if (contrastRatios.length < 4 || contrastRatios.some((ratio) => !Number.isFinite(ratio) || ratio < 4.5)) {
  throw new Error('manual contrast evidence is incomplete');
}

const keyboard = jsonEvidence(manifest.accessibility.keyboardEvidence);
if (keyboard.skipFocus?.visible !== true
    || keyboard.skipActivation?.mainFocused !== true
    || keyboard.drawerOpen?.drawerOpen !== true
    || keyboard.drawerClose?.drawerOpen !== false
    || keyboard.drawerClose?.activeTag !== 'BUTTON'
    || !String(keyboard.drawerClose?.activeLabel || keyboard.drawerClose?.activeText || '')
      .includes('导入批次')) {
  throw new Error('keyboard focus evidence is incomplete');
}

for (const [label, evidencePath] of Object.entries({
  selectAndCards: evidenceFiles.touchSelectAndCards,
  mobileMenu: evidenceFiles.touchMobileMenu,
  datePicker: evidenceFiles.touchDatePicker,
  historyDrawer: evidenceFiles.touchHistoryDrawer,
})) {
  const touchEvidence = jsonEvidence(evidencePath);
  if (!touchEvidence?.success
      || touchEvidence?.data?.result?.pass !== true
      || touchEvidence?.data?.result?.horizontalOverflow !== false
      || touchEvidence?.data?.result?.viewport?.width !== 390) {
    throw new Error(`expanded touch-target evidence failed for ${label}`);
  }
}

const stateItems = Array.isArray(stateCoverage)
  ? stateCoverage
  : [...(stateCoverage.states ?? []), ...(stateCoverage.domainScenarioCoverage ?? [])];
const requiredStates = [
  'normal',
  'loading',
  'empty',
  'error',
  '401',
  '403',
  '404',
  '409',
  'stale',
  'processing',
  'partial-success',
  'success',
  'six-step-import',
  'precheck-blocked',
  'idempotency-hit',
  'local-authority',
  'rehire',
  'period-overlap',
  'prior-service',
  'audit',
];
if (!Array.isArray(stateItems)) throw new Error('state coverage evidence is malformed');
const stateAliases = new Map([
  ['409-conflict', '409'],
  ['frozen-blocked', 'precheck-blocked'],
  ['local-authority-organization', 'local-authority'],
]);
const stateNames = new Set(stateItems.map((item) => stateAliases.get(item.state ?? item.id) ?? item.state ?? item.id));
for (const state of requiredStates) {
  if (!stateNames.has(state)) throw new Error(`state coverage is missing ${state}`);
}
for (const item of stateItems) {
  const status = item.status ?? 'PASS';
  const layers = item.layers ?? [item.layer];
  const evidence = item.evidence ?? item.evidenceFiles;
  if (!String(status).startsWith('PASS')
      || !Array.isArray(layers)
      || layers.length === 0
      || layers.some((layer) => !['API_REAL', 'BROWSER_REAL', 'BROWSER_FAULT_INJECTION'].includes(layer))
      || !Array.isArray(evidence)
      || evidence.length === 0) {
    throw new Error(`state coverage is incomplete for ${item.state ?? item.id}`);
  }
  for (const evidenceFile of evidence) regularFile(evidenceFile);
}
NODE
  log "WAVE2_DELIVERY_EVIDENCE=PASS"
}

dispatch() {
  case "$COMMAND" in
    static)
      run_static_verification
      ;;
    mysql)
      run_mysql_verification
      ;;
    runtime)
      run_runtime_verification
      ;;
    evidence)
      verify_delivery_evidence
      ;;
    all)
      fail "The all command is intentionally unavailable. Run mysql and runtime with --execute, capture the runId-bound browser/delivery evidence, then run static and evidence."
      ;;
    *)
      fail "Unknown command."
      ;;
  esac
}

parse_arguments "$@"
require_execute
dispatch
