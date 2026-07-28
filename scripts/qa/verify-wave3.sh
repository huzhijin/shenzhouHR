#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$-" == *x* ]]; then
  printf '[verify-wave3] ERROR: shell tracing must be disabled.\n' >&2
  exit 1
fi

readonly INVOCATION_ROOT="$(pwd -P)"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd -P)"
readonly DRIVER="${SCRIPT_DIR}/verify_wave3_evidence.py"
readonly SELF_TEST="${SCRIPT_DIR}/test_verify_wave3_evidence.py"

fail() {
  printf '[verify-wave3] ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage:
  scripts/qa/verify-wave3.sh self-test
  scripts/qa/verify-wave3.sh audit-history
  scripts/qa/verify-wave3.sh init \
    --run-id FRESH_RUN_ID \
    --db-identity REAL_DB_IDENTITY \
    --implementer-id IMPLEMENTER_ID \
    --implementer-process-id IMPLEMENTER_PROCESS_ID
  scripts/qa/verify-wave3.sh context \
    --run-id RUN_ID --evidence-id W3-VER-...
  scripts/qa/verify-wave3.sh capture \
    --run-id RUN_ID --evidence-id W3-VER-... -- COMMAND [ARG...]
  scripts/qa/verify-wave3.sh register \
    --run-id RUN_ID --evidence-id W3-VER-... \
    --primary ROLE=RUN_RELATIVE_ARTIFACT \
    [--artifact ROLE=RUN_RELATIVE_ARTIFACT ...]
  scripts/qa/verify-wave3.sh assemble --run-id RUN_ID
  scripts/qa/verify-wave3.sh accept-review --run-id RUN_ID
  scripts/qa/verify-wave3.sh manifest --run-id RUN_ID
  scripts/qa/verify-wave3.sh integrity --run-id RUN_ID
  scripts/qa/verify-wave3.sh audit --run-id RUN_ID
  scripts/qa/verify-wave3.sh final --run-id RUN_ID --apply-completion

Evidence order:
  1. init freezes the bounded normalized source tree and database identity.
  2. capture/register records exactly the fixed 20 pre-review leaf IDs.
     register requires the exact per-leaf artifact-role set fixed in the
     contract; missing, extra or duplicate roles fail closed. capture is only
     available for a leaf whose contract has one artifact role.
     A text primary artifact must contain the exact line printed by `context`
     and every marker fixed in wave3-evidence-contract-v1.json. A structured
     JSON primary must carry exact evidenceId/runId/sourceTreeHash/
     databaseIdentity/verdict=PASS fields plus every marker.
  3. assemble creates source END, artifact-registry.json, the mutually
     non-hashing acceptance-matrix.json and WAVE3-VERIFICATION.md.
  4. A distinct read-only reviewer writes review/independent-review.md and
     review/independent-review-input.json. Every fixed leaf and every registered
     raw artifact needs its own structured PASS challenge record and substantive
     challengeNotes. The same reviewer process invokes accept-review, which
     seals review/independent-review.json with its real PID/process identity and
     verified source hashes from immediately before/after the read-only seal.
  5. manifest hashes exactly registry/matrix/report/review/detached metadata.
  6. integrity validates but never rewrites the manifest.
  7. final derives only from the exact 20 + review + integrity child IDs. It
     prepares and validates proof/final outputs before atomically applying the
     detached verification checkboxes. A hard interruption after both outputs
     are durable is recovered by rerunning final; partial/existing outputs never
     authorize a checkbox write.

The removed historical `static`, `mysql`, `evidence-manifest.json`,
16-criterion and partial/NOT_VERIFIED workflows are intentionally unsupported.
Gate-specific Maven/MySQL/browser commands remain responsible for semantic
PASS and must emit their own fixed marker; this wrapper only captures and
mechanically binds their raw output.
USAGE
}

[[ "$INVOCATION_ROOT" == "$REPOSITORY_ROOT" ]] \
  || fail "Run from the repository root: ${REPOSITORY_ROOT}"
[[ -f "$DRIVER" && ! -L "$DRIVER" ]] || fail "Evidence driver is missing."
[[ -f "$SELF_TEST" && ! -L "$SELF_TEST" ]] || fail "Evidence self-test is missing."
command -v python3 >/dev/null 2>&1 || fail "python3 is required."

command_name="${1:-}"
case "$command_name" in
  "")
    usage
    exit 2
    ;;
  --help | -h | help)
    usage
    ;;
  self-test)
    shift
    [[ $# -eq 0 ]] || fail "self-test accepts no additional arguments."
    PYTHONDONTWRITEBYTECODE=1 python3 -m py_compile "$DRIVER" "$SELF_TEST"
    PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v \
      scripts/qa/test_verify_wave3_evidence.py
    PYTHONDONTWRITEBYTECODE=1 python3 "$DRIVER" audit-history
    test_count="$(
      PYTHONDONTWRITEBYTECODE=1 python3 -c \
        'import unittest; from scripts.qa.test_verify_wave3_evidence import Wave3EvidenceSelfTest; print(unittest.defaultTestLoader.loadTestsFromTestCase(Wave3EvidenceSelfTest).countTestCases())'
    )"
    [[ "$test_count" == "22" ]] \
      || fail "Evidence self-test count drifted: expected 22, got ${test_count}"
    printf 'W3_EVIDENCE_HARNESS_SELF_TEST=PASS tests=%s\n' "$test_count"
    ;;
  static | mysql | evidence | all)
    fail "Legacy '${command_name}' orchestration is invalid; run --help for the strict 20→review→manifest→integrity→22 workflow."
    ;;
  *)
    PYTHONDONTWRITEBYTECODE=1 exec python3 "$DRIVER" "$@"
    ;;
esac
