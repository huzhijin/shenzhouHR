#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[produce-wave3-build-gates] ERROR: shell tracing must be disabled.\n' >&2
  exit 1
fi

readonly EXPECTED_REPOSITORY_ROOT="/Users/huzhijin/Downloads/shenzhouHR"
readonly SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"

if [[ "$(pwd -P)" != "$EXPECTED_REPOSITORY_ROOT" \
  || "$SCRIPT_ROOT" != "$EXPECTED_REPOSITORY_ROOT" ]]; then
  printf 'PROJECT_ROOT_SCOPE_ERROR\n' >&2
  exit 1
fi

exec python3 scripts/qa/produce_wave3_build_gates.py "$@"
