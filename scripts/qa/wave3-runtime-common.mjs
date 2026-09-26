#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import {
  createHash,
  createHmac,
  randomBytes,
} from 'node:crypto';
import {
  chmodSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import {
  dirname,
  isAbsolute,
  join,
  relative,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath } from 'node:url';

export const EVIDENCE_ID_NORMAL_BROWSER = 'W3-VER-NORMAL-BROWSER';
export const EVIDENCE_ID_W2_CURRENT_SMOKE = 'W3-VER-W2-CURRENT-SMOKE';
export const EVIDENCE_ID_DEMO_ISOLATION = 'W3-VER-DEMO-ISOLATION';
export const NORMAL_BROWSER_MARKER =
  'W3_NORMAL_BROWSER_MATRIX=PASS roles=SYSTEM_ADMIN,HR_ADMIN,AUDITOR viewports=390x844,768x1024,1024x768,1366x768,1440x900,1920x1080 transport=normal db=real';
export const W2_CURRENT_SMOKE_MARKER = 'W3_W2_CURRENT_SMOKE=PASS';
export const DEMO_ISOLATION_MARKER =
  'W3_DEMO_ISOLATION=PASS businessRequests=0 mysqlConnections=0';
export const CONTEXT_PREFIX = 'W3_EVIDENCE_CONTEXT=PASS';
export const COMPANY_ID = '30000000-0000-0000-0000-000000000001';
export const RUNTIME_DATABASE = 'shenzhou_hr_test';
export const MYSQL_HOST = '127.0.0.1';
export const MYSQL_PORT = '13306';
export const MYSQL_VERSION = '8.4.10';
export const MYSQL_CLIENT =
  '/Users/huzhijin/.local/share/shenzhouhr/mysql-8.4.10-isolated/install/bin/mysql';
export const MYSQL_ISOLATED_ROOT =
  '/Users/huzhijin/.local/share/shenzhouhr/mysql-8.4.10-isolated';
export const SHA256_PATTERN = /^[0-9a-f]{64}$/;
export const RUN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{5,63}$/;
export const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export const HTTP_TIMEOUT_MS = 15_000;
export const SUBPROCESS_TIMEOUT_MS = 30_000;
export const RUNTIME_PROVENANCE_TIMEOUT_MS = 15 * 60_000;
export const RUNTIME_PROVENANCE_PREFIX = 'W3_RUNTIME_PROVENANCE_JSON=';

const scriptPath = fileURLToPath(import.meta.url);
export const repositoryRoot = resolve(dirname(scriptPath), '../..');
export const EXPECTED_REPOSITORY_ROOT = repositoryRoot;

assert(
  process.cwd() === EXPECTED_REPOSITORY_ROOT
    && repositoryRoot === EXPECTED_REPOSITORY_ROOT,
  'PROJECT_ROOT_SCOPE_ERROR',
);

export const RUNTIME_ENV_ALLOWED_KEYS = [
  'SHENZHOUHR_MYSQL_HOST',
  'SHENZHOUHR_MYSQL_PORT',
  'SHENZHOUHR_MYSQL_CLIENT_BIN',
  'SHENZHOUHR_FLYWAY_BIN',
  'SHENZHOUHR_TEST_FINAL_STATE',
  'SHENZHOUHR_W3_DB_IDENTITY',
  'SHENZHOUHR_MYSQL_ROOT_PASSWORD',
  'SHENZHOUHR_FLYWAY_PASSWORD',
  'SHENZHOUHR_DEV_DB_PASSWORD',
  'SHENZHOUHR_TEST_DB_PASSWORD',
];

export function parseNamedArguments(args, allowedNames, booleanNames = []) {
  const values = new Map();
  const booleans = new Set(booleanNames);
  for (let index = 0; index < args.length;) {
    const token = args[index];
    assert(token?.startsWith('--'), 'arguments must use explicit --name values');
    const name = token.slice(2);
    assert(allowedNames.includes(name), `unsupported argument: --${name}`);
    assert(!values.has(name), `duplicate argument: --${name}`);
    if (booleans.has(name)) {
      values.set(name, true);
      index += 1;
    } else {
      const value = args[index + 1];
      assert(value !== undefined && !value.startsWith('--'), `--${name} requires a value`);
      values.set(name, value);
      index += 2;
    }
  }
  return values;
}

export function requireArguments(values, names) {
  for (const name of names) {
    assert(values.has(name), `missing required argument: --${name}`);
  }
}

export function readRuntimeEnvironment(path) {
  const environment = readPrivateEnvironment(
    path,
    RUNTIME_ENV_ALLOWED_KEYS,
    [
      'SHENZHOUHR_MYSQL_HOST',
      'SHENZHOUHR_MYSQL_PORT',
      'SHENZHOUHR_MYSQL_CLIENT_BIN',
      'SHENZHOUHR_W3_DB_IDENTITY',
      'SHENZHOUHR_FLYWAY_PASSWORD',
      'SHENZHOUHR_TEST_DB_PASSWORD',
    ],
  );
  assert(
    environment.SHENZHOUHR_MYSQL_HOST === MYSQL_HOST,
    `runtime MySQL host must be exactly ${MYSQL_HOST}`,
  );
  assert(
    environment.SHENZHOUHR_MYSQL_PORT === MYSQL_PORT,
    `runtime MySQL port must be exactly ${MYSQL_PORT}`,
  );
  assert(
    environment.SHENZHOUHR_MYSQL_CLIENT_BIN === MYSQL_CLIENT,
    `runtime MySQL client must be exactly ${MYSQL_CLIENT}`,
  );
  assertRegularExecutable(MYSQL_CLIENT, 'isolated MySQL client');
  assert(
    realpathSync(MYSQL_CLIENT) === MYSQL_CLIENT,
    'isolated MySQL client must not resolve through an indirect path',
  );
  return environment;
}

export function readLoginEnvironment(path) {
  return readPrivateEnvironment(
    path,
    ['SHENZHOUHR_LOGIN_USERNAME', 'SHENZHOUHR_LOGIN_PASSWORD'],
    ['SHENZHOUHR_LOGIN_USERNAME', 'SHENZHOUHR_LOGIN_PASSWORD'],
  );
}

export function readPrivateEnvironment(path, allowedKeys, requiredKeys) {
  assert(isAbsolute(path), 'environment paths must be absolute');
  const requested = lstatSync(path);
  assert(
    requested.isFile() && !requested.isSymbolicLink(),
    'environment files must be regular files, not symbolic links',
  );
  const resolvedPath = realpathSync(path);
  assert(
    resolvedPath !== repositoryRoot
      && !resolvedPath.startsWith(`${repositoryRoot}${sep}`),
    'environment files must be outside the repository',
  );
  const metadata = statSync(resolvedPath);
  assert((metadata.mode & 0o777) === 0o600, 'environment file mode must be exactly 0600');
  if (typeof process.getuid === 'function') {
    assert(metadata.uid === process.getuid(), 'environment file must be owned by the current user');
  }
  assert(metadata.size <= 64 * 1024, 'environment file must be at most 64 KiB');

  const allowed = new Set(allowedKeys);
  const environment = {};
  for (const [index, rawLine] of readFileSync(resolvedPath, 'utf8').split(/\r?\n/).entries()) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
    if (!line || line.trimStart().startsWith('#')) continue;
    const separatorIndex = line.indexOf('=');
    assert(separatorIndex >= 1, `invalid environment entry at line ${index + 1}`);
    const key = line.slice(0, separatorIndex);
    const value = line.slice(separatorIndex + 1);
    assert(
      /^[A-Z][A-Z0-9_]*$/.test(key) && allowed.has(key),
      `unsupported environment key at line ${index + 1}`,
    );
    assert(!Object.hasOwn(environment, key), `duplicate environment key at line ${index + 1}`);
    assert(!value.includes('\0'), `invalid environment value at line ${index + 1}`);
    environment[key] = value;
  }
  for (const key of requiredKeys) {
    assert(environment[key], `required environment key is missing: ${key}`);
  }
  return environment;
}

export function loadRunContext(path, expectedEvidenceId) {
  assert(isAbsolute(path), '--run-context must be absolute');
  assertRegularFileNoSymlink(path, 'run context');
  const resolvedPath = realpathSync(path);
  const runsRoot = resolve(repositoryRoot, 'docs/verification/wave3/runs');
  assert(
    resolvedPath.startsWith(`${runsRoot}${sep}`),
    'run context must be under docs/verification/wave3/runs',
  );
  assert(resolvedPath.endsWith(`${sep}run-context.json`), 'run context filename must be run-context.json');
  const context = readJson(resolvedPath, 'run context');
  const exactKeys = [
    'schemaVersion',
    'nodeType',
    'runId',
    'sourceTreeHash',
    'databaseIdentity',
    'implementerId',
    'implementerProcessId',
    'startedAt',
    'startedAtEpochNs',
    'sourceMaximumMtimeNs',
    'contractPath',
    'contractSha256',
  ];
  assertExactKeys(context, exactKeys, 'run context');
  assert(context.schemaVersion === 1 && context.nodeType === 'run-context', 'run context schema mismatch');
  assert(RUN_ID_PATTERN.test(context.runId), 'run context runId is invalid');
  assert(SHA256_PATTERN.test(context.sourceTreeHash), 'run context sourceTreeHash is invalid');
  assert(
    typeof context.databaseIdentity === 'string'
      && context.databaseIdentity.length >= 4
      && context.databaseIdentity.length <= 240,
    'run context databaseIdentity is invalid',
  );
  assert(
    dirname(resolvedPath).endsWith(`${sep}${context.runId}`),
    'run context directory/runId mismatch',
  );
  const contractPath = resolve(repositoryRoot, context.contractPath);
  assertRegularFileNoSymlink(contractPath, 'evidence contract');
  assert(sha256File(contractPath) === context.contractSha256, 'evidence contract hash is stale');
  return {
    ...context,
    evidenceId: expectedEvidenceId,
    path: resolvedPath,
    runRoot: dirname(resolvedPath),
  };
}

export function contextLine(context, evidenceId) {
  return `${CONTEXT_PREFIX} runId=${context.runId} sourceHash=${context.sourceTreeHash} dbIdentity=${context.databaseIdentity} evidenceId=${evidenceId}`;
}

export function currentSourceTreeHash() {
  const program = [
    'from pathlib import Path',
    'from scripts.qa.verify_wave3_evidence import Wave3Evidence',
    'print(Wave3Evidence(Path.cwd()).compute_source_snapshot().tree_hash)',
  ].join('; ');
  const result = spawnSync('python3', ['-c', program], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    timeout: SUBPROCESS_TIMEOUT_MS,
    killSignal: 'SIGKILL',
    maxBuffer: 1024 * 1024,
    env: safeSubprocessEnvironment(),
  });
  assert(!result.error, 'source-tree hash verifier could not execute');
  assert(result.status === 0, 'source-tree hash verifier failed');
  const hash = result.stdout.trim();
  assert(SHA256_PATTERN.test(hash), 'source-tree hash verifier returned an invalid digest');
  return hash;
}

export function assertFrozenSource(context) {
  assert(
    currentSourceTreeHash() === context.sourceTreeHash,
    'current normalized source tree differs from the run context',
  );
}

export function canonicalJson(value) {
  const normalize = (item) => {
    if (Array.isArray(item)) return item.map(normalize);
    if (item && typeof item === 'object') {
      return Object.fromEntries(
        Object.keys(item)
          .sort()
          .map((key) => [key, normalize(item[key])]),
      );
    }
    return item;
  };
  return JSON.stringify(normalize(value));
}

export function parseRuntimeProvenance(text, {
  mode,
  context,
}) {
  let document;
  try {
    document = JSON.parse(text);
  } catch {
    throw new Error('runtime provenance verifier returned invalid JSON');
  }
  assertExactKeys(document, [
    'schemaVersion',
    'nodeType',
    'mode',
    'runId',
    'sourceTreeHash',
    'databaseIdentity',
    'binding',
    'bindingSha256',
    'verdict',
  ], 'runtime provenance');
  assert(
    Number.isSafeInteger(document.schemaVersion)
      && document.schemaVersion === 1
      && document.nodeType === 'wave3-runtime-provenance'
      && document.mode === mode
      && document.runId === context.runId
      && document.sourceTreeHash === context.sourceTreeHash
      && document.databaseIdentity === context.databaseIdentity
      && document.verdict === 'PASS',
    'runtime provenance identity/schema mismatch',
  );
  assert(
    document.binding
      && typeof document.binding === 'object'
      && !Array.isArray(document.binding)
      && document.binding.verdict === 'PASS',
    'runtime provenance binding is invalid',
  );
  assertPublicProvenanceValue(document, 'runtime provenance');
  assert(
    SHA256_PATTERN.test(document.bindingSha256)
      && document.bindingSha256 === sha256Text(canonicalJson(document.binding)),
    'runtime provenance binding digest mismatch',
  );
  return document;
}

export function captureRuntimeProvenance({
  mode,
  context,
  runtimeEnv,
  backendUrl,
  frontendUrl,
}) {
  assert(['w2', 'normal', 'demo'].includes(mode), 'runtime provenance mode is invalid');
  const args = [
    '-B',
    join(repositoryRoot, 'scripts/qa/wave3_runtime_provenance.py'),
    'capture',
    '--mode',
    mode,
    '--run-context',
    context.path,
    '--runtime-env',
    runtimeEnv,
  ];
  if (backendUrl) args.push('--backend-url', backendUrl);
  if (frontendUrl) args.push('--frontend-url', frontendUrl);
  const result = spawnSync('python3', args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    timeout: RUNTIME_PROVENANCE_TIMEOUT_MS,
    killSignal: 'SIGKILL',
    maxBuffer: 32 * 1024 * 1024,
    env: safeSubprocessEnvironment(),
  });
  assert(!result.error, 'runtime provenance verifier could not execute');
  assert(
    result.status === 0,
    'runtime provenance verifier failed; run its documented CLI for a public-safe diagnostic',
  );
  assert(result.stderr === '', 'runtime provenance verifier emitted unexpected diagnostics');
  return parseRuntimeProvenance(result.stdout, { mode, context });
}

export function assertRuntimeProvenanceStable(before, after) {
  for (const key of [
    'schemaVersion',
    'nodeType',
    'mode',
    'runId',
    'sourceTreeHash',
    'databaseIdentity',
    'verdict',
  ]) {
    assert(before[key] === after[key], `runtime provenance ${key} changed`);
  }
  assert(
    before.bindingSha256 === after.bindingSha256
      && canonicalJson(before.binding) === canonicalJson(after.binding),
    'runtime provenance changed between the before/after seals',
  );
  return after;
}

export function runtimeProvenanceLine(document) {
  return `${RUNTIME_PROVENANCE_PREFIX}${canonicalJson(document)}`;
}

function assertPublicProvenanceValue(value, label) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => assertPublicProvenanceValue(item, `${label}[${index}]`));
    return;
  }
  if (value && typeof value === 'object') {
    for (const [key, item] of Object.entries(value)) {
      assert(
        !key.startsWith('_private')
          && !['__proto__', 'prototype', 'constructor'].includes(key),
        `${label} contains a private/unsafe key`,
      );
      assertPublicProvenanceValue(item, `${label}.${key}`);
    }
    return;
  }
  assert(
    value === null
      || typeof value === 'string'
      || typeof value === 'boolean'
      || (typeof value === 'number' && Number.isSafeInteger(value)),
    `${label} contains a non-canonical JSON scalar`,
  );
}

export function createDatabaseBinding(runtimeEnvironment) {
  const temporaryRoot = mkdtempSync(join(tmpdir(), 'shenzhouhr-w3-db-binding-'));
  try {
    const migratorDefaults = createMysqlDefaultsFile(
      temporaryRoot,
      'migrator.cnf',
      'shenzhou_hr_local_migrator',
      runtimeEnvironment.SHENZHOUHR_FLYWAY_PASSWORD,
    );
    const appDefaults = createMysqlDefaultsFile(
      temporaryRoot,
      'test-app.cnf',
      'shenzhou_hr_test_app',
      runtimeEnvironment.SHENZHOUHR_TEST_DB_PASSWORD,
    );
    const serverRows = mysqlQuery(
      runtimeEnvironment,
      migratorDefaults,
      [
        'SELECT VERSION(), @@port, @@server_uuid, @@socket, @@datadir, DATABASE();',
        "SELECT installed_rank, version, description, type, script, COALESCE(CAST(checksum AS CHAR), 'NULL'), success",
        'FROM flyway_schema_history',
        'ORDER BY installed_rank;',
      ].join('\n'),
    ).trim().split(/\r?\n/).filter(Boolean);
    assert(serverRows.length >= 2, 'database identity query returned incomplete evidence');
    const server = serverRows[0].split('\t');
    assert(server.length === 6, 'database server identity row is malformed');
    const [reportedVersion, reportedPort, serverUuid, socket, datadir, database] = server;
    const versionCore = /^(\d+\.\d+\.\d+)/.exec(reportedVersion)?.[1];
    assert(versionCore === MYSQL_VERSION, `MySQL SemVer core must be exactly ${MYSQL_VERSION}`);
    assert(reportedPort === MYSQL_PORT, `MySQL reported port must be exactly ${MYSQL_PORT}`);
    assert(UUID_PATTERN.test(serverUuid), 'MySQL server UUID is invalid');
    assert(
      database === RUNTIME_DATABASE,
      `MySQL database must be exactly ${RUNTIME_DATABASE}`,
    );
    assert(
      socket.startsWith(`${MYSQL_ISOLATED_ROOT}/`) && datadir.startsWith(`${MYSQL_ISOLATED_ROOT}/`),
      'MySQL socket/datadir are outside the isolated 8.4.10 root',
    );
    const historyLines = serverRows.slice(1);
    const history = historyLines.map((line) => {
      const fields = line.split('\t');
      assert(fields.length === 7, 'Flyway history row is malformed');
      assert(fields[6] === '1', 'Flyway history contains an unsuccessful migration');
      return {
        installedRank: Number(fields[0]),
        version: fields[1],
        description: fields[2],
        type: fields[3],
        script: fields[4],
        checksum: fields[5] === 'NULL' ? null : Number(fields[5]),
        success: true,
      };
    });
    assert(history.length >= 7, 'Flyway history must include at least V1-V7');
    assert(
      history.every((row, index) => Number.isInteger(row.installedRank)
        && (index === 0 || row.installedRank > history[index - 1].installedRank)),
      'Flyway installed_rank must be strictly increasing',
    );
    const latestVersion = history.at(-1)?.version;
    assert(latestVersion, 'Flyway latest version is missing');
    const historySha256 = sha256Text(
      history.map((row) => [
        row.installedRank,
        row.version,
        row.description,
        row.type,
        row.script,
        row.checksum ?? 'NULL',
        row.success ? 1 : 0,
      ].join('\t')).join('\n') + '\n',
    );
    const databaseIdentity = databaseIdentityFor(serverUuid);
    assert(
      runtimeEnvironment.SHENZHOUHR_W3_DB_IDENTITY === databaseIdentity,
      'runtime environment database identity differs from the actual MySQL server',
    );

    const appMarkerRows = mysqlQuery(
      runtimeEnvironment,
      appDefaults,
      [
        `SELECT COUNT(*) FROM company WHERE company_id = '${COMPANY_ID}';`,
        'SELECT COUNT(*) FROM attendance_policy_template;',
        'SELECT COUNT(*) FROM attendance_policy_scoped_version;',
        'SELECT COUNT(*) FROM attendance_group_revision;',
        'SELECT COUNT(*) FROM shift_version;',
        'SELECT COUNT(*) FROM work_calendar_version;',
        'SELECT COUNT(*) FROM organization_current_projection;',
        'SELECT COUNT(*) FROM employee_current_projection;',
        'SELECT COUNT(*) FROM people_import_batch;',
        'SELECT COUNT(*) FROM policy_template;',
      ].join('\n'),
    ).trim().split(/\r?\n/);
    assert(appMarkerRows.length === 10, 'application DB marker query is incomplete');
    assert(appMarkerRows[0] === '1', 'fixed company is missing from the approved local DB');
    const counts = {
      company: Number(appMarkerRows[0]),
      policyTemplates: Number(appMarkerRows[1]),
      policyVersions: Number(appMarkerRows[2]),
      groupRevisions: Number(appMarkerRows[3]),
      shiftVersions: Number(appMarkerRows[4]),
      calendarVersions: Number(appMarkerRows[5]),
      organizations: Number(appMarkerRows[6]),
      employees: Number(appMarkerRows[7]),
      peopleImportBatches: Number(appMarkerRows[8]),
      retainedPolicyTemplates: Number(appMarkerRows[9]),
    };
    assert(
      Object.values(counts).every((value) => Number.isSafeInteger(value) && value >= 0),
      'application DB marker counts are invalid',
    );
    assert(counts.policyTemplates === 3 && counts.policyVersions >= 3, 'W3 policy seed DB marker is invalid');
    return {
      databaseIdentity,
      server: {
        version: reportedVersion,
        versionCore,
        host: MYSQL_HOST,
        port: Number(reportedPort),
        serverUuid: serverUuid.toLowerCase(),
        socket,
        datadir,
        database,
      },
      flyway: {
        latestVersion,
        appliedCount: history.length,
        historySha256,
      },
      applicationReadMarker: counts,
    };
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

export function assertDatabaseBinding(context, binding) {
  assert(
    binding.databaseIdentity === context.databaseIdentity,
    'actual MySQL identity differs from the orchestrated run databaseIdentity',
  );
}

export function databaseIdentityFor(serverUuid) {
  assert(UUID_PATTERN.test(serverUuid), 'MySQL server UUID is invalid');
  return `mysql8410:${serverUuid.toLowerCase()}:${RUNTIME_DATABASE}`;
}

export function mysqlQuery(environment, defaultsPath, sql) {
  const result = spawnSync(environment.SHENZHOUHR_MYSQL_CLIENT_BIN, [
    `--defaults-extra-file=${defaultsPath}`,
    '--batch',
    '--skip-column-names',
    `--database=${RUNTIME_DATABASE}`,
    '--execute',
    sql,
  ], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    timeout: SUBPROCESS_TIMEOUT_MS,
    killSignal: 'SIGKILL',
    maxBuffer: 4 * 1024 * 1024,
    env: safeSubprocessEnvironment(),
  });
  assert(!result.error, 'MySQL evidence query could not execute');
  assert(result.status === 0, 'MySQL evidence query failed');
  return result.stdout;
}

export function createMysqlDefaultsFile(directory, name, user, password) {
  const path = join(directory, name);
  const contents = [
    '[client]',
    `host="${mysqlOptionEscape(MYSQL_HOST)}"`,
    `port="${mysqlOptionEscape(MYSQL_PORT)}"`,
    'protocol=tcp',
    `user="${mysqlOptionEscape(user)}"`,
    `password="${mysqlOptionEscape(password)}"`,
    '',
  ].join('\n');
  writeFileSync(path, contents, { mode: 0o600 });
  chmodSync(path, 0o600);
  return path;
}

export function mysqlOptionEscape(value) {
  return value
    .replaceAll('\\', '\\\\')
    .replaceAll('"', '\\"')
    .replaceAll('\t', '\\t')
    .replaceAll('\r', '\\r')
    .replaceAll('\n', '\\n');
}

export class ApiSession {
  constructor(baseUrl, traceRows = []) {
    this.baseUrl = validateLoopbackUrl(baseUrl, 'backend URL').origin;
    this.traceRows = traceRows;
    this.cookie = undefined;
    this.csrfToken = undefined;
  }

  async login(username, password, label, expectedStatuses = [200]) {
    return this.request(label, '/api/v1/auth/login', {
      method: 'POST',
      body: { username, password },
      includeAuthentication: false,
      includeCsrf: false,
      expectedStatus: expectedStatuses,
      sensitiveRequest: true,
    });
  }

  async request(label, path, options = {}) {
    assert(
      path.startsWith('/api/v1/') && !path.includes('\\'),
      `invalid API path for ${label}`,
    );
    const method = options.method ?? 'GET';
    const headers = new Headers(options.headers ?? {});
    headers.set('Accept', 'application/json');
    if (options.includeAuthentication !== false && this.cookie) {
      headers.set('Cookie', this.cookie);
    }
    if (
      !['GET', 'HEAD', 'OPTIONS'].includes(method)
      && options.includeCsrf !== false
      && this.csrfToken
    ) {
      headers.set('X-CSRF-TOKEN', this.csrfToken);
    }
    let body;
    if (options.body !== undefined) {
      headers.set('Content-Type', 'application/json');
      body = JSON.stringify(options.body);
    }
    let response;
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        method,
        headers,
        body,
        signal: AbortSignal.timeout(HTTP_TIMEOUT_MS),
      });
    } catch {
      throw new Error(`${label} could not reach the loopback backend`);
    }
    const parsed = await parseResponse(response);
    const expected = Array.isArray(options.expectedStatus)
      ? options.expectedStatus
      : [options.expectedStatus ?? 200];
    recordApiTrace(this.traceRows, {
      label,
      method,
      path,
      response,
      body: parsed,
      requestBody: options.sensitiveRequest ? undefined : options.body,
    });
    assert(
      expected.includes(response.status),
      `${label} returned ${response.status}${parsed?.code ? ` ${parsed.code}` : ''}; expected ${expected.join('/')}`,
    );
    const setCookie = response.headers.get('set-cookie');
    if (setCookie) this.cookie = setCookie.split(';', 1)[0];
    const receivedCsrf = response.headers.get('x-csrf-token');
    if (receivedCsrf) this.csrfToken = receivedCsrf;
    return {
      status: response.status,
      body: parsed,
      etag: response.headers.get('etag'),
      correlationId: response.headers.get('x-correlation-id')
        ?? (typeof parsed === 'object' && parsed ? parsed.correlationId : undefined),
    };
  }
}

export async function provisionRolePrincipals({
  backendUrl,
  loginEnvironment,
  context,
  traceRows,
}) {
  const admin = new ApiSession(backendUrl, traceRows);
  const adminLogin = await admin.login(
    loginEnvironment.SHENZHOUHR_LOGIN_USERNAME,
    loginEnvironment.SHENZHOUHR_LOGIN_PASSWORD,
    'bootstrap-admin-login',
  );
  assert(
    adminLogin.body && adminLogin.body.firstPasswordChangeRequired === false,
    'bootstrap admin must have completed first password change',
  );
  const rolesResponse = await admin.request('bootstrap-role-catalog', '/api/v1/access/roles');
  assert(Array.isArray(rolesResponse.body), 'role catalog response must be an array');
  const roles = new Map(rolesResponse.body.map((role) => [
    role.roleCode ?? role.code,
    {
      roleId: role.roleId,
      roleCode: role.roleCode ?? role.code,
      roleName: role.roleName ?? role.name,
      capabilities: [...role.capabilities].sort(),
    },
  ]));
  const requiredRoles = ['SYSTEM_ADMIN', 'HR_ADMIN', 'AUDITOR'];
  for (const roleCode of requiredRoles) {
    assert(roles.has(roleCode), `required V2 role is missing: ${roleCode}`);
  }
  assertManagerCapabilities(roles.get('SYSTEM_ADMIN'));
  assertManagerCapabilities(roles.get('HR_ADMIN'));
  assertAuditorCapabilities(roles.get('AUDITOR'));

  const principals = [];
  for (const roleCode of requiredRoles) {
    const role = roles.get(roleCode);
    const secrets = derivedPrincipalSecrets(
      loginEnvironment.SHENZHOUHR_LOGIN_PASSWORD,
      context,
      roleCode,
    );
    const accountSearch = await admin.request(
      `find-${roleCode.toLowerCase()}-account`,
      `/api/v1/access/accounts?query=${encodeURIComponent(secrets.username)}&page=0&size=20`,
    );
    const matches = accountSearch.body.items.filter(
      (account) => account.username === secrets.username,
    );
    assert(matches.length <= 1, `synthetic ${roleCode} username is not unique`);
    let account;
    let created = false;
    if (matches.length === 0) {
      account = (await admin.request(
        `create-${roleCode.toLowerCase()}-account`,
        '/api/v1/access/accounts',
        {
          method: 'POST',
          body: {
            username: secrets.username,
            displayName: `W3 验收 ${roleCode}`,
            temporaryPassword: secrets.temporaryPassword,
            roleAssignments: [{
              roleId: role.roleId,
              scopeType: 'COMPANY',
              scopeResourceId: COMPANY_ID,
              validFrom: '2000-01-01T00:00:00Z',
              validTo: null,
            }],
          },
          expectedStatus: 201,
          sensitiveRequest: true,
        },
      )).body;
      created = true;
    } else {
      account = (await admin.request(
        `get-${roleCode.toLowerCase()}-account`,
        `/api/v1/access/accounts/${encodeURIComponent(matches[0].accountId)}`,
      )).body;
    }
    assert(account.status === 'ACTIVE', `synthetic ${roleCode} account must be ACTIVE`);
    const assignedRoleCodes = account.roles.map((assignment) => assignment.roleCode).sort();
    if (
      assignedRoleCodes.length !== 1
      || assignedRoleCodes[0] !== roleCode
      || account.roles[0].scopeType !== 'COMPANY'
      || account.roles[0].scopeResourceId !== COMPANY_ID
    ) {
      account = (await admin.request(
        `repair-${roleCode.toLowerCase()}-role-assignment`,
        `/api/v1/access/accounts/${encodeURIComponent(account.accountId)}/role-assignments`,
        {
          method: 'PUT',
          body: {
            assignments: [{
              roleId: role.roleId,
              scopeType: 'COMPANY',
              scopeResourceId: COMPANY_ID,
              validFrom: '2000-01-01T00:00:00Z',
              validTo: null,
            }],
            reason: `W3 ${context.runId} 本地验收角色修复`,
            expectedVersion: account.rowVersion,
          },
        },
      )).body;
    }

    const roleSession = new ApiSession(backendUrl, traceRows);
    let login = await roleSession.login(
      secrets.username,
      account.firstPasswordChangeRequired
        ? secrets.temporaryPassword
        : secrets.password,
      `${roleCode.toLowerCase()}-login`,
      [200],
    );
    if (login.body.firstPasswordChangeRequired) {
      await roleSession.request(
        `${roleCode.toLowerCase()}-first-password-change`,
        '/api/v1/auth/password/first-change',
        {
          method: 'POST',
          body: {
            currentPassword: secrets.temporaryPassword,
            newPassword: secrets.password,
          },
          expectedStatus: 204,
          sensitiveRequest: true,
        },
      );
      const permanentSession = new ApiSession(backendUrl, traceRows);
      login = await permanentSession.login(
        secrets.username,
        secrets.password,
        `${roleCode.toLowerCase()}-permanent-login`,
      );
    }
    assert(login.body.firstPasswordChangeRequired === false, `${roleCode} principal is not browser-ready`);
    assert(login.body.username === secrets.username, `${roleCode} session username mismatch`);
    const current = new ApiSession(backendUrl, traceRows);
    const capabilityLogin = await current.login(
      secrets.username,
      secrets.password,
      `${roleCode.toLowerCase()}-capability-login`,
    );
    const capabilities = await current.request(
      `${roleCode.toLowerCase()}-capabilities`,
      '/api/v1/me/capabilities',
    );
    assert(
      JSON.stringify([...capabilityLogin.body.capabilities].sort())
        === JSON.stringify([...capabilities.body.capabilities].sort()),
      `${roleCode} session/capability exports disagree`,
    );
    validatePrincipalCapabilities(roleCode, capabilities.body);
    principals.push({
      roleCode,
      roleId: role.roleId,
      roleName: role.roleName,
      accountId: account.accountId,
      username: secrets.username,
      displayName: login.body.displayName,
      created,
      capabilities: [...capabilities.body.capabilities].sort(),
      menu: capabilities.body.menu,
      password: secrets.password,
    });
  }
  return principals;
}

export function derivedPrincipalSecrets(adminPassword, context, roleCode) {
  assert(typeof adminPassword === 'string' && adminPassword.length >= 1, 'admin password is missing');
  assert(['SYSTEM_ADMIN', 'HR_ADMIN', 'AUDITOR'].includes(roleCode), 'unsupported synthetic role');
  const binding = `${context.runId}|${context.sourceTreeHash}|${context.databaseIdentity}|${roleCode}`;
  const usernameDigest = createHmac('sha256', adminPassword)
    .update(`username|${binding}`)
    .digest('hex')
    .slice(0, 16);
  const passwordDigest = createHmac('sha256', adminPassword)
    .update(`password|${binding}`)
    .digest('base64url');
  const temporaryDigest = createHmac('sha256', adminPassword)
    .update(`temporary|${binding}`)
    .digest('base64url');
  return {
    username: `w3_${roleCode.toLowerCase()}_${usernameDigest}`,
    password: `W3!aA9-${passwordDigest.slice(0, 36)}`,
    temporaryPassword: `W3!tT9-${temporaryDigest.slice(0, 36)}`,
  };
}

export function publicPrincipal(principal) {
  const {
    password: _password,
    temporaryPassword: _temporaryPassword,
    ...safe
  } = principal;
  return safe;
}

export function validatePrincipalCapabilities(roleCode, view) {
  assert(Array.isArray(view.capabilities), `${roleCode} capabilities must be an array`);
  assert(Array.isArray(view.menu) && view.menu.length > 0, `${roleCode} authorized menu must not be empty`);
  assert(new Set(view.capabilities).size === view.capabilities.length, `${roleCode} capabilities contain duplicates`);
  assert(new Set(view.menu.map((item) => item.key)).size === view.menu.length, `${roleCode} menu contains duplicate keys`);
  const role = { roleCode, capabilities: view.capabilities };
  if (roleCode === 'AUDITOR') assertAuditorCapabilities(role);
  else assertManagerCapabilities(role);
  const expectedW3Menu = new Map([
    ['attendance-groups', '/rules/attendance-groups'],
    ['attendance-shifts', '/rules/shifts'],
    ['attendance-calendars', '/rules/calendars'],
    ['attendance-policies', '/rules/attendance-policy'],
  ]);
  for (const [key, path] of expectedW3Menu) {
    const item = view.menu.find((candidate) => candidate.key === key);
    assert(item?.path === path && item.label, `${roleCode} W3 menu item mismatch: ${key}`);
  }
  assert(
    !view.menu.some((item) => /payroll|payslip/i.test(`${item.key} ${item.label} ${item.path}`)),
    `${roleCode} menu exposes a forbidden payroll surface`,
  );
}

export function assertManagerCapabilities(role) {
  const required = [
    'ATTENDANCE_SETUP:READ',
    'ATTENDANCE_SETUP:MANAGE_GROUP',
    'ATTENDANCE_SETUP:ASSIGN',
    'ATTENDANCE_SETUP:MANAGE_SHIFT',
    'ATTENDANCE_SETUP:MANAGE_CALENDAR',
    'ATTENDANCE_SETUP:MANAGE_POLICY',
  ];
  for (const capability of required) {
    assert(role.capabilities.includes(capability), `${role.roleCode} lacks ${capability}`);
  }
}

export function assertAuditorCapabilities(role) {
  const attendanceCapabilities = role.capabilities
    .filter((capability) => capability.startsWith('ATTENDANCE_SETUP:'))
    .sort();
  assert(
    JSON.stringify(attendanceCapabilities) === JSON.stringify(['ATTENDANCE_SETUP:READ']),
    'AUDITOR attendance capability set must be read-only',
  );
}

export function validateLoopbackUrl(value, label) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${label} is invalid`);
  }
  assert(url.protocol === 'http:', `${label} must use local HTTP`);
  assert(['127.0.0.1', 'localhost'].includes(url.hostname), `${label} must use a loopback host`);
  assert(!url.username && !url.password, `${label} must not contain credentials`);
  assert(url.pathname === '/' && !url.search && !url.hash, `${label} must be an origin only`);
  return url;
}

export function prepareAtomicOutput(context, requestedOutput, expectedLeafSlug) {
  assert(isAbsolute(requestedOutput), '--output-dir must be absolute');
  const finalPath = resolve(requestedOutput);
  assert(
    finalPath.startsWith(`${context.runRoot}${sep}`),
    'output directory must be inside the current run root',
  );
  const relativePath = relative(context.runRoot, finalPath).split(sep).join('/');
  assert(
    relativePath === `leaves/${expectedLeafSlug}/raw`,
    `output directory must be exactly leaves/${expectedLeafSlug}/raw`,
  );
  assert(!pathExists(finalPath), 'refusing to overwrite an existing evidence output directory');
  const parent = dirname(finalPath);
  mkdirSync(parent, { recursive: true });
  assertNoSymlinkPath(context.runRoot, parent);
  const temporaryPath = mkdtempSync(join(parent, '.runtime-tmp-'));
  return {
    finalPath,
    temporaryPath,
    commit() {
      assert(!pathExists(finalPath), 'evidence output appeared during execution');
      renameSync(temporaryPath, finalPath);
    },
    cleanup() {
      if (pathExists(temporaryPath)) {
        rmSync(temporaryPath, { recursive: true, force: true });
      }
    },
  };
}

export function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, { flag: 'wx' });
}

export function writeText(path, value) {
  writeFileSync(path, value, { flag: 'wx' });
}

export function writeTsv(path, columns, rows) {
  const escape = (value) => String(value ?? '')
    .replaceAll('\t', '\\t')
    .replaceAll('\r', '\\r')
    .replaceAll('\n', '\\n');
  const lines = [
    columns.join('\t'),
    ...rows.map((row) => columns.map((column) => escape(row[column])).join('\t')),
  ];
  writeText(path, `${lines.join('\n')}\n`);
}

export function recordApiTrace(rows, { label, method, path, response, body, requestBody }) {
  rows.push({
    label,
    method,
    path,
    requestBodySha256: requestBody === undefined
      ? null
      : sha256Text(JSON.stringify(requestBody)),
    status: response.status,
    code: body && typeof body === 'object' ? body.code ?? null : null,
    correlationId: response.headers.get('x-correlation-id')
      ?? (body && typeof body === 'object' ? body.correlationId ?? null : null),
    etag: response.headers.get('etag'),
    cacheControl: response.headers.get('cache-control'),
  });
}

export function assertCorrelatedApiTrace(traceRows, { allowLoginWithoutCorrelation = false } = {}) {
  assert(traceRows.length > 0, 'HTTP trace must not be empty');
  for (const row of traceRows) {
    if (
      allowLoginWithoutCorrelation
      && row.path === '/api/v1/auth/login'
      && !row.correlationId
    ) {
      continue;
    }
    assert(row.correlationId, `API response lacks correlation ID: ${row.label}`);
  }
}

export function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

export function sha256Text(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

export function inventoryFiles(root, relativePaths) {
  return relativePaths.map((relativePath) => {
    const path = join(root, relativePath);
    assertRegularFileNoSymlink(path, `evidence artifact ${relativePath}`);
    const metadata = statSync(path);
    return {
      path: relativePath.split(sep).join('/'),
      sizeBytes: metadata.size,
      sha256: sha256File(path),
    };
  });
}

export function safeSubprocessEnvironment(extra = {}) {
  const allowed = [
    'PATH',
    'HOME',
    'TMPDIR',
    'LANG',
    'LC_ALL',
    'TZ',
    'SYSTEMROOT',
    'WINDIR',
  ];
  const environment = {};
  for (const key of allowed) {
    if (process.env[key]) environment[key] = process.env[key];
  }
  return {
    ...environment,
    LC_ALL: 'C',
    LANG: 'C',
    TZ: 'UTC',
    ...extra,
  };
}

export function assertRegularFileNoSymlink(path, label) {
  const metadata = lstatSync(path);
  assert(metadata.isFile() && !metadata.isSymbolicLink(), `${label} must be a regular non-symbolic file`);
}

export function assertRegularExecutable(path, label) {
  assertRegularFileNoSymlink(path, label);
  assert((statSync(path).mode & 0o111) !== 0, `${label} must be executable`);
}

export function assertNoSymlinkPath(root, target) {
  const resolvedRoot = realpathSync(root);
  let current = resolvedRoot;
  const parts = relative(resolvedRoot, resolve(target)).split(sep).filter(Boolean);
  assert(!parts.includes('..'), 'path escapes its allowed root');
  for (const part of parts) {
    current = join(current, part);
    if (pathExists(current)) {
      assert(!lstatSync(current).isSymbolicLink(), `path traverses a symbolic link: ${current}`);
    }
  }
}

export function assertExactKeys(value, keys, label) {
  assert(value && typeof value === 'object' && !Array.isArray(value), `${label} must be an object`);
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  assert(JSON.stringify(actual) === JSON.stringify(expected), `${label} key set mismatch`);
}

export function readJson(path, label) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    throw new Error(`${label} is not valid JSON`);
  }
}

export function pathExists(path) {
  try {
    lstatSync(path);
    return true;
  } catch (error) {
    if (error?.code === 'ENOENT') return false;
    throw error;
  }
}

export function randomRunSuffix() {
  return randomBytes(5).toString('hex');
}

export function utcNow() {
  return new Date().toISOString();
}

export function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function parseResponse(response) {
  if (response.status === 204) return null;
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('json')) return response.text();
  try {
    return await response.json();
  } catch {
    throw new Error(`backend returned malformed JSON with status ${response.status}`);
  }
}
