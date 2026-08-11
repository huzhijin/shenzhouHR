#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../scripts/provisioning-env.sh
source "$SCRIPT_DIR/../scripts/provisioning-env.sh"

ENV_FILE="/etc/shenzhouhr/shenzhouhr.env"
MIGRATOR_ENV_FILE="/etc/shenzhouhr/shenzhouhr-migrator.env"
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_NAME="shenzhou_hr"
ACCOUNT_HOST="127.0.0.1"
BACKEND_ADDRESS="0.0.0.0"
BACKEND_PORT="8080"
APP_USER="shenzhouhr_app"
MIGRATOR_USER="shenzhouhr_migrator"
PANEL_USER="shenzhouhr_panel"
EXPECTED_VERSION="8.0.45"
SESSION_COOKIE_SECURE="false"
MYSQL_BIN="${MYSQL_BIN:-mysql}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: provision.sh [options]
  --env-file PATH              App env output (default /etc/shenzhouhr/shenzhouhr.env)
  --migrator-env-file PATH     Migrator env output (default /etc/shenzhouhr/shenzhouhr-migrator.env)
  --db-host HOST               MySQL host (default 127.0.0.1)
  --db-port PORT               MySQL port (default 3306)
  --db-name NAME               Database name (default shenzhou_hr)
  --account-host HOST          MySQL account host (default 127.0.0.1)
  --backend-address ADDRESS    Spring Boot bind address (default 0.0.0.0)
  --backend-port PORT          Spring Boot port (default 8080)
  --app-user NAME              App DB user (default shenzhouhr_app)
  --migrator-user NAME         Migration DB user (default shenzhouhr_migrator)
  --expected-version VERSION   Exact core version (default 8.0.45)
  --session-cookie-secure BOOL  Cookie secure flag (default false for this internal HTTP deployment)
  --mysql-bin PATH             mysql client binary
USAGE
}

valid_identifier() {
  [[ "$1" =~ ^[A-Za-z0-9_]+$ ]]
}

mysql_option_value() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  printf '"%s"' "$value"
}

write_env() {
  local target="$1"
  local content="$2"
  local temp
  [[ ! -e "$target" ]] || die "Refusing to overwrite existing env file: $target (move it aside first)"
  mkdir -p "$(dirname -- "$target")"
  temp="$(mktemp "${target}.tmp.XXXXXX")"
  trap 'rm -f -- "$temp"' RETURN
  printf '%s\n' "$content" > "$temp"
  chmod 600 "$temp"
  mv -- "$temp" "$target"
  trap - RETURN
}

while (($#)); do
  case "$1" in
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --migrator-env-file) MIGRATOR_ENV_FILE="$2"; shift 2 ;;
    --db-host) DB_HOST="$2"; shift 2 ;;
    --db-port) DB_PORT="$2"; shift 2 ;;
    --db-name) DB_NAME="$2"; shift 2 ;;
    --account-host) ACCOUNT_HOST="$2"; shift 2 ;;
    --backend-address) BACKEND_ADDRESS="$2"; shift 2 ;;
    --backend-port) BACKEND_PORT="$2"; shift 2 ;;
    --app-user) APP_USER="$2"; shift 2 ;;
    --migrator-user) MIGRATOR_USER="$2"; shift 2 ;;
    --expected-version) EXPECTED_VERSION="$2"; shift 2 ;;
    --session-cookie-secure) SESSION_COOKIE_SECURE="$2"; shift 2 ;;
    --mysql-bin) MYSQL_BIN="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

command -v "$MYSQL_BIN" >/dev/null 2>&1 || die "mysql client not found: $MYSQL_BIN"
valid_identifier "$DB_NAME" || die "Unsafe database name: $DB_NAME"
valid_identifier "$APP_USER" || die "Unsafe app username: $APP_USER"
valid_identifier "$MIGRATOR_USER" || die "Unsafe migrator username: $MIGRATOR_USER"
[[ "$DB_PORT" =~ ^[0-9]+$ ]] || die "Invalid database port: $DB_PORT"
[[ "$DB_PORT" -ge 1 && "$DB_PORT" -le 65535 ]] || die "Invalid database port: $DB_PORT"
[[ "$BACKEND_ADDRESS" == "127.0.0.1" || "$BACKEND_ADDRESS" == "0.0.0.0" ]] \
  || die 'Backend address must be 127.0.0.1 or 0.0.0.0'
[[ "$BACKEND_PORT" =~ ^[0-9]+$ ]] || die "Invalid backend port: $BACKEND_PORT"
[[ "$BACKEND_PORT" -ge 1024 && "$BACKEND_PORT" -le 65535 ]] \
  || die "Backend port must be between 1024 and 65535: $BACKEND_PORT"
[[ "$EXPECTED_VERSION" =~ ^8\.0\.[0-9]+$ ]] || die "Invalid expected MySQL version: $EXPECTED_VERSION"
[[ "$SESSION_COOKIE_SECURE" == "true" || "$SESSION_COOKIE_SECURE" == "false" ]] \
  || die 'session-cookie-secure must be true or false'
[[ "$DB_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]*$ ]] || die 'Unsafe database host'
[[ "$DB_HOST" == "127.0.0.1" || "$DB_HOST" == "localhost" ]] \
  || die 'This Baota release requires MySQL on the same server (127.0.0.1 or localhost)'
[[ "$ACCOUNT_HOST" =~ ^[A-Za-z0-9._:-]+$ ]] || die 'Unsafe MySQL account host'
[[ "$ACCOUNT_HOST" != "%" ]] || die 'Refusing a wildcard MySQL account host; use the fixed app server IP'
[[ "$DB_HOST" == "127.0.0.1" ]] \
  || die 'This customer package requires db-host 127.0.0.1'
[[ "$DB_PORT" == "3306" ]] \
  || die 'This customer package requires db-port 3306'
[[ "$DB_NAME" == "shenzhou_hr" ]] \
  || die 'This customer package requires database shenzhou_hr'
[[ "$ACCOUNT_HOST" == "127.0.0.1" ]] \
  || die 'This customer package requires account-host 127.0.0.1'
[[ "$BACKEND_ADDRESS" == "0.0.0.0" ]] \
  || die 'This customer package requires backend-address 0.0.0.0'
[[ "$BACKEND_PORT" == "8080" ]] \
  || die 'This customer package requires backend-port 8080'
[[ "$APP_USER" == "shenzhouhr_app" && "$MIGRATOR_USER" == "shenzhouhr_migrator" ]] \
  || die 'This customer package requires the fixed ShenzhouHR service account names'
[[ "$EXPECTED_VERSION" == "8.0.45" ]] \
  || die 'This customer package requires MySQL 8.0.45'
[[ ! -e "$ENV_FILE" ]] \
  || die "Existing app env found: $ENV_FILE. Refusing to change database credentials; use upgrade.sh for an existing installation."
[[ ! -e "$MIGRATOR_ENV_FILE" ]] \
  || die "Existing migrator env found: $MIGRATOR_ENV_FILE. Refusing to change database credentials; use upgrade.sh for an existing installation."

read -r -p 'MySQL root user [root]: ' MYSQL_ROOT_USER
MYSQL_ROOT_USER="${MYSQL_ROOT_USER:-root}"
[[ "$MYSQL_ROOT_USER" =~ ^[A-Za-z0-9._-]+$ ]] || die 'Unsafe MySQL root username'
read -r -s -p 'MySQL root password: ' MYSQL_ROOT_PASSWORD
printf '\n'
[[ -n "$MYSQL_ROOT_PASSWORD" ]] || die 'MySQL root password cannot be empty'

MYSQL_DEFAULTS="$(mktemp /tmp/shenzhouhr-mysql.XXXXXX.cnf)"
ACCOUNT_MUTATION_STARTED=0
PROVISION_COMPLETE=0
cleanup_provision() {
  local status=$?
  trap - EXIT
  if ((status != 0 && ACCOUNT_MUTATION_STARTED == 1 && PROVISION_COMPLETE == 0)); then
    "$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
      -e "DROP USER IF EXISTS '$APP_USER'@'$ACCOUNT_HOST', '$MIGRATOR_USER'@'$ACCOUNT_HOST'" \
      >/dev/null 2>&1 \
      || printf 'WARNING: failed to remove partially created MySQL service accounts.\n' >&2
    rm -f -- "$ENV_FILE" "$MIGRATOR_ENV_FILE"
  fi
  rm -f -- "$MYSQL_DEFAULTS"
  exit "$status"
}
trap cleanup_provision EXIT
chmod 600 "$MYSQL_DEFAULTS"
{
  printf '[client]\n'
  printf 'user=%s\n' "$(mysql_option_value "$MYSQL_ROOT_USER")"
  printf 'password=%s\n' "$(mysql_option_value "$MYSQL_ROOT_PASSWORD")"
  printf 'host=%s\n' "$(mysql_option_value "$DB_HOST")"
  printf 'port=%s\n' "$DB_PORT"
  printf 'protocol=tcp\n'
} > "$MYSQL_DEFAULTS"
unset MYSQL_ROOT_PASSWORD

MYSQL_VERSION="$($MYSQL_BIN --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e 'SELECT SUBSTRING_INDEX(VERSION(), "-", 1)' 2>/dev/null)" || die 'Cannot connect to MySQL with the supplied root account'
[[ "$MYSQL_VERSION" == "$EXPECTED_VERSION" ]] || die "MySQL version is $MYSQL_VERSION; this release expects exactly $EXPECTED_VERSION"

DB_EXISTS="$($MYSQL_BIN --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$DB_NAME'" 2>/dev/null)" \
  || die "Cannot verify Baota database: $DB_NAME"
[[ "$DB_EXISTS" == "1" ]] \
  || die "Database $DB_NAME does not exist. Create this empty database in the Baota panel before installation."
DB_TABLE_COUNT="$($MYSQL_BIN --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$DB_NAME'" 2>/dev/null)" \
  || die "Cannot inspect Baota database: $DB_NAME"
[[ "$DB_TABLE_COUNT" == "0" ]] \
  || die "Database $DB_NAME is not empty ($DB_TABLE_COUNT tables). Refusing a first installation; stop and review the cleanup or data-migration plan."
PANEL_ACCOUNT_COUNT="$($MYSQL_BIN --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e "SELECT COUNT(*) FROM mysql.user WHERE User = '$PANEL_USER' AND Host IN ('localhost', '127.0.0.1')" 2>/dev/null)" \
  || die 'Cannot inspect the Baota panel database account'
[[ "$PANEL_ACCOUNT_COUNT" =~ ^[12]$ ]] \
  || die "Expected one or two local $PANEL_USER accounts created by the Baota panel; found $PANEL_ACCOUNT_COUNT"
PANEL_UNSAFE_HOSTS="$($MYSQL_BIN --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e "SELECT COUNT(*) FROM mysql.user WHERE User = '$PANEL_USER' AND Host NOT IN ('localhost', '127.0.0.1')" 2>/dev/null)" \
  || die 'Cannot inspect Baota panel account Hosts'
[[ "$PANEL_UNSAFE_HOSTS" == "0" ]] \
  || die "The $PANEL_USER account exists at a non-local Host; correct it in Baota/DBA before installation"
PANEL_SCHEMA_GRANTS="$($MYSQL_BIN --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e "SELECT COUNT(DISTINCT GRANTEE) FROM information_schema.SCHEMA_PRIVILEGES WHERE TABLE_SCHEMA = '$DB_NAME' AND GRANTEE IN (CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), 'localhost', CHAR(39)), CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), '127.0.0.1', CHAR(39)))" 2>/dev/null)" \
  || die 'Cannot inspect Baota panel database grants'
[[ "$PANEL_SCHEMA_GRANTS" == "$PANEL_ACCOUNT_COUNT" ]] \
  || die "Every local $PANEL_USER account must have database-level grants on $DB_NAME; accounts=$PANEL_ACCOUNT_COUNT grantees=$PANEL_SCHEMA_GRANTS"
UNEXPECTED_DB_GRANTS="$($MYSQL_BIN --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e "SELECT (SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES WHERE TABLE_SCHEMA = '$DB_NAME' AND GRANTEE NOT IN (CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), 'localhost', CHAR(39)), CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), '127.0.0.1', CHAR(39)))) + (SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA = '$DB_NAME' AND GRANTEE NOT IN (CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), 'localhost', CHAR(39)), CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), '127.0.0.1', CHAR(39)))) + (SELECT COUNT(*) FROM information_schema.COLUMN_PRIVILEGES WHERE TABLE_SCHEMA = '$DB_NAME' AND GRANTEE NOT IN (CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), 'localhost', CHAR(39)), CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), '127.0.0.1', CHAR(39)))) + (SELECT COUNT(*) FROM information_schema.ROUTINE_PRIVILEGES WHERE ROUTINE_SCHEMA = '$DB_NAME' AND GRANTEE NOT IN (CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), 'localhost', CHAR(39)), CONCAT(CHAR(39), '$PANEL_USER', CHAR(39), '@', CHAR(39), '127.0.0.1', CHAR(39))))" 2>/dev/null)" \
  || die 'Cannot inspect unexpected database grants'
[[ "$UNEXPECTED_DB_GRANTS" == "0" ]] \
  || die "Unexpected old account grants still target $DB_NAME. Revoke them with the DBA before installation."
ACCOUNT_CONFLICTS="$($MYSQL_BIN --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e "SELECT COUNT(*) FROM mysql.user WHERE User IN ('$APP_USER', '$MIGRATOR_USER')" 2>/dev/null)" \
  || die 'Cannot inspect existing MySQL service accounts'
[[ "$ACCOUNT_CONFLICTS" == "0" ]] \
  || die 'An existing ShenzhouHR MySQL service account was found at one or more Hosts. Refusing to rotate passwords or reuse old grants; finish the DBA-reviewed cleanup first.'

APP_PASSWORD="$(openssl rand -hex 32)" || die 'openssl is required to generate an app password'
MIGRATOR_PASSWORD="$(openssl rand -hex 32)" || die 'openssl is required to generate a migrator password'
provisioning_generate_pepper || die "$PROVISIONING_ENV_ERROR"
PROVISIONING_PEPPER="$PROVISIONING_GENERATED_PEPPER"
PROVISIONING_KEY_ID="v1"
PROVISIONING_RECOVERY_WINDOW="PT1H"
DB_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true"

ACCOUNT_MUTATION_STARTED=1
"$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names <<SQL
ALTER DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$APP_USER'@'$ACCOUNT_HOST' IDENTIFIED BY '$APP_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$DB_NAME\`.* TO '$APP_USER'@'$ACCOUNT_HOST';
CREATE USER '$MIGRATOR_USER'@'$ACCOUNT_HOST' IDENTIFIED BY '$MIGRATOR_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON \`$DB_NAME\`.* TO '$MIGRATOR_USER'@'$ACCOUNT_HOST';
FLUSH PRIVILEGES;
SQL

write_env "$ENV_FILE" "SPRING_PROFILES_ACTIVE=prod
SHENZHOUHR_SERVER_ADDRESS=$BACKEND_ADDRESS
SHENZHOUHR_SERVER_PORT=$BACKEND_PORT
SHENZHOUHR_DB_URL='$DB_URL'
SHENZHOUHR_DB_USERNAME=$APP_USER
SHENZHOUHR_DB_PASSWORD=$APP_PASSWORD
SHENZHOUHR_DB_POOL_SIZE=12
SHENZHOUHR_DB_MIN_IDLE=2
SHENZHOUHR_FLYWAY_ENABLED=false
SHENZHOUHR_FLYWAY_USERNAME=$APP_USER
SHENZHOUHR_FLYWAY_PASSWORD=$APP_PASSWORD
SHENZHOUHR_SESSION_COOKIE_SECURE=$SESSION_COOKIE_SECURE
SHENZHOUHR_PROVISIONING_PEPPER=$PROVISIONING_PEPPER
SHENZHOUHR_PROVISIONING_KEY_ID=$PROVISIONING_KEY_ID
SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=$PROVISIONING_RECOVERY_WINDOW
DELI_EPLUS_ENABLED=false
SHENZHOUHR_DELI_AUTO_SYNC_ENABLED=false
OA_MYSQL_ENABLED=false
OA_MYSQL_JDBC_URL=
OA_MYSQL_USERNAME=
OA_MYSQL_PASSWORD=
OA_MYSQL_MAX_POOL_SIZE=2
SHENZHOUHR_PAYROLL_RESERVATION_ENABLED=false"

write_env "$MIGRATOR_ENV_FILE" "SPRING_PROFILES_ACTIVE=prod
SHENZHOUHR_SERVER_ADDRESS=$BACKEND_ADDRESS
SHENZHOUHR_SERVER_PORT=$BACKEND_PORT
SHENZHOUHR_DB_URL='$DB_URL'
SHENZHOUHR_DB_USERNAME=$MIGRATOR_USER
SHENZHOUHR_DB_PASSWORD=$MIGRATOR_PASSWORD
SHENZHOUHR_FLYWAY_ENABLED=true
SHENZHOUHR_FLYWAY_URL='$DB_URL'
SHENZHOUHR_FLYWAY_USERNAME=$MIGRATOR_USER
SHENZHOUHR_FLYWAY_PASSWORD=$MIGRATOR_PASSWORD
SHENZHOUHR_DB_POOL_SIZE=2
SHENZHOUHR_DB_MIN_IDLE=1
SHENZHOUHR_SESSION_COOKIE_SECURE=$SESSION_COOKIE_SECURE
SHENZHOUHR_PROVISIONING_PEPPER=$PROVISIONING_PEPPER
SHENZHOUHR_PROVISIONING_KEY_ID=$PROVISIONING_KEY_ID
SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=$PROVISIONING_RECOVERY_WINDOW
DELI_EPLUS_ENABLED=false
SHENZHOUHR_DELI_AUTO_SYNC_ENABLED=false
OA_MYSQL_ENABLED=false
SHENZHOUHR_PAYROLL_RESERVATION_ENABLED=false"

PROVISION_COMPLETE=1

printf 'MySQL %s and the empty Baota database were verified. Least-privilege users were created.\n' "$MYSQL_VERSION"
printf 'App env: %s\n' "$ENV_FILE"
printf 'Migrator env: %s\n' "$MIGRATOR_ENV_FILE"
