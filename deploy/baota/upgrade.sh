#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

RELEASE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BAOTA_ROOT="$RELEASE_ROOT/deploy/baota"
APP_ROOT="/opt/shenzhouhr"
APP_DIR="$APP_ROOT/app"
ENV_ROOT="/etc/shenzhouhr"
readonly WEB_ROOT_BASE="/www/wwwroot"

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
      || die 'Release integrity verification failed; do not continue upgrade'
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$RELEASE_ROOT" && shasum -a 256 --check SHA256SUMS >/dev/null) \
      || die 'Release integrity verification failed; do not continue upgrade'
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
  PACKAGE_FLYWAY_MAX="$(sed -n 's/^migration_files=V1\.\.V\([0-9][0-9]*\)$/\1/p' "$manifest" | head -1)"
  [[ "$PACKAGE_MYSQL_EXPECTED" == "8.0.45" ]] \
    || die "Unsupported or missing MySQL release contract: ${PACKAGE_MYSQL_EXPECTED:-unknown}"
  [[ "$PACKAGE_FLYWAY_MAX" =~ ^[1-9][0-9]*$ ]] \
    || die "Invalid or missing Flyway release contract: ${PACKAGE_FLYWAY_MAX:-unknown}"
}

valid_domain() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$ ]] && [[ "$1" != *..* ]]
}

validate_web_root() {
  local expected="$WEB_ROOT_BASE/$DOMAIN"
  [[ "$WEB_ROOT" == "$expected" ]] \
    || die "Frontend directory must be exactly $expected"
  [[ "$WEB_ROOT" != "/" && "$WEB_ROOT" != "/www" && "$WEB_ROOT" != "$WEB_ROOT_BASE" ]] \
    || die "Refusing broad frontend directory: $WEB_ROOT"
  [[ -d /www && ! -L /www ]] \
    || die 'Baota /www directory must be a real directory, not a symbolic link'
  [[ -d "$WEB_ROOT_BASE" && ! -L "$WEB_ROOT_BASE" ]] \
    || die "Baota web root must be a real directory, not a symbolic link: $WEB_ROOT_BASE"
  [[ -d "$WEB_ROOT" && ! -L "$WEB_ROOT" ]] \
    || die "Frontend directory must be a real directory, not a symbolic link: $WEB_ROOT"
}

clear_web_root() {
  validate_web_root
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
  command -v java >/dev/null 2>&1 || die 'Java 21 is required'
  java -version 2>&1 | grep -q 'version "21\.' || die 'Java 21 is required'
  command -v java
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

[[ "$(id -u)" -eq 0 ]] || die 'Please run as root: sudo bash upgrade.sh'
verify_release_integrity
load_release_contract
# The checksum was verified before loading any packaged helper.
# shellcheck source=deploy/baota/scripts/provisioning-env.sh
source "$BAOTA_ROOT/scripts/provisioning-env.sh"
[[ -f "$RELEASE_ROOT/backend/shenzhou-hr.jar" ]] || die 'Release backend jar is missing'
[[ -d "$RELEASE_ROOT/web" ]] || die 'Release frontend files are missing'
[[ -r "$ENV_ROOT/shenzhouhr.env" ]] || die 'Existing app env was not found; use install.sh for first installation'
[[ -r "$ENV_ROOT/shenzhouhr-migrator.env" ]] || die 'Existing migrator env was not found'
[[ -f "$APP_DIR/shenzhou-hr.jar" ]] || die 'Existing application was not found'
command -v systemctl >/dev/null 2>&1 || die 'systemctl is required'
command -v curl >/dev/null 2>&1 || die 'curl is required'

JAVA_BIN="$(detect_java)"
JAVA_MAJOR="$($JAVA_BIN -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAVA_MAJOR" == "21" ]] || die "Java 21 is required; detected: ${JAVA_MAJOR:-unknown} at $JAVA_BIN"
MYSQL_BIN="$(detect_mysql)"
export JAVA_BIN MYSQL_BIN
printf 'Using Java: %s\n' "$JAVA_BIN"
printf 'Using MySQL client: %s\n' "$MYSQL_BIN"

"$BAOTA_ROOT/scripts/database-preflight.sh" \
  --env-file "$ENV_ROOT/shenzhouhr.env" \
  --mysql-bin "$MYSQL_BIN" \
  --expected-mysql-version "$PACKAGE_MYSQL_EXPECTED" \
  --max-flyway-version "$PACKAGE_FLYWAY_MAX"

# Older installations predate deterministic recovery receipts. Converge both
# root-owned env files before Flyway starts the full Spring context. Existing
# valid values are reused; conflicting values stop the upgrade.
provisioning_sync_env_pair \
  "$ENV_ROOT/shenzhouhr.env" \
  "$ENV_ROOT/shenzhouhr-migrator.env" \
  || die "$PROVISIONING_ENV_ERROR"
printf '%s\n' 'Account-provisioning recovery configuration verified.'

set -a
# shellcheck disable=SC1090
source "$ENV_ROOT/shenzhouhr.env"
set +a
BACKEND_PORT="${SHENZHOUHR_SERVER_PORT:-18080}"
[[ "$BACKEND_PORT" =~ ^[0-9]+$ ]] || die "Invalid backend port in app env: $BACKEND_PORT"
((BACKEND_PORT >= 1024 && BACKEND_PORT <= 65535)) \
  || die "Backend port in app env must be between 1024 and 65535: $BACKEND_PORT"

read -r -p 'Customer domain (for example hr.example.com): ' DOMAIN
DOMAIN="${DOMAIN:-${SHENZHOUHR_DOMAIN:-}}"
valid_domain "$DOMAIN" || die "Invalid domain: $DOMAIN"
WEB_ROOT="$WEB_ROOT_BASE/$DOMAIN"
validate_web_root

STAMP="$(date -u +%Y%m%d%H%M%S)"
BACKUP_DIR="$APP_ROOT/backups/upgrade-$STAMP"
mkdir -p "$BACKUP_DIR/web"
cp -a "$APP_DIR/shenzhou-hr.jar" "$BACKUP_DIR/"
cp -a "$WEB_ROOT/." "$BACKUP_DIR/web/"

WAS_ACTIVE=0
if systemctl is-active --quiet shenzhouhr.service; then
  WAS_ACTIVE=1
fi
systemctl stop shenzhouhr.service

install -o shenzhouhr -g shenzhouhr -m 0750 \
  "$RELEASE_ROOT/backend/shenzhou-hr.jar" "$APP_DIR/shenzhou-hr.jar"

printf 'Running database migrations, if this release contains new ones...\n'
JAVA_BIN="$JAVA_BIN" "$BAOTA_ROOT/scripts/migrate.sh" \
  --jar "$APP_DIR/shenzhou-hr.jar" \
  --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env"

clear_web_root
cp -a "$RELEASE_ROOT/web/." "$WEB_ROOT/"
find "$WEB_ROOT" -type d -exec chmod 0755 {} +
find "$WEB_ROOT" -type f -exec chmod 0644 {} +
chown -R www:www "$WEB_ROOT" 2>/dev/null || chown -R www-data:www-data "$WEB_ROOT"

if ((WAS_ACTIVE == 1)); then
  systemctl start shenzhouhr.service
else
  read -r -p 'Application was stopped before upgrade. Start it now? [Y/n]: ' START_APP
  START_APP="${START_APP:-Y}"
  [[ "$START_APP" =~ ^[Yy]$ ]] && systemctl start shenzhouhr.service
fi

if ((WAS_ACTIVE == 1)) || [[ "${START_APP:-Y}" =~ ^[Yy]$ ]]; then
  printf 'Waiting for backend health endpoint...\n'
  for attempt in {1..30}; do
    if curl --fail --silent --show-error --max-time 3 \
        "http://127.0.0.1:$BACKEND_PORT/actuator/health" | grep -q '"status":"UP"'; then
      break
    fi
    if [[ "$attempt" -eq 30 ]]; then
      systemctl --no-pager --full status shenzhouhr.service || true
      journalctl -u shenzhouhr.service -n 80 --no-pager || true
      die "Upgrade finished copying files, but backend is not healthy. Backup: $BACKUP_DIR"
    fi
    sleep 2
  done
  "$BAOTA_ROOT/scripts/verify.sh" \
    --env-file "$ENV_ROOT/shenzhouhr.env" \
    --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env" \
    --health-url "http://127.0.0.1:$BACKEND_PORT/actuator/health"
else
  printf 'Upgrade completed; application remains stopped by choice.\n'
fi
systemctl reload nginx 2>/dev/null || true

printf '\nUpgrade completed.\n'
printf 'Backup: %s\n' "$BACKUP_DIR"
printf 'Service: systemctl status shenzhouhr\n'
