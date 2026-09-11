# Wave 7 independent-phase verification

Date: 2026-07-28

Change: `wave7-self-service-dashboard-and-reports`

Branch: `codex/w7-self-service-dashboard-reports-20260728`

Baseline: `codex/sync-w3-w9-20260728` at `0c09fc375b1d2973915234e50a9aac10900446ca`

## Verified in this worktree

| Gate | Command / evidence | Result |
| --- | --- | --- |
| Lint | `cd frontend && npm run lint` | PASS |
| Typecheck | `cd frontend && npm run typecheck` | PASS |
| Full frontend unit suite | `cd frontend && npm test -- --maxWorkers=1 --testTimeout=60000` | PASS — 29 files, 265 tests |
| Wave 7 contracts, states, responsive semantics, and source isolation | `cd frontend && npm test -- --run src/features/wave7/wave7Contracts.test.ts src/features/wave7/wave7Pages.test.tsx src/test/wave7SourceContract.test.ts --maxWorkers=3` | PASS — 3 files, 21 tests |
| Wave 7 route and authorization behavior | `cd frontend && npm test -- --run src/app/App.test.tsx src/app/routeAuthorization.test.ts -t "WAVE-7\|WAVE-7 route\|prototype-only" --maxWorkers=2` | PASS — 2 files, 13 selected tests |
| Production build | `cd frontend && npm run build` | PASS |
| Production artifact zero-discovery scan | Case-insensitive scan of `frontend/dist/prod` for compensation discovery terms | PASS — `W7_BUILD_ZERO_DISCOVERY=PASS` |
| OpenSpec strict validation | `openspec validate wave7-self-service-dashboard-and-reports --type change --strict --no-interactive --json` | PASS |

The stable full-suite run uses one Vitest worker and a 60-second per-test ceiling because
the baseline attendance-setup tests can exceed their default 15-second ceiling or race
their async pagination setup when several jsdom workers compete. The same final run
covered every frontend test and passed all 265 tests.

The Wave 7 production route containers are intentionally fail closed with
`WAVE7_UPSTREAM_PENDING`. They perform no network request and cannot resolve test
fixtures. Synthetic projections are imported only by test modules.

The older Wave 3 proof-producer unit module was also compatibility-audited with Python
3.13. It passed 45 of 47 tests. Its two remaining checks are not valid Wave 7 product
gates: one freezes the pre-Wave-7 frontend at exactly 79 files, and the other requires
the absent Wave 3 real-proof directory `docs/verification/wave3/runs`. No frozen Wave 3
manifest or proof path was changed to manufacture a pass. Wave 7 instead verifies both
its production source isolation contract and the actual production build output.

## Upstream-dependent evidence

The following evidence is deliberately marked `NOT_VERIFIED` and is not implied by the
fixture-driven UI:

- `NOT_VERIFIED`: Wave 5 and Wave 6 FINAL commit synchronization, accepted OpenAPI
  schemas, capability identifiers, and adapter reconciliation.
- `NOT_VERIFIED`: real same-origin employee, dashboard, report, feedback, export
  creation/status/download, and server-reauthorization requests.
- `NOT_VERIFIED`: real API/MySQL tenant and scope authorization, same-version
  drill-down, immutable historical reads, and concurrent scope-change behavior.
- `NOT_VERIFIED`: the 50,000-row synchronous/asynchronous threshold, export worker,
  object delivery, create/download reauthorization, audit records, and expiry.
- `NOT_VERIFIED`: authenticated browser verification at 360/390/430/768/1024/1366/
  1440/1920 widths, screenshots, assistive-technology review, and performance budgets.
- `NOT_VERIFIED`: post-W5/W6 integrated production/deployment zero-discovery proof.

These items correspond to OpenSpec tasks 5.1–5.3 and remain unchecked until the
finalized upstream contracts and services are available.
