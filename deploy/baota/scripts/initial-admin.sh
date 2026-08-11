#!/usr/bin/env bash
set -Eeuo pipefail
set +x

JAR_PATH=""
MIGRATOR_ENV_FILE="/etc/shenzhouhr/shenzhouhr-migrator.env"
COMPANY_CATALOG=""
INPUT_MODE="interactive"
readonly EXPECTED_COMPANY_CODES="SZSZ,SZJN,SZSC,SZXY"
readonly EXPECTED_COMPANY_CATALOG=$'SZSZ\t上海昇州半导体科技有限公司\nSZJN\t上海晟州聚能半导体科技有限公司\nSZSC\t江苏神州半导体科技股份有限公司\nSZXY\t江苏芯越半导体科技有限公司\n'

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: initial-admin.sh --jar PATH [--migrator-env-file PATH] [--non-interactive]
                        --company-catalog PATH
       initial-admin.sh --validate-only --company-catalog PATH
USAGE
}

load_non_interactive_input() {
  ADMIN_USERNAME="${SHENZHOUHR_INIT_ADMIN_USERNAME:-}"
  ADMIN_DISPLAY_NAME="${SHENZHOUHR_INIT_ADMIN_DISPLAY_NAME:-}"
  ADMIN_PASSWORD="${SHENZHOUHR_INIT_ADMIN_PASSWORD:-}"
}

validate_company_catalog() {
  [[ -n "$COMPANY_CATALOG" ]] || die 'A signed company catalog is required'
  [[ -f "$COMPANY_CATALOG" && ! -L "$COMPANY_CATALOG" && -r "$COMPANY_CATALOG" ]] \
    || die "Company catalog must be a readable regular file, not a symlink: $COMPANY_CATALOG"
  command -v cmp >/dev/null 2>&1 || die 'cmp is required to validate the company catalog'
  cmp -s -- "$COMPANY_CATALOG" <(printf '%s' "$EXPECTED_COMPANY_CATALOG") \
    || die 'Company catalog differs from the signed four-company release contract'
}

print_company_catalog() {
  local company_code company_name
  printf '%s\n' 'The signed release will create these four companies:'
  while IFS=$'\t' read -r company_code company_name; do
    printf '  %s  %s\n' "$company_code" "$company_name"
  done < "$COMPANY_CATALOG"
}

validate_input() {
  [[ "$ADMIN_USERNAME" =~ ^[A-Za-z0-9._-]{3,64}$ ]] \
    || die 'Initial admin username must be 3-64 safe characters'
  [[ -n "$ADMIN_DISPLAY_NAME" && "$ADMIN_DISPLAY_NAME" != *$'\n'* \
      && ${#ADMIN_DISPLAY_NAME} -le 100 ]] \
    || die 'Initial admin display name must be 1-100 characters on one line'
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
    --company-catalog) COMPANY_CATALOG="$2"; shift 2 ;;
    --non-interactive) INPUT_MODE="non-interactive"; shift ;;
    --validate-only) INPUT_MODE="validate-only"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

validate_company_catalog

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
  print_company_catalog
  read -r -p "Type $EXPECTED_COMPANY_CODES to confirm all four companies: " \
    COMPANY_CONFIRMATION
  [[ "$COMPANY_CONFIRMATION" == "$EXPECTED_COMPANY_CODES" ]] \
    || die 'Four-company initialization was not confirmed'
  unset COMPANY_CONFIRMATION
  read -r -p 'Initial admin username [admin]: ' ADMIN_USERNAME
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
  read -r -p 'Initial admin display name [系统管理员]: ' ADMIN_DISPLAY_NAME
  ADMIN_DISPLAY_NAME="${ADMIN_DISPLAY_NAME:-系统管理员}"
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
export SHENZHOUHR_INIT_COMPANY_CATALOG="$COMPANY_CATALOG"
export SHENZHOUHR_INIT_ADMIN_PASSWORD="$ADMIN_PASSWORD"
unset ADMIN_PASSWORD

EXTRACT_DIR="$(mktemp -d /tmp/shenzhouhr-admin.XXXXXX)"
trap 'rm -rf -- "$EXTRACT_DIR"' EXIT
(cd "$EXTRACT_DIR" && "$JAR_BIN" xf "$JAR_PATH" BOOT-INF/classes BOOT-INF/lib)

CLASSPATH="$EXTRACT_DIR/BOOT-INF/classes:$EXTRACT_DIR/BOOT-INF/lib/*"
"$JAVA_BIN" -cp "$CLASSPATH" \
  com.szsemicon.hr.identityaccess.infrastructure.bootstrap.InitialProductionAdminCommand

printf '%s\n' \
  'Four companies and their initial administrator were created. The first login must change its password.'
