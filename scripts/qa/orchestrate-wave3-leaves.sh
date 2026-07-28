#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[orchestrate-wave3-leaves] ERROR: shell tracing must be disabled.\n' >&2
  exit 1
fi

readonly INVOCATION_ROOT="$(pwd -P)"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd -P)"
readonly DRIVER="${SCRIPT_DIR}/orchestrate_wave3_leaves.py"
readonly SELF_TEST="${SCRIPT_DIR}/test_orchestrate_wave3_leaves.py"

fail() {
  printf '[orchestrate-wave3-leaves] ERROR: %s\n' "$*" >&2
  exit 1
}

[[ "$INVOCATION_ROOT" == "$REPOSITORY_ROOT" ]] \
  || fail "Run from the repository root: ${REPOSITORY_ROOT}"
[[ -f "$DRIVER" && ! -L "$DRIVER" ]] || fail "Leaf orchestrator is missing."
[[ -f "$SELF_TEST" && ! -L "$SELF_TEST" ]] || fail "Leaf orchestrator tests are missing."
command -v python3 >/dev/null 2>&1 || fail "python3 is required."

command_name="${1:-plan}"
if [[ "$command_name" == "self-test" ]]; then
  shift || true
  [[ $# -eq 0 ]] || fail "self-test accepts no additional arguments."
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v \
    scripts/qa/test_orchestrate_wave3_leaves.py
  PYTHONDONTWRITEBYTECODE=1 python3 "$DRIVER" self-test
  exit 0
fi

if [[ $# -eq 0 ]]; then
  PYTHONDONTWRITEBYTECODE=1 exec python3 "$DRIVER" plan
fi
PYTHONDONTWRITEBYTECODE=1 exec python3 "$DRIVER" "$@"
