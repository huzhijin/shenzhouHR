#!/usr/bin/env bash
set -Eeuo pipefail
set +x

JAR_PATH=""
MIGRATOR_ENV_FILE="/etc/shenzhouhr/shenzhouhr-migrator.env"
INPUT_MODE="interactive"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: initial-admin.sh --jar PATH [--migrator-env-file PATH] [--non-interactive]
       initial-admin.sh --validate-only
USAGE
}

load_non_interactive_input() {
  ADMIN_USERNAME="${SHENZHOUHR_INIT_ADMIN_USERNAME:-}"
  ADMIN_DISPLAY_NAME="${SHENZHOUHR_INIT_ADMIN_DISPLAY_NAME:-}"
  COMPANY_CODE="${SHENZHOUHR_INIT_COMPANY_CODE:-}"
  COMPANY_NAME="${SHENZHOUHR_INIT_COMPANY_NAME:-}"
  ADMIN_PASSWORD="${SHENZHOUHR_INIT_ADMIN_PASSWORD:-}"
}

validate_input() {
  [[ "$ADMIN_USERNAME" =~ ^[A-Za-z0-9._-]{3,64}$ ]] \
    || die 'Initial admin username must be 3-64 safe characters'
  [[ -n "$ADMIN_DISPLAY_NAME" && "$ADMIN_DISPLAY_NAME" != *$'\n'* \
      && ${#ADMIN_DISPLAY_NAME} -le 100 ]] \
    || die 'Initial admin display name must be 1-100 characters on one line'
  [[ "$COMPANY_CODE" =~ ^[A-Za-z0-9_-]{2,32}$ ]] \
    || die 'Company code must be 2-32 letters, digits, underscores, or hyphens'
  [[ -n "$COMPANY_NAME" && "$COMPANY_NAME" != *$'\n'* \
      && ${#COMPANY_NAME} -le 200 ]] \
    || die 'Company name must be 1-200 characters on one line'
  [[ ${#ADMIN_PASSWORD} -ge 12 \
      && "$ADMIN_PASSWORD" =~ [A-Z] \
      && "$ADMIN_PASSWORD" =~ [a-z] \
      && "$ADMIN_PASSWORD" =~ [0-9] \
      && "$ADMIN_PASSWORD" =~ [^A-Za-z0-9] ]] \
    || die 'Initial admin password must be 12+ chars with upper/lower/digit/symbol'
}

while (($#)); do
  case "$1" in
    --jar) JAR_PATH="$2"; shift 2 ;;
    --migrator-env-file) MIGRATOR_ENV_FILE="$2"; shift 2 ;;
    --non-interactive) INPUT_MODE="non-interactive"; shift ;;
    --validate-only) INPUT_MODE="validate-only"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

if [[ "$INPUT_MODE" == "validate-only" ]]; then
  load_non_interactive_input
  validate_input
  printf '%s\n' 'Initial administrator input passed local validation.'
  exit 0
fi

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

if [[ "$INPUT_MODE" == "non-interactive" ]]; then
  load_non_interactive_input
else
  read -r -p 'Initial admin username [admin]: ' ADMIN_USERNAME
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
  read -r -p 'Initial admin display name [系统管理员]: ' ADMIN_DISPLAY_NAME
  ADMIN_DISPLAY_NAME="${ADMIN_DISPLAY_NAME:-系统管理员}"
  read -r -p 'Company code [CUSTOMER]: ' COMPANY_CODE
  COMPANY_CODE="${COMPANY_CODE:-CUSTOMER}"
  read -r -p 'Company name: ' COMPANY_NAME
  read -r -s -p 'Initial admin password (12+ chars, upper/lower/digit/symbol): ' ADMIN_PASSWORD
  printf '\n'
  read -r -s -p 'Repeat initial admin password: ' ADMIN_PASSWORD_CONFIRM
  printf '\n'
  [[ "$ADMIN_PASSWORD" == "$ADMIN_PASSWORD_CONFIRM" ]] || die 'Passwords do not match'
  unset ADMIN_PASSWORD_CONFIRM
fi
validate_input

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
