import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const sourceRoot = resolve(process.cwd(), 'src');

describe('WAVE-3 frontend source contract', () => {
  it('keeps runtime routes under /api/v1 and demo checks before business requests', () => {
    const api = source('features/attendanceSetup/attendanceSetupApi.ts');

    expect(api).toContain("const basePath = '/api/v1/attendance-setup'");
    expect(api).not.toContain("'/api/attendance-setup");
    expect(api.match(/if \(isDemoMode\(\)\)/g)?.length).toBeGreaterThanOrEqual(18);
  });

  it('uses semantic design tokens, real icon components and responsive mobile layouts', () => {
    const featureSources = [
      'AttendanceGroupsPage.tsx',
      'ShiftsPage.tsx',
      'CalendarsPage.tsx',
      'AttendancePolicyPage.tsx',
      'AttendanceGroupDialogs.tsx',
      'ShiftDialogs.tsx',
      'CalendarDialogs.tsx',
      'PolicyBindingDialog.tsx',
      'PolicySimulationPanel.tsx',
    ].map((file) => source(`features/attendanceSetup/${file}`)).join('\n');
    const styles = source('styles/global.css');

    expect(featureSources).not.toMatch(/#[0-9a-f]{3,8}\b/i);
    expect(featureSources).not.toMatch(/style=\{\{/);
    expect(featureSources).toContain('@tabler/icons-react');
    expect(styles).toContain('@media (max-width: 430px)');
    expect(styles).toContain('var(--color-');
    expect(styles).toContain('.rule-effective-timeline');
  });

  it('keeps later waves and PAYROLL isolated from the WAVE-3 feature', () => {
    const wave3Api = source('features/attendanceSetup/attendanceSetupApi.ts');
    const wave3Demo = source('features/attendanceSetup/attendanceSetupDemo.ts');

    expect(`${wave3Api}\n${wave3Demo}`).not.toMatch(
      /\/(?:payroll|attendance-results|month-close|leave|self-service|reports|dashboard)/i,
    );
  });
});

function source(relativePath: string): string {
  return readFileSync(resolve(sourceRoot, relativePath), 'utf8');
}
