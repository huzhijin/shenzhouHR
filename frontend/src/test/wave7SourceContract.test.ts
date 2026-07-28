import {
  readFileSync,
  readdirSync,
  statSync,
} from 'node:fs';
import { extname, join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const sourceRoot = resolve(process.cwd(), 'src');
const wave7Root = join(sourceRoot, 'features/wave7');
const productionFiles = collectFiles(wave7Root)
  .filter((path) => ['.ts', '.tsx'].includes(extname(path)))
  .filter((path) => !path.includes('.test.'));
const productionSource = productionFiles.map(read).join('\n');
const routeSource = read(join(sourceRoot, 'app/routeAuthorization.ts'));
const appSource = read(join(sourceRoot, 'app/App.tsx'));
const styles = read(join(sourceRoot, 'styles/global.css'));

describe('WAVE-7 frontend source contract', () => {
  it('registers only the production Wave 7 route contract', () => {
    for (const route of [
      '/workbench',
      '/attendance/reports',
      '/me/today',
      '/me/records',
      '/me/leave',
      '/me/feedback',
    ]) {
      expect(`${routeSource}\n${appSource}`, `missing ${route}`).toContain(`'${route}'`);
    }
    expect(appSource).not.toMatch(/\/qa\/handoff|EMP-DEMO|ATT-EVENT-DEMO|ATT-XLS-DEMO/);
  });

  it('keeps fixtures isolated while the formal report gateway uses same-origin API clients', () => {
    expect(productionSource).not.toMatch(/test\/fixtures|ContractFixtures|localStorage|isDemoMode|getDemo/i);
    const gatewaySource = read(join(wave7Root, 'wave7Gateway.ts'));
    expect(gatewaySource).toContain('WAVE7_UPSTREAM_PENDING');
    expect(gatewaySource).toContain('requestJson');
    expect(gatewaySource).toContain('requestFile');
    expect(gatewaySource).toContain('/api/v1/attendance-reports');
    expect(gatewaySource).toContain('/exports');
    expect(gatewaySource).not.toMatch(/\bfetch\(/);
  });

  it('keeps formal export passwords out of browser persistence and application logs', () => {
    expect(productionSource).not.toMatch(
      /localStorage|sessionStorage|console\.(?:log|info|warn|error)/,
    );
  });

  it('keeps excluded sensitive and compensation discovery fields out of Wave 7 production', () => {
    expect(productionSource).not.toMatch(
      /pay[\s._/-]*roll|pay[\s._/-]*slips?|薪资|工资(?:条|核算)?|preciseCoordinates|rawPunchId|sensitiveLeaveReason/i,
    );
  });

  it('uses semantic tokens and responsive table-to-card/mobile navigation rules', () => {
    expect(productionSource).not.toMatch(/#[0-9a-f]{3,8}\b/i);
    expect(styles).toContain('.wave7-self-navigation');
    expect(styles).toContain('.wave7-metric-grid');
    expect(styles).toContain('@media (max-width: 430px)');
    expect(styles).toContain('var(--size-touch-target)');
    expect(styles).toContain('var(--layout-safe-area-bottom)');
    expect(styles).not.toMatch(/\.wave7[^}]*#[0-9a-f]{3,8}\b/is);
  });

  it('keeps employee identity out of Wave 7 gateway method arguments', () => {
    const gatewaySource = read(join(wave7Root, 'wave7Gateway.ts'));
    for (const method of [
      'loadToday()',
      'loadRecords()',
      'loadLeave()',
      'loadFeedback()',
    ]) {
      expect(gatewaySource).toContain(method);
    }
    expect(gatewaySource).not.toMatch(/employeeId|employeeNo|employee_id/i);
  });
});

function collectFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    return statSync(path).isDirectory() ? collectFiles(path) : [path];
  });
}

function read(path: string): string {
  return readFileSync(path, 'utf8');
}
