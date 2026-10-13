#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[shenzhouhr-mysql8410] ERROR: shell tracing must be disabled.\n' >&2
  exit 1
fi

umask 077
export LC_ALL=C
export LANG=C

readonly MYSQL8410_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly MYSQL8410_PROJECT_ROOT="$(cd "${MYSQL8410_SCRIPT_DIR}/../.." && pwd -P)"
readonly MYSQL8410_EXPECTED_REPOSITORY_ROOT="$MYSQL8410_PROJECT_ROOT"
readonly MYSQL8410_VERSION="8.4.10"
readonly MYSQL8410_SOURCE_ARCHIVE="mysql-8.4.10.tar.gz"
readonly MYSQL8410_SOURCE_URL="https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.10.tar.gz"
readonly MYSQL8410_SOURCE_SHA256="d57a6730baef14ae118f7f4a6e02845b5b50933758df61fb06e104f27ccc8f96"
readonly MYSQL8410_HOST="127.0.0.1"
readonly MYSQL8410_PORT="13306"
readonly MYSQL8410_EXISTING_BASEDIR="/usr/local/mysql"
readonly MYSQL8410_EXISTING_DATADIR="/usr/local/mysql/data"
readonly MYSQL8410_EXISTING_SOCKET="/tmp/mysql.sock"
readonly MYSQL8410_EXISTING_PORT="3306"
readonly MYSQL8410_EXISTING_VERSION="8.0.34"
readonly MYSQL8410_EXISTING_SERVICE_PLIST="/Library/LaunchDaemons/com.oracle.oss.mysql.mysqld.plist"
readonly MYSQL8410_EXISTING_SERVICE_LABEL="com.oracle.oss.mysql.mysqld"
readonly MYSQL8410_LD_WRAPPER="${MYSQL8410_SCRIPT_DIR}/mysql8410-ld-wrapper.sh"
readonly MYSQL8410_CXX_WRAPPER="${MYSQL8410_SCRIPT_DIR}/mysql8410-cxx-wrapper.sh"

MYSQL8410_EXECUTE="false"
MYSQL8410_COMMAND="plan"
MYSQL8410_COMMAND_SET="false"
MYSQL8410_RUN_ID=""
MYSQL8410_BUILD_JOBS="4"
MYSQL8410_ISOLATION_ROOT="${HOME}/.local/share/shenzhouhr/mysql-8.4.10-isolated"
MYSQL8410_EVIDENCE_DIR=""
MYSQL8410_STARTED_BY_THIS_PROCESS="false"
MYSQL8410_BISON_BIN=""

mysql8410_log() {
  printf '[shenzhouhr-mysql8410] %s\n' "$*"
}

mysql8410_fail() {
  printf '[shenzhouhr-mysql8410] ERROR: %s\n' "$*" >&2
  exit 1
}

mysql8410_usage() {
  cat <<'USAGE'
Usage:
  mysql8410-isolated.sh [OPTIONS] [COMMAND]

Commands:
  plan       Print the exact immutable source and isolated runtime plan (default).
  preflight  Record existing MySQL 8.0.34 identity and verify build/runtime safety.
  build      Download, SHA256-verify, extract, build and install MySQL 8.4.10.
  init       Initialize only the isolated datadir and secure its local root account.
  start      Start only the isolated 8.4.10 server on 127.0.0.1:13306.
  verify     Query the running server and require exact 8.4.10/path/port identity.
  stop       Stop only the PID proven to be the isolated 8.4.10 server.
  all        Run preflight, build, init, start, verify and stop in that order.

Options:
  --execute                 Required for every command except plan.
  --run-id ID               Required with --execute; 6-64 lowercase safe characters.
  --root ABSOLUTE_PATH      Isolated cache/source/build/install/data/runtime root.
  --evidence-dir ABS_PATH   Evidence directory under the isolated root or the
                            repository's docs/verification/wave3/runs tree.
  --jobs N                  Parallel build jobs, 1-8 (default: 4).
  --help                    Show this help.

No command writes to /usr/local/mysql, /usr/local/mysql/data, /tmp/mysql.sock,
port 3306, launchd, PATH or system symlinks. The script contains no deletion or
reset path. Runtime credentials are generated as lowercase hexadecimal and kept
only in a mode-0600 file below the isolated root; they are never printed.
USAGE
}

mysql8410_parse_arguments() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --execute)
        MYSQL8410_EXECUTE="true"
        shift
        ;;
      --run-id)
        [[ $# -ge 2 ]] || mysql8410_fail "--run-id requires a value."
        MYSQL8410_RUN_ID="$2"
        shift 2
        ;;
      --root)
        [[ $# -ge 2 ]] || mysql8410_fail "--root requires a value."
        MYSQL8410_ISOLATION_ROOT="$2"
        shift 2
        ;;
      --evidence-dir)
        [[ $# -ge 2 ]] || mysql8410_fail "--evidence-dir requires a value."
        MYSQL8410_EVIDENCE_DIR="$2"
        shift 2
        ;;
      --jobs)
        [[ $# -ge 2 ]] || mysql8410_fail "--jobs requires a value."
        MYSQL8410_BUILD_JOBS="$2"
        shift 2
        ;;
      --help | -h)
        mysql8410_usage
        exit 0
        ;;
      -*)
        mysql8410_fail "Unknown option: $1"
        ;;
      *)
        [[ "$MYSQL8410_COMMAND_SET" == "false" ]] \
          || mysql8410_fail "Only one command may be supplied."
        MYSQL8410_COMMAND="$1"
        MYSQL8410_COMMAND_SET="true"
        shift
        ;;
    esac
  done
}

mysql8410_sha256() {
  shasum -a 256 "$1" | awk '{print $1}'
}

mysql8410_assert_plain_absolute_path() {
  local path_value="$1"
  local label="$2"
  [[ "$path_value" = /* ]] || mysql8410_fail "${label} must be absolute."
  [[ "$path_value" =~ ^/[A-Za-z0-9._/-]+$ ]] \
    || mysql8410_fail "${label} may contain only ASCII path-safe characters."
  case "/${path_value#/}/" in
    */../* | */./*) mysql8410_fail "${label} must not contain dot segments." ;;
  esac
}

mysql8410_assert_no_symlink_ancestors() {
  local path_value="$1"
  local cursor="$path_value"
  while [[ "$cursor" != "/" ]]; do
    [[ ! -L "$cursor" ]] \
      || mysql8410_fail "Symbolic links are forbidden in managed paths: ${cursor}"
    cursor="$(dirname "$cursor")"
  done
}

mysql8410_resolve_paths() {
  mysql8410_assert_plain_absolute_path "$MYSQL8410_ISOLATION_ROOT" "--root"
  case "$MYSQL8410_ISOLATION_ROOT" in
    "${HOME}"/*) ;;
    *) mysql8410_fail "--root must remain below the current user's home directory." ;;
  esac
  case "$MYSQL8410_ISOLATION_ROOT" in
    "$MYSQL8410_PROJECT_ROOT" | "$MYSQL8410_PROJECT_ROOT"/* | \
    "$MYSQL8410_EXISTING_BASEDIR" | "$MYSQL8410_EXISTING_BASEDIR"/* | \
    /usr | /usr/* | /tmp | /tmp/*)
      mysql8410_fail "--root collides with a protected or source-controlled path."
      ;;
  esac
  mysql8410_assert_no_symlink_ancestors "$MYSQL8410_ISOLATION_ROOT"

  MYSQL8410_DOWNLOAD_DIR="${MYSQL8410_ISOLATION_ROOT}/downloads"
  MYSQL8410_TARBALL="${MYSQL8410_DOWNLOAD_DIR}/${MYSQL8410_SOURCE_ARCHIVE}"
  MYSQL8410_SOURCE_PARENT="${MYSQL8410_ISOLATION_ROOT}/source"
  MYSQL8410_SOURCE_DIR="${MYSQL8410_SOURCE_PARENT}/mysql-8.4.10"
  MYSQL8410_BUILD_DIR="${MYSQL8410_ISOLATION_ROOT}/build-bundled-deps-v2"
  MYSQL8410_BASEDIR="${MYSQL8410_ISOLATION_ROOT}/install"
  MYSQL8410_DATADIR="${MYSQL8410_ISOLATION_ROOT}/data"
  MYSQL8410_RUNTIME_DIR="${MYSQL8410_ISOLATION_ROOT}/run"
  MYSQL8410_SOCKET="${MYSQL8410_RUNTIME_DIR}/mysql8410.sock"
  MYSQL8410_PID_FILE="${MYSQL8410_RUNTIME_DIR}/mysql8410.pid"
  MYSQL8410_LOG_DIR="${MYSQL8410_ISOLATION_ROOT}/log"
  MYSQL8410_ERROR_LOG="${MYSQL8410_LOG_DIR}/mysql8410.err"
  MYSQL8410_CONFIG_DIR="${MYSQL8410_ISOLATION_ROOT}/config"
  MYSQL8410_SERVER_CONFIG="${MYSQL8410_CONFIG_DIR}/my.cnf"
  MYSQL8410_SECRET_DIR="${MYSQL8410_ISOLATION_ROOT}/secrets"
  MYSQL8410_CLIENT_CONFIG="${MYSQL8410_SECRET_DIR}/root-client.cnf"
  MYSQL8410_STATE_DIR="${MYSQL8410_ISOLATION_ROOT}/state"
  MYSQL8410_INITIALIZED_MARKER="${MYSQL8410_STATE_DIR}/initialized-8.4.10"
  MYSQL8410_DB_IDENTITY_FILE="${MYSQL8410_STATE_DIR}/wave3-db-identity"
  MYSQL8410_SECURE_FILE_DIR="${MYSQL8410_ISOLATION_ROOT}/mysql-files"
  MYSQL8410_MYSQLD="${MYSQL8410_BASEDIR}/bin/mysqld"
  MYSQL8410_MYSQL="${MYSQL8410_BASEDIR}/bin/mysql"
  MYSQL8410_MYSQLADMIN="${MYSQL8410_BASEDIR}/bin/mysqladmin"

  if [[ -z "$MYSQL8410_EVIDENCE_DIR" && -n "$MYSQL8410_RUN_ID" ]]; then
    MYSQL8410_EVIDENCE_DIR="${MYSQL8410_ISOLATION_ROOT}/evidence/${MYSQL8410_RUN_ID}"
  fi
  if [[ -n "$MYSQL8410_EVIDENCE_DIR" ]]; then
    mysql8410_assert_plain_absolute_path "$MYSQL8410_EVIDENCE_DIR" "--evidence-dir"
    case "$MYSQL8410_EVIDENCE_DIR" in
      "${MYSQL8410_ISOLATION_ROOT}/evidence/"* | \
      "${MYSQL8410_PROJECT_ROOT}/docs/verification/wave3/runs/"*)
        ;;
      *)
        mysql8410_fail "--evidence-dir is outside the exact evidence allowlist."
        ;;
    esac
    mysql8410_assert_no_symlink_ancestors "$MYSQL8410_EVIDENCE_DIR"
  fi

  local socket_bytes
  socket_bytes="$(LC_ALL=C printf '%s' "$MYSQL8410_SOCKET" | wc -c | tr -d ' ')"
  [[ "$socket_bytes" -le 100 ]] \
    || mysql8410_fail "The isolated socket path exceeds the safe Unix-domain limit."
}

mysql8410_validate_invocation() {
  [[ "$MYSQL8410_PROJECT_ROOT" == "$MYSQL8410_EXPECTED_REPOSITORY_ROOT" ]] \
    || mysql8410_fail "PROJECT_ROOT_SCOPE_ERROR"
  [[ "$(pwd -P)" == "$MYSQL8410_EXPECTED_REPOSITORY_ROOT" ]] \
    || mysql8410_fail "Run this script from the exact repository root."
  [[ "$MYSQL8410_BUILD_JOBS" =~ ^[1-8]$ ]] \
    || mysql8410_fail "--jobs must be an integer from 1 through 8."
  case "$MYSQL8410_COMMAND" in
    plan | preflight | build | init | start | verify | stop | all) ;;
    *) mysql8410_fail "Unknown command: ${MYSQL8410_COMMAND}" ;;
  esac
  if [[ "$MYSQL8410_COMMAND" != "plan" ]]; then
    [[ "$MYSQL8410_EXECUTE" == "true" ]] \
      || mysql8410_fail "${MYSQL8410_COMMAND} requires --execute."
    [[ "$MYSQL8410_RUN_ID" =~ ^[a-z0-9][a-z0-9._-]{5,63}$ ]] \
      || mysql8410_fail "--execute requires a valid --run-id."
    [[ -n "$MYSQL8410_EVIDENCE_DIR" ]] \
      || mysql8410_fail "--execute requires an evidence directory."
  fi
}

mysql8410_print_plan() {
  cat <<PLAN
MySQL 8.4.10 isolated source-build plan
  source filename : ${MYSQL8410_SOURCE_ARCHIVE}
  source URL      : ${MYSQL8410_SOURCE_URL}
  source SHA256   : ${MYSQL8410_SOURCE_SHA256}
  isolation root : ${MYSQL8410_ISOLATION_ROOT}
  basedir        : ${MYSQL8410_BASEDIR}
  datadir        : ${MYSQL8410_DATADIR}
  bind           : ${MYSQL8410_HOST}:${MYSQL8410_PORT}
  socket         : ${MYSQL8410_SOCKET}
  pid            : ${MYSQL8410_PID_FILE}
  log            : ${MYSQL8410_ERROR_LOG}
  existing guard : ${MYSQL8410_EXISTING_BASEDIR} ${MYSQL8410_EXISTING_VERSION}
                   ${MYSQL8410_EXISTING_SOCKET} port ${MYSQL8410_EXISTING_PORT}
  mode           : plan only; no directory creation, download, build or process action
PLAN
}

mysql8410_extract_core_version() {
  local version_text="$1"
  if [[ "$version_text" =~ ([0-9]+\.[0-9]+\.[0-9]+) ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

mysql8410_existing_process_pid() {
  ps -axo pid=,command= \
    | awk '$2 == "/usr/local/mysql/bin/mysqld" {print $1}'
}

mysql8410_capture_existing_identity() {
  local existing_pid
  local pid_count
  local process_command
  local process_parent
  local binary_version_line
  local binary_version_core
  local service_label
  local launch_output
  local launch_state
  local launch_pid
  local port_pid
  local socket_stat

  [[ -L "$MYSQL8410_EXISTING_BASEDIR" ]] \
    || mysql8410_fail "Existing /usr/local/mysql is no longer the expected symlink."
  [[ -x "${MYSQL8410_EXISTING_BASEDIR}/bin/mysqld" ]] \
    || mysql8410_fail "Existing MySQL server binary is unavailable."
  [[ -d "$MYSQL8410_EXISTING_DATADIR" ]] \
    || mysql8410_fail "Existing MySQL datadir is unavailable."
  [[ -S "$MYSQL8410_EXISTING_SOCKET" ]] \
    || mysql8410_fail "Existing MySQL socket is unavailable."
  [[ -f "$MYSQL8410_EXISTING_SERVICE_PLIST" && ! -L "$MYSQL8410_EXISTING_SERVICE_PLIST" ]] \
    || mysql8410_fail "Existing MySQL launchd plist is unavailable or indirect."

  existing_pid="$(mysql8410_existing_process_pid)"
  pid_count="$(printf '%s\n' "$existing_pid" | awk 'NF {count++} END {print count+0}')"
  [[ "$pid_count" == "1" && "$existing_pid" =~ ^[0-9]+$ ]] \
    || mysql8410_fail "Expected exactly one running /usr/local/mysql/bin/mysqld."

  process_command="$(ps -p "$existing_pid" -o command=)"
  [[ "$process_command" == *"--basedir=${MYSQL8410_EXISTING_BASEDIR}"* ]] \
    || mysql8410_fail "Existing MySQL basedir identity changed."
  [[ "$process_command" == *"--datadir=${MYSQL8410_EXISTING_DATADIR}"* ]] \
    || mysql8410_fail "Existing MySQL datadir identity changed."
  process_parent="$(ps -p "$existing_pid" -o ppid= | tr -d ' ')"
  [[ "$process_parent" == "1" ]] \
    || mysql8410_fail "Existing MySQL is no longer launchd-owned."

  binary_version_line="$("${MYSQL8410_EXISTING_BASEDIR}/bin/mysqld" --version)"
  binary_version_core="$(mysql8410_extract_core_version "$binary_version_line")" \
    || mysql8410_fail "Cannot normalize the existing MySQL version."
  [[ "$binary_version_core" == "$MYSQL8410_EXISTING_VERSION" ]] \
    || mysql8410_fail "Existing MySQL is not exactly 8.0.34."

  service_label="$(plutil -extract Label raw -o - "$MYSQL8410_EXISTING_SERVICE_PLIST")"
  [[ "$service_label" == "$MYSQL8410_EXISTING_SERVICE_LABEL" ]] \
    || mysql8410_fail "Existing MySQL launchd label changed."
  launch_output="$(launchctl print "system/${MYSQL8410_EXISTING_SERVICE_LABEL}")" \
    || mysql8410_fail "Existing MySQL launchd service is unavailable."
  launch_state="$(printf '%s\n' "$launch_output" \
    | awk -F' = ' '/^[[:space:]]*state = / {print $2; exit}')"
  launch_pid="$(printf '%s\n' "$launch_output" \
    | awk -F' = ' '/^[[:space:]]*pid = / {print $2; exit}')"
  [[ "$launch_state" == "running" && "$launch_pid" == "$existing_pid" ]] \
    || mysql8410_fail "Existing MySQL launchd runtime identity changed."

  port_pid="$(netstat -anv -p tcp 2>/dev/null \
    | awk '$1 ~ /^tcp/ && $4 ~ /[.*]3306$/ && $6 == "LISTEN" {print $9}')"
  [[ "$port_pid" == "$existing_pid" ]] \
    || mysql8410_fail "Existing MySQL port 3306 listener identity changed."
  socket_stat="$(stat -f '%HT|%Sp|%u|%g|%i' "$MYSQL8410_EXISTING_SOCKET")"

  printf 'basedir=%s\n' "$MYSQL8410_EXISTING_BASEDIR"
  printf 'basedir_link=%s\n' "$(readlink "$MYSQL8410_EXISTING_BASEDIR")"
  printf 'binary_sha256=%s\n' "$(mysql8410_sha256 "${MYSQL8410_EXISTING_BASEDIR}/bin/mysqld")"
  printf 'datadir=%s\n' "$MYSQL8410_EXISTING_DATADIR"
  printf 'pid=%s\n' "$existing_pid"
  printf 'ppid=%s\n' "$process_parent"
  printf 'port=%s\n' "$MYSQL8410_EXISTING_PORT"
  printf 'port_listener_pid=%s\n' "$port_pid"
  printf 'process_command=%s\n' "$process_command"
  printf 'service_label=%s\n' "$service_label"
  printf 'service_plist=%s\n' "$MYSQL8410_EXISTING_SERVICE_PLIST"
  printf 'service_plist_sha256=%s\n' "$(mysql8410_sha256 "$MYSQL8410_EXISTING_SERVICE_PLIST")"
  printf 'service_state=%s\n' "$launch_state"
  printf 'socket=%s\n' "$MYSQL8410_EXISTING_SOCKET"
  printf 'socket_stat=%s\n' "$socket_stat"
  printf 'version=%s\n' "$binary_version_core"
}

mysql8410_prepare_evidence() {
  mkdir -p "$MYSQL8410_EVIDENCE_DIR"
  [[ "$(cd "$MYSQL8410_EVIDENCE_DIR" && pwd -P)" == "$MYSQL8410_EVIDENCE_DIR" ]] \
    || mysql8410_fail "Evidence directory resolved through an unexpected path."
  local context_file="${MYSQL8410_EVIDENCE_DIR}/${MYSQL8410_COMMAND}-run-context.tsv"
  [[ ! -e "$context_file" && ! -L "$context_file" ]] \
    || mysql8410_fail "This command/run evidence path already exists."
  {
    printf 'command=%s\n' "$MYSQL8410_COMMAND"
    printf 'run_id=%s\n' "$MYSQL8410_RUN_ID"
    printf 'source_archive=%s\n' "$MYSQL8410_SOURCE_ARCHIVE"
    printf 'source_url=%s\n' "$MYSQL8410_SOURCE_URL"
    printf 'source_sha256=%s\n' "$MYSQL8410_SOURCE_SHA256"
    printf 'ld_wrapper=%s\n' "$MYSQL8410_LD_WRAPPER"
    printf 'ld_wrapper_sha256=%s\n' "$(mysql8410_sha256 "$MYSQL8410_LD_WRAPPER")"
    printf 'cxx_wrapper=%s\n' "$MYSQL8410_CXX_WRAPPER"
    printf 'cxx_wrapper_sha256=%s\n' "$(mysql8410_sha256 "$MYSQL8410_CXX_WRAPPER")"
    printf 'root=%s\n' "$MYSQL8410_ISOLATION_ROOT"
    printf 'basedir=%s\n' "$MYSQL8410_BASEDIR"
    printf 'datadir=%s\n' "$MYSQL8410_DATADIR"
    printf 'host=%s\n' "$MYSQL8410_HOST"
    printf 'port=%s\n' "$MYSQL8410_PORT"
    printf 'socket=%s\n' "$MYSQL8410_SOCKET"
    printf 'pid_file=%s\n' "$MYSQL8410_PID_FILE"
    printf 'error_log=%s\n' "$MYSQL8410_ERROR_LOG"
  } > "$context_file"
  chmod 600 "$context_file"
}

mysql8410_start_transcript() {
  local transcript="${MYSQL8410_EVIDENCE_DIR}/${MYSQL8410_COMMAND}.log"
  [[ ! -e "$transcript" && ! -L "$transcript" ]] \
    || mysql8410_fail "This command/run transcript already exists."
  exec > >(/usr/bin/tee "$transcript") 2>&1
}

mysql8410_capture_before() {
  local snapshot="${MYSQL8410_EVIDENCE_DIR}/existing-mysql8034-${MYSQL8410_COMMAND}-before.tsv"
  [[ ! -e "$snapshot" && ! -L "$snapshot" ]] \
    || mysql8410_fail "Existing-instance before snapshot already exists."
  mysql8410_capture_existing_identity > "$snapshot"
  chmod 600 "$snapshot"
}

mysql8410_capture_after_and_compare() {
  local before="${MYSQL8410_EVIDENCE_DIR}/existing-mysql8034-${MYSQL8410_COMMAND}-before.tsv"
  local after="${MYSQL8410_EVIDENCE_DIR}/existing-mysql8034-${MYSQL8410_COMMAND}-after.tsv"
  [[ -f "$before" && ! -L "$before" ]] \
    || mysql8410_fail "Existing-instance before snapshot is missing."
  [[ ! -e "$after" && ! -L "$after" ]] \
    || mysql8410_fail "Existing-instance after snapshot already exists."
  mysql8410_capture_existing_identity > "$after"
  chmod 600 "$after"
  cmp -s "$before" "$after" \
    || mysql8410_fail "Existing MySQL 8.0.34 identity changed byte-for-byte."
  mysql8410_log "MYSQL8034_UNCHANGED=PASS sha256=$(mysql8410_sha256 "$after")"
}

mysql8410_require_command() {
  command -v "$1" >/dev/null 2>&1 \
    || mysql8410_fail "Required command is unavailable: $1"
}

mysql8410_assert_build_preflight() {
  local command_name
  local free_kib
  local memory_bytes
  local openssl_prefix
  local ncurses_prefix
  local bison_version_line
  local bison_major
  local bison_minor
  local bison_patch

  [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "x86_64" ]] \
    || mysql8410_fail "This reviewed build profile requires macOS x86_64."
  [[ -x "$MYSQL8410_LD_WRAPPER" && ! -L "$MYSQL8410_LD_WRAPPER" ]] \
    || mysql8410_fail "The reviewed ld64 compatibility wrapper is unavailable."
  [[ -x "$MYSQL8410_CXX_WRAPPER" && ! -L "$MYSQL8410_CXX_WRAPPER" ]] \
    || mysql8410_fail "The reviewed C++ include-isolation wrapper is unavailable."
  printf '%s\n' 'int main(void) { return 0; }' \
    | /usr/bin/clang "-fuse-ld=${MYSQL8410_LD_WRAPPER}" \
      -Wl,-no_warn_duplicate_libraries -x c - -o /dev/null \
      || mysql8410_fail "The reviewed ld64 compatibility wrapper probe failed."
  for command_name in cmake make clang clang++ curl shasum tar gzip awk sed \
    stat ps netstat plutil launchctl openssl cmp; do
    mysql8410_require_command "$command_name"
  done
  [[ -x /usr/local/bin/brew ]] \
    || mysql8410_fail "The reviewed Homebrew dependency resolver is unavailable."
  if [[ -x /usr/local/opt/bison/bin/bison ]]; then
    MYSQL8410_BISON_BIN="/usr/local/opt/bison/bin/bison"
  else
    MYSQL8410_BISON_BIN="$(command -v bison 2>/dev/null || true)"
  fi
  [[ -n "$MYSQL8410_BISON_BIN" && "$MYSQL8410_BISON_BIN" = /* ]] \
    || mysql8410_fail "Bison is unavailable."
  bison_version_line="$("$MYSQL8410_BISON_BIN" --version | sed -n '1p')"
  if [[ "$bison_version_line" =~ ([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
    bison_major="${BASH_REMATCH[1]}"
    bison_minor="${BASH_REMATCH[2]}"
    bison_patch="${BASH_REMATCH[3]}"
  elif [[ "$bison_version_line" =~ ([0-9]+)\.([0-9]+) ]]; then
    bison_major="${BASH_REMATCH[1]}"
    bison_minor="${BASH_REMATCH[2]}"
    bison_patch="0"
  else
    mysql8410_fail "Cannot normalize the Bison version."
  fi
  if (( bison_major < 3 )) \
    || (( bison_major == 3 && bison_minor == 0 && bison_patch < 4 )); then
    mysql8410_fail "MySQL 8.4.10 requires Bison 3.0.4 or newer."
  fi
  openssl_prefix="$(/usr/local/bin/brew --prefix openssl@3)"
  ncurses_prefix="$(/usr/local/bin/brew --prefix ncurses)"
  [[ -f "${openssl_prefix}/include/openssl/ssl.h" \
      && -f "${openssl_prefix}/lib/libssl.dylib" \
      && -f "${openssl_prefix}/lib/libcrypto.dylib" ]] \
    || mysql8410_fail "OpenSSL 3 headers/libraries are incomplete."
  [[ -f "${ncurses_prefix}/include/ncurses.h" \
      && -f "${ncurses_prefix}/lib/libncurses.dylib" ]] \
    || mysql8410_fail "ncurses headers/libraries are incomplete."

  free_kib="$(df -k "${HOME}" | awk 'NR == 2 {print $4}')"
  [[ "$free_kib" =~ ^[0-9]+$ && "$free_kib" -ge 31457280 ]] \
    || mysql8410_fail "At least 30 GiB of free disk is required."
  memory_bytes="$(sysctl -n hw.memsize)"
  [[ "$memory_bytes" =~ ^[0-9]+$ && "$memory_bytes" -ge 8589934592 ]] \
    || mysql8410_fail "At least 8 GiB of memory is required."
  mysql8410_log "MYSQL8410_BUILD_PREFLIGHT=PASS jobs=${MYSQL8410_BUILD_JOBS} free_kib=${free_kib} memory_bytes=${memory_bytes} bison=${bison_major}.${bison_minor}.${bison_patch}"
}

mysql8410_listen_rows() {
  local port_value="$1"
  netstat -anv -p tcp 2>/dev/null \
    | awk -v suffix=".${port_value}" \
      '$1 ~ /^tcp/ && substr($4, length($4) - length(suffix) + 1) == suffix && $6 == "LISTEN" {print}'
}

mysql8410_assert_isolation_available() {
  local listen_rows
  listen_rows="$(mysql8410_listen_rows "$MYSQL8410_PORT")"
  [[ -z "$listen_rows" ]] \
    || mysql8410_fail "Port 13306 is already occupied."
  [[ ! -e "$MYSQL8410_SOCKET" && ! -L "$MYSQL8410_SOCKET" ]] \
    || mysql8410_fail "The isolated socket path already exists."
  if [[ -e "$MYSQL8410_PID_FILE" || -L "$MYSQL8410_PID_FILE" ]]; then
    mysql8410_fail "The isolated PID path already exists while no listener is proven."
  fi
  mysql8410_log "MYSQL8410_ISOLATION_AVAILABLE=PASS host=${MYSQL8410_HOST} port=${MYSQL8410_PORT}"
}

mysql8410_verify_source_archive() {
  local actual_sha
  [[ -f "$MYSQL8410_TARBALL" && ! -L "$MYSQL8410_TARBALL" ]] \
    || mysql8410_fail "The official source archive is unavailable."
  actual_sha="$(mysql8410_sha256 "$MYSQL8410_TARBALL")"
  [[ "$actual_sha" == "$MYSQL8410_SOURCE_SHA256" ]] \
    || mysql8410_fail "MySQL 8.4.10 source archive SHA256 mismatch."
  printf '%s  %s\n' "$actual_sha" "$MYSQL8410_SOURCE_ARCHIVE" \
    > "${MYSQL8410_EVIDENCE_DIR}/${MYSQL8410_COMMAND}-tarball.sha256"
  mysql8410_log "MYSQL8410_SOURCE_SHA256=PASS sha256=${actual_sha}"
}

mysql8410_download_source() {
  local partial_archive
  mkdir -p "$MYSQL8410_DOWNLOAD_DIR"
  if [[ -f "$MYSQL8410_TARBALL" && ! -L "$MYSQL8410_TARBALL" ]]; then
    mysql8410_verify_source_archive
    return
  fi
  [[ ! -e "$MYSQL8410_TARBALL" && ! -L "$MYSQL8410_TARBALL" ]] \
    || mysql8410_fail "The source archive target is not a regular file."
  partial_archive="${MYSQL8410_TARBALL}.partial-${MYSQL8410_RUN_ID}"
  [[ ! -L "$partial_archive" ]] \
    || mysql8410_fail "The partial source archive must not be a symbolic link."
  curl --proto '=https' --tlsv1.2 --fail --location --show-error \
    --continue-at - --output "$partial_archive" "$MYSQL8410_SOURCE_URL"
  [[ "$(mysql8410_sha256 "$partial_archive")" == "$MYSQL8410_SOURCE_SHA256" ]] \
    || mysql8410_fail "Downloaded source archive SHA256 mismatch."
  mv "$partial_archive" "$MYSQL8410_TARBALL"
  mysql8410_verify_source_archive
}

mysql8410_extract_source() {
  local stage_dir
  if [[ -d "$MYSQL8410_SOURCE_DIR" && ! -L "$MYSQL8410_SOURCE_DIR" ]]; then
    mysql8410_log "MYSQL8410_SOURCE_EXTRACT=PASS reused=true"
    return
  fi
  [[ ! -e "$MYSQL8410_SOURCE_DIR" && ! -L "$MYSQL8410_SOURCE_DIR" ]] \
    || mysql8410_fail "The source directory target is not reusable."
  tar -tzf "$MYSQL8410_TARBALL" \
    | awk '
      /^\// {bad=1}
      /(^|\/)\.\.(\/|$)/ {bad=1}
      {
        top=$0
        sub(/\/.*/, "", top)
        if (top != "mysql-8.4.10") bad=1
      }
      END {exit bad}
    ' || mysql8410_fail "Source archive path framing is unsafe or unexpected."

  mkdir -p "$MYSQL8410_SOURCE_PARENT"
  stage_dir="${MYSQL8410_SOURCE_PARENT}/extract-${MYSQL8410_RUN_ID}"
  [[ ! -e "$stage_dir" && ! -L "$stage_dir" ]] \
    || mysql8410_fail "The extraction staging path already exists."
  mkdir "$stage_dir"
  tar -xzf "$MYSQL8410_TARBALL" -C "$stage_dir"
  [[ -f "${stage_dir}/mysql-8.4.10/CMakeLists.txt" ]] \
    || mysql8410_fail "Extracted source does not contain the expected root."
  mv "${stage_dir}/mysql-8.4.10" "$MYSQL8410_SOURCE_DIR"
  rmdir "$stage_dir"
  mysql8410_log "MYSQL8410_SOURCE_EXTRACT=PASS reused=false"
}

mysql8410_binary_core_version() {
  local version_line
  version_line="$("$MYSQL8410_MYSQLD" --no-defaults --version)"
  mysql8410_extract_core_version "$version_line"
}

mysql8410_assert_installed_binaries() {
  local installed_core
  [[ -x "$MYSQL8410_MYSQLD" && -x "$MYSQL8410_MYSQL" \
      && -x "$MYSQL8410_MYSQLADMIN" ]] \
    || mysql8410_fail "Installed MySQL server/client binaries are incomplete."
  installed_core="$(mysql8410_binary_core_version)" \
    || mysql8410_fail "Cannot normalize installed mysqld version."
  [[ "$installed_core" == "$MYSQL8410_VERSION" ]] \
    || mysql8410_fail "Installed mysqld is not exactly 8.4.10."
  mysql8410_log "MYSQL8410_BUILD_INSTALL=PASS version=${installed_core} basedir=${MYSQL8410_BASEDIR}"
}

mysql8410_build_server() {
  local cmake_bin
  local openssl_prefix
  local ncurses_prefix

  mysql8410_assert_build_preflight
  mysql8410_assert_isolation_available
  mysql8410_download_source
  mysql8410_extract_source
  export MYSQL8410_BUNDLED_PROTOBUF_INCLUDE="${MYSQL8410_SOURCE_DIR}/extra/protobuf/protobuf-24.4/src"
  export MYSQL8410_BUNDLED_ABSEIL_INCLUDE="${MYSQL8410_SOURCE_DIR}/extra/abseil/abseil-cpp-20230802.1"
  [[ -f "${MYSQL8410_BUNDLED_PROTOBUF_INCLUDE}/google/protobuf/any.pb.h" ]] \
    || mysql8410_fail "The official bundled Protobuf headers are unavailable."
  [[ -f "${MYSQL8410_BUNDLED_ABSEIL_INCLUDE}/absl/base/config.h" ]] \
    || mysql8410_fail "The official bundled Abseil headers are unavailable."
  if [[ -x "$MYSQL8410_MYSQLD" ]]; then
    mysql8410_assert_installed_binaries
    return
  fi

  cmake_bin="$(command -v cmake)"
  openssl_prefix="$(/usr/local/bin/brew --prefix openssl@3)"
  ncurses_prefix="$(/usr/local/bin/brew --prefix ncurses)"
  mkdir -p "$MYSQL8410_BUILD_DIR" "$MYSQL8410_BASEDIR"
  "$cmake_bin" \
    -S "$MYSQL8410_SOURCE_DIR" \
    -B "$MYSQL8410_BUILD_DIR" \
    -DCMAKE_BUILD_TYPE:STRING=Release \
    -DCMAKE_INSTALL_PREFIX:PATH="$MYSQL8410_BASEDIR" \
    -DCMAKE_CXX_COMPILER:FILEPATH="$MYSQL8410_CXX_WRAPPER" \
    -DMYSQL_DATADIR:PATH="$MYSQL8410_DATADIR" \
    -DMYSQL_TCP_PORT:STRING="$MYSQL8410_PORT" \
    -DMYSQL_UNIX_ADDR:FILEPATH="$MYSQL8410_SOCKET" \
    -DSYSCONFDIR:PATH="$MYSQL8410_CONFIG_DIR" \
    -DBISON_EXECUTABLE:FILEPATH="$MYSQL8410_BISON_BIN" \
    -DCMAKE_EXE_LINKER_FLAGS:STRING="-fuse-ld=${MYSQL8410_LD_WRAPPER}" \
    -DCMAKE_SHARED_LINKER_FLAGS:STRING="-fuse-ld=${MYSQL8410_LD_WRAPPER}" \
    -DCMAKE_MODULE_LINKER_FLAGS:STRING="-fuse-ld=${MYSQL8410_LD_WRAPPER}" \
    -DWITH_SSL:PATH="$openssl_prefix" \
    -DCURSES_INCLUDE_PATH:PATH="${ncurses_prefix}/include" \
    -DCURSES_LIBRARY:FILEPATH="${ncurses_prefix}/lib/libncurses.dylib" \
    -DWITH_PROTOBUF:STRING=bundled \
    -DWITH_ROUTER:BOOL=OFF \
    -DWITH_NDBCLUSTER_STORAGE_ENGINE:BOOL=OFF \
    -DWITH_UNIT_TESTS:BOOL=OFF \
    -DWITH_TEST_TRACE_PLUGIN:BOOL=OFF \
    -DWITH_LTO:BOOL=OFF
  "$cmake_bin" --build "$MYSQL8410_BUILD_DIR" \
    --parallel "$MYSQL8410_BUILD_JOBS" --target install
  mysql8410_assert_installed_binaries
}

mysql8410_write_server_config() {
  local temporary_config="${MYSQL8410_SERVER_CONFIG}.tmp-${MYSQL8410_RUN_ID}"
  mkdir -p "$MYSQL8410_CONFIG_DIR" "$MYSQL8410_RUNTIME_DIR" "$MYSQL8410_LOG_DIR" \
    "$MYSQL8410_SECURE_FILE_DIR" "$MYSQL8410_STATE_DIR" "$MYSQL8410_SECRET_DIR"
  chmod 700 "$MYSQL8410_RUNTIME_DIR" "$MYSQL8410_LOG_DIR" \
    "$MYSQL8410_SECURE_FILE_DIR" "$MYSQL8410_STATE_DIR" "$MYSQL8410_SECRET_DIR"
  [[ ! -L "$temporary_config" ]] \
    || mysql8410_fail "Temporary server configuration must not be indirect."
  {
    printf '[mysqld]\n'
    printf 'basedir=%s\n' "$MYSQL8410_BASEDIR"
    printf 'datadir=%s\n' "$MYSQL8410_DATADIR"
    printf 'bind-address=%s\n' "$MYSQL8410_HOST"
    printf 'port=%s\n' "$MYSQL8410_PORT"
    printf 'socket=%s\n' "$MYSQL8410_SOCKET"
    printf 'pid-file=%s\n' "$MYSQL8410_PID_FILE"
    printf 'log-error=%s\n' "$MYSQL8410_ERROR_LOG"
    printf 'secure-file-priv=%s\n' "$MYSQL8410_SECURE_FILE_DIR"
    printf 'mysqlx=0\n'
    printf 'skip-name-resolve=ON\n'
    printf 'local-infile=OFF\n'
    printf 'log-error-verbosity=3\n'
  } > "$temporary_config"
  chmod 600 "$temporary_config"
  mv "$temporary_config" "$MYSQL8410_SERVER_CONFIG"
}

mysql8410_is_own_pid() {
  local pid_value="$1"
  local process_command
  [[ "$pid_value" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid_value" 2>/dev/null || return 1
  process_command="$(ps -p "$pid_value" -o command= 2>/dev/null || true)"
  [[ "$process_command" == "${MYSQL8410_MYSQLD} "* \
      || "$process_command" == "$MYSQL8410_MYSQLD" ]]
}

mysql8410_pid_from_file() {
  local pid_value
  [[ -f "$MYSQL8410_PID_FILE" && ! -L "$MYSQL8410_PID_FILE" ]] || return 1
  pid_value="$(tr -d '[:space:]' < "$MYSQL8410_PID_FILE")"
  mysql8410_is_own_pid "$pid_value" || return 1
  printf '%s' "$pid_value"
}

mysql8410_wait_for_socket_ping() {
  local mode="$1"
  local attempt
  for attempt in $(seq 1 120); do
    if [[ "$mode" == "insecure" ]]; then
      if "$MYSQL8410_MYSQLADMIN" --no-defaults --protocol=socket \
        --socket="$MYSQL8410_SOCKET" --user=root ping >/dev/null 2>&1; then
        return 0
      fi
    elif "$MYSQL8410_MYSQLADMIN" --defaults-file="$MYSQL8410_CLIENT_CONFIG" \
      ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

mysql8410_wait_for_stop() {
  local pid_value="$1"
  local attempt
  for attempt in $(seq 1 120); do
    if ! kill -0 "$pid_value" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

mysql8410_emergency_stop() {
  local pid_value
  [[ "$MYSQL8410_STARTED_BY_THIS_PROCESS" == "true" ]] || return 0
  pid_value="$(mysql8410_pid_from_file 2>/dev/null || true)"
  [[ -n "$pid_value" ]] || return 0
  if [[ -f "$MYSQL8410_CLIENT_CONFIG" && ! -L "$MYSQL8410_CLIENT_CONFIG" ]]; then
    "$MYSQL8410_MYSQLADMIN" --defaults-file="$MYSQL8410_CLIENT_CONFIG" \
      shutdown >/dev/null 2>&1 || true
  else
    kill -TERM "$pid_value" 2>/dev/null || true
  fi
  mysql8410_wait_for_stop "$pid_value" || true
}

mysql8410_write_client_config() {
  local password_value="$1"
  local temporary_config="${MYSQL8410_CLIENT_CONFIG}.tmp-${MYSQL8410_RUN_ID}"
  [[ "$password_value" =~ ^[0-9a-f]{64}$ ]] \
    || mysql8410_fail "Generated credential format is invalid."
  {
    printf '[client]\n'
    printf 'user=root\n'
    printf 'password=%s\n' "$password_value"
    printf 'protocol=socket\n'
    printf 'socket=%s\n' "$MYSQL8410_SOCKET"
  } > "$temporary_config"
  chmod 600 "$temporary_config"
  mv "$temporary_config" "$MYSQL8410_CLIENT_CONFIG"
}

mysql8410_capture_db_identity() {
  local server_uuid
  local db_identity
  local temporary_identity="${MYSQL8410_DB_IDENTITY_FILE}.tmp-${MYSQL8410_RUN_ID}"
  server_uuid="$("$MYSQL8410_MYSQL" --defaults-file="$MYSQL8410_CLIENT_CONFIG" \
    --batch --raw --skip-column-names --execute="SELECT @@server_uuid;")"
  [[ "$server_uuid" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
    || mysql8410_fail "The isolated server UUID is invalid."
  server_uuid="$(printf '%s' "$server_uuid" | tr '[:upper:]' '[:lower:]')"
  db_identity="mysql8410:${server_uuid}:shenzhou_hr_test"
  if [[ -f "$MYSQL8410_DB_IDENTITY_FILE" && ! -L "$MYSQL8410_DB_IDENTITY_FILE" ]]; then
    [[ "$(tr -d '\r\n' < "$MYSQL8410_DB_IDENTITY_FILE")" == "$db_identity" ]] \
      || mysql8410_fail "The stored W3 database identity changed."
  else
    [[ ! -e "$MYSQL8410_DB_IDENTITY_FILE" && ! -L "$MYSQL8410_DB_IDENTITY_FILE" ]] \
      || mysql8410_fail "The W3 database identity target is not reusable."
    printf '%s\n' "$db_identity" > "$temporary_identity"
    chmod 600 "$temporary_identity"
    mv "$temporary_identity" "$MYSQL8410_DB_IDENTITY_FILE"
  fi
  printf 'db_identity\t%s\nserver_uuid\t%s\ndatabase\tshenzhou_hr_test\n' \
    "$db_identity" "$server_uuid" \
    > "${MYSQL8410_EVIDENCE_DIR}/${MYSQL8410_COMMAND}-db-identity.tsv"
  mysql8410_log "W3_DB_IDENTITY=PASS db_identity=${db_identity}"
}

mysql8410_initialize_server() {
  local root_password
  local server_pid

  mysql8410_assert_installed_binaries
  if [[ -f "$MYSQL8410_INITIALIZED_MARKER" \
      && -f "$MYSQL8410_CLIENT_CONFIG" \
      && -f "$MYSQL8410_DB_IDENTITY_FILE" \
      && -d "${MYSQL8410_DATADIR}/mysql" ]]; then
    mysql8410_log "MYSQL8410_INITIALIZE=PASS reused=true"
    return
  fi
  [[ ! -e "$MYSQL8410_DATADIR" && ! -L "$MYSQL8410_DATADIR" ]] \
    || mysql8410_fail "Datadir exists without a complete 8.4.10 initialization marker."
  mysql8410_assert_isolation_available
  mysql8410_write_server_config
  mkdir "$MYSQL8410_DATADIR"
  chmod 700 "$MYSQL8410_DATADIR"
  "$MYSQL8410_MYSQLD" --defaults-file="$MYSQL8410_SERVER_CONFIG" \
    --initialize-insecure

  "$MYSQL8410_MYSQLD" --defaults-file="$MYSQL8410_SERVER_CONFIG" \
    --skip-networking=ON --daemonize
  MYSQL8410_STARTED_BY_THIS_PROCESS="true"
  mysql8410_wait_for_socket_ping insecure \
    || mysql8410_fail "Bootstrap server did not become ready."
  root_password="$(openssl rand -hex 32)"
  printf "ALTER USER 'root'@'localhost' IDENTIFIED BY '%s';\n" "$root_password" \
    | "$MYSQL8410_MYSQL" --no-defaults --protocol=socket \
      --socket="$MYSQL8410_SOCKET" --user=root >/dev/null
  mysql8410_write_client_config "$root_password"
  unset root_password
  mysql8410_capture_db_identity
  server_pid="$(mysql8410_pid_from_file)" \
    || mysql8410_fail "Cannot prove isolated bootstrap PID ownership."
  "$MYSQL8410_MYSQLADMIN" --defaults-file="$MYSQL8410_CLIENT_CONFIG" shutdown
  mysql8410_wait_for_stop "$server_pid" \
    || mysql8410_fail "Bootstrap server did not stop."
  MYSQL8410_STARTED_BY_THIS_PROCESS="false"
  [[ ! -e "$MYSQL8410_SOCKET" && ! -e "$MYSQL8410_PID_FILE" ]] \
    || mysql8410_fail "Bootstrap runtime artifacts were not removed."
  printf 'version=%s\nsource_sha256=%s\n' \
    "$MYSQL8410_VERSION" "$MYSQL8410_SOURCE_SHA256" > "$MYSQL8410_INITIALIZED_MARKER"
  chmod 600 "$MYSQL8410_INITIALIZED_MARKER"
  mysql8410_log "MYSQL8410_INITIALIZE=PASS reused=false"
}

mysql8410_start_server() {
  local server_pid
  [[ -f "$MYSQL8410_INITIALIZED_MARKER" \
      && -f "$MYSQL8410_CLIENT_CONFIG" \
      && -f "$MYSQL8410_SERVER_CONFIG" ]] \
    || mysql8410_fail "The isolated server is not fully initialized."
  mysql8410_assert_installed_binaries
  mysql8410_assert_isolation_available
  "$MYSQL8410_MYSQLD" --defaults-file="$MYSQL8410_SERVER_CONFIG" --daemonize
  MYSQL8410_STARTED_BY_THIS_PROCESS="true"
  mysql8410_wait_for_socket_ping secure \
    || mysql8410_fail "Isolated 8.4.10 server did not become ready."
  mysql8410_capture_db_identity
  server_pid="$(mysql8410_pid_from_file)" \
    || mysql8410_fail "Cannot prove isolated server PID ownership."
  mysql8410_log "MYSQL8410_START=PASS pid=${server_pid} host=${MYSQL8410_HOST} port=${MYSQL8410_PORT}"
}

mysql8410_query_server_identity() {
  "$MYSQL8410_MYSQL" --defaults-file="$MYSQL8410_CLIENT_CONFIG" \
    --batch --skip-column-names --raw \
    --execute="SELECT VERSION(), @@port, @@socket, @@datadir, @@hostname, @@bind_address, @@server_uuid;"
}

mysql8410_verify_running_server() {
  local result
  local server_version
  local server_core
  local server_port
  local server_socket
  local server_datadir
  local server_hostname
  local server_bind
  local server_uuid
  local db_identity
  local server_pid
  local listen_rows
  local identity_file="${MYSQL8410_EVIDENCE_DIR}/${MYSQL8410_COMMAND}-mysql8410-server-identity.tsv"

  mysql8410_assert_installed_binaries
  server_pid="$(mysql8410_pid_from_file)" \
    || mysql8410_fail "No proven isolated server PID is running."
  mysql8410_capture_db_identity
  result="$(mysql8410_query_server_identity)" \
    || mysql8410_fail "SELECT VERSION() identity query failed."
  IFS=$'\t' read -r server_version server_port server_socket server_datadir \
    server_hostname server_bind server_uuid <<< "$result"
  server_core="$(mysql8410_extract_core_version "$server_version")" \
    || mysql8410_fail "Cannot normalize SELECT VERSION()."
  [[ "$server_core" == "$MYSQL8410_VERSION" ]] \
    || mysql8410_fail "SELECT VERSION() SemVer core is not exactly 8.4.10."
  [[ "$server_port" == "$MYSQL8410_PORT" ]] \
    || mysql8410_fail "Running server port is not exactly 13306."
  [[ "$server_socket" == "$MYSQL8410_SOCKET" ]] \
    || mysql8410_fail "Running server socket path differs from the isolated path."
  [[ "${server_datadir%/}" == "$MYSQL8410_DATADIR" ]] \
    || mysql8410_fail "Running server datadir differs from the isolated path."
  [[ "$server_bind" == "$MYSQL8410_HOST" ]] \
    || mysql8410_fail "Running server bind address is not exactly 127.0.0.1."
  [[ "$server_uuid" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
    || mysql8410_fail "Running server UUID is invalid."
  server_uuid="$(printf '%s' "$server_uuid" | tr '[:upper:]' '[:lower:]')"
  db_identity="mysql8410:${server_uuid}:shenzhou_hr_test"
  [[ "$(tr -d '\r\n' < "$MYSQL8410_DB_IDENTITY_FILE")" == "$db_identity" ]] \
    || mysql8410_fail "Running server/database identity differs from the stored identity."

  listen_rows="$(mysql8410_listen_rows "$MYSQL8410_PORT")"
  [[ -n "$listen_rows" ]] \
    || mysql8410_fail "No TCP listener exists on port 13306."
  printf '%s\n' "$listen_rows" \
    | awk -v expected_pid="$server_pid" '
        $4 != "127.0.0.1.13306" || $9 != expected_pid {bad=1}
        END {exit bad}
      ' || mysql8410_fail "Port 13306 is not exclusively bound to 127.0.0.1 by the proven PID."

  {
    printf 'version=%s\n' "$server_version"
    printf 'version_core=%s\n' "$server_core"
    printf 'port=%s\n' "$server_port"
    printf 'socket=%s\n' "$server_socket"
    printf 'datadir=%s\n' "$server_datadir"
    printf 'hostname=%s\n' "$server_hostname"
    printf 'bind_address=%s\n' "$server_bind"
    printf 'server_uuid=%s\n' "$server_uuid"
    printf 'db_identity=%s\n' "$db_identity"
    printf 'pid=%s\n' "$server_pid"
    printf 'mysqld_sha256=%s\n' "$(mysql8410_sha256 "$MYSQL8410_MYSQLD")"
  } > "$identity_file"
  chmod 600 "$identity_file"
  mysql8410_log "W3_MYSQL8410=PASS version=${server_core} port=${server_port} db_identity=${db_identity}"
}

mysql8410_stop_server() {
  local server_pid
  server_pid="$(mysql8410_pid_from_file)" \
    || mysql8410_fail "No proven isolated server PID is running."
  [[ -f "$MYSQL8410_CLIENT_CONFIG" && ! -L "$MYSQL8410_CLIENT_CONFIG" ]] \
    || mysql8410_fail "Isolated root client configuration is unavailable."
  "$MYSQL8410_MYSQLADMIN" --defaults-file="$MYSQL8410_CLIENT_CONFIG" shutdown
  mysql8410_wait_for_stop "$server_pid" \
    || mysql8410_fail "Isolated server did not stop."
  MYSQL8410_STARTED_BY_THIS_PROCESS="false"
  [[ ! -e "$MYSQL8410_SOCKET" && ! -e "$MYSQL8410_PID_FILE" ]] \
    || mysql8410_fail "Isolated runtime socket/PID was not removed."
  mysql8410_log "MYSQL8410_STOP=PASS pid=${server_pid}"
}

mysql8410_run_preflight() {
  mysql8410_assert_build_preflight
  mysql8410_assert_isolation_available
  mysql8410_log "MYSQL8410_PREFLIGHT=PASS"
}

mysql8410_dispatch() {
  case "$MYSQL8410_COMMAND" in
    preflight)
      mysql8410_run_preflight
      ;;
    build)
      mysql8410_build_server
      ;;
    init)
      mysql8410_initialize_server
      ;;
    start)
      mysql8410_start_server
      ;;
    verify)
      mysql8410_verify_running_server
      ;;
    stop)
      mysql8410_stop_server
      ;;
    all)
      mysql8410_run_preflight
      mysql8410_build_server
      mysql8410_initialize_server
      mysql8410_start_server
      mysql8410_verify_running_server
      mysql8410_stop_server
      ;;
  esac
}

mysql8410_on_exit() {
  local exit_status=$?
  local before_snapshot
  local after_snapshot
  trap - EXIT
  mysql8410_emergency_stop
  if [[ -n "$MYSQL8410_EVIDENCE_DIR" ]]; then
    before_snapshot="${MYSQL8410_EVIDENCE_DIR}/existing-mysql8034-${MYSQL8410_COMMAND}-before.tsv"
    after_snapshot="${MYSQL8410_EVIDENCE_DIR}/existing-mysql8034-${MYSQL8410_COMMAND}-after.tsv"
    if [[ -f "$before_snapshot" && ! -e "$after_snapshot" ]]; then
      mysql8410_capture_after_and_compare
    fi
  fi
  exit "$exit_status"
}

mysql8410_main() {
  mysql8410_parse_arguments "$@"
  mysql8410_resolve_paths
  mysql8410_validate_invocation
  if [[ "$MYSQL8410_COMMAND" == "plan" ]]; then
    mysql8410_print_plan
    return
  fi

  mysql8410_prepare_evidence
  mysql8410_start_transcript
  mysql8410_capture_before
  mysql8410_dispatch
  mysql8410_capture_after_and_compare
  if [[ "$MYSQL8410_COMMAND" == "start" ]]; then
    MYSQL8410_STARTED_BY_THIS_PROCESS="false"
  fi
}

trap mysql8410_on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

mysql8410_main "$@"
