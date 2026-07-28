#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  chmodSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import {
  NORMAL_BROWSER_MARKER,
  RUNTIME_ENV_ALLOWED_KEYS,
  RUNTIME_PROVENANCE_PREFIX,
  W2_CURRENT_SMOKE_MARKER,
  assertRuntimeProvenanceStable,
  canonicalJson,
  databaseIdentityFor,
  derivedPrincipalSecrets,
  parseNamedArguments,
  parseRuntimeProvenance,
  publicPrincipal,
  readPrivateEnvironment,
  runtimeProvenanceLine,
  sha256Text,
  validatePrincipalCapabilities,
} from './wave3-runtime-common.mjs';
import {
  AgentBrowserRunner,
  ROLES,
  ROUTES,
  VIEWPORTS,
  expectedMatrixKeys,
  normalizeAxeResult,
  normalizeBrowserRequests,
  validateExactMatrix,
  viewportLabel,
} from './verify-wave3-normal-browser.mjs';

const MENU = [
  { key: 'attendance-groups', label: '考勤组与人员归属', path: '/rules/attendance-groups' },
  { key: 'attendance-shifts', label: '班次版本', path: '/rules/shifts' },
  { key: 'attendance-calendars', label: '工作日历', path: '/rules/calendars' },
  { key: 'attendance-policies', label: '考勤基础策略', path: '/rules/attendance-policy' },
];
const MANAGER_CAPABILITIES = [
  'ATTENDANCE_SETUP:READ',
  'ATTENDANCE_SETUP:MANAGE_GROUP',
  'ATTENDANCE_SETUP:ASSIGN',
  'ATTENDANCE_SETUP:MANAGE_SHIFT',
  'ATTENDANCE_SETUP:MANAGE_CALENDAR',
  'ATTENDANCE_SETUP:MANAGE_POLICY',
];

test('fixed role, viewport and route matrix is exact', () => {
  assert.deepEqual(ROLES, ['SYSTEM_ADMIN', 'HR_ADMIN', 'AUDITOR']);
  assert.deepEqual(VIEWPORTS.map(viewportLabel), [
    '390x844',
    '768x1024',
    '1024x768',
    '1366x768',
    '1440x900',
    '1920x1080',
  ]);
  assert.deepEqual(ROUTES.map((route) => route.path), [
    '/',
    '/login',
    '/rules',
    '/rules/templates',
    '/rules/attendance-groups',
    '/rules/shifts',
    '/rules/calendars',
    '/rules/attendance-policy',
    '/w3-acceptance/unknown-route',
  ]);
  assert.equal(expectedMatrixKeys().length, 162);
  assert.equal(new Set(expectedMatrixKeys()).size, 162);
});

test('exact matrix validator rejects missing, duplicate, reordered and failed rows', () => {
  const matrix = expectedMatrixKeys().map((rowKey) => ({ rowKey, verdict: 'PASS' }));
  assert.doesNotThrow(() => validateExactMatrix(matrix));
  assert.throws(() => validateExactMatrix(matrix.slice(1)), /exactly 162/);
  assert.throws(
    () => validateExactMatrix([...matrix.slice(0, -1), matrix[0]]),
    /duplicate/,
  );
  assert.throws(
    () => validateExactMatrix([matrix[1], matrix[0], ...matrix.slice(2)]),
    /order\/set/,
  );
  assert.throws(
    () => validateExactMatrix(matrix.map((row, index) => (
      index === 0 ? { ...row, verdict: 'FAIL' } : row
    ))),
    /non-PASS/,
  );
});

test('run-bound synthetic secrets are deterministic, separated and redacted', () => {
  const context = {
    runId: 'w3-test-20260727',
    sourceTreeHash: 'a'.repeat(64),
    databaseIdentity: 'mysql8410:a1b2c3d4-1234-4abc-8def-1234567890ab:shenzhou_hr_test',
  };
  const system = derivedPrincipalSecrets('bootstrap-admin-secret', context, 'SYSTEM_ADMIN');
  assert.deepEqual(
    system,
    derivedPrincipalSecrets('bootstrap-admin-secret', context, 'SYSTEM_ADMIN'),
  );
  const auditor = derivedPrincipalSecrets('bootstrap-admin-secret', context, 'AUDITOR');
  assert.notEqual(system.username, auditor.username);
  assert.notEqual(system.password, auditor.password);
  assert.equal(JSON.stringify(system).includes('bootstrap-admin-secret'), false);
  assert.deepEqual(
    publicPrincipal({ username: system.username, password: system.password, temporaryPassword: system.temporaryPassword }),
    { username: system.username },
  );
});

test('manager and AUDITOR capabilities fail closed', () => {
  assert.doesNotThrow(() => validatePrincipalCapabilities(
    'SYSTEM_ADMIN',
    { capabilities: MANAGER_CAPABILITIES, menu: MENU },
  ));
  assert.doesNotThrow(() => validatePrincipalCapabilities(
    'HR_ADMIN',
    { capabilities: MANAGER_CAPABILITIES, menu: MENU },
  ));
  assert.doesNotThrow(() => validatePrincipalCapabilities(
    'AUDITOR',
    { capabilities: ['ATTENDANCE_SETUP:READ'], menu: MENU },
  ));
  assert.throws(
    () => validatePrincipalCapabilities(
      'AUDITOR',
      { capabilities: ['ATTENDANCE_SETUP:READ', 'ATTENDANCE_SETUP:MANAGE_POLICY'], menu: MENU },
    ),
    /read-only/,
  );
  assert.throws(
    () => validatePrincipalCapabilities(
      'AUDITOR',
      {
        capabilities: ['ATTENDANCE_SETUP:READ'],
        menu: [...MENU, { key: 'payroll', label: 'Payroll', path: '/payroll' }],
      },
    ),
    /payroll/,
  );
});

test('database identity is exact and lower-cased', () => {
  assert.equal(
    databaseIdentityFor('A1B2C3D4-1234-4ABC-8DEF-1234567890AB'),
    'mysql8410:a1b2c3d4-1234-4abc-8def-1234567890ab:shenzhou_hr_test',
  );
  assert.throws(() => databaseIdentityFor('not-a-uuid'), /UUID/);
});

test('runtime environment accepts the provisioner database-identity binding', () => {
  assert(RUNTIME_ENV_ALLOWED_KEYS.includes('SHENZHOUHR_W3_DB_IDENTITY'));
  assert.equal(
    new Set(RUNTIME_ENV_ALLOWED_KEYS).size,
    RUNTIME_ENV_ALLOWED_KEYS.length,
  );
});

test('named argument parsing rejects ambiguity', () => {
  assert.deepEqual(
    Object.fromEntries(parseNamedArguments(['--alpha', 'one', '--flag'], ['alpha', 'flag'], ['flag'])),
    { alpha: 'one', flag: true },
  );
  assert.throws(
    () => parseNamedArguments(['--alpha', 'one', '--alpha', 'two'], ['alpha']),
    /duplicate/,
  );
  assert.throws(() => parseNamedArguments(['--unknown', 'one'], ['alpha']), /unsupported/);
  assert.throws(() => parseNamedArguments(['--alpha'], ['alpha']), /requires a value/);
});

test('private environment parser enforces external regular mode-0600 files', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'w3-runtime-env-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const privatePath = join(root, 'private.env');
  writeFileSync(privatePath, 'TEST_SECRET=opaque=value\n', { mode: 0o600 });
  chmodSync(privatePath, 0o600);
  assert.deepEqual(
    readPrivateEnvironment(privatePath, ['TEST_SECRET'], ['TEST_SECRET']),
    { TEST_SECRET: 'opaque=value' },
  );
  chmodSync(privatePath, 0o644);
  assert.throws(
    () => readPrivateEnvironment(privatePath, ['TEST_SECRET'], ['TEST_SECRET']),
    /0600/,
  );
  chmodSync(privatePath, 0o600);
  const linkedPath = join(root, 'linked.env');
  symlinkSync(privatePath, linkedPath);
  assert.throws(
    () => readPrivateEnvironment(linkedPath, ['TEST_SECRET'], ['TEST_SECRET']),
    /symbolic links/,
  );
});

test('Agent Browser result normalization preserves required evidence', () => {
  assert.deepEqual(
    normalizeBrowserRequests({
      success: true,
      data: {
        requests: [{
          request: { method: 'GET', url: 'http://127.0.0.1:8080/api/v1/example' },
          response: { status: 200, headers: { 'x-correlation-id': 'cid-1' } },
          type: 'fetch',
        }],
      },
    }),
    [{
      method: 'GET',
      url: 'http://127.0.0.1:8080/api/v1/example',
      status: 200,
      resourceType: 'fetch',
      responseHeaders: { 'x-correlation-id': 'cid-1' },
    }],
  );
  const axe = normalizeAxeResult({
    success: true,
    data: {
      violations: [{ id: 'fixture', impact: 'minor', nodes: [{ html: 'x'.repeat(600) }] }],
      incomplete: [],
    },
  });
  assert.equal(axe.violations[0].nodes[0].html.length, 500);
  assert.throws(
    () => normalizeAxeResult({ success: false, error: { message: 'failure' } }),
    /failure/,
  );
});

test('Agent Browser runner puts global flags before command and omits CLI timeout', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'w3-agent-browser-runner-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const logPath = join(root, 'argv.jsonl');
  const browserPath = join(root, 'fake-agent-browser');
  const chromePath = join(root, 'fake-chrome');
  writeFileSync(
    browserPath,
    [
      '#!/usr/bin/env node',
      "import { appendFileSync } from 'node:fs';",
      `appendFileSync(${JSON.stringify(logPath)}, JSON.stringify(process.argv.slice(2)) + '\\n');`,
      "process.stdout.write(JSON.stringify({ success: true, data: { result: true }, error: null }));",
      '',
    ].join('\n'),
    { mode: 0o700 },
  );
  writeFileSync(chromePath, '#!/bin/sh\nexit 0\n', { mode: 0o700 });
  chmodSync(browserPath, 0o700);
  chmodSync(chromePath, 0o700);
  const runner = new AgentBrowserRunner({
    binary: browserPath,
    chrome: chromePath,
    namespace: 'w3-test-namespace',
    allowedDomains: ['127.0.0.1'],
  });
  runner.command('w3-test-session', ['set', 'viewport', '390', '844']);
  runner.sensitiveCommand('w3-test-session', ['fill', '#password', 'opaque']);
  const calls = readFileSync(logPath, 'utf8').trim().split('\n').map(JSON.parse);
  assert.deepEqual(calls[0], [
    '--session',
    'w3-test-session',
    '--namespace',
    'w3-test-namespace',
    '--executable-path',
    realpathSync(chromePath),
    'set',
    'viewport',
    '390',
    '844',
    '--json',
  ]);
  assert.equal(calls.flat().includes('--timeout'), false);
});

test('fixed PASS markers do not drift', () => {
  assert.equal(W2_CURRENT_SMOKE_MARKER, 'W3_W2_CURRENT_SMOKE=PASS');
  assert.equal(
    NORMAL_BROWSER_MARKER,
    'W3_NORMAL_BROWSER_MATRIX=PASS roles=SYSTEM_ADMIN,HR_ADMIN,AUDITOR viewports=390x844,768x1024,1024x768,1366x768,1440x900,1920x1080 transport=normal db=real',
  );
});

test('runtime provenance parser and before/after seal reject mutants', () => {
  const context = {
    runId: 'w3-runtime-provenance-test',
    sourceTreeHash: 'a'.repeat(64),
    databaseIdentity: (
      'mysql8410:a1b2c3d4-1234-4abc-8def-1234567890ab:'
      + 'shenzhou_hr_test'
    ),
  };
  const binding = {
    backend: {
      listenerPid: 42,
      processBinding: {
        executableSha256: 'b'.repeat(64),
      },
    },
    verdict: 'PASS',
  };
  const document = {
    schemaVersion: 1,
    nodeType: 'wave3-runtime-provenance',
    mode: 'w2',
    runId: context.runId,
    sourceTreeHash: context.sourceTreeHash,
    databaseIdentity: context.databaseIdentity,
    binding,
    bindingSha256: sha256Text(canonicalJson(binding)),
    verdict: 'PASS',
  };
  const parsed = parseRuntimeProvenance(JSON.stringify(document), {
    mode: 'w2',
    context,
  });
  assert.deepEqual(assertRuntimeProvenanceStable(parsed, parsed), parsed);
  assert.equal(
    runtimeProvenanceLine(parsed),
    `${RUNTIME_PROVENANCE_PREFIX}${canonicalJson(parsed)}`,
  );
  for (const mutant of [
    { ...document, schemaVersion: true },
    { ...document, mode: 'demo' },
    { ...document, bindingSha256: '0'.repeat(64) },
    {
      ...document,
      binding: {
        ...binding,
        _privateArgv: ['--secret'],
      },
    },
  ]) {
    assert.throws(
      () => parseRuntimeProvenance(JSON.stringify(mutant), {
        mode: 'w2',
        context,
      }),
      /provenance/,
    );
  }
  const changedBinding = {
    ...binding,
    backend: {
      ...binding.backend,
      listenerPid: 43,
    },
  };
  const changed = {
    ...document,
    binding: changedBinding,
    bindingSha256: sha256Text(canonicalJson(changedBinding)),
  };
  assert.throws(
    () => assertRuntimeProvenanceStable(document, changed),
    /before\/after/,
  );
});
