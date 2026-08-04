#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=provisioning-env.sh
source "$SCRIPT_DIR/provisioning-env.sh"

ENV_FILE="/etc/shenzhouhr/shenzhouhr.env"
MIGRATOR_ENV_FILE="/etc/shenzhouhr/shenzhouhr-migrator.env"
HEALTH_URL="http://127.0.0.1:8080/actuator/health"
MYSQL_BIN="${MYSQL_BIN:-mysql}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

while (($#)); do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --migrator-env-file) MIGRATOR_ENV_FILE="$2"; shift 2 ;;
    --health-url) HEALTH_URL="$2"; shift 2 ;;
    --mysql-bin) MYSQL_BIN="$2"; shift 2 ;;
    -h|--help)
      printf 'Usage: verify.sh [--env-file PATH] [--migrator-env-file PATH] [--health-url URL] [--mysql-bin PATH]\n'
      exit 0
      ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ -r "$ENV_FILE" ]] || die "App env not found: $ENV_FILE"
[[ -r "$MIGRATOR_ENV_FILE" ]] || die "Migrator env not found: $MIGRATOR_ENV_FILE"
provisioning_validate_env_pair "$ENV_FILE" "$MIGRATOR_ENV_FILE" \
  || die "$PROVISIONING_ENV_ERROR"
printf '%s\n' 'Checking account-provisioning recovery configuration... passed'
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

printf 'Checking application health...\n'
HEALTH_RESPONSE="$(curl --fail --silent --show-error --max-time 10 "$HEALTH_URL")" \
  || die "Health endpoint failed: $HEALTH_URL"
printf '%s\n' "$HEALTH_RESPONSE" | grep -q '"status":"UP"' \
  || die 'Health endpoint did not report UP'

if command -v "$MYSQL_BIN" >/dev/null 2>&1; then
  printf 'Checking database connectivity and Flyway version...\n'
  DB_URL_VALUE="${SHENZHOUHR_DB_URL#jdbc:mysql://}"
  DB_HOST_PORT="${DB_URL_VALUE%%/*}"
  DB_NAME_VALUE="${DB_URL_VALUE#*/}"
  DB_NAME_VALUE="${DB_NAME_VALUE%%\?*}"
  DB_HOST_VALUE="${DB_HOST_PORT%%:*}"
  DB_PORT_VALUE="${DB_HOST_PORT#*:}"
  [[ "$DB_HOST_VALUE" == "$DB_PORT_VALUE" ]] && DB_PORT_VALUE=3306
  "$MYSQL_BIN" --batch --skip-column-names --protocol=tcp \
    --host="$DB_HOST_VALUE" --port="$DB_PORT_VALUE" \
    --user="$SHENZHOUHR_DB_USERNAME" --password="$SHENZHOUHR_DB_PASSWORD" \
    -e "SELECT VERSION(), DATABASE(); SELECT MAX(version) FROM flyway_schema_history;" \
    "$DB_NAME_VALUE" 2>/dev/null || die 'Database verification failed'
fi

printf '%s\n' 'Verification passed.'
