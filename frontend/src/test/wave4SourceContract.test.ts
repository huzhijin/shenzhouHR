import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const sourceRoot = resolve(process.cwd(), 'src');

describe('WAVE-4 frontend source contract', () => {
  it('uses only versioned W4 runtime routes and checks demo mode before fetch', () => {
    const sourceApi = source('features/attendanceSources/attendanceSourceApi.ts');
    const importApi = source('features/punchImport/punchImportApi.ts');

    expect(sourceApi).toContain('/api/v1/attendance-sources');
    expect(importApi).toContain('/api/v1/attendance-punch-imports');
    expect(`${sourceApi}\n${importApi}`).not.toMatch(/['"]\/api\/(?!v1\/)/);
    expect(sourceApi).toContain('isDemoMode');
    expect(importApi).toContain('isDemoMode');
  });

  it('keeps W5 calculations, payroll and organization synchronization out of W4', () => {
    const files = [
      'features/attendanceSources/attendanceSourceApi.ts',
      'features/attendanceSources/attendanceSourceDemo.ts',
      'features/punchImport/punchImportApi.ts',
      'features/punchImport/punchImportDemo.ts',
    ].map(source).join('\n');

    expect(files).not.toMatch(
      /\/(?:payroll|attendance-daily-results|attendance-monthly-results|month-close|organization-sync)/i,
    );
  });

  it('uses server capability strings rather than role names for W4 affordances', () => {
    const pages = [
      'features/attendanceSources/SourceJobsPage.tsx',
      'features/punchImport/PunchImportsPage.tsx',
      'features/punchImport/PunchImportDetailPage.tsx',
    ].map(source).join('\n');

    expect(pages).toContain('ATTENDANCE_SOURCE:RETRY');
    expect(pages).toContain('ATTENDANCE_PUNCH_IMPORT:PARTIAL_PUBLISH');
    expect(pages).not.toMatch(/HR_ADMIN|AUDITOR|SYSTEM_ADMIN/);
  });

  it('keeps synthetic fixtures visibly synthetic and out of normal endpoint selection', () => {
    const sourceDemo = source('features/attendanceSources/attendanceSourceDemo.ts');
    const importDemo = source('features/punchImport/punchImportDemo.ts');

    expect(`${sourceDemo}\n${importDemo}`).toContain('synthetic');
    expect(`${sourceDemo}\n${importDemo}`).not.toMatch(/真实员工|生产员工|身份证号/);
  });
});

function source(relativePath: string): string {
  return readFileSync(resolve(sourceRoot, relativePath), 'utf8');
}
