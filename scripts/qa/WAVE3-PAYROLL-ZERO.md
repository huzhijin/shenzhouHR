# W3 PAYROLL zero-discoverability producer

`verify_wave3_payroll_zero.py` produces only the six raw artifact roles required
by `W3-VER-PAYROLL-ZERO`. It does not initialize, register, assemble or
finalize a W3 evidence run.

Run it only after source is frozen, a fresh run has been initialized, both
`frontend/dist/prod` and `frontend/dist/demo` have been rebuilt during that
run by the registered build-gates producer, and the normal frontend/backend
are serving the bound MySQL 8.4.10 test database. The producer requires the
current-run prod/demo build receipts, exact inventories and build-gates commit
record; copied or merely retimestamped dist files fail the mtime/ctime seal.
The commit record must close exactly nine build gates and thirteen immutable
raw artifacts (`0400` files below `0500` raw directories). Each schema-v2
inventory binds one resolved npm executable, the current Node/npm identities,
the three run-private cloned install-state files, the run-wide workspace-seal
receipt and text timestamps derived from the same epoch-nanosecond fields.
The clone path, content and command-start window are rebound to the current
frozen frontend origin. Its canonical manifest and bounded zlib/base64 content
archive are decoded and compared, file-for-file and byte-for-byte, with the
current dist tree. Production and demo must share the same bundle commit and
workspace seal.
The backend-full schema-v3 summary additionally contains the complete logical
`target/classes` byte receipt. The live backend class snapshot must equal that
committed receipt; run-relative ctime alone is not accepted. Current
Java/Node/npm authority is resolved independently before receipt paths are
accepted, and current command-support recomputation uses the same stable
repository seal as the build producer.

Initialize the evidence run first, then start both runtime processes from this
repository. The backend must be a direct Java process with cwd `backend`, the
current `target/classes` as the first classpath entry, only fixed Maven JARs
after it, and exact main class
`com.szsemicon.hr.ShenzhouHrApplication`. Its process environment must bind
`SHENZHOUHR_DB_URL`, `SHENZHOUHR_DB_USERNAME` and
`SHENZHOUHR_DB_PASSWORD` to the private runtime environment and the isolated
test database; no Spring/Java override channel is accepted.

Before starting the normal Vite listener, run this from the repository root.
`frontend/node_modules/.cache/vite` must be wholly absent, including no
dangling symbolic link; a cache from an earlier listener is not reusable:

```bash
test ! -e "$PWD/frontend/node_modules/.cache/vite" &&
  test ! -L "$PWD/frontend/node_modules/.cache/vite"
```

Then start the listener with this exact argv contract:

```bash
cd frontend
VITE_API_PROXY_TARGET=http://127.0.0.1:8080 \
  npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

The `dev` script itself contributes the fixed
`--configLoader runner` pair before these host/port arguments; the listener
process contract requires that exact pair and rejects the default bundled
config loader.

The resulting listener must be the repository's exact Node executable and
`node_modules/.bin/vite` entry, with no demo mode, extra Vite environment keys,
Node options or loaded `.env.development*` file. Both processes must be the
unique listeners on exactly
`http://127.0.0.1:8080` and `http://127.0.0.1:5173`. The producer rejects
listeners that predate the run/frozen source, stale backend classes, a Vite
source byte mismatch, an origin escape, or a frontend `/api` proxy response
that is not byte-equal to the bound backend under the same correlation ID.
Run the backend-full gate's clean build before starting the backend. Every
runtime `.class` must have been created in this run and before the listener
started; the producer freezes the complete `target/classes` membership,
realpaths, inode/mode/size, SHA-256, mtime and ctime, then rechecks the whole
snapshot immediately before atomic publication. Because macOS `ps lstart`
reports only whole seconds, the producer uses that second's start as the
strict latest permitted class mtime/ctime; start the backend in a later second
than the completed clean build.

The final seal runs after all MySQL work: one repeatable-read consistent
snapshot rebinds database/Flyway/markers and all three API principals; all 79
fixed frontend product files are fetched from Vite once in each direction;
source, dist receipts, listener executable/argv/classpath, complete class tree,
and all six staged artifacts are then rechecked. Artifact bytes receive an
exact schema, deny-term and private-secret scan before and after publication.
Publication is macOS-only and uses `renameatx_np(RENAME_EXCL)` with staged
file/directory and parent-directory `fsync`; an existing empty directory or
symbolic link is never replaced.

Cleanup and rollback are deliberately recoverable and never recursively
delete files or directories. If staging fails, committed validation fails, or
the final parent-directory `fsync` fails, the exact owned directory is moved
atomically to a sibling `.zero-recovery-<nonce>` directory, rebound to its
device/inode, restricted to mode `0700`, and durably retained. A recovery
directory is failed-run material only: it must never be registered or treated
as PASS evidence. Inspect and recover it manually, then clean it deliberately,
or use a fresh evidence run. The producer rejects a retry in a run that still
contains recovery material.

```bash
python3 scripts/qa/verify_wave3_payroll_zero.py execute \
  --run-context "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/run-context.json" \
  --runtime-env /absolute/private/wave3-runtime.env \
  --login-env /absolute/private/FRESH_RUN_ID-login.env \
  --backend-url http://127.0.0.1:8080 \
  --frontend-url http://127.0.0.1:5173 \
  --output-dir "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/leaves/payroll-zero/raw"
```

The default is read-only for accounts and requires the three deterministic
run-bound role accounts to exist already. To explicitly permit creation or
repair of only those local synthetic `SYSTEM_ADMIN`, `HR_ADMIN`, and `AUDITOR`
accounts, add:

```text
--provision-synthetic-accounts
```

The runtime and login environment files must be absolute, outside the
repository, owned by the current user, regular non-symlink files with mode
`0600`. Values are never printed. Browser cookies, CSRF tokens and passwords
are never stored in the artifacts. Unapproved response-header values, URL
paths/queries and full 404/UI bodies are scanned in memory but persisted only
as safe presence or closed-schema evidence.

Formal production assumes a trusted operator and an exclusive current-user
session. A malicious same-UID process can race files, listeners, process
metadata, or the private environment between observations; the producer does
not claim isolation from that attacker. Use a dedicated OS user and keep all
other same-UID automation away from the repository, bound ports, runtime
processes, database, and credential files until capture and registration end.

The producer atomically publishes exactly:

- `zero-discoverability-report.log`
- `scan-scope-manifest.json`
- `request-target-exclusions.json`
- `line-addressed-allowlist.json`
- `normalized-scan-output.json`
- `normal-role-runtime-probes.json`

Register the completed leaf with the existing verifier:

```bash
scripts/qa/verify-wave3.sh register \
  --run-id FRESH_RUN_ID \
  --evidence-id W3-VER-PAYROLL-ZERO \
  --primary zero-discoverability-report=leaves/payroll-zero/raw/zero-discoverability-report.log \
  --artifact scan-scope-manifest=leaves/payroll-zero/raw/scan-scope-manifest.json \
  --artifact request-target-exclusions=leaves/payroll-zero/raw/request-target-exclusions.json \
  --artifact line-addressed-allowlist=leaves/payroll-zero/raw/line-addressed-allowlist.json \
  --artifact normalized-scan-output=leaves/payroll-zero/raw/normalized-scan-output.json \
  --artifact normal-role-runtime-probes=leaves/payroll-zero/raw/normal-role-runtime-probes.json
```

Run the dependency-free checks without Maven or a live runtime:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m py_compile \
  scripts/qa/verify_wave3_payroll_zero.py \
  scripts/qa/test_verify_wave3_payroll_zero.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v \
  scripts/qa/test_verify_wave3_payroll_zero.py
python3 scripts/qa/verify_wave3_payroll_zero.py self-test
```

These checks are producer tests only. They do not create formal live evidence;
the `execute` command above still requires a fresh frozen run, both bound
runtime listeners, the private environments, and the committed build-gates
bundle.
