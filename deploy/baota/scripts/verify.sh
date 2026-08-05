#!/usr/bin/env bash
set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=provisioning-env.sh
source "$SCRIPT_DIR/provisioning-env.sh"

ENV_FILE="/etc/shenzhouhr/shenzhouhr.env"
MIGRATOR_ENV_FILE="/etc/shenzhouhr/shenzhouhr-migrator.env"
HEALTH_URL=""
MYSQL_BIN="${MYSQL_BIN:-mysql}"
EXPECTED_MYSQL_VERSION="8.0.45"
EXPECTED_FLYWAY_VERSION="${EXPECTED_FLYWAY_VERSION:-}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
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
    --migrator-env-file) MIGRATOR_ENV_FILE="$2"; shift 2 ;;
    --health-url) HEALTH_URL="$2"; shift 2 ;;
    --mysql-bin) MYSQL_BIN="$2"; shift 2 ;;
    --expected-mysql-version) EXPECTED_MYSQL_VERSION="$2"; shift 2 ;;
    --expected-flyway-version) EXPECTED_FLYWAY_VERSION="$2"; shift 2 ;;
    -h|--help)
      printf 'Usage: verify.sh [--env-file PATH] [--migrator-env-file PATH] [--health-url URL] [--mysql-bin PATH] [--expected-mysql-version VERSION] [--expected-flyway-version VERSION]\n'
      exit 0
      ;;
    *) die "Unknown option: $1" ;;
  esac
done

if [[ -z "$EXPECTED_FLYWAY_VERSION" ]]; then
  PACKAGE_MANIFEST="$SCRIPT_DIR/../../../BUILD-MANIFEST.txt"
  if [[ -r "$PACKAGE_MANIFEST" ]]; then
    EXPECTED_FLYWAY_VERSION="$(sed -n 's/^migration_files=V1\.\.V\([0-9][0-9]*\)$/\1/p' "$PACKAGE_MANIFEST" | head -1)"
  fi
  EXPECTED_FLYWAY_VERSION="${EXPECTED_FLYWAY_VERSION:-31}"
fi
[[ -r "$ENV_FILE" ]] || die "App env not found: $ENV_FILE"
[[ -r "$MIGRATOR_ENV_FILE" ]] || die "Migrator env not found: $MIGRATOR_ENV_FILE"
[[ "$EXPECTED_MYSQL_VERSION" =~ ^8\.0\.[0-9]+$ ]] \
  || die "Invalid expected MySQL version: $EXPECTED_MYSQL_VERSION"
[[ "$EXPECTED_FLYWAY_VERSION" =~ ^[0-9]+$ ]] \
  || die "Invalid expected Flyway version: $EXPECTED_FLYWAY_VERSION"
provisioning_validate_env_pair "$ENV_FILE" "$MIGRATOR_ENV_FILE" \
  || die "$PROVISIONING_ENV_ERROR"
printf '%s\n' 'Checking account-provisioning recovery configuration... passed'
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
APP_SERVER_PORT="${SHENZHOUHR_SERVER_PORT:-18080}"
[[ "$APP_SERVER_PORT" =~ ^[0-9]+$ ]] \
  || die "Invalid application server port: $APP_SERVER_PORT"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:$APP_SERVER_PORT/actuator/health}"

MYSQL_BIN_RESOLVED="$(command -v "$MYSQL_BIN" 2>/dev/null || true)"
[[ -n "$MYSQL_BIN_RESOLVED" && -x "$MYSQL_BIN_RESOLVED" ]] \
  || die "MySQL client not found: $MYSQL_BIN"
MYSQL_BIN="$MYSQL_BIN_RESOLVED"
MYSQL_DEFAULTS="$(mktemp /tmp/shenzhouhr-verify.XXXXXX.cnf)"
trap 'rm -f -- "$MYSQL_DEFAULTS"' EXIT
chmod 600 "$MYSQL_DEFAULTS"

printf 'Checking application health...\n'
HEALTH_RESPONSE="$(curl --fail --silent --show-error --max-time 10 "$HEALTH_URL")" \
  || die "Health endpoint failed: $HEALTH_URL"
printf '%s\n' "$HEALTH_RESPONSE" | grep -q '"status":"UP"' \
  || die 'Health endpoint did not report UP'

printf 'Checking database connectivity and Flyway version...\n'
DB_URL_VALUE="${SHENZHOUHR_DB_URL#jdbc:mysql://}"
DB_HOST_PORT="${DB_URL_VALUE%%/*}"
DB_NAME_VALUE="${DB_URL_VALUE#*/}"
DB_NAME_VALUE="${DB_NAME_VALUE%%\?*}"
DB_HOST_VALUE="${DB_HOST_PORT%%:*}"
DB_PORT_VALUE="${DB_HOST_PORT#*:}"
[[ "$DB_HOST_VALUE" == "$DB_PORT_VALUE" ]] && DB_PORT_VALUE=3306
{
  printf '[client]\n'
  printf 'user=%s\n' "$(mysql_option_value "$SHENZHOUHR_DB_USERNAME")"
  printf 'password=%s\n' "$(mysql_option_value "$SHENZHOUHR_DB_PASSWORD")"
  printf 'host=%s\n' "$(mysql_option_value "$DB_HOST_VALUE")"
  printf 'port=%s\n' "$DB_PORT_VALUE"
  printf 'protocol=tcp\n'
} > "$MYSQL_DEFAULTS"
DB_CHECK_OUTPUT="$("$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e "SELECT VERSION(), DATABASE(); SELECT MAX(CAST(version AS UNSIGNED)), SUM(success = 0) FROM flyway_schema_history WHERE version IS NOT NULL;" \
  "$DB_NAME_VALUE" 2>/dev/null)" || die 'Database verification failed'
MYSQL_VERSION="$(printf '%s\n' "$DB_CHECK_OUTPUT" | sed -n '1s/[[:space:]].*$//p')"
MYSQL_CORE_VERSION="${MYSQL_VERSION%%-*}"
[[ "$MYSQL_CORE_VERSION" == "$EXPECTED_MYSQL_VERSION" ]] \
  || die "MySQL version is ${MYSQL_VERSION:-unknown}; this release expects exactly $EXPECTED_MYSQL_VERSION"
FLYWAY_STATUS="$(printf '%s\n' "$DB_CHECK_OUTPUT" | sed -n '2p')"
[[ "$FLYWAY_STATUS" == *$'\t'* ]] || die 'Flyway verification returned an unexpected result'
FLYWAY_VERSION="${FLYWAY_STATUS%%$'\t'*}"
FLYWAY_FAILURES="${FLYWAY_STATUS#*$'\t'}"
[[ "$FLYWAY_VERSION" == "$EXPECTED_FLYWAY_VERSION" ]] \
  || die "Flyway version is ${FLYWAY_VERSION:-unknown}; this release expects V$EXPECTED_FLYWAY_VERSION"
[[ "$FLYWAY_FAILURES" == "0" ]] || die "Flyway history contains $FLYWAY_FAILURES failed migration(s)"
printf '%s\n' "$DB_CHECK_OUTPUT"

printf '%s\n' 'Verification passed.'
