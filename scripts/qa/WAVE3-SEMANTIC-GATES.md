# W3 semantic evidence gates

`produce-wave3-semantic-gates.sh` produces the first seven pre-review leaves
for one fresh, frozen W3 evidence run:

- `W3-VER-W2-RETAINED`
- `W3-VER-W2-PUBLIC-ORACLE`
- `W3-VER-CANONICALIZER`
- `W3-VER-SEED-ORACLE`
- `W3-VER-MIGRATION-PATHS`
- `W3-VER-MYSQL8410`
- `W3-VER-OPENAPI-CLOSURE`

It stages every required role, validates all seven primary artifacts with the
shared evidence contract, then publishes each complete raw directory with an
exclusive atomic rename. It does not register leaves, seal source END,
assemble acceptance, perform independent review, or finalize a run.
Historical PASS conclusions are never copied.

The semantic expectations are fixed before execution. In particular:

- the review-owned API oracle
  `w3-api-semantic-oracle-v1.json` is SHA256-pinned and closes all 54
  operations, exact parameters, headers, statuses, request/response bodies,
  53 referenced schemas and 52 Java records independently of the current
  Controller/OpenAPI/Java exports;
- the W2 registry is exactly six tables, six single-column primary keys and
  76 ordered columns; `w2-retained-fixture-rows-v1.json` fixes all seven V6
  source rows and their recomputed canonical final hash;
- every captured JSON object and row has an exact key set. Row counts,
  row hashes, Flyway snapshot digests, seed digests and canonical final hashes
  are recomputed; a claimed `verdict` is never sufficient;
- every accepted transcript marker must occupy one complete line with its
  exact field syntax and cardinality. Prefix/suffix text and duplicates fail.

## Static validation

Run from the fixed repository root:

```bash
python3 -m py_compile \
  scripts/qa/capture_wave3_mysql_semantics.py \
  scripts/qa/produce_wave3_semantic_gates.py \
  scripts/qa/test_produce_wave3_semantic_gates.py
bash -n \
  deploy/mysql/wave3-local-mysql.sh \
  scripts/qa/produce-wave3-semantic-gates.sh
ruff check \
  scripts/qa/capture_wave3_mysql_semantics.py \
  scripts/qa/produce_wave3_semantic_gates.py \
  scripts/qa/test_produce_wave3_semantic_gates.py
python3 scripts/qa/test_produce_wave3_semantic_gates.py
bash scripts/qa/produce-wave3-semantic-gates.sh self-test
bash scripts/qa/produce-wave3-semantic-gates.sh plan
```

`plan` and `self-test` do not connect to MySQL. The self-test closes the
54-operation Controller/OpenAPI set and executes fail-closed route, parameter,
header, request/response schema, nullability and unknown-field mutants.
The Python suite runs 20 test cases, including linked API mutations, forged
structured PASS documents, all-seven-rows-in-one-table, extra/dangling staged
entries, publication replacement races, ancestor symlink swaps and a second
`V7__*.sql` migration.

For a non-mutating preview, run:

```bash
bash scripts/qa/produce-wave3-semantic-gates.sh plan
bash deploy/mysql/wave3-local-mysql.sh plan
```

These commands show the planned seven leaves and database boundary without
starting a server, connecting to MySQL or rebuilding tables.

## Required current-run inputs

Finish all source, migration, OpenSpec and QA edits before initializing the
run. The producer requires:

1. an active `run-context.json` bound to
   `mysql8410:<lower-server-uuid>:shenzhou_hr_test`;
2. the exact-20 `orchestration/leaf-plan.json`;
3. current-run raw `W3-VER-W1-REGRESSION` and
   `W3-VER-W2-REGRESSION` outputs from the build producer;
4. an absolute, repository-external, owner-only mode-`0600` runtime
   environment for the same MySQL identity, including the Flyway, test-app
   and dev-app credentials required by the `all` contract probes;
5. an exact test-table rebuild confirmation containing the run ID.

A normal sequence is:

```bash
scripts/qa/verify-wave3.sh init \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --implementer-id IMPLEMENTER_ID \
  --implementer-process-id IMPLEMENTER_PROCESS_ID

scripts/qa/orchestrate-wave3-leaves.sh prepare \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --execute

scripts/qa/produce-wave3-build-gates.sh execute \
  --run-context \
  "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/run-context.json"
```

Any source change after `init` invalidates the run. Start another fresh run
instead of editing the frozen source or reusing old raw evidence.

## Current-run execution

The execution command is:

```bash
scripts/qa/produce-wave3-semantic-gates.sh execute \
  --run-context \
  "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/run-context.json" \
  --runtime-env /absolute/private/wave3-runtime.env \
  --confirm-test-table-rebuild shenzhou_hr_test:FRESH_RUN_ID
```

The producer invokes `mysql8410-isolated.sh verify`, which verifies the
already-running isolated server and does not start or stop it. It then invokes
the reviewed `wave3-local-mysql.sh all` path. That path:

- connects only to the isolated MySQL 8.4.10 endpoint
  `127.0.0.1:13306`, never port 3306;
- rebuilds only the explicitly confirmed `shenzhou_hr_test` table set;
- exercises V6 → target7 → latest → repeat and empty → latest;
- migrates `shenzhou_hr_dev` forward without dropping its tables;
- captures W2 before/after full-row snapshots, exact target7 seed rows,
  Flyway phase history, schema/constraint/lock/DML/grant results, and the
  byte-identical before/after identity of the existing MySQL 8.0.34 instance;
- never starts or stops either MySQL instance.

Static preflight enumerates `V7__*.sql` and requires the result to be exactly
`V7__attendance_setup_and_base_policies.sql`; no match, a symlink or any
second V7 migration is fatal.

Capture and publication are rooted through progressive `openat` directory
walks with `O_NOFOLLOW`. Fixed ancestor, directory and entry device/inode
identities are revalidated around each write and rename. Staged raw
directories must contain only the exact regular role files—directories,
symlinks, dangling symlinks and extras fail before any leaf is published.
Rollback removes only the device/inode object that this producer published;
if another object replaced it, that replacement is preserved and the
rollback reports an explicit failure.

Do not enable shell tracing. The runtime file is parsed as data, must remain
mode `0600`, and is never copied into evidence.

## Preflight and registration boundary

Successful execution publishes raw roles only. Complete all remaining
pre-review producers, then run one exact-20 read-only preflight:

```bash
scripts/qa/orchestrate-wave3-leaves.sh check \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --runtime-env /absolute/private/wave3-runtime.env \
  --login-env /absolute/private/FRESH_RUN_ID-login.env \
  --login-binding /absolute/private/FRESH_RUN_ID-login.env.binding.json
```

Register only after that complete preflight passes. A handled producer failure
rolls back its published semantic leaf set; an existing destination is never
overwritten.
