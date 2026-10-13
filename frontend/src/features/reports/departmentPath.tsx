import { Fragment, type ReactNode } from 'react';

/** Matches backend `DepartmentPathNames.SEGMENT_BREAK`. */
export const DEPARTMENT_SEGMENT_BREAK = '\u200B';
const SEGMENT_JOIN = `-${DEPARTMENT_SEGMENT_BREAK}`;

export function joinDepartmentSegments(segments: readonly string[]): string {
  return segments
    .map((segment) => segment.trim())
    .filter((segment) => segment.length > 0)
    .join(SEGMENT_JOIN);
}

export function visibleDepartmentPath(value: string | undefined): string {
  if (!value) return '';
  return value.replaceAll(DEPARTMENT_SEGMENT_BREAK, '');
}

export function excelWrappedDepartment(value: string | undefined): string {
  if (!value) return '';
  return value.replaceAll(DEPARTMENT_SEGMENT_BREAK, '\n');
}

export function departmentPathNodes(value: string | undefined): ReactNode {
  if (!value) return value ?? '';
  const parts = value.split(DEPARTMENT_SEGMENT_BREAK);
  if (parts.length === 1) return value;
  return parts.map((part, index) => (
    <Fragment key={`${index}-${part}`}>
      {index > 0 ? <wbr /> : null}
      {part}
    </Fragment>
  ));
}
