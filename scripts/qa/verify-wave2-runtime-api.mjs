#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import {
  chmodSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import {
  dirname,
  isAbsolute,
  join,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath } from 'node:url';

const EXPECTED_REPOSITORY_ROOT = '/Users/huzhijin/Downloads/shenzhouHR';
if (process.cwd() !== EXPECTED_REPOSITORY_ROOT) {
  process.stderr.write('PROJECT_ROOT_SCOPE_ERROR\n');
  process.exit(1);
}

const scriptPath = fileURLToPath(import.meta.url);
const repositoryRoot = resolve(dirname(scriptPath), '../..');
if (repositoryRoot !== EXPECTED_REPOSITORY_ROOT) {
  process.stderr.write('PROJECT_ROOT_SCOPE_ERROR\n');
  process.exit(1);
}
const runtimeRoot = join(repositoryRoot, 'docs/verification/wave2/runtime');
const fixturesRoot = join(runtimeRoot, 'fixtures');
const XLSX_MEDIA_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const COMPANY_ID = '30000000-0000-0000-0000-000000000001';
const BASE_URL = 'http://127.0.0.1:8080';
const HTTP_TIMEOUT_MS = 15_000;
const SUBPROCESS_TIMEOUT_MS = 30_000;
const argumentsMap = parseArguments(process.argv.slice(2));
const runtimeEnvironmentPath = argumentsMap.get('runtime-env')
  ?? '/Users/huzhijin/.config/shenzhouhr/wave2-runtime.env';
const loginEnvironmentPath = argumentsMap.get('login-env')
  ?? '/Users/huzhijin/.config/shenzhouhr/wave1-login.env';
const runtimeEnvironment = readPrivateEnvironment(
  runtimeEnvironmentPath,
  [
    'SHENZHOUHR_MYSQL_HOST',
    'SHENZHOUHR_MYSQL_PORT',
    'SHENZHOUHR_MYSQL_CLIENT_BIN',
    'SHENZHOUHR_FLYWAY_BIN',
    'SHENZHOUHR_TEST_FINAL_STATE',
    'SHENZHOUHR_FLYWAY_PASSWORD',
    'SHENZHOUHR_DEV_DB_PASSWORD',
    'SHENZHOUHR_TEST_DB_PASSWORD',
  ],
  [
    'SHENZHOUHR_MYSQL_HOST',
    'SHENZHOUHR_MYSQL_PORT',
    'SHENZHOUHR_DEV_DB_PASSWORD',
  ],
);
if (!['localhost', '127.0.0.1'].includes(runtimeEnvironment.SHENZHOUHR_MYSQL_HOST)) {
  throw new Error('runtime MySQL host must be localhost or 127.0.0.1');
}
if (runtimeEnvironment.SHENZHOUHR_MYSQL_PORT !== '3306') {
  throw new Error('runtime MySQL port must be 3306');
}
const loginEnvironment = readPrivateEnvironment(
  loginEnvironmentPath,
  ['SHENZHOUHR_LOGIN_USERNAME', 'SHENZHOUHR_LOGIN_PASSWORD'],
  ['SHENZHOUHR_LOGIN_USERNAME', 'SHENZHOUHR_LOGIN_PASSWORD'],
);
const runToken = `${Date.now().toString(36)}${randomBytes(4).toString('hex')}`;
const runCode = runToken.slice(-12).toUpperCase();
const temporaryRoot = mkdtempSync(join(tmpdir(), 'shenzhouhr-wave2-api-'));
let temporaryRootCleaned = false;

mkdirSync(fixturesRoot, { recursive: true });

function cleanupTemporaryRoot() {
  if (temporaryRootCleaned) return;
  rmSync(temporaryRoot, { recursive: true, force: true });
  temporaryRootCleaned = true;
}

for (const [signal, exitCode] of [
  ['SIGHUP', 129],
  ['SIGINT', 130],
  ['SIGTERM', 143],
]) {
  process.once(signal, () => {
    cleanupTemporaryRoot();
    process.exit(exitCode);
  });
}

const trace = [];
const createdTemplatePaths = new Set();
const results = {
  runId: runToken,
  generatedAt: new Date().toISOString(),
  services: {
    backend: BASE_URL,
    frontend: 'http://127.0.0.1:5173',
    demoFrontend: 'http://127.0.0.1:5174',
    database: 'shenzhou_hr_dev',
  },
  probes: {},
  imports: {},
  localMaintenance: {},
  employment: {},
  priorService: {},
  security: {},
  audit: {},
  formalCounts: {},
};

async function main() {
  try {
    const mysqlDefaultsPath = createMysqlDefaultsFile(runtimeEnvironment, temporaryRoot);
    const admin = new ApiSession(BASE_URL, trace);
    await admin.login(
      loginEnvironment.SHENZHOUHR_LOGIN_USERNAME,
      loginEnvironment.SHENZHOUHR_LOGIN_PASSWORD,
      'admin-login',
    );

    await verifySecurityBoundaries(admin);
    const templates = await loadTemplates(admin);
    const local = await verifyLocalPeopleLifecycle(admin);
    await verifyOrganizationImportRollback(admin, templates.ORGANIZATION);
    await verifyOrganizationImportLocalMaintenance(admin, templates.ORGANIZATION);
    const employeeImport = await verifyEmployeeImportAndDeduplication(
      admin,
      templates.EMPLOYEE,
    );
    await verifyDraftVoid(admin, templates.EMPLOYEE);
    const blockingImport = await verifyFiveCategoryPrecheck(
      admin,
      templates.EMPLOYEE,
      local,
      mysqlDefaultsPath,
    );
    await verifyAudit(admin, local.employeeUnderTest.employeeId);

    const context = {
      runId: runToken,
      generatedAt: new Date().toISOString(),
      companyId: COMPANY_ID,
      organizationId: local.rootOrganization.organizationId,
      organizationCode: local.rootOrganization.code,
      employeeId: local.employeeUnderTest.employeeId,
      employeeNumber: local.employeeUnderTest.employeeNumber,
      importedEmployeeId: employeeImport.employeeId,
      blockingBatchId: blockingImport.batchId,
      blockingBatchStatus: blockingImport.status,
    };
    writeJson(join(runtimeRoot, 'runtime-context.json'), context);
    writeJson(join(runtimeRoot, 'runtime-api-business.json'), results);
    writeTrace(join(runtimeRoot, 'runtime-business-api.tsv'), trace);
    process.stdout.write(`[wave2-runtime-api] PASS trace=${join(runtimeRoot, 'runtime-business-api.tsv')}\n`);
  } catch (error) {
    writeTrace(join(runtimeRoot, 'runtime-business-api.tsv'), trace);
    throw error;
  } finally {
    cleanupTemporaryRoot();
  }
}

async function verifySecurityBoundaries(admin) {
  const unauthenticated = new ApiSession(BASE_URL, trace);
  await unauthenticated.request('unauthenticated-people', '/api/v1/people-imports', {
    expectedStatus: 401,
  });
  await admin.request('unknown-employee', '/api/v1/employees/00000000-0000-0000-0000-000000000099', {
    expectedStatus: 404,
  });
  await admin.request('cross-scope-batch-create', '/api/v1/people-imports', {
    method: 'POST',
    body: {
      companyId: '30000000-0000-0000-0000-000000000099',
      templateType: 'EMPLOYEE',
      templateVersion: '1.0.0',
      reason: 'WAVE-2 越权范围拒绝验证',
    },
    headers: versionHeaders(0, key('cross-scope')),
    expectedStatus: 404,
  });
  await admin.request('csrf-rejected', '/api/v1/people-imports', {
    method: 'POST',
    body: {
      companyId: COMPANY_ID,
      templateType: 'EMPLOYEE',
      templateVersion: '1.0.0',
      reason: 'WAVE-2 CSRF 拒绝验证',
    },
    headers: versionHeaders(0, key('csrf-rejected')),
    includeCsrf: false,
    expectedStatus: 403,
  });

  const roles = (await admin.request('roles-for-restricted-user', '/api/v1/access/roles')).body;
  const restrictedRole = roles.find((role) => !role.capabilities.some((capability) => (
    capability.startsWith('PEOPLE_IMPORT:')
      || capability.startsWith('ORGANIZATION:')
      || capability.startsWith('EMPLOYEE:')
      || capability.startsWith('EMPLOYMENT:')
      || capability.startsWith('PRIOR_SERVICE:')
      || capability === 'MASTER_DATA:READ'
  )));
  assert(restrictedRole, 'no role without WAVE-2 capabilities is available');
  const restrictedPassword = `${randomBytes(8).toString('hex')}Aa9!`;
  const restrictedUsername = `w2_restricted_${runToken}`;
  await admin.request('restricted-account-create', '/api/v1/access/accounts', {
    method: 'POST',
    body: {
      username: restrictedUsername,
      displayName: 'WAVE-2 受限合成账号',
      temporaryPassword: restrictedPassword,
      roleAssignments: [{
        roleId: restrictedRole.roleId,
        scopeType: 'COMPANY',
        scopeResourceId: COMPANY_ID,
        validFrom: new Date(Date.now() - 60_000).toISOString(),
        validTo: null,
      }],
    },
    expectedStatus: 201,
  });
  const restricted = new ApiSession(BASE_URL, trace);
  await restricted.login(restrictedUsername, restrictedPassword, 'restricted-login');
  await restricted.request('capability-denied', '/api/v1/people-imports', {
    expectedStatus: 403,
  });

  results.security = {
    unauthenticatedStatus: 401,
    csrfStatus: 403,
    capabilityStatus: 403,
    resourceNotAvailableStatus: 404,
    crossScopeStatus: 404,
    restrictedRoleCode: restrictedRole.roleCode,
  };
}

async function loadTemplates(admin) {
  const response = await admin.request(
    'template-catalog',
    '/api/v1/people-imports/templates?page=0&size=20',
  );
  assert(response.body.items.length === 4, 'the template catalog must expose four WAVE-2 templates');
  const byType = Object.fromEntries(response.body.items.map((item) => [item.templateType, item]));
  for (const type of ['ORGANIZATION', 'EMPLOYEE', 'EMPLOYMENT', 'PRIOR_SERVICE']) {
    assert(byType[type], `missing ${type} template`);
    assert(byType[type].fields.length > 0, `${type} template has no field instructions`);
  }
  results.probes.openapiTemplateCount = response.body.items.length;
  return byType;
}

async function verifyLocalPeopleLifecycle(admin) {
  const rootOrganization = (await admin.request('local-root-organization-create', '/api/v1/organization-units', {
    method: 'POST',
    body: {
      companyId: COMPANY_ID,
      parentOrganizationId: null,
      code: `W2ROOT${runCode}`,
      name: `WAVE-2 合成总部 ${runCode}`,
      organizationType: 'COMPANY',
      effectiveFrom: '2020-01-01',
      reason: 'WAVE-2 真实 API 本地组织创建',
    },
    headers: versionHeaders(0, key('root-org-create')),
    expectedStatus: 201,
  })).body;
  const childOrganization = (await admin.request('local-child-organization-create', '/api/v1/organization-units', {
    method: 'POST',
    body: {
      companyId: COMPANY_ID,
      parentOrganizationId: rootOrganization.organizationId,
      code: `W2DEPT${runCode}`,
      name: `WAVE-2 合成部门 ${runCode}`,
      organizationType: 'DEPARTMENT',
      effectiveFrom: '2020-01-01',
      reason: 'WAVE-2 真实 API 本地部门创建',
    },
    headers: versionHeaders(0, key('child-org-create')),
    expectedStatus: 201,
  })).body;
  const updatedChild = (await admin.request(
    'local-child-organization-update',
    `/api/v1/organization-units/${childOrganization.organizationId}`,
    {
      method: 'PATCH',
      body: {
        parentOrganizationId: rootOrganization.organizationId,
        code: childOrganization.code,
        name: `${childOrganization.name} 本地版本`,
        organizationType: childOrganization.organizationType,
        status: 'ACTIVE',
        effectiveFrom: '2026-07-26',
        effectiveTo: null,
        reason: 'WAVE-2 组织本地修改创建新版本',
      },
      headers: versionHeaders(childOrganization.rowVersion, key('child-org-update')),
    },
  )).body;
  const staleOrganization = await admin.request(
    'local-child-organization-stale',
    `/api/v1/organization-units/${childOrganization.organizationId}`,
    {
      method: 'PATCH',
      body: {
        parentOrganizationId: rootOrganization.organizationId,
        code: childOrganization.code,
        name: `${childOrganization.name} 过期覆盖`,
        organizationType: childOrganization.organizationType,
        status: 'ACTIVE',
        effectiveFrom: '2026-07-27',
        effectiveTo: null,
        reason: 'WAVE-2 组织过期版本拒绝',
      },
      headers: versionHeaders(childOrganization.rowVersion, key('child-org-stale')),
      expectedStatus: 409,
    },
  );
  assert(staleOrganization.body.code === 'STALE_VERSION', 'stale organization update must return STALE_VERSION');
  const organizationVersions = (await admin.request(
    'local-child-organization-versions',
    `/api/v1/organization-units/${childOrganization.organizationId}/versions?page=0&size=20`,
  )).body;
  assert(organizationVersions.total === 2, 'local organization update must create a second immutable version');
  assert(updatedChild.sourceAuthority === 'LOCAL', 'updated organization must be locally authoritative');

  const employees = {};
  for (const variant of ['UNDER_TEST', 'UNCHANGED', 'CONFLICT_A', 'CONFLICT_B']) {
    employees[variant] = (await admin.request(
      `local-employee-${variant.toLowerCase()}-create`,
      '/api/v1/employees',
      {
        method: 'POST',
        body: {
          companyId: COMPANY_ID,
          employeeNumber: `W2${variant.replaceAll('_', '')}${runCode}`,
          displayName: 'WAVE-2 同名合成员工',
          externalEmployeeId: `EXT-${variant}-${runCode}`,
          effectiveFrom: '2020-01-01',
          reason: `WAVE-2 ${variant} 本地员工创建`,
        },
        headers: versionHeaders(0, key(`employee-${variant}-create`)),
        expectedStatus: 201,
      },
    )).body;
  }
  const originalEmployee = employees.UNDER_TEST;
  const employeeUnderTest = (await admin.request(
    'local-employee-update',
    `/api/v1/employees/${originalEmployee.employeeId}`,
    {
      method: 'PATCH',
      body: {
        employeeNumber: originalEmployee.employeeNumber,
        displayName: 'WAVE-2 本地版本员工',
        status: 'ACTIVE',
        effectiveFrom: '2026-07-26',
        effectiveTo: null,
        reason: 'WAVE-2 员工本地修改创建新版本',
      },
      headers: versionHeaders(originalEmployee.rowVersion, key('employee-update')),
    },
  )).body;
  const employeeVersions = (await admin.request(
    'local-employee-versions',
    `/api/v1/employees/${employeeUnderTest.employeeId}/versions?page=0&size=20`,
  )).body;
  assert(employeeVersions.total === 2, 'local employee update must create a second immutable version');
  assert(employeeUnderTest.sourceAuthority === 'LOCAL', 'updated employee must be locally authoritative');

  let currentEmployee = await getEmployee(admin, employeeUnderTest.employeeId, 'employee-before-first-period');
  const firstPeriod = (await admin.request(
    'employment-first-period',
    `/api/v1/employees/${employeeUnderTest.employeeId}/employment-periods`,
    {
      method: 'POST',
      body: {
        organizationId: rootOrganization.organizationId,
        positionId: null,
        startDate: '2020-01-01',
        terminationDate: '2020-12-31',
        reason: 'WAVE-2 首次任职周期',
      },
      headers: versionHeaders(currentEmployee.rowVersion, key('employment-first')),
      expectedStatus: 201,
    },
  )).body;
  assert(firstPeriod.endExclusive === '2021-01-01', 'termination day must convert to the next endExclusive day');
  currentEmployee = await getEmployee(admin, employeeUnderTest.employeeId, 'employee-before-rehire');
  const rehirePeriod = (await admin.request(
    'employment-rehire-period',
    `/api/v1/employees/${employeeUnderTest.employeeId}/employment-periods`,
    {
      method: 'POST',
      body: {
        organizationId: updatedChild.organizationId,
        positionId: null,
        startDate: '2021-02-01',
        terminationDate: '2026-07-25',
        reason: 'WAVE-2 二次入职创建新任职周期',
      },
      headers: versionHeaders(currentEmployee.rowVersion, key('employment-rehire')),
      expectedStatus: 201,
    },
  )).body;
  assert(rehirePeriod.endExclusive === '2026-07-26', 'rehire termination must retain half-open semantics');
  assert(
    firstPeriod.employmentPeriodId !== rehirePeriod.employmentPeriodId,
    'rehire must create a distinct employment period identity',
  );
  currentEmployee = await getEmployee(admin, employeeUnderTest.employeeId, 'employee-before-overlap');
  const overlap = await admin.request(
    'employment-overlap-rejected',
    `/api/v1/employees/${employeeUnderTest.employeeId}/employment-periods`,
    {
      method: 'POST',
      body: {
        organizationId: rootOrganization.organizationId,
        positionId: null,
        startDate: '2026-01-01',
        terminationDate: null,
        reason: 'WAVE-2 任职重叠拒绝验证',
      },
      headers: versionHeaders(currentEmployee.rowVersion, key('employment-overlap')),
      expectedStatus: 409,
    },
  );
  assert(overlap.body.code === 'EMPLOYMENT_PERIOD_OVERLAP', 'overlap must return EMPLOYMENT_PERIOD_OVERLAP');
  const employmentHistory = (await admin.request(
    'employment-history-query',
    `/api/v1/employees/${employeeUnderTest.employeeId}/employment-periods?page=0&size=20`,
  )).body;
  assert(employmentHistory.total === 2, 'employment history without asOf must retain both periods');
  assert(employmentHistory.items.length === 2, 'employment history page must contain both periods');
  assert(
    new Set(employmentHistory.items.map((period) => period.employmentPeriodId)).size === 2,
    'employment history must expose two distinct period identities',
  );
  assert(
    employmentHistory.items.some(
      (period) => period.employmentPeriodId === firstPeriod.employmentPeriodId,
    )
      && employmentHistory.items.some(
        (period) => period.employmentPeriodId === rehirePeriod.employmentPeriodId,
      ),
    'employment history must contain the original and rehire periods',
  );
  const gap = (await admin.request(
    'employment-gap-query',
    `/api/v1/employees/${employeeUnderTest.employeeId}/employment-periods?asOf=2021-01-15&page=0&size=20`,
  )).body;
  assert(gap.items.length === 0, 'employment gap must not infer organization assignment');
  const terminationDay = (await admin.request(
    'employment-termination-day-query',
    `/api/v1/employees/${employeeUnderTest.employeeId}/employment-periods?asOf=2026-07-25&page=0&size=20`,
  )).body;
  const dayAfterTermination = (await admin.request(
    'employment-day-after-query',
    `/api/v1/employees/${employeeUnderTest.employeeId}/employment-periods?asOf=2026-07-26&page=0&size=20`,
  )).body;
  assert(terminationDay.items.length === 1, 'business termination day must remain inside the period');
  assert(dayAfterTermination.items.length === 0, 'day after termination must be outside the period');

  currentEmployee = await getEmployee(admin, employeeUnderTest.employeeId, 'employee-before-prior-service');
  const priorServiceReason = 'WAVE-2 累计工龄真实调整';
  const adjustment = (await admin.request(
    'prior-service-adjustment',
    `/api/v1/employees/${employeeUnderTest.employeeId}/prior-service-adjustments`,
    {
      method: 'POST',
      body: {
        amountDays: 365,
        businessDate: '2026-07-25',
        reason: priorServiceReason,
      },
      headers: versionHeaders(currentEmployee.rowVersion, key('prior-adjustment')),
      expectedStatus: 201,
    },
  )).body;
  assert(adjustment.amountDays === 365, 'prior-service adjustment must retain the exact 365-day amount');
  assert(adjustment.reason === priorServiceReason, 'prior-service adjustment must retain its reason');
  assert(adjustment.resultingTotalDays === 365, 'prior-service adjustment must produce 365 days');
  currentEmployee = await getEmployee(admin, employeeUnderTest.employeeId, 'employee-before-recalculate');
  const replayOne = (await admin.request(
    'prior-service-recalculate-one',
    `/api/v1/employees/${employeeUnderTest.employeeId}/prior-service/recalculate`,
    {
      method: 'POST',
      body: { reason: 'WAVE-2 累计工龄第一次确定性重算' },
      headers: versionHeaders(currentEmployee.rowVersion, key('prior-recalculate-one')),
    },
  )).body;
  currentEmployee = await getEmployee(admin, employeeUnderTest.employeeId, 'employee-before-second-recalculate');
  const replayTwo = (await admin.request(
    'prior-service-recalculate-two',
    `/api/v1/employees/${employeeUnderTest.employeeId}/prior-service/recalculate`,
    {
      method: 'POST',
      body: { reason: 'WAVE-2 累计工龄第二次确定性重算' },
      headers: versionHeaders(currentEmployee.rowVersion, key('prior-recalculate-two')),
    },
  )).body;
  assert(replayOne.totalDays === 365 && replayTwo.totalDays === 365, 'prior-service replay total must be stable');
  assert(replayOne.replayDigest === replayTwo.replayDigest, 'prior-service replay digest must be reproducible');
  const priorRecords = (await admin.request(
    'prior-service-records',
    `/api/v1/employees/${employeeUnderTest.employeeId}/prior-service-records?page=0&size=20`,
  )).body;
  assert(priorRecords.items.length === 1, 'prior-service ledger must preserve one immutable adjustment');
  assert(priorRecords.items[0].amountDays === 365,
    'prior-service ledger must retain the exact 365-day adjustment');
  assert(priorRecords.items[0].reason === priorServiceReason,
    'prior-service ledger must retain the original adjustment reason');
  assert(priorRecords.items[0].actorId && priorRecords.items[0].occurredAt && priorRecords.items[0].requestId,
    'prior-service ledger must retain actor, time and request correlation');

  results.localMaintenance = {
    rootOrganizationId: rootOrganization.organizationId,
    childOrganizationId: childOrganization.organizationId,
    childOrganizationVersions: organizationVersions.total,
    employeeId: employeeUnderTest.employeeId,
    employeeVersions: employeeVersions.total,
    duplicateDisplayNameEmployeeIds: Object.values(employees).map((employee) => employee.employeeId),
  };
  results.employment = {
    firstPeriodId: firstPeriod.employmentPeriodId,
    rehirePeriodId: rehirePeriod.employmentPeriodId,
    distinctPeriodIds: firstPeriod.employmentPeriodId !== rehirePeriod.employmentPeriodId,
    historyTotalWithoutAsOf: employmentHistory.total,
    firstEndExclusive: firstPeriod.endExclusive,
    rehireEndExclusive: rehirePeriod.endExclusive,
    overlapStatus: overlap.status,
    gapAssignments: gap.items.length,
    terminationDayAssignments: terminationDay.items.length,
    dayAfterTerminationAssignments: dayAfterTermination.items.length,
  };
  results.priorService = {
    recordId: adjustment.priorServiceRecordId,
    amountDays: priorRecords.items[0].amountDays,
    reason: priorRecords.items[0].reason,
    reasonPreserved: priorRecords.items[0].reason === priorServiceReason,
    totalDays: replayTwo.totalDays,
    recordCount: replayTwo.recordCount,
    replayDigest: replayTwo.replayDigest,
    actorRecorded: Boolean(priorRecords.items[0].actorId),
    occurredAtRecorded: Boolean(priorRecords.items[0].occurredAt),
    requestIdRecorded: Boolean(priorRecords.items[0].requestId),
  };
  return {
    rootOrganization,
    childOrganization: updatedChild,
    employeeUnderTest,
    employees,
  };
}

async function verifyOrganizationImportRollback(admin, template) {
  const file = await createWorkbook(admin, template, 'organization-rollback', [{
    '组织编码': `W2IMPORG${runCode}`,
    '组织名称': `WAVE-2 导入后撤销组织 ${runCode}`,
    '上级组织编码': '',
    '组织类型': 'DEPARTMENT',
    '生效日期': '2026-07-25',
  }]);
  const prepared = await prepareImport(admin, template, file, 'organization-rollback');
  assert(prepared.batch.precheckSummary.added === 1, 'organization import must precheck one added row');
  const publication = await publishImport(admin, prepared.batch, key('organization-publish'));
  const publishedBatch = await getBatch(admin, prepared.batch.batchId, 'organization-published-batch');
  const rollback = await admin.request(
    'organization-import-rollback',
    `/api/v1/people-imports/${publishedBatch.batchId}/rollback`,
    {
      method: 'POST',
      body: {
        reason: 'WAVE-2 无引用组织批次受控撤销',
        confirmedPublicationId: publication.publicationId,
      },
      headers: versionHeaders(publishedBatch.rowVersion, key('organization-rollback')),
      expectedStatus: 201,
    },
  );
  assert(rollback.body.createdVersionIds.length > 0, 'controlled rollback must create forward restoration versions');
  results.imports.organizationRollback = {
    batchId: publishedBatch.batchId,
    publicationId: publication.publicationId,
    rollbackId: rollback.body.rollbackId,
    restoredVersionCount: rollback.body.createdVersionIds.length,
  };
}

async function verifyOrganizationImportLocalMaintenance(admin, template) {
  const organizationCode = `W2IMPMAINT${runCode}`;
  const file = await createWorkbook(admin, template, 'organization-local-maintenance', [{
    '组织编码': organizationCode,
    '组织名称': `WAVE-2 导入后本地维护组织 ${runCode}`,
    '上级组织编码': '',
    '组织类型': 'DEPARTMENT',
    '生效日期': '2026-07-25',
  }]);
  const prepared = await prepareImport(
    admin,
    template,
    file,
    'organization-local-maintenance',
  );
  assert(
    prepared.batch.precheckSummary.added === 1,
    'organization local-maintenance import must precheck one added row',
  );
  const publication = await publishImport(
    admin,
    prepared.batch,
    key('organization-local-maintenance-publish'),
  );
  assert(
    publication.deduplicated === false,
    'organization local-maintenance import must create a new publication',
  );
  const publishedBeforeLocalEdit = await getBatch(
    admin,
    prepared.batch.batchId,
    'organization-maintenance-published-batch-before-local-edit',
  );
  assert(
    publishedBeforeLocalEdit.file.sha256 === prepared.batch.file.sha256,
    'organization publication must retain the uploaded file hash',
  );
  assert(
    publishedBeforeLocalEdit.publication.snapshotDigest === publication.snapshotDigest,
    'organization batch detail must expose the published snapshot digest',
  );

  const tree = (await admin.request(
    'imported-organization-query-by-code',
    '/api/v1/organization-units?includeInactive=true',
  )).body;
  const matches = flattenOrganizations(tree)
    .filter((organization) => organization.code === organizationCode);
  assert(matches.length === 1, 'published organization code must resolve to exactly one organization');
  const imported = await getOrganization(
    admin,
    matches[0].organizationId,
    'imported-organization-detail',
  );
  assert(
    imported.sourceAuthority === 'INITIAL_EXCEL',
    'published organization must retain its import authority before local maintenance',
  );
  assert(
    imported.sourceBatchId === prepared.batch.batchId,
    'published organization must retain its source batch before local maintenance',
  );

  const updated = (await admin.request(
    'imported-organization-local-update',
    `/api/v1/organization-units/${imported.organizationId}`,
    {
      method: 'PATCH',
      body: {
        parentOrganizationId: imported.parentOrganizationId,
        code: imported.code,
        name: `${imported.name} 本地权威版本`,
        organizationType: imported.organizationType,
        status: 'ACTIVE',
        effectiveFrom: '2026-07-26',
        effectiveTo: null,
        reason: 'WAVE-2 导入组织发布后本地维护',
      },
      headers: versionHeaders(
        imported.rowVersion,
        key('imported-organization-local-update'),
      ),
    },
  )).body;
  assert(updated.sourceAuthority === 'LOCAL', 'post-publication organization edit must establish local authority');
  assert(updated.sourceBatchId == null, 'locally maintained organization version must not claim an import batch');
  assert(
    updated.organizationVersionId !== imported.organizationVersionId,
    'post-publication organization edit must create a new immutable version',
  );

  const versions = (await admin.request(
    'imported-organization-versions',
    `/api/v1/organization-units/${imported.organizationId}/versions?page=0&size=20`,
  )).body;
  assert(versions.total === 2, 'published organization local edit must produce exactly two versions');
  assert(
    versions.items.some(
      (version) => version.organizationVersionId === imported.organizationVersionId
        && version.sourceAuthority === 'INITIAL_EXCEL'
        && version.sourceBatchId === prepared.batch.batchId,
    ),
    'organization version history must retain the original import provenance',
  );
  assert(
    versions.items.some(
      (version) => version.organizationVersionId === updated.organizationVersionId
        && version.sourceAuthority === 'LOCAL'
        && version.sourceBatchId == null,
    ),
    'organization version history must retain the local successor',
  );

  const audit = (await admin.request(
    'imported-organization-audit-events',
    `/api/v1/access/audit-events?resourceType=ORGANIZATION&resourceId=${imported.organizationId}&page=0&size=100`,
  )).body;
  const versionAuditEvents = audit.items.filter(
    (item) => item.action === 'ORGANIZATION_VERSION_CREATED',
  );
  assert(
    versionAuditEvents.length >= 2,
    'organization import and local edit must both produce version audit events',
  );
  assert(
    versionAuditEvents.every((item) => item.correlationId),
    'organization version audit events must expose correlation IDs',
  );

  const publishedAfterLocalEdit = await getBatch(
    admin,
    prepared.batch.batchId,
    'organization-maintenance-published-batch-after-local-edit',
  );
  assert(
    publishedAfterLocalEdit.file.sha256 === publishedBeforeLocalEdit.file.sha256,
    'organization local maintenance must not mutate the source file hash',
  );
  assert(
    publishedAfterLocalEdit.publication.snapshotDigest
      === publishedBeforeLocalEdit.publication.snapshotDigest,
    'organization local maintenance must not mutate the publication snapshot digest',
  );

  results.imports.organizationLocalMaintenance = {
    batchId: prepared.batch.batchId,
    publicationId: publication.publicationId,
    organizationId: imported.organizationId,
    importedSourceAuthority: imported.sourceAuthority,
    importedSourceBatchId: imported.sourceBatchId,
    localSourceAuthority: updated.sourceAuthority,
    localSourceBatchId: updated.sourceBatchId ?? null,
    versionTotal: versions.total,
    versionAuditEventCount: versionAuditEvents.length,
    fileSha256BeforeLocalEdit: publishedBeforeLocalEdit.file.sha256,
    fileSha256AfterLocalEdit: publishedAfterLocalEdit.file.sha256,
    publicationSnapshotDigestBeforeLocalEdit:
      publishedBeforeLocalEdit.publication.snapshotDigest,
    publicationSnapshotDigestAfterLocalEdit:
      publishedAfterLocalEdit.publication.snapshotDigest,
    fileSha256Preserved:
      publishedAfterLocalEdit.file.sha256 === publishedBeforeLocalEdit.file.sha256,
    publicationSnapshotDigestPreserved:
      publishedAfterLocalEdit.publication.snapshotDigest
        === publishedBeforeLocalEdit.publication.snapshotDigest,
  };
}

async function verifyEmployeeImportAndDeduplication(admin, template) {
  const employeeNumber = `W2IMPEMP${runCode}`;
  const file = await createWorkbook(admin, template, 'employee-clean', [{
    '员工编号': employeeNumber,
    '外部精确员工ID': `EXT-IMPORTED-${runCode}`,
    '姓名': `WAVE-2 导入员工 ${runCode}`,
    '生效日期': '2026-07-25',
  }]);
  const first = await prepareImport(admin, template, file, 'employee-clean-first');
  assert(first.batch.precheckSummary.added === 1, 'clean employee import must add one employee');
  const publishKey = key('employee-publish');
  const firstPublication = await publishImport(admin, first.batch, publishKey);
  assert(firstPublication.deduplicated === false, 'first employee publish must be a new publication');
  const repeated = await publishImport(admin, first.batch, publishKey);
  assert(repeated.deduplicated === true, 'repeated idempotency key must return the original publication');

  const second = await prepareImport(admin, template, file, 'employee-clean-second');
  assert(second.batch.precheckSummary.unchanged === 1, 'same employee workbook must precheck as unchanged after publish');
  const fileDuplicate = await publishImport(admin, second.batch, key('employee-hash-publish'));
  assert(fileDuplicate.deduplicated === true, 'same file hash must deduplicate across batches');
  assert(fileDuplicate.duplicateOfPublicationId === firstPublication.publicationId,
    'file hash deduplication must point to the first publication');

  const search = (await admin.request(
    'imported-employee-search',
    `/api/v1/employees?page=0&size=20&query=${encodeURIComponent(employeeNumber)}`,
  )).body;
  assert(search.total === 1, 'published employee must be visible in the real employee list');
  const imported = await getEmployee(admin, search.items[0].employeeId, 'imported-employee-detail');
  assert(
    imported.sourceAuthority === 'INITIAL_EXCEL',
    'published employee must retain its import authority before local maintenance',
  );
  assert(
    imported.sourceBatchId === first.batch.batchId,
    'published employee must retain its source batch before local maintenance',
  );
  const publishedBeforeLocalEdit = await getBatch(
    admin,
    first.batch.batchId,
    'employee-published-batch-before-local-edit',
  );
  assert(
    publishedBeforeLocalEdit.file.sha256 === first.batch.file.sha256,
    'employee publication must retain the uploaded file hash',
  );
  assert(
    publishedBeforeLocalEdit.publication.snapshotDigest === firstPublication.snapshotDigest,
    'employee batch detail must expose the published snapshot digest',
  );
  const updated = (await admin.request(
    'imported-employee-local-update',
    `/api/v1/employees/${imported.employeeId}`,
    {
      method: 'PATCH',
      body: {
        employeeNumber: imported.employeeNumber,
        displayName: `${imported.displayName} 本地权威版本`,
        status: 'ACTIVE',
        effectiveFrom: '2026-07-26',
        effectiveTo: null,
        reason: 'WAVE-2 发布后本地员工维护',
      },
      headers: versionHeaders(imported.rowVersion, key('imported-employee-update')),
    },
  )).body;
  assert(updated.sourceAuthority === 'LOCAL', 'post-publication employee edit must establish local authority');
  assert(updated.sourceBatchId == null, 'locally maintained employee version must not claim an import batch');

  const publishedBatch = await getBatch(
    admin,
    first.batch.batchId,
    'employee-published-batch-after-local-edit',
  );
  assert(
    publishedBatch.file.sha256 === publishedBeforeLocalEdit.file.sha256,
    'employee local maintenance must not mutate the source file hash',
  );
  assert(
    publishedBatch.publication.snapshotDigest
      === publishedBeforeLocalEdit.publication.snapshotDigest,
    'employee local maintenance must not mutate the publication snapshot digest',
  );
  const rollbackConflict = await admin.request(
    'employee-import-rollback-later-version',
    `/api/v1/people-imports/${publishedBatch.batchId}/rollback`,
    {
      method: 'POST',
      body: {
        reason: 'WAVE-2 后续本地版本阻止撤销',
        confirmedPublicationId: firstPublication.publicationId,
      },
      headers: versionHeaders(publishedBatch.rowVersion, key('employee-rollback-conflict')),
      expectedStatus: 409,
    },
  );
  assert(
    ['PEOPLE_IMPORT_ROLLBACK_NOT_LATEST', 'PEOPLE_IMPORT_ROLLBACK_HAS_REFERENCES']
      .includes(rollbackConflict.body.code),
    'rollback with later versions or references must return a controlled conflict',
  );
  const deletePublished = await admin.request(
    'published-batch-delete-rejected',
    `/api/v1/people-imports/${publishedBatch.batchId}`,
    { method: 'DELETE', expectedStatus: [404, 405], parse: 'text' },
  );

  results.imports.employeePublish = {
    batchId: publishedBatch.batchId,
    publicationId: firstPublication.publicationId,
    employeeId: imported.employeeId,
    importedSourceAuthority: imported.sourceAuthority,
    importedSourceBatchId: imported.sourceBatchId,
    localSourceAuthority: updated.sourceAuthority,
    localSourceBatchId: updated.sourceBatchId ?? null,
    fileSha256BeforeLocalEdit: publishedBeforeLocalEdit.file.sha256,
    fileSha256AfterLocalEdit: publishedBatch.file.sha256,
    publicationSnapshotDigestBeforeLocalEdit:
      publishedBeforeLocalEdit.publication.snapshotDigest,
    publicationSnapshotDigestAfterLocalEdit:
      publishedBatch.publication.snapshotDigest,
    fileSha256Preserved:
      publishedBatch.file.sha256 === publishedBeforeLocalEdit.file.sha256,
    publicationSnapshotDigestPreserved:
      publishedBatch.publication.snapshotDigest
        === publishedBeforeLocalEdit.publication.snapshotDigest,
    initialPublishDeduplicated: firstPublication.deduplicated,
    repeatedIdempotencyDeduplicated: repeated.deduplicated,
    fileHashDeduplicated: fileDuplicate.deduplicated,
    duplicateOfPublicationId: fileDuplicate.duplicateOfPublicationId,
    rollbackConflictStatus: rollbackConflict.status,
    rollbackConflictCode: rollbackConflict.body.code,
    physicalDeleteStatus: deletePublished.status,
  };
  return { employeeId: imported.employeeId };
}

async function verifyDraftVoid(admin, template) {
  const created = await createBatch(admin, template, 'draft-void');
  const voided = (await admin.request(
    'draft-batch-void',
    `/api/v1/people-imports/${created.batchId}/void`,
    {
      method: 'POST',
      body: { reason: 'WAVE-2 草稿作废保留源记录' },
      headers: versionHeaders(created.rowVersion, key('draft-void')),
    },
  )).body;
  assert(voided.status === 'VOIDED', 'draft batch must be voided without physical deletion');
  const persisted = await getBatch(admin, created.batchId, 'voided-batch-still-readable');
  assert(persisted.status === 'VOIDED', 'voided batch must remain queryable');
  results.imports.draftVoid = { batchId: created.batchId, status: persisted.status };
}

async function verifyFiveCategoryPrecheck(admin, template, local, mysqlDefaultsPath) {
  const formalBefore = queryFormalCounts(mysqlDefaultsPath);
  const employee = await getEmployee(admin, local.employeeUnderTest.employeeId, 'five-category-current-employee');
  const unchanged = await getEmployee(admin, local.employees.UNCHANGED.employeeId, 'five-category-unchanged-employee');
  const conflictA = await getEmployee(admin, local.employees.CONFLICT_A.employeeId, 'five-category-conflict-a');
  const conflictB = await getEmployee(admin, local.employees.CONFLICT_B.employeeId, 'five-category-conflict-b');
  const file = await createWorkbook(admin, template, 'employee-five-category', [
    {
      '员工编号': `W2FIVEADD${runCode}`,
      '外部精确员工ID': `EXT-FIVE-ADD-${runCode}`,
      '姓名': `WAVE-2 预检新增 ${runCode}`,
      '生效日期': '2026-08-10',
    },
    {
      '员工编号': employee.employeeNumber,
      '外部精确员工ID': employee.externalEmployeeId,
      '姓名': `${employee.displayName} 预检修改`,
      '生效日期': '2026-08-10',
    },
    {
      '员工编号': unchanged.employeeNumber,
      '外部精确员工ID': unchanged.externalEmployeeId,
      '姓名': unchanged.displayName,
      '生效日期': unchanged.effectiveFrom,
    },
    {
      '员工编号': conflictA.employeeNumber,
      '外部精确员工ID': conflictB.externalEmployeeId,
      '姓名': 'WAVE-2 多人精确键冲突',
      '生效日期': '2026-08-10',
    },
    {
      '员工编号': '',
      '外部精确员工ID': '',
      '姓名': 'WAVE-2 缺少匹配键',
      '生效日期': '2026-08-10',
    },
  ]);
  const prepared = await prepareImport(admin, template, file, 'employee-five-category');
  assert(prepared.batch.status === 'VALIDATION_FAILED', 'blocking precheck must end in VALIDATION_FAILED');
  const summary = prepared.batch.precheckSummary;
  for (const category of ['added', 'updated', 'unchanged', 'conflict', 'error']) {
    assert(summary[category] === 1, `five-category precheck must contain one ${category} row`);
  }
  assert(summary.blockingIssueCount >= 2, 'five-category precheck must retain blocking issues');
  const categoryCounts = {};
  for (const category of ['ADDED', 'UPDATED', 'UNCHANGED', 'CONFLICT', 'ERROR']) {
    const page = (await admin.request(
      `five-category-diff-${category.toLowerCase()}`,
      `/api/v1/people-imports/${prepared.batch.batchId}/diff?page=0&size=20&category=${category}`,
    )).body;
    categoryCounts[category] = page.total;
    assert(page.total === 1, `${category} diff page must contain exactly one row`);
  }
  const issues = (await admin.request(
    'five-category-issues',
    `/api/v1/people-imports/${prepared.batch.batchId}/errors?page=0&size=20`,
  )).body;
  const ambiguous = issues.items.find((issue) => issue.code === 'EMPLOYEE_MATCH_AMBIGUOUS');
  assert(ambiguous?.candidateEmployeeIds?.length === 2,
    'ambiguous exact keys must report both candidate employee IDs');
  const report = await admin.request(
    'five-category-error-report',
    `/api/v1/people-imports/${prepared.batch.batchId}/error-report`,
    { parse: 'buffer' },
  );
  assert(report.body.length > 512, 'error report must be a non-empty real xlsx');
  writeFileSync(join(fixturesRoot, 'employee-five-category-errors.xlsx'), report.body);
  const blockedPublish = await admin.request(
    'five-category-publish-blocked',
    `/api/v1/people-imports/${prepared.batch.batchId}/publish`,
    {
      method: 'POST',
      body: {
        reason: 'WAVE-2 阻断错误禁止发布',
        confirmedFileSha256: prepared.batch.file.sha256,
        confirmedPrecheckVersion: prepared.batch.precheckVersion,
      },
      headers: versionHeaders(prepared.batch.rowVersion, key('five-category-publish')),
      expectedStatus: 409,
    },
  );
  const formalAfter = queryFormalCounts(mysqlDefaultsPath);
  assert(JSON.stringify(formalBefore) === JSON.stringify(formalAfter),
    'precheck must not write formal organization, employee, employment or prior-service tables');
  results.formalCounts = { beforePrecheck: formalBefore, afterPrecheck: formalAfter };
  results.imports.fiveCategory = {
    batchId: prepared.batch.batchId,
    status: prepared.batch.status,
    summary,
    categoryCounts,
    issueCount: issues.total,
    ambiguousCandidateCount: ambiguous.candidateEmployeeIds.length,
    errorReportBytes: report.body.length,
    blockedPublishStatus: blockedPublish.status,
    blockedPublishCode: blockedPublish.body.code,
  };
  return prepared.batch;
}

async function verifyAudit(admin, employeeId) {
  const audit = (await admin.request(
    'employee-audit-events',
    `/api/v1/access/audit-events?resourceType=EMPLOYEE&resourceId=${employeeId}&page=0&size=100`,
  )).body;
  const actions = new Set(audit.items.map((item) => item.action));
  for (const expected of ['EMPLOYEE_VERSION_CREATED', 'PRIOR_SERVICE_ADJUSTED']) {
    assert(actions.has(expected), `audit trail is missing ${expected}`);
  }
  assert(audit.items.every((item) => item.correlationId), 'every audit item must expose a correlation ID');
  results.audit = {
    employeeId,
    total: audit.total,
    actions: [...actions].sort(),
    correlationIdsPresent: audit.items.every((item) => Boolean(item.correlationId)),
  };
}

async function createWorkbook(admin, template, stem, rows) {
  const templatePath = join(fixturesRoot, `template-${template.templateType.toLowerCase()}-${template.templateVersion}.xlsx`);
  if (!createdTemplatePaths.has(templatePath)) {
    const download = await admin.request(
      `download-${template.templateType.toLowerCase()}-template`,
      `/api/v1/people-imports/templates/${template.templateType}/versions/${template.templateVersion}`,
      { parse: 'buffer' },
    );
    assert(download.body.length > 512, `${template.templateType} template download is empty`);
    writeFileSync(templatePath, download.body);
    createdTemplatePaths.add(templatePath);
  }
  const outputPath = join(fixturesRoot, `${stem}.xlsx`);
  const rowsPath = join(temporaryRoot, `${stem}-rows.json`);
  writeFileSync(rowsPath, `${JSON.stringify(rows)}\n`, { mode: 0o600 });
  const build = spawnSync('python3', [
    join(repositoryRoot, 'scripts/qa/build-wave2-workbook.py'),
    '--template', templatePath,
    '--output', outputPath,
    '--rows-json', rowsPath,
  ], {
    encoding: 'utf8',
    timeout: SUBPROCESS_TIMEOUT_MS,
    killSignal: 'SIGKILL',
    maxBuffer: 1024 * 1024,
  });
  if (build.error) {
    throw new Error(`workbook builder could not complete for ${stem}`);
  }
  if (build.status !== 0) {
    throw new Error(`workbook builder failed for ${stem} with exit code ${build.status}`);
  }
  return outputPath;
}

async function prepareImport(admin, template, filePath, label) {
  let batch = await createBatch(admin, template, label);
  const form = new FormData();
  form.set('file', new Blob([readFileSync(filePath)], { type: XLSX_MEDIA_TYPE }), `${label}.xlsx`);
  const upload = await admin.request(
    `${label}-upload`,
    `/api/v1/people-imports/${batch.batchId}/file`,
    {
      method: 'PUT',
      body: form,
      headers: versionHeaders(batch.rowVersion, key(`${label}-upload`)),
    },
  );
  batch = await getBatch(admin, batch.batchId, `${label}-after-upload`);
  assert(batch.file.sha256 === upload.body.sha256, `${label} uploaded hash must match batch detail`);
  batch = (await admin.request(
    `${label}-mapping`,
    `/api/v1/people-imports/${batch.batchId}/mapping`,
    {
      method: 'PUT',
      body: {
        entries: template.fields.map((field) => ({
          sourceColumn: field.label,
          targetField: field.key,
        })),
        reason: `WAVE-2 ${label} 字段映射`,
      },
      headers: versionHeaders(batch.rowVersion, key(`${label}-mapping`)),
    },
  )).body;
  batch = (await admin.request(
    `${label}-precheck`,
    `/api/v1/people-imports/${batch.batchId}/precheck`,
    {
      method: 'POST',
      headers: versionHeaders(batch.rowVersion, key(`${label}-precheck`)),
      expectedStatus: 202,
    },
  )).body;
  assert(['AWAITING_CONFIRMATION', 'VALIDATION_FAILED'].includes(batch.status),
    `${label} precheck returned an unexpected status`);
  return { batch, filePath };
}

async function createBatch(admin, template, label) {
  return (await admin.request(`${label}-batch-create`, '/api/v1/people-imports', {
    method: 'POST',
    body: {
      companyId: COMPANY_ID,
      templateType: template.templateType,
      templateVersion: template.templateVersion,
      reason: `WAVE-2 ${label} 真实批次`,
    },
    headers: versionHeaders(0, key(`${label}-batch-create`)),
    expectedStatus: 201,
  })).body;
}

async function publishImport(admin, batch, idempotencyKey) {
  return (await admin.request(
    `publish-${batch.batchId}-${idempotencyKey.slice(-8)}`,
    `/api/v1/people-imports/${batch.batchId}/publish`,
    {
      method: 'POST',
      body: {
        reason: 'WAVE-2 真实预检确认发布',
        confirmedFileSha256: batch.file.sha256,
        confirmedPrecheckVersion: batch.precheckVersion,
      },
      headers: versionHeaders(batch.rowVersion, idempotencyKey),
    },
  )).body;
}

async function getBatch(admin, batchId, label) {
  return (await admin.request(label, `/api/v1/people-imports/${batchId}`)).body;
}

async function getOrganization(admin, organizationId, label) {
  return (await admin.request(label, `/api/v1/organization-units/${organizationId}`)).body;
}

async function getEmployee(admin, employeeId, label) {
  return (await admin.request(label, `/api/v1/employees/${employeeId}`)).body;
}

function flattenOrganizations(nodes) {
  return nodes.flatMap((node) => [
    node,
    ...flattenOrganizations(node.children ?? []),
  ]);
}

class ApiSession {
  constructor(baseUrl, traceRows) {
    this.baseUrl = baseUrl;
    this.traceRows = traceRows;
    this.cookie = undefined;
    this.csrfToken = undefined;
  }

  async login(username, password, label) {
    const response = await fetch(`${this.baseUrl}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ username, password }),
      signal: AbortSignal.timeout(HTTP_TIMEOUT_MS),
    });
    const body = await response.json();
    recordTrace(this.traceRows, label, 'POST', '/api/v1/auth/login', response, body);
    assert(response.status === 200, `${label} returned ${response.status}`);
    this.cookie = response.headers.get('set-cookie')?.split(';', 1)[0];
    this.csrfToken = response.headers.get('x-csrf-token');
    assert(this.cookie && this.csrfToken, `${label} did not establish cookie and CSRF state`);
    return body;
  }

  async request(label, path, options = {}) {
    const method = options.method ?? 'GET';
    const headers = new Headers(options.headers ?? {});
    headers.set('Accept', options.parse === 'buffer' ? XLSX_MEDIA_TYPE : 'application/json');
    if (this.cookie) headers.set('Cookie', this.cookie);
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)
        && options.includeCsrf !== false
        && this.csrfToken) {
      headers.set('X-CSRF-TOKEN', this.csrfToken);
    }
    let body = options.body;
    if (body !== undefined && !(body instanceof FormData)) {
      headers.set('Content-Type', 'application/json');
      body = JSON.stringify(body);
    }
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body,
      signal: AbortSignal.timeout(HTTP_TIMEOUT_MS),
    });
    const parsed = await parseResponse(response, options.parse);
    recordTrace(this.traceRows, label, method, path, response, parsed);
    const expected = Array.isArray(options.expectedStatus)
      ? options.expectedStatus
      : [options.expectedStatus ?? 200];
    if (!expected.includes(response.status)) {
      const code = parsed && typeof parsed === 'object' && !Buffer.isBuffer(parsed)
        ? parsed.code
        : undefined;
      throw new Error(`${label} returned ${response.status}${code ? ` ${code}` : ''}; expected ${expected.join('/')}`);
    }
    const receivedCsrf = response.headers.get('x-csrf-token');
    if (receivedCsrf) this.csrfToken = receivedCsrf;
    return {
      status: response.status,
      body: parsed,
      etag: response.headers.get('etag'),
      correlationId: response.headers.get('x-correlation-id'),
    };
  }
}

async function parseResponse(response, requested) {
  if (requested === 'buffer') return Buffer.from(await response.arrayBuffer());
  if (requested === 'text') return response.text();
  if (response.status === 204) return null;
  const contentType = response.headers.get('content-type') ?? '';
  return contentType.includes('json') ? response.json() : response.text();
}

function recordTrace(rows, label, method, path, response, body) {
  rows.push({
    label,
    method,
    path,
    status: response.status,
    code: body && typeof body === 'object' && !Buffer.isBuffer(body) ? body.code ?? '' : '',
    correlationId: response.headers.get('x-correlation-id') ?? '',
  });
}

function queryFormalCounts(defaultsPath) {
  const tables = [
    'organization_current_projection',
    'organization_version',
    'employee_current_projection',
    'employee_version',
    'employment_assignment',
    'prior_service_record',
  ];
  const sql = tables.map((table) => `SELECT '${table}', COUNT(*) FROM ${table}`).join(' UNION ALL ');
  const output = mysqlQuery(defaultsPath, sql);
  return Object.fromEntries(output.trim().split(/\r?\n/).filter(Boolean).map((line) => {
    const [table, count] = line.split('\t');
    return [table, Number(count)];
  }));
}

function createMysqlDefaultsFile(environment, directory) {
  const path = join(directory, 'dev-app.cnf');
  const contents = [
    '[client]',
    `host="${mysqlOptionEscape(environment.SHENZHOUHR_MYSQL_HOST)}"`,
    `port="${mysqlOptionEscape(environment.SHENZHOUHR_MYSQL_PORT)}"`,
    'protocol=tcp',
    'user="shenzhou_hr_dev_app"',
    `password="${mysqlOptionEscape(environment.SHENZHOUHR_DEV_DB_PASSWORD)}"`,
    '',
  ].join('\n');
  writeFileSync(path, contents, { mode: 0o600 });
  chmodSync(path, 0o600);
  return path;
}

function mysqlQuery(defaultsPath, sql) {
  const binary = runtimeEnvironment.SHENZHOUHR_MYSQL_CLIENT_BIN || 'mysql';
  const result = spawnSync(binary, [
    `--defaults-extra-file=${defaultsPath}`,
    '--batch',
    '--skip-column-names',
    '--database=shenzhou_hr_dev',
    '--execute',
    sql,
  ], {
    encoding: 'utf8',
    timeout: SUBPROCESS_TIMEOUT_MS,
    killSignal: 'SIGKILL',
    maxBuffer: 1024 * 1024,
  });
  if (result.error) {
    throw new Error('MySQL evidence query could not complete');
  }
  if (result.status !== 0) {
    throw new Error(`MySQL evidence query failed with exit code ${result.status}`);
  }
  return result.stdout;
}

function mysqlOptionEscape(value) {
  return value
    .replaceAll('\\', '\\\\')
    .replaceAll('"', '\\"')
    .replaceAll('\t', '\\t')
    .replaceAll('\r', '\\r')
    .replaceAll('\n', '\\n');
}

function versionHeaders(rowVersion, idempotencyKey) {
  return {
    'If-Match': `"${rowVersion}"`,
    'Idempotency-Key': idempotencyKey,
  };
}

function key(scope) {
  return `wave2.acceptance.${scope.replace(/[^A-Za-z0-9._:-]/g, '-')}.${runToken}`.slice(0, 128);
}

function readPrivateEnvironment(path, allowedKeys, requiredKeys) {
  if (!isAbsolute(path)) {
    throw new Error('environment paths must be absolute');
  }
  const requested = lstatSync(path);
  if (!requested.isFile() || requested.isSymbolicLink()) {
    throw new Error('environment files must be regular files, not symbolic links');
  }
  const resolvedPath = realpathSync(path);
  if (resolvedPath === repositoryRoot || resolvedPath.startsWith(`${repositoryRoot}${sep}`)) {
    throw new Error('environment files must be outside the repository');
  }
  const metadata = statSync(resolvedPath);
  if ((metadata.mode & 0o777) !== 0o600) {
    throw new Error('environment file mode must be exactly 0600');
  }
  if (typeof process.getuid === 'function' && metadata.uid !== process.getuid()) {
    throw new Error('environment files must be owned by the current user');
  }
  if (metadata.size > 64 * 1024) {
    throw new Error('environment files must be smaller than 64 KiB');
  }

  const allowed = new Set(allowedKeys);
  const environment = {};
  for (const [index, rawLine] of readFileSync(resolvedPath, 'utf8').split(/\r?\n/).entries()) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
    if (!line || line.trimStart().startsWith('#')) continue;
    const separatorIndex = line.indexOf('=');
    if (separatorIndex < 1) {
      throw new Error(`invalid environment entry at line ${index + 1}`);
    }
    const key = line.slice(0, separatorIndex);
    const value = line.slice(separatorIndex + 1);
    if (!/^[A-Z][A-Z0-9_]*$/.test(key) || !allowed.has(key)) {
      throw new Error(`unsupported environment key at line ${index + 1}`);
    }
    if (Object.hasOwn(environment, key)) {
      throw new Error(`duplicate environment key at line ${index + 1}`);
    }
    if (value.includes('\0')) {
      throw new Error(`invalid environment value at line ${index + 1}`);
    }
    environment[key] = value;
  }
  for (const key of requiredKeys) {
    if (!environment[key]) {
      throw new Error(`required environment key is missing: ${key}`);
    }
  }
  return environment;
}

function parseArguments(args) {
  const allowedNames = new Set(['runtime-env', 'login-env']);
  const parsed = new Map();
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index];
    const value = args[index + 1];
    if (!name?.startsWith('--') || value === undefined) {
      throw new Error('arguments must be supplied as --name value pairs');
    }
    const normalizedName = name.slice(2);
    if (!allowedNames.has(normalizedName)) {
      throw new Error(`unsupported argument: --${normalizedName}`);
    }
    if (parsed.has(normalizedName)) {
      throw new Error(`duplicate argument: --${normalizedName}`);
    }
    parsed.set(normalizedName, value);
  }
  return parsed;
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function writeTrace(path, rows) {
  const header = 'probe\tmethod\tpath\tstatus\tcode\tcorrelation_id\n';
  const body = rows.map((row) => [
    row.label,
    row.method,
    row.path,
    row.status,
    row.code,
    row.correlationId,
  ].join('\t')).join('\n');
  writeFileSync(path, `${header}${body}\n`);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

await main();
