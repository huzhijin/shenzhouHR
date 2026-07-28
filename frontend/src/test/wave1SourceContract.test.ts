import {
  readFileSync,
  readdirSync,
  statSync,
} from 'node:fs';
import { extname, join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const frontendRoot = resolve(process.cwd());
const sourceRoot = join(frontendRoot, 'src');
const productionFiles = collectFiles(sourceRoot)
  .filter((path) => ['.ts', '.tsx', '.css'].includes(extname(path)))
  .filter((path) => !path.includes('.test.'))
  .filter((path) => !path.includes(`${join('src', 'test')}${process.platform === 'win32' ? '\\' : '/'}`));

const productionSource = productionFiles
  .filter((path) => extname(path) !== '.css')
  .map(read)
  .join('\n');
const operationalSource = productionFiles
  .filter((path) => extname(path) !== '.css')
  .filter((path) => !/(?:^|[/\\])demo[^/\\]*\.(?:ts|tsx)$/.test(path))
  .map(read)
  .join('\n');
const componentSource = productionFiles
  .filter((path) => path.endsWith('.tsx'))
  .map(read)
  .join('\n');
const styleSource = productionFiles
  .filter((path) => path.endsWith('.css'))
  .map(read)
  .join('\n');
const visibleCopySource = [
  read(join(frontendRoot, 'index.html')),
  read(join(sourceRoot, 'shared', 'i18n', 'messages.ts')),
  componentSource,
].join('\n');
const normalizedOperationalSource = normalize(operationalSource);

describe('WAVE-1 production route contract', () => {
  const requiredRoutes = [
    '/login',
    '/rules',
    '/rules/templates',
    '/rules/templates/:templateId',
    '/rules/templates/:templateId/versions/:versionId',
    '/access/accounts',
    '/access/accounts/:accountId',
    '/access/roles',
    '/access/audit',
    '/access/audit/:auditEventId',
  ];

  it.each(requiredRoutes)('registers production route %s', (route) => {
    expect(hasQuotedLiteral(operationalSource, route)).toBe(true);
  });

  it('keeps all WAVE-1 data access on the versioned API surface', () => {
    const requiredApiPaths = [
      '/api/v1/auth/login',
      '/api/v1/auth/session',
      '/api/v1/auth/logout',
      '/api/v1/access/accounts',
      '/api/v1/access/roles',
      '/api/v1/access/audit-events',
      '/api/v1/policy-templates',
    ];

    for (const path of requiredApiPaths) {
      expect(
        operationalSource.includes(path),
        `missing real API path ${path}`,
      ).toBe(true);
    }
  });
});

describe('WAVE-1 authentication state contract', () => {
  it.each([
    ['wrong password', ['INVALID_CREDENTIALS', 'AUTHENTICATION_FAILED']],
    ['locked', ['ACCOUNT_LOCKED', 'LOCKED']],
    ['first password change', ['FIRST_PASSWORD_CHANGE_REQUIRED', 'FIRST_CHANGE_REQUIRED']],
    ['session expired', ['SESSION_EXPIRED']],
  ])('implements %s as an explicit server-driven state', (_name, acceptedTerms) => {
    expect(acceptedTerms.some((term) => operationalSource.includes(term))).toBe(true);
  });

  it('uses one safe visible login failure message for unknown and known usernames', () => {
    expect(normalize(visibleCopySource)).toContain(normalize('用户名或密码错误'));
    expect(normalize(visibleCopySource)).not.toContain(normalize('用户名不存在'));
  });

  it('ships labelled username and password controls with linked validation text', () => {
    expect(componentSource).toMatch(/(?:<label\b[^>]*htmlFor=|<Form\.Item\b[^>]*label=)/);
    expect(componentSource).toMatch(/aria-describedby=/);
    expect(componentSource).toMatch(/autoComplete=(?:["'])username(?:["'])/);
    expect(componentSource).toMatch(/autoComplete=(?:["'])current-password(?:["'])/);
  });
});

describe('WAVE-1 asynchronous and domain state contract', () => {
  it.each([
    'loading',
    'partial-loading',
    'empty',
    'error',
    'network-error',
    '401',
    '403',
    '404',
    'conflict',
    'validation-error',
    'processing',
    'success',
    'retry',
    'stale',
  ])('implements shared state %s', (state) => {
    expect(hasStateContract(operationalSource, normalizedOperationalSource, state)).toBe(true);
  });

  it.each([
    'draft',
    'invalid',
    'scope-conflict',
    'impact-preview',
    'published',
    'inactive',
    'rollback',
    'optimistic-lock-conflict',
  ])('implements policy state %s', (state) => {
    expect(hasStateContract(operationalSource, normalizedOperationalSource, state)).toBe(true);
  });

  it.each([
    'active',
    'disabled',
    'locked',
    'reset-pending',
    'first-change-required',
    'session-revoked',
  ])('implements account state %s', (state) => {
    expect(hasStateContract(operationalSource, normalizedOperationalSource, state)).toBe(true);
  });
});

describe('WAVE-1 capability, demo isolation, and excluded-surface contract', () => {
  it.each([
    'ACCOUNT:READ',
    'ACCOUNT:CREATE',
    'ACCOUNT:EDIT',
    'ACCOUNT:LOCK',
    'ACCOUNT:UNLOCK',
    'ACCOUNT:RESET_PASSWORD',
    'ROLE:READ',
    'ROLE:ASSIGN',
    'AUDIT:READ',
    'POLICY:READ',
    'POLICY:CREATE',
    'POLICY:EDIT',
    'POLICY:VALIDATE',
    'POLICY:SIMULATE',
    'POLICY:PUBLISH',
    'POLICY:DEACTIVATE',
    'POLICY:ROLLBACK',
  ])('recognizes server capability %s', (capability) => {
    expect(operationalSource.includes(capability)).toBe(true);
  });

  it('does not expose payroll language or routes in user-visible frontend surfaces', () => {
    expect(visibleCopySource).not.toMatch(/薪资|工资条|工资核算|\/payroll\b|\/payslips\b/i);
  });

  it('keeps every feature API with a demo branch isolated from requestJson', () => {
    const featureApiFiles = productionFiles.filter((path) => (
      /Api\.ts$/.test(path) && read(path).includes('requestJson')
    ));

    expect(featureApiFiles.length).toBeGreaterThanOrEqual(4);
    for (const path of featureApiFiles) {
      expect(
        read(path),
        `${relative(frontendRoot, path)} must short-circuit demo mode before requestJson`,
      ).toContain('isDemoMode');
    }
  });

  it('never persists passwords, session tokens, reset tokens, or CSRF tokens in browser storage', () => {
    const storageCalls = productionSource.match(/(?:localStorage|sessionStorage)\.(?:setItem|getItem)\([^)]*\)/g) ?? [];
    const sensitiveStorageCall = storageCalls.find((call) => (
      /password|session|reset|csrf|token/i.test(call)
    ));

    expect(sensitiveStorageCall).toBeUndefined();
  });
});

describe('WAVE-1 keyboard, focus, and 390px responsive contract', () => {
  it('keeps a visible keyboard focus indicator and a skip link to main content', () => {
    expect(styleSource).toContain(':focus-visible');
    expect(styleSource).toMatch(/outline:\s*var\(--border-width-focus\)/);
    expect(componentSource).toMatch(/className=(?:["'])skip-link(?:["'])/);
    expect(componentSource).toMatch(/href=(?:["'])#main-content(?:["'])/);
  });

  it('uses semantic dialogs and live regions for blocking and result feedback', () => {
    expect(componentSource).toMatch(/(?:<Modal\b|<Dialog\b|role=(?:["'])dialog(?:["']))/);
    expect(componentSource).toMatch(/aria-live=(?:["'])(?:polite|assertive)(?:["'])/);
  });

  it('switches tablet navigation to a drawer at 1024px or below', () => {
    const tabletRule = mediaRule(styleSource, 1024);
    expect(tabletRule).toMatch(/\.app-sidebar[\s\S]*display:\s*none/);
    expect(tabletRule).toMatch(/\.mobile-menu-trigger[\s\S]*display:\s*inline-flex/);
  });

  it('converts dense tables to labelled records on a 390px viewport contract', () => {
    const mobileRule = mediaRule(styleSource, 430);
    expect(mobileRule).toMatch(/(?:table|responsive-data|mobile-card)[\s\S]*display:\s*block/);
    expect(mobileRule).toMatch(/(?:data-label|mobile-card|record-card)/);
  });

  it('prevents page-level horizontal scrolling without removing controlled component overflow', () => {
    expect(styleSource).toMatch(/(?:html|body|#root)[^{]*\{[^}]*overflow-x:\s*(?:clip|hidden)/);
    expect(styleSource).toMatch(/(?:table-scroll|data-table-scroll)[^{]*\{[^}]*overflow-x:\s*auto/);
  });

  it('uses the approved touch target and reduced-motion contracts', () => {
    expect(styleSource).toContain('--size-touch-target: 44px');
    expect(styleSource).toMatch(/min-(?:height|block-size):\s*var\(--size-touch-target\)/);
    expect(styleSource).toContain('@media (prefers-reduced-motion: reduce)');
  });
});

function collectFiles(directory: string): string[] {
  return readdirSync(directory)
    .flatMap((name) => {
      const path = join(directory, name);
      return statSync(path).isDirectory() ? collectFiles(path) : [path];
    });
}

function read(path: string): string {
  return readFileSync(path, 'utf8');
}

function hasQuotedLiteral(source: string, literal: string): boolean {
  return [`'${literal}'`, `"${literal}"`, `\`${literal}\``]
    .some((candidate) => source.includes(candidate));
}

function normalize(value: string): string {
  return value.toLowerCase().replaceAll(/[^a-z0-9\u4e00-\u9fff]/g, '');
}

function hasStateContract(
  source: string,
  normalizedSource: string,
  state: string,
): boolean {
  const snakeCase = state.replaceAll('-', '_');
  const stateVariants = [
    state,
    snakeCase,
    snakeCase.toUpperCase(),
  ];
  if (stateVariants.some((variant) => hasQuotedLiteral(source, variant))) {
    return true;
  }
  if (/^\d{3}$/.test(state)) {
    return new RegExp(`(?:status|httpStatus)\\s*={0,2}\\s*${state}\\b`).test(source);
  }
  return normalizedSource.includes(normalize(`data-state=${state}`));
}

function mediaRule(source: string, maxWidth: number): string {
  const marker = new RegExp(`@media\\s*\\(max-width:\\s*${maxWidth}px\\s*\\)`);
  const start = source.search(marker);
  if (start < 0) {
    return '';
  }
  const next = source.indexOf('@media', start + 1);
  return source.slice(start, next < 0 ? undefined : next);
}
