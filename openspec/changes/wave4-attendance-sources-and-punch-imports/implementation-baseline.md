# W4 implementation baseline

- Change: `wave4-attendance-sources-and-punch-imports`
- Branch: `codex/w4-attendance-sources-imports-20260728`
- Baseline commit: `0c09fc375b1d2973915234e50a9aac10900446ca`
- Baseline branch: `codex/sync-w3-w9-20260728`
- Migration versions observed before the first W4 write: `V1` through `V7`
- W4 migration decision: `V8__attendance_source_and_evidence.sql` and
  `V9__attendance_punch_import.sql`

## Retained migration SHA-256

| Migration | SHA-256 |
| --- | --- |
| V1 | `5f5cdd3367ef7ab128974b7fcad89a44bcfc5631008bd066806f77d33f85c440` |
| V2 | `28934279faafa2154faccd470976aa6ab2c4a5058f7d1b08a989c271da5241b4` |
| V3 | `75120a31c594f2a974c031ed400d028028f83fb915df1b457902df27a971f9f9` |
| V4 | `22b7f4b4b5ad21beec719c09aade3fc50b41a64d958951a690328ea38d2402a8` |
| V5 | `6fd13a0e31a37d27fb7d9f71f8117acf7d5fb0bd38d4d117303b2b8582b6b589` |
| V6 | `11762c79e34bea2ab6aa790a6b32c6466658eec03fd7a97b23fe540cf985c003` |
| V7 | `3d37209aa462b49b71d4a51baf504f7eefab180b59a97d18cf64501e15ff57f2` |

## W3 prerequisite state

The synchronized source baseline is present, but a current W3 FINAL manifest and
post-manifest integrity PASS were not supplied in this worktree. This document
does not infer or manufacture that result. OpenSpec task 1.1 and every W4 final
evidence gate remain blocked until the separately accepted W3 FINAL artifacts
are synchronized and verified against the hashes above.

## External integration state

- `DELI_LIVE=NOT_VERIFIED`
- `OA_LIVE=NOT_VERIFIED`
- `PRODUCTION_FILE_STORAGE=NOT_VERIFIED`

Only deterministic synthetic contract adapters are authorized in this worktree.
No production endpoint, credential, employee record, attendance record or file
is used.

## Independent verification completed

The following commands were run from this worktree on 2026-07-28:

- `cd backend && ./mvnw -Dtest='Wave4*Test,MyBatisMapperContractTest' test`
  — 32 tests passed.
- `cd backend && ./mvnw test` — 208 tests passed, including the retained W1,
  W2 and in-branch W3 suites. This is regression evidence only and does not
  substitute for a separately accepted W3 FINAL manifest.
- `cd frontend && npm run typecheck` — passed.
- `cd frontend && npm run lint` — passed.
- `cd frontend && npm test -- --maxWorkers=1 --no-file-parallelism --reporter=dot`
  — 27 test files and 236 tests passed.
- `cd frontend && npm run build:all` — production and demo builds passed.
  The production artifact contains none of the W4 synthetic fixture markers;
  the demo artifact contains them, and both artifact inventories are newer
  than their source files.

## Verification deliberately not claimed

- Exact MySQL 8.4.10 migration, constraint, denied-DDL, concurrency and query
  plan gates were not run. The repository helper available in this checkout
  targets a different original working tree, which is outside this delegated
  worktree's authorized write boundary.
- V8/V9 have not yet been mirrored into the retained H2 test schema.
- Normal-mode browser acceptance, live 得力/OA connectivity and production
  file storage were not run.
- Mutation workflows for upload/precheck/publication/partial publication,
  void/reversal, durable retry, access audit and concurrent ledger writes are
  intentionally still incomplete and remain unchecked in `tasks.md`.
