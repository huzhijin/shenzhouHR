#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

RELEASE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BAOTA_ROOT="$RELEASE_ROOT/deploy/baota"
APP_ROOT="/opt/shenzhouhr"
APP_DIR="$APP_ROOT/app"
readonly WEB_ROOT_BASE="/www/wwwroot"
ENV_ROOT="/etc/shenzhouhr"
BACKEND_PORT="${BACKEND_PORT:-18080}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

verify_release_integrity() {
  local checksum_file="$RELEASE_ROOT/SHA256SUMS"
  [[ -f "$checksum_file" && ! -L "$checksum_file" ]] \
    || die "Release checksum manifest is missing or unsafe: $checksum_file"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$RELEASE_ROOT" && sha256sum --check --quiet SHA256SUMS) \
      || die 'Release integrity verification failed; do not continue installation'
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$RELEASE_ROOT" && shasum -a 256 --check SHA256SUMS >/dev/null) \
      || die 'Release integrity verification failed; do not continue installation'
  else
    die 'sha256sum or shasum is required to verify the release package'
  fi
  printf '%s\n' 'Release integrity verification passed.'
}

load_release_contract() {
  local manifest="$RELEASE_ROOT/BUILD-MANIFEST.txt"
  [[ -f "$manifest" && ! -L "$manifest" ]] \
    || die "Release manifest is missing or unsafe: $manifest"
  PACKAGE_MYSQL_EXPECTED="$(sed -n 's/^mysql_expected=//p' "$manifest" | head -1)"
  [[ "$PACKAGE_MYSQL_EXPECTED" == "8.0.45" ]] \
    || die "Unsupported or missing MySQL release contract: ${PACKAGE_MYSQL_EXPECTED:-unknown}"
}

need_root() {
  [[ "$(id -u)" -eq 0 ]] || die 'Please run as root: sudo bash install.sh'
}

valid_domain() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$ ]] && [[ "$1" != *..* ]]
}

validate_web_root() {
  local require_existing="${1:-false}"
  local expected="$WEB_ROOT_BASE/$DOMAIN"
  [[ "$WEB_ROOT" == "$expected" ]] \
    || die "Frontend directory must be exactly $expected"
  [[ "$WEB_ROOT" != "/" && "$WEB_ROOT" != "/www" && "$WEB_ROOT" != "$WEB_ROOT_BASE" ]] \
    || die "Refusing broad frontend directory: $WEB_ROOT"
  [[ -d /www && ! -L /www ]] \
    || die 'Baota /www directory must be a real directory, not a symbolic link'
  [[ -d "$WEB_ROOT_BASE" && ! -L "$WEB_ROOT_BASE" ]] \
    || die "Baota web root must be a real directory, not a symbolic link: $WEB_ROOT_BASE"
  if [[ -e "$WEB_ROOT" || -L "$WEB_ROOT" ]]; then
    [[ -d "$WEB_ROOT" && ! -L "$WEB_ROOT" ]] \
      || die "Frontend directory must be a real directory, not a symbolic link: $WEB_ROOT"
  elif [[ "$require_existing" == "true" ]]; then
    die "Frontend directory was not found: $WEB_ROOT"
  fi
}

clear_web_root() {
  validate_web_root true
  find "$WEB_ROOT" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
}

detect_java() {
  if [[ -n "${JAVA_BIN:-}" && -x "$JAVA_BIN" ]]; then
    printf '%s\n' "$JAVA_BIN"
    return
  fi
  local candidate
  for candidate in /usr/bin/java /www/server/java/*/bin/java; do
    [[ -x "$candidate" ]] || continue
    if "$candidate" -version 2>&1 | grep -q 'version "21\.'; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  command -v java >/dev/null 2>&1 || die 'Java 21 is required; install JDK 21 in Baota first'
  java -version 2>&1 | grep -q 'version "21\.' || die 'Java 21 is required; install JDK 21 in Baota first'
  command -v java
}

detect_nginx() {
  if [[ -n "${NGINX_BIN:-}" && -x "$NGINX_BIN" ]]; then
    printf '%s\n' "$NGINX_BIN"
    return
  fi
  for candidate in /www/server/nginx/sbin/nginx /usr/sbin/nginx /usr/bin/nginx; do
    [[ -x "$candidate" ]] && { printf '%s\n' "$candidate"; return; }
  done
  die 'Nginx not found; install Nginx in Baota first'
}

detect_mysql() {
  local candidate
  if [[ -n "${MYSQL_BIN:-}" ]]; then
    candidate="$(command -v "$MYSQL_BIN" 2>/dev/null || true)"
    [[ -n "$candidate" && -x "$candidate" ]] || die "MySQL client not found: $MYSQL_BIN"
    printf '%s\n' "$candidate"
    return
  fi
  for candidate in /www/server/mysql/bin/mysql /usr/bin/mysql /usr/local/bin/mysql; do
    [[ -x "$candidate" ]] && { printf '%s\n' "$candidate"; return; }
  done
  command -v mysql >/dev/null 2>&1 || die 'MySQL client not found; install MySQL in Baota first'
  command -v mysql
}

need_root
verify_release_integrity
load_release_contract
[[ -f "$RELEASE_ROOT/backend/shenzhou-hr.jar" ]] || die 'Release backend jar is missing'
[[ -d "$RELEASE_ROOT/web" ]] || die 'Release frontend files are missing'
[[ -d "$RELEASE_ROOT/db/migration" ]] || die 'Release migration files are missing'
command -v systemctl >/dev/null 2>&1 || die 'systemd/systemctl is required on the customer server'
command -v curl >/dev/null 2>&1 || die 'curl is required'
command -v openssl >/dev/null 2>&1 || die 'openssl is required'
command -v ss >/dev/null 2>&1 || die 'ss is required to verify the private backend port'
[[ "$BACKEND_PORT" =~ ^[0-9]+$ ]] || die "Invalid backend port: $BACKEND_PORT"
((BACKEND_PORT >= 1024 && BACKEND_PORT <= 65535)) \
  || die "Backend port must be between 1024 and 65535: $BACKEND_PORT"
[[ ! -e "$ENV_ROOT/shenzhouhr.env" && ! -e "$ENV_ROOT/shenzhouhr-migrator.env" ]] \
  || die "Existing ShenzhouHR environment found under $ENV_ROOT; use upgrade.sh or stop and diagnose an interrupted installation."
if ss -H -ltn "sport = :$BACKEND_PORT" | grep -q .; then
  die "Backend port $BACKEND_PORT is already in use; choose a free private port before installation"
fi

read -r -p "Customer domain (for example hr.example.com): " DOMAIN
DOMAIN="${DOMAIN:-${SHENZHOUHR_DOMAIN:-}}"
valid_domain "$DOMAIN" || die "Invalid domain: $DOMAIN"
WEB_ROOT="$WEB_ROOT_BASE/$DOMAIN"
validate_web_root false

read -r -p 'Enable HTTPS for this site? [y/N]: ' ENABLE_SSL
ENABLE_SSL="${ENABLE_SSL:-N}"
if [[ "$ENABLE_SSL" =~ ^[Yy]$ ]]; then
  SESSION_COOKIE_SECURE="true"
else
  SESSION_COOKIE_SECURE="false"
fi

read -r -p 'MySQL host [127.0.0.1]: ' DB_HOST
DB_HOST="${DB_HOST:-127.0.0.1}"
read -r -p 'MySQL port [3306]: ' DB_PORT
DB_PORT="${DB_PORT:-3306}"
read -r -p 'Database name [shenzhou_hr]: ' DB_NAME
DB_NAME="${DB_NAME:-shenzhou_hr}"
read -r -p 'MySQL account host [127.0.0.1]: ' ACCOUNT_HOST
ACCOUNT_HOST="${ACCOUNT_HOST:-127.0.0.1}"

JAVA_BIN="$(detect_java)"
JAVA_MAJOR="$($JAVA_BIN -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAVA_MAJOR" == "21" ]] || die "Java 21 is required; detected: ${JAVA_MAJOR:-unknown} at $JAVA_BIN"
JAR_BIN="${JAR_BIN:-$(dirname -- "$JAVA_BIN")/jar}"
[[ -x "$JAR_BIN" ]] || die "A full JDK 21 is required; jar tool not found next to Java: $JAR_BIN"
JAR_MAJOR="$($JAR_BIN --version 2>&1 | sed -n 's/^jar \([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAR_MAJOR" == "21" ]] || die "JDK 21 jar tool is required; detected: ${JAR_MAJOR:-unknown} at $JAR_BIN"
MYSQL_BIN="$(detect_mysql)"
export JAVA_BIN JAR_BIN MYSQL_BIN
NGINX_BIN="$(detect_nginx)"

printf 'Using Java: %s\n' "$JAVA_BIN"
printf 'Using JAR tool: %s\n' "$JAR_BIN"
printf 'Using MySQL client: %s\n' "$MYSQL_BIN"
printf 'Using private backend port: %s\n' "$BACKEND_PORT"

if [[ -d /www/server/panel/vhost/nginx ]]; then
  NGINX_VHOST_DIR="/www/server/panel/vhost/nginx"
elif [[ -d /www/server/nginx/conf/vhost ]]; then
  NGINX_VHOST_DIR="/www/server/nginx/conf/vhost"
else
  die 'Baota Nginx vhost directory was not found'
fi
if [[ -f "$NGINX_VHOST_DIR/$DOMAIN.conf" ]]; then
  VHOST_CONFIG="$NGINX_VHOST_DIR/$DOMAIN.conf"
else
  VHOST_CONFIG="$NGINX_VHOST_DIR/shenzhouhr.conf"
fi

CERT_DIR="/www/server/panel/vhost/cert/$DOMAIN"
SSL_CERT="${SSL_CERT:-$CERT_DIR/fullchain.pem}"
SSL_KEY="${SSL_KEY:-$CERT_DIR/privkey.pem}"
if [[ "$SESSION_COOKIE_SECURE" == "true" ]]; then
  [[ -r "$SSL_CERT" && -r "$SSL_KEY" ]] || die "SSL certificate not found. In Baota, issue/enable HTTPS for $DOMAIN first, then rerun. Expected: $CERT_DIR/fullchain.pem and $CERT_DIR/privkey.pem"
  NGINX_TEMPLATE="$BAOTA_ROOT/nginx/shenzhouhr-site.conf"
else
  NGINX_TEMPLATE="$BAOTA_ROOT/nginx/shenzhouhr-site-http.conf"
  printf '%s\n' 'Internal HTTP mode selected: HTTPS certificate is not required.'
fi

if ! getent group shenzhouhr >/dev/null 2>&1; then
  groupadd --system shenzhouhr
fi
if ! id shenzhouhr >/dev/null 2>&1; then
  useradd --system --gid shenzhouhr --home-dir "$APP_ROOT" --shell /usr/sbin/nologin shenzhouhr
fi

INSTALL_STAMP="$(date -u +%Y%m%d%H%M%S)"
BACKUP_DIR="$APP_ROOT/backups/$INSTALL_STAMP"
mkdir -p "$APP_DIR" "$WEB_ROOT" "$ENV_ROOT" "$BACKUP_DIR"
validate_web_root true
if [[ -f "$APP_DIR/shenzhou-hr.jar" ]]; then
  cp -a "$APP_DIR/shenzhou-hr.jar" "$BACKUP_DIR/"
fi
if [[ -d "$WEB_ROOT" && -n "$(find "$WEB_ROOT" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  mkdir -p "$BACKUP_DIR/web"
  cp -a "$WEB_ROOT/." "$BACKUP_DIR/web/"
fi
if [[ -f "$VHOST_CONFIG" ]]; then
  cp -a "$VHOST_CONFIG" "$BACKUP_DIR/"
fi

"$BAOTA_ROOT/mysql/provision.sh" \
  --env-file "$ENV_ROOT/shenzhouhr.env" \
  --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env" \
  --db-host "$DB_HOST" \
  --db-port "$DB_PORT" \
  --db-name "$DB_NAME" \
  --account-host "$ACCOUNT_HOST" \
  --backend-port "$BACKEND_PORT" \
  --expected-version "$PACKAGE_MYSQL_EXPECTED" \
  --session-cookie-secure "$SESSION_COOKIE_SECURE"

install -o shenzhouhr -g shenzhouhr -m 0750 "$RELEASE_ROOT/backend/shenzhou-hr.jar" "$APP_DIR/shenzhou-hr.jar"
install -o root -g root -m 0644 "$RELEASE_ROOT/db/migration/"*.sql "$APP_DIR/" 2>/dev/null || true

"$BAOTA_ROOT/scripts/migrate.sh" \
  --jar "$APP_DIR/shenzhou-hr.jar" \
  --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env"

read -r -p 'Create the first production administrator now? [Y/n]: ' CREATE_ADMIN
CREATE_ADMIN="${CREATE_ADMIN:-Y}"
if [[ "$CREATE_ADMIN" =~ ^[Yy]$ ]]; then
  JAVA_BIN="$JAVA_BIN" "$BAOTA_ROOT/scripts/initial-admin.sh" \
    --jar "$APP_DIR/shenzhou-hr.jar" \
    --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env"
fi

clear_web_root
cp -a "$RELEASE_ROOT/web/." "$WEB_ROOT/"
find "$WEB_ROOT" -type d -exec chmod 0755 {} +
find "$WEB_ROOT" -type f -exec chmod 0644 {} +
chown -R www:www "$WEB_ROOT" 2>/dev/null || chown -R www-data:www-data "$WEB_ROOT"

chown root:shenzhouhr "$ENV_ROOT/shenzhouhr.env"
chmod 0640 "$ENV_ROOT/shenzhouhr.env"
chown root:root "$ENV_ROOT/shenzhouhr-migrator.env"
chmod 0600 "$ENV_ROOT/shenzhouhr-migrator.env"
chown -R shenzhouhr:shenzhouhr "$APP_ROOT"
chmod 0750 "$APP_ROOT" "$APP_DIR"

sed -e "s|__DOMAIN__|$DOMAIN|g" \
  -e "s|__WEB_ROOT__|$WEB_ROOT|g" \
  -e "s|__SSL_CERT__|$SSL_CERT|g" \
  -e "s|__SSL_KEY__|$SSL_KEY|g" \
  -e "s|__BACKEND_PORT__|$BACKEND_PORT|g" \
  "$NGINX_TEMPLATE" > "$VHOST_CONFIG"
chmod 0644 "$VHOST_CONFIG"
"$NGINX_BIN" -t

sed "s|__JAVA_BIN__|$JAVA_BIN|g" "$BAOTA_ROOT/systemd/shenzhouhr.service" > /etc/systemd/system/shenzhouhr.service
chmod 0644 /etc/systemd/system/shenzhouhr.service
systemctl daemon-reload
systemctl enable --now shenzhouhr.service

printf 'Waiting for backend health endpoint...\n'
for attempt in {1..30}; do
  if curl --fail --silent --show-error --max-time 3 http://127.0.0.1:$BACKEND_PORT/actuator/health | grep -q '"status":"UP"'; then
    break
  fi
  if [[ "$attempt" -eq 30 ]]; then
    systemctl --no-pager --full status shenzhouhr.service || true
    journalctl -u shenzhouhr.service -n 80 --no-pager || true
    die 'Backend did not become healthy'
  fi
  sleep 2
done

"$BAOTA_ROOT/scripts/verify.sh" \
  --env-file "$ENV_ROOT/shenzhouhr.env" \
  --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env" \
  --health-url "http://127.0.0.1:$BACKEND_PORT/actuator/health"
systemctl reload nginx

printf '\nDeployment completed.\n'
if [[ "$SESSION_COOKIE_SECURE" == "true" ]]; then
  printf 'Open: https://%s\n' "$DOMAIN"
else
  printf 'Open: http://%s\n' "$DOMAIN"
fi
printf 'Backend: http://127.0.0.1:%s (not publicly exposed)\n' "$BACKEND_PORT"
printf 'Service: systemctl status shenzhouhr\n'
printf 'Logs: journalctl -u shenzhouhr -f\n'
printf 'Backup: %s\n' "$BACKUP_DIR"
printf '%s\n' 'Opening business data is not embedded in the code package.'
printf '%s\n' 'Import approved organization, employee and employment files separately after login.'
