#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

RELEASE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BAOTA_ROOT="$RELEASE_ROOT/deploy/baota"
APP_ROOT="/opt/shenzhouhr"
APP_DIR="$APP_ROOT/app"
ENV_ROOT="/etc/shenzhouhr"
BACKEND_PORT="8080"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

valid_domain() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$ ]] && [[ "$1" != *..* ]]
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

[[ "$(id -u)" -eq 0 ]] || die 'Please run as root: sudo bash upgrade.sh'
[[ -f "$RELEASE_ROOT/backend/shenzhou-hr.jar" ]] || die 'Release backend jar is missing'
[[ -d "$RELEASE_ROOT/web" ]] || die 'Release frontend files are missing'
[[ -r "$ENV_ROOT/shenzhouhr.env" ]] || die 'Existing app env was not found; use install.sh for first installation'
[[ -r "$ENV_ROOT/shenzhouhr-migrator.env" ]] || die 'Existing migrator env was not found'
[[ -f "$APP_DIR/shenzhou-hr.jar" ]] || die 'Existing application was not found'
command -v systemctl >/dev/null 2>&1 || die 'systemctl is required'
command -v curl >/dev/null 2>&1 || die 'curl is required'

read -r -p 'Customer domain (for example hr.example.com): ' DOMAIN
DOMAIN="${DOMAIN:-${SHENZHOUHR_DOMAIN:-}}"
valid_domain "$DOMAIN" || die "Invalid domain: $DOMAIN"
WEB_ROOT="${WEB_ROOT:-/www/wwwroot/$DOMAIN}"
[[ -d "$WEB_ROOT" ]] || die "Frontend directory not found: $WEB_ROOT"

JAVA_BIN="$(detect_java)"
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

find "$WEB_ROOT" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
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
  "$BAOTA_ROOT/scripts/verify.sh" --env-file "$ENV_ROOT/shenzhouhr.env"
else
  printf 'Upgrade completed; application remains stopped by choice.\n'
fi
systemctl reload nginx 2>/dev/null || true

printf '\nUpgrade completed.\n'
printf 'Backup: %s\n' "$BACKUP_DIR"
printf 'Service: systemctl status shenzhouhr\n'
