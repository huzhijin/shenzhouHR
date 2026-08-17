#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly CUSTOMER_DOMAIN="192.168.160.226"
readonly CUSTOMER_SITE_PORT="23272"
readonly CUSTOMER_WEB_ROOT="/www/wwwroot/192.168.160.226"
readonly VHOST_CONFIG="/www/server/panel/vhost/nginx/192.168.160.226.conf"
readonly PROXY_INCLUDE="/www/server/panel/vhost/nginx/proxy/192.168.160.226/*.conf"
readonly BAOTA_NGINX_BIN="/www/server/nginx/sbin/nginx"
readonly BAOTA_NGINX_CONFIG="/www/server/nginx/conf/nginx.conf"
readonly BACKUP_ROOT="/opt/shenzhouhr/backups"
MODE="check"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: repair-spa-routing.sh [--check | --apply]

  --check  Validate the fixed BaoTa site and report whether a repair is needed.
           This is the default and never changes the vhost.
  --apply  Back up the exact registered vhost, apply the narrow SPA fallback
           repair, test/reload Nginx, and verify /workbench plus /api.

The script only touches the registered internal 192.168.160.226:23272 site.
It never creates a site or changes an external/NAT port mapping.
EOF
}

while (($#)); do
  case "$1" in
    --check) MODE="check"; shift ;;
    --apply) MODE="apply"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

detect_python() {
  local candidate version
  for candidate in /usr/bin/python3 /www/server/panel/pyenv/bin/python3; do
    [[ -x "$candidate" ]] || continue
    version="$($candidate -c 'import sys; print(sys.version_info.major)' 2>/dev/null || true)"
    [[ "$version" == "3" ]] && { printf '%s\n' "$candidate"; return; }
  done
  command -v python3 >/dev/null 2>&1 \
    || die 'Python 3 is required to validate the BaoTa vhost'
  command -v python3
}

require_safe_root_file() {
  local path="$1" owner mode
  [[ -f "$path" && ! -L "$path" ]] \
    || die "Required file is missing or is a symbolic link: $path"
  owner="$(stat -c %u "$path")"
  mode="$(stat -c %a "$path")"
  [[ "$owner" == "0" ]] || die "File must be owned by root: $path"
  (( (8#$mode & 022) == 0 )) \
    || die "File must not be writable by group or other: $path (mode $mode)"
}

require_safe_root_directory() {
  local path="$1" owner mode
  [[ -d "$path" && ! -L "$path" ]] \
    || die "Required directory is missing or is a symbolic link: $path"
  owner="$(stat -c %u "$path")"
  mode="$(stat -c %a "$path")"
  [[ "$owner" == "0" ]] || die "Directory must be owned by root: $path"
  (( (8#$mode & 022) == 0 )) \
    || die "Directory must not be writable by group or other: $path (mode $mode)"
}

verify_live_routes() {
  local attempt root_status route_result route_status route_type
  local asset_status api_status
  for attempt in 1 2 3 4 5; do
    root_status="$(curl --silent --show-error --output /dev/null \
      --write-out '%{http_code}' --max-time 10 \
      --header "Host: $CUSTOMER_DOMAIN" \
      "http://127.0.0.1:$CUSTOMER_SITE_PORT/" 2>/dev/null || true)"
    route_result="$(curl --silent --show-error --output /dev/null \
      --write-out '%{http_code}\t%{content_type}' --max-time 10 \
      --header "Host: $CUSTOMER_DOMAIN" \
      "http://127.0.0.1:$CUSTOMER_SITE_PORT/workbench" 2>/dev/null || true)"
    route_status="${route_result%%$'\t'*}"
    route_type="${route_result#*$'\t'}"
    asset_status="$(curl --silent --show-error --output /dev/null \
      --write-out '%{http_code}' --max-time 10 \
      --header "Host: $CUSTOMER_DOMAIN" \
      "http://127.0.0.1:$CUSTOMER_SITE_PORT/assets/__shenzhouhr_missing_probe__.js" \
      2>/dev/null || true)"
    api_status="$(curl --silent --show-error --output /dev/null \
      --write-out '%{http_code}' --max-time 10 \
      --header "Host: $CUSTOMER_DOMAIN" \
      "http://127.0.0.1:$CUSTOMER_SITE_PORT/api/v1/auth/session" \
      2>/dev/null || true)"
    if [[ "$root_status" == "200" \
        && "$route_status" == "200" \
        && "$route_type" == text/html* \
        && "$asset_status" == "404" \
        && "$api_status" == "401" ]]; then
      return 0
    fi
    ((attempt == 5)) || sleep 1
  done
  die "Route verification failed after 5 attempts: root=${root_status:-unavailable}, workbench=${route_status:-unavailable}, content-type=${route_type:-unavailable}, missing-asset=${asset_status:-unavailable}, unauthenticated-api=${api_status:-unavailable}"
}

[[ "$(id -u)" -eq 0 ]] \
  || die 'Run as root from a checksum-verified customer release directory'
command -v curl >/dev/null 2>&1 || die 'curl is required'
[[ -x "$BAOTA_NGINX_BIN" ]] \
  || die "BaoTa-managed Nginx was not found: $BAOTA_NGINX_BIN"
require_safe_root_file "$BAOTA_NGINX_CONFIG"
require_safe_root_file "$VHOST_CONFIG"
require_safe_root_directory "$(dirname -- "$VHOST_CONFIG")"
[[ -f "$CUSTOMER_WEB_ROOT/index.html" \
    && ! -L "$CUSTOMER_WEB_ROOT/index.html" ]] \
  || die "Frontend index.html is missing or unsafe: $CUSTOMER_WEB_ROOT/index.html"
require_safe_root_file "$SCRIPT_DIR/spa-routing.py"
PYTHON_BIN="$(detect_python)"

STAMP="$(date -u +%Y%m%d%H%M%S)"
CANDIDATE="$(dirname -- "$VHOST_CONFIG")/.${CUSTOMER_DOMAIN}.conf.spa-candidate-$STAMP-$$"
NGINX_DUMP="$(mktemp /tmp/shenzhouhr-spa-nginx.XXXXXX)"
BACKUP_DIR=""
BACKUP_FILE=""
VHOST_REPLACED=0

cleanup_or_rollback() {
  local status=$?
  trap - EXIT
  rm -f -- "$CANDIDATE" "$NGINX_DUMP"
  if ((status != 0)) && [[ "$VHOST_REPLACED" == "1" \
      && -n "$BACKUP_FILE" && -f "$BACKUP_FILE" && ! -L "$BACKUP_FILE" ]]; then
    printf '%s\n' 'Repair failed; restoring the original BaoTa vhost.' >&2
    if cp -a -- "$BACKUP_FILE" "$VHOST_CONFIG" \
        && "$BAOTA_NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -t >/dev/null 2>&1 \
        && "$BAOTA_NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -s reload; then
      printf 'Original vhost restored from %s\n' "$BACKUP_FILE" >&2
    else
      printf 'WARNING: automatic restore failed. Keep %s and stop changing Nginx.\n' \
        "$BACKUP_DIR" >&2
    fi
  fi
  exit "$status"
}
trap cleanup_or_rollback EXIT

"$BAOTA_NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -T >"$NGINX_DUMP" 2>&1 \
  || die 'Current BaoTa Nginx configuration is invalid; no file was changed'
[[ "$(grep -F -c "# configuration file $VHOST_CONFIG:" "$NGINX_DUMP")" == "1" ]] \
  || die "The registered customer vhost is not loaded exactly once: $VHOST_CONFIG"

RESULT="$($PYTHON_BIN "$SCRIPT_DIR/spa-routing.py" \
  --vhost-file "$VHOST_CONFIG" \
  --candidate-file "$CANDIDATE" \
  --expected-port "$CUSTOMER_SITE_PORT" \
  --expected-server-name "$CUSTOMER_DOMAIN" \
  --expected-root "$CUSTOMER_WEB_ROOT" \
  --expected-proxy-include "$PROXY_INCLUDE")" \
  || die 'The vhost is not the fixed ShenzhouHR site; refusing to modify it'
[[ "$RESULT" == "changed" || "$RESULT" == "unchanged" ]] \
  || die "SPA validator returned an unexpected result: $RESULT"

if [[ "$RESULT" == "unchanged" ]]; then
  rm -f -- "$CANDIDATE"
  verify_live_routes
  trap - EXIT
  rm -f -- "$NGINX_DUMP"
  printf '%s\n' 'SPA routing is already correct; /workbench and /api verification passed.'
  exit 0
fi

if [[ "$MODE" == "check" ]]; then
  trap - EXIT
  rm -f -- "$CANDIDATE" "$NGINX_DUMP"
  printf '%s\n' \
    'REPAIR NEEDED: the fixed site is missing the SPA history fallback. No file was changed.'
  printf '%s\n' \
    'After confirming a BaoTa site/file backup, run this script again with --apply.'
  exit 2
fi

require_safe_root_directory "/opt/shenzhouhr"
require_safe_root_directory "$BACKUP_ROOT"
BACKUP_DIR="$BACKUP_ROOT/nginx-spa-$STAMP"
[[ ! -e "$BACKUP_DIR" && ! -L "$BACKUP_DIR" ]] \
  || die "Backup target already exists: $BACKUP_DIR"
install -d -o root -g root -m 0700 "$BACKUP_DIR"
BACKUP_FILE="$BACKUP_DIR/$(basename -- "$VHOST_CONFIG")"
cp -a -- "$VHOST_CONFIG" "$BACKUP_FILE"
chown --reference="$VHOST_CONFIG" "$CANDIDATE"
chmod --reference="$VHOST_CONFIG" "$CANDIDATE"

mv -f -- "$CANDIDATE" "$VHOST_CONFIG"
VHOST_REPLACED=1
"$BAOTA_NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -t
"$BAOTA_NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -s reload
verify_live_routes

VHOST_REPLACED=0
trap - EXIT
rm -f -- "$NGINX_DUMP"
printf '%s\n' 'SPA history fallback repaired; /workbench and /api verification passed.'
printf 'Original vhost backup: %s\n' "$BACKUP_FILE"
printf '%s\n' \
  'External port mapping was not changed. If WAN :23273 is approved for the frontend, it must forward to internal 192.168.160.226:23272.'
