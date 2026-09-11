#!/bin/sh
set -eu

script_directory=$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)
repository_tools=$(CDPATH= cd -- "${script_directory}/../../../../../.." && pwd -P)
driver="${repository_tools}/scripts/qa/verify_w2_public_contract_v2.py"
expected="${repository_tools}/openspec/changes/wave3-attendance-setup-and-policies/specs/wave3-verification/oracles/w2-public-contract-v2.json"

if [ "${1:-}" = "--self-test" ]; then
  repo_root=${2:-$(pwd -P)}
  python3 "${driver}" \
    --self-test \
    --repo "${repo_root}" \
    --expected "${expected}" \
    --extractor "$0"
  exit 0
fi

repo_root=${1:?repository root required}
output=${2:?output file required}
case "${repo_root}" in
  /*) ;;
  *) echo "repository root must be absolute" >&2; exit 2 ;;
esac

python3 "${driver}" --extract --repo "${repo_root}" --output "${output}"
