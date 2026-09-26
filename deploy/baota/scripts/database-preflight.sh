#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

ENV_FILE="/etc/shenzhouhr/shenzhouhr.env"
MYSQL_BIN="${MYSQL_BIN:-mysql}"
EXPECTED_MYSQL_VERSION="8.0.45"
MAX_FLYWAY_VERSION=""

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: database-preflight.sh --max-flyway-version VERSION [options]
  --env-file PATH                    Existing application env file
  --mysql-bin PATH                   mysql client binary
  --expected-mysql-version VERSION   Exact MySQL core version (default 8.0.45)
  --max-flyway-version VERSION       Highest migration contained in this release
USAGE
}

mysql_option_value() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  printf '"%s"' "$value"
}

while (($#)); do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --mysql-bin) MYSQL_BIN="$2"; shift 2 ;;
    --expected-mysql-version) EXPECTED_MYSQL_VERSION="$2"; shift 2 ;;
    --max-flyway-version) MAX_FLYWAY_VERSION="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] \
  || die "Application env must be a regular file, not a symbolic link: $ENV_FILE"
[[ "$EXPECTED_MYSQL_VERSION" == "8.0.45" ]] \
  || die "This customer release requires exactly MySQL 8.0.45, not ${EXPECTED_MYSQL_VERSION:-unknown}"
[[ "$MAX_FLYWAY_VERSION" =~ ^[1-9][0-9]*$ ]] \
  || die "Invalid release Flyway version: ${MAX_FLYWAY_VERSION:-missing}"

REQUESTED_MYSQL_BIN="$MYSQL_BIN"
REQUESTED_EXPECTED_MYSQL_VERSION="$EXPECTED_MYSQL_VERSION"
REQUESTED_MAX_FLYWAY_VERSION="$MAX_FLYWAY_VERSION"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
MYSQL_BIN="$REQUESTED_MYSQL_BIN"
EXPECTED_MYSQL_VERSION="$REQUESTED_EXPECTED_MYSQL_VERSION"
MAX_FLYWAY_VERSION="$REQUESTED_MAX_FLYWAY_VERSION"

[[ "${SHENZHOUHR_DB_URL:-}" == jdbc:mysql://* ]] \
  || die 'Application env contains an invalid MySQL JDBC URL'
[[ -n "${SHENZHOUHR_DB_USERNAME:-}" ]] \
  || die 'Application env is missing SHENZHOUHR_DB_USERNAME'
[[ -n "${SHENZHOUHR_DB_PASSWORD:-}" ]] \
  || die 'Application env is missing SHENZHOUHR_DB_PASSWORD'

MYSQL_BIN_RESOLVED="$(command -v "$MYSQL_BIN" 2>/dev/null || true)"
[[ -n "$MYSQL_BIN_RESOLVED" && -x "$MYSQL_BIN_RESOLVED" ]] \
  || die "MySQL client not found: $MYSQL_BIN"
MYSQL_BIN="$MYSQL_BIN_RESOLVED"

DB_URL_VALUE="${SHENZHOUHR_DB_URL#jdbc:mysql://}"
DB_HOST_PORT="${DB_URL_VALUE%%/*}"
DB_NAME_VALUE="${DB_URL_VALUE#*/}"
DB_NAME_VALUE="${DB_NAME_VALUE%%\?*}"
DB_HOST_VALUE="${DB_HOST_PORT%%:*}"
DB_PORT_VALUE="${DB_HOST_PORT#*:}"
[[ "$DB_HOST_VALUE" == "$DB_PORT_VALUE" ]] && DB_PORT_VALUE=3306
[[ "$DB_HOST_VALUE" == "127.0.0.1" || "$DB_HOST_VALUE" == "localhost" ]] \
  || die 'This Baota release requires the application MySQL endpoint to be local'
[[ "$DB_PORT_VALUE" =~ ^[0-9]+$ ]] \
  || die "Invalid database port in application env: $DB_PORT_VALUE"
((DB_PORT_VALUE >= 1 && DB_PORT_VALUE <= 65535)) \
  || die "Database port in application env is out of range: $DB_PORT_VALUE"
[[ "$DB_NAME_VALUE" =~ ^[A-Za-z0-9_]+$ ]] \
  || die 'Unsafe database name in application env'

MYSQL_DEFAULTS="$(mktemp /tmp/shenzhouhr-upgrade-preflight.XXXXXX.cnf)"
cleanup() {
  rm -f -- "$MYSQL_DEFAULTS"
}
trap cleanup EXIT
chmod 600 "$MYSQL_DEFAULTS"
{
  printf '[client]\n'
  printf 'user=%s\n' "$(mysql_option_value "$SHENZHOUHR_DB_USERNAME")"
  printf 'password=%s\n' "$(mysql_option_value "$SHENZHOUHR_DB_PASSWORD")"
  printf 'host=%s\n' "$(mysql_option_value "$DB_HOST_VALUE")"
  printf 'port=%s\n' "$DB_PORT_VALUE"
  printf 'protocol=tcp\n'
} > "$MYSQL_DEFAULTS"

PREFLIGHT_OUTPUT="$("$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" \
  --batch --skip-column-names "$DB_NAME_VALUE" 2>/dev/null <<'SQL'
SELECT SUBSTRING_INDEX(VERSION(), '-', 1);
SELECT
    COUNT(CASE WHEN version IS NOT NULL THEN 1 END),
    COALESCE(MIN(CASE WHEN version REGEXP '^[1-9][0-9]*$' THEN CAST(version AS UNSIGNED) END), 0),
    COALESCE(MAX(CASE WHEN version REGEXP '^[1-9][0-9]*$' THEN CAST(version AS UNSIGNED) END), 0),
    COUNT(DISTINCT CASE WHEN version REGEXP '^[1-9][0-9]*$' THEN CAST(version AS UNSIGNED) END),
    SUM(CASE WHEN version IS NOT NULL AND version NOT REGEXP '^[1-9][0-9]*$' THEN 1 ELSE 0 END),
    SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END)
FROM flyway_schema_history;
SQL
)" || die 'Database preflight failed; no application files or migrations were changed'

MYSQL_VERSION="$(printf '%s\n' "$PREFLIGHT_OUTPUT" | sed -n '1p')"
[[ "$MYSQL_VERSION" == "$EXPECTED_MYSQL_VERSION" ]] \
  || die "MySQL version is ${MYSQL_VERSION:-unknown}; this release expects exactly $EXPECTED_MYSQL_VERSION"

FLYWAY_STATUS="$(printf '%s\n' "$PREFLIGHT_OUTPUT" | sed -n '2p')"
IFS=$'\t' read -r FLYWAY_ROWS FLYWAY_MIN FLYWAY_MAX FLYWAY_DISTINCT \
  FLYWAY_INVALID FLYWAY_FAILURES <<< "$FLYWAY_STATUS"
for value in "$FLYWAY_ROWS" "$FLYWAY_MIN" "$FLYWAY_MAX" "$FLYWAY_DISTINCT" \
    "$FLYWAY_INVALID" "$FLYWAY_FAILURES"; do
  [[ "$value" =~ ^[0-9]+$ ]] \
    || die 'Flyway history preflight returned an unexpected result'
done
[[ "$FLYWAY_FAILURES" == "0" ]] \
  || die "Flyway history contains $FLYWAY_FAILURES failed migration(s)"
[[ "$FLYWAY_INVALID" == "0" ]] \
  || die "Flyway history contains $FLYWAY_INVALID unsupported version value(s)"
[[ "$FLYWAY_MIN" == "1" && "$FLYWAY_ROWS" == "$FLYWAY_MAX" \
    && "$FLYWAY_DISTINCT" == "$FLYWAY_ROWS" ]] \
  || die 'Flyway history must be a contiguous, unique V1..Vn chain before upgrade'
((FLYWAY_MAX <= MAX_FLYWAY_VERSION)) \
  || die "Database Flyway version V$FLYWAY_MAX is newer than release V$MAX_FLYWAY_VERSION"

printf 'Database preflight passed: MySQL %s, Flyway V1..V%s (release max V%s).\n' \
  "$MYSQL_VERSION" "$FLYWAY_MAX" "$MAX_FLYWAY_VERSION"
