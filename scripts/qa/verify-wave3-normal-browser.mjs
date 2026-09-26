#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import {
  lstatSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  ApiSession,
  EVIDENCE_ID_NORMAL_BROWSER,
  COMPANY_ID,
  NORMAL_BROWSER_MARKER,
  assert,
  assertCorrelatedApiTrace,
  assertDatabaseBinding,
  assertFrozenSource,
  assertRuntimeProvenanceStable,
  captureRuntimeProvenance,
  contextLine,
  createDatabaseBinding,
  currentSourceTreeHash,
  inventoryFiles,
  loadRunContext,
  parseNamedArguments,
  prepareAtomicOutput,
  provisionRolePrincipals,
  publicPrincipal,
  readLoginEnvironment,
  readRuntimeEnvironment,
  requireArguments,
  runtimeProvenanceLine,
  safeSubprocessEnvironment,
  sha256File,
  utcNow,
  validateLoopbackUrl,
  writeJson,
  writeText,
  writeTsv,
} from './wave3-runtime-common.mjs';

export const ROLES = ['SYSTEM_ADMIN', 'HR_ADMIN', 'AUDITOR'];
export const VIEWPORTS = [
  { width: 390, height: 844 },
  { width: 768, height: 1024 },
  { width: 1024, height: 768 },
  { width: 1366, height: 768 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
];
export const ROUTES = [
  { key: 'root', path: '/', type: 'default-redirect' },
  { key: 'login', path: '/login', type: 'default-redirect' },
  { key: 'rules', path: '/rules', type: 'rules-landing' },
  { key: 'templates', path: '/rules/templates', type: 'policy-templates' },
  {
    key: 'attendance-groups',
    path: '/rules/attendance-groups',
    type: 'w3',
    heading: '考勤组与人员归属',
    breadcrumb: ['考勤设置', '考勤组与人员归属'],
    managerActions: ['新建地点', '新建考勤组'],
  },
  {
    key: 'shifts',
    path: '/rules/shifts',
    type: 'w3',
    heading: '班次版本',
    breadcrumb: ['考勤设置', '班次版本'],
    managerActions: ['新建班次模板'],
  },
  {
    key: 'calendars',
    path: '/rules/calendars',
    type: 'w3',
    heading: '工作日历',
    breadcrumb: ['考勤设置', '工作日历'],
    managerActions: ['新建年度日历'],
  },
  {
    key: 'attendance-policy',
    path: '/rules/attendance-policy',
    type: 'w3',
    heading: '考勤基础策略',
    breadcrumb: ['考勤设置', '考勤基础策略'],
    managerActions: ['新建策略绑定', '预览影响', '运行试算'],
  },
  {
    key: 'unknown',
    path: '/w3-acceptance/unknown-route',
    type: 'unknown',
  },
];

const W3_DIRECT_ROUTES = ROUTES.filter((route) => route.type === 'w3');
const AUDITOR_FORBIDDEN_BUTTONS = [
  '新建地点',
  '新建考勤组',
  '分配人员',
  '新建班次模板',
  '新建班次版本',
  '新建年度日历',
  '追加工作日历版本',
  '保存日期覆盖',
  '新建策略绑定',
  '预览影响',
  '运行试算',
  '校验版本',
  '发布版本',
  '回滚',
];
const DEFAULT_AGENT_BROWSER = '/Users/huzhijin/.local/bin/agent-browser';
const DEFAULT_CHROME =
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const USAGE = `Usage:
  node scripts/qa/verify-wave3-normal-browser.mjs execute \\
    --run-context ABS_RUN_CONTEXT \\
    --runtime-env ABS_PRIVATE_ENV \\
    --login-env ABS_PRIVATE_ENV \\
    --backend-url http://127.0.0.1:8080 \\
    --frontend-url http://127.0.0.1:5173 \\
    --output-dir ABS_RUN_ROOT/leaves/normal-browser/raw \\
    --provision-synthetic-accounts \\
    [--agent-browser-bin ${DEFAULT_AGENT_BROWSER}] \\
    [--chrome-bin "${DEFAULT_CHROME}"]

  node scripts/qa/verify-wave3-normal-browser.mjs self-test

The execute command is intentionally mutating only for three deterministic,
run-bound synthetic accounts in the exact local 8.4.10 dev database. It never
prints or writes their passwords.
`;

export async function executeNormalBrowser(options) {
  assert(options.provisionSyntheticAccounts, 'execute requires --provision-synthetic-accounts');
  const context = loadRunContext(options.runContext, EVIDENCE_ID_NORMAL_BROWSER);
  const runtimeEnvironment = readRuntimeEnvironment(options.runtimeEnv);
  const loginEnvironment = readLoginEnvironment(options.loginEnv);
  const backendUrl = validateLoopbackUrl(options.backendUrl, 'backend URL').origin;
  const frontendUrl = validateLoopbackUrl(options.frontendUrl, 'frontend URL').origin;
  assert(backendUrl !== frontendUrl, 'frontend and backend origins must be distinct in local normal mode');
  assertFrozenSource(context);
  const database = createDatabaseBinding(runtimeEnvironment);
  assertDatabaseBinding(context, database);
  const provenanceBefore = captureRuntimeProvenance({
    mode: 'normal',
    context,
    runtimeEnv: options.runtimeEnv,
    backendUrl,
    frontendUrl,
  });
  const output = prepareAtomicOutput(context, options.outputDir, 'normal-browser');
  const browser = new AgentBrowserRunner({
    binary: options.agentBrowserBin ?? DEFAULT_AGENT_BROWSER,
    chrome: options.chromeBin ?? DEFAULT_CHROME,
    namespace: `w3-${context.runId}`,
    allowedDomains: [...new Set([
      new URL(frontendUrl).hostname,
      new URL(backendUrl).hostname,
    ])],
  });
  const startedAt = utcNow();
  const apiTrace = [];
  const liveSessions = new Set();
  try {
    const version = browser.version();
    const principals = await provisionRolePrincipals({
      backendUrl,
      loginEnvironment,
      context,
      traceRows: apiTrace,
    });
    assert(
      JSON.stringify(principals.map((principal) => principal.roleCode))
        === JSON.stringify(ROLES),
      'provisioned role order/set differs from the fixed matrix',
    );
    const authorizationProbes = await probeRoleHttpBoundaries({
      backendUrl,
      principals,
      apiTrace,
      context,
    });
    assertCorrelatedApiTrace(apiTrace);

    const unauthenticated = await verifyUnauthenticatedRedirect({
      browser,
      frontendUrl,
      context,
      outputRoot: output.temporaryPath,
      liveSessions,
    });

    const matrix = [];
    const axe = [];
    const keyboard = [];
    const network = [];
    const screenshots = [];
    const traces = [];
    const browserDiagnostics = [];

    for (const principal of principals) {
      const roleCode = principal.roleCode;
      const session = sessionName(context.runId, roleCode);
      liveSessions.add(session);
      await browserLogin({
        browser,
        session,
        frontendUrl,
        principal,
      });
      browser.command(session, ['network', 'requests', '--clear']);
      const traceRelative = `traces/${roleCode.toLowerCase()}-trace.json`;
      mkdirSync(join(output.temporaryPath, 'traces'), { recursive: true });
      browser.command(session, ['trace', 'start']);

      for (const [viewportIndex, viewport] of VIEWPORTS.entries()) {
        browser.command(session, [
          'set',
          'viewport',
          String(viewport.width),
          String(viewport.height),
        ]);
        keyboard.push(await verifyKeyboard({
          browser,
          session,
          frontendUrl,
          principal,
          viewport,
        }));

        for (const route of ROUTES) {
          browser.command(session, ['network', 'requests', '--clear']);
          browser.command(session, ['open', `${frontendUrl}${route.path}`]);
          browser.command(session, ['wait', '700']);
          if (route.type === 'w3') {
            browser.tryCommand(session, [
              'wait',
              '--fn',
              "!document.querySelector('.async-state[aria-busy=\"true\"]')",
            ]);
          }
          const state = browser.evaluate(session, pageStateExpression());
          const requestResult = browser.command(session, [
            'network',
            'requests',
            '--type',
            'xhr,fetch',
          ]);
          const requests = normalizeBrowserRequests(requestResult);
          const safeRequests = requests.map(safeBrowserRequest);
          validateRouteState({ principal, route, viewport, state, requests });

          const rowKey = matrixKey(roleCode, viewport, route.key);
          const screenshot = shouldScreenshot(viewportIndex, viewport, route)
            ? captureScreenshot({
              browser,
              session,
              outputRoot: output.temporaryPath,
              roleCode,
              viewport,
              route,
            })
            : null;
          if (screenshot) screenshots.push({ rowKey, ...screenshot });

          let axeResult = null;
          if (route.type === 'w3') {
            axeResult = normalizeAxeResult(
              browser.command(session, [
                'a11y',
                '--tags',
                'wcag2a,wcag2aa,wcag21a,wcag21aa',
              ]),
            );
            const blocking = axeResult.violations.filter(
              (item) => ['critical', 'serious'].includes(item.impact),
            );
            assert(
              blocking.length === 0,
              `${rowKey} has critical/serious axe violations: ${blocking.map((item) => item.id).join(',')}`,
            );
            axe.push({
              rowKey,
              roleCode,
              viewport: viewportLabel(viewport),
              route: route.path,
              violationCount: axeResult.violations.length,
              incompleteCount: axeResult.incomplete.length,
              violations: axeResult.violations,
              incomplete: axeResult.incomplete,
            });
          }
          const apiRequests = safeRequests.filter((request) => request.api);
          matrix.push({
            rowKey,
            roleCode,
            viewport: viewportLabel(viewport),
            routeKey: route.key,
            requestedPath: route.path,
            actualPath: state.path,
            heading: state.heading,
            breadcrumbs: state.breadcrumbs,
            menuLabels: state.menuLabels,
            states: state.states,
            horizontalOverflow: state.horizontalOverflow,
            unlabeledControlCount: state.unlabeledControls.length,
            buttonTexts: state.buttonTexts,
            apiRequestCount: apiRequests.length,
            apiStatuses: [...new Set(apiRequests.map((request) => request.status))].sort(),
            correlationIds: [...new Set(
              apiRequests.map((request) => request.correlationId).filter(Boolean),
            )].sort(),
            axeCriticalSeriousViolationCount: axeResult
              ? axeResult.violations.filter(
                (item) => ['critical', 'serious'].includes(item.impact),
              ).length
              : null,
            screenshot: screenshot?.path ?? null,
            verdict: 'PASS',
          });
          network.push({
            rowKey,
            roleCode,
            viewport: viewportLabel(viewport),
            route: route.path,
            requests: safeRequests,
          });
        }
      }

      browser.command(session, [
        'trace',
        'stop',
        join(output.temporaryPath, traceRelative),
      ]);
      sanitizeTrace(join(output.temporaryPath, traceRelative));
      traces.push({
        roleCode,
        path: traceRelative,
        sha256: sha256File(join(output.temporaryPath, traceRelative)),
      });
      const errors = normalizeDiagnosticList(browser.command(session, ['errors']));
      const consoleMessages = normalizeDiagnosticList(browser.command(session, ['console']));
      assert(errors.length === 0, `${roleCode} browser page errors are not empty`);
      const blockingConsole = consoleMessages.filter(isBlockingConsoleMessage);
      assert(blockingConsole.length === 0, `${roleCode} browser console contains application errors`);
      browserDiagnostics.push({
        roleCode,
        errors,
        consoleMessages: consoleMessages.map(safeDiagnostic),
      });
      browser.command(session, ['close']);
      liveSessions.delete(session);
    }

    const faultState = await verifyFrontendErrorState({
      browser,
      frontendUrl,
      context,
      principals,
      outputRoot: output.temporaryPath,
      liveSessions,
    });
    validateExactMatrix(matrix);
    const recomputedSourceTreeHash = currentSourceTreeHash();
    assert(
      recomputedSourceTreeHash === context.sourceTreeHash,
      'current normalized source tree differs from the run context',
    );
    const runtimeProvenance = assertRuntimeProvenanceStable(
      provenanceBefore,
      captureRuntimeProvenance({
        mode: 'normal',
        context,
        runtimeEnv: options.runtimeEnv,
        backendUrl,
        frontendUrl,
      }),
    );
    const endedAt = utcNow();

    mkdirSync(join(output.temporaryPath, 'screenshots'), { recursive: true });
    writeJson(join(output.temporaryPath, 'principal-role-capabilities.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      roles: principals.map(publicPrincipal),
    });
    writeJson(join(output.temporaryPath, 'route-matrix.json'), {
      schemaVersion: 1,
      evidenceId: EVIDENCE_ID_NORMAL_BROWSER,
      verdict: 'PASS',
      marker: NORMAL_BROWSER_MARKER,
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      roles: ROLES,
      viewports: VIEWPORTS.map(viewportLabel),
      routes: ROUTES.map((route) => route.path),
      expectedRowCount: expectedMatrixKeys().length,
      actualRowCount: matrix.length,
      rows: matrix,
    });
    writeTsv(
      join(output.temporaryPath, 'route-matrix.tsv'),
      [
        'rowKey',
        'roleCode',
        'viewport',
        'routeKey',
        'requestedPath',
        'actualPath',
        'heading',
        'breadcrumbs',
        'menuLabels',
        'states',
        'horizontalOverflow',
        'unlabeledControlCount',
        'apiRequestCount',
        'apiStatuses',
        'correlationIds',
        'axeCriticalSeriousViolationCount',
        'screenshot',
        'verdict',
      ],
      matrix.map((row) => ({
        ...row,
        breadcrumbs: row.breadcrumbs.join(' > '),
        menuLabels: row.menuLabels.join(' | '),
        states: row.states.join(','),
        apiStatuses: row.apiStatuses.join(','),
        correlationIds: row.correlationIds.join(','),
      })),
    );
    writeJson(join(output.temporaryPath, 'axe-report.json'), axe);
    writeJson(join(output.temporaryPath, 'keyboard-report.json'), keyboard);
    writeJson(join(output.temporaryPath, 'axe-keyboard-report.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      blockingImpacts: ['critical', 'serious'],
      axe,
      keyboard,
    });
    writeJson(join(output.temporaryPath, 'browser-network.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      rows: network,
    });
    writeJson(join(output.temporaryPath, 'browser-diagnostics.json'), browserDiagnostics);
    writeJson(join(output.temporaryPath, 'screenshots.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      items: screenshots,
      controlledErrorState: faultState,
    });
    writeJson(join(output.temporaryPath, 'traces.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      sanitizedSensitiveHeaders: true,
      items: traces,
    });
    writeJson(join(output.temporaryPath, 'http-authorization-probes.json'), authorizationProbes);
    writeJson(join(output.temporaryPath, 'unauthenticated-redirect.json'), unauthenticated);
    writeJson(join(output.temporaryPath, 'frontend-error-state.json'), faultState);
    writeJson(join(output.temporaryPath, 'db-read-marker.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      serverUuid: database.server.serverUuid,
      flywayVersion: database.flyway.latestVersion,
      applicationReadMarker: database.applicationReadMarker,
      matrixRowCount: matrix.length,
    });
    writeJson(join(output.temporaryPath, 'database-flyway-identity.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      server: database.server,
      flyway: database.flyway,
    });
    writeJson(join(output.temporaryPath, 'normal-runtime-source-hash.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      recomputedSourceTreeHash,
      match: true,
      runtimeProvenance,
      provenanceStable: true,
    });
    writeJson(join(output.temporaryPath, 'backend-http.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      rows: apiTrace,
    });
    writeJson(join(output.temporaryPath, 'backend-correlation.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      rows: apiTrace.map((row) => ({
        label: row.label,
        method: row.method,
        path: row.path,
        status: row.status,
        code: row.code,
        correlationId: row.correlationId,
      })),
    });
    writeJson(join(output.temporaryPath, 'http-request-response-log.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      backendRequests: apiTrace,
      browserRows: network,
      authorizationProbes,
      unauthenticated,
    });
    writeTsv(
      join(output.temporaryPath, 'backend-correlation.tsv'),
      [
        'label',
        'method',
        'path',
        'requestBodySha256',
        'status',
        'code',
        'correlationId',
        'etag',
        'cacheControl',
      ],
      apiTrace,
    );

    const summary = {
      schemaVersion: 1,
      evidenceId: EVIDENCE_ID_NORMAL_BROWSER,
      verdict: 'PASS',
      marker: NORMAL_BROWSER_MARKER,
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      startedAt,
      endedAt,
      transport: 'normal',
      databaseMode: 'real',
      backendUrl,
      frontendUrl,
      browser: {
        tool: 'agent-browser',
        version,
        engine: 'chrome',
        chrome: browser.chrome,
      },
      roles: ROLES,
      viewports: VIEWPORTS.map(viewportLabel),
      routes: ROUTES.map((route) => route.path),
      expectedMatrixRows: ROLES.length * VIEWPORTS.length * ROUTES.length,
      actualMatrixRows: matrix.length,
      w3AxeRows: axe.length,
      keyboardRows: keyboard.length,
      screenshotCount: screenshots.length,
      traceCount: traces.length,
      apiTraceRows: apiTrace.length,
      unauthenticatedStatus: unauthenticated.sessionStatus,
      auditorDeniedStatuses: authorizationProbes.auditor.deniedStatuses,
      faultState: faultState.state,
      database,
    };
    writeJson(join(output.temporaryPath, 'normal-browser.json'), summary);
    writeText(
      join(output.temporaryPath, 'normal-browser.log'),
      [
        contextLine(context, EVIDENCE_ID_NORMAL_BROWSER),
        `W3_BROWSER_RUNTIME=normal frontend=${frontendUrl} backend=${backendUrl} mysql=${database.server.versionCore} port=${database.server.port}`,
        runtimeProvenanceLine(runtimeProvenance),
        `W3_BROWSER_MATRIX_ROWS=PASS rows=${matrix.length} roles=${ROLES.length} viewports=${VIEWPORTS.length} routes=${ROUTES.length}`,
        `W3_BROWSER_A11Y=PASS rows=${axe.length} criticalSeriousViolations=0`,
        `W3_BROWSER_KEYBOARD=PASS rows=${keyboard.length}`,
        `W3_BROWSER_AUDITOR_READ_ONLY=PASS denied=${authorizationProbes.auditor.deniedStatuses.join(',')}`,
        NORMAL_BROWSER_MARKER,
        '',
      ].join('\n'),
    );
    const artifactPaths = [
      'normal-browser.json',
      'normal-browser.log',
      'principal-role-capabilities.json',
      'route-matrix.json',
      'route-matrix.tsv',
      'axe-report.json',
      'keyboard-report.json',
      'axe-keyboard-report.json',
      'browser-network.json',
      'browser-diagnostics.json',
      'screenshots.json',
      'traces.json',
      'http-authorization-probes.json',
      'unauthenticated-redirect.json',
      'frontend-error-state.json',
      'db-read-marker.json',
      'database-flyway-identity.json',
      'normal-runtime-source-hash.json',
      'backend-http.json',
      'backend-correlation.json',
      'backend-correlation.tsv',
      'http-request-response-log.json',
      'unauthenticated-initial.json',
      ...screenshots.map((item) => item.path),
      ...traces.map((item) => item.path),
      faultState.screenshot,
    ];
    writeJson(
      join(output.temporaryPath, 'artifact-inventory.json'),
      inventoryFiles(output.temporaryPath, artifactPaths),
    );
    output.commit();
    process.stdout.write(
      `${NORMAL_BROWSER_MARKER} runId=${context.runId} output=${output.finalPath}\n`,
    );
    return summary;
  } catch (error) {
    for (const session of liveSessions) {
      browser.tryCommand(session, ['close']);
    }
    output.cleanup();
    throw error;
  }
}

async function probeRoleHttpBoundaries({
  backendUrl,
  principals,
  apiTrace,
  context,
}) {
  const result = {};
  for (const principal of principals) {
    const session = new ApiSession(backendUrl, apiTrace);
    await session.login(
      principal.username,
      principal.password,
      `${principal.roleCode.toLowerCase()}-http-login`,
    );
    const statuses = {};
    for (const [label, path] of [
      ['groupsRead', '/api/v1/attendance-setup/groups?page=0&size=20'],
      ['shiftsRead', '/api/v1/attendance-setup/shifts?page=0&size=20'],
      ['calendarsRead', '/api/v1/attendance-setup/calendars?page=0&size=20'],
      ['policyCatalogRead', '/api/v1/attendance-setup/policy-catalog'],
      ['policyBindingsRead', '/api/v1/attendance-setup/policy-bindings?page=0&size=20'],
    ]) {
      statuses[label] = (await session.request(
        `${principal.roleCode.toLowerCase()}-${label}`,
        path,
      )).status;
    }
    const real404 = await session.request(
      `${principal.roleCode.toLowerCase()}-real-404`,
      '/api/v1/attendance-setup/groups/00000000-0000-4000-8000-000000000099/revisions?page=0&size=20',
      { expectedStatus: 404 },
    );
    statuses.real404 = real404.status;
    if (principal.roleCode === 'AUDITOR') {
      const idempotencyPrefix = `w3.browser.${context.runId}`;
      const groupDenied = await session.request(
        'auditor-manage-group-denied',
        '/api/v1/attendance-setup/groups',
        {
          method: 'POST',
          headers: {
            'Idempotency-Key': `${idempotencyPrefix}.group`,
            'X-Change-Reason': "UTF-8''W3%20AUDITOR%20denial",
          },
          body: {
            companyId: COMPANY_ID,
            code: 'W3AUDITORDENY',
            name: 'W3 AUDITOR 拒绝探针',
            locationId: '00000000-0000-4000-8000-000000000011',
            calendarId: '00000000-0000-4000-8000-000000000012',
            shiftTemplateId: '00000000-0000-4000-8000-000000000013',
            effectiveFrom: '2026-07-27',
            effectiveTo: null,
            reason: 'W3 AUDITOR manage 拒绝探针',
          },
          expectedStatus: 403,
        },
      );
      const simulationDenied = await session.request(
        'auditor-policy-simulation-denied',
        '/api/v1/attendance-setup/policy-simulations',
        {
          method: 'POST',
          body: {
            employeeId: '9200000000000000001',
            businessDate: '2026-07-27',
            correctionAsOf: '2026-07-27T12:00:00+08:00',
            punches: [],
          },
          expectedStatus: 403,
        },
      );
      const impactDenied = await session.request(
        'auditor-policy-impact-denied',
        '/api/v1/attendance-setup/policy-impact-preview',
        {
          method: 'POST',
          body: {
            policyKind: 'LATE_GRACE',
            policyVersionId: '25200000-0000-0000-0000-000000000002',
            groupId: '00000000-0000-4000-8000-000000000021',
            groupRevisionId: '00000000-0000-4000-8000-000000000022',
            effectiveFrom: '2026-07-27',
            effectiveTo: null,
            reason: 'W3 AUDITOR impact 拒绝探针',
          },
          expectedStatus: 403,
        },
      );
      statuses.deniedStatuses = [
        groupDenied.status,
        simulationDenied.status,
        impactDenied.status,
      ];
      assert(
        statuses.deniedStatuses.every((status) => status === 403),
        'AUDITOR manage/impact/simulation HTTP paths must all be 403',
      );
    }
    result[principal.roleCode.toLowerCase()] = statuses;
  }
  return result;
}

async function verifyUnauthenticatedRedirect({
  browser,
  frontendUrl,
  context,
  outputRoot,
  liveSessions,
}) {
  const session = `w3-${context.runId}-unauth`;
  liveSessions.add(session);
  browser.command(session, ['open', `${frontendUrl}/rules/attendance-groups`]);
  browser.command(session, ['wait', '700']);
  const state = browser.evaluate(session, pageStateExpression());
  const requests = normalizeBrowserRequests(
    browser.command(session, ['network', 'requests', '--type', 'xhr,fetch']),
  ).map(safeBrowserRequest);
  assert(state.path === '/login', 'unauthenticated W3 route must redirect to /login');
  assert(state.heading === '神州 HR 管理系统', 'unauthenticated redirect must show the real login page');
  assert(
    !state.bodyText.includes('演示环境'),
    'normal unauthenticated login must not expose demo mode',
  );
  const sessionRequest = requests.find(
    (request) => request.path === '/api/v1/auth/session',
  );
  assert(sessionRequest?.status === 401, 'unauthenticated session probe must return 401');
  assert(sessionRequest.correlationId, 'unauthenticated 401 must carry correlation ID');
  browser.command(session, ['close']);
  liveSessions.delete(session);
  const result = {
    requestedPath: '/rules/attendance-groups',
    actualPath: state.path,
    heading: state.heading,
    sessionStatus: sessionRequest.status,
    correlationId: sessionRequest.correlationId,
  };
  writeJson(join(outputRoot, 'unauthenticated-initial.json'), result);
  return result;
}

async function browserLogin({ browser, session, frontendUrl, principal }) {
  browser.command(session, ['open', `${frontendUrl}/login`]);
  browser.command(session, ['wait', 'input[autocomplete="username"]']);
  browser.sensitiveCommand(session, [
    'fill',
    'input[autocomplete="username"]',
    principal.username,
  ]);
  browser.sensitiveCommand(session, [
    'fill',
    'input[autocomplete="current-password"]',
    principal.password,
  ]);
  browser.command(session, ['click', 'button[type="submit"]']);
  browser.command(session, ['wait', '1000']);
  const state = browser.evaluate(session, pageStateExpression());
  assert(state.path !== '/login', `${principal.roleCode} browser login did not leave /login`);
  assert(
    state.environmentText && !state.environmentText.includes('演示'),
    `${principal.roleCode} browser is not in normal mode`,
  );
}

async function verifyKeyboard({
  browser,
  session,
  frontendUrl,
  principal,
  viewport,
}) {
  browser.command(session, ['open', `${frontendUrl}/rules/attendance-groups`]);
  browser.command(session, ['wait', '700']);
  browser.evaluate(session, 'document.activeElement?.blur(); document.body.focus(); true');
  browser.command(session, ['press', 'Tab']);
  const skipFocused = browser.evaluate(
    session,
    "document.activeElement?.classList.contains('skip-link') === true",
  );
  assert(skipFocused === true, `${principal.roleCode} ${viewportLabel(viewport)} skip link is not first`);
  browser.command(session, ['press', 'Enter']);
  const mainFocused = browser.evaluate(
    session,
    "document.activeElement?.id === 'main-content'",
  );
  assert(mainFocused === true, `${principal.roleCode} ${viewportLabel(viewport)} skip link does not focus main`);
  const mobileTriggerVisible = browser.evaluate(
    session,
    `(() => {
      const element = document.querySelector('.mobile-menu-trigger');
      if (!element) return false;
      const style = getComputedStyle(element);
      return style.display !== 'none' && style.visibility !== 'hidden'
        && element.getClientRects().length > 0;
    })()`,
  );
  let drawerKeyboard = null;
  if (mobileTriggerVisible) {
    browser.command(session, ['focus', '.mobile-menu-trigger']);
    browser.command(session, ['press', 'Enter']);
    browser.command(session, ['wait', '300']);
    const drawerOpen = browser.evaluate(
      session,
      "document.querySelector('.ant-drawer-open') !== null || document.querySelector('.ant-drawer-content') !== null",
    );
    assert(drawerOpen === true, `${principal.roleCode} ${viewportLabel(viewport)} mobile drawer did not open by keyboard`);
    browser.command(session, ['press', 'Escape']);
    browser.command(session, ['wait', '300']);
    const drawerStillOpen = browser.evaluate(
      session,
      "document.querySelector('.ant-drawer-open') !== null",
    );
    assert(drawerStillOpen === false, `${principal.roleCode} ${viewportLabel(viewport)} mobile drawer did not close with Escape`);
    drawerKeyboard = { openedWithEnter: true, closedWithEscape: true };
  }
  return {
    roleCode: principal.roleCode,
    viewport: viewportLabel(viewport),
    route: '/rules/attendance-groups',
    skipLinkFirst: true,
    skipLinkFocusedMain: true,
    mobileTriggerVisible,
    drawerKeyboard,
    verdict: 'PASS',
  };
}

function validateRouteState({ principal, route, viewport, state, requests }) {
  const label = matrixKey(principal.roleCode, viewport, route.key);
  assert(state.viewport.width === viewport.width, `${label} viewport width mismatch`);
  assert(state.viewport.height === viewport.height, `${label} viewport height mismatch`);
  assert(state.mainPresent, `${label} lacks #main-content`);
  assert(!state.horizontalOverflow, `${label} has page-level horizontal overflow`);
  assert(state.unlabeledControls.length === 0, `${label} has unlabeled visible controls`);
  assert(
    state.environmentText && !state.environmentText.includes('演示'),
    `${label} is not normal frontend transport`,
  );
  const expectedMenu = [...new Set(principal.menu.map((item) => item.label))].sort();
  assert(
    JSON.stringify([...state.menuLabels].sort()) === JSON.stringify(expectedMenu),
    `${label} UI menu differs from the server-authorized menu`,
  );
  assert(
    !/payroll|payslip/i.test(`${state.bodyText} ${state.path}`),
    `${label} exposes a forbidden payroll surface`,
  );

  const apiRequests = requests.filter((request) => request.api);
  assert(apiRequests.length > 0, `${label} did not call the real backend`);
  assert(
    apiRequests.every((request) => Number.isInteger(request.status) && request.status < 500),
    `${label} contains a missing/5xx backend response`,
  );
  assert(
    apiRequests.every((request) => request.correlationId),
    `${label} backend response lacks correlation ID`,
  );
  if (route.type === 'w3') {
    assert(state.path === route.path, `${label} W3 direct route redirected unexpectedly`);
    assert(state.heading === route.heading, `${label} heading mismatch`);
    assert(
      JSON.stringify(state.breadcrumbs) === JSON.stringify(route.breadcrumb),
      `${label} breadcrumb mismatch`,
    );
    assert(
      !state.states.some((value) => ['401', '403', '404', 'error', 'network-error'].includes(value)),
      `${label} W3 route rendered an error state`,
    );
    if (principal.roleCode === 'AUDITOR') {
      assert(
        !state.buttonTexts.some((text) => AUDITOR_FORBIDDEN_BUTTONS.includes(text)),
        `${label} AUDITOR exposes a manage/impact/simulation action`,
      );
    } else {
      for (const expectedAction of route.managerActions) {
        assert(
          state.buttonTexts.includes(expectedAction),
          `${label} manager action is missing: ${expectedAction}`,
        );
      }
    }
  } else if (route.type === 'unknown') {
    assert(state.path === route.path, `${label} unknown route changed path`);
    assert(state.states.includes('404'), `${label} unknown route lacks UI 404 state`);
  } else if (route.type === 'default-redirect') {
    assert(
      state.path === principal.menu[0].path,
      `${label} default/login redirect is not the first authorized menu path`,
    );
  } else if (route.type === 'rules-landing') {
    const expectedPath = principal.capabilities.includes('POLICY:READ')
      ? '/rules'
      : '/rules/attendance-groups';
    assert(state.path === expectedPath, `${label} rules landing redirect mismatch`);
    assert(
      state.heading === (expectedPath === '/rules' ? '规则中心' : '考勤组与人员归属'),
      `${label} rules landing heading mismatch`,
    );
  } else if (route.type === 'policy-templates') {
    if (principal.capabilities.includes('POLICY:READ')) {
      assert(state.path === route.path && state.heading === '策略模板', `${label} template route mismatch`);
      assert(!state.states.includes('403'), `${label} authorized template route rendered 403`);
    } else {
      assert(state.path === route.path && state.states.includes('403'), `${label} unauthorized template route lacks UI 403`);
    }
  }
}

function captureScreenshot({
  browser,
  session,
  outputRoot,
  roleCode,
  viewport,
  route,
}) {
  mkdirSync(join(outputRoot, 'screenshots'), { recursive: true });
  const relativePath = [
    'screenshots',
    `${roleCode.toLowerCase()}-${viewportLabel(viewport)}-${route.key}.png`,
  ].join('/');
  const absolutePath = join(outputRoot, relativePath);
  browser.command(session, ['screenshot', absolutePath]);
  const metadata = statSync(absolutePath);
  assert(metadata.size > 1024, `screenshot is unexpectedly small: ${relativePath}`);
  return {
    path: relativePath,
    roleCode,
    viewport: viewportLabel(viewport),
    route: route.path,
    sizeBytes: metadata.size,
    sha256: sha256File(absolutePath),
  };
}

function shouldScreenshot(viewportIndex, viewport, route) {
  if (route.type !== 'w3') return false;
  const rotatingRoute = W3_DIRECT_ROUTES[viewportIndex % W3_DIRECT_ROUTES.length];
  return route.key === rotatingRoute.key
    || (viewport.width === 1440 && viewport.height === 900);
}

async function verifyFrontendErrorState({
  browser,
  frontendUrl,
  context,
  principals,
  outputRoot,
  liveSessions,
}) {
  const principal = principals.find((candidate) => candidate.roleCode === 'SYSTEM_ADMIN');
  const session = `w3-${context.runId}-fault`;
  liveSessions.add(session);
  await browserLogin({ browser, session, frontendUrl, principal });
  browser.command(session, ['set', 'viewport', '1440', '900']);
  browser.command(session, [
    'network',
    'route',
    '**/api/v1/attendance-setup/groups*',
    '--abort',
  ]);
  browser.command(session, ['open', `${frontendUrl}/rules/attendance-groups`]);
  browser.command(session, ['wait', '900']);
  const state = browser.evaluate(session, pageStateExpression());
  assert(
    state.states.includes('network-error') || state.states.includes('error'),
    'controlled frontend network fault did not render an error state',
  );
  mkdirSync(join(outputRoot, 'screenshots'), { recursive: true });
  const screenshot = 'screenshots/system_admin-network-error-1440x900.png';
  browser.command(session, ['screenshot', join(outputRoot, screenshot)]);
  browser.command(session, ['network', 'unroute']);
  browser.command(session, ['reload']);
  browser.command(session, ['wait', '700']);
  const recovered = browser.evaluate(session, pageStateExpression());
  assert(
    !recovered.states.some((value) => value === 'network-error' || value === 'error'),
    'frontend did not recover after removing the controlled network fault',
  );
  browser.command(session, ['close']);
  liveSessions.delete(session);
  return {
    roleCode: principal.roleCode,
    route: '/rules/attendance-groups',
    fault: 'browser-local abort of groups GET',
    state: state.states.includes('network-error') ? 'network-error' : 'error',
    recovered: true,
    screenshot,
  };
}

export function expectedMatrixKeys() {
  return ROLES.flatMap((roleCode) => VIEWPORTS.flatMap(
    (viewport) => ROUTES.map((route) => matrixKey(roleCode, viewport, route.key)),
  ));
}

export function validateExactMatrix(matrix) {
  const expected = expectedMatrixKeys();
  const actual = matrix.map((row) => row.rowKey);
  assert(actual.length === 162, 'normal-browser matrix must contain exactly 162 rows');
  assert(new Set(actual).size === actual.length, 'normal-browser matrix contains duplicate rows');
  assert(JSON.stringify(actual) === JSON.stringify(expected), 'normal-browser matrix row order/set mismatch');
  assert(matrix.every((row) => row.verdict === 'PASS'), 'normal-browser matrix contains a non-PASS row');
}

export function viewportLabel(viewport) {
  return `${viewport.width}x${viewport.height}`;
}

export function matrixKey(roleCode, viewport, routeKey) {
  return `${roleCode}|${viewportLabel(viewport)}|${routeKey}`;
}

function sessionName(runId, roleCode) {
  return `w3-${runId}-${roleCode.toLowerCase()}`.slice(0, 96);
}

function pageStateExpression() {
  return `(() => {
    const visible = (element) => {
      if (!(element instanceof HTMLElement)) return false;
      const style = getComputedStyle(element);
      return style.display !== 'none' && style.visibility !== 'hidden'
        && element.getClientRects().length > 0;
    };
    const text = (element) => (element?.textContent ?? '').replace(/\\s+/g, ' ').trim();
    const unique = (items) => [...new Set(items.filter(Boolean))];
    const controls = [...document.querySelectorAll('button,[role="button"],input,select,textarea,a[href]')]
      .filter(visible);
    const unlabeledControls = controls.filter((element) => {
      const name = element.getAttribute('aria-label')
        || element.getAttribute('title')
        || text(element)
        || (element instanceof HTMLInputElement
          ? (element.labels ? [...element.labels].map(text).join(' ') : '')
            || element.getAttribute('placeholder')
          : '');
      return !name;
    }).map((element) => element.outerHTML.slice(0, 240));
    return {
      path: location.pathname + location.search,
      heading: text([...document.querySelectorAll('h1')].find(visible)),
      breadcrumbs: [...document.querySelectorAll('.breadcrumbs .ant-breadcrumb-link')]
        .filter(visible).map(text),
      menuLabels: unique([...document.querySelectorAll('.ant-menu-title-content')]
        .map(text)),
      states: unique([...document.querySelectorAll('.async-state[data-state]')]
        .filter(visible).map((element) => element.dataset.state)),
      buttonTexts: unique([...document.querySelectorAll('button,[role="button"]')]
        .filter(visible).map(text)),
      environmentText: text(document.querySelector('.app-environment')),
      bodyText: text(document.body),
      mainPresent: document.querySelector('#main-content') !== null,
      horizontalOverflow: document.documentElement.scrollWidth > window.innerWidth
        || document.body.scrollWidth > window.innerWidth,
      documentWidth: document.documentElement.scrollWidth,
      unlabeledControls,
      viewport: { width: window.innerWidth, height: window.innerHeight }
    };
  })()`;
}

export function normalizeBrowserRequests(result) {
  const value = unwrapAgentBrowser(result);
  const requests = Array.isArray(value)
    ? value
    : value?.requests ?? value?.data?.requests ?? [];
  assert(Array.isArray(requests), 'agent-browser network result is malformed');
  return requests.map((request) => {
    const url = request.url ?? request.request?.url ?? '';
    const response = request.response ?? {};
    const responseHeaders = request.responseHeaders ?? response.headers ?? {};
    return {
      method: request.method ?? request.request?.method ?? '',
      url,
      status: Number(request.status ?? response.status),
      resourceType: request.resourceType ?? request.type ?? '',
      responseHeaders,
    };
  });
}

function safeBrowserRequest(request) {
  let url;
  try {
    url = new URL(request.url);
  } catch {
    return {
      method: request.method,
      path: '[invalid-url]',
      status: request.status,
      resourceType: request.resourceType,
      api: false,
      correlationId: null,
    };
  }
  const correlationId = headerValue(request.responseHeaders, 'x-correlation-id');
  return {
    method: request.method,
    path: `${url.pathname}${url.search}`,
    status: request.status,
    resourceType: request.resourceType,
    api: url.pathname.startsWith('/api/v1/'),
    correlationId,
  };
}

function headerValue(headers, targetName) {
  if (Array.isArray(headers)) {
    return headers.find(
      (header) => String(header.name).toLowerCase() === targetName,
    )?.value ?? null;
  }
  if (headers && typeof headers === 'object') {
    const key = Object.keys(headers).find((name) => name.toLowerCase() === targetName);
    return key ? headers[key] : null;
  }
  return null;
}

export function normalizeAxeResult(result) {
  const value = unwrapAgentBrowser(result);
  const data = value?.data && !value.violations ? value.data : value;
  const violations = data?.violations ?? [];
  const incomplete = data?.incomplete ?? [];
  assert(Array.isArray(violations) && Array.isArray(incomplete), 'agent-browser axe result is malformed');
  const safeRule = (rule) => ({
    id: rule.id,
    impact: rule.impact ?? null,
    help: rule.help,
    helpUrl: rule.helpUrl,
    tags: rule.tags,
    nodes: (rule.nodes ?? []).map((node) => ({
      impact: node.impact ?? null,
      target: node.target,
      failureSummary: node.failureSummary,
      html: node.html?.slice(0, 500),
    })),
  });
  return {
    violations: violations.map(safeRule),
    incomplete: incomplete.map(safeRule),
  };
}

function normalizeDiagnosticList(result) {
  const value = unwrapAgentBrowser(result);
  const candidates = Array.isArray(value)
    ? value
    : value?.errors ?? value?.messages ?? value?.data?.errors ?? value?.data?.messages ?? [];
  return Array.isArray(candidates) ? candidates : [];
}

function isBlockingConsoleMessage(message) {
  const type = String(message.type ?? message.level ?? '').toLowerCase();
  const text = String(message.text ?? message.message ?? '');
  if (!['error', 'assert'].includes(type)) return false;
  return !text.includes('Download the React DevTools');
}

function safeDiagnostic(message) {
  return {
    type: message.type ?? message.level ?? null,
    text: String(message.text ?? message.message ?? '').slice(0, 1000),
  };
}

function sanitizeTrace(path) {
  const parsed = JSON.parse(readFileSync(path, 'utf8'));
  const sensitive = new Set([
    'authorization',
    'cookie',
    'set-cookie',
    'x-csrf-token',
    'proxy-authorization',
  ]);
  const visit = (value) => {
    if (Array.isArray(value)) {
      return value.map((item) => {
        if (
          item
          && typeof item === 'object'
          && sensitive.has(String(item.name ?? '').toLowerCase())
        ) {
          return { ...item, value: '[REDACTED]' };
        }
        return visit(item);
      });
    }
    if (!value || typeof value !== 'object') return value;
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
      key,
      sensitive.has(key.toLowerCase()) ? '[REDACTED]' : visit(item),
    ]));
  };
  writeFileSync(path, `${JSON.stringify(visit(parsed))}\n`, { flag: 'w' });
}

function unwrapAgentBrowser(result) {
  if (result && typeof result === 'object' && Object.hasOwn(result, 'success')) {
    assert(result.success === true, result.error?.message ?? result.error ?? 'agent-browser command failed');
    return result.data;
  }
  return result;
}

export class AgentBrowserRunner {
  constructor({ binary, chrome, namespace, allowedDomains }) {
    assert(typeof binary === 'string' && binary.startsWith('/'), 'agent-browser path must be absolute');
    assert(typeof chrome === 'string' && chrome.startsWith('/'), 'Chrome path must be absolute');
    const binaryTarget = realpathSync(binary);
    const chromeTarget = realpathSync(chrome);
    assert(statSync(binaryTarget).isFile() && (statSync(binaryTarget).mode & 0o111), 'agent-browser is not executable');
    assert(!lstatSync(chrome).isSymbolicLink(), 'Chrome executable must not be a symbolic link');
    assert(statSync(chromeTarget).isFile() && (statSync(chromeTarget).mode & 0o111), 'Chrome is not executable');
    this.binary = binary;
    this.binaryTarget = binaryTarget;
    this.chrome = chromeTarget;
    this.namespace = namespace;
    this.allowedDomains = allowedDomains.join(',');
  }

  version() {
    const result = spawnSync(this.binary, ['--version'], {
      encoding: 'utf8',
      timeout: 10_000,
      maxBuffer: 1024 * 1024,
      env: this.environment(),
    });
    assert(!result.error && result.status === 0, 'agent-browser --version failed');
    const version = result.stdout.trim();
    assert(/^agent-browser \d+\.\d+\.\d+$/.test(version), 'agent-browser version output is invalid');
    return version;
  }

  command(session, args) {
    const result = spawnSync(this.binary, [
      '--session',
      session,
      '--namespace',
      this.namespace,
      '--executable-path',
      this.chrome,
      ...args,
      '--json',
    ], {
      encoding: 'utf8',
      timeout: 30_000,
      killSignal: 'SIGKILL',
      maxBuffer: 16 * 1024 * 1024,
      env: this.environment(),
    });
    assert(!result.error, `agent-browser ${args[0]} could not execute`);
    assert(result.status === 0, `agent-browser ${args[0]} failed`);
    const output = result.stdout.trim();
    assert(output, `agent-browser ${args[0]} returned empty output`);
    let parsed;
    try {
      parsed = JSON.parse(output);
    } catch {
      throw new Error(`agent-browser ${args[0]} returned malformed JSON`);
    }
    unwrapAgentBrowser(parsed);
    return parsed;
  }

  sensitiveCommand(session, args) {
    const result = spawnSync(this.binary, [
      '--session',
      session,
      '--namespace',
      this.namespace,
      '--executable-path',
      this.chrome,
      ...args,
      '--json',
    ], {
      encoding: 'utf8',
      timeout: 30_000,
      killSignal: 'SIGKILL',
      maxBuffer: 1024 * 1024,
      env: this.environment(),
    });
    assert(!result.error && result.status === 0, `agent-browser ${args[0]} sensitive action failed`);
    return true;
  }

  tryCommand(session, args) {
    try {
      return this.command(session, args);
    } catch {
      return null;
    }
  }

  evaluate(session, expression) {
    const result = this.command(session, ['eval', expression]);
    const value = unwrapAgentBrowser(result);
    return value?.result ?? value;
  }

  environment() {
    return safeSubprocessEnvironment({
      AGENT_BROWSER_ALLOWED_DOMAINS: this.allowedDomains,
      AGENT_BROWSER_CONTENT_BOUNDARIES: 'false',
      AGENT_BROWSER_HIDE_SCROLLBARS: 'true',
    });
  }
}

export function staticSelfTest() {
  assert(ROLES.length === 3 && new Set(ROLES).size === 3, 'role matrix drifted');
  assert(
    JSON.stringify(VIEWPORTS.map(viewportLabel))
      === JSON.stringify([
        '390x844',
        '768x1024',
        '1024x768',
        '1366x768',
        '1440x900',
        '1920x1080',
      ]),
    'viewport matrix drifted',
  );
  assert(ROUTES.length === 9 && W3_DIRECT_ROUTES.length === 4, 'route matrix drifted');
  assert(expectedMatrixKeys().length === 162, 'matrix cardinality drifted');
  assert(new Set(expectedMatrixKeys()).size === 162, 'matrix keys are not unique');
  assert(
    NORMAL_BROWSER_MARKER.includes('transport=normal db=real'),
    'normal-browser marker drifted',
  );
  const fixtureAxe = normalizeAxeResult({
    success: true,
    data: { violations: [], incomplete: [] },
  });
  assert(fixtureAxe.violations.length === 0, 'axe normalization fixture failed');
  return {
    marker: 'W3_NORMAL_BROWSER_HARNESS_SELF_TEST=PASS tests=7 matrixRows=162',
  };
}

async function main() {
  const command = process.argv[2];
  if (!command || ['help', '--help', '-h'].includes(command)) {
    process.stdout.write(USAGE);
    return;
  }
  if (command === 'self-test') {
    assert(process.argv.length === 3, 'self-test accepts no arguments');
    process.stdout.write(`${staticSelfTest().marker}\n`);
    return;
  }
  assert(command === 'execute', 'unsupported command');
  const values = parseNamedArguments(
    process.argv.slice(3),
    [
      'run-context',
      'runtime-env',
      'login-env',
      'backend-url',
      'frontend-url',
      'output-dir',
      'agent-browser-bin',
      'chrome-bin',
      'provision-synthetic-accounts',
    ],
    ['provision-synthetic-accounts'],
  );
  requireArguments(values, [
    'run-context',
    'runtime-env',
    'login-env',
    'backend-url',
    'frontend-url',
    'output-dir',
    'provision-synthetic-accounts',
  ]);
  await executeNormalBrowser({
    runContext: values.get('run-context'),
    runtimeEnv: values.get('runtime-env'),
    loginEnv: values.get('login-env'),
    backendUrl: values.get('backend-url'),
    frontendUrl: values.get('frontend-url'),
    outputDir: values.get('output-dir'),
    agentBrowserBin: values.get('agent-browser-bin'),
    chromeBin: values.get('chrome-bin'),
    provisionSyntheticAccounts: values.get('provision-synthetic-accounts'),
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`[wave3-normal-browser] ERROR: ${error.message}\n`);
    process.exitCode = 1;
  });
}
