#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

JAR_PATH=""
APP_ENV_FILE="/etc/shenzhouhr/shenzhouhr.env"
MIGRATOR_ENV_FILE="/etc/shenzhouhr/shenzhouhr-migrator.env"
TIMEOUT_SEC="180"
MYSQL_BIN="${MYSQL_BIN:-mysql}"
EXPECTED_MYSQL_VERSION="8.0.45"
readonly DB_NAME="shenzhou_hr"
readonly DB_HOST="127.0.0.1"
readonly DB_PORT="3306"
readonly ACCOUNT_HOST="127.0.0.1"
readonly APP_USER="shenzhouhr_app"
readonly MIGRATOR_USER="shenzhouhr_migrator"
readonly YEAR_END_PROCEDURE="szsc_oa_time_off_expire"

MYSQL_ADMIN_DEFAULTS=""
LOG_FILE=""
PID=""

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: migrate.sh --jar PATH [--app-env-file PATH] [--migrator-env-file PATH]
                  [--timeout-sec SECONDS] [--mysql-bin PATH]
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

cleanup() {
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    kill -TERM "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  [[ -z "$LOG_FILE" ]] || rm -f -- "$LOG_FILE"
  [[ -z "$MYSQL_ADMIN_DEFAULTS" ]] || rm -f -- "$MYSQL_ADMIN_DEFAULTS"
}
trap cleanup EXIT

while (($#)); do
  case "$1" in
    --jar) JAR_PATH="$2"; shift 2 ;;
    --app-env-file) APP_ENV_FILE="$2"; shift 2 ;;
    --migrator-env-file) MIGRATOR_ENV_FILE="$2"; shift 2 ;;
    --timeout-sec) TIMEOUT_SEC="$2"; shift 2 ;;
    --mysql-bin) MYSQL_BIN="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ -f "$JAR_PATH" ]] || die "Jar not found: $JAR_PATH"
[[ -r "$APP_ENV_FILE" ]] || die "App env not found: $APP_ENV_FILE"
[[ -r "$MIGRATOR_ENV_FILE" ]] || die "Migrator env not found: $MIGRATOR_ENV_FILE"
[[ "$TIMEOUT_SEC" =~ ^[0-9]+$ ]] || die 'Timeout must be an integer'
[[ "$TIMEOUT_SEC" -ge 1 ]] || die 'Timeout must be greater than zero'

MYSQL_BIN_RESOLVED="$(command -v "$MYSQL_BIN" 2>/dev/null || true)"
[[ -n "$MYSQL_BIN_RESOLVED" && -x "$MYSQL_BIN_RESOLVED" ]] \
  || die "MySQL client not found: $MYSQL_BIN"
MYSQL_BIN="$MYSQL_BIN_RESOLVED"

JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
[[ -n "$JAVA_BIN" ]] || die 'Java 21 is required'
JAVA_MAJOR="$($JAVA_BIN -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAVA_MAJOR" == "21" ]] || die "Java 21 is required; detected: ${JAVA_MAJOR:-unknown}"
JAR_BIN="${JAR_BIN:-$(dirname -- "$JAVA_BIN")/jar}"
[[ -x "$JAR_BIN" ]] || die "A full JDK 21 is required; jar tool not found: $JAR_BIN"
"$JAR_BIN" tf "$JAR_PATH" | grep '^BOOT-INF/lib/spring-boot-flyway-.*\.jar$' >/dev/null \
  || die 'Jar is missing Spring Boot Flyway auto-configuration; migration cannot run'

set -a
# shellcheck disable=SC1090
source "$MIGRATOR_ENV_FILE"
set +a
[[ "${SHENZHOUHR_FLYWAY_ENABLED:-false}" == "true" ]] || die 'Migrator env must enable Flyway'
MIGRATOR_DB_URL="${SHENZHOUHR_DB_URL:-}"
MIGRATOR_FLYWAY_URL="${SHENZHOUHR_FLYWAY_URL:-}"
MIGRATOR_DB_USERNAME="${SHENZHOUHR_DB_USERNAME:-}"

# Read only the two non-secret app contract values in an isolated shell.  In
# particular, OA credentials from the app env must never enter the migration
# Java process environment.
APP_DB_CONTRACT="$(
  set -a
  # shellcheck disable=SC1090
  source "$APP_ENV_FILE"
  set +a
  printf '%s\t%s' "${SHENZHOUHR_DB_URL:-}" "${SHENZHOUHR_DB_USERNAME:-}"
)"
IFS=$'\t' read -r APP_DB_URL APP_DB_USERNAME <<< "$APP_DB_CONTRACT"
unset APP_DB_CONTRACT

EXPECTED_DB_URL_PREFIX="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?"
[[ "$APP_DB_URL" == "$EXPECTED_DB_URL_PREFIX"* ]] \
  || die 'App env must target the fixed local shenzhou_hr database'
[[ "$MIGRATOR_DB_URL" == "$APP_DB_URL" && "$MIGRATOR_FLYWAY_URL" == "$APP_DB_URL" ]] \
  || die 'App and migrator env files must target the same fixed database URL'
[[ "$APP_DB_USERNAME" == "$APP_USER" ]] \
  || die "App env must use $APP_USER"
[[ "$MIGRATOR_DB_USERNAME" == "$MIGRATOR_USER" ]] \
  || die "Migrator env must use $MIGRATOR_USER"

read -r -p 'MySQL root user for migration grants [root]: ' MYSQL_ROOT_USER
MYSQL_ROOT_USER="${MYSQL_ROOT_USER:-root}"
[[ "$MYSQL_ROOT_USER" =~ ^[A-Za-z0-9._-]+$ ]] || die 'Unsafe MySQL root username'
read -r -s -p 'MySQL root password for migration grants: ' MYSQL_ROOT_PASSWORD
printf '\n'
[[ -n "$MYSQL_ROOT_PASSWORD" ]] || die 'MySQL root password cannot be empty'

MYSQL_ADMIN_DEFAULTS="$(mktemp /tmp/shenzhouhr-migration-admin.XXXXXX.cnf)"
chmod 600 "$MYSQL_ADMIN_DEFAULTS"
{
  printf '[client]\n'
  printf 'user=%s\n' "$(mysql_option_value "$MYSQL_ROOT_USER")"
  printf 'password=%s\n' "$(mysql_option_value "$MYSQL_ROOT_PASSWORD")"
  printf 'host=%s\n' "$(mysql_option_value "$DB_HOST")"
  printf 'port=%s\n' "$DB_PORT"
  printf 'protocol=tcp\n'
} > "$MYSQL_ADMIN_DEFAULTS"
unset MYSQL_ROOT_PASSWORD

MYSQL_VERSION="$($MYSQL_BIN --defaults-extra-file="$MYSQL_ADMIN_DEFAULTS" \
  --batch --skip-column-names \
  -e "SELECT SUBSTRING_INDEX(VERSION(), '-', 1)" 2>/dev/null)" \
  || die 'Cannot connect to MySQL with the supplied root account'
[[ "$MYSQL_VERSION" == "$EXPECTED_MYSQL_VERSION" ]] \
  || die "MySQL version is ${MYSQL_VERSION:-unknown}; this release expects exactly $EXPECTED_MYSQL_VERSION"

DATABASE_CONTRACT="$($MYSQL_BIN --defaults-extra-file="$MYSQL_ADMIN_DEFAULTS" \
  --batch --skip-column-names -e "SELECT
    (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$DB_NAME'),
    (SELECT COUNT(*) FROM mysql.user WHERE User = '$APP_USER' AND Host = '$ACCOUNT_HOST'),
    (SELECT COUNT(*) FROM mysql.user WHERE User = '$MIGRATOR_USER' AND Host = '$ACCOUNT_HOST'),
    IF(@@GLOBAL.automatic_sp_privileges, 1, 0);" 2>/dev/null)" \
  || die 'Cannot validate database accounts and stored-routine privilege policy'
[[ "$DATABASE_CONTRACT" == $'1\t1\t1\t1' ]] \
  || die 'Database, fixed service accounts, or automatic_sp_privileges do not match the migration contract'

printf '%s\n' 'Applying the two additional schema privileges required by V41/V42...'
"$MYSQL_BIN" --defaults-extra-file="$MYSQL_ADMIN_DEFAULTS" --batch --skip-column-names <<SQL
GRANT CREATE VIEW, CREATE ROUTINE ON \`$DB_NAME\`.* TO '$MIGRATOR_USER'@'$ACCOUNT_HOST';
SQL

PRE_MIGRATION_GRANTS="$($MYSQL_BIN --defaults-extra-file="$MYSQL_ADMIN_DEFAULTS" \
  --batch --skip-column-names -e "SELECT
    COALESCE((SELECT GROUP_CONCAT(PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE SEPARATOR ',')
      FROM information_schema.SCHEMA_PRIVILEGES
      WHERE TABLE_SCHEMA = '$DB_NAME'
        AND GRANTEE = CONCAT(QUOTE('$APP_USER'), '@', QUOTE('$ACCOUNT_HOST'))), ''),
    COALESCE((SELECT GROUP_CONCAT(PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE SEPARATOR ',')
      FROM information_schema.SCHEMA_PRIVILEGES
      WHERE TABLE_SCHEMA = '$DB_NAME'
        AND GRANTEE = CONCAT(QUOTE('$MIGRATOR_USER'), '@', QUOTE('$ACCOUNT_HOST'))), ''),
    (SELECT COUNT(*) FROM information_schema.USER_PRIVILEGES
      WHERE GRANTEE IN (
        CONCAT(QUOTE('$APP_USER'), '@', QUOTE('$ACCOUNT_HOST')),
        CONCAT(QUOTE('$MIGRATOR_USER'), '@', QUOTE('$ACCOUNT_HOST'))
      ) AND PRIVILEGE_TYPE <> 'USAGE'),
    (SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES
      WHERE TABLE_SCHEMA = '$DB_NAME'
        AND GRANTEE IN (
          CONCAT(QUOTE('$APP_USER'), '@', QUOTE('$ACCOUNT_HOST')),
          CONCAT(QUOTE('$MIGRATOR_USER'), '@', QUOTE('$ACCOUNT_HOST'))
        ) AND IS_GRANTABLE <> 'NO'),
    (SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES
      WHERE TABLE_SCHEMA = '$DB_NAME'
        AND GRANTEE IN (
          CONCAT(QUOTE('$APP_USER'), '@', QUOTE('$ACCOUNT_HOST')),
          CONCAT(QUOTE('$MIGRATOR_USER'), '@', QUOTE('$ACCOUNT_HOST'))
        )),
    (SELECT COUNT(*) FROM information_schema.COLUMN_PRIVILEGES
      WHERE TABLE_SCHEMA = '$DB_NAME'
        AND GRANTEE IN (
          CONCAT(QUOTE('$APP_USER'), '@', QUOTE('$ACCOUNT_HOST')),
          CONCAT(QUOTE('$MIGRATOR_USER'), '@', QUOTE('$ACCOUNT_HOST'))
        ));" 2>/dev/null)" \
  || die 'Cannot verify least-privilege database grants before migration'
EXPECTED_PRE_MIGRATION_GRANTS=$'DELETE,INSERT,SELECT,UPDATE\tALTER,CREATE,CREATE ROUTINE,CREATE VIEW,DELETE,DROP,INDEX,INSERT,REFERENCES,SELECT,UPDATE\t0\t0\t0\t0'
[[ "$PRE_MIGRATION_GRANTS" == "$EXPECTED_PRE_MIGRATION_GRANTS" ]] \
  || die 'Service-account grants differ from the least-privilege migration contract'

LOG_FILE="$(mktemp /tmp/shenzhouhr-migrate.XXXXXX.log)"

printf 'Running one-time database migration...\n'
"$JAVA_BIN" -jar "$JAR_PATH" \
  --server.address=127.0.0.1 \
  --server.port=0 \
  --shenzhouhr.reporting.export-worker-enabled=false \
  >"$LOG_FILE" 2>&1 &
PID=$!
STARTED=0
DEADLINE=$((SECONDS + TIMEOUT_SEC))

while ((SECONDS < DEADLINE)); do
  if grep -q 'Started ShenzhouHrApplication' "$LOG_FILE"; then
    STARTED=1
    break
  fi
  if grep -qE 'APPLICATION FAILED TO START|Validate failed|Migration .* failed|Unable to obtain connection' "$LOG_FILE"; then
    printf '%s\n' 'Flyway migration failed. Safe log tail:' >&2
    tail -80 "$LOG_FILE" >&2
    exit 1
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    wait "$PID" || {
      printf '%s\n' 'Migration process exited with an error. Safe log tail:' >&2
      tail -80 "$LOG_FILE" >&2
      exit 1
    }
    break
  fi
  sleep 1
done

if ((STARTED == 0)); then
  printf '%s\n' 'Migration timed out. Safe log tail:' >&2
  tail -80 "$LOG_FILE" >&2
  exit 1
fi

kill -TERM "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || STATUS=$?
STATUS="${STATUS:-0}"
[[ "$STATUS" == 0 || "$STATUS" == 143 ]] || die "Migration process stopped with exit code $STATUS"

PROCEDURE_COUNT="$($MYSQL_BIN --defaults-extra-file="$MYSQL_ADMIN_DEFAULTS" \
  --batch --skip-column-names -e "SELECT COUNT(*)
    FROM information_schema.ROUTINES
    WHERE ROUTINE_SCHEMA = '$DB_NAME'
      AND ROUTINE_NAME = '$YEAR_END_PROCEDURE'
      AND ROUTINE_TYPE = 'PROCEDURE'
      AND SECURITY_TYPE = 'DEFINER'
      AND DEFINER = CONCAT('$MIGRATOR_USER', '@', '$ACCOUNT_HOST');" 2>/dev/null)" \
  || die 'Cannot verify the year-end procedure after migration'
[[ "$PROCEDURE_COUNT" == "1" ]] \
  || die "Required year-end procedure was not created: $YEAR_END_PROCEDURE"

printf '%s\n' 'Granting the application EXECUTE on the single year-end procedure...'
"$MYSQL_BIN" --defaults-extra-file="$MYSQL_ADMIN_DEFAULTS" --batch --skip-column-names <<SQL
GRANT EXECUTE ON PROCEDURE \`$DB_NAME\`.\`$YEAR_END_PROCEDURE\` TO '$APP_USER'@'$ACCOUNT_HOST';
SQL

APP_ROUTINE_GRANTS="$($MYSQL_BIN --defaults-extra-file="$MYSQL_ADMIN_DEFAULTS" \
  --batch --skip-column-names -e "SELECT COUNT(*), COALESCE(SUM(
      Db = '$DB_NAME'
      AND Routine_name = '$YEAR_END_PROCEDURE'
      AND Routine_type = 'PROCEDURE'
      AND Proc_priv = 'Execute'
    ), 0)
    FROM mysql.procs_priv
    WHERE User = '$APP_USER' AND Host = '$ACCOUNT_HOST';" 2>/dev/null)" \
  || die 'Cannot verify application routine privileges after migration'
[[ "$APP_ROUTINE_GRANTS" == $'1\t1' ]] \
  || die 'Application routine grants exceed or miss the single year-end EXECUTE privilege'
printf '%s\n' 'Database migration completed.'
