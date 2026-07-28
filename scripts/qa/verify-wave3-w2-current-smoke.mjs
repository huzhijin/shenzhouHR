#!/usr/bin/env node

import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  ApiSession,
  EVIDENCE_ID_W2_CURRENT_SMOKE,
  W2_CURRENT_SMOKE_MARKER,
  assert,
  assertCorrelatedApiTrace,
  assertDatabaseBinding,
  assertFrozenSource,
  assertRuntimeProvenanceStable,
  captureRuntimeProvenance,
  contextLine,
  createDatabaseBinding,
  inventoryFiles,
  loadRunContext,
  parseNamedArguments,
  prepareAtomicOutput,
  publicPrincipal,
  readLoginEnvironment,
  readRuntimeEnvironment,
  requireArguments,
  runtimeProvenanceLine,
  utcNow,
  validateLoopbackUrl,
  writeJson,
  writeText,
  writeTsv,
} from './wave3-runtime-common.mjs';

const USAGE = `Usage:
  node scripts/qa/verify-wave3-w2-current-smoke.mjs identity \\
    --runtime-env ABS_PATH

  node scripts/qa/verify-wave3-w2-current-smoke.mjs execute \\
    --run-context ABS_RUN_CONTEXT \\
    --runtime-env ABS_PRIVATE_ENV \\
    --login-env ABS_PRIVATE_ENV \\
    --backend-url http://127.0.0.1:8080 \\
    --output-dir ABS_RUN_ROOT/leaves/w2-current-smoke/raw

  node scripts/qa/verify-wave3-w2-current-smoke.mjs self-test
`;

export async function executeW2CurrentSmoke(options) {
  const context = loadRunContext(
    options.runContext,
    EVIDENCE_ID_W2_CURRENT_SMOKE,
  );
  const runtimeEnvironment = readRuntimeEnvironment(options.runtimeEnv);
  const loginEnvironment = readLoginEnvironment(options.loginEnv);
  const backendUrl = validateLoopbackUrl(options.backendUrl, 'backend URL').origin;
  assertFrozenSource(context);
  const database = createDatabaseBinding(runtimeEnvironment);
  assertDatabaseBinding(context, database);
  const provenanceBefore = captureRuntimeProvenance({
    mode: 'w2',
    context,
    runtimeEnv: options.runtimeEnv,
    backendUrl,
  });
  const output = prepareAtomicOutput(
    context,
    options.outputDir,
    'w2-current-smoke',
  );
  const startedAt = utcNow();
  const trace = [];
  try {
    const unauthenticated = new ApiSession(backendUrl, trace);
    await unauthenticated.request(
      'w2-unauthenticated-capabilities',
      '/api/v1/me/capabilities',
      { expectedStatus: 401, includeAuthentication: false },
    );

    const admin = new ApiSession(backendUrl, trace);
    const login = await admin.login(
      loginEnvironment.SHENZHOUHR_LOGIN_USERNAME,
      loginEnvironment.SHENZHOUHR_LOGIN_PASSWORD,
      'w2-current-admin-login',
    );
    assert(
      login.body && login.body.firstPasswordChangeRequired === false,
      'W2 current smoke principal must have completed first password change',
    );
    const capabilities = (await admin.request(
      'w2-current-capabilities',
      '/api/v1/me/capabilities',
    )).body;
    const requiredCapabilities = [
      'MASTER_DATA:READ',
      'ORGANIZATION:READ',
      'EMPLOYEE:READ',
      'PEOPLE_IMPORT:READ',
      'PEOPLE_IMPORT:TEMPLATE_DOWNLOAD',
    ];
    for (const capability of requiredCapabilities) {
      assert(
        capabilities.capabilities.includes(capability),
        `W2 current smoke principal lacks ${capability}`,
      );
    }
    assert(capabilities.menu.length > 0, 'W2 current smoke authorized menu is empty');

    const organizations = (await admin.request(
      'w2-current-organizations',
      '/api/v1/organization-units?includeInactive=true',
    )).body;
    assert(Array.isArray(organizations), 'organization read must return an array');
    const organizationCount = flattenOrganizations(organizations).length;

    const employees = (await admin.request(
      'w2-current-employees',
      '/api/v1/employees?page=0&size=20',
    )).body;
    assertPage(employees, 'employee page');

    const templates = (await admin.request(
      'w2-current-import-templates',
      '/api/v1/people-imports/templates?page=0&size=20',
    )).body;
    assertPage(templates, 'people-import template page');
    const templateTypes = templates.items.map((item) => item.templateType).sort();
    assert(
      JSON.stringify(templateTypes)
        === JSON.stringify(['EMPLOYEE', 'EMPLOYMENT', 'ORGANIZATION', 'PRIOR_SERVICE']),
      'W2 current template catalog differs from the exact four-template contract',
    );
    assert(
      templates.items.every((item) => Array.isArray(item.fields) && item.fields.length > 0),
      'W2 current template instructions are incomplete',
    );

    const imports = (await admin.request(
      'w2-current-import-batches',
      '/api/v1/people-imports?page=0&size=20',
    )).body;
    assertPage(imports, 'people-import batch page');

    const missingEmployee = await admin.request(
      'w2-current-real-404',
      '/api/v1/employees/00000000-0000-0000-0000-000000000099',
      { expectedStatus: 404 },
    );
    assert(
      missingEmployee.body?.code,
      'W2 current 404 must use the correlated API error envelope',
    );
    assertCorrelatedApiTrace(trace);
    assertFrozenSource(context);
    const runtimeProvenance = assertRuntimeProvenanceStable(
      provenanceBefore,
      captureRuntimeProvenance({
        mode: 'w2',
        context,
        runtimeEnv: options.runtimeEnv,
        backendUrl,
      }),
    );

    const principal = {
      accountId: login.body.accountId,
      username: login.body.username,
      displayName: login.body.displayName,
      capabilities: [...capabilities.capabilities].sort(),
      menu: capabilities.menu,
    };
    const result = {
      schemaVersion: 1,
      evidenceId: EVIDENCE_ID_W2_CURRENT_SMOKE,
      verdict: 'PASS',
      marker: W2_CURRENT_SMOKE_MARKER,
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      startedAt,
      endedAt: utcNow(),
      transport: 'normal',
      backendUrl,
      database,
      runtimeProvenance,
      principal: publicPrincipal(principal),
      probes: {
        unauthenticatedStatus: 401,
        organizationCount,
        employeeTotal: employees.total,
        importTemplateTypes: templateTypes,
        importBatchTotal: imports.total,
        real404Status: missingEmployee.status,
        real404Code: missingEmployee.body.code,
      },
      httpTraceRowCount: trace.length,
    };
    writeJson(join(output.temporaryPath, 'w2-current-smoke.json'), result);
    writeTsv(
      join(output.temporaryPath, 'w2-current-http.tsv'),
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
      trace,
    );
    writeJson(join(output.temporaryPath, 'w2-current-http.json'), trace);
    writeJson(join(output.temporaryPath, 'w2-current-db-marker.json'), {
      runId: context.runId,
      sourceTreeHash: context.sourceTreeHash,
      databaseIdentity: context.databaseIdentity,
      serverUuid: database.server.serverUuid,
      flywayVersion: database.flyway.latestVersion,
      applicationReadMarker: database.applicationReadMarker,
    });
    writeText(
      join(output.temporaryPath, 'w2-current-smoke.log'),
      [
        contextLine(context, EVIDENCE_ID_W2_CURRENT_SMOKE),
        `W3_W2_CURRENT_RUNTIME=normal backend=${backendUrl} mysql=${database.server.versionCore} port=${database.server.port}`,
        runtimeProvenanceLine(runtimeProvenance),
        `W3_W2_CURRENT_READS=PASS organizations=${organizationCount} employees=${employees.total} templates=${templateTypes.length} imports=${imports.total}`,
        W2_CURRENT_SMOKE_MARKER,
        '',
      ].join('\n'),
    );
    const artifactPaths = [
      'w2-current-smoke.json',
      'w2-current-http.tsv',
      'w2-current-http.json',
      'w2-current-db-marker.json',
      'w2-current-smoke.log',
    ];
    writeJson(
      join(output.temporaryPath, 'artifact-inventory.json'),
      inventoryFiles(output.temporaryPath, artifactPaths),
    );
    output.commit();
    process.stdout.write(
      `${W2_CURRENT_SMOKE_MARKER} runId=${context.runId} output=${output.finalPath}\n`,
    );
    return result;
  } catch (error) {
    output.cleanup();
    throw error;
  }
}

function assertPage(value, label) {
  assert(value && typeof value === 'object', `${label} must be an object`);
  assert(Array.isArray(value.items), `${label}.items must be an array`);
  assert(Number.isSafeInteger(value.total) && value.total >= 0, `${label}.total is invalid`);
  assert(Number.isSafeInteger(value.page) && value.page >= 0, `${label}.page is invalid`);
  assert(Number.isSafeInteger(value.size) && value.size >= 1, `${label}.size is invalid`);
}

function flattenOrganizations(nodes) {
  return nodes.flatMap((node) => [
    node,
    ...flattenOrganizations(Array.isArray(node.children) ? node.children : []),
  ]);
}

export function staticSelfTest() {
  assertPage({ items: [], total: 0, page: 0, size: 20 }, 'fixture');
  assert(flattenOrganizations([{ children: [{ children: [] }] }]).length === 2, 'flatten fixture failed');
  assert(
    W2_CURRENT_SMOKE_MARKER === 'W3_W2_CURRENT_SMOKE=PASS',
    'W2 current marker drifted',
  );
  return {
    marker: 'W3_W2_CURRENT_SMOKE_HARNESS_SELF_TEST=PASS tests=3',
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
  if (command === 'identity') {
    const values = parseNamedArguments(
      process.argv.slice(3),
      ['runtime-env'],
    );
    requireArguments(values, ['runtime-env']);
    const binding = createDatabaseBinding(
      readRuntimeEnvironment(values.get('runtime-env')),
    );
    process.stdout.write(`${binding.databaseIdentity}\n`);
    return;
  }
  assert(command === 'execute', 'unsupported command');
  const values = parseNamedArguments(
    process.argv.slice(3),
    ['run-context', 'runtime-env', 'login-env', 'backend-url', 'output-dir'],
  );
  requireArguments(values, [
    'run-context',
    'runtime-env',
    'login-env',
    'backend-url',
    'output-dir',
  ]);
  await executeW2CurrentSmoke({
    runContext: values.get('run-context'),
    runtimeEnv: values.get('runtime-env'),
    loginEnv: values.get('login-env'),
    backendUrl: values.get('backend-url'),
    outputDir: values.get('output-dir'),
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`[wave3-w2-current-smoke] ERROR: ${error.message}\n`);
    process.exitCode = 1;
  });
}
