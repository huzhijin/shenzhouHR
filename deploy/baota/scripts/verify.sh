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
readonly CUSTOMER_DOMAIN="192.168.160.226"
readonly CUSTOMER_SITE_PORT="23272"
readonly BAOTA_NGINX_BIN="/www/server/nginx/sbin/nginx"
readonly BAOTA_NGINX_CONFIG="/www/server/nginx/conf/nginx.conf"
readonly BAOTA_PROXY_METADATA="/www/server/panel/data/proxyfile.json"
readonly BAOTA_PROXY_ROOT="/www/server/panel/vhost/nginx/proxy"
readonly CUSTOMER_PROXY_NAME="kaoqin-api"
readonly CUSTOMER_PROXY_TARGET="http://127.0.0.1:8080/api"

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

detect_python() {
  local candidate version
  for candidate in /usr/bin/python3 /www/server/panel/pyenv/bin/python3; do
    [[ -x "$candidate" ]] || continue
    version="$($candidate -c 'import sys; print(sys.version_info.major)' 2>/dev/null || true)"
    [[ "$version" == "3" ]] && { printf '%s\n' "$candidate"; return; }
  done
  command -v python3 >/dev/null 2>&1 \
    || die 'Python 3 is required to validate the BaoTa reverse-proxy record'
  command -v python3
}

cleanup_verify() {
  [[ -z "${MYSQL_DEFAULTS:-}" ]] || rm -f -- "$MYSQL_DEFAULTS"
  [[ -z "${NGINX_DUMP:-}" ]] || rm -f -- "$NGINX_DUMP"
  [[ -z "${API_HEADERS:-}" ]] || rm -f -- "$API_HEADERS"
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
  EXPECTED_FLYWAY_VERSION="${EXPECTED_FLYWAY_VERSION:-35}"
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
APP_SERVER_PORT="${SHENZHOUHR_SERVER_PORT:-8080}"
[[ "$APP_SERVER_PORT" =~ ^[0-9]+$ ]] \
  || die "Invalid application server port: $APP_SERVER_PORT"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:$APP_SERVER_PORT/actuator/health}"

printf 'Checking BaoTa site and panel-managed reverse proxy...\n'
[[ -x "$BAOTA_NGINX_BIN" && -f "$BAOTA_NGINX_CONFIG" \
    && ! -L "$BAOTA_NGINX_CONFIG" ]] \
  || die 'BaoTa-managed Nginx is missing or unsafe'
VHOST_CONFIG="/www/server/panel/vhost/nginx/$CUSTOMER_DOMAIN.conf"
[[ -f "$VHOST_CONFIG" && ! -L "$VHOST_CONFIG" ]] \
  || die "BaoTa site was not found: $VHOST_CONFIG"
grep -Fq "include $BAOTA_PROXY_ROOT/$CUSTOMER_DOMAIN/*.conf;" "$VHOST_CONFIG" \
  || die 'BaoTa site does not load its panel-managed reverse-proxy rule'
PYTHON_BIN="$(detect_python)"
PROXY_CONFIG="$("$PYTHON_BIN" "$SCRIPT_DIR/baota-proxy.py" \
  --metadata-file "$BAOTA_PROXY_METADATA" \
  --proxy-root "$BAOTA_PROXY_ROOT" \
  --site "$CUSTOMER_DOMAIN" \
  --proxy-name "$CUSTOMER_PROXY_NAME" \
  --expected-target "$CUSTOMER_PROXY_TARGET" \
  --require-marker)" \
  || die "BaoTa reverse-proxy record $CUSTOMER_PROXY_NAME is missing or invalid"
NGINX_DUMP="$(mktemp /tmp/shenzhouhr-nginx-verify.XXXXXX)"
API_HEADERS="$(mktemp /tmp/shenzhouhr-api-headers.XXXXXX)"
chmod 600 "$NGINX_DUMP" "$API_HEADERS"
trap cleanup_verify EXIT
"$BAOTA_NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -T >"$NGINX_DUMP" 2>&1 \
  || die 'BaoTa Nginx effective configuration could not be read'
[[ "$(grep -F -c '# SHENZHOUHR-BAOTA-PROXY-V1' "$NGINX_DUMP")" == "1" ]] \
  || die 'Effective Nginx configuration must contain exactly one managed ShenzhouHR proxy rule'
[[ "$(grep -F -c 'proxy_pass http://127.0.0.1:8080/api/;' "$NGINX_DUMP")" == "1" ]] \
  || die 'Effective Nginx configuration must contain exactly one /api to 8080 upstream'

MYSQL_BIN_RESOLVED="$(command -v "$MYSQL_BIN" 2>/dev/null || true)"
[[ -n "$MYSQL_BIN_RESOLVED" && -x "$MYSQL_BIN_RESOLVED" ]] \
  || die "MySQL client not found: $MYSQL_BIN"
MYSQL_BIN="$MYSQL_BIN_RESOLVED"
MYSQL_DEFAULTS="$(mktemp /tmp/shenzhouhr-verify.XXXXXX.cnf)"
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

printf 'Checking SPA and /api routing through port %s...\n' "$CUSTOMER_SITE_PORT"
SPA_STATUS="$(curl --silent --show-error --output /dev/null \
  --write-out '%{http_code}' --max-time 10 \
  --header "Host: $CUSTOMER_DOMAIN" \
  "http://127.0.0.1:$CUSTOMER_SITE_PORT/")" \
  || die 'Static site request through BaoTa Nginx failed'
[[ "$SPA_STATUS" == "200" ]] \
  || die "Static site returned HTTP $SPA_STATUS instead of 200"
API_STATUS="$(curl --silent --show-error --output /dev/null \
  --dump-header "$API_HEADERS" --write-out '%{http_code}' --max-time 10 \
  --header "Host: $CUSTOMER_DOMAIN" \
  "http://127.0.0.1:$CUSTOMER_SITE_PORT/api/v1/auth/session")" \
  || die 'API request through the BaoTa reverse proxy failed'
[[ "$API_STATUS" == "401" ]] \
  || die "Unauthenticated API probe returned HTTP $API_STATUS instead of 401"
grep -Eiq '^Cache-Control:[[:space:]].*no-store' "$API_HEADERS" \
  || die 'API response is missing Cache-Control: no-store'
grep -Eiq '^X-Content-Type-Options:[[:space:]]*nosniff' "$API_HEADERS" \
  || die 'API response is missing the Nginx security headers'

printf 'Checking four-company administrator bootstrap...\n'
BOOTSTRAP_CHECK_OUTPUT="$("$MYSQL_BIN" --defaults-extra-file="$MYSQL_DEFAULTS" \
  --batch --skip-column-names \
  -e "SELECT
    (SELECT COUNT(*) FROM company),
    (SELECT COUNT(*) FROM company WHERE status = 'ACTIVE' AND (
      (BINARY code = 'SZSZ' AND BINARY name = '上海昇州半导体科技有限公司') OR
      (BINARY code = 'SZJN' AND BINARY name = '上海晟州聚能半导体科技有限公司') OR
      (BINARY code = 'SZSC' AND BINARY name = '江苏神州半导体科技股份有限公司') OR
      (BINARY code = 'SZXY' AND BINARY name = '江苏芯越半导体科技有限公司')
    )),
    (SELECT COUNT(*) FROM auth_principal),
    (SELECT COUNT(*) FROM auth_principal
      WHERE principal_id = '20000000-0000-0000-0000-000000000001'
        AND employee_id IS NULL AND status = 'ACTIVE' AND row_version = 0),
    (SELECT COUNT(*) FROM local_account),
    (SELECT COUNT(*) FROM local_account WHERE status = 'ACTIVE'),
    (SELECT COUNT(*) FROM password_credential),
    (SELECT COUNT(*) FROM login_failure_window),
    (SELECT COUNT(*) FROM auth_data_scope),
    (SELECT COUNT(*) FROM auth_principal_role_assignment),
    (SELECT COUNT(*) FROM (
      SELECT scope.company_id, assignment.principal_id
      FROM auth_data_scope scope
      JOIN auth_principal_role_assignment assignment
        ON assignment.data_scope_id = scope.scope_id
      JOIN auth_role role ON role.role_id = assignment.role_id
      JOIN local_account account ON account.principal_id = assignment.principal_id
      WHERE scope.scope_type = 'COMPANY'
        AND scope.company_id IS NOT NULL
        AND scope.organization_id IS NULL
        AND scope.include_descendants = TRUE
        AND scope.valid_to IS NULL
        AND assignment.valid_to IS NULL
        AND account.status = 'ACTIVE'
        AND role.role_code IN ('SYSTEM_ADMIN', 'HR_ADMIN')
      GROUP BY scope.company_id, assignment.principal_id
      HAVING COUNT(*) = 2 AND COUNT(DISTINCT role.role_code) = 2
    ) verified_company_admin_roles);" \
  "$DB_NAME_VALUE" 2>/dev/null)" || die 'Four-company administrator verification failed'
EXPECTED_BOOTSTRAP_STATUS=$'4\t4\t2\t1\t1\t1\t1\t1\t4\t8\t4'
[[ "$BOOTSTRAP_CHECK_OUTPUT" == "$EXPECTED_BOOTSTRAP_STATUS" ]] \
  || die "Four-company administrator state differs from the release contract: ${BOOTSTRAP_CHECK_OUTPUT:-empty}"
printf '%s\n' "$BOOTSTRAP_CHECK_OUTPUT"

printf '%s\n' 'Verification passed.'
