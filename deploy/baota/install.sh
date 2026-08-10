#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 077

RELEASE_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BAOTA_ROOT="$RELEASE_ROOT/deploy/baota"
APP_ROOT="/opt/shenzhouhr"
APP_DIR="$APP_ROOT/app"
readonly WEB_ROOT_BASE="/www/wwwroot"
readonly CUSTOMER_DOMAIN="192.168.160.226"
readonly CUSTOMER_DB_HOST="127.0.0.1"
readonly CUSTOMER_DB_PORT="3306"
readonly CUSTOMER_DB_NAME="shenzhou_hr"
readonly CUSTOMER_ACCOUNT_HOST="127.0.0.1"
readonly BAOTA_NGINX_CONFIG="/www/server/nginx/conf/nginx.conf"
ENV_ROOT="/etc/shenzhouhr"
BACKEND_ADDRESS="${BACKEND_ADDRESS:-0.0.0.0}"
BACKEND_PORT="${BACKEND_PORT:-8080}"
SITE_PORT="${SITE_PORT:-23272}"
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

rollback_vhost_on_install_failure() {
  local status=$?
  local vhost_restore_failed=0
  trap - EXIT
  if ((status != 0)) && [[ -n "${VHOST_BACKUP:-}" && -f "$VHOST_BACKUP" ]]; then
    printf 'Installation failed; restoring the original Baota vhost.\n' >&2
    if ! cp -a -- "$VHOST_BACKUP" "$VHOST_CONFIG"; then
      printf 'WARNING: failed to restore %s from %s\n' "$VHOST_CONFIG" "$VHOST_BACKUP" >&2
      vhost_restore_failed=1
    elif [[ -x "${NGINX_BIN:-}" ]] \
        && ! { "$NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -t >/dev/null 2>&1 \
          && "$NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -s reload; }; then
      printf 'WARNING: restored vhost requires manual Nginx verification.\n' >&2
      vhost_restore_failed=1
    fi
  fi
  [[ -z "${VHOST_CANDIDATE:-}" ]] || rm -f -- "$VHOST_CANDIDATE"
  if ((status != 0)) && [[ "${INSTALL_STORAGE_CREATED:-0}" == "1" \
      && "${INSTALL_PROVISIONED:-0}" == "0" \
      && "$vhost_restore_failed" == "0" \
      && "$APP_ROOT" == "/opt/shenzhouhr" \
      && "$ENV_ROOT" == "/etc/shenzhouhr" ]]; then
    rm -rf -- "$ENV_ROOT" "$APP_ROOT" \
      || printf 'WARNING: failed to remove this run\047s unprovisioned directories.\n' >&2
  elif ((status != 0)) && [[ "${INSTALL_PROVISIONED:-0}" == "1" ]]; then
    printf '%s\n' \
      'WARNING: database provisioning completed before the failure. Keep /etc/shenzhouhr and /opt/shenzhouhr for diagnosis; do not blindly rerun install.sh.' >&2
  elif ((status != 0)) && [[ "$vhost_restore_failed" == "1" ]]; then
    printf '%s\n' \
      "WARNING: keeping $APP_ROOT because it contains the only verified vhost backup; restore it manually before retrying." >&2
  fi
  exit "$status"
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
  local expected="/www/server/nginx/sbin/nginx"
  local selected="${NGINX_BIN:-$expected}"
  [[ "$selected" == "$expected" ]] \
    || die "This customer package requires the Baota-managed Nginx binary: $expected"
  [[ -x "$selected" ]] || die 'Baota-managed Nginx not found; install or take over Nginx in Baota first'
  printf '%s\n' "$selected"
}

validate_running_nginx() {
  local expected_real active_real candidate_real pid cmdline
  local master_candidates=() site_pids=()
  mapfile -t master_candidates < <(ps -eo pid=,args= | awk '/[n]ginx: master process/ {print $1}')
  for pid in "${master_candidates[@]}"; do
    candidate_real="$(readlink -f "/proc/$pid/exe" 2>/dev/null || true)"
    [[ "$(basename -- "$candidate_real")" == "nginx" ]] || continue
    site_pids+=("$pid")
  done
  [[ "${#site_pids[@]}" -eq 1 ]] \
    || die "Expected exactly one site Nginx master; found ${#site_pids[@]} (panel webserver is excluded)"
  expected_real="$(readlink -f "$NGINX_BIN")"
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
    || die 'Run user shenzhouhr has supplementary groups; remove shared/admin privileges before installation'
}

collect_initial_admin_input() {
  local password_confirm
  read -r -p 'Initial admin username [admin]: ' INITIAL_ADMIN_USERNAME
  INITIAL_ADMIN_USERNAME="${INITIAL_ADMIN_USERNAME:-admin}"
  read -r -p 'Initial admin display name [系统管理员]: ' INITIAL_ADMIN_DISPLAY_NAME
  INITIAL_ADMIN_DISPLAY_NAME="${INITIAL_ADMIN_DISPLAY_NAME:-系统管理员}"
  read -r -p 'Company code [CUSTOMER]: ' INITIAL_COMPANY_CODE
  INITIAL_COMPANY_CODE="${INITIAL_COMPANY_CODE:-CUSTOMER}"
  read -r -p 'Company name: ' INITIAL_COMPANY_NAME
  read -r -s -p 'Initial admin password (12+ chars, upper/lower/digit/symbol): ' INITIAL_ADMIN_PASSWORD
  printf '\n'
  read -r -s -p 'Repeat initial admin password: ' password_confirm
  printf '\n'
  [[ "$INITIAL_ADMIN_PASSWORD" == "$password_confirm" ]] \
    || die 'Initial administrator passwords do not match'
  unset password_confirm
  SHENZHOUHR_INIT_ADMIN_USERNAME="$INITIAL_ADMIN_USERNAME" \
  SHENZHOUHR_INIT_ADMIN_DISPLAY_NAME="$INITIAL_ADMIN_DISPLAY_NAME" \
  SHENZHOUHR_INIT_COMPANY_CODE="$INITIAL_COMPANY_CODE" \
  SHENZHOUHR_INIT_COMPANY_NAME="$INITIAL_COMPANY_NAME" \
  SHENZHOUHR_INIT_ADMIN_PASSWORD="$INITIAL_ADMIN_PASSWORD" \
    "$BAOTA_ROOT/scripts/initial-admin.sh" --validate-only
}

need_root
verify_release_permissions
verify_release_integrity
load_release_contract
[[ -f "$RELEASE_ROOT/backend/shenzhou-hr.jar" ]] || die 'Release backend jar is missing'
[[ -d "$RELEASE_ROOT/web" ]] || die 'Release frontend files are missing'
[[ -f "$RELEASE_ROOT/web/index.html" && ! -L "$RELEASE_ROOT/web/index.html" ]] \
  || die 'Release frontend is missing a safe index.html'
[[ -z "$(find "$RELEASE_ROOT/web" -type l -print -quit)" ]] \
  || die 'Release frontend contains a symbolic link; refusing installation'
[[ -d "$RELEASE_ROOT/db/migration" ]] || die 'Release migration files are missing'
command -v curl >/dev/null 2>&1 || die 'curl is required'
command -v openssl >/dev/null 2>&1 || die 'openssl is required'
command -v ss >/dev/null 2>&1 || die 'ss is required to verify the private backend port'
[[ "$PROCESS_MANAGER" == "baota" ]] \
  || die 'This customer package only supports PROCESS_MANAGER=baota'
[[ "$BACKEND_ADDRESS" == "0.0.0.0" ]] \
  || die 'This customer package requires BACKEND_ADDRESS=0.0.0.0'
[[ "$BACKEND_PORT" == "8080" ]] \
  || die 'This customer package requires BACKEND_PORT=8080'
[[ "$SITE_PORT" == "23272" ]] \
  || die 'This customer package requires SITE_PORT=23272'
if [[ -e /etc/systemd/system/shenzhouhr.service ]] \
    || { command -v systemctl >/dev/null 2>&1 \
      && systemctl cat shenzhouhr.service >/dev/null 2>&1; }; then
  die 'Legacy shenzhouhr.service still exists; stop and remove the old command deployment before using Baota Java management'
fi
[[ ! -e "$ENV_ROOT/shenzhouhr.env" && ! -e "$ENV_ROOT/shenzhouhr-migrator.env" ]] \
  || die "Existing ShenzhouHR environment found under $ENV_ROOT; use upgrade.sh or stop and diagnose an interrupted installation."
[[ ! -e "$APP_ROOT" && ! -L "$APP_ROOT" ]] \
  || die "Existing application root found at $APP_ROOT; finish the verified legacy cleanup before installation"
[[ ! -e "$ENV_ROOT" && ! -L "$ENV_ROOT" ]] \
  || die "Existing environment root found at $ENV_ROOT; finish the verified legacy cleanup before installation"
if ss -H -ltn "sport = :$BACKEND_PORT" | grep -q .; then
  die "Backend port $BACKEND_PORT is already in use; choose a free private port before installation"
fi

DOMAIN="$CUSTOMER_DOMAIN"
printf 'Using fixed customer site: %s:%s\n' "$DOMAIN" "$SITE_PORT"
valid_domain "$DOMAIN" || die "Invalid domain: $DOMAIN"
WEB_ROOT="$WEB_ROOT_BASE/$DOMAIN"
validate_web_root false

SESSION_COOKIE_SECURE="false"

DB_HOST="$CUSTOMER_DB_HOST"
DB_PORT="$CUSTOMER_DB_PORT"
DB_NAME="$CUSTOMER_DB_NAME"
ACCOUNT_HOST="$CUSTOMER_ACCOUNT_HOST"
printf 'Using fixed local database target: %s:%s/%s (account host %s)\n' \
  "$DB_HOST" "$DB_PORT" "$DB_NAME" "$ACCOUNT_HOST"

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
[[ -f "$BAOTA_NGINX_CONFIG" && ! -L "$BAOTA_NGINX_CONFIG" ]] \
  || die "Baota Nginx config was not found or is unsafe: $BAOTA_NGINX_CONFIG"
validate_running_nginx

printf 'Using Java: %s\n' "$JAVA_BIN"
printf 'Using JAR tool: %s\n' "$JAR_BIN"
printf 'Using MySQL client: %s\n' "$MYSQL_BIN"
printf 'Using process manager: %s\n' "$PROCESS_MANAGER"
printf 'Using backend listener: %s:%s\n' "$BACKEND_ADDRESS" "$BACKEND_PORT"
printf 'Using Nginx site port: %s\n' "$SITE_PORT"

NGINX_VHOST_DIR="/www/server/panel/vhost/nginx"
VHOST_CONFIG="$NGINX_VHOST_DIR/$DOMAIN.conf"
[[ -d "$NGINX_VHOST_DIR" && ! -L "$NGINX_VHOST_DIR" ]] \
  || die "Baota-managed Nginx vhost directory was not found: $NGINX_VHOST_DIR"
[[ -f "$VHOST_CONFIG" && ! -L "$VHOST_CONFIG" ]] \
  || die "Baota site was not found at $VHOST_CONFIG. Create $DOMAIN:$SITE_PORT in the panel before installation; refusing to create an unmanaged vhost."
grep -Eq "^[[:space:]]*listen[[:space:]]+${SITE_PORT}[[:space:]]*;" "$VHOST_CONFIG" \
  || die "Baota site $VHOST_CONFIG does not listen on $SITE_PORT; correct the panel site before installation"
awk -v expected="$WEB_ROOT" '
  $1 == "root" {
    sub(/;$/, "", $2)
    if ($2 == expected) found = 1
  }
  END { exit(found ? 0 : 1) }
' "$VHOST_CONFIG" \
  || die "Baota site $VHOST_CONFIG does not use root $WEB_ROOT; correct the panel site before installation"

SSL_CERT=""
SSL_KEY=""
NGINX_TEMPLATE="$BAOTA_ROOT/nginx/shenzhouhr-site-http.conf"
printf '%s\n' 'Fixed controlled-network HTTP mode selected; public Internet use requires a separately approved TLS gateway design.'
printf '%s\n' 'Collecting and validating the required first administrator before any database mutation.'
collect_initial_admin_input

if ! getent group shenzhouhr >/dev/null 2>&1; then
  groupadd --system shenzhouhr
fi
if ! id shenzhouhr >/dev/null 2>&1; then
  useradd --system --gid shenzhouhr --home-dir "$APP_ROOT" --shell /usr/sbin/nologin shenzhouhr
fi
validate_run_user

INSTALL_STAMP="$(date -u +%Y%m%d%H%M%S)"
BACKUP_DIR="$APP_ROOT/backups/$INSTALL_STAMP"
INSTALL_STORAGE_CREATED=1
INSTALL_PROVISIONED=0
trap rollback_vhost_on_install_failure EXIT
install -d -o root -g shenzhouhr -m 0750 "$APP_ROOT" "$APP_DIR" "$ENV_ROOT"
install -d -o root -g root -m 0700 "$APP_ROOT/backups" "$BACKUP_DIR"
mkdir -p "$WEB_ROOT"
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
VHOST_BACKUP="$BACKUP_DIR/$(basename -- "$VHOST_CONFIG")"
VHOST_CANDIDATE="$NGINX_VHOST_DIR/.${DOMAIN}.conf.candidate-$INSTALL_STAMP"
sed -e "s|__DOMAIN__|$DOMAIN|g" \
  -e "s|__WEB_ROOT__|$WEB_ROOT|g" \
  -e "s|__SSL_CERT__|$SSL_CERT|g" \
  -e "s|__SSL_KEY__|$SSL_KEY|g" \
  -e "s|__SITE_PORT__|$SITE_PORT|g" \
  -e "s|__BACKEND_PORT__|$BACKEND_PORT|g" \
  "$NGINX_TEMPLATE" > "$VHOST_CANDIDATE"
chmod 0644 "$VHOST_CANDIDATE"
mv -f -- "$VHOST_CANDIDATE" "$VHOST_CONFIG"
"$NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -t

"$BAOTA_ROOT/mysql/provision.sh" \
  --env-file "$ENV_ROOT/shenzhouhr.env" \
  --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env" \
  --db-host "$DB_HOST" \
  --db-port "$DB_PORT" \
  --db-name "$DB_NAME" \
  --account-host "$ACCOUNT_HOST" \
  --backend-address "$BACKEND_ADDRESS" \
  --backend-port "$BACKEND_PORT" \
  --expected-version "$PACKAGE_MYSQL_EXPECTED" \
  --session-cookie-secure "$SESSION_COOKIE_SECURE"
INSTALL_PROVISIONED=1

chown root:shenzhouhr "$ENV_ROOT/shenzhouhr.env"
chmod 0640 "$ENV_ROOT/shenzhouhr.env"
chown root:root "$ENV_ROOT/shenzhouhr-migrator.env"
chmod 0600 "$ENV_ROOT/shenzhouhr-migrator.env"
chown root:shenzhouhr "$ENV_ROOT"
chmod 0750 "$ENV_ROOT"
if command -v runuser >/dev/null 2>&1; then
  runuser -u shenzhouhr -- test -r "$ENV_ROOT/shenzhouhr.env" \
    || die "Run user shenzhouhr cannot read $ENV_ROOT/shenzhouhr.env"
else
  su -s /bin/sh -c "test -r '$ENV_ROOT/shenzhouhr.env'" shenzhouhr \
    || die "Run user shenzhouhr cannot read $ENV_ROOT/shenzhouhr.env"
fi

install -o root -g shenzhouhr -m 0640 "$RELEASE_ROOT/backend/shenzhou-hr.jar" "$APP_DIR/shenzhou-hr.jar"
install -o root -g shenzhouhr -m 0640 "$RELEASE_ROOT/db/migration/"*.sql "$APP_DIR/" 2>/dev/null || true

"$BAOTA_ROOT/scripts/migrate.sh" \
  --jar "$APP_DIR/shenzhou-hr.jar" \
  --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env"

printf '%s\n' 'A first production administrator is required for this empty customer database.'
SHENZHOUHR_INIT_ADMIN_USERNAME="$INITIAL_ADMIN_USERNAME" \
SHENZHOUHR_INIT_ADMIN_DISPLAY_NAME="$INITIAL_ADMIN_DISPLAY_NAME" \
SHENZHOUHR_INIT_COMPANY_CODE="$INITIAL_COMPANY_CODE" \
SHENZHOUHR_INIT_COMPANY_NAME="$INITIAL_COMPANY_NAME" \
SHENZHOUHR_INIT_ADMIN_PASSWORD="$INITIAL_ADMIN_PASSWORD" \
JAVA_BIN="$JAVA_BIN" "$BAOTA_ROOT/scripts/initial-admin.sh" --non-interactive \
  --jar "$APP_DIR/shenzhou-hr.jar" \
  --migrator-env-file "$ENV_ROOT/shenzhouhr-migrator.env"
unset INITIAL_ADMIN_USERNAME INITIAL_ADMIN_DISPLAY_NAME INITIAL_COMPANY_CODE \
  INITIAL_COMPANY_NAME INITIAL_ADMIN_PASSWORD

clear_web_root
cp -a "$RELEASE_ROOT/web/." "$WEB_ROOT/"
find "$WEB_ROOT" -type d -exec chmod 0755 {} +
find "$WEB_ROOT" -type f -exec chmod 0644 {} +
chown -R www:www "$WEB_ROOT" 2>/dev/null || chown -R www-data:www-data "$WEB_ROOT"
restore_baota_user_ini "$BACKUP_DIR/web/.user.ini"

chown root:shenzhouhr "$APP_ROOT" "$APP_DIR"
chown -R root:shenzhouhr "$APP_DIR"
chown -R root:root "$APP_ROOT/backups"
chmod 0700 "$APP_ROOT/backups" "$BACKUP_DIR"
chmod 0750 "$APP_ROOT" "$APP_DIR"
find "$APP_DIR" -type f -exec chmod 0640 {} +

"$NGINX_BIN" -c "$BAOTA_NGINX_CONFIG" -s reload
trap - EXIT

printf '\nBaota preparation completed. Add and start the Java project in the panel now.\n'
printf 'Open: http://%s:%s\n' "$DOMAIN" "$SITE_PORT"
printf 'Backend listener: %s:%s\n' "$BACKEND_ADDRESS" "$BACKEND_PORT"
printf 'Baota Java project name: kaoqinweb\n'
printf 'JAR: %s/shenzhou-hr.jar\n' "$APP_DIR"
printf 'JDK: %s\n' "$(dirname -- "$(dirname -- "$JAVA_BIN")")"
printf 'Run user: shenzhouhr\n'
printf 'Environment file: %s/shenzhouhr.env\n' "$ENV_ROOT"
printf 'Start command: %s -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -jar %s/shenzhou-hr.jar\n' \
  "$JAVA_BIN" "$APP_DIR"
printf 'Port: %s\n' "$BACKEND_PORT"
printf '%s\n' 'Domain / external mapping: leave blank'
printf 'Backup: %s\n' "$BACKUP_DIR"
printf '%s\n' 'Opening business data is not embedded in the code package.'
printf '%s\n' 'Import approved organization, employee and employment files separately after login.'
