#!/usr/bin/env node

import {
  mkdtempSync,
  readFileSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { AgentBrowserRunner } from './verify-wave3-normal-browser.mjs';
import {
  DEMO_ISOLATION_MARKER,
  EVIDENCE_ID_DEMO_ISOLATION,
  assert,
  assertDatabaseBinding,
  assertFrozenSource,
  assertRuntimeProvenanceStable,
  captureRuntimeProvenance,
  contextLine,
  createDatabaseBinding,
  createMysqlDefaultsFile,
  loadRunContext,
  mysqlQuery,
  parseNamedArguments,
  prepareAtomicOutput,
  readRuntimeEnvironment,
  requireArguments,
  runtimeProvenanceLine,
  utcNow,
  validateLoopbackUrl,
  writeJson,
  writeText,
} from './wave3-runtime-common.mjs';

export const DEMO_ROUTES = [
  '/',
  '/rules',
  '/rules/templates',
  '/access/accounts',
  '/access/roles',
  '/access/audit',
  '/people/import',
  '/people/organization',
  '/people/employees',
  '/rules/attendance-groups',
  '/rules/shifts',
  '/rules/calendars',
  '/rules/attendance-policy',
];

const DEFAULT_AGENT_BROWSER = '/Users/huzhijin/.local/bin/agent-browser';
const DEFAULT_CHROME =
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const USAGE = `Usage:
  node scripts/qa/verify-wave3-demo-isolation.mjs execute \\
    --run-context ABS_RUN_CONTEXT \\
    --runtime-env ABS_PRIVATE_ENV \\
    --frontend-url http://127.0.0.1:4175 \\
    --output-dir ABS_RUN_ROOT/leaves/demo-isolation/raw \\
    [--agent-browser-bin ${DEFAULT_AGENT_BROWSER}] \\
    [--chrome-bin "${DEFAULT_CHROME}"]

  node scripts/qa/verify-wave3-demo-isolation.mjs self-test

The frontend URL must serve the frozen dist/demo build. The gate records
ordinary static asset traffic but rejects every root /api request, every
cross-origin HTTP(S) request and every MySQL connection beyond its own two
counter samples.
`;

export async function executeDemoIsolation(options) {
  const context = loadRunContext(
    options.runContext,
    EVIDENCE_ID_DEMO_ISOLATION,
  );
  const runtimeEnvironment = readRuntimeEnvironment(options.runtimeEnv);
  const frontendUrl = validateLoopbackUrl(
    options.frontendUrl,
    'demo frontend URL',
  ).origin;
  assertFrozenSource(context);
  const database = createDatabaseBinding(runtimeEnvironment);
  assertDatabaseBinding(context, database);
  const provenanceBefore = captureRuntimeProvenance({
    mode: 'demo',
    context,
    runtimeEnv: options.runtimeEnv,
    frontendUrl,
  });
  const output = prepareAtomicOutput(
    context,
    options.outputDir,
    'demo-isolation',
  );
  const browser = new AgentBrowserRunner({
    binary: options.agentBrowserBin ?? DEFAULT_AGENT_BROWSER,
    chrome: options.chromeBin ?? DEFAULT_CHROME,
    namespace: `w3-demo-${context.runId}`,
    allowedDomains: [new URL(frontendUrl).hostname],
  });
  const session = `demo-${context.runId}`;
  const harPath = join(output.temporaryPath, 'demo-network.har');
  const startedAt = utcNow();
  let browserStarted = false;
  let before;
  try {
    before = readMysqlConnectionSnapshot(runtimeEnvironment);
    browser.command(session, ['network', 'har', 'start']);
    browserStarted = true;
    const routeResults = [];
    for (const route of DEMO_ROUTES) {
      browser.command(session, ['open', `${frontendUrl}${route}`]);
      browser.command(session, ['wait', '--load', 'networkidle']);
      const page = browser.evaluate(
        session,
        `({
          href: location.href,
          title: document.title,
          bodyText: document.body?.innerText?.slice(0, 5000) ?? ''
        })`,
      );
      assert(
        page
          && typeof page.href === 'string'
          && page.href.startsWith(frontendUrl)
          && page.title === '神州 HR 管理系统'
          && page.bodyText.trim().length > 0,
        `demo route did not render a valid application page: ${route}`,
      );
      assert(
        !/Internal Server Error|Failed to fetch|服务暂时不可用/.test(
          page.bodyText,
        ),
        `demo route exposed a runtime failure state: ${route}`,
      );
      routeResults.push({
        route,
        finalPath: new URL(page.href).pathname,
        title: page.title,
        rendered: true,
      });
    }
    browser.command(session, ['network', 'har', 'stop', harPath]);
    browser.command(session, ['close']);
    browserStarted = false;

    const after = readMysqlConnectionSnapshot(runtimeEnvironment);
    const mysqlConnections = adjustedMysqlConnectionDelta(before, after);
    const har = readHar(harPath);
    const requestClassification = classifyBusinessRequests(
      har.log.entries,
      frontendUrl,
    );
    assert(
      requestClassification.business.length === 0,
      `demo made business network requests: ${
        requestClassification.business
          .map((request) => `${request.method} ${request.url}`)
          .join(', ')
      }`,
    );
    assert(mysqlConnections === 0, 'demo created one or more MySQL connections');
    assert(
      requestClassification.static.length > 0,
      'demo HAR did not capture static runtime traffic',
    );
    assertFrozenSource(context);
    const runtimeProvenance = assertRuntimeProvenanceStable(
      provenanceBefore,
      captureRuntimeProvenance({
        mode: 'demo',
        context,
        runtimeEnv: options.runtimeEnv,
        frontendUrl,
      }),
    );

    const completedAt = utcNow();
    const delta = {
      schemaVersion: 1,
      nodeType: 'wave3-demo-database-connection-delta',
      evidenceId: EVIDENCE_ID_DEMO_ISOLATION,
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      verdict: 'PASS',
      marker: DEMO_ISOLATION_MARKER,
      counter: 'SHOW GLOBAL STATUS Connections',
      samplingConnectionsExcluded: 1,
      beforeConnections: before.connections,
      afterConnections: after.connections,
      observedProductConnections: mysqlConnections,
      beforeThreadsConnected: before.threadsConnected,
      afterThreadsConnected: after.threadsConnected,
      startedAt,
      completedAt,
    };
    writeJson(
      join(output.temporaryPath, 'demo-database-connection-delta.json'),
      delta,
    );
    writeText(
      join(output.temporaryPath, 'demo-isolation.log'),
      [
        contextLine(context, EVIDENCE_ID_DEMO_ISOLATION),
        `W3_DEMO_RUNTIME=PASS routes=${routeResults.length} staticRequests=${requestClassification.static.length} origin=${frontendUrl}`,
        runtimeProvenanceLine(runtimeProvenance),
        `W3_DEMO_ROUTE_RENDER=PASS routes=${routeResults.map((item) => item.route).join(',')}`,
        `W3_DEMO_NETWORK=PASS businessRequests=${requestClassification.business.length} crossOriginRequests=${requestClassification.crossOrigin.length} apiRequests=${requestClassification.api.length}`,
        `W3_DEMO_MYSQL=PASS adjustedConnections=${mysqlConnections} counterBefore=${before.connections} counterAfter=${after.connections} samplingConnectionsExcluded=1`,
        DEMO_ISOLATION_MARKER,
        '',
      ].join('\n'),
    );
    output.commit();
    assertFrozenSource(context);
    return {
      outputDir: output.finalPath,
      routeCount: routeResults.length,
      staticRequestCount: requestClassification.static.length,
      businessRequestCount: requestClassification.business.length,
      mysqlConnections,
      runtimeProvenance,
    };
  } catch (error) {
    if (browserStarted) {
      browser.tryCommand(session, ['network', 'har', 'stop', harPath]);
      browser.tryCommand(session, ['close']);
    }
    output.cleanup();
    throw error;
  }
}

export function classifyBusinessRequests(entries, frontendOrigin) {
  assert(Array.isArray(entries), 'HAR entries must be an array');
  const staticRequests = [];
  const apiRequests = [];
  const crossOriginRequests = [];
  const businessRequests = [];
  for (const entry of entries) {
    const method = entry?.request?.method;
    const rawUrl = entry?.request?.url;
    assert(
      typeof method === 'string' && typeof rawUrl === 'string',
      'HAR request row is malformed',
    );
    let url;
    try {
      url = new URL(rawUrl);
    } catch {
      throw new Error('HAR contains an invalid request URL');
    }
    if (!['http:', 'https:'].includes(url.protocol)) continue;
    const request = { method, url: rawUrl };
    if (url.origin !== frontendOrigin) {
      crossOriginRequests.push(request);
      businessRequests.push(request);
    } else if (/^\/api(?:\/|$)/.test(url.pathname)) {
      apiRequests.push(request);
      businessRequests.push(request);
    } else {
      staticRequests.push(request);
    }
  }
  return {
    static: staticRequests,
    api: apiRequests,
    crossOrigin: crossOriginRequests,
    business: businessRequests,
  };
}

export function adjustedMysqlConnectionDelta(before, after) {
  assert(
    Number.isSafeInteger(before.connections)
      && Number.isSafeInteger(after.connections)
      && after.connections >= before.connections + 1,
    'MySQL connection counters are invalid',
  );
  return after.connections - before.connections - 1;
}

export function readMysqlConnectionSnapshot(runtimeEnvironment) {
  const temporaryRoot = mkdtempSync(
    join(tmpdir(), 'shenzhouhr-w3-demo-mysql-'),
  );
  try {
    const defaultsPath = createMysqlDefaultsFile(
      temporaryRoot,
      'test-app.cnf',
      'shenzhou_hr_test_app',
      runtimeEnvironment.SHENZHOUHR_TEST_DB_PASSWORD,
    );
    const rows = mysqlQuery(
      runtimeEnvironment,
      defaultsPath,
      "SHOW GLOBAL STATUS WHERE Variable_name IN ('Connections','Threads_connected');",
    ).trim().split(/\r?\n/).filter(Boolean);
    const values = new Map(rows.map((row) => {
      const fields = row.split('\t');
      assert(fields.length === 2, 'MySQL connection status row is malformed');
      return [fields[0], Number(fields[1])];
    }));
    const connections = values.get('Connections');
    const threadsConnected = values.get('Threads_connected');
    assert(
      Number.isSafeInteger(connections)
        && Number.isSafeInteger(threadsConnected)
        && connections >= 1
        && threadsConnected >= 1,
      'MySQL connection status values are invalid',
    );
    return { connections, threadsConnected };
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

export function staticSelfTest() {
  assert(
    DEMO_ROUTES.length === 13
      && new Set(DEMO_ROUTES).size === DEMO_ROUTES.length,
    'demo route set drifted or contains duplicates',
  );
  const frontendOrigin = 'http://127.0.0.1:4175';
  const classified = classifyBusinessRequests([
    request('GET', `${frontendOrigin}/assets/app.js`),
    request('GET', `${frontendOrigin}/src/shared/api/apiClient.ts`),
    request('GET', `${frontendOrigin}/api/v1/session`),
    request('POST', 'http://127.0.0.1:8080/api/v1/auth/login'),
  ], frontendOrigin);
  assert(
    classified.static.length === 2
      && classified.api.length === 1
      && classified.crossOrigin.length === 1
      && classified.business.length === 2,
    'demo business-request classifier drifted',
  );
  assert(
    adjustedMysqlConnectionDelta(
      { connections: 100 },
      { connections: 101 },
    ) === 0,
    'demo MySQL sampling adjustment drifted',
  );
  assert(
    DEMO_ISOLATION_MARKER
      === 'W3_DEMO_ISOLATION=PASS businessRequests=0 mysqlConnections=0',
    'demo isolation marker drifted',
  );
  return {
    marker: 'W3_DEMO_ISOLATION_HARNESS_SELF_TEST=PASS tests=4 routes=13',
  };
}

function readHar(path) {
  let document;
  try {
    document = JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    throw new Error('demo HAR is not valid JSON');
  }
  assert(
    document
      && typeof document === 'object'
      && document.log
      && Array.isArray(document.log.entries),
    'demo HAR has an invalid shape',
  );
  return document;
}

function request(method, url) {
  return { request: { method, url } };
}

async function main() {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'self-test') {
    assert(args.length === 0, 'self-test does not accept arguments');
    console.log(staticSelfTest().marker);
    return;
  }
  if (command === 'execute') {
    const values = parseNamedArguments(args, [
      'run-context',
      'runtime-env',
      'frontend-url',
      'output-dir',
      'agent-browser-bin',
      'chrome-bin',
    ]);
    requireArguments(values, [
      'run-context',
      'runtime-env',
      'frontend-url',
      'output-dir',
    ]);
    const result = await executeDemoIsolation({
      runContext: values.get('run-context'),
      runtimeEnv: values.get('runtime-env'),
      frontendUrl: values.get('frontend-url'),
      outputDir: values.get('output-dir'),
      agentBrowserBin: values.get('agent-browser-bin'),
      chromeBin: values.get('chrome-bin'),
    });
    console.log(
      `${DEMO_ISOLATION_MARKER} routes=${result.routeCount} staticRequests=${result.staticRequestCount} output=${result.outputDir}`,
    );
    return;
  }
  console.error(USAGE);
  process.exitCode = 2;
}

if (fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error) => {
    console.error(`[verify-wave3-demo-isolation] ERROR: ${error.message}`);
    process.exitCode = 1;
  });
}
