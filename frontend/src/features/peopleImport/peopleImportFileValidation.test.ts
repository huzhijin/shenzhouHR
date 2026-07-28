import { describe, expect, it } from 'vitest';
import { peopleImportFileValidationPolicy } from './peopleImportTypes';
import { validatePeopleImportFile } from './peopleImportFileValidation';

describe('people import file validation', () => {
  it('accepts a non-empty xlsx file with the approved media type', () => {
    expect(() => validatePeopleImportFile(new File(
      ['workbook'],
      'employees.xlsx',
      { type: peopleImportFileValidationPolicy.allowedTypes[0] },
    ))).not.toThrow();
  });

  it.each([
    sizedFile('employees.xls', 'application/vnd.ms-excel', 1_024),
    sizedFile('employees.xlsx', 'text/plain', 1_024),
    sizedFile('employees.xlsx', peopleImportFileValidationPolicy.allowedTypes[0], 0),
    sizedFile(
      'employees.xlsx',
      peopleImportFileValidationPolicy.allowedTypes[0],
      peopleImportFileValidationPolicy.maxSizeBytes + 1,
    ),
  ])('rejects an invalid upload candidate %#', (file) => {
    expect(() => validatePeopleImportFile(file)).toThrow(expect.objectContaining({
      status: 415,
      code: 'UNSUPPORTED_PEOPLE_IMPORT_FILE',
    }));
  });
});

function sizedFile(name: string, type: string, size: number): File {
  const file = new File(['x'], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}
