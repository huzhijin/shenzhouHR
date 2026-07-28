# W3 exact-20 leaf orchestration

`orchestrate-wave3-leaves.sh` prepares and registers the exact 20 pre-review
leaves fixed by `wave3-evidence-contract-v1.json`. It never runs `init`,
`assemble`, independent review, manifest, integrity or finalization. It also
never writes a semantic PASS marker: Maven, frontend, MySQL and browser gates
remain responsible for producing real current-run raw evidence.

With no arguments the helper is read-only:

```bash
scripts/qa/orchestrate-wave3-leaves.sh
scripts/qa/orchestrate-wave3-leaves.sh plan --json
scripts/qa/orchestrate-wave3-leaves.sh self-test
```

The plan names all 20 IDs in contract order, every exact artifact role and the
existing producer that can supply raw inputs. A producer classified
`DEDICATED_*_REQUIRED` still needs its semantic gate; the orchestrator will not
turn a command exit code or a historical log into PASS.

## Fresh run boundary

Add or change QA source before initializing the evidence run. Resolve the real
database identity separately, then initialize through the existing verifier.
The orchestrator does not freeze source:

```bash
scripts/qa/verify-wave3.sh init \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --implementer-id IMPLEMENTER_ID \
  --implementer-process-id IMPLEMENTER_PROCESS_ID
```

After initialization, explicitly create the run-bound role plan:

```bash
scripts/qa/orchestrate-wave3-leaves.sh prepare \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --execute
```

This writes only
`docs/verification/wave3/runs/FRESH_RUN_ID/orchestration/leaf-plan.json`.
Artifact paths may be adjusted inside that run-bound plan, but each one must
remain below `leaves/<exact-slug>/raw/`; role order and closure cannot change.

Run every real producer against the same frozen source and database identity.
The existing runtime harnesses already write compatible paths for
`W3-VER-W2-CURRENT-SMOKE` and `W3-VER-NORMAL-BROWSER`. Existing MySQL build
evidence can establish that the isolated installation is reusable, but a fresh
strict run must still recapture current-run identity, migration and contract
outputs. Evidence under earlier `w3-mysql8410-*` directories is not eligible:
it has a different runId, predates later source edits and is outside the active
run root.

The three formal runtime leaves are registerable only after their shared
before/after provenance seal passes. W2/demo embed the sanitized provenance in
their registered primary logs; normal-browser embeds it in the registered
`normal-runtime-source-hash` role. A listener selected only by port, an old
class tree, a different Node/Vite install, reused optimizer cache, stale build
receipt, raw-source mismatch, preview argv drift, or served demo byte mismatch
therefore fails before leaf publication.

The nine backend/frontend build leaves are produced together and published
only after all commands pass:

```bash
scripts/qa/produce-wave3-build-gates.sh execute \
  --run-context \
  "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/run-context.json"
```

Both orchestration preflight and strict verifier registration require the
last-written `wave3-build-gates.commit.json`. It binds the current
run/source/database, exact ordered nine IDs, canonical manifest SHA, and every
raw artifact's SHA/size/device/inode/mode/mtime/ctime. The verifier repeats
this check for direct manual `register` and every later leaf validation, so
neither bypass nor post-registration mutation can admit a partial batch.
The strict verifier reaches the evidence root only through lexical
repository-relative `docs/verification/wave3/runs` components validated with
`lstat` plus `openat(O_NOFOLLOW)` identity checks. Any intermediate, external,
or broken symlink is rejected consistently by initialization, registration,
leaf validation, assembly, and audit.

Do not run another Maven process while that producer owns `backend/target`.
The demo-isolation and payroll-zero producers remain separate because they
also require live transport/database checks; use their companion W3 harness
documents for exact commands and private-input boundaries.

## Private runtime and login environments

The runtime environment produced by
`deploy/mysql/mysql8410-provision-wave3.sh` contains database keys only. The
normal-browser and current-W2 runtime gates additionally require a login
environment with exactly:

```text
SHENZHOUHR_LOGIN_USERNAME=...
SHENZHOUHR_LOGIN_PASSWORD=...
```

Both files must be absolute, outside the repository, regular non-symlink
files owned by the current user and mode `0600`. Secrets are never printed.

To copy an already-provisioned synthetic SYSTEM_ADMIN bootstrap credential
into a run-bound file without exposing its values:

```bash
scripts/qa/orchestrate-wave3-leaves.sh prepare-login-env \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --source-login-env /absolute/private/existing-login.env \
  --output /absolute/private/FRESH_RUN_ID-login.env \
  --execute
```

To generate a new synthetic credential file:

```bash
scripts/qa/orchestrate-wave3-leaves.sh prepare-login-env \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --generate \
  --output /absolute/private/FRESH_RUN_ID-login.env \
  --execute
```

Generation does not create a backend account. Its non-secret sidecar records
`REQUIRES_EXPLICIT_PROVISIONING`; the account must be provisioned through a
separately authorized real backend flow before runtime capture. Copy mode
records `EXISTING_BOOTSTRAP_TO_BE_PROVEN_BY_RUNTIME`. The command prints only
the environment/binding paths and SHA-256 hashes, never either credential.

## Check and register

`check` is read-only. It recomputes the normalized source hash before and after
inspection, requires the requested database identity to equal the run context,
rejects files outside the active run, symlinks, empty files, artifacts older
than run START, future mtimes, `NOT_VERIFIED`, mismatched structured context,
missing exact markers and missing text context lines:

```bash
scripts/qa/orchestrate-wave3-leaves.sh check \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --runtime-env /absolute/private/wave3-runtime.env \
  --login-env /absolute/private/FRESH_RUN_ID-login.env \
  --login-binding /absolute/private/FRESH_RUN_ID-login.env.binding.json
```

Only after all 20 raw role sets pass one complete preflight, register them in
the fixed contract order:

```bash
scripts/qa/orchestrate-wave3-leaves.sh register \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --runtime-env /absolute/private/wave3-runtime.env \
  --login-env /absolute/private/FRESH_RUN_ID-login.env \
  --login-binding /absolute/private/FRESH_RUN_ID-login.env.binding.json \
  --execute
```

If registration was interrupted after some exact leaves were written,
`--resume` first validates those registered leaf descriptors against the same
plan before continuing. The helper always stops after the 20th registration.
Source END and independent review remain separate, explicit operator/reviewer
actions under `verify-wave3.sh`.

## Independent read-only review seal

After `assemble`, a reviewer distinct from the implementation process directly
inspects the registered raw artifacts and writes:

- `review/independent-review.md`, containing the exact marker and every fixed
  pre-review evidence ID exactly once;
- `review/independent-review-input.json`, containing the frozen run/source/DB
  binding, reviewer identity, exact 20-ID order, and one structured challenge
  record for every leaf and every raw artifact listed in
  `artifact-registry.json`.

Each leaf and raw-artifact challenge record has `verdict: "PASS"` and a
non-empty `challengeNotes` array describing the direct inspection. Artifact
roles and SHA-256 values must exactly match the registry. Summary-only
declarations, a missing artifact challenge, a hard-gate waiver, or an
unresolved finding fail closed.

The same read-only reviewer process then seals the review:

```bash
scripts/qa/verify-wave3.sh accept-review --run-id FRESH_RUN_ID
```

The reviewer does not pre-create `review/independent-review.json`.
`accept-review` creates it once, records the actual current PID, parent PID,
resolved Python executable and verifier path, and recomputes the normalized
source tree before and after sealing. Both hashes must equal the frozen source
hash. Later audit/manifest steps validate the input/report hashes and the full
per-artifact challenge closure.

## Recoverable FINAL completion

`final --apply-completion` prepares proof and FINAL JSON completely and
validates both durable files before touching `tasks.md`. It then replaces the
task file atomically and verifies every detached task checkbox plus the
normalized source hash. A normal write/validation failure removes only outputs
created by that invocation and restores the original task bytes.

If a hard interruption leaves both valid FINAL outputs durable before checkbox
commit, rerun the same command to validate those outputs and finish the
detached checkbox write. If only one output exists, FINAL fails without
changing tasks; investigate/remove the partial file before retrying. An
arbitrary pre-existing proof never authorizes task changes.
