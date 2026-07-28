#!/bin/sh
set -eu

if [ "${1:-}" = "--self-test" ]; then
  repo_root=${2:-$(pwd -P)}
  script_path=$(cd "$(dirname "$0")" && pwd -P)/$(basename "$0")
  temporary_directory=$(mktemp -d)
  trap 'rm -rf "$temporary_directory"' EXIT HUP INT TERM
  actual="$temporary_directory/actual.json"
  "$script_path" "$repo_root" "$actual"
  python3 "$repo_root/scripts/qa/verify_w2_public_contract.py" \
    --actual "$actual" \
    --expected "$repo_root/openspec/changes/wave3-attendance-setup-and-policies/specs/wave3-verification/oracles/w2-public-contract-v1.json"
  python3 - "$actual" "$temporary_directory" <<'PY'
import json
import sys
from pathlib import Path
source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
target = Path(sys.argv[2])
mutations = {
    "java": lambda value: value["java"].clear(),
    "mapper": lambda value: value["mapper"]["ids"].__setitem__(
        slice(None), value["mapper"]["ids"][:1]
    ),
    "openapi": lambda value: value["openapi"]["policyPaths"].__setitem__(
        slice(None), value["openapi"]["policyPaths"][:1]
    ),
    "h2": lambda value: value["h2"]["policyTables"].pop(),
}
for name, mutate in mutations.items():
    candidate = json.loads(json.dumps(source))
    mutate(candidate)
    (target / f"mutated-{name}.json").write_text(
        json.dumps(candidate), encoding="utf-8"
    )
PY
  for mutated in "$temporary_directory"/mutated-*.json; do
    if python3 "$repo_root/scripts/qa/verify_w2_public_contract.py" \
        --actual "$mutated" \
        --expected "$repo_root/openspec/changes/wave3-attendance-setup-and-policies/specs/wave3-verification/oracles/w2-public-contract-v1.json" \
        >/dev/null 2>&1; then
      echo "mutated W2 fixture unexpectedly passed: $mutated" >&2
      exit 1
    fi
  done
  echo "W2_PUBLIC_EXTRACTOR_SELF_TEST=PASS productionPath=true mutationsRejected=4"
  exit 0
fi

repo_root=${1:?repository root required}
output=${2:?output file required}
case "$repo_root" in
  /*) ;;
  *) echo "repository root must be absolute" >&2; exit 2 ;;
esac

python3 - "$repo_root" "$output" <<'PY'
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2])
java_names = [
    "PolicyModels.java", "PolicyRows.java", "PolicyRepository.java",
    "PolicyMapper.java", "MyBatisPolicyRepository.java",
    "PolicyDraftService.java", "PolicyApplicationService.java",
    "PolicyLifecycleService.java", "PolicyRecordSupport.java",
    "PolicyDtos.java", "PolicyController.java",
]
java_root = root / "backend/src/main/java"
java_files = {}
for name in java_names:
    matches = sorted(java_root.rglob(name))
    if len(matches) != 1:
        raise SystemExit(f"expected exactly one W2 file named {name}")
    path = matches[0]
    if "/attendance/" in path.as_posix():
        raise SystemExit("attendance source entered W2 oracle")
    text = path.read_text(encoding="utf-8")
    java_files[name] = {
        "records": sorted(
            [
                {"name": record, "components": " ".join(components.split())}
                for record, components in re.findall(
                r"public\s+record\s+(\w+)\s*\((.*?)\)\s*\{", text, re.S
                )
            ],
            key=lambda value: (value["name"], value["components"]),
        ),
        "publicMethods": sorted(
            " ".join(match.split())
            for match in re.findall(
                r"public\s+(?!record\b|class\b|interface\b|enum\b)[^;{]+\([^;{]*\)\s*(?:;|\{)",
                text,
                re.S,
            )
        ),
    }

mapper_path = root / "backend/src/main/resources/mappers/PolicyMapper.xml"
mapper = mapper_path.read_text(encoding="utf-8")
mapper_export = {
    "ids": sorted(re.findall(
        r'<(?:select|insert|update|delete|resultMap)\b[^>]*\bid="([^"]+)"', mapper
    )),
    "columns": sorted(set(re.findall(r'\b[a-z][a-z0-9_]+_id\b', mapper))),
}

schema = (root / "backend/src/test/resources/db/test-schema.sql").read_text(encoding="utf-8")
openapi = (root / "api/openapi.yaml").read_text(encoding="utf-8")
export = {
    "format": "w2-public-structured-export-v1",
    "java": java_files,
    "mapper": mapper_export,
    "h2": {
        "policyTables": sorted(set(re.findall(
            r"CREATE TABLE\s+(policy_[a-z_]+)", schema, re.I
        ))),
        "effectiveCheck": "effective_to IS NULL OR effective_to >= effective_from",
        "auditEventHasRequestId": bool(re.search(
            r"CREATE TABLE\s+audit_event\b.*?\brequest_id\b", schema, re.I | re.S
        )),
    },
    "openapi": {
        "policyPaths": sorted(set(re.findall(
            r"^\s{2}(/policy[^:]*):", openapi, re.M
        ))),
        "policySchemas": sorted(set(re.findall(
            r"^\s{4}(Policy[A-Za-z0-9]+):", openapi, re.M
        ))),
    },
}
output.write_text(
    json.dumps(export, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
