#!/usr/bin/env bash
set -Eeuo pipefail

readonly WRAPPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly PROJECT_ROOT="$(cd "${WRAPPER_DIR}/../.." && pwd -P)"
readonly BACKEND_POM="${PROJECT_ROOT}/backend/pom.xml"
readonly MAVEN_WRAPPER="${PROJECT_ROOT}/backend/mvnw"

CONFIG_FILE=""
COMMAND=""
declare -a FLYWAY_PROPERTIES=()

for argument in "$@"; do
  case "$argument" in
    -configFiles=*)
      CONFIG_FILE="${argument#-configFiles=}"
      ;;
    -target=*)
      FLYWAY_PROPERTIES+=("-Dflyway.target=${argument#-target=}")
      ;;
    migrate | validate | info | repair)
      [[ -z "$COMMAND" ]] || {
        printf '[shenzhouhr-flyway] ERROR: exactly one Flyway command is required.\n' >&2
        exit 2
      }
      COMMAND="$argument"
      ;;
    *)
      printf '[shenzhouhr-flyway] ERROR: unsupported Flyway argument.\n' >&2
      exit 2
      ;;
  esac
done

[[ -n "$CONFIG_FILE" && -f "$CONFIG_FILE" && ! -L "$CONFIG_FILE" ]] || {
  printf '[shenzhouhr-flyway] ERROR: a regular Flyway configuration file is required.\n' >&2
  exit 2
}
[[ -n "$COMMAND" ]] || {
  printf '[shenzhouhr-flyway] ERROR: a Flyway command is required.\n' >&2
  exit 2
}
[[ -x "$MAVEN_WRAPPER" && -f "$BACKEND_POM" ]] || {
  printf '[shenzhouhr-flyway] ERROR: backend Maven wrapper is unavailable.\n' >&2
  exit 2
}

if [[ ${#FLYWAY_PROPERTIES[@]} -gt 0 ]]; then
  exec "$MAVEN_WRAPPER" \
    --no-transfer-progress \
    --file "$BACKEND_POM" \
    "org.flywaydb:flyway-maven-plugin:${COMMAND}" \
    "-Dflyway.configFiles=${CONFIG_FILE}" \
    "${FLYWAY_PROPERTIES[@]}"
fi

exec "$MAVEN_WRAPPER" \
  --no-transfer-progress \
  --file "$BACKEND_POM" \
  "org.flywaydb:flyway-maven-plugin:${COMMAND}" \
  "-Dflyway.configFiles=${CONFIG_FILE}"
