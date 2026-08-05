#!/usr/bin/env bash
set -Eeuo pipefail
set +x

JAR_PATH=""
MIGRATOR_ENV_FILE="/etc/shenzhouhr/shenzhouhr-migrator.env"
TIMEOUT_SEC="180"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: migrate.sh --jar PATH [--migrator-env-file PATH] [--timeout-sec SECONDS]
USAGE
}

while (($#)); do
  case "$1" in
    --jar) JAR_PATH="$2"; shift 2 ;;
    --migrator-env-file) MIGRATOR_ENV_FILE="$2"; shift 2 ;;
    --timeout-sec) TIMEOUT_SEC="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ -f "$JAR_PATH" ]] || die "Jar not found: $JAR_PATH"
[[ -r "$MIGRATOR_ENV_FILE" ]] || die "Migrator env not found: $MIGRATOR_ENV_FILE"
[[ "$TIMEOUT_SEC" =~ ^[0-9]+$ ]] || die 'Timeout must be an integer'

JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
[[ -n "$JAVA_BIN" ]] || die 'Java 21 is required'
JAVA_MAJOR="$($JAVA_BIN -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
[[ "$JAVA_MAJOR" == "21" ]] || die "Java 21 is required; detected: ${JAVA_MAJOR:-unknown}"
JAR_BIN="${JAR_BIN:-$(dirname -- "$JAVA_BIN")/jar}"
[[ -x "$JAR_BIN" ]] || die "A full JDK 21 is required; jar tool not found: $JAR_BIN"
"$JAR_BIN" tf "$JAR_PATH" | grep '^BOOT-INF/lib/spring-boot-flyway-.*\.jar$' >/dev/null \
  || die 'Jar is missing Spring Boot Flyway auto-configuration; migration cannot run'

set -a
# shellcheck disable=SC1090
source "$MIGRATOR_ENV_FILE"
set +a
[[ "${SHENZHOUHR_FLYWAY_ENABLED:-false}" == "true" ]] || die 'Migrator env must enable Flyway'

LOG_FILE="$(mktemp /tmp/shenzhouhr-migrate.XXXXXX.log)"
PID=""
cleanup() {
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    kill -TERM "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  rm -f -- "$LOG_FILE"
}
trap cleanup EXIT

printf 'Running one-time database migration...\n'
"$JAVA_BIN" -jar "$JAR_PATH" \
  --server.address=127.0.0.1 \
  --server.port=0 \
  --shenzhouhr.reporting.export-worker-enabled=false \
  >"$LOG_FILE" 2>&1 &
PID=$!
STARTED=0
DEADLINE=$((SECONDS + TIMEOUT_SEC))

while ((SECONDS < DEADLINE)); do
  if grep -q 'Started ShenzhouHrApplication' "$LOG_FILE"; then
    STARTED=1
    break
  fi
  if grep -qE 'APPLICATION FAILED TO START|Validate failed|Migration .* failed|Unable to obtain connection' "$LOG_FILE"; then
    printf '%s\n' 'Flyway migration failed. Safe log tail:' >&2
    tail -80 "$LOG_FILE" >&2
    exit 1
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    wait "$PID" || {
      printf '%s\n' 'Migration process exited with an error. Safe log tail:' >&2
      tail -80 "$LOG_FILE" >&2
      exit 1
    }
    break
  fi
  sleep 1
done

if ((STARTED == 0)); then
  printf '%s\n' 'Migration timed out. Safe log tail:' >&2
  tail -80 "$LOG_FILE" >&2
  exit 1
fi

kill -TERM "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || STATUS=$?
STATUS="${STATUS:-0}"
[[ "$STATUS" == 0 || "$STATUS" == 143 ]] || die "Migration process stopped with exit code $STATUS"
printf '%s\n' 'Database migration completed.'
