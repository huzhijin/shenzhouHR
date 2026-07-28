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
