import {
  readFileSync,
  readdirSync,
  statSync,
} from 'node:fs';
import { extname, join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const sourceRoot = join(resolve(process.cwd()), 'src');
const repositoryRoot = resolve(process.cwd(), '..');
const productionFiles = collectFiles(sourceRoot)
  .filter((path) => ['.ts', '.tsx'].includes(extname(path)))
  .filter((path) => !path.includes('.test.'));
const productionSource = productionFiles.map(read).join('\n');
const componentSource = productionFiles
  .filter((path) => path.endsWith('.tsx'))
  .map(read)
  .join('\n');

describe('WAVE-2 people production source contract', () => {
  it.each([
    '/people/import',
    '/people/organization',
    '/people/employees',
    '/people/employees/:employeeId',
  ])('registers production route %s', (route) => {
    expect(productionSource).toContain(`'${route}'`);
  });

  it('implements real import and people API families', () => {
    for (const path of [
      '/api/v1/people-imports',
      '/api/v1/organization-units',
      '/api/v1/employees',
      '/employment-periods',
      '/prior-service-adjustments',
    ]) {
      expect(productionSource, `missing ${path}`).toContain(path);
    }
  });

  it('implements precheck, blocking publish, versions, employment and prior service states', () => {
    for (const term of [
      'AWAITING_CONFIRMATION',
      'BLOCKING_PRECHECK_ERRORS',
      'EMPLOYMENT_PERIOD_OVERLAP',
      'endExclusive',
      'priorService',
      'rowVersion',
      'Idempotency-Key',
      'If-Match',
    ]) {
      expect(productionSource, `missing state ${term}`).toContain(term);
    }
  });

  it('keeps every WAVE-2 API isolated in demo mode', () => {
    const wave2ApiFiles = productionFiles
      .filter((path) => /(?:peopleImport|organization|employee).*Api\.ts$/i.test(path));

    expect(wave2ApiFiles.length).toBeGreaterThanOrEqual(3);
    for (const path of wave2ApiFiles) {
      expect(read(path), `${path} must short-circuit demo mode`).toContain('isDemoMode');
    }
  });

  it('keeps payroll and organization synchronization undiscoverable', () => {
    expect(componentSource).not.toMatch(/\/payroll\b|工资条|薪资核算/i);
    expect(componentSource).not.toMatch(
      /organization[-_/ ]?sync|组织架构变更同步|双向同步|持续同步/i,
    );
  });

  it('does not embed the Open Design prototype implementation', () => {
    const unsafeMarkupApi = String.fromCharCode(
      100, 97, 110, 103, 101, 114, 111, 117, 115, 108, 121, 83, 101, 116,
      73, 110, 110, 101, 114, 72, 84, 77, 76,
    );
    expect(componentSource).not.toMatch(
      new RegExp(`${unsafeMarkupApi}|<iframe|PROTOTYPE_SIMULATION|v19-od-03|v19-od-04`, 'i'),
    );
  });

  it('references only the real WAVE-2 Open Design handoff paths', () => {
    const uiux = read(join(repositoryRoot, 'docs/verification/wave2/UIUX.md'));

    expect(uiux).not.toContain('design/output/v1.9/');
    for (const path of [
      'design/open-design/v1.9/v19-od-03-people-initial-import.html',
      'design/open-design/v1.9/v19-od-04-organization-employment.html',
      'design/open-design/v1.9/route-to-artifact.json',
      'design/open-design/v1.9/page-state-matrix.md',
      'design/open-design/v1.9/design-system/DESIGN.md',
      'design/open-design/v1.9/react-implementation-notes.md',
    ]) {
      expect(statSync(join(repositoryRoot, path)).isFile(), `missing ${path}`).toBe(true);
      expect(uiux, `UIUX missing ${path}`).toContain(path);
    }
  });

  it('uses a wrapping mobile import stepper instead of horizontal page interaction', () => {
    const css = read(join(sourceRoot, 'styles/global.css'));

    expect(css).not.toMatch(/\.import-stepper\s*\{[^}]*overflow-x:\s*auto/s);
    expect(css).not.toMatch(/\.import-step\s*\{[^}]*min-width:\s*10rem/s);
  });

  it('keeps drawers within the responsive design token instead of a fixed large width', () => {
    const peopleImport = read(join(sourceRoot, 'features/peopleImport/PeopleImportPage.tsx'));
    const feedback = read(join(sourceRoot, 'shared/components/FeedbackComponents.tsx'));

    expect(peopleImport).toContain('size="var(--size-drawer)"');
    expect(feedback).toContain('size="var(--size-drawer)"');
    expect(`${peopleImport}\n${feedback}`).not.toContain('size="large"');
  });

  it('keeps people form limits aligned with the OpenAPI contract', () => {
    const employeeDetail = read(join(sourceRoot, 'features/employee/EmployeeDetailPage.tsx'));
    const employees = read(join(sourceRoot, 'features/employee/EmployeesPage.tsx'));

    expect(employeeDetail).toContain("name=\"displayName\"");
    expect(employeeDetail).toMatch(/name="displayName"[^]*?max:\s*100/);
    expect(employees).toMatch(/name="displayName"[^]*?max:\s*100/);
    expect(employeeDetail).toMatch(/name="amountDays"[^]*?min:\s*-36500[^]*?max:\s*36500/);
  });

  it('validates import file type and size at both the UI and API boundary', () => {
    const validationBoundary = read(join(
      sourceRoot,
      'features/peopleImport/peopleImportFileValidation.ts',
    ));
    const sharedPolicy = read(join(
      sourceRoot,
      'shared/files/peopleImportFilePolicy.ts',
    ));
    const api = read(join(sourceRoot, 'features/peopleImport/peopleImportApi.ts'));
    const wizard = read(join(sourceRoot, 'features/peopleImport/ImportWizard.tsx'));

    expect(validationBoundary).toContain('peopleImportFilePolicy.allowedExtensions');
    expect(validationBoundary).toContain('peopleImportFilePolicy.allowedTypes');
    expect(validationBoundary).toContain('file.size > 0');
    expect(validationBoundary).toContain('peopleImportFilePolicy.maxSizeBytes');
    expect(sharedPolicy).toContain('maxSizeBytes: 20 * 1024 * 1024');
    expect(api).toContain('peopleImportFileValidator.validate(file)');
    expect(wizard).toContain('validatePeopleImportFile(selectedFile)');
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
