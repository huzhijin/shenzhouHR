#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[shenzhouhr-mysql8410-provision] ERROR: shell tracing must be disabled.\n' >&2
  exit 1
fi

umask 077
export LC_ALL=C
export LANG=C

readonly EXPECTED_REPOSITORY_ROOT="/Users/huzhijin/Downloads/shenzhouHR"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd -P)"
readonly ISOLATION_ROOT="${HOME}/.local/share/shenzhouhr/mysql-8.4.10-isolated"
readonly MYSQL_CLIENT="${ISOLATION_ROOT}/install/bin/mysql"
readonly ROOT_CLIENT_CONFIG="${ISOLATION_ROOT}/secrets/root-client.cnf"
readonly RUNTIME_ENV="${ISOLATION_ROOT}/secrets/wave3-runtime.env"
readonly RUNTIME_ENV_TEMP="${RUNTIME_ENV}.tmp"
readonly FLYWAY_BIN="${SCRIPT_DIR}/flyway-maven.sh"
readonly EXPECTED_VERSION="8.4.10"
readonly EXPECTED_PORT="13306"
readonly EXPECTED_SOCKET="${ISOLATION_ROOT}/run/mysql8410.sock"
readonly EXPECTED_DATADIR="${ISOLATION_ROOT}/data"

EXECUTE="false"
RUN_ID=""
COMMAND="plan"
COMMAND_SET="false"
SERVER_UUID=""
DB_IDENTITY=""

fail() {
  printf '[shenzhouhr-mysql8410-provision] ERROR: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '[shenzhouhr-mysql8410-provision] %s\n' "$*"
}

cleanup() {
  if [[ -f "$RUNTIME_ENV_TEMP" && ! -L "$RUNTIME_ENV_TEMP" ]]; then
    rm -f -- "$RUNTIME_ENV_TEMP"
  fi
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

usage() {
  cat <<'USAGE'
Usage:
  mysql8410-provision-wave3.sh [--execute --run-id ID] [plan|provision]

plan is non-mutating. provision creates/retains only the two fixed local
schemas and three least-privilege accounts on the already-proven isolated
MySQL 8.4.10 socket. It writes generated credentials only to the fixed
repository-external mode-0600 runtime environment file and never prints them.
USAGE
}

parse_arguments() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --execute)
        EXECUTE="true"
        shift
        ;;
      --run-id)
        [[ $# -ge 2 ]] || fail "--run-id requires a value."
        RUN_ID="$2"
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

portable_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
  fi
}

read_or_generate_credentials() {
  FLYWAY_PASSWORD=""
  DEV_APP_PASSWORD=""
  TEST_APP_PASSWORD=""
  if [[ -f "$RUNTIME_ENV" && ! -L "$RUNTIME_ENV" ]]; then
    [[ "$(portable_mode "$RUNTIME_ENV")" == "600" ]] \
      || fail "Existing runtime environment mode must be exactly 0600."
    local line
    local key
    local value
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ -z "$line" || "$line" == \#* ]] && continue
      key="${line%%=*}"
      value="${line#*=}"
      case "$key" in
        SHENZHOUHR_FLYWAY_PASSWORD) FLYWAY_PASSWORD="$value" ;;
        SHENZHOUHR_DEV_DB_PASSWORD) DEV_APP_PASSWORD="$value" ;;
        SHENZHOUHR_TEST_DB_PASSWORD) TEST_APP_PASSWORD="$value" ;;
      esac
    done < "$RUNTIME_ENV"
  else
    [[ ! -e "$RUNTIME_ENV" && ! -L "$RUNTIME_ENV" ]] \
      || fail "Runtime environment target is not a reusable regular file."
    FLYWAY_PASSWORD="$(openssl rand -hex 32)"
    DEV_APP_PASSWORD="$(openssl rand -hex 32)"
    TEST_APP_PASSWORD="$(openssl rand -hex 32)"
  fi
  [[ "$FLYWAY_PASSWORD" =~ ^[0-9a-f]{64}$ ]] \
    || fail "Migrator credential framing is invalid."
  [[ "$DEV_APP_PASSWORD" =~ ^[0-9a-f]{64}$ ]] \
    || fail "Dev-app credential framing is invalid."
  [[ "$TEST_APP_PASSWORD" =~ ^[0-9a-f]{64}$ ]] \
    || fail "Test-app credential framing is invalid."
}

write_runtime_environment() {
  [[ ! -L "$RUNTIME_ENV_TEMP" ]] || fail "Runtime environment staging path is indirect."
  {
    printf 'SHENZHOUHR_MYSQL_HOST=127.0.0.1\n'
    printf 'SHENZHOUHR_MYSQL_PORT=13306\n'
    printf 'SHENZHOUHR_MYSQL_CLIENT_BIN=%s\n' "$MYSQL_CLIENT"
    printf 'SHENZHOUHR_FLYWAY_BIN=%s\n' "$FLYWAY_BIN"
    printf 'SHENZHOUHR_TEST_FINAL_STATE=PRESERVED\n'
    printf 'SHENZHOUHR_W3_DB_IDENTITY=%s\n' "$DB_IDENTITY"
    printf 'SHENZHOUHR_FLYWAY_PASSWORD=%s\n' "$FLYWAY_PASSWORD"
    printf 'SHENZHOUHR_DEV_DB_PASSWORD=%s\n' "$DEV_APP_PASSWORD"
    printf 'SHENZHOUHR_TEST_DB_PASSWORD=%s\n' "$TEST_APP_PASSWORD"
  } > "$RUNTIME_ENV_TEMP"
  chmod 600 "$RUNTIME_ENV_TEMP"
  mv "$RUNTIME_ENV_TEMP" "$RUNTIME_ENV"
}

verify_isolated_server() {
  local identity
  local version
  local port
  local socket
  local datadir
  local server_uuid
  [[ "$PROJECT_ROOT" == "$EXPECTED_REPOSITORY_ROOT" ]] \
    || fail "Repository identity differs from the fixed local project."
  [[ "$(pwd -P)" == "$EXPECTED_REPOSITORY_ROOT" ]] \
    || fail "Run from the fixed repository root."
  [[ -x "$MYSQL_CLIENT" && ! -L "$MYSQL_CLIENT" ]] \
    || fail "Isolated MySQL client is unavailable or indirect."
  [[ -f "$ROOT_CLIENT_CONFIG" && ! -L "$ROOT_CLIENT_CONFIG" ]] \
    || fail "Isolated root client configuration is unavailable or indirect."
  [[ "$(portable_mode "$ROOT_CLIENT_CONFIG")" == "600" ]] \
    || fail "Isolated root client configuration mode must be 0600."
  [[ -x "$FLYWAY_BIN" && ! -L "$FLYWAY_BIN" ]] \
    || fail "Flyway wrapper is unavailable or indirect."
  identity="$("$MYSQL_CLIENT" --defaults-file="$ROOT_CLIENT_CONFIG" \
    --batch --raw --skip-column-names \
    --execute="SELECT VERSION(), @@port, @@socket, @@datadir, @@server_uuid;")"
  IFS=$'\t' read -r version port socket datadir server_uuid <<< "$identity"
  version="${version%%-*}"
  version="${version%%+*}"
  [[ "$version" == "$EXPECTED_VERSION" ]] \
    || fail "Connected server is not exactly MySQL 8.4.10."
  [[ "$port" == "$EXPECTED_PORT" ]] \
    || fail "Connected server is not on isolated port 13306."
  [[ "$socket" == "$EXPECTED_SOCKET" ]] \
    || fail "Connected server socket differs from the isolated path."
  [[ "${datadir%/}" == "$EXPECTED_DATADIR" ]] \
    || fail "Connected server datadir differs from the isolated path."
  [[ "$server_uuid" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
    || fail "Connected server returned an invalid @@server_uuid."
  SERVER_UUID="$(printf '%s' "$server_uuid" | tr '[:upper:]' '[:lower:]')"
  DB_IDENTITY="mysql8410:${SERVER_UUID}:shenzhou_hr_test"
  log "MYSQL8410_PROVISION_SERVER_IDENTITY=PASS version=${version} port=${port} server_uuid=${SERVER_UUID} db_identity=${DB_IDENTITY}"
}

provision() {
  local invalid_grants
  local invalid_schema_grants
  local schema_grant_count
  verify_isolated_server
  read_or_generate_credentials
  "$MYSQL_CLIENT" --defaults-file="$ROOT_CLIENT_CONFIG" \
    --batch --raw --silent >/dev/null <<SQL
SET SESSION time_zone = '+00:00';
SET SESSION sql_mode =
  'STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

CREATE DATABASE IF NOT EXISTS shenzhou_hr_dev
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS shenzhou_hr_test
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'shenzhou_hr_local_migrator'@'127.0.0.1'
  IDENTIFIED BY '${FLYWAY_PASSWORD}';
ALTER USER 'shenzhou_hr_local_migrator'@'127.0.0.1'
  IDENTIFIED BY '${FLYWAY_PASSWORD}';
REVOKE ALL PRIVILEGES, GRANT OPTION
  FROM 'shenzhou_hr_local_migrator'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON shenzhou_hr_dev.* TO 'shenzhou_hr_local_migrator'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
  ON shenzhou_hr_test.* TO 'shenzhou_hr_local_migrator'@'127.0.0.1';

CREATE USER IF NOT EXISTS 'shenzhou_hr_dev_app'@'127.0.0.1'
  IDENTIFIED BY '${DEV_APP_PASSWORD}';
ALTER USER 'shenzhou_hr_dev_app'@'127.0.0.1'
  IDENTIFIED BY '${DEV_APP_PASSWORD}';
REVOKE ALL PRIVILEGES, GRANT OPTION
  FROM 'shenzhou_hr_dev_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE
  ON shenzhou_hr_dev.* TO 'shenzhou_hr_dev_app'@'127.0.0.1';

CREATE USER IF NOT EXISTS 'shenzhou_hr_test_app'@'127.0.0.1'
  IDENTIFIED BY '${TEST_APP_PASSWORD}';
ALTER USER 'shenzhou_hr_test_app'@'127.0.0.1'
  IDENTIFIED BY '${TEST_APP_PASSWORD}';
REVOKE ALL PRIVILEGES, GRANT OPTION
  FROM 'shenzhou_hr_test_app'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE
  ON shenzhou_hr_test.* TO 'shenzhou_hr_test_app'@'127.0.0.1';
SQL
  write_runtime_environment

  invalid_grants="$("$MYSQL_CLIENT" --defaults-file="$ROOT_CLIENT_CONFIG" \
    --batch --raw --skip-column-names --execute="
      SELECT COUNT(*)
      FROM information_schema.USER_PRIVILEGES
      WHERE GRANTEE IN (
        '''shenzhou_hr_local_migrator''@''127.0.0.1''',
        '''shenzhou_hr_dev_app''@''127.0.0.1''',
        '''shenzhou_hr_test_app''@''127.0.0.1'''
      )
        AND PRIVILEGE_TYPE <> 'USAGE';
    ")"
  [[ "$invalid_grants" == "0" ]] \
    || fail "A W3 runtime account has an unexpected global privilege."
  invalid_schema_grants="$("$MYSQL_CLIENT" --defaults-file="$ROOT_CLIENT_CONFIG" \
    --batch --raw --skip-column-names --execute="
      SELECT COUNT(*)
      FROM information_schema.SCHEMA_PRIVILEGES
      WHERE GRANTEE IN (
        '''shenzhou_hr_local_migrator''@''127.0.0.1''',
        '''shenzhou_hr_dev_app''@''127.0.0.1''',
        '''shenzhou_hr_test_app''@''127.0.0.1'''
      )
        AND NOT (
          (
            GRANTEE = '''shenzhou_hr_local_migrator''@''127.0.0.1'''
            AND TABLE_SCHEMA IN ('shenzhou_hr_dev', 'shenzhou_hr_test')
            AND PRIVILEGE_TYPE IN (
              'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'DROP',
              'REFERENCES', 'INDEX', 'ALTER'
            )
          )
          OR (
            GRANTEE = '''shenzhou_hr_dev_app''@''127.0.0.1'''
            AND TABLE_SCHEMA = 'shenzhou_hr_dev'
            AND PRIVILEGE_TYPE IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE')
          )
          OR (
            GRANTEE = '''shenzhou_hr_test_app''@''127.0.0.1'''
            AND TABLE_SCHEMA = 'shenzhou_hr_test'
            AND PRIVILEGE_TYPE IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE')
          )
        );
    ")"
  [[ "$invalid_schema_grants" == "0" ]] \
    || fail "A W3 runtime account has an unexpected schema privilege."
  schema_grant_count="$("$MYSQL_CLIENT" --defaults-file="$ROOT_CLIENT_CONFIG" \
    --batch --raw --skip-column-names --execute="
      SELECT COUNT(*)
      FROM information_schema.SCHEMA_PRIVILEGES
      WHERE GRANTEE IN (
        '''shenzhou_hr_local_migrator''@''127.0.0.1''',
        '''shenzhou_hr_dev_app''@''127.0.0.1''',
        '''shenzhou_hr_test_app''@''127.0.0.1'''
      );
    ")"
  [[ "$schema_grant_count" == "26" ]] \
    || fail "The exact W3 schema privilege set is incomplete."
  log "MYSQL8410_W3_PROVISION=PASS schemas=shenzhou_hr_dev,shenzhou_hr_test accounts=migrator,dev_app,test_app global_privileges=none schema_privileges=26 db_identity=${DB_IDENTITY}"
  log "MYSQL8410_W3_RUNTIME_ENV=PASS path=${RUNTIME_ENV} mode=0600 db_identity=${DB_IDENTITY} variables=SHENZHOUHR_MYSQL_HOST,SHENZHOUHR_MYSQL_PORT,SHENZHOUHR_MYSQL_CLIENT_BIN,SHENZHOUHR_FLYWAY_BIN,SHENZHOUHR_TEST_FINAL_STATE,SHENZHOUHR_W3_DB_IDENTITY,SHENZHOUHR_FLYWAY_PASSWORD,SHENZHOUHR_DEV_DB_PASSWORD,SHENZHOUHR_TEST_DB_PASSWORD"
}

print_plan() {
  cat <<PLAN
W3 isolated MySQL provisioning plan
  server         : exact MySQL 8.4.10 at socket ${EXPECTED_SOCKET}
  schemas        : shenzhou_hr_dev, shenzhou_hr_test
  accounts       : shenzhou_hr_local_migrator, shenzhou_hr_dev_app, shenzhou_hr_test_app
  global grants  : none
  db identity    : mysql8410:<@@server_uuid>:shenzhou_hr_test
  runtime env    : ${RUNTIME_ENV} (mode 0600, outside repository)
  secret output  : forbidden
PLAN
}

dispatch() {
  case "$COMMAND" in
    plan)
      print_plan
      ;;
    provision)
      [[ "$EXECUTE" == "true" ]] || fail "provision requires --execute."
      [[ "$RUN_ID" =~ ^[a-z0-9][a-z0-9._-]{5,63}$ ]] \
        || fail "provision requires a valid --run-id."
      log "MYSQL8410_PROVISION_RUN_CONTEXT=START run_id=${RUN_ID}"
      provision
      log "MYSQL8410_PROVISION_RUN_CONTEXT=PASS run_id=${RUN_ID}"
      ;;
    *)
      fail "Unknown command. Run with --help."
      ;;
  esac
}

parse_arguments "$@"
dispatch
