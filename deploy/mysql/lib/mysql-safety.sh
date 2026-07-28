#!/usr/bin/env bash

if [[ -n "${SHENZHOUHR_MYSQL_SAFETY_LOADED:-}" ]]; then
  return 0
fi
SHENZHOUHR_MYSQL_SAFETY_LOADED=1

readonly MYSQL_SAFETY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly MYSQL_DEPLOY_DIR="$(cd "${MYSQL_SAFETY_DIR}/.." && pwd -P)"
readonly PROJECT_ROOT="$(cd "${MYSQL_DEPLOY_DIR}/../.." && pwd -P)"
readonly MIGRATION_DIR="${PROJECT_ROOT}/backend/src/main/resources/db/migration"
readonly DEV_DATABASE="shenzhou_hr_dev"
readonly TEST_DATABASE="shenzhou_hr_test"
readonly ROOT_ACCOUNT="root"
readonly MIGRATOR_ACCOUNT="shenzhou_hr_local_migrator"
readonly DEV_APP_ACCOUNT="shenzhou_hr_dev_app"
readonly TEST_APP_ACCOUNT="shenzhou_hr_test_app"
readonly ACCOUNT_HOST="localhost"
readonly REQUIRED_COLLATION="utf8mb4_0900_ai_ci"
readonly MYSQL_SAFETY_TEMP_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/shenzhouhr-mysql.XXXXXX")"

chmod 700 "$MYSQL_SAFETY_TEMP_DIRECTORY"

log() {
  printf '[shenzhouhr-mysql] %s\n' "$*"
}

fail() {
  printf '[shenzhouhr-mysql] ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup_mysql_safety() {
  local file
  for file in "$MYSQL_SAFETY_TEMP_DIRECTORY"/*; do
    if [[ -f "$file" && ! -L "$file" ]]; then
      rm -f -- "$file"
    fi
  done
  rmdir "$MYSQL_SAFETY_TEMP_DIRECTORY" 2>/dev/null || true
}

trap cleanup_mysql_safety EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

make_private_temporary_file() {
  local file
  file="$(mktemp "${MYSQL_SAFETY_TEMP_DIRECTORY}/private.XXXXXX")"
  chmod 600 "$file"
  printf '%s' "$file"
}

portable_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
  fi
}

portable_owner_uid() {
  if stat -f '%u' "$1" >/dev/null 2>&1; then
    stat -f '%u' "$1"
  else
    stat -c '%u' "$1"
  fi
}

canonical_file_path() {
  local path="$1"
  local directory
  local basename_value
  directory="$(cd "$(dirname "$path")" 2>/dev/null && pwd -P)" \
    || fail "Cannot resolve environment-file directory."
  basename_value="$(basename "$path")"
  printf '%s/%s' "$directory" "$basename_value"
}

is_allowed_environment_key() {
  case "$1" in
    SHENZHOUHR_MYSQL_HOST | \
    SHENZHOUHR_MYSQL_PORT | \
    SHENZHOUHR_MYSQL_CLIENT_BIN | \
    SHENZHOUHR_FLYWAY_BIN | \
    SHENZHOUHR_TEST_FINAL_STATE | \
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

load_private_environment_file() {
  local requested_path="$1"
  local resolved_path
  local mode
  local owner_uid
  local line
  local key
  local value
  local line_number=0

  [[ -n "$requested_path" ]] || fail "--env-file is required for this command."
  [[ "$requested_path" = /* ]] || fail "The environment-file path must be absolute."
  [[ -f "$requested_path" ]] || fail "The environment file does not exist."
  [[ ! -L "$requested_path" ]] || fail "The environment file must not be a symbolic link."

  resolved_path="$(canonical_file_path "$requested_path")"
  case "$resolved_path" in
    "$PROJECT_ROOT" | "$PROJECT_ROOT"/*)
      fail "The environment file must be outside the repository."
      ;;
  esac

  mode="$(portable_mode "$resolved_path")"
  [[ "$mode" == "600" ]] || fail "The environment file mode must be exactly 0600."
  owner_uid="$(portable_owner_uid "$resolved_path")"
  [[ "$owner_uid" == "$(id -u)" ]] || fail "The environment file must be owned by the current user."

  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    line="${line%$'\r'}"
    [[ -z "$line" ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" == *=* ]] || fail "Invalid environment entry at line ${line_number}."
    key="${line%%=*}"
    value="${line#*=}"
    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] \
      || fail "Invalid environment variable name at line ${line_number}."
    is_allowed_environment_key "$key" \
      || fail "Unsupported environment variable name: ${key}."
    printf -v "$key" '%s' "$value"
  done < "$resolved_path"

  apply_environment_defaults
  validate_nonsecret_environment
  report_environment_variable_status
}

apply_environment_defaults() {
  : "${SHENZHOUHR_MYSQL_HOST:=localhost}"
  : "${SHENZHOUHR_MYSQL_PORT:=3306}"
  : "${SHENZHOUHR_MYSQL_CLIENT_BIN:=mysql}"
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
  case "$SHENZHOUHR_MYSQL_HOST" in
    localhost | 127.0.0.1) ;;
    *) fail "MySQL host must be exactly localhost or 127.0.0.1." ;;
  esac
  [[ "$SHENZHOUHR_MYSQL_PORT" == "3306" ]] \
    || fail "MySQL port must be exactly 3306."
  case "$SHENZHOUHR_TEST_FINAL_STATE" in
    PRESERVED | EMPTY | DROPPED) ;;
    *) fail "SHENZHOUHR_TEST_FINAL_STATE must be PRESERVED, EMPTY, or DROPPED." ;;
  esac
  [[ "$SHENZHOUHR_MYSQL_CLIENT_BIN" != *[[:space:]]* ]] \
    || fail "MySQL client executable must be a single path without whitespace."
  [[ "$SHENZHOUHR_FLYWAY_BIN" != *[[:space:]]* ]] \
    || fail "Flyway executable must be a single path without whitespace."
}

report_environment_variable_status() {
  local key
  for key in \
    SHENZHOUHR_MYSQL_HOST \
    SHENZHOUHR_MYSQL_PORT \
    SHENZHOUHR_MYSQL_CLIENT_BIN \
    SHENZHOUHR_FLYWAY_BIN \
    SHENZHOUHR_TEST_FINAL_STATE \
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

require_secret_variable() {
  local variable_name="$1"
  [[ -n "${!variable_name:-}" ]] || fail "${variable_name} is missing."
  case "${!variable_name}" in
    *$'\n'* | *$'\r'*) fail "${variable_name} must be a single-line value." ;;
  esac
}

require_mysql_client() {
  command -v "$SHENZHOUHR_MYSQL_CLIENT_BIN" >/dev/null 2>&1 \
    || fail "MySQL client executable is unavailable."
}

require_flyway_client() {
  command -v "$SHENZHOUHR_FLYWAY_BIN" >/dev/null 2>&1 \
    || fail "Flyway CLI executable is unavailable."
  [[ -d "$MIGRATION_DIR" ]] || fail "Flyway migration directory is unavailable."
}

mysql_option_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\t'/\\t}"
  printf '%s' "$value"
}

properties_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value// /\\ }"
  value="${value//$'\t'/\\t}"
  value="${value//\#/\\#}"
  value="${value//\!/\\!}"
  printf '%s' "$value"
}

create_mysql_defaults_file() {
  local user="$1"
  local password="$2"
  local file
  file="$(make_private_temporary_file)"
  {
    printf '[client]\n'
    printf 'protocol=TCP\n'
    printf 'host="%s"\n' "$(mysql_option_escape "$SHENZHOUHR_MYSQL_HOST")"
    printf 'port=%s\n' "$SHENZHOUHR_MYSQL_PORT"
    printf 'user="%s"\n' "$(mysql_option_escape "$user")"
    printf 'password="%s"\n' "$(mysql_option_escape "$password")"
    printf 'default-character-set=utf8mb4\n'
  } > "$file"
  printf '%s' "$file"
}

mysql_query() {
  local defaults_file="$1"
  local database="$2"
  local sql="$3"
  local error_file
  local arguments=()
  error_file="$(make_private_temporary_file)"
  arguments+=("--defaults-extra-file=${defaults_file}")
  arguments+=("--batch" "--raw" "--skip-column-names")
  if [[ -n "$database" ]]; then
    assert_exact_database "$database"
    arguments+=("--database=${database}")
  fi
  if ! printf '%s\n' "$sql" \
      | "$SHENZHOUHR_MYSQL_CLIENT_BIN" "${arguments[@]}" 2>"$error_file"; then
    fail "MySQL query failed; diagnostic text was suppressed to protect credentials."
  fi
}

mysql_query_with_headers() {
  local defaults_file="$1"
  local database="$2"
  local sql="$3"
  local error_file
  local arguments=()
  error_file="$(make_private_temporary_file)"
  arguments+=("--defaults-extra-file=${defaults_file}")
  arguments+=("--batch" "--raw")
  if [[ -n "$database" ]]; then
    assert_exact_database "$database"
    arguments+=("--database=${database}")
  fi
  if ! printf '%s\n' "$sql" \
      | "$SHENZHOUHR_MYSQL_CLIENT_BIN" "${arguments[@]}" 2>"$error_file"; then
    fail "MySQL query failed; diagnostic text was suppressed to protect credentials."
  fi
}

mysql_execute_quietly() {
  local defaults_file="$1"
  local database="$2"
  local sql="$3"
  local error_file
  local arguments=()
  error_file="$(make_private_temporary_file)"
  arguments+=("--defaults-extra-file=${defaults_file}")
  arguments+=("--batch" "--raw" "--silent")
  if [[ -n "$database" ]]; then
    assert_exact_database "$database"
    arguments+=("--database=${database}")
  fi
  if ! printf '%s\n' "$sql" \
      | "$SHENZHOUHR_MYSQL_CLIENT_BIN" "${arguments[@]}" \
        >/dev/null 2>"$error_file"; then
    fail "MySQL operation failed; diagnostic text was suppressed to protect credentials."
  fi
}

mysql_scalar() {
  local defaults_file="$1"
  local database="$2"
  local sql="$3"
  mysql_query "$defaults_file" "$database" "$sql" | tail -n 1
}

expect_mysql_failure() {
  local defaults_file="$1"
  local database="$2"
  local sql="$3"
  local label="$4"
  local error_file
  local arguments=()
  error_file="$(make_private_temporary_file)"
  arguments+=("--defaults-extra-file=${defaults_file}")
  arguments+=("--batch" "--raw" "--silent")
  if [[ -n "$database" ]]; then
    assert_exact_database "$database"
    arguments+=("--database=${database}")
  fi
  if printf '%s\n' "$sql" \
      | "$SHENZHOUHR_MYSQL_CLIENT_BIN" "${arguments[@]}" \
        >/dev/null 2>"$error_file"; then
    fail "${label} unexpectedly succeeded."
  fi
  log "${label}=DENIED_AS_EXPECTED"
}

assert_exact_database() {
  case "$1" in
    "$DEV_DATABASE" | "$TEST_DATABASE") ;;
    *) fail "Database target is outside the authorized exact-name allowlist." ;;
  esac
}

sql_string_literal() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\'/\\\'}"
  printf "'%s'" "$value"
}

create_flyway_configuration() {
  local database="$1"
  local file
  local jdbc_url
  assert_exact_database "$database"
  require_secret_variable SHENZHOUHR_FLYWAY_PASSWORD
  file="$(make_private_temporary_file)"
  jdbc_url="jdbc:mysql://${SHENZHOUHR_MYSQL_HOST}:${SHENZHOUHR_MYSQL_PORT}/${database}?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
  {
    printf 'flyway.url=%s\n' "$(properties_escape "$jdbc_url")"
    printf 'flyway.user=%s\n' "$(properties_escape "$MIGRATOR_ACCOUNT")"
    printf 'flyway.password=%s\n' "$(properties_escape "$SHENZHOUHR_FLYWAY_PASSWORD")"
    printf 'flyway.locations=filesystem:%s\n' "$(properties_escape "$MIGRATION_DIR")"
    printf 'flyway.defaultSchema=%s\n' "$database"
    printf 'flyway.schemas=%s\n' "$database"
    printf 'flyway.createSchemas=false\n'
    printf 'flyway.baselineOnMigrate=false\n'
    printf 'flyway.cleanDisabled=true\n'
    printf 'flyway.validateMigrationNaming=true\n'
  } > "$file"
  printf '%s' "$file"
}

run_flyway() {
  local database="$1"
  shift
  local config_file
  local error_file
  config_file="$(create_flyway_configuration "$database")"
  error_file="$(make_private_temporary_file)"
  if ! "$SHENZHOUHR_FLYWAY_BIN" "-configFiles=${config_file}" "$@" 2>"$error_file"; then
    fail "Flyway operation failed; diagnostic text was suppressed to protect credentials."
  fi
}

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    fail "A SHA-256 utility is required."
  fi
}

destructive_database_preflight() {
  local root_defaults="$1"
  local target="$2"
  local actual_port
  local version
  local current_database
  local server_hostname

  assert_exact_database "$target"
  validate_nonsecret_environment

  actual_port="$(mysql_scalar "$root_defaults" "" "SELECT @@port;")"
  version="$(mysql_scalar "$root_defaults" "" "SELECT VERSION();")"
  current_database="$(mysql_scalar "$root_defaults" "" "SELECT COALESCE(DATABASE(), '<none>');")"
  server_hostname="$(mysql_scalar "$root_defaults" "" "SELECT @@hostname;")"
  [[ "$actual_port" == "3306" ]] || fail "Connected server port is not 3306."
  [[ "$current_database" == "<none>" ]] \
    || fail "Destructive preflight requires a connection with no selected database."

  log "DESTRUCTIVE_PREFLIGHT_CONNECTION_HOST=${SHENZHOUHR_MYSQL_HOST}"
  log "DESTRUCTIVE_PREFLIGHT_SERVER_HOSTNAME=${server_hostname}"
  log "DESTRUCTIVE_PREFLIGHT_PORT=${actual_port}"
  log "DESTRUCTIVE_PREFLIGHT_VERSION=${version}"
  log "DESTRUCTIVE_PREFLIGHT_DATABASE=${current_database}"
  log "DESTRUCTIVE_PREFLIGHT_TARGET=${target}"
}
