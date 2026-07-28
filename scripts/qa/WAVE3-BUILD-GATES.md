# W3 frozen-source build gates

`produce-wave3-build-gates.sh` is the current-run producer for these exact
evidence leaves:

- `W3-VER-BACKEND-FULL`
- `W3-VER-W1-REGRESSION`
- `W3-VER-W2-REGRESSION`
- `W3-VER-W3-REGRESSION`
- `W3-VER-FRONTEND-TYPECHECK`
- `W3-VER-FRONTEND-LINT`
- `W3-VER-FRONTEND-TESTS`
- `W3-VER-PROD-BUILD`
- `W3-VER-DEMO-BUILD`

It runs all nine gates sequentially against one already initialized evidence
context. Commands execute from a copied, run-private source workspace. A
run-wide Darwin kqueue seal covers the original and copied source, exact
fd-bound source identity/content tuples and containing directories,
`frontend/node_modules`, the Maven cache, the complete user-owned Java runtime,
the complete npm package runtime, and the resolved self-contained Node binary.
Java/npm runtime logical manifests are captured before watcher construction and
recomputed after the full watcher closure is live; their exact digests/counts,
plus the Node executable hash, are embedded in every workspace receipt.

## Threat and operator boundary

These gates defend against stale inputs, accidental or independent concurrent
filesystem mutation, path/symlink replacement, partial publication, and
forged or internally inconsistent receipts. They assume the current OS user,
the reviewed frozen command sources, and the operator of the formal run are
trusted. They do not claim isolation from a malicious process with the same
UID (which could signal, trace, or inject command-owned output), from root, or
from a compromised kernel. During a formal run no other same-UID process may
write the repository, dependency homes, run root, or tool runtimes.

`target`, `dist`, the two declared cache namespaces, and the private staging
tree are command-owned outputs rather than immutable semantic inputs. Their
contents are accepted only through the command exit/test parser, fresh
content-addressed receipts, exact role closure, atomic publication, and later
consumer/reviewer checks. “Transient mutation is fatal” below refers to the
sealed immutable input authorities and cache-root identities, not to bytes
inside those declared output namespaces.

Seal construction has two phases: it first opens the recursive directory
closure through retained parent fds and registers every discovered
root/parent/candidate. A separate identity-only fd chain binds every lexical
ancestor from `/` to each authority parent without treating unrelated sibling
writes as mutations. Only after that global watcher closure is live does the
producer sample the canonical content manifest. Pre-open and
post-registration metadata must match, and the queue is checked throughout
both phases. The completed watcher closure is the linearization point: every
subsequent transient write-and-restore, rename-and-restore, or ancestor
re-binding is fatal. The already frozen source hash and origin dependency
manifest are recomputed while that closure is live, so a durable change made
after those baselines but before the linearization point cannot become a new
accepted baseline. Exact source identities are also rescanned before and
after every command.

On APFS, a read may emit `KQ_NOTE_ATTRIB` when only the unsealed access time
advances. The seal drains the complete event queue and accepts an
attribute-only event only when `fstat` on that exact retained vnode descriptor
still matches its protected device, inode, mode, size, mtime, and ctime
baseline. Any combined or non-attribute event is immediately fatal, as is an
attribute event with changed protected metadata. Source/tool records and
directory/cache anchors are then rebound normally, so this access-time
exception does not waive chmod, replacement, content, timestamp, or namespace
changes. A fatal event reports its retained descriptor path, decoded vnode
flags and failure reason for current-run diagnosis; this diagnostic never
changes which event is accepted.

All nine empty gate-output directories are created before the isolated
workspace seal is established. Commands write only inside those preallocated
containers; the Vitest private report is nested inside its own gate container
and removed before publication. The protected staging-parent child set
therefore stays fixed while commands run. Creating, deleting, or linking an
undeclared sibling under that parent remains a fatal WRITE/LINK event.

Before commands can start, the producer constructs a canonical logical
dependency manifest for the active `node_modules` tree and the complete Maven
home. The manifest binds relative path, kind, mode, file size/hash, link text,
and resolved npm target. The copied dependency manifest must equal the origin
manifest immediately after copying and again after the isolated seal is live.
Origin and isolated trees therefore cannot be independently re-baselined. The
exact comparison result, logical-manifest digest, Node/Maven record counts,
and npm link count are embedded in every command's workspace-seal receipt.
The origin seal is closed only after the copy and exact handoff are complete,
before the isolated full-tree seal is opened; the two descriptor-heavy seals
therefore never overlap.
The payroll-zero consumer independently rebuilds this origin dependency
manifest inside its own short-lived kqueue seal and requires the receipt
digest/counts to equal the current complete Node/Maven closure; the producer's
summary is not accepted as an opaque self-attestation.

The only symbolic dependency entries admitted by the seal are ordinary
relative links directly below `node_modules/.bin`. Their normalized target
must remain inside the same cloned or origin `node_modules` tree, every target
ancestor must be a real directory, and the final target must be a regular
file. Link and target reads share one held boundary fd; each target component
is opened through `openat(O_NOFOLLOW)` and its named parent chain is rebound
after the read. The seal binds link device/inode/mode/size/timestamps and text
together with the resolved target path, ancestors, identity, metadata, size,
and SHA-256. Targets beneath `.cache` or `.tmp` are forbidden.
Origin caches are identity-anchored but content-writable during the copy seal,
so an already running development server cannot create a false failure. Their
contents are not copied: real empty top-level isolated cache directories are created
privately. Each cache root has its own delete/attribute/rename/revoke watcher
and is rebound by parent fd on every seal check; its device, inode, type, and
mode cannot change, while content writes remain command-writable. Same-named
nested package directories stay sealed. Absolute, external, broken, looping,
retargeted, directory-type, and non-`.bin` links fail closed. The frozen
installation contains exactly 14 accepted `.bin` links.

Command-writable cache bytes are never accepted as semantic inputs. TypeScript
always runs build mode with `--force`, Vitest runs with cache disabled, and
Vite/Vitest use the runner config loader, so executable `.vite-temp` config
bundles are neither created nor imported (an already present empty directory is
still treated as immutable input). `.tmp` build-info and npm `.cache` are
output-only for these gates; their root identities stay sealed. Vite's
`cacheDir` is fixed below that excluded namespace at
`node_modules/.cache/vite`, so a cold normal development-server start cannot
mutate the sealed `.vite` dependency tree after the build receipts are issued.
The normal Vite optimizer is required because React's development entry points
are CommonJS. Its generated cache is a command-owned runtime output, never a
build-gate dependency input. Normal-browser runtime provenance binds the exact
cache tree and transformed application module closure before and after the
browser run, together with the listener, argv, current sources, dependency
seal, and fresh build receipts.
Vitest dependency optimization and filesystem module caching are disabled, so
the existing `.vite` tree is immutable input and remains fully sealed.
Creation, replacement, or content mutation of any undeclared sibling cache,
including `.vite-temp`, is a fatal node_modules directory mutation.

The command workspace also carries an exact 14-record support manifest:
twelve repository inputs used outside the copied backend/frontend trees
(OpenAPI, MySQL helpers, Nginx/UIUX evidence, reviewed design artifacts, and
the retained registry) plus two generated empty npm config files. Every
repository-backed input is included in the frozen source hash, and the copied
destination bytes/mode are compared before and after the support-file watcher
closure is live. npm receives private `HOME`/`TMPDIR`/cache paths and explicit
sealed user/global config paths, so the operator's live `~/.npmrc` cannot
change a gate. Maven receives private `HOME`, `MAVEN_USER_HOME`,
`MAVEN_SKIP_RC=1`, and a matching JVM `user.home`; `JAVA_HOME` is not inherited,
so the Maven launcher uses the same PATH-resolved Java executable bound by the
tool receipt. Maven and frontend commands receive separate PATHs: Maven sees
only the sealed JDK `bin` plus root-owned system directories, while frontend
commands see only the watched Node binary directory plus those system
directories. Cross-prefix shadow insertion and user-writable discovery
prefixes are therefore excluded; the non-system Node search directory itself
is watched against transient replacement/insertion. Temporary files use the
writable isolated `.tmp` anchor.
The consumer also recomputes the repository-layout support manifest and
requires its 14-record digest/count to match every build receipt.

The isolated `backend/target` and `frontend/dist` containers are created empty
before the source seal. Their parent identities remain sealed while build
outputs may change only below those excluded roots. Maven receives
`maven.clean.excludeDefaultDirectories=true`: the pre-created `target`
directory is already empty, so `clean test` remains fresh without deleting and
recreating the sealed container. After the global watcher closure is live,
every isolated backend/frontend source file is compared with the frozen source
snapshot a second time.

Each command owns a new session/process group. Timeout, exception, and
apparently successful leader exit all trigger a whole-group liveness probe.
Any surviving descendant makes the gate fail; cleanup always gives TERM a
bounded grace period, attempts SIGKILL even if the leader/pipes already
disappeared, reaps the direct child, and waits for group ESRCH before returning.

The backend-full gate begins with `./mvnw --offline clean test`; the targeted
regressions reuse that isolated clean compilation. Surefire XML is reconciled
testcase-by-testcase against root counts, requires exact recursive FQCN
closure, and allows no skip/failure/error. Vitest requires the exact 26-file
closure from the unique `testResults[].name` records, every file and assertion
to be passed and non-empty, and zero failed, pending, or todo tests. Vitest v4
reports recursively nested `describe` blocks in `numTotalTestSuites`, so that
strictly positive all-passed suite count is reconciled independently rather
than equated with the number of test files. The report must be strict UTF-8
JSON with no duplicate object keys at any depth and no nonstandard
`NaN`/`Infinity` constants. Raw Surefire/Vitest bytes are
archived as canonical JSON+zlib+base64 inside the existing contract roles.
Every archive is immediately decoded again and must match exact envelope and
member key closures, canonical base64 and JSON, strict single-stream zlib,
both compressed/uncompressed sizes and hashes, canonical safe member paths,
and every member's raw-byte size/hash.

The schema-v3 backend-full test summary also commits an
absolute-path-independent receipt for every regular byte below
`target/classes` (canonical relative path, mode, size and SHA-256), including
the exact main-class hash. The producer captures it inside the clean
backend-full command window, requires all three targeted regression gates to
leave it byte-identical, publishes `backend/target`, and requires the
published tree to remain exactly equal. Runtime provenance consumers compare
the live direct-Java class tree to this same committed receipt.

Production/demo output is built in isolation, exclusively published into
`frontend/dist`, and scanned through nofollow fds. Every output mtime and ctime
must be inside its command window. Inventories contain the exact file manifest
and a compressed content-addressed archive of every output byte.

Artifact files are sealed `0400` and raw directories `0500`. Nine raw
directories are published by native no-clobber rename and fsynced; only then
is `wave3-build-gates.commit.json` exclusively published last. The strict
verifier and orchestrator both require that marker and its exact-nine identity
manifest, so an interrupted partial batch is not registerable.
The marker rename is the explicit commit point: an interrupt before it removes
only identity-matching raw directories from a complete pre-rename plan,
including a raw directory whose native rename succeeded immediately before an
interrupt. An interrupt after it preserves and revalidates the complete
marker+exact-nine bundle instead of rolling raw directories back underneath a
committed marker. Final lock removal is derived from that durable marker,
exact raw closure, modes, inodes, and hashes rather than from an interruptible
Python return-value assignment.

## Static checks

Run from the fixed repository root:

```bash
python3 -m py_compile scripts/qa/produce_wave3_build_gates.py
ruff check scripts/qa/produce_wave3_build_gates.py scripts/qa/test_produce_wave3_build_gates.py
python3 -m unittest scripts.qa.test_produce_wave3_build_gates -v
bash scripts/qa/produce-wave3-build-gates.sh self-test
```

The self-test marker is:

```text
W3_BUILD_GATE_PRODUCER_SELF_TEST=PASS tests=41 gates=9 backendClasses=39 frontendFiles=26
```

## Current-run execution

Initialize the W3 evidence run only after source is frozen and the real
MySQL 8.4.10 database identity is known. Then run:

```bash
bash scripts/qa/produce-wave3-build-gates.sh execute \
  --run-context "$PWD/docs/verification/wave3/runs/FRESH_RUN_ID/run-context.json"
```

The producer requires an absolute `run-context.json` directly below the W3
runs root and refuses symbolic ancestors, transient source/dependency change,
existing destinations, incomplete/skipped test closure, stale build outputs,
or contract-role drift. An interrupted/failed run retains its lock so partial
output cannot be mistaken for a new run. Maven gates publish their isolated
final target, so do not run another Maven process at the same time.

After all other pre-review producers have completed, use the orchestration
plan to preflight and register these raw artifacts with the strict evidence
verifier. Never copy build artifacts from a historical run.
