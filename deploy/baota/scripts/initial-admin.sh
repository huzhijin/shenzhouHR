#!/usr/bin/env bash
set -Eeuo pipefail
set +x

JAR_PATH=""
MIGRATOR_ENV_FILE="/etc/shenzhouhr/shenzhouhr-migrator.env"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: initial-admin.sh --jar PATH [--migrator-env-file PATH]
USAGE
}

while (($#)); do
  case "$1" in
    --jar) JAR_PATH="$2"; shift 2 ;;
    --migrator-env-file) MIGRATOR_ENV_FILE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ -f "$JAR_PATH" ]] || die "Jar not found: $JAR_PATH"
[[ -r "$MIGRATOR_ENV_FILE" ]] || die "Migrator env not found: $MIGRATOR_ENV_FILE"

JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
[[ -n "$JAVA_BIN" ]] || die 'Java 21 is required'
JAVA_MAJOR="$($JAVA_BIN -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAVA_MAJOR" == "21" ]] || die "Java 21 is required; detected: ${JAVA_MAJOR:-unknown}"
JAR_BIN="${JAR_BIN:-$(dirname -- "$JAVA_BIN")/jar}"
[[ -x "$JAR_BIN" ]] || JAR_BIN="$(command -v jar || true)"
[[ -n "$JAR_BIN" ]] || die 'JDK jar tool was not found'

set -a
# shellcheck disable=SC1090
source "$MIGRATOR_ENV_FILE"
set +a

read -r -p 'Initial admin username [admin]: ' ADMIN_USERNAME
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
read -r -p 'Initial admin display name [系统管理员]: ' ADMIN_DISPLAY_NAME
ADMIN_DISPLAY_NAME="${ADMIN_DISPLAY_NAME:-系统管理员}"
read -r -p 'Company code [CUSTOMER]: ' COMPANY_CODE
COMPANY_CODE="${COMPANY_CODE:-CUSTOMER}"
read -r -p 'Company name: ' COMPANY_NAME
[[ -n "$COMPANY_NAME" ]] || die 'Company name cannot be empty'
read -r -s -p 'Initial admin password (12+ chars, upper/lower/digit/symbol): ' ADMIN_PASSWORD
printf '\n'
read -r -s -p 'Repeat initial admin password: ' ADMIN_PASSWORD_CONFIRM
printf '\n'
[[ "$ADMIN_PASSWORD" == "$ADMIN_PASSWORD_CONFIRM" ]] || die 'Passwords do not match'
unset ADMIN_PASSWORD_CONFIRM

export SHENZHOUHR_INIT_ADMIN_USERNAME="$ADMIN_USERNAME"
export SHENZHOUHR_INIT_ADMIN_DISPLAY_NAME="$ADMIN_DISPLAY_NAME"
export SHENZHOUHR_INIT_COMPANY_CODE="$COMPANY_CODE"
export SHENZHOUHR_INIT_COMPANY_NAME="$COMPANY_NAME"
export SHENZHOUHR_INIT_ADMIN_PASSWORD="$ADMIN_PASSWORD"
unset ADMIN_PASSWORD

EXTRACT_DIR="$(mktemp -d /tmp/shenzhouhr-admin.XXXXXX)"
trap 'rm -rf -- "$EXTRACT_DIR"' EXIT
(cd "$EXTRACT_DIR" && "$JAR_BIN" xf "$JAR_PATH" BOOT-INF/classes BOOT-INF/lib)

CLASSPATH="$EXTRACT_DIR/BOOT-INF/classes:$EXTRACT_DIR/BOOT-INF/lib/*"
"$JAVA_BIN" -cp "$CLASSPATH" \
  com.szsemicon.hr.identityaccess.infrastructure.bootstrap.InitialProductionAdminCommand

printf '%s\n' 'Initial administrator created. The first login must change its password.'
