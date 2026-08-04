#!/usr/bin/env bash
set -Eeuo pipefail
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
APP_USER="shenzhouhr_app"
MIGRATOR_USER="shenzhouhr_migrator"
EXPECTED_VERSION="8.0.45"
SESSION_COOKIE_SECURE="true"
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
  --app-user NAME              App DB user (default shenzhouhr_app)
  --migrator-user NAME         Migration DB user (default shenzhouhr_migrator)
  --expected-version VERSION   Exact core version (default 8.0.45)
  --session-cookie-secure BOOL  Cookie secure flag (default true; use false for HTTP-only internal use)
  --mysql-bin PATH             mysql client binary
USAGE
}

valid_identifier() {
  [[ "$1" =~ ^[A-Za-z0-9_]+$ ]]
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
[[ "$EXPECTED_VERSION" =~ ^8\.0\.[0-9]+$ ]] || die "Invalid expected MySQL version: $EXPECTED_VERSION"
[[ "$SESSION_COOKIE_SECURE" == "true" || "$SESSION_COOKIE_SECURE" == "false" ]] \
  || die 'session-cookie-secure must be true or false'
[[ "$ACCOUNT_HOST" =~ ^[A-Za-z0-9._:-]+$ ]] || die 'Unsafe MySQL account host'
[[ "$ACCOUNT_HOST" != "%" ]] || die 'Refusing a wildcard MySQL account host; use the fixed app server IP'

read -r -p 'MySQL root user [root]: ' MYSQL_ROOT_USER
MYSQL_ROOT_USER="${MYSQL_ROOT_USER:-root}"
[[ "$MYSQL_ROOT_USER" =~ ^[A-Za-z0-9._-]+$ ]] || die 'Unsafe MySQL root username'
read -r -s -p 'MySQL root password: ' MYSQL_ROOT_PASSWORD
printf '\n'
[[ -n "$MYSQL_ROOT_PASSWORD" ]] || die 'MySQL root password cannot be empty'

MYSQL_DEFAULTS="$(mktemp /tmp/shenzhouhr-mysql.XXXXXX.cnf)"
trap 'rm -f -- "$MYSQL_DEFAULTS"' EXIT
chmod 600 "$MYSQL_DEFAULTS"
cat > "$MYSQL_DEFAULTS" <<EOF
[client]
user=$MYSQL_ROOT_USER
password=$MYSQL_ROOT_PASSWORD
host=$DB_HOST
port=$DB_PORT
protocol=tcp
EOF
unset MYSQL_ROOT_PASSWORD

MYSQL_VERSION="$($MYSQL_BIN --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names \
  -e 'SELECT SUBSTRING_INDEX(VERSION(), "-", 1)' 2>/dev/null)" || die 'Cannot connect to MySQL with the supplied root account'
[[ "$MYSQL_VERSION" == "$EXPECTED_VERSION" ]] || die "MySQL version is $MYSQL_VERSION; this release expects exactly $EXPECTED_VERSION"

APP_PASSWORD="$(openssl rand -hex 32)" || die 'openssl is required to generate an app password'
MIGRATOR_PASSWORD="$(openssl rand -hex 32)" || die 'openssl is required to generate a migrator password'
provisioning_generate_pepper || die "$PROVISIONING_ENV_ERROR"
PROVISIONING_PEPPER="$PROVISIONING_GENERATED_PEPPER"
PROVISIONING_KEY_ID="v1"
PROVISIONING_RECOVERY_WINDOW="PT1H"
DB_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED"

"$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" --batch --skip-column-names <<SQL
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$APP_USER'@'$ACCOUNT_HOST' IDENTIFIED BY '$APP_PASSWORD';
ALTER USER '$APP_USER'@'$ACCOUNT_HOST' IDENTIFIED BY '$APP_PASSWORD';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$APP_USER'@'$ACCOUNT_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$DB_NAME\`.* TO '$APP_USER'@'$ACCOUNT_HOST';
CREATE USER IF NOT EXISTS '$MIGRATOR_USER'@'$ACCOUNT_HOST' IDENTIFIED BY '$MIGRATOR_PASSWORD';
ALTER USER '$MIGRATOR_USER'@'$ACCOUNT_HOST' IDENTIFIED BY '$MIGRATOR_PASSWORD';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$MIGRATOR_USER'@'$ACCOUNT_HOST';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON \`$DB_NAME\`.* TO '$MIGRATOR_USER'@'$ACCOUNT_HOST';
FLUSH PRIVILEGES;
SQL

write_env "$ENV_FILE" "SPRING_PROFILES_ACTIVE=prod
SHENZHOUHR_SERVER_ADDRESS=127.0.0.1
SHENZHOUHR_SERVER_PORT=8080
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
OA_MYSQL_ENABLED=false
SHENZHOUHR_PAYROLL_RESERVATION_ENABLED=false"

write_env "$MIGRATOR_ENV_FILE" "SPRING_PROFILES_ACTIVE=prod
SHENZHOUHR_SERVER_ADDRESS=127.0.0.1
SHENZHOUHR_SERVER_PORT=8080
SHENZHOUHR_DB_URL='$DB_URL'
SHENZHOUHR_DB_USERNAME=$MIGRATOR_USER
SHENZHOUHR_DB_PASSWORD=$MIGRATOR_PASSWORD
SHENZHOUHR_FLYWAY_ENABLED=true
SHENZHOUHR_FLYWAY_URL='$DB_URL'
SHENZHOUHR_FLYWAY_USERNAME=$MIGRATOR_USER
SHENZHOUHR_FLYWAY_PASSWORD=$MIGRATOR_PASSWORD
SHENZHOUHR_DB_POOL_SIZE=2
SHENZHOUHR_DB_MIN_IDLE=1
SHENZHOUHR_SESSION_COOKIE_SECURE=true
SHENZHOUHR_PROVISIONING_PEPPER=$PROVISIONING_PEPPER
SHENZHOUHR_PROVISIONING_KEY_ID=$PROVISIONING_KEY_ID
SHENZHOUHR_PROVISIONING_RECOVERY_WINDOW=$PROVISIONING_RECOVERY_WINDOW
DELI_EPLUS_ENABLED=false
OA_MYSQL_ENABLED=false
SHENZHOUHR_PAYROLL_RESERVATION_ENABLED=false"

printf 'MySQL %s verified. Database and least-privilege users were created.\n' "$MYSQL_VERSION"
printf 'App env: %s\n' "$ENV_FILE"
printf 'Migrator env: %s\n' "$MIGRATOR_ENV_FILE"
