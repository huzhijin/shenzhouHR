#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[shenzhouhr-wave3-mysql] ERROR: shell tracing must be disabled.\n' >&2
  exit 1
fi

umask 077
export LC_ALL=C
export LANG=C

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly EXPECTED_REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd -P)"
if [[ "$(pwd -P)" != "$EXPECTED_REPOSITORY_ROOT" ]]; then
  printf 'PROJECT_ROOT_SCOPE_ERROR\n' >&2
  exit 1
fi

if [[ "$(cd "${SCRIPT_DIR}/../.." && pwd -P)" != "$EXPECTED_REPOSITORY_ROOT" ]]; then
  printf 'PROJECT_ROOT_SCOPE_ERROR\n' >&2
  exit 1
fi

# shellcheck source=lib/mysql-safety.sh
source "${SCRIPT_DIR}/lib/mysql-safety.sh"

readonly W3_MYSQL_VERSION="8.4.10"
readonly W3_MYSQL_HOST="127.0.0.1"
readonly W3_MYSQL_PORT="13306"
readonly W3_MYSQL_SOURCE_ARCHIVE="mysql-8.4.10.tar.gz"
readonly W3_MYSQL_SOURCE_URL="https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.10.tar.gz"
readonly W3_MYSQL_SOURCE_SHA256="d57a6730baef14ae118f7f4a6e02845b5b50933758df61fb06e104f27ccc8f96"
readonly W3_MYSQL_ROOT="${HOME}/.local/share/shenzhouhr/mysql-8.4.10-isolated"
readonly W3_MYSQL_CLIENT="${W3_MYSQL_ROOT}/install/bin/mysql"
readonly W3_MYSQL_TARBALL="${W3_MYSQL_ROOT}/downloads/${W3_MYSQL_SOURCE_ARCHIVE}"
readonly W3_MYSQL_SOCKET="${W3_MYSQL_ROOT}/run/mysql8410.sock"
readonly W3_MYSQL_DATADIR="${W3_MYSQL_ROOT}/data"

readonly W3_CHANGE_ROOT="${EXPECTED_REPOSITORY_ROOT}/openspec/changes/wave3-attendance-setup-and-policies"
readonly W3_REGISTRY="${W3_CHANGE_ROOT}/specs/wave3-verification/oracles/w3-retained-registry-v1.json"
readonly W3_REGISTRY_SHA256="aa82a8d941bdb02b0f206b8715691d5f048fb4cfa34e01fc488e4263241803cb"
readonly W3_REGISTRY_TABLE_COUNT="22"
readonly W3_V7_MIGRATION="${MIGRATION_DIR}/V7__attendance_setup_and_base_policies.sql"
readonly W3_V11_MIGRATION="${MIGRATION_DIR}/V11__unify_company_dimension.sql"
readonly W3_TABLE_NAME_PATTERN="^(location|location_revision|location_timeline|shift_template|shift_version|shift_publication_timeline|work_calendar|work_calendar_version|calendar_publication_timeline|work_calendar_day|attendance_group|attendance_group_revision|attendance_group_timeline|attendance_group_assignment|attendance_assignment_timeline|attendance_policy_template|attendance_policy_scope|attendance_policy_scoped_version|attendance_policy_lifecycle_event|attendance_policy_binding_family|attendance_policy_binding_revision|attendance_setup_idempotency)$"
readonly W3_COMPANY_V10_TABLE_PATTERN="^(legal_entity|employee|organization_identity|auth_data_scope|people_import_batch|people_import_publication|location|shift_template|work_calendar|attendance_group|attendance_policy_scope|attendance_source|source_device|device_person_binding|attendance_evidence_subject_lock|raw_attendance_fact|effective_attendance_event|duplicate_review_group|evidence_interval_slice|attendance_recalculation_intent|punch_mapping_profile|punch_import_batch|punch_import_file|attendance_report_projection|attendance_report_daily_fact|attendance_report_oa_fact|attendance_report_exception_fact|attendance_report_time_account_fact|attendance_report_export_job)$"
readonly W3_COMPANY_LATEST_TABLE_PATTERN="^(company|employee|organization_identity|auth_data_scope|people_import_batch|people_import_publication|location|shift_template|work_calendar|attendance_group|attendance_policy_scope|attendance_source|source_device|device_person_binding|attendance_evidence_subject_lock|raw_attendance_fact|effective_attendance_event|duplicate_review_group|evidence_interval_slice|attendance_recalculation_intent|punch_mapping_profile|punch_import_batch|punch_import_file|attendance_report_projection|attendance_report_daily_fact|attendance_report_oa_fact|attendance_report_exception_fact|attendance_report_time_account_fact|attendance_report_export_job)$"

ENVIRONMENT_FILE=""
RUN_ID=""
EXECUTE="false"
COMMAND="plan"
COMMAND_SET="false"
TEST_REBUILD_CONFIRMATION=""
W3_RUNTIME_DB_IDENTITY=""
SEMANTIC_EVIDENCE_DIR=""

cleanup_wave3() {
  cleanup_mysql_safety
}
trap cleanup_wave3 EXIT

usage() {
  cat <<'USAGE'
Usage:
  wave3-local-mysql.sh [OPTIONS] COMMAND

Commands:
  plan              Print the exact non-mutating WAVE-3 verification plan.
  verify-static     Verify the fixed registry SHA/content and current V7 shape.
  upgrade-v6-test   Rebuild only shenzhou_hr_test, run V1-V6 -> target7 ->
                    populated V10 preflight -> V11, then prove repeat no-op.
  verify-contract   Read-only verification of dev/test on isolated MySQL 8.4.10.
  all               Run populated and empty V10 preflight -> V11 paths,
                    then the read-only contract/privilege gate.

Options:
  --env-file ABS_PATH
                    Private mode-0600 environment file outside this repository.
  --run-id ID       Required with --execute; 6-64 lowercase safe characters.
  --execute         Required for commands that connect to MySQL or mutate test.
  --confirm-test-table-rebuild TOKEN
                    Required for upgrade-v6-test/all. TOKEN must be exactly
                    shenzhou_hr_test:<run-id>.
  --semantic-evidence-dir ABS_PATH
                    Optional current-run staging directory below
                    docs/verification/wave3/runs; all captures V6/target7/
                    latest/repeat/empty-latest semantic snapshots there.
  --help            Show this help.

The only destructive path is an explicitly confirmed table-level rebuild of
shenzhou_hr_test. The script never drops a database, never resets dev, never
uses production/deploy targets, and never connects to port 3306.
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
      --run-id)
        [[ $# -ge 2 ]] || fail "--run-id requires a value."
        RUN_ID="$2"
        shift 2
        ;;
      --confirm-test-table-rebuild)
        [[ $# -ge 2 ]] || fail "--confirm-test-table-rebuild requires a value."
        TEST_REBUILD_CONFIRMATION="$2"
        shift 2
        ;;
      --semantic-evidence-dir)
        [[ $# -ge 2 ]] || fail "--semantic-evidence-dir requires a value."
        SEMANTIC_EVIDENCE_DIR="$2"
        shift 2
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
    plan | verify-static) ;;
    upgrade-v6-test | verify-contract | all)
      [[ "$EXECUTE" == "true" ]] || fail "${COMMAND} requires --execute."
      [[ "$RUN_ID" =~ ^[a-z0-9][a-z0-9._-]{5,63}$ ]] \
        || fail "${COMMAND} requires a valid --run-id."
      ;;
    *)
      fail "Unknown command. Run with --help."
      ;;
  esac
}

require_test_rebuild_confirmation() {
  [[ "$TEST_REBUILD_CONFIRMATION" == "${TEST_DATABASE}:${RUN_ID}" ]] \
    || fail "Test-table rebuild requires --confirm-test-table-rebuild ${TEST_DATABASE}:${RUN_ID}."
}

validate_semantic_evidence_directory() {
  local resolved
  local cursor
  [[ -n "$SEMANTIC_EVIDENCE_DIR" ]] || return 0
  [[ "$COMMAND" == "all" ]] \
    || fail "--semantic-evidence-dir is supported only by all."
  [[ "$SEMANTIC_EVIDENCE_DIR" = /* ]] \
    || fail "--semantic-evidence-dir must be absolute."
  [[ -d "$SEMANTIC_EVIDENCE_DIR" && ! -L "$SEMANTIC_EVIDENCE_DIR" ]] \
    || fail "--semantic-evidence-dir must be an existing non-symbolic directory."
  resolved="$(cd "$SEMANTIC_EVIDENCE_DIR" && pwd -P)"
  case "$resolved" in
    "${EXPECTED_REPOSITORY_ROOT}/docs/verification/wave3/runs/"*) ;;
    *) fail "--semantic-evidence-dir escapes the W3 run tree." ;;
  esac
  cursor="$SEMANTIC_EVIDENCE_DIR"
  while [[ "$cursor" != "/" ]]; do
    [[ ! -L "$cursor" ]] \
      || fail "--semantic-evidence-dir traverses a symbolic link."
    cursor="$(dirname "$cursor")"
  done
  SEMANTIC_EVIDENCE_DIR="$resolved"
}

capture_semantic_phase() {
  local phase="$1"
  local defaults_file="$2"
  [[ -n "$SEMANTIC_EVIDENCE_DIR" ]] || return 0
  python3 "${EXPECTED_REPOSITORY_ROOT}/scripts/qa/capture_wave3_mysql_semantics.py" \
    --mysql-client "$SHENZHOUHR_MYSQL_CLIENT_BIN" \
    --defaults-file "$defaults_file" \
    --database "$TEST_DATABASE" \
    --output-dir "$SEMANTIC_EVIDENCE_DIR" \
    --phase "$phase"
}

# The shared W1/W2 safety library intentionally defaults to the legacy local
# 8.0/3306 instance. W3 extends its allowlist and overrides environment
# validation so every operation is pinned to the isolated official 8.4.10 install.
is_allowed_environment_key() {
  case "$1" in
    SHENZHOUHR_MYSQL_HOST | \
    SHENZHOUHR_MYSQL_PORT | \
    SHENZHOUHR_MYSQL_CLIENT_BIN | \
    SHENZHOUHR_FLYWAY_BIN | \
    SHENZHOUHR_TEST_FINAL_STATE | \
    SHENZHOUHR_W3_DB_IDENTITY | \
    SHENZHOUHR_MYSQL_ROOT_PASSWORD | \
    SHENZHOUHR_FLYWAY_PASSWORD | \
    SHENZHOUHR_DEV_DB_PASSWORD | \
    SHENZHOUHR_TEST_DB_PASSWORD)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

report_environment_variable_status() {
  local key
  for key in \
    SHENZHOUHR_MYSQL_HOST \
    SHENZHOUHR_MYSQL_PORT \
    SHENZHOUHR_MYSQL_CLIENT_BIN \
    SHENZHOUHR_FLYWAY_BIN \
    SHENZHOUHR_TEST_FINAL_STATE \
    SHENZHOUHR_W3_DB_IDENTITY \
    SHENZHOUHR_MYSQL_ROOT_PASSWORD \
    SHENZHOUHR_FLYWAY_PASSWORD \
    SHENZHOUHR_DEV_DB_PASSWORD \
    SHENZHOUHR_TEST_DB_PASSWORD; do
    if [[ -n "${!key:-}" ]]; then
      log "${key}_STATUS=available"
    else
      log "${key}_STATUS=missing"
    fi
  done
}

apply_environment_defaults() {
  : "${SHENZHOUHR_MYSQL_HOST:=$W3_MYSQL_HOST}"
  : "${SHENZHOUHR_MYSQL_PORT:=$W3_MYSQL_PORT}"
  : "${SHENZHOUHR_MYSQL_CLIENT_BIN:=$W3_MYSQL_CLIENT}"
  if [[ -z "${SHENZHOUHR_FLYWAY_BIN:-}" ]]; then
    if command -v flyway >/dev/null 2>&1; then
      SHENZHOUHR_FLYWAY_BIN="flyway"
    else
      SHENZHOUHR_FLYWAY_BIN="${MYSQL_DEPLOY_DIR}/flyway-maven.sh"
    fi
  fi
  : "${SHENZHOUHR_TEST_FINAL_STATE:=PRESERVED}"
}

validate_nonsecret_environment() {
  local resolved_client
  [[ "$SHENZHOUHR_MYSQL_HOST" == "$W3_MYSQL_HOST" ]] \
    || fail "W3 MySQL host must be exactly ${W3_MYSQL_HOST}."
  [[ "$SHENZHOUHR_MYSQL_PORT" == "$W3_MYSQL_PORT" ]] \
    || fail "W3 MySQL port must be exactly ${W3_MYSQL_PORT}."
  [[ "$SHENZHOUHR_MYSQL_CLIENT_BIN" == "$W3_MYSQL_CLIENT" ]] \
    || fail "W3 MySQL client must be the isolated absolute 8.4.10 binary."
  [[ -x "$SHENZHOUHR_MYSQL_CLIENT_BIN" && ! -L "$SHENZHOUHR_MYSQL_CLIENT_BIN" ]] \
    || fail "The isolated MySQL client is unavailable or indirect."
  resolved_client="$(canonical_file_path "$SHENZHOUHR_MYSQL_CLIENT_BIN")"
  [[ "$resolved_client" == "$W3_MYSQL_CLIENT" ]] \
    || fail "The isolated MySQL client resolved outside its fixed path."
  [[ "$SHENZHOUHR_FLYWAY_BIN" != *[[:space:]]* ]] \
    || fail "Flyway executable must be a single path without whitespace."
  case "$SHENZHOUHR_TEST_FINAL_STATE" in
    PRESERVED | EMPTY | DROPPED) ;;
    *) fail "SHENZHOUHR_TEST_FINAL_STATE must be PRESERVED, EMPTY, or DROPPED." ;;
  esac
  [[ "${SHENZHOUHR_W3_DB_IDENTITY:-}" =~ ^mysql8410:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:shenzhou_hr_test$ ]] \
    || fail "SHENZHOUHR_W3_DB_IDENTITY must be mysql8410:<lowercase-@@server_uuid>:shenzhou_hr_test."
}

migrator_defaults_file() {
  require_secret_variable SHENZHOUHR_FLYWAY_PASSWORD
  create_mysql_defaults_file "$MIGRATOR_ACCOUNT" "$SHENZHOUHR_FLYWAY_PASSWORD"
}

app_defaults_file() {
  local database="$1"
  case "$database" in
    "$DEV_DATABASE")
      require_secret_variable SHENZHOUHR_DEV_DB_PASSWORD
      create_mysql_defaults_file "$DEV_APP_ACCOUNT" "$SHENZHOUHR_DEV_DB_PASSWORD"
      ;;
    "$TEST_DATABASE")
      require_secret_variable SHENZHOUHR_TEST_DB_PASSWORD
      create_mysql_defaults_file "$TEST_APP_ACCOUNT" "$SHENZHOUHR_TEST_DB_PASSWORD"
      ;;
    *)
      fail "Application defaults requested outside the exact database allowlist."
      ;;
  esac
}

print_plan() {
  cat <<PLAN
WAVE-3 local MySQL verification plan
  server source       : ${W3_MYSQL_SOURCE_URL}
  source SHA256       : ${W3_MYSQL_SOURCE_SHA256}
  server identity     : exact SELECT VERSION() SemVer core ${W3_MYSQL_VERSION}
  database identity   : mysql8410:<exact @@server_uuid>:${TEST_DATABASE}
  connection          : ${W3_MYSQL_HOST}:${W3_MYSQL_PORT}, isolated absolute client
  existing instance   : /usr/local/mysql 8.0.34 on 3306 is out of scope
  target migrations   : reviewed V7 plus company cutover V11
  migration ordering  : V6 -> target7 snapshot -> V10 preflight -> V11
  fixed W3 registry   : ${W3_REGISTRY_TABLE_COUNT} tables, SHA256 ${W3_REGISTRY_SHA256}
  Flyway metadata     : flyway_schema_history is external metadata, excluded
  seed oracle         : three fixed scoped policies and PUBLISHED lifecycle facts
  destructive scope   : only confirmed table rebuild of ${TEST_DATABASE}
  dev database        : all performs forward-only migrate; verify-contract is read-only
  production/deploy   : forbidden
PLAN
  log "WAVE3_RUN_CONTEXT=PLAN run_id=${RUN_ID:-not-supplied}"
}

verify_static_contract() {
  local actual_registry_sha
  local -a v7_migrations
  command -v python3 >/dev/null 2>&1 || fail "python3 is required."
  [[ -f "$W3_REGISTRY" && ! -L "$W3_REGISTRY" ]] \
    || fail "The fixed W3 registry is unavailable or indirect."
  v7_migrations=("${MIGRATION_DIR}"/V7__*.sql)
  [[ "${#v7_migrations[@]}" -eq 1 \
      && "${v7_migrations[0]}" == "$W3_V7_MIGRATION" ]] \
    || fail "Migration directory must contain exactly the reviewed V7__*.sql file."
  [[ -f "$W3_V7_MIGRATION" && ! -L "$W3_V7_MIGRATION" ]] \
    || fail "The exact V7 migration file is unavailable or indirect."
  [[ -f "$W3_V11_MIGRATION" && ! -L "$W3_V11_MIGRATION" ]] \
    || fail "The exact V11 company migration is unavailable or indirect."
  actual_registry_sha="$(hash_file "$W3_REGISTRY")"
  [[ "$actual_registry_sha" == "$W3_REGISTRY_SHA256" ]] \
    || fail "The review-owned W3 registry SHA256 changed."

  if ! python3 - "$W3_REGISTRY" "$W3_V7_MIGRATION" <<'PY'
import json
import re
import sys
from pathlib import Path

registry_path = Path(sys.argv[1])
migration_path = Path(sys.argv[2])
registry = json.loads(registry_path.read_text(encoding="utf-8"))
ddl = migration_path.read_text(encoding="utf-8")

expected_tables = [
    "location", "location_revision", "location_timeline",
    "shift_template", "shift_version", "shift_publication_timeline",
    "work_calendar", "work_calendar_version", "calendar_publication_timeline",
    "work_calendar_day", "attendance_group", "attendance_group_revision",
    "attendance_group_timeline", "attendance_group_assignment",
    "attendance_assignment_timeline", "attendance_policy_template",
    "attendance_policy_scope", "attendance_policy_scoped_version",
    "attendance_policy_lifecycle_event", "attendance_policy_binding_family",
    "attendance_policy_binding_revision", "attendance_setup_idempotency",
]

if registry.get("version") != 1:
    raise SystemExit("registry version must be exactly 1")
tables = registry.get("tables")
if not isinstance(tables, list) or len(tables) != 22:
    raise SystemExit("registry must contain exactly 22 tables")
registry_names = [item.get("table") for item in tables]
if registry_names != expected_tables:
    raise SystemExit("registry table order/content differs from the fixed v1 contract")
if len(set(registry_names)) != 22:
    raise SystemExit("registry contains duplicate table names")

block_pattern = re.compile(
    r"(?ims)^\s*CREATE\s+TABLE\s+`?([a-z][a-z0-9_]*)`?\s*\((.*?)^\)\s*ENGINE\s*="
)
blocks = {}
for match in block_pattern.finditer(ddl):
    name = match.group(1).lower()
    if name in blocks:
        raise SystemExit(f"duplicate CREATE TABLE in V7: {name}")
    blocks[name] = match.group(2)
if set(blocks) != set(expected_tables) or len(blocks) != 22:
    missing = sorted(set(expected_tables) - set(blocks))
    extra = sorted(set(blocks) - set(expected_tables))
    raise SystemExit(f"V7 table set differs from registry; missing={missing} extra={extra}")

keywords = {
    "primary", "unique", "key", "index", "constraint", "check", "foreign",
    "references", "and", "or",
    "char", "varchar", "tinyint", "smallint", "mediumint", "int", "bigint",
    "decimal", "date", "datetime", "timestamp", "json", "text", "longtext",
}
for definition in tables:
    table = definition["table"]
    expected_columns = []
    for framed in definition.get("columns", []):
        column, separator, logical_type = framed.partition(":")
        if not separator or not column or not logical_type:
            raise SystemExit(f"invalid registry column framing: {table}.{framed}")
        expected_columns.append(column)
    if len(expected_columns) != len(set(expected_columns)):
        raise SystemExit(f"duplicate registry column: {table}")
    actual_columns = []
    for line in blocks[table].splitlines():
        candidate = line.strip().rstrip(",")
        match = re.match(r"`?([a-z][a-z0-9_]*)`?(?:\s+|$)", candidate, re.I)
        if match and match.group(1).lower() not in keywords:
            column_name = match.group(1).lower()
            if column_name not in actual_columns:
                actual_columns.append(column_name)
    if actual_columns != expected_columns:
        raise SystemExit(
            f"V7 column order differs from registry: {table}; "
            f"expected={expected_columns} actual={actual_columns}"
        )
    primary = re.search(r"PRIMARY\s+KEY\s*\(([^)]*)\)", blocks[table], re.I)
    if not primary:
        raise SystemExit(f"V7 primary key is missing: {table}")
    actual_pk = [
        token.strip().strip("`").lower() for token in primary.group(1).split(",")
    ]
    if actual_pk != definition.get("pk"):
        raise SystemExit(f"V7 primary key differs from registry: {table}")

lower_ddl = ddl.lower()
for forbidden in (
    "alter table policy_version",
    "min(legal_entity_id)",
    "maximumlateminutes",
    "minimumlateminutes",
    "643f6bff05feddc128e0f0aaf6fe52da47058f7e629a63ca50ce40ce91925310",
):
    if forbidden in lower_ddl:
        raise SystemExit(f"forbidden legacy V7 input remains: {forbidden}")
if re.search(r"(?im)^\s*status\s+", blocks["attendance_policy_scoped_version"]):
    raise SystemExit("attendance_policy_scoped_version must not contain status")

foreign_keys = re.findall(r"(?im)^\s*CONSTRAINT\s+(fk_[a-z0-9_]+)\b", ddl)
checks = re.findall(r"(?im)^\s*CONSTRAINT\s+(ck_[a-z0-9_]+)\b", ddl)
explicit_indexes = (
    re.findall(r"(?im)^\s*PRIMARY\s+KEY\b", ddl)
    + re.findall(r"(?im)^\s*UNIQUE\s+KEY\s+([a-z0-9_]+)\b", ddl)
    + re.findall(r"(?im)^\s*KEY\s+([a-z0-9_]+)\b", ddl)
)
if len(foreign_keys) != 71 or len(set(foreign_keys)) != 71:
    raise SystemExit("V7 foreign-key contract must contain 71 unique named constraints")
if len(checks) != 25 or len(set(checks)) != 25:
    raise SystemExit("V7 CHECK contract must contain 25 unique named constraints")
if len(explicit_indexes) != 84:
    raise SystemExit("V7 explicit index contract must contain 84 indexes")

print(
    "W3_STATIC_REGISTRY_V7_COMPARE=PASS "
    "tables=22 foreign_keys=71 checks=25 explicit_indexes=84 "
    "flyway_metadata=excluded"
)
PY
  then
    fail "The current V7 migration does not match the fixed W3 registry."
  fi
  log "W3_RETAINED_REGISTRY_STATIC=PASS tables=${W3_REGISTRY_TABLE_COUNT} sha256=${actual_registry_sha} flyway_metadata=excluded"
}

verify_official_distribution() {
  local tarball_sha
  [[ -f "$W3_MYSQL_TARBALL" && ! -L "$W3_MYSQL_TARBALL" ]] \
    || fail "The official MySQL 8.4.10 source archive is unavailable."
  tarball_sha="$(hash_file "$W3_MYSQL_TARBALL")"
  [[ "$tarball_sha" == "$W3_MYSQL_SOURCE_SHA256" ]] \
    || fail "The MySQL 8.4.10 source archive SHA256 is invalid."
  log "W3_MYSQL8410_SOURCE=PASS file=${W3_MYSQL_SOURCE_ARCHIVE} sha256=${tarball_sha}"
}

verify_mysql_8410() {
  local defaults_file
  local identity
  local server_version
  local core_version
  local actual_port
  local bind_address
  local socket_path
  local datadir_path
  local server_uuid
  local derived_db_identity
  defaults_file="$(migrator_defaults_file)"
  identity="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|', VERSION(), @@port, @@bind_address, @@socket, @@datadir, @@server_uuid);
  ")"
  IFS='|' read -r server_version actual_port bind_address socket_path datadir_path server_uuid \
    <<< "$identity"
  core_version="${server_version%%-*}"
  core_version="${core_version%%+*}"
  [[ "$core_version" == "$W3_MYSQL_VERSION" ]] \
    || fail "SELECT VERSION() SemVer core must be exactly ${W3_MYSQL_VERSION}."
  [[ "$actual_port" == "$W3_MYSQL_PORT" ]] \
    || fail "Connected server port must be exactly ${W3_MYSQL_PORT}."
  [[ "$bind_address" == "$W3_MYSQL_HOST" ]] \
    || fail "Connected server bind address must be exactly ${W3_MYSQL_HOST}."
  [[ "$socket_path" == "$W3_MYSQL_SOCKET" ]] \
    || fail "Connected server socket is outside the isolated path."
  [[ "${datadir_path%/}" == "$W3_MYSQL_DATADIR" ]] \
    || fail "Connected server datadir is outside the isolated path."
  [[ "$server_uuid" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
    || fail "Connected server returned an invalid @@server_uuid."
  server_uuid="$(printf '%s' "$server_uuid" | tr '[:upper:]' '[:lower:]')"
  derived_db_identity="mysql8410:${server_uuid}:${TEST_DATABASE}"
  [[ "$derived_db_identity" == "$SHENZHOUHR_W3_DB_IDENTITY" ]] \
    || fail "Connected server/database identity differs from the authorized runtime identity."
  W3_RUNTIME_DB_IDENTITY="$derived_db_identity"
  log "W3_MYSQL8410_RUNTIME=PASS version=${core_version} host=${bind_address} port=${actual_port} socket=${socket_path} datadir=${datadir_path} server_uuid=${server_uuid} db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

load_runtime() {
  verify_static_contract
  load_private_environment_file "$ENVIRONMENT_FILE"
  require_mysql_client
  require_flyway_client
  verify_official_distribution
  verify_mysql_8410
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

assert_v1_v6_checksums() {
  local migration
  local expected
  local actual
  while IFS='|' read -r migration expected; do
    actual="$(hash_file "${MIGRATION_DIR}/${migration}")"
    [[ "$actual" == "$expected" ]] \
      || fail "Immutable migration checksum changed: ${migration}."
  done <<'CHECKSUMS'
V1__identity_organization_authorization_audit.sql|5f5cdd3367ef7ab128974b7fcad89a44bcfc5631008bd066806f77d33f85c440
V2__baseline_authorization_catalog.sql|28934279faafa2154faccd470976aa6ab2c4a5058f7d1b08a989c271da5241b4
V3__local_identity_session.sql|75120a31c594f2a974c031ed400d028028f83fb915df1b457902df27a971f9f9
V4__versioned_policy_foundation.sql|22b7f4b4b5ad21beec719c09aade3fc50b41a64d958951a690328ea38d2402a8
V5__people_initial_import_and_versioning.sql|6fd13a0e31a37d27fb7d9f71f8117acf7d5fb0bd38d4d117303b2b8582b6b589
V6__system_admin_people_read_prerequisite.sql|11762c79e34bea2ab6aa790a6b32c6466658eec03fd7a97b23fe540cf985c003
CHECKSUMS
  log "WAVE3_V1_V6_MIGRATION_CHECKSUMS=PASS"
}

verify_migration_history() {
  local defaults_file="$1"
  local database="$2"
  local base_versions
  local failed_count
  local latest_version
  base_versions="$(mysql_scalar "$defaults_file" "$database" "
    SELECT GROUP_CONCAT(version ORDER BY installed_rank SEPARATOR ',')
    FROM flyway_schema_history
    WHERE type = 'SQL' AND success = 1
      AND version IN ('1','2','3','4','5','6','7');
  ")"
  [[ "$base_versions" == "1,2,3,4,5,6,7" ]] \
    || fail "Successful Flyway history must contain the exact ordered V1-V7 base."
  failed_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;
  ")"
  [[ "$failed_count" == "0" ]] || fail "Flyway history contains a failed migration."
  latest_version="$(mysql_scalar "$defaults_file" "$database" "
    SELECT version FROM flyway_schema_history
    WHERE type = 'SQL' AND success = 1
    ORDER BY installed_rank DESC LIMIT 1;
  ")"
  [[ "$latest_version" == "11" ]] \
    || fail "Flyway latest version must be exactly V11."
  log "W3_MIGRATION_HISTORY=PASS base=1,2,3,4,5,6,7 latest=11 company_schema=required db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

company_boundary_orphan_count() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local boundary_table
  local boundary_column
  local dependent_table
  local sql_union=""
  case "$schema_mode" in
    v10)
      boundary_table="legal_entity"
      boundary_column="legal_entity_id"
      ;;
    latest)
      boundary_table="company"
      boundary_column="company_id"
      ;;
    *)
      fail "Unknown company-boundary orphan schema mode."
      ;;
  esac
  while IFS= read -r dependent_table; do
    [[ -n "$dependent_table" ]] || continue
    if [[ -n "$sql_union" ]]; then
      sql_union+=" UNION ALL "
    fi
    sql_union+="
      SELECT COUNT(*) AS orphan_count
      FROM \`${dependent_table}\` dependent
      LEFT JOIN \`${boundary_table}\` boundary
        ON boundary.\`${boundary_column}\` =
           dependent.\`${boundary_column}\`
      WHERE dependent.\`${boundary_column}\` IS NOT NULL
        AND boundary.\`${boundary_column}\` IS NULL"
  done <<'TABLES'
employee
organization_identity
auth_data_scope
people_import_batch
people_import_publication
location
shift_template
work_calendar
attendance_group
attendance_policy_scope
attendance_source
source_device
device_person_binding
attendance_evidence_subject_lock
raw_attendance_fact
effective_attendance_event
duplicate_review_group
evidence_interval_slice
attendance_recalculation_intent
punch_mapping_profile
punch_import_batch
punch_import_file
attendance_report_projection
attendance_report_daily_fact
attendance_report_oa_fact
attendance_report_exception_fact
attendance_report_time_account_fact
attendance_report_export_job
TABLES
  mysql_scalar "$defaults_file" "$database" "
    SELECT COALESCE(SUM(orphan_count), 0)
    FROM (${sql_union}) company_boundary_orphans;
  "
}

capture_company_boundary_snapshot() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local output_file="$4"
  local logical_table
  local v10_table
  local latest_table
  local v10_primary_key
  local latest_primary_key
  local table_name
  local primary_key_columns
  local boundary_column
  local row_count
  local id_rows
  local id_digest
  : >"$output_file"
  while IFS='|' read -r logical_table v10_table latest_table \
      v10_primary_key latest_primary_key; do
    case "$schema_mode" in
      v10)
        table_name="$v10_table"
        primary_key_columns="$v10_primary_key"
        boundary_column="legal_entity_id"
        ;;
      latest)
        table_name="$latest_table"
        primary_key_columns="$latest_primary_key"
        boundary_column="company_id"
        ;;
      *)
        fail "Unknown company-boundary snapshot schema mode."
        ;;
    esac
    row_count="$(mysql_scalar "$defaults_file" "$database" "
      SELECT COUNT(*) FROM \`${table_name}\`;
    ")"
    id_rows="$(make_private_temporary_file)"
    mysql_query "$defaults_file" "$database" "
      SELECT CONCAT_WS(
        CHAR(31),
        ${primary_key_columns},
        COALESCE(\`${boundary_column}\`, '<NULL>'))
      FROM \`${table_name}\`
      ORDER BY ${primary_key_columns};
    " >"$id_rows"
    id_digest="$(hash_file "$id_rows")"
    printf '%s|%s|%s\n' \
      "$logical_table" "$row_count" "$id_digest" >>"$output_file"
  done <<'TABLES'
company|legal_entity|company|legal_entity_id|company_id
employee|employee|employee|employee_id|employee_id
organization_identity|organization_identity|organization_identity|organization_id|organization_id
auth_data_scope|auth_data_scope|auth_data_scope|scope_id|scope_id
people_import_batch|people_import_batch|people_import_batch|batch_id|batch_id
people_import_publication|people_import_publication|people_import_publication|publication_id|publication_id
location|location|location|location_id|location_id
shift_template|shift_template|shift_template|shift_template_id|shift_template_id
work_calendar|work_calendar|work_calendar|work_calendar_id|work_calendar_id
attendance_group|attendance_group|attendance_group|attendance_group_id|attendance_group_id
attendance_policy_scope|attendance_policy_scope|attendance_policy_scope|scope_id|scope_id
attendance_source|attendance_source|attendance_source|attendance_source_id|attendance_source_id
source_device|source_device|source_device|source_device_id|source_device_id
device_person_binding|device_person_binding|device_person_binding|device_person_binding_id|device_person_binding_id
attendance_evidence_subject_lock|attendance_evidence_subject_lock|attendance_evidence_subject_lock|legal_entity_id, employee_id|company_id, employee_id
raw_attendance_fact|raw_attendance_fact|raw_attendance_fact|raw_attendance_fact_id|raw_attendance_fact_id
effective_attendance_event|effective_attendance_event|effective_attendance_event|effective_attendance_event_id|effective_attendance_event_id
duplicate_review_group|duplicate_review_group|duplicate_review_group|duplicate_review_group_id|duplicate_review_group_id
evidence_interval_slice|evidence_interval_slice|evidence_interval_slice|evidence_interval_slice_id|evidence_interval_slice_id
attendance_recalculation_intent|attendance_recalculation_intent|attendance_recalculation_intent|attendance_recalculation_intent_id|attendance_recalculation_intent_id
punch_mapping_profile|punch_mapping_profile|punch_mapping_profile|punch_mapping_profile_id|punch_mapping_profile_id
punch_import_batch|punch_import_batch|punch_import_batch|punch_import_batch_id|punch_import_batch_id
punch_import_file|punch_import_file|punch_import_file|punch_import_file_id|punch_import_file_id
attendance_report_projection|attendance_report_projection|attendance_report_projection|attendance_report_projection_id|attendance_report_projection_id
attendance_report_daily_fact|attendance_report_daily_fact|attendance_report_daily_fact|attendance_report_daily_fact_id|attendance_report_daily_fact_id
attendance_report_oa_fact|attendance_report_oa_fact|attendance_report_oa_fact|attendance_report_oa_fact_id|attendance_report_oa_fact_id
attendance_report_exception_fact|attendance_report_exception_fact|attendance_report_exception_fact|attendance_report_exception_fact_id|attendance_report_exception_fact_id
attendance_report_time_account_fact|attendance_report_time_account_fact|attendance_report_time_account_fact|attendance_report_time_account_fact_id|attendance_report_time_account_fact_id
attendance_report_export_job|attendance_report_export_job|attendance_report_export_job|attendance_report_export_id|attendance_report_export_id
TABLES
  [[ "$(wc -l <"$output_file" | tr -d '[:space:]')" == "29" ]] \
    || fail "Company-boundary snapshot must contain exactly 29 tables."
}

auth_scope_check_contract_matches() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local constraint_shape
  local check_clause
  constraint_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      COUNT(*),
      SUM(CASE WHEN tc.ENFORCED = 'YES' THEN 1 ELSE 0 END)
    )
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
     AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.CONSTRAINT_SCHEMA = '${database}'
      AND tc.TABLE_NAME = 'auth_data_scope'
      AND tc.CONSTRAINT_NAME = 'ck_auth_scope_target'
      AND tc.CONSTRAINT_TYPE = 'CHECK';
  ")"
  [[ "$constraint_shape" == "1|1" ]] || return 1
  check_clause="$(mysql_scalar "$defaults_file" "" "
    SELECT cc.CHECK_CLAUSE
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
     AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.CONSTRAINT_SCHEMA = '${database}'
      AND tc.TABLE_NAME = 'auth_data_scope'
      AND tc.CONSTRAINT_NAME = 'ck_auth_scope_target'
      AND tc.CONSTRAINT_TYPE = 'CHECK'
      AND tc.ENFORCED = 'YES';
  ")"
  python3 - "$schema_mode" "$check_clause" <<'PY'
import re
import sys

mode, clause = sys.argv[1:]
expected_by_mode = {
    "v10": """
        (scope_type = 'LEGAL_ENTITY'
          AND legal_entity_id IS NOT NULL
          AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION'
          AND legal_entity_id IS NULL
          AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF'
          AND legal_entity_id IS NULL
          AND organization_id IS NULL)
    """,
    "latest": """
        (scope_type = 'COMPANY'
          AND company_id IS NOT NULL
          AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION'
          AND company_id IS NULL
          AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF'
          AND company_id IS NULL
          AND organization_id IS NULL)
    """,
}
if mode not in expected_by_mode:
    raise SystemExit(1)


def tokens(value):
    value = value.replace("\\'", "'")
    value = value.replace("`", "")
    value = re.sub(
        r"(?<![A-Za-z0-9_'])_[A-Za-z0-9]+(?=')",
        "",
        value,
    )
    pattern = re.compile(
        r"\s*(?:"
        r"(?P<left>\()|(?P<right>\))|(?P<equal>=)|"
        r"(?P<string>'(?:''|[^'])*')|"
        r"(?P<word>[A-Za-z_][A-Za-z0-9_]*)"
        r")"
    )
    result = []
    offset = 0
    while offset < len(value):
        match = pattern.match(value, offset)
        if match is None:
            if value[offset:].strip() == "":
                break
            raise ValueError("unsupported CHECK token")
        result.append(match.group(match.lastgroup))
        offset = match.end()
    return result


class Parser:
    def __init__(self, values):
        self.values = values
        self.offset = 0

    def peek(self):
        if self.offset >= len(self.values):
            return None
        return self.values[self.offset]

    def take(self, expected=None):
        value = self.peek()
        if value is None or (
            expected is not None and value.upper() != expected
        ):
            raise ValueError("unexpected CHECK structure")
        self.offset += 1
        return value

    @staticmethod
    def combine(operator, nodes):
        flattened = []
        for node in nodes:
            if node[0] == operator:
                flattened.extend(node[1])
            else:
                flattened.append(node)
        if len(flattened) == 1:
            return flattened[0]
        return operator, tuple(sorted(flattened, key=repr))

    def parse(self):
        result = self.parse_or()
        if self.peek() is not None:
            raise ValueError("trailing CHECK tokens")
        return result

    def parse_or(self):
        nodes = [self.parse_and()]
        while self.peek() is not None and self.peek().upper() == "OR":
            self.take("OR")
            nodes.append(self.parse_and())
        return self.combine("or", nodes)

    def parse_and(self):
        nodes = [self.parse_factor()]
        while self.peek() is not None and self.peek().upper() == "AND":
            self.take("AND")
            nodes.append(self.parse_factor())
        return self.combine("and", nodes)

    def parse_factor(self):
        if self.peek() == "(":
            self.take("(")
            result = self.parse_or()
            self.take(")")
            return result
        return self.parse_atom()

    def parse_atom(self):
        identifier = self.take().lower()
        if not re.fullmatch(r"[a-z_][a-z0-9_]*", identifier):
            raise ValueError("invalid CHECK identifier")
        operator = self.take()
        if operator == "=":
            literal = self.take()
            if not (literal.startswith("'") and literal.endswith("'")):
                raise ValueError("CHECK equality requires a string literal")
            return "eq", identifier, literal[1:-1]
        if operator.upper() != "IS":
            raise ValueError("unsupported CHECK operator")
        negated = False
        if self.peek() is not None and self.peek().upper() == "NOT":
            self.take("NOT")
            negated = True
        self.take("NULL")
        return ("not_null" if negated else "null"), identifier


try:
    actual = Parser(tokens(clause)).parse()
    expected = Parser(tokens(expected_by_mode[mode])).parse()
except ValueError:
    raise SystemExit(1)
raise SystemExit(0 if actual == expected else 1)
PY
}

company_boundary_auxiliary_check_contract_matches() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local table_pattern
  local check_shape
  case "$schema_mode" in
    v10)
      table_pattern="$W3_COMPANY_V10_TABLE_PATTERN"
      ;;
    latest)
      table_pattern="$W3_COMPANY_LATEST_TABLE_PATTERN"
      ;;
    *)
      return 1
      ;;
  esac
  check_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      COUNT(*),
      SUM(CASE WHEN ENFORCED = 'YES' THEN 1 ELSE 0 END),
      COUNT(DISTINCT CASE
        WHEN CONCAT(TABLE_NAME, '.', CONSTRAINT_NAME) IN (
          'auth_data_scope.ck_auth_scope_period',
          'people_import_batch.ck_people_import_status',
          'people_import_batch.ck_people_import_template_type',
          'attendance_source.ck_att_source_status',
          'attendance_source.ck_att_source_type',
          'device_person_binding.ck_device_person_period',
          'raw_attendance_fact.ck_raw_fact_identity',
          'raw_attendance_fact.ck_raw_fact_kind',
          'raw_attendance_fact.ck_raw_fact_temporal',
          'effective_attendance_event.ck_effective_event_direction',
          'effective_attendance_event.ck_effective_event_kind',
          'effective_attendance_event.ck_effective_event_temporal',
          'duplicate_review_group.ck_duplicate_group_direction',
          'duplicate_review_group.ck_duplicate_group_status',
          'duplicate_review_group.ck_duplicate_group_window',
          'evidence_interval_slice.ck_evidence_slice_period',
          'evidence_interval_slice.ck_evidence_slice_status',
          'evidence_interval_slice.ck_evidence_slice_winner',
          'punch_import_file.ck_punch_file_scan',
          'punch_import_file.ck_punch_file_size',
          'attendance_report_projection.ck_att_report_projection_period',
          'attendance_report_projection.ck_att_report_projection_publish',
          'attendance_report_projection.ck_att_report_projection_state',
          'attendance_report_projection.ck_att_report_projection_status',
          'attendance_report_daily_fact.ck_att_report_daily_actual_work',
          'attendance_report_daily_fact.ck_att_report_daily_day_type',
          'attendance_report_daily_fact.ck_att_report_daily_late',
          'attendance_report_daily_fact.ck_att_report_daily_punch_order',
          'attendance_report_oa_fact.ck_att_report_oa_status',
          'attendance_report_oa_fact.ck_att_report_oa_temporal_shape',
          'attendance_report_oa_fact.ck_att_report_oa_type',
          'attendance_report_exception_fact.ck_att_report_exception_severity',
          'attendance_report_exception_fact.ck_att_report_exception_state',
          'attendance_report_time_account_fact.ck_att_report_account_type',
          'attendance_report_export_job.ck_att_report_export_delivery',
          'attendance_report_export_job.ck_att_report_export_digests',
          'attendance_report_export_job.ck_att_report_export_extension',
          'attendance_report_export_job.ck_att_report_export_fields',
          'attendance_report_export_job.ck_att_report_export_period',
          'attendance_report_export_job.ck_att_report_export_purpose',
          'attendance_report_export_job.ck_att_report_export_state',
          'attendance_report_export_job.ck_att_report_export_status',
          'attendance_report_export_job.ck_att_report_export_time',
          'attendance_report_export_job.ck_att_report_export_type'
        )
        THEN CONCAT(TABLE_NAME, '.', CONSTRAINT_NAME)
      END)
    )
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND CONSTRAINT_TYPE = 'CHECK'
      AND TABLE_NAME REGEXP '${table_pattern}'
      AND NOT (
        TABLE_NAME = 'auth_data_scope'
        AND CONSTRAINT_NAME = 'ck_auth_scope_target'
      );
  ")"
  [[ "$check_shape" == "44|44|44" ]]
}

company_boundary_index_contract_matches() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local index_shape
  index_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      COUNT(DISTINCT CASE
        WHEN (TABLE_NAME = 'legal_entity'
                AND INDEX_NAME = 'uq_legal_entity_code')
          OR (TABLE_NAME = 'employee'
                AND INDEX_NAME = 'ix_employee_legal_entity_status')
          OR (TABLE_NAME = 'organization_identity'
                AND INDEX_NAME = 'ix_organization_identity_legal_entity')
          OR (TABLE_NAME = 'attendance_policy_scope'
                AND INDEX_NAME = 'ix_attendance_policy_scope_legal_entity')
          OR (TABLE_NAME = 'attendance_source'
                AND INDEX_NAME = 'uq_att_source_id_entity')
        THEN CONCAT(TABLE_NAME, CHAR(0), INDEX_NAME)
      END),
      COUNT(DISTINCT CASE
        WHEN INDEX_NAME IN (
          'uq_legal_entity_code',
          'ix_employee_legal_entity_status',
          'ix_organization_identity_legal_entity',
          'ix_attendance_policy_scope_legal_entity',
          'uq_att_source_id_entity'
        )
        THEN CONCAT(TABLE_NAME, CHAR(0), INDEX_NAME)
      END),
      COUNT(DISTINCT CASE
        WHEN (TABLE_NAME = 'company'
                AND INDEX_NAME = 'uq_company_code')
          OR (TABLE_NAME = 'employee'
                AND INDEX_NAME = 'ix_employee_company_status')
          OR (TABLE_NAME = 'organization_identity'
                AND INDEX_NAME = 'ix_organization_identity_company')
          OR (TABLE_NAME = 'attendance_policy_scope'
                AND INDEX_NAME = 'ix_attendance_policy_scope_company')
          OR (TABLE_NAME = 'attendance_source'
                AND INDEX_NAME = 'uq_att_source_id_company')
        THEN CONCAT(TABLE_NAME, CHAR(0), INDEX_NAME)
      END),
      COUNT(DISTINCT CASE
        WHEN INDEX_NAME IN (
          'uq_company_code',
          'ix_employee_company_status',
          'ix_organization_identity_company',
          'ix_attendance_policy_scope_company',
          'uq_att_source_id_company'
        )
        THEN CONCAT(TABLE_NAME, CHAR(0), INDEX_NAME)
      END)
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = '${database}';
  ")"
  case "$schema_mode" in
    v10)
      [[ "$index_shape" == "5|5|0|0" ]]
      ;;
    latest)
      [[ "$index_shape" == "0|0|5|5" ]]
      ;;
    *)
      return 1
      ;;
  esac
}

company_boundary_relationship_contract_matches() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local boundary_table
  local boundary_column
  local table_pattern
  local relationship_shape
  case "$schema_mode" in
    v10)
      boundary_table="legal_entity"
      boundary_column="legal_entity_id"
      table_pattern="$W3_COMPANY_V10_TABLE_PATTERN"
      ;;
    latest)
      boundary_table="company"
      boundary_column="company_id"
      table_pattern="$W3_COMPANY_LATEST_TABLE_PATTERN"
      ;;
    *)
      return 1
      ;;
  esac
  relationship_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      COUNT(DISTINCT TABLE_NAME),
      COUNT(DISTINCT CONCAT(
        TABLE_NAME, CHAR(0), REFERENCED_TABLE_NAME)),
      COUNT(DISTINCT CASE
        WHEN (
          TABLE_NAME IN (
            'employee',
            'organization_identity',
            'auth_data_scope',
            'people_import_batch',
            'people_import_publication',
            'location',
            'shift_template',
            'work_calendar',
            'attendance_group',
            'attendance_policy_scope',
            'attendance_source',
            'attendance_evidence_subject_lock',
            'effective_attendance_event',
            'duplicate_review_group',
            'evidence_interval_slice',
            'attendance_recalculation_intent',
            'punch_mapping_profile',
            'attendance_report_projection',
            'attendance_report_export_job'
          )
          AND REFERENCED_TABLE_NAME = '${boundary_table}'
        )
        OR (
          TABLE_NAME IN (
            'source_device',
            'raw_attendance_fact',
            'punch_import_batch',
            'punch_import_file'
          )
          AND REFERENCED_TABLE_NAME = 'attendance_source'
        )
        OR (
          TABLE_NAME = 'device_person_binding'
          AND REFERENCED_TABLE_NAME IN (
            'source_device',
            'attendance_source'
          )
        )
        OR (
          TABLE_NAME IN (
            'attendance_report_daily_fact',
            'attendance_report_oa_fact',
            'attendance_report_exception_fact',
            'attendance_report_time_account_fact'
          )
          AND REFERENCED_TABLE_NAME = 'attendance_report_projection'
        )
        THEN CONCAT(TABLE_NAME, CHAR(0), REFERENCED_TABLE_NAME)
      END),
      COUNT(DISTINCT CONCAT(
        TABLE_NAME, CHAR(0), CONSTRAINT_NAME,
        CHAR(0), REFERENCED_TABLE_NAME))
    )
    FROM information_schema.KEY_COLUMN_USAGE
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${table_pattern}'
      AND TABLE_NAME <> '${boundary_table}'
      AND COLUMN_NAME = '${boundary_column}'
      AND REFERENCED_COLUMN_NAME = '${boundary_column}';
  ")"
  [[ "$relationship_shape" == "28|29|29|29" ]]
}

company_dimension_preflight_v10() {
  local defaults_file="$1"
  local database="$2"
  local checkpoint
  local v11_history
  local schema_shape
  local scope_violations
  local setup_started
  local ingestion_table_count
  local ingestion_started=0
  local orphan_count
  checkpoint="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
    FROM flyway_schema_history
    WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$checkpoint" == "10" ]] || {
    printf 'COMPANY_V11_PREFLIGHT_ERROR exact_v10_required\n' >&2
    return 1
  }
  v11_history="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM flyway_schema_history
    WHERE type = 'SQL' AND version = '11';
  ")"
  [[ "$v11_history" == "0" ]] || {
    printf 'COMPANY_V11_PREFLIGHT_ERROR partial_v11_history\n' >&2
    return 1
  }
  schema_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      (
        SELECT COUNT(*)
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_TYPE = 'BASE TABLE'
          AND TABLE_NAME REGEXP '${W3_COMPANY_V10_TABLE_PATTERN}'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME REGEXP '${W3_COMPANY_V10_TABLE_PATTERN}'
          AND COLUMN_NAME = 'legal_entity_id'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME = 'company'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME REGEXP '${W3_COMPANY_V10_TABLE_PATTERN}'
          AND COLUMN_NAME = 'company_id'
      )
    );
  ")"
  [[ "$schema_shape" == "29|29|0|0" ]] || {
    printf 'COMPANY_V11_PREFLIGHT_ERROR non_exact_v10_shape\n' >&2
    return 1
  }
  if ! company_boundary_relationship_contract_matches \
      "$defaults_file" "$database" v10; then
    printf 'COMPANY_V11_PREFLIGHT_ERROR legacy_relationship_shape\n' >&2
    return 1
  fi
  scope_violations="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM auth_data_scope
    WHERE scope_type NOT IN ('LEGAL_ENTITY', 'ORGANIZATION', 'SELF')
       OR NOT (
            (scope_type = 'LEGAL_ENTITY'
              AND legal_entity_id IS NOT NULL
              AND organization_id IS NULL)
            OR (scope_type = 'ORGANIZATION'
              AND legal_entity_id IS NULL
              AND organization_id IS NOT NULL)
            OR (scope_type = 'SELF'
              AND legal_entity_id IS NULL
              AND organization_id IS NULL)
       );
  ")"
  [[ "$scope_violations" == "0" ]] || {
    printf 'COMPANY_V11_PREFLIGHT_ERROR invalid_scope_shape\n' >&2
    return 1
  }
  if ! auth_scope_check_contract_matches \
      "$defaults_file" "$database" v10; then
    printf 'COMPANY_V11_PREFLIGHT_ERROR scope_check_contract\n' >&2
    return 1
  fi
  if ! company_boundary_auxiliary_check_contract_matches \
      "$defaults_file" "$database" v10; then
    printf 'COMPANY_V11_PREFLIGHT_ERROR auxiliary_check_contract\n' >&2
    return 1
  fi
  if ! company_boundary_index_contract_matches \
      "$defaults_file" "$database" v10; then
    printf 'COMPANY_V11_PREFLIGHT_ERROR boundary_index_contract\n' >&2
    return 1
  fi
  orphan_count="$(
    company_boundary_orphan_count "$defaults_file" "$database" v10
  )"
  [[ "$orphan_count" == "0" ]] || {
    printf 'COMPANY_V11_PREFLIGHT_ERROR orphan_company_reference\n' >&2
    return 1
  }
  setup_started="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM attendance_setup_idempotency
    WHERE state = 'STARTED';
  ")"
  [[ "$setup_started" == "0" ]] || {
    printf 'COMPANY_V11_PREFLIGHT_ERROR setup_started\n' >&2
    return 1
  }
  ingestion_table_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${database}'
      AND TABLE_NAME = 'attendance_ingestion_idempotency';
  ")"
  if ((10#$checkpoint >= 8)); then
    [[ "$ingestion_table_count" == "1" ]] || {
      printf 'COMPANY_V11_PREFLIGHT_ERROR ingestion_table_missing\n' >&2
      return 1
    }
    ingestion_started="$(mysql_scalar "$defaults_file" "$database" "
      SELECT COUNT(*)
      FROM attendance_ingestion_idempotency
      WHERE status = 'STARTED';
    ")"
    [[ "$ingestion_started" == "0" ]] || {
      printf 'COMPANY_V11_PREFLIGHT_ERROR ingestion_started\n' >&2
      return 1
    }
  fi
  log "W3_COMPANY_V11_PREFLIGHT=PASS database=${database} exact_version=10 boundary_tables=29 relationship_tables=28 relationship_edges=29 auxiliary_checks=44 enforced=44 invalid_scopes=0 orphans=0 setup_started=0 ingestion_started=0 db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

cleanup_company_preflight_negative_probes() {
  local defaults_file="$1"
  local database="$2"
  local scope_check_count
  local company_index_probe_shape
  local auxiliary_check_probe_shape
  mysql_execute_quietly "$defaults_file" "$database" "
    SET FOREIGN_KEY_CHECKS = 0;
    DELETE FROM employee
    WHERE employee_id = 'fd000000-0000-4000-8000-000000000016';
    SET FOREIGN_KEY_CHECKS = 1;
    DELETE FROM auth_data_scope
    WHERE scope_id = 'fd000000-0000-4000-8000-000000000011';
    DELETE FROM attendance_setup_idempotency
    WHERE attendance_setup_idempotency_id =
      'fd000000-0000-4000-8000-000000000012';
    DELETE FROM attendance_ingestion_idempotency
    WHERE attendance_ingestion_idempotency_id =
      'fd000000-0000-4000-8000-000000000014';
  "
  scope_check_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND TABLE_NAME = 'auth_data_scope'
      AND CONSTRAINT_NAME = 'ck_auth_scope_target'
      AND CONSTRAINT_TYPE = 'CHECK';
  ")"
  if ! auth_scope_check_contract_matches \
      "$defaults_file" "$database" v10; then
    if [[ "$scope_check_count" == "1" ]]; then
      mysql_execute_quietly "$defaults_file" "$database" "
        ALTER TABLE auth_data_scope DROP CHECK ck_auth_scope_target;
      "
    fi
    mysql_execute_quietly "$defaults_file" "$database" "
      ALTER TABLE auth_data_scope
        ADD CONSTRAINT ck_auth_scope_target CHECK (
          (scope_type = 'LEGAL_ENTITY'
            AND legal_entity_id IS NOT NULL
            AND organization_id IS NULL)
          OR (scope_type = 'ORGANIZATION'
            AND legal_entity_id IS NULL
            AND organization_id IS NOT NULL)
          OR (scope_type = 'SELF'
            AND legal_entity_id IS NULL
            AND organization_id IS NULL)
      );
    "
  fi
  auxiliary_check_probe_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      SUM(CASE
        WHEN CONSTRAINT_NAME = 'ck_auth_scope_period'
        THEN 1 ELSE 0
      END),
      SUM(CASE
        WHEN CONSTRAINT_NAME = 'ck_auth_scope_period'
          AND ENFORCED = 'YES'
        THEN 1 ELSE 0
      END),
      SUM(CASE
        WHEN CONSTRAINT_NAME = 'ck_company_cutover_extra_probe'
        THEN 1 ELSE 0
      END)
    )
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND TABLE_NAME = 'auth_data_scope'
      AND CONSTRAINT_TYPE = 'CHECK';
  ")"
  case "$auxiliary_check_probe_shape" in
    "1|1|0")
      ;;
    "1|0|0")
      mysql_execute_quietly "$defaults_file" "$database" "
        ALTER TABLE auth_data_scope
          ALTER CHECK ck_auth_scope_period ENFORCED;
      "
      ;;
    "0|0|0")
      mysql_execute_quietly "$defaults_file" "$database" "
        ALTER TABLE auth_data_scope
          ADD CONSTRAINT ck_auth_scope_period CHECK (
            valid_to IS NULL OR valid_to > valid_from
          );
      "
      ;;
    "1|1|1")
      mysql_execute_quietly "$defaults_file" "$database" "
        ALTER TABLE auth_data_scope
          DROP CHECK ck_company_cutover_extra_probe;
      "
      ;;
    "1|0|1")
      mysql_execute_quietly "$defaults_file" "$database" "
        ALTER TABLE auth_data_scope
          DROP CHECK ck_company_cutover_extra_probe,
          ALTER CHECK ck_auth_scope_period ENFORCED;
      "
      ;;
    "0|0|1")
      mysql_execute_quietly "$defaults_file" "$database" "
        ALTER TABLE auth_data_scope
          DROP CHECK ck_company_cutover_extra_probe,
          ADD CONSTRAINT ck_auth_scope_period CHECK (
            valid_to IS NULL OR valid_to > valid_from
          );
      "
      ;;
    *)
      fail "Company auxiliary CHECK negative-probe cleanup found an unexpected state."
      ;;
  esac
  company_index_probe_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      COUNT(DISTINCT CASE
        WHEN TABLE_NAME = 'legal_entity'
          AND INDEX_NAME = 'uq_legal_entity_code'
        THEN INDEX_NAME
      END),
      COUNT(DISTINCT CASE
        WHEN TABLE_NAME = 'legal_entity'
          AND INDEX_NAME = 'uq_legal_entity_code_probe'
        THEN INDEX_NAME
      END),
      COUNT(DISTINCT CASE
        WHEN TABLE_NAME = 'legal_entity'
          AND INDEX_NAME = 'uq_company_code'
        THEN INDEX_NAME
      END)
    )
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = '${database}';
  ")"
  case "$company_index_probe_shape" in
    "0|1|0")
      mysql_execute_quietly "$defaults_file" "$database" "
        ALTER TABLE legal_entity
          RENAME INDEX uq_legal_entity_code_probe
          TO uq_legal_entity_code;
      "
      ;;
    "1|0|1")
      mysql_execute_quietly "$defaults_file" "$database" "
        ALTER TABLE legal_entity DROP INDEX uq_company_code;
      "
      ;;
    "0|1|1")
      mysql_execute_quietly "$defaults_file" "$database" "
        ALTER TABLE legal_entity DROP INDEX uq_company_code;
        ALTER TABLE legal_entity
          RENAME INDEX uq_legal_entity_code_probe
          TO uq_legal_entity_code;
      "
      ;;
    "1|0|0")
      ;;
    *)
      fail "Company index negative-probe cleanup found an unexpected state."
      ;;
  esac
}

verify_company_preflight_negative_probes() (
  local defaults_file="$1"
  local database="$2"
  local cleanup_armed="true"
  trap '
    if [[ "$cleanup_armed" == "true" ]]; then
      cleanup_company_preflight_negative_probes \
        "$defaults_file" "$database" || true
    fi
  ' EXIT
  mysql_execute_quietly "$defaults_file" "$database" "
    ALTER TABLE auth_data_scope DROP CHECK ck_auth_scope_target;
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted a missing scope CHECK."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    INSERT INTO auth_data_scope (
      scope_id, scope_type, legal_entity_id, organization_id,
      include_descendants, valid_from, valid_to
    ) VALUES (
      'fd000000-0000-4000-8000-000000000011',
      'UNSUPPORTED_SCOPE', NULL, NULL, TRUE,
      TIMESTAMP '2020-01-01 00:00:00', NULL
    );
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted an unknown scope shape."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    DELETE FROM auth_data_scope
    WHERE scope_id = 'fd000000-0000-4000-8000-000000000011';
    ALTER TABLE auth_data_scope
      ADD CONSTRAINT ck_auth_scope_target CHECK (
        (scope_type = 'LEGAL_ENTITY'
          AND legal_entity_id IS NOT NULL
          AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION'
          AND legal_entity_id IS NULL
          AND organization_id IS NOT NULL)
        OR (scope_type = 'PERSONAL'
          AND legal_entity_id IS NULL
          AND organization_id IS NULL)
      );
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted a mutated scope literal."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    ALTER TABLE auth_data_scope DROP CHECK ck_auth_scope_target;
    ALTER TABLE auth_data_scope
      ADD CONSTRAINT ck_auth_scope_target CHECK (
        (scope_type = 'LEGAL_ENTITY'
          AND legal_entity_id IS NOT NULL)
        OR (scope_type = 'ORGANIZATION'
          AND legal_entity_id IS NULL
          AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF'
          AND legal_entity_id IS NULL
          AND organization_id IS NULL)
      );
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted a scope CHECK with a missing term."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    ALTER TABLE auth_data_scope DROP CHECK ck_auth_scope_target;
    ALTER TABLE auth_data_scope
      ADD CONSTRAINT ck_auth_scope_target CHECK (
        (scope_type = 'LEGAL_ENTITY'
          AND legal_entity_id IS NOT NULL
          AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION'
          AND legal_entity_id IS NULL
          AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF'
          AND legal_entity_id IS NULL
          AND organization_id IS NULL)
        OR (scope_type = 'UNSUPPORTED_SCOPE')
      );
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted a scope CHECK with an extra term."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    ALTER TABLE auth_data_scope DROP CHECK ck_auth_scope_target;
    ALTER TABLE auth_data_scope
      ADD CONSTRAINT ck_auth_scope_target CHECK (
        (scope_type = 'LEGAL_ENTITY'
          AND legal_entity_id IS NOT NULL
          AND organization_id IS NULL)
        OR (scope_type = 'ORGANIZATION'
          AND legal_entity_id IS NULL
          AND organization_id IS NOT NULL)
        OR (scope_type = 'SELF'
          AND legal_entity_id IS NULL
          AND organization_id IS NULL)
      );
    ALTER TABLE auth_data_scope
      ALTER CHECK ck_auth_scope_period NOT ENFORCED;
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted a non-enforced auxiliary CHECK."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    ALTER TABLE auth_data_scope
      ALTER CHECK ck_auth_scope_period ENFORCED;
    ALTER TABLE auth_data_scope
      DROP CHECK ck_auth_scope_period;
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted a missing auxiliary CHECK."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    ALTER TABLE auth_data_scope
      ADD CONSTRAINT ck_auth_scope_period CHECK (
        valid_to IS NULL OR valid_to > valid_from
      );
    ALTER TABLE auth_data_scope
      ADD CONSTRAINT ck_company_cutover_extra_probe CHECK (
        include_descendants IN (TRUE, FALSE)
      );
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted an extra auxiliary CHECK."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    ALTER TABLE auth_data_scope
      DROP CHECK ck_company_cutover_extra_probe;
    ALTER TABLE legal_entity
      RENAME INDEX uq_legal_entity_code
      TO uq_legal_entity_code_probe;
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted a missing source boundary index."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    ALTER TABLE legal_entity
      RENAME INDEX uq_legal_entity_code_probe
      TO uq_legal_entity_code;
    CREATE INDEX uq_company_code ON legal_entity (status);
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted an occupied target boundary index."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    ALTER TABLE legal_entity DROP INDEX uq_company_code;
    SET FOREIGN_KEY_CHECKS = 0;
    INSERT INTO employee (
      employee_id, legal_entity_id, employee_number, display_name,
      employment_status, onboard_date, row_version, created_at, updated_at
    ) VALUES (
      'fd000000-0000-4000-8000-000000000016',
      'fd000000-0000-4000-8000-000000000017',
      'V10-ORPHAN-PROBE', 'V10 orphan company probe',
      'ACTIVE', DATE '2020-01-01', 0,
      TIMESTAMP '2020-01-01 00:00:00',
      TIMESTAMP '2020-01-01 00:00:00'
    );
    SET FOREIGN_KEY_CHECKS = 1;
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted an orphan company reference."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    SET FOREIGN_KEY_CHECKS = 0;
    DELETE FROM employee
    WHERE employee_id = 'fd000000-0000-4000-8000-000000000016';
    SET FOREIGN_KEY_CHECKS = 1;
    INSERT INTO attendance_setup_idempotency (
      attendance_setup_idempotency_id, actor_id, operation_code,
      resource_type, resource_id, idempotency_key, request_digest,
      state, created_at
    ) VALUES (
      'fd000000-0000-4000-8000-000000000012',
      '20000000-0000-0000-0000-000000000001',
      'COMPANY_V11_PREFLIGHT', 'COMPANY_MIGRATION',
      'fd000000-0000-4000-8000-000000000013',
      'company-v11-setup-started',
      REPEAT('c', 64), 'STARTED', CURRENT_TIMESTAMP(6)
    );
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted a started setup request."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    DELETE FROM attendance_setup_idempotency
    WHERE attendance_setup_idempotency_id =
      'fd000000-0000-4000-8000-000000000012';
    INSERT INTO attendance_ingestion_idempotency (
      attendance_ingestion_idempotency_id, actor_id, operation_code,
      resource_type, resource_id, idempotency_key, request_digest,
      status, created_at
    ) VALUES (
      'fd000000-0000-4000-8000-000000000014',
      '20000000-0000-0000-0000-000000000001',
      'COMPANY_V11_PREFLIGHT', 'COMPANY_MIGRATION',
      'fd000000-0000-4000-8000-000000000015',
      'company-v11-ingestion-started',
      REPEAT('d', 64), 'STARTED', CURRENT_TIMESTAMP(6)
    );
  "
  if company_dimension_preflight_v10 \
      "$defaults_file" "$database" >/dev/null 2>&1; then
    fail "Company preflight accepted a started ingestion request."
  fi
  mysql_execute_quietly "$defaults_file" "$database" "
    DELETE FROM attendance_ingestion_idempotency
    WHERE attendance_ingestion_idempotency_id =
      'fd000000-0000-4000-8000-000000000014';
  "
  company_dimension_preflight_v10 "$defaults_file" "$database" \
    || fail "Company preflight did not recover after negative probes."
  cleanup_company_preflight_negative_probes "$defaults_file" "$database"
  cleanup_armed="false"
  log "W3_COMPANY_V11_PREFLIGHT_NEGATIVE=PASS database=${database} missing_scope_check=REJECTED unknown_scope=REJECTED mutated_literal=REJECTED missing_term=REJECTED extra_term=REJECTED non_enforced_auxiliary_check=REJECTED missing_auxiliary_check=REJECTED extra_auxiliary_check=REJECTED missing_source_index=REJECTED occupied_target_index=REJECTED orphan=REJECTED setup_started=REJECTED ingestion_started=REJECTED restored=true db_identity=${W3_RUNTIME_DB_IDENTITY}"
)

verify_company_dimension_latest() {
  local defaults_file="$1"
  local database="$2"
  local schema_shape
  local legacy_relationship_count
  local scope_violations
  local legacy_scopes
  local orphan_count
  schema_shape="$(mysql_scalar "$defaults_file" "" "
    SELECT CONCAT_WS(
      '|',
      (
        SELECT COUNT(*)
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_TYPE = 'BASE TABLE'
          AND TABLE_NAME REGEXP '${W3_COMPANY_LATEST_TABLE_PATTERN}'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME REGEXP '${W3_COMPANY_LATEST_TABLE_PATTERN}'
          AND COLUMN_NAME = 'company_id'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME = 'legal_entity'
      ),
      (
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = '${database}'
          AND TABLE_NAME REGEXP '${W3_COMPANY_LATEST_TABLE_PATTERN}'
          AND COLUMN_NAME = 'legal_entity_id'
      )
    );
  ")"
  [[ "$schema_shape" == "29|29|0|0" ]] \
    || fail "Latest company schema is not the exact 29-table shape."
  company_boundary_relationship_contract_matches \
    "$defaults_file" "$database" latest \
    || fail "Latest company relationship metadata is not the exact 28-table/29-edge contract."
  legacy_relationship_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*)
    FROM information_schema.KEY_COLUMN_USAGE
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND (
        COLUMN_NAME = 'legal_entity_id'
        OR REFERENCED_COLUMN_NAME = 'legal_entity_id'
        OR REFERENCED_TABLE_NAME = 'legal_entity'
      );
  ")"
  [[ "$legacy_relationship_count" == "0" ]] \
    || fail "Latest company relationship metadata retains an old boundary."
  scope_violations="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM auth_data_scope
    WHERE scope_type NOT IN ('COMPANY', 'ORGANIZATION', 'SELF')
       OR NOT (
            (scope_type = 'COMPANY'
              AND company_id IS NOT NULL
              AND organization_id IS NULL)
            OR (scope_type = 'ORGANIZATION'
              AND company_id IS NULL
              AND organization_id IS NOT NULL)
            OR (scope_type = 'SELF'
              AND company_id IS NULL
              AND organization_id IS NULL)
       );
  ")"
  [[ "$scope_violations" == "0" ]] \
    || fail "Latest company scope values or shapes are invalid."
  legacy_scopes="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM auth_data_scope
    WHERE scope_type = CONCAT('LEGAL', '_ENTITY');
  ")"
  [[ "$legacy_scopes" == "0" ]] \
    || fail "Latest company schema retains an old top-level scope."
  orphan_count="$(
    company_boundary_orphan_count "$defaults_file" "$database" latest
  )"
  [[ "$orphan_count" == "0" ]] \
    || fail "Latest company schema contains an orphan company reference."
  company_boundary_index_contract_matches \
    "$defaults_file" "$database" latest \
    || fail "Latest company boundary index pairs are not exact."
  auth_scope_check_contract_matches \
    "$defaults_file" "$database" latest \
    || fail "Latest company scope CHECK contract is not exact and enforced."
  company_boundary_auxiliary_check_contract_matches \
    "$defaults_file" "$database" latest \
    || fail "Latest company auxiliary CHECK inventory is not exact and enforced."
  log "W3_COMPANY_V11_LATEST=PASS database=${database} boundary_tables=29 relationship_tables=28 relationship_edges=29 auxiliary_checks=44 enforced=44 company_indexes=5 invalid_scopes=0 legacy_scopes=0 orphans=0 db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

verify_registry_database_contract() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local columns_snapshot
  local primary_snapshot
  local columns_sha
  local primary_sha
  columns_snapshot="$(make_private_temporary_file)"
  primary_snapshot="$(make_private_temporary_file)"

  mysql_query "$defaults_file" "" "
    SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, IS_NULLABLE,
           DATA_TYPE, COLUMN_TYPE, COALESCE(CHARACTER_SET_NAME, '<NULL>')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
    ORDER BY TABLE_NAME, ORDINAL_POSITION;
  " >"$columns_snapshot"
  mysql_query "$defaults_file" "" "
    SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION
    FROM information_schema.KEY_COLUMN_USAGE
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND CONSTRAINT_NAME = 'PRIMARY'
      AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
    ORDER BY TABLE_NAME, ORDINAL_POSITION;
  " >"$primary_snapshot"

  case "$schema_mode" in
    target7 | latest) ;;
    *) fail "Unknown W3 registry schema mode." ;;
  esac

  if ! python3 - "$W3_REGISTRY" "$columns_snapshot" "$primary_snapshot" \
      "$schema_mode" <<'PY'
import json
import sys
from pathlib import Path

registry = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
column_lines = Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
primary_lines = Path(sys.argv[3]).read_text(encoding="utf-8").splitlines()
schema_mode = sys.argv[4]
if schema_mode not in {"target7", "latest"}:
    raise SystemExit("invalid W3 registry schema mode")

actual = {}
for line in column_lines:
    fields = line.split("\t")
    if len(fields) != 7:
        raise SystemExit("invalid information_schema column framing")
    table, column, ordinal, nullable, data_type, column_type, charset = fields
    actual.setdefault(table, []).append({
        "name": column,
        "ordinal": int(ordinal),
        "nullable": nullable == "YES",
        "data_type": data_type.lower(),
        "column_type": column_type.lower(),
        "charset": charset.lower(),
    })

primary = {}
for line in primary_lines:
    fields = line.split("\t")
    if len(fields) != 3:
        raise SystemExit("invalid information_schema primary-key framing")
    table, column, ordinal = fields
    primary.setdefault(table, []).append((int(ordinal), column))

expected_names = [item["table"] for item in registry["tables"]]
if set(actual) != set(expected_names) or len(actual) != 22:
    missing = sorted(set(expected_names) - set(actual))
    extra = sorted(set(actual) - set(expected_names))
    raise SystemExit(f"database W3 table set differs; missing={missing} extra={extra}")

def assert_logical_type(table, column, logical, value):
    data_type = value["data_type"]
    column_type = value["column_type"]
    charset = value["charset"]
    if logical == "id":
        ok = data_type in {"char", "varchar"} and "(36)" in column_type and charset == "ascii"
    elif logical == "code":
        ok = data_type in {"char", "varchar"} and charset == "ascii"
    elif logical == "text":
        ok = data_type in {"char", "varchar", "text", "mediumtext", "longtext"} and charset in {"utf8", "utf8mb4"}
    elif logical == "uint":
        ok = data_type in {"tinyint", "smallint", "mediumint", "int", "bigint"} and "unsigned" in column_type
    elif logical == "date":
        ok = data_type == "date"
    elif logical == "instant":
        ok = data_type in {"datetime", "timestamp"} and "(6)" in column_type
    elif logical == "json":
        ok = data_type == "json"
    elif logical == "digest":
        ok = data_type in {"char", "varchar"} and "(64)" in column_type and charset == "ascii"
    elif logical == "bool":
        ok = data_type in {"boolean", "tinyint"} and ("(1)" in column_type or column_type == "boolean")
    else:
        ok = False
    if not ok:
        raise SystemExit(f"logical type mismatch: {table}.{column} expected={logical} actual={column_type}/{charset}")

for definition in registry["tables"]:
    table = definition["table"]
    expected_columns = definition["columns"]
    actual_columns = sorted(actual[table], key=lambda item: item["ordinal"])
    if len(actual_columns) != len(expected_columns):
        raise SystemExit(f"column count mismatch: {table}")
    for expected, value in zip(expected_columns, actual_columns):
        column, logical = expected.split(":", 1)
        if schema_mode == "latest" and column == "legal_entity_id":
            column = "company_id"
        nullable = logical.endswith("?")
        logical = logical.removesuffix("?")
        if value["name"] != column or value["nullable"] != nullable:
            raise SystemExit(f"column order/nullability mismatch: {table}.{column}")
        assert_logical_type(table, column, logical, value)
    actual_pk = [column for _, column in sorted(primary.get(table, []))]
    if actual_pk != definition["pk"]:
        raise SystemExit(f"primary-key mismatch: {table}")

print(
    "W3_REGISTRY_DB_COMPARE=PASS "
    f"tables=22 schema_mode={schema_mode} flyway_metadata=excluded"
)
PY
  then
    fail "Database W3 metadata does not match the fixed registry."
  fi

  columns_sha="$(hash_file "$columns_snapshot")"
  primary_sha="$(hash_file "$primary_snapshot")"
  log "W3_RETAINED_REGISTRY_DB=PASS database=${database} tables=22 schema_mode=${schema_mode} flyway_metadata=excluded columns_sha256=${columns_sha} primary_sha256=${primary_sha} db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

verify_seed_oracle() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local company_column
  local kind
  local template_id
  local scope_id
  local scoped_version_id
  local snapshot_json
  local digest
  local count
  case "$schema_mode" in
    target7) company_column="legal_entity_id" ;;
    latest) company_column="company_id" ;;
    *) fail "Unknown W3 seed schema mode." ;;
  esac
  while IFS='|' read -r kind template_id scope_id scoped_version_id snapshot_json digest; do
    count="$(mysql_scalar "$defaults_file" "$database" "
      SELECT COUNT(*)
      FROM attendance_policy_template template
      JOIN attendance_policy_scope scope
        ON scope.policy_template_id = template.policy_template_id
      JOIN attendance_policy_scoped_version version
        ON version.scope_id = scope.scope_id
      WHERE template.policy_template_id = '${template_id}'
        AND template.template_code = '${kind}'
        AND scope.scope_id = '${scope_id}'
        AND scope.${company_column} = '30000000-0000-0000-0000-000000000001'
        AND version.scoped_version_id = '${scoped_version_id}'
        AND version.version_number = 1
        AND version.effective_from = DATE '1970-01-01'
        AND version.effective_to IS NULL
        AND version.snapshot_digest = '${digest}'
        AND JSON_CONTAINS(version.snapshot_json, CAST('${snapshot_json}' AS JSON))
        AND JSON_CONTAINS(CAST('${snapshot_json}' AS JSON), version.snapshot_json)
        AND JSON_CONTAINS(
              version.parameters_json,
              JSON_EXTRACT(CAST('${snapshot_json}' AS JSON), '$.parameters'))
        AND JSON_CONTAINS(
              JSON_EXTRACT(CAST('${snapshot_json}' AS JSON), '$.parameters'),
              version.parameters_json)
        AND (
          SELECT COUNT(*)
          FROM attendance_policy_lifecycle_event event
          WHERE event.scope_id = scope.scope_id
            AND event.scoped_version_id = version.scoped_version_id
            AND event.event_sequence = 1
            AND event.action = 'PUBLISHED'
            AND event.business_effective_from = DATE '1970-01-01'
            AND event.predecessor_event_id IS NULL
        ) = 1;
    ")"
    [[ "$count" == "1" ]] || fail "Fixed W3 seed oracle mismatch: ${kind}."
    log "W3_SEED_ORACLE_ROW=PASS database=${database} kind=${kind} digest=${digest}"
  done <<'SEEDS'
MEAL_DEDUCTION|25000000-0000-0000-0000-000000000001|25100000-0000-0000-0000-000000000001|25200000-0000-0000-0000-000000000001|{"effectiveFrom":"1970-01-01","effectiveTo":null,"legalEntityId":"30000000-0000-0000-0000-000000000001","parameters":{"applicableDayTypes":["SPECIAL_WORKDAY","WORKDAY"],"deductionMinutes":30,"enabled":true,"mealWindowEnd":"20:00","mealWindowStart":"18:00","triggerMinutes":240},"policyKind":"MEAL_DEDUCTION","scopeId":"25100000-0000-0000-0000-000000000001","templateId":"25000000-0000-0000-0000-000000000001","versionNumber":1}|4279b3f40121f995d09245f3450e1087b4d5757e237ded4f8d4e0343fb210981
LATE_GRACE|25000000-0000-0000-0000-000000000002|25100000-0000-0000-0000-000000000002|25200000-0000-0000-0000-000000000002|{"effectiveFrom":"1970-01-01","effectiveTo":null,"legalEntityId":"30000000-0000-0000-0000-000000000001","parameters":{"enabled":true,"graceMinutes":15},"policyKind":"LATE_GRACE","scopeId":"25100000-0000-0000-0000-000000000002","templateId":"25000000-0000-0000-0000-000000000002","versionNumber":1}|ea11c63a991838a20b61d6d3e9d0281035263b8529b2fb767fa0b2e631306ce9
MONTHLY_LATE_EXEMPTION|25000000-0000-0000-0000-000000000003|25100000-0000-0000-0000-000000000003|25200000-0000-0000-0000-000000000003|{"effectiveFrom":"1970-01-01","effectiveTo":null,"legalEntityId":"30000000-0000-0000-0000-000000000001","parameters":{"enabled":true,"graceMinutes":15,"monthlyUses":1,"resetOnGroupChange":false},"policyKind":"MONTHLY_LATE_EXEMPTION","scopeId":"25100000-0000-0000-0000-000000000003","templateId":"25000000-0000-0000-0000-000000000003","versionNumber":1}|b0a533500852c464c7065812fd56519f0c571ebbf453834a8b189388e29f1a51
SEEDS
  log "W3_SEED_ORACLE=PASS database=${database} schema_mode=${schema_mode} templates=3 scopes=3 scoped_versions=3 lifecycle=PUBLISHED db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

verify_target7_seed_exactness() {
  local defaults_file="$1"
  local database="$2"
  local seed_counts
  seed_counts="$(mysql_scalar "$defaults_file" "$database" "
    SELECT CONCAT_WS(
      '|',
      (SELECT COUNT(*) FROM attendance_policy_template),
      (SELECT COUNT(*) FROM attendance_policy_scope),
      (SELECT COUNT(*) FROM attendance_policy_scoped_version),
      (SELECT COUNT(*) FROM attendance_policy_lifecycle_event),
      (SELECT COUNT(*) FROM attendance_policy_binding_family),
      (SELECT COUNT(*) FROM attendance_policy_binding_revision)
    );
  ")"
  [[ "$seed_counts" == "3|3|3|3|0|0" ]] \
    || fail "Target7 must contain exactly the three fixed seed aggregates and no bindings."
  log "W3_TARGET7_SEED_EXACT=PASS database=${database} templates=3 scopes=3 scoped_versions=3 lifecycle_events=3 binding_families=0 binding_revisions=0 db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

verify_constraint_and_index_names() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local foreign_key_snapshot
  local check_snapshot
  local index_snapshot
  foreign_key_snapshot="$(make_private_temporary_file)"
  check_snapshot="$(make_private_temporary_file)"
  index_snapshot="$(make_private_temporary_file)"
  mysql_query "$defaults_file" "" "
    SELECT TABLE_NAME, CONSTRAINT_NAME
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
      AND CONSTRAINT_TYPE = 'FOREIGN KEY'
    ORDER BY TABLE_NAME, CONSTRAINT_NAME;
  " > "$foreign_key_snapshot"
  mysql_query "$defaults_file" "" "
    SELECT TABLE_NAME, CONSTRAINT_NAME, ENFORCED
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
      AND CONSTRAINT_TYPE = 'CHECK'
    ORDER BY TABLE_NAME, CONSTRAINT_NAME;
  " > "$check_snapshot"
  mysql_query "$defaults_file" "" "
    SELECT DISTINCT TABLE_NAME, INDEX_NAME
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
    ORDER BY TABLE_NAME, INDEX_NAME;
  " > "$index_snapshot"

  if ! python3 - "$W3_V7_MIGRATION" \
      "$foreign_key_snapshot" "$check_snapshot" "$index_snapshot" \
      "$W3_RUNTIME_DB_IDENTITY" "$schema_mode" <<'PY'
import re
import sys
from pathlib import Path

ddl = Path(sys.argv[1]).read_text(encoding="utf-8")
schema_mode = sys.argv[6]
if schema_mode not in {"target7", "latest"}:
    raise SystemExit("invalid W3 constraint schema mode")

def read_tsv(path, width):
    rows = set()
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        fields = tuple(line.split("\t"))
        if len(fields) != width:
            raise SystemExit(f"invalid metadata framing in {path}")
        rows.add(fields)
    return rows

blocks = {}
for match in re.finditer(
    r"(?is)CREATE\s+TABLE\s+`?([a-z0-9_]+)`?\s*\((.*?)\)"
    r"\s*ENGINE\s*=\s*InnoDB",
    ddl,
):
    blocks[match.group(1)] = match.group(2)

expected_foreign_keys = set()
expected_checks = set()
expected_explicit_indexes = set()
for table, block in blocks.items():
    for name in re.findall(r"(?im)^\s*CONSTRAINT\s+(fk_[a-z0-9_]+)\b", block):
        expected_foreign_keys.add((table, name))
    for name in re.findall(r"(?im)^\s*CONSTRAINT\s+(ck_[a-z0-9_]+)\b", block):
        expected_checks.add((table, name, "YES"))
    if re.search(r"(?im)^\s*PRIMARY\s+KEY\b", block):
        expected_explicit_indexes.add((table, "PRIMARY"))
    for name in re.findall(
        r"(?im)^\s*(?:UNIQUE\s+)?KEY\s+([a-z0-9_]+)\b", block
    ):
        if (
            schema_mode == "latest"
            and table == "attendance_policy_scope"
            and name == "ix_attendance_policy_scope_legal_entity"
        ):
            name = "ix_attendance_policy_scope_company"
        expected_explicit_indexes.add((table, name))

actual_foreign_keys = read_tsv(sys.argv[2], 2)
actual_checks = read_tsv(sys.argv[3], 3)
actual_indexes = read_tsv(sys.argv[4], 2)
if actual_foreign_keys != expected_foreign_keys:
    raise SystemExit("runtime foreign-key names differ from frozen V7")
if actual_checks != expected_checks:
    raise SystemExit("runtime CHECK names/ENFORCED state differ from frozen V7")
missing_indexes = expected_explicit_indexes - actual_indexes
extra_indexes = actual_indexes - expected_explicit_indexes
allowed_implicit_indexes = expected_foreign_keys
if missing_indexes:
    raise SystemExit("runtime is missing an explicit frozen V7 index")
if extra_indexes - allowed_implicit_indexes:
    raise SystemExit("runtime contains an unexplained W3 index")

print(
    "W3_CONSTRAINT_INDEX_NAME_COMPARE=PASS "
    f"foreign_keys={len(actual_foreign_keys)} checks={len(actual_checks)} "
    f"explicit_indexes={len(expected_explicit_indexes)} "
    f"implicit_fk_indexes={len(extra_indexes)} "
    f"schema_mode={schema_mode} db_identity={sys.argv[5]}"
)
PY
  then
    fail "Runtime W3 constraint/index names differ from the frozen V7 contract."
  fi
}

verify_constraints_and_capabilities() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local non_enforced
  local checks
  local foreign_keys
  local indexes
  local capability_count
  local admin_grants
  local auditor_read
  local auditor_manage
  verify_constraint_and_index_names \
    "$defaults_file" "$database" "$schema_mode"
  non_enforced="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
      AND CONSTRAINT_TYPE = 'CHECK'
      AND ENFORCED <> 'YES';
  ")"
  [[ "$non_enforced" == "0" ]] || fail "A W3 CHECK constraint is not ENFORCED."
  checks="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
      AND CONSTRAINT_TYPE = 'CHECK';
  ")"
  [[ "$checks" == "25" ]] \
    || fail "W3 CHECK metadata count differs from the frozen V7 contract."
  foreign_keys="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}';
  ")"
  [[ "$foreign_keys" == "71" ]] \
    || fail "W3 foreign-key metadata count differs from the frozen V7 contract."
  indexes="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(DISTINCT CONCAT(TABLE_NAME, CHAR(0), INDEX_NAME))
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = '${database}'
      AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}';
  ")"
  [[ "$indexes" =~ ^[0-9]+$ && "$indexes" -ge 84 ]] \
    || fail "W3 index metadata is incomplete."

  capability_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*) FROM auth_capability
    WHERE capability_code LIKE 'ATTENDANCE_SETUP:%';
  ")"
  [[ "$capability_count" == "6" ]] || fail "W3 capability catalog must contain exactly six rows."
  admin_grants="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM auth_role_capability grant_row
    JOIN auth_role role ON role.role_id = grant_row.role_id
    JOIN auth_capability capability ON capability.capability_id = grant_row.capability_id
    WHERE role.role_code IN ('HR_ADMIN', 'SYSTEM_ADMIN')
      AND capability.capability_code LIKE 'ATTENDANCE_SETUP:%';
  ")"
  [[ "$admin_grants" == "12" ]] || fail "W3 administrator capability grants are incomplete."
  auditor_read="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM auth_role_capability grant_row
    JOIN auth_role role ON role.role_id = grant_row.role_id
    JOIN auth_capability capability ON capability.capability_id = grant_row.capability_id
    WHERE role.role_code = 'AUDITOR'
      AND capability.capability_code = 'ATTENDANCE_SETUP:READ';
  ")"
  [[ "$auditor_read" == "1" ]] || fail "AUDITOR read capability is missing."
  auditor_manage="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM auth_role_capability grant_row
    JOIN auth_role role ON role.role_id = grant_row.role_id
    JOIN auth_capability capability ON capability.capability_id = grant_row.capability_id
    WHERE role.role_code = 'AUDITOR'
      AND capability.capability_code LIKE 'ATTENDANCE_SETUP:%'
      AND capability.action_code <> 'READ';
  ")"
  [[ "$auditor_manage" == "0" ]] || fail "AUDITOR received a W3 manage capability."
  log "W3_CONSTRAINT_CAPABILITY=PASS database=${database} foreign_keys=${foreign_keys} checks=${checks} indexes=${indexes} checks_enforced=true capabilities=6 admin_grants=12 auditor_read=1 auditor_manage=0 db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

verify_app_privileges() {
  local database="$1"
  local app_defaults
  local app_account
  local other_database
  assert_exact_database "$database"
  app_defaults="$(app_defaults_file "$database")"
  case "$database" in
    "$DEV_DATABASE")
      app_account="$DEV_APP_ACCOUNT"
      other_database="$TEST_DATABASE"
      ;;
    "$TEST_DATABASE")
      app_account="$TEST_APP_ACCOUNT"
      other_database="$DEV_DATABASE"
      ;;
  esac

  mysql_query "$app_defaults" "$database" "
    START TRANSACTION;
    DELETE FROM attendance_setup_idempotency
    WHERE attendance_setup_idempotency_id = 'ffffffff-ffff-4fff-8fff-000000000003';
    INSERT INTO attendance_setup_idempotency (
      attendance_setup_idempotency_id, actor_id, operation_code,
      resource_type, resource_id, idempotency_key, request_digest,
      state, created_at
    ) VALUES (
      'ffffffff-ffff-4fff-8fff-000000000003',
      '20000000-0000-0000-0000-000000000001',
      'W3_MYSQL_VERIFY', 'VERIFY_RESOURCE',
      'ffffffff-ffff-4fff-8fff-000000000004',
      'w3-mysql-transactional-dml',
      REPEAT('a', 64), 'STARTED', CURRENT_TIMESTAMP(6)
    );
    UPDATE attendance_setup_idempotency
    SET resource_type = 'VERIFY_RESOURCE_UPDATED'
    WHERE attendance_setup_idempotency_id = 'ffffffff-ffff-4fff-8fff-000000000003';
    DELETE FROM attendance_setup_idempotency
    WHERE attendance_setup_idempotency_id = 'ffffffff-ffff-4fff-8fff-000000000003';
    ROLLBACK;
  " >/dev/null
  expect_mysql_failure "$app_defaults" "$database" \
    "CREATE TEMPORARY TABLE w3_forbidden_ddl_probe (id INT);" \
    "W3_APP_DDL"
  expect_mysql_failure "$app_defaults" "" \
    "GRANT SELECT ON ${database}.* TO '${app_account}'@'127.0.0.1';" \
    "W3_APP_GRANT"
  expect_mysql_failure "$app_defaults" "" \
    "SELECT COUNT(*) FROM ${other_database}.attendance_policy_template;" \
    "W3_APP_CROSS_SCHEMA"
  expect_mysql_failure "$app_defaults" "" \
    "SELECT COUNT(*) FROM mysql.user;" \
    "W3_APP_SYSTEM_SCHEMA"
  log "W3_APP_PRIVILEGES=PASS database=${database} transactional_crud=PASS ddl=DENIED grant=DENIED cross_schema=DENIED system_schema=DENIED db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

verify_chosen_index() {
  local defaults_file
  local plan
  defaults_file="$(migrator_defaults_file)"
  plan="$(mysql_query_with_headers "$defaults_file" "$TEST_DATABASE" "
    START TRANSACTION;
    INSERT INTO attendance_setup_idempotency (
      attendance_setup_idempotency_id, actor_id, operation_code,
      resource_type, resource_id, idempotency_key, request_digest,
      state, created_at
    )
    WITH RECURSIVE sequence_rows(sequence_number) AS (
      SELECT 1
      UNION ALL
      SELECT sequence_number + 1
      FROM sequence_rows
      WHERE sequence_number < 512
    )
    SELECT
      CONCAT('fe000000-0000-4000-8000-', LPAD(sequence_number, 12, '0')),
      '20000000-0000-0000-0000-000000000001',
      'W3_INDEX_VERIFY',
      'VERIFY_RESOURCE',
      CONCAT('fd000000-0000-4000-8000-', LPAD(sequence_number, 12, '0')),
      CONCAT('w3-index-', sequence_number),
      REPEAT('b', 64),
      'STARTED',
      TIMESTAMP '2026-07-27 00:00:00' + INTERVAL sequence_number SECOND
    FROM sequence_rows;

    EXPLAIN FORMAT=JSON
    SELECT attendance_setup_idempotency_id
    FROM attendance_setup_idempotency
    WHERE state = 'STARTED'
      AND created_at < TIMESTAMP '2026-07-28 00:00:00'
    ORDER BY created_at
    LIMIT 20;
    ROLLBACK;
  ")"
  [[ "$plan" == *'"key": "ix_attendance_setup_idempotency_state"'* ]] \
    || fail "Representative W3 query did not choose its state/current-time index."
  log "W3_QUERY_PLAN=PASS database=${TEST_DATABASE} query=idempotency_current index=ix_attendance_setup_idempotency_state fixture_rows=512 rolled_back=true db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

verify_current_lock() {
  local app_defaults
  local holder_error
  local challenger_error
  local holder_pid
  app_defaults="$(app_defaults_file "$TEST_DATABASE")"
  holder_error="$(make_private_temporary_file)"
  challenger_error="$(make_private_temporary_file)"

  (
    printf '%s\n' "
      START TRANSACTION;
      SELECT policy_template_id
      FROM attendance_policy_template
      WHERE policy_template_id = '25000000-0000-0000-0000-000000000001'
      FOR UPDATE;
      SELECT SLEEP(4);
      ROLLBACK;
    " | "$SHENZHOUHR_MYSQL_CLIENT_BIN" \
      "--defaults-extra-file=${app_defaults}" \
      --batch --raw --skip-column-names \
      "--database=${TEST_DATABASE}" >/dev/null 2>"$holder_error"
  ) &
  holder_pid=$!
  sleep 1

  if printf '%s\n' "
      SET SESSION innodb_lock_wait_timeout = 1;
      START TRANSACTION;
      UPDATE attendance_policy_template
      SET name = name
      WHERE policy_template_id = '25000000-0000-0000-0000-000000000001';
      ROLLBACK;
    " | "$SHENZHOUHR_MYSQL_CLIENT_BIN" \
      "--defaults-extra-file=${app_defaults}" \
      --batch --raw --silent \
      "--database=${TEST_DATABASE}" >/dev/null 2>"$challenger_error"; then
    wait "$holder_pid" || true
    fail "Concurrent W3 update was not blocked by the current-read lock."
  fi
  grep -Eq 'ERROR 1205|Lock wait timeout exceeded' "$challenger_error" \
    || {
      wait "$holder_pid" || true
      fail "Concurrent W3 update failed for a reason other than lock timeout."
    }
  wait "$holder_pid" || fail "Current-read lock holder failed."
  log "W3_CURRENT_LOCK=PASS database=${TEST_DATABASE} holder=SELECT_FOR_UPDATE challenger=LOCK_TIMEOUT rollback=true db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

reset_test_tables() {
  local defaults_file
  local object_name
  local views
  local tables
  local sql="SET FOREIGN_KEY_CHECKS = 0;"
  require_test_rebuild_confirmation
  defaults_file="$(migrator_defaults_file)"
  assert_schema_exists "$TEST_DATABASE" "$defaults_file"
  verify_mysql_8410

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
  log "WAVE3_TEST_TABLE_REBUILD=PASS database=${TEST_DATABASE} confirmation=exact db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

bootstrap_fixed_legal_entity_at_v6() {
  local database="$1"
  local defaults_file
  local checkpoint
  local exact_row_count
  assert_exact_database "$database"
  defaults_file="$(migrator_defaults_file)"
  checkpoint="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
    FROM flyway_schema_history
    WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$checkpoint" == "6" ]] \
    || fail "The fixed legal-entity bootstrap is authorized only at the exact V6 boundary."
  mysql_execute_quietly "$defaults_file" "$database" "
    INSERT IGNORE INTO legal_entity (
      legal_entity_id, code, name, status, created_at
    ) VALUES (
      '30000000-0000-0000-0000-000000000001',
      'W3_BASELINE_LEGAL_ENTITY',
      'W3 verification baseline legal entity',
      'ACTIVE',
      TIMESTAMP '2020-01-01 00:00:00'
    );
  "
  exact_row_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM legal_entity
    WHERE legal_entity_id = '30000000-0000-0000-0000-000000000001'
      AND code = 'W3_BASELINE_LEGAL_ENTITY'
      AND name = 'W3 verification baseline legal entity'
      AND status = 'ACTIVE'
      AND created_at = TIMESTAMP '2020-01-01 00:00:00';
  ")"
  [[ "$exact_row_count" == "1" ]] \
    || fail "The exact fixed legal-entity fixture conflicts with existing V6 data."
  log "W3_FIXED_TENANT_BOOTSTRAP=PASS database=${database} boundary=V6 legal_entity_id=30000000-0000-0000-0000-000000000001 db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

verify_fixed_legal_entity_before_v11() {
  local database="$1"
  local defaults_file="$2"
  local exact_row_count
  exact_row_count="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COUNT(*)
    FROM legal_entity
    WHERE legal_entity_id = '30000000-0000-0000-0000-000000000001'
      AND code = 'W3_BASELINE_LEGAL_ENTITY'
      AND name = 'W3 verification baseline legal entity'
      AND status = 'ACTIVE'
      AND created_at = TIMESTAMP '2020-01-01 00:00:00';
  ")"
  [[ "$exact_row_count" == "1" ]] \
    || fail "Existing V7-V10 development data lacks the exact fixed company baseline required by retained W3 seeds."
  log "W3_FIXED_TENANT_EXISTING=PASS database=${database} boundary=V7-V10 exact_baseline=true db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

bootstrap_populated_company_fixture_at_v10() {
  local database="$1"
  local defaults_file="$2"
  local checkpoint
  local fixture_counts
  checkpoint="$(mysql_scalar "$defaults_file" "$database" "
    SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
    FROM flyway_schema_history
    WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$checkpoint" == "10" ]] \
    || fail "The populated company fixture is authorized only at exact V10."
  mysql_execute_quietly "$defaults_file" "$database" "
    INSERT INTO legal_entity (
      legal_entity_id, code, name, status, created_at
    ) VALUES (
      '30000000-0000-0000-0000-000000000002',
      'W3_SECOND_COMPANY',
      'W3 verification second company',
      'ACTIVE', TIMESTAMP '2020-01-01 00:00:00'
    );
    INSERT INTO organization_identity (
      organization_id, legal_entity_id, identity_status, created_at
    ) VALUES (
      'fd100000-0000-4000-8000-000000000001',
      '30000000-0000-0000-0000-000000000001',
      'ACTIVE', TIMESTAMP '2020-01-01 00:00:00'
    );
    INSERT INTO organization_version (
      organization_version_id, organization_id, parent_organization_id,
      code, name, org_type, effective_from, effective_to,
      row_version, status, source_authority, change_reason,
      created_by, created_at
    ) VALUES (
      'fd110000-0000-4000-8000-000000000001',
      'fd100000-0000-4000-8000-000000000001',
      NULL, 'V10-COMPANY-ORG', 'V10 retained company organization',
      'DEPARTMENT', TIMESTAMP '2020-01-01 00:00:00', NULL,
      0, 'ACTIVE', 'LOCAL', 'V10 company retention fixture',
      '20000000-0000-0000-0000-000000000001',
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO organization_current_projection (
      organization_id, current_version_id, projection_batch_id, projected_at
    ) VALUES (
      'fd100000-0000-4000-8000-000000000001',
      'fd110000-0000-4000-8000-000000000001',
      'V10-COMPANY-RETENTION', TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO organization_current_closure (
      ancestor_organization_id, descendant_organization_id,
      depth, projection_batch_id
    ) VALUES (
      'fd100000-0000-4000-8000-000000000001',
      'fd100000-0000-4000-8000-000000000001',
      0, 'V10-COMPANY-RETENTION'
    );
    INSERT INTO employee (
      employee_id, legal_entity_id, employee_number, display_name,
      employment_status, onboard_date, row_version, created_at, updated_at
    ) VALUES (
      'fd200000-0000-4000-8000-000000000001',
      '30000000-0000-0000-0000-000000000001',
      'V10-EMPLOYEE-001', 'V10 retained company employee',
      'ACTIVE', DATE '2020-01-01', 0,
      TIMESTAMP '2020-01-01 00:00:00',
      TIMESTAMP '2020-01-01 00:00:00'
    );
    INSERT INTO employee (
      employee_id, legal_entity_id, employee_number, display_name,
      employment_status, onboard_date, row_version, created_at, updated_at
    ) VALUES (
      'fd200000-0000-4000-8000-000000000002',
      '30000000-0000-0000-0000-000000000002',
      'V10-EMPLOYEE-002', 'V10 retained second-company employee',
      'ACTIVE', DATE '2020-01-01', 0,
      TIMESTAMP '2020-01-01 00:00:00',
      TIMESTAMP '2020-01-01 00:00:00'
    );
    INSERT INTO employee_version (
      employee_version_id, employee_id, employee_number, display_name,
      status, external_employee_id, effective_from, effective_to,
      source_authority, row_version, change_reason, created_by, created_at
    ) VALUES (
      'fd210000-0000-4000-8000-000000000001',
      'fd200000-0000-4000-8000-000000000001',
      'V10-EMPLOYEE-001', 'V10 retained company employee',
      'ACTIVE', 'V10-EXTERNAL-001', DATE '2020-01-01', NULL,
      'LOCAL', 0, 'V10 company retention fixture',
      '20000000-0000-0000-0000-000000000001',
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO employee_version (
      employee_version_id, employee_id, employee_number, display_name,
      status, external_employee_id, effective_from, effective_to,
      source_authority, row_version, change_reason, created_by, created_at
    ) VALUES (
      'fd210000-0000-4000-8000-000000000002',
      'fd200000-0000-4000-8000-000000000002',
      'V10-EMPLOYEE-002', 'V10 retained second-company employee',
      'ACTIVE', 'V10-EXTERNAL-002', DATE '2020-01-01', NULL,
      'LOCAL', 0, 'V10 second-company retention fixture',
      '20000000-0000-0000-0000-000000000001',
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO employee_current_projection (
      employee_id, current_version_id, projected_at
    ) VALUES (
      'fd200000-0000-4000-8000-000000000001',
      'fd210000-0000-4000-8000-000000000001',
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO employee_current_projection (
      employee_id, current_version_id, projected_at
    ) VALUES (
      'fd200000-0000-4000-8000-000000000002',
      'fd210000-0000-4000-8000-000000000002',
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO employment_assignment (
      assignment_id, employment_period_id, employee_id, organization_id,
      effective_from, effective_to, row_version, change_reason,
      created_by, created_at, version_valid_to, record_status
    ) VALUES (
      'fd220000-0000-4000-8000-000000000001',
      'fd220000-0000-4000-8000-000000000001',
      'fd200000-0000-4000-8000-000000000001',
      'fd100000-0000-4000-8000-000000000001',
      TIMESTAMP '2020-01-01 00:00:00', NULL, 0,
      'V10 company retention fixture',
      '20000000-0000-0000-0000-000000000001',
      TIMESTAMP '2026-07-29 00:00:00', NULL, 'ACTIVE'
    );
    INSERT INTO auth_data_scope (
      scope_id, scope_type, legal_entity_id, organization_id,
      include_descendants, valid_from, valid_to
    ) VALUES
      (
        'fd300000-0000-4000-8000-000000000001',
        'LEGAL_ENTITY',
        '30000000-0000-0000-0000-000000000001',
        NULL, TRUE, TIMESTAMP '2020-01-01 00:00:00', NULL
      ),
      (
        'fd300000-0000-4000-8000-000000000002',
        'LEGAL_ENTITY',
        '30000000-0000-0000-0000-000000000002',
        NULL, TRUE, TIMESTAMP '2020-01-01 00:00:00', NULL
      );
    INSERT INTO attendance_source (
      attendance_source_id, legal_entity_id, source_code, source_type,
      display_name, status, row_version, created_by, created_at
    ) VALUES (
      'fd400000-0000-4000-8000-000000000001',
      '30000000-0000-0000-0000-000000000001',
      'V10-RETAINED-SOURCE', 'DELI_CLOUD',
      'V10 retained attendance source', 'ACTIVE', 0,
      '20000000-0000-0000-0000-000000000001',
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO attendance_source_config_revision (
      source_config_revision_id, attendance_source_id, revision_number,
      endpoint_kind, source_time_zone, page_size, rate_limit_per_minute,
      backoff_seconds, secret_reference_name, adapter_settings_json,
      effective_from, supersedes_config_revision_id, snapshot_digest,
      change_reason, created_by, created_at
    ) VALUES (
      'fd410000-0000-4000-8000-000000000001',
      'fd400000-0000-4000-8000-000000000001', 1,
      'DELI_PUNCH_PAGE', 'Asia/Shanghai', 100, 60, 1, NULL,
      JSON_OBJECT('fixture', 'v10-company-retention'),
      TIMESTAMP '2020-01-01 00:00:00', NULL, REPEAT('4', 64),
      'V10 company retention fixture',
      '20000000-0000-0000-0000-000000000001',
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO attendance_evidence_subject_lock (
      legal_entity_id, employee_id, touched_at
    ) VALUES (
      '30000000-0000-0000-0000-000000000001',
      'fd200000-0000-4000-8000-000000000001',
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO raw_attendance_fact (
      raw_attendance_fact_id, attendance_source_id, legal_entity_id,
      fact_kind, stable_fingerprint, source_time_text, source_time_zone,
      source_instant, canonical_payload_digest, request_id,
      received_at, created_by
    ) VALUES (
      'fd420000-0000-4000-8000-000000000001',
      'fd400000-0000-4000-8000-000000000001',
      '30000000-0000-0000-0000-000000000001',
      'PUNCH_POINT', REPEAT('5', 64), '2026-07-28 08:00:00',
      'Asia/Shanghai', TIMESTAMP '2026-07-28 00:00:00',
      REPEAT('6', 64), 'v10-company-retention',
      TIMESTAMP '2026-07-29 00:00:00',
      '20000000-0000-0000-0000-000000000001'
    );
    INSERT INTO attendance_report_projection (
      attendance_report_projection_id, legal_entity_id,
      period_start, period_end_exclusive, period_state,
      projection_version, formula_catalog_version,
      source_versions_json, source_snapshot_digest, projection_digest,
      status, data_as_of, published_at, created_by, created_at
    ) VALUES (
      'fd500000-0000-4000-8000-000000000001',
      '30000000-0000-0000-0000-000000000001',
      DATE '2026-07-01', DATE '2026-08-01', 'OPEN',
      'V10-RETAINED-PROJECTION', 'V10-RETAINED-FORMULA',
      JSON_OBJECT('fixture', 'v10-company-retention'),
      REPEAT('7', 64), REPEAT('8', 64), 'DRAFT',
      TIMESTAMP '2026-07-29 00:00:00', NULL,
      '20000000-0000-0000-0000-000000000001',
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO attendance_report_daily_fact (
      attendance_report_daily_fact_id, attendance_report_projection_id,
      legal_entity_id, employee_id, employee_version_id,
      employment_period_id, organization_id, organization_version_id,
      business_date, day_type, shift_label, scheduled_minutes,
      confirmed_scheduled_work_minutes, recognized_overtime_minutes,
      leave_or_time_off_minutes, absence_minutes, actual_work_minutes,
      late_minutes, penalized_late_minutes, early_departure_minutes,
      missing_punch_count, first_punch_at, last_punch_at,
      calculation_version_id, result_digest, created_at
    ) VALUES (
      'fd510000-0000-4000-8000-000000000001',
      'fd500000-0000-4000-8000-000000000001',
      '30000000-0000-0000-0000-000000000001',
      'fd200000-0000-4000-8000-000000000001',
      'fd210000-0000-4000-8000-000000000001',
      'fd220000-0000-4000-8000-000000000001',
      'fd100000-0000-4000-8000-000000000001',
      'fd110000-0000-4000-8000-000000000001',
      DATE '2026-07-28', 'WEEKDAY', 'V10 retained shift',
      480, 480, 0, 0, 0, 480, 0, 0, 0, 0, NULL, NULL,
      'V10-RETAINED-CALCULATION', REPEAT('9', 64),
      TIMESTAMP '2026-07-29 00:00:00'
    );
    INSERT INTO attendance_report_export_job (
      attendance_report_export_id, principal_id, report_type,
      period_start, legal_entity_id, organization_id, employee_id,
      filter_status, purpose, projection_version,
      authorization_digest, query_fingerprint, visible_content_digest,
      formula_version, export_fields_json, row_count,
      delivery_mode, status, expires_at, created_at
    ) VALUES (
      'fd520000-0000-4000-8000-000000000001',
      '20000000-0000-0000-0000-000000000001',
      'ATTENDANCE_DETAIL', DATE '2026-07-01',
      '30000000-0000-0000-0000-000000000001',
      'fd100000-0000-4000-8000-000000000001',
      'fd200000-0000-4000-8000-000000000001',
      NULL, 'V10 company retention export',
      'V10-RETAINED-PROJECTION',
      REPEAT('a', 64), REPEAT('b', 64), REPEAT('c', 64),
      'V10-RETAINED-FORMULA', JSON_ARRAY('employeeNumber'), 1,
      'ASYNC', 'QUEUED', TIMESTAMP '2026-08-29 00:00:00',
      TIMESTAMP '2026-07-29 00:00:00'
    );
  "
  fixture_counts="$(mysql_scalar "$defaults_file" "$database" "
    SELECT CONCAT_WS(
      '|',
      (SELECT COUNT(*) FROM legal_entity
       WHERE legal_entity_id IN (
         '30000000-0000-0000-0000-000000000001',
         '30000000-0000-0000-0000-000000000002'
       )),
      (SELECT COUNT(*) FROM employee
       WHERE employee_id IN (
         'fd200000-0000-4000-8000-000000000001',
         'fd200000-0000-4000-8000-000000000002'
       )),
      (SELECT COUNT(*) FROM organization_identity
       WHERE organization_id =
         'fd100000-0000-4000-8000-000000000001'),
      (SELECT COUNT(*) FROM auth_data_scope
       WHERE scope_id IN (
         'fd300000-0000-4000-8000-000000000001',
         'fd300000-0000-4000-8000-000000000002'
       )),
      (SELECT COUNT(*) FROM attendance_policy_scope),
      (SELECT COUNT(*) FROM attendance_source
       WHERE attendance_source_id =
         'fd400000-0000-4000-8000-000000000001'),
      (SELECT COUNT(*) FROM attendance_source_config_revision
       WHERE source_config_revision_id =
         'fd410000-0000-4000-8000-000000000001'),
      (SELECT COUNT(*) FROM attendance_evidence_subject_lock
       WHERE employee_id =
         'fd200000-0000-4000-8000-000000000001'),
      (SELECT COUNT(*) FROM raw_attendance_fact
       WHERE raw_attendance_fact_id =
         'fd420000-0000-4000-8000-000000000001'),
      (SELECT COUNT(*) FROM attendance_report_projection
       WHERE attendance_report_projection_id =
         'fd500000-0000-4000-8000-000000000001'),
      (SELECT COUNT(*) FROM attendance_report_daily_fact
       WHERE attendance_report_daily_fact_id =
         'fd510000-0000-4000-8000-000000000001'),
      (SELECT COUNT(*) FROM attendance_report_export_job
       WHERE attendance_report_export_id =
         'fd520000-0000-4000-8000-000000000001')
    );
  ")"
  [[ "$fixture_counts" == "2|2|1|2|3|1|1|1|1|1|1|1" ]] \
    || fail "The populated V10 company retention fixture is incomplete."
  log "W3_COMPANY_V10_POPULATED_FIXTURE=PASS database=${database} companies=2 domains=company,employee,organization,scope,configuration,source,evidence,projection,export nonempty_boundary_tables=11 db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

upgrade_development_company_dimension() {
  local defaults_file
  local history_table_count
  local failed_history_count
  local checkpoint
  defaults_file="$(migrator_defaults_file)"
  history_table_count="$(mysql_scalar "$defaults_file" "" "
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = '${DEV_DATABASE}'
      AND TABLE_NAME = 'flyway_schema_history'
      AND TABLE_TYPE = 'BASE TABLE';
  ")"
  if [[ "$history_table_count" == "0" ]]; then
    checkpoint="0"
  else
    failed_history_count="$(mysql_scalar "$defaults_file" "$DEV_DATABASE" "
      SELECT COUNT(*)
      FROM flyway_schema_history
      WHERE success = 0;
    ")"
    [[ "$failed_history_count" == "0" ]] \
      || fail "Development Flyway history contains a failed migration."
    checkpoint="$(mysql_scalar "$defaults_file" "$DEV_DATABASE" "
      SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
      FROM flyway_schema_history
      WHERE type = 'SQL' AND success = 1;
    ")"
  fi
  [[ "$checkpoint" =~ ^[0-9]+$ ]] \
    || fail "Development Flyway checkpoint is not numeric."
  ((10#$checkpoint <= 11)) \
    || fail "Development schema is newer than the supported company migration."

  if ((10#$checkpoint <= 6)); then
    run_flyway "$DEV_DATABASE" "-target=6" migrate
    bootstrap_fixed_legal_entity_at_v6 "$DEV_DATABASE"
    checkpoint="6"
  fi
  if ((10#$checkpoint >= 7 && 10#$checkpoint <= 9)); then
    verify_fixed_legal_entity_before_v11 \
      "$DEV_DATABASE" "$defaults_file"
  fi
  if ((10#$checkpoint <= 9)); then
    run_flyway "$DEV_DATABASE" "-target=10" migrate
    checkpoint="10"
  fi
  if [[ "$checkpoint" == "10" ]]; then
    company_dimension_preflight_v10 \
      "$defaults_file" "$DEV_DATABASE" \
      || fail "Development V10 company preflight failed."
    run_flyway "$DEV_DATABASE" "-target=11" migrate
    checkpoint="11"
  fi
  [[ "$checkpoint" == "11" ]] \
    || fail "Development company migration did not reach exact V11."
  run_flyway "$DEV_DATABASE" validate
  verify_company_dimension_latest "$defaults_file" "$DEV_DATABASE"
  log "W3_DEV_FORWARD_MIGRATION=PASS database=${DEV_DATABASE} starting_checkpoint=adaptive latest=11 repeat_safe=true db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

capture_registry_fingerprint_snapshot() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local raw_snapshot="$4"
  local semantic_snapshot="$5"
  case "$schema_mode" in
    target7 | latest) ;;
    *) fail "Unknown W3 registry fingerprint schema mode." ;;
  esac
  mysql_query "$defaults_file" "" "
    SELECT stable_row
    FROM (
      SELECT CONCAT_WS(
        '|', 'COLUMN', TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION,
        COLUMN_TYPE, IS_NULLABLE, COALESCE(COLUMN_DEFAULT, '<NULL>'),
        EXTRA, COALESCE(GENERATION_EXPRESSION, '<NULL>')) AS stable_row
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = '${database}'
        AND TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
      UNION ALL
      SELECT CONCAT_WS(
        '|',
        CASE
          WHEN EXISTS (
            SELECT 1
            FROM information_schema.TABLE_CONSTRAINTS constraint_row
            WHERE constraint_row.CONSTRAINT_SCHEMA = index_row.TABLE_SCHEMA
              AND constraint_row.TABLE_NAME = index_row.TABLE_NAME
              AND constraint_row.CONSTRAINT_NAME = index_row.INDEX_NAME
              AND constraint_row.CONSTRAINT_TYPE = 'FOREIGN KEY'
          )
          THEN 'INDEX_IMPLICIT_FK'
          ELSE 'INDEX_EXPLICIT'
        END,
        index_row.TABLE_NAME, index_row.INDEX_NAME,
        index_row.NON_UNIQUE, index_row.SEQ_IN_INDEX,
        index_row.COLUMN_NAME, index_row.COLLATION,
        COALESCE(index_row.SUB_PART, '<NULL>')) AS stable_row
      FROM information_schema.STATISTICS index_row
      WHERE index_row.TABLE_SCHEMA = '${database}'
        AND index_row.TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'FK', rc.TABLE_NAME, rc.CONSTRAINT_NAME,
        kcu.COLUMN_NAME, kcu.ORDINAL_POSITION,
        kcu.REFERENCED_TABLE_NAME, kcu.REFERENCED_COLUMN_NAME,
        rc.UPDATE_RULE, rc.DELETE_RULE) AS stable_row
      FROM information_schema.REFERENTIAL_CONSTRAINTS rc
      JOIN information_schema.KEY_COLUMN_USAGE kcu
        ON kcu.CONSTRAINT_SCHEMA = rc.CONSTRAINT_SCHEMA
       AND kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
       AND kcu.TABLE_NAME = rc.TABLE_NAME
      WHERE rc.CONSTRAINT_SCHEMA = '${database}'
        AND rc.TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
      UNION ALL
      SELECT CONCAT_WS(
        '|', 'CHECK', tc.TABLE_NAME, tc.CONSTRAINT_NAME,
        tc.ENFORCED, cc.CHECK_CLAUSE) AS stable_row
      FROM information_schema.TABLE_CONSTRAINTS tc
      JOIN information_schema.CHECK_CONSTRAINTS cc
        ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
       AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
      WHERE tc.CONSTRAINT_SCHEMA = '${database}'
        AND tc.TABLE_NAME REGEXP '${W3_TABLE_NAME_PATTERN}'
        AND tc.CONSTRAINT_TYPE = 'CHECK'
    ) rows_to_hash
    ORDER BY stable_row;
  " >"$raw_snapshot"
  python3 - "$raw_snapshot" "$semantic_snapshot" "$schema_mode" <<'PY'
import sys
from pathlib import Path

raw_path = Path(sys.argv[1])
semantic_path = Path(sys.argv[2])
schema_mode = sys.argv[3]
if schema_mode not in {"target7", "latest"}:
    raise SystemExit("invalid registry fingerprint schema mode")

semantic_rows = []
explicit_indexes = set()
implicit_fk_indexes = set()
for row in raw_path.read_text(encoding="utf-8").splitlines():
    fields = row.split("|")
    if not fields or not fields[0]:
        raise SystemExit("invalid registry fingerprint row")
    if fields[0] in {"INDEX_EXPLICIT", "INDEX_IMPLICIT_FK"}:
        if len(fields) != 8:
            raise SystemExit("invalid registry index fingerprint row")
        index_key = (fields[1], fields[2])
        if fields[0] == "INDEX_IMPLICIT_FK":
            implicit_fk_indexes.add(index_key)
            continue
        explicit_indexes.add(index_key)
        fields[0] = "INDEX"
        row = "|".join(fields)
    row = row.replace("legal_entity_id", "company_id")
    row = row.replace("legal_entity", "company")
    semantic_rows.append(row)

if len(explicit_indexes) != 84:
    raise SystemExit(
        "registry fingerprint requires exactly 84 review-owned explicit indexes"
    )
if len(implicit_fk_indexes) != 29:
    raise SystemExit(
        "registry fingerprint requires exactly 29 classified implicit FK indexes"
    )
semantic_path.write_text(
    "\n".join(sorted(semantic_rows)) + "\n",
    encoding="utf-8",
)
PY
}

classify_registry_fingerprint_delta() {
  local target7_raw="$1"
  local latest_raw="$2"
  local target7_semantic="$3"
  local latest_semantic="$4"
  python3 - \
      "$target7_raw" "$latest_raw" \
      "$target7_semantic" "$latest_semantic" <<'PY'
import sys
from pathlib import Path

def normalized_rows(path):
    rows = set()
    for row in Path(path).read_text(encoding="utf-8").splitlines():
        row = row.replace("legal_entity_id", "company_id")
        row = row.replace("legal_entity", "company")
        rows.add(row)
    return rows

def index_inventory(rows, row_kind):
    return {
        tuple(row.split("|")[1:3])
        for row in rows
        if row.startswith(row_kind + "|")
    }

def classify_delta(before, after):
    delta = before.symmetric_difference(after)
    unexpected = sorted(
        row for row in delta
        if not row.startswith("INDEX_IMPLICIT_FK|")
    )
    if unexpected:
        raise ValueError(
            "non-implicit-index registry metadata changed: "
            + unexpected[0]
        )
    return delta

target7_raw = normalized_rows(sys.argv[1])
latest_raw = normalized_rows(sys.argv[2])
target7_semantic = Path(sys.argv[3]).read_bytes()
latest_semantic = Path(sys.argv[4]).read_bytes()

delta = classify_delta(target7_raw, latest_raw)
if target7_semantic != latest_semantic:
    raise SystemExit("semantic registry fingerprints differ")
for label, rows in (("target7", target7_raw), ("latest", latest_raw)):
    explicit = index_inventory(rows, "INDEX_EXPLICIT")
    implicit = index_inventory(rows, "INDEX_IMPLICIT_FK")
    if len(explicit) != 84 or len(implicit) != 29:
        raise SystemExit(
            f"{label} index classification differs: "
            f"explicit={len(explicit)} implicit={len(implicit)}"
        )

# Mutation-style negative contract: a non-index metadata delta must fail closed.
try:
    classify_delta({"COLUMN|sample|company_id"}, {"COLUMN|sample|other_id"})
except ValueError:
    negative_result = "rejected"
else:
    raise SystemExit("non-index registry delta negative probe was accepted")

delta_class = "implicit_fk_index_only" if delta else "none"
print(
    f"raw_delta_class={delta_class} raw_delta_rows={len(delta)} "
    "target7_explicit_indexes=84 target7_implicit_fk_indexes=29 "
    "latest_explicit_indexes=84 latest_implicit_fk_indexes=29 "
    f"negative_non_index_delta={negative_result}"
)
PY
}

registry_fingerprint() {
  local defaults_file="$1"
  local database="$2"
  local schema_mode="$3"
  local raw_snapshot
  local semantic_snapshot
  raw_snapshot="$(make_private_temporary_file)"
  semantic_snapshot="$(make_private_temporary_file)"
  capture_registry_fingerprint_snapshot \
    "$defaults_file" "$database" "$schema_mode" \
    "$raw_snapshot" "$semantic_snapshot"
  hash_file "$semantic_snapshot"
}

verify_contract_for_database() {
  local database="$1"
  local defaults_file
  assert_exact_database "$database"
  defaults_file="$(migrator_defaults_file)"
  assert_schema_exists "$database" "$defaults_file"
  verify_migration_history "$defaults_file" "$database"
  verify_company_dimension_latest "$defaults_file" "$database"
  verify_registry_database_contract "$defaults_file" "$database" latest
  verify_seed_oracle "$defaults_file" "$database" latest
  verify_constraints_and_capabilities \
    "$defaults_file" "$database" latest
  verify_app_privileges "$database"
  log "W3_SCHEMA_CONTRACT=PASS database=${database} registry_tables=22 flyway_metadata=excluded db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

verify_contract() {
  assert_v1_v6_checksums
  verify_contract_for_database "$DEV_DATABASE"
  verify_contract_for_database "$TEST_DATABASE"
  verify_chosen_index
  verify_current_lock
  log "WAVE3_CONTRACT_AND_APP_ACCESS=PASS"
}

upgrade_v6_target7_latest_test() {
  local defaults_file
  local checkpoint
  local target7_fingerprint
  local latest_fingerprint
  local repeat_fingerprint
  local target7_registry_raw
  local target7_registry_semantic
  local latest_registry_raw
  local latest_registry_semantic
  local repeat_registry_raw
  local repeat_registry_semantic
  local registry_delta_classification
  local v10_company_snapshot
  local latest_company_snapshot
  local repeat_company_snapshot
  local v10_company_digest
  local latest_company_digest
  local repeat_company_digest
  local history_before_repeat
  local history_after_repeat
  reset_test_tables
  assert_v1_v6_checksums
  run_flyway "$TEST_DATABASE" "-target=6" migrate
  defaults_file="$(migrator_defaults_file)"
  checkpoint="$(mysql_scalar "$defaults_file" "$TEST_DATABASE" "
    SELECT MAX(CAST(version AS UNSIGNED))
    FROM flyway_schema_history WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$checkpoint" == "6" ]] || fail "Upgrade checkpoint did not stop at V6."
  bootstrap_fixed_legal_entity_at_v6 "$TEST_DATABASE"
  capture_semantic_phase "v6-before" "$defaults_file"

  run_flyway "$TEST_DATABASE" "-target=7" migrate
  checkpoint="$(mysql_scalar "$defaults_file" "$TEST_DATABASE" "
    SELECT MAX(CAST(version AS UNSIGNED))
    FROM flyway_schema_history WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$checkpoint" == "7" ]] || fail "Target migration did not stop at V7."
  verify_registry_database_contract \
    "$defaults_file" "$TEST_DATABASE" target7
  verify_seed_oracle "$defaults_file" "$TEST_DATABASE" target7
  verify_target7_seed_exactness "$defaults_file" "$TEST_DATABASE"
  capture_semantic_phase "target7" "$defaults_file"
  target7_registry_raw="$(make_private_temporary_file)"
  target7_registry_semantic="$(make_private_temporary_file)"
  capture_registry_fingerprint_snapshot \
    "$defaults_file" "$TEST_DATABASE" target7 \
    "$target7_registry_raw" "$target7_registry_semantic"
  target7_fingerprint="$(hash_file "$target7_registry_semantic")"
  log "W3_TARGET7_SNAPSHOT_BEFORE_CHILD_LATEST=PASS database=${TEST_DATABASE} registry_sha256=${target7_fingerprint} child_latest_invoked=false db_identity=${W3_RUNTIME_DB_IDENTITY}"

  run_flyway "$TEST_DATABASE" "-target=10" migrate
  checkpoint="$(mysql_scalar "$defaults_file" "$TEST_DATABASE" "
    SELECT MAX(CAST(version AS UNSIGNED))
    FROM flyway_schema_history WHERE type = 'SQL' AND success = 1;
  ")"
  [[ "$checkpoint" == "10" ]] \
    || fail "Company upgrade checkpoint did not stop at exact V10."
  bootstrap_populated_company_fixture_at_v10 \
    "$TEST_DATABASE" "$defaults_file"
  company_dimension_preflight_v10 "$defaults_file" "$TEST_DATABASE" \
    || fail "Company V11 preflight rejected the valid populated V10 fixture."
  verify_company_preflight_negative_probes \
    "$defaults_file" "$TEST_DATABASE"
  v10_company_snapshot="$(make_private_temporary_file)"
  capture_company_boundary_snapshot \
    "$defaults_file" "$TEST_DATABASE" v10 "$v10_company_snapshot"
  v10_company_digest="$(hash_file "$v10_company_snapshot")"
  log "W3_COMPANY_V10_SNAPSHOT=PASS database=${TEST_DATABASE} tables=29 rows_ids_relationships_sha256=${v10_company_digest} db_identity=${W3_RUNTIME_DB_IDENTITY}"

  run_flyway "$TEST_DATABASE" "-target=11" migrate
  run_flyway "$TEST_DATABASE" validate
  latest_company_snapshot="$(make_private_temporary_file)"
  capture_company_boundary_snapshot \
    "$defaults_file" "$TEST_DATABASE" latest "$latest_company_snapshot"
  latest_company_digest="$(hash_file "$latest_company_snapshot")"
  cmp -s "$v10_company_snapshot" "$latest_company_snapshot" \
    || fail "V11 changed a company-bound row count or stable ID and company relationship digest."
  verify_migration_history "$defaults_file" "$TEST_DATABASE"
  verify_company_dimension_latest "$defaults_file" "$TEST_DATABASE"
  verify_registry_database_contract \
    "$defaults_file" "$TEST_DATABASE" latest
  verify_seed_oracle "$defaults_file" "$TEST_DATABASE" latest
  verify_constraints_and_capabilities \
    "$defaults_file" "$TEST_DATABASE" latest
  latest_registry_raw="$(make_private_temporary_file)"
  latest_registry_semantic="$(make_private_temporary_file)"
  capture_registry_fingerprint_snapshot \
    "$defaults_file" "$TEST_DATABASE" latest \
    "$latest_registry_raw" "$latest_registry_semantic"
  latest_fingerprint="$(hash_file "$latest_registry_semantic")"
  registry_delta_classification="$(
    classify_registry_fingerprint_delta \
      "$target7_registry_raw" "$latest_registry_raw" \
      "$target7_registry_semantic" "$latest_registry_semantic"
  )"
  [[ "$target7_fingerprint" == "$latest_fingerprint" ]] \
    || fail "Latest migration changed the fixed W3 registry contract."
  log "W3_REGISTRY_FINGERPRINT_DELTA=PASS ${registry_delta_classification} semantic_sha256=${latest_fingerprint} db_identity=${W3_RUNTIME_DB_IDENTITY}"
  capture_semantic_phase "latest-after" "$defaults_file"
  log "W3_COMPANY_V10_TO_V11=PASS database=${TEST_DATABASE} tables=29 rows_ids_relationships_sha256=${latest_company_digest} stable_ids=preserved relationships=preserved scope=COMPANY db_identity=${W3_RUNTIME_DB_IDENTITY}"
  log "W3_TARGET7_TO_LATEST=PASS database=${TEST_DATABASE} registry_sha256=${latest_fingerprint} post_v7=allowed db_identity=${W3_RUNTIME_DB_IDENTITY}"

  history_before_repeat="$(mysql_scalar "$defaults_file" "$TEST_DATABASE" "
    SELECT COUNT(*) FROM flyway_schema_history;
  ")"
  run_flyway "$TEST_DATABASE" migrate
  history_after_repeat="$(mysql_scalar "$defaults_file" "$TEST_DATABASE" "
    SELECT COUNT(*) FROM flyway_schema_history;
  ")"
  repeat_registry_raw="$(make_private_temporary_file)"
  repeat_registry_semantic="$(make_private_temporary_file)"
  capture_registry_fingerprint_snapshot \
    "$defaults_file" "$TEST_DATABASE" latest \
    "$repeat_registry_raw" "$repeat_registry_semantic"
  repeat_fingerprint="$(hash_file "$repeat_registry_semantic")"
  repeat_company_snapshot="$(make_private_temporary_file)"
  capture_company_boundary_snapshot \
    "$defaults_file" "$TEST_DATABASE" latest "$repeat_company_snapshot"
  repeat_company_digest="$(hash_file "$repeat_company_snapshot")"
  [[ "$history_before_repeat" == "$history_after_repeat" ]] \
    || fail "Repeat migrate changed Flyway history."
  [[ "$latest_fingerprint" == "$repeat_fingerprint" ]] \
    || fail "Repeat migrate changed the fixed W3 registry contract."
  cmp -s "$latest_company_snapshot" "$repeat_company_snapshot" \
    || fail "Repeat migrate changed a company-bound row count, stable ID, or company relationship."
  capture_semantic_phase "repeat-after" "$defaults_file"
  log "W3_V6_TARGET7_LATEST=PASS database=${TEST_DATABASE} repeat_noop=PASS registry_sha256=${repeat_fingerprint} company_rows_ids_relationships_sha256=${repeat_company_digest} db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

empty_to_latest_test() {
  local defaults_file
  local v10_company_snapshot
  local latest_company_snapshot
  reset_test_tables
  run_flyway "$TEST_DATABASE" "-target=6" migrate
  bootstrap_fixed_legal_entity_at_v6 "$TEST_DATABASE"
  run_flyway "$TEST_DATABASE" "-target=10" migrate
  defaults_file="$(migrator_defaults_file)"
  company_dimension_preflight_v10 "$defaults_file" "$TEST_DATABASE" \
    || fail "Empty-path V10 company preflight failed."
  v10_company_snapshot="$(make_private_temporary_file)"
  capture_company_boundary_snapshot \
    "$defaults_file" "$TEST_DATABASE" v10 "$v10_company_snapshot"
  run_flyway "$TEST_DATABASE" "-target=11" migrate
  run_flyway "$TEST_DATABASE" validate
  latest_company_snapshot="$(make_private_temporary_file)"
  capture_company_boundary_snapshot \
    "$defaults_file" "$TEST_DATABASE" latest "$latest_company_snapshot"
  cmp -s "$v10_company_snapshot" "$latest_company_snapshot" \
    || fail "Empty-path V11 changed company-bound rows, stable IDs, or company relationships."
  verify_migration_history "$defaults_file" "$TEST_DATABASE"
  verify_company_dimension_latest "$defaults_file" "$TEST_DATABASE"
  verify_registry_database_contract \
    "$defaults_file" "$TEST_DATABASE" latest
  verify_seed_oracle "$defaults_file" "$TEST_DATABASE" latest
  capture_semantic_phase "empty-latest" "$defaults_file"
  log "W3_EMPTY_TO_LATEST=PASS database=${TEST_DATABASE} registry_tables=22 post_v7=allowed db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

run_all() {
  log "WAVE3_RUN_CONTEXT=START run_id=${RUN_ID}"
  require_test_rebuild_confirmation
  load_runtime
  upgrade_v6_target7_latest_test
  empty_to_latest_test
  upgrade_development_company_dimension
  verify_contract
  log "WAVE3_LOCAL_MYSQL_VERIFICATION=PASS run_id=${RUN_ID} version=${W3_MYSQL_VERSION} port=${W3_MYSQL_PORT} registry_tables=22 flyway_metadata=excluded db_identity=${W3_RUNTIME_DB_IDENTITY}"
}

dispatch() {
  case "$COMMAND" in
    plan)
      print_plan
      ;;
    verify-static)
      verify_static_contract
      log "WAVE3_RUN_CONTEXT=PASS run_id=${RUN_ID:-not-supplied}"
      ;;
    upgrade-v6-test)
      log "WAVE3_RUN_CONTEXT=START run_id=${RUN_ID}"
      require_test_rebuild_confirmation
      load_runtime
      upgrade_v6_target7_latest_test
      log "WAVE3_RUN_CONTEXT=PASS run_id=${RUN_ID}"
      ;;
    verify-contract)
      log "WAVE3_RUN_CONTEXT=START run_id=${RUN_ID}"
      load_runtime
      verify_contract
      log "WAVE3_RUN_CONTEXT=PASS run_id=${RUN_ID}"
      ;;
    all)
      run_all
      ;;
  esac
}

parse_arguments "$@"
require_execute
validate_semantic_evidence_directory
dispatch
