# W3 current-run runtime acceptance harness

This harness produces the two current-run leaves that require a live normal
runtime:

- `W3-VER-W2-CURRENT-SMOKE`
- `W3-VER-NORMAL-BROWSER`

The companion demo harness produces `W3-VER-DEMO-ISOLATION` against the
frozen `dist/demo` preview. It rejects root `/api` traffic, cross-origin
HTTP(S) traffic and any MySQL connection beyond its own counter sample.

It fails closed unless the source hash, evidence run and actual database all
match. The database target is exactly MySQL `8.4.10` on
`127.0.0.1:13306`, schema `shenzhou_hr_test`, with identity
`mysql8410:<lower-@@server_uuid>:shenzhou_hr_test`.

All three leaves run the shared mechanical provenance verifier before and
after their workload and require the complete binding SHA-256 to remain
identical. W2 binds the unique backend listener to the current run, direct
Java/classpath/main class, private JDBC target and committed backend-full
`target/classes` receipt. Normal-browser also binds exact Node/Vite argv,
current tool/dependency/support manifests, fresh prod/demo receipts and dist
bytes, all 79 Vite raw source responses, the transformed main/App ESM graph,
and every file/metadata field in the command-owned
`frontend/node_modules/.cache/vite` tree. Demo binds the fresh demo receipt,
exact preview listener and an HTTP byte comparison of every `dist/demo` file.
Raw argv/environment entries, credentials, cookies and tokens are never
published.

## Safety boundary

Run every command from the repository root. Supply `--runtime-env` and
`--login-env` explicitly; there is no default credential path. Both files must
be absolute, outside this repository, owned by the current user, regular
non-symlink files, and mode `0600`. Do not enable shell tracing.

The runtime environment contains the exact local client/DB connection inputs.
The login environment contains only
`SHENZHOUHR_LOGIN_USERNAME` and `SHENZHOUHR_LOGIN_PASSWORD`. Neither harness
prints credentials or stores plaintext passwords in evidence. Normal-browser
execution may create or repair only three deterministic, run-bound synthetic
accounts, and only when `--provision-synthetic-accounts` is explicitly passed.

The harness does not start, migrate, rebuild, or stop MySQL. The operator must
first provide an approved local test database in its expected final state.

This evidence model assumes a trusted operator and an exclusive current-user
session. A hostile or compromised process running under the same UID can race
repository files, listeners, process metadata, or private environment files
between observations; these gates are provenance checks, not isolation from a
same-UID attacker. Run formal capture under a dedicated OS user with no other
same-UID automation touching the repository, runtimes, credentials, or ports.

## Static self-test

```bash
node --check scripts/qa/wave3-runtime-common.mjs
node --check scripts/qa/verify-wave3-w2-current-smoke.mjs
node --check scripts/qa/verify-wave3-normal-browser.mjs
node --test scripts/qa/test-wave3-runtime-harness.mjs
node scripts/qa/verify-wave3-w2-current-smoke.mjs self-test
node scripts/qa/verify-wave3-normal-browser.mjs self-test
node --check scripts/qa/verify-wave3-demo-isolation.mjs
node --test scripts/qa/test-wave3-demo-isolation.mjs
node scripts/qa/verify-wave3-demo-isolation.mjs self-test
PYTHONDONTWRITEBYTECODE=1 python3 -m py_compile \
  scripts/qa/wave3_runtime_provenance.py \
  scripts/qa/test_wave3_runtime_provenance.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v \
  scripts/qa/test_wave3_runtime_provenance.py
```

## Fresh run sequence

Resolve the actual DB identity before initializing evidence:

```bash
node scripts/qa/verify-wave3-w2-current-smoke.mjs identity \
  --runtime-env /absolute/private/runtime.env
```

Use that exact output for the fresh run:

```bash
scripts/qa/verify-wave3.sh init \
  --run-id FRESH_RUN_ID \
  --db-identity 'mysql8410:<lower-server-uuid>:shenzhou_hr_test' \
  --implementer-id IMPLEMENTER_ID \
  --implementer-process-id IMPLEMENTER_PROCESS_ID
```

Run `produce-wave3-build-gates.sh execute` before either runtime. Its committed
backend-full schema-v3 summary contains the exact logical file/byte manifest
for the clean-built `target/classes`; later targeted gates and final target
publication must remain byte-identical to it.

Start the backend as a direct `java` process from cwd `backend`. Its argv must
contain exactly `java`, one `-cp`/`-classpath`/`--class-path` pair, and
`com.szsemicon.hr.ShenzhouHrApplication`; `target/classes` is the first
classpath entry and every later entry is a fixed regular JAR below the current
Maven repository. Bind only `SHENZHOUHR_DB_URL`,
`SHENZHOUHR_DB_USERNAME`, and `SHENZHOUHR_DB_PASSWORD` to
`127.0.0.1:13306/shenzhou_hr_test` and the least-privilege
`shenzhou_hr_test_app` credential. Start it in a later whole second than the
clean build because Darwin listener start time is second-granular.

Before normal Vite starts, `frontend/node_modules/.cache/vite` must not exist.
If an earlier cache exists, move it to an operator-owned recovery path instead
of reusing it. Absence includes a dangling symbolic link; then verify:

```bash
test ! -e "$PWD/frontend/node_modules/.cache/vite" &&
  test ! -L "$PWD/frontend/node_modules/.cache/vite"
```

Start normal Vite with this exact command:

```bash
cd frontend
VITE_API_PROXY_TARGET=http://127.0.0.1:8080 \
  npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

The fixed `dev` script contributes the sole `--configLoader runner` pair.
Do not add Node options, other `VITE_*` keys, `.env.development*` files, or
reuse `.vite`/`.vite-temp`. The optimizer remains required: the provenance
probe warms and freezes the complete redirected `.cache/vite` tree and the
transformed ESM graph before browser work, then verifies both again. Confirm
the backend and frontend origins are reachable before capture.

Run the current W2 smoke:

```bash
node scripts/qa/verify-wave3-w2-current-smoke.mjs execute \
  --run-context "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/run-context.json" \
  --runtime-env /absolute/private/runtime.env \
  --login-env /absolute/private/login.env \
  --backend-url http://127.0.0.1:8080 \
  --output-dir "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/leaves/w2-current-smoke/raw"
```

Run the real browser matrix:

```bash
node scripts/qa/verify-wave3-normal-browser.mjs execute \
  --run-context "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/run-context.json" \
  --runtime-env /absolute/private/runtime.env \
  --login-env /absolute/private/login.env \
  --backend-url http://127.0.0.1:8080 \
  --frontend-url http://127.0.0.1:5173 \
  --output-dir "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/leaves/normal-browser/raw" \
  --provision-synthetic-accounts
```

After the normal listener is no longer needed, start the built-demo preview
from cwd `frontend` with this exact accepted argv command:

```bash
node "$PWD/node_modules/vite/bin/vite.js" preview --configLoader runner --host 127.0.0.1 --port 4175 --strictPort --outDir dist/demo
```

Do not set `VITE_*`, `NODE_OPTIONS`, `BABEL_ENV`, or a production/demo
`.env*` file for that process. The unique preview listener must start after
the fresh demo build. Then capture the demo isolation artifacts:

```bash
node scripts/qa/verify-wave3-demo-isolation.mjs execute \
  --run-context "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/run-context.json" \
  --runtime-env /absolute/private/runtime.env \
  --frontend-url http://127.0.0.1:4175 \
  --output-dir "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/leaves/demo-isolation/raw"
```

The demo server must serve the already-built `frontend/dist/demo` output, not
the Vite source development server. The HAR may contain same-origin HTML,
JavaScript, CSS, images and fonts. A request whose pathname starts with
`/api` or whose HTTP(S) origin differs from the demo preview is a blocking
business request. Independently of HAR, provenance fetches every regular file
below `dist/demo` with identity encoding and requires exact bytes and the
committed demo content-tree digest.

The browser gate covers only `SYSTEM_ADMIN`, `HR_ADMIN`, and read-only
`AUDITOR`, at exactly `390x844`, `768x1024`, `1024x768`, `1366x768`,
`1440x900`, and `1920x1080`. It records the 162 ordered route rows, real
HTTP/correlation evidence, DB/Flyway binding, screenshots, sanitized Chrome
traces, keyboard checks, and vendored offline axe output. Any axe `critical`
or `serious` violation blocks PASS; `incomplete` findings remain visible for
independent review.

## Register the leaves

```bash
scripts/qa/verify-wave3.sh register \
  --run-id FRESH_RUN_ID \
  --evidence-id W3-VER-W2-CURRENT-SMOKE \
  --primary w2-current-smoke-log=leaves/w2-current-smoke/raw/w2-current-smoke.log
```

```bash
scripts/qa/verify-wave3.sh register \
  --run-id FRESH_RUN_ID \
  --evidence-id W3-VER-NORMAL-BROWSER \
  --primary normal-browser-matrix=leaves/normal-browser/raw/route-matrix.json \
  --artifact normal-runtime-source-hash=leaves/normal-browser/raw/normal-runtime-source-hash.json \
  --artifact database-flyway-identity=leaves/normal-browser/raw/database-flyway-identity.json \
  --artifact principal-role-capability-export=leaves/normal-browser/raw/principal-role-capabilities.json \
  --artifact playwright-trace-inventory=leaves/normal-browser/raw/traces.json \
  --artifact screenshot-inventory=leaves/normal-browser/raw/screenshots.json \
  --artifact http-request-response-log=leaves/normal-browser/raw/http-request-response-log.json \
  --artifact backend-correlation-log=leaves/normal-browser/raw/backend-correlation.json \
  --artifact database-read-marker=leaves/normal-browser/raw/db-read-marker.json \
  --artifact axe-keyboard-report=leaves/normal-browser/raw/axe-keyboard-report.json
```

Both commands refuse an existing output directory or previously registered
leaf. A failed attempt leaves no PASS marker and removes its temporary output.
The registered W2/demo primary logs contain exactly one
`W3_RUNTIME_PROVENANCE_JSON=` line. The registered
`normal-runtime-source-hash.json` contains the full sanitized provenance
document and `provenanceStable: true`; these registered bytes are the runtime
binding, not an unregistered sidecar.
