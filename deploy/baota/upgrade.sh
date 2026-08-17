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
readonly CUSTOMER_DOMAIN="192.168.160.226"
readonly SITE_PORT="23272"
readonly BAOTA_NGINX_CONFIG="/www/server/nginx/conf/nginx.conf"
readonly BAOTA_PROXY_METADATA="/www/server/panel/data/proxyfile.json"
readonly BAOTA_PROXY_ROOT="/www/server/panel/vhost/nginx/proxy"
readonly CUSTOMER_PROXY_NAME="kaoqin-api"
readonly CUSTOMER_PROXY_TARGET="http://127.0.0.1:8080/api"
PROCESS_MANAGER="${PROCESS_MANAGER:-baota}"

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

verify_release_permissions() {
  local current owner mode unsafe
  [[ -d "$RELEASE_ROOT" && ! -L "$RELEASE_ROOT" ]] \
    || die "Release root must be a real directory: $RELEASE_ROOT"
  unsafe="$(find "$RELEASE_ROOT" -xdev \
    \( -type l -o ! -user root -o -perm /022 \) -print -quit)"
  [[ -z "$unsafe" ]] \
    || die "Release contains a symlink, non-root owner, or group/other-writable path: $unsafe"
  current="$RELEASE_ROOT"
  while [[ "$current" != "/" ]]; do
    [[ -d "$current" && ! -L "$current" ]] \
      || die "Release parent must be a real directory: $current"
    owner="$(stat -c %u "$current")"
    mode="$(stat -c %a "$current")"
    [[ "$owner" == "0" ]] || die "Release parent must be owned by root: $current"
    (( (8#$mode & 022) == 0 )) \
      || die "Release parent must not be writable by group or other: $current (mode $mode)"
    current="$(dirname -- "$current")"
  done
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
  local baota_user_ini="$WEB_ROOT/.user.ini"
  if [[ -L "$baota_user_ini" ]]; then
    rm -f -- "$baota_user_ini" \
      || die "Cannot remove Baota .user.ini symbolic link: $baota_user_ini"
  elif [[ -e "$baota_user_ini" ]]; then
    [[ -f "$baota_user_ini" ]] \
      || die "Baota .user.ini is not a regular file: $baota_user_ini"
    if command -v chattr >/dev/null 2>&1; then
      chattr -i -- "$baota_user_ini" 2>/dev/null || true
    fi
    rm -f -- "$baota_user_ini" \
      || die "Cannot remove protected Baota .user.ini: $baota_user_ini"
  fi
  find "$WEB_ROOT" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
}

restore_baota_user_ini() {
  local saved_user_ini="$1"
  [[ -f "$saved_user_ini" && ! -L "$saved_user_ini" ]] || return 0
  install -o root -g root -m 0644 "$saved_user_ini" "$WEB_ROOT/.user.ini"
  if command -v chattr >/dev/null 2>&1; then
    chattr +i -- "$WEB_ROOT/.user.ini" 2>/dev/null \
      || printf 'WARNING: could not restore immutable flag on %s\n' "$WEB_ROOT/.user.ini" >&2
  fi
}

rollback_failed_upgrade() {
  local status=$?
  trap - EXIT
  ((status != 0)) || exit 0
  printf '%s\n' 'Upgrade failed; restoring file/config state where safe.' >&2

  if [[ "${WEB_OLD_RENAMED:-0}" == "1" \
      && "${WEB_ROOT:-}" == "/www/wwwroot/192.168.160.226" \
      && -d "${WEB_PREVIOUS:-}" && ! -L "${WEB_PREVIOUS:-}" ]]; then
    if [[ -d "$WEB_ROOT" && ! -L "$WEB_ROOT" ]]; then
      if command -v chattr >/dev/null 2>&1 && [[ -e "$WEB_ROOT/.user.ini" ]]; then
        chattr -i -- "$WEB_ROOT/.user.ini" 2>/dev/null || true
      fi
      rm -rf -- "$WEB_ROOT" \
        || printf 'WARNING: could not remove the failed staged web root: %s\n' "$WEB_ROOT" >&2
    fi
    if [[ ! -e "$WEB_ROOT" && ! -L "$WEB_ROOT" ]]; then
      mv -- "$WEB_PREVIOUS" "$WEB_ROOT" \
        || printf 'WARNING: could not restore the previous web root from %s\n' "$WEB_PREVIOUS" >&2
    fi
  fi

  if [[ -n "${WEB_STAGE:-}" && "$WEB_STAGE" == /www/wwwroot/.192.168.160.226.pending-* ]]; then
    if [[ -L "$WEB_STAGE" ]]; then
      rm -f -- "$WEB_STAGE"
    elif [[ -d "$WEB_STAGE" ]]; then
      rm -rf -- "$WEB_STAGE"
    fi
  fi
  [[ -z "${PENDING_JAR:-}" ]] || rm -f -- "$PENDING_JAR"
  [[ -z "${VHOST_CANDIDATE:-}" ]] || rm -f -- "$VHOST_CANDIDATE"
  [[ -z "${PROXY_CANDIDATE:-}" ]] || rm -f -- "$PROXY_CANDIDATE"

  if [[ "${ENV_MUTATED:-0}" == "1" && -d "${BACKUP_DIR:-}/env" ]]; then
    cp -a -- "$BACKUP_DIR/env/shenzhouhr.env" "$ENV_ROOT/shenzhouhr.env" \
      || printf 'WARNING: could not restore the app env backup.\n' >&2
    cp -a -- "$BACKUP_DIR/env/shenzhouhr-migrator.env" \
      "$ENV_ROOT/shenzhouhr-migrator.env" \
      || printf 'WARNING: could not restore the migrator env backup.\n' >&2
  fi

  if [[ "${VHOST_REPLACED:-0}" == "1" && -f "${VHOST_BACKUP:-}" \
      && -f "${PROXY_BACKUP:-}" ]]; then
    if cp -a -- "$VHOST_BACKUP" "$VHOST_CONFIG" \
        && cp -a -- "$PROXY_BACKUP" "$PROXY_CONFIG" \
        && /www/server/nginx/sbin/nginx -c "$BAOTA_NGINX_CONFIG" -t >/dev/null 2>&1; then
      /www/server/nginx/sbin/nginx -c "$BAOTA_NGINX_CONFIG" -s reload \
        || printf 'WARNING: restored vhost could not be reloaded.\n' >&2
    else
      printf 'WARNING: vhost/proxy restore failed; keep backup %s and do not start Java.\n' \
        "$BACKUP_DIR" >&2
    fi
  fi

  if [[ "${MIGRATION_ATTEMPTED:-0}" == "1" ]]; then
    printf '%s\n' \
      "WARNING: database migration was attempted (completed=${MIGRATION_COMPLETED:-0}) and may have partially applied non-transactional DDL. Do not start the old JAR; keep the database backup and contact the delivery engineer." >&2
  fi
  exit "$status"
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

validate_run_user() {
  local passwd_entry uid gid home shell primary_group group_count
  local group_names group_members member other_primary_users
  passwd_entry="$(getent passwd shenzhouhr)" \
    || die 'Run user shenzhouhr was not found'
  IFS=: read -r _ _ uid gid _ home shell <<< "$passwd_entry"
  [[ "$uid" =~ ^[0-9]+$ && "$uid" -gt 0 && "$uid" -lt 1000 ]] \
    || die "Run user shenzhouhr must be a system account; detected UID ${uid:-unknown}"
  [[ "$home" == "$APP_ROOT" ]] \
    || die "Run user shenzhouhr must use home $APP_ROOT; detected ${home:-unknown}"
  [[ "$shell" == "/usr/sbin/nologin" || "$shell" == "/sbin/nologin" ]] \
    || die "Run user shenzhouhr must have a nologin shell; detected ${shell:-unknown}"
  primary_group="$(getent group "$gid" | cut -d: -f1)"
  [[ "$primary_group" == "shenzhouhr" ]] \
    || die "Run user shenzhouhr must use primary group shenzhouhr; detected ${primary_group:-unknown}"
  group_names="$(getent group | awk -F: -v expected_gid="$gid" '$3 == expected_gid {print $1}')"
  [[ "$group_names" == "shenzhouhr" ]] \
    || die "Run group GID $gid must be unique to shenzhouhr; detected: ${group_names:-unknown}"
  group_members="$(getent group shenzhouhr | cut -d: -f4)"
  IFS=, read -r -a explicit_members <<< "$group_members"
  for member in "${explicit_members[@]}"; do
    [[ -z "$member" || "$member" == "shenzhouhr" ]] \
      || die "Run group shenzhouhr contains another explicit member: $member"
  done
  other_primary_users="$(getent passwd | awk -F: -v expected_gid="$gid" \
    '$4 == expected_gid && $1 != "shenzhouhr" {print $1}')"
  [[ -z "$other_primary_users" ]] \
    || die "Another user has shenzhouhr as its primary group: $other_primary_users"
  group_count="$(id -G shenzhouhr | wc -w | tr -d ' ')"
  [[ "$group_count" == "1" ]] \
    || die 'Run user shenzhouhr has supplementary groups; remove shared/admin privileges before upgrade'
}

require_real_directory() {
  local path="$1"
  [[ -d "$path" && ! -L "$path" ]] \
    || die "Required path must be a real directory, not a symbolic link: $path"
}

require_safe_root_config() {
  local path="$1"
  local current owner mode
  [[ -f "$path" && ! -L "$path" ]] \
    || die "Configuration must be a regular file, not a symbolic link: $path"
  owner="$(stat -c %u "$path")"
  mode="$(stat -c %a "$path")"
  [[ "$owner" == "0" ]] || die "Configuration must be owned by root before it can be sourced: $path"
  (( (8#$mode & 022) == 0 )) \
    || die "Configuration must not be writable by group or other before it can be sourced: $path (mode $mode)"
  current="$(dirname -- "$path")"
  while [[ "$current" != "/" ]]; do
    [[ -d "$current" && ! -L "$current" ]] \
      || die "Configuration parent must be a real directory: $current"
    owner="$(stat -c %u "$current")"
    mode="$(stat -c %a "$current")"
    [[ "$owner" == "0" ]] || die "Configuration parent must be owned by root: $current"
    (( (8#$mode & 022) == 0 )) \
      || die "Configuration parent must not be writable by group or other: $current (mode $mode)"
    current="$(dirname -- "$current")"
  done
}

validate_running_baota_nginx() {
  local nginx_bin="/www/server/nginx/sbin/nginx"
  local expected_real active_real candidate_real pid cmdline
  local master_candidates=() site_pids=()
  [[ -x "$nginx_bin" ]] || die "Baota-managed Nginx was not found: $nginx_bin"
  [[ -f "$BAOTA_NGINX_CONFIG" && ! -L "$BAOTA_NGINX_CONFIG" ]] \
    || die "Baota Nginx config was not found or is unsafe: $BAOTA_NGINX_CONFIG"
  mapfile -t master_candidates < <(ps -eo pid=,args= | awk '/[n]ginx: master process/ {print $1}')
  for pid in "${master_candidates[@]}"; do
    candidate_real="$(readlink -f "/proc/$pid/exe" 2>/dev/null || true)"
    [[ "$(basename -- "$candidate_real")" == "nginx" ]] || continue
    site_pids+=("$pid")
  done
  [[ "${#site_pids[@]}" -eq 1 ]] \
    || die "Expected exactly one site Nginx master; found ${#site_pids[@]} (panel webserver is excluded)"
  expected_real="$(readlink -f "$nginx_bin")"
  active_real="$(readlink -f "/proc/${site_pids[0]}/exe")"
  [[ -n "$expected_real" && "$active_real" == "$expected_real" ]] \
    || die "Running Nginx is not the Baota-managed binary: ${active_real:-unknown}"
  cmdline="$(tr '\0' ' ' < "/proc/${site_pids[0]}/cmdline")"
  if [[ " $cmdline " =~ [[:space:]]-c[[:space:]]+([^[:space:]]+) ]]; then
    [[ "${BASH_REMATCH[1]}" == "$BAOTA_NGINX_CONFIG" ]] \
      || die "Running Nginx uses an unexpected config: ${BASH_REMATCH[1]}"
  elif [[ " $cmdline " == *' -c'* ]]; then
    die 'Running Nginx has an unsupported or incomplete -c option'
  fi
  if [[ " $cmdline " =~ [[:space:]]-p[[:space:]]+([^[:space:]]+) ]]; then
    [[ "${BASH_REMATCH[1]%/}" == "/www/server/nginx" ]] \
      || die "Running Nginx uses an unexpected prefix: ${BASH_REMATCH[1]}"
  elif [[ " $cmdline " == *' -p'* ]]; then
    die 'Running Nginx has an unsupported or incomplete -p option'
  fi
}

[[ "$(id -u)" -eq 0 ]] || die 'Please run as root: sudo bash upgrade.sh'
verify_release_permissions
verify_release_integrity
load_release_contract
# The checksum was verified before loading any packaged helper.
# shellcheck source=deploy/baota/scripts/provisioning-env.sh
source "$BAOTA_ROOT/scripts/provisioning-env.sh"
[[ -f "$RELEASE_ROOT/backend/shenzhou-hr.jar" ]] || die 'Release backend jar is missing'
[[ -d "$RELEASE_ROOT/web" ]] || die 'Release frontend files are missing'
require_real_directory "$APP_ROOT"
require_real_directory "$APP_DIR"
require_real_directory "$ENV_ROOT"
[[ ! -L "$APP_ROOT/backups" ]] || die "Backup root must not be a symbolic link: $APP_ROOT/backups"
if [[ -e "$APP_ROOT/backups" ]]; then
  require_real_directory "$APP_ROOT/backups"
fi
[[ -r "$ENV_ROOT/shenzhouhr.env" && -f "$ENV_ROOT/shenzhouhr.env" \
    && ! -L "$ENV_ROOT/shenzhouhr.env" ]] \
  || die 'Existing app env was not found or is not a safe regular file; use install.sh for first installation'
[[ -r "$ENV_ROOT/shenzhouhr-migrator.env" && -f "$ENV_ROOT/shenzhouhr-migrator.env" \
    && ! -L "$ENV_ROOT/shenzhouhr-migrator.env" ]] \
  || die 'Existing migrator env was not found or is not a safe regular file'
require_safe_root_config "$ENV_ROOT/shenzhouhr.env"
require_safe_root_config "$ENV_ROOT/shenzhouhr-migrator.env"
[[ -f "$APP_DIR/shenzhou-hr.jar" && ! -L "$APP_DIR/shenzhou-hr.jar" ]] \
  || die 'Existing application JAR was not found or is not a safe regular file'
command -v curl >/dev/null 2>&1 || die 'curl is required'
command -v ss >/dev/null 2>&1 || die 'ss is required'
[[ "$PROCESS_MANAGER" == "baota" ]] \
  || die 'This customer package only supports PROCESS_MANAGER=baota'
if [[ -e /etc/systemd/system/shenzhouhr.service ]] \
    || { command -v systemctl >/dev/null 2>&1 \
      && systemctl cat shenzhouhr.service >/dev/null 2>&1; }; then
  die 'Legacy shenzhouhr.service still exists; remove it before a Baota-managed upgrade'
fi
validate_run_user
validate_running_baota_nginx

JAVA_BIN="$(detect_java)"
JAVA_MAJOR="$($JAVA_BIN -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAVA_MAJOR" == "21" ]] || die "Java 21 is required; detected: ${JAVA_MAJOR:-unknown} at $JAVA_BIN"
MYSQL_BIN="$(detect_mysql)"
export JAVA_BIN MYSQL_BIN
printf 'Using Java: %s\n' "$JAVA_BIN"
printf 'Using MySQL client: %s\n' "$MYSQL_BIN"

set -a
# shellcheck disable=SC1090
source "$ENV_ROOT/shenzhouhr.env"
set +a
BACKEND_PORT="${SHENZHOUHR_SERVER_PORT:-8080}"
[[ "${SHENZHOUHR_SERVER_ADDRESS:-}" == "0.0.0.0" ]] \
  || die 'App env must contain SHENZHOUHR_SERVER_ADDRESS=0.0.0.0 for this customer topology'
[[ "$BACKEND_PORT" == "8080" ]] \
  || die 'App env must contain SHENZHOUHR_SERVER_PORT=8080 for this customer topology'
[[ "${SHENZHOUHR_DB_URL:-}" == jdbc:mysql://127.0.0.1:3306/shenzhou_hr\?* ]] \
  || die 'App env must target jdbc:mysql://127.0.0.1:3306/shenzhou_hr for this customer package'
[[ "${SHENZHOUHR_DB_USERNAME:-}" == "shenzhouhr_app" ]] \
  || die 'App env must use the fixed shenzhouhr_app database account'
[[ "${SHENZHOUHR_SESSION_COOKIE_SECURE:-}" == "false" ]] \
  || die 'App env must use SHENZHOUHR_SESSION_COOKIE_SECURE=false for this fixed internal HTTP deployment'
if ! (
  set -a
  # shellcheck disable=SC1090
  source "$ENV_ROOT/shenzhouhr-migrator.env"
  set +a
  [[ "${SHENZHOUHR_SERVER_ADDRESS:-}" == "0.0.0.0" ]]
  [[ "${SHENZHOUHR_SERVER_PORT:-}" == "8080" ]]
  [[ "${SHENZHOUHR_DB_URL:-}" == jdbc:mysql://127.0.0.1:3306/shenzhou_hr\?* ]]
  [[ "${SHENZHOUHR_FLYWAY_URL:-}" == jdbc:mysql://127.0.0.1:3306/shenzhou_hr\?* ]]
  [[ "${SHENZHOUHR_DB_USERNAME:-}" == "shenzhouhr_migrator" ]]
  [[ "${SHENZHOUHR_FLYWAY_USERNAME:-}" == "shenzhouhr_migrator" ]]
); then
  die 'Migrator env does not match the fixed 127.0.0.1:3306/shenzhou_hr and 0.0.0.0:8080 customer contract'
fi
if ss -H -ltn "sport = :$BACKEND_PORT" | grep -q .; then
  die "Port $BACKEND_PORT is still listening. Stop Java project 'kaoqinweb' in Baota, then rerun upgrade.sh"
fi
if ps -eo args= | grep -E '[j]ava([[:space:]].*)?/opt/shenzhouhr/app/shenzhou-hr\.jar' >/dev/null; then
  die "The ShenzhouHR JAR is still running. Stop Java project 'kaoqinweb' in Baota, then rerun upgrade.sh"
fi

"$BAOTA_ROOT/scripts/database-preflight.sh" \
  --env-file "$ENV_ROOT/shenzhouhr.env" \
  --mysql-bin "$MYSQL_BIN" \
  --expected-mysql-version "$PACKAGE_MYSQL_EXPECTED" \
  --max-flyway-version "$PACKAGE_FLYWAY_MAX"

DOMAIN="$CUSTOMER_DOMAIN"
printf 'Using fixed customer site: %s:%s\n' "$DOMAIN" "$SITE_PORT"
valid_domain "$DOMAIN" || die "Invalid domain: $DOMAIN"
WEB_ROOT="$WEB_ROOT_BASE/$DOMAIN"
validate_web_root
VHOST_CONFIG="/www/server/panel/vhost/nginx/$DOMAIN.conf"
[[ -f "$VHOST_CONFIG" && ! -L "$VHOST_CONFIG" ]] \
  || die "Baota site was not found at $VHOST_CONFIG; refusing to update an unregistered web root"
grep -Eq "^[[:space:]]*listen[[:space:]]+${SITE_PORT}[[:space:]]*;" "$VHOST_CONFIG" \
  || die "Baota site $VHOST_CONFIG does not listen on $SITE_PORT"
awk -v expected="$WEB_ROOT" '
  $1 == "root" {
    sub(/;$/, "", $2)
    if ($2 == expected) found = 1
  }
  END { exit(found ? 0 : 1) }
' "$VHOST_CONFIG" \
  || die "Baota site $VHOST_CONFIG does not use root $WEB_ROOT"
grep -Fq "include $BAOTA_PROXY_ROOT/$DOMAIN/*.conf;" "$VHOST_CONFIG" \
  || die "BaoTa site $DOMAIN does not load its panel-managed proxy rules"
PYTHON_BIN="$(detect_python)"
PROXY_CONFIG="$("$PYTHON_BIN" "$BAOTA_ROOT/scripts/baota-proxy.py" \
  --metadata-file "$BAOTA_PROXY_METADATA" \
  --proxy-root "$BAOTA_PROXY_ROOT" \
  --site "$DOMAIN" \
  --proxy-name "$CUSTOMER_PROXY_NAME" \
  --expected-target "$CUSTOMER_PROXY_TARGET" \
  --require-marker)" \
  || die "BaoTa reverse-proxy record $CUSTOMER_PROXY_NAME is missing or no longer matches the managed rule"

STAMP="$(date -u +%Y%m%d%H%M%S)"
BACKUP_DIR="$APP_ROOT/backups/upgrade-$STAMP"
WEB_STAGE="$WEB_ROOT_BASE/.${DOMAIN}.pending-$STAMP"
WEB_PREVIOUS="$WEB_ROOT_BASE/.${DOMAIN}.previous-$STAMP"
VHOST_BACKUP="$BACKUP_DIR/$(basename -- "$VHOST_CONFIG")"
VHOST_CANDIDATE="$(dirname -- "$VHOST_CONFIG")/.${DOMAIN}.conf.candidate-$STAMP"
PROXY_BACKUP="$BACKUP_DIR/$(basename -- "$PROXY_CONFIG").original"
PROXY_CANDIDATE="$(dirname -- "$PROXY_CONFIG")/.$(basename -- "$PROXY_CONFIG").candidate-$STAMP"
[[ ! -e "$BACKUP_DIR" && ! -L "$BACKUP_DIR" ]] \
  || die "Upgrade backup target already exists: $BACKUP_DIR"
[[ ! -e "$WEB_STAGE" && ! -L "$WEB_STAGE" ]] \
  || die "Staged web target already exists: $WEB_STAGE"
[[ ! -e "$WEB_PREVIOUS" && ! -L "$WEB_PREVIOUS" ]] \
  || die "Previous web target already exists: $WEB_PREVIOUS"
[[ ! -e "$VHOST_CANDIDATE" && ! -L "$VHOST_CANDIDATE" ]] \
  || die "Nginx candidate already exists: $VHOST_CANDIDATE"
[[ ! -e "$PROXY_CANDIDATE" && ! -L "$PROXY_CANDIDATE" ]] \
  || die "Nginx proxy candidate already exists: $PROXY_CANDIDATE"
chown root:shenzhouhr "$APP_ROOT" "$APP_DIR"
chmod 0750 "$APP_ROOT" "$APP_DIR"
chown -R root:shenzhouhr "$APP_DIR"
find "$APP_DIR" -type f -exec chmod 0640 {} +
if [[ ! -e "$APP_ROOT/backups" ]]; then
  install -d -o root -g root -m 0700 "$APP_ROOT/backups"
else
  chown root:root "$APP_ROOT/backups"
  chmod 0700 "$APP_ROOT/backups"
fi
install -d -o root -g root -m 0700 "$BACKUP_DIR" "$BACKUP_DIR/web" "$BACKUP_DIR/env"
cp -a "$APP_DIR/shenzhou-hr.jar" "$BACKUP_DIR/"
cp -a "$WEB_ROOT/." "$BACKUP_DIR/web/"
cp -a "$ENV_ROOT/shenzhouhr.env" "$ENV_ROOT/shenzhouhr-migrator.env" \
  "$BACKUP_DIR/env/"
cp -a "$VHOST_CONFIG" "$VHOST_BACKUP"
cp -a "$PROXY_CONFIG" "$PROXY_BACKUP"

VHOST_REPLACED=0
ENV_MUTATED=0
MIGRATION_COMPLETED=0
MIGRATION_ATTEMPTED=0
WEB_OLD_RENAMED=0
trap rollback_failed_upgrade EXIT

install -d -o root -g root -m 0755 "$WEB_STAGE"
cp -a "$RELEASE_ROOT/web/." "$WEB_STAGE/"
[[ -f "$WEB_STAGE/index.html" && ! -L "$WEB_STAGE/index.html" ]] \
  || die 'Staged frontend is missing a safe index.html'
[[ -z "$(find "$WEB_STAGE" -type l -print -quit)" ]] \
  || die 'Staged frontend contains a symbolic link; refusing deployment'
find "$WEB_STAGE" -type d -exec chmod 0755 {} +
find "$WEB_STAGE" -type f -exec chmod 0644 {} +
chown -R www:www "$WEB_STAGE" 2>/dev/null || chown -R www-data:www-data "$WEB_STAGE"

sed -e "s|__DOMAIN__|$DOMAIN|g" \
  -e "s|__WEB_ROOT__|$WEB_ROOT|g" \
  -e "s|__SSL_CERT__||g" \
  -e "s|__SSL_KEY__||g" \
  -e "s|__SITE_PORT__|$SITE_PORT|g" \
  -e "s|__BACKEND_PORT__|$BACKEND_PORT|g" \
  "$BAOTA_ROOT/nginx/shenzhouhr-site-http.conf" > "$VHOST_CANDIDATE"
sed -e "s|__BACKEND_PORT__|$BACKEND_PORT|g" \
  "$BAOTA_ROOT/nginx/shenzhouhr-api-proxy.conf" > "$PROXY_CANDIDATE"
chmod 0644 "$VHOST_CANDIDATE"
chmod 0644 "$PROXY_CANDIDATE"
VHOST_REPLACED=1
mv -f -- "$VHOST_CANDIDATE" "$VHOST_CONFIG"
mv -f -- "$PROXY_CANDIDATE" "$PROXY_CONFIG"
/www/server/nginx/sbin/nginx -c "$BAOTA_NGINX_CONFIG" -t
/www/server/nginx/sbin/nginx -c "$BAOTA_NGINX_CONFIG" -s reload

chown root:shenzhouhr "$ENV_ROOT" "$ENV_ROOT/shenzhouhr.env"
chmod 0750 "$ENV_ROOT"
chmod 0640 "$ENV_ROOT/shenzhouhr.env"
chown root:root "$ENV_ROOT/shenzhouhr-migrator.env"
chmod 0600 "$ENV_ROOT/shenzhouhr-migrator.env"
if command -v runuser >/dev/null 2>&1; then
  runuser -u shenzhouhr -- test -r "$ENV_ROOT/shenzhouhr.env" \
    || die "Run user shenzhouhr cannot read $ENV_ROOT/shenzhouhr.env"
else
  su -s /bin/sh -c "test -r '$ENV_ROOT/shenzhouhr.env'" shenzhouhr \
    || die "Run user shenzhouhr cannot read $ENV_ROOT/shenzhouhr.env"
fi

# Older installations predate deterministic recovery receipts. Converge both
# root-owned env files only after their exact originals have been backed up.
# Existing valid values are reused; conflicting values stop the upgrade.
ENV_MUTATED=1
provisioning_sync_env_pair \
  "$ENV_ROOT/shenzhouhr.env" \
  "$ENV_ROOT/shenzhouhr-migrator.env" \
  || die "$PROVISIONING_ENV_ERROR"
printf '%s\n' 'Account-provisioning recovery configuration verified.'

PENDING_JAR="$APP_DIR/.shenzhou-hr.jar.pending-$STAMP"
[[ ! -e "$PENDING_JAR" && ! -L "$PENDING_JAR" ]] \
  || die "Pending JAR path already exists: $PENDING_JAR"
install -o root -g shenzhouhr -m 0640 \
  "$RELEASE_ROOT/backend/shenzhou-hr.jar" "$PENDING_JAR"

printf 'Running database migrations with the staged JAR, if this release contains new ones...\n'
MIGRATION_ATTEMPTED=1
JAVA_BIN="$JAVA_BIN" "$BAOTA_ROOT/scripts/migrate.sh" \
  --jar "$PENDING_JAR" \
  --app-env-file "$ENV_ROOT/shenzhouhr.env" \
  --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env" \
  --mysql-bin "$MYSQL_BIN"
MIGRATION_COMPLETED=1

mv -- "$WEB_ROOT" "$WEB_PREVIOUS"
WEB_OLD_RENAMED=1
mv -- "$WEB_STAGE" "$WEB_ROOT"
restore_baota_user_ini "$BACKUP_DIR/web/.user.ini"

mv -f -- "$PENDING_JAR" "$APP_DIR/shenzhou-hr.jar"
trap - EXIT

printf '%s\n' "Files and migrations are upgraded. Start Java project 'kaoqinweb' in Baota, then run verify.sh."
if command -v chattr >/dev/null 2>&1 && [[ -e "$WEB_PREVIOUS/.user.ini" ]]; then
  chattr -i -- "$WEB_PREVIOUS/.user.ini" 2>/dev/null || true
fi
rm -rf -- "$WEB_PREVIOUS" \
  || printf 'WARNING: old frontend remains isolated at %s; remove it after verification.\n' "$WEB_PREVIOUS" >&2

printf '\nUpgrade completed.\n'
printf 'Backup: %s\n' "$BACKUP_DIR"
printf '%s\n' "Next: Baota > Java projects > kaoqinweb > Start"
