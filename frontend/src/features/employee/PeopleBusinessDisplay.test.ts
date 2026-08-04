import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import dayjs from 'dayjs';
import { describe, expect, it } from 'vitest';

import type { OrganizationNode } from '../organization/organizationApi';
import {
  employeeOrganizationName,
  employeeOrganizationOptions,
  toPeriodRequest,
} from './EmployeeDetailPage';

const departmentId = 'department-internal-id';
const companyId = 'company-internal-id';
const organizations: OrganizationNode[] = [{
  organizationId: companyId,
  code: 'SZSC',
  name: '江苏神州半导体科技股份有限公司',
  organizationType: 'COMPANY',
  status: 'ACTIVE',
  sourceOrganizationId: 'source-company-id',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  children: [{
    organizationId: departmentId,
    code: 'HR',
    name: '人力资源部',
    organizationType: 'DEPARTMENT',
    status: 'ACTIVE',
    sourceOrganizationId: 'source-department-id',
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    children: [],
  }],
}];

describe('people management business display', () => {
  it('uses department names as labels while keeping IDs as submitted values', () => {
    const options = employeeOrganizationOptions(organizations);
    const department = options.find((option) => option.value === departmentId);

    expect(department).toMatchObject({
      label: '— 人力资源部',
      value: departmentId,
      disabled: false,
    });
    expect(department?.label).not.toContain(departmentId);
    expect(options.some((option) => option.value === companyId)).toBe(false);
    expect(employeeOrganizationName(organizations, departmentId)).toBe('人力资源部');
  });

  it('never falls back to a raw organization ID for a historic unavailable department', () => {
    const options = employeeOrganizationOptions(
      organizations,
      ['historic-department-id'],
      '部门信息暂不可用',
    );

    expect(options.at(-1)).toEqual({
      label: '部门信息暂不可用',
      value: 'historic-department-id',
      disabled: true,
    });
  });

  it('preserves an existing hidden position value when an employment record is edited', () => {
    const request = toPeriodRequest({
      organizationId: departmentId,
      positionId: 'position-internal-id',
      startDate: dayjs('2026-07-01'),
      terminationDate: null,
      reason: '调整任职部门',
    });

    expect(request).toMatchObject({
      organizationId: departmentId,
      positionId: 'position-internal-id',
      startDate: '2026-07-01',
    });
  });

  it('keeps technical provenance and raw IDs out of ordinary page markup', () => {
    const root = resolve(process.cwd(), 'src/features');
    const employees = readFileSync(resolve(root, 'employee/EmployeesPage.tsx'), 'utf8');
    const employeeDetail = readFileSync(resolve(root, 'employee/EmployeeDetailPage.tsx'), 'utf8');
    const organization = readFileSync(resolve(root, 'organization/OrganizationPage.tsx'), 'utf8');

    expect(employees).not.toContain('<PeopleContextStrip');
    expect(employees).not.toContain('<SourceAuthority');
    expect(employees).toContain('<CompanySelect placeholder="请选择公司" />');
    expect(employeeDetail).not.toContain('<PeopleContextStrip');
    expect(employeeDetail).not.toContain('<SourceAuthority');
    expect(employeeDetail).not.toContain('<VersionAuditPanel');
    expect(employeeDetail).not.toContain('<code>{period.organizationId}</code>');
    expect(employeeDetail).not.toContain('{row.actorId}');
    expect(employeeDetail).not.toContain("label={t('employee.positionId')}");
    expect(employeeDetail).toContain('<Form.Item name="positionId" hidden>');
    expect(organization).not.toContain('<PeopleContextStrip');
    expect(organization).not.toContain('<SourceAuthority');
    expect(organization).not.toContain('<VersionAuditPanel');
    expect(organization).toContain('<CompanySelect placeholder="请选择公司" />');
    expect(organization).not.toContain('node.unit.sourceOrganizationId ?');
  });
});
