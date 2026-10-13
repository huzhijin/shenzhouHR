#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf 'W3_SEMANTIC_GATE_PRODUCER=FAIL shell tracing must be disabled\n' >&2
  exit 1
fi

readonly SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
readonly EXPECTED_REPOSITORY_ROOT="$SCRIPT_ROOT"
if [[ "$(pwd -P)" != "$EXPECTED_REPOSITORY_ROOT" ]]; then
  printf 'W3_SEMANTIC_GATE_PRODUCER=FAIL run from exact repository root\n' >&2
  exit 1
fi

umask 077
export LC_ALL=C
export LANG=C

exec python3 \
  "$EXPECTED_REPOSITORY_ROOT/scripts/qa/produce_wave3_semantic_gates.py" \
  "$@"
